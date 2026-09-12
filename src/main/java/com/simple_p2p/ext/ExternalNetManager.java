package com.simple_p2p.ext;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.P2PMode;
import com.simple_p2p.signaling.EmbeddedSignaling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 外部官方客户端组网的门面单例。
 *
 * <p>所有下载/组网/就绪轮询都在单线程后台 worker 上执行，结果经 {@code chat} 回调抛回，
 * 绝不阻塞 MC 主线程。提供服务端开/关房、客户端连房（EasyTier→OpenP2P 自动回退）、
 * 关服/JVM 退出清理。
 *
 * <p>同时负责调用 {@link EmbeddedSignaling#registerRoom/unregisterRoom}，仅用于本机/局域网
 * 服务器列表的 UDP 延迟播报（外网联机完全走官方二进制）。
 */
public final class ExternalNetManager {

    public static final ExternalNetManager INSTANCE = new ExternalNetManager();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SimpleP2P-ExtNet");
        t.setDaemon(true);
        return t;
    });

    private final EasyTierManager easyTier = new EasyTierManager();
    private final OpenP2PManager openP2P = new OpenP2PManager();

    private volatile String activeRoomCode;
    private volatile boolean serverRunning;

    private ExternalNetManager() {}

    /** 服务端开启结果。 */
    public static final class ServerStartResult {
        public final boolean ok;
        public final P2PMode effectiveMode;
        public final String[] warnings;
        public final String failReason;
        /** 组网对外地址（如 EasyTier 虚拟IP:端口），用于开房信息展示。 */
        public final String address;
        private ServerStartResult(boolean ok, P2PMode mode, String address, String[] warnings, String fail) {
            this.ok = ok; this.effectiveMode = mode; this.address = address;
            this.warnings = warnings == null ? new String[0] : warnings;
            this.failReason = fail;
        }
        public static ServerStartResult success(P2PMode mode, String address, String... warnings) {
            return new ServerStartResult(true, mode, address, warnings, null);
        }
        public static ServerStartResult fail(String reason) {
            return new ServerStartResult(false, null, null, new String[0], reason);
        }
    }

    /** 客户端连接目标。 */
    public static final class ConnectTarget {
        public final boolean ok;
        public final String host;
        public final int port;
        public final String mode;
        public final String error;
        private ConnectTarget(boolean ok, String host, int port, String mode, String error) {
            this.ok = ok; this.host = host; this.port = port; this.mode = mode; this.error = error;
        }
        public static ConnectTarget ok(String host, int port, String mode) {
            return new ConnectTarget(true, host, port, mode, null);
        }
        public static ConnectTarget fail(String error) {
            return new ConnectTarget(false, null, 0, null, error);
        }
    }

    // ================== 服务端 ==================

    /** 异步开启房间。done 回调返回最终结果；chat 用于进度/日志回聊。 */
    public void startServerAsync(String roomCode, P2PMode mode, int mcPort,
                                 Consumer<ServerStartResult> done, Consumer<String> chat) {
        worker.execute(() -> {
            try {
                ServerStartResult r = startServer(roomCode, mode, mcPort, chat);
                if (done != null) done.accept(r);
            } catch (Exception e) {
                if (done != null) done.accept(ServerStartResult.fail("开房异常: " + e.getMessage()));
            }
        });
    }

    private ServerStartResult startServer(String roomCode, P2PMode mode, int mcPort, Consumer<String> chat) {
        boolean hasToken = ModConfig.getInstance().hasOpenP2PToken();
        boolean reqEasy = mode.supportsEasyTier();
        boolean reqOpen = mode.supportsOpenP2P();
        P2PMode effective = mode;
        java.util.ArrayList<String> warnings = new java.util.ArrayList<>();

        // Token 降级（沿用现有语义）
        if (reqOpen && !hasToken) {
            if (!reqEasy) {
                return ServerStartResult.fail("当前模式为 仅OpenP2P，但服务端未设置 OpenP2P Token，无法启动房间。"
                        + " 请先执行: /p2p settoken <你的Token> 或切换模式: /p2p mode easytier | both");
            }
            reqOpen = false;
            effective = P2PMode.EASYTIER_ONLY;
            warnings.add("服务端未设置 OpenP2P Token，双开(EasyTier + OpenP2P)模式已自动降级为 仅EasyTier。"
                    + " 如需恢复双开，请先 /p2p settoken <Token>，再 /p2p close 后重新 /p2p open。");
        }

        // 先停旧会话
        stopAllInternal();

        boolean easyOk = false;
        boolean openOk = false;
        String address = null;
        if (reqEasy) {
            EasyTierManager.StartResult r = easyTier.startServer(roomCode, mcPort, chat);
            if (r.ok) { easyOk = true; address = r.address; } else { warnings.add("EasyTier 启动失败: " + r.error); }
        }
        if (reqOpen) {
            OpenP2PManager.StartResult r = openP2P.startServer(roomCode, ModConfig.getInstance().getOpenP2PToken(), mcPort, chat);
            if (r.ok) {
                openOk = true;
                if (address == null) {
                    address = "OpenP2P 节点 " + OpenP2PManager.serverNodeName(roomCode);
                }
            } else { warnings.add("OpenP2P 启动失败: " + r.error); }
        }

        if (!easyOk && !openOk) {
            stopAllInternal();
            return ServerStartResult.fail(warnings.isEmpty()
                    ? "房间启动失败" : String.join("\n", warnings));
        }
        activeRoomCode = roomCode;
        serverRunning = true;

        // LAN 播报（服务器列表延迟显示用）
        try {
            EmbeddedSignaling.instance().registerRoom(roomCode, effective, hasToken);
        } catch (Exception ignored) {}
        return ServerStartResult.success(effective, address, warnings.toArray(new String[0]));
    }

    /** 异步关闭房间。 */
    public void stopServerAsync(String roomCode, Consumer<String> chat) {
        worker.execute(() -> {
            stopAllInternal();
            try {
                EmbeddedSignaling.instance().unregisterRoom(roomCode);
            } catch (Exception ignored) {}
            if (chat != null) chat.accept("P2P房间已关闭");
        });
    }

    // ================== 客户端 ==================

    /** 异步连接房间。modeOrAuto：easytier / openp2p / auto（EasyTier→OpenP2P 回退）。 */
    public void connectClientAsync(String roomCode, String modeOrAuto, int mcPort,
                                   Consumer<ConnectTarget> done, Consumer<String> chat) {
        worker.execute(() -> {
            try {
                ConnectTarget t = connectClient(roomCode, modeOrAuto, mcPort, chat);
                if (done != null) done.accept(t);
            } catch (Exception e) {
                if (done != null) done.accept(ConnectTarget.fail("连接异常: " + e.getMessage()));
            }
        });
    }

    private ConnectTarget connectClient(String roomCode, String modeOrAuto, int mcPort, Consumer<String> chat) {
        // 同机自连（本进程正在开同一个房间，如单人世界开房后本进程再连接）：直接连本机服务端，
        // 否则会在同一台机器上再起一个 EasyTier 实例，两块 TUN 虚拟网卡冲突导致连不上。
        if (serverRunning && roomCode != null && roomCode.equals(activeRoomCode)) {
            if (chat != null) chat.accept("检测到本机已开启该房间，直接连接本地服务端");
            return ConnectTarget.ok("127.0.0.1", mcPort, "local");
        }
        // 同机双开判定：只有本机内嵌信令"注册了该房间号"才说明服务端就在本机，才用本地地址直连。
        // 不能简单判断「127.0.0.1:mcPort 是否可达」——本机可能正开着自己的服务端（别的房间号），
        // 那样会把本该连的远程房间错误地指向本机（表现为连到自己服或超时）。
        if (isLocalRoomServer(roomCode)) {
            if (chat != null) chat.accept("检测到本机已开启该房间，直接使用本地地址连接（同机双开）");
            activeRoomCode = roomCode;
            return ConnectTarget.ok("127.0.0.1", mcPort, "local");
        }
        stopAllInternal();
        String mode = modeOrAuto == null ? "auto" : modeOrAuto.toLowerCase();
        String token = ModConfig.getInstance().getOpenP2PToken();

        if ("easytier".equals(mode)) {
            EasyTierManager.ClientTarget t = easyTier.startClient(roomCode, mcPort, chat);
            if (t.ok) { activeRoomCode = roomCode; return ConnectTarget.ok(t.host, t.port, t.mode); }
            return localFallback(roomCode, mcPort, chat, t.error);
        }
        if ("openp2p".equals(mode)) {
            OpenP2PManager.ClientTarget t = openP2P.startClient(roomCode, token, mcPort, chat);
            if (t.ok) { activeRoomCode = roomCode; return ConnectTarget.ok(t.host, t.port, t.mode); }
            return localFallback(roomCode, mcPort, chat, t.error);
        }
        // auto：先 EasyTier，失败且有 token 时回退 OpenP2P
        EasyTierManager.ClientTarget te = easyTier.startClient(roomCode, mcPort, chat);
        if (te.ok) { activeRoomCode = roomCode; return ConnectTarget.ok(te.host, te.port, te.mode); }
        if (token != null && !token.trim().isEmpty()) {
            if (chat != null) chat.accept("EasyTier 连接失败，自动回退 OpenP2P...");
            OpenP2PManager.ClientTarget to = openP2P.startClient(roomCode, token, mcPort, chat);
            if (to.ok) { activeRoomCode = roomCode; return ConnectTarget.ok(to.host, to.port, to.mode); }
            return localFallback(roomCode, mcPort, chat, te.error + "；OpenP2P 也失败: " + to.error);
        }
        return localFallback(roomCode, mcPort, chat, te.error);
    }

    /**
     * 组网失败后的同机兜底：仅当确认本机就是该房间的服务端（本机内嵌信令注册了该房间号）时，
     * 才回退到本地地址；否则一律返回失败，避免把远程房间误连到本机无关的服务端。
     */
    private ConnectTarget localFallback(String roomCode, int mcPort, Consumer<String> chat, String originalError) {
        if (isLocalRoomServer(roomCode)) {
            if (chat != null) {
                chat.accept("组网失败，但检测到本机已开启该房间，改用本地地址直连（同机双开）。"
                        + "提示：同机双开请让两个游戏使用不同的账号名，否则进入时会提示“名称已占用”。");
            }
            activeRoomCode = roomCode;
            return ConnectTarget.ok("127.0.0.1", mcPort, "local");
        }
        return ConnectTarget.fail(originalError);
    }

    /**
     * 判断该房间的服务端是否就是本机：向本机内嵌信令探测该房间号。
     * <p>内嵌信令只对"已注册的房间号"回包，因此收到响应即可确认本机就是该房间的服务端。
     */
    private boolean isLocalRoomServer(String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) return false;
        try (com.simple_p2p.proxy.UdpServerProbe probe = new com.simple_p2p.proxy.UdpServerProbe()) {
            return probe.probeLocal(roomCode).success;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同步连接房间（供旧客户端门面在非主线程调用）。modeOrAuto：easytier/openp2p/auto。 */
    public ConnectTarget connectClientSync(String roomCode, String modeOrAuto, int mcPort, Consumer<String> chat) {
        return connectClient(roomCode, modeOrAuto, mcPort, chat);
    }

    /** 同步启动服务端（供需要阻塞等待的调用方；一般命令层应使用异步版本）。 */
    public ServerStartResult startServerSync(String roomCode, P2PMode mode, int mcPort, Consumer<String> chat) {
        return startServer(roomCode, mode, mcPort, chat);
    }

    // ================== 状态与清理 ==================

    public String statusText() {
        StringBuilder sb = new StringBuilder("==== SimpleP2P 组网状态 ====");
        sb.append("\n房间: ").append(activeRoomCode == null ? "-" : activeRoomCode);
        sb.append("\nEasyTier: ").append(easyTier.isRunning() ? "运行中" : "未运行");
        sb.append("\nOpenP2P: ").append(openP2P.isRunning() ? "运行中" : "未运行");
        if (easyTier.isRunning()) {
            sb.append("\nEasyTier 虚拟IP: ").append(ModConfig.getInstance().getEasyTierServerIp());
        }
        return sb.toString();
    }

    public boolean isServerRunning() { return serverRunning; }
    public String getActiveRoomCode() { return activeRoomCode; }

    /**
     * 停止所有会话（客户端断连/关服/退出/换房）。
     * <p>刻意不提交到 {@code worker} 队列：worker 是单线程，连接过程中会被"等待服务端节点"、
     * "建立端口转发"等长任务占用，若排队执行清理，玩家断开连接时会感觉游戏卡住。
     */
    public void stopAll() {
        Thread t = new Thread(this::stopAllInternal, "SimpleP2P-StopAll");
        t.setDaemon(true);
        t.start();
    }

    private void stopAllInternal() {
        try { easyTier.stop(); } catch (Exception ignored) {}
        try { openP2P.stop(); } catch (Exception ignored) {}
        serverRunning = false;
        activeRoomCode = null;
    }

    /** 注册 JVM 退出钩子（游戏退出时同步终止子进程，避免残留）。 */
    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // 必须同步执行：JVM 关闭时线程池任务可能来不及跑，导致 easytier-core 残留
            try {
                easyTier.stop();
            } catch (Exception ignored) {}
            try {
                openP2P.stop();
            } catch (Exception ignored) {}
        }, "SimpleP2P-Shutdown"));
    }
}
