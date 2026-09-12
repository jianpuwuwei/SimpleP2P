package com.simple_p2p.p2p;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.ConnectionType;
import com.simple_p2p.signaling.SignalingClient;
import com.simple_p2p.signaling.SignalingMessage;

import java.io.*;
import java.net.*;

/**
 * P2P连接器 - 客户端侧建立到服务端的P2P/中继隧道
 *
 * 客户端连接流程：
 *  1. EasyTier模式：免token，直接请求信令服务器协调打洞；打洞失败自动切到EasyTier的TCP中继节点
 *  2. OpenP2P模式：需要token；先带token向信令服务器验证，然后协调打洞；失败走OpenP2P官方中继
 *  3. 任何模式下，打洞流程失败都会自动切换到对应中继服务器
 *
 *  成功建立后返回ReliableUdpTunnel(提供InputStream/OutputStream)
 */
public class P2PConnector {

    private final ModConfig config;

    public P2PConnector() {
        this.config = ModConfig.getInstance();
    }

    /**
     * 连接结果
     */
    public static class ConnectResult {
        public final boolean success;
        public final ReliableUdpTunnel tunnel; // 成功的隧道
        public final ConnectionType connectionType;
        public final String mode;              // "easytier" | "openp2p"
        public final String errorMessage;

        public ConnectResult(boolean success, ReliableUdpTunnel tunnel, ConnectionType ct, String mode, String err) {
            this.success = success;
            this.tunnel = tunnel;
            this.connectionType = ct;
            this.mode = mode;
            this.errorMessage = err;
        }

        public static ConnectResult ok(ReliableUdpTunnel t, ConnectionType ct, String mode) {
            return new ConnectResult(true, t, ct, mode, null);
        }

        public static ConnectResult fail(String msg) {
            return new ConnectResult(false, null, ConnectionType.NONE, null, msg);
        }
    }

    /**
     * 使用EasyTier模式连接（免token）
     */
    public ConnectResult connectEasyTier(String roomCode) {
        SignalingClient client = new SignalingClient();
        try {
            // 1. 请求信令服务器协调连接
            Object[] r = client.requestConnect(roomCode, "easytier", null);
            String pubIp = (String) r[0];
            int pubPort = (Integer) r[1];
            String prvIp = (String) r[2];
            int prvPort = (Integer) r[3];
            boolean useRelay = (Boolean) r[4];
            String err = (String) r[5];
            if (err != null) {
                client.close();
                return ConnectResult.fail("EasyTier连接失败: " + err);
            }

            ReliableUdpTunnel tunnel;
            ConnectionType ct;

            if (useRelay || pubIp == null) {
                // 信令服务器直接要求走中继
                tunnel = connectEasyTierRelay(roomCode);
                ct = ConnectionType.RELAY;
            } else {
                // 2. 尝试UDP打洞
                UDPHolePuncher puncher = new UDPHolePuncher();
                UDPHolePuncher.PunchResult pr = puncher.punch(pubIp, pubPort, prvIp, prvPort);
                if (pr.success) {
                    tunnel = new ReliableUdpTunnel(pr.socket, pr.remoteAddress);
                    ct = ConnectionType.P2P_DIRECT;
                } else {
                    puncher.close();
                    // 3. 打洞失败，fallback到中继
                    tunnel = connectEasyTierRelay(roomCode);
                    ct = ConnectionType.RELAY;
                }
            }

            if (tunnel == null) {
                client.close();
                return ConnectResult.fail("EasyTier连接失败：打洞和中继均不可用");
            }
            tunnel.start();
            client.close();
            return ConnectResult.ok(tunnel, ct, "easytier");
        } catch (Exception e) {
            client.close();
            return ConnectResult.fail("EasyTier连接异常: " + e.getMessage());
        }
    }

    /**
     * 使用OpenP2P模式连接（需要token）
     */
    public ConnectResult connectOpenP2P(String roomCode, String token) {
        if (token == null || token.trim().isEmpty()) {
            return ConnectResult.fail("OpenP2P模式需要填写Token");
        }
        SignalingClient client = new SignalingClient();
        try {
            // 1. 带token请求连接
            Object[] r = client.requestConnect(roomCode, "openp2p", token);
            String pubIp = (String) r[0];
            int pubPort = (Integer) r[1];
            String prvIp = (String) r[2];
            int prvPort = (Integer) r[3];
            boolean useRelay = (Boolean) r[4];
            String err = (String) r[5];
            if (err != null) {
                client.close();
                return ConnectResult.fail("OpenP2P连接失败: " + err);
            }

            ReliableUdpTunnel tunnel;
            ConnectionType ct;

            if (useRelay || pubIp == null) {
                tunnel = connectOpenP2PRelay(roomCode, token);
                ct = ConnectionType.RELAY;
            } else {
                UDPHolePuncher puncher = new UDPHolePuncher();
                UDPHolePuncher.PunchResult pr = puncher.punch(pubIp, pubPort, prvIp, prvPort);
                if (pr.success) {
                    tunnel = new ReliableUdpTunnel(pr.socket, pr.remoteAddress);
                    ct = ConnectionType.P2P_DIRECT;
                } else {
                    puncher.close();
                    tunnel = connectOpenP2PRelay(roomCode, token);
                    ct = ConnectionType.RELAY;
                }
            }

            if (tunnel == null) {
                client.close();
                return ConnectResult.fail("OpenP2P连接失败：打洞和中继均不可用");
            }
            tunnel.start();
            client.close();
            return ConnectResult.ok(tunnel, ct, "openp2p");
        } catch (Exception e) {
            client.close();
            return ConnectResult.fail("OpenP2P连接异常: " + e.getMessage());
        }
    }

    // ================== 中继连接 ==================

    /**
     * EasyTier中继连接：连接到公共中继服务器，发送 CONNECT 消息，
     * 然后服务器会把TCP socket桥接到目标房间的服务端
     */
    private ReliableUdpTunnel connectEasyTierRelay(String roomCode) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(config.getEasyTierHost(), config.getEasyTierPort()), 5000);
            s.setTcpNoDelay(true);
            SignalingMessage msg = new SignalingMessage(SignalingMessage.TYPE_CONNECT);
            msg.mode = "easytier";
            msg.roomCode = roomCode;
            msg.sendTcp(s);
            // 等待ACK
            SignalingMessage resp = SignalingMessage.receiveTcp(s, 5000);
            if (resp == null || SignalingMessage.TYPE_ERROR.equals(resp.type)) {
                String err = resp != null ? resp.errorMsg : "中继超时";
                try { s.close(); } catch (Exception ignored) {}
                System.err.println("[SimpleP2P] EasyTier中继失败: " + err);
                return null;
            }
            return new ReliableUdpTunnel(s);
        } catch (Exception e) {
            System.err.println("[SimpleP2P] EasyTier中继异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * OpenP2P中继连接：带token
     */
    private ReliableUdpTunnel connectOpenP2PRelay(String roomCode, String token) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(config.getRelayServerHost(), config.getRelayServerPort()), 5000);
            s.setTcpNoDelay(true);
            SignalingMessage msg = new SignalingMessage(SignalingMessage.TYPE_CONNECT);
            msg.mode = "openp2p";
            msg.roomCode = roomCode;
            msg.token = token;
            msg.sendTcp(s);
            SignalingMessage resp = SignalingMessage.receiveTcp(s, 5000);
            if (resp == null || SignalingMessage.TYPE_ERROR.equals(resp.type)) {
                String err = resp != null ? resp.errorMsg : "中继超时";
                try { s.close(); } catch (Exception ignored) {}
                System.err.println("[SimpleP2P] OpenP2P中继失败: " + err);
                return null;
            }
            return new ReliableUdpTunnel(s);
        } catch (Exception e) {
            System.err.println("[SimpleP2P] OpenP2P中继异常: " + e.getMessage());
            return null;
        }
    }
}
