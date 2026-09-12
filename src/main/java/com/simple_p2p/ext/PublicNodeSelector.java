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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EasyTier 公共节点自动选优。
 *
 * <p>从配置的节点列表接口（Uptime Kuma 状态页 API）拉取社区公共节点，
 * 过滤掉被打码（名称含 {@code *}，需加群才显示）以及非 tcp 的条目，
 * 然后逐个实测 TCP 连接延迟，返回最快的那个节点。
 *
 * <p>为什么实测而不是用监控站上报的延迟：状态页里的延迟是"监控服务器 → 节点"的，
 * 与本机到该节点的实际延迟无关；本机实测才能反映真实网络情况，也能直接排除已宕机的节点。
 */
public final class PublicNodeSelector {

    private PublicNodeSelector() {}

    /** 匹配 monitor 名称里的节点地址，如 "tcp://225284.xyz:11010"。 */
    private static final Pattern NODE_PATTERN =
            Pattern.compile("(tcp|udp|ws|wss|quic|wg|faketcp)://([A-Za-z0-9.\\-]+):(\\d{1,5})");

    /** 节点列表缓存时长。 */
    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static volatile List<String> cachedNodes = null;
    private static volatile long cacheAt = 0L;

    /** 社区节点备用源（对齐 MinecraftConnectTool：远程 fallback 配置 + 硬编码兜底）。 */
    private static final String FALLBACK_API = "https://api.mct.mczlf.loft.games/007/ETFullBack";
    private static final String[] HARDCODED_FALLBACK_NODES = {
            "tcp://225284.xyz:11010"
    };

    /**
     * 返回所有本机实测可达的节点，按延迟升序排列。
     * <p>EasyTier 支持同时连接多个公共节点（-p 可跟多个地址），多连几个能显著提升组网成功率：
     * 任意一个节点可达即可发现对端，某个节点宕机也不影响。
     */
    public static List<String> selectAllReachable(Consumer<String> log) {
        List<String> nodes = fetchCandidates(log);
        List<String> reachable = new ArrayList<>();
        if (nodes.isEmpty()) return reachable;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, nodes.size()));
        try {
            List<Future<NodeLatency>> futures = new ArrayList<>();
            for (String node : nodes) {
                futures.add(pool.submit(() -> ping(node)));
            }
            List<NodeLatency> ok = new ArrayList<>();
            for (Future<NodeLatency> f : futures) {
                try {
                    NodeLatency nl = f.get(4, TimeUnit.SECONDS);
                    if (nl != null && nl.latencyMs >= 0) ok.add(nl);
                } catch (Exception ignored) {
                }
            }
            ok.sort((a, b) -> Long.compare(a.latencyMs, b.latencyMs));
            for (NodeLatency nl : ok) reachable.add(nl.node);
            if (log != null && !reachable.isEmpty()) {
                log.accept("实测可用公共节点 " + reachable.size() + " 个（最快: " + reachable.get(0) + "）");
            }
            System.out.println("[SimpleP2P] 可用公共节点(" + reachable.size() + "): " + reachable);
            return reachable;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 选取延迟最低的可用节点；全部不可用或获取失败时返回 null。 */
    public static String selectBest(Consumer<String> log) {
        List<String> all = selectAllReachable(log);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 获取候选节点列表（主源 + MCT 备用源，带缓存）。 */
    public static List<String> fetchCandidates(Consumer<String> log) {
        long now = System.currentTimeMillis();
        List<String> cached = cachedNodes;
        if (cached != null && now - cacheAt < CACHE_MS) return cached;

        List<String> nodes = fetchFromPrimary(log);
        // 补充 MCT 的备用节点源，提高节点获取成功率
        for (String node : fetchFromFallback()) {
            if (!nodes.contains(node)) nodes.add(node);
        }
        if (!nodes.isEmpty()) {
            cachedNodes = nodes;
            cacheAt = now;
        }
        return nodes;
    }

    /** 主源：状态页 API（Uptime Kuma 格式），解析出社区公共节点。 */
    private static List<String> fetchFromPrimary(Consumer<String> log) {
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
            if (log != null) log.accept("获取公共节点列表失败: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return nodes;
    }

    /**
     * 备用源（对齐 MinecraftConnectTool）：远程 fallback 配置（每行一个节点）+ 硬编码兜底。
     */
    private static List<String> fetchFromFallback() {
        List<String> result = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(FALLBACK_API).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 SimpleP2P-Mod");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.contains("*") && line.contains("://")) {
                            result.add(line);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        for (String node : HARDCODED_FALLBACK_NODES) {
            if (!result.contains(node)) result.add(node);
        }
        return result;
    }

    /** 解析状态页 JSON，提取可用的 tcp 节点。 */
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
                    String name = nameEl.getAsString();
                    // 名称含 * 表示地址被打码（需加 QQ 群获取完整地址），跳过
                    if (name.contains("*")) continue;
                    Matcher matcher = NODE_PATTERN.matcher(name);
                    if (matcher.find()) {
                        String proto = matcher.group(1);
                        // 只保留 tcp：便于用 TCP 连接实测延迟与可用性
                        if (!"tcp".equalsIgnoreCase(proto)) continue;
                        String node = proto + "://" + matcher.group(2) + ":" + matcher.group(3);
                        if (!result.contains(node)) result.add(node);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 实测 TCP 连接延迟（毫秒），不可达返回 -1。 */
    private static NodeLatency ping(String node) {
        String[] hp = splitHostPort(node);
        if (hp == null) return new NodeLatency(node, -1);
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 1500);
            return new NodeLatency(node, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            return new NodeLatency(node, -1);
        }
    }

    private static String[] splitHostPort(String node) {
        try {
            int idx = node.indexOf("://");
            String rest = node.substring(idx + 3);
            int colon = rest.lastIndexOf(':');
            return new String[]{rest.substring(0, colon), rest.substring(colon + 1)};
        } catch (Exception e) {
            return null;
        }
    }

    /** 忽略 SSL 证书校验（复用 ignoreSslVerify 开关）。 */
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

    private static final class NodeLatency {
        final String node;
        final long latencyMs;
        NodeLatency(String node, long latencyMs) {
            this.node = node;
            this.latencyMs = latencyMs;
        }
    }
}
