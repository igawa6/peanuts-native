package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;

import dev.nxnative.android.BananaBackgroundView;
import dev.nxnative.android.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import javax.net.ssl.HttpsURLConnection;

/**
 * Settings › GPU driver (MainActivity's row, and the Readiness check's "Pick a GPU driver"), in place of upstream's three list dialogs. One page:
 * the drivers on the phone (System and each installed one, a row to pick, Remove beside a custom one) and the ways to add one (a download source,
 * a zip in the app's folder, a zip picked anywhere). A source opens its own page: its GitHub releases, newest first, each with its date, its notes
 * (GitHubText) and its files; a file says which Adreno it is made for (DriverMatch) and installs with one tap: downloaded with its progress,
 * unpacked by upstream's GpuDriver.install (DriverPort) and picked. Nothing goes to the network before a source is opened. Back closes a source,
 * then the page; Settings' row shows the pick when it is in front again.
 */
public final class DriverActivity extends Activity {
    public static final int REQUEST_GAME = 8;
    private static final int REQ_ZIP = 1;
    private static final long MAX_ZIP = 512L << 20;   // as upstream's DriverFetch: no driver package is larger
    private static final long CACHE_MS = 10 * 60_000;
    private static final int SHOWN = 8;
    private static final String TAG = "peanuts";

    /** What a source is, in a sentence; a source upstream adds later shows its address only */
    private static final Map<String, String> ABOUT = new HashMap<>();
    static {
        ABOUT.put("MrPurple666/purple-turnip", "Turnip, the open Mesa driver, in one build for Adreno 6xx, 7xx and 8xx.");
        ABOUT.put("K11MCH1/AdrenoToolsDrivers", "Turnip builds, and Qualcomm's own drivers taken from other devices.");
        ABOUT.put("whitebelyash/freedreno_turnip-CI", "Turnip test builds aimed at Adreno 8xx. Its author calls them unstable.");
        ABOUT.put("Weab-chan/freedreno_turnip-CI", "Turnip built from Mesa every two weeks, with builds for some phones' quirks.");
        ABOUT.put("crueter/GameHub-8Elite-Drivers", "Qualcomm's drivers from GameHub, for Adreno 8xx (Snapdragon 8 Elite).");
        ABOUT.put("StevenMXZ/Adreno-Tools-Drivers", "Turnip builds and Qualcomm's own drivers, most of them for Adreno 8xx.");
        ABOUT.put("Balemuni/Balemunis-Aurora", "Turnip builds for Adreno, including an edition tuned for Snapdragon 8 Gen 2.");
        ABOUT.put("JimVulkan/mali-panvk", "Mesa PanVK builds for supported Mali and Immortalis GPUs with Arm's kbase kernel driver.");
    }

    static final class Asset {
        final String name, url; final long size; TreeSet<String> gpus = new TreeSet<>();
        Asset(String name, String url, long size) { this.name = name; this.url = url; this.size = size; }
    }

    static final class Release {
        final String title, date, notes; final boolean prerelease; final List<Asset> assets = new ArrayList<>();
        Release(String title, String date, String notes, boolean prerelease) { this.title = title; this.date = date; this.notes = notes; this.prerelease = prerelease; }
    }

    /** A source's releases as last read (this process): kept a few minutes, so going back and forth reads GitHub once */
    private static final class Read { final long at = System.currentTimeMillis(); List<Release> releases; String error; }
    private static final Map<String, Read> READ = new HashMap<>();
    // Both entry points and recreated pages share the same main-thread install guard.
    private static boolean installing;
    private static DriverActivity visiblePage;

    /** A file being installed or done: its progress, then its result */
    private static final class Job { final String url; volatile long done, total; boolean finished; String result; boolean failed; Job(String url) { this.url = url; } }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private String gameTitleId, gameName;   // null title ID means the app-wide driver
    private String[] source;   // { owner/name, label } while a source's page shows
    private int shown = SHOWN;
    private Job job;   // one install at a time
    private ProgressBar jobBar;
    private TextView jobText;
    private String fileResult;   // the last install from a file, on the main page
    private boolean fileFailed;
    private String focusSource;   // the source row to focus when its page closes
    private final java.util.Set<String> openNotes = new java.util.HashSet<>();   // the releases whose notes are open (source, title)

    public static void open(Activity a) { a.startActivity(new Intent(a, DriverActivity.class)); }

    public static void openForGame(Activity a, String titleId, String gameName) {
        Intent intent = new Intent(a, DriverActivity.class).putExtra("title_id", titleId).putExtra("game_name", gameName);
        a.startActivityForResult(intent, REQUEST_GAME);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        gameTitleId = getIntent().getStringExtra("title_id");
        gameName = getIntent().getStringExtra("game_name");
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(PUi.col(this, R.color.bn_bg));
        frame.addView(new BananaBackgroundView(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        content = PUi.page(this, frame, 760);
        ((View) content.getParent()).setFocusable(false);   // past a long text the page scrolls on; as the focus itself it would grey out whole
        try {   // a controller: Settings' focus frame (PadFocus, package-private)
            java.lang.reflect.Method m = Class.forName("dev.nxnative.android.PadFocus").getDeclaredMethod("watch", android.view.Window.class);
            m.setAccessible(true);
            m.invoke(null, getWindow());
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "focus frame: " + e); }
    }

    @Override protected void onStart() {
        super.onStart();
        visiblePage = this;
        render();
    }

    @Override protected void onStop() {
        if (visiblePage == this) visiblePage = null;
        super.onStop();
    }

    private static void installFinished() {
        installing = false;
        if (visiblePage != null && !visiblePage.isFinishing()) visiblePage.render();
    }

    @Override public void onBackPressed() {
        if (source != null) { closeSource(); return; }
        super.onBackPressed();
    }

    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B) { onBackPressed(); return true; }   // B is Back, as on Settings
        return super.onKeyUp(keyCode, e);
    }

    private void render() {
        content.removeAllViews();
        jobBar = null; jobText = null;
        if (source == null) main(); else sourcePage();
    }

    // ---- The main page ----

    private void main() {
        content.addView(PUi.header(this, PUi.back(this, v -> finish()), "GPU driver", gameName, null));
        PUi.add(content, PUi.body(this, "System is the phone's own driver. A custom one can make a game run faster or look right, and another game worse. "
                + (gameTitleId != null ? "Your choice applies to this game on its next start." : "Your choice applies to games using the app default.")), 4);

        List<DriverPort.Driver> drivers = new ArrayList<>();
        String chosen = null;
        String defaultTitle = "Default (System)";
        try {
            drivers = DriverPort.installed(this);
            File appDriver = DriverPort.selected(this);
            String appChoice = appDriver == null ? null : appDriver.getName();
            if (gameTitleId == null) {
                chosen = appChoice;
            } else {
                chosen = DriverPort.gameChoice(this, gameTitleId);
                String recommended = DriverPort.gameRecommendation(this, gameTitleId);
                defaultTitle = recommended == null ? "Default (" + driverName(drivers, appChoice) + ")"
                        : "Recommended (" + driverName(drivers, recommended) + ")";
            }
        }
        catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "gpu drivers: " + e); }
        String gpu = DriverPort.gpu();

        PUi.add(content, PUi.section(this, "On this phone"), PUi.SECTION_GAP);
        LinearLayout list = PUi.add(content, PUi.listCard(this), 8);
        boolean useDefault = gameTitleId != null && chosen == null;
        if (gameTitleId != null)
            list.addView(choice(defaultTitle, "Follow the recommendation for this game or the app default.", useDefault, null, true));
        boolean useSystem = !useDefault && pickedDriver(drivers, chosen) == null;
        list.addView(choice("System", gpu != null ? "The phone's own driver for its " + gpu + "." : "The phone's own driver.", useSystem, null, false));
        for (DriverPort.Driver d : drivers) list.addView(choice(d.name, about(d), d.dir.getName().equals(chosen), d, false));

        PUi.add(content, PUi.section(this, "Add a driver"), PUi.SECTION_GAP);
        String family = DriverMatch.family(gpu);
        if (gpu != null) PUi.add(content, PUi.small(this, "This phone's GPU is " + gpu + ". "
                + (family == null ? "Check each release's supported GPUs."
                : family.equals(DriverMatch.MALI) ? "Check PanVK release notes for your GPU model."
                : "Look for drivers made for Adreno " + family + ".")), 8);
        LinearLayout sources = PUi.add(content, PUi.listCard(this), 8);
        String[][] all = new String[0][];
        try { all = DriverPort.sources(); } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "driver sources: " + e); }
        View focus = null;
        for (String[] s : all) {
            String about = ABOUT.get(s[0]);
            LinearLayout r = row(s[1], (about != null ? about + "\n" : "") + "github.com/" + s[0], v -> openSource(s));
            r.addView(chevron());
            sources.addView(r);
            if (s[0].equals(focusSource)) focus = r;
        }

        PUi.add(content, PUi.section(this, "From a file"), PUi.SECTION_GAP);
        LinearLayout files = PUi.add(content, PUi.listCard(this), 8);
        List<File> zips = new ArrayList<>();
        try { zips = DriverPort.zips(this); } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "driver zips: " + e); }
        for (File z : zips) {
            LinearLayout r = row(z.getName(), "In the app's driver folder.", null);
            Button b = PUi.button(this, "Install", PUi.Kind.TONAL, v -> installFile(z.getName(), () -> DriverPort.install(this, z)));
            b.setEnabled(!installing);
            r.addView(b);
            files.addView(r);
        }
        LinearLayout pick = row("Pick a zip file", "A driver zip with its meta.json, from anywhere on the phone.", v -> pickZip());
        pick.addView(chevron());
        files.addView(pick);
        if (installing) {
            ProgressBar bar = PUi.progress(this);
            bar.setIndeterminate(true);
            PUi.add(content, bar, 12);
        } else if (fileResult != null) {
            PUi.add(content, PUi.status(this, fileResult, fileFailed ? R.color.bn_danger : R.color.bn_leaf), 12);
        }
        if (focus != null) { View f = focus; f.post(f::requestFocus); focusSource = null; }
    }

    private static DriverPort.Driver pickedDriver(List<DriverPort.Driver> drivers, String folder) {
        for (DriverPort.Driver d : drivers) if (d.dir.getName().equals(folder)) return d;
        return null;
    }

    private static String driverName(List<DriverPort.Driver> drivers, String folder) {
        DriverPort.Driver d = pickedDriver(drivers, folder);
        return d == null ? "System" : d.name;
    }

    /** A driver's line under its name: version and author, and the app's recommendation */
    private static String about(DriverPort.Driver d) {
        StringBuilder s = new StringBuilder();
        if (!d.version.isEmpty()) s.append("Version ").append(d.version);
        if (!d.author.isEmpty()) s.append(s.length() > 0 ? ", by " : "By ").append(d.author);
        if (s.length() > 0) s.append('.');
        if (d.recommended) s.append(s.length() > 0 ? " " : "").append("Recommended for this phone.");
        return s.toString();
    }

    /** A driver to pick: a radio, its name over its line, Remove beside a custom one */
    private LinearLayout choice(String title, String line, boolean on, DriverPort.Driver d, boolean useDefault) {
        LinearLayout r = row(title, line, v -> pick(d, useDefault));
        RadioButton radio = new RadioButton(this);
        radio.setChecked(on);
        radio.setClickable(false);
        radio.setFocusable(false);
        radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.addView(radio, 0);
        r.setContentDescription(title + (on ? ", in use" : ""));
        if (d != null) r.addView(PUi.button(this, "Remove", PUi.Kind.QUIET, v -> remove(d)));
        return r;
    }

    private void select(Context c, DriverPort.Driver d, boolean useDefault) throws ReflectiveOperationException {
        if (gameTitleId == null) {
            DriverPort.select(c, d);
        } else {
            String value = useDefault ? null : d == null ? DriverPort.systemChoice() : d.dir.getName();
            DriverPort.gameChoice(c, gameTitleId, value);
        }
    }

    private void pick(DriverPort.Driver d, boolean useDefault) {
        if (installing) return;
        try { select(this, d, useDefault); } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "pick driver: " + e); }
        Haptics.quiet(150);
        render();
    }

    private void remove(DriverPort.Driver d) {
        if (installing) return;
        PUi.confirmDanger(this, "Remove " + d.name + "?", "Deleted: the app's copy of this driver.\n\nKept: the zip you installed it from, your games, packs and saves. "
                + "This removes the driver for every game in the app. Games that picked it will use System.", "Remove driver", () -> {
            if (installing) return;
            try { DriverPort.remove(this, d); } catch (ReflectiveOperationException | RuntimeException e) { Log.w(TAG, "remove driver: " + e); }
            render();
        });
    }

    private void pickZip() {
        if (installing) return;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")   // some providers label zips otherwise
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"}), REQ_ZIP);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_ZIP || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = u.getLastPathSegment() == null ? "the zip" : u.getLastPathSegment().replaceAll(".*[/:]", "");
        installFile(name, () -> DriverPort.install(this, u));
    }

    private interface Install { DriverPort.Driver run() throws IOException; }

    /** A zip from the phone: installed and picked off the main thread, the result on the main page */
    private void installFile(String name, Install install) {
        if (installing) return;
        installing = true;
        fileResult = null;
        render();
        new Thread(() -> {
            String msg; boolean failed = false;
            try { DriverPort.Driver d = install.run(); select(this, d, false); msg = installedMessage(d); }
            catch (Throwable e) { failed = true; msg = "Not installed: " + reason(e) + ". The drivers and your choice are as they were."; Log.w(TAG, "driver " + name + ": " + e); }
            String m = msg; boolean f = failed;
            ui.post(() -> { fileResult = m; fileFailed = f; installFinished(); });
        }, "peanuts-driver-file").start();
    }

    // ---- A source's page ----

    private void openSource(String[] s) { source = s; shown = SHOWN; render(); content.post(() -> { View b = content.findFocus(); if (b == null) content.requestFocus(); }); }

    private void closeSource() { focusSource = source[0]; source = null; render(); }

    private void sourcePage() {
        String repo = source[0];
        content.addView(PUi.header(this, PUi.back(this, v -> closeSource()), source[1], "github.com/" + repo, null));
        String about = ABOUT.get(repo);
        if (about != null) PUi.add(content, PUi.body(this, about), 4);
        Read r = READ.get(repo);
        if (r == null || System.currentTimeMillis() - r.at > CACHE_MS) { load(repo); r = null; }
        if (r == null) {
            ProgressBar bar = PUi.progress(this);
            bar.setIndeterminate(true);
            PUi.add(content, bar, 20);
            return;
        }
        if (r.error != null) {
            PUi.add(content, PUi.body(this, "The releases did not load: " + r.error + ". Check the connection and try again."), 16);
            LinearLayout row = PUi.row(this);
            row.addView(PUi.button(this, "Try again", PUi.Kind.TONAL, v -> { READ.remove(repo); render(); }), PUi.wrap());
            PUi.add(content, row, 10);
            return;
        }
        if (r.releases.isEmpty()) { PUi.add(content, PUi.body(this, "This source has no driver zips in its releases."), 16); return; }
        String phone = DriverMatch.family(DriverPort.gpu());
        Map<String, String> from = installedFrom();
        for (int i = 0; i < r.releases.size() && i < shown; i++) PUi.add(content, release(r.releases.get(i), phone, from), i == 0 ? 16 : 12);
        if (r.releases.size() > shown) {
            LinearLayout row = PUi.row(this);
            row.addView(PUi.button(this, "Show older releases", PUi.Kind.TONAL, v -> { shown += SHOWN; render(); }), PUi.wrap());
            PUi.add(content, row, 12);
        }
    }

    /** A release, short: its name and date, Notes and Install on the right (with several files: Notes there, the files below, each with Install); the
     *  notes open under it */
    private View release(Release r, String phone, Map<String, String> from) {
        LinearLayout card = PUi.card(this);
        card.setPadding(PUi.dp(this, 18), PUi.dp(this, 12), PUi.dp(this, 12), PUi.dp(this, 12));
        String when = (r.prerelease ? "Pre-release, " : "") + day(r.date);
        TextView notes = null;
        Button toggle = null;
        if (!r.notes.trim().isEmpty()) {
            String key = source[0] + "\n" + r.title;
            TextView n = notes = GitHubTextStyle.view(this, r.notes);
            n.setFocusable(false);   // the buttons are the stops; the page scrolls with them
            n.setPadding(0, 0, 0, 0);
            n.setVisibility(openNotes.contains(key) ? View.VISIBLE : View.GONE);
            Button t = toggle = PUi.button(this, openNotes.contains(key) ? "Hide notes" : "Notes", PUi.Kind.QUIET, null);
            t.setOnClickListener(v -> {
                boolean open = !openNotes.remove(key);
                if (open) openNotes.add(key);
                n.setVisibility(open ? View.VISIBLE : View.GONE);
                t.setText(open ? "Hide notes" : "Notes");
            });
        }
        if (r.assets.size() == 1) card.addView(file(r.assets.get(0), r.title, when, phone, from, toggle));
        else {
            LinearLayout head = PUi.row(this);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(PUi.text(this, r.title, 17, R.color.bn_text, 700));
            col.addView(PUi.small(this, when + ", " + r.assets.size() + " files."));
            head.addView(col, PUi.weight());
            if (toggle != null) head.addView(toggle);
            card.addView(head);
        }
        if (notes != null) PUi.add(card, notes, 10);
        if (r.assets.size() > 1) for (Asset a : r.assets) PUi.add(card, file(a, null, null, phone, from, null), 12);
        return card;
    }

    /** A file's row: its name over its size and GPUs (a release's only file: the release's name, and its date first), Install on the right; under it,
     *  its progress or its result */
    private View file(Asset a, String title, String when, String phone, Map<String, String> from, View beside) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = PUi.row(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(title != null ? PUi.text(this, title, 17, R.color.bn_text, 700) : PUi.text(this, a.name, 15, R.color.bn_text, 600));
        String gpus = DriverMatch.words(a.gpus);
        boolean other = DriverMatch.otherGpu(a.gpus, phone);
        col.addView(PUi.small(this, (when != null ? when + ", " : "") + mb(a.size) + (gpus.isEmpty() ? "." : ", for " + gpus + ".")));
        boolean installed = from.containsKey(a.url);
        boolean mine = job != null && job.url.equals(a.url);
        if (other) PUi.add(col, PUi.status(this, "Not made for this phone's " + DriverPort.gpu(), R.color.bn_danger), 2);
        else if (installed && !(mine && job.finished)) PUi.add(col, PUi.status(this, "Installed from this file", R.color.bn_leaf), 2);   // else the install's own line says it
        top.addView(col, PUi.weight());
        Button b = PUi.button(this, installed ? "Install again" : "Install", other || installed ? PUi.Kind.TONAL : PUi.Kind.PRIMARY, v -> {
            if (!other) { download(a); return; }
            new AlertDialog.Builder(this).setTitle("Install " + a.name + "?").setMessage("It is made for " + gpus + ". This phone's GPU is " + DriverPort.gpu()
                    + ", so games may not start with it. You can pick System again at any time.").setPositiveButton("Install", (d, w) -> download(a)).setNegativeButton("Cancel", null).show();
        });
        b.setEnabled(!installing);
        if (beside != null) top.addView(beside);
        top.addView(b);
        box.addView(top);
        if (mine) {
            if (!job.finished) {
                jobBar = PUi.progress(this);
                jobText = PUi.small(this, "");
                PUi.add(box, jobBar, 8);
                PUi.add(box, jobText, 4);
                progress();
            } else {
                PUi.add(box, PUi.status(this, job.result, job.failed ? R.color.bn_danger : R.color.bn_leaf), 8);
            }
        }
        return box;
    }

    private void progress() {
        if (jobBar == null || job == null) return;
        jobBar.setIndeterminate(job.total <= 0);
        if (job.total > 0) { jobBar.setMax(1000); jobBar.setProgress((int) (job.done * 1000 / job.total)); }
        jobText.setText(job.done >= job.total && job.total > 0 ? "Installing" : mb(job.done) + " of " + mb(job.total));
    }

    /** Downloads a file into the cache with its progress, installs it through upstream and picks it */
    private void download(Asset a) {
        if (installing) return;
        installing = true;
        Job j = job = new Job(a.url);
        j.total = a.size;
        render();
        Context app = getApplicationContext();
        new Thread(() -> {
            String msg; boolean failed = false;
            File tmp = null;
            try {
                if (a.size > MAX_ZIP) throw new IOException("the file is larger than 512 MB, so it is no driver package");
                tmp = File.createTempFile("driver-", ".zip", app.getCacheDir());
                HttpsURLConnection c = open(a.url, "application/octet-stream");
                long last = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        j.done += k;
                        if (j.done > MAX_ZIP) throw new IOException("the download is larger than 512 MB, so it is no driver package");
                        out.write(buf, 0, k);
                        long now = System.currentTimeMillis();
                        if (now - last > 250) { last = now; ui.post(this::progress); }
                    }
                } finally { c.disconnect(); }
                ui.post(this::progress);
                DriverPort.Driver d;
                try (InputStream in = new FileInputStream(tmp)) { d = DriverPort.install(app, in, a.name.replaceAll("(?i)(\\.adpkg)?\\.zip$", "")); }
                select(app, d, false);
                prefs().edit().putString(a.url, d.dir.getName()).apply();
                msg = installedMessage(d);
            } catch (Throwable e) {
                failed = true;
                msg = "Not installed: " + reason(e) + ". The drivers and your choice are as they were.";
                Log.w(TAG, "driver download " + a.url + ": " + e);
            } finally { if (tmp != null && !tmp.delete()) tmp.deleteOnExit(); }
            String m = msg; boolean f = failed;
            ui.post(() -> { j.finished = true; j.result = m; j.failed = f; installFinished(); });
        }, "peanuts-driver-download").start();
    }

    private String installedMessage(DriverPort.Driver d) {
        return "Installed and picked " + d.name + (gameTitleId == null ? "." : " for this game.")
                + " It applies from the next game start.";
    }

    private void load(String repo) {
        new Thread(() -> {
            Read r = new Read();
            try { r.releases = releases(repo); }
            catch (Throwable e) { r.error = reason(e); r.releases = new ArrayList<>(); Log.w(TAG, "driver releases " + repo + ": " + e); }
            ui.post(() -> { READ.put(repo, r); if (source != null && source[0].equals(repo) && !isFinishing() && !isDestroyed()) render(); });
        }, "peanuts-driver-releases").start();
    }

    /** A source's releases with .zip files, newest first (GitHub's order); network, off the main thread */
    static List<Release> releases(String repo) throws Exception {
        HttpsURLConnection c = open("https://api.github.com/repos/" + repo + "/releases?per_page=30", "application/vnd.github+json");
        String body;
        try (InputStream in = c.getInputStream()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        finally { c.disconnect(); }
        List<Release> out = new ArrayList<>();
        JSONArray rel = new JSONArray(body);
        for (int k = 0; k < rel.length(); k++) {
            JSONObject o = rel.getJSONObject(k);
            if (o.optBoolean("draft")) continue;
            String tag = o.optString("tag_name", ""), name = o.optString("name", "").trim(), notes = o.isNull("body") ? "" : o.optString("body", "");
            String title = name.isEmpty() ? tag : name;
            if (!title.contains(" ")) title = title.replaceAll("(?i)(\\.adpkg)?(\\.zip)?$", "").replace('_', ' ');   // a file's name as the release's: "turnip mrpurple T30-toasted"
            Release r = new Release(title, o.optString("published_at", ""), notes, o.optBoolean("prerelease"));
            JSONArray as = o.optJSONArray("assets");
            for (int j = 0; as != null && j < as.length(); j++) {
                JSONObject a = as.getJSONObject(j);
                String n = a.optString("name", ""), url = a.optString("browser_download_url", "");
                if (n.toLowerCase(Locale.ROOT).endsWith(".zip") && url.startsWith("https://")) r.assets.add(new Asset(n, url, a.optLong("size", 0)));
            }
            if (r.assets.isEmpty()) continue;
            List<String> names = new ArrayList<>();
            for (Asset a : r.assets) names.add(a.name);
            for (Asset a : r.assets) a.gpus = DriverMatch.forFile(a.name, names, r.title + " " + tag, notes, repo);
            out.add(r);
        }
        return out;
    }

    private static HttpsURLConnection open(String url, String accept) throws IOException {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol())) throw new IOException("not an https address");
        HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
        c.setRequestProperty("User-Agent", "PeanutsNative-driver-page");
        c.setRequestProperty("Accept", accept);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);   // a release file redirects to GitHub's storage (https to https)
        int code = c.getResponseCode();
        if (code == 403 || code == 429) { c.disconnect(); throw new IOException("GitHub's limit for this hour is reached"); }
        if (code != 200) { c.disconnect(); throw new IOException("GitHub answered " + code); }
        return c;
    }

    /** A failure for the page: what went wrong in a few words (the details go to the log) */
    private static String reason(Throwable e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException) return "no connection to GitHub";
        if (e instanceof SocketTimeoutException) return "GitHub did not answer in time";
        String m = e.getMessage();
        return m != null && !m.isEmpty() ? m : e.getClass().getSimpleName();
    }

    // ---- small parts ----

    /** Which release file each installed driver came from (its address → the driver's folder), as long as the driver is still installed */
    private Map<String, String> installedFrom() {
        Map<String, String> out = new HashMap<>();
        List<String> dirs = new ArrayList<>();
        try { for (DriverPort.Driver d : DriverPort.installed(this)) dirs.add(d.dir.getName()); } catch (ReflectiveOperationException | RuntimeException e) { return out; }
        for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) if (e.getValue() instanceof String && dirs.contains(e.getValue())) out.put(e.getKey(), (String) e.getValue());
        return out;
    }

    private SharedPreferences prefs() { return getSharedPreferences("peanuts_drivers", MODE_PRIVATE); }

    /** A list row: a title over a line or two; with l, the whole row is the target */
    private LinearLayout row(CharSequence title, CharSequence line, View.OnClickListener l) {
        LinearLayout r = PUi.row(this);
        r.setPadding(PUi.dp(this, 12), PUi.dp(this, 10), PUi.dp(this, 12), PUi.dp(this, 10));
        r.setMinimumHeight(PUi.dp(this, 60));
        if (l != null) {
            r.setBackground(PUi.ripple(this, PUi.round(Color.TRANSPARENT, PUi.dp(this, 14), 0, 0)));
            r.setOnClickListener(Haptics.on(Haptics.Word.TICK, l));
            r.setFocusable(true);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(PUi.dp(this, 6), 0, PUi.dp(this, 8), 0);
        col.addView(PUi.text(this, title, 16, R.color.bn_text, 600));
        if (line != null && line.length() > 0) col.addView(PUi.small(this, line));
        r.addView(col, PUi.weight());
        return r;
    }

    private View chevron() {
        android.widget.ImageView ch = new android.widget.ImageView(this);
        ch.setImageDrawable(PUi.icon(this, R.drawable.ic_chevron, R.color.bn_text_2));
        ch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ch.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(this, 20), PUi.dp(this, 20)));
        return ch;
    }

    private static String mb(long bytes) { return bytes >= 1 << 20 ? String.format(Locale.US, "%.1f MB", bytes / 1048576.0) : String.format(Locale.US, "%.0f KB", bytes / 1024.0); }

    /** "2026-08-17T12:38:01Z" → "17 Aug 2026" */
    private static String day(String date) {
        try { return java.time.LocalDate.parse(date.substring(0, 10)).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)); }
        catch (RuntimeException e) { return date; }
    }

}
