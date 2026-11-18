package dev.nxnative.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FixFinder on the JVM, driven by dm/tools/tests/test_android_fixfinder.py: args are docs/advanced_settings.tsv and dm/tools/advanced_fix.tsv; prints "ok" or the failures, one a line */
public final class FixFinderJvmTest {
    static int bad;
    static void check(boolean c, String what) { if (!c) { bad++; System.out.println("FAIL " + what); } }

    /** Prefs in a map: "name@id" (id "-" for every game) -> value, and the offered set */
    static final class Mem implements FixFinder.State {
        final Map<String, String> vals = new HashMap<>();
        final Set<String> offered = new HashSet<>();
        @Override public String own(String name, String titleId) { return vals.get(name + "@" + (titleId == null ? "-" : titleId)); }
        @Override public boolean offered(String name, String titleId) { return offered.contains(FixFinder.offeredKey(name, titleId)); }
    }

    public static void main(String[] a) throws IOException {
        List<AdvancedTable.Row> table = AdvancedTable.parse(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        List<FixFinder.Fix> real = FixFinder.parse(new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8));
        check(!real.isEmpty(), "the table has rows");
        String id = "0400000000000001";
        for (FixFinder.Fix f : real) {
            check(FixFinder.row(table, f, id) != null, f.rule + " -> " + f.name + "=" + f.value + ": a row the game page can set to that value");
            check(FixFinder.pick(real, table, f.rule, id, new Mem()) != null, f.rule + ": offered on a fresh game");
        }

        // A table of our own, so the rules do not depend on the real rows
        String text = "# comment\nrule_id\tadvanced_id\tvalue\tscope\tsentence\n"
                + "lmk-kill\tDM_VK_TEX_SOFT\t1\tgame\tFirst.\n"
                + "lmk-kill\tDM_VK_TEX_SOFT_LEVELS\t2\tgame\tSecond.\n"
                + "lmk-kill\tDM_MEM_FILEBACK\t1\tgame\tOwned: never.\n"
                + "exit:out_of_memory\tDM_VK_TEX_SOFT\t9\tgame\tA value the row does not take.\n"
                + "heat-throttle\tDM_VK_IMG_LIMIT_MB\t1024\tglobal\tGlobal.\n";
        List<FixFinder.Fix> fx = FixFinder.parse(text);
        check(fx.size() == 5, "parse: five rows, got " + fx.size());
        Mem s = new Mem();
        check(FixFinder.key("lmk-kill", "out_of_memory").equals("lmk-kill"), "key: the rule first");
        check(FixFinder.key(null, "out_of_memory").equals("exit:out_of_memory") && FixFinder.key("", "out_of_memory").equals("exit:out_of_memory"), "key: the kind without a rule");
        check(FixFinder.key(null, null) == null, "key: nothing");

        FixFinder.Fix f = FixFinder.pick(fx, table, "lmk-kill", id, s);
        check(f != null && f.name.equals("DM_VK_TEX_SOFT"), "the first row in order");
        s.offered.add(FixFinder.offeredKey("DM_VK_TEX_SOFT", id));
        f = FixFinder.pick(fx, table, "lmk-kill", id, s);
        check(f != null && f.name.equals("DM_VK_TEX_SOFT_LEVELS"), "once per setting and game: the next row");
        check(FixFinder.pick(fx, table, "lmk-kill", "0400000000000002", s).name.equals("DM_VK_TEX_SOFT"), "offered on one game, not on another");
        s.vals.put("DM_VK_TEX_SOFT_LEVELS@" + id, "1");
        check(FixFinder.pick(fx, table, "lmk-kill", id, s) == null, "a row set for the game is not Auto; an owned row is never offered");
        s.vals.clear();
        s.offered.clear();
        s.vals.put("DM_VK_TEX_SOFT@-", "0");
        f = FixFinder.pick(fx, table, "lmk-kill", id, s);
        check(f != null && f.name.equals("DM_VK_TEX_SOFT_LEVELS"), "a row set for every game is not Auto either");
        check(FixFinder.pick(fx, table, "exit:out_of_memory", id, new Mem()) == null, "a value the row does not accept is never offered");
        check(FixFinder.pick(fx, table, "gpu-hang", id, new Mem()) == null, "no row for the rule");
        check(FixFinder.pick(fx, table, "lmk-kill", null, new Mem()) == null, "no game, no fix");
        FixFinder.Fix g = FixFinder.pick(fx, table, "heat-throttle", id, new Mem());
        check(g != null && g.global() && FixFinder.target(g, id) == null, "a global fix writes for every game");
        check(FixFinder.target(fx.get(0), id).equals(id), "a game fix writes for the game");

        AdvancedTable.Row r = AdvancedTable.find(table, "DM_VK_TEX_SOFT");
        FixFinder.Fix f0 = fx.get(0);
        check(FixFinder.button(r).equals("Try: Soft textures"), "button: " + FixFinder.button(r));
        check(FixFinder.offerText(r, f0).equals("A setting may help: Soft textures On. First."), "offer: " + FixFinder.offerText(r, f0));
        check(FixFinder.doneText(r, f0, "Test Game").equals("Soft textures is On for Test Game. Applies at the next start."), "done: " + FixFinder.doneText(r, f0, "Test Game"));
        check(FixFinder.doneText(r, f0, null).endsWith("for this game. Applies at the next start."), "done without a name");
        check(FixFinder.doneText(AdvancedTable.find(table, "DM_VK_IMG_LIMIT_MB"), g, "x").equals("Texture cache limit is 1024 MiB for every game. Applies at the next start."), "done, global");
        for (FixFinder.Fix x : real) {
            AdvancedTable.Row xr = AdvancedTable.find(table, x.name);
            String all = FixFinder.button(xr) + FixFinder.offerText(xr, x) + FixFinder.doneText(xr, x, "Game");
            check(all.chars().allMatch(c -> c < 128), x.name + ": ASCII texts");
        }
        System.out.println(bad == 0 ? "ok" : bad + " failed");
    }
}
