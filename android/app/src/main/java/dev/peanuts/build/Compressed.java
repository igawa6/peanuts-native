package dev.peanuts.build;

import java.io.File;
import java.util.Locale;

/**
 * Compressed game files, .nsz and .xcz (issue #149): what the build says about them. The importer (dm/peanuts/import, nx_ncz.c) does the work: it unpacks a
 * solid file into a temporary file beside the .nup and checks every compressed NCA against its SHA-256 before it writes, reporting those as the steps
 * "unpack/&lt;file&gt;" and "check/&lt;file&gt;" ahead of the writer's own. Plain Java (JVM-tested: peanuts/tests/test_compressed.py).
 */
public final class Compressed {
    private Compressed() {}

    /** An .nsz or .xcz file: imported like an .nsp or .xci, rebuilt and checked on the way */
    public static boolean is(File f) {
        if (f == null) return false;
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".nsz") || n.endsWith(".xcz");
    }

    /** The type in the game sheet's words: "NSZ", "XCZ", "XCI", or "" for an NSP */
    public static String kind(File f) {
        String n = f == null ? "" : f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".nsz") ? "NSZ" : n.endsWith(".xcz") ? "XCZ" : n.endsWith(".xci") ? "XCI" : "";
    }

    static boolean prep(String step) { return step != null && (step.startsWith("unpack/") || step.startsWith("check/")); }

    /**
     * The card's detail for an importer step: "Unpacking Game.nsz: 37 %", "Checking Game.xcz: 12 %", "Reading the game files" (probe), else "Importing the game
     * files: 25 %". The writer's own steps (base/romfs, update/&lt;v&gt;/exefs, addon/&lt;id&gt;/romfs, toc) take turns as it writes, so they share one sentence
     */
    public static String stepText(String step, long done, long total) {
        String pct = total > 0 ? String.format(Locale.ROOT, ": %d %%", Math.min(100, done * 100 / total)) : "";
        if (step != null && step.startsWith("unpack/")) return "Unpacking " + step.substring(7) + pct;
        if (step != null && step.startsWith("check/")) return "Checking " + step.substring(6) + pct;
        if ("probe".equals(step)) return "Reading the game files" + pct;
        return "Importing the game files" + pct;
    }

    /**
     * Where the import is on the bar (0 to end - 1): unpacking and checking first, in proportion to their bytes against the .nup's (nupBytes, the probe's
     * size; 0 unknown: they then take a third), then the writer. The writer's own done/total count only its bytes, so the two parts never meet in the middle.
     */
    public static int percent(String step, long done, long total, long prepBytes, long nupBytes, int end) {
        int last = end - 1;
        int share = prepBytes <= 0 ? 0 : nupBytes <= 0 ? last / 3 : (int) Math.min(last - 1, Math.max(1, last * prepBytes / (prepBytes + nupBytes)));
        long frac = total > 0 ? Math.max(0, Math.min(total, done)) : 0, of = Math.max(1, total);
        if (prep(step)) return (int) (share * frac / of);
        if ("probe".equals(step)) return 0;   // before anything: the bar starts at its start
        if (total <= 0) return share;
        return share + (int) ((last - share) * frac / of);
    }

    /**
     * An importer error about a compressed file in a player's words (what did not finish, that nothing changed when unchanged, what to do), or null when it
     * is not one. The importer's own message goes to the log as it is.
     */
    public static String failure(String message, String name, boolean unchanged) {
        if (message == null) return null;
        String m = message.toLowerCase(Locale.ROOT), file = fileIn(message);
        String game = name == null || name.isEmpty() ? "the game" : name, same = unchanged ? " Nothing was changed." : "";
        if (!m.contains(".ncz") && !m.contains(".nsz") && !m.contains(".xcz") && !m.contains("compressed")) return null;
        if (m.contains("keys inside"))
            return file + " does not match your keys, so " + game + " was not imported." + same + " Compress the game again from your own dump, or build from the uncompressed file.";
        if (m.contains("zstd window") || m.contains("ncz layout") || m.contains("block layout") || m.contains("blocks, more than"))
            return file + " was compressed with settings the app cannot read, so " + game + " was not imported." + same + " Compress it again with nsz's default settings, or build from the uncompressed file.";
        if (m.contains("checksum") || m.contains("cut off") || m.contains("damaged") || m.contains("does not decompress") || m.contains("not a compressed nca"))
            return file + " is damaged or incomplete, so " + game + " was not imported." + same + " Copy or compress the file again, or build from the uncompressed file.";
        return null;
    }

    /** The file an importer message names first (its messages start "<file name>: " or "<file name> is"), else "The compressed game file" */
    static String fileIn(String message) {
        int c = message.indexOf(": "), s = message.indexOf(" is ");
        int end = c < 0 ? s : s < 0 ? c : Math.min(c, s);
        String f = end > 0 ? message.substring(0, end).trim() : "";
        String l = f.toLowerCase(Locale.ROOT);
        return l.endsWith(".nsz") || l.endsWith(".xcz") || l.endsWith(".nsp") || l.endsWith(".xci") ? f : "The compressed game file";
    }
}
