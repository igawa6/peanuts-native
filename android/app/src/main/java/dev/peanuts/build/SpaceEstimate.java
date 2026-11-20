package dev.peanuts.build;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * A rough free-space check before a build writes anything big (warn-only,
 * two volumes compared each against only what it needs, no new game.tsv parser).
 * <p>
 * Two volumes matter, and they are compared separately, never against the combined total (found in review):
 * <ul>
 * <li>the game folder (maybe an SD card), which only needs room for the merged .nup when a base/update/DLC needs importing ({@link #freeBytes} against
 *     {@code importBytes} alone, not import+pack);</li>
 * <li>the app's own external storage, {@code getExternalFilesDir(null)} (AppStorage.java, where {@code AppStorage}/the pack actually lands) — NOT
 *     {@code getFilesDir()}, which is internal storage the pack is never written to — which needs room for the pack, estimated from {@code packBasisBytes}:
 *     the merged .nup's size when importing, else the existing dump-folder/.nup base's own size on disk (found in review: a dump-folder base used to
 *     estimate 0, i.e. "0.5 GB", which is wrong for a multi-GB game; callers must measure the base's real size when it needs no import).</li>
 * </ul>
 * The multiplier below is a flat, uncalibrated guess (no per-game pack_size_hint exists yet): this is why {@link #warning} only ever warns, and
 * {@link BuildService} never fails a build on it. A real estimate needs measured pack sizes per game (APK-SIZE.md); until then a wrong guess must never
 * block Build.
 */
public final class SpaceEstimate {
    private SpaceEstimate() {}

    /** The total size of a list of files (base/update/DLC that need import); 0 for any that are not plain files (a directory, or missing) */
    public static long inputBytes(List<File> files) {
        long total = 0;
        if (files != null) for (File f : files) if (f != null && f.isFile()) total += f.length();
        return total;
    }

    /** A dump-folder or .nup base's real size on disk, recursive for a folder (found in review): the basis for the pack estimate when nothing needs
     *  importing (the base already holds everything, updates and DLC included, the way LibraryScanner groups a dump folder's Source) */
    public static long onDiskBytes(File f) {
        if (f == null || !f.exists()) return 0;
        if (!f.isDirectory()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += onDiskBytes(k);
        return total;
    }

    /**
     * What Prepare's pack step needs on the app's external storage volume: a flat, uncalibrated 1.3x of basisBytes (the merged .nup's size when importing,
     * else the existing base's own size, {@link #onDiskBytes}) plus 500 MB headroom for shader/runtime-shader intermediates. Deliberately conservative:
     * never used to disable Build, only to warn
     */
    public static long packBytes(long basisBytes) { return (long) (basisBytes * 1.3) + 500L * 1024 * 1024; }

    /** A folder's free space (its own volume: an SD card's game folder and the app's external storage are usually different volumes, AUDIT F10) */
    public static long freeBytes(File dir) {
        if (dir == null) return Long.MAX_VALUE;   // unknown: never the reason to warn
        File d = dir;   // the nearest folder that exists: a build folder's <title ID>/pack is made by the build itself, and a missing path reads as 0 bytes free
        while (d != null && !d.isDirectory()) d = d.getParentFile();
        return d == null ? Long.MAX_VALUE : d.getUsableSpace();
    }

    /**
     * A short, one- or two-sentence warning when either volume looks short, each checked against only what it needs, else null (found in review).
     * importBytes: the merged .nup's size, relevant only when needsImport (0 otherwise — nothing new is written to the game folder for an existing
     * dump/.nup base); packBasisBytes: {@link #onDiskBytes} of the base when it needs no import, else importBytes again (the .nup about to be written is
     * what the pack step will actually read from); gameDir: where the .nup lands (null: unknown, never warns); appExternal: {@code getExternalFilesDir(null)},
     * never {@code getFilesDir()} (found in review)
     */
    public static String warning(long importBytes, long packBasisBytes, boolean needsImport, File gameDir, File appExternal) {
        StringBuilder s = new StringBuilder();
        if (needsImport) {
            long free = freeBytes(gameDir);
            if (free < importBytes) s.append("Needs about ").append(words(importBytes)).append(" free in the game folder (").append(words(free)).append(" there now).");
        }
        long packNeed = packBytes(packBasisBytes), appFree = freeBytes(appExternal);
        if (appFree < packNeed) {
            if (s.length() > 0) s.append(' ');
            s.append("Needs about ").append(words(packNeed)).append(" free on this device for the pack (").append(words(appFree)).append(" free now).");
        }
        return s.length() == 0 ? null : s.toString();
    }

    /** The combined "needs about X GB" line when neither volume looks short: the import volume's need (0 without one) plus the pack volume's need */
    public static long totalNeeded(long importBytes, long packBasisBytes, boolean needsImport) {
        return (needsImport ? importBytes : 0) + packBytes(packBasisBytes);
    }

    /** The words for the part of the need a solid .nsz takes only while it imports (issue #149: unpacked into a temporary file beside the .nup, deleted
     *  at the end), "" without one: " About 6.2 GB of it only while the compressed game is unpacked." */
    public static String unpackSentence(long unpackBytes) {
        return unpackBytes <= 0 ? "" : " About " + words(unpackBytes) + " of it only while the compressed game is unpacked.";
    }

    /** A byte count in words, close enough for a warning: "6.2 GB" */
    public static String words(long bytes) {
        if (bytes == Long.MAX_VALUE) return "an unknown amount";
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
