package dev.peanuts.mods;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What a picked .zip, .txt or folder holds for a game (SPEC-mod-menu.md, Package B 2). No Android classes: CheatLoaderJvmTest runs it.
 * <p>
 * A cheat file is found in any of these places (any folders above them, any case):
 * <ul>
 * <li>Atmosphère: atmosphere/contents/&lt;TID&gt;/cheats/&lt;BID&gt;.txt, also titles/ and without the atmosphere/ folder;</li>
 * <li>Eden and Yuzu: load/&lt;TID&gt;/&lt;name&gt;/cheats/&lt;BID&gt;.txt, also without load/;</li>
 * <li>Ryujinx: mods/contents/&lt;TID&gt;/&lt;name&gt;/cheats/&lt;BID&gt;.txt;</li>
 * <li>a mod's own folder: &lt;name&gt;/cheats/&lt;BID&gt;.txt;</li>
 * <li>a bare &lt;BID&gt;.txt, or any .txt whose first line is "build &lt;BID&gt;".</li>
 * </ul>
 * BID is 16 hex digits (the first 8 bytes of main's build ID) or 32. A file whose BID is one of the game's main builds is for the game; any other is "for another
 * version", with its BID; one under another game's TID is left out. A pick with romfs or exefs folders is a mod and goes to the mod import (ModImporter), which
 * takes its cheats along. Nothing here writes: {@link CheatSources} keeps what the player adds.
 */
public final class CheatImport {
    private CheatImport() {}

    public static final int MAX_FILE = 256 * 1024, MAX_TXT = 64, MAX_PATHS = 20000;

    /** A .txt of the pick: its path in it ('/' between folders) and its bytes */
    public static final class In {
        public final String path;
        public final byte[] data;
        public In(String path, byte[] data) { this.path = path; this.data = data; }
    }

    /** One cheat file found */
    public static final class Hit {
        public final String path, tid, bid, set;   // tid: the layout's title ID (null: none); bid: 16 hex, upper case; set: the Eden / Ryujinx / mod folder's name, null
        public final String text;
        public final CheatFile.Parsed parsed;
        Hit(String path, String tid, String bid, String set, String text, CheatFile.Parsed parsed) {
            this.path = path; this.tid = tid; this.bid = bid; this.set = set; this.text = text; this.parsed = parsed;
        }
        /** The cheats it holds that can be listed (the Not supported ones too) */
        public int cheats() { return parsed.cheats.size(); }
        @Override public String toString() { return path + ":" + bid + (set != null ? "/" + set : "") + ":" + cheats(); }
    }

    /** What the pick holds */
    public static final class Found {
        public final List<Hit> forGame = new ArrayList<>(), otherVersion = new ArrayList<>(), otherGame = new ArrayList<>();
        public final List<String> noBuild = new ArrayList<>();   // cheat files that name no build (their paths)
        public boolean mod;                                       // romfs or exefs folders in it: a mod
        public String name;                                       // the source's name

        public int cheats() { int n = 0; for (Hit h : forGame) n += h.parsed.switchable(); return n; }
        /** The game's cheats that are Not supported (listed, never switched) */
        public int unsupported() { int n = 0; for (Hit h : forGame) n += h.parsed.unsupported(); return n; }
        public int otherCheats() { int n = 0; for (Hit h : otherVersion) n += h.cheats(); return n; }
        public boolean restores() { for (Hit h : forGame) if (!h.parsed.restores.isEmpty()) return true; return false; }
        public int groups() { int n = 0; for (Hit h : forGame) n += h.parsed.groups.size(); return n; }

        /** The sources it makes: name → BID → text, the game's files and the other versions' (a set's own name for an Eden or Ryujinx folder, else name) */
        public Map<String, Map<String, String>> sources() {
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            List<Hit> all = new ArrayList<>(forGame);
            all.addAll(otherVersion);
            for (Hit h : all) out.computeIfAbsent(h.set != null ? h.set : name, k -> new LinkedHashMap<>()).putIfAbsent(h.bid, h.text);
            return out;
        }
    }

    private static final String BID = "([0-9A-Fa-f]{16})(?:[0-9A-Fa-f]{16})?\\.txt$", TID = "([0-9A-Fa-f]{16})";
    private static final Pattern RYUJINX = Pattern.compile("(?i)(?:^|/)mods/contents/" + TID + "/([^/]+)/cheats/" + BID);
    private static final Pattern ATMOSPHERE = Pattern.compile("(?i)(?:^|/)(?:atmosphere/)?(?:contents|titles)/" + TID + "/cheats/" + BID);
    private static final Pattern EDEN = Pattern.compile("(?i)(?:^|/)(?:load/)?" + TID + "/([^/]+)/cheats/" + BID);
    private static final Pattern SET = Pattern.compile("(?i)(?:^|/)([^/]+)/cheats/" + BID);
    private static final Pattern BARE = Pattern.compile("(?i)(?:^|/)" + BID);
    private static final Pattern HEX16 = Pattern.compile("(?i)[0-9a-f]{16}");

    /** The pick's path with '/' between folders and no leading "./" or "/" */
    static String norm(String path) {
        String p = path.replace('\\', '/');
        while (p.startsWith("./") || p.startsWith("/")) p = p.substring(p.startsWith("./") ? 2 : 1);
        return p;
    }

    /** Whether two title IDs are the same game (an update's or another program's ID differs in its last three digits) */
    static boolean sameGame(String a, String b) {
        if (a == null || b == null || !HEX16.matcher(a).matches() || !HEX16.matcher(b).matches()) return a != null && a.equalsIgnoreCase(b);
        return (Long.parseUnsignedLong(a, 16) & ~0xFFFL) == (Long.parseUnsignedLong(b, 16) & ~0xFFFL);
    }

    /** {tid or null, bid or null, set or null} of a path, by its layout */
    static String[] layout(String path) {
        String p = norm(path);
        Matcher m;
        if ((m = RYUJINX.matcher(p)).find()) return new String[] {m.group(1), m.group(3), m.group(2)};
        if ((m = ATMOSPHERE.matcher(p)).find()) return new String[] {m.group(1), m.group(2), null};
        if ((m = EDEN.matcher(p)).find()) return new String[] {m.group(1), m.group(3), m.group(2)};
        if ((m = SET.matcher(p)).find()) {
            String set = m.group(1);
            return HEX16.matcher(set).matches() ? new String[] {set, m.group(2), null} : new String[] {null, m.group(2), set};
        }
        if ((m = BARE.matcher(p)).find()) return new String[] {null, m.group(1), null};
        return new String[] {null, null, null};
    }

    /** Whether a path is in a romfs or exefs folder (a mod's) */
    static boolean modPath(String path) {
        for (String seg : norm(path).toLowerCase(Locale.ROOT).split("/")) if (seg.equals("romfs") || seg.equals("exefs") || seg.equals("exefs_patches")) return true;
        return false;
    }

    /** The source's name from the pick's name (its zip's or folder's, a file's without .txt); a build ID, or nothing: the credit's site ("cheatslips"), else "Cheats" */
    static String name(String hint, List<Hit> hits) {
        String n = hint == null ? "" : new File(norm(hint)).getName().replaceFirst("(?i)\\.(zip|txt)$", "").trim();
        if (!n.isEmpty() && !n.matches("(?i)[0-9a-f]{16}([0-9a-f]{16})?")) return n;
        for (Hit h : hits) for (String c : h.parsed.credits) {
            Matcher m = Pattern.compile("(?i)(?:www\\.)?([a-z0-9-]+)\\.(com|net|org|io|gg|de|fr|jp|co|me|xyz)\\b").matcher(c);
            if (m.find()) return m.group(1);
        }
        return "Cheats";
    }

    /**
     * The pick read for a game: files, its .txt files; paths, every path in it (for the romfs / exefs check); titleId, the game's; builds, its main builds (16 hex,
     * upper case); nameHint, the zip's, folder's or file's name
     */
    public static Found read(List<In> files, List<String> paths, String titleId, Set<String> builds, String nameHint) {
        Found f = new Found();
        for (String p : paths) if (modPath(p)) { f.mod = true; break; }
        List<String> taken = new ArrayList<>();   // the game's files' texts: one zip with the same file in two layouts adds it once
        for (In in : files) {
            if (in.data.length > MAX_FILE) continue;
            String text = new String(in.data, StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) continue;
            CheatFile.Parsed parsed = CheatFile.parse(text, -1);
            if (!parsed.any()) continue;   // a readme, a site's empty marker file
            String[] l = layout(in.path);
            String bid = l[1] != null ? l[1].substring(0, 16).toUpperCase(Locale.ROOT) : parsed.build;
            if (bid != null && titleId != null && bid.equalsIgnoreCase(titleId) && l[0] == null) bid = parsed.build;   // a file named by the title ID is not a build's
            if (bid == null) { f.noBuild.add(norm(in.path)); continue; }
            Hit h = new Hit(norm(in.path), l[0] != null ? l[0].toUpperCase(Locale.ROOT) : null, bid, l[2], text, parsed);
            if (h.tid != null && titleId != null && !sameGame(h.tid, titleId)) f.otherGame.add(h);
            else if (builds.contains(bid)) {
                String norm = text.replace("\r\n", "\n").trim();
                if (taken.contains(norm)) continue;
                taken.add(norm);
                f.forGame.add(h);
            } else f.otherVersion.add(h);
        }
        List<Hit> all = new ArrayList<>(f.forGame);
        all.addAll(f.otherVersion);
        f.name = name(nameHint, all);
        return f;
    }

    // ---- Reading a pick ----

    private static byte[] readMax(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) {
            o.write(b, 0, n);
            if (o.size() > max) return null;
        }
        return o.toByteArray();
    }

    /** A zip's .txt files (at most MAX_TXT, each at most MAX_FILE) and every path in it into paths */
    public static List<In> fromZip(InputStream in, List<String> paths) throws IOException {
        List<In> out = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (paths.size() < MAX_PATHS) paths.add(n);
                if (e.isDirectory() || !n.toLowerCase(Locale.ROOT).endsWith(".txt") || out.size() >= MAX_TXT) continue;
                byte[] b = readMax(z, MAX_FILE);
                if (b != null) out.add(new In(n, b));
            }
        } catch (IllegalArgumentException e) {   // a name the zip's charset cannot read
            throw new IOException("the zip cannot be read", e);
        }
        return out;
    }

    /** A folder's .txt files and paths, as fromZip (relative to root, its own name first, as a zip of it would have them); a romfs or exefs folder is not walked into */
    public static List<In> fromFolder(File root, List<String> paths) throws IOException {
        List<In> out = new ArrayList<>();
        walk(root, root.getName(), 0, out, paths);
        return out;
    }

    private static void walk(File dir, String rel, int depth, List<In> out, List<String> paths) throws IOException {
        File[] l = dir.listFiles();
        if (l == null || depth > 12) return;
        java.util.Arrays.sort(l, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        for (File f : l) {
            String p = rel + "/" + f.getName();
            if (paths.size() >= MAX_PATHS) return;
            paths.add(f.isDirectory() ? p + "/" : p);
            if (f.isDirectory()) { if (!modPath(p)) walk(f, p, depth + 1, out, paths); continue; }
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".txt") || out.size() >= MAX_TXT || f.length() > MAX_FILE) continue;
            out.add(new In(p, Files.readAllBytes(f.toPath())));
        }
    }
}
