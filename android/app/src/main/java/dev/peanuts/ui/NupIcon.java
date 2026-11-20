package dev.peanuts.ui;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A game's icon kept with its .nup, where it outlives the app's own copy (files/icons/&lt;title ID&gt;.png, which Clear data and an uninstall empty), so a game
 * whose NSP or XCI is gone still has its cover (issue #158). Two places, which {@link LibraryScanner#icon} reads in this order:
 * <ul>
 * <li>the PNG beside the .nup, "&lt;the .nup's name without .nup&gt;.png": the PC builder's (peanuts/scripts/peanuts_build.py), and the one {@link #keep} writes
 *     for a .nup that carries no icon of its own (one the importer made before #158, or another tool's without a meta block);</li>
 * <li>the icon inside the .nup, in its base package's meta block (SPEC §5, type 3): the importer writes it since #158 (dm/peanuts/import/nximport.c pkg_meta), the
 *     nup packer and scripts/nup_meta.py do too. Read by upstream's own reader, Nup.peek(f).gameMeta(), through reflection as {@link Upstream} reads Nup.</li>
 * </ul>
 * The app never writes into a .nup (android/NUP.md: scripts/nup_meta.py does, when the user runs it). No Android classes: NupIconJvmTest runs it.
 */
final class NupIcon {
    private NupIcon() {}

    /** A title's icon is a 256x256 picture, some hundred KiB as a PNG; anything larger is not one */
    static final int MAX = 4 << 20;
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
    /** The .nups {@link #keep} has looked at in this process (path, size, time): each is read for its meta block once */
    private static final Set<String> looked = new HashSet<>();

    /** The PNG beside a .nup: its name with .png in place of .nup; null for a name that does not end with .nup */
    static File beside(File nup) {
        String n = nup == null ? "" : nup.getName();
        File dir = nup == null ? null : nup.getParentFile();
        if (dir == null || n.length() <= 4 || !n.toLowerCase(Locale.ROOT).endsWith(".nup")) return null;
        return new File(dir, n.substring(0, n.length() - 4) + ".png");
    }

    /**
     * The image bytes (a JPEG or a PNG, by their first bytes: Nup.Meta takes no other) of the icon inside the .nup, from its base package, else its newest update
     * (Nup.gameMeta); null when it carries none or a larger one than {@link #MAX}. An IOException when the .nup cannot be read, or upstream's reader is not the
     * one this was written against (NupIconJvmTest calls the same methods)
     */
    static byte[] inside(File nup) throws IOException {
        try {
            Class<?> k = Class.forName("dev.nxnative.android.Nup");
            Method peek = k.getDeclaredMethod("peek", File.class), meta = k.getDeclaredMethod("gameMeta");
            peek.setAccessible(true); meta.setAccessible(true);
            Object m = meta.invoke(peek.invoke(null, nup));
            Field icon = m.getClass().getDeclaredField("icon");
            icon.setAccessible(true);
            byte[] b = (byte[]) icon.get(m);
            return b == null || b.length == 0 || b.length > MAX ? null : b;
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            throw c instanceof IOException ? (IOException) c : new IOException(String.valueOf(c), c);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new IOException("upstream's .nup reader: " + e, e);
        }
    }

    static boolean isPng(byte[] b) {
        if (b == null || b.length < PNG.length || b.length > MAX) return false;
        for (int i = 0; i < PNG.length; i++) if (b[i] != PNG[i]) return false;
        return true;
    }

    /**
     * from copied to to when from is a PNG (by its signature) of at most {@link #MAX} bytes; false when it is not one, or not there. The copy is written to a
     * temporary file beside to and renamed onto it, so no reader sees half a file: a file in files/icons is kept for good, and the PNG beside a .nup is read by
     * every app on the phone that lists the folder. The file comes from shared storage, so it is checked after it is read (it may have changed since length()).
     * An IOException when it cannot be written
     */
    static boolean copyPng(File from, File to) throws IOException {
        File dir = to == null ? null : to.getParentFile();
        long len = from != null && from.isFile() ? from.length() : 0;
        if (dir == null || len < PNG.length || len > MAX) return false;
        byte[] b = Files.readAllBytes(from.toPath());
        if (!isPng(b)) return false;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = File.createTempFile("." + to.getName() + ".", ".tmp", dir);
        try {
            Files.write(tmp.toPath(), b);
            if (!tmp.renameTo(to)) throw new IOException("cannot rename " + tmp.getName() + " to " + to.getName());
            tmp = null;
            return true;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /**
     * Puts png (the app's copy of the game's icon, files/icons/&lt;title ID&gt;.png) beside a .nup that carries no icon of its own, so the game keeps its cover
     * once the NSP or XCI it was made from is gone and the app's data is cleared: the .nups the importer made before #158 have none, and a game whose NSP was
     * deleted (Free up space, or by hand) keeps its icon only while files/icons has it. Nothing is written when a PNG is beside it already, when the .nup carries
     * an icon, or when it is not a file (a .nup picked with the system file picker has only a stand-in path in the app's storage). Each .nup is read for its meta
     * block once a process. True when the PNG was written; an IOException when the .nup cannot be read or the PNG cannot be written
     */
    static boolean keep(File nup, File png) throws IOException {
        if (nup == null || png == null || !nup.isFile() || !png.isFile()) return false;
        File to = beside(nup);
        if (to == null || to.exists()) return false;
        synchronized (looked) { if (!looked.add(nup.getPath() + "|" + nup.length() + "|" + nup.lastModified())) return false; }
        if (inside(nup) != null) return false;
        return copyPng(png, to);
    }
}
