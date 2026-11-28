package dev.peanuts.amiibo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.nxnative.android.R;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.Icons;
import dev.peanuts.ui.PUi;

/**
 * The game page's amiibo area (dev.peanuts.ui.GamePage, GameAreas.AMIIBO): the game's amiibo switch first (AmiiboSwitch: amiibo is on only where the
 * player turns it on), then the player's own amiibo that fit the game, then every amiibo that fits it (AmiiboAPI's table), as square cards with their
 * names; a search over all of them; Import of .bin files, folders and ZIP packs. An amiibo opens its own page in the area: its picture, name, series and
 * type, what it does in this game, the game's data on it with Reset game data, and the other games that use it. Pictures load as the area opens
 * (AmiiboPictures), once the player has turned them on (Settings › Users › amiibo pictures, off by default); Peanuts' own card stands in until then, offline
 * and for one that is not there. B goes from an amiibo's page back to the pictures (GamePage.onBack asks {@link #back}).
 */
public final class AmiiboPage {
    private AmiiboPage() {}

    public static final int REQ_FILES = 87, REQ_FOLDER = 88;
    private static LinearLayout body;
    private static Activity owner;
    private static String titleId, game, query = "", detail;
    private static boolean wide, waiting;

    /** The tile's line: "2 of yours fit, 35 in all"; "Off for this game" while amiibo is off for it */
    public static CharSequence tileLine(Context c, String titleId) {
        if (!AmiiboSwitch.on(c, titleId)) return "Off for this game";
        AmiiboDb db = AmiiboStand.db(c);
        List<AmiiboDb.Amiibo> fit = db.fitting(titleId);
        int mine = AmiiboStore.forGame(AmiiboStore.list(c.getFilesDir()), titleId, ids(fit)).size();
        if (fit.isEmpty()) return mine > 0 ? count(mine, "of yours", "of yours") : "Import your amiibo";
        return (mine > 0 ? mine + " of yours fit, " : "") + fit.size() + " in all";
    }

    /** The area into its body; wide: the Thor's or a tablet's page (six pictures a row), else three */
    public static void area(Activity a, LinearLayout into, String titleId, String game, boolean wide) {
        if (!titleId.equals(AmiiboPage.titleId)) { query = ""; detail = null; }
        owner = a; body = into; AmiiboPage.titleId = titleId; AmiiboPage.game = game; AmiiboPage.wide = wide;
        build();
    }

    /** GamePage.onBack: from an amiibo's page, or a search, back to the pictures; true when it went back */
    public static boolean back() {
        if (body == null || !body.isAttachedToWindow()) return false;
        if (detail != null) { detail = null; build(); return true; }
        if (!query.isEmpty()) { query = ""; build(); return true; }
        return false;
    }

    /** The area is left (another area, the tiles) */
    public static void leave() { detail = null; query = ""; body = null; }

    private static void build() {
        Activity a = owner;
        if (a == null || body == null) return;
        body.removeAllViews();
        if (detail != null) { detailPage(a, detail); return; }
        AmiiboDb db = AmiiboStand.db(a);
        List<AmiiboDb.Amiibo> fit = db.fitting(titleId);
        Set<String> fitIds = ids(fit);
        List<AmiiboStore.Own> all = AmiiboStore.list(a.getFilesDir()), mine = AmiiboStore.forGame(all, titleId, fitIds);

        // Search and Import, at the right of the area's first line
        LinearLayout top = PUi.row(a);
        EditText search = searchField(a);
        top.addView(search, PUi.weight());
        View imp = PUi.button(a, "Import", PUi.Kind.PRIMARY, Feel.button(v -> importSheet(a)));
        LinearLayout.LayoutParams lp = PUi.wrap();
        lp.setMarginStart(PUi.dp(a, 10));
        top.addView(imp, lp);
        PUi.add(body, top, 2);

        if (!query.isEmpty()) { results(a, db, all); return; }
        PUi.add(body, switchRow(a), 14);
        PUi.add(body, PUi.section(a, "Yours"), 18);
        if (mine.isEmpty()) {
            TextView none = PUi.add(body, PUi.small(a, all.isEmpty() ? "None of yours yet. Import .bin files, a folder or a ZIP pack of your own amiibo, or pick one in the game."
                    : "None of yours fits this game yet."), 8);
            none.setPadding(PUi.dp(a, 4), 0, PUi.dp(a, 4), 0);
        } else {
            List<Item> items = new ArrayList<>();
            for (AmiiboStore.Own o : mine) items.add(new Item(db.get(o.id), o));
            grid(a, body, items);
            int other = all.size() - mine.size();
            if (other > 0) PUi.add(body, PUi.small(a, other == 1 ? "1 more of yours does not fit this game." : other + " more of yours do not fit this game."), 8)
                    .setPadding(PUi.dp(a, 4), 0, PUi.dp(a, 4), 0);
        }
        Set<String> own = new HashSet<>();
        for (AmiiboStore.Own o : mine) own.add(o.id);
        List<Item> rest = new ArrayList<>();
        for (AmiiboDb.Amiibo x : fit) if (!own.contains(x.id)) rest.add(new Item(x, null));
        if (!rest.isEmpty()) {
            PUi.add(body, PUi.section(a, "Fit " + game), 22);
            grid(a, body, rest);
        } else if (fit.isEmpty()) {
            PUi.add(body, PUi.small(a, "AmiiboAPI lists no amiibo for this game. It looked for one, so any of yours can be placed."), 14)
                    .setPadding(PUi.dp(a, 4), 0, PUi.dp(a, 4), 0);
        }
        PUi.add(body, PUi.small(a, (AmiiboSwitch.picturesOn(a) ? "Pictures and names come from AmiiboAPI."
                : "Names come from AmiiboAPI's list in the app. Its pictures show once you turn them on in Settings › Users › amiibo pictures.")
                + " Each game keeps its own data on an amiibo; nothing is written to a figure."), 18)
                .setPadding(PUi.dp(a, 4), 0, PUi.dp(a, 4), 0);
    }

    /** The game's amiibo switch (the same as the one in its Game options): placing needs it on, from the game's next start */
    private static View switchRow(Activity a) {
        boolean on = AmiiboSwitch.on(a, titleId);
        LinearLayout card = PUi.listCard(a);
        PUi.Setting row = new PUi.Setting(a, "", "amiibo in " + game, null)
                .value(on ? AmiiboSwitch.ON + "." : "Off: " + game + " finds no amiibo. Turn it on to place yours, from its next start.")
                .toggle(a, on, (b, now) -> { Feel.toggled(b, now); AmiiboSwitch.game(a, titleId, now); b.post(AmiiboPage::build); });
        row.view.setOnClickListener(v -> row.toggle.toggle());
        Icons.tile((TextView) row.view.getChildAt(0), "amiibo");   // the stand, as the tile and the Game options row show it
        card.addView(row.view);
        return card;
    }

    private static void results(Activity a, AmiiboDb db, List<AmiiboStore.Own> all) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Item> items = new ArrayList<>();
        for (AmiiboStore.Own o : all) { AmiiboDb.Amiibo x = db.get(o.id); if (matches(db, x, q)) items.add(new Item(x, o)); }
        for (AmiiboDb.Amiibo x : db.all()) if (matches(db, x, q)) items.add(new Item(x, null));
        PUi.add(body, PUi.section(a, items.isEmpty() ? "No amiibo found" : items.size() == 1 ? "1 amiibo" : items.size() + " amiibo"), 18);
        grid(a, body, items.subList(0, Math.min(items.size(), 120)));
    }

    static boolean matches(AmiiboDb db, AmiiboDb.Amiibo x, String q) {
        if (x.title().toLowerCase(Locale.ROOT).contains(q)) return true;
        String s = db.series(x), g = db.gameSeries(x);
        return s != null && s.toLowerCase(Locale.ROOT).contains(q) || g != null && g.toLowerCase(Locale.ROOT).contains(q);
    }

    private static EditText searchField(Activity a) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setHint("Search all amiibo");
        e.setText(query);
        e.setTextSize(15);
        e.setTextColor(PUi.col(a, R.color.bn_text));
        e.setHintTextColor(PUi.col(a, R.color.bn_text_2));
        e.setBackground(PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 14), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1)));
        e.setPadding(PUi.dp(a, 14), PUi.dp(a, 10), PUi.dp(a, 14), PUi.dp(a, 10));
        android.graphics.drawable.Drawable mag = Icons.get(a, "search", Icons.Ink.QUIET);
        if (mag != null) { mag.setBounds(0, 0, PUi.dp(a, 20), PUi.dp(a, 20)); e.setCompoundDrawablesRelative(mag, null, null, null); e.setCompoundDrawablePadding(PUi.dp(a, 10)); }
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                if (q.equals(query)) return;
                boolean was = query.isEmpty();
                query = q;
                if (was != q.isEmpty() || !q.isEmpty()) { int at = e.getSelectionEnd(); build(); refocus(at); }
            }
        });
        return e;
    }

    private static void refocus(int at) {
        if (body == null || body.getChildCount() == 0 || !(body.getChildAt(0) instanceof LinearLayout)) return;
        View f = ((LinearLayout) body.getChildAt(0)).getChildAt(0);
        if (f instanceof EditText) { f.requestFocus(); ((EditText) f).setSelection(Math.min(at, ((EditText) f).length())); }
    }

    // ---- the pictures ----

    static final class Item {
        final AmiiboDb.Amiibo a; final AmiiboStore.Own own;
        Item(AmiiboDb.Amiibo a, AmiiboStore.Own own) { this.a = a; this.own = own; }
    }

    private static void grid(Activity a, LinearLayout into, List<Item> items) {
        int cols = wide ? 6 : 3;
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) { row = PUi.row(a); row.setGravity(Gravity.TOP); PUi.add(into, row, i == 0 ? 8 : 10); }
            Item it = items.get(i);
            View cell = cell(a, it.a, it.own == null ? null : it.own.key, () -> { detail = it.own != null ? "own:" + it.own.key : "id:" + it.a.id; build(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % cols != 0) lp.setMarginStart(PUi.dp(a, 10));
            row.addView(cell, lp);
        }
        if (row != null) for (int k = items.size() % cols; k != 0 && k < cols; k++) {   // the last row's empty places keep the pictures' size
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.setMarginStart(PUi.dp(a, 10));
            row.addView(new View(a), lp);
        }
    }

    /** One amiibo: its picture on a square card (focusable), its name under it */
    static View cell(Activity a, AmiiboDb.Amiibo x, String key, Runnable open) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        Square card = new Square(a);
        card.setBackground(PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 18), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1)));
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(x.title() + (key != null ? ", yours" : ""));
        card.setOnClickListener(Feel.row(v -> open.run()));
        picture(a, card, x.id, PUi.dp(a, 150));
        col.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = PUi.text(a, x.title(), 13, R.color.bn_text_2, 500);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        PUi.add(col, name, 6);
        return col;
    }

    /** The picture of an amiibo into a card: Peanuts' own card (the stand icon) at once, the picture when it has come (none while the pictures are off) */
    static void picture(Context c, FrameLayout card, String id, int px) {
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = PUi.dp(c, 10);
        img.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.Drawable stand = Icons.get(c, "amiibo", Icons.Ink.QUIET);
        if (stand != null) img.setImageDrawable(new android.graphics.drawable.InsetDrawable(stand, PUi.dp(c, 18)));
        img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        img.setTag(id);
        AmiiboPictures.load(c, id, px, b -> { if (b != null && id.equals(img.getTag())) { img.setImageBitmap(b); img.setAlpha(0f); img.animate().alpha(1f).setDuration(160).start(); } });
    }

    /** A view as tall as it is wide */
    static final class Square extends FrameLayout {
        Square(Context c) {
            super(c);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), PUi.dp(v.getContext(), 18)); }
            });
            setClipToOutline(true);
        }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w), MeasureSpec.EXACTLY)); }
    }

    // ---- an amiibo's page ----

    private static void detailPage(Activity a, String which) {
        AmiiboDb db = AmiiboStand.db(a);
        AmiiboStore.Own own = which.startsWith("own:") ? AmiiboStore.find(a.getFilesDir(), which.substring(4)) : null;
        String id = own != null ? own.id : which.substring(3);
        if (own == null && which.startsWith("own:")) { detail = null; build(); return; }   // removed meanwhile
        AmiiboDb.Amiibo x = db.get(id);
        LinearLayout top = PUi.row(a);
        top.setGravity(Gravity.TOP);
        Square pic = new Square(a);
        pic.setBackground(PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 22), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1)));
        picture(a, pic, id, PUi.dp(a, 320));
        top.addView(pic, new LinearLayout.LayoutParams(PUi.dp(a, wide ? 180 : 130), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = PUi.weight();
        ilp.setMarginStart(PUi.dp(a, 18));
        top.addView(info, ilp);
        TextView name = PUi.text(a, x.title(), 22, R.color.bn_text, 800);
        name.setAccessibilityHeading(true);
        info.addView(name);
        LinearLayout chips = PUi.row(a);
        for (String s : new String[] {db.series(x), db.type(x)}) {
            if (s == null) continue;
            TextView ch = PUi.chip(a, s);
            LinearLayout.LayoutParams clp = PUi.wrap();
            if (chips.getChildCount() > 0) clp.setMarginStart(PUi.dp(a, 8));
            chips.addView(ch, clp);
        }
        if (chips.getChildCount() > 0) PUi.add(info, chips, 8);

        LinearLayout uses = PUi.add(info, PUi.card(a), 14);
        uses.addView(PUi.section(a, "In " + game));
        List<String> u = db.usesIn(titleId, id);
        if (u.isEmpty()) PUi.add(uses, PUi.body(a, "AmiiboAPI lists no use of it in this game. The game may still take it."), 6);
        for (String s : u) PUi.add(uses, PUi.body(a, s.endsWith(".") ? s : s + "."), 6);

        LinearLayout data = PUi.add(info, PUi.card(a), 12);
        data.addView(PUi.section(a, "Game data on it"));
        boolean has = own != null && AmiiboStore.hasData(own, titleId), keeps = db.keepsData(titleId, id);
        String words = has ? game + " keeps its data on this amiibo."
                : own == null ? (keeps ? game + " keeps data on an amiibo. It starts once you place this one." : game + " keeps no data on an amiibo.")
                : game + " keeps no data on this amiibo.";
        PUi.add(data, PUi.body(a, words), 6);
        if (own != null) {
            View reset = PUi.button(a, "Reset game data", PUi.Kind.TONAL, Feel.button(v -> resetData(a, own)));
            reset.setEnabled(has);
            reset.setAlpha(has ? 1f : 0.45f);
            PUi.add(data, reset, 12, PUi.wrap());
        }
        if (own != null) {
            long[] p = AmiiboStore.placedIn(own, titleId);
            String from = own.imported() ? "Yours, imported from " + (own.file.isEmpty() ? "a file" : own.file) + "." : "Yours, picked from the list.";
            String placed = p[0] == 0 ? " Not placed in this game yet." : " Placed " + (p[0] == 1 ? "once" : p[0] + " times") + " in this game, last on "
                    + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(p[1])) + ".";
            PUi.add(info, PUi.small(a, from + placed), 12);
        }
        PUi.add(body, top, 8);

        List<String> others = new ArrayList<>();
        for (String t : db.titles(id)) if (!t.equalsIgnoreCase(titleId) && db.gameName(t) != null && !others.contains(db.gameName(t))) others.add(db.gameName(t));
        if (!others.isEmpty()) {
            PUi.add(body, PUi.section(a, "Also used by"), 22);
            LinearLayout wrap = new LinearLayout(a);
            wrap.setOrientation(LinearLayout.VERTICAL);
            PUi.add(body, wrap, 8);
            TextView list = PUi.body(a, String.join(", ", others) + ".");
            list.setTextColor(PUi.col(a, R.color.bn_text_2));
            wrap.addView(list);
        }
        if (own != null) {
            View remove = PUi.button(a, "Remove from yours", PUi.Kind.QUIET, Feel.button(v -> removeOwn(a, own, x)));
            PUi.add(body, remove, 22, PUi.wrap());
        }
        body.post(() -> { View f = body.findFocus(); if (f == null) pic.requestFocus(); });
    }

    private static void resetData(Activity a, AmiiboStore.Own o) {
        AlertDialog d = PUi.sheet(a, "Reset game data?", PUi.body(a, game + " starts over with this amiibo, as the first time it is placed. Other games keep their data on it."))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", Feel.confirm((x, w) -> {
                    try { AmiiboStore.reset(o, titleId); Toast.makeText(a, "Game data reset.", Toast.LENGTH_SHORT).show(); }
                    catch (IOException e) { Toast.makeText(a, "The game data was not reset: the app's storage refused. Try again.", Toast.LENGTH_LONG).show(); }
                    build();
                })).create();
        Feel.dialog(d);
        d.show();
    }

    private static void removeOwn(Activity a, AmiiboStore.Own o, AmiiboDb.Amiibo x) {
        AlertDialog d = PUi.sheet(a, "Remove " + x.title() + "?", PUi.body(a, "It leaves yours with every game's data on it. The file you imported it from stays where it is."))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", Feel.confirm((y, w) -> {
                    try { AmiiboStore.remove(o); detail = null; }
                    catch (IOException e) { Toast.makeText(a, "It was not removed: the app's storage refused. Try again.", Toast.LENGTH_LONG).show(); }
                    build();
                })).create();
        Feel.dialog(d);
        d.show();
    }

    // ---- Import ----

    private static void importSheet(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        PUi.add(box, PUi.body(a, "Your own amiibo, from this device. Peanuts reads which amiibo each file is; it needs no keys and never downloads any."), 0);
        PUi.Setting files = new PUi.Setting(a, "", "Files", Feel.row(v -> { d[0].dismiss(); pick(a, false); })).value(".bin files, or ZIP packs of them").chevron(a);
        Icons.tile((TextView) files.view.getChildAt(0), "list");
        PUi.add(box, files.view, 12);
        PUi.Setting folder = new PUi.Setting(a, "", "A folder", Feel.row(v -> { d[0].dismiss(); pick(a, true); })).value("Every .bin in it and its folders").chevron(a);
        Icons.tile((TextView) folder.view.getChildAt(0), "folder");
        PUi.add(box, folder.view, 4);
        d[0] = PUi.sheet(a, "Import amiibo", box).setNegativeButton("Cancel", null).create();
        Feel.dialog(d[0]);
        d[0].show();
    }

    private static void pick(Activity a, boolean folder) {
        Intent i = folder ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));   // the storage, not Recent (a .bin is no media)
        try { waiting = true; a.startActivityForResult(i, folder ? REQ_FOLDER : REQ_FILES); }
        catch (android.content.ActivityNotFoundException e) { waiting = false; Toast.makeText(a, "This device has no file picker.", Toast.LENGTH_LONG).show(); }
    }

    /** MainActivity.onActivityResult (through LibrarySettings.result): the files or the folder picked for Import; true when it was this page's */
    public static boolean result(Activity a, int request, int result, Intent data) {
        if (request != REQ_FILES && request != REQ_FOLDER || !waiting) return false;
        waiting = false;
        if (result != Activity.RESULT_OK || data == null) return true;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return true;
        new Thread(() -> {
            AmiiboImport.Result r = request == REQ_FOLDER ? AmiiboFiles.folder(a, uris.get(0)) : AmiiboFiles.files(a, uris);
            a.runOnUiThread(() -> { Toast.makeText(a, r.words(), Toast.LENGTH_LONG).show(); query = ""; detail = null; build(); });
        }, "peanuts-amiibo-import").start();
        return true;
    }

    // ----

    static Set<String> ids(List<AmiiboDb.Amiibo> l) { Set<String> s = new HashSet<>(); for (AmiiboDb.Amiibo x : l) s.add(x.id); return s; }

    static String count(int n, String one, String many) { return n + " " + (n == 1 ? one : many); }
}
