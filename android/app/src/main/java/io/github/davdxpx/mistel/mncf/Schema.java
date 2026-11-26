package io.github.davdxpx.mistel.mncf;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Checks a parsed file against the spec and builds the model.
 *
 * <p>Problems outside a cheat make the whole file unusable ({@link MncfException}) or, for keys
 * the spec does not define, a warning. Problems inside a cheat turn only that cheat off: its
 * problem becomes unknown-key:&lt;key&gt;, extension-missing:&lt;name&gt; or invalid:&lt;key&gt;,
 * where &lt;key&gt; is the key's path from the cheat with its parts joined by dots.
 */
final class Schema {

    static final List<String> KNOWN_EXTENSIONS = Values.list("raw/1", "arm64-imm/1");

    private static final List<String> FILE_KEYS = Values.list("format", "name", "id", "authors", "about",
            "file_version", "language", "origin", "extensions", "signers", "update", "changes", "game", "builds",
            "cheat", "choices", "preset", "i18n");
    private static final List<String> RUNS = Values.list("on", "once", "action", "timed", "monitor");
    private static final List<String> OPS =
            Values.list("==", "!=", "<", "<=", ">", ">=", "&", "!&", "between", "changed");
    private static final List<String> SECTIONS = Values.list("code", "rodata", "data");
    private static final List<String> ACTIONS = Values.list("return", "skip");
    private static final List<String> OP_KEYS = Values.list("patch", "write", "override", "read", "when", "raw");
    private static final List<String> WHOLE_VALUE_TYPES;

    static {
        List<String> types = new ArrayList<>(Values.INT_TYPES);
        types.add("bool");
        WHOLE_VALUE_TYPES = Collections.unmodifiableList(types);
    }

    /** A node of the key tree of a cheat, for finding keys the spec does not define. */
    private static final class Node {
        static final int VALUE = 0;
        static final int TABLE = 1;
        static final int ARRAY = 2;
        static final int MAP = 3;

        final int kind;
        final Map<String, Node> children;
        final Node element;

        private Node(int kind, Map<String, Node> children, Node element) {
            this.kind = kind;
            this.children = children;
            this.element = element;
        }

        static Node table(Object... keysAndNodes) {
            Map<String, Node> children = new LinkedHashMap<>();
            for (int i = 0; i < keysAndNodes.length; i += 2) {
                children.put((String) keysAndNodes[i], (Node) keysAndNodes[i + 1]);
            }
            return new Node(TABLE, children, null);
        }

        static Node values(String... keys) {
            Object[] pairs = new Object[2 * keys.length];
            for (int i = 0; i < keys.length; i++) {
                pairs[2 * i] = keys[i];
                pairs[2 * i + 1] = new Node(VALUE, null, null);
            }
            return table(pairs);
        }

        static Node arrayOf(Node table) {
            return new Node(ARRAY, null, table);
        }

        static Node mapOf(Node table) {
            return new Node(MAP, null, table);
        }

        Node with(Object... keysAndNodes) {
            Map<String, Node> all = new LinkedHashMap<>(children);
            all.putAll(table(keysAndNodes).children);
            return new Node(TABLE, all, null);
        }
    }

    private static final Node VALUE = new Node(Node.VALUE, null, null);
    private static final Node LOC = Node.values("module", "offset", "offsets", "find", "section", "region", "address", "path");
    private static final Node CHEAT_TREE = Node.values("id", "name", "about", "group", "tags", "default", "master", "run",
            "duration", "hold", "toggle", "press", "requires", "excludes", "radio", "match", "trigger", "interval")
            .with("values", Node.mapOf(Node.values("type", "min", "max", "step", "default", "unit", "display", "choices",
                            "flags")),
                    "patch", Node.arrayOf(Node.table("at", LOC, "expect", VALUE, "bytes", VALUE,
                            "asm", Node.values("expect", "bytes"),
                            "encode", Node.values("insn", "width", "rd", "value"))),
                    "write", Node.arrayOf(Node.table("at", LOC, "type", VALUE, "value", VALUE, "expect", VALUE)),
                    "override", Node.arrayOf(Node.table("function", LOC, "action", VALUE, "value", VALUE, "expect", VALUE)),
                    "read", Node.arrayOf(Node.table("at", LOC, "type", VALUE, "label", VALUE)),
                    "when", Node.arrayOf(Node.table("at", LOC, "type", VALUE, "op", VALUE,
                            "value", Node.table("at", LOC, "type", VALUE))),
                    "raw", Node.arrayOf(Node.values("syntax", "build", "code")));

    /** A rule broken inside a cheat. */
    static final class Problem extends Exception {
        private static final long serialVersionUID = 1L;
        final String reason;

        Problem(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    private static Problem invalid(String path) {
        return new Problem(path.isEmpty() ? "invalid" : "invalid:" + path);
    }

    private Schema() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        return (List<Object>) v;
    }

    private static Object get(Map<String, Object> table, String key, Object fallback) {
        return table.containsKey(key) ? table.get(key) : fallback;
    }

    // The file

    /** The checked parts of a parsed file. */
    static MncfFile.Parts buildFile(Map<String, Object> doc) throws MncfException {
        if (doc.isEmpty() || !doc.keySet().iterator().next().equals("format")) {
            throw new MncfException("format", "`format` must be the first key of the file");
        }
        if (!"mncf/1".equals(doc.get("format"))) {
            throw new MncfException("format", "the format is " + Py.repr(doc.get("format"))
                    + ", this reader reads \"mncf/1\"");
        }
        MncfFile.Parts out = new MncfFile.Parts();
        List<String> warnings = out.warnings;
        for (String k : doc.keySet()) {
            if (!FILE_KEYS.contains(k)) {
                warnings.add("unknown-key:" + k);
            }
        }
        out.doc = doc;

        out.name = reqStr(doc, "name");
        if (doc.containsKey("about")) {
            out.about = reqStr(doc, "about");
        }
        if (doc.containsKey("file_version")) {
            out.fileVersion = reqStr(doc, "file_version");
        }
        if (doc.containsKey("language")) {
            out.language = reqStr(doc, "language");
        }
        if (doc.containsKey("authors")) {
            out.authors = strList(doc.get("authors"), "authors");
        }
        out.signers = Updates.readSigners(doc, warnings);
        Updates.Keys keys = Updates.readKeys(doc, warnings, Updates.CHEAT_ID);
        out.id = keys.id;
        out.update = keys.update;
        out.changes = keys.changes;
        if (doc.containsKey("origin")) {
            Object o = doc.get("origin");
            if (!(o instanceof Map) || !(map(o).get("format") instanceof String)
                    || !(get(map(o), "name", "") instanceof String) || !(get(map(o), "site", "") instanceof String)) {
                throw new MncfException("schema:origin",
                        "`origin` must be a table with a `format` string and maybe `name` and `site` strings");
            }
            for (String k : map(o).keySet()) {
                if (!k.equals("format") && !k.equals("name") && !k.equals("site")) {
                    warnings.add("unknown-key:origin." + k);
                }
            }
            out.origin = map(o);
        }

        List<String> required = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        if (doc.containsKey("extensions")) {
            Object ext = doc.get("extensions");
            if (!(ext instanceof Map)) {
                throw new MncfException("schema:extensions", "`extensions` must be a table");
            }
            Map<String, Object> e = map(ext);
            for (String k : e.keySet()) {
                if (!k.equals("required") && !k.equals("optional")) {
                    warnings.add("unknown-key:extensions." + k);
                }
            }
            required = strList(get(e, "required", new ArrayList<>()), "extensions.required");
            optional = strList(get(e, "optional", new ArrayList<>()), "extensions.optional");
            List<String> both = new ArrayList<>(required);
            both.addAll(optional);
            for (String name : both) {
                if (!Py.matches(Values.EXTENSION, name)) {
                    throw new MncfException("schema:extensions", Py.repr(name)
                            + " is not an extension name with its version");
                }
            }
        }
        out.required = required;
        out.optional = optional;

        Object g = doc.get("game");
        if (!(g instanceof Map)) {
            throw new MncfException("schema:game", "the `game` table is missing");
        }
        Map<String, Object> game = map(g);
        for (String k : game.keySet()) {
            if (!k.equals("platform") && !k.equals("id") && !k.equals("name")) {
                warnings.add("unknown-key:game." + k);
            }
        }
        Object platName = game.get("platform");
        if (!(platName instanceof String)) {
            throw new MncfException("schema:game.platform", "`game.platform` must be a string");
        }
        Platform plat = Platform.forName((String) platName);
        if (plat == null) {
            throw new MncfException("platform", "the platform " + Py.repr(platName) + " is not in Appendix A");
        }
        out.platform = plat;
        Object gid = game.get("id");
        if (!(gid instanceof String) || !plat.isValidGameId((String) gid)) {
            throw new MncfException("schema:game.id", "`game.id` is not a game ID of the " + platName + " platform");
        }
        out.gameId = (String) gid;
        if (game.containsKey("name")) {
            if (!(game.get("name") instanceof String)) {
                throw new MncfException("schema:game.name", "`game.name` must be a string");
            }
            out.gameName = (String) game.get("name");
        }

        Object rawBuilds = get(doc, "builds", new LinkedHashMap<String, Object>());
        if (!(rawBuilds instanceof Map)) {
            throw new MncfException("schema:builds", "`builds` must be a table");
        }
        for (Map.Entry<String, Object> entry : map(rawBuilds).entrySet()) {
            String key = entry.getKey();
            if (key.isEmpty() || !(entry.getValue() instanceof Map)) {
                throw new MncfException("schema:builds", "build " + Py.repr(key)
                        + " must be a table with a non-empty key");
            }
            Map<String, Object> b = map(entry.getValue());
            for (String k : b.keySet()) {
                if (!k.equals("version") && !k.equals("modules")) {
                    warnings.add("unknown-key:builds." + key + "." + k);
                }
            }
            Object mods = b.get("modules");
            if (!(mods instanceof Map) || map(mods).isEmpty()) {
                throw new MncfException("schema:builds", "build " + Py.repr(key) + " needs a `modules` table");
            }
            Map<String, String> modules = new LinkedHashMap<>();
            for (Map.Entry<String, Object> m : map(mods).entrySet()) {
                if ((!plat.modules().isEmpty() && !plat.modules().contains(m.getKey())) || m.getKey().isEmpty()) {
                    throw new MncfException("schema:builds", "build " + Py.repr(key) + ": " + Py.repr(m.getKey())
                            + " is not a module of the " + platName + " platform");
                }
                if (!plat.isValidModuleId(m.getValue())) {
                    throw new MncfException("schema:builds", "build " + Py.repr(key) + ": " + Py.repr(m.getValue())
                            + " is not a module identity of " + platName);
                }
                modules.put(m.getKey(), (String) m.getValue());
            }
            Object version = get(b, "version", key);
            if (!(version instanceof String)) {
                throw new MncfException("schema:builds", "build " + Py.repr(key) + ": `version` must be a string");
            }
            out.builds.put(key, new Build(key, (String) version, modules));
        }

        Object rawChoices = get(doc, "choices", new LinkedHashMap<String, Object>());
        if (!(rawChoices instanceof Map)) {
            throw new MncfException("schema:choices", "`choices` must be a table");
        }
        for (Map.Entry<String, Object> entry : map(rawChoices).entrySet()) {
            Map<Number, String> parsed = choiceList(entry.getValue());
            if (parsed == null) {
                throw new MncfException("schema:choices", "choice list " + Py.repr(entry.getKey())
                        + " must map whole numbers to names");
            }
            out.choices.put(entry.getKey(), parsed);
        }

        Object rawCheats = doc.get("cheat");
        if (!(rawCheats instanceof List) || list(rawCheats).isEmpty() || !allMaps(list(rawCheats))) {
            throw new MncfException("schema:cheat", "the file needs at least one [[cheat]]");
        }
        List<String> ids = new ArrayList<>();
        List<Object> cheatTables = list(rawCheats);
        for (int i = 0; i < cheatTables.size(); i++) {
            Object cid = map(cheatTables.get(i)).get("id");
            if (!(cid instanceof String) || !Py.matches(Values.ID, cid)) {
                throw new MncfException("schema:cheat.id", "cheat " + (i + 1) + " has no valid `id`");
            }
            if (ids.contains(cid)) {
                throw new MncfException("duplicate-id:" + cid, "two cheats have the id " + Py.repr(cid));
            }
            ids.add((String) cid);
        }
        List<String> listed = new ArrayList<>(required);
        listed.addAll(optional);
        Context ctx = new Context(plat, out.builds, out.choices, new HashSet<>(ids), listed);
        for (int i = 0; i < cheatTables.size(); i++) {
            out.cheats.add(checkCheat(i, map(cheatTables.get(i)), ctx));
        }

        Object rawPresets = get(doc, "preset", new ArrayList<>());
        if (!(rawPresets instanceof List)) {
            throw new MncfException("schema:preset", "`preset` must be an array of tables");
        }
        List<Object> presetTables = list(rawPresets);
        List<String> presetIds = new ArrayList<>();
        for (int i = 0; i < presetTables.size(); i++) {
            Object p = presetTables.get(i);
            Preset preset = preset(p, out.cheats, presetIds);
            if (preset == null) {
                Object pid = p instanceof Map ? map(p).get("id") : null;
                warnings.add("preset:" + (pid instanceof String ? (String) pid : String.valueOf(i + 1)));
            } else {
                out.presets.add(preset);
                presetIds.add(preset.id());
            }
        }

        Object i18n = get(doc, "i18n", Collections.emptyMap());
        boolean good = i18n instanceof Map;
        if (good) {
            for (Object v : ((Map<?, ?>) i18n).values()) {
                good &= v instanceof Map;
            }
        }
        if (!good) {
            warnings.add("i18n");
            i18n = Collections.emptyMap();
        }
        out.i18n = map(i18n);
        return out;
    }

    private static boolean allMaps(List<Object> items) {
        for (Object item : items) {
            if (!(item instanceof Map)) {
                return false;
            }
        }
        return true;
    }

    private static String reqStr(Map<String, Object> table, String key) throws MncfException {
        Object v = table.get(key);
        if (!(v instanceof String)) {
            throw new MncfException("schema:" + key, "`" + key + "` must be a string");
        }
        return (String) v;
    }

    private static List<String> strList(Object v, String key) throws MncfException {
        if (!(v instanceof List)) {
            throw new MncfException("schema:" + key, "`" + key + "` must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (Object x : list(v)) {
            if (!(x instanceof String)) {
                throw new MncfException("schema:" + key, "`" + key + "` must be an array of strings");
            }
            out.add((String) x);
        }
        return out;
    }

    /** A key of a choice list as a number: up to 20 decimal digits, or 0x and up to 16 hexadecimal ones, maybe after a minus. */
    private static Number choiceKey(String k) {
        Matcher m = Py.match(CHOICE_KEY, k);
        if (m == null) {
            return null;
        }
        BigInteger n = m.group(2) != null ? new BigInteger(m.group(2), 16) : new BigInteger(m.group(3));
        return Values.normalize(m.group(1).isEmpty() ? n : n.negate());
    }

    private static final java.util.regex.Pattern CHOICE_KEY =
            java.util.regex.Pattern.compile("(-?)(?:0x([0-9A-Fa-f]{1,16})|([0-9]{1,20}))");

    /** Whether a number is one a player can choose for this value: inside its range and among its choices. */
    static boolean inSpec(ValueSpec s, Object n) {
        return !((s.min() != null && Values.less(n, s.min())) || (s.max() != null && Values.less(s.max(), n))
                || (s.choices() != null && !s.choices().containsKey(n)));
    }

    private static Map<Number, String> choiceList(Object table) {
        if (!(table instanceof Map) || map(table).isEmpty()) {
            return null;
        }
        Map<Number, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(table).entrySet()) {
            Number n = choiceKey(e.getKey());
            if (n == null || !(e.getValue() instanceof String)) {
                return null;
            }
            out.put(n, (String) e.getValue());
        }
        return out;
    }

    private static Preset preset(Object p, List<Cheat> cheats, List<String> seen) {
        if (!(p instanceof Map)) {
            return null;
        }
        Map<String, Object> t = map(p);
        Object pid = t.get("id");
        Object name = t.get("name");
        Object ids = get(t, "cheats", new ArrayList<>());
        if (!(pid instanceof String) || !Py.matches(Values.ID, pid) || seen.contains(pid) || !(name instanceof String)) {
            return null;
        }
        Map<String, Cheat> byId = new LinkedHashMap<>();
        for (Cheat c : cheats) {
            byId.put(c.id(), c);
        }
        if (!(ids instanceof List)) {
            return null;
        }
        List<String> cheatIds = new ArrayList<>();
        for (Object x : list(ids)) {
            if (!(x instanceof String) || !byId.containsKey(x)) {
                return null;
            }
            cheatIds.add((String) x);
        }
        Object values = get(t, "values", new LinkedHashMap<String, Object>());
        if (!(values instanceof Map)) {
            return null;
        }
        Map<String, Object> numbers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(values).entrySet()) {
            String k = e.getKey();
            int dot = k.indexOf('.');
            String cid = dot < 0 ? k : k.substring(0, dot);
            String vid = dot < 0 ? "" : k.substring(dot + 1);
            Cheat cheat = byId.get(cid);
            ValueSpec spec = cheat != null ? cheat.values().get(vid) : null;
            Object n = spec != null ? valueNumber(spec.type(), e.getValue()) : null;
            if (n == null || !inSpec(spec, n)) {
                return null;
            }
            numbers.put(k, n);
        }
        for (String k : t.keySet()) {
            if (!k.equals("id") && !k.equals("name") && !k.equals("cheats") && !k.equals("values")) {
                return null;
            }
        }
        return new Preset((String) pid, (String) name, cheatIds, numbers);
    }

    // One cheat

    private static final class Context {
        final Platform platform;
        final Map<String, Build> builds;
        final Map<String, Map<Number, String>> choices;
        final Set<String> ids;
        final List<String> listed;
        final List<String> unknownListed = new ArrayList<>();

        Context(Platform platform, Map<String, Build> builds, Map<String, Map<Number, String>> choices, Set<String> ids,
                List<String> listed) {
            this.platform = platform;
            this.builds = builds;
            this.choices = choices;
            this.ids = ids;
            this.listed = listed;
            for (String e : listed) {
                if (!KNOWN_EXTENSIONS.contains(e)) {
                    unknownListed.add(e);
                }
            }
        }
    }

    private static Cheat checkCheat(int index, Map<String, Object> table, Context ctx) {
        Cheat.Draft c = new Cheat.Draft();
        c.index = index;
        c.id = (String) table.get("id");
        c.table = table;
        try {
            checkKeys(table, CHEAT_TREE, Collections.<String>emptyList(), ctx);
            fillCheat(c, table, ctx);
        } catch (Problem p) {
            c.problem = p.reason;
        }
        return new Cheat(c);
    }

    private static String extensionOf(List<String> path) {
        if (path.equals(Collections.singletonList("raw"))) {
            return "raw/1";
        }
        if (path.equals(Arrays.asList("patch", "encode"))) {
            return "arm64-imm/1";
        }
        return null;
    }

    private static void checkKeys(Map<String, Object> table, Node tree, List<String> path, Context ctx) throws Problem {
        for (Map.Entry<String, Object> entry : table.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            List<String> sub = new ArrayList<>(path);
            sub.add(key);
            String ext = extensionOf(sub);
            if (!tree.children.containsKey(key) || (ext != null && !ctx.listed.contains(ext))) {
                if (!ctx.unknownListed.isEmpty()) {
                    throw new Problem("extension-missing:" + ctx.unknownListed.get(0));
                }
                throw new Problem("unknown-key:" + String.join(".", sub));
            }
            Node node = tree.children.get(key);
            if (node.kind == Node.TABLE) {
                if (value instanceof Map) {
                    checkKeys(map(value), node, sub, ctx);
                }
            } else if (node.kind == Node.ARRAY) {
                if (value instanceof List) {
                    for (Object item : list(value)) {
                        if (item instanceof Map) {
                            checkKeys(map(item), node.element, sub, ctx);
                        }
                    }
                }
            } else if (node.kind == Node.MAP) {
                if (value instanceof Map) {
                    for (Map.Entry<String, Object> named : map(value).entrySet()) {
                        if (named.getValue() instanceof Map) {
                            List<String> inner = new ArrayList<>(sub);
                            inner.add(named.getKey());
                            checkKeys(map(named.getValue()), node.element, inner, ctx);
                        }
                    }
                }
            }
        }
    }

    private static void fillCheat(Cheat.Draft c, Map<String, Object> t, Context ctx) throws Problem {
        Platform plat = ctx.platform;
        c.name = string(t, "name", true);
        c.about = string(t, "about", false);
        if (t.containsKey("group")) {
            Object g = t.get("group");
            if (!(g instanceof String) || ((String) g).isEmpty() || Py.split((String) g, '/').contains("")) {
                throw invalid("group");
            }
            c.group = (String) g;
        }
        if (t.containsKey("tags")) {
            c.tags = stringList(t.get("tags"), "tags");
        }
        c.defaultOn = bool(t, "default", false);
        c.master = bool(t, "master", false);
        Object run = get(t, "run", "on");
        if (!RUNS.contains(run)) {
            throw invalid("run");
        }
        c.run = (String) run;
        if (t.containsKey("duration") || c.run.equals("timed")) {
            Object d = t.get("duration");
            if (!c.run.equals("timed") || !Values.isNumber(d) || !finite(d) || Values.lessOrEqual(d, 0L)) {
                throw invalid("duration");
            }
            c.duration = (Number) d;
        }
        if (t.containsKey("hold")) {
            c.hold = buttons(t.get("hold"), "hold", plat);
        }
        if (t.containsKey("toggle")) {
            c.toggle = buttons(t.get("toggle"), "toggle", plat);
        }
        if (t.containsKey("press")) {
            c.press = buttons(t.get("press"), "press", plat);
        }
        if (c.run.equals("action") != (c.press != null)) {
            throw invalid("press");
        }
        if (t.containsKey("requires")) {
            c.requires = others(t.get("requires"), "requires", c, ctx);
        }
        if (t.containsKey("excludes")) {
            c.excludes = others(t.get("excludes"), "excludes", c, ctx);
        }
        if (t.containsKey("radio")) {
            Object r = t.get("radio");
            if (!(r instanceof String) || ((String) r).isEmpty()) {
                throw invalid("radio");
            }
            c.radio = (String) r;
        }
        Object match = get(t, "match", "all");
        if (!"all".equals(match) && !"any".equals(match)) {
            throw invalid("match");
        }
        c.match = (String) match;
        Object trigger = get(t, "trigger", "while");
        if (!"while".equals(trigger) && !"enter".equals(trigger)) {
            throw invalid("trigger");
        }
        c.trigger = (String) trigger;
        Object interval = get(t, "interval", 1L);
        if (!Values.isInt(interval) || Values.less(interval, 1L)) {
            throw invalid("interval");
        }
        c.interval = ((Number) interval).longValue();

        if (t.containsKey("values")) {
            Object vs = t.get("values");
            if (!(vs instanceof Map)) {
                throw invalid("values");
            }
            for (Map.Entry<String, Object> e : map(vs).entrySet()) {
                c.values.put(e.getKey(), valueSpec(e.getKey(), e.getValue(), ctx));
            }
        }

        List<Model.Op> ops = new ArrayList<>();
        for (Map.Entry<String, Object> e : t.entrySet()) {
            String key = e.getKey();
            if (!OP_KEYS.contains(key)) {
                continue;
            }
            Object value = e.getValue();
            if (!(value instanceof List) || !allMaps(list(value)) || list(value).isEmpty()) {
                throw invalid(key);
            }
            for (Object item : list(value)) {
                Map<String, Object> table = map(item);
                switch (key) {
                    case "patch":
                        ops.add(patch(table, c, ctx));
                        break;
                    case "write":
                        ops.add(write(table, c, ctx));
                        break;
                    case "override":
                        ops.add(override(table, c, ctx));
                        break;
                    case "read":
                        ops.add(read(table, ctx));
                        break;
                    case "when":
                        ops.add(condition(table, c, ctx));
                        break;
                    default:
                        c.raw.add(raw(table, ctx));
                        break;
                }
            }
        }
        c.ops = ops;
        boolean reads = false;
        boolean changes = false;
        boolean code = false;
        boolean core = false;
        for (Model.Op op : ops) {
            reads |= op instanceof Model.Read;
            changes |= op instanceof Model.Patch || op instanceof Model.Write || op instanceof Model.Override;
            code |= op instanceof Model.Patch || op instanceof Model.Override;
            core |= !(op instanceof Model.Condition);
        }
        if (c.run.equals("monitor")) {
            if (changes || !reads) {
                throw invalid("run");
            }
        } else if (c.run.equals("once") && code) {
            // A cheat that runs once is off right after its pass; code works only while a cheat is on.
            throw invalid("run");
        } else if (reads) {
            throw invalid("read");
        }
        if (!core && c.raw.isEmpty()) {
            throw invalid("");
        }
    }

    private static String string(Map<String, Object> t, String key, boolean required) throws Problem {
        if (!t.containsKey(key)) {
            if (required) {
                throw invalid(key);
            }
            return null;
        }
        if (!(t.get(key) instanceof String)) {
            throw invalid(key);
        }
        return (String) t.get(key);
    }

    private static List<String> stringList(Object v, String path) throws Problem {
        if (!(v instanceof List)) {
            throw invalid(path);
        }
        List<String> out = new ArrayList<>();
        for (Object x : list(v)) {
            if (!(x instanceof String)) {
                throw invalid(path);
            }
            out.add((String) x);
        }
        return out;
    }

    private static List<String> others(Object v, String path, Cheat.Draft c, Context ctx) throws Problem {
        if (!(v instanceof List)) {
            throw invalid(path);
        }
        List<String> out = new ArrayList<>();
        for (Object x : list(v)) {
            if (!(x instanceof String) || !ctx.ids.contains(x) || x.equals(c.id)) {
                throw invalid(path);
            }
            out.add((String) x);
        }
        return out;
    }

    private static boolean bool(Map<String, Object> t, String key, boolean fallback) throws Problem {
        if (!t.containsKey(key)) {
            return fallback;
        }
        if (!(t.get(key) instanceof Boolean)) {
            throw invalid(key);
        }
        return (Boolean) t.get(key);
    }

    private static String buttons(Object v, String path, Platform plat) throws Problem {
        if (!(v instanceof String) || ((String) v).isEmpty()) {
            throw invalid(path);
        }
        List<String> names = Py.split((String) v, '+');
        for (String n : names) {
            if (!plat.isValidButton(n)) {
                throw invalid(path);
            }
        }
        if (new HashSet<>(names).size() != names.size()) {
            throw invalid(path);
        }
        return (String) v;
    }

    static boolean isNan(Object v) {
        return v instanceof Double && Double.isNaN((Double) v);
    }

    private static boolean finite(Object v) {
        return !(v instanceof Double) || (!Double.isNaN((Double) v) && !Double.isInfinite((Double) v));
    }

    /** A number of the file as a value of type t, or null if it is not one that fits. */
    static Object valueNumber(String t, Object v) {
        if (t.equals("bool")) {
            return v instanceof Boolean ? v : null;
        }
        if (Values.isIntType(t)) {
            Number n = Values.integer(v);
            return n != null && Values.fits(t, n) ? n : null;
        }
        if (Values.isFloatType(t)) {
            return Values.isNumber(v) && !isNan(v) && Values.fits(t, v) ? v : null;
        }
        return null;
    }

    private static ValueSpec valueSpec(String vid, Object specValue, Context ctx) throws Problem {
        String path = "values." + vid;
        if (!Py.matches(Values.ID, vid) || !(specValue instanceof Map)) {
            throw invalid(path);
        }
        Map<String, Object> spec = map(specValue);
        Object t = spec.get("type");
        if (!Values.VALUE_TYPES.contains(t)) {
            throw invalid(path + ".type");
        }
        String type = (String) t;
        Number[] limits = new Number[3];
        String[] names = {"min", "max", "step"};
        for (int i = 0; i < 3; i++) {
            String key = names[i];
            if (spec.containsKey(key)) {
                if (type.equals("bool")) {
                    throw invalid(path + "." + key);
                }
                Object n = valueNumber(type, spec.get(key));
                if (n == null || (key.equals("step") && Values.lessOrEqual(n, 0L))) {
                    throw invalid(path + "." + key);
                }
                limits[i] = (Number) n;
            }
        }
        Number min = limits[0];
        Number max = limits[1];
        if (min != null && max != null && Values.less(max, min)) {
            throw invalid(path + ".max");
        }
        String unit = null;
        if (spec.containsKey("unit")) {
            if (!(spec.get("unit") instanceof String)) {
                throw invalid(path + ".unit");
            }
            unit = (String) spec.get("unit");
        }
        String display = "dec";
        if (spec.containsKey("display")) {
            Object d = spec.get("display");
            if (!"dec".equals(d) && !"hex".equals(d)) {
                throw invalid(path + ".display");
            }
            display = (String) d;
        }
        Map<Number, String> choices = null;
        if (spec.containsKey("choices")) {
            Object ch = spec.get("choices");
            Map<Number, String> parsed = ch instanceof String ? ctx.choices.get(ch) : choiceList(ch);
            boolean fit = parsed != null;
            if (fit) {
                for (Number k : parsed.keySet()) {
                    fit &= Values.fits(type, k);
                }
            }
            if (!Values.isIntType(type) || !fit) {
                throw invalid(path + ".choices");
            }
            choices = parsed;
        }
        Map<Number, String> flags = null;
        if (spec.containsKey("flags")) {
            Object fl = spec.get("flags");
            Map<Number, String> parsed = fl instanceof Map ? choiceList(fl) : null;
            boolean fit = parsed != null && Values.isIntType(type);
            if (fit) {
                long width = 8L * Values.typeSize(type);
                for (Number b : parsed.keySet()) {
                    fit &= Values.compare(b, 0L) >= 0 && Values.compare(b, width) < 0;
                }
            }
            if (!fit) {
                throw invalid(path + ".flags");
            }
            flags = parsed;
        }
        if (!spec.containsKey("default")) {
            throw invalid(path + ".default");
        }
        Object d = valueNumber(type, spec.get("default"));
        if (d == null || (min != null && Values.less(d, min)) || (max != null && Values.less(max, d))
                || (choices != null && !choices.containsKey(d))) {
            throw invalid(path + ".default");
        }
        return new ValueSpec(vid, type, d, min, max, limits[2], unit, display, choices, flags);
    }

    private static Model.ValueRef ref(Object v, Cheat.Draft c, String path, List<String> kinds) throws Problem {
        Matcher m = Py.match(Values.PLAYER_VALUE, v);
        if (m == null) {
            return null;
        }
        ValueSpec spec = c.values.get(m.group(1));
        if (spec == null || (kinds != null && !kinds.contains(spec.type()))) {
            throw invalid(path);
        }
        return new Model.ValueRef(m.group(1));
    }

    /** A checked location (section 6.2). */
    private static Model.Location location(Object v, String path, Context ctx) throws Problem {
        if (!(v instanceof Map)) {
            throw invalid(path);
        }
        Map<String, Object> t = map(v);
        List<String> bases = new ArrayList<>();
        for (String k : new String[] {"module", "region", "address"}) {
            if (t.containsKey(k)) {
                bases.add(k);
            }
        }
        if (bases.size() != 1) {
            throw invalid(path);
        }
        String base = bases.get(0);
        Model.Location loc = new Model.Location();
        loc.kind = base;
        if (t.containsKey("path")) {
            Object p = t.get("path");
            if (!(p instanceof List) || list(p).isEmpty()) {
                throw invalid(path + ".path");
            }
            List<Object> steps = list(p);
            long[] out = new long[steps.size()];
            for (int i = 0; i < out.length; i++) {
                Number n = Values.integer(steps.get(i));
                if (n == null) {
                    throw invalid(path + ".path");
                }
                out[i] = n.longValue();
            }
            loc.path = out;
        }
        if (base.equals("address")) {
            if (!ctx.platform.addresses()) {
                throw invalid(path + ".address");
            }
            for (String k : new String[] {"offset", "offsets", "find", "section"}) {
                if (t.containsKey(k)) {
                    throw invalid(path + "." + k);
                }
            }
            Number a = Values.integer(t.get("address"));
            if (a == null || Values.compare(a, 0L) < 0) {
                throw invalid(path + ".address");
            }
            loc.address = a.longValue();
            return loc;
        }
        Object name = t.get(base);
        if (!(name instanceof String) || ((String) name).isEmpty()) {
            throw invalid(path + "." + base);
        }
        if (base.equals("module")) {
            loc.module = (String) name;
        } else {
            loc.region = (String) name;
        }
        if (t.containsKey("find")) {
            if (!base.equals("module")) {
                throw invalid(path + ".find");
            }
            if (t.containsKey("offsets")) {
                throw invalid(path + ".offsets");
            }
            int[] pattern = Values.parsePattern(t.get("find"));
            if (pattern == null) {
                throw invalid(path + ".find");
            }
            loc.kind = "pattern";
            loc.find = pattern;
            if (t.containsKey("offset")) {
                if (!Values.isInt(t.get("offset"))) {
                    throw invalid(path + ".offset");
                }
                loc.offset = ((Number) t.get("offset")).longValue();
            }
            if (t.containsKey("section")) {
                if (!SECTIONS.contains(t.get("section"))) {
                    throw invalid(path + ".section");
                }
                loc.section = (String) t.get("section");
            }
            return loc;
        }
        if (t.containsKey("section")) {
            throw invalid(path + ".section");
        }
        if (t.containsKey("offset") == t.containsKey("offsets")) {
            throw invalid(path);
        }
        if (t.containsKey("offset")) {
            Number n = Values.integer(t.get("offset"));
            if (n == null || Values.compare(n, 0L) < 0) {
                throw invalid(path + ".offset");
            }
            loc.offset = n.longValue();
            return loc;
        }
        Object offs = t.get("offsets");
        if (!(offs instanceof Map) || map(offs).isEmpty()) {
            throw invalid(path + ".offsets");
        }
        loc.offsets = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(offs).entrySet()) {
            Number n = Values.integer(e.getValue());
            if (!ctx.builds.containsKey(e.getKey()) || n == null || Values.compare(n, 0L) < 0) {
                throw invalid(path + ".offsets");
            }
            loc.offsets.put(e.getKey(), n.longValue());
        }
        loc.kind = base + "-builds";
        return loc;
    }

    private static Model.Patch patch(Map<String, Object> t, Cheat.Draft c, Context ctx) throws Problem {
        Model.Location at = location(t.get("at"), "patch.at", ctx);
        byte[] expect = Values.parseBytes(t.get("expect"));
        if (expect == null) {
            throw invalid("patch.expect");
        }
        if (t.containsKey("bytes") == t.containsKey("encode")) {
            throw invalid("patch.bytes");
        }
        Model.Patch p = new Model.Patch();
        p.at = at;
        p.expect = expect;
        if (t.containsKey("bytes")) {
            p.bytes = Values.parseBytes(t.get("bytes"));
            if (p.bytes == null) {
                throw invalid("patch.bytes");
            }
            if (p.bytes.length != expect.length) {
                throw invalid("patch.expect");
            }
        } else {
            Object e = t.get("encode");
            if (!(e instanceof Map)) {
                throw invalid("patch.encode");
            }
            Map<String, Object> enc = map(e);
            Object insn = enc.get("insn");
            Object width = enc.get("width");
            Object rd = enc.get("rd");
            Object value = enc.get("value");
            if (!(insn instanceof String) || !Encode.ARM64_IMM_INSNS.containsKey(insn)) {
                throw invalid("patch.encode.insn");
            }
            if (!Values.isInt(width) || !(Values.same(width, 32L) || Values.same(width, 64L))) {
                throw invalid("patch.encode.width");
            }
            if (!Values.isInt(rd) || Values.compare(rd, 0L) < 0 || Values.compare(rd, 31L) > 0) {
                throw invalid("patch.encode.rd");
            }
            int w = ((Number) width).intValue();
            int r = ((Number) rd).intValue();
            Model.ValueRef ref = ref(value, c, "patch.encode.value", Values.NUMBER_TYPES);
            if (ref == null && (!Values.isNumber(value) || Encode.arm64Imm((String) insn, w, r, value) == null)) {
                throw invalid("patch.encode.value");
            }
            Model.Encoding encoding = new Model.Encoding();
            encoding.insn = (String) insn;
            encoding.width = w;
            encoding.rd = r;
            encoding.value = ref != null ? ref : value;
            p.encode = encoding;
            if (expect.length != Encode.ARM64_IMM_INSNS.get(insn)) {
                throw invalid("patch.expect");
            }
        }
        if (t.containsKey("asm")) {
            Object a = t.get("asm");
            if (!(a instanceof Map) || !(get(map(a), "expect", "") instanceof String)
                    || !(get(map(a), "bytes", "") instanceof String)) {
                throw invalid("patch.asm");
            }
            p.asm = map(a);
        }
        return p;
    }

    private static Model.Write write(Map<String, Object> t, Cheat.Draft c, Context ctx) throws Problem {
        Model.Location at = location(t.get("at"), "write.at", ctx);
        Object typ = t.get("type");
        if (!Values.WRITE_TYPES.contains(typ)) {
            throw invalid("write.type");
        }
        if (!t.containsKey("value")) {
            throw invalid("write.value");
        }
        Object v = t.get("value");
        Model.Write w = new Model.Write();
        w.at = at;
        w.type = (String) typ;
        if (typ.equals("bytes")) {
            byte[] value = Values.parseBytes(v);
            if (value == null) {
                throw invalid("write.value");
            }
            byte[] expect = null;
            if (t.containsKey("expect")) {
                expect = Values.parseBytes(t.get("expect"));
                if (expect == null || expect.length != value.length) {
                    throw invalid("write.expect");
                }
            }
            w.value = value;
            w.expect = expect;
            return w;
        }
        Model.ValueRef ref = ref(v, c, "write.value", Values.VALUE_TYPES);
        if (ref != null && Values.isIntType(typ) && Values.isFloatType(c.values.get(ref.name).type())) {
            throw invalid("write.value");
        }
        Object value = ref != null ? ref : valueNumber(w.type, v);
        if (value == null) {
            throw invalid("write.value");
        }
        w.value = value;
        if (t.containsKey("expect")) {
            w.expect = valueNumber(w.type, t.get("expect"));
            if (w.expect == null) {
                throw invalid("write.expect");
            }
        }
        return w;
    }

    private static Model.Override override(Map<String, Object> t, Cheat.Draft c, Context ctx) throws Problem {
        Model.Location fn = location(t.get("function"), "override.function", ctx);
        Object action = t.get("action");
        if (!ACTIONS.contains(action)) {
            throw invalid("override.action");
        }
        Model.Override o = new Model.Override();
        o.function = fn;
        o.action = (String) action;
        if (action.equals("return")) {
            if (!t.containsKey("value")) {
                throw invalid("override.value");
            }
            Model.ValueRef ref = ref(t.get("value"), c, "override.value", WHOLE_VALUE_TYPES);
            Number n = ref == null ? Values.integer(t.get("value")) : null;
            if (ref == null && (n == null || !Values.inLongOrU64(Values.big(n)))) {
                throw invalid("override.value");
            }
            o.value = ref != null ? ref : n;
        } else if (t.containsKey("value")) {
            throw invalid("override.value");
        }
        if (t.containsKey("expect")) {
            o.expect = Values.parseBytes(t.get("expect"));
            if (o.expect == null) {
                throw invalid("override.expect");
            }
        }
        return o;
    }

    private static Model.Read read(Map<String, Object> t, Context ctx) throws Problem {
        Model.Location at = location(t.get("at"), "read.at", ctx);
        Object typ = t.get("type");
        if (!Values.NUMBER_TYPES.contains(typ)) {
            throw invalid("read.type");
        }
        Model.Read r = new Model.Read();
        r.at = at;
        r.type = (String) typ;
        if (t.containsKey("label")) {
            if (!(t.get("label") instanceof String)) {
                throw invalid("read.label");
            }
            r.label = (String) t.get("label");
        }
        return r;
    }

    private static Model.Condition condition(Map<String, Object> t, Cheat.Draft c, Context ctx) throws Problem {
        Model.Location at = location(t.get("at"), "when.at", ctx);
        Object typ = t.get("type");
        if (!Values.NUMBER_TYPES.contains(typ)) {
            throw invalid("when.type");
        }
        Object op = t.get("op");
        if (!OPS.contains(op)) {
            throw invalid("when.op");
        }
        if ((op.equals("&") || op.equals("!&")) && !Values.isIntType(typ)) {
            throw invalid("when.type");
        }
        Model.Condition cond = new Model.Condition();
        cond.at = at;
        cond.type = (String) typ;
        cond.op = (String) op;
        if (op.equals("changed")) {
            if (t.containsKey("value")) {
                throw invalid("when.value");
            }
            return cond;
        }
        if (!t.containsKey("value")) {
            throw invalid("when.value");
        }
        Object v = t.get("value");
        if (op.equals("between")) {
            if (!(v instanceof List) || list(v).size() != 2) {
                throw invalid("when.value");
            }
            Object low = valueNumber(cond.type, list(v).get(0));
            Object high = valueNumber(cond.type, list(v).get(1));
            if (low == null || high == null || Values.less(high, low)) {
                throw invalid("when.value");
            }
            cond.value = new Model.Between(low, high);
            return cond;
        }
        if (v instanceof Map) {
            Map<String, Object> other = map(v);
            Object otherType = other.get("type");
            if (!other.containsKey("at")) {
                throw invalid("when.value.at");
            }
            Model.Location placeAt = location(other.get("at"), "when.value.at", ctx);
            if (!Values.NUMBER_TYPES.contains(otherType) || (bits(cond.op) && !Values.isIntType(otherType))) {
                throw invalid("when.value.type");
            }
            cond.value = new Model.Place(placeAt, (String) otherType);
            return cond;
        }
        Model.ValueRef ref = ref(v, c, "when.value", bits(cond.op) ? WHOLE_VALUE_TYPES : Values.VALUE_TYPES);
        cond.value = ref != null ? ref : valueNumber(cond.type, v);
        if (cond.value == null) {
            throw invalid("when.value");
        }
        return cond;
    }

    /** Whether a condition's operator tests bits, so that what it compares with is a whole number. */
    private static boolean bits(String op) {
        return op.equals("&") || op.equals("!&");
    }

    private static Model.Raw raw(Map<String, Object> t, Context ctx) throws Problem {
        Object syntax = t.get("syntax");
        Object build = t.get("build");
        Object code = t.get("code");
        if (!(syntax instanceof String) || ((String) syntax).isEmpty()) {
            throw invalid("raw.syntax");
        }
        if (!(build instanceof String) || !ctx.builds.containsKey(build)) {
            throw invalid("raw.build");
        }
        if (!(code instanceof String) || Py.strip((String) code).isEmpty()) {
            throw invalid("raw.code");
        }
        if (syntax.equals("atmosphere")) {
            for (String token : Py.splitSpace((String) code)) {
                if (token.codePointCount(0, token.length()) != 8 || !isHex(token)) {
                    throw invalid("raw.code");
                }
            }
        }
        return new Model.Raw((String) syntax, (String) build, (String) code);
    }

    private static boolean isHex(String token) {
        for (int i = 0; i < token.length(); i++) {
            if ("0123456789abcdefABCDEF".indexOf(token.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
