package dev.peanuts.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.nxnative.android.R;
import dev.peanuts.PeanutsPrefs;
import dev.peanuts.folders.FolderKind;
import dev.peanuts.folders.FolderKinds;
import dev.peanuts.folders.FolderList;
import dev.peanuts.setup.SetupSteps;
import dev.peanuts.setup.SetupSteps.Step;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The first-run setup, a page of its own on Home in place of the library (Library.render) until a game folder is set and the setup has ended. The app's
 * peanut (About's, SetupPeanut) stands on its round tile, whole on the welcome, and cracks a little further with each step done; at the end it opens. Beside
 * it (under it on a phone) one question at a time: a welcome, file access, the keys, the firmware, the game folders (with the update and DLC folders as a
 * link), and the end with the games found, where the shell springs open and its halves and kernels fall; then, as when switching users, the peanut cracks
 * once more over the whole window ("Setting up Peanuts Native") until the library has read the folders, and Home shows the games. Each screen has the one
 * button that leads on, and Later where a step may wait. The folders are the folder lists of Settings › Library (LibraryFolders), as chips with what the
 * library found in them. Home's header and upstream's file access card step aside meanwhile: the setup asks for the access in its first step. The second
 * screen explains the step (BottomScreen.setup). Controller: the D-pad over the step's choices, A, B the screen before; X opens Settings, as on Home.
 */
final class SetupPage {
    private SetupPage() {}

    static final int WIDE_DP = 600;

    private static Activity owner;
    private static LinearLayout root;          // the page in Home's box: the peanut's stage and the step, kept for the whole setup
    private static LinearLayout page;          // the step's own views beside (under) the stage: the one part built again, when what it shows changed
    private static FrameLayout tile;           // the peanut's round tile
    private static SetupPeanut peanut;         // one peanut for the whole setup: its seam runs on, never from the start again
    private static ValueAnimator sizing;       // the tile on its way to the next step's size (a phone's welcome has a larger one)
    private static String builtFor;            // the screen the page was built for (its size in dp): another builds it again
    private static String shown;               // what the step shows (signature): the same again builds nothing
    private static Step shownStep;
    private static Object lastFocus;           // the tag of the setup's view the focus was on last (Home's refresh takes the views out of the window and back)
    private static Activity focusWatched;
    private static View primary;               // the screen's button that leads on, where the focus goes when it shows
    private static View firstChoice;           // or, where the screen asks for a choice first, its first one
    private static boolean moved;              // the player went to another screen: the focus goes to its target
    private static boolean drawnWithAccess;    // All files access when the step was built: back from Android's page with it, the step is built again
    private static int fitH;                   // the page's height on this screen (fitHeight), kept for the next build's first frame
    private static boolean fitWide;            // and the orientation it was measured in
    private static View stageView;             // the peanut's column on a wide screen: as high as the screen (fitHeight)
    private static boolean waitTimed;          // the end's last wait is on its way (one timer, whatever Home builds again meanwhile)
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean covering;           // the last wait's cover is up (loading)
    private static ScrollView pageScroll;      // Home's ScrollView, not focusable while the setup shows: the focus never frames the whole page
    private static boolean pageFocusable;
    /** The last prod.keys picked was not taken (LibrarySettings.copyKeys): what the keys step says until another is picked */
    static boolean keysRejected;
    private static final java.util.Map<View, Boolean> HIDDEN = new java.util.WeakHashMap<>();

    /** Whether Home shows the setup: no game folder yet, or the setup not ended */
    static boolean needed(Activity a) { return PeanutsPrefs.gameDirs(a).isEmpty() || !PeanutsPrefs.setupDone(a); }

    // ---- State ----

    static SetupSteps.State state(Activity a) {
        SetupSteps.State st = new SetupSteps.State();
        st.access = Environment.isExternalStorageManager();
        st.keys = PeanutsPrefs.hasKeys(a) && !keysRejected;
        st.firmware = PeanutsPrefs.hasFonts(a);
        st.gameFolders = PeanutsPrefs.gameDirs(a).size();
        st.addonFolders = PeanutsPrefs.addonDirs(a).size();
        st.keysLater = PeanutsPrefs.setupMark(a, "keys_later");
        st.firmwareLater = PeanutsPrefs.setupMark(a, "firmware_later");
        st.furthest = SetupSteps.further(SetupSteps.of(PeanutsPrefs.setupStep(a)), SetupSteps.of(PeanutsPrefs.setupFurthest(a)));   // an older app's setup kept only its step
        return st;
    }

    /** The screen that shows: the one the player was on (kept while the setup is open), else the welcome */
    static Step current(Activity a) {
        Step s = SetupSteps.of(PeanutsPrefs.setupStep(a));
        if (s == null) SetupPeanut.reset();
        return s != null ? s : Step.WELCOME;
    }

    /** Another screen: only the step's part of the page changes (Home is not built again, nothing is scanned) */
    private static void go(Activity a, Step s) {
        PeanutsPrefs.setupStep(a, s.key);
        if (SetupSteps.further(s, SetupSteps.of(PeanutsPrefs.setupFurthest(a))) == s) PeanutsPrefs.setupFurthest(a, s.key);
        moved = true;
        if (root != null && root.getParent() instanceof LinearLayout) fill(a, (LinearLayout) root.getParent());
        else Library.changed(a);
    }

    // ---- The page ----

    /**
     * Home's box during the setup (Library.render, which runs whenever Home is built again or the library changed): the page is built once and kept, the
     * peanut with it; only the step's part is built again, when what it shows changed ({@link #signature}), and with a short fade when it is another step.
     * A controller's focus stays on the same choice, or goes to the screen's target on another step
     */
    static void fill(Activity a, LinearLayout box) {
        if (a != owner) { root = null; peanut = null; }   // another window: built for it
        owner = a;
        chrome(box, false);
        holdPageFocus(box);
        watchFocus(a);
        Step s = current(a);
        SetupSteps.State st = state(a);
        android.content.res.Configuration cf = a.getResources().getConfiguration();
        boolean wide = cf.screenWidthDp >= WIDE_DP && cf.screenWidthDp > cf.screenHeightDp;   // side by side on a landscape screen; a tablet held upright stacks, as a phone
        String screenNow = cf.screenWidthDp + "x" + cf.screenHeightDp;
        if (root == null || !screenNow.equals(builtFor)) build(a, cf, wide, screenNow);
        boolean back = root.getParent() != box;
        if (back) {   // Home built its box again (upstream's refresh empties it): the same page back in it
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
            box.removeAllViews();
            box.addView(root, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            fitHeight(box);
        }
        boolean other = s != shownStep;
        if (other) moved = true;
        String sig = signature(a, s, st);
        if (!sig.equals(shown)) {
            Step was = shownStep;
            shown = sig;
            shownStep = s;
            stage(a, s, wide, other && was != null);
            LinearLayout fresh = new LinearLayout(a);
            fresh.setOrientation(LinearLayout.VERTICAL);
            primary = null;
            firstChoice = null;
            drawnWithAccess = Environment.isExternalStorageManager();
            screen(a, fresh, s, st, wide);
            BottomScreen.setup(a, explain(a, s, st));
            View lead = firstChoice != null ? firstChoice : primary;
            if (lead != null) lead.setFocusedByDefault(true);   // a controller's first press lands here, not on the page (as Home's first cover)
            swap(a, box, fresh, wide, other && was != null ? (s.ordinal() > was.ordinal() ? 1 : -1) : 0);
        } else {
            if (back) BottomScreen.setup(a, explain(a, s, st));   // as it was (in place on the second screen)
            if (!box.isInTouchMode()) focus(a);
        }
        peanut.to(SetupSteps.crack(s, st), s == Step.DONE);
        if (s == Step.DONE && !waitTimed) {   // the end: its clock started with the peanut's, and the last wait comes when the kernels have fallen
            waitTimed = true;
            UI.postDelayed(() -> { if (owner != null && needed(owner) && current(owner) == Step.DONE) lastWait(owner); }, SetupPeanut.endLeft() + 80);
        }
        moved = false;
    }

    /** The page's frame, once for this screen: the stage with the peanut, and room for the step */
    private static void build(Activity a, android.content.res.Configuration cf, boolean wide, String screenNow) {
        if (root != null && root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
        if (sizing != null) { sizing.cancel(); sizing = null; }
        builtFor = screenNow;
        shown = null;
        shownStep = null;
        page = null;
        int side = !wide && cf.screenWidthDp > 560 ? PUi.dp(a, (cf.screenWidthDp - 520) / 2) : 0;   // a tablet held upright keeps the column a phone's width, in the middle
        root = new LinearLayout(a);
        root.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setPadding(side, 0, side, 0);
        root.setClipChildren(false);   // what falls at the end leaves the tile and fades below it
        boolean landscape = cf.screenWidthDp > cf.screenHeightDp;
        if (fitH > 0 && fitWide == landscape) root.setMinimumHeight(fitH);   // the height measured before, from the first frame
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClipChildren(false);
        tile = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(PUi.col(a, R.color.bn_tint));
        g.setStroke(PUi.dp(a, 1), PUi.col(a, R.color.bn_card_stroke));
        tile.setBackground(g);
        tile.setClipChildren(false);
        if (peanut != null && peanut.getParent() instanceof ViewGroup) ((ViewGroup) peanut.getParent()).removeView(peanut);
        if (peanut == null) peanut = new SetupPeanut(a);
        int room = Math.round(PUi.dp(a, wide ? 236 : 220) * SetupPeanut.ROOM);   // as large as the largest tile it stands on: a tile that changes size only scales the drawing
        tile.addView(peanut, new FrameLayout.LayoutParams(room, room, Gravity.CENTER));
        col.addView(tile, new LinearLayout.LayoutParams(0, 0));
        if (wide) {
            // the stage as high as the screen, not as the step: a step taller than the screen (it scrolls) leaves the peanut where every other step has it
            stageView = col;
            root.addView(col, new LinearLayout.LayoutParams(PUi.dp(a, 290), fitH > 0 && fitWide == landscape ? fitH : ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            stageView = null;
            root.addView(col, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** The tile's size for a step: the same on every step of a wide screen; a phone's welcome a larger one, the step after it eased to the smaller one */
    private static void stage(Activity a, Step s, boolean wide, boolean ease) {
        int disc = PUi.dp(a, wide ? 236 : (s == Step.WELCOME ? 220 : 170));
        int top = wide ? 0 : PUi.dp(a, s == Step.WELCOME ? 48 : 8);
        LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) tile.getLayoutParams();
        if (sizing != null) { sizing.cancel(); sizing = null; }
        if (tl.width == disc && tl.topMargin == top) return;
        if (!ease || tl.width == 0 || !Motion.on() || !ValueAnimator.areAnimatorsEnabled()) { size(tl, disc, top); return; }
        int d0 = tl.width, t0 = tl.topMargin;
        sizing = ValueAnimator.ofFloat(0f, 1f);
        sizing.setDuration(280);
        sizing.setInterpolator(new DecelerateInterpolator());
        sizing.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            size(tl, Math.round(d0 + (disc - d0) * f), Math.round(t0 + (top - t0) * f));
        });
        sizing.start();
    }

    private static void size(LinearLayout.LayoutParams tl, int disc, int top) {
        if (tile == null || peanut == null) return;
        tl.width = tl.height = disc;
        tl.topMargin = top;
        tile.setLayoutParams(tl);
        peanut.disc(disc);
    }

    /** What a step shows, in one string: the same twice, and the step is not built again (Home builds its box on every scan, resume and stale check) */
    private static String signature(Activity a, Step s, SetupSteps.State st) {
        StringBuilder b = new StringBuilder(s.key);
        b.append('|').append(st.keys).append(st.firmware).append(st.keysLater).append(st.firmwareLater).append(keysRejected).append(Environment.isExternalStorageManager());
        b.append('|').append(LibrarySettings.firmwareState()).append('|').append(PeanutsPrefs.firmwareDir(a));
        for (FolderKind k : new FolderKind[] {FolderKinds.GAMES, FolderKinds.ADDONS})
            for (String d : k.list(a)) b.append('|').append(k.key).append(':').append(d).append('=').append(holds(k, d));
        b.append('|').append(gamesFound());
        Library lib = Library.now();
        if (s == Step.DONE) b.append('|').append(lib != null && lib.scanningNow());
        return b.toString();
    }

    /**
     * The step's views in place of the last ones. Another step fades in, from the side it comes from (dir 1 on, -1 back, 0 the same step: at once). The
     * new views are in before the old ones go, and the focus is on its target already: Android never hands it to the page meanwhile
     */
    private static void swap(Activity a, LinearLayout box, LinearLayout fresh, boolean wide, int dir) {
        LinearLayout.LayoutParams lp;
        if (wide) {
            // at least as high as the screen (its buttons at the foot), higher when the step is: not MATCH_PARENT, which the stage's fixed height would
            // cut the step to (a horizontal LinearLayout takes its height from the children that do not match it)
            lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginStart(PUi.dp(a, 14));
            fresh.setMinimumHeight(fitWide ? fitH : 0);
        } else {
            lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        }
        LinearLayout old = page;
        page = fresh;
        root.addView(fresh, lp);
        if (!box.isInTouchMode()) focus(a);
        if (old != null) { old.animate().cancel(); root.removeView(old); }
        if (dir != 0 && Motion.on() && ValueAnimator.areAnimatorsEnabled()) {
            fresh.setAlpha(0f);
            if (wide) fresh.setTranslationX(PUi.dp(a, 18) * dir); else fresh.setTranslationY(PUi.dp(a, 12));
            fresh.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(wide ? 0 : 60).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /** The controller's focus: on the same choice again, else on the screen's target */
    private static void focus(Activity a) {
        View lead = firstChoice != null ? firstChoice : primary;
        View target = !moved && lastFocus != null ? root.findViewWithTag(lastFocus) : null;
        if (target == null || !target.isFocusable() || !target.isEnabled()) target = lead;
        View now = a.getCurrentFocus();
        if (target == null || !moved && now != null && now.isAttachedToWindow() && isIn(now, page)) return;
        target.requestFocus();
    }

    /** The tag of the view the focus is on, whenever it is one of the setup's: the same choice again after Home took the page out of the window and back */
    private static void watchFocus(Activity a) {
        if (focusWatched == a) return;
        focusWatched = a;
        a.getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> {
            if (n != null && root != null && n.getTag() != null && isIn(n, root)) lastFocus = n.getTag();
        });
    }

    private static boolean isIn(View v, View group) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null) if (x == group) return true;
        return false;
    }

    /** The setup is over, or Home is built for the library: its header and the access card show again */
    static void leave(LinearLayout box) {
        if (root != null && (root.getParent() == box || root.getParent() == null)) {   // also when upstream's refresh took it out of the box already
            if (sizing != null) { sizing.cancel(); sizing = null; }
            root = null;
            page = null;
            tile = null;
            peanut = null;
            stageView = null;
            shown = null;
            shownStep = null;
            lastFocus = null;
            if (owner != null) BottomScreen.setup(owner, null);   // the second screen: the logo or the cover the focus is on again
        }
        if (pageScroll != null) { pageScroll.setFocusable(pageFocusable); pageScroll = null; }
        chrome(box, true);
    }

    /** Home's ScrollView takes no focus while the setup shows: when a step's views go, Android would give it the focus and frame the whole page */
    private static void holdPageFocus(LinearLayout box) {
        if (pageScroll != null) return;
        for (android.view.ViewParent p = box.getParent(); p != null; p = p.getParent())
            if (p instanceof ScrollView) { pageScroll = (ScrollView) p; pageFocusable = pageScroll.isFocusable(); pageScroll.setFocusable(false); return; }
    }

    /** The launcher is in front again (LibrarySettings.resume, after upstream's onResume set its file access card): the setup keeps Home's top to itself */
    static void resumed(Activity a) {
        if (a != owner || root == null || !(root.getParent() instanceof LinearLayout) || !needed(a)) return;
        chrome((LinearLayout) root.getParent(), false);   // also before the window is attached (the first start)
        if (Environment.isExternalStorageManager() != drawnWithAccess) Library.changed(a);   // back from Android's All files access page: the step without its access chip, and a scan
    }

    /** Home's header and upstream's file access card (what stands before the box) hidden for the setup, shown again after it */
    private static void chrome(LinearLayout box, boolean show) {
        if (!(box.getParent() instanceof ViewGroup)) return;
        ViewGroup p = (ViewGroup) box.getParent();
        int at = p.indexOfChild(box);
        for (int i = 0; i < at; i++) {
            View v = p.getChildAt(i);
            if (!show) {
                if (v.getVisibility() == View.VISIBLE) { HIDDEN.put(v, Boolean.TRUE); v.setVisibility(View.GONE); }   // again after upstream's onResume showed its card
            } else if (HIDDEN.remove(v) != null) {
                boolean access = i == 1;   // the card right under the header: upstream's file access card, which its onResume shows again when it is needed
                v.setVisibility(access && Environment.isExternalStorageManager() ? View.GONE : View.VISIBLE);
            }
        }
    }

    /** The page fills the screen's height, so a screen's buttons sit at its bottom (the box is in a ScrollView: wrap_content alone stops at the content) */
    private static void fitHeight(LinearLayout box) {
        root.post(() -> {
            ScrollView sv = null;
            for (android.view.ViewParent p = box.getParent(); p != null && sv == null; p = p.getParent()) if (p instanceof ScrollView) sv = (ScrollView) p;
            if (sv == null || root == null || !root.isAttachedToWindow()) return;
            int[] at = new int[2], top = new int[2];
            root.getLocationInWindow(at);
            sv.getLocationInWindow(top);
            View content = sv.getChildAt(0);
            int below = sv.getPaddingBottom() + (content != null ? content.getPaddingBottom() : 0);   // the system bars' room the page keeps at its foot (a tablet's taskbar)
            int h = sv.getHeight() - (at[1] - top[1] + sv.getScrollY()) - Math.max(below, PUi.dp(sv.getContext(), 16));
            if (h <= 0) return;
            fitH = h;
            fitWide = sv.getResources().getConfiguration().screenWidthDp > sv.getResources().getConfiguration().screenHeightDp;
            if (root.getMinimumHeight() != h) root.setMinimumHeight(h);
            View st = stageView;
            if (st != null && st.getParent() == root && st.getLayoutParams().height != h) { st.getLayoutParams().height = h; st.requestLayout(); }
            if (st != null && page != null && page.getMinimumHeight() != h) page.setMinimumHeight(h);   // a wide screen's step: at least as high
        });
    }

    // ---- A screen ----

    private static void screen(Activity a, LinearLayout page, Step s, SetupSteps.State st, boolean wide) {
        List<View> buttons = new ArrayList<>();
        if (s == Step.WELCOME && wide) page.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
        if (s == Step.DONE && !wide) page.setPadding(0, PUi.dp(a, 14), 0, 0);
        String over = s == Step.WELCOME ? "Welcome" : s == Step.DONE ? "All set" : place(s);
        LinearLayout head = PUi.row(a);   // Back and the step's number on one line: a short screen keeps its height for the step
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (s != Step.WELCOME && s != Step.DONE) {
            View back = PUi.back(a, Haptics.on(Haptics.Word.TICK, v -> go(a, SetupSteps.previous(s))));
            back.setTag("back");
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(PUi.dp(a, 40), PUi.dp(a, 40));
            bl.setMarginEnd(PUi.dp(a, 10));
            head.addView(back, bl);
        }
        head.addView(PUi.section(a, over));
        PUi.add(page, head, s == Step.WELCOME || wide ? 0 : 14);
        TextView q = PUi.text(a, question(a, s), s == Step.WELCOME ? 34 : 28, R.color.bn_text, 800);
        q.setAccessibilityHeading(true);
        PUi.add(page, q, 4);
        PUi.add(page, PUi.text(a, line(a, s, st), 15, R.color.bn_text_2, 400), 8);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        PUi.add(page, body, 16, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        switch (s) {
            case WELCOME: welcome(a, body, buttons); break;
            case ACCESS: access(a, body, st, buttons); break;
            case KEYS: keys(a, body, st, buttons); break;
            case FIRMWARE: firmware(a, body, st, buttons, wide); break;
            case GAMES: games(a, body, st, buttons); break;
            default: done(a, body, st, buttons);
        }
        View spacer = new View(a);
        page.addView(spacer, new LinearLayout.LayoutParams(1, 0, (s == Step.WELCOME || s == Step.DONE) && wide ? 1 : 2));
        LinearLayout row = new LinearLayout(a);
        boolean welcomeWide = s == Step.WELCOME && wide;
        row.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        row.setGravity(welcomeWide ? Gravity.START | Gravity.CENTER_VERTICAL : wide ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.CENTER_HORIZONTAL);
        if (!wide) java.util.Collections.reverse(buttons);   // a phone: the button that leads on first, the quiet one under it
        for (View b : buttons) {
            LinearLayout.LayoutParams lp = wide ? PUi.wrap() : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (row.getChildCount() > 0) { if (wide) lp.setMarginStart(PUi.dp(a, 12)); else lp.topMargin = PUi.dp(a, 8); }
            row.addView(b, lp);
        }
        PUi.add(page, row, 14, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (s == Step.DONE && wide) page.addView(new View(a), 0, new LinearLayout.LayoutParams(1, 0, 1));   // the end's words in the middle, as the welcome's
        if (s == Step.WELCOME) {
            TextView foot = PUi.text(a, "Keys, firmware and folders stay on this device.", 12, R.color.bn_text_2, 400);
            if (!wide) foot.setGravity(Gravity.CENTER);
            PUi.add(page, foot, 12, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (wide) page.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1));
        }
    }

    private static String question(Activity a, Step s) {
        switch (s) {
            case WELCOME: return "Peanuts Native";
            case ACCESS: return "Allow file access";
            case KEYS: return "Add your keys";
            case FIRMWARE: return "Add your firmware";
            case GAMES: return "Where are your games?";
            default: {
                int n = gamesFound();
                Library lib = Library.now();
                return n > 0 ? count(n, "game") + " found" : lib != null && lib.scanningNow() ? "Looking for your games" : "Your games are set up";
            }
        }
    }

    private static String line(Activity a, Step s, SetupSteps.State st) {
        switch (s) {
            case WELCOME: return "Your own Switch games, built for this device. Four short steps: file access, your keys, your firmware, your games.";
            case ACCESS: return "The app reads your games and your firmware where you keep them, on this device or an SD card. Android asks for this once.";
            case KEYS: return "The prod.keys of your own console. The app opens NSP and XCI files with them; they never leave this device.";
            case FIRMWARE: return "Games take their fonts from it, Miis their parts. The same version as your keys.";
            case GAMES: return "Every folder with NSP, NSZ, XCI, XCZ, .nup files or dump folders. Add each place you keep games.";
            default:
                return "In " + count(st.gameFolders, "game folder") + (st.addonFolders > 0 ? ", with " + count(st.addonFolders, "update and DLC folder") : "")
                        + ". Anything here can be changed later in Settings.";
        }
    }

    private static void welcome(Activity a, LinearLayout body, List<View> buttons) {
        buttons.add(primary = main(a, "Set up", null, v -> go(a, SetupSteps.next(Step.WELCOME))));
    }

    /** File access: Android's page for the app; back from it with the access, the step is done (the peanut's first crack) and Continue leads on */
    private static void access(Activity a, LinearLayout body, SetupSteps.State st, List<View> buttons) {
        if (st.access) {
            body.addView(chip(a, "check", "File access allowed", null, Chip.DONE, null), PUi.wrap());
            buttons.add(primary = next(a, Step.ACCESS));
            return;
        }
        body.addView(PUi.text(a, "Android opens its page for this app: turn the switch on, then come back here.", 13, R.color.bn_text_2, 400));
        buttons.add(primary = main(a, "Allow access", "lock", v -> LibrarySettings.openAccessSettings(a)));
    }

    private static void keys(Activity a, LinearLayout body, SetupSteps.State st, List<View> buttons) {
        if (keysRejected) {
            body.addView(chip(a, "warning", "Not a prod.keys. Nothing was changed.", null, Chip.WARN, null), PUi.wrap());
            buttons.add(quiet(a, "Later", v -> later(a, "keys_later", Step.KEYS)));
            buttons.add(primary = main(a, "Choose another file", "key", v -> LibrarySettings.pickFile(a, LibrarySettings.REQ_KEYS, "Pick your prod.keys")));
        } else if (st.keys) {
            body.addView(chip(a, "check", "prod.keys added", null, Chip.DONE, null), PUi.wrap());
            buttons.add(quiet(a, "Choose another", v -> LibrarySettings.pickFile(a, LibrarySettings.REQ_KEYS, "Pick your prod.keys")));
            buttons.add(primary = next(a, Step.KEYS));
        } else {
            buttons.add(quiet(a, "Later", v -> later(a, "keys_later", Step.KEYS)));
            buttons.add(primary = main(a, "Choose prod.keys", "key", v -> LibrarySettings.pickFile(a, LibrarySettings.REQ_KEYS, "Pick your prod.keys")));
        }
    }

    private static void firmware(Activity a, LinearLayout body, SetupSteps.State st, List<View> buttons, boolean wide) {
        String state = LibrarySettings.firmwareState();
        String fw = PeanutsPrefs.firmwareDir(a);
        String name = fw == null ? "Firmware" : new File(fw).getName();
        boolean reading = state != null && (state.startsWith("Reading") || state.startsWith("Copying"));
        if (reading) {
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 20), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1)));
            card.setPadding(PUi.dp(a, 16), PUi.dp(a, 14), PUi.dp(a, 16), PUi.dp(a, 16));
            LinearLayout head = PUi.row(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(icon(a, "driver", 22));
            TextView t = PUi.text(a, name, 16, R.color.bn_text, 700);
            LinearLayout.LayoutParams tl = PUi.weight();
            tl.setMarginStart(PUi.dp(a, 10));
            head.addView(t, tl);
            card.addView(head);
            PUi.add(card, Strip.of(a, PUi.col(a, R.color.bn_card_stroke), true), 12, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(card);
            buttons.add(primary = quiet(a, "Later", v -> later(a, "firmware_later", Step.FIRMWARE)));
            return;
        }
        if (st.firmware) {
            body.addView(chip(a, "check", "Firmware added", null, Chip.DONE, null), PUi.wrap());
            buttons.add(quiet(a, "Choose another", v -> LibrarySettings.pickFirmwareSource(a)));
            buttons.add(primary = next(a, Step.FIRMWARE));
            return;
        }
        if (state != null && state.startsWith("Firmware not added: add your prod.keys")) {
            body.addView(chip(a, "warning", "Add your keys first: the firmware is read with them", null, Chip.WARN, null), PUi.wrap());
            buttons.add(quiet(a, "Later", v -> later(a, "firmware_later", Step.FIRMWARE)));
            buttons.add(primary = main(a, "Go to keys", "key", v -> go(a, Step.KEYS)));
            return;
        }
        if (state != null && state.startsWith("Waiting for file access")) {
            View access = chip(a, "lock", "Allow file access: the firmware is read then", null, Chip.WARN, v -> LibrarySettings.openAccessSettings(a));
            body.addView(access, PUi.wrap());
            firstChoice = access;
        } else if (state != null && state.startsWith("Firmware not added")) {
            body.addView(chip(a, "warning", "Not read. Pick your own console's firmware, the same version as your keys", null, Chip.WARN, null), PUi.wrap());
        }
        LinearLayout tiles = new LinearLayout(a);
        tiles.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        View folder = choice(a, "folder", "A folder", "Its NCA files, unpacked", v -> LibrarySettings.pickFolder(a, LibrarySettings.REQ_FIRMWARE, "Pick your firmware folder"));
        View zip = choice(a, "pack-in", "A .zip file", "As it comes", v -> LibrarySettings.pickFile(a, LibrarySettings.REQ_FIRMWARE_ZIP, "Pick your firmware zip", "application/zip"));
        LinearLayout.LayoutParams f = wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams z = wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1) : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (wide) z.setMarginStart(PUi.dp(a, 12)); else z.topMargin = PUi.dp(a, 10);
        tiles.addView(folder, f);
        tiles.addView(zip, z);
        PUi.add(body, tiles, body.getChildCount() > 0 ? 12 : 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (firstChoice == null) firstChoice = folder;
        buttons.add(primary = quiet(a, "Later", v -> later(a, "firmware_later", Step.FIRMWARE)));
    }

    private static void games(Activity a, LinearLayout body, SetupSteps.State st, List<View> buttons) {
        Flow chips = new Flow(a);
        if (!Environment.isExternalStorageManager()) {
            View access = chip(a, "lock", "Allow file access first", null, Chip.WARN, v -> LibrarySettings.openAccessSettings(a));
            chips.addView(access);
            firstChoice = access;
        }
        for (FolderKind k : new FolderKind[] {FolderKinds.GAMES, FolderKinds.ADDONS}) {
            for (String d : k.list(a)) {
                String held = holds(k, d);
                View c = chip(a, k.icon, shortPath(d), held, Chip.NORMAL, v -> LibraryFolders.folderSheet(a, k, d));
                c.setTag("folder:" + d);
                c.setContentDescription(k.title + ", " + d + (held != null ? ", " + held : ""));
                chips.addView(c);
            }
        }
        View add = chip(a, "add", "Add a folder", null, Chip.ADD, v -> LibraryFolders.pick(a, FolderKinds.GAMES, false));
        add.setTag("add:games");
        chips.addView(add);
        if (firstChoice == null) firstChoice = add;
        body.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View addons = link(a, "update", st.addonFolders == 0 ? "Updates and DLC in a folder of their own? Add it too" : "Add another update and DLC folder",
                v -> LibraryFolders.pick(a, FolderKinds.ADDONS, false));
        addons.setTag("add:addons");
        PUi.add(body, addons, 12, PUi.wrap());
        Button next = (Button) next(a, Step.GAMES);
        if (!SetupSteps.canGoOn(Step.GAMES, st)) { next.setEnabled(false); next.setAlpha(0.5f); }
        else primary = next;
        buttons.add(next);
    }

    /** The end: what is set up, and what was left (Settings › Console has it later); no button, it goes on by itself once the kernels have fallen */
    private static void done(Activity a, LinearLayout body, SetupSteps.State st, List<View> buttons) {
        Flow chips = new Flow(a);
        if (!st.keys) chips.addView(chip(a, "key", "Keys later: needed before your first build", null, Chip.WARN, null));
        if (!st.firmware) chips.addView(chip(a, "driver", "Firmware later: needed before your first build", null, Chip.WARN, null));
        if (chips.getChildCount() > 0) body.addView(chips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static final long COVER_MIN_MS = 1800, COVER_MAX_MS = 10000, COVER_POLL_MS = 200;

    /**
     * The end's kernels have fallen: over the whole window the app's peanut cracks once more with "Setting up Peanuts Native" under it (the cover of switching
     * users, ProfileButton), the setup is done, and the cover fades once the library has read the folders (at least as long as a user switch shows, at most
     * ten seconds). Touches and keys wait meanwhile
     */
    private static void lastWait(Activity a) {
        if (covering || a.isFinishing() || a.isDestroyed() || !SetupSteps.canFinish(state(a))) return;
        covering = true;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        FrameLayout cover = new FrameLayout(a);
        cover.setBackgroundColor(PUi.col(a, R.color.bn_bg));
        cover.setClickable(true);
        cover.setFocusable(true);
        LinearLayout mid = new LinearLayout(a);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        mid.addView(new CrackingPeanut(a), new LinearLayout.LayoutParams(PUi.dp(a, 180), PUi.dp(a, 180)));
        TextView t = PUi.text(a, "Setting up Peanuts Native", 20, R.color.bn_text, 700);
        t.setPadding(0, PUi.dp(a, 12), 0, 0);
        mid.addView(t, PUi.wrap());
        cover.addView(mid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        cover.setAlpha(0f);
        decor.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover.requestFocus();
        cover.animate().alpha(1f).setDuration(160).start();
        long start = android.os.SystemClock.uptimeMillis();
        cover.postDelayed(new Runnable() {
            @Override public void run() {
                long gone = android.os.SystemClock.uptimeMillis() - start;
                Library lib = Library.now();
                boolean reading = lib != null && lib.scanningNow();
                if (gone < COVER_MIN_MS || reading && gone < COVER_MAX_MS) { cover.postDelayed(this, COVER_POLL_MS); return; }
                finish(a);
                cover.animate().alpha(0f).setDuration(260).withEndAction(() -> { decor.removeView(cover); covering = false; }).start();
            }
        }, COVER_POLL_MS);
    }

    /** The setup is done: its marks go, Home shows the library */
    private static void finish(Activity a) {
        if (!SetupSteps.canFinish(state(a))) return;
        PeanutsPrefs.setupDone(a, true);
        PeanutsPrefs.setupClear(a);
        keysRejected = false;
        waitTimed = false;
        SetupPeanut.reset();
        Library.changed(a);
    }

    /** Later on Keys or Firmware: the step is left, and the next one shows */
    private static void later(Activity a, String mark, Step s) {
        PeanutsPrefs.setupMark(a, mark, true);
        go(a, SetupSteps.next(s));
    }

    // ---- B ----

    /** Back (B) while the setup shows: the screen before; on the welcome nothing (SettingsPage.onBack, MainActivity's one Back hook) */
    static boolean onBack(Activity a) {
        if (a != owner || root == null || !root.isAttachedToWindow() || !root.isShown()) return false;
        Step s = current(a);
        if (s != Step.WELCOME && s != Step.DONE) go(a, SetupSteps.previous(s));
        else Haptics.play(a.getWindow().getDecorView(), Haptics.Word.EDGE);   // the welcome: nothing before it; the end: it goes on by itself
        return true;
    }

    // ---- The second screen ----

    private static BottomScreen.Explain explain(Activity a, Step s, SetupSteps.State st) {
        BottomScreen.Explain e = new BottomScreen.Explain();
        e.key = "setup:" + s.key;
        e.icon = s.icon;
        e.rows = new ArrayList<>();
        e.keys = new ArrayList<>();
        switch (s) {
            case WELCOME:
                e.title = "Welcome"; e.place = "Set up";
                e.text = "Four steps, about a minute. Everything can be changed later in Settings.";
                e.rows.add(new CharSequence[] {"File access", "Step 1"});
                e.rows.add(new CharSequence[] {"Keys", "Step 2"});
                e.rows.add(new CharSequence[] {"Firmware", "Step 3"});
                e.rows.add(new CharSequence[] {"Your games", "Step 4"});
                e.keys.add(new String[] {"A", "Set up"});
                break;
            case ACCESS:
                e.title = "File access"; e.place = place(s);
                e.text = "Android lets an app read folders outside its own only with this access. The app reads the folders you add, and nothing leaves this device.";
                e.rows.add(new CharSequence[] {"Now", st.access ? "Allowed" : "Not allowed yet"});
                e.rows.add(new CharSequence[] {"Needed for", "Reading your games"});
                break;
            case KEYS:
                e.title = "Keys"; e.place = place(s);
                e.text = "An NSP or XCI file is locked with your console's keys. The app reads it with them on this device and never sends them anywhere.";
                e.rows.add(new CharSequence[] {"Now", st.keys ? "Added" : "Not added yet"});
                e.rows.add(new CharSequence[] {"Needed for", "Building a game"});
                break;
            case FIRMWARE:
                e.title = "Firmware"; e.place = place(s);
                e.text = "Games take their fonts from your console's firmware, and Miis their parts. Without the fonts some games stop at their start.";
                e.rows.add(new CharSequence[] {"Now", st.firmware ? "Added" : "Not added yet"});
                e.rows.add(new CharSequence[] {"Needed for", "Building a game"});
                break;
            case GAMES:
                e.title = "Your games"; e.place = place(s);
                e.text = "Add every folder you keep games in: the phone, an SD card, both. Open a folder's chip to remove it; its files stay.";
                e.rows.add(new CharSequence[] {"Game folders", String.valueOf(st.gameFolders)});
                if (st.addonFolders > 0) e.rows.add(new CharSequence[] {"Update and DLC folders", String.valueOf(st.addonFolders)});
                e.rows.add(new CharSequence[] {"Games found", String.valueOf(gamesFound())});
                break;
            default:
                e.title = "All set"; e.place = "Set up";
                e.text = "Your games show on Home now. Anything here can be changed later in Settings.";
                e.rows.add(new CharSequence[] {"Keys", st.keys ? "Added" : "Later"});
                e.rows.add(new CharSequence[] {"Firmware", st.firmware ? "Added" : "Later"});
                e.rows.add(new CharSequence[] {"Game folders", String.valueOf(st.gameFolders)});
                e.rows.add(new CharSequence[] {"Games", String.valueOf(gamesFound())});
        }
        if (s != Step.WELCOME && s != Step.DONE) { e.keys.add(new String[] {"A", "Choose"}); e.keys.add(new String[] {"B", "Back"}); }
        return e;
    }

    // ---- Pieces ----

    /** "Step 2 of 4" */
    private static String place(Step s) { return String.format(Locale.ROOT, "Step %d of %d", SetupSteps.number(s), SetupSteps.COUNTED.size()); }

    private enum Chip { NORMAL, ADD, DONE, WARN }

    /** A round chip: an icon, its words and a count after them; l null: not a button */
    private static View chip(Activity a, String icon, CharSequence text, CharSequence count, Chip kind, View.OnClickListener l) {
        LinearLayout c = PUi.row(a);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(PUi.dp(a, kind == Chip.DONE ? 44 : 40));
        c.setPadding(PUi.dp(a, 12), PUi.dp(a, 6), PUi.dp(a, 16), PUi.dp(a, 6));
        int fill, ink;
        switch (kind) {
            case ADD: fill = PUi.col(a, R.color.bn_tint); ink = PUi.col(a, R.color.bn_accent); break;
            case DONE: fill = PUi.col(a, R.color.bn_leaf); ink = PUi.col(a, R.color.bn_on_leaf); break;
            case WARN: fill = PUi.col(a, R.color.bn_danger_tint); ink = PUi.col(a, R.color.bn_danger); break;
            default: fill = PUi.col(a, R.color.bn_card); ink = PUi.col(a, R.color.bn_text);
        }
        GradientDrawable bg = PUi.round(fill, PUi.dp(a, 22), kind == Chip.NORMAL ? PUi.col(a, R.color.bn_card_stroke) : 0, kind == Chip.NORMAL ? PUi.dp(a, 1) : 0);
        c.setBackground(l == null ? bg : PUi.ripple(a, bg));
        ImageView ic = new ImageView(a);
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Drawable d = Icons.get(a, icon, Icons.Ink.NORMAL);
        if (d != null && kind != Chip.NORMAL) { d = d.mutate(); d.setTint(ink); }
        ic.setImageDrawable(d);
        c.addView(ic, new LinearLayout.LayoutParams(PUi.dp(a, 18), PUi.dp(a, 18)));
        TextView t = PUi.text(a, text, 14, R.color.bn_text, kind == Chip.NORMAL ? 600 : 700);
        t.setTextColor(ink);
        LinearLayout.LayoutParams tl = PUi.wrap();
        tl.setMarginStart(PUi.dp(a, 8));
        c.addView(t, tl);
        if (count != null) {
            TextView n = PUi.text(a, count, 14, R.color.bn_text_2, 500);
            LinearLayout.LayoutParams nl = PUi.wrap();
            nl.setMarginStart(PUi.dp(a, 8));
            c.addView(n, nl);
        }
        if (l != null) {
            c.setFocusable(true);
            c.setClickable(true);
            c.setOnClickListener(Haptics.on(Haptics.Word.TICK, l));
        }
        return c;
    }

    /** One of the firmware's two choices: a large tile, its icon over its name and line */
    private static View choice(Activity a, String icon, String title, String line, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(PUi.dp(a, 16), PUi.dp(a, 16), PUi.dp(a, 16), PUi.dp(a, 16));
        c.setBackground(PUi.ripple(a, PUi.round(PUi.col(a, R.color.bn_card), PUi.dp(a, 20), PUi.col(a, R.color.bn_card_stroke), PUi.dp(a, 1))));
        TextView tile = PUi.text(a, "", 18, R.color.bn_text, 400);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(PUi.round(PUi.col(a, R.color.bn_tint), PUi.dp(a, 14), 0, 0));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Icons.tile(tile, icon);
        c.addView(tile, new LinearLayout.LayoutParams(PUi.dp(a, 44), PUi.dp(a, 44)));
        PUi.add(c, PUi.text(a, title, 16, R.color.bn_text, 700), 10);
        PUi.add(c, PUi.text(a, line, 13, R.color.bn_text_2, 400), 2);
        c.setFocusable(true);
        c.setClickable(true);
        c.setContentDescription(title + ", " + line);
        c.setOnClickListener(Haptics.on(Haptics.Word.TICK, l));
        c.setTag("choice:" + title);
        return c;
    }

    /** A link in the accent: its icon and words (the games' Updates and DLC) */
    private static View link(Activity a, String icon, String text, View.OnClickListener l) {
        LinearLayout c = PUi.row(a);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setMinimumHeight(PUi.dp(a, 36));
        c.setPadding(PUi.dp(a, 6), 0, PUi.dp(a, 10), 0);
        c.setBackground(PUi.ripple(a, PUi.round(Color.TRANSPARENT, PUi.dp(a, 12), 0, 0)));
        ImageView ic = new ImageView(a);
        Drawable d = Icons.get(a, icon, Icons.Ink.NORMAL);
        if (d != null) { d = d.mutate(); d.setTint(PUi.col(a, R.color.bn_accent)); }
        ic.setImageDrawable(d);
        ic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        c.addView(ic, new LinearLayout.LayoutParams(PUi.dp(a, 18), PUi.dp(a, 18)));
        TextView t = PUi.text(a, text, 14, R.color.bn_accent, 700);
        LinearLayout.LayoutParams tl = PUi.wrap();
        tl.setMarginStart(PUi.dp(a, 8));
        c.addView(t, tl);
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnClickListener(Haptics.on(Haptics.Word.TICK, l));
        return c;
    }

    private static ImageView icon(Activity a, String name, int dp) {
        ImageView v = new ImageView(a);
        v.setImageDrawable(Icons.get(a, name, Icons.Ink.NORMAL));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setLayoutParams(new LinearLayout.LayoutParams(PUi.dp(a, dp), PUi.dp(a, dp)));
        return v;
    }

    private static View main(Activity a, String text, String icon, View.OnClickListener l) {
        Button b = PUi.button(a, text, PUi.Kind.PRIMARY, Haptics.on(Haptics.Word.TICK, l));
        Drawable d = icon == null ? null : Icons.get(a, icon, Icons.Ink.ON_BANANA);
        if (d != null) { b.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null); b.setCompoundDrawablePadding(PUi.dp(a, 8)); }
        b.setTag("main:" + text);
        return b;
    }

    private static View quiet(Activity a, String text, View.OnClickListener l) {
        Button b = PUi.button(a, text, PUi.Kind.QUIET, Haptics.on(Haptics.Word.TICK, l));
        b.setTag("quiet:" + text);
        return b;
    }

    private static View next(Activity a, Step s) {
        Button b = PUi.button(a, "Continue", PUi.Kind.PRIMARY, Haptics.on(Haptics.Word.TICK, v -> {
            if (!SetupSteps.canGoOn(s, state(a))) { Haptics.play(v, Haptics.Word.REJECT); return; }
            go(a, SetupSteps.next(s));
        }));
        b.setTag("next");
        return b;
    }

    /** A folder's path in a chip: the volume as a word (an SD card's ID says nothing), the rest as it is */
    static String shortPath(String path) {
        String p = FolderList.clean(path);
        if (p == null) return "";
        if (p.startsWith("/storage/emulated/0")) return "Phone" + p.substring("/storage/emulated/0".length());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}(.*)").matcher(p);
        if (m.matches()) return "SD card" + m.group(1);
        return p;
    }

    private static int gamesFound() {
        List<LibraryScanner.Title> t = Library.titlesNow();
        return t == null ? 0 : t.size();
    }

    /** What the library found in a folder ("6 games", "4 updates, 2 DLC"), null before the scan has */
    private static String holds(FolderKind k, String path) {
        List<LibraryScanner.Title> titles = Library.titlesNow();
        if (titles == null || !Library.scannedOnce()) return null;
        List<String> one = java.util.Collections.singletonList(path);
        int games = 0, updates = 0, dlc = 0;
        for (LibraryScanner.Title t : titles) {
            if (t.base != null && FolderList.holding(one, t.base.file.getPath()) != null) games++;
            for (LibraryScanner.Source s : t.updates) if (FolderList.holding(one, s.file.getPath()) != null) updates++;
            for (LibraryScanner.Source s : t.dlcs) if (FolderList.holding(one, s.file.getPath()) != null) dlc++;
        }
        if (k == FolderKinds.ADDONS) {
            if (updates == 0 && dlc == 0) return null;
            return updates == 0 ? dlc + " DLC" : dlc == 0 ? count(updates, "update") : count(updates, "update") + ", " + dlc + " DLC";
        }
        return String.valueOf(games);
    }

    private static String count(int n, String what) { return String.format(Locale.ROOT, "%d %s%s", n, what, n == 1 ? "" : "s"); }

    /** Chips side by side, on as many lines as they need */
    private static final class Flow extends ViewGroup {
        private final int gap;
        Flow(android.content.Context c) { super(c); gap = PUi.dp(c, 8); }

        @Override protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws), x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += line + gap; line = 0; }
                x += v.getMeasuredWidth() + gap;
                line = Math.max(line, v.getMeasuredHeight());
            }
            setMeasuredDimension(max, y + line);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                if (x > 0 && x + v.getMeasuredWidth() > max) { x = 0; y += line + gap; line = 0; }
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
                x += v.getMeasuredWidth() + gap;
                line = Math.max(line, v.getMeasuredHeight());
            }
        }
    }
}
