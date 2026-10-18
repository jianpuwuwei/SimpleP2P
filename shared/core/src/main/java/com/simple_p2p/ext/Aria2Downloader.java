package com.simple_p2p.ext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.simple_p2p.config.ModConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于 aria2c 的多线程 / 多源下载器。
 *
 * <p>优先使用系统已安装的 aria2c；Windows 上找不到时自动从 GitHub release 拉取官方
 * win-64bit 构建并解压到 {@code <安装根>/aria2/}。aria2 不可用或下载失败时由调用方
 * 回退到 {@link BinaryInstaller} 的单线程下载，保证功能不受影响。
 */
public final class Aria2Downloader {

    private Aria2Downloader() {}

    /** aria2 官方仓库。 */
    private static final String REPO = "aria2/aria2";

    /** GitHub API 不可用时的固定兜底地址（官方 Windows 构建）。 */
    private static final String FALLBACK_WIN_ZIP =
            "https://github.com/aria2/aria2/releases/download/release-1.37.0/"
                    + "aria2-1.37.0-win-64bit-build1.zip";

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

    /** 定位可用的 aria2c：受管目录 → 系统 PATH；都没有返回 null。 */
    public static File binary() {
        if (resolved) return cachedBin;
        File std = new File(toolDir(), binName());
        if (std.isFile()) {
            cachedBin = std;
            resolved = true;
            return cachedBin;
        }
        cachedBin = findOnPath();
        resolved = true;
        return cachedBin;
    }

    /** 在系统 PATH 里查找 aria2c。 */
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
     * 确保 aria2c 可用：系统已有则直接用；Windows 下缺失时自动下载官方构建。
     * <p>任何失败都只是放弃 aria2（调用方回退单线程下载），不抛异常。
     */
    public static void ensureBinary(BinaryInstaller.ProgressListener l) {
        if (binary() != null) return;
        if (!ExtPaths.isWindows()) return; // 官方只提供 Windows 构建，其它平台依赖系统安装
        try {
            String url = latestWindowsAssetUrl();
            if (url == null) url = FALLBACK_WIN_ZIP;
            if (l != null) l.onProgress("准备下载 aria2 ...", 0, 0);
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            File archive = BinaryInstaller.downloadFromMirrors(url, fileName, l);
            if (archive == null) return;
            BinaryInstaller.extractAndInstallBinary(archive, binName(), new File(toolDir(), binName()));
            try { archive.delete(); } catch (Exception ignored) {}
            resolved = false; // 重新解析，让 binary() 找到刚安装的文件
        } catch (Throwable ignored) {
            // aria2 安装失败不影响主流程
        }
    }

    /**
     * 用 aria2c 多线程（受管 16 连接 / 16 分片）且多镜像源下载到 target。
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

    /** 查询 aria2 最新 release 里当前平台（Windows 64 位）的压缩包直链；失败返回 null。 */
    private static String latestWindowsAssetUrl() {
        String body = BinaryInstaller.httpGet("https://api.github.com/repos/" + REPO + "/releases/latest", 8000);
        if (body == null) return null;
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            JsonArray assets = o.getAsJsonArray("assets");
            if (assets == null) return null;
            for (JsonElement el : assets) {
                JsonObject a = el.getAsJsonObject();
                JsonElement nameEl = a.get("name");
                JsonElement urlEl = a.get("browser_download_url");
                if (nameEl == null || nameEl.isJsonNull() || urlEl == null || urlEl.isJsonNull()) continue;
                String name = nameEl.getAsString().toLowerCase(Locale.ROOT);
                if (name.contains("win-64bit") && name.endsWith(".zip")) return urlEl.getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
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
