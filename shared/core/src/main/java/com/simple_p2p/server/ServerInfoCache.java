package com.simple_p2p.server;

import com.simple_p2p.util.SimpleJson;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端信息本地缓存（按房间号一份）。
 * <p>客户端连过一次房间后，把服务端自定义图标与 MOTD 标语写入
 * {@code config/simplep2p/serverinfo/<房间号>.json}；之后每次成功连接都会用最新观测值覆盖，
 * 服务端改了图标/标语就会自动更新，不需要玩家手动维护。
 */
public final class ServerInfoCache {

    private static final String DIR = "config" + File.separator + "simplep2p" + File.separator + "serverinfo";

    /** 缓存条目。 */
    public static final class Info {
        public String motd = "";
        public byte[] icon;
        public String versionName = "";
        public int onlinePlayers = -1;
        public int maxPlayers = -1;
        public long updatedAt;
    }

    private ServerInfoCache() {}

    /** 读取指定房间号的服务端信息；无缓存返回 null。 */
    public static Info load(String roomCode) {
        File f = fileFor(roomCode);
        if (f == null || !f.isFile()) return null;
        try {
            String json = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            Map<String, Object> m = SimpleJson.parseObject(json);
            if (m.isEmpty()) return null;

            Info info = new Info();
            info.motd = notNull(SimpleJson.getStr(m, "motd"));
            info.versionName = notNull(SimpleJson.getStr(m, "versionName"));
            info.onlinePlayers = SimpleJson.getInt(m, "onlinePlayers");
            info.maxPlayers = SimpleJson.getInt(m, "maxPlayers");
            info.updatedAt = SimpleJson.getLong(m, "updatedAt");

            String iconB64 = SimpleJson.getStr(m, "icon");
            if (iconB64 != null && !iconB64.isEmpty()) {
                try {
                    byte[] png = Base64.getDecoder().decode(iconB64);
                    if (png.length > 0) info.icon = png;
                } catch (Exception ignored) {
                }
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 用最新的状态查询结果覆盖本地缓存（信息变化自动更新）。
     *
     * @return true 表示图标/标语等展示信息相较旧缓存确实发生了变化
     */
    public static boolean store(String roomCode, McStatusPing.Result r) {
        File f = fileFor(roomCode);
        if (f == null || r == null) return false;

        Info old = load(roomCode);
        boolean changed = old == null
                || !notNull(old.motd).equals(notNull(r.motd))
                || !notNull(old.versionName).equals(notNull(r.versionName))
                || old.onlinePlayers != r.onlinePlayers
                || old.maxPlayers != r.maxPlayers
                || iconChanged(old.icon, r.icon);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomCode", roomCode);
        m.put("motd", r.motd);
        m.put("icon", r.icon != null ? Base64.getEncoder().encodeToString(r.icon) : "");
        m.put("versionName", r.versionName);
        m.put("onlinePlayers", r.onlinePlayers);
        m.put("maxPlayers", r.maxPlayers);
        m.put("updatedAt", System.currentTimeMillis());
        try {
            File parent = f.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            Files.writeString(f.toPath(), SimpleJson.toJsonString(m), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return false;
        }
        return changed;
    }

    private static boolean iconChanged(byte[] a, byte[] b) {
        if (a == null && b == null) return false;
        if (a == null || b == null) return true;
        if (a.length != b.length) return true;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return true;
        }
        return false;
    }

    private static File fileFor(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) return null;
        String safe = roomCode.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) return null;
        return new File(DIR, safe + ".json");
    }

    private static String notNull(String s) {
        return s == null ? "" : s;
    }
}
