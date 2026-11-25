package dev.peanuts.ctl;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The settings the set command may change: a short list of the launcher's own rows that only choose how a game is drawn and paced. Each key's values are the
 * ones its row offers. gpu_driver only picks among the drivers already installed (by folder name, as the Settings and Game settings lists do); installing
 * one is not here. Not here on purpose: anything that names a file or folder to read, the game's extra environment, driver installs, mods (they put code or
 * host switches into the game). No Android class here: the JVM tests drive it.
 */
public final class Settings {
    private Settings() {}

    public static final List<String> KEYS = Arrays.asList("console_mode", "res_scale", "upscaler", "fps_cap", "overlay", "keep_screen_on",
            "gpu_driver", "fast_cores", "hold60", "bottom_screen_games", "display");
    static final List<String> GAMES_PAGES = Arrays.asList("android", "stats", "cover");   // Settings > Library > Games without a companion, in its order
    static final List<String> DISPLAYS = Arrays.asList("fit", "stretch", "fill");   // Game settings > Display, in its order (GameSettings.setDisplay)
    static final List<String> UPSCALERS = Arrays.asList("nearest", "bilinear", "fsr1", "native", "sgsr1");   // the order of the launcher's Upscaler list
    static final String[] SCALES = {"0.5", "0.75", "1", "1.25", "1.5", "2"};
    static final int[] QUARTERS = {2, 3, 4, 5, 6, 8};
    static final int[] GLOBAL_CAPS = {-1, 0, 60, 30, 20, 15};

    /** A checked change: the key, whether it is one game's, and the value to store (an Integer, a Boolean or a String; null: back to the default, one game's only) */
    public static final class Change {
        public final String key;
        public final boolean perGame;
        public final Object value;
        /** When it takes effect: now, or next_start (the game's next start) */
        public final String effective;
        Change(String key, boolean perGame, Object value, String effective) { this.key = key; this.perGame = perGame; this.value = value; this.effective = effective; }
    }

    /**
     * perGame: a title ID was given. caps: the frame rate caps this game's row enables (Prefs.offeredCaps), unused for the global value.
     * paced: the game's row is the frame limit list, where 0 stands for 60 fps and "own" (Game owned) is stored as -2.
     */
    public static Change parse(String key, String value, boolean perGame, int[] caps, boolean paced) throws Request.Bad {
        return parse(key, value, perGame, caps, paced, null);
    }

    /** drivers: the installed GPU drivers' folder names (GpuDriver.installed), read only for gpu_driver */
    public static Change parse(String key, String value, boolean perGame, int[] caps, boolean paced, List<String> drivers) throws Request.Bad {
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
            case "gpu_driver": {   // per game: its own pick (GpuDriver.choice), default = none; every game: GpuDriver.select, default = the phone's own
                if (dflt) return perGame ? new Change(k, true, null, "next_start") : new Change(k, false, "system", "next_start");
                if (v.equals("system")) return new Change(k, perGame, "system", "next_start");
                if (drivers != null) for (String d : drivers) if (d.equalsIgnoreCase(value.trim())) return new Change(k, perGame, d, "next_start");
                throw new Request.Bad("bad_value", k + " is system, an installed driver or default, not " + value
                        + (drivers == null || drivers.isEmpty() ? ". No driver is installed." : ". Installed: " + String.join(", ", drivers) + "."));
            }
            case "fast_cores": {   // per game: Prefs.pinThreads(c, id, on), default = the main screen's; every game: Prefs.pinThreadsDefault, default = off
                if (dflt) return perGame ? new Change(k, true, null, "next_start") : new Change(k, false, false, "next_start");
                Boolean b = onOff(v);
                if (b == null) throw bad(k, "on, off or default", v);
                return new Change(k, perGame, b, "next_start");
            }
            case "hold60": {   // one game's only (Prefs.hold60): its row is there for games that run whole steps a frame
                if (!perGame) throw new Request.Bad("bad_args", k + " is one game's setting: give a title_id");
                if (dflt) return new Change(k, true, null, "next_start");
                Boolean b = onOff(v);
                if (b == null) throw bad(k, "on, off or default", v);
                return new Change(k, true, b, "next_start");
            }
            case "bottom_screen_games": {   // every game's: PeanutsPrefs.bottomScreenInGames and bottomScreenGamesPage
                if (perGame) throw new Request.Bad("bad_args", k + " is one setting for every game: give no title_id");
                if (GAMES_PAGES.contains(v)) return new Change(k, false, v, "next_start");
                throw bad(k, String.join(", ", GAMES_PAGES), v);
            }
            case "display": {   // one game's only (GameSettings.setDisplay): 0 Fit, 1 Stretch, 2 Fill; default is Fit
                if (!perGame) throw new Request.Bad("bad_args", k + " is one game's setting: give a title_id");
                int i = dflt ? 0 : DISPLAYS.indexOf(v);
                if (i < 0) throw bad(k, String.join(", ", DISPLAYS) + " or default", v);
                return new Change(k, true, i, "next_start");
            }
            default: throw new Request.Bad("unknown_key", key + " is not a setting this command changes (" + String.join(", ", KEYS) + ")");
        }
    }

    private static Boolean onOff(String v) {
        if (v.equals("on") || v.equals("true") || v.equals("1")) return true;
        if (v.equals("off") || v.equals("false") || v.equals("0")) return false;
        return null;
    }

    private static Request.Bad bad(String key, String allowed, String got) { return new Request.Bad("bad_value", key + " is " + allowed + ", not " + got); }
}
