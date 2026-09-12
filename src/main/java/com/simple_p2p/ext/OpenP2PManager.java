package com.simple_p2p.ext;

import com.simple_p2p.config.ModConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * OpenP2P 官方客户端(openp2p)的组网管理。
 *
 * <p>节点名映射：服务端 {@code openP2PServerNodePrefix + roomCode}（确定性，两侧同源），
 * 客户端 {@code openP2PClientNodePrefix + roomCode + "-" + 4hex}（每客户端唯一）。
 * 两侧必须使用同一个 token。
 *
 * <p>Windows 官方无独立命令行包，只有 setup.exe：此处探测已安装的 openp2p.exe，
 * 未命中则提示手动安装。Linux/macOS 由 {@link BinaryInstaller} 自动下载。
 */
public final class OpenP2PManager {

    private final ModConfig config;
    private ProcessSupervisor proc;
    private String currentRoomCode;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OpenP2PManager() {
        this.config = ModConfig.getInstance();
    }

    public boolean isRunning() {
        return running.get() && proc != null && proc.isAlive();
    }

    public String getCurrentRoomCode() { return currentRoomCode; }

    public String[] recentOutput(int n) {
        return proc != null ? proc.tail(n) : new String[0];
    }

    public static String serverNodeName(String roomCode) {
        return ModConfig.getInstance().getOpenP2PServerNodePrefix() + roomCode;
    }

    public static String clientNodeName(String roomCode) {
        String hex = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        while (hex.length() < 4) hex = "0" + hex;
        return ModConfig.getInstance().getOpenP2PClientNodePrefix() + roomCode + "-" + hex;
    }

    /** 探测 openp2p 二进制位置；Windows 返回手动安装路径或 null。 */
    public static File findBinary() {
        // 1) 已放置/已下载的目录（含 mods/simplep2p 下任意子目录）
        File auto = ExtPaths.resolveBinary(ExtPaths.ExtTool.OPENP2P);
        if (auto != null) return auto;
        if (!ExtPaths.isWindows()) return null;
        // 2) Windows 手动安装的常见路径
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\OpenP2P\\openp2p.exe",
                System.getenv("ProgramFiles(x86)") + "\\OpenP2P\\openp2p.exe",
                System.getenv("LOCALAPPDATA") + "\\OpenP2P\\openp2p.exe",
        };
        for (String c : candidates) {
            if (c != null && new File(c).isFile()) return new File(c);
        }
        return null;
    }

    /** 服务端启动房间。需 token。 */
    public synchronized StartResult startServer(String roomCode, String token, int mcPort, Consumer<String> log) {
        if (token == null || token.trim().isEmpty()) {
            return StartResult.fail("OpenP2P 模式需要 Token，请先 /p2p settoken <Token>");
        }
        File bin = prepareBinary(log);
        if (bin == null) return StartResult.fail(windowsHint());
        if (isRunning()) return StartResult.fail("OpenP2P 房间已开启: " + currentRoomCode);

        currentRoomCode = roomCode;
        running.set(true);
        File cwd = bin.getParentFile();
        List<String> args = new ArrayList<>();
        args.add("-node"); args.add(serverNodeName(roomCode));
        args.add("-token"); args.add(token.trim());
        args.add("-sharebandwidth"); args.add("0"); // 不共享，避免意外占用带宽

        try {
            proc = ProcessSupervisor.start(bin, args, cwd, line -> {
                if (log != null) log.accept(line);
            }, null);
            procStartTime = System.currentTimeMillis();
        } catch (Exception e) {
            running.set(false);
            return StartResult.fail("启动 openp2p 失败: " + e.getMessage());
        }
        // 就绪：进程存活≥5s 或出现关键字
        boolean alive = waitAlive(config.getExtConnectTimeoutMs() * 3 / 4);
        if (!alive) {
            String out = String.join(" | ", proc.tail(6));
            proc.stop();
            running.set(false);
            return StartResult.fail("OpenP2P 服务端启动失败（进程退出）。输出: " + out
                    + "。请检查 Token 是否正确。");
        }
        return StartResult.success();
    }

    /** 客户端加入房间。返回本地代理地址或错误。 */
    public synchronized ClientTarget startClient(String roomCode, String token, int mcPort, Consumer<String> log) {
        if (token == null || token.trim().isEmpty()) {
            return ClientTarget.fail("OpenP2P 模式需要 Token，请先 /p2p settoken <Token>");
        }
        File bin = prepareBinary(log);
        if (bin == null) return ClientTarget.fail(windowsHint());
        if (isRunning()) return ClientTarget.fail("OpenP2P 客户端会话已存在");

        currentRoomCode = roomCode;
        running.set(true);
        File cwd = bin.getParentFile();
        int srcPort = NetUtils.findFreePort();
        if (srcPort <= 0) {
            running.set(false);
            return ClientTarget.fail("无法分配本地监听端口");
        }
        List<String> args = new ArrayList<>();
        args.add("-node"); args.add(clientNodeName(roomCode));
        args.add("-token"); args.add(token.trim());
        args.add("-appname"); args.add("mc-" + roomCode);
        args.add("-peernode"); args.add(serverNodeName(roomCode));
        args.add("-dstip"); args.add("127.0.0.1");
        args.add("-dstport"); args.add(String.valueOf(mcPort));
        args.add("-srcport"); args.add(String.valueOf(srcPort));

        try {
            proc = ProcessSupervisor.start(bin, args, cwd, line -> {
                if (log != null) log.accept(line);
            }, null);
        } catch (Exception e) {
            running.set(false);
            return ClientTarget.fail("启动 openp2p 失败: " + e.getMessage());
        }

        // 等待本地代理端口就绪
        long deadline = System.currentTimeMillis() + config.getExtConnectTimeoutMs();
        boolean ready = false;
        while (System.currentTimeMillis() < deadline && isRunning()) {
            if (NetUtils.tcpReachable("127.0.0.1", srcPort, 1200)) { ready = true; break; }
            try { Thread.sleep(800); } catch (InterruptedException e) { break; }
        }
        if (!ready) {
            String out = String.join(" | ", proc.tail(6));
            proc.stop();
            running.set(false);
            return ClientTarget.fail("OpenP2P 连接服务端失败（超时）。请确认服务端已开启且 Token 相同。输出: " + out);
        }
        return ClientTarget.ok("127.0.0.1", srcPort, "openp2p");
    }

    private File prepareBinary(Consumer<String> log) {
        File bin = findBinary();
        if (bin != null && bin.isFile()) return bin;
        if (ExtPaths.isWindows()) return null;
        try {
            BinaryInstaller.ensureInstalled(ExtPaths.ExtTool.OPENP2P, new BinaryInstaller.ProgressListener() {
                @Override
                public void onProgress(String stage, long bytes, long total) {
                    // 过滤下载进度，避免刷屏
                    if (log != null && !stage.startsWith("下载中")) log.accept(stage);
                }
            });
        } catch (BinaryInstaller.ExtException e) {
            System.err.println("[SimpleP2P] OpenP2P 安装失败: " + e.getMessage());
            return null;
        }
        return ExtPaths.resolveBinary(ExtPaths.ExtTool.OPENP2P);
    }

    private String windowsHint() {
        return "Windows 版 OpenP2P 无独立命令行版。请从 https://openp2p.cn 下载安装 setup.exe 后重试，"
                + "mod 会自动识别安装位置。";
    }

    private boolean waitAlive(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) return false;
            // 存活满 5 秒即可视为已启动
            if (System.currentTimeMillis() - procStartTime > 5000) return true;
            try { Thread.sleep(400); } catch (InterruptedException e) { break; }
        }
        return proc.isAlive();
    }

    private long procStartTime;

    /** 停止会话。 */
    public synchronized void stop() {
        running.set(false);
        if (proc != null) { try { proc.stop(); } catch (Exception ignored) {} proc = null; }
        currentRoomCode = null;
    }

    // ================== 结果容器 ==================

    public static final class StartResult {
        public final boolean ok;
        public final String error;
        private StartResult(boolean ok, String error) { this.ok = ok; this.error = error; }
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
