package dev.peanuts.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.peanuts.build.BuildListener;
import dev.peanuts.build.ModBuild;
import dev.peanuts.build.ModBuildPipeline;
import dev.peanuts.build.ModBuildRequest;
import dev.peanuts.build.ModdedBuild;
import dev.peanuts.mods.ModImportUi;
import dev.peanuts.mods.ModRuntime;
import dev.peanuts.mods.ModStore;
import dev.peanuts.ui.LibrarySettings;

/**
 * The Mods section of a game's settings page (GameSettings.build, the Game tab, above Companion; HOOKS.md): one row per installed mod with what it carries
 * (files, cheats, code), a switch (on or off from the game's next start) and a bin (delete, after a question), then "Install mod…" (a folder or a zip:
 * dev.peanuts.mods.ModImportUi). A game that is not built has one row that says so: a mod is checked against the pack's build IDs when it is installed, and a
 * code mod is built from the pack. Local files only, never a catalog and never a download (DESIGN-MODS.md: a mod is someone else's edit of the game).
 * <p>
 * Files and cheats apply on the normal build (dev.peanuts.mods.ModRuntime, at the game's start). Code (exefs, IPS patches) needs a build of its own, a variant
 * pack beside the normal one (ModStore.variantId: one per set of enabled code mods). A mod is on when it is installed, and that build starts by itself when a set of code mods
 * that is on has none ({@link #autoBuild}); the section's "Build with mods" is for a failed one and for a rebuild. The normal build is never
 * changed. Every start of the game (the page's Play, Home, a shortcut, a frontend: all go through MainActivity.launch) asks {@link #packToPlay} for the pack:
 * the variant's while code mods are on and their build is ready, else the normal one. So a mod that is on plays, and one that is off or deleted does not.
 * <p>
 * The page is built again after a choice of another row, and a pick, an import or a build outlives that: what is in progress is kept per process (PICK, IMPORT,
 * BUILD, FAILED), and whichever section shows now ({@link #current}) is the one told to render again.
 */
public final class ModsSection {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The section on the page now: the one an import's or a build's news is rendered into (null: none shows) */
    private static ModsSection current;
    /** The section "Install mod…" was tapped in, for the picker's result (LibrarySettings.result): the game the pick was for, whatever the page shows by then */
    private static ModsSection pickFor;
    /** The import or the build with mods in progress: the game's title ID and its line; null: none */
    private static String importTitle, buildTitle, buildText;
    private static CharSequence importText;
    private static String buildVariant;   // the variant buildTitle is being built as: another set of the same game's code mods waits for it as another game's does
    /** The one build with mods that waits to start by itself ({@link #autoBuild}): its game's section and its variant; null: none. Not kept over a restart of the app */
    private static ModsSection waiting;
    private static String waitingVariant;
    private static String deleteTitle, deleteName, deleteText;   // a mod being deleted (one at a time, as an import): its game, its name and how far it is
    private static int deletePct = -1;
    /** Variant ID → why its build failed, until it is started again */
    private static final Map<String, String> FAILED = new HashMap<>();

    private final Activity a;
    private final SectionUi ui;
    private final String titleId, gameName;
    private final File game, pack, home;   // the dump folder or .nup, its pack (null: not built), and the game's folder the mods are in (ModRuntime.home, as GameActivity finds it)
    private final LinearLayout body;
    /** Run when a mod was installed or deleted (Mods & Cheats builds its page again: its Mods count); null: nothing to tell */
    public static Runnable listChanged;
    private boolean ownInstall = true;   // its own "Install mod…" button: off on Mods & Cheats, whose Add installs a mod (dev.peanuts.mods.CheatImportUi)

    /**
     * The section. gameDir and packDir are the game's entry in upstream's list (Game.gameDir, Game.packDir).
     */
    public static View view(Activity a, String titleId, String gameName, String gameDir, String packDir) { return view(a, titleId, gameName, gameDir, packDir, true); }

    /** The section; install: with its own "Install mod…" (Mods & Cheats has Add instead) */
    public static View view(Activity a, String titleId, String gameName, String gameDir, String packDir, boolean install) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        SectionUi ui = new SectionUi(a);
        box.addView(ui.section("Mods"), SectionUi.lp(0, 12));
        ModsSection s = new ModsSection(a, ui, titleId, gameName, gameDir, packDir);
        s.ownInstall = install;
        box.addView(s.body, SectionUi.lp(0, 0));
        current = s;
        // the page left, or built again: this section's views are gone, so nothing more is rendered into them (a newer section has taken current by then)
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { if (current == s) current = null; }
        });
        s.autoBuild();   // renders; and a build with mods that is due (the game was built again, the app started again while one waited) starts
        return box;
    }

    private ModsSection(Activity a, SectionUi ui, String titleId, String gameName, String gameDir, String packDir) {
        this.a = a; this.ui = ui; this.titleId = titleId; this.gameName = gameName;
        game = gameDir == null || gameDir.isEmpty() ? null : new File(gameDir);
        File listed = packDir == null || packDir.isEmpty() ? null : new File(packDir), p = normalPack(a, titleId, listed);
        if (p != null && !p.equals(listed)) repairEntry(a.getApplicationContext(), titleId, gameName, gameDir, p);
        pack = p != null && new File(p, "pack.tsv").isFile() ? p : null;
        home = pack == null ? null : ModRuntime.home(a, titleId, game, p.getParentFile());
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
    }

    /**
     * The game page's Mods & Cheats row (dev.peanuts.mods.CheatsPage.entry), where this section was: a build with mods that is due starts as when the section
     * showed there ({@link #autoBuild}); nothing is rendered
     */
    public static void due(Activity a, String titleId, String gameName, String gameDir, String packDir) {
        ModsSection s = new ModsSection(a, new SectionUi(a), titleId, gameName, gameDir, packDir);
        if (current == null || !current.titleId.equals(titleId)) s.autoBuild();
    }

    /** Mods & Cheats' Add, for a pick that is a mod (romfs, exefs folders): installed as Install mod… installs, into the section on screen; false when none shows */
    public static boolean installPicked(Activity a, Uri u, String displayName, boolean folder) {
        if (current == null || current.home == null || importTitle != null) return false;
        pickFor = current;
        if (folder) pickedFolder(a, u); else pickedZip(a, u, displayName);
        return true;
    }

    /**
     * The pack a start of this game uses (MainActivity.launch's PEANUTS hook): the variant's when code mods are on and their build is ready, else packDir as it
     * is. With code mods on and no build of them yet the game starts without them, and notBuilt (may be null) is run to say so. Anything unreadable: packDir
     */
    public static String packToPlay(android.content.Context c, String titleId, String gameDir, String packDir, Runnable notBuilt) {
        try {
            File p = normalPack(c, titleId, packDir == null || packDir.isEmpty() ? null : new File(packDir));
            if (p == null || !new File(p, "pack.tsv").isFile()) return packDir;
            packDir = p.getPath();   // the game's own pack, also when the list's entry named a variant's (normalPack)
            File game = gameDir == null || gameDir.isEmpty() ? null : new File(gameDir);
            File home = ModRuntime.home(c, titleId, game, p.getParentFile());
            List<ModStore.Mod> codeMods = ModStore.enabledCodeMods(ModRuntime.mods(home, titleId));
            if (codeMods.isEmpty()) return packDir;
            String variantId = ModStore.variantId(titleId, codeMods);
            if (variantId == null) return packDir;
            File variantPack = dev.nxnative.android.AppStorage.pack(c, variantId);
            if (variantProblem(c, titleId, p, variantPack) == null) return variantPack.getPath();
            if (notBuilt != null) notBuilt.run();
        } catch (RuntimeException e) {
            android.util.Log.w("peanuts", "mods: the pack to play: " + e);
        }
        return packDir;
    }

    /**
     * Why a start cannot take the variant's pack, null when it can: there is none, its build did not finish (ModdedBuild.variantProblem), or its own profile
     * is of another version of the app ({@link #staleProfile}). Play then starts the normal pack and says the mods are not built; "Build with mods" says why
     */
    private static String variantProblem(android.content.Context c, String titleId, File normalPack, File variantPack) {
        String why = ModdedBuild.variantProblem(variantPack);
        if (why == null && staleProfile(c, titleId, variantPack)) why = "it was made by another version of the app";
        // The shader library this app's host loads (Pack.libraryOf): the game's own pack has it and the variant, which copies the game's at its build, has not.
        // The launcher then starts its shader update for the pack it is about to play, a job of the game itself on the variant's folder
        String lib = "shaders/v-" + dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN + "/dmsh_vk.lib";
        if (why == null && normalPack != null && new File(normalPack, lib).isFile() && !new File(variantPack, lib).isFile()) why = "its shaders are of another version of the app";
        return why;
    }

    /**
     * The game's own pack for the pack folder the launcher's list has for it. That is the same folder, except when the list's entry names a build with mods
     * (ModdedBuild.isVariantPack): the launcher's panel once put a finished variant's pack into the list as the game's (Ondevice.Panel.onMessage, which no
     * longer does), and this section then planned from the variant's pack.tsv and, once the mod was deleted and the variant with it, read "Cannot build with
     * mods: no module list" with the game's own pack in its place all along. Then: the pack beside the variant (ModdedBuild.normalPackBeside), else where the
     * app keeps the game's (AppStorage.pack); null when neither is a pack (the game is to be built first)
     */
    private static File normalPack(android.content.Context c, String titleId, File listed) {
        if (listed == null || !ModdedBuild.isVariantPack(listed)) return listed;
        for (File f : new File[] {ModdedBuild.normalPackBeside(listed), dev.nxnative.android.AppStorage.pack(c, titleId)})
            if (f != null && new File(f, "pack.tsv").isFile() && !ModdedBuild.isVariantPack(f)) {
                android.util.Log.w("peanuts", "mods: " + titleId + ": the game list names a build with mods as the game's pack (" + listed + "); the game's own is " + f);
                return f;
            }
        android.util.Log.w("peanuts", "mods: " + titleId + ": the game list names a build with mods as the game's pack (" + listed + "), and the game's own pack was not found");
        return null;
    }

    /** Puts the game's own pack back into the launcher's list (upstream's Game.put, which replaces the game's entry and keeps its place; not public: reflection,
     *  as dev.peanuts.ui.Upstream.addToList). Off the main thread: it reads the dumps' build keys. Quiet: the section works from the right pack either way */
    private static void repairEntry(android.content.Context c, String titleId, String name, String gameDir, File pack) {
        if (gameDir == null || gameDir.isEmpty()) return;
        new Thread(() -> {
            try {
                Class<?> k = Class.forName("dev.nxnative.android.Game");
                java.lang.reflect.Constructor<?> ctor = k.getDeclaredConstructor(String.class, String.class, String.class, String.class);
                ctor.setAccessible(true);
                java.lang.reflect.Method put = k.getDeclaredMethod("put", android.content.Context.class, k);
                put.setAccessible(true);
                put.invoke(null, c, ctor.newInstance(titleId, name, gameDir, pack.getPath()));
                android.util.Log.i("peanuts", "mods: " + titleId + ": the game list has the game's own pack again (" + pack + ")");
            } catch (ReflectiveOperationException | RuntimeException e) {
                android.util.Log.w("peanuts", "mods: " + titleId + ": the game list could not be put right: " + e);
            }
        }, "peanuts-mod-list").start();
    }

    /**
     * Whether the variant's own profile (profile.bin beside its pack: scripts/pack_android.py writes one for a build that adds a module or has its own memory
     * numbers, and the host reads it in place of the app's, dev.peanuts.build.GenericProfile) is of another schema than this app's profile of the game: their
     * first eight bytes, the magic and the schema version. An update of the app that changes the schema left the variant with a profile the host refuses at
     * the game's start ("schema version does not match"), until its mods were built again. Without a profile of its own, or without the app's to compare: no
     */
    private static boolean staleProfile(android.content.Context c, String titleId, File variantPack) {
        File own = new File(variantPack, "profile.bin");
        if (!own.isFile()) return false;
        byte[] a = new byte[8], b = new byte[8];
        try (java.io.DataInputStream x = new java.io.DataInputStream(new java.io.FileInputStream(own));
             java.io.DataInputStream y = new java.io.DataInputStream(c.getAssets().open("profiles/" + titleId + ".profile.bin"))) {
            x.readFully(a);
            y.readFully(b);
            return !java.util.Arrays.equals(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static void renderCurrent() { if (current != null) current.render(); }

    // ---- A code mod's build starts by itself: a mod that needs a build gets it when it is installed or switched, without a tap on the button ----

    /** What a build with mods must wait for: another one, a normal build of any game (one packer serves them all), or an install or a delete, whose files it reads */
    private static boolean busy() {
        return buildTitle != null || importTitle != null || deleteTitle != null || ModBuildPipeline.running() != 0 || dev.peanuts.build.BuildPipeline.busy();   // busy(): a game builds or waits in the build queue (issue #348): they share one packer
    }

    /** Whether the app shows now: Android refuses a foreground service (the build) started by an app in the background */
    private static boolean onScreen() {
        android.app.ActivityManager.RunningAppProcessInfo i = new android.app.ActivityManager.RunningAppProcessInfo();
        android.app.ActivityManager.getMyMemoryState(i);
        return i.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    /**
     * Starts the build with mods of the code mods that are on when it is due (ModdedBuild.autoBuild: buildable, not ready, not failed, not being built), as a tap
     * on the button would, or notes it as waiting when something else must end first; then renders the section on screen. Called where the answer can change:
     * the page opens ({@link #view}), a mod was installed, a code mod's switch was flipped, a code mod was deleted, and for the waiting one when a build with
     * mods, an install or a delete ends and every five seconds while it waits ({@link #poll}: a normal build's end is not told to this class). One build waits at
     * a time: a second game's takes the place, and the first is started when its page is opened again. Main thread; reads the mods' manifests, as render does
     */
    private void autoBuild() {
        if (home == null) { renderCurrent(); return; }
        if (waiting != null && waiting.titleId.equals(titleId)) { waiting = null; waitingVariant = null; }   // decided again below
        List<ModStore.Mod> mods = ModRuntime.mods(home, titleId);
        Variant v = ModStore.needsBuild(mods) ? variant(mods) : null;
        boolean buildable = v != null && v.stopTitle == null;
        String due = ModdedBuild.autoBuild(buildable, buildable && v.problem == null, buildable && FAILED.containsKey(v.id),
                buildable && titleId.equals(buildTitle) && v.id.equals(buildVariant), busy() || !onScreen());
        if (due.equals(ModdedBuild.AUTO_START)) { startBuild(v.id, v.pack, v.plan); return; }
        if (due.equals(ModdedBuild.AUTO_WAIT)) {
            waiting = this; waitingVariant = v.id;
            MAIN.removeCallbacks(POLL);
            MAIN.postDelayed(POLL, 5000);
        }
        renderCurrent();
    }

    private static final Runnable POLL = ModsSection::poll;

    /** The waiting build's next try: at once when nothing is in its way, else in five seconds */
    private static void poll() {
        MAIN.removeCallbacks(POLL);
        ModsSection w = waiting;
        if (w == null) return;
        if (busy() || !onScreen()) MAIN.postDelayed(POLL, 5000);
        else w.autoBuild();
    }

    /** A game's failed builds with mods are forgotten when its code mods change (installed, switched, deleted): the next build is of other mods, or a new try */
    private static void forget(String titleId) {
        String prefix = titleId.toUpperCase(java.util.Locale.ROOT) + "-mod-";
        FAILED.keySet().removeIf(id -> id.startsWith(prefix));
    }

    /** Builds the rows again from what is on disk (the mods' folders and manifests are small: read here, as the Companion row reads its package) */
    private void render() {
        body.removeAllViews();
        if (home == null) {
            View row = icon(ui.setting("🧩", "Mods", "Build the game first", null), "mods");
            row.setEnabled(false);
            row.setAlpha(0.5f);
            body.addView(row, SectionUi.lp(0, 0));
            return;
        }
        List<ModStore.Mod> mods = ModRuntime.mods(home, titleId);
        boolean deleting = titleId.equals(deleteTitle), importing = titleId.equals(importTitle) || deleting;   // either: the list's switches and bins wait
        // what an install or a delete left when the app ended in the middle of it: once per run, and never while one goes on (its folders are what a sweep looks for)
        if (deleteTitle == null && importTitle == null && SWEPT.add(home.getPath())) new Thread(() -> ModStore.sweepDeleted(ModRuntime.modsRoot(home), titleId), "peanuts-mod-sweep").start();
        if (importTitle == null && SWEPT.add("the app's cache")) ModImportUi.sweepStaging(a);
        if (mods.isEmpty() && !importing) body.addView(icon(ui.setting("🧩", "No mods installed", "Files, cheats and code mods from this device", null), "mods"), SectionUi.lp(0, 0));
        boolean built = false;   // the code mods that are on have their build (the chip of a code mod says so)
        if (ModStore.needsBuild(mods)) { try { Variant v = variant(mods); built = v.stopTitle == null && v.problem == null; } catch (RuntimeException e) { built = false; } }
        for (ModStore.Mod m : mods) body.addView(modRow(m, !importing, !m.hasCode() ? "Next start" : built && m.enabled ? "Built" : "Needs a build"), SectionUi.lp(0, 0));
        if (titleId.equals(importTitle)) body.addView(ui.setting("📥", "Installing a mod", importText, null), SectionUi.lp(0, 0));
        if (deleting) body.addView(ui.setting("🗑", "Deleting " + deleteName, deleteText + " Install, the switches and Build with mods wait for it.", null), SectionUi.lp(0, 0));
        if (ModStore.needsBuild(mods)) buildRows(mods);
        if (!ownInstall) return;
        LinearLayout buttons = SectionUi.buttons(a);
        SectionUi.add(buttons, ui.button("Install mod…", SectionUi.PRIMARY, v -> install()), importTitle == null && deleteTitle == null);   // one import or delete at a time: its line is one field
        body.addView(buttons, SectionUi.lp(0, 4));
    }

    /** One mod: its name, what it carries, when it applies (a chip: Next start, Needs a build, Built), its switch, and the bin after the switch. The switch and the bin are off while a mod is being installed (its folder is being written) */
    private View modRow(ModStore.Mod m, boolean enabled, String when) {
        View row = ui.toggle(m.hasCode() ? "⚙️" : "🧩", m.name, what(m), m.enabled, (b, on) -> setEnabled(m, on));
        if (!enabled) setEnabledDeep(row, false);
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildAt(0) instanceof TextView) dev.peanuts.ui.Icons.tile((TextView) ((LinearLayout) row).getChildAt(0), m.hasCode() ? "build" : "mods");
        if (row instanceof LinearLayout && when != null) {   // the chip before the switch
            TextView chip = dev.peanuts.ui.PUi.chip(a, when);
            chip.setSingleLine(true);
            if (when.equals("Needs a build")) { chip.setBackground(dev.peanuts.ui.PUi.round(dev.peanuts.ui.PUi.col(a, dev.nxnative.android.R.color.bn_banana), SectionUi.dp(999), 0, 0)); chip.setTextColor(dev.peanuts.ui.PUi.col(a, dev.nxnative.android.R.color.bn_on_banana)); }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.rightMargin = SectionUi.dp(6);
            LinearLayout r = (LinearLayout) row;
            r.addView(chip, Math.max(0, r.getChildCount() - 1), cp);
        }
        if (row instanceof LinearLayout) {
            TextView bin = new TextView(a);
            bin.setText("🗑");
            bin.setTextSize(17);
            bin.setGravity(Gravity.CENTER);
            bin.setContentDescription("Delete " + m.name);
            bin.setFocusable(true);
            bin.setEnabled(enabled);
            bin.setAlpha(enabled ? 1f : 0.4f);
            bin.setOnClickListener(dev.peanuts.ui.Feel.row(v -> confirmDelete(m)));   // it opens the question: a tick, as a row that opens something
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(SectionUi.dp(44), SectionUi.dp(44));
            p.leftMargin = SectionUi.dp(4);
            ((LinearLayout) row).addView(bin, p);
            dev.peanuts.ui.Icons.tile(bin, "trash");   // the Peanuts icon in place of the emoji, where the build has the icons
        }
        return row;
    }

    /** A row's emoji tile with the Peanuts icon instead (Icons.tile; the emoji stays where the build has no icons) */
    private static View icon(View row, String name) {
        if (row instanceof LinearLayout && ((LinearLayout) row).getChildAt(0) instanceof TextView) dev.peanuts.ui.Icons.tile((TextView) ((LinearLayout) row).getChildAt(0), name);
        return row;
    }

    private static void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) setEnabledDeep(((android.view.ViewGroup) v).getChildAt(i), on);
    }

    /** What a mod carries, as ModStore knows it: "Files" and "Cheats" with PUi.joined's bar between, "Code: needs a build with mods" */
    private static CharSequence what(ModStore.Mod m) {
        List<String> parts = new ArrayList<>();
        if (m.hasRomfs()) parts.add("Files");
        if (m.hasCheats()) parts.add("Cheats");
        if (m.hasCode()) parts.add("Code: needs a build with mods");
        return parts.isEmpty() ? "Empty" : dev.peanuts.ui.PUi.joined(parts.toArray(new CharSequence[0]));
    }

    private void setEnabled(ModStore.Mod m, boolean on) {
        new Thread(() -> {
            String err = null;
            try { ModStore.setEnabled(ModRuntime.modsRoot(home), titleId, m.dir.getName(), on); } catch (IOException e) { err = e.getMessage(); }
            final String ferr = err;
            MAIN.post(() -> {
                if (ferr != null) dev.peanuts.ui.Feel.refused(a);
                if (ferr != null) Toast.makeText(a, "Could not turn " + m.name + (on ? " on: " : " off: ") + ferr, Toast.LENGTH_LONG).show();
                else if (!m.hasCode()) Toast.makeText(a, m.name + (on ? " is on" : " is off") + " from the game's next start.", Toast.LENGTH_SHORT).show();   // a code mod: the Build with mods row says what comes next
                if (ferr == null && m.hasCode()) { forget(titleId); autoBuild(); }   // another set of code mods: its build starts by itself when it has none (renders)
                else renderCurrent();   // from disk: the switch shows what was saved
            });
        }, "peanuts-mod-toggle").start();
    }

    private void confirmDelete(ModStore.Mod m) {
        new AlertDialog.Builder(a).setTitle("Delete this mod?").setMessage(m.name + " and its files are deleted from this device" + (m.hasCode() ? ", and the builds with mods made with it" : "")
                        + ". The game and its saves are not changed.")
                .setPositiveButton("Delete", dev.peanuts.ui.Feel.confirm((d, w) -> startDelete(m))).setNegativeButton("Cancel", null).show();
    }

    /**
     * Deletes off the UI thread with its own row and percentage: a mod's files can be GBs on a memory card and take minutes (Luminescent: 1.7 GB, 3,464 files).
     * The mod leaves the list and the game at once (ModStore.remove renames its folder first); while the files go, install, the switches, the bins and Build
     * with mods are off. The end is shown in the row's place when this section is on screen, and not as a message over whatever is on screen by then
     * (a toast came up in another game).
     */
    private void startDelete(ModStore.Mod m) {
        if (deleteTitle != null || importTitle != null) return;
        boolean code = m.hasCode();
        // the build reads the mod's files, and the delete below takes the builds made with it away
        if (code && titleId.equals(buildTitle)) { message("Delete this mod", gameName + " is being built with its mods now. Delete " + m.name + " when the build has ended."); return; }
        deleteTitle = titleId; deleteName = m.name; deleteText = "Counting its files…"; deletePct = -1;
        renderCurrent();
        File root = ModRuntime.modsRoot(home), gameHome = home;
        String id = titleId, folder = m.dir.getName(), name = m.name;
        android.content.Context app = a.getApplicationContext();
        ModsSection self = this;
        new Thread(() -> {
            String err = null;
            try {
                ModStore.remove(root, id, folder, (done, total) -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct == deletePct) return;
                    MAIN.post(() -> { deletePct = pct; deleteText = pct < 0 ? "Counting its files…" : pct + " %."; renderCurrent(); });
                });
            } catch (IOException e) { err = e.getMessage(); }
            if (code) sweepVariants(app, id, gameHome);   // the mod is out of the list by now, also when not all of its files went
            final String ferr = err;
            MAIN.post(() -> {
                deleteTitle = null; deleteName = null; deleteText = null; deletePct = -1;
                if (ferr != null) SWEPT.remove(gameHome.getPath());   // so that the page's next opening looks for what is left, as the message below says
                if (ferr != null) android.util.Log.w("peanuts", "mods: delete of " + name + ": " + ferr);
                if (current != null && ferr != null) dev.peanuts.ui.Feel.refused(current.a);
                if (current != null && ferr != null) current.message("Delete failed", name + ": " + ferr + "\n\nWhat is left of it is removed the next time this page is opened.");
                if (code) forget(id);
                if (code) self.autoBuild(); else renderCurrent();   // the code mods that are left on: their build starts by itself when they have none (renders)
                if (ferr == null && listChanged != null) listChanged.run();
                poll();   // and a build that waited for this delete
            });
        }, "peanuts-mod-delete").start();
    }
    private static final java.util.Set<String> SWEPT = java.util.Collections.synchronizedSet(new java.util.HashSet<>());   // game folders whose interrupted deletes were looked for in this run

    /**
     * After a code mod is deleted: the builds with mods no choice of the switches gives any more go with it (ModStore.reachableVariantIds from the mods that
     * are left, ModdedBuild.sweepVariants). Each is a pack of its own, the modules and a copy of the shader library, which nothing else in the app removes; and
     * a mod installed again under the same name had the deleted one's build as "Ready". On the delete's thread. Nothing goes when the mods cannot be listed
     */
    private static void sweepVariants(android.content.Context c, String titleId, File home) {
        try {
            if (!ModRuntime.modsRoot(home).isDirectory()) return;
            java.util.Set<String> keep = ModStore.reachableVariantIds(titleId, ModRuntime.mods(home, titleId));
            File files = c.getExternalFilesDir(null);   // the app's packs/<id>, as dev.nxnative.android.AppStorage.pack (its root and packs are not public)
            int n = ModdedBuild.sweepVariants(titleId, keep, new File(files != null ? files : c.getFilesDir(), "packs"), dev.peanuts.PeanutsPrefs.buildGameDir(c, titleId), home);
            if (n > 0) android.util.Log.i("peanuts", "mods: " + titleId + ": " + n + " build(s) with mods of a deleted mod removed");
        } catch (RuntimeException e) {
            android.util.Log.w("peanuts", "mods: removing the builds of a deleted mod: " + e);
        }
    }

    // ---- Install mod…: the pickers' results come back through MainActivity.onActivityResult → dev.peanuts.ui.LibrarySettings.result ----

    private void install() {
        pickFor = this;
        ModImportUi.pick(a, titleId, () -> ModImportUi.pickFolder(a, LibrarySettings.REQ_MOD_FOLDER), () -> ModImportUi.pickZip(a, LibrarySettings.REQ_MOD_ZIP));
    }

    /** The folder picker's result. a: the activity that got it (the one the pick was started in may have been made again meanwhile) */
    public static void pickedFolder(Activity a, Uri tree) {
        ModsSection s = startImport();
        if (s != null) ModImportUi.importFolder(a, tree, ModRuntime.modsRoot(s.home), s.titleId, null, ModdedBuild.buildIds(s.pack), ModsSection::importProgress, (ok, message) -> importDone(a, s, ok, message));
    }

    /** The zip picker's result; displayName: the picked file's name, the mod's name when its layout has none */
    public static void pickedZip(Activity a, Uri doc, String displayName) {
        ModsSection s = startImport();
        if (s != null) ModImportUi.importZip(a, doc, ModRuntime.modsRoot(s.home), s.titleId, displayName, ModdedBuild.buildIds(s.pack), ModsSection::importProgress, (ok, message) -> importDone(a, s, ok, message));
    }

    /** The section the pick was for, with its import marked as started; null when there is none (the app was started again while the picker showed) or another import runs */
    private static ModsSection startImport() {
        ModsSection s = pickFor;
        pickFor = null;
        if (s == null || s.home == null || importTitle != null) return null;
        importTitle = s.titleId; importText = "Starting…";
        renderCurrent();
        return s;
    }

    private static void importProgress(long done, long total, String name) {
        importText = total > 0 ? dev.peanuts.ui.PUi.joined((done * 100 / total) + " %", name) : name;
        renderCurrent();
    }

    /** The end of an import: its sentence (installed, or why not: a dialog, since a refusal such as a mod for another game is longer than a toast holds).
     *  s: the section the install was started in. The mod is on (ModImporter), and a code mod's build starts here by itself, whatever page shows by now */
    private static void importDone(Activity a, ModsSection s, boolean ok, String message) {
        importTitle = null; importText = null;
        if (ok) dev.peanuts.ui.Feel.accepted(a); else dev.peanuts.ui.Feel.refused(a);   // installed, or refused (a mod for another game)
        if (!a.isFinishing() && !a.isDestroyed()) new AlertDialog.Builder(a).setTitle("Install mod").setMessage(message).setPositiveButton("OK", null).show();
        if (ok) { forget(s.titleId); s.autoBuild(); } else renderCurrent();
        poll();   // a build that waited for this install
        if (ok && listChanged != null) listChanged.run();
    }

    // ---- Code mods: the build with mods (dev.peanuts.build.ModBuild / ModdedBuild / ModBuildPipeline) and its Play ----

    /** The build with mods of the code mods that are on, as the rows show it and as {@link #autoBuild} decides on it */
    private static final class Variant {
        String stopTitle, stopText;   // why nothing can be built (a row of its own); null: it can
        String warning;               // a module whose hooks will not run (a row of its own, and the build goes on); null: none
        ModBuild.Plan plan;
        String id, problem;           // its variant id, and why its pack cannot be played (variantProblem); null: ready
        File pack;
    }

    private Variant variant(List<ModStore.Mod> mods) {
        Variant v = new Variant();
        List<String> profileModules = ModdedBuild.normalModules(pack);
        if (profileModules == null) { v.stopTitle = "Cannot build with mods"; v.stopText = "The game's build cannot be read: " + new File(pack, "pack.tsv") + " is missing or has no modules row. Leave this page and open it again; if it still says so, build the game again (its mods are then built by themselves)."; return v; }
        List<ModStore.Mod> codeMods = ModStore.enabledCodeMods(mods);
        v.plan = ModBuild.compose(profileModules, codeMods, dev.nxnative.android.BuildConfig.NX_GENERIC_HOST);
        if (v.plan.refusal != null) { v.stopTitle = "Cannot build with these mods"; v.stopText = v.plan.refusal; return v; }
        v.warning = ModdedBuild.exlaunchWarning(profileModules, v.plan);
        v.id = ModStore.variantId(titleId, codeMods);
        // No module and no patch file in them (an exefs/ with a .pchtxt or a main.npdm alone): the build's extra arguments would be empty, and a job without
        // them is a build of the game itself, into the variant's folder and remembered as the game's own job (PackService)
        if (v.id == null || v.plan.source.isEmpty() && v.plan.patchSets.isEmpty()) {
            v.stopTitle = "Nothing to build";
            v.stopText = "The code mods that are on have no module (main, subsdk…) and no IPS patch in them. A .pchtxt patch is not one the app can build. Turn them off, or install them again.";
            return v;
        }
        v.pack = dev.nxnative.android.AppStorage.pack(a, v.id);
        v.problem = variantProblem(a, titleId, pack, v.pack);   // as packToPlay asks: the row says what Play does
        return v;
    }

    /** The rows under the list while a code mod is on: what stops a build, or its state (building, waiting, failed and why, ready) with Build with mods.
     *  The build starts by itself ({@link #autoBuild}); the button is for a failed one, one that waits for a build this class does not see, and a rebuild */
    private void buildRows(List<ModStore.Mod> mods) {
        Variant v = variant(mods);
        if (v.warning != null) explain("⚠️", "A module's hooks will not run", v.warning);
        if (v.stopTitle != null) { explain("⚠️", v.stopTitle, v.stopText); return; }
        String variantId = v.id;
        boolean ready = v.problem == null, built = !ModdedBuild.NOT_BUILT.equals(v.problem), building = buildTitle != null, mine = titleId.equals(buildTitle) && variantId.equals(buildVariant);
        boolean waits = waiting != null && titleId.equals(waiting.titleId) && variantId.equals(waitingVariant);
        String failed = FAILED.get(variantId);
        String value = mine ? "Building: " + buildText
                : waits ? "Waiting: starts when the " + (importTitle != null ? "install" : deleteTitle != null ? "delete" : "build") + " that is running finishes."
                : building ? "Another game is being built with mods. Wait for it to finish."
                : failed != null ? "Failed: " + failed
                : ready ? "Ready. Play starts the game with its code mods."
                : built ? "The build with mods must be made again: " + v.problem + ". Until then Play starts the game without its code mods."
                : "Code mods need a build of their own: until it is made, Play starts the game without them. The normal build is kept as it is.";
        body.addView(ui.setting("🔨", "Build with mods", value, failed == null || mine || waits ? null : x -> message("Build with mods failed", failed)), SectionUi.lp(0, 0));
        if (mine) return;
        LinearLayout buttons = SectionUi.buttons(a);
        SectionUi.add(buttons, ui.button(built ? "Rebuild with mods" : "Build with mods", ready ? SectionUi.QUIET : SectionUi.PRIMARY, dev.peanuts.ui.Feel.heavy(x -> startBuild(variantId, v.pack, v.plan))), !building && deleteTitle == null);
        body.addView(buttons, SectionUi.lp(0, 0));
    }

    /** A row whose sentence may be longer than its three lines: a tap shows all of it */
    private void explain(String emoji, String title, String text) {
        body.addView(ui.setting(emoji, title, text, v -> message(title, text)), SectionUi.lp(0, 0));
    }

    private void message(String title, String text) { new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("OK", null).show(); }

    /** The build, from the button or by itself (autoBuild, which has no haptic of a tap): the same rows, the same end. Renders the section on screen */
    private void startBuild(String variantId, File variantPack, ModBuild.Plan plan) {
        if (waiting != null && waiting.titleId.equals(titleId)) { waiting = null; waitingVariant = null; }   // this is its start, or the user's own
        ModBuildRequest r = new ModBuildRequest();
        r.titleId = titleId; r.name = gameName; r.dump = game; r.normalPack = pack; r.variantId = variantId;
        r.workDir = ModdedBuild.workDir(home, variantId);
        r.packDir = variantPack;
        r.modArgs = ModdedBuild.rewriteArgvJoined(plan);
        FAILED.remove(variantId);
        buildTitle = titleId; buildVariant = variantId; buildText = "starting…";
        String name = gameName, id = titleId;
        File normal = pack;
        Activity act = a;
        ModBuildPipeline.start(a, r, new BuildListener() {   // a request that cannot be built comes back through done() too, with why
            @Override public void progress(String step, int percent, String detail) { buildText = detail + " (" + percent + " %)"; renderCurrent(); }
            @Override public void done(boolean ok, String message, String code) {
                buildTitle = null; buildVariant = null; buildText = null;
                if (!ok) FAILED.put(variantId, message);   // also what keeps a failed or stopped build from starting again by itself (autoBuild)
                String still = ok ? variantProblem(act, id, normal, variantPack) : null;   // ended well and cannot be played: said, and not built again and again
                if (still != null) FAILED.put(variantId, "The build ended, but " + still + ".");
                if (ok) dev.peanuts.ui.Feel.done(act); else if (!BuildListener.CANCELLED.equals(code)) dev.peanuts.ui.Feel.refused(act);   // felt only while the app shows; a stop the user asked for is quiet
                Toast.makeText(act.getApplicationContext(), ok ? name + " with mods is ready." : "Build with mods failed: " + message, Toast.LENGTH_LONG).show();
                renderCurrent();
                poll();   // a build that waited for this one (another game's, or this game's other set of code mods)
            }
        });
        renderCurrent();
    }
}
