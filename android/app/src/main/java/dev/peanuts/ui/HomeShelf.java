package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Row's and Carousel's strip of covers (HomeViews): every game's cover, the chosen one large, the others small beside it. Row keeps the chosen one at the
 * left edge, the next ones after it; Carousel keeps it in the middle, its neighbours looking in at the edges. One focusable view for a controller: left and
 * right choose the game before or after it (the covers glide, 150 ms in Row, 200 ms in Carousel; a fade under reduced motion), A is the cover's tap, up and
 * down leave the strip. A finger swipes it (a drag follows the finger, a flick carries on, it settles on a game, never past the ends); a tap on another
 * cover chooses it, a tap on the chosen one is its tap (Play, the sheet), a long press its second action. Covers move by their translation and scale only:
 * a move lays nothing out. TalkBack reads the chosen game and scrolls with its gestures.
 */
final class HomeShelf extends ViewGroup {
    /** What the strip tells its owner (Library) */
    interface Listener {
        /** Another game is the chosen one; byUser: a key, a swipe or a tap (not a rebuild) */
        void chosen(int index, boolean byUser);
        void tap(int index);
        void hold(int index);
        /** What TalkBack says for the game at index */
        CharSequence describe(int index);
    }

    private final boolean carousel;
    private final Listener listener;
    private final List<View> covers = new ArrayList<>();
    private int big, small, gap;
    private int sel;
    private float pos;   // the strip's place in games: sel when it rests, fractional while it moves
    private ValueAnimator settle;
    private Drawable frame;   // the app's focus frame (PadFocus puts it on the focused view as its foreground): drawn round the chosen cover, not the strip
    private final android.graphics.Rect fr = new android.graphics.Rect();
    private final int slop, minFling;
    private VelocityTracker tracker;
    private float downX, downY, downPos;
    private boolean dragging, pressed;
    private final Runnable longPress;
    private final int[] at = new int[2];

    HomeShelf(Context c, boolean carousel, Listener l) {
        super(c);
        this.carousel = carousel;
        listener = l;
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setDefaultFocusHighlightEnabled(false);   // the frame round the chosen cover is the focus: no grey over the whole strip
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        longPress = () -> {
            if (!pressed || dragging) return;
            pressed = false;
            int i = under(downX, downY);
            if (i < 0) return;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (i != sel) choose(i, true, true);
            listener.hold(i);
        };
    }

    /** The covers (one per game, in the order shown) and the game chosen; sizes in px */
    void set(List<View> views, int chosen, int bigPx, int smallPx, int gapPx) {
        removeAllViews();
        covers.clear();
        covers.addAll(views);
        big = bigPx; small = smallPx; gap = gapPx;
        for (View v : views) {
            v.setClickable(false);
            v.setFocusable(false);
            v.setLongClickable(false);
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addView(v, new LayoutParams(big, big));
        }
        sel = covers.isEmpty() ? 0 : Math.max(0, Math.min(covers.size() - 1, chosen));
        pos = sel;
        place();
        describe();
    }

    int chosen() { return sel; }

    /** The covers' sizes again (Library fits the large one to the room the page leaves): laid out anew */
    void resize(int bigPx, int smallPx) {
        if (bigPx == big && smallPx == small) return;
        big = bigPx; small = smallPx;
        for (View v : covers) { ViewGroup.LayoutParams lp = v.getLayoutParams(); lp.width = big; lp.height = big; }
        requestLayout();
    }
    int count() { return covers.size(); }
    View cover(int i) { return i >= 0 && i < covers.size() ? covers.get(i) : null; }

    /** Chooses a game: glides there (animate), else jumps; byUser tells the listener a person did it */
    void choose(int i, boolean animate, boolean byUser) {
        if (covers.isEmpty()) return;
        i = Math.max(0, Math.min(covers.size() - 1, i));
        boolean changed = i != sel;
        sel = i;
        if (settle != null) settle.cancel();
        if (animate && Motion.on() && Math.abs(pos - i) > 0.001f) {
            settle = ValueAnimator.ofFloat(pos, i);
            settle.setDuration(carousel ? 200 : 150);
            settle.setInterpolator(Motion.OUT);
            settle.addUpdateListener(x -> { pos = (float) x.getAnimatedValue(); place(); });
            settle.start();
        } else {
            pos = i;
            place();
            if (animate && changed) {   // reduced motion: the new cover fades in where it is, nothing glides
                View v = covers.get(i);
                v.setAlpha(0.35f);
                v.animate().alpha(1f).setDuration(Motion.ENTER).setInterpolator(Motion.OUT);
            }
        }
        describe();
        if (changed) listener.chosen(i, byUser);
    }

    // ---- Layout: every cover is laid out large once; where it shows and how large is its translation and scale ----

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        for (View v : covers) v.measure(MeasureSpec.makeMeasureSpec(big, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(big, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, big);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int left = carousel ? (getWidth() - big) / 2 : 0;
        for (View v : covers) v.layout(left, 0, left + big, big);
        place();
    }

    /** Each cover's place for pos: size, then where (Row: bottoms in a line, Carousel: centres); covers far off the screen are not drawn */
    private void place() {
        int w = getWidth();
        if (w == 0 || big == 0) return;
        getLocationInWindow(at);
        float screenLeft = -at[0], screenRight = screenLeft + getRootView().getWidth();
        for (int i = 0; i < covers.size(); i++) {
            View v = covers.get(i);
            float d = i - pos, s = HomeViews.size(d, big, small), k = s / big;
            float x0;
            if (carousel) {
                v.setPivotX(big / 2f); v.setPivotY(big / 2f);
                v.setTranslationX(HomeViews.carouselX(d, big, small, gap));
                x0 = (w - big) / 2f + v.getTranslationX() + (big - s) / 2f;
            } else {
                v.setPivotX(0); v.setPivotY(big);
                v.setTranslationX(HomeViews.rowX(d, big, small, gap));
                x0 = v.getTranslationX();
            }
            v.setScaleX(k); v.setScaleY(k);
            if (!carousel) v.setAlpha(d < 0 ? Math.max(0f, 1f + d) : 1f);   // Row: the ones before the chosen one fade as they leave at the left
            boolean on = x0 + s > screenLeft - big && x0 < screenRight + big && (carousel || d > -1f);
            v.setVisibility(on ? VISIBLE : INVISIBLE);
        }
        invalidate();
    }

    /** The app's focus frame (PadFocus.watch sets it as the focused view's foreground) is kept, and drawn round the chosen cover instead of the strip */
    @Override public void setForeground(Drawable d) {
        if (frame != null) frame.setCallback(null);
        frame = d;
        if (d != null) d.setCallback(this);
        invalidate();
    }

    @Override public Drawable getForeground() { return frame; }

    @Override protected boolean verifyDrawable(Drawable who) { return who == frame || super.verifyDrawable(who); }

    /** The frame round the chosen cover, 4 dp outside it (as a grid cover's), while a controller is on the strip */
    @Override protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (frame == null || !isFocused() || isInTouchMode() || covers.isEmpty()) return;
        View v = covers.get(sel);
        float s = big * v.getScaleX(), x = v.getLeft() + v.getTranslationX() + v.getPivotX() * (1 - v.getScaleX()), y = v.getTop() + v.getTranslationY() + v.getPivotY() * (1 - v.getScaleY());
        int out = PUi.dp(getContext(), 4);
        fr.set(Math.round(x) - out, Math.round(y) - out, Math.round(x + s) + out, Math.round(y + s) + out);
        frame.setBounds(fr);
        frame.draw(c);
    }

    @Override protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        invalidate();
        if (gained && !covers.isEmpty()) listener.chosen(sel, false);
    }

    // ---- Keys ----

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (sel > 0) { Motion.held(e.getRepeatCount() > 0); choose(sel - 1, true, true); }
                return true;   // the strip has ends: no wrap, and the focus stays
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (sel < covers.size() - 1) { Motion.held(e.getRepeatCount() > 0); choose(sel + 1, true, true); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                if (e.getRepeatCount() == 0 && !covers.isEmpty()) { pressCover(); listener.tap(sel); }
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) { Motion.held(false); return true; }
        return super.onKeyUp(code, e);
    }

    private void pressCover() {
        View v = covers.get(sel);
        if (Motion.on()) v.animate().scaleX(v.getScaleX() * 0.97f).scaleY(v.getScaleY() * 0.97f).setDuration(Motion.SNAP).withEndAction(() -> { if (v.isAttachedToWindow()) place(); });
    }

    // ---- Touch: a drag follows the finger, a flick carries on, it settles on a game ----

    /** The covers take no touch of their own (not clickable): every touch on the strip comes here, and a vertical drag goes on to the page's scroll */
    @Override public boolean onTouchEvent(MotionEvent e) { return track(e); }

    private float step() {
        return carousel ? (big + small) / 2f + gap : small + gap;
    }

    private boolean track(MotionEvent e) {
        if (covers.isEmpty()) return false;
        if (tracker == null) tracker = VelocityTracker.obtain();
        tracker.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                if (settle != null && settle.isRunning()) { settle.cancel(); dragging = true; getParent().requestDisallowInterceptTouchEvent(true); }
                else dragging = false;
                downPos = pos;
                pressed = under(downX, downY) >= 0;
                removeCallbacks(longPress);
                if (pressed) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!dragging && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    pressed = false;
                    removeCallbacks(longPress);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    downX = e.getX();
                    downPos = pos;
                    dx = 0;
                }
                if (!dragging && Math.abs(dy) > slop) { pressed = false; removeCallbacks(longPress); }
                if (dragging) {
                    float p = downPos - dx / step(), last = covers.size() - 1;
                    if (p < 0) p = p / 3f;   // past an end the strip gives a little, then less
                    else if (p > last) p = last + (p - last) / 3f;
                    pos = p;
                    place();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (dragging) {
                    tracker.computeCurrentVelocity(1000);
                    float vx = tracker.getXVelocity();
                    float v = Math.abs(vx) < minFling ? 0 : -vx / step();
                    finishDrag(HomeViews.settle(pos, v, covers.size()));
                    return true;
                }
                if (pressed && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                    pressed = false;
                    int i = under(e.getX(), e.getY());
                    if (i == sel) { pressCover(); listener.tap(i); }   // its haptic is the tap's own (Play is the weighty one)
                    else if (i >= 0) { Haptics.play(this, Haptics.Word.CLICK); choose(i, true, true); }
                    return true;
                }
                pressed = false;
                return false;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                pressed = false;
                if (dragging) finishDrag(Math.round(Math.max(0, Math.min(covers.size() - 1, pos))));
                return false;
            default:
                return dragging;
        }
    }

    private void finishDrag(int target) {
        dragging = false;
        if (tracker != null) { tracker.recycle(); tracker = null; }
        if (target != sel) Haptics.play(this, Haptics.Word.TICK);
        boolean changed = target != sel;
        sel = target;
        if (settle != null) settle.cancel();
        float from = pos;
        if (Motion.on() && Math.abs(from - target) > 0.001f) {
            settle = ValueAnimator.ofFloat(from, target);
            settle.setDuration(Math.min(320, 120 + Math.round(Math.abs(from - target) * 90)));
            settle.setInterpolator(Motion.OUT);
            settle.addUpdateListener(x -> { pos = (float) x.getAnimatedValue(); place(); });
            settle.start();
        } else { pos = target; place(); }
        describe();
        if (changed) listener.chosen(target, true);
    }

    /** The game whose cover is under a point of the strip, the nearer the chosen one first (they overlap while they move); -1 for none */
    private int under(float x, float y) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < covers.size(); i++) {
            View v = covers.get(i);
            if (v.getVisibility() != VISIBLE) continue;
            float s = big * v.getScaleX(), left = v.getLeft() + v.getTranslationX() + v.getPivotX() * (1 - v.getScaleX()), top = v.getTop() + v.getPivotY() * (1 - v.getScaleY());
            if (x >= left && x <= left + s && y >= top && y <= top + s) {
                float d = Math.abs(i - pos);
                if (d < bestD) { bestD = d; best = i; }
            }
        }
        return best;
    }

    // ---- TalkBack ----

    private void describe() {
        if (covers.isEmpty()) return;
        CharSequence d = listener.describe(sel);
        setContentDescription(d + ", " + (sel + 1) + " of " + covers.size());
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClickable(true);
        info.setLongClickable(true);
        if (sel > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (sel < covers.size() - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && sel < covers.size() - 1) { choose(sel + 1, true, true); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && sel > 0) { choose(sel - 1, true, true); return true; }
        if (action == AccessibilityNodeInfo.ACTION_CLICK && !covers.isEmpty()) { listener.tap(sel); return true; }
        if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK && !covers.isEmpty()) { listener.hold(sel); return true; }
        return super.performAccessibilityAction(action, args);
    }

    @Override public CharSequence getAccessibilityClassName() { return android.widget.HorizontalScrollView.class.getName(); }

    @Override protected void onDetachedFromWindow() {
        if (settle != null) settle.cancel();
        removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }
}
