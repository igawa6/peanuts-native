package dev.peanuts.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.nxnative.android.R;

/**
 * Settings › About: a Segmented at its top right, Overview, Games, Credits and Licenses.
 * <ul>
 * <li>Overview: the app's badge, its name and version (the build tag below, to copy), what it is, Check for updates and two links; beside it Peanuts
 * cracked, the app's own peanut cracked open (CrackedPeanut) over the count.</li>
 * <li>Games: the games this version includes (the game catalog, peanuts/games.json, copied into the app as a raw resource), All, the ones new in this
 * version and the ones with a companion, each a card with its letter, name, version and tag.</li>
 * <li>Credits: the makers of the three projects, and the contributors, davdxpx first (AboutData).</li>
 * <li>Licenses: what Peanuts Native is built on and what it carries, each opening its page or notice.</li>
 * </ul>
 * The tab and the Games filter are kept while the app runs. Upstream's own About card (its build tag, "Bananas Popped" and list of games) is left out:
 * its three things are here.
 */
final class AboutPage {
    private AboutPage() {}

    static final String[] TABS = {"Overview", "Games", "Credits", "Licenses"};
    private static int tab, filter;

    /** The About section's page below its top: the tabs and the one picked. update and faq press the App and Help rows' own actions */
    static void fill(Activity a, LinearLayout col, boolean wide, CharSequence buildTag, Runnable update, Runnable faq) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        PUi.Segmented seg = new PUi.Segmented(a, TABS, tab, i -> {
            tab = i;
            Haptics.play(col, Haptics.Word.CLICK);
            render(a, body, wide, buildTag, update, faq);
            Motion.enterPage(body, 0);
        });
        LinearLayout.LayoutParams sp = wide ? new LinearLayout.LayoutParams(PUi.dp(a, 380), ViewGroup.LayoutParams.WRAP_CONTENT) : PUi.fill();
        if (wide) sp.gravity = Gravity.END;
        PUi.add(col, seg, wide ? 4 : 12, sp);
        PUi.add(col, body, 14);
        render(a, body, wide, buildTag, update, faq);
    }

    private static void render(Activity a, LinearLayout body, boolean wide, CharSequence buildTag, Runnable update, Runnable faq) {
        body.removeAllViews();
        switch (tab) {
            case 1: games(a, body, wide); break;
            case 2: credits(a, body, wide); break;
            case 3: licenses(a, body, wide); break;
            default: overview(a, body, wide, buildTag, update, faq);
        }
        View first = SettingsPage.firstClickable(body);   // a controller: right from the sidebar, or down from the tabs, lands here (Overview: Check for updates)
        if (first == null) return;
        first.setFocusedByDefault(true);
        if (first.getId() == View.NO_ID) first.setId(View.generateViewId());
        ViewGroup col = body.getParent() instanceof ViewGroup ? (ViewGroup) body.getParent() : null;   // the tabs: down goes here (the page's ScrollView
        if (col != null) for (int i = 0; i < col.getChildCount(); i++) {                             // moves the focus by its own search, nearest first)
            if (!(col.getChildAt(i) instanceof PUi.Segmented)) continue;
            ViewGroup seg = (ViewGroup) col.getChildAt(i);
            for (int k = 0; k < seg.getChildCount(); k++) seg.getChildAt(k).setNextFocusDownId(first.getId());
        }
    }

    // ---- Overview ----

    private static void overview(Activity a, LinearLayout body, boolean wide, CharSequence buildTag, Runnable update, Runnable faq) {
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = PUi.row(a);
        top.addView(PUi.badge(a, 68));
        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(PUi.dp(a, 14), 0, 0, 0);
        names.addView(PUi.text(a, "Peanuts Native", 24, R.color.bn_text, 800));
        String id = dev.nxnative.android.BuildConfig.NX_PEANUTS_ID;
        if (!id.isEmpty()) PUi.add(names, PUi.text(a, id, 15, R.color.bn_text_2, 600), 2);
        top.addView(names, PUi.weight());
        info.addView(top);
        if (buildTag != null && buildTag.length() > 0 && !buildTag.toString().equals(id)) {   // which build this is, to copy into a report (every log starts with it)
            TextView tag = PUi.chip(a, buildTag);
            tag.setTextIsSelectable(true);
            PUi.add(info, tag, 10, PUi.wrap());
        }
        TextView what = PUi.body(a, "Plays your own Switch games natively on this device.");
        what.setLineSpacing(0, 1.3f);
        PUi.add(info, what, 12);
        Flow buttons = new Flow(a, PUi.dp(a, 8));
        buttons.addView(PUi.button(a, "Check for updates", PUi.Kind.PRIMARY, v -> update.run()));
        buttons.addView(PUi.button(a, "GitHub", PUi.Kind.TONAL, v -> open(a, AboutData.REPO)));
        buttons.addView(PUi.button(a, "Questions and answers", PUi.Kind.TONAL, v -> faq.run()));
        PUi.add(info, buttons, 14);
        View cracked = crackedCard(a, wide);
        if (wide) {
            LinearLayout row = PUi.row(a);
            row.setGravity(Gravity.TOP);
            row.addView(info, PUi.weight());
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(PUi.dp(a, 200), ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMarginStart(PUi.dp(a, 14));
            row.addView(cracked, cp);
            body.addView(row, PUi.fill());
        } else {
            body.addView(info, PUi.fill());
            PUi.add(body, cracked, 16);
        }
    }

    /** Peanuts cracked: the peanut, open, in a round tile; the count under it, kept up to date while it shows */
    private static View crackedCard(Activity a, boolean wide) {
        LinearLayout card = PUi.card(a);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = PUi.dp(a, 16);
        card.setPadding(p, p, p, p);
        FrameLayout disc = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(PUi.col(a, R.color.bn_tint));
        disc.setBackground(g);
        disc.addView(new CrackedPeanut(a), new FrameLayout.LayoutParams(PUi.dp(a, 112), PUi.dp(a, 112), Gravity.CENTER));
        card.addView(disc, new LinearLayout.LayoutParams(PUi.dp(a, 120), PUi.dp(a, 120)));
        TextView count = PUi.text(a, number(Cracked.total(a)), 30, R.color.bn_text, 800);
        count.setFontFeatureSettings("tnum");
        count.setGravity(Gravity.CENTER);
        PUi.add(card, count, 6, PUi.wrap());
        TextView label = PUi.text(a, "Peanuts cracked", 15, R.color.bn_text, 700);
        label.setGravity(Gravity.CENTER);
        PUi.add(card, label, 0, PUi.wrap());
        card.setContentDescription(number(Cracked.total(a)) + " peanuts cracked");
        Runnable update = () -> { long n = Cracked.total(a); count.setText(number(n)); card.setContentDescription(number(n) + " peanuts cracked"); };
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { Cracked.listen(a, update); update.run(); Cracked.settle(a); }
            @Override public void onViewDetachedFromWindow(View v) { Cracked.unlisten(update); }
        });
        return card;
    }

    static String number(long n) { return java.text.NumberFormat.getIntegerInstance().format(n); }

    // ---- Games ----

    /** A game of the catalog: its name, the newest version this app builds, whether it came in this version, whether it has a companion */
    private static final class Game {
        String name = "", version = "";
        boolean isNew, companion;
    }

    private static String appVersion = "";

    private static List<Game> catalog(Context c) {
        List<Game> out = new ArrayList<>();
        int id = c.getResources().getIdentifier("peanuts_games", "raw", c.getPackageName());
        if (id == 0) return out;
        try (InputStream in = c.getResources().openRawResource(id)) {
            JSONObject j = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            appVersion = j.optString("app_version", "");
            JSONArray games = j.getJSONArray("games");
            for (int i = 0; i < games.length(); i++) {
                JSONObject g = games.getJSONObject(i);
                Game x = new Game();
                x.name = g.optString("name", g.optString("title_id"));
                JSONArray b = g.optJSONArray("builds");
                if (b != null && b.length() > 0) x.version = b.getJSONObject(0).optString("display", b.getJSONObject(0).optString("version", ""));
                x.isNew = !appVersion.isEmpty() && appVersion.equals(g.optString("since", ""));
                x.companion = g.optBoolean("companion", false);
                out.add(x);
            }
        } catch (Exception e) {
            android.util.Log.w("peanuts", "about: the game catalog is not readable: " + e);
        }
        return out;
    }

    private static void games(Activity a, LinearLayout body, boolean wide) {
        List<Game> all = catalog(a);
        if (all.isEmpty()) { body.addView(PUi.small(a, "The list of games is not in this build.")); return; }
        List<Game> fresh = new ArrayList<>(), comp = new ArrayList<>();
        for (Game g : all) { if (g.isNew) fresh.add(g); if (g.companion) comp.add(g); }
        String[] labels = {"All", "New in " + appVersion, "With a dual-screen companion"};
        int[] counts = {all.size(), fresh.size(), comp.size()};
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        Flow chips = new Flow(a, PUi.dp(a, 8));
        List<TextView> chipViews = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0 && counts[i] == 0) continue;
            int k = i;
            TextView chip = filterChip(a, labels[i], counts[i]);
            chip.setOnClickListener(v -> {
                if (filter == k) return;
                filter = k;
                Haptics.play(v, Haptics.Word.CLICK);
                for (TextView t : chipViews) styleChip(a, t, ((Integer) t.getTag()) == filter);
                grid(a, grid, wide, filter == 1 ? fresh : filter == 2 ? comp : all);
                Motion.enterPage(grid, 0);
            });
            chip.setTag(k);
            chipViews.add(chip);
            chips.addView(chip);
        }
        if ((filter == 1 && fresh.isEmpty()) || (filter == 2 && comp.isEmpty())) filter = 0;
        for (TextView t : chipViews) styleChip(a, t, ((Integer) t.getTag()) == filter);
        body.addView(chips, PUi.fill());
        PUi.add(body, grid, 12);
        grid(a, grid, wide, filter == 1 ? fresh : filter == 2 ? comp : all);
    }

    private static TextView filterChip(Activity a, String label, int count) {
        TextView t = PUi.text(a, label + "  " + count, 14, R.color.bn_text, 700);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(PUi.dp(a, 40));
        t.setPadding(PUi.dp(a, 14), 0, PUi.dp(a, 14), 0);
        t.setClickable(true);
        t.setFocusable(true);
        t.setContentDescription(label + ", " + count + " games");
        return t;
    }

    private static void styleChip(Activity a, TextView t, boolean on) {
        t.setBackground(on ? PUi.round(PUi.col(a, R.color.bn_banana), PUi.dp(a, 14), 0, 0) : PUi.ripple(a, PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 14), 0, 0)));
        t.setTextColor(PUi.col(a, on ? R.color.bn_on_banana : R.color.bn_text));
        t.setSelected(on);
    }

    /** The cards, as many side by side as fit at about 220 dp each */
    private static void grid(Activity a, LinearLayout grid, boolean wide, List<Game> games) {
        grid.removeAllViews();
        int avail = SettingsPage.paneDp(a);
        int cols = Math.max(1, Math.min(3, avail / 220));
        LinearLayout row = null;
        for (int i = 0; i < games.size(); i++) {
            if (i % cols == 0) { row = PUi.row(a); row.setGravity(Gravity.TOP); PUi.add(grid, row, i == 0 ? 0 : 8); }
            LinearLayout.LayoutParams lp = PUi.weight();
            if (i % cols > 0) lp.setMarginStart(PUi.dp(a, 8));
            row.addView(gameCard(a, games.get(i)), lp);
        }
        if (row != null) for (int k = games.size() % cols; k > 0 && k < cols; k++) {   // the last row's empty places keep the cards' width
            LinearLayout.LayoutParams lp = PUi.weight();
            lp.setMarginStart(PUi.dp(a, 8));
            row.addView(new View(a), lp);
        }
    }

    private static View gameCard(Activity a, Game g) {
        LinearLayout card = PUi.card(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int p = PUi.dp(a, 8);
        card.setPadding(p, p, p, p);
        card.addView(PUi.tile(a, g.name, 44));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(a, 10), 0, 0, 0);
        TextView name = PUi.text(a, g.name, 14, R.color.bn_text, 600);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        LinearLayout line = PUi.row(a);
        if (!g.version.isEmpty()) {
            TextView v = PUi.text(a, g.version, 12, R.color.bn_text_2, 400);
            v.setFontFeatureSettings("tnum");
            line.addView(v, PUi.wrap());
        }
        String tag = g.isNew ? "New in " + appVersion : g.companion ? "Companion" : null;
        if (tag != null) {
            TextView t = PUi.text(a, tag, 11, R.color.bn_text_2, 600);
            t.setSingleLine(true);
            t.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 999), 0, 0));
            t.setPadding(PUi.dp(a, 8), PUi.dp(a, 2), PUi.dp(a, 8), PUi.dp(a, 2));
            LinearLayout.LayoutParams tp = PUi.wrap();
            if (line.getChildCount() > 0) tp.setMarginStart(PUi.dp(a, 6));
            line.addView(t, tp);
        }
        if (line.getChildCount() > 0) PUi.add(col, line, 3);
        card.addView(col, PUi.weight());
        return card;
    }

    // ---- Credits ----

    private static void credits(Activity a, LinearLayout body, boolean wide) {
        LinearLayout made = section(a, "Made by");
        for (String[] m : AboutData.MADE_BY) made.addView(linkRow(a, m[0], m[1], null, m[2], initial(a, m[0], 36, 11)));
        LinearLayout people = section(a, "Contributors");
        Flow names = new Flow(a, PUi.dp(a, 8));
        for (String c : AboutData.CONTRIBUTED) names.addView(person(a, c));
        LinearLayout pad = new LinearLayout(a);
        pad.setOrientation(LinearLayout.VERTICAL);
        int p = PUi.dp(a, 8);
        pad.setPadding(p, PUi.dp(a, 4), p, p);
        pad.addView(names, PUi.fill());
        TextView everyone = PUi.button(a, "Everyone on GitHub", PUi.Kind.QUIET, v -> open(a, AboutData.CONTRIBUTORS));
        everyone.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, Icons.get(a, "external", Icons.Ink.NORMAL), null);
        everyone.setCompoundDrawablePadding(PUi.dp(a, 6));
        PUi.add(pad, everyone, 8, PUi.wrap());
        TextView thanks = PUi.small(a, "Thanks to everyone who tested a game on their device and wrote what they saw.");
        PUi.add(pad, thanks, 6);
        people.addView(pad);
        pair(a, body, wide, made, people);
    }

    /** A contributor: the round initial and the name, on the tint; opens their GitHub page */
    private static View person(Activity a, String name) {
        LinearLayout v = PUi.row(a);
        v.setPadding(PUi.dp(a, 4), PUi.dp(a, 4), PUi.dp(a, 12), PUi.dp(a, 4));
        v.setBackground(PUi.ripple(a, PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 999), 0, 0)));
        TextView i = initial(a, name, 28, 999);
        i.setBackground(PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 999), 0, 0));
        v.addView(i);
        TextView n = PUi.text(a, name, 13, R.color.bn_text, 600);
        LinearLayout.LayoutParams lp = PUi.wrap();
        lp.setMarginStart(PUi.dp(a, 8));
        v.addView(n, lp);
        v.setClickable(true);
        v.setFocusable(true);
        v.setContentDescription(name + ", GitHub");
        v.setOnClickListener(x -> { Haptics.play(x, Haptics.Word.TICK); open(a, "https://github.com/" + name); });
        return v;
    }

    private static TextView initial(Activity a, String name, int sizeDp, int radiusDp) {
        TextView t = PUi.text(a, name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), sizeDp * 0.44f, R.color.bn_accent, 800);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, radiusDp), 0, 0));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(a, sizeDp), PUi.dp(a, sizeDp)));
        return t;
    }

    // ---- Licenses ----

    private static void licenses(Activity a, LinearLayout body, boolean wide) {
        LinearLayout built = section(a, "Built on");
        for (String[] l : AboutData.BUILT_ON) built.addView(linkRow(a, l[0], l[1], l[2], l[3], null));
        LinearLayout third = section(a, "In this app");
        for (String[] l : AboutData.THIRD_PARTY) third.addView(linkRow(a, l[0], l[1], l[2], l[3], null));
        pair(a, body, wide, built, third);
    }

    // ---- Pieces ----

    /** A card with its label inside at the top (Made by, Contributors, Built on, In this app) */
    private static LinearLayout section(Activity a, String label) {
        LinearLayout card = PUi.listCard(a);
        TextView l = PUi.section(a, label);
        l.setPadding(PUi.dp(a, 10), PUi.dp(a, 6), 0, PUi.dp(a, 4));
        card.addView(l);
        return card;
    }

    /** Two cards side by side on a wide window, one under the other on a phone */
    private static void pair(Activity a, LinearLayout body, boolean wide, View first, View second) {
        if (!wide) { body.addView(first, PUi.fill()); PUi.add(body, second, 12); return; }
        LinearLayout row = PUi.row(a);
        row.setGravity(Gravity.TOP);
        row.addView(first, PUi.weight());
        LinearLayout.LayoutParams lp = PUi.weight();
        lp.setMarginStart(PUi.dp(a, 10));
        row.addView(second, lp);
        body.addView(row, PUi.fill());
    }

    /** A row that opens a page: optional leading tile, the title over its line, an optional license pill, the "opens outside" mark */
    private static View linkRow(Activity a, String title, String line, String pill, String url, View lead) {
        LinearLayout r = PUi.row(a);
        r.setMinimumHeight(PUi.dp(a, 48));
        r.setPadding(PUi.dp(a, 10), PUi.dp(a, 6), PUi.dp(a, 10), PUi.dp(a, 6));
        r.setBackground(PUi.ripple(a, PUi.round(Color.TRANSPARENT, PUi.dp(a, 12), 0, 0)));
        if (lead != null) r.addView(lead);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(a, lead != null ? 12 : 4), 0, PUi.dp(a, 8), 0);
        col.addView(PUi.text(a, title, 15, R.color.bn_text, 600));
        if (line != null && !line.isEmpty()) col.addView(PUi.small(a, line));
        r.addView(col, PUi.weight());
        if (pill != null) {
            TextView t = PUi.text(a, pill, 11, R.color.bn_accent, 600);
            t.setSingleLine(true);
            t.setBackground(PUi.round(Color.TRANSPARENT, PUi.dp(a, 999), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1)));
            t.setPadding(PUi.dp(a, 8), PUi.dp(a, 3), PUi.dp(a, 8), PUi.dp(a, 3));
            LinearLayout.LayoutParams pp = PUi.wrap();
            pp.setMarginEnd(PUi.dp(a, 8));
            r.addView(t, pp);
        }
        ImageView out = new ImageView(a);
        out.setImageDrawable(Icons.get(a, "external", Icons.Ink.QUIET));
        out.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(out, new LinearLayout.LayoutParams(PUi.dp(a, 18), PUi.dp(a, 18)));
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(title + (line == null ? "" : ", " + line) + ", opens the browser");
        r.setOnClickListener(v -> { Haptics.play(v, Haptics.Word.TICK); open(a, url); });
        return r;
    }

    /** A page in the browser */
    static void open(Activity a, String url) {
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(a, "No browser to open " + url, Toast.LENGTH_LONG).show(); }
    }

    /** Views side by side, wrapping to the next line when one does not fit (AdvancedPage's chips do the same) */
    static final class Flow extends ViewGroup {
        private final int gap;
        Flow(Context c, int gap) { super(c); this.gap = gap; }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec), x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED);
                if (x > 0 && x + c.getMeasuredWidth() > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
            setMeasuredDimension(maxW, y + rowH);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c.getVisibility() == GONE) continue;
                if (x > 0 && x + c.getMeasuredWidth() > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, c.getMeasuredHeight());
            }
        }
    }
}
