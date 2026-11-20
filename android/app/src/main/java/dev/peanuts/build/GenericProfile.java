package dev.peanuts.build;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;

import dev.nxnative.android.BuildConfig;

/** Makes the generic host's per-title APK assets available as ordinary files to the game and pack processes. */
public final class GenericProfile {
    private GenericProfile() {}

    public static List<String> envLines(Context c, String titleId) throws IOException {
        return envLines(c, titleId, null);
    }

    /** A modded pack can supply its own module list in profile.bin. Its pipeline seed still comes from the APK. */
    public static List<String> envLines(Context c, String titleId, File packDir) throws IOException {
        if (!BuildConfig.NX_GENERIC_HOST || titleId == null || titleId.isEmpty()) return Collections.emptyList();
        File dir = new File(c.getFilesDir(), "peanuts/profiles");
        Files.createDirectories(dir.toPath());
        long installed;
        try {
            installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            installed = 0;   // Still usable. Refresh the assets on this launch.
        }

        File override = packDir == null ? null : new File(packDir, "profile.bin");
        File profile;
        if (override != null && override.isFile()) {
            try (InputStream in = new FileInputStream(override)) {
                profile = install(in, new File(dir, titleId + ".mod.profile.bin"), 0);
            }
        } else {
            profile = asset(c, new File(dir, titleId + ".profile.bin"), installed, true);
        }
        File seed = asset(c, new File(dir, titleId + ".seed"), installed, false);
        return java.util.Arrays.asList("DM_PROFILE_PATH=" + profile.getPath(),
                "DM_PROFILE_SEED=" + (seed != null && seed.length() > 0 ? seed.getPath() : ""));
    }

    private static File asset(Context c, File dest, long installed, boolean required) throws IOException {
        // The install time also changes when a development APK reuses its version code.
        if (installed > 0 && dest.isFile() && dest.lastModified() == installed) return dest;
        String path = "profiles/" + dest.getName();
        InputStream stream;
        try {
            stream = c.getAssets().open(path);
        } catch (FileNotFoundException e) {
            if (!required) return null;
            throw new IOException("This build has no generic host profile at assets/" + path, e);
        }
        try (InputStream in = stream) {
            return install(in, dest, installed);
        }
    }

    private static File install(InputStream in, File dest, long installed) throws IOException {
        File tmp = File.createTempFile(dest.getName(), ".tmp", dest.getParentFile());
        try {
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (installed > 0) tmp.setLastModified(installed);   // On failure the next launch copies it again.
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }
}
