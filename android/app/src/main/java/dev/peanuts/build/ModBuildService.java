package dev.peanuts.build;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * "Build with mods": a foreground service ("Building &lt;name&gt; with mods") that runs {@link Prepare#runModded} for one variant at a time — the
 * modded-variant counterpart of {@link BuildService}, but with none of its import/companion steps: a modded build always starts from a dump that is
 * already imported (the normal build's own), so there is nothing to import, and a companion is not mod content (DESIGN-MODS.md "Code injection").
 * ACTION_CANCEL stops the running build between progress updates (the packer's job stays saved; Retry from the Mods section resumes it).
 */
public final class ModBuildService extends Service {
    public static final String ACTION_BUILD = "dev.peanuts.build.MOD_BUILD", ACTION_CANCEL = "dev.peanuts.build.MOD_CANCEL";
    private static final String EXTRA_ID = "id", EXTRA_TITLE = "title_id", EXTRA_NAME = "name", EXTRA_DUMP = "dump", EXTRA_NORMAL_PACK = "normal_pack",
            EXTRA_VARIANT_ID = "variant_id", EXTRA_WORK_DIR = "work_dir", EXTRA_PACK_DIR = "pack_dir", EXTRA_MOD_ARGS = "mod_args";
    private static final String TAG = Prepare.TAG, CHANNEL = "peanuts_mod_build";
    private static final int NOTE = 7101;

    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private Thread worker;
    private volatile Prepare prepare;
    private volatile boolean cancelled;
    private int id;
    private String name = "";

    static Intent intent(Context c, int id, ModBuildRequest r) {
        return new Intent(c, ModBuildService.class).setAction(ACTION_BUILD).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, r.titleId).putExtra(EXTRA_NAME, r.name)
                .putExtra(EXTRA_DUMP, r.dump.getPath()).putExtra(EXTRA_NORMAL_PACK, r.normalPack.getPath()).putExtra(EXTRA_VARIANT_ID, r.variantId)
                .putExtra(EXTRA_WORK_DIR, r.workDir.getPath()).putExtra(EXTRA_PACK_DIR, r.packDir.getPath()).putExtra(EXTRA_MOD_ARGS, r.modArgs == null ? "" : r.modArgs);
    }

    private static ModBuildRequest request(Intent i) {
        ModBuildRequest r = new ModBuildRequest();
        r.titleId = i.getStringExtra(EXTRA_TITLE);
        r.name = i.getStringExtra(EXTRA_NAME);
        r.dump = new File(i.getStringExtra(EXTRA_DUMP));
        r.normalPack = new File(i.getStringExtra(EXTRA_NORMAL_PACK));
        r.variantId = i.getStringExtra(EXTRA_VARIANT_ID);
        r.workDir = new File(i.getStringExtra(EXTRA_WORK_DIR));
        r.packDir = new File(i.getStringExtra(EXTRA_PACK_DIR));
        r.modArgs = i.getStringExtra(EXTRA_MOD_ARGS);
        return r;
    }

    @Override public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Building games with mods", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            Prepare p = prepare;
            if (p != null) p.cancel();
            Thread w = worker;
            if (w != null) w.interrupt();
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        ModBuildRequest r = request(intent);
        int reqId = intent.getIntExtra(EXTRA_ID, 0);
        if (worker != null && worker.isAlive()) {
            ModBuildPipeline.finish(reqId, false, name + " is being built with mods now. Wait for it to finish.", BuildListener.OTHER);
            return START_NOT_STICKY;
        }
        id = reqId;
        name = r.name == null || r.name.isEmpty() ? r.titleId : r.name;
        cancelled = false;
        try {
            startForeground(NOTE, note(0, "Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground refused (modded): " + e);
            ModBuildPipeline.finish(id, false, "Android did not let the modded build start in the background: open the app and try again.", BuildListener.OTHER);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        worker = new Thread(() -> run(r), "peanuts-mod-build");
        worker.start();
        return START_NOT_STICKY;
    }

    private void run(ModBuildRequest r) {
        boolean ok = false;
        String message;
        String code = BuildListener.OTHER;
        long started = System.currentTimeMillis();
        // the game's build log (BuildLog, Share build log): a build with mods is a run of its own there, its job in its own work folder
        BuildLogShare.line(this, r.titleId, BuildLog.START + "build of " + name + " (" + r.titleId + ") with mods, variant " + r.variantId + ", " + Upstream.buildTag(this)
                + ", engine " + dev.nxnative.android.BuildConfig.NX_PACK_ENGINE + "\ngame file: " + r.dump + "\nwork folder: " + r.workDir + "\npack: " + r.packDir);
        BuildLogShare.remember(this, r.titleId, r.workDir);
        try {
            File normalShaders = ModdedBuild.normalShadersDir(r.normalPack);
            Prepare p = new Prepare(this);
            prepare = p;
            File pack;
            try {
                pack = p.runModded(r.dump, r.titleId, r.workDir, r.packDir, r.modArgs, normalShaders, (pct, detail) -> progress(pct, detail));
            } finally {
                prepare = null;
            }
            ok = true;
            ModdedBuild.markVariant(pack, r.variantId);   // known as a variant wherever it lies: never taken for the game's own pack (ModdedBuild.isVariantPack)
            message = name + " is ready to play with mods (" + pack.getName() + ").";
            progress(100, message);
        } catch (IOException e) {
            message = e.getMessage() == null ? e.toString() : e.getMessage();
            if (e instanceof BuildListener.Failure) code = ((BuildListener.Failure) e).code;
            Log.w(TAG, "modded build " + name + ": " + message);
        } catch (RuntimeException e) {
            message = "Building with mods failed: " + e;
            Log.e(TAG, "modded build " + name, e);
        }
        long took = (System.currentTimeMillis() - started) / 1000;
        BuildLogShare.line(this, r.titleId, BuildLog.END + (ok ? "ready" : BuildListener.CANCELLED.equals(code) ? "stopped (" + code + ")" : "failed (" + code + ")") + ": "
                + String.valueOf(message).replace('\n', ' ') + " (after " + took / 60 + " min " + took % 60 + " s)");
        final boolean fok = ok;
        final String fmsg = message;
        final String fcode = code;
        main.post(() -> {
            nm.notify(NOTE + 1, done(fok, fmsg));
            ModBuildPipeline.finish(id, fok, fmsg, fcode);
            worker = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void progress(int percent, String detail) {
        if (worker != null) nm.notify(NOTE, note(percent, detail));
        ModBuildPipeline.progress(id, "rewrite", percent, detail);
    }

    private Notification note(int percent, String detail) {
        Intent cancel = new Intent(this, ModBuildService.class).setAction(ACTION_CANCEL);
        Notification.Builder b = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Building " + name + " with mods")
                .setContentText(detail).setOnlyAlertOnce(true).setOngoing(true).setProgress(100, Math.max(0, Math.min(100, percent)), false)
                .addAction(new Notification.Action.Builder(null, "Cancel", PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    private Notification done(boolean ok, String message) {
        Notification.Builder b = new Notification.Builder(this, BuildPipeline.endChannel(nm, CHANNEL, !ok && cancelled)).setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? name + " is ready to play with mods" : "Building " + name + " with mods stopped").setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message)).setAutoCancel(true);
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open != null) b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }
}
