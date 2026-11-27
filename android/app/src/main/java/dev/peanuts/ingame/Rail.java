package dev.peanuts.ingame;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The quick menu's frame: upstream's side panel keeps building its tabs, and this turns its
 * sheet around. The tab bar becomes a dark rail of solid icons at the left (the menu's own settings at its bottom), the selected tab a peanut-brown block
 * across the rail that slides to the next one; the tab's rows get a header with a back button and the tab's name. With a controller, up and down on the
 * rail switch the tab at once, right goes into the rows, left from a row comes back to the selected tab.
 * <p>
 * In the one menu (at the right or on the bottom screen) the rail starts with Game, above the tabs and apart from them: the game menu's rows (GameRows) in place of
 * the panel's own.
 */
public final class Rail {
    private final Context c;
    private final LinearLayout tabs;   // upstream's tab bar, now the rail's items in tab order (SidePanel.tabs: getChildAt(tab) still finds a tab's item)
    private final FrameLayout box;     // the rail: the moving block under the items
    private final View block;
    private final TextView title;
    private final LinearLayout head;
    private final View content;   // the tab's rows beside the rail (the panel's scroll)
    private View action;   // the shown tab's own action at the right of the header (the Cheats tab's All off)
    private final List<View> items = new ArrayList<>();
    private final View gameItem;       // the bottom screen's Game (null on the top screen)
    private final ScrollView gameRows;
    private final View gameFoot;   // Game's Stop under its rows (GameRows.footer)
    private String[] names = new String[0];
    private int selected = -1;
    private boolean onGame;
    private IntConsumer show;
    private boolean placed;

    /** Takes the panel's sheet (upstream: the tab bar over the rows) apart and puts it together as rail | (header over rows). back: the header's button */
    public Rail(Context c, LinearLayout sheet, LinearLayout tabs, View rows, Runnable back) { this(c, sheet, tabs, rows, back, null); }

    /** The same with the bottom screen's Game first on the rail (game: its rows), null for the top screen's quick menu */
    public Rail(Context c, LinearLayout sheet, LinearLayout tabs, View rows, Runnable back, GameRows game) {
        this.c = c;
        this.tabs = tabs;
        this.content = rows;
        sheet.removeAllViews();
        sheet.setOrientation(LinearLayout.HORIZONTAL);
        sheet.setBackgroundColor(Look.SHEET);

        box = new FrameLayout(c) {
            // Right from the rail goes into the rows beside it (the first one in sight), not to the header's Back (the nearer one by the default search)
            @Override public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_RIGHT) { View r = firstRow(); if (r != null) return r; }
                return super.focusSearch(focused, direction);
            }
        };
        box.setBackgroundColor(Look.RAIL);
        block = new View(c);
        block.setBackgroundColor(Look.PEANUT);
        block.setVisibility(View.INVISIBLE);
        box.addView(block, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight()));
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, Look.dp(c, game != null ? 14 : 8), 0, Look.dp(c, 8));
        if (game != null) {
            gameItem = item("game", "Game");
            gameItem.setOnClickListener(v -> game());
            column.addView(gameItem, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemHeight()));
            View sep = new View(c);
            sep.setBackgroundColor(Look.ROW);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Look.dp(c, 1)));
            sl.leftMargin = sl.rightMargin = Look.dp(c, 12);
            sl.topMargin = sl.bottomMargin = Look.dp(c, 4);
            column.addView(sep, sl);
        } else gameItem = null;
        tabs.setOrientation(LinearLayout.VERTICAL);
        tabs.setBackground(null);
        tabs.setPadding(0, 0, 0, 0);
        column.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        box.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sheet.addView(box, new LinearLayout.LayoutParams(Look.dp(c, game != null ? 56 : 60), ViewGroup.LayoutParams.MATCH_PARENT));
        View edge = new View(c);
        edge.setBackgroundColor(Look.RAIL_EDGE);
        sheet.addView(edge, new LinearLayout.LayoutParams(Math.max(1, Look.dp(c, 1)), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(c) {
            // Left from the rows comes back to the selected tab on the rail (on the bottom screen's Game: to Game), not to the header's Back above them
            // (the nearer one by the default search)
            @Override public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_LEFT && focused != null && !inside(focused, head)) {
                    if (onGame && gameItem != null) return gameItem;
                    if (selected >= 0 && selected < items.size()) return items.get(selected);
                }
                return super.focusSearch(focused, direction);
            }
        };
        col.setOrientation(LinearLayout.VERTICAL);
        head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Look.dp(c, 12), Look.dp(c, game != null ? 14 : 10), Look.dp(c, 12), Look.dp(c, 2));
        ImageView b = new ImageView(c);
        Drawable bd = Look.icon(c, "back", Look.Ink.MENU);
        if (bd != null) b.setImageDrawable(bd);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Look.dp(c, 7);
        b.setPadding(p, p, p, p);
        b.setBackground(Look.round(Look.ROW, Look.dp(c, 8)));
        b.setFocusable(true);
        b.setClickable(true);
        b.setDefaultFocusHighlightEnabled(false);   // the panel's yellow frame alone shows the focus (no grey veil over the button)
        b.setContentDescription("Back");
        b.setOnClickListener(v -> back.run());
        head.addView(b, new LinearLayout.LayoutParams(Look.dp(c, 36), Look.dp(c, 32)));
        title = new TextView(c);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(Look.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setPadding(Look.dp(c, 12), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(head);
        col.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (game != null) {
            gameRows = new ScrollView(c);
            gameRows.setVerticalScrollBarEnabled(false);
            gameRows.setVerticalFadingEdgeEnabled(true);   // a row cut by the screen's edge or by Stop below fades out instead of ending in a hard line
            gameRows.setFadingEdgeLength(Look.dp(c, 28));
            gameRows.setPadding(Look.dp(c, 10), Look.dp(c, 16), Look.dp(c, 10), 0);   // no bottom padding: the fade lies on the edge where the rows are cut
            gameRows.setClipToPadding(false);
            gameRows.addView(game);
            gameRows.setVisibility(View.GONE);
            col.addView(gameRows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            gameFoot = game.footer();
            gameFoot.setVisibility(View.GONE);
            col.addView(gameFoot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else { gameRows = null; gameFoot = null; }
        sheet.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // Up and down on the rail switch the tab; left from the rows comes back to the selected tab only (the others take no focus then)
        Look.focusListener(sheet, (old, now) -> {
            if (now != null && now == gameItem) { focusable(true); if (!onGame) game(); return; }
            int i = items.indexOf(now);
            if (i >= 0) {
                focusable(true);
                if (i != selected || onGame) pick(i);
            } else if (now != null && inside(now, sheet)) focusable(false);
        });
    }

    /** The first row in sight beside the rail (Game's on Game): where Right from the rail goes */
    public View firstRow() { return onGame && gameRows != null ? firstGameRow() : firstIn(content); }

    /** The first focusable in (top to bottom) that is mostly in sight, else the first at all (a track's chosen choice); null when there is none */
    static View firstIn(View in) {
        if (in == null || in.getVisibility() != View.VISIBLE) return null;
        ArrayList<View> all = new ArrayList<>();
        in.addFocusables(all, View.FOCUS_DOWN);
        android.graphics.Rect r = new android.graphics.Rect();
        for (View v : all) if (v.isShown() && v.getGlobalVisibleRect(r) && r.height() * 2 > v.getHeight()) return dev.peanuts.ui.PanelPolish.landing(v);
        for (View v : all) if (v.isShown()) return dev.peanuts.ui.PanelPolish.landing(v);
        return null;
    }

    private int itemHeight() { return Look.dp(c, gameItem != null || names.length > 6 ? 46 : 50); }

    private View item(String icon, String name) {
        FrameLayout item = new FrameLayout(c);
        ImageView ic = new ImageView(c);
        ic.setScaleType(ImageView.ScaleType.CENTER);
        Drawable d = Look.icon(c, icon, Look.Ink.REST);
        if (d != null) ic.setImageDrawable(d);
        item.addView(ic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        item.setFocusable(true);
        item.setClickable(true);
        item.setDefaultFocusHighlightEnabled(false);   // the yellow frame alone, over the peanut block
        item.setContentDescription(name);
        item.setId(View.generateViewId());
        return item;
    }

    private static boolean inside(View v, View in) {
        for (Object p = v; p instanceof View; p = ((View) p).getParent()) if (p == in) return true;
        return false;
    }

    private void focusable(boolean all) {
        for (int k = 0; k < items.size(); k++) items.get(k).setFocusable(all || (!onGame && k == selected));
        if (gameItem != null) gameItem.setFocusable(all || onGame);
    }

    /**
     * The shown tab's own action at the right of its header, as a console's menus show one: the controller's button for it and its name ("X All off"),
     * a tap does it too. Not a focus stop: the button is the controller's way. Null label: none (each tab's build starts without one)
     */
    public void action(String key, String label, Runnable run) {
        if (action != null) { head.removeView(action); action = null; }
        if (label == null) return;
        LinearLayout a = new LinearLayout(c);
        a.setOrientation(LinearLayout.HORIZONTAL);
        a.setGravity(Gravity.CENTER_VERTICAL);
        a.setPadding(Look.dp(c, 5), Look.dp(c, 4), Look.dp(c, 14), Look.dp(c, 4));
        a.setBackground(Look.round(Look.ROW, Look.dp(c, 999)));
        TextView k = new TextView(c);
        k.setText(key);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setTextColor(Look.SHEET | 0xFF000000);
        k.setGravity(Gravity.CENTER);
        k.setIncludeFontPadding(false);
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(Look.TEXT);
        k.setBackground(dot);
        a.addView(k, new LinearLayout.LayoutParams(Look.dp(c, 24), Look.dp(c, 24)));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(Look.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setPadding(Look.dp(c, 8), 0, 0, 0);
        a.addView(t);
        a.setFocusable(false);
        a.setClickable(true);
        a.setContentDescription(label);
        a.setOnClickListener(v -> run.run());
        head.addView(a, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        action = a;
    }

    /** Whether the controller's focus is on the rail (a tab switched from there keeps it there) */
    public boolean focused() {
        if (gameItem != null && gameItem.isFocused()) return true;
        for (View v : items) if (v.isFocused()) return true;
        return false;
    }

    /** Whether the bottom screen's Game shows */
    public boolean onGame() { return onGame; }

    /** A tab picked on the rail: from Game back to the panel's rows, and the panel to that tab unless it shows it already */
    private void pick(int i) {
        boolean was = onGame;
        if (onGame) leaveGame();
        if (i != selected && show != null) show.accept(i);
        else if (was) select(selected);
    }

    /** The bottom screen's Game: its rows in place of the panel's; the panel keeps its tab for the way back */
    public void game() {
        if (gameRows == null) return;
        onGame = true;
        content.setVisibility(View.GONE);
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0)   // the panel's own rows take no focus while hidden (SidePanel.focusFirst)
            ((ViewGroup) ((ViewGroup) content).getChildAt(0)).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gameRows.setVisibility(View.VISIBLE);
        gameFoot.setVisibility(View.VISIBLE);
        gameRows.scrollTo(0, 0);
        head.setVisibility(View.GONE);   // Game's own header (the cover, the name) is the top, as in the game menu: its first row goes back to the game
        title.setText("Game");
        paintIcons();
        if (!focused()) focusable(false);
        moveBlock(gameItem, placed);
        gameRows.setAlpha(0f);
        gameRows.setTranslationY(Look.dp(c, 8));
        gameRows.animate().alpha(1f).translationY(0f).setStartDelay(Look.TAB_OUT_MS).setDuration(Look.TAB_IN_MS).setInterpolator(Look.OUT).withLayer().start();
    }

    private void leaveGame() {
        onGame = false;
        gameRows.setVisibility(View.GONE);
        gameFoot.setVisibility(View.GONE);
        head.setVisibility(View.VISIBLE);
        if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0)
            ((ViewGroup) ((ViewGroup) content).getChildAt(0)).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        content.setVisibility(View.VISIBLE);
    }

    /** The first of Game's rows (a controller's first focus there), null on the top screen */
    public View firstGameRow() {
        if (gameRows == null || gameRows.getChildCount() == 0) return null;
        java.util.ArrayList<View> f = gameRows.getChildAt(0).getFocusables(View.FOCUS_FORWARD);
        return f.isEmpty() ? null : f.get(0);
    }

    /** The tab after (+1) or before (-1) on the rail, with Game first on the bottom screen: for L and R there (dev.nxnative.android.InGamePanel) */
    public void step(int dir) {
        int n = names.length;
        if (n == 0) return;
        if (gameRows == null) { if (show != null) show.accept(Math.floorMod(selected + dir, n)); return; }
        int at = onGame ? -1 : selected, to = at + dir;   // -1 is Game
        if (to < -1) to = n - 1;
        if (to >= n) to = -1;
        View f = tabs.getRootView().findFocus();
        boolean inRows = f != null && !inside(f, tabs) && !inside(f, head);   // the controller was in the rows: it stays in the rows of the next tab
        if (to == -1) game(); else pick(to);
        if (inRows) tabs.post(() -> { View r = firstRow(); if (r != null) r.requestFocus(); });
    }

    /**
     * The rail for these tabs (upstream's names; icons and titles by name), tab selected; show switches the panel to a tab. Built once for a set of tabs, then
     * only the selection moves (a rebuild would take the focus away from the item the controller is on)
     */
    public void tabs(String[] tabNames, int tab, IntConsumer show) {
        this.show = show;
        if (!java.util.Arrays.equals(tabNames, names)) {
            names = tabNames.clone();
            items.clear();
            tabs.removeAllViews();
            selected = -1;
            placed = false;
            int h = itemHeight();
            for (int i = 0; i < names.length; i++) {
                int k = i;
                View item = item(iconOf(names[i]), titleOf(names[i]) + " tab, " + (i + 1) + " of " + names.length);
                item.setOnClickListener(v -> { if (this.show != null && (k != selected || onGame)) pick(k); });
                items.add(item);
                if (i == names.length - 1) {   // the menu's own settings, at the bottom of the rail
                    FrameLayout rest = new FrameLayout(c);
                    rest.addView(item, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM));
                    tabs.addView(rest, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                } else {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
                    lp.bottomMargin = Look.dp(c, 2);
                    tabs.addView(item, lp);
                }
            }
            // Up and down stay on the rail (the next item by id): the nearest focusable below the last tab would be a row of the content, since the
            // settings sit at the rail's bottom
            List<View> all = new ArrayList<>();
            if (gameItem != null) all.add(gameItem);
            all.addAll(items);
            for (int i = 0; i < all.size(); i++) {
                all.get(i).setNextFocusUpId(all.get(Math.max(0, i - 1)).getId());
                all.get(i).setNextFocusDownId(all.get(Math.min(all.size() - 1, i + 1)).getId());
            }
        }
        select(tab);
    }

    private void select(int tab) {
        selected = tab;
        if (onGame) { paintIcons(); return; }   // the panel rebuilt a tab while Game shows: the rail stays on Game
        paintIcons();
        for (int i = 0; i < items.size(); i++) items.get(i).setSelected(i == tab);
        if (!focused()) focusable(false);
        title.setText(titleOf(tab >= 0 && tab < names.length ? names[tab] : ""));
        if (tab >= 0 && tab < items.size()) moveBlock(items.get(tab), placed);
    }

    private void paintIcons() {
        for (int i = 0; i < items.size(); i++) {
            ImageView ic = (ImageView) ((FrameLayout) items.get(i)).getChildAt(0);
            Drawable d = Look.icon(c, iconOf(names[i]), !onGame && i == selected ? Look.Ink.SELECTED : Look.Ink.REST);
            if (d != null) ic.setImageDrawable(d);
        }
        if (gameItem != null) {
            Drawable d = Look.icon(c, "game", onGame ? Look.Ink.SELECTED : Look.Ink.REST);
            if (d != null) ((ImageView) ((FrameLayout) gameItem).getChildAt(0)).setImageDrawable(d);
        }
    }

    private View blockAt;   // the item the block goes to: a later choice wins over an earlier one still waiting for the rail's first layout

    /** The block to an item: sliding (BLOCK_MS) from where it was, or placed at once (the first time, or before the rail is laid out) */
    private void moveBlock(View it, boolean slide) {
        blockAt = it;
        Runnable go = () -> {
            View to = blockAt;
            if (to == null || to.getHeight() == 0) return;
            int y = 0;
            for (View v = to; v != null && v != box; v = v.getParent() instanceof View ? (View) v.getParent() : null) y += v.getTop();
            block.setVisibility(View.VISIBLE);
            block.animate().cancel();
            if (block.getLayoutParams().height != to.getHeight()) { block.getLayoutParams().height = to.getHeight(); block.requestLayout(); }
            if (slide && placed) block.animate().translationY(y).setDuration(Look.BLOCK_MS).setInterpolator(Look.OUT).start();
            else block.setTranslationY(y);
            placed = true;
        };
        if (it.getHeight() > 0) go.run(); else box.post(go);
    }

    /** The solid icon of a tab, by upstream's name (dev.nxnative.android.ModsPanel's names) */
    public static String iconOf(String tab) {
        switch (tab) {
            case "Performance": return "bolt";
            case "Display": return "display";
            case "Cheats": return "cheats";
            case "Mods": return "mods";
            case "Controls": return "controller";
            default: return "settings";
        }
    }

    /** A tab's title in the header ("General", the menu's own settings, reads Menu settings) */
    public static String titleOf(String tab) { return "General".equals(tab) ? "Menu settings" : tab; }
}
