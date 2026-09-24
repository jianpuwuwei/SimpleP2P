package com.simple_p2p.util;

import java.util.*;

/**
 * 轻量级 JSON 工具，替代 Gson 以避免编译期依赖 Gson。
 * 支持对象/数组/字符串/数字/布尔/null；不支持转义字符的完整规范，仅满足本项目简单需求。
 */
public final class SimpleJson {

    private SimpleJson() {}

    // ===================== 解析 =====================

    public static Object parse(String json) {
        if (json == null) return null;
        json = json.trim();
        if (json.isEmpty()) return null;
        Parser p = new Parser(json);
        p.skipWhitespace();
        return p.parseValue();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (o instanceof Map) return (Map<String, Object>) o;
        return new LinkedHashMap<>();
    }

    private static class Parser {
        final String s;
        int pos;
        Parser(String s) { this.s = s; this.pos = 0; }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= s.length()) return null;
            char c = s.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return map; }
            while (pos < s.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (pos < s.length() && s.charAt(pos) == ':') pos++;
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < s.length()) {
                    char c = s.charAt(pos);
                    if (c == ',') { pos++; continue; }
                    if (c == '}') { pos++; break; }
                }
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
            while (pos < s.length()) {
                Object v = parseValue();
                list.add(v);
                skipWhitespace();
                if (pos < s.length()) {
                    char c = s.charAt(pos);
                    if (c == ',') { pos++; continue; }
                    if (c == ']') { pos++; break; }
                }
            }
            return list;
        }

        String parseString() {
            StringBuilder sb = new StringBuilder();
            pos++; // '"'
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < s.length()) {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (pos + 4 <= s.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                                    pos += 4;
                                } catch (Exception ignored) {}
                            }
                            break;
                        default: sb.append(e); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Boolean parseBoolean() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            return Boolean.FALSE;
        }

        Object parseNull() {
            if (s.startsWith("null", pos)) pos += 4;
            return null;
        }

        Object parseNumber() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            boolean isFloat = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isDigit(c)) pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    pos++;
                } else break;
            }
            String num = s.substring(start, pos);
            try {
                if (isFloat) return Double.parseDouble(num);
                long l = Long.parseLong(num);
                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
                return l;
            } catch (Exception e) { return 0; }
        }
    }

    // ===================== 序列化 =====================

    public static String toJsonString(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            List<?> list = (List<?>) o;
            sb.append('[');
            boolean first = true;
            for (Object it : list) {
                if (!first) sb.append(',');
                first = false;
                write(sb, it);
            }
            sb.append(']');
            return;
        }
        if (o instanceof String) { writeString(sb, (String) o); return; }
        if (o instanceof Boolean) { sb.append(o.toString()); return; }
        if (o instanceof Number) { sb.append(o.toString()); return; }
        writeString(sb, o.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
    }

    // ===================== 便捷访问 =====================

    @SuppressWarnings("unchecked")
    public static String getStr(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) return null;
        Object o = map.get(key);
        return o == null ? null : o.toString();
    }

    public static int getInt(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) return 0;
        Object o = map.get(key);
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; }
    }

    public static long getLong(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) return 0;
        Object o = map.get(key);
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0; }
    }

    public static boolean getBool(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) return false;
        Object o = map.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        return Boolean.parseBoolean(o.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObj(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) return null;
        Object o = map.get(key);
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }

    public static boolean hasKey(Map<String, Object> map, String key) {
        return map != null && map.containsKey(key) && map.get(key) != null;
    }
}
