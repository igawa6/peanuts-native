package dev.peanuts;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/**
 * The companion page (CompanionPage) on the built-in, default display while the game runs on another one (Swap screens, dev.peanuts.Screens, or the app
 * started there; #131).
 * Android takes no Presentation on the default display, so the page gets an activity of its own there, in the game's process. Its window never takes the
 * input focus: the controller stays with the game. Companion starts and finishes it.
 */
public final class CompanionScreen extends Activity {
    static CompanionScreen current;
    private CompanionPage page;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!Companion.screenWanted()) { finish(); return; }   // a stale start: the game ended or went away meanwhile
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        page = new CompanionPage(this, getWindow(), getIntent().getStringExtra("name"));
        setContentView(page.create());
    }

    @Override protected void onStart() { super.onStart(); if (page != null) page.start(); }
    @Override protected void onResume() { super.onResume(); getWindow().getDecorView().post(Companion::refocusGame); }
    @Override protected void onStop() { if (page != null) page.stop(); super.onStop(); }
    @Override protected void onDestroy() { if (current == this) current = null; super.onDestroy(); }

    static void close() {
        CompanionScreen s = current;
        current = null;
        if (s != null && !s.isFinishing()) s.finish();
    }
}
