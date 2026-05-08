package com.futureai.fdrs.layer2;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Json {
    private Json() {}

    public static String encode(Object v) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, v);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean) { sb.append(v.toString()); return; }
        if (v instanceof Number) {
            Number n = (Number) v;
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) { sb.append("null"); return; }
            sb.append(n.toString());
            return;
        }
        if (v instanceof CharSequence) { writeString(sb, v.toString()); return; }
        if (v instanceof Character) { writeString(sb, v.toString()); return; }
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object e : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, e);
            }
            sb.append(']');
            return;
        }
        if (v.getClass().isArray()) {
            sb.append('[');
            int len = Array.getLength(v);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, Array.get(v, i));
            }
            sb.append(']');
            return;
        }
        writeString(sb, v.toString());
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    public static Object walkBean(Object obj, int maxDepth) {
        return walk(obj, 0, maxDepth, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Object walk(Object v, int depth, int maxDepth, Set<Object> seen) {
        if (v == null) return null;
        if (v instanceof Boolean || v instanceof Number || v instanceof CharSequence || v instanceof Character) {
            return v;
        }
        if (v instanceof Enum) return ((Enum<?>) v).name();
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), walk(e.getValue(), depth + 1, maxDepth, seen));
            }
            return out;
        }
        if (v instanceof Iterable) {
            // Guard against Ford iterators whose next() throws NoSuchElementException
            // even after hasNext() == true — observed on com.ford.otx.services.vehicle.CdlHistory
            // in PR #1587 Part-2 live-smoke. Return accumulated entries up to the failure.
            List<Object> out = new ArrayList<>();
            java.util.Iterator<?> it;
            try { it = ((Iterable<?>) v).iterator(); }
            catch (Throwable ignore) { return out; }
            while (true) {
                try {
                    if (!it.hasNext()) break;
                    out.add(walk(it.next(), depth + 1, maxDepth, seen));
                } catch (java.util.NoSuchElementException | IllegalStateException stop) {
                    break;
                }
            }
            return out;
        }
        if (v.getClass().isArray()) {
            int len = Array.getLength(v);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(walk(Array.get(v, i), depth + 1, maxDepth, seen));
            return out;
        }
        if (depth >= maxDepth) return v.toString();
        if (!seen.add(v)) return v.toString();
        try {
            Map<String, Object> bean = new LinkedHashMap<>();
            for (Method m : v.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if ((m.getModifiers() & Modifier.STATIC) != 0) continue;
                if (m.getDeclaringClass() == Object.class) continue;
                String n = m.getName();
                String prop;
                if (n.startsWith("get") && n.length() > 3 && !"getClass".equals(n)) {
                    prop = Character.toLowerCase(n.charAt(3)) + n.substring(4);
                } else if (n.startsWith("is") && n.length() > 2 && m.getReturnType() == boolean.class) {
                    prop = Character.toLowerCase(n.charAt(2)) + n.substring(3);
                } else {
                    continue;
                }
                try {
                    Object r = m.invoke(v);
                    bean.put(prop, walk(r, depth + 1, maxDepth, seen));
                } catch (Throwable ignore) {
                    // skip getters that throw; leave their value out of the bean
                }
            }
            if (bean.isEmpty()) return v.toString();
            return bean;
        } finally {
            seen.remove(v);
        }
    }

    public static Object parse(String src) {
        Lexer lx = new Lexer(src);
        Object v = parseValue(lx);
        lx.skipWs();
        if (lx.hasMore()) throw new IllegalArgumentException("trailing content at pos " + lx.pos);
        return v;
    }

    private static Object parseValue(Lexer lx) {
        lx.skipWs();
        if (!lx.hasMore()) throw new IllegalArgumentException("unexpected EOF");
        char c = lx.peek();
        if (c == '{') return parseObject(lx);
        if (c == '[') return parseArray(lx);
        if (c == '"') return parseString(lx);
        if (c == '-' || (c >= '0' && c <= '9')) return parseNumber(lx);
        if (c == 't' || c == 'f') return parseBoolean(lx);
        if (c == 'n') return parseNull(lx);
        throw new IllegalArgumentException("unexpected char '" + c + "' at pos " + lx.pos);
    }

    private static Map<String, Object> parseObject(Lexer lx) {
        lx.expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        lx.skipWs();
        if (lx.hasMore() && lx.peek() == '}') { lx.consume(); return m; }
        while (true) {
            lx.skipWs();
            String k = parseString(lx);
            lx.skipWs();
            lx.expect(':');
            m.put(k, parseValue(lx));
            lx.skipWs();
            if (!lx.hasMore()) throw new IllegalArgumentException("unterminated object");
            if (lx.peek() == ',') { lx.consume(); continue; }
            if (lx.peek() == '}') { lx.consume(); return m; }
            throw new IllegalArgumentException("expected , or } at pos " + lx.pos);
        }
    }

    private static List<Object> parseArray(Lexer lx) {
        lx.expect('[');
        List<Object> list = new ArrayList<>();
        lx.skipWs();
        if (lx.hasMore() && lx.peek() == ']') { lx.consume(); return list; }
        while (true) {
            list.add(parseValue(lx));
            lx.skipWs();
            if (!lx.hasMore()) throw new IllegalArgumentException("unterminated array");
            if (lx.peek() == ',') { lx.consume(); continue; }
            if (lx.peek() == ']') { lx.consume(); return list; }
            throw new IllegalArgumentException("expected , or ] at pos " + lx.pos);
        }
    }

    private static String parseString(Lexer lx) {
        lx.expect('"');
        StringBuilder sb = new StringBuilder();
        while (lx.hasMore()) {
            char c = lx.next();
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (!lx.hasMore()) throw new IllegalArgumentException("trailing backslash");
                char esc = lx.next();
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (lx.pos + 4 > lx.src.length()) throw new IllegalArgumentException("truncated \\u escape");
                        int code = Integer.parseInt(lx.src.substring(lx.pos, lx.pos + 4), 16);
                        lx.pos += 4;
                        sb.append((char) code);
                        break;
                    default: throw new IllegalArgumentException("bad escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private static Object parseNumber(Lexer lx) {
        int start = lx.pos;
        if (lx.peek() == '-') lx.consume();
        while (lx.hasMore()) {
            char c = lx.peek();
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') lx.consume();
            else break;
        }
        String s = lx.src.substring(start, lx.pos);
        if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) return Double.parseDouble(s);
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return Double.parseDouble(s); }
    }

    private static Boolean parseBoolean(Lexer lx) {
        if (lx.src.startsWith("true", lx.pos)) { lx.pos += 4; return Boolean.TRUE; }
        if (lx.src.startsWith("false", lx.pos)) { lx.pos += 5; return Boolean.FALSE; }
        throw new IllegalArgumentException("expected true/false at " + lx.pos);
    }

    private static Object parseNull(Lexer lx) {
        if (lx.src.startsWith("null", lx.pos)) { lx.pos += 4; return null; }
        throw new IllegalArgumentException("expected null at " + lx.pos);
    }

    private static final class Lexer {
        final String src;
        int pos;
        Lexer(String src) { this.src = src; }
        void skipWs() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }
        boolean hasMore() { return pos < src.length(); }
        char peek() { return src.charAt(pos); }
        char next() { return src.charAt(pos++); }
        void consume() { pos++; }
        void expect(char c) {
            if (!hasMore() || src.charAt(pos) != c) throw new IllegalArgumentException("expected '" + c + "' at pos " + pos);
            pos++;
        }
    }
}
