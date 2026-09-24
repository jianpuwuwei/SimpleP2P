package com.simple_p2p.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 兼容旧版 SignalingClient 调用方的适配器：把 connect()/registerRoom()/unregisterRoom()/
 * requestConnect() 这类 Object[] 旧 API 映射到新的基于 JsonObject 的 call()。
 */
public class SignalingClientLegacy {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    private final SignalingClient delegate;

    public SignalingClientLegacy() {
        this.delegate = new SignalingClient();
    }

    public SignalingClientLegacy(SignalingClient delegate) {
        this.delegate = delegate == null ? new SignalingClient() : delegate;
    }

    public SignalingClient delegate() { return delegate; }

    /** 旧 API：连接信令服务器。EmbeddedSignaling 已运行时直接返回 true，否则做一次轻量探测。 */
    public boolean connect() {
        try {
            if (EmbeddedSignaling.instance().isRunning()) return true;
            JsonObject req = new JsonObject();
            req.addProperty("type", "join_room");
            req.addProperty("room_code", "");
            req.addProperty("peer_id", "probe-" + Long.toHexString(System.nanoTime()));
            JsonObject resp = delegate.call(req);
            return resp != null && !"error".equals(resp.has("type") ? resp.get("type").getAsString() : "");
        } catch (Exception e) {
            LOGGER.warn("[SimpleP2P] SignalingClient.connect() failed (will still try memory-only register): {}",
                    String.valueOf(e.getMessage()));
            return false;
        }
    }

    /** 服务端调用：注册房间。新实现同时在 EmbeddedSignaling 里 registerRoom，让 UDP PROBE 能查到。 */
    public boolean registerRoom(String roomCode, boolean supportsEasyTier, boolean supportsOpenP2P, int mcLocalPort) {
        try {
            // 1) 到远端/回退的信令注册
            JsonObject req = new JsonObject();
            req.addProperty("type", "join_room");
            req.addProperty("room_code", roomCode == null ? "" : roomCode);
            req.addProperty("peer_id", "server-" + Long.toHexString(System.nanoTime()));
            req.addProperty("supports_easytier", supportsEasyTier);
            req.addProperty("supports_openp2p", supportsOpenP2P);
            req.addProperty("mc_local_port", mcLocalPort);
            JsonObject resp = delegate.call(req);
            boolean tcpOk = resp != null && !"error".equals(resp.has("type") ? resp.get("type").getAsString() : "x");
            // 2) 本地内嵌信令显式 registerRoom，使客户端 LAN/loopback PROBE 能拿到模式/延迟
            EmbeddedSignaling.instance().registerRoom(
                    roomCode,
                    EmbeddedSignaling.ModeBits.fromP2PMode(toMode(supportsEasyTier, supportsOpenP2P)),
                    com.simple_p2p.config.ModConfig.openP2PTokenPresent());
            return tcpOk;
        } catch (Exception e) {
            // 远端信令失败时，只要本地内嵌注册成功就允许继续
            try {
                EmbeddedSignaling.instance().registerRoom(
                        roomCode,
                        EmbeddedSignaling.ModeBits.fromP2PMode(toMode(supportsEasyTier, supportsOpenP2P)),
                        com.simple_p2p.config.ModConfig.openP2PTokenPresent());
                LOGGER.info("[SimpleP2P] SignalingClientLegacy.registerRoom fallback to embedded only: room={}",
                        roomCode);
                return true;
            } catch (Exception fatal) {
                LOGGER.warn("[SimpleP2P] SignalingClientLegacy.registerRoom failed: {} / {}",
                        e.getMessage(), fatal.getMessage());
                return false;
            }
        }
    }

    public void unregisterRoom(String roomCode) {
        if (roomCode == null) return;
        try {
            JsonObject req = new JsonObject();
            req.addProperty("type", "leave_room");
            req.addProperty("room_code", roomCode);
            delegate.call(req);
        } catch (Exception ignore) {}
        EmbeddedSignaling.instance().unregisterRoom(roomCode);
    }

    /** 客户端调用：请求连接协商，返回 [公网ip, 公网port, 私网ip, 私网port, useRelay, errMsg]；失败填错误到最后一格。 */
    public Object[] requestConnect(String roomCode, String mode, String tokenIfOpenP2P) {
        Object[] def = new Object[] { null, 0, null, 0, Boolean.FALSE, "连接未建立" };
        try {
            JsonObject req = new JsonObject();
            req.addProperty("type", "join_room");
            req.addProperty("room_code", roomCode == null ? "" : roomCode);
            req.addProperty("peer_id", "client-" + mode + "-" + Long.toHexString(System.nanoTime()));
            req.addProperty("connect_mode", mode);
            if (tokenIfOpenP2P != null) req.addProperty("token", tokenIfOpenP2P);
            JsonObject resp = delegate.call(req);
            if (resp == null) {
                def[5] = "信令无响应";
                return def;
            }
            String t = resp.has("type") ? resp.get("type").getAsString() : "error";
            if ("error".equals(t)) {
                def[5] = resp.has("message") ? resp.get("message").getAsString() : "信令错误";
                return def;
            }
            // 内嵌信令不提供公网打洞信息，这里用 loopback/最近 responder 地址填回，保证 HolePuncher 有确定 target
            String peerIp = findResponderIpOrDefault(roomCode);
            int peerPort = resolvePeerPort(roomCode);
            boolean useRelay = "relay".equals(resp.has("hint") ? resp.get("hint").getAsString() : "");
            return new Object[] { peerIp, peerPort, peerIp, peerPort, useRelay, null };
        } catch (Exception e) {
            // 保持兼容：把 exception 吸收为 errMsg，让 P2PConnector 继续走中继分支
            def[5] = e.getMessage() == null ? "信令异常" : e.getMessage();
            return def;
        }
    }

    public void close() { delegate.close(); }

    // ---------------- helpers ----------------

    private static com.simple_p2p.enums.P2PMode toMode(boolean e, boolean o) {
        if (e && o) return com.simple_p2p.enums.P2PMode.BOTH;
        if (e) return com.simple_p2p.enums.P2PMode.EASYTIER_ONLY;
        if (o) return com.simple_p2p.enums.P2PMode.OPENP2P_ONLY;
        return com.simple_p2p.enums.P2PMode.BOTH;
    }

    private static String findResponderIpOrDefault(String roomCode) {
        // 直接再探测一次 UdpServerProbe，取命中的 responder（通常就是服务端本身）
        try {
            com.simple_p2p.proxy.UdpServerProbe p = new com.simple_p2p.proxy.UdpServerProbe();
            try {
                com.simple_p2p.proxy.UdpServerProbe.ProbeResult r = p.probe(roomCode);
                if (r != null && r.success && r.responderAddress != null) {
                    int colon = r.responderAddress.lastIndexOf(':');
                    if (colon > 0) return r.responderAddress.substring(0, colon);
                    return r.responderAddress;
                }
            } finally { p.close(); }
        } catch (Exception ignore) {}
        return "127.0.0.1";
    }

    private static int resolvePeerPort(String roomCode) {
        // 返回约定的打洞端口（同机/同局域网时即 responder 端口），交由上层建隧道
        try {
            com.simple_p2p.proxy.UdpServerProbe p = new com.simple_p2p.proxy.UdpServerProbe();
            try {
                com.simple_p2p.proxy.UdpServerProbe.ProbeResult r = p.probe(roomCode);
                if (r != null && r.success && r.responderAddress != null) {
                    int colon = r.responderAddress.lastIndexOf(':');
                    if (colon > 0 && colon < r.responderAddress.length() - 1) {
                        try { return Integer.parseInt(r.responderAddress.substring(colon + 1)); }
                        catch (NumberFormatException ignore) {}
                    }
                }
            } finally { p.close(); }
        } catch (Exception ignore) {}
        return 25565;
    }
}
