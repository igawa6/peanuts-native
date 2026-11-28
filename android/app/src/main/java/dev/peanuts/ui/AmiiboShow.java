package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/**
 * The show of an amiibo placed from the card or the quick menu's tab (about 2.3 s). The game dims softly (eased in over DIM_IN, never at once) and a stand
 * fades in and its banana ring pulses once; the app's own peanut (PeanutArt, the one of About and the shader screen) drops straight onto it; it lands with one
 * firm tick through the in-game haptics (MenuFeel's setting), its seam runs down and the halves open; the amiibo's picture (Peanuts' own card while the
 * pictures are off or there is none) rises out of the kernel's light (the game gets the tag here: {@code atCrack}); then it stands on the ring with the line
 * ("On the stand") and the amiibo's name for a good second (RISE_END to END), its base on the ring's centre (the picture's own base found in it: AmiiboAPI's
 * pictures are not centred on the figure's base), and all fades. No title names the app beside amiibo, so nothing suggests an endorsement (igawa6's review of
 * #713). Calm motion: decelerating, no bounce, no wobble. At the end the figure and the words fade first and the dim lifts a little after them (eased,
 * OUT_MS). Any button or a tap skips to the end (the tag is still placed). Without the system's animations, or under reduced motion, nothing is drawn and the
 * tag is placed at once.
 */
public final class AmiiboShow extends View {
    static final long DROP_AT = 250, LAND_AT = 500, CRACK_AT = 650, OPEN_END = 900, RISE_END = 1150, END = 2300, DIM_IN = 380, OUT_MS = 560, SKIP_OUT_MS = 280;
    static final int DIM = 140;   // the dim's alpha at its fullest
    private final PeanutArt art = new PeanutArt();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap picture;
    private final float baseX, baseY;   // where the figure's base is in the picture (fractions of its width and height): it stands on the ring's centre
    private final Drawable stand;
    private final String name;
    private Runnable atCrack, done;
    private ValueAnimator anim;
    private float t, gone, dimHeld = -1;   // t: ms into the show; gone: how far the end has gone (0 to 1); dimHeld: the dim when skipped early
    private boolean ticked, cracked, ending;

    /** Plays the show over the game in root; atCrack: the tag is placed; done: it has gone. Returns the view (skip() ends it early) */
    public static AmiiboShow play(ViewGroup root, Bitmap picture, String name, Runnable atCrack, Runnable done) {
        Context c = root.getContext();
        if (!Motion.on() || !ValueAnimator.areAnimatorsEnabled()) {
            atCrack.run();
            done.run();
            return null;
        }
        AmiiboShow s = new AmiiboShow(c, picture, name, atCrack, done);
        root.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        s.start();
        return s;
    }

    private AmiiboShow(Context c, Bitmap picture, String name, Runnable atCrack, Runnable done) {
        super(c);
        this.picture = picture;
        float[] base = base(picture);
        baseX = base[0];
        baseY = base[1];
        this.name = name;
        this.atCrack = atCrack;
        this.done = done;
        stand = dev.peanuts.ingame.Look.icon(c, "amiibo", dev.peanuts.ingame.Look.Ink.SELECTED);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // PeanutArt's clipPath of the halves, on every device
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void start() {
        anim = ValueAnimator.ofFloat(0f, END);
        anim.setDuration(END);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); beats(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!ending) out(OUT_MS); }
        });
        anim.start();
    }

    private void beats() {
        if (!ticked && t >= LAND_AT) {
            ticked = true;
            if (MenuFeel.haptics(getContext())) Haptics.play(this, Haptics.Word.HEAVY);   // one firm tick as it lands
        }
        if (!cracked && t >= CRACK_AT) crack();
    }

    private void crack() {
        cracked = true;
        Runnable r = atCrack;
        atCrack = null;
        if (r != null) r.run();
    }

    /** A button or a tap: straight to the end (the tag placed if it was not yet), then out */
    public void skip() {
        if (ending) return;
        if (!cracked) crack();
        if (anim != null) anim.cancel();
        if (t < DIM_IN) dimHeld = smooth(t / DIM_IN);   // the dim goes on from where it was, never jumps to its fullest
        t = END;
        invalidate();
        out(SKIP_OUT_MS);
    }

    private void out(long ms) {
        if (ending) return;
        ending = true;
        ValueAnimator f = ValueAnimator.ofFloat(0f, 1f);
        f.setDuration(ms);
        f.setInterpolator(new LinearInterpolator());   // the easing is in onDraw: the figure and the dim each on their own curve
        f.addUpdateListener(v -> { gone = (float) v.getAnimatedValue(); invalidate(); });
        f.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(AmiiboShow.this);
                Runnable d = done;
                done = null;
                if (d != null) d.run();
            }
        });
        f.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) skip();
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        if (!cracked) crack();
        super.onDetachedFromWindow();
    }

    /**
     * The figure's base in the picture: the lowest row with something drawn, and the middle of what is drawn just above it (the stand of the figure). A
     * picture without transparency, or one that cannot be read, stands by its bottom middle
     */
    static float[] base(Bitmap b) {
        float[] none = {0.5f, 1f};
        if (b == null || !b.hasAlpha() || b.getConfig() == Bitmap.Config.HARDWARE) return none;
        int w = b.getWidth(), h = b.getHeight();
        if (w < 4 || h < 4) return none;
        int[] row = new int[w];
        int bottom = -1;
        try {
            for (int y = h - 1; y >= 0 && bottom < 0; y--) {
                b.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) if ((row[x] >>> 24) > 100) { bottom = y; break; }
            }
            if (bottom < 0) return none;
            int band = Math.max(2, h / 16), left = w, right = -1;
            for (int y = bottom; y > bottom - band && y >= 0; y--) {
                b.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) if ((row[x] >>> 24) > 100) { if (x < left) left = x; if (x > right) right = x; }
            }
            if (right < left) return none;
            return new float[] {(left + right + 1) / 2f / w, (bottom + 1f) / h};
        } catch (RuntimeException e) {
            return none;
        }
    }

    // ---- drawing ----

    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    private static float out3(float x) { float y = 1 - clamp(x); return 1 - y * y * y; }   // decelerating
    private static float in2(float x) { float y = clamp(x); return y * y; }                 // a fall
    private static float smooth(float x) { float y = clamp(x); return y * y * (3 - 2 * y); }  // eased in and out: no step at either end

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float a = 1 - smooth(gone / 0.7f);   // the figure, the stand and the words go first
        // the game dimmed a little, so the stand and the figure read: eased in, and lifted last, eased too
        float dim = (dimHeld >= 0 ? dimHeld : smooth(t / DIM_IN)) * (1 - smooth(gone));
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(DIM * dim), 0, 0, 0));
        c.drawRect(0, 0, w, h, paint);

        float cx = w / 2f, cy = h * 0.66f, rx = dp(80), ry = rx * 0.28f;
        float standIn = smooth((t - 60) / (DROP_AT - 20));
        int sa = Math.round(255 * a * standIn);
        // the stand: its shadow, the disc, the banana ring, a faint glow on its top
        paint.setColor(Color.argb(Math.round(sa * 0.85f), 11, 11, 12));
        c.drawOval(new RectF(cx - rx, cy - ry + dp(8), cx + rx, cy + ry + dp(8)), paint);
        paint.setColor(Color.argb(sa, 0x20, 0x21, 0x24));
        c.drawOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint);
        paint.setColor(Color.argb(Math.round(sa * 0.12f), 0xFF, 0xD5, 0x4F));
        c.drawOval(new RectF(cx - rx * 0.62f, cy - ry * 0.62f, cx + rx * 0.62f, cy + ry * 0.62f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3.5f));
        paint.setColor(Color.argb(sa, 0xFF, 0xD5, 0x4F));
        c.drawOval(new RectF(cx - rx + dp(3), cy - ry + dp(1.5f), cx + rx - dp(3), cy + ry - dp(1.5f)), paint);
        float pulse = clamp((t - 60) / 520f);   // once, outward, fading
        if (pulse > 0 && pulse < 1) {
            float s = 1 + 0.35f * out3(pulse);
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(Color.argb(Math.round(204 * a * (1 - pulse)), 0xFF, 0xD5, 0x4F));
            c.drawOval(new RectF(cx - rx * s, cy - ry * s, cx + rx * s, cy + ry * s), paint);
        }
        paint.setStyle(Paint.Style.FILL);

        // the peanut: drops (a fall, no bounce), lands on the stand's centre, cracks and opens, and fades as the figure rises
        float size = dp(124), scale = size / 108f;
        float footY = cy - ry * 0.1f;
        if (t >= DROP_AT) {
            float fall = in2((t - DROP_AT) / (LAND_AT - DROP_AT));
            float top = -size, landY = footY - 90 * scale;   // from above the screen to its foot on the stand
            float y = top + (landY - top) * fall;
            float crackT = clamp((t - LAND_AT) / (CRACK_AT - LAND_AT));
            float swing = out3((t - CRACK_AT) / (OPEN_END - CRACK_AT));
            float faded = clamp((t - OPEN_END) / 220f);
            int pa = Math.round(255 * a * (1 - faded));
            if (pa > 0) {
                if (swing > 0) {   // the kernel's light, behind the opening halves
                    float gx = cx, gy = y + 56 * scale, gr = dp(30 + 36 * swing);
                    paint.setShader(new RadialGradient(gx, gy, gr, new int[] {Color.argb(Math.round(230 * swing * a * (1 - faded)), 0xFF, 0xF3, 0xC4),
                            Color.argb(Math.round(110 * swing * a * (1 - faded)), 0xFF, 0xD5, 0x4F), Color.argb(0, 0xFF, 0xD5, 0x4F)}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                    c.drawCircle(gx, gy, gr, paint);
                    paint.setShader(null);
                }
                c.save();
                c.translate(cx - 54 * scale, y);
                c.scale(scale, scale);
                art.draw(c, crackT, swing, 24.5f * swing, pa);
                c.restore();
            }
        }

        // the figure: out of the kernel, growing as it rises, then standing on the ring
        if (t >= CRACK_AT) {
            float rise = out3((t - CRACK_AT) / (float) (RISE_END - CRACK_AT));
            float maxH = dp(190), maxW = dp(150);
            float kernelY = footY - 34 * scale, s = 0.28f + 0.72f * rise;
            float bottom = kernelY + (cy - kernelY) * rise;
            int fa = Math.round(255 * a * clamp(rise * 2.2f));
            if (picture != null) {
                float k = Math.min(maxH / picture.getHeight(), maxW / picture.getWidth()) * s;
                float pw = picture.getWidth() * k, ph = picture.getHeight() * k;
                float left = cx - baseX * pw, top = bottom - baseY * ph;   // its base on the ring's centre
                paint.setAlpha(fa);
                c.drawBitmap(picture, null, new RectF(left, top, left + pw, top + ph), paint);
                paint.setAlpha(255);
            } else if (stand != null) {   // no picture (off, offline, or none): Peanuts' own card, the stand icon
                int side = Math.round(dp(96) * s);
                stand.setBounds(Math.round(cx - side / 2f), Math.round(bottom - side - dp(8)), Math.round(cx + side / 2f), Math.round(bottom - dp(8)));
                stand.setAlpha(fa);
                stand.draw(c);
            }
        }

        // the line and the name, under the stand
        float words = clamp((t - OPEN_END) / 200f);
        if (words > 0) {
            text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            text.setTextSize(dp(11));
            text.setLetterSpacing(0.14f);
            text.setColor(Color.argb(Math.round(255 * a * words), 0xCD, 0xB4, 0x8C));
            c.drawText("ON THE STAND", cx, cy + ry + dp(34), text);   // what happened, in the show's small capitals; no title beside amiibo
            text.setLetterSpacing(0f);
            text.setTextSize(dp(18));
            text.setColor(Color.argb(Math.round(255 * a * words), 0xE8, 0xEA, 0xED));
            if (name != null) c.drawText(name, cx, cy + ry + dp(58), text);
        }
    }
}
