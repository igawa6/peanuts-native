package dev.peanuts.mods;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.nxnative.android.CheatsBridge;
import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;

/**
 * The in-game panel's Mods tab (SPEC-mod-menu.md Package C 1-3, 5-7), in the panel's own look (its dark sheet, the
 * yellow of its selected tab and focus frame, SidePanel's headings and notes as PanelPolish styles them). dev.nxnative.android.ModsPanel puts it between
 * Controls and General and hands it the panel's box.
 * <ul>
 * <li>The cheats the game started with (the sources that were on: CheatsBridge.merged), grouped as in their files. A row: the name, a chip (Running, Hold ZL, Not
 *     possible here, Next start, or the kind), a switch that acts at once (CheatsBridge.set: the host's dm_cheat_set) and is kept for the next start. The chips
 *     follow the host while the tab shows (CheatsBridge.status, twice a second): a cheat the host refused flips back and says why.</li>
 * <li>All off at the top (X), a search field from 15 cheats on, Y on a row: its details.</li>
 * <li>The game's mods: a switch each, "Next start" or "Needs a build" (a code mod; its build is on the game's page). A change offers Restart the game, with a
 *     line to save in the game first.</li>
 * </ul>
 */
public final class ModsTab {
    private ModsTab() {}

    /** What the panel does for the tab (ModsPanel) */
    public interface Host {
        void close();
        /** The panel's look over views added after the tab was built */
        void style(View v);
    }

    private static final int YELLOW = 0xFFFFD54F, ROW = 0xFF2D2E31, CHIP = 0xFF3A3B3E, TEXT = 0xFFE8EAED, QUIET = 0xFFB0B3B8, BAD_TEXT = 0xFFFF9670, BAD = 0xFF3D2016;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    static final int SEARCH_FROM = 15;

    /** The tab on screen: its rows, kept for the status poll and the keys; null when another tab shows */
    private static Shown shown;
    private static boolean modsChanged;   // a mod was switched in this run: Restart the game shows
    private static int confirmKey = KeyEvent.KEYCODE_BUTTON_A, backKey = KeyEvent.KEYCODE_BUTTON_B;

    /** One cheat's row */
    static final class Bind {
        CheatFile.Cheat cheat;
        String key, source, group;
        View row;
        TextView chip, desc;
        Switch sw;
        boolean inHost = true;   // the host has the key (else it is listed for the next start)
        String state = "off", flags = "", reason = "";
        Field field;             // a Value cheat's number (Part F), else null
        TextView mark;           // beside a number set in the tab: the file's own number
        long number;             // the number it writes now
        boolean later;           // the host did not read the new number again: it is in the game from the next start
        Runnable apply;          // the number to the files and the host, a moment after the last step
    }

    static final class Shown {
        Activity a;
        Host host;
        String titleId;
        LinearLayout box, list;
        View restart;
        final List<Bind> binds = new ArrayList<>();
        boolean updating, polled;
        final Runnable poll = new Runnable() { @Override public void run() { ModsTab.poll(Shown.this); } };
        View firstMod;   // the first mod's row (the controller's first focus when there is no cheat)
        File folder;                                   // the game's cheats folder (the merged file, values.tsv)
        String build;                                  // the merged file's build: <folder>/<build>.txt
        Map<String, CheatValues.Kept> kept = new java.util.HashMap<>();
        long lastStep;                                 // a held left / right: the steps grow (1, 10, 100, ...)
        int run;
    }

    // ---- Whether there is a tab, and which tab shows last ----

    /** Whether the panel has a Mods tab for this game: Settings › Controls › Mods tab on, the game's Show in the game on, and cheats or mods to show */
    public static boolean wanted(Context c, String titleId, String gameDir, String packDir) {
        if (!PeanutsPrefs.modsTabAll(c) || !PeanutsPrefs.modsTab(c, titleId)) return false;
        try {
            if (!model(c, titleId, gameDir, packDir).isEmpty()) return true;
            return !mods(c, titleId, gameDir, packDir).isEmpty();
        } catch (RuntimeException e) {
            android.util.Log.w("peanuts", "mods tab: " + e);
            return false;
        }
    }

    public static String lastTab(Context c) { return PeanutsPrefs.panelTab(c); }
    public static void lastTab(Context c, String name) { PeanutsPrefs.panelTab(c, name); }

    /** Another tab shows: the poll stops */
    public static void left() {
        Shown s = shown;
        shown = null;
        if (s != null) s.box.removeCallbacks(s.poll);
    }

    // ---- The model: the cheats of the sources that were on, by group ----

    /** One group of rows: its heading ("" none) and its cheats */
    static final class Group {
        final String heading;
        final List<Bind> binds = new ArrayList<>();
        Group(String heading) { this.heading = heading; }
    }

    static List<Group> model(Context c, String titleId, String gameDir, String packDir) {
        List<Group> out = new ArrayList<>();
        Set<String> builds = CheatsBridge.builds(titleId, packDir);
        if (builds.isEmpty()) return out;
        String build = builds.iterator().next();
        CheatSources.Merged m = CheatsBridge.merged(c, titleId, gameDir, packDir, build, CheatsBridge.textEnd(packDir));
        List<CheatSources.Block> blocks = new ArrayList<>();
        for (CheatSources.Row r : m.rows) if (r.block.enabled && !blocks.contains(r.block)) blocks.add(r.block);
        Map<String, Group> byHeading = new LinkedHashMap<>();
        for (CheatSources.Block b : blocks) {
            for (CheatFile.Cheat ch : b.parsed.cheats) {
                String heading = blocks.size() > 1 ? (ch.group.isEmpty() ? b.name : b.name + ": " + ch.group) : ch.group;
                Group g = byHeading.computeIfAbsent(heading, Group::new);
                Bind x = new Bind();
                x.cheat = ch; x.key = m.key(b.id, ch.key); x.source = b.name; x.group = ch.group;
                g.binds.add(x);
            }
        }
        out.addAll(byHeading.values());
        return out;
    }

    static List<ModStore.Mod> mods(Context c, String titleId, String gameDir, String packDir) {
        File pack = packDir == null || packDir.isEmpty() ? null : new File(packDir);
        File game = gameDir == null || gameDir.isEmpty() ? null : new File(gameDir);
        if (pack == null || !new File(pack, "pack.tsv").isFile()) return new ArrayList<>();
        return ModRuntime.mods(ModRuntime.home(c, titleId, game, pack.getParentFile()), titleId);
    }

    private static File modsRoot(Context c, String titleId, String gameDir, String packDir) {
        File pack = new File(packDir), game = gameDir == null || gameDir.isEmpty() ? null : new File(gameDir);
        return ModRuntime.modsRoot(ModRuntime.home(c, titleId, game, pack.getParentFile()));
    }

    // ---- The tab ----

    /** Builds the tab into the panel's box (ModsPanel.build) */
    public static void fill(Activity a, LinearLayout box, Host host) {
        left();
        Shown s = new Shown();
        s.a = a; s.host = host; s.box = box;
        s.titleId = String.valueOf(a.getIntent().getStringExtra("title_id"));
        String gameDir = a.getIntent().getStringExtra("game"), packDir = a.getIntent().getStringExtra("pack");
        List<Group> groups;
        List<ModStore.Mod> mods;
        try {
            groups = model(a, s.titleId, gameDir, packDir);
            mods = mods(a, s.titleId, gameDir, packDir);
        } catch (RuntimeException e) {
            android.util.Log.w("peanuts", "mods tab: " + e);
            groups = new ArrayList<>(); mods = new ArrayList<>();
        }
        Set<String> on = CheatsBridge.on(a, s.titleId);
        s.folder = CheatsBridge.folder(a, s.titleId);
        Set<String> builds = CheatsBridge.builds(s.titleId, packDir);
        s.build = builds.isEmpty() ? null : builds.iterator().next();
        s.kept = CheatValues.read(s.folder);
        int cheats = 0;
        for (Group g : groups) cheats += g.binds.size();

        // The top: search (from 15 cheats on) and All off
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = null;
        // A controller's first focus on the tab (SidePanel.focusFirst: the first focusable, which is up here) goes on to the first cheat or mod: A on the search
        // opened the keyboard, A on All off turned every cheat off. D-pad up still reaches them
        boolean[] passed = {false};
        View.OnFocusChangeListener toFirstRow = (v, has) -> {
            if (!has || v.isInTouchMode() || passed[0]) return;
            passed[0] = true;
            View first = !s.binds.isEmpty() ? s.binds.get(0).row : s.firstMod;
            if (first != null) first.requestFocus();
        };
        if (cheats >= SEARCH_FROM) {
            search = new EditText(a);
            search.setHint("Search " + cheats + " cheats");
            search.setSingleLine(true);
            search.setTextSize(14);
            search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);   // landscape (the Thor): the keyboard beside the list, not over it (as Settings' search)
            search.setOnEditorActionListener((v, id, ev) -> {   // the keyboard's search key: the keyboard goes, the list shows
                boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
                if (id != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
                InputMethodManager im = a.getSystemService(InputMethodManager.class);
                if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            });
            search.setOnFocusChangeListener(toFirstRow);
            top.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        } else top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        if (cheats > 0) {
            Button all = new Button(a);
            all.setText("All off");
            all.setOnClickListener(v -> allOff(s));
            all.setOnFocusChangeListener(toFirstRow);
            top.addView(all);
        }
        box.addView(top);

        // The cheats, by group
        s.list = new LinearLayout(a);
        s.list.setOrientation(LinearLayout.VERTICAL);
        box.addView(s.list);
        for (Group g : groups) {
            if (!g.heading.isEmpty()) s.list.addView(head(a, g.heading));
            for (Bind b : g.binds) {
                b.row = row(s, b, on.contains(b.key));
                s.list.addView(b.row);
                s.binds.add(b);
            }
        }
        if (cheats == 0) box.addView(note(a, "No cheats for this game: add them on the game's page, Mods & Cheats."));
        else box.addView(note(a, "A switch acts at once and is kept for the next start. Cheated values are stored when the game saves."));

        // The mods
        if (!mods.isEmpty()) {
            box.addView(head(a, "Mods"));
            for (ModStore.Mod m : mods) {
                View r = modRow(s, m, gameDir, packDir);
                if (s.firstMod == null) s.firstMod = r;
                box.addView(r);
            }
        }
        s.restart = restartBar(s);
        s.restart.setVisibility(modsChanged ? View.VISIBLE : View.GONE);
        box.addView(s.restart);
        box.addView(keys(a, cheats > 0));

        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence t, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence t, int st, int b, int c) {}
                @Override public void afterTextChanged(Editable e) { filter(s, e.toString()); }
            });
        }
        shown = s;
        poll(s);
    }

    /** One cheat: its name and chip over its line (shown while the row has the focus), and its switch */
    private static View row(Shown s, Bind b, boolean on) {
        Context c = s.a;
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(c);
        b.row = r;   // show(b) below dims it
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int ph = Math.round(10 * dp), pv = Math.round(6 * dp);
        r.setPadding(ph, pv, Math.round(6 * dp), pv);
        r.setMinimumHeight(Math.round(46 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ROW);
        bg.setCornerRadius(12 * dp);
        r.setBackground(bg);
        r.setFocusable(true);
        r.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(5 * dp);
        r.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(b.cheat.name);
        name.setTextSize(14);
        name.setTextColor(TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        b.chip = chip(c, "");
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = Math.round(6 * dp);
        line.addView(b.chip, cl);
        col.addView(line);
        b.desc = new TextView(c);
        b.desc.setTextSize(12);
        b.desc.setTextColor(Color.LTGRAY);
        b.desc.setVisibility(View.GONE);
        col.addView(b.desc);
        if (b.key != null && CheatValues.plain(b.cheat)) col.addView(valueBar(s, b));
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        b.sw = new Switch(c);
        b.sw.setFocusable(false);
        b.sw.setChecked(on);
        b.sw.setContentDescription(b.cheat.name);
        b.sw.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {YELLOW, 0xFFBDC1C6}));
        b.sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {0xAAFFD54F, 0xFF5F6368}));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Math.round(8 * dp);
        r.addView(b.sw, sl);
        if (!b.cheat.switchable() || b.key == null) { b.state = "unsupported"; b.reason = "unsupported:" + b.cheat.why; }
        b.sw.setOnCheckedChangeListener((v, x) -> { if (!s.updating) switched(s, b, x); });
        r.setOnClickListener(v -> { if (b.sw.isEnabled()) b.sw.toggle(); else details(s, b); });
        r.setOnLongClickListener(v -> { details(s, b); return true; });
        r.setOnFocusChangeListener((v, has) -> { b.desc.setText(line(b)); b.desc.setVisibility(has ? View.VISIBLE : View.GONE); });
        show(b);
        return r;
    }

    /** A switch moved: the host's switch at once (and kept for the next start); one the host does not take flips back and says why */
    private static void switched(Shown s, Bind b, boolean on) {
        if (!b.inHost) { CheatsBridge.on(s.a, s.titleId, b.key, on); show(b); return; }   // listed for the next start: kept only
        int r = CheatsBridge.set(b.key, on);
        if (r == 1) {
            CheatsBridge.on(s.a, s.titleId, b.key, on);
            b.state = on ? "on" : "off";
            b.reason = "";
        } else {
            s.updating = true;
            b.sw.setChecked(!on);
            s.updating = false;
            if (r == 0 && b.reason.isEmpty()) b.reason = "other:the host did not take it";
            if (r == -1) { b.inHost = false; CheatsBridge.on(s.a, s.titleId, b.key, on); s.updating = true; b.sw.setChecked(on); s.updating = false; }
        }
        show(b);
        s.box.removeCallbacks(s.poll);
        s.box.postDelayed(s.poll, 250);   // the host's own answer (a code write lands at its next pass)
    }

    /** X / All off: every cheat that is on, off */
    private static void allOff(Shown s) {
        for (Bind b : s.binds) if (b.sw.isChecked() && b.sw.isEnabled()) b.sw.setChecked(false);
    }

    private static void filter(Shown s, String q) {
        String want = q.trim().toLowerCase(Locale.ROOT);
        View heading = null;
        boolean any = false;
        for (int i = 0; i < s.list.getChildCount(); i++) {
            View v = s.list.getChildAt(i);
            Bind b = null;
            for (Bind x : s.binds) if (x.row == v) { b = x; break; }
            if (b == null) {   // a heading: shown when one of its rows is
                if (heading != null) heading.setVisibility(any ? View.VISIBLE : View.GONE);
                heading = v; any = false;
                continue;
            }
            boolean hit = want.isEmpty() || b.cheat.name.toLowerCase(Locale.ROOT).contains(want);
            v.setVisibility(hit ? View.VISIBLE : View.GONE);
            any |= hit;
        }
        if (heading != null) heading.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    // ---- The host's state ----

    /** The host's state of every cheat (CheatsBridge.status), the rows' chips and switches set from it; again in half a second while the tab shows */
    static void poll(Shown s) {
        if (shown != s) return;
        if (s.polled && !s.box.isShown()) return;   // the panel was closed: no more asking; its next opening builds the tab again and asks anew
        s.polled = true;
        Map<String, CheatStatus.Entry> st = CheatStatus.parse(CheatsBridge.status());
        s.updating = true;
        for (Bind b : s.binds) {
            if (b.key == null) { show(b); continue; }
            CheatStatus.Entry e = st == null ? null : st.get(b.key.toLowerCase(Locale.ROOT));
            b.inHost = e != null;
            if (e != null) {
                b.state = e.state; b.flags = e.flags; b.reason = e.reason;
                if (b.sw.isChecked() != e.on() && e.switchState()) b.sw.setChecked(e.on());
            }
            show(b);
        }
        s.updating = false;
        s.box.removeCallbacks(s.poll);
        if (shown == s) s.box.postDelayed(s.poll, 500);
    }

    /** The chip and the line of a row as its state is now */
    private static void show(Bind b) {
        boolean bad = bad(b);
        CheatStatus.Look look = CheatStatus.look(b.cheat, bad, b.inHost, b.sw.isChecked());
        int fg, bg;
        switch (look) {
            case NOT_POSSIBLE: fg = BAD_TEXT; bg = BAD; break;
            case HOLD: fg = 0xFF2A1D0C; bg = YELLOW; break;
            case RUNNING: fg = YELLOW; bg = CHIP; break;
            default: fg = QUIET; bg = CHIP;
        }
        b.chip.setText(CheatStatus.chip(look, b.cheat));
        b.chip.setTextColor(fg);
        GradientDrawable d = (GradientDrawable) b.chip.getBackground();
        d.setColor(bg);
        d.setStroke(look == CheatStatus.Look.RUNNING ? Math.max(1, Math.round(b.chip.getResources().getDisplayMetrics().density)) : 0, 0x66FFD54F);
        b.sw.setEnabled(!bad);
        if (b.field != null) { b.field.setEnabled(!bad); b.field.setFocusable(!bad); }
        b.row.setAlpha(bad ? 0.75f : 1f);
        b.desc.setText(b.field != null && b.field.isFocused() ? FIELD_LINE : line(b));
    }

    /** Refused or never run by the host, or given no key by the merge: no switch */
    private static boolean bad(Bind b) { return b.key == null || CheatStatus.bad(b.state); }

    /** What a row says while it has the focus (CheatStatus.line), a Value cheat with the number set now */
    static String line(Bind b) {
        if (b.field == null) return CheatStatus.line(b.cheat, bad(b), b.inHost, b.reason);
        String l = CheatStatus.line(b.cheat, bad(b), b.inHost, b.reason, b.number);
        if (bad(b)) return l;
        if (changed(b)) l += " The file's own number is " + b.cheat.value() + ".";
        return b.later ? l + " The new number is in the game from the next start." : l;
    }

    // ---- A Value cheat's number (Part F) ----

    static final String FIELD_LINE = "Left and right change the number, A types one.";
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * The number field: left / right step it, A (a click) types it. SidePanel moves the focus with the D-pad by asking the focused view's focusSearch; this one
     * steps its number and answers with itself, so the focus stays and a held direction repeats (SidePanel's own repeat)
     */
    static final class Field extends TextView {
        Runnable left, right;

        Field(Context c) {
            super(c);
            setDefaultFocusHighlightEnabled(false);   // SidePanel's frame shows the focus; Android's grey square would sit over the round field
        }

        /** SidePanel's focus frame, round as the field is */
        @Override public void setForeground(android.graphics.drawable.Drawable d) {
            if (d instanceof GradientDrawable) ((GradientDrawable) d).setCornerRadius(999);
            super.setForeground(d);
        }

        @Override public View focusSearch(int direction) {
            if (direction == View.FOCUS_LEFT && left != null && isEnabled()) { left.run(); return this; }
            if (direction == View.FOCUS_RIGHT && right != null && isEnabled()) { right.run(); return this; }
            return super.focusSearch(direction);
        }
    }

    /** Under a Value cheat's name: − and + by touch, the number between them (a field of its own for the controller's focus) */
    private static View valueBar(Shown s, Bind b) {
        Context c = s.a;
        float dp = c.getResources().getDisplayMetrics().density;
        b.number = CheatValues.of(s.kept, b.key, b.cheat);
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, Math.round(6 * dp), 0, Math.round(2 * dp));
        b.field = new Field(c);
        b.field.setTextSize(15);
        b.field.setTextColor(TEXT);
        b.field.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.field.setGravity(Gravity.CENTER);
        b.field.setMaxLines(1);   // not setSingleLine: its horizontal scrolling moves the foreground (the focus frame) out of sight
        b.field.setMinWidth(Math.round(112 * dp));
        b.field.setMinHeight(Math.round(36 * dp));
        b.field.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(0xFF202124);
        fb.setStroke(Math.max(1, Math.round(dp)), 0xFF5F6368);
        fb.setCornerRadius(999);
        b.field.setBackground(fb);
        b.field.setFocusable(true);
        b.field.setClickable(true);
        // The field lies inside its row, so the D-pad's search does not find it below the row (nor the row above the field): down and up say so
        b.field.setId(View.generateViewId());
        if (b.row.getId() == View.NO_ID) b.row.setId(View.generateViewId());
        b.row.setNextFocusDownId(b.field.getId());
        b.field.setNextFocusUpId(b.row.getId());
        b.field.left = () -> step(s, b, -1);
        b.field.right = () -> step(s, b, 1);
        b.field.setOnClickListener(v -> type(s, b));
        b.field.setOnFocusChangeListener((v, has) -> {
            b.desc.setText(has ? FIELD_LINE : line(b));
            b.desc.setVisibility(has || b.row.isFocused() ? View.VISIBLE : View.GONE);
        });
        b.apply = () -> applyNumber(s, b);
        shownNumber(b);
        bar.addView(stepper(c, "−", v -> step(s, b, -1)));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.leftMargin = fl.rightMargin = Math.round(8 * dp);
        bar.addView(b.field, fl);
        bar.addView(stepper(c, "+", v -> step(s, b, 1)));
        b.mark = new TextView(c);   // the marker of a set number: the file's own beside it (Reset in the details brings it back)
        b.mark.setTextSize(11);
        b.mark.setTextColor(QUIET);
        b.mark.setMaxLines(1);
        b.mark.setEllipsize(TextUtils.TruncateAt.END);
        b.mark.setPadding(Math.round(10 * dp), 0, 0, 0);
        bar.addView(b.mark, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        shownNumber(b);
        return bar;
    }

    /** Whether the number differs from the file's own (the player set it in the tab) */
    private static boolean changed(Bind b) { return b.field != null && b.number != b.cheat.value(); }

    /** A round − or + for touch (not focusable: the controller's left and right step the field) */
    private static TextView stepper(Context c, String sign, View.OnClickListener click) {
        float dp = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(sign);
        t.setTextSize(18);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        int d = Math.round(36 * dp);
        t.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CHIP);
        g.setShape(GradientDrawable.OVAL);
        t.setBackground(g);
        t.setFocusable(false);
        t.setClickable(true);
        t.setContentDescription(sign.equals("+") ? "One more" : "One less");
        t.setOnClickListener(click);
        return t;
    }

    /** The number, in yellow with the file's own beside it when it was set in the tab */
    private static void shownNumber(Bind b) {
        boolean set = changed(b);
        b.field.setText(Long.toString(b.number));
        b.field.setTextColor(set ? YELLOW : TEXT);
        b.field.setContentDescription(b.cheat.name + ", number " + b.number + (set ? ", the file has " + b.cheat.value() : ""));
        if (b.mark == null) return;
        b.mark.setText("File: " + b.cheat.value());
        b.mark.setVisibility(set ? View.VISIBLE : View.GONE);
    }

    /** One step: 1 at first; held (steps less than 0.4 s apart), 10 after ten steps, 100 after twenty, and so on */
    private static void step(Shown s, Bind b, int dir) {
        long now = android.os.SystemClock.uptimeMillis();
        s.run = now - s.lastStep < 400 ? s.run + 1 : 0;
        s.lastStep = now;
        long size = 1;
        for (int i = 0; i < Math.min(s.run / 10, 9); i++) size *= 10;
        long max = CheatValues.max(b.cheat.valueWidth);
        long v = dir > 0 ? (b.number > max - size ? max : b.number + size) : Math.max(0, b.number - size);
        setNumber(s, b, v, false);
    }

    /** The number shown at once; to the files and the host a moment after the last change (now: at once) */
    private static void setNumber(Shown s, Bind b, long v, boolean now) {
        b.number = Math.max(0, Math.min(v, CheatValues.max(b.cheat.valueWidth)));
        shownNumber(b);
        s.box.removeCallbacks(b.apply);
        s.box.postDelayed(b.apply, now ? 0 : 500);
    }

    /**
     * The number kept for the next start (CheatValues.keep), written into the cheat's block of the merged file the host runs, and the host told to read it again
     * (CheatsBridge.reload: the switches stay as they are). A host that cannot read it again has it at the next start
     */
    private static void applyNumber(Shown s, Bind b) {
        long v = b.number;
        String key = b.key, build = s.build;
        CheatFile.Cheat ch = b.cheat;
        File folder = s.folder;
        IO.execute(() -> {
            int r = -3;
            try {
                CheatValues.keep(folder, key, ch, v);
                File merged = build == null ? null : new File(folder, build + ".txt");
                if (merged != null && merged.isFile()) {
                    String p = CheatValues.patch(new String(java.nio.file.Files.readAllBytes(merged.toPath()), java.nio.charset.StandardCharsets.UTF_8), key, ch, v);
                    if (p != null) {
                        CheatValues.write(merged, p);
                        r = CheatsBridge.reload();
                    }
                }
            } catch (IOException e) {
                android.util.Log.w("peanuts", "mods tab: the number of " + key + ": " + e);
            }
            int got = r;
            android.util.Log.i("peanuts", "mods tab: " + key + " writes " + v + (got >= 0 ? " (the host read the cheats again: " + got + " entries)" : " from the next start (" + got + ")"));
            UI.post(() -> {
                b.later = got < 0;
                if (shown == s) { show(b); s.box.removeCallbacks(s.poll); s.box.postDelayed(s.poll, 250); }
            });
        });
    }

    /** A or a tap on the number: type one, in the app's dialog sheet */
    private static void type(Shown s, Bind b) {
        AlertDialog.Builder db = dialog(s.a);
        Context c = db.getContext();
        float dp = c.getResources().getDisplayMetrics().density;
        long max = CheatValues.max(b.cheat.valueWidth);
        android.widget.FrameLayout box = new android.widget.FrameLayout(c);   // as upstream's text dialog (TextInput): the field in the sheet's margins
        box.setPadding(Math.round(20 * dp), 0, Math.round(20 * dp), 0);
        EditText e = new EditText(c);
        e.setHint("0 to " + max);   // no message line: in landscape the keyboard leaves room for the title, the field and the buttons only
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setSingleLine(true);
        e.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(Long.toString(max).length())});
        e.setText(Long.toString(b.number));
        e.setSelection(0, e.getText().length());   // typing replaces it
        box.addView(e);
        AlertDialog d = db.setTitle(b.cheat.name).setView(box)
                .setPositiveButton("Set", (x, w) -> typed(s, b, e.getText().toString())).setNegativeButton("Cancel", null).create();
        e.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id != EditorInfo.IME_ACTION_DONE && !enter) return false;
            typed(s, b, e.getText().toString());
            d.dismiss();
            return true;
        });
        d.setOnKeyListener((di, k, ev) -> {
            if (k != confirmKey && k != backKey) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                if (k == backKey) di.cancel();
                else { d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); }
            }
            return true;
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
        e.requestFocus();
    }

    /** The typed number, kept inside the cheat's width (a number too long for it is its largest) */
    private static void typed(Shown s, Bind b, String text) {
        String t = text.trim();
        if (t.isEmpty() || !t.chars().allMatch(Character::isDigit)) return;
        long v;
        try { v = Long.parseLong(t); } catch (NumberFormatException e) { v = Long.MAX_VALUE; }
        setNumber(s, b, v, true);
    }

    // ---- Details ----

    /** The app's dialog sheet (Theme.Banana.Dialog: its card and yellow text buttons) also in the game's process, whose own theme is the system's */
    private static AlertDialog.Builder dialog(Activity a) { return new AlertDialog.Builder(a, R.style.Theme_Banana_Dialog); }

    /** Y or a long press: what the cheat does, why it is not possible when it is not, its source and its code */
    static void details(Shown s, Bind b) {
        AlertDialog.Builder db = dialog(s.a);
        Context c = db.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        // The sheet's own side margin (dialogPreferredPadding, as its title and a message such as Restart's have), so the text starts under the title
        android.util.TypedValue tv = new android.util.TypedValue();
        int p = c.getTheme().resolveAttribute(android.R.attr.dialogPreferredPadding, tv, true)
                ? android.util.TypedValue.complexToDimensionPixelSize(tv.data, c.getResources().getDisplayMetrics()) : Math.round(24 * c.getResources().getDisplayMetrics().density);
        box.setPadding(p, Math.round(8 * c.getResources().getDisplayMetrics().density), p, 0);
        TextView what = new TextView(c);
        what.setText(line(b));
        what.setTextSize(15);
        box.addView(what);
        TextView from = new TextView(c);
        from.setText("From " + b.source + "." + (b.group.isEmpty() ? "" : " In " + b.group + "."));
        from.setTextSize(13);
        from.setPadding(0, Math.round(8 * c.getResources().getDisplayMetrics().density), 0, 0);
        box.addView(from);
        TextView code = new TextView(c);
        code.setText(String.join("\n", changed(b) ? CheatValues.lines(b.cheat, b.number) : b.cheat.lines));   // with the number set in the tab
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(12);
        code.setPadding(0, Math.round(10 * c.getResources().getDisplayMetrics().density), 0, 0);
        box.addView(code);
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setFocusable(false);   // as AlertDialog's own message: the controller's focus goes to the buttons
        sv.addView(box);
        db.setTitle(b.cheat.name).setView(sv).setNegativeButton("Close", null);
        if (changed(b)) db.setNeutralButton("Reset", (x, w) -> setNumber(s, b, b.cheat.value(), true));   // the file's own number again (its kept one is forgotten)
        AlertDialog d = db.create();
        d.setOnKeyListener((di, k, e) -> {   // B closes; A presses the focused button (Close first), or closes when none has the focus
            if (k != confirmKey && k != backKey) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                if (k == confirmKey && d.getCurrentFocus() instanceof Button) {
                    d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                    d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
                } else di.cancel();
            }
            return true;
        });
        d.setOnShowListener(x -> {   // Close first (A on it does nothing that cannot be undone); after the window's own first focus
            Button close = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (close != null) close.post(() -> { if (!close.isInTouchMode()) close.requestFocus(); });
        });
        d.show();
    }

    /** SidePanel.padKey through ModsPanel: Y the focused row's details, X All off; true when taken. key: 'Y', 'X' or 0 */
    public static boolean padKey(char key, View focus, int confirm, int back) {
        confirmKey = confirm; backKey = back;
        Shown s = shown;
        if (s == null || key == 0) return false;
        if (key == 'X') { allOff(s); return true; }
        for (Bind b : s.binds) for (View v = focus; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) if (v == b.row) { details(s, b); return true; }
        return true;
    }

    // ---- Mods ----

    private static View modRow(Shown s, ModStore.Mod m, String gameDir, String packDir) {
        Context c = s.a;
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Math.round(10 * dp), Math.round(6 * dp), Math.round(6 * dp), Math.round(6 * dp));
        r.setMinimumHeight(Math.round(46 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ROW);
        bg.setCornerRadius(12 * dp);
        r.setBackground(bg);
        r.setFocusable(true);
        r.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(5 * dp);
        r.setLayoutParams(lp);
        TextView name = new TextView(c);
        name.setText(m.name);
        name.setTextSize(14);
        name.setTextColor(TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chip = chip(c, m.hasCode() ? "Needs a build" : "Next start");
        if (m.hasCode()) { chip.setTextColor(0xFF2A1D0C); ((GradientDrawable) chip.getBackground()).setColor(YELLOW); }
        r.addView(chip);
        Switch sw = new Switch(c);
        sw.setFocusable(false);
        sw.setChecked(m.enabled);
        sw.setThumbTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {YELLOW, 0xFFBDC1C6}));
        sw.setTrackTintList(new ColorStateList(new int[][] {{android.R.attr.state_checked}, {}}, new int[] {0xAAFFD54F, 0xFF5F6368}));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Math.round(8 * dp);
        r.addView(sw, sl);
        String id = s.titleId;
        sw.setOnCheckedChangeListener((v, on) -> new Thread(() -> {
            String err = null;
            try { ModStore.setEnabled(modsRoot(c, id, gameDir, packDir), id, m.dir.getName(), on); } catch (IOException | RuntimeException e) { err = String.valueOf(e.getMessage()); }
            String ferr = err;
            UI.post(() -> {
                if (ferr != null) { android.util.Log.w("peanuts", "mods tab: " + m.name + ": " + ferr); v.setChecked(!on); return; }
                modsChanged = true;
                if (shown == s) s.restart.setVisibility(View.VISIBLE);
            });
        }, "peanuts-mod-switch").start());
        r.setOnClickListener(v -> sw.toggle());
        return r;
    }

    /** Restart the game to apply: the game stops and starts again (a mod's files and code are read at a start). Save in the game first */
    private static View restartBar(Shown s) {
        Context c = s.a;
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Math.round(10 * dp), Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(YELLOW);
        bg.setCornerRadius(12 * dp);
        bar.setBackground(bg);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText("Restart the game to apply");
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF2A1D0C);
        col.addView(t);
        TextView n = new TextView(c);
        n.setText("Save in the game first");
        n.setTextSize(12);
        n.setTextColor(0xFF2A1D0C);
        col.addView(n);
        bar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button go = new Button(c);
        go.setText("Restart");
        go.setAllCaps(false);
        go.setTextColor(YELLOW);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xFF2A1D0C);
        gb.setCornerRadius(999);
        go.setBackground(gb);
        go.setOnClickListener(v -> {
            AlertDialog d = dialog(s.a).setTitle("Restart the game?").setMessage("The game stops and starts again with the mods as they are now. Progress since the last save is lost.")
                    .setPositiveButton("Restart", (x, w) -> CheatsBridge.restart(s.a)).setNegativeButton("Keep playing", null).create();
            d.setOnKeyListener((di, k, e) -> {
                if (k != confirmKey && k != backKey) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    if (k == backKey) di.cancel();
                    else { d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)); d.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)); }
                }
                return true;
            });
            d.show();
            Button no = d.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (no != null && !no.isInTouchMode()) no.requestFocus();
        });
        bar.addView(go);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * dp);
        bar.setLayoutParams(lp);
        return bar;
    }

    // ---- Small views in the panel's look ----

    private static TextView chip(Context c, String s) {
        float dp = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(11);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(QUIET);
        t.setSingleLine(true);
        t.setPadding(Math.round(8 * dp), Math.round(3 * dp), Math.round(8 * dp), Math.round(3 * dp));
        GradientDrawable d = new GradientDrawable();
        d.setColor(CHIP);
        d.setCornerRadius(999);
        t.setBackground(d);
        return t;
    }

    /** A heading as SidePanel.head makes it (PanelPolish gives it the panel's heading look) */
    private static TextView head(Context c, String s) {
        TextView h = new TextView(c);
        h.setText(s);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(15);
        h.setPadding(0, Math.round(18 * c.getResources().getDisplayMetrics().density), 0, 0);
        return h;
    }

    /** A note as SidePanel.note makes it */
    private static TextView note(Context c, String s) {
        TextView n = new TextView(c);
        n.setText(s);
        n.setTextSize(12);
        n.setTextColor(Color.LTGRAY);
        n.setPadding(0, Math.round(10 * c.getResources().getDisplayMetrics().density), 0, 0);
        return n;
    }

    /** The keys at the tab's foot: A Switch, Y Details, X All off, L R Tabs, B Close */
    private static View keys(Context c, boolean cheats) {
        float dp = c.getResources().getDisplayMetrics().density;
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, Math.round(14 * dp), 0, 0);
        String[][] k = cheats ? new String[][] {{"A", "Switch"}, {"Y", "Details"}, {"X", "All off"}, {"L R", "Tabs"}, {"B", "Close"}}
                : new String[][] {{"A", "Switch"}, {"L R", "Tabs"}, {"B", "Close"}};   // Y and X act on cheats only (no All off without them)
        for (String[] x : k) {
            TextView g = chip(c, x[0]);
            g.setTextColor(TEXT);
            l.addView(g);
            TextView t = new TextView(c);
            t.setText(x[1]);
            t.setTextSize(11);
            t.setTextColor(QUIET);
            t.setPadding(Math.round(4 * dp), 0, Math.round(10 * dp), 0);
            l.addView(t);
        }
        return l;
    }
}
