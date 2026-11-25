package dev.peanuts.mods;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One Atmosphère cheat file read for the game's page and the in-game tab (SPEC-mod-menu.md, Package B 4 and 5). No Android classes: CheatLoaderJvmTest runs it.
 * <p>
 * The entries are found as the host finds them (dm/host/hle_cheats.c parse_file, dev.nxnative.android.Cheats.parse): a line that starts with [Name] or {Name}
 * starts an entry, words of 8 hex digits (or runs of them) are its code. What each entry is, here:
 * <ul>
 * <li>[Name] with code: a cheat. [Name] without code, or with only end / else words (cheatslips' [Item/Misc] over 20000000, its closing twin over 20000001): a
 *     group heading; the same heading again closes the group. EdiZon's [--SectionStart:X--] / [--SectionEnd:X--] open and close a group too.</li>
 * <li>{Name}: a master code, always on.</li>
 * <li>A label that is a link or a credit ([www.cheatslips.com], [credits: GBATEMP], "created by"): the source's credit. A label with the title or build IDs in it
 *     (TID=, BID=): the file's title, kept apart.</li>
 * <li>An entry whose name says it restores code (cheatslips' "Restore Code (Use after unchecking any cheats below)"): hidden. The host puts a code cheat's
 *     original bytes back when it is turned off (Package A), so the entry is left out of the merged file too.</li>
 * </ul>
 * Each cheat gets a kind from its opcodes ({@link #classify}): Code (it writes main's code), Data, Value (one plain number written to data, which the in-game tab
 * offers to change), or Not supported (an opcode, width, condition or operand the host's VM does not run: hle_cheats.c ins_dec). The key-press conditionals
 * (opcode 8, C4) give its "Hold" buttons.
 */
public final class CheatFile {
    private CheatFile() {}

    public static final int NAME_MAX = 127;   // a header's name, as the host cuts it (CHEAT_NAME_MAX)

    /** What a cheat does, from its opcodes. label: the chip's word */
    public enum Kind {
        CODE("Code"), DATA("Data"), VALUE("Value"), UNSUPPORTED("Not supported");
        public final String label;
        Kind(String label) { this.label = label; }
    }

    /** One cheat or master of the file */
    public static final class Cheat {
        public final String name;          // as the file has it
        public final String key;           // unique in the file: the name, "Name #2" for a later entry of the same name (the host's cheat_key)
        public final String group;         // its group heading, "" none
        public final boolean master;
        public final List<String> lines;   // its code lines, words as written, for the merged file
        public final Kind kind;
        public final List<String> hold;    // each key-press condition's buttons ("ZL", "Up + RS"), in order, no repeats; empty: none
        public final String why;           // Not supported: which part ("opcode FF0", "width"); else null
        public final int codePlaces;       // the places in main's code it writes
        public final boolean pointers;     // reads a pointer chain (opcode 5)
        public final int valueWord, valueWidth;   // Value: the index of the number's first word in words, and its width in bytes; else -1, 0
        public final long[] words;

        Cheat(String name, String key, String group, boolean master, List<String> lines, long[] words, Shape s) {
            this.name = name; this.key = key; this.group = group; this.master = master; this.lines = Collections.unmodifiableList(lines); this.words = words;
            kind = s.kind; hold = Collections.unmodifiableList(s.hold); why = s.why; codePlaces = s.codePlaces; pointers = s.pointers;
            valueWord = s.valueWord; valueWidth = s.valueWidth;
        }

        public boolean switchable() { return !master && kind != Kind.UNSUPPORTED; }

        /** The hold chip's words: "Hold ZL", "Hold B or ZL"; null without a key-press condition */
        public String holdLabel() { return hold.isEmpty() ? null : "Hold " + String.join(" or ", hold); }

        /** Value: the number as written (unsigned, its width), else -1 */
        public long value() {
            if (valueWord < 0) return -1;
            if (valueWidth == 8) return (words[valueWord] << 32) | words[valueWord + 1];
            long v = words[valueWord];
            return valueWidth == 4 ? v : v & ((1L << (8 * valueWidth)) - 1);
        }

        @Override public String toString() { return (master ? "{" + key + "}" : key) + "/" + kind.label + (hold.isEmpty() ? "" : "/" + holdLabel()) + (group.isEmpty() ? "" : "@" + group); }
    }

    /** The file as the page shows it */
    public static final class Parsed {
        public final List<Cheat> cheats = new ArrayList<>();     // the cheats, file order, Not supported ones too
        public final List<Cheat> masters = new ArrayList<>();
        public final List<String> groups = new ArrayList<>();    // group headings, in first-seen order
        public final List<String> credits = new ArrayList<>();   // credit and link labels, as written
        public final List<String> restores = new ArrayList<>();  // the hidden "Restore Code" entries
        public final List<String> titles = new ArrayList<>();    // the file's title labels (TID=, BID=)
        public String build;                                     // a first line "build <ID>": its first 16 hex digits, upper case; else null

        /** The cheats a player may switch (not the Not supported ones) */
        public int switchable() { int n = 0; for (Cheat c : cheats) if (c.switchable()) n++; return n; }
        /** The cheats that are Not supported */
        public int unsupported() { return cheats.size() - switchable(); }
        /** Whether the file holds any code at all (a cheat or a master) */
        public boolean any() { return !cheats.isEmpty() || !masters.isEmpty(); }
    }

    // ---- The lines ----

    /** A header ([Name] or {Name} after spaces): {open char, name, the rest after the header}; null when the line has none or its name is empty */
    static String[] header(String line) {
        String t = line.replaceFirst("^[ \t﻿]+", "");
        if (t.isEmpty() || (t.charAt(0) != '[' && t.charAt(0) != '{')) return null;
        int e = t.indexOf(t.charAt(0) == '[' ? ']' : '}');
        if (e <= 1) return null;
        String name = t.substring(1, e);
        if (name.length() > NAME_MAX) name = name.substring(0, NAME_MAX);
        return new String[] {t.substring(0, 1), name, t.substring(e + 1)};
    }

    /** Whether a line is code words only: tokens of hex digits whose length is a multiple of 8, at least one */
    static boolean codeLine(String s) {
        String[] tok = s.trim().split("[ \t\r]+");
        if (tok.length == 0 || tok[0].isEmpty()) return false;
        for (String t : tok) if (t.length() % 8 != 0 || !t.matches("[0-9A-Fa-f]+")) return false;
        return true;
    }

    private static void words(String line, List<Long> out) {
        for (String t : line.trim().split("[ \t\r]+")) for (int i = 0; i + 8 <= t.length(); i += 8) out.add(Long.parseLong(t.substring(i, i + 8), 16));
    }

    private static final Pattern LINK = Pattern.compile("(?i)(www\\.|https?://|\\b[a-z0-9-]+\\.(com|net|org|io|gg|de|fr|jp|co|me|xyz)\\b)");
    private static final Pattern CREDIT = Pattern.compile("(?i)(credit|created by|made by|cheats? by|coded? by|ported by|thanks|author|enjoy)");
    private static final Pattern TITLE = Pattern.compile("(?i)(\\b(tid|bid|title ?id|build ?id)\\s*[=:])");
    private static final Pattern RESTORE = Pattern.compile("(?i)(restore\\s+(the\\s+)?(original\\s+)?codes?|original\\s+codes?|codes?\\s+restore)");
    private static final Pattern BUILD_LINE = Pattern.compile("(?i)^build\\s+([0-9a-f]{16})[0-9a-f]*$");

    /** A credit label as the page shows it: without a leading "credits:" (the line says Credit already) */
    public static String creditWords(String label) {
        String t = label.replaceFirst("(?i)^\\s*(credits?|thanks)\\s*(to)?\\s*[:=-]?\\s*", "").trim();
        return t.isEmpty() ? label.trim() : t;
    }

    /** The credits of a file as one line ("www.example.org, Example Team") */
    public static String credit(Parsed p) {
        List<String> out = new ArrayList<>();
        for (String c : p.credits) { String w = creditWords(c); if (!out.contains(w)) out.add(w); }
        return String.join(", ", out);
    }

    /** Whether a label is a link or a credit (the source's credit line) */
    static boolean credit(String name) { return LINK.matcher(name).find() || CREDIT.matcher(name).find(); }

    /** Whether an entry's name says it restores the game's code */
    public static boolean restore(String name) { return RESTORE.matcher(name).find(); }

    /** EdiZon's marker: 1 start, 2 end, 0 none; out[0] gets the group's name */
    static int marker(String name, String[] out) {
        if (!name.endsWith("--")) return 0;
        int kind = name.startsWith("--SectionStart:") ? 1 : name.startsWith("--SectionEnd:") ? 2 : 0;
        if (kind == 0) return 0;
        int s = kind == 1 ? 15 : 13;
        if (name.length() - 2 < s) return 0;
        out[0] = name.substring(s, name.length() - 2);
        return kind;
    }

    /** A group heading as shown: the name without decoration (---, ===, ***, a trailing colon) */
    static String clean(String name) {
        String t = name.replaceAll("^[\\s\\-=*~#_:.]+|[\\s\\-=*~#_:.]+$", "");
        return t.isEmpty() ? name.trim() : t;
    }

    // ---- The file ----

    private static final class Raw {
        final String open, name;
        final List<String> lines = new ArrayList<>();
        final List<Long> words = new ArrayList<>();
        Raw(String open, String name) { this.open = open; this.name = name; }
    }

    /** The file read. textEnd: the size of main's code (main.fixups.tsv TEXT_END), which tells a code write from a data write in main; -1 unknown */
    public static Parsed parse(String text, long textEnd) {
        Parsed p = new Parsed();
        List<Raw> raws = new ArrayList<>();
        Raw cur = null;
        boolean first = true;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String t = line.replace("﻿", "").trim();
            if (t.isEmpty()) continue;
            String[] h = header(t);
            if (h != null) {
                cur = new Raw(h[0], h[1]);
                raws.add(cur);
                if (codeLine(h[2])) { cur.lines.add(h[2].trim()); words(h[2], cur.words); }
            } else if (cur != null && codeLine(t)) {
                cur.lines.add(t);
                words(t, cur.words);
            } else if (first) {
                java.util.regex.Matcher m = BUILD_LINE.matcher(t);
                if (m.matches()) p.build = m.group(1).toUpperCase(Locale.ROOT);
            }
            first = false;
        }
        String group = "";
        Map<String, Integer> seen = new HashMap<>();
        for (Raw r : raws) {
            int dup = seen.merge(r.name, 1, Integer::sum) - 1;
            String key = dup == 0 ? r.name : r.name + " #" + (dup + 1);
            long[] w = new long[r.words.size()];
            for (int i = 0; i < w.length; i++) w[i] = r.words.get(i);
            if (r.open.equals("{")) {
                if (w.length > 0) p.masters.add(new Cheat(r.name, key, group, true, r.lines, w, classify(w, textEnd)));
                continue;
            }
            if (noop(w)) {   // a label
                String[] sec = new String[1];
                int mark = marker(r.name, sec);
                if (mark == 1) { group = clean(sec[0]); if (!p.groups.contains(group)) p.groups.add(group); }
                else if (mark == 2) group = "";
                else if (TITLE.matcher(r.name).find()) p.titles.add(r.name);
                else if (credit(r.name)) p.credits.add(r.name);
                else if (restore(r.name)) p.restores.add(r.name);
                else if (clean(r.name).equals(group)) group = "";   // the heading again: its group ends
                else { group = clean(r.name); if (!p.groups.contains(group)) p.groups.add(group); }
                continue;
            }
            if (restore(r.name)) { p.restores.add(r.name); continue; }
            p.cheats.add(new Cheat(r.name, key, group, false, r.lines, w, classify(w, textEnd)));
        }
        return p;
    }

    /** No code, or only end / else words (opcode 2): what cheatslips puts under a group heading */
    static boolean noop(long[] w) {
        for (long x : w) if ((x >>> 28) != 2) return false;
        return true;
    }

    // ---- What a cheat does ----

    /** The verdict of a cheat's words */
    static final class Shape {
        Kind kind = Kind.DATA;
        final List<String> hold = new ArrayList<>();
        String why;
        int codePlaces, valueWord = -1, valueWidth;
        boolean pointers;
    }

    /** The opcode of a word as the host names it (hle_cheats.c op_kind): 0..A, C0..CF, FF0..FFF */
    static int op(long w) {
        int op = (int) (w >>> 28);
        if (op >= 0xC) op = (op << 4) | (int) ((w >>> 24) & 0xF);
        if (op >= 0xF0) op = (op << 4) | (int) ((w >>> 20) & 0xF);
        return op;
    }

    private static boolean widthOk(long t) { return t == 1 || t == 2 || t == 4 || t == 8; }

    /** Whether a region nybble reaches main (0, and 5..F, which share main's base) */
    private static boolean mainRegion(long m) { return m == 0 || m >= 5; }

    /**
     * A write at main + rel of a region that reaches main is a code write when it falls in main's code, up to the end of its last page as the loader seals it
     * (the host's main_rx_end: a cheat may write into the gap after the last instruction, as cheat sites put a few instructions there); textEnd unknown: a 4-byte
     * store is taken as one (an instruction)
     */
    private static boolean codeAt(long m, long rel, long width, long textEnd) {
        if (!mainRegion(m)) return false;
        return textEnd >= 0 ? rel < ((textEnd + 0xFFF) & ~0xFFFL) : width == 4;
    }

    /** The kind, the hold buttons and the places written, walking the words as the host's decoder does (hle_cheats.c ins_dec) */
    static Shape classify(long[] p, long textEnd) {
        Shape s = new Shape();
        int n = p.length, staticData = 0, regStores = 0, arith = 0, dataWrites = 0;
        Set<Long> masks = new LinkedHashSet<>(), places = new java.util.HashSet<>();   // places: the code addresses written (an else branch that writes the original back is the same place)
        for (int i = 0; i < n; ) {
            long w = p[i];
            int op = op(w), len = 1;
            long t = (w >>> 24) & 0xF;
            String bad = null;
            switch (op) {
                case 0: {
                    if (!widthOk(t)) { bad = "width"; break; }
                    len = t == 8 ? 4 : 3;
                    if (n - i < len) { bad = "truncated"; break; }
                    long m = (w >>> 20) & 0xF, rel = ((w & 0xFF) << 32) | p[i + 1];
                    if (codeAt(m, rel, t, textEnd)) places.add(m << 40 | rel);
                    else { dataWrites++; staticData++; s.valueWord = i + 2; s.valueWidth = (int) t; }   // the value's words follow the address
                } break;
                case 1: {
                    if (!widthOk(t)) { bad = "width"; break; }
                    long c = (w >>> 16) & 0xF;
                    if (c < 1 || c > 6) { bad = "condition"; break; }
                    len = t == 8 ? 4 : 3;
                } break;
                case 2: break;
                case 3: len = ((w >>> 24) & 0xF) != 0 ? 1 : 2; break;
                case 4: len = 3; break;
                case 5: {
                    if (!widthOk(t)) { bad = "width"; break; }
                    if (((w >>> 12) & 0xF) > 3) { bad = "operand"; break; }
                    len = 2;
                    s.pointers = true;
                } break;
                case 6: {
                    if (!widthOk(t)) { bad = "width"; break; }
                    len = 3;
                    dataWrites++; staticData++;
                    s.valueWord = t == 8 ? i + 1 : i + 2; s.valueWidth = (int) t;
                } break;
                case 7: {
                    if (!widthOk(t)) { bad = "width"; break; }
                    if (((w >>> 12) & 0xF) > 4) { bad = "arithmetic"; break; }
                    len = 2; arith++;
                } break;
                case 8: masks.add(w & 0x0FFFFFFFL); break;
                case 9: {
                    if (!widthOk(t)) { bad = "width"; break; }
                    if (((w >>> 8) & 0xF) != 0) len = t == 8 ? 3 : 2;
                    arith++;
                } break;
                case 0xA: {
                    if (!widthOk(t)) { bad = "width"; break; }
                    long o = (w >>> 8) & 0xF;
                    if (o > 5) { bad = "operand"; break; }
                    len = o == 2 || o == 4 || o == 5 ? 2 : 1;
                    regStores++;
                    if (o == 4 && n - i >= 2 && codeAt((w >>> 4) & 0xF, ((w & 0xF) << 32) | p[i + 1], t, textEnd)) places.add(((w >>> 4) & 0xF) << 40 | ((w & 0xF) << 32) | p[i + 1]);
                    else dataWrites++;
                } break;
                case 0xC0: {
                    long tt = (w >>> 20) & 0xF, c = (w >>> 16) & 0xF, o = (w >>> 8) & 0xF;
                    if (!widthOk(tt)) { bad = "width"; break; }
                    if (c < 1 || c > 6) { bad = "condition"; break; }
                    if (o == 0 || o == 2) len = 2;
                    else if (o == 4) len = tt == 8 ? 3 : 2;
                    else if (o == 1 || o == 3 || o == 5) len = 1;
                    else bad = "operand";
                } break;
                case 0xC1: case 0xC2: case 0xC3: break;
                case 0xC4: {
                    len = 3;
                    if (n - i >= 3) masks.add((p[i + 1] << 32) | p[i + 2]);
                } break;
                default: bad = "opcode " + Integer.toHexString(op).toUpperCase(Locale.ROOT);
            }
            if (bad == null && len > n - i) bad = "truncated";
            if (bad != null) { s.kind = Kind.UNSUPPORTED; s.why = bad; return s; }
            i += len;
        }
        for (long m : masks) { String b = buttons(m); if (!b.isEmpty() && !s.hold.contains(b)) s.hold.add(b); }
        s.codePlaces = places.size();
        if (s.codePlaces > 0) s.kind = Kind.CODE;
        else if (staticData == 1 && dataWrites == 1 && regStores == 0 && arith == 0) s.kind = Kind.VALUE;
        else s.kind = Kind.DATA;
        if (s.kind != Kind.VALUE) { s.valueWord = -1; s.valueWidth = 0; }
        return s;
    }

    /** The buttons of a key mask, the player's names in the NpadButton order (dm_input.h DM_BTN_*): "ZL", "Up + RS" */
    static final String[] BUTTONS = {"A", "B", "X", "Y", "L stick", "R stick", "L", "R", "ZL", "ZR", "Plus", "Minus", "Left", "Up", "Right", "Down",
            "L stick left", "L stick up", "L stick right", "L stick down", "R stick left", "R stick up", "R stick right", "R stick down", "SL", "SR", "SL", "SR"};

    static String buttons(long mask) {
        List<String> out = new ArrayList<>();
        for (int b = 0; b < BUTTONS.length; b++) if ((mask >>> b & 1) != 0 && !out.contains(BUTTONS[b])) out.add(BUTTONS[b]);
        return String.join(" + ", out);
    }

    // ---- In words ----

    private static String places(int n) { return n == 1 ? "1 place" : n + " places"; }

    /** What a cheat changes, in the player's words, for the bottom screen and its details (no developer terms: UI-RULES 5) */
    public static String describe(Cheat c) { return describe(c, c.value()); }

    /** The same, a Value cheat writing value (the number set in the in-game Mods tab, CheatValues) */
    public static String describe(Cheat c, long value) {
        String hold = c.hold.isEmpty() ? null : String.join(" or ", c.hold);
        switch (c.kind) {
            case UNSUPPORTED:
                return "Peanuts cannot run this cheat: it uses a part of the cheat format the app does not run (" + c.why + "). It stays off.";
            case CODE:
                if (hold != null) return "While you hold " + hold + ", it changes " + places(c.codePlaces) + " in the game's code. Let go and the original code is back.";
                return "Changes " + places(c.codePlaces) + " in the game's code. Off puts the original code back.";
            case VALUE:
                return (hold != null ? "While you hold " + hold + ", it writes" : "Writes") + " one number into the game's data" + (c.pointers ? ", found through a chain of pointers" : "")
                        + ": " + value + ". No code is changed.";
            default:
                return (hold != null ? "While you hold " + hold + ", it changes" : "Changes") + " the game's data" + (c.pointers ? " through a chain of pointers" : "") + ". No code is changed.";
        }
    }
}
