package dev.peanuts.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

import dev.nxnative.android.BananaBackgroundView;
import dev.nxnative.android.R;
import dev.peanuts.smooth.Smooth;
import dev.peanuts.smooth.SmoothItems;

/**
 * Smooth Experience's start screen (SmoothItems.START_LOG): over the game until its first frame, in place of the log's last lines (GameActivity.tail). The
 * home screen's drifting background, the game's cover and the peanut of PrepareCover on its round card, breathing slowly on the clock of the bottom screen's
 * start page (StartView: the same period and easing on SystemClock.uptimeMillis, the same in every process), so the two screens breathe together. Still under
 * reduced motion. At the first frame it fades out and is GONE and taken out of the window: a view over the SurfaceView costs a GPU-bound game frames (Overlay,
 * issue #34). The bottom screen keeps its start page until then (GameScreen.apply, {@link #waiting}). When the start fails ("CRASH sig=" or "FATAL" in the
 * log), the peanut stops and a calm card says so, with Share log and Close.
 */
public final class StartCover extends FrameLayout implements Choreographer.FrameCallback {
    private static final PathInterpolator EASE = new PathInterpolator(0.45f, 0f, 0.55f, 1f);   // StartView's
    private static final long BREATH_MS = 1300;   // StartView's ring
    private static final float BREATH = 0.03f;    // the peanut's size swings by 3 %
    private static final int FADE_MS = 400, FAST_POLL_MS = 250;

    private static StartCover shown;   // this process's (one game screen at a time)
    private static FrameLayout root;
    private static int rootAt;
    private static Runnable close;
    private static boolean leaving;

    private final Activity a;
    private final BananaBackgroundView back;
    private final View peanut;
    private final LinearLayout col;
    private LinearLayout card;
    private View first;   // the card's Share log
    private boolean posted;

    /**
     * GameActivity.start: the cover over the game, at index at of root (under the paused view and the in-game menu, which stay reachable), with the log's
     * view gone; nothing while Smooth Experience's start screen is off, for a preparation boot (ShaderCapture has its own cover), or when it is up already
     * (start again after the readiness check). close: the game's own Stop (GameActivity.end)
     */
    public static void add(Activity act, FrameLayout into, int at, View log, Runnable end) {
        if (shown != null && shown.a == act) return;
        if (!Smooth.hides(act, SmoothItems.START_LOG) || dev.peanuts.build.ShaderCapture.active(act)) return;
        root = into; rootAt = at; close = end; leaving = false;
        shown = new StartCover(act);
        into.addView(shown, Math.min(at, into.getChildCount()), new FrameLayout.LayoutParams(-1, -1));
        log.setVisibility(View.GONE);
        GameScreen.refresh();   // the bottom screen opened before this (Companion.start): its start page from now on, until the first frame
    }

    /**
     * GameActivity.tail, once a second with what the log says: the log's view stays gone (unless the player turned Show the log over the game on); the first
     * frame takes the cover away; a failure shows the card (again over the game when it came after the first frame)
     */
    public static void tail(Activity act, View log, boolean drawing, boolean failed, boolean showLog) {
        if (root == null || !Smooth.hides(act, SmoothItems.START_LOG)) return;
        if (!showLog) log.setVisibility(View.GONE);
        if (failed) {
            if (shown == null || shown.a != act) {   // after the first frame: the card over the game's last picture
                shown = new StartCover(act);
                root.addView(shown, Math.min(rootAt, root.getChildCount()), new FrameLayout.LayoutParams(-1, -1));
            }
            shown.animate().cancel();
            shown.setAlpha(1f);
            leaving = false;
            shown.failed(drawing);
        } else if (drawing && shown != null && !leaving) {
            leaving = true;
            StartCover s = shown;
            s.setAnimating(false);
            s.animate().alpha(0f).setDuration(Motion.on() ? FADE_MS : 0).setInterpolator(Motion.IN).withEndAction(() -> {
                s.setVisibility(View.GONE);
                if (s.getParent() == root) root.removeView(s);
                if (shown == s) shown = null;
                GameScreen.refresh();   // the bottom screen leaves its start page with it
            }).start();
        }
    }

    /** The log's next look (GameActivity.tail): every 250 ms while the cover waits for the first frame, so it goes with it; else the caller's */
    public static int next(int ms) { return shown != null && !leaving && shown.card == null ? FAST_POLL_MS : ms; }

    /** The cover waits for the game's first frame (the bottom screen shows its start page meanwhile) */
    static boolean waiting() { return shown != null && !leaving && shown.card == null; }

    /**
     * A controller moves over the card's buttons (GameActivity.dispatchKeyEvent): the game behind it has stopped. A key that finds the focus outside the card
     * (the card came while the screen was in touch mode) puts it on Share log first
     */
    public static boolean takesPad() {
        if (shown == null || shown.card == null) return false;
        if (shown.first != null && !shown.card.hasFocus()) shown.first.requestFocus();
        return true;
    }

    private StartCover(Activity act) {
        super(act);
        a = act;
        Motion.refresh(act);
        setClickable(true);   // nothing under it takes a touch while it shows
        setFocusable(NOT_FOCUSABLE);   // but no focus stop of its own: the focus highlight would grey the whole screen, and the card's buttons take the pad
        setDefaultFocusHighlightEnabled(false);
        setBackgroundColor(PUi.col(act, R.color.bn_bg));
        back = new BananaBackgroundView(act);
        addView(back, new LayoutParams(-1, -1));
        back.setRunning(Motion.on());

        col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = PUi.dp(act, 24);
        col.setPadding(pad, pad, pad, pad);

        // PrepareCover's art: the cover, the peanut over its lower right corner (alone and larger when the game has no cover yet)
        String titleId = act.getIntent().getStringExtra("title_id"), name = act.getIntent().getStringExtra("name");
        Bitmap icon = cover(act, titleId);
        int side = PUi.dp(act, 176), nut = PUi.dp(act, icon != null ? 104 : 140), out = PUi.dp(act, 44);
        FrameLayout art = new FrameLayout(act);
        art.setClipChildren(false);
        if (icon != null) {
            ImageView img = new ImageView(act);
            img.setImageBitmap(icon);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final float r = PUi.dp(act, 18);
            img.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); } });
            img.setClipToOutline(true);
            img.setElevation(PUi.dp(act, 6));
            LayoutParams ip = new LayoutParams(side, side);
            ip.rightMargin = out; ip.bottomMargin = out * 2 / 3;
            art.addView(img, ip);
        }
        peanut = new Shell(act);
        peanut.setBackground(PUi.round(PUi.col(act, R.color.bn_card), nut / 2f, PUi.col(act, R.color.bn_banana_deep), PUi.dp(act, 3)));
        peanut.setElevation(PUi.dp(act, 10));
        peanut.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        art.addView(peanut, new LayoutParams(nut, nut, icon != null ? Gravity.BOTTOM | Gravity.END : Gravity.CENTER));
        col.addView(art, new LinearLayout.LayoutParams(-2, -2));
        if (name != null && !name.isEmpty() && !"null".equals(name)) {
            TextView title = PUi.text(act, name, 22, R.color.bn_text, 700);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(PUi.dp(act, 420), -2);
            tp.topMargin = PUi.dp(act, 18);
            col.addView(title, tp);
        }
        setContentDescription((name != null ? name + ": " : "") + "starting");
        addView(col, new LayoutParams(-2, -2, Gravity.CENTER));
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (card == null && !leaving) setAnimating(true); }
    @Override protected void onDetachedFromWindow() { setAnimating(false); back.setRunning(false); super.onDetachedFromWindow(); }

    private void setAnimating(boolean on) {
        boolean run = on && Motion.on() && isAttachedToWindow();
        if (run && !posted) { posted = true; Choreographer.getInstance().postFrameCallback(this); }
        else if (!run && posted) { posted = false; Choreographer.getInstance().removeFrameCallback(this); }
        if (!run) { peanut.setScaleX(1f); peanut.setScaleY(1f); }
    }

    /** The breath: in step with StartView's ring, which follows the same clock */
    @Override public void doFrame(long nowNs) {
        posted = false;
        if (card != null || leaving) return;
        long now = SystemClock.uptimeMillis();
        float p = now % (2 * BREATH_MS) / (float) BREATH_MS, s = 1f + BREATH * EASE.getInterpolation(p <= 1f ? p : 2f - p);
        peanut.setScaleX(s);
        peanut.setScaleY(s);
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The start stopped: the peanut still, a card with what happened, Share log and Close; the controller's focus on Share log */
    private void failed(boolean afterFirstFrame) {
        setAnimating(false);
        back.setRunning(false);
        if (card != null) return;
        card = PUi.card(a);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView t = PUi.text(a, afterFirstFrame ? "The game stopped" : "The game stopped while starting", 18, R.color.bn_text, 700);
        t.setGravity(Gravity.CENTER);
        card.addView(t, PUi.wrap());
        TextView l = PUi.small(a, "Share log sends what happened, so it can be looked into.");
        l.setGravity(Gravity.CENTER);
        PUi.add(card, l, 6, PUi.wrap());
        View share = PUi.button(a, "Share log", PUi.Kind.PRIMARY, Feel.button(v -> share(a)));
        View shut = PUi.button(a, "Close", PUi.Kind.TONAL, Feel.button(v -> { if (close != null) close.run(); }));
        focusLook(share);
        focusLook(shut);
        LinearLayout row = PUi.pair(a, share, shut);
        PUi.add(card, row, 14);   // the card's width: pair's two halves
        card.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(PUi.dp(a, 420), -2);
        cp.topMargin = PUi.dp(a, 18);
        col.addView(card, cp);
        first = share;
        share.requestFocus();   // takes only outside touch mode; takesPad puts it there at the first key otherwise
    }

    /** The controller's focus frame on a button while it has the focus: the look of upstream's PadFocus.frame (whose watch does not reach the game's window), drawn where the button shows (FocusFrame) */
    private static void focusLook(View b) {
        Context c = b.getContext();
        android.graphics.drawable.GradientDrawable outer = new android.graphics.drawable.GradientDrawable();
        outer.setColor((c.getColor(R.color.bn_accent) & 0x00FFFFFF) | 0x26000000);
        outer.setStroke(PUi.dp(c, 3), 0xFFFFFFFF);
        outer.setCornerRadius(PUi.dp(c, 14));
        android.graphics.drawable.GradientDrawable inner = new android.graphics.drawable.GradientDrawable();
        inner.setStroke(PUi.dp(c, 2), 0xFF1B1208);
        inner.setCornerRadius(PUi.dp(c, 12));
        android.graphics.drawable.LayerDrawable frame = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {outer, inner});
        frame.setLayerInset(1, PUi.dp(c, 3), PUi.dp(c, 3), PUi.dp(c, 3), PUi.dp(c, 3));
        b.setDefaultFocusHighlightEnabled(false);
        b.setOnFocusChangeListener((v, has) -> v.setForeground(has ? FocusFrame.at(v, frame) : null));
    }

    /** This run's log (the game's newest, which is this run's while it runs) and the digest beside it when there is one, as Settings › Share log sends it */
    private static void share(Activity a) {
        File log = BuildLogUi.newestRunLog(a, a.getIntent().getStringExtra("title_id"));
        if (log == null) { android.widget.Toast.makeText(a, "No log of this game yet.", android.widget.Toast.LENGTH_SHORT).show(); return; }
        Uri uri = Uri.parse("content://" + a.getPackageName() + ".logs/" + Uri.encode(log.getName()));   // LogProvider's
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "nxnative log " + log.getName());
        send.setClipData(ClipData.newRawUri(log.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { a.startActivity(Intent.createChooser(send, "Share " + log.getName())); }
        catch (RuntimeException e) { android.widget.Toast.makeText(a, "The log could not be shared from here. Settings › Share log sends it.", android.widget.Toast.LENGTH_LONG).show(); }
    }

    private static Bitmap cover(Context c, String titleId) {
        if (titleId == null || titleId.isEmpty() || "null".equals(titleId)) return null;
        File f = new File(c.getFilesDir(), "icons/" + titleId + ".png");
        if (!f.isFile()) return null;
        int target = Math.round(352 * c.getResources().getDisplayMetrics().density);   // about twice the 176 dp it is shown at, as PrepareCover
        BitmapFactory.Options b = new BitmapFactory.Options(); b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 1;
        while (o.inSampleSize < 16 && b.outWidth / (o.inSampleSize * 2) >= target && b.outHeight / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** The app's peanut, closed (PeanutArt, as PrepareCover draws it before its count goes up) */
    private static final class Shell extends View {
        private final PeanutArt art = new PeanutArt();
        Shell(Context c) { super(c); setLayerType(LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 108f * 1.16f;
            c.save();
            c.translate(getWidth() / 2f - 54 * s, getHeight() / 2f - 55 * s);
            c.scale(s, s);
            art.draw(c, 0f, 0f, 0f, 255);
            c.restore();
        }
    }
}
