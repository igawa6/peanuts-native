package dev.peanuts.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the second screen's stats page (StatsView) shows, and how it guards the AMOLED panel: pure Java, kept in the app's preferences as strings
 * (StatsView reads and writes them, PeanutsPrefs' file). The gear on the page edits it.
 * <ul>
 * <li>Items: the header's driver and state pills, ON PACE, the graph and the eight tiles. The console mode pill and the frame rate always show.</li>
 * <li>Presets: SIMPLE (a large frame rate and the four tiles a player looks at: SoC, battery, RAM, the run's clock), NORMAL (and the pace, the graph and the frame
 *     times), FULL (everything). Changing an item by hand makes it CUSTOM.</li>
 * <li>Burn-in guard: a black page, the pixel shifter, dimmer fixed labels, a dim after 10 minutes without a touch, black after 30. All on by default.</li>
 * </ul>
 */
public final class StatsConfig {
    public static final String PILLS = "pills", PACE = "pace", GRAPH = "graph";
    public static final String FRAME = "frame", LOW = "low", GPU = "gpu", CPU = "cpu", SOC = "soc", BAT = "bat", RAM = "ram", RUN = "run";
    /** Every item in its place on the page; the tiles in their order, four a row */
    public static final String[] ITEMS = {PILLS, PACE, GRAPH, FRAME, LOW, GPU, CPU, SOC, BAT, RAM, RUN};
    public static final String[] TILES = {FRAME, LOW, GPU, CPU, SOC, BAT, RAM, RUN};
    /** The sheet's names for the items, in ITEMS' order */
    public static final String[] NAMES = {"Header pills", "On pace", "Graph", "Frame", "1% low", "GPU", "CPU", "Temp", "Battery", "Game RAM", "This run"};

    public static final String SIMPLE = "simple", NORMAL = "normal", FULL = "full", CUSTOM = "custom";
    public static final String[] PRESETS = {SIMPLE, NORMAL, FULL};

    public static final String BLACK = "black", SHIFT = "shift", DIM_LABELS = "dim_labels", AUTO_DIM = "auto_dim", AUTO_OFF = "auto_off";
    public static final String[] GUARDS = {BLACK, SHIFT, DIM_LABELS, AUTO_DIM, AUTO_OFF};
    public static final String[] GUARD_NAMES = {"Black background", "Pixel shifter", "Dim fixed labels", "Dim after 10 min", "Black after 30 min"};

    private StatsConfig() {}

    /** A preset's items; null for CUSTOM or an unknown name */
    public static Set<String> preset(String name) {
        switch (name == null ? "" : name) {
            case SIMPLE: return of(SOC, BAT, RAM, RUN);
            case NORMAL: return of(PILLS, PACE, GRAPH, FRAME, LOW, SOC, BAT, RAM, RUN);
            case FULL: return of(ITEMS);
            default: return null;
        }
    }

    /** The preset these items are, or CUSTOM */
    public static String match(Set<String> shown) {
        for (String p : PRESETS) if (preset(p).equals(shown)) return p;
        return CUSTOM;
    }

    /** The stored form: the items in ITEMS' order, comma separated ("" for none) */
    public static String format(Set<String> shown) {
        StringBuilder b = new StringBuilder();
        for (String k : ITEMS) if (shown.contains(k)) b.append(b.length() > 0 ? "," : "").append(k);
        return b.toString();
    }

    /** The items of a stored form, unknown names left out; FULL's for null (never set) */
    public static Set<String> parse(String stored) {
        if (stored == null) return preset(FULL);
        Set<String> s = new LinkedHashSet<>();
        List<String> known = Arrays.asList(ITEMS);
        for (String k : stored.split(",")) if (known.contains(k.trim())) s.add(k.trim());
        return s;
    }

    /** The tiles to show, in their order: the shown ones the phone can read (gpu and cpu false where their files do not read) */
    public static List<String> tiles(Set<String> shown, boolean gpu, boolean cpu) {
        List<String> t = new ArrayList<>();
        for (String k : TILES) {
            if (!shown.contains(k)) continue;
            if (k.equals(GPU) && !gpu) continue;
            if (k.equals(CPU) && !cpu) continue;
            t.add(k);
        }
        return t;
    }

    /** Rows of at most four tiles, as even as they can be: 5 tiles are 3 + 2, 6 are 3 + 3, 7 are 4 + 3 */
    public static List<List<String>> rows(List<String> tiles) {
        List<List<String>> r = new ArrayList<>();
        int n = tiles.size();
        if (n == 0) return r;
        int rows = (n + 3) / 4, per = (n + rows - 1) / rows;
        for (int i = 0; i < n; i += per) r.add(new ArrayList<>(tiles.subList(i, Math.min(n, i + per))));
        return r;
    }

    /** Two tiles a row (Simple's 2 x 2) */
    public static List<List<String>> pairs(List<String> tiles) {
        List<List<String>> r = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i += 2) r.add(new ArrayList<>(tiles.subList(i, Math.min(tiles.size(), i + 2))));
        return r;
    }

    private static Set<String> of(String... k) { return new LinkedHashSet<>(Arrays.asList(k)); }
}
