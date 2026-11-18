package dev.nxnative.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** AdvancedTable on the JVM, driven by dm/tools/tests/test_android_advanced.py: args are docs/advanced_settings.tsv and docs/env_switches.tsv; prints "ok" or the failures, one a line */
public final class AdvancedTableJvmTest {
    static int bad;
    static void check(boolean c, String what) { if (!c) { bad++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) throws IOException {
        List<AdvancedTable.Row> rows = AdvancedTable.parse(new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.UTF_8));
        java.util.Map<String, String> meanings = AdvancedTable.meanings(new String(Files.readAllBytes(Paths.get(a[1])), StandardCharsets.UTF_8));
        check(rows.size() == meanings.size(), "one row for each env_switches row: " + rows.size() + " / " + meanings.size());
        for (AdvancedTable.Row r : rows) check(meanings.containsKey(r.name), r.name + " has a meaning");

        AdvancedTable.Row q = AdvancedTable.find(rows, "DM_GFX_QUALITY");
        check(q != null && q.tier.equals("friendly") && q.editable(), "DM_GFX_QUALITY is a friendly, editable row");
        check(q.accepts("quality") && q.accepts("performance") && !q.accepts("high") && !q.accepts(""), "choice values");
        check(q.optionLabel("quality").equals("Quality"), "option label");
        check(!q.forGame(null) && q.forGame("04007EF01F8AE000") && !q.forGame("0400000000000000"), "a game-only row for its games: " + q.games);

        AdvancedTable.Row n = AdvancedTable.find(rows, "DM_VK_IMG_LIMIT_MB");
        check(n != null && n.range()[0] == 512 && n.range()[1] == 8192 && n.unit().equals("MiB"), "number range and unit");
        check(n.accepts("1024") && !n.accepts("100") && !n.accepts("9000") && !n.accepts("x"), "number values");
        check(n.forGame(null) && n.forGame("04007EF01F8AE000"), "a both-scope row is in the global list and a game's");

        AdvancedTable.Row o = AdvancedTable.find(rows, "DM_MEM_FILEBACK");
        check(o != null && o.owned() && !o.editable(), "an owned row is not editable");

        AdvancedTable.Row d = AdvancedTable.find(rows, "DM_VK_TEX_STATS");
        check(d != null && d.developer() && d.shown() && d.accepts("all"), "a developer row takes text");
        check(!q.accepts("a=b") && d.accepts("a=b"), "= only in text rows");

        check(AdvancedTable.visible(rows, null, false, "").stream().noneMatch(AdvancedTable.Row::developer), "no developer rows without the toggle");
        check(AdvancedTable.visible(rows, null, true, "").stream().anyMatch(AdvancedTable.Row::developer), "developer rows with it");
        check(AdvancedTable.visible(rows, null, false, "DM_VK_TEX_STATS").size() == 1, "an exact name finds a developer row");
        check(AdvancedTable.visible(rows, null, false, "memory kills").isEmpty() || !AdvancedTable.visible(rows, null, false, "memory").isEmpty(), "search words");
        check(AdvancedTable.visible(rows, null, true, "dm_vk").size() == AdvancedTable.SEARCH_MAX, "a search is capped at " + AdvancedTable.SEARCH_MAX);
        check(AdvancedTable.visible(rows, null, false, "").stream().noneMatch(r -> r.tier.equals("internal")), "internal rows never show");

        check(AdvancedTable.redact("DM_ROMFS", "/data/x").equals("<path>") && AdvancedTable.redact("DM_TEMP_DIR", "x").equals("<path>") && AdvancedTable.redact("DM_A", "3").equals("3"), "redact");
        try { AdvancedTable.parse("a\tb\n"); check(false, "a header without name and tier is refused"); } catch (IllegalArgumentException e) { /* expected */ }
        System.out.println(bad == 0 ? "ok" : bad + " failures");
        if (bad != 0) System.exit(1);
    }
}
