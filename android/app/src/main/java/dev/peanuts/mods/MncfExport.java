package dev.peanuts.mods;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.davdxpx.mistel.mncf.CodeModule;
import io.github.davdxpx.mistel.mncf.Mncf;
import io.github.davdxpx.mistel.mncf.MncfException;
import io.github.davdxpx.mistel.mncf.MncfFile;
import io.github.davdxpx.mistel.mncf.SignatureResult;
import io.github.davdxpx.mistel.mncf.Writer;

/**
 * Export: the player's cheat sources of a game as Mistel Native Cheat Format files, so another device (or another app that reads .mncf) has the same cheats,
 * switched as here. One source becomes one .mncf, several an .mncp pack with one .mncf a source (Mistel's spec, section 13; its manifest names each file's
 * SHA-256). In each file the cheats switched on here are default = true and the others false, and a number set in the in-game tab is its value's default, so the
 * import of the export on another device starts as this one is. A file nothing of that changes is written byte for byte as it was added (a signature stays
 * good); a changed one is the library's writing of it (Writer: without signature lines). For other emulators, {@link #zip} writes the same sources as
 * Atmosphère cheat files in Atmosphère's folders, one a build, and names what Atmosphère code cannot hold. No Android classes: CheatLoaderJvmTest runs it.
 */
public final class MncfExport {
    private MncfExport() {}

    /** What an export is: its file name, the bytes, and how many sources and cheats went in */
    public static final class Out {
        public final String fileName;
        public final byte[] bytes;
        public final int sources, cheats;
        public final boolean pack;
        Out(String fileName, byte[] bytes, int sources, int cheats, boolean pack) { this.fileName = fileName; this.bytes = bytes; this.sources = sources; this.cheats = cheats; this.pack = pack; }
    }

    /** The name an export is offered under: "&lt;game&gt; cheats.mncf" or ".mncp", without characters a file name cannot have */
    public static String fileName(String gameName, boolean pack) {
        String n = (gameName == null || gameName.trim().isEmpty() ? "Cheats" : gameName.trim() + " cheats").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return n + (pack ? ".mncp" : ".mncf");
    }

    /** Whether the game has sources to export (an .mncf each) */
    public static boolean any(File folder) {
        for (CheatSources.Source s : CheatSources.list(folder)) if (s.file().isFile()) return true;
        return false;
    }

    /**
     * The export of the game's sources: merged gives each cheat's key (the toggles' and the kept numbers' key), on the keys switched on, kept the numbers set
     * (CheatValues.read). Null when the game has no source. IOException when a source's file cannot be read
     */
    public static Out make(File folder, String gameName, CheatSources.Merged merged, Set<String> on, Map<String, CheatValues.Kept> kept) throws IOException {
        List<CheatSources.Source> sources = new ArrayList<>();
        for (CheatSources.Source s : CheatSources.list(folder)) if (s.file().isFile()) sources.add(s);
        if (sources.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        List<byte[]> files = new ArrayList<>();
        int cheats = 0;
        for (CheatSources.Source s : sources) {
            byte[] b = Files.readAllBytes(s.file().toPath());
            MncfFile f;
            try {
                f = Mncf.load(b);
            } catch (MncfException e) {
                files.add(b);   // a file this app cannot use goes as it is: the other side may read it
                names.add(s.id + ".mncf");
                continue;
            }
            for (io.github.davdxpx.mistel.mncf.Cheat c : f.cheats()) if (!c.isMaster()) cheats++;
            files.add(withState(b, f, s.id, merged, on, kept));
            names.add(s.id + ".mncf");
        }
        if (sources.size() == 1) return new Out(fileName(gameName, false), files.get(0), 1, cheats, false);
        return new Out(fileName(gameName, true), pack(gameName, names, files), sources.size(), cheats, true);
    }

    /** A source's file with the player's switches as defaults and the set numbers as its values' defaults; its own bytes when that changes nothing */
    @SuppressWarnings("unchecked")
    static byte[] withState(byte[] own, MncfFile f, String sourceId, CheatSources.Merged merged, Set<String> on, Map<String, CheatValues.Kept> kept) {
        Map<String, CheatSources.Row> byId = new HashMap<>();
        if (merged != null) for (CheatSources.Row r : merged.rows) if (r.block.id.equals(sourceId) && r.cheat.mncf != null) byId.put(r.cheat.mncf.id, r);
        Map<String, Long> numbers = numbers(sourceId, merged, kept);
        Map<String, Object> doc = MncfBuild.copy(f.doc());
        boolean changed = false;
        for (Object o : (List<Object>) doc.get("cheat")) {
            Map<String, Object> c = (Map<String, Object>) o;
            CheatSources.Row r = byId.get(String.valueOf(c.get("id")));
            if (r == null || r.cheat.master) continue;   // refused here, a master, or not in this build's merge: as the file has it
            boolean want = on.contains(r.key), had = Boolean.TRUE.equals(c.get("default"));
            if (want != had) {
                if (want) c.put("default", true); else c.remove("default");
                changed = true;
            }
            Long n = r.cheat.mncf.valueKey == null ? null : numbers.get(r.cheat.mncf.valueKey);
            if (n != null && c.get("values") instanceof Map) {
                Object v = ((Map<String, Object>) c.get("values")).get(r.cheat.mncf.value.id());
                if (v instanceof Map) { ((Map<String, Object>) v).put("default", n); changed = true; }
            }
        }
        return changed ? Writer.dumps(doc).getBytes(StandardCharsets.UTF_8) : own;
    }

    /** The numbers set in the in-game tab of a source's cheats (CheatValues.read), inside their ranges: "&lt;cheat id&gt;.&lt;value id&gt;" → the number */
    static Map<String, Long> numbers(String sourceId, CheatSources.Merged merged, Map<String, CheatValues.Kept> kept) {
        Map<String, Long> out = new HashMap<>();
        if (merged == null || kept == null) return out;
        for (CheatSources.Row r : merged.rows) {
            if (!r.block.id.equals(sourceId) || r.cheat.mncf == null || r.cheat.number == null || r.cheat.mncf.valueKey == null) continue;
            CheatValues.Kept k = kept.get(r.key.toLowerCase(Locale.ROOT));
            if (k != null && CheatValues.apply(r.cheat, k) != null) out.put(r.cheat.mncf.valueKey, Math.max(r.cheat.number.min(), Math.min(k.number, r.cheat.number.max())));
        }
        return out;
    }

    // ---- A ZIP for other emulators ----

    /** At most this many entries beside the master in one Atmosphère file, group starts and ends counted (spec section 15; Atmosphère refuses more) */
    static final int ATMOSPHERE_MAX = 127;

    /** At most this many words of code in one entry, the master's too (spec section 15; Atmosphère refuses more) */
    static final int ATMOSPHERE_WORDS = 256;

    /** A file of this many bytes or more Atmosphère refuses (dmnt's text buffer, docs/reference/cheats.md) */
    static final int ATMOSPHERE_BYTES = 0x10000;

    /** The bytes of a name Atmosphère keeps (readable_name[0x40] with its terminator, docs/reference/cheats.md): a longer one is cut */
    static final int ATMOSPHERE_NAME = 63;

    /** A ZIP in Atmosphère's layout, and what went in and what could not */
    public static final class Zip {
        public final String fileName;
        public final byte[] bytes;
        public final Map<String, Integer> builds;      // build ID (16 hex, upper case) → the cheats its file has; the running build first
        public final Map<String, String> versions;     // build ID → its version name, where a file names one
        public final int cheats;                       // the cheats in the ZIP, in one build's file at least
        public final List<String[]> left;              // the cheats in no file: {name, why in the player's words}, in the sources' order
        Zip(String fileName, byte[] bytes, Map<String, Integer> builds, Map<String, String> versions, int cheats, List<String[]> left) {
            this.fileName = fileName; this.bytes = bytes; this.builds = builds; this.versions = versions; this.cheats = cheats; this.left = left;
        }
    }

    /** The name a ZIP export is offered under: "&lt;game&gt; cheats.zip" */
    public static String zipName(String gameName) { return fileName(gameName, false).replaceFirst("\\.mncf$", ".zip"); }

    /** Where Atmosphère reads a build's cheats in the ZIP (Eden, Yuzu and Ryujinx read the same files) */
    public static String zipPath(String titleId, String build16) {
        return "atmosphere/contents/" + titleId.toUpperCase(Locale.ROOT) + "/cheats/" + build16.toUpperCase(Locale.ROOT) + ".txt";
    }

    /** One cheat lowered for one build; key: "&lt;source's place in the list&gt;/&lt;cheat id&gt;" */
    private static final class Lowered {
        final int source;
        final String key, id, name, group;
        final boolean master;
        final List<String> requires, lines;
        Lowered(int source, MncfBuild.Cheat c, List<String> lines) {
            this.source = source; key = source + "/" + c.id; id = c.id; name = c.name; group = c.group; master = c.master; requires = c.requires; this.lines = lines;
        }
    }

    /**
     * The game's sources as a ZIP for other emulators: one Atmosphère cheat file a build, at atmosphere/contents/&lt;title ID&gt;/cheats/&lt;build ID&gt;.txt, each
     * source's cheats lowered by Mistel's converter for every build its file names (Converter.toAtmosphereAll). For the build that runs it gets the game's own
     * main (MncfMain: the pack's main with its original words), so the patterns are found and every patch and override stub is checked against expect before
     * it is written: a cheat whose place holds other code stays out, and the ZIP never writes over the wrong bytes. A build the game runs that the file does
     * not name is written from the patterns with that main. Each cheat is its own code for the build where the file has it, else code made from its
     * operations; one with a number set in the in-game tab is made from its operations with that number where it can be. Left out and named, with the
     * converter's stable reason in the player's words: what Atmosphère code cannot say (a monitor, toggle buttons, a run once or for a time, several
     * conditions), a pattern in a build whose main Peanuts does not have, what does not fit the game, and what goes past Atmosphère's limits. Masters become
     * each file's one master code; groups are written as the spec writes them (section 15). Null when the game has no source
     */
    public static Zip zip(File folder, String gameName, MncfBuild.Game g, CheatSources.Merged merged, Map<String, CheatValues.Kept> kept) throws IOException {
        List<CheatSources.Source> sources = new ArrayList<>();
        for (CheatSources.Source s : CheatSources.list(folder)) if (s.file().isFile()) sources.add(s);
        if (sources.isEmpty()) return null;
        CodeModule main = null;   // the game's own main of the build that runs, read once for every source
        if (g.pack != null && g.build != null) {
            try { main = MncfMain.of(g.pack); } catch (IOException e) { main = null; }   // a pack whose main does not fit together: its patterns stay out
            if (main != null && !MncfConvert.sameBuild(main, g.build)) main = null;
        }
        Map<String, List<Lowered>> byBuild = new LinkedHashMap<>();
        if (g.build != null) byBuild.put(g.build, new ArrayList<>());
        Map<String, String> versions = new LinkedHashMap<>();
        List<String[]> left = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();   // every cheat (not a master) by its key, in the sources' order
        Map<String, String> why = new HashMap<>();           // a cheat's key → why it is in no build
        for (int si = 0; si < sources.size(); si++) {
            CheatSources.Source s = sources.get(si);
            byte[] bytes = Files.readAllBytes(s.file().toPath());
            MncfFile f;
            try {
                f = Mncf.load(bytes, MncfBuild.TRUST);
            } catch (MncfException e) {
                left.add(new String[] {s.name, "Its file cannot be read."});
                continue;
            }
            String sig = f.signature().result();
            boolean bad = SignatureResult.INVALID.equals(sig) || SignatureResult.REVOKED.equals(sig);
            Map<String, Object> values = new HashMap<>(numbers(s.id, merged, kept));
            List<MncfBuild.Cheat> all = new ArrayList<>();
            Map<String, Boolean> want = new LinkedHashMap<>();   // its own code first, or code made from its operations first where a number is set
            for (io.github.davdxpx.mistel.mncf.Cheat c : f.cheats()) {
                MncfBuild.Cheat x = new MncfBuild.Cheat(c.index(), c, null, Collections.<String>emptyList(), MncfBuild.number(c));
                all.add(x);
                want.put(c.id(), !(x.valueKey != null && values.containsKey(x.valueKey)));
                if (!c.isMaster()) names.put(si + "/" + c.id(), c.name());
                if (bad) why.put(si + "/" + c.id(), MncfWords.reason("signature", null));
            }
            if (bad) continue;
            String own = new String(bytes, StandardCharsets.UTF_8), runKey = MncfBuild.buildKey(f, g.build);
            Map<String, CodeModule> mains = new HashMap<>();
            if (runKey != null && main != null) mains.put(runKey, main);
            Map<String, Map<String, String>> reasons = new LinkedHashMap<>();   // build key → cheat id → the converter's reason
            try {
                for (Map.Entry<String, Map<Integer, List<String>>> e : MncfBuild.lowerAll(own, mains, values, want, reasons).entrySet()) {
                    String id = f.builds().get(e.getKey()).modules().get("main"), bid = id.substring(0, Math.min(16, id.length())).toUpperCase(Locale.ROOT);
                    add(byBuild.computeIfAbsent(bid, k -> new ArrayList<>()), si, all, e.getValue());
                    String v = MncfBuild.version(f, bid);
                    if (v != null) versions.putIfAbsent(bid, v);
                }
                if (runKey == null && main != null) {   // the build that runs, which the file does not name: from its patterns, with the game's main
                    Map<String, String> sk = new LinkedHashMap<>();
                    add(byBuild.get(g.build), si, all, MncfBuild.lower(own, g.build, main, values, want, sk));
                    reasons.put(g.build, sk);
                    if (g.version != null) versions.putIfAbsent(g.build, g.version);
                }
            } catch (MncfException | RuntimeException e) {   // the library's own writing of a file it read: not expected
                for (MncfBuild.Cheat c : all) why.putIfAbsent(si + "/" + c.id, "An Atmosphère file cannot say what it does.");
                continue;
            }
            // a cheat in no build: the reason of the build that runs, else of the file's first build
            List<Map<String, String>> order = new ArrayList<>();
            Map<String, String> run = reasons.get(runKey != null ? runKey : g.build);
            if (run != null) order.add(run);
            order.addAll(reasons.values());
            for (MncfBuild.Cheat c : all) {
                for (Map<String, String> r : order) if (r.containsKey(c.id)) { why.putIfAbsent(si + "/" + c.id, zipWords(r.get(c.id), c)); break; }
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> in = new HashSet<>();
        Map<String, String> past = new HashMap<>();   // a cheat's key → why a file it was lowered for could not hold it, the running build's first
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            for (Map.Entry<String, List<Lowered>> e : byBuild.entrySet()) {
                Set<String> here = new HashSet<>();
                String text = atmosphere(e.getValue(), sources, here, past);
                if (here.isEmpty()) continue;
                counts.put(e.getKey(), here.size());
                in.addAll(here);
                z.putNextEntry(new ZipEntry(zipPath(g.titleId, e.getKey())));
                z.write(text.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (in.contains(e.getKey())) continue;
            left.add(new String[] {e.getValue(), past.containsKey(e.getKey()) ? past.get(e.getKey()) : why.getOrDefault(e.getKey(), "An Atmosphère file cannot say what it does.")});
        }
        versions.keySet().retainAll(counts.keySet());
        return new Zip(zipName(gameName), o.toByteArray(), counts, versions, in.size(), left);
    }

    /** Why the converter left a cheat out of an Atmosphère file, from its stable reason (Converter.Export.reasons(), spec section 15), in the player's words */
    static String zipWords(String code, MncfBuild.Cheat c) {
        String k = code == null ? "" : code;
        switch (k) {
            case "atmosphere:no-module": return "Its place is found by the code around it, and Peanuts can look it up only in the version it has built.";
            case "atmosphere:run": return "timed".equals(c == null ? null : c.run) ? "It runs for a set time, which an Atmosphère file cannot say."
                    : "It runs once when you turn it on, which an Atmosphère file cannot say.";
            case "atmosphere:monitor": return "It shows values from the game, which an Atmosphère file cannot do.";
            case "atmosphere:toggle": return "It is switched with a button combination, which an Atmosphère file cannot say.";
            case "atmosphere:condition": return "It waits for the game in a way an Atmosphère file cannot say.";
            case "atmosphere:action": return "It runs when you press its buttons in a way an Atmosphère file cannot say.";
            case "atmosphere:override": return "It changes a function in a way an Atmosphère file cannot say.";
            case "atmosphere:module": return "It changes a part of the game other than its main code, which an Atmosphère file cannot name.";
            case "atmosphere:region": return "It writes a part of the game's memory an Atmosphère file cannot name.";
            case "atmosphere:address": return "It writes a fixed address, which an Atmosphère file cannot name.";
            case "atmosphere:empty": return "It has nothing to write.";
            case "atmosphere:too-long": return "Its code is longer than one cheat in an Atmosphère file can be.";
            case "atmosphere:too-many": return TOO_MANY;
            case "atmosphere:too-big": return TOO_BIG;   // Mistel's converter keeps dmnt's 64 KB itself since davdxpx/mistel#17
            default: return k.isEmpty() || k.startsWith("atmosphere:") ? "An Atmosphère file cannot say what it does." : MncfWords.reason(k, c);   // the reader's reasons (Appendix B)
        }
    }

    private static void add(List<Lowered> to, int source, List<MncfBuild.Cheat> cheats, Map<Integer, List<String>> lines) {
        for (MncfBuild.Cheat c : cheats) {
            List<String> code = lines.get(c.index);
            if (code != null && !code.isEmpty()) to.add(new Lowered(source, c, code));
        }
    }

    static final String TOO_MANY = "An Atmosphère file holds at most " + ATMOSPHERE_MAX + " cheats.";
    static final String TOO_BIG = "An Atmosphère file can be at most 64 KB, and the cheats before this one fill it.";
    static final String MASTER_TOO_LONG = "It needs a code that is always on, and that code does not fit in one Atmosphère file beside the others.";

    /**
     * One build's Atmosphère file, within Atmosphère's limits (spec section 15), as Mistel's converter writes one source's file, for every source of the
     * game together (the converter takes one file): the masters as its one master code, named by the first, of at most ATMOSPHERE_WORDS words (a
     * master past that stays out, and with it the cheats of its source that require it), then each source's cheats in its order, at most ATMOSPHERE_MAX
     * entries, the file under ATMOSPHERE_BYTES (past either, the rest stays out). A group starts with its name as an empty entry and ends with an entry of
     * only 20000001 where a cheat in no group follows it, as Mistel's converter writes and reads them; where the group entries would leave cheats out, the
     * file has no groups. A name an earlier source has already gets the source's name after it, as in the merged file; within one source the names stay as
     * its file has them; every name is cut to the ATMOSPHERE_NAME bytes Atmosphère keeps. in gets the key of each cheat it has, past the key of each it could
     * not hold with why (an earlier file's why is kept)
     */
    static String atmosphere(List<Lowered> l, List<CheatSources.Source> sources, Set<String> in, Map<String, String> past) {
        List<Lowered> masters = new ArrayList<>(), cheats = new ArrayList<>();
        Set<String> outMasters = new HashSet<>();   // "<source>/<id>" of a master that does not fit
        int words = 0;
        for (Lowered x : l) {
            if (!x.master) continue;
            int w = words(x.lines);
            if (words + w > ATMOSPHERE_WORDS) { outMasters.add(x.key); continue; }
            words += w;
            masters.add(x);
        }
        for (Lowered x : l) {
            if (x.master) continue;
            boolean needs = false;
            for (String r : x.requires) needs |= outMasters.contains(x.source + "/" + r);
            if (needs) past.putIfAbsent(x.key, MASTER_TOO_LONG); else cheats.add(x);
        }
        boolean groups = cheats.size() + count(marks(cheats, true)) <= ATMOSPHERE_MAX;
        List<String> mark = marks(cheats, groups);
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> taken = new HashMap<>();   // a name in lower case → the source that has it
        if (!masters.isEmpty()) {
            String name = atm(masters.get(0).name);   // the first master's name, as the converter names its one master entry (spec section 15)
            if (name.isEmpty()) name = "Master code";
            sb.append('{').append(name).append("}\n");
            taken.put(name.toLowerCase(Locale.ROOT), -1);
            for (Lowered x : masters) for (String line : x.lines) sb.append(line).append('\n');
        }
        int entries = 0, bytes = utf8(sb);
        String full = null;   // why the file is full: then the rest stays out too
        for (int i = 0; i < cheats.size(); i++) {
            Lowered x = cheats.get(i);
            String m = mark.get(i);
            StringBuilder e = new StringBuilder();
            if (m != null) e.append(sb.length() > 0 ? "\n" : "").append(m);
            String safe = atm(x.name);
            e.append(sb.length() + e.length() > 0 ? "\n" : "").append('[').append(name(safe.isEmpty() ? "Cheat" : safe, x.source, sources.get(x.source).name, taken)).append("]\n");
            for (String line : x.lines) e.append(line).append('\n');
            int n = entries + (m == null ? 0 : 1) + 1, b = utf8(e);
            if (full == null && n > ATMOSPHERE_MAX) full = TOO_MANY;
            if (full == null && bytes + b >= ATMOSPHERE_BYTES) full = TOO_BIG;
            if (full != null) { past.putIfAbsent(x.key, full); continue; }
            sb.append(e);
            entries = n;
            bytes += b;
            in.add(x.key);
        }
        return sb.toString();
    }

    /**
     * The group entry before each cheat, null where it needs none: its group's name as an empty entry where a group starts (a group is its source's, so
     * the next source starts its own), an entry of only 20000001 under the group's name where a cheat in no group follows one. All null without groups
     */
    private static List<String> marks(List<Lowered> cheats, boolean groups) {
        List<String> out = new ArrayList<>();
        String open = null;
        int source = -1;
        for (Lowered x : cheats) {
            String g = groups && !x.group.isEmpty() ? atm(x.group) : null;
            String m = null;
            if (g != null && (x.source != source || !g.equals(open))) m = "[" + g + "]\n";
            else if (g == null && open != null) m = "[" + open + "]\n20000001\n";
            out.add(m);
            open = g;
            source = x.source;
        }
        return out;
    }

    private static int count(List<String> marks) {
        int n = 0;
        for (String m : marks) if (m != null) n++;
        return n;
    }

    /** The words of code in an entry's lines */
    private static int words(List<String> lines) {
        int n = 0;
        for (String line : lines) for (String w : line.trim().split("\\s+")) if (!w.isEmpty()) n++;
        return n;
    }

    /** A cheat's name in the file: its own, or with its source's name when another source has it (taken: lower case → source); at most ATMOSPHERE_NAME bytes */
    private static String name(String base, int source, String sourceName, Map<String, Integer> taken) {
        Integer had = taken.get(base.toLowerCase(Locale.ROOT));
        if (had == null || had == source) { taken.put(base.toLowerCase(Locale.ROOT), source); return base; }
        String n = fit(base, " (" + CheatSources.safe(sourceName) + ")");
        for (int k = 2; taken.containsKey(n.toLowerCase(Locale.ROOT)) && taken.get(n.toLowerCase(Locale.ROOT)) != source; k++) n = fit(base, " (" + CheatSources.safe(sourceName) + " " + k + ")");
        taken.put(n.toLowerCase(Locale.ROOT), source);
        return n;
    }

    /** A name as Atmosphère keeps it: without the characters that end a header, cut to ATMOSPHERE_NAME bytes */
    static String atm(String name) { return cut(CheatSources.safe(name), ATMOSPHERE_NAME); }

    /** name with suffix, the name cut so the whole has at most ATMOSPHERE_NAME bytes (the suffix cut too where it is longer alone) */
    private static String fit(String name, String suffix) {
        String sfx = cut(suffix, ATMOSPHERE_NAME);
        return cut(name, ATMOSPHERE_NAME - utf8(sfx)) + sfx;
    }

    /** s cut to at most max UTF-8 bytes, between characters, trimmed */
    static String cut(String s, int max) {
        if (utf8(s) <= max) return s;
        int i = 0, b = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i), n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (b + n > max) break;
            b += n;
            i += Character.charCount(cp);
        }
        return s.substring(0, i).trim();
    }

    private static int utf8(CharSequence s) { return s.toString().getBytes(StandardCharsets.UTF_8).length; }

    /** An .mncp: pack.toml (format, name, each file with its SHA-256) and the files, stored in a ZIP */
    static byte[] pack(String gameName, List<String> names, List<byte[]> files) throws IOException {
        StringBuilder m = new StringBuilder("format = \"mncf-pack/1\"\nname = ").append(toml(gameName == null || gameName.trim().isEmpty() ? "Cheats" : gameName.trim() + " cheats")).append('\n');
        for (int i = 0; i < names.size(); i++) m.append("\n[[file]]\npath = ").append(toml(names.get(i))).append("\nsha256 = \"").append(MncfBuild.sha256(files.get(i))).append("\"\n");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("pack.toml"));
            z.write(m.toString().getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            for (int i = 0; i < names.size(); i++) {
                z.putNextEntry(new ZipEntry(names.get(i)));
                z.write(files.get(i));
                z.closeEntry();
            }
        }
        return o.toByteArray();
    }

    /** A TOML basic string */
    static String toml(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20 || c == 0x7F) sb.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
