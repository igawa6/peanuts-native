package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The player's cheat sources of a game (SPEC-mod-menu.md, Package B 3) and the one merged file the host reads (DM_CHEATS: &lt;folder&gt;/&lt;build&gt;.txt). No
 * Android classes: CheatLoaderJvmTest runs it. The folder is the launcher's cheats folder of the game (&lt;files&gt;/cheats/&lt;title ID&gt;/, CheatsUi.folder).
 * <ul>
 * <li>Each import is a source: &lt;folder&gt;/sources/&lt;id&gt;/ with its files by build (&lt;BID&gt;.txt, the game's build and any other the pick held) and
 *     source.tsv (its name, where it came from, when it was added, on or off). A source is switched off or removed as a whole.</li>
 * <li>The cheats that come with the app (the profile's file in the pack) are one more source, {@link #APP}, whose on / off is &lt;folder&gt;/sources/.app.tsv.</li>
 * <li>{@link #merge}: the enabled sources' cheats of the build, in order (the app's first, then by when they were added, then the mods' files), each source's
 *     masters first and its groups as EdiZon's section markers. Credits, titles, group headings, "Restore Code" entries and Not supported cheats are left out.</li>
 * <li>Every cheat's name in the merged file is unique (the host's key: dm_cheat_set, &lt;build&gt;.enabled, the toggles the launcher keeps): its own, or, when an
 *     earlier source has that name already, "Name (Source)". The names are given over every source, the ones switched off too, so switching a source never moves
 *     another source's toggle to another cheat.</li>
 * </ul>
 */
public final class CheatSources {
    private CheatSources() {}

    public static final String APP = ".app", STATE = "source.tsv", APP_STATE = ".app.tsv";
    public static final String FROM_ZIP = "zip", FROM_FILE = "file", FROM_FOLDER = "folder", FROM_EARLIER = "earlier", FROM_APP = "app", FROM_MOD = "mod";

    /** One source */
    public static final class Source {
        public final String id, name, from;
        public final File dir;
        public final long added;
        public final boolean enabled;
        Source(String id, String name, String from, File dir, long added, boolean enabled) { this.id = id; this.name = name; this.from = from; this.dir = dir; this.added = added; this.enabled = enabled; }

        /** Its files by build (BID → the file), sorted by build */
        public Map<String, File> files() {
            Map<String, File> out = new java.util.TreeMap<>();
            File[] l = dir == null ? null : dir.listFiles((d, n) -> n.matches("(?i)[0-9a-f]{16}\\.txt"));
            if (l != null) for (File f : l) out.put(f.getName().substring(0, 16).toUpperCase(Locale.ROOT), f);
            return out;
        }

        /** Its file of this build, read; null when it has none */
        public String text(String build) throws IOException {
            File f = build == null ? null : files().get(build.toUpperCase(Locale.ROOT));
            return f == null ? null : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }

        @Override public String toString() { return id + "(" + name + (enabled ? "" : ", off") + ")"; }
    }

    static File root(File folder) { return new File(folder, "sources"); }

    // ---- The state files: "key \t value" lines ----

    static Map<String, String> readState(File f) {
        Map<String, String> m = new LinkedHashMap<>();
        if (!f.isFile()) return m;
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")) {
                int t = line.indexOf('\t');
                if (t > 0) m.put(line.substring(0, t), line.substring(t + 1).trim());
            }
        } catch (IOException ignored) { }
        return m;
    }

    static void writeState(File f, Map<String, String> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue().replaceAll("[\t\r\n]", " ")).append('\n');
        write(f, sb.toString());
    }

    static void write(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) {
            if (!f.delete() || !tmp.renameTo(f)) { tmp.delete(); throw new IOException("cannot write " + f); }
        }
    }

    // ---- The sources ----

    /** The player's sources (not the app's), by when they were added */
    public static List<Source> list(File folder) {
        List<Source> out = new ArrayList<>();
        File[] l = root(folder).listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
        if (l == null) return out;
        for (File d : l) {
            Map<String, String> s = readState(new File(d, STATE));
            long added;
            try { added = Long.parseLong(s.getOrDefault("added", "0")); } catch (NumberFormatException e) { added = 0; }
            out.add(new Source(d.getName(), s.getOrDefault("name", d.getName()), s.getOrDefault("from", FROM_FILE), d, added, !"0".equals(s.get("enabled"))));
        }
        out.sort((x, y) -> x.added != y.added ? Long.compare(x.added, y.added) : x.id.compareToIgnoreCase(y.id));
        return out;
    }

    /** The source of this id, null when there is none */
    public static Source find(File folder, String id) {
        for (Source s : list(folder)) if (s.id.equals(id)) return s;
        return null;
    }

    /** Whether the app's cheats are on; null when it was never said (the launcher takes the game's old Cheats switch then: {@link #appKnown}) */
    public static Boolean appEnabled(File folder) {
        Map<String, String> s = readState(new File(root(folder), APP_STATE));
        String v = s.get("enabled");
        return v == null ? null : !"0".equals(v);
    }

    public static boolean appKnown(File folder) { return appEnabled(folder) != null; }

    public static void appEnabled(File folder, boolean on) throws IOException {
        Map<String, String> s = readState(new File(root(folder), APP_STATE));
        s.put("enabled", on ? "1" : "0");
        writeState(new File(root(folder), APP_STATE), s);
    }

    /** A name not taken by another source (case aside): name, "name 2", "name 3" … */
    static String uniqueName(List<Source> have, String name) {
        Set<String> low = new HashSet<>();
        for (Source s : have) low.add(s.name.toLowerCase(Locale.ROOT));
        String n = name.trim().isEmpty() ? "Cheats" : name.trim();
        if (n.length() > 60) n = n.substring(0, 60).trim();
        String base = n;
        for (int k = 2; low.contains(n.toLowerCase(Locale.ROOT)); k++) n = base + " " + k;
        return n;
    }

    /** A folder name for a source: letters, digits, space, '.', '-', '_', not starting with '.', not taken */
    static String idFor(File folder, String name) {
        String id = name.replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        while (id.startsWith(".")) id = id.substring(1);
        if (id.isEmpty()) id = "cheats";
        if (id.length() > 60) id = id.substring(0, 60).trim();
        String base = id;
        for (int k = 2; new File(root(folder), id).exists(); k++) id = base + "-" + k;
        return id;
    }

    /** A cheat file's text as two copies of it compare: its line ends, an empty start and a "build" first line (an export's, upstream's kept files') left aside */
    static String body(String text) {
        String t = text.replace("\r\n", "\n").trim();
        int nl = t.indexOf('\n');
        String first = nl < 0 ? t : t.substring(0, nl);
        if (first.trim().matches("(?i)build\\s+[0-9a-f]{16,}")) t = nl < 0 ? "" : t.substring(nl + 1).trim();
        return t;
    }

    /** The source that has this build's file with this text already (an import of the same file again), null when none */
    public static Source sameAs(File folder, String build, String text) {
        String want = body(text);
        for (Source s : list(folder)) {
            try { String t = s.text(build); if (t != null && body(t).equals(want)) return s; } catch (IOException ignored) { }
        }
        return null;
    }

    /** A new source with these files (BID → text), on. Returns it */
    public static Source add(File folder, String name, String from, Map<String, String> byBuild, long now) throws IOException {
        String n = uniqueName(list(folder), name), id = idFor(folder, n);
        File dir = new File(root(folder), id);
        if (!dir.mkdirs()) throw new IOException("cannot create " + dir);
        try {
            for (Map.Entry<String, String> e : byBuild.entrySet()) write(new File(dir, e.getKey().toUpperCase(Locale.ROOT) + ".txt"), e.getValue().replace("\r\n", "\n"));
            Map<String, String> s = new LinkedHashMap<>();
            s.put("name", n); s.put("from", from); s.put("added", String.valueOf(now)); s.put("enabled", "1");
            writeState(new File(dir, STATE), s);
        } catch (IOException e) {
            deleteTree(dir);
            throw e;
        }
        return find(folder, id);
    }

    /** A source switched on or off as a whole */
    public static void enabled(File folder, String id, boolean on) throws IOException {
        File f = new File(new File(root(folder), id), STATE);
        if (!f.isFile()) throw new IOException("no source " + id);
        Map<String, String> s = readState(f);
        s.put("enabled", on ? "1" : "0");
        writeState(f, s);
    }

    /** A source removed with its files; false when there is no such source */
    public static boolean remove(File folder, String id) {
        if (id == null || id.isEmpty() || id.startsWith(".") || id.contains("/") || id.contains("\\")) return false;
        File dir = new File(root(folder), id);
        if (!new File(dir, STATE).isFile()) return false;
        deleteTree(dir);
        return !dir.exists();
    }

    static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteTree(c);
        f.delete();
    }

    /**
     * The files of upstream's earlier import (&lt;folder&gt;/imported/*.txt, each starting with "build &lt;ID&gt;") made into sources, one a file, by their names'
     * order, then removed. Returns the new sources (empty: nothing to move)
     */
    public static List<Source> migrate(File folder, long now) throws IOException {
        List<Source> out = new ArrayList<>();
        File[] l = new File(folder, "imported").listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (l == null) return out;
        java.util.Arrays.sort(l, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        int k = 0;
        for (File f : l) {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            CheatFile.Parsed p = CheatFile.parse(text, -1);
            if (p.build == null) continue;
            Map<String, String> files = new LinkedHashMap<>();
            files.put(p.build, text);
            out.add(add(folder, f.getName().replaceFirst("(?i)\\.txt$", ""), FROM_EARLIER, files, now + k++));
            if (!f.delete()) throw new IOException("cannot remove " + f);
        }
        new File(folder, "imported").delete();
        return out;
    }

    /** Sources just made (moved: an import of an export, CheatSources.migrate) whose file of this build another source has already: removed again. Returns how many stay */
    public static int dropDuplicates(File folder, List<Source> moved, String build) {
        int stay = 0;
        Set<String> ids = new HashSet<>();
        for (Source m : moved) ids.add(m.id);
        for (Source m : moved) {
            boolean dup = false;
            try {
                String t = m.text(build);
                if (t != null) for (Source o : list(folder)) {
                    if (o.id.equals(m.id) || ids.contains(o.id) && o.added > m.added) continue;   // a later one of the same batch is not "already there"
                    String u = o.text(build);
                    if (u != null && body(u).equals(body(t))) { dup = true; break; }
                }
            } catch (IOException ignored) { }
            if (dup) remove(folder, m.id); else stay++;
        }
        return stay;
    }

    // ---- Export ----

    /**
     * The sources' files of this build as one .zip in upstream's export format (Cheats.exportZip / readZip): cheats/&lt;source&gt;.txt, each starting with a line
     * "build &lt;ID&gt;", and toggles.json {"title_id", "build", "cheats" (the game's switch), "on" (the toggles' keys)}. Upstream's Import cheats… takes it back,
     * and so does an import of the zip as a cheat file (CheatImport: a "build" line). Returns how many files went in
     */
    public static int exportZip(java.io.OutputStream os, String titleId, String build, File folder, boolean master, Set<String> on) throws IOException {
        int n = 0;
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(os)) {
            if (build != null) for (Source s : list(folder)) {
                String t = s.text(build);
                if (t == null) continue;
                z.putNextEntry(new java.util.zip.ZipEntry("cheats/" + s.id + ".txt"));
                z.write(withBuild(t, build).getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                n++;
            }
            StringBuilder j = new StringBuilder("{\"title_id\": ").append(json(titleId)).append(", \"build\": ").append(json(build == null ? "" : build))
                    .append(", \"cheats\": ").append(master).append(", \"on\": [");
            int k = 0;
            for (String key : on) j.append(k++ == 0 ? "" : ", ").append(json(key));
            j.append("]}\n");
            z.putNextEntry(new java.util.zip.ZipEntry("toggles.json"));
            z.write(j.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return n;
    }

    /** A file's text with "build &lt;ID&gt;" as its first line (an earlier build line, and empty lines before it, left out) */
    static String withBuild(String text, String build) {
        StringBuilder sb = new StringBuilder("build ").append(build).append('\n');
        boolean first = true;
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (first && line.trim().isEmpty()) continue;
            if (first && line.trim().matches("(?i)build\\s+[0-9a-f]{16,}")) { first = false; continue; }
            first = false;
            sb.append(line).append('\n');
        }
        return sb.toString().replaceAll("\n+$", "\n");
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    // ---- The merged file ----

    /** One source's file of the build, read, for the merge: its id, its name, whether it is on */
    public static final class Block {
        public final String id, name;
        public final boolean enabled;
        public final CheatFile.Parsed parsed;
        public Block(String id, String name, boolean enabled, CheatFile.Parsed parsed) { this.id = id; this.name = name; this.enabled = enabled; this.parsed = parsed; }
    }

    /** The merge's blocks of a game: the app's file (appText, null or empty: none) and each player's source that has a file of this build */
    public static List<Block> blocks(File folder, String appText, boolean appOn, String build, long textEnd) {
        List<Block> out = new ArrayList<>();
        if (appText != null && !appText.isEmpty()) out.add(new Block(APP, "Comes with the app", appOn, CheatFile.parse(appText, textEnd)));
        for (Source s : list(folder)) {
            try {
                String t = s.text(build);
                if (t != null) out.add(new Block(s.id, s.name, s.enabled, CheatFile.parse(t, textEnd)));
            } catch (IOException ignored) { }   // a file that cannot be read: its source has no cheats this time
        }
        return out;
    }

    /** One cheat or master in the merge: its block, the cheat, and its name in the merged file (the host's key) */
    public static final class Row {
        public final Block block;
        public final CheatFile.Cheat cheat;
        public final String key;
        Row(Block block, CheatFile.Cheat cheat, String key) { this.block = block; this.cheat = cheat; this.key = key; }
    }

    /** The merged file and the keys of every cheat of every block (switched-off sources' too) */
    public static final class Merged {
        public final String text;
        public final List<Row> rows;
        public final int cheats, masters;   // what the text holds
        Merged(String text, List<Row> rows, int cheats, int masters) { this.text = text; this.rows = rows; this.cheats = cheats; this.masters = masters; }

        /** The key of a block's cheat (by its key in its own file), null when it has none in the merge (Not supported) */
        public String key(String blockId, String ownKey) {
            for (Row r : rows) if (r.block.id.equals(blockId) && r.cheat.key.equals(ownKey)) return r.key;
            return null;
        }

        /** The keys of the cheats the player may switch, in the merged file's order */
        public List<String> keys() {
            List<String> out = new ArrayList<>();
            for (Row r : rows) if (r.block.enabled && !r.cheat.master && r.cheat.switchable()) out.add(r.key);
            return out;
        }
    }

    /** A name the host reads as one header's name: no brackets or braces, no line breaks, at most NAME_MAX characters */
    static String safe(String s) {
        String t = s.replaceAll("[\\[\\]{}\r\n\t]", "_").trim();
        return t.length() > CheatFile.NAME_MAX ? t.substring(0, CheatFile.NAME_MAX).trim() : t;
    }

    /** name with suffix, the name cut so the whole fits NAME_MAX */
    static String fit(String name, String suffix) {
        if (suffix.length() > 60) suffix = suffix.substring(0, 59) + ")";
        return (name.length() + suffix.length() > CheatFile.NAME_MAX ? name.substring(0, CheatFile.NAME_MAX - suffix.length()).trim() : name) + suffix;
    }

    /** The merged file of these blocks (CheatSources.blocks, then the mods' blocks) */
    public static Merged merge(List<Block> blocks) { return merge(blocks, java.util.Collections.emptyMap()); }

    /** The same, a Value cheat with the player's number when values has its key in lower case (CheatValues.read: the in-game Mods tab's number field) */
    public static Merged merge(List<Block> blocks, Map<String, CheatValues.Kept> values) {
        Set<String> taken = new HashSet<>();   // lower case: the host compares keys without case (dm_cheat_set)
        List<Row> rows = new ArrayList<>();
        for (Block b : blocks) {
            List<CheatFile.Cheat> all = new ArrayList<>(b.parsed.masters);
            all.addAll(b.parsed.cheats);
            for (CheatFile.Cheat c : all) {
                if (!c.master && !c.switchable()) continue;   // Not supported: never in the merged file
                String base = safe(c.key), n = base;
                if (n.isEmpty()) n = base = "Cheat";
                if (taken.contains(n.toLowerCase(Locale.ROOT))) n = fit(base, " (" + safe(b.name) + ")");
                for (int k = 2; taken.contains(n.toLowerCase(Locale.ROOT)); k++) n = fit(base, " (" + safe(b.name) + " " + k + ")");
                taken.add(n.toLowerCase(Locale.ROOT));
                rows.add(new Row(b, c, n));
            }
        }
        int on = 0;
        for (Block b : blocks) if (b.enabled && b.parsed.any()) on++;
        StringBuilder sb = new StringBuilder();
        int cheats = 0, masters = 0;
        for (Block b : blocks) {
            if (!b.enabled) continue;
            String open = null;
            for (int pass = 0; pass < 2; pass++) {   // the masters first, then the cheats in the file's order
                for (Row r : rows) {
                    if (r.block != b || r.cheat.master != (pass == 0)) continue;
                    String sec = pass == 0 ? null : section(on > 1 ? b.name : null, r.cheat.group);
                    if (pass == 1 && (sec == null ? open != null : !sec.equals(open))) {
                        if (open != null) sb.append("[--SectionEnd:").append(open).append("--]\n");
                        if (sec != null) sb.append("[--SectionStart:").append(sec).append("--]\n");
                        open = sec;
                    }
                    sb.append(r.cheat.master ? "{" : "[").append(r.key).append(r.cheat.master ? "}" : "]").append('\n');
                    List<String> set = CheatValues.apply(r.cheat, values.get(r.key.toLowerCase(Locale.ROOT)));
                    for (String l : set != null ? set : r.cheat.lines) sb.append(l).append('\n');
                    if (r.cheat.master) masters++; else cheats++;
                }
            }
            if (open != null) sb.append("[--SectionEnd:").append(open).append("--]\n");
        }
        return new Merged(sb.toString(), rows, cheats, masters);
    }

    /** A group's section in the merged file: the group, with the source's name in front when more than one source is on; null for neither */
    static String section(String source, String group) {
        String s = source == null || source.isEmpty() ? group : group.isEmpty() ? source : source + ": " + group;
        if (s == null || s.isEmpty()) return null;
        return safe(s).replace("--", "-");
    }
}
