package io.github.davdxpx.mistel.mncf;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Atmosphère cheat files to .mncf, and .mncf back to Atmosphère for one build (section
 * 15).
 *
 * <p>Every cheat keeps its code as a raw/1 entry. Where the code is plain writes, it also gets
 * core operations: a write into the main module's code becomes a patch, one into data or a
 * region a write, a pointer chain a path, a button block hold, a memory condition when, a movz
 * or fmov a value the player sets (arm64-imm/1). With the code module, patches get expect from
 * it and a pattern instead of a fixed offset wherever the bytes mean the same at another place.
 * Several files for builds of the same game become one file with all their builds. This
 * library has no disassembler, so it writes no asm fields.
 */
public final class Converter {

    // The patterns have ASCII semantics, as Python's re.A gives them: \b, \w, \W and \s are
    // written out, and so is matching ASCII letters in either case, because some regular
    // expression engines fold the case of all of Unicode under CASE_INSENSITIVE.
    private static final String WORD = "[A-Za-z0-9_]";
    private static final String NOT_WORD = "[^A-Za-z0-9_]";
    private static final String BOUND = "(?:(?<=" + WORD + ")(?!" + WORD + ")|(?<!" + WORD + ")(?=" + WORD + "))";
    private static final String SPACE_CHARS = " \\t\\n\\r\\f\\x0B";
    private static final String SPACE = "[" + SPACE_CHARS + "]";
    private static final String END = "\\z";

    private static final Pattern RESTORE_NAME = Pattern.compile(BOUND + "(" + caseless("restore|restores|original|vanilla")
            + ")" + BOUND + "|" + BOUND + caseless("off") + NOT_WORD + "*" + END);
    private static final Pattern CREDIT_NAME = Pattern.compile(caseless("credit|made by|created by|written by"
            + "|cheats? by|codes? by|patch(?:es)? by|thanks|www\\.|https?:|\\.com") + BOUND + "|" + caseless("\\.org") + BOUND + "|"
            + caseless("\\.net") + BOUND + "|" + caseless("discord|patreon") + "|" + BOUND + caseless("TID") + SPACE
            + "*=|" + BOUND + caseless("BID") + SPACE + "*=");
    private static final Pattern LINK = Pattern.compile(caseless("www\\.|https?:|\\.com") + BOUND + "|"
            + caseless("\\.org") + BOUND + "|" + caseless("\\.net") + BOUND);
    private static final Pattern AUTHOR = Pattern.compile(BOUND + caseless("by") + SPACE + "+([^\\n]+)|" + BOUND
            + caseless("credits?") + SPACE + "*[:=]" + SPACE + "*([^\\n]+)");
    private static final String DECORATION_CHARS = "[" + SPACE_CHARS + "\\-=*~#_.:+<>|]";
    private static final Pattern DECORATION = Pattern.compile("^" + DECORATION_CHARS + "+|" + DECORATION_CHARS + "+" + END);
    private static final Pattern NAME_END = Pattern.compile("[,!;|]");
    private static final Pattern NAME_SPLIT = Pattern.compile(SPACE + "+&" + SPACE + "+|" + SPACE + "+and" + SPACE + "+");
    private static final Pattern PLATFORM = Pattern.compile("([^\\n]+?)" + SPACE + "*(?:" + SPACE + "(?:"
            + caseless("on|at|from") + ")" + SPACE + "|@|\\()" + SPACE + "*([^\\n]+?)\\)?");
    private static final Pattern SITE = Pattern.compile(
            "(?:https?://)?(?:www\\.)?([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})");
    private static final long[] GROUP_END = {0x20000001L};
    private static final Pattern EDIZON = Pattern.compile("^--" + SPACE + "*" + caseless("section") + SPACE + "*("
            + caseless("start|end") + ")" + SPACE + "*:" + SPACE + "*([^\\n]*?)" + SPACE + "*--" + END);
    private static final Pattern NOT_A_NAME = Pattern.compile("(?:" + caseless("all credited|all credits?|everyone"
            + "|everybody|unknown|anonymous|various|(the )?community|n/?a") + ")");

    private Converter() {
    }

    /** A pattern in which each ASCII letter matches in either case; an escaped character stays as it is. */
    private static String caseless(String pattern) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                b.append(c).append(pattern.charAt(++i));
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                b.append('[').append(Character.toLowerCase(c)).append(Character.toUpperCase(c)).append(']');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** What to write into a converted file besides the cheats. Immutable. */
    public static final class Options {
        private final String gameId;
        private String platform = "switch";
        private String gameName;
        private String name;
        private String origin;
        private List<String> authors;
        private boolean values = true;

        private Options(String gameId) {
            this.gameId = gameId;
        }

        private Options copy() {
            Options o = new Options(gameId);
            o.platform = platform;
            o.gameName = gameName;
            o.name = name;
            o.origin = origin;
            o.authors = authors;
            o.values = values;
            return o;
        }

        /** Starts options for a game: its ID on its platform. */
        public static Options forGame(String gameId) {
            return new Options(gameId);
        }

        /** Returns options with another platform; the default is "switch". */
        public Options platform(String p) {
            Options o = copy();
            o.platform = p;
            return o;
        }

        /** Returns options with the game's name. */
        public Options gameName(String n) {
            Options o = copy();
            o.gameName = n;
            return o;
        }

        /** Returns options with the file's name; the default is "&lt;game name&gt; cheats". */
        public Options name(String n) {
            Options o = copy();
            o.name = n;
            return o;
        }

        /** Returns options with where the files came from, such as a ZIP or folder name. */
        public Options origin(String n) {
            Options o = copy();
            o.origin = n;
            return o;
        }

        /** Returns options with the authors to name when the files credit nobody. */
        public Options authors(List<String> names) {
            Options o = copy();
            o.authors = names == null ? null : Collections.unmodifiableList(new ArrayList<>(names));
            return o;
        }

        /** Returns options that turn movz and fmov patches into values the player sets, or not. The default is on. */
        public Options values(boolean on) {
            Options o = copy();
            o.values = on;
            return o;
        }
    }

    // Mutable state while converting

    private static final class Report {
        final List<Rep> cheats = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
        final List<String> credits = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> notes = new ArrayList<>();          // lines of the sources that are neither names nor code
        final List<String> unreadable = new ArrayList<>();     // names of entries whose code could not be read
    }

    private static final class Rep {
        String id;
        final String name;
        final String build;
        String outcome;
        String why = "";
        int patterns;
        int fixed;
        int values;
        List<String> notes = new ArrayList<>();

        Rep(String id, String name, String build, String outcome) {
            this.id = id;
            this.name = name;
            this.build = build;
            this.outcome = outcome;
        }

        CheatReport done() {
            return new CheatReport(id, name, build, outcome, why, patterns, fixed, values, notes);
        }
    }

    private static final class Item {
        final String name;
        final boolean master;
        final String group;
        final Atmosphere.Entry entry;
        Atmosphere.Derived derived;
        Atmosphere.Unclear unclear;

        Item(String name, boolean master, String group, Atmosphere.Entry entry) {
            this.name = name;
            this.master = master;
            this.group = group;
            this.entry = entry;
        }
    }

    private static final class Op {
        final String kind;
        final Map<String, Object> table;

        Op(String kind, Map<String, Object> table) {
            this.kind = kind;
            this.table = table;
        }
    }

    /** One converted cheat of one file, before cheats of several builds are merged. */
    private static final class CheatState {
        final Item item;
        final String key;
        final Rep rep;
        String hold;
        String press;            // buttons of a block that patches without an else branch: run = "action"
        boolean needsMaster;
        boolean defaultOn;       // a patch of a patch file that is on by default
        List<Op> ops = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, Object> raw;     // null for a patch of a patch file

        CheatState(Item item, String key, Rep rep) {
            this.item = item;
            this.key = key;
            this.rep = rep;
            if (item.entry == null) {
                raw = null;
                return;
            }
            raw = new LinkedHashMap<>();
            raw.put("syntax", "atmosphere");
            raw.put("build", key);
            raw.put("code", item.entry.code());
        }
    }

    private static Map<String, Object> table(Object... keysAndValues) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            t.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return t;
    }

    /** Returns an id from a name: lower-case letters, digits and hyphens, not yet in taken, which it is added to. */
    static String slug(String name, Set<String> taken) {
        String base = stripChars(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"), "-");
        if (base.isEmpty()) {
            base = "cheat";
        }
        if (!(base.charAt(0) >= 'a' && base.charAt(0) <= 'z')) {
            base = "cheat-" + base;
        }
        base = base.substring(0, Math.min(48, base.length()));
        while (base.endsWith("-")) {
            base = base.substring(0, base.length() - 1);
        }
        String out = base;
        int n = 2;
        while (taken.contains(out)) {
            out = base + "-" + n;
            n++;
        }
        taken.add(out);
        return out;
    }

    /** Python's str.strip(chars): the text without those characters at both ends. */
    private static String stripChars(String s, String chars) {
        int a = 0;
        int b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    /**
     * Returns the people that credit lines name: what follows "by" or "credits:", up to a comma
     * or the end. A person named with the platform they posted on ("Ann on Some Forum", "Ann @
     * Some Forum") is written "Ann (Some Forum)". A credit such as "All Credited" or "everyone"
     * names no person.
     */
    public static List<String> authorsOf(List<String> credits) {
        List<String> names = new ArrayList<>();
        for (String line : credits) {
            Matcher m = AUTHOR.matcher(line);
            if (!m.find()) {
                continue;
            }
            String text = m.group(1) != null ? m.group(1) : m.group(2);
            Matcher cut = NAME_END.matcher(text);
            text = Py.strip(cut.find() ? text.substring(0, cut.start()) : text);
            for (String name : NAME_SPLIT.split(text, -1)) {
                name = stripChars(name, " .");
                Matcher on = PLATFORM.matcher(name);
                if (on.matches()) {
                    name = Py.strip(on.group(1)) + " (" + stripChars(on.group(2), " .()") + ")";
                }
                if (!name.isEmpty() && !LINK.matcher(name).lookingAt() && !NOT_A_NAME.matcher(name).matches()
                        && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Returns the website the credit lines name, as a lower-case domain without www, or null. */
    public static String siteOf(List<String> credits) {
        for (String line : credits) {
            Matcher m = SITE.matcher(line);
            if (m.find()) {
                return m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    // One file

    private static List<Item> read(Source src, Report report, String key) throws AtmosphereException {
        List<Item> items = new ArrayList<>();
        String group = null;
        List<Atmosphere.Note> notes = new ArrayList<>();
        for (Atmosphere.Entry e : Atmosphere.parse(src.text(), notes)) {
            if (!e.broken().isEmpty()) {
                report.unreadable.add(e.name());
                Rep rep = new Rep("", e.name(), key, CheatReport.UNREADABLE);
                rep.why = "lines that mix code with other text: " + String.join("; ", e.broken());
                report.cheats.add(rep);
                continue;
            }
            long[] words = e.wordsShared();
            Matcher section = words.length == 0 || Atmosphere.isNoop(e) ? EDIZON.matcher(e.name()) : null;
            if (section != null && section.find()) {
                if (section.group(1).toLowerCase(Locale.ROOT).equals("start")) {
                    String cleaned = Py.strip(DECORATION.matcher(section.group(2)).replaceAll("").replace('/', '-'));
                    group = cleaned.isEmpty() ? null : cleaned;
                    if (group != null && !report.groups.contains(group)) {
                        report.groups.add(group);
                    }
                } else {
                    group = null;
                }
                continue;
            }
            if (words.length == 0 || Atmosphere.isNoop(e)) {
                if (CREDIT_NAME.matcher(e.name()).find()) {
                    if (!report.credits.contains(e.name())) {
                        report.credits.add(e.name());
                    }
                } else if (Arrays.equals(words, GROUP_END)) {
                    group = null;
                } else if (!e.master()) {
                    String cleaned = Py.strip(DECORATION.matcher(e.name()).replaceAll("").replace('/', '-'));
                    group = cleaned.isEmpty() ? null : cleaned;
                    if (group != null && !report.groups.contains(group)) {
                        report.groups.add(group);
                    }
                }
                continue;
            }
            items.add(new Item(e.name(), e.master(), group, e));
        }
        for (Atmosphere.Note n : notes) {
            if (!report.notes.contains(n.text())) {
                report.notes.add(n.text());
            }
        }
        List<Item> mastersFirst = new ArrayList<>(items);
        Collections.sort(mastersFirst, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Boolean.compare(!a.master, !b.master);
            }
        });
        Set<Long> written = new HashSet<>();
        for (Item it : mastersFirst) {
            Atmosphere.Derived d;
            try {
                d = Atmosphere.derive(it.entry);
            } catch (Atmosphere.Unclear u) {
                it.unclear = u;
                written.addAll(Atmosphere.registersWritten(it.entry));
                continue;
            }
            Set<Long> both = new HashSet<>(d.reads);
            both.retainAll(written);
            if (!both.isEmpty()) {
                it.unclear = new Atmosphere.Unclear("it reads a register that an earlier cheat sets");
            } else {
                it.derived = d;
            }
            written.addAll(d.writes);
        }
        return items;
    }

    private static byte[] slice(byte[] image, long offset, long width) {
        if (Long.compareUnsigned(offset, image.length) >= 0) {
            return new byte[0];
        }
        long end = Math.min(image.length, offset + width);
        return Arrays.copyOfRange(image, (int) offset, (int) end);
    }

    /** The original bytes at a place of the main module: from the module, else from the file's own restore code. */
    private static byte[] original(CodeModule module, Map<List<Long>, byte[]> originals, long offset, int width) {
        if (module != null && !module.excluded(offset, width)) {
            return slice(module.image(), offset, width);
        }
        byte[] out = new byte[width];
        for (int i = 0; i < width; i++) {
            boolean found = false;
            for (Map.Entry<List<Long>, byte[]> e : originals.entrySet()) {
                long off = e.getKey().get(0);
                long w = e.getKey().get(1);
                if (off <= offset + i && offset + i < off + w) {
                    out[i] = e.getValue()[(int) (offset + i - off)];
                    found = true;
                    break;
                }
            }
            if (!found) {
                return null;
            }
        }
        return out;
    }

    private static boolean inCode(CodeModule module, long offset, long width) {
        String a = module.sectionOf(offset);
        String b = module.sectionOf(offset + width - 1);
        return ("code".equals(a) || "rodata".equals(a)) && ("code".equals(b) || "rodata".equals(b));
    }

    private static boolean isMainPlain(Atmosphere.Store s) {
        return s.place.region.equals("main") && !s.place.hasPath();
    }

    /** The entries that only put back original code, and the original bytes the file's code gives. */
    private static Set<Item> restores(List<Item> items, CodeModule module, Map<List<Long>, byte[]> originals) {
        Set<Item> restore = Collections.newSetFromMap(new IdentityHashMap<Item, Boolean>());
        for (Item it : items) {
            Atmosphere.Derived d = it.derived;
            if (d == null || d.stores.isEmpty()) {
                continue;
            }
            if (d.released != null) {
                for (Atmosphere.Store s : d.released) {
                    if (isMainPlain(s)) {
                        List<Long> k = Arrays.asList(s.place.offset, (long) s.width);
                        if (!originals.containsKey(k)) {
                            originals.put(k, s.data());
                        }
                    }
                }
            }
            boolean other = d.keys != 0 || d.condition != null;
            for (Atmosphere.Store s : d.stores) {
                other |= !isMainPlain(s);
            }
            if (other) {
                continue;
            }
            boolean anyExcluded = false;
            if (module != null) {
                for (Atmosphere.Store s : d.stores) {
                    anyExcluded |= module.excluded(s.place.offset, s.width);
                }
            }
            if (module != null && !anyExcluded) {
                boolean all = true;
                for (Atmosphere.Store s : d.stores) {
                    all &= inCode(module, s.place.offset, s.width)
                            && Arrays.equals(slice(module.image(), s.place.offset, s.width), s.data());
                }
                if (all) {
                    restore.add(it);
                }
                continue;
            }
            if (RESTORE_NAME.matcher(it.name).find()) {
                Set<Long> others = new HashSet<>();
                for (Item o : items) {
                    if (o != it) {
                        others.addAll(Atmosphere.mainOffsets(o.entry));
                    }
                }
                boolean all = true;
                for (Atmosphere.Store s : d.stores) {
                    all &= others.contains(s.place.offset);
                }
                if (all) {
                    restore.add(it);
                    for (Atmosphere.Store s : d.stores) {
                        originals.put(Arrays.asList(s.place.offset, (long) s.width), s.data());
                    }
                }
            }
        }
        return restore;
    }

    private static Map<String, Object> location(Atmosphere.Place place, String key) {
        Map<String, Object> offsets = table(key, Values.unsigned(place.offset));
        Map<String, Object> loc = place.region.equals("main") ? table("module", "main", "offsets", offsets)
                : table("region", place.region, "offsets", offsets);
        if (place.hasPath()) {
            List<Object> path = new ArrayList<>();
            for (long p : place.path) {
                path.add(p);
            }
            loc.put("path", path);
        }
        return loc;
    }

    private static List<Object[]> mergeRuns(List<Atmosphere.Store> stores) {
        List<Object[]> runs = new ArrayList<>();
        for (Atmosphere.Store s : stores) {
            Object[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && (Long) last[0] + ((ByteArrayOutputStream) last[1]).size() == s.place.offset) {
                byte[] data = s.data();
                ((ByteArrayOutputStream) last[1]).write(data, 0, data.length);
            } else {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] data = s.data();
                b.write(data, 0, data.length);
                runs.add(new Object[] {s.place.offset, b});
            }
        }
        List<Object[]> out = new ArrayList<>();
        for (Object[] r : runs) {
            out.add(new Object[] {r[0], ((ByteArrayOutputStream) r[1]).toByteArray()});
        }
        return out;
    }

    private static double fmovValue(int imm8) {
        double sign = (imm8 & 0x80) != 0 ? -1.0 : 1.0;
        int cd = (imm8 >> 4) & 3;
        int e = (imm8 & 0x40) == 0 ? cd + 1 : cd - 3;
        return sign * (16 + (imm8 & 15)) / 16 * Math.pow(2.0, e);
    }

    private static long le32(byte[] data, int at) {
        return (data[at] & 0xFFL) | (data[at + 1] & 0xFFL) << 8 | (data[at + 2] & 0xFFL) << 16
                | (data[at + 3] & 0xFFL) << 24;
    }

    /**
     * The encode table and the value's type for new bytes that are one movz, a movz and a movk,
     * or one fmov with an immediate, else null.
     */
    private static Object[] encodeOf(byte[] data) {
        if (data.length != 4 && data.length != 8) {
            return null;
        }
        long w0 = le32(data, 0);
        if (data.length == 4 && (w0 & 0x7FE00000L) == 0x52800000L) {
            return new Object[] {table("insn", "movz", "width", (w0 >> 31) != 0 ? 64L : 32L, "rd", w0 & 31,
                "value", (w0 >> 5) & 0xFFFF), "u16"};
        }
        if (data.length == 8) {
            long w1 = le32(data, 4);
            if ((w0 & 0x7FE00000L) == 0x52800000L && (w1 & 0x7FE00000L) == 0x72A00000L && w0 >> 31 == w1 >> 31
                    && (w0 & 31) == (w1 & 31)) {
                long lo = (w0 >> 5) & 0xFFFF;
                long hi = (w1 >> 5) & 0xFFFF;
                return new Object[] {table("insn", "movz-movk", "width", (w0 >> 31) != 0 ? 64L : 32L, "rd", w0 & 31,
                    "value", hi << 16 | lo), "u32"};
            }
            return null;
        }
        if ((w0 & 0xFF201FE0L) == 0x1E201000L && ((w0 >> 22) & 3) <= 1) {
            boolean isDouble = ((w0 >> 22) & 3) == 1;
            return new Object[] {table("insn", "fmov", "width", isDouble ? 64L : 32L, "rd", w0 & 31,
                "value", fmovValue((int) ((w0 >> 13) & 0xFF))), isDouble ? "f64" : "f32"};
        }
        return null;
    }

    /** An unsigned 64-bit number as a BigInteger. */
    private static BigInteger unsigned(long bits) {
        BigInteger v = BigInteger.valueOf(bits & Long.MAX_VALUE);
        return bits < 0 ? v.setBit(63) : v;
    }

    private static List<Atmosphere.Store> storesAndReleased(Atmosphere.Derived d) {
        List<Atmosphere.Store> out = new ArrayList<>(d.stores);
        if (d.released != null) {
            out.addAll(d.released);
        }
        return out;
    }

    /**
     * The main module byte ranges a master entry writes, as start and end, or null when it is not
     * known what it writes.
     */
    private static List<BigInteger[]> masterRanges(Item master) {
        List<BigInteger[]> out = new ArrayList<>();
        if (master == null) {
            return out;
        }
        if (master.unclear != null) {
            return null;
        }
        for (Atmosphere.Store s : storesAndReleased(master.derived)) {
            if (s.place.region.equals("main") && !s.place.hasPath()) {
                BigInteger lo = unsigned(s.place.offset);
                out.add(new BigInteger[] {lo, lo.add(BigInteger.valueOf(s.width))});
            }
        }
        return out;
    }

    private static boolean inside(List<BigInteger[]> ranges, BigInteger offset, long width) {
        BigInteger end = offset.add(BigInteger.valueOf(width));
        for (BigInteger[] r : ranges) {
            if (r[0].compareTo(end) < 0 && offset.compareTo(r[1]) < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a cheat depends on the master entry of its file: it reads registers the master
     * sets, or it touches, reads through or branches into bytes the master writes. A cheat that
     * stays raw code, or a master whose effect is not known, counts as dependent, as in
     * Atmosphère, where the master always runs first.
     */
    private static boolean depends(Item it, CheatState c, Item master, List<BigInteger[]> ranges) {
        if (master == null) {
            return false;
        }
        if (ranges == null || it.unclear != null || c.ops.isEmpty()) {
            return true;
        }
        Atmosphere.Derived d = it.derived;
        for (Long r : d.reads) {
            if (master.derived.writes.contains(r)) {
                return true;
            }
        }
        List<Atmosphere.Place> places = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        for (Atmosphere.Store s : storesAndReleased(d)) {
            places.add(s.place);
            widths.add(s.width);
        }
        if (d.condition != null) {
            places.add(d.condition.place);
            widths.add(d.condition.width);
        }
        for (int k = 0; k < places.size(); k++) {
            Atmosphere.Place place = places.get(k);
            if (!place.region.equals("main")) {
                continue;
            }
            if (inside(ranges, unsigned(place.offset), place.hasPath() ? 8 : widths.get(k))) {
                return true;
            }
        }
        for (Atmosphere.Store s : d.stores) {
            if (s.place.region.equals("main") && !s.place.hasPath() && (s.place.offset & 3) == 0) {
                byte[] data = s.data();
                for (int i = 0; i < data.length - data.length % 4; i += 4) {
                    long pc = s.place.offset + i;
                    Long target = Arm64.pcRelativeTarget(le32(data, i), pc);
                    if (target != null && inside(ranges, unsigned(s.place.offset).add(BigInteger.valueOf(i))
                            .add(BigInteger.valueOf(target - pc)), 4)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<CheatState> convertOne(Source src, String key, Report report, boolean values)
            throws AtmosphereException {
        CodeModule module = src.module();
        List<Item> items = read(src, report, key);
        Map<List<Long>, byte[]> originals = new LinkedHashMap<>();
        Set<Item> restore = restores(items, module, originals);
        Item master = null;
        for (Item it : items) {
            if (it.master && !restore.contains(it)) {
                master = it;
                break;
            }
        }
        List<BigInteger[]> ranges = masterRanges(master);
        List<CheatState> out = new ArrayList<>();
        for (Item it : items) {
            Rep rep = new Rep("", it.name, key, CheatReport.RAW);
            if (restore.contains(it)) {
                rep.outcome = CheatReport.RESTORE;
                rep.why = "it only puts back the original code";
                report.cheats.add(rep);
                continue;
            }
            CheatState c = new CheatState(it, key, rep);
            if (it.unclear != null) {
                rep.why = it.unclear.why;
            } else {
                try {
                    core(c, it.derived, module, originals, values);
                    rep.outcome = rep.patterns > 0 && rep.fixed == 0 ? CheatReport.PATTERN : CheatReport.BUILD;
                } catch (Atmosphere.Unclear u) {
                    c.ops = new ArrayList<>();
                    c.values = new LinkedHashMap<>();
                    c.hold = null;
                    c.press = null;
                    rep.why = u.why;
                    rep.outcome = CheatReport.RAW;
                    rep.patterns = 0;
                    rep.fixed = 0;
                    rep.values = 0;
                    rep.notes = new ArrayList<>();
                }
            }
            if (!it.master) {
                c.needsMaster = depends(it, c, master, ranges);
            }
            out.add(c);
            report.cheats.add(rep);
        }
        return out;
    }

    private static String uType(int width) {
        return "u" + (8 * width);
    }

    private static void core(CheatState c, Atmosphere.Derived d, CodeModule module, Map<List<Long>, byte[]> originals,
            boolean values) throws Atmosphere.Unclear {
        Rep rep = c.rep;
        String key = c.key;
        String buttons = null;
        if (d.keys != 0) {
            buttons = Atmosphere.keysToButtons(d.keys);
            if (buttons == null) {
                throw new Atmosphere.Unclear("a button mask with bits that have no name");
            }
        }
        List<Atmosphere.Store> code = new ArrayList<>();
        List<Op> ops = new ArrayList<>();
        for (Atmosphere.Store s : d.stores) {
            if (isMainPlain(s)) {
                if (module == null) {
                    if (original(null, originals, s.place.offset, s.width) == null) {
                        throw new Atmosphere.Unclear(
                                "a write into the main module: without the module, code and data look alike");
                    }
                    code.add(s);
                } else if (inCode(module, s.place.offset, s.width)) {
                    code.add(s);
                } else if (module.sectionOf(s.place.offset) == null) {
                    throw new Atmosphere.Unclear("a write outside the main module");
                } else {
                    ops.add(new Op("write", table("at", location(s.place, key), "type", uType(s.width),
                            "value", Values.unsigned(s.value))));
                    rep.fixed++;
                }
            } else {
                ops.add(new Op("write", table("at", location(s.place, key), "type", uType(s.width),
                        "value", Values.unsigned(s.value))));
                rep.fixed++;
            }
        }
        if (d.released != null) {
            for (Atmosphere.Store s : d.released) {
                if (!isMainPlain(s)) {
                    throw new Atmosphere.Unclear("it writes data when the buttons are released");
                }
                byte[] o = original(module, originals, s.place.offset, s.width);
                if (o == null || !Arrays.equals(o, s.data())) {
                    throw new Atmosphere.Unclear("the release branch writes other bytes than the original code");
                }
            }
        }
        List<Map<String, Object>> patches = new ArrayList<>();
        for (Object[] run : mergeRuns(code)) {
            long off = (Long) run[0];
            byte[] data = (byte[]) run[1];
            byte[] expect = original(module, originals, off, data.length);
            if (expect == null) {
                throw new Atmosphere.Unclear("no original bytes for every byte it writes");
            }
            if (Arrays.equals(expect, data)) {
                rep.notes.add(String.format("main+0x%X already holds those bytes; the patch stays, as the source writes them",
                        off));
            }
            patches.add(patch(c, off, data, expect, module, values, "main"));
        }
        if (patches.isEmpty() && ops.isEmpty()) {
            throw new Atmosphere.Unclear("it writes only what is already there");
        }
        if (buttons != null) {
            if (d.released == null && !patches.isEmpty()) {
                // Atmosphère patches at the press and leaves the patch when the buttons are let go: an action.
                if (!ops.isEmpty()) {
                    throw new Atmosphere.Unclear("a button block without an else branch that patches code and writes data");
                }
                c.press = buttons;
            } else {
                c.hold = buttons;
            }
        }
        List<Op> all = new ArrayList<>();
        for (Map<String, Object> p : patches) {
            all.add(new Op("patch", p));
        }
        all.addAll(ops);
        c.ops = all;
        if (d.condition != null) {
            Atmosphere.Cond cond = d.condition;
            if (cond.place.region.equals("main") && module != null && inCode(module, cond.place.offset, cond.width)) {
                throw new Atmosphere.Unclear("a condition on the code itself");
            }
            c.ops.add(0, new Op("when", table("at", location(cond.place, key), "type", uType(cond.width),
                    "op", cond.op, "value", Values.unsigned(cond.value))));
        }
    }

    private static Map<String, Object> patch(CheatState c, long off, byte[] data, byte[] expect, CodeModule module,
            boolean values, String name) {
        Rep rep = c.rep;
        Map<String, Object> loc = null;
        if (module != null) {
            if (module.excluded(off, data.length)) {
                rep.notes.add(String.format("%s+0x%X: the module's bytes there are not its own, so no pattern", name, off));
            } else if (Arm64.relativeOutside(data, off, data.length)) {
                rep.notes.add(String.format("%s+0x%X: the new code branches or refers outside itself, so it fits this "
                        + "build only", name, off));
            } else if (module.textSize() != null && off >= module.textSize() && "code".equals(module.sectionOf(off))) {
                rep.notes.add(String.format("%s+0x%X: code written after the end of the code, kept for this build", name, off));
            } else {
                Patterns.Found p = Patterns.make(module, off, Math.min(4, data.length));
                if (p == null) {
                    rep.notes.add(String.format("%s+0x%X: no unique pattern of up to 64 bytes", name, off));
                } else {
                    loc = p.location(name);
                    if (!Arm64.movingBytes(module.image(), (int) off, (int) (off + expect.length)).isEmpty()) {
                        rep.notes.add(String.format("%s+0x%X: the original code refers to another place, so `expect` "
                                + "fits this build only", name, off));
                    }
                }
            }
        }
        if (loc == null) {
            loc = table("module", name, "offsets", table(c.key, Values.unsigned(off)));
            rep.fixed++;
        } else {
            rep.patterns++;
        }
        Map<String, Object> t = table("at", loc, "expect", Values.hexBytes(expect));
        Object[] encoded = values ? encodeOf(data) : null;
        if (encoded != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> enc = (Map<String, Object>) encoded[0];
            String vid = !c.values.containsKey("value") ? "value" : "value-" + (c.values.size() + 1);
            c.values.put(vid, table("type", encoded[1], "default", enc.get("value")));
            Map<String, Object> e = new LinkedHashMap<>(enc);
            e.put("value", "$" + vid);
            t.put("encode", e);
            rep.values++;
        } else {
            t.put("bytes", Values.hexBytes(data));
        }
        return t;
    }

    // Several files

    /** The operations without the offsets of their build: what must agree for two builds' cheats to be one. */
    private static List<String> shape(List<Op> ops) {
        List<String> out = new ArrayList<>();
        for (Op op : ops) {
            Map<String, Object> t = new LinkedHashMap<>(op.table);
            @SuppressWarnings("unchecked")
            Map<String, Object> at = t.containsKey("at") ? new LinkedHashMap<>((Map<String, Object>) t.get("at"))
                    : new LinkedHashMap<String, Object>();
            if (at.containsKey("offsets")) {
                at.put("offsets", null);
            }
            t.put("at", at);
            t.remove("asm");
            List<String> keys = new ArrayList<>(t.keySet());
            Collections.sort(keys);
            List<Object> items = new ArrayList<>();
            for (String k : keys) {
                items.add(Arrays.asList(k, t.get(k)));
            }
            out.add(op.kind + " " + Py.repr(items));
        }
        return out;
    }

    private static boolean mergeable(CheatState a, CheatState b) {
        return java.util.Objects.equals(a.hold, b.hold) && java.util.Objects.equals(a.press, b.press)
                && a.needsMaster == b.needsMaster && sameValues(a.values, b.values) && shape(a.ops).equals(shape(b.ops));
    }

    /** Python's == for the value tables: numbers compare by value. */
    private static boolean sameValues(Object a, Object b) {
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> x = (Map<?, ?>) a;
            Map<?, ?> y = (Map<?, ?>) b;
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (Object k : x.keySet()) {
                if (!sameValues(x.get(k), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number && b instanceof Number) {
            return Values.same(a, b);
        }
        return java.util.Objects.equals(a, b);
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(CheatState a, CheatState b) {
        for (int i = 0; i < Math.min(a.ops.size(), b.ops.size()); i++) {
            Object at = a.ops.get(i).table.get("at");
            if (at instanceof Map && ((Map<String, Object>) at).containsKey("offsets")) {
                Map<String, Object> offsets = (Map<String, Object>) ((Map<String, Object>) at).get("offsets");
                Map<String, Object> other = (Map<String, Object>) ((Map<String, Object>) b.ops.get(i).table.get("at"))
                        .get("offsets");
                offsets.putAll(other);
            }
        }
    }

    /** Returns a conversion of one Atmosphère cheat file. */
    public static Conversion fromAtmosphere(Source source, Options options) throws ConvertException {
        return fromAtmosphere(Collections.singletonList(source), options);
    }

    /**
     * Returns a conversion of one or more Atmosphère cheat files, each for one build of the same
     * game.
     *
     * <p>Two files with the same version name are named by their build IDs.
     *
     * @throws ConvertException with the code "atmosphere" when a file cannot be read, or none
     *     holds a cheat with code ({@link AtmosphereException}), or "source" when two files are
     *     for the same build ID or name the same build
     */
    public static Conversion fromAtmosphere(List<Source> sources, Options options) throws ConvertException {
        Report report = new Report();
        Map<String, Object> builds = new LinkedHashMap<>();
        List<List<CheatState>> perSource = new ArrayList<>();
        List<String> buildIds = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        for (Source src : sources) {
            buildIds.add(src.buildId().toUpperCase(Locale.ROOT));
            if (nonEmpty(src.version())) {
                versions.add(src.version());
            }
        }
        if (new HashSet<>(buildIds).size() != buildIds.size()) {
            throw new ConvertException("two files are for the same build ID");
        }
        for (Source src : sources) {
            String bid = src.buildId().toUpperCase(Locale.ROOT);
            boolean shared = src.version() != null && Collections.frequency(versions, src.version()) > 1;
            String named = shared ? bid : src.version();
            String key = nonEmpty(src.buildKey()) ? src.buildKey() : nonEmpty(named) ? named : bid;
            if (builds.containsKey(key)) {
                throw new ConvertException("two files name the build " + Py.repr(key));
            }
            CodeModule m = src.module();
            String shortId = m == null ? null : m.shortId();
            if (shortId != null && !(shortId.startsWith(bid.substring(0, Math.min(16, bid.length())))
                    || bid.startsWith(shortId))) {
                report.warnings.add("the module of build " + key + " is " + shortId + ", its cheat file " + bid);
            }
            Map<String, Object> build = new LinkedHashMap<>();
            if (nonEmpty(src.version()) && !src.version().equals(key)) {
                build.put("version", src.version());
            }
            if (shared) {
                report.warnings.add("builds " + bid + " and another one are both version " + src.version()
                        + ": named by their build IDs");
            }
            build.put("modules", table("main", bid));
            builds.put(key, build);
            perSource.add(convertOne(src, key, report, options.values));
        }

        // Cheats of several builds with the same name become one where they do the same; masters
        // first, so that a cheat is only one cheat across builds whose masters are one cheat too.
        List<List<CheatState>> merged = new ArrayList<>();
        Map<String, List<CheatState>> masterOf = new HashMap<>();
        for (boolean wantMaster : new boolean[] {true, false}) {
            Map<String, List<List<CheatState>>> byName = new HashMap<>();
            for (List<CheatState> cheats : perSource) {
                for (CheatState c : cheats) {
                    if (c.item.master != wantMaster) {
                        continue;
                    }
                    List<CheatState> target = null;
                    List<List<CheatState>> named = byName.get(c.item.name);
                    for (List<CheatState> g : named == null ? Collections.<List<CheatState>>emptyList() : named) {
                        boolean keyTaken = false;
                        for (CheatState x : g) {
                            keyTaken |= x.key.equals(c.key);
                        }
                        if (!keyTaken && mergeable(g.get(0), c)
                                && (wantMaster || masterOf.get(g.get(0).key) == masterOf.get(c.key))) {
                            target = g;
                            break;
                        }
                    }
                    if (target == null) {
                        target = new ArrayList<>();
                        target.add(c);
                        merged.add(target);
                        if (named == null) {
                            named = new ArrayList<>();
                            byName.put(c.item.name, named);
                        }
                        named.add(target);
                    } else {
                        target.add(c);
                    }
                    if (wantMaster) {
                        masterOf.put(c.key, target);
                    }
                }
            }
        }
        final Map<CheatState, Integer> order = new IdentityHashMap<>();
        for (List<CheatState> cheats : perSource) {
            for (CheatState c : cheats) {
                order.put(c, order.size());
            }
        }
        Collections.sort(merged, new Comparator<List<CheatState>>() {
            @Override
            public int compare(List<CheatState> a, List<CheatState> b) {
                return Integer.compare(first(a), first(b));
            }

            private int first(List<CheatState> g) {
                int min = Integer.MAX_VALUE;
                for (CheatState c : g) {
                    min = Math.min(min, order.get(c));
                }
                return min;
            }
        });

        Set<String> taken = new HashSet<>();
        Map<List<CheatState>, String> ids = new IdentityHashMap<>();
        for (List<CheatState> g : merged) {
            ids.put(g, slug(g.get(0).item.name, taken));
            for (CheatState c : g) {
                c.rep.id = ids.get(g);
            }
        }
        List<Object> cheats = new ArrayList<>();
        for (List<CheatState> g : merged) {
            CheatState first = g.get(0);
            for (CheatState c : g.subList(1, g.size())) {
                mergeInto(first, c);
            }
            Map<String, Object> t = table("id", ids.get(g), "name", first.item.name);
            if (first.item.group != null && !first.item.group.isEmpty()) {
                t.put("group", first.item.group);
            }
            if (first.item.master) {
                t.put("master", true);
            } else if (masterOf.get(first.key) != null && anyNeedsMaster(g)) {
                t.put("requires", new ArrayList<Object>(Collections.singletonList(ids.get(masterOf.get(first.key)))));
            }
            if (first.press != null && !first.press.isEmpty()) {
                t.put("run", "action");
                t.put("press", first.press);
            }
            if (first.hold != null && !first.hold.isEmpty()) {
                t.put("hold", first.hold);
            }
            if (!first.values.isEmpty()) {
                t.put("values", new LinkedHashMap<>(first.values));
            }
            for (Op op : first.ops) {
                if (!t.containsKey(op.kind)) {
                    t.put(op.kind, new ArrayList<Object>());
                }
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) t.get(op.kind);
                list.add(op.table);
            }
            List<Object> raw = new ArrayList<>();
            for (CheatState c : g) {
                raw.add(c.raw);
            }
            t.put("raw", raw);
            cheats.add(t);
        }
        if (cheats.isEmpty()) {
            throw new AtmosphereException("the file has no cheat with code");
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "mncf/1");
        doc.put("name", nonEmpty(options.name) ? options.name
                : nonEmpty(options.gameName) ? options.gameName + " cheats" : "Converted cheats");
        List<String> found = authorsOf(report.credits);
        if (!found.isEmpty() || (options.authors != null && !options.authors.isEmpty())) {
            doc.put("authors", new ArrayList<Object>(!found.isEmpty() ? found : options.authors));
        }
        List<String> about = new ArrayList<>();
        if (!report.credits.isEmpty()) {
            about.add("Credits in the source: " + String.join("; ", report.credits));
        }
        if (!report.notes.isEmpty()) {
            about.add("Lines in the source that are not cheat code: " + String.join("; ", report.notes));
        }
        if (!report.unreadable.isEmpty()) {
            about.add("Entries whose code could not be read, left out: " + String.join("; ", report.unreadable));
        }
        if (!about.isEmpty()) {
            doc.put("about", String.join("\n", about));
        }
        Map<String, Object> origin = table("format", "atmosphere");
        if (nonEmpty(options.origin)) {
            origin.put("name", options.origin);
        }
        String site = siteOf(report.credits);
        if (site != null && !site.isEmpty()) {
            origin.put("site", site);
        }
        doc.put("origin", origin);
        List<Object> exts = new ArrayList<>();
        exts.add("raw/1");
        for (Object c : cheats) {
            if (((Map<?, ?>) c).containsKey("values")) {
                exts.add("arm64-imm/1");
                break;
            }
        }
        doc.put("extensions", table("optional", exts));
        Map<String, Object> game = table("platform", options.platform, "id", options.gameId.toUpperCase(Locale.ROOT));
        if (nonEmpty(options.gameName)) {
            game.put("name", options.gameName);
        }
        doc.put("game", game);
        doc.put("builds", builds);
        doc.put("cheat", cheats);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) Values.freeze(doc);
        List<CheatReport> reports = new ArrayList<>();
        for (Rep r : report.cheats) {
            reports.add(r.done());
        }
        return new Conversion(frozen, Writer.dumps(frozen),
                new ConversionReport(reports, report.groups, report.credits, report.warnings, report.notes,
                        report.unreadable));
    }

    private static boolean anyNeedsMaster(List<CheatState> g) {
        for (CheatState c : g) {
            if (c.needsMaster) {
                return true;
            }
        }
        return false;
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    // Patch files: IPS, IPS32, IPSwitch

    private static final Pattern TITLE_ID = Pattern.compile("[0-9A-Fa-f]{16}");
    private static final List<String> PATCH_FOLDERS = Arrays.asList("exefs", "exefs_patches", "atmosphere", "contents");

    /**
     * The patch set a file belongs to, from its path: the folder it is in
     * ("exefs_patches/&lt;set&gt;/&lt;build ID&gt;.ips", or a mod's folder above "exefs/"), else its own
     * name where that is no build ID, else "Patch".
     */
    static String setName(String fileName) {
        List<String> parts = new ArrayList<>();
        for (String part : Py.split((fileName == null ? "" : fileName).replace('\\', '/'), '/')) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return "Patch";
        }
        for (int i = parts.size() - 2; i >= 0; i--) {
            String folder = parts.get(i);
            if (!PATCH_FOLDERS.contains(folder.toLowerCase(Locale.ROOT)) && !TITLE_ID.matcher(folder).matches()) {
                return folder;
            }
        }
        String last = parts.get(parts.size() - 1);
        int dot = last.lastIndexOf('.');
        String stem = dot < 0 ? last : last.substring(0, dot);
        return stem.isEmpty() || Patches.buildIdOf(last) != null ? "Patch" : stem;
    }

    private static CheatState patchCheat(Item item, String key, Rep rep, List<Patches.Run> runs, CodeModule module,
            String mname, boolean values) throws Atmosphere.Unclear {
        CheatState c = new CheatState(item, key, rep);
        for (Patches.Run run : runs) {
            long off = run.offset();
            byte[] data = run.dataShared();
            List<Object[]> pieces = new ArrayList<>();
            long at = off;
            while (at < off + data.length) {                  // one run for each section it crosses
                String sec = module.sectionOf(at);
                long stop = Math.min(off + data.length, sec != null ? module.sectionEnd(sec) : at + 1);
                pieces.add(new Object[] {sec, at, Arrays.copyOfRange(data, (int) (at - off), (int) (stop - off))});
                at = stop;
            }
            for (Object[] piece : pieces) {
                String sec = (String) piece[0];
                long where = (Long) piece[1];
                byte[] bytes = (byte[]) piece[2];
                if (sec == null) {
                    throw new Atmosphere.Unclear(String.format("%s+0x%X is in no section of the module", mname, where));
                }
                if (module.excluded(where, bytes.length)) {
                    throw new Atmosphere.Unclear(String.format("%s+0x%X: the module's bytes there are not its own, so "
                            + "there is no `expect`", mname, where));
                }
                byte[] expect = Arrays.copyOfRange(module.image(), (int) where, (int) where + bytes.length);
                if (Arrays.equals(expect, bytes)) {
                    rep.notes.add(String.format("%s+0x%X already holds those bytes; the patch stays, as the source "
                            + "writes them", mname, where));
                }
                if ("data".equals(sec)) {
                    c.ops.add(new Op("write", table("at", table("module", mname, "offsets",
                            table(key, Values.unsigned(where))), "type", "bytes", "value", Values.hexBytes(bytes),
                            "expect", Values.hexBytes(expect))));
                    rep.fixed++;
                } else {
                    c.ops.add(new Op("patch", patch(c, where, bytes, expect, module, values, mname)));
                }
            }
        }
        return c;
    }

    /** Returns a conversion of one patch file. */
    public static Conversion fromPatches(PatchSource source, Options options) throws ConvertException {
        return fromPatches(Collections.singletonList(source), options);
    }

    /**
     * Returns a conversion of patch files (IPS, IPS32 or IPSwitch text) of the same game, for one
     * or more builds.
     *
     * <p>Each IPS file becomes one cheat, named by its patch set, that is on by default, as a
     * loader applies it whenever it is there. Each patch of an IPSwitch file becomes one cheat,
     * named by its comment, on by default where it is @enabled. A write into code or read-only
     * data becomes a patch, one into data a write that is made only while the place holds its
     * original bytes, as a loader sets them only once.
     *
     * @throws ConvertException with the code "patch" for a file that is no patch file
     *     ({@link PatchFileException}), or "source" for a file without its module or build ID,
     *     a build with two build IDs for one module, or files of which no patch could be converted
     */
    public static Conversion fromPatches(List<PatchSource> sources, Options options) throws ConvertException {
        Report report = new Report();
        Map<String, Map<String, Object>> builds = new LinkedHashMap<>();
        List<List<CheatState>> perSource = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        List<PatchFile> files = new ArrayList<>();
        List<String> bids = new ArrayList<>();
        for (PatchSource src : sources) {
            PatchFile f = Patches.read(src.dataShared());
            formats.add(f.format());
            String bid = f.buildId();
            if (!nonEmpty(bid)) {
                bid = nonEmpty(src.fileName()) ? Patches.buildIdOf(src.fileName()) : null;
            }
            if (!nonEmpty(bid)) {
                bid = nonEmpty(src.buildId()) ? Patches.trimBuildId(src.buildId()) : null;
            }
            if (!nonEmpty(bid)) {
                bid = src.module() != null && nonEmpty(src.module().buildId())
                        ? Patches.trimBuildId(src.module().buildId()) : null;
            }
            String what = nonEmpty(src.fileName()) ? src.fileName() : "a patch file";
            if (src.module() == null) {
                throw new ConvertException(what + ": a patch needs the module it patches, for `expect`");
            }
            if (!nonEmpty(bid)) {
                throw new ConvertException(what + ": no build ID in the file, its name or the module");
            }
            files.add(f);
            bids.add(bid);
        }
        Map<List<String>, Set<String>> versions = new HashMap<>();
        for (int k = 0; k < sources.size(); k++) {
            PatchSource src = sources.get(k);
            if (nonEmpty(src.version())) {
                List<String> vkey = Arrays.asList(src.version(), src.moduleName());
                if (!versions.containsKey(vkey)) {
                    versions.put(vkey, new HashSet<String>());
                }
                versions.get(vkey).add(bids.get(k));
            }
        }
        for (int k = 0; k < sources.size(); k++) {
            PatchSource src = sources.get(k);
            PatchFile f = files.get(k);
            String bid = bids.get(k);
            Set<String> same = versions.get(Arrays.asList(src.version(), src.moduleName()));
            boolean shared = nonEmpty(src.version()) && same != null && same.size() > 1;
            String named = shared ? bid : src.version();
            String key = nonEmpty(src.buildKey()) ? src.buildKey() : nonEmpty(named) ? named : bid;
            CodeModule module = src.module();
            String mid = module.buildId();
            if (nonEmpty(mid)) {
                String upper = mid.toUpperCase(Locale.ROOT);
                if (!(upper.startsWith(bid) || bid.startsWith(upper))) {
                    report.warnings.add("the module of build " + key + " is " + upper + ", its patch file " + bid);
                }
            }
            Map<String, Object> build = builds.get(key);
            if (build == null) {
                build = new LinkedHashMap<>();
                if (nonEmpty(src.version()) && !src.version().equals(key)) {
                    build.put("version", src.version());
                }
                builds.put(key, build);
            }
            if (!build.containsKey("modules")) {
                build.put("modules", new LinkedHashMap<String, Object>());
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> mods = (Map<String, Object>) build.get("modules");
            if (mods.containsKey(src.moduleName()) && !bid.equals(mods.get(src.moduleName()))) {
                throw new ConvertException("build " + Py.repr(key) + " has two build IDs for " + src.moduleName());
            }
            mods.put(src.moduleName(), bid);
            String set = nonEmpty(src.setName()) ? src.setName() : setName(src.fileName());
            for (PatchFile.Line line : f.notes()) {
                if (!report.notes.contains(line.text())) {
                    report.notes.add(line.text());
                }
            }
            for (PatchFile.Line line : f.comments()) {
                if (CREDIT_NAME.matcher(line.text()).find() && !report.credits.contains(line.text())) {
                    report.credits.add(line.text());
                }
            }
            int count = f.patches().size();
            String group = count > 1 ? set : null;
            List<CheatState> out = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                Patch p = f.patches().get(i - 1);
                String own = nonEmpty(p.name()) && !CREDIT_NAME.matcher(p.name()).find() ? p.name() : "";
                String title = !own.isEmpty() ? own : count == 1 ? set : set + " " + i;
                Rep rep = new Rep("", title, key, CheatReport.PATTERN);
                Patches.ImageRuns written = Patches.imageRuns(p, module.image().length);
                for (String d : written.dropped()) {
                    rep.notes.add("left out " + d);
                }
                CheatState c;
                try {
                    if (written.runs().isEmpty()) {
                        throw new Atmosphere.Unclear("it writes nothing into the module");
                    }
                    c = patchCheat(new Item(title, false, group, null), key, rep, written.runs(), module,
                            src.moduleName(), options.values);
                } catch (Atmosphere.Unclear u) {
                    rep.outcome = CheatReport.UNREADABLE;
                    rep.why = u.why;
                    rep.patterns = 0;
                    rep.fixed = 0;
                    rep.values = 0;
                    rep.notes = new ArrayList<>();
                    for (String d : written.dropped()) {
                        rep.notes.add("left out " + d);
                    }
                    report.unreadable.add(title + " (" + u.why + ")");
                    report.cheats.add(rep);
                    continue;
                }
                c.defaultOn = p.enabled();
                rep.outcome = rep.patterns > 0 && rep.fixed == 0 ? CheatReport.PATTERN : CheatReport.BUILD;
                out.add(c);
                report.cheats.add(rep);
            }
            perSource.add(out);
        }

        List<List<CheatState>> merged = new ArrayList<>();
        Map<String, List<List<CheatState>>> byName = new HashMap<>();
        for (List<CheatState> cheats : perSource) {
            for (CheatState c : cheats) {
                List<CheatState> target = null;
                List<List<CheatState>> named = byName.get(c.item.name);
                for (List<CheatState> g : named == null ? Collections.<List<CheatState>>emptyList() : named) {
                    boolean keyTaken = false;
                    for (CheatState x : g) {
                        keyTaken |= x.key.equals(c.key);
                    }
                    if (!keyTaken && mergeable(g.get(0), c) && g.get(0).defaultOn == c.defaultOn
                            && java.util.Objects.equals(g.get(0).item.group, c.item.group)) {
                        target = g;
                        break;
                    }
                }
                if (target == null) {
                    target = new ArrayList<>();
                    target.add(c);
                    merged.add(target);
                    if (named == null) {
                        named = new ArrayList<>();
                        byName.put(c.item.name, named);
                    }
                    named.add(target);
                } else {
                    target.add(c);
                }
            }
        }
        Set<String> taken = new HashSet<>();
        List<Object> cheats = new ArrayList<>();
        for (List<CheatState> g : merged) {
            CheatState first = g.get(0);
            String cid = slug(first.item.name, taken);
            for (CheatState c : g) {
                c.rep.id = cid;
                if (c != first) {
                    mergeInto(first, c);
                }
            }
            Map<String, Object> t = table("id", cid, "name", first.item.name);
            if (nonEmpty(first.item.group)) {
                t.put("group", first.item.group);
            }
            if (first.defaultOn) {
                t.put("default", true);
            }
            if (!first.values.isEmpty()) {
                t.put("values", new LinkedHashMap<>(first.values));
            }
            for (Op op : first.ops) {
                if (!t.containsKey(op.kind)) {
                    t.put(op.kind, new ArrayList<Object>());
                }
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) t.get(op.kind);
                list.add(op.table);
            }
            cheats.add(t);
        }
        if (cheats.isEmpty()) {
            throw new ConvertException("no patch of the files could be converted: " + String.join("; ",
                    report.unreadable.isEmpty() ? Collections.singletonList("none") : report.unreadable));
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "mncf/1");
        doc.put("name", nonEmpty(options.name) ? options.name
                : nonEmpty(options.gameName) ? options.gameName + " patches" : "Converted patches");
        List<String> found = authorsOf(report.credits);
        if (!found.isEmpty() || (options.authors != null && !options.authors.isEmpty())) {
            doc.put("authors", new ArrayList<Object>(!found.isEmpty() ? found : options.authors));
        }
        List<String> about = new ArrayList<>();
        if (!report.credits.isEmpty()) {
            about.add("Credits in the source: " + String.join("; ", report.credits));
        }
        if (!report.notes.isEmpty()) {
            about.add("Lines in the source that are not patches: " + String.join("; ", report.notes));
        }
        if (!report.unreadable.isEmpty()) {
            about.add("Patches that could not be converted, left out: " + String.join("; ", report.unreadable));
        }
        if (!about.isEmpty()) {
            doc.put("about", String.join("\n", about));
        }
        Map<String, Object> origin = table("format", formats.get(0).equals("ips32") ? "ips" : formats.get(0));
        if (nonEmpty(options.origin)) {
            origin.put("name", options.origin);
        }
        String site = siteOf(report.credits);
        if (site != null && !site.isEmpty()) {
            origin.put("site", site);
        }
        doc.put("origin", origin);
        for (Object c : cheats) {
            if (((Map<?, ?>) c).containsKey("values")) {
                doc.put("extensions", table("optional", new ArrayList<Object>(Collections.singletonList("arm64-imm/1"))));
                break;
            }
        }
        Map<String, Object> game = table("platform", options.platform, "id", options.gameId.toUpperCase(Locale.ROOT));
        if (nonEmpty(options.gameName)) {
            game.put("name", options.gameName);
        }
        doc.put("game", game);
        doc.put("builds", builds);
        doc.put("cheat", cheats);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>) Values.freeze(doc);
        List<CheatReport> reports = new ArrayList<>();
        for (Rep r : report.cheats) {
            reports.add(r.done());
        }
        return new Conversion(frozen, Writer.dumps(frozen),
                new ConversionReport(reports, report.groups, report.credits, report.warnings, report.notes,
                        report.unreadable));
    }

    // Back to Atmosphere

    /** An Atmosphère cheat file made from a loaded file for one build, and what went into it. Immutable. */
    public static final class Export {
        private final String fileName;
        private final String text;
        private final Map<String, String> written;
        private final Map<String, String> skipped;
        private final Map<String, String> reasons;

        Export(String fileName, String text, Map<String, String> written, Map<String, String> skipped,
                Map<String, String> reasons) {
            this.fileName = fileName;
            this.text = text;
            this.written = Collections.unmodifiableMap(new LinkedHashMap<>(written));
            this.skipped = Collections.unmodifiableMap(new LinkedHashMap<>(skipped));
            this.reasons = Collections.unmodifiableMap(new LinkedHashMap<>(reasons));
        }

        /** Returns the file name cheat files use: the build ID of the main module and ".txt". */
        public String fileName() {
            return fileName;
        }

        /** Returns the text of the cheat file. */
        public String text() {
            return text;
        }

        /** Returns the cheats written, by id: "raw" for their own raw code, "core" for code made from their operations. */
        public Map<String, String> written() {
            return written;
        }

        /** Returns the cheats left out, by id, with why. */
        public Map<String, String> skipped() {
            return skipped;
        }

        /**
         * Returns the cheats left out, by id, with the stable reason (spec, section 15): the
         * reader's reason for a cheat with a problem of its own, such as {@code build-unknown},
         * {@code pattern-not-found} or {@code expect-mismatch}, or {@code atmosphere:<what>} for
         * what Atmosphère cannot say.
         */
        public Map<String, String> reasons() {
            return reasons;
        }
    }

    /** One build of a file as an Atmosphère cheat file, or the reason it cannot be one. Immutable. */
    public static final class BuildExport {
        private final String build;
        private final Export export;
        private final String reason;

        BuildExport(String build, Export export, String reason) {
            this.build = build;
            this.export = export;
            this.reason = reason;
        }

        /** Returns the build key. */
        public String build() {
            return build;
        }

        /** Returns the cheat file, or null when the build cannot be one. */
        public Export export() {
            return export;
        }

        /** Returns null, or {@code atmosphere:module} for a build that names no main module. */
        public String reason() {
            return reason;
        }
    }

    /**
     * Returns every build of a loaded file as an Atmosphère cheat file, in the file's order,
     * for an export in the Atmosphère layout.
     *
     * @param modules the main module of each build, by build key, for the cheats that find their
     *     place by a pattern; may be null or lack builds
     * @param prefer "raw" or "core"
     * @param choices the values the player chose; may be null
     */
    public static List<BuildExport> toAtmosphereAll(MncfFile f, Map<String, CodeModule> modules, String prefer,
            Choices choices) {
        return toAtmosphereAll(f, modules, prefer, choices, null);
    }

    /**
     * Returns every build of a loaded file as an Atmosphère cheat file, as
     * {@link #toAtmosphereAll(MncfFile, Map, String, Choices)} does, with raw or core chosen for
     * single cheats.
     *
     * @param preferCheats "raw" or "core" by cheat id, where a cheat should differ from prefer;
     *     may be null
     */
    public static List<BuildExport> toAtmosphereAll(MncfFile f, Map<String, CodeModule> modules, String prefer,
            Choices choices, Map<String, String> preferCheats) {
        return toAtmosphereAll(f, modules, prefer, choices, preferCheats, AtmosphereLimits.ATMOSPHERE);
    }

    /**
     * Returns every build of a loaded file as an Atmosphère cheat file, as
     * {@link #toAtmosphereAll(MncfFile, Map, String, Choices, Map)} does, in the limits of the
     * host it is for.
     *
     * @param limits the host's limits; null for Atmosphère's
     */
    public static List<BuildExport> toAtmosphereAll(MncfFile f, Map<String, CodeModule> modules, String prefer,
            Choices choices, Map<String, String> preferCheats, AtmosphereLimits limits) {
        List<BuildExport> out = new ArrayList<>();
        for (Map.Entry<String, Build> e : f.builds().entrySet()) {
            if (!e.getValue().modules().containsKey("main")) {
                out.add(new BuildExport(e.getKey(), null, "atmosphere:module"));
                continue;
            }
            CodeModule module = modules == null ? null : modules.get(e.getKey());
            out.add(new BuildExport(e.getKey(),
                    toAtmosphere(f, e.getKey(), module, prefer, choices, preferCheats, limits), null));
        }
        return out;
    }

    /**
     * Returns the cheats of a loaded file as an Atmosphère cheat file for one build. Each cheat
     * uses its raw code for the build when it has one and prefer is "raw"; otherwise code made
     * from its operations, where they can be said in Atmosphère code.
     *
     * @param module the main module of the build, for operations that find their place by a
     *     pattern; may be null
     * @param prefer "raw" or "core"
     * @throws IllegalArgumentException when the file has no such build and no module is given,
     *     or the build names no main module
     */
    public static Export toAtmosphere(MncfFile f, String buildKey, CodeModule module, String prefer) {
        return toAtmosphere(f, buildKey, module, prefer, null);
    }

    /**
     * Returns the cheats of a loaded file as an Atmosphère cheat file for one build, as
     * {@link #toAtmosphere(MncfFile, String, CodeModule, String)} does, with the values the player
     * chose. A build the file does not name is written from its patterns, with the module of that
     * build; the file name is then the module's build ID.
     *
     * @param choices the values the player chose, checked as the reader checks them: a cheat with
     *     a value it cannot take is written from its raw entry for the build, or else left out;
     *     null, or a value not chosen, writes the default
     * @throws IllegalArgumentException when the file has no such build and no module is given,
     *     or the build names no main module
     */
    public static Export toAtmosphere(MncfFile f, String buildKey, CodeModule module, String prefer,
            Choices choices) {
        return toAtmosphere(f, buildKey, module, prefer, choices, null);
    }

    /**
     * What one Atmosphère cheat file may hold, beyond which a host refuses the whole file; null
     * is no limit. {@link #ATMOSPHERE} are the limits of Atmosphère's dmnt. A host that takes
     * more, such as an emulator's own cheat engine, gives its own, or {@link #NONE} (spec,
     * section 15). Immutable.
     */
    public static final class AtmosphereLimits {
        /**
         * Atmosphère's: 127 entries with code besides the master, of 256 words each, a file of
         * at most 65,535 bytes, names of at most 63 bytes.
         */
        public static final AtmosphereLimits ATMOSPHERE = new AtmosphereLimits(127, 0x100, 0xFFFF, 63);
        /** No limits. */
        public static final AtmosphereLimits NONE = new AtmosphereLimits(null, null, null, null);

        private final Integer entries;
        private final Integer words;
        private final Integer size;
        private final Integer name;

        /**
         * Makes limits.
         *
         * @param entries entries with code besides the master; null for any number
         * @param words code words of one entry, and of the master entry; null for any number
         * @param size bytes of the file; null for any size
         * @param name bytes of a name in UTF-8; null for names of any length
         * @throws IllegalArgumentException for a limit below 1
         */
        public AtmosphereLimits(Integer entries, Integer words, Integer size, Integer name) {
            check("entries", entries);
            check("words", words);
            check("size", size);
            check("name", name);
            this.entries = entries;
            this.words = words;
            this.size = size;
            this.name = name;
        }

        private static void check(String key, Integer v) {
            if (v != null && v < 1) {
                throw new IllegalArgumentException("the limit " + key
                        + " is a whole number of at least 1, or null, not " + v);
            }
        }

        /** Returns the entries with code a file holds besides the master, or null for any number. */
        public Integer entries() {
            return entries;
        }

        /** Returns the code words of one entry, or null for any number. */
        public Integer words() {
            return words;
        }

        /** Returns the bytes of the file, or null for any size. */
        public Integer size() {
            return size;
        }

        /** Returns the bytes of a name in UTF-8, or null for names of any length. */
        public Integer name() {
            return name;
        }
    }

    /** Entries of an Atmosphère file besides the master: dmnt holds 0x80, the first the master. */
    static final int ATM_ENTRIES = 127;
    /** Words of one entry. */
    static final int ATM_WORDS = 0x100;
    /** What an EdiZon section marker adds to the group's name. */
    private static final int SECTION_SIZE = "--SectionStart:--".length();

    private static int wordCount(List<String> lines) {
        int n = 0;
        for (String line : lines) {
            n += Py.splitSpace(line).size();
        }
        return n;
    }

    /**
     * Returns a name as an Atmosphère file holds it: the bracket that would end it early ("]",
     * for a master "}") becomes ")", a control character or line break a space, and it is cut
     * to limit bytes of UTF-8 at a character boundary.
     *
     * @param limit bytes of UTF-8; null for no cut
     */
    public static String atmosphereName(String name, boolean master, Integer limit) {
        int close = master ? '}' : ']';
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == close) {
                out.append(')');
            } else if (cp < 0x20 || cp == 0x7F || cp == 0x85 || cp == 0x2028 || cp == 0x2029) {
                out.append(' ');
            } else {
                out.appendCodePoint(cp);
            }
        }
        return limit == null ? out.toString() : cut(out.toString(), limit);
    }

    private static String cut(String text, int limit) {
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + n > limit) {
                return text.substring(0, i);
            }
            bytes += n;
            i += Character.charCount(cp);
        }
        return text;
    }

    private static int utf8Length(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * An EdiZon section marker, --SectionStart:Name-- or --SectionEnd:Name--: an entry without
     * code, which takes no slot in dmnt. The group's name is cut alike in both, so they pair.
     */
    private static String section(String kind, String group, Integer limit) {
        return "--Section" + kind + ":" + atmosphereName(group, false, limit == null ? null : limit - SECTION_SIZE)
                + "--";
    }

    /** The bytes an entry adds to the text writeFile makes: its name line, its code lines, a blank line. */
    private static int entrySize(String name, List<String> lines) {
        int n = utf8Length(name) + 4;
        for (String line : lines) {
            n += line.length() + 1;
        }
        return n;
    }

    /** The name, or with " (2)", " (3)" and so on where an entry already has it: dmnt keeps states by name. */
    private static String unique(String name, Set<String> used, Integer limit) {
        String out = name;
        for (int k = 2; used.contains(out); k++) {
            String suffix = " (" + k + ")";
            out = (limit == null ? name : cut(name, limit - suffix.length())) + suffix;
        }
        return out;
    }

    private static Map<String, String> modes(String prefer, Map<String, String> preferCheats) {
        Map<String, String> modes = preferCheats == null ? Collections.<String, String>emptyMap() : preferCheats;
        List<String> all = new ArrayList<>();
        all.add(prefer);
        all.addAll(modes.values());
        for (String mode : all) {
            if (!"raw".equals(mode) && !"core".equals(mode)) {
                throw new IllegalArgumentException("prefer is \"raw\" or \"core\", not " + Py.repr(mode));
            }
        }
        return modes;
    }

    /**
     * Returns the cheats of a loaded file as an Atmosphère cheat file for one build, as
     * {@link #toAtmosphere(MncfFile, String, CodeModule, String, Choices)} does, with raw or core
     * chosen for single cheats, such as one whose value the player set, in Atmosphère's limits.
     *
     * @param preferCheats "raw" or "core" by cheat id, where a cheat should differ from prefer;
     *     may be null
     * @throws IllegalArgumentException as the other forms do, or for a preference that is neither
     *     "raw" nor "core"
     */
    public static Export toAtmosphere(MncfFile f, String buildKey, CodeModule module, String prefer,
            Choices choices, Map<String, String> preferCheats) {
        return toAtmosphere(f, buildKey, module, prefer, choices, preferCheats, AtmosphereLimits.ATMOSPHERE);
    }

    /**
     * Returns the cheats of a loaded file as an Atmosphère cheat file for one build, as
     * {@link #toAtmosphere(MncfFile, String, CodeModule, String, Choices, Map)} does, in the
     * limits of the host it is for. The file keeps them: one master entry with the code of every
     * master cheat, then the other cheats in file order, each after the cheats it requires, groups
     * in EdiZon section markers, names as dmnt reads them. What does not fit is left out with
     * {@code atmosphere:too-many}, {@code atmosphere:too-long} or {@code atmosphere:too-big},
     * and a cheat that requires a cheat left out with {@code requires:<id>}.
     *
     * @param limits the host's limits; null for Atmosphère's
     * @throws IllegalArgumentException as the other forms do
     */
    public static Export toAtmosphere(MncfFile f, String buildKey, CodeModule module, String prefer,
            Choices choices, Map<String, String> preferCheats, AtmosphereLimits limits) {
        Map<String, String> modes = modes(prefer, preferCheats);
        AtmosphereLimits lim = limits == null ? AtmosphereLimits.ATMOSPHERE : limits;
        Build build = f.builds().get(buildKey);
        String mainId;
        if (build != null) {
            mainId = build.modules().get("main");
            if (mainId == null) {
                throw new IllegalArgumentException("build " + Py.repr(buildKey) + " names no main module");
            }
        } else if (module != null && nonEmpty(module.shortId())) {
            mainId = module.shortId();
        } else {
            throw new IllegalArgumentException("the file has no build " + Py.repr(buildKey)
                    + ", and no module of it is given");
        }
        Choices chosen = choices != null ? choices : Choices.none();
        Part part = new Part(lower(f, buildKey, module, prefer, chosen, modes, lim));
        List<Atmosphere.Out> entries = assemble(Collections.singletonList(part), lim);
        String name = shortBuildId(mainId) + ".txt";
        return new Export(name, Atmosphere.writeFile(entries), part.written, part.skipped, part.reasons);
    }

    private static String shortBuildId(String id) {
        return id.substring(0, Math.min(16, id.length())).toUpperCase(Locale.ROOT);
    }

    /**
     * One loaded file to write back together with others ({@link #toAtmosphereMerged}): the
     * player's choices for it, and raw or core for its cheats. Immutable.
     */
    public static final class BackSource {
        private final MncfFile file;
        private final Choices choices;
        private final String prefer;
        private final Map<String, String> preferCheats;

        /**
         * Makes a source.
         *
         * @param choices the values the player chose; may be null
         * @param prefer "raw" or "core"
         * @param preferCheats "raw" or "core" by cheat id; may be null
         */
        public BackSource(MncfFile file, Choices choices, String prefer, Map<String, String> preferCheats) {
            this.file = file;
            this.choices = choices;
            this.prefer = prefer;
            this.preferCheats = preferCheats == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(preferCheats));
        }

        /** Returns a source with its raw code and the defaults. */
        public static BackSource of(MncfFile file) {
            return new BackSource(file, null, "raw", null);
        }

        /** Returns the file. */
        public MncfFile file() {
            return file;
        }

        /** Returns the values the player chose, or null. */
        public Choices choices() {
            return choices;
        }

        /** Returns "raw" or "core". */
        public String prefer() {
            return prefer;
        }

        /** Returns "raw" or "core" by cheat id, or null. */
        public Map<String, String> preferCheats() {
            return preferCheats;
        }
    }

    /**
     * One build as one Atmosphère cheat file with the cheats of several files, or the reason a
     * build cannot be one. The lists have an item for each source, in their order. Immutable.
     */
    public static final class MergedExport {
        private final String buildId;
        private final String fileName;
        private final String text;
        private final List<String> builds;
        private final List<Map<String, String>> written;
        private final List<Map<String, String>> skipped;
        private final List<Map<String, String>> reasons;
        private final String reason;

        MergedExport(String buildId, String text, List<String> builds, List<Part> parts, String reason) {
            this.buildId = buildId;
            this.fileName = buildId == null ? null : buildId + ".txt";
            this.text = text;
            this.builds = Collections.unmodifiableList(new ArrayList<>(builds));
            List<Map<String, String>> w = new ArrayList<>();
            List<Map<String, String>> s = new ArrayList<>();
            List<Map<String, String>> r = new ArrayList<>();
            for (Part p : parts) {
                w.add(Collections.unmodifiableMap(new LinkedHashMap<>(p.written)));
                s.add(Collections.unmodifiableMap(new LinkedHashMap<>(p.skipped)));
                r.add(Collections.unmodifiableMap(new LinkedHashMap<>(p.reasons)));
            }
            this.written = Collections.unmodifiableList(w);
            this.skipped = Collections.unmodifiableList(s);
            this.reasons = Collections.unmodifiableList(r);
            this.reason = reason;
        }

        /** Returns the first 16 digits of the build ID of the main module, upper case, or null. */
        public String buildId() {
            return buildId;
        }

        /** Returns the file name: the build ID and ".txt", or null when the build cannot be a file. */
        public String fileName() {
            return fileName;
        }

        /** Returns the text of the cheat file, or null. */
        public String text() {
            return text;
        }

        /** Returns each source's key of the build, null where a source does not name it. */
        public List<String> builds() {
            return builds;
        }

        /** Returns for each source the cheats written, by id: "raw" or "core". */
        public List<Map<String, String>> written() {
            return written;
        }

        /** Returns for each source the cheats left out, by id, with why. */
        public List<Map<String, String>> skipped() {
            return skipped;
        }

        /** Returns for each source the cheats left out, by id, with the stable reason (spec, section 15). */
        public List<Map<String, String>> reasons() {
            return reasons;
        }

        /** Returns null, or {@code atmosphere:module} for a build that names no main module. */
        public String reason() {
            return reason;
        }
    }

    /**
     * Returns several loaded files of one game as Atmosphère cheat files, one for each build. A
     * host reads one cheat file a build, so every file's cheats for a build go into one, in one
     * set of limits: one master entry with the code of every master cheat, then each file's
     * cheats, in the order of the sources. Builds are matched by the first 16 digits of the build
     * ID of their main module. A build that only a module names is written from what the files
     * can place without their own offsets for it, as is a build that one file names for the
     * others. The files come in the order their builds first appear, then those of the modules;
     * a build that names no main module comes as one with reason {@code atmosphere:module}.
     *
     * @param modules the main module of each build, by build ID, for the cheats that find their
     *     place by a pattern; may be null
     * @param limits the host's limits; null for Atmosphère's
     * @throws IllegalArgumentException for a preference that is neither "raw" nor "core"
     */
    public static List<MergedExport> toAtmosphereMerged(List<BackSource> sources, Map<String, CodeModule> modules,
            AtmosphereLimits limits) {
        AtmosphereLimits lim = limits == null ? AtmosphereLimits.ATMOSPHERE : limits;
        Map<String, CodeModule> byId = new LinkedHashMap<>();
        if (modules != null) {
            for (Map.Entry<String, CodeModule> e : modules.entrySet()) {
                byId.put(shortBuildId(e.getKey()), e.getValue());
            }
        }
        for (BackSource s : sources) {
            modes(s.prefer(), s.preferCheats());
        }
        // Each item: a build ID, or null with the source and key of a build without main.
        List<Object[]> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            for (Map.Entry<String, Build> e : sources.get(i).file().builds().entrySet()) {
                String main = e.getValue().modules().get("main");
                if (main == null) {
                    order.add(new Object[] {null, i, e.getKey()});
                } else if (seen.add(shortBuildId(main))) {
                    order.add(new Object[] {shortBuildId(main)});
                }
            }
        }
        for (String id : byId.keySet()) {
            if (seen.add(id)) {
                order.add(new Object[] {id});
            }
        }
        List<MergedExport> out = new ArrayList<>();
        for (Object[] item : order) {
            String id = (String) item[0];
            List<String> builds = new ArrayList<>();
            if (id == null) {
                for (int j = 0; j < sources.size(); j++) {
                    builds.add(j == (Integer) item[1] ? (String) item[2] : null);
                }
                out.add(new MergedExport(null, null, builds, Collections.<Part>emptyList(), "atmosphere:module"));
                continue;
            }
            List<Part> parts = new ArrayList<>();
            for (BackSource s : sources) {
                String key = null;
                for (Map.Entry<String, Build> e : s.file().builds().entrySet()) {
                    String main = e.getValue().modules().get("main");
                    if (shortBuildId(main == null ? "" : main).equals(id)) {
                        key = e.getKey();
                        break;
                    }
                }
                builds.add(key);
                Choices chosen = s.choices() != null ? s.choices() : Choices.none();
                parts.add(new Part(lower(s.file(), key, byId.get(id), s.prefer(), chosen,
                        modes(s.prefer(), s.preferCheats()), lim)));
            }
            out.add(new MergedExport(id, Atmosphere.writeFile(assemble(parts, lim)), builds, parts, null));
        }
        return out;
    }

    /** A cheat as Atmosphère code lines for one build, or the reason it cannot be. */
    private static final class Lowered {
        final Cheat cheat;
        final String how;
        final List<String> lines;
        final String why;
        final String reason;

        Lowered(Cheat cheat, String how, List<String> lines, String why, String reason) {
            this.cheat = cheat;
            this.how = how;
            this.lines = lines;
            this.why = why;
            this.reason = reason;
        }
    }

    /** The lowered cheats of one file, the master cheats first, and what became of them. */
    private static final class Part {
        final List<Lowered> lowered;
        final Map<String, String> written = new LinkedHashMap<>();
        final Map<String, String> skipped = new LinkedHashMap<>();
        final Map<String, String> reasons = new LinkedHashMap<>();

        Part(List<Lowered> lowered) {
            this.lowered = lowered;
        }

        void leaveOut(Cheat c, String why, String code) {
            skipped.put(c.id(), why);
            reasons.put(c.id(), code);
        }
    }

    /** Each cheat of a file as Atmosphère code lines for one build, or why not, the masters first. */
    private static List<Lowered> lower(MncfFile f, String buildKey, CodeModule module, String prefer, Choices chosen,
            Map<String, String> modes, AtmosphereLimits limits) {
        List<Cheat> cheats = new ArrayList<>(f.cheats());
        Collections.sort(cheats, new Comparator<Cheat>() {
            @Override
            public int compare(Cheat a, Cheat b) {
                return Boolean.compare(!a.isMaster(), !b.isMaster());
            }
        });
        List<Lowered> out = new ArrayList<>();
        for (Cheat c : cheats) {
            if (c.problem() != null) {
                out.add(new Lowered(c, null, null, c.problem(), c.problem()));
                continue;
            }
            Model.Raw raw = null;
            for (Model.Raw r : c.raw()) {
                if (r.build.equals(buildKey) && r.syntax.equals("atmosphere")) {
                    raw = r;
                    break;
                }
            }
            List<String> rawLines = null;
            if (raw != null) {
                // Words apart by plain spaces, as dmnt reads white space.
                rawLines = new ArrayList<>();
                for (String line : Py.splitLines(Py.strip(raw.code))) {
                    if (!Py.strip(line).isEmpty()) {
                        rawLines.add(String.join(" ", Py.splitSpace(line)));
                    }
                }
            }
            if (raw == null && c.ops().isEmpty()) {
                out.add(new Lowered(c, null, null, "only raw code, for other builds", "build-unknown"));
                continue;
            }
            List<String> lines;
            String how;
            String mode = modes.containsKey(c.id()) ? modes.get(c.id()) : prefer;
            if (raw != null && "raw".equals(mode)) {
                lines = rawLines;
                how = "raw";
            } else {
                try {
                    List<Long> words = coreWords(c, buildKey, module, chosen);
                    if (limits.words() != null && words.size() > limits.words()) {
                        throw new Atmosphere.Unclear(words.size() + " words, more than an entry holds",
                                "atmosphere:too-long");
                    }
                    lines = Atmosphere.formatWords(words);
                    how = "core";
                } catch (Atmosphere.Unclear u) {
                    if (raw == null) {
                        out.add(new Lowered(c, null, null, u.why, u.code != null ? u.code : "atmosphere:unclear"));
                        continue;
                    }
                    lines = rawLines;
                    how = "raw";
                }
            }
            if (limits.words() != null && wordCount(lines) > limits.words()) {
                out.add(new Lowered(c, null, null, wordCount(lines) + " words, more than an entry holds",
                        "atmosphere:too-long"));
                continue;
            }
            out.add(new Lowered(c, how, lines, null, null));
        }
        return out;
    }

    /** The lowered cheats in file order, except that a cheat comes after those of them it requires. */
    private static List<Lowered> afterRequirements(List<Lowered> items) {
        Set<String> ids = new HashSet<>();
        for (Lowered lo : items) {
            ids.add(lo.cheat.id());
        }
        Set<String> done = new HashSet<>();
        List<Lowered> out = new ArrayList<>();
        List<Lowered> todo = new ArrayList<>(items);
        while (!todo.isEmpty()) {
            int pick = 0;
            for (int i = 0; i < todo.size(); i++) {
                boolean ready = true;
                for (String r : todo.get(i).cheat.requires()) {
                    if (!done.contains(r) && ids.contains(r)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    pick = i;
                    break;
                }
            }
            Lowered lo = todo.remove(pick);
            done.add(lo.cheat.id());
            out.add(lo);
        }
        return out;
    }

    private static String missingRequirement(Part part, Cheat c) {
        for (String r : c.requires()) {
            if (part.reasons.containsKey(r)) {
                return r;
            }
        }
        return null;
    }

    /**
     * The entries of one Atmosphère file from the lowered cheats of one or more files, in the
     * order and limits of section 15.
     */
    private static List<Atmosphere.Out> assemble(List<Part> parts, AtmosphereLimits limits) {
        for (Part part : parts) {
            for (Lowered lo : part.lowered) {
                if (lo.reason != null) {
                    part.leaveOut(lo.cheat, lo.why, lo.reason);
                }
            }
        }
        List<Object[]> order = new ArrayList<>();          // part index, lowered
        for (int k = 0; k < parts.size(); k++) {
            for (Lowered lo : parts.get(k).lowered) {
                if (lo.cheat.isMaster() && lo.reason == null) {
                    order.add(new Object[] {k, lo});
                }
            }
        }
        for (int k = 0; k < parts.size(); k++) {
            List<Lowered> others = new ArrayList<>();
            for (Lowered lo : parts.get(k).lowered) {
                if (!lo.cheat.isMaster() && lo.reason == null) {
                    others.add(lo);
                }
            }
            for (Lowered lo : afterRequirements(others)) {
                order.add(new Object[] {k, lo});
            }
        }
        List<Object[]> placed = new ArrayList<>();         // part index, lowered, name (null for a master)
        Set<String> used = new HashSet<>();
        String masterName = null;
        int masterWords = 0;
        int count = 0;
        long size = 0;                                      // bytes of the text so far plus one
        String group = null;
        for (Object[] item : order) {
            int k = (Integer) item[0];
            Lowered lo = (Lowered) item[1];
            Cheat c = lo.cheat;
            Part part = parts.get(k);
            String missing = missingRequirement(part, c);
            if (missing != null) {
                part.leaveOut(c, "requires " + missing + ", which is left out", "requires:" + missing);
                continue;
            }
            if (c.isMaster()) {
                // Atmosphère runs one master entry: the code of every master cheat goes into it, in order.
                String name = masterName != null ? masterName : atmosphereName(c.name(), true, limits.name());
                long add = masterName != null ? 0 : utf8Length(name) + 4;
                for (String line : lo.lines) {
                    add += line.length() + 1;
                }
                if (limits.words() != null && masterWords + wordCount(lo.lines) > limits.words()) {
                    part.leaveOut(c, "the master entry would hold more than " + limits.words() + " words",
                            "atmosphere:too-long");
                } else if (limits.size() != null && size + add - 1 > limits.size()) {
                    part.leaveOut(c, "the file would be more than " + limits.size() + " bytes", "atmosphere:too-big");
                } else {
                    masterName = name;
                    masterWords += wordCount(lo.lines);
                    size += add;
                    placed.add(new Object[] {k, lo, null});
                    part.written.put(c.id(), lo.how);
                }
                continue;
            }
            String cheatGroup = c.group() == null || c.group().isEmpty() ? null : c.group();
            List<String> marks = new ArrayList<>();
            if (!java.util.Objects.equals(cheatGroup, group)) {
                if (group != null) {
                    marks.add(section("End", group, limits.name()));
                }
                if (cheatGroup != null) {
                    marks.add(section("Start", cheatGroup, limits.name()));
                }
            }
            String name = unique(atmosphereName(c.name(), false, limits.name()), used, limits.name());
            long add = entrySize(name, lo.lines);
            for (String m : marks) {
                add += entrySize(m, Collections.<String>emptyList());
            }
            if (limits.entries() != null && count + 1 > limits.entries()) {
                part.leaveOut(c, "more than " + limits.entries() + " entries", "atmosphere:too-many");
            } else if (limits.size() != null && size + add - 1 > limits.size()) {
                part.leaveOut(c, "the file would be more than " + limits.size() + " bytes", "atmosphere:too-big");
            } else {
                count++;
                size += add;
                group = cheatGroup;
                used.add(name);
                placed.add(new Object[] {k, lo, name});
                part.written.put(c.id(), lo.how);
            }
        }
        // A cheat placed before a cheat it requires, in a ring of requirements, goes again where that one is left out.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < placed.size(); i++) {
                Part part = parts.get((Integer) placed.get(i)[0]);
                Lowered lo = (Lowered) placed.get(i)[1];
                String missing = missingRequirement(part, lo.cheat);
                if (missing != null) {
                    placed.remove(i);
                    part.written.remove(lo.cheat.id());
                    part.leaveOut(lo.cheat, "requires " + missing + ", which is left out", "requires:" + missing);
                    changed = true;
                    break;
                }
            }
        }
        List<Atmosphere.Out> entries = new ArrayList<>();
        List<String> masterLines = new ArrayList<>();
        boolean anyMaster = false;
        for (Object[] p : placed) {
            if (p[2] == null) {
                anyMaster = true;
                masterLines.addAll(((Lowered) p[1]).lines);
            }
        }
        if (anyMaster) {
            entries.add(new Atmosphere.Out(masterName, true, masterLines));
        }
        group = null;
        for (Object[] p : placed) {
            if (p[2] == null) {
                continue;
            }
            Lowered lo = (Lowered) p[1];
            String cheatGroup = lo.cheat.group() == null || lo.cheat.group().isEmpty() ? null : lo.cheat.group();
            if (!java.util.Objects.equals(cheatGroup, group)) {
                if (group != null) {
                    entries.add(new Atmosphere.Out(section("End", group, limits.name()), false,
                            Collections.<String>emptyList()));
                }
                if (cheatGroup != null) {
                    entries.add(new Atmosphere.Out(section("Start", cheatGroup, limits.name()), false,
                            Collections.<String>emptyList()));
                }
                group = cheatGroup;
            }
            entries.add(new Atmosphere.Out((String) p[2], false, lo.lines));
        }
        return entries;
    }

    private static long base(Model.Location loc, String buildKey, CodeModule module) throws Atmosphere.Unclear {
        if (loc.kind.equals("module") || loc.kind.equals("region")) {
            return loc.offset;
        }
        if (loc.kind.equals("module-builds") || loc.kind.equals("region-builds")) {
            if (!loc.offsets.containsKey(buildKey)) {
                throw new Atmosphere.Unclear("a location without an offset for this build", "build-unknown");
            }
            return loc.offsets.get(buildKey);
        }
        if (loc.kind.equals("pattern")) {
            if (module == null) {
                throw new Atmosphere.Unclear("a pattern, and no module to search", "atmosphere:no-module");
            }
            List<Long> found = Evaluator.findAll(module.toModuleWithoutId(), loc.find, loc.section, 2);
            if (found.size() != 1) {
                throw new Atmosphere.Unclear("a pattern that does not match exactly once in the module",
                        found.isEmpty() ? "pattern-not-found" : "pattern-ambiguous");
            }
            return found.get(0) + loc.offset;
        }
        throw new Atmosphere.Unclear("an absolute address", "atmosphere:address");
    }

    private static Atmosphere.Place place(Model.Location loc, String buildKey, CodeModule module) throws Atmosphere.Unclear {
        if (loc.module != null && !loc.module.equals("main")) {
            throw new Atmosphere.Unclear("a module other than main", "atmosphere:module");
        }
        String region = loc.module != null ? "main" : loc.region;
        if (Atmosphere.regionCode(region) < 0) {
            throw new Atmosphere.Unclear("the region " + (region == null ? "None" : region), "atmosphere:region");
        }
        long[] path = loc.hasPath() ? loc.path.clone() : null;
        return new Atmosphere.Place(region, base(loc, buildKey, module), path);
    }

    private static List<Atmosphere.Store> stores(Atmosphere.Place place, byte[] data) {
        List<Atmosphere.Store> out = new ArrayList<>();
        int i = 0;
        while (i < data.length) {
            int left = data.length - i;
            int width = left >= 4 ? 4 : left >= 2 ? 2 : 1;
            long v = 0;
            for (int k = 0; k < width; k++) {
                v |= (data[i + k] & 0xFFL) << (8 * k);
            }
            out.add(new Atmosphere.Store(place.plus(i), width, v));
            i += width;
        }
        return out;
    }

    /**
     * A value of the cheat: a number, the value the player chose, or the default. A chosen value
     * that the player cannot choose makes the cheat unclear, as the reader refuses it
     * (value-out-of-range).
     */
    private static Object defaultOf(Cheat c, Object v, Choices chosen) throws Atmosphere.Unclear {
        if (!(v instanceof Model.ValueRef)) {
            return v;
        }
        Model.ValueRef ref = (Model.ValueRef) v;
        if (!chosen.values().containsKey(c.id() + "." + ref.name)) {
            return c.values().get(ref.name).defaultValue();
        }
        Object value = Evaluator.chosenValue(c, ref, chosen);
        if (value == null) {
            throw new Atmosphere.Unclear("a chosen value the cheat cannot take", "value-out-of-range");
        }
        return value;
    }

    /**
     * With the module, the bytes a patch or stub writes over must be expect, or data already:
     * Atmosphère code writes without looking. Not where the module's bytes are not its own.
     */
    /**
     * With the module, a place in main lies inside it with every byte read or written there: for a
     * pointer path, the pointer at its base.
     */
    private static void checkInside(CodeModule module, Atmosphere.Place place, long length) throws Atmosphere.Unclear {
        if (module != null && place.region.equals("main")
                && place.offset + (place.hasPath() ? 8 : length) > module.image().length) {
            throw new Atmosphere.Unclear("a place past the end of the module", "outside");
        }
    }

    private static void checkModule(CodeModule module, Atmosphere.Place place, byte[] expect, byte[] data)
            throws Atmosphere.Unclear {
        if (module == null || place.hasPath() || !place.region.equals("main")) {
            return;
        }
        byte[] image = module.image();
        if (place.offset + expect.length > image.length) {
            throw new Atmosphere.Unclear("a place past the end of the module", "outside");
        }
        byte[] here = Arrays.copyOfRange(image, (int) place.offset, (int) place.offset + expect.length);
        if (!Arrays.equals(here, expect) && !Arrays.equals(here, data)
                && !module.excluded(place.offset, expect.length)) {
            throw new Atmosphere.Unclear("the module holds other bytes than `expect`", "expect-mismatch");
        }
    }

    private static List<Long> coreWords(Cheat c, String buildKey, CodeModule module, Choices chosen)
            throws Atmosphere.Unclear {
        if (!c.run().equals("on") && !c.run().equals("action")) {
            throw new Atmosphere.Unclear("run = \"" + c.run() + "\" has no Atmosph\u00E8re form",
                    c.run().equals("monitor") ? "atmosphere:monitor" : "atmosphere:run");
        }
        if (c.toggle() != null) {
            throw new Atmosphere.Unclear("toggle buttons have no Atmosph\u00E8re form", "atmosphere:toggle");
        }
        List<Atmosphere.Store> stores = new ArrayList<>();
        List<Atmosphere.Store> restores = new ArrayList<>();
        List<Model.Condition> conds = new ArrayList<>();
        for (Model.Op op : c.ops()) {
            if (op instanceof Model.Patch) {
                Model.Patch p = (Model.Patch) op;
                Atmosphere.Place place = place(p.at, buildKey, module);
                checkInside(module, place, p.expect.length);
                byte[] data;
                if (p.encode != null) {
                    data = Encode.arm64Imm(p.encode.insn, p.encode.width, p.encode.rd,
                            defaultOf(c, p.encode.value, chosen));
                    if (data == null) {
                        throw new Atmosphere.Unclear("a value its instruction cannot hold", "value-out-of-range");
                    }
                } else {
                    data = p.bytes;
                }
                checkModule(module, place, p.expect, data);
                stores.addAll(stores(place, data));
                restores.addAll(stores(place, p.expect));
            } else if (op instanceof Model.Write) {
                Model.Write w = (Model.Write) op;
                Atmosphere.Place place = place(w.at, buildKey, module);
                checkInside(module, place,
                        w.type.equals("bytes") ? ((byte[]) w.value).length : Values.typeSize(w.type));
                Object v = defaultOf(c, w.value, chosen);
                if (v instanceof Boolean) {
                    v = ((Boolean) v) ? 1L : 0L;
                }
                byte[] data;
                if (w.type.equals("bytes")) {
                    data = (byte[]) w.value;
                } else {
                    if (!Values.fits(w.type, v)) {
                        throw new Atmosphere.Unclear("a value its write cannot hold", "value-out-of-range");
                    }
                    data = Values.encode(w.type, v, false);
                }
                stores.addAll(stores(place, data));
            } else if (op instanceof Model.Override) {
                Model.Override o = (Model.Override) op;
                if (o.expect == null) {
                    throw new Atmosphere.Unclear("an override without expect", "atmosphere:override");
                }
                Object v = defaultOf(c, o.value, chosen);
                long bits = v == null ? 0 : Values.bits(v);
                byte[] stub = Encode.arm64Stub(o.action, bits);
                if (stub.length > o.expect.length) {
                    throw new Atmosphere.Unclear("an override whose stub is longer than expect", "atmosphere:override");
                }
                Atmosphere.Place place = place(o.function, buildKey, module);
                checkInside(module, place, stub.length);
                checkModule(module, place, Arrays.copyOf(o.expect, stub.length), stub);
                stores.addAll(stores(place, stub));
                restores.addAll(stores(place, Arrays.copyOf(o.expect, stub.length)));
            } else if (op instanceof Model.Condition) {
                conds.add((Model.Condition) op);
            } else if (op instanceof Model.Read) {
                throw new Atmosphere.Unclear("a monitor has no Atmosph\u00E8re form", "atmosphere:monitor");
            }
        }
        if (conds.size() > 1 || (!conds.isEmpty() && (!c.match().equals("all") || !c.trigger().equals("while")
                || c.interval() != 1))) {
            throw new Atmosphere.Unclear("conditions that Atmosph\u00E8re cannot say", "atmosphere:condition");
        }
        List<Long> body = new ArrayList<>();
        for (Atmosphere.Store s : stores) {
            body.addAll(Atmosphere.storeWords(s.place, s.width, s.value));
        }
        if (!conds.isEmpty()) {
            Model.Condition cond = conds.get(0);
            Atmosphere.Place place = place(cond.at, buildKey, module);
            checkInside(module, place, Arrays.asList("u8", "u16", "u32", "u64").contains(cond.type)
                    ? Values.typeSize(cond.type) : 1);
            Object value = defaultOf(c, cond.value, chosen);
            if (place.hasPath() || !Arrays.asList("u8", "u16", "u32", "u64").contains(cond.type)
                    || !Atmosphere.isConditionOp(cond.op) || !Values.isInt(value)) {
                throw new Atmosphere.Unclear("a condition that Atmosph\u00E8re cannot say", "atmosphere:condition");
            }
            List<Long> words = Atmosphere.conditionWords(place, Integer.parseInt(cond.type.substring(1)) / 8, cond.op,
                    Values.bits(value));
            words.addAll(body);
            words.add(0x20000000L);
            body = words;
        }
        List<Long> words;
        if (c.run().equals("action")) {
            boolean writes = false;
            for (Model.Op op : c.ops()) {
                writes |= op instanceof Model.Write;
            }
            if (c.hold() != null || writes) {
                throw new Atmosphere.Unclear("an action with writes or hold has no Atmosph\u00E8re form",
                        "atmosphere:action");
            }
            words = new ArrayList<>();
            words.add(0x80000000L | Atmosphere.keyMask(c.press()));
            words.addAll(body);
            words.add(0x20000000L);
        } else if (c.hold() != null) {
            words = new ArrayList<>();
            words.add(0x80000000L | Atmosphere.keyMask(c.hold()));
            words.addAll(body);
            if (!restores.isEmpty()) {
                words.add(0x21000000L);
                for (Atmosphere.Store s : restores) {
                    words.addAll(Atmosphere.storeWords(s.place, s.width, s.value));
                }
            }
            words.add(0x20000000L);
        } else {
            words = body;
        }
        if (words.isEmpty()) {
            throw new Atmosphere.Unclear("nothing to write", "atmosphere:empty");
        }
        return words;
    }

    /** The names of an original file's entries, sorted by how a regenerated file has their code. Immutable. */
    public static final class Comparison {
        private final List<String> same;
        private final List<String> differ;
        private final List<String> missing;

        Comparison(List<String> same, List<String> differ, List<String> missing) {
            this.same = Collections.unmodifiableList(new ArrayList<>(same));
            this.differ = Collections.unmodifiableList(new ArrayList<>(differ));
            this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
        }

        /** Returns the entries whose code the other file has the same. */
        public List<String> same() {
            return same;
        }

        /** Returns the entries whose code the other file has otherwise. */
        public List<String> differ() {
            return differ;
        }

        /** Returns the entries the other file lacks. */
        public List<String> missing() {
            return missing;
        }
    }

    /**
     * Compares the entries with code of two Atmosphère cheat files, by name. Code is the same
     * when its words are; with effects, also when it writes the same bytes at the same places
     * under the same buttons and condition, and puts the same back on release. Both files are
     * read as the converter reads them: lines that are not code are passed over, and an entry
     * that mixes code with other text is left out.
     *
     * @throws AtmosphereException for a name without its closing bracket
     */
    public static Comparison compare(String original, String regenerated, boolean effects) throws AtmosphereException {
        Map<List<Object>, Atmosphere.Entry> a = entryTable(original);
        Map<List<Object>, Atmosphere.Entry> b = entryTable(regenerated);
        List<String> same = new ArrayList<>();
        List<String> differ = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<List<Object>, Atmosphere.Entry> e : a.entrySet()) {
            Atmosphere.Entry other = b.get(e.getKey());
            String name = (String) e.getKey().get(0);
            if (other == null) {
                missing.add(name);
            } else if (Arrays.equals(other.wordsShared(), e.getValue().wordsShared())) {
                same.add(name);
            } else {
                List<Object> mine = effects ? effect(e.getValue()) : null;
                List<Object> theirs = effects ? effect(other) : null;
                if (effects && theirs != null && theirs.equals(mine)) {
                    same.add(name);
                } else {
                    differ.add(name);
                }
            }
        }
        return new Comparison(same, differ, missing);
    }

    private static Map<List<Object>, Atmosphere.Entry> entryTable(String text) throws AtmosphereException {
        Map<List<Object>, Atmosphere.Entry> out = new LinkedHashMap<>();
        for (Atmosphere.Entry e : Atmosphere.parse(text, new ArrayList<Atmosphere.Note>())) {
            if (e.wordsShared().length > 0 && !Atmosphere.isNoop(e) && e.broken().isEmpty()) {
                out.put(Arrays.<Object>asList(e.name(), e.master()), e);
            }
        }
        return out;
    }

    private static List<Object> effect(Atmosphere.Entry entry) {
        Atmosphere.Derived d;
        try {
            d = Atmosphere.derive(entry);
        } catch (Atmosphere.Unclear u) {
            return null;
        }
        Map<Atmosphere.Place, Integer> written = new HashMap<>();
        for (Atmosphere.Store s : d.stores) {
            byte[] data = s.data();
            for (int i = 0; i < data.length; i++) {
                written.put(s.place.plus(i), data[i] & 0xFF);
            }
        }
        Object cond = d.condition == null ? null : d.condition.key();
        return Arrays.<Object>asList(d.keys, cond, d.released != null, written);
    }
}
