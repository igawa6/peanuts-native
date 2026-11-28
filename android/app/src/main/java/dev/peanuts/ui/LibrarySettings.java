package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.Toast;

import dev.peanuts.PeanutsPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Settings › Library (SPEC.md), the first section of Banana's Settings: every folder the app uses (LibraryFolders: the game folders, the update and DLC folders,
 * the build folders, each a row, and Add folder), the keys (prod.keys, copied into files/keys and checked by the importer), the firmware fonts (the importer
 * extracts them into files/firmware/fonts), Rescan, and the Companions catalog. The pickers are the system's; their results come back through
 * MainActivity.onActivityResult to {@link #result} (requests 60 to 70, also the game sheet's Choose/Add). The same pickers serve Home's first-run card and the
 * sheet's Add keys….
 */
public final class LibrarySettings {
    static final String KEYS_FIRMWARE_GUIDANCE = "Firmware and keys should be the same version.";
    /** 61, 62 and 68 are the folder pickers of FolderKinds (games, updates and DLC, builds); 63 was the DLC folder's, a kind of its own before issue #546 */
    static final int REQ_KEYS = 60, REQ_FIRMWARE = 64, REQ_SHEET_UPDATE = 65, REQ_SHEET_DLC = 66, REQ_FIRMWARE_ZIP = 67;
    /** dev.peanuts.settings.ModsSection's "Install mod…" (ACTION_OPEN_DOCUMENT_TREE / ACTION_OPEN_DOCUMENT), public: the section lives in another
     *  package and only needs these two request codes, not any other picker here */
    public static final int REQ_MOD_FOLDER = 69, REQ_MOD_ZIP = 70;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static String fontsState;   // "Reading the firmware…" and what came of it, while the rows show
    private static PUi.Setting keysRow, fontsRow, companionsRow, hapticsRow, motionRow, swapRow, tvRow, bottomRow, gamesRow, smoothRow, devRow;
    private static Activity rowsOf;

    private LibrarySettings() {}

    /** The section and its card, added to the Settings column before upstream's own sections */
    public static void add(Activity a, LinearLayout content) {
        rowsOf = a;
        BottomScreen.page(a);   // bottom screen: logo
        PUi.add(content, PUi.section(a, "Library"), 16);
        LinearLayout card = PUi.add(content, PUi.listCard(a), 8);
        // a row that opens a picker or another screen ticks; Rescan, which acts at once, clicks (Haptics)
        LibraryFolders.rows(a, card);   // each folder a row, then Add folder (issue #546)
        keysRow = new PUi.Setting(a, "🔑", "Keys", Haptics.on(Haptics.Word.TICK, v -> pickKeys(a))).chevron(a);
        card.addView(keysRow.view);
        fontsRow = new PUi.Setting(a, "🔤", "Firmware", Haptics.on(Haptics.Word.TICK, v -> pickFirmwareSource(a))).chevron(a);
        card.addView(fontsRow.view);
        PUi.add(card, PUi.body(a, KEYS_FIRMWARE_GUIDANCE), 12);
        card.addView(new PUi.Setting(a, "🔄", "Rescan", Haptics.on(Haptics.Word.CLICK, v -> { Library.rescan(a); Toast.makeText(a, "Looking in the folders again", Toast.LENGTH_SHORT).show(); }))
                .value("Reads the folders again").chevron(a).view);
        card.addView(new PUi.Setting(a, "🧹", "Free up space", Haptics.on(Haptics.Word.TICK, v -> freeUpSpace(a)))
                .value("Delete original dumps of games already built").chevron(a).view);
        String played = dev.peanuts.launch.PlayTime.totalLine(a);   // the total play time of every game (dev.peanuts.launch.PlayTime), once one was played
        if (played != null) { PUi.Setting pr = new PUi.Setting(a, "⏱️", "Played", null).value(played); pr.view.setClickable(false); pr.view.setFocusable(false); card.addView(pr.view); }
        // Haptics and Motion: each row is its own switch between two values, System (the default: what Android's settings say) and the quieter one
        hapticsRow = new PUi.Setting(a, "📳", "Haptics", v -> {
            boolean off = !PeanutsPrefs.hapticsOff(a);
            PeanutsPrefs.hapticsOff(a, off);
            Haptics.refresh(a);
            Haptics.play(v, Haptics.Word.CLICK);   // felt when it turns back on, nothing when it turns off
            refreshRows();
        });
        card.addView(hapticsRow.view);
        motionRow = new PUi.Setting(a, "🎞️", "Motion", v -> {
            PeanutsPrefs.motionReduced(a, !PeanutsPrefs.motionReduced(a));
            Motion.refresh(a);
            Haptics.play(v, Haptics.Word.CLICK);
            refreshRows();
        });
        card.addView(motionRow.view);
        // Swap screens: only where there is a second display to swap with (dev.peanuts.Screens)
        swapRow = null;
        if (dev.peanuts.Companion.hasSecondDisplay(a)) {
            swapRow = new PUi.Setting(a, "🔃", "Swap screens", v -> {
                PeanutsPrefs.swapScreens(a, !PeanutsPrefs.swapScreens(a));
                Haptics.play(v, Haptics.Word.CLICK);
                refreshRows();
            });
            card.addView(swapRow.view);
        }
        // TV and monitors: the system decides what a TV shows (the default), or a game plays on it (dev.peanuts.Screens, #358)
        tvRow = new PUi.Setting(a, "", "TV and monitors", v -> {   // no emoji: its tile is Peanuts' "tv" icon (SettingsSections, SettingsPage.rowIcon)
            PeanutsPrefs.tvPlay(a, !PeanutsPrefs.tvPlay(a));
            Haptics.play(v, Haptics.Word.CLICK);
            refreshRows();
        });
        card.addView(tvRow.view);
        // Bottom screen (BottomScreen)
        bottomRow = new PUi.Setting(a, "📱", "Bottom screen", v -> {
            PeanutsPrefs.bottomScreen(a, !PeanutsPrefs.bottomScreen(a));
            BottomScreen.settingChanged();
            Haptics.play(v, Haptics.Word.CLICK);
            refreshRows();
        });
        card.addView(bottomRow.view);
        gamesRow = new PUi.Setting(a, "🎮", "Games without a companion", v -> {
            if (!PeanutsPrefs.bottomScreen(a)) { Haptics.play(v, Haptics.Word.REJECT); return; }
            // Android, then Stats, then Cover, then Android again
            boolean in = PeanutsPrefs.bottomScreenInGames(a);
            boolean stats = PeanutsPrefs.bottomScreenGamesPage(a).equals(PeanutsPrefs.GAMES_STATS);
            if (!in) { PeanutsPrefs.bottomScreenInGames(a, true); PeanutsPrefs.bottomScreenGamesPage(a, PeanutsPrefs.GAMES_STATS); }
            else if (stats) PeanutsPrefs.bottomScreenGamesPage(a, PeanutsPrefs.GAMES_COVER);
            else PeanutsPrefs.bottomScreenInGames(a, false);
            Haptics.play(v, Haptics.Word.CLICK);
            refreshRows();
        });
        card.addView(gamesRow.view);
        companionsRow = new PUi.Setting(a, "🖥️", "Dual-screen companions", Haptics.on(Haptics.Word.TICK, v -> a.startActivity(new Intent().setClassName(a.getPackageName(), "dev.peanuts.CatalogActivity"))))
                .chevron(a);
        card.addView(companionsRow.view);
        // Your own Miis, which games list as the console's (dev.peanuts.mii.MiiActivity, DM_MII_DB)
        card.addView(new PUi.Setting(a, "🙂", "Miis", Haptics.on(Haptics.Word.TICK, v -> a.startActivity(new Intent().setClassName(a.getPackageName(), "dev.peanuts.mii.MiiActivity"))))
                .value("Make your own Miis for games").chevron(a).view);
        // Developer-only, no row of its own (PeanutsPrefs.developerReadout): a long press flips whether a title with no companion package shows the raw
        // chains/scan readout on the second display instead of leaving it free for Android's own launcher. The row's own value says so when it's on, so
        // it can't be left on by accident when the device is handed back.
        companionsRow.view.setOnLongClickListener(v -> {
            boolean on = !PeanutsPrefs.developerReadout(a);
            PeanutsPrefs.developerReadout(a, on);
            Toast.makeText(a, "Developer second-screen readout: " + (on ? "on" : "off"), Toast.LENGTH_SHORT).show();
            refreshRows();
            return true;
        });
        // The in-game panel's Mods tab for every game (dev.peanuts.mods.ModsTab; SettingsSections puts the row under App › In-game panel)
        PUi.Setting modsTab = new PUi.Setting(a, "", "Mods tab", null).value("Cheats and mods in the quick menu")
                .toggle(a, PeanutsPrefs.modsTabAll(a), (b, on) -> { Feel.toggled(b, on); PeanutsPrefs.modsTabAll(a, on); });
        modsTab.view.setOnClickListener(v -> modsTab.toggle.toggle());
        card.addView(modsTab.view);
        // The player's own button for the in-game quick menu (QuickButton; SettingsSections puts the row under Controls › Controller)
        PUi.Setting quick = new PUi.Setting(a, "", "Quick menu button", null).chevron(a).value(QuickButton.label(a));
        quick.view.setOnClickListener(v -> QuickButton.row(a, () -> quick.value(QuickButton.label(a))));
        card.addView(quick.view);
        // Smooth Experience (dev.peanuts.smooth; SettingsSections puts the row under App › Extras): its switch, and right under it, while it is on, one switch
        // per thing it takes away (SmoothItems, all on by default), in a tinted block set in under the switch's title, so they read as its parts. The block
        // is not a row of its own: SettingsPage carries it with the row it follows
        smoothRow = new PUi.Setting(a, "", "Smooth Experience", null)
                .toggle(a, dev.peanuts.smooth.Smooth.on(a), (b, on) -> { Feel.toggled(b, on); PeanutsPrefs.smooth(a, on); refreshRows(); });
        smoothRow.view.setOnClickListener(v -> smoothRow.toggle.toggle());
        card.addView(smoothRow.view);
        smoothBox = new LinearLayout(a);
        smoothBox.setOrientation(LinearLayout.VERTICAL);
        smoothBox.setBackground(PUi.round(PUi.col(a, dev.nxnative.android.R.color.bn_tint), PUi.dp(a, 14), 0, 0));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = PUi.dp(a, 52); bp.rightMargin = PUi.dp(a, 6); bp.bottomMargin = PUi.dp(a, 6);   // under the switch's title, not its tile
        card.addView(smoothBox, bp);
        smoothPart(a, smoothBox, new PUi.Setting(a, "", "Start screen", null), dev.peanuts.smooth.SmoothItems.START_LOG);
        smoothPart(a, smoothBox, new PUi.Setting(a, "", "Preparing graphics notice", null), dev.peanuts.smooth.SmoothItems.WARM);
        smoothPart(a, smoothBox, new PUi.Setting(a, "", "Graphics driver notice", null), dev.peanuts.smooth.SmoothItems.SKIP);
        // Developer view (dev.peanuts.smooth.DevView; under Developer › Testing): the testers' toasts and the in-game menu's tool words, or neither
        devRow = new PUi.Setting(a, "", "Developer view", null)
                .toggle(a, dev.peanuts.smooth.DevView.on(a), (b, on) -> { Feel.toggled(b, on); dev.peanuts.smooth.DevView.on(a, on); refreshRows(); });
        devRow.view.setOnClickListener(v -> devRow.toggle.toggle());
        card.addView(devRow.view);
        // amiibo for every game (dev.peanuts.amiibo.AmiiboSwitch; SettingsSections puts the row under Users › amiibo): off by default, a game's own switch
        // in its Game options comes first
        PUi.Setting amiibo = new PUi.Setting(a, "", "amiibo", null).value("Games find your amiibo on Peanuts' stand, from their next start. Off: they find none, as before.")
                .toggle(a, dev.peanuts.amiibo.AmiiboSwitch.all(a), (b, on) -> { Feel.toggled(b, on); dev.peanuts.amiibo.AmiiboSwitch.all(a, on); });
        amiibo.view.setOnClickListener(v -> amiibo.toggle.toggle());
        card.addView(amiibo.view);
        // amiibo pictures (dev.peanuts.amiibo.AmiiboPictures, under Users › amiibo with amiibo): off by default, says where the pictures are downloaded
        // from, nothing downloaded until on (and while amiibo is off everywhere: AmiiboSwitch.picturesOn)
        PUi.Setting pics = new PUi.Setting(a, "", "amiibo pictures", null)
                .value("Turning this on downloads each amiibo's picture from AmiiboAPI on GitHub (raw.githubusercontent.com) when a page shows it. Off: Peanuts' own card, nothing downloaded.")
                .toggle(a, dev.peanuts.amiibo.AmiiboSwitch.pictures(a), (b, on) -> { Feel.toggled(b, on); dev.peanuts.amiibo.AmiiboSwitch.pictures(a, on); });
        pics.view.setOnClickListener(v -> pics.toggle.toggle());
        card.addView(pics.view);
        refreshRows();
    }

    /** Smooth Experience's parts, in their block under its row, shown while it is on (refreshRows) */
    private static LinearLayout smoothBox;

    /** One of Smooth Experience's parts in its block: no tile, a little smaller than a row of its own; its switch (the row turns it, one focus stop) and its line */
    private static void smoothPart(Activity a, LinearLayout box, PUi.Setting r, String key) {
        r.value(dev.peanuts.smooth.SmoothItems.of(key).line).toggle(a, PeanutsPrefs.smoothItem(a, key), (b, on) -> { Feel.toggled(b, on); PeanutsPrefs.smoothItem(a, key, on); refreshRows(); });
        r.view.setOnClickListener(v -> r.toggle.toggle());
        r.view.getChildAt(0).setVisibility(android.view.View.GONE);   // the tile: the switch above has the icon
        r.view.setMinimumHeight(PUi.dp(a, 52));
        r.view.setPadding(PUi.dp(a, 4), PUi.dp(a, 6), PUi.dp(a, 12), PUi.dp(a, 6));
        ((android.widget.TextView) ((LinearLayout) r.view.getChildAt(1)).getChildAt(0)).setTextSize(15);
        box.addView(r.view);
    }

    private static CharSequence companionsValue(Activity a) {
        String base = "Pages made for one game that show on the second screen while you play, like its map or items. Install, update or remove them.";
        return PeanutsPrefs.developerReadout(a) ? PUi.joined(base, "developer readout on (long press to turn off)") : base;
    }

    static void refreshRows() {
        Activity a = rowsOf;
        if (a == null || keysRow == null) return;
        keysRow.value(keysValue(a));
        fontsRow.value(fontsValue(a, "Used by games and companions. Missing characters in Android's fonts can prevent a game from starting."));
        if (companionsRow != null) companionsRow.value(companionsValue(a));
        if (hapticsRow != null) hapticsRow.value(PeanutsPrefs.hapticsOff(a) ? "Off. Tap to follow the system's touch feedback setting again."
                : "System: follows Android's touch feedback setting. Tap to turn the app's own haptics off.");
        if (motionRow != null) motionRow.value(PeanutsPrefs.motionReduced(a) ? "Reduced: fades and progress only. Tap to follow the system's animation setting again."
                : "System: follows Android's animation setting. Tap for reduced motion (fades and progress only).");
        if (smoothRow != null) {
            boolean on = dev.peanuts.smooth.Smooth.on(a);
            smoothRow.value(dev.peanuts.smooth.SmoothItems.rowLine(on, dev.peanuts.smooth.Smooth.picked(a)));
            if (smoothBox != null) smoothBox.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);   // the page grows under the switch while it is on
        }
        if (devRow != null) devRow.value(dev.peanuts.smooth.DevView.on(a) ? "On: messages for testers and the in-game menu's tool words show. Tap to turn them off."
                : "Off: no messages for testers, and the in-game menu in plain words. The log keeps them. Tap to show them.");
        if (swapRow != null) swapRow.value(PeanutsPrefs.swapScreens(a)
                ? "On: games start on the other screen, the companion is on this one. Tap to swap back. Takes effect at the next game start."
                : "Off: games start on this screen, the companion is on the other one. Tap to swap, for a clip-on top screen. Takes effect at the next game start.");
        if (tvRow != null) tvRow.value(PeanutsPrefs.tvPlay(a)
                ? "Play on the TV: a game starts full screen on a TV or monitor. Tap to follow the system."
                : "System: a TV or monitor shows what Android sends it. Tap to start games on the TV instead.");
        if (bottomRow != null) bottomRow.value(PeanutsPrefs.bottomScreen(a) ? "On: a second screen shows the game you are on, with Play and its page. Tap to leave that screen to Android."
                : "Off: a second screen shows Android's own launcher while you are here. Tap to turn it on.");
        if (gamesRow != null) {
            boolean on = PeanutsPrefs.bottomScreen(a);
            gamesRow.view.setAlpha(on ? 1f : 0.5f);
            gamesRow.value(!on ? "Needs Bottom screen on."
                    : !PeanutsPrefs.bottomScreenInGames(a) ? "Android's own screen while you play. Tap for the game's live stats there."
                    : PeanutsPrefs.bottomScreenGamesPage(a).equals(PeanutsPrefs.GAMES_STATS)
                    ? "Stats: frame rate, frame times, GPU, CPU, temperature, battery and the game's RAM while you play. A four-finger tap on the game hides or shows it. Tap for the game's cover instead."
                    : "The game's cover stays on the second screen while you play. A four-finger tap on the game hides or shows it. Tap to leave it to Android.");
        }
    }

    static CharSequence keysValue(Activity a) {
        if (!PeanutsPrefs.hasKeys(a)) return "Needed to build a game (Build asks for them). Tap to pick the prod.keys from your own console.";
        return PUi.joined("prod.keys is in the app (" + PeanutsPrefs.keysFile(a).length() + " bytes)", ImporterPort.get().available() ? null : "the importer is not in this build");
    }

    private static String firmwareTried;   // the firmware path a read was last started for by firmwareAfterAccess: once a path, not at every drawing of the row

    /** A firmware folder or zip that is set but not read (picked before All files access was given, or the read failed for want of it): read now that the
     * access is there. Called where the Firmware row gets its text, so coming back from Android's access page is enough */
    private static void firmwareAfterAccess(Activity a) {
        String fw = PeanutsPrefs.firmwareDir(a);
        if (fw == null || !fw.startsWith("/") || fw.equals(firmwareTried) || PeanutsPrefs.hasFonts(a) || !Environment.isExternalStorageManager()) return;
        if ("Reading the firmware…".equals(fontsState) || !new File(fw).exists()) return;
        firmwareTried = fw;
        extractFonts(a, fw, null);
    }

    /** The launcher is in front again (MainActivity.onResume): coming back from Android's All files access page reads a firmware that waited for it */
    public static void resume(Activity a) {
        if (fontsState != null && fontsState.startsWith("Waiting for file access") && Environment.isExternalStorageManager()) fontsState = null;
        firmwareAfterAccess(a);
        LibraryFolders.resumed(a);
        refreshRows();
    }

    static CharSequence fontsValue(Activity a, String none) {
        firmwareAfterAccess(a);
        String fw = PeanutsPrefs.firmwareDir(a);
        if (fontsState != null) return fontsState;
        if (PeanutsPrefs.hasFonts(a)) return "Firmware added" + (fw != null ? " from " + fw : "");
        return fw != null ? PUi.joined(fw, "not added yet") : none;
    }

    // ---- Pickers ----

    /** The keys picker (Settings › Console › Keys, the first-run card, the sheet's Add keys…) */
    public static void pickKeys(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        PUi.add(box, PUi.body(a, KEYS_FIRMWARE_GUIDANCE), 0);
        PUi.sheet(a, "Firmware and keys", box)
                .setPositiveButton("Choose keys", (d, w) -> pickFile(a, REQ_KEYS, "Pick your prod.keys"))
                .setNegativeButton("Cancel", null).show();
    }

    static void pickFile(Activity a, int request, String title) { pickFile(a, request, title, "*/*"); }

    private static final String[] ZIP_TYPES = {"application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip", "application/octet-stream"};

    static void pickFile(Activity a, int request, String title, String mime) {
        Toast.makeText(a, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE);
        // A zip is not application/zip on every phone: a file manager or a download can label it x-zip-compressed, x-zip or plain octet-stream, and the
        // picker then greys it out so it cannot be chosen at all (GitHub issue #42 may be this). The importer says what a wrong file is
        if ("application/zip".equals(mime)) i.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, ZIP_TYPES);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, request);
    }

    /** Settings › Console › Firmware: a folder of extracted NCAs (the old way), or a firmware .zip with the same NCAs flat inside it (stored
     * or deflated — users usually have firmware as a zip, not pre-extracted) */
    static void pickFirmwareSource(Activity a) {   // also the game sheet's and the first-run card's firmware buttons
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        AlertDialog[] d = new AlertDialog[1];
        PUi.add(box, PUi.body(a, "Pick the folder your firmware is extracted into, or a firmware .zip (its NCA files flat inside, stored or deflated)."), 0);
        PUi.add(box, PUi.body(a, KEYS_FIRMWARE_GUIDANCE), 12);
        PUi.add(box, new PUi.Setting(a, "📁", "Folder", v -> { d[0].dismiss(); pickFolder(a, REQ_FIRMWARE, "Pick your firmware folder"); }).chevron(a).view, 12);
        PUi.add(box, new PUi.Setting(a, "🗜️", "Zip file", v -> { d[0].dismiss(); pickFile(a, REQ_FIRMWARE_ZIP, "Pick your firmware zip", "application/zip"); }).chevron(a).view, 0);
        d[0] = PUi.sheet(a, "Firmware", box).setPositiveButton("Cancel", null).show();
    }

    /** As upstream's MainActivity.pickFolder: the system folder picker opened at the shared storage's root */
    static void pickFolder(Activity a, int request, String title) {
        Toast.makeText(a, title, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3A"));
        a.startActivityForResult(i, request);
    }

    /** The game sheet's Build destination › Add folder…: a build folder picked here is that game's destination too (result) */
    static void addBuildFolder(Activity a) { buildFolderFromSheet = true; LibraryFolders.pick(a, dev.peanuts.folders.FolderKinds.BUILDS, false); }

    private static boolean buildFolderFromSheet;   // the build folder picker was opened by a game's sheet

    /** MainActivity.onActivityResult's first line: true when the request was one of this package's (handled, whatever the result) */
    public static boolean result(Activity a, int request, int result, Intent data) {
        if (BuildLogUi.result(a, request, result, data)) return true;   // Share build log's Save to a folder (REQ_SAVE 71)
        if (PackImportUi.result(a, request, result, data)) return true;   // the game sheet's Import pack… (72, 73)
        if (dev.peanuts.mods.CheatImportUi.result(a, request, result, data)) return true;   // Mods & Cheats' Add (83, 84 when it started the pick)
        if (dev.nxnative.android.CheatsBridge.result(a, request, result, data)) return true;   // Game settings › Cheat files: a cheat file, a folder, an export (83 to 86)
        if (dev.peanuts.amiibo.AmiiboPage.result(a, request, result, data)) return true;   // the game page's amiibo Import (87, 88)
        if (request < REQ_KEYS || request > REQ_MOD_ZIP) return false;
        dev.peanuts.folders.FolderKind kind = dev.peanuts.folders.FolderKinds.ofRequest(request);   // a game, update and DLC or build folder (LibraryFolders)
        if (kind != null) {
            boolean none = result != Activity.RESULT_OK || data == null || data.getData() == null;
            String p = none ? null : Upstream.treeToPath(data.getData());
            boolean sheet = buildFolderFromSheet && kind == dev.peanuts.folders.FolderKinds.BUILDS;
            buildFolderFromSheet = false;
            LibraryFolders.picked(a, kind, p, none);
            if (sheet && p != null && GameSheet.current != null) GameSheet.current.chooseBuildFolder(dev.peanuts.folders.FolderList.clean(p));
            return true;
        }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri u = data.getData();
        if (request == REQ_KEYS) { copyKeys(a, u); return true; }
        if (request == REQ_FIRMWARE_ZIP) { pickedFirmwareZip(a, u); return true; }
        if (request == REQ_MOD_FOLDER) { dev.peanuts.settings.ModsSection.pickedFolder(a, u); return true; }
        if (request == REQ_MOD_ZIP) { dev.peanuts.settings.ModsSection.pickedZip(a, u, displayName(a, u)); return true; }
        if (request == REQ_SHEET_UPDATE || request == REQ_SHEET_DLC) {
            String p = Upstream.documentToPath(u);
            if (p == null || !new File(p).isFile()) { error(a, "Pick the file from the phone's storage (not Recent or a cloud provider), so the app can open it by its path."); return true; }
            if (GameSheet.current != null) GameSheet.current.picked(request, new File(p));
            return true;
        }
        String p = Upstream.treeToPath(u);
        if (p == null) { error(a, "That folder is not on the phone's storage (a cloud provider, say). Pick one on the phone or its SD card."); return true; }
        switch (request) {
            case REQ_FIRMWARE:
                PeanutsPrefs.firmwareDir(a, p);
                // The importer opens the folder by its path, which needs All files access. Without it the read failed here, the dialog below asked for the
                // access, and nothing read the firmware again once it was given: the row stayed at "not added" (GitHub issue #42). The read now waits for it
                if (Environment.isExternalStorageManager()) extractFonts(a, p, null);
                else { firmwareTried = null; fontsState = "Waiting for file access: allow All files access for the app, and the firmware is read."; }
                break;
            default: return true;
        }
        if (!Environment.isExternalStorageManager()) askAccess(a, "The app needs All files access to read " + p + ". Allow it, then come back.");
        refreshRows();
        Library.changed(a);
        return true;
    }

    /** "Allow file access": why, and the button to the app's All files access page */
    static void askAccess(Activity a, String why) {
        new AlertDialog.Builder(a).setTitle("Allow file access").setMessage(why).setPositiveButton("Open settings", (d, w) -> openAccessSettings(a)).setNegativeButton("Later", null).show();
    }

    /** As upstream's MainActivity.openAccessSettings: the app's own All files access page, else the list */
    static void openAccessSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /**
     * The picked document → files/keys/prod.keys (through the content resolver: no path needed). With the importer in the build, the file is checked beside the
     * keys in the app first and takes their place only when the importer accepts it: a wrong file (not a prod.keys) changes nothing, and the keys the app had
     * are loaded again (the importer's check of the wrong file replaced them in the importer). Before, the wrong file took their place and builds failed
     */
    private static void copyKeys(Activity a, Uri u) {
        File out = PeanutsPrefs.keysFile(a);
        out.getParentFile().mkdirs();
        File tmp = new File(out.getPath() + ".tmp");
        try (InputStream in = a.getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("the picker gave no file");
            byte[] buf = new byte[65536]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; if (total > 1 << 20) throw new IOException("that file is too large for a prod.keys"); }
        } catch (IOException e) { tmp.delete(); keysRefused(a, "keys: " + e.getMessage()); return; }
        ImporterPort imp = ImporterPort.get();
        if (!imp.available()) {
            if (!tmp.renameTo(out)) { tmp.delete(); error(a, "Could not place the keys in " + out); return; }
            keysLanded(a);
            return;
        }
        new Thread(() -> {
            int r = imp.keys(tmp.getPath());
            String err = imp.lastError();
            boolean placed = r == 0 && tmp.renameTo(out);
            if (!placed) {
                tmp.delete();
                ImporterPort.Holder.KEYS_OK = false;   // the importer holds the wrong file's keys now: the app's own again, when it has them
                ImporterPort.ensureKeys(a);
            } else ImporterPort.Holder.KEYS_OK = true;
            UI.post(() -> {
                if (!placed) { keysRefused(a, "keys refused by the importer: " + err); return; }
                Haptics.play(a.getWindow().getDecorView(), Haptics.Word.CLICK);
                Toast.makeText(a, "Keys loaded", Toast.LENGTH_LONG).show();
                keysLanded(a);
            });
        }, "peanuts-keys").start();
    }

    /** A picked file that is not a prod.keys the app can read: nothing changed, and a dialog says so */
    private static void keysRefused(Activity a, String why) {
        android.util.Log.w("peanuts", why);
        error(a, "That file is not a prod.keys the app can read. Your keys were not changed. Pick the prod.keys from your own console, the same version as your firmware.");
    }

    private static void keysLanded(Activity a) {
        // A firmware picked before the keys said "add your prod.keys first" and stayed so after the keys came, until it was picked a second time (seen on a
        // fresh install; GitHub issue #42 reads like it): with keys in the app it is read now
        if (PeanutsPrefs.hasKeys(a) && fontsState != null && fontsState.startsWith("Firmware not added: add your prod.keys")) { fontsState = null; firmwareTried = null; }
        firmwareAfterAccess(a);
        refreshRows();
        if (GameSheet.current != null) GameSheet.current.keysChanged();
        Library.changed(a);
    }

    /** The firmware's shared fonts → files/firmware/fonts (the importer; a note when it is not in the build). source is a folder or a firmware .zip
     * path (nximport_firmware_fonts takes either); deleteAfter is a temp copy (pickedFirmwareZip, when the picked zip was not on local storage) to
     * remove once the importer is done with it, whichever way the extraction went, or null when source is a real picked path that stays as it is. */
    private static void extractFonts(Activity a, String source, File deleteAfter) {
        ImporterPort imp = ImporterPort.get();
        if (!imp.available()) {
            fontsState = "The importer is not in this build: the firmware is read when it is.";
            refreshRows();
            if (deleteAfter != null) deleteAfter.delete();
            return;
        }
        fontsState = "Reading the firmware…";
        refreshRows();
        File out = PeanutsPrefs.fontsDir(a);
        out.mkdirs();
        new Thread(() -> {
            // The importer reads the firmware's files with the user's keys: loaded here when this process has not loaded them yet (GitHub issues #18, #22: "keys not
            // loaded" unless prod.keys had been picked in the same session), and a plain line when there are none
            if (!ImporterPort.ensureKeys(a)) {
                if (deleteAfter != null) deleteAfter.delete();
                UI.post(() -> { fontsState = "Firmware not added: add your prod.keys first (Keys, above). The firmware is read then; choose it again if it is not."; fontsLanded(a); });
                return;
            }
            int r = imp.firmwareFonts(source, out.getPath());
            String err = imp.lastError();
            if (r == 0) PeanutsPrefs.firmwareRead(a, FIRMWARE_READ);   // read whole: what this firmware lacks is not looked for again (addMissingFonts)
            if (deleteAfter != null) deleteAfter.delete();
            UI.post(() -> { fontsState = r == 0 ? null : "Firmware not added: " + err
                    + "\n" + KEYS_FIRMWARE_GUIDANCE; fontsLanded(a); });
        }, "peanuts-fonts").start();
    }

    private static boolean triedMoreFonts;

    /**
     * What the importer takes from a firmware, counted up when it learns to take more: 1 the two fonts the games need, 2 the Chinese and Korean fonts and MiiModel
     * too, 3 AvatarImage too (the console's profile pictures). Kept after each read that worked (PeanutsPrefs.firmwareRead)
     */
    static final int FIRMWARE_READ = 3;

    /**
     * A firmware added before the app read its Chinese and Korean fonts, MiiModel and AvatarImage too (cjk_sc.ttf and the others beside standard.ttf: a game in
     * those languages drew "?" without them): read once more from the folder or zip it came from, when that is still there. Once per app process; nothing is
     * asked, and a firmware that is gone leaves the two fonts as they are (choosing the firmware again adds the rest). A firmware this importer has read already
     * is not read again, even when it lacks one of them (a firmware without AvatarImage was read at every cold start)
     */
    public static void addMissingFonts(Activity a) {
        if (triedMoreFonts) return;
        triedMoreFonts = true;
        if (PeanutsPrefs.firmwareRead(a) >= FIRMWARE_READ) return;
        File d = PeanutsPrefs.fontsDir(a);
        String src = PeanutsPrefs.firmwareDir(a);
        boolean haveAll = new File(d, "cjk_sc.ttf").isFile() && new File(PeanutsPrefs.miiModelDir(a), "ShapeHigh.dat").isFile()   // PEANUTS: MiiModel too (the importer writes it beside the fonts)
                && dev.peanuts.users.FirmwarePictures.dir(a).isDirectory();   // the console's profile pictures too (Settings › Users), which the importer reads since then
        if (src == null || !new File(d, "standard.ttf").isFile() || haveAll || !src.startsWith("/") || !new File(src).exists()) return;
        extractFonts(a, src, null);
    }

    private static void fontsLanded(Activity a) {
        refreshRows();
        if (GameSheet.current != null) GameSheet.current.keysChanged();
        Library.changed(a);
    }

    /** The firmware .zip picker's result: a path on the phone's own storage needs no copy (the importer, native code, reads the zip straight from
     * disk by that path, same as the folder path); anything else (a cloud provider, an archive app's content provider) is copied into the app's
     * cache first — about 300 MB for a full firmware zip, so this runs off the UI thread — and deleted again once the importer is done with it. */
    private static void pickedFirmwareZip(Activity a, Uri u) {
        String local = Upstream.documentToPath(u);
        if (local != null && new File(local).isFile()) {
            PeanutsPrefs.firmwareDir(a, local);
            extractFonts(a, local, null);
            return;
        }
        fontsState = "Copying the firmware zip…";
        refreshRows();
        new Thread(() -> {
            File tmp = new File(a.getCacheDir(), "firmware_import.zip");
            try (InputStream in = a.getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("the picker gave no file");
                byte[] buf = new byte[1 << 20]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { o.write(buf, 0, n); total += n; if (total > 8L << 30) throw new IOException("that file is too large for a firmware zip"); }
            } catch (IOException e) {
                tmp.delete();
                UI.post(() -> { fontsState = "Firmware not added: could not copy the zip (" + e.getMessage() + ")"; refreshRows(); });
                return;
            }
            String name = displayName(a, u);
            UI.post(() -> { PeanutsPrefs.firmwareDir(a, name != null ? name : "a firmware zip"); extractFonts(a, tmp.getPath(), tmp); });
        }, "peanuts-firmware-copy").start();
    }

    private static String displayName(Activity a, Uri u) {
        try (android.database.Cursor c = a.getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { /* no name: the generic label is fine */ }
        return null;
    }

    /**
     * Settings › Library › Free up space: the original NSP/XCI of every game already built into a .nup (LibraryScanner.Title.originals), which the game no
     * longer needs to play or to rebuild. Deleted one by one after a confirmation that says what is lost: a later game update needs the original again (its
     * patch reads the base NCA's raw bytes, which the .nup does not keep). Only a READY title whose .nup is there and not empty, and only plain files. Before an
     * original goes, the game's icon is made sure of with the .nup (LibraryScanner.icon: inside it, else the PNG beside it), as the original may be its last source.
     */
    static void freeUpSpace(Activity a) {
        java.util.List<LibraryScanner.Title> ready = new java.util.ArrayList<>();
        for (LibraryScanner.Title t : Library.titlesNow()) {
            if (t.status != LibraryScanner.Status.READY || t.base == null || !t.base.isNup() || !t.base.file.isFile() || t.base.file.length() == 0) continue;
            for (LibraryScanner.Source s : t.originals) if (s.file.isFile()) { ready.add(t); break; }
        }
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(PUi.dp(a, 20), PUi.dp(a, 4), PUi.dp(a, 20), 0);
        AlertDialog[] d = new AlertDialog[1];
        if (ready.isEmpty()) PUi.add(box, PUi.small(a, "Nothing to free: no built game still has its original game files in your folders (or the library is still scanning)."), 0);
        else PUi.add(box, PUi.small(a, "These games are built, so they play and rebuild without their original dump. Keep the original if you will add a later game update: an update can only be applied to the original."), 8);
        for (LibraryScanner.Title t : ready) {
            for (LibraryScanner.Source s : t.originals) {
                if (!s.file.isFile()) continue;
                File f = s.file;
                String size = String.format(java.util.Locale.ROOT, "%.1f GB", f.length() / 1e9);
                box.addView(new PUi.Setting(a, "🗑️", t.name.isEmpty() ? t.titleId : t.name, v -> PUi.confirmDanger(a, "Delete the original dump?",
                        f.getName() + " (" + size + ") is deleted. " + (t.name.isEmpty() ? "The game" : t.name) + " keeps playing from its built copy, with its saves. "
                                + "To add a later game update you will need this file again.", "Delete", () -> new Thread(() -> {
                            LibraryScanner.icon(a, t);   // the icon with the .nup first, while its source is still there (a .nup made before issue #158 carries none)
                            boolean ok = f.isFile() && t.base.file.isFile() && t.base.file.length() > 0 && f.delete();   // the built copy checked again just before
                            UI.post(() -> {
                                if (a.isFinishing() || a.isDestroyed()) return;
                                Toast.makeText(a, ok ? "Deleted " + f.getName() + ", " + size + " freed" : "Could not delete " + f.getName(), Toast.LENGTH_LONG).show();
                                if (d[0] != null) d[0].dismiss();
                                Library.rescan(a);
                            });
                        }, "peanuts-free-space").start()))
                        .value(PUi.joined("Original " + size, f.getName())).view);
            }
        }
        d[0] = PUi.sheet(a, "Free up space", box).setPositiveButton("Done", null).show();
    }

    static void error(Activity a, String msg) { if (a.isFinishing() || a.isDestroyed()) return; Haptics.play(a.getWindow().getDecorView(), Haptics.Word.REJECT); new AlertDialog.Builder(a).setTitle("Library").setMessage(msg).setPositiveButton("OK", null).show(); }
}
