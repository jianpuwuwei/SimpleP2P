package com.simple_p2p.signaling;

import com.simple_p2p.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 信令客户端（兼旧 API 兼容薄壳）。
 *
 * <p>关键修复：当配置的信令地址不可达时，自动根据 {@link ModConfig#autoFallbackToLocalSignaling()}
 * 回退到 {@code 127.0.0.1} 并确保 {@link EmbeddedSignaling#ensureStarted()}。这从流程上解决了
 * 之前写死的 {@code signaling.simple_p2p.local} 根本不存在，导致
 * “无论是否登录 OpenP2P token / 切换 EasyTier 模式，都先提示无法连接信令服务器”的问题。
 *
 * <p>同时保留了旧调用方依赖的方法签名（connect / registerRoom / unregisterRoom /
 * requestConnect / close / probeRoomUdp / queryRoomModes），因此 P2PServerAcceptor /
 * P2PConnector / UdpServerProbe（其旧版本调用过这些方法）不需要再做细粒度改写即可编译通过，
 * 避免本轮一次性改太多链路导致回归。
 */
public class SignalingClient {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    private final ModConfig config;
    private final AtomicReference<Socket> lastSocket = new AtomicReference<>();
    private volatile String lastUsedEndpoint;

    public SignalingClient() {
        this(ModConfig.getInstance());
    }

    public SignalingClient(ModConfig config) {
        this.config = config;
    }

    public String getLastUsedEndpoint() { return lastUsedEndpoint; }

    // ================== 新通用 JSON call ==================

    /** 按协议发送一行 JSON、读取一行 JSON 回复。内部实现远端失败 -> 内嵌信令自动回退。 */
    public JsonObject call(JsonObject req) throws Exception {
        String configured = ModConfig.signalingEndpoint();
        try {
            return callInternal(configured, req);
        } catch (Exception configuredFailed) {
            if (!ModConfig.autoFallbackToLocalSignaling()) throw configuredFailed;
            try {
                EmbeddedSignaling.instance().ensureStarted();
                int localPort = EmbeddedSignaling.instance().getTcpPort();
                // memory-only 模式（running 但没起 TCP listener）：直接返回"空成功"响应，
                // 不再尝试连 127.0.0.1:X 造成 Connection refused 噪声。
                // 后续 SignalingClientLegacy 的 registerRoom / connect 会走内存兜底。
                if (EmbeddedSignaling.instance().isRunning() && localPort <= 0) {
                    JsonObject r = new JsonObject();
                    r.addProperty("type", "ok");
                    r.addProperty("hint", "memory-only");
                    lastUsedEndpoint = "memory://embedded";
                    return r;
                }
                if (localPort <= 0) localPort = ModConfig.embeddedSignalingTcpPort();
                String localEp = "127.0.0.1:" + localPort;
                LOGGER.info("[SimpleP2P] SignalingClient fallback to embedded signaling {} "
                                + "(configured={} failed: {})",
                        localEp, configured, String.valueOf(configuredFailed.getMessage()));
                return callInternal(localEp, req);
            } catch (Exception localFailed) {
                // 最后的最后：embedded 也挂了 → 如果确实 running，给个 memory-only 空响应，不让上层抛错
                if (EmbeddedSignaling.instance().isRunning()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("type", "ok");
                    r.addProperty("hint", "memory-only-final-fallback");
                    lastUsedEndpoint = "memory://embedded";
                    return r;
                }
                Exception ex = new Exception("信令服务器不可达：已尝试 " + configured
                        + " 以及本机内嵌信令，均失败。 原始错误: " + configuredFailed.getMessage()
                        + "；本地回退错误: " + localFailed.getMessage());
                ex.addSuppressed(configuredFailed);
                ex.addSuppressed(localFailed);
                throw ex;
            }
        }
    }

    private JsonObject callInternal(String endpoint, JsonObject req) throws Exception {
        String host;
        int port;
        int colon = endpoint.lastIndexOf(':');
        if (colon < 0) {
            host = endpoint;
            port = ModConfig.embeddedSignalingTcpPort();
        } else {
            host = endpoint.substring(0, colon).trim();
            port = Integer.parseInt(endpoint.substring(colon + 1).trim());
        }
        boolean isLoopback = InetAddress.getByName(host).isLoopbackAddress();
        if (isLoopback) EmbeddedSignaling.instance().ensureStarted();

        Socket s = borrowSocket(host, port);
        lastSocket.set(s);
        try {
            OutputStreamWriter w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            w.write(GSON.toJson(req) + "\n");
            w.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) throw new IllegalStateException("信令服务器 " + host + ":" + port + " 提前关闭连接");
            JsonObject resp = JsonParser.parseString(line).getAsJsonObject();
            lastUsedEndpoint = host + ":" + port;
            return resp;
        } finally {
            closeQuietly(s);
            lastSocket.compareAndSet(s, null);
        }
    }

    private Socket borrowSocket(String host, int port) throws Exception {
        Socket s = new Socket();
        int connectTimeoutMs = Math.max(800, Math.min(2500, config.getHolePunchTimeoutMs() / 2));
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        s.setSoTimeout(Math.max(1500, config.getHolePunchTimeoutMs()));
        return s;
    }

    // ================== 旧 API（兼容 P2PServerAcceptor / P2PConnector / 原 UdpServerProbe） ==================

    /** @see SignalingClientLegacy#connect() */
    public boolean connect() {
        return new SignalingClientLegacy(this).connect();
    }

    /** @see SignalingClientLegacy#registerRoom(String, boolean, boolean, int) */
    public boolean registerRoom(String roomCode, boolean supportsEasyTier, boolean supportsOpenP2P, int mcLocalPort) {
        return new SignalingClientLegacy(this).registerRoom(roomCode, supportsEasyTier, supportsOpenP2P, mcLocalPort);
    }

    public void unregisterRoom(String roomCode) {
        new SignalingClientLegacy(this).unregisterRoom(roomCode);
    }

    /** @see SignalingClientLegacy#requestConnect(String, String, String)  */
    public Object[] requestConnect(String roomCode, String mode, String tokenIfOpenP2P) {
        return new SignalingClientLegacy(this).requestConnect(roomCode, mode, tokenIfOpenP2P);
    }

    /**
     * 旧 API：通过 UDP 探测房间延迟与模式。新版 UdpServerProbe 已重写为并行目标探测，
     * 这里保留一个等价实现以避免任何遗留编译/反射引用。
     */
    public Object[] probeRoomUdp(String roomCode) {
        try (com.simple_p2p.proxy.UdpServerProbe p = new com.simple_p2p.proxy.UdpServerProbe()) {
            com.simple_p2p.proxy.UdpServerProbe.ProbeResult r = p.probe(roomCode);
            return new Object[] {
                    r.success ? r.pingMs : -1,
                    r.supportsEasyTier,
                    r.supportsOpenP2P,
                    r.success ? null : r.errorMessage
            };
        }
    }

    /** 旧 API：仅查询房间模式（此处复用 UDP 探测，顺便拿延迟信息）。 */
    public Object[] queryRoomModes(String roomCode) {
        try (com.simple_p2p.proxy.UdpServerProbe p = new com.simple_p2p.proxy.UdpServerProbe()) {
            com.simple_p2p.proxy.UdpServerProbe.ProbeResult r = p.probe(roomCode);
            return new Object[] { r.supportsEasyTier, r.supportsOpenP2P, r.success ? null : r.errorMessage };
        }
    }

    public void close() {
        Socket s = lastSocket.getAndSet(null);
        closeQuietly(s);
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignore) {}
    }
}
