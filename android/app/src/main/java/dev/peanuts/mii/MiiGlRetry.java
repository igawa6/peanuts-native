package dev.peanuts.mii;

import java.io.File;

/**
 * When MiiGl tries again after it could not start (the firmware's Mii files unreadable, no EGL context, a shader the GPU refused): once the Mii files are other
 * files (the firmware imported again), or WAIT_MS after the failure, so a later page asks again instead of the renderer staying off until the process ends.
 * Between those, a picture asked for gets none at once. No Android classes: MiiGlRetryJvmTest checks it.
 */
final class MiiGlRetry {
    static final long WAIT_MS = 30_000;

    private boolean failed;
    private long at;
    private String stamp;

    /** The renderer could not start at now; dir is the Mii files' folder */
    void failed(long now, File dir) { failed = true; at = now; stamp = stamp(dir); }

    /** It started */
    void ok() { failed = false; stamp = null; }

    /** Whether it still waits after a failure: true until the files in dir change or WAIT_MS have passed (then the next try is now) */
    boolean waiting(long now, File dir) {
        if (!failed) return false;
        if (now - at < WAIT_MS && stamp(dir).equals(stamp)) return true;
        failed = false;
        return false;
    }

    /** The Mii files MiiParts would open in dir, each by name, size and the time it was written; "" when there are none */
    static String stamp(File dir) {
        StringBuilder s = new StringBuilder();
        for (String[] names : new String[][] {MiiParts.TEXTURE_FILES, MiiParts.SHAPE_FILES})
            for (String n : names) {
                File f = new File(dir, n);
                if (f.isFile()) s.append(n).append(':').append(f.length()).append(':').append(f.lastModified()).append(';');
            }
        return s.toString();
    }
}
