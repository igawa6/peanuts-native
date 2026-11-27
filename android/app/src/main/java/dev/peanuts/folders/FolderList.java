package dev.peanuts.folders;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The list of one kind of folder (FolderKind), as plain data: what a store reads back, what an added or a removed folder makes of it, and which folder of a list
 * holds a file. Paths are /storage paths as the folder picker gives them (Upstream.treeToPath); a trailing slash never makes a second entry. Plain Java (no
 * android.*): peanuts/tests/test_folder_list.py runs FolderListJvmTest on it.
 */
public final class FolderList {
    private FolderList() {}

    /** What {@link #add} did */
    public enum Added { ADDED, ALREADY, FULL, NOT_A_PATH }

    /** A path as it is kept: trimmed, no trailing slash (the root "/" stays) */
    public static String clean(String p) {
        if (p == null) return null;
        String s = p.trim();
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    /** Whether two paths name one folder: the same after {@link #clean}; a volume's letters in any case (an SD card's ID is upper case, a typed one may not be) */
    public static boolean same(String a, String b) {
        String x = clean(a), y = clean(b);
        return x != null && y != null && x.toLowerCase(Locale.ROOT).equals(y.toLowerCase(Locale.ROOT));
    }

    /**
     * The folders of a kind from what a store keeps: its first folder in the key an older version kept its one folder in, then the keys it read too (the DLC
     * folder, kept beside the update folder before both became one kind), then the ones added since. Empty and repeated entries are left out, so a folder kept
     * twice (an update and a DLC folder that were the same) is one entry
     */
    public static List<String> read(Collection<String> firsts, Collection<String> extra) {
        List<String> out = new ArrayList<>();
        if (firsts != null) for (String p : firsts) put(out, p);
        if (extra != null) for (String p : extra) put(out, p);
        return out;
    }

    private static void put(List<String> out, String p) {
        String c = clean(p);
        if (c == null) return;
        for (String o : out) if (same(o, c)) return;
        out.add(c);
    }

    /** The list with path added at its end; the list itself is not changed. max: how many the kind takes (0: any number) */
    public static Added add(List<String> list, String path, int max, List<String> out) {
        String c = clean(path);
        out.clear();
        out.addAll(list);
        if (c == null || !c.startsWith("/")) return Added.NOT_A_PATH;
        for (String o : list) if (same(o, c)) return Added.ALREADY;
        if (max > 0 && list.size() >= max) return Added.FULL;
        out.add(c);
        return Added.ADDED;
    }

    /** The list without path (a copy) */
    public static List<String> remove(List<String> list, String path) {
        List<String> out = new ArrayList<>();
        for (String o : list) if (!same(o, path)) out.add(o);
        return out;
    }

    /** The list with path first (a copy; the kinds whose first folder is their default: builds) */
    public static List<String> first(List<String> list, String path) {
        List<String> out = new ArrayList<>();
        for (String o : list) if (same(o, path)) out.add(o);
        for (String o : list) if (!same(o, path)) out.add(o);
        return out;
    }

    /**
     * The folder of the list that holds file, the deepest when one holds another (a game folder inside a game folder); null when none does. A file is inside a
     * folder when its path starts with the folder's and a slash
     */
    public static String holding(List<String> list, String file) {
        String f = clean(file);
        if (f == null) return null;
        String fl = f.toLowerCase(Locale.ROOT), best = null;
        for (String o : list) {
            String c = clean(o);
            if (c == null) continue;
            String cl = c.toLowerCase(Locale.ROOT);
            boolean in = fl.equals(cl) || fl.startsWith(cl.equals("/") ? "/" : cl + "/");
            if (in && (best == null || c.length() > best.length())) best = c;
        }
        return best;
    }

    /** Where an import of a game file writes its .nup when no build folder is set: the game folder that holds the file, else the first game folder; null without one */
    public static File home(List<String> gameFolders, String file) {
        String h = holding(gameFolders, file);
        if (h == null && !gameFolders.isEmpty()) h = clean(gameFolders.get(0));
        return h == null ? null : new File(h);
    }
}
