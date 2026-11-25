package dev.peanuts.ctl;

import java.util.Map;

/**
 * The status file's writer: maps, lists, strings, numbers, booleans and null as JSON text, two spaces a level. Its own few lines instead of org.json so the
 * status and the results are built from plain maps, which the JVM tests can make and read without Android.
 */
public final class Json {
    private Json() {}

    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        value(b, o, 0);
        return b.append('\n').toString();
    }

    private static void value(StringBuilder b, Object o, int depth) {
        if (o == null) b.append("null");
        else if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                line(b, depth + 1);
                string(b, String.valueOf(e.getKey()));
                b.append(": ");
                value(b, e.getValue(), depth + 1);
            }
            line(b, depth);
            b.append('}');
        } else if (o instanceof Iterable) {
            boolean first = true;
            b.append('[');
            for (Object x : (Iterable<?>) o) {
                if (!first) b.append(',');
                first = false;
                line(b, depth + 1);
                value(b, x, depth + 1);
            }
            if (!first) line(b, depth);
            b.append(']');
        } else if (o instanceof Boolean) b.append(o);
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");   // JSON has no such numbers
            else b.append(d);
        } else if (o instanceof Number) b.append(o);
        else string(b, o.toString());
    }

    /**
     * JSON text back into maps (in their order), lists, strings, numbers (an Integer when it fits, else a Long, a Double with a fraction or an exponent),
     * booleans and null: what {@link #write} makes. The running game's answer to a live control comes across as this text (GameLive). Throws
     * IllegalArgumentException for text that is not JSON.
     */
    public static Object read(String text) {
        int[] at = {0};
        Object v = parse(text, at);
        skip(text, at);
        if (at[0] != text.length()) throw new IllegalArgumentException("JSON: text after the value at " + at[0]);
        return v;
    }

    private static void skip(String s, int[] at) { while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++; }

    private static Object parse(String s, int[] at) {
        skip(s, at);
        if (at[0] >= s.length()) throw new IllegalArgumentException("JSON: no value at " + at[0]);
        char c = s.charAt(at[0]);
        if (c == '{') {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            at[0]++;
            skip(s, at);
            if (at[0] < s.length() && s.charAt(at[0]) == '}') { at[0]++; return m; }
            while (true) {
                skip(s, at);
                if (at[0] >= s.length() || s.charAt(at[0]) != '"') throw new IllegalArgumentException("JSON: no key at " + at[0]);
                String k = text(s, at);
                skip(s, at);
                expect(s, at, ':');
                m.put(k, parse(s, at));
                skip(s, at);
                if (at[0] < s.length() && s.charAt(at[0]) == ',') { at[0]++; continue; }
                expect(s, at, '}');
                return m;
            }
        }
        if (c == '[') {
            java.util.List<Object> l = new java.util.ArrayList<>();
            at[0]++;
            skip(s, at);
            if (at[0] < s.length() && s.charAt(at[0]) == ']') { at[0]++; return l; }
            while (true) {
                l.add(parse(s, at));
                skip(s, at);
                if (at[0] < s.length() && s.charAt(at[0]) == ',') { at[0]++; continue; }
                expect(s, at, ']');
                return l;
            }
        }
        if (c == '"') return text(s, at);
        if (s.startsWith("true", at[0])) { at[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", at[0])) { at[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", at[0])) { at[0] += 4; return null; }
        int from = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        String n = s.substring(from, at[0]);
        try {
            if (n.indexOf('.') >= 0 || n.indexOf('e') >= 0 || n.indexOf('E') >= 0) return Double.parseDouble(n);
            long x = Long.parseLong(n);
            return x == (int) x ? (Object) (int) x : (Object) x;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JSON: no value at " + from);
        }
    }

    private static void expect(String s, int[] at, char c) {
        if (at[0] >= s.length() || s.charAt(at[0]) != c) throw new IllegalArgumentException("JSON: '" + c + "' expected at " + at[0]);
        at[0]++;
    }

    private static String text(String s, int[] at) {
        StringBuilder b = new StringBuilder();
        at[0]++;   // the opening quote
        while (true) {
            if (at[0] >= s.length()) throw new IllegalArgumentException("JSON: a string without its end");
            char c = s.charAt(at[0]++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (at[0] >= s.length()) throw new IllegalArgumentException("JSON: a string without its end");
            char e = s.charAt(at[0]++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (at[0] + 4 > s.length()) throw new IllegalArgumentException("JSON: a short \\u escape");
                    try { b.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16)); }
                    catch (NumberFormatException x) { throw new IllegalArgumentException("JSON: a bad \\u escape"); }
                    at[0] += 4;
                    break;
                default: b.append(e);   // \" \\ \/
            }
        }
    }

    private static void line(StringBuilder b, int depth) {
        b.append('\n');
        for (int i = 0; i < depth; i++) b.append("  ");
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
