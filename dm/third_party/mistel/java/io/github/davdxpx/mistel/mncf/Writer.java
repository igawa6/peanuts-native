package io.github.davdxpx.mistel.mncf;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes .mncf text: any document as {@link Mncf#load} reads it (a map of TOML values), in one
 * fixed layout.
 *
 * <p>The layout follows the spec's examples: the file's own keys, its [update] and change
 * entries, then [game], the builds, the choice lists, every cheat with its values and operations
 * as indented sub-tables, the presets and the translations. Keys come in the order the spec
 * lists them, and keys it does not define after them in their own order. Locations, offsets,
 * addresses and path steps are written in hexadecimal, other whole numbers in decimal up to
 * 0xFFFF and in hexadecimal above. Writing a file that was read gives the same file again,
 * without its signature lines.
 *
 * <p>A document holds maps, lists, strings, whole numbers (Long, or BigInteger at 2^63 and
 * above), doubles and booleans. A float is written as the shortest text that reads back as the
 * same double, as Python's repr() writes it.
 */
public final class Writer {

    private static final List<String> TOP = Values.list("format", "name", "id", "authors", "about", "file_version",
            "language", "origin", "extensions", "signers");
    private static final List<String> TABLES =
            Values.list("update", "changes", "game", "builds", "choices", "cheat", "preset", "i18n");
    private static final List<String> UPDATE = Values.list("url");
    private static final List<String> CHANGES =
            Values.list("version", "date", "text", "added", "fixed", "removed", "renamed");
    private static final List<String> GAME = Values.list("platform", "id", "name");
    private static final List<String> BUILD = Values.list("version", "modules");
    private static final List<String> CHEAT = Values.list("id", "name", "about", "group", "tags", "default", "master",
            "run", "duration", "hold", "toggle", "press", "requires", "excludes", "radio", "match", "trigger",
            "interval");
    private static final List<String> VALUE =
            Values.list("type", "min", "max", "step", "default", "unit", "display", "choices", "flags");
    private static final List<String> PRESET = Values.list("id", "name", "cheats", "values");
    private static final Map<String, List<String>> OPS = new HashMap<>();
    private static final Map<String, List<String>> INLINE = new HashMap<>();
    private static final List<String> HEX_KEYS = Values.list("offset", "address", "path", "offsets");
    private static final List<String> MULTILINE = Values.list("code", "about");
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String INDENT = "  ";
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    static {
        OPS.put("patch", Values.list("at", "expect", "bytes", "encode", "asm"));
        OPS.put("write", Values.list("at", "type", "value", "expect"));
        OPS.put("override", Values.list("function", "action", "value", "expect"));
        OPS.put("read", Values.list("at", "type", "label"));
        OPS.put("when", Values.list("at", "type", "op", "value"));
        OPS.put("raw", Values.list("syntax", "build", "code"));
        List<String> location = Values.list("module", "region", "address", "find", "offset", "offsets", "section", "path");
        INLINE.put("at", location);
        INLINE.put("function", location);
        INLINE.put("value", Values.list("at", "type"));
        INLINE.put("encode", Values.list("insn", "width", "rd", "value"));
        INLINE.put("asm", Values.list("expect", "bytes"));
        INLINE.put("origin", Values.list("format", "name", "site"));
        INLINE.put("extensions", Values.list("required", "optional"));
        INLINE.put("modules", Collections.<String>emptyList());
        INLINE.put("renamed", Collections.<String>emptyList());
    }

    private Writer() {
    }

    /** Returns the text of a loaded file, in this layout, without its signature lines. */
    public static String write(MncfFile f) {
        return dumps(f.doc());
    }

    /**
     * Returns the text of a document. A key of the layout's own tables (update, changes, game,
     * builds, choices, cheat, preset, i18n) whose value does not have the shape the layout writes
     * is written as a plain value, with the file's own keys.
     *
     * @throws IllegalArgumentException for a value that has no TOML form
     */
    public static String dumps(Map<String, Object> doc) {
        List<String> out = new ArrayList<>();
        for (String k : ordered(doc, TOP)) {
            Object v = doc.get(k);
            if (TABLES.contains(k) ? !shaped(k, v)
                    : !(v instanceof Map && !INLINE.containsKey(k)) && !isTableArray(v)) {
                out.add(valueLine(k, v, ""));
            }
        }
        Map<String, Object> shaped = new HashMap<>();
        for (String k : TABLES) {
            if (doc.containsKey(k) && shaped(k, doc.get(k))) {
                shaped.put(k, doc.get(k));
            }
        }
        if (shaped.containsKey("update")) {
            plain(out, "[update]", map(shaped.get("update")), UPDATE);
        }
        if (shaped.containsKey("changes")) {
            for (Object c : (List<?>) shaped.get("changes")) {
                plain(out, "[[changes]]", map(c), CHANGES);
            }
        }
        if (shaped.containsKey("game")) {
            Map<String, Object> game = map(shaped.get("game"));
            out.add("");
            out.add("[game]");
            for (String k : ordered(game, GAME)) {
                out.add(valueLine(k, game.get(k), ""));
            }
        }
        if (shaped.containsKey("builds")) {
            for (Map.Entry<String, Object> b : map(shaped.get("builds")).entrySet()) {
                out.add("");
                out.add("[builds." + key(b.getKey()) + "]");
                Map<String, Object> build = map(b.getValue());
                for (String k : ordered(build, BUILD)) {
                    out.add(valueLine(k, build.get(k), ""));
                }
            }
        }
        if (shaped.containsKey("choices")) {
            for (Map.Entry<String, Object> c : map(shaped.get("choices")).entrySet()) {
                out.add("");
                out.add("[choices." + key(c.getKey()) + "]");
                for (Map.Entry<String, Object> e : map(c.getValue()).entrySet()) {
                    out.add(valueLine(e.getKey(), e.getValue(), ""));
                }
            }
        }
        if (shaped.containsKey("cheat")) {
            for (Object c : (List<?>) shaped.get("cheat")) {
                cheat(out, map(c));
            }
        }
        if (shaped.containsKey("preset")) {
            for (Object p : (List<?>) shaped.get("preset")) {
                Map<String, Object> preset = map(p);
                out.add("");
                out.add("[[preset]]");
                for (String k : ordered(preset, PRESET)) {
                    out.add(valueLine(k, preset.get(k), ""));
                }
            }
        }
        if (shaped.containsKey("i18n")) {
            section(out, "i18n", map(shaped.get("i18n")), false);
        }
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (TOP.contains(k) || TABLES.contains(k)) {
                continue;
            }
            if (v instanceof Map && !INLINE.containsKey(k)) {
                section(out, key(k), map(v), false);
            } else if (isTableArray(v)) {
                for (Object item : (List<?>) v) {
                    section(out, key(k), map(item), true);
                }
            }
        }
        while (!out.isEmpty() && out.get(0).isEmpty()) {
            out.remove(0);
        }
        return String.join("\n", out) + "\n";
    }

    /** A table of values under its header, tables in it as inline tables. */
    private static void plain(List<String> out, String header, Map<String, Object> table, List<String> order) {
        out.add("");
        out.add(header);
        for (String k : ordered(table, order)) {
            out.add(valueLine(k, table.get(k), ""));
        }
    }

    private static void cheat(List<String> out, Map<String, Object> c) {
        out.add("");
        out.add("[[cheat]]");
        List<String> subs = new ArrayList<>();
        for (String k : ordered(c, CHEAT)) {
            Object v = c.get(k);
            if ((k.equals("values") && isTableOfTables(v)) || isTableArray(v)) {
                subs.add(k);
            } else {
                out.add(valueLine(k, v, ""));
            }
        }
        for (String k : subs) {
            Object v = c.get(k);
            if (k.equals("values") && !isTableArray(v)) {
                for (Map.Entry<String, Object> e : map(v).entrySet()) {
                    out.add("");
                    out.add(INDENT + "[cheat.values." + key(e.getKey()) + "]");
                    Map<String, Object> spec = map(e.getValue());
                    for (String x : ordered(spec, VALUE)) {
                        out.add(valueLine(x, spec.get(x), INDENT));
                    }
                }
                continue;
            }
            List<String> order = OPS.containsKey(k) ? OPS.get(k) : Collections.<String>emptyList();
            for (Object item : (List<?>) v) {
                Map<String, Object> t = map(item);
                out.add("");
                out.add(INDENT + "[[cheat." + key(k) + "]]");
                for (String x : ordered(t, order)) {
                    out.add(valueLine(x, t.get(x), INDENT));
                }
            }
        }
    }

    /**
     * A table the layout does not know, under its header: its values, then its tables and arrays
     * of tables as sections of their own. The header is left out for a table that has only
     * tables and arrays of tables, unless it is an element of an array.
     */
    private static void section(List<String> out, String path, Map<String, Object> table, boolean array) {
        List<String> values = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        for (Map.Entry<String, Object> e : table.entrySet()) {
            if (!(e.getValue() instanceof Map) && !isTableArray(e.getValue())) {
                values.add(e.getKey());
            } else {
                subs.add(e.getKey());
            }
        }
        if (!values.isEmpty() || subs.isEmpty() || array) {
            out.add("");
            out.add(array ? "[[" + path + "]]" : "[" + path + "]");
            for (String k : values) {
                out.add(valueLine(k, table.get(k), ""));
            }
        }
        for (String k : subs) {
            Object v = table.get(k);
            if (v instanceof Map) {
                section(out, path + "." + key(k), map(v), false);
            } else {
                for (Object item : (List<?>) v) {
                    section(out, path + "." + key(k), map(item), true);
                }
            }
        }
    }

    /** Whether the value is a table that holds tables only, and at least one. */
    private static boolean isTableOfTables(Object v) {
        if (!(v instanceof Map) || ((Map<?, ?>) v).isEmpty()) {
            return false;
        }
        for (Object x : ((Map<?, ?>) v).values()) {
            if (!(x instanceof Map)) {
                return false;
            }
        }
        return true;
    }

    private static String valueLine(String k, Object v, String indent) {
        if (MULTILINE.contains(k) && v instanceof String && ((String) v).indexOf('\n') >= 0) {
            return indent + key(k) + " = " + multiline((String) v);
        }
        return indent + key(k) + " = " + scalar(v, k, false);
    }

    private static List<String> ordered(Map<String, Object> table, List<String> order) {
        List<String> keys = new ArrayList<>();
        for (String k : order) {
            if (table.containsKey(k)) {
                keys.add(k);
            }
        }
        for (String k : table.keySet()) {
            if (!order.contains(k)) {
                keys.add(k);
            }
        }
        return keys;
    }

    private static boolean isTableArray(Object v) {
        if (!(v instanceof List) || ((List<?>) v).isEmpty()) {
            return false;
        }
        for (Object x : (List<?>) v) {
            if (!(x instanceof Map)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a top-level table of the layout has the shape the layout writes: cheat, preset and
     * changes an array of tables, builds and choices a table of tables, update, game and i18n a
     * table.
     */
    private static boolean shaped(String k, Object v) {
        if (k.equals("cheat") || k.equals("preset") || k.equals("changes")) {
            return isTableArray(v);
        }
        if (k.equals("builds") || k.equals("choices")) {
            if (!(v instanceof Map)) {
                return false;
            }
            for (Object x : ((Map<?, ?>) v).values()) {
                if (!(x instanceof Map)) {
                    return false;
                }
            }
            return true;
        }
        return v instanceof Map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    // Values

    /** A key: bare where it can be, else a string. */
    static String key(String k) {
        return Py.matches(BARE, k) ? k : string(k);
    }

    /** A basic string. */
    static String string(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') {
                out.append('\\').append(ch);
            } else if (ch == '\n') {
                out.append("\\n");
            } else if (ch == '\t') {
                out.append("\\t");
            } else if (ch < 0x20 || ch == 0x7F) {
                out.append(String.format("\\u%04X", (int) ch));
            } else {
                out.append(ch);
            }
        }
        return out.append('"').toString();
    }

    /** A multi-line basic string, or a basic string where it holds other control characters. */
    static String multiline(String s) {
        String body = s.replace("\\", "\\\\").replace("\"\"\"", "\"\"\\\"");
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch < 0x20 && ch != '\n' && ch != '\t') {
                return string(s);
            }
        }
        return s.endsWith("\n") ? "\"\"\"\n" + body + "\"\"\"" : "\"\"\"\n" + body + "\\\n\"\"\"";
    }

    private static String integer(Object n, boolean hex) {
        BigInteger v = Values.big(n);
        if (v.compareTo(LONG_MAX) > 0) {
            return "\"0x" + v.toString(16).toUpperCase(java.util.Locale.ROOT) + "\"";
        }
        if (v.signum() < 0 || (!hex && v.compareTo(BigInteger.valueOf(0xFFFF)) <= 0)) {
            return v.toString();
        }
        return "0x" + v.toString(16).toUpperCase(java.util.Locale.ROOT);
    }

    private static String number(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        String text = Py.floatRepr(v);
        return text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('n') >= 0 ? text : text + ".0";
    }

    /** A value, inline tables and arrays included. */
    static String scalar(Object v, String k, boolean hex) {
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        if (v instanceof Long || v instanceof BigInteger || v instanceof Integer || v instanceof Short
                || v instanceof Byte) {
            return integer(v instanceof BigInteger ? v : (Object) ((Number) v).longValue(), hex || HEX_KEYS.contains(k));
        }
        if (v instanceof Double || v instanceof Float) {
            return number(((Number) v).doubleValue());
        }
        if (v instanceof String) {
            return string((String) v);
        }
        if (v instanceof List) {
            List<String> parts = new ArrayList<>();
            for (Object x : (List<?>) v) {
                parts.add(scalar(x, k, hex || HEX_KEYS.contains(k)));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        if (v instanceof Map) {
            Map<String, Object> m = map(v);
            List<String> order = INLINE.containsKey(k) ? INLINE.get(k) : Collections.<String>emptyList();
            boolean innerHex = HEX_KEYS.contains(k);
            List<String> parts = new ArrayList<>();
            for (String x : ordered(m, order)) {
                parts.add(key(x) + " = " + scalar(m.get(x), x, innerHex));
            }
            return parts.isEmpty() ? "{}" : "{ " + String.join(", ", parts) + " }";
        }
        throw new IllegalArgumentException("no TOML form for " + (v == null ? "null" : v.getClass().getSimpleName()));
    }
}
