package dev.peanuts.users;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.nxnative.android.R;
import dev.peanuts.mii.MiiEditor;
import dev.peanuts.mii.MiiStore;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;
import dev.peanuts.ui.UiPort;

/**
 * A user's profile picture (Settings › Users › Change picture): one of the player's Miis, drawn from the firmware's parts, or one of the firmware's own pictures,
 * on a background colour. The top screen lists them (New Mii first); the bottom screen shows the pick on its background, the colours, and Use this picture.
 * New Mii and Edit this Mii open the Mii editor, which comes back here with that Mii picked.
 */
public final class PictureActivity extends Activity {
    public static final String EXTRA_UID = "dev.peanuts.user.uid", EXTRA_INDEX = "dev.peanuts.user.index";
    static final int EDIT = 1;

    private String uid, name;
    private UserPictures.Picture pick;
    private int tab;
    private FrameLayout frame;
    private Bottom bottom;
    private boolean wide;
    private final Map<String, View> cells = new HashMap<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        uid = getIntent().getStringExtra(EXTRA_UID);
        int index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        name = "";
        for (UsersPort.User u : UsersPort.list(this)) if (u.uid.equals(uid)) name = u.name;
        pick = UserPictures.of(getFilesDir(), uid, index);
        if (saved != null) pick = new UserPictures.Picture(saved.getString("source", ""), saved.getInt("bg", pick.background));
        tab = pick.firmware() != null ? 1 : 0;
        wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(PUi.col(this, R.color.bn_bg));
        frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, wide ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1));
        FrameLayout inline = new FrameLayout(this);
        root.addView(inline, new LinearLayout.LayoutParams(wide ? PUi.dp(this, 380) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        bottom = new Bottom(this, inline);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("source", pick.source); out.putInt("bg", pick.background); }
    @Override protected void onStart() { super.onStart(); bottom.start(); build(); }
    @Override protected void onStop() { bottom.stop(); super.onStop(); }

    // ---- The top screen ----

    private void build() {
        frame.removeAllViews();
        cells.clear();
        LinearLayout content = PUi.page(this, frame, 760);
        View seg = UiPort.segmented(this, new String[] {"Miis", "Firmware"}, tab, k -> { tab = k; build(); });
        LinearLayout head = PUi.header(this, PUi.back(this, v -> finish()), "Picture", null);
        head.addView(seg, new LinearLayout.LayoutParams(PUi.dp(this, wide ? 290 : 220), ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(head);
        PUi.add(content, PUi.small(this, name), 0);
        int cols = Bottom.columns(this, wide ? 112 : 100, 4, 380, 6);   // as many as fit beside the bottom half, when it is on this screen
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(cols);
        View focus = null;
        if (tab == 0) {
            grid.addView(newMii(), cell());
            List<MiiStore.Mii> miis = MiiStore.load(getFilesDir());
            for (MiiStore.Mii m : miis) {
                String id = MiiStore.hex(m.createId);
                View c = card("mii:" + id, m.name, UserPictures.mii(id, pick.background));
                grid.addView(c, cell());
                if (("mii:" + id).equals(pick.source)) focus = c;
            }
        } else {
            List<String> pics = FirmwarePictures.names(this);
            if (pics.isEmpty()) {
                TextView t = PUi.body(this, "The console's own pictures come from your firmware. The app reads them from it at its next start, or when you choose the firmware again under Console in Settings.");
                PUi.add(content, PUi.card(this), 12).addView(t);
            }
            for (String n : pics) {
                View c = card("fw:" + n, null, UserPictures.firmware(n, pick.background));
                grid.addView(c, cell());
                if (("fw:" + n).equals(pick.source)) focus = c;
            }
        }
        PUi.add(content, grid, 12);
        if (focus != null) { View f = focus; f.post(f::requestFocus); }
        bottom.set(this::panel);
        Feel.page(content, 1);
    }

    private GridLayout.LayoutParams cell() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED));
        lp.width = PUi.dp(this, wide ? 112 : 100);   // fixed, so a short list keeps the grid's columns
        int h = PUi.dp(this, 4);
        lp.setMargins(h, h, h, h);
        return lp;
    }

    private static GradientDrawable cardBg(Context c, boolean on) {
        return PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 18), PUi.col(c, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(c, on ? 2 : 1));
    }

    /** A picture to pick: its face on the current background, the Mii's name under it */
    private View card(String source, String label, UserPictures.Picture p) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = PUi.dp(this, 6);
        c.setPadding(pad, pad, pad, label != null ? PUi.dp(this, 8) : pad);
        c.setBackground(cardBg(this, source.equals(pick.source)));
        c.setFocusable(true);
        c.setClickable(true);
        c.setContentDescription(label != null ? label : "A picture from your firmware");
        View box = UserFace.box(this, label != null ? label : name, p, 84, 14, true);
        box.setTag(BOX);
        c.addView(box);
        if (label != null) {
            TextView t = PUi.text(this, label, 13, R.color.bn_text, 600);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, PUi.dp(this, 6), 0, 0);
            c.addView(t);
        }
        cells.put(source, c);
        c.setOnFocusChangeListener((v, has) -> { if (has) choose(source); });
        c.setOnClickListener(Feel.row(v -> { choose(source); v.requestFocus(); }));
        return c;
    }

    private View newMii() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setMinimumHeight(PUi.dp(this, 124));
        GradientDrawable d = PUi.round(0, PUi.dp(this, 18), 0, 0);
        d.setStroke(PUi.dp(this, 1.5f), PUi.col(this, R.color.bn_text_2), PUi.dp(this, 6), PUi.dp(this, 4));
        c.setBackground(PUi.ripple(this, d));
        c.setFocusable(true);
        c.setClickable(true);
        ImageView plus = new ImageView(this);
        plus.setImageDrawable(PUi.icon(this, R.drawable.ic_add, R.color.bn_text_2));
        c.addView(plus, new LinearLayout.LayoutParams(PUi.dp(this, 26), PUi.dp(this, 26)));
        TextView t = PUi.text(this, "New Mii", 13, R.color.bn_text_2, 600);
        t.setPadding(0, PUi.dp(this, 6), 0, 0);
        c.addView(t, PUi.wrap());
        c.setOnClickListener(Feel.button(v -> MiiEditor.edit(this, EDIT, null)));
        return c;
    }

    private static final String BOX = "picture";

    /** The grid's pictures on the background just picked, as the picture will be (their faces stay, only the colour behind them changes) */
    private void recolor() {
        for (View cell : cells.values()) {
            View box = cell.findViewWithTag(BOX);
            if (box != null) box.setBackground(PUi.round(0xff000000 | pick.color(), PUi.dp(this, 14), 0, 0));
        }
    }

    private void choose(String source) {
        if (source.equals(pick.source)) return;
        pick = new UserPictures.Picture(source, pick.background);
        for (Map.Entry<String, View> e : cells.entrySet()) e.getValue().setBackground(cardBg(this, e.getKey().equals(source)));
        bottom.refresh();
    }

    // ---- The bottom screen ----

    private View panel(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(c, 16);
        col.setPadding(p, p, p, p);
        LinearLayout card = PUi.card(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(PUi.dp(c, 14), PUi.dp(c, 14), PUi.dp(c, 14), PUi.dp(c, 14));
        card.addView(UserFace.box(c, name, pick, 112, 16, false));
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(PUi.dp(c, 14), 0, 0, 0);
        String shown = shownName();
        TextView n = PUi.text(c, shown, 22, R.color.bn_text, 800);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        side.addView(n);
        PUi.add(side, PUi.small(c, "Background"), 8);
        GridLayout sw = new GridLayout(c);
        sw.setColumnCount(4);
        for (int k = 0; k < UserPictures.BACKGROUNDS.length; k++) {
            int kk = k;
            View s = new View(c);
            boolean on = k == pick.background;
            s.setBackground(PUi.ripple(c, PUi.round(0xff000000 | UserPictures.BACKGROUNDS[k], PUi.dp(c, 11), PUi.col(c, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(c, on ? 2 : 1))));
            s.setMinimumHeight(PUi.dp(c, 30));
            s.setContentDescription(UserPictures.BACKGROUND_NAMES[k]);
            s.setClickable(true);
            s.setOnClickListener(Feel.row(v -> { pick = pick.withBackground(kk); recolor(); bottom.refresh(); }));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = PUi.dp(c, 30);   // a plain view would take all the height it is offered
            int m = PUi.dp(c, 3);
            lp.setMargins(m, m, m, m);
            sw.addView(s, lp);
        }
        side.addView(sw);
        card.addView(side, PUi.weight());
        col.addView(card, PUi.fill());
        View use = PUi.button(c, "Use this picture", PUi.Kind.PRIMARY, Feel.heavy(v -> use()));
        use.setEnabled(!pick.none());
        PUi.add(col, use, 12);
        View fresh = PUi.button(c, "New Mii", PUi.Kind.TONAL, Feel.button(v -> MiiEditor.edit(this, EDIT, null)));
        if (pick.mii() != null) {
            View edit = PUi.button(c, "Edit this Mii", PUi.Kind.TONAL, Feel.button(v -> MiiEditor.edit(this, EDIT, pick.mii())));
            PUi.add(col, PUi.pair(c, edit, fresh), 8);
        } else PUi.add(col, fresh, 8);
        return col;
    }

    private String shownName() {
        String mii = pick.mii();
        if (mii == null) return name;
        for (MiiStore.Mii m : MiiStore.load(getFilesDir())) if (MiiStore.hex(m.createId).equals(mii)) return m.name;
        return name;
    }

    private void use() {
        try { UserPictures.set(getFilesDir(), uid, pick); }
        catch (IOException e) { Toast.makeText(this, "The picture was not saved: the app could not write its files. Try again.", Toast.LENGTH_LONG).show(); return; }
        UserImages.write(this, uid, pick);   // the picture a game asks the console for
        Feel.done(this);
        setResult(RESULT_OK);
        finish();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        String id = MiiEditor.result(result, data);
        if (request == EDIT && id != null) { pick = UserPictures.mii(id, pick.background); tab = 0; }   // the editor comes back with its Mii picked (build() runs in onStart)
    }
}
