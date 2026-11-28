package dev.peanuts.mods;

// PEANUTS: new file (a frame rate variant's game files merged with the enabled mods' copies of them at a start: Animal Crossing: New Horizons' 60 fps
// StaticParam.pack hid the mods' copy, and with it ACNH Combined's River Hopping)

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A variant's RomFS overlay (pack_android.py variant_overlays: {@code <pack>/<variant>/romfs}, the variant's rows applied to the player's own files) goes
 * first in {@code DM_ROMFS_OVERLAY}, and the first root that has a file wins (hle_fs.c rom_overlay_path). So a mod that ships one of the variant's files
 * lost all its changes to that file at the variant's frame rate, and nothing said so. This merges them instead, as a three-way merge against the file
 * the variant was made from ({@code <pack>/<variant>/romfs-orig}, which the pack step writes beside it):
 * <ul>
 * <li>every value a mod changed is written into a copy of the variant's file, unless the variant changed the same value: then the variant's stays (its
 *     frame rate needs it) and the log says so;</li>
 * <li>values are matched by what they are, not where: SARC entries by name, BYML (v2 and later, little-endian) values by their path of keys and indexes,
 *     so a mod made from another game version, whose files are laid out differently, still merges. Other files of the same size are merged by changed
 *     runs of bytes;</li>
 * <li>nothing is resized or moved: a value the mod adds, or a file whose shape differs and is neither SARC nor BYML, is not merged, and the log says so.</li>
 * </ul>
 * A mod higher in the list wins a value two mods change, as it wins a whole file in the overlay. The merged files go to a staging folder the caller puts
 * ahead of the variant's folder; the files no mod ships still come from the variant's folder. Plain Java (no Android classes): VariantMergeJvmTest.
 */
public final class VariantMerge {
    private VariantMerge() {}

    /** What a start takes: the folder to put first in the overlay (null when nothing was merged) and the lines for the log */
    public static final class Result {
        public final File root;
        public final List<String> notes;
        Result(File root, List<String> notes) { this.root = root; this.notes = notes; }
    }

    /**
     * The enabled mods' copies of the variant's files, merged into the variant's. variantRomfs: the variant's overlay folder; originals: the files it was
     * made from (null or missing for a pack from before they were kept: then nothing is merged and the log names the hidden files); mods: the enabled
     * mods' romfs folders, first wins; stage: emptied, then the merged files are written to it; variant: its name, for the log
     */
    public static Result merge(File variantRomfs, File originals, List<File> mods, File stage, String variant) {
        List<String> notes = new ArrayList<>();
        deleteTree(stage);
        boolean wrote = false;
        for (String rel : files(variantRomfs)) {
            List<File> theirs = new ArrayList<>();   // the mods (their romfs folders) that ship this file
            for (File m : mods) if (new File(m, rel).isFile()) theirs.add(m);
            if (theirs.isEmpty()) continue;
            String what = "[nxlaunch] mods: " + rel + " (the " + variant + " variant's game data)";
            File orig = originals == null ? null : new File(originals, rel);
            if (orig == null || !orig.isFile()) {
                notes.add(what + ": " + names(theirs) + "'s copy is hidden: this pack has no original to merge it against (prepare the game again)");
                continue;
            }
            try {
                byte[] base = Files.readAllBytes(orig.toPath()), ours = Files.readAllBytes(new File(variantRomfs, rel).toPath()), out = ours.clone();
                List<String> parts = new ArrayList<>();
                for (int i = theirs.size() - 1; i >= 0; i--) {   // the last in the list first, so the first one's values are written last and win
                    Merger mg = new Merger(base, ours, out);
                    mg.merge(Files.readAllBytes(new File(theirs.get(i), rel).toPath()));
                    parts.add(0, mod(theirs.get(i)) + ": " + mg.summary());
                }
                notes.add(what + ": " + String.join("; ", parts));
                if (!Arrays.equals(out, ours)) {
                    File dst = new File(stage, rel);
                    File parent = dst.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot make " + parent);
                    File tmp = new File(dst.getPath() + ".partial");
                    Files.write(tmp.toPath(), out);
                    Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    wrote = true;
                }
            } catch (IOException | RuntimeException e) {   // a file that cannot be read or parsed: the variant's file as it is, never a half-merged one
                new File(stage, rel).delete();
                notes.add(what + ": " + names(theirs) + "'s copy is hidden: not merged (" + e + ")");
            }
        }
        return new Result(wrote ? stage : null, notes);
    }

    /**
     * One mod's file merged into out: base is the original, ours the variant's file (out's layout: the merge writes in place), theirs the mod's. A change
     * clashes when the variant changed the same value against base; then out keeps what it has
     */
    static final class Merger {
        final byte[] base, ours, out;
        int ported, clashes, unmerged;
        final List<String> why = new ArrayList<>();

        Merger(byte[] base, byte[] ours, byte[] out) { this.base = base; this.ours = ours; this.out = out; }

        void merge(byte[] theirs) { node("", new Span(base, 0, base.length), new Span(ours, 0, ours.length), new Span(theirs, 0, theirs.length), 0); }

        String summary() {
            if (ported == 0 && clashes == 0 && unmerged == 0) return "no change against the original";
            String s = ported + " value" + (ported == 1 ? "" : "s") + " merged";
            if (clashes > 0) s += ", " + clashes + " kept as the variant has them (it changes them too)";
            if (unmerged > 0) s += ", " + unmerged + " not merged (" + String.join(", ", why) + (unmerged > why.size() ? ", ..." : "") + ")";
            return s;
        }

        private void miss(String what) { unmerged++; if (why.size() < 4) why.add(what); }

        private void node(String path, Span b, Span o, Span t, int depth) {
            if (b.equalTo(t)) return;
            if (depth < 8 && Sarc.is(b) && Sarc.is(o) && Sarc.is(t)) { sarc(path, b, o, t, depth); return; }
            if (Byml.is(b) && Byml.is(o) && Byml.is(t)) { byml(path, b, o, t); return; }
            if (b.len == o.len && b.len == t.len) { runs(path, b, o, t); return; }
            miss((path.isEmpty() ? "the file" : path) + " has another size");
        }

        private void sarc(String path, Span b, Span o, Span t, int depth) {
            Map<String, Span> bs = Sarc.entries(b), os = Sarc.entries(o), ts = Sarc.entries(t);
            for (Map.Entry<String, Span> e : ts.entrySet()) {
                String p = path + "/" + e.getKey();
                Span be = bs.get(e.getKey()), oe = os.get(e.getKey());
                if (be == null || oe == null) { miss(p + " is not in the " + (be == null ? "original" : "variant")); continue; }
                node(p, be, oe, e.getValue(), depth + 1);
            }
        }

        private void byml(String path, Span b, Span o, Span t) {
            Byml bb = new Byml(b), ob = new Byml(o), tb = new Byml(t);
            Map<String, Byml.Slot> bv = bb.slots(), ov = ob.slots(), tv = tb.slots();
            for (Map.Entry<String, Byml.Slot> e : tv.entrySet()) {
                Byml.Slot bs = bv.get(e.getKey()), os = ov.get(e.getKey()), ts = e.getValue();
                if (bs == null || bs.type != ts.type) { miss(path + e.getKey() + " is added or retyped"); continue; }
                if (os == null || os.type != ts.type) { miss(path + e.getKey() + " is not in the variant's file"); continue; }
                byte[] v0 = bb.value(bs), v1 = ob.value(os), v2 = tb.value(ts);
                if (Arrays.equals(v2, v0)) continue;
                if (!Arrays.equals(v1, v0)) { clashes++; continue; }
                byte[] w = v2;
                if (ts.type == Byml.STRING) {   // a string is an index into the file's own string table: the variant's index of the mod's string
                    int k = ob.stringIndex(new String(v2, StandardCharsets.UTF_8));
                    if (k < 0) { miss(path + e.getKey() + " is a string the variant's file lacks"); continue; }
                    w = new byte[] {(byte) k, (byte) (k >> 8), (byte) (k >> 16), (byte) (k >>> 24)};
                }
                System.arraycopy(w, 0, out, o.off + ob.valueAt(os), w.length);
                ported++;
            }
        }

        /** Same-size data of no known shape: each run of bytes the mod changed, unless the variant changed a byte within 3 of it (a value the variant changed) */
        private void runs(String path, Span b, Span o, Span t) {
            int n = b.len, i = 0;
            while (i < n) {
                if (t.at(i) == b.at(i)) { i++; continue; }
                int j = i;
                while (j < n && t.at(j) != b.at(j)) j++;
                boolean clash = false;
                for (int k = Math.max(0, i - 3); k < Math.min(n, j + 3) && !clash; k++) clash = o.at(k) != b.at(k);
                if (clash) clashes++;
                else { System.arraycopy(t.a, t.off + i, out, o.off + i, j - i); ported++; }
                i = j;
            }
        }
    }

    /** A slice of a file */
    static final class Span {
        final byte[] a; final int off, len;
        Span(byte[] a, int off, int len) {
            if (off < 0 || len < 0 || off + len > a.length || off + len < 0) throw new IllegalArgumentException("a slice past the file's end");
            this.a = a; this.off = off; this.len = len;
        }
        int at(int i) { return a[off + i] & 0xFF; }
        int u16(int i) { return at(i) | at(i + 1) << 8; }
        int u32(int i) { return u16(i) | u16(i + 2) << 16; }
        int u24(int i) { return at(i) | at(i + 1) << 8 | at(i + 2) << 16; }
        boolean equalTo(Span s) { return Arrays.equals(a, off, off + len, s.a, s.off, s.off + s.len); }
        String cstr(int i) {
            int e = i;
            while (e < len && a[off + e] != 0) e++;
            if (e >= len) throw new IllegalArgumentException("a name past the file's end");
            return new String(a, off + i, e - i, StandardCharsets.UTF_8);
        }
    }

    /** SARC archives (little-endian): entry name → its data */
    static final class Sarc {
        static boolean is(Span s) { return s.len >= 0x28 && s.at(0) == 'S' && s.at(1) == 'A' && s.at(2) == 'R' && s.at(3) == 'C' && s.u16(6) == 0xFEFF; }

        static Map<String, Span> entries(Span s) {
            int data = s.u32(0xC);
            if (s.at(0x14) != 'S' || s.at(0x15) != 'F' || s.at(0x16) != 'A' || s.at(0x17) != 'T') throw new IllegalArgumentException("a SARC without SFAT");
            int n = s.u16(0x1A), names = 0x20 + 16 * n + 8;
            Map<String, Span> out = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int e = 0x20 + 16 * i, hash = s.u32(e), attr = s.u32(e + 4), start = s.u32(e + 8), end = s.u32(e + 12);
                String name = (attr >>> 24) != 0 ? s.cstr(names + (attr & 0xFFFF) * 4) : String.format("%08x", hash);
                if (end < start) throw new IllegalArgumentException("a SARC entry that ends before it starts");
                out.put(name, new Span(s.a, s.off + data + start, end - start));
            }
            return out;
        }
    }

    /** BYML v2 and later (little-endian "YB"): every value that is not a container, by its path (/key for a dictionary, [i] for an array) */
    static final class Byml {
        static final int STRING = 0xA0;
        final Span s;
        final String[] keys, strings;

        static boolean is(Span s) { return s.len >= 16 && s.at(0) == 'Y' && s.at(1) == 'B' && s.u16(2) >= 2; }

        Byml(Span s) { this.s = s; keys = table(s.u32(4)); strings = table(s.u32(8)); }

        private String[] table(int at) {
            if (at == 0) return new String[0];
            if (s.at(at) != 0xC2) throw new IllegalArgumentException("not a BYML string table");
            int n = s.u24(at + 1);
            String[] out = new String[n];
            for (int i = 0; i < n; i++) out[i] = s.cstr(at + s.u32(at + 4 + 4 * i));
            return out;
        }

        int stringIndex(String v) { for (int i = 0; i < strings.length; i++) if (strings[i].equals(v)) return i; return -1; }

        /** A value's place: where its 4-byte slot is, and its type */
        static final class Slot { final int at, type; Slot(int at, int type) { this.at = at; this.type = type; } }

        Map<String, Slot> slots() {
            Map<String, Slot> out = new HashMap<>();
            int root = s.u32(12);
            if (root != 0) walk(root, "", out, 0);
            return out;
        }

        private void walk(int at, String path, Map<String, Slot> out, int depth) {
            if (depth > 64) throw new IllegalArgumentException("a BYML nested too deep");
            int type = s.at(at), n = s.u24(at + 1);
            List<Object[]> items = new ArrayList<>();
            if (type == 0xC0) {
                int values = (at + 4 + n + 3) & ~3;
                for (int i = 0; i < n; i++) items.add(new Object[] {path + "[" + i + "]", s.at(at + 4 + i), values + 4 * i});
            } else if (type == 0xC1) {
                for (int i = 0; i < n; i++) {
                    int e = at + 4 + 8 * i, k = s.u24(e);
                    if (k >= keys.length) throw new IllegalArgumentException("a BYML key past its table");
                    items.add(new Object[] {path + "/" + keys[k], s.at(e + 3), e + 4});
                }
            } else return;   // another container type (later BYML versions): its values are not merged
            for (Object[] it : items) {
                int t = (Integer) it[1], slot = (Integer) it[2];
                if (t == 0xC0 || t == 0xC1) walk(s.u32(slot), (String) it[0], out, depth + 1);
                else if (t == STRING || (t >= 0xD0 && t <= 0xD6)) out.put((String) it[0], new Slot(slot, t));
            }
        }

        /** Where the value's bytes are in this file: the slot itself, or for a 64-bit value the place the slot points at */
        int valueAt(Slot v) { return v.type >= 0xD4 ? s.u32(v.at) : v.at; }

        byte[] value(Slot v) {
            if (v.type == STRING) {
                int k = s.u32(v.at);
                if (k < 0 || k >= strings.length) throw new IllegalArgumentException("a BYML string past its table");
                return strings[k].getBytes(StandardCharsets.UTF_8);
            }
            int at = valueAt(v), w = v.type >= 0xD4 ? 8 : 4;
            if (at < 0 || at + w > s.len) throw new IllegalArgumentException("a BYML value past the file's end");
            return Arrays.copyOfRange(s.a, s.off + at, s.off + at + w);
        }
    }

    /** The files under root, as RomFS paths ('/'), sorted */
    static List<String> files(File root) {
        List<String> out = new ArrayList<>();
        walk(root, "", out);
        java.util.Collections.sort(out);
        return out;
    }

    private static void walk(File dir, String rel, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String r = rel.isEmpty() ? k.getName() : rel + "/" + k.getName();
            if (k.isDirectory()) walk(k, r, out);
            else if (k.isFile() && !k.getName().endsWith(".partial")) out.add(r);
        }
    }

    /** A mod's name in the log: its folder (<mods>/<mod>/romfs) */
    private static String mod(File romfs) { File m = romfs.getParentFile(); return m == null ? romfs.getPath() : m.getName(); }

    private static String names(List<File> fs) {
        List<String> out = new ArrayList<>();
        for (File f : fs) out.add(mod(f));
        return String.join(", ", out);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
