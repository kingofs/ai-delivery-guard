package com.wsw.guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 180 行的 JSON 解析器。
 *
 * <p>为什么不引 Gson/Jackson：这个项目对"截断"的处理精度要求高于一般业务。
 * 主流库在遇到未闭合的对象时抛的是 SyntaxError，看不出"读到第几个字符断的"；
 * 而这里需要把断点位置、已读到的 itemId 数量一起带出去，才能判断模型是被
 * max_tokens 截断还是真的输出了坏结构。自己写一遍，行为完全可控。
 *
 * <p>还有一个更实际的原因：不引依赖 = 面试时 {@code javac} 一条命令就能编译运行，
 * 不用等 Maven 下包。
 */
public final class Json {

    /** 解析失败。message 里一定带位置，便于定位是不是被截断。 */
    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static Object parse(String text) {
        if (text == null || text.isBlank()) {
            throw new JsonException("输入为空，位置 0");
        }
        Json p = new Json(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != text.length()) {
            throw new JsonException("对象结束后仍有多余内容，位置 " + p.pos);
        }
        return v;
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private Object readValue() {
        if (pos >= src.length()) {
            throw new JsonException("输入在此处提前结束，位置 " + pos + "（极可能是被 max_tokens 截断）");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return readNumber();
                }
                throw new JsonException("非法字符 '" + c + "'，位置 " + pos);
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (pos >= src.length()) {
                throw new JsonException("对象未闭合，位置 " + pos);
            }
            if (src.charAt(pos) != '"') {
                throw new JsonException("对象键必须是字符串，位置 " + pos);
            }
            String key = readString();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw new JsonException("键 " + key + " 后缺少冒号，位置 " + pos);
            }
            pos++;
            skipWs();
            map.put(key, readValue());
            skipWs();
            if (pos >= src.length()) {
                throw new JsonException("对象未闭合，位置 " + pos);
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new JsonException("对象内出现意外字符 '" + c + "'，位置 " + pos);
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            if (pos >= src.length()) {
                throw new JsonException("数组未闭合，位置 " + pos + "（已读到元素 " + list.size() + " 个）");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new JsonException("数组内出现意外字符 '" + c + "'，位置 " + pos);
        }
    }

    private String readString() {
        pos++; // "
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new JsonException("字符串转义未完成，位置 " + pos);
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new JsonException("\\u 转义不完整，位置 " + pos);
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new JsonException("非法转义 \\" + e + "，位置 " + pos);
            }
        }
        throw new JsonException("字符串未闭合，位置 " + pos);
    }

    private Double readNumber() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new JsonException("非法数字 " + src.substring(start, pos) + "，位置 " + start);
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new JsonException("期望 " + literal + "，位置 " + pos);
        }
        pos += literal.length();
    }

    // ---------------- 序列化 ----------------

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
