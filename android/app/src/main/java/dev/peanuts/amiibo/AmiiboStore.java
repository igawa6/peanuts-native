package dev.peanuts.amiibo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The player's own amiibo: one folder each under files/amiibo/own, named by ID and UID. tag.txt says what it is (ID, UID, where it came from: an imported
 * file or the app's list), placed.tsv when it was placed in which game. The host writes the games' data next to it (dm/peanuts/peanuts_amiibo.c): one
 * area-&lt;access ID&gt;.bin per game with the game's title ID beside it (.title), and common.txt (how often and when last written). No Android class: the JVM
 * tests use a folder of their own.
 */
public final class AmiiboStore {
    private AmiiboStore() {}

    private static final SecureRandom RNG = new SecureRandom();

    public static File root(File filesDir) { return new File(new File(filesDir, "amiibo"), "own"); }

    /** One of the player's amiibo */
    public static final class Own {
        public final String key, id, uid, from, file;   // from: "file" (imported) or "list" (picked from the app's list)
        public final long added;
        public final File dir;
        Own(File dir, String id, String uid, String from, String file, long added) {
            this.dir = dir; this.key = dir.getName(); this.id = id; this.uid = uid; this.from = from; this.file = file; this.added = added;
        }
        public boolean imported() { return "file".equals(from); }
    }

    /** A game's data on an amiibo: the area the game made (its access ID), the game's title ID when known, its bytes */
    public static final class Area {
        public final String access, titleId;
        public final byte[] data;
        Area(String access, String titleId, byte[] data) { this.access = access; this.titleId = titleId; this.data = data; }
    }

    public static List<Own> list(File filesDir) {
        File[] dirs = root(filesDir).listFiles(File::isDirectory);
        List<Own> out = new ArrayList<>();
        if (dirs != null) for (File d : dirs) { Own o = read(d); if (o != null) out.add(o); }
        out.sort(Comparator.comparingLong((Own o) -> o.added).thenComparing(o -> o.key));
        return out;
    }

    static Own read(File dir) {
        Map<String, String> m = props(new File(dir, "tag.txt"));
        String id = AmiiboDb.id(m.get("id")), uid = m.get("uid");
        if (id == null || uid == null || !uid.matches("[0-9a-f]{14}")) return null;
        long added; try { added = Long.parseLong(m.getOrDefault("added", "0")); } catch (NumberFormatException e) { added = 0; }
        return new Own(dir, id, uid, m.getOrDefault("from", "file"), m.getOrDefault("file", ""), added);
    }

    public static Own find(File filesDir, String key) {
        if (key == null || !key.matches("[0-9a-f]{16}-[0-9a-f]{14}")) return null;
        File d = new File(root(filesDir), key);
        return d.isDirectory() ? read(d) : null;
    }

    /** Adds an amiibo (or finds it: the same ID and UID is the same amiibo); the second value is true when it is new */
    public static Map.Entry<Own, Boolean> add(File filesDir, String id, String uid, String from, String file) throws IOException {
        id = AmiiboDb.id(id);
        if (id == null || uid == null || !uid.matches("[0-9a-f]{14}")) throw new IOException("not an amiibo ID and UID");
        File d = new File(root(filesDir), id + "-" + uid);
        Own had = d.isDirectory() ? read(d) : null;
        if (had != null) return new java.util.AbstractMap.SimpleEntry<>(had, false);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot make " + d);
        long now = System.currentTimeMillis();
        String text = "id=" + id + "\nuid=" + uid + "\nfrom=" + from + "\nfile=" + clean(file) + "\nadded=" + now + "\n";
        write(new File(d, "tag.txt"), text.getBytes(StandardCharsets.UTF_8));
        return new java.util.AbstractMap.SimpleEntry<>(read(d), true);
    }

    /** An amiibo of the app's list made the player's own: a tag of its own with a new UID (an NXP one, as every figure's) */
    public static Own fromList(File filesDir, String id) throws IOException {
        byte[] uid = new byte[7];
        RNG.nextBytes(uid);
        uid[0] = 0x04;
        return add(filesDir, id, AmiiboBin.hex(uid), "list", "").getKey();
    }

    /** The one own amiibo of this ID picked from the list, if there is one (a second pick of the same amiibo reuses it, with its data) */
    public static Own listed(File filesDir, String id) {
        for (Own o : list(filesDir)) if (o.id.equals(id) && "list".equals(o.from)) return o;
        return null;
    }

    public static void remove(Own o) throws IOException { delete(o.dir); }

    /** The games' data on it */
    public static List<Area> areas(Own o) {
        File[] fs = o.dir.listFiles((d, n) -> n.matches("area-[0-9a-f]{8}\\.bin"));
        List<Area> out = new ArrayList<>();
        if (fs == null) return out;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            String access = f.getName().substring(5, 13);
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                File t = new File(o.dir, "area-" + access + ".title");
                String title = t.isFile() ? new String(Files.readAllBytes(t.toPath()), StandardCharsets.UTF_8).trim().toUpperCase(Locale.ROOT) : null;
                out.add(new Area(access, title, data));
            } catch (IOException e) { /* a file the host is writing: left out this time */ }
        }
        return out;
    }

    /** Whether this game has data on the amiibo */
    public static boolean hasData(Own o, String titleId) {
        for (Area a : areas(o)) if (titleId != null && titleId.equalsIgnoreCase(a.titleId)) return true;
        return false;
    }

    /** Removes this game's data from the amiibo: the game starts over with it, as after the console's "Delete game data" */
    public static int reset(Own o, String titleId) throws IOException {
        int n = 0;
        for (Area a : areas(o)) {
            if (titleId == null || !titleId.equalsIgnoreCase(a.titleId)) continue;
            Files.deleteIfExists(new File(o.dir, "area-" + a.access + ".bin").toPath());
            Files.deleteIfExists(new File(o.dir, "area-" + a.access + ".title").toPath());
            n++;
        }
        return n;
    }

    /** How often and when the games last wrote to it: {writes, "YYYY-MM-DD" or null} */
    public static String[] common(Own o) {
        Map<String, String> m = props(new File(o.dir, "common.txt"));
        return new String[] {m.getOrDefault("writes", "0"), m.get("written")};
    }

    // ---- placed: per game, how often and when last ----

    /** Notes that it was placed in this game now */
    public static void placed(Own o, String titleId, long now) {
        if (titleId == null) return;
        Map<String, long[]> m = placedAll(o);
        long[] v = m.computeIfAbsent(titleId.toUpperCase(Locale.ROOT), k -> new long[2]);
        v[0]++; v[1] = now;
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, long[]> e : m.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        try { write(new File(o.dir, "placed.tsv"), b.toString().getBytes(StandardCharsets.UTF_8)); } catch (IOException e) { /* a count only */ }
    }

    /** {times placed, when last (ms)} in this game; {0, 0} when never */
    public static long[] placedIn(Own o, String titleId) {
        long[] v = titleId == null ? null : placedAll(o).get(titleId.toUpperCase(Locale.ROOT));
        return v == null ? new long[2] : v;
    }

    static Map<String, long[]> placedAll(Own o) {
        Map<String, long[]> m = new LinkedHashMap<>();
        File f = new File(o.dir, "placed.tsv");
        if (!f.isFile()) return m;
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String[] p = line.split("\t");
                if (p.length == 3) try { m.put(p[0], new long[] {Long.parseLong(p[1]), Long.parseLong(p[2])}); } catch (NumberFormatException e) { /* skip */ }
            }
        } catch (IOException e) { /* none */ }
        return m;
    }

    /**
     * The player's amiibo for a game: those that do something in it (fits) or were placed in it before, the last placed in it first, then the newest
     * added. extra: IDs that count as fitting besides the table's (an empty set for none)
     */
    public static List<Own> forGame(List<Own> own, String titleId, Set<String> fits) {
        List<Own> out = new ArrayList<>();
        for (Own o : own) if (fits.contains(o.id) || placedIn(o, titleId)[0] > 0) out.add(o);
        out.sort(Comparator.comparingLong((Own o) -> -placedIn(o, titleId)[1]).thenComparingLong(o -> -o.added));
        return out;
    }

    // ---- files ----

    static Map<String, String> props(File f) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!f.isFile()) return m;
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) m.put(line.substring(0, i), line.substring(i + 1).trim());
            }
        } catch (IOException e) { /* empty */ }
        return m;
    }

    static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), data);
        if (!tmp.renameTo(f)) { Files.deleteIfExists(f.toPath()); if (!tmp.renameTo(f)) throw new IOException("cannot write " + f); }
    }

    static void delete(File f) throws IOException {
        File[] in = f.listFiles();
        if (in != null) for (File c : in) delete(c);
        Files.deleteIfExists(f.toPath());
    }

    static String clean(String s) { return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim(); }

}
