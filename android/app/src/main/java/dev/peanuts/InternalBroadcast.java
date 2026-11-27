package dev.peanuts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * The app's own broadcast receivers (the updater's installer answer, the pack and build services' battery, the live game commands, the bottom screen's
 * companion and page state), registered so that only this app, or the system, reaches them on every Android version.
 * From API 33: the flag RECEIVER_NOT_EXPORTED, the call as it always was. Android 12 (API 31/32) has no such flag, and the plain 2-argument call would take
 * broadcasts from any app; there the receiver requires {@link #permission}, a signature-level permission the manifest declares and requests
 * ({@code ${applicationId}.permission.INTERNAL_BROADCAST}: its own name per application ID, so the dev build and the release do not clash). This app's own
 * senders hold it; the system (ACTION_BATTERY_CHANGED) passes every permission check; a PendingIntent the installer sends goes out as this app.
 */
public final class InternalBroadcast {
    private InternalBroadcast() {}

    public static final String SUFFIX = ".permission.INTERNAL_BROADCAST";

    /** The permission's name: this app's application ID (its package name) with SUFFIX */
    public static String permission(Context c) { return c.getPackageName() + SUFFIX; }

    /** Context.registerReceiver for an app-internal receiver; returns what registerReceiver returns (the sticky intent, if any) */
    public static Intent register(Context c, BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) return c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        return c.registerReceiver(r, f, permission(c), null);   // Android 12: senders must hold the signature permission (the 4-argument call is on every API level)
    }
}
