// PEANUTS: the adb control's live game commands, the game's side (peanuts/docs/CONTROL.md, game do=fps_cap | console_mode | second_screen | controls)
package dev.peanuts.ctl;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.peanuts.Companion;
import dev.peanuts.ui.GameControls;

/**
 * GameActivity registers this receiver at onCreate and lets it go at onDestroy. It takes GameLive.ACTION, which Ctl sends from the launcher's process as an
 * ordered broadcast with the app's own package set; the receiver is not exported (Context.RECEIVER_NOT_EXPORTED), so only this app's own processes reach it.
 * onReceive runs on the game's main thread, where GameControls.host() is read and called as the stats page does, and sets the broadcast's result (RESULT_OK
 * and GameLive.EXTRA_ANSWER) that Ctl's final receiver reads.
 */
public final class GameLink {
    private GameLink() {}

    /** The receiver, registered; null when Android refused it (the commands then answer no_answer) */
    public static BroadcastReceiver register(Activity game) {
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!isOrderedBroadcast()) return;   // Ctl always sends it ordered: nothing else has a result to set
                Map<String, String> args = new LinkedHashMap<>();
                for (String k : GameLive.ARGS) { String v = i.getStringExtra(k); if (v != null) args.put(k, v); }
                Bundle b = new Bundle();
                b.putString(GameLive.EXTRA_ANSWER, GameLive.answer(args, live(game)));
                setResult(Activity.RESULT_OK, null, b);
            }
        };
        try {
            game.registerReceiver(r, new IntentFilter(GameLive.ACTION), Context.RECEIVER_NOT_EXPORTED);
            return r;
        } catch (RuntimeException e) {
            Log.w(Ctl.TAG, "live game commands not available: " + e);
            return null;
        }
    }

    public static void unregister(Activity game, BroadcastReceiver r) {
        if (r == null) return;
        try { game.unregisterReceiver(r); } catch (RuntimeException e) { /* not registered */ }
    }

    /** The game's controls as GameLive asks for them; null while the game's screen has none (GameControls.host() is null) */
    private static GameLive.Live live(Activity game) {
        GameControls.Host h = GameControls.host();
        if (h == null) return null;
        String id = game.getIntent() == null ? null : game.getIntent().getStringExtra("title_id");
        return new GameLive.Live() {
            @Override public String titleId() { return id; }
            @Override public int consoleMode() { return h.consoleMode(); }
            @Override public boolean consoleMode(int m) { return h.consoleMode(m); }
            @Override public int[] caps() { return h.caps(); }
            @Override public String capLabel(int v) { return h.capLabel(v); }
            @Override public boolean capOffered(int v) { return h.capOffered(v); }
            @Override public String capWhy(int v) { return h.capWhy(v); }
            @Override public int cap() { return h.cap(); }
            @Override public int capRunning() { return h.capRunning(); }
            @Override public void cap(int v) { h.cap(v); }
            @Override public void forgetCap() {
                try { Up.call(Up.k("Prefs"), null, "fpsCap", new Class<?>[] {Context.class, String.class, Integer.class}, game, id, null); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException("upstream changed: " + e, e); }
            }
            @Override public Integer defaultCap() {
                try {
                    // a limited game's cap without its own: Prefs.paceCap (the recommended one, the profile's limit, Game owned, the main screen's); the others: Prefs.fpsCap
                    boolean limited = Up.prefsBool("limited", id);
                    int v = ((Number) Up.call(Up.k("Prefs"), null, limited ? "paceCap" : "fpsCap", new Class<?>[] {Context.class, String.class}, game, id)).intValue();
                    for (int c : h.caps()) if (c == v) return v;
                    return null;   // the profile's own target (-1): only a start sets it
                } catch (ReflectiveOperationException e) { throw new IllegalStateException("upstream changed: " + e, e); }
            }
            @Override public Boolean screenShown() { return Companion.screenShown(game); }
            @Override public Boolean toggleScreen() { return Companion.toggleScreen(game); }
        };
    }
}
