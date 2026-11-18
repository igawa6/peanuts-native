package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PEANUTS: the cheat files of the enabled mods (dev.peanuts.mods, a mod's cheats/ folder) in upstream's cheat list (Cheats.java, Game settings › Cheats). A Peanuts
 * Native file in upstream's launcher package, as ScreenMode.java: Cheats' merge and entries are package-private. No Android classes.
 * <p>
 * The host reads one cheats folder (DM_CHEATS). Upstream's start writes the profile's file and the player's imported files there as one merged file with the
 * player's toggles; this fork's start staged the mods' files into a folder of its own and passed that. With both, the later line won: the host ran the mods'
 * cheats while the in-game panel listed upstream's. {@link #install} is Cheats.install with the mods' files of the game's build added after the imported ones,
 * each under its own section ("Imported: mod &lt;mod&gt;: &lt;file&gt;"), so every cheat is in the one list the panel shows and toggles, and one folder is passed.
 * A game whose Cheats switch is off, or whose profile has no cheats, takes the mods' folder as before (GameActivity.start).
 * <ul>
 * <li>A mod's cheat is named "&lt;its name&gt; (&lt;mod&gt;)" in the list. A toggle is kept by the entry's key, which is its name (Cheats.parse; a name that is
 *     there twice gets " (imported)" from Cheats.merge, by its place in the file): without the mod's name in it, two mods with a cheat of one name had keys that
 *     depended on which of them was enabled, and turning the first mod off handed its toggle to the second mod's cheat. With it the key is the mod's and the
 *     cheat's alone.</li>
 * <li>A mod may say which of its cheats are on: &lt;build&gt;.enabled beside its file, the host's own format (one key a line, as the mod's file names them). Those
 *     are turned on the first time the mod's cheats enter the list, and never again: after that the player's toggles decide. What was offered is kept in
 *     &lt;folder&gt;/mods.seen.</li>
 * <li>A mod's file that cannot be read is left out with a note; the other cheats are installed.</li>
 * </ul>
 */
final class ModCheats {
    private ModCheats() {}

    static final String SEEN = "mods.seen";

    /** One mod file of this build: its label and text for the list (the mod's name in each entry's name), and the names its own .enabled turns on, as the list has them */
    static final class ModFile {
        final Cheats.Named named; final List<String> on;
        ModFile(Cheats.Named named, List<String> on) { this.named = named; this.on = on; }
    }

    private static String clean(String s) { return s.replaceAll("[\\[\\]{}\n\r\t]", "_").trim(); }

    /** An entry's name in the list: "name (mod)", the name cut so that the whole stays within the 127 characters a header's name has (Cheats.header) */
    static String named(String name, String mod) {
        String suffix = " (" + mod + ")";
        if (suffix.length() > 100) suffix = suffix.substring(0, 99) + ")";
        return (name.length() + suffix.length() > 127 ? name.substring(0, 127 - suffix.length()) : name) + suffix;
    }

    /** A mod's cheat text with every entry renamed (named); section markers, prose and code lines stay as they are */
    static String renamed(String text, String mod) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String[] h = Cheats.header(line);
            if (h == null || Cheats.sectionMarker(h[1], new String[1]) != 0) { sb.append(line).append('\n'); continue; }
            sb.append(h[0]).append(named(h[1], mod)).append(h[0].equals("[") ? "]" : "}").append(h[2]).append('\n');
        }
        return sb.toString();
    }

    /** The mods' cheat files of this build, in the mods' order (the first enabled mod first), each folder's by name. notes gets a line for a file left out */
    static List<ModFile> of(List<File> modCheatDirs, String build, List<String> notes) {
        List<ModFile> out = new ArrayList<>();
        if (modCheatDirs == null) return out;
        for (File dir : modCheatDirs) {
            File[] l = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".txt"));
            if (l == null) continue;
            java.util.Arrays.sort(l, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            String mod = clean(dir.getParentFile() == null ? dir.getName() : dir.getParentFile().getName());
            for (File f : l) {
                try {
                    if (f.length() > Cheats.MAX_FILE) throw new IOException("larger than " + Cheats.MAX_FILE / 1024 + " KiB");
                    String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    if (!build.equals(Cheats.buildOf(t, f.getName()))) continue;
                    String stem = f.getName().replaceFirst("(?i)\\.txt$", ""), text = renamed(t, mod);
                    // The mod's own toggles: its file's keys (Cheats.parse), told as the names the same entries have after the renaming
                    List<String> on = new ArrayList<>();
                    File en = new File(dir, stem + ".enabled");
                    if (en.isFile()) {
                        Set<String> want = Cheats.decodeOn(new String(Files.readAllBytes(en.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n"));
                        List<Cheats.Entry> own = Cheats.parse(t.replace("\r\n", "\n")), now = Cheats.parse(text);
                        for (int i = 0; i < own.size() && i < now.size(); i++) if (own.get(i).toggles() && want.contains(own.get(i).key)) on.add(now.get(i).name);
                    }
                    out.add(new ModFile(new Cheats.Named("mod " + mod + ": " + stem, text), on));
                } catch (IOException | RuntimeException e) {
                    if (notes != null) notes.add("the cheat file " + f.getName() + " of the mod " + mod + " is left out (" + e + ")");
                }
            }
        }
        return out;
    }

    /**
     * Cheats.install with the mods' files: the merged file and its toggles in folder; the merged file's entries, null when there is no cheat at all. on is the
     * player's toggles and is added to: the cheats a mod's own .enabled names, the first time they enter the list (the caller keeps on when it grew)
     */
    static List<Cheats.Entry> install(File shipped, File folder, String build, Set<String> on, List<File> modCheatDirs, List<String> notes) throws IOException {
        List<ModFile> mods = of(modCheatDirs, build, notes);
        if (mods.isEmpty()) return Cheats.install(shipped, folder, build, on);
        String s = shipped != null ? new String(Files.readAllBytes(shipped.toPath()), StandardCharsets.UTF_8) : "";
        List<Cheats.Named> all = new ArrayList<>(Cheats.importedOf(folder, build));
        for (ModFile m : mods) all.add(m.named);
        String merged = Cheats.merge(s, all);
        List<Cheats.Entry> list = Cheats.parse(merged);
        boolean any = false;
        for (Cheats.Entry e : list) if (e.toggles()) any = true;
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        File[] old = folder.listFiles((d, n) -> n.endsWith(".txt") || n.endsWith(".enabled"));
        if (old != null) for (File f : old) if (!f.delete()) throw new IOException("cannot remove " + f);
        if (!any) return null;
        // The mods' own toggles, once: a key the list has that was never offered before and that its mod turns on
        File seenFile = new File(folder, SEEN);
        Set<String> seen = seenFile.isFile() ? Cheats.decodeOn(new String(Files.readAllBytes(seenFile.toPath()), StandardCharsets.UTF_8)) : new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        for (Cheats.Entry e : list) if (e.toggles()) keys.add(e.key);
        boolean grew = false;
        for (ModFile m : mods) for (String k : m.on) if (keys.contains(k) && seen.add(k)) { on.add(k); grew = true; }
        if (grew) write(seenFile, Cheats.encodeOn(seen) + "\n");
        write(new File(folder, build + ".txt"), merged);
        write(new File(folder, build + ".enabled"), Cheats.enabledFile(list, on));
        return list;
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) { tmp.delete(); throw new IOException("cannot write " + f); }
    }
}
