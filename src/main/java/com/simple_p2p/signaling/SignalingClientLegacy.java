package com.simple_p2p.signaling;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 兼容旧版 SignalingClient 调用方的适配器（旧代码大量使用 connect()/registerRoom()/
 * unregisterRoom()/requestConnect() 这类 Object[] 返回 API；本次我们把 SignalingClient 改成了
 * 基于 JsonObject 的通用 call() 并加入自动回退）。这里提供一个“旧 API -> 新 call()”的稳定薄壳，
 * 避免 P2PServerAcceptor / P2PConnector 出现 NoSuchMethodError，同时所有客户端/服务端连接都自动
 * 获得“远端失败 -> 127.0.0.1 + EmbeddedSignaling”的兜底能力，从根上解决
 * “无论 token/模式如何都提示无法连接 signaling.simple_p2p.local”。
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

    /**
     * 旧 API：连接到信令服务器。
     *
     * <p>对本 mod 现有的 "EmbeddedSignaling 内存注册表 + UDP PROBE" 架构而言，TCP 信令通道不是必须的。
     * 因此这里做了两级短路：
     * <ol>
     *   <li>若 EmbeddedSignaling.instance().isRunning() → 直接返回 true，不再发探测包，
     *       消除 "127.0.0.1:已占用端口" 的 Connection refused 噪音；</li>
     *   <li>否则走一次 delegate.call() 轻量探测（已由 SignalingClient 吸收远端/内嵌错误，
     *       极端失败也只会返回 false，不会中断起房间流程）。</li>
     * </ol>
     */
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
            // 2) 本地内嵌信令里显式 registerRoom -> 解决客户端 LAN/loopback PROBE 能出模式/延迟
            EmbeddedSignaling.instance().registerRoom(
                    roomCode,
                    EmbeddedSignaling.ModeBits.fromP2PMode(toMode(supportsEasyTier, supportsOpenP2P)),
                    com.simple_p2p.config.ModConfig.openP2PTokenPresent());
            return tcpOk;
        } catch (Exception e) {
            // 即使远端信令挂了，只要本地内嵌注册成功也允许继续（否则就会出现用户描述的
            // “/p2p open 立即提示无法连接信令服务器，房间起不来，后续 EasyTier / OpenP2P 也无从谈起”）
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

    /** 客户端调用：请求连接协商（返回 公网ip/公网port/私网ip/私网port/useRelay/errMsg）。
     *  失败时不直接抛异常，而是把错误填到最后一格，保持 Object[] 签名兼容 P2PConnector。 */
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
            // 内嵌信令只负责房间/candidate元数据，不直接给出公网打洞信息；对于同机/同局域网，
            // 直接把 loopback / 最近 responder 的地址作为“公网/私网”信息填回去，
            // 让后续 HolePuncher 有确定的 target 而不是 null。
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
        // 如果客户端做过 UDP PROBE，就用最近 PROBE 命中的 responder（通常就是服务端本身）。
        // 这里做一个简版：直接从 UdpServerProbe 再探测一次（耗时 ~1s 内）。
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
        // 回退到 MC 本地端口（服务端侧）：如果在同机同进程 / 同局域网，MC 的 25565 是可达的；
        // 我们这里不直接代理到 25565，而是让上层的 P2PConnector / UDPHolePuncher 基于返回的 IP
        // 建立一个可靠 UDP 隧道，再由 LocalTcpProxy 把 127.0.0.1:random 转到 MC。这里返回一个
        // 约定的打洞端口即可。
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
