package com.simple_p2p.client;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.ConnectionType;
import com.simple_p2p.ext.ExternalNetManager;
import com.simple_p2p.ext.OpenP2PManager;
import com.simple_p2p.util.AddressRecognizer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端连接管理器（外部官方客户端版本）。
 *
 * <p>输入房间号时，通过 {@link ExternalNetManager} 调用 EasyTier / OpenP2P 官方客户端组网，
 * 就绪后把目标地址（EasyTier 虚拟 IP 或 OpenP2P 本地代理端口）返回给 MC 连接。
 *
 * <p>保留原有方法签名与结果结构，供 {@link ClientP2PEntry} / {@link ServerListUIHelper} 复用，
 * 内部从自研 {@code P2PConnector}+{@code UdpServerProbe} 切换为官方客户端。
 */
public class ClientConnectionManager {

    private final ModConfig config;
    private final AtomicReference<ExternalNetManager.ConnectTarget> lastTarget = new AtomicReference<>();

    public ClientConnectionManager() {
        this.config = ModConfig.getInstance();
    }

    // ================== 关键动作枚举（保留） ==================

    public enum PreAction {
        HANDLE_PASS,
        NEED_TOKEN_FOR_OPENP2P,
        PROMPT_SELECT_MODE,
        CONNECT_EASYTIER_DIRECT,
        CONNECT_OPENP2P_DIRECT,
        ROOM_NOT_FOUND,
        SIGNALING_UNREACHABLE
    }

    public static class PreCheckResult {
        public final PreAction action;
        public final String roomCode;
        public final String originalInput;
        public final String errorMessage;
        public PreCheckResult(PreAction action, String roomCode, String input, String err) {
            this.action = action;
            this.roomCode = roomCode;
            this.originalInput = input;
            this.errorMessage = err;
        }
    }

    public static class ConnectFinalResult {
        public final boolean success;
        public final String localProxyHost;
        public final int localProxyPort;
        public final String mode;
        public final ConnectionType connectionType;
        public final String errorMessage;
        public ConnectFinalResult(boolean success, String host, int port,
                                  String mode, ConnectionType ct, String err) {
            this.success = success;
            this.localProxyHost = host;
            this.localProxyPort = port;
            this.mode = mode;
            this.connectionType = ct;
            this.errorMessage = err;
        }
    }

    // ================== 前置判定 ==================

    /**
     * 识别输入是否为房间号。是房间号 → 直接标记为可连接（具体模式由 connectAuto 决定）；
     * 普通 IP/域名 → 不接管。
     */
    public PreCheckResult preCheck(String userInput) {
        AddressRecognizer.RecognizeResult r = AddressRecognizer.recognize(userInput);
        if (!ModConfig.roomCodeMode() || !r.isRoomCode) {
            return new PreCheckResult(PreAction.HANDLE_PASS, null, userInput, null);
        }
        return new PreCheckResult(PreAction.CONNECT_EASYTIER_DIRECT, r.address, userInput, null);
    }

    // ================== 连接（委托官方客户端，同步阻塞） ==================

    public ConnectFinalResult connectEasyTier(String roomCode) {
        return doConnect(roomCode, "easytier");
    }

    public ConnectFinalResult connectOpenP2P(String roomCode, String tokenOrNull) {
        String token = tokenOrNull;
        if (token == null || token.trim().isEmpty()) token = config.getOpenP2PToken();
        if (token == null || token.trim().isEmpty()) {
            return new ConnectFinalResult(false, null, 0, "openp2p", ConnectionType.NONE, "OpenP2P Token为空，请先填写");
        }
        return doConnect(roomCode, "openp2p");
    }

    /** 一键连接：EasyTier → OpenP2P 自动回退。 */
    public ConnectFinalResult connectAuto(String roomCode) {
        return doConnect(roomCode, "auto");
    }

    /** 按配置里的房间码连接方式组网：auto / easytier / openp2p。 */
    public ConnectFinalResult connectByConfiguredMode(String roomCode) {
        if (ModConfig.openP2PChosenWithoutToken()) {
            ToastHelper.show("SimpleP2P", "未设置 OpenP2P Token，改用 EasyTier");
        }
        String mode = ModConfig.clientConnectMode();
        if ("easytier".equals(mode)) return connectEasyTier(roomCode);
        if ("openp2p".equals(mode)) return connectOpenP2P(roomCode, null);
        return connectAuto(roomCode);
    }

    private ConnectFinalResult doConnect(String roomCode, String mode) {
        int mcPort = config.getServerLocalPort();
        // 组网过程较长（选节点 / 下载核心 / 等待服务端 / 建立转发），把每一步进度
        // 同时弹到右上角：玩家此刻停留在服务器列表或连接界面，看不到聊天栏提示。
        ExternalNetManager.ConnectTarget t = ExternalNetManager.INSTANCE.connectClientSync(
                roomCode, mode, mcPort, msg -> ToastHelper.show("P2P 组网", msg));
        if (t.ok) {
            lastTarget.set(t);
            ConnectionType ct = "openp2p".equals(t.mode) ? ConnectionType.RELAY : ConnectionType.P2P_DIRECT;
            return new ConnectFinalResult(true, t.host, t.port, t.mode, ct, null);
        }
        return new ConnectFinalResult(false, null, 0, mode, ConnectionType.NONE, t.error);
    }

    /** 当前最近一次成功的目标（供回调侧查询）。 */
    public ExternalNetManager.ConnectTarget getLastTarget() {
        return lastTarget.get();
    }

    // ================== 保存/清理 ==================

    public void saveToken(String token) {
        config.setOpenP2PToken(token);
    }

    public void saveServerToFavorite(String displayName, String roomCode) {
        config.addSavedServer(displayName, roomCode);
    }

    public ModConfig getConfig() {
        return config;
    }

    public void closeCurrentConnection() {
        // 客户端断连时停止组网会话
        ExternalNetManager.INSTANCE.stopAll();
    }

    public void close() {
        closeCurrentConnection();
    }
}
