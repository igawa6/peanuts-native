package dev.peanuts.users;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.List;

import dev.nxnative.android.R;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;

/**
 * The user playing now at Home's top right, beside FAQ and Settings (MainActivity's header buttons): their picture, their name under it. Shown only with two
 * or more users, the feature's threshold. A tap opens a menu below it: Profile (Settings › Users, their card focused) and Switch profile, which opens a dialog
 * in the middle of the screen with every user side by side. A switch makes that user the one games start with (UsersPort.setDefault), as "Who's playing?"
 * does when the app opens (WhoPlaysActivity), while the app's peanut cracks open over the whole screen for a moment.
 */
public final class ProfileButton {
    private ProfileButton() {}

    /** The button; openSettings shows Settings (MainActivity's page), after which the Users section is opened */
    public static View make(Activity a, Runnable openSettings) {
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        fill(a, column, openSettings);
        // a pick when the app opened, a switch in Settings, a new picture: the button shows the user playing now when Home has the focus again
        column.getViewTreeObserver().addOnWindowFocusChangeListener(has -> { if (has && column.isAttachedToWindow()) fill(a, column, openSettings); });
        return column;
    }

    private static void fill(Activity a, LinearLayout column, Runnable openSettings) {
        column.removeAllViews();
        List<UsersPort.User> users = UsersPort.list(a);
        if (users.size() < 2) { column.setVisibility(View.GONE); return; }
        column.setVisibility(View.VISIBLE);
        UsersPort.User now = current(a, users);
        FrameLayout box = UserFace.box(a, now.name, UserPictures.of(a.getFilesDir(), now.uid, users.indexOf(now)), 44, 14, false);
        box.setForeground(PUi.ripple(a, PUi.round(0, PUi.dp(a, 14), 0, 0)));   // the tap shows over the picture
        box.setFocusable(true);
        box.setClickable(true);
        box.setContentDescription(now.name + ", playing now. Profile and switching users");
        box.setOnClickListener(Feel.button(v -> menu(a, column, openSettings)));
        column.addView(box);
        TextView caption = PUi.text(a, now.name, 10, R.color.bn_accent, 700);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setMaxWidth(PUi.dp(a, 64));
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, PUi.dp(a, 2), 0, 0);
        caption.setMinHeight(PUi.dp(a, 16));
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(caption, PUi.wrap());
    }

    private static UsersPort.User current(Context c, List<UsersPort.User> users) {
        String uid = UsersPort.defaultUid(c);
        for (UsersPort.User u : users) if (u.uid.equals(uid)) return u;
        return users.get(0);
    }

    /** The menu below the button, its right edge under the button's: the user playing now with Profile, then the others to switch to */
    private static void menu(Activity a, LinearLayout anchor, Runnable openSettings) {
        List<UsersPort.User> users = UsersPort.list(a);
        if (users.size() < 2) return;
        LinearLayout card = PUi.card(a);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(a, 8);
        card.setPadding(p, p, p, p);
        card.setElevation(PUi.dp(a, 8));
        int width = PUi.dp(a, 280);
        PopupWindow pop = new PopupWindow(card, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(0));
        pop.setOutsideTouchable(true);

        View profile = row(a, null, users, 0, "Profile", "Picture, name and saves", v -> {
            pop.dismiss();
            openSettings.run();
            a.getWindow().getDecorView().post(() -> dev.peanuts.ui.SettingsPage.go(a, "users"));   // once Settings is laid out: its Users section, this user's card focused
        });
        UserFace.rowIcon(a, profile, "user");
        card.addView(profile);
        View change = PUi.button(a, "Switch profile", PUi.Kind.TONAL, Feel.button(v -> { pop.dismiss(); switchDialog(a, anchor, openSettings); }));
        LinearLayout.LayoutParams lp = PUi.fill();
        lp.setMargins(PUi.dp(a, 8), PUi.dp(a, 6), PUi.dp(a, 8), PUi.dp(a, 8));
        card.addView(change, lp);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        pop.showAsDropDown(anchor, anchor.getWidth() - width, PUi.dp(a, 6));
        profile.requestFocus();
    }

    /** Every user side by side in a dialog in the middle of the screen, the user playing now marked; a pick switches (with the cracking peanut), Back keeps */
    private static void switchDialog(Activity a, LinearLayout anchor, Runnable openSettings) {
        List<UsersPort.User> users = UsersPort.list(a);
        if (users.size() < 2) return;
        UsersPort.User now = current(a, users);
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = PUi.card(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = PUi.dp(a, 20);
        card.setPadding(p, p, p, p);
        TextView title = PUi.text(a, "Switch profile", 22, R.color.bn_text, 800);
        title.setAccessibilityHeading(true);
        card.addView(title, PUi.wrap());
        PUi.add(card, PUi.small(a, "Games you start here play as the user you pick."), 4, PUi.wrap());
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int q = PUi.dp(a, 4);
        row.setPadding(q, q, q, q);
        hs.addView(row);
        View focus = null;
        for (int i = 0; i < users.size(); i++) {
            UsersPort.User u = users.get(i);
            boolean on = u.uid.equals(now.uid);
            LinearLayout c = new LinearLayout(a);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            int cp = PUi.dp(a, 8);
            c.setPadding(cp, cp, cp, PUi.dp(a, 10));
            c.setBackground(PUi.ripple(a, PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 16), PUi.col(a, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(a, on ? 2 : 1))));
            c.setFocusable(true);
            c.setClickable(true);
            c.setContentDescription(u.name + (on ? ", playing now" : ""));
            c.addView(UserFace.box(a, u.name, UserPictures.of(a.getFilesDir(), u.uid, i), 112, 14, false));
            TextView n = PUi.text(a, u.name, 16, R.color.bn_text, 700);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            n.setMaxWidth(PUi.dp(a, 112));
            n.setPadding(0, PUi.dp(a, 6), 0, 0);
            c.addView(n, PUi.wrap());
            c.addView(PUi.small(a, on ? "Playing now" : UsersPage.savesText(UsersPage.saves(a, u.uid))), PUi.wrap());
            c.setOnClickListener(Feel.button(v -> {
                d.dismiss();
                if (!on) switchTo(a, u, anchor, openSettings);
            }));
            LinearLayout.LayoutParams lp = PUi.wrap();
            if (i > 0) lp.leftMargin = PUi.dp(a, 12);
            row.addView(c, lp);
            if (on) focus = c;
        }
        LinearLayout.LayoutParams hp = PUi.wrap();
        hp.topMargin = PUi.dp(a, 16);
        card.addView(hs, hp);
        d.setContentView(card);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        if (focus != null) { View f = focus; f.post(f::requestFocus); }
    }

    /**
     * The switch: the user games start with is u now; over the whole window the app's peanut cracks open with "Switching to <name>" under it for a moment
     * (about 1.8 s), then it fades and Home shows the new user at its top right. Touches wait meanwhile
     */
    private static void switchTo(Activity a, UsersPort.User u, LinearLayout anchor, Runnable openSettings) {
        UsersPort.setDefault(a, u.uid);
        fill(a, anchor, openSettings);
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        FrameLayout cover = new FrameLayout(a);
        cover.setBackgroundColor(PUi.col(a, R.color.bn_bg));
        cover.setClickable(true);   // the page under it takes no touch meanwhile
        cover.setFocusable(true);
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        mid.addView(new dev.peanuts.ui.CrackingPeanut(a), new LinearLayout.LayoutParams(PUi.dp(a, 180), PUi.dp(a, 180)));
        TextView t = PUi.text(a, "Switching to " + u.name, 20, R.color.bn_text, 700);
        t.setPadding(0, PUi.dp(a, 12), 0, 0);
        mid.addView(t, PUi.wrap());
        cover.addView(mid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        cover.setAlpha(0f);
        decor.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover.requestFocus();
        cover.animate().alpha(1f).setDuration(160).start();
        cover.postDelayed(() -> cover.animate().alpha(0f).setDuration(260).withEndAction(() -> decor.removeView(cover)).start(), 1800);
    }

    /** One line of the menu: a user's picture (or, with user null, an icon tile the caller fills) and two lines of text */
    private static LinearLayout row(Activity a, UsersPort.User u, List<UsersPort.User> users, int pictureDp, String title, String line, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = PUi.dp(a, 8);
        r.setPadding(p, p, p, p);
        if (u != null) {
            r.addView(UserFace.box(a, u.name, UserPictures.of(a.getFilesDir(), u.uid, users.indexOf(u)), pictureDp, pictureDp > 44 ? 14 : 11, false));
        } else {
            TextView tile = PUi.text(a, "", 18, R.color.bn_text, 700);   // an icon tile, as Settings' rows have (UserFace.rowIcon puts the icon in)
            tile.setGravity(Gravity.CENTER);
            tile.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 11), 0, 0));
            r.addView(tile, new LinearLayout.LayoutParams(PUi.dp(a, 40), PUi.dp(a, 40)));
        }
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(PUi.dp(a, 12), 0, 0, 0);
        TextView t = PUi.text(a, title, pictureDp > 44 ? 18 : 15, R.color.bn_text, pictureDp > 44 ? 800 : 600);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(t);
        text.addView(PUi.small(a, line));
        r.addView(text, PUi.weight());
        if (click != null) {
            r.setBackground(PUi.ripple(a, PUi.round(0, PUi.dp(a, 12), 0, 0)));
            r.setFocusable(true);
            r.setClickable(true);
            r.setOnClickListener(Feel.row(click));
        }
        return r;
    }
}
