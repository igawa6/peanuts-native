package dev.peanuts.ui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StatsConfigJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        check(StatsConfig.ITEMS.length == StatsConfig.NAMES.length, "a name for every item");
        check(StatsConfig.GUARDS.length == StatsConfig.GUARD_NAMES.length, "a name for every guard");
        for (String p : StatsConfig.PRESETS) check(StatsConfig.match(StatsConfig.preset(p)).equals(p), "a preset matches itself: " + p);
        check(StatsConfig.preset(StatsConfig.CUSTOM) == null && StatsConfig.preset(null) == null, "custom has no items");
        Set<String> s = new HashSet<>(StatsConfig.preset(StatsConfig.NORMAL));
        s.remove(StatsConfig.GRAPH);
        check(StatsConfig.match(s).equals(StatsConfig.CUSTOM), "a change by hand is custom");
        check(StatsConfig.parse(null).equals(StatsConfig.preset(StatsConfig.FULL)), "never set: full");
        check(StatsConfig.parse("").isEmpty(), "set to nothing: nothing");
        check(StatsConfig.parse("soc,bogus, ram").equals(new HashSet<>(Arrays.asList("soc", "ram"))), "unknown names left out, spaces trimmed");
        check(StatsConfig.format(new HashSet<>(Arrays.asList("run", "pills", "soc"))).equals("pills,soc,run"), "stored in the page's order");
        check(StatsConfig.parse(StatsConfig.format(StatsConfig.preset(StatsConfig.FULL))).equals(StatsConfig.preset(StatsConfig.FULL)), "round trip");

        List<String> all = StatsConfig.tiles(StatsConfig.preset(StatsConfig.FULL), true, true);
        check(all.equals(Arrays.asList(StatsConfig.TILES)), "full: every tile in order");
        check(!StatsConfig.tiles(StatsConfig.preset(StatsConfig.FULL), false, true).contains(StatsConfig.GPU), "no GPU tile where kgsl does not read");
        check(StatsConfig.rows(all).size() == 2 && StatsConfig.rows(all).get(0).size() == 4, "8 tiles: 4 + 4");
        List<List<String>> r6 = StatsConfig.rows(StatsConfig.tiles(StatsConfig.preset(StatsConfig.NORMAL), true, true));
        check(r6.size() == 2 && r6.get(0).size() == 3 && r6.get(1).size() == 3, "normal's 6 tiles: 3 + 3");
        List<List<String>> r4 = StatsConfig.rows(StatsConfig.tiles(StatsConfig.preset(StatsConfig.SIMPLE), true, true));
        check(r4.size() == 1 && r4.get(0).size() == 4, "simple's 4 tiles: one row");
        check(StatsConfig.rows(Arrays.asList("a", "b", "c", "d", "e")).get(0).size() == 3, "5 tiles: 3 + 2");
        check(StatsConfig.rows(Arrays.asList("a", "b", "c", "d", "e", "f", "g")).get(0).size() == 4, "7 tiles: 4 + 3");
        check(StatsConfig.rows(Arrays.asList()).isEmpty(), "no tiles: no row");
        List<List<String>> p4 = StatsConfig.pairs(Arrays.asList("a", "b", "c", "d"));
        check(p4.size() == 2 && p4.get(0).size() == 2 && p4.get(1).size() == 2, "pairs: 2 x 2");
        check(StatsConfig.pairs(Arrays.asList("a", "b", "c")).get(1).size() == 1, "pairs: an odd last tile alone");
        System.out.println("ok");
    }
}
