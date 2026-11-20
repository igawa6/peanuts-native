package dev.peanuts.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;

import dev.peanuts.build.Companions;

/**
 * The Companion section of a game's page (its Game tab, GameSettings.build; a PEANUTS hook, HOOKS.md): the installed companion and the catalog's version, Install /
 * Update / Remove, and where the companion shows ("Second screen": Physical / Off, Off by default on a device without a second display, kept in the "peanuts" prefs as second_screen_&lt;title ID&gt; for dev.peanuts.Companion
 * to read). Rows are upstream's Ui.Setting and Ui.button, reached through SectionUi.
 */
public final class CompanionSection {
    public static final String PREFS = "peanuts", KEY_SECOND_SCREEN = "second_screen_", PHYSICAL = "physical", OFF = "off";

    private CompanionSection() {}

    /** dev.peanuts.Games.Entry.companionRoot for a game's pack folder: &lt;pack&gt;/../peanuts, or null without a pack */
    public static File root(String packDir) {
        return packDir == null || packDir.isEmpty() ? null : new File(new File(packDir).getParentFile(), "peanuts");
    }

    /**
     * The package folder in a companion root whose manifest names this title, null when none does. The app's own packs share one root (packs/peanuts holds
     * every game's package), so "the first folder there" is another game's companion as often as not. The rule is the runtime's (dm/peanuts/peanuts.c
     * rt_scan_root): a folder that is not hidden and not a swap's ".new" or ".old", whose dualscreen/manifest.json has this title_id in its first 4 KiB.
     * Reads a small file per folder: off the main thread, or once per render as the section does
     */
    public static String packageFor(File root, String titleId) {
        long want;
        try { want = Long.parseUnsignedLong(titleId == null ? "" : titleId.trim(), 16); } catch (NumberFormatException e) { return null; }
        String[] names = root == null ? null : root.list();
        if (names == null || want == 0) return null;
        java.util.Arrays.sort(names);
        for (String n : names) {
            if (n.startsWith(".") || n.endsWith(".new") || n.endsWith(".old")) continue;
            if (manifestTitle(new File(new File(root, n), "dualscreen/manifest.json")) == want) return n;
        }
        return null;
    }

    /** The title a package's manifest names (the hex digits of its first "title_id"), 0 when it names none or does not read */
    static long manifestTitle(File manifest) {
        byte[] b = new byte[4095];
        int n = 0;
        try (java.io.InputStream in = new java.io.FileInputStream(manifest)) {
            for (int k; n < b.length && (k = in.read(b, n, b.length - n)) > 0; ) n += k;
        } catch (IOException e) {
            return 0;
        }
        String head = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = head.indexOf("\"title_id\"");
        at = at < 0 ? -1 : head.indexOf('"', at + 10);
        if (at < 0) return 0;
        int from = at + 1;
        while (from < head.length() && Character.isWhitespace(head.charAt(from))) from++;
        if (head.startsWith("0x", from) || head.startsWith("0X", from)) from += 2;
        int to = from;
        while (to < head.length() && to - from < 16 && Character.digit(head.charAt(to), 16) >= 0) to++;
        try { return to == from ? 0 : Long.parseUnsignedLong(head.substring(from, to), 16); } catch (NumberFormatException e) { return 0; }
    }

    public static String secondScreen(Context c, String titleId) {
        String v = prefs(c).getString(KEY_SECOND_SCREEN + titleId, null);
        return v != null ? v : dev.peanuts.Companion.hasSecondDisplay(c) ? PHYSICAL : OFF;   // not chosen: Off on a device without a second display
    }

    /** Whether the user chose the title's Second screen (else {@link #secondScreen} is the device's default) */
    public static boolean secondScreenChosen(Context c, String titleId) { return prefs(c).contains(KEY_SECOND_SCREEN + titleId); }

    public static void secondScreen(Context c, String titleId, String v) {
        prefs(c).edit().putString(KEY_SECOND_SCREEN + titleId, v).apply();
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS); }

    /** The section: a label, the companion row with its buttons, the second-screen row. companionRoot null (no pack yet): Install is off and the row says so */
    public static View view(Context ctx, String titleId, File companionRoot) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        SectionUi ui = new SectionUi(ctx);
        box.addView(ui.section("Companion"), SectionUi.lp(0, 12));
        new Rows(ctx, ui, titleId, companionRoot, box).render();
        return box;
    }

    /** The rows' state and async work; render() rebuilds them in place. Found in review: installedPkg/installedVersion/installedVerified are a cache, filled by
     * refreshInstalled() - a directory scan plus a reflective file read - and only ever refreshed there, never from render() itself. Without this, every single
     * progress tick of an install (several a second on a slow download) re-scanned the companion folder and re-read installed.json on the UI thread for no reason,
     * since render() during a busy install only ever shows busyText anyway. */
    private static final class Rows {
        final Context c;
        final SectionUi ui;
        final String titleId;
        final File root;
        final LinearLayout box;
        final Handler main = new Handler(Looper.getMainLooper());
        List<Companions.Item> catalog;
        String catalogError;
        boolean busy;
        String busyText = "";
        final LinearLayout body;
        View buttonsRow;   // the row of Install / Update / Remove / Versions of the last render (null: none)
        // The installed-state cache (found in review): stale until refreshInstalled() runs, which render() asks for only on its first call and after fetch/install/remove finish
        boolean installedKnown;
        String installedPkg, installedVersion;
        boolean installedVerified;
        String[] packNotice;   // {title, sentence} when the pack lacks what the installed companion needs of it (dev.peanuts.pack.PackUpgrade.notice), else null

        Rows(Context c, SectionUi ui, String titleId, File root, LinearLayout box) {
            this.c = c; this.ui = ui; this.titleId = titleId; this.root = root; this.box = box;
            body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            box.addView(body, SectionUi.lp(0, 0));
            fetch();
        }

        void fetch() {
            new Thread(() -> {
                List<Companions.Item> it = null; String err = null;
                try { it = Companions.fetch(); } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final List<Companions.Item> fit = it; final String ferr = err;
                main.post(() -> {
                    catalog = fit; catalogError = ferr; installedKnown = false;
                    View had = buttonsRow;
                    render();
                    if (had == null && buttonsRow != null) dev.peanuts.ui.Feel.appeared(buttonsRow);   // Install / Versions came with the catalog: they fade in, once (a progress tick's render never does)
                });
            }, "peanuts-catalog").start();
        }

        /** The one place that touches disk for the installed package's state (found in review): a directory scan (packageFor) plus installed.json's version
         * and verified flag. Called from render() only when installedKnown is false - set true right after, so a busy install's progress ticks (which call render()
         * every update) reuse the cached values instead of re-scanning. */
        void refreshInstalled(Companions.Item item) {
            // Self-heal before reading anything: a killed install/switch is completed or rolled back here, never left with no active companion. The package
            // name to repair can only come from the catalog item at this point (a fully-vanished active folder has no manifest for packageFor to find).
            if (item != null) Companions.repair(root, item.packageName, c);   // ctx = c: skips while the game is running (the lock alone does not cover that; found in review)
            installedPkg = item != null ? item.packageName : packageFor(root, titleId);   // without the catalog: the package of this title, not the first of the shared folder (another game's, with its Remove)
            installedVersion = installedPkg == null ? null : Companions.installedVersion(root, installedPkg);
            installedVerified = installedPkg != null && installedVersion != null && Companions.installedVerified(root, installedPkg);
            packNotice = installedPkg == null || installedVersion == null || root == null ? null : dev.peanuts.pack.PackUpgrade.notice(c, titleId, new File(root, installedPkg));
            installedKnown = true;
        }

        void render() {
            body.removeAllViews();
            Companions.Item item = catalog == null ? null : Companions.find(catalog, titleId);
            if (!installedKnown) refreshInstalled(item);
            String pkg = installedPkg, have = installedVersion;
            boolean update = item != null && have != null && Companions.compareVersions(item.version, have) > 0;
            CharSequence value;
            if (busy) value = busyText;
            else if (have != null) value = dev.peanuts.ui.PUi.joined("Installed: " + Companions.label(pkg, have), !installedVerified ? "unverified" : null,
                    item == null ? (catalog == null && catalogError == null ? "checking the catalog…" : null) : update ? "update " + item.version + " available" : "up to date");
            else if (item != null) value = dev.peanuts.ui.PUi.joined("Available: " + Companions.label(item.packageName, item.version), root == null ? "build the game first" : null);
            else if (catalog == null && catalogError == null) value = "Checking the catalog…";
            else if (catalogError != null) value = "Catalog unavailable: " + catalogError;
            else value = "No companion in the catalog for this game";
            boolean hasVersions = root != null && (item != null || have != null) && c instanceof Activity;
            View.OnClickListener openVersions = !hasVersions ? null : v -> dev.peanuts.ui.CompanionVersions.open((Activity) c, titleId, () -> { installedKnown = false; render(); });
            body.addView(ui.setting("🖥️", "Companion", value, openVersions), SectionUi.lp(0, 0));
            LinearLayout buttons = SectionUi.buttons(c);
            if (have == null && item != null) SectionUi.add(buttons, ui.button("Install", SectionUi.PRIMARY, v -> install(item)), !busy && root != null);
            if (update) SectionUi.add(buttons, ui.button("Update", SectionUi.PRIMARY, v -> install(item)), !busy);
            if (have != null) SectionUi.add(buttons, ui.button("Remove", SectionUi.DANGER, v -> remove(pkg)), !busy);
            if (hasVersions) SectionUi.add(buttons, ui.button("Versions", SectionUi.QUIET, v -> dev.peanuts.ui.CompanionVersions.open((Activity) c, titleId, () -> { installedKnown = false; render(); })), !busy);
            buttonsRow = buttons.getChildCount() > 0 ? buttons : null;
            if (buttonsRow != null) body.addView(buttons, SectionUi.lp(0, 0));
            // A pack built before the app kept the game's original code, which the last start could not add: the companion works with less, and only this row says so
            if (have != null && packNotice != null) body.addView(ui.setting("⚠️", packNotice[0], packNotice[1], null), SectionUi.lp(0, 0));
            String ss = secondScreen(c, titleId);
            body.addView(ui.setting("📱", "Second screen", ss.equals(OFF) ? (secondScreenChosen(c, titleId) ? "Off: the companion does not load" : "Off: no second display found, the companion does not load") : "Physical: the companion shows on the second display",
                    v -> new AlertDialog.Builder(c).setTitle("Second screen").setSingleChoiceItems(new String[] {"Physical display", "Off"}, ss.equals(OFF) ? 1 : 0, (d, w) -> {
                        dev.peanuts.ui.Feel.picked(d);
                        secondScreen(c, titleId, w == 1 ? OFF : PHYSICAL);
                        d.dismiss();
                        render();
                    }).setNegativeButton("Cancel", null).show()), SectionUi.lp(0, 0));
        }

        void install(Companions.Item item) {
            if (root == null) { dev.peanuts.ui.Feel.refused(c); Toast.makeText(c, "Build the game first: the companion goes beside its pack.", Toast.LENGTH_LONG).show(); return; }
            busy = true; busyText = "Downloading " + Companions.label(item.packageName, item.version) + "…";
            render();
            new Thread(() -> {
                String err = null;
                try {
                    Companions.install(item, root, c.getCacheDir(), (what, done, total) -> main.post(() -> { busyText = what + (total > 0 ? " " + (done * 100 / total) + " %" : ""); render(); }), c);   // ctx = c: refuses with "close the game first" while the game is running (found in review)
                } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                final String ferr = err;
                main.post(() -> {
                    busy = false;
                    installedKnown = false;   // Found in review: the one point this cache is invalidated - an install actually finished, not merely ticked
                    if (ferr == null) dev.peanuts.ui.Feel.accepted(c); else dev.peanuts.ui.Feel.refused(c);   // the install ended: done, or refused
                    Toast.makeText(c, ferr == null ? "Installed " + Companions.label(item.packageName, item.version) + ". It loads at the game's next start." : "Install failed: " + ferr, Toast.LENGTH_LONG).show();
                    render();
                });
            }, "peanuts-install").start();
        }

        void remove(String pkg) {
            new AlertDialog.Builder(c).setTitle("Remove the companion?").setMessage(pkg + " is deleted from the game's folder. The catalog can install it again.")
                    .setPositiveButton("Remove", dev.peanuts.ui.Feel.confirm((d, w) -> {
                        // off the UI thread: the delete walks shared storage and waits for the package's lock (an install of it may be downloading)
                        Handler mainUi = new Handler(Looper.getMainLooper());
                        new Thread(() -> {
                            String err = null;
                            try { Companions.remove(root, pkg, c); } catch (IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                            final String ferr = err;
                            mainUi.post(() -> {
                                if (ferr != null) dev.peanuts.ui.Feel.refused(c);
                                Toast.makeText(c, ferr == null ? "Removed" : "Remove failed: " + ferr, ferr == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                                installedKnown = false;
                                render();
                            });
                        }, "peanuts-companion-remove").start();
                    })).setNegativeButton("Cancel", null).show();
        }
    }
}
