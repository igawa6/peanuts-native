package dev.peanuts.ui;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

import dev.nxnative.android.BananaBackgroundView;
import dev.nxnative.android.BuildConfig;
import dev.nxnative.android.R;

/**
 * The bottom screen's window: logo, game card, build and start pages, built once and updated in place.
 * Compact column on small panels, cover beside the text from 600 dp landscape.
 */
final class BottomPanel extends Presentation {
    static final int LOGO = 0, CARD = 1, BUILD = 2, LAUNCH = 3;
    private static final float DIM = 0.5f;

    private FrameLayout root, stage;
    private BananaBackgroundView bg;
    private View scrim, hint;
    private boolean dimmed, active = true, pad = true;
    private Button settingsBtn;
    private int page = -1;
    private View[] pages;
    private boolean wide, tall;
    private int coverDp;
    private final int madeW, madeH, madeDpi;

    // card page
    private LinearLayout cardBox;
    private TextView cardTile, cardName, cardState, cardLine1, cardLine2;
    private LinearLayout cardMark, cardLine1Row;
    private View cardBadge;
    private PUi.Action cardPrimary, cardSecond;
    private BottomScreen.Card shownCard, slidingTo;

    // build page
    private TextView buildTile, buildName, buildStatus, buildBody, buildPct;
    private ProgressBar buildBar;
    private PUi.Action buildStop, buildOff, buildOpen;
    private BottomScreen.BuildInfo shownBuild;

    // start page
    private StartView start;
    private String launchId;

    @SuppressWarnings("deprecation")   // getRealMetrics
    BottomPanel(Activity owner, Display display) {
        super(owner, display, R.style.Theme_Banana);
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        display.getRealMetrics(m);
        madeW = m.widthPixels; madeH = m.heightPixels; madeDpi = m.densityDpi;
    }

    int page() { return page; }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);   // the controller stays on top
        getWindow().setDecorFitsSystemWindows(false);                            // no black band for system bars
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(PUi.col(getContext(), R.color.bn_bg)));
        Context c = getContext();
        root = new FrameLayout(c);
        root.setBackgroundColor(PUi.col(c, R.color.bn_bg));
        bg = new BananaBackgroundView(c);
        root.addView(bg, fill());
        stage = new FrameLayout(c);
        root.addView(stage, fill());
        scrim = new View(c);
        scrim.setBackgroundColor(0xFF000000);
        scrim.setClickable(true);   // swallows taps while dimmed
        scrim.setAlpha(dimmed ? DIM : 0f);
        scrim.setVisibility(dimmed ? View.VISIBLE : View.GONE);
        root.addView(scrim, fill());
        setContentView(root);
        build();
    }

    @Override protected void onStart() { super.onStart(); setActive(active); }
    @Override protected void onStop() { if (bg != null) bg.setRunning(false); if (start != null) start.setAnimating(false); super.onStop(); }

    /** Off while hidden or the display is off: hide() keeps the window, and its animations would keep running */
    void setActive(boolean on) {
        active = on;
        if (bg != null) bg.setRunning(on);
        if (start != null) start.setAnimating(on && page == LAUNCH);
    }

    /** Dim while a dialog or sheet on top has the controller */
    void dim(boolean on) {
        if (on == dimmed) return;
        dimmed = on;
        if (scrim == null) return;
        scrim.animate().cancel();
        if (on) scrim.setVisibility(View.VISIBLE);
        float to = on ? DIM : 0f;
        if (!Motion.on() || !scrim.isShown()) { scrim.setAlpha(to); if (!on) scrim.setVisibility(View.GONE); return; }
        scrim.animate().alpha(to).setDuration(on ? Motion.FOCUS : Motion.SNAP).setInterpolator(Motion.OUT)
                .withEndAction(() -> { if (!dimmed) scrim.setVisibility(View.GONE); });
    }

    /** False after a size or density change: Android cancels a Presentation then, so BottomScreen makes a new one */
    @SuppressWarnings("deprecation")   // getRealMetrics
    boolean fits() {
        Display d = getDisplay();
        if (d == null || !d.isValid()) return false;
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        d.getRealMetrics(m);
        return m.widthPixels == madeW && m.heightPixels == madeH && m.densityDpi == madeDpi;
    }

    /** Back after a game: the content fades in */
    void appear() {
        if (stage == null) return;
        for (View v : new View[] {stage, hint}) {
            v.animate().cancel();
            if (Motion.on()) { v.setAlpha(0f); v.animate().alpha(1f).setDuration(Motion.ENTER).setInterpolator(Motion.OUT); }
            else v.setAlpha(1f);
        }
    }

    /** The game took over: the content fades out, then {@code after} */
    void fadeOut(Runnable after) {
        if (stage == null || !Motion.on() || !stage.isShown()) { after.run(); return; }
        hint.animate().cancel();
        hint.animate().alpha(0f).setDuration(Motion.EXIT);
        stage.animate().cancel();
        stage.animate().alpha(0f).setDuration(Motion.EXIT * 2).setInterpolator(Motion.IN).withEndAction(after);
    }

    // ---- Layout ----

    private void build() {
        Context c = getContext();
        Configuration cf = c.getResources().getConfiguration();
        int w = cf.screenWidthDp, h = cf.screenHeightDp;
        wide = w >= 600 && w >= h * 1.25f;
        tall = h >= 340;
        coverDp = wide ? clamp(Math.round(h * 0.42f), 120, 280) : clamp(Math.round(h * 0.25f), 72, 120);
        stage.removeAllViews();
        start = new StartView(c);
        pages = new View[] {page(logoPage()), page(cardPage()), page(buildPage()), start};
        for (View v : pages) { v.setVisibility(View.GONE); stage.addView(v); }
        if (hint != null) root.removeView(hint);
        hint = settingsBar(c);
        hint.setVisibility(View.GONE);
        root.addView(hint, root.indexOfChild(stage) + 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        applyKeys();
    }

    /** Centred column, capped width, scrolls on short screens; room for the Settings hint */
    private View page(View column) {
        Context c = getContext();
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        FrameLayout f = new FrameLayout(c);
        int pad = PUi.dp(c, wide ? 24 : 16);
        f.setPadding(pad, pad, pad, pad + (tall ? PUi.dp(c, 56) : 0));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        f.addView(new MaxWidth(c, PUi.dp(c, wide ? 880 : 560), column), lp);
        s.addView(f, fill());
        // a sliding card or the growing cover may pass the column's edge
        for (ViewGroup g : new ViewGroup[] {s, f, (ViewGroup) column.getParent(), (ViewGroup) column}) { g.setClipChildren(false); g.setClipToPadding(false); }
        return s;
    }

    /** X Settings, fixed at the bottom, in line with the column */
    private View settingsBar(Context c) {
        FrameLayout bar = new FrameLayout(c);
        int pad = PUi.dp(c, wide ? 24 : 16);
        bar.setPadding(pad, 0, pad, PUi.dp(c, 12));
        Button b = PUi.button(c, "Settings", PUi.Kind.QUIET, v -> { Haptics.play(v, Haptics.Word.TICK); BottomScreen.settings(); });
        settingsBtn = b;
        b.setCompoundDrawablePadding(PUi.dp(c, 10));
        LinearLayout row = PUi.row(c);
        row.addView(b, PUi.wrap());
        // the same width as the pages' column, centred
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL);
        bar.addView(new MaxWidth(c, PUi.dp(c, wide ? 880 : 560), row), lp);
        return bar;
    }

    private View logoPage() {
        Context c = getContext();
        LinearLayout col = column(c);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(PUi.badge(c, wide ? 128 : 96));
        TextView t = PUi.title(c, "Peanuts Native");
        t.setGravity(Gravity.CENTER);
        PUi.add(col, t, 16, PUi.wrap());
        String id = BuildConfig.NX_PEANUTS_ID;
        if (id != null && !id.isEmpty()) {
            TextView v = PUi.text(c, id, 13, R.color.bn_text_2, 600);
            v.setGravity(Gravity.CENTER);
            PUi.add(col, v, 4, PUi.wrap());
        }
        return col;
    }

    private View cardPage() {
        Context c = getContext();
        LinearLayout col = column(c);
        cardBox = PUi.card(c);
        LinearLayout top = PUi.row(c);
        top.setGravity(wide ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        cardTile = PUi.tile(c, "?", coverDp);
        top.addView(cardTile);
        LinearLayout info = column(c);
        info.setPadding(PUi.dp(c, 16), 0, 0, 0);
        cardName = PUi.text(c, "", wide ? 26 : 22, R.color.bn_text, 800);
        cardName.setMaxLines(wide ? 3 : 2);
        cardName.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(cardName);
        cardMark = PUi.row(c);
        PUi.add(info, cardMark, 8);
        cardState = PUi.text(c, "", 14, R.color.bn_text, 600);
        cardLine1Row = PUi.row(c);
        cardBadge = companionBadge(c);
        cardLine1Row.addView(cardBadge);
        cardLine1 = PUi.small(c, "");
        LinearLayout.LayoutParams l1 = PUi.wrap();
        l1.leftMargin = PUi.dp(c, 8);
        cardLine1Row.addView(cardLine1, l1);
        PUi.add(info, cardLine1Row, 8);
        cardLine2 = PUi.small(c, "");
        PUi.add(info, cardLine2, 4);
        top.addView(info, PUi.weight());
        cardBox.addView(top);

        cardPrimary = PUi.action(c, 0, "Play", PUi.Kind.PRIMARY, v -> act(v, shownCard == null || slidingTo != null ? null : shownCard.primary, true));   // not mid-slide: that card is leaving
        setKey(cardPrimary, "A");
        cardSecond = keyButton(c, "Y", "Game page", v -> act(v, shownCard == null || slidingTo != null ? null : shownCard.second, false));
        if (wide) {
            PUi.add(info, PUi.pair(c, cardPrimary, cardSecond), 16);
            cardSecond.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;   // as tall as A
        } else {
            PUi.add(cardBox, cardPrimary, 16);
            PUi.add(cardBox, cardSecond, 10);
        }
        col.addView(cardBox);
        return col;
    }

    private View buildPage() {
        Context c = getContext();
        LinearLayout col = column(c);
        LinearLayout card = PUi.card(c);
        LinearLayout top = PUi.row(c);
        buildTile = PUi.tile(c, "?", 52);
        top.addView(buildTile);
        LinearLayout info = column(c);
        info.setPadding(PUi.dp(c, 14), 0, 0, 0);
        buildName = PUi.text(c, "", 17, R.color.bn_text, 700);
        buildName.setMaxLines(2);
        buildName.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(buildName);
        buildStatus = PUi.text(c, "", 13, R.color.bn_accent, 600);
        PUi.add(info, buildStatus, 2);
        top.addView(info, PUi.weight());
        card.addView(top);
        buildBody = PUi.body(c, "");
        PUi.add(card, buildBody, 12);
        buildBar = PUi.progress(c);
        buildBar.setMax(100);
        PUi.add(card, buildBar, 10);
        buildPct = PUi.small(c, "");
        PUi.add(card, buildPct, 6);
        buildStop = keyButton(c, "Y", "Stop build", v -> act(v, shownBuild == null ? null : shownBuild.stop, false));
        buildOff = keyButton(c, null, "Screens off", v -> act(v, shownBuild == null ? null : shownBuild.screensOff, false));
        PUi.add(card, PUi.pair(c, buildStop, buildOff), 14);
        buildOpen = keyButton(c, "A", "Open the build card", v -> act(v, shownBuild == null ? null : shownBuild.open, false));
        PUi.add(card, buildOpen, 14);
        col.addView(card);
        return col;
    }

    /** TONAL button, face button and label centred; key null: no letter */
    private static PUi.Action keyButton(Context c, String key, String label, View.OnClickListener l) {
        PUi.Action b = PUi.action(c, 0, label, PUi.Kind.TONAL, l);
        b.setMinimumHeight(PUi.dp(c, 48));
        b.setBackground(PUi.ripple(c, PUi.round(PUi.col(c, R.color.bn_tint), PUi.dp(c, 14), 0, 0)));
        b.label.setTextSize(15);
        b.label.setTypeface(PUi.face(600));
        setKey(b, key);
        return b;
    }

    private static void setKey(PUi.Action b, String key) { setKey(b, key, 0); }

    /** With a controller its face button; without, the app's icon for the action (none: the label alone) */
    private static void setKey(PUi.Action b, String key, int iconRes) {
        Context c = b.getContext();
        boolean primary = b.kind == PUi.Kind.PRIMARY;
        Drawable d = null;
        if (key != null) d = new KeyGlyph(c, key, PUi.col(c, primary ? R.color.bn_on_banana : R.color.bn_text), PUi.col(c, primary ? R.color.bn_banana : R.color.bn_tint));
        else if (iconRes != 0) { d = c.getDrawable(iconRes).mutate(); d.setTint(b.label.getCurrentTextColor()); }
        b.icon.setImageDrawable(d);
        b.icon.setVisibility(d == null ? View.GONE : View.VISIBLE);
    }

    /** A controller came or went: face buttons, or the app's icons for touch */
    void setPad(boolean on) {
        if (on == pad) return;
        pad = on;
        if (stage == null) return;   // build() applies it
        View[] v = {cardPrimary.icon, cardSecond.icon, buildStop.icon, buildOpen.icon, settingsBtn};
        if (!Motion.on() || !stage.isShown()) { applyKeys(); return; }
        for (View x : v) { x.animate().cancel(); x.animate().alpha(0f).setDuration(Motion.SNAP); }
        stage.postDelayed(() -> { applyKeys(); for (View x : v) x.animate().alpha(1f).setDuration(Motion.FOCUS); }, Motion.SNAP);
    }

    private void applyKeys() {
        if (cardPrimary == null || settingsBtn == null) return;
        setKey(cardPrimary, pad ? "A" : null, shownCard != null ? shownCard.primaryIcon : 0);
        setKey(cardSecond, pad ? "Y" : null);
        setKey(buildStop, pad ? "Y" : null);
        setKey(buildOpen, pad ? "A" : null);
        Context c = getContext();
        int ink = settingsBtn.getCurrentTextColor();
        Drawable d = pad ? BottomScreen.settingsGlyph(c, 22) : c.getDrawable(R.drawable.ic_settings).mutate();
        if (!pad) d.setTint(ink);
        int s = PUi.dp(c, 22);
        d.setBounds(0, 0, s, s);
        settingsBtn.setCompoundDrawablesRelative(d, null, null, null);
    }

    // ---- Pages ----

    /** onHome: with the Settings hint */
    void showLogo(boolean onHome) {
        setHint(onHome);
        switchTo(LOGO);
    }

    /** Another game slides in from where the D-pad went; the same one updates in place */
    void showCard(BottomScreen.Card k) {
        setHint(true);
        boolean other = shownCard != null && !shownCard.titleId.equals(k.titleId);
        if (page == CARD && (other || slidingTo != null) && Motion.on() && stage.isShown()) { slide(k); return; }
        bindCard(k);
        switchTo(CARD);
    }

    private void slide(BottomScreen.Card k) {
        boolean underWay = slidingTo != null;
        slidingTo = k;
        if (underWay) return;   // the slide out binds the newest
        BottomScreen.Card from = shownCard;
        int dx = 0, dy = 1;   // unknown: a small rise
        if (from != null && from.pos >= 0 && k.pos >= 0 && from.pos != k.pos) {
            int cols = Math.max(1, k.cols);
            if (from.pos / cols != k.pos / cols) dy = k.pos > from.pos ? 1 : -1;
            else { dy = 0; dx = k.pos > from.pos ? 1 : -1; }
        }
        float d = PUi.dp(getContext(), 18);
        float ox = dx * d, oy = dy * d;
        cardBox.animate().cancel();
        cardBox.animate().alpha(0f).translationX(-ox).translationY(-oy).setDuration(Motion.SNAP).setInterpolator(Motion.IN).withLayer().withEndAction(() -> {
            BottomScreen.Card n = slidingTo;
            slidingTo = null;
            if (n == null) return;
            bindCard(n);
            cardBox.setTranslationX(ox);
            cardBox.setTranslationY(oy);
            cardBox.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(Motion.ENTER).setInterpolator(Motion.OUT).withLayer();
        });
    }

    private void bindCard(BottomScreen.Card k) {
        Context c = getContext();
        boolean same = shownCard != null && shownCard.titleId.equals(k.titleId);
        shownCard = k;
        cardName.setText(k.name);
        if (!same) setTile(cardTile, k.name, k.icon); else if (k.icon != null && cardTile.getCurrentTextColor() != Color.TRANSPARENT) Motion.cover(cardTile, k.icon);
        stateMark(c, k);
        boolean ready = k.kind == BottomScreen.Card.K_READY, toBuild = k.kind == BottomScreen.Card.K_BUILD;
        boolean hasComp = ready && k.companion != null;
        cardBadge.setVisibility(hasComp ? View.VISIBLE : View.GONE);
        ((LinearLayout.LayoutParams) cardLine1.getLayoutParams()).leftMargin = hasComp ? PUi.dp(c, 8) : 0;
        String l1 = ready ? (hasComp ? "Companion " + k.companion : "") : toBuild ? k.found : "This app version does not include this game.";
        String l2 = ready ? played(k.played) : toBuild ? k.space : "";
        setLine(cardLine1, cardLine1Row, l1);
        setLine(cardLine2, cardLine2, l2);
        cardPrimary.setVisibility(k.primary != null ? View.VISIBLE : View.GONE);
        if (k.primary != null) { cardPrimary.setText(k.primaryLabel); setKey(cardPrimary, pad ? "A" : null, k.primaryIcon); }
        cardSecond.setVisibility(k.second != null ? View.VISIBLE : View.GONE);
        if (k.second != null) cardSecond.setText(k.secondLabel);
        cardBox.setAlpha(1f);
        cardBox.setTranslationX(0f);
        cardBox.setTranslationY(0f);
    }

    /** A cover's art landed after its card showed */
    void icon(String titleId, Bitmap b) {
        if (shownCard != null && shownCard.titleId.equals(titleId)) { shownCard.icon = b; Motion.cover(cardTile, b); }
        if (shownBuild != null && shownBuild.titleId.equals(titleId)) { shownBuild.icon = b; Motion.cover(buildTile, b); }
        if (titleId.equals(launchId)) start.icon(b);
    }

    void showBuild(BottomScreen.BuildInfo b) {
        setHint(true);
        boolean same = shownBuild != null && shownBuild.titleId.equals(b.titleId);
        shownBuild = b;
        buildName.setText(b.name);
        if (!same) setTile(buildTile, b.name, b.icon);
        updateBuild(b);
        switchTo(BUILD);
    }

    /** Progress in place, no layout */
    void updateBuild(BottomScreen.BuildInfo b) {
        Context c = getContext();
        boolean fresh = shownBuild == null || page != BUILD;
        shownBuild = b;
        swap(buildStatus, b.step);
        buildStatus.setTextColor(PUi.col(c, b.failed ? R.color.bn_danger : R.color.bn_accent));
        buildBody.setText(b.body);
        buildBar.setVisibility(b.failed ? View.GONE : View.VISIBLE);
        buildBar.setIndeterminate(b.indeterminate);
        if (!b.indeterminate) Motion.progress(buildBar, b.percent, fresh);
        buildPct.setVisibility(b.failed || b.indeterminate ? View.GONE : View.VISIBLE);
        buildPct.setText(b.percent + " %");
        // running: Stop and Screens off; stopped or not ours: Open the build card
        ((View) buildStop.getParent()).setVisibility(b.failed ? View.GONE : View.VISIBLE);
        buildStop.setVisibility(b.stop != null ? View.VISIBLE : View.GONE);
        buildOpen.setVisibility((b.failed || b.stop == null) && b.open != null ? View.VISIBLE : View.GONE);
    }

    /** Play: the card's cover grows into the start page */
    void showLaunch(String titleId, String name, Bitmap icon, CharSequence step) {
        if (page == LAUNCH && titleId.equals(launchId)) return;   // already on its way
        launchId = titleId;
        start.bind(name, icon);
        start.status(step);
        start.quiet(false);
        setHint(false);
        TextView tile = start.tile();
        View info = start.info();
        boolean grow = page == CARD && shownCard != null && shownCard.titleId.equals(titleId) && Motion.on() && stage.isShown() && cardTile.isShown();
        if (!grow) {
            tile.setScaleX(1f); tile.setScaleY(1f); tile.setTranslationX(0f); tile.setTranslationY(0f); tile.setAlpha(1f);
            info.setAlpha(1f); info.setTranslationY(0f);
            switchTo(LAUNCH);
            start.setAnimating(active);
            return;
        }
        int[] from = new int[2];
        cardTile.getLocationInWindow(from);
        float fromW = cardTile.getWidth();
        View out = pages[page];
        page = LAUNCH;
        out.animate().cancel();
        out.animate().alpha(0f).setDuration(Motion.EXIT).withLayer().withEndAction(() -> { if (page != CARD) { out.setVisibility(View.GONE); out.setAlpha(1f); } });
        start.animate().cancel();
        start.setAlpha(1f);
        start.setTranslationY(0f);
        start.setVisibility(View.VISIBLE);
        info.setAlpha(0f);
        tile.setAlpha(0f);   // until it sits over the card's
        tile.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                tile.getViewTreeObserver().removeOnPreDrawListener(this);
                int[] to = new int[2];
                tile.getLocationInWindow(to);
                float sc = tile.getWidth() > 0 ? fromW / tile.getWidth() : 1f;
                tile.setPivotX(0f); tile.setPivotY(0f);
                tile.setScaleX(sc); tile.setScaleY(sc);
                tile.setTranslationX(from[0] - to[0]); tile.setTranslationY(from[1] - to[1]);
                tile.setAlpha(1f);
                tile.animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f).setDuration(Motion.EMPHASIS).setInterpolator(Motion.OUT).withLayer();
                info.setTranslationY(PUi.dp(getContext(), 10));
                info.animate().alpha(1f).translationY(0f).setStartDelay(Motion.EXIT).setDuration(Motion.ENTER).setInterpolator(Motion.OUT);
                start.setAnimating(active);
                return true;
            }
        });
    }

    /** Crossfade (cut under reduced motion); the incoming page rises a little */
    private void switchTo(int next) {
        if (pages == null || next == page) return;
        View in = pages[next];
        View out = page >= 0 ? pages[page] : null;
        if (page == LAUNCH && next != LAUNCH) start.setAnimating(false);
        page = next;
        boolean fade = Motion.on() && stage.isShown();
        in.animate().cancel();
        in.setVisibility(View.VISIBLE);
        if (out != null) {
            out.animate().cancel();
            if (fade) out.animate().alpha(0f).setDuration(Motion.EXIT).withLayer().withEndAction(() -> { if (pages != null && pages[page] != out) { out.setVisibility(View.GONE); out.setAlpha(1f); } });
            else { out.setAlpha(1f); out.setVisibility(View.GONE); }
        }
        if (fade) {
            in.setAlpha(0f);
            in.setTranslationY(PUi.dp(getContext(), 10));
            in.animate().alpha(1f).translationY(0f).setDuration(Motion.ENTER).setInterpolator(Motion.OUT).withLayer();
        } else { in.setAlpha(1f); in.setTranslationY(0f); }
    }

    private void setHint(boolean on) {
        boolean show = on && tall;
        if (hint == null || (hint.getVisibility() == View.VISIBLE) == show) return;
        hint.animate().cancel();
        if (show) {
            hint.setVisibility(View.VISIBLE);
            if (Motion.on() && stage.isShown()) { hint.setAlpha(0f); hint.animate().alpha(1f).setDuration(Motion.ENTER); } else hint.setAlpha(1f);
        } else if (Motion.on() && stage.isShown()) {
            hint.animate().alpha(0f).setDuration(Motion.EXIT).withEndAction(() -> hint.setVisibility(View.GONE));
        } else hint.setVisibility(View.GONE);
    }

    // ---- Pieces ----

    /** New text fades in; the same text is left alone */
    private static void swap(TextView t, CharSequence s) {
        if (TextUtils.equals(t.getText().toString(), s.toString())) return;
        t.animate().cancel();
        if (!Motion.on() || !t.isShown() || t.getText().length() == 0) { t.setText(s); t.setAlpha(1f); return; }
        t.animate().alpha(0f).setDuration(Motion.SNAP).withEndAction(() -> { t.setText(s); t.animate().alpha(1f).setDuration(Motion.FOCUS); });
    }

    private static void act(View v, Runnable r, boolean weighty) {
        if (r == null) return;
        Haptics.play(v, weighty ? Haptics.Word.HEAVY : Haptics.Word.CLICK);
        r.run();
    }

    /** The cover's own mark: ✓ in a leaf circle, or its pill */
    private void stateMark(Context c, BottomScreen.Card k) {
        cardMark.removeAllViews();
        if (cardState.getParent() != null) ((ViewGroup) cardState.getParent()).removeView(cardState);
        if (k.kind == BottomScreen.Card.K_READY) {
            TextView ok = PUi.text(c, "✓", 12, R.color.bn_on_leaf, 700);
            ok.setGravity(Gravity.CENTER);
            ok.setIncludeFontPadding(false);
            ok.setBackground(PUi.round(PUi.col(c, R.color.bn_leaf), PUi.dp(c, 999), 0, 0));
            ok.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cardMark.addView(ok, new LinearLayout.LayoutParams(PUi.dp(c, 22), PUi.dp(c, 22)));
            cardState.setText(k.state);
            LinearLayout.LayoutParams lp = PUi.wrap();
            lp.leftMargin = PUi.dp(c, 8);
            cardMark.addView(cardState, lp);
        } else {
            boolean danger = k.kind == BottomScreen.Card.K_NO_PROFILE || !"Build needed".equals(k.state);
            TextView pill = PUi.text(c, k.state, 12, danger ? R.color.bn_danger : R.color.bn_on_banana, 700);
            pill.setSingleLine(true);
            pill.setBackground(PUi.round(PUi.col(c, danger ? R.color.bn_danger_tint : R.color.bn_banana), PUi.dp(c, 999), 0, 0));
            pill.setPadding(PUi.dp(c, 10), PUi.dp(c, 5), PUi.dp(c, 10), PUi.dp(c, 5));
            cardMark.addView(pill, PUi.wrap());
        }
        cardMark.setContentDescription(k.state);
    }

    private static void setLine(TextView t, View row, String s) {
        boolean has = s != null && !s.isEmpty();
        t.setText(has ? s : "");
        row.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    /** Letter first, then the art */
    private static void setTile(TextView tile, String name, Bitmap icon) {
        String n = name == null ? "" : name.trim();
        tile.setText(n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
        PUi.tileIcon(tile, null);
        if (icon != null) PUi.tileIcon(tile, icon);
    }

    /** "Last played yesterday" and the like; empty if never */
    private static String played(long at) {
        if (at <= 0) return "";
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(at);
        int days = daysBetween(then, now);
        if (days <= 0) return "Last played today";
        if (days == 1) return "Last played yesterday";
        if (days < 7) return "Last played " + days + " days ago";
        return "Last played on " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(at));
    }

    private static int daysBetween(Calendar a, Calendar b) {
        Calendar x = (Calendar) a.clone(), y = (Calendar) b.clone();
        for (Calendar k : new Calendar[] {x, y}) { k.set(Calendar.HOUR_OF_DAY, 0); k.set(Calendar.MINUTE, 0); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0); }
        return (int) Math.round((y.getTimeInMillis() - x.getTimeInMillis()) / 86_400_000d);
    }

    /** The covers' two-screen companion badge */
    private static View companionBadge(Context c) {
        LinearLayout v = column(c);
        v.setGravity(Gravity.CENTER);
        v.setBackground(PUi.round(PUi.col(c, R.color.bn_leaf), PUi.dp(c, 6), 0, 0));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 2; i++) {
            View s = new View(c);
            s.setBackground(PUi.round(PUi.col(c, R.color.bn_on_leaf), PUi.dp(c, 1), 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(PUi.dp(c, 12), PUi.dp(c, 5));
            if (i > 0) lp.topMargin = PUi.dp(c, 2);
            v.addView(s, lp);
        }
        v.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(c, 22), PUi.dp(c, 22)));
        return v;
    }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Face button: a letter cut out of a disc. */
    static final class KeyGlyph extends Drawable {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG), letter = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String key;
        private final int size;
        private final Rect bounds = new Rect();

        KeyGlyph(Context c, String key, int ink, int cut) {
            this(c, key, ink, cut, 22);
        }

        KeyGlyph(Context c, String key, int ink, int cut, int diameterDp) {
            this.key = key;
            size = PUi.dp(c, diameterDp);
            disc.setColor(ink);
            letter.setColor(cut);
            letter.setTypeface(Typeface.create(Typeface.SANS_SERIF, 800, false));
            letter.setTextSize(PUi.dp(c, 12f * diameterDp / 22));
            letter.setTextAlign(Paint.Align.CENTER);
        }

        @Override public void draw(Canvas canvas) {
            Rect r = getBounds();
            float cx = r.exactCenterX(), cy = r.exactCenterY(), rad = Math.min(r.width(), r.height()) / 2f;
            canvas.drawCircle(cx, cy, rad, disc);
            letter.getTextBounds(key, 0, key.length(), bounds);
            canvas.drawText(key, cx, cy - bounds.exactCenterY(), letter);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { disc.setAlpha(alpha); letter.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { disc.setColorFilter(cf); letter.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Caps its child's width */
    private static final class MaxWidth extends FrameLayout {
        private final int max;
        MaxWidth(Context c, int max, View child) {
            super(c);
            this.max = max;
            addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        @Override protected void onMeasure(int w, int h) {
            int size = MeasureSpec.getSize(w);
            if (size > max) w = MeasureSpec.makeMeasureSpec(max, MeasureSpec.getMode(w) == MeasureSpec.UNSPECIFIED ? MeasureSpec.AT_MOST : MeasureSpec.getMode(w));
            super.onMeasure(w, h);
        }
    }
}
