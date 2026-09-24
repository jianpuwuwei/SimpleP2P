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
 * <p>节点名映射：服务端 {@code openP2PServerNodePrefix + roomCode}，客户端
 * {@code openP2PClientNodePrefix + roomCode + "-" + 4hex}。两侧必须使用同一个 token。
 *
 * <p>Windows 版 openp2p.exe 的清单要求管理员权限，因此 Windows 下以 UAC 提权 + 隐藏窗口启动，
 * 由提权看门狗脚本负责在游戏退出或收到停机标记时回收进程，运行期不会重复弹 UAC。
 */
public final class OpenP2PManager {

    private final ModConfig config;
    private ProcessSupervisor proc;
    private String currentRoomCode;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private long procStartTime;

    public OpenP2PManager() {
        this.config = ModConfig.getInstance();
    }

    public boolean isRunning() {
        return running.get() && proc != null && proc.isAlive();
    }

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

    /** 查找 openp2p 可执行文件：先看 mod 自带目录，再看 Windows 手动安装的常见路径。 */
    public static File findBinary() {
        File auto = ExtPaths.resolveBinary(ExtPaths.ExtTool.OPENP2P);
        if (auto != null) return auto;
        if (!ExtPaths.isWindows()) return null;
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

    /** 服务端开启房间。需要 token。 */
    public synchronized StartResult startServer(String roomCode, String token, int mcPort, Consumer<String> log) {
        if (token == null || token.trim().isEmpty()) {
            return StartResult.fail("OpenP2P 模式需要 Token，请先设置 Token");
        }
        File bin = prepareBinary(log);
        if (bin == null) return StartResult.fail("未找到 openp2p，可放入 " + ExtPaths.toolDir(ExtPaths.ExtTool.OPENP2P).getAbsolutePath());
        if (isRunning()) return StartResult.fail("OpenP2P 房间已开启: " + currentRoomCode);

        currentRoomCode = roomCode;
        running.set(true);
        List<String> args = new ArrayList<>();
        args.add("-node"); args.add(serverNodeName(roomCode));
        args.add("-token"); args.add(token.trim());
        args.add("-sharebandwidth"); args.add("10");

        try {
            proc = startProc(bin, args, log);
            procStartTime = System.currentTimeMillis();
        } catch (Exception e) {
            running.set(false);
            return StartResult.fail("启动 openp2p 失败: " + e.getMessage());
        }
        if (!waitAlive(config.getExtConnectTimeoutMs() * 3 / 4)) {
            proc.stop();
            running.set(false);
            return StartResult.fail("OpenP2P 服务端启动失败，请检查 Token 是否正确");
        }
        return StartResult.success();
    }

    /** 客户端加入房间，返回本地代理地址。 */
    public synchronized ClientTarget startClient(String roomCode, String token, int mcPort, Consumer<String> log) {
        if (token == null || token.trim().isEmpty()) {
            return ClientTarget.fail("OpenP2P 模式需要 Token，请先设置 Token");
        }
        File bin = prepareBinary(log);
        if (bin == null) return ClientTarget.fail("未找到 openp2p，可放入 " + ExtPaths.toolDir(ExtPaths.ExtTool.OPENP2P).getAbsolutePath());
        if (isRunning()) return ClientTarget.fail("OpenP2P 客户端会话已存在");

        int srcPort = NetUtils.findFreePort();
        if (srcPort <= 0) {
            return ClientTarget.fail("无法分配本地监听端口");
        }
        currentRoomCode = roomCode;
        running.set(true);

        List<String> args = new ArrayList<>();
        args.add("-node"); args.add(clientNodeName(roomCode));
        args.add("-token"); args.add(token.trim());
        args.add("-sharebandwidth"); args.add("10");
        args.add("-appname"); args.add("mc-" + roomCode);
        args.add("-peernode"); args.add(serverNodeName(roomCode));
        args.add("-dstip"); args.add("127.0.0.1");
        args.add("-dstport"); args.add(String.valueOf(mcPort));
        args.add("-srcport"); args.add(String.valueOf(srcPort));
        args.add("-protocol"); args.add("tcp");

        try {
            proc = startProc(bin, args, log);
        } catch (Exception e) {
            running.set(false);
            return ClientTarget.fail("启动 openp2p 失败: " + e.getMessage());
        }

        // 等待本地代理端口就绪
        long deadline = System.currentTimeMillis() + config.getExtConnectTimeoutMs();
        boolean ready = false;
        while (System.currentTimeMillis() < deadline && isRunning()) {
            if (NetUtils.tcpReachable("127.0.0.1", srcPort, 1200)) {
                ready = true;
                break;
            }
            try { Thread.sleep(800); } catch (InterruptedException e) { break; }
        }
        if (!ready) {
            proc.stop();
            running.set(false);
            return ClientTarget.fail("连接服务端超时，请确认服务端已开启且两端 Token 一致");
        }
        return ClientTarget.ok("127.0.0.1", srcPort, "openp2p");
    }

    /** 确保可执行文件就绪（缺失时按配置自动下载）。 */
    private File prepareBinary(Consumer<String> log) {
        File bin = findBinary();
        if (bin != null && bin.isFile()) return bin;
        try {
            BinaryInstaller.ensureInstalled(ExtPaths.ExtTool.OPENP2P, (stage, bytes, total) -> {
                if (log != null && !stage.startsWith("下载中")) log.accept(stage);
            });
        } catch (BinaryInstaller.ExtException e) {
            if (log != null) log.accept(e.getMessage());
            return null;
        }
        return ExtPaths.resolveBinary(ExtPaths.ExtTool.OPENP2P);
    }

    /** Windows 提权隐藏启动（openp2p.exe 要求管理员权限），其它平台普通启动。 */
    private ProcessSupervisor startProc(File bin, List<String> args, Consumer<String> log) throws Exception {
        File cwd = bin.getParentFile();
        if (ExtPaths.isWindows()) {
            File logFile = new File(cwd, "openp2p.log");
            File pidFile = new File(cwd, "openp2p.pid");
            return ProcessSupervisor.startWindowsElevated(bin, args, cwd, pidFile, logFile, "openp2p-launch");
        }
        return ProcessSupervisor.start(bin, args, cwd, line -> {
            if (log != null) log.accept(line);
        }, null);
    }

    /** 进程存活满 5 秒即视为启动成功。 */
    private boolean waitAlive(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) return false;
            if (System.currentTimeMillis() - procStartTime > 5000) return true;
            try { Thread.sleep(400); } catch (InterruptedException e) { break; }
        }
        return proc.isAlive();
    }

    /** 停止会话。 */
    public synchronized void stop() {
        running.set(false);
        if (proc != null) {
            try { proc.stop(); } catch (Exception ignored) {}
            proc = null;
        }
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
