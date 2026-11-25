package dev.peanuts.mii;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import dev.nxnative.android.R;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.PUi;
import dev.peanuts.ui.UiPort;
import dev.peanuts.users.Bottom;

/**
 * The Mii editor, a part any page opens for a result: {@link #edit} with a Mii's create ID (or null for a new one), and {@link #result} gives the create ID of
 * the Mii that was saved, or null when the player went back without saving. The profile picture opens it ("New Mii", "Edit this Mii"), the Mii list does, and
 * later a game asking for a Mii can. The top screen shows the live Mii (drawn from the firmware's parts, MiiView) and the parts of the current tab as pictures
 * to pick with the D-pad; the bottom screen holds the colours and places the part by touch. Random and Undo are in the header, Done saves.
 */
public final class MiiEditor extends Activity {
    public static final String EXTRA_MII = "dev.peanuts.mii";

    /** Opens the editor on Mii id (its create ID, 32 hex digits), or on a new Mii when id is null */
    public static void edit(Activity a, int request, String id) { a.startActivityForResult(new Intent(a, MiiEditor.class).putExtra(EXTRA_MII, id), request); }

    /** The saved Mii's create ID from the editor's result, or null when it was left without saving */
    public static String result(int resultCode, Intent data) { return resultCode == RESULT_OK && data != null ? data.getStringExtra(EXTRA_MII) : null; }

    // ---- What each tab edits ----

    /** A group of tiles: the field it picks, its count, its label (null when the tab has one group) and how big the face is in its pictures */
    static final class Group {
        final String field, label; final int count; final float zoom;
        Group(String field, int count, String label, float zoom) { this.field = field; this.count = count; this.label = label; this.zoom = zoom; }
    }

    /** A tab: its groups; the colour it shows below (a field of the common colours, the skin colours or the favourite colours); the fields placed by touch
     *  (across, down) and the ones changed by steps (size, shape, turn) */
    static final class Tab {
        final String name; final Group[] groups; final String color; final int palette; final String across, down; final String[][] steps;
        Tab(String name, Group[] groups, String color, int palette, String across, String down, String[][] steps) {
            this.name = name; this.groups = groups; this.color = color; this.palette = palette; this.across = across; this.down = down; this.steps = steps;
        }
    }

    static final int COMMON = 0, SKIN = 1, FAVORITE = 2;
    static final Tab[] TABS = {
        new Tab("Hair", new Group[] {new Group("hair_type", 132, null, 0.95f)}, "hair_color", COMMON, null, null, new String[0][]),
        new Tab("Eyes", new Group[] {new Group("eye_type", 60, null, 2.1f)}, "eye_color", COMMON, "eye_x", "eye_y",
                new String[][] {{"eye_scale", "Size", "Smaller", "Bigger"}, {"eye_aspect", "Shape", "Rounder", "Taller"}, {"eye_rotate", "Turn", "Less", "More"}}),
        new Tab("Mouth", new Group[] {new Group("mouth_type", 36, null, 2.1f)}, "mouth_color", COMMON, null, "mouth_y",
                new String[][] {{"mouth_scale", "Size", "Smaller", "Bigger"}, {"mouth_aspect", "Shape", "Flatter", "Taller"}}),
        new Tab("Brows", new Group[] {new Group("eyebrow_type", 24, null, 2.1f)}, "eyebrow_color", COMMON, "eyebrow_x", "eyebrow_y",
                new String[][] {{"eyebrow_scale", "Size", "Smaller", "Bigger"}, {"eyebrow_aspect", "Shape", "Flatter", "Taller"}, {"eyebrow_rotate", "Turn", "Less", "More"}}),
        new Tab("Nose", new Group[] {new Group("nose_type", 18, null, 2.1f)}, null, COMMON, null, "nose_y", new String[][] {{"nose_scale", "Size", "Smaller", "Bigger"}}),
        new Tab("Face", new Group[] {new Group("faceline_type", 12, "Shape", 1.35f), new Group("faceline_wrinkle", 12, "Wrinkles", 1.6f), new Group("faceline_make", 12, "Make-up", 1.6f)},
                "faceline_color", SKIN, null, null, new String[0][]),
        new Tab("Extras", new Group[] {new Group("glass_type", 20, "Glasses", 1.6f), new Group("beard_type", 6, "Beard", 1.4f), new Group("mustache_type", 6, "Mustache", 1.8f),
                new Group("mole_type", 2, "Mole", 1.8f)}, null, COMMON, null, null, new String[0][]),
        new Tab("Body", new Group[0], "favorite_color", FAVORITE, null, null, new String[0][]),
    };
    /** In Extras, what the picked group's colour, placement and steps are */
    private static final Map<String, String[]> EXTRA_FIELDS = new LinkedHashMap<>();
    static {
        EXTRA_FIELDS.put("glass_type", new String[] {"glass_color", null, "glass_y", "glass_scale"});
        EXTRA_FIELDS.put("beard_type", new String[] {"beard_color", null, null, null});
        EXTRA_FIELDS.put("mustache_type", new String[] {"beard_color", null, "mustache_y", "mustache_scale"});
        EXTRA_FIELDS.put("mole_type", new String[] {null, "mole_x", "mole_y", "mole_scale"});
    }

    // ---- State ----
    private List<MiiStore.Mii> all;
    private MiiStore.Mii m;
    private int index = -1;
    private byte[] original;
    private final ArrayDeque<Map<String, Integer>> undo = new ArrayDeque<>();
    private int tab;
    private String group;   // Face and Extras: the group last picked from
    private boolean more;   // all 100 colours shown, not only the classic ones
    private final Random rng = new Random();
    private final LruCache<String, Bitmap> tiles = new LruCache<>(48 * 1024 * 1024) { @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); } };

    private FrameLayout frame;
    private Bottom bottom;
    private MiiView preview;
    private LinearLayout tabPane;
    private View undoButton;
    private TextView countLine;
    private boolean wide, parts, sideBySide;   // wide: the bottom half beside the page; sideBySide: the Mii beside the tiles

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        wide = getResources().getConfiguration().screenWidthDp >= 600;
        // the Mii beside the tiles only where the page itself has the room (the Thor's top screen, a tablet on its side), else the Mii above them (a phone, a
        // tablet upright, whose page shares the width with the bottom half)
        sideBySide = getResources().getConfiguration().screenWidthDp - (wide && !dev.peanuts.users.Bottom.onSecondScreen(this) ? 380 : 0) >= 640;
        parts = MiiGl.available(this);
        all = MiiStore.load(getFilesDir());
        String id = getIntent().getStringExtra(EXTRA_MII);
        for (int i = 0; id != null && i < all.size(); i++) if (MiiStore.hex(all.get(i).createId).equals(id)) index = i;
        m = index >= 0 ? all.get(index).copy(all.get(index).name) : MiiStore.fromDefault(0, "Mii " + (all.size() + 1));
        if (index >= 0) keepId(m, all.get(index));
        original = MiiStore.charInfo(m);
        restore(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(PUi.col(this, R.color.bn_bg));
        frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(wide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT, wide ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1));
        FrameLayout inline = new FrameLayout(this);
        root.addView(inline, new LinearLayout.LayoutParams(wide ? PUi.dp(this, 380) : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        bottom = new Bottom(this, inline);
        build();
    }

    /** A copy keeps its create ID: the edited Mii stays the same Mii for games and pictures */
    private static void keepId(MiiStore.Mii copy, MiiStore.Mii from) { System.arraycopy(from.createId, 0, copy.createId, 0, 16); }

    // ---- Built again by Android ----
    // The device turned, dark mode switched or the app's process ended in the background: the edits, Undo and the tab come back as they were (MiiEditState)

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putByteArray("mii", MiiEditState.mii(m));
        out.putByteArray("original", original);
        out.putByteArray("undo", MiiEditState.undo(undo));
        out.putInt("tab", tab);
        out.putString("group", group);
        out.putBoolean("more", more);
    }

    private void restore(Bundle saved) {
        MiiStore.Mii kept = saved != null ? MiiEditState.mii(saved.getByteArray("mii")) : null;
        if (kept == null) return;
        m = kept;
        byte[] o = saved.getByteArray("original");
        if (o != null && o.length == MiiStore.CHARINFO) original = o;
        undo.clear();
        undo.addAll(MiiEditState.undo(saved.getByteArray("undo")));   // newest first, as push keeps them
        tab = Math.max(0, Math.min(TABS.length - 1, saved.getInt("tab")));
        group = saved.getString("group");
        more = saved.getBoolean("more");
    }

    @Override protected void onStart() { super.onStart(); bottom.start(); }
    @Override protected void onStop() { bottom.stop(); super.onStop(); }

    // ---- The top screen ----

    private void build() {
        frame.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int g = PUi.dp(this, 16);
        page.setPadding(g, PUi.dp(this, 12), g, PUi.dp(this, 8));
        frame.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout head = PUi.header(this, PUi.back(this, v -> leave()), index >= 0 ? "Edit " + m.name : "New Mii", null);
        // Random, Undo and Done as icons (a die, an arrow turning back, a check), so the title keeps its room on a phone; the controller's Y and X do the same
        head.addView(headIcon("random", "Random", PUi.Kind.TONAL, Feel.button(v -> random())), square(8));
        undoButton = headIcon("undo", "Undo", PUi.Kind.TONAL, Feel.button(v -> undo()));
        undoState();
        head.addView(undoButton, square(8));
        head.addView(headIcon("check", "Done", PUi.Kind.PRIMARY, Feel.heavy(v -> done())), square(8));
        page.addView(head);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(sideBySide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(PUi.round(PUi.col(this, R.color.bn_tint), PUi.dp(this, 18), 0, 0));
        stage.setClipToOutline(true);
        if (parts) {
            preview = new MiiView(this);
            stage.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            preview.show(m);
        } else {
            TextView t = PUi.small(this, "Your firmware's Mii parts are missing, so there is no picture. Add your firmware under Console in Settings to see your Mii here.");
            t.setGravity(Gravity.CENTER);
            int p = PUi.dp(this, 16);
            t.setPadding(p, p, p, p);
            stage.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        body.addView(stage, sideBySide ? new LinearLayout.LayoutParams(PUi.dp(this, 250), ViewGroup.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(this, 240)));
        tabPane = new LinearLayout(this);
        tabPane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = sideBySide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        if (sideBySide) lp.leftMargin = PUi.dp(this, 12); else lp.topMargin = PUi.dp(this, 8);
        body.addView(tabPane, lp);
        tabPane.addView(tabs());
        tabContent();
        Feel.page(page, 1);
    }

    /** The tabs, the current one marked; picking one draws them again with the new one marked */
    private View tabs() {
        String[] names = new String[TABS.length];
        for (int i = 0; i < names.length; i++) names[i] = TABS[i].name;
        return UiPort.tabs(this, names, tab, k -> {
            tab = k; group = null; more = false;
            tabPane.removeViewAt(0);
            tabPane.addView(tabs(), 0);
            tabContent();
        });
    }

    /** A square icon button of the page's header with a Peanuts icon (dark on the yellow of Done) */
    private View headIcon(String icon, String label, PUi.Kind kind, View.OnClickListener l) {
        android.widget.ImageView b = PUi.iconButton(this, R.drawable.ic_more, label, kind, l);
        android.graphics.drawable.Drawable d = dev.peanuts.ui.Icons.get(this, icon, kind == PUi.Kind.PRIMARY ? dev.peanuts.ui.Icons.Ink.ON_BANANA : dev.peanuts.ui.Icons.Ink.NORMAL);
        if (d != null) b.setImageDrawable(d);
        b.setTooltipText(label);
        return b;
    }

    /** Undo shows faded and does nothing while there is nothing to take back */
    private void undoState() {
        if (undoButton == null) return;
        undoButton.setEnabled(!undo.isEmpty());
        undoButton.setAlpha(undo.isEmpty() ? 0.4f : 1f);
    }

    private LinearLayout.LayoutParams square(int gapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(PUi.dp(this, 44), PUi.dp(this, 44));
        lp.leftMargin = PUi.dp(this, gapDp);
        return lp;
    }

    /** The current tab's tiles on the top screen and its tools on the bottom one */
    private void tabContent() {
        while (tabPane.getChildCount() > 1) tabPane.removeViewAt(1);
        Tab t = TABS[tab];
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, PUi.dp(this, 8), 0, PUi.dp(this, 8));
        sv.addView(col);
        View first = null;
        if (t.groups.length == 0) {   // Body: the favourite colour as big swatches
            col.addView(PUi.section(this, "Favourite colour"));
            GridLayout grid = grid(6);
            for (int k = 0; k < 12; k++) {
                int kk = k;
                View s = swatch(this, 0xff000000 | MiiLook.favorite(k), m.get("favorite_color") == k, 44, v -> change("favorite_color", kk, true));
                GridLayout.LayoutParams lp = cell(grid.getColumnCount());
                lp.height = PUi.dp(this, 44);
                grid.addView(s, lp);
                if (first == null || m.get("favorite_color") == k) first = s;
            }
            col.addView(grid);
        }
        for (Group g : t.groups) {
            if (g.label != null) col.addView(PUi.section(this, g.label), sectionLp());
            GridLayout grid = grid(4);
            for (int k = 0; k < g.count; k++) {
                View tile = tile(g, k);
                grid.addView(tile, cell(4));
                if (m.get(g.field) == k && (group == null ? g == t.groups[0] : g.field.equals(group))) first = tile;
            }
            col.addView(grid);
        }
        tabPane.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        countLine = PUi.small(this, "");
        tabPane.addView(countLine);
        count();
        if (first != null) { View f = first; f.post(() -> { f.requestFocus(); sv.requestChildFocus(f, f); }); }
        bottom.set(this::tools);
    }

    private LinearLayout.LayoutParams sectionLp() { LinearLayout.LayoutParams lp = PUi.fill(); lp.topMargin = PUi.dp(this, 8); return lp; }

    private GridLayout grid(int cols) {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(cols);
        g.setUseDefaultMargins(false);
        return g;
    }

    private GridLayout.LayoutParams cell(int cols) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        int h = PUi.dp(this, 4);
        lp.setMargins(h, h, h, h);
        return lp;
    }

    private void count() {
        Tab t = TABS[tab];
        if (countLine == null) return;
        if (t.groups.length == 0) { countLine.setText(""); return; }
        Group g = currentGroup();
        String what = g.label != null ? g.label : t.name;
        countLine.setText(what + " " + (m.get(g.field) + 1) + " of " + g.count);
    }

    private Group currentGroup() {
        Tab t = TABS[tab];
        if (group != null) for (Group g : t.groups) if (g.field.equals(group)) return g;
        return t.groups[0];
    }

    /**
     * Marks a tile picked or not: the ring is the tile's foreground, drawn over its picture (which is cut to the tile's round corners), so a hair or a shirt that
     * reaches the tile's edge does not cover it
     */
    private static void markTile(FrameLayout f, boolean on) {
        Context c = f.getContext();
        f.setBackground(PUi.round(PUi.col(c, R.color.bn_card), PUi.dp(c, 14), PUi.col(c, R.color.bn_card_stroke), PUi.dp(c, 1)));
        f.setForeground(on ? PUi.round(0, PUi.dp(c, 14), PUi.col(c, R.color.bn_banana), PUi.dp(c, 2)) : null);
    }

    /** A part's tile: this Mii with that part, close on the face for small parts; its number while the firmware's parts are missing */
    private View tile(Group g, int k) {
        FrameLayout f = new FrameLayout(this);
        f.setMinimumHeight(PUi.dp(this, 62));
        markTile(f, m.get(g.field) == k);
        f.setClipToOutline(true);
        f.setFocusable(true);
        f.setClickable(true);
        f.setContentDescription((g.label != null ? g.label : TABS[tab].name) + " " + (k + 1));
        f.setOnClickListener(Feel.row(v -> { group = g.field; change(g.field, k, true); }));
        f.setTag(g.field + ":" + k);
        if (!parts) {
            TextView n = PUi.text(this, String.valueOf(k + 1), 15, R.color.bn_text_2, 600);
            n.setGravity(Gravity.CENTER);
            f.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(this, 62)));
            return f;
        }
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(this, 62)));
        MiiStore.Mii v = m.copy(m.name);
        v.set(g.field, k);
        if (g.field.equals("mole_type") && k == 1) v.set("mole_scale", Math.max(4, v.get("mole_scale")));
        String key = g.field + ":" + k + ":" + java.util.Arrays.hashCode(MiiStore.charInfo(v));
        Bitmap b = tiles.get(key);
        if (b != null) img.setImageBitmap(b);
        else MiiGl.get(this).picture(v, PUi.dp(this, 96), 0, g.zoom, bmp -> {
            if (bmp == null) return;
            tiles.put(key, bmp);
            img.setImageBitmap(bmp);
            Feel.appeared(img);
        });
        return f;
    }

    /** Marks the picked tile of each group again (after a change), without drawing the tiles again */
    private void retile() {
        View sv = tabPane.getChildAt(1);
        if (!(sv instanceof ScrollView)) return;
        mark((ViewGroup) ((ScrollView) sv).getChildAt(0));
    }

    private void mark(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            Object tag = v.getTag();
            if (tag instanceof String && ((String) tag).contains(":")) {
                String[] p = ((String) tag).split(":");
                markTile((FrameLayout) v, m.get(p[0]) == Integer.parseInt(p[1]));
            } else if (v instanceof ViewGroup && !(v instanceof FrameLayout)) mark((ViewGroup) v);
        }
    }

    // ---- Changes, Undo, Random ----

    private Map<String, Integer> snapshot() { return new LinkedHashMap<>(m.v); }

    /** One change: kept for Undo (a drag keeps one for its whole move: record false after its first step) */
    private void change(String field, int value, boolean record) {
        if (m.get(field) == value) { if (record) refresh(false); return; }
        if (record) undo.push(snapshot());
        m.set(field, value);
        refresh(true);
    }

    private void refresh(boolean changed) {
        if (preview != null) preview.show(m);
        undoState();
        retile();
        count();
        if (changed) bottom.refresh();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        m.v.clear();
        m.v.putAll(undo.pop());
        tabContent();
        refresh(true);
    }

    private void random() {
        undo.push(snapshot());
        MiiStore.Mii r = MiiStore.random(m.name, rng);
        m.v.clear();
        m.v.putAll(r.v);
        tabContent();
        refresh(true);
    }

    // ---- Done and Back ----

    private boolean changed() { return !java.util.Arrays.equals(original, MiiStore.charInfo(m)) || index < 0; }

    private void done() {
        if (index < 0 && all.size() >= MiiStore.MAX_MIIS) { Toast.makeText(this, "There are already " + MiiStore.MAX_MIIS + " Miis. Delete one first.", Toast.LENGTH_LONG).show(); return; }
        if (index >= 0) all.set(index, m); else all.add(m);
        try { MiiStore.save(getFilesDir(), all); }
        catch (IOException e) { Toast.makeText(this, "The Mii was not saved: the app could not write its files. Try again.", Toast.LENGTH_LONG).show(); return; }
        Feel.done(this);
        if (index < 0) dev.peanuts.ui.Cracked.mii(this);   // Peanuts cracked: the first Mii of the player's own
        if (index >= 0) dev.peanuts.users.UserImages.miiChanged(this, MiiStore.hex(m.createId));   // a user whose picture it is gets the new face, in games too
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_MII, MiiStore.hex(m.createId)));
        finish();
    }

    private void leave() {
        if (!changed() || index < 0 && undo.isEmpty()) { setResult(RESULT_CANCELED); finish(); return; }
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Leave without saving?").setMessage("The changes to this Mii are not kept.")
                .setPositiveButton("Leave", (x, w) -> { setResult(RESULT_CANCELED); finish(); }).setNegativeButton("Keep editing", null).create();
        Feel.dialog(d);
        d.show();
    }

    @SuppressWarnings("deprecation")   // onBackPressed: the editor asks before its changes go
    @Override public void onBackPressed() { leave(); }

    // ---- Controller ----

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {   // the shoulder buttons move between tabs
            int next = (tab + (code == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : TABS.length - 1)) % TABS.length;
            tab = next; group = null;
            build();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y) { random(); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_X) { undo(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (preview != null && preview.stick(e)) return true;
        return super.onGenericMotionEvent(e);
    }

    // ---- The bottom screen: colour, place, size, the Body's values ----

    private View tools(Context c) {
        ScrollView sv = new ScrollView(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(c, 16);
        col.setPadding(p, p, p, p);
        sv.addView(col);
        Tab t = TABS[tab];
        String color = t.color, across = t.across, down = t.down;
        String[][] steps = t.steps;
        String title = t.name;
        if (t.name.equals("Extras")) {
            Group g = currentGroup();
            String[] x = EXTRA_FIELDS.get(g.field);
            color = x[0]; across = x[1]; down = x[2];
            steps = x[3] != null ? new String[][] {{x[3], "Size", "Smaller", "Bigger"}} : new String[0][];
            title = g.label;
        }
        LinearLayout head = PUi.row(c);
        head.addView(PUi.text(c, title, 18, R.color.bn_text, 700), PUi.weight());
        String[] resetFields = fields(across, down, steps);
        if (resetFields.length > 0) head.addView(PUi.button(c, "Reset", PUi.Kind.QUIET, Feel.button(v -> reset(resetFields))));
        col.addView(head);
        if (t.name.equals("Body")) body(c, col);
        if (color != null && !t.name.equals("Body")) {   // the body's colour, the favourite colour, is the top screen's tiles already
            PUi.add(col, PUi.section(c, "Colour"), 10);
            col.addView(palette(c, color, t.palette));
        }
        if (t.name.equals("Hair")) {
            LinearLayout card = PUi.add(col, PUi.listCard(c), 12);
            PUi.Setting flip = new PUi.Setting(c, "", "Part on the other side", null).toggle(c, m.get("hair_flip") != 0, (b, on) -> { Feel.toggled(b, on); change("hair_flip", on ? 1 : 0, true); });
            flip.view.setOnClickListener(v -> flip.toggle.toggle());
            dev.peanuts.users.UserFace.rowIcon(c, flip.view, "flip");
            card.addView(flip.view);
        }
        if (across != null || down != null) {
            PUi.add(col, PUi.section(c, "Place"), 12);
            col.addView(pad(c, across, down), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(c, 150)));
        }
        for (String[] s : steps) col.addView(stepper(c, s[0], s[1], s[2], s[3]));
        if (parts && (across != null || down != null)) {
            TextView hint = PUi.small(c, "Pick the style above with the D-pad. Place it here by touch.");
            PUi.add(col, hint, 12);
        }
        return sv;
    }

    private static String[] fields(String across, String down, String[][] steps) {
        java.util.List<String> f = new java.util.ArrayList<>();
        if (across != null) f.add(across);
        if (down != null) f.add(down);
        for (String[] s : steps) f.add(s[0]);
        return f.toArray(new String[0]);
    }

    /** The fields back to a default Mii's values (the first default's) */
    private void reset(String[] fields) {
        MiiStore.Mii d = MiiStore.fromDefault(0, "x");
        undo.push(snapshot());
        for (String f : fields) m.set(f, d.get(f));
        refresh(true);
    }

    /** The classic colours of a common-colour field, the ones the console's editor shows first (hair, eyebrows and beard; eyes; mouth; glasses) */
    static int[] classic(String field) {
        switch (field) {
            case "eye_color": return new int[] {8, 9, 10, 11, 12, 13};
            case "mouth_color": return new int[] {19, 20, 21, 22, 23};
            case "glass_color": return new int[] {8, 14, 15, 16, 17, 18};
            default: return new int[] {8, 1, 2, 3, 4, 5, 6, 7};
        }
    }

    /** The colours: for the common colours the classic few and More colours for all 100; else the 10 skin colours or the 12 favourite colours. The current one ringed */
    private View palette(Context c, String field, int palette) {
        if (palette == COMMON) {
            int[] cl = classic(field);
            boolean inClassic = false;
            for (int k : cl) inClassic |= m.get(field) == k;
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(grid(c, field, cl, 8, 40));
            if (more || !inClassic) {
                int[] all = new int[100];
                for (int k = 0; k < 100; k++) all[k] = k;
                PUi.add(col, grid(c, field, all, 10, 28), 8);
            } else col.addView(PUi.button(c, "More colours", PUi.Kind.QUIET, Feel.button(v -> { more = true; bottom.refresh(); })), PUi.wrap());
            return col;
        }
        int[] all = new int[palette == SKIN ? 10 : 12];
        for (int k = 0; k < all.length; k++) all[k] = k;
        return grid(c, field, all, palette == SKIN ? 5 : 6, 40);
    }

    private View grid(Context c, String field, int[] which, int cols, int heightDp) {
        Tab t = TABS[tab];
        int palette = t.name.equals("Body") ? FAVORITE : field.equals("faceline_color") ? SKIN : COMMON;
        GridLayout g = new GridLayout(c);
        g.setColumnCount(cols);
        for (int k : which) {
            int kk = k, rgb = palette == COMMON ? MiiLook.common(k) : palette == SKIN ? MiiLook.skin(k) : MiiLook.favorite(k);
            View s = swatch(c, 0xff000000 | rgb, m.get(field) == k, heightDp, v -> change(field, kk, true));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            lp.width = 0;
            lp.height = PUi.dp(c, heightDp);   // a plain view would take all the height it is offered
            int h = PUi.dp(c, 3);
            lp.setMargins(h, h, h, h);
            g.addView(s, lp);
        }
        return g;
    }

    private static View swatch(Context c, int color, boolean on, int heightDp, View.OnClickListener l) {
        View s = new View(c);
        s.setMinimumHeight(PUi.dp(c, heightDp));
        s.setBackground(PUi.ripple(c, PUi.round(color, PUi.dp(c, 11), PUi.col(c, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(c, on ? 3 : 1))));
        s.setFocusable(true);
        s.setClickable(true);
        s.setOnClickListener(Feel.row(l));
        return s;
    }

    /** A row: label, the lower step, the value, the higher step (the console's range keeps it in bounds) */
    private View stepper(Context c, String field, String label, String less, String more) {
        MiiStore.Field fd = MiiStore.field(field);
        LinearLayout r = PUi.row(c);
        r.setPadding(0, PUi.dp(c, 8), 0, 0);
        TextView l = PUi.text(c, label, 14, R.color.bn_text, 600);
        r.addView(l, new LinearLayout.LayoutParams(PUi.dp(c, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView value = PUi.text(c, (m.get(field) - fd.min + 1) + " of " + (fd.max - fd.min + 1), 14, R.color.bn_text_2, 700);
        value.setGravity(Gravity.CENTER);
        r.addView(PUi.button(c, less, PUi.Kind.TONAL, Feel.button(v -> { change(field, m.get(field) - 1, true); value.setText((m.get(field) - fd.min + 1) + " of " + (fd.max - fd.min + 1)); })), PUi.weight());
        r.addView(value, new LinearLayout.LayoutParams(PUi.dp(c, 64), ViewGroup.LayoutParams.WRAP_CONTENT));
        r.addView(PUi.button(c, more, PUi.Kind.TONAL, Feel.button(v -> { change(field, m.get(field) + 1, true); value.setText((m.get(field) - fd.min + 1) + " of " + (fd.max - fd.min + 1)); })), PUi.weight());
        return r;
    }

    /**
     * The placing pad: dragging moves the part across (spacing or across the face) and down, a step for every 10 dp, as the live Mii above shows. A whole drag
     * is one Undo.
     */
    @SuppressLint("ClickableViewAccessibility")
    private View pad(Context c, String across, String down) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(PUi.round(PUi.col(c, R.color.bn_tint), PUi.dp(c, 16), PUi.col(c, R.color.bn_card_stroke), PUi.dp(c, 1)));
        TextView t = PUi.small(c, across != null && down != null ? "Drag up and down to move it, sideways to space it" : "Drag up and down to move it");
        t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        float step = PUi.dp(c, 10);
        final float[] start = new float[4];
        f.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getX(); start[1] = e.getY();
                    start[2] = across != null ? m.get(across) : 0; start[3] = down != null ? m.get(down) : 0;
                    undo.push(snapshot());
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (across != null) { int k = Math.round(start[2] + (e.getX() - start[0]) / step); if (k != m.get(across)) { m.set(across, k); refresh(false); } }
                    if (down != null) { int k = Math.round(start[3] + (e.getY() - start[1]) / step); if (k != m.get(down)) { m.set(down, k); refresh(false); } }
                    return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    if (!undo.isEmpty() && undo.peek().equals(m.v)) undo.pop();   // a touch that moved nothing keeps no Undo
                    refresh(false);
                    return true;
                default: return false;
            }
        });
        return f;
    }

    /** Body: name, gender, height and build */
    private void body(Context c, LinearLayout col) {
        LinearLayout card = PUi.add(col, PUi.listCard(c), 10);
        View name = new PUi.Setting(c, "", "Name", Feel.row(v -> rename())).value(m.name).chevron(c).view;
        dev.peanuts.users.UserFace.rowIcon(c, name, "mii");
        card.addView(name);
        PUi.add(col, UiPort.segmented(c, new String[] {"Male", "Female"}, m.get("gender"), k -> change("gender", k, true)), 10);
        for (String[] s : new String[][] {{"height", "Height"}, {"build", "Build"}}) {
            PUi.add(col, PUi.section(c, s[1]), 10);
            SeekBar bar = new SeekBar(c);
            bar.setMax(127);
            bar.setProgress(m.get(s[0]));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int v, boolean user) { if (user) { m.set(s[0], v); refresh(false); } }
                @Override public void onStartTrackingTouch(SeekBar b) { undo.push(snapshot()); }
                @Override public void onStopTrackingTouch(SeekBar b) { refresh(false); }
            });
            col.addView(bar);
        }
    }

    private void rename() {
        EditText e = new EditText(this);
        e.setText(m.name);
        e.setSingleLine(true);
        e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(MiiStore.NAME_MAX)});
        AlertDialog d = PUi.sheet(this, "Name (up to " + MiiStore.NAME_MAX + " characters)", e)
                .setPositiveButton("OK", Feel.confirm((x, w) -> { m.name = MiiStore.cleanName(e.getText().toString()); bottom.refresh(); }))
                .setNegativeButton("Cancel", null).create();
        Feel.dialog(d);
        d.show();
    }
}
