package dev.peanuts.amiibo;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import dev.peanuts.ingame.Look;

/**
 * The card over the game while it looks for an amiibo, at the bottom of the screen the game runs on, in the in-game panel's
 * look: one of the player's own amiibo that fits (or was the last placed in this game) with A Place and B Not now; several as a short row, the last placed
 * first, picked with left and right; none of the player's own: Choose an amiibo, which opens the quick menu's amiibo tab. It slides in and out calmly;
 * the keys come from AmiiboWatch while it shows. A tap works too: a tile picks, the buttons do what their keys do.
 */
final class AmiiboCard extends FrameLayout {
    interface Act { void place(AmiiboStore.Own o); void choose(); void notNow(); }

    private final Activity a;
    private final Act act;
    private final List<AmiiboStore.Own> own;   // empty: Choose an amiibo
    private final AmiiboDb db;
    private final String titleId;
    private final int fits;
    private final boolean keys;
    private final LinearLayout card = new LinearLayout(getContext());
    private final java.util.ArrayList<View> tiles = new java.util.ArrayList<>();
    private TextView name, sub;
    private int at;
    private boolean leaving;
    final java.util.Map<String, Bitmap> pictures = new java.util.HashMap<>();

    AmiiboCard(Activity a, List<AmiiboStore.Own> own, AmiiboDb db, String titleId, int fits, boolean keys, Act act) {
        super(a);
        this.a = a; this.own = own; this.db = db; this.titleId = titleId; this.fits = fits; this.keys = keys; this.act = act;
        setClipChildren(false);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Look.round(Look.SHEET, Look.dp(a, 20)));
        card.setElevation(Look.dp(a, 12));
        int p = Look.dp(a, 14);
        card.setPadding(p, p, p, p);
        card.setClickable(true);   // a touch on the card is the card's, not the game's
        card.setFocusable(false);  // the keys come from AmiiboWatch: nothing here takes the focus (a clickable view is focusable by default, and the
                                   // first D-pad press would go to leaving touch mode and grey it)
        build();
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Look.dp(a, 22);
        addView(card, lp);
    }

    private void build() {
        int n = Math.min(own.size(), 5);
        int w = Look.dp(a, own.size() > 1 ? Math.max(340, 28 + n * 66 + (n - 1) * 6 + (own.size() > n ? 30 : 0)) : 372);
        card.setMinimumWidth(w);
        if (own.size() > 1) { row(w); return; }
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout tile = tile(own.isEmpty() ? null : own.get(0), 104, false);
        line.addView(tile);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Look.dp(a, w / a.getResources().getDisplayMetrics().density - 104 - 28 - 14), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMarginStart(Look.dp(a, 14));
        line.addView(col, cl);
        col.addView(eyebrow(null));
        name = text(own.isEmpty() ? "Choose an amiibo" : db.get(own.get(0).id).title(), 20, Look.TEXT, true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Look.dp(a, 4);
        col.addView(name, nl);
        sub = text(own.isEmpty() ? (fits > 0 ? fits + " fit this game" : "Any of the app's list") : why(own.get(0)), 13, Look.QUIET, false);
        col.addView(sub);
        col.addView(buttons(own.isEmpty() ? "Choose" : "Place"));
        card.addView(line);
    }

    private void row(int w) {
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(eyebrow(null), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(own.size() + " of yours fit", 12, Look.QUIET, false));
        card.addView(head);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int n = Math.min(own.size(), 5);
        for (int i = 0; i < n; i++) {
            FrameLayout t = tile(own.get(i), 62, true);
            int k = i;
            t.setOnClickListener(v -> { if (k == at) act.place(own.get(at)); else select(k); });
            t.setFocusable(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Look.dp(a, 66), Look.dp(a, 66));
            if (i > 0) lp.setMarginStart(Look.dp(a, 6));
            row.addView(t, lp);
            tiles.add(t);
        }
        if (own.size() > n) {
            TextView more = text("+" + (own.size() - n), 13, Look.QUIET, false);
            more.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Look.dp(a, 30), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(more, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = Look.dp(a, 10);
        card.addView(row, rl);
        name = text("", 18, Look.TEXT, true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(Look.dp(a, w / a.getResources().getDisplayMetrics().density - 28), ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = Look.dp(a, 8);
        card.addView(name, nl);
        sub = text("", 13, Look.QUIET, false);
        card.addView(sub);
        card.addView(buttons("Place"));
        select(0);
    }

    private void select(int k) {
        at = k;
        for (int i = 0; i < tiles.size(); i++) {
            View t = tiles.get(i);
            boolean on = i == k;
            t.setForeground(on ? Look.focus(a, 14) : null);
            t.animate().alpha(on ? 1f : 0.62f).scaleX(on ? 1f : 0.9f).scaleY(on ? 1f : 0.9f).setDuration(140).setInterpolator(Look.OUT).start();
        }
        AmiiboStore.Own o = own.get(k);
        name.setText(db.get(o.id).title());
        sub.setText(why(o));
    }

    private String why(AmiiboStore.Own o) {
        long[] p = AmiiboStore.placedIn(o, titleId);
        return p[0] > 0 ? "Last placed in this game" : "Fits this game";
    }

    private View eyebrow(String s) {
        LinearLayout l = new LinearLayout(a);
        l.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(a);
        Drawable d = Look.icon(a, "amiibo", Look.Ink.MENU);
        if (d != null) ic.setImageDrawable(d);
        l.addView(ic, new LinearLayout.LayoutParams(Look.dp(a, 16), Look.dp(a, 16)));
        TextView t = text(s != null ? s : "Looking for an amiibo", 11.5f, Look.TAN, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Look.dp(a, 6));
        l.addView(t, lp);
        return l;
    }

    private LinearLayout buttons(String yes) {
        LinearLayout b = new LinearLayout(a);
        b.setGravity(Gravity.CENTER_VERTICAL);
        View ok = button("A", yes, true, v -> { if (own.isEmpty()) act.choose(); else act.place(own.get(at)); });
        b.addView(ok);
        View no = button("B", "Not now", false, v -> act.notNow());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Look.dp(a, 8));
        b.addView(no, lp);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Look.dp(a, 12);
        b.setLayoutParams(bl);
        return b;
    }

    /** A pill: the controller's key in a circle (only while a controller is attached) and the word */
    private View button(String key, String word, boolean yellow, View.OnClickListener click) {
        LinearLayout p = new LinearLayout(a);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Look.round(yellow ? Look.YELLOW : Look.ROW, Look.dp(a, 19)));
        p.setMinimumHeight(Look.dp(a, 38));
        p.setPadding(Look.dp(a, keys ? 6 : 16), 0, Look.dp(a, 16), 0);
        if (keys) {
            TextView k = text(key, 13, yellow ? Look.YELLOW : Look.TEXT, true);
            k.setGravity(Gravity.CENTER);
            k.setBackground(Look.round(yellow ? Look.ON_YELLOW : 0xFF46474B, Look.dp(a, 13)));
            p.addView(k, new LinearLayout.LayoutParams(Look.dp(a, 26), Look.dp(a, 26)));
        }
        TextView t = text(word, 14.5f, yellow ? Look.ON_YELLOW : Look.TEXT, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (keys) lp.setMarginStart(Look.dp(a, 8));
        p.addView(t, lp);
        p.setOnClickListener(click);
        p.setFocusable(false);
        p.setContentDescription(word);
        return p;
    }

    /** A square tile with the amiibo's picture (Peanuts' own card until it comes, or for none) */
    private FrameLayout tile(AmiiboStore.Own o, int sizeDp, boolean small) {
        FrameLayout f = new FrameLayout(a);
        f.setBackground(Look.round(Look.ROW, Look.dp(a, small ? 12 : 14)));
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline ol) { ol.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Look.dp(a, small ? 12 : 14)); }
        });
        f.setClipToOutline(true);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Look.dp(a, small ? 5 : 8);
        img.setPadding(pad, pad, pad, pad);
        Drawable stand = Look.icon(a, "amiibo", Look.Ink.REST);
        if (stand != null) img.setImageDrawable(new android.graphics.drawable.InsetDrawable(stand, Look.dp(a, small ? 10 : 18)));
        f.addView(img, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!small) f.setLayoutParams(new LinearLayout.LayoutParams(Look.dp(a, sizeDp), Look.dp(a, sizeDp)));
        if (o != null) {
            String id = o.id;
            img.setTag(id);
            AmiiboPictures.load(a, id, Look.dp(a, 200), b -> {
                if (b == null || !id.equals(img.getTag())) return;
                pictures.put(id, b);
                img.setImageBitmap(b);
            });
        }
        return f;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? 600 : 400, false));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ---- shown, keys, gone ----

    void show() {
        card.setAlpha(0f);
        card.setTranslationY(Look.dp(a, 40));
        card.animate().alpha(1f).translationY(0f).setDuration(Look.OPEN_MS).setInterpolator(Look.OUT).start();
    }

    void hide(Runnable after) {
        if (leaving) return;
        leaving = true;
        card.animate().alpha(0f).translationY(Look.dp(a, 40)).setDuration(Look.CLOSE_MS).setInterpolator(Look.IN).withEndAction(() -> {
            if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
            if (after != null) after.run();
        }).start();
    }

    boolean leaving() { return leaving; }

    /** A key while it shows: A, B, left, right. True when taken */
    boolean key(int what) {
        switch (what) {
            case AmiiboWatch.K_A: if (own.isEmpty()) act.choose(); else act.place(own.get(at)); return true;
            case AmiiboWatch.K_B: act.notNow(); return true;
            case AmiiboWatch.K_LEFT: if (tiles.size() > 1 && at > 0) { select(at - 1); dev.peanuts.ui.MenuFeel.move(this); } return true;
            case AmiiboWatch.K_RIGHT: if (tiles.size() > 1 && at < tiles.size() - 1) { select(at + 1); dev.peanuts.ui.MenuFeel.move(this); } return true;
            default: return false;
        }
    }

    AmiiboStore.Own current() { return own.isEmpty() ? null : own.get(at); }
}
