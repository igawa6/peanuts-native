package dev.peanuts.users;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.nxnative.android.R;
import dev.peanuts.mii.MiiStore;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;

/**
 * Settings › Users as a part of a page: the console's users as cards with their pictures (up to 8, each with saves of its own), Add a user, the "Who's playing?"
 * setting and the Miis every user shares. It fills a column under the page's own title: Settings' Users section (dev.peanuts.ui.SettingsPage). The top screen browses (the D-pad moves between the cards); the focused user's card with its actions (its picture, Rename, Remove, which user games
 * start with) goes to the page's bottom screen through a {@link Sink}.
 */
public final class UsersPage {
    /** Where the focused user's card goes: the page's bottom screen (a Bottom, or Settings' own); key is the same while it shows the same user */
    public interface Sink { void show(String key, Bottom.Builder card); }

    private final Activity a;
    private final LinearLayout col;
    private final int start;
    private final boolean wide;
    private final Sink sink;
    private final Map<String, View> cards = new HashMap<>();
    private List<UsersPort.User> users;
    private String focused;
    private LinearLayout below;   // Settings on one screen: where the focused user's card goes, after the rows

    private UsersPage(Activity a, LinearLayout col, boolean wide, Sink sink) {
        this.a = a; this.col = col; this.wide = wide; this.sink = sink;
        start = col.getChildCount();
    }

    /** Fills col below what it holds (the page's title); wide: four cards a row (the Thor, a tablet), else two. Call {@link #refresh} when the page comes back */
    public static UsersPage fill(Activity a, LinearLayout col, boolean wide, Sink sink) {
        UsersPage p = new UsersPage(a, col, wide, sink);
        p.refresh();
        return p;
    }

    /**
     * Settings' Users section (dev.peanuts.ui.SettingsPage): the focused user's card on the launcher's bottom screen (BottomScreen.explain, a view of its own), and
     * the part read again when the window has the focus back (after the picture page, a rename or the editor), since Settings builds its column only once
     */
    public static UsersPage fill(Activity a, LinearLayout col, boolean wide) {
        LinearLayout below = new LinearLayout(a);   // a phone or a tablet with one screen (or the bottom screen off): the user's card below the rows
        below.setOrientation(LinearLayout.VERTICAL);
        UsersPage p = new UsersPage(a, col, wide, (key, card) -> {
            if (!Bottom.onSecondScreen(a)) {
                below.removeAllViews();
                View v = card.build(a);
                v.setPadding(0, 0, 0, 0);   // in the page's own column, as wide as the rows above (the bottom screen's margin is its own)
                below.addView(v);
                return;
            }
            below.removeAllViews();
            dev.peanuts.ui.BottomScreen.Explain e = new dev.peanuts.ui.BottomScreen.Explain();
            e.key = key;
            e.view = card::build;
            dev.peanuts.ui.BottomScreen.explain(a, e);
        });
        p.below = below;
        p.refresh();
        col.getViewTreeObserver().addOnWindowFocusChangeListener(has -> { if (has && col.isAttachedToWindow()) p.refresh(); });
        return p;
    }

    public String focused() { return focused; }
    public void focus(String uid) { focused = uid; }

    /** Reads the users again and draws the part anew (after a picture, a rename, the editor) */
    public void refresh() {
        users = UsersPort.list(a);
        if (focused == null || find(focused) == null) focused = UsersPort.defaultUid(a);
        if (find(focused) == null) focused = users.get(0).uid;
        while (col.getChildCount() > start) col.removeViewAt(start);
        cards.clear();
        int cols = wide ? 4 : 2;
        LinearLayout row = null;
        View focus = null;
        int n = users.size() + (users.size() < UsersPort.MAX ? 1 : 0);
        for (int i = 0; i < n; i++) {
            if (i % cols == 0) { row = new LinearLayout(a); row.setOrientation(LinearLayout.HORIZONTAL); PUi.add(col, row, i == 0 ? 12 : 8); }
            View cell = i < users.size() ? card(users.get(i), i) : addCard();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.leftMargin = i % cols == 0 ? 0 : PUi.dp(a, 8);
            row.addView(cell, lp);
            if (i < users.size() && users.get(i).uid.equals(focused)) focus = cell;
        }
        for (int i = n; i % cols != 0; i++) {   // keep the last row's cards their width
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, 1);
            lp.leftMargin = PUi.dp(a, 8);
            row.addView(new View(a), lp);
        }
        LinearLayout card = PUi.add(col, PUi.listCard(a), 12);
        card.addView(new PUi.Setting(a, "", "Who's playing?", Feel.row(v -> whoPlays())).value(whoLabel()).chevron(a).view);
        UserFace.rowIcon(a, card.getChildAt(0), "user");
        int miis = MiiStore.load(a.getFilesDir()).size();
        card.addView(new PUi.Setting(a, "", "Miis", Feel.row(v -> a.startActivity(new Intent().setClassName(a.getPackageName(), "dev.peanuts.mii.MiiActivity"))))
                .value(miis == 0 ? "No Miis yet, shared by every user" : miis == 1 ? "1 Mii, shared by every user" : miis + " Miis, shared by every user").chevron(a).view);
        UserFace.rowIcon(a, card.getChildAt(1), "mii");
        if (below != null) {
            if (below.getParent() instanceof ViewGroup) ((ViewGroup) below.getParent()).removeView(below);
            below.animate().cancel();   // the same view each time: an entrance the page began on it and this refresh cut off would leave it unseen
            below.setAlpha(1f);
            below.setTranslationY(0);
            PUi.add(col, below, 16);
        }
        if (focus != null) { View f = focus; f.post(f::requestFocus); }
        sink.show("users:" + focused, this::actions);
    }

    private UsersPort.User find(String uid) { for (UsersPort.User u : users) if (u.uid.equals(uid)) return u; return null; }

    private static GradientDrawable cardBg(Context c, boolean on) {
        return PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 18), PUi.col(c, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(c, on ? 2 : 1));
    }

    private View card(UsersPort.User u, int index) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(a, 6);
        c.setPadding(p, p, p, PUi.dp(a, 10));
        c.setBackground(cardBg(a, u.uid.equals(focused)));
        c.setFocusable(true);
        c.setClickable(true);
        c.setContentDescription(u.name);
        c.addView(UserFace.box(a, u.name, UserPictures.of(a.getFilesDir(), u.uid, index), 84, 14, true));
        TextView name = PUi.text(a, u.name, 15, R.color.bn_text, 600);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(PUi.dp(a, 4), PUi.dp(a, 6), PUi.dp(a, 4), 0);
        c.addView(name);
        TextView meta = PUi.small(a, meta(u));
        meta.setPadding(PUi.dp(a, 4), 0, PUi.dp(a, 4), 0);
        c.addView(meta);
        cards.put(u.uid, c);
        c.setOnFocusChangeListener((v, has) -> { if (has) pick(u.uid); });
        c.setOnClickListener(Feel.row(v -> { pick(u.uid); v.requestFocus(); }));
        return c;
    }

    private View addCard() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setMinimumHeight(PUi.dp(a, 136));
        GradientDrawable d = PUi.round(0, PUi.dp(a, 18), 0, 0);
        d.setStroke(PUi.dp(a, 1.5f), PUi.col(a, R.color.bn_text_2), PUi.dp(a, 6), PUi.dp(a, 4));
        c.setBackground(PUi.ripple(a, d));
        c.setFocusable(true);
        c.setClickable(true);
        ImageView plus = new ImageView(a);
        plus.setImageDrawable(PUi.icon(a, R.drawable.ic_add, R.color.bn_text_2));
        c.addView(plus, new LinearLayout.LayoutParams(PUi.dp(a, 26), PUi.dp(a, 26)));
        TextView t = PUi.text(a, "Add a user", 13, R.color.bn_text_2, 600);
        t.setPadding(0, PUi.dp(a, 6), 0, 0);
        c.addView(t, PUi.wrap());
        c.setOnClickListener(Feel.button(v -> name("Add a user", "", n -> {
            UsersPort.User u = UsersPort.add(a, n);
            if (u == null) { Toast.makeText(a, "There are already " + UsersPort.MAX + " users", Toast.LENGTH_SHORT).show(); return; }
            focused = u.uid;
            refresh();
        })));
        return c;
    }

    /** A card got the focus or a tap: the ring moves to it and its user's card shows below (again too: the page's own explanation may have taken its place) */
    private void pick(String uid) {
        if (!uid.equals(focused)) {
            focused = uid;
            for (Map.Entry<String, View> e : cards.entrySet()) e.getValue().setBackground(cardBg(a, e.getKey().equals(uid)));
        }
        sink.show("users:" + uid, this::actions);
    }

    // ---- What a user's card says ----

    private String meta(UsersPort.User u) {
        if (u.uid.equals(UsersPort.defaultUid(a)) && users.size() > 1) return "Playing now";
        return savesText(saves(a, u.uid));
    }

    static String savesText(int n) { return n == 0 ? "No saves yet" : n == 1 ? "1 save" : n + " saves"; }

    /**
     * How many games of the library keep a save for this user: their folder of each game's save (UserSaves.of: the game's own save folder for the first user, the
     * folder beside it for another; a save folder picked in a game's Folders as well), a file in it
     */
    static int saves(Context c, String uid) { return dev.nxnative.android.UserSaves.of(c, uid).size(); }

    // ---- The bottom screen: the focused user ----

    private View actions(Context c) {
        UsersPort.User u = find(focused);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(c, 16);
        box.setPadding(p, p, p, p);
        if (u == null) return box;
        int index = users.indexOf(u);
        LinearLayout card = PUi.card(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(PUi.dp(c, 14), PUi.dp(c, 14), PUi.dp(c, 14), PUi.dp(c, 14));
        card.addView(UserFace.box(c, u.name, UserPictures.of(a.getFilesDir(), u.uid, index), 120, 16, false));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(PUi.dp(c, 14), 0, 0, 0);
        TextView name = PUi.text(c, u.name, 24, R.color.bn_text, 800);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, PUi.dp(c, 8), 0, 0);
        boolean starts = u.uid.equals(UsersPort.defaultUid(a));
        if (starts && users.size() > 1) chips.addView(PUi.chip(c, "Playing now"), chipLp(c));
        List<dev.nxnative.android.UserSaves.Saved> mine = dev.nxnative.android.UserSaves.of(a, u.uid);
        chips.addView(PUi.chip(c, savesText(mine.size())), chipLp(c));
        text.addView(chips);
        card.addView(text, PUi.weight());
        box.addView(card, PUi.fill());
        PUi.add(box, PUi.button(c, "Change picture", PUi.Kind.PRIMARY, Feel.button(v ->
                a.startActivity(new Intent(a, PictureActivity.class).putExtra(PictureActivity.EXTRA_UID, u.uid).putExtra(PictureActivity.EXTRA_INDEX, index)))), 12);
        View rename = PUi.button(c, "Rename", PUi.Kind.TONAL, Feel.button(v -> name("Rename", u.name, nn -> { UsersPort.rename(a, u.uid, nn); refresh(); })));
        if (u.first()) PUi.add(box, rename, 8);
        else PUi.add(box, PUi.pair(c, rename, PUi.button(c, "Remove user", PUi.Kind.DANGER, Feel.button(v -> remove(u)))), 8);
        if (!starts && users.size() > 1)
            PUi.add(box, PUi.button(c, "Switch to " + u.name, PUi.Kind.SOFT, Feel.button(v -> { UsersPort.setDefault(a, u.uid); refresh(); })), 8);   // the user playing now: games start as them
        if (!mine.isEmpty()) PUi.add(box, PUi.button(c, "Show saves", PUi.Kind.TONAL, Feel.button(v -> showSaves(u, mine))), 8);   // each game's Saves tab, this user picked
        return box;
    }

    /** The games this user has a save of, with its size (read off the main thread first); one opens that game's page on its Saves tab with this user picked */
    private void showSaves(UsersPort.User u, List<dev.nxnative.android.UserSaves.Saved> mine) {
        new Thread(() -> {
            String[] items = new String[mine.size()];
            for (int i = 0; i < items.length; i++) items[i] = mine.get(i).name + "\n" + dev.nxnative.android.UserSaves.size(dev.nxnative.android.UserSaves.bytes(mine.get(i).dir));
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                AlertDialog d = new AlertDialog.Builder(a).setTitle(u.name + "'s saves")
                        .setItems(items, (x, w) -> {
                            Feel.picked(x);
                            if (!dev.nxnative.android.UserSaves.open(a, mine.get(w).titleId, u.uid))
                                Toast.makeText(a, mine.get(w).name + " is not in the library", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null).create();
                Feel.dialog(d);
                d.show();
            });
        }, "pn-user-saves").start();
    }

    private static LinearLayout.LayoutParams chipLp(Context c) {
        LinearLayout.LayoutParams lp = PUi.wrap();
        lp.rightMargin = PUi.dp(c, 6);
        return lp;
    }

    private void remove(UsersPort.User u) {
        PUi.confirmDanger(a, "Remove " + u.name + "?", "Games no longer offer this user. Their saves stay on the phone: each game's Saves tab shows them as a removed "
                + "user's, to back up, copy to another user or delete.", "Remove", () -> {
            UsersPort.remove(a, u.uid);
            try { UserPictures.remove(a.getFilesDir(), u.uid); } catch (IOException ignored) { }
            UserImages.removed(a, u.uid);
            focused = null;
            refresh();
        });
    }

    // ---- Dialogs ----

    private void name(String title, String initial, java.util.function.Consumer<String> done) {
        EditText f = new EditText(a);
        f.setSingleLine(true);
        f.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.setText(initial);
        f.setSelection(f.getText().length());
        f.setHint("Name");
        AlertDialog d = PUi.sheet(a, title, f).setPositiveButton("OK", Feel.confirm((x, w) -> {
            String n = UsersPort.clean(f.getText().toString());
            if (n.isEmpty()) { Feel.refused(a); Toast.makeText(a, "A user needs a name", Toast.LENGTH_SHORT).show(); return; }
            done.accept(n);
        })).setNegativeButton("Cancel", null).create();
        Feel.dialog(d);
        d.show();
    }

    private String whoLabel() {
        if (users.size() < 2) return "Asks when there are two or more users";
        if (UsersPrefs.whoMode(a) == WhoPlays.ASK) return "Asks when the app opens and before games from shortcuts";
        UsersPort.User d = find(UsersPort.defaultUid(a));
        return "Don't ask, " + (d != null ? d.name : users.get(0).name) + " plays";
    }

    private void whoPlays() {
        String[] items = {"Ask when the app opens and before games from shortcuts or frontends", "Don't ask"};   // one setting for both questions (WhoPlays)
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Who's playing?")
                .setSingleChoiceItems(items, UsersPrefs.whoMode(a), (x, w) -> { UsersPrefs.whoMode(a, w); Feel.picked(x); x.dismiss(); refresh(); })
                .setNegativeButton("Cancel", null).create();
        Feel.dialog(d);
        d.show();
    }
}
