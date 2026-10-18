package com.simple_p2p.ext;

import com.simple_p2p.config.ModConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 自动下载并安装 EasyTier / OpenP2P 官方客户端到 {@code mods/simplep2p/<tool>/}。
 *
 * <p>官方直链 + 可配置加速镜像（前缀 + 官方 URL）顺序回退；支持 zip 与 tar.gz 解压（无第三方依赖）。
 */
public final class BinaryInstaller {

    private BinaryInstaller() {}

    /** 面向玩家的安装失败异常。 */
    public static final class ExtException extends Exception {
        public ExtException(String message) { super(message); }
        public ExtException(String message, Throwable cause) { super(message, cause); }
    }

    public interface ProgressListener {
        void onProgress(String stage, long bytes, long total);
    }

    private static final ProgressListener NOOP = (s, b, t) -> {};

    /**
     * 官方下载直链：优先查询 GitHub 最新 release 并从中识别当前平台的资产文件地址；
     * 查不到再退回按发布命名规则拼接。返回的始终是压缩包文件地址，供加速镜像拼接。
     */
    static String officialUrl(ExtPaths.ExtTool t) {
        String version = versionOf(t);
        if (autoLatest(t)) {
            ReleaseInfo info = fetchLatestRelease(t);
            if (info != null) {
                setVersion(t, info.version);
                version = info.version;
                if (info.downloadUrl != null) return info.downloadUrl;
            }
        }
        return templateUrl(t, version);
    }

    /** 按发布命名规则拼接的官方直链（兜底）。 */
    private static String templateUrl(ExtPaths.ExtTool t, String version) {
        if (t == ExtPaths.ExtTool.EASYTIER) {
            String tag = "v" + version;
            return "https://github.com/EasyTier/EasyTier/releases/download/"
                    + tag + "/easytier-" + ExtPaths.osTag() + "-" + tag + ".zip";
        }
        return "https://github.com/openp2p-cn/openp2p/releases/download/"
                + "v" + version + "/openp2p-" + version + "." + platformTag(t) + archiveExt(t);
    }

    /** 归档扩展名（Windows 为 zip，其它平台 tar.gz）。 */
    private static String archiveExt(ExtPaths.ExtTool t) {
        if (t == ExtPaths.ExtTool.EASYTIER) return ".zip";
        return ExtPaths.isWindows() ? ".zip" : ".tar.gz";
    }

    /** 当前平台在发布产物名里的标识。 */
    private static String platformTag(ExtPaths.ExtTool t) {
        if (t == ExtPaths.ExtTool.EASYTIER) {
            return ExtPaths.osTag(); // windows-x86_64 / macos-x86_64 / linux-x86_64
        }
        if (ExtPaths.isWindows()) return "windows-amd64";
        if (ExtPaths.isMac()) return "darwin-amd64";
        return "linux-amd64";
    }

    /** 工具对应的 GitHub 仓库。 */
    private static String repo(ExtPaths.ExtTool t) {
        return t == ExtPaths.ExtTool.EASYTIER ? "EasyTier/EasyTier" : "openp2p-cn/openp2p";
    }

    /** 是否自动使用 GitHub 最新版本。 */
    private static boolean autoLatest(ExtPaths.ExtTool t) {
        ModConfig c = ModConfig.getInstance();
        return t == ExtPaths.ExtTool.EASYTIER ? c.isEasyTierAutoLatest() : c.isOpenP2PAutoLatest();
    }

    private static String versionOf(ExtPaths.ExtTool t) {
        ModConfig c = ModConfig.getInstance();
        return t == ExtPaths.ExtTool.EASYTIER ? c.getEasyTierVersion() : c.getOpenP2PVersion();
    }

    /** 把识别到的版本号写回配置。 */
    private static void setVersion(ExtPaths.ExtTool t, String version) {
        if (version == null || version.isEmpty() || version.equals(versionOf(t))) return;
        if (t == ExtPaths.ExtTool.EASYTIER) {
            ModConfig.getInstance().setEasyTierVersion(version);
        } else {
            ModConfig.getInstance().setOpenP2PVersion(version);
        }
        ModConfig.getInstance().save();
    }

    /** release 解析结果：版本号 + 当前平台的资产直链（可能为空）。 */
    private static final class ReleaseInfo {
        final String version;
        final String downloadUrl;
        ReleaseInfo(String version, String downloadUrl) {
            this.version = version;
            this.downloadUrl = downloadUrl;
        }
    }

    /**
     * 查询最新 release 并从资产列表里挑出当前平台的包。
     * 资产名变化导致匹配不到时仍返回版本号（上层按命名规则拼接）；
     * GitHub API 不可用时退回读 releases/latest 的 302，只取版本号。
     */
    private static ReleaseInfo fetchLatestRelease(ExtPaths.ExtTool t) {
        String body = httpGet("https://api.github.com/repos/" + repo(t) + "/releases/latest", 8000);
        if (body != null) {
            try {
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
                com.google.gson.JsonElement tagEl = o.get("tag_name");
                if (tagEl != null && !tagEl.isJsonNull()) {
                    String version = stripV(tagEl.getAsString());
                    com.google.gson.JsonArray assets = o.getAsJsonArray("assets");
                    if (assets != null) {
                        for (com.google.gson.JsonElement el : assets) {
                            com.google.gson.JsonObject a = el.getAsJsonObject();
                            com.google.gson.JsonElement nameEl = a.get("name");
                            com.google.gson.JsonElement urlEl = a.get("browser_download_url");
                            if (nameEl == null || urlEl == null) continue;
                            if (assetMatches(t, nameEl.getAsString())) {
                                return new ReleaseInfo(version, urlEl.getAsString());
                            }
                        }
                    }
                    return new ReleaseInfo(version, null);
                }
            } catch (Exception ignored) {
            }
        }
        String v = tagFromLocation(
                httpLocation("https://github.com/" + repo(t) + "/releases/latest", 6000));
        return v == null ? null : new ReleaseInfo(v, null);
    }

    /** 判断某个 release 资产是否是当前平台可用的压缩包。 */
    private static boolean assetMatches(ExtPaths.ExtTool t, String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        String prefix = t == ExtPaths.ExtTool.EASYTIER ? "easytier-" : "openp2p-";
        if (!n.startsWith(prefix)) return false;
        if (!n.contains(platformTag(t).toLowerCase(java.util.Locale.ROOT))) return false;
        return n.endsWith(archiveExt(t));
    }

    private static String stripV(String tag) {
        String t = tag == null ? "" : tag.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    private static String tagFromLocation(String location) {
        if (location == null) return null;
        int i = location.lastIndexOf("/tag/");
        if (i < 0) return null;
        String v = stripV(location.substring(i + 5));
        return v.matches("[0-9][0-9.]*") ? v : null;
    }

    /** GET 返回响应体；失败返回 null。 */
    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "SimpleP2P-Mod");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 不跟随重定向，返回 Location 头；失败返回 null。 */
    private static String httpLocation(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "SimpleP2P-Mod");
            conn.getResponseCode();
            return conn.getHeaderField("Location");
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 是否已有可用的可执行文件（用户手动放置的也算，不强制版本标记文件）。 */
    public static boolean isInstalled(ExtPaths.ExtTool t) {
        return ExtPaths.resolveBinary(t) != null;
    }

    /** 确保工具已安装；已装则直接返回。 */
    public static void ensureInstalled(ExtPaths.ExtTool t) throws ExtException {
        ensureInstalled(t, NOOP);
    }

    public static void ensureInstalled(ExtPaths.ExtTool t, ProgressListener l) throws ExtException {
        if (!ModConfig.getInstance().isAutoDownloadBinaries()) {
            if (isInstalled(t)) return;
            throw new ExtException("自动下载已关闭，请手动下载官方客户端放入: "
                    + ExtPaths.toolDir(t).getAbsolutePath());
        }
        if (isInstalled(t)) return;
        if (l == null) l = NOOP;

        // 用户手动放置了官方压缩包（如 easytier-windows-x86_64-v2.6.4.zip）？直接解压安装
        File localArchive = findLocalArchive(t);
        if (localArchive != null) {
            l.onProgress("发现本地压缩包，解压安装: " + localArchive.getName(), 0, 0);
            File dest = extractAndInstall(t, localArchive);
            writeMarker(t);
            l.onProgress("安装完成: " + dest.getAbsolutePath(), 0, 0);
            return;
        }

        File toolDir = ExtPaths.toolDir(t);
        if (!toolDir.isDirectory() && !toolDir.mkdirs()) {
            throw new ExtException("无法创建工具目录: " + toolDir.getAbsolutePath());
        }

        l.onProgress("准备下载 " + (t == ExtPaths.ExtTool.EASYTIER ? "EasyTier" : "OpenP2P") + " ...", 0, 0);
        File archive = downloadToCache(t, l);
        l.onProgress("解压安装 ...", 0, 0);
        File dest = extractAndInstall(t, archive);
        writeMarker(t);
        deleteRecursive(archive);
        l.onProgress("安装完成: " + dest.getAbsolutePath(), 0, 0);
    }

    private static void writeMarker(ExtPaths.ExtTool t) throws ExtException {
        try {
            Files.writeString(ExtPaths.versionMarker(t).toPath(), "installed");
        } catch (IOException e) {
            throw new ExtException("写版本标记失败: " + e.getMessage(), e);
        }
    }

    /** 在安装根目录下查找用户手动放置的官方归档包（easytier*.zip / openp2p*.zip|*.tar.gz）。 */
    private static File findLocalArchive(ExtPaths.ExtTool t) {
        String prefix = t == ExtPaths.ExtTool.EASYTIER ? "easytier" : "openp2p";
        File zip = findArchiveRecursively(ExtPaths.installRoot(), prefix, ".zip", 0, 3);
        if (zip != null) return zip;
        return findArchiveRecursively(ExtPaths.installRoot(), prefix, ".tar.gz", 0, 3);
    }

    private static File findArchiveRecursively(File dir, String prefix, String suffix, int depth, int maxDepth) {
        if (dir == null || !dir.isDirectory() || depth > maxDepth) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile()) {
                String n = f.getName().toLowerCase();
                if (n.contains(prefix) && n.endsWith(suffix) && !n.endsWith(".part")) return f;
            }
        }
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith("tmp-")) {
                File r = findArchiveRecursively(f, prefix, suffix, depth + 1, maxDepth);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ================== 下载 ==================

    private static File downloadToCache(ExtPaths.ExtTool t, ProgressListener l) throws ExtException {
        String official = officialUrl(t);
        List<String> candidates = new ArrayList<>();
        // 镜像代理优先（chenc.dev 置首），官方直链兜底
        for (String mirror : ModConfig.getInstance().getDownloadMirrors()) {
            candidates.add(mirror + official);
        }
        candidates.add(official);
        File cacheDir = new File(ExtPaths.installRoot(), "download");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new ExtException("无法创建下载目录: " + cacheDir.getAbsolutePath());
        }
        int slash = official.lastIndexOf('/');
        String fileName = slash >= 0 ? official.substring(slash + 1) : (t == ExtPaths.ExtTool.EASYTIER ? "easytier" : "openp2p") + archiveExt(t);
        File target = new File(cacheDir, fileName);

        // 先并行测试所有候选地址，按实测延迟排序后再下载，避免逐个等死镜像的连接超时
        l.onProgress("测试下载地址 ...", 0, 0);
        List<String> ordered = rankCandidates(candidates);

        Exception lastError = null;
        for (String url : ordered) {
            try {
                l.onProgress("下载 " + describe(t) + " ...", 0, 0);
                downloadFile(url, target, l);
                return target;
            } catch (Exception e) {
                lastError = e;
                deleteQuietly(target);
            }
        }
        // SSL 证书校验失败时可让用户选择跳过校验后重试
        String hint = isSslError(lastError) && !ModConfig.getInstance().isIgnoreSslVerify()
                ? "。疑似 SSL 证书校验失败，可用 /p2p sslignore on 后重试"
                : "";
        throw new ExtException("下载失败" + hint + "。可手动下载官方客户端压缩包放入 "
                + ExtPaths.toolDir(t).getAbsolutePath() + "，重启游戏后自动识别");
    }

    /**
     * 并行探测所有候选下载地址（Range 取首字节），可用的按延迟升序排在前面；
     * 未测通/超时的保持原顺序排在后面兜底——探测失败不代表真的下载不了。
     */
    private static List<String> rankCandidates(List<String> urls) {
        List<String> ordered = new ArrayList<>();
        if (urls.size() <= 1) {
            ordered.addAll(urls);
            return ordered;
        }
        ExecutorService pool = Executors.newFixedThreadPool(urls.size(), r -> {
            Thread th = new Thread(r, "SimpleP2P-DlProbe");
            th.setDaemon(true);
            return th;
        });
        List<Probe> done = new ArrayList<>();
        try {
            ExecutorCompletionService<Probe> ecs = new ExecutorCompletionService<>(pool);
            for (String url : urls) {
                Probe p = new Probe(url);
                ecs.submit(() -> {
                    probe(url, p);
                    return p;
                });
            }
            long hardDeadline = System.currentTimeMillis() + 12000;
            // 拿到第一个可用结果后再多等一会儿，收集可能更快的镜像
            long gatherUntil = -1;
            for (int i = 0; i < urls.size(); i++) {
                long now = System.currentTimeMillis();
                long wait = hardDeadline - now;
                if (gatherUntil > 0) {
                    if (now >= gatherUntil) break;
                    wait = Math.min(wait, gatherUntil - now);
                }
                if (wait <= 0) break;
                Future<Probe> f;
                try {
                    f = ecs.poll(wait, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (f == null) break;
                try {
                    done.add(f.get());
                } catch (Exception ignored) {
                }
                if (gatherUntil < 0 && done.stream().anyMatch(p -> p.ok)) {
                    gatherUntil = System.currentTimeMillis() + 1000;
                }
            }
            done.stream().filter(p -> p.ok)
                    .sorted(Comparator.comparingLong(p -> p.latencyMs))
                    .forEach(p -> ordered.add(p.url));
        } finally {
            pool.shutdownNow();
        }
        for (String url : urls) {
            if (!ordered.contains(url)) ordered.add(url);
        }
        return ordered;
    }

    /** 单个候选地址的探测结果。 */
    private static final class Probe {
        final String url;
        volatile boolean ok;
        volatile long latencyMs = Long.MAX_VALUE;
        Probe(String url) {
            this.url = url;
        }
    }

    /** 探测下载地址：Range 取首字节，能读到数据即视为可用，延迟取首字节耗时。 */
    private static void probe(String url, Probe p) {
        HttpURLConnection conn = null;
        long t0 = System.currentTimeMillis();
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 SimpleP2P-Mod");
            conn.setRequestProperty("Range", "bytes=0-1023");
            if (ModConfig.getInstance().isIgnoreSslVerify() && conn instanceof HttpsURLConnection) {
                applyInsecureSsl((HttpsURLConnection) conn);
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) return;
            try (InputStream in = conn.getInputStream()) {
                if (in.read() < 0) return;
            }
            p.latencyMs = System.currentTimeMillis() - t0;
            p.ok = true;
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String describe(ExtPaths.ExtTool t) {
        return t == ExtPaths.ExtTool.EASYTIER ? "EasyTier" : "OpenP2P";
    }

    /** 忽略 SSL 证书与主机名校验（仅当用户显式开启 ignoreSslVerify 时调用）。 */
    private static void applyInsecureSsl(HttpsURLConnection conn) {
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            conn.setSSLSocketFactory(ctx.getSocketFactory());
            conn.setHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            System.err.println("[SimpleP2P] 应用忽略 SSL 校验失败: " + e.getMessage());
        }
    }

    /** 判断异常链中是否包含 SSL 证书校验类错误。 */
    private static boolean isSslError(Throwable t) {
        while (t != null) {
            if (t instanceof javax.net.ssl.SSLException) return true;
            String msg = t.getMessage();
            if (msg != null && (msg.contains("PKIX") || msg.contains("certification path")
                    || msg.contains("SSL") || msg.contains("certificate"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static void downloadFile(String url, File target, ProgressListener l) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 SimpleP2P-Mod");
        // 用户选择忽略 SSL 校验时（应对部分网络下 GitHub 证书链校验失败），跳过证书与主机名校验
        if (ModConfig.getInstance().isIgnoreSslVerify() && conn instanceof HttpsURLConnection) {
            applyInsecureSsl((HttpsURLConnection) conn);
        }
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + code + " @ " + url);
        }
        long total = conn.getContentLengthLong();
        File part = new File(target.getAbsolutePath() + ".part");
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] buf = new byte[16384];
            long done = 0;
            int n;
            int lastPct = -1;
            long lastBytes = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                // 进度节流：按 10% 步进上报，避免每 16KB 回调一次导致聊天刷屏
                if (total > 0) {
                    int pct = (int) Math.min(100, done * 100 / total);
                    if (pct >= lastPct + 10) {
                        lastPct = pct;
                        l.onProgress("下载中 " + pct + "%", done, total);
                    }
                } else if (done - lastBytes >= 2L * 1024 * 1024) {
                    lastBytes = done;
                    l.onProgress("下载中 " + (done / 1024 / 1024) + "MB", done, total);
                }
            }
        } finally {
            conn.disconnect();
        }
        // 改名完成
        if (target.exists() && !target.delete()) {
            // ignore
        }
        if (!part.renameTo(target)) {
            try {
                copyFile(part, target);
            } catch (ExtException e) {
                throw new IOException("复制下载文件失败: " + e.getMessage(), e);
            }
            deleteQuietly(part);
        }
    }

    // ================== 解压与定位 ==================

    /**
     * 解压归档并把目标可执行文件安装到标准位置 {@code <toolDir>/<bin>}。
     * <p>返回的是稳定位置的文件（临时解压目录会在结束时清理），可直接用于启动。
     */
    private static File extractAndInstall(ExtPaths.ExtTool t, File archive) throws ExtException {
        File temp = new File(ExtPaths.installRoot(), "tmp-" + System.currentTimeMillis());
        if (!temp.mkdirs()) throw new ExtException("无法创建临时解压目录");
        try {
            if (archive.getName().toLowerCase().endsWith(".zip")) {
                extractZip(archive, temp);
            } else {
                extractTarGz(archive, temp);
            }
            String want = ExtPaths.toolBinaryName(t);
            File found = findBinaryRecursively(temp, want, 0);
            if (found == null) {
                throw new ExtException("解压后未找到可执行文件 " + want + "（压缩包可能损坏或布局变化）");
            }
            File dest = ExtPaths.toolBin(t);
            File toolDir = ExtPaths.toolDir(t);
            // 把可执行文件所在目录的“全部内容”复制到工具目录：EasyTier 依赖同目录的 wintun.dll 等，
            // 只复制单个 exe 会导致无法创建 TUN 虚拟网卡。
            copyDirContents(found.getParentFile(), toolDir);
            if (dest.isFile()) {
                if (!ExtPaths.isWindows()) dest.setExecutable(true, false);
                return dest;
            }
            throw new ExtException("可执行文件复制后未找到: " + dest.getAbsolutePath());
        } finally {
            deleteRecursive(temp);
        }
    }

    /** 递归复制目录内容到目标目录。 */
    private static void copyDirContents(File srcDir, File dstDir) throws ExtException {
        if (srcDir == null) return;
        if (!dstDir.isDirectory() && !dstDir.mkdirs()) {
            throw new ExtException("无法创建目录: " + dstDir.getAbsolutePath());
        }
        File[] files = srcDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            File to = new File(dstDir, f.getName());
            if (f.isDirectory()) {
                copyDirContents(f, to);
            } else {
                try {
                    Files.copy(f.toPath(), to.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (!ExtPaths.isWindows()) to.setExecutable(true, false);
                } catch (IOException e) {
                    throw new ExtException("复制 " + f.getName() + " 失败: " + e.getMessage(), e);
                }
            }
        }
    }

    private static File findBinaryRecursively(File dir, String name, int depth) {
        if (depth > 2 || dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File r = findBinaryRecursively(f, name, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ================== zip 解压 ==================

    private static void extractZip(File zip, File dest) throws ExtException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                File out = safeResolve(dest, e.getName());
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new ExtException("解压创建目录失败: " + parent.getAbsolutePath());
                }
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) != -1) os.write(buf, 0, n);
                }
            }
        } catch (IOException e) {
            throw new ExtException("解压 zip 失败: " + e.getMessage(), e);
        }
    }

    // ================== tar.gz 解压（最小 ustar 解析） ==================

    private static void extractTarGz(File tgz, File dest) throws ExtException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tgz)))) {
            extractTar(in, dest);
        } catch (IOException e) {
            throw new ExtException("解压 tar.gz 失败: " + e.getMessage(), e);
        }
    }

    /** 手写最小 ustar tar 解析：512 字节头，仅处理普通文件（'0' 或 '\0'），拒绝 ".." 路径。 */
    private static void extractTar(InputStream in, File dest) throws IOException {
        byte[] header = new byte[512];
        while (true) {
            int read = readFully(in, header);
            if (read == 0) break; // 两个 512 空块结束
            if (read < 512) break;
            // 校验 ustar 魔数
            boolean ustar = header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a' && header[261] == 'r';
            String typeflag = new String(header, 156, 1);
            // 大小：八进制，偏移 124，12 字节
            long size = parseOctal(header, 124, 12);
            String name = new String(header, 0, 100, java.nio.charset.StandardCharsets.UTF_8).trim();
            String prefix = ustar ? new String(header, 345, 155, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            String full = (prefix.isEmpty() ? "" : prefix + "/") + name;
            // 仅处理普通文件
            if (!typeflag.equals("0") && !typeflag.equals("\0")) {
                skipFully(in, size);
                skipPadding(in, size);
                continue;
            }
            if (full.isEmpty() || full.contains("..")) {
                skipFully(in, size);
                skipPadding(in, size);
                continue;
            }
            File out = safeResolve(dest, full);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("tar 解压创建目录失败: " + parent.getAbsolutePath());
            }
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                copyN(in, os, size);
            }
            skipPadding(in, size);
        }
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int r = in.read(buf, 0, want);
            if (r < 0) break;
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) break;
            left -= r;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skipFully(in, pad);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == ' ' || c == 0) continue;
            if (c >= '0' && c <= '7') {
                v = (v << 3) | (c - '0');
            } else if (c == 0) {
                break;
            } else {
                break; // GNU 长文件名等，忽略该字段
            }
        }
        return v;
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    // ================== 工具方法 ==================

    private static File safeResolve(File dest, String rel) {
        // 已过滤 ".."，再兜底一次
        String norm = rel.replace('\\', '/');
        return new File(dest, norm);
    }

    private static void copyFile(File src, File dst) throws ExtException {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new ExtException("无法创建目录: " + parent.getAbsolutePath());
            }
            Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ExtException("复制文件失败: " + e.getMessage(), e);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        deleteQuietly(f);
    }

    private static void deleteQuietly(File f) {
        try { if (f != null) f.delete(); } catch (Exception ignored) {}
    }
}
