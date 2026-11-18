package dev.peanuts.mods;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Play-time wiring for {@link ModStore} (DESIGN-MODS.md "Launch hook"/"Cheats"): called from {@code GameActivity.start()}'s env assembly, once per
 * launch, to turn the enabled mods into the two env lines the host already understands — no new host mechanism, both already exist and are tested
 * (dm/host/tests/t_fs.c "base + overlay" for the overlay; dm/host/hle_cheats.c's own build-ID scan for cheats):
 * <ul>
 * <li>{@code DM_ROMFS_OVERLAY=<dir1>:<dir2>:...} — hle_fs.c:222-223 reads it once per {@code rom} mount; {@code rom_overlay_path} (hle_fs.c:375-388)
 *     checks the roots in that order and the <b>first one that has the file wins</b>; {@code dir_snapshot} (hle_fs.c:1289-1326) unions every root's
 *     directory listing so files the mods don't touch still show from the base RomFS. No copying: the directories are read live, so this is cheap
 *     even for a multi-GB romfs mod.</li>
 * <li>{@code DM_CHEATS=<folder>} — hle_cheats.c scans one flat folder (plus its {@code atmosphere/contents/<tid>/cheats} tree) for
 *     {@code <build id>.txt} by name; only one folder is ever read, so two enabled mods' cheats are staged (copied, not symlinked — shared storage
 *     refuses symlinks, the same FUSE limit {@code GameActivity}'s own DLC-link comment notes) into one folder under the app's own files, first
 *     enabled mod's file winning a same-named collision.</li>
 * </ul>
 * Only "files"-kind content (romfs, cheats) is ever wired here. A "code" mod's exefs/exefs_patches never reach the normal build's process at
 * all — {@link ModStore#needsBuild} is what the Mods section (dev.peanuts.settings.ModsSection) reads to offer "Build with mods" instead of pretending the code ran.
 */
public final class ModRuntime {
    private ModRuntime() {}

    /** A game's mods folder: <game home>/mods, beside its .nup (owner, 2026-11-13: mods next to the game's build): <build folder>/<title ID>/mods for a
     *  game in a build folder, <game folder>/<Name>/mods beside the .nup and <title ID>-work otherwise */
    public static File modsRoot(File gameHome) { return new File(gameHome, "mods"); }

    /** The game home its mods go in: the built game's folder (the .nup's, or a dump folder itself); before the first build, where the build will put
     *  it: the build folder's <title ID> when one is set, else the planned folder beside the dump (LibraryScanner.Title.home) */
    public static File home(Context c, String titleId, File builtGame, File plannedHome) {
        if (builtGame != null && builtGame.exists()) return builtGame.isDirectory() ? builtGame : builtGame.getParentFile();
        File b = dev.peanuts.PeanutsPrefs.buildGameDir(c, titleId);
        return b != null ? b : plannedHome;
    }

    /** The mods currently installed for this title, highest priority (wins first) first */
    public static List<ModStore.Mod> mods(File gameHome, String titleId) { return gameHome == null ? new java.util.ArrayList<>() : ModStore.list(modsRoot(gameHome), titleId); }

    /**
     * The {@code DM_ROMFS_OVERLAY} line to add to the launch env, or null when there is nothing for this method to add (no enabled mod ships a romfs
     * and there is no scene overlay either — the caller's existing scene-overlay line, if any, is then left exactly as it was).
     * <p>
     * Order: enabled mods first, in their list order (list order IS priority — {@link ModStore#list}'s {@code order} field), then the 3D-scene
     * overlay last (lowest priority) when one is active, since a mod intentionally replacing a file should win over a scene/resolution overlay that
     * only exists to serve a higher-resolution variant of the same file. This line is added to the env list after the scene code's own
     * {@code DM_ROMFS_OVERLAY} line, so it wins (the host's env is built with {@code setenv(..., overwrite=1)}; the last line for a given key wins —
     * see the scene code's own comment, "a DM_ROMFS_OVERLAY line of the user's comes later and wins").
     */
    public static String romfsOverlayEnv(Context c, File gameHome, String titleId, File sceneOverlayDirOrNull) { return romfsOverlayEnv(c, gameHome, titleId, sceneOverlayDirOrNull, null); }

    /**
     * The mods this start takes files and cheats from (ModStore.contributing): packDir is the pack being started, and a code mod counts only when that is
     * the build made for the code mods that are on (ModStore.playsVariant). With the game's own pack (their build missing, not ready, refused by the
     * launcher, still running), or without a pack to tell by (null), a code mod gives nothing: its files on the game's own code crashed the game
     */
    static List<ModStore.Mod> forStart(File gameHome, String titleId, File packDir) {
        List<ModStore.Mod> mods = mods(gameHome, titleId);
        return ModStore.contributing(mods, ModStore.playsVariant(titleId, ModStore.enabledCodeMods(mods), packDir));
    }

    /** As above for a start of packDir (GameActivity.start's pack folder): see {@link #forStart} */
    public static String romfsOverlayEnv(Context c, File gameHome, String titleId, File sceneOverlayDirOrNull, File packDir) {
        List<File> dirs = ModStore.enabledRomfsDirs(forStart(gameHome, titleId, packDir));
        if (dirs.isEmpty() && sceneOverlayDirOrNull == null) return null;
        StringBuilder b = new StringBuilder();
        for (File d : dirs) { if (b.length() > 0) b.append(':'); b.append(d.getPath()); }
        if (sceneOverlayDirOrNull != null) { if (b.length() > 0) b.append(':'); b.append(sceneOverlayDirOrNull.getPath()); }
        return "DM_ROMFS_OVERLAY=" + b;
    }

    /**
     * The {@code DM_CHEATS} line, staging the enabled mods' {@code cheats/*.txt} into {@code <files>/mods_stage/<titleId>/cheats} (first enabled
     * mod wins a same-named file); null when no enabled mod ships any cheats, leaving the host's own default ({@code <DM_ROOT>/cheats}) in force.
     * The staging folder is small (plain text cheat files), so this runs synchronously on the launch path, same as the DLC-link setup beside it.
     */
    public static String cheatsEnv(Context c, File gameHome, String titleId) { return cheatsEnv(c, gameHome, titleId, null); }

    /** The enabled mods' cheats folders for a start of packDir, first mod first: what the launcher merges into upstream's cheat list when the game has one
     *  (dev.nxnative.android.ModCheats), in place of the staged folder below */
    public static List<File> cheatsDirs(File gameHome, String titleId, File packDir) { return ModStore.enabledCheatsDirs(forStart(gameHome, titleId, packDir)); }

    /** As above for a start of packDir: a code mod's cheats only with its build ({@link #forStart}) */
    public static String cheatsEnv(Context c, File gameHome, String titleId, File packDir) {
        List<File> dirs = ModStore.enabledCheatsDirs(forStart(gameHome, titleId, packDir));
        if (dirs.isEmpty()) return null;
        File stage = new File(new File(c.getFilesDir(), "mods_stage"), titleId.toUpperCase(java.util.Locale.ROOT) + "/cheats");
        try {
            deleteTree(stage);
            stage.mkdirs();
            for (File dir : dirs) {
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (!f.isFile()) continue;
                    File dst = new File(stage, f.getName());
                    if (dst.exists()) continue;   // the first (highest-priority) enabled mod's file for this name wins
                    Files.copy(f.toPath(), dst.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException e) {
            return null;   // staging failed: play with the host's own default cheats folder rather than fail the launch over cosmetic cheats
        }
        return "DM_CHEATS=" + stage.getPath();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
