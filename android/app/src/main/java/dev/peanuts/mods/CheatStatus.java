package dev.peanuts.mods;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The host's cheat status for the in-game Mods tab (ModsTab), and what a row shows from it. The text is Part B's Native.cheatStatus (read through
 * CheatsBridge.status): a line per entry of the loaded file, labels too, "key \t state \t flags \t reason". state: on, off, master, label, refused (turned
 * off while the game ran), unsupported (never runs). flags: a comma list (code, hold, wait; a flag this reader does not know is ignored). reason: "" or
 * "token:detail" (rewritten, nolist, region, unsupported, other; access or wait on a running cheat; an unknown token reads as other). No Android here, so
 * CheatLoaderJvmTest reads it.
 */
public final class CheatStatus {
    private CheatStatus() {}

    /** One entry of the status */
    public static final class Entry {
        public final String key, state, flags, reason;

        Entry(String key, String state, String flags, String reason) {
            this.key = key;
            this.state = state;
            this.flags = flags;
            this.reason = reason;
        }

        public boolean on() { return state.equals("on"); }

        /** Turned off by the host or never run by it: the row has no switch */
        public boolean bad() { return CheatStatus.bad(state); }

        /** A state a switch shows (refused is off) */
        public boolean switchState() { return state.equals("on") || state.equals("off") || state.equals("refused"); }

        public boolean flag(String f) {
            for (String x : flags.split(",")) if (x.trim().equals(f)) return true;
            return false;
        }
    }

    public static boolean bad(String state) { return state.equals("refused") || state.equals("unsupported"); }

    /** The status text by key in lower case (the host's keys compare without case); null when there is none (no file loaded, or a host without the call) */
    public static Map<String, Entry> parse(String status) {
        if (status == null) return null;
        Map<String, Entry> out = new HashMap<>();
        for (String line : status.split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 2 || f[0].isEmpty()) continue;
            out.put(f[0].toLowerCase(Locale.ROOT), new Entry(f[0], f[1], f.length > 2 ? f[2] : "", f.length > 3 ? f[3] : ""));
        }
        return out;
    }

    /** What a row's chip is */
    public enum Look { NOT_POSSIBLE, NEXT_START, HOLD, RUNNING, KIND }

    /** bad: the host refused it or never runs it (or the merge gave it no key); inHost: this run's host has it (else it comes at the next start) */
    public static Look look(CheatFile.Cheat c, boolean bad, boolean inHost, boolean on) {
        if (bad) return Look.NOT_POSSIBLE;
        if (!inHost) return Look.NEXT_START;
        if (c.holdLabel() != null) return Look.HOLD;
        return on ? Look.RUNNING : Look.KIND;
    }

    public static String chip(Look l, CheatFile.Cheat c) {
        switch (l) {
            case NOT_POSSIBLE: return "Not possible here";
            case NEXT_START: return "Next start";
            case HOLD: return c.holdLabel();
            case RUNNING: return "Running";
            default: return c.kind.label;
        }
    }

    /** What a row says while it has the focus (and its details): why it is not possible, else what it changes, and when it waits */
    public static String line(CheatFile.Cheat c, boolean bad, boolean inHost, String reason) { return line(c, bad, inHost, reason, c.value()); }

    /** The same, a Value cheat writing number (the one set in the tab) */
    public static String line(CheatFile.Cheat c, boolean bad, boolean inHost, String reason, long number) {
        if (bad) return why(reason, c);
        String d = CheatFile.describe(c, number);
        if (!inHost) return d + " It is in the game from the next start.";
        if (reason.startsWith("wait:")) return d + " Its change waits for a moment when the game can be stopped.";
        if (reason.startsWith("access:")) return d + " It found nothing to change yet: the game has not made that part.";
        return d;
    }

    /** A refusal in the player's words (the host's detail goes to the log, not here) */
    public static String why(String reason, CheatFile.Cheat c) {
        String token = reason == null ? "" : reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
        switch (token) {
            case "rewritten": return "Peanuts rewrote this place of the game's code while building it, so this cheat cannot change it.";
            case "nolist": return "This build of the game does not list the places Peanuts rewrote. Build the game again to use code cheats.";
            case "region": return "It writes a part of the game's memory this host does not give it.";
            case "unsupported": return "It uses a part of the cheat format Peanuts does not run" + (c.why != null ? " (" + c.why + ")" : "") + ".";
            default: return "The game stopped this cheat. The others go on.";
        }
    }
}
