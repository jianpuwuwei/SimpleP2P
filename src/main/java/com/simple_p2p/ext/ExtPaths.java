package com.simple_p2p.ext;

import com.simple_p2p.config.ModConfig;

import java.io.File;

/**
 * 外部官方客户端二进制的路径与平台判定。
 *
 * <p>安装根目录优先级：{@code -Dsimplep2p.binDir} > jar 所在目录的父目录(mods/) > 当前工作目录下 mods/。
 * 开发环境(ForgeGradle run)下类不在 jar 里，自动回退到 {@code cwd/mods/simplep2p}（即 run/mods/simplep2p），
 * 便于本地无额外配置直接测试。
 */
public final class ExtPaths {

    private ExtPaths() {}

    /** 支持的官方工具。 */
    public enum ExtTool {
        EASYTIER, OPENP2P
    }

    public static boolean isWindows() {
        return os().toLowerCase().contains("win");
    }

    public static boolean isMac() {
        return os().toLowerCase().contains("mac");
    }

    public static boolean isLinux() {
        return os().toLowerCase().contains("linux");
    }

    private static String os() {
        String o = System.getProperty("os.name", "");
        return o == null ? "" : o;
    }

    /** 平台标签，用于拼接下载 URL，如 windows-x86_64 / linux-x86_64 / macos-x86_64。 */
    public static String osTag() {
        if (isWindows()) return "windows-x86_64";
        if (isMac()) return "macos-x86_64";
        return "linux-x86_64";
    }

    /** 安装根目录：<mods>/simplep2p。 */
    public static File installRoot() {
        // 1) 系统属性显式指定（dev 调试 / 手动指定）
        String sys = System.getProperty("simplep2p.binDir");
        if (sys != null && !sys.isBlank()) {
            return new File(sys);
        }
        // 2) 配置指定绝对路径
        String cfg = ModConfig.getInstance().getBinaryInstallDir();
        if (cfg != null && !cfg.isBlank()) {
            File f = new File(cfg);
            if (f.isAbsolute()) return f;
        }
        // 3) 自动定位：优先 jar 所在目录的父目录(mods/)
        File fromJar = modsDirFromJar();
        if (fromJar != null) {
            return new File(fromJar, "simplep2p");
        }
        // 4) 回退 cwd/mods
        return new File(new File(System.getProperty("user.dir", "."), "mods"), "simplep2p");
    }

    private static File modsDirFromJar() {
        try {
            File loc = new File(ExtPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (loc.isFile() && loc.getName().toLowerCase().endsWith(".jar")) {
                File parent = loc.getParentFile();
                if (parent != null) return parent; // 即 mods/
            }
            // dev 环境：类是 .class 目录，回退 cwd/mods
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 某工具的工具目录，如 <root>/easytier。 */
    public static File toolDir(ExtTool t) {
        return new File(installRoot(), t == ExtTool.EASYTIER ? "easytier" : "openp2p");
    }

    /** 某工具的可执行文件名（含平台后缀）。 */
    public static String toolBinaryName(ExtTool t) {
        String name = t == ExtTool.EASYTIER ? "easytier-core" : "openp2p";
        if (isWindows()) name += ".exe";
        return name;
    }

    /** 某工具的标准可执行文件路径（<toolDir>/<bin>）。 */
    public static File toolBin(ExtTool t) {
        return new File(toolDir(t), toolBinaryName(t));
    }

    /**
     * 查找实际可用的可执行文件。
     * <p>优先标准位置 {@code <root>/<tool>/<bin>}；找不到则在安装根目录下递归（深度≤3）查找同名文件。
     * 这样用户把解压出的文件（或整个压缩包解压后的文件夹）手动放到 {@code mods/simplep2p/} 下任意位置都能被识别。
     *
     * @return 找到的文件；未找到返回 null
     */
    public static File resolveBinary(ExtTool t) {
        File std = toolBin(t);
        if (std.isFile()) return std;
        return findRecursively(installRoot(), toolBinaryName(t), 0, 3);
    }

    /**
     * easytier-cli 可执行文件（与 easytier-core 同目录）。
     * <p>用于查询节点信息与建立本地端口转发（对齐 MinecraftConnectTool 的 ET 模式做法）。
     *
     * @return 找到的 cli 文件；未找到返回 null
     */
    public static File cliBin() {
        File core = resolveBinary(ExtTool.EASYTIER);
        if (core == null) return null;
        String name = isWindows() ? "easytier-cli.exe" : "easytier-cli";
        File cli = new File(core.getParentFile(), name);
        return cli.isFile() ? cli : null;
    }

    private static File findRecursively(File dir, String name, int depth, int maxDepth) {
        if (dir == null || !dir.isDirectory() || depth > maxDepth) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File r = findRecursively(f, name, depth + 1, maxDepth);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 版本标记文件，用于判断某版本是否已安装。 */
    public static File versionMarker(ExtTool t) {
        String ver = t == ExtTool.EASYTIER
                ? ModConfig.getInstance().getEasyTierVersion()
                : ModConfig.getInstance().getOpenP2PVersion();
        return new File(toolDir(t), ".installed-" + ver);
    }
}
