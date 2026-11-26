package dev.peanuts.mods;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nxnative.android.CheatsBridge;
import dev.nxnative.android.R;
import dev.peanuts.ui.Feel;
import dev.peanuts.ui.Icons;
import dev.peanuts.ui.PUi;

/**
 * Mods &amp; Cheats › Add (SPEC-mod-menu.md, Package B 2): a file (.zip or .txt) or a folder from this device, read off the UI thread
 * (CheatImport), then one dialog in the app's AlertDialog look: the cheats for the game's version ("Add cheats"), or why not (another version, another game, no
 * build named, nothing found). A pick with romfs or exefs folders is a mod: it goes to the mod import (ModsSection) on the page's Mods tab. Nothing is downloaded.
 * An .mncf (or an .mncp pack of them) is added as it is; any other cheat file becomes an .mncf as it is added (CheatSources.add: MncfConvert), off the UI thread,
 * and is made ready for the game's build before the page lists it. Export ({@link #export}) writes the game's sources as .mncf or .mncp, or as a ZIP in
 * Atmosphère's folders for other emulators (MncfExport).
 * The pickers' results come back through MainActivity.onActivityResult → dev.peanuts.ui.LibrarySettings.result → {@link #result}.
 */
public final class CheatImportUi {
    private CheatImportUi() {}

    public static final int REQ_FILE = CheatsBridge.REQ_FILE, REQ_FOLDER = CheatsBridge.REQ_FOLDER, REQ_EXPORT = CheatsBridge.REQ_EXPORT;   // the cheat rows' codes: this page replaces those rows
    private static boolean waiting, exporting;   // a pick or an export of this page is open (the rows' sheet uses the same codes)

    /** Add: a file or a folder */
    static void add(Activity a, String titleId) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        PUi.add(box, PUi.body(a, "Cheats or a mod for this game, from this device. Peanuts never offers any of its own."), 0);
        PUi.Setting file = new PUi.Setting(a, "", "A file", Feel.row(v -> { d[0].dismiss(); pick(a, false); })).value("A .zip, a .txt, an .mncf or an .mncp").chevron(a);
        Icons.tile((TextView) file.view.getChildAt(0), "list");
        PUi.add(box, file.view, 12);
        PUi.Setting folder = new PUi.Setting(a, "", "A folder", Feel.row(v -> { d[0].dismiss(); pick(a, true); })).value("With Atmosphère, Eden or Ryujinx folders, or a mod's").chevron(a);
        Icons.tile((TextView) folder.view.getChildAt(0), "folder");
        PUi.add(box, folder.view, 4);
        d[0] = PUi.sheet(a, "Add cheats or mods", box).setNegativeButton("Cancel", null).create();
        Feel.dialog(d[0]);
        d[0].show();
    }

    private static void pick(Activity a, boolean folder) {
        Intent i = folder ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) : new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "text/plain", "application/octet-stream", "application/toml"});
        i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        try { waiting = true; a.startActivityForResult(i, folder ? REQ_FOLDER : REQ_FILE); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(a, "This device has no file picker.", Toast.LENGTH_LONG).show(); }
    }

    private static boolean exportZip;   // the export that is open: a ZIP for other emulators, else .mncf / .mncp
    private static MncfExport.Zip exportPlan;   // the ZIP the dialog worked out (it reads the game's main once), written as it is; null: worked out when written

    /**
     * Export: one dialog with the two ways the cheats leave Peanuts. The Mistel cheat file (one .mncf, or an .mncp for several sources), recommended and picked:
     * every cheat for every version, with what is on and the numbers set. Or a ZIP in Atmosphère's folders for other emulators: one file for each version,
     * with what Atmosphère code can say; which cheats cannot go in (and why) is worked out off the UI thread and shown under it, before anything is written
     */
    static void export(Activity a, CheatsPage.Game g, int sources) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        boolean[] zip = {false};
        MncfExport.Zip[] plan = new MncfExport.Zip[1];
        boolean[] planned = new boolean[1];
        AlertDialog[] d = new AlertDialog[1];
        LinearLayout mncf = choice(a, "check", "Mistel cheat file", "Recommended", (sources == 1 ? "One .mncf" : "An .mncp of " + sources + " files")
                + ": every cheat for every version, with what you turned on and the numbers you set.");
        LinearLayout other = choice(a, "folder", "ZIP for other emulators", null, "Atmosphère's folders, one file for each version, for Atmosphère, Eden, Ryujinx and others.");
        LinearLayout details = new LinearLayout(a);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        details.addView(PUi.progress(a), PUi.wrap());   // until the plan is known
        PUi.add(other, details, 0);
        Runnable show = () -> {
            select(a, mncf, !zip[0]);
            select(a, other, zip[0]);
            details.setVisibility(zip[0] ? View.VISIBLE : View.GONE);
            if (zip[0]) box.post(() -> ((android.widget.ScrollView) box.getParent()).smoothScrollTo(0, other.getTop()));   // what goes in and what stays out, in view
            if (d[0] != null && d[0].getButton(AlertDialog.BUTTON_POSITIVE) != null)
                d[0].getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!zip[0] || !planned[0] || plan[0] != null && plan[0].cheats > 0);
        };
        mncf.setOnClickListener(Feel.row(v -> { zip[0] = false; show.run(); }));
        other.setOnClickListener(Feel.row(v -> { zip[0] = true; show.run(); }));
        mncf.setContentDescription("Mistel cheat file, recommended");
        other.setContentDescription("ZIP for other emulators");
        box.addView(mncf);
        PUi.add(box, other, 10);
        d[0] = PUi.sheet(a, "Export cheats", box).setNegativeButton("Cancel", null)
                .setPositiveButton("Export", Feel.confirm((x, w) -> pickExport(a, g, sources, zip[0], zip[0] ? plan[0] : null))).create();
        Feel.dialog(d[0]);
        d[0].show();
        show.run();
        new Thread(() -> {
            MncfExport.Zip z = null;
            try {
                z = zipOf(a, g);
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("peanuts", "cheats: export: the ZIP's plan: " + e);
            }
            MncfExport.Zip zz = z;
            a.runOnUiThread(() -> {
                if (!d[0].isShowing()) return;
                plan[0] = zz;
                planned[0] = true;
                details.removeAllViews();
                zipLines(a, g, zz, details);
                show.run();
            });
        }, "peanuts-cheat-export").start();
    }

    /** One way to export, as a card the player picks: a tile, its title (with a badge), a line under it; select() draws it picked or not */
    private static LinearLayout choice(Activity a, String icon, String title, String badge, String sub) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(PUi.dp(a, 14), PUi.dp(a, 10), PUi.dp(a, 14), PUi.dp(a, 10));
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout row = PUi.row(a);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tile = PUi.text(a, "", 12, R.color.bn_text, 400);
        tile.setTag(icon);
        row.addView(tile, new LinearLayout.LayoutParams(PUi.dp(a, 44), PUi.dp(a, 44)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(a, 14), 0, PUi.dp(a, 8), 0);
        LinearLayout head = PUi.row(a);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(PUi.text(a, title, 17, R.color.bn_text, 800), PUi.wrap());
        if (badge != null) {
            LinearLayout.LayoutParams lp = PUi.wrap();
            lp.leftMargin = PUi.dp(a, 8);
            head.addView(CheatsPage.chip(a, badge, true), lp);
        }
        col.addView(head);
        TextView s = PUi.small(a, sub);
        s.setLineSpacing(0, 1.1f);
        col.addView(s);
        row.addView(col, PUi.weight());
        TextView mark = new TextView(a);   // the pick: a ring with a dot, or an empty ring
        row.addView(mark, new LinearLayout.LayoutParams(PUi.dp(a, 24), PUi.dp(a, 24)));
        card.addView(row);
        return card;
    }

    /** A choice card drawn picked (tinted, banana outline, banana tile, a dot in the ring) or not (an outline, a quiet tile, an empty ring) */
    private static void select(Activity a, LinearLayout card, boolean on) {
        LinearLayout row = (LinearLayout) card.getChildAt(0);
        TextView tile = (TextView) row.getChildAt(0), mark = (TextView) row.getChildAt(2);
        card.setBackground(PUi.ripple(a, PUi.round(PUi.col(a, on ? R.color.bn_tint : R.color.bn_card), PUi.dp(a, 16), PUi.col(a, on ? R.color.bn_banana : R.color.bn_card_stroke), PUi.dp(a, on ? 2 : 1))));
        tile.setBackground(PUi.round(PUi.col(a, on ? R.color.bn_banana : R.color.bn_tint), PUi.dp(a, 13), 0, 0));
        android.graphics.drawable.Drawable icon = Icons.get(a, (String) tile.getTag(), on ? Icons.Ink.ON_BANANA : Icons.Ink.NORMAL);
        tile.setForeground(icon == null ? null : new android.graphics.drawable.InsetDrawable(icon, PUi.dp(a, 10)));
        mark.setBackground(PUi.round(android.graphics.Color.TRANSPARENT, PUi.dp(a, 12), PUi.col(a, on ? R.color.bn_banana : R.color.bn_text_2), PUi.dp(a, 2)));
        mark.setForeground(on ? new android.graphics.drawable.InsetDrawable(PUi.round(PUi.col(a, R.color.bn_banana), PUi.dp(a, 6), 0, 0), PUi.dp(a, 6)) : null);
        card.setSelected(on);
    }

    /** The ZIP of the game's sources as it would be written now (MncfExport.zip) */
    private static MncfExport.Zip zipOf(Activity a, CheatsPage.Game g) throws IOException {
        File folder = CheatsBridge.folder(a, g.titleId);
        String build = CheatsBridge.builds(g.titleId, g.packDir).stream().findFirst().orElse(null);
        CheatSources.Merged m = build == null ? null : CheatsBridge.merged(a, g.titleId, g.gameDir, g.packDir, build, CheatsBridge.textEnd(g.packDir), true);
        return MncfExport.zip(folder, g.name, CheatsBridge.game(g.titleId, build, g.packDir), m, CheatValues.read(folder));
    }

    /** Most of the cheats left out the dialog names one by one; the rest are counted */
    static final int LEFT_NAMED = 5;

    /** Under the ZIP's card: how many cheats go in, for which versions, and calmly, which ones cannot and why (the Mistel cheat file keeps them) */
    private static void zipLines(Activity a, CheatsPage.Game g, MncfExport.Zip z, LinearLayout to) {
        List<String[]> lines = new ArrayList<>();
        if (z == null) {
            lines.add(new String[] {"info", "Checked when you export", "Which cheats fit an Atmosphère file is worked out as the ZIP is written."});
        } else if (z.cheats == 0) {
            lines.add(new String[] {"info", "None of them fits a ZIP", "An Atmosphère file cannot hold what these cheats do. The Mistel cheat file keeps them all."});
        } else {
            List<String> per = new ArrayList<>();
            for (Map.Entry<String, Integer> e : z.builds.entrySet()) {
                String v = z.versions.get(e.getKey());
                if (v == null) v = CheatsBridge.version(g.titleId, e.getKey());
                per.add(per.isEmpty() ? CheatsPage.count(e.getValue(), "cheat") + " for " + (v != null ? v : "build " + CheatsPage.shortBuild(e.getKey())) : e.getValue() + " for " + (v != null ? v : "build " + CheatsPage.shortBuild(e.getKey())));
            }
            lines.add(new String[] {"check", String.join(", ", per), z.builds.size() == 1 ? "One file, where Atmosphère and the others look for it." : "One file for each version, where Atmosphère and the others look for them."});
            if (!z.left.isEmpty()) lines.add(new String[] {"info", (z.left.size() == 1 ? "1 stays out" : z.left.size() + " stay out") + " of the ZIP",
                    "An Atmosphère file cannot hold what " + (z.left.size() == 1 ? "it does" : "they do") + ". The Mistel cheat file keeps " + (z.left.size() == 1 ? "it." : "them.")});
        }
        for (String[] line : lines) PUi.add(to, line(a, line), 12);
        if (z == null || z.left.isEmpty() || z.cheats == 0) return;
        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(PUi.dp(a, 44), 0, 0, 0);   // under the line's text, past its tile
        for (int i = 0; i < z.left.size() && i < LEFT_NAMED; i++) {
            TextView n = PUi.text(a, z.left.get(i)[0], 14, R.color.bn_text, 600);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            PUi.add(names, n, i == 0 ? 0 : 8);
            TextView why = PUi.small(a, z.left.get(i)[1]);
            why.setLineSpacing(0, 1.1f);
            names.addView(why);
        }
        if (z.left.size() > LEFT_NAMED) PUi.add(names, PUi.small(a, "And " + (z.left.size() - LEFT_NAMED) + " more."), 8);
        PUi.add(to, names, 10);
    }

    /** The export's file picked: the name and kind of the format chosen */
    private static void pickExport(Activity a, CheatsPage.Game g, int sources, boolean zip, MncfExport.Zip plan) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(zip ? "application/zip" : "application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, zip ? MncfExport.zipName(g.name) : MncfExport.fileName(g.name, sources > 1));
        try { exporting = true; exportZip = zip; exportPlan = plan; a.startActivityForResult(i, REQ_EXPORT); }
        catch (android.content.ActivityNotFoundException e) { exporting = false; Toast.makeText(a, "This device has no file picker.", Toast.LENGTH_LONG).show(); }
    }

    /** The export written to the document picked, off the UI thread; a ZIP as the dialog worked it out (plan), or worked out now when it had not yet */
    private static void exported(Activity a, CheatsPage.Game g, Uri u, boolean zip, MncfExport.Zip plan) {
        CheatPick.work(a, "Export cheats", () -> {
            File folder = CheatsBridge.folder(a, g.titleId);
            byte[] bytes;
            String done;
            if (zip) {
                MncfExport.Zip z = plan != null ? plan : zipOf(a, g);
                if (z == null) throw new IOException("There are no added cheats to export.");
                if (z.cheats == 0) throw new IOException("None of the cheats fits an Atmosphère file. Export them as a Mistel cheat file, which keeps them all.");
                bytes = z.bytes;
                done = "Exported " + CheatsPage.count(z.cheats, "cheat") + " as a ZIP" + (z.left.isEmpty() ? "." : ". " + (z.left.size() == 1 ? "1 could" : z.left.size() + " could") + " not go in.");
            } else {
                String build = CheatsBridge.builds(g.titleId, g.packDir).stream().findFirst().orElse(null);
                CheatSources.Merged m = build == null ? null : CheatsBridge.merged(a, g.titleId, g.gameDir, g.packDir, build, CheatsBridge.textEnd(g.packDir), true);
                MncfExport.Out out = MncfExport.make(folder, g.name, m, CheatsBridge.on(a, g.titleId), CheatValues.read(folder));
                if (out == null) throw new IOException("There are no added cheats to export.");
                bytes = out.bytes;
                done = "Exported " + CheatsPage.count(out.cheats, "cheat") + (out.pack ? " of " + out.sources + " sources as a pack." : ".");
            }
            try (java.io.OutputStream os = a.getContentResolver().openOutputStream(u)) {
                if (os == null) throw new IOException("The place picked cannot be written. Pick another one.");
                os.write(bytes);
            }
            return done;
        }, done -> {
            Feel.accepted(a);
            Toast.makeText(a, done, Toast.LENGTH_LONG).show();
        }, e -> {
            Feel.refused(a);
            android.util.Log.w("peanuts", "cheats: export: " + e);
            message(a, "Not exported", e instanceof IOException && e.getMessage() != null && e.getMessage().endsWith(".") ? e.getMessage() : "The cheats could not be written there. Nothing was changed here.");
        });
    }

    /** LibrarySettings.result (PEANUTS: Mods & Cheats' pickers, asked before CheatsBridge's): true when the request was Add's or Export's, the ones this page started */
    public static boolean result(Activity a, int request, int result, Intent data) {
        if (request == REQ_EXPORT && exporting) {
            exporting = false;
            CheatsPage.Game g = CheatsPage.shown();
            MncfExport.Zip plan = exportPlan;
            exportPlan = null;
            if (result == Activity.RESULT_OK && data != null && data.getData() != null && g != null) exported(a, g, data.getData(), exportZip, plan);
            return true;
        }
        if (request != REQ_FILE && request != REQ_FOLDER || !waiting) return false;
        waiting = false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        CheatsPage.Game g = CheatsPage.shown();
        if (g == null) { Toast.makeText(a, "Open the game's Mods & Cheats again to add them.", Toast.LENGTH_LONG).show(); return true; }
        Uri u = data.getData();
        boolean folder = request == REQ_FOLDER;
        CheatPick.read(a, u, folder, g.titleId, CheatsBridge.builds(g.titleId, g.packDir), "Add cheats or mods", r -> {
            if (r.error != null) { Feel.refused(a); message(a, "Not added", r.error); return; }
            decide(a, g, r.found, r.name == null ? "it" : r.name, r.from, u, r.display, folder);
        });
        return true;
    }

    /** One dialog for what the pick holds */
    static void decide(Activity a, CheatsPage.Game g, CheatImport.Found f, String what, String from, Uri u, String display, boolean folder) {
        String build = CheatsBridge.builds(g.titleId, g.packDir).stream().findFirst().orElse(null);
        String version = build == null ? null : CheatsBridge.version(g.titleId, build);
        String yours = version != null ? version : "your version";
        String kind = folder ? "folder" : CheatSources.FROM_ZIP.equals(from) ? "zip" : "file";
        if (f.mod) {   // romfs or exefs: the mod import, which takes a mod's cheats along
            if (!CheatsPage.showMods() || !dev.peanuts.settings.ModsSection.installPicked(a, u, display, folder)) Toast.makeText(a, "Open the game's Mods & Cheats again to install the mod.", Toast.LENGTH_LONG).show();
            return;
        }
        if (build == null) { message(a, "Build the game first", "Cheats are made for one build of the game, and this game's build is not known yet. Prepare the game, then add them."); return; }
        File folderOf = CheatsBridge.folder(a, g.titleId);
        if (!f.mncf.isEmpty()) { decideMncf(a, g, f, what, from, build, yours); return; }
        if (!f.forGame.isEmpty()) {
            for (CheatImport.Hit h : f.forGame) {
                CheatSources.Source same = CheatSources.sameAs(folderOf, h.text);
                if (same != null && f.forGame.size() == 1) { message(a, "Added already", same.name + " has the same cheats. Nothing was added."); return; }
            }
            int n = f.cheats();
            List<String[]> lines = new ArrayList<>();
            lines.add(new String[] {"check", CheatsPage.count(n, "cheat") + " for your version",
                    "Build " + CheatsPage.shortBuild(build) + ", the one your game was built from." + (f.groups() > 0 ? " " + CheatsPage.count(f.groups(), "group") + "." : "")});
            lines.add(new String[] {"lock", "Checked before every write", "Each cheat that changes the game's code names the code it expects there, taken from your game. Peanuts compares it before it writes."});
            lines.add(new String[] {"update", "Ready for game updates", "Its places are found again by the code around them, so the cheats keep working after an update."});
            if (f.unsupported() > 0) lines.add(new String[] {"info", f.unsupported() + " more " + (f.unsupported() == 1 ? "is" : "are") + " not supported",
                    "They use a part of the cheat format Peanuts does not run. They are listed and stay off."});
            if (f.restores()) lines.add(new String[] {"info", "Restore Code is left out", "Peanuts puts the original code back by itself when you turn a cheat off."});
            if (!f.otherVersion.isEmpty()) lines.add(new String[] {"info", CheatsPage.count(f.otherVersion.size(), "file") + " for another version",
                    "Kept with them: they work when the game is that version."});
            if (!folder && CheatSources.FROM_ZIP.equals(from)) lines.add(new String[] {"info", "No mods in this zip", "Mods go in the same way: a zip with romfs or exefs folders."});
            View card = formatCard(a, "update", "Becomes a Mistel cheat file", "Atmosphère cheats from " + what + ". Peanuts keeps them as one .mncf for every version of the game.", null);
            dialog(a, "Add cheats", card, null, lines,
                    "Cancel", null, "Add " + CheatsPage.count(n, "cheat"), () -> take(a, f, from, n, display != null ? display : what));
            return;
        }
        if (!f.otherVersion.isEmpty()) {
            List<String> theirs = new ArrayList<>();
            int n = 0;
            for (CheatImport.Hit h : f.otherVersion) { if (!theirs.contains(CheatsPage.shortBuild(h.bid))) theirs.add(CheatsPage.shortBuild(h.bid)); n += h.cheats(); }
            List<String[]> lines = new ArrayList<>();
            lines.add(new String[] {"warning", "In the " + kind + ": build " + String.join(", ", theirs),
                    "Your game: " + yours + ", build " + CheatsPage.shortBuild(build) + ". None of the " + CheatsPage.count(n, "cheat") + " would hit the right places."});
            lines.add(new String[] {"info", "Look for cheats for " + yours, "The file has to be named " + build + ".txt or start with that build."});
            dialog(a, "These cheats are for another version", "Cheats only work on the exact build they were made for.", lines,
                    "Keep them anyway", () -> take(a, f, from, 0, display != null ? display : what), "OK", null);
            return;
        }
        if (!f.otherGame.isEmpty() || !f.mncfOtherGame.isEmpty()) {
            String other = !f.otherGame.isEmpty() ? f.otherGame.get(0).tid : f.mncfOtherGame.get(0).file.gameId();
            message(a, "These cheats are for another game", "The " + kind + " has them under the title ID " + other + ", not this game's. Nothing was added.");
            return;
        }
        if (!f.mncfBroken.isEmpty()) {
            android.util.Log.w("peanuts", "cheats: .mncf files that cannot be read: " + f.mncfBroken);
            message(a, "These cheats cannot be read", "The " + kind + " has a Mistel cheat file Peanuts cannot read: it is damaged or of a newer kind. Nothing was added.");
            return;
        }
        if (!f.noBuild.isEmpty()) {
            List<String[]> lines = new ArrayList<>();
            lines.add(new String[] {"info", "Name it " + build + ".txt", "Or start it with the line build " + build + ", if it was made for " + yours + "."});
            dialog(a, "These cheats do not name their version", "A cheat file has to say which build of the game it was made for. Nothing was added.", lines, null, null, "OK", null);
            return;
        }
        message(a, "No cheats in it", "There is no cheat file in the " + kind + ". Cheats are text files in the Atmosphère, Eden or Ryujinx folders; a mod is a zip or a folder with romfs or exefs folders.");
    }

    /** An .mncf pick (one or more files, or an .mncp): one dialog, the files added as they are */
    private static void decideMncf(Activity a, CheatsPage.Game g, CheatImport.Found f, String what, String from, String build, String yours) {
        File folderOf = CheatsBridge.folder(a, g.titleId);
        List<CheatImport.MncfHit> fresh = new ArrayList<>();
        List<String> had = new ArrayList<>();
        for (CheatImport.MncfHit h : f.mncf) { CheatSources.Source same = CheatSources.sameMncf(folderOf, h.data); if (same != null) had.add(same.name); else fresh.add(h); }
        if (fresh.isEmpty()) { message(a, "Added already", String.join(", ", had) + (had.size() == 1 ? " has" : " have") + " the same cheats. Nothing was added."); return; }
        // A pack with the id of one the player has is a version of it (section 17.6): its update screen
        if (f.packData != null) {
            io.github.davdxpx.mistel.mncf.Pack pk = null;
            try { pk = io.github.davdxpx.mistel.mncf.PackReader.read(f.packData, MncfBuild.TRUST); } catch (io.github.davdxpx.mistel.mncf.MncfException ignored) { }
            CheatUpdates.Held held = CheatUpdates.heldOf(folderOf, pk);
            if (held != null) { CheatUpdatesUi.showPack(a, new CheatsPage.Model(a, g), held, f.packData, true); return; }
        }
        // A file with the id of one the player has is a version of it (Mistel's spec, section 17.2): its update screen, so what they set stays; the others after it
        for (CheatImport.MncfHit h : fresh) {
            CheatSources.Source of = CheatUpdatesUi.versionOf(folderOf, h.file);
            if (of == null) continue;
            List<CheatImport.MncfHit> rest = new ArrayList<>(fresh);
            rest.remove(h);
            CheatImport.Found more = new CheatImport.Found();
            more.mncf.addAll(rest);
            more.name = f.name;
            more.pack = f.pack;
            more.packData = f.packData;
            CheatUpdatesUi.show(a, new CheatsPage.Model(a, g), of, h.data, true);
            if (!rest.isEmpty()) decideMncf(a, g, more, what, from, build, yours);
            return;
        }
        List<io.github.davdxpx.mistel.mncf.MncfFile> files = new ArrayList<>();
        boolean ours = false;
        List<String> authors = new ArrayList<>();
        for (CheatImport.MncfHit h : fresh) {
            files.add(h.file);
            if (MncfBuild.builds(h.file).contains(build)) ours = true;
            for (String x : h.file.authors()) if (!authors.contains(x)) authors.add(x);
        }
        MncfFacts x = MncfFacts.of(files);
        int n = x.cheats;
        String by = authors.isEmpty() ? "" : " by " + String.join(", ", authors);
        View card = formatCard(a, "check", "Mistel cheat file", (fresh.size() == 1 ? fresh.get(0).name() + ".mncf" : fresh.size() + " files of a pack") + by, x.signer);
        List<String[]> lines = facts(x, yours, ours);
        lines.add(new String[] {"search", "Looked up in your game next", "Peanuts finds each cheat's place in " + yours + " and lists the ones that cannot work, with the reason."});
        java.util.Set<String> hosts = new java.util.LinkedHashSet<>();   // where its author puts its new versions (Mistel's spec, section 17); a pack's own counts for its files
        io.github.davdxpx.mistel.mncf.Pack pk = null;
        if (f.packData != null) try { pk = io.github.davdxpx.mistel.mncf.PackReader.read(f.packData, MncfBuild.TRUST); } catch (io.github.davdxpx.mistel.mncf.MncfException ignored) { }
        if (pk != null) { if (pk.update() != null) hosts.add(pk.update().host()); }
        else for (io.github.davdxpx.mistel.mncf.MncfFile mf : files) if (mf.update() != null) hosts.add(mf.update().host());
        if (!hosts.isEmpty()) lines.add(new String[] {"update", "New versions from " + String.join(", ", hosts), "Its author says where they are. Peanuts asks you once whether it may look there."});
        if (!had.isEmpty()) lines.add(new String[] {"info", "Added already: " + String.join(", ", had), "Those are not added again."});
        dialog(a, "Add cheats", card, null, lines, "Cancel", null, "Add " + CheatsPage.count(n, "cheat"), () -> takeMncf(a, fresh, from, n, f.packData));
    }

    /** What an .mncf brings, as the dialog's lines (only what it has): its cheats and versions, the checks, the updates, the numbers, the words */
    static List<String[]> facts(MncfFacts x, String yours, boolean ours) {
        List<String[]> lines = new ArrayList<>();
        String v = x.versionWords();
        lines.add(new String[] {"check", CheatsPage.count(x.cheats, "cheat"), ours ? (x.versions.size() > 1 ? "Made for " + v + ", " + yours + " among them." : "Made for " + yours + ", your version.")
                : v == null ? "It names no version: the cheats that find their place in the game's code work on any." : "Made for " + v + ". The cheats that find their place in the game's code can work on " + yours + " too."});
        if (x.checked > 0) lines.add(checked(x.checked, x.cheats));
        if (x.patterns > 0) lines.add(updates(x.patterns));
        if (x.values > 0) lines.add(new String[] {"scale", CheatsPage.count(x.values, "number") + " you can set", "In the game's Mods tab, within the range the file gives."});
        if (x.described > 0) lines.add(new String[] {"info", x.described == x.cheats ? (x.cheats == 1 ? "It says what it does" : "Each says what it does") : x.described == 1 ? "1 says what it does" : x.described + " say what they do",
                "Shown on the cheat and on the second screen."});
        return lines;
    }

    static String[] checked(int checked, int cheats) {
        String who = checked == cheats ? (cheats == 1 ? "It changes" : "Each changes") : checked == 1 ? "1 of them changes" : checked + " of them change";
        return new String[] {"lock", "Checked before every write", who + " the game's code only where the code it expects is, so nothing is written into the wrong place."};
    }

    static String[] updates(int patterns) {
        return new String[] {"update", "Ready for game updates", (patterns == 1 ? "1 cheat finds its place by the code around it, so it keeps" : patterns + " cheats find their place by the code around it, so they keep")
                + " working after an update."};
    }

    /**
     * The card at the top of the Add dialog and the sheet after it: the Mistel cheat file, what it is called and who made it, and who signed it when a key
     * Peanuts trusts did (an unsigned file gets no mark: it is as any other)
     */
    static View formatCard(Activity a, String icon, String title, String sub, String signer) {
        LinearLayout card = PUi.row(a);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(PUi.dp(a, 14), PUi.dp(a, 12), PUi.dp(a, 14), PUi.dp(a, 12));
        card.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 16), 0, 0));
        TextView tile = PUi.text(a, "", 12, R.color.bn_on_banana, 400);
        tile.setBackground(PUi.round(PUi.col(a, R.color.bn_banana), PUi.dp(a, 13), 0, 0));
        card.addView(tile, new LinearLayout.LayoutParams(PUi.dp(a, 44), PUi.dp(a, 44)));
        android.graphics.drawable.Drawable mark = Icons.get(a, icon, Icons.Ink.ON_BANANA);   // on the banana tile: Icons.tile draws in the row tile's colours
        if (mark != null) tile.setForeground(new android.graphics.drawable.InsetDrawable(mark, PUi.dp(a, 10)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(a, 14), 0, PUi.dp(a, 8), 0);
        TextView t = PUi.text(a, title, 17, R.color.bn_text, 800);
        col.addView(t);
        TextView s = PUi.small(a, sub);
        s.setMaxLines(3);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(s);
        card.addView(col, PUi.weight());
        if (signer != null) card.addView(CheatsPage.chip(a, "Signed by " + signer, true), PUi.wrap());
        return card;
    }

    /** The pick's sources added (one per Eden or Ryujinx set, else one), each made one .mncf, on; n: the game's cheats, for the line after */
    private static void take(Activity a, CheatImport.Found f, String from, int n, String origin) {
        CheatsPage.Game g = CheatsPage.shown();
        if (g == null) return;
        File folder = CheatsBridge.folder(a, g.titleId);
        added(a, g, n, true, () -> {
            List<CheatSources.Source> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            MncfConvert.Known k = CheatsBridge.known(a, g.titleId, g.name, g.packDir);
            for (Map.Entry<String, Map<String, String>> e : f.sources().entrySet()) out.add(CheatSources.add(folder, e.getKey(), from, e.getValue(), now++, k, origin));
            return out;
        });
    }

    /** .mncf files added as they are, on; pack: the .mncp they came from, kept for its new versions when its manifest has an id (CheatUpdates.keep), else null */
    private static void takeMncf(Activity a, List<CheatImport.MncfHit> files, String from, int n, byte[] pack) {
        CheatsPage.Game g = CheatsPage.shown();
        if (g == null) return;
        File folder = CheatsBridge.folder(a, g.titleId);
        added(a, g, n, false, () -> {
            List<CheatSources.Source> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            String key = pack == null ? null : CheatUpdates.keep(folder, pack);
            for (CheatImport.MncfHit h : files) {
                CheatSources.Source s = CheatSources.addMncf(folder, h.name(), from, h.data, now++);
                if (key != null) CheatUpdates.fromPack(s, key, h.path);
                out.add(s);
            }
            return out;
        });
    }

    /** What adding sources gave: the cheats it switched on (the file's defaults) and what the new files bring */
    private static final class Added {
        final java.util.Set<String> on = new java.util.LinkedHashSet<>();
        MncfFacts facts;
        String version;
        int work, refused;   // the new sources' cheats that work on this version, and the ones that cannot (listed with the reason)
        List<CheatSources.Source> made = new ArrayList<>();   // the new sources: each whose file names its new versions is asked about once (CheatUpdatesUi.ask)
        String named;        // the new files as the card names them: "Example cheats.mncf by Example Team"
    }

    /**
     * Sources added off the UI thread (add: makes them; a conversion reads the game's main, so the wait says it looks up the cheats there), then made ready for
     * the game's build, and their cheats that are on by default (an export's: the ones that were on) switched on; then a sheet with what the new Mistel cheat
     * files bring (converted: what the conversion made of them), and the page again. A failure leaves what was added before it
     */
    private static void added(Activity a, CheatsPage.Game g, int n, boolean converted, java.util.concurrent.Callable<List<CheatSources.Source>> add) {
        CheatPick.work(a, "Add cheats", converted ? "Finding each cheat's place in your game's code." : null, () -> {
            List<CheatSources.Source> made = add.call();
            Added out = new Added();
            out.made.addAll(made);
            String build = CheatsBridge.builds(g.titleId, g.packDir).stream().findFirst().orElse(null);
            out.version = build == null ? null : CheatsBridge.version(g.titleId, build);
            if (build != null) {
                CheatSources.Merged m = CheatsBridge.merged(a, g.titleId, g.gameDir, g.packDir, build, CheatsBridge.textEnd(g.packDir), true);
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (CheatSources.Source s : made) ids.add(s.id);
                for (CheatSources.Row r : m.rows) if (ids.contains(r.block.id) && r.cheat.mncf != null && r.cheat.mncf.isDefault && r.cheat.switchable()) out.on.add(r.key);
                for (CheatSources.Block b : m.blocks) if (ids.contains(b.id)) for (CheatFile.Cheat c : b.parsed.cheats) { if (c.switchable()) out.work++; else out.refused++; }
            }
            List<io.github.davdxpx.mistel.mncf.MncfFile> files = new ArrayList<>();
            List<String> authors = new ArrayList<>();
            for (CheatSources.Source s : made) {
                try { files.add(io.github.davdxpx.mistel.mncf.Mncf.load(java.nio.file.Files.readAllBytes(s.file().toPath()))); } catch (IOException | io.github.davdxpx.mistel.mncf.MncfException ignored) { }
            }
            for (io.github.davdxpx.mistel.mncf.MncfFile f : files) for (String x : f.authors()) if (!authors.contains(x)) authors.add(x);
            out.facts = MncfFacts.of(files);
            out.named = (made.size() == 1 ? made.get(0).name + ".mncf" : made.size() + " files") + (authors.isEmpty() ? "" : " by " + String.join(", ", authors));
            return out;
        }, out -> {
            if (!out.on.isEmpty()) { java.util.Set<String> all = CheatsBridge.on(a, g.titleId); all.addAll(out.on); CheatsBridge.on(a, g.titleId, all); }
            Feel.accepted(a);
            CheatsPage.rebuild();
            if (n == 0) { Toast.makeText(a, "Kept for another version.", Toast.LENGTH_LONG).show(); CheatUpdatesUi.ask(a, out.made, null); return; }
            String yours = out.version != null ? out.version : "your version";
            MncfFacts x = out.facts;
            List<String[]> lines = new ArrayList<>();
            int all = out.work + out.refused;
            lines.add(new String[] {"check", out.refused == 0 ? (all == 1 ? "It works on " + yours : "All " + all + " work on " + yours) : out.work + " of " + all + " work on " + yours,
                    "Each was looked up in your game's code."});
            if (out.refused > 0) lines.add(new String[] {"info", out.refused == 1 ? "1 cannot work here" : out.refused + " cannot work here", "Listed with the reason, and off."});
            if (x.checked > 0) lines.add(checked(x.checked, x.cheats));
            if (x.patterns > 0) lines.add(updates(x.patterns));
            if (x.values > 0) lines.add(new String[] {"scale", CheatsPage.count(x.values, "number") + " you can set", "In the game's Mods tab, within the range the file gives."});
            if (converted && x.rawOnly > 0) lines.add(new String[] {"layers", x.rawOnly == 1 ? "1 kept as its own code" : x.rawOnly + " kept as their own code",
                    "Code that does more than change places Peanuts can name runs as it came, on " + yours + "."});
            lines.add(new String[] {"controller", out.on.isEmpty() ? "Turn on the ones you want" : (out.on.size() == 1 ? "1 is on" : out.on.size() + " are on") + ", as the file has them",
                    "Here or in the game's Mods tab. A cheat switches at once in a running game."});
            View card = formatCard(a, converted ? "update" : "check", converted ? "Now a Mistel cheat file" : "Mistel cheat file",
                    converted ? "One .mncf for every version of " + g.name + ": " + out.named : out.named, x.signer);
            List<CheatSources.Source> asked = out.made;
            dialog(a, "Cheats added", card, null, lines, null, null, "Done", () -> CheatUpdatesUi.ask(a, asked, null));
        }, e -> {
            Feel.refused(a);
            android.util.Log.w("peanuts", "cheats: adding a source: " + e);
            message(a, "Not added", "The cheats could not be kept in the app's storage. Free some space and add them again.");
            CheatsPage.rebuild();
        });
    }

    // ---- The dialogs, in the app's AlertDialog look ----

    static void message(Activity a, String title, String text) {
        AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK", null).create();
        Feel.dialog(d);
        d.show();
    }

    /** A dialog with a lead and lines of an icon, a heading and a line under it (icon: check, info, warning) */
    private static void dialog(Activity a, String title, String lead, List<String[]> lines, String negative, Runnable onNegative, String positive, Runnable onPositive) {
        dialog(a, title, null, lead, lines, negative, onNegative, positive, onPositive);
    }

    /** The same with a card above the lead (formatCard); lead null: none */
    static void dialog(Activity a, String title, View card, String lead, List<String[]> lines, String negative, Runnable onNegative, String positive, Runnable onPositive) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        if (card != null) box.addView(card);
        if (lead != null) {
            TextView l = PUi.body(a, lead);
            l.setLineSpacing(0, 1.15f);
            PUi.add(box, l, card != null ? 14 : 0);
        }
        for (String[] line : lines) PUi.add(box, line(a, line), 14);
        AlertDialog.Builder b = PUi.sheet(a, title, box);
        if (positive != null) b.setPositiveButton(positive, onPositive == null ? null : Feel.confirm((x, w) -> onPositive.run()));
        if (negative != null) b.setNegativeButton(negative, onNegative == null ? null : (x, w) -> onNegative.run());
        AlertDialog d = b.create();
        Feel.dialog(d);
        d.show();
    }

    /** One of a dialog's lines: {icon, heading, the line under it} */
    private static View line(Activity a, String[] line) {
        LinearLayout r = PUi.row(a);
        r.setGravity(android.view.Gravity.TOP);
        TextView tile = PUi.text(a, "", 12, R.color.bn_text, 400);
        tile.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 10), 0, 0));
        r.addView(tile, new LinearLayout.LayoutParams(PUi.dp(a, 32), PUi.dp(a, 32)));
        Icons.tile(tile, line[0]);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(a, 12), PUi.dp(a, 1), 0, 0);
        col.addView(PUi.text(a, line[1], 15, R.color.bn_text, 600));
        TextView sub = PUi.small(a, line[2]);
        sub.setLineSpacing(0, 1.1f);
        col.addView(sub);
        r.addView(col, PUi.weight());
        return r;
    }

}
