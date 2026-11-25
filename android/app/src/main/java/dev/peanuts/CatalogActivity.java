package dev.peanuts;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.peanuts.ui.PUi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Companions (Settings › Screens › Companions): the catalog of Eden Duo companion packages, a Banana page (PUi.page, a header with Back) of list cards: the games in
 * the launcher's list first, with Install / Update / Remove, then every other companion. A package installs beside the game's pack (<title ID>-work/peanuts/<Name>/);
 * the game picks it up at its next start. Games without a pack yet are listed but cannot take a package. Install from file puts in a .dsmod.zip from the
 * device without the catalog (CompanionImport, the same installer); a game's Companion section opens this page for that with {@link #EXTRA_TITLE}.
 */
public final class CatalogActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private List<Games.Entry> games = new ArrayList<>();
    private List<Catalog.Item> items = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(PUi.col(this, dev.nxnative.android.R.color.bn_bg));
        setContentView(frame);
        LinearLayout content = PUi.page(this, frame, 640);
        content.addView(PUi.header(this, PUi.back(this, v -> finish()), "Dual-screen companions", PUi.button(this, "Refresh", PUi.Kind.TONAL, v -> load())));
        // Install from file: a .dsmod.zip on the device, for when the catalog cannot be reached (it shows with the catalog or without it)
        LinearLayout fileCard = PUi.add(content, PUi.listCard(this), 12);
        fileCard.addView(new PUi.Setting(this, "📦", "Install from file", v -> pickFile()).value("A .dsmod.zip on this device. No internet needed.").chevron(this).view);
        status = PUi.small(this, "");
        PUi.add(content, status, 12);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        PUi.add(content, list, 8);
        forTitle = getIntent().getStringExtra(EXTRA_TITLE);
        if (forTitle == null) load();
        else {   // opened by a game's Companion section for its Install from file: the picker at once, and back to the game when it is done
            status.setText("Pick the companion's .dsmod.zip file.");
            if (saved == null) pickFile();
        }
    }

    // ---- Install from file (CompanionImport: the catalog install's own checks and swap, Installer.installFile) ----

    /** A game's Companion section opens this page with its title ID: the picked file must be that game's companion, and the page closes when it is done */
    public static final String EXTRA_TITLE = "dev.peanuts.companion_title";
    private static final int REQ_FILE = 1;
    private static final String[] ZIP_TYPES = {"application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream"};   // a .dsmod.zip is often octet-stream
    private String forTitle;

    private void pickFile() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(android.content.Intent.EXTRA_MIME_TYPES, ZIP_TYPES);
        try { startActivityForResult(i, REQ_FILE); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, "This device has no file picker.", Toast.LENGTH_LONG).show(); if (forTitle != null) finish(); }
    }

    @Override protected void onActivityResult(int request, int code, android.content.Intent data) {
        super.onActivityResult(request, code, data);
        if (request != REQ_FILE) return;
        android.net.Uri uri = code == RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) { if (forTitle != null) finish(); return; }   // cancelled
        status.setText("Reading the file…");
        new Thread(() -> {
            File zip = null; String err = null;
            String name = CompanionImport.displayName(this, uri);
            try { zip = CompanionImport.copy(this, uri); } catch (java.io.IOException e) { err = "The file could not be read: " + e.getMessage(); }
            final File fzip = zip; final String ferr = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) { if (fzip != null) fzip.delete(); return; }
                if (ferr != null) fileDone("Not installed", ferr, false);
                else installFile(fzip, name, false);
            });
        }, "peanuts-companion-file").start();
    }

    /** Installs the copied file (deleted at the end, also when the answer is No to an older version) */
    private void installFile(File zip, String name, boolean allowOlder) {
        status.setText("Installing from the file…");
        new Thread(() -> {
            CompanionImport.Done done = null; String err = null, older = null;
            try {
                done = CompanionImport.install(this, zip, name, forTitle, allowOlder, (what, n, total) -> ui.post(() -> status.setText("Installing from the file… " + (total > 0 ? (n * 100 / total) + " %" : n + " files"))));
            } catch (CompanionFile.Refused e) {
                if (CompanionFile.Refused.OLDER.equals(e.code)) older = e.getMessage(); else err = e.getMessage();
            } catch (java.io.IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            if (older == null) zip.delete();
            final CompanionImport.Done fdone = done; final String ferr = err, folder = older;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) { zip.delete(); return; }
                if (folder != null) {
                    new AlertDialog.Builder(this).setTitle("Install an older version?").setMessage(folder + " Install it anyway? The installed version stays on this device, and Versions can switch back to it.")
                            .setPositiveButton("Install older", (d, w) -> installFile(zip, name, true))
                            .setNegativeButton("Cancel", (d, w) -> { zip.delete(); fileDone(null, null, false); })
                            .setOnCancelListener(d -> { zip.delete(); fileDone(null, null, false); }).show();
                    return;
                }
                if (ferr != null) fileDone("Not installed", ferr, false);
                else fileDone(fdone.result.changed() ? "Companion installed" : "Already installed", fdone.message(), true);
            });
        }, "peanuts-companion-file").start();
    }

    /** The answer in a dialog (title null: none, the user said No); a game's section gets its page back after it */
    private void fileDone(String title, String message, boolean ok) {
        status.setText("");
        if (title == null) { if (forTitle != null) finish(); else load(); return; }
        if (ok) dev.peanuts.ui.Feel.accepted(this); else dev.peanuts.ui.Feel.refused(this);
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null)
                .setOnDismissListener(d -> { if (forTitle != null) finish(); else load(); }).show();
    }

    private void load() {
        status.setText("Loading the catalog…");
        list.removeAllViews();
        new Thread(() -> {
            List<Games.Entry> g = Games.load(this);
            List<Catalog.Item> it; String err = null;
            try { it = Catalog.fetch(); } catch (Exception e) { it = new ArrayList<>(); err = e.toString(); }
            final List<Catalog.Item> fit = it; final String ferr = err;
            ui.post(() -> { games = g; items = fit; status.setText(ferr != null ? "The catalog could not be loaded. Check the connection, then open it again." : PUi.joined(fit.size() + " dual-screen companions in the catalog", g.size() + (g.size() == 1 ? " game" : " games") + " in your list")); render(); });
        }, "peanuts-catalog").start();
    }

    private void render() {
        list.removeAllViews();
        // The user's games first, then every other companion
        List<Catalog.Item> rest = new ArrayList<>(items);
        if (!games.isEmpty()) {
            PUi.add(list, PUi.section(this, "Your games"), 0);
            LinearLayout card = PUi.add(list, PUi.listCard(this), 8);
            for (Games.Entry g : games) {
                Catalog.Item hit = null;
                for (Catalog.Item i : items) if (i.titleId.equalsIgnoreCase(g.titleId)) { hit = i; rest.remove(i); }
                card.addView(row(g, hit));
            }
        }
        if (!rest.isEmpty()) {
            PUi.add(list, PUi.section(this, "For games you have not added yet"), games.isEmpty() ? 0 : PUi.SECTION_GAP);
            LinearLayout card = PUi.add(list, PUi.listCard(this), 8);
            for (Catalog.Item i : rest) card.addView(row(null, i));
        }
        if (games.isEmpty() && items.isEmpty()) PUi.add(list, PUi.body(this, "Nothing to show."), 0);
    }

    private LinearLayout row(Games.Entry g, Catalog.Item item) {
        String name = g != null ? g.name : item.gameName;
        File root = g != null ? g.companionRoot() : null;
        String installed = root != null && item != null ? Installer.installedVersion(root, item.packageName) : null;
        String first = g != null && root == null ? "build the game first" : null;
        CharSequence line = item == null ? PUi.joined("No dual-screen companion for this game yet", first)
                    : installed == null ? PUi.joined(item.version, String.format(Locale.US, "%.0f KB", item.size / 1024.0), first)
                    : installed.equals(item.version) ? PUi.joined("installed " + installed, "up to date", first) : PUi.joined("installed " + installed, "update " + item.version, first);
        boolean hasVersions = item != null && root != null;
        PUi.Setting s = new PUi.Setting(this, "🖥️", name, !hasVersions ? null : v -> dev.peanuts.ui.CompanionVersions.open(this, item.titleId, this::render)).value(line);
        if (hasVersions) s.chevron(this);
        if (item != null && root != null) {
            Button b = PUi.button(this, installed == null ? "Install" : installed.equals(item.version) ? "Remove" : "Update", installed != null && installed.equals(item.version) ? PUi.Kind.DANGER : PUi.Kind.TONAL, null);
            File froot = root;
            b.setOnClickListener(v -> {
                if ("Remove".contentEquals(b.getText())) {
                    PUi.confirmDanger(this, "Remove the companion?", name + "'s dual-screen companion is deleted from this device. The game and its saves stay.", "Remove", () -> {
                        new Thread(() -> {   // off the UI thread: the delete walks shared storage and waits for the package's lock
                            String err = null;
                            try { Installer.remove(froot, item.packageName, this); }   // ctx = this: refuses while the game is running (found in review)
                            catch (java.io.IOException | RuntimeException e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
                            final String ferr = err;
                            ui.post(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                Toast.makeText(this, ferr == null ? "Removed" : "Remove failed: " + ferr, ferr == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                                render();
                            });
                        }, "peanuts-companion-remove").start();
                    });
                    return;
                }
                b.setEnabled(false);
                install(item, froot);
            });
            s.view.addView(b);
        }
        return s.view;
    }

    private void install(Catalog.Item item, File root) {
        status.setText("Installing " + item.packageName + " " + item.version + "…");
        new Thread(() -> {
            String err = null;
            try {
                if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("cannot create " + root);
                Installer.install(item, root, getCacheDir(), (what, done, total) -> ui.post(() -> status.setText(what + " " + (total > 0 ? (done * 100 / total) + " %" : done + " files"))), null, this);   // ctx = this: refuses while the game is running
            } catch (Exception e) { err = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final String ferr = err;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;   // the screen closed during the download: no dialog on a gone window
                if (ferr == null) { status.setText("Installed " + item.packageName + " " + item.version + " into " + root); Toast.makeText(this, "Start the game to use it", Toast.LENGTH_LONG).show(); }
                else new AlertDialog.Builder(this).setTitle("Install failed").setMessage(ferr).setPositiveButton("OK", null).show();
                render();
            });
        }, "peanuts-install").start();
    }
}
