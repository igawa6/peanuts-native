package dev.nxnative.android;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;

/**
 * The controller applet's join screen (nn::hid::ShowControllerSupport; the host's nxhost_ctrl_callback, through nxlaunch.c): one card per player the game asks for, filled
 * as controllers press L and R together; A on a joined controller ends it once enough have joined, B cancels, as on the console. The host takes the pad keys while it
 * shows (input_android.c), so this is an overlay that never takes the keys' focus: the cards follow the host's state (Native.ctrlState), read every 100 ms. Its own
 * buttons are for touch: Cancel, OK, and, with no gamepad connected, Join with the on-screen controls (the console's handheld controller, player 1). Back cancels it,
 * and so does the game's screen closing for good. The game waits until it ends.
 */
final class CtrlScreen {
    private CtrlScreen() {}

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static GameActivity screen;   // UI thread
    private static View view;             // the overlay shown, or null
    private static volatile boolean open;

    /** From nxlaunch.c (a thread of its own), when the host's join screen opens (true) and ends (false); returns at once */
    @SuppressWarnings("unused")
    static void changed(boolean on) { open = on; UI.post(CtrlScreen::sync); }

    static void screen(GameActivity a) { screen = a; open = state().startsWith("open"); sync(); }

    static void gone(GameActivity a) {
        if (screen != a) return;
        hide(); screen = null;
        if (open && a.isFinishing()) { Native.note("[RED] controller applet: the game's screen closed while it was up: the game gets a cancel"); Native.ctrlButton(0); }
    }

    /** Back: cancels the screen while it shows; false when it does not */
    static boolean back() { if (!open) return false; Native.ctrlButton(0); return true; }

    private static String state() { byte[] b = Native.ctrlState(); return b == null ? "closed" : new String(b, StandardCharsets.UTF_8); }

    private static void sync() {   // UI thread
        if (!open || screen == null || screen.isFinishing() || screen.isDestroyed()) { hide(); return; }
        if (view == null) show(screen);
    }

    private static void hide() {
        if (view == null) return;
        UI.removeCallbacks(TICK);
        if (view.getParent() instanceof FrameLayout) ((FrameLayout) view.getParent()).removeView(view);
        view = null;
    }

    private static LinearLayout cards; private static TextView hint; private static Button ok, touchJoin;
    private static float dp;

    private static void show(GameActivity a) {
        dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xE0101418);
        box.setClickable(true);   // touches stop here: the game's controls are held while it shows
        box.setFocusable(false);
        int pad = Math.round(24 * dp);
        box.setPadding(pad, pad, pad, pad);
        TextView title = text(a, "Controllers", 24, Color.WHITE); title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        hint = text(a, "", 15, 0xFFB8C4CC);
        box.addView(hint, lp(LinearLayout.LayoutParams.WRAP_CONTENT, 8));
        cards = new LinearLayout(a); cards.setOrientation(LinearLayout.HORIZONTAL); cards.setGravity(Gravity.CENTER);
        box.addView(cards, lp(LinearLayout.LayoutParams.MATCH_PARENT, 20));
        LinearLayout buttons = new LinearLayout(a); buttons.setGravity(Gravity.CENTER);
        Button cancel = button(a, "Cancel (B)", v -> Native.ctrlButton(0));
        touchJoin = button(a, "Join with on-screen controls", v -> Native.ctrlButton(2));
        ok = button(a, "OK (A)", v -> Native.ctrlButton(1));
        buttons.addView(cancel); buttons.addView(touchJoin); buttons.addView(ok);
        box.addView(buttons, lp(LinearLayout.LayoutParams.WRAP_CONTENT, 20));
        FrameLayout root = a.rootView();
        int at = root.indexOfChild(a.panelView());   // under the in-game panel, so Stop stays reachable
        root.addView(box, at < 0 ? root.getChildCount() : at, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view = box;
        TICK.run();
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (view == null) return;
            String[] lines = state().split("\n");
            if (lines.length == 0 || !lines[0].startsWith("open")) { UI.postDelayed(this, 100); return; }   // the host's close follows
            String[] h = lines[0].split("\t", -1);
            int min = num(h, 1), max = num(h, 2); boolean canEnd = num(h, 4) != 0, touch = num(h, 5) != 0;
            hint.setText((max == 1 ? "1 player" : (min > 1 && min != max ? min + "–" + max : String.valueOf(max)) + " players")
                    + ". Press L + R on each controller in turn: the first is player 1. A on a joined controller when everyone is in; B on player 1 cancels.");
            ok.setEnabled(canEnd); ok.setAlpha(canEnd ? 1f : 0.4f);
            touchJoin.setVisibility(touch && screen != null && !screen.gamepadConnected() ? View.VISIBLE : View.GONE);
            GameActivity a = screen;
            if (a != null && cards.getChildCount() != lines.length - 1) { cards.removeAllViews(); for (int i = 1; i < lines.length; i++) cards.addView(card(a)); }
            for (int i = 1; i < lines.length && i - 1 < cards.getChildCount(); i++) fill((LinearLayout) cards.getChildAt(i - 1), lines[i].split("\t", -1));
            UI.postDelayed(this, 100);
        }
    };

    private static LinearLayout card(GameActivity a) {
        LinearLayout c = new LinearLayout(a); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Math.round(12 * dp); c.setPadding(p, p, p, p);
        c.addView(text(a, "", 18, Color.WHITE));   // Player n
        c.addView(text(a, "", 14, Color.WHITE));   // the controller, or Press L + R
        c.addView(text(a, "", 12, 0xFFB8C4CC));    // the game's text
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Math.round(150 * dp), Math.round(130 * dp));
        l.setMargins(Math.round(6 * dp), 0, Math.round(6 * dp), 0);
        c.setLayoutParams(l);
        return c;
    }

    private static void fill(LinearLayout c, String[] f) {   // "<player>\t<device>\t<name>\t<colour>\t<text>"
        if (f.length < 5) return;
        boolean joined = !"-1".equals(f[1]);
        int col = 0xFF2E3A44;
        if (!"-".equals(f[3]) && f[3].length() == 8) {
            try { long v = Long.parseLong(f[3], 16); col = (int) (0xFF000000L | (v >>> 8)); } catch (NumberFormatException ignored) { }
        }
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(10 * dp);
        bg.setColor(joined ? col : 0x00000000); bg.setStroke(Math.round(2 * dp), joined ? col : 0xFF55626C);
        c.setBackground(bg);
        ((TextView) c.getChildAt(0)).setText("Player " + f[0]);
        ((TextView) c.getChildAt(1)).setText(joined ? f[2] : "Press L + R");
        ((TextView) c.getChildAt(1)).setAlpha(joined ? 1f : 0.6f);
        ((TextView) c.getChildAt(2)).setText(f[4]);
    }

    private static int num(String[] f, int i) { try { return i < f.length ? Integer.parseInt(f[i].trim()) : 0; } catch (NumberFormatException e) { return 0; } }

    private static TextView text(GameActivity a, String s, float sp, int color) {
        TextView t = new TextView(a); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER); return t;
    }

    private static Button button(GameActivity a, String s, View.OnClickListener l) {
        Button b = new Button(a); b.setText(s); b.setAllCaps(false); b.setFocusable(false); b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(Math.round(8 * dp), 0, Math.round(8 * dp), 0); b.setLayoutParams(p);
        return b;
    }

    private static LinearLayout.LayoutParams lp(int w, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Math.round(topDp * dp); return p;
    }
}
