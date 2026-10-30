package com.simple_p2p.server;

import com.simple_p2p.util.SimpleJson;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 通过本地转发的地址查询 Minecraft 服务端状态（服务器列表 ping 协议）。
 * <p>用于在客户端连过一次房间后，把服务端的自定义图标（favicon）和 MOTD 标语缓存到本地，
 * 之后服务器列表里的房间码条目就能直接显示服务端真实的图标和标语。
 */
public final class McStatusPing {

    /** 状态查询结果。 */
    public static final class Result {
        /** MOTD 标语（已转换成原版 § 格式文本）。 */
        public final String motd;
        /** 服务端图标 PNG 字节（无图标时为 null）。 */
        public final byte[] icon;
        public final String versionName;
        public final int onlinePlayers;
        public final int maxPlayers;

        public Result(String motd, byte[] icon, String versionName, int onlinePlayers, int maxPlayers) {
            this.motd = motd == null ? "" : motd;
            this.icon = icon;
            this.versionName = versionName == null ? "" : versionName;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
        }
    }

    private McStatusPing() {}

    /**
     * 查询服务端状态。
     *
     * @param host            本地转发地址（通常 127.0.0.1）
     * @param port            本地转发端口（对应服务端 MC 端口）
     * @param protocolVersion 当前游戏协议号（只用于握手，服务端会返回自己的版本信息）
     * @param timeoutMs       连接与读取超时
     * @return 查询成功返回结果；服务端不可达/未开启状态查询返回 null
     */
    public static Result query(String host, int port, int protocolVersion, int timeoutMs) {
        if (host == null || host.isBlank() || port <= 0 || port > 65535) return null;
        int timeout = timeoutMs > 0 ? timeoutMs : 3000;
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 握手包：id=0, 协议号, 主机名, 端口, 下一状态=1(状态查询)
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0x00);
            writeVarInt(hs, protocolVersion);
            writeString(hs, "localhost");
            writeUnsignedShort(hs, port);
            writeVarInt(hs, 1);
            sendPacket(out, hs.toByteArray());
            // 状态请求：空包体，id=0
            sendPacket(out, new byte[]{0x00});

            byte[] resp = readPacket(in);
            if (resp == null || resp.length < 2 || resp[0] != 0x00) return null;
            String json = readString(resp, 1);
            if (json == null || json.isBlank()) return null;

            Map<String, Object> root = SimpleJson.parseObject(json);

            String motd = "";
            Object desc = root.get("description");
            if (desc != null) {
                StringBuilder sb = new StringBuilder();
                flatten(desc, sb);
                motd = sb.toString();
            }

            byte[] icon = null;
            String favicon = SimpleJson.getStr(root, "favicon");
            if (favicon != null) {
                int comma = favicon.indexOf(',');
                if (favicon.startsWith("data:image/") && comma > 0) {
                    try {
                        byte[] png = Base64.getDecoder().decode(favicon.substring(comma + 1).trim());
                        if (png.length > 0) icon = png;
                    } catch (Exception ignored) {
                    }
                }
            }

            String versionName = "";
            Map<String, Object> ver = SimpleJson.getObj(root, "version");
            if (ver != null) {
                String n = SimpleJson.getStr(ver, "name");
                if (n != null) versionName = n;
            }

            int online = -1;
            int max = -1;
            Map<String, Object> players = SimpleJson.getObj(root, "players");
            if (players != null) {
                online = SimpleJson.getInt(players, "online");
                max = SimpleJson.getInt(players, "max");
            }

            return new Result(motd, icon, versionName, online, max);
        } catch (Throwable t) {
            return null;
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== 报文读写 ====================

    private static void sendPacket(OutputStream out, byte[] payload) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, payload.length);
        buf.write(payload);
        out.write(buf.toByteArray());
        out.flush();
    }

    private static byte[] readPacket(InputStream in) {
        try {
            int length = readVarInt(in);
            if (length <= 0 || length > 4 * 1024 * 1024) return null;
            byte[] data = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(data, read, length - read);
                if (n < 0) return null;
                read += n;
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            int b = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    private static int readVarInt(InputStream in) throws Exception {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new Exception("eof");
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 35) throw new Exception("varint too long");
        }
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeUnsignedShort(ByteArrayOutputStream out, int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static String readString(byte[] data, int offset) {
        try {
            int pos = offset;
            int length = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) return null;
                int b = data[pos++] & 0xFF;
                length |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 35) return null;
            }
            if (length < 0 || pos + length > data.length) return null;
            return new String(data, pos, length, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 文本组件 → § 格式文本 ====================

    @SuppressWarnings("unchecked")
    private static void flatten(Object el, StringBuilder sb) {
        if (el == null) return;
        if (el instanceof String) {
            sb.append((String) el);
            return;
        }
        if (el instanceof List) {
            for (Object o : (List<Object>) el) flatten(o, sb);
            return;
        }
        if (el instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) el;
            appendStyle(m, sb);
            Object text = m.get("text");
            if (text != null) {
                sb.append(text);
            } else {
                Object translate = m.get("translate");
                if (translate != null) sb.append(translate);
            }
            flatten(m.get("extra"), sb);
            // 1.20 之前组件的 with 参数是子组件；1.20+ 变成普通参数，这里兼容处理
            flatten(m.get("with"), sb);
        }
    }

    private static void appendStyle(Map<String, Object> m, StringBuilder sb) {
        String color = SimpleJson.getStr(m, "color");
        if (color != null) {
            if (color.startsWith("#") && color.length() == 7) {
                sb.append("\u00a7x");
                for (int i = 1; i < 7; i++) sb.append('\u00a7').append(Character.toUpperCase(color.charAt(i)));
            } else {
                char code = colorCode(color);
                if (code != 0) sb.append('\u00a7').append(code);
            }
        }
        if (SimpleJson.getBool(m, "bold")) sb.append("\u00a7l");
        if (SimpleJson.getBool(m, "italic")) sb.append("\u00a7o");
        if (SimpleJson.getBool(m, "underlined")) sb.append("\u00a7n");
        if (SimpleJson.getBool(m, "strikethrough")) sb.append("\u00a7m");
        if (SimpleJson.getBool(m, "obfuscated")) sb.append("\u00a7k");
    }

    private static char colorCode(String color) {
        switch (color) {
            case "black": return '0';
            case "dark_blue": return '1';
            case "dark_green": return '2';
            case "dark_aqua": return '3';
            case "dark_red": return '4';
            case "dark_purple": return '5';
            case "gold": return '6';
            case "gray": return '7';
            case "dark_gray": return '8';
            case "blue": return '9';
            case "green": return 'a';
            case "aqua": return 'b';
            case "red": return 'c';
            case "light_purple": return 'd';
            case "yellow": return 'e';
            case "white": return 'f';
            case "reset": return 'r';
            default: return 0;
        }
    }
}
