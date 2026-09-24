package com.simple_p2p.util;

import java.util.regex.Pattern;

/**
 * 服务器地址智能识别工具：合法 IP/域名形式视为普通 MC 服务器地址；
 * 非 IP/域名的纯字母数字串视为房间号，接管 P2P 流程。
 */
public class AddressRecognizer {

    // IPv4 正则: xxx.xxx.xxx.xxx[:port]
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)(:\\d{1,5})?$"
    );

    // IPv6 正则 (简化): [xxxx:xxxx:..][:port]
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^\\[[0-9a-fA-F:]+\\](:\\d{1,5})?$"
    );

    // 域名: 含至少一个点号, 最后段为字母, 可选端口
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(:\\d{1,5})?$"
    );

    // localhost:port
    private static final Pattern LOCALHOST_PATTERN = Pattern.compile(
            "^localhost(:\\d{1,5})?$", Pattern.CASE_INSENSITIVE
    );

    // 纯房间号: 只含字母+数字, 长度>=6
    private static final Pattern ROOM_CODE_PATTERN = Pattern.compile(
            "^[A-Za-z0-9]{6,}$"
    );

    public static class RecognizeResult {
        public final boolean isRoomCode;     // 是否为房间号
        public final boolean isNormalAddress;// 是否为普通MC服务器地址(IP/域名)
        public final String address;         // 原样输入
        public final String ipOrDomain;      // 如果是普通地址，这部分为IP或域名(不含端口)
        public final int port;               // 如果是普通地址，这里为端口(缺省25565)

        RecognizeResult(boolean isRoomCode, boolean isNormalAddress, String address, String ipOrDomain, int port) {
            this.isRoomCode = isRoomCode;
            this.isNormalAddress = isNormalAddress;
            this.address = address;
            this.ipOrDomain = ipOrDomain;
            this.port = port;
        }
    }

    public static RecognizeResult recognize(String input) {
        if (input == null) {
            return new RecognizeResult(false, false, null, null, 25565);
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return new RecognizeResult(false, false, trimmed, null, 25565);
        }

        // 1. 先尝试匹配 localhost
        if (LOCALHOST_PATTERN.matcher(trimmed).matches()) {
            String[] parts = splitHostPort(trimmed);
            return new RecognizeResult(false, true, trimmed, parts[0], Integer.parseInt(parts[1]));
        }

        // 2. 匹配 IPv4
        if (IPV4_PATTERN.matcher(trimmed).matches()) {
            String[] parts = splitHostPort(trimmed);
            return new RecognizeResult(false, true, trimmed, parts[0], Integer.parseInt(parts[1]));
        }

        // 3. 匹配 IPv6
        if (IPV6_PATTERN.matcher(trimmed).matches()) {
            String ip = trimmed;
            int port = 25565;
            int lastBracket = trimmed.lastIndexOf(']');
            if (lastBracket > 0 && lastBracket < trimmed.length() - 1 && trimmed.charAt(lastBracket + 1) == ':') {
                ip = trimmed.substring(0, lastBracket + 1);
                try {
                    port = Integer.parseInt(trimmed.substring(lastBracket + 2));
                } catch (NumberFormatException ignored) {
                }
            }
            return new RecognizeResult(false, true, trimmed, ip, port);
        }

        // 4. 匹配域名
        if (DOMAIN_PATTERN.matcher(trimmed).matches()) {
            String[] parts = splitHostPort(trimmed);
            return new RecognizeResult(false, true, trimmed, parts[0], Integer.parseInt(parts[1]));
        }

        // 5. 不是IP也不是域名，且只包含字母数字，长度>=6 -> 判定为房间号
        if (ROOM_CODE_PATTERN.matcher(trimmed).matches()) {
            return new RecognizeResult(true, false, trimmed, null, 25565);
        }

        // 6. 不含点号/冒号的输入 -> 视为房间号（非明确 IP 格式）
        if (!trimmed.contains(".") && !trimmed.contains(":")) {
            return new RecognizeResult(true, false, trimmed, null, 25565);
        }

        // 兜底：不明确的格式，不接管，交给MC原版流程
        return new RecognizeResult(false, false, trimmed, null, 25565);
    }

    /** 将 "host:port" 拆为 [host, port]，缺省 port=25565。 */
    private static String[] splitHostPort(String input) {
        int lastColon = input.lastIndexOf(':');
        if (lastColon > 0) {
            String host = input.substring(0, lastColon);
            String portStr = input.substring(lastColon + 1);
            try {
                int port = Integer.parseInt(portStr);
                if (port > 0 && port <= 65535) {
                    return new String[]{host, String.valueOf(port)};
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return new String[]{input, "25565"};
    }
}
