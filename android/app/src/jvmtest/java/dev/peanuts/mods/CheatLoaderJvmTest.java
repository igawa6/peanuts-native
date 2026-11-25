package dev.peanuts.mods;   // PEANUTS: cheats for every game (0.1.8)

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The cheat loader of the game page (SPEC-mod-menu.md, Package B) on the JVM, driven by peanuts/tests/test_cheat_loader.py. args[0]: an empty folder; args[1]
 * (optional): a real cheat file to read as a smoke test (never part of the repository). Prints "ok" or throws. Covers the parse (groups, masters, credits,
 * restore entries, kinds and hold buttons), the import's layouts and its choice of build, the sources and the merged file.
 */
public final class CheatLoaderJvmTest {
    static final String TID = "0100AAAA0BBB0000", B = "0123456789ABCDEF", OTHER = "FEDCBA9876543210";
    static final Set<String> BUILDS = Set.of(B);

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static byte[] u(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** A made-up file in a cheat site's shape: credits, a title, a Restore Code entry, groups opened and closed by the same heading, a master, every kind */
    static final String SITE = String.join("\n",
            "[www.example-cheats.com]",
            "[credits: Example Team]",
            "",
            "[Example Game v1.0.0 TID=" + TID + " BID=" + B + "]",
            "",
            "[Restore Code (Use after unchecking any cheats below)]",
            "040A0000 00001000 B9004008",
            "040A0000 00002000 52800000",
            "",
            "{Setup}",
            "400F0000 00000000 00000000",
            "",
            "[Items]",
            "20000000",
            "",
            "[1. Lots of coins]",
            "040A0000 00001000 B9004009",
            "",
            "[2. No item cost]",
            "040A0000 00002000 D503201F",
            "040A0000 00002004 D503201F",
            "",
            "[3. Coins as a number]",
            "580F0000 00300000",
            "580F1000 00000010",
            "640F0000 00000000 0001869F",
            "",
            "[Items]",
            "20000001",
            "",
            "[Moving]",
            "20000000",
            "",
            "[4. Fast run (Hold ZL)]",
            "80000100",
            "040A0000 00003000 1E241000",
            "21000000",
            "040A0000 00003000 1E2E1000",
            "20000000",
            "",
            "[5. High jump (Hold B)]",
            "80000002",
            "580F0000 00300000",
            "580F1000 00000248",
            "780F0000 00000094",
            "940EF000 40000000",
            "A4EF0000",
            "20000000",
            "",
            "[6. Pause trick]",
            "FF000000",
            "",
            "[7. Two buttons]",
            "C4000000 00000000 00002020",
            "040A0000 00004000 52800020",
            "20000000",
            "",
            "[Moving]",
            "20000001",
            "",
            "[--SectionStart:Extras--]",
            "[8. Data in main]",
            "04000000 00500000 00000063",
            "[8. Data in main]",
            "04000000 00500004 00000064",
            "[--SectionEnd:Extras--]",
            "[Odd width]",
            "030A0000 00001000 00000001",
            "",
            "[This set is made by Example Team, enjoy!]",
            "");

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        parse();
        kinds();
        layouts(root);
        sources(new File(root, "sources-case"));
        merge(new File(root, "merge-case"));
        status();
        values(new File(root, "values-case"));
        if (a.length > 1 && !a[1].isEmpty()) real(new File(a[1]));
        System.out.println("ok");
    }

    static CheatFile.Cheat cheat(CheatFile.Parsed p, String name) {
        for (CheatFile.Cheat c : p.cheats) if (c.key.equals(name)) return c;
        throw new AssertionError("no cheat " + name + " in " + p.cheats);
    }

    // ---- The host's status for the in-game Mods tab (Part E: CheatStatus over Part B's Native.cheatStatus) ----

    static void status() {
        check(CheatStatus.parse(null) == null, "no status: null");
        String st = "1. Lots of coins\ton\tcode\t\n"
                + "Items\tlabel\t\t\n"
                + "4. Fast run (Hold ZL)\toff\thold\t\n"
                + "2. No item cost\trefused\tcode\trewritten:a word the rewriter changed or moved\n"
                + "6. Pause trick\tunsupported\t\tunsupported:opcode FF\n"
                + "3. Coins as a number\ton\tcode,wait,future\twait:the game's threads did not stop in time\r\n"
                + "a line without a tab\n"
                + "\n";
        Map<String, CheatStatus.Entry> m = CheatStatus.parse(st);
        check(m.size() == 6, "six entries, the label too, a line without a tab left out: " + m.keySet());
        CheatStatus.Entry coins = m.get("1. lots of coins");
        check(coins != null && coins.key.equals("1. Lots of coins") && coins.on() && coins.switchState() && !coins.bad() && coins.flag("code") && coins.reason.isEmpty(),
                "on, found by its key in lower case");
        check(m.get("items").state.equals("label") && !m.get("items").switchState(), "a label has no switch state");
        CheatStatus.Entry cost = m.get("2. no item cost");
        check(cost.bad() && cost.switchState() && !cost.on(), "refused: no switch, shown off");
        check(m.get("6. pause trick").bad() && !m.get("6. pause trick").switchState(), "unsupported: no switch");
        CheatStatus.Entry num = m.get("3. coins as a number");
        check(num.flag("wait") && num.flag("code") && !num.flag("hold") && !num.flag("futur") && num.reason.startsWith("wait:"), "flags as a list, an unknown one ignored, no \\r left");

        CheatFile.Parsed p = CheatFile.parse(SITE, 0x200000);
        CheatFile.Cheat c1 = cheat(p, "1. Lots of coins"), fast = cheat(p, "4. Fast run (Hold ZL)");
        check(CheatStatus.chip(CheatStatus.look(c1, false, true, true), c1).equals("Running"), "on: Running");
        check(CheatStatus.chip(CheatStatus.look(c1, false, true, false), c1).equals(c1.kind.label), "off: its kind");
        String hold = CheatStatus.chip(CheatStatus.look(fast, false, true, true), fast);
        check(hold.equals("Hold ZL"), "a hold cheat shows its buttons: " + hold);
        check(CheatStatus.look(c1, false, false, true) == CheatStatus.Look.NEXT_START, "not in this run's host: Next start");
        check(CheatStatus.look(fast, true, true, true) == CheatStatus.Look.NOT_POSSIBLE, "refused before anything else");
        check(CheatStatus.line(c1, true, true, cost.reason).startsWith("Peanuts rewrote"), "rewritten, in the player's words");
        check(CheatStatus.why("nolist:x", c1).contains("Build the game again") && CheatStatus.why("region:x", c1).contains("memory")
                && CheatStatus.why("odd:x", c1).equals("The game stopped this cheat. The others go on."), "the refusals; an unknown token reads as other");
        check(CheatStatus.line(c1, false, true, num.reason).endsWith("Its change waits for a moment when the game can be stopped."), "wait on a running cheat");
        check(CheatStatus.line(c1, false, false, "").endsWith("It is in the game from the next start."), "Next start in its line");
        check(CheatStatus.line(c1, false, true, "").equals(CheatFile.describe(c1)), "else what it changes");
    }

    // ---- A Value cheat's number, set in the in-game Mods tab (Part F: CheatValues) ----

    static void values(File folder) throws Exception {
        CheatFile.Parsed p = CheatFile.parse(SITE, 0x200000);
        CheatFile.Cheat num = cheat(p, "3. Coins as a number"), coins = cheat(p, "1. Lots of coins");
        check(CheatValues.plain(num) && !CheatValues.plain(coins), "a Value cheat has a number field, a code cheat keeps its words");
        check(!CheatValues.plain(CheatFile.parse("[F]\n04100000 F0000030 3FC00000\n", 0x200000).cheats.get(0)), "1.5 as a float keeps its words");
        check(CheatValues.plain(CheatFile.parse("[N]\n04100000 F0000030 0001869F\n", 0x200000).cheats.get(0)), "99999 is a plain number");
        List<String> l500 = CheatValues.lines(num, 500);
        check(l500.equals(List.of("580F0000 00300000", "580F1000 00000010", "640F0000 00000000 000001F4")), "500 in its word, the other words as written: " + l500);
        check(CheatValues.lines(num, -5).get(2).endsWith(" 00000000") && CheatValues.lines(num, 1L << 40).get(2).endsWith(" FFFFFFFF"), "kept inside its width");
        CheatFile.Cheat wide = CheatFile.parse("[W]\n680F0000 0000000000000005\n", 0x200000).cheats.get(0);
        check(wide.kind == CheatFile.Kind.VALUE && wide.valueWidth == 8 && wide.value() == 5, "an 8-byte number in one token of two words: " + wide + " " + wide.value());
        List<String> lw = CheatValues.lines(wide, 0x100000002L);
        check(lw.equals(List.of("680F0000 0000000100000002")), "both words of the token: " + lw);

        // While the game runs: that cheat's block of the merged file
        String merged = "[Other]\n04100000 F0000000 00000001\n[3. Coins as a number]\n580F0000 00300000\n580F1000 00000010\n640F0000 00000000 0001869F\n[Next]\n04100000 F0000004 00000002\n";
        String patched = CheatValues.patch(merged, "3. Coins as a number", num, 7);
        check(patched != null && patched.equals(merged.replace("640F0000 00000000 0001869F", "640F0000 00000000 00000007")), "only its number changes: " + patched);
        check(CheatValues.patch(merged, "Missing", num, 7) == null, "no such block: null");

        // Kept for the next start: the merge writes it while the cheat still has the original number there
        check(folder.mkdirs(), "the folder");
        CheatValues.keep(folder, "3. Coins as a number", num, 500);
        Map<String, CheatValues.Kept> kept = CheatValues.read(folder);
        CheatValues.Kept k = kept.get("3. coins as a number");
        check(kept.size() == 1 && k != null && k.number == 500 && k.original == 99999 && k.word == 6 && k.width == 4, "kept with its place and original");
        check(CheatValues.of(kept, "3. Coins as a number", num) == 500 && CheatValues.of(kept, "1. Lots of coins", coins) == coins.value(), "the number now");
        CheatSources.Merged m = CheatSources.merge(List.of(new CheatSources.Block("app", "app", true, CheatFile.parse(SITE, -1))), kept);
        check(m.text.contains("[3. Coins as a number]\n580F0000 00300000\n580F1000 00000010\n640F0000 00000000 000001F4\n"), "a start writes the kept number");
        check(CheatSources.merge(List.of(new CheatSources.Block("app", "app", true, CheatFile.parse(SITE, -1)))).text.contains("0001869F"), "without values: the file's own");

        // A number in main's data: the start reads it without main's code size (as code), the kept place still applies; another original does not
        String mainFile = "[M]\n040A0000 00500000 00000063\n";
        CheatFile.Cheat mainVal = CheatFile.parse(mainFile, 0x200000).cheats.get(0);
        check(mainVal.kind == CheatFile.Kind.VALUE && CheatFile.parse(mainFile, -1).cheats.get(0).kind == CheatFile.Kind.CODE, "Value with the code size, Code without it");
        File f2 = new File(folder, "main");
        check(f2.mkdirs(), "the second folder");
        CheatValues.keep(f2, "M", mainVal, 1000);
        String m2 = CheatSources.merge(List.of(new CheatSources.Block("app", "app", true, CheatFile.parse(mainFile, -1))), CheatValues.read(f2)).text;
        check(m2.contains("040A0000 00500000 000003E8"), "the start applies it without main's code size: " + m2);
        String m3 = CheatSources.merge(List.of(new CheatSources.Block("app", "app", true, CheatFile.parse("[M]\n040A0000 00500000 00000064\n", -1))), CheatValues.read(f2)).text;
        check(m3.contains("00000064") && !m3.contains("000003E8"), "a file with another number there keeps its own: " + m3);
        CheatValues.keep(f2, "M", mainVal, 0x63);
        check(CheatValues.read(f2).isEmpty(), "the file's own number again forgets it");
    }

    // ---- Reading a file ----

    static void parse() {
        CheatFile.Parsed p = CheatFile.parse(SITE, 0x200000);
        List<String> names = new ArrayList<>();
        for (CheatFile.Cheat c : p.cheats) names.add(c.key);
        check(names.equals(List.of("1. Lots of coins", "2. No item cost", "3. Coins as a number", "4. Fast run (Hold ZL)", "5. High jump (Hold B)", "6. Pause trick",
                "7. Two buttons", "8. Data in main", "8. Data in main #2", "Odd width")), "the cheats, in order, a repeated name keyed #2: " + names);
        check(p.groups.equals(List.of("Items", "Moving", "Extras")), "the groups: " + p.groups);
        check(cheat(p, "1. Lots of coins").group.equals("Items") && cheat(p, "3. Coins as a number").group.equals("Items"), "Items holds its cheats");
        check(cheat(p, "4. Fast run (Hold ZL)").group.equals("Moving") && cheat(p, "7. Two buttons").group.equals("Moving"), "Moving holds its cheats");
        check(cheat(p, "8. Data in main").group.equals("Extras"), "EdiZon's markers make a group");
        check(cheat(p, "Odd width").group.isEmpty(), "the heading again closes its group, and a marker's end too");
        check(p.masters.size() == 1 && p.masters.get(0).name.equals("Setup") && p.masters.get(0).master, "{Setup} is a master: " + p.masters);
        check(p.credits.equals(List.of("www.example-cheats.com", "credits: Example Team", "This set is made by Example Team, enjoy!")), "the credits: " + p.credits);
        check(CheatFile.credit(p).equals("www.example-cheats.com, Example Team, This set is made by Example Team, enjoy!"), "the credit line: " + CheatFile.credit(p));
        check(p.unsupported() == 2, "two are Not supported");
        check(p.titles.size() == 1 && p.titles.get(0).contains("TID="), "the title line: " + p.titles);
        check(p.restores.size() == 1 && p.restores.get(0).startsWith("Restore Code"), "Restore Code is hidden: " + p.restores);
        check(p.switchable() == 8, "8 switchable (Pause trick and Odd width are not): " + p.switchable());
        // an empty [Name] is a heading; a build line; a header with code on its line
        CheatFile.Parsed q = CheatFile.parse("build " + B.toLowerCase() + "\n[Group A]\n[X] 04000000 00000010 00000001\n[Group A]\n[Y]\n04000000 00000010 00000001\n", -1);
        check(B.equals(q.build), "the build line: " + q.build);
        check(q.groups.equals(List.of("Group A")) && q.cheats.get(0).group.equals("Group A") && q.cheats.get(1).group.isEmpty(), "an empty [Name] heads a group: " + q.cheats);
        check(q.cheats.get(0).lines.equals(List.of("04000000 00000010 00000001")), "code on the header's line");
        check(CheatFile.clean("--- Battle ---").equals("Battle") && CheatFile.clean("== Time: ==").equals("Time"), "a heading without its decoration");
        check(CheatFile.restore("Restore original code") && !CheatFile.restore("Restore HP"), "only a code restore is hidden");
        check(!CheatFile.parse("just text\nno code here\n", -1).any(), "a readme is no cheat file");
    }

    static void kinds() {
        CheatFile.Parsed p = CheatFile.parse(SITE, 0x200000);   // main's code is the first 2 MB: 0x1000..0x4000 are code, 0x500000 is data
        CheatFile.Cheat coins = cheat(p, "1. Lots of coins"), cost = cheat(p, "2. No item cost"), num = cheat(p, "3. Coins as a number"), run = cheat(p, "4. Fast run (Hold ZL)"),
                jump = cheat(p, "5. High jump (Hold B)"), pause = cheat(p, "6. Pause trick"), two = cheat(p, "7. Two buttons"), data = cheat(p, "8. Data in main"), odd = cheat(p, "Odd width");
        check(coins.kind == CheatFile.Kind.CODE && coins.codePlaces == 1 && coins.hold.isEmpty(), "a write into main's code: " + coins);
        check(cost.kind == CheatFile.Kind.CODE && cost.codePlaces == 2, "two code places: " + cost);
        check(num.kind == CheatFile.Kind.VALUE && num.value() == 99999 && num.valueWidth == 4 && num.pointers, "one number through pointers is a Value: " + num + " " + num.value());
        check(run.kind == CheatFile.Kind.CODE && run.hold.equals(List.of("ZL")) && "Hold ZL".equals(run.holdLabel()), "opcode 8 ZL: " + run);
        check(jump.kind == CheatFile.Kind.DATA && jump.hold.equals(List.of("B")) && jump.pointers, "arithmetic and a register store are Data, held on B: " + jump);
        check(pause.kind == CheatFile.Kind.UNSUPPORTED && "opcode FF0".equals(pause.why) && !pause.switchable(), "FF0 is not supported: " + pause.why);
        check(two.kind == CheatFile.Kind.CODE && two.hold.equals(List.of("R stick + Up")), "C4's 64-bit mask: " + two.hold);
        check(data.kind == CheatFile.Kind.VALUE && data.value() == 0x63, "a plain number into main's data, past its code: " + data);
        // the gap after the last instruction, on the code's last page, is code (a cheat site's code cave); the next page is data
        check(CheatFile.parse("[A]\n080A0000 03163FF8 1763D8B3 F9000102\n", 0x3163f70).cheats.get(0).kind == CheatFile.Kind.CODE, "a write after TEXT_END on its page is Code");
        check(CheatFile.parse("[A]\n040A0000 03164000 00000001\n", 0x3163f70).cheats.get(0).kind == CheatFile.Kind.VALUE, "the next page is data");
        check(odd.kind == CheatFile.Kind.UNSUPPORTED && "width".equals(odd.why), "a width of 3 bytes: " + odd.why);
        check(p.masters.get(0).kind == CheatFile.Kind.DATA, "a master that writes nothing is Data");
        // without the size of main's code a 4-byte store in main is taken as code, a 1-byte one as data
        check(CheatFile.parse("[A]\n04000000 00500000 00000001\n", -1).cheats.get(0).kind == CheatFile.Kind.CODE, "4 bytes into main, size unknown: Code");
        check(CheatFile.parse("[A]\n01000000 00500000 00000001\n", -1).cheats.get(0).kind == CheatFile.Kind.VALUE, "1 byte into main, size unknown: Value");
        check(CheatFile.parse("[A]\n04100000 00500000 00000001\n", 0x100).cheats.get(0).kind == CheatFile.Kind.VALUE, "the heap is data");
        check(CheatFile.parse("[A]\n08100000 00500000 00000001 00000002\n", -1).cheats.get(0).value() == 0x100000002L, "an 8-byte value, high word first");
        check(CheatFile.parse("[A]\n04100000 00500000 00000001\n04100000 00500004 00000002\n", 0).cheats.get(0).kind == CheatFile.Kind.DATA, "two numbers: Data, no number field");
        check(CheatFile.parse("[A]\n04000000 00500000\n", 0).cheats.get(0).kind == CheatFile.Kind.UNSUPPORTED, "a store cut short is truncated");
        check(CheatFile.parse("[A]\n80000003\n80000100\n04100000 00000000 00000001\n20000000\n20000000\n", 0).cheats.get(0).holdLabel().equals("Hold A + B or ZL"), "two conditions");
        check(CheatFile.buttons(0x2000 | 0x400 | 0x800).equals("Plus + Minus + Up"), "names in the button order");
        // the words the bottom screen says
        check(CheatFile.describe(coins).equals("Changes 1 place in the game's code. Off puts the original code back."), CheatFile.describe(coins));
        check(CheatFile.describe(run).startsWith("While you hold ZL, it changes 1 place in the game's code."), CheatFile.describe(run));
        check(CheatFile.describe(num).contains("99999") && CheatFile.describe(num).contains("No code is changed"), CheatFile.describe(num));
        check(CheatFile.describe(jump).startsWith("While you hold B, it changes the game's data through a chain of pointers."), CheatFile.describe(jump));
        check(CheatFile.describe(pause).contains("stays off"), CheatFile.describe(pause));
        for (CheatFile.Cheat c : p.cheats) { String d = CheatFile.describe(c); check(!d.contains("—") && !d.contains("–") && !d.contains("opcode 8"), "UI rules: " + d); }
    }

    // ---- The import ----

    static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(o)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                if (!e.getKey().endsWith("/")) z.write(u(e.getValue()));
                z.closeEntry();
            }
        }
        return o.toByteArray();
    }

    static CheatImport.Found fromZip(Map<String, String> files, String name) throws Exception {
        List<String> paths = new ArrayList<>();
        List<CheatImport.In> in = CheatImport.fromZip(new ByteArrayInputStream(zip(files)), paths);
        return CheatImport.read(in, paths, TID, BUILDS, name);
    }

    static final String SMALL = "[Coins]\n04000000 00001000 00000063\n";

    static void layouts(File root) throws Exception {
        String[][] cases = {
                {"atmosphere/contents/" + TID + "/cheats/" + B + ".txt", "Atmosphère"},
                {"atmosphere/titles/" + TID.toLowerCase() + "/cheats/" + B.toLowerCase() + ".txt", "titles/, lower case"},
                {"contents/" + TID + "/cheats/" + B + "AAAAAAAAAAAAAAAA.txt", "contents/ alone, a 32-digit build ID"},
                {"load/" + TID + "/My Set/cheats/" + B + ".txt", "Eden / Yuzu"},
                {"mods/contents/" + TID + "/Ryu Set/cheats/" + B + ".txt", "Ryujinx"},
                {"some/folder/" + B + ".txt", "a bare build file"},
        };
        for (String[] c : cases) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("readme.txt", "Put this on your SD card.\n");
            f.put(c[0], SMALL);
            CheatImport.Found r = fromZip(f, "pack.zip");
            check(r.forGame.size() == 1 && r.forGame.get(0).bid.equals(B) && r.otherVersion.isEmpty() && !r.mod, c[1] + ": " + r.forGame + " " + r.otherVersion);
            check(r.cheats() == 1, c[1] + ": one cheat");
        }
        check(fromZip(Map.of("load/" + TID + "/My Set/cheats/" + B + ".txt", SMALL), "pack.zip").sources().containsKey("My Set"), "an Eden set's own name names its source");
        check(fromZip(Map.of("mods/contents/" + TID + "/Ryu Set/cheats/" + B + ".txt", SMALL), "pack.zip").sources().containsKey("Ryu Set"), "a Ryujinx set's own name");
        check(fromZip(Map.of("atmosphere/contents/" + TID + "/cheats/" + B + ".txt", SMALL), "pack.zip").sources().containsKey("pack"), "Atmosphère: the zip's name");
        // a file that starts with its build, in any name
        CheatImport.Found line = CheatImport.read(List.of(new CheatImport.In("my cheats.txt", u("build " + B + "\n" + SMALL))), List.of("my cheats.txt"), TID, BUILDS, "my cheats.txt");
        check(line.forGame.size() == 1 && line.name.equals("my cheats"), "a build line: " + line.forGame + " " + line.name);
        CheatImport.Found none = CheatImport.read(List.of(new CheatImport.In("cheats.txt", u(SMALL))), List.of("cheats.txt"), TID, BUILDS, "cheats.txt");
        check(none.forGame.isEmpty() && none.noBuild.equals(List.of("cheats.txt")), "a file that names no build: " + none.noBuild);
        // the game's build is used, the others are for another version
        Map<String, String> both = new LinkedHashMap<>();
        both.put("atmosphere/contents/" + TID + "/cheats/" + OTHER + ".txt", SMALL + "[More]\n04000000 00001004 00000001\n");
        both.put("atmosphere/contents/" + TID + "/cheats/" + B + ".txt", SITE);
        both.put("atmosphere/contents/0100BBBB0CCC0000/cheats/" + B + ".txt", SMALL);
        CheatImport.Found r = fromZip(both, "pack.zip");
        check(r.forGame.size() == 1 && r.forGame.get(0).bid.equals(B) && r.cheats() == 8 && r.unsupported() == 2, "the game's build, its switchable cheats: " + r.forGame);
        check(r.otherVersion.size() == 1 && r.otherVersion.get(0).bid.equals(OTHER) && r.otherCheats() == 2, "another version: " + r.otherVersion);
        check(r.otherGame.size() == 1, "another game's title ID is left out: " + r.otherGame);
        check(r.restores() && r.groups() == 3, "Restore Code and the groups, for the dialog");
        check(r.sources().get("pack").keySet().equals(Set.of(B, OTHER)), "both versions are kept in the source: " + r.sources());
        CheatImport.Found wrong = fromZip(Map.of("atmosphere/contents/" + TID + "/cheats/" + OTHER + ".txt", SMALL), "x.zip");
        check(wrong.forGame.isEmpty() && wrong.otherVersion.size() == 1, "only another version");
        check(fromZip(Map.of("atmosphere/contents/0100AAAA0BBB0800/cheats/" + B + ".txt", SMALL), "x.zip").forGame.size() == 1, "an update's program ID is the same game");
        // a mod: romfs or exefs folders
        Map<String, String> mod = new LinkedHashMap<>();
        mod.put("atmosphere/contents/" + TID + "/romfs/", "");
        mod.put("atmosphere/contents/" + TID + "/romfs/data/a.bin", "x");
        mod.put("atmosphere/contents/" + TID + "/cheats/" + B + ".txt", SMALL);
        check(fromZip(mod, "mod.zip").mod, "romfs: the mod import's");
        check(fromZip(Map.of("My Mod/exefs/main.ips", "x"), "mod.zip").mod, "exefs: the mod import's");
        // the same file twice in one zip is one file; names
        Map<String, String> twice = new LinkedHashMap<>();
        twice.put("atmosphere/contents/" + TID + "/cheats/" + B + ".txt", SMALL);
        twice.put("backup/" + B + ".txt", SMALL.replace("\n", "\r\n"));
        check(fromZip(twice, "a.zip").forGame.size() == 1, "one file in two places is added once");
        check(fromZip(Map.of(B + ".txt", SITE), B + ".zip").name.equals("example-cheats"), "a build ID as the name: the credit's site");
        check(fromZip(Map.of(B + ".txt", SMALL), B + ".txt").name.equals("Cheats"), "nothing better: Cheats");
        check(CheatImport.layout(TID + ".txt")[1] != null && CheatImport.read(List.of(new CheatImport.In(TID + ".txt", u(SMALL))), List.of(TID + ".txt"), TID, Set.of(TID), "t").forGame.isEmpty(),
                "a file named by the title ID is not a build's");
        // a folder
        File dir = new File(root, "picked/Fake Pack");
        File cf = new File(dir, "atmosphere/contents/" + TID + "/cheats/" + B + ".txt");
        check(cf.getParentFile().mkdirs(), "mkdirs");
        Files.write(cf.toPath(), u(SITE));
        File rom = new File(dir, "atmosphere/contents/" + TID + "/romfs/x");
        check(rom.mkdirs(), "mkdirs romfs");
        List<String> paths = new ArrayList<>();
        List<CheatImport.In> in = CheatImport.fromFolder(dir, paths);
        CheatImport.Found fr = CheatImport.read(in, paths, TID, BUILDS, dir.getName());
        check(fr.forGame.size() == 1 && fr.mod && fr.name.equals("Fake Pack"), "a folder: " + fr.forGame + " " + fr.mod + " " + fr.name);
        check(paths.contains("Fake Pack/atmosphere/contents/" + TID + "/romfs/") && !paths.contains("Fake Pack/atmosphere/contents/" + TID + "/romfs/x/"), "romfs is not walked into: " + paths);
    }

    // ---- The sources ----

    static void sources(File folder) throws Exception {
        check(CheatSources.list(folder).isEmpty() && CheatSources.appEnabled(folder) == null, "nothing yet");
        CheatSources.Source s1 = CheatSources.add(folder, "pack", CheatSources.FROM_ZIP, Map.of(B, SITE, OTHER, SMALL), 1000);
        CheatSources.Source s2 = CheatSources.add(folder, "Pack", CheatSources.FROM_FILE, Map.of(B, SMALL), 2000);
        CheatSources.Source s3 = CheatSources.add(folder, "../odd/name?", CheatSources.FROM_FOLDER, Map.of(B, SMALL + "[X]\n04000000 00000010 00000001\n"), 1500);
        List<CheatSources.Source> l = CheatSources.list(folder);
        check(l.size() == 3 && l.get(0).id.equals(s1.id) && l.get(1).id.equals(s3.id) && l.get(2).id.equals(s2.id), "by when they were added: " + l);
        check(s2.name.equals("Pack 2"), "a name is unique without case: " + s2.name);
        check(!s3.id.contains("/") && !s3.id.startsWith("."), "a folder name of its own: " + s3.id);
        check(s1.files().keySet().equals(Set.of(B, OTHER)) && s1.text(B).equals(SITE) && s1.text("1111111111111111") == null, "its files by build");
        check(CheatSources.sameAs(folder, B, SMALL.replace("\n", "\r\n")) != null && CheatSources.sameAs(folder, B, "[New]\n04000000 0 1\n") == null, "the same file again is found");
        CheatSources.enabled(folder, s2.id, false);
        check(!CheatSources.find(folder, s2.id).enabled && CheatSources.find(folder, s1.id).enabled, "a source off as a whole");
        check(CheatSources.remove(folder, s3.id) && CheatSources.find(folder, s3.id) == null && !CheatSources.remove(folder, "..") && !CheatSources.remove(folder, CheatSources.APP),
                "a source removed, nothing else");
        CheatSources.appEnabled(folder, false);
        check(Boolean.FALSE.equals(CheatSources.appEnabled(folder)) && CheatSources.list(folder).size() == 2, "the app's state is not a source");
        // upstream's earlier imports move over
        File old = new File(folder, "imported");
        check(old.mkdirs(), "mkdirs imported");
        Files.write(new File(old, "b old.txt").toPath(), u("build " + B + "\n" + SMALL));
        Files.write(new File(old, "a old.txt").toPath(), u("build " + B + "\n[Y]\n04000000 00000010 00000001\n"));
        List<CheatSources.Source> moved = CheatSources.migrate(folder, 3000);
        check(moved.size() == 2 && moved.get(0).name.equals("a old") && moved.get(1).name.equals("b old") && !old.exists(), "imported/ became sources: " + moved);
        check(CheatSources.migrate(folder, 4000).isEmpty(), "once");
        // Export cheats…: upstream's format, each source's file of this build with its build line, and the toggles; an import reads it back
        ByteArrayOutputStream ex = new ByteArrayOutputStream();
        int n = CheatSources.exportZip(ex, TID, B, folder, true, new java.util.LinkedHashSet<>(List.of("Coins", "Y")));
        Map<String, String> got = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new ByteArrayInputStream(ex.toByteArray()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) got.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        check(n == 4 && got.size() == 5 && got.containsKey("toggles.json"), "4 sources with a file of this build and the toggles: " + got.keySet());
        for (Map.Entry<String, String> e : got.entrySet()) if (!e.getKey().equals("toggles.json")) check(e.getKey().startsWith("cheats/") && e.getValue().startsWith("build " + B + "\n"), "a build line first: " + e.getKey());
        check(got.get("toggles.json").equals("{\"title_id\": \"" + TID + "\", \"build\": \"" + B + "\", \"cheats\": true, \"on\": [\"Coins\", \"Y\"]}\n"), got.get("toggles.json"));
        List<String> paths = new ArrayList<>();
        CheatImport.Found back = CheatImport.read(CheatImport.fromZip(new ByteArrayInputStream(ex.toByteArray()), paths), paths, TID, BUILDS, "export.zip");
        check(back.forGame.size() == 3 && back.otherVersion.isEmpty(), "the export reads back by its build lines (two sources with one text are one file): " + back.forGame);
        check(CheatSources.withBuild("\n\nbuild 0123456789abcdef\n[A]\n", B).equals("build " + B + "\n[A]\n"), "an earlier build line is replaced");
        // the export imported again (upstream's Import cheats…: imported/, then sources): what the game has already is not added twice
        int before = CheatSources.list(folder).size();
        check(old.mkdirs(), "mkdirs imported again");
        Files.write(new File(old, "again.txt").toPath(), u("build " + B + "\n" + SITE));   // s1 keeps SITE without a build line: the same cheats
        Files.write(new File(old, "fresh.txt").toPath(), u("build " + B + "\n[Fresh]\n04000000 00000010 00000001\n"));
        Files.write(new File(old, "fresh2.txt").toPath(), u("build " + B + "\n[Fresh]\n04000000 00000010 00000001\n"));
        int stay = CheatSources.dropDuplicates(folder, CheatSources.migrate(folder, 5000), B);
        check(stay == 1 && CheatSources.list(folder).size() == before + 1, "one new source, the copies gone: " + CheatSources.list(folder));
    }

    // ---- The merged file ----

    static void merge(File folder) throws Exception {
        String app = "[Coins]\n04000000 00001000 00000001\n[Moon]\n04000000 00001004 00000001\n";
        CheatSources.add(folder, "site", CheatSources.FROM_ZIP, Map.of(B, SITE + "[Coins]\n04000000 00001008 00000001\n"), 1000);
        CheatSources.Source off = CheatSources.add(folder, "second", CheatSources.FROM_FILE, Map.of(B, "[Moon]\n04000000 00001010 00000001\n[Odd]\n04000000 00001014 00000001\n"), 2000);
        CheatSources.add(folder, "third", CheatSources.FROM_FILE, Map.of(B, "[Moon]\n04000000 00001018 00000001\n[ODD]\n04000000 0000101C 00000001\n"), 3000);
        CheatSources.enabled(folder, off.id, false);
        CheatSources.Merged m = CheatSources.merge(CheatSources.blocks(folder, app, true, B, 0x200000));
        List<String> keys = m.keys();
        check(keys.equals(List.of("Coins", "Moon", "1. Lots of coins", "2. No item cost", "3. Coins as a number", "4. Fast run (Hold ZL)", "5. High jump (Hold B)",
                "7. Two buttons", "8. Data in main", "8. Data in main #2", "Coins (site)", "Moon (third)", "ODD (third)")), "the keys, the switched-off source's names kept free: " + keys);
        check(m.key(off.id, "Moon").equals("Moon (second)") && m.key(off.id, "Odd").equals("Odd"), "a source that is off keeps its names for when it is on again");
        check(m.key("site", "6. Pause trick") == null, "Not supported has no key");
        String t = m.text;
        check(!t.contains("Restore Code") && !t.contains("Pause trick") && !t.contains("Odd width") && !t.contains("www.") && !t.contains("TID=") && !t.contains("20000001"),
                "credits, titles, headings, Restore Code and Not supported are left out:\n" + t);
        check(!t.contains("[Moon (second)]"), "a source that is off is not in the file");
        check(t.indexOf("{Setup}") < t.indexOf("[1. Lots of coins]") && t.indexOf("{Setup}") > t.indexOf("[Moon]"), "a source's master comes before its cheats");
        check(t.contains("[--SectionStart:site: Items--]\n[1. Lots of coins]\n040A0000 00001000 B9004009\n") && t.contains("[--SectionEnd:site: Items--]\n[--SectionStart:site: Moving--]"),
                "the groups as sections, with the source's name when more than one is on:\n" + t);
        CheatFile.Parsed again = CheatFile.parse(t, 0x200000);
        java.util.Set<String> low = new java.util.HashSet<>();
        for (CheatFile.Cheat c : again.cheats) check(low.add(c.key.toLowerCase()), "unique without case: " + c.key);
        check(again.cheats.size() == m.cheats && m.cheats == 13 && again.masters.size() == 1, "the merged file reads back: " + again.cheats.size());
        check(again.groups.equals(List.of("Comes with the app", "site: Items", "site: Moving", "site: Extras", "site", "third")), "its groups, a cheat outside one under its source's name: " + again.groups);
        // one source alone: its groups without its name; a long name still fits a header
        File one = new File(folder.getParentFile(), "merge-one");
        CheatSources.add(one, "solo", CheatSources.FROM_ZIP, Map.of(B, SITE), 1);
        check(CheatSources.merge(CheatSources.blocks(one, null, true, B, -1)).text.contains("[--SectionStart:Items--]"), "one source: the group alone");
        String longName = "x".repeat(127);
        CheatSources.Merged lm = CheatSources.merge(List.of(
                new CheatSources.Block("a", "a", true, CheatFile.parse("[" + longName + "]\n04000000 00000010 00000001\n", -1)),
                new CheatSources.Block("b", "bee", true, CheatFile.parse("[" + longName + "]\n04000000 00000010 00000001\n", -1))));
        check(lm.keys().get(1).length() == 127 && lm.keys().get(1).endsWith(" (bee)"), "a long clashing name is cut to fit: " + lm.keys().get(1).length());
    }

    // ---- A real file, when one is given (never in the repository) ----

    static void real(File f) throws Exception {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        CheatFile.Parsed p = CheatFile.parse(text, -1);
        int code = 0, hold = 0, unsup = 0;
        for (CheatFile.Cheat c : p.cheats) { if (c.kind == CheatFile.Kind.CODE) code++; if (!c.hold.isEmpty()) hold++; if (c.kind == CheatFile.Kind.UNSUPPORTED) unsup++; }
        System.err.println("real file: " + p.cheats.size() + " cheats, " + p.groups.size() + " groups " + p.groups + ", " + p.masters.size() + " masters, " + p.credits.size()
                + " credits, " + p.restores.size() + " restore, " + code + " code, " + hold + " hold, " + unsup + " not supported");
        for (CheatFile.Cheat c : p.cheats) System.err.println("  " + c + "  " + CheatFile.describe(c));
        check(p.any(), "the real file has cheats");
    }
}
