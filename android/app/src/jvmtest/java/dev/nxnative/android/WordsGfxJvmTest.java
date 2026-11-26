package dev.nxnative.android;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PEANUTS: new file. WordsGfx and Profile's words: kind: the Native size, the Output choices, the host's lines for each control, the move of the old
 * "3D at full window size" switch, the gates, and that a profile without the words: kind is left as it was. Prints "ok".
 */
public final class WordsGfxJvmTest {
    static void check(boolean c, String what) { if (!c) { System.err.println("FAIL: " + what); System.exit(1); } }
    static void eq(Object got, Object want, String what) { check(want == null ? got == null : want.equals(got), what + ": " + got + " (wanted " + want + ")"); }
    static void size(int[] got, int w, int h, String what) { check(got[0] == w && got[1] == h, what + ": " + got[0] + "x" + got[1] + " (wanted " + w + "x" + h + ")"); }

    /** a profile of another title (the fixed-window one) */
    static Profile profileOf(String titleId, String resolution) {
        Profile p = new Profile(titleId, "Test", 0, "", "", "", "");
        p.resolution = resolution;
        return p;
    }

    /** Prefs.paceCap's words: branch on a store (stored choice, else fps_default, else the recommended rate, else 30), then Profile.fpsMode for a profile with fps_modes */
    static int paceLike(Map1 store, Profile p, String key, String table, String id, int rec) {
        int v = WordsGfx.fpsStart(WordsGfx.fpsSteps(p.fpsModeCaps()), store.has(key) ? (Integer) store.m.get(key) : -1, WordsGfx.fpsDefault(table, id), rec);
        return p.fpsMode(v);
    }

    static Profile profile(String resolution) {
        Profile p = new Profile("0100317013770000", "Test", 0, "", "", "", "");
        p.resolution = resolution;
        return p;
    }

    static final class Map1 implements WordsGfx.Store {
        final Map<String, Object> m = new HashMap<>();
        public boolean has(String k) { return m.containsKey(k); }
        public boolean bool(String k) { return Boolean.TRUE.equals(m.get(k)); }
        public void putInt(String k, int v) { m.put(k, v); }
        public void remove(String k) { m.remove(k); }
    }

    public static void main(String[] a) {
        // ---- Native: the largest 16:9 that fits the screen, at most 1080p ----
        size(WordsGfx.nativeOut(1280, 960, 1920, 1080), 1280, 720, "Retroid Pocket Nova 1280x960");
        size(WordsGfx.nativeOut(2400, 1080, 1920, 1080), 1920, 1080, "20:9 phone 2400x1080");
        size(WordsGfx.nativeOut(1920, 1200, 1920, 1080), 1920, 1080, "16:10 tablet 1920x1200");
        size(WordsGfx.nativeOut(800, 1280, 1920, 1080), 1280, 720, "portrait 800x1280 is taken as landscape 1280x800");
        size(WordsGfx.nativeOut(960, 1280, 1920, 1080), 1280, 720, "the Nova reported upright");
        size(WordsGfx.nativeOut(1080, 2400, 1920, 1080), 1920, 1080, "a phone reported upright");
        size(WordsGfx.nativeOut(2560, 1600, 1920, 1080), 1920, 1080, "2560x1600 is capped at 1080p");
        size(WordsGfx.nativeOut(3840, 2160, 1920, 1080), 1920, 1080, "4K is capped at 1080p");
        size(WordsGfx.nativeOut(1920, 1080, 1920, 1080), 1920, 1080, "exactly 1080p");
        size(WordsGfx.nativeOut(1366, 768, 1920, 1080), 1280, 720, "1366x768: only probed sizes, so 720p");
        size(WordsGfx.nativeOut(1919, 1080, 1920, 1080), 1280, 720, "one pixel short of 1080p: 720p");
        size(WordsGfx.nativeOut(1920, 1079, 1920, 1080), 1280, 720, "one pixel short of 1080p high: 720p");
        size(WordsGfx.nativeOut(2560, 1440, 1280, 720), 1280, 720, "a profile maximum of 720p caps it");
        size(WordsGfx.nativeOut(480, 320, 1920, 1080), 1280, 720, "neither size fits a tiny screen: 720p");
        size(WordsGfx.nativeOut(0, 0, 1920, 1080), 1280, 720, "an unknown screen is 720p");
        for (int w : new int[] {640, 700, 1000, 1080, 1280, 1500, 1600, 1920, 2200, 2400, 2560, 3000})
            for (int h : new int[] {360, 480, 720, 800, 960, 1080, 1200, 1440}) {
                int[] n = WordsGfx.nativeOut(w, h, 1920, 1080);
                check(n[0] == 1920 && n[1] == 1080 || n[0] == 1280 && n[1] == 720, "any screen " + w + "x" + h + " gives a probed size: " + n[0] + "x" + n[1]);
                if (Math.max(w, h) >= 1280 && Math.min(w, h) >= 720) check(n[0] <= Math.max(w, h) && n[1] <= Math.min(w, h), "it fits the screen " + w + "x" + h + ": " + n[0] + "x" + n[1]);
            }

        // ---- the Output choices ----
        List<WordsGfx.Out> nova = WordsGfx.outChoices(WordsGfx.nativeOut(1280, 960, 1920, 1080), 1920, 1080);
        eq(nova.size(), 2, "Nova: Native and 1080p (720p is Native's own size)");
        eq(nova.get(0).label(), "Native (1280x720)", "Nova Native label");
        eq(nova.get(0).value, WordsGfx.NATIVE, "Native's stored value");
        eq(nova.get(1).label(), "1080p (1920x1080)", "Nova 1080p label: no \"heavy\" word");
        eq(nova.get(1).valueText(), "1080p · 1920x1080", "1080p's row value");
        List<WordsGfx.Out> phone = WordsGfx.outChoices(WordsGfx.nativeOut(2400, 1080, 1920, 1080), 1920, 1080);
        eq(phone.size(), 2, "2400x1080 phone: Native and 720p");
        eq(phone.get(0).label(), "Native (1920x1080)", "phone Native");
        eq(phone.get(1).label(), "720p (1280x720)", "phone 720p");
        List<WordsGfx.Out> odd = WordsGfx.outChoices(WordsGfx.nativeOut(1366, 768, 1920, 1080), 1920, 1080);
        eq(odd.size(), 2, "1366x768: Native (720p) and 1080p");
        eq(WordsGfx.outChoices(new int[] {1280, 720}, 1920, 1080).get(1).label(), "1080p (1920x1080)", "1280x720 Native: 1080p is the other");
        eq(WordsGfx.outChoices(new int[] {1280, 720}, 1280, 720).size(), 1, "a profile limited to 720p: Native alone");
        eq(WordsGfx.outOf(nova, "1920x1080").value, "1920x1080", "a stored size the list has");
        eq(WordsGfx.outOf(nova, "1280x720").value, WordsGfx.NATIVE, "a stored 720p that is Native here takes Native");
        eq(WordsGfx.outOf(nova, "999x1").value, WordsGfx.NATIVE, "a stored size that is not offered takes Native");
        eq(WordsGfx.outOf(nova, WordsGfx.NATIVE).value, WordsGfx.NATIVE, "Native");

        // ---- the profile's Output default (game.tsv output_default -> NX_PEANUTS_OUTPUT_DEFAULT) ----
        Profile pw = profile("words:1920x1080");
        String idd = "0100317013770000", oth = "01006BB00C6F0000";
        // key absent (the table is empty, as for the Sparks of Hope build, or names only another title): every expectation above is unchanged
        for (String t : new String[] {"", null, oth + ":720p", " ", "garbage", oth + ":1080p " + "0100000000010000:720p"}) {
            eq(WordsGfx.outDefault(t, idd), WordsGfx.NATIVE, "no row for the title (" + t + "): Native");
            eq(WordsGfx.outPick(null, t, idd), WordsGfx.NATIVE, "nothing stored, no row: Native (" + t + ")");
            eq(WordsGfx.outPick("1920x1080", t, idd), "1920x1080", "a stored size wins (" + t + ")");
            eq(WordsGfx.outStore(WordsGfx.NATIVE, t, idd), null, "Native with no row stores nothing, as before (" + t + ")");
            eq(WordsGfx.outStore(null, t, idd), null, "null stores nothing (" + t + ")");
            eq(WordsGfx.outStore("1920x1080", t, idd), "1920x1080", "a size is stored (" + t + ")");
            eq(WordsGfx.outOf(nova, WordsGfx.outPick(null, t, idd)).value, WordsGfx.NATIVE, "the Nova's list takes Native (" + t + ")");
            eq(WordsGfx.env(pw, 1280, 960, WordsGfx.outPick(null, t, idd), 0, 0, 3), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=1"), "the start is the same as before (" + t + ")");
        }
        // key present: native, 720p, 1080p, among other titles' entries
        String tn = idd + ":native", t7 = idd + ":720p", t1 = idd + ":1080p";
        eq(WordsGfx.outDefault(tn, idd), WordsGfx.NATIVE, "native");
        eq(WordsGfx.outDefault(t7, idd), "1280x720", "720p");
        eq(WordsGfx.outDefault(t1, idd), "1920x1080", "1080p");
        eq(WordsGfx.outDefault(oth + ":1080p " + t7, idd), "1280x720", "the entry is found among others");
        eq(WordsGfx.outDefault(t7 + " " + oth + ":1080p", idd), "1280x720", "and when first");
        eq(WordsGfx.outDefault(oth + ":1080p " + t7, oth), "1920x1080", "another title has its own");
        eq(WordsGfx.outDefault(idd + ":4k", idd), WordsGfx.NATIVE, "an unknown value is Native");
        eq(WordsGfx.outDefault(idd + ":", idd), WordsGfx.NATIVE, "an empty value is Native");
        eq(WordsGfx.outDefault(idd, idd), WordsGfx.NATIVE, "an entry without a value is Native");
        eq(WordsGfx.outDefault(idd.substring(1) + ":1080p", idd), WordsGfx.NATIVE, "a title ID that only partly matches is not it");
        // applies only when nothing is stored
        eq(WordsGfx.outPick(null, t1, idd), "1920x1080", "nothing stored: the default");
        eq(WordsGfx.outPick("1280x720", t1, idd), "1280x720", "a stored 720p wins over a 1080p default");
        eq(WordsGfx.outPick(WordsGfx.NATIVE, t1, idd), WordsGfx.NATIVE, "a stored Native wins over a 1080p default");
        eq(WordsGfx.outPick("", t1, idd), "", "a stored empty string is a stored value (outOf takes Native for it)");
        // limited to the choices offered, else Native
        List<WordsGfx.Out> small = WordsGfx.outChoices(new int[] {1280, 720}, 1280, 720);   // a profile limited to 720p: Native alone
        eq(WordsGfx.outOf(phone, WordsGfx.outPick(null, t1, idd)).value, WordsGfx.NATIVE, "1080p is Native's own size on a 2400x1080 phone: Native (the same picture)");
        eq(WordsGfx.outOf(small, WordsGfx.outPick(null, t1, idd)).value, WordsGfx.NATIVE, "1080p default, not offered (720p-limited profile): Native");
        eq(WordsGfx.outOf(small, WordsGfx.outPick(null, t7, idd)).value, WordsGfx.NATIVE, "720p default, only Native offered: Native");
        eq(WordsGfx.outOf(nova, WordsGfx.outPick(null, t1, idd)).value, "1920x1080", "1080p default, offered on the Nova");
        eq(WordsGfx.outOf(nova, WordsGfx.outPick(null, t7, idd)).value, WordsGfx.NATIVE, "720p default on the Nova is its Native (the same 1280x720)");
        eq(WordsGfx.outOf(phone, WordsGfx.outPick(null, t7, idd)).value, "1280x720", "720p default, offered on a 2400x1080 phone");
        List<WordsGfx.Out> odd2 = WordsGfx.outChoices(WordsGfx.nativeOut(1366, 768, 1920, 1080), 1920, 1080);
        eq(WordsGfx.outOf(odd2, WordsGfx.outPick(null, t1, idd)).value, "1920x1080", "1080p default on 1366x768: offered");
        // the start: the default's size is sent when nothing is stored; a stored choice still wins; a profile limited to 720p sends 1280x720
        eq(WordsGfx.env(pw, 1280, 960, WordsGfx.outPick(null, t1, idd), 0, 0, 3).get(0), "DM_WINDOW_SIZE=1920x1080", "start with a 1080p default and nothing stored");
        eq(WordsGfx.env(pw, 2400, 1080, WordsGfx.outPick(null, t7, idd), 0, 0, 3).get(0), "DM_WINDOW_SIZE=1280x720", "start with a 720p default on a 2400x1080 phone");
        eq(WordsGfx.env(pw, 2400, 1080, WordsGfx.outPick(WordsGfx.NATIVE, t7, idd), 0, 0, 3).get(0), "DM_WINDOW_SIZE=1920x1080", "a player's Native beats a 720p default");
        eq(WordsGfx.env(pw, 1280, 960, WordsGfx.outPick(null, tn, idd), 0, 0, 3).get(0), "DM_WINDOW_SIZE=1280x720", "an explicit native default is today's Native");
        eq(WordsGfx.env(profile("words:1280x720"), 2400, 1080, WordsGfx.outPick(null, t1, idd), 0, 0, 3).get(0), "DM_WINDOW_SIZE=1280x720", "1080p default on a 720p-limited profile falls back to Native (1280x720)");
        // what a pick stores, and Reset to defaults (the stored key is removed: Map1 stands for SharedPreferences)
        eq(WordsGfx.outStore(WordsGfx.NATIVE, t7, idd), WordsGfx.NATIVE, "with a 720p default, the player's Native is stored (or the default would come back)");
        eq(WordsGfx.outStore(WordsGfx.NATIVE, t1, idd), WordsGfx.NATIVE, "and with a 1080p default");
        eq(WordsGfx.outStore(WordsGfx.NATIVE, tn, idd), null, "a native default behaves as no row: Native stores nothing");
        eq(WordsGfx.outStore("1280x720", t7, idd), "1280x720", "a size is stored");
        eq(WordsGfx.outStore(null, t7, idd), null, "null clears");
        Map<String, String> prefs = new HashMap<>();   // SharedPreferences' out_res@<title ID>
        String ok = "out_res@" + idd;
        String st = WordsGfx.outStore(WordsGfx.NATIVE, t1, idd); if (st != null) prefs.put(ok, st);
        eq(WordsGfx.outPick(prefs.get(ok), t1, idd), WordsGfx.NATIVE, "the player chose Native over a 1080p default: it holds");
        prefs.remove(ok);   // Prefs.resetGame removes every GAME_SETTINGS key, out_res among them
        eq(WordsGfx.outPick(prefs.get(ok), t1, idd), "1920x1080", "Reset to defaults returns to the profile's default");
        eq(WordsGfx.outPick(prefs.get(ok), "", idd), WordsGfx.NATIVE, "Reset with no row returns to Native, as before");

        // ---- the gradle column and the profile's words: kind ----
        Profile w = profile("words:1920x1080");
        check(w.words(), "words: is the kind");
        size(w.wordsMax(), 1920, 1080, "the kind's largest output");
        check(!w.hasResolution() && !w.renderCfg() && !w.resolutionLive() && w.modes() == null, "it is none of the other kinds: no Game resolution, no render_cfg rows, no live resolution");
        eq(w.resolutionEnv("1920x1080").size(), 0, "Game resolution sends nothing for it");
        size(profile("words:1280x720").wordsMax(), 1280, 720, "another maximum");
        size(profile("words:garbage").wordsMax(), 1920, 1080, "an unreadable maximum is 1080p");
        String line = "0100317013770000\tMRSoH\t0\tunknown\t0 main=5b76a43b\t\t\t\t\twords:1920x1080";   // columns 0-9 of BuildConfig.NX_PROFILES: resolution is the tenth
        check(Profile.parse(line).get(0).words(), "Profile.parse reads the kind from the resolution column");

        // ---- the host's lines for each control ----
        Profile p = w;
        // defaults on the Nova: the window is explicit (Native), the Upscaler is Bilinear, nothing else
        eq(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 0, 0, WordsGfx.SHARP_DEFAULT), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=1"), "defaults on the Nova");
        eq(WordsGfx.env(p, 960, 1280, WordsGfx.NATIVE, 0, 0, WordsGfx.SHARP_DEFAULT), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=1"), "the Nova reported upright");
        // Output resolution: always an explicit WxH
        eq(WordsGfx.env(p, 1280, 960, "1920x1080", 0, 0, 3).get(0), "DM_WINDOW_SIZE=1920x1080", "1080p");
        eq(WordsGfx.env(p, 2400, 1080, "1280x720", 0, 0, 3).get(0), "DM_WINDOW_SIZE=1280x720", "720p on a 1080p-native phone");
        eq(WordsGfx.env(p, 2400, 1080, WordsGfx.NATIVE, 0, 0, 3).get(0), "DM_WINDOW_SIZE=1920x1080", "Native on a 2400x1080 phone");
        eq(WordsGfx.env(p, 1280, 960, "garbage", 0, 0, 3).get(0), "DM_WINDOW_SIZE=1280x720", "an unknown stored output takes Native");
        eq(WordsGfx.env(p, 1280, 960, "1920x1080", 0, 0, 3).get(0), "DM_WINDOW_SIZE=1920x1080", "and the size is sent even where it is not offered as a list entry twice");
        // Internal resolution: a step is sent, the game's own (0) is not
        for (int pct : WordsGfx.PCTS) {
            List<String> e = WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, pct, 0, 3);
            check(e.contains("DM_SCENE_PCT=" + pct), "Internal " + pct + " %: " + e);
        }
        check(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 100, 0, 3).contains("DM_SCENE_PCT=100"), "100 % is sent too (the host writes exactly 1.0)");
        for (int none : new int[] {0, 35, 83, 110, -1})
            check(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, none, 0, 3).stream().noneMatch(s -> s.startsWith("DM_SCENE_PCT")), "Internal " + none + ": Game default sends nothing");
        // Upscaler
        eq(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 0, 1, 3), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=2", "DM_VK_CUPSCALE_SHARP=0.25"), "FSR 1, default sharpness");
        eq(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 0, 2, 0), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=4", "DM_VK_CUPSCALE_SHARP=2.0"), "SGSR 1, softest");
        eq(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 0, 2, 4).get(2), "DM_VK_CUPSCALE_SHARP=0.0", "SGSR 1, max");
        eq(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 0, 0, 0), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=1"), "Bilinear sends no sharpness");
        eq(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 0, 7, 9), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=1"), "a stored mode out of range is Bilinear");
        eq(WordsGfx.env(p, 1280, 960, WordsGfx.NATIVE, 0, 1, 9).get(2), "DM_VK_CUPSCALE_SHARP=0.25", "a stored sharpness out of range is the default");
        for (float s : WordsGfx.SHARPS) check(s >= 0f && s <= 2f, "every sharpness step is inside the host's 0.0 to 2.0: " + s);
        eq(WordsGfx.SHARPS[WordsGfx.SHARP_DEFAULT], 0.25f, "the default sharpness is AMD's 0.25");
        eq(WordsGfx.upscalerLines(0, 3, true), Arrays.asList("DM_VK_CUPSCALE=1", "DM_VK_CUPSCALE_SHARP=0.25"), "a live change back to Bilinear sets the sharpness too");
        eq(WordsGfx.upscalerLines(1, 1, true), Arrays.asList("DM_VK_CUPSCALE=2", "DM_VK_CUPSCALE_SHARP=1.0"), "a live FSR 1 change");
        eq(WordsGfx.UP_ENV.length, WordsGfx.UP_NAMES.length, "one env value a choice");
        eq(Arrays.toString(WordsGfx.UP_ENV), "[1, 2, 4]", "Bilinear 1, FSR 1 2, SGSR 1 4 (the documented DM_VK_CUPSCALE values)");
        // everything together
        eq(WordsGfx.env(p, 1280, 960, "1920x1080", 70, 2, 2), Arrays.asList("DM_WINDOW_SIZE=1920x1080", "DM_SCENE_PCT=70", "DM_VK_CUPSCALE=4", "DM_VK_CUPSCALE_SHARP=0.5"), "all of them");

        // ---- Frame rate: the slider's steps ----
        int[] allowed = {FrameRateCaps.GAME_OWNED, 0, 45, 40, 30, 20};   // Prefs.paceCaps for a profile with fps_limit and the default minimum
        int[] steps = WordsGfx.fpsSteps(allowed);
        eq(Arrays.toString(steps), "[20, 30, 40, 45, 0]", "20, 30, 40, 45, 60 (stored as 0), slowest first, Game owned not a step");
        eq(Arrays.toString(WordsGfx.fpsNames(steps)), "[20 fps, 30 fps, 40 fps, 45 fps, 60 fps]", "the steps' words");
        eq(WordsGfx.fpsPick(steps, 30), 30, "30 is a step");
        eq(WordsGfx.fpsPick(steps, 0), 0, "60 (stored 0) is the last step");
        eq(WordsGfx.fpsPick(steps, 35), 30, "between steps: the one below");
        eq(WordsGfx.fpsPick(steps, 10), 20, "below all: the slowest");
        eq(WordsGfx.fpsIndex(steps, 45), 3, "45's index");
        eq(WordsGfx.fpsIndex(steps, 0), 4, "60's index");
        eq(Arrays.toString(WordsGfx.fpsSteps(new int[] {FrameRateCaps.GAME_OWNED, 0, 30, 20})), "[20, 30, 0]", "a profile with fewer rates");

        // ---- no Game owned; 30 fps is the default, else the nearest offered step ----
        eq(WordsGfx.FPS_DEFAULT, 30, "the default frame rate is 30");
        eq(WordsGfx.fpsStart(steps, -1, -1), 30, "nothing stored, no recommendation: 30 (a step)");
        eq(WordsGfx.fpsStart(steps, FrameRateCaps.GAME_OWNED, -1), 30, "a stale Game owned (-2) reads as the 30 default");
        eq(WordsGfx.fpsStart(steps, FrameRateCaps.GAME_OWNED, FrameRateCaps.GAME_OWNED), 30, "Game owned stored and recommended: still 30");
        eq(WordsGfx.fpsStart(steps, FrameRateCaps.GAME_OWNED, 45), 45, "a stale Game owned gives way to the recommended rate");
        eq(WordsGfx.fpsStart(steps, 45, 20), 45, "a stored rate wins over the recommended one");
        eq(WordsGfx.fpsStart(steps, 0, -1), 0, "a stored 60 (0) stays 60");
        eq(WordsGfx.fpsStart(steps, 20, -1), 20, "a stored 20 stays");
        eq(WordsGfx.fpsStart(new int[] {45, 0}, -1, -1), 45, "30 not offered, 45 and 60 are: 45 is the nearest");
        eq(WordsGfx.fpsStart(new int[] {20, 45, 0}, -1, -1), 20, "30 is 10 from 20 and 15 from 45: 20");
        eq(WordsGfx.fpsStart(new int[] {20, 40, 0}, -1, -1), 20, "30 is equally far from 20 and 40: the slower");
        eq(WordsGfx.fpsStart(new int[] {0}, -1, -1), 0, "only 60 offered: 60");
        eq(WordsGfx.fpsStart(new int[0], -1, -1), 30, "no steps at all: the default as it is");
        eq(WordsGfx.fpsNearest(new int[] {20, 30, 0}, 120), 0, "a rate above all steps lands on the fastest");
        eq(WordsGfx.fpsNearest(new int[] {20, 30, 60, 90, 120}, 100), 90, "100 is nearer 90 than 120");
        int[] wide = WordsGfx.fpsSteps(new int[] {FrameRateCaps.GAME_OWNED, 120, 90, 0, 45, 40, 30, 20});   // a profile with fps_limit_max 120 (fps_owned_default is ignored)
        eq(WordsGfx.fpsStart(wide, FrameRateCaps.GAME_OWNED, -1), 30, "high-rate profile with a stale Game owned: 30");
        eq(Arrays.toString(WordsGfx.fpsSteps(new int[] {FrameRateCaps.GAME_OWNED})), "[]", "Game owned alone offers no step");
        eq(WordsGfx.NEXT_START, "Applies on next start", "the helper line of the in-game Internal resolution and Graphics quality");

        // ---- the in-game Graphics quality radio group (Handheld first) and the console mode ScreenMode stores (1 handheld, 0 docked) ----
        eq(WordsGfx.gfxModeOfSlot(0), 1, "slot 0 is Handheld = mode 1");
        eq(WordsGfx.gfxModeOfSlot(1), 0, "slot 1 is Docked = mode 0");
        eq(WordsGfx.GFX_NAMES[WordsGfx.gfxModeOfSlot(0)] + "," + WordsGfx.GFX_NAMES[WordsGfx.gfxModeOfSlot(1)], "Handheld,Docked", "the group lists Handheld first");
        for (int m = 0; m < 2; m++) eq(WordsGfx.gfxModeOfSlot(WordsGfx.gfxSlotOfMode(m)), m, "mode " + m + " survives slot and back");
        // the in-game Graphics quality log lines (ScreenMode.keep)
        eq(WordsGfx.gfxPickedLine("010071F0143EA000", 1), "Graphics quality: Handheld picked in game, writing console_mode@010071F0143EA000=1 for the next start", "picked line, Handheld");
        eq(WordsGfx.gfxKeptLine("010071F0143EA000", 1, 1), "Graphics quality: kept Handheld (console_mode@010071F0143EA000=1) for the next start", "kept line, Handheld");
        eq(WordsGfx.gfxKeptLine("0100317013770000", 0, 0), "Graphics quality: kept Docked (console_mode@0100317013770000=0) for the next start", "kept line, Docked");
        eq(WordsGfx.gfxSlotOfMode(1), 0, "the checked slot of Handheld");
        eq(WordsGfx.gfxSlotOfMode(0), 1, "the checked slot of Docked");

        // ---- the profile's own default (game.tsv fps_default -> NX_PEANUTS_FPS_DEFAULT): stored, else fps_default, else recommended, else 30 ----
        String fid = "0100000000030000", sid = "0100317013770000";
        eq(WordsGfx.fpsDefault("", fid), -1, "an empty table: no default");
        eq(WordsGfx.fpsDefault(null, fid) + WordsGfx.fpsDefault(fid + ":60", null), -2, "no table or no title: none");
        eq(WordsGfx.fpsDefault(fid + ":60", fid), 60, "a 60 entry");
        eq(WordsGfx.fpsDefault(sid + ":30 " + fid + ":45", fid), 45, "the entry of the title among several");
        eq(WordsGfx.fpsDefault(sid + ":30 " + fid + ":45", sid), 30, "and of the other title");
        eq(WordsGfx.fpsDefault(sid + ":30", fid), -1, "another title's entry is not this title's");
        for (String bad : new String[] {fid + ":", fid + ":6", fid + ":600", fid + ":+60", fid + ":6x", fid + ":-60", fid + ":00", fid + ":05", fid + ":game", ":60", fid + "60"})
            eq(WordsGfx.fpsDefault(bad, fid), -1, "a malformed entry '" + bad + "' is none (the default order applies)");
        int[] r30 = {30, 40, 0};   // fps_modes 30,40,60 (Profile.fpsModeCaps without Game owned), as stored: 60 is 0
        eq(WordsGfx.fpsStart(r30, -1, 60, -1), 0, "nothing stored, fps_default 60: 60 (stored as 0)");
        eq(WordsGfx.fpsStart(r30, -1, -1, -1), 30, "no fps_default: 30");
        eq(WordsGfx.fpsStart(r30, -1, 40, -1), 40, "fps_default 40");
        eq(WordsGfx.fpsStart(r30, 30, 60, -1), 30, "a stored choice wins over fps_default");
        eq(WordsGfx.fpsStart(r30, 0, 30, -1), 0, "a stored 60 wins over a lower fps_default");
        eq(WordsGfx.fpsStart(r30, FrameRateCaps.GAME_OWNED, 60, -1), 0, "a stale Game owned (-2) counts as none: fps_default");
        eq(WordsGfx.fpsStart(r30, -1, 60, 30), 0, "fps_default comes before the recommended rate");
        eq(WordsGfx.fpsStart(r30, -1, -1, 40), 40, "no fps_default: the recommended rate");
        eq(WordsGfx.fpsStart(r30, 30, 60, 40), 30, "stored before fps_default before recommended");
        eq(WordsGfx.fpsStart(new int[] {20, 30, 0}, -1, 45, -1), 30, "a default that is not a step (the build refuses it) lands on the nearest step");
        for (int sv : new int[] {-1, 20, 30, 45, 0}) eq(WordsGfx.fpsStart(steps, sv, -1, 20), WordsGfx.fpsStart(steps, sv, 20), "the 3-argument form is the default order (stored " + sv + ")");
        // a checkbox dialog's title follows the box (Internal resolution with Game default ticked read "80 %"), and the Game default row value is the words, not a stored step
        eq(WordsGfx.checkTitle("Internal resolution", "Game default", true, "Internal resolution: 80 %"), "Internal resolution: Game default", "ticked: the title says Game default");
        eq(WordsGfx.checkTitle("Internal resolution", "Game default", false, "Internal resolution: 80 %"), "Internal resolution: 80 %", "unticked: the step's title");
        eq(WordsGfx.checkTitle("Internal resolution", null, true, "Internal resolution: 80 %"), "Internal resolution: 80 %", "no checkbox: the step's title");
        eq(WordsGfx.pctLabel(0), "Game default", "Internal resolution nothing stored: the row reads Game default, not the resting step");
        eq(WordsGfx.pctLabel(80), "80 %", "a stored step reads as the step");
        // the gate is a function of the current Graphics quality only: the rows read it again at every build (Game settings builds again after a choice), so flipping the mode flips the rows
        WordsGfx.Gate fg = WordsGfx.gateOf("010071F0143EA000:docked_only=output,internal;hidden=upscaler", "010071F0143EA000");
        for (int pass = 0; pass < 3; pass++) {
            eq(fg.gated("output", 0) || fg.gated("internal", 0), false, "Docked: Output and Internal are live (pass " + pass + ")");
            eq(fg.gated("output", 1) && fg.gated("internal", 1), true, "Handheld: Output and Internal are Docked only (pass " + pass + ")");
        }
        check(!fg.shows("upscaler") && !fg.gated("upscaler", 1) && !fg.gated("upscaler", 0), "hidden=upscaler: the Internal upscaler row is never shown, in either mode");
        // the whole path as Prefs.paceCap runs it: a profile with fps_modes, a stored choice in a store, Reset to defaults removing it
        Profile fpr = profileOf(fid, "words:1920x1080");
        fpr.fpsModes = "30,40,60";
        String tab = fid + ":60", key = "fps_cap@" + fid;
        Map1 pf = new Map1();
        eq(paceLike(pf, fpr, key, tab, fid, -1), 0, "first run, fps_default 60: 60 fps (DM_FPS_LIMIT 60)");
        pf.putInt(key, 30);
        eq(paceLike(pf, fpr, key, tab, fid, -1), 30, "the player picked 30: 30");
        pf.remove(key);   // Reset to defaults (Prefs.GAME_SETTINGS has fps_cap)
        eq(paceLike(pf, fpr, key, tab, fid, -1), 0, "Reset to defaults: back to 60");
        pf.putInt(key, 40);
        eq(paceLike(pf, fpr, key, "", fid, -1), 40, "no fps_default, 40 stored: 40");
        pf.remove(key);
        eq(paceLike(pf, fpr, key, "", fid, -1), 30, "no fps_default, nothing stored: 30");
        eq(paceLike(pf, fpr, key, sid + ":30", fid, -1), 30, "another title's entry does not apply: 30");

        // ---- the old "3D at full window size" switch ----
        String id = "0100317013770000";
        Map1 s = new Map1();
        s.m.put(WordsGfx.legacyKey(id), true);
        check(WordsGfx.migrate(s, id), "the old switch on: moved");
        eq(s.m.get(WordsGfx.key(WordsGfx.SCENE_KEY, id)), 100, "Internal resolution is 100 %");
        check(!s.has(WordsGfx.legacyKey(id)), "and the old switch is dropped");
        check(!WordsGfx.migrate(s, id), "a second run does nothing");
        eq(s.m.get(WordsGfx.key(WordsGfx.SCENE_KEY, id)), 100, "and keeps 100 %");
        s.putInt(WordsGfx.key(WordsGfx.SCENE_KEY, id), 60);   // the player moved the slider afterwards
        WordsGfx.migrate(s, id);
        eq(s.m.get(WordsGfx.key(WordsGfx.SCENE_KEY, id)), 60, "a later choice is not overwritten");
        Map1 off = new Map1();
        off.m.put(WordsGfx.legacyKey(id), false);
        check(WordsGfx.migrate(off, id), "the old switch off: dropped");
        check(!off.has(WordsGfx.key(WordsGfx.SCENE_KEY, id)) && !off.has(WordsGfx.legacyKey(id)), "off stores nothing: the game's own scale");
        Map1 none = new Map1();
        check(!WordsGfx.migrate(none, id) && none.m.isEmpty(), "no old key: nothing happens");
        Map1 other = new Map1();
        other.m.put(WordsGfx.legacyKey("0100000000010000"), true);
        check(!WordsGfx.migrate(other, id) && other.has(WordsGfx.legacyKey("0100000000010000")), "another game's key is left alone");
        eq(WordsGfx.legacyKey(id), "opt_DM_MRSOH_3D_FULL@0100317013770000", "the key is Prefs.gameOption's: opt_<env>@<title ID>");
        eq(WordsGfx.pctOf(100), 100, "a stored step"); eq(WordsGfx.pctOf(83), 0, "a stored value that is no step is the game's own");

        // ---- every other game is as it was ----
        for (String r : new String[] {"", "window", "scale:1920x1080=2/console,1280x720=1/handheld", "render:720:1920x1080:1280x720:::0"}) {
            Profile o = profile(r);
            check(!o.words(), "not words: " + r);
            eq(WordsGfx.env(o, 1280, 960, WordsGfx.NATIVE, 70, 2, 0).size(), 0, "no lines for " + r);
            check(!WordsGfx.unlocksMode(o) && !WordsGfx.hidesResScale(o), "no gates for " + r);
        }
        check(profile("window").hasResolution() && profile("scale:1920x1080=2/console,1280x720=1/handheld").hasResolution() && profile("render:720:1920x1080:1280x720:::0").renderCfg(), "the other kinds are still themselves");
        eq(WordsGfx.env(null, 1280, 960, WordsGfx.NATIVE, 70, 2, 0).size(), 0, "no profile: no lines");
        check(!WordsGfx.unlocksMode(null) && !WordsGfx.hidesResScale(null), "no profile: no gates");
        check(WordsGfx.unlocksMode(w) && WordsGfx.hidesResScale(w), "the words: kind unlocks Graphics quality and hides Resolution scale");

        // ---- the restart line ----
        eq(WordsGfx.restartSig(new int[] {1280, 720}, 0, 1), "1280x720|0|1", "the sig");
        check(!WordsGfx.restartSig(new int[] {1280, 720}, 0, 1).equals(WordsGfx.restartSig(new int[] {1920, 1080}, 0, 1)), "another output differs");
        check(!WordsGfx.restartSig(new int[] {1280, 720}, 0, 1).equals(WordsGfx.restartSig(new int[] {1280, 720}, 80, 1)), "another Internal resolution differs");
        check(!WordsGfx.restartSig(new int[] {1280, 720}, 0, 1).equals(WordsGfx.restartSig(new int[] {1280, 720}, 0, 0)), "another Graphics quality differs");
        eq(WordsGfx.pctLabel(80), "80 %", "Internal resolution shows the percentage only");
        eq(WordsGfx.pctLabel(0), "Game default", "or Game default");
        eq(WordsGfx.PCT_NAMES[0] + WordsGfx.PCT_NAMES[WordsGfx.PCT_NAMES.length - 1], "40 %100 %", "from 40 % to 100 %");
        for (int i = 0; i < WordsGfx.PCTS.length; i++) eq(WordsGfx.PCT_NAMES[i], WordsGfx.PCTS[i] + " %", "step " + i + "'s name has the percentage and no size");
        eq(WordsGfx.GFX_SHORT[0] + WordsGfx.GFX_SHORT[1], "DockedHandheld", "ScreenMode's order: 0 docked, 1 handheld");
        // ---- a fixed-window title (game.tsv view_scale_fixed -> NX_PEANUTS_FIXED_WINDOW) and row gating (game.tsv words_gate -> NX_PEANUTS_WORDS_GATE) ----
        // N1, the four documented panels, then the edges: runtime Native is 64k x 36k, capped at the fixed window
        size(WordsGfx.nativeFixed(1280, 960, 1920, 1080), 1280, 720, "N1 Nova 1280x960");
        size(WordsGfx.nativeFixed(2400, 1080, 1920, 1080), 1920, 1080, "N1 2400x1080 phone");
        size(WordsGfx.nativeFixed(1366, 768, 1920, 1080), 1344, 756, "N1 1366x768");
        size(WordsGfx.nativeFixed(2560, 1600, 1920, 1080), 1920, 1080, "N1 2560x1600 capped at the window 1920x1080");
        size(WordsGfx.nativeFixed(2560, 1600, 3840, 2160), 2560, 1440, "N1 2560x1600 under a larger window: 2560x1440");
        size(WordsGfx.nativeFixed(960, 1280, 1920, 1080), 1280, 720, "N1 the Nova reported upright");
        size(WordsGfx.nativeFixed(1920, 1080, 1920, 1080), 1920, 1080, "N1 exactly the window");
        size(WordsGfx.nativeFixed(1919, 1079, 1920, 1080), 1856, 1044, "N1 one pixel short of 1080p: k = 29");
        size(WordsGfx.nativeFixed(3840, 2160, 1920, 1080), 1920, 1080, "N1 4K capped at the window");
        size(WordsGfx.nativeFixed(0, 0, 1920, 1080), 1280, 720, "N1 an unknown panel is 1280x720");
        size(WordsGfx.nativeFixed(0, 0, 640, 360), 640, 360, "N1 an unknown panel under a smaller window is the window");
        size(WordsGfx.nativeFixed(1280, 720, 1280, 720), 1280, 720, "N1 a 1280x720 window");
        for (int pnlW : new int[] {320, 640, 800, 1000, 1080, 1280, 1366, 1500, 1600, 1920, 2200, 2400, 2560, 3000, 3840})
            for (int ph : new int[] {180, 360, 480, 720, 768, 800, 960, 1080, 1200, 1440, 1600, 2160}) {
                int[] n = WordsGfx.nativeFixed(pnlW, ph, 1920, 1080);
                check(n[0] * 9 == n[1] * 16, "N1 any panel " + pnlW + "x" + ph + " is 16:9: " + n[0] + "x" + n[1]);
                check(n[0] <= 1920 && n[1] <= 1080, "N1 any panel " + pnlW + "x" + ph + " is within the window: " + n[0] + "x" + n[1]);
                if (Math.max(pnlW, ph) >= 64 && Math.min(pnlW, ph) >= 36) check(n[0] <= Math.max(pnlW, ph) && n[1] <= Math.min(pnlW, ph), "N1 it fits the panel " + pnlW + "x" + ph + ": " + n[0] + "x" + n[1]);
            }

        // the table NX_PEANUTS_FIXED_WINDOW: "<title ID>:<W>x<H>", space-separated
        String fw = "010071F0143EA000", fixT = fw + ":1920x1080", fixT2 = "0100317013770001:1280x720 " + fixT;
        size(WordsGfx.fixedSize(fixT, fw), 1920, 1080, "the fixed window of the title");
        size(WordsGfx.fixedSize(fixT2, fw), 1920, 1080, "the second entry of a table");
        size(WordsGfx.fixedSize(fixT2, "0100317013770001"), 1280, 720, "the first entry of a table");
        check(WordsGfx.fixedSize(fixT, idd) == null, "Sparks of Hope is not fixed");
        for (String bad : new String[] {"", fw + ":", fw + ":1920", fw + ":1920x", fw + ":0x0", fw + ":ax1080", fw + ":1920x1080x1", fw, ":1920x1080"})
            check(WordsGfx.fixedSize(bad, fw) == null, "an unreadable entry '" + bad + "' is not fixed");
        check(WordsGfx.fixedSize(null, fw) == null && WordsGfx.fixedSize(fixT, null) == null, "no table or no title: not fixed");

        // the Output choices: Native, 1080p, 720p, all capped at the window; a windowed title's list is the old one
        List<WordsGfx.Out> fNova = WordsGfx.outs(1280, 960, new int[] {1920, 1080}, true);
        eq(fNova.size(), 2, "fixed on the Nova: Native and 1080p");
        eq(fNova.get(0).label(), "Native (1280x720)", "fixed Nova Native");
        eq(fNova.get(1).label(), "1080p (1920x1080)", "fixed Nova 1080p");
        List<WordsGfx.Out> fOdd = WordsGfx.outs(1366, 768, new int[] {1920, 1080}, true);
        eq(fOdd.size(), 3, "fixed on 1366x768: Native, 1080p and 720p");
        eq(fOdd.get(0).label(), "Native (1344x756)", "fixed 1366x768 Native is 1344x756");
        eq(fOdd.get(1).label(), "1080p (1920x1080)", "fixed 1366x768 1080p");
        eq(fOdd.get(2).label(), "720p (1280x720)", "fixed 1366x768 720p");
        List<WordsGfx.Out> fPhone = WordsGfx.outs(2400, 1080, new int[] {1920, 1080}, true);
        eq(fPhone.size(), 2, "fixed on a 2400x1080 phone: Native (1920x1080) and 720p");
        eq(fPhone.get(0).label(), "Native (1920x1080)", "fixed phone Native");
        eq(fPhone.get(1).label(), "720p (1280x720)", "fixed phone 720p");
        List<WordsGfx.Out> fTab = WordsGfx.outs(2560, 1600, new int[] {1920, 1080}, true);
        eq(fTab.get(0).label(), "Native (1920x1080)", "fixed 2560x1600 Native is capped at the window");
        eq(fTab.size(), 2, "fixed 2560x1600: Native and 720p");
        for (WordsGfx.Out o : WordsGfx.outs(1366, 768, new int[] {1280, 720}, true)) check(o.size[0] <= 1280 && o.size[1] <= 720, "a 1280x720 window caps every choice: " + o.label());
        eq(WordsGfx.outs(1366, 768, new int[] {1280, 720}, true).size(), 1, "a 1280x720 window: Native (the window) alone, 1080p is over the window and 720p is Native's size");
        for (int[] scr : new int[][] {{1280, 960}, {2400, 1080}, {1366, 768}, {2560, 1600}, {800, 1280}}) {
            List<WordsGfx.Out> was = WordsGfx.outChoices(WordsGfx.nativeOut(scr[0], scr[1], 1920, 1080), 1920, 1080), now = WordsGfx.outs(scr[0], scr[1], new int[] {1920, 1080}, false);
            eq(now.size(), was.size(), "windowed list unchanged " + scr[0] + "x" + scr[1]);
            for (int i = 0; i < was.size(); i++) eq(now.get(i).label() + "|" + now.get(i).value, was.get(i).label() + "|" + was.get(i).value, "windowed choice " + i + " unchanged " + scr[0] + "x" + scr[1]);
        }
        eq(WordsGfx.nativeOut(1366, 768, 1920, 1080)[0], 1280, "nativeOut is untouched: 1366x768 is still 1280x720 for a windowed title");

        // the env of a fixed-window title: DM_OUT_SIZE (the 3D target size), never DM_WINDOW_SIZE
        Profile fp = profileOf(fw, "words:1920x1080");
        eq(WordsGfx.env(fp, 1280, 960, WordsGfx.NATIVE, 0, 0, 3, fixT, "", 0), Arrays.asList("DM_OUT_SIZE=1280x720", "DM_VK_CUPSCALE=1"), "fixed: defaults on the Nova");
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 0, 3, fixT, "", 0), Arrays.asList("DM_OUT_SIZE=1920x1080", "DM_SCENE_PCT=70", "DM_VK_CUPSCALE=1"), "fixed: 1080p, Internal 70 %");
        eq(WordsGfx.env(fp, 1366, 768, WordsGfx.NATIVE, 0, 0, 3, fixT, "", 0).get(0), "DM_OUT_SIZE=1344x756", "fixed: Native on 1366x768");
        eq(WordsGfx.env(fp, 1366, 768, "1280x720", 0, 0, 3, fixT, "", 0).get(0), "DM_OUT_SIZE=1280x720", "fixed: 720p");
        eq(WordsGfx.env(fp, 2560, 1600, WordsGfx.NATIVE, 0, 0, 3, fixT, "", 0).get(0), "DM_OUT_SIZE=1920x1080", "fixed: Native on 2560x1600 is the window");
        eq(WordsGfx.env(fp, 1280, 960, "garbage", 0, 0, 3, fixT, "", 0).get(0), "DM_OUT_SIZE=1280x720", "fixed: an unknown stored output takes Native");
        eq(WordsGfx.env(fp, 1280, 960, WordsGfx.NATIVE, 100, 2, 0, fixT, "", 0), Arrays.asList("DM_OUT_SIZE=1280x720", "DM_SCENE_PCT=100", "DM_VK_CUPSCALE=4", "DM_VK_CUPSCALE_SHARP=2.0"), "fixed: all of them");
        eq(WordsGfx.env(profileOf(fw, "words:1280x720"), 1366, 768, "1920x1080", 0, 0, 3, fw + ":1280x720", "", 0).get(0), "DM_OUT_SIZE=1280x720", "fixed 1280x720: a stored 1080p falls back to Native, the window");
        for (String sto : new String[] {WordsGfx.NATIVE, "1920x1080", "1280x720", "garbage"})
            for (int[] scr : new int[][] {{1280, 960}, {2400, 1080}, {1366, 768}, {2560, 1600}}) {
                List<String> e = WordsGfx.env(fp, scr[0], scr[1], sto, 70, 1, 2, fixT, "", 0);
                check(e.stream().noneMatch(x -> x.startsWith("DM_WINDOW_SIZE")), "fixed: never DM_WINDOW_SIZE (" + sto + " " + scr[0] + "x" + scr[1] + ")");
                check(e.stream().filter(x -> x.startsWith("DM_OUT_SIZE=")).count() == 1, "fixed: exactly one DM_OUT_SIZE (" + sto + " " + scr[0] + "x" + scr[1] + ")");
            }
        // not fixed by the table: the windowed env is today's (the table names another title)
        eq(WordsGfx.env(pw, 1280, 960, WordsGfx.NATIVE, 0, 0, 3, fixT, "", 0), Arrays.asList("DM_WINDOW_SIZE=1280x720", "DM_VK_CUPSCALE=1"), "a title the table does not name keeps DM_WINDOW_SIZE");

        // words_gate: the table "<title ID>:docked_only=<list>;hidden=<list>"
        String gateT = fw + ":docked_only=output,internal;hidden=upscaler";
        WordsGfx.Gate g = WordsGfx.gateOf(gateT, fw);
        check(g.shows("output") && g.shows("internal") && !g.shows("upscaler"), "hidden=upscaler hides only the upscaler");
        check(g.gated("output", 1) && g.gated("internal", 1) && !g.gated("upscaler", 1), "docked_only rows are gated in Handheld (a hidden row is not shown, so not gated)");
        check(!g.gated("output", 0) && !g.gated("internal", 0), "Docked: nothing is gated");
        check(!g.sends("output", 1) && !g.sends("internal", 1) && !g.sends("upscaler", 1) && !g.sends("upscaler", 0), "Handheld sends neither Output nor Internal; a hidden upscaler never sends");
        check(g.sends("output", 0) && g.sends("internal", 0), "Docked sends Output and Internal");
        eq(WordsGfx.DOCKED_ONLY, "Docked only", "the disabled row's value, exactly");
        WordsGfx.Gate noGate = WordsGfx.gateOf(gateT, idd);
        check(noGate.shows("output") && noGate.shows("internal") && noGate.shows("upscaler") && !noGate.gated("output", 1) && noGate.sends("upscaler", 1), "another title: no gate");
        for (String bad : new String[] {"", fw, fw + ":", fw + ":docked_only", fw + ":docked_only=", fw + ":nonsense=output", fw + ":docked_only=sharpness", "x:docked_only=output"}) {
            WordsGfx.Gate b = WordsGfx.gateOf(bad, fw);
            check(b.shows("output") && b.shows("internal") && b.shows("upscaler") && !b.gated("output", 1) && !b.gated("internal", 1) && !b.gated("upscaler", 1), "an unreadable table '" + bad + "' is no gate");
        }
        check(WordsGfx.gateOf(null, fw).shows("upscaler") && WordsGfx.gateOf(gateT, null).shows("upscaler"), "no table or no title: no gate");
        WordsGfx.Gate d1 = WordsGfx.gateOf(fw + ":docked_only=upscaler", fw);
        check(d1.shows("upscaler") && d1.gated("upscaler", 1) && !d1.gated("upscaler", 0) && !d1.gated("output", 1), "docked_only alone, on the upscaler");
        WordsGfx.Gate h1 = WordsGfx.gateOf(fw + ":hidden=output,internal", fw);
        check(!h1.shows("output") && !h1.shows("internal") && h1.shows("upscaler") && !h1.gated("output", 1), "hidden alone");
        WordsGfx.Gate both = WordsGfx.gateOf(fw + ":docked_only=output;hidden=output", fw);
        check(!both.shows("output") && !both.gated("output", 1), "hidden wins over docked_only");
        WordsGfx.Gate two = WordsGfx.gateOf("0100317013770001:hidden=output " + gateT, fw);
        check(two.shows("output") && !two.shows("upscaler"), "the title's entry in a table of two");

        // the env while gated
        String hx = "DM_VK_CUPSCALE";
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 1, 2, fixT, gateT, 0), Arrays.asList("DM_OUT_SIZE=1920x1080", "DM_SCENE_PCT=70"), "Docked, gate: Output and Internal go, the hidden upscaler sends no DM_VK_CUPSCALE*");
        check(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 2, 0, fixT, gateT, 0).stream().noneMatch(x -> x.startsWith(hx)), "hidden=upscaler: no cupscale env whatever the stored mode and sharpness");
        check(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 2, 0, fixT, gateT, 1).isEmpty(), "Handheld, gate: no DM_OUT_SIZE, no DM_SCENE_PCT, no DM_VK_CUPSCALE*");
        check(WordsGfx.env(fp, 1280, 960, WordsGfx.NATIVE, 0, 0, 3, fixT, gateT, 1).isEmpty(), "Handheld, gate, defaults: nothing at all");
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 1, 2, fixT, fw + ":docked_only=output", 1), Arrays.asList("DM_SCENE_PCT=70", "DM_VK_CUPSCALE=2", "DM_VK_CUPSCALE_SHARP=0.5"), "Handheld, only Output gated: no DM_OUT_SIZE");
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 0, 3, fixT, fw + ":docked_only=internal", 1), Arrays.asList("DM_OUT_SIZE=1920x1080", "DM_VK_CUPSCALE=1"), "Handheld, only Internal gated: no DM_SCENE_PCT");
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 1, 2, fixT, fw + ":docked_only=upscaler", 1), Arrays.asList("DM_OUT_SIZE=1920x1080", "DM_SCENE_PCT=70"), "Handheld, upscaler docked_only: no cupscale env");
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 1, 2, fixT, fw + ":docked_only=upscaler", 0), Arrays.asList("DM_OUT_SIZE=1920x1080", "DM_SCENE_PCT=70", "DM_VK_CUPSCALE=2", "DM_VK_CUPSCALE_SHARP=0.5"), "Docked, upscaler docked_only: sent");
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 1, 2, fixT, fw + ":hidden=output,internal", 0), Arrays.asList("DM_VK_CUPSCALE=2", "DM_VK_CUPSCALE_SHARP=0.5"), "hidden Output and Internal send nothing");
        // the same title through the short form and through no gate: as before
        eq(WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 1, 2, fixT, "", 1), WordsGfx.env(fp, 1280, 960, "1920x1080", 70, 1, 2, fixT, "", 0), "no gate: Handheld changes nothing");
        // a windowed title (Sparks of Hope) is byte-identical with or without the new arguments, in either Graphics quality
        for (String sto : new String[] {WordsGfx.NATIVE, "1920x1080", "1280x720", "garbage"})
            for (int[] scr : new int[][] {{1280, 960}, {2400, 1080}, {1366, 768}})
                for (int pc : new int[] {0, 40, 70, 100})
                    for (int m : new int[] {0, 1}) {
                        eq(WordsGfx.env(pw, scr[0], scr[1], sto, pc, 2, 1, fixT, gateT, m), WordsGfx.env(pw, scr[0], scr[1], sto, pc, 2, 1), "Sparks of Hope env is identical (" + sto + " " + scr[0] + "x" + scr[1] + " " + pc + " mode " + m + ")");
                        eq(WordsGfx.env(pw, scr[0], scr[1], sto, pc, 2, 1, "", "", m), WordsGfx.env(pw, scr[0], scr[1], sto, pc, 2, 1), "Sparks of Hope env with empty tables is identical (" + sto + " " + pc + " mode " + m + ")");
                    }
        // a gate on a windowed title acts the same way on its own env line (DM_WINDOW_SIZE)
        check(WordsGfx.env(pw, 1280, 960, "1920x1080", 70, 0, 3, "", idd + ":docked_only=output", 1).stream().noneMatch(x -> x.startsWith("DM_WINDOW_SIZE")), "a windowed title with docked_only=output in Handheld: no DM_WINDOW_SIZE");
        // not words: a gate or a fixed window does nothing
        eq(WordsGfx.env(profileOf(fw, "window"), 1280, 960, WordsGfx.NATIVE, 70, 2, 0, fixT, gateT, 0).size(), 0, "no words: kind, no lines even with the tables");
        System.out.println("ok");
    }
}
