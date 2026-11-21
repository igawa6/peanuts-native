package dev.peanuts.ui;

import dev.nxnative.android.BananaBackgroundView;
import dev.nxnative.android.R;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/**
 * The screen over the game while its shaders are prepared before it starts (GameActivity.prewarmTick: the startup prewarm's pipelines, then the shader
 * parts of the game's programs, vka_pso.c pp_prepare; minutes for a large game, once per GPU driver). The home screen's drifting background, the game's
 * cover with a peanut that cracks open as the count goes up, the game's name, the app's progress strip with the count, and a line saying it happens once.
 */
public final class PrepareCover extends FrameLayout {
    private final Peanut peanut;
    private final ProgressBar bar;
    private final TextView count;
    private final BananaBackgroundView back;

    private boolean skipped, closed;

    /** driver: the GPU driver's name as the settings show it, null for the system's; playAnyway: the step stops and the game starts; close: the game is left */
    public PrepareCover(Activity a, String titleId, String name, String driver, Runnable playAnyway, Runnable close) {
        super(a);
        setClickable(true);   // nothing under it takes a touch
        setBackgroundColor(PUi.col(a, R.color.bn_bg));
        back = new BananaBackgroundView(a);
        addView(back, new LayoutParams(-1, -1));
        back.setRunning(true);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = PUi.dp(a, 24);
        col.setPadding(pad, pad, pad, pad);

        // The cover, the peanut over its lower right corner (alone and larger when the game has no cover yet)
        Bitmap icon = cover(a, titleId);
        int side = PUi.dp(a, 176), nut = PUi.dp(a, icon != null ? 104 : 140), out = PUi.dp(a, 44);
        FrameLayout art = new FrameLayout(a);
        if (icon != null) {
            ImageView img = new ImageView(a);
            img.setImageBitmap(icon);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final float r = PUi.dp(a, 18);
            img.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); } });
            img.setClipToOutline(true);
            img.setElevation(PUi.dp(a, 6));
            LayoutParams ip = new LayoutParams(side, side);
            ip.rightMargin = out; ip.bottomMargin = out * 2 / 3;
            art.addView(img, ip);
        }
        peanut = new Peanut(a);
        // on a round card of its own, so it reads on any cover
        peanut.setBackground(PUi.round(PUi.col(a, R.color.bn_card), nut / 2f, PUi.col(a, R.color.bn_banana_deep), PUi.dp(a, 3)));
        peanut.setElevation(PUi.dp(a, 10));
        art.addView(peanut, new LayoutParams(nut, nut, icon != null ? Gravity.BOTTOM | Gravity.END : Gravity.CENTER));
        col.addView(art, new LinearLayout.LayoutParams(-2, -2));

        if (name != null && !name.isEmpty() && !"null".equals(name)) {
            TextView title = PUi.text(a, name, 22, R.color.bn_text, 700);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(PUi.dp(a, 420), -2);
            tp.topMargin = PUi.dp(a, 18);
            col.addView(title, tp);
        }
        bar = Strip.of(a, PUi.col(a, R.color.bn_card_stroke), false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(PUi.dp(a, 340), -2);
        bp.topMargin = PUi.dp(a, 16);
        col.addView(bar, bp);
        count = PUi.text(a, "Preparing shaders", 15, R.color.bn_text, 500);
        count.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.topMargin = PUi.dp(a, 10);
        col.addView(count, cp);
        TextView drv = PUi.text(a, driver == null ? "For the system's graphics driver" : "For the graphics driver " + driver, 14, R.color.bn_text_2, 500);
        drv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-2, -2);
        dp.topMargin = PUi.dp(a, 6);
        col.addView(drv, dp);
        TextView once = PUi.small(a, "This happens once for each graphics driver. After that the game starts right away.");
        once.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2);
        op.topMargin = PUi.dp(a, 14);
        col.addView(once, op);

        addView(col, new LayoutParams(-2, -2, Gravity.CENTER));

        // Top right, as Home's header: Play anyway (what is prepared so far is kept, the rest at the next start) and Close
        LinearLayout actions = new LinearLayout(a);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        final View[] play = new View[1];
        play[0] = Library.headerAction(a, R.drawable.ic_play, "Play anyway", "Play anyway: start the game now and prepare the rest next time", null, v -> {
            if (skipped) return;
            skipped = true;
            play[0].setAlpha(0.4f);
            count.setText("Finishing the shader in progress, then the game starts");   // (a large one can take most of a minute)
            playAnyway.run();
        });
        actions.addView(play[0], PUi.wrap());
        View shut = Library.headerAction(a, R.drawable.ic_close, "Close", "Close: leave the game. Shaders prepared up to the last half minute are kept", null, v -> { if (closed) return; closed = true; close.run(); });
        LinearLayout.LayoutParams sp = PUi.wrap();
        sp.leftMargin = PUi.dp(a, 14);
        actions.addView(shut, sp);
        LayoutParams ap = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ap.topMargin = PUi.dp(a, 16); ap.rightMargin = PUi.dp(a, 20);
        addView(actions, ap);
    }

    /** done of total, from the host (Native.prewarmProgress) */
    public void set(int done, int total) {
        if (total <= 0 || skipped) return;
        if (done > total) done = total;
        bar.setMax(total);
        bar.setProgress(done);
        count.setText(PUi.joined("Preparing shaders", String.format(Locale.US, "%,d of %,d", done, total), (int) (100L * done / total) + "%"));
        peanut.open(done / (float) total);
    }

    @Override protected void onDetachedFromWindow() { back.setRunning(false); super.onDetachedFromWindow(); }

    private static Bitmap cover(Context c, String titleId) {
        if (titleId == null || titleId.isEmpty() || "null".equals(titleId)) return null;
        File f = new File(c.getFilesDir(), "icons/" + titleId + ".png");
        if (!f.isFile()) return null;
        // about twice the 176 dp it is shown at: a full-size icon is not decoded whole
        int target = Math.round(352 * c.getResources().getDisplayMetrics().density);
        BitmapFactory.Options b = new BitmapFactory.Options(); b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 1;
        while (o.inSampleSize < 16 && b.outWidth / (o.inSampleSize * 2) >= target && b.outHeight / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * The app's peanut (PeanutArt: the shell of res/drawable/ic_banana.xml of the peanuts source set, 108 units across) split along its seam: a crack runs down it
     * over the first two fifths of the progress, then the halves swing apart about the foot and show the two kernels. It rocks a little all the while, so the
     * screen is seen to be alive between two counts (still when the system's animations are off).
     */
    private static final class Peanut extends View {
        private final PeanutArt art = new PeanutArt();
        private float open, shown;   // the progress asked for, and the one drawn (eased towards it)
        private float rock;
        private ValueAnimator anim;

        Peanut(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // clipPath of the halves, on every device
        }

        void open(float p) { open = Math.max(0f, Math.min(1f, p)); if (anim == null) { shown = open; invalidate(); } }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!ValueAnimator.areAnimatorsEnabled()) return;
            anim = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
            anim.setDuration(1600); anim.setRepeatCount(ValueAnimator.INFINITE); anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(v -> { rock = (float) v.getAnimatedValue(); shown += (open - shown) * 0.12f; invalidate(); });
            anim.start();
        }
        @Override protected void onDetachedFromWindow() { if (anim != null) { anim.cancel(); anim = null; } super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 108f * 1.16f;   // (the shell is 70 of the 108 units high: most of its round card)
            c.save();
            c.translate(getWidth() / 2f - 54 * s, getHeight() / 2f - 55 * s);
            c.scale(s, s);
            // a knock every turn of the clock: two quick rocks, then still
            float k = (float) (Math.sin(rock * 3) * Math.max(0, Math.sin(rock)) * 3.5);
            c.rotate(k, 54, 90);
            float crackT = Math.min(1f, shown / 0.4f), swing = Math.max(0f, (shown - 0.4f) / 0.6f);
            float deg = 30f * swing * swing * (3 - 2 * swing);   // eased
            art.draw(c, crackT, swing, deg, 255);
            c.restore();
        }
    }
}
