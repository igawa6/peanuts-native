// PEANUTS: Install from file (Settings › Library › Dual-screen companions, a game's Companion section) and the companion command's path: a .dsmod.zip on the device, no web
package dev.peanuts;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import dev.peanuts.ui.CtlPort;

/**
 * A companion from a file: where it goes for its game and what to tell the player. The checks and the install are the catalog install's
 * (Installer.installFile: the same lock, extraction, title and runtime checks and swap), after CompanionFile.read checked the whole file. A game the
 * library has goes beside its pack, as the catalog's install does; a game that is not in the library yet goes where its build will look, the app's
 * packs/peanuts (dev.nxnative.android.AppStorage.pack's folder, BuildService.companionRoot). Off the main thread, all of it.
 */
public final class CompanionImport {
    /** Larger than any companion today (the biggest is a few MB) by far */
    static final long MAX_BYTES = 512L << 20;

    /** Where a game's companion goes: root, the game's name (its title ID when the library has no name), whether the library has it and has built it */
    public static final class Target {
        public final File root;
        public final String name;
        public final boolean inLibrary, built;
        Target(File root, String name, boolean inLibrary, boolean built) { this.root = root; this.name = name; this.inLibrary = inLibrary; this.built = built; }
    }

    /** One file's install: what the file is, where it went, what changed */
    public static final class Done {
        public final CompanionFile.Info info;
        public final Target target;
        public final Installer.FileInstall result;
        Done(CompanionFile.Info info, Target target, Installer.FileInstall result) { this.info = info; this.target = target; this.result = result; }

        /** The sentence the page, the toast and the companion command give */
        public String message() {
            String game = target.inLibrary ? target.name : "the game with title ID " + info.titleId;
            String what;
            switch (result.kind) {
                case SAME: what = cap(info.label()) + " version " + result.version + " is installed already for " + game + ". Nothing changed."; break;
                case UPDATE: what = result.was == null || result.was.equals("?") ? "Updated " + info.label() + " to version " + result.version + ", for " + game + "."
                        : "Updated " + info.label() + " from version " + result.was + " to " + result.version + ", for " + game + "."; break;
                case OLDER: what = "Installed the older version " + result.version + " of " + info.label() + " for " + game + ". Version " + result.was + " stays on this device under Versions."; break;
                default: what = "Installed " + info.label() + ", version " + result.version + ", for " + game + "."; break;
            }
            if (!result.changed()) return what;
            if (!target.inLibrary) return what + " That game is not in your library yet. The companion is used once you add and build it.";
            if (!target.built) return what + " It is used once the game is built.";
            return what + " It shows on the second screen the next time the game starts.";
        }
    }

    private CompanionImport() {}

    /** The picked document's file name (OpenableColumns.DISPLAY_NAME), null when the provider gives none */
    public static String displayName(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) return q.getString(0);
        } catch (RuntimeException e) { /* a provider without the column: the file is read by its contents alone */ }
        return null;
    }

    /** Copies a picked document into the app's cache (a provider's stream reads once; the file is read twice, checked and then extracted). The caller deletes it */
    public static File copy(Context c, Uri uri) throws IOException {
        File dir = new File(c.getCacheDir(), "companion-files");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File to = new File(dir, "picked-" + System.nanoTime() + ".dsmod.zip");
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("the picker gave no file");
            CompanionFile.copy(in, to, MAX_BYTES);
        } catch (IOException | RuntimeException e) {
            to.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e.toString(), e);
        }
        return to;
    }

    /** Where titleId's companion goes (reads the library: off the main thread) */
    public static Target target(Context c, String titleId) {
        CtlPort.Row row = row(CtlPort.rows(c), titleId);
        File root = row == null ? null : row.companionRoot;
        if (root == null) {
            File pack = dev.nxnative.android.AppStorage.pack(c, titleId);   // where the game's build puts its pack, and its companions beside it
            root = new File(pack.getAbsoluteFile().getParentFile(), "peanuts");
        }
        return new Target(root, row == null || row.name == null || row.name.isEmpty() ? titleId : row.name, row != null, row != null && row.pack != null);
    }

    private static CtlPort.Row row(List<CtlPort.Row> rows, String titleId) {
        for (CtlPort.Row r : rows) if (r.titleId != null && r.titleId.equalsIgnoreCase(titleId)) return r;
        return null;
    }

    /**
     * Checks zip and installs it for its game (wantTitle: only for that game, null: any). fileName: the file's own name (its package name when it is named
     * as the catalog names them). Throws CompanionFile.Refused with a code (not_companion, damaged, wrong_title, needs_newer_app, older: ask first, then
     * again with allowOlder), or an IOException with the installer's reason (a game runs, no room).
     */
    public static Done install(Context c, File zip, String fileName, String wantTitle, boolean allowOlder, Installer.Progress progress) throws IOException {
        CompanionFile.Info info = CompanionFile.read(zip, fileName);
        Target t = target(c, info.titleId);
        String wantName = wantTitle == null ? null : wantTitle.equalsIgnoreCase(info.titleId) ? t.name : target(c, wantTitle).name;
        Installer.FileInstall r = Installer.installFile(zip, info, t.root, wantTitle, wantName, allowOlder, c, progress);
        return new Done(info, t, r);
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
