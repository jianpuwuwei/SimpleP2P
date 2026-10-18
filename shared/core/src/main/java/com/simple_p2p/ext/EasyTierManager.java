package com.simple_p2p.ext;

import com.simple_p2p.config.ModConfig;
import com.simple_p2p.proxy.TcpForwarder;

import java.io.File;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * EasyTier 官方客户端(easytier-core)的组网管理。
 *
 * <p>两端都用 --no-tun + --tcp-whitelist 暴露端口；客户端通过 easytier-cli 建立本地端口转发，
 * MC 只连 127.0.0.1，不依赖虚拟网卡路由。
 */
public final class EasyTierManager {

    /** 生成节点机器标识（用户/系统信息摘要的前 16 位）。 */
    private static String machineId() {
        String raw = System.getProperty("user.name", "") + "-"
                + System.getProperty("os.name", "") + "-"
                + System.getProperty("os.arch", "");
        String hex = sha256Hex(raw);
        return hex.length() >= 16 ? hex.substring(0, 16) : hex;
    }

    /** EasyTier 虚拟网内统一对外服务的约定端口（客户端固定连它，服务端负责转发到此端口）。 */
    public static final int DEFAULT_VIRTUAL_PORT = 25565;

    /**
     * 节点 hostname 前缀。参考 MinecraftConnectTool(ET模式) 的做法：
     * 服务端 hostname 形如 {@code sp2p-server-<MC端口>}，客户端形如 {@code sp2p-client-<随机>}，
     * 便于对端在 peer 列表里识别角色。
     */
    public static final String SERVER_HOSTNAME_PREFIX = "sp2p-server-";
    public static final String CLIENT_HOSTNAME_PREFIX = "sp2p-client-";

    /** easytier-cli 的 RPC 端口（每次启动动态分配，避免多实例冲突）。 */
    private int rpcPort;

    /** 组网名（network-name）派生。 */
    public static String networkName(String roomCode) {
        return "sp2p-" + roomCode;
    }

    /** 组网密钥（network-secret）：房间号的 SHA-256 十六进制，两侧同源。 */
    public static String networkSecret(String roomCode) {
        return sha256Hex(roomCode == null ? "" : roomCode);
    }

    /** SHA-256 摘要的小写十六进制表示。 */
    private static String sha256Hex(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必然可用，此分支只保证不会返回空密钥
            return "sp2p-fallback-" + text;
        }
    }

    private final ModConfig config;
    private ProcessSupervisor proc;
    private TcpForwarder forwarder;
    private ServerSocket probeBind; // TUN 就绪探测用，占用后立即关闭
    private String currentRoomCode;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EasyTierManager() {
        this.config = ModConfig.getInstance();
    }

    public boolean isRunning() {
        return running.get() && proc != null && proc.isAlive();
    }

    public String getCurrentRoomCode() { return currentRoomCode; }

    public String[] recentOutput(int n) {
        return proc != null ? proc.tail(n) : new String[0];
    }

    /** 服务端启动房间。返回成功或中文错误。 */
    public synchronized StartResult startServer(String roomCode, int mcPort, Consumer<String> log) {
        try {
            BinaryInstaller.ensureInstalled(ExtPaths.ExtTool.EASYTIER, new BinaryInstaller.ProgressListener() {
                @Override
                public void onProgress(String stage, long bytes, long total) {
                    // 过滤下载进度，避免刷屏；只提示准备/解压/完成等阶段性信息
                    if (log != null && !stage.startsWith("下载中")) log.accept(stage);
                }
            });
        } catch (BinaryInstaller.ExtException e) {
            return StartResult.fail(e.getMessage());
        }
        if (isRunning()) {
            return StartResult.fail("EasyTier 房间已开启: " + currentRoomCode);
        }
        currentRoomCode = roomCode;
        running.set(true);

        File bin = ExtPaths.resolveBinary(ExtPaths.ExtTool.EASYTIER);
        if (bin == null) {
            running.set(false);
            return StartResult.fail("未找到 easytier-core，请放入 " + ExtPaths.toolDir(ExtPaths.ExtTool.EASYTIER).getAbsolutePath());
        }
        File cwd = bin.getParentFile();
        List<String> args = new ArrayList<>();
        // 参数组合对齐 MinecraftConnectTool：--no-tun 不建虚拟网卡（免管理员权限），
        // 用 --tcp-whitelist/--udp-whitelist 暴露本机端口；--hostname 携带角色与 MC 端口。
        args.add("--no-tun");
        args.add("--multi-thread");
        args.add("--network-name"); args.add(networkName(roomCode));
        args.add("--network-secret"); args.add(networkSecret(roomCode));
        args.add("--hostname"); args.add(SERVER_HOSTNAME_PREFIX + mcPort);
        args.add("--machine-id"); args.add(machineId());
        args.add("--private-mode"); args.add("true");
        args.add("--ipv4"); args.add(config.getEasyTierServerIp());
        // 允许虚拟网络访问：本机 MC 端口 + 约定端口
        args.add("--tcp-whitelist"); args.add(String.valueOf(mcPort));
        args.add("--udp-whitelist"); args.add(String.valueOf(mcPort));
        args.add("--tcp-whitelist"); args.add(String.valueOf(DEFAULT_VIRTUAL_PORT));
        args.add("--udp-whitelist"); args.add(String.valueOf(DEFAULT_VIRTUAL_PORT));
        args.add("--listeners"); args.add("tcp://0.0.0.0:0");
        args.add("--listeners"); args.add("udp://0.0.0.0:0");
        this.rpcPort = NetUtils.findFreePort();
        if (this.rpcPort > 0) {
            args.add("--rpc-portal"); args.add("127.0.0.1:" + this.rpcPort);
        }
        args.add("--enable-kcp-proxy");
        args.add("--enable-quic-proxy");
        args.add("--use-smoltcp");
        args.add("--compression"); args.add("zstd");
        args.add("--default-protocol"); args.add("tcp");
        args.add("--encryption-algorithm"); args.add("aes-gcm");
        args.add("--peers");
        args.addAll(resolvePublicNodes(log));

        try {
            proc = startEasyTierProc(bin, cwd, args, log);
        } catch (Exception e) {
            running.set(false);
            return StartResult.fail("启动 easytier-core 失败: " + e.getMessage());
        }

        // 本机 MC 端口必须真实在监听，否则虚拟网络里访问不到游戏
        if (!NetUtils.tcpReachable("127.0.0.1", mcPort, 1500)) {
            proc.stop();
            running.set(false);
            return StartResult.fail("本机 MC 端口 " + mcPort + " 不可用，请先对局域网开放或用 /p2p setport 指定端口");
        }

        // 等待连上公共节点
        if (!waitNetworkReady(config.getExtConnectTimeoutMs())) {
            proc.stop();
            running.set(false);
            return StartResult.fail("未连上任何公共节点");
        }
        return StartResult.success();
    }

    /** 等待 EasyTier 至少连上一个非本机节点（公共节点）。 */
    private boolean waitNetworkReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && isRunning()) {
            if (hasAnyPeer()) return true;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        return false;
    }

    /** 查询 peer 列表，判断是否已连上任何非本机节点。 */
    private boolean hasAnyPeer() {
        if (rpcPort <= 0) return false;
        File cli = ExtPaths.cliBin();
        if (cli == null || !cli.isFile()) return false;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(cli.getAbsolutePath());
            cmd.add("--rpc-portal"); cmd.add("127.0.0.1:" + rpcPort);
            cmd.add("-o"); cmd.add("json");
            cmd.add("peer");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            String json = sb.toString().trim();
            if (json.isEmpty() || !json.startsWith("[")) return false;
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement el : arr) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                if (!o.has("cost") || o.get("cost").isJsonNull()) continue;
                if (!"Local".equalsIgnoreCase(o.get("cost").getAsString())) return true;
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /** 客户端加入房间。返回可达的目标地址或错误。 */
    public synchronized ClientTarget startClient(String roomCode, int mcPort, Consumer<String> log) {
        try {
            BinaryInstaller.ensureInstalled(ExtPaths.ExtTool.EASYTIER, new BinaryInstaller.ProgressListener() {
                @Override
                public void onProgress(String stage, long bytes, long total) {
                    // 过滤下载进度，避免刷屏；只提示准备/解压/完成等阶段性信息
                    if (log != null && !stage.startsWith("下载中")) log.accept(stage);
                }
            });
        } catch (BinaryInstaller.ExtException e) {
            return ClientTarget.fail(e.getMessage());
        }
        if (isRunning()) {
            return ClientTarget.fail("EasyTier 客户端会话已存在");
        }
        currentRoomCode = roomCode;
        running.set(true);

        File bin = ExtPaths.resolveBinary(ExtPaths.ExtTool.EASYTIER);
        if (bin == null) {
            running.set(false);
            return ClientTarget.fail("未找到 easytier-core，请放入 " + ExtPaths.toolDir(ExtPaths.ExtTool.EASYTIER).getAbsolutePath());
        }
        File cwd = bin.getParentFile();
        List<String> args = new ArrayList<>();
        // 同 MCT：客户端 --no-tun（免管理员权限），靠 port-forward 访问服务端。
        args.add("--no-tun");
        args.add("--multi-thread");
        // --dhcp 让客户端获得虚拟 IP 并参与隧道路由，缺失时无法转发。
        args.add("--dhcp"); args.add("true");
        args.add("--network-name"); args.add(networkName(roomCode));
        args.add("--network-secret"); args.add(networkSecret(roomCode));
        args.add("--hostname"); args.add(CLIENT_HOSTNAME_PREFIX + Integer.toHexString(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000)));
        args.add("--machine-id"); args.add(machineId());
        args.add("--private-mode"); args.add("true");
        args.add("--tcp-whitelist"); args.add("0");
        args.add("--udp-whitelist"); args.add("0");
        args.add("--listeners"); args.add("tcp://0.0.0.0:0");
        args.add("--listeners"); args.add("udp://0.0.0.0:0");
        this.rpcPort = NetUtils.findFreePort();
        if (this.rpcPort > 0) {
            args.add("--rpc-portal"); args.add("127.0.0.1:" + this.rpcPort);
        }
        args.add("--enable-kcp-proxy");
        args.add("--enable-quic-proxy");
        args.add("--use-smoltcp");
        args.add("--compression"); args.add("zstd");
        args.add("--default-protocol"); args.add("tcp");
        args.add("--encryption-algorithm"); args.add("aes-gcm");
        args.add("--peers");
        args.addAll(resolvePublicNodes(log));

        try {
            proc = startEasyTierProc(bin, cwd, args, log);
        } catch (Exception e) {
            running.set(false);
            return ClientTarget.fail("启动 easytier-core 失败: " + e.getMessage());
        }

        // 先在虚拟网络里找到服务端节点，再建立本地端口转发。
        // 只做本地 TCP 握手不够——服务端不在同一网络时转发会立即断开（Connection reset）。
        int timeout = config.getExtConnectTimeoutMs();
        long deadline = System.currentTimeMillis() + timeout;
        ServerPeer server = null;
        while (System.currentTimeMillis() < deadline && isRunning()) {
            server = findServerPeer();
            if (server != null) break;
            try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
        }
        if (server == null) {
            proc.stop();
            running.set(false);
            return ClientTarget.fail("未发现服务端节点，请确认服务端已 /p2p open 且两端房间号一致");
        }
        if (log != null) log.accept("发现服务端 " + server.ip + ":" + server.mcPort);

        // 建立本地转发 127.0.0.1:<localPort> → <服务端虚拟IP>:<MC端口>，MC 只连本机回环地址。
        // 目标端口取自服务端 hostname，服务端换端口也能自适应。
        int localPort = NetUtils.findFreePort();
        boolean ready = false;
        while (System.currentTimeMillis() < deadline && isRunning() && localPort > 0) {
            if (addPortForward(localPort, server.ip, server.mcPort)) {
                // 转发建立后需留时间让通道就绪，立刻验证容易误判为失败
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                if (verifyForwardAlive(localPort)) {
                    ready = true;
                    break;
                }
            }
            try { Thread.sleep(1200); } catch (InterruptedException e) { break; }
        }
        if (!ready) {
            proc.stop();
            running.set(false);
            return ClientTarget.fail("连接服务端超时（" + (timeout / 1000) + "s）");
        }
        if (log != null) {
            log.accept("本地转发就绪 127.0.0.1:" + localPort);
        }
        return ClientTarget.ok("127.0.0.1", localPort, "easytier");
    }

    /**
     * 查询 EasyTier 的 peer 列表，查找服务端节点（hostname 以 {@link #SERVER_HOSTNAME_PREFIX} 开头）。
     * <p>对齐 MinecraftConnectTool 的做法：用它来确认"服务端确实和我在同一个虚拟网络里"，
     * 并取到服务端真实的虚拟 IP（而不是硬编码）。
     *
     * @return 服务端虚拟 IP；未发现返回 null
     */
    private ServerPeer findServerPeer() {
        if (rpcPort <= 0) return null;
        File cli = ExtPaths.cliBin();
        if (cli == null || !cli.isFile()) return null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(cli.getAbsolutePath());
            cmd.add("--rpc-portal"); cmd.add("127.0.0.1:" + rpcPort);
            cmd.add("-o"); cmd.add("json");
            cmd.add("peer");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String json = sb.toString().trim();
            if (json.isEmpty() || !json.startsWith("[")) return null;
            com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
            for (com.google.gson.JsonElement el : arr) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                if (!o.has("hostname") || o.get("hostname").isJsonNull()) continue;
                String hostname = o.get("hostname").getAsString();
                if (!hostname.startsWith(SERVER_HOSTNAME_PREFIX)) continue;
                if (!o.has("ipv4") || o.get("ipv4").isJsonNull()) continue;
                String ipv4 = o.get("ipv4").getAsString();
                int slash = ipv4.indexOf('/');
                String ip = slash > 0 ? ipv4.substring(0, slash) : ipv4;
                if (ip.isEmpty()) continue;
                // 服务端 MC 端口由 hostname 携带（形如 sp2p-server-25565），换端口也能自适应
                int mcPort = DEFAULT_VIRTUAL_PORT;
                try {
                    mcPort = Integer.parseInt(hostname.substring(SERVER_HOSTNAME_PREFIX.length()).trim());
                } catch (NumberFormatException ignored) {
                }
                return new ServerPeer(ip, mcPort);
            }
        } catch (Exception e) {
            // 查询失败按"未发现"处理，由上层重试
        }
        return null;
    }

    /** 服务端节点信息：虚拟 IP + 其 MC 端口（端口由 hostname 携带）。 */
    private static final class ServerPeer {
        final String ip;
        final int mcPort;
        ServerPeer(String ip, int mcPort) {
            this.ip = ip;
            this.mcPort = mcPort;
        }
    }

    /**
     * 端到端校验：连上本地转发端口后，确认连接不会被立即断开。
     * <p>仅做 TCP 握手是不够的——端口转发只是让 EasyTier 接受了连接，
     * 若隧道到服务端不通，EasyTier 会立刻关闭连接（客户端表现为 Connection reset）。
     * 这里读一次数据：收到数据或超时（连接保持）都算通过；被重置/关闭则视为失败。
     */
    private boolean verifyForwardAlive(int localPort) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", localPort), 2000);
            s.setSoTimeout(2000);
            try {
                int b = s.getInputStream().read();
                return b >= 0;
            } catch (java.net.SocketTimeoutException te) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过 easytier-cli 建立本地端口转发：127.0.0.1:localPort → remoteIp:remotePort。
     * <p>这是 MinecraftConnectTool(ET模式) 的做法：由 EasyTier 自身完成转发，
     * Minecraft 只需连接本地回环地址，不依赖本机 TUN 虚拟网卡路由。
     */
    private boolean addPortForward(int localPort, String remoteIp, int remotePort) {
        File cli = ExtPaths.cliBin();
        if (cli == null || !cli.isFile() || rpcPort <= 0) return false;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(cli.getAbsolutePath());
            cmd.add("--rpc-portal"); cmd.add("127.0.0.1:" + rpcPort);
            cmd.add("port-forward"); cmd.add("add"); cmd.add("tcp");
            cmd.add("127.0.0.1:" + localPort);
            cmd.add(remoteIp + ":" + remotePort);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                while (br.readLine() != null) { /* 排空输出，避免阻塞 */ }
            }
            if (!p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 本次要连接的公共节点：默认并行实测节点池里所有可达节点（按延迟升序），全部失败时回退固定节点。
     */
    private List<String> resolvePublicNodes(Consumer<String> log) {
        if (!config.isAutoSelectNode() && !config.isNodeSelectManual()) {
            return java.util.Collections.singletonList(config.getEasyTierPublicNode());
        }
        try {
            List<String> nodes = PublicNodeSelector.selectAllReachable(log);
            if (!nodes.isEmpty()) return nodes;
            if (log != null) log.accept("无可用节点，使用默认节点");
        } catch (Throwable t) {
            if (log != null) log.accept("节点选优失败: " + t.getMessage());
        }
        return java.util.Collections.singletonList(config.getEasyTierPublicNode());
    }

    /**
     * 启动 easytier-core（Windows 下静默启动，不弹出控制台窗口）。
     * Windows 且非管理员时自动弹出 UAC 提权（RunAs）以创建 TUN 虚拟网卡；否则隐藏窗口启动。
     */
    private ProcessSupervisor startEasyTierProc(File bin, File cwd, List<String> args,
                                                Consumer<String> log) throws Exception {
        if (ExtPaths.isWindows()) {
            File logFile = new File(cwd, "easytier.log");
            // 已启用 --no-tun：不创建虚拟网卡，故无需管理员权限。
            // 不再走 UAC 提权——提权会产生分离的管理员进程，退出时必须以管理员身份
            // taskkill 才能回收（会弹窗/阻塞，玩家感觉"断开后卡住"），且每次启动都弹窗。
            return ProcessSupervisor.startWindowsHidden(bin, args, cwd, logFile);
        }
        return ProcessSupervisor.start(bin, args, cwd, line -> {
            if (log != null) log.accept(line);
        }, () -> {
            if (running.get()) {
                System.err.println("[SimpleP2P] easytier-core 意外退出，可能缺少管理员权限或 TUN 驱动");
            }
        });
    }

    /** 停止会话。 */
    public synchronized void stop() {
        running.set(false);
        if (forwarder != null) { try { forwarder.stop(); } catch (Exception ignored) {} forwarder = null; }
        if (proc != null) { try { proc.stop(); } catch (Exception ignored) {} proc = null; }
        if (probeBind != null) { try { probeBind.close(); } catch (Exception ignored) {} probeBind = null; }
        currentRoomCode = null;
    }

    private boolean waitTunUp(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && isRunning()) {
            ServerSocket s = NetUtils.tryBind(config.getEasyTierServerIp(), 0);
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
                return true;
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        return false;
    }

    // ================== 结果容器 ==================

    public static final class StartResult {
        public final boolean ok;
        public final String error;
        private StartResult(boolean ok, String error) {
            this.ok = ok; this.error = error;
        }
        public static StartResult success() { return new StartResult(true, null); }
        public static StartResult fail(String error) { return new StartResult(false, error); }
    }

    public static final class ClientTarget {
        public final boolean ok;
        public final String host;
        public final int port;
        public final String mode;
        public final String error;
        private ClientTarget(boolean ok, String host, int port, String mode, String error) {
            this.ok = ok; this.host = host; this.port = port; this.mode = mode; this.error = error;
        }
        public static ClientTarget ok(String host, int port, String mode) {
            return new ClientTarget(true, host, port, mode, null);
        }
        public static ClientTarget fail(String error) {
            return new ClientTarget(false, null, 0, null, error);
        }
    }
}
