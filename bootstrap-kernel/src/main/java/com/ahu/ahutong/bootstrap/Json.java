package com.ahu.ahutong.bootstrap;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded strict JSON used before any downloadable runtime is trusted or loaded. */
public final class Json {
    private final String source;
    private int position;
    private Json(String source) { this.source = source; }
    public static Object parse(byte[] bytes) throws Exception {
        if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("JSON 超过大小限制");
        String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        Json parser = new Json(source);
        Object result = parser.value(0);
        parser.space();
        if (parser.position != source.length()) throw parser.error("JSON 尾部存在多余内容");
        return result;
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("需要 JSON 对象");
        return (Map<String,Object>) value;
    }
    @SuppressWarnings("unchecked") public static List<Object> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("需要 JSON 数组");
        return (List<Object>) value;
    }
    public static String text(Object value) {
        if (!(value instanceof String)) throw new IllegalArgumentException("需要字符串");
        return (String) value;
    }
    public static long number(Object value) {
        if (!(value instanceof Long)) throw new IllegalArgumentException("需要整数");
        return (Long) value;
    }
    public static int positive(Object value) {
        long result = number(value);
        if (result < 1 || result > Integer.MAX_VALUE) throw new IllegalArgumentException("无效正整数");
        return (int) result;
    }
    private Object value(int depth) {
        if (depth > 64) throw error("JSON 嵌套过深");
        space();
        if (position >= source.length()) throw error("JSON 不完整");
        char token = source.charAt(position);
        if (token == '"') return string();
        if (token == '{') {
            position++;
            Map<String,Object> object = new LinkedHashMap<>();
            space(); if (take('}')) return object;
            do {
                space(); if (position >= source.length() || source.charAt(position) != '"') throw error("无效对象键");
                String key = string();
                if (object.containsKey(key)) throw error("重复 JSON 键: " + key);
                space(); expect(':');
                object.put(key, value(depth + 1));
                if (object.size() > 8192) throw error("JSON 对象过大");
                space(); if (take('}')) return object;
                expect(',');
            } while (true);
        }
        if (token == '[') {
            position++;
            List<Object> array = new ArrayList<>();
            space(); if (take(']')) return array;
            do {
                array.add(value(depth + 1));
                if (array.size() > 8192) throw error("JSON 数组过大");
                space(); if (take(']')) return array;
                expect(',');
            } while (true);
        }
        for (String literal : new String[]{"true", "false", "null"}) {
            if (source.startsWith(literal, position)) {
                position += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = position;
        take('-');
        if (!take('0')) {
            if (position >= source.length() || source.charAt(position) < '1' || source.charAt(position) > '9') throw error("无效数字");
            while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
        }
        if (position < source.length() && ".eE".indexOf(source.charAt(position)) >= 0) {
            // Metadata and state contain integers only; reject lossy/non-finite coercion.
            throw error("元数据不接受小数或指数数字");
        }
        try { return Long.valueOf(source.substring(start, position)); }
        catch (NumberFormatException error) { throw error("整数超出范围"); }
    }
    private String string() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (position < source.length()) {
            char c = source.charAt(position++);
            if (c == '"') return value.toString();
            if (c < 0x20) throw error("字符串包含控制字符");
            if (c == '\\') {
                if (position >= source.length()) throw error("转义不完整");
                c = source.charAt(position++);
                switch (c) {
                    case '"': case '\\': case '/': value.append(c); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u':
                        if (position + 4 > source.length()) throw error("Unicode 转义不完整");
                        try { value.append((char)Integer.parseInt(source.substring(position, position + 4), 16)); }
                        catch (NumberFormatException e) { throw error("无效 Unicode 转义"); }
                        position += 4; break;
                    default: throw error("无效转义");
                }
            } else value.append(c);
        }
        throw error("字符串不完整");
    }
    private void space() {
        while (position < source.length() && " \t\r\n".indexOf(source.charAt(position)) >= 0) position++;
    }
    private boolean take(char c) {
        if (position < source.length() && source.charAt(position) == c) { position++; return true; }
        return false;
    }
    private void expect(char c) { if (!take(c)) throw error("预期字符 " + c); }
    private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " @" + position); }
    public static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : ((String)value).toCharArray()) {
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default: if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c)); else out.append(c);
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        StringBuilder out = new StringBuilder();
        if (value instanceof Map) {
            out.append('{');
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) {
                if (out.length() > 1) out.append(',');
                out.append(stringify(text(entry.getKey()))).append(':').append(stringify(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable) {
            out.append('[');
            for (Object item : (Iterable<?>)value) { if (out.length() > 1) out.append(','); out.append(stringify(item)); }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("无法编码 JSON 类型: " + value.getClass());
    }
}
