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
 * restore entries, kinds and hold buttons), the import's layouts and its choice of build, the sources (.mncf files: MncfConvert, MncfMain, MncfBuild), the
 * merged file, the number field, export and packs.
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
        mncf(new File(root, "mncf-case"));
        updates(new File(root, "updates-case"));
        packUpdates(new File(root, "pack-updates-case"));
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

    // ---- A pack's main for the .mncf sources (MncfMain): made up, as patch_nso.py writes one ----

    /** A made-up pack: main.patched.bin (text up to 0x8000, read-only data to 0xC000, data to 0x10000), its layout, and the words SITE's restore code names */
    static File pack(File dir, String bid, int shift) throws Exception {
        check(dir.mkdirs() || dir.isDirectory(), "mkdirs " + dir);
        byte[] img = new byte[0x10000 + shift];
        new java.util.Random(7).nextBytes(img);
        for (int i = 0; i < 0x80; i += 4) put(img, shift + 0x7000 + i, 0xD503201FL);   // nop padding a pattern does not match by chance
        put(img, shift + 0x1000, 0xB9004008L);
        put(img, shift + 0x1004, 0x94000123L);
        put(img, shift + 0x2000, 0x52800000L); put(img, shift + 0x2004, 0x52800000L); put(img, shift + 0x2008, 0x2BCA1F01L);
        put(img, shift + 0x3000, 0x1E2E1000L); put(img, shift + 0x3004, 0x1E201001L);
        put(img, shift + 0x4000, 0x52800000L); put(img, shift + 0x4004, 0x2BCA1F01L);
        Files.write(new File(dir, "main.patched.bin").toPath(), img);
        Files.write(new File(dir, "main.fixups.tsv").toPath(), u(String.format("# PATCH_OFF\t0x%x\n# TEXT_END\t0x%x\n# RO_OFF\t0x%x\n# DATA_OFF\t0x%x\n# BUILD_ID\t%s0000000000000000\n",
                0x10000 + shift, 0x8000 + shift, 0x8000 + shift, 0xC000 + shift, bid)));
        return dir;
    }

    static void put(byte[] b, int at, long w) { for (int i = 0; i < 4; i++) b[at + i] = (byte) (w >>> (8 * i)); }

    static MncfConvert.Known known(File pack, String author) {
        return new MncfConvert.Known(TID, "Example Game", author, B, Map.of(B, "1.0.0"), MncfConvert.of(pack));
    }

    static MncfBuild.Game game(String build, File pack) { return new MncfBuild.Game(TID, build, build.equals(B) ? "1.0.0" : null, pack); }

    static io.github.davdxpx.mistel.mncf.MncfFile load(File f) throws Exception { return io.github.davdxpx.mistel.mncf.Mncf.load(Files.readAllBytes(f.toPath())); }

    // ---- The sources ----

    static void sources(File folder) throws Exception {
        File pk = pack(new File(folder.getParentFile(), "sources-pack"), B, 0);
        MncfConvert.Known k = known(pk, "Tester");
        check(CheatSources.list(folder).isEmpty() && CheatSources.appEnabled(folder) == null, "nothing yet");
        CheatSources.Source s1 = CheatSources.add(folder, "pack", CheatSources.FROM_ZIP, Map.of(B, SITE, OTHER, SMALL), 1000, k, "pack.zip");
        CheatSources.Source s2 = CheatSources.add(folder, "Pack", CheatSources.FROM_FILE, Map.of(B, SMALL), 2000, k, "Pack.txt");
        CheatSources.Source s3 = CheatSources.add(folder, "../odd/name?", CheatSources.FROM_FOLDER, Map.of(B, SMALL + "[X]\n04000000 00000010 00000001\n"), 1500, k, null);
        List<CheatSources.Source> l = CheatSources.list(folder);
        check(l.size() == 3 && l.get(0).id.equals(s1.id) && l.get(1).id.equals(s3.id) && l.get(2).id.equals(s2.id), "by when they were added: " + l);
        check(s2.name.equals("Pack 2"), "a name is unique without case: " + s2.name);
        check(!s3.id.contains("/") && !s3.id.startsWith("."), "a folder name of its own: " + s3.id);
        // one .mncf a source, for all of its builds, with what is known: the game, the versions, where it came from, who made it
        String[] kept = s1.dir.list();
        check(kept != null && java.util.Arrays.asList(kept).containsAll(List.of(CheatSources.MNCF, CheatSources.STATE)) && !new File(s1.dir, B + ".txt").exists(), "an .mncf, no Atmosphère file: " + java.util.Arrays.toString(kept));
        io.github.davdxpx.mistel.mncf.MncfFile f1 = load(s1.file());
        check(f1.gameId().equals(TID) && "Example Game".equals(f1.gameName()) && new java.util.HashSet<>(s1.builds()).equals(Set.of(B, OTHER)), "the game and both builds: " + s1.builds());
        check("1.0.0".equals(MncfBuild.version(f1, B)) && MncfBuild.version(f1, OTHER) == null, "the version the app knows names its build: " + MncfBuild.version(f1, B) + " " + MncfBuild.version(f1, OTHER) + " " + f1.builds().keySet());
        check(f1.authors().equals(List.of("Example Team")), "the authors its credits name, not the player: " + f1.authors());
        check("atmosphere".equals(f1.origin().get("format")) && "pack.zip".equals(f1.origin().get("name")) && "example-cheats.com".equals(f1.origin().get("site")), "its origin: " + f1.origin());
        check(f1.about() != null && f1.about().contains("enjoy"), "its credit lines in about: " + f1.about());
        check(load(s2.file()).authors().equals(List.of("Tester")), "no credits: the player who adds it");
        check(load(CheatSources.add(folder, "nobody", CheatSources.FROM_FILE, Map.of(B, "[N]\n04000000 00000020 00000001\n"), 2500,
                new MncfConvert.Known(TID, null, null, null, null, null), null).file()).authors().isEmpty(), "no credits and no name: no author");
        CheatSources.remove(folder, "nobody");
        // a note line Mistel's reader would refuse is kept in about, the file is still converted
        CheatSources.Source noted = CheatSources.add(folder, "noted", CheatSources.FROM_FILE, Map.of(B, "Made for fun\n[Coins]\n04000000 00001000 00000063\nsee the site\n"), 2600, k, null);
        check(load(noted.file()).about().contains("Made for fun") && load(noted.file()).about().contains("see the site") && load(noted.file()).cheats().size() == 1, "notes in about");
        CheatSources.remove(folder, noted.id);
        check(CheatSources.sameAs(folder, SMALL.replace("\n", "\r\n")) != null && CheatSources.sameAs(folder, "[New]\n04000000 0 1\n") == null, "the same file again is found");
        check(CheatSources.sameMncf(folder, Files.readAllBytes(s1.file().toPath())) != null && CheatSources.sameMncf(folder, u("format = \"mncf/1\"\n")) == null, "the same .mncf again is found");
        CheatSources.enabled(folder, s2.id, false);
        check(!CheatSources.find(folder, s2.id).enabled && CheatSources.find(folder, s1.id).enabled, "a source off as a whole");
        check(CheatSources.remove(folder, s3.id) && CheatSources.find(folder, s3.id) == null && !CheatSources.remove(folder, "..") && !CheatSources.remove(folder, CheatSources.APP),
                "a source removed, nothing else");
        CheatSources.appEnabled(folder, false);
        check(Boolean.FALSE.equals(CheatSources.appEnabled(folder)) && CheatSources.list(folder).size() == 2, "the app's state is not a source");
        // upstream's earlier imports move over, as .mncf
        File old = new File(folder, "imported");
        check(old.mkdirs(), "mkdirs imported");
        Files.write(new File(old, "b old.txt").toPath(), u("build " + B + "\n" + SMALL));
        Files.write(new File(old, "a old.txt").toPath(), u("build " + B + "\n[Y]\n04000000 00000010 00000001\n"));
        List<CheatSources.Source> moved = CheatSources.migrate(folder, 3000, k);
        check(moved.size() == 2 && moved.get(0).name.equals("a old") && moved.get(1).name.equals("b old") && !old.exists() && moved.get(0).file().isFile(), "imported/ became sources: " + moved);
        check(CheatSources.migrate(folder, 4000, k).isEmpty(), "once");
        // an earlier version of the app kept a source as Atmosphère files by build: it becomes one .mncf, the files go
        File earlier = new File(CheatSources.root(folder), "earlier");
        check(earlier.mkdirs(), "mkdirs earlier");
        Files.write(new File(earlier, B + ".txt").toPath(), u(SITE));
        Files.write(new File(earlier, OTHER + ".txt").toPath(), u(SMALL));
        Files.write(new File(earlier, CheatSources.STATE).toPath(), u("name\tEarlier\nfrom\tzip\nadded\t500\nenabled\t1\n"));
        check(CheatSources.upgrade(folder, k) == 1 && CheatSources.upgrade(folder, k) == 0, "once");
        CheatSources.Source up = CheatSources.find(folder, "earlier");
        check(up.file().isFile() && !new File(earlier, B + ".txt").exists() && new java.util.HashSet<>(up.builds()).equals(Set.of(B, OTHER)) && CheatSources.sameAs(folder, SMALL) != null, "an .mncf of both builds: " + up.builds());
        // the export imported again (upstream's Import cheats…: imported/, then sources): what the game has already is not added twice
        int before = CheatSources.list(folder).size();
        check(old.mkdirs(), "mkdirs imported again");
        Files.write(new File(old, "again.txt").toPath(), u("build " + B + "\n" + SITE));   // s1 was made from SITE: the same cheats
        Files.write(new File(old, "fresh.txt").toPath(), u("build " + B + "\n[Fresh]\n04000000 00000010 00000001\n"));
        Files.write(new File(old, "fresh2.txt").toPath(), u("build " + B + "\n[Fresh]\n04000000 00000010 00000001\n"));
        int stay = CheatSources.dropDuplicates(folder, CheatSources.migrate(folder, 5000, k));
        check(stay == 1 && CheatSources.list(folder).size() == before + 1, "one new source, the copies gone: " + CheatSources.list(folder));
    }

    // ---- The merged file ----

    static void merge(File folder) throws Exception {
        File pk = pack(new File(folder.getParentFile(), "merge-pack"), B, 0);
        MncfConvert.Known k = known(pk, null);
        MncfBuild.Game g = game(B, pk);
        String app = "[Coins]\n04000000 00001000 00000001\n[Moon]\n04000000 00001004 00000001\n";
        CheatSources.add(folder, "site", CheatSources.FROM_ZIP, Map.of(B, SITE + "[Coins]\n04000000 00001008 00000001\n"), 1000, k, null);
        CheatSources.Source off = CheatSources.add(folder, "second", CheatSources.FROM_FILE, Map.of(B, "[Moon]\n04000000 00001010 00000001\n[Odd]\n04000000 00001014 00000001\n"), 2000, k, null);
        CheatSources.add(folder, "third", CheatSources.FROM_FILE, Map.of(B, "[Moon]\n04000000 00001018 00000001\n[ODD]\n04000000 0000101C 00000001\n"), 3000, k, null);
        CheatSources.enabled(folder, off.id, false);
        CheatSources.Merged m = CheatSources.merge(CheatSources.blocks(folder, app, true, g, 0x8000, true));
        List<String> keys = m.keys();
        check(keys.equals(List.of("Coins", "Moon", "1. Lots of coins", "2. No item cost", "4. Fast run (Hold ZL)", "5. High jump (Hold B)",
                "7. Two buttons", "8. Data in main", "8. Data in main #2", "Coins (site)", "Moon (third)", "ODD (third)")), "the keys the Atmosphère files had, the switched-off source's names kept free: " + keys);
        check(m.key(off.id, "Moon").equals("Moon (second)") && m.key(off.id, "Odd").equals("Odd"), "a source that is off keeps its names for when it is on again");
        check(m.key("site", "6. Pause trick") == null && m.key("site", "3. Coins as a number") == null, "what cannot run has no key");
        CheatSources.Block site = null;
        for (CheatSources.Block b : m.blocks) if (b.id.equals("site")) site = b;
        check(site != null && site.parsed.mncf != null && site.error == null && !site.pending, "the source's block is its .mncf made ready");
        CheatFile.Cheat pointer = cheat(site.parsed, "3. Coins as a number"), pause = cheat(site.parsed, "6. Pause trick");
        check("outside".equals(pointer.reason()) && pointer.kind == CheatFile.Kind.UNSUPPORTED && !pointer.switchable(), "a place outside the made-up main: refused with the spec's reason: " + pointer.reason());
        check(pause.reason() == null && pause.kind == CheatFile.Kind.UNSUPPORTED && pause.why.startsWith("opcode"), "raw code the host does not run: Not supported, as before");
        check(CheatFile.describe(pointer).equals("Its place lies outside the game. It stays off."), CheatFile.describe(pointer));
        String t = m.text;
        check(!t.contains("Restore Code") && !t.contains("Pause trick") && !t.contains("Odd width") && !t.contains("www.") && !t.contains("TID=") && !t.contains("20000001"),
                "credits, titles, headings, Restore Code and what cannot run are left out:\n" + t);
        check(!t.contains("[Moon (second)]"), "a source that is off is not in the file");
        check(t.indexOf("{Setup}") < t.indexOf("[1. Lots of coins]") && t.indexOf("{Setup}") > t.indexOf("[Moon]"), "a source's master comes before its cheats");
        check(t.contains("[--SectionStart:site: Items--]\n[1. Lots of coins]\n04000000 00001000 B9004009\n") && t.contains("[--SectionEnd:site: Items--]\n[--SectionStart:site: Moving--]"),
                "the groups as sections, with the source's name when more than one is on, the code made from the cheat's patch:\n" + t);
        check(t.contains("[4. Fast run (Hold ZL)]\n80000100\n04000000 00003000 1E241000\n21000000\n04000000 00003000 1E2E1000\n20000000\n"), "a hold with the original back on release:\n" + t);
        CheatFile.Parsed again = CheatFile.parse(t, 0x8000);
        java.util.Set<String> low = new java.util.HashSet<>();
        for (CheatFile.Cheat c : again.cheats) check(low.add(c.key.toLowerCase()), "unique without case: " + c.key);
        check(again.cheats.size() == m.cheats && m.cheats == 12 && again.masters.size() == 1, "the merged file reads back: " + again.cheats.size());
        check(again.groups.equals(List.of("Comes with the app", "site: Items", "site: Moving", "site: Extras", "site", "third")), "its groups, EdiZon's sections among them: " + again.groups);
        // one source alone: its groups without its name; a long name still fits a header
        File one = new File(folder.getParentFile(), "merge-one");
        CheatSources.add(one, "solo", CheatSources.FROM_ZIP, Map.of(B, SITE), 1, k, null);
        check(CheatSources.merge(CheatSources.blocks(one, null, true, g, -1, true)).text.contains("[--SectionStart:Items--]"), "one source: the group alone");
        String longName = "x".repeat(127);
        CheatSources.Merged lm = CheatSources.merge(List.of(
                new CheatSources.Block("a", "a", true, CheatFile.parse("[" + longName + "]\n04000000 00000010 00000001\n", -1)),
                new CheatSources.Block("b", "bee", true, CheatFile.parse("[" + longName + "]\n04000000 00000010 00000001\n", -1))));
        check(lm.keys().get(1).length() == 127 && lm.keys().get(1).endsWith(" (bee)"), "a long clashing name is cut to fit: " + lm.keys().get(1).length());
    }

    // ---- .mncf for the build that runs (MncfMain, MncfBuild, the number field, export, packs) ----

    static final String HAND = String.join("\n",
            "format = \"mncf/1\"",
            "name = \"Hand-made\"",
            "authors = [\"someone\"]",
            "extensions = { optional = [\"arm64-imm/1\"] }",
            "",
            "[game]",
            "platform = \"switch\"",
            "id = \"" + TID + "\"",
            "",
            "[[cheat]]",
            "id = \"coins\"",
            "name = \"Coins by pattern\"",
            "about = \"Buying keeps your coins\"",
            "tags = [\"touches_save\"]",
            "",
            "  [[cheat.patch]]",
            "  at = { module = \"main\", find = \"08 40 00 B9 23 01 00 94\" }",
            "  expect = \"08 40 00 B9\"",
            "  bytes = \"09 40 00 B9\"",
            "",
            "[[cheat]]",
            "id = \"count\"",
            "name = \"A count\"",
            "radio = \"r\"",
            "",
            "  [cheat.values.n]",
            "  type = \"u16\"",
            "  min = 1",
            "  max = 500",
            "  default = 5",
            "  unit = \"coins\"",
            "",
            "  [[cheat.patch]]",
            "  at = { module = \"main\", find = \"00 00 80 52 00 00 80 52\" }",
            "  expect = \"00 00 80 52\"",
            "  encode = { insn = \"movz\", width = 32, rd = 0, value = \"$n\" }",
            "",
            "[[cheat]]",
            "id = \"other\"",
            "name = \"The other one\"",
            "radio = \"r\"",
            "requires = [\"coins\"]",
            "",
            "  [[cheat.write]]",
            "  at = { region = \"heap\", offset = 0x100 }",
            "  type = \"u32\"",
            "  value = 1",
            "",
            "[[cheat]]",
            "id = \"view\"",
            "name = \"A monitor\"",
            "run = \"monitor\"",
            "",
            "  [[cheat.read]]",
            "  at = { region = \"heap\", offset = 0x100 }",
            "  type = \"u32\"",
            "  label = \"N\"",
            "",
            "[[cheat]]",
            "id = \"once\"",
            "name = \"Once\"",
            "run = \"once\"",
            "",
            "  [[cheat.write]]",
            "  at = { region = \"heap\", offset = 0x104 }",
            "  type = \"u32\"",
            "  value = 2",
            "",
            "[[cheat]]",
            "id = \"gone\"",
            "name = \"Not in this version\"",
            "",
            "  [[cheat.patch]]",
            "  at = { module = \"main\", find = \"DE AD BE EF 11 22 33 44\" }",
            "  expect = \"DE AD BE EF\"",
            "  bytes = \"00 00 00 00\"",
            "");

    // ---- New versions of a file (CheatUpdates; Mistel's spec, section 17), with made-up files only ----

    /** Version n of a made-up file: what changes between 1, 2 and 3 is said in each */
    static String version(int n) {
        List<String> l = new ArrayList<>(List.of(
                "format = \"mncf/1\"",
                "name = \"Versions\"",
                "id = \"example.org/someone/versions\"",
                "file_version = \"" + n + "\"",
                "extensions = { optional = [\"arm64-imm/1\"] }",
                "",
                "[update]",
                "url = \"https://example.org/someone/versions.mncf\""));
        if (n >= 2) l.addAll(List.of("", "[[changes]]", "version = \"2\"", "date = \"2026-12-02\"", "text = \"Fits the new game version.\"",
                "added = [\"extra\"]", "fixed = [\"coins\"]", "removed = [\"other\"]", "renamed = { count = \"amount\" }"));
        if (n >= 3) l.addAll(List.of("", "[[changes]]", "version = \"3\"", "text = \"Tidier names.\""));
        if (n >= 2) l.addAll(List.of("", "[i18n.de.changes.\"2\"]", "text = \"Passt zur neuen Spielversion.\""));
        l.addAll(List.of("", "[game]", "platform = \"switch\"", "id = \"" + TID + "\"",
                "", "[[cheat]]", "id = \"coins\"", "name = \"Coins by pattern\"",
                "", "  [[cheat.patch]]", "  at = { module = \"main\", find = \"08 40 00 B9 23 01 00 94\" }", "  expect = \"08 40 00 B9\"", "  bytes = \"09 40 00 B9\"",
                "", "[[cheat]]", "id = \"" + (n >= 2 ? "amount" : "count") + "\"", "name = \"" + (n >= 2 ? "An amount" : "A count") + "\"",
                "", "  [cheat.values.n]", "  type = \"u16\"", "  min = 1", "  max = " + (n >= 2 ? 200 : 500), "  default = 5",
                "", "  [[cheat.patch]]", "  at = { module = \"main\", find = \"00 00 80 52 00 00 80 52\" }", "  expect = \"00 00 80 52\"",
                "  encode = { insn = \"movz\", width = 32, rd = 0, value = \"$n\" }"));
        if (n == 1) l.addAll(List.of("", "[[cheat]]", "id = \"other\"", "name = \"The other one\"",
                "", "  [[cheat.write]]", "  at = { region = \"heap\", offset = 0x100 }", "  type = \"u32\"", "  value = 1"));
        else l.addAll(List.of("", "[[cheat]]", "id = \"extra\"", "name = \"Extra\"", "default = true",
                "", "  [[cheat.write]]", "  at = { region = \"heap\", offset = 0x200 }", "  type = \"u32\"", "  value = 2"));
        return String.join("\n", l) + "\n";
    }

    /** A stand-in for the address: the bytes it answers with, and what was asked */
    static final class Answer implements CheatUpdates.Http {
        byte[] data;
        final List<String> asked = new ArrayList<>();
        Answer(byte[] data) { this.data = data; }
        @Override public byte[] get(String url, int max) throws java.io.IOException {
            asked.add(url);
            if (data == null) throw new java.io.IOException("no answer");
            return data;
        }
    }

    static void updates(File root) throws Exception {
        File pk = pack(new File(root, "pack"), B, 0), folder = new File(root, "folder");
        CheatSources.Source s = CheatSources.addMncf(folder, "versions", CheatSources.FROM_FILE, u(version(1)), 1);
        MncfBuild.Game g = game(B, pk);

        // The question once per file and host; looks on its own only after a yes, once a day
        check("example.org".equals(CheatUpdates.host(s)) && CheatUpdates.allowed(s) == null, "the host, not asked yet");
        check(!CheatUpdates.due(s, 10 * CheatUpdates.DAY, false), "not asked: no look on its own");
        CheatUpdates.allowed(s, true);
        check(Boolean.TRUE.equals(CheatUpdates.allowed(s)) && CheatUpdates.due(s, 10 * CheatUpdates.DAY, false), "allowed: due");

        // What the player set on version 1: coins and the other one on, the count's number 300
        CheatSources.Merged m1 = CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true));
        Set<String> on = new java.util.LinkedHashSet<>(List.of("Coins by pattern", "The other one"));
        CheatFile.Cheat count = null;
        for (CheatSources.Row r : m1.rows) if (r.key.equals("A count")) count = r.cheat;
        check(count != null, "the count is in the merge");
        CheatValues.keep(folder, "A count", count, 300);

        // The same version at the address: nothing to install; version 2: kept as the offer, not installed
        Answer at = new Answer(u(version(1)));
        CheatUpdates.Look same = CheatUpdates.look(s, at, 10 * CheatUpdates.DAY);
        check(same.reason.equals("same") && at.asked.equals(List.of("https://example.org/someone/versions.mncf")), "the same version: " + same.reason);
        check(!CheatUpdates.due(s, 10 * CheatUpdates.DAY + 1000, false) && CheatUpdates.due(s, 11 * CheatUpdates.DAY, false), "the look noted: once a day");
        at.data = u(version(2));
        CheatUpdates.Look found = CheatUpdates.look(s, at, 11 * CheatUpdates.DAY);
        check(found.found() && "2".equals(found.version) && CheatUpdates.offered(s) != null, "version 2 found and kept as the offer: " + found.reason);
        check(java.util.Arrays.equals(Files.readAllBytes(s.file().toPath()), u(version(1))), "nothing installed without the player");
        at.data = null;
        check(CheatUpdates.look(s, at, 12 * CheatUpdates.DAY).reason.equals("unreachable"), "no answer: unreachable");

        // What the update screen shows
        CheatUpdates.Offer o = CheatUpdates.offer(s, u(version(2)), m1, on, CheatValues.read(folder), B);
        check(o.newer() && o.since.size() == 1 && o.since.get(0).version().equals("2") && "example.org".equals(o.host), "a newer version from example.org");
        check(o.added.equals(List.of("Extra")) && o.fixed.equals(List.of("Coins by pattern")), "its marks: " + o.added + " " + o.fixed);
        check(o.reset.equals(List.of("An amount")) && o.gone.equals(List.of("The other one")), "300 no longer fits, the other one is gone: " + o.reset + " " + o.gone);
        check(CheatUpdates.text(o.next, o.since.get(0), java.util.Locale.GERMANY).equals("Passt zur neuen Spielversion.")
                && CheatUpdates.text(o.next, o.since.get(0), java.util.Locale.UK).equals("Fits the new game version."), "the notes in the player's language");

        // Installed: the old file kept, the old names out, what the player set by id until the new file is in the merge
        CheatUpdates.Done d = CheatUpdates.install(folder, s, u(version(2)), m1, on);
        check(d.version.equals("2") && d.reset.equals(List.of("An amount")) && d.gone.equals(List.of("The other one")), "installed");
        check(on.isEmpty() && CheatValues.read(folder).isEmpty(), "the old names are out: " + on + " " + CheatValues.read(folder).keySet());
        check(new File(s.dir, CheatUpdates.PREVIOUS).isFile() && new File(s.dir, CheatUpdates.CARRY).isFile() && CheatUpdates.offered(s) == null, "the files");
        check(CheatUpdates.marks(s, false).equals(Set.of("extra")) && CheatUpdates.marks(s, true).equals(Set.of("coins")), "the marks");
        check(Boolean.TRUE.equals(CheatUpdates.allowed(s)), "the same host: the answer stays");
        CheatSources.Merged m2 = CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true));
        check(CheatUpdates.applyPending(folder, m2, on), "put in place");
        check(on.equals(Set.of("Coins by pattern", "Extra")) && CheatValues.read(folder).isEmpty() && !new File(s.dir, CheatUpdates.CARRY).isFile(),
                "coins stays on, the new cheat is on as its file says, the number went back to the file's own: " + on + " " + CheatValues.read(folder).keySet());
        check(!CheatUpdates.applyPending(folder, m2, on), "once");
        CheatUpdates.seen(folder);
        check(CheatUpdates.marks(s, false).isEmpty() && CheatUpdates.marks(s, true).isEmpty(), "the marks seen at the start");

        // A number that fits moves with a renamed cheat
        CheatFile.Cheat amount = null;
        for (CheatSources.Row r : m2.rows) if (r.key.equals("An amount")) amount = r.cheat;
        CheatValues.keep(folder, "An amount", amount, 150);
        CheatUpdates.Done d3 = CheatUpdates.install(folder, s, u(version(3)), m2, on);
        CheatUpdates.applyPending(folder, CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true)), on);
        check(d3.reset.isEmpty() && CheatValues.read(folder).get("an amount").number == 150 && on.equals(Set.of("Coins by pattern", "Extra")), "150 stays: " + CheatValues.read(folder).keySet());

        // Back to version 2: what the player set moves back, version 3 is not offered again, version 4 would be
        CheatSources.Merged m3 = CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true));
        on.remove("Extra");
        CheatUpdates.Done b = CheatUpdates.back(folder, s, m3, on);
        CheatUpdates.applyPending(folder, CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true)), on);
        check(b.version.equals("2") && on.equals(Set.of("Coins by pattern")) && CheatValues.read(folder).get("an amount").number == 150 && CheatUpdates.previous(s) == null,
                "back at 2: " + on + " " + CheatValues.read(folder).keySet());
        check(CheatUpdates.look(s, new Answer(u(version(3))), 20 * CheatUpdates.DAY).reason.equals("left"), "the version left is not offered again");
        check(CheatUpdates.look(s, new Answer(u(version(3).replace("file_version = \"3\"", "file_version = \"4\""))), 21 * CheatUpdates.DAY).found(), "a later one is");
        check(CheatUpdates.look(s, new Answer(u(version(1))), 22 * CheatUpdates.DAY).reason.equals("older"), "an older one never replaces it");
        check(CheatUpdates.look(s, new Answer(u(version(4).replace("example.org/someone/versions", "example.org/someone/other"))), 23 * CheatUpdates.DAY).reason.equals("update:id"),
                "another file at the address is not taken");

        // A file the player adds by hand that has the id: a version of this one; another host is asked about again
        check(CheatUpdates.versionOf(folder, io.github.davdxpx.mistel.mncf.Mncf.load(u(version(1)))).id.equals(s.id), "the same id and game");
        check(CheatUpdates.versionOf(folder, io.github.davdxpx.mistel.mncf.Mncf.load(u(HAND))) == null, "no id: a file of its own");
        String moved = version(4).replace("https://example.org/someone/versions.mncf", "https://example.net/versions.mncf");
        CheatUpdates.install(folder, s, u(moved), CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true)), on);
        check(CheatUpdates.allowed(s) == null && "example.net".equals(CheatUpdates.host(s)), "a new host: asked again");

        // When a game update stops the cheats: the moment to look
        File po = pack(new File(root, "pack-other"), OTHER, 0x3000);
        byte[] changed = Files.readAllBytes(new File(po, "main.patched.bin").toPath());
        put(changed, 0x3000 + 0x1000, 0xB9004108L);   // the game update changed the code the coins cheat expects
        Files.write(new File(po, "main.patched.bin").toPath(), changed);
        CheatSources.Block stuckBlock = CheatSources.blocks(folder, null, true, game(OTHER, po), 0x8000, true).get(0);
        check(CheatUpdates.stuck(stuckBlock, OTHER), "its pattern not found in the new build");
        check(!CheatUpdates.stuck(CheatSources.blocks(folder, null, true, g, 0x8000, true).get(0), B), "found: not stuck");
        check(CheatUpdates.due(s, 0, true) == false, "not allowed for the new host: no look on its own, the card offers one");

        // What the player set for a cheat that cannot work in this build waits for a build where it can
        on.clear();
        on.add("Coins by pattern");
        CheatSources.Merged before = CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true));
        CheatUpdates.install(folder, s, u(version(4).replace("file_version = \"4\"", "file_version = \"5\"")), before, on);
        MncfBuild.Game other = game(OTHER, po);
        CheatUpdates.applyPending(folder, CheatSources.merge(CheatSources.blocks(folder, null, true, other, 0x8000, true)), on);
        check(!on.contains("Coins by pattern") && new File(s.dir, CheatUpdates.CARRY).isFile(), "kept while the build cannot run it: " + on);
        CheatUpdates.applyPending(folder, CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true)), on);
        check(on.contains("Coins by pattern") && !new File(s.dir, CheatUpdates.CARRY).isFile(), "on again in a build that can: " + on);

        // A cheat refused for a key the reader does not know before its name is read: listed by its id (it was a crash of the page)
        File odd = new File(root, "odd");
        CheatSources.addMncf(odd, "odd", CheatSources.FROM_FILE, u(HAND.replace("id = \"coins\"", "id = \"coins\"\nx-colour = 1")), 1);
        CheatSources.Block ob = CheatSources.blocks(odd, null, true, g, 0x8000, true).get(0);
        check(ob.parsed.cheats.get(0).name.equals("Coins by pattern") || ob.parsed.cheats.get(0).name.equals("coins"), "a refused cheat keeps a name: " + ob.parsed.cheats.get(0).name);

        // The request: https only, never to this device or its network
        for (String bad : new String[] {"http://example.org/x.mncf", "https://localhost/x.mncf", "https://127.0.0.1/x.mncf"}) {
            boolean refused = false;
            try { new CheatUpdates.Https().get(bad, 1000); } catch (java.io.IOException e) { refused = true; }
            check(refused, "refused: " + bad);
        }
    }

    /** A made-up pack: its manifest (version v, with an entry when v is 2) and its files, path to text */
    static byte[] mncp(int v, Map<String, String> files) throws Exception {
        StringBuilder m = new StringBuilder("format = \"mncf-pack/1\"\nname = \"Example pack\"\nid = \"example.org/someone/pack\"\nfile_version = \"" + v + "\"\n\n"
                + "[update]\nurl = \"https://example.org/someone/pack.mncp\"\n");
        if (v == 2) m.append("\n[[changes]]\nversion = \"2\"\ntext = \"B has a new place, C is gone, D is new.\"\nrenamed = { \"old/b.mncf\" = \"b.mncf\" }\n");
        for (Map.Entry<String, String> e : files.entrySet()) m.append("\n[[file]]\npath = \"").append(e.getKey()).append("\"\nsha256 = \"").append(MncfBuild.sha256(u(e.getValue()))).append("\"\n");
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(o)) {
            z.putNextEntry(new java.util.zip.ZipEntry("pack.toml"));
            z.write(u(m.toString()));
            for (Map.Entry<String, String> e : files.entrySet()) { z.putNextEntry(new java.util.zip.ZipEntry(e.getKey())); z.write(u(e.getValue())); }
        }
        return o.toByteArray();
    }

    /** A pack's file: name n, a write cheat w (on by default when on), an id when named */
    static String packFile(String n, String id, String w, boolean on) {
        return "format = \"mncf/1\"\nname = \"" + n + "\"\n" + (id == null ? "" : "id = \"example.org/someone/" + id + "\"\n")
                + "\n[game]\nplatform = \"switch\"\nid = \"" + TID + "\"\n\n[[cheat]]\nid = \"" + w + "\"\nname = \"Cheat " + n + "\"\n" + (on ? "default = true\n" : "")
                + "\n  [[cheat.write]]\n  at = { region = \"heap\", offset = 0x10 }\n  type = \"u8\"\n  value = 1\n";
    }

    static void packUpdates(File root) throws Exception {
        File pk = pack(new File(root, "pack"), B, 0), folder = new File(root, "folder");
        MncfBuild.Game g = game(B, pk);
        Map<String, String> v1 = new LinkedHashMap<>();
        v1.put("a.mncf", packFile("A", "a", "wa", false));
        v1.put("old/b.mncf", packFile("B", null, "wb", false));
        v1.put("c.mncf", packFile("C", "c", "wc", false));
        byte[] p1 = mncp(1, v1);
        // added as the pick adds it: a source per file, the pack kept
        String key = CheatUpdates.keep(folder, p1);
        check(key != null && key.equals("example.org_someone_pack"), "kept by its id: " + key);
        long t = 1;
        for (Map.Entry<String, String> e : v1.entrySet()) CheatUpdates.fromPack(CheatSources.addMncf(folder, CheatUpdates.nameOf(e.getKey()), CheatSources.FROM_ZIP, u(e.getValue()), t++), key, e.getKey());
        List<CheatSources.Source> of = CheatUpdates.sourcesOf(folder, key);
        check(of.size() == 3 && CheatUpdates.held(folder, of.get(0)) != null && CheatUpdates.host(of.get(0)) == null, "a pack's file follows its pack");
        CheatUpdates.Held h = CheatUpdates.held(folder, of.get(0));
        check("example.org".equals(h.host()) && CheatUpdates.allowed(h) == null && h.name().equals("Example pack"), "the pack's host, not asked yet");
        Set<String> on = new java.util.LinkedHashSet<>(List.of("Cheat A", "Cheat B", "Cheat C"));

        // version 2: A changed, B at a new path, C gone, D new and on by default
        Map<String, String> v2 = new LinkedHashMap<>();
        v2.put("a.mncf", packFile("A", "a", "wa", false).replace("value = 1", "value = 2"));
        v2.put("b.mncf", packFile("B", null, "wb", false));
        v2.put("d.mncf", packFile("D", "d", "wd", true));
        byte[] p2 = mncp(2, v2);
        CheatUpdates.Look l = CheatUpdates.look(h, new Answer(p2), 5);
        check(l.found() && CheatUpdates.offered(h) != null, "found: " + l.reason);
        CheatSources.Merged m = CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true));
        CheatUpdates.PackOffer o = CheatUpdates.offer(folder, h, p2, m, on, CheatValues.read(folder), B, TID);
        check(o.newer() && o.since.size() == 1 && o.files.size() == 1 && o.added.equals(List.of("d")) && o.gone.equals(List.of("c")), "what it brings: " + o.added + " " + o.gone);
        CheatUpdates.PackDone d = CheatUpdates.install(folder, h, p2, m, on, TID, 10);
        check(d.version.equals("2") && d.added.equals(List.of("d")) && d.gone.contains("c"), "installed: " + d.added + " " + d.gone);
        CheatUpdates.applyPending(folder, CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true)), on);
        check(on.equals(Set.of("Cheat A", "Cheat B", "Cheat D")), "A and B stay on, C went with its file, D is on as its file says: " + on);
        List<CheatSources.Source> now = CheatUpdates.sourcesOf(folder, key);
        check(now.size() == 3 && CheatUpdates.state(now.get(1)).get(CheatUpdates.PACK_PATH).equals("b.mncf") && CheatUpdates.previous(now.get(0)) != null, "B's new path, A's version before kept");
        check(CheatUpdates.look(h, new Answer(p2), 20).reason.equals("same"), "the same pack again: nothing");
        check(CheatUpdates.heldOf(folder, io.github.davdxpx.mistel.mncf.PackReader.read(p1, MncfBuild.TRUST)).key.equals(key), "a pack added by hand with its id: this one");
    }

    static void mncf(File root) throws Exception {
        File pk = pack(new File(root, "pack"), B, 0), po = pack(new File(root, "pack-orig"), B, 0);
        // MncfMain: the image with the original words back where the rewriter changed them
        byte[] img = Files.readAllBytes(new File(po, "main.patched.bin").toPath());
        byte[] orig = java.util.Arrays.copyOfRange(img, 0x1000, 0x1008);
        put(img, 0x1000, 0x14000000L); put(img, 0x1004, 0x14000001L);   // the rewriter's branches
        Files.write(new File(po, "main.patched.bin").toPath(), img);
        Files.write(new File(po, "main.changed.tsv").toPath(), u("# BUILD_ID\t" + B.toLowerCase() + "\n0x1000\t0x8\n"));
        Files.write(new File(po, "main.orig.bin").toPath(), orig);
        io.github.davdxpx.mistel.mncf.CodeModule main = MncfMain.of(po);
        check(main != null && main.image().length == 0x10000 && (main.image()[0x1000] & 0xFF) == 0x08 && main.exclude().isEmpty(), "the original words are back");
        check(main.sectionStart("code") == 0 && main.sectionEnd("code") == 0x8000 && main.sectionStart("data") == 0xC000 && main.sectionEnd("data") == 0x10000, "the sections");
        File noOrig = pack(new File(root, "pack-no-orig"), B, 0);
        Files.write(new File(noOrig, "main.changed.tsv").toPath(), u("# BUILD_ID\t" + B.toLowerCase() + "\n0x1000\t0x8\n"));
        check(MncfMain.of(noOrig).excluded(0x1000, 4) && !MncfMain.of(noOrig).excluded(0x2000, 4), "without orig.bin the changed runs stay out of every search");
        check(MncfMain.of(new File(root, "nothing")) == null, "no pack: no main");

        // A file of the player's: its cheats for this build, the spec's reasons, the code for the host
        File folder = new File(root, "folder");
        CheatSources.Source s = CheatSources.addMncf(folder, "hand", CheatSources.FROM_FILE, u(HAND), 1);
        check(java.util.Arrays.equals(Files.readAllBytes(s.file().toPath()), u(HAND)), "an .mncf is kept byte for byte");
        MncfBuild.Ready r = s.ready(game(B, pk));
        check(r.error == null && r.buildKey == null && r.signature.equals("unsigned") && r.signer == null && !r.verified(), "no build of its own, unsigned: loaded as any file, no mark");
        Map<String, MncfBuild.Cheat> by = new LinkedHashMap<>();
        for (MncfBuild.Cheat c : r.cheats) by.put(c.id, c);
        check(by.get("coins").reason == null && by.get("coins").lines.equals(List.of("04000000 00001000 B9004009")), "found by its pattern in the game's own main: " + by.get("coins").lines + " " + by.get("coins").reason);
        check(by.get("count").reason == null && by.get("count").lines.equals(List.of("04000000 00002000 528000A0")) && by.get("count").value != null, "a value in the instruction: " + by.get("count").lines + " " + by.get("count").reason);
        check(by.get("other").reason == null && by.get("other").lines.equals(List.of("04100000 00000100 00000001")), "a write into the heap: " + by.get("other").lines);
        check("capability-missing:display".equals(by.get("view").reason), "a monitor: Peanuts has no display yet: " + by.get("view").reason);
        check("runtime:unsupported:run.once".equals(by.get("once").reason), "run once: a part of the core Atmosphère code cannot say (section 11.1): " + by.get("once").reason);
        check(MncfWords.reason(by.get("once").reason, by.get("once")).equals("It runs once when you turn it on, which Peanuts cannot do yet."), MncfWords.reason(by.get("once").reason, null));
        check("pattern-not-found".equals(by.get("gone").reason), by.get("gone").reason);
        check(new File(s.dir, "build/" + B + ".mncf").isFile() && new File(s.dir, "build/" + B + ".tsv").isFile(), "kept for the build");
        String pinned = new String(Files.readAllBytes(new File(s.dir, "build/" + B + ".mncf").toPath()), StandardCharsets.UTF_8);
        check(pinned.contains("at = { module = \"main\", offset = 0x1000 }") && !pinned.contains("08 40 00 B9 23 01 00 94"), "the pinned file has the place as an offset:\n" + pinned);
        MncfBuild.Ready again = MncfBuild.kept(s.file(), game(B, null));
        check(again != null && again.cheats.size() == r.cheats.size() && again.cheats.get(0).lines.equals(r.cheats.get(0).lines), "a later start reads the kept one without main");
        check(MncfBuild.kept(s.file(), game(OTHER, null)) == null, "another build is made again");
        // made while the pack has no main yet (a game not built): kept, so the page does not look it up again and again; made again once main is there
        File early = new File(root, "early");
        CheatSources.Source es = CheatSources.addMncf(early, "hand", CheatSources.FROM_FILE, u(HAND), 1);
        MncfBuild.Ready e1 = es.ready(game(B, new File(root, "not-built-yet")));
        check("runtime:no-main".equals(e1.cheats.get(0).reason) && MncfBuild.kept(es.file(), game(B, new File(root, "not-built-yet"))) != null, "kept without main: " + e1.cheats.get(0).reason);
        check(MncfBuild.kept(es.file(), game(B, pk)) == null && es.ready(game(B, pk)).cheats.get(0).reason == null && MncfBuild.kept(es.file(), game(B, pk)) != null,
                "made again with the pack's main, its pattern found");

        // The page and the tab: kinds, words, the number field
        CheatFile.Parsed p = CheatFile.fromMncf(r, 0x8000);
        CheatFile.Cheat coins = cheat(p, "Coins by pattern"), count = cheat(p, "A count");
        check(coins.kind == CheatFile.Kind.CODE && coins.number == null && p.credits.equals(List.of("someone")), "a code cheat, its file's author as its credit");
        check(CheatFile.describe(coins).startsWith("Buying keeps your coins. Changes 1 place"), CheatFile.describe(coins));
        check(CheatFile.describe(cheat(p, "A monitor")).equals("It shows values from the game, which Peanuts cannot do yet. It stays off."), CheatFile.describe(cheat(p, "A monitor")));
        check(count.number != null && count.number.own() == 5 && count.number.min() == 1 && count.number.max() == 500 && "coins".equals(count.number.unit()), "the value's range and unit");
        check(CheatValues.lines(count, 300).equals(List.of("04000000 00002000 52802580")) && CheatValues.lines(count, 9999).equals(CheatValues.lines(count, 500)), "the number made into the instruction, kept inside its range");
        check(CheatFile.describe(count, 300).endsWith("Its number is 300 coins."), CheatFile.describe(count, 300));
        File vf = new File(root, "values");
        check(vf.mkdirs(), "mkdirs values");
        CheatValues.keep(vf, "A count", count, 42);
        Map<String, CheatValues.Kept> kv = CheatValues.read(vf);
        check(kv.get("a count").width == 0 && kv.get("a count").original == 5 && CheatValues.of(kv, "A count", count) == 42, "kept by its value, not by a place in the words");
        String mt = CheatSources.merge(List.of(new CheatSources.Block("hand", "hand", true, p)), kv).text;
        check(mt.contains("[A count]\n04000000 00002000 52800540\n"), "a start writes the kept number:\n" + mt);
        check(CheatValues.patch("[A count]\n04000000 00002000 528000A0\n[Next]\n", "A count", count, 7).equals("[A count]\n04000000 00002000 528000E0\n[Next]\n"), "the tab's patch of the merged file");

        // Relations (section 7.8): on a radio the other goes off; what it requires goes on
        CheatSources.Merged m = CheatSources.merge(List.of(new CheatSources.Block("hand", "hand", true, p)));
        Set<String> on = new java.util.LinkedHashSet<>(List.of("A count"));
        Set<String> changed = m.related(on, "The other one", true);
        on.add("The other one");
        check(changed.equals(Set.of("A count", "Coins by pattern")) && on.equals(Set.of("Coins by pattern", "The other one")), "radio and requires: " + changed + " " + on);

        // Another build: the pattern still finds the place, per-build offsets and raw code of other builds do not
        File pk2 = pack(new File(root, "pack2"), OTHER, 0x100);
        MncfBuild.Ready r2 = MncfBuild.of(s.file(), game(OTHER, pk2));
        check(r2.cheats.get(0).lines.equals(List.of("04000000 00001100 B9004009")), "the place moved with the code: " + r2.cheats.get(0).lines);
        File conv = new File(root, "conv");
        CheatSources.Source cs = CheatSources.add(conv, "cheatslips", CheatSources.FROM_ZIP, Map.of(B, "[Coins]\n040A0000 00001000 B9004009\n[Restore]\n040A0000 00001000 B9004008\n"), 1, known(pk, null), null);
        MncfBuild.Ready r3 = MncfBuild.of(cs.file(), game(OTHER, pk2));
        check(r3.cheats.size() == 1 && r3.cheats.get(0).lines.equals(List.of("04000000 00001100 B9004009")), "an Atmosphère cheat converted with the game's main works on the next build: " + r3.cheats.get(0).lines + " " + r3.cheats.get(0).reason);

        // A place the rewriter changed: refused before the game runs (runtime:rewritten)
        File pk3 = pack(new File(root, "pack3"), B, 0);
        Files.write(new File(pk3, "main.changed.tsv").toPath(), u("# BUILD_ID\t" + B.toLowerCase() + "\n0x2000\t0x4\n"));
        Files.write(new File(pk3, "main.orig.bin").toPath(), java.util.Arrays.copyOfRange(Files.readAllBytes(new File(pk3, "main.patched.bin").toPath()), 0x2000, 0x2004));
        File rw = new File(root, "rw");
        MncfBuild.Ready r4 = CheatSources.addMncf(rw, "hand", CheatSources.FROM_FILE, u(HAND), 1).ready(game(B, pk3));
        check("runtime:rewritten".equals(r4.cheats.get(1).reason) && r4.cheats.get(0).reason == null, "the count's place was rewritten: " + r4.cheats.get(1).reason);

        // A file Peanuts cannot read at all
        File bad = new File(root, "bad");
        MncfBuild.Ready r5 = CheatSources.addMncf(bad, "bad", CheatSources.FROM_FILE, u("format = \"mncf/1\"\nname = \"x\"\n"), 1).ready(game(B, pk));
        check(r5.error != null && r5.error.startsWith("schema") && r5.cheats.isEmpty(), "its error code: " + r5.error);
        CheatSources.Block bb = CheatSources.blocks(bad, null, true, game(B, pk), -1, true).get(0);
        check(bb.error != null && bb.parsed.cheats.isEmpty(), "its block says so");
        check(CheatSources.blocks(conv, null, true, game(B, pk), -1, false).get(0).pending, "not made ready for a build and not to be made now: pending");

        // Export: one source an .mncf with what is on as default = true and the set number as default; several an .mncp
        CheatSources.Merged em = CheatSources.merge(CheatSources.blocks(folder, null, true, game(B, pk), 0x8000, true));
        MncfExport.Out one = MncfExport.make(folder, "Example Game", em, Set.of("Coins by pattern"), kv);
        check(one != null && !one.pack && one.fileName.equals("Example Game cheats.mncf") && one.sources == 1, "one file: " + (one == null ? null : one.fileName));
        io.github.davdxpx.mistel.mncf.MncfFile ex = io.github.davdxpx.mistel.mncf.Mncf.load(one.bytes);
        check(ex.cheats().get(0).isDefault() && !ex.cheats().get(1).isDefault() && Long.valueOf(42).equals(ex.cheats().get(1).values().get("n").defaultValue()), "the switches and the number travel with it");
        check(java.util.Arrays.equals(MncfExport.make(folder, "Example Game", em, Set.of(), new LinkedHashMap<>()).bytes, u(HAND)), "nothing changed: its own bytes, a signature stays good");
        CheatSources.add(folder, "site", CheatSources.FROM_ZIP, Map.of(B, SITE), 2, known(pk, null), null);
        MncfExport.Out two = MncfExport.make(folder, "Example Game", CheatSources.merge(CheatSources.blocks(folder, null, true, game(B, pk), 0x8000, true)), Set.of(), kv);
        check(two.pack && two.fileName.endsWith(".mncp") && two.sources == 2, "two sources: a pack");
        io.github.davdxpx.mistel.mncf.Pack pack = io.github.davdxpx.mistel.mncf.PackReader.read(two.bytes, MncfBuild.TRUST);
        check(pack.usable() && pack.files().size() == 2 && pack.files().get(0).status().equals("ok") && pack.files().get(1).status().equals("ok"), "its manifest and hashes read back: " + pack.files());
        // The export back in: a pack's files, a bare .mncf, one in a zip
        List<String> paths = new ArrayList<>(), broken = new ArrayList<>();
        CheatImport.Found back = CheatImport.read(CheatImport.fromPack(two.bytes, paths, broken), paths, TID, BUILDS, "Example Game cheats.mncp");
        check(back.mncf.size() == 2 && broken.isEmpty() && back.forGame.isEmpty(), "the pack's two files: " + back.mncf);
        ByteArrayOutputStream to = new ByteArrayOutputStream();   // the same manifest, one file changed after it was packed
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(two.bytes)); ZipOutputStream zo2 = new ZipOutputStream(to)) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) {
                byte[] d = zi.readAllBytes();
                if (e.getName().equals(pack.files().get(1).path())) d = (new String(d, StandardCharsets.UTF_8) + "# changed\n").getBytes(StandardCharsets.UTF_8);
                zo2.putNextEntry(new ZipEntry(e.getName()));
                zo2.write(d);
                zo2.closeEntry();
            }
        }
        List<String> b2 = new ArrayList<>();
        List<CheatImport.In> left = CheatImport.fromPack(to.toByteArray(), new ArrayList<>(), b2);
        check(left.size() == 1 && b2.size() == 1 && b2.get(0).endsWith(": hash"), "a file changed after packing is left out: " + b2);
        paths.clear();
        CheatImport.Found bare = CheatImport.read(List.of(new CheatImport.In("Hand.mncf", u(HAND))), List.of("Hand.mncf"), TID, BUILDS, "Hand.mncf");
        check(bare.mncf.size() == 1 && bare.mncf.get(0).name().equals("Hand") && bare.mncf.get(0).cheats() == 6 && bare.cheats() == 6, "a bare .mncf");
        CheatImport.Found otherGame = CheatImport.read(List.of(new CheatImport.In("x.mncf", u(HAND.replace(TID, "0100BBBB0CCC0000")))), List.of("x.mncf"), TID, BUILDS, "x");
        check(otherGame.mncf.isEmpty() && otherGame.mncfOtherGame.size() == 1, "another game's");
        CheatImport.Found broken2 = CheatImport.read(List.of(new CheatImport.In("x.mncf", u("format = \"mncf/2\"\n"))), List.of("x.mncf"), TID, BUILDS, "x");
        check(broken2.mncfBroken.size() == 1 && "format".equals(broken2.mncfBroken.get(0).error), "a file of another version: " + broken2.mncfBroken);
        ByteArrayOutputStream zo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zo)) { z.putNextEntry(new ZipEntry("cheats/Hand.mncf")); z.write(u(HAND)); z.closeEntry(); }
        paths.clear();
        CheatImport.Found inZip = CheatImport.read(CheatImport.fromZip(new ByteArrayInputStream(zo.toByteArray()), paths), paths, TID, BUILDS, "hand.zip");
        check(inZip.mncf.size() == 1 && !inZip.pack, "an .mncf in a zip");
        check(MncfExport.fileName("A/B: C?", false).equals("A B C cheats.mncf"), MncfExport.fileName("A/B: C?", false));
        zip(root, folder, pk, kv);
        limits(root, pk);

        // What a file brings, for the Add dialog: counted from the file itself
        MncfFacts hf = MncfFacts.of(io.github.davdxpx.mistel.mncf.Mncf.load(u(HAND)));
        check(hf.cheats == 6 && hf.checked == 3 && hf.patterns == 3 && hf.values == 1 && hf.described == 1 && hf.rawOnly == 0 && hf.versions.isEmpty() && hf.signer == null,
                "the hand-made file: " + hf.cheats + " " + hf.checked + " " + hf.patterns + " " + hf.values + " " + hf.described);
        MncfFacts sf = MncfFacts.of(load(CheatSources.find(folder, "site").file()));
        check(sf.versions.equals(List.of("1.0.0")) && "1.0.0".equals(sf.versionWords()) && sf.rawOnly > 0 && sf.checked > 0, "a converted file: " + sf.versions + " raw " + sf.rawOnly);
    }

    /** The cheats a source's file made ready for the build can run, by name → their Atmosphère code */
    static Map<String, List<String>> runnable(File folder, MncfBuild.Game g) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (CheatSources.Source s : CheatSources.list(folder)) for (MncfBuild.Cheat c : s.ready(g).cheats) if (c.reason == null && !c.master) out.put(out.containsKey(c.name) ? c.name + " #2" : c.name, c.lines);
        return out;
    }

    /** Export as a ZIP for other emulators (folder: the hand-made file and the site's, kv: the count's number 42), then that ZIP added again */
    static void zip(File root, File folder, File pk, Map<String, CheatValues.Kept> kv) throws Exception {
        MncfBuild.Game g = game(B, pk);
        CheatSources.Merged m = CheatSources.merge(CheatSources.blocks(folder, null, true, g, 0x8000, true));
        MncfExport.Zip z = MncfExport.zip(folder, "Example Game", g, m, kv);
        check(z != null && z.fileName.equals("Example Game cheats.zip") && MncfExport.zipName("A/B").equals("A B cheats.zip"), "its name");
        Map<String, String> files = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(z.bytes))) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) files.put(e.getName(), new String(zi.readAllBytes(), StandardCharsets.UTF_8));
        }
        String path = "atmosphere/contents/" + TID + "/cheats/" + B + ".txt", t = files.get(path);
        check(files.keySet().equals(Set.of(path)) && z.builds.equals(Map.of(B, z.cheats)) && "1.0.0".equals(z.versions.get(B)), "Atmosphère's layout, one file a build: " + files.keySet() + " " + z.builds);
        Map<String, List<String>> before = runnable(folder, g);
        // what an Atmosphère file cannot hold, or what does not fit the game, is left out and named; everything else is in
        Map<String, String> left = new LinkedHashMap<>();
        for (String[] l : z.left) left.put(l[0], l[1]);
        check(left.keySet().equals(Set.of("A monitor", "Once", "Not in this version")), "left out: " + left);
        check(left.get("Once").equals("It runs once when you turn it on, which an Atmosphère file cannot say.") && left.get("A monitor").equals("It shows values from the game, which an Atmosphère file cannot do.")
                && left.get("Not in this version").equals("Its place was not found in this version of the game."), "and why, from the converter's stable reasons: " + left);
        // every cheat the file can run here, the raw one Peanuts' host does not run among them; and the pointer whose base lies past the made-up main, which
        // Peanuts refuses (outside) but whose own raw code for the build the converter writes as it is (spec section 15: it checks what it makes from a
        // cheat's operations, never a raw entry)
        check(z.cheats == before.size() + 1 && before.containsKey("6. Pause trick") && t.contains("[3. Coins as a number]"), "what goes in: " + z.cheats + " " + before.keySet());
        check(t.startsWith("{Setup}\n") && t.indexOf("{") == t.lastIndexOf("{"), "one master code, first:\n" + t);
        check(t.contains("[A count]\n04000000 00002000 52800540\n"), "the number set in the game's tab (42) in its instruction:\n" + t);
        check(t.contains("[Items]\n\n[1. Lots of coins]\n") && t.contains("[Extras]\n20000001\n") && t.contains("[6. Pause trick]\n") && !t.contains("SectionStart"),
                "the groups as the spec writes them (a name, then an entry of only 20000001 where a cheat in no group follows), the raw code as it came:\n" + t);
        // added again: the same cheats, the same code, for everything that went in
        List<String> paths = new ArrayList<>();
        CheatImport.Found back = CheatImport.read(CheatImport.fromZip(new ByteArrayInputStream(z.bytes), paths), paths, TID, BUILDS, z.fileName);
        check(back.forGame.size() == 1 && back.mncf.isEmpty(), "read back as an Atmosphère file of this game's build: " + back.forGame.size());
        File again = new File(root, "zip-again");
        for (Map.Entry<String, Map<String, String>> e : back.sources().entrySet()) CheatSources.add(again, e.getKey(), CheatSources.FROM_ZIP, e.getValue(), 1, known(pk, null), z.fileName);
        Map<String, List<String>> after = runnable(again, g);
        before.put("A count", List.of("04000000 00002000 52800540"));   // the number set, now the file's own
        for (Map.Entry<String, List<String>> e : before.entrySet()) check(e.getValue().equals(after.get(e.getKey())), "the same code for " + e.getKey() + ": " + e.getValue() + " " + after.get(e.getKey()));
        check(after.keySet().equals(before.keySet()), "the same cheats: " + after.keySet() + " " + before.keySet());
        MncfBuild.Ready ra = CheatSources.list(again).get(0).ready(g);
        for (MncfBuild.Cheat c : ra.cheats) if (c.name.equals("1. Lots of coins")) check(c.group.equals("Items"), "its group came back: " + c.group);
        // a file of two builds: each build's own file, the other build's cheats as their own code for it
        File two = new File(root, "zip-two");
        CheatSources.add(two, "both", CheatSources.FROM_ZIP, Map.of(B, "[Coins]\n04000000 00001000 B9004009\n", OTHER, SMALL), 1, known(pk, null), null);
        MncfExport.Zip z2 = MncfExport.zip(two, "Example Game", g, CheatSources.merge(CheatSources.blocks(two, null, true, g, 0x8000, true)), new LinkedHashMap<>());
        Map<String, String> f2 = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(z2.bytes))) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) f2.put(e.getName(), new String(zi.readAllBytes(), StandardCharsets.UTF_8));
        }
        // with the game's main every patch is checked before it is written, so the ZIP never writes over other code; without it a pattern cannot be placed
        String checked = String.join("\n", "format = \"mncf/1\"", "name = \"Checked\"", "", "[game]", "platform = \"switch\"", "id = \"" + TID + "\"", "",
                "[builds.\"1.0.0\"]", "modules = { main = \"" + B + "\" }", "",
                "[[cheat]]", "id = \"wrong\"", "name = \"Made for other code\"", "",
                "  [[cheat.patch]]", "  at = { module = \"main\", offset = 0x1000 }", "  expect = \"DE AD BE EF\"", "  bytes = \"1F 20 03 D5\"", "",
                "[[cheat]]", "id = \"found\"", "name = \"Found by its pattern\"", "",
                "  [[cheat.patch]]", "  at = { module = \"main\", find = \"08 40 00 B9 23 01 00 94\" }", "  expect = \"08 40 00 B9\"", "  bytes = \"09 40 00 B9\"", "",
                "[[cheat]]", "id = \"far\"", "name = \"Far pointer\"", "",
                "  [[cheat.write]]", "  at = { module = \"main\", offset = 0x200000, path = [0x10] }", "  type = \"u32\"", "  value = 1", "");
        File ck = new File(root, "zip-checked");
        CheatSources.addMncf(ck, "checked", CheatSources.FROM_FILE, u(checked), 1);
        MncfExport.Zip zc = MncfExport.zip(ck, "Example Game", g, null, new LinkedHashMap<>());
        String tc;
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zc.bytes))) { zi.getNextEntry(); tc = new String(zi.readAllBytes(), StandardCharsets.UTF_8); }
        Map<String, String> lc = new LinkedHashMap<>();
        for (String[] l : zc.left) lc.put(l[0], l[1]);
        check(tc.equals("[Found by its pattern]\n04000000 00001000 B9004009\n") && lc.size() == 2
                && "The game's code at its place is not what the cheat was made for: it is for another version.".equals(lc.get("Made for other code"))
                && "Its place lies outside the game.".equals(lc.get("Far pointer")), "checked against main, a pointer's base too: " + tc + " " + lc);
        MncfExport.Zip zn = MncfExport.zip(ck, "Example Game", game(B, null), null, new LinkedHashMap<>());
        check(zn.cheats == 2 && zn.left.size() == 1 && zn.left.get(0)[0].equals("Found by its pattern")
                && zn.left.get(0)[1].equals("Its place is found by the code around it, and Peanuts can look it up only in the version it has built."), "no main: " + zn.left.get(0)[1]);
        String other = "atmosphere/contents/" + TID + "/cheats/" + OTHER + ".txt";
        check(f2.keySet().equals(Set.of(path, other)) && f2.get(other).equals("[Coins]\n04000000 00001000 00000063\n") && z2.left.isEmpty()
                && new ArrayList<>(z2.builds.keySet()).equals(List.of(B, OTHER)), "both builds, the running one first: " + f2 + " " + z2.builds);
    }

    /** An .mncf of n cheats, each a write of its own place in main (the first in, of them, in group "Few"), and two masters ahead of them */
    static String many(int n, int in) {
        StringBuilder sb = new StringBuilder(String.join("\n", "format = \"mncf/1\"", "name = \"Many\"", "", "[game]", "platform = \"switch\"", "id = \"" + TID + "\"", "",
                "[builds.\"1.0.0\"]", "modules = { main = \"" + B + "\" }", "", ""));
        for (int m = 0; m < 2; m++) {
            sb.append("[[cheat]]\nid = \"setup").append(m).append("\"\nname = \"Setup ").append(m).append("\"\nmaster = true\n\n  [[cheat.write]]\n  at = { module = \"main\", offset = 0x")
                    .append(Integer.toHexString(0x8000 + 4 * m)).append(" }\n  type = \"u32\"\n  value = ").append(m + 1).append("\n\n");
        }
        for (int i = 0; i < n; i++) {
            sb.append("[[cheat]]\nid = \"c").append(i).append("\"\nname = \"Cheat ").append(i).append("\"\n").append(i < in ? "group = \"Few\"\n" : "")
                    .append("\n  [[cheat.write]]\n  at = { module = \"main\", offset = 0x").append(Integer.toHexString(0x1000 + 4 * i)).append(" }\n  type = \"u32\"\n  value = ").append(i).append("\n\n");
        }
        return sb.toString();
    }

    /** An Atmosphère file whose master writes words places from at, and two cheats: one on the master's first place (so it requires it), one apart */
    static String withMaster(String name, int at, int words) {
        StringBuilder sb = new StringBuilder("{" + name + " setup}\n");
        for (int i = 0; i < words / 3; i++) sb.append(String.format("04000000 %08X 00000001\n", at + 4 * i));
        sb.append(String.format("\n[%s on the setup]\n04000000 %08X 00000002\n\n[%s apart]\n04000000 %08X 00000003\n", name, at, name, at + 0x800));
        return sb.toString();
    }

    /** Atmosphère's limits (spec section 15) in the ZIP, across sources; Peanuts' own engine is not held to them */
    static void limits(File root, File pk) throws Exception {
        MncfBuild.Game g = game(B, pk);
        // more than 127 cheats: Peanuts runs them all, the ZIP has 127 and names the rest; group entries that would leave cheats out are not written
        File big = new File(root, "zip-many");
        CheatSources.addMncf(big, "many", CheatSources.FROM_FILE, u(many(130, 3)), 1);
        MncfBuild.Ready r = CheatSources.list(big).get(0).ready(g);
        int runs = 0, masters = 0;
        for (MncfBuild.Cheat c : r.cheats) {
            if (c.reason == null && !c.master) runs++;
            if (c.master && c.reason == null && c.lines.size() == 1) masters++;
        }
        check(runs == 130 && masters == 2, "Peanuts runs every cheat, each master with its own code: " + runs + " " + masters);
        MncfExport.Zip z = MncfExport.zip(big, "Example Game", g, null, new LinkedHashMap<>());
        String t;
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(z.bytes))) { zi.getNextEntry(); t = new String(zi.readAllBytes(), StandardCharsets.UTF_8); }
        check(z.cheats == 127 && z.left.size() == 3 && z.left.get(0)[0].equals("Cheat 127") && z.left.get(0)[1].equals("An Atmosphère file holds at most 127 cheats.")
                && !t.contains("[Few]") && t.startsWith("{Setup 0}\n04000000 00008000 00000001\n04000000 00008004 00000002\n"), "127 cheats, one master: " + z.cheats + " " + z.left.size());
        CheatSources.addMncf(new File(root, "zip-few"), "few", CheatSources.FROM_FILE, u(many(5, 3)), 1);
        MncfExport.Zip zf = MncfExport.zip(new File(root, "zip-few"), "Example Game", g, null, new LinkedHashMap<>());
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zf.bytes))) { zi.getNextEntry(); t = new String(zi.readAllBytes(), StandardCharsets.UTF_8); }
        check(t.contains("[Few]\n\n[Cheat 0]\n") && t.contains("[Few]\n20000001\n\n[Cheat 3]\n") && zf.cheats == 5, "where there is room, the groups:\n" + t);
        // the master codes of several sources are one entry of at most 256 words: a master past that stays out, with the cheats of its source that need it
        File two = new File(root, "zip-masters");
        CheatSources.add(two, "first", CheatSources.FROM_ZIP, Map.of(B, withMaster("First", 0x1000, 210)), 1, known(pk, null), null);
        CheatSources.add(two, "second", CheatSources.FROM_ZIP, Map.of(B, withMaster("Second", 0x4000, 210)), 1, known(pk, null), null);
        MncfExport.Zip zm = MncfExport.zip(two, "Example Game", g, null, new LinkedHashMap<>());
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zm.bytes))) { zi.getNextEntry(); t = new String(zi.readAllBytes(), StandardCharsets.UTF_8); }
        Map<String, String> left = new LinkedHashMap<>();
        for (String[] l : zm.left) left.put(l[0], l[1]);
        check(t.startsWith("{First setup}\n") && !t.contains("00004000 00000001") && t.contains("[Second apart]") && t.contains("[First on the setup]")
                && left.keySet().equals(Set.of("Second on the setup"))
                && left.get("Second on the setup").equals("It needs a code that is always on, and that code does not fit in one Atmosphère file beside the others."),
                "one master of at most 256 words: " + left + "\n" + t.substring(0, Math.min(t.length(), 60)));
        // a file of 64 KB or more Atmosphère refuses: the cheats that would make it that big stay out; a name keeps the 63 bytes Atmosphère keeps
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            huge.append(i == 0 ? "[" + "A very long name ".repeat(5).trim() + "]\n" : "[Long " + i + "]\n");
            for (int k = 0; k < 67; k++) huge.append(String.format("04000000 %08X 00000001\n", 0x1000 + 4 * (67 * i + k)));
            huge.append('\n');
        }
        File hf = new File(root, "zip-huge");
        CheatSources.add(hf, "huge", CheatSources.FROM_ZIP, Map.of(B, huge.toString()), 1, known(pk, null), null);
        MncfExport.Zip zh = MncfExport.zip(hf, "Example Game", g, null, new LinkedHashMap<>());
        byte[] file;
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zh.bytes))) { zi.getNextEntry(); file = zi.readAllBytes(); }
        t = new String(file, StandardCharsets.UTF_8);
        boolean allBig = !zh.left.isEmpty();
        for (String[] l : zh.left) allBig &= l[1].equals("An Atmosphère file can be at most 64 KB, and the cheats before this one fill it.");
        String first = t.substring(1, t.indexOf(']'));
        check(file.length < 0x10000 && zh.cheats + zh.left.size() == 40 && zh.cheats > 30 && allBig && zh.left.get(zh.left.size() - 1)[0].equals("Long 39"),
                "under 64 KB: " + file.length + " " + zh.cheats + " " + zh.left.size());
        check(first.getBytes(StandardCharsets.UTF_8).length <= 63 && first.startsWith("A very long name A very long name"), "a name of at most 63 bytes: " + first);
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
