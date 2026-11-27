package dev.peanuts.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.build.BuildListener;
import dev.peanuts.build.BuildPipeline;
import dev.peanuts.build.BuildQueue;
import dev.peanuts.build.BuildRequest;
import dev.peanuts.build.GameEnv;
import dev.peanuts.build.UpdateFound;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Banana's Home is the library (SPEC.md, SPEC3.md): {@link #fill} is the one call MainActivity.refresh makes for its game box. It lays out the filter row (All ·
 * Building · Ready · Build needed · Not Supported Yet, Find), then one cover grid (Eden's shape in Banana's clothes: PUi.tile at cell size with the NACP icon,
 * the name under it, the state on the cover, four columns on a handheld), ordered Building → Ready by last played → Build needed A–Z → Not Supported Yet A–Z,
 * no sections. A tap plays a ready game (Banana's launch) or opens the game sheet; a long press (or Y) opens Banana's Manage sheet for a listed game. The Building
 * filter exists only while something runs and holds the full cards: the build card (Ondevice's shape: status, a sentence, the bar, Stop/Retry and a disabled Play)
 * and upstream's progress card (Ondevice); everywhere else the building game is a cover with a progress strip and a "Building · N %" chip that opens that filter.
 * The games come from {@link LibraryScanner} (every NSP/XCI/.nup/dump in the game folder) merged with upstream's own list: a scanned game that is ready is put
 * into upstream's list so Play and Manage are Banana's. With no game folder set, the first-run card (emptyCard's shape with three Setting rows) is Home's one card.
 */
public final class Library {
    private static final int ALL = 0, BUILDING = 1, READY = 2, BUILD = 3, NO_PROFILE = 4;
    private static final int[] FILTERS = {ALL, BUILDING, READY, BUILD, NO_PROFILE};
    private static final int REQ_NOTIFY = 71;   // the notification permission's request (nobody reads the answer: the next notification shows or does not)
    private static final String GOES_ON = " The build goes on if you leave the app.";

    /** The library of the activity that is showing (one MainActivity at a time; a recreated one gets a new instance that keeps the scan) */
    private static Library current;
    private static List<LibraryScanner.Title> lastTitles = new ArrayList<>();
    /** The newest scan's titles (Settings › Library › Free up space reads them) */
    static List<LibraryScanner.Title> titlesNow() { return lastTitles; }
    private static List<CompanionPort.Item> catalog = new ArrayList<>();
    private static boolean catalogFetched, everScanned;
    /** Whether this process scanned the folders once: before that the library has no game that is not in upstream's list (PackImportUi waits for it) */
    static boolean scannedOnce() { return everScanned; }
    private static final Map<String, Bitmap> icons = new HashMap<>();
    private static final Set<String> listed = new HashSet<>();   // launch paths handed to upstream's list already (Upstream.addToList), so a failing add is not retried every refresh
    /**
     * Owner request round 3, hide-only since round 4: upstream's Game list entries this build leaves out of {@link #rows()} — never
     * deleted, never any permission revoked — either because their volume is not mounted right now (removable storage unplugged: hidden at once), or
     * because {@link #staleGames} got positive, repeated proof the file itself is gone (hidden only after two scans at least 60 s apart agree, and
     * never for a SAF-picked .nup or without All files access/an app-storage path — see {@link #staleGames}'s doc). Recomputed from scratch every
     * scan() (cleared, then repopulated), so a returned volume's or a reappeared file's games come back on the next rescan automatically. The new
     * "Hidden: N missing games" toggle in the filter row lets the player show these again and use upstream's own "Game file gone → Remove" dialog on
     * them, by their own choice, instead of this code ever deleting anything on its own
     */
    private static final Set<String> hiddenUnmounted = Collections.synchronizedSet(new HashSet<>());
    /** gameDir → the wall-clock time {@link #staleGames} first got positive proof it was gone (the review's "two scans at least 60 s apart"
     *  gate); removed the moment any check stops confirming "gone", or the entry leaves upstream's list some other way */
    private static final Map<String, Long> missingSince = new java.util.concurrent.ConcurrentHashMap<>();
    private static Build build;   // the running (or stopped) build, kept across activity recreation (the service runs on)
    private static final Handler UI = new Handler(Looper.getMainLooper());
    /**
     * Round 5: the stale-hide gate in {@link #staleGames} needs two scans at least 60 s apart, but a full {@link #scan()} only ever runs once per
     * process ({@code everScanned}) unless Settings › Library › Rescan or a folder change triggers another. Upstream's own {@code onResume} only
     * calls {@code refresh()} (which reaches {@link #fill}) when its Game list's *size* changed, so a card whose file silently disappeared without
     * the list shrinking (nothing removed it) never gets a fresh look on resume at all -- confirmed on the Thor, where a stale Octopath card never
     * hid across repeated background/resume cycles. Since this file cannot change upstream's onResume, this timer instead re-runs the cheap stale
     * check on a plain interval, independent of any lifecycle callback: it keeps ticking as long as the process is alive, so by the time a player
     * backgrounds and resumes the app 60+ seconds later, the two scans the gate needs have already happened on their own
     */
    private static final long STALE_TICK_MS = 30_000L;
    private static boolean staleTimerStarted;
    private static final Runnable staleTick = new Runnable() {
        @Override public void run() {
            Library l = current;
            if (l != null && l.box != null && !l.scanning && PeanutsPrefs.gameDir(l.a) != null) l.staleCheck();
            if (l != null) BuildPipeline.followPacker(l.a);   // a paused build queue whose game's job was resumed on upstream's Prepare card goes on (issue #348)
            if (l != null) dev.peanuts.build.ReuseCheck.startAny(l.a);   // a build's kept shaders checked in full while the phone charges (issue #120); it waits while a game runs
            UI.postDelayed(this, STALE_TICK_MS);
        }
    };

    /** A build as the card shows it */
    static final class Build {
        final int id; final String titleId, name;
        String step = "import", detail = "Starting"; int percent; boolean failed; String error = "", code = "";
        boolean reimport;   // Import again: a stop in its import step is not resumed (Resume would build from the old .nup), the sheet is opened instead
        Build(int id, String titleId, String name) { this.id = id; this.titleId = titleId; this.name = name; }
    }

    private final Activity a;
    private LinearLayout box;
    private List<?> games = Collections.emptyList();
    private View ondeviceCard;
    private Predicate<Object> shows = g -> false;
    private Function<Object, View> card = g -> null;
    private Consumer<Object> manage = g -> {};
    private Runnable refresh = () -> {};
    private List<LibraryScanner.Title> titles = lastTitles;
    private boolean scanning;
    private boolean staleChecking;   // one in flight at a time; staleTick skips a tick rather than piling up background threads
    private int filter = ALL;
    private String find = "";
    private boolean showHidden;   // the "Hidden: N missing games · Show/Hide" toggle (found in review): off by default, so a hidden card needs an explicit tap to reveal
    private final Map<String, TextView> tiles = new HashMap<>();   // the tiles of the covers showing, by title ID (icons land while they show)
    private final Map<String, Boolean> companions = new HashMap<>();   // title ID → a companion is installed (read once per pass; cleared by a scan and a build's end)
    // the build card's live parts (null when it is not on screen)
    private TextView buildStatus, buildBody, buildPct;
    private Button buildGoOn;   // the build card's Go on anyway, shown while the packer waits in a heat pause it lifts (BuildPipeline.goOnOffered, GitHub #803)
    private ProgressBar buildBar;
    // the building game's cover marks (null when the Building filter shows the card instead)
    private TextView coverChip;
    private ProgressBar coverStrip;
    private View firstCover;   // the grid's first cover of this render (focused after a shoulder-button filter change)
    private boolean focusGrid;
    // ---- What a rebuild keeps (the grid is torn down and built again by every render that changes something) ----
    private String shown;   // the signature of what the box shows now (see render); null: build it again whatever it would be
    private final Map<String, View> keyed = new HashMap<>();   // the library's own focusable views by a key that outlives a rebuild ("cover:<title ID>", "chip:<filter>", "find", "hidden")
    private String lostKey, restoreKey;   // the view that lost the focus last, and the one that lost it by being removed (upstream empties the box before fill)
    private long lostAt;
    private boolean restoring;   // the focus is being put back by code: no focus animation, the scale is set
    private boolean animateNext;   // the next rebuild was caused by the user (a filter, Find): its rows enter (Motion.enterRows)
    private int lastCells = -1;   // how many cells the last rebuild showed (-1 before the first): the grid's first appearance after an empty one enters too
    private boolean hadNote;   // the last rebuild showed the scan's or the empty library's line (it fades in only when it is new)
    private String popReady;   // the title whose build just ended well: its Ready check pops in at the rebuild that shows it
    private long popReadyAt;
    private static boolean watching;   // the activity lifecycle callbacks are registered (once per process)
    private static boolean queueWatched;   // BuildPipeline.watch has the library's follower (once per process)
    /** The build queue changed (BuildPipeline.watch, issue #348): the game it starts gets the build card, and Home is drawn again */
    private static final Runnable queueChanged = () -> { Library l = current; if (l != null) l.followQueue(); };
    private static String played;   // the title Play was pressed on: Home's next resume puts the focus on its cover
    private static long playedAt;

    private Library(Activity a) { this.a = a; }

    /**
     * The build card's listener for the build of this id: what it hears goes to the card only while the card is that build's (the build queue runs one game
     * after the other, and the end of one can come after the card took the next, issue #348)
     */
    private BuildListener listener(int id) { return new BuildListener() {
        @Override public void progress(String step, int percent, String detail) {
            Build b = build;
            if (b == null || b.id != id) return;
            boolean stepChanged = !step.equals(b.step);
            b.step = step; b.percent = percent; b.detail = detail == null ? "" : detail;
            if (stepChanged || (buildStatus == null && coverChip == null) || box == null) render(); else updateBuildCard(b, false);
            pushBuild();
        }
        @Override public void done(boolean ok, String message, String code) {
            Build b = build;
            if (b == null || b.id != id) return;
            // The end is felt and seen only while one of the app's screens is in front; away from the app the service's notification tells it (BuildPipeline.endChannel)
            if (ok) {
                Toast.makeText(a, b.name + " is ready to play.", Toast.LENGTH_LONG).show();
                if (inFront) { Haptics.play(anchor(), Haptics.Word.DONE); popReady = b.titleId; popReadyAt = android.os.SystemClock.uptimeMillis(); }
                TextView tile = tiles.get(b.titleId);
                if (inFront && Motion.on() && tile != null && coverChip != null && coverChip.isAttachedToWindow()) {
                    // the building cover is on screen: it says Ready and pops, and the rescan that moves it among the Ready games waits until that was seen
                    coverChip.setText("Ready");
                    coverChip.setTextColor(PUi.col(a, R.color.bn_leaf));
                    if (coverStrip != null && !coverStrip.isIndeterminate()) Motion.progress(coverStrip, 100, false);
                    Motion.pop(tile);
                    UI.postDelayed(() -> { if (build == b) build = null; companions.clear(); if (current == Library.this) scan(); }, 400);
                } else {
                    build = null; companions.clear();
                    scan();
                }
            } else {
                b.failed = true; b.error = message == null ? "" : message; b.code = code == null ? "" : code;
                render();
                if (b.reimport && !scanning) scan();   // an import again that stopped may have left another base than the one the titles name (the old .nup deleted first): read again
                // a build the user stopped is not a refusal: the press on Stop was its feedback
                if (inFront && !BuildListener.CANCELLED.equals(b.code)) { Haptics.play(anchor(), Haptics.Word.REJECT); if (coverChip != null) Motion.shake(coverChip); }
            }
            pushBuild();
        }
    }; }

    /** A view of the activity's window to play a haptic through when no pressed view is at hand (a build's end) */
    private View anchor() { return box != null && box.isAttachedToWindow() ? box : a.getWindow().getDecorView(); }

    // ---- The app in front, the settings read at a resume, the focus after a game ----

    private static boolean inFront;
    private static Activity top;   // the resumed activity of this process, if any

    /**
     * Once per process: the app's activity lifecycle (this process only; a game runs in its own). While one of the app's screens is resumed a build's end is
     * felt here, else the service's notification alerts (BuildPipeline.front). A resume reads the haptic and motion settings again, and Home's first resume
     * after Play puts the focus on the played game's cover, silently, with the focus scale once as the landing cue.
     */
    private static void watch(Activity a) {
        if (watching) return;
        watching = true;
        if (a.hasWindowFocus()) { top = a; inFront = true; BuildPipeline.front(true); }   // registered by a resumed activity (else its resume follows and says so)
        a.getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity x) {
                top = x;
                front(true);
                Haptics.refresh(x); Motion.refresh(x);
                Library l = current;
                // the bottom screen card's play time after a game, and the toast of a session its Stop ended (the game's process writes both as it goes, maybe just after this)
                if (l != null && l.a == x) { l.playTimesAgain(); UI.postDelayed(() -> { if (current == l && top == x) { l.playTimesAgain(); l.sessionToast(); } }, 2500); }
                String id = played;
                if (l == null || l.a != x || id == null) return;
                played = null;
                View cover = l.keyed.get("cover:" + id);
                TextView tile = l.tiles.get(id);
                if (cover == null || tile == null || cover.isInTouchMode()) return;
                Haptics.quiet(200);
                l.restoring = true;
                boolean has = cover.isFocused() || cover.requestFocus();
                l.restoring = false;
                if (has) Motion.land(tile);
            }
            @Override public void onActivityPaused(Activity x) {
                if (top == x) { top = null; front(false); }   // from one of the app's screens to another, the next one's resume says in front again
                if (played != null && android.os.SystemClock.uptimeMillis() - playedAt > 3000) played = null;   // Play opened a dialog and no game: nothing to come back from
            }
            private void front(boolean on) { inFront = on; BuildPipeline.front(on); }
            @Override public void onActivityCreated(Activity x, android.os.Bundle b) {}
            @Override public void onActivityStarted(Activity x) {}
            @Override public void onActivityStopped(Activity x) {}
            @Override public void onActivitySaveInstanceState(Activity x, android.os.Bundle b) {}
            @Override public void onActivityDestroyed(Activity x) {}
        });
    }

    // ---- Screens off (Home's header, left of the Settings gear) ----

    private static View screensOffB;

    /** An icon above its caption in Home's header. The optional key glyph is shown beside the caption. */
    public static View headerAction(Activity a, int iconRes, String label, String description, Drawable key, View.OnClickListener action) {
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(PUi.iconButton(a, iconRes, description, PUi.Kind.TONAL, action));

        TextView caption = PUi.text(a, label, 10, R.color.bn_accent, 700);
        caption.setSingleLine(true);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, PUi.dp(a, 2), 0, 0);
        caption.setMinHeight(PUi.dp(a, 16));
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (key != null) {
            caption.setCompoundDrawablesRelativeWithIntrinsicBounds(key, null, null, null);
            caption.setCompoundDrawablePadding(PUi.dp(a, 4));
        }
        column.addView(caption, PUi.wrap());
        return column;
    }

    /**
     * The "Screens off" button of Home's header (MainActivity.buildHome puts it left of the Settings gear): shown only while a build runs or waits. A long build
     * with a card that hardly changes marks an OLED panel, so it turns every screen black until a tap (ScreensOff); render() shows and hides it
     */
    public static View screensOffButton(Activity a) {
        screensOffB = headerAction(a, R.drawable.ic_screens_off, "Screens off", "Screens off while a build runs", null,
                Haptics.on(Haptics.Word.CLICK, v -> ScreensOff.show(a)));
        screensOffB.setVisibility(View.GONE);
        return screensOffB;
    }

    /**
     * MainActivity.buildHome (PEANUTS hook): Home's header with the build line under the title as plain text. Upstream makes it selectable, which makes it a
     * focus stop: a controller's first focus landed on it (the first focusable view of the page) and drew the focus frame round the whole line. The first
     * cover takes the first focus instead (cover, setFocusedByDefault); the tag is still selectable in Settings › About.
     */
    public static LinearLayout homeHeader(LinearLayout header) {
        plainText(header);
        return header;
    }

    private static void plainText(View v) {
        if (v instanceof TextView && ((TextView) v).isTextSelectable()) { ((TextView) v).setTextIsSelectable(false); v.setFocusable(false); }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) plainText(((ViewGroup) v).getChildAt(i));
    }

    // ---- The hook ----

    /**
     * MainActivity.refresh's game box: games is upstream's list, ondeviceCard its progress card when it shows (Ondevice.cardView), shows says whether that card is a
     * game's (Ondevice.shows), card makes a game's own card (gameCard; unused since the covers, kept for the hook's shape), manage opens its Manage sheet, refresh is refresh() itself
     */
    @SuppressWarnings("unchecked")
    public static <G> void fill(Activity a, LinearLayout box, List<G> games, View ondeviceCard, Predicate<G> shows, Function<G, View> card, Consumer<G> manage, Runnable refresh) {
        box.post(() -> LayoutChoice.offer(a));   // how the in-game menus look, asked at the app's start until it is picked (twice at most)
        if (current == null || current.a != a) current = new Library(a);
        Library l = current;
        l.box = box; l.games = new ArrayList<>(games); l.ondeviceCard = ondeviceCard; gen++;
        Consumer<Object> m = (Consumer<Object>) manage;
        l.shows = (Predicate<Object>) shows; l.card = (Function<Object, View>) card; l.refresh = refresh;
        l.manage = g -> { BottomScreen.page(a); m.accept(g); };   // bottom screen: logo
        if (build != null) {
            if (BuildPipeline.running() == build.id) BuildPipeline.attach(build.id, l.listener(build.id));   // an activity made anew while the service builds
            else if (!build.failed) build = null;   // the service ended while no listener heard it
        }
        // The build queue (issue #348): read once a process, so what a killed process left shows at once and goes on; the game it builds has the card
        if (!queueWatched) { queueWatched = true; BuildPipeline.watch(queueChanged); }
        BuildPipeline.queue(a);
        BuildPipeline.resume(a);
        BuildPipeline.followPacker(a);
        l.takeRunning();
        Haptics.refresh(a); Motion.refresh(a);
        LibrarySettings.addMissingFonts(a);   // a firmware added before the Chinese and Korean fonts were read
        watch(a);
        l.render();
        preloadIcons(a);
        if (!everScanned && !l.scanning && PeanutsPrefs.gameDir(a) != null) l.scan();
        if (!staleTimerStarted) { staleTimerStarted = true; UI.postDelayed(staleTick, STALE_TICK_MS); }
    }

    /** Settings › Library › Rescan, a folder change: the folders are read again */
    static void rescan(Activity a) {
        if (current == null || current.a != a) return;
        CompanionPort.resetNames(); catalogFetched = false;
        current.scan();
    }

    /** Something the library shows changed (a folder set, keys added): drawn again, and scanned when that is new */
    static void changed(Activity a) {
        if (current == null || current.a != a) return;
        current.render();
        if (!current.scanning) current.scan();
    }

    /** The game sheet of a title (MainActivity's Manage › Build again, a cover's tap): the scanned title, else one made from upstream's list entry */
    static void openSheet(Activity a, String titleId) {
        if (current == null || current.a != a) return;
        current.sheet(current.titleFor(titleId));
    }

    // ---- The covers kept from earlier runs ----

    private static boolean iconsPreloaded;
    /**
     * Once a process: every cover already extracted (files/icons/&lt;title ID&gt;.png) is decoded on a thread of its own and put on its tile, before and beside the scan.
     * The covers used to be read only at the end of the scan thread, behind the folder walk, the companion catalog's network fetch and each game's build id check,
     * so a cold start showed the letter tiles for as long as all of that took.
     */
    private static void preloadIcons(Activity a) {
        if (iconsPreloaded) return;
        iconsPreloaded = true;
        File dir = PeanutsPrefs.iconsDir(a);
        new Thread(() -> {
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                String n = f.getName();
                if (!n.endsWith(".png") || f.length() == 0) continue;
                String id = n.substring(0, n.length() - 4);
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b == null) continue;
                UI.post(() -> {
                    if (icons.containsKey(id)) return;
                    icons.put(id, b);
                    BottomScreen.icon(id, b);
                    Library l = current; TextView tile = l == null ? null : l.tiles.get(id);
                    if (tile != null) Motion.cover(tile, b);   // a tile on screen crossfades to its art; a tile built from now on has it at once
                });
            }
        }, "peanuts-icons").start();
    }

    // ---- The scan ----

    private void scan() {
        if (scanning) return;
        scanning = true;
        render();
        new Thread(() -> { try {
            List<LibraryScanner.Title> t = new LibraryScanner(a).scan();
            // a scanned game that is ready and not in upstream's list goes into it: its card is then Banana's own
            List<Upstream.Entry> list = Upstream.games(a);
            staleGames(list, a);
            for (LibraryScanner.Title x : t) {
                if (x.status != LibraryScanner.Status.READY || x.launch == null || x.packDir == null || listedIn(list, x)) continue;
                if (!listed.add(x.launch.getPath())) continue;
                Upstream.addToList(a, x.launch, x.packDir, x.titleId, x.name);
            }
            // the games show as soon as the folders are read; the companion catalog (network: a slow or failing GitHub held the whole library empty for a minute)
            // follows in its own post, and only its badges wait for it
            UI.post(() -> {
                lastTitles = t; everScanned = true;
                dev.peanuts.ctl.Ctl.libraryChanged();
                if (current != this) return;
                titles = t; gen++; scanning = false; companions.clear();
                if (box != null && box.isAttachedToWindow()) refresh.run(); else render();   // upstream's list again too (Prepare and the add above put games into it)
                PackImportUi.scanned(a);   // a pack picked while Android made the process anew (GitHub issue #44) goes on now that the game is known
            });
            for (LibraryScanner.Title x : t) {   // the icons not kept yet (preloadIcons has the others), before anything slow: the catalog and the build ids below
                if (icons.containsKey(x.titleId)) { LibraryScanner.keep(a, x); continue; }   // a .nup without an icon of its own gets this one beside it (issue #158)
                File f = LibraryScanner.icon(a, x);
                Bitmap b = f == null ? null : BitmapFactory.decodeFile(f.getPath());
                if (b == null) continue;
                UI.post(() -> { icons.put(x.titleId, b); BottomScreen.icon(x.titleId, b); TextView tile = tiles.get(x.titleId); if (tile != null) Motion.cover(tile, b); });
            }
            if (!catalogFetched) {
                List<CompanionPort.Item> c = CompanionPort.fetch();
                UI.post(() -> {
                    catalog = c; catalogFetched = true;
                    if (current != this) return;
                    companions.clear();
                    if (box != null && box.isAttachedToWindow()) refresh.run(); else render();
                });
            }
            for (LibraryScanner.Title x : t)   // the fonts and build id every ready game's env needs (GameEnv)
                if (x.status == LibraryScanner.Status.READY && x.launch != null) { GameEnv.ensureFonts(a, x.titleId); GameEnv.ensureBuildId(a, x.titleId, x.launch); }
        } catch (RuntimeException e) {   // a failed scan must not leave the library "scanning" for good
            UI.post(() -> { scanning = false; if (current == this) { render(); PackImportUi.scanned(a); } });   // a waiting pick: its checks say what is wrong
        } }, "peanuts-scan").start();
    }

    /**
     * The lightweight half of {@link #scan()}, run on its own by {@link #staleTick} (round 5): only reads upstream's own Game list
     * ({@link Upstream#games}, no filesystem walk of the whole library) and runs {@link #staleGames} on it, which itself stats only a handful of
     * paths per entry whose file does not plainly {@code exists()}. Cheap enough to run on a timer; a full {@link #scan()} already does this too, so
     * this is skipped while one is running rather than duplicating the work
     */
    private void staleCheck() {
        if (scanning || staleChecking) return;
        staleChecking = true;
        new Thread(() -> {
            try {
                List<Upstream.Entry> list = Upstream.games(a);
                staleGames(list, a);
            } catch (RuntimeException e) { /* the flag below is reset either way */ }
            UI.post(() -> {
                staleChecking = false;
                if (current == this && box != null && box.isAttachedToWindow()) render();
            });
        }, "peanuts-stale").start();
    }

    private static boolean listedIn(List<Upstream.Entry> list, LibraryScanner.Title t) {
        for (Upstream.Entry e : list) if (samePath(e.gameDir, t.launch.getPath())) return true;
        return false;
    }

    /**
     * Now (owner's choice, 2026-11-13): an entry confirmed gone is removed (removeEntry), behind the gates below; an unmounted volume's entries are only hidden.
     * History, round 4 (a blocker found in review of round 3's version): HIDE-ONLY, never remove. Round 3 deleted the upstream list entry itself
     * whenever {@code !File.exists()} on a mounted volume — but {@code File.exists()} is not a reliable "the file is gone" test in this app:
     * <ul>
     * <li>every SAF-picked .nup's {@code gameDir} is a stand-in path whose file is never created ({@code PickedNup}/{@code PickedPath}: only the
     *     stand-in's parent folder exists), so {@code exists()} on it is false by design, always — deleting the record there meant losing the
     *     library entry AND, had {@code PickedNup.forget} still run, the user's picked-file permission, on the very first scan after picking it;</li>
     * <li>without All files access, {@code exists()} is false for everything outside the app's own storage, which would have wiped out the whole
     *     library the moment the permission is off;</li>
     * <li>a transient FUSE/MediaProvider hiccup can make {@code exists()} false for a moment even though the volume is mounted and the file is fine.</li>
     * </ul>
     * None of this is fixed by checking the volume is mounted — in every case above, it is. So this method now only ever **hides** a card (never
     * touches upstream's Game list, never calls {@code Game.save} or {@code PickedNup.forget}), and only when every one of these holds:
     * <ol>
     * <li>the entry is <b>not</b> a {@code PickedNup} stand-in ({@code PickedNup.is}, reflection) — those are never this logic's business; upstream's
     *     own {@code gone()}/{@code PickedNup.problem} already covers them correctly, through its own dialog;</li>
     * <li>this app has All files access ({@code Environment.isExternalStorageManager()}) or the path is inside app storage ({@code CopyPlan.inApp}) —
     *     mirrors upstream's own {@code readable} gate in {@code gone()}: without one of these, "cannot tell" means "not gone", never "gone";</li>
     * <li><b>positive proof of absence</b>: the parent directory exists and {@code listFiles()} returns a non-null listing that does not contain the
     *     file's name — not merely {@code !exists()}, which a permission problem or a provider hiccup can also produce;</li>
     * <li>that proof held on <b>two scans at least 60 seconds apart</b> ({@code missingSince}, kept in memory, keyed by {@code gameDir}; the gate
     *     resets the moment any check above stops confirming "gone").</li>
     * </ol>
     * A card hidden this way is never deleted and never loses anything: the Game list entry stays exactly as it was, and the new "Hidden: N missing
     * games" toggle (the filter row) lets the player show it again and use upstream's own "Game file gone → Remove" dialog themselves, on their own
     * say — satisfying the original owner ask ("let the user see and remove them") without this code ever guessing on their behalf.
     * <p>
     * The unmounted-volume branch is unchanged from round 3 (already correct per review), aside from fixing a path-prefix bug in
     * {@link #volumeMounted}: it hides immediately (no 60-second gate — a volume being absent is itself the positive proof) and never removes.
     */
    private static void staleGames(List<Upstream.Entry> list, Activity act) {
        if (act == null) return;   // nothing to ask the StorageManager/reflection with right now: leave every record exactly as it is, try again next scan
        Set<String> hiddenNow = new HashSet<>();
        Set<String> seenThisPass = new HashSet<>();
        int candidates = 0;
        for (Upstream.Entry e : list) {
            String gameDir = e.gameDir;
            if (gameDir == null || gameDir.isEmpty()) continue;
            seenThisPass.add(gameDir);
            if (new File(gameDir).exists()) { missingSince.remove(gameDir); continue; }   // plainly there: clear any stale bookkeeping, nothing to hide
            candidates++;
            Boolean mounted = volumeMounted(act, gameDir);
            if (mounted == null) continue;   // cannot tell: leave the record and its card exactly as they are, try again next scan
            if (!mounted) { hiddenNow.add(gameDir); continue; }   // its volume is not plugged in right now: hide at once, no 60-second gate needed
            // the volume is mounted, so !exists() is actually worth asking about -- but only with the gates above, never on !exists() alone
            if (pickedNupStandIn(act, gameDir)) { missingSince.remove(gameDir); continue; }
            if (!readableOutsideApp(act, gameDir)) { missingSince.remove(gameDir); continue; }
            if (!confirmedAbsent(act, gameDir)) { missingSince.remove(gameDir); continue; }
            long now = System.currentTimeMillis();
            Long since = missingSince.putIfAbsent(gameDir, now);
            if (since != null && now - since >= 60_000L) { missingSince.remove(gameDir); removeEntry(act, gameDir); }   // confirmed absent on two scans at least 60 s apart: its list entry goes (owner's choice, 2026-11-13)
        }
        missingSince.keySet().retainAll(seenThisPass);   // an entry gone from upstream's list some other way (the user removed it themselves, etc.) drops its bookkeeping too
        hiddenUnmounted.clear();
        hiddenUnmounted.addAll(hiddenNow);
        Log.i("peanuts", "[library] stale check: " + candidates + " candidates, " + hiddenNow.size() + " hidden (volume not mounted)");
    }

    /**
     * Removes one entry from upstream's Game list, as its own "Game file gone > Remove" does for a game that is not a picked .nup: only the list entry
     * goes; the dump, its pack and the saves stay (Add game brings it back). On the UI thread, where upstream itself loads and saves the list, so the two
     * never write it at once. Reached by reflection (Game is package-private upstream). Any failure leaves the entry as it is.
     */
    private static void removeEntry(Activity act, String gameDir) {
        act.runOnUiThread(() -> {
            try {
                Class<?> g = Class.forName("dev.nxnative.android.Game");
                java.lang.reflect.Method load = g.getDeclaredMethod("load", Context.class), save = g.getDeclaredMethod("save", Context.class, List.class);
                java.lang.reflect.Field dir = g.getDeclaredField("gameDir");
                load.setAccessible(true); save.setAccessible(true); dir.setAccessible(true);
                synchronized (g) {   // Game's own lock (its put/load/save take Game.class): a scan thread adding a game between our load and save would be lost
                    @SuppressWarnings("unchecked") List<Object> games = (List<Object>) load.invoke(null, act);
                    boolean gone = games.removeIf(x -> { try { return gameDir.equals(dir.get(x)); } catch (IllegalAccessException e) { return false; } });
                    if (!gone) return;
                    save.invoke(null, act, games);
                }
                Log.i("peanuts", "[library] removed the entry of a missing game: " + gameDir);
                rescan(act);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w("peanuts", "[library] could not remove the entry of " + gameDir + ": " + e);
            }
        });
    }

    /** Whether path is on a volume Android currently lists (internal storage, or a mounted SD/USB); null when this cannot be told (no StorageManager, or
     *  an exception reading it) — never a guess in either direction: null leaves the record exactly as it is (neither removed nor hidden) until a later
     *  scan can tell for sure. Matches the volume's root exactly or with a path separator after it (a prefix-bug fix from review: without the
     *  separator, "/storage/emulated/0" would also match "/storage/emulated/01…") */
    private static Boolean volumeMounted(Context ctx, String path) {
        try {
            StorageManager sm = ctx.getSystemService(StorageManager.class);
            if (sm == null) return null;
            String target = new File(path).getCanonicalPath();
            for (StorageVolume v : sm.getStorageVolumes()) {
                File dir = v.getDirectory();
                if (dir == null) continue;
                String root = dir.getCanonicalPath();
                if (target.equals(root) || target.startsWith(root + File.separator)) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /** {@code PickedNup.is(Context, String)} by reflection: true for a .nup picked through the system file picker (its gameDir is a stand-in path
     *  that is never itself created, PickedNup.java:157 — exists() on it is false always, by design, so this logic must never act on it). An
     *  exception defaults to true: "might be a stand-in" is the safe guess, since treating one as a stand-in only ever means leaving it alone */
    private static boolean pickedNupStandIn(Context ctx, String gameDir) {
        try {
            Class<?> k = Class.forName("dev.nxnative.android.PickedNup");
            Method is = k.getDeclaredMethod("is", Context.class, String.class); is.setAccessible(true);
            return Boolean.TRUE.equals(is.invoke(null, ctx, gameDir));
        } catch (Throwable t) { return true; }
    }

    /** Mirrors upstream's own `readable` gate in {@code MainActivity.gone} (found in review): All files access, or the path is inside this
     *  app's own storage ({@code CopyPlan.inApp}, reflection). Without either, upstream itself treats "cannot tell" as "not gone" — never as "gone" —
     *  and so does this: an exception here also defaults to false, the same "not gone" reading */
    private static boolean readableOutsideApp(Context ctx, String gameDir) {
        try {
            if (android.os.Environment.isExternalStorageManager()) return true;
            Class<?> appStorage = Class.forName("dev.nxnative.android.AppStorage");
            Method root = appStorage.getDeclaredMethod("root", Context.class); root.setAccessible(true);
            File appRoot = (File) root.invoke(null, ctx);
            Class<?> copyPlan = Class.forName("dev.nxnative.android.CopyPlan");
            Method inApp = copyPlan.getDeclaredMethod("inApp", File.class, String.class); inApp.setAccessible(true);
            return Boolean.TRUE.equals(inApp.invoke(null, appRoot, gameDir));
        } catch (Throwable t) { return false; }
    }

    /**
     * Positive proof the file is gone, not merely {@code !exists()}. Round 4 required the immediate parent to exist, which misses the common case
     * (confirmed on the Thor, round 5): the whole game folder is deleted along with the file, e.g. ".../ROMs/switch/OCTOPATH TRAVELER 0/OCTOPATH
     * TRAVELER 0.nup" where "OCTOPATH TRAVELER 0/" is gone too, not just the .nup — the immediate parent can never be listed, so the old check always
     * returned "no proof" and the card never hid. This instead walks up to the nearest existing ancestor and requires two extra things of it:
     * <ol>
     * <li>it must be listable ({@code list()} non-null — a permission problem or a provider hiccup gives null, not an empty array, so that is still
     *     "no proof"), and must not contain the next path component down (the first one that is missing);</li>
     * <li>it must still be on the same mounted volume as the original path (re-checked at the ancestor's own level, since walking up far enough
     *     could in principle cross onto a different volume on some setups), and it must <b>not be the volume's root itself</b> — an unmounted or
     *     not-yet-mounted removable volume can present its mount point as an existing, empty, listable directory, which would otherwise "confirm"
     *     every game on that volume as gone the moment it is unplugged, before {@link #volumeMounted} even gets a chance to say so.</li>
     * </ol>
     */
    private static boolean confirmedAbsent(Context ctx, String gameDir) {
        File missing = new File(gameDir);
        File ancestor = missing.getParentFile();
        File child = missing;
        while (ancestor != null && !ancestor.isDirectory()) {
            child = ancestor;
            ancestor = ancestor.getParentFile();
        }
        if (ancestor == null) return false;   // walked all the way to the root with nothing existing: no proof
        String[] names = ancestor.list();
        if (names == null) return false;
        String nextComponent = child.getName();
        for (String n : names) if (n.equals(nextComponent)) return false;   // the next step down IS there after all: not proof
        Boolean mounted = volumeMounted(ctx, ancestor.getPath());
        if (mounted == null || !mounted) return false;
        if (isVolumeRoot(ctx, ancestor)) return false;
        return true;
    }

    /** Whether dir is exactly a currently-listed volume's own root (not a folder inside one) — an unmounted/not-yet-mounted removable volume can
     *  present its mount point this way, so {@link #confirmedAbsent} must never treat that bare root as proof of anything missing under it. An
     *  exception defaults to true ("assume it is the root"), the reading that only ever costs a missed hide, never a wrong one */
    private static boolean isVolumeRoot(Context ctx, File dir) {
        try {
            StorageManager sm = ctx.getSystemService(StorageManager.class);
            if (sm == null) return true;
            String path = dir.getCanonicalPath();
            for (StorageVolume v : sm.getStorageVolumes()) {
                File d = v.getDirectory();
                if (d != null && path.equals(d.getCanonicalPath())) return true;
            }
            return false;
        } catch (RuntimeException | java.io.IOException e) {
            return true;
        }
    }

    private static final Map<String, String> canon = new HashMap<>();   // path → canonical path (a render compares every listed game with every scanned one)

    private static String canonical(String p) {
        String c = canon.get(p);
        if (c == null) {
            try { c = new File(p).getCanonicalPath(); } catch (java.io.IOException | RuntimeException e) { c = p; }
            canon.put(p, c);
        }
        return c;
    }

    private static boolean samePath(String x, String y) { return x.equals(y) || canonical(x).equals(canonical(y)); }

    /** The scanned title of an ID, else one made from upstream's list entry (a game added with Add game, outside the game folder) */
    private LibraryScanner.Title titleFor(String titleId) {
        for (LibraryScanner.Title t : titles) if (t.titleId.equalsIgnoreCase(titleId)) return t;
        for (Object g : games) {
            Upstream.Entry e = Upstream.entryOf(g);
            if (e == null || !e.titleId.equalsIgnoreCase(titleId)) continue;
            LibraryScanner.Title t = new LibraryScanner.Title(e.titleId);
            File game = new File(e.gameDir);
            t.name = e.name.isEmpty() ? e.titleId : e.name;
            t.base = new LibraryScanner.Source(game, LibraryScanner.BASE, e.titleId, e.titleId, 0, "", "");
            t.home = game.isDirectory() ? game : game.getParentFile();
            t.launch = game;
            t.packDir = e.packDir.isEmpty() ? null : new File(e.packDir);
            t.status = Upstream.hasProfile(e.titleId) ? LibraryScanner.Status.READY : LibraryScanner.Status.NO_PROFILE;
            return t;
        }
        return null;
    }

    // ---- The control interface (dev.peanuts.ctl, through CtlPort): Home's view state read and set, the build without the sheet ----

    static final String[] FILTER_NAMES = {"all", "building", "ready", "build", "not_in_app"};   // by ALL, BUILDING, READY, BUILD, NO_PROFILE

    /** The library of this activity, null when its Home was never drawn */
    static Library of(Activity a) { return current != null && current.a == a ? current : null; }
    static Library now() { return current; }

    /** The activity this library draws in */
    Activity activity() { return a; }
    static Build buildNow() { return build; }
    static List<CompanionPort.Item> catalogNow() { return catalog; }
    String filterName() { return FILTER_NAMES[filter]; }
    String findText() { return find; }
    boolean hiddenShown() { return showHidden; }
    boolean scanningNow() { return scanning; }
    LibraryScanner.Title title(String titleId) { return titleFor(titleId); }

    /** Sets the filter chip and the Find text (null: as it is) and draws Home again; the Building filter exists only while something builds (render puts All back) */
    void view(String filterName, String findText) {
        if (filterName != null) for (int i = 0; i < FILTER_NAMES.length; i++) if (FILTER_NAMES[i].equals(filterName)) filter = i;
        if (findText != null) find = findText;
        render();
    }

    // ---- Home ----

    /** One cover of the grid: upstream's game or a scanned title, or both when they are the same game */
    private static final class Row {
        final Object game; final Upstream.Entry entry; final LibraryScanner.Title title;
        Row(Object game, Upstream.Entry entry, LibraryScanner.Title title) { this.game = game; this.entry = entry; this.title = title; }
        String id() { return title != null ? title.titleId : entry.titleId; }
        String name() { return title != null ? title.name : entry.name; }
        int status() {
            if (game != null) return Upstream.hasProfile(entry.titleId) ? READY : NO_PROFILE;
            return title.status == LibraryScanner.Status.READY ? READY : title.status == LibraryScanner.Status.BUILD ? BUILD : NO_PROFILE;
        }
        long used() { return entry != null ? entry.used : 0; }
    }

    /** How many of the Activity's current Game entries {@link #hiddenUnmounted} covers right now, regardless of {@link #showHidden} — the filter row's
     *  "Hidden: N missing games" count always reflects what is actually hidden this render, not {@link #hiddenUnmounted}'s raw size (which may include
     *  gameDirs the current {@code games} list does not even have any more) */
    private int hiddenGameCount() {
        int n = 0;
        for (Object g : games) {
            Upstream.Entry e = Upstream.entryOf(g);
            if (e != null && hiddenUnmounted.contains(e.gameDir)) n++;
        }
        return n;
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        Set<LibraryScanner.Title> taken = new HashSet<>();
        for (Object g : games) {
            Upstream.Entry e = Upstream.entryOf(g);
            if (e == null) continue;
            if (!showHidden && hiddenUnmounted.contains(e.gameDir)) continue;   // hidden (never removed): either its volume is not mounted right now, or
            // it is confirmed missing but kept for the player to see and Remove themselves (found in review); the "Hidden: N · Show" toggle reveals it
            LibraryScanner.Title hit = null;
            for (LibraryScanner.Title t : titles) if (t.launch != null && samePath(e.gameDir, t.launch.getPath())) { hit = t; break; }
            if (hit != null) taken.add(hit);
            out.add(new Row(g, e, hit));
        }
        for (LibraryScanner.Title t : titles) if (!taken.contains(t)) out.add(new Row(null, null, t));
        return out;
    }

    /**
     * Whether this row's game is being built now: upstream's Prepare shows its card for it, or the Peanuts build (running, or stopped until retried) is its,
     * or it waits in the build queue, or the queue stopped it (its card says why until it is built again or dismissed)
     */
    private boolean isBuilding(Row r) {
        if (r.game != null && shows.test(r.game)) return true;
        Build b = build;
        return b != null && r.id().equalsIgnoreCase(b.titleId) || queueItem(r.id()) != null;
    }

    // ---- The build queue (issue #348, dev.peanuts.build.BuildQueue) ----

    /** Where a building row goes in All: upstream's Prepare, the build, the queue in its order, then what stopped */
    private int buildRank(Row r) {
        if (r.game != null && shows.test(r.game)) return 0;
        Build b = build;
        if (b != null && r.id().equalsIgnoreCase(b.titleId)) return b.failed ? 100_000 : 1;
        BuildQueue.Item i = queueItem(r.id());
        BuildQueue q = BuildPipeline.queueNow();
        if (i != null && i.waiting() && q != null) return 2 + q.place(i);
        return 100_001;
    }

    /** What a rebuild depends on of the queue: each game's state and place, and what it waits for */
    private static String queueSignature() {
        BuildQueue q = BuildPipeline.queueNow();
        if (q == null) return "";
        StringBuilder s = new StringBuilder("\nqueue|").append(BuildPipeline.waitReason()).append('|').append(q.held()).append('|').append(q.total());
        for (BuildQueue.Item i : q.items()) s.append('|').append(i.id).append(',').append(i.titleId()).append(',').append(i.state).append(',').append(q.place(i));
        return s.toString();
    }

    /** The game's waiting or stopped item of the build queue, null when it has none or the build card is that item's */
    private BuildQueue.Item queueItem(String titleId) {
        BuildQueue q = BuildPipeline.queueNow();
        BuildQueue.Item i = q == null ? null : q.of(titleId);
        if (i == null || i.running()) return null;
        Build b = build;
        return b != null && b.id == i.id ? null : i;
    }

    /** The queue's waiting games, in the order they build */
    private static List<BuildQueue.Item> queueWaiting() {
        BuildQueue q = BuildPipeline.queueNow();
        return q == null ? new ArrayList<>() : q.waiting();
    }

    /** The queue's stopped games, but the one the build card shows already */
    private static List<BuildQueue.Item> queueStopped() {
        BuildQueue q = BuildPipeline.queueNow();
        List<BuildQueue.Item> l = new ArrayList<>();
        if (q == null) return l;
        Build b = build;
        for (BuildQueue.Item i : q.stopped()) if (b == null || b.id != i.id) l.add(i);
        return l;
    }

    /** "Queued, 3 of 5" ("Queued" when the queue has only it) */
    private static String queuedWords(BuildQueue.Item i) {
        BuildQueue q = BuildPipeline.queueNow();
        String p = q == null ? "" : q.placeText(i);
        return p.isEmpty() ? "Queued" : "Queued, " + p;
    }

    /**
     * Upstream's own Prepare (Ondevice.prepare: Home's Prepare, Storage › Prepare again, a picked dump) while a build runs or waits: the packer runs one job,
     * so it goes into the build queue as a build of that .nup or dump folder (Prepare there does what upstream's card does: what fits is kept, the rest made
     * again). Returns false when nothing builds: upstream's Prepare goes on as it was. dump: the game file the card names
     */
    public static boolean queuePrepare(Activity a, File dump) {
        if (dump == null || !BuildPipeline.busy()) return false;
        String id = Upstream.titleIdOf(dump);
        String name = null;
        for (Upstream.Entry e : Upstream.games(a)) if (samePath(e.gameDir, dump.getPath())) { if (id == null || id.isEmpty()) id = e.titleId; name = e.name; break; }
        for (LibraryScanner.Title t : lastTitles) if (t.launch != null && samePath(t.launch.getPath(), dump.getPath())) { if (id == null || id.isEmpty()) id = t.titleId; if (name == null) name = t.name; break; }
        if (id == null || id.isEmpty()) return false;   // upstream's own check says what is wrong with the dump
        BuildRequest r = new BuildRequest(id.toUpperCase(Locale.ROOT), name != null ? name : dump.getName(), dump, null, null, null, null);
        r.gameDir = PeanutsPrefs.homeFor(a, dump);   // the game folder that holds the file, else the first (issue #546)
        r.buildDestination = PeanutsPrefs.chosenBuildDir(a, r.titleId);
        Library l = current != null && current.a == a ? current : null;
        if (l != null && l.building(r.titleId)) { Toast.makeText(a, r.name + " is being built now.", Toast.LENGTH_LONG).show(); return true; }
        final int[] mine = {0};
        int qid = BuildPipeline.start(a, r, false, new BuildListener() {
            @Override public void progress(String step, int percent, String detail) {}
            @Override public void done(boolean ok, String message, String code) {
                if (mine[0] != 0) return;   // the game's own end: the build card's listener (attached when it starts) has it
                Haptics.play(a.getWindow().getDecorView(), Haptics.Word.REJECT);
                Toast.makeText(a, message, Toast.LENGTH_LONG).show();
            }
        });
        mine[0] = qid;
        if (qid == 0) return true;   // refused: the listener says why (a game file that is gone, no title ID)
        BuildQueue.Item qi = BuildPipeline.queued(r.titleId);
        String p = qi == null || BuildPipeline.queueNow() == null ? "" : BuildPipeline.queueNow().placeText(qi);
        Toast.makeText(a, "Another game is building now, so " + r.name + " builds after it" + (p.isEmpty() ? "." : ", " + p + " in the build queue."), Toast.LENGTH_LONG).show();
        if (l != null) l.followQueue();
        return true;
    }

    // ---- Build all that need it ----

    /** Whether Home offers Build all: a game the scan found that this app can build (Ready or Build needed) and that does not build or wait already */
    private boolean offersBuildAll(List<Row> rows) {
        for (Row r : rows) if (r.title != null && r.title.base != null && (r.status() == BUILD || r.status() == READY) && !isQueuedOrRunning(r)) return true;
        return false;
    }

    /** The game builds now or waits in the queue (a stopped one is not: Build all builds it again) */
    private boolean isQueuedOrRunning(Row r) {
        if (r.game != null && shows.test(r.game)) return true;
        Build b = build;
        if (b != null && !b.failed && r.id().equalsIgnoreCase(b.titleId)) return true;
        return BuildPipeline.queued(r.id()) != null;
    }

    /**
     * Build all that need it: every game whose cover says Build needed, every game whose build stopped, and every ready game whose pack an update of the app
     * left behind (its code or shaders are of other tools: Prepare would make that part again), checked off the main thread, then one question that names them,
     * then each into the build queue. Left out, and said so: a game whose file is gone or whose version this app does not list (its sheet asks first)
     */
    private void buildAllAsk() {
        if (!PeanutsPrefs.hasKeys(a) || !PeanutsPrefs.hasFonts(a)) {
            Haptics.play(anchor(), Haptics.Word.REJECT);
            new AlertDialog.Builder(a).setTitle("Build all").setMessage(!PeanutsPrefs.hasKeys(a) ? "Add your keys first in Settings › Console, then build." : "Add your firmware first in Settings › Console, then build.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<LibraryScanner.Title> need = new ArrayList<>(), ready = new ArrayList<>();
        List<String> asks = new ArrayList<>();
        for (Row r : rows()) {
            LibraryScanner.Title t = r.title;
            if (t == null || t.base == null || isQueuedOrRunning(r) || r.status() == NO_PROFILE) continue;
            if (r.status() == READY) { if (t.launch != null && t.packDir != null) ready.add(t); continue; }
            if (t.home == null || t.versionState == LibraryScanner.VersionState.FILE_GONE || t.versionState == LibraryScanner.VersionState.UNSUPPORTED || GameSheet.wouldAsk(a, t)) asks.add(t.name);
            else need.add(t);
        }
        Haptics.play(anchor(), Haptics.Word.TICK);
        Context app = a.getApplicationContext();
        new Thread(() -> {
            List<LibraryScanner.Title> stale = new ArrayList<>();
            for (LibraryScanner.Title t : ready) if (!BuildPipeline.upToDate(app, t.launch, t.titleId, t.packDir)) stale.add(t);
            UI.post(() -> { if (!a.isFinishing() && !a.isDestroyed()) buildAllAnswer(need, stale, asks); });
        }, "peanuts-build-all").start();
    }

    private void buildAllAnswer(List<LibraryScanner.Title> need, List<LibraryScanner.Title> stale, List<String> asks) {
        String left = asks.isEmpty() ? "" : "\n\n" + names(asks) + (asks.size() == 1 ? " is" : " are") + " left out: " + (asks.size() == 1 ? "its" : "their")
                + " sheet asks first, for a file that is gone or an untested game version.";
        if (need.isEmpty() && stale.isEmpty()) {
            new AlertDialog.Builder(a).setTitle("Build all").setMessage("Every game is built and up to date with this app version." + left).setPositiveButton("OK", null).show();
            return;
        }
        List<String> n = new ArrayList<>(), s = new ArrayList<>();
        for (LibraryScanner.Title t : need) n.add(t.name);
        for (LibraryScanner.Title t : stale) s.add(t.name);
        int total = need.size() + stale.size();
        StringBuilder m = new StringBuilder();
        if (!n.isEmpty()) m.append(n.size() == 1 ? names(n) + " needs a build." : names(n) + " need a build.");
        if (!s.isEmpty()) m.append(m.length() > 0 ? " " : "").append(names(s)).append(s.size() == 1 ? " is built, but by an earlier app version, so part of its build is made again."
                : " are built, but by an earlier app version, so part of each build is made again.");
        m.append(total == 1 ? "" : " They build one after the other.").append(" Before each one the queue waits while the phone is hot, the battery is low or a game runs.").append(left);
        new AlertDialog.Builder(a).setTitle(total == 1 ? "Build 1 game?" : "Build " + total + " games?").setMessage(m)
                .setPositiveButton("Build all", (d, w) -> {
                    List<LibraryScanner.Title> all = new ArrayList<>(need);
                    all.addAll(stale);
                    List<GameSheet.State> states = new ArrayList<>();
                    for (LibraryScanner.Title t : all) states.add(GameSheet.resumeState(a, t, CompanionPort.forTitle(catalog, t.titleId)));
                    int queued = startBuilds(states);   // all at once: one write of the queue, one redraw of Home (a refusal says why in its own dialog)
                    Haptics.play(anchor(), queued > 0 ? Haptics.Word.HEAVY : Haptics.Word.REJECT);
                    if (queued > 0) {
                        Toast.makeText(a, queued == 1 ? "1 game is in the build queue." : queued + " games are in the build queue.", Toast.LENGTH_LONG).show();
                        askNotify();
                        filter = BUILDING; focusGrid = true; animateNext = true; render();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** "A", "A and B", "A, B and C" */
    private static String names(List<String> l) {
        if (l.size() <= 1) return l.isEmpty() ? "" : l.get(0);
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    /** The game the queue builds now gets the build card (a service that took the next game, an activity made after the app's process ended) */
    private void takeRunning() {
        int r = BuildPipeline.running();
        Build b = build;
        // paused, asked again, or waiting before it starts (heat, the battery, a game): its queue item stands for its card
        if (b != null && b.id != r && BuildPipeline.queued(b.titleId) != null) { build = null; b = null; }
        if (r == 0 || b != null && b.id == r) return;
        BuildQueue.Item it = BuildPipeline.queue(a).get(r);
        if (it == null) return;
        Build n = new Build(r, it.titleId(), it.name());
        n.reimport = it.request.replaceNup != null;
        build = n;
        BuildPipeline.attach(r, listener(r));
    }

    /** The queue changed: the build card follows the game it builds, Home and the bottom screen are drawn again */
    private void followQueue() {
        takeRunning();
        if (box != null) render();
        pushBuild();
    }

    /** Remove from queue: the game leaves it (nothing of its files changes); asks first */
    private void removeAsk(BuildQueue.Item i) {
        PUi.confirmDanger(a, "Remove from the queue?", i.name() + " leaves the build queue and is not built. Its files do not change, and Build adds it again.",
                "Remove", () -> {
                    boolean ok = BuildPipeline.remove(a, i.id);
                    Haptics.play(anchor(), ok ? Haptics.Word.CLICK : Haptics.Word.REJECT);
                    if (!ok) Toast.makeText(a, i.name() + " is being built now.", Toast.LENGTH_LONG).show();
                });
    }

    /** Stop all: the game that builds stops and every waiting game leaves the queue; asks first */
    private void stopAllAsk() {
        List<BuildQueue.Item> w = queueWaiting();
        Build b = build;
        String now = b != null && !b.failed ? "The build of " + b.name + " stops, and " : "";
        String rest = w.size() == 1 ? w.get(0).name() + " leaves the queue." : w.size() + " waiting games leave the queue.";
        PUi.confirmDanger(a, "Stop the build queue?", now + rest + " What is built so far is kept, and Build adds a game again.",
                "Stop all", () -> { Haptics.play(anchor(), Haptics.Word.CLICK); BuildPipeline.cancelAll(a); });
    }

    /** The Building filter's card of the games that wait: each with its place and Remove, what the queue waits for, and Stop all */
    private View queueCard(List<BuildQueue.Item> waiting) {
        LinearLayout c = PUi.card(a);
        int p16 = PUi.dp(a, 16);
        c.setPadding(p16, p16, p16, p16);
        boolean paused = BuildPipeline.held();
        c.addView(PUi.text(a, paused ? "The build queue is paused" : "Up next", 17, R.color.bn_text, 700));
        String w = BuildPipeline.waitReason();
        PUi.add(c, PUi.small(a, paused ? "Resume goes on with " + waiting.get(0).name() + ", where it stopped."
                : w != null ? BuildQueue.waitText(w)
                : "They build one after the other. Before each one the queue waits while the phone is hot, the battery is low or a game runs."), 4);
        for (BuildQueue.Item i : waiting) {
            LinearLayout row = PUi.row(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tile = PUi.tile(a, i.name(), 40);
            Bitmap bm = icons.get(i.titleId());
            if (bm != null) PUi.tileIcon(tile, bm);
            row.addView(tile);
            LinearLayout info = new LinearLayout(a);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(PUi.dp(a, 12), 0, PUi.dp(a, 8), 0);
            TextView n = PUi.text(a, i.name(), 15, R.color.bn_text, 600);
            n.setMaxLines(1);
            n.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(n);
            String place = BuildPipeline.queueNow() == null ? "" : BuildPipeline.queueNow().placeText(i);
            info.addView(PUi.small(a, place.isEmpty() ? "Waits in the queue" : place));
            row.addView(info, PUi.weight());
            Button rm = PUi.button(a, "Remove", PUi.Kind.QUIET, v -> { Haptics.play(v, Haptics.Word.TICK); removeAsk(i); });
            rm.setContentDescription("Remove " + i.name() + " from the queue");
            rm.setOnKeyListener(shoulders);
            focusRing(rm, Color.TRANSPARENT);
            track(rm, "queue:" + i.titleId(), null);
            row.addView(rm, PUi.wrap());
            PUi.add(c, row, 12);
        }
        Button stop = PUi.button(a, "Stop all", paused ? PUi.Kind.SOFT : PUi.Kind.TONAL, v -> stopAllAsk());
        stop.setOnKeyListener(shoulders);
        if (paused) {
            Button go = PUi.button(a, "Resume", PUi.Kind.TONAL, v -> { Haptics.play(v, Haptics.Word.HEAVY); BuildPipeline.release(a); });
            go.setOnKeyListener(shoulders);
            PUi.add(c, PUi.pair(a, go, stop), 14);
        } else PUi.add(c, stop, 14, PUi.wrap());
        return c;
    }

    /** A game the queue stopped (not the build card's): its build card, as the card of a stopped build, with Dismiss */
    private View stoppedCard(BuildQueue.Item i) {
        Build b = stoppedBuild(i);
        return buildCard(b);
    }

    /** A stopped item as the build card's Build: its message, code and step, as the build reported them */
    private static Build stoppedBuild(BuildQueue.Item i) {
        Build b = new Build(i.id, i.titleId(), i.name());
        b.failed = true;
        b.error = i.message;
        b.code = i.code;
        b.step = i.step.isEmpty() ? "import" : i.step;
        b.reimport = i.request.replaceNup != null;
        return b;
    }

    /**
     * Home's game box, built again. A render that would show what the box shows already is skipped (the 30 second stale check, a build's progress while
     * neither its card nor its cover shows): {@link #signature} says what a rebuild depends on. A rebuild keeps the controller's focus (by title ID for a
     * cover, by filter for a chip) and the page's scroll position; one the user caused (a filter, Find) lets its first rows enter.
     */
    private void render() {
        if (box == null) return;
        dev.peanuts.ctl.Ctl.screen();   // the control interface's status file follows the filter, Find and what builds
        View pc = ondeviceCard;
        boolean setup = PeanutsPrefs.gameDir(a) == null || !PeanutsPrefs.setupDone(a);
        List<Row> rows = rows();
        boolean bare = setup && rows.isEmpty() && pc == null;   // the first-run card alone
        if (setup || rows.isEmpty()) BottomScreen.idle(setup);   // bottom screen: logo

        Build b = build;
        if (b != null && b.failed && b.step.equals("prepare")) pc = null;   // a build that stopped in Prepare: one card, this build's (its Retry builds again), not upstream's failed card beside it
        boolean buildCard = b != null && !(b.step.equals("prepare") && pc != null && !b.failed);   // during Prepare upstream's own card is the card
        List<BuildQueue.Item> waiting = queueWaiting(), stoppedQ = queueStopped();   // the build queue's other games (issue #348): Up next, and the ones it stopped
        int building = (pc != null ? 1 : 0) + (buildCard ? 1 : 0) + waiting.size() + stoppedQ.size();
        if (building == 0 && filter == BUILDING) filter = ALL;   // the last job finished: the filter goes away
        if (building == 0) ScreensOff.hide();   // nothing builds any more: the screens come back by themselves
        if (screensOffB != null) screensOffB.setVisibility(building > 0 ? View.VISIBLE : View.GONE);   // Home's header shows it only while something builds
        int all = rows.size(), ready = 0, toBuild = 0, noProfile = 0;
        List<Row> bl = new ArrayList<>(), rd = new ArrayList<>(), bd = new ArrayList<>(), np = new ArrayList<>();
        for (Row r : rows) {
            boolean shown = find.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(find.toLowerCase(Locale.ROOT));
            if (isBuilding(r)) { if (shown) bl.add(r); continue; }   // the other filters never show it
            switch (r.status()) {
                case READY: ready++; if (shown) rd.add(r); break;
                case BUILD: toBuild++; if (shown) bd.add(r); break;
                default: noProfile++; if (shown) np.add(r);
            }
        }
        Collections.sort(bl, (x, y) -> Integer.compare(buildRank(x), buildRank(y)));   // upstream's Prepare first, like its card, then the build, the queue in its order, what stopped
        playTimes = dev.peanuts.launch.PlayTime.all(a);   // once per render: Sort by play time and the bottom screen's card of a focused cover
        noteAdded(rows);
        String sort = PeanutsPrefs.librarySort(a);
        Comparator<Row> order = LibrarySort.order(sort, Row::name, Row::used, this::added, this::playMs);
        List<Row> allRows = LibrarySort.all(bl, rd, bd, np, order, PeanutsPrefs.librarySortGrouped(a));   // sorts rd, bd and np too
        List<Row> show = new ArrayList<>();   // the cover grid: one grid, the filters are the sections (the Building filter shows the full cards instead)
        switch (filter) {
            case BUILDING: break;
            case READY: show.addAll(rd); break;
            case BUILD: show.addAll(bd); break;
            case NO_PROFILE: show.addAll(np); break;
            default: show.addAll(allRows);
        }
        int hidden = hiddenGameCount();
        int cols = coverColumns();
        gridPos.clear();
        for (int i = 0; i < show.size(); i++) gridPos.put(show.get(i).id(), i);
        gridCols = Math.max(1, cols);
        if (!setup && !rows.isEmpty() && filter != BUILDING) BottomScreen.only(gridPos.keySet());

        // nothing visible changed: the views stay, with their focus, their scroll position and whatever is animating
        String sig = setup ? null : signature(show, pc, b, buildCard, building, all, ready, toBuild, noProfile, hidden, cols) + queueSignature();   // the first-run card reads too much to list: always built
        boolean user = animateNext;
        animateNext = false;
        if (sig != null && sig.equals(shown) && box.getChildCount() > 0) {
            if (b != null) updateBuildCard(b, false);
            focusGrid = false;
            return;
        }

        // what the rebuild keeps: the focused view's key (upstream's refresh has emptied the box already: then the one that lost the focus by that), the scroll position
        String keep = restoreKey;
        View focused = box.findFocus();
        if (focused != null) for (Map.Entry<String, View> e : keyed.entrySet()) if (e.getValue() == focused) keep = e.getKey();
        android.widget.ScrollView page = null;
        for (android.view.ViewParent p = box.getParent(); p != null && page == null; p = p.getParent()) if (p instanceof android.widget.ScrollView) page = (android.widget.ScrollView) p;
        int scrollY = page == null ? 0 : page.getScrollY();

        Motion.clear();
        box.removeAllViews();
        tiles.clear(); keyed.clear();
        lostKey = null; restoreKey = null;   // the removal above is not a focus lost to restore at the next rebuild
        buildStatus = null; buildBody = null; buildPct = null; buildBar = null; buildGoOn = null; coverChip = null; coverStrip = null; firstCover = null;
        if (ondeviceCard != null && ondeviceCard.getParent() instanceof ViewGroup) ((ViewGroup) ondeviceCard.getParent()).removeView(ondeviceCard);
        shown = sig;
        if (setup) PUi.add(box, setupCard(), 0);
        if (bare) { lastCells = 0; hadNote = false; return; }

        // the filter row
        LinearLayout top = PUi.row(a);
        HorizontalScrollView chips = new HorizontalScrollView(a);
        chips.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = PUi.row(a);
        chip(chipRow, "All " + all, ALL);
        if (building > 0) {
            SpannableString s = new SpannableString("Building " + building);
            if (filter != BUILDING) s.setSpan(new ForegroundColorSpan(PUi.col(a, R.color.bn_banana)), 9, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);   // the count in banana: something runs
            chip(chipRow, s, BUILDING);
        }
        chip(chipRow, "Ready " + ready, READY);
        chip(chipRow, "Build needed " + toBuild, BUILD);
        chip(chipRow, a.getString(R.string.not_supported_yet) + " " + noProfile, NO_PROFILE);
        if (hidden > 0) {
            // Found in review: lets the player see and remove a confirmed-missing (or currently unreachable) game themselves, through upstream's
            // own "Game file gone → Remove" dialog, instead of this code ever deleting the record on its own
            Button hideB = PUi.button(a, PUi.joined(hidden + " hidden", showHidden ? "Hide" : "Show"),
                    PUi.Kind.QUIET, v -> { Haptics.play(v, Haptics.Word.CLICK); showHidden = !showHidden; render(); });
            hideB.setOnKeyListener(shoulders);
            focusRing(hideB, Color.TRANSPARENT);
            track(hideB, "hidden", null);
            LinearLayout.LayoutParams hlp = PUi.wrap();
            hlp.leftMargin = PUi.dp(a, 8);
            chipRow.addView(hideB, hlp);
        }
        chips.addView(chipRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(chips, PUi.weight());
        if (!setup && offersBuildAll(rows)) {   // Build all that need it (issue #348): it asks first and names the games
            Button allB = PUi.button(a, "Build all…", PUi.Kind.QUIET, v -> buildAllAsk());
            allB.setCompoundDrawablesRelativeWithIntrinsicBounds(PUi.icon(a, R.drawable.ic_build, R.color.bn_accent), null, null, null);
            allB.setCompoundDrawablePadding(PUi.dp(a, 6));
            allB.setOnKeyListener(shoulders);
            focusRing(allB, Color.TRANSPARENT);
            track(allB, "build_all", null);
            LinearLayout.LayoutParams alp = PUi.wrap(); alp.leftMargin = PUi.dp(a, 8);
            top.addView(allB, alp);
        }
        // icon only: a label would push the filter chips off a handheld's row; the sheet shows the order in use
        android.widget.ImageView sortB = PUi.iconButton(a, R.drawable.ic_sort, "Sort: " + LibrarySort.label(sort),
                PUi.Kind.QUIET, Haptics.on(Haptics.Word.CLICK, v -> sortDialog()));
        sortB.setImageDrawable(PUi.icon(a, R.drawable.ic_sort, R.color.bn_accent));
        sortB.setOnKeyListener(shoulders);
        focusRing(sortB, Color.TRANSPARENT);
        track(sortB, "sort", null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(PUi.dp(a, 44), PUi.dp(a, 44)); slp.leftMargin = PUi.dp(a, 4);
        slp.gravity = android.view.Gravity.CENTER_VERTICAL;
        top.addView(sortB, slp);
        Button findB = PUi.button(a, find.isEmpty() ? "Find" : PUi.joined("Find", find), PUi.Kind.QUIET, Haptics.on(Haptics.Word.CLICK, v -> findDialog()));
        findB.setCompoundDrawablesRelativeWithIntrinsicBounds(PUi.icon(a, R.drawable.ic_search, R.color.bn_accent), null, null, null);
        findB.setCompoundDrawablePadding(PUi.dp(a, 6));
        findB.setOnKeyListener(shoulders);
        focusRing(findB, Color.TRANSPARENT);
        track(findB, "find", null);
        LinearLayout.LayoutParams flp = PUi.wrap(); flp.leftMargin = PUi.dp(a, 8);
        top.addView(findB, flp);
        PUi.add(box, top, setup ? 16 : 2);
        TextView note = null;   // the scan's line, or the empty library's: it fades in when the last rebuild did not have it
        if (scanning) {
            int n = PeanutsPrefs.roots(a).size();
            note = PUi.add(box, PUi.small(a, "Looking in " + n + (n == 1 ? " folder…" : " folders…")), 8);
        } else if (rows.isEmpty() && pc == null && !setup) {
            List<String> dirs = PeanutsPrefs.gameDirs(a);
            note = PUi.add(box, PUi.small(a, (dirs.size() == 1 ? "No games found in " + dirs.get(0) + ". Put NSP, NSZ, XCI, XCZ, .nup files or dump folders there"
                    : "No games found in your " + dirs.size() + " game folders. Put NSP, NSZ, XCI, XCZ, .nup files or dump folders in one of them") + ", then Rescan (Settings › Library)."), 8);
        }
        if (note != null && !hadNote) Motion.fadeIn(note);
        hadNote = note != null;
        int firstRow = box.getChildCount();   // the grid's first row in the box (Motion.enterRows)

        // the Building filter: the full cards, two per row
        if (filter == BUILDING) {
            List<View> full = new ArrayList<>();
            // the queue's stopped games' cards are made first: a build card keeps its live parts in this library's fields, and the running build's must be the last made
            List<View> stoppedCards = new ArrayList<>();
            for (BuildQueue.Item i : stoppedQ) stoppedCards.add(stoppedCard(i));
            if (pc != null) full.add(pc);
            if (buildCard) full.add(buildCard(b));
            if (!waiting.isEmpty()) full.add(queueCard(waiting));
            full.addAll(stoppedCards);
            grid(full, fullColumns(), PUi.GAP);
            PUi.add(box, PUi.small(a, "When a build finishes, its game moves to Ready and this filter goes away."), 10);
            afterRender(chipRow, keep, page, scrollY, user, firstRow);
            return;
        }

        int cell = (Math.min(widthDp(), 900) - 40 - PUi.GAP * (cols - 1)) / cols;   // Banana's 20 dp gutters and 12 dp gaps
        List<View> cells = new ArrayList<>();
        for (Row r : show) cells.add(coverCell(r, cell));
        grid(cells, cols, 16);
        boolean first = lastCells == 0 && !cells.isEmpty();   // the grid shows after an empty one (the first scan ended): it enters as after a filter change
        lastCells = cells.size();
        afterRender(chipRow, keep, page, scrollY, user || first, firstRow);
    }

    /**
     * What a rebuild depends on, as one string: the filter, Find, the counts on the chips, the scan's and the empty library's lines, the build (its percent
     * apart: {@link #updateBuildCard} writes that into the views that are there), upstream's progress card, and every cover showing in its order with its
     * name, state and companion badge. {@link #gen} stands for the lists themselves: the covers' taps hold the list entries they were built from, so a new
     * list of games (every fill) or of scanned titles is always built again.
     */
    private String signature(List<Row> show, View pc, Build b, boolean buildCard, int building, int all, int ready, int toBuild, int noProfile, int hidden, int cols) {
        StringBuilder s = new StringBuilder(64 + show.size() * 48);
        s.append(gen).append('|').append(filter).append('|').append(find).append('|').append(showHidden).append('|').append(hidden).append('|').append(cols).append('|').append(widthDp())
                .append('|').append(PeanutsPrefs.librarySort(a)).append(PeanutsPrefs.librarySortGrouped(a))
                .append('|').append(all).append(',').append(ready).append(',').append(toBuild).append(',').append(noProfile).append(',').append(building)
                .append('|').append(scanning ? PeanutsPrefs.roots(a).size() : -1).append('|').append(PeanutsPrefs.gameDirs(a))
                .append('|').append(pc == null ? 0 : System.identityHashCode(pc)).append('|').append(buildCard);
        if (b != null) s.append('|').append(b.id).append(',').append(b.step).append(',').append(b.failed).append(',').append(b.code).append(',').append(b.error.hashCode());
        for (Row r : show) {
            s.append('\n').append(r.id()).append('\t').append(r.name()).append('\t').append(r.status()).append('\t').append(isBuilding(r) ? 1 : 0)
                    .append(r.game != null && shows.test(r.game) ? 1 : 0).append(companionInstalled(r) ? 1 : 0)
                    .append(r.title == null ? "-" : String.valueOf(r.title.versionState));
        }
        return s.toString();
    }

    private static int gen;   // counts the lists a signature stands for: bumped by every fill (upstream's games) and every scan's result (the titles)

    /**
     * After a rebuild: the focus goes back where it was (a shoulder-button filter change puts it on the first cover instead), silently and with no focus
     * animation; without a focus to put back the page keeps its scroll position (the removal moved the focus, and the page with it, to the page's first
     * view); and the first rows enter when the user caused the rebuild.
     */
    private void afterRender(LinearLayout chipRow, String keep, android.widget.ScrollView page, int scrollY, boolean enter, int firstRow) {
        boolean placed = focusGrid;
        focusAfterRender(chipRow);
        View v = placed || keep == null ? null : keyed.get(keep);
        if (v != null && !box.isInTouchMode()) {
            Haptics.quiet(200);
            restoring = true;
            placed = v.requestFocus();   // before the layout: the page then scrolls only as far as showing it needs, which is not at all when it did not move
            restoring = false;
        }
        if (!placed && !enter && page != null && scrollY > 0) {
            android.widget.ScrollView sv = page;
            sv.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    sv.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    if (sv.getScrollY() != scrollY) sv.scrollTo(0, scrollY);
                }
            });
        }
        if (enter) Motion.enterRows(box, firstRow);
    }

    /** After a shoulder-button filter change the first cover (else the chosen chip) takes the focus, so the controller stays in the grid */
    private void focusAfterRender(LinearLayout chipRow) {
        if (!focusGrid) return;
        focusGrid = false;
        View f = firstCover;
        if (f == null) for (int i = 0; i < chipRow.getChildCount(); i++) if (chipRow.getChildAt(i).isSelected()) f = chipRow.getChildAt(i);
        if (f == null && chipRow.getChildCount() > 0) f = chipRow.getChildAt(0);
        if (f != null) { View v = f; v.post(() -> { Haptics.quiet(200); restoring = true; v.requestFocus(); restoring = false; }); }
    }

    /**
     * One of the library's own focusable views under a key that outlives a rebuild. A view that loses the focus and is removed in the same moment lost it
     * to the removal (upstream's refresh empties the box before {@link #fill}): the next rebuild puts the focus on the view with that key. tile: a cover's
     * tile, whose focus and press animations end with the view.
     */
    private void track(View v, String key, View tile) {
        keyed.put(key, v);
        if (tile == null) v.setOnFocusChangeListener((x, f) -> noteFocus(key, f));   // a cover's own listener calls noteFocus
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View x) {}
            @Override public void onViewDetachedFromWindow(View x) {
                if (key.equals(lostKey) && android.os.SystemClock.uptimeMillis() - lostAt < 50) restoreKey = key;
                if (tile != null) tile.animate().cancel();
            }
        });
    }

    private void noteFocus(String key, boolean gained) {
        if (gained) { lostKey = null; restoreKey = null; }
        else { lostKey = key; lostAt = android.os.SystemClock.uptimeMillis(); }
    }

    private void chip(LinearLayout row, CharSequence label, int which) {
        boolean on = filter == which;
        Button c = PUi.button(a, label, on ? PUi.Kind.PRIMARY : PUi.Kind.TONAL, v -> { Haptics.play(v, Haptics.Word.CLICK); filter = which; animateNext = true; render(); });
        c.setSelected(on);
        c.setOnKeyListener(shoulders);
        focusRing(c, PUi.col(a, on ? R.color.bn_banana : R.color.bn_tint));
        track(c, "chip:" + which, null);
        LinearLayout.LayoutParams lp = PUi.wrap();
        if (row.getChildCount() > 0) lp.leftMargin = PUi.dp(a, 8);
        row.addView(c, lp);
    }

    /** The covers' focus ring on a filter-row button, at its edge; a filled button's fill steps inside it */
    private void focusRing(View v, int fill) {
        int r = PUi.dp(a, 14), w = PUi.dp(a, 3), gap = PUi.dp(a, 2);
        LayerDrawable focused = new LayerDrawable(new Drawable[] {PUi.round(Color.TRANSPARENT, r, PUi.col(a, R.color.bn_banana), w),
                PUi.round(fill, r - w - gap, 0, 0)});
        focused.setLayerInset(1, w + gap, w + gap, w + gap, w + gap);   // layer insets, not an InsetDrawable: those would become the button's padding
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, focused);
        s.addState(new int[0], v.getBackground());
        v.setBackground(s);
    }

    /** A key on any of the library's own focusable views (covers, chips, Find): the D-pad's focus haptic first (it never takes the key), then L/R
     *  shoulder: the previous/next filter chip */
    private final View.OnKeyListener shoulders = (v, code, ev) -> {
        Haptics.focusKey(v, ev);
        if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() != 0) return false;
        if (code == KeyEvent.KEYCODE_BUTTON_L1) { Haptics.play(v, Haptics.Word.CLICK); stepFilter(-1); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_R1) { Haptics.play(v, Haptics.Word.CLICK); stepFilter(1); return true; }
        return false;
    };

    private void stepFilter(int d) {
        Build b = build;
        boolean pcShows = ondeviceCard != null && !(b != null && b.failed && b.step.equals("prepare"));   // as render(): a build stopped in Prepare shows its own card only
        boolean buildCard = b != null && !(b.step.equals("prepare") && pcShows && !b.failed);
        boolean hasBuilding = pcShows || buildCard || !queueWaiting().isEmpty() || !queueStopped().isEmpty();
        List<Integer> avail = new ArrayList<>();
        for (int f : FILTERS) if (f != BUILDING || hasBuilding) avail.add(f);
        int i = Math.max(0, avail.indexOf(filter));
        filter = avail.get(((i + d) % avail.size() + avail.size()) % avail.size());
        focusGrid = true;
        animateNext = true;
        render();
    }

    // ---- Sort ----

    /** When each game came into the library (LibrarySort.firstSeen, kept in PeanutsPrefs), by title ID: read again once per list (gen) for Recently added */
    private final Map<String, Long> addedAt = new HashMap<>();
    private int addedGen = -1;

    /** Reads every game's first-seen time and keeps one for each game the library has not seen before (its file's time, else now) */
    private void noteAdded(List<Row> rows) {
        if (addedGen == gen) return;
        addedGen = gen;
        addedAt.clear();
        Map<String, Long> fresh = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Row r : rows) {
            long t = PeanutsPrefs.firstSeen(a, r.id());
            if (t <= 0) {
                File f = r.title != null ? (r.title.launch != null ? r.title.launch : r.title.base != null ? r.title.base.file : null)
                        : r.entry != null && !r.entry.gameDir.isEmpty() ? new File(r.entry.gameDir) : null;
                t = LibrarySort.firstSeen(f == null ? 0 : f.lastModified(), now);
                fresh.put(r.id(), t);
            }
            addedAt.put(r.id(), t);
        }
        if (!fresh.isEmpty()) PeanutsPrefs.firstSeen(a, fresh);
    }

    private long added(Row r) { Long t = addedAt.get(r.id()); return t == null ? 0 : t; }

    private long playMs(Row r) {
        dev.peanuts.launch.PlayLedger.Entry e = playTimes.get(r.id().toUpperCase(Locale.ROOT));
        return e == null ? 0 : e.ms;
    }

    private void sortDialog() {
        String cur = PeanutsPrefs.librarySort(a);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        android.widget.RadioGroup g = new android.widget.RadioGroup(a);
        for (int i = 0; i < LibrarySort.MODES.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(a);
            rb.setId(View.generateViewId());
            rb.setText(LibrarySort.HINTS[i] == null ? LibrarySort.LABELS[i] : PUi.joined(LibrarySort.LABELS[i], LibrarySort.HINTS[i]));
            rb.setMinHeight(PUi.dp(a, 48));
            rb.setTag(LibrarySort.MODES[i]);
            g.addView(rb);
            if (i == LibrarySort.index(cur)) rb.setChecked(true);
        }
        wrap.addView(g, PUi.fill());
        android.widget.Switch apart = new android.widget.Switch(a);
        apart.setText("Keep Ready, Build needed and Not supported yet apart");
        apart.setChecked(PeanutsPrefs.librarySortGrouped(a));
        apart.setMinHeight(PUi.dp(a, 48));
        PUi.add(wrap, apart, 8);
        g.setOnCheckedChangeListener((x, id) -> {
            View v = g.findViewById(id);
            if (v == null) return;
            Haptics.play(v, Haptics.Word.CLICK);
            PeanutsPrefs.librarySort(a, (String) v.getTag());
            animateNext = true; render();
        });
        apart.setOnCheckedChangeListener((x, on) -> { Haptics.play(x, Haptics.Word.CLICK); PeanutsPrefs.librarySortGrouped(a, on); animateNext = true; render(); });
        PUi.sheet(a, "Sort", wrap).setPositiveButton("Done", null).show();
    }

    private void findDialog() {
        EditText e = new EditText(a);
        e.setHint("A game's name");
        e.setText(find);
        e.setSingleLine(true);
        e.setSelection(find.length());
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(e, PUi.fill());
        AlertDialog d = PUi.sheet(a, "Find", wrap).setPositiveButton("Find", (x, w) -> { find = e.getText().toString().trim(); animateNext = true; render(); })
                .setNegativeButton("Cancel", null).setNeutralButton(find.isEmpty() ? null : "Clear", (x, w) -> { find = ""; animateNext = true; render(); }).create();
        e.setOnEditorActionListener((v, id, ev) -> { find = e.getText().toString().trim(); d.dismiss(); animateNext = true; render(); return true; });
        d.show();
        e.requestFocus();
    }

    private int widthDp() { Configuration c = a.getResources().getConfiguration(); return c.screenWidthDp; }
    private int fullColumns() { return widthDp() >= 700 ? 2 : 1; }
    private int coverColumns() { return widthDp() >= 700 ? 4 : widthDp() >= 540 ? 3 : 2; }

    /** Views in rows of cols weighted cells, 12 dp apart, rowGap dp between rows; an odd last cell keeps its width (empty cells fill the rest) */
    private void grid(List<View> cards, int cols, int rowGap) {
        for (int i = 0; i < cards.size(); i += cols) {
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < cols; k++) {
                View v = i + k < cards.size() ? cards.get(i + k) : new View(a);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (k > 0) lp.leftMargin = PUi.dp(a, PUi.GAP);
                r.addView(v, lp);
            }
            PUi.add(box, r, i == 0 ? 10 : rowGap);
        }
    }

    // ---- The covers ----

    /** Every title's total play time (dev.peanuts.launch.PlayTime), read again at each render of the grid */
    private Map<String, dev.peanuts.launch.PlayLedger.Entry> playTimes = Collections.emptyMap();

    /** The session a game's Stop left (dev.peanuts.launch.PlayTime.sessionNote), shown once */
    private void sessionToast() {
        String note = dev.peanuts.launch.PlayTime.sessionNote(a);
        if (note != null) Toast.makeText(a, note, Toast.LENGTH_LONG).show();
    }

    /** The bottom screen's card shows the focused game's total, read again after a game (playTimesAgain) */
    private void playTimesAgain() {
        playTimes = dev.peanuts.launch.PlayTime.all(a);
        for (Map.Entry<String, dev.peanuts.launch.PlayLedger.Entry> e : playTimes.entrySet()) BottomScreen.playTime(e.getKey(), e.getValue());
    }

    /** The title's play time for the bottom screen's card, null when the game was never played with this app */
    private dev.peanuts.launch.PlayLedger.Entry play(String titleId) {
        dev.peanuts.launch.PlayLedger.Entry e = playTimes.get(titleId.toUpperCase(Locale.ROOT));
        return e == null || e.ms <= 0 ? null : e;
    }

    /**
     * One cell of the grid: the cover (PUi's tile at cell size, the NACP icon once it has landed) with the state on it, the name under it. The cover is the focusable,
     * clickable thing: a tap plays a ready game, opens the Building filter for a building one, the sheet or Manage otherwise; a long press (or Y) opens Manage
     * (listed) or the sheet (scanned); a 3 dp banana ring and a banana name show the focus
     */
    private View coverCell(Row r, int cellDp) {
        int st = r.status();
        Build b = build;
        boolean building = isBuilding(r);
        boolean ownBuild = building && b != null && r.id().equalsIgnoreCase(b.titleId);   // the Peanuts build's game (else upstream's Prepare: no percent to show)
        boolean stopped = ownBuild && b.failed;
        // the build queue's other games (issue #348): waiting with its place, or stopped by the queue while it built on
        BuildQueue.Item qi = building && !ownBuild && !(r.game != null && shows.test(r.game)) ? queueItem(r.id()) : null;
        boolean queued = qi != null && qi.waiting(), queueStopped = qi != null && qi.stopped();
        String name = r.name();
        boolean companion = companionInstalled(r);
        // P1 (version truth): a "Build needed" cover splits into "File gone" and "Wrong version" when the scan knows enough (r.title set); a listed-only row
        // (r.title == null) keeps today's plain "Build needed" since LibraryScanner never looked at it
        LibraryScanner.VersionState vs = r.title == null ? LibraryScanner.VersionState.UNKNOWN : r.title.versionState;
        boolean fileGone = vs == LibraryScanner.VersionState.FILE_GONE, wrongVersion = vs == LibraryScanner.VersionState.UNSUPPORTED;
        String buildWords = fileGone ? "File gone" : wrongVersion ? "Wrong version" : "Build needed";
        CharSequence state = building ? (stopped || queueStopped ? "Build stopped" : queued ? queuedWords(qi) : PUi.joined("Building", ownBuild ? b.percent + " %" : "in progress"))
                : st == READY ? "Ready" : st == BUILD ? buildWords : a.getString(R.string.not_supported_yet);
        // a newer update or new add-ons beside a built game's .nup (issue #120): the cover says so, and a tap asks before it takes them in ("Not now" asks no more for these)
        UpdateFound.Found found = !building && st == READY ? found(r) : null;
        boolean askFound = found != null && !found.key().equals(PeanutsPrefs.foundSeen(a, r.id()));
        if (found != null) state = PUi.joined(state, UpdateFound.what(found, updateName(r, found)));

        LinearLayout cell = new LinearLayout(a);
        cell.setOrientation(LinearLayout.VERTICAL);
        FrameLayout cover = new FrameLayout(a);
        int p4 = PUi.dp(a, 4), p8 = PUi.dp(a, 8);
        cover.setPadding(p4, p4, p4, p4);
        cover.setClipToPadding(false);   // the focused tile grows into the 4 dp around it (Motion.focus)
        cover.setHapticFeedbackEnabled(Haptics.enabled());   // the system's own long-press haptic follows the app's setting too
        cover.setClickable(true);
        cover.setFocusable(true);
        cover.setContentDescription(name + ", " + state + (companion ? ", companion installed" : ""));
        // the ripple under the cover (masked to its 16 dp corners) and the focus ring, 3 dp outside the cover's edge
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[] {android.R.attr.state_focused}, PUi.round(Color.TRANSPARENT, PUi.dp(a, 20), PUi.col(a, R.color.bn_banana), PUi.dp(a, 3)));
        ring.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x336B4423), null, new InsetDrawable(PUi.round(Color.BLACK, PUi.dp(a, 16), 0, 0), p4));
        cover.setForeground(new LayerDrawable(new Drawable[] {ripple, ring}));

        int coverDp = cellDp - 8;   // the cell less the 4 dp the focus ring needs on each side: the cover stays square
        TextView tile = PUi.tile(a, name, coverDp);
        tile.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(a, coverDp)));
        Bitmap bm = icons.get(r.id());
        if (bm != null) PUi.tileIcon(tile, bm);
        if (!building && st == NO_PROFILE) tile.setAlpha(0.45f);
        tiles.put(r.id(), tile);
        cover.addView(tile);

        // the state on the cover
        if (queued || queueStopped) {   // not the build's own cover: its chip and strip are the running build's (updateBuildCard)
            cover.addView(mark(state, PUi.col(a, R.color.bn_card), queueStopped ? R.color.bn_danger : R.color.bn_text_2, true, Gravity.TOP | Gravity.START));
        } else if (building) {
            coverChip = mark(state, PUi.col(a, R.color.bn_card), stopped ? R.color.bn_danger : R.color.bn_accent, true, Gravity.TOP | Gravity.START);
            cover.addView(coverChip);
            coverStrip = strip(!ownBuild);
            if (stopped) coverStrip.setVisibility(View.GONE); else if (ownBuild) coverStrip.setProgress(Math.max(0, Math.min(100, b.percent)));
            cover.addView(coverStrip);
        } else if (st == READY) {
            TextView ok = PUi.text(a, "✓", 12, R.color.bn_on_leaf, 700);
            if (popReady != null && popReady.equalsIgnoreCase(r.id())) {   // its build ended a moment ago, in front of the user: the check is new
                if (android.os.SystemClock.uptimeMillis() - popReadyAt < 60_000L) Motion.popIn(ok);
                popReady = null;
            }
            ok.setGravity(Gravity.CENTER);
            ok.setIncludeFontPadding(false);
            ok.setBackground(PUi.round(PUi.col(a, R.color.bn_leaf), PUi.dp(a, 999), 0, 0));
            ok.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(PUi.dp(a, 22), PUi.dp(a, 22), Gravity.TOP | Gravity.END);
            lp.setMargins(p8, p8, p8, p8);
            cover.addView(ok, lp);
            if (found != null) cover.addView(mark(askFound ? "Update" : "Update later", PUi.col(a, R.color.bn_banana), R.color.bn_on_banana, false, Gravity.TOP | Gravity.START));
        } else if (st == BUILD) {
            String pill = fileGone ? "File gone" : wrongVersion ? "Wrong version" : "Build";
            int fill = fileGone || wrongVersion ? R.color.bn_danger_tint : R.color.bn_banana;
            int text = fileGone || wrongVersion ? R.color.bn_danger : R.color.bn_on_banana;
            cover.addView(mark(pill, PUi.col(a, fill), text, false, Gravity.TOP | Gravity.START));
        } else {
            cover.addView(mark(a.getString(R.string.not_supported_yet), PUi.col(a, R.color.bn_danger_tint), R.color.bn_danger, false, Gravity.TOP | Gravity.START));
        }
        if (companion) cover.addView(companionBadge(building && !stopped && !queued && !queueStopped));

        // the name
        int nameColor = !building && st == NO_PROFILE ? R.color.bn_text_2 : R.color.bn_text;
        TextView n = PUi.text(a, name, 14, nameColor, 600);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        n.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams nlp = PUi.fill();
        nlp.setMargins(p4, p4, p4, 0);
        String key = "cover:" + r.id();
        cover.setOnFocusChangeListener((v, f) -> {
            n.setTextColor(PUi.col(a, f ? R.color.bn_banana : nameColor));   // at once: a colour animation would redraw the text every frame
            noteFocus(key, f);
            Motion.focus(tile, f, restoring);
        });
        track(cover, key, tile);

        // tap, long press, Y, shoulders
        Runnable tap, hold;
        Runnable manageOrSheet = r.game != null ? () -> manage.accept(r.game) : () -> sheet(r.title);
        boolean plays = !building && st == READY && r.game != null;
        if (building) { tap = () -> { filter = BUILDING; focusGrid = true; animateNext = true; render(); }; hold = manageOrSheet; }   // a queued game's sheet says its place and has Remove from queue
        else if (st == READY) { tap = askFound ? () -> askFound(r, found) : plays ? () -> play(r) : () -> sheet(r.title); hold = manageOrSheet; }
        else if (st == BUILD) { tap = () -> sheet(r.title); hold = tap; }
        else { tap = manageOrSheet; hold = manageOrSheet; }   // listed: Banana's Manage (the sentence, Remove from list); scanned: the sheet (Profile.missing's sentence)
        // a tap or A: Play is the weighty word, at the press, whatever dialog may come first; the tile dips while the launch or the sheet goes on
        cover.setOnClickListener(v -> {
            Haptics.play(v, plays ? Haptics.Word.HEAVY : Haptics.Word.CLICK);
            if (!building) Motion.press(tile);
            tap.run();
        });
        cover.setOnLongClickListener(v -> { hold.run(); return true; });   // the system plays its own long-press haptic for a true: nothing added, or it plays twice
        // Y: second action; Stop on a running build while the bottom screen shows
        cover.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && code == KeyEvent.KEYCODE_BUTTON_Y) {
                Haptics.play(v, Haptics.Word.HEAVY);
                Runnable stop = ownBuild && !stopped && BottomScreen.showing() ? stopAsk(name) : queued && BottomScreen.showing() ? () -> removeAsk(qi) : null;   // a queued game's Y: Remove from queue
                (stop != null ? stop : hold).run();
                return true;
            }
            return shoulders.onKey(v, code, ev);
        });
        View.OnFocusChangeListener focusWas = cover.getOnFocusChangeListener();
        cover.setOnFocusChangeListener((v, f) -> { focusWas.onFocusChange(v, f); if (f) bottom(r, tap, hold); });

        cell.addView(cover, PUi.fill());
        cell.addView(n, nlp);
        if (firstCover == null) { firstCover = cover; cover.setFocusedByDefault(true); }   // a controller's first focus on Home: the first game (homeHeader)
        return cell;
    }

    /** Play for a listed, ready game: Banana's own launch (the pack search, the checks, its dialogs); its Manage sheet if that cannot be reached */
    /** What an Import again would add to a built game whose base is a .nup (UpdateFound), from the last scan; null when nothing or not a .nup */
    private static UpdateFound.Found found(Row r) {
        LibraryScanner.Title t = r.title;
        if (t == null || t.base == null || !t.base.isNup()) return null;
        List<UpdateFound.Part> parts = new ArrayList<>();
        for (LibraryScanner.Source s : t.updates) parts.add(new UpdateFound.Part(UpdateFound.UPDATE, s.titleId, s.version, !s.needsImport()));
        for (LibraryScanner.Source s : t.dlcs) parts.add(new UpdateFound.Part(UpdateFound.DLC, s.titleId, s.version, !s.needsImport()));
        return UpdateFound.of(parts, t.profileBuilds.keySet());   // (not supportedBuilds: a BUILD title's only, empty for this READY one)
    }

    /** The update's name as the game's page shows it (the profile's), else its number */
    private static String updateName(Row r, UpdateFound.Found f) {
        String n = r.title == null ? null : r.title.profileBuilds.get(f.update);
        return n != null && !n.isEmpty() ? n : String.valueOf(f.update);
    }

    /** Ask, then build: Build takes the finds in (Import again, whose build keeps what did not change), Play plays as it is, Not now asks no more for these */
    private void askFound(Row r, UpdateFound.Found f) {
        AlertDialog.Builder d = new AlertDialog.Builder(a).setTitle(UpdateFound.what(f, updateName(r, f))).setMessage(UpdateFound.ask(f, r.name()))
                .setPositiveButton("Build", (x, w) -> GameSheet.importAgain(a, this, r.title, CompanionPort.forTitle(catalog, r.id()), null, f))
                .setNegativeButton("Not now", (x, w) -> { PeanutsPrefs.foundSeen(a, r.id(), f.key()); render(); });
        if (r.game != null) d.setNeutralButton("Play", (x, w) -> play(r));
        d.show();
    }

    private void play(Row r) {
        // PN_BUILD_ID as the scan sets it: a game added without a folder scan (picked, or by adb) has none, and a companion module that lists whole build ids refuses it
        File dump = r.title != null && r.title.launch != null ? r.title.launch : r.entry != null && r.entry.gameDir != null && !r.entry.gameDir.isEmpty() ? new File(r.entry.gameDir) : null;
        if (dump != null) dev.peanuts.build.GameEnv.ensureBuildId(a, r.id(), dump);
        FontGate.check(a, r.id(), r.name(), () -> {
            played = r.id(); playedAt = android.os.SystemClock.uptimeMillis();   // Home's next resume puts the focus on this cover (watch)
            BottomScreen.launching(r.id(), r.name(), icons.get(r.id()), startsCompanion(r));
            if (Upstream.launch(a, r.game)) Motion.launched(a);
            else { BottomScreen.launchCancelled(); Haptics.play(anchor(), Haptics.Word.REJECT); manage.accept(r.game); }
        });
    }

    /** A pill on the cover, 8 dp in from its corner: 12 sp 700, a 1 dp shadow so it reads on any art */
    private TextView mark(CharSequence s, int fill, int textRes, boolean stroke, int gravity) {
        TextView t = PUi.text(a, s, 12, textRes, 700);
        t.setSingleLine(true);
        t.setBackground(PUi.round(fill, PUi.dp(a, 999), stroke ? PUi.col(a, R.color.bn_card_stroke) : 0, stroke ? Math.max(1, PUi.dp(a, 1)) : 0));
        t.setPadding(PUi.dp(a, 10), PUi.dp(a, 5), PUi.dp(a, 10), PUi.dp(a, 5));
        t.setElevation(PUi.dp(a, 1));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        int p8 = PUi.dp(a, 8);
        lp.setMargins(p8, p8, p8, p8);
        t.setLayoutParams(lp);
        return t;
    }

    /** The building cover's 6 dp progress strip along the bottom edge, 8 dp inset: banana deep on 70 % of the screen's colour; indeterminate for upstream's Prepare */
    private ProgressBar strip(boolean indeterminate) {
        ProgressBar p = Strip.of(a, (PUi.col(a, R.color.bn_bg) & 0x00FFFFFF) | 0xB3000000, indeterminate);
        p.setMax(100);
        p.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, PUi.dp(a, 6), Gravity.BOTTOM);
        int p8 = PUi.dp(a, 8);
        lp.setMargins(p8, 0, p8, p8);
        p.setLayoutParams(lp);
        return p;
    }

    /** The green two-screen badge, bottom-right of a cover whose game has a companion installed (above the strip while it builds) */
    private View companionBadge(boolean aboveStrip) {
        LinearLayout v = new LinearLayout(a);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackground(PUi.round(PUi.col(a, R.color.bn_leaf), PUi.dp(a, 6), 0, 0));
        v.setElevation(PUi.dp(a, 1));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        for (int i = 0; i < 2; i++) {
            View s = new View(a);
            s.setBackground(PUi.round(PUi.col(a, R.color.bn_on_leaf), PUi.dp(a, 1), 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(PUi.dp(a, 12), PUi.dp(a, 5));
            if (i > 0) lp.topMargin = PUi.dp(a, 2);
            v.addView(s, lp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(PUi.dp(a, 22), PUi.dp(a, 22), Gravity.BOTTOM | Gravity.END);
        int p8 = PUi.dp(a, 8);
        lp.setMargins(p8, p8, p8, aboveStrip ? PUi.dp(a, 20) : p8);
        v.setLayoutParams(lp);
        return v;
    }

    /** As the game's process decides (Companion.firstPage): a package beside the pack names this title, its second screen is not Off */
    private boolean startsCompanion(Row r) {
        if (dev.peanuts.settings.CompanionSection.secondScreen(a, r.id()).equals(dev.peanuts.settings.CompanionSection.OFF)) return false;
        File pack = r.title == null && !r.entry.packDir.isEmpty() ? new File(r.entry.packDir) : null;
        File root = r.title != null ? r.title.companionRoot() : pack == null || pack.getParentFile() == null ? null : new File(pack.getParentFile(), "peanuts");
        return root != null && dev.peanuts.settings.CompanionSection.packageFor(root, r.id()) != null;
    }

    /** Whether a companion is installed for this title (Installer.installedVersion under the title's companion root), cached until the next scan or build */
    private boolean companionInstalled(Row r) {
        Boolean c = companions.get(r.id());
        if (c == null) {
            CompanionPort.Item it = CompanionPort.forTitle(catalog, r.id());
            File pack = r.title == null && !r.entry.packDir.isEmpty() ? new File(r.entry.packDir) : null;
            File root = r.title != null ? r.title.companionRoot() : pack == null || pack.getParentFile() == null ? null : new File(pack.getParentFile(), "peanuts");
            c = it != null && root != null && CompanionPort.installedVersion(root, it.packageName) != null;
            companions.put(r.id(), c);
        }
        return c;
    }

    // ---- Bottom screen ----

    /** Asks first, Cancel focused */
    private Runnable stopAsk(String name) {
        return () -> PUi.confirmDanger(a, "Stop the build?", "The build of " + name + " stops. What it made so far is kept, and the next build goes on from there.",
                "Stop", () -> { Haptics.play(anchor(), Haptics.Word.CLICK); BuildPipeline.cancel(a); });
    }

    /** The focused cover's card; A = the cover's tap, Y = its second action */
    private void bottom(Row r, Runnable tap, Runnable hold) {
        BottomScreen.Card k = new BottomScreen.Card();
        k.titleId = r.id(); k.name = r.name(); k.icon = icons.get(r.id());
        Integer at = gridPos.get(r.id());
        k.pos = at == null ? -1 : at; k.cols = gridCols;
        int st = r.status();
        BuildQueue.Item qi = isBuilding(r) && !(r.game != null && shows.test(r.game)) ? queueItem(r.id()) : null;
        if (qi != null && qi.waiting()) {   // waits in the build queue (issue #348): its place, and A removes it
            k.kind = BottomScreen.Card.K_BUILD; k.queued = true;
            k.state = queuedWords(qi);
            k.found = "It builds when the games before it are done.";
            k.primary = tap; k.primaryLabel = "Build card";   // A: the cover's tap
            k.second = () -> removeAsk(qi); k.secondLabel = "Remove from queue";   // Y: as Stop is a building cover's Y
            BottomScreen.focus(k, null);
            return;
        }
        if (isBuilding(r)) {
            k.kind = BottomScreen.Card.K_BUILDING;
            BottomScreen.focus(k, qi != null ? buildInfo(stoppedBuild(qi), r.name()) : buildInfo(r.id(), r.name()));
            return;
        }
        if (st == READY) {
            k.kind = BottomScreen.Card.K_READY; k.state = "Ready";
            k.companion = companionVersion(r); k.played = r.used(); k.play = play(r.id());
            k.primary = tap; k.primaryLabel = r.game != null ? "Play" : "Open"; k.primaryIcon = r.game != null ? R.drawable.ic_play : 0;
            if (r.game != null) { k.second = hold; k.secondLabel = "Game page"; }
            UpdateFound.Found f = found(r);   // as on the cover: what was found, and A asks first while it is not "Not now"
            if (f != null) {
                boolean ask = !f.key().equals(PeanutsPrefs.foundSeen(a, r.id()));
                k.update = UpdateFound.what(f, updateName(r, f)) + (ask ? "" : ", for later");
                if (ask) { k.primaryLabel = "Update…"; k.primaryIcon = R.drawable.ic_build; }
            }
        } else if (st == BUILD) {
            k.kind = BottomScreen.Card.K_BUILD;
            LibraryScanner.VersionState vs = r.title == null ? LibraryScanner.VersionState.UNKNOWN : r.title.versionState;
            k.state = vs == LibraryScanner.VersionState.FILE_GONE ? "File gone" : vs == LibraryScanner.VersionState.UNSUPPORTED ? "Wrong version" : "Build needed";
            k.found = found(r.title);
            k.primary = tap; k.primaryLabel = "Build…"; k.primaryIcon = R.drawable.ic_build;   // the sheet, as the tap
        } else {
            k.kind = BottomScreen.Card.K_NO_PROFILE; k.state = a.getString(R.string.not_supported_yet);
            if (r.game != null) { k.second = hold; k.secondLabel = "Game page"; }
        }
        BottomScreen.focus(k, null);
    }

    /** Build page; upstream's Prepare only points to its card */
    private BottomScreen.BuildInfo buildInfo(String titleId, String name) {
        BottomScreen.BuildInfo i = new BottomScreen.BuildInfo();
        i.titleId = titleId; i.name = name; i.icon = icons.get(titleId);
        i.open = () -> { filter = BUILDING; focusGrid = true; animateNext = true; render(); };
        i.screensOff = () -> ScreensOff.show(a);
        Build b = build;
        if (b == null || !titleId.equalsIgnoreCase(b.titleId)) {
            i.step = PUi.joined("Building", "in progress"); i.body = "Its progress is on the game's card."; i.indeterminate = true;
            return i;
        }
        return buildInfo(b, i);
    }

    /** The bottom screen's page of a build the queue stopped (its item, not the build card's own) */
    private BottomScreen.BuildInfo buildInfo(Build b, String name) {
        BottomScreen.BuildInfo i = new BottomScreen.BuildInfo();
        i.titleId = b.titleId; i.name = name; i.icon = icons.get(b.titleId);
        i.open = () -> { filter = BUILDING; focusGrid = true; animateNext = true; render(); };
        i.screensOff = () -> ScreensOff.show(a);
        return buildInfo(b, i);
    }

    private BottomScreen.BuildInfo buildInfo(Build b, BottomScreen.BuildInfo i) {
        String name = i.name;
        i.percent = b.percent; i.failed = b.failed; i.step = stepWords(b);
        if (b.failed) {
            // details and fixes stay on the build card
            i.body = BuildListener.CANCELLED.equals(b.code) ? "The build stopped. Tap Resume on the game's card to go on." : "The build stopped. The game's card says why and what to do.";
        } else {
            String d = b.detail.trim();
            i.body = (d.isEmpty() ? "Working" : d.endsWith(".") ? d.substring(0, d.length() - 1) : d) + ". It goes on with the screens off.";
            i.stop = stopAsk(name);
        }
        return i;
    }

    private void pushBuild() {
        Build b = build;
        if (b == null) { BottomScreen.build(null); return; }
        LibraryScanner.Title t = titleFor(b.titleId);
        BottomScreen.build(buildInfo(b.titleId, t != null ? t.name : b.name));
    }

    /** "Found the game and update 1.4.1." */
    private static String found(LibraryScanner.Title t) {
        if (t == null || t.base == null) return "";
        if (t.versionState == LibraryScanner.VersionState.FILE_GONE) return "Its file is no longer where it was found.";
        long u = 0;
        for (LibraryScanner.Source s : t.updates) u = Math.max(u, s.version);
        List<String> items = new ArrayList<>();
        // a dump folder carries its update inside
        items.add(u == 0 && t.base.version > 0 ? "the game at " + versionWords(t, t.base.version) : "the game");
        if (u > 0) items.add("update " + versionWords(t, u));
        int n = t.dlcs.size();
        if (n > 0) items.add(n == 1 ? "1 DLC" : n + " DLC");
        StringBuilder s = new StringBuilder("Found ");
        for (int i = 0; i < items.size(); i++) s.append(i == 0 ? "" : i == items.size() - 1 ? " and " : ", ").append(items.get(i));
        s.append('.');
        if (t.versionState == LibraryScanner.VersionState.UNSUPPORTED && t.recommendedVersion > 0) s.append(" This app supports ").append(versionWords(t, t.recommendedVersion)).append('.');
        return s.toString();
    }

    /** "1.4.1" if the profile names it, else "v5" */
    private static String versionWords(LibraryScanner.Title t, long v) {
        String d = t.supportedBuilds.get(v);
        if (d != null && !d.isEmpty()) return d;
        return (v & 0xFFFF) == 0 ? "v" + (v >> 16) : "v" + v;
    }

    private final Map<String, String> companionVersions = new HashMap<>();
    private final Map<String, Integer> gridPos = new HashMap<>();   // cover order: the bottom screen slides the way the D-pad went
    private int gridCols = 1;

    /** Installed companion version, null if none; cached like companionInstalled */
    private String companionVersion(Row r) {
        boolean fresh = !companions.containsKey(r.id());
        if (!companionInstalled(r)) return null;
        String v = fresh ? null : companionVersions.get(r.id());
        if (v == null) {
            CompanionPort.Item it = CompanionPort.forTitle(catalog, r.id());
            File pack = r.title == null && !r.entry.packDir.isEmpty() ? new File(r.entry.packDir) : null;
            File root = r.title != null ? r.title.companionRoot() : pack == null || pack.getParentFile() == null ? null : new File(pack.getParentFile(), "peanuts");
            v = it == null || root == null ? null : CompanionPort.installedVersion(root, it.packageName);
            if (v == null) v = "";
            companionVersions.put(r.id(), v);
        }
        return v.isEmpty() ? null : v;
    }

    // ---- The build card (Ondevice's shape) ----

    private static CharSequence stepWords(Build b) {
        if (b.failed) return "Build stopped";
        switch (b.step) {
            case "prepare": return PUi.joined("Building", "making the game");
            case "companion": return PUi.joined("Building", "installing the dual-screen companion");
            case "done": return "Ready";
            default: return PUi.joined("Building", "importing the game");
        }
    }

    private View buildCard(Build b) {
        LibraryScanner.Title t = titleFor(b.titleId);
        String name = t != null ? t.name : b.name;
        LinearLayout c = PUi.card(a);
        int p16 = PUi.dp(a, 16);
        c.setPadding(p16, p16, p16, p16);
        LinearLayout top = PUi.row(a);
        TextView tile = PUi.tile(a, name, 52);
        Bitmap bm = icons.get(b.titleId);
        if (bm != null) PUi.tileIcon(tile, bm);
        tiles.put(b.titleId, tile);
        top.addView(tile);
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(PUi.dp(a, 14), 0, PUi.dp(a, 4), 0);
        TextView n = PUi.text(a, name, 17, R.color.bn_text, 700);
        n.setMaxLines(2);
        n.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(n);
        CharSequence about = t == null ? "" : PUi.joined(t.versionText(), t.base != null ? t.base.publisher : null);
        if (about.length() > 0) info.addView(PUi.small(a, about));
        buildStatus = PUi.status(a, "", R.color.bn_accent);
        buildStatus.setPadding(0, PUi.dp(a, 2), 0, 0);
        info.addView(buildStatus);
        top.addView(info, PUi.weight());
        top.addView(PUi.more(a, "Manage " + name, Haptics.on(Haptics.Word.CLICK, v -> { if (t != null) sheet(t); })));
        c.addView(top);
        buildBody = PUi.body(a, "");
        PUi.add(c, buildBody, 12);
        buildBar = Strip.card(a);
        buildBar.setMax(100);
        PUi.add(c, buildBar, 10);
        buildPct = PUi.small(a, "");
        PUi.add(c, buildPct, 6);
        PUi.Details details = PUi.details(a, b.failed ? b.error : "");
        PUi.add(c, details, 4);
        View right;
        View left;
        if (b.failed) {
            // Every button here must do what its label says (found in review — before this, "Build anyway"/"Pick the recommended
            // update"/"Pick the file again"/"Resume" all just reopened the sheet, same as plain "Retry"). Only two actions are actually honest to offer from
            // the card without a file picker of its own: open the sheet (unchanged — it already has the Version row, the re-pick chevrons and the relabeled
            // Build/"Build anyway" button, so "open the sheet" is never a dead end), or really resume the job (now implemented, below)
            Runnable openSheet = () -> { if (t != null) sheet(t); };
            String code = b.reimport && b.step.equals("import") && !BuildListener.OUT_OF_SPACE.equals(b.code) ? BuildListener.FILE_MISSING : b.code;   // an import again that stopped: the sheet has Import again; Resume would build from the old .nup
            switch (code) {
                case BuildListener.ADDON_MISSING: {
                    // Prepare stopped and asks (dev.peanuts.build.AutoRelax): the shaders come with add-on content that was not imported. What mends it, by
                    // what is there: no update and DLC folder set, its folder picker; DLC found for the game, Import again; else the sheet (its DLC row adds a file).
                    // Try anyway is the other choice, asked once with what it costs
                    boolean nup = t != null && t.base != null && t.base.isNup();
                    if (nup && PeanutsPrefs.addonDirs(a).isEmpty() && !GameSheet.dlcFound(t))
                        left = PUi.button(a, "Add a DLC folder", PUi.Kind.TONAL, Haptics.on(Haptics.Word.TICK, v -> LibraryFolders.pick(a, dev.peanuts.folders.FolderKinds.ADDONS, false)));
                    else if (nup && GameSheet.dlcFound(t))
                        left = PUi.button(a, "Import again with DLC", PUi.Kind.TONAL, Haptics.on(Haptics.Word.TICK, v -> GameSheet.importAgain(a, this, t, CompanionPort.forTitle(catalog, t.titleId), null)));
                    else left = PUi.button(a, "Open the game's sheet", PUi.Kind.TONAL, Haptics.on(Haptics.Word.CLICK, v -> openSheet.run()));
                    right = PUi.button(a, "Try anyway", PUi.Kind.SOFT, v -> PUi.confirmDanger(a, "Try anyway?", "This builds " + name + " without the shaders that come with its DLC. "
                            + "The base game runs, and what the DLC adds is drawn without them. Import again with DLC makes them later. "
                            + "Your game files and saves do not change.", "Try anyway", () -> tryAnyway(t)));
                    break;
                }
                case BuildListener.FILE_MISSING:
                case BuildListener.FILE_DAMAGED:
                case BuildListener.WRONG_VERSION:
                case BuildListener.INVALID_DUMP:
                    left = PUi.button(a, "Open the game's sheet", PUi.Kind.TONAL, Haptics.on(Haptics.Word.CLICK, v -> openSheet.run()));
                    right = null;
                    break;
                case BuildListener.OUT_OF_SPACE:
                    left = PUi.button(a, "Free up space", PUi.Kind.TONAL, v -> {
                        Haptics.play(v, Haptics.Word.CLICK);
                        try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)); }
                        catch (RuntimeException e) { Toast.makeText(a, "Open Settings › Library › Storage to free some space.", Toast.LENGTH_LONG).show(); }
                    });
                    right = PUi.button(a, "Retry", PUi.Kind.SOFT, Haptics.on(Haptics.Word.CLICK, v -> openSheet.run()));
                    break;
                case BuildListener.STALLED:
                case BuildListener.CANCELLED:
                    left = PUi.button(a, "Resume", PUi.Kind.TONAL, v -> resumeBuild(t));   // its haptic is resumeBuild's: the weighty word when it starts, the refusal when not
                    right = null;
                    break;
                default:
                    left = PUi.button(a, "Retry", PUi.Kind.TONAL, Haptics.on(Haptics.Word.CLICK, v -> openSheet.run()));
                    right = null;
            }
        } else {
            left = PUi.button(a, "Stop", PUi.Kind.TONAL, v -> stopAsk(name).run());
            PUi.Action play = PUi.action(a, R.drawable.ic_play, "Play", PUi.Kind.PRIMARY, null);
            play.setEnabled(false);
            right = play;
        }
        PUi.add(c, PUi.pair(a, left, right), 14);
        // a heat pause Go on anyway lifts (#803): the card's own button, first under the bar; the body says why (Prepare.pausedLine)
        if (!b.failed) {
            buildGoOn = PUi.button(a, "Go on anyway", PUi.Kind.PRIMARY, v -> { Haptics.play(v, Haptics.Word.HEAVY); BuildPipeline.goOnAnyway(a); v.setVisibility(View.GONE); });
            buildGoOn.setContentDescription("Go on anyway: build " + name + " without pausing for heat");
            buildGoOn.setVisibility(View.GONE);
            PUi.add(c, buildGoOn, 10);
        }
        // a build that stopped or failed: its build log, or its newest game run when it has one too (BuildLogUi.shareLog asks which)
        if (b.failed) {
            LinearLayout quiet = PUi.row(a);
            quiet.addView(PUi.button(a, "Share log", PUi.Kind.QUIET, Haptics.on(Haptics.Word.TICK, v -> BuildLogUi.shareLog(a, b.titleId, name, null, null))), PUi.wrap());
            // the build queue keeps a stopped game's card until it is built again (issue #348): Dismiss lets it go, and nothing else changes
            BuildQueue q = BuildPipeline.queueNow();
            BuildQueue.Item qi = q == null ? null : q.get(b.id);
            if (qi != null && qi.stopped()) {
                LinearLayout.LayoutParams dl = PUi.wrap();
                dl.leftMargin = PUi.dp(a, 8);
                quiet.addView(PUi.button(a, "Dismiss", PUi.Kind.QUIET, v -> {
                    Haptics.play(v, Haptics.Word.TICK);
                    if (build != null && build.id == b.id) build = null;
                    if (!BuildPipeline.remove(a, b.id)) render();
                }), dl);
            }
            PUi.add(c, quiet, 8, PUi.wrap());
        }
        updateBuildCard(b, true);
        return c;
    }

    /** The build's state into the card and the cover that show it; fresh: the views were just built (a bar shows its value as it is, later values animate their fill) */
    private void updateBuildCard(Build b, boolean fresh) {
        if (coverChip != null) {   // the building cover's chip and strip (the Building filter not chosen)
            coverChip.setText(b.failed ? "Build stopped" : PUi.joined("Building", b.percent + " %"));
            coverChip.setTextColor(PUi.col(a, b.failed ? R.color.bn_danger : R.color.bn_accent));
            if (coverStrip != null) { coverStrip.setVisibility(b.failed ? View.GONE : View.VISIBLE); if (!coverStrip.isIndeterminate()) Motion.progress(coverStrip, b.percent, fresh); }
        }
        if (buildStatus == null) return;
        PUi.status(buildStatus, stepWords(b), b.failed ? R.color.bn_danger : b.step.equals("done") ? R.color.bn_leaf : R.color.bn_accent);
        if (buildGoOn != null) buildGoOn.setVisibility(!b.failed && BuildPipeline.goOnOffered(b.titleId) ? View.VISIBLE : View.GONE);   // #803
        if (b.failed) {
            String first = b.error.split("\n")[0].trim();
            LibraryScanner.Title bt = titleFor(b.titleId);
            boolean btNup = bt != null && bt.base != null && bt.base.isNup();
            String action = b.reimport && b.step.equals("import") && !BuildListener.OUT_OF_SPACE.equals(b.code) ? " Open the game's sheet and tap Import again."
                    : BuildListener.ADDON_MISSING.equals(b.code) ? (btNup && PeanutsPrefs.addonDirs(a).isEmpty() && !GameSheet.dlcFound(bt) ? " No update and DLC folder is set. Tap Add a DLC folder, then import the game again."
                            : btNup && GameSheet.dlcFound(bt) ? " DLC for this game was found. Tap Import again with DLC to add it." : " Add the DLC on the DLC row of the game's sheet.")
                    : BuildListener.FILE_DAMAGED.equals(b.code) ? ""   // its message says what to do (Compressed.failure)
                    : BuildListener.FILE_MISSING.equals(b.code) ? " Open the game's sheet to pick the file again."
                    : BuildListener.WRONG_VERSION.equals(b.code) ? (first.contains("Build anyway") ? "" : " To try it anyway, open the game's sheet and tap Build anyway. Or pick the update the sheet recommends.")
                    : BuildListener.INVALID_DUMP.equals(b.code) ? (bt != null && bt.base != null && dev.peanuts.build.VersionFolder.is(bt.base.file) ? ""   // its message says where the files go (GitHub issue #370)
                            : " Details says why. Open the game's sheet to check the files, or dump the game again.")
                    : BuildListener.OUT_OF_SPACE.equals(b.code) ? (first.contains("Free up") || first.contains("Free some") ? "" : " Free up some space, then tap Retry.")   // the packer's own sentence says "Free some space" (PrepareText)
                    : BuildListener.STALLED.equals(b.code) || BuildListener.CANCELLED.equals(b.code) ? (first.contains("Resume") ? "" : " Tap Resume to go on from where it stopped.")
                    : BuildListener.TOOL_STOPPED.equals(b.code) || first.contains("Tap ") || first.contains(" tap ") ? "" : " Tap Retry to build again.";   // a first line that says what to tap gets no second instruction
            buildBody.setText((first.isEmpty() ? "The build stopped." : first) + action);
            buildBar.setVisibility(View.GONE);
            buildPct.setVisibility(View.GONE);
        } else {
            String d = b.detail.trim();
            buildBody.setText((d.isEmpty() ? "Working" : d.endsWith(".") ? d.substring(0, d.length() - 1) : d) + "." + GOES_ON);
            buildBar.setVisibility(View.VISIBLE);
            Motion.progress(buildBar, b.percent, fresh);
            buildPct.setVisibility(View.VISIBLE);
            buildPct.setText(b.percent + " %");
        }
    }

    // ---- The sheet and the build ----

    private void sheet(LibraryScanner.Title t) {
        if (t == null) return;
        GameSheet.show(a, t, CompanionPort.forTitle(catalog, t.titleId), this);
    }

    /** Whether this title is being built now */
    boolean building(String titleId) { Build b = build; return b != null && !b.failed && b.titleId.equalsIgnoreCase(titleId); }

    /** Whether this title waits in the build queue: its item (its place), else null */
    static BuildQueue.Item queued(String titleId) { return BuildPipeline.queued(titleId); }

    /** The sheet's Build: the state to the pipeline, which builds it now or puts it into the queue; the card shows the rest. Returns why it could not start, or null */
    String startBuild(GameSheet.State s) { return startBuild(s, true); }

    /** Same: a toast says the place of a game that goes into the queue, and the notification permission is asked */
    String startBuild(GameSheet.State s, boolean say) {
        String why = refusal(s);
        if (why != null) return why;
        final int[] mine = {0};   // this request's id once the pipeline took it: its own end and progress go to the card of that build only
        int id = BuildPipeline.start(a, request(s), s.buildAnyway, startListener(mine));   // a refusal comes back through this (id 0); a started build's listener is attached in took
        mine[0] = id;
        if (id == 0) return null;   // the pipeline says why, in its own dialog
        took(s, id, say);
        render();
        if (say) askNotify();
        return null;
    }

    /**
     * Build all's games into the pipeline as one change ({@link BuildPipeline#startAll}): the queue's file written once and Home drawn once. One {@link
     * #startBuild} a game drew Home again for each of them, and the queue's watcher once more each (about 100 rebuilds of the whole grid for 50 games, on the
     * main thread). Returns how many build now or wait; a refusal by the pipeline says why in its own dialog, as startBuild's
     */
    private int startBuilds(List<GameSheet.State> states) {
        List<GameSheet.State> go = new ArrayList<>();
        List<BuildRequest> reqs = new ArrayList<>();
        List<Boolean> anyway = new ArrayList<>();
        List<BuildListener> ls = new ArrayList<>();
        List<int[]> mines = new ArrayList<>();
        for (GameSheet.State s : states) {
            if (refusal(s) != null) continue;
            int[] mine = {0};
            go.add(s); reqs.add(request(s)); anyway.add(s.buildAnyway); ls.add(startListener(mine)); mines.add(mine);
        }
        if (go.isEmpty()) return 0;
        int[] ids = BuildPipeline.startAll(a, reqs, anyway, ls);
        boolean any = false;
        for (int k = 0; k < ids.length; k++) {
            mines.get(k)[0] = ids[k];
            if (ids[k] != 0) { took(go.get(k), ids[k], false); any = true; }
        }
        int queued = 0;
        for (GameSheet.State s : states) if (building(s.titleId) || BuildPipeline.queued(s.titleId) != null) queued++;
        if (any && queued == 0) render();   // (the caller draws Home when a game builds or waits)
        return queued;
    }

    /** Why this game's build cannot start now, null when it can */
    private String refusal(GameSheet.State s) {
        if (building(s.titleId)) return s.name + " is being built now. Wait for it to finish.";
        if (dev.peanuts.pack.PackImport.busy(s.titleId)) return "Import pack is working on " + s.name + ". Wait for it to finish, then build.";   // GitHub issue #44: the import writes the pack folder this build would use
        // Keys and firmware fonts are a one-time setup requirement (owner, 2026-11-13), checked here as a cheap file-presence gate for every entry point
        // that can start a build (the sheet's Build/Build anyway/Rebuild, and Resume below): GameSheet already disables its Build button for the same
        // reason, so this is only a last line of defense, never a per-build re-validation against the importer.
        if (!PeanutsPrefs.hasKeys(a)) return "Add your keys first in Settings › Console, then build.";
        if (!PeanutsPrefs.hasFonts(a)) return "Add your firmware first in Settings › Console, then build.";
        return null;
    }

    /** The sheet's state as the pipeline's request */
    private BuildRequest request(GameSheet.State s) {
        BuildRequest r = new BuildRequest();
        r.titleId = s.titleId; r.name = s.name; r.base = s.base; r.update = s.update;
        r.dlcs = new ArrayList<>(s.dlcs);
        r.gameDir = PeanutsPrefs.homeFor(a, s.base);   // the game folder that holds the game's file, else the first (issue #546)
        r.buildDestination = PeanutsPrefs.chosenBuildDir(a, s.titleId);
        r.companionPackage = s.installCompanion ? s.companionPackage : null;
        r.replaceNup = s.replaceNup; r.deleteOldFirst = s.deleteOldFirst;   // Import again (GameSheet.importAgain)
        return r;
    }

    /** The listener a start hands the pipeline: a refusal (mine[0] still 0) in a dialog, else what it hears goes to the card of that build */
    private BuildListener startListener(int[] mine) {
        return new BuildListener() {
            @Override public void progress(String step, int percent, String detail) { if (mine[0] != 0) listener(mine[0]).progress(step, percent, detail); }
            @Override public void done(boolean ok, String message, String code) {
                if (mine[0] != 0) { listener(mine[0]).done(ok, message, code); return; }
                if (dev.peanuts.ctl.Ctl.buildRefused(message)) return;   // a control command asked for this build: the refusal is its answer, not a dialog
                Haptics.play(anchor(), Haptics.Word.REJECT);
                new AlertDialog.Builder(a).setTitle("Build").setMessage(message).setPositiveButton("OK", null).show();
            }
        };
    }

    /** The pipeline took the game (id): the build card when it starts now, else its place in the queue (a toast when say) */
    private void took(GameSheet.State s, int id, boolean say) {
        if (BuildPipeline.running() == id) {
            build = new Build(id, s.titleId, s.name);
            build.reimport = s.replaceNup != null;
            BuildPipeline.attach(id, listener(id));
        } else {   // into the build queue (issue #348): its cover says its place; the card follows it when it starts (followQueue)
            if (build != null && build.failed && build.titleId.equalsIgnoreCase(s.titleId)) build = null;   // its stopped card: this build stands for it
            BuildQueue.Item qi = BuildPipeline.queued(s.titleId);
            String p = qi == null || BuildPipeline.queueNow() == null ? "" : BuildPipeline.queueNow().placeText(qi);
            if (say) Toast.makeText(a, s.name + (p.isEmpty() ? " waits in the build queue." : " is in the build queue, " + p + "."), Toast.LENGTH_LONG).show();
        }
    }

    /** The build's notifications (its progress, and its end while the app is not in front) need the permission; asked as upstream's Prepare asks (Ondevice) */
    private void askNotify() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)   // Android 12: no such permission, nothing to ask
            a.requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    /**
     * The failed card's "Resume" (STALLED/CANCELLED only): actually resumes the job instead of just reopening the sheet (found in review).
     * Rebuilds the same {@link GameSheet.State} the last build used, from the title and its persisted sheet choices (no open sheet needed — the job itself
     * was already saved by Prepare and picks up where it stopped, Prepare.java's job.json resume path), and hands it straight to {@link #startBuild}
     */
    private void resumeBuild(LibraryScanner.Title t) {
        if (t == null) { Haptics.play(anchor(), Haptics.Word.REJECT); Toast.makeText(a, "Open the game from Home to resume it.", Toast.LENGTH_LONG).show(); return; }
        GameSheet.State s = GameSheet.resumeState(a, t, CompanionPort.forTitle(catalog, t.titleId));
        String why = startBuild(s);
        if (why != null) { Haptics.play(anchor(), Haptics.Word.REJECT); new AlertDialog.Builder(a).setTitle("Build").setMessage(why).setPositiveButton("OK", null).show(); }
        else if (building(t.titleId)) Haptics.play(anchor(), Haptics.Word.HEAVY);   // it started (a refusal by the pipeline says so in its own dialog)
    }

    /**
     * The build card's "Try anyway" after its question (ADDON_MISSING): the same build again with the player's consent, which Prepare gives the stopped job
     * (Prepare.run0: a failed job whose failure offers Try anyway). The control interface's job try_anyway comes the same way (CtlPort.build)
     */
    private void tryAnyway(LibraryScanner.Title t) {
        if (t == null) { Haptics.play(anchor(), Haptics.Word.REJECT); Toast.makeText(a, "Open the game from Home to try it anyway.", Toast.LENGTH_LONG).show(); return; }
        GameSheet.State s = GameSheet.resumeState(a, t, CompanionPort.forTitle(catalog, t.titleId));
        s.buildAnyway = true;
        String why = startBuild(s);
        if (why != null) { Haptics.play(anchor(), Haptics.Word.REJECT); new AlertDialog.Builder(a).setTitle("Build").setMessage(why).setPositiveButton("OK", null).show(); }
        else if (building(t.titleId)) Haptics.play(anchor(), Haptics.Word.HEAVY);
    }

    // ---- First run ----

    /** emptyCard's shape: the badge, a heading, the four steps (P5: Updates & DLC folded in as its own numbered row, no longer a quiet
     *  footnote button), the four Setting rows, then the single "Show my games" action */
    private View setupCard() {
        LinearLayout c = PUi.card(a);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(PUi.dp(a, 20), PUi.dp(a, 28), PUi.dp(a, 20), PUi.dp(a, 20));
        ImageView im = new ImageView(a);
        im.setImageResource(R.drawable.ic_banana_happy);
        im.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(im, new LinearLayout.LayoutParams(PUi.dp(a, 80), PUi.dp(a, 80)));
        TextView h = PUi.heading(a, "Set up your games");
        h.setGravity(Gravity.CENTER);
        PUi.add(c, h, 12, PUi.wrap());
        TextView t = PUi.small(a, "Keys, firmware and folders stay on this device. Nothing is uploaded.");
        t.setGravity(Gravity.CENTER);
        PUi.add(c, t, 6, PUi.wrap());
        LinearLayout steps = PUi.row(a);
        steps.setGravity(Gravity.CENTER);
        String[] s = {"1  Keys", "2  Game folder", "3  Updates & DLC", "4  Firmware"};
        for (int k = 0; k < s.length; k++) {
            LinearLayout.LayoutParams lp = PUi.wrap();
            if (k > 0) lp.leftMargin = PUi.dp(a, 6);
            TextView chip = PUi.chip(a, s[k]);
            chip.setSingleLine(true);
            steps.addView(chip, lp);
        }
        PUi.add(c, steps, 16, PUi.wrap());
        boolean keys = PeanutsPrefs.hasKeys(a);
        List<String> games = PeanutsPrefs.gameDirs(a);
        String game = games.isEmpty() ? null : games.size() == 1 ? games.get(0) : games.size() + " game folders";
        // Keys and firmware fonts are a one-time setup requirement before Build, not per-build (owner, 2026-11-13): no longer worded "Optional" here, since
        // Build itself is gated on both (GameSheet, Library.startBuild). Browsing this screen (Show my games, below) still needs only the game folder.
        PUi.add(c, PUi.body(a, LibrarySettings.KEYS_FIRMWARE_GUIDANCE), 12);
        PUi.add(c, new PUi.Setting(a, "🔑", "Keys", Haptics.on(Haptics.Word.TICK, v -> LibrarySettings.pickKeys(a)))
                .value(keys ? "Added: prod.keys is in the app." : "Needed before building. Importing an NSP, NSZ, XCI or XCZ needs it; browsing your folders here does not.").chevron(a).view, 16);
        PUi.add(c, new PUi.Setting(a, "📁", "Game folder", Haptics.on(Haptics.Word.TICK, v -> LibraryFolders.pick(a, dev.peanuts.folders.FolderKinds.GAMES, false)))
                .value(game != null ? game : "Required. Every NSP, NSZ, XCI, XCZ, .nup and dump folder inside is shown on this screen.").chevron(a).view, 0);
        // P5: folded in as its own numbered step, same Setting-row shape as the other three, instead of a quiet text button below the action row
        List<String> addons = PeanutsPrefs.addonDirs(a);
        CharSequence updDlcValue = addons.isEmpty() ? "Point at your update and DLC files if they're not inside the game folder already. Skip if everything's together."
                : addons.size() == 1 ? addons.get(0) : addons.size() + " folders";
        PUi.add(c, new PUi.Setting(a, "⬆️", "Updates & DLC", Haptics.on(Haptics.Word.TICK, v -> LibraryFolders.pick(a, dev.peanuts.folders.FolderKinds.ADDONS, false))).value(updDlcValue).chevron(a).view, 0);
        PUi.add(c, new PUi.Setting(a, "🔤", "Firmware", Haptics.on(Haptics.Word.TICK, v -> LibrarySettings.pickFirmwareSource(a)))
                .value(LibrarySettings.fontsValue(a, "Needed before building. A missing font can crash some games at boot.")).chevron(a).view, 0);
        PUi.Action show = PUi.action(a, R.drawable.ic_add, "Show my games", PUi.Kind.PRIMARY, v -> {
            if (PeanutsPrefs.gameDir(a) == null) { Haptics.play(v, Haptics.Word.REJECT); Toast.makeText(a, "Pick the game folder first", Toast.LENGTH_LONG).show(); return; }
            Haptics.play(v, Haptics.Word.CLICK);
            PeanutsPrefs.setupDone(a, true);
            changed(a);
        });
        show.setEnabled(game != null);
        PUi.add(c, show, 16, PUi.fill());
        return c;
    }
}
