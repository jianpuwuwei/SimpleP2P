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
 * P2P 服务端接受器 - 服务端侧注册房间并等待客户端连入；
 * 客户端连入后通过 {@link ClientConnectedListener} 回调一条可靠隧道，由上层桥接到本地 MC 端口。
 */
public class P2PServerAcceptor {

    public interface ClientConnectedListener {
        /** 当有客户端通过 P2P/中继连接进来时调用；mode 为 easytier/openp2p，type 为 P2P直连/中继。 */
        void onClientConnected(ReliableUdpTunnel tunnel, String mode, ConnectionType type);
    }

    private final ModConfig config;
    private SignalingClient signaling;
    private DatagramSocket punchSocket;
    private ServerSocket easyTierRelayListener;
    private ServerSocket openP2PRelayListener;

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

    /** startEx() 的返回对象：除成败外可带降级警告（如双开无 Token 自动降级为 EasyTier），供上层回显。 */
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
     * <p>Token 校验：OPENP2P_ONLY 无 Token 直接失败；BOTH 无 Token 自动降级为仅 EasyTier 并记入 warnings。 */
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

        // 按降级后实际支持模式注册房间
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

    /** 停止服务端并注销房间。 */
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

    /** 处理一个新通过 UDP 打洞连入的客户端。 */
    private void handleNewClientUdp(InetAddress addr, int port) {
        try {
            // 为该客户端建立基于对端 addr:port 过滤的可靠通道
            InetSocketAddress remote = new InetSocketAddress(addr, port);
            DatagramSocket newSock = cloneUdpSocketFor(punchSocket, remote);
            if (newSock == null) {
                // 兜底：共用 punchSocket（多客户端会竞争，单房间通常 1 对 1）
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
            // Java 不允许两个 Socket 绑定同端口，新建 socket 并 connect(remote) 以定向收发
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
            // 仅做非空校验（生产环境应对接 openp2p token 校验服务）
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
