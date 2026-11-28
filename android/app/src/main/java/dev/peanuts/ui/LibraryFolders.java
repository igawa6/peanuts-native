package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.folders.FolderKind;
import dev.peanuts.folders.FolderKinds;
import dev.peanuts.folders.FolderList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every folder the app uses, in one place (issue #546): Settings › Library lists each folder as a row of its own, its kind's name over its path, and an Add
 * folder row after them. Add folder asks what is in the folder (FolderKinds.ALL), opens the system's folder picker, and comes back to the same sheet with what
 * was added, so several folders go in one after another. A folder's row opens what it is with Remove, which takes it off the list and never touches its files,
 * and for builds Make default. The first-run card and a build that misses its DLC open the picker of one kind directly ({@link #pick}).
 */
final class LibraryFolders {
    private LibraryFolders() {}

    /** One folder added while the Add sheet was open: what it shows under Added */
    private static final class Added {
        final FolderKind kind; final String path;
        Added(FolderKind kind, String path) { this.kind = kind; this.path = path; }
    }

    private static final List<Added> added = new ArrayList<>();   // this round of the Add sheet
    private static boolean sheetOpen;                              // the Add sheet waits for the picker's answer: it opens again with it
    private static FolderKind lastKind;                            // the kind picked last in the sheet: its button has the focus when it opens again
    private static String replacing;                               // a kind of one folder (max 1): the folder its sheet's Pick another replaces
    private static Activity rowsOf;
    private static View addRow;                                    // Settings' Add folder row: the folder rows stand before it, in its card
    private static final List<View> folderRows = new ArrayList<>();

    // ---- Settings › Library: the rows ----

    /** The folder rows and Add folder, into Library's card (LibrarySettings.add) */
    static void rows(Activity a, LinearLayout card) {
        rowsOf = a;
        folderRows.clear();
        for (View r : folderRows(a)) { card.addView(r); folderRows.add(r); }
        addRow = new PUi.Setting(a, "", "Add folder", Haptics.on(Haptics.Word.TICK, v -> addSheet(a))).value(addLine(a)).view;
        card.addView(addRow);
        for (FolderKind k : FolderKinds.ALL) k.hooks.rows(a, () -> refresh(a));
    }

    /** The launcher is in front again (LibrarySettings.resume): each kind may act on it */
    static void resumed(Activity a) { for (FolderKind k : FolderKinds.ALL) k.hooks.resumed(a); }

    private static List<View> folderRows(Activity a) {
        List<View> out = new ArrayList<>();
        for (FolderKind k : FolderKinds.ALL) {
            List<String> dirs = k.list(a);
            for (int i = 0; i < dirs.size(); i++) {
                String p = dirs.get(i);
                CharSequence state = k.hooks.state(a, p);
                CharSequence line = k.firstIsDefault && i == 0 ? PUi.joined("Default", p) : p;
                out.add(new PUi.Setting(a, "", k.title, Haptics.on(Haptics.Word.TICK, v -> folderSheet(a, k, p))).value(state == null ? line : PUi.joined(line, state)).view);
            }
        }
        return out;
    }

    /** Add folder's line: what can be added, and that there can be many */
    private static CharSequence addLine(Activity a) {
        List<String> kinds = new ArrayList<>();
        boolean any = false;
        for (FolderKind k : FolderKinds.ALL) {
            if (!k.list(a).isEmpty()) any = true;
            if (!k.full(a)) kinds.add(kinds.isEmpty() ? k.label : Character.toLowerCase(k.label.charAt(0)) + k.label.substring(1));
        }
        String what = or(kinds);
        return any ? what + ". As many as you like." : "Add the folders with your " + Character.toLowerCase(what.charAt(0)) + what.substring(1);
    }

    /** "A, b or c" (with the comma before "or" when there are three or more, as the app's other lists) */
    static String or(List<String> l) {
        if (l.isEmpty()) return "";
        if (l.size() == 1) return l.get(0);
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) s.append(i == l.size() - 1 ? (l.size() > 2 ? ", or " : " or ") : ", ");
            s.append(l.get(i));
        }
        return s.toString();
    }

    /**
     * The folder rows again after a change, where they are now (laid into Settings › Library's Folders card, or still in the card add() built): the old ones out,
     * the new ones before Add folder, each adopted by SettingsPage (its icon, its measures, its explanation). Nothing when Settings is not this activity's
     */
    static void refresh(Activity a) {
        if (rowsOf != a || addRow == null) return;
        ((TextView) ((LinearLayout) ((LinearLayout) addRow).getChildAt(1)).getChildAt(1)).setText(addLine(a));
        ViewGroup parent = (ViewGroup) addRow.getParent();
        boolean focused = false;
        for (View r : folderRows) {
            focused |= r.hasFocus();
            SettingsPage.drop(r);
            if (r.getParent() instanceof ViewGroup) ((ViewGroup) r.getParent()).removeView(r);
        }
        folderRows.clear();
        if (parent == null) return;
        int at = parent.indexOfChild(addRow);
        for (View r : folderRows(a)) {
            parent.addView(r, at++);
            folderRows.add(r);
            SettingsPage.adopt(r, titleOf(r));
        }
        if (focused) addRow.requestFocus();   // the row the focus was on is gone: it goes on Add folder, not to the page's top
    }

    private static String titleOf(View row) {
        return String.valueOf(((TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(1)).getChildAt(0)).getText());
    }

    // ---- Add folder ----

    /** Settings' Add folder: what is in the folder, then the picker; again after each folder, with what was added, until Done */
    static void addSheet(Activity a) {
        if (!sheetOpen) added.clear();
        sheetOpen = false;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        List<FolderKind> open = new ArrayList<>();
        for (FolderKind k : FolderKinds.ALL) if (!k.full(a)) open.add(k);
        View focus = null;
        if (added.isEmpty()) {
            PUi.add(box, PUi.body(a, "What is in the folder?"), 0);
            LinearLayout card = PUi.add(box, PUi.listCard(a), 10);
            for (FolderKind k : open) {
                PUi.Setting s = new PUi.Setting(a, "", k.label, Haptics.on(Haptics.Word.TICK, v -> { d[0].dismiss(); pick(a, k, true); })).value(k.line).chevron(a);
                Icons.tile((TextView) s.view.getChildAt(0), k.icon);
                card.addView(s.view);
                if (focus == null || k == lastKind) focus = s.view;
            }
        } else {
            PUi.add(box, PUi.section(a, "Added"), 0);
            LinearLayout card = PUi.add(box, PUi.listCard(a), 6);
            for (Added x : added) {
                PUi.Setting s = new PUi.Setting(a, "", x.path, null).value(x.kind.label);
                Icons.tile((TextView) s.view.getChildAt(0), x.kind.icon);
                ((TextView) ((LinearLayout) s.view.getChildAt(1)).getChildAt(0)).setSingleLine(false);   // the whole path: its end is what tells two folders apart
                s.view.setClickable(false);
                s.view.setFocusable(false);
                ImageView ok = new ImageView(a);
                ok.setImageDrawable(Icons.get(a, "check", Icons.Ink.NORMAL));
                ok.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                s.view.addView(ok, new LinearLayout.LayoutParams(PUi.dp(a, 22), PUi.dp(a, 22)));
                card.addView(s.view);
            }
            if (!open.isEmpty()) {
                PUi.add(box, PUi.section(a, "Add another"), 14);
                boolean narrow = a.getResources().getConfiguration().screenWidthDp < 480;   // three labels side by side do not fit a phone held upright: one under the other
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                for (FolderKind k : open) {
                    View b = PUi.button(a, k.label, PUi.Kind.TONAL, Haptics.on(Haptics.Word.TICK, v -> { d[0].dismiss(); pick(a, k, true); }));
                    LinearLayout.LayoutParams lp = narrow ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) : PUi.wrap();
                    if (row.getChildCount() > 0) { if (narrow) lp.topMargin = PUi.dp(a, 8); else lp.leftMargin = PUi.dp(a, 8); }
                    row.addView(b, lp);
                    if (focus == null || k == lastKind) focus = b;
                }
                PUi.add(box, row, 8);
            }
        }
        if (!Environment.isExternalStorageManager()) {
            LinearLayout card = PUi.add(box, PUi.listCard(a), 12);
            PUi.Setting s = new PUi.Setting(a, "", "Allow file access", Haptics.on(Haptics.Word.TICK, v -> { d[0].dismiss(); LibrarySettings.openAccessSettings(a); }))
                    .value("The app needs All files access to read the games in these folders").chevron(a);
            Icons.tile((TextView) s.view.getChildAt(0), "lock");
            card.addView(s.view);
        }
        AlertDialog.Builder b = PUi.sheet(a, "Add a folder", box);
        if (added.isEmpty()) b.setNegativeButton("Cancel", null); else b.setPositiveButton("Done", null);
        d[0] = b.show();
        View f = focus;
        if (f != null && !box.isInTouchMode()) box.post(f::requestFocus);
    }

    /** The system's folder picker for a kind (the Add sheet's choice, the first-run card's rows, a build's "Add a DLC folder"); sheet: its answer opens the Add sheet again */
    static void pick(Activity a, FolderKind k, boolean sheet) {
        sheetOpen = sheet;
        lastKind = k;
        Toast.makeText(a, k.pick, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, k.request);
    }

    /**
     * The picker's answer for a kind (LibrarySettings.result): the folder by its path into the kind's list. A folder already in it is said and not added twice.
     * Then what follows: the kind's own step (FolderKind.Hooks.added), the rows, a new scan, and the Add sheet again when it asked. cancelled: the picker
     * was closed without a folder (the sheet still opens again, with what it had)
     */
    static void picked(Activity a, FolderKind k, String path, boolean cancelled) {
        boolean sheet = sheetOpen;
        sheetOpen = false;
        if (cancelled) { if (sheet) reopen(a); return; }
        if (path == null) {
            LibrarySettings.error(a, "That folder is not on the phone's storage (a cloud provider, say). Pick one on the phone or its SD card.");
            sheetOpen = sheet;
            return;
        }
        List<String> now = k.list(a), next = new ArrayList<>();
        String old = replacing;
        replacing = null;
        if (old != null) now = FolderList.remove(now, old);
        FolderList.Added r = FolderList.add(now, path, k.max, next);
        if (r == FolderList.Added.ALREADY) {
            Haptics.play(a.getWindow().getDecorView(), Haptics.Word.REJECT);
            Toast.makeText(a, FolderList.clean(path) + " is already in the list", Toast.LENGTH_LONG).show();
            if (sheet) reopen(a);
            return;
        }
        if (r != FolderList.Added.ADDED) { if (sheet) reopen(a); return; }
        k.save(a, next);
        if (sheet) added.add(new Added(k, FolderList.clean(path)));
        k.hooks.added(a, FolderList.clean(path), () -> {
            changed(a);
            if (sheet) reopen(a);
            else if (!Environment.isExternalStorageManager()) LibrarySettings.askAccess(a, k.hooks.accessNeed(FolderList.clean(path)).toString());
        });
    }

    private static void reopen(Activity a) {
        sheetOpen = true;
        if (!a.isFinishing() && !a.isDestroyed()) addSheet(a);
    }

    /** A folder list changed: Settings' rows, Library's state and a new scan */
    private static void changed(Activity a) {
        refresh(a);
        LibrarySettings.refreshRows();
        Library.changed(a);
        Library.rescan(a);
    }

    // ---- One folder ----

    /** A folder's row: its path, what it holds, Remove (the files stay) and, for a kind whose first folder is the default, Make default */
    static void folderSheet(Activity a, FolderKind k, String path) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = PUi.add(box, PUi.card(a), 0);
        card.setPadding(PUi.dp(a, 14), PUi.dp(a, 12), PUi.dp(a, 14), PUi.dp(a, 12));
        TextView p = PUi.text(a, path, 16, R.color.bn_text, 600);
        p.setTextIsSelectable(true);
        card.addView(p);
        CharSequence line = k.hooks.sheetLine(a, path);
        if (line == null) {
            String held = holds(k, path);
            line = held == null ? k.about : held + ". " + k.about;
        }
        PUi.add(card, PUi.small(a, line), 4);
        if (!new File(path).isDirectory() && Environment.isExternalStorageManager())
            PUi.add(box, PUi.small(a, "This folder is not there now (an SD card that is out, say). Its games come back when it is."), 12);
        CharSequence note = k.hooks.removeNote();
        if (note != null) PUi.add(box, PUi.small(a, note), 12);
        List<String> dirs = k.list(a);
        AlertDialog.Builder b = PUi.sheet(a, k.title, box).setPositiveButton("Close", null)
                .setNeutralButton(k.hooks.removeLabel(), (x, w) -> k.hooks.removing(a, path, () -> {
                    k.save(a, FolderList.remove(k.list(a), path));
                    Haptics.play(a.getWindow().getDecorView(), Haptics.Word.CLICK);
                    changed(a);
                }));
        if (k.firstIsDefault && dirs.size() > 1 && !FolderList.same(dirs.get(0), path))
            b.setNegativeButton("Make default", (x, w) -> { k.save(a, FolderList.first(k.list(a), path)); changed(a); });
        else if (k.max == 1)
            b.setNegativeButton("Pick another", (x, w) -> { replacing = path; pick(a, k, false); });
        b.show();
    }

    /** What the library found in a folder, in a few words ("9 games", "4 updates and 2 DLC", "Builds of 3 games"); null before the first scan or for none */
    private static String holds(FolderKind k, String path) {
        List<LibraryScanner.Title> titles = Library.titlesNow();
        if (titles == null || titles.isEmpty() || !Library.scannedOnce()) return null;
        List<String> one = java.util.Collections.singletonList(path);
        int games = 0, updates = 0, dlc = 0;
        for (LibraryScanner.Title t : titles) {
            boolean game = t.base != null && FolderList.holding(one, t.base.file.getPath()) != null;
            for (LibraryScanner.Source s : t.originals) game |= FolderList.holding(one, s.file.getPath()) != null;
            if (game) games++;
            for (LibraryScanner.Source s : t.updates) if (FolderList.holding(one, s.file.getPath()) != null) updates++;
            for (LibraryScanner.Source s : t.dlcs) if (FolderList.holding(one, s.file.getPath()) != null) dlc++;
        }
        if (k == FolderKinds.BUILDS) return games == 0 ? null : "Builds of " + count(games, "game");
        if (k == FolderKinds.ADDONS) {
            if (updates == 0 && dlc == 0) return games == 0 ? null : count(games, "game");
            return updates == 0 ? dlc + " DLC" : dlc == 0 ? count(updates, "update") : count(updates, "update") + " and " + dlc + " DLC";
        }
        return games == 0 ? null : count(games, "game");
    }

    private static String count(int n, String what) { return String.format(Locale.ROOT, "%d %s%s", n, what, n == 1 ? "" : "s"); }
}
