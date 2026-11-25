package dev.peanuts.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nxnative.android.R;

/**
 * A game's page in areas: the game at the left (its cover, name, version,
 * publisher, play time and Play), its areas at the right (GameAreas: Graphics, Mods &amp; Cheats, Saves, Game options, Controls, Companion, Files,
 * Help, Advanced) as tiles that say what is set, and an area's rows in their place once it is opened. Upstream's GameSettings and MainActivity.gameTab
 * build every row as before (dev.nxnative.android.GamePagePort takes them off their tabs); this lays them out, gives each its Peanuts icon, and keeps
 * the rows' own listeners, dialogs and values.
 * <ul>
 * <li>The page opens on the tiles. A (or a tap) opens an area at the right, the game stays at the left; L1 / R1 step to the area before or after
 *     it; B goes back to the tiles, on the tile of the area it came from, and from the tiles to Home. Y plays, except in Mods &amp; Cheats, whose
 *     rows keep Y for their details. Settings › Users opens the page in Saves (GamePagePort.at).</li>
 * <li>Mods &amp; Cheats is the page of Mods &amp; Cheats (dev.peanuts.mods.CheatsPage) inside the area; Saves has the user cards of #422.</li>
 * <li>On a device with a second screen, that screen explains what the focus is on (BottomScreen.explain, as Settings does): the game and what is set
 *     in each area, an area and its rows with their values, Play, or a row with what it does (GameHelp), its value now and, for a switch, Off | On to
 *     flip it by touch. While a controller is connected, the keys for it are at the bottom.</li>
 * </ul>
 * A window 600 dp wide or more (the Thor's top screen, a tablet, a phone on its side) has the game in a panel at the left of the page; a narrower one
 * (a phone) has it at the top of the tiles (cover, name, facts, Play across the width) and each area as a page of its own, with Back to the tiles.
 */
public final class GamePage {
    private GamePage() {}

    public static final String SAVES_AREA = GameAreas.SAVES;
    static final int WIDE_DP = 600, PANEL_DP = 210, GAP_DP = 18;

    /** What GamePagePort hands over: the game, its harvested tabs, and what the page does */
    public static final class Facts {
        public String titleId, name, gameDir, packDir, missing, savesLine;
        public Runnable again, close, top, play;
        public boolean edition, built;
        public Boolean recommended;   // null: no recommendation for this device
        public LinearLayout performance, saves, storage;   // upstream's page with every section, its Saves and Storage tabs (when their area shows)
        public java.util.function.Consumer<Meta> meta;
    }

    /** The game's icon, version and publisher, once read (any may be null) */
    public interface Meta { void meta(Bitmap icon, String version, String publisher); }

    private static String area;        // the area that shows, null: the tiles
    private static String areaFor;     // the title its area is kept for
    private static String pending;     // the area the page opens at (Settings › Users: Saves)
    private static String cameFrom;    // Back from this area: its tile takes the focus
    private static boolean entering;   // another area (or the tiles) shows: it enters, and the focus starts at its top
    private static Activity owner;
    private static Facts facts;
    private static LinearLayout pane;  // the right side (the page's column)
    private static View play, paneLast, panelView, modsBody;
    private static Got got;
    private static Object explained;   // what the second screen explains now (a row, an area's key, "play", "game")
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final List<String> shown = new ArrayList<>();
    private static final Map<String, TextView> tileLines = new HashMap<>();   // the tiles' lines, for the ones that change while the page shows (Companion)

    // ---- Hooks ----

    /** Whether this window gets the page in areas (GamePagePort.build): every window, the game at the left (wide) or at the top of the tiles */
    public static boolean used(Context c) { return true; }

    /** The game in a panel at the left of the page (else at the top of the tiles, each area a page of its own) */
    static boolean wide(Context c) { return c.getResources().getConfiguration().screenWidthDp >= WIDE_DP; }

    /** The area the page shows for this title (or opens at next), null: the tiles */
    public static String area(String titleId) {
        if (pending != null) return pending;
        return titleId.equals(areaFor) ? area : null;
    }

    /** Whether the page needs upstream's Storage tab now (its rows are in Files and Help) */
    public static boolean storageWanted(String area) { return GameAreas.FILES.equals(area) || GameAreas.HELP.equals(area); }

    /** GameSettings.openOn (GamePagePort.at): the page opens at this area (null: the tiles) */
    public static void openAt(String key) { pending = key; }

    /** L1 / R1 (GamePagePort.step): the area before or after; false on the tiles; null when this page does not show */
    public static Boolean step(String titleId, int delta) {
        if (pane == null || !pane.isAttachedToWindow() || !titleId.equals(areaFor)) return null;
        if (area == null) return false;
        String next = GameAreas.step(shown, area, delta);
        if (next.equals(area)) return false;
        leaveArea(next);
        area = next;
        entering = true;
        return true;
    }

    /** MainActivity.onBackPressed (through SettingsPage.onBack): an area goes back to the tiles; true when it did */
    public static boolean onBack(Activity a) {
        if (a != owner || pane == null || !pane.isAttachedToWindow() || area == null || facts == null) return false;
        cameFrom = area;
        leaveArea(null);
        area = null;
        entering = true;
        facts.top.run();
        return true;
    }

    /** Home or Settings shows (BottomScreen.home, SettingsPage.lay): a game's page opens on its tiles again */
    public static void left() {
        leaveArea(null);
        area = null;
        areaFor = null;
        cameFrom = null;
        pane = null;
        play = null;
        paneLast = null;
    }

    private static void leaveArea(String next) {
        if (GameAreas.MODS.equals(area) && !GameAreas.MODS.equals(next)) dev.peanuts.mods.CheatsPage.leaveArea();
    }

    // ---- The page ----

    /** GamePagePort.build: the page into content (upstream's scrolling column), the game at the left of it */
    public static void lay(Activity a, LinearLayout content, Facts f) {
        owner = a;
        facts = f;
        if (!f.titleId.equals(areaFor)) { areaFor = f.titleId; area = null; cameFrom = null; }
        if (pending != null) { area = pending; pending = null; entering = true; }
        tileLines.clear();
        modsBody = null;
        panelView = null;
        explained = null;
        Got got = new Got(a, f);
        GamePage.got = got;
        shown.clear();
        for (GameAreas.Area ar : GameAreas.ALL) if (got.has(ar.key)) shown.add(ar.key);
        if (area != null && !shown.contains(area)) area = null;
        if (!GameAreas.MODS.equals(area)) dev.peanuts.mods.CheatsPage.leaveArea();
        content.removeAllViews();
        Pane p = new Pane(a);
        p.setOrientation(LinearLayout.VERTICAL);
        content.addView(p, PUi.fill());
        pane = p;
        paneLast = null;
        boolean wide = wide(a);
        play = null;
        if (wide) panel(a, content, f);
        if (area == null) {
            if (!wide) phoneTop(a, p, f);
            hub(a, p, got, f, wide);
        } else areaPage(a, p, got, f, area, wide);
        watchFocus(a);
        boolean enter = entering;
        entering = false;
        if (enter) Motion.enterPage(p, area == null ? 0 : 1);
        String from = cameFrom;
        cameFrom = null;
        if (!content.isInTouchMode() && (enter || from != null)) p.post(() -> {   // a controller: the tile it came back from, or the area's first row
            View t = from != null ? tileOf(p, from) : null;
            if (t == null && area == null) t = tileOf(p, shown.isEmpty() ? "" : shown.get(0));
            if (t == null) t = SettingsPage.firstClickable(p.getChildCount() > 1 ? p.getChildAt(1) : p);
            if (t == null) t = SettingsPage.firstClickable(p);
            if (t != null) t.requestFocus();
        });
        else if (!content.isInTouchMode() && area == null && a.getCurrentFocus() == null) p.post(() -> {   // the page opened: its first tile
            if (a.getCurrentFocus() != null && a.getCurrentFocus().isAttachedToWindow()) return;
            View t = tileOf(p, shown.isEmpty() ? "" : shown.get(0));
            if (t != null) t.requestFocus();
        });
        explainRows(got);
        p.post(() -> { if (pane == p && p.isAttachedToWindow() && (a.getCurrentFocus() == null || !isIn(a.getCurrentFocus(), p) && !isIn(a.getCurrentFocus(), panelView))) explainNothing(); });   // the focus on the page explains itself (watchFocus)
        dev.peanuts.ctl.Ctl.screen();
    }

    // ---- A phone: the game at the top of the tiles ----

    /** Back, then the cover with the name, the facts and the play time beside it, then Play across the width (the canvas' phone board) */
    private static void phoneTop(Activity a, LinearLayout p, Facts f) {
        LinearLayout bar = PUi.row(a);
        bar.setMinimumHeight(PUi.dp(a, 48));
        bar.addView(PUi.back(a, v -> backKey(a)));
        p.addView(bar);
        LinearLayout head = PUi.row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cover = coverView(a);
        head.addView(cover, new LinearLayout.LayoutParams(PUi.dp(a, 112), PUi.dp(a, 112)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(a, 14), 0, 0, 0);
        TextView name = PUi.text(a, f.name, 20, R.color.bn_text, 800);
        name.setMaxLines(3);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setAccessibilityHeading(true);
        col.addView(name);
        LinearLayout facts = PUi.row(a);
        TextView version = chip(a), publisher = chip(a);
        facts.addView(version, PUi.wrap());
        LinearLayout.LayoutParams pl = PUi.wrap();
        pl.setMarginStart(PUi.dp(a, 6));
        facts.addView(publisher, pl);
        facts.setVisibility(View.GONE);
        PUi.add(col, facts, 6);
        if (f.edition) {
            TextView ed = PUi.chip(a, "Switch 2 Edition");
            ed.setSingleLine(true);
            PUi.add(col, ed, 6, PUi.wrap());
        }
        CharSequence played = dev.peanuts.launch.PlayTime.line(a, f.titleId);
        if (played != null) PUi.add(col, PUi.small(a, played), 6);
        head.addView(col, PUi.weight());
        PUi.add(p, head, 8);
        if (f.play != null) {
            Button b = playButton(a, f);
            PUi.add(p, b, 14, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(a, 48)));
            play = b;
        } else if (f.missing != null) {
            PUi.add(p, PUi.small(a, f.missing), 12);
        }
        cover(a, f, cover, version, publisher, facts);
    }

    private static Button playButton(Activity a, Facts f) {
        Button b = PUi.button(a, "Play", PUi.Kind.PRIMARY, Feel.launch(v -> f.play.run()));
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(PUi.icon(a, R.drawable.ic_play, R.color.bn_on_banana), null, null, null);
        b.setCompoundDrawablePadding(PUi.dp(a, 4));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /** The cover's view: rounded, the tint until the icon is read */
    private static ImageView coverView(Context a) {
        ImageView cover = new ImageView(a);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 16), 0, 0));
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), PUi.dp(v.getContext(), 16)); }
        });
        cover.setClipToOutline(true);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return cover;
    }

    // ---- The game at the left ----

    /** The game's panel beside upstream's scrolling column, in its gutter (as Settings' sidebar): Back, cover, name, facts, play time and Play */
    private static void panel(Activity a, LinearLayout content, Facts f) {
        if (!(content.getParent() instanceof ScrollView) || !(content.getParent().getParent() instanceof FrameLayout)) return;
        ScrollView scroll = (ScrollView) content.getParent();
        FrameLayout frame = (FrameLayout) scroll.getParent();
        int at = frame.indexOfChild(scroll);
        View scrim = at + 1 < frame.getChildCount() ? frame.getChildAt(at + 1) : null;   // Ui.page's scrim under the status bar, above both
        Panel side = new Panel(a);
        side.setOrientation(LinearLayout.VERTICAL);
        panelView = side;
        ImageView back = PUi.back(a, v -> backKey(a));
        side.addView(back, new LinearLayout.LayoutParams(PUi.dp(a, 40), PUi.dp(a, 40)));
        ImageView cover = coverView(a);
        side.cover = cover;
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(PUi.dp(a, 140), PUi.dp(a, 140));
        cp.topMargin = PUi.dp(a, 10);
        side.addView(cover, cp);
        TextView name = PUi.text(a, f.name, 18, R.color.bn_text, 800);
        name.setMaxLines(3);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setAccessibilityHeading(true);
        PUi.add(side, name, 10);
        LinearLayout facts = PUi.row(a);
        TextView version = chip(a), publisher = chip(a);
        facts.addView(version, PUi.wrap());
        LinearLayout.LayoutParams pl = PUi.wrap();
        pl.setMarginStart(PUi.dp(a, 6));
        facts.addView(publisher, pl);
        PUi.add(side, facts, 6);
        facts.setVisibility(View.GONE);
        if (f.edition) {
            TextView ed = PUi.chip(a, "Switch 2 Edition");
            ed.setSingleLine(true);
            PUi.add(side, ed, 6, PUi.wrap());
        }
        CharSequence played = dev.peanuts.launch.PlayTime.line(a, f.titleId);
        if (played != null) {
            TextView pt = PUi.small(a, played);
            pt.setMaxLines(2);
            PUi.add(side, pt, 6);
        }
        side.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
        if (f.play != null) {
            Button b = playButton(a, f);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(a, 48));
            bp.topMargin = PUi.dp(a, 12);
            side.addView(b, bp);
            play = b;
        } else if (f.missing != null) {
            TextView m = PUi.small(a, f.missing);
            m.setMaxLines(6);
            PUi.add(side, m, 12);
        }
        cover(a, f, cover, version, publisher, facts);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(PUi.dp(a, PANEL_DP), ViewGroup.LayoutParams.MATCH_PARENT);
        frame.addView(side, at + 1, sp);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {   // Ui.page's gutter and width (its game page's: 1040 dp), the game in its left part
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int w = a.getResources().getDisplayMetrics().widthPixels;
            int gutter = PUi.dp(a, a.getResources().getConfiguration().screenWidthDp < 360 ? 16 : 20);
            int edge = Math.max(gutter, (w - i.left - i.right - PUi.dp(a, 1040)) / 2);
            v.setPadding(edge + i.left + PUi.dp(a, PANEL_DP + GAP_DP), i.top + PUi.dp(a, 8), edge + i.right, i.bottom + PUi.dp(a, 24));
            side.setPadding(0, i.top + PUi.dp(a, 8), 0, i.bottom + PUi.dp(a, 16));
            if (sp.leftMargin != edge + i.left) { sp.leftMargin = edge + i.left; side.setLayoutParams(sp); }
            if (scrim != null && scrim.getLayoutParams() != null && scrim.getLayoutParams().height != i.top) { scrim.getLayoutParams().height = i.top; scrim.requestLayout(); }
            return in;
        });
        frame.requestApplyInsets();
    }

    private static TextView chip(Context c) {
        TextView t = PUi.chip(c, "");
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setVisibility(View.GONE);
        return t;
    }

    /** The cover: the library's copy of the game's icon (files/icons), else the one in its files; the version and publisher once read */
    private static void cover(Activity a, Facts f, ImageView cover, TextView version, TextView publisher, View facts) {
        File kept = new File(dev.peanuts.PeanutsPrefs.iconsDir(a), f.titleId.toUpperCase(java.util.Locale.ROOT) + ".png");
        new Thread(() -> {
            Bitmap b = kept.isFile() ? BitmapFactory.decodeFile(kept.getPath()) : null;
            if (b != null) a.runOnUiThread(() -> { if (cover.getDrawable() == null) cover.setImageBitmap(b); });
        }, "peanuts-game-cover").start();
        if (f.meta == null) return;
        f.meta.accept((icon, v, p) -> a.runOnUiThread(() -> {
            if (icon != null && cover.getDrawable() == null) cover.setImageBitmap(icon);
            boolean any = false;
            if (v != null && !v.isEmpty()) { version.setText(v); version.setVisibility(View.VISIBLE); any = true; }
            if (p != null && !p.isEmpty()) { publisher.setText(p); publisher.setVisibility(View.VISIBLE); any = true; }
            facts.setVisibility(any ? View.VISIBLE : View.GONE);
        }));
    }

    @SuppressWarnings("deprecation")   // MainActivity's own Back (onBackPressed): an area to the tiles, the tiles to Home
    private static void backKey(Activity a) { a.onBackPressed(); }

    /**
     * The game's panel: its cover takes the height Back, the name, the facts and Play leave (square, at most as wide as the panel). Right goes into the
     * page (where the focus was last, else its first item); up and down stay in the panel. Y plays
     */
    private static final class Panel extends LinearLayout {
        ImageView cover;
        Panel(Context c) { super(c); }

        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, h);
            if (cover == null || MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) return;
            int others = getPaddingTop() + getPaddingBottom();
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c == cover || c.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) c.getLayoutParams();
                others += lp.weight > 0 ? 0 : c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            }
            LayoutParams cp = (LayoutParams) cover.getLayoutParams();
            others += cp.topMargin + cp.bottomMargin;
            int side = Math.max(PUi.dp(getContext(), 56), Math.min(MeasureSpec.getSize(w) - getPaddingLeft() - getPaddingRight(), MeasureSpec.getSize(h) - others));
            if (cp.width != side) { cp.width = side; cp.height = side; super.onMeasure(w, h); }
        }

        @Override public View focusSearch(View focused, int dir) {
            View next = super.focusSearch(focused, dir);
            if (focused == null || !isIn(focused, this)) return next;
            if (dir == FOCUS_RIGHT) { View t = paneTarget(); if (t != null) return t; }
            if ((dir == FOCUS_UP || dir == FOCUS_DOWN) && next != null && !isIn(next, this)) return focused;
            return next;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) { return playKey(e) || super.dispatchKeyEvent(e); }
    }

    /** The page's right side: up and down stay in it, left from its left edge goes to Play (else Back). Y plays (not in Mods &amp; Cheats) */
    private static final class Pane extends LinearLayout {
        Pane(Context c) { super(c); }

        @Override public View focusSearch(View focused, int dir) {
            View next = super.focusSearch(focused, dir);
            if (focused == null || !isIn(focused, this)) return next;
            if (next != null && isIn(next, this)) return next;
            if (dir == FOCUS_LEFT) {
                if (play != null && play.isShown()) return play;
                return next;
            }
            if (dir == FOCUS_UP || dir == FOCUS_DOWN) {
                View in = FocusFinder.getInstance().findNextFocus(this, focused, dir);
                return in != null ? in : focused;
            }
            return next;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) { return playKey(e) || super.dispatchKeyEvent(e); }
    }

    /** Y plays (its press and its release are taken; Play runs on the release); in Mods &amp; Cheats Y stays its rows' (their details) */
    private static boolean playKey(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_BUTTON_Y || GameAreas.MODS.equals(area) || play == null || !play.isShown()) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && e.getRepeatCount() == 0) play.performClick();
        return true;
    }

    private static View paneTarget() {
        LinearLayout p = pane;
        if (p == null || !p.isShown()) return null;
        if (paneLast != null && paneLast.isAttachedToWindow() && paneLast.isShown() && isIn(paneLast, p)) return paneLast;
        View t = area == null ? tileOf(p, shown.isEmpty() ? "" : shown.get(0)) : null;
        if (t == null) t = SettingsPage.firstClickable(p.getChildCount() > 1 ? p.getChildAt(1) : p);
        return t != null ? t : SettingsPage.firstClickable(p);
    }

    private static Activity focusWatched;

    private static void watchFocus(Activity a) {
        if (focusWatched == a) return;
        focusWatched = a;
        a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            LinearLayout p = pane;
            if (now != null && p != null && isIn(now, p)) paneLast = now;
            if (now != null) explain(now);
            // the row the focus was on went (an area's rows drawn again, the Companion's once its catalog is read): the page's scroll took it, the area gets it back
            if (now instanceof ScrollView && p != null && p.isAttachedToWindow() && p.getParent() != null && p.getParent().getParent() == now && !now.isInTouchMode())
                p.post(() -> { View t = paneTarget(); if (t != null && t.isShown()) t.requestFocus(); });
        });
    }

    static boolean isIn(View v, View group) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) if (x == group) return true;
        return false;
    }

    /** A controller is connected (the face buttons it names; touch shows none) */
    static boolean controller() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && (d.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return true;
        }
        return false;
    }

    // ---- The tiles ----

    private static void hub(Activity a, LinearLayout p, Got got, Facts f, boolean wide) {
        if (got.shader != null) PUi.add(p, detach(got.shader), wide ? 0 : 14);
        int right = Math.min(a.getResources().getConfiguration().screenWidthDp, 1040) - 40 - (wide ? PANEL_DP + GAP_DP : 0);   // the page's right side, in dp
        int cols = right >= 700 ? 3 : 2;
        boolean upright = !wide;   // a phone: icon over name over two lines
        LinearLayout row = null;
        int n = 0;
        for (String key : shown) {
            if (key.equals(GameAreas.ADVANCED)) continue;
            if (n % cols == 0) { row = PUi.row(a); row.setBaselineAligned(false); PUi.add(p, row, n == 0 ? (got.shader != null ? 12 : wide ? 4 : 16) : 8); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (n % cols > 0) lp.setMarginStart(PUi.dp(a, 8));
            row.addView(tile(a, GameAreas.area(key), got.line(key), false, upright), lp);
            n++;
        }
        for (int k = n % cols; row != null && k > 0 && k < cols; k++) {   // the last row's empty places keep the tiles' width
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.setMarginStart(PUi.dp(a, 8));
            row.addView(new android.widget.Space(a), lp);
        }
        if (shown.contains(GameAreas.ADVANCED)) PUi.add(p, tile(a, GameAreas.area(GameAreas.ADVANCED), got.line(GameAreas.ADVANCED), true, false), n == 0 ? 4 : 8);
    }

    /** An area's tile: its icon, its name and what is set in it; A or a tap opens the area */
    private static View tile(Activity a, GameAreas.Area ar, CharSequence line, boolean quiet, boolean upright) {
        LinearLayout t = upright ? new LinearLayout(a) : PUi.row(a);
        if (upright) t.setOrientation(LinearLayout.VERTICAL);
        t.setTag(new Tile(ar.key));
        t.setMinimumHeight(PUi.dp(a, upright ? 104 : quiet ? 48 : 60));
        int pad = PUi.dp(a, upright ? 12 : 10);
        t.setPadding(pad, PUi.dp(a, upright ? 12 : 8), pad, PUi.dp(a, upright ? 12 : 8));
        t.setBackground(PUi.ripple(a, PUi.round(quiet ? Color.TRANSPARENT : PUi.col(a, R.color.bn_card), PUi.dp(a, 16), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1))));
        TextView ic = rowTile(a, ar.icon);
        t.addView(ic, new LinearLayout.LayoutParams(PUi.dp(a, 36), PUi.dp(a, 36)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(upright ? 0 : PUi.dp(a, 12), upright ? PUi.dp(a, 8) : 0, PUi.dp(a, 4), 0);
        TextView name = PUi.text(a, ar.label, 15, R.color.bn_text, 600);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(name);
        TextView l = PUi.text(a, line == null ? "" : line, 13, R.color.bn_text_2, 400);
        if (upright) l.setMaxLines(2); else l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setVisibility(line == null || line.length() == 0 ? View.GONE : View.VISIBLE);
        col.addView(l);
        tileLines.put(ar.key, l);
        t.addView(col, upright ? PUi.fill() : PUi.weight());
        t.setFocusable(true);
        t.setClickable(true);
        t.setContentDescription(ar.label + (line == null || line.length() == 0 ? "" : ". " + line));
        t.setOnClickListener(Feel.row(v -> open(ar.key)));
        return t;
    }

    /** The tile's tag: its area */
    private static final class Tile {
        final String key;
        Tile(String key) { this.key = key; }
    }

    private static View tileOf(View v, String key) {
        if (v.getTag() instanceof Tile && key.equals(((Tile) v.getTag()).key)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View t = tileOf(((ViewGroup) v).getChildAt(i), key); if (t != null) return t; }
        return null;
    }

    /** A tile of 36 dp with a Peanuts icon (as a settings row's) */
    private static TextView rowTile(Context c, String icon) {
        TextView t = PUi.text(c, "", 17, R.color.bn_text, 400);
        t.setGravity(Gravity.CENTER);
        t.setBackground(PUi.round(PUi.col(c, R.color.bn_tint), PUi.dp(c, 11), 0, 0));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(c, 36), PUi.dp(c, 36)));
        Icons.tile(t, icon);
        return t;
    }

    private static void open(String key) {
        Facts f = facts;
        if (f == null || key.equals(area)) return;
        leaveArea(key);
        area = key;
        entering = true;
        f.top.run();
    }

    // ---- An area ----

    private static void areaPage(Activity a, LinearLayout p, Got got, Facts f, String key, boolean wide) {
        GameAreas.Area ar = GameAreas.area(key);
        LinearLayout h = PUi.row(a);
        h.setMinimumHeight(PUi.dp(a, wide ? 40 : 56));
        if (!wide) h.addView(PUi.back(a, v -> backKey(a)));   // a phone: the area is a page of its own, Back to the tiles
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(wide ? 0 : PUi.dp(a, 14), 0, PUi.dp(a, 8), 0);
        TextView title = PUi.text(a, ar.label, wide ? 20 : 22, R.color.bn_text, 800);
        title.setAccessibilityHeading(true);
        t.addView(title);
        if (!wide) {
            TextView game = PUi.text(a, f.name, 12, R.color.bn_text_2, 400);
            game.setSingleLine(true);
            game.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(game);
        }
        h.addView(t, PUi.weight());
        p.addView(h);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        PUi.add(p, body, 4);
        switch (key) {
            case GameAreas.MODS:
                modsBody = body;   // its rows explain themselves on the second screen (CheatsPage)
                dev.peanuts.mods.CheatsPage.area(a, body, f.titleId, f.name, f.gameDir, f.packDir, f.again);
                return;
            case GameAreas.COMPANION:
                if (got.companion != null) {   // in a card, as it sat in the Game tab's
                    LinearLayout card = PUi.add(body, PUi.listCard(a), 4);
                    card.addView(detach(got.companion));
                }
                return;
            case GameAreas.SAVES:
                for (View v : got.saves) PUi.add(body, detach(v), body.getChildCount() == 0 ? 4 : 8);
                return;
            case GameAreas.GRAPHICS:
                if (got.recommend != null) PUi.add(body, detach(got.recommend), 4);
                break;
            case GameAreas.FILES:
                for (View v : got.gameFiles) PUi.add(body, detach(v), body.getChildCount() == 0 ? 4 : 6);
                break;
            default:
                break;
        }
        groups(a, body, got, key);
        if (key.equals(GameAreas.HELP) && got.details != null) PUi.add(body, detach(got.details), 12);
        if (key.equals(GameAreas.ADVANCED) && got.reset != null) PUi.add(body, detach(got.reset), 20, PUi.wrap());
        if (key.equals(GameAreas.FILES) && got.remove != null) PUi.add(body, detach(got.remove), 20, PUi.wrap());
    }

    /** The area's groups in their order, each its label (not when it only repeats the area's name) over a card of its rows, and its line under it */
    private static void groups(Activity a, LinearLayout body, Got got, String key) {
        Map<String, List<View>> gs = got.rows.get(key);
        if (gs == null) return;
        List<String> order = GameAreas.groups(key);
        int filled = 0;
        for (String g : order) if (gs.containsKey(g) && !gs.get(g).isEmpty()) filled++;
        String label = GameAreas.area(key).label;
        for (String g : order) {
            List<View> rows = gs.get(g);
            if (rows == null || rows.isEmpty()) continue;
            boolean named = filled > 1 || !g.equals(label) && !g.equals(GameAreas.MORE);
            if (named) PUi.add(body, PUi.section(a, g), body.getChildCount() == 0 ? 6 : 18);
            LinearLayout card = PUi.add(body, PUi.listCard(a), named ? 6 : (body.getChildCount() == 0 ? 4 : 12));
            for (View r : inOrder(got, rows)) card.addView(detach(r));
            String info = got.info.get(g);
            if (info != null) {
                TextView t = PUi.add(body, PUi.small(a, info), 6);
                t.setTextSize(12);
                t.setPadding(PUi.dp(a, 4), 0, PUi.dp(a, 4), 0);
            }
        }
    }

    /**
     * A group's rows in the table's order (GameAreas: Build again before Prepare again, whatever order the tab built them in), each with the views that
     * followed it in its card; a row the table does not know keeps its place after the known ones (a stable sort)
     */
    private static List<View> inOrder(Got got, List<View> rows) {
        List<List<View>> units = new ArrayList<>();
        for (View v : rows) {
            if (SettingsPage.title(v) != null || units.isEmpty()) units.add(new ArrayList<>());
            units.get(units.size() - 1).add(v);
        }
        units.sort((x, y) -> Integer.compare(got.orderOf.getOrDefault(x.get(0), Integer.MAX_VALUE), got.orderOf.getOrDefault(y.get(0), Integer.MAX_VALUE)));
        List<View> out = new ArrayList<>();
        for (List<View> u : units) out.addAll(u);
        return out;
    }

    private static <V extends View> V detach(V v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        return v;
    }

    // ---- Upstream's rows, taken off its tabs ----

    /** What upstream built: each row in its area and group, with the views that followed it in its card, and the cards and lines of their own */
    private static final class Got {
        final Map<String, Map<String, List<View>>> rows = new HashMap<>();
        final Map<String, String> values = new HashMap<>();   // a row's value as it is now, by its title (the tiles' lines)
        final Map<String, Boolean> switches = new HashMap<>();
        final Map<String, String> info = new HashMap<>();     // a group's line under its card (upstream's section line)
        final Map<View, Integer> orderOf = new HashMap<>();   // a row's place in its group (GameAreas), for the order of the page
        final List<View> saves = new ArrayList<>(), gameFiles = new ArrayList<>();
        final Map<View, String[]> placeOf = new HashMap<>();   // a row: its title and its area (the second screen's explanation)
        View recommend, shader, reset, details, remove, companion;
        final Activity a;
        final Facts f;

        Got(Activity a, Facts f) {
            this.a = a;
            this.f = f;
            if (f.performance != null) walk(f.performance);
            if (f.saves != null) for (int i = 0; i < f.saves.getChildCount(); i++) saves.add(f.saves.getChildAt(i));
            if (f.saves != null) for (View v : saves) icons(v, GameAreas.SAVES);
            if (f.storage != null) storage(f.storage);
        }

        boolean has(String key) {
            switch (key) {
                case GameAreas.MODS: return f.missing == null;   // its row was on the Game tab: a game with a profile (Mods & Cheats, Part D)
                case GameAreas.SAVES: case GameAreas.FILES: case GameAreas.HELP: return true;
                case GameAreas.COMPANION: return companion != null;
                case GameAreas.GRAPHICS: return recommend != null || rows.containsKey(key);
                default: return rows.containsKey(key);
            }
        }

        /** One of upstream's settings tabs, built in its narrow column: its headings, cards, rows and lines */
        private void walk(LinearLayout col) {
            String heading = null;
            boolean afterCard = false;
            List<View> kids = children(col);
            for (View v : kids) {
                if (v instanceof TextView && v.isAccessibilityHeading()) { heading = text(v); afterCard = false; continue; }
                TextView head = headOf(v);
                if (head != null) { heading = text(head); afterCard = false; continue; }   // Advanced's label with Show / Hide
                if (v instanceof Button) {
                    if (reset == null && "Reset to defaults".contentEquals(((Button) v).getText())) reset = v;
                    continue;
                }
                if (v instanceof TextView) {
                    if (afterCard && heading != null && !info.containsKey(heading)) info.put(heading, text(v));
                    continue;
                }
                if (!(v instanceof LinearLayout) || ((LinearLayout) v).getOrientation() != LinearLayout.VERTICAL) continue;
                LinearLayout card = (LinearLayout) v;
                String first = card.getChildCount() > 0 && card.getChildAt(0) instanceof TextView ? text(card.getChildAt(0)) : null;
                if ("Recommended for this phone".equals(first)) { if (recommend == null) recommend = card; continue; }
                if ("Shader update available".equals(first)) { if (shader == null) shader = card; continue; }
                boolean any = false;
                View last = null;
                for (View k : children(card)) {
                    String t = SettingsPage.title(k);
                    if (t != null) { last = add(k, t, heading) ? k : null; any = true; continue; }
                    if (k instanceof LinearLayout && ((LinearLayout) k).getOrientation() == LinearLayout.VERTICAL) {   // a section of Peanuts' own in the card
                        String label = ((LinearLayout) k).getChildCount() > 0 && ((LinearLayout) k).getChildAt(0) instanceof TextView ? text(((LinearLayout) k).getChildAt(0)) : null;
                        if ("Companion".equalsIgnoreCase(label)) { if (companion == null) { companion = companionBody((LinearLayout) k); } any = true; continue; }
                        if ("Mods & Cheats".equalsIgnoreCase(label) || "Mods".equalsIgnoreCase(label)) { any = true; continue; }   // its area has the page
                        if (last != null) after(last, k);
                        continue;
                    }
                    if (last != null) after(last, k);
                }
                afterCard = any;
            }
        }

        /** The Companion section without its own label (the area's title says it), its rows getting their icons as it draws them again */
        private View companionBody(LinearLayout box) {
            box.removeViewAt(0);
            icons(box, GameAreas.COMPANION);
            box.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { icons(child, GameAreas.COMPANION); }
                @Override public void onChildViewRemoved(View parent, View child) {}
            });
            for (int i = 0; i < box.getChildCount(); i++) if (box.getChildAt(i) instanceof ViewGroup) {
                ViewGroup body = (ViewGroup) box.getChildAt(i);
                body.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override public void onChildViewAdded(View parent, View child) { icons(child, GameAreas.COMPANION); }
                    @Override public void onChildViewRemoved(View parent, View child) {}
                });
            }
            return box;
        }

        /** Upstream's Storage tab: Game files (its card filled off the main thread stays one view), Pack and More's rows by their titles, Details and Remove */
        private void storage(LinearLayout col) {
            String heading = null;
            for (View v : children(col)) {
                if (v instanceof TextView && v.isAccessibilityHeading()) {
                    heading = text(v);
                    if ("Game files".equalsIgnoreCase(heading)) gameFiles.add(v);
                    continue;
                }
                if (v instanceof Button) { remove = v; continue; }
                if ("Game files".equalsIgnoreCase(heading) && v instanceof LinearLayout && gameFiles.size() == 1) { gameFiles.add(v); filesIcons((LinearLayout) v); continue; }
                if (v.getClass().getSimpleName().equals("Details")) { details = v; continue; }
                if (!(v instanceof LinearLayout) || ((LinearLayout) v).getOrientation() != LinearLayout.VERTICAL) continue;
                View last = null;
                for (View k : children((LinearLayout) v)) {
                    String t = SettingsPage.title(k);
                    if (t != null) { last = add(k, t, heading) ? k : null; continue; }
                    if (last != null) after(last, k);
                }
            }
        }

        /** Game files' rows (read off the main thread, so they come later): base, update, DLC, a file that cannot be read, by their emoji */
        private void filesIcons(LinearLayout card) {
            ViewGroup.OnHierarchyChangeListener l = new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    String e = tileText(child), icon = e == null ? null : e.startsWith("🎮") ? "game" : e.startsWith("⬆") ? "update" : e.startsWith("🧩") ? "dlc" : e.startsWith("⚠") ? "warning" : null;
                    if (icon != null) Icons.tile((TextView) ((LinearLayout) child).getChildAt(0), icon);
                }
                @Override public void onChildViewRemoved(View parent, View child) {}
            };
            card.setOnHierarchyChangeListener(l);
            for (View k : children(card)) l.onChildViewAdded(card, k);
        }

        /** The rows of an area as the second screen lists them: each title and its value or switch as it is now */
        List<CharSequence[]> listed(String key) {
            List<CharSequence[]> out = new ArrayList<>();
            for (Map.Entry<View, String[]> e : placeOf.entrySet()) if (e.getValue()[1].equals(key) && e.getKey().getVisibility() == View.VISIBLE) out.add(new CharSequence[] {e.getValue()[0], shown(e.getKey())});
            List<String> groups = GameAreas.groups(key);   // in the page's order: its groups, then each group's rows
            out.sort((x, y) -> {
                GameAreas.Place px = GameAreas.place(String.valueOf(x[0]), null, null), py = GameAreas.place(String.valueOf(y[0]), null, null);
                int g = Integer.compare(groups.indexOf(px.group), groups.indexOf(py.group));
                return g != 0 ? g : Integer.compare(px.order, py.order);
            });
            return out;
        }

        /** A row into its area and group, its icon on its tile; false when a row of that title is placed already (the other tab built it too) */
        private boolean add(View row, String title, String heading) {
            if (values.containsKey(title)) return false;
            GameAreas.Place p = GameAreas.place(title, heading, tileText(row));
            rows.computeIfAbsent(p.area, k -> new LinkedHashMap<>()).computeIfAbsent(p.group, k -> new ArrayList<>()).add(row);
            orderOf.put(row, p.order);
            placeOf.put(row, new String[] {title, p.area});
            TextView v = value(row);
            android.widget.Switch sw = toggle(row);
            values.put(title, v != null && v.getVisibility() == View.VISIBLE ? String.valueOf(v.getText()) : "");
            if (sw != null) switches.put(title, sw.isChecked());
            String icon = GameAreas.icon(p, title, v == null ? null : v.getText(), sw == null ? null : sw.isChecked());
            if (icon != null && ((LinearLayout) row).getChildAt(0) instanceof TextView) Icons.tile((TextView) ((LinearLayout) row).getChildAt(0), icon);
            return true;
        }

        private final Map<View, List<View>> afters = new HashMap<>();

        /** A view that followed a row in its card (a line under it) moves with it */
        private void after(View row, View v) {
            for (Map.Entry<String, Map<String, List<View>>> a : rows.entrySet())
                for (List<View> l : a.getValue().values()) {
                    int i = l.indexOf(row);
                    if (i < 0) continue;
                    int at = i + 1;
                    List<View> more = afters.computeIfAbsent(row, k -> new ArrayList<>());
                    at += more.size();
                    more.add(v);
                    l.add(at, v);
                    return;
                }
        }

        /** The icons of the rows inside v (a section of Peanuts' own, the Saves tab's card), by their titles */
        private void icons(View v, String area) {
            String t = SettingsPage.title(v);
            if (t != null) {
                GameAreas.Place p = GameAreas.place(t, null, tileText(v));
                placeOf.put(v, new String[] {t, area});
                TextView val = value(v);
                android.widget.Switch sw = toggle(v);
                String icon = GameAreas.icon(p, t, val == null ? null : val.getText(), sw == null ? null : sw.isChecked());
                if (icon != null && ((LinearLayout) v).getChildAt(0) instanceof TextView) Icons.tile((TextView) ((LinearLayout) v).getChildAt(0), icon);
                if (area.equals(GameAreas.COMPANION) && t.equals("Companion")) {   // the catalog read: the tile says it
                    values.put("Companion", val == null ? "" : String.valueOf(val.getText()));
                    TextView tl = tileLines.get(GameAreas.COMPANION);
                    if (tl != null) { CharSequence l = line(GameAreas.COMPANION); tl.setText(l); tl.setVisibility(l == null || l.length() == 0 ? View.GONE : View.VISIBLE); }
                }
                return;
            }
            if (v instanceof ViewGroup && !(v instanceof HorizontalScrollView)) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) icons(((ViewGroup) v).getChildAt(i), area);
        }

        /** A tile's line: what is set in the area, short */
        CharSequence line(String key) {
            switch (key) {
                case GameAreas.GRAPHICS:
                    return GameAreas.line(Boolean.TRUE.equals(f.recommended) ? "Recommended" : null, sv("Resolution"), sv("Upscaler"), sv("Frame rate cap"));
                case GameAreas.MODS:
                    return dev.peanuts.mods.CheatsPage.tileLine(a, f.titleId, f.name, f.gameDir, f.packDir);
                case GameAreas.SAVES:
                    return f.savesLine != null ? f.savesLine : "Back up, restore and import";
                case GameAreas.OPTIONS: {
                    int on = 0;
                    Map<String, List<View>> gs = rows.get(GameAreas.OPTIONS);
                    if (gs != null) for (List<View> l : gs.values()) for (View r : l) { String t = SettingsPage.title(r); if (t != null && Boolean.TRUE.equals(switches.get(t))) on++; }
                    return GameAreas.line(sv("Game language"), on == 0 ? null : on + (on == 1 ? " switch on" : " switches on"));
                }
                case GameAreas.CONTROLS: {
                    String map = values.get("Button mapping");
                    return GameAreas.line(switches.containsKey("Rumble") ? (switches.get("Rumble") ? "Rumble on" : "Rumble off") : null,
                            map == null ? null : map.startsWith("As in Settings") ? "buttons as in Settings" : "own buttons");
                }
                case GameAreas.COMPANION:
                    return sv("Companion");
                case GameAreas.FILES:
                    return f.built ? "Built" : "Not built yet";
                case GameAreas.HELP:
                    return "Share log, title ID and folders";
                case GameAreas.ADVANCED: {   // Advanced switches' line says how many are set ("3 set"; none: "Search every switch; ...")
                    String v = values.getOrDefault("Advanced switches", "");
                    int i = 0;
                    while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
                    String n = v.substring(0, i);
                    return !n.isEmpty() && v.startsWith(n + " set") ? n + (n.equals("1") ? " switch set" : " switches set") + ". For testing" : "For testing. Every switch at Auto";
                }
                default:
                    return null;
            }
        }

        private String sv(String title) { return GameAreas.shortValue(values.get(title)); }
    }

    // ---- The second screen: what the focus is on (BottomScreen.explain) ----

    private static final String[] PLAY = {"Y", "Play"};

    /** What the focus is on, explained; false when it is on nothing of the page (or on Mods &amp; Cheats' rows, which explain themselves) */
    private static boolean explain(View now) {
        Activity a = owner;
        Got g = got;
        if (a == null || g == null || now == null || pane == null || !pane.isAttachedToWindow()) return false;
        if (!isIn(now, pane) && (panelView == null || !isIn(now, panelView))) return false;
        if (modsBody != null && isIn(now, modsBody)) {   // Mods & Cheats' rows explain themselves (a focus listener of their own); its other views: the area
            for (View v = now; v != null && v != modsBody; v = v.getParent() instanceof View ? (View) v.getParent() : null) if (v.getOnFocusChangeListener() != null) return true;
            explainArea(GameAreas.MODS);
            return true;
        }
        if (now == play) { explainPlay(); return true; }
        for (View v = now; v != null && v != pane; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v.getTag() instanceof Tile) { explainArea(((Tile) v.getTag()).key); return true; }
            String[] place = g.placeOf.get(v);
            if (place != null) { explainRow(v, place[0], place[1]); return true; }
        }
        explainNothing();
        return true;
    }

    /** Nothing in particular: in an area, the area; on the tiles, the game */
    private static void explainNothing() {
        if (area != null) explainArea(area); else explainGame();
    }

    private static BottomScreen.Explain start(Object key, String icon, String title, String place, String text) {
        BottomScreen.Explain e = new BottomScreen.Explain();
        e.key = key;
        e.icon = icon;
        e.title = title;
        e.place = place;
        e.text = text;
        return e;
    }

    private static List<String[]> keys(String[]... k) {
        List<String[]> out = new ArrayList<>();
        for (String[] x : k) if (x != null) out.add(x);
        return out;
    }

    private static String[] playKeyIf() { return play != null && !GameAreas.MODS.equals(area) ? PLAY : null; }

    /** The game: what is set in each of its areas */
    private static void explainGame() {
        Facts f = facts;
        Got g = got;
        if (f == null || g == null) return;
        CharSequence played = dev.peanuts.launch.PlayTime.line(owner, f.titleId);
        BottomScreen.Explain e = start("game:" + f.titleId, "game", f.name, f.edition ? "Switch 2 Edition" : "", f.missing != null ? f.missing : null);
        e.rows = new ArrayList<>();
        if (played != null) e.rows.add(new CharSequence[] {"Played", played});   // a row: its bar between the time and the date is a span (PUi.joined)
        for (String k : shown)   // what a player looks at before a start (the others are a tile away): fits the screen
            if (k.equals(GameAreas.GRAPHICS) || k.equals(GameAreas.MODS) || k.equals(GameAreas.SAVES) || k.equals(GameAreas.OPTIONS) || k.equals(GameAreas.CONTROLS))
                e.rows.add(new CharSequence[] {GameAreas.area(k).label, g.line(k)});
        e.keys = keys(new String[] {"A", "Open"}, playKeyIf(), new String[] {"B", "Home"});
        show(e);
    }

    /** An area: its line, and its rows with their values (an area whose rows are not built yet: what its tile says) */
    private static void explainArea(String key) {
        Facts f = facts;
        Got g = got;
        GameAreas.Area ar = GameAreas.area(key);
        if (f == null || g == null || ar == null) return;
        BottomScreen.Explain e = start("area:" + key + (key.equals(area) ? ":in" : ""), ar.icon, ar.label, f.name, ar.line);
        e.rows = g.listed(key);
        if (e.rows.isEmpty()) e.rows.add(new CharSequence[] {ar.label, g.line(key)});
        e.keys = key.equals(area) ? keys(playKeyIf(), new String[] {"B", "Back"}, new String[] {"L", ""}, new String[] {"R", "Areas"})
                : keys(new String[] {"A", "Open"}, playKeyIf(), new String[] {"B", "Home"});
        show(e);
    }

    private static void explainPlay() {
        Facts f = facts;
        Got g = got;
        if (f == null || g == null) return;
        BottomScreen.Explain e = start("play", "play", "Play", f.name, f.edition ? "Starts the game's Switch 2 Edition with what is set on this page." : "Starts the game with what is set on this page.");
        e.rows = new ArrayList<>();
        if (shown.contains(GameAreas.GRAPHICS)) e.rows.add(new CharSequence[] {"Graphics", g.line(GameAreas.GRAPHICS)});
        if (shown.contains(GameAreas.MODS)) e.rows.add(new CharSequence[] {"Mods & Cheats", g.line(GameAreas.MODS)});
        CharSequence played = dev.peanuts.launch.PlayTime.line(owner, f.titleId);
        if (played != null) e.rows.add(new CharSequence[] {"Played", played});
        e.keys = keys(new String[] {"A", "Play"}, new String[] {"B", area == null ? "Home" : "Back"});
        show(e);
    }

    /** A row: what it does, its value now, or for a switch Off | On (a touch flips it on the top screen too) */
    private static void explainRow(View row, String title, String areaKey) {
        Facts f = facts;
        if (f == null) return;
        GameAreas.Area ar = GameAreas.area(areaKey);
        TextView v = value(row);
        android.widget.Switch sw = toggle(row);
        String icon = GameAreas.icon(GameAreas.place(title, null, tileText(row)), title, v == null ? null : v.getText(), sw == null ? null : sw.isChecked());
        String help = GameHelp.of(title);
        BottomScreen.Explain e = start(row, icon != null ? icon : ar != null ? ar.icon : "game", title, (ar != null ? ar.label + ", " : "") + f.name, help != null ? help : "");
        if (sw != null) {
            e.on = sw.isChecked();
            e.flip = () -> { if (sw.isEnabled()) { sw.toggle(); explainRow(row, title, areaKey); } };
        } else if (v != null && v.getVisibility() == View.VISIBLE && !GameHelp.action(title)) e.value = v.getText();   // an action's line says what it does: no "Now"
        String a = !row.isClickable() ? null : sw != null ? (sw.isChecked() ? "Turn off" : "Turn on") : GameHelp.action(title) ? "Open" : "Change";   // Played: nothing to press
        e.keys = keys(a == null ? null : new String[] {"A", a}, playKeyIf(), new String[] {"B", area == null ? "Home" : "Back"},
                area == null ? null : new String[] {"L", ""}, area == null ? null : new String[] {"R", "Areas"});
        boolean fresh = explained != row;
        show(e);
        if (sw != null && fresh) UI.postDelayed(new Runnable() {   // a switch flipped on the top screen (A, a tap) changes no text: looked at while it is explained
            boolean was = sw.isChecked();
            @Override public void run() {
                if (explained != row || !row.isAttachedToWindow()) return;
                if (sw.isChecked() != was) { was = sw.isChecked(); explainRow(row, title, areaKey); }
                UI.postDelayed(this, 250);
            }
        }, 250);
    }

    private static void show(BottomScreen.Explain e) {
        if (owner == null) return;
        explained = e.key;
        BottomScreen.explain(owner, e);
    }

    /** The rows of the area that shows: a tap explains its row, a value that changes explains it again */
    private static void explainRows(Got g) {
        for (Map.Entry<View, String[]> en : g.placeOf.entrySet()) {
            View row = en.getKey();
            String[] place = en.getValue();
            if (area == null || !area.equals(place[1])) continue;
            row.setOnTouchListener((x, ev) -> { if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) explainRow(row, place[0], place[1]); return false; });
            TextView v = value(row);
            if (v != null) v.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence x, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence x, int st, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable x) { if (explained == row) explainRow(row, place[0], place[1]); }
            });
        }
    }

    /** A row's value as the second screen lists it: On / Off for a switch, else its value line */
    private static CharSequence shown(View row) {
        android.widget.Switch sw = toggle(row);
        if (sw != null) return sw.isChecked() ? "On" : "Off";
        TextView v = value(row);
        return v != null && v.getVisibility() == View.VISIBLE ? v.getText() : "";
    }

    private static List<View> children(ViewGroup g) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) out.add(g.getChildAt(i));
        return out;
    }

    private static String text(View v) { return v instanceof TextView ? String.valueOf(((TextView) v).getText()).trim() : null; }

    /** A row of a label over Show / Hide (upstream's folded Advanced): its label */
    private static TextView headOf(View v) {
        if (!(v instanceof LinearLayout) || ((LinearLayout) v).getOrientation() != LinearLayout.HORIZONTAL || ((LinearLayout) v).getChildCount() == 0) return null;
        View c = ((LinearLayout) v).getChildAt(0);
        return c instanceof TextView && c.isAccessibilityHeading() ? (TextView) c : null;
    }

    /** A row's tile text (its emoji) */
    private static String tileText(View row) {
        View t = row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 0 ? ((LinearLayout) row).getChildAt(0) : null;
        return t instanceof TextView ? String.valueOf(((TextView) t).getText()) : null;
    }

    /** The row's value line (Ui.Setting: the second line of its column), or null */
    static TextView value(View row) {
        if (!(row instanceof LinearLayout) || ((LinearLayout) row).getChildCount() < 2 || !(((LinearLayout) row).getChildAt(1) instanceof LinearLayout)) return null;
        LinearLayout col = (LinearLayout) ((LinearLayout) row).getChildAt(1);
        return col.getChildCount() > 1 && col.getChildAt(1) instanceof TextView ? (TextView) col.getChildAt(1) : null;
    }

    /** The row's switch (Ui.Setting.toggle: its last view), or null */
    static android.widget.Switch toggle(View row) {
        if (!(row instanceof LinearLayout)) return null;
        LinearLayout r = (LinearLayout) row;
        View last = r.getChildAt(r.getChildCount() - 1);
        return last instanceof android.widget.Switch ? (android.widget.Switch) last : null;
    }
}
