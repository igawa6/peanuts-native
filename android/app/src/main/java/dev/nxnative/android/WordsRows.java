package dev.nxnative.android;
// PEANUTS: new file. The Android side of WordsGfx, for a game whose profile has window_words rows (Profile.words): the stored choices (per title ID, Prefs.gameKey), the
// start's lines (GameActivity.settingsEnv) and the rows of Game settings › Picture and Speed. The in-game panel's rows are SidePanel's (wordsPicture, wordsFps), with the
// same choices and words. It lives in this package because Ui.Setting, Prefs.of and Profile are package-private; the hooks are one line each (GameSettings, SidePanel,
// GameActivity, ScreenMode, ResScale, Prefs.GAME_SETTINGS).

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.function.Consumer;

final class WordsRows {
    private WordsRows() {}

    static final String OUT = "out_res", CUP_MODE = "cup_mode", CUP_SHARP = "cup_sharp", FPS_LAST = "fps_last" /* no longer used (it served "Game owned"); still removed by Reset to defaults */;   // WordsGfx.SCENE_KEY is Internal resolution's
    /** What this run's start fixed (the Output size, Internal resolution and Graphics quality: WordsGfx.restartSig), for the in-game panel's Restart line; set by env */
    private static String startedSig;

    static boolean on(String id) { Profile p = Profile.of(id); return p != null && p.words(); }

    // ---- The stored choices ----

    private static SharedPreferences sp(Context c) { return Prefs.of(c); }

    /** The old "3D at full window size" switch becomes Internal resolution 100 % (WordsGfx.migrate); run on every read of a choice, a no-op once the old key is gone */
    private static void migrate(Context c, String id) {
        SharedPreferences p = sp(c);
        if (!p.contains(WordsGfx.legacyKey(id))) return;
        WordsGfx.migrate(new WordsGfx.Store() {
            @Override public boolean has(String key) { return p.contains(key); }
            @Override public boolean bool(String key) { return p.getBoolean(key, false); }
            @Override public void putInt(String key, int v) { p.edit().putInt(key, v).commit(); }
            @Override public void remove(String key) { p.edit().remove(key).commit(); }
        }, id);
    }

    /** Output resolution as stored: WordsGfx.NATIVE or "WxH" (the choices list falls back to Native for one it does not have) */
    // PEANUTS: nothing stored gives the profile's Output default (game.tsv output_default; Native without a row), the choices list limits it; Reset to defaults removes the key
    static String outPref(Context c, String id) { return WordsGfx.outPick(sp(c).getString(Prefs.gameKey(OUT, id), null), BuildConfig.NX_PEANUTS_OUTPUT_DEFAULT, id); }
    static void outPref(Context c, String id, String v) {
        String s = WordsGfx.outStore(v, BuildConfig.NX_PEANUTS_OUTPUT_DEFAULT, id);
        if (s == null) sp(c).edit().remove(Prefs.gameKey(OUT, id)).commit(); else sp(c).edit().putString(Prefs.gameKey(OUT, id), s).commit();
    }

    /** Internal resolution: a step of WordsGfx.PCTS, 0 for the game's own (nothing sent) */
    static int scenePct(Context c, String id) { migrate(c, id); return WordsGfx.pctOf(sp(c).getInt(Prefs.gameKey(WordsGfx.SCENE_KEY, id), 0)); }
    static void scenePct(Context c, String id, Integer v) {
        if (v == null) sp(c).edit().remove(Prefs.gameKey(WordsGfx.SCENE_KEY, id)).commit(); else sp(c).edit().putInt(Prefs.gameKey(WordsGfx.SCENE_KEY, id), v).commit();
    }

    /** The Upscaler (index into WordsGfx.UP_ENV) and its sharpness (index into WordsGfx.SHARPS) */
    static int cupMode(Context c, String id) { return WordsGfx.upMode(sp(c).getInt(Prefs.gameKey(CUP_MODE, id), 0)); }
    static void cupMode(Context c, String id, int v) { if (v == 0) sp(c).edit().remove(Prefs.gameKey(CUP_MODE, id)).commit(); else sp(c).edit().putInt(Prefs.gameKey(CUP_MODE, id), v).commit(); }
    static boolean ownCupSharp(Context c, String id) { return sp(c).contains(Prefs.gameKey(CUP_SHARP, id)); }
    static int cupSharp(Context c, String id) { return WordsGfx.sharpIndex(sp(c).getInt(Prefs.gameKey(CUP_SHARP, id), WordsGfx.SHARP_DEFAULT)); }
    static void cupSharp(Context c, String id, Integer v) { if (v == null) sp(c).edit().remove(Prefs.gameKey(CUP_SHARP, id)).commit(); else sp(c).edit().putInt(Prefs.gameKey(CUP_SHARP, id), v).commit(); }

    // ---- The screen, the choices, the lines ----

    /** The display's own size in pixels (the most the game's window can be on it): the Output choices and Native are made from it, in the launcher and in the game alike */
    static int[] screen(Activity a) {
        android.graphics.Rect b = a.getWindowManager().getMaximumWindowMetrics().getBounds();
        return new int[] {b.width(), b.height()};
    }

    // PEANUTS begin: a fixed-window title (NX_PEANUTS_FIXED_WINDOW: Native at run time, the Output row is the 3D target size) and a title's gate (NX_PEANUTS_WORDS_GATE)
    static boolean fixed(String id) { return WordsGfx.fixedSize(BuildConfig.NX_PEANUTS_FIXED_WINDOW, id) != null; }
    static WordsGfx.Gate gate(String id) { return WordsGfx.gateOf(BuildConfig.NX_PEANUTS_WORDS_GATE, id); }
    /** The row is shown disabled now: docked_only while the title's effective Graphics quality is Handheld (ScreenMode.mode) */
    static boolean gated(Context c, String id, String row) { return gate(id).gated(row, ScreenMode.mode(c, id)); }
    // PEANUTS end

    static List<WordsGfx.Out> outs(Activity a, Profile p) {
        int[] s = screen(a), fix = WordsGfx.fixedSize(BuildConfig.NX_PEANUTS_FIXED_WINDOW, p.titleId);   // PEANUTS: a fixed-window title's maximum is its window
        return WordsGfx.outs(s[0], s[1], fix != null ? fix : p.wordsMax(), fix != null);
    }

    /** The Output size the stored choice gives on this screen */
    static int[] outSize(Activity a, String id) {
        Profile p = Profile.of(id);
        return WordsGfx.outOf(outs(a, p), outPref(a, id)).size;
    }

    /** What the start fixes now, as it would be at the next start (WordsGfx.restartSig); the in-game panel compares it with the run's */
    static String sig(Activity a, String id) { return WordsGfx.restartSig(outSize(a, id), scenePct(a, id), ScreenMode.mode(a, id)); }
    static boolean restartNeeded(Activity a, String id) { return startedSig != null && !startedSig.equals(sig(a, id)); }

    /** GameActivity.settingsEnv: the host's lines for a words: game (WordsGfx.env), and the sig this run starts with */
    static void env(Activity a, String id, List<String> env) {
        Profile p = Profile.of(id);
        if (p == null || !p.words()) return;
        int[] s = screen(a);
        env.addAll(WordsGfx.env(p, s[0], s[1], outPref(a, id), scenePct(a, id), cupMode(a, id), cupSharp(a, id),
                BuildConfig.NX_PEANUTS_FIXED_WINDOW, BuildConfig.NX_PEANUTS_WORDS_GATE, ScreenMode.mode(a, id)));   // PEANUTS: fixed window, gate, Graphics quality
        startedSig = sig(a, id);
    }

    /** The Upscaler's lines sent to the running game (the in-game panel; the host reads them as it does DM_VK_NDSR's, through the live control file) */
    static void pushUpscaler(Context c, String id) { GameUpscaler.send(c, WordsGfx.upscalerLines(cupMode(c, id), cupSharp(c, id), true)); }

    // ---- Game settings: Picture and Speed ----

    private static void radio(MainActivity a, String title, String[] labels, int checked, java.util.function.IntConsumer picked, Runnable done) {
        new PadFocus.Builder(a).setTitle(title).setSingleChoiceItems(labels, checked, (x, w) -> { x.dismiss(); picked.accept(w); done.run(); }).setNegativeButton("Cancel", null).show();
    }

    /**
     * Picture's rows for a words: game: Output resolution (Native, 1080p, 720p), Internal resolution (a slider with "Game default"), the Internal upscaler (Bilinear / FSR 1 / SGSR 1) with its
     * sharpness under FSR 1 or SGSR 1. Graphics quality is ScreenMode's row, built after these. Each applies at the next start; the in-game panel's Internal upscaler changes at once
     */
    static void settings(MainActivity a, Game g, Consumer<Ui.Setting> add, Runnable again) {
        String id = g.titleId;
        Profile p = Profile.of(id);
        WordsGfx.Gate gt = gate(id);   // PEANUTS: a hidden row is not added; a docked_only row is a disabled row with the value "Docked only" (no helper line) while Graphics quality is Handheld
        int gm = ScreenMode.mode(a, id);
        List<WordsGfx.Out> outs = outs(a, p);
        WordsGfx.Out cur = WordsGfx.outOf(outs, outPref(a, id));
        String[] outLabels = new String[outs.size()];
        for (int i = 0; i < outLabels.length; i++) outLabels[i] = outs.get(i).label();
        if (gt.gated(WordsGfx.ROW_OUTPUT, gm)) add.accept(disabled(new Ui.Setting(a, "🖼️", "Output resolution", null)));
        else if (gt.shows(WordsGfx.ROW_OUTPUT))
            add.accept(new Ui.Setting(a, "🖼️", "Output resolution", v -> radio(a, "Output resolution · " + g.name, outLabels, outs.indexOf(cur), w -> outPref(a, id, outs.get(w).value), again))
                    .value(cur.valueText()).chevron(a));

        int pct = scenePct(a, id);
        if (gt.gated(WordsGfx.ROW_INTERNAL, gm)) add.accept(disabled(new Ui.Setting(a, "🎬", "Internal resolution", null)));
        else if (gt.shows(WordsGfx.ROW_INTERNAL))
            add.accept(new Ui.Setting(a, "🎬", "Internal resolution", v -> checkDialog(a, "Internal resolution", " · " + g.name, WordsGfx.PCT_NAMES,
                            WordsGfx.pctIndex(pct > 0 ? pct : WordsGfx.PCT_REST), null, null, "Game default", pct == 0,
                            (def, i) -> scenePct(a, id, def ? null : WordsGfx.PCTS[i]), again))
                    .value(WordsGfx.pctLabel(pct)).chevron(a));

        int mode = cupMode(a, id);
        if (gt.gated(WordsGfx.ROW_UPSCALER, gm)) add.accept(disabled(new Ui.Setting(a, "🔬", "Internal upscaler", null)));   // no sharpness row under a disabled one
        else if (gt.shows(WordsGfx.ROW_UPSCALER)) {   // hidden=upscaler: neither the row nor its sharpness
            add.accept(new Ui.Setting(a, "🔬", "Internal upscaler", v -> radio(a, "Internal upscaler · " + g.name, WordsGfx.UP_NAMES, mode, w -> cupMode(a, id, w), again))
                    .value(WordsGfx.UP_NAMES[mode]).chevron(a));
            if (mode != 0)
                add.accept(new Ui.Setting(a, "✨", "Upscaler sharpness", v -> checkDialog(a, "Upscaler sharpness", " · " + g.name, WordsGfx.SHARP_NAMES, cupSharp(a, id), null, null, "Default", !ownCupSharp(a, id),
                                (def, i) -> cupSharp(a, id, def ? null : i), again))
                        .value(WordsGfx.SHARP_NAMES[cupSharp(a, id)]).chevron(a).sub());
        }
    }

    /**
     * StepSlider.dialogCheck for the words: rows with a checkbox of their own words (Internal resolution's "Game default", the sharpness's "Default"), where the title reads
     * "&lt;label&gt;: &lt;check&gt;" while the box is checked (WordsGfx.checkTitle: it showed the step the slider rested on, "80 %", with Game default ticked) and the slider is disabled then. Same
     * layout and callbacks as dialogCheck; check is not null here
     */
    private static void checkDialog(MainActivity a, String label, String suffix, String[] names, int index, String left, String right, String check, boolean checked,
                                    java.util.function.BiConsumer<Boolean, Integer> set, Runnable done) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20); box.setPadding(pad, pad / 2, pad, 0);
        int[] cur = {index};
        String[] step = {StepSlider.title(label, names, index)};
        android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        android.widget.CheckBox def = new android.widget.CheckBox(a);
        Runnable retitle = () -> { if (dlg[0] != null) dlg[0].setTitle(WordsGfx.checkTitle(label, check, def.isChecked(), step[0]) + suffix); };
        android.widget.LinearLayout slider = StepSlider.view(a, label, names, index, left, right, t -> { step[0] = t; retitle.run(); }, i -> cur[0] = i);
        def.setText(check);
        def.setChecked(checked);
        slider.setAlpha(checked ? 0.4f : 1f);
        enable(slider, !checked);
        def.setOnCheckedChangeListener((b, on) -> { slider.setAlpha(on ? 0.4f : 1f); enable(slider, !on); retitle.run(); });
        box.addView(def);
        box.addView(slider);
        dlg[0] = new PadFocus.Builder(a).setTitle(WordsGfx.checkTitle(label, check, checked, step[0]) + suffix).setView(box)
                .setPositiveButton("OK", (x, w) -> { set.accept(def.isChecked(), cur[0]); done.run(); })
                .setNegativeButton("Cancel", null).show();
    }

    private static void enable(android.view.View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) enable(g.getChildAt(i), on); }
    }

    /** A docked_only row in Handheld: no tap, dimmed, the value slot reads WordsGfx.DOCKED_ONLY */
    private static Ui.Setting disabled(Ui.Setting s) {
        s.value(WordsGfx.DOCKED_ONLY);
        s.view.setEnabled(false);
        s.view.setClickable(false);
        s.view.setAlpha(0.5f);
        return s;
    }

    /** Speed's Frame rate for a words: game (a limited one): a slider over the profile's rates, no end words and no "Game owned"; the same fps_cap as the radio list, so the panel and the page agree */
    static void capDialog(MainActivity a, Game g, Runnable done) {
        String id = g.titleId;
        int[] steps = WordsGfx.fpsSteps(Prefs.paceCaps(id));
        int shown = WordsGfx.fpsPick(steps, Prefs.paceCap(a, id));
        StepSlider.dialogCheck(a, "Frame rate", " · " + g.name, WordsGfx.fpsNames(steps), WordsGfx.fpsIndex(steps, shown), null, null, null, false,
                (own, i) -> Prefs.fpsCap(a, id, steps[i]), done);
    }
}
