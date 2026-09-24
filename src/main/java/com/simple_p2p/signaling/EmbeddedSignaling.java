package com.simple_p2p.signaling;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.enums.P2PMode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内嵌（随 MC 进程启动）的轻量信令服务器：TCP 与 {@link SignalingClient} 协议一致，
 * UDP 接受客户端 {@code PROBE roomCode} 并回复 {@code PROBE_OK roomCode modeBits hasToken latencyMs}，
 * 另支持在 255.255.255.255 上收 PROBE 回包作为 LAN 广播兜底。
 */
public class EmbeddedSignaling {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new Gson();
    private static final String PROBE_MAGIC = "PROBE ";
    private static final String PROBE_RESP_PREFIX = "PROBE_OK ";
    private static final EmbeddedSignaling INSTANCE = new EmbeddedSignaling();

    public static EmbeddedSignaling instance() {
        return INSTANCE;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, RoomRegistration> rooms = new ConcurrentHashMap<>();
    private final Map<String, List<JsonObject>> candidateQueues = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private ServerSocket tcpSocket;
    private DatagramSocket udpSocket;
    private int tcpPort;
    private int udpPort;

    public synchronized void ensureStarted() {
        if (running.get()) return;
        int desiredTcp = resolveTcpPort();
        int desiredUdp = ModConfig.embeddedSignalingUdpProbePort();
        try {
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "AIO-EmbeddedSignaling");
                t.setDaemon(true);
                return t;
            });
            tcpSocket = new ServerSocket();
            tcpSocket.setReuseAddress(true);
            try {
                tcpSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), desiredTcp));
            } catch (IOException desiredOccupied) {
                // 期望端口被占用时退让给 JVM 选自由端口，避免 tcpPort=0 导致上层连错端口
                LOGGER.info("[SimpleP2P] EmbeddedSignaling desired tcp port {} occupied, use random free port instead: {}",
                        desiredTcp, desiredOccupied.getMessage());
                tcpSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            }
            this.tcpPort = tcpSocket.getLocalPort();

            udpSocket = new DatagramSocket(null);
            udpSocket.setReuseAddress(true);
            try {
                udpSocket.bind(new InetSocketAddress(ModConfig.udpProbeListenAll()
                        ? new InetSocketAddress(desiredUdp).getAddress()
                        : InetAddress.getLoopbackAddress(), desiredUdp));
            } catch (IOException bindFailed) {
                // 退化为只绑 127 随机
                udpSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            }
            this.udpPort = udpSocket.getLocalPort();

            running.set(true);
            executor.submit(this::tcpAcceptLoop);
            executor.submit(this::udpReceiveLoop);
            if (ModConfig.lanBroadcastEnabled()) {
                executor.submit(this::lanBroadcastReceiveLoop);
            }
            LOGGER.info("[SimpleP2P] EmbeddedSignaling started: tcp=127.0.0.1:{} udp=:{}",
                    this.tcpPort, this.udpPort);
        } catch (IOException e) {
            LOGGER.warn("[SimpleP2P] EmbeddedSignaling start failed, falling back to memory-only registry. msg={}",
                    e.getMessage());
            // 绑定失败进入 memory-only 模式：房间注册/查询走内存兜底，避免上层连错端口
            if (this.tcpPort <= 0) this.tcpPort = 0; // 保持 0，提示上层不要连 TCP
            if (this.udpPort <= 0) this.udpPort = 0;
            if (executor == null) {
                executor = Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "AIO-EmbeddedSignaling-Memory");
                    t.setDaemon(true);
                    return t;
                });
            }
            // memory-only 也置 running=true，使 registerRoom 仍能把房间存入 rooms 表
            running.set(true);
        }
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 服务端执行 /p2p open 时调这里注册房间，让客户端 UDP PROBE 能查到 */
    public void registerRoom(String roomCode, ModeBits mode, boolean hasToken) {
        ensureStarted();
        rooms.put(roomCode, new RoomRegistration(roomCode, mode, hasToken, System.currentTimeMillis()));
        LOGGER.info("[SimpleP2P] EmbeddedSignaling room registered: {} mode={} hasToken={}",
                roomCode, mode, hasToken);
    }

    /** 使用 P2PMode 注册房间（避免外部自行拼 mode bits）。 */
    public void registerRoom(String roomCode, P2PMode mode, boolean hasToken) {
        registerRoom(roomCode, ModeBits.fromP2PMode(mode), hasToken);
    }

    public void unregisterRoom(String roomCode) {
        if (roomCode == null) return;
        rooms.remove(roomCode);
        candidateQueues.remove(roomCode);
    }

    // ---------------- TCP 协议 (与 SignalingClient 对齐) ----------------
    private void tcpAcceptLoop() {
        while (running.get()) {
            try {
                Socket s = tcpSocket.accept();
                executor.submit(() -> handleTcp(s));
            } catch (IOException e) {
                if (running.get()) LOGGER.debug("[SimpleP2P] EmbeddedSignaling tcp accept ex: {}", e.getMessage());
            }
        }
    }

    private void handleTcp(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStreamWriter out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                String resp;
                try {
                    JsonObject req = JsonParser.parseString(line).getAsJsonObject();
                    resp = handleJson(req);
                } catch (Exception parseEx) {
                    JsonObject err = new JsonObject();
                    err.addProperty("type", "error");
                    err.addProperty("message", "bad request: " + parseEx.getMessage());
                    resp = GSON.toJson(err);
                }
                out.write(resp + "\n");
                out.flush();
            }
        } catch (IOException e) {
            LOGGER.debug("[SimpleP2P] EmbeddedSignaling tcp handler ex: {}", e.getMessage());
        }
    }

    private String handleJson(JsonObject req) {
        String type = req.has("type") ? req.get("type").getAsString() : "";
        JsonObject resp = new JsonObject();
        switch (type) {
            case "join_room": {
                String room = stringOr(req, "room_code", "");
                String peer = stringOr(req, "peer_id", UUID.randomUUID().toString().replace("-", ""));
                resp.addProperty("type", "joined");
                resp.addProperty("room_code", room);
                resp.addProperty("peer_id", peer);
                // 把之前缓存的candidates都返回（一次性给到）
                List<JsonObject> cached = candidateQueues.getOrDefault(room, Collections.emptyList());
                resp.add("candidates", GSON.toJsonTree(cached));
                return GSON.toJson(resp);
            }
            case "publish_candidate": {
                String room = stringOr(req, "room_code", "");
                candidateQueues.computeIfAbsent(room, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(req.deepCopy());
                resp.addProperty("type", "published");
                resp.addProperty("room_code", room);
                return GSON.toJson(resp);
            }
            case "fetch_candidates": {
                String room = stringOr(req, "room_code", "");
                resp.addProperty("type", "candidates");
                resp.addProperty("room_code", room);
                List<JsonObject> cached = new ArrayList<>(candidateQueues.getOrDefault(room, Collections.emptyList()));
                resp.add("candidates", GSON.toJsonTree(cached));
                return GSON.toJson(resp);
            }
            case "leave_room": {
                String room = stringOr(req, "room_code", "");
                candidateQueues.remove(room);
                resp.addProperty("type", "left");
                resp.addProperty("room_code", room);
                return GSON.toJson(resp);
            }
            default:
                resp.addProperty("type", "error");
                resp.addProperty("message", "unknown type: " + type);
                return GSON.toJson(resp);
        }
    }

    // ---------------- UDP PROBE (对应 UdpServerProbe / pingRoomCode) ----------------
    private void udpReceiveLoop() {
        byte[] buf = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running.get() && udpSocket != null && !udpSocket.isClosed()) {
            try {
                udpSocket.receive(packet);
                handleProbe(packet, false);
            } catch (SocketTimeoutException e) {
                // ignore
            } catch (IOException e) {
                if (running.get()) LOGGER.debug("[SimpleP2P] EmbeddedSignaling udp ex: {}", e.getMessage());
            }
        }
    }

    private void lanBroadcastReceiveLoop() {
        // 在广播端口单独再开一个 DatagramSocket（绑定 0.0.0.0:udpProbePort 允许收 255.255.255.255）
        try (DatagramSocket lan = new DatagramSocket(null)) {
            lan.setReuseAddress(true);
            lan.setBroadcast(true);
            try {
                lan.bind(new InetSocketAddress("0.0.0.0", ModConfig.embeddedSignalingUdpProbePort()));
            } catch (IOException e) {
                // 被占用就放弃LAN广播
                LOGGER.info("[SimpleP2P] EmbeddedSignaling LAN broadcast bind failed, port={} occupied: {}",
                        ModConfig.embeddedSignalingUdpProbePort(), e.getMessage());
                return;
            }
            byte[] buf = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (running.get() && !lan.isClosed()) {
                try {
                    lan.receive(packet);
                    handleProbe(packet, true);
                } catch (IOException e) {
                    if (running.get()) LOGGER.debug("[SimpleP2P] EmbeddedSignaling lan udp ex: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[SimpleP2P] EmbeddedSignaling LAN socket init failed: {}", e.getMessage());
        }
    }

    private void handleProbe(DatagramPacket packet, boolean fromLan) {
        long recvAt = System.currentTimeMillis();
        String raw = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        if (!raw.startsWith(PROBE_MAGIC)) return;
        String roomCode = raw.substring(PROBE_MAGIC.length()).trim().split("\\s+")[0];
        RoomRegistration room = rooms.get(roomCode);
        if (room == null) {
            // 未注册的房间不回包，避免客户端误判本机就是该房间的服务端
            return;
        }
        int modeBits = room.mode.bits();
        boolean hasToken = room.hasToken;
        long latency = Math.max(1, System.currentTimeMillis() - recvAt);
        // 客户端自行计算 RTT，这里固定填 0
        String body = PROBE_RESP_PREFIX + roomCode + " " + modeBits + " " + (hasToken ? 1 : 0) + " 0";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        DatagramPacket reply = new DatagramPacket(payload, payload.length, packet.getAddress(), packet.getPort());
        try {
            if (udpSocket != null && !udpSocket.isClosed()) udpSocket.send(reply);
        } catch (IOException e) {
            LOGGER.debug("[SimpleP2P] EmbeddedSignaling probe reply send failed (lan={}): {}", fromLan, e.getMessage());
        }
    }

    private int resolveTcpPort() {
        String ep = ModConfig.signalingEndpoint();
        if (ep == null || ep.isBlank()) return ModConfig.embeddedSignalingTcpPort();
        try {
            URI u = URI.create("tcp://" + ep);
            return u.getPort() > 0 ? u.getPort() : ModConfig.embeddedSignalingTcpPort();
        } catch (Exception e) {
            return ModConfig.embeddedSignalingTcpPort();
        }
    }

    private int defaultModeBits() {
        try {
            P2PMode m = ModConfig.getInstance().getServerMode();
            if (m == null) return 3;
            switch (m) {
                case EASYTIER_ONLY: return 1;
                case OPENP2P_ONLY: return 2;
                default: return 3;
            }
        } catch (Exception ignore) {
            return 3;
        }
    }

    private static String stringOr(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    /** 对外仅给调试/日志使用的房间视图 */
    public static final class RoomRegistration {
        public final String roomCode;
        public final ModeBits mode;
        public final boolean hasToken;
        public final long createdAt;

        public RoomRegistration(String roomCode, ModeBits mode, boolean hasToken, long createdAt) {
            this.roomCode = roomCode;
            this.mode = mode;
            this.hasToken = hasToken;
            this.createdAt = createdAt;
        }
    }

    /** 位表示，镜像 ModConfig.ModeBits 以减少包循环依赖 */
    public static final class ModeBits {
        public final int bits;
        public ModeBits(int bits) { this.bits = bits; }
        public int bits() { return bits; }
        public static ModeBits fromP2PMode(P2PMode m) {
            if (m == null) return new ModeBits(3);
            switch (m) {
                case EASYTIER_ONLY: return new ModeBits(1);
                case OPENP2P_ONLY: return new ModeBits(2);
                default: return new ModeBits(3);
            }
        }
    }
}
