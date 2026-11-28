package dev.peanuts.amiibo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The player's own amiibo from files: .bin dumps picked one by one, a folder of them (with its subfolders), or ZIP packs of them. Each file is read as
 * {@link AmiiboBin} reads it (ID and UID, no keys); one that is no amiibo is counted and left out. The same amiibo twice (ID and UID) is kept once.
 */
public final class AmiiboImport {
    private AmiiboImport() {}

    static final int ZIP_ENTRIES = 4000, DEPTH = 6;

    /** What an import did */
    public static final class Result {
        public int added, known, skipped;
        public final List<String> keys = new ArrayList<>();   // the amiibo added or found, in the files' order
        /** One line for the player: "3 amiibo added. 1 was already there. 2 files are no amiibo." */
        public String words() {
            List<String> s = new ArrayList<>();
            if (added > 0) s.add(added == 1 ? "1 amiibo added." : added + " amiibo added.");
            if (known > 0) s.add(known == 1 ? "1 was already there." : known + " were already there.");
            if (skipped > 0) s.add(skipped == 1 ? "1 file is no amiibo." : skipped + " files are no amiibo.");
            if (s.isEmpty()) s.add("No amiibo found there.");
            return String.join(" ", s);
        }
    }

    // ---- without Android: a file's bytes, a ZIP's stream ----

    public static void bytes(File filesDir, String name, byte[] b, Result r) {
        AmiiboBin.Read a = AmiiboBin.read(b);
        if (a == null) { r.skipped++; return; }
        try {
            java.util.Map.Entry<AmiiboStore.Own, Boolean> e = AmiiboStore.add(filesDir, a.id, a.uid, "file", name);
            if (e.getValue()) r.added++; else r.known++;
            r.keys.add(e.getKey().key);
        } catch (IOException e) { r.skipped++; }
    }

    /**
     * A folder on the device's storage by its path, with its subfolders: every .bin and every ZIP pack in it. The folder registry (#546) keeps its
     * folders as paths, read with All files access; a folder picked once comes as a document tree instead (AmiiboFiles.folder)
     */
    public static Result folder(File filesDir, File dir) {
        Result r = new Result();
        walk(filesDir, dir, 0, r);
        return r;
    }

    private static void walk(File filesDir, File dir, int depth, Result r) {
        File[] fs = depth > DEPTH ? null : dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) { walk(filesDir, f, depth + 1, r); continue; }
            String low = f.getName().toLowerCase(Locale.ROOT);
            try {
                if (low.endsWith(".zip")) { try (InputStream in = new FileInputStream(f)) { zip(filesDir, in, r); } }
                else if (low.endsWith(".bin")) {
                    if (f.length() > AmiiboBin.MAX) r.skipped++;
                    else bytes(filesDir, f.getName(), Files.readAllBytes(f.toPath()), r);
                }
            } catch (IOException | RuntimeException e) { r.skipped++; }
        }
    }

    public static void zip(File filesDir, InputStream in, Result r) throws IOException {
        ZipInputStream z = new ZipInputStream(in);
        int n = 0;
        for (ZipEntry e; (e = z.getNextEntry()) != null && n++ < ZIP_ENTRIES; ) {
            if (e.isDirectory()) continue;
            String name = e.getName(), low = name.toLowerCase(Locale.ROOT);
            if (low.contains("__macosx/") || !low.endsWith(".bin")) continue;
            byte[] b = read(z, AmiiboBin.MAX);
            if (b == null) { r.skipped++; continue; }
            bytes(filesDir, leaf(name), b, r);
        }
    }

    /** At most max bytes, or null when there are more */
    static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int k; (k = in.read(buf)) > 0; ) { o.write(buf, 0, k); if (o.size() > max) return null; }
        return o.toByteArray();
    }

    static String leaf(String path) { int i = path.lastIndexOf('/'); return i < 0 ? path : path.substring(i + 1); }
}
