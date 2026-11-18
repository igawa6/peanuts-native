package dev.peanuts.ctl;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The settings the set command may change: a short list of the launcher's own rows that only choose how a game is drawn and paced. Each key's values are the
 * ones its row offers. Not here on purpose: anything that names a file or folder, the game's extra environment, GPU drivers, mods (they put code or host
 * switches into the game). No Android class here: the JVM tests drive it.
 */
public final class Settings {
    private Settings() {}

    public static final List<String> KEYS = Arrays.asList("console_mode", "res_scale", "upscaler", "fps_cap", "overlay", "keep_screen_on");
    static final List<String> UPSCALERS = Arrays.asList("nearest", "bilinear", "fsr1", "native", "sgsr1");   // the order of the launcher's Upscaler list
    static final String[] SCALES = {"0.5", "0.75", "1", "1.25", "1.5", "2"};
    static final int[] QUARTERS = {2, 3, 4, 5, 6, 8};
    static final int[] GLOBAL_CAPS = {-1, 0, 60, 30, 20, 15};

    /** A checked change: the key, whether it is one game's, and the value to store (an Integer or a Boolean; null: back to the default, one game's only) */
    public static final class Change {
        public final String key;
        public final boolean perGame;
        public final Object value;
        /** When it takes effect: now, or next_start (the game's next start) */
        public final String effective;
        Change(String key, boolean perGame, Object value, String effective) { this.key = key; this.perGame = perGame; this.value = value; this.effective = effective; }
    }

    /**
     * perGame: a title ID was given. caps: the frame rate caps this game's row offers (the launcher's own list for it), unused for the global value.
     * paced: the game's row is the frame limit list, where 0 stands for 60 fps and there is no "the game's own".
     */
    public static Change parse(String key, String value, boolean perGame, int[] caps, boolean paced) throws Request.Bad {
        String k = key.toLowerCase(Locale.ROOT), v = value.toLowerCase(Locale.ROOT);
        boolean dflt = v.equals("default");
        switch (k) {
            case "console_mode":
                if (dflt) return perGame ? new Change(k, true, null, "next_start") : new Change(k, false, -1, "next_start");   // every game: each game's own mode
                if (v.equals("docked")) return new Change(k, perGame, 0, "next_start");
                if (v.equals("handheld")) return new Change(k, perGame, 1, "next_start");
                throw bad(k, "docked, handheld or default", v);
            case "res_scale": {
                if (dflt && perGame) return new Change(k, true, null, "next_start");
                String s = dflt ? "1" : v.endsWith("x") ? v.substring(0, v.length() - 1) : v;
                for (int i = 0; i < SCALES.length; i++) if (SCALES[i].equals(s)) return new Change(k, perGame, QUARTERS[i], "next_start");
                throw bad(k, String.join(", ", SCALES) + " or default", v);
            }
            case "upscaler": {
                if (dflt && perGame) return new Change(k, true, null, "next_start");
                int i = UPSCALERS.indexOf(v);
                if (i >= 0) return new Change(k, perGame, i, "next_start");
                throw bad(k, String.join(", ", UPSCALERS) + (perGame ? " or default" : ""), v);
            }
            case "fps_cap": {
                if (dflt && perGame) return new Change(k, true, null, "next_start");
                boolean pacedGame = perGame && paced;
                int[] offered = perGame ? caps : GLOBAL_CAPS;
                Integer n = null;
                if (dflt) n = -1;
                else if (v.equals("own")) n = pacedGame ? -2 : 0;
                else try { n = Integer.parseInt(v); } catch (NumberFormatException e) { n = null; }
                if (n != null && pacedGame && n == 60) n = 0;   // the frame limit list stores 60 fps as 0
                else if (n != null && pacedGame && n == 0) n = null;
                if (n != null && !v.equals("-1")) for (int c : offered) if (c == n) return new Change(k, perGame, n, "next_start");
                StringBuilder all = new StringBuilder();
                for (int c : offered) if (c != -1) all.append(all.length() > 0 ? ", " : "").append(c == -2 ? "own" : c == 0 ? (pacedGame ? "60" : "own") : String.valueOf(c));
                throw bad(k, all + " or default", v);
            }
            case "overlay": case "keep_screen_on": {
                if (perGame) throw new Request.Bad("bad_args", k + " is one setting for every game: give no title_id");
                if (v.equals("on") || v.equals("true") || v.equals("1")) return new Change(k, false, true, "now");
                if (v.equals("off") || v.equals("false") || v.equals("0")) return new Change(k, false, false, "now");
                throw bad(k, "on or off", v);
            }
            default: throw new Request.Bad("unknown_key", key + " is not a setting this command changes (" + String.join(", ", KEYS) + ")");
        }
    }

    private static Request.Bad bad(String key, String allowed, String got) { return new Request.Bad("bad_value", key + " is " + allowed + ", not " + got); }
}
