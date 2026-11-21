package dev.peanuts.launch;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The play-time ledger's file format and arithmetic, with no Android class (peanuts/tests/test_play_time.py drives it on the JVM). One line per title:
 * {@code <title ID>\t<milliseconds played>\t<last played, epoch ms>\t<the last session's milliseconds>} (the fourth column is optional: a line written before it
 * reads 0); a line that does not parse is skipped, so a damaged file loses only that line.
 */
public final class PlayLedger {
    static final String HEADER = "# peanuts play time v1";

    /** One title's total, when it was last played (epoch ms, 0: never) and how long its last session ran (0: not known) */
    public static final class Entry {
        public final long ms, last, lastRun;
        Entry(long ms, long last, long lastRun) { this.ms = ms; this.last = last; this.lastRun = lastRun; }
    }

    private PlayLedger() { }

    public static Map<String, Entry> parse(String text) {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            if (f.length < 3 || f[0].isEmpty()) continue;
            try {
                long ms = Long.parseLong(f[1].trim()), last = Long.parseLong(f[2].trim()), run = f.length > 3 ? Long.parseLong(f[3].trim()) : 0;
                if (ms >= 0) out.put(f[0].toUpperCase(Locale.ROOT), new Entry(ms, last, Math.max(0, run)));
            } catch (NumberFormatException e) { /* skipped */ }
        }
        return out;
    }

    public static String format(Map<String, Entry> m) {
        StringBuilder b = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, Entry> e : m.entrySet()) b.append(e.getKey()).append('\t').append(e.getValue().ms).append('\t').append(e.getValue().last).append('\t').append(e.getValue().lastRun).append('\n');
        return b.toString();
    }

    /** Adds {@code ms} to the title's total and stamps it as played at {@code now} */
    public static void add(Map<String, Entry> m, String titleId, long ms, long now) {
        if (titleId == null || titleId.isEmpty() || ms <= 0) return;
        String k = titleId.toUpperCase(Locale.ROOT);
        Entry old = m.get(k);
        m.put(k, new Entry((old == null ? 0 : old.ms) + ms, now, old == null ? 0 : old.lastRun));
    }

    /** A session of the title ended after {@code ms} of play: its Last run */
    public static void lastRun(Map<String, Entry> m, String titleId, long ms) {
        if (titleId == null || titleId.isEmpty() || ms <= 0) return;
        String k = titleId.toUpperCase(Locale.ROOT);
        Entry old = m.get(k);
        if (old != null) m.put(k, new Entry(old.ms, old.last, ms));
    }

    /** The short form for a stat tile: "<1m", "42m", "12h05m", "120h" */
    public static String compact(long ms) {
        long min = ms / 60_000;
        if (min < 1) return "<1m";
        if (min < 60) return min + "m";
        long h = min / 60, m = min % 60;
        return h >= 100 ? h + "h" : String.format(Locale.ROOT, "%dh%02dm", h, m);
    }

    /**
     * The time a tick may count since the previous one: what passed while the game ran, at most {@code maxStep} (a tick that came late, after the device
     * slept or the process was frozen, counts as one step, not the whole gap); nothing when the previous tick did not see the game running.
     */
    public static long step(long prevTick, long now, long maxStep) {
        if (prevTick <= 0 || now <= prevTick) return 0;
        return Math.min(now - prevTick, maxStep);
    }

    /** Every title's time together */
    public static long total(Map<String, Entry> m) {
        long t = 0;
        for (Entry e : m.values()) t += e.ms;
        return t;
    }

    /** "Less than a minute", "42 min", "3 h 5 min", "120 h" */
    public static String text(long ms) {
        long min = ms / 60_000;
        if (min < 1) return "Less than a minute";
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        return h >= 100 || m == 0 ? h + " h" : h + " h " + m + " min";
    }
}
