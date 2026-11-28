package dev.peanuts.launch;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One game's entry in the Frontend folder (FrontendFolder, LAUNCHERS.md "Frontend folder"): a small text file that a frontend such as Cocoon lists as a game and
 * hands back to the app's launch alias. Its name is {@code <game name> [<title ID>].nutshell}: the frontend's scraper reads the game's name from it, and
 * FrontendLaunch reads the title ID from the bracket. Its text is two lines, the marker of the app that wrote it and the title ID, so a sync only ever changes
 * the files of this app, and a renamed file still starts its game.
 * <p>
 * Plain Java, no Android: FrontendEntryJvmTest checks it on the JVM (peanuts/tests/test_frontend_folder.py).
 */
public final class FrontendEntry {
    private FrontendEntry() {}

    /** The entry's extension: no real file format uses it */
    public static final String EXT = ".nutshell";
    /** The first line's start: then a space and the application ID of the app that wrote the file */
    static final String MARK = "Peanuts Native";
    /** The most an entry's text can be: anything larger is not one */
    static final int MAX_BYTES = 512;

    private static final Pattern BRACKET = Pattern.compile("\\[([0-9A-Fa-f]{16})\\]");
    private static final Pattern TITLE_ID = Pattern.compile("[0-9A-Fa-f]{16}");
    /** What a FAT or exFAT SD card and Android's shared storage refuse in a file name, and control characters */
    private static final Pattern REFUSED = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f\\x7f]");
    /** Room for a name: a file name is at most 255 bytes, and " [" + 16 + "]" + EXT takes 28 of them */
    static final int NAME_BYTES = 200;

    /** Whether a file name is an entry's (by its extension, in any case) */
    public static boolean isEntry(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(EXT);
    }

    /**
     * The entry's file name for a game: its name without the characters a file name cannot hold (Pokémon Legends: Z-A becomes Pokémon Legends Z-A, as the
     * common dump names have it), then its title ID in brackets. A name with nothing left is the title ID's
     */
    public static String fileName(String name, String titleId) {
        String id = titleId.toUpperCase(Locale.ROOT);
        String n = name == null ? "" : REFUSED.matcher(name).replaceAll(" ");
        n = n.replaceAll("\\s+", " ").trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1).trim();   // a name ending in a dot reads as a different extension
        while (n.startsWith(".")) n = n.substring(1).trim();   // a name starting with one is a hidden file, which frontends do not list
        n = cut(n, NAME_BYTES).trim();
        if (n.isEmpty()) n = id;
        return n + " [" + id + "]" + EXT;
    }

    /** s cut to at most max bytes of UTF-8, never inside a character */
    private static String cut(String s, int max) {
        int bytes = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > max) return s.substring(0, i);
            bytes += len;
            i += Character.charCount(cp);
        }
        return s;
    }

    /** The title ID in an entry's file name (the last bracket of 16 hex digits), upper case; null when the name is not an entry's or has none */
    public static String titleIdOfName(String fileName) {
        if (!isEntry(fileName)) return null;
        Matcher m = BRACKET.matcher(fileName.substring(0, fileName.length() - EXT.length()));
        String id = null;
        while (m.find()) id = m.group(1);
        return id == null ? null : id.toUpperCase(Locale.ROOT);
    }

    /** The entry's text: the marker with the application ID of the app writing it, and the title ID */
    public static String text(String appId, String titleId) {
        return MARK + " " + appId + "\n" + titleId.toUpperCase(Locale.ROOT) + "\n";
    }

    /**
     * The title ID an entry's text names, upper case. appId: only the text of the app with this ID counts (a sync changes only its own files), null: any
     * Peanuts Native app's (a launch: the app checks the title against its own list anyway). null when the text is not an entry's
     */
    public static String titleIdOfText(String text, String appId) {
        if (text == null || text.length() > MAX_BYTES) return null;
        String[] lines = text.replace("\r", "").split("\n");
        if (lines.length < 2) return null;
        String first = lines[0].trim();
        if (!first.startsWith(MARK + " ")) return null;
        if (appId != null && !first.equals(MARK + " " + appId)) return null;
        String id = lines[1].trim();
        return TITLE_ID.matcher(id).matches() ? id.toUpperCase(Locale.ROOT) : null;
    }
}
