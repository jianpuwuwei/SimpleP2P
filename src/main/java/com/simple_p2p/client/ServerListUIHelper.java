package com.simple_p2p.client;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.proxy.UdpServerProbe;

/**
 * 服务器列表UI交互逻辑辅助类（外部官方客户端版本）。
 *
 * <p>输入房间号 → 直接标记为 P2P 房间；加入时通过 {@link ClientConnectionManager#connectAuto}
 * 调用 EasyTier / OpenP2P 官方客户端组网，返回可连接的本地/虚拟地址。
 *
 * <p>{@link #refreshServer} 仍用 {@link UdpServerProbe} 做本机/局域网 UDP 探测（仅延迟展示用，
 * 不影响外网组网）。
 */
public class ServerListUIHelper {

    private final ModConfig config;
    private final ClientConnectionManager connMgr;

    public ServerListUIHelper() {
        this.config = ModConfig.getInstance();
        this.connMgr = new ClientConnectionManager();
    }

    public ClientConnectionManager getConnectionManager() {
        return connMgr;
    }

    /** 添加服务器：识别房间号则保存到收藏并标记 P2P 房间。 */
    public AddServerResult onAddServer(String displayName, String address) {
        ClientConnectionManager.PreCheckResult pc = connMgr.preCheck(address);
        if (pc.action == ClientConnectionManager.PreAction.HANDLE_PASS) {
            return AddServerResult.passThrough(displayName, address);
        }
        String roomCode = pc.roomCode;
        if (displayName == null || displayName.trim().isEmpty()) displayName = "P2P房间-" + roomCode;
        connMgr.saveServerToFavorite(displayName, roomCode);
        return AddServerResult.savedRoomServer(displayName, roomCode, "?", "EasyTier/OpenP2P");
    }

    /** 刷新单个房间服务器的状态（本机/局域网 UDP 探测，延迟展示用）。 */
    public UdpServerProbe.ProbeResult refreshServer(String roomCode) {
        UdpServerProbe probe = new UdpServerProbe();
        try {
            return probe.probe(roomCode);
        } finally {
            probe.close();
        }
    }

    /** 加入服务器：通过官方客户端组网，返回可连接地址。 */
    public JoinAction onJoinServer(String roomCode) {
        ClientConnectionManager.ConnectFinalResult r = connMgr.connectAuto(roomCode);
        if (r.success) return JoinAction.proxyAddress(r.localProxyHost, r.localProxyPort, r);
        return JoinAction.fail(r.errorMessage);
    }

    public JoinAction joinByEasyTier(String roomCode) {
        ClientConnectionManager.ConnectFinalResult r = connMgr.connectEasyTier(roomCode);
        if (r.success) return JoinAction.proxyAddress(r.localProxyHost, r.localProxyPort, r);
        return JoinAction.fail(r.errorMessage);
    }

    public JoinAction joinByOpenP2P(String roomCode, String tokenOrNullToUseSaved) {
        ClientConnectionManager.ConnectFinalResult r = connMgr.connectOpenP2P(roomCode, tokenOrNullToUseSaved);
        if (r.success) return JoinAction.proxyAddress(r.localProxyHost, r.localProxyPort, r);
        return JoinAction.fail(r.errorMessage);
    }

    public void saveToken(String token) {
        config.setOpenP2PToken(token);
    }

    // ================== 辅助结果类 ==================

    public static class AddServerResult {
        public enum Type {
            PASS_THROUGH, SAVED_ROOM, NEED_OPENP2P_TOKEN, NEED_SELECT_MODE,
            ROOM_NOT_FOUND, SIGNALING_UNREACHABLE
        }
        public final Type type;
        public final String displayName;
        public final String addressOrRoomCode;
        public final String pingText;
        public final String modeText;
        public final boolean supportsEasyTier;
        public final boolean supportsOpenP2P;
        public final boolean hasOpenP2PToken;
        public final String openP2PRegisterUrl;
        public final String errorMessage;

        private AddServerResult(Type type, String displayName, String addressOrRoomCode,
                                String pingText, String modeText,
                                boolean supportsEasyTier, boolean supportsOpenP2P,
                                boolean hasOpenP2PToken, String openP2PRegisterUrl, String err) {
            this.type = type; this.displayName = displayName; this.addressOrRoomCode = addressOrRoomCode;
            this.pingText = pingText; this.modeText = modeText;
            this.supportsEasyTier = supportsEasyTier; this.supportsOpenP2P = supportsOpenP2P;
            this.hasOpenP2PToken = hasOpenP2PToken; this.openP2PRegisterUrl = openP2PRegisterUrl;
            this.errorMessage = err;
        }

        public static AddServerResult passThrough(String name, String addr) {
            return new AddServerResult(Type.PASS_THROUGH, name, addr, null, null,
                    false, false, false, null, null);
        }
        public static AddServerResult savedRoomServer(String name, String code, String ping, String mode) {
            return new AddServerResult(Type.SAVED_ROOM, name, code, ping, mode,
                    true, false, false, null, null);
        }
    }

    public static class JoinAction {
        public enum Type {
            USE_PROXY_ADDRESS, NEED_TOKEN, NEED_SELECT_MODE, FAIL
        }
        public final Type type;
        public final String proxyHost;
        public final int proxyPort;
        public final String mode;
        public final String connectionTypeName;
        public final int pingMs;
        public final String openP2PRegisterUrl;
        public final boolean supportsEasyTier;
        public final boolean supportsOpenP2P;
        public final String pingText;
        public final boolean hasOpenP2PToken;
        public final String errorMessage;
        public final ClientConnectionManager.ConnectFinalResult rawResult;

        private JoinAction(Type type, String host, int port, String mode, String connName, int pingMs,
                           String regUrl, boolean e, boolean o, String pingText, boolean hasToken,
                           String err, ClientConnectionManager.ConnectFinalResult raw) {
            this.type = type; this.proxyHost = host; this.proxyPort = port; this.mode = mode;
            this.connectionTypeName = connName; this.pingMs = pingMs; this.openP2PRegisterUrl = regUrl;
            this.supportsEasyTier = e; this.supportsOpenP2P = o; this.pingText = pingText;
            this.hasOpenP2PToken = hasToken; this.errorMessage = err; this.rawResult = raw;
        }

        public static JoinAction proxyAddress(String host, int port, ClientConnectionManager.ConnectFinalResult r) {
            return new JoinAction(Type.USE_PROXY_ADDRESS, host, port, r.mode,
                    r.connectionType.getDisplayName(), 0, null, false, false, null, false, null, r);
        }

        public static JoinAction fail(String err) {
            return new JoinAction(Type.FAIL, null, 0, null, null, 0,
                    null, false, false, null, false, err, null);
        }
    }
}
