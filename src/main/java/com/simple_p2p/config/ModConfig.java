package com.simple_p2p.config;

import com.simple_p2p.enums.P2PMode;
import com.simple_p2p.util.SimpleJson;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Mod 配置，持久化到 config/simplep2p.json。
 */
public class ModConfig {

    private static final String CONFIG_DIR = "config";
    private static final String CONFIG_FILE = "simplep2p.json";
    private static ModConfig INSTANCE;

    // ================== 持久化字段 ==================

    /** 服务端房间号。 */
    private String serverRoomCode;

    /** 服务端是否已开启房间。 */
    private boolean serverRoomOpened;

    /** 服务端运行模式。 */
    private P2PMode serverMode = P2PMode.BOTH;

    /** 服务端本地 MC 端口。 */
    private int serverLocalPort = 25565;

    /** 是否自动检测运行中的 MC 端口；false 时使用 serverLocalPort。 */
    private boolean autoDetectMcPort = true;

    /** 服务器加载完成（专用服）或本地对局域网开放后，是否自动开启房间。 */
    private boolean autoOpenRoom = false;

    /** OpenP2P Token。 */
    private String openP2PToken = "";

    /** OpenP2P Token 注册地址。 */
    private String openP2PRegisterUrl = "https://openp2p.cn/register";

    /** 信令服务器地址（内嵌信令默认本机）。 */
    private String signalingServerHost = "127.0.0.1";
    private int signalingServerPort = 7887;

    /** 信令连接失败时是否回退到本机内嵌信令。 */
    private boolean autoFallbackToLocalSignaling = true;

    /** 内嵌信令 TCP 端口。 */
    private int embeddedSignalingTcpPort = 7887;

    /** 内嵌信令 UDP PROBE 端口（服务器列表延迟探测）。 */
    private int embeddedSignalingUdpProbePort = 7888;

    /** UDP PROBE 是否接受非本机来源。 */
    private boolean udpProbeListenAll = true;

    /** 是否接收 LAN 广播探测。 */
    private boolean lanBroadcastEnabled = true;

    /** 中继服务器地址（保留）。 */
    private String relayServerHost = "127.0.0.1";
    private int relayServerPort = 7889;

    /** EasyTier 直连地址（保留）。 */
    private String easyTierHost = "127.0.0.1";
    private int easyTierPort = 11010;

    /** UDP 打洞超时（毫秒）。 */
    private int holePunchTimeoutMs = 5000;

    /** UDP 打洞重试次数。 */
    private int holePunchRetries = 5;

    /** 房间号长度。 */
    private int roomCodeLength = 16;

    /** 收藏的房间号服务器。 */
    private List<SavedServerEntry> savedRoomServers = new ArrayList<>();

    /** 内置首选下载代理。 */
    public static final String BUILTIN_PRIMARY_MIRROR = "https://github.chenc.dev/";

    /** 自动选优全部失败时使用的兜底节点。 */
    public static final String DEFAULT_EASYTIER_PUBLIC_NODE = "tcp://easytier.weiai.org.cn:11010";

    // ================== 外部客户端（EasyTier / OpenP2P） ==================

    /** 是否自动下载官方客户端。 */
    private boolean autoDownloadBinaries = true;

    /** 二进制安装根目录，空为自动定位。 */
    private String binaryInstallDir = "";

    /** EasyTier 版本号（easyTierAutoLatest 开启时忽略）。 */
    private String easyTierVersion = "2.6.4";

    /** EasyTier 是否自动使用 GitHub 最新版本。 */
    private boolean easyTierAutoLatest = true;

    /** OpenP2P 版本号（openP2PAutoLatest 开启时忽略）。 */
    private String openP2PVersion = "3.25.11";

    /** OpenP2P 是否自动使用 GitHub 最新版本。 */
    private boolean openP2PAutoLatest = true;

    /** EasyTier 兜底公共节点。 */
    private String easyTierPublicNode = DEFAULT_EASYTIER_PUBLIC_NODE;

    /** EasyTier 服务端虚拟 IP。 */
    private String easyTierServerIp = "10.144.144.1";

    /** 下载加速镜像前缀（顺序回退）。 */
    private List<String> downloadMirrors = new ArrayList<>(List.of(
            "https://github.chenc.dev/",
            "https://ghfast.top/",
            "https://hk.gh-proxy.org/",
            "https://cdn.gh-proxy.org/"));

    /** OpenP2P 服务端节点名前缀。 */
    private String openP2PServerNodePrefix = "sp2p-srv-";

    /** OpenP2P 客户端节点名前缀。 */
    private String openP2PClientNodePrefix = "sp2p-cli-";

    /** 组网就绪等待上限（毫秒）。 */
    private int extConnectTimeoutMs = 20000;

    /** 是否自动安装 OpenP2P setup.exe（保留）。 */
    private boolean windowsOpenP2PAutoInstall = false;

    /** 下载时是否忽略 SSL 证书校验。 */
    private boolean ignoreSslVerify = false;

    /** 公共节点列表接口（Uptime Kuma 状态页）。 */
    private String nodeListUrl = "https://info.qtet.cn/uptime/api/status-page/easytier";

    /** 是否自动实测选优（false 时用 easyTierPublicNode）。 */
    private boolean autoSelectNode = true;

    /** 单个节点连通性测试超时（毫秒）。 */
    private int nodePingTimeoutMs = 2000;

    /** 节点使用方式：auto=实测自动选优，selected=只用 selectedNodes。 */
    private String nodeSelectMode = "auto";

    /** 用户勾选的节点（nodeSelectMode=selected 时生效）。 */
    private List<String> selectedNodes = new ArrayList<>();

    /** 用户手动添加的节点，置顶参与测速与连接。 */
    private List<String> customNodes = new ArrayList<>();

    /** 用户从池中排除的节点，重新拉取后也不会再出现。 */
    private List<String> excludedNodes = new ArrayList<>();

    /** 最近一次拉取到的节点池，供配置界面离线展示。 */
    private List<String> nodePool = new ArrayList<>();

    /** 地址输入模式：room=房间号，ip=普通地址（仅手动切换后生效）。 */
    private String clientAddressMode = "room";

    /** 用户是否手动切换过地址模式；false 时按输入内容自动识别。 */
    private boolean clientAddressModeManual = false;

    // ================== 构造与加载 ==================

    private ModConfig() {
    }

    public static synchronized ModConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfig();
            INSTANCE.load();
        }
        return INSTANCE;
    }

    /**
     * 将配置转为Map（用于序列化）
     */
    private Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverRoomCode", serverRoomCode);
        m.put("serverRoomOpened", serverRoomOpened);
        m.put("serverMode", serverMode != null ? serverMode.getId() : null);
        m.put("serverLocalPort", serverLocalPort);
        m.put("autoDetectMcPort", autoDetectMcPort);
        m.put("autoOpenRoom", autoOpenRoom);
        m.put("openP2PToken", openP2PToken);
        m.put("openP2PRegisterUrl", openP2PRegisterUrl);
        m.put("signalingServerHost", signalingServerHost);
        m.put("signalingServerPort", signalingServerPort);
        m.put("autoFallbackToLocalSignaling", autoFallbackToLocalSignaling);
        m.put("embeddedSignalingTcpPort", embeddedSignalingTcpPort);
        m.put("embeddedSignalingUdpProbePort", embeddedSignalingUdpProbePort);
        m.put("udpProbeListenAll", udpProbeListenAll);
        m.put("lanBroadcastEnabled", lanBroadcastEnabled);
        m.put("relayServerHost", relayServerHost);
        m.put("relayServerPort", relayServerPort);
        m.put("easyTierHost", easyTierHost);
        m.put("easyTierPort", easyTierPort);
        m.put("holePunchTimeoutMs", holePunchTimeoutMs);
        m.put("holePunchRetries", holePunchRetries);
        m.put("roomCodeLength", roomCodeLength);
        // 外部官方客户端集成
        m.put("autoDownloadBinaries", autoDownloadBinaries);
        m.put("binaryInstallDir", binaryInstallDir);
        m.put("easyTierVersion", easyTierVersion);
        m.put("easyTierAutoLatest", easyTierAutoLatest);
        m.put("openP2PVersion", openP2PVersion);
        m.put("openP2PAutoLatest", openP2PAutoLatest);
        m.put("easyTierPublicNode", easyTierPublicNode);
        m.put("easyTierServerIp", easyTierServerIp);
        m.put("downloadMirrors", new ArrayList<>(downloadMirrors));
        m.put("openP2PServerNodePrefix", openP2PServerNodePrefix);
        m.put("openP2PClientNodePrefix", openP2PClientNodePrefix);
        m.put("extConnectTimeoutMs", extConnectTimeoutMs);
        m.put("windowsOpenP2PAutoInstall", windowsOpenP2PAutoInstall);
        m.put("ignoreSslVerify", ignoreSslVerify);
        m.put("nodeListUrl", nodeListUrl);
        m.put("autoSelectNode", autoSelectNode);
        m.put("nodePingTimeoutMs", nodePingTimeoutMs);
        m.put("nodeSelectMode", nodeSelectMode);
        m.put("selectedNodes", new ArrayList<>(selectedNodes));
        m.put("customNodes", new ArrayList<>(customNodes));
        m.put("excludedNodes", new ArrayList<>(excludedNodes));
        m.put("nodePool", new ArrayList<>(nodePool));
        m.put("clientAddressMode", clientAddressMode);
        m.put("clientAddressModeManual", clientAddressModeManual);
        List<Map<String, Object>> list = new ArrayList<>();
        for (SavedServerEntry e : savedRoomServers) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("name", e.name);
            em.put("roomCode", e.roomCode);
            em.put("lastPingMs", e.lastPingMs);
            em.put("supportsEasyTier", e.supportsEasyTier);
            em.put("supportsOpenP2P", e.supportsOpenP2P);
            list.add(em);
        }
        m.put("savedRoomServers", list);
        return m;
    }

    /**
     * 从Map填充配置（用于反序列化）
     */
    private void fromMap(Map<String, Object> m) {
        if (m == null) return;
        this.serverRoomCode = SimpleJson.getStr(m, "serverRoomCode");
        this.serverRoomOpened = SimpleJson.getBool(m, "serverRoomOpened");
        String modeId = SimpleJson.getStr(m, "serverMode");
        this.serverMode = modeId != null ? P2PMode.fromId(modeId) : P2PMode.BOTH;
        int port = SimpleJson.getInt(m, "serverLocalPort");
        this.serverLocalPort = port > 0 ? port : 25565;
        this.autoDetectMcPort = !Objects.equals(Boolean.FALSE, m.get("autoDetectMcPort"));
        this.autoOpenRoom = Objects.equals(Boolean.TRUE, m.get("autoOpenRoom"));
        String tk = SimpleJson.getStr(m, "openP2PToken");
        this.openP2PToken = tk != null ? tk : "";
        String reg = SimpleJson.getStr(m, "openP2PRegisterUrl");
        this.openP2PRegisterUrl = reg != null ? reg : "https://openp2p.cn/register";
        this.signalingServerHost = migrateLegacyHost(notNullOrElse(SimpleJson.getStr(m, "signalingServerHost"), "127.0.0.1"));
        this.signalingServerPort = positiveOrDefault(SimpleJson.getInt(m, "signalingServerPort"), 7887);
        this.autoFallbackToLocalSignaling = !Objects.equals(Boolean.FALSE, m.get("autoFallbackToLocalSignaling"));
        this.embeddedSignalingTcpPort = positiveOrDefault(SimpleJson.getInt(m, "embeddedSignalingTcpPort"), this.signalingServerPort);
        this.embeddedSignalingUdpProbePort = positiveOrDefault(SimpleJson.getInt(m, "embeddedSignalingUdpProbePort"), 7888);
        this.udpProbeListenAll = !Objects.equals(Boolean.FALSE, m.get("udpProbeListenAll"));
        this.lanBroadcastEnabled = !Objects.equals(Boolean.FALSE, m.get("lanBroadcastEnabled"));
        this.relayServerHost = migrateLegacyHost(notNullOrElse(SimpleJson.getStr(m, "relayServerHost"), "127.0.0.1"));
        this.relayServerPort = positiveOrDefault(SimpleJson.getInt(m, "relayServerPort"), 7889);
        this.easyTierHost = migrateLegacyHost(notNullOrElse(SimpleJson.getStr(m, "easyTierHost"), "127.0.0.1"));
        this.easyTierPort = positiveOrDefault(SimpleJson.getInt(m, "easyTierPort"), 11010);
        this.holePunchTimeoutMs = positiveOrDefault(SimpleJson.getInt(m, "holePunchTimeoutMs"), 5000);
        this.holePunchRetries = positiveOrDefault(SimpleJson.getInt(m, "holePunchRetries"), 5);
        this.roomCodeLength = positiveOrDefault(SimpleJson.getInt(m, "roomCodeLength"), 16);
        // 外部官方客户端集成
        this.autoDownloadBinaries = !Objects.equals(Boolean.FALSE, m.get("autoDownloadBinaries"));
        this.binaryInstallDir = notNullOrElse(SimpleJson.getStr(m, "binaryInstallDir"), "");
        this.easyTierVersion = notNullOrElse(SimpleJson.getStr(m, "easyTierVersion"), "2.6.4");
        this.easyTierAutoLatest = !Objects.equals(Boolean.FALSE, m.get("easyTierAutoLatest"));
        this.openP2PVersion = notNullOrElse(SimpleJson.getStr(m, "openP2PVersion"), "3.25.11");
        this.openP2PAutoLatest = !Objects.equals(Boolean.FALSE, m.get("openP2PAutoLatest"));
        String etNode = notNullOrElse(SimpleJson.getStr(m, "easyTierPublicNode"), DEFAULT_EASYTIER_PUBLIC_NODE);
        // 迁移已失效的官方公共节点
        if (etNode.contains("public.easytier.cn") || etNode.contains("public.easytier.top")) {
            etNode = DEFAULT_EASYTIER_PUBLIC_NODE;
        }
        this.easyTierPublicNode = etNode;
        this.easyTierServerIp = notNullOrElse(SimpleJson.getStr(m, "easyTierServerIp"), "10.144.144.1");
        Object mirrors = m.get("downloadMirrors");
        if (mirrors instanceof List && !((List<?>) mirrors).isEmpty()) {
            List<String> ms = new ArrayList<>();
            for (Object it : (List<?>) mirrors) ms.add(String.valueOf(it));
            this.downloadMirrors = ms;
        }
        // 确保内置首选代理 chenc.dev 始终存在且置首（旧配置文件可能没有它）
        if (!this.downloadMirrors.contains(BUILTIN_PRIMARY_MIRROR)) {
            this.downloadMirrors.add(0, BUILTIN_PRIMARY_MIRROR);
        }
        this.openP2PServerNodePrefix = notNullOrElse(SimpleJson.getStr(m, "openP2PServerNodePrefix"), "sp2p-srv-");
        this.openP2PClientNodePrefix = notNullOrElse(SimpleJson.getStr(m, "openP2PClientNodePrefix"), "sp2p-cli-");
        this.extConnectTimeoutMs = positiveOrDefault(SimpleJson.getInt(m, "extConnectTimeoutMs"), 20000);
        this.windowsOpenP2PAutoInstall = Objects.equals(Boolean.TRUE, m.get("windowsOpenP2PAutoInstall"));
        this.ignoreSslVerify = Objects.equals(Boolean.TRUE, m.get("ignoreSslVerify"));
        this.nodeListUrl = notNullOrElse(SimpleJson.getStr(m, "nodeListUrl"),
                "https://info.qtet.cn/uptime/api/status-page/easytier");
        this.autoSelectNode = !Objects.equals(Boolean.FALSE, m.get("autoSelectNode"));
        this.nodePingTimeoutMs = positiveOrDefault(SimpleJson.getInt(m, "nodePingTimeoutMs"), 2000);
        String nsm = SimpleJson.getStr(m, "nodeSelectMode");
        this.nodeSelectMode = "selected".equalsIgnoreCase(nsm) ? "selected" : "auto";
        this.selectedNodes = strList(m.get("selectedNodes"));
        this.customNodes = strList(m.get("customNodes"));
        this.excludedNodes = strList(m.get("excludedNodes"));
        this.nodePool = strList(m.get("nodePool"));
        String cam = SimpleJson.getStr(m, "clientAddressMode");
        this.clientAddressMode = "ip".equalsIgnoreCase(cam) ? "ip" : "room";
        this.clientAddressModeManual = Objects.equals(Boolean.TRUE, m.get("clientAddressModeManual"));
        // saved servers
        List<SavedServerEntry> entries = new ArrayList<>();
        Object list = m.get("savedRoomServers");
        if (list instanceof List) {
            for (Object it : (List<?>) list) {
                if (it instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> em = (Map<String, Object>) it;
                    SavedServerEntry entry = new SavedServerEntry();
                    entry.name = SimpleJson.getStr(em, "name");
                    entry.roomCode = SimpleJson.getStr(em, "roomCode");
                    Number n = (Number) ((Map<?, ?>) it).get("lastPingMs");
                    entry.lastPingMs = n != null ? n.longValue() : -1;
                    entry.supportsEasyTier = SimpleJson.getBool(em, "supportsEasyTier");
                    entry.supportsOpenP2P = SimpleJson.getBool(em, "supportsOpenP2P");
                    entries.add(entry);
                }
            }
        }
        this.savedRoomServers = entries;
    }

    private static String notNullOrElse(String s, String def) {
        return s != null ? s : def;
    }

    private static int positiveOrDefault(int v, int def) {
        return v > 0 ? v : def;
    }

    private static List<String> strList(Object v) {
        List<String> r = new ArrayList<>();
        if (v instanceof List) {
            for (Object it : (List<?>) v) {
                String s = String.valueOf(it).trim();
                if (!s.isEmpty() && !r.contains(s)) r.add(s);
            }
        }
        return r;
    }

    /**
     * 迁移旧版占位域名（signaling/relay/easytier .simple_p2p.local）到 127.0.0.1。
     */
    private static String migrateLegacyHost(String host) {
        if (host == null || host.isBlank()) return "127.0.0.1";
        if (host.endsWith(".simple_p2p.local") || host.endsWith(".local")) return "127.0.0.1";
        return host;
    }

    /**
     * 从磁盘加载配置
     */
    public void load() {
        File file = getConfigFile();
        if (!file.exists()) {
            save();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            Map<String, Object> m = SimpleJson.parseObject(sb.toString());
            // 读取旧值用于迁移检测
            String oldSig = SimpleJson.getStr(m, "signalingServerHost");
            String oldRelay = SimpleJson.getStr(m, "relayServerHost");
            String oldEt = SimpleJson.getStr(m, "easyTierHost");
            fromMap(m);
            // 如果检测到旧版占位域名被迁移了，立即回写磁盘，避免下次启动又读到旧值
            boolean migrated = isLegacyHost(oldSig) || isLegacyHost(oldRelay) || isLegacyHost(oldEt);
            if (migrated) {
                System.out.println("[SimpleP2P] 检测到旧版占位域名，已自动迁移到 127.0.0.1 并回写配置");
                save();
            }
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 加载配置失败，使用默认配置: " + e.getMessage());
            save();
        }
    }

    private static boolean isLegacyHost(String host) {
        if (host == null) return false;
        return host.endsWith(".simple_p2p.local") || host.endsWith(".local");
    }

    /**
     * 保存配置到磁盘
     */
    public synchronized void save() {
        File file = getConfigFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            String json = SimpleJson.toJsonString(toMap());
            // 简单美化：换行
            writer.write(prettyPrint(json));
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 保存配置失败: " + e.getMessage());
        }
    }

    private static String prettyPrint(String json) {
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
                continue;
            }
            if (inString) { sb.append(c); continue; }
            if (c == '{' || c == '[') {
                sb.append(c);
                sb.append('\n');
                indent++;
                for (int j = 0; j < indent; j++) sb.append("  ");
            } else if (c == '}' || c == ']') {
                sb.append('\n');
                indent--;
                for (int j = 0; j < indent; j++) sb.append("  ");
                sb.append(c);
            } else if (c == ',') {
                sb.append(c);
                sb.append('\n');
                for (int j = 0; j < indent; j++) sb.append("  ");
            } else if (c == ':') {
                sb.append(": ");
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private File getConfigFile() {
        return new File(CONFIG_DIR, CONFIG_FILE);
    }

    // ================== 业务方法 ==================

    public boolean hasOpenP2PToken() {
        return openP2PToken != null && !openP2PToken.trim().isEmpty();
    }

    public String generateRoomCode() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        Random random = new Random(UUID.randomUUID().getMostSignificantBits());
        for (int i = 0; i < roomCodeLength; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public String openRoom() {
        if (serverRoomCode == null || serverRoomCode.isEmpty()) {
            serverRoomCode = generateRoomCode();
        }
        serverRoomOpened = true;
        save();
        return serverRoomCode;
    }

    public boolean setRoomCode(String newCode) {
        if (newCode == null || newCode.length() < 6) {
            return false;
        }
        if (!newCode.matches("[A-Za-z0-9]+")) {
            return false;
        }
        this.serverRoomCode = newCode;
        this.serverRoomOpened = true;
        save();
        return true;
    }

    public void closeRoom() {
        this.serverRoomOpened = false;
        save();
    }

    public void setServerMode(P2PMode mode) {
        this.serverMode = mode;
        save();
    }

    public void setOpenP2PToken(String token) {
        this.openP2PToken = token != null ? token.trim() : "";
        save();
    }

    // ================== 保存的服务器条目 ==================

    public static class SavedServerEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        public String name;
        public String roomCode;
        public long lastPingMs = -1;
        public boolean supportsEasyTier;
        public boolean supportsOpenP2P;

        public SavedServerEntry() {}

        public SavedServerEntry(String name, String roomCode) {
            this.name = name;
            this.roomCode = roomCode;
            this.lastPingMs = -1;
        }
    }

    public void addSavedServer(String name, String roomCode) {
        for (SavedServerEntry e : savedRoomServers) {
            if (e.roomCode.equalsIgnoreCase(roomCode)) {
                e.name = name;
                save();
                return;
            }
        }
        savedRoomServers.add(new SavedServerEntry(name, roomCode));
        save();
    }

    public void removeSavedServer(String roomCode) {
        savedRoomServers.removeIf(e -> e.roomCode.equalsIgnoreCase(roomCode));
        save();
    }

    public List<SavedServerEntry> getSavedRoomServers() {
        return new ArrayList<>(savedRoomServers);
    }

    // ================== Getter / Setter ==================

    public String getServerRoomCode() { return serverRoomCode; }
    public boolean isServerRoomOpened() { return serverRoomOpened; }
    public P2PMode getServerMode() { return serverMode; }
    public int getServerLocalPort() { return serverLocalPort; }
    public void setServerLocalPort(int port) { this.serverLocalPort = port; save(); }
    public boolean isAutoDetectMcPort() { return autoDetectMcPort; }
    public void setAutoDetectMcPort(boolean v) { this.autoDetectMcPort = v; save(); }
    public boolean isAutoOpenRoom() { return autoOpenRoom; }
    public void setAutoOpenRoom(boolean v) { this.autoOpenRoom = v; }
    public String getOpenP2PToken() { return openP2PToken; }
    public String getOpenP2PRegisterUrl() { return openP2PRegisterUrl; }
    public String getSignalingServerHost() { return signalingServerHost; }
    public int getSignalingServerPort() { return signalingServerPort; }
    public boolean isAutoFallbackToLocalSignaling() { return autoFallbackToLocalSignaling; }
    public int getEmbeddedSignalingTcpPort() { return embeddedSignalingTcpPort; }
    public int getEmbeddedSignalingUdpProbePort() { return embeddedSignalingUdpProbePort; }
    public boolean isUdpProbeListenAll() { return udpProbeListenAll; }
    public boolean isLanBroadcastEnabled() { return lanBroadcastEnabled; }
    public String getRelayServerHost() { return relayServerHost; }
    public int getRelayServerPort() { return relayServerPort; }
    public String getEasyTierHost() { return easyTierHost; }
    public int getEasyTierPort() { return easyTierPort; }
    public int getHolePunchTimeoutMs() { return holePunchTimeoutMs; }
    public int getHolePunchRetries() { return holePunchRetries; }
    public int getRoomCodeLength() { return roomCodeLength; }

    public boolean isAutoDownloadBinaries() { return autoDownloadBinaries; }
    public String getBinaryInstallDir() { return binaryInstallDir; }
    public String getEasyTierVersion() { return easyTierVersion; }
    public String getOpenP2PVersion() { return openP2PVersion; }
    public String getEasyTierPublicNode() { return easyTierPublicNode; }
    public String getEasyTierServerIp() { return easyTierServerIp; }
    public List<String> getDownloadMirrors() { return new ArrayList<>(downloadMirrors); }
    public String getOpenP2PServerNodePrefix() { return openP2PServerNodePrefix; }
    public String getOpenP2PClientNodePrefix() { return openP2PClientNodePrefix; }
    public int getExtConnectTimeoutMs() { return extConnectTimeoutMs; }
    public boolean isWindowsOpenP2PAutoInstall() { return windowsOpenP2PAutoInstall; }

    public boolean isIgnoreSslVerify() { return ignoreSslVerify; }
    public void setIgnoreSslVerify(boolean v) { this.ignoreSslVerify = v; save(); }

    public String getNodeListUrl() { return nodeListUrl; }
    public boolean isAutoSelectNode() { return autoSelectNode; }
    public void setAutoSelectNode(boolean v) { this.autoSelectNode = v; save(); }

    public int getNodePingTimeoutMs() { return nodePingTimeoutMs; }
    public void setNodePingTimeoutMs(int v) { this.nodePingTimeoutMs = Math.max(200, Math.min(30000, v)); }

    public String getNodeSelectMode() { return nodeSelectMode; }
    public void setNodeSelectMode(String v) { this.nodeSelectMode = "selected".equalsIgnoreCase(v) ? "selected" : "auto"; }
    public boolean isNodeSelectManual() { return "selected".equals(nodeSelectMode); }

    public List<String> getSelectedNodes() { return new ArrayList<>(selectedNodes); }
    public void setSelectedNodes(List<String> v) { this.selectedNodes = v == null ? new ArrayList<>() : new ArrayList<>(v); }

    public List<String> getCustomNodes() { return new ArrayList<>(customNodes); }
    public void addCustomNode(String node) {
        if (node != null && !node.isBlank() && !customNodes.contains(node)) customNodes.add(node);
    }
    public void removeCustomNode(String node) { customNodes.remove(node); }

    public List<String> getExcludedNodes() { return new ArrayList<>(excludedNodes); }
    public void excludeNode(String node) {
        if (node != null && !node.isBlank() && !excludedNodes.contains(node)) excludedNodes.add(node);
    }
    public void clearExcludedNodes() { excludedNodes.clear(); }

    public List<String> getNodePool() { return new ArrayList<>(nodePool); }
    /** 更新节点池缓存（低频写入，直接落盘）。 */
    public synchronized void setNodePool(List<String> v) {
        List<String> next = v == null ? new ArrayList<>() : new ArrayList<>(v);
        if (next.equals(nodePool)) return;
        this.nodePool = next;
        save();
    }

    public String getClientAddressMode() { return clientAddressMode; }
    public void setClientAddressMode(String v) { this.clientAddressMode = "ip".equalsIgnoreCase(v) ? "ip" : "room"; }
    public boolean isClientAddressModeManual() { return clientAddressModeManual; }
    public void setClientAddressModeManual(boolean v) { this.clientAddressModeManual = v; }

    /**
     * 是否按房间号处理地址输入：用户手动切换过就按切换结果；
     * 没切过时返回 true，让 AddressRecognizer 自行识别是不是房间号。
     */
    public static boolean roomCodeMode() {
        ModConfig c = getInstance();
        return !c.clientAddressModeManual || "room".equals(c.clientAddressMode);
    }

    public boolean isOpenP2PAutoLatest() { return openP2PAutoLatest; }
    public void setOpenP2PAutoLatest(boolean v) { this.openP2PAutoLatest = v; }
    public boolean isEasyTierAutoLatest() { return easyTierAutoLatest; }
    public void setEasyTierAutoLatest(boolean v) { this.easyTierAutoLatest = v; }
    public void setOpenP2PTokenRaw(String token) { this.openP2PToken = token != null ? token.trim() : ""; }
    public void setEasyTierPublicNode(String v) { this.easyTierPublicNode = v == null ? "" : v.trim(); }
    public void setEasyTierVersion(String v) { this.easyTierVersion = v == null ? "" : v.trim(); }
    public void setOpenP2PVersion(String v) { this.openP2PVersion = v == null ? "" : v.trim(); }
    public void setExtConnectTimeoutMs(int v) { this.extConnectTimeoutMs = Math.max(5000, v); }
    public void setAutoDownloadBinaries(boolean v) { this.autoDownloadBinaries = v; }
    public void setAutoDetectMcPortRaw(boolean v) { this.autoDetectMcPort = v; }
    public void setServerLocalPortRaw(int v) { this.serverLocalPort = Math.max(1, Math.min(65535, v)); }
    public void setNodeListUrl(String v) { this.nodeListUrl = v == null ? "" : v.trim(); }
    public void setEasyTierServerIp(String v) { this.easyTierServerIp = v == null ? "" : v.trim(); }

    public static String signalingEndpoint() {
        ModConfig c = getInstance();
        String h = c.getSignalingServerHost();
        int p = c.getSignalingServerPort();
        return (h == null || h.isBlank() ? "127.0.0.1" : h) + ":" + (p > 0 ? p : c.embeddedSignalingTcpPort);
    }

    public static int embeddedSignalingTcpPort() { return getInstance().embeddedSignalingTcpPort; }
    public static int embeddedSignalingUdpProbePort() { return getInstance().embeddedSignalingUdpProbePort; }
    public static boolean autoFallbackToLocalSignaling() { return getInstance().autoFallbackToLocalSignaling; }
    public static boolean udpProbeListenAll() { return getInstance().udpProbeListenAll; }
    public static boolean lanBroadcastEnabled() { return getInstance().lanBroadcastEnabled; }

    public static ModeBits serverModeBits() {
        P2PMode m = getInstance().getServerMode();
        if (m == null) return new ModeBits(3);
        switch (m) {
            case EASYTIER_ONLY: return new ModeBits(1);
            case OPENP2P_ONLY: return new ModeBits(2);
            default: return new ModeBits(3);
        }
    }

    public static boolean openP2PTokenPresent() {
        return getInstance().hasOpenP2PToken();
    }

    public static String openP2PToken() {
        String t = getInstance().getOpenP2PToken();
        return t == null ? "" : t;
    }

    public static final class ModeBits {
        public final int bits;
        public ModeBits(int bits) { this.bits = bits; }
        public int bits() { return bits; }
        public boolean supportsEasyTier() { return (bits & 1) != 0; }
        public boolean supportsOpenP2P() { return (bits & 2) != 0; }
        public static ModeBits fromBits(int bits) { return new ModeBits(bits); }
    }
}
