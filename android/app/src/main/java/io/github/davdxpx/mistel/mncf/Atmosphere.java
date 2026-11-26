package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Atmosphère cheat files (the dmnt cheat code of the Switch): reading, decoding, understanding
 * and writing.
 *
 * <p>A file is a list of entries: [Name] or {Name} (the master entry) followed by opcodes, words
 * of 8 hexadecimal digits. The converter reads an entry's opcodes the way the cheat machine runs
 * them and finds the plain writes they make, with the button mask and the memory condition
 * around them, wherever the code says that plainly.
 */
public final class Atmosphere {

    static final String[] REGIONS = {"main", "heap", "alias", "aslr"};
    static final long MASK64 = -1L;
    private static final Pattern WORD = Pattern.compile("[0-9A-Fa-f]{8}");
    private static final int[] WIDTHS = {1, 2, 4, 8};
    private static final String[] KEY_NAMES = {"A", "B", "X", "Y", "LSTICK", "RSTICK", "L", "R", "ZL", "ZR", "PLUS",
        "MINUS", "DLEFT", "DUP", "DRIGHT", "DDOWN", "LSTICK_LEFT", "LSTICK_UP", "LSTICK_RIGHT", "LSTICK_DOWN",
        "RSTICK_LEFT", "RSTICK_UP", "RSTICK_RIGHT", "RSTICK_DOWN", "SL", "SR"};
    private static final Map<Integer, String> CONDITIONS = new HashMap<>();
    private static final Map<String, Integer> CONDITION_CODES = new HashMap<>();

    static {
        String[] ops = {">", ">=", "<", "<=", "==", "!="};
        for (int i = 0; i < ops.length; i++) {
            CONDITIONS.put(i + 1, ops[i]);
            CONDITION_CODES.put(ops[i], i + 1);
        }
    }

    private Atmosphere() {
    }

    /** The bit of each button, in the order of KEY_NAMES. */
    private static long keyBit(int index) {
        return 1L << index;
    }

    static int regionCode(String region) {
        for (int i = 0; i < REGIONS.length; i++) {
            if (REGIONS[i].equals(region)) {
                return i;
            }
        }
        return -1;
    }

    static boolean isConditionOp(String op) {
        return CONDITION_CODES.containsKey(op);
    }

    /** The opcodes do something the core operations of .mncf cannot say plainly. */
    static final class Unclear extends Exception {
        private static final long serialVersionUID = 1L;
        final String why;
        /** Back to Atmosphère: the stable reason (spec, section 15); null otherwise. */
        final String code;

        Unclear(String why) {
            this(why, null);
        }

        Unclear(String why, String code) {
            super(why, null, false, false);
            this.why = why;
            this.code = code;
        }
    }

    /** One entry of a file: its name, whether it is the master entry, its line and its code. Immutable. */
    public static final class Entry {
        private final String name;
        private final boolean master;
        private final int line;
        private final List<String> lines;
        private final long[] words;
        private final List<String> broken;

        Entry(String name, boolean master, int line, List<String> lines, long[] words) {
            this(name, master, line, lines, words, Collections.<String>emptyList());
        }

        Entry(String name, boolean master, int line, List<String> lines, long[] words, List<String> broken) {
            this.name = name;
            this.master = master;
            this.line = line;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.words = words.clone();
            this.broken = Collections.unmodifiableList(new ArrayList<>(broken));
        }

        /** Returns the name between the brackets, without spaces at its ends. */
        public String name() {
            return name;
        }

        /** Returns whether the entry is written {Name}: the master code that runs before the others. */
        public boolean master() {
            return master;
        }

        /** Returns the number of the line the entry's name is on, counted from 1. */
        public int line() {
            return line;
        }

        /** Returns the code lines as written, without spaces at their ends. */
        public List<String> lines() {
            return lines;
        }

        /** Returns the code's words. */
        public long[] words() {
            return words.clone();
        }

        long[] wordsShared() {
            return words;
        }

        /**
         * Returns the lines that mix code words with other text, when the file was read with
         * notes; the entry's code cannot be read then. Empty otherwise.
         */
        public List<String> broken() {
            return broken;
        }

        /** Returns the code lines, each followed by a line feed, or empty text when there is no code. */
        public String code() {
            if (lines.isEmpty()) {
                return "";
            }
            return String.join("\n", lines) + "\n";
        }
    }

    /** A line of a cheat file that is neither a name nor code, read leniently. */
    public static final class Note {
        private final int line;
        private final String text;

        /** Makes a note of a line, by its number counted from 1 and its text. */
        public Note(int line, String text) {
            this.line = line;
            this.text = text;
        }

        /** Returns the number of the line, counted from 1. */
        public int line() {
            return line;
        }

        /** Returns the text of the line, without spaces at its ends and without a name before it. */
        public String text() {
            return text;
        }

        @java.lang.Override
        public boolean equals(Object o) {
            return o instanceof Note && ((Note) o).line == line && ((Note) o).text.equals(text);
        }

        @java.lang.Override
        public int hashCode() {
            return line * 31 + text.hashCode();
        }

        @java.lang.Override
        public String toString() {
            return "(" + line + ", " + Py.repr(text) + ")";
        }
    }

    /**
     * Returns the entries of an Atmosphère cheat file.
     *
     * @throws AtmosphereException for a name without its closing bracket, a word that is not 8
     *     hexadecimal digits, or code before the first name
     */
    public static List<Entry> parse(String text) throws AtmosphereException {
        return parse(text, null);
    }

    /**
     * Returns the entries of an Atmosphère cheat file. Strictly, with null as notes, as
     * Atmosphère reads a file, any line that is neither a name nor code is an error. With a list
     * as notes, a line without any code word, or any line before the first name, goes into it
     * instead, and a line that mixes code words with other text goes into its entry's
     * {@link Entry#broken}.
     *
     * @throws AtmosphereException for a name without its closing bracket, and strictly also for a
     *     word that is not 8 hexadecimal digits or code before the first name
     */
    public static List<Entry> parse(String text, List<Note> notes) throws AtmosphereException {
        int from = 0;
        while (from < text.length() && text.charAt(from) == '\uFEFF') {
            from++;
        }
        List<String> raw = Py.splitLines(text.substring(from));
        List<Object[]> drafts = new ArrayList<>();
        Object[] current = null;
        for (int n = 1; n <= raw.size(); n++) {
            String line = Py.strip(raw.get(n - 1));
            while (!line.isEmpty()) {
                char c = line.charAt(0);
                if (c == '[' || c == '{') {
                    char close = c == '[' ? ']' : '}';
                    int end = line.indexOf(close);
                    if (end < 0) {
                        throw new AtmosphereException("line " + n + ": the name has no closing " + close);
                    }
                    current = new Object[] {Py.strip(line.substring(1, end)), c == '{', n, new ArrayList<String>(),
                        new ArrayList<Long>(), new ArrayList<String>()};
                    drafts.add(current);
                    line = Py.strip(line.substring(end + 1));
                    continue;
                }
                List<String> tokens = Py.splitSpace(line);
                List<String> bad = new ArrayList<>();
                for (String tok : tokens) {
                    if (!Py.matches(WORD, tok)) {
                        bad.add(tok);
                    }
                }
                if (!bad.isEmpty() && notes != null) {
                    if (bad.size() == tokens.size() || current == null) {
                        notes.add(new Note(n, line));
                    } else {
                        broken(current).add(line);
                    }
                    line = "";
                    continue;
                }
                if (!bad.isEmpty()) {
                    throw new AtmosphereException("line " + n + ": " + Py.repr(bad.get(0))
                            + " is not a word of 8 hexadecimal digits");
                }
                if (current == null) {
                    if (notes != null) {
                        notes.add(new Note(n, line));
                        line = "";
                        continue;
                    }
                    throw new AtmosphereException("line " + n + ": code before the first name");
                }
                lines(current).add(line);
                for (String tok : tokens) {
                    words(current).add(Long.parseLong(tok, 16));
                }
                line = "";
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (Object[] d : drafts) {
            List<Long> ws = words(d);
            long[] w = new long[ws.size()];
            for (int i = 0; i < w.length; i++) {
                w[i] = ws.get(i);
            }
            entries.add(new Entry((String) d[0], (Boolean) d[1], (Integer) d[2], lines(d), w, broken(d)));
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static List<String> lines(Object[] draft) {
        return (List<String>) draft[3];
    }

    @SuppressWarnings("unchecked")
    private static List<Long> words(Object[] draft) {
        return (List<Long>) draft[4];
    }

    @SuppressWarnings("unchecked")
    private static List<String> broken(Object[] draft) {
        return (List<String>) draft[5];
    }

    // Opcodes

    /** One opcode: its kind, its words and its fields. A field may be null where the opcode has none. */
    static final class Op {
        final String kind;
        final long[] words;
        final Map<String, Long> f;

        Op(String kind, long[] words, Map<String, Long> f) {
            this.kind = kind;
            this.words = words;
            this.f = f;
        }

        long get(String field) {
            Long v = f.get(field);
            return v == null ? 0 : v;
        }
    }

    /** The i-th hexadecimal digit of a word, from the left. */
    static long nib(long w, int i) {
        return (w >> (28 - 4 * i)) & 0xF;
    }

    private static Map<String, Long> fields(Object... namesAndValues) {
        Map<String, Long> f = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            Object v = namesAndValues[i + 1];
            f.put((String) namesAndValues[i], v == null ? null : v instanceof Boolean
                    ? (((Boolean) v) ? 1L : 0L) : ((Number) v).longValue());
        }
        return f;
    }

    private static boolean isWidth(long width) {
        for (int w : WIDTHS) {
            if (w == width) {
                return true;
            }
        }
        return false;
    }

    /** Reads words one opcode at a time. */
    private static final class Reader {
        final long[] words;
        int i;

        Reader(long[] words) {
            this.words = words;
        }

        long[] take(int n) throws Unclear {
            if (i + n > words.length) {
                throw new Unclear("the code ends inside an opcode");
            }
            long[] got = Arrays.copyOfRange(words, i, i + n);
            i += n;
            return got;
        }
    }

    /** The opcodes in a list of words. */
    static List<Op> decode(long[] words) throws Unclear {
        List<Op> ops = new ArrayList<>();
        Reader r = new Reader(words);
        while (r.i < words.length) {
            long w = words[r.i];
            long t = nib(w, 0);
            if (t == 0x0) {
                long width = nib(w, 1);
                if (!isWidth(width)) {
                    throw new Unclear("a store of width " + width);
                }
                long[] ws = r.take(width == 8 ? 4 : 3);
                long value = width == 8 ? ws[2] << 32 | ws[3] : ws[2];
                ops.add(new Op("store", ws, fields("width", width, "mem", nib(w, 2), "reg", nib(w, 3),
                        "offset", (w & 0xFF) << 32 | ws[1], "value", value)));
            } else if (t == 0x1) {
                long width = nib(w, 1);
                if (!isWidth(width)) {
                    throw new Unclear("a condition of width " + width);
                }
                long[] ws = r.take(width == 8 ? 4 : 3);
                long value = width == 8 ? ws[2] << 32 | ws[3] : ws[2];
                ops.add(new Op("begin-cond", ws, fields("width", width, "mem", nib(w, 2), "cond", nib(w, 3),
                        "use_reg", nib(w, 4), "reg", nib(w, 5), "offset", (w & 0xFF) << 32 | ws[1], "value", value)));
            } else if (t == 0x2) {
                ops.add(new Op("end-cond", r.take(1), fields("else", nib(w, 1) == 1)));
            } else if (t == 0x3) {
                if (nib(w, 1) == 0) {
                    ops.add(new Op("loop", r.take(2), fields("start", true, "reg", nib(w, 3))));
                } else {
                    ops.add(new Op("loop", r.take(1), fields("start", false, "reg", nib(w, 3))));
                }
            } else if (t == 0x4) {
                long[] ws = r.take(3);
                ops.add(new Op("load-const", ws, fields("reg", nib(w, 3), "value", ws[1] << 32 | ws[2])));
            } else if (t == 0x5) {
                long[] ws = r.take(2);
                ops.add(new Op("load-mem", ws, fields("width", nib(w, 1), "mem", nib(w, 2), "reg", nib(w, 3),
                        "from", nib(w, 4), "oreg", nib(w, 5), "offset", (w & 0xFF) << 32 | ws[1])));
            } else if (t == 0x6) {
                long[] ws = r.take(3);
                ops.add(new Op("store-reg-imm", ws, fields("width", nib(w, 1), "reg", nib(w, 3), "inc", nib(w, 4),
                        "use_oreg", nib(w, 5), "oreg", nib(w, 6), "value", ws[1] << 32 | ws[2])));
            } else if (t == 0x7) {
                long[] ws = r.take(2);
                ops.add(new Op("arith", ws, fields("width", nib(w, 1), "reg", nib(w, 3), "op", nib(w, 4),
                        "value", ws[1])));
            } else if (t == 0x8) {
                ops.add(new Op("keys", r.take(1), fields("mask", w & 0x0FFFFFFF)));
            } else if (t == 0x9) {
                long width = nib(w, 1);
                if (nib(w, 5) == 1) {
                    long[] ws = r.take(width == 8 ? 3 : 2);
                    long value = width == 8 ? ws[1] << 32 | ws[2] : ws[1];
                    ops.add(new Op("arith-reg", ws, fields("width", width, "op", nib(w, 2), "dst", nib(w, 3),
                            "src", nib(w, 4), "imm", value)));
                } else {
                    ops.add(new Op("arith-reg", r.take(1), fields("width", width, "op", nib(w, 2), "dst", nib(w, 3),
                            "src", nib(w, 4), "src2", nib(w, 6))));
                }
            } else if (t == 0xA) {
                long kind = nib(w, 5);
                long[] ws = r.take(kind == 2 || kind == 4 || kind == 5 ? 2 : 1);
                Long imm = ws.length == 2 ? (w & 0xF) << 32 | ws[1] : null;
                ops.add(new Op("store-reg", ws, fields("width", nib(w, 1), "src", nib(w, 2), "reg", nib(w, 3),
                        "inc", nib(w, 4), "kind", kind, "mem", nib(w, 6), "oreg", nib(w, 7), "imm", imm)));
            } else if (t == 0xC) {
                long sub = nib(w, 1);
                if (sub == 0) {
                    long kind = nib(w, 5);
                    int n;
                    if (kind == 0 || kind == 2) {
                        n = 2;
                    } else if (kind == 1 || kind == 3 || kind == 5) {
                        n = 1;
                    } else if (kind == 4) {
                        n = nib(w, 2) == 8 ? 3 : 2;
                    } else {
                        throw new Unclear("a register condition of an unknown kind");
                    }
                    ops.add(new Op("reg-cond", r.take(n), fields()));
                } else if (sub == 4) {
                    long[] ws = r.take(3);
                    ops.add(new Op("keys", ws, fields("mask", ws[1] << 32 | ws[2], "repeat", nib(w, 2))));
                } else if (sub == 1 || sub == 2 || sub == 3) {
                    ops.add(new Op(sub == 1 ? "save" : sub == 2 ? "save-mask" : "static-reg", r.take(1), fields()));
                } else {
                    throw new Unclear(String.format("code type C%X", sub));
                }
            } else if (t == 0xF) {
                long sub = nib(w, 2);
                if (nib(w, 1) != 0xF || (sub != 0 && sub != 1)) {
                    throw new Unclear(String.format("code type %03X", w >> 20));
                }
                ops.add(new Op(sub == 0 ? "pause" : "resume", r.take(1), fields()));
            } else {
                throw new Unclear(String.format("code type %X", t));
            }
        }
        return ops;
    }

    // What the opcodes do

    /** A place the code writes: a region of Appendix A and an offset, maybe followed by a pointer path. */
    static final class Place {
        final String region;
        final long offset;
        final long[] path;

        Place(String region, long offset, long[] path) {
            this.region = region;
            this.offset = offset;
            this.path = path;
        }

        Place(String region, long offset) {
            this(region, offset, null);
        }

        boolean hasPath() {
            return path != null && path.length > 0;
        }

        Place plus(long n) {
            if (hasPath()) {
                long[] p = path.clone();
                p[p.length - 1] = p[p.length - 1] + n;
                return new Place(region, offset, p);
            }
            return new Place(region, offset + n);
        }

        Place read() {
            long[] p = path == null ? new long[1] : Arrays.copyOf(path, path.length + 1);
            return new Place(region, offset, p);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Place)) {
                return false;
            }
            Place p = (Place) o;
            return region.equals(p.region) && offset == p.offset
                    && (path == null ? p.path == null : p.path != null && Arrays.equals(path, p.path));
        }

        @Override
        public int hashCode() {
            return region.hashCode() * 31 + Long.valueOf(offset).hashCode() * 7 + (path == null ? 0 : Arrays.hashCode(path));
        }
    }

    /** A plain store: width bytes of value at a place. */
    static final class Store {
        final Place place;
        final int width;
        final long value;

        Store(Place place, int width, long value) {
            this.place = place;
            this.width = width;
            this.value = value;
        }

        /** The value's bytes, little-endian, width of them. */
        byte[] data() {
            byte[] out = new byte[width];
            for (int i = 0; i < width && i < 8; i++) {
                out[i] = (byte) (value >>> (8 * i));
            }
            return out;
        }
    }

    /** A memory condition around the stores. */
    static final class Cond {
        final Place place;
        final int width;
        final String op;
        final long value;

        Cond(Place place, int width, String op, long value) {
            this.place = place;
            this.width = width;
            this.op = op;
            this.value = value;
        }

        List<Object> key() {
            return Arrays.<Object>asList(place, width, op, value);
        }
    }

    /** What an entry does. */
    static final class Derived {
        List<Store> stores = new ArrayList<>();
        long keys;
        Cond condition;
        List<Store> released;               // the stores of an else branch of the button block
        final Set<Long> reads = new HashSet<>();     // registers read before the entry wrote them
        final Set<Long> writes = new HashSet<>();    // registers the entry writes
    }

    /** What the entry does, as plain stores. */
    static Derived derive(Entry entry) throws Unclear {
        return derive(entry.wordsShared());
    }

    static Derived derive(long[] words) throws Unclear {
        List<Op> ops = decode(words);
        Derived out = new Derived();
        List<Op> body = new ArrayList<>(ops);
        if (!body.isEmpty() && body.get(0).kind.equals("keys")) {
            if (body.get(0).get("repeat") != 0) {
                throw new Unclear("a button block that repeats");
            }
            out.keys = body.get(0).get("mask");
            body = new ArrayList<>(body.subList(1, body.size()));
            if (body.isEmpty() || !body.get(body.size() - 1).kind.equals("end-cond")) {
                throw new Unclear("a button block that does not end");
            }
            body = new ArrayList<>(body.subList(0, body.size() - 1));
            int elseAt = -1;
            for (int i = 0; i < body.size(); i++) {
                Op op = body.get(i);
                if (op.kind.equals("end-cond") && op.get("else") != 0 && depth(body.subList(0, i)) == 0) {
                    elseAt = i;
                    break;
                }
            }
            if (elseAt >= 0) {
                out.released = plain(body.subList(elseAt + 1, body.size()), out);
                body = new ArrayList<>(body.subList(0, elseAt));
            }
        }
        if (!body.isEmpty() && body.get(0).kind.equals("begin-cond")) {
            Op c = body.get(0);
            if (c.get("use_reg") != 0 || c.get("mem") >= REGIONS.length || !CONDITIONS.containsKey((int) c.get("cond"))) {
                throw new Unclear("a memory condition through a register");
            }
            Op last = body.get(body.size() - 1);
            if (!last.kind.equals("end-cond") || last.get("else") != 0) {
                throw new Unclear("a memory condition that does not end plainly");
            }
            out.condition = new Cond(new Place(REGIONS[(int) c.get("mem")], c.get("offset")), (int) c.get("width"),
                    CONDITIONS.get((int) c.get("cond")), c.get("value"));
            body = new ArrayList<>(body.subList(1, body.size() - 1));
        }
        out.stores = plain(body, out);
        if (out.released != null) {
            if (out.condition != null) {
                throw new Unclear("a button block with an else branch and a condition");
            }
            Set<Place> held = new HashSet<>();
            for (Store s : out.stores) {
                for (int i = 0; i < s.width; i++) {
                    held.add(s.place.plus(i));
                }
            }
            for (Store s : out.released) {
                for (int i = 0; i < s.width; i++) {
                    if (!held.contains(s.place.plus(i))) {
                        throw new Unclear("an else branch that writes places the button block does not");
                    }
                }
            }
        }
        return out;
    }

    private static int depth(List<Op> ops) {
        int d = 0;
        for (Op op : ops) {
            if (op.kind.equals("begin-cond") || op.kind.equals("keys") || op.kind.equals("reg-cond")) {
                d++;
            } else if (op.kind.equals("end-cond") && op.get("else") == 0) {
                d--;
            }
        }
        return d;
    }

    /** A register's value: a constant, or a place. */
    private static final class Val {
        final Place place;
        final long n;

        Val(Place place, long n) {
            this.place = place;
            this.n = n;
        }

        static Val constant(long n) {
            return new Val(null, n);
        }

        boolean isConst() {
            return place == null;
        }
    }

    /** The registers of one run of opcodes. */
    private static final class Regs {
        final Map<Long, Val> regs = new HashMap<>();
        final Derived out;

        Regs(Derived out) {
            this.out = out;
        }

        Val get(long r) {
            if (!regs.containsKey(r)) {
                if (!out.writes.contains(r)) {
                    out.reads.add(r);
                }
                return Val.constant(0);
            }
            return regs.get(r);
        }

        void put(long r, Val v) {
            regs.put(r, v);
            out.writes.add(r);
        }
    }

    private static String region(long mem) {
        return mem >= 0 && mem < REGIONS.length ? REGIONS[(int) mem] : null;
    }

    /** The stores of a sequence of opcodes without blocks, following the registers. */
    private static List<Store> plain(List<Op> ops, Derived out) throws Unclear {
        Regs regs = new Regs(out);
        List<Store> stores = new ArrayList<>();
        for (Op op : ops) {
            switch (op.kind) {
                case "store": {
                    Val base = regs.get(op.get("reg"));
                    if (!base.isConst() || region(op.get("mem")) == null) {
                        throw new Unclear("a store whose offset register holds an address");
                    }
                    stores.add(new Store(new Place(region(op.get("mem")), op.get("offset") + base.n),
                            (int) op.get("width"), op.get("value")));
                    break;
                }
                case "load-const":
                    regs.put(op.get("reg"), Val.constant(op.get("value")));
                    break;
                case "load-mem": {
                    if (op.get("width") != 8) {
                        throw new Unclear("a load of a value that is not a pointer");
                    }
                    if (op.get("from") == 0) {
                        if (region(op.get("mem")) == null) {
                            throw new Unclear("a load from an unknown region");
                        }
                        regs.put(op.get("reg"), new Val(new Place(region(op.get("mem")), op.get("offset")).read(), 0));
                    } else if (op.get("from") == 1) {
                        Val src = regs.get(op.get("reg"));
                        if (src.isConst()) {
                            throw new Unclear("a load from an address the code computed");
                        }
                        regs.put(op.get("reg"), new Val(src.place.plus(op.get("offset")).read(), 0));
                    } else {
                        throw new Unclear("a load through an offset register");
                    }
                    break;
                }
                case "arith": {
                    long o = op.get("op");
                    if (o != 0 && o != 1) {
                        throw new Unclear("arithmetic other than adding or subtracting");
                    }
                    regs.put(op.get("reg"), add(regs.get(op.get("reg")), o == 0 ? op.get("value") : -op.get("value")));
                    break;
                }
                case "arith-reg": {
                    Val src = regs.get(op.get("src"));
                    Val other = op.f.containsKey("imm") ? Val.constant(op.get("imm")) : regs.get(op.get("src2"));
                    long o = op.get("op");
                    if (o == 9) {
                        regs.put(op.get("dst"), src);
                    } else if ((o == 0 || o == 1) && other.isConst()) {
                        regs.put(op.get("dst"), add(src, o == 0 ? other.n : -other.n));
                    } else if (o == 0 && src.isConst()) {
                        regs.put(op.get("dst"), add(other, src.n));
                    } else {
                        throw new Unclear("arithmetic on registers that the core operations cannot say");
                    }
                    break;
                }
                case "store-reg-imm": {
                    Val target = regs.get(op.get("reg"));
                    if (op.get("use_oreg") != 0) {
                        target = add(target, constant(regs.get(op.get("oreg"))));
                    }
                    if (target.isConst()) {
                        throw new Unclear("a store to an address the code computed");
                    }
                    if (!isWidth(op.get("width"))) {
                        throw new Unclear("a store of width " + op.get("width"));
                    }
                    stores.add(new Store(target.place, (int) op.get("width"), op.get("value")));
                    if (op.get("inc") != 0) {
                        regs.put(op.get("reg"), add(regs.get(op.get("reg")), op.get("width")));
                    }
                    break;
                }
                case "store-reg": {
                    Val value = regs.get(op.get("src"));
                    if (!value.isConst()) {
                        throw new Unclear("a store of a value the code computed");
                    }
                    long k = op.get("kind");
                    Val target;
                    if (k == 0) {
                        target = regs.get(op.get("reg"));
                    } else if (k == 1) {
                        target = add(regs.get(op.get("reg")), constant(regs.get(op.get("oreg"))));
                    } else if (k == 2) {
                        target = add(regs.get(op.get("reg")), op.get("imm"));
                    } else if ((k == 3 || k == 4 || k == 5) && region(op.get("mem")) != null) {
                        long off = (k == 3 || k == 5 ? constant(regs.get(op.get("oreg"))) : 0) + op.get("imm");
                        target = new Val(new Place(region(op.get("mem")), off), 0);
                    } else {
                        throw new Unclear("a register store of an unknown kind");
                    }
                    if (target.isConst()) {
                        throw new Unclear("a store to an address the code computed");
                    }
                    if (!isWidth(op.get("width"))) {
                        throw new Unclear("a store of width " + op.get("width"));
                    }
                    stores.add(new Store(target.place, (int) op.get("width"), value.n));
                    if (op.get("inc") != 0) {
                        regs.put(op.get("reg"), add(regs.get(op.get("reg")), op.get("width")));
                    }
                    break;
                }
                default:
                    throw new Unclear(unclearKind(op.kind));
            }
        }
        return stores;
    }

    private static String unclearKind(String kind) {
        switch (kind) {
            case "begin-cond":
                return "a memory condition inside the code";
            case "end-cond":
                return "a block inside the code";
            case "keys":
                return "a button block inside the code";
            case "loop":
                return "a loop";
            case "reg-cond":
                return "a register condition";
            case "save":
            case "save-mask":
                return "saved registers";
            case "static-reg":
                return "static registers";
            case "pause":
            case "resume":
                return "pausing the game";
            default:
                return kind;
        }
    }

    private static long constant(Val v) throws Unclear {
        if (!v.isConst()) {
            throw new Unclear("an offset register that holds an address");
        }
        return v.n;
    }

    private static Val add(Val v, long n) {
        if (v.isConst()) {
            return Val.constant(v.n + n);
        }
        return new Val(v.place.plus(n), 0);
    }

    /** Returns the buttons of a key mask joined by "+", or null when the mask is empty or has a bit without a name. */
    public static String keysToButtons(long mask) {
        List<String> names = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < KEY_NAMES.length; i++) {
            if ((mask & keyBit(i)) != 0) {
                names.add(KEY_NAMES[i]);
                sum += keyBit(i);
            }
        }
        if (names.isEmpty() || sum != mask) {
            return null;
        }
        return String.join("+", names);
    }

    /**
     * Returns the key mask of buttons joined by "+".
     *
     * @throws IllegalArgumentException for a button that has no Atmosphère key
     */
    public static long buttonsToKeys(String text) {
        try {
            return keyMask(text);
        } catch (Unclear u) {
            throw new IllegalArgumentException(u.why);
        }
    }

    /** The key mask of buttons joined by "+"; Unclear for a name without an Atmosphère bit. */
    static long keyMask(String text) throws Unclear {
        List<String> names = Py.split(text, '+');
        for (String name : names) {
            if (!Arrays.asList(KEY_NAMES).contains(name)) {
                throw new Unclear("the buttons " + text + " have no Atmosph\u00E8re key mask");
            }
        }
        long mask = 0;
        for (String name : names) {
            mask += keyBit(Arrays.asList(KEY_NAMES).indexOf(name));
        }
        return mask;
    }

    /** Whether an entry has no code, or only ends of blocks: a heading such as [--- Items ---]. */
    static boolean isNoop(Entry entry) {
        try {
            for (Op op : decode(entry.wordsShared())) {
                if (!op.kind.equals("end-cond")) {
                    return false;
                }
            }
            return true;
        } catch (Unclear u) {
            return false;
        }
    }

    // Writing

    private static void valueWords(List<Long> words, int width, long value) {
        if (width == 8) {
            words.add((value >>> 32) & 0xFFFFFFFFL);
        }
        words.add(value & 0xFFFFFFFFL);
    }

    /** The opcodes that write value at a place: a type 0 store, or for a pointer path the loads and a type 6 store. */
    static List<Long> storeWords(Place place, int width, long value) throws Unclear {
        if ((place.offset >>> 40) != 0) {
            throw new Unclear("an offset beyond the 40 bits of a store");
        }
        long region = regionCode(place.region);
        List<Long> words = new ArrayList<>();
        if (!place.hasPath()) {
            words.add((long) width << 24 | region << 20 | (place.offset >>> 32) & 0xFF);
            words.add(place.offset & 0xFFFFFFFFL);
            valueWords(words, width, value);
            return words;
        }
        long reg = 0xF;
        words.add(0x58000000L | region << 20 | reg << 16 | (place.offset >>> 32) & 0xFF);
        words.add(place.offset & 0xFFFFFFFFL);
        long[] path = place.path;
        for (int i = 0; i < path.length - 1; i++) {
            words.addAll(addWords(reg, path[i], true));
        }
        long last = path[path.length - 1];
        if (last != 0) {
            words.addAll(addWords(reg, last, false));
        }
        words.add(0x60000000L | (long) width << 24 | reg << 16);
        words.add((value >>> 32) & 0xFFFFFFFFL);
        words.add(value & 0xFFFFFFFFL);
        return words;
    }

    /** The opcodes that add a path step to the register, and with thenLoad read the pointer there. */
    private static List<Long> addWords(long reg, long step, boolean thenLoad) {
        long signed = step;
        List<Long> words = new ArrayList<>();
        if (thenLoad && signed >= 0 && signed < (1L << 40)) {
            words.add(0x58001000L | reg << 16 | (signed >> 32) & 0xFF);
            words.add(signed & 0xFFFFFFFFL);
            return words;
        }
        long magnitude = Math.abs(signed);       // Long.MIN_VALUE stays negative: a 64-bit add below
        if (magnitude > 0 && magnitude < (1L << 32)) {
            long op = signed > 0 ? 0 : 1;
            words.add(0x78000000L | reg << 16 | op << 12);
            words.add(magnitude);
        } else if (signed != 0) {                // a 64-bit add: type 9, register plus a value
            words.add(0x98000100L | reg << 16 | reg << 12);
            words.add((step >>> 32) & 0xFFFFFFFFL);
            words.add(step & 0xFFFFFFFFL);
        }
        if (thenLoad) {
            words.add(0x58001000L | reg << 16);
            words.add(0L);
        }
        return words;
    }

    /** The opcodes of a memory condition. */
    static List<Long> conditionWords(Place place, int width, String op, long value) throws Unclear {
        if ((place.offset >>> 40) != 0) {
            throw new Unclear("an offset beyond the 40 bits of a condition");
        }
        long region = regionCode(place.region);
        List<Long> words = new ArrayList<>();
        words.add(0x10000000L | (long) width << 24 | region << 20 | (long) CONDITION_CODES.get(op) << 16
                | (place.offset >>> 32) & 0xFF);
        words.add(place.offset & 0xFFFFFFFFL);
        valueWords(words, width, value);
        return words;
    }

    /** Code lines: one opcode per line, as cheat files write them. */
    static List<String> formatWords(List<Long> words) throws Unclear {
        long[] w = new long[words.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = words.get(i);
        }
        List<String> lines = new ArrayList<>();
        for (Op op : decode(w)) {
            StringBuilder b = new StringBuilder();
            for (long x : op.words) {
                if (b.length() > 0) {
                    b.append(' ');
                }
                b.append(String.format("%08X", x));
            }
            lines.add(b.toString());
        }
        return lines;
    }

    /** One entry to write: its name, whether it is the master entry, and its code lines. */
    static final class Out {
        final String name;
        final boolean master;
        final List<String> lines;

        Out(String name, boolean master, List<String> lines) {
            this.name = name;
            this.master = master;
            this.lines = lines;
        }
    }

    /** The text of an Atmosphère cheat file. */
    static String writeFile(List<Out> entries) {
        List<String> out = new ArrayList<>();
        for (Out e : entries) {
            out.add(e.master ? "{" + e.name + "}" : "[" + e.name + "]");
            out.addAll(e.lines);
            out.add("");
        }
        return String.join("\n", out);
    }

    /**
     * The registers an entry may write, for an entry that derive cannot read: all of them when
     * its opcodes are not even known.
     */
    static Set<Long> registersWritten(Entry entry) {
        Set<Long> all = new LinkedHashSet<>();
        for (long r = 0; r < 16; r++) {
            all.add(r);
        }
        List<Op> ops;
        try {
            ops = decode(entry.wordsShared());
        } catch (Unclear u) {
            return all;
        }
        Set<Long> regs = new LinkedHashSet<>();
        for (Op op : ops) {
            if (op.kind.equals("load-const") || op.kind.equals("load-mem") || op.kind.equals("arith")
                    || (op.kind.equals("loop") && op.get("start") != 0)) {
                regs.add(op.get("reg"));
            } else if (op.kind.equals("arith-reg")) {
                regs.add(op.get("dst"));
            } else if ((op.kind.equals("store-reg-imm") || op.kind.equals("store-reg")) && op.get("inc") != 0) {
                regs.add(op.get("reg"));
            } else if (op.kind.equals("save") || op.kind.equals("save-mask") || op.kind.equals("static-reg")) {
                return all;
            }
        }
        return regs;
    }

    /** The offsets of every plain store into the main module in an entry, whatever blocks are around them. */
    static Set<Long> mainOffsets(Entry entry) {
        Set<Long> out = new HashSet<>();
        try {
            for (Op op : decode(entry.wordsShared())) {
                if (op.kind.equals("store") && op.get("mem") == 0) {
                    out.add(op.get("offset"));
                }
            }
        } catch (Unclear u) {
            return new HashSet<>();
        }
        return out;
    }
}
