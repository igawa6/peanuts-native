package dev.peanuts.ui;

import android.content.Context;
import android.graphics.Insets;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.nxnative.android.R;

import java.util.function.Consumer;

/**
 * What floats over Home's page, in its frame (MainActivity's page: the bananas, the scrolling column, the status bar's scrim): on a phone held upright the
 * dock at the bottom with Find, Sort, View and Build all, under the thumb; it slides away while the page scrolls down and comes back when it scrolls up (a
 * fade under reduced motion). Find turns it into a search field just above the keyboard that filters as one types; the x empties it, Back or Done closes
 * it. Beside it: the view's name for a moment after the View button (under the button, or above the dock), and the slim bar that names the chosen game in
 * Covers on one screen with a controller (the covers carry no names there). And the tabs pinned under the status bar once the page's own went up.
 */
final class HomeDock extends FrameLayout {
    private static final String TAG = "peanuts.home.dock";
    private final LinearLayout bar, field;
    private final EditText edit;
    private ScrollView watched;
    private boolean away, finding;
    private int bottomInset, imeInset, topInset;
    private View pinSource;      // the page's own tabs
    private FrameLayout pinned;  // their copy, pinned at the top while the page's are scrolled away
    private final int[] at = new int[2];
    private Consumer<String> typed;
    private Runnable closed;

    /** The frame's dock, made the first time (a page built again has a new frame, and a new dock) */
    static HomeDock in(FrameLayout frame) {
        View v = frame.findViewWithTag(TAG);
        if (v instanceof HomeDock) return (HomeDock) v;
        HomeDock d = new HomeDock(frame.getContext());
        d.setTag(TAG);
        frame.addView(d, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return d;
    }

    /** The frame's dock if it has one */
    static HomeDock of(FrameLayout frame) {
        View v = frame == null ? null : frame.findViewWithTag(TAG);
        return v instanceof HomeDock ? (HomeDock) v : null;
    }

    private HomeDock(Context c) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        int p16 = PUi.dp(c, 16);
        bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(PUi.dp(c, 18), PUi.dp(c, 8), PUi.dp(c, 18), PUi.dp(c, 6));
        bar.setBackground(PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 26), PUi.col(c, R.color.bn_card_stroke), Math.max(1, PUi.dp(c, 1))));
        bar.setElevation(PUi.dp(c, 6));
        addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        field = new LinearLayout(c);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(PUi.dp(c, 18), PUi.dp(c, 4), PUi.dp(c, 6), PUi.dp(c, 4));
        field.setBackground(PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 28), PUi.col(c, R.color.bn_banana), PUi.dp(c, 2)));
        field.setElevation(PUi.dp(c, 6));
        ImageView glass = new ImageView(c);
        glass.setImageDrawable(PUi.icon(c, R.drawable.ic_search, R.color.bn_accent));
        glass.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(glass, new LinearLayout.LayoutParams(PUi.dp(c, 24), PUi.dp(c, 24)));
        edit = new EditText(c);
        edit.setHint("A game's name");
        edit.setSingleLine(true);
        edit.setBackground(null);
        edit.setTextSize(17);
        edit.setTextColor(PUi.col(c, R.color.bn_text));
        edit.setHintTextColor(PUi.col(c, R.color.bn_text_2));
        edit.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        edit.setPadding(PUi.dp(c, 12), 0, PUi.dp(c, 8), 0);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, PUi.dp(c, 52), 1);
        field.addView(edit, ep);
        ImageView x = new ImageView(c);   // the x: empties the field, and closes it when it is empty
        x.setImageDrawable(CoverMarks.icon(c, "close", PUi.col(c, R.color.bn_text)));
        x.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        x.setPadding(PUi.dp(c, 11), PUi.dp(c, 11), PUi.dp(c, 11), PUi.dp(c, 11));
        x.setContentDescription("Clear");
        x.setClickable(true);
        x.setFocusable(true);
        x.setBackground(PUi.ripple(c, PUi.round(PUi.col(c, R.color.bn_tint), PUi.dp(c, 999), 0, 0)));
        x.setOnClickListener(Haptics.on(Haptics.Word.CLICK, v -> { if (edit.getText().length() > 0) edit.setText(""); else close(); }));
        field.addView(x, new LinearLayout.LayoutParams(PUi.dp(c, 44), PUi.dp(c, 44)));
        field.setVisibility(GONE);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        fp.leftMargin = fp.rightMargin = p16;
        addView(field, fp);

        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable s) { if (finding && typed != null) typed.accept(s.toString().trim()); }
        });
        edit.setOnEditorActionListener((v, id, ev) -> { hideKeyboard(); return true; });
        edit.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP && finding) { close(); return true; }
            return false;
        });
        // above the navigation bar, and above the keyboard while it is up
        setOnApplyWindowInsetsListener((v, in) -> {
            Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()), ime = in.getInsets(WindowInsets.Type.ime());
            bottomInset = bars.bottom;
            if (topInset != bars.top) { topInset = bars.top; if (pinned != null) ((FrameLayout.LayoutParams) pinned.getLayoutParams()).topMargin = topInset; }
            imeInset = ime.bottom;
            setPadding(0, 0, 0, Math.max(bottomInset, imeInset) + PUi.dp(getContext(), 14));
            if (imeInset == 0 && finding && edit.getText().length() == 0 && !edit.hasFocus()) close();
            return in;
        });
        requestApplyInsets();
    }

    /** The dock's buttons (Library makes them: the same actions as the header's) */
    void buttons(View... tools) {
        bar.removeAllViews();
        for (View t : tools) {
            if (t == null) continue;
            if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(PUi.dp(getContext(), 64), ViewGroup.LayoutParams.WRAP_CONTENT);
            bar.addView(t, lp);
        }
    }

    /** Shows the dock (a phone upright, a library with games) or takes it away */
    void shown(boolean on) {
        setVisibility(on ? VISIBLE : GONE);
        if (!on && finding) close();
        if (!on) pinTabs(null, null);
    }

    /** How much of the page's end the dock covers: the page leaves that much room under its last row */
    int room() { return PUi.dp(getContext(), 96) + bottomInset; }

    // ---- Away while the page scrolls down ----

    /** Follows the page's scrolling: down takes the dock away, up brings it back, the top of the page always has it */
    void follow(ScrollView page) {
        if (page == null || page == watched) return;
        watched = page;
        page.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            pin();
            if (finding) return;
            if (y <= 0 || y < oy - PUi.dp(getContext(), 2)) away(false);
            else if (y > oy + PUi.dp(getContext(), 2)) away(true);
        });
    }

    private void away(boolean go) {
        if (go == away) return;
        away = go;
        bar.animate().cancel();
        if (!Motion.on()) {   // reduced motion: it fades
            bar.setTranslationY(0);
            bar.animate().alpha(go ? 0f : 1f).setDuration(go ? Motion.EXIT : Motion.ENTER).setInterpolator(Motion.OUT)
                    .withEndAction(() -> bar.setVisibility(away ? INVISIBLE : VISIBLE));
            if (!go) bar.setVisibility(VISIBLE);
            return;
        }
        bar.setAlpha(1f);
        bar.setVisibility(VISIBLE);
        float out = bar.getHeight() + getPaddingBottom() + PUi.dp(getContext(), 8);
        bar.animate().translationY(go ? out : 0).setDuration(go ? 200 : 240).setInterpolator(go ? Motion.IN : Motion.OUT)
                .withEndAction(() -> bar.setVisibility(away ? INVISIBLE : VISIBLE));
    }

    // ---- The tabs stay at the top ----

    /**
     * Many games on a phone upright: while the page scrolls the title goes, the tabs stay at the top. inPage: the page's own tabs (null: none, the copy goes);
     * copy: the same tabs, pinned under the status bar while inPage is scrolled up under it
     */
    void pinTabs(View inPage, View copy) {
        FrameLayout frame = getParent() instanceof FrameLayout ? (FrameLayout) getParent() : null;
        if (pinned != null && pinned.getParent() instanceof ViewGroup) ((ViewGroup) pinned.getParent()).removeView(pinned);
        pinned = null;
        pinSource = inPage;
        if (frame == null || inPage == null || copy == null) return;
        Context c = getContext();
        pinned = new FrameLayout(c);
        pinned.setBackgroundColor((PUi.col(c, R.color.bn_bg) & 0x00FFFFFF) | 0xF2000000);
        int side = watched != null ? watched.getPaddingLeft() : PUi.dp(c, 20);
        pinned.setPadding(side - PUi.dp(c, 4), 0, side, PUi.dp(c, 2));
        pinned.setElevation(PUi.dp(c, 3));
        pinned.addView(copy, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.topMargin = topInset;
        pinned.setVisibility(INVISIBLE);
        frame.addView(pinned, lp);
        post(this::pin);
    }

    /** The copy shows once the page's own tabs went up under the status bar */
    private void pin() {
        if (pinned == null || pinSource == null || !pinSource.isAttachedToWindow()) { if (pinned != null) pinned.setVisibility(INVISIBLE); return; }
        pinSource.getLocationInWindow(at);
        boolean show = getVisibility() == VISIBLE && at[1] < topInset;
        int want = show ? VISIBLE : INVISIBLE;
        if (pinned.getVisibility() == want) return;
        pinned.setVisibility(want);
        if (show && Motion.on()) { pinned.setAlpha(0f); pinned.animate().alpha(1f).setDuration(Motion.ENTER).setInterpolator(Motion.OUT); }
    }

    // ---- Find ----

    /** The dock turns into the search field with text in it, the keyboard up; typed hears every change, closed the field's end */
    void find(String text, Consumer<String> typed, Runnable closed) {
        this.typed = null;
        edit.setText(text);
        edit.setSelection(edit.getText().length());
        this.typed = typed;
        this.closed = closed;
        finding = true;
        away(false);
        bar.setVisibility(GONE);
        field.setVisibility(VISIBLE);
        if (Motion.on()) { field.setAlpha(0f); field.setScaleX(0.92f); field.animate().alpha(1f).scaleX(1f).setDuration(Motion.ENTER).setInterpolator(Motion.OUT); }
        edit.requestFocus();
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) edit.post(() -> m.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT));
    }

    boolean finding() { return finding; }

    /** The field goes, the dock comes back (the text stays Home's Find: the Find button says it) */
    void close() {
        if (!finding) return;
        finding = false;
        hideKeyboard();
        edit.clearFocus();
        field.setVisibility(GONE);
        bar.setVisibility(VISIBLE);
        bar.setAlpha(1f);
        bar.setTranslationY(0);
        away = false;
        Runnable r = closed;
        closed = null; typed = null;
        if (r != null) r.run();
    }

    private void hideKeyboard() {
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.hideSoftInputFromWindow(edit.getWindowToken(), 0);
    }

    // ---- The view's name, for a moment ----

    /** The view's name fades in under anchor (the View button), or above the dock when it is the dock's, and out again after a little more than a second */
    static void toast(FrameLayout frame, View anchor, CharSequence text) {
        if (frame == null || anchor == null || !anchor.isAttachedToWindow()) return;
        Context c = frame.getContext();
        View old = frame.findViewWithTag(TAG + ".toast");
        if (old != null) frame.removeView(old);
        TextView t = PUi.text(c, text, 15, R.color.bn_on_banana, 700);
        t.setTag(TAG + ".toast");
        t.setBackground(PUi.round(PUi.col(c, R.color.bn_banana), PUi.dp(c, 999), 0, 0));
        t.setPadding(PUi.dp(c, 16), PUi.dp(c, 8), PUi.dp(c, 16), PUi.dp(c, 8));
        t.setElevation(PUi.dp(c, 4));
        t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        frame.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        int[] fa = new int[2], aa = new int[2];
        t.setVisibility(INVISIBLE);
        t.post(() -> {
            if (!anchor.isAttachedToWindow()) { frame.removeView(t); return; }
            frame.getLocationInWindow(fa);
            anchor.getLocationInWindow(aa);
            boolean inDock = anchor.getParent() != null && anchor.getParent().getParent() instanceof HomeDock || isInDock(anchor);
            float x = aa[0] - fa[0] + anchor.getWidth() / 2f - t.getWidth() / 2f;
            x = Math.max(PUi.dp(c, 8), Math.min(frame.getWidth() - t.getWidth() - PUi.dp(c, 8), x));
            float y = inDock ? aa[1] - fa[1] - t.getHeight() - PUi.dp(c, 18) : aa[1] - fa[1] + anchor.getHeight() + PUi.dp(c, 6);
            t.setX(x); t.setY(y);
            t.setVisibility(VISIBLE);
            t.setAlpha(0f);
            t.animate().alpha(1f).setDuration(Motion.ENTER).setInterpolator(Motion.OUT).withEndAction(() ->
                    t.animate().alpha(0f).setStartDelay(1100).setDuration(260).setInterpolator(Motion.IN).withEndAction(() -> frame.removeView(t)));
        });
        anchor.announceForAccessibility(text);
    }

    private static boolean isInDock(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p instanceof HomeDock) return true;
        return false;
    }

    // ---- Covers on one screen: the chosen game's name ----

    /**
     * The slim bar that names the game a controller is on in Covers (its covers carry no names) when there is no bottom screen to say it: at the bottom of
     * the page, above the dock on a phone. null name: the bar goes
     */
    static void nameBar(FrameLayout frame, CharSequence name, CharSequence state, CharSequence keys) {
        if (frame == null) return;
        View old = frame.findViewWithTag(TAG + ".name");
        if (name == null) { if (old != null) frame.removeView(old); return; }
        Context c = frame.getContext();
        LinearLayout b;
        if (old instanceof LinearLayout) b = (LinearLayout) old;
        else {
            b = new LinearLayout(c);
            b.setTag(TAG + ".name");
            b.setOrientation(LinearLayout.HORIZONTAL);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(PUi.dp(c, 20), PUi.dp(c, 10), PUi.dp(c, 20), PUi.dp(c, 10));
            b.setBackground(PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 22), PUi.col(c, R.color.bn_card_stroke), Math.max(1, PUi.dp(c, 1))));
            b.setElevation(PUi.dp(c, 5));
            b.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            lp.leftMargin = lp.rightMargin = PUi.dp(c, 20);
            b.setOnApplyWindowInsetsListener((v, in) -> {
                HomeDock d = of(frame);
                int dockRoom = d != null && d.getVisibility() == VISIBLE ? PUi.dp(c, 88) : 0;
                ((FrameLayout.LayoutParams) v.getLayoutParams()).bottomMargin = in.getInsets(WindowInsets.Type.systemBars()).bottom + PUi.dp(c, 14) + dockRoom;
                v.requestLayout();
                return in;
            });
            frame.addView(b, lp);
            b.requestApplyInsets();
            Motion.fadeIn(b);
        }
        b.removeAllViews();
        // a narrow screen (a phone upright): the name on a line of its own, the state and the keys under it; else all on one line
        boolean narrow = c.getResources().getConfiguration().screenWidthDp < 600;
        b.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        b.setGravity(narrow ? Gravity.START : Gravity.CENTER_VERTICAL);
        TextView n = PUi.text(c, name, 17, R.color.bn_text, 700);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        b.addView(n, narrow ? PUi.fill() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout rest = b;
        if (narrow) {
            rest = PUi.row(c);
            rest.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rl = PUi.fill();
            rl.topMargin = PUi.dp(c, 2);
            b.addView(rest, rl);
        }
        if (state != null) {
            TextView s = PUi.text(c, state, 14, R.color.bn_text_2, 600);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sl = narrow ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1) : PUi.wrap();
            if (!narrow) sl.leftMargin = PUi.dp(c, 12);
            rest.addView(s, sl);
        }
        if (keys != null) {
            TextView k = PUi.text(c, keys, 14, R.color.bn_accent, 700);
            k.setSingleLine(true);
            LinearLayout.LayoutParams kl = PUi.wrap(); kl.leftMargin = PUi.dp(c, 16);
            rest.addView(k, kl);
        }
    }
}
