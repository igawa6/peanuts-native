package dev.peanuts.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.nxnative.android.R;
import dev.peanuts.Bridge;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.overlay.FrameStats;
import dev.peanuts.overlay.Hud;
import dev.peanuts.overlay.SocTemp;

/**
 * The second screen while a game with no companion runs (GameScreen, Settings › Library › Games without a companion: Stats), in the Home bottom screen's card
 * style (BottomPanel):
 * <ul>
 * <li>a header with the cover, the name, a gear (the settings sheet) and three pills: the console mode the game sees (DOCKED or HANDHELD, Bridge.opMode; its
 *     blinking dot says the page is live), the GPU driver (Hud.driverName) and the state (GameStats.cause): COOL or WARM from Android's thermal status and
 *     headroom, THROTTLING when the system cuts the clocks, GPU-BOUND when the game is behind with a full GPU;</li>
 * <li>the frame rate against the game's target, and ON PACE: the share of the newest frames within 15 % of the target (GameStats.onPace);</li>
 * <li>a frame-time graph that scrolls once a display frame (Graph), with its scale, the target and the 1% low; a tap shows the last 30 s instead (the worst
 *     frame of each half second), another tap the last 2 s;</li>
 * <li>up to eight tiles, four a row at most: the frame time, the 1% low, the GPU's load and clock with the game's GPU memory (VRAM), the main CPU cluster's
 *     clock and its cap (DeviceStats, sysfs; a tile hides where its files do not read), the SoC's temperature, the power in watts with the battery's level and
 *     time left (its current, voltage and charge counter; the level's fall an hour where the current does not read), the game's RAM (its CPU side and its GPU
 *     memory; the label the phone's RAM in use over its whole RAM, the meter the game's share, the rest in use faint behind it, the free part empty) and the
 *     run's clock. The meters mark the run's peak, and SOC names it when it is above now.</li>
 * </ul>
 * What shows is the player's (StatsConfig: the presets Simple, Normal and Full, or items picked by hand), from the gear's sheet, kept for every game
 * (PeanutsPrefs.statsShown). Frame times come from the host (Bridge.frameTimes, on whether or not the overlay is); the numbers update 4 times a second and roll to
 * a new value (Num), GPU and CPU once a second, the rest every 2 s; nothing is read while the page is not shown.
 * <p>
 * Burn-in guard (the AYN Thor's bottom screen is AMOLED, and this page stays still for hours), each switchable in the sheet (PeanutsPrefs.statsGuard), all on by
 * default: a black page (its pixels are off; the page takes the dark theme's colours then), the pixel shifter (the page goes round a circle of 8 pixels, a pixel
 * every 30 s: GameStats.orbit), dimmer fixed labels, a dim after 10 minutes without a touch and black after 30 but for a small moving dot. A touch brings it back
 * (and does nothing else). The build tag always shows small at the bottom right (GameScreen.buildTag), whatever the overlay over the game shows.
 */
public final class StatsView extends FrameLayout {
    private static final long TICK_MS = 250;
    private static final int BARS = 60, WINDOW = 512, PACE_FRAMES = 150;
    private static final float GIB = 1024f * 1024f * 1024f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float[] frames = new float[WINDOW];
    private final DeviceStats device = new DeviceStats(new File("/"));
    private final boolean night;   // the app's own theme

    // the settings (StatsConfig), read at each build
    private Set<String> shown;
    private boolean black, shift, dimLabels, autoDim, autoOff;

    // the views and colours of the current build (build)
    private boolean dark;   // the dark theme's colours: the app's, or the black page's
    private int warnYellow, warnRed, good, text, text2, labelCol, tileBg, track;
    private int cFps, cGpu, cCpu, cSoc, cBat, cMem;   // each tile group's colour, one hue each, darker on the light theme (tone)
    private FrameLayout content;   // the card, centred; the pixel shifter moves it
    private LinearLayout card;
    private View sheet, dim, ember, live, thrDot;
    private TextView tile, name, driver, thrText, modeText, target, graphNote;
    private TextView vLow, vCpu, vRam, vRun, vBat;
    private Num fps, pace, frame, gpu, soc, watts;
    private Meter mGpu, mCpu, mSoc, mBat, mRam;
    private Graph graph;
    private TextView buildTag;   // the build tag, always (buildTag(CharSequence))
    private CharSequence buildTagText;
    private final Map<String, View> tiles = new HashMap<>();
    private boolean bigTiles;   // Simple's larger tiles (tileView)

    private String gameName = "";
    private Bitmap cover;
    private boolean running, sheetOpen;
    private int stage;   // 0 lit, 1 dimmed, 2 black (GameStats.DIM_MS, OFF_MS)
    private int ticks, stale;
    private float lastTail = Float.NaN;
    private long startedAt, touchedAt;
    private int batFrom = -1;
    private long batFromAt;
    private boolean wasCharging;
    private float ampsAvg = Float.NaN, headroom = Float.NaN;
    private int thermal = -1, gpuBusy = -1, cause = -1, causeSeen = -2;   // the state pill: the last reading of each, the cause shown and the one seen last second
    private float paceNow = Float.NaN;
    private int socPeak = SocTemp.NONE, gpuPeak = -1;   // this run's peaks
    private long ramPeak = -1;

    public StatsView(Context c) {
        super(c);
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setClipChildren(false);
        setClipToPadding(false);
        build();
    }

    /** Reads the settings and makes the page again (its numbers come back at the next tick; the graph starts a new history) */
    private void build() {
        Context base = getContext();
        shown = StatsConfig.parse(PeanutsPrefs.statsShown(base));
        black = PeanutsPrefs.statsGuard(base, StatsConfig.BLACK);
        shift = PeanutsPrefs.statsGuard(base, StatsConfig.SHIFT);
        dimLabels = PeanutsPrefs.statsGuard(base, StatsConfig.DIM_LABELS);
        autoDim = PeanutsPrefs.statsGuard(base, StatsConfig.AUTO_DIM);
        autoOff = PeanutsPrefs.statsGuard(base, StatsConfig.AUTO_OFF);
        dark = night || black;
        Context c = base;
        if (dark && !night) {   // a black page takes the dark theme's colours whatever the app's theme: cream text on black
            Configuration cf = new Configuration(base.getResources().getConfiguration());
            cf.uiMode = (cf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
            c = base.createConfigurationContext(cf);
        }
        boolean was = running;
        if (graph != null) graph.live(false);
        nums.clear();
        removeAllViews();
        tiles.clear();
        causeSeen = -2;
        cause = -1;

        // three steps that read apart at a glance: the banana (a frame on pace, the brand), orange (slow, warm), red (bad). Amber was too near the banana: a slow
        // frame in the graph looked like a good one
        warnYellow = dark ? 0xFFFF9F1A : 0xFFB25E00;
        warnRed = dark ? 0xFFFF5A4E : 0xFFC62828;
        good = dark ? 0xFF4CAF50 : 0xFF2E7D32;
        text = PUi.col(c, R.color.bn_text);
        text2 = PUi.col(c, R.color.bn_text_2);
        int cardBg = black ? 0xFF120E0A : PUi.col(c, R.color.bn_card);
        tileBg = black ? 0xFF0B0806 : PUi.col(c, R.color.bn_bg);
        track = black ? 0xFF241B10 : PUi.col(c, R.color.bn_tint);
        labelCol = dimLabels ? blend(text2, cardBg, 0.35f) : text2;   // the words that never change, a step quieter than the numbers
        // the overlay's group colours where they do not clash here: the CPU's blue was the frame times' too (indigo now); the SoC and the battery shared one orange,
        // the warning's (pink and teal now), so a hot SoC's meter did not change colour
        cFps = tone(FrameStats.LABEL_FPS); cGpu = tone(FrameStats.LABEL_GPU); cCpu = tone(0xFF8C9EFF); cSoc = tone(0xFFFF8FB1); cBat = tone(0xFF4DD0C4); cMem = tone(FrameStats.LABEL_MEM);
        setBackgroundColor(black ? Color.BLACK : Color.TRANSPARENT);   // black: over GameScreen's banana pattern too

        Configuration cf = c.getResources().getConfiguration();
        boolean wide = cf.screenWidthDp >= 600 && cf.screenWidthDp >= cf.screenHeightDp * 1.25f;
        boolean simple = StatsConfig.match(shown).equals(StatsConfig.SIMPLE);
        // Simple: the cover large beside the frame rate and a 2 x 2 of large tiles ("now playing"), the page filled; on a narrow screen (a phone held upright) the
        // same stacked, the cover above
        boolean side = simple && cf.screenWidthDp >= 480, stacked = simple && !side;
        bigTiles = simple;
        int pad = PUi.dp(c, wide ? 24 : 16);
        content = new FrameLayout(c);
        content.setPadding(pad, pad, pad, pad);
        content.setClipChildren(false);
        content.setClipToPadding(false);

        card = PUi.card(c);
        if (black) card.setBackground(PUi.round(cardBg, PUi.dp(c, 20), 0xFF221A12, PUi.dp(c, 1)));

        // the parts: the cover, the name, the pills, the gear
        // the side cover: as large as the card leaves it, at most 192 dp: under the card's height (with its name and pill below) and beside the right column's
        // 260 dp (the frame rate and the 2 x 2 tiles); the Thor's bottom screen (538 x 468 dp) gives the 192
        int innerW = Math.min(wide ? 640 : 560, cf.screenWidthDp - 2 * (wide ? 24 : 16)) - 36;
        int coverDp = side ? Math.max(96, Math.min(192, Math.min(cf.screenHeightDp - 2 * (wide ? 24 : 16) - 36 - 90, innerW - 18 - 260))) : stacked ? 96 : 52;
        tile = PUi.tile(c, "?", coverDp);
        name = PUi.text(c, "", stacked ? 20 : 17, R.color.bn_text, 800);
        if (simple) name.setMaxLines(2); else name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout pills = PUi.row(c);
        live = dot(c, good);
        LinearLayout livePill = pill(c, live, "LIVE");
        modeText = (TextView) livePill.getChildAt(1);
        pills.addView(livePill);
        driver = pillText(c, "");
        LinearLayout drvPill = pill(c, null, null);
        drvPill.addView(driver);
        pills.addView(drvPill, gapLeft(c, 6));
        thrDot = dot(c, good);
        LinearLayout thrPill = pill(c, thrDot, "");
        thrText = (TextView) thrPill.getChildAt(1);
        thrPill.setVisibility(View.GONE);
        pills.addView(thrPill, gapLeft(c, 6));
        if (!shown.contains(StatsConfig.PILLS)) { drvPill.setVisibility(View.GONE); thrPill.setVisibility(View.GONE); }
        ImageView gear = PUi.iconButton(c, R.drawable.ic_settings, "Stats settings", PUi.Kind.QUIET, v -> { Haptics.play(v, Haptics.Word.TICK); openSheet(true); });
        gear.setAlpha(0.8f);

        // the frame rate and its target; ON PACE beside it (on the right, or next to it in Simple)
        LinearLayout big = PUi.row(c);
        big.setGravity(simple ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.BOTTOM);
        TextView fpsView = PUi.text(c, "--", side ? 116 : stacked ? 100 : 52, R.color.bn_accent, 700);
        fpsView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        fpsView.setIncludeFontPadding(false);
        fps = new Num(fpsView, v -> String.format(Locale.ROOT, "%.0f", v), "--", 2f);
        big.addView(fpsView);
        target = PUi.text(c, "", simple ? 22 : 15, R.color.bn_text_2, 600);
        target.setTypeface(Typeface.MONOSPACE);
        target.setTextColor(labelCol);
        LinearLayout.LayoutParams tl = gapLeft(c, 6);
        tl.bottomMargin = PUi.dp(c, simple ? 10 : 4);
        big.addView(target, tl);
        LinearLayout.LayoutParams fl = gapLeft(c, 6);
        fl.bottomMargin = PUi.dp(c, simple ? 12 : 6);
        TextView fpsLabel = label(c, "FPS");
        if (simple) fpsLabel.setTextSize(12);
        big.addView(fpsLabel, fl);
        if (!simple) big.addView(PUi.spacer(c));
        TextView paceView = PUi.text(c, "", 20, R.color.bn_text, 700);
        paceView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        paceView.setIncludeFontPadding(false);
        pace = new Num(paceView, v -> Math.round(v) + "%", "\u2014", 3f);
        if (shown.contains(StatsConfig.PACE)) {
            LinearLayout pc = column(c);
            pc.setGravity(Gravity.END);
            pc.addView(paceView, PUi.wrap());
            PUi.add(pc, label(c, "ON PACE"), 3, PUi.wrap());   // wrap: a match_parent child in a wrap_content column is cut to its sibling's width
            LinearLayout.LayoutParams pl = simple ? gapLeft(c, 20) : PUi.wrap();
            pl.bottomMargin = PUi.dp(c, simple ? 12 : 4);
            big.addView(pc, pl);
        }

        // the graph, its legend and its span; a tap switches 2 s / 30 s
        graph = new Graph(c);
        LinearLayout gbox = null;
        if (shown.contains(StatsConfig.GRAPH)) {
            gbox = column(c);
            gbox.setBackground(PUi.round(tileBg, PUi.dp(c, 10), 0, 0));
            gbox.setPadding(PUi.dp(c, 6), PUi.dp(c, 6), PUi.dp(c, 6), PUi.dp(c, 4));
            gbox.addView(graph, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(c, 66)));
            LinearLayout legend = PUi.row(c);
            TextView key = PUi.text(c, legendText(), 11, R.color.bn_text_2, 500);
            key.setTypeface(Typeface.MONOSPACE);
            key.setSingleLine(true);
            legend.addView(key, PUi.wrap());
            legend.addView(PUi.spacer(c));
            graphNote = PUi.text(c, "", 11, R.color.bn_text_2, 500);
            graphNote.setTypeface(Typeface.MONOSPACE);
            graphNote.setSingleLine(true);
            graphNote.setTextColor(labelCol);
            legend.addView(graphNote, PUi.wrap());
            PUi.add(gbox, legend, 3);
            gbox.setOnClickListener(v -> { Haptics.play(v, Haptics.Word.TICK); graph.toggle(); frameStats(); });
            gbox.setContentDescription("Frame-time graph. Tap for the last 30 seconds or the last 2.");
        } else graphNote = new TextView(c);   // not shown: the tick writes to a view that is not on the page

        // the tiles: every one made, the shown ones (StatsConfig.tiles) laid out in rows of four at most, or two (Simple's 2 x 2)
        mGpu = new Meter(c); mCpu = new Meter(c); mSoc = new Meter(c); mBat = new Meter(c); mRam = new Meter(c);
        frame = new Num(tileView(c, StatsConfig.FRAME, "FRAME", cFps, null), v -> unit(String.format(Locale.ROOT, "%.1f", v), " ms"), "\u2014", 1f);
        vLow = tileView(c, StatsConfig.LOW, "1% LOW", cFps, null);
        gpu = new Num(tileView(c, StatsConfig.GPU, "GPU", cGpu, mGpu), v -> {
            int mhz = device.gpuMhz();
            return unit(Math.round(v) + "%", mhz > 0 ? " " + mhz + "MHz" : "");
        }, "\u2014", 5f);
        vCpu = tileView(c, StatsConfig.CPU, "CPU", cCpu, mCpu);
        soc = new Num(tileView(c, StatsConfig.SOC, "SOC", cSoc, mSoc), v -> Math.round(v) + "\u00B0", "\u2014", 2f);
        vBat = tileView(c, StatsConfig.BAT, "BATTERY", cBat, mBat);
        watts = new Num(vBat, v -> unit((wasCharging ? "+" : "") + String.format(Locale.ROOT, "%.1f", v), "W"), "\u2014", 0.5f);
        vRam = tileView(c, StatsConfig.RAM, "GAME RAM", cMem, mRam);
        Meter none = new Meter(c);
        vRun = tileView(c, StatsConfig.RUN, "THIS RUN", PUi.col(c, R.color.bn_banana), none);
        none.setVisibility(View.INVISIBLE);   // no meter, but its room: the clock lines up with its neighbours
        List<String> shownTiles = StatsConfig.tiles(shown, device.hasGpu(), device.cpuKHz() > 0);
        LinearLayout grid = column(c);
        int gap = PUi.dp(c, simple ? 8 : 7);
        for (List<String> row : simple ? StatsConfig.pairs(shownTiles) : StatsConfig.rows(shownTiles)) {
            LinearLayout r = PUi.row(c);
            r.setGravity(Gravity.FILL_VERTICAL);
            for (int i = 0; i < row.size(); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (i > 0) lp.leftMargin = gap;
                r.addView(tiles.get(row.get(i)), lp);
            }
            if (simple && row.size() == 1) r.setWeightSum(2);   // an odd last tile keeps its half
            LinearLayout.LayoutParams rl = PUi.fill();
            if (grid.getChildCount() > 0) rl.topMargin = gap;
            grid.addView(r, rl);
        }

        // put together
        View box = card;
        if (side) {
            int coverPx = PUi.dp(c, coverDp);
            LinearLayout row = PUi.row(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout left = column(c);
            left.addView(tile);
            PUi.add(left, name, 12, new LinearLayout.LayoutParams(coverPx, ViewGroup.LayoutParams.WRAP_CONTENT));
            PUi.add(left, pills, 8, PUi.wrap());
            row.addView(left, new LinearLayout.LayoutParams(coverPx, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout right = column(c);
            right.addView(big);
            if (gbox != null) PUi.add(right, gbox, 12);
            if (grid.getChildCount() > 0) PUi.add(right, grid, 18);
            LinearLayout.LayoutParams rp = PUi.weight();
            rp.leftMargin = PUi.dp(c, 18);
            row.addView(right, rp);
            card.addView(row);
            FrameLayout withGear = new FrameLayout(c);   // the gear in the card's corner, over it
            withGear.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(PUi.dp(c, 36), PUi.dp(c, 36), Gravity.TOP | Gravity.END);
            gp.topMargin = gp.rightMargin = PUi.dp(c, 8);
            withGear.addView(gear, gp);
            box = withGear;
        } else {
            LinearLayout top = PUi.row(c);
            top.addView(tile);
            LinearLayout info = column(c);
            info.setPadding(PUi.dp(c, stacked ? 16 : 12), 0, PUi.dp(c, 6), 0);
            info.addView(name);
            PUi.add(info, pills, stacked ? 8 : 5);
            top.addView(info, PUi.weight());
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(PUi.dp(c, 36), PUi.dp(c, 36));
            gl.gravity = Gravity.TOP;
            top.addView(gear, gl);
            card.addView(top);
            PUi.add(card, big, stacked ? 18 : 14);
            if (gbox != null) PUi.add(card, gbox, 10);
            if (grid.getChildCount() > 0) PUi.add(card, grid, stacked ? 18 : 9);
        }

        // Simple fills the screen's height, its parts centred in the card; the others are as tall as their parts, centred on the screen
        if (simple) card.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(new Capped(c, PUi.dp(c, wide ? 640 : 560), box, simple),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, simple ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // the build tag: small, bottom right, in content's bottom padding (below the card, never over it; content draws outside its padding), a child of content so
        // the pixel shifter moves it with the page
        buildTag = new TextView(c);
        buildTag.setTextSize(10);
        buildTag.setTextColor(labelCol);
        buildTag.setTypeface(Typeface.MONOSPACE);
        buildTag.setSingleLine(true);
        buildTag.setIncludeFontPadding(false);
        buildTag.setPadding(PUi.dp(c, 4), 0, PUi.dp(c, 4), 0);
        buildTag.setText(buildTagText);
        buildTag.setVisibility(buildTagText == null ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        tagLp.bottomMargin = -pad + PUi.dp(c, 2);   // the tag (about 12 dp) fits in the padding (16 or 24 dp) under the card
        content.addView(buildTag, tagLp);
        addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet = sheet(c);
        sheet.setVisibility(sheetOpen ? View.VISIBLE : View.GONE);
        addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim = new View(c);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(stage == 0 ? 0f : stage == 1 ? dimAlpha() : 1f);
        dim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ember = new View(c);   // black after 30 min: a small dot that moves, so the page is seen to be there
        ember.setBackground(PUi.round(0xFF5A4A2A, PUi.dp(c, 3), 0, 0));
        ember.setVisibility(stage == 2 ? View.VISIBLE : View.GONE);
        addView(ember, new FrameLayout.LayoutParams(PUi.dp(c, 6), PUi.dp(c, 6)));

        bindHeader();
        lastTail = Float.NaN;
        if (was) { graph.live(graphRuns()); ui.removeCallbacks(tick); ticks = 0; ui.post(tick); }
    }

    /** The build tag, moved here from over the game while the overlay hides for this page (GameScreen.buildTag); null hides it */
    public void buildTag(CharSequence t) {
        if (t == null ? buildTagText == null : buildTagText != null && t.toString().contentEquals(buildTagText)) return;
        buildTagText = t;
        if (buildTag != null) { buildTag.setText(t); buildTag.setVisibility(t == null ? GONE : VISIBLE); }
    }

    /** The game's name and cover; the run's clock starts at {@code since} (SystemClock.elapsedRealtime) */
    public void bind(String gameName, Bitmap icon, long since) {
        this.gameName = gameName == null ? "" : gameName;
        cover = icon;
        bindHeader();
        if (since != startedAt) {   // a new run: its own battery stretch and peaks
            startedAt = since;
            batFrom = -1;
            ampsAvg = Float.NaN;
            socPeak = SocTemp.NONE;
            gpuPeak = -1;
            ramPeak = -1;
        }
    }

    private void bindHeader() {
        name.setText(gameName);
        String n = gameName.trim();
        tile.setText(n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
        PUi.tileIcon(tile, null);
        if (cover != null) PUi.tileIcon(tile, cover);
    }

    public void icon(Bitmap b) { if (b != null) { cover = b; Motion.cover(tile, b); } }

    /** Ticks while shown; nothing is read while it is not */
    public void setRunning(boolean on) {
        if (on == running) return;
        running = on;
        ui.removeCallbacks(tick);
        if (on) { touchedAt = SystemClock.elapsedRealtime(); wake(); ticks = 0; ui.post(tick); }
        graph.live(graphRuns());
    }

    @Override protected void onDetachedFromWindow() {
        ui.removeCallbacks(tick);
        if (clockPosted) { clockPosted = false; Choreographer.getInstance().removeFrameCallback(clockCb); }
        super.onDetachedFromWindow();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (running) { ui.removeCallbacks(tick); ui.post(tick); graph.live(graphRuns()); } }

    /** A touch wakes a dimmed or black page and does nothing else (a tap on the graph does not switch it); otherwise it goes on to what is under it */
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return swallow;
        touchedAt = SystemClock.elapsedRealtime();
        if (stage == 0) return false;
        wake();
        swallow = true;
        return true;
    }
    private boolean swallow;   // the rest of the gesture that woke the page

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (swallow) { if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) swallow = false; return true; }
        return e.getActionMasked() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
    }

    private void wake() {
        if (stage == 0) return;
        stage = 0;
        graph.live(graphRuns());
        ember.setVisibility(View.GONE);
        dim.animate().alpha(0f).setDuration(Motion.on() ? Motion.ENTER : 0).start();
        frameStats();   // the numbers it shows at once are now, not from when it went black
    }

    private float dimAlpha() { return dark ? 0.65f : 0.55f; }

    /**
     * Whether the graph draws on its own (30 a second): while the page is shown and lit, and the GPU has room. With the GPU 85 % busy or more for two readings
     * the graph is frugal: drawn with the numbers, 4 times a second, so a GPU-bound game does not give its frames to the page; back on its own under 75 %.
     */
    private boolean graphRuns() { return running && stage == 0 && !frugal; }

    /**
     * The page's clock: once a display frame, at most 30 a second, it moves the graph (while it runs on its own) and the rolling numbers, so they change in the
     * same frames and the page draws at most 30 times a second; it stops when neither has anything to do. The page took the shared GPU 1-2 ms a draw (median
     * and 90th percentile of dumpsys gfxinfo for the game's process, Kirby and the Forgotten Land on the AYN Thor, 2026-11-21): at 60 that was up to 12 % of it.
     */
    private static final long CLOCK_NS = 32_000_000L;
    private final List<Num> nums = new java.util.ArrayList<>();
    private boolean clockPosted;
    private long clockAt;
    private final Choreographer.FrameCallback clockCb = new Choreographer.FrameCallback() {
        @Override public void doFrame(long nowNs) {
            clockPosted = false;
            if (!running || !isAttachedToWindow()) return;
            boolean more = graphRuns() && graph.isAttachedToWindow();
            if (nowNs - clockAt >= CLOCK_NS) {
                clockAt = nowNs;
                if (more) graph.step(nowNs);
                for (Num n : nums) if (n.advance(nowNs)) more = true;
            } else for (Num n : nums) if (n.rolling) more = true;
            if (more) clock();
        }
    };

    /** Starts the clock if it is not running */
    private void clock() {
        if (clockPosted || !running || !isAttachedToWindow()) return;
        clockPosted = true;
        Choreographer.getInstance().postFrameCallback(clockCb);
    }
    private boolean frugal;
    private int busyRun;   // readings in a row on the other side of the line

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || !isAttachedToWindow()) return;
            if (stage < 2) {   // black: nothing to show, nothing read but the clock
                frameStats();
                if (frugal && stage == 0 && graph.isAttachedToWindow()) graph.step(System.nanoTime());   // the graph, frugal: with the numbers
                if (ticks % 8 == 0) slowStats();   // every 2 s (first: chips() reads the thermal level it leaves)
                if (ticks % 4 == 0) { chips(); live.setAlpha(live.getAlpha() > 0.6f ? 0.35f : 1f); }   // once a second
            } else if (ticks % 4 == 0) ember.setAlpha(ember.getAlpha() > 0.6f ? 0.3f : 1f);
            burnIn();
            ticks++;
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void frameStats() {
        int n;
        try { n = Math.min(Bridge.frameTimes(frames), WINDOW); } catch (UnsatisfiedLinkError e) { n = 0; }
        // the newest frame times unchanged a second long: the game is not presenting (a pause, a load); the numbers would be old
        float tail = n > 0 ? frames[n - 1] + frames[Math.max(0, n - 2)] * 7f : Float.NaN;
        stale = tail == lastTail ? stale + 1 : 0;
        lastTail = tail;
        boolean fresh = n > 0 && stale < 4;
        float rate = fresh ? GameStats.fps(frames, n) : Float.NaN;
        float med = n >= 10 ? FrameStats.median(frames, n) : Float.NaN;
        int aim = GameStats.target(med);
        // the target frames are measured against: the game's own rate when the median is near one (a game held at 25 fps by a slow phone would otherwise read
        // as on pace against its own median), else the median
        float tgt = aim > 0 ? 1000f / aim : med;
        fps.set(rate);
        target.setText(aim > 0 ? "/" + aim : "");
        paceNow = fresh ? GameStats.onPace(frames, n, PACE_FRAMES, tgt) : Float.NaN;
        pace.set(paceNow * 100f);
        pace.view.setTextColor(Float.isNaN(paceNow) ? text2 : paceNow >= 0.9f ? good : paceNow >= 0.75f ? warnYellow : warnRed);
        float ft = Float.isNaN(rate) ? Float.NaN : 1000f / rate;
        frame.set(ft);
        frame.view.setTextColor(Float.isNaN(ft) || Float.isNaN(tgt) ? text : warn(FrameStats.level(ft, tgt)));
        float low = fresh ? FrameStats.onePercentLowFps(frames, n) : Float.NaN;
        vLow.setText(Float.isNaN(low) ? "—" : String.format(Locale.ROOT, "%.0f", low));
        vRun.setText(GameStats.clock(SystemClock.elapsedRealtime() - startedAt));
        graph.aim(tgt, Float.isNaN(low) ? Float.NaN : 1000f / low);
        if (graph.longView) graphNote.setText(PUi.joined("last 30 s", "worst per ½ s"));
        else {
            int shownBars = Math.min(BARS, n);
            float span = 0f;
            for (int i = n - shownBars; i < n; i++) span += frames[i];
            graphNote.setText(shownBars == 0 ? "waiting for frames" : PUi.joined(String.format(Locale.ROOT, "last %.1f s", span / 1000f), "ms"));
        }
    }

    /** The GPU, the CPU, the console mode and the state pill, once a second */
    private void chips() {
        gpuBusy = device.gpuBusy();
        boolean other = frugal ? gpuBusy >= 0 && gpuBusy < 75 : gpuBusy >= 85;
        busyRun = other ? busyRun + 1 : 0;
        if (busyRun >= 2) { frugal = !frugal; busyRun = 0; graph.live(graphRuns()); }
        gpu.set(gpuBusy < 0 ? Float.NaN : gpuBusy);
        gpu.view.setTextColor(gpuBusy >= 95 ? warnYellow : text);
        if (gpuBusy > gpuPeak) gpuPeak = gpuBusy;
        mGpu.set(gpuBusy < 0 ? 0f : gpuBusy / 100f, gpuBusy >= 95 ? warnYellow : cGpu);
        mGpu.peak(gpuPeak < 0 ? -1f : gpuPeak / 100f);
        long cur = device.cpuKHz(), max = device.cpuMaxKHz();
        float cap = device.cpuCap();
        vCpu.setText(cur <= 0 ? "—" : unit(String.format(Locale.ROOT, "%.1f", cur / 1e6f), max > 0 ? String.format(Locale.ROOT, "/%.1fGHz", max / 1e6f) : "GHz"));
        // the cap (scaling_max_freq) is information, not a warning: a performance mode sets it as well as heat (slowStats)
        ((TextView) vCpu.getTag()).setText(!Float.isNaN(cap) && cap < 0.99f ? "CPU CAP " + Math.round(cap * 100) + "%" : "CPU");
        mCpu.set(cur > 0 && max > 0 ? (float) cur / max : 0f, cCpu);
        int mode = Bridge.opModeSafe();   // the in-game panel can change it while the game runs
        modeText.setText(mode == 1 ? "DOCKED" : mode == 0 ? "HANDHELD" : "LIVE");
        state();
    }

    /** The state pill (GameStats.cause). A new cause shows once it has held two readings in a row, so a single slow second does not flash it */
    private void state() {
        int now = GameStats.cause(thermal, gpuBusy, paceNow);
        boolean held = now == causeSeen;
        causeSeen = now;
        if (now == cause || (!held && cause != -1)) return;
        cause = now;
        View p = (View) thrText.getParent();
        p.setVisibility(now < 0 || !shown.contains(StatsConfig.PILLS) ? View.GONE : View.VISIBLE);
        if (now < 0) return;
        thrText.setText(now == GameStats.COOL ? "COOL" : now == GameStats.WARM ? "WARM" : now == GameStats.THROTTLING ? "THROTTLING" : "GPU-BOUND");
        int col = now == GameStats.COOL ? good : now == GameStats.THROTTLING ? warnRed : warnYellow;
        thrDot.setBackground(PUi.round(col, PUi.dp(getContext(), 3), 0, 0));
    }

    private void slowStats() {
        Context c = getContext();
        String d = Hud.driverName();
        driver.setText(d == null ? "" : d.toUpperCase(Locale.ROOT));
        ((View) driver.getParent()).setVisibility(d == null || !shown.contains(StatsConfig.PILLS) ? View.GONE : View.VISIBLE);

        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        int th = pm != null ? pm.getCurrentThermalStatus() : -1;
        if (pm != null) { float h = pm.getThermalHeadroom(10); if (!Float.isNaN(h)) headroom = h; }   // NaN when asked again within about a second (the overlay asks too): the last one
        // Android's thermal signals only. Overlay's dot also counts a CPU cap below 85 % as throttling, but on the AYN Thor the main cluster sits at 64 % of its
        // top clock in the default performance mode with thermal status 0 (seen 2026-11-21): a cap is a mode as often as heat, so the CPU tile shows it instead
        thermal = GameStats.throttle(th, headroom, Float.NaN);

        int t = SocTemp.celsius();
        if (t != SocTemp.NONE && (socPeak == SocTemp.NONE || t > socPeak)) socPeak = t;
        soc.set(t == SocTemp.NONE ? Float.NaN : t);
        int socLv = t == SocTemp.NONE ? 0 : t >= 80 ? 2 : t >= 70 ? 1 : 0;
        soc.view.setTextColor(warn(socLv));
        mSoc.set(t == SocTemp.NONE ? 0f : t / 100f, socLv > 0 ? warn(socLv) : cSoc);
        mSoc.peak(socPeak == SocTemp.NONE ? -1f : socPeak / 100f);
        // the peak named when it is 3 degrees above now: a run that warmed and cooled again
        ((TextView) soc.view.getTag()).setText(t != SocTemp.NONE && socPeak >= t + 3 ? "SOC ▲" + socPeak + "°" : "SOC");

        battery(c);
        memory(c);
    }

    /**
     * The power, the level and the time left. The current (BATTERY_PROPERTY_CURRENT_NOW) and the voltage give watts at once, the tile's value; the time left
     * is the charge counter over the current, averaged over the last readings. While charging the watts are what goes into the battery (+): what the phone
     * uses then does not show in the battery's current. Where the current does not read, the value is the level and the level's fall an hour gives the time
     * left (GameStats.drainPerHour: after 5 minutes and a fall of 2 points).
     */
    private void battery(Context c) {
        TextView lab = (TextView) vBat.getTag();
        Intent b;
        try { b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)); } catch (RuntimeException e) { b = null; }
        int level = -1, mv = -1;
        float temp = Float.NaN;
        boolean charging = false;
        if (b != null) {
            int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100), tc = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            level = l >= 0 && sc > 0 ? l * 100 / sc : -1;
            temp = tc != Integer.MIN_VALUE ? tc / 10f : Float.NaN;
            mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL || b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        }
        if (level < 0) { watts.set(Float.NaN); lab.setText("BATTERY"); mBat.set(0f, good); return; }
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        float amps = bm == null ? Float.NaN : GameStats.amps(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        long charge = bm == null ? -1 : bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
        long now = SystemClock.elapsedRealtime();
        if (charging != wasCharging) ampsAvg = Float.NaN;   // charging and draining are two different currents
        if (charging != wasCharging || batFrom < 0 || level > batFrom) { batFrom = level; batFromAt = now; }
        wasCharging = charging;
        if (!Float.isNaN(amps)) ampsAvg = Float.isNaN(ampsAvg) ? amps : ampsAvg + (amps - ampsAvg) * 0.3f;   // about 6 s to settle
        float w = GameStats.watts(ampsAvg, mv);
        mBat.set(level / 100f, level <= 15 ? warnRed : level <= 30 ? warnYellow : good);
        int left = charging ? -1 : GameStats.minutesLeftAt(charge, ampsAvg);
        if (!charging && left < 0) left = GameStats.minutesLeft(level, GameStats.drainPerHour(batFrom, batFromAt, level, now));
        String until = charging || left < 0 ? "" : GameStats.shortDuration(left);
        if (Float.isNaN(w)) {   // no current on this phone: the level is the value, as before
            watts.clear(level + "%");
            lab.setText(charging ? "CHARGING" : left < 0 ? "BATTERY" : PUi.joined("BAT", until));
        } else {
            watts.set(w);
            lab.setText(charging ? level + "% ⚡︎" : PUi.joined(level + "%", until));
        }
        watts.view.setTextColor(warn(FrameStats.batteryLevel(temp)));   // the battery's temperature colours it
    }

    private void memory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.totalMem <= 0) { vRam.setText("—"); return; }
        long used = mi.totalMem - mi.availMem;
        // the game's own: its CPU side and its GPU side (DeviceStats); the phone's RAM in use behind it on the meter
        int pid = android.os.Process.myPid();   // this page runs in the game's process
        long cpu = device.appCpuBytes(pid), gpuMem = device.appGpuBytes(pid);
        long game = cpu < 0 ? -1 : cpu + Math.max(0, gpuMem);
        if (game > ramPeak) ramPeak = game;
        vRam.setText(game < 0 ? unit(String.format(Locale.ROOT, "%.1f", used / GIB), String.format(Locale.ROOT, "/%.0fGB", mi.totalMem / GIB))
                : unit(String.format(Locale.ROOT, "%.1f", game / GIB), "GB"));
        vRam.setContentDescription(String.format(Locale.ROOT, "Game RAM %.1f GB, at most %.1f GB this run; the phone %.1f of %.1f GB in use",
                Math.max(0, game) / GIB, Math.max(0, ramPeak) / GIB, used / GIB, mi.totalMem / GIB));
        // the value is the game's; the label the phone's RAM in use over all of it, coloured when it runs short (the run's peak is the meter's tick)
        int lv = FrameStats.ramLevel(used, mi.totalMem, mi.lowMemory);
        TextView lab = (TextView) vRam.getTag();
        lab.setText(game < 0 ? "RAM" : String.format(Locale.ROOT, "%.1f/%.0fGB", used / GIB, mi.totalMem / GIB));
        lab.setTextColor(game >= 0 && lv > 0 ? warn(lv) : labelCol);
        vRam.setTextColor(game < 0 ? warn(lv) : text);
        int col = lv > 0 ? warn(lv) : cMem;
        mRam.set(game < 0 ? (float) used / mi.totalMem : (float) game / mi.totalMem, col, (float) used / mi.totalMem);
        mRam.peak(game < 0 ? -1f : (float) ramPeak / mi.totalMem);
        if (gpuMem > 0) ((TextView) gpu.view.getTag()).setText(String.format(Locale.ROOT, "VRAM %.1fGB", gpuMem / GIB));
    }

    /** The pixel shifter and the dim and black stages after a while without a touch */
    private void burnIn() {
        long now = SystemClock.elapsedRealtime();
        int[] at = shift ? GameStats.orbit(now - startedAt) : new int[] {0, 0};
        if (content.getTranslationX() != at[0] || content.getTranslationY() != at[1]) { content.setTranslationX(at[0]); content.setTranslationY(at[1]); }
        if (sheetOpen) return;   // the sheet is being read
        long idle = now - touchedAt;
        if (stage < 2 && autoOff && idle >= GameStats.OFF_MS) {
            stage = 2;
            graph.live(false);
            dim.animate().alpha(1f).setDuration(Motion.on() ? 1500 : 0).start();
            ember.setVisibility(View.VISIBLE);
        } else if (stage == 0 && autoDim && idle >= GameStats.DIM_MS) {
            stage = 1;
            graph.live(false);   // dimmed: the graph holds still, no draw a display frame
            dim.animate().alpha(dimAlpha()).setDuration(Motion.on() ? 1500 : 0).start();
        }
        if (stage == 2 && ticks % 240 == 0) {   // the dot moves once a minute, somewhere in the middle of the page
            int w = getWidth(), h = getHeight(), s = ember.getWidth();
            if (w > 0 && h > 0) {
                java.util.Random r = new java.util.Random(now);
                ember.setTranslationX(w * 0.2f + r.nextFloat() * (w * 0.6f - s));
                ember.setTranslationY(h * 0.2f + r.nextFloat() * (h * 0.6f - s));
            }
        }
    }

    // ---- the settings sheet ----

    private void openSheet(boolean on) {
        sheetOpen = on;
        sheet.setVisibility(on ? View.VISIBLE : View.GONE);
        touchedAt = SystemClock.elapsedRealtime();
    }

    /** Presets, the items shown and the burn-in guards; each change is kept at once (PeanutsPrefs) and the page made again behind the sheet */
    private View sheet(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(blend(black ? Color.BLACK : PUi.col(c, R.color.bn_bg), Color.BLACK, 0.1f) & 0xF7FFFFFF);
        f.setClickable(true);   // touches stop here, not on the card under it
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = column(c);
        int pad = PUi.dp(c, 16);
        col.setPadding(pad + PUi.dp(c, 2), pad, pad + PUi.dp(c, 2), pad);
        LinearLayout head = PUi.row(c);
        head.addView(PUi.text(c, "Bottom screen stats", 17, R.color.bn_text, 800), PUi.weight());
        ImageView close = PUi.iconButton(c, R.drawable.ic_close, "Close", PUi.Kind.QUIET, v -> { Haptics.play(v, Haptics.Word.TICK); openSheet(false); });
        close.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(c, 36), PUi.dp(c, 36)));
        head.addView(close);
        col.addView(head);

        PUi.add(col, section(c, "PRESET"), 8);
        LinearLayout seg = PUi.row(c);
        String now = StatsConfig.match(shown);
        String[] names = {"Simple", "Normal", "Full", "Custom"};
        String[] keys = {StatsConfig.SIMPLE, StatsConfig.NORMAL, StatsConfig.FULL, StatsConfig.CUSTOM};
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            boolean on = k.equals(now);
            TextView b = PUi.text(c, names[i], 13, on ? R.color.bn_on_banana : R.color.bn_text, 700);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, PUi.dp(c, 8), 0, PUi.dp(c, 8));
            b.setBackground(PUi.round(on ? PUi.col(c, R.color.bn_banana) : track, PUi.dp(c, 10), 0, 0));
            if (k.equals(StatsConfig.CUSTOM)) { if (!on) b.setAlpha(0.45f); }   // Custom is what a change by hand makes, not a choice
            else b.setOnClickListener(v -> { Haptics.play(v, Haptics.Word.CLICK); setShown(StatsConfig.preset(k)); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = PUi.dp(c, 6);
            seg.addView(b, lp);
        }
        PUi.add(col, seg, 6);

        PUi.add(col, section(c, "SHOWN"), 12);
        LinearLayout grid = column(c);
        for (int i = 0; i < StatsConfig.ITEMS.length; i++) {
            String k = StatsConfig.ITEMS[i];
            addToggle(c, grid, StatsConfig.NAMES[i], shown.contains(k), v -> {
                java.util.Set<String> s = new java.util.LinkedHashSet<>(shown);
                if (!s.remove(k)) s.add(k);
                setShown(s);
            });
        }
        PUi.add(col, grid, 6);

        PUi.add(col, section(c, "OLED / BURN-IN"), 12);
        LinearLayout guards = column(c);
        for (int i = 0; i < StatsConfig.GUARDS.length; i++) {
            String k = StatsConfig.GUARDS[i];
            addToggle(c, guards, StatsConfig.GUARD_NAMES[i], PeanutsPrefs.statsGuard(getContext(), k), v -> {
                PeanutsPrefs.statsGuard(getContext(), k, !PeanutsPrefs.statsGuard(getContext(), k));
                build();
            });
        }
        PUi.add(col, guards, 6);
        TextView note = PUi.small(c, "Kept for every game. A touch wakes a dimmed or black screen.");
        note.setTextColor(labelCol);
        PUi.add(col, note, 10);
        sv.addView(col);
        f.addView(sv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    private void setShown(Set<String> s) {
        PeanutsPrefs.statsShown(getContext(), StatsConfig.format(s));
        build();
    }

    private TextView section(Context c, String s) {
        TextView t = label(c, s);
        t.setTextColor(labelCol);
        return t;
    }

    /** A toggle into a grid of three a row: its name and a small switch */
    private void addToggle(Context c, LinearLayout grid, String label, boolean on, View.OnClickListener l) {
        LinearLayout row = grid.getChildCount() == 0 ? null : (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        if (row == null || row.getChildCount() == 3) {
            row = PUi.row(c);
            PUi.add(grid, row, 6);
        }
        LinearLayout t = PUi.row(c);
        t.setPadding(PUi.dp(c, 10), PUi.dp(c, 7), PUi.dp(c, 8), PUi.dp(c, 7));
        t.setBackground(PUi.ripple(c, PUi.round(track, PUi.dp(c, 9), 0, 0)));
        TextView n = PUi.text(c, label, 12, R.color.bn_text, 600);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(n, PUi.weight());
        t.addView(new Switch(c, on), new LinearLayout.LayoutParams(PUi.dp(c, 26), PUi.dp(c, 15)));
        t.setOnClickListener(v -> { Haptics.play(v, Haptics.Word.CLICK); l.onClick(v); });
        t.setContentDescription(label + (on ? ", on" : ", off"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (row.getChildCount() > 0) lp.leftMargin = PUi.dp(c, 6);
        row.addView(t, lp);
        if (row.getChildCount() < 3 && grid.getChildCount() > 0) row.setWeightSum(3);   // a last row of fewer keeps the toggles' width
    }

    /** The sheet's small switch: a track and a thumb, the banana when on */
    private final class Switch extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final boolean on;
        Switch(Context c, boolean on) { super(c); this.on = on; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), g = h * 0.15f;
            p.setColor(on ? 0xFFFFD43B : blend(track, text2, 0.25f));
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, p);
            p.setColor(on ? 0xFF3B2A1A : text2);
            float d = h - 2 * g, x = on ? w - g - d : g;
            r.set(x, g, x + d, g + d);
            c.drawOval(r, p);
        }
    }

    // ---- helpers ----

    /** A group colour as it is on the dark theme; on the light one 35 % darker, so the pastel reads on cream */
    private int tone(int color) {
        if (dark) return color;
        return blend(color, Color.BLACK, 0.35f);
    }

    /** a moved towards b by t (0..1), opaque */
    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t), g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private int warn(int level) { return level >= 2 ? warnRed : level == 1 ? warnYellow : text; }

    /** A value with its unit smaller and quieter */
    private CharSequence unit(String value, String unit) {
        SpannableStringBuilder s = new SpannableStringBuilder(value);
        if (unit.isEmpty()) return s;
        int a = s.length();
        s.append(unit);
        s.setSpan(new RelativeSizeSpan(0.7f), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(text2), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** "╌╌ target  ── 1% low", each mark in its line's colour */
    private CharSequence legendText() {
        SpannableStringBuilder s = new SpannableStringBuilder();
        s.append("╌╌ target  ");
        s.setSpan(new ForegroundColorSpan(labelCol), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int a = s.length();
        s.append("──");
        s.setSpan(new ForegroundColorSpan(cFps), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int b = s.length();
        s.append(" 1% low");
        s.setSpan(new ForegroundColorSpan(labelCol), b, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /**
     * One stat tile, kept by its item (laid into a row by build): the value, a coloured dot and the label, a meter when given. Returns the value; its tag is
     * the label (the battery's, the CPU's and the RAM's change)
     */
    private TextView tileView(Context c, String key, String labelText, int dotColor, Meter meter) {
        LinearLayout t = column(c);
        int p = bigTiles ? 14 : 8;   // Simple's tiles: larger, to read at a glance
        t.setPadding(PUi.dp(c, p), PUi.dp(c, p + 1), PUi.dp(c, p), PUi.dp(c, p));
        t.setBackground(PUi.round(tileBg, PUi.dp(c, 10), 0, 0));
        TextView v = PUi.text(c, "—", bigTiles ? 26 : 16, R.color.bn_text, 700);
        v.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        v.setSingleLine(true);
        t.addView(v);
        LinearLayout l = PUi.row(c);
        View d = dot(c, dotColor);
        if (bigTiles) { d.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(c, 8), PUi.dp(c, 8))); d.setBackground(PUi.round(dotColor, PUi.dp(c, 4), 0, 0)); }
        l.addView(d);
        TextView lab = label(c, labelText);
        if (bigTiles) lab.setTextSize(12);
        l.addView(lab, gapLeft(c, bigTiles ? 6 : 5));
        PUi.add(t, l, bigTiles ? 2 : 1);
        v.setTag(lab);
        if (meter != null) PUi.add(t, meter, bigTiles ? 8 : 6, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(c, bigTiles ? 5 : 3)));
        tiles.put(key, t);
        return v;
    }

    private static View dot(Context c, int color) {
        View d = new View(c);
        d.setBackground(PUi.round(color, PUi.dp(c, 3), 0, 0));
        d.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(c, 6), PUi.dp(c, 6)));
        return d;
    }

    private static LinearLayout.LayoutParams gapLeft(Context c, int dp) {
        LinearLayout.LayoutParams lp = PUi.wrap();
        lp.leftMargin = PUi.dp(c, dp);
        return lp;
    }

    private TextView label(Context c, String s) {
        TextView l = PUi.text(c, s, 11, R.color.bn_text_2, 600);
        l.setLetterSpacing(0.08f);
        l.setSingleLine(true);
        l.setTextColor(labelCol);
        return l;
    }

    /** A rounded chip; with a dot, the dot and a label (child 1) */
    private LinearLayout pill(Context c, View dot, String s) {
        LinearLayout p = PUi.row(c);
        p.setBackground(PUi.round(track, PUi.dp(c, 999), 0, 0));
        p.setPadding(PUi.dp(c, 8), PUi.dp(c, 3), PUi.dp(c, 8), PUi.dp(c, 3));
        if (dot != null) { p.addView(dot); p.addView(pillText(c, s), gapLeft(c, 5)); }
        return p;
    }

    private TextView pillText(Context c, String s) {
        TextView t = PUi.text(c, s, 11, R.color.bn_text_2, 600);
        t.setLetterSpacing(0.06f);
        t.setSingleLine(true);
        t.setTextColor(text2);   // the pills change, so not dimmed with the fixed labels
        return t;
    }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /**
     * A number that rolls to its next value (about 0.3 s, eased out) instead of jumping, as a speedometer: its view shows fmt of the value in between, moved on
     * by the page's clock (clock: 30 a second, with the graph, so the two draw in the same frames). It rolls only for a change of at least {@code step} (a
     * small one is written at once) and not while the GPU is busy (frugal) or the page is dimmed, and its view is written only when its text changes. Rolling on its own animator
     * at every 4 Hz reading kept the page drawing 55-60 times a second (dumpsys gfxinfo, 2026-11-21). NaN shows the dash at once; under reduced motion
     * (Motion.on false) every value is set at once.
     */
    private final class Num {
        final TextView view;
        private final java.util.function.Function<Float, CharSequence> fmt;
        private final String none;
        private final float step;
        private float shown = Float.NaN, from, to = Float.NaN;
        private long startNs;
        private boolean rolling;

        Num(TextView view, java.util.function.Function<Float, CharSequence> fmt, String none, float step) {
            this.view = view; this.fmt = fmt; this.none = none; this.step = step;
            view.setText(none);
            nums.add(this);
        }

        void set(float v) {
            if (Float.isNaN(v)) { rolling = false; shown = Float.NaN; write(none); return; }
            if (rolling && Math.abs(v - to) < step) { to = v; return; }   // rolling already, to about this: it lands there
            if (Float.isNaN(shown) || !Motion.on() || frugal || stage != 0 || Math.abs(v - shown) < step) { rolling = false; shown = v; write(fmt.apply(v)); return; }
            from = shown;
            to = v;
            startNs = System.nanoTime();
            rolling = true;
            clock();
        }

        /** One step of the roll at the clock's time; true while it is still rolling */
        boolean advance(long nowNs) {
            if (!rolling) return false;
            float k = Math.min(1f, (nowNs - startNs) / 300e6f), e = 1f - (1f - k) * (1f - k);   // eased out
            shown = from + (to - from) * e;
            write(fmt.apply(k >= 1f ? to : shown));
            if (k >= 1f) { shown = to; rolling = false; }
            return rolling;
        }

        /** The view's text, set only when it is other text (a TextView draws again at every setText) */
        private void write(CharSequence s) {
            CharSequence now = view.getText();
            if (now == null || !TextUtils.equals(now.toString(), s.toString())) view.setText(s);
        }

        /** Shows other text (the battery's level where there is no current) and forgets the value, so the next one does not roll from it */
        void clear(CharSequence s) { rolling = false; shown = Float.NaN; write(s); }

        void stop() { rolling = false; }
    }

    /** A thin bar: a share of its width in a colour, on the track; a fainter share behind it, and a tick at the run's peak */
    private final class Meter extends View {
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), under = new Paint(Paint.ANTI_ALIAS_FLAG),
                mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float share, behind, top = -1f;

        Meter(Context c) {
            super(c);
            back.setColor(track);
            mark.setColor(text);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(float s, int color) { set(s, color, 0f); }

        /** s in color over {@code b} (a larger share, the same colour faint behind it: the game's RAM over the phone's in use) */
        void set(float s, int color, float b) {
            s = Math.max(0f, Math.min(1f, s));
            b = Math.max(0f, Math.min(1f, b));
            if (s == share && b == behind && fill.getColor() == color) return;
            share = s;
            behind = b;
            fill.setColor(color);
            under.setColor(color);
            under.setAlpha(80);
            invalidate();
        }

        /** The run's peak as a share (a tick on the bar where it is above now); -1 none */
        void peak(float p) {
            p = p < 0f ? -1f : Math.min(1f, p);
            if (p == top) return;
            top = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            c.drawRoundRect(r, h / 2, h / 2, back);
            if (behind > share) { r.set(0, 0, w * behind, h); c.drawRoundRect(r, h / 2, h / 2, under); }
            if (share > 0f) { r.set(0, 0, w * share, h); c.drawRoundRect(r, h / 2, h / 2, fill); }
            if (top > share + 0.01f) {   // the peak, above where the bar is now
                float half = PUi.dp(getContext(), 1), x = Math.max(half, Math.min(w - half, w * top));
                r.set(x - half, 0, x + half, h);
                c.drawRect(r, mark);
            }
        }
    }

    /**
     * The frame times as bars, coloured against the target (FrameStats.level), with the target (dashed), the 1% low (blue) and the scale's numbers on the
     * right. It runs on the page's clock (at most 30 a second, clock), so it scrolls smoothly; the newest frames are read from the host at each step (64
     * values, Bridge.frameTimes) and matched to the last read (GameStats.newFrames).
     * <ul>
     * <li>2 s (the default): the newest 60 frames, one bar each. Each new frame comes in at the right edge and slides one slot left over about one frame time.</li>
     * <li>30 s (a tap): the worst frame of each half second (GameStats.worst), from its own history of the frames' end times (the host keeps 512 frames: 17 s at
     *     30 fps). The bars slide with the clock.</li>
     * </ul>
     * The older bars fade towards the left; the scale and the lines ease to new values instead of jumping. It stops while the page is not running, is dimmed or
     * black, or is not shown (no draw a display frame).
     */
    private final class Graph extends View {
        private static final int READ = 64, HISTORY = 4096;
        private static final long BUCKET_NS = 500_000_000L, LONG_NS = 30_000_000_000L;
        private static final int BUCKETS = (int) (LONG_NS / BUCKET_NS);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), lowLine = new Paint(Paint.ANTI_ALIAS_FLAG),
                num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float[] ms = new float[BARS + 1];   // oldest first; one more than fits, the one sliding out on the left
        private final float[] read = new float[READ], last = new float[READ];
        private final float[] histMs = new float[HISTORY];   // the 30 s view's history: each frame's time and when it ended, oldest first
        private final long[] histEnd = new long[HISTORY];
        private final float[] worst = new float[BUCKETS + 1];
        private final int normal;
        private final float labelW;
        private int n, lastN, hist;
        private long arrivedNs, drawnNs, prevNs;
        private float pace = 33.3f;   // the newest frames' time, smoothed: how long the slide takes
        private float target = Float.NaN, scale = Float.NaN, low = Float.NaN;   // eased, ms
        private float aimTo = Float.NaN, lowTo = Float.NaN;
        boolean longView;

        Graph(Context c) {
            super(c);
            normal = PUi.col(c, R.color.bn_accent);
            line.setColor(text2);
            line.setAlpha(150);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(PUi.dp(c, 1));
            line.setPathEffect(new DashPathEffect(new float[] {PUi.dp(c, 3), PUi.dp(c, 3)}, 0));
            lowLine.setColor(cFps);
            lowLine.setAlpha(190);
            lowLine.setStrokeWidth(PUi.dp(c, 1));
            num.setTypeface(Typeface.MONOSPACE);
            num.setTextSize(PUi.dp(c, 11));
            num.setTextAlign(Paint.Align.RIGHT);
            labelW = num.measureText("000") + PUi.dp(c, 6);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** Runs on the page's clock while on is true (graphRuns); the clock stops by itself when nothing moves */
        void live(boolean on) { if (on) clock(); }

        /** 2 s / 30 s */
        void toggle() { longView = !longView; invalidate(); }

        /** The target the bars are coloured against (the game's rate, else the median frame time) and the 1% low's frame time, both ms (NaN: none yet), from StatsView's tick */
        void aim(float targetMs, float lowMs) { aimTo = targetMs; lowTo = lowMs; }

        /** One read and draw: from the page's clock, or from its tick while the GPU is busy (frugal: the graph does not run on the clock then) */
        void step(long nowNs) {
            drawnNs = nowNs;
            int m;
            try { m = Bridge.frameTimes(read); } catch (UnsatisfiedLinkError e) { m = 0; }
            int k = GameStats.newFrames(last, lastN, read, m);
            if (k > 0) {
                boolean first = lastN == 0 || k == m;
                push(read, m - k, k);
                remember(read, m - k, k, nowNs);
                if (!first) arrivedNs = nowNs;   // a fresh history (the first read, a reset) is not slid in
                pace += (read[m - 1] - pace) * 0.25f;
            }
            System.arraycopy(read, 0, last, 0, m);
            lastN = m;
            float dt = prevNs == 0 ? 1f : 1f - (float) Math.exp(-(nowNs - prevNs) / 250e6);   // about a quarter second to settle
            prevNs = nowNs;
            if (aimTo > 0f) target = Float.isNaN(target) ? aimTo : target + (aimTo - target) * dt;
            float want = (target > 0f ? target : 33.3f) * 2.1f;
            scale = Float.isNaN(scale) ? want : scale + (want - scale) * dt;
            if (lowTo > 0f) low = Float.isNaN(low) ? lowTo : low + (lowTo - low) * dt;
            else low = Float.NaN;
            invalidate();
        }

        private void push(float[] src, int from, int count) {
            int keep = Math.max(0, Math.min(n, ms.length - count));
            System.arraycopy(ms, n - keep, ms, 0, keep);
            int add = Math.min(count, ms.length);
            System.arraycopy(src, from + count - add, ms, keep, add);
            n = keep + add;
        }

        /** The 30 s history: the new frames, the newest ending now and each one before it ending when the next began */
        private void remember(float[] src, int from, int count, long nowNs) {
            if (hist + count > HISTORY) {   // full: the older half goes (more than 30 s even at 60 fps)
                int drop = Math.min(hist, Math.max(hist / 2, hist + count - HISTORY));
                System.arraycopy(histMs, drop, histMs, 0, hist - drop);
                System.arraycopy(histEnd, drop, histEnd, 0, hist - drop);
                hist -= drop;
            }
            count = Math.min(count, HISTORY - hist);
            long end = nowNs;
            for (int i = count - 1; i >= 0; i--) {
                histMs[hist + i] = src[from + i];
                histEnd[hist + i] = end;
                end -= (long) (src[from + i] * 1e6f);
            }
            hist += count;
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth() - labelW;   // the bars' width; the scale's numbers on the right
            float t = target > 0 ? target : 33.3f, top = scale > 0 ? scale : t * 2.1f;
            float slot = w / BARS, bw = Math.max(1f, slot * 0.7f), rad = Math.min(bw / 2, PUi.dp(getContext(), 1.2f)), fade = w * 0.42f;
            c.save();
            c.clipRect(0, 0, w, h);
            if (low > 0) { float y = h - Math.min(h, low / top * h); c.drawLine(0, y, w, y, lowLine); }
            if (longView) {
                long now = System.nanoTime(), first = Math.floorDiv(now, BUCKET_NS) - BUCKETS;   // the newest bucket is the one filling now
                GameStats.worst(histMs, histEnd, hist, first, BUCKETS + 1, BUCKET_NS, worst);
                float bucketW = w * BUCKET_NS / LONG_NS, lw = bucketW * 0.7f;
                for (int j = 0; j <= BUCKETS; j++) {
                    if (Float.isNaN(worst[j])) continue;
                    long start = (first + j) * BUCKET_NS;   // the bucket's place follows the clock: the bars slide continuously
                    float x = w - (now - start) / (float) LONG_NS * w + (bucketW - lw) / 2;
                    drawBar(c, x, lw, rad, worst[j], t, top, h, fade);
                }
            } else {
                // the slide: 0 when the newest frame just came (it sits just past the right edge), 1 once a frame time has passed (it is in place)
                float phase = arrivedNs == 0 ? 1f : Math.min(1f, (System.nanoTime() - arrivedNs) / (Math.max(8f, pace) * 1e6f));
                phase = 1f - (1f - phase) * (1f - phase);   // eased out
                float right = w + slot * (1f - phase);
                for (int i = 0; i < n; i++) drawBar(c, right - (n - i) * slot + (slot - bw) / 2, bw, rad, ms[i], t, top, h, fade);
            }
            float ty = h - t / top * h;
            c.drawLine(0, ty, w, ty, line);
            c.restore();
            // the scale: its top, the target and 0
            float x = getWidth(), asc = -num.ascent();
            num.setColor(labelCol);
            c.drawText(String.format(Locale.ROOT, "%.0f", top), x, asc, num);
            c.drawText("0", x, h, num);
            num.setColor(text);
            c.drawText(String.format(Locale.ROOT, "%.0f", t), x, Math.max(asc, Math.min(h - asc, ty + asc / 2 - 1)), num);
        }

        private void drawBar(Canvas c, float x, float bw, float rad, float v, float t, float top, float h, float fade) {
            if (x + bw < 0) return;
            float bh = Math.max(PUi.dp(getContext(), 1), Math.min(h, v / top * h));
            int lv = FrameStats.level(v, t);
            bar.setColor(lv == 0 ? normal : lv == 1 ? warnYellow : warnRed);
            bar.setAlpha(Math.round(255 * Math.max(0.12f, Math.min(1f, x / fade))));   // the older bars fade towards the left
            r.set(x, h - bh, x + bw, h);
            c.drawRoundRect(r, rad, rad, bar);
        }
    }

    /** At most max wide, centred; tall: the child as tall as this */
    private static final class Capped extends FrameLayout {
        private final int max;
        Capped(Context c, int max, View child, boolean tall) {
            super(c);
            this.max = max;
            setClipChildren(false);
            addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tall ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        @Override protected void onMeasure(int w, int h) {
            int size = MeasureSpec.getSize(w);
            if (size > max) w = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
            super.onMeasure(w, h);
        }
    }
}
