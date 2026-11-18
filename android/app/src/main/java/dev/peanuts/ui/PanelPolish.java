package dev.peanuts.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * PEANUTS: a restyle of the in-game side panel's tabs, called from SidePanel.look() after a tab is built. It only changes looks, never behaviour and never removes a
 * view: short radio groups become segmented controls, the section headings (SidePanel.head: bold, 15 sp, an 18 dp top gap, no side padding) and the grey notes
 * (SidePanel.note: 12 sp, light grey, not bold) get one look each, and the all-caps default Buttons are rounded. A styled view is tagged, so a rebuilt tab
 * (or a second call) is styled once.
 */
public final class PanelPolish {
    private PanelPolish() {}

    private static final String DONE = "pn_polish";
    private static final int ACCENT = 0xFFFFD54F;   // SidePanel.FOCUS, the selected tab's colour
    private static final int OUTLINE = 0xFF555555;

    public static void apply(View root) {
        if (root == null) return;
        float dp = root.getResources().getDisplayMetrics().density, sp = root.getResources().getDisplayMetrics().scaledDensity;
        walk(root, dp, sp);
    }

    private static void walk(View v, float dp, float sp) {
        if (DONE.equals(v.getTag())) return;
        if (v instanceof RadioGroup) segmented((RadioGroup) v, dp);
        else if (v instanceof Button) button((Button) v, dp);
        else if (v instanceof TextView && !(v instanceof CompoundButton)) text((TextView) v, dp, sp);
        if (v instanceof ViewGroup && !(v instanceof RadioGroup)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), dp, sp);
        }
    }

    private static void segmented(RadioGroup g, float dp) {
        int n = g.getChildCount();
        if (n < 1 || n > 4) return;
        for (int i = 0; i < n; i++) {
            View c = g.getChildAt(i);
            if (!(c instanceof RadioButton) || String.valueOf(((RadioButton) c).getText()).length() > 12) return;
        }
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setTag(DONE);
        for (int i = 0; i < n; i++) {
            RadioButton b = (RadioButton) g.getChildAt(i);
            b.setButtonDrawable(null);
            b.setGravity(Gravity.CENTER);
            b.setSingleLine(true);
            b.setPadding(Math.round(6 * dp), 0, Math.round(6 * dp), 0);
            b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {0xFF000000, 0xFFE8EAED}));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[] {android.R.attr.state_checked}, pill(ACCENT, 0, dp));
            bg.addState(new int[] {}, pill(Color.TRANSPARENT, OUTLINE, dp));
            b.setBackground(bg);
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(i == 0 ? 0 : Math.round(3 * dp), Math.round(2 * dp), i == n - 1 ? 0 : Math.round(3 * dp), Math.round(2 * dp));
            b.setLayoutParams(lp);
        }
    }

    private static GradientDrawable pill(int fill, int stroke, float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(8 * dp);
        if (stroke != 0) d.setStroke(Math.round(dp), stroke);
        return d;
    }

    private static void text(TextView t, float dp, float sp) {
        // Headings and notes (SidePanel.head / note) are neither focusable nor clickable. A tab label is both, so it keeps its own size (the
        // tab bar sets one size for all of them); a view in a row of weighted children (the tab bar) is left alone as a second guard
        if (t.isFocusable() || t.isClickable() || inWeightedRow(t)) return;
        Typeface tf = t.getTypeface();
        boolean bold = tf != null && tf.isBold();
        float size = t.getTextSize();
        if (bold && t.getPaddingLeft() == 0 && t.getPaddingTop() >= Math.round(12 * dp) && size >= 13 * sp) {
            float scale = size / (15 * sp);   // the panel's text size setting scales every size; keep it
            t.setTag(DONE);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13 * sp * scale);
            t.setTextColor(0xFFC8C8C8);
            t.setPadding(0, Math.round(20 * dp), 0, 0);
        } else if (!bold && t.getCurrentTextColor() == Color.LTGRAY && size <= 13 * sp) {
            float scale = size / (12 * sp);
            t.setTag(DONE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12 * sp * scale);
            t.setTextColor(0xFF8A8A8A);
            t.setLineSpacing(0, 1.15f);
        }
    }

    /** True when v's parent is a LinearLayout whose children all have a layout weight (the tab bar's row of tabs) */
    private static boolean inWeightedRow(View v) {
        if (!(v.getParent() instanceof LinearLayout)) return false;
        LinearLayout row = (LinearLayout) v.getParent();
        int n = row.getChildCount();
        if (n == 0) return false;
        for (int i = 0; i < n; i++) {
            View c = row.getChildAt(i);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (!(lp instanceof LinearLayout.LayoutParams) || ((LinearLayout.LayoutParams) lp).weight <= 0) return false;
        }
        return true;
    }

    private static void button(Button b, float dp) {
        if (!b.isAllCaps()) return;   // the panel's list rows (SidePanel.row) already turned it off
        TypedValue tv = new TypedValue();
        int fill = b.getContext().getTheme().resolveAttribute(android.R.attr.colorButtonNormal, tv, true) ? tv.data : 0xFF555555;
        b.setTag(DONE);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setBackground(pill(fill, 0, dp));
    }
}
