package dev.nxnative.android;
// PEANUTS: new file. The saves of a game's users as folders (issue #422), without Android classes, so the JVM test (UserSavesJvmTest) runs it with SaveStore.
// Each user has a save of their own (Users.saveDir, hle_account.c dm_account_save_dir): the first user's is the game's save folder, another user's is
// <save>_user_<uid> beside it, their account save alone (the device save is the game's: SaveStore.userOf). A user removed in Settings › Users leaves their
// folders; they are listed here so they can still be backed up, given to a user or deleted. It lives in this package because SaveStore is package-private;
// UserSaves is its Android side, and GameActivity finishes a stopped restore into any user's save at a game's start (recoverUsers).

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class UserSaveDirs {
    private UserSaveDirs() {}

    private static final Pattern UID = Pattern.compile("[0-9a-f]{32}");

    /** The other users' save folders beside base (&lt;base&gt;_user_&lt;uid&gt;, folders only), by uid in name order: today's users and removed ones */
    static Map<String, File> others(File base) {
        Map<String, File> out = new LinkedHashMap<>();
        File p = base.getAbsoluteFile().getParentFile();
        String[] l = p == null ? null : p.list();
        if (l == null) return out;
        Arrays.sort(l);
        String pre = base.getName() + SaveStore.USER_SUFFIX;
        for (String n : l) {
            if (!n.startsWith(pre) || n.length() != pre.length() + 32 || !UID.matcher(n.substring(pre.length())).matches()) continue;
            File d = new File(p, n);
            if (d.isDirectory()) out.put(n.substring(pre.length()), d);
        }
        return out;
    }

    /** The uids of folders beside base that hold a save (a file somewhere in them) but are none of the users': the saves of removed users */
    static List<String> removed(File base, List<String> users) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, File> e : others(base).entrySet()) if (!users.contains(e.getKey()) && SaveStore.hasFiles(e.getValue())) out.add(e.getKey());
        return out;
    }

    /**
     * Finishes what a stop left of a restore into another user's save (SaveStore's journal: its set file or a marker), as GameActivity does for the game's own save
     * at every start: a game may switch to any of its users (the user selector), so each is whole before it runs. The caller holds base's lock (the game's start),
     * which every backup or restore of another user's save holds as well (UserSaves.locked). A folder whose journal SaveStore refuses to finish is left as it is,
     * as SaveStore leaves it, and named in the note: it stops no start, as the game may never open that user's save (a removed user's never). The note for the
     * game's log, null when there was nothing
     */
    static String recoverUsers(File base) {
        List<String> done = new ArrayList<>(), left = new ArrayList<>();
        for (File d : others(base).values()) {
            if (!SaveStore.pending(d)) continue;
            try {
                if (SaveStore.recoverLocked(d)) done.add(d.getName());
            } catch (IOException | RuntimeException e) {
                left.add(d.getName() + " (" + e.getMessage() + ")");
            }
        }
        if (done.isEmpty() && left.isEmpty()) return null;
        return (done.isEmpty() ? "" : "a stopped restore into another user's save was finished first: " + String.join(", ", done))
                + (done.isEmpty() || left.isEmpty() ? "" : "; ") + (left.isEmpty() ? "" : "left as it is, its restore cannot be finished: " + String.join(", ", left));
    }

    /** A user's name in a zip's file name: letters, digits, '.', '_' and '-' kept, every other character '_', at most 32; "" for none */
    static String fileName(String name) {
        String s = name.trim().replaceAll("[^0-9A-Za-z._-]", "_");
        return s.length() > 32 ? s.substring(0, 32) : s;
    }
}
