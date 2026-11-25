package dev.nxnative.android;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import dev.peanuts.mods.ModsTab;

/**
 * PEANUTS: the in-game panel's Mods tab (SPEC-mod-menu.md Package C; dev.peanuts.mods.ModsTab) in upstream's SidePanel, whose tabs, close and look are
 * package-private. A Peanuts Native file in upstream's launcher package, as CheatsBridge.java. SidePanel's hooks:
 * <ul>
 * <li>open: {@link #opening} gives the tabs (Performance, Display, Controls, Mods, General; Mods only when the game has cheats or mods and neither Settings ›
 *     Controls › Mods tab nor the game's Show in the game hides it) and the tab to open on (the last one shown, by name, kept across runs);</li>
 * <li>build: {@link #build} builds the Mods tab when it is the one shown, and keeps which tab shows;</li>
 * <li>General: its cheat switches only when there is no Mods tab ({@link #shows});</li>
 * <li>padKey: Y and X on the Mods tab ({@link #padKey});</li>
 * <li>tabBar: with five tabs each as wide as its label, and the tab text fit after the bar's layout ({@link #afterLayout}).</li>
 * </ul>
 */
final class ModsPanel {
    private ModsPanel() {}

    static final String MODS = "Mods";
    private static final String[] BASE = {"Performance", "Display", "Controls", "General"}, WITH = {"Performance", "Display", "Controls", MODS, "General"};
    private static boolean shows, opened;

    /** SidePanel.open: the tabs (SidePanel.TAB_NAMES, TAB_GENERAL) and the tab to open on; tab: the one the panel has now */
    static int opening(SidePanel p, Activity a, String titleId, int tab) {
        String was = SidePanel.TAB_NAMES[Math.min(tab, SidePanel.TAB_NAMES.length - 1)];
        shows = ModsTab.wanted(a, titleId, a.getIntent().getStringExtra("game"), a.getIntent().getStringExtra("pack"));
        SidePanel.TAB_NAMES = shows ? WITH : BASE;
        SidePanel.TAB_GENERAL = SidePanel.TAB_NAMES.length - 1;
        String want = opened ? was : ModsTab.lastTab(a);   // the first opening of this run: the tab shown last, in this run or an earlier one
        opened = true;
        for (int i = 0; i < SidePanel.TAB_NAMES.length; i++) if (SidePanel.TAB_NAMES[i].equals(want)) return i;
        return Math.min(tab, SidePanel.TAB_NAMES.length - 1);
    }

    /** SidePanel.build: the Mods tab into box when it shows (true), and which tab shows (kept by name) */
    static boolean build(SidePanel p, Activity a, LinearLayout box, int tab) {
        String name = SidePanel.TAB_NAMES[Math.min(tab, SidePanel.TAB_NAMES.length - 1)];
        ModsTab.lastTab(a, name);
        if (!MODS.equals(name)) { ModsTab.left(); return false; }
        ModsTab.fill(a, box, new ModsTab.Host() {
            @Override public void close() { p.close(); }
            @Override public void style(View v) { ModsPanel.style(a, v); }
        });
        return true;
    }

    /** SidePanel.general: whether the cheat switches are in the Mods tab (General has them otherwise, as upstream's) */
    static boolean shows() { return shows; }

    /** SidePanel.padKey: Y (details) and X (All off) on the Mods tab, the Switch's Y and X by the game's A / B (confirm: the key that is A); true when taken */
    static boolean padKey(int k, View focus, int confirm) {
        if (!shows) return false;
        boolean nintendo = confirm == KeyEvent.KEYCODE_BUTTON_B;   // A on the right: the controller's labels are the Switch's, X on top and Y on the left
        int y = nintendo ? KeyEvent.KEYCODE_BUTTON_X : KeyEvent.KEYCODE_BUTTON_Y, x = nintendo ? KeyEvent.KEYCODE_BUTTON_Y : KeyEvent.KEYCODE_BUTTON_X;
        return ModsTab.padKey(k == y ? 'Y' : k == x ? 'X' : 0, focus, confirm, nintendo ? KeyEvent.KEYCODE_BUTTON_A : KeyEvent.KEYCODE_BUTTON_B);
    }

    /**
     * SidePanel.tabBar: r after v's next layout (the bar built again: its labels at 16 sp) and after any later one that changes v's width. Upstream posted
     * its tab text fit once, which at the panel's first opening ran before the bar had its width: four tabs fit at 16 sp, five (with Mods) do not. One
     * listener a bar (its tag), so building the bar again does not add another
     */
    static void afterLayout(View v, Runnable r) {
        if (v instanceof LinearLayout && SidePanel.TAB_NAMES.length > BASE.length) widths((LinearLayout) v);
        Object old = v.getTag();
        if (old instanceof View.OnLayoutChangeListener) v.removeOnLayoutChangeListener((View.OnLayoutChangeListener) old);
        View.OnLayoutChangeListener fit = new View.OnLayoutChangeListener() {
            boolean first = true;
            @Override public void onLayoutChange(View x, int l, int t, int rt, int b, int ol, int ot, int or, int ob) {
                if (!first && rt - l == or - ol) return;   // the fit's own text sizes lay the bar out again at the same width: nothing more to do
                first = false;
                x.post(r);   // after this layout pass: the fit sets text sizes, which asks for another
            }
        };
        v.setTag(fit);
        v.addOnLayoutChangeListener(fit);
        v.requestLayout();
    }

    /**
     * Five tabs: each as wide as its label in bold (as the canvas draws them) instead of equal widths. A fifth of the panel left "Performance" too wide even at
     * the fit's smallest size (10 sp) when it is the selected, bold one; by label, the fit stays near 15 sp. Four tabs keep upstream's equal widths
     */
    private static void widths(LinearLayout bar) {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        p.setTextSize(16 * bar.getResources().getDisplayMetrics().scaledDensity);
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (!(c instanceof TextView) || !(c.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
            if (lp.weight <= 0) continue;   // ✕: its own size
            TextView t = (TextView) c;
            lp.weight = p.measureText(t.getText().toString()) + t.getPaddingLeft() + t.getPaddingRight();
        }
    }

    /** The panel's look over views the Mods tab added after a build (SidePanel.look's text size and row height, then PanelPolish) */
    static void style(Context c, View v) {
        float dp = c.getResources().getDisplayMetrics().density;
        look(v, PanelLook.textScale(Prefs.panelText(c)), PanelLook.rowPx(Prefs.panelSize(c), dp));
        dev.peanuts.ui.PanelPolish.apply(v);
    }
    private static void look(View v, float text, int row) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            if (text != 1f) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * text);
            if (v instanceof Button || v instanceof CompoundButton) t.setMinHeight(row);
        } else if (v instanceof SeekBar) v.setMinimumHeight(row);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) look(((ViewGroup) v).getChildAt(i), text, row);
    }

    // ---- For the tab (CheatsBridge carries the rest) ----

    /**
     * The game started again, as Play started it: the same start (GameActivity's intent) in a moment, then this process ends as Stop ends it. The game's host
     * starts once per process, so a mod's files or code take effect only in a new one
     */
    static void restart(Activity a) {
        Intent again = new Intent(a.getIntent()).setClass(a, a.getClass()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(a, 4711, again, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager am = (AlarmManager) a.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 800, pi);
        Native.note("restarted by the user (the in-game panel's Mods tab)");
        ExitWatch.stopped();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
