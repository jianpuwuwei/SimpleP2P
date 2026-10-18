package com.simple_p2p.ext;

import com.simple_p2p.config.ModConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于 aria2c 的多线程 / 多源下载器。
 *
 * <p>aria2c 二进制已内置在 mod 的 jar 资源里（{@code simplep2p/native/win/aria2c.exe}），
 * 首次使用时直接释放到 {@code <安装根>/aria2/}，无需联网下载。非 Windows 平台没有官方
 * 二进制，因此回退到系统 PATH 中的 aria2c；仍不可用时由调用方回退到
 * {@link BinaryInstaller} 的单线程下载。
 */
public final class Aria2Downloader {

    private Aria2Downloader() {}

    /** 内置的 Windows 64 位二进制在 jar 中的路径。 */
    private static final String EMBEDDED_WIN_RESOURCE = "/simplep2p/native/win/aria2c.exe";

    /** 已解析到的可执行文件；未解析时为 null。 */
    private static volatile File cachedBin;
    private static volatile boolean resolved;

    /** aria2c 可执行文件名（含平台后缀）。 */
    private static String binName() {
        return ExtPaths.isWindows() ? "aria2c.exe" : "aria2c";
    }

    /** aria2 工具目录：{@code <安装根>/aria2}。 */
    public static File toolDir() {
        return new File(ExtPaths.installRoot(), "aria2");
    }

    /** 定位可用的 aria2c：受管目录 → 释放内置资源 → 系统 PATH；都没有返回 null。 */
    public static File binary() {
        if (resolved) return cachedBin;
        File managed = new File(toolDir(), binName());
        if (managed.isFile() && managed.length() > 0) {
            cachedBin = managed;
            resolved = true;
            return cachedBin;
        }
        if (extractEmbedded(managed)) {
            cachedBin = managed;
            resolved = true;
            return cachedBin;
        }
        cachedBin = findOnPath();
        resolved = true;
        return cachedBin;
    }

    /** 判断当前平台是否有内置二进制可释放（目前仅 Windows 64 位）。 */
    private static String embeddedResource() {
        if (!ExtPaths.isWindows()) return null;
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.contains("amd64") && !arch.contains("x86_64")) return null;
        return EMBEDDED_WIN_RESOURCE;
    }

    /** 把内置的 aria2c 从 jar 资源释放到受管目录；没有内置资源或失败返回 false。 */
    private static boolean extractEmbedded(File dest) {
        String res = embeddedResource();
        if (res == null) return false;
        try (InputStream in = Aria2Downloader.class.getResourceAsStream(res)) {
            if (in == null) return false;
            File dir = dest.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            File tmp = new File(dir, dest.getName() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (!ExtPaths.isWindows()) dest.setExecutable(true, false);
            return dest.isFile() && dest.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 在系统 PATH 里查找 aria2c（非 Windows 平台的主要来源）。 */
    private static File findOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) return null;
        String name = binName();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir == null || dir.isBlank()) continue;
            File f = new File(dir, name);
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * 用 aria2c 多线程（16 连接 / 16 分片）且多镜像源下载到 target。
     * @param urls 同一文件的多个镜像地址，aria2 会作为镜像源并行使用
     * @return 成功返回 true；aria2 不可用或失败返回 false（调用方回退单线程）
     */
    public static boolean download(List<String> urls, File target, BinaryInstaller.ProgressListener l) {
        if (urls == null || urls.isEmpty()) return false;
        if (!ModConfig.getInstance().isAria2Download()) return false;
        File bin = binary();
        if (bin == null) return false;
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;

        File control = new File(target.getAbsolutePath() + ".aria2");
        deleteQuietly(target);
        deleteQuietly(control);

        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("--dir=" + (dir != null ? dir.getAbsolutePath() : "."));
        cmd.add("--out=" + target.getName());
        cmd.add("--continue=false");
        cmd.add("--allow-overwrite=true");
        cmd.add("--auto-file-renaming=false");
        cmd.add("--file-allocation=none");
        cmd.add("--max-connection-per-server=16");
        cmd.add("--split=16");
        cmd.add("--min-split-size=1M");
        cmd.add("--max-tries=3");
        cmd.add("--retry-wait=1");
        cmd.add("--connect-timeout=15");
        cmd.add("--timeout=60");
        cmd.add("--console-log-level=error");
        cmd.add("--summary-interval=0");
        cmd.add("--show-console-readout=false");
        cmd.add("--user-agent=Mozilla/5.0 SimpleP2P-Mod");
        if (ModConfig.getInstance().isIgnoreSslVerify()) cmd.add("--check-certificate=false");
        cmd.addAll(urls);

        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            drain(p);
            long lastReported = 0;
            while (p.isAlive()) {
                try { Thread.sleep(400); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (l == null) continue;
                long done = target.length();
                if (done - lastReported >= 2L * 1024 * 1024) {
                    lastReported = done;
                    l.onProgress("下载中 " + (done / 1024 / 1024) + "MB", done, 0);
                }
            }
            int code = p.waitFor();
            if (code == 0 && target.isFile() && target.length() > 0) return true;
            deleteQuietly(target);
            deleteQuietly(control);
            return false;
        } catch (Exception e) {
            deleteQuietly(target);
            deleteQuietly(control);
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    /** 后台排空 aria2 输出，避免管道写满导致进程阻塞。 */
    private static void drain(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (br.readLine() != null) { /* 丢弃 */ }
            } catch (Exception ignored) {
            }
        }, "SimpleP2P-Aria2Out");
        t.setDaemon(true);
        t.start();
    }

    private static void deleteQuietly(File f) {
        try { if (f != null) f.delete(); } catch (Exception ignored) {}
    }
}
