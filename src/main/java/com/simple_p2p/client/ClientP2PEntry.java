package com.simple_p2p.client;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.ext.ExternalNetManager;
import com.simple_p2p.proxy.UdpServerProbe;
import com.simple_p2p.util.AddressRecognizer;

/**
 * Mixin 与业务逻辑之间的桥梁（外部官方客户端版本）。
 * <p>
 * ConnectScreenMixin / ServerPingerMixin 调用这里的静态方法，
 * 由 ClientP2PEntry 转发给 ClientConnectionManager / ExternalNetManager，
 * 避免 Mixin 类直接持有大量业务依赖。
 */
public class ClientP2PEntry {

    private static ClientConnectionManager cm;

    private static void ensureInit() {
        if (cm == null) cm = new ClientConnectionManager();
    }

    // ==================== 连接（ConnectScreen Mixin 调用） ====================

    /**
     * 尝试为房间码建立 P2P 组网。
     *
     * @param serverAddress 玩家在服务器地址栏输入的字符串
     * @return 非 null：成功时 host/port 有效；失败时 error 非空。
     *         返回 null 表示"不是房间码，交给 MC 原版流程"。
     */
    public static ConnectResult connect(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) return null;
        AddressRecognizer.RecognizeResult r = AddressRecognizer.recognize(serverAddress);
        if (!r.isRoomCode) return null;

        ensureInit();
        // 一键联机：EasyTier → OpenP2P 自动回退
        ClientConnectionManager.ConnectFinalResult result = cm.connectAuto(r.address);
        if (result.success) {
            return new ConnectResult(true, result.localProxyHost, result.localProxyPort, null);
        }
        return new ConnectResult(false, null, 0,
                result.errorMessage != null ? result.errorMessage : "连接失败");
    }

    // ==================== 探测（ServerPinger Mixin 调用） ====================

    /**
     * 对房间码做 UDP 探测，返回 ping 和 MOTD（本机/局域网场景显示用）。
     *
     * @param serverAddress 玩家在服务器地址栏输入的字符串
     * @return 非 null 表示已处理（房间码）；null 表示不是房间码，交给 MC 原版 ping。
     */
    public static PingResult ping(String serverAddress) {
        if (serverAddress == null || serverAddress.isBlank()) return null;
        AddressRecognizer.RecognizeResult r = AddressRecognizer.recognize(serverAddress);
        if (!r.isRoomCode) return null;

        UdpServerProbe probe = new UdpServerProbe();
        try {
            UdpServerProbe.ProbeResult pr = probe.probe(r.address);
            if (!pr.success) {
                // 跨机联机时服务端位于 NAT 之后，UDP 探测包无法到达，这是**正常现象**，
                // 不能当作错误提示（否则服务器列表里全是红色的"房间码探测超时"，误导玩家）。
                // 延迟改为"连接后测"，房间本身仍然可用（连接走 EasyTier 组网）。
                return new PingResult(true, -1,
                        "\u00a7aP2P\u00a7r | 房间码联机 | \u00a77延迟需连接后测");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("\u00a7aP2P\u00a7r | ");
            sb.append(pr.getModeDescription());
            sb.append(" | \u00a7b").append(pr.getFormattedPing());
            return new PingResult(true, pr.pingMs, sb.toString());
        } finally {
            probe.close();
        }
    }

    // ==================== 结果容器 ====================

    public static final class ConnectResult {
        public final boolean success;
        public final String host;
        public final int port;
        public final String error;

        public ConnectResult(boolean success, String host, int port, String error) {
            this.success = success;
            this.host = host;
            this.port = port;
            this.error = error;
        }
    }

    public static final class PingResult {
        public final boolean handled;
        public final int ping;
        public final String motd;

        public PingResult(boolean handled, int ping, String motd) {
            this.handled = handled;
            this.ping = ping;
            this.motd = motd;
        }
    }
}
