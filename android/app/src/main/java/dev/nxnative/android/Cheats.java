package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The launcher's side of the host's cheat engine (dm/host/hle_cheats.c; the front end's contract in docs/frontends.md "Cheats"), for a game whose profile has
 * cheats rows (game.tsv; build.gradle cheatsOf, Profile.cheats). No Android classes: CheatsJvmTest runs it on the JVM (dm/tools/tests/test_android_cheats.py).
 * <ul>
 * <li>The rule: with the game's Game settings › Cheats off, nothing is passed to the host (no DM_CHEATS) and the in-game panel lists no cheat ({@link #env}, {@link #panel}).</li>
 * <li>The files: the profile's file, which Prepare copies into the pack (pack_android.py place_cheats: &lt;pack&gt;/cheats/&lt;build&gt;.txt), and the player's
 *     imported files, kept in the app's own folder of the game (&lt;files&gt;/cheats/&lt;title ID&gt;/imported/, each starting with a "build &lt;ID&gt;" line). An import is
 *     taken only when its file name (&lt;build ID&gt;.txt, as Atmosphère names them) or its "build" line names the game's main build ({@link #checkImport}).</li>
 * <li>Each start builds one merged file, the profile's cheats first and then each imported file's under a section "Imported: &lt;file&gt;", an imported name that clashes
 *     getting " (imported)" ({@link #merge}), as &lt;files&gt;/cheats/&lt;title ID&gt;/&lt;build&gt;.txt, with &lt;build&gt;.enabled from the player's toggles; the launcher passes
 *     that folder as DM_CHEATS. The host parses the merged file as any cheat file ({@link #install}).</li>
 * <li>Export / import of the imported files and the toggles as one .zip: cheats/&lt;file&gt;.txt and toggles.json ({@link #exportZip}, {@link #importZip}). It touches
 *     nothing but the game's cheats folder: never a save.</li>
 * </ul>
 * The entries are read as the host reads them (hle_cheats.c parse_file): a line starting with [Name] or {Name} (after spaces) starts an entry, {Name} the master
 * (always on, never listed); words of 8 hex digits (or runs of them) are code; an entry without code words is a label (credits, or EdiZon's [--SectionStart:X--] /
 * [--SectionEnd:X--] markers, which give the entries between them a section); a duplicate name's later entries are keyed "Name #2", "Name #3" (cheat_key).
 */
final class Cheats {
    private Cheats() {}

    static final String SECTION_IMPORTED = "Imported: ";
    static final String SUFFIX = " (imported)";
    static final int MAX_FILE = 256 * 1024, MAX_ZIP_FILES = 64;

    /** One entry of a cheat file: its name, the key the host and the toggles use, its section ("" none), and what it is */
    static final class Entry {
        final String name, key, section;
        final boolean master, label;
        Entry(String name, String key, String section, boolean master, boolean label) { this.name = name; this.key = key; this.section = section; this.master = master; this.label = label; }
        /** A row the player toggles: no master, no label */
        boolean toggles() { return !master && !label; }
        boolean imported() { return section.startsWith(SECTION_IMPORTED); }
        @Override public String toString() { return (master ? "{" + name + "}" : label ? "(" + name + ")" : key) + (section.isEmpty() ? "" : "@" + section); }
    }

    /** The header of a line ([Name] or {Name} after spaces): {open char, name, the rest of the line after the header}, null when the line has none or an empty name */
    static String[] header(String line) {
        String t = line.replaceFirst("^[ \t]+", "");
        if (t.isEmpty() || (t.charAt(0) != '[' && t.charAt(0) != '{')) return null;
        int e = t.indexOf(t.charAt(0) == '[' ? ']' : '}');
        if (e <= 1) return null;
        String name = t.substring(1, e);
        if (name.length() > 127) name = name.substring(0, 127);
        return new String[] {t.substring(0, 1), name, t.substring(e + 1)};
    }

    /** Whether a line (or a header's rest) is code words only: tokens of hex digits whose length is a multiple of 8, at least one */
    static boolean codeLine(String s) {
        String[] tok = s.trim().split("[ \t\r]+");
        if (tok.length == 0 || tok[0].isEmpty()) return false;
        for (String t : tok) if (t.length() % 8 != 0 || !t.matches("[0-9A-Fa-f]+")) return false;
        return true;
    }

    /** EdiZon's section marker: 1 start, 2 end, 0 none; out[0] gets the section's name */
    static int sectionMarker(String name, String[] out) {
        if (!name.endsWith("--")) return 0;
        int kind = name.startsWith("--SectionStart:") ? 1 : name.startsWith("--SectionEnd:") ? 2 : 0;
        if (kind == 0) return 0;
        int s = kind == 1 ? 15 : 13;
        if (name.length() - 2 < s) return 0;
        out[0] = name.substring(s, name.length() - 2);
        return kind;
    }

    /** The entries of a cheat file, in file order */
    static List<Entry> parse(String text) {
        List<String> names = new ArrayList<>();
        List<Boolean> masters = new ArrayList<>(), code = new ArrayList<>();
        List<String> sections = new ArrayList<>();
        String section = "";
        int cur = -1;
        for (String line : text.split("\n", -1)) {
            String[] h = header(line);
            String rest = line;
            if (h != null) {
                String[] sec = new String[1];
                int mark = sectionMarker(h[1], sec);
                if (mark == 1) section = sec[0];
                names.add(h[1]); masters.add(h[0].equals("{")); code.add(false); sections.add(mark == 1 ? sec[0] : section);
                if (mark == 2) section = "";
                cur = names.size() - 1;
                rest = h[2];
            }
            if (cur >= 0 && codeLine(rest)) code.set(cur, true);
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            int dup = 0;
            for (int k = 0; k < i; k++) if (names.get(k).equals(names.get(i))) dup++;
            String key = dup == 0 ? names.get(i) : names.get(i) + " #" + (dup + 1);
            out.add(new Entry(names.get(i), key, sections.get(i), masters.get(i), !code.get(i)));
        }
        return Collections.unmodifiableList(out);
    }

    // ---- The build gate ----

    /** The main build IDs, first 16 hex digits upper case, of "main=&lt;ID&gt;" tokens (a pack's build rows, a profile's builds column) */
    static Set<String> builds16(String... texts) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : texts) if (t != null) for (String w : t.split("[\\s,;]+"))
            if (w.startsWith("main=") && w.length() >= 21 && w.substring(5, 21).matches("[0-9A-Fa-f]{16}")) out.add(w.substring(5, 21).toUpperCase(Locale.ROOT));
        return out;
    }

    /** The build a cheat file says it is for: its file name's stem when that is a build ID (16 or 32 hex digits, as Atmosphère names cheat files), else its first
     *  line that is not empty, when that is "build &lt;ID&gt;" (16 hex digits or more); first 16 hex digits, upper case; null when neither says */
    static String buildOf(String text, String fileName) {
        String stem = fileName == null ? "" : new File(fileName).getName().replaceFirst("(?i)\\.txt$", "");
        if (stem.matches("[0-9A-Fa-f]{16}([0-9A-Fa-f]{16})?")) return stem.substring(0, 16).toUpperCase(Locale.ROOT);
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^build\\s+([0-9a-f]{16})[0-9a-f]*$").matcher(l);
            return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) : null;
        }
        return null;
    }

    /** Why a file cannot be imported for a game whose main builds are these (null: it can): too large, not text, no build named, another build, no cheat in it */
    static String checkImport(byte[] data, String fileName, Set<String> builds) {
        if (data.length > MAX_FILE) return "It is larger than " + (MAX_FILE / 1024) + " KB: not a cheat file.";
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.indexOf('\0') >= 0) return "It is not a text file.";
        String b = buildOf(text, fileName);
        if (b == null) return "It does not say which build of the game it is for: name it <build ID>.txt (as Atmosphère cheat files are named) or start it with a line \"build <build ID>\".";
        if (builds.isEmpty()) return "This game's build ID is not known yet: Prepare the game first.";
        if (!builds.contains(b)) return "It is for build " + b + "; this game is build " + String.join(", ", builds) + ". Cheats of another build write the wrong places, so it was not imported.";
        int n = 0;
        for (Entry e : parse(text)) if (e.toggles()) n++;
        if (n == 0) return "It holds no cheat ([Name] followed by code lines).";
        return null;
    }

    /** An imported file as it is kept: "build &lt;ID&gt;" as its first line (so a kept file can always be checked again: export, import), then its text without an earlier build line */
    static String normalize(String text, String build) {
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

    /** A file name for a picked file kept in the imported folder: letters, digits, space, '.', '-', '_' only, .txt, not one of taken (case-insensitive): "-2", "-3" … */
    static String keptName(String picked, Set<String> taken) {
        String stem = picked == null ? "" : new File(picked).getName().replaceFirst("(?i)\\.txt$", "").replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        if (stem.isEmpty() || stem.startsWith(".")) stem = "cheats" + stem;
        if (stem.length() > 60) stem = stem.substring(0, 60);
        Set<String> low = new HashSet<>();
        for (String t : taken) low.add(t.toLowerCase(Locale.ROOT));
        String n = stem + ".txt";
        for (int k = 2; low.contains(n.toLowerCase(Locale.ROOT)); k++) n = stem + "-" + k + ".txt";
        return n;
    }

    // ---- The merged file ----

    /** An imported file: its label (the kept file's name without .txt) and text */
    static final class Named {
        final String label, text;
        Named(String label, String text) { this.label = label; this.text = text; }
    }

    /** The merged file the host reads: the profile's text as it is, then each imported file under [--SectionStart:Imported: &lt;label&gt;--] … [--SectionEnd:…--]. In an
     *  imported file its build line, its prose lines and its own section markers are left out; a name that an earlier entry has gets " (imported)", then
     *  " (imported 2)" …, so the profile's toggles keep their keys and no two files share one */
    static String merge(String shipped, List<Named> imported) {
        StringBuilder sb = new StringBuilder();
        Set<String> names = new HashSet<>();
        if (shipped != null && !shipped.isEmpty()) {
            sb.append(shipped.replace("\r\n", "\n"));
            if (sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            for (Entry e : parse(shipped)) names.add(e.name);
        }
        for (Named f : imported) {
            String label = f.label.replaceAll("[\\[\\]{}\n\r]", "_");
            sb.append("[--SectionStart:").append(SECTION_IMPORTED).append(label).append("--]\n");
            boolean in = false;
            for (String line : f.text.replace("\r\n", "\n").split("\n")) {
                String[] h = header(line);
                if (h == null) {
                    if (in && codeLine(line)) sb.append(line.trim()).append('\n');   // code of the entry; prose and the build line are left out
                    continue;
                }
                if (sectionMarker(h[1], new String[1]) != 0) { in = false; continue; }
                String name = h[1];
                if (names.contains(name)) {
                    String n = name + SUFFIX;
                    for (int k = 2; names.contains(n); k++) n = name + " (imported " + k + ")";
                    name = n;
                }
                names.add(name);
                in = true;
                sb.append(h[0]).append(name).append(h[0].equals("[") ? "]" : "}");
                if (codeLine(h[2])) sb.append(' ').append(h[2].trim());
                sb.append('\n');
            }
            sb.append("[--SectionEnd:").append(SECTION_IMPORTED).append(label).append("--]\n");
        }
        return sb.toString();
    }

    /** &lt;build&gt;.enabled's text: the key of each toggled entry that is in on, in file order */
    static String enabledFile(List<Entry> list, Set<String> on) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) if (e.toggles() && on.contains(e.key)) sb.append(e.key).append('\n');
        return sb.toString();
    }

    /** The player's toggles as one preference string (keys, one a line) and back */
    static String encodeOn(Set<String> on) { return String.join("\n", on); }
    static Set<String> decodeOn(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s != null) for (String k : s.split("\n")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    /** The host's line for a start: DM_CHEATS=&lt;folder&gt; only when the game's profile has cheats, its Cheats switch is on and a merged file was made */
    static List<String> env(boolean master, boolean profileHas, File folder) {
        return master && profileHas && folder != null ? List.of("DM_CHEATS=" + folder.getPath()) : List.of();
    }

    /** The in-game panel's rows: the entries the player toggles, none while the switch is off or the game has no cheats */
    static List<Entry> panel(boolean master, boolean profileHas, List<Entry> all) {
        if (!master || !profileHas || all == null) return List.of();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (e.toggles()) out.add(e);
        return out;
    }

    /** The profile's file in a pack for this build (&lt;pack&gt;/cheats/&lt;build&gt;.txt), null when the pack has none (made before the game had cheats: Prepare again) */
    static File packFile(File packDir, String build) {
        if (packDir == null || build == null) return null;
        File f = new File(new File(packDir, "cheats"), build + ".txt");
        return f.isFile() ? f : null;
    }

    /** The imported files kept for the game, sorted by name (the merge order) */
    static List<File> importedFiles(File folder) {
        File[] l = new File(folder, "imported").listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (l == null) return List.of();
        List<File> out = new ArrayList<>(Arrays.asList(l));
        out.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        return out;
    }

    /** The imported files of this build, read (a kept file of another build, from an older game version, is left out of the merge) */
    static List<Named> importedOf(File folder, String build) throws IOException {
        List<Named> out = new ArrayList<>();
        for (File f : importedFiles(folder)) {
            String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (build.equals(buildOf(t, null))) out.add(new Named(f.getName().replaceFirst("(?i)\\.txt$", ""), t));
        }
        return out;
    }

    /** Before a start: the merged file of the profile's file (shipped, may be null) and the imported ones as &lt;folder&gt;/&lt;build&gt;.txt, with &lt;build&gt;.enabled from on;
     *  any other .txt or .enabled in the folder goes (another build's, from before an update). Returns the merged file's entries, null when there is no cheat at all */
    static List<Entry> install(File shipped, File folder, String build, Set<String> on) throws IOException {
        String s = shipped != null ? new String(Files.readAllBytes(shipped.toPath()), StandardCharsets.UTF_8) : "";
        String merged = merge(s, importedOf(folder, build));
        List<Entry> list = parse(merged);
        boolean any = false;
        for (Entry e : list) if (e.toggles()) any = true;
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        File[] old = folder.listFiles((d, n) -> n.endsWith(".txt") || n.endsWith(".enabled"));
        if (old != null) for (File f : old) if (!f.delete()) throw new IOException("cannot remove " + f);
        if (!any) return null;
        write(new File(folder, build + ".txt"), merged);
        write(new File(folder, build + ".enabled"), enabledFile(list, on));
        return list;
    }

    /** Import one picked file (its bytes and its name): kept in &lt;folder&gt;/imported/ as normalize makes it. Returns the kept file; IOException with the sentence of checkImport */
    static File importFile(File folder, byte[] data, String fileName, Set<String> builds) throws IOException { return importFile(folder, data, fileName, fileName, builds); }

    /** checkName: the name the build gate reads (null: only the file's build line, as for a kept file in an export); keepName: the name it is kept under */
    private static File importFile(File folder, byte[] data, String checkName, String keepName, Set<String> builds) throws IOException {
        String why = checkImport(data, checkName, builds);
        if (why != null) throw new IOException(why);
        String text = new String(data, StandardCharsets.UTF_8);
        File dir = new File(folder, "imported");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        Set<String> taken = new HashSet<>();
        for (File f : importedFiles(folder)) taken.add(f.getName());
        File out = new File(dir, keptName(keepName, taken));
        write(out, normalize(text, buildOf(text, checkName)));
        return out;
    }

    /** Remove an imported file (only one of the imported folder: the profile's file is not the player's to remove). False when it is no such file */
    static boolean remove(File folder, String name) {
        File f = new File(new File(folder, "imported"), name);
        return f.getParentFile().equals(new File(folder, "imported")) && !name.contains("/") && f.isFile() && f.delete();
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) { tmp.delete(); throw new IOException("cannot write " + f); }
    }

    // ---- Export / import as one .zip ----

    /** The export's name: &lt;title ID&gt;-cheats-&lt;stamp&gt;.zip */
    static String zipName(String titleId, String stamp) { return titleId + "-cheats-" + stamp + ".zip"; }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** The zip: cheats/&lt;name&gt;.txt for each imported file, and toggles.json {"title_id", "build", "cheats" (the switch), "on" (the keys)} */
    static void exportZip(OutputStream os, String titleId, String build, File folder, boolean master, Set<String> on) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            for (File f : importedFiles(folder)) {
                z.putNextEntry(new ZipEntry("cheats/" + f.getName()));
                z.write(Files.readAllBytes(f.toPath()));
                z.closeEntry();
            }
            StringBuilder j = new StringBuilder("{\"title_id\": ").append(jsonStr(titleId)).append(", \"build\": ").append(jsonStr(build == null ? "" : build))
                    .append(", \"cheats\": ").append(master).append(", \"on\": [");
            int k = 0;
            for (String key : on) j.append(k++ == 0 ? "" : ", ").append(jsonStr(key));
            j.append("]}\n");
            z.putNextEntry(new ZipEntry("toggles.json"));
            z.write(j.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
    }

    /** What an imported zip held: its files (kept name → bytes, all checked), the switch and the toggles (null without toggles.json) */
    static final class ZipContent {
        final Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        Boolean master;
        Set<String> on;
    }

    /** Read and check a zip made by exportZip: every cheats/*.txt must pass checkImport for these builds and toggles.json must be this game's; else IOException with
     *  the reason and nothing is taken. Entries outside cheats/ and toggles.json are refused (a save or anything else has no place in it) */
    static ZipContent readZip(InputStream in, String titleId, Set<String> builds) throws IOException {
        ZipContent c = new ZipContent();
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (e.isDirectory()) { if (n.equals("cheats/")) continue; throw new IOException("Not a cheats export: it holds " + n); }
                byte[] b = readMax(z, MAX_FILE);
                if (n.equals("toggles.json")) {
                    Object o;
                    try { o = Json.parse(new String(b, StandardCharsets.UTF_8)); } catch (IllegalArgumentException x) { throw new IOException("toggles.json cannot be read: " + x.getMessage()); }
                    if (!(o instanceof Map)) throw new IOException("toggles.json cannot be read");
                    Map<?, ?> m = (Map<?, ?>) o;
                    if (!titleId.equals(Json.str(m, "title_id"))) throw new IOException("It is an export of another game (" + Json.str(m, "title_id") + "), not " + titleId + ".");
                    c.master = Json.bool(m, "cheats");
                    c.on = new LinkedHashSet<>();
                    Object l = m.get("on");
                    if (l instanceof List) for (Object k : (List<?>) l) if (k instanceof String && !((String) k).isEmpty()) c.on.add((String) k);
                } else if (n.startsWith("cheats/") && n.indexOf('/', 7) < 0 && n.length() > 7) {
                    if (c.files.size() >= MAX_ZIP_FILES) throw new IOException("It holds more than " + MAX_ZIP_FILES + " cheat files.");
                    String why = checkImport(b, null, builds);
                    if (why != null) throw new IOException(n.substring(7) + ": " + why);
                    c.files.put(n.substring(7), b);
                } else throw new IOException("Not a cheats export: it holds " + n);
            }
        }
        if (c.files.isEmpty() && c.on == null) throw new IOException("Not a cheats export: no cheat file and no toggles.json.");
        return c;
    }

    private static byte[] readMax(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            o.write(buf, 0, n);
            if (o.size() > max) throw new IOException("A file in it is larger than " + (max / 1024) + " KB.");
        }
        return o.toByteArray();
    }

    /** Take a checked zip: its files join the imported folder (a file with the same text already there is not added twice). Returns how many files were added */
    static int takeZip(File folder, ZipContent c, Set<String> builds) throws IOException {
        Set<String> have = new HashSet<>();
        for (File f : importedFiles(folder)) have.add(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        int n = 0;
        for (Map.Entry<String, byte[]> e : c.files.entrySet()) {
            String t = new String(e.getValue(), StandardCharsets.UTF_8);
            if (have.contains(normalize(t, buildOf(t, null)))) continue;
            importFile(folder, e.getValue(), null, e.getKey(), builds);
            n++;
        }
        return n;
    }
}
