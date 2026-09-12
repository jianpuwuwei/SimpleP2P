package com.simple_p2p.ext;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 单个外部进程的监督器：启动、逐行读取输出、环形缓冲、停止(destroy→forcibly)、
 * 意外退出回调、按关键字等待就绪。
 */
public final class ProcessSupervisor {

    private final Process process;
    private final Thread readerThread;
    private final Deque<String> ring = new ArrayDeque<>();
    private final int maxLines = 50;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final List<WaitEntry> waiters = new ArrayList<>();
    private final Consumer<String> pumpConsumer;
    private final Runnable onExit;
    /** 非 null 表示 Windows UAC 提权分离启动（RunAs），无法直接管理 process，改按 PID 清理。 */
    private final Long elevatedPid;
    /** 隐藏启动时的输出日志文件（提权分离进程无法用管道读输出，改读该文件）。 */
    private final File logFile;

    private ProcessSupervisor(Process p, Consumer<String> onLine, Runnable onExit) {
        this(p, onLine, onExit, null, null);
    }

    private ProcessSupervisor(Process p, Consumer<String> onLine, Runnable onExit,
                              Long elevatedPid, File logFile) {
        this.process = p;
        this.pumpConsumer = onLine;
        this.onExit = onExit;
        this.elevatedPid = elevatedPid;
        this.logFile = logFile;
        if (p != null) {
            this.readerThread = new Thread(this::pump, "SimpleP2P-ProcOut");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        } else {
            this.readerThread = null;
        }
    }

    /** 启动进程。cwd 为 null 时使用当前目录。 */
    public static ProcessSupervisor start(File bin, List<String> args, File cwd,
                                          Consumer<String> onLine) throws IOException {
        return start(bin, args, cwd, onLine, null);
    }

    public static ProcessSupervisor start(File bin, List<String> args, File cwd,
                                          Consumer<String> onLine, Runnable onExit) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) pb.directory(cwd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        return new ProcessSupervisor(p, onLine, onExit);
    }

    /**
     * Windows 下隐藏窗口启动（不弹出控制台黑窗），输出重定向到 logFile 便于排错。
     * 返回的 supervisor 按 PID 管理（隐藏启动的进程同样拿不到管道输出）。
     */
    public static ProcessSupervisor startWindowsHidden(File bin, List<String> args, File cwd, File logFile)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("$p = Start-Process -FilePath '").append(bin.getAbsolutePath()).append("'");
        sb.append(" -ArgumentList '").append(String.join("','", args)).append("'");
        if (cwd != null) sb.append(" -WorkingDirectory '").append(cwd.getAbsolutePath()).append("'");
        sb.append(" -WindowStyle Hidden");
        if (logFile != null) {
            File err = new File(logFile.getAbsolutePath() + ".err");
            sb.append(" -RedirectStandardOutput '").append(logFile.getAbsolutePath()).append("'");
            sb.append(" -RedirectStandardError '").append(err.getAbsolutePath()).append("'");
        }
        sb.append(" -PassThru; if ($p) { $p.Id } else { Write-Error 'start failed' }");
        return runPowerShellForPid(sb.toString(), logFile);
    }

    /**
     * Windows 下以 UAC 提权启动，并附带一个"提权看门狗"脚本。
     *
     * <p>为什么需要看门狗：提权启动的进程属于管理员权限，普通权限的 taskkill 无法终止它
     * （Access denied），导致游戏退出后核心残留。这里让提权脚本自己负责收尾——
     * 启动 EasyTier 后写入其 PID，然后等待 Minecraft 进程退出，再强制结束 EasyTier
     * （脚本自身是提权的，所以能杀掉 EasyTier），这样退出时无需再次弹 UAC。
     *
     * <p>另外脚本本身已提权，因此可以正常使用输出重定向，从而拿到日志（提权进程的 stdout 无法被父进程捕获）。
     *
     * @param pidFile 提权脚本把 EasyTier 的 PID 写到这里，供本进程读取
     * @param logFile 提权脚本把 EasyTier 的输出重定向到这里
     */
    public static ProcessSupervisor startWindowsElevated(File bin, List<String> args, File cwd,
                                                         File pidFile, File logFile) throws IOException {
        long mcPid = ProcessHandle.current().pid();
        StringBuilder inner = new StringBuilder();
        inner.append("$et = Start-Process -FilePath '").append(bin.getAbsolutePath()).append("'");
        inner.append(" -ArgumentList '").append(String.join("','", args)).append("'");
        if (cwd != null) inner.append(" -WorkingDirectory '").append(cwd.getAbsolutePath()).append("'");
        inner.append(" -WindowStyle Hidden");
        if (logFile != null) {
            inner.append(" -RedirectStandardOutput '").append(logFile.getAbsolutePath()).append("'");
            inner.append(" -RedirectStandardError '").append(logFile.getAbsolutePath()).append(".err'");
        }
        inner.append(" -PassThru; ");
        inner.append("Set-Content -Path '").append(pidFile.getAbsolutePath()).append("' -Value $et.Id -Encoding ascii; ");
        // 等待 Minecraft 退出后清理 EasyTier（脚本提权，故可终止管理员进程）
        inner.append("Wait-Process -Id ").append(mcPid).append(" -ErrorAction SilentlyContinue; ");
        inner.append("Stop-Process -Id $et.Id -Force -ErrorAction SilentlyContinue");

        File scriptFile = new File(pidFile.getParentFile(), "easytier-launch.ps1");
        java.nio.file.Files.writeString(scriptFile.toPath(), inner.toString(), StandardCharsets.UTF_8);
        try { java.nio.file.Files.deleteIfExists(pidFile.toPath()); } catch (Exception ignored) {}

        // 外层：请求 UAC 提权执行上面的脚本（-ExecutionPolicy Bypass 允许执行 ps1）
        String outer = "Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList "
                + "'-NoProfile','-ExecutionPolicy','Bypass','-File','" + scriptFile.getAbsolutePath() + "'";
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", outer);
        pb.redirectErrorStream(true);
        Process starter = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(starter.getInputStream(), StandardCharsets.UTF_8))) {
            while (br.readLine() != null) { /* 排空输出，避免阻塞 */ }
            starter.waitFor(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 UAC 提权被中断", e);
        }

        // 轮询读取提权脚本写回的 PID
        Long pid = null;
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (pidFile.isFile()) {
                    String s = java.nio.file.Files.readString(pidFile.toPath(), StandardCharsets.UTF_8).trim();
                    if (s.matches("\\d+")) { pid = Long.parseLong(s); break; }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (pid == null) {
            throw new IOException("UAC 提权启动失败或被取消（未取得 EasyTier 进程 PID）");
        }
        return new ProcessSupervisor(null, null, null, pid, logFile);
    }

    /** 用 powershell 执行脚本并读取其输出的 PID（第一行纯数字）。 */
    private static ProcessSupervisor runPowerShellForPid(String script, File logFile) throws IOException {
        // 注意：第一个参数必须是可执行程序本身（powershell），否则会被当作程序名导致 error=2
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            Long pid = null;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.matches("\\d+")) { pid = Long.parseLong(t); break; }
            }
            p.waitFor();
            if (pid == null) {
                throw new IOException("启动失败或被用户取消（未取得进程 PID）");
            }
            return new ProcessSupervisor(null, null, null, pid, logFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待启动结果被中断", e);
        }
    }

    private void pump() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                synchronized (ring) {
                    ring.addLast(line);
                    while (ring.size() > maxLines) ring.removeFirst();
                }
                if (pumpConsumer != null) {
                    try { pumpConsumer.accept(line); } catch (Exception ignored) {}
                }
                synchronized (waiters) {
                    for (WaitEntry w : new ArrayList<>(waiters)) {
                        if (line.contains(w.keyword)) {
                            w.latch.countDown();
                            waiters.remove(w);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            alive.set(false);
            synchronized (waiters) {
                for (WaitEntry w : waiters) w.latch.countDown();
                waiters.clear();
            }
            if (onExit != null) {
                try { onExit.run(); } catch (Exception ignored) {}
            }
        }
    }

    /** 进程是否仍存活。 */
    public boolean isAlive() {
        if (elevatedPid != null) return processAliveByPid(elevatedPid);
        return alive.get() && process.isAlive();
    }

    public int exitValue() {
        if (elevatedPid != null) return -1;
        try {
            return process.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 停止进程：destroy → 等3s → destroyForcibly；提权分离进程按 PID taskkill（必要时提权重试）。 */
    public void stop() {
        alive.set(false);
        if (elevatedPid != null) {
            taskkillPid(elevatedPid);
            // 提权启动的进程属于管理员权限，普通 taskkill 会被拒绝（Access denied），
            // 必须以管理员身份重试；否则进程残留会继续占用虚拟 IP 和 11010 端口，
            // 导致后续连接走到回环/启动失败。
            if (processAliveByPid(elevatedPid)) {
                taskkillPidElevated(elevatedPid);
            }
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** 以管理员身份结束进程（会弹 UAC，仅在普通 taskkill 失败时使用）。 */
    private static void taskkillPidElevated(long pid) {
        try {
            String script = "Start-Process -FilePath 'taskkill' -ArgumentList '/PID','" + pid
                    + "','/T','/F' -Verb RunAs -WindowStyle Hidden";
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
            pb.redirectErrorStream(true);
            pb.start();
        } catch (Exception ignored) {
        }
    }

    private static boolean processAliveByPid(long pid) {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV")
                    .redirectErrorStream(true).start();
            String out;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) sb.append(l);
                out = sb.toString();
            }
            p.destroyForcibly();
            return out.contains("\"" + pid + "\"");
        } catch (Exception e) {
            return false;
        }
    }

    private static void taskkillPid(long pid) {
        try {
            new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                    .redirectErrorStream(true).start();
        } catch (Exception ignored) {}
    }

    /** 最近 n 行输出（新→旧顺序）。隐藏/提权启动时管道无输出，改为读取日志文件尾部。 */
    public String[] tail(int n) {
        synchronized (ring) {
            if (ring.isEmpty() && logFile != null && logFile.isFile()) {
                return tailFromFile(n);
            }
            List<String> list = new ArrayList<>(ring);
            int from = Math.max(0, list.size() - n);
            return list.subList(from, list.size()).toArray(new String[0]);
        }
    }

    private String[] tailFromFile(int n) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - n);
            return lines.subList(from, lines.size()).toArray(new String[0]);
        } catch (Exception e) {
            return new String[0];
        }
    }

    /** 等待某关键字出现于输出，超时返回 false。 */
    public boolean waitForLine(String keyword, long timeoutMs) {
        synchronized (ring) {
            for (String l : ring) if (l.contains(keyword)) return true;
        }
        WaitEntry w = new WaitEntry(keyword);
        synchronized (waiters) {
            if (!isAlive()) return false;
            waiters.add(w);
        }
        try {
            return w.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class WaitEntry {
        final String keyword;
        final CountDownLatch latch = new CountDownLatch(1);
        WaitEntry(String keyword) { this.keyword = keyword; }
    }
}
