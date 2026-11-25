package dev.peanuts.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;

import dev.peanuts.Bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * PEANUTS: the overlay's top-right block as a label / value table (Overlay.tick hands its readings here). Built from spans only, because the upstream path draws a
 * CharSequence into a bitmap and redraws when Overlay.key (text and colours) changes: one monospace face, labels padded to a fixed width, values padded to the widest,
 * a larger fps number, and the frame-time histogram as a ReplacementSpan over a placeholder whose chars encode the bars (so a new frame changes the key).
 * The style ("table", the default, "pill" or "classic") and the preset are in the launcher's preferences file, the one Prefs uses. The pill is one line: the dot,
 * the fps, a short histogram, 1%L and the temperature (an icon, IconSpan; Normal), with the preset's own items (HudSettings.pillItems) and any value that turns yellow or red.
 */
public final class Hud {
    private Hud() {}

    /** The launcher's SharedPreferences file (Prefs.of); a test checks it against Prefs.java */
    static final String PREFS = "launcher";
    static final String STYLE = "pn_overlay_style", PRESET = "pn_overlay_preset", CORNER = "pn_overlay_corner", SCALE = "pn_overlay_scale";
    static final String HIDE_FOR_STATS = "pn_overlay_hide_for_stats";

    /** Overlay › With bottom stats: the overlay over the game hides while the second screen shows the stats page (dev.peanuts.ui.GameScreen.statsShowing), on by default */
    public static boolean hideForStats(Context c) { return prefs(c).getBoolean(HIDE_FOR_STATS, true); }

    /** The place keys and the host's value of each (vka_ovl.h: VKO_TOP_LEFT, VKO_TOP_RIGHT, VKO_BOTTOM_LEFT, VKO_BOTTOM_RIGHT, VKO_TOP_CENTER, VKO_BOTTOM_CENTER) */
    static final String[] CORNERS = {"tl", "tr", "bl", "br", "tc", "bc"};
    static final int[] CORNER_IDS = {0, 1, 2, 3, 4, 6};
    private static int sentCorner = -1;

    /** Value colours: white is normal, yellow a warning, red a bad value (the dot's green stays in LEVEL) */
    private static final int WHITE = 0xFFFFFFFF, YELLOW = 0xFFFFC107, RED = 0xFFF44336;
    private static final int[] LEVEL = {0xFF4CAF50, YELLOW, RED};
    /** LABEL: the widest label ("GAME") and the column every label is padded to; MIN_VALUE: the narrowest value column */
    private static final int BARS = 48, WINDOW = 512, LABEL = 4, MIN_VALUE = 10;
    private static final float GIB = 1024f * 1024f * 1024f;

    private static volatile String driver;
    private static final float[] frames = new float[WINDOW];

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS); }

    private static boolean item(Context c, String key) { return prefs(c).getBoolean("overlay_" + key, true); }

    /**
     * The build tag's switch (GameActivity: the top-left block): the player's when they set it, else off under the pill (a new player sees the pill alone; the
     * second screen's stats page names the build anyway) and on under the table and classic styles (Prefs.overlayItem's default)
     */
    public static boolean tagOn(Context c) {
        SharedPreferences p = prefs(c);
        return p.contains("overlay_tag") ? p.getBoolean("overlay_tag", true) : !"pill".equals(style(p));
    }

    /** The style: "table" until the player picked one */
    static String style(SharedPreferences p) { return p.getString(STYLE, "table"); }   // PEANUTS: Table stays the default; the pill is a choice (#538)

    /** The place: the player's, else the top centre for the pill and the top right for the taller table and classic blocks */
    static String corner(SharedPreferences p) { return p.getString(CORNER, "pill".equals(style(p)) ? "tc" : "tr"); }

    /** The preset the sheet names: the player's, else Normal for the pill and Full (every item on, Prefs.overlayItem's default) for the others */
    static String preset(SharedPreferences p) { return p.getString(PRESET, "pill".equals(style(p)) ? "normal" : HudSettings.DEFAULT_PRESET); }

    /** Whether an item of the table that Overlay.tick has no switch of its own for is on (the early return) */
    public static boolean extra(Context c) { return item(c, "graph") || item(c, "driver"); }

    /** The short driver name the DRV row shows, null before GameActivity set it (the second screen's stats page, dev.peanuts.ui.StatsView) */
    public static String driverName() { return driver; }

    /** The GPU driver's name for the DRV row (null: the system driver) */
    public static void driver(String label) {
        String s = FrameStats.shortDriver(label);
        driver = s.isEmpty() ? "System" : s;
    }

    /** One row: label, value and colours; {@code hist} is the histogram's placeholder (no value) */
    private static final class Row {
        String label = "", value = "", hist;
        int color = WHITE, dot = -2;   // dot: -2 none, -1 grey, 0..2 level
        boolean big;
        Row(String label, String value) { this.label = label; this.value = value; }
    }

    /** The block: {@code classic} when the style is "classic", otherwise the table (null when no row) */
    public static CharSequence table(Context c, CharSequence classic, float[] rate, boolean fresh, int level, float cap, float batTemp, int batLevel, boolean charging,
                                     long rss, long memTotal, long memAvail, boolean memLow) {
        SharedPreferences p = prefs(c);
        sendCorner(p);
        int scale = p.getInt(SCALE, 100);
        String style = style(p);
        if ("classic".equals(style)) { graphOff(); return scaled(classic, scale); }
        if ("pill".equals(style)) return fit(c, pill(c, p, rate, fresh, level, batTemp, batLevel, charging, memTotal, memAvail, memLow), Math.round(scale * PILL_BASE));
        List<Row> rows = new ArrayList<>();
        boolean fps = item(c, "fps"), dot = item(c, "throttle");
        // the frame-time window: the histogram, 1%L and FT's colour all take their target from its median
        int n = item(c, "graph") || (fps && fresh) ? readFrames() : 0;
        float target = n >= 10 ? FrameStats.median(frames, n) : Float.NaN;
        if (fps || dot) {
            Row r = new Row(fps ? "FPS" : "THR", "");
            if (fps) {
                r.value = !fresh ? "--" : rate[3] == 1 || rate[3] == 2 ? String.format(Locale.ROOT, "%.0f→%.0f", rate[0], rate[1]) : String.format(Locale.ROOT, "%.0f", rate[0]);
                r.big = true;
            }
            if (dot) r.dot = level >= 0 && level <= 2 ? level : -1;
            rows.add(r);
        }
        if (item(c, "graph")) graph(rows, n, target);
        if (fps && fresh) {
            Row r = new Row("FT", String.format(Locale.ROOT, "%.1f ms", rate[2]));
            if (!Float.isNaN(target) && !Float.isNaN(rate[2])) r.color = warn(FrameStats.level(rate[2], target));
            rows.add(r);
        }
        if (item(c, "gpu") && fresh && rate[5] >= 0) rows.add(new Row("GPU", String.format(Locale.ROOT, "%.1f ms", rate[5])));
        if (item(c, "cpu") && !Float.isNaN(cap)) {
            Row r = new Row("CPU", String.format(Locale.ROOT, "%.0f%%", cap * 100f));
            r.color = cap < 0.65f ? LEVEL[2] : cap < 0.85f ? LEVEL[1] : WHITE;
            rows.add(r);
        }
        if (item(c, "soc")) {
            int t = SocTemp.celsius();
            if (t != SocZones.NONE) {
                Row r = new Row("TEMP", t + "°");
                r.color = t >= SocTemp.HOT ? LEVEL[2] : t >= 70 ? LEVEL[1] : WHITE;
                rows.add(r);
            }
        }
        if (item(c, "battery") && batLevel >= 0) {
            // the temperature drives the colour of the whole value
            Row r = new Row("BAT", (Float.isNaN(batTemp) ? "" : String.format(Locale.ROOT, "%.0f° ", batTemp)) + batLevel + "%" + (charging ? "⚡︎" : ""));
            r.color = warn(FrameStats.batteryLevel(batTemp));
            rows.add(r);
        }
        if (item(c, "memory")) {
            // GAME: the app's own resident memory; RAM: the phone's used / total (red " low" when it runs short)
            if (rss >= 0) rows.add(new Row("GAME", String.format(Locale.ROOT, "%.1fG", rss / GIB)));
            if (memTotal > 0) {
                Row r = new Row("RAM", String.format(Locale.ROOT, "%.1f/%.1fG", (memTotal - memAvail) / GIB, memTotal / GIB));
                if (memLow) r.value += " low";
                r.color = warn(FrameStats.ramLevel(memTotal - memAvail, memTotal, memLow));
                rows.add(r);
            }
        }
        String drv = driver;
        if (item(c, "driver") && drv != null) rows.add(new Row("DRV", drv));
        boolean hasGraph = false;
        for (Row r : rows) hasGraph |= r.hist != null;
        if (!hasGraph) graphOff();   // no bars in this block: the host paints none
        if (rows.isEmpty()) return null;
        return scaled(build(rows), scale);
    }

    /** The whole block at scale percent of its size (a span over all of it, so the histogram's paint is scaled too); null stays null */
    private static CharSequence scaled(CharSequence t, int scale) {
        if (t == null || scale == 100 || scale <= 0) return t;
        SpannableStringBuilder s = new SpannableStringBuilder(t);
        s.setSpan(new RelativeSizeSpan(scale / 100f), 0, s.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return s;
    }

    /** Tells the host which corner the block is drawn in, when it changed (the choice is read here, so the first table of a game sends it too) */
    private static void sendCorner(SharedPreferences p) {
        int i = Arrays.asList(CORNERS).indexOf(corner(p));
        int c = i < 0 ? 1 : CORNER_IDS[i];
        if (c == sentCorner) return;
        try { Bridge.overlayCorner(c); sentCorner = c; } catch (UnsatisfiedLinkError e) { /* no host */ }
    }

    private static void graphOff() {
        try { Bridge.graphRect(0, 0, 0, 0); } catch (UnsatisfiedLinkError e) { /* no host */ }
    }

    /** Copies the frame-time window into frames and returns its count (0 without a host) */
    private static int readFrames() {
        try { return Math.min(Bridge.frameTimes(frames), WINDOW); } catch (UnsatisfiedLinkError e) { return 0; }
    }

    /** A value's colour from its status: 0 white (normal), 1 yellow, 2 red */
    private static int warn(int level) {
        return level == 2 ? RED : level == 1 ? YELLOW : WHITE;
    }

    /** The pill's own size at Size 100 %: 1.5 times the overlay's 8 sp (GameActivity.OVERLAY_SP, small so the tall table stays off the games' corner buttons); one line can be larger */
    private static final float PILL_BASE = 1.5f;

    /** The widest the pill may be, as a share of the display's width: room for the build tag (slot 0, top left) beside a centred pill, and no cut by the host */
    private static final float PILL_MAX_WIDTH = 0.75f;

    /**
     * The pill's own factor on its scale, 1 or less: Hud.margins lowers it when the drawn pill is wider than PILL_MAX_WIDTH of the game's window (a Full pill at a
     * large Size: on a 1920 px wide phone it went past the edge, and the host cuts a block to the display) and raises it back when there is room; the next tick
     * (half a second) draws it at that size. From the width the view really laid out, not a measure of our own
     */
    private static volatile float pillFit = 1f;

    private static CharSequence fit(Context c, CharSequence pill, int scale) {
        if (pill == null) return null;
        return scaled(pill, Math.max(25, Math.round(scale * pillFit)));
    }

    /** Lowers or raises pillFit from the pill the view laid out (vw px wide) against the game window's width; a line in the log when it changes */
    private static void fitTo(android.view.View view, int vw) {
        android.util.DisplayMetrics dm = view.getResources().getDisplayMetrics();
        int sw = screenWidth(view.getContext(), dm);
        float max = sw * PILL_MAX_WIDTH, f = pillFit;
        if (vw <= 0 || sw <= 0) return;
        if (vw > max) f = f * max / vw;
        else if (f < 1f && vw < max * 0.9f) f = Math.min(1f, f * max * 0.95f / vw);
        f = Math.max(0.25f, f);
        if (Math.abs(f - pillFit) < 0.01f) return;
        android.util.Log.i("peanuts", String.format(java.util.Locale.ROOT, "[overlay] pill fit %.2f -> %.2f: drawn %d px, window %d px (metrics %dx%d)", pillFit, f, vw, sw, dm.widthPixels, dm.heightPixels));
        pillFit = f;
    }

    /**
     * The width the game's window has, in px. Not the Resources' metrics alone: on a phone with a second screen (the AYN Thor) they read the 1080 px of the other
     * display at times, and a Full pill was made smaller for nothing. The GameActivity's own window bounds; the metrics' longer side without an Activity (the game
     * is landscape)
     */
    private static int screenWidth(Context c, android.util.DisplayMetrics dm) {
        if (c instanceof android.app.Activity) {
            int w = ((android.app.Activity) c).getWindowManager().getCurrentWindowMetrics().getBounds().width();
            if (w > 0) return w;
        }
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    /** The pill's gap to the display's edges (dp): it floats */
    private static final int FLOAT_DP = 6;

    /**
     * The stats block's transparent margins (left, top, right; px) in its bitmap, from the window's (Overlay.Block.inset: the top-right corner's) and the place
     * (Overlay.Block.draw calls it for slot 1 before drawing). A left place takes the corner's side margin on its left, a centred one on both sides, so it is centred;
     * the pill adds FLOAT_DP all round and gets its own background (PillBg); the others get GameActivity.overlayText's back (black at 50 %, 3 dp corners). The top
     * margin is the bottom one for a bottom place (peanuts_graph.c moves the leading transparent rows below).
     */
    public static int[] margins(android.view.View view, int h, int l, int t, int r) {
        SharedPreferences p = prefs(view.getContext());
        boolean pill = "pill".equals(style(p));
        float dp = view.getResources().getDisplayMetrics().density;
        if (pill) fitTo(view, view.getMeasuredWidth());
        if (pill && !(view.getBackground() instanceof PillBg)) view.setBackground(new PillBg());
        else if (!pill && view.getBackground() instanceof PillBg) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x80000000);
            bg.setCornerRadius(3 * dp);
            view.setBackground(bg);
        }
        int gap = pill ? Math.round(FLOAT_DP * dp) : 0, side = Math.max(l, r);
        String place = corner(p);
        if (place.endsWith("c")) return new int[] {side + gap, t + gap, side + gap};
        if (place.endsWith("l")) return new int[] {side + gap, t + gap, 0};
        return new int[] {0, t + gap, side + gap};
    }

    /** The pill's histogram: 6 chars wide, the newest 20 frames (HistogramSpan tells the host, Bridge.graphBars; the table: MIN_VALUE chars, 48) */
    private static final int PILL_BARS = 6, PILL_FRAMES = 20;
    /**
     * The pill is plain: white values, warm grey units, light warm icons (toned to PillBg's peanut brown); yellow and red only mark a problem, and the throttle
     * dot's green is its one other colour.
     * Everything is one size (a larger fps number stood out too much on the phone); the order comes from weight and colour instead: the fps number bold, the
     * units grey, the phone's values a dimmer white, and a thin line (IconSpan.SEP) between the frames' group (the graph, 1%L) and the phone's (the temperature,
     * the battery, RAM)
     */
    private static final int GREY = 0xFFA89A8A, ICON = 0xFFE6DCCF, SECONDARY = 0xFFD6CDC2, SEP = 0x73D9A066;
    private static final float FPS_SIZE = 1f, UNIT_SIZE = 1f, SECONDARY_SIZE = 1f, GRAPH_SIZE = 1f;

    /**
     * The pill: one line. Its items are the preset's pill items (HudSettings.pillItems; Custom: the item switches); TEMP, BAT and RAM also show when they are yellow
     * or red, whatever the preset, as far as Overlay.tick read them (BAT and RAM only while their switch is on: it reads nothing else). The fps number takes the
     * frame time's colour against the window's median, as FT does in the table. Null when nothing is left.
     */
    private static CharSequence pill(Context c, SharedPreferences p, float[] rate, boolean fresh, int level, float batTemp, int batLevel, boolean charging,
                                     long memTotal, long memAvail, boolean memLow) {
        java.util.Set<String> on = HudSettings.pillItems(preset(p));
        java.util.function.Predicate<String> has = k -> on != null ? on.contains(k) : item(c, k);
        boolean fps = has.test("fps"), graph = has.test("graph"), low1 = on != null ? on.contains("low") : graph;   // 1%L: Full's (a word few players know), Custom's with the graph
        int n = graph || (fps && fresh) ? readFrames() : 0;
        float target = n >= 10 ? FrameStats.median(frames, n) : Float.NaN;
        // the frames' group: the dot, the fps number, the graph, 1%L
        SpannableStringBuilder s = new SpannableStringBuilder();
        if (has.test("throttle")) put(s, "●", level >= 0 && level <= 2 ? LEVEL[level] : 0xFF888888, SECONDARY_SIZE, false);
        if (fps) {
            String v = !fresh ? "--" : rate[3] == 1 || rate[3] == 2 ? String.format(Locale.ROOT, "%.0f→%.0f", rate[0], rate[1]) : String.format(Locale.ROOT, "%.0f", rate[0]);
            int col = fresh && !Float.isNaN(target) && !Float.isNaN(rate[2]) ? warn(FrameStats.level(rate[2], target)) : WHITE;
            put(s, v, col, FPS_SIZE, true);
            put(s, "fps", GREY, UNIT_SIZE, false);
        }
        boolean bars = false;
        if (graph && n >= 10) {
            int shown = Math.min(PILL_FRAMES, n);
            if (s.length() > 0) s.append(' ');
            int hs = s.length();
            s.append(FrameStats.placeholder(frames, n - shown, shown, target));
            s.setSpan(new HistogramSpan(PILL_BARS, true), hs, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new RelativeSizeSpan(GRAPH_SIZE), hs, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            bars = true;
            float low = FrameStats.onePercentLowFps(frames, n);
            if (low1 && !Float.isNaN(low)) {
                put(s, "1%L", GREY, SECONDARY_SIZE, false);
                put(s, String.format(Locale.ROOT, "%.0f", low), secondary(FrameStats.level(1000f / low, target)), SECONDARY_SIZE, false);
            }
        }
        // the phone's group: the temperature, the battery, RAM
        SpannableStringBuilder d = new SpannableStringBuilder();
        int t = SocTemp.celsius();
        if (t != SocZones.NONE) {
            int lv = t >= SocTemp.HOT ? 2 : t >= 70 ? 1 : 0;
            if (has.test("soc") || lv > 0) { icon(d, IconSpan.TEMP, lv); put(d, t + "°", secondary(lv), SECONDARY_SIZE, false); }
        }
        if (batLevel >= 0) {
            int lv = FrameStats.batteryLevel(batTemp);
            if (has.test("battery") || lv > 0) {
                icon(d, IconSpan.BATTERY, lv);
                put(d, (lv > 0 && !Float.isNaN(batTemp) ? String.format(Locale.ROOT, "%.0f° ", batTemp) : "") + batLevel + "%" + (charging ? "⚡︎" : ""), secondary(lv), SECONDARY_SIZE, false);
            }
        }
        if (memTotal > 0) {
            int lv = FrameStats.ramLevel(memTotal - memAvail, memTotal, memLow);
            if (has.test("memory") || lv > 0) {
                icon(d, IconSpan.RAM, lv);
                put(d, String.format(Locale.ROOT, "%.1fG", (memTotal - memAvail) / GIB) + (memLow ? " low" : ""), secondary(lv), SECONDARY_SIZE, false);
            }
        }
        if (s.length() > 0 && d.length() > 0) {   // both groups: the line between them
            s.append(' ');
            int a = s.length();
            s.append('|');
            s.setSpan(new IconSpan(IconSpan.SEP, SEP), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.append(' ');
        }
        s.append(d);
        if (!bars) graphOff();
        if (s.length() == 0) return null;
        s.insert(0, " ").append(' ');   // room for the round ends (Hud.margins)
        s.setSpan(new TypefaceSpan("monospace"), 0, s.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return s;
    }

    /** A value of the second rank: the dimmer white, or the status's yellow or red at full strength */
    private static int secondary(int level) { return level > 0 ? warn(level) : SECONDARY; }

    /** An icon of the pill (IconSpan) over one placeholder char at the second rank's size, a space before it: light grey, or the status's yellow or red (the colour span keys Overlay.key) */
    private static void icon(SpannableStringBuilder s, int kind, int level) {
        int color = level > 0 ? warn(level) : ICON;
        if (s.length() > 0) s.append(' ');
        int a = s.length();
        s.append('o');
        s.setSpan(new IconSpan(kind, color), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(color), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(SECONDARY_SIZE), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** One piece of the pill, a space before it (none before the first), in its colour and at {@code size} times the text's, bold for the fps number */
    private static void put(SpannableStringBuilder s, String t, int color, float size, boolean bold) {
        if (s.length() > 0) s.append(' ');
        int a = s.length();
        s.append(t);
        s.setSpan(new ForegroundColorSpan(color), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (size != 1f) s.setSpan(new RelativeSizeSpan(size), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void graph(List<Row> rows, int n, float target) {
        if (n < 10) return;
        int shown = Math.min(BARS, n);
        Row h = new Row("", "");
        h.hist = FrameStats.placeholder(frames, n - shown, shown, target);
        rows.add(h);
        float low = FrameStats.onePercentLowFps(frames, n);
        if (!Float.isNaN(low)) {
            Row r = new Row("1%L", String.format(Locale.ROOT, "%.0f", low));
            r.color = LEVEL[FrameStats.level(1000f / low, target)];
            rows.add(r);
        }
    }

    private static CharSequence build(List<Row> rows) {
        int w = MIN_VALUE;
        for (Row r : rows) w = Math.max(w, valueLength(r));
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (Row r : rows) {
            if (s.length() > 0) s.append('\n');
            int ls = s.length();
            s.append(pad(r.label, LABEL)).append(' ');
            if (!r.label.isEmpty()) s.setSpan(new ForegroundColorSpan(FrameStats.labelColor(r.label)), ls, ls + r.label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (r.hist != null) {
                int hs = s.length();
                s.append(r.hist);
                s.setSpan(new HistogramSpan(w), hs, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            int len = valueLength(r);
            // the fps number is 1.5 times as wide: half a char less padding for each of its chars keeps the right edge near the others
            int gap = Math.max(0, w - len - (r.big ? (int) Math.round(r.value.length() * 0.5) : 0));
            for (int i = 0; i < gap; i++) s.append(' ');
            if (r.dot > -2) {
                int d = s.length();
                s.append('●');
                s.setSpan(new ForegroundColorSpan(r.dot >= 0 ? LEVEL[r.dot] : 0xFF888888), d, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (!r.value.isEmpty()) s.append(' ');
            }
            int vs = s.length();
            s.append(r.value);
            if (s.length() > vs) {
                s.setSpan(new ForegroundColorSpan(r.color), vs, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (r.big) s.setSpan(new RelativeSizeSpan(1.5f), vs, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        s.setSpan(new TypefaceSpan("monospace"), 0, s.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        return s;
    }

    private static int valueLength(Row r) {
        if (r.hist != null) return 0;
        return (r.dot > -2 ? (r.value.isEmpty() ? 1 : 2) : 0) + r.value.length();
    }

    private static String pad(String t, int n) {
        StringBuilder b = new StringBuilder(t);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }
}
