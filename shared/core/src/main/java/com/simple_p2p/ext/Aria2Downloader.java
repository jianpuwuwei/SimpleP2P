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
 * <p>Windows：内置 64 位二进制（{@code simplep2p/native/win/aria2c.exe}）随 jar 分发，
 * 首次使用时释放到 {@code <安装根>/aria2/}，无需联网。
 *
 * <p>Linux / macOS：aria2 官方未发布对应平台二进制，故不内嵌，只在系统 PATH 与常见
 * 安装目录中探测用户自行安装的 aria2c；找不到时通过 {@link #installHint()} 返回安装命令
 * 交给调用方提示，并回退到 {@link BinaryInstaller} 的单线程下载（不自动执行任何安装命令）。
 *
 * <p>所有平台都可在设置界面（配置项 {@code aria2BinPath}）手动指定 aria2c 的可执行文件
 * 或其所在目录，手动指定的路径优先于上面的自动探测。
 */
public final class Aria2Downloader {

    private Aria2Downloader() {}

    /** 内置的 Windows 64 位二进制在 jar 中的路径。 */
    private static final String EMBEDDED_WIN_RESOURCE = "/simplep2p/native/win/aria2c.exe";

    /** aria2c 可执行文件名（含平台后缀）。 */
    private static String binName() {
        return ExtPaths.isWindows() ? "aria2c.exe" : "aria2c";
    }

    /** aria2 工具目录：{@code <安装根>/aria2}。 */
    public static File toolDir() {
        return new File(ExtPaths.installRoot(), "aria2");
    }

    /**
     * 定位可用的 aria2c，每次调用都重新解析（配置可能在设置界面被随时修改）。
     * <p>优先级：配置里手动指定的路径 → Windows 受管目录（释放内置二进制）→
     * 系统 PATH 与平台常见目录。都没有返回 null。
     */
    public static File binary() {
        File configured = configuredBinary();
        if (configured != null) return configured;
        if (ExtPaths.isWindows()) {
            File managed = new File(toolDir(), binName());
            if (managed.isFile() && managed.length() > 0) return managed;
            if (extractEmbedded(managed)) return managed;
        }
        return findOnPath();
    }

    /** 读取配置中手动指定的 aria2c：可直接指向可执行文件或其所在目录；未配置 / 无效返回 null。 */
    private static File configuredBinary() {
        String p = ModConfig.getInstance().getAria2BinPath();
        if (p == null || p.isBlank()) return null;
        File f = new File(p.trim());
        if (f.isFile()) return f;
        if (f.isDirectory()) {
            File bin = new File(f, binName());
            if (bin.isFile()) return bin;
        }
        return null;
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

    /** 在系统 PATH 与平台常见安装目录里查找 aria2c（Linux / macOS 的主要来源）。 */
    private static File findOnPath() {
        String name = binName();
        String path = System.getenv("PATH");
        if (path != null && !path.isEmpty()) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) continue;
                File f = new File(dir, name);
                if (f.isFile()) return f;
            }
        }
        // 图形化启动的 MC 客户端 PATH 可能不含包管理器路径，补充探测常见目录
        for (File dir : commonDirs()) {
            File f = new File(dir, name);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** 各平台 aria2c 常见的安装目录。 */
    private static List<File> commonDirs() {
        List<File> dirs = new ArrayList<>();
        if (ExtPaths.isMac()) {
            dirs.add(new File("/opt/homebrew/bin")); // Apple Silicon Homebrew
            dirs.add(new File("/usr/local/bin"));    // Intel Homebrew / 手动安装
            dirs.add(new File("/opt/local/bin"));    // MacPorts
        } else if (ExtPaths.isLinux()) {
            dirs.add(new File("/usr/bin"));
            dirs.add(new File("/usr/local/bin"));
            dirs.add(new File("/bin"));
            dirs.add(new File("/snap/bin"));
            dirs.add(new File("/var/lib/flatpak/exports/bin"));
        }
        return dirs;
    }

    /**
     * 当前平台缺少 aria2c 时的安装提示；Windows 内置二进制无需安装，返回 null。
     * 若配置里手动指定了路径但无效，则提示检查该路径。
     */
    public static String installHint() {
        String cfg = ModConfig.getInstance().getAria2BinPath();
        if (cfg != null && !cfg.isBlank()) {
            return "配置的 aria2c 路径无效: " + cfg + "，请检查后重试（当前回退单线程）";
        }
        if (ExtPaths.isWindows()) return null;
        if (ExtPaths.isMac()) {
            return "未检测到 aria2c，可执行 `brew install aria2` 启用多线程下载（当前回退单线程）";
        }
        if (ExtPaths.isLinux()) {
            return "未检测到 aria2c，可执行 `" + linuxInstallCommand()
                    + "` 启用多线程下载（当前回退单线程）";
        }
        return "未检测到 aria2c，多线程下载不可用（当前回退单线程）";
    }

    /** 按发行版探测包管理器，给出对应的 aria2 安装命令。 */
    private static String linuxInstallCommand() {
        if (existsInPath("apt-get")) return "sudo apt-get install aria2";
        if (existsInPath("dnf")) return "sudo dnf install aria2";
        if (existsInPath("yum")) return "sudo yum install aria2";
        if (existsInPath("pacman")) return "sudo pacman -S aria2";
        if (existsInPath("zypper")) return "sudo zypper install aria2";
        if (existsInPath("apk")) return "sudo apk add aria2";
        return "使用发行版包管理器安装 aria2";
    }

    private static boolean existsInPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (dir == null || dir.isBlank()) continue;
            if (new File(dir, exe).isFile()) return true;
        }
        return false;
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
