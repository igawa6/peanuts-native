package dev.peanuts.users;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.Screens;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;

/**
 * "Who's playing?" (Settings › Users), with two or more users: big profile cards over the app's drifting peanuts on the top screen, under its peanut badge.
 * <ul>
 * <li>When the app opens (its icon, a cold start: {@link #appOpened}): the user playing now picked already; the bottom screen shows them, Continue as X and
 * "Always X", which stops this question and the one before a game from a shortcut or a frontend (the setting's Don't ask). The pick is the user games start
 * with (UsersPort.setDefault), so every game started in the app plays as them; the picture at Home's top right (ProfileButton) switches later. Back keeps the
 * user playing now.</li>
 * <li>Before a game that starts without the app, from a home screen shortcut or a frontend ({@link #ask}): the user who played this game last picked
 * already; the bottom screen shows the game, Play as X and "Always X for this game". The pick goes to that game only (WhoPlays.EXTRA, GameActivity: DM_USER).
 * Back starts nothing. It runs in a task of its own (the manifest's empty taskAffinity), so Home never shows in between.</li>
 * </ul>
 */
public final class WhoPlaysActivity extends Activity {
    static final String EXTRA_GAME = "dev.peanuts.game";

    /**
     * Screens.startGame's question: true when the player is asked first (this page then starts the game), false when the game starts now, with the user the
     * setting or the game's "Always" names already in the intent. Only a start without the app asks (FrontendLaunch's "frontend" extra: a home screen shortcut,
     * a frontend); a start in the app plays as the user playing now
     */
    public static boolean ask(Activity launcher, Intent game) {
        if (game.hasExtra(WhoPlays.EXTRA)) return false;
        String tid = game.getStringExtra("title_id");
        if (tid == null) return false;
        List<UsersPort.User> users = UsersPort.list(launcher);
        WhoPlays.Decision d = WhoPlays.decide(ids(users), UsersPort.defaultUid(launcher), UsersPrefs.whoMode(launcher), UsersPrefs.always(launcher, tid),
                UsersPrefs.last(launcher, tid), game.getBooleanExtra("frontend", false));
        if (!d.ask) {
            if (users.size() > 1) { game.putExtra(WhoPlays.EXTRA, d.uid); UsersPrefs.last(launcher, tid, d.uid); }
            return false;
        }
        Intent ask = new Intent(launcher, WhoPlaysActivity.class).putExtra(EXTRA_GAME, game).putExtra(WhoPlays.EXTRA, d.uid);
        if ((game.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launcher.startActivity(ask);
        return true;
    }

    /**
     * MainActivity's hook when it is made anew (not when the app's icon went back to a running game, GameReturn): the app's icon opened it, setup is done and
     * there are two or more users, so "Who's playing?" comes first, the user playing now picked
     */
    public static void appOpened(Activity home, Intent launch) {
        if (launch == null || !Intent.ACTION_MAIN.equals(launch.getAction()) || !launch.hasCategory(Intent.CATEGORY_LAUNCHER) || !PeanutsPrefs.setupDone(home)) return;
        WhoPlays.Decision d = WhoPlays.atAppStart(ids(UsersPort.list(home)), UsersPort.defaultUid(home), UsersPrefs.whoMode(home));
        if (d.ask) home.startActivity(new Intent(home, WhoPlaysActivity.class).putExtra(WhoPlays.EXTRA, d.uid));
    }

    private static List<String> ids(List<UsersPort.User> users) {
        List<String> ids = new ArrayList<>();
        for (UsersPort.User u : users) ids.add(u.uid);
        return ids;
    }

    /** GameActivity's hook: the user the start names (WhoPlays.EXTRA) when it is one of the users, else def (the user games start with) */
    public static String startUser(Context c, Intent start, String def) {
        String uid = WhoPlays.uid(start != null ? start.getStringExtra(WhoPlays.EXTRA) : null, def);
        for (UsersPort.User u : UsersPort.list(c)) if (u.uid.equals(uid)) return uid;
        return def;
    }

    private Intent game;   // null: the question when the app opens
    private String tid, name, picked;
    private boolean always;
    private List<UsersPort.User> users;
    private final java.util.Map<String, View> cards = new java.util.HashMap<>();
    private Bottom bottom;
    private dev.nxnative.android.BananaBackgroundView bananas;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        game = getIntent().getParcelableExtra(EXTRA_GAME, Intent.class);
        if (game != null) { tid = game.getStringExtra("title_id"); name = game.getStringExtra("name"); }
        picked = saved != null ? saved.getString("picked") : getIntent().getStringExtra(WhoPlays.EXTRA);
        users = UsersPort.list(this);
        if (users.size() < 2) { finish(); return; }
        if (picked == null || find(picked) == null) picked = users.get(0).uid;
        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(PUi.col(this, R.color.bn_bg));
        FrameLayout frame = new FrameLayout(this);
        bananas = new dev.nxnative.android.BananaBackgroundView(this);
        frame.addView(bananas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));   // the app's drifting peanuts, as on Home
        root.addView(frame, new LinearLayout.LayoutParams(wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, wide ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1));
        FrameLayout inline = new FrameLayout(this);
        root.addView(inline, new LinearLayout.LayoutParams(wide ? PUi.dp(this, 380) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        bottom = new Bottom(this, inline);
        frame.addView(top(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bottom.set(this::panel);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("picked", picked); }
    // the peanuts drift while the question shows (Home's do between its onResume and onPause; a still picture otherwise), one background with the bottom half's
    @Override protected void onStart() { super.onStart(); bottom.start(); if (bananas != null) bananas.setRunning(true); }
    @Override protected void onStop() { if (bananas != null) bananas.setRunning(false); bottom.stop(); super.onStop(); }

    // ---- The top screen: the users ----

    private View top() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = PUi.dp(this, 26);
        col.setPadding(p, PUi.dp(this, 20), p, p);
        col.addView(PUi.badge(this, 52));   // the app's peanut badge, as at the top of Home
        TextView title = PUi.text(this, "Who's playing?", 26, R.color.bn_text, 800);
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        PUi.add(col, title, 10, PUi.wrap());
        TextView sub = PUi.text(this, game == null ? "Every game you start here plays as this user. Switch with the picture at the top right of Home."
                : (name != null ? name : "This game") + " keeps a save for each user.", 14, R.color.bn_text_2, 400);
        sub.setGravity(Gravity.CENTER);
        PUi.add(col, sub, 4, PUi.wrap());
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);   // the picked card is drawn a little larger than its place
        col.setClipChildren(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(PUi.dp(this, 12), PUi.dp(this, 12), PUi.dp(this, 12), PUi.dp(this, 12));
        hs.addView(row);
        View focus = null;
        for (int i = 0; i < users.size(); i++) {
            UsersPort.User u = users.get(i);
            View c = card(u, i);
            LinearLayout.LayoutParams lp = PUi.wrap();
            if (i > 0) lp.leftMargin = PUi.dp(this, 18);
            row.addView(c, lp);
            if (u.uid.equals(picked)) focus = c;
        }
        LinearLayout.LayoutParams lp = PUi.wrap();
        lp.topMargin = PUi.dp(this, 20);
        col.addView(hs, lp);
        if (focus != null) { View f = focus; f.post(f::requestFocus); }
        Feel.page(col, 0);
        return col;
    }

    private static GradientDrawable cardBg(Context c, boolean on) {
        return PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 18), PUi.col(c, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(c, on ? 2 : 1));
    }

    private View card(UsersPort.User u, int index) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = PUi.dp(this, 10);
        c.setPadding(p, p, p, p);
        c.setBackground(cardBg(this, u.uid.equals(picked)));
        c.setFocusable(true);
        c.setClickable(true);
        c.setContentDescription(u.name);
        c.addView(UserFace.box(this, u.name, UserPictures.of(getFilesDir(), u.uid, index), 150, 14, false));
        TextView n = PUi.text(this, u.name, 18, R.color.bn_text, 700);
        n.setPadding(0, PUi.dp(this, 6), 0, 0);
        c.addView(n, PUi.wrap());
        c.addView(PUi.small(this, game == null ? UsersPage.savesText(UsersPage.saves(this, u.uid)) : hasSave(u.uid) ? "Has a save" : "No save yet"), PUi.wrap());
        cards.put(u.uid, c);
        if (u.uid.equals(picked)) { c.setScaleX(1.035f); c.setScaleY(1.035f); }
        c.setOnFocusChangeListener((v, has) -> { if (has) pick(u.uid); });
        c.setOnClickListener(Feel.launch(v -> { pick(u.uid); play(); }));
        return c;
    }

    private void pick(String uid) {
        if (uid.equals(picked)) return;
        picked = uid;
        for (java.util.Map.Entry<String, View> e : cards.entrySet()) {   // the picked card a little larger, the others back, calmly
            boolean on = e.getKey().equals(uid);
            View v = e.getValue();
            v.setBackground(cardBg(this, on));
            v.animate().scaleX(on ? 1.035f : 1f).scaleY(on ? 1.035f : 1f).setDuration(180).start();
        }
        always = false;
        bottom.refresh();
    }

    private UsersPort.User find(String uid) { for (UsersPort.User u : users) if (u.uid.equals(uid)) return u; return null; }
    private UsersPort.User user(String uid) { UsersPort.User u = find(uid); return u != null ? u : users.get(0); }

    /** Whether this user has a save of the game: a file in their folder of its save (UserSaves.has: the game's save folder, a picked one as well) */
    private boolean hasSave(String uid) { return tid != null && dev.nxnative.android.UserSaves.has(this, tid, uid); }

    // ---- The bottom screen: the game, Play as, Always ----

    private View panel(Context c) {
        if (game == null) return appPanel(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(c, 16);
        col.setPadding(p, p, p, p);
        UsersPort.User u = user(picked);
        LinearLayout card = PUi.card(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(PUi.dp(c, 12), PUi.dp(c, 12), PUi.dp(c, 12), PUi.dp(c, 12));
        TextView cover = PUi.tile(c, name, 84);
        Bitmap icon = tid != null ? BitmapFactory.decodeFile(new File(PeanutsPrefs.iconsDir(c), tid + ".png").getPath()) : null;
        if (icon != null) PUi.tileIcon(cover, icon);
        card.addView(cover);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(PUi.dp(c, 12), 0, 0, 0);
        TextView n = PUi.text(c, name != null ? name : "", 20, R.color.bn_text, 800);
        n.setMaxLines(2);
        text.addView(n);
        LinearLayout chips = new LinearLayout(c);
        chips.setPadding(0, PUi.dp(c, 6), 0, 0);
        chips.addView(PUi.chip(c, hasSave(u.uid) ? u.name + " has a save" : "A new save for " + u.name));
        text.addView(chips);
        card.addView(text, PUi.weight());
        col.addView(card, PUi.fill());
        PUi.Action play = PUi.action(c, R.drawable.ic_play, "Play as " + u.name, PUi.Kind.PRIMARY, Feel.launch(v -> play()));
        PUi.add(col, play, 12);
        LinearLayout box = PUi.add(col, PUi.listCard(c), 12);
        PUi.Setting s = new PUi.Setting(c, "", "Always " + u.name + " for this game", null).value("Skips this question for this game")
                .toggle(c, always, (b, on) -> { Feel.toggled(b, on); always = on; });
        s.view.setOnClickListener(v -> s.toggle.toggle());
        box.addView(s.view);
        UserFace.rowIcon(c, s.view, "user");
        return col;
    }

    /** When the app opens: the picked user, Continue as X, and "Always X" (from then on no question when the app opens, nor before a game from a shortcut or a frontend) */
    private View appPanel(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(c, 16);
        col.setPadding(p, p, p, p);
        UsersPort.User u = user(picked);
        LinearLayout card = PUi.card(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(PUi.dp(c, 12), PUi.dp(c, 12), PUi.dp(c, 12), PUi.dp(c, 12));
        card.addView(UserFace.box(c, u.name, UserPictures.of(getFilesDir(), u.uid, users.indexOf(u)), 96, 16, false));
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(PUi.dp(c, 14), 0, 0, 0);
        TextView n = PUi.text(c, u.name, 24, R.color.bn_text, 800);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(n);
        LinearLayout chips = new LinearLayout(c);
        chips.setPadding(0, PUi.dp(c, 8), 0, 0);
        chips.addView(PUi.chip(c, UsersPage.savesText(UsersPage.saves(c, u.uid))));
        text.addView(chips);
        card.addView(text, PUi.weight());
        col.addView(card, PUi.fill());
        PUi.add(col, PUi.button(c, "Continue as " + u.name, PUi.Kind.PRIMARY, Feel.launch(v -> play())), 12);
        LinearLayout box = PUi.add(col, PUi.listCard(c), 12);
        PUi.Setting s = new PUi.Setting(c, "", "Always " + u.name, null).value("Skips this question and the one before games from shortcuts or frontends")
                .toggle(c, always, (b, on) -> { Feel.toggled(b, on); always = on; });
        s.view.setOnClickListener(v -> s.toggle.toggle());
        box.addView(s.view);
        UserFace.rowIcon(c, s.view, "user");
        return col;
    }

    private void play() {
        if (game == null) {   // the app opens: the pick is the user games start with; "Always" is the setting's Don't ask, both questions stop
            UsersPort.setDefault(this, picked);
            if (always) UsersPrefs.whoMode(this, WhoPlays.DONT_ASK);
            finish();
            return;
        }
        if (tid != null) {
            UsersPrefs.last(this, tid, picked);
            if (always) UsersPrefs.always(this, tid, picked);
        }
        Intent start = new Intent(game).putExtra(WhoPlays.EXTRA, picked);
        Screens.startGame(this, start);
        finish();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BUTTON_A && getCurrentFocus() == null) { play(); return true; }
        return super.onKeyDown(code, e);
    }
}
