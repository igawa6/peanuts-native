package dev.peanuts.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;

/**
 * How the in-game menus look, picked once by the player: at the first start after the update, and for a new player at the start after the one that ran the
 * first start's setup (one question a start), Home's list shows the layouts as pictures (drawable/pil_layout_*.xml). Two menus (the game menu at the left, the quick menu at the right), One menu at the right, and on a device with
 * a second screen Bottom screen (one menu there), which the focus starts on there. Later (or closing it) keeps the two menus and asks once more at the next
 * start, then no more. The menu's own settings change it later (Layout, dev.nxnative.android.InGamePanel).
 */
public final class LayoutChoice {
    private LayoutChoice() {}

    private static boolean asked;   // once a run of the app
    private static boolean setupRun;   // this run showed the first start's setup: its new player is asked at the next start
    /** Shown at most this often: closed without a pick, once more at the next start */
    static final int TIMES = 2;
    /** Each picture's box: the cards, their names and lines fit a handheld's screen under the title */
    private static final int PICTURE_DP = 108;
    /** A card takes no press this soon after the question shows: a button already on its way (an A for Home's list) picks nothing */
    static final long INPUT_DELAY_MS = 600;

    private static final int[] LAYOUTS = {PeanutsPrefs.LAYOUT_TWO, PeanutsPrefs.LAYOUT_RIGHT, PeanutsPrefs.LAYOUT_BOTTOM};
    private static final String[] PICTURES = {"pil_layout_two", "pil_layout_right", "pil_layout_bottom"};   // Peanuts' res (src/peanuts/res): by name
    private static final String[] NAMES = {"Two menus", "One menu at the right", "Bottom screen"};   // short: each name fits one line of its card, so the lines under them start level
    private static final String[] LINES = {
            "The game menu at the left, the settings at the right.",
            "Everything in one place over the game.",
            "The game in full view on top, the menu below."};

    /** Home's list, each time it fills: the question while no layout is picked, once a run, at most TIMES in all, never in the run of the setup */
    public static void offer(Activity a) {
        if (!PeanutsPrefs.setupDone(a)) { setupRun = true; return; }
        if (asked || setupRun || a.isFinishing() || PeanutsPrefs.menuLayoutPicked(a) || PeanutsPrefs.menuLayoutAsked(a) >= TIMES) return;
        asked = true;
        boolean second = dev.peanuts.Companion.hasSecondDisplay(a);
        int count = second ? 3 : 2;
        float dp = a.getResources().getDisplayMetrics().density;
        boolean wide = a.getResources().getDisplayMetrics().widthPixels > a.getResources().getDisplayMetrics().heightPixels;

        // A dialog of its own: Android's AlertDialog keeps its custom content to a part of a landscape screen, too short for the pictures and their lines
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(22 * dp);
        box.setPadding(pad, Math.round(20 * dp), pad, Math.round(10 * dp));
        box.setBackground(PUi.round(PUi.col(a, R.color.bn_card), 28 * dp, 0, 0));
        TextView title = PUi.text(a, "How should the in-game menus look?", 22, R.color.bn_text, 700);
        box.addView(title);
        TextView intro = PUi.text(a, "Pick how the menus look in a game. The menu's own settings change it later.", 14, R.color.bn_text_2, 400);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = Math.round(6 * dp);
        box.addView(intro, ip);
        LinearLayout cards = new LinearLayout(a);
        cards.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        cards.setBaselineAligned(false);   // side by side, the cards are one size: as tall as the tallest (a name on two lines)
        View[] made = new View[count];
        long[] shownAt = {Long.MAX_VALUE};
        for (int i = 0; i < count; i++) {
            int layout = LAYOUTS[i];
            View card = card(a, dp, i, second && layout == PeanutsPrefs.LAYOUT_BOTTOM);
            card.setOnClickListener(v -> {
                if (android.os.SystemClock.uptimeMillis() - shownAt[0] < INPUT_DELAY_MS) return;   // an A pressed for Home, not for this question
                PeanutsPrefs.menuLayoutPick(a, layout);
                d.dismiss();
            });
            LinearLayout.LayoutParams lp = wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int gap = Math.round(6 * dp);
            lp.setMargins(gap, Math.round(14 * dp), gap, 0);
            cards.addView(card, lp);
            made[i] = card;
        }
        ScrollView list = new ScrollView(a);   // a short screen: the cards scroll between the title and Later
        list.addView(cards);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));   // the one to shrink
        TextView later = PUi.text(a, "Later", 15, R.color.bn_accent, 700);
        int lh = Math.round(16 * dp), lv = Math.round(10 * dp);
        later.setPadding(lh, lv, lh, lv);
        later.setBackground(PUi.ripple(a, PUi.round(android.graphics.Color.TRANSPARENT, 99 * dp, 0, 0)));
        later.setFocusable(true);
        later.setClickable(true);
        later.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = android.view.Gravity.END;
        bp.topMargin = Math.round(8 * dp);
        box.addView(later, bp);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            int sw = a.getResources().getDisplayMetrics().widthPixels;
            int w = wide ? Math.min(Math.round(sw * 0.92f), Math.round((count == 3 ? 900 : 640) * dp)) : Math.round(sw * 0.92f);
            d.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.setOnShowListener(x -> {   // asked once it shows, not before (a dialog that never showed asks again)
            shownAt[0] = android.os.SystemClock.uptimeMillis();
            PeanutsPrefs.menuLayoutAsked(a, PeanutsPrefs.menuLayoutAsked(a) + 1);
        });
        d.show();
        View first = made[second ? count - 1 : 0];   // two screens: the bottom screen's; else the first
        boolean controller = padAttached();   // with a controller the frame shows at once where it starts
        first.post(() -> { if (controller) first.requestFocusFromTouch(); else first.requestFocus(); });
    }

    private static boolean padAttached() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d != null && !d.isVirtual() && d.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD)) return true;
        }
        return false;
    }

    /** A layout's card: its picture, its name, what it does; the controller's focus as a frame in banana yellow, a touch's ripple */
    private static View card(Activity a, float dp, int i, boolean twoScreens) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(10 * dp);
        c.setPadding(p, p, p, p);
        float r = 18 * dp;
        StateListDrawable frame = new StateListDrawable();   // over the card: the controller's focus as an outline, nothing else
        frame.addState(new int[] {android.R.attr.state_focused}, PUi.round(android.graphics.Color.TRANSPARENT, r, PUi.col(a, R.color.bn_banana_deep), Math.round(3 * dp)));
        frame.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        c.setBackground(PUi.ripple(a, PUi.round(PUi.col(a, R.color.bn_tint), r, 0, 0)));
        c.setForeground(frame);
        c.setFocusable(true);
        c.setClickable(true);
        c.setDefaultFocusHighlightEnabled(false);
        c.setContentDescription(NAMES[i]);
        ImageView pic = new ImageView(a);
        int id = a.getResources().getIdentifier(PICTURES[i], "drawable", a.getPackageName());
        if (id != 0) pic.setImageResource(id);
        pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.widget.FrameLayout top = new android.widget.FrameLayout(a);   // the picture, with the two screens' badge in its corner
        top.addView(pic, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (twoScreens) {
            TextView tag = PUi.text(a, "For two screens", 12, R.color.bn_on_banana, 600);
            int h = Math.round(8 * dp), v = Math.round(3 * dp);
            tag.setPadding(h, v, h, v);
            tag.setBackground(PUi.round(PUi.col(a, R.color.bn_banana), 99 * dp, 0, 0));
            top.addView(tag, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.START));
        }
        c.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(PICTURE_DP * dp)));   // one box for every picture, so the names line up
        TextView name = PUi.text(a, NAMES[i], 15, R.color.bn_text, 700);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = Math.round(8 * dp);
        c.addView(name, np);
        TextView line = PUi.text(a, LINES[i], 12.5f, R.color.bn_text_2, 400);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(2 * dp);
        c.addView(line, lp);
        return c;
    }
}
