package dev.peanuts.mods;   // PEANUTS: a frame rate variant's files merged with the mods' copies (VariantMerge)

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VariantMerge on the JVM, driven by dm/tools/tests/test_pack_android_variants.py. args[0]: an empty folder. Prints "ok" or throws. The files are made up
 * here (a SARC of BYML documents and a table, in the layout Animal Crossing's StaticParam.pack has), never game data. Covers a value the mod changes, one
 * both change, a mod laid out differently (another game version), two mods on one value, strings, same-size data, a pack without originals, a mod file
 * like the original and one that does not parse.
 */
public final class VariantMergeJvmTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    // ---- made-up files ----

    /** A BYML v2 document of a root dictionary; values: Float, Integer, String, a Map (a dictionary) or a List (an array); keys and strings in the order given */
    static byte[] byml(Map<String, Object> root) {
        List<String> keys = new ArrayList<>(), strings = new ArrayList<>();
        collect(root, keys, strings);
        java.util.Collections.sort(keys); java.util.Collections.sort(strings);   // the tables are sorted, as the game's are
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int keyAt = 16;
        byte[] kt = table(keys), st = table(strings);
        int strAt = keyAt + kt.length, rootAt = strAt + st.length;
        Out o = new Out(rootAt);
        o.node(root, keys, strings);
        ByteBuffer h = le(16);
        h.put((byte) 'Y').put((byte) 'B').putShort((short) 2).putInt(keyAt).putInt(strings.isEmpty() ? 0 : strAt).putInt(rootAt);
        body.writeBytes(h.array()); body.writeBytes(kt); body.writeBytes(st); body.writeBytes(o.bytes());
        return body.toByteArray();
    }

    @SuppressWarnings("unchecked")
    static void collect(Object v, List<String> keys, List<String> strings) {
        if (v instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) { if (!keys.contains(e.getKey())) keys.add(e.getKey()); collect(e.getValue(), keys, strings); }
        else if (v instanceof List) for (Object x : (List<Object>) v) collect(x, keys, strings);
        else if (v instanceof String && !strings.contains(v)) strings.add((String) v);
    }

    static byte[] table(List<String> names) {
        if (names.isEmpty()) return new byte[0];
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        int head = 4 + 4 * (names.size() + 1), at = head;
        List<Integer> offs = new ArrayList<>();
        for (String n : names) { offs.add(at); byte[] b = n.getBytes(StandardCharsets.UTF_8); s.writeBytes(b); s.write(0); at += b.length + 1; }
        while (at % 4 != 0) { s.write(0); at++; }
        ByteBuffer h = le(head);
        h.putInt(0xC2 | names.size() << 8);
        for (int x : offs) h.putInt(x);
        h.putInt(at);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(h.array()); out.writeBytes(s.toByteArray());
        return out.toByteArray();
    }

    /** Containers written depth first, each after its parent, at 4-byte alignment */
    static final class Out {
        final int base;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Out(int base) { this.base = base; }
        byte[] bytes() { return b.toByteArray(); }
        int pos() { return base + b.size(); }

        @SuppressWarnings("unchecked")
        int node(Object v, List<String> keys, List<String> strings) {
            int at = pos();
            if (v instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) v;
                List<String> ks = new ArrayList<>(m.keySet());
                ks.sort((x, y) -> Integer.compare(keys.indexOf(x), keys.indexOf(y)));   // a dictionary's entries in key table order
                ByteBuffer n = le(4 + 8 * ks.size());
                n.putInt(0xC1 | ks.size() << 8);
                int[] fix = new int[ks.size()];
                for (int i = 0; i < ks.size(); i++) { Object x = m.get(ks.get(i)); n.putInt(keys.indexOf(ks.get(i)) | type(x) << 24); fix[i] = n.position(); n.putInt(scalar(x, strings)); }
                b.writeBytes(n.array());
                for (int i = 0; i < ks.size(); i++) { Object x = m.get(ks.get(i)); if (x instanceof Map || x instanceof List) patch(at + fix[i] - base, node(x, keys, strings)); }
            } else {
                List<Object> l = (List<Object>) v;
                int pad = (4 + l.size() + 3) & ~3;
                ByteBuffer n = le(pad + 4 * l.size());
                n.putInt(0xC0 | l.size() << 8);
                for (Object x : l) n.put((byte) type(x));
                n.position(pad);
                int[] fix = new int[l.size()];
                for (int i = 0; i < l.size(); i++) { fix[i] = n.position(); n.putInt(scalar(l.get(i), strings)); }
                b.writeBytes(n.array());
                for (int i = 0; i < l.size(); i++) { Object x = l.get(i); if (x instanceof Map || x instanceof List) patch(at + fix[i] - base, node(x, keys, strings)); }
            }
            return at;
        }

        void patch(int rel, int value) { byte[] a = b.toByteArray(); ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).putInt(rel, value); b = new ByteArrayOutputStream(); b.writeBytes(a); }
    }

    static int type(Object x) { return x instanceof Map ? 0xC1 : x instanceof List ? 0xC0 : x instanceof String ? 0xA0 : x instanceof Float ? 0xD2 : 0xD1; }
    static int scalar(Object x, List<String> strings) { return x instanceof Float ? Float.floatToRawIntBits((Float) x) : x instanceof Integer ? (Integer) x : x instanceof String ? strings.indexOf(x) : 0; }

    /** A SARC of these entries, in this order, each at 8-byte alignment after a gap of pad bytes (another tool's layout) */
    static byte[] sarc(Map<String, byte[]> entries, int pad) {
        int n = entries.size();
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        List<Integer> nameAt = new ArrayList<>();
        for (String k : entries.keySet()) { nameAt.add(names.size() / 4); names.writeBytes(k.getBytes(StandardCharsets.UTF_8)); do names.write(0); while (names.size() % 4 != 0); }
        int head = 0x20 + 16 * n + 8 + names.size(), data = (head + 0x7F) & ~0x7F;
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        ByteBuffer h = le(head);
        h.put("SARC".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0x14).putShort((short) 0xFEFF).putInt(0).putInt(data).putInt(0x01000100);
        h.put("SFAT".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0xC).putShort((short) n).putInt(0x65);
        int i = 0;
        for (byte[] e : entries.values()) {
            d.writeBytes(new byte[pad]);
            while (d.size() % 8 != 0) d.write(0);
            h.putInt(0x1000 + i).putInt(0x01000000 | nameAt.get(i)).putInt(d.size()).putInt(d.size() + e.length);
            d.writeBytes(e);
            i++;
        }
        h.put("SFNT".getBytes(StandardCharsets.US_ASCII)).putShort((short) 8).putShort((short) 0).put(names.toByteArray());
        byte[] out = new byte[data + d.size()];
        System.arraycopy(h.array(), 0, out, 0, head);
        System.arraycopy(d.toByteArray(), 0, out, data, d.size());
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(8, out.length);
        return out;
    }

    static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

    /** The made-up pack: a player's actor (a hop gap, a walk speed, a list, a nested dictionary, a name) and a table of fixed-size rows */
    static Map<String, Object> actor(float gap, float walk, int frames, String name, float nested) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("HopGap", gap); m.put("WalkSpeed", walk); m.put("Frames", new ArrayList<>(List.of(frames, frames * 2, 7)));
        Map<String, Object> sub = new LinkedHashMap<>(); sub.put("Lean", nested); m.put("Body", sub);
        m.put("Name", name);
        return m;
    }

    static byte[] table(int a, int b) { ByteBuffer t = le(32); for (int i = 0; i < 8; i++) t.putInt(i == 2 ? a : i == 6 ? b : 1000 + i); return t.array(); }

    static byte[] pack(Map<String, Object> actor, byte[] table, int pad) {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("Param/Actor/PlayerActor.byml", byml(actor)); e.put("Param/Other.byml", byml(actor(1f, 2f, 3, "Other", 0f))); e.put("Bcsv/Table.bcsv", table);
        return sarc(e, pad);
    }

    // ---- reading a merged file back ----

    static Map<String, Object> read(byte[] packBytes, String entry) {
        VariantMerge.Span s = VariantMerge.Sarc.entries(new VariantMerge.Span(packBytes, 0, packBytes.length)).get(entry);
        VariantMerge.Byml b = new VariantMerge.Byml(s);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, VariantMerge.Byml.Slot> e : b.slots().entrySet()) {
            byte[] v = b.value(e.getValue());
            out.put(e.getKey(), e.getValue().type == 0xD2 ? (Object) ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).getFloat()
                    : e.getValue().type == 0xA0 ? (Object) new String(v, StandardCharsets.UTF_8) : (Object) ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).getInt());
        }
        return out;
    }

    static int tableValue(byte[] packBytes, int i) {
        VariantMerge.Span s = VariantMerge.Sarc.entries(new VariantMerge.Span(packBytes, 0, packBytes.length)).get("Bcsv/Table.bcsv");
        return s.u32(4 * i);
    }

    // ---- the cases ----

    static File dir;
    static int n;

    /** A pack folder with the variant's file and its original, and mods with their copies; returns the merge's result */
    static VariantMerge.Result run(byte[] orig, byte[] variant, boolean originals, byte[]... mods) throws Exception {
        File d = new File(dir, "case" + (n++));
        File romfs = new File(d, "pack/60/romfs/Pack"), origs = new File(d, "pack/60/romfs-orig/Pack");
        check(romfs.mkdirs() && origs.mkdirs(), "folders");
        Files.write(new File(romfs, "StaticParam.pack").toPath(), variant);
        Files.write(new File(d, "pack/60/romfs/Other.bin").toPath(), new byte[] {1, 2, 3});   // a variant file no mod ships
        if (originals) Files.write(new File(origs, "StaticParam.pack").toPath(), orig);
        List<File> roots = new ArrayList<>();
        for (int i = 0; i < mods.length; i++) {
            File r = new File(d, "mods/Mod" + i + "/romfs");
            check(new File(r, "Pack").mkdirs(), "mod folder");
            Files.write(new File(r, "Pack/StaticParam.pack").toPath(), mods[i]);
            roots.add(r);
        }
        File stage = new File(d, "stage");
        check(new File(stage, "stale").mkdirs(), "a stale staging folder");
        return VariantMerge.merge(new File(d, "pack/60/romfs"), new File(d, "pack/60/romfs-orig"), roots, stage, "60");
    }

    static byte[] merged(VariantMerge.Result r) throws Exception {
        check(r.root != null, "a merged folder: " + r.notes);
        check(!new File(r.root, "stale").exists(), "the staging folder is emptied first");
        check(!new File(r.root, "Other.bin").exists(), "only merged files are staged; the others come from the variant's folder");
        return Files.readAllBytes(new File(r.root, "Pack/StaticParam.pack").toPath());
    }

    public static void main(String[] args) throws Exception {
        dir = new File(args[0]);
        // the original, the variant (it halves the walk speed and doubles the frames, as a 60 fps patch does) and the mod (it widens the hop gap)
        byte[] orig = pack(actor(35f, 4f, 30, "Player", 0.243165f), table(10, 20), 0);
        byte[] variant = pack(actor(35f, 2f, 60, "Player", 0.243165f), table(10, 40), 0);
        byte[] mod = pack(actor(100f, 4f, 30, "Player", 0.243165f), table(10, 20), 0);

        // 1. a value only the mod changes is merged; the variant's own values stay; nothing moves (same size, same layout as the variant's)
        VariantMerge.Result r = run(orig, variant, true, mod);
        byte[] m = merged(r);
        check(m.length == variant.length, "the variant's layout");
        Map<String, Object> v = read(m, "Param/Actor/PlayerActor.byml");
        check(v.get("/HopGap").equals(100f) && v.get("/WalkSpeed").equals(2f) && v.get("/Frames[0]").equals(60), "merged: " + v);
        check(r.notes.size() == 1 && r.notes.get(0).equals("[nxlaunch] mods: Pack/StaticParam.pack (the 60 variant's game data): Mod0: 1 value merged"), r.notes.toString());

        // 2. a value both change keeps the variant's (its frame rate needs it), and the log says so; the mod's other change is still merged
        byte[] both = pack(actor(100f, 5f, 30, "Player", 0.243165f), table(10, 20), 0);
        r = run(orig, variant, true, both);
        v = read(merged(r), "Param/Actor/PlayerActor.byml");
        check(v.get("/HopGap").equals(100f) && v.get("/WalkSpeed").equals(2f), "clash: " + v);
        check(r.notes.get(0).endsWith("Mod0: 1 value merged, 1 kept as the variant has them (it changes them too)"), r.notes.toString());

        // 3. a mod made from another game version: its SARC laid out differently (gaps), a value rounded, a key the original lacks; still merged by path
        Map<String, Object> other = actor(100f, 4f, 30, "Player", 0.24317f);
        other.put("NewKey", 5);
        r = run(orig, variant, true, pack(other, table(10, 20), 24));
        v = read(merged(r), "Param/Actor/PlayerActor.byml");
        check(v.get("/HopGap").equals(100f) && v.get("/Body/Lean").equals(0.24317f) && !v.containsKey("/NewKey"), "another layout: " + v);
        check(r.notes.get(0).endsWith("Mod0: 2 values merged, 1 not merged (/Param/Actor/PlayerActor.byml/NewKey is added or retyped)"), r.notes.toString());

        // 4. two mods on one value: the first in the list wins, as it wins a whole file in the overlay; each one's other values are merged
        byte[] first = pack(actor(100f, 4f, 30, "Player", 0.243165f), table(10, 20), 0), second = pack(actor(50f, 4f, 30, "Player", 0.5f), table(10, 20), 0);
        r = run(orig, variant, true, first, second);
        v = read(merged(r), "Param/Actor/PlayerActor.byml");
        check(v.get("/HopGap").equals(100f) && v.get("/Body/Lean").equals(0.5f), "two mods: " + v);
        check(r.notes.get(0).contains("Mod0: 1 value merged; Mod1: 2 values merged"), r.notes.toString());

        // 5. strings: one the variant's string table has is merged (by its index there); one it lacks is not
        byte[] origS = pack(actor(35f, 4f, 30, "Player", 0f), table(10, 20), 0);
        Map<String, Object> varS = actor(35f, 2f, 60, "Player", 0f); varS.put("Alt", "Runner");
        byte[] variantS = pack(varS, table(10, 40), 0);
        Map<String, Object> modS = actor(35f, 4f, 30, "Runner", 0f); modS.put("Alt", "Runner");   // Alt is added (not merged); Name becomes a string the variant has
        v = read(merged(run(origS, variantS, true, pack(modS, table(10, 20), 0))), "Param/Actor/PlayerActor.byml");
        check(v.get("/Name").equals("Runner") && v.get("/Alt").equals("Runner"), "a string the variant has: " + v);
        r = run(origS, variantS, true, pack(actor(35f, 4f, 30, "Swimmer", 0f), table(10, 20), 0));
        check(r.root == null && r.notes.get(0).endsWith("Mod0: 0 values merged, 1 not merged (/Param/Actor/PlayerActor.byml/Name is a string the variant's file lacks)"), r.notes.toString());

        // 6. same-size data of no known shape: a run of bytes merged; one next to a value the variant changed is not
        r = run(orig, variant, true, pack(actor(35f, 4f, 30, "Player", 0.243165f), table(11, 21), 0));
        m = merged(r);
        check(tableValue(m, 2) == 11 && tableValue(m, 6) == 40, "table: " + tableValue(m, 2) + " " + tableValue(m, 6));
        check(r.notes.get(0).endsWith("Mod0: 1 value merged, 1 kept as the variant has them (it changes them too)"), r.notes.toString());

        // 7. a pack from before the originals were kept: nothing merged, the hidden file named
        r = run(orig, variant, false, mod);
        check(r.root == null && r.notes.get(0).equals("[nxlaunch] mods: Pack/StaticParam.pack (the 60 variant's game data): Mod0's copy is hidden: this pack has no original to merge it against (prepare the game again)"), r.notes.toString());

        // 8. a mod whose copy is the original: nothing to merge, nothing staged
        r = run(orig, variant, true, orig.clone());
        check(r.root == null && r.notes.get(0).endsWith("Mod0: no change against the original"), r.notes.toString());

        // 9. a mod file that does not parse: the variant's file as it is, never a half-merged one
        r = run(orig, variant, true, Arrays.copyOf(mod, 0x30));
        check(r.root == null && r.notes.get(0).contains("Mod0's copy is hidden: not merged ("), r.notes.toString());
        byte[] bad = mod.clone();
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20 + 8, 1 << 30);   // an entry past the file's end
        r = run(orig, variant, true, bad);
        check(r.root == null && r.notes.get(0).contains("not merged ("), r.notes.toString());

        // 10. a file of another size and no known shape is not merged
        File d = new File(dir, "raw");
        check(new File(d, "v").mkdirs() && new File(d, "o").mkdirs() && new File(d, "m/romfs").mkdirs(), "folders");
        Files.write(new File(d, "v/T.bin").toPath(), new byte[] {1, 2, 3, 4});
        Files.write(new File(d, "o/T.bin").toPath(), new byte[] {1, 2, 3, 3});
        Files.write(new File(d, "m/romfs/T.bin").toPath(), new byte[] {9, 2, 3, 3, 0});
        r = VariantMerge.merge(new File(d, "v"), new File(d, "o"), List.of(new File(d, "m/romfs")), new File(d, "stage"), "60");
        check(r.root == null && r.notes.get(0).equals("[nxlaunch] mods: T.bin (the 60 variant's game data): m: 0 values merged, 1 not merged (the file has another size)"), r.notes.toString());

        System.out.println("ok");
    }
}
