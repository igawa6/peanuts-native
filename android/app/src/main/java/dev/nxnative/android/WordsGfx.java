package dev.nxnative.android;
// PEANUTS: new file. The graphics settings of a game whose profile has window_words rows (build.gradle resolutionOf: "words:<max WxH>", Profile.words): Output resolution, Internal
// resolution, the Internal upscaler, Graphics quality and a frame rate slider. This file is the plain Java part, no Android classes, so the JVM test (WordsGfxJvmTest)
// runs it: the Native size, the choices and their words, the host's lines for each control (DM_WINDOW_SIZE, DM_SCENE_PCT, DM_VK_CUPSCALE, DM_VK_CUPSCALE_SHARP) and the move of the
// old "3D at full window size" switch. WordsRows has the rows, the stored choices and the start's lines. Every other game is untouched: each entry here answers "no" or "nothing"
// for a profile without the words: kind.

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WordsGfx {
    private WordsGfx() {}

    // ---- Output resolution ----

    /** The stored Output choice that means the largest 16:9 size that fits the screen (absent is the same) */
    static final String NATIVE = "native";
    /** The sizes offered besides Native, largest first (the sizes the host's window words were probed at), and their names */
    static final int[][] SIZES = {{1920, 1080}, {1280, 720}};
    static final String[] SIZE_NAMES = {"1080p", "720p"};
    static String sizeText(int[] s) { return s[0] + "x" + s[1]; }

    /**
     * Native: the largest of the probed sizes (SIZES: 1920x1080, 1280x720) that fits the screen and the profile's maximum, else 1280x720 (no size that was not probed). The screen
     * is taken as landscape (the game always is), so a display reported upright gives the same size. Nova 1280x960 gives 1280x720, a 2400x1080 phone 1920x1080, a 1920x1200 tablet
     * 1920x1080, a 1366x768 screen 1280x720. An unknown screen (0) gives 1280x720
     */
    static int[] nativeOut(int screenW, int screenH, int maxW, int maxH) {
        int w = Math.max(screenW, screenH), h = Math.min(screenW, screenH);
        for (int[] s : SIZES)
            if (s[0] <= w && s[1] <= h && s[0] <= maxW && s[1] <= maxH) return s.clone();
        return new int[] {1280, 720};
    }

    /** One Output choice: the value stored for it (NATIVE or "WxH"), its size and its name ("Native", "1080p") */
    static final class Out {
        final String value, name; final int[] size;
        Out(String value, int[] size, String name) { this.value = value; this.size = size; this.name = name; }
        /** The list's words: "Native (1280x720)" */
        String label() { return name + " (" + sizeText(size) + ")"; }
        /** The row's value: "Native · 1280x720" */
        String valueText() { return name + " · " + sizeText(size); }
    }

    /** The Output choices, Native first: Native, then 1080p and 720p unless one is Native's own size or larger than the profile's maximum */
    static List<Out> outChoices(int[] nat, int maxW, int maxH) {
        List<Out> l = new ArrayList<>();
        l.add(new Out(NATIVE, nat, "Native"));
        for (int i = 0; i < SIZES.length; i++) {
            int[] s = SIZES[i];
            if (s[0] > maxW || s[1] > maxH || s[0] == nat[0] && s[1] == nat[1]) continue;
            l.add(new Out(sizeText(s), s, SIZE_NAMES[i]));
        }
        return l;
    }

    /** The choice a stored value takes: its own, else Native (an absent value, or a size this screen no longer offers) */
    static Out outOf(List<Out> choices, String stored) {
        for (Out o : choices) if (o.value.equals(stored)) return o;
        return choices.get(0);
    }

    // ---- The profile's Output default (game.tsv output_default, build.gradle NX_PEANUTS_OUTPUT_DEFAULT) ----

    /**
     * The Output choice a profile starts with when none is stored, as a stored value (NATIVE or "WxH"). {@code table} is build.gradle's NX_PEANUTS_OUTPUT_DEFAULT: space-separated
     * "&lt;title ID&gt;:native|720p|1080p". No entry, an unknown value or an empty table is Native (as without the key). It is only a wish: outOf takes Native for a size the list does not offer
     */
    static String outDefault(String table, String titleId) {
        if (table == null || titleId == null) return NATIVE;
        for (String e : table.split(" ")) {
            int c = e.indexOf(':');
            if (c <= 0 || !e.substring(0, c).equals(titleId)) continue;
            String v = e.substring(c + 1);
            for (int i = 0; i < SIZES.length; i++) if (SIZE_NAMES[i].equals(v)) return sizeText(SIZES[i]);
            return NATIVE;
        }
        return NATIVE;
    }

    /** The Output value in force: the stored one, else the profile's default (stored is null when the player has not chosen: Reset to defaults removes it) */
    static String outPick(String stored, String table, String titleId) { return stored != null ? stored : outDefault(table, titleId); }

    /**
     * What to store for a choice the player makes: null (store nothing) when it is Native and the profile's default is Native too (as without the key), else the value itself, Native
     * included: with a profile default of 720p, a player's Native must stay stored or the default would come back
     */
    static String outStore(String value, String table, String titleId) {
        if (value == null || value.equals(NATIVE) && outDefault(table, titleId).equals(NATIVE)) return null;
        return value;
    }

    // ---- a fixed-window title (game.tsv view_scale_fixed -> build.gradle NX_PEANUTS_FIXED_WINDOW) ----

    /**
     * The fixed window of a title, {w, h}, or null when it has none. {@code table} is build.gradle's NX_PEANUTS_FIXED_WINDOW: space-separated "&lt;title ID&gt;:&lt;W&gt;x&lt;H&gt;". Its Output
     * resolution is the 3D target size (DM_OUT_SIZE), never the window: the window stays this size
     */
    static int[] fixedSize(String table, String titleId) {
        if (table == null || titleId == null) return null;
        for (String e : table.split(" ")) {
            int c = e.indexOf(':');
            if (c <= 0 || !e.substring(0, c).equals(titleId)) continue;
            String[] wh = e.substring(c + 1).split("x");
            if (wh.length != 2) return null;
            try {
                int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]);
                return w > 0 && h > 0 ? new int[] {w, h} : null;
            } catch (NumberFormatException x) { return null; }
        }
        return null;
    }

    /**
     * Native of a fixed-window title, at run time: k = min(floor(W/64), floor(H/36)) gives the largest 16:9 size 64k x 36k that fits the panel (taken as landscape, W &gt;= H); capped to the
     * fixed window when that is larger. Nova 1280x960 gives 1280x720, a 2400x1080 phone 1920x1080, a 1366x768 screen 1344x756, a 2560x1600 tablet the window (1920x1080). An unknown panel
     * (0) gives 1280x720 (or the window when that is smaller). nativeOut, SIZES and outChoices are the windowed titles' and stay as they were
     */
    static int[] nativeFixed(int panelW, int panelH, int maxW, int maxH) {
        int w = Math.max(panelW, panelH), h = Math.min(panelW, panelH);
        int k = Math.min(w / 64, h / 36);
        int[] s = k > 0 ? new int[] {64 * k, 36 * k} : new int[] {1280, 720};
        return s[0] > maxW || s[1] > maxH ? new int[] {maxW, maxH} : s;
    }

    /** The Output choices on this screen: a fixed-window title's from nativeFixed, every other title's from nativeOut; both capped at {@code max} */
    static List<Out> outs(int screenW, int screenH, int[] max, boolean fixed) {
        int[] nat = fixed ? nativeFixed(screenW, screenH, max[0], max[1]) : nativeOut(screenW, screenH, max[0], max[1]);
        return outChoices(nat, max[0], max[1]);
    }

    // ---- row gating (game.tsv words_gate -> build.gradle NX_PEANUTS_WORDS_GATE) ----

    /** The rows a gate names (as game.tsv writes them): Output resolution, Internal resolution, the Internal upscaler (with its sharpness) */
    static final String ROW_OUTPUT = "output", ROW_INTERNAL = "internal", ROW_UPSCALER = "upscaler";
    /** The value a row shows while Graphics quality is Handheld and the row is docked_only (it is shown disabled; no helper line) */
    static final String DOCKED_ONLY = "Docked only";

    /** What a title's words_gate row says: the docked_only rows (shown disabled while Graphics quality is Handheld, their env not sent) and the hidden rows (not shown, their env not sent) */
    static final class Gate {
        private final Set<String> dockedOnly, hidden;
        Gate(Set<String> dockedOnly, Set<String> hidden) { this.dockedOnly = dockedOnly; this.hidden = hidden; }
        /** The row is shown (not hidden) */
        boolean shows(String row) { return !hidden.contains(row); }
        /** The row is shown disabled: docked_only, and Graphics quality is Handheld (mode 1, ScreenMode.mode) */
        boolean gated(String row, int mode) { return mode == 1 && dockedOnly.contains(row) && shows(row); }
        /** The row's env goes to the host: shown and not gated */
        boolean sends(String row, int mode) { return shows(row) && !gated(row, mode); }
    }
    static final Gate NO_GATE = new Gate(Collections.emptySet(), Collections.emptySet());

    /**
     * A title's gate from build.gradle's NX_PEANUTS_WORDS_GATE: space-separated "&lt;title ID&gt;:&lt;spec&gt;", spec = "docked_only=output,internal;hidden=upscaler" (either part alone is fine). No
     * entry, an empty table or an unreadable spec is no gate (build.gradle fails the build on a bad row, so this is only a safe reading)
     */
    static Gate gateOf(String table, String titleId) {
        if (table == null || titleId == null) return NO_GATE;
        for (String e : table.split(" ")) {
            int c = e.indexOf(':');
            if (c <= 0 || !e.substring(0, c).equals(titleId)) continue;
            Set<String> docked = new HashSet<>(), hid = new HashSet<>();
            for (String part : e.substring(c + 1).split(";")) {
                int q = part.indexOf('=');
                if (q <= 0) continue;
                Set<String> into = part.substring(0, q).equals("docked_only") ? docked : part.substring(0, q).equals("hidden") ? hid : null;
                if (into == null) continue;
                for (String r : part.substring(q + 1).split(",")) if (r.equals(ROW_OUTPUT) || r.equals(ROW_INTERNAL) || r.equals(ROW_UPSCALER)) into.add(r);
            }
            return new Gate(docked, hid);
        }
        return NO_GATE;
    }

    // ---- Internal resolution ----

    /** The steps, lowest (leftmost) first: the 3D picture's size in percent of the output. DM_SCENE_PCT takes the number */
    static final int[] PCTS = {40, 50, 60, 70, 80, 90, 100};
    static final String[] PCT_NAMES = {"40 %", "50 %", "60 %", "70 %", "80 %", "90 %", "100 %"};
    /** Where the slider rests while "Game default" is on (the game's own 5/6 is 83 %; 80 % costs about the same GPU time, measured on device) */
    static final int PCT_REST = 80;
    /** the one helper line of the in-game panel's Internal resolution (the view-scale words are written at load, so it cannot change while the game runs). Game settings has none: every row there applies at the next start */
    static final String NEXT_START = "Applies on next start";

    static int pctIndex(int pct) {
        for (int i = 0; i < PCTS.length; i++) if (PCTS[i] == pct) return i;
        return -1;
    }
    /** A stored percentage as a step: itself, else 0 (the game's own) for anything that is not one of PCTS */
    static int pctOf(int stored) { return pctIndex(stored) >= 0 ? stored : 0; }

    // ---- Internal upscaler ----

    static final String[] UP_NAMES = {"Bilinear (game's own)", "FSR 1", "SGSR 1"};
    static final String[] UP_SHORT = {"Bilinear", "FSR 1", "SGSR 1"};
    /** Each choice's DM_VK_CUPSCALE value (1 the game's own bilinear, 2 FSR 1, 4 SGSR 1) */
    static final int[] UP_ENV = {1, 2, 4};
    /** The sharpness steps, softest first: stops as DM_VK_FSR_SHARP's (0 the most), in the 0.0 to 2.0 the host takes; 0.25 is AMD's default */
    static final float[] SHARPS = {2f, 1f, 0.5f, 0.25f, 0f};
    static final String[] SHARP_NAMES = {"Softest", "Softer", "Soft", "Default", "Max"};
    static final int SHARP_DEFAULT = 3;

    static int upMode(int stored) { return stored >= 0 && stored < UP_ENV.length ? stored : 0; }
    static int sharpIndex(int stored) { return stored >= 0 && stored < SHARPS.length ? stored : SHARP_DEFAULT; }

    // ---- Graphics quality: the console mode, relabelled ----

    /** ScreenMode's two modes in the words of this game (index 0 docked, 1 handheld, as ScreenMode.NAMES) */
    static final String[] GFX_NAMES = {"Docked", "Handheld"};
    static final String[] GFX_SHORT = {"Docked", "Handheld"};
    /** the in-game panel's Graphics quality radio group lists Handheld first (slot 0), then Docked (slot 1), as Game settings' dialog does; the mode a slot stands for (ScreenMode: 1 handheld, 0 docked) and the slot of a mode */
    static int gfxModeOfSlot(int slot) { return slot == 0 ? 1 : 0; }
    static int gfxSlotOfMode(int mode) { return mode == 1 ? 0 : 1; }
    /** the in-game Graphics quality choice's log lines (ScreenMode.keep, Native.note), so a device log shows whether the control ran and what was read back: picked (before the write), kept (after it, the value read back) */
    static String gfxPickedLine(String id, int mode) { return "Graphics quality: " + GFX_NAMES[mode == 1 ? 1 : 0] + " picked in game, writing console_mode@" + id + "=" + mode + " for the next start"; }
    static String gfxKeptLine(String id, int mode, int readBack) { return "Graphics quality: kept " + GFX_NAMES[mode == 1 ? 1 : 0] + " (console_mode@" + id + "=" + readBack + ") for the next start"; }

    // ---- Frame rate ----

    /** the frame rate a words: game starts with when nothing is stored (its Frame rate slider has no "Game owned" step): 30 fps, else the nearest offered step (fpsNearest) */
    static final int FPS_DEFAULT = 30;
    /** The frame rates the slider offers, as stored (fps_cap: 0 is 60), slowest first, from the profile's allowed caps (Prefs.paceCaps; a "Game owned" entry of the list is not a step) */
    static int[] fpsSteps(int[] allowed) {
        List<Integer> l = new ArrayList<>();
        for (int v : allowed) if (v >= 0) l.add(v);
        l.sort((a, b) -> Integer.compare(a <= 0 ? 60 : a, b <= 0 ? 60 : b));
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
    }
    static String fpsLabel(int v) { return (v <= 0 ? 60 : v) + " fps"; }
    static String[] fpsNames(int[] steps) {
        String[] n = new String[steps.length];
        for (int i = 0; i < n.length; i++) n[i] = fpsLabel(steps[i]);
        return n;
    }
    /** The offered step nearest to want (60 is stored as 0; a tie goes to the slower step); want itself when it is a step; want unchanged when there are no steps */
    static int fpsNearest(int[] steps, int want) {
        if (steps.length == 0) return want;
        int w = want <= 0 ? 60 : want, best = steps[0], bd = Integer.MAX_VALUE, bs = Integer.MAX_VALUE;
        for (int s : steps) {
            int r = s <= 0 ? 60 : s, d = Math.abs(r - w);
            if (d < bd || d == bd && r < bs) { best = s; bd = d; bs = r; }
        }
        return best;
    }
    /**
     * The rate a words: game runs at (the profile's fps_default comes after the stored one, see the second fpsStart): the stored one (stored below 0 is none: nothing stored, or a stored "Game owned" -2: the slider has no Game owned step, so it is dropped and counts as none), else the recommended one
     * (rec below 0: none), else FPS_DEFAULT, each landed on the nearest offered step. A stale "Game owned" therefore reads as 30 and never as a state of its own
     */
    static int fpsStart(int[] steps, int stored, int rec) {
        return fpsStart(steps, stored, -1, rec);
    }
    /**
     * the same with the profile's own default (game.tsv fps_default, see fpsDefault; def below 1: none). The order is the stored rate, else the profile's fps_default, else the
     * recommended one, else FPS_DEFAULT, each landed on the nearest offered step. Reset to defaults removes the stored rate, so it returns to fps_default
     */
    static int fpsStart(int[] steps, int stored, int def, int rec) {
        return fpsNearest(steps, stored >= 0 ? stored : def > 0 ? def : rec >= 0 ? rec : FPS_DEFAULT);
    }
    /**
     * a profile's frame rate default, in fps (60 is 60, not the stored 0), or -1 for none. {@code table} is build.gradle's NX_PEANUTS_FPS_DEFAULT: space-separated
     * "&lt;title ID&gt;:&lt;n&gt;" (n: 10 to 60, two digits, no sign; build.gradle and game.py refuse any other row). No entry, an empty table or a malformed entry is none: the default order applies
     */
    static int fpsDefault(String table, String titleId) {
        if (table == null || titleId == null) return -1;
        for (String e : table.split(" ")) {
            int c = e.indexOf(':');
            if (c <= 0 || !e.substring(0, c).equals(titleId)) continue;
            String v = e.substring(c + 1);
            if (v.length() != 2) return -1;
            int n = 0;
            for (int i = 0; i < v.length(); i++) { char ch = v.charAt(i); if (ch < '0' || ch > '9') return -1; n = n * 10 + (ch - '0'); }
            return n >= 10 ? n : -1;
        }
        return -1;
    }
    /** The step a stored rate lands on: itself, else the fastest step below it, else the slowest */
    static int fpsPick(int[] steps, int preferred) {
        if (steps.length == 0) return preferred;
        int want = preferred <= 0 ? 60 : preferred, best = 0;
        for (int i = 0; i < steps.length; i++) { int s = steps[i] <= 0 ? 60 : steps[i]; if (s <= want) best = i; }
        return steps[best];
    }
    static int fpsIndex(int[] steps, int stored) {
        for (int i = 0; i < steps.length; i++) if (steps[i] == stored) return i;
        return Math.max(0, steps.length - 1);
    }

    // ---- Gates: what only a words: game does ----

    /** Whether the game's Graphics quality (the console mode) is the player's to choose: the profile's op_mode handheld stays the default, it no longer locks the row */
    static boolean unlocksMode(Profile p) { return p != null && p.words(); }
    /** Whether the Resolution scale row is hidden (two competing scale rows otherwise: Internal resolution is the game's own) */
    static boolean hidesResScale(Profile p) { return p != null && p.words(); }

    // ---- The host's lines ----

    /**
     * The host's lines for a start (none for a profile without words: rows): DM_WINDOW_SIZE always (the Output choice's size: the launcher sends Native as an explicit size, the host
     * never needs "native"); DM_SCENE_PCT when Internal resolution is a step (scenePct 0: the game's own, nothing sent); DM_VK_CUPSCALE with the Upscaler's choice, and
     * DM_VK_CUPSCALE_SHARP with its sharpness while that is FSR 1 or SGSR 1. DM_OPMODE (Graphics quality) and DM_FPS_LIMIT (Frame rate) are ScreenMode's and GameActivity's own
     */
    static List<String> env(Profile p, int screenW, int screenH, String outStored, int scenePct, int upStored, int sharpStored) {
        return env(p, screenW, screenH, outStored, scenePct, upStored, sharpStored, "", "", 0);
    }

    /**
     * the same with the title's fixed window and gate (build.gradle's NX_PEANUTS_FIXED_WINDOW and NX_PEANUTS_WORDS_GATE; "" for none) and the effective Graphics quality ({@code mode}: 1
     * Handheld, ScreenMode.mode). A fixed-window title sends DM_OUT_SIZE (the 3D target size) where a windowed one sends DM_WINDOW_SIZE: never both, and never DM_WINDOW_SIZE for the fixed
     * one. A docked_only row in Handheld, and a hidden row, send nothing: no Output line (DM_OUT_SIZE or DM_WINDOW_SIZE), no DM_SCENE_PCT, no DM_VK_CUPSCALE*. With neither table, as the
     * short form
     */
    static List<String> env(Profile p, int screenW, int screenH, String outStored, int scenePct, int upStored, int sharpStored, String fixedTable, String gateTable, int mode) {
        List<String> env = new ArrayList<>();
        if (p == null || !p.words()) return env;
        int[] fix = fixedSize(fixedTable, p.titleId);
        int[] max = fix != null ? fix : p.wordsMax();
        Gate gate = gateOf(gateTable, p.titleId);
        int[] size = outOf(outs(screenW, screenH, max, fix != null), outStored).size;
        if (gate.sends(ROW_OUTPUT, mode)) env.add((fix != null ? "DM_OUT_SIZE=" : "DM_WINDOW_SIZE=") + sizeText(size));
        if (gate.sends(ROW_INTERNAL, mode) && pctIndex(scenePct) >= 0) env.add("DM_SCENE_PCT=" + scenePct);
        if (gate.sends(ROW_UPSCALER, mode)) env.addAll(upscalerLines(upMode(upStored), sharpIndex(sharpStored), false));
        return env;
    }

    /** The Upscaler's lines: DM_VK_CUPSCALE, and the sharpness while the choice has one (always with live: a change back to Bilinear must not leave the old sharpness) */
    static List<String> upscalerLines(int mode, int sharp, boolean live) {
        List<String> l = new ArrayList<>();
        l.add("DM_VK_CUPSCALE=" + UP_ENV[mode]);
        if (mode != 0 || live) l.add("DM_VK_CUPSCALE_SHARP=" + SHARPS[sharp]);
        return l;
    }

    /** What a start fixes for the run (the Output size, Internal resolution, Graphics quality): the in-game panel says "Restart" when the choices now differ from it */
    static String restartSig(int[] size, int pct, int mode) { return sizeText(size) + "|" + pct + "|" + mode; }

    // ---- The old "3D at full window size" switch ----

    /** The stored keys (their "@<title ID>" is added): the old option's (Prefs.gameOption: "opt_" + env) and Internal resolution's */
    static final String LEGACY_ENV = "DM_MRSOH_3D_FULL", SCENE_KEY = "scene_pct";
    static String key(String k, String titleId) { return k + "@" + titleId; }
    static String legacyKey(String titleId) { return key("opt_" + LEGACY_ENV, titleId); }

    /** The stored choices, as much of SharedPreferences as the move needs */
    interface Store {
        boolean has(String key);
        boolean bool(String key);
        void putInt(String key, int v);
        void remove(String key);
    }

    /**
     * Moves the old option: "3D at full window size" on becomes Internal resolution 100 %; the old key is dropped either way (an option the profile no longer has). Returns whether
     * anything was stored or dropped. It runs on every read of the choice and is a no-op once the old key is gone
     */
    static boolean migrate(Store s, String titleId) {
        String old = legacyKey(titleId);
        if (!s.has(old)) return false;
        if (s.bool(old)) s.putInt(key(SCENE_KEY, titleId), 100);
        s.remove(old);
        return true;
    }

    /** the title of a dialog with a checkbox of its own words (Game default): "&lt;label&gt;: &lt;check&gt;" while the box is checked, else the step's title (StepSlider.title) as the slider shows it */
    static String checkTitle(String label, String check, boolean checked, String stepTitle) { return checked && check != null ? label + ": " + check : stepTitle; }
    static String pctLabel(int pct) { return pctIndex(pct) >= 0 ? String.format(Locale.ROOT, "%d %%", pct) : "Game default"; }
}
