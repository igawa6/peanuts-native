package dev.peanuts.amiibo;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.peanuts.PeanutsPrefs;
import dev.peanuts.ingame.Look;
import dev.peanuts.mods.ModsTab;

/**
 * The quick menu's amiibo tab (dev.nxnative.android.ModsPanel puts it between Mods and Controls for a game that uses amiibo):
 * what the stand has (the game looking, an amiibo ready or on it), a search over every amiibo, the player's own that fit the game and every one that fits it,
 * and "When a game looks for an amiibo". A pick while the game looks places it at once with the show; otherwise it is ready for the game's next search.
 * X Take off in the header clears the stand. The rail lights this tab while the game looks (AmiiboWatch, InGamePanel).
 */
public final class AmiiboTab {
    private AmiiboTab() {}

    public static final String NAME = "amiibo";
    private static String forced;
    private static String query = "";
    private static Runnable takeOff;   // the header's X Take off while this tab shows it; ModsPanel.padKey runs it for the controller's X
    private static java.lang.ref.WeakReference<View> takeOffOn = new java.lang.ref.WeakReference<>(null);

    /** Whether the game gets the tab: AmiiboAPI lists it, it was seen looking for one, or its nfp is up now */
    public static boolean wanted(Activity a, String titleId) { return AmiiboSwitch.on(a, titleId) && (AmiiboStand.usesAmiibo(a, titleId) || AmiiboWatch.status().lib); }

    /** The card's Choose: the quick menu's next opening is on this tab */
    public static void openOnNext() { forced = NAME; }

    /** ModsPanel.opening: the tab to open on (the one asked for by the card, else want) */
    public static String openOn(String want) { String f = forced; forced = null; return f != null ? f : want; }

    /** ModsPanel.padKey: the controller's X (the Switch's X by the game's A / B, as the Cheats tab's All off) takes the amiibo off while this tab shows */
    public static boolean padKey(int k, int confirm) {
        Runnable r = takeOff;
        View on = takeOffOn.get();
        if (r == null || on == null || !on.isShown()) return false;
        if (k != (confirm == KeyEvent.KEYCODE_BUTTON_B ? KeyEvent.KEYCODE_BUTTON_Y : KeyEvent.KEYCODE_BUTTON_X)) return false;
        r.run();
        return true;
    }

    public static void fill(Activity a, LinearLayout box, ModsTab.Host host) {
        String titleId = AmiiboWatch.titleId() != null ? AmiiboWatch.titleId() : a.getIntent().getStringExtra("title_id");
        LinearLayout status = new LinearLayout(a);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        int p = Look.dp(a, 12);
        status.setPadding(p, Look.dp(a, 10), p, Look.dp(a, 10));
        box.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(a);
        search.setSingleLine(true);
        search.setHint("Search all amiibo");
        search.setText(query);
        search.setTextSize(15);
        search.setTextColor(Look.TEXT);
        search.setHintTextColor(0xFF8E9196);
        search.setBackground(Look.round(Look.ROW, Look.dp(a, 12)));
        search.setPadding(Look.dp(a, 12), Look.dp(a, 9), Look.dp(a, 12), Look.dp(a, 9));
        Drawable mag = dev.peanuts.ui.Icons.get(a, "search", dev.peanuts.ui.Icons.Ink.QUIET);
        if (mag != null) { mag.setBounds(0, 0, Look.dp(a, 18), Look.dp(a, 18)); search.setCompoundDrawablesRelative(mag, null, null, null); search.setCompoundDrawablePadding(Look.dp(a, 8)); }
        search.setDefaultFocusHighlightEnabled(false);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);   // as the Cheats tab's search: the keyboard beside the list
        search.setOnEditorActionListener((v, id, ev) -> {   // the keyboard's search key: the keyboard goes, the amiibo show
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
            InputMethodManager im = a.getSystemService(InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Look.dp(a, 10);
        box.addView(search, sl);
        // A controller's first focus on the tab (the first focusable, the search up here) goes on to the first amiibo, as on the Cheats tab: focused, the search
        // opened the keyboard over the tab. D-pad up still reaches it
        boolean[] passed = {false};
        search.setOnFocusChangeListener((v, has) -> {
            if (!has || v.isInTouchMode() || passed[0]) return;
            passed[0] = true;
            for (View f : box.getFocusables(View.FOCUS_DOWN)) if (f != search) { f.requestFocus(); return; }
        });

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(head(a, "When a game looks for an amiibo"));
        RadioGroup when = new RadioGroup(a);
        String[] words = {"Suggest one of mine", "Only light up this tab", "Place my last one at once"};
        int now = PeanutsPrefs.amiiboWhen(a);
        for (int i = 0; i < words.length; i++) {
            RadioButton r = new RadioButton(a);
            r.setId(View.generateViewId());
            r.setText(words[i]);
            r.setTextColor(Look.TEXT);
            r.setTextSize(15);
            int k = i;
            r.setOnClickListener(v -> PeanutsPrefs.amiiboWhen(a, k));
            when.addView(r);
            if (i == now) when.check(r.getId());
        }
        box.addView(when);
        TextView note = note(a, "Suggest shows a small card over the game after it has looked for a moment. The game keeps looking until you place one or it stops.");
        box.addView(note);
        host.style(when);
        host.style(note);

        Runnable fillStatus = () -> status(a, status, host);
        Runnable fillList = () -> list(a, list, titleId, host);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { String q = s.toString().trim(); if (!q.equals(query)) { query = q; fillList.run(); } }
        });
        Runnable follow = () -> { if (status.isAttachedToWindow()) fillStatus.run(); };
        AmiiboWatch.listen(follow);
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { AmiiboWatch.unlisten(follow); }
        });
        fillStatus.run();
        fillList.run();
    }

    // ---- what the stand has ----

    private static void status(Activity a, LinearLayout s, ModsTab.Host host) {
        s.removeAllViews();
        AmiiboStand.Status st = AmiiboWatch.status();
        AmiiboDb db = AmiiboStand.db(a);
        String key = st.onStand() ? st.on : st.ready;
        AmiiboStore.Own o = key.isEmpty() ? null : AmiiboStore.find(a.getFilesDir(), key);
        String title, line;
        boolean lit = st.searching();
        if (lit) { title = "The game is looking for an amiibo"; line = "Pick one to place it"; }
        else if (o != null && st.onStand()) { title = db.get(o.id).title() + " is on the stand"; line = "The game is reading it"; }
        else if (o != null) { title = db.get(o.id).title() + " is ready"; line = "The game gets it the next time it looks"; }
        else if (!st.lib) { title = "The game has not asked for an amiibo yet"; line = "Pick one to have it ready"; }
        else { title = "Pick one to have it ready"; line = "The game gets it the next time it looks"; }
        s.setBackground(Look.round(lit ? 0xFF2E2717 : Look.ROW, Look.dp(a, 14)));
        View pic;
        if (o != null && !lit) pic = tile(a, o.id, 40, false);
        else {
            ImageView ic = new ImageView(a);
            Drawable d = Look.icon(a, "amiibo", lit ? Look.Ink.SELECTED : Look.Ink.REST);
            if (d != null) ic.setImageDrawable(d);
            pic = ic;
        }
        s.addView(pic, new LinearLayout.LayoutParams(Look.dp(a, o != null && !lit ? 40 : 30), Look.dp(a, o != null && !lit ? 40 : 30)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cl.setMarginStart(Look.dp(a, 10));
        s.addView(col, cl);
        TextView t = text(a, title, 14.5f, Look.TEXT, true);
        t.setSingleLine(false);
        t.setMaxLines(2);
        col.addView(t);
        col.addView(text(a, line, 12.5f, Look.QUIET, false));
        boolean any = st.onStand() || !st.ready.isEmpty();
        Runnable run = any ? () -> { AmiiboWatch.takeOff(); s.postDelayed(() -> status(a, s, host), 200); } : null;
        host.action("X", any ? "Take off" : null, run);
        takeOff = run;
        takeOffOn = new java.lang.ref.WeakReference<>(s);
    }

    // ---- the amiibo ----

    private static void list(Activity a, LinearLayout list, String titleId, ModsTab.Host host) {
        list.removeAllViews();
        AmiiboDb db = AmiiboStand.db(a);
        List<AmiiboDb.Amiibo> fit = db.fitting(titleId);
        Set<String> fits = AmiiboPage.ids(fit);
        List<AmiiboStore.Own> all = AmiiboStore.list(a.getFilesDir());
        if (!query.isEmpty()) {
            String q = query.toLowerCase(Locale.ROOT);
            List<Object> found = new ArrayList<>();
            for (AmiiboStore.Own o : all) if (AmiiboPage.matches(db, db.get(o.id), q)) found.add(o);
            for (AmiiboDb.Amiibo x : db.all()) if (AmiiboPage.matches(db, x, q)) found.add(x);
            list.addView(head(a, found.isEmpty() ? "No amiibo found" : found.size() == 1 ? "1 amiibo" : found.size() + " amiibo"));
            grid(a, list, found.subList(0, Math.min(found.size(), 48)), db);
            return;
        }
        List<AmiiboStore.Own> mine = AmiiboStore.forGame(all, titleId, fits);
        list.addView(head(a, "Yours"));
        if (mine.isEmpty()) list.addView(note(a, all.isEmpty() ? "None yet. Pick one below: it becomes yours, with its data for each game." : "None of yours fits this game. Pick one below or search."));
        else grid(a, list, new ArrayList<>(mine), db);
        Set<String> own = new HashSet<>();
        for (AmiiboStore.Own o : mine) own.add(o.id);
        List<Object> rest = new ArrayList<>();
        for (AmiiboDb.Amiibo x : fit) if (!own.contains(x.id)) rest.add(x);
        if (!rest.isEmpty()) { list.addView(head(a, "Fit this game")); grid(a, list, rest, db); }
        else if (fit.isEmpty()) {   // a game AmiiboAPI does not list: every amiibo, the search narrows it
            list.addView(head(a, "All amiibo"));
            List<Object> l = new ArrayList<>();
            for (AmiiboDb.Amiibo x : db.all()) { l.add(x); if (l.size() == 24) break; }
            grid(a, list, l, db);
            list.addView(note(a, "Search for the one you want."));
        }
        host.style(list);
    }

    private static void grid(Activity a, LinearLayout into, List<Object> items, AmiiboDb db) {
        int cols = 4;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(a);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.topMargin = Look.dp(a, i == 0 ? 4 : 10);
                into.addView(row, rl);
            }
            Object it = items.get(i);
            AmiiboStore.Own o = it instanceof AmiiboStore.Own ? (AmiiboStore.Own) it : null;
            AmiiboDb.Amiibo x = o != null ? db.get(o.id) : (AmiiboDb.Amiibo) it;
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout t = tile(a, x.id, 0, true);
            t.setFocusable(true);
            t.setClickable(true);
            t.setContentDescription(x.title() + (o != null ? ", yours" : ""));
            t.setOnClickListener(v -> pick(a, o, x));
            cell.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView n = text(a, x.title(), 11.5f, Look.QUIET, false);
            n.setGravity(Gravity.CENTER);
            n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = Look.dp(a, 4);
            cell.addView(n, nl);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % cols != 0) lp.setMarginStart(Look.dp(a, 8));
            row.addView(cell, lp);
        }
        if (row != null) for (int k = items.size() % cols; k != 0 && k < cols; k++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.setMarginStart(Look.dp(a, 8));
            row.addView(new View(a), lp);
        }
    }

    /** A pick: placed at once with the show while the game looks, else ready for its next search. One of the list becomes the player's own copy */
    private static void pick(Activity a, AmiiboStore.Own o, AmiiboDb.Amiibo x) {
        AmiiboStore.Own own = o != null ? o : AmiiboWatch.ownCopy(x.id);
        if (own == null) return;
        dev.peanuts.ui.MenuFeel.select(a.getWindow().getDecorView().findFocus());
        if (AmiiboWatch.status().searching()) AmiiboWatch.place(own);
        else if (!AmiiboWatch.placeAhead(own)) android.widget.Toast.makeText(a, "This game's build has no amiibo stand yet.", android.widget.Toast.LENGTH_LONG).show();
    }

    // ---- views in the panel's look ----

    /** A square tile with the picture (Peanuts' own card until it comes); sizeDp 0: as wide as its cell */
    private static FrameLayout tile(Activity a, String id, int sizeDp, boolean square) {
        FrameLayout f = square ? new Square(a) : new FrameLayout(a);
        f.setBackground(Look.round(Look.ROW, Look.dp(a, 12)));
        int pad = Look.dp(a, 6);
        f.setPadding(pad, pad, pad, pad);   // also keeps PanelPolish's inset away (it pads a focusable with less than 6 dp)
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline ol) { ol.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Look.dp(a, 12)); }
        });
        f.setClipToOutline(true);
        f.setDefaultFocusHighlightEnabled(false);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable stand = Look.icon(a, "amiibo", Look.Ink.REST);
        if (stand != null) img.setImageDrawable(new android.graphics.drawable.InsetDrawable(stand, Look.dp(a, sizeDp > 0 && sizeDp < 50 ? 4 : 12)));
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        img.setTag(id);
        AmiiboPictures.load(a, id, Look.dp(a, 120), b -> { if (b != null && id.equals(img.getTag())) img.setImageBitmap(b); });
        return f;
    }

    static final class Square extends FrameLayout {
        Square(android.content.Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY)); }
    }

    private static TextView head(Activity a, String s) {
        TextView h = text(a, s, 12, Look.TAN, true);
        h.setAllCaps(true);
        h.setLetterSpacing(0.08f);
        h.setSingleLine(false);
        h.setPadding(0, Look.dp(a, 16), 0, Look.dp(a, 6));
        h.setAccessibilityHeading(true);
        return h;
    }

    private static TextView note(Activity a, String s) {
        TextView n = text(a, s, 12.5f, 0xFF8A8A8A, false);
        n.setSingleLine(false);
        n.setPadding(0, Look.dp(a, 6), 0, 0);
        return n;
    }

    private static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? 600 : 400, false));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }
}
