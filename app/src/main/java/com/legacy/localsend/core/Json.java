package com.legacy.localsend.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/序列化。
 *
 * 不依赖 org.json，也不依赖 Android，因此核心协议代码可以在桌面 JVM 上直接跑单元/集成测试。
 * 映射规则：JSON object -> Map&lt;String,Object&gt;，array -> List&lt;Object&gt;，
 * 整数 -> Long，浮点 -> Double，其余 String/Boolean/null 原样。
 */
public final class Json {

    public static class JsonException extends Exception {
        private static final long serialVersionUID = 1L;

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

    public static Object parse(String text) throws JsonException {
        if (text == null) {
            throw new JsonException("null input");
        }
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (p.pos < p.src.length()) {
            throw new JsonException("trailing characters at " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected JSON object");
        }
        return (Map<String, Object>) value;
    }

    public static Map<String, Object> newObject() {
        return new LinkedHashMap<String, Object>();
    }

    public static List<Object> newArray() {
        return new ArrayList<Object>();
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(((Boolean) value).booleanValue() ? "true" : "false");
        } else if (value instanceof Number) {
            Number n = (Number) value;
            if (value instanceof Double || value instanceof Float) {
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    sb.append("null");
                } else if (d == Math.rint(d) && Math.abs(d) < 1e15d) {
                    sb.append(Long.toString((long) d));
                } else {
                    sb.append(Double.toString(d));
                }
            } else {
                sb.append(Long.toString(n.longValue()));
            }
        } else if (value instanceof Map) {
            writeObject(sb, (Map<?, ?>) value);
        } else if (value instanceof List) {
            writeArray(sb, (List<?>) value);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = hex.length(); k < 4; k++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object parseValue() throws JsonException {
        if (pos >= src.length()) {
            throw new JsonException("unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObjectValue();
            case '[':
                return parseArrayValue();
            case '"':
                return parseStringValue();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumberValue();
                }
                throw new JsonException("unexpected character '" + c + "' at " + pos);
        }
    }

    private Map<String, Object> parseObjectValue() throws JsonException {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // {
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw new JsonException("expected object key at " + pos);
            }
            String key = parseStringValue();
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw new JsonException("expected ':' at " + pos);
            }
            pos++;
            skipWhitespace();
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            if (pos >= src.length()) {
                throw new JsonException("unterminated object");
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
            throw new JsonException("expected ',' or '}' at " + pos);
        }
    }

    private List<Object> parseArrayValue() throws JsonException {
        List<Object> list = new ArrayList<Object>();
        pos++; // [
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            if (pos >= src.length()) {
                throw new JsonException("unterminated array");
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
            throw new JsonException("expected ',' or ']' at " + pos);
        }
    }

    private String parseStringValue() throws JsonException {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder(64);
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= src.length()) {
                    throw new JsonException("bad escape");
                }
                char e = src.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw new JsonException("bad unicode escape");
                        }
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char h = src.charAt(pos++);
                            int digit = Character.digit(h, 16);
                            if (digit < 0) {
                                throw new JsonException("bad unicode escape");
                            }
                            code = (code << 4) | digit;
                        }
                        sb.append((char) code);
                        break;
                    default:
                        throw new JsonException("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new JsonException("unterminated string");
    }

    private Object parseNumberValue() throws JsonException {
        int start = pos;
        if (pos < src.length() && src.charAt(pos) == '-') {
            pos++;
        }
        boolean isDouble = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                isDouble = true;
                pos++;
            } else {
                break;
            }
        }
        String num = src.substring(start, pos);
        try {
            if (isDouble) {
                return Double.valueOf(num);
            }
            return Long.valueOf(num);
        } catch (NumberFormatException e) {
            throw new JsonException("bad number '" + num + "'");
        }
    }

    private void expectLiteral(String literal) throws JsonException {
        if (!src.startsWith(literal, pos)) {
            throw new JsonException("expected '" + literal + "' at " + pos);
        }
        pos += literal.length();
    }

    // ---------- 类型安全的读取辅助 ----------

    public static String optString(Map<String, Object> map, String key, String def) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return def;
        }
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    public static long optLong(Map<String, Object> map, String key, long def) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public static int optInt(Map<String, Object> map, String key, int def) {
        return (int) optLong(map, key, def);
    }

    public static boolean optBoolean(Map<String, Object> map, String key, boolean def) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof String) {
            return "true".equalsIgnoreCase((String) v);
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> optObject(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> optArray(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v instanceof List ? (List<Object>) v : null;
    }
}
