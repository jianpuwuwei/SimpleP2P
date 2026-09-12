package com.simple_p2p.proxy;

import com.simple_p2p.config.ModConfig;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * UDP 服务器探测：
 * <p>
 * 客户端对“输入的非 IP 字符串 = 房间码”服务器时，发 PROBE roomCode UDP 包，
 * 服务端（运行 MC + 开启 /p2p open 的进程）通过 {@link com.simple_p2p.signaling.EmbeddedSignaling}
 * 回复 {@code PROBE_OK roomCode modeBits hasToken serverStampMs}。
 *
 * <p>关键修复：探测阶段不再先依赖远端 TCP 信令，直接使用 UDP 并行发 4 个候选地址：
 * <ol>
 *     <li>ModConfig.signalingServerHost : embeddedSignalingUdpProbePort（配置里给的）</li>
 *     <li>127.0.0.1 : embeddedSignalingUdpProbePort（本机内嵌，默认兜底）</li>
 *     <li>255.255.255.255 : embeddedSignalingUdpProbePort（LAN 广播）</li>
 *     <li>（可选）若服务端地址本身是 IP/Host，则直接发给它</li>
 * </ol>
 * 第一个回包就当作结果，避免连接不到不存在的公共信令导致“任何模式都先提示信令服务器不可达”。
 */
public class UdpServerProbe implements AutoCloseable {

    public static final String PROBE_PREFIX = "PROBE ";
    public static final String PROBE_OK_PREFIX = "PROBE_OK ";

    private final ModConfig config;

    public UdpServerProbe() {
        this.config = ModConfig.getInstance();
    }

    public static final class ProbeResult {
        public final boolean success;
        public final int pingMs;
        public final boolean supportsEasyTier;
        public final boolean supportsOpenP2P;
        public final boolean hasOpenP2PToken;
        public final String errorMessage;
        /** 响应包实际来自的地址；用于 UI 调试展示，null 表示未收到回包。 */
        public final String responderAddress;

        public ProbeResult(boolean success, int pingMs, boolean e, boolean o, boolean hasToken, String err, String responder) {
            this.success = success;
            this.pingMs = pingMs;
            this.supportsEasyTier = e;
            this.supportsOpenP2P = o;
            this.hasOpenP2PToken = hasToken;
            this.errorMessage = err;
            this.responderAddress = responder;
        }

        public String getFormattedPing() {
            if (!success) return "?";
            if (pingMs < 0) return "-";
            return Math.max(1, pingMs) + "ms";
        }

        public String getModeDescription() {
            if (!success) return "未知模式";
            if (supportsEasyTier && supportsOpenP2P) return "双模式（EasyTier + OpenP2P）";
            if (supportsEasyTier) return "仅 EasyTier 模式";
            if (supportsOpenP2P) return "仅 OpenP2P 模式";
            return "不支持任何 P2P 模式";
        }
    }

    /** 对房间码（可能包含显式 host:roomCode 简写）做 UDP 探测。 */
    public ProbeResult probe(String roomCode) {
        if (roomCode == null) return new ProbeResult(false, -1, false, false, false, "房间号为空", null);
        String code = roomCode.trim();
        if (code.isEmpty()) return new ProbeResult(false, -1, false, false, false, "房间号为空", null);

        List<InetSocketAddress> targets = buildTargets(code);
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(2, targets.size()));
        CompletableFuture<ProbeResult> race = CompletableFuture.anyOf(
                targets.stream()
                        .map(addr -> CompletableFuture.supplyAsync(() -> doProbe(code, addr), executor))
                        .toArray(CompletableFuture[]::new)
        ).thenApply(o -> (ProbeResult) o);

        try {
            // 取第一个成功的；若都失败则找最后拿到的失败信息
            ProbeResult result = race.get(Math.max(1200, config.getHolePunchTimeoutMs() / 3), TimeUnit.MILLISECONDS);
            if (result == null || !result.success) {
                ProbeResult best = bestFailed(targets, executor, code);
                return best != null ? best :
                        new ProbeResult(false, -1, false, false, false,
                                "未收到房间码 " + code + " 的 UDP PROBE 响应（本机/配置/广播均无回包）",
                                null);
            }
            return result;
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            return new ProbeResult(false, -1, false, false, false,
                    "房间码探测超时: " + (e.getMessage() == null ? "" : e.getMessage()), null);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 简化版：仅查模式（其实 UDP 探测本身已带模式信息，直接复用）。 */
    public ProbeResult queryModesOnly(String roomCode) {
        return probe(roomCode);
    }

    /**
     * 只探测本机 127.0.0.1 的内嵌信令，用于判断"该房间的服务端是否就是本机"。
     * <p>内嵌信令只会对"已注册的房间号"回包，因此收到响应即可确认本机就是该房间的服务端；
     * 未注册（或服务端在远程）时不会收到响应。跨机时对方的 UDP 探测包也无法通过 NAT 到达，
     * 所以本方法只适用于本机判定。
     */
    public ProbeResult probeLocal(String roomCode) {
        if (roomCode == null || roomCode.trim().isEmpty()) {
            return new ProbeResult(false, -1, false, false, false, "房间号为空", null);
        }
        return doProbe(roomCode.trim(), new InetSocketAddress("127.0.0.1",
                ModConfig.embeddedSignalingUdpProbePort()));
    }

    public void close() { /* 无长连接需要关闭，探测为一次性 UDP */ }

    // ---------------- 内部实现 ----------------

    private List<InetSocketAddress> buildTargets(String roomCodeInput) {
        List<InetSocketAddress> list = new ArrayList<>();
        int udpPort = ModConfig.embeddedSignalingUdpProbePort();
        // 1) 配置里指定的 signaling host (默认 127.0.0.1)
        try {
            String host = config.getSignalingServerHost();
            if (host != null && !host.isBlank()) list.add(new InetSocketAddress(host, udpPort));
        } catch (Exception ignore) {}
        // 2) 本机 loopback 保底
        list.add(new InetSocketAddress("127.0.0.1", udpPort));
        // 3) LAN 广播（若启用）
        if (ModConfig.lanBroadcastEnabled()) {
            try { list.add(new InetSocketAddress("255.255.255.255", udpPort)); } catch (Exception ignore) {}
        }
        // 4) 如果用户输入形态是 <host>/<room> 或 room@host，显式把 host 加进 target
        String hostExtra = parseExplicitHost(roomCodeInput);
        if (hostExtra != null) {
            try { list.add(new InetSocketAddress(hostExtra, udpPort)); } catch (Exception ignore) {}
        }
        return list;
    }

    private static String parseExplicitHost(String input) {
        if (input == null) return null;
        int slash = input.indexOf('/');
        if (slash > 0 && slash < input.length() - 1) return input.substring(0, slash);
        int at = input.indexOf('@');
        if (at > 0 && at < input.length() - 1) return input.substring(at + 1);
        return null;
    }

    private ProbeResult doProbe(String code, InetSocketAddress addr) {
        long start = System.currentTimeMillis();
        try (DatagramSocket sock = new DatagramSocket(0)) {
            sock.setSoTimeout(Math.max(1000, config.getHolePunchTimeoutMs() / 3));
            if (addr.getAddress() != null && addr.getAddress().isMulticastAddress()) sock.setBroadcast(true);
            if (addr.getAddress() == null) { /* unresolved */ sock.connect(addr); }
            String roomCode = extractRoomCode(code);
            String payload = PROBE_PREFIX + roomCode;
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket p = new DatagramPacket(data, data.length, addr.getAddress() == null
                    ? InetAddress.getByName(addr.getHostString()) : addr.getAddress(), addr.getPort());
            // 最多发 3 次（轻量 UDP）
            for (int i = 0; i < 3; i++) {
                try { sock.send(p); } catch (IOException ignore) {}
                try {
                    byte[] buf = new byte[512];
                    DatagramPacket rp = new DatagramPacket(buf, buf.length);
                    sock.receive(rp);
                    String resp = new String(rp.getData(), rp.getOffset(), rp.getLength(), StandardCharsets.UTF_8);
                    ProbeResult parsed = parseResp(resp, start, rp);
                    if (parsed != null) return parsed;
                } catch (SocketTimeoutException t) {
                    // continue retry
                } catch (IOException ioe) {
                    return new ProbeResult(false, -1, false, false, false,
                            "UDP 错误(" + addr + "): " + ioe.getMessage(), null);
                }
            }
            return new ProbeResult(false, -1, false, false, false, "目标 " + addr + " 无响应", null);
        } catch (Exception ex) {
            return new ProbeResult(false, -1, false, false, false,
                    "套接字创建失败(" + addr + "): " + ex.getMessage(), null);
        }
    }

    private static String extractRoomCode(String input) {
        if (input == null) return "";
        int slash = input.indexOf('/');
        if (slash > 0) return input.substring(slash + 1);
        int at = input.indexOf('@');
        if (at > 0) return input.substring(0, at);
        return input;
    }

    private static ProbeResult parseResp(String raw, long startMs, DatagramPacket rp) {
        if (raw == null || !raw.startsWith(PROBE_OK_PREFIX)) return null;
        long rtt = Math.max(1, System.currentTimeMillis() - startMs);
        String rest = raw.substring(PROBE_OK_PREFIX.length()).trim();
        String[] parts = rest.split("\\s+");
        if (parts.length < 4) return null;
        int modeBits;
        boolean hasToken;
        try {
            modeBits = Integer.parseInt(parts[1]);
            hasToken = !"0".equals(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        ModConfig.ModeBits m = ModConfig.ModeBits.fromBits(modeBits);
        String responder = rp.getAddress() == null ? null : (rp.getAddress().getHostAddress() + ":" + rp.getPort());
        return new ProbeResult(true, (int) Math.min(rtt, Integer.MAX_VALUE),
                m.supportsEasyTier(), m.supportsOpenP2P(), hasToken, null, responder);
    }

    /** 当 anyOf 只等到失败结果时，汇总各 target 的失败信息。 */
    private ProbeResult bestFailed(List<InetSocketAddress> targets, ExecutorService ex, String code) {
        List<CompletableFuture<ProbeResult>> futs = targets.stream()
                .map(a -> CompletableFuture.supplyAsync(() -> doProbe(code, a), ex))
                .toList();
        String msg = "所有目标无响应";
        for (CompletableFuture<ProbeResult> f : futs) {
            try {
                ProbeResult r = f.get(1500, TimeUnit.MILLISECONDS);
                if (r != null && !r.success && r.errorMessage != null) msg = r.errorMessage;
            } catch (Exception ignore) {}
        }
        return new ProbeResult(false, -1, false, false, false, msg, null);
    }
}
