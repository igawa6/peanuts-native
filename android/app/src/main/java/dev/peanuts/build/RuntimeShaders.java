package dev.peanuts.build;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Shaders a game compiles at run time with its own GLSLC (Fire Emblem: Three Houses: nine built-in 2D/text shaders at boot; Turrican) are in no game file, so a
 * pack made from the files alone lacks them: the job finishes (Try anyway) with "shaders_missing" [n, total] and the host skips their draws (black panels, no
 * text). The Mac's NSP script boots the game once with DM_GLSLC_CAPTURE to collect them (dm/host/hle_glslc.c, dm/games/README.md); this does the same on the
 * phone, inside Build, after Prepare:
 * <ol>
 * <li>the non-exported GameActivity starts in internal preparation mode, with an opaque progress screen, isolated save data, no input, sound or companion;</li>
 * <li>its boot is watched until the folder holds the missing count of .glslc files (or {@link #CAPTURE_MS} pass), then the ":game" process is ended;</li>
 * <li>&lt;work&gt;/ondevice/env.txt gets the same line for the packer's tools (PackService reads it; shader_split.py reads DM_GLSLC_CAPTURE), the job's split, shaders
 *     and pack steps are set back to pending, and Prepare resumes it: the split finds the captured outputs, the shaders step keeps the library's translated shaders
 *     (DMSH_VK_DELTA_FROM) and translates the new ones, the pack is written again.</li>
 * </ol>
 * The whole step is skipped when nothing is missing, or when the capture folder already holds enough files (a second Build only re-runs the job).
 */
final class RuntimeShaders {
    private static final String TAG = Prepare.TAG;
    private static final String ENV = "DM_GLSLC_CAPTURE";
    /** How long the game may run for its boot-time compiles (they come within seconds of the first frame) */
    private static final long CAPTURE_MS = 150_000;
    private static final long START_MS = 30_000;

    private final Context c;

    RuntimeShaders(Context c) { this.c = c.getApplicationContext(); }

    /** The missing count from the job file, 0 when the job lacks the line or nothing is missing */
    static int missing(File jobFile) {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(jobFile.toPath()), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("shaders_missing");
            return a == null || a.length() < 1 ? 0 : a.optInt(0);
        } catch (IOException | JSONException e) {
            return 0;
        }
    }

    /**
     * {shaders that did not translate or validate, total shaders} from the job file ("shaders_failed", written by the shaders step), {0, 0} when the job lacks the
     * line or every shader was built. With Try anyway (pressed for the player, AutoRelax) the library is written without them and the build still ends as ready
     */
    static long[] failed(File jobFile) {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(jobFile.toPath()), StandardCharsets.UTF_8));
            JSONArray a = o.optJSONArray("shaders_failed");
            return a == null || a.length() < 2 ? new long[] {0, 0} : new long[] {a.optLong(0), a.optLong(1)};
        } catch (IOException | JSONException e) {
            return new long[] {0, 0};
        }
    }

    /**
     * Whether the title compiles shaders at run time at all: its profile's shader_map.tsv (in the APK's tools.zip) names GLSLC run-time
     * sources (/glslc_capture/ paths, dm/games/README.md). A pack can miss shaders for other reasons (a game version the map was not made from: Mario Kart 8 Deluxe's 135),
     * and those no game run can capture
     */
    static boolean compilesAtRunTime(Context c, String titleId) throws IOException {
        // PackEngine has no repo() method; PackNative.repo() also starts CPython and belongs in :pack only.
        return RuntimeShaderMap.compilesAtRunTime(c.getAssets().open("pack/tools.zip"), titleId);
    }

    static int missingInPack(Context c, String titleId, File pack) throws IOException {
        File library = new File(pack, "shaders/v-" + dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN + "/dmsh_vk.lib");
        return RuntimeShaderMap.missing(c.getAssets().open("pack/tools.zip"), titleId, library);
    }

    /** The buttons the title's capture boot presses (RuntimeShaderMap.captureKeys), "" for none or an APK without the profile */
    static String captureKeys(Context c, String titleId) {
        try { return RuntimeShaderMap.captureKeys(c.getAssets().open("pack/tools.zip"), titleId); }
        catch (IOException e) { Log.w(TAG, "capture: no capture keys for " + titleId + " (" + e.getMessage() + ")"); return ""; }
    }

    static File captureDir(File work) { return new File(work, "glslc_capture"); }

    static int captured(File dir) {
        String[] l = dir.list((d, n) -> n.endsWith(".glslc"));
        return l == null ? 0 : l.length;
    }

    /**
     * Captures the game's run-time shaders when the capture folder holds fewer than {@code need}; returns true when the folder has enough afterwards.
     * {@code game} and {@code pack} are the prepared input and pack; {@code cancelled} ends the wait early.
     */
    boolean capture(String titleId, File work, File game, File pack, int need, Prepare.Progress p, java.util.function.BooleanSupplier cancelled) throws IOException {
        File dir = captureDir(work);
        if (captured(dir) >= need) return true;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Finishing graphics setup could not make its folder. " + Prepare.RETRY_SHARE + "\n" + dir);
        // A game already running (the player's, perhaps another title) would count as this capture's start, and endGame() would then end it, unsaved
        if (gameAlive()) throw new IOException("Finishing graphics setup needs the game to start, and a game is running. Close it, then tap Retry.");
        File save = new File(work, "shader-capture-save");
        Files.deleteIfExists(ShaderCapture.error(dir).toPath());
        try {
            p.on(0, "Finishing graphics setup");
            Intent i = ShaderCapture.intent(c, titleId, game, pack, dir, save, captureKeys(c, titleId));
            try {
                c.startActivity(i);
            } catch (RuntimeException e) {
                throw new IOException("Android did not let graphics setup start. Open the app and tap Retry.", e);
            }
            long t0 = SystemClock.elapsedRealtime();
            boolean seen = false;
            while (!cancelled.getAsBoolean()) {
                SystemClock.sleep(1000);
                int have = captured(dir);
                boolean alive = gameAlive();
                seen |= alive;
                if (have >= need) { p.on(100, "Finishing graphics setup"); break; }
                long t = SystemClock.elapsedRealtime() - t0;
                if (seen && !alive) { Log.w(TAG, "capture: the game ended with " + have + " of " + need); break; }
                if (!seen && t > START_MS) throw new IOException("Finishing graphics setup could not start the game. " + Prepare.RETRY_SHARE);
                if (t > CAPTURE_MS) { Log.w(TAG, "capture: " + have + " of " + need + " after " + t / 1000 + " s"); break; }
                p.on((int) Math.min(95, 5 + have * 90L / Math.max(1, need)), String.format(java.util.Locale.ROOT, "Finishing graphics setup: %,d of %,d", have, need));
            }
        } finally {
            endGame();   // also on cancellation, a start failure or timeout
        }
        File error = ShaderCapture.error(dir);
        if (error.isFile()) throw new IOException("Finishing graphics setup stopped. " + Prepare.RETRY_SHARE + "\n" + new String(Files.readAllBytes(error.toPath()), StandardCharsets.UTF_8));
        return captured(dir) >= need;
    }

    /** Points the packer's tools at the capture folder and sets the job back to its split step, so Prepare resumes it from there */
    static void rerun(File work, File jobFile) throws IOException {
        File od = jobFile.getParentFile();
        File env = new File(od, "env.txt");
        String text = env.isFile() ? new String(Files.readAllBytes(env.toPath()), StandardCharsets.UTF_8) : "";
        Files.write(env.toPath(), withLine(text, ENV + "=" + captureDir(work).getPath()).getBytes(StandardCharsets.UTF_8));
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(jobFile.toPath()), StandardCharsets.UTF_8));
            JSONObject steps = o.optJSONObject("steps");
            if (steps == null) throw new IOException("Finishing graphics setup could not go on with the build. " + Prepare.RETRY_SHARE + "\njob.json has no steps: " + jobFile);
            for (String s : new String[] {"split", "shaders", "pack"}) {
                JSONObject st = steps.optJSONObject(s);
                if (st == null) { st = new JSONObject(); steps.put(s, st); }
                st.put("state", "pending").put("done", 0).put("total", 0).put("stage", "");
            }
            o.put("state", "pending").put("step", "split").put("reason", "").put("error", "");
            o.remove("shaders_missing");
            o.remove("shaders_failed");
            o.remove("shader_cache");
            Files.write(jobFile.toPath(), o.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Finishing graphics setup could not go on with the build. " + Prepare.RETRY_SHARE + "\njob.json cannot be reset: " + e.getMessage());
        }
    }

    private boolean gameAlive() {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) if (p.processName != null && p.processName.equals(c.getPackageName() + ":game")) return true;
        return false;
    }

    /** Ends the ":game" process (the game and its activity: GameActivity runs in it), as the system does when a game is swiped away */
    private void endGame() {
        // The run's exit record (ExitWatch: <files>/last-exit.txt, "state=running" while the game runs) gets the player's own "stopped" line first, so the launcher
        // does not tell the player that the game ended unexpectedly: this end is the build's
        File rec = new File(c.getFilesDir(), "last-exit.txt");
        if (rec.isFile()) {
            try { Files.write(rec.toPath(), "state=stopped\n".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND); } catch (IOException e) { Log.w(TAG, "capture: exit record " + e); }
        }
        ActivityManager am = c.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> l = am == null ? null : am.getRunningAppProcesses();
        if (l != null) for (ActivityManager.RunningAppProcessInfo p : l) if (p.processName != null && p.processName.equals(c.getPackageName() + ":game")) android.os.Process.killProcess(p.pid);
        SystemClock.sleep(1500);
    }

    static String withLine(String text, String line) {
        String key = line.substring(0, line.indexOf('=') + 1);
        List<String> out = new ArrayList<>();
        for (String l : (text == null ? "" : text).split("\n")) if (!l.trim().isEmpty() && !l.trim().startsWith(key)) out.add(l);
        out.add(line);
        return String.join("\n", out) + "\n";   // every line ended: an append to the file (another tool's line) never runs into the last one (Engage's env.txt read "DMSH_VK_XLATE_J=4DMSH_VK_XLATE_J=4")
    }

}
