package dev.peanuts.ctl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * game, do=fps_cap | console_mode | second_screen | controls: the running game's live controls (peanuts/docs/CONTROL.md). The game runs in the ":game" process
 * and the control commands in the launcher's, so {@link Ctl} sends the request as an ordered broadcast to this package only ({@link #ACTION}); GameLink's
 * receiver, registered by GameActivity while its screen is there, runs {@link #run} on the game's main thread against the game's own controls (the calls the
 * second screen's stats page makes, dev.peanuts.ui.GameControls) and hands the answer back as the broadcast's result ({@link #EXTRA_ANSWER}, JSON), which
 * {@link #take} puts into the command's result. No Android class here: the JVM tests drive it with a {@link Live} of their own.
 */
public final class GameLive {
    private GameLive() {}

    /** The broadcast GameLink's receiver takes: sent with the launcher's package set, received only from this app (RECEIVER_NOT_EXPORTED) */
    public static final String ACTION = "dev.peanuts.ctl.GAME_LIVE";
    /** The request's arguments as extras of the broadcast */
    public static final List<String> ARGS = Arrays.asList("do", "value", "title_id");
    /** The broadcast's result extra: the answer, Result.toMap as JSON */
    public static final String EXTRA_ANSWER = "answer";
    /** How long the launcher waits for the game's answer */
    public static final long ANSWER_MS = 4000;
    public static final List<String> ACTIONS = Arrays.asList("fps_cap", "console_mode", "second_screen", "controls");
    static final List<String> MODES = Arrays.asList("docked", "handheld"), SCREEN = Arrays.asList("toggle", "on", "off");

    /** The running game's controls, as the game's process has them */
    public interface Live {
        String titleId();
        /** 0 Docked, 1 Handheld; -1 when the game's Resolution sets the mode */
        int consoleMode();
        /** true: applied now; false: stored, it applies at the next start */
        boolean consoleMode(int m);
        /** The caps in menu order, with their labels ("Game owned", "30 fps") and whether each is offered for this game */
        int[] caps();
        String capLabel(int v);
        boolean capOffered(int v);
        /** Why a cap of caps() is not offered when the game has it but the display cannot show it ("This screen shows up to 60 fps."), else null */
        default String capWhy(int v) { return null; }
        /** The cap picked, -1 when it is none of caps(); the one the game runs at now (it differs while the picked one waits for a restart) */
        int cap();
        int capRunning();
        void cap(int v);
        /** The game's own cap choice removed (back to the default) */
        void forgetCap();
        /** The cap the game has without its own choice: one of caps(), or null when it is none of them (the game's own target, which only a start sets) */
        Integer defaultCap();
        /** Whether the second screen shows the companion or the game's page; null: no second display, or the game is not in front */
        Boolean screenShown();
        /** Hides or shows it (the four-finger tap); the state after it, null when nothing can be done */
        Boolean toggleScreen();
    }

    /** What Request.check refuses of these actions before anything is sent */
    static void check(Request r, String act) throws Request.Bad {
        switch (act) {
            case "fps_cap": {
                String v = r.need("value").toLowerCase(Locale.ROOT);
                if (!v.equals("own") && !v.equals("default") && !v.matches("[0-9]{1,3}"))
                    throw new Request.Bad("bad_args", "value is a frame rate such as 30, own or default, not " + v);
                break;
            }
            case "console_mode": r.need("value"); r.word("value", MODES); break;
            case "second_screen": r.need("value"); r.word("value", SCREEN); break;
            default: break;   // controls: nothing to give
        }
    }

    /** The request as the broadcast carries it: its arguments by name (ARGS) */
    public static Map<String, String> args(Request r) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : ARGS) if (r.str(k) != null) m.put(k, r.str(k));
        return m;
    }

    /** In the game's process, on its main thread: the action against the game's controls (null: the game's screen has none yet); the answer as JSON */
    public static String answer(Map<String, String> args, Live live) {
        Map<String, String> ex = new LinkedHashMap<>(args);
        ex.put("cmd", "game");
        Request r = new Request(ex, 0);
        Result res = new Result(r);
        try {
            r.check();
            run(r, live, res);
        } catch (Request.Bad e) {
            res.fail(e.code, e.getMessage());
        } catch (RuntimeException e) {
            res.fail("internal", "the game refused: " + e);
        }
        return Json.write(res.toMap());
    }

    static void run(Request r, Live live, Result res) throws Request.Bad {
        if (live == null) throw new Request.Bad("not_ready", "The game's screen is not ready yet. Try again in a moment.");
        String act = r.action(), want = r.titleId(false), id = live.titleId();
        if (want != null && id != null && !want.equalsIgnoreCase(id)) throw new Request.Bad("other_game", "The game that runs is " + id + ", not " + want + ".");
        res.put("title_id", id);
        switch (act) {
            case "fps_cap": fpsCap(r.need("value").toLowerCase(Locale.ROOT), live, res); break;
            case "console_mode": consoleMode(r.word("value", MODES), live, res); break;
            case "second_screen": secondScreen(r.word("value", SCREEN), live, res); break;
            case "controls": controls(live, res); break;
            default: throw new Request.Bad("bad_args", "do is " + String.join(", ", ACTIONS) + " here, not " + act);
        }
    }

    // ---- the frame rate cap ----

    /** A cap label as the word adb gives: "Game owned" is own, "30 fps" is 30 */
    static String capWord(String label) {
        if (label == null) return null;
        if (label.equalsIgnoreCase("Game owned")) return "own";
        return label.endsWith(" fps") ? label.substring(0, label.length() - 4) : label;
    }

    /** The offered caps as words, in menu order ("own, 120, 90, 60") */
    static String offeredText(Live live) {
        List<String> out = new ArrayList<>();
        for (int v : live.caps()) if (live.capOffered(v)) out.add(capWord(live.capLabel(v)));
        return String.join(", ", out);
    }

    static List<Map<String, Object>> capList(Live live, boolean onlyOffered) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int v : live.caps()) {
            boolean offered = live.capOffered(v);
            if (onlyOffered && !offered) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", capWord(live.capLabel(v)));
            m.put("label", live.capLabel(v));
            if (!onlyOffered) m.put("offered", offered);
            out.add(m);
        }
        return out;
    }

    /** The menu value a word names (own or a rate), offered or not; null when the menu has none */
    static Integer capOf(String word, Live live) {
        for (int v : live.caps()) if (word.equals(capWord(live.capLabel(v)))) return v;
        return null;
    }

    private static void fpsCap(String value, Live live, Result res) throws Request.Bad {
        if (value.equals("default")) {
            live.forgetCap();
            Integer d = live.defaultCap();
            boolean apply = d != null && live.capOffered(d);
            if (apply) { live.cap(d); live.forgetCap(); }   // the stats page's call, then the game's own choice removed again: the default stays the default
            capAnswer(live, res, apply, "The frame rate cap is back to the default");
            res.put("stored", "default");
            return;
        }
        Integer v = capOf(value, live);
        if (v == null || !live.capOffered(v)) {
            res.put("offered", capList(live, true));
            String why = v == null ? null : live.capWhy(v);   // the display's limit: the game has the cap, this screen cannot show it
            String what = value.equals("own") ? "Game owned" : value + " fps";
            throw new Request.Bad("not_offered", (why != null ? what + " is not offered here. " + why : "This game does not offer " + what + ".") + " It offers " + offeredText(live) + ".");
        }
        live.cap(v);
        capAnswer(live, res, true, "The frame rate cap is " + live.capLabel(v));
    }

    /** The cap as it stands after a change: now when the game runs at the picked cap (the stats page's restart rule), else at the next start */
    private static void capAnswer(Live live, Result res, boolean applied, String what) {
        int picked = live.cap(), run = live.capRunning();
        boolean now = applied && picked == run;
        String pl = picked == -1 ? null : live.capLabel(picked), rl = live.capLabel(run);
        res.ok(now ? what + (what.endsWith("default") ? ", " + pl + ", now." : " now.")
                   : what + ". It applies at the next start." + (rl == null ? "" : " Until then the game runs at " + rl + "."));
        res.put("cap", capWord(pl)).put("cap_label", pl).put("running", capWord(rl)).put("running_label", rl).put("effective", now ? "now" : "next_start");
    }

    // ---- the console mode ----

    private static void consoleMode(String value, Live live, Result res) throws Request.Bad {
        if (live.consoleMode() < 0) throw new Request.Bad("not_for_this_game", "This game's Resolution sets the console mode.");
        boolean now = live.consoleMode(MODES.indexOf(value));
        res.ok(now ? "The console mode is " + value + " now." : "The console mode is set to " + value + ". It applies at the next start.")
                .put("console_mode", value).put("effective", now ? "now" : "next_start");
    }

    static String modeWord(int m) { return m == 0 ? "docked" : m == 1 ? "handheld" : "fixed"; }

    // ---- the second screen ----

    private static void secondScreen(String value, Live live, Result res) throws Request.Bad {
        Boolean was = live.screenShown();
        if (was == null) throw new Request.Bad("no_second_screen", "The second screen cannot be changed now: there is no second display, or the game is not in front.");
        boolean change = value.equals("toggle") || value.equals("on") != was;
        Boolean is = change ? live.toggleScreen() : was;
        if (is == null) throw new Request.Bad("no_second_screen", "The second screen cannot be changed now: there is no second display, or the game is not in front.");
        res.ok(is ? "The second screen shows the game's page or companion." : "The second screen is hidden. Android's own screen is there until it is shown again.")
                .put("second_screen", is ? "on" : "off").put("changed", change);
    }

    // ---- everything at once ----

    private static void controls(Live live, Result res) {
        int mode = live.consoleMode(), picked = live.cap(), run = live.capRunning();
        String pl = picked == -1 ? null : live.capLabel(picked), rl = live.capLabel(run);
        Boolean shown = live.screenShown();
        res.ok("Console mode " + modeWord(mode) + ". Frame rate cap " + (pl == null ? "not one of the menu's" : pl) + (rl != null && !rl.equals(pl) ? ", running at " + rl : "")
                + ". Second screen " + (shown == null ? "not there" : shown ? "shown" : "hidden") + ".");
        res.put("console_mode", modeWord(mode)).put("cap", capWord(pl)).put("cap_label", pl).put("running", capWord(rl)).put("running_label", rl)
                .put("caps", capList(live, false)).put("second_screen", shown == null ? null : shown ? "on" : "off");
    }

    // ---- back in the launcher ----

    /** The game's answer (null: none came) into the command's result */
    @SuppressWarnings("unchecked")
    static void take(String answer, Result res) {
        if (answer == null) { res.fail("no_answer", "The game did not answer. Its screen may still be starting, or it is stopping."); return; }
        Map<String, Object> m;
        try {
            m = (Map<String, Object>) Json.read(answer);
        } catch (RuntimeException e) {
            res.fail("internal", "the game's answer does not read: " + e.getMessage());
            return;
        }
        Object data = m.get("data");
        if (data instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) data).entrySet()) res.put(e.getKey(), e.getValue());
        String msg = m.get("message") == null ? null : String.valueOf(m.get("message"));
        if (Boolean.TRUE.equals(m.get("ok"))) res.ok(msg);
        else res.fail(m.get("error") == null ? "internal" : String.valueOf(m.get("error")), msg);
    }
}
