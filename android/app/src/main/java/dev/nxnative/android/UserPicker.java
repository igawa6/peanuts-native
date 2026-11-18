package dev.nxnative.android;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The user selector (the host's nxhost_user_select_callback, through nxlaunch.c; nn::account::ShowUserSelector): a list of the users over the game, the ones the game
 * leaves out (a user that already has a save, for instance) not offered, the preselected one first marked. Picking one returns it; Cancel, Back (or B) cancels, which
 * the game takes as the console's cancelled selector. {@link #ask} runs on a host thread and blocks until then, and the box shows on the game's screen as TextInput's
 * does (again when the screen is recreated; a cancel when the game stops).
 */
final class UserPicker {
    private UserPicker() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final class Request {
        final String[] names; final int[] offered; final int def;
        int result = -1; boolean done; AlertDialog dialog;
        Request(String[] names, int[] offered, int def) { this.names = names; this.offered = offered; this.def = def; }
    }

    private static GameActivity screen;   // UI thread
    private static Request pending;        // guarded by the class

    /** Called by nxlaunch.c on a thread of its own: the names (UTF-8, one a line), offered[i] != 0 for a user the game offers, def the preselected user. The pick, or -1 */
    static int ask(byte[] names, int[] offered, int def) {
        String[] n = new String(names == null ? new byte[0] : names, StandardCharsets.UTF_8).split("\n");
        Request r = new Request(n, offered == null ? new int[0] : offered, def);
        synchronized (UserPicker.class) { pending = r; }
        UI.post(UserPicker::show);
        synchronized (r) {
            while (!r.done) {
                try { r.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        synchronized (UserPicker.class) { if (pending == r) pending = null; }
        return r.result;
    }

    private static void finish(Request r, int result) {   // UI thread
        if (r.dialog != null) { r.dialog.setOnDismissListener(null); r.dialog.setOnCancelListener(null); r.dialog.dismiss(); r.dialog = null; }
        synchronized (UserPicker.class) { if (pending == r) pending = null; }
        synchronized (r) { r.result = result; r.done = true; r.notifyAll(); }
    }

    static void screen(GameActivity a) { screen = a; show(); }

    static void gone(GameActivity a) {
        if (screen != a) return;
        screen = null;
        Request r;
        synchronized (UserPicker.class) { r = pending; }
        if (r == null) return;
        if (r.dialog != null) { r.dialog.setOnCancelListener(null); r.dialog.dismiss(); r.dialog = null; }
        if (a.isFinishing()) { Native.note("[RED] user selector: the game's screen closed while it was up: the game gets a cancel"); finish(r, -1); }
    }

    private static void show() {   // UI thread
        Request r;
        synchronized (UserPicker.class) { r = pending; }
        GameActivity a = screen;
        if (r == null || r.done || r.dialog != null || a == null || a.isFinishing() || a.isDestroyed()) return;
        List<Integer> idx = new ArrayList<>(); List<String> labels = new ArrayList<>();
        for (int i = 0; i < r.names.length && i < r.offered.length; i++) {
            if (r.offered[i] == 0) continue;
            idx.add(i); labels.add(r.names[i] + (i == r.def ? "  ·  current" : ""));
        }
        AlertDialog d = new PadFocus.Builder(a).setTitle("Select a user").setItems(labels.toArray(new String[0]), (x, w) -> finish(r, idx.get(w)))
                .setNegativeButton("Cancel", (x, w) -> finish(r, -1)).setCancelable(true).create();
        d.setCanceledOnTouchOutside(false);
        d.setOnCancelListener(x -> finish(r, -1));   // Back
        r.dialog = d;
        d.show();
    }
}
