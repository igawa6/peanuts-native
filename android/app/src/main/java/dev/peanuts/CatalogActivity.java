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
 * Companions (Settings › Library › Companions): the catalog of Eden Duo companion packages, a Banana page (PUi.page, a header with Back) of list cards: the games in
 * the launcher's list first, with Install / Update / Remove, then every other companion. A package installs beside the game's pack (<title ID>-work/peanuts/<Name>/);
 * the game picks it up at its next start. Games without a pack yet are listed but cannot take a package.
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
        content.addView(PUi.header(this, PUi.back(this, v -> finish()), "Companions", PUi.button(this, "Refresh", PUi.Kind.TONAL, v -> load())));
        status = PUi.small(this, "");
        PUi.add(content, status, 12);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        PUi.add(content, list, 8);
        load();
    }

    private void load() {
        status.setText("Loading the catalog…");
        list.removeAllViews();
        new Thread(() -> {
            List<Games.Entry> g = Games.load(this);
            List<Catalog.Item> it; String err = null;
            try { it = Catalog.fetch(); } catch (Exception e) { it = new ArrayList<>(); err = e.toString(); }
            final List<Catalog.Item> fit = it; final String ferr = err;
            ui.post(() -> { games = g; items = fit; status.setText(ferr != null ? "Catalog unavailable: " + ferr : PUi.joined(fit.size() + " companions in the catalog", g.size() + (g.size() == 1 ? " game" : " games") + " in your list")); render(); });
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
            PUi.add(list, PUi.section(this, "Other companions: add the game first"), games.isEmpty() ? 0 : PUi.SECTION_GAP);
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
        CharSequence line = item == null ? PUi.joined("No companion in the catalog", first)
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
                    PUi.confirmDanger(this, "Remove the companion?", name + "'s companion is deleted from " + froot + ". The game and its saves stay.", "Remove", () -> {
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
