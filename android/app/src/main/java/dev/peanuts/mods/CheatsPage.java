package dev.peanuts.mods;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.nxnative.android.CheatsBridge;
import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.ui.BottomScreen;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.Icons;
import dev.peanuts.ui.PUi;

/**
 * A game's Mods &amp; Cheats (SPEC-mod-menu.md, Package B 6; David's canvas, page "Mod-Menü"): a page in place of the game's page (GameSettings.build's PEANUTS
 * hook), opened from its row on the Game tab ({@link #entry}); Back returns to the game's page (SettingsPage.onBack's hook), L1 / R1 switch Cheats and Mods
 * (GameSettings.stepTab's hook). Its top (Back, the game, Cheats | Mods, Add) stays at the top while the rest scrolls under it ({@link #stick}).
 * <ul>
 * <li>Cheats: "Show in the game" (the Mods tab of the in-game panel, Package C), then each source (the cheats that come with the app, each import, each enabled
 *     mod's cheat files) with its credit, its masters and the Restore Code line, and its cheats in their groups: a switch each, its kind and its hold buttons as
 *     chips. A switch keeps the toggle for the game's next start (CheatsBridge.on: the toggles the launcher writes into &lt;build&gt;.enabled) and acts at once in a
 *     running game through the in-game panel. A tap on a source opens its sheet: on or off as a whole, other versions, remove.</li>
 * <li>Mods: today's Mods section (dev.peanuts.settings.ModsSection), each mod with "Next start" or "Needs a build".</li>
 * <li>Add: a file (.zip, .txt) or a folder (CheatImportUi); a mod goes to the mod import.</li>
 * </ul>
 * On the Thor the bottom screen explains the row the focus is on (BottomScreen.explain): a cheat's kind, what it changes, that it works at once, its source.
 * A, Y and X: switch, details, all off. Nothing here reads or writes a save.
 */
public final class CheatsPage {
    private CheatsPage() {}

    static final String CHEATS = "cheats", MODS = "mods";

    /** The game the page is open for, null: the game's own page shows */
    private static Game openFor;
    private static String tab = CHEATS;
    private static Runnable again;      // MainActivity.gameSettingsAgain: the page (or, once closed, the game's page) built again where it was
    private static Activity owner;
    private static int gameScroll;      // where the game's page was when the page opened, for Back
    private static boolean toTop;       // the page shows anew (opened, another tab): at its top
    private static boolean inArea;      // the page is an area of the game's page in areas (dev.peanuts.ui.GamePage): no top of its own, Back and L1 / R1 are the area's

    /** A game as the game's page has it */
    static final class Game {
        final String titleId, name, gameDir, packDir;
        Game(String titleId, String name, String gameDir, String packDir) { this.titleId = titleId; this.name = name; this.gameDir = gameDir; this.packDir = packDir; }
    }

    // ---- Hooks ----

    /** The Game tab's row (GameSettings, PEANUTS hook in place of the Mods section): "Mods & Cheats" with what the game has, opening the page */
    public static View entry(Activity a, String titleId, String name, String gameDir, String packDir, Runnable again) {
        Game g = new Game(titleId, name, gameDir, packDir);
        dev.peanuts.settings.ModsSection.due(a, titleId, name, gameDir, packDir);   // a build with mods that is due starts as when the Mods section showed here
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        dev.peanuts.settings.SectionUi ui = new dev.peanuts.settings.SectionUi(a);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = PUi.dp(a, 12);
        box.addView(ui.section("Mods & Cheats"), lp);
        View row = ui.setting("", "Mods & Cheats", summary(a, g), Feel.row(v -> { gameScroll = scrollY(a); open(a, g, CHEATS, again); }));
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildAt(0) instanceof TextView) Icons.tile((TextView) ((LinearLayout) row).getChildAt(0), "mods");
        box.addView(row);
        return box;
    }

    /** GameSettings.build's first line (PEANUTS hook): Mods & Cheats open for this game: its page is built into content instead, true */
    public static boolean build(Activity a, LinearLayout content, String titleId, String name, String gameDir, String packDir, Runnable again, Runnable close) {
        if (inArea) return false;   // an area of the game's page (GamePage builds it)
        if (openFor == null || !openFor.titleId.equals(titleId) || a != owner) { openFor = null; return false; }
        openFor = new Game(titleId, name, gameDir, packDir);   // its folders as the page has them now
        CheatsPage.again = again;
        page(a, content, openFor);
        if (toTop) {   // twice posted: after the scroll gameSettingsAgain posts once this returns
            toTop = false;
            content.post(() -> content.post(() -> { android.widget.ScrollView sv = scroller(a); if (sv != null) sv.scrollTo(0, 0); }));
        }
        return true;
    }

    /** MainActivity.onBackPressed (through SettingsPage.onBack): Mods & Cheats closes to the game's page; true when it did */
    public static boolean onBack(Activity a) {
        if (inArea) return false;   // the game's page in areas: Back goes to its tiles (GamePage.onBack)
        if (openFor == null || a != owner) return false;
        close();
        return true;
    }

    /** GameSettings.stepTab (PEANUTS hook): L1 / R1 on the page switch Cheats and Mods; true when the page shows (the caller builds it again) */
    public static boolean stepTab(String titleId, int delta) {
        if (inArea) return false;   // the game's page in areas: L1 / R1 step its areas
        if (openFor == null || !openFor.titleId.equals(titleId)) return false;
        tab = tab.equals(CHEATS) ? MODS : CHEATS;
        toTop = true;
        return true;
    }

    /** Home or Settings shows (BottomScreen.home, SettingsPage.lay): the page is closed */
    public static void left() { openFor = null; inArea = false; dev.peanuts.settings.ModsSection.listChanged = null; }

    /**
     * The game's page in areas (dev.peanuts.ui.GamePage): the page's content in the area Mods &amp; Cheats (pane: the area's column under its title):
     * Cheats | Mods and Add in a bar, then the cheats or the mods. No Back and no game name (the area and the game's panel have them), no top that stays.
     * A switch, an import or a removal builds the game's page again (again), which shows this area again
     */
    public static void area(Activity a, LinearLayout pane, String titleId, String name, String gameDir, String packDir, Runnable again) {
        owner = a;
        inArea = true;
        openFor = new Game(titleId, name, gameDir, packDir);
        CheatsPage.again = again;
        toTop = false;
        page(a, pane, openFor, false);
    }

    /** The area Mods &amp; Cheats is left (another area, the tiles): the page is closed */
    public static void leaveArea() {
        if (!inArea) return;
        inArea = false;
        openFor = null;
        dev.peanuts.settings.ModsSection.listChanged = null;
    }

    /** The tile of the area Mods &amp; Cheats: what the game has (as the Game tab's row said it), or what to do first */
    public static CharSequence tileLine(Activity a, String titleId, String name, String gameDir, String packDir) {
        Game g = new Game(titleId, name, gameDir, packDir);
        dev.peanuts.settings.ModsSection.due(a, titleId, name, gameDir, packDir);   // a build with mods that is due starts as when the row showed
        return summary(a, g);
    }

    static void open(Activity a, Game g, String which, Runnable again) {
        owner = a;
        openFor = g;
        tab = which;
        toTop = true;
        CheatsPage.again = again;
        again.run();
    }

    private static void close() {
        openFor = null;
        dev.peanuts.settings.ModsSection.listChanged = null;
        BottomScreen.explain(owner, null);
        if (again == null) return;
        again.run();
        Activity a = owner;
        int y = gameScroll;
        a.getWindow().getDecorView().post(() -> { android.widget.ScrollView sv = scroller(a); if (sv != null) sv.scrollTo(0, y); });   // after the game page's own scroll (gameSettingsAgain posts it)
    }

    /** The page's scrolling column (MainActivity's Ui.page) */
    private static android.widget.ScrollView scroller(Activity a) { return a == null ? null : firstScroll(a.getWindow().getDecorView()); }

    private static android.widget.ScrollView firstScroll(View v) {
        if (v instanceof android.widget.ScrollView && v.isShown()) return (android.widget.ScrollView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { android.widget.ScrollView s = firstScroll(((ViewGroup) v).getChildAt(i)); if (s != null) return s; }
        return null;
    }

    private static int scrollY(Activity a) { android.widget.ScrollView s = scroller(a); return s == null ? 0 : s.getScrollY(); }

    /** The page built again (after a switch of a source, an import, a removal), where it was */
    static void rebuild() { if (openFor != null && again != null) again.run(); }

    /** The game the page shows, null: none (an import's result came after the page was left) */
    static Game shown() { return openFor; }

    /** Shows the Mods tab (an import that turned out to be a mod) and builds the page; false when the page does not show */
    static boolean showMods() {
        if (openFor == null || again == null) return false;
        tab = MODS;
        again.run();
        return true;
    }

    // ---- What the game has ----

    /** The page's facts of a game: its build, the merge (every source's cheats and keys), the player's sources and toggles */
    static final class Model {
        final Game g;
        final File folder;
        final Set<String> builds;
        final String build, version;
        final long textEnd;
        final CheatSources.Merged merged;
        final List<CheatSources.Source> sources;
        final Set<String> on;
        final boolean appHas, appOn;

        Model(Context c, Game g) {
            this.g = g;
            folder = CheatsBridge.folder(c, g.titleId);
            builds = CheatsBridge.builds(g.titleId, g.packDir);
            build = builds.isEmpty() ? null : builds.iterator().next();
            version = build == null ? null : CheatsBridge.version(g.titleId, build);
            textEnd = CheatsBridge.textEnd(g.packDir);
            CheatsBridge.wanted(c, g.titleId, CheatsBridge.master(c, g.titleId));   // the earlier state moved over once (the app's switch, upstream's imports)
            merged = build == null ? null : CheatsBridge.merged(c, g.titleId, g.gameDir, g.packDir, build, textEnd);
            sources = CheatSources.list(folder);
            on = CheatsBridge.on(c, g.titleId);
            appHas = build != null && CheatsBridge.appText(g.packDir, build) != null;
            Boolean ao = CheatSources.appEnabled(folder);
            appOn = ao == null || ao;
        }

        /** The blocks of the merge, in its order */
        List<CheatSources.Block> blocks() {
            List<CheatSources.Block> out = new ArrayList<>();
            if (merged != null) for (CheatSources.Row r : merged.rows) if (!out.contains(r.block)) out.add(r.block);
            return out;
        }

        CheatSources.Block block(String id) { for (CheatSources.Block b : blocks()) if (b.id.equals(id)) return b; return null; }

        /** The cheats the game has now: the switchable ones of the sources that are on */
        int cheats() { return merged == null ? 0 : merged.keys().size(); }

        int onCount() { int n = 0; if (merged != null) for (String k : merged.keys()) if (on.contains(k)) n++; return n; }

        String versionWords() { return version != null ? version : "your version"; }
    }

    static String shortBuild(String b) { return b == null ? "" : b.substring(0, Math.min(8, b.length())); }

    static String count(int n, String one) { return n + " " + one + (n == 1 ? "" : "s"); }

    private static CharSequence summary(Activity a, Game g) {
        try {
            Model m = new Model(a, g);
            int mods = modsCount(a, g);
            if (m.build == null) return "Build the game first: cheats are made for one build of it";
            int n = m.cheats(), on = m.onCount();
            if (n == 0 && mods == 0) return "Add cheats or mods from this device";
            return PUi.joined(n == 0 ? null : on > 0 ? on + " of " + count(n, "cheat") + " on" : count(n, "cheat"), mods == 0 ? null : count(mods, "mod"));
        } catch (RuntimeException e) {
            android.util.Log.w("peanuts", "cheats: the row: " + e);
            return "Cheats and mods from this device";
        }
    }

    private static int modsCount(Context c, Game g) {
        try {
            File pack = g.packDir == null || g.packDir.isEmpty() ? null : new File(g.packDir);
            File game = g.gameDir == null || g.gameDir.isEmpty() ? null : new File(g.gameDir);
            if (pack == null || !new File(pack, "pack.tsv").isFile()) return 0;
            return ModRuntime.mods(ModRuntime.home(c, g.titleId, game, pack.getParentFile()), g.titleId).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ---- The page ----

    private static void page(Activity a, LinearLayout content, Game g) { page(a, content, g, true); }

    /** The page; own: with its top (Back, the titles) kept at the top (its own page), else the bar alone (an area of the game's page) */
    private static void page(Activity a, LinearLayout content, Game g, boolean own) {
        Model m = new Model(a, g);
        boolean wide = a.getResources().getConfiguration().screenWidthDp >= 600;
        int mods = modsCount(a, g);
        PUi.Segmented seg = new PUi.Segmented(a, new String[] {"Cheats " + m.cheats(), "Mods " + mods}, tab.equals(CHEATS) ? 0 : 1, k -> {
            tab = k == 0 ? CHEATS : MODS;
            toTop = true;
            Feel.picked(content);
            rebuild();
        });
        android.widget.Button add = PUi.button(a, "Add", PUi.Kind.PRIMARY, Feel.button(v -> CheatImportUi.add(a, g.titleId)));
        android.graphics.drawable.Drawable plus = Icons.get(a, "add", Icons.Ink.ON_BANANA);
        if (plus != null) { plus.setBounds(0, 0, PUi.dp(a, 20), PUi.dp(a, 20)); add.setCompoundDrawables(plus, null, null, null); add.setCompoundDrawablePadding(PUi.dp(a, 6)); add.setPadding(PUi.dp(a, 12), 0, PUi.dp(a, 16), 0); }

        LinearLayout head = PUi.row(a);
        head.setMinimumHeight(PUi.dp(a, 56));
        head.addView(PUi.back(a, v -> close()));
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(PUi.dp(a, 14), 0, PUi.dp(a, 8), 0);
        TextView t = PUi.text(a, "Mods & Cheats", 22, R.color.bn_text, 800);
        t.setAccessibilityHeading(true);
        titles.addView(t);
        TextView sub = PUi.text(a, PUi.joined(g.name, m.version), 13, R.color.bn_text_2, 400);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(sub);
        head.addView(titles, PUi.weight());
        LinearLayout top = new LinearLayout(a);   // the page's top, kept at the top while the rest scrolls (stick)
        top.setOrientation(LinearLayout.VERTICAL);
        if (!own) {   // the area's bar: Cheats | Mods, then Add
            LinearLayout bar = PUi.row(a);
            bar.addView(seg, new LinearLayout.LayoutParams(PUi.dp(a, 240), ViewGroup.LayoutParams.WRAP_CONTENT));
            bar.addView(PUi.spacer(a));
            bar.addView(add, PUi.wrap());
            top.addView(bar);
        } else if (wide) {
            head.addView(seg, new LinearLayout.LayoutParams(PUi.dp(a, 240), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams al = PUi.wrap();
            al.leftMargin = PUi.dp(a, 10);
            head.addView(add, al);
            top.addView(head);
        } else {
            top.addView(head);
            LinearLayout bar = PUi.row(a);
            bar.addView(seg, PUi.weight());
            LinearLayout.LayoutParams al = PUi.wrap();
            al.leftMargin = PUi.dp(a, 10);
            bar.addView(add, al);
            PUi.add(top, bar, 8);
        }
        content.addView(top);
        dev.peanuts.settings.ModsSection.listChanged = tab.equals(MODS) ? CheatsPage::rebuild : null;   // a mod installed or deleted: the count above
        if (tab.equals(MODS)) {
            LinearLayout card = PUi.add(content, PUi.listCard(a), 12);   // as the section sat in the Game tab's card
            card.addView(dev.peanuts.settings.ModsSection.view(a, g.titleId, g.name, g.gameDir, g.packDir, false));
            BottomScreen.explain(a, null);
        } else {
            cheats(a, content, m);
        }
        if (own) stick(content, top);
    }

    /**
     * The page's top stays at the top of the screen while the rest of the page scrolls under it (the canvas' game page): it moves with the page until it
     * reaches the status bar's scrim, then stays there, on the page's own colour, solid (no row shows through), which fades in only once rows pass under
     * it. Its bottom padding takes the gap to the first card (the card's margin gives it back, so the page looks as before at its top). A row the
     * controller's focus moves to is scrolled out from under it.
     */
    private static void stick(LinearLayout content, LinearLayout top) {
        if (!(content.getParent() instanceof android.widget.ScrollView)) return;
        android.widget.ScrollView sv = (android.widget.ScrollView) content.getParent();
        Context c = content.getContext();
        int pad = PUi.dp(c, 8);
        top.setPadding(0, 0, 0, pad);
        View next = content.getChildAt(content.indexOfChild(top) + 1);
        if (next != null && next.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) next.getLayoutParams();
            lp.topMargin = Math.max(0, lp.topMargin - pad);
            next.setLayoutParams(lp);
        }
        android.graphics.drawable.ColorDrawable bg = new android.graphics.drawable.ColorDrawable(PUi.col(c, R.color.bn_bg) | 0xFF000000);
        bg.setAlpha(0);
        top.setBackground(bg);
        top.setOutlineProvider(null);   // no shadow: rows go under it, no line between
        top.setTranslationZ(PUi.dp(c, 1));   // drawn over the rows that pass under it
        top.setOnTouchListener((v, e) -> true);   // a tap on its empty part (only reached when no button took it) never reaches a row under it
        java.util.function.IntSupplier gap = () -> {   // the page's own space above its top (Ui.page: the status bar's inset, then this)
            android.view.WindowInsets wi = sv.getRootWindowInsets();
            int bar = wi == null ? 0 : wi.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()).top;
            return Math.max(0, sv.getPaddingTop() - bar);
        };
        Runnable place = () -> {   // top is content's first child: at scroll y it sits y - gap down the content once it stays
            float t = Math.max(0, sv.getScrollY() - gap.getAsInt());
            top.setTranslationY(t);
            bg.setAlpha(Math.round(255 * Math.min(1f, t / pad)));
        };
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> place.run());
        place.run();
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener clear = (old, now) -> {
            if (now == null || !inside(now, content) || inside(now, top)) return;
            android.graphics.Rect r = new android.graphics.Rect();
            now.getDrawingRect(r);
            content.offsetDescendantRectToMyCoords(now, r);
            int most = r.top + gap.getAsInt() - top.getHeight();   // the furthest scroll that keeps the row below the top (its bottom padding is the room above it)
            // An absolute target: the ScrollView may have started its own smooth scroll to the row (D-pad up), which this ends where the row shows
            if (most < sv.getScrollY()) sv.smoothScrollTo(0, Math.max(0, most));
        };
        if (content.isAttachedToWindow()) content.getViewTreeObserver().addOnGlobalFocusChangeListener(clear);
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {   // the window's observer outlives the page: added and removed with it
            @Override public void onViewAttachedToWindow(View v) { v.getViewTreeObserver().addOnGlobalFocusChangeListener(clear); }
            @Override public void onViewDetachedFromWindow(View v) { v.getViewTreeObserver().removeOnGlobalFocusChangeListener(clear); }
        });
    }

    private static boolean inside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private static void cheats(Activity a, LinearLayout content, Model m) {
        Game g = m.g;
        LinearLayout first = PUi.add(content, PUi.listCard(a), 12);
        PUi.Setting show = new PUi.Setting(a, "", "Show in the game", null).value("The Mods tab in the in-game panel")
                .toggle(a, PeanutsPrefs.modsTab(a, g.titleId), (b, x) -> { Feel.toggled(b, x); PeanutsPrefs.modsTab(a, g.titleId, x); explainShow(a, x, b); });
        rowLook(show.view, "controller");
        show.view.setOnClickListener(v -> show.toggle.toggle());
        explainOnFocus(show.view, () -> explainShow(a, show.toggle.isChecked(), show.toggle));
        first.addView(show.view);
        if (m.build == null) {
            PUi.add(content, note(a, "Cheats are made for one build of the game, and this game's build is not known yet. Prepare the game, then add its cheats."), 12);
            return;
        }
        List<CheatSources.Block> blocks = m.blocks();
        boolean any = false;
        // the app's cheats, then the player's sources by when they were added (those without a file of this build too), then the mods'
        if (m.appHas) { CheatSources.Block b = m.block(CheatSources.APP); any |= source(a, content, m, b, null, b != null ? b.name : "Comes with the app"); }
        for (CheatSources.Source s : m.sources) any |= source(a, content, m, m.block(s.id), s, s.name);
        for (CheatSources.Block b : blocks) if (b.id.startsWith("mod:")) any |= source(a, content, m, b, null, b.name);
        if (!any) {
            LinearLayout card = PUi.add(content, PUi.listCard(a), 12);
            PUi.Setting none = new PUi.Setting(a, "", "No cheats yet", Feel.row(v -> CheatImportUi.add(a, g.titleId)))
                    .value("Add a zip, a text file or a folder with cheats for " + m.versionWords() + " (build " + shortBuild(m.build) + "). Peanuts never downloads any.");
            rowLook(none.view, "folder");
            explainOnFocus(none.view, () -> BottomScreen.explain(a, null));
            card.addView(none.view);
        }
        PUi.add(content, note(a, "A cheat switches at once in a running game and is kept for the next start. Cheated values are stored when the game saves."), 14);
    }

    /** One source: its card (name, what it has, its chips, credit, masters, Restore Code), then its cheats by group. false when it has nothing to show */
    private static boolean source(Activity a, LinearLayout content, Model m, CheatSources.Block b, CheatSources.Source s, String name) {
        boolean app = s == null && b != null && b.id.equals(CheatSources.APP), mod = b != null && b.id.startsWith("mod:");
        boolean enabled = s != null ? s.enabled : app ? m.appOn : true;
        Set<String> otherBuilds = new LinkedHashSet<>();
        if (s != null) for (String bid : s.files().keySet()) if (!bid.equals(m.build)) otherBuilds.add(bid);
        if (b == null && otherBuilds.isEmpty()) return false;
        CheatFile.Parsed p = b == null ? null : b.parsed;
        int n = p == null ? 0 : p.switchable();

        LinearLayout card = PUi.add(content, PUi.listCard(a), 16);
        CharSequence line = p != null ? PUi.joined(count(n, "cheat") + " for " + m.versionWords(), shortBuild(m.build))
                : PUi.joined("For another version", shortBuild(otherBuilds.iterator().next()));
        PUi.Setting head = new PUi.Setting(a, "", name, Feel.row(v -> sourceSheet(a, m, b, s, name, otherBuilds))).value(line);
        rowLook(head.view, mod ? "mods" : "folder");
        String from = s == null ? (app ? "Comes with the app" : "From a mod") : fromWords(s.from);
        head.view.addView(chip(a, enabled ? from : "Off", false), chipLp(a));
        explainOnFocus(head.view, () -> explainSource(a, m, b, s, name, enabled, otherBuilds));
        card.addView(head.view);
        if (p != null && !p.credits.isEmpty()) card.addView(cardLine(a, "Credit: " + CheatFile.credit(p)));
        if (p != null && p.unsupported() > 0) card.addView(cardLine(a, p.unsupported() + " more " + (p.unsupported() == 1 ? "is" : "are") + " not supported here: listed, and off."));
        if (p != null && !p.masters.isEmpty() && enabled) {
            List<String> ms = new ArrayList<>();
            for (CheatFile.Cheat c : p.masters) ms.add(c.name);
            card.addView(cardLine(a, "Always on: " + String.join(", ", ms)));
        }
        if (p != null && !p.restores.isEmpty()) card.addView(cardLine(a, "Restore Code is left out: Peanuts puts the original code back by itself."));
        if (!enabled) { card.addView(cardLine(a, "Off: its cheats are not in the game. Tap to turn it on.")); return true; }
        if (p == null) { card.addView(cardLine(a, "None of its cheats are for " + m.versionWords() + ". Tap to see its versions.")); return true; }

        // its cheats by group, in the file's order of the groups ("" first: cheats outside any group)
        Map<String, List<CheatFile.Cheat>> groups = new LinkedHashMap<>();
        for (CheatFile.Cheat c : p.cheats) groups.computeIfAbsent(c.group, k -> new ArrayList<>()).add(c);
        for (Map.Entry<String, List<CheatFile.Cheat>> e : groups.entrySet()) {
            if (!e.getKey().isEmpty()) PUi.add(content, PUi.section(a, e.getKey()), 14);
            LinearLayout list = PUi.add(content, PUi.listCard(a), e.getKey().isEmpty() ? 8 : 6);
            for (CheatFile.Cheat c : e.getValue()) list.addView(cheatRow(a, m, b, c, name, from));
        }
        return true;
    }

    static String fromWords(String from) {
        switch (from) {
            case CheatSources.FROM_ZIP: return "From your zip";
            case CheatSources.FROM_FOLDER: return "From your folder";
            case CheatSources.FROM_EARLIER: return "Added earlier";
            default: return "From your file";
        }
    }

    /** One cheat: its name over its chips (kind, hold), a switch; Not supported: no switch that moves */
    private static View cheatRow(Activity a, Model m, CheatSources.Block b, CheatFile.Cheat c, String source, String from) {
        String key = m.merged.key(b.id, c.key);
        boolean can = c.switchable() && key != null;
        LinearLayout row = PUi.row(a);
        row.setPadding(PUi.dp(a, 12), PUi.dp(a, 7), PUi.dp(a, 10), PUi.dp(a, 7));
        row.setMinimumHeight(PUi.dp(a, 52));
        row.setBackground(PUi.ripple(a, PUi.round(android.graphics.Color.TRANSPARENT, PUi.dp(a, 12), 0, 0)));
        row.setFocusable(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = PUi.text(a, c.name, 15, R.color.bn_text, 600);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        LinearLayout chips = PUi.row(a);
        chips.setPadding(0, PUi.dp(a, 4), 0, 0);
        chips.addView(chip(a, c.kind.label, false));
        if (c.holdLabel() != null) { LinearLayout.LayoutParams lp = PUi.wrap(); lp.leftMargin = PUi.dp(a, 6); chips.addView(chip(a, c.holdLabel(), true), lp); }
        col.addView(chips);
        row.addView(col, PUi.weight());
        Switch sw = new Switch(a);
        sw.setChecked(can && m.on.contains(key));
        sw.setEnabled(can);
        sw.setFocusable(false);   // the row is the stop: A on it switches
        sw.setContentDescription(c.name);
        row.addView(sw);
        if (!can) row.setAlpha(0.6f);
        Runnable explain = () -> explainCheat(a, m, c, key, sw.isChecked(), source, from);
        sw.setOnCheckedChangeListener((v, x) -> {
            Feel.toggled(v, x);
            CheatsBridge.on(a, m.g.titleId, key, x);
            if (x) m.on.add(key); else m.on.remove(key);
            explain.run();
        });
        row.setOnClickListener(v -> { if (can) sw.toggle(); else details(a, c, source, from); });
        row.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) return code == KeyEvent.KEYCODE_BUTTON_Y || code == KeyEvent.KEYCODE_BUTTON_X;
            if (code == KeyEvent.KEYCODE_BUTTON_Y) { details(a, c, source, from); return true; }
            if (code == KeyEvent.KEYCODE_BUTTON_X) { allOff(a, m); return true; }
            return false;
        });
        row.setOnLongClickListener(v -> { details(a, c, source, from); return true; });
        explainOnFocus(row, explain);
        return row;
    }

    /** X: every cheat of the game off, for the next start and in a running game's panel */
    private static void allOff(Activity a, Model m) {
        if (m.onCount() == 0) { Feel.refused(a); return; }
        CheatsBridge.on(a, m.g.titleId, new LinkedHashSet<>());
        Feel.accepted(a);
        Toast.makeText(a, "All cheats are off.", Toast.LENGTH_SHORT).show();
        rebuild();
    }

    /** Y or a long press: what the cheat does, its source, and its code as written */
    private static void details(Activity a, CheatFile.Cheat c, String source, String from) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = PUi.row(a);
        chips.addView(chip(a, c.kind.label, false));
        if (c.holdLabel() != null) { LinearLayout.LayoutParams lp = PUi.wrap(); lp.leftMargin = PUi.dp(a, 6); chips.addView(chip(a, c.holdLabel(), true), lp); }
        box.addView(chips);
        PUi.add(box, PUi.body(a, CheatFile.describe(c)), 12);
        PUi.add(box, PUi.small(a, "From " + source + (from.startsWith("From ") ? ", " + from.substring(5) : "") + "." + (c.group.isEmpty() ? "" : " In " + c.group + ".")), 8);
        TextView code = PUi.text(a, String.join("\n", c.lines), 13, R.color.bn_text_2, 400);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        PUi.add(box, code, 12);
        AlertDialog d = PUi.sheet(a, c.name, box).setPositiveButton("Close", null).create();
        Feel.dialog(d);
        d.show();
    }

    /** A tap on a source: on or off as a whole, its other versions, remove (not the app's own, not a mod's: the Mods tab has those) */
    private static void sourceSheet(Activity a, Model m, CheatSources.Block b, CheatSources.Source s, String name, Set<String> otherBuilds) {
        boolean app = s == null && b != null && b.id.equals(CheatSources.APP);
        if (s == null && !app) { tab = MODS; rebuild(); return; }   // a mod's cheats: the mod is switched on the Mods tab
        boolean on = s != null ? s.enabled : m.appOn;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        CheatFile.Parsed p = b == null ? null : b.parsed;
        String what = p == null ? "No cheats for " + m.versionWords() + "." : count(p.switchable(), "cheat") + " for " + m.versionWords() + ", build " + shortBuild(m.build) + ".";
        PUi.add(box, PUi.body(a, what + (app ? " They come with the app for this game." : " " + fromWords(s.from) + ".")), 0);
        if (p != null && !p.credits.isEmpty()) PUi.add(box, PUi.small(a, "Credit: " + CheatFile.credit(p)), 8);
        if (!otherBuilds.isEmpty()) {
            PUi.add(box, PUi.section(a, "For another version"), 16);
            for (String bid : otherBuilds) {
                String v = CheatsBridge.version(m.g.titleId, bid);
                int n = 0;
                try { String text = s.text(bid); if (text != null) n = CheatFile.parse(text, -1).switchable(); } catch (IOException ignored) { }
                PUi.add(box, PUi.small(a, PUi.joined(count(n, "cheat"), v != null ? v : null, "build " + bid)), 6);
            }
            PUi.add(box, PUi.small(a, "They start to work when the game is that version."), 6);
        }
        AlertDialog.Builder d = PUi.sheet(a, name, box).setNegativeButton("Close", null);
        d.setPositiveButton(on ? "Turn off" : "Turn on", Feel.confirm((x, w) -> {
            try {
                if (s != null) CheatSources.enabled(m.folder, s.id, !on); else CheatSources.appEnabled(m.folder, !on);
            } catch (IOException e) {
                Feel.refused(a);
                Toast.makeText(a, "Could not turn " + name + (on ? " off" : " on") + ". Its files are as they were.", Toast.LENGTH_LONG).show();
                android.util.Log.w("peanuts", "cheats: switch a source: " + e);
            }
            rebuild();
        }));
        if (s != null) d.setNeutralButton("Remove", (x, w) -> confirmRemove(a, m, s, p == null ? 0 : p.switchable()));
        AlertDialog ad = d.create();
        Feel.dialog(ad);
        ad.show();
    }

    private static void confirmRemove(Activity a, Model m, CheatSources.Source s, int n) {
        PUi.confirmDanger(a, "Remove " + s.name + "?", (n > 0 ? "Its " + count(n, "cheat") + " leave the game from the next start. " : "") + "Its files are deleted from this device. The game and its saves are not changed.",
                "Remove", () -> {
                    CheatSources.Merged before = m.merged;
                    if (!CheatSources.remove(m.folder, s.id)) { Feel.refused(a); Toast.makeText(a, "Could not remove " + s.name + ".", Toast.LENGTH_LONG).show(); rebuild(); return; }
                    keepToggles(a, m, before, s.id);
                    Feel.accepted(a);
                    rebuild();
                });
    }

    /**
     * After a source is removed: its cheats' toggles go, and a later source's cheat whose name was taken by it (so it was "Name (Source)") keeps its toggle
     * under the name it has now
     */
    private static void keepToggles(Context c, Model m, CheatSources.Merged before, String removed) {
        if (before == null || m.build == null) return;
        CheatSources.Merged after = CheatsBridge.merged(c, m.g.titleId, m.g.gameDir, m.g.packDir, m.build, m.textEnd);
        Set<String> on = CheatsBridge.on(c, m.g.titleId);
        for (CheatSources.Row r : before.rows) {
            if (r.block.id.equals(removed)) { on.remove(r.key); continue; }
            String now = after.key(r.block.id, r.cheat.key);
            if (now != null && !now.equals(r.key) && on.remove(r.key)) on.add(now);
        }
        CheatsBridge.on(c, m.g.titleId, on);
    }

    // ---- Views ----

    /** A row of PUi.Setting in the game page's measures (SettingsPage.likeUpstream's: a 36 dp tile, 52 dp high, a 15 sp title, the value whole) */
    private static void rowLook(LinearLayout r, String icon) {
        Context c = r.getContext();
        View tile = r.getChildAt(0);
        ViewGroup.LayoutParams lp = tile.getLayoutParams();
        lp.width = lp.height = PUi.dp(c, 36);
        tile.setLayoutParams(lp);
        tile.setBackground(PUi.round(PUi.col(c, R.color.bn_tint), PUi.dp(c, 11), 0, 0));
        r.setPadding(PUi.dp(c, 10), PUi.dp(c, 7), PUi.dp(c, 10), PUi.dp(c, 7));
        r.setMinimumHeight(PUi.dp(c, 52));
        r.setFocusable(true);
        LinearLayout col = (LinearLayout) r.getChildAt(1);
        col.setPadding(PUi.dp(c, 12), 0, PUi.dp(c, 8), 0);
        ((TextView) col.getChildAt(0)).setTextSize(15);
        if (col.getChildCount() > 1) ((TextView) col.getChildAt(1)).setMaxLines(4);
        Icons.tile((TextView) tile, icon);   // after the size: its inset follows the tile's width
    }

    /** A chip: the tint with the quieter text; strong: banana yellow (a hold button) */
    static TextView chip(Context c, CharSequence s, boolean strong) {
        TextView t = PUi.chip(c, s);
        t.setSingleLine(true);
        if (strong) {
            t.setBackground(PUi.round(PUi.col(c, R.color.bn_banana), PUi.dp(c, 999), 0, 0));
            t.setTextColor(PUi.col(c, R.color.bn_on_banana));
        }
        return t;
    }

    private static LinearLayout.LayoutParams chipLp(Context c) {
        LinearLayout.LayoutParams lp = PUi.wrap();
        lp.leftMargin = PUi.dp(c, 6);
        lp.rightMargin = PUi.dp(c, 4);
        return lp;
    }

    private static TextView cardLine(Context c, CharSequence s) {
        TextView t = PUi.small(c, s);
        t.setPadding(PUi.dp(c, 58), 0, PUi.dp(c, 12), PUi.dp(c, 8));
        return t;
    }

    private static TextView note(Context c, CharSequence s) {
        TextView t = PUi.small(c, s);
        t.setPadding(PUi.dp(c, 4), 0, PUi.dp(c, 4), 0);
        return t;
    }

    // ---- The bottom screen ----

    private static void explainOnFocus(View v, Runnable explain) {
        v.setOnFocusChangeListener((x, has) -> { if (has) explain.run(); });
        v.setOnTouchListener((x, ev) -> { if (ev.getAction() == android.view.MotionEvent.ACTION_UP) explain.run(); return false; });
    }

    private static void explainShow(Activity a, boolean on, android.widget.CompoundButton sw) {
        BottomScreen.Explain e = new BottomScreen.Explain();
        e.icon = "controller";
        e.title = "Show in the game";
        e.place = "Mods & Cheats";
        e.text = "The Mods tab of the in-game panel, for this game. Off hides the tab; the cheats you turned on still run.";
        e.on = on;
        e.flip = sw::toggle;
        e.key = "mods-show";
        BottomScreen.explain(a, e);
    }

    private static void explainSource(Activity a, Model m, CheatSources.Block b, CheatSources.Source s, String name, boolean enabled, Set<String> other) {
        BottomScreen.Explain e = new BottomScreen.Explain();
        e.icon = b != null && b.id.startsWith("mod:") ? "mods" : "folder";
        e.title = name;
        e.place = "Mods & Cheats";
        CheatFile.Parsed p = b == null ? null : b.parsed;
        String t = p == null ? "None of its cheats are for " + m.versionWords() + ": " + count(other.size(), "file") + " for another version."
                : count(p.switchable(), "cheat") + " for " + m.versionWords() + "." + (p.credits.isEmpty() ? "" : " Credit: " + CheatFile.credit(p) + ".");
        e.text = t + (enabled ? "" : " It is off: its cheats are not in the game.") + (s != null ? " A tap turns it on or off, or removes it." : "");
        e.key = "mods-source:" + (b != null ? b.id : s != null ? s.id : name);
        BottomScreen.explain(a, e);
    }

    private static void explainCheat(Activity a, Model m, CheatFile.Cheat c, String key, boolean on, String source, String from) {
        BottomScreen.Explain e = new BottomScreen.Explain();
        e.key = "mods-cheat:" + key + ":" + on;
        String fromLine = "From " + source + (from.startsWith("From ") ? ", " + from.substring(5) : "");
        e.view = ctx -> explainView(ctx, c, on, fromLine);
        BottomScreen.explain(a, e);
    }

    /** The bottom screen's page of a cheat: its name, its chips, what it changes, where it is from, the buttons */
    static View explainView(Context c, CheatFile.Cheat ch, boolean on, String fromLine) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(PUi.dp(c, 22), PUi.dp(c, 20), PUi.dp(c, 22), PUi.dp(c, 20));
        TextView name = PUi.text(c, ch.name, 24, R.color.bn_text, 800);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(name);
        LinearLayout chips = PUi.row(c);
        chips.addView(chip(c, ch.kind.label, false));
        if (ch.holdLabel() != null) chips.addView(chip(c, ch.holdLabel(), true), chipLp(c));
        if (ch.switchable()) chips.addView(chip(c, on ? "On" : "Works at once", false), chipLp(c));
        PUi.add(box, chips, 12);
        TextView d = PUi.text(c, CheatFile.describe(ch), 16, R.color.bn_text, 400);
        d.setLineSpacing(0, 1.2f);
        PUi.add(box, d, 12);
        PUi.add(box, PUi.text(c, fromLine + ".", 14, R.color.bn_text_2, 400), 10);
        LinearLayout keys = PUi.row(c);
        if (ch.switchable()) keys.addView(key(c, "A", on ? "Turn off" : "Turn on"));
        keys.addView(key(c, "Y", "Details"));
        keys.addView(key(c, "X", "All off"));
        PUi.add(box, keys, 22);
        return box;
    }

    private static View key(Context c, String k, String what) {
        LinearLayout l = PUi.row(c);
        l.setPadding(0, 0, PUi.dp(c, 18), 0);
        TextView g = PUi.text(c, k, 12, R.color.bn_text, 700);
        g.setGravity(Gravity.CENTER);
        g.setIncludeFontPadding(false);
        g.setBackground(PUi.round(PUi.col(c, R.color.bn_tint), PUi.dp(c, 999), 0, 0));
        l.addView(g, new LinearLayout.LayoutParams(PUi.dp(c, 22), PUi.dp(c, 22)));
        TextView t = PUi.text(c, what, 14, R.color.bn_text_2, 400);
        t.setPadding(PUi.dp(c, 6), 0, 0, 0);
        l.addView(t);
        return l;
    }
}
