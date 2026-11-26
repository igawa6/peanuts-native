package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.davdxpx.mistel.mncf.CodeModule;

/**
 * The game's own main module, as a pack holds it, for .mncf cheats (Mistel's spec, sections 6.3 and 7.4: patterns are searched in the original bytes and a
 * patch's expect is compared with them). The pack has main as the rewriter left it (main.patched.bin: the image, then its patch area), the words it changed
 * (main.changed.tsv: runs of offset and length in main's code) and what the game had there (main.orig.bin: those runs' original bytes, one after the other;
 * dm/tools/patch_nso.py, dev.peanuts.pack.OrigFill). Put together they are the game's own image. A pack without main.orig.bin gives the rewritten image with the
 * changed runs left out of every search and every expect (CodeModule's exclude), so nothing is read from a place the rewriter changed. The layout comes from
 * main.fixups.tsv's header (TEXT_END, RO_OFF, DATA_OFF, PATCH_OFF, FILE_IMAGE_LEN, KEY_PAGE_OFF, BUILD_ID). No Android classes: CheatLoaderJvmTest runs it.
 */
public final class MncfMain {
    private MncfMain() {}

    /** The largest image read (the arrays a JVM gives): a main larger than this has no patterns here */
    static final long MAX_IMAGE = Integer.MAX_VALUE - 64;

    /** main.fixups.tsv's header: "# NAME \t 0x..." lines, the names upper case */
    static Map<String, String> header(File fixups) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : new String(Files.readAllBytes(fixups.toPath()), StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith("#")) continue;   // the table's rows after the header
            String[] w = line.substring(1).trim().split("\\s+");
            if (w.length >= 2) out.put(w[0].toUpperCase(Locale.ROOT), w[1].trim());
        }
        return out;
    }

    static long num(Map<String, String> h, String key, long none) {
        String v = h.get(key);
        if (v == null) return none;
        try {
            boolean neg = v.startsWith("-");
            String t = neg ? v.substring(1) : v;
            long n = t.startsWith("0x") || t.startsWith("0X") ? Long.parseUnsignedLong(t.substring(2), 16) : Long.parseLong(t);
            return neg ? -n : n;
        } catch (NumberFormatException e) {
            return none;
        }
    }

    /** The build ID the pack's main was rewritten from (fixups' BUILD_ID, else changed.tsv's), upper case; null when neither names it */
    public static String buildId(File pack) {
        try {
            File f = new File(pack, "main.fixups.tsv");
            if (f.isFile()) { String b = header(f).get("BUILD_ID"); if (b != null && !b.isEmpty()) return b.toUpperCase(Locale.ROOT); }
            File c = new File(pack, "main.changed.tsv");
            if (c.isFile()) { String b = header(c).get("BUILD_ID"); if (b != null && !b.isEmpty()) return b.toUpperCase(Locale.ROOT); }
        } catch (IOException ignored) { }
        return null;
    }

    /** The runs main.changed.tsv lists (the words the rewriter changed), empty when the pack has none */
    public static List<CodeModule.Run> changed(File pack) throws IOException {
        List<CodeModule.Run> out = new ArrayList<>();
        File f = new File(pack, "main.changed.tsv");
        if (!f.isFile()) return out;
        for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
            String t = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (t.trim().isEmpty() || t.startsWith("#")) continue;
            String[] c = t.split("\t");
            if (c.length < 2) throw new IOException("main.changed.tsv: a line that is not an offset and a length");
            try {
                out.add(new CodeModule.Run(hex(c[0]), hex(c[1])));
            } catch (NumberFormatException e) {
                throw new IOException("main.changed.tsv: a line that is not an offset and a length");
            }
        }
        return out;
    }

    private static long hex(String s) {
        String t = s.trim();
        if (t.startsWith("0x") || t.startsWith("0X")) t = t.substring(2);
        return Long.parseUnsignedLong(t, 16);
    }

    /** Whether the pack has a main image to read */
    public static boolean has(File pack) {
        return pack != null && new File(pack, "main.patched.bin").isFile() && new File(pack, "main.fixups.tsv").isFile();
    }

    /**
     * The game's own main of a pack: null when the pack has no image (not built yet, or another kind of pack). IOException when its files do not fit together
     * (orig.bin shorter than the runs, a layout past the file's end)
     */
    public static CodeModule of(File pack) throws IOException {
        if (!has(pack)) return null;
        Map<String, String> h = header(new File(pack, "main.fixups.tsv"));
        long text = num(h, "TEXT_END", -1), ro = num(h, "RO_OFF", -1), data = num(h, "DATA_OFF", -1), patch = num(h, "PATCH_OFF", 0), fileImage = num(h, "FILE_IMAGE_LEN", -1);
        long key = num(h, "KEY_PAGE_OFF", -1);
        if (text <= 0 || ro < text || data < ro) throw new IOException("main.fixups.tsv has no layout (TEXT_END, RO_OFF, DATA_OFF)");
        File bin = new File(pack, "main.patched.bin");
        long end = patch > 0 ? patch : fileImage;   // the image's part of the file: up to the patch area after it, or FILE_IMAGE_LEN when the patch area lies before the base
        if (end <= 0) end = bin.length();
        if (key > data && key < end) end = key;   // the key page the loader puts after the data: not the game's
        end = Math.min(end, bin.length());
        if (end <= data) throw new IOException("main.patched.bin ends before main's data");
        if (end > MAX_IMAGE) throw new IOException("main is larger than the app reads (" + end + " bytes)");
        byte[] image = new byte[(int) end];
        try (RandomAccessFile f = new RandomAccessFile(bin, "r")) { f.readFully(image); }
        List<CodeModule.Run> runs = changed(pack), exclude = null;
        File orig = new File(pack, "main.orig.bin");
        if (!runs.isEmpty()) {
            if (orig.isFile()) {
                long total = 0;
                for (CodeModule.Run r : runs) total += r.length();
                if (orig.length() < total) throw new IOException("main.orig.bin is shorter than the runs main.changed.tsv lists");
                try (RandomAccessFile f = new RandomAccessFile(orig, "r")) {
                    for (CodeModule.Run r : runs) {
                        if (r.offset() < 0 || r.offset() + r.length() > end) throw new IOException("main.changed.tsv names a run past main's image");
                        f.readFully(image, (int) r.offset(), (int) r.length());
                    }
                }
            } else {
                exclude = runs;   // the rewritten words stay out of every search and expect
            }
        }
        Map<String, long[]> sections = new LinkedHashMap<>();
        sections.put("code", new long[] {0, Math.min((text + 0xFFF) & ~0xFFFL, ro)});
        sections.put("rodata", new long[] {ro, data});
        sections.put("data", new long[] {data, end});
        String id = h.get("BUILD_ID");
        return new CodeModule(image, sections, id == null || id.isEmpty() ? null : id.toUpperCase(Locale.ROOT), text, exclude);
    }
}
