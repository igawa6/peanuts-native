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

import dev.peanuts.mods.CheatFile;
import dev.peanuts.mods.CheatSources;

/**
 * PEANUTS: the one merged cheat file a start hands the host (DM_CHEATS), from the game's cheat sources (dev.peanuts.mods.CheatSources: the cheats that come
 * with the app, the player's imports) and the enabled mods' cheat files (dev.peanuts.mods, a mod's cheats/ folder). A Peanuts Native file in upstream's launcher
 * package, as ScreenMode.java: Cheats' entries and toggles are package-private. No Android classes.
 * <p>
 * The host reads one cheats folder. {@link #install} writes &lt;build&gt;.txt there with every cheat the game has (CheatSources.merge: one unique name each, the
 * host's key) and &lt;build&gt;.enabled with the player's toggles, so the in-game panel lists and toggles what the host runs. A game without any source switched
 * on and without mods' cheats takes the mods' folder as before (GameActivity.start).
 * <ul>
 * <li>A mod's cheat is named "&lt;its name&gt; (&lt;mod&gt;)" in the list, so its toggle's key does not depend on which other mods are enabled.</li>
 * <li>A mod may say which of its cheats are on: &lt;build&gt;.enabled beside its file, the host's own format (one key a line, as the mod's file names them). Those
 *     are turned on the first time the mod's cheats enter the list, and never again: after that the player's toggles decide. What was offered is kept in
 *     &lt;folder&gt;/mods.seen.</li>
 * <li>A mod's file that cannot be read is left out with a note; the other cheats are installed.</li>
 * <li>Upstream's earlier imports (&lt;folder&gt;/imported/) become sources first (CheatSources.migrate); a toggle kept as "Name (imported)" is given the cheat's new
 *     key as well.</li>
 * </ul>
 */
final class ModCheats {
    private ModCheats() {}

    static final String SEEN = "mods.seen";

    /** One mod file of this build: its label and text for the list (the mod's name in each entry's name), the mod's name, and the keys (in that text) its own .enabled turns on */
    static final class ModFile {
        final Cheats.Named named; final String mod; final List<String> on;
        ModFile(Cheats.Named named, String mod, List<String> on) { this.named = named; this.mod = mod; this.on = on; }
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
                    // The mod's own toggles: its file's keys (Cheats.parse), told as the keys the same entries have after the renaming
                    List<String> on = new ArrayList<>();
                    File en = new File(dir, stem + ".enabled");
                    if (en.isFile()) {
                        Set<String> want = Cheats.decodeOn(new String(Files.readAllBytes(en.toPath()), StandardCharsets.UTF_8).replace("\r", ""));   // any line ends (a file saved twice on Windows has \r\r\n)
                        List<Cheats.Entry> own = Cheats.parse(t.replace("\r\n", "\n")), now = Cheats.parse(text);
                        for (int i = 0; i < own.size() && i < now.size(); i++) if (own.get(i).toggles() && want.contains(own.get(i).key)) on.add(now.get(i).key);
                    }
                    out.add(new ModFile(new Cheats.Named("mod " + mod + ": " + stem, text), mod, on));
                } catch (IOException | RuntimeException e) {
                    if (notes != null) notes.add("the cheat file " + f.getName() + " of the mod " + mod + " is left out (" + e + ")");
                }
            }
        }
        return out;
    }

    static final String IMPORTED = "imported:";   // a block of upstream's imported/ folder: "imported:<file without .txt>"

    /**
     * The merge's blocks: the app's file (shipped) and the player's sources (CheatSources.blocks), upstream's imported files of this build that are still in
     * imported/ (each as a source of its own, by its file name), then the mods' files; textEnd as CheatFile.parse takes it
     */
    static List<CheatSources.Block> blocks(String shipped, File folder, String build, List<ModFile> mods, long textEnd) {
        Boolean appOn = CheatSources.appEnabled(folder);
        List<CheatSources.Block> blocks = CheatSources.blocks(folder, shipped, appOn == null || appOn, build, textEnd);
        try {
            for (Cheats.Named n : Cheats.importedOf(folder, build)) blocks.add(new CheatSources.Block(IMPORTED + n.label, n.label, true, CheatFile.parse(n.text, textEnd)));
        } catch (IOException e) { /* an imported file that cannot be read: left out, as a source's */ }
        for (ModFile m : mods) blocks.add(new CheatSources.Block("mod:" + m.named.label, m.mod, true, CheatFile.parse(m.named.text, textEnd)));
        return blocks;
    }

    /**
     * A toggle kept by upstream's earlier merge as "Name (imported)" / "Name (imported 2)": the cheat's key now in the blocks of those files (still in imported/,
     * or made sources: moved), added to on
     */
    static void legacyKeys(Set<String> on, List<CheatSources.Source> moved, CheatSources.Merged m) {
        Set<String> ids = new java.util.HashSet<>();
        for (CheatSources.Source s : moved) ids.add(s.id);
        for (CheatSources.Row r : m.rows) if (r.block.id.startsWith(IMPORTED)) ids.add(r.block.id);
        if (ids.isEmpty()) return;
        for (String k : new ArrayList<>(on)) {
            java.util.regex.Matcher x = java.util.regex.Pattern.compile("^(.*) \\(imported( \\d+)?\\)$").matcher(k);
            if (!x.matches()) continue;
            for (CheatSources.Row r : m.rows) if (ids.contains(r.block.id) && r.cheat.key.equals(x.group(1)) && !on.contains(r.key)) { on.add(r.key); break; }
        }
    }

    /**
     * Before a start: the merged file and its toggles in folder; the merged file's entries, null when there is no cheat at all. on is the player's toggles and is
     * added to: the cheats a mod's own .enabled names, the first time they enter the list, and an earlier import's renamed keys (the caller keeps on when it grew)
     */
    static List<Cheats.Entry> install(File shipped, File folder, String build, Set<String> on, List<File> modCheatDirs, List<String> notes) throws IOException {
        List<ModFile> mods = of(modCheatDirs, build, notes);
        List<CheatSources.Source> moved = CheatSources.migrate(folder, System.currentTimeMillis());
        String s = shipped != null ? new String(Files.readAllBytes(shipped.toPath()), StandardCharsets.UTF_8) : "";
        CheatSources.Merged m = CheatSources.merge(blocks(s, folder, build, mods, -1), dev.peanuts.mods.CheatValues.read(folder));   // with the numbers set in the game's Mods tab
        legacyKeys(on, moved, m);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create " + folder);
        File[] old = folder.listFiles((d, n) -> n.endsWith(".txt") || n.endsWith(".enabled"));
        if (old != null) for (File f : old) if (!f.delete()) throw new IOException("cannot remove " + f);
        if (m.cheats == 0) return null;
        List<Cheats.Entry> list = Cheats.parse(m.text);
        // The mods' own toggles, once: a key the list has that was never offered before and that its mod turns on
        File seenFile = new File(folder, SEEN);
        Set<String> seen = seenFile.isFile() ? Cheats.decodeOn(new String(Files.readAllBytes(seenFile.toPath()), StandardCharsets.UTF_8)) : new LinkedHashSet<>();
        boolean grew = false;
        for (ModFile mf : mods) for (String own : mf.on) {
            String k = m.key("mod:" + mf.named.label, own);
            if (k != null && seen.add(k)) { on.add(k); grew = true; }
        }
        if (grew) write(seenFile, Cheats.encodeOn(seen) + "\n");
        write(new File(folder, build + ".txt"), m.text);
        write(new File(folder, build + ".enabled"), Cheats.enabledFile(list, on));
        return list;
    }

    private static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) { tmp.delete(); throw new IOException("cannot write " + f); }
    }
}
