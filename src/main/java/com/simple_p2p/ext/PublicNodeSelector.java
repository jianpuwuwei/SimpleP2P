package com.simple_p2p.ext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simple_p2p.config.ModConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EasyTier 公共节点的拉取、管理与连通性实测。
 *
 * <p>状态页、MCT、内置节点三个来源合并成同一个节点池，按 host:port 去重，不再区分主源/备用源；
 * 节点地址的端口可省略，按协议取默认端口（ws=80、wss=443、其余=11010）。
 * 所有节点在同一批线程里并行测试，单个节点连接超时取自配置 {@code nodePingTimeoutMs}；
 * 整体超出该时长的任务直接放弃，避免个别慢节点拖住整个组网流程。
 */
public final class PublicNodeSelector {

    private PublicNodeSelector() {}

    /** 节点地址：协议 + 主机 + 可选端口。 */
    private static final Pattern NODE_PATTERN =
            Pattern.compile("(tcp|udp|ws|wss|quic|wg|faketcp)://([A-Za-z0-9.\\-]+)(?::(\\d{1,5}))?");

    /** 端口省略时的默认端口。 */
    private static final int DEFAULT_PORT = 11010;
    private static final int DEFAULT_WS_PORT = 80;
    private static final int DEFAULT_WSS_PORT = 443;

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static volatile List<String> cachedFetched = null;
    private static volatile long cacheAt = 0L;

    /** MCT 节点源，与状态页源共用同一个节点池。 */
    private static final String MCT_NODE_API = "https://api.mct.mczlf.loft.games/007/ETFullBack";

    /** 内置兜底节点，同样并入节点池统一去重与测速。 */
    private static final String[] EXTRA_NODES = {
            "tcp://225284.xyz:11010"
    };

    /** 单个节点的实测结果。 */
    public static final class NodeLatency {
        public final String node;
        public final long latencyMs;
        NodeLatency(String node, long latencyMs) {
            this.node = node;
            this.latencyMs = latencyMs;
        }
        public boolean reachable() { return latencyMs >= 0; }
    }

    /**
     * 并行实测给定节点的 TCP 延迟，按延迟升序返回（不可达排最后）。
     * 超过单个节点超时时间仍未返回的测试会被放弃。
     */
    public static List<NodeLatency> probeAll(List<String> nodes, Consumer<String> log) {
        List<NodeLatency> result = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) return result;
        int timeout = ModConfig.getInstance().getNodePingTimeoutMs();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(32, nodes.size()), r -> {
            Thread t = new Thread(r, "SimpleP2P-NodePing");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<NodeLatency>> futures = new ArrayList<>();
            for (String node : nodes) {
                futures.add(pool.submit(() -> ping(node, timeout)));
            }
            long deadline = System.currentTimeMillis() + timeout + 1000L;
            for (Future<NodeLatency> f : futures) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    f.cancel(true);
                    continue;
                }
                try {
                    result.add(f.get(left, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    f.cancel(true);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        result.sort(Comparator.comparingLong(n -> n.latencyMs < 0 ? Long.MAX_VALUE : n.latencyMs));
        if (log != null) {
            long ok = result.stream().filter(NodeLatency::reachable).count();
            log.accept("节点测试完成：可用 " + ok + "/" + result.size());
        }
        return result;
    }

    /** 实测可达节点，按延迟升序返回。 */
    public static List<String> selectAllReachable(Consumer<String> log) {
        List<String> out = new ArrayList<>();
        for (NodeLatency nl : probeAll(candidatesForUse(log), log)) {
            if (nl.reachable()) out.add(nl.node);
        }
        return out;
    }

    /** 延迟最低的可用节点；无可用节点返回 null。 */
    public static String selectBest(Consumer<String> log) {
        List<String> all = selectAllReachable(log);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 本次要测试的节点：手动模式下用勾选的节点，否则用节点池。 */
    private static List<String> candidatesForUse(Consumer<String> log) {
        ModConfig c = ModConfig.getInstance();
        if (c.isNodeSelectManual()) {
            List<String> sel = c.getSelectedNodes();
            if (!sel.isEmpty()) return sel;
            if (log != null) log.accept("未勾选节点，改用自动选优");
        }
        return fetchCandidates(log);
    }

    /** 实际节点池：手动添加的节点置顶，拉取到的节点按排除名单过滤，整体按 host:port 去重。 */
    public static List<String> fetchCandidates(Consumer<String> log) {
        ModConfig c = ModConfig.getInstance();
        Set<String> excluded = new HashSet<>();
        for (String n : c.getExcludedNodes()) excluded.add(dedupeKey(n));

        List<String> nodes = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        mergeInto(nodes, keys, c.getCustomNodes());
        for (String node : fetchedPool(log)) {
            if (excluded.contains(dedupeKey(node))) continue;
            mergeInto(nodes, keys, Collections.singletonList(node));
        }
        try {
            c.setNodePool(nodes);
        } catch (Throwable ignored) {
        }
        return nodes;
    }

    /** 强制重新拉取节点池（忽略缓存）。 */
    public static List<String> refreshPool(Consumer<String> log) {
        cachedFetched = null;
        cacheAt = 0L;
        return fetchCandidates(log);
    }

    /** 拉取所有来源的节点（带缓存）。 */
    private static List<String> fetchedPool(Consumer<String> log) {
        long now = System.currentTimeMillis();
        List<String> cached = cachedFetched;
        if (cached != null && now - cacheAt < CACHE_MS) return cached;

        List<String> nodes = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        mergeInto(nodes, keys, fetchFromStatusPage(log));
        mergeInto(nodes, keys, fetchFromMct());
        mergeInto(nodes, keys, Arrays.asList(EXTRA_NODES));
        if (!nodes.isEmpty()) {
            cachedFetched = nodes;
            cacheAt = now;
        }
        return nodes;
    }

    /** 合并去重：同一 host:port 只保留第一个出现的节点，并统一成 proto://host:port 形式。 */
    private static void mergeInto(List<String> pool, Set<String> keys, Collection<String> candidates) {
        for (String node : candidates) {
            String normalized = normalizeNode(node);
            if (normalized == null) continue;
            if (keys.add(dedupeKey(normalized))) pool.add(normalized);
        }
    }

    /** 去重键：host:port（host 不区分大小写，与协议无关）。 */
    private static String dedupeKey(String node) {
        String[] p = parseNode(node);
        if (p == null) return node == null ? "" : node.trim().toLowerCase(Locale.ROOT);
        return p[1].toLowerCase(Locale.ROOT) + ":" + p[2];
    }

    /** 校验并归一化节点地址；不合法返回 null。 */
    public static String normalizeNode(String node) {
        String[] p = parseNode(node);
        return p == null ? null : p[0] + "://" + p[1] + ":" + p[2];
    }

    /** 解析节点地址为 [proto, host, port]，端口缺省时按协议默认值补齐；不合法返回 null。 */
    private static String[] parseNode(String node) {
        if (node == null) return null;
        Matcher m = NODE_PATTERN.matcher(node);
        if (!m.find()) return null;
        String proto = m.group(1).toLowerCase(Locale.ROOT);
        int port;
        try {
            port = m.group(3) != null ? Integer.parseInt(m.group(3)) : defaultPort(proto);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535) return null;
        return new String[]{proto, m.group(2), String.valueOf(port)};
    }

    private static int defaultPort(String proto) {
        if ("ws".equals(proto)) return DEFAULT_WS_PORT;
        if ("wss".equals(proto)) return DEFAULT_WSS_PORT;
        return DEFAULT_PORT;
    }

    /** Uptime Kuma 状态页 API，只取 tcp 节点。 */
    private static List<String> fetchFromStatusPage(Consumer<String> log) {
        List<String> nodes = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            String url = ModConfig.getInstance().getNodeListUrl();
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 SimpleP2P-Mod");
            if (conn instanceof javax.net.ssl.HttpsURLConnection
                    && ModConfig.getInstance().isIgnoreSslVerify()) {
                applyInsecure((javax.net.ssl.HttpsURLConnection) conn);
            }
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return nodes;
            }
            StringBuilder sb = new StringBuilder();
            try (InputStream in = conn.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            nodes = parse(sb.toString());
        } catch (Exception e) {
            if (log != null) log.accept("获取节点列表失败: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return nodes;
    }

    /** MCT 节点源：每行一个节点，按原样提取（协议不限、端口可省）。 */
    private static List<String> fetchFromMct() {
        List<String> result = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(MCT_NODE_API).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 SimpleP2P-Mod");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String node = extractNode(line, false);
                        if (node != null) result.add(node);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    /** 从一行文本里提取第一个未被打码的节点地址并归一化；tcpOnly 时只接受 tcp。 */
    private static String extractNode(String line, boolean tcpOnly) {
        if (line == null || line.contains("*")) return null;
        Matcher m = NODE_PATTERN.matcher(line);
        while (m.find()) {
            if (tcpOnly && !"tcp".equalsIgnoreCase(m.group(1))) continue;
            String normalized = normalizeNode(m.group(0));
            if (normalized != null) return normalized;
        }
        return null;
    }

    /** 解析状态页 JSON，提取 tcp 节点。 */
    private static List<String> parse(String json) {
        List<String> result = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray groups = root.getAsJsonArray("publicGroupList");
            if (groups == null) return result;
            for (JsonElement g : groups) {
                JsonObject group = g.getAsJsonObject();
                JsonArray monitors = group.getAsJsonArray("monitorList");
                if (monitors == null) continue;
                for (JsonElement m : monitors) {
                    JsonObject mon = m.getAsJsonObject();
                    JsonElement nameEl = mon.get("name");
                    if (nameEl == null || nameEl.isJsonNull()) continue;
                    String node = extractNode(nameEl.getAsString(), true);
                    if (node != null) result.add(node);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 实测 TCP 连接延迟（毫秒），不可达返回 -1。 */
    private static NodeLatency ping(String node, int timeoutMs) {
        String[] p = parseNode(node);
        if (p == null) return new NodeLatency(node, -1);
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(p[1], Integer.parseInt(p[2])), timeoutMs);
            return new NodeLatency(node, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return new NodeLatency(node, -1);
        }
    }

    /** 忽略 SSL 证书校验。 */
    private static void applyInsecure(javax.net.ssl.HttpsURLConnection conn) {
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {
        }
    }
}
