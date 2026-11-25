package dev.peanuts.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nxnative.android.R;

/**
 * Settings in ten sections (SettingsSections) instead of one long page. Upstream's MainActivity.buildSettings builds its rows as before; its last line
 * (PEANUTS hook) hands the column to {@link #lay}, which takes every row out of its card and puts it into its section's group, rows keeping their own
 * listeners (and the fields MainActivity and LibrarySettings keep them in, so their values still update). One section shows at a time:
 * <ul>
 * <li>A window 600 dp wide or more (the Thor's top screen, a tablet, a phone on its side): the sections in a sidebar at the left, each with its Peanuts icon,
 * the selected one on banana yellow; the section's rows at the right, in upstream's scrolling column.</li>
 * <li>A narrow phone: the section's page, with a button of three lines at its top that opens a drawer of the sections.</li>
 * </ul>
 * The section that shows is kept while the app runs, so Back from Advanced, a game's page or another screen comes back to it.
 */
public final class SettingsPage {
    private SettingsPage() {}

    static final int WIDE_DP = 600, SIDEBAR_DP = 196, PAGE_DP = 900;   // PAGE_DP: MainActivity.build's Ui.page width (PEANUTS: 900)

    private static String current = "library";   // the section that shows
    private static Activity owner;
    private static LinearLayout pane;            // upstream's column: the right side (wide) or the whole page (phone)
    private static boolean wide;
    private static boolean twoScreens;           // the device has a second screen: Screens' icon and tile say so (SettingsSections.icon, summary)
    private static final Map<View, String> rowIcons = new HashMap<>();   // the icon a row's tile shows now, for the rows whose icon follows their value
    private static boolean tablet;               // a tablet (its smaller side 600 dp or more): the sections as tiles (the hub), a section's page with Back
    private static boolean atHub = true;         // a tablet's Settings shows its tiles (again when Settings is opened from Home: homeBuilt)
    private static LinearLayout hubPage;
    private static final Map<String, LinearLayout> pages = new HashMap<>();   // each section's column, built once per lay()
    private static final List<View> entries = new ArrayList<>();              // the sidebar's entries (wide)
    private static Dialog drawer;

    /** One row Settings built, or a card of its own (About), with what followed it in its card (Library's line about keys and firmware) */
    private static final class Item {
        final View view;
        final String title, heading;
        final List<View> after = new ArrayList<>();
        Item(View view, String title, String heading) { this.view = view; this.title = title; this.heading = heading; }
    }

    /** MainActivity.buildSettings' last line (PEANUTS hook): its rows into the sections, and the section that was showing */
    public static void lay(Activity a, LinearLayout content) {
        if (owner != a) a.registerActivityLifecycleCallbacks(new Gone());   // the activity's own callbacks: they go with it
        dev.peanuts.mods.CheatsPage.left();   // Settings shows: a game's Mods & Cheats is closed
        GamePage.left();   // and a game's page opens on its tiles again
        owner = a;
        pane = content;
        tablet = a.getResources().getConfiguration().smallestScreenWidthDp >= WIDE_DP;
        wide = !tablet && a.getResources().getConfiguration().screenWidthDp >= WIDE_DP;
        twoScreens = dev.peanuts.Companion.hasSecondDisplay(a);
        rowIcons.clear();
        if (drawer != null) { drawer.dismiss(); drawer = null; }
        places.clear();
        lastFocus.clear();
        explained = null;
        buildTag = null;
        Map<String, Map<String, List<Item>>> placed = new LinkedHashMap<>();
        Map<String, List<Item>> loose = new HashMap<>();   // whole cards (About), by section
        for (Item it : collect(content)) {
            if (it.title == null) {
                String sec = SettingsSections.ofHeading(it.heading).key;
                if (sec.equals("about")) { buildTag = firstText(it.view); continue; }   // upstream's About card: About's own page has its three things
                loose.computeIfAbsent(sec, k -> new ArrayList<>()).add(it);
                continue;
            }
            SettingsSections.Place p = SettingsSections.place(it.title, it.heading);
            placed.computeIfAbsent(p.section.key, k -> new LinkedHashMap<>()).computeIfAbsent(p.group, k -> new ArrayList<>()).add(it);
            likeUpstream(it.view);
            places.put(it.view, new Explained(it.title, p));
            rowIcon(it.view);
        }
        content.removeAllViews();
        pages.clear();
        for (SettingsSections.Section s : SettingsSections.ALL)
            pages.put(s.key, column(a, s, placed.getOrDefault(s.key, new HashMap<>()), loose.getOrDefault(s.key, new ArrayList<>())));
        entries.clear();
        if (wide) sidebar(a, content);
        if (SettingsSections.section(current) == null) current = "library";
        hubPage = tablet ? hub(a) : null;
        if (tablet && atHub) showHub(false);
        else show(current, false);
        if (wide && !content.isInTouchMode()) content.post(() -> {   // a controller: the focus starts on the selected section (a page that keeps its place moves it on after)
            if (pane != content) return;
            for (View e : entries) if (e.isSelected()) { e.requestFocus(); break; }
        });
        bottom(a, content);
    }

    /**
     * The activity went (Back out of the app, a change outside its configChanges, Android ended it): nothing of its window stays in the fields above,
     * which until the next lay() held the activity and every row of Settings. What shows (current, atHub) is kept for the activity made again
     */
    private static final class Gone implements android.app.Application.ActivityLifecycleCallbacks {
        @Override public void onActivityDestroyed(Activity x) { forget(x); }
        @Override public void onActivityCreated(Activity x, android.os.Bundle b) {}
        @Override public void onActivityStarted(Activity x) {}
        @Override public void onActivityResumed(Activity x) {}
        @Override public void onActivityPaused(Activity x) {}
        @Override public void onActivityStopped(Activity x) {}
        @Override public void onActivitySaveInstanceState(Activity x, android.os.Bundle b) {}
    }

    /** Every view and window of a's Settings let go (Gone); nothing when a newer activity laid Settings out since */
    static void forget(Activity a) {
        if (a != owner) return;
        if (drawer != null) { try { drawer.dismiss(); } catch (RuntimeException ignored) { /* its window went with the activity */ } }
        if (search != null) { try { search.dismiss(); } catch (RuntimeException ignored) { /* the same */ } }
        drawer = null;
        search = null;
        owner = null;
        focusWatched = null;
        pane = null;
        hubPage = null;
        explained = null;
        buildTag = null;
        pages.clear();
        entries.clear();
        rowIcons.clear();
        places.clear();
        lastFocus.clear();
    }

    /** About how wide a section's page is, in dp: the page's width (Ui.page's, at most PAGE_DP) less its gutters, less the sidebar where there is one */
    static int paneDp(android.content.Context c) {
        int w = Math.min(c.getResources().getConfiguration().screenWidthDp, PAGE_DP) - 40;
        return wide ? w - SIDEBAR_DP - 18 : w;
    }

    /**
     * Home was built (BottomScreen.home, from MainActivity.buildHome): a tablet's Settings opens on the tiles of its sections next time. Nothing else
     * puts it back on them but Back from a section's page: Advanced, the GPU driver page or another screen over Settings, and Settings built again (a
     * value, a rotation, Back from Advanced), come back to the section they left
     */
    static void homeBuilt() { atHub = true; }

    /** The section that shows (its key: library, console, …), or "hub" while a tablet shows the tiles of the sections */
    public static String current() { return tablet && atHub ? "hub" : current; }

    /**
     * MainActivity.onBackPressed (PEANUTS hook): on a tablet, Back from a section's page goes to the tiles of the sections; true when it did. Elsewhere,
     * and from the tiles, false: Back goes on as it did (Advanced closes, Settings goes back to Home)
     */
    public static boolean onBack(Activity a) {
        if (dev.peanuts.mods.CheatsPage.onBack(a)) return true;   // a game's Mods & Cheats: back to the game's page
        if (GamePage.onBack(a)) return true;   // a game's page in areas: an area back to its tiles
        if (!tablet || atHub || a != owner || pane == null || !pane.isAttachedToWindow()) return false;
        toHub();
        return true;
    }

    private static void toHub() { showHub(true); }

    /** A tablet: the tiles of the sections in the pane, where the section's page was */
    private static void showHub(boolean animate) {
        if (hubPage == null || pane == null) return;
        atHub = true;
        if (hubPage.getParent() != pane) {
            pane.removeAllViews();
            if (hubPage.getParent() instanceof ViewGroup) ((ViewGroup) hubPage.getParent()).removeView(hubPage);
            pane.addView(hubPage, PUi.fill());
        }
        if (pane.getParent() instanceof ScrollView) ((ScrollView) pane.getParent()).scrollTo(0, 0);
        if (animate) Motion.enterPage(hubPage, 1);
        explained = null;
        if (owner != null) BottomScreen.explain(owner, null);
        if (animate && !pane.isInTouchMode()) pane.post(() -> {   // a controller: on the tile of the section it came from
            for (int i = 0; i < hubPage.getChildCount(); i++) {
                View f = tileOf(hubPage.getChildAt(i), current);
                if (f != null) { f.requestFocus(); return; }
            }
        });
        dev.peanuts.ctl.Ctl.screen();
    }

    private static boolean isIn(View v, View group) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) if (x == group) return true;
        return false;
    }

    private static View tileOf(View v, String key) {
        if (v.getTag() instanceof Entry && key.equals(((Entry) v.getTag()).key)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View t = tileOf(((ViewGroup) v).getChildAt(i), key); if (t != null) return t; }
        return null;
    }

    /**
     * A tablet's Settings opens on an overview of its sections, as a page of its own: Back to Home, "Settings" and the search, then the ten sections as
     * tiles across the screen, each its icon, its name and what it holds; a tile opens the section's page, and Back from there comes back to the tiles
     */
    private static LinearLayout hub(Activity a) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout h = PUi.row(a);
        h.setMinimumHeight(PUi.dp(a, 56));
        h.addView(PUi.back(a, v -> back(a)));
        TextView title = PUi.title(a, "Settings");
        title.setAccessibilityHeading(true);
        title.setPadding(PUi.dp(a, 14), 0, PUi.dp(a, 8), 0);
        h.addView(title, PUi.weight());
        h.addView(iconButton(a, "search", R.drawable.ic_search, "Search settings", v -> openSearch(a)));
        col.addView(h);
        int avail = Math.min(a.getResources().getConfiguration().screenWidthDp, PAGE_DP) - 40;
        int cols = Math.max(3, Math.min(5, avail / 160));
        LinearLayout row = null;
        int n = 0;
        for (SettingsSections.Section s : SettingsSections.ALL) {
            if (n % cols == 0) { row = PUi.row(a); row.setBaselineAligned(false); PUi.add(col, row, n == 0 ? 14 : 12); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (n % cols > 0) lp.setMarginStart(PUi.dp(a, 12));
            row.addView(tile(a, s), lp);
            n++;
        }
        for (int k = n % cols; k > 0 && k < cols; k++) {   // the last row's empty places keep the tiles' width
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);   // as the tiles (a fixed height would set the row's); a Space asks for no height of its own
            lp.setMarginStart(PUi.dp(a, 12));
            row.addView(new android.widget.Space(a), lp);
        }
        return col;
    }

    /** One section's tile: a card with its icon, its name and its line; a tap or A opens the section */
    private static View tile(Activity a, SettingsSections.Section s) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setTag(new Entry(s.key, true));
        t.setMinimumHeight(PUi.dp(a, 148));
        int p = PUi.dp(a, 16);
        t.setPadding(p, p, p, p);
        t.setBackground(PUi.ripple(a, PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 20), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1))));
        ImageView ic = new ImageView(a);
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ic.setImageDrawable(Icons.get(a, icon(s), Icons.Ink.NORMAL));
        t.addView(ic, new LinearLayout.LayoutParams(PUi.dp(a, 30), PUi.dp(a, 30)));
        TextView name = PUi.text(a, s.label, 17, R.color.bn_text, 700);
        PUi.add(t, name, 12);
        TextView line = PUi.text(a, SettingsSections.summary(s, twoScreens), 14, R.color.bn_text_2, 400);
        line.setMaxLines(4);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        PUi.add(t, line, 4);
        t.setClickable(true);
        t.setFocusable(true);
        t.setContentDescription(s.label + ". " + SettingsSections.summary(s, twoScreens));
        t.setOnClickListener(v -> {
            Haptics.play(v, Haptics.Word.CLICK);
            show(s.key, true);
            LinearLayout page = pages.get(s.key);
            if (page != null && !page.isInTouchMode()) page.post(() -> {   // a controller: on the section's first row (its tile has gone with the tiles)
                for (View f : page.getFocusables(View.FOCUS_FORWARD)) if (!isIn(f, page.getChildAt(0))) { f.requestFocus(); return; }
            });
        });
        return t;
    }

    /**
     * The control interface's `nav to=settings section=<name>`: the section of that name (a section, one of its groups, or one of the old long page's
     * headings) shows, scrolled to the group when it names one. False when no section has that name or Settings is not built
     */
    public static boolean go(Activity a, String name) {
        SettingsSections.Place p = SettingsSections.find(name);
        if (p == null || a != owner || pane == null || !pane.isAttachedToWindow()) return false;
        atHub = false;
        show(p.section.key, false);
        if (p.group != null) scrollToGroup(p.group);
        return true;
    }

    // ---- The rows ----

    /** The rows of upstream's column in order, each with the heading it was under; the header and the loose lines between the cards are left out */
    private static List<Item> collect(LinearLayout content) {
        List<Item> out = new ArrayList<>();
        String heading = null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            if (v instanceof TextView && v.isAccessibilityHeading()) { heading = String.valueOf(((TextView) v).getText()).trim(); continue; }
            if (!(v instanceof LinearLayout) || ((LinearLayout) v).getOrientation() != LinearLayout.VERTICAL) continue;   // the header (a row), the hint line
            LinearLayout card = (LinearLayout) v;
            List<View> kids = new ArrayList<>();
            for (int k = 0; k < card.getChildCount(); k++) kids.add(card.getChildAt(k));
            boolean rows = false;
            for (View k : kids) if (title(k) != null) rows = true;
            if (!rows) { out.add(new Item(card, null, heading)); continue; }
            card.removeAllViews();
            Item last = null;
            for (View k : kids) {
                String t = title(k);
                if (t != null) { last = new Item(k, t, heading); out.add(last); }
                else if (last != null) last.after.add(k);
            }
        }
        return out;
    }

    /** The title of a settings row (Ui.Setting / PUi.Setting: its tile, then a column whose first line is the title), else null */
    static String title(View v) {
        if (!(v instanceof LinearLayout) || ((LinearLayout) v).getOrientation() != LinearLayout.HORIZONTAL) return null;
        LinearLayout r = (LinearLayout) v;
        if (r.getChildCount() < 2 || !(r.getChildAt(0) instanceof TextView) || !(r.getChildAt(1) instanceof LinearLayout)) return null;
        LinearLayout col = (LinearLayout) r.getChildAt(1);
        if (col.getOrientation() != LinearLayout.VERTICAL || col.getChildCount() == 0 || !(col.getChildAt(0) instanceof TextView)) return null;
        return String.valueOf(((TextView) col.getChildAt(0)).getText()).trim();
    }

    /**
     * A row of Peanuts' own (PUi.Setting: Library's rows, from an older copy of upstream's Ui with a 40 dp tile, 60 dp high, a 16 sp title, two lines of value) in the measures of
     * upstream's rows it now shares a card with (Ui.Setting: a 36 dp tile, 52 dp high, a 15 sp title, the value whole). Upstream's rows are left as they are
     */
    private static void likeUpstream(View row) {
        LinearLayout r = (LinearLayout) row;
        android.content.Context c = r.getContext();
        View tile = r.getChildAt(0);
        ViewGroup.LayoutParams lp = tile.getLayoutParams();
        int tileDp = PUi.dp(c, 36);
        if (lp == null || lp.width <= tileDp) return;
        lp.width = lp.height = tileDp;
        tile.setLayoutParams(lp);
        tile.setBackground(PUi.round(PUi.col(c, R.color.bn_tint), PUi.dp(c, 11), 0, 0));
        ((TextView) tile).setTextSize(17);
        r.setPadding(PUi.dp(c, 10), PUi.dp(c, 7), PUi.dp(c, 10), PUi.dp(c, 7));
        r.setMinimumHeight(PUi.dp(c, 52));
        r.setBackground(PUi.ripple(c, PUi.round(Color.TRANSPARENT, PUi.dp(c, 12), 0, 0)));
        LinearLayout col = (LinearLayout) r.getChildAt(1);
        col.setPadding(PUi.dp(c, 12), 0, PUi.dp(c, 8), 0);
        ((TextView) col.getChildAt(0)).setTextSize(15);
        if (col.getChildCount() > 1 && col.getChildAt(1) instanceof TextView) ((TextView) col.getChildAt(1)).setMaxLines(8);   // its value whole, as upstream's (PUi cuts at two lines)
    }

    /** A section's icon on this device (Screens: one screen or two) */
    private static String icon(SettingsSections.Section s) { return SettingsSections.icon(s, twoScreens); }

    /** The icon of a row as it is set now (SettingsSections.icon: from its value line or its switch), the section's for a row the table does not know */
    private static String iconNow(View row) {
        Explained x = places.get(row);
        if (x == null) return null;
        TextView v = value(row);
        android.widget.Switch sw = toggle(row);
        String i = SettingsSections.icon(x.place, x.title, v != null ? v.getText() : null, sw != null ? sw.isChecked() : null);
        return i != null ? i : icon(x.place.section);
    }

    /** The row's tile shows its icon as the row is set now; again when that changes (its value line, its switch), only when the icon is another */
    private static void rowIcon(View row) {
        Explained x = places.get(row);
        if (x == null || x.place.icon == null) return;   // a row the table does not know keeps its emoji
        String i = iconNow(row);
        if (i.equals(rowIcons.get(row))) return;
        rowIcons.put(row, i);
        tileIcon(row, i);
    }

    /** The row's emoji tile shows its Peanuts icon instead (the emoji stays when this build has no icons) */
    private static void tileIcon(View row, String icon) {
        View t = ((LinearLayout) row).getChildAt(0);
        if (t instanceof TextView) Icons.tile((TextView) t, icon);
    }

    // ---- A section's page ----

    private static CharSequence buildTag;   // the build tag of upstream's About card (the first line of every log)

    /** The first text of a card (upstream's About card: its build tag chip) */
    private static CharSequence firstText(View v) {
        if (v instanceof TextView) return ((TextView) v).getText();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { CharSequence t = firstText(((ViewGroup) v).getChildAt(i)); if (t != null) return t; }
        return null;
    }

    /** The row of this title presses its own action (About's Check for updates and Questions and answers are the App and Help rows') */
    private static void press(String title) {
        for (Map.Entry<View, Explained> e : places.entrySet()) if (e.getValue().title.equals(title)) { e.getKey().performClick(); return; }
    }

    private static LinearLayout column(Activity a, SettingsSections.Section s, Map<String, List<Item>> groups, List<Item> loose) {
        LinearLayout col = new Page(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(top(a, s));
        if (!wide) PUi.add(col, line(a, s.line, 13), 4);
        if (s.key.equals("about")) { AboutPage.fill(a, col, wide || tablet, buildTag, () -> press("Check for updates"), () -> press("Questions and answers")); return col; }   // a tablet: About in two columns, as on a wide screen
        if (s.key.equals("users")) { dev.peanuts.users.UsersPage.fill(a, col, wide || tablet); return col; }   // the users as cards, Who's playing? and the Miis (dev.peanuts.users); a tablet: four a row
        boolean first = true;
        for (String label : SettingsSections.groupOrder(s)) {
            List<Item> items = groups.get(label);
            if (items == null || items.isEmpty()) continue;
            items.sort((x, y) -> Integer.compare(SettingsSections.order(s, label, x.title), SettingsSections.order(s, label, y.title)));   // stable: unknown rows keep their order
            PUi.add(col, PUi.section(a, label), first ? 14 : 18);
            LinearLayout card = PUi.add(col, PUi.listCard(a), 6);
            for (Item it : items) {
                card.addView(it.view);
                for (View v : it.after) card.addView(v);
            }
            first = false;
        }
        for (Item it : loose) PUi.add(col, it.view, first ? 14 : 18);
        return col;
    }

    /** The top of a section's page: wide, its title over its line; a phone, the menu button, the title over "Settings" */
    private static View top(Activity a, SettingsSections.Section s) {
        LinearLayout h = PUi.row(a);
        h.setMinimumHeight(PUi.dp(a, wide ? 48 : 56));   // wide: level with the sidebar's Back and "Settings"
        if (!wide) h.addView(tablet ? PUi.back(a, v -> toHub()) : iconButton(a, "menu", R.drawable.ic_more, "Sections of Settings", v -> openDrawer(a)));
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(PUi.dp(a, wide ? 4 : 14), 0, PUi.dp(a, 8), 0);
        TextView title = wide ? PUi.heading(a, s.label) : PUi.text(a, s.label, 22, R.color.bn_text, 800);
        title.setAccessibilityHeading(true);
        t.addView(title);
        t.addView(wide ? line(a, s.line, 13) : line(a, "Settings", 12));
        h.addView(t, PUi.weight());
        h.addView(iconButton(a, "search", R.drawable.ic_search, "Search settings", v -> openSearch(a)));
        return h;
    }

    private static TextView line(Activity a, String s, float sp) {
        TextView t = PUi.text(a, s, sp, R.color.bn_text_2, 400);
        t.setPadding(PUi.dp(a, 2), PUi.dp(a, 1), 0, 0);
        return t;
    }

    /** A square icon button of the app (Ui.iconButton) with a Peanuts icon in it; fallback, the app's own drawable */
    private static ImageView iconButton(Activity a, String icon, int fallback, String description, View.OnClickListener l) {
        ImageView b = PUi.iconButton(a, fallback, description, PUi.Kind.TONAL, l);
        Drawable d = Icons.get(a, icon, Icons.Ink.NORMAL);
        if (d != null) b.setImageDrawable(d);
        return b;
    }

    /** Another section shows: its page in the pane, scrolled to its top; animate when the player picked it */
    private static void show(String key, boolean animate) {
        LinearLayout page = pages.get(key);
        if (page == null || pane == null) return;
        boolean changed = !key.equals(current) || (tablet && atHub);   // another section opens at its top; the same one built again stays where it was
        current = key;
        atHub = false;
        if (page.getParent() != pane) {
            pane.removeAllViews();
            if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
            pane.addView(page, PUi.fill());
        }
        for (View e : entries) style(e, e.getTag() instanceof Entry && key.equals(((Entry) e.getTag()).key));
        if (changed && pane.getParent() instanceof ScrollView) ((ScrollView) pane.getParent()).scrollTo(0, 0);
        if (animate) Motion.enterPage(page, 1);
        if (changed) explainSection(key);
        dev.peanuts.ctl.Ctl.screen();
    }

    private static void picked(View v, String key) {
        if (key.equals(current)) return;
        Haptics.play(v, Haptics.Word.CLICK);
        show(key, true);
    }

    /** The group's label scrolled to the top of the pane (the control interface) */
    private static void scrollToGroup(String group) {
        LinearLayout page = pages.get(current);
        if (page == null || !(pane.getParent() instanceof ScrollView)) return;
        ScrollView sv = (ScrollView) pane.getParent();
        for (int i = 0; i < page.getChildCount(); i++) {
            View v = page.getChildAt(i);
            if (v instanceof TextView && v.isAccessibilityHeading() && group.equalsIgnoreCase(String.valueOf(((TextView) v).getText()).trim())) {
                sv.post(() -> {
                    android.graphics.Rect r = new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight());
                    sv.offsetDescendantRectToMyCoords(v, r);
                    sv.scrollTo(0, Math.max(0, r.top - PUi.dp(sv.getContext(), 8)));
                });
                return;
            }
        }
    }

    // ---- The sections' entries ----

    /** An entry's tag: its section, and whether it is the drawer's bigger kind */
    private static final class Entry {
        final String key;
        final boolean big;
        Entry(String key, boolean big) { this.key = key; this.big = big; }
    }

    /** One section's entry: its icon and label; the selected one on banana yellow (no bar at its edge) */
    private static View entry(Activity a, SettingsSections.Section s, boolean big, View.OnClickListener l) {
        LinearLayout e = PUi.row(a);
        e.setTag(new Entry(s.key, big));
        e.setMinimumHeight(PUi.dp(a, big ? 48 : 34));
        int pad = PUi.dp(a, big ? 14 : 10);
        e.setPadding(pad, 0, pad, 0);
        ImageView ic = new ImageView(a);
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = PUi.dp(a, big ? 22 : 20);
        e.addView(ic, new LinearLayout.LayoutParams(size, size));
        TextView t = PUi.text(a, s.label, big ? 15 : 14, R.color.bn_text, 600);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = PUi.weight();
        tp.setMarginStart(PUi.dp(a, big ? 14 : 10));
        e.addView(t, tp);
        e.setFocusable(true);
        e.setClickable(true);
        e.setContentDescription(s.label);
        e.setOnClickListener(l);
        style(e, s.key.equals(current));
        return e;
    }

    private static void style(View e, boolean on) {
        Activity a = owner;
        if (a == null || !(e instanceof LinearLayout)) return;
        LinearLayout l = (LinearLayout) e;
        if (!(l.getTag() instanceof Entry)) return;
        Entry tag = (Entry) l.getTag();
        boolean big = tag.big;
        float r = PUi.dp(a, big ? 14 : 11);
        l.setBackground(on ? PUi.round(PUi.col(a, R.color.bn_banana), r, 0, 0) : PUi.ripple(a, PUi.round(Color.TRANSPARENT, r, 0, 0)));
        l.setSelected(on);
        SettingsSections.Section s = SettingsSections.section(tag.key);
        ((ImageView) l.getChildAt(0)).setImageDrawable(s == null ? null : Icons.get(a, icon(s), on ? Icons.Ink.ON_BANANA : big ? Icons.Ink.NORMAL : Icons.Ink.QUIET));
        TextView t = (TextView) l.getChildAt(1);
        t.setTextColor(PUi.col(a, on ? R.color.bn_on_banana : big ? R.color.bn_text : R.color.bn_text_2));
        t.setTypeface(PUi.face(on ? 700 : 600));
    }

    // ---- Wide: the sidebar ----

    /** The sidebar at the left of upstream's scrolling column, in the same width and gutter (Ui.page's): Back and "Settings", then the entries in a card */
    private static void sidebar(Activity a, LinearLayout content) {
        if (!(content.getParent() instanceof ScrollView) || !(content.getParent().getParent() instanceof FrameLayout)) return;
        ScrollView scroll = (ScrollView) content.getParent();
        FrameLayout frame = (FrameLayout) scroll.getParent();
        int at = frame.indexOfChild(scroll);
        View scrim = at + 1 < frame.getChildCount() ? frame.getChildAt(at + 1) : null;   // Ui.page's scrim under the status bar, above both
        ScrollView side = new Side(a);
        side.setClipToPadding(false);
        side.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout h = PUi.row(a);
        h.setMinimumHeight(PUi.dp(a, 48));
        h.addView(PUi.back(a, v -> back(a)));
        TextView title = PUi.title(a, "Settings");
        title.setAccessibilityHeading(true);
        title.setPadding(PUi.dp(a, 12), 0, 0, 0);
        h.addView(title, PUi.weight());
        col.addView(h);
        LinearLayout card = PUi.listCard(a);
        int p = PUi.dp(a, 5);
        card.setPadding(p, p, p, p);
        for (SettingsSections.Section s : SettingsSections.ALL) {
            View e = entry(a, s, false, v -> picked(v, s.key));
            entries.add(e);
            PUi.add(card, e, 1);
        }
        PUi.add(col, card, 6);
        side.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(PUi.dp(a, SIDEBAR_DP), ViewGroup.LayoutParams.MATCH_PARENT);
        frame.addView(side, at + 1, sp);
        scroll.setOnApplyWindowInsetsListener((v, in) -> {   // Ui.page's gutter and width, the sidebar in its left part
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int w = a.getResources().getDisplayMetrics().widthPixels;
            int gutter = PUi.dp(a, a.getResources().getConfiguration().screenWidthDp < 360 ? 16 : 20);
            int edge = Math.max(gutter, (w - i.left - i.right - PUi.dp(a, PAGE_DP)) / 2);
            v.setPadding(edge + i.left + PUi.dp(a, SIDEBAR_DP + 18), i.top + PUi.dp(a, 8), edge + i.right, i.bottom + PUi.dp(a, 24));
            side.setPadding(0, i.top + PUi.dp(a, 8), 0, i.bottom + PUi.dp(a, 16));
            if (sp.leftMargin != edge + i.left) { sp.leftMargin = edge + i.left; side.setLayoutParams(sp); }
            if (scrim != null && scrim.getLayoutParams() != null && scrim.getLayoutParams().height != i.top) { scrim.getLayoutParams().height = i.top; scrim.requestLayout(); }
            return in;
        });
        frame.requestApplyInsets();
    }

    // ---- The D-pad between the sidebar and the page (wide) ----

    private static final Map<String, View> lastFocus = new HashMap<>();   // the view the focus was on last in each section's page

    /**
     * A section's page. Wide, the D-pad stays in it going up and down: Android's own search goes to whatever is nearest in that direction, and where the
     * page has nothing more below (or above) that was an entry of the sidebar beside it. Down from a tab row (About's Overview, Games, ...) goes to the
     * tab's first item. Left from the page goes to the selected section's entry, not to the entry that happens to be at the same height
     */
    private static final class Page extends LinearLayout {
        Page(android.content.Context c) { super(c); }

        @Override public View focusSearch(View focused, int dir) {
            View next = super.focusSearch(focused, dir);
            if (!wide || focused == null || !isIn(focused, this)) return next;
            if (dir == FOCUS_DOWN && inTabs(focused)) {
                View d = byDefault(this);
                if (d != null && d != focused) return d;
            }
            if (next != null && isIn(next, this)) return next;
            if (dir == FOCUS_LEFT) {
                View e = selectedEntry();
                return e != null ? e : next;
            }
            if (dir == FOCUS_UP || dir == FOCUS_DOWN) {
                View in = android.view.FocusFinder.getInstance().findNextFocus(this, focused, dir);
                return in != null ? in : focused;
            }
            return next;
        }
    }

    /** The sidebar: right from an entry goes into the page (where the focus was last, else its first item); up and down stay in the sidebar */
    private static final class Side extends ScrollView {
        Side(android.content.Context c) { super(c); }

        @Override public View focusSearch(View focused, int dir) {
            View next = super.focusSearch(focused, dir);
            if (focused == null || !isIn(focused, this)) return next;
            if (dir == FOCUS_RIGHT && focused.getTag() instanceof Entry) {
                View t = pageTarget();
                if (t != null) return t;
            }
            if ((dir == FOCUS_UP || dir == FOCUS_DOWN) && next != null && !isIn(next, this)) return focused;
            return next;
        }
    }

    private static View selectedEntry() {
        for (View e : entries) if (e.isSelected() && e.isShown()) return e;
        return null;
    }

    /** Where right from the sidebar lands in the page that shows: where the focus was last, else the item the page marks as its first (About: Check for
     *  updates), else the first one under the page's title */
    private static View pageTarget() {
        LinearLayout page = pages.get(current);
        if (page == null || !page.isShown()) return null;
        View last = lastFocus.get(current);
        if (last != null && last.isShown() && last.isFocusable() && isIn(last, page)) return last;
        View d = byDefault(page);
        if (d != null) return d;
        for (int i = 1; i < page.getChildCount(); i++) {   // child 0 is the page's title row (its search button)
            View f = firstClickable(page.getChildAt(i));
            if (f != null) return f;
        }
        return null;
    }

    /** The view under v that says it takes the focus first (View.setFocusedByDefault), not hidden */
    static View byDefault(View v) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v.isFocusedByDefault() && v.isFocusable()) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = byDefault(((ViewGroup) v).getChildAt(i));
            if (f != null) return f;
        }
        return null;
    }

    /** The first view under v, in the order of the page, that can be focused and pressed (a selectable text is not one), not hidden (v may not be
     *  in a window yet: About marks its first item while it builds the tab) */
    static View firstClickable(View v) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v.isFocusable() && v.isClickable()) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = firstClickable(((ViewGroup) v).getChildAt(i));
            if (f != null) return f;
        }
        return null;
    }

    private static boolean inTabs(View v) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) if (x instanceof PUi.Segmented) return true;
        return false;
    }

    @SuppressWarnings("deprecation")   // MainActivity's own Back (onBackPressed): Settings goes back to Home
    private static void back(Activity a) { a.onBackPressed(); }

    // ---- The second display: what the focus is on (BottomScreen.explain) ----

    /** A row's title and place, for its explanation */
    private static final class Explained {
        final String title;
        final SettingsSections.Place place;
        Explained(String title, SettingsSections.Place place) { this.title = title; this.place = place; }
    }

    private static final Map<View, Explained> places = new HashMap<>();   // the rows of this lay(), by their view
    private static View explained;                                           // the row the second display explains now
    private static boolean explainedOn;                                      // its switch's state when it was explained
    private static Activity focusWatched;
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * The second display follows Settings: the section that shows, or the row the D-pad is on (or that was tapped), with what it does, its value now and the
     * app default. A row's value that changes (after its dialog, a switch flipped on either screen) updates it in place. Leaving Settings (Home, Advanced)
     * gives the display back to its logo
     */
    private static void bottom(Activity a, LinearLayout content) {
        if (focusWatched != a) {
            focusWatched = a;
            a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> focused(now));
        }
        for (View row : places.keySet()) {
            TextView v = value(row);
            if (v != null) v.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence x, int st, int c, int af) {}
                @Override public void onTextChanged(CharSequence x, int st, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable x) { rowIcon(row); if (explained == row) explainRow(row); }
            });
            android.widget.Switch sw = toggle(row);
            if (sw != null && SettingsSections.followsValue(places.get(row).title)) sw.setForeground(new Watch(() -> rowIcon(row)));   // a switch changes no text
            row.setOnTouchListener((x, e) -> { if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) explainRow(row); return false; });
        }
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                content.removeOnAttachStateChangeListener(this);
                // Settings left (no new lay): Home, or Advanced over it. The section stays: Back from Advanced builds Settings again on it, and only Home's
                // build (homeBuilt) puts a tablet's Settings on its tiles again
                UI.post(() -> { if (pane == content && !content.isAttachedToWindow()) { explained = null; BottomScreen.explain(a, null); } });
            }
        });
        content.post(() -> { if (pane == content && !focusedRow(a.getCurrentFocus())) explainSection(current); });
    }

    /**
     * Draws nothing; tells when the view's state changes (a switch's foreground: it gets the switch's checked state, from a tap, A or the bottom screen
     * alike), where a CompoundButton takes one listener only and upstream's has it
     */
    private static final class Watch extends Drawable {
        private final Runnable changed;
        private boolean checked, known;
        Watch(Runnable changed) { this.changed = changed; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) {
            boolean c = false;
            for (int x : state) if (x == android.R.attr.state_checked) c = true;
            if (known && c != checked) UI.post(changed);
            checked = c;
            known = true;
            return false;
        }
        @Override public void draw(android.graphics.Canvas canvas) {}
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSPARENT; }
    }

    /** The focus moved in the launcher's window: a row or a section's entry is explained */
    private static void focused(View now) {
        if (now == null || pane == null || !pane.isAttachedToWindow()) return;
        LinearLayout page = pages.get(current);
        if (wide && page != null && isIn(now, page)) lastFocus.put(current, now);   // right from the sidebar comes back here
        focusedRow(now);
    }

    private static boolean focusedRow(View now) {
        for (View v = now; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (places.containsKey(v)) { explainRow(v); return true; }
            if (v.getTag() instanceof Entry) { explained = null; explainSection(((Entry) v.getTag()).key); return true; }
        }
        return false;
    }

    /** The row's value line (Ui.Setting: the second line of its column), or null */
    private static TextView value(View row) {
        if (!(row instanceof LinearLayout) || ((LinearLayout) row).getChildCount() < 2 || !(((LinearLayout) row).getChildAt(1) instanceof LinearLayout)) return null;
        LinearLayout col = (LinearLayout) ((LinearLayout) row).getChildAt(1);
        return col.getChildCount() > 1 && col.getChildAt(1) instanceof TextView ? (TextView) col.getChildAt(1) : null;
    }

    /** The row's switch (Ui.Setting.toggle: its last view), or null */
    private static android.widget.Switch toggle(View row) {
        if (!(row instanceof LinearLayout)) return null;
        LinearLayout r = (LinearLayout) row;
        View last = r.getChildAt(r.getChildCount() - 1);
        return last instanceof android.widget.Switch ? (android.widget.Switch) last : null;
    }

    private static void explainRow(View row) {
        Activity a = owner;
        Explained x = places.get(row);
        if (a == null || x == null) return;
        BottomScreen.Explain e = new BottomScreen.Explain();
        e.key = row;
        e.icon = iconNow(row);
        e.title = x.title;
        e.place = SettingsHelp.place(x.place.section);
        SettingsHelp.Help h = SettingsHelp.of(x.title);
        e.text = h != null ? h.text : "";
        e.deflt = h != null ? h.deflt : null;
        TextView v = value(row);
        e.value = v != null && v.getVisibility() == View.VISIBLE ? v.getText() : null;
        android.widget.Switch sw = toggle(row);
        if (sw != null) {
            e.on = sw.isChecked();
            e.flip = () -> { if (sw.isEnabled()) { sw.toggle(); explainRow(row); } };
            explainedOn = sw.isChecked();
        }
        boolean fresh = explained != row;
        explained = row;
        BottomScreen.explain(a, e);
        if (sw != null && fresh) UI.postDelayed(new Runnable() {   // a switch flipped on the top screen (A, a tap) changes no text: looked at while it is explained
            @Override public void run() {
                if (explained != row || !row.isAttachedToWindow()) return;
                if (sw.isChecked() != explainedOn) explainRow(row);
                UI.postDelayed(this, 250);
            }
        }, 250);
    }

    /** A section: its line, and each of its rows with its value as it is now */
    private static void explainSection(String key) {
        Activity a = owner;
        SettingsSections.Section s = SettingsSections.section(key);
        if (a == null || s == null) return;
        BottomScreen.Explain e = new BottomScreen.Explain();
        e.key = s.key;
        e.icon = icon(s);
        e.title = s.label;
        if (s.key.equals("about")) e.cracked = Cracked.total(a);   // About: Peanuts cracked, big
        e.place = "Settings";
        e.text = s.line;
        e.rows = new ArrayList<>();
        LinearLayout page = pages.get(key);
        if (page != null) for (int i = 0; i < page.getChildCount(); i++) {
            if (!(page.getChildAt(i) instanceof LinearLayout)) continue;
            LinearLayout card = (LinearLayout) page.getChildAt(i);
            for (int k = 0; k < card.getChildCount(); k++) {
                View r = card.getChildAt(k);
                Explained x = places.get(r);
                if (x == null || r.getVisibility() != View.VISIBLE) continue;
                android.widget.Switch sw = toggle(r);
                TextView v = value(r);
                CharSequence shown = sw != null ? (sw.isChecked() ? "On" : "Off") : v != null && v.getVisibility() == View.VISIBLE ? v.getText() : "";
                e.rows.add(new CharSequence[] {x.title, shown});
            }
        }
        explained = null;
        BottomScreen.explain(a, e);
    }

    // ---- One search over every setting ----

    private static android.app.AlertDialog search;

    /**
     * The search, as a sheet of the app (Back and B close it): a field, then the rows of every section that match (Advanced's rule, SettingsSearch), then
     * Advanced's own rows that match. A row of Settings jumps to its place; one of Advanced's opens Advanced with the words typed in
     */
    private static void openSearch(Activity a) {
        if (search != null && search.isShowing()) return;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        android.widget.EditText q = new android.widget.EditText(a);
        q.setHint("Search every setting");
        q.setTextSize(15);
        q.setSingleLine(true);
        q.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);   // landscape (the Thor): the keyboard beside the results, not over them
        q.setOnEditorActionListener((v, id, ev) -> {   // the keyboard's search key: the keyboard goes, the results show (a landscape screen has room for one of them)
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;   // a hardware keyboard
            if (id != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && !enter) return false;
            android.view.inputmethod.InputMethodManager im = a.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        box.addView(q, PUi.fill());
        LinearLayout results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        PUi.add(box, results, 4);
        android.app.AlertDialog d = PUi.sheet(a, "Search settings", box).setNegativeButton("Close", null).create();
        q.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence x, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable x) { results(a, d, results, x.toString()); }
        });
        results(a, d, results, "");
        d.setOnKeyListener((x, k, e) -> {   // B closes it, as a dialog of the app
            if (k != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) x.cancel();
            return true;
        });
        d.setOnDismissListener(x -> { if (search == d) search = null; });
        Window w = d.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            Motion.sheet(d);
        }
        search = d;
        d.show();
        if (w != null) watchFocus(w);
        q.requestFocus();
    }

    /** The results for the words typed so far */
    private static void results(Activity a, android.app.AlertDialog d, LinearLayout out, String query) {
        out.removeAllViews();
        if (SettingsSearch.words(query).length == 0) {
            PUi.add(out, PUi.small(a, "A row's name, a word of what it does or its value. Advanced's switches are found too."), 8);
            return;
        }
        List<View> rows = new ArrayList<>();
        List<String> titles = new ArrayList<>(), texts = new ArrayList<>();
        for (SettingsSections.Section s : SettingsSections.ALL) {   // in the order Settings shows them
            LinearLayout page = pages.get(s.key);
            if (page == null) continue;
            for (int i = 0; i < page.getChildCount(); i++) {
                if (!(page.getChildAt(i) instanceof LinearLayout)) continue;
                LinearLayout card = (LinearLayout) page.getChildAt(i);
                for (int k = 0; k < card.getChildCount(); k++) {
                    View r = card.getChildAt(k);
                    Explained x = places.get(r);
                    if (x == null || r.getVisibility() != View.VISIBLE) continue;
                    TextView v = value(r);
                    rows.add(r);
                    titles.add(x.title);
                    texts.add(SettingsSearch.text(x.title, s.label, SettingsSections.MORE.equals(x.place.group) ? null : x.place.group, v == null ? null : v.getText()));
                }
            }
        }
        List<Integer> hits = SettingsSearch.find(titles, texts, query);
        if (!hits.isEmpty()) {
            PUi.add(out, PUi.section(a, "Settings"), 12);
            LinearLayout card = PUi.add(out, PUi.listCard(a), 6);
            for (int i : hits) {
                View row = rows.get(i);
                Explained x = places.get(row);
                String where = SettingsSections.MORE.equals(x.place.group) ? x.place.section.label : x.place.section.label + ", " + x.place.group;
                PUi.Setting r = new PUi.Setting(a, "", x.title, v -> { d.dismiss(); jump(row); }).value(where).chevron(a);
                likeUpstream(r.view);
                tileIcon(r.view, iconNow(row));
                card.addView(r.view);
            }
        }
        List<AdvancedPort.Hit> adv = AdvancedPort.find(a, query);
        if (!adv.isEmpty()) {
            PUi.add(out, PUi.section(a, "Advanced"), 16);
            LinearLayout card = PUi.add(out, PUi.listCard(a), 6);
            int shown = Math.min(adv.size(), 5);
            for (int i = 0; i < shown; i++) {
                AdvancedPort.Hit h = adv.get(i);
                PUi.Setting r = new PUi.Setting(a, "", h.title, v -> { d.dismiss(); AdvancedPort.open(a, query); })
                        .value(h.tryIf.isEmpty() ? null : "Try this if " + h.tryIf + ".").chevron(a);
                likeUpstream(r.view);
                tileIcon(r.view, "settings");
                card.addView(r.view);
            }
            if (adv.size() > shown) {
                PUi.Setting more = new PUi.Setting(a, "", "All " + adv.size() + " in Advanced", v -> { d.dismiss(); AdvancedPort.open(a, query); }).chevron(a);
                likeUpstream(more.view);
                tileIcon(more.view, "search");
                card.addView(more.view);
            }
        }
        if (hits.isEmpty() && adv.isEmpty()) PUi.add(out, PUi.small(a, "Nothing matches. Try another word, or a word of what the setting does."), 8);
    }

    /** A row found by the search: its section shows, scrolled to the row, the focus on it (a controller) or a calm highlight (touch), and it is explained */
    private static void jump(View row) {
        Explained x = places.get(row);
        if (x == null || pane == null) return;
        show(x.place.section.key, false);
        pane.post(() -> {
            if (!row.isAttachedToWindow() || !(pane.getParent() instanceof ScrollView)) return;
            ScrollView sv = (ScrollView) pane.getParent();
            android.graphics.Rect r = new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight());
            sv.offsetDescendantRectToMyCoords(row, r);
            sv.smoothScrollTo(0, Math.max(0, r.top - PUi.dp(row.getContext(), 48)));
            if (!row.isInTouchMode()) row.requestFocus();
            else flash(row);
            explainRow(row);
        });
    }

    /** The row found lights up in banana yellow and fades out (Motion.EMPHASIS twice); under reduced motion it shows and goes */
    private static void flash(View row) {
        android.content.Context c = row.getContext();
        GradientDrawable g = PUi.round((PUi.col(c, R.color.bn_banana) & 0x00FFFFFF) | 0x40000000, PUi.dp(c, 12), 0, 0);
        row.setForeground(g);
        if (!Motion.on()) { row.postDelayed(() -> { if (row.getForeground() == g) row.setForeground(null); }, 1200); return; }
        android.animation.ObjectAnimator f = android.animation.ObjectAnimator.ofInt(g, "alpha", 255, 0);
        f.setStartDelay(Motion.EMPHASIS);
        f.setDuration(Motion.EMPHASIS * 2);
        f.setInterpolator(Motion.IN);
        f.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) { if (row.getForeground() == g) row.setForeground(null); }
        });
        f.start();
    }

    /** The app's focus frame in a window of Peanuts' own (PadFocus.watch, package-private) */
    private static void watchFocus(Window w) {
        try {
            java.lang.reflect.Method m = Class.forName("dev.nxnative.android.PadFocus").getDeclaredMethod("watch", Window.class);
            m.setAccessible(true);
            m.invoke(null, w);
        } catch (ReflectiveOperationException ignored) { /* touch still works */ }
    }

    // ---- A phone: the drawer ----

    /** The drawer of the sections, over the page from the left: the app's badge, "Settings" and the version, a close button, the entries */
    private static void openDrawer(Activity a) {
        if (drawer != null && drawer.isShowing()) return;
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        int r = PUi.dp(a, 24);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PUi.col(a, R.color.bn_card));
        bg.setCornerRadii(new float[] {0, 0, r, r, r, r, 0, 0});
        bg.setStroke(PUi.dp(a, 1), PUi.col(a, R.color.bn_card_stroke));
        panel.setBackground(bg);
        panel.setPadding(PUi.dp(a, 12), PUi.dp(a, 18), PUi.dp(a, 12), PUi.dp(a, 18));
        LinearLayout h = PUi.row(a);
        h.setPadding(PUi.dp(a, 6), 0, 0, PUi.dp(a, 14));
        h.addView(PUi.badge(a, 44));
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(PUi.dp(a, 12), 0, PUi.dp(a, 8), 0);
        t.addView(PUi.text(a, "Settings", 22, R.color.bn_text, 800));
        t.addView(PUi.text(a, dev.nxnative.android.BuildConfig.NX_PEANUTS_ID.isEmpty() ? "" : dev.nxnative.android.BuildConfig.NX_PEANUTS_ID, 12, R.color.bn_text_2, 400));
        h.addView(t, PUi.weight());
        ImageView close = iconButton(a, "close", R.drawable.ic_back, "Close", v -> d.cancel());
        close.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(a, 40), PUi.dp(a, 40)));
        h.addView(close);
        panel.addView(h);
        for (SettingsSections.Section s : SettingsSections.ALL)
            PUi.add(panel, entry(a, s, true, v -> { picked(v, s.key); d.dismiss(); }), 4);
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        sv.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.START | Gravity.FILL_VERTICAL);
            w.setLayout(PUi.dp(a, 300), ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.65f);
            w.getDecorView().setPadding(0, 0, 0, 0);
            Motion.drawer(d);
            watchFocus(w);   // a controller: the app's focus frame
        }
        d.setOnKeyListener((x, k, e) -> {   // B closes it, as a dialog of the app
            if (k != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) x.cancel();
            return true;
        });
        d.setOnDismissListener(x -> { if (drawer == d) drawer = null; });
        drawer = d;
        d.show();
    }
}
