package dev.nxnative.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Cheats on the JVM, driven by dm/tools/tests/test_android_cheats.py. args[0]: an empty folder; args[1]: Ocarina of Time's profile cheat file
 * (dm/games/0400486024DEC000/cheats/E0F3CCFAF50065AE.txt). Prints "ok" or throws. Covers the parse (the host's rules), the rule that the switch off passes nothing
 * and lists nothing, the import's build gate (refused files store nothing), the merge's naming, the merged folder a start writes, removing, and the export / import zip.
 */
public final class CheatsJvmTest {
    static final String B = "E0F3CCFAF50065AE", OTHER = "1111111111111111", TID = "0400486024DEC000";
    static final Set<String> BUILDS = Set.of(B);

    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static byte[] u(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static List<String> keys(List<Cheats.Entry> l) { List<String> o = new ArrayList<>(); for (Cheats.Entry e : l) if (e.toggles()) o.add(e.key); return o; }

    public static void main(String[] a) throws Exception {
        File root = new File(a[0]);
        String oot = read(new File(a[1]));

        // ---- the parse, as the host's: labels and sections, masters, duplicates, a header with code on its line ----
        List<Cheats.Entry> l = Cheats.parse(oot);
        List<String> k = keys(l);
        check(k.size() == 12 && k.get(0).equals("Infinite Rupees (999)") && k.contains("Infinite Health (experimental)") && k.contains("Gold Skulltula Tokens (100)"), "OoT's cheats: " + k);
        check(l.get(0).label && !l.get(0).toggles(), "the header lines are labels");
        for (Cheats.Entry e : l) if (e.key.startsWith("Infinite Arrows")) check(e.section.equals("Ammo"), "Arrows in Ammo: " + e.section);
        List<Cheats.Entry> d = Cheats.parse("{M}\n04000000 00000010 00000001\n[A]\n04000000 00000010 00000001\n[A] 04000000 00000010 00000002\n[Credit]\n[P]\nnot code\n");
        check(keys(d).equals(List.of("A", "A #2")), "duplicates keyed as the host does, the master, a label and a prose-only entry not listed: " + keys(d));
        check(d.get(0).master && !d.get(0).toggles(), "the master is never a toggle");

        // ---- the switch: off passes nothing and lists nothing ----
        File folder = new File(root, "cheats/" + TID);
        check(Cheats.env(false, true, folder).isEmpty() && Cheats.panel(false, true, l).isEmpty(), "switch off: no DM_CHEATS, no panel rows");
        check(Cheats.env(true, false, folder).isEmpty() && Cheats.panel(true, false, l).isEmpty(), "a game without cheats: nothing");
        check(Cheats.env(true, true, folder).equals(List.of("DM_CHEATS=" + folder.getPath())) && Cheats.panel(true, true, l).size() == 12, "switch on: the folder and the rows");
        check(Cheats.decodeOn(Cheats.encodeOn(new LinkedHashSet<>(List.of("x", "y #2")))).equals(Set.of("x", "y #2")) && Cheats.decodeOn("").isEmpty(), "toggles in a preference");

        // ---- the build gate ----
        check(Cheats.builds16("458752 main=e0f3ccfaf50065ae4ea01eb5aeb13b1a subsdk0=aa").equals(Set.of(B)), "main build of a builds column");
        check(B.equals(Cheats.buildOf("[X]\n", "e0f3ccfaf50065ae.txt")) && B.equals(Cheats.buildOf("\nbuild e0f3ccfaf50065ae4ea01eb5aeb13b1a\n[X]\n", "mine.txt")), "build by name, by build line");
        String one = "[Max Rupees]\n580F0000 04E842A8\n580F1000 000000D8\n780F0000 0000966C\n640F0000 00000000 000003E7\n";
        String why = Cheats.checkImport(u(one), OTHER + ".txt", BUILDS);
        check(why != null && why.startsWith("It is for build " + OTHER), "another build's file refused: " + why);
        check(Cheats.checkImport(u(one), "mine.txt", BUILDS).startsWith("It does not say which build"), "no build named: refused");
        check(Cheats.checkImport(u("build " + B + "\n[Only a label]\n"), "x.txt", BUILDS).startsWith("It holds no cheat"), "no cheat: refused");
        check(Cheats.checkImport(u(one), B + ".txt", Set.of()).contains("Prepare"), "no known build: refused");
        try { Cheats.importFile(folder, u(one), OTHER + ".txt", BUILDS); check(false, "imported another build"); } catch (IOException e) { check(e.getMessage().startsWith("It is for build"), e.getMessage()); }
        check(Cheats.importedFiles(folder).isEmpty() && !new File(folder, "imported").exists(), "a refused import stores nothing");

        // ---- importing, the merge's naming ----
        File k1 = Cheats.importFile(folder, u("Some site's header line\n" + "[Infinite Rupees (999)]\n580F0000 04E842A8\n580F1000 000000D8\n780F0000 0000966C\n640F0000 00000000 000001F4\n" + one), B + ".txt", BUILDS);
        check(k1.getName().equals(B + ".txt") && read(k1).startsWith("build " + B + "\n"), "kept with its build line: " + k1.getName());
        File k2 = Cheats.importFile(folder, u("build " + B + "\n[--SectionStart:Mine--]\n[Max Rupees]\n04000000 00000010 00000001\n[--SectionEnd:Mine--]\n"), "my cheats?.txt", BUILDS);
        check(k2.getName().equals("my cheats_.txt"), "a kept name of safe characters: " + k2.getName());
        String merged = Cheats.merge(oot, Cheats.importedOf(folder, B));
        List<String> mk = keys(Cheats.parse(merged));
        check(mk.subList(0, 12).equals(k), "the profile's cheats first, their keys unchanged");
        check(mk.subList(12, mk.size()).equals(List.of("Infinite Rupees (999) (imported)", "Max Rupees", "Max Rupees (imported)")), "imported names, clashes suffixed: " + mk);
        check(!merged.contains("Some site's header") && !merged.contains("build " + B) && !merged.contains("SectionStart:Mine"), "the build line, prose and the file's own sections left out");
        for (Cheats.Entry e : Cheats.parse(merged)) if (e.key.equals("Max Rupees (imported)")) check(e.imported() && e.section.equals("Imported: my cheats_"), "section of the file: " + e.section);
        check(Cheats.merge(oot, List.of()).equals(oot), "no import: the profile's file as it is");

        // ---- a start's folder ----
        Files.write(new File(folder, OTHER + ".txt").toPath(), u(one));   // from before an update
        File packDir = new File(root, "pack"); new File(packDir, "cheats").mkdirs();
        Files.write(new File(packDir, "cheats/" + B + ".txt").toPath(), u(oot));
        check(Cheats.packFile(packDir, B) != null && Cheats.packFile(packDir, OTHER) == null, "the pack's file of the build");
        List<Cheats.Entry> inst = Cheats.install(Cheats.packFile(packDir, B), folder, B, Set.of("Infinite Bombs (99)", "Max Rupees (imported)", "Gone"));
        check(inst != null && keys(inst).equals(mk), "the start's list is the merge's");
        check(read(new File(folder, B + ".txt")).equals(merged) && read(new File(folder, B + ".enabled")).equals("Infinite Bombs (99)\nMax Rupees (imported)\n"), "merged file and its .enabled");
        check(!new File(folder, OTHER + ".txt").exists(), "another build's file in the folder goes");
        check(Cheats.install(null, new File(root, "empty"), B, Set.of()) == null, "nothing to load: null");

        // ---- removing ----
        check(!Cheats.remove(folder, "../" + B + ".txt") && !Cheats.remove(folder, B + ".enabled"), "only an imported file is removed");

        // ---- export / import zip ----
        ByteArrayOutputStream zo = new ByteArrayOutputStream();
        Cheats.exportZip(zo, TID, B, folder, true, new LinkedHashSet<>(List.of("Max Rupees (imported)", "Infinite Bombs (99)")));
        File other = new File(root, "other/" + TID);
        Cheats.ZipContent c = Cheats.readZip(new ByteArrayInputStream(zo.toByteArray()), TID, BUILDS);
        check(c.files.size() == 2 && c.master && c.on.equals(Set.of("Max Rupees (imported)", "Infinite Bombs (99)")), "zip read: " + c.files.keySet() + " " + c.on);
        check(Cheats.takeZip(other, c, BUILDS) == 2, "two files taken");
        check(Cheats.merge(oot, Cheats.importedOf(other, B)).equals(merged), "the round trip merges the same");
        check(Cheats.takeZip(other, c, BUILDS) == 0 && Cheats.importedFiles(other).size() == 2, "the same zip again adds nothing");
        try { Cheats.readZip(new ByteArrayInputStream(zo.toByteArray()), "0100000000000000", BUILDS); check(false, "another game's zip"); }
        catch (IOException e) { check(e.getMessage().contains("another game"), e.getMessage()); }
        try { Cheats.readZip(new ByteArrayInputStream(zo.toByteArray()), TID, Set.of(OTHER)); check(false, "another build's zip"); }
        catch (IOException e) { check(e.getMessage().contains("It is for build " + B), e.getMessage()); }
        for (String bad : new String[] {"saves/0/slot0.bin", "cheats/../x.txt", "readme.txt"}) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(bo)) { z.putNextEntry(new ZipEntry(bad)); z.write(u("build " + B + "\n" + one)); z.closeEntry(); }
            try { Cheats.readZip(new ByteArrayInputStream(bo.toByteArray()), TID, BUILDS); check(false, "took " + bad); }
            catch (IOException e) { check(e.getMessage().startsWith("Not a cheats export"), bad + ": " + e.getMessage()); }
        }
        check(Cheats.remove(folder, k2.getName()) && Cheats.importedFiles(folder).size() == 1, "an imported file removed");
        System.out.println("ok");
    }
}
