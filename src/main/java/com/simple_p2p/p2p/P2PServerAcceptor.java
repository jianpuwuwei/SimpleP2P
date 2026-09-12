package com.simple_p2p.p2p;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.ConnectionType;
import com.simple_p2p.enums.P2PMode;
import com.simple_p2p.signaling.SignalingClient;
import com.simple_p2p.signaling.SignalingMessage;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P2P服务端接受器 - 服务端侧等待客户端连入
 *
 * 服务端流程：
 *  1. 调用 start(roomCode, mode, mcLocalPort) 开始
 *  2. 注册到信令服务器，告知房间号和支持的模式
 *  3. 绑定本地UDP端口(打洞监听)、监听EasyTier中继端口、监听OpenP2P中继端口
 *  4. 客户端连入后，在 onClientConnected 回调返回一个可靠隧道
 *  5. 由上层（TCPPortProxy）将这个隧道桥接到本地MC的25565端口
 */
public class P2PServerAcceptor {

    public interface ClientConnectedListener {
        /**
         * 当有客户端通过P2P/中继连接进来时调用
         *
         * @param tunnel 已建立的可靠隧道（双向流）
         * @param mode   easytier / openp2p
         * @param type   P2P直连 / 中继
         */
        void onClientConnected(ReliableUdpTunnel tunnel, String mode, ConnectionType type);
    }

    private final ModConfig config;
    private SignalingClient signaling;
    private DatagramSocket punchSocket;  // UDP打洞监听
    private ServerSocket easyTierRelayListener; // EasyTier中继接入监听
    private ServerSocket openP2PRelayListener;  // OpenP2P中继接入监听

    private Thread punchAcceptThread;
    private Thread easyTierThread;
    private Thread openP2PThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ClientConnectedListener listener;
    private int mcLocalPort;

    public P2PServerAcceptor() {
        this.config = ModConfig.getInstance();
    }

    public void setClientConnectedListener(ClientConnectedListener l) {
        this.listener = l;
    }

    /** start() 的返回对象：除了成败，还可能带有 "双开因无 OpenP2P Token 自动降级为 EasyTier"
     * 这类警告行，供上层命令回显给玩家看。 */
    public static class StartResult {
        public final boolean ok;
        public final P2PMode effectiveMode;
        public final String[] warnings; // 可能为空数组
        public final String failReason; // 失败时给出，成功时为 null

        private StartResult(boolean ok, P2PMode effectiveMode, String[] warnings, String failReason) {
            this.ok = ok;
            this.effectiveMode = effectiveMode;
            this.warnings = warnings == null ? new String[0] : warnings;
            this.failReason = failReason;
        }

        public static StartResult success(P2PMode effectiveMode, String... warnings) {
            return new StartResult(true, effectiveMode, warnings, null);
        }

        public static StartResult fail(String reason) {
            return new StartResult(false, null, new String[0], reason);
        }
    }

    /** @deprecated 保留给可能的旧调用；新代码请使用 startEx() 以获得降级告警/失败原因。 */
    @Deprecated
    public synchronized boolean start(String roomCode, P2PMode mode, int mcLocalPort) {
        return startEx(roomCode, mode, mcLocalPort).ok;
    }

    /** 启动房间（新 API，返回警告/失败详情）。
     * <p>Token 一致性校验：
     * <ul>
     *   <li>OPENP2P_ONLY + 无 Token → 直接失败（否则服务端对外宣称支持 OpenP2P 实际跑不起来）。</li>
     *   <li>BOTH + 无 Token → 自动降级为仅 EasyTier，并把降级说明塞进 StartResult.warnings。</li>
     * </ul>
     * 注册到 EmbeddedSignaling 的 modeBits、是否启动 OpenP2P 中继监听，全部按"实际支持模式"决定。
     */
    public synchronized StartResult startEx(String roomCode, P2PMode mode, int mcLocalPort) {
        if (running.get()) return StartResult.success(mode);
        this.mcLocalPort = mcLocalPort;

        boolean hasToken = config.hasOpenP2PToken();
        boolean reqEasy = mode.supportsEasyTier();
        boolean reqOpen = mode.supportsOpenP2P();
        String warn = null;
        P2PMode displayMode = mode;

        if (reqOpen && !hasToken) {
            if (!reqEasy) {
                String reason = "当前模式为 仅OpenP2P，但服务端未设置 OpenP2P Token，无法启动房间。"
                        + " 请先执行: /p2p settoken <你的Token>"
                        + "（注册地址: " + config.getOpenP2PRegisterUrl() + "）"
                        + " 或切换模式: /p2p mode easytier | both";
                System.err.println("[SimpleP2P] " + reason);
                return StartResult.fail(reason);
            }
            // 双开无 token：降级为仅 EasyTier，不再对外宣称 supportOpenP2P=true
            reqOpen = false;
            displayMode = P2PMode.EASYTIER_ONLY;
            warn = "⚠ 服务端未设置 OpenP2P Token，双开(EasyTier + OpenP2P)模式已自动降级为 仅EasyTier。"
                    + " 如需恢复双开，请先 /p2p settoken <Token>，再 /p2p close 后重新 /p2p open。";
            System.out.println("[SimpleP2P] " + warn);
        }

        signaling = new SignalingClient();
        boolean connected = signaling.connect();
        if (!connected) {
            System.out.println("[SimpleP2P] 远端信令不可达，将使用内嵌信令继续启动房间");
        }

        // 使用"降级后实际支持模式"注册房间，绝不再出现"无 token 但对外宣布支持 OpenP2P"的错误语义
        boolean ok = signaling.registerRoom(roomCode, reqEasy, reqOpen, mcLocalPort);
        if (!ok) {
            System.err.println("[SimpleP2P] 注册房间号失败（可能房间号重复或信令服务器错误）");
            signaling.close();
            return StartResult.fail("注册房间号失败（可能房间号重复或信令服务器错误）");
        }

        running.set(true);

        if (reqEasy || reqOpen) startPunchAcceptor();
        if (reqEasy) startEasyTierRelayAcceptor();
        if (reqOpen) startOpenP2PRelayAcceptor();

        System.out.println("[SimpleP2P] P2P房间已启动: 房间号=" + roomCode
                + ", 模式=" + displayMode.getDisplayName()
                + (hasToken ? " (hasOpenP2PToken)" : "")
                + ", MC端口=" + mcLocalPort);
        return StartResult.success(displayMode, warn == null ? new String[0] : new String[]{warn});
    }

    /**
     * 停止服务端，注销房间
     */
    public synchronized void stop(String roomCode) {
        if (!running.compareAndSet(true, false)) return;
        if (signaling != null) {
            signaling.unregisterRoom(roomCode);
            signaling.close();
            signaling = null;
        }
        if (punchSocket != null) try { punchSocket.close(); } catch (Exception ignored) {}
        if (easyTierRelayListener != null) try { easyTierRelayListener.close(); } catch (Exception ignored) {}
        if (openP2PRelayListener != null) try { openP2PRelayListener.close(); } catch (Exception ignored) {}
        if (punchAcceptThread != null) punchAcceptThread.interrupt();
        if (easyTierThread != null) easyTierThread.interrupt();
        if (openP2PThread != null) openP2PThread.interrupt();
        System.out.println("[SimpleP2P] P2P房间已关闭");
    }

    // ================== UDP打洞接受器 ==================

    private void startPunchAcceptor() {
        try {
            // 用固定端口绑定，便于信令服务器知道公网映射
            punchSocket = new DatagramSocket(0); // 随机端口，信令服务器从数据包获取真实公网地址
            punchSocket.setSoTimeout(2000);
            final int localPort = punchSocket.getLocalPort();
            System.out.println("[SimpleP2P] 打洞UDP监听端口: " + localPort);

            punchAcceptThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    while (running.get() && !punchSocket.isClosed()) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            punchSocket.receive(p);
                        } catch (SocketTimeoutException e) {
                            continue;
                        } catch (IOException e) {
                            break;
                        }
                        String json = new String(p.getData(), 0, p.getLength());
                        SignalingMessage msg = SignalingMessage.fromJson(json);
                        if (msg == null) continue;

                        if (SignalingMessage.TYPE_PUNCH.equals(msg.type)) {
                            // 收到客户端打洞包，回复ACK
                            SignalingMessage ack = new SignalingMessage(SignalingMessage.TYPE_PUNCH_ACK);
                            try {
                                ack.sendUdp(punchSocket, p.getAddress(), p.getPort());
                            } catch (IOException ignored) {}

                            // 将这个socket和对端地址封装成可靠隧道，回调给上层
                            handleNewClientUdp(p.getAddress(), p.getPort());
                        }
                    }
                } catch (Exception e) {
                    if (running.get())
                        System.err.println("[SimpleP2P] 打洞接收器异常: " + e.getMessage());
                }
            }, "AIO-Server-Punch");
            punchAcceptThread.setDaemon(true);
            punchAcceptThread.start();
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 启动打洞监听失败: " + e.getMessage());
        }
    }

    /**
     * 处理一个新通过UDP打洞连入的客户端
     */
    private void handleNewClientUdp(InetAddress addr, int port) {
        try {
            // 复制一个新的DatagramSocket继续使用原通道（复用punchSocket的绑定）
            // 这里简化：直接使用原punchSocket建立可靠通道（基于对端addr+port过滤）
            // 为避免并发冲突，为该客户端创建专用包装
            InetSocketAddress remote = new InetSocketAddress(addr, port);
            DatagramSocket newSock = cloneUdpSocketFor(punchSocket, remote);
            if (newSock == null) {
                // fallback：直接共用punchSocket（但会和其他客户端竞争，单房间通常1对1可接受）
                newSock = punchSocket;
            }
            ReliableUdpTunnel tunnel = new ReliableUdpTunnel(newSock, remote);
            tunnel.start();
            fireConnected(tunnel, "punch", ConnectionType.P2P_DIRECT);
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 接受UDP打洞客户端失败: " + e.getMessage());
        }
    }

    private DatagramSocket cloneUdpSocketFor(DatagramSocket orig, InetSocketAddress remote) {
        try {
            // Java不允许两个Socket绑定同端口，这里只能：
            // 要么让reliable tunnel基于同一个orig socket但定向输出到remote(connect)
            DatagramSocket s = new DatagramSocket();
            s.connect(remote); // connect()后只接受/发送给这个remote
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    // ================== EasyTier中继监听器 ==================

    private void startEasyTierRelayAcceptor() {
        try {
            easyTierRelayListener = new ServerSocket(0);
            final int port = easyTierRelayListener.getLocalPort();
            System.out.println("[SimpleP2P] EasyTier中继本地监听端口: " + port);

            easyTierThread = new Thread(() -> {
                try {
                    while (running.get() && !easyTierRelayListener.isClosed()) {
                        Socket client = easyTierRelayListener.accept();
                        handleRelayClient(client, "easytier");
                    }
                } catch (Exception e) {
                    if (running.get())
                        System.err.println("[SimpleP2P] EasyTier中继监听异常: " + e.getMessage());
                }
            }, "AIO-Server-ET-Relay");
            easyTierThread.setDaemon(true);
            easyTierThread.start();
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 启动EasyTier中继监听失败: " + e.getMessage());
        }
    }

    // ================== OpenP2P中继监听器 ==================

    private void startOpenP2PRelayAcceptor() {
        try {
            openP2PRelayListener = new ServerSocket(0);
            final int port = openP2PRelayListener.getLocalPort();
            System.out.println("[SimpleP2P] OpenP2P中继本地监听端口: " + port);

            openP2PThread = new Thread(() -> {
                try {
                    while (running.get() && !openP2PRelayListener.isClosed()) {
                        Socket client = openP2PRelayListener.accept();
                        // 验证token
                        handleOpenP2PRelayClient(client);
                    }
                } catch (Exception e) {
                    if (running.get())
                        System.err.println("[SimpleP2P] OpenP2P中继监听异常: " + e.getMessage());
                }
            }, "AIO-Server-OP-Relay");
            openP2PThread.setDaemon(true);
            openP2PThread.start();
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 启动OpenP2P中继监听失败: " + e.getMessage());
        }
    }

    private void handleRelayClient(Socket client, String mode) {
        try {
            client.setTcpNoDelay(true);
            // 读取首行CONNECT消息
            SignalingMessage msg = SignalingMessage.receiveTcp(client, 3000);
            if (msg == null || !SignalingMessage.TYPE_CONNECT.equals(msg.type)) {
                // 非法请求，直接关闭
                sendErrorAndClose(client, "非法请求");
                return;
            }
            // 回ACK
            SignalingMessage ack = new SignalingMessage(SignalingMessage.TYPE_ACK);
            ack.sendTcp(client);

            ReliableUdpTunnel tunnel = new ReliableUdpTunnel(client);
            tunnel.start();
            fireConnected(tunnel, mode, ConnectionType.RELAY);
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
            System.err.println("[SimpleP2P] 处理中继客户端失败: " + e.getMessage());
        }
    }

    private void handleOpenP2PRelayClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            SignalingMessage msg = SignalingMessage.receiveTcp(client, 3000);
            if (msg == null || !SignalingMessage.TYPE_CONNECT.equals(msg.type)) {
                sendErrorAndClose(client, "非法请求");
                return;
            }
            // 验证token（服务端配置里可以没有token，这里只做非空校验；生产环境应对接openp2p token校验服务）
            if (msg.token == null || msg.token.trim().isEmpty()) {
                sendErrorAndClose(client, "OpenP2P需要token认证");
                return;
            }
            SignalingMessage ack = new SignalingMessage(SignalingMessage.TYPE_ACK);
            ack.sendTcp(client);
            ReliableUdpTunnel tunnel = new ReliableUdpTunnel(client);
            tunnel.start();
            fireConnected(tunnel, "openp2p", ConnectionType.RELAY);
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
            System.err.println("[SimpleP2P] 处理OpenP2P客户端失败: " + e.getMessage());
        }
    }

    private void sendErrorAndClose(Socket s, String err) {
        try {
            SignalingMessage em = new SignalingMessage(SignalingMessage.TYPE_ERROR);
            em.errorMsg = err;
            em.sendTcp(s);
        } catch (Exception ignored) {}
        try { s.close(); } catch (Exception ignored) {}
    }

    private void fireConnected(ReliableUdpTunnel tunnel, String mode, ConnectionType ct) {
        System.out.println("[SimpleP2P] 新客户端接入: mode=" + mode + ", connection=" + ct.getDisplayName());
        ClientConnectedListener l = listener;
        if (l != null) {
            try {
                l.onClientConnected(tunnel, mode, ct);
            } catch (Exception e) {
                System.err.println("[SimpleP2P] 回调处理客户端连接失败: " + e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
