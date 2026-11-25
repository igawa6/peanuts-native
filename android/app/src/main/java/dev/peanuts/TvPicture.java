package dev.peanuts;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

/**
 * The game's picture on a TV or monitor (Play on the TV, GitHub issue #358). GameActivity asks at its start and whenever its display changes (a TV came or
 * went and Android moved the game):
 * <ul>
 * <li>The game surface is at most 1080 lines high, with the window's shape (ScreenRole.tvSurface). On a 4K TV the display hardware scales it up, so the GPU
 *     does not write a 3840x2160 picture each frame. The present fits the game's 16:9 picture into the surface as on any screen (vka_present.c).</li>
 * <li>The window asks for the TV's game mode (Window.setPreferMinimalPostProcessing: Auto Low Latency Mode over HDMI), so the TV does not add its picture
 *     processing and the lag that comes with it.</li>
 * <li>The refresh rate: RunningDisplayMode asks for 60 Hz when the TV runs 30 or 50 Hz (RunningDisplayPolicy.tvMode).</li>
 * </ul>
 * On the device's own screens the surface follows the window and nothing is asked, as before.
 */
public final class TvPicture {
    private TvPicture() {}

    /** GameActivity.onCreate and onConfigurationChanged (PEANUTS hooks) */
    public static void fit(Activity a) {
        try {
            boolean tv = Screens.onTv(a);
            a.getWindow().setPreferMinimalPostProcessing(tv);
            SurfaceView v = surface(a.getWindow().getDecorView());
            if (v == null) return;
            Rect b = a.getWindowManager().getCurrentWindowMetrics().getBounds();
            int[] size = ScreenRole.tvSurface(b.width(), b.height(), tv);
            String was = asked.get(v), now = size == null ? "window" : size[0] + "x" + size[1];
            if (now.equals(was)) return;
            asked.put(v, now);
            if (size == null) v.getHolder().setSizeFromLayout();
            else v.getHolder().setFixedSize(size[0], size[1]);
            if (tv || was != null) Log.i(Companion.TAG, "the game's picture: " + (size == null ? "the window's size, " + b.width() + "x" + b.height() : now + " in a " + b.width() + "x" + b.height() + " window")
                    + (tv ? ", the TV's game mode asked" + (a.getDisplay() != null && a.getDisplay().isMinimalPostProcessingSupported() ? "" : " (it has none)") : ""));
        } catch (RuntimeException e) {
            Log.w(Companion.TAG, "the game's picture was not fitted to its display: " + e);
        }
    }

    /** What each game surface was last given ("window" or the fixed size), so a configuration change that keeps the display asks nothing again */
    private static final java.util.Map<SurfaceView, String> asked = new java.util.WeakHashMap<>();

    private static SurfaceView surface(View v) {
        if (v instanceof SurfaceView) return (SurfaceView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { SurfaceView s = surface(g.getChildAt(i)); if (s != null) return s; }
        }
        return null;
    }
}
