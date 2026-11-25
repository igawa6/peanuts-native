package dev.peanuts.build;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.peanuts.ui.PUi;

/**
 * The launcher's side of the in-place shader upgrade (ShaderUpgrade): before a game starts (MainActivity.launch) whose build has an older app version's shaders
 * and none of this one, the packer upgrades them (PackService MSG_UPGRADE, seconds) behind a short wait dialog, "Updating the game's shaders for this app
 * version", then the game starts as usual (its launch goes on: with this version's shaders now, it is a plain start). The tool finding no path (exit 3) or
 * failing (exit 1), the packer busy with a build or a check, or no answer in time: the launch goes on exactly as before (the older shaders play, the shader
 * update is offered or runs as it did), with one log line saying why.
 * <p>
 * At Play, not at the library scan after an app update: every way to start a game goes through MainActivity.launch (the cover's Play through
 * dev.peanuts.ui.Upstream.launch, a shortcut or frontend whose game is not ready through Home), the game is about to map its library, the packer is asked
 * for one game at a time (never beside a build, never for a game nobody plays), and the player sees why the start takes a few seconds.
 */
public final class ShaderUpgradeUi {
    private ShaderUpgradeUi() {}

    private static final String TAG = "peanuts", PREFS = "peanuts_shader_upgrade", REFUSED = "refused";
    private static final long WAIT_MS = 180_000, POLL_MS = 300;
    /** Libraries the tool failed on (exit 1) in this process: asked again only in the next one */
    private static final Set<String> failed = new HashSet<>();
    private static boolean running;

    /**
     * Before titleId's game starts with packDir (play: true) or the game page's Update shaders goes on (false): true when an upgrade was started (then runs on
     * the main thread once it has ended, however it ended), or one runs already (this request is dropped: a second Play under the dialog must not start the
     * game beside the first one's, review m5); false when there is nothing to upgrade (the caller goes on at once). inputs: the profile's shader inputs
     * (Profile.shaderInputs; null or "unknown": no upgrade, review M2). Main thread
     */
    public static boolean before(Activity a, String titleId, File packDir, String inputs, boolean play, Runnable then) {
        if (running) { Log.i(TAG, "shader upgrade: " + titleId + ": another start while the shaders are being updated: dropped"); return true; }
        if (titleId == null || packDir == null) return false;
        String current = dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN;
        if (ShaderUpgrade.cleanStale(packDir, current, System.currentTimeMillis())) Log.w(TAG, "shader upgrade: " + titleId + ": removed what a stopped upgrade left in " + packDir);
        List<File> libs = ShaderUpgrade.candidates(packDir, titleId, current, inputs);
        if (libs.isEmpty()) return false;
        libs = ShaderUpgrade.withoutDueCheck(libs, CheckDue.todo(new File(a.getNoBackupFilesDir(), "reuse/" + titleId)), current, packDir);
        SharedPreferences prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> refused = new HashSet<>(prefs.getStringSet(REFUSED, new HashSet<>()));
        List<File> ask = new ArrayList<>();
        for (File l : libs) {
            String k = ShaderUpgrade.refusedKey(l, current);
            if (!refused.contains(k) && !failed.contains(k)) ask.add(l);
        }
        if (ask.isEmpty()) return false;
        Context app = a.getApplicationContext();
        File log = ShaderUpgrade.logFile(app.getFilesDir(), titleId);
        Handler ui = new Handler(Looper.getMainLooper());
        AtomicBoolean ended = new AtomicBoolean();

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView text = PUi.add(box, PUi.body(a, ShaderUpgrade.progressText(null)), 0);
        long biggest = 0;
        for (File l : ask) biggest = Math.max(biggest, l.length());
        PUi.add(box, PUi.small(a, ShaderUpgrade.waitText(biggest, play)), 6);
        ProgressBar bar = PUi.progress(a);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        PUi.add(box, bar, 14, PUi.fill());
        AlertDialog wait = PUi.sheet(a, "Updating shaders", box).setCancelable(false).create();

        ServiceConnection[] conn = new ServiceConnection[1];
        // the end, once: the dialog goes, the binding goes, the game's launch goes on
        java.util.function.BiConsumer<Integer, String[]> end = (code, r) -> {
            if (!ended.compareAndSet(false, true)) return;
            running = false;
            ui.removeCallbacksAndMessages(null);
            try { wait.dismiss(); } catch (RuntimeException e) { /* its activity is gone */ }
            try { if (conn[0] != null) app.unbindService(conn[0]); } catch (RuntimeException e) { /* not bound */ }
            String from = r[0], line = r[1];
            if (code == ShaderUpgrade.UPGRADED) Log.i(TAG, "shader upgrade: " + titleId + ": " + line + " (from " + from + ")");
            else Log.w(TAG, "shader upgrade: " + titleId + ": not upgraded (" + (code == ShaderUpgrade.NO_PATH ? "no path" : code == ShaderUpgrade.BUSY ? "the packer is busy"
                    : "error, code " + code) + "): " + line + "; the game goes on as before (log " + log + ")");
            if (code == ShaderUpgrade.NO_PATH) {   // asked again only for another library or another app version
                for (File l : ask) refused.add(ShaderUpgrade.refusedKey(l, current));
                prefs.edit().putStringSet(REFUSED, refused).apply();
            } else if (code != ShaderUpgrade.UPGRADED && code != ShaderUpgrade.BUSY) {
                for (File l : ask) failed.add(ShaderUpgrade.refusedKey(l, current));
            }
            then.run();
        };
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), m -> {
            if (m.what != 5) return false;   // PackService.MSG_UPGRADE_DONE
            Bundle d = m.getData();
            end.accept(d.getInt(ShaderUpgrade.KEY_CODE, ShaderUpgrade.ERROR), new String[] {d.getString(ShaderUpgrade.KEY_FROM, ""), d.getString(ShaderUpgrade.KEY_LINE, "")});
            return true;
        }));
        conn[0] = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName n, IBinder b) {
                Message m = Message.obtain(null, 4);   // PackService.MSG_UPGRADE
                Bundle d = new Bundle();
                d.putString(ShaderUpgrade.KEY_TITLE, titleId);
                d.putString(ShaderUpgrade.KEY_PACK, packDir.getPath());
                ArrayList<String> paths = new ArrayList<>();
                for (File l : ask) paths.add(l.getPath());
                d.putStringArrayList(ShaderUpgrade.KEY_LIBS, paths);
                m.setData(d);
                m.replyTo = reply;
                try { new Messenger(b).send(m); } catch (RemoteException e) { end.accept(ShaderUpgrade.BUSY, new String[] {"", "the packer could not be asked: " + e}); }
            }
            @Override public void onServiceDisconnected(ComponentName n) { end.accept(ShaderUpgrade.ERROR, new String[] {"", "the packer's process ended"}); }
        };
        boolean bound;
        try { bound = app.bindService(Upstream.packBind(app), conn[0], Context.BIND_AUTO_CREATE); }
        catch (ClassNotFoundException | RuntimeException e) { Log.w(TAG, "shader upgrade: " + e); bound = false; }
        if (!bound) {
            try { app.unbindService(conn[0]); } catch (RuntimeException e) { /* not bound */ }
            return false;
        }
        running = true;
        wait.show();
        long t0 = System.currentTimeMillis();
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (ended.get()) return;
                if (System.currentTimeMillis() - t0 > WAIT_MS) { end.accept(ShaderUpgrade.BUSY, new String[] {"", "no answer within " + WAIT_MS / 1000 + " s"}); return; }
                long[] p = ShaderUpgrade.progress(ShaderUpgrade.read(log));
                if (p != null && p[1] > 0 && log.lastModified() >= t0 - 1000) {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) Math.min(1000, p[0] * 1000 / p[1]));
                    text.setText(ShaderUpgrade.progressText(p));
                }
                ui.postDelayed(this, POLL_MS);
            }
        };
        ui.postDelayed(poll, POLL_MS);
        return true;
    }
}
