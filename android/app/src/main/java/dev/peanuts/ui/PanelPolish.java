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
 * (or a second call) is styled once. In the quick menu (the canvas page "In-game menus") a heading is a section head in capitals in peanut tan, and a
 * slider's label ("Size 100 %") reads as its name at the left and its value at the right.
 */
public final class PanelPolish {
    private PanelPolish() {}

    private static final String DONE = "pn_polish";
    // The rows already moved clear of the focus frame: a weak set, not a keyed tag, so this class needs no R (dm/tools/tests/test_android_hud compiles it alone)
    private static final java.util.Set<View> INSET = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final int ACCENT = 0xFFFFD54F;   // SidePanel.FOCUS, the selected tab's colour
    private static final int OUTLINE = 0xFF555555;
    private static final int HEAD = 0xFFCDB48C;   // peanut tan: the section heads (dev.peanuts.ingame.Look.TAN)
    private static final int TRACK = 0xFF2D2E31, PEANUT = 0xFF4A3920, CREAM = 0xFFFFF1D2, QUIET = 0xFFB0B3B8;   // a choice's track, its chosen part (the rail's block)
    private static final java.util.regex.Pattern VALUED = java.util.regex.Pattern.compile("^(\\D+?)\\s+(\\d.*)$");

    public static void apply(View root) {
        if (root == null) return;
        float dp = root.getResources().getDisplayMetrics().density, sp = root.getResources().getDisplayMetrics().scaledDensity;
        walk(root, dp, sp);
    }

    private static void walk(View v, float dp, float sp) {
        // The panel's yellow frame (SidePanel.focusMoved) alone shows the focus: no grey veil of the system's over a row, a choice or a switch
        if (v.isFocusable()) v.setDefaultFocusHighlightEnabled(false);
        if ((v instanceof CompoundButton || v instanceof android.widget.SeekBar) && v.getBackground() instanceof android.graphics.drawable.RippleDrawable) {
            // The theme's borderless ripple of a switch, a radio or a slider: a light circle around the focused one, drawn on the sheet behind the rows.
            // The frame shows the focus, and the switch, the choice or the thumb itself shows the change
            v.setBackground(null);
        }
        if (DONE.equals(v.getTag())) return;
        if (v instanceof RadioGroup) {
            for (int i = 0; i < ((RadioGroup) v).getChildCount(); i++) ((RadioGroup) v).getChildAt(i).setDefaultFocusHighlightEnabled(false);
            if (!segmented((RadioGroup) v, dp)) radios((RadioGroup) v, dp);
        }
        else if (v instanceof android.widget.CheckBox) switchLook((android.widget.CheckBox) v, dp);
        else if (v instanceof android.widget.SeekBar) slider((android.widget.SeekBar) v);
        else if (v instanceof Button) button((Button) v, dp);
        else if (v instanceof TextView && !(v instanceof CompoundButton)) text((TextView) v, dp, sp);
        if (v.isFocusable() && !(v instanceof android.widget.SeekBar) && !(v.getParent() instanceof RadioGroup) && v.getPaddingLeft() < Math.round(6 * dp)) inset(v, dp);
        if (v instanceof ViewGroup && !(v instanceof RadioGroup)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), dp, sp);
            for (int i = 0; i + 1 < g.getChildCount(); i++) valued(g, i, dp);
        }
    }

    /**
     * A short choice (up to 4, labels up to 12 characters): one track, the panel's row grey, its parts side by side; the chosen one a peanut-brown block with
     * its label in peanut cream (the rail's selected tab), the others in the quiet grey. The controller's yellow frame stays the focus alone. False: not short
     */
    private static boolean segmented(RadioGroup g, float dp) {
        int n = g.getChildCount();
        if (n < 1 || n > 4) return false;
        for (int i = 0; i < n; i++) {
            View c = g.getChildAt(i);
            if (!(c instanceof RadioButton) || String.valueOf(((RadioButton) c).getText()).length() > 12) return false;
        }
        g.setOrientation(LinearLayout.HORIZONTAL);
        g.setTag(DONE);
        int pad = Math.round(3 * dp);
        g.setPadding(pad, pad, pad, pad);
        g.setBackground(pill(TRACK, 0, dp, 12));
        for (int i = 0; i < n; i++) {
            RadioButton b = (RadioButton) g.getChildAt(i);
            b.setButtonDrawable(null);
            b.setGravity(Gravity.CENTER);
            b.setMaxLines(1);   // not setSingleLine: its horizontal scrolling moves the foreground (SidePanel's focus frame) out of sight
            b.setMinHeight(Math.round(38 * dp));
            b.setPadding(Math.round(8 * dp), 0, Math.round(8 * dp), 0);
            b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {-android.R.attr.state_enabled}, {}}, new int[] {CREAM, 0x66B0B3B8, QUIET}));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[] {android.R.attr.state_checked}, pill(PEANUT, 0, dp, 9));
            bg.addState(new int[] {}, pill(Color.TRANSPARENT, 0, dp, 9));
            b.setBackground(bg);
            RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, 0, 0);
            b.setLayoutParams(lp);
        }
        return true;
    }

    /**
     * The controller's focus came into a track of choices (Auto / On / Off, made by segmented) from outside it or from nothing (a menu opening), onto one that is not the chosen one: the
     * chosen one, which the focus takes instead, as a console's menus land on the current value (Android picks the nearest by its position). Null when
     * nothing is to change. A list of choices one under the other keeps its step to the next row
     */
    public static View chosen(View old, View now) {
        if (!(now instanceof RadioButton) || !(now.getParent() instanceof RadioGroup)) return null;
        if (old != null) for (android.view.ViewParent o = old.getParent(); o != null; o = o.getParent()) if (o == now.getParent()) return null;   // a step inside the track
        View c = landing(now);
        return c == now ? null : c;
    }

    /** Where the focus lands for v: the chosen one of a track of choices when v is another of its choices, else v itself */
    public static View landing(View v) {
        if (!(v instanceof RadioButton) || ((RadioButton) v).isChecked() || !(v.getParent() instanceof RadioGroup)) return v;
        RadioGroup g = (RadioGroup) v.getParent();
        if (!DONE.equals(g.getTag()) || g.getOrientation() != LinearLayout.HORIZONTAL) return v;
        View c = g.findViewById(g.getCheckedRadioButtonId());
        return c != null && c.getParent() == g && c.isEnabled() && c.isFocusable() ? c : v;
    }

    /**
     * A longer list of choices (the frame rate cap, Console mode): a still mark of the panel's own, a ring and, when chosen, a dot in its yellow (the free
     * ones grey). SidePanel's focus frame lies on a choice's edge, which was its mark's and its text's: the list reaches 10 dp into the rows' side padding
     * at both ends, each choice takes the row's width and its mark and text move 10 dp in, so the frame keeps clear of both as on the other rows and the
     * marks stay under the headings
     */
    private static void radios(RadioGroup g, float dp) {
        if (DONE.equals(g.getTag())) return;
        g.setTag(DONE);
        int in = Math.round(10 * dp);
        ViewGroup p = g.getParent() instanceof ViewGroup ? (ViewGroup) g.getParent() : null;
        boolean room = p != null && p.getPaddingLeft() >= in && p.getPaddingRight() >= in && g.getOrientation() == LinearLayout.VERTICAL
                && g.getLayoutParams() instanceof LinearLayout.LayoutParams;
        if (room) {
            p.setClipToPadding(false);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) g.getLayoutParams();
            lp.leftMargin -= in;
            lp.rightMargin -= in;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            g.setLayoutParams(lp);
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            if (!(g.getChildAt(i) instanceof RadioButton)) continue;
            RadioButton b = (RadioButton) g.getChildAt(i);
            b.setButtonTintList(null);
            if (b.getBackground() instanceof android.graphics.drawable.RippleDrawable) b.setBackground(null);   // the theme's grey circle on the focused mark: the frame shows the focus
            b.setButtonDrawable(radioMark(dp, room ? in : 0));   // not the theme's animated mark: inside a wrapper its check animation stops before the dot
            b.setMinHeight(Math.round(40 * dp));   // the row's height, which the theme's taller mark gave it
            if (room) {
                b.setPadding(b.getPaddingLeft(), b.getPaddingTop(), b.getPaddingRight() + in, b.getPaddingBottom());
                ViewGroup.LayoutParams blp = b.getLayoutParams();
                if (blp != null) { blp.width = ViewGroup.LayoutParams.MATCH_PARENT; b.setLayoutParams(blp); }
            }
        }
    }

    /** A list choice's mark: a ring 18 dp across, a dot in it when chosen; the panel's yellow when chosen, grey when free, faint when it cannot be chosen */
    private static android.graphics.drawable.Drawable radioMark(float dp, int left) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_checked, android.R.attr.state_enabled}, ring(dp, ACCENT, true));
        s.addState(new int[] {android.R.attr.state_checked}, ring(dp, 0x669AA0A6, true));
        s.addState(new int[] {-android.R.attr.state_enabled}, ring(dp, 0x669AA0A6, false));
        s.addState(new int[] {}, ring(dp, 0xFF9AA0A6, false));
        return new android.graphics.drawable.InsetDrawable(s, left + Math.round(3 * dp), 0, Math.round(12 * dp), 0);
    }

    private static android.graphics.drawable.Drawable ring(float dp, int color, boolean dot) {
        int size = Math.round(18 * dp);
        GradientDrawable r = new GradientDrawable();
        r.setShape(GradientDrawable.OVAL);
        r.setStroke(Math.round(2 * dp), color);
        r.setSize(size, size);
        if (!dot) return r;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {r, d});
        int i = Math.round(5 * dp);
        l.setLayerInset(1, i, i, i, i);
        return l;
    }

    /** A check box: its text over the row's width and a switch at its end, as the Mods tab's (track and thumb in the panel's yellow, grey when off) */
    private static void switchLook(android.widget.CheckBox b, float dp) {
        if (DONE.equals(b.getTag())) return;
        b.setTag(DONE);
        b.setButtonDrawable(null);
        StateListDrawable sw = new StateListDrawable();
        sw.addState(new int[] {android.R.attr.state_checked}, toggle(dp, true));
        sw.addState(new int[] {}, toggle(dp, false));
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, sw, null);
        b.setCompoundDrawablePadding(Math.round(12 * dp));
        b.setMinHeight(Math.round(44 * dp));
        if (b.getLayoutParams() instanceof LinearLayout.LayoutParams) b.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        b.setPaddingRelative(0, b.getPaddingTop(), 0, b.getPaddingBottom());
        inset(b, dp);
    }

    /**
     * A focusable row whose text starts at its edge (a switch, a list row such as Preset): SidePanel's focus frame lies on the row's edge and would cover
     * its first letter. The row reaches 10 dp into the rows' side padding at both ends and its content moves 10 dp in, so the text stays under the
     * headings and the frame keeps clear of it. Only where the parent has that padding (the panel's rows); once a row (INSET)
     */
    private static void inset(View v, float dp) {
        int in = Math.round(10 * dp);
        if (INSET.contains(v) || !(v.getParent() instanceof ViewGroup) || !(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        ViewGroup p = (ViewGroup) v.getParent();
        if (p.getPaddingLeft() < in || p.getPaddingRight() < in) return;
        INSET.add(v);
        p.setClipToPadding(false);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.leftMargin -= in;
        lp.rightMargin -= in;
        v.setLayoutParams(lp);
        v.setPadding(v.getPaddingLeft() + in, v.getPaddingTop(), v.getPaddingRight() + in, v.getPaddingBottom());
    }

    private static android.graphics.drawable.Drawable toggle(float dp, boolean on) {
        GradientDrawable track = pill(on ? 0xAAFFD54F : 0xFF5F6368, 0, dp, 7);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(on ? ACCENT : 0xFFBDC1C6);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {track, thumb});
        int w = Math.round(38 * dp), h = Math.round(22 * dp);
        l.setLayerSize(0, Math.round(34 * dp), Math.round(14 * dp));
        l.setLayerGravity(0, Gravity.CENTER);
        l.setLayerSize(1, Math.round(20 * dp), Math.round(20 * dp));
        l.setLayerGravity(1, Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        l.setBounds(0, 0, w, h);
        return new android.graphics.drawable.InsetDrawable(l, 0) { @Override public int getIntrinsicWidth() { return w; } @Override public int getIntrinsicHeight() { return h; } };
    }

    /** A slider: its played part and thumb in the panel's yellow over a grey track */
    private static void slider(android.widget.SeekBar s) {
        if (DONE.equals(s.getTag())) return;
        s.setTag(DONE);
        s.setProgressTintList(ColorStateList.valueOf(ACCENT));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF5F6368));
        s.setThumbTintList(ColorStateList.valueOf(ACCENT));
    }

    private static GradientDrawable pill(int fill, int stroke, float dp) { return pill(fill, stroke, dp, 8); }

    private static GradientDrawable pill(int fill, int stroke, float dp, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius * dp);
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
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12 * sp * scale);
            t.setAllCaps(true);
            t.setLetterSpacing(0.08f);
            t.setTextColor(HEAD);
            t.setPadding(0, Math.round(20 * dp), 0, Math.round(2 * dp));
        } else if (!bold && t.getCurrentTextColor() == Color.LTGRAY && size <= 13 * sp) {
            float scale = size / (12 * sp);
            t.setTag(DONE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12 * sp * scale);
            t.setTextColor(0xFF8A8A8A);
            t.setLineSpacing(0, 1.15f);
        }
    }

    /**
     * A slider's label just above it, "Size 100 % (full)": its name at the left and its value at the right, in one row put where the label was. The label stays
     * (hidden) and its text is still set by upstream's code; each change is split into the row again
     */
    private static void valued(ViewGroup g, int i, float dp) {
        View v = g.getChildAt(i), next = g.getChildAt(i + 1);
        if (!(next instanceof android.widget.SeekBar) || !(v instanceof TextView) || v instanceof CompoundButton || v instanceof Button || v.getVisibility() != View.VISIBLE) return;
        TextView label = (TextView) v;
        java.util.regex.Matcher m = VALUED.matcher(String.valueOf(label.getText()));
        if (!m.matches() || !(g instanceof LinearLayout) || ((LinearLayout) g).getOrientation() != LinearLayout.VERTICAL) return;
        LinearLayout row = new LinearLayout(g.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Math.round(6 * dp), 0, 0);
        TextView name = new TextView(g.getContext()), value = new TextView(g.getContext());
        for (TextView t : new TextView[] {name, value}) { t.setTextSize(TypedValue.COMPLEX_UNIT_PX, label.getTextSize()); t.setTextColor(0xFFE8EAED); t.setSingleLine(true); }
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable split = () -> {
            java.util.regex.Matcher x = VALUED.matcher(String.valueOf(label.getText()));
            if (x.matches()) { name.setText(x.group(1)); value.setText(x.group(2)); } else { name.setText(label.getText()); value.setText(""); }
        };
        split.run();
        label.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { split.run(); }
        });
        label.setVisibility(View.GONE);
        g.addView(row, i);
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
        // A button of the panel's own (All off, Restart): a rounded chip in the rows' grey with its text in the panel's white, not the theme's raised grey block
        b.setTag(DONE);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setBackground(pill(0xFF3A3B3E, 0, dp, 999));
        b.setTextColor(0xFFE8EAED);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinHeight(Math.round(38 * dp));
        b.setMinimumHeight(Math.round(38 * dp));
        b.setPadding(Math.round(18 * dp), 0, Math.round(18 * dp), 0);
    }
}
