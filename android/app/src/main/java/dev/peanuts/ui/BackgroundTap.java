package dev.peanuts.ui;

/**
 * A tap on a page's background, as MainActivity.bananaTaps tells one on the top screen: a press that starts where nothing else is, moves no further than the
 * touch slop and lifts before a long press. No Android types (BackgroundTapJvmTest).
 */
final class BackgroundTap {
    private float x, y;
    private boolean may;

    /** The finger went down; empty: no card, button or row is under it */
    void down(float x, float y, boolean empty) {
        this.x = x;
        this.y = y;
        may = empty;
    }

    /** The finger moved: past the slop it is a drag, not a tap */
    void move(float x, float y, float slop) {
        if (Math.abs(x - this.x) > slop || Math.abs(y - this.y) > slop) may = false;
    }

    /** The finger lifted after heldMs: true when that was a tap on the background */
    boolean up(float x, float y, float slop, long heldMs, long longPressMs) {
        move(x, y, slop);
        boolean tap = may && heldMs < longPressMs;
        may = false;
        return tap;
    }

    /** A second finger, a cancel: no tap */
    void cancel() { may = false; }
}
