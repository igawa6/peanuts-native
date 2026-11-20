package dev.peanuts.ui;

import android.app.Activity;
import android.app.Presentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;

import dev.nxnative.android.BananaBackgroundView;
import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;

/**
 * The game's side of the second display (game process, Companion): the start page until its companion is up,
 * or the game's cover in place of one (Settings › Library › Games without a companion).
 */
public final class GameScreen extends Presentation {
    public static final int LOADING = 0, PLAYING = 1;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static GameScreen shown;
    private static int mode = -1;
    private static String name = "", iconOf = "";
    private static Bitmap icon;

    private BananaBackgroundView bg;
    private StartView start;

    private GameScreen(Activity game, Display d) { super(game, d, R.style.Theme_Banana); }

    /** Opens it on d, or switches it to mode */
    public static void open(Activity game, Display d, String titleId, String gameName, int m) {
        if (d == null || game.isFinishing() || game.isDestroyed()) return;
        name = gameName == null ? "" : gameName;
        if (!iconOf.equals(titleId)) { iconOf = titleId; icon = null; }   // another game in this process
        if (shown != null && shown.getDisplay() != null && shown.getDisplay().getDisplayId() != d.getDisplayId()) close();
        mode = m;
        if (shown == null) {
            Motion.refresh(game);
            try { shown = new GameScreen(game, d); shown.show(); }
            catch (RuntimeException e) { Log.w("peanuts", "game screen not shown on display " + d.getDisplayId() + ": " + e); shown = null; return; }
            BackgroundFlow.field(game.getDisplay(), d);
            if (icon == null) loadIcon(game, titleId);
        }
        shown.apply();
    }

    public static int mode() { return shown != null ? mode : -1; }

    /** The game left the foreground */
    public static void away() { if (shown != null) { shown.pause(); try { shown.hide(); } catch (RuntimeException ignored) { } } }

    /** The game is back */
    public static void back() { if (shown != null) { try { shown.show(); } catch (RuntimeException e) { shown = null; return; } shown.apply(); } }

    public static void close() {
        if (shown == null) return;
        GameScreen s = shown;
        shown = null;
        mode = -1;
        BackgroundFlow.clear();
        try { s.dismiss(); } catch (RuntimeException ignored) { }
    }

    /** After the companion's window is up, so the display never shows Android's launcher between the two */
    public static void closeSoon() { UI.postDelayed(GameScreen::close, 250); }

    private static void loadIcon(Activity game, String titleId) {
        File f = new File(PeanutsPrefs.iconsDir(game), titleId + ".png");
        if (!f.isFile()) return;
        new Thread(() -> {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) UI.post(() -> { if (!titleId.equals(iconOf)) return; icon = b; if (shown != null) shown.start.icon(b); });
        }, "peanuts-game-icon").start();
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);   // the game keeps the controller
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PUi.col(getContext(), R.color.bn_bg)));
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(PUi.col(getContext(), R.color.bn_bg));
        bg = new BananaBackgroundView(getContext());
        root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        start = new StartView(getContext());
        root.addView(start, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override protected void onStop() { pause(); super.onStop(); }

    private void pause() { if (bg != null) bg.setRunning(false); if (start != null) start.setAnimating(false); }

    /** Loading: drifting, breathing, gliding. Playing: a still page */
    private void apply() {
        if (start == null) return;
        boolean loading = mode == LOADING;
        start.bind(name, icon);
        start.status(loading ? "Starting the companion" : "Now playing");
        start.quiet(!loading);
        start.setAnimating(loading);
        bg.setRunning(loading);
    }
}
