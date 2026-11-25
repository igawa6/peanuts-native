// PEANUTS begin: Peanuts' own file, down to the marker on its last line. Import pack… before the first build (#44) on the JVM with upstream's own PackZip, Pack, BuildCheck and SaveStore (peanuts/tests/test_first_import.py compiles and runs it)
package dev.peanuts.pack;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Import pack… before the first build (GitHub issue #44) on the JVM: run by peanuts/tests/test_first_import.py, which compiles this with FirstImport,
 * PackImport and its Upstream, upstream's own PackZip, Pack, BuildCheck, SaveStore, Profile (and what they need), and stand-ins for the upstream classes
 * that need Android (Game, GameList, PackUi, SaveUi, Folders, Prefs, Job, DumpCheck, with the parameters upstream's have; the test checks those
 * against upstream's sources) and for android.content.Context and android.util.Log. The profile is the stub BuildConfig's Donkey Kong Bananza
 * (040074A01BF12000, build 458752, main=aa…), the library chain "c0ffee".
 * <p>
 * {@code <scratch folder> <.nup of the pack's build> <.nup of another build>} (both made by the Python test with dm/tools/nup_pack.py): every case; prints "ok &lt;n&gt;" and exits 0, or the first failure and exits 1.
 */
public final class FirstImportJvmTest {
    static final String ID = "040074A01BF12000", MKW = "0400C3F00006E000";
    static final long V = 458752;
    static final Context C = new Context() {};
    static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static void write(File f, byte[] b) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), b);
    }

    static void write(File f, String s) throws IOException { write(f, s.getBytes(StandardCharsets.UTF_8)); }

    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    /** An NSO-like file: NSO0 and a build ID at 0x40 (byte i = seed * 31 + i); seed 22 starts with aa, the profile's build */
    static byte[] nso(int seed) {
        byte[] b = new byte[0x200];
        b[0] = 'N'; b[1] = 'S'; b[2] = 'O'; b[3] = '0';
        for (int i = 0; i < 32; i++) b[0x40 + i] = (byte) (seed * 31 + i);
        return b;
    }

    /** The first 16 bytes of nso(seed)'s build ID in hex, as pack_android.py writes a build row */
    static String buildId(int seed) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) s.append(String.format("%02x", (seed * 31 + i) & 0xff));
        return s.toString();
    }

    /** A pack folder like pack_android.py's: pack.tsv (title, version, the main build row), a finished module, a library of the APK's version */
    static File pack(File dir, String id, long version, String tag) throws IOException {
        write(new File(dir, "pack.tsv"), "format\t1\ntarget\tandroid\ntitle_id\t" + id + "\nname\tDonkey Kong Bananza\nversion\t" + version + "\nromfs\tupdates/" + version + "/romfs\n"
                + "modules\tmain\nshaders\tshaders\nbuild\tmain=" + buildId(22) + "\n");
        write(new File(dir, "main.patched.bin"), "code " + tag);
        write(new File(dir, "main.android.key"), "key");
        write(new File(dir, "shaders/v-c0ffee/dmsh_vk.lib"), "library " + tag);
        write(new File(dir, "shaders/v-c0ffee/inputs.tsv"), "inputs\tabcdef012345\n");
        return dir;
    }

    /** A dump folder of update 458752 whose main has nso(seed)'s build */
    static File dump(File dir, int seed) throws IOException {
        write(new File(dir, "updates/" + V + "/exefs/main"), nso(seed));
        write(new File(dir, "updates/" + V + "/exefs/main.npdm"), "npdm");
        write(new File(dir, "updates/" + V + "/romfs/data.bin"), "romfs");
        return dir;
    }

    static FirstImport.Game game(File file, long version, File target, File buildFile) {
        Map<Long, String> builds = new HashMap<>();
        builds.put(V, "1.0.1");
        return new FirstImport.Game(ID, "Donkey Kong Bananza", file, version, new HashSet<>(Arrays.asList(V)), builds, target, buildFile);
    }

    static final PackImport.Titles TITLES = new PackImport.Titles() {
        @Override public String name(String id) { return id.equals(MKW) ? "Mario Kart World" : id.equals(ID) ? "Donkey Kong Bananza" : null; }
        @Override public FirstImport.Other where(String id) { return id.equals(MKW) ? FirstImport.Other.TO_BUILD : FirstImport.Other.NOT_LISTED; }
    };

    // ---- the stand-ins' state (their fields are public: they are this test's) ----

    static Class<?> k(String n) throws ClassNotFoundException { return Class.forName("dev.nxnative.android." + n); }

    static void set(String cls, String field, Object v) throws ReflectiveOperationException {
        Field f = k(cls).getField(field);
        f.set(null, v);
    }

    @SuppressWarnings("unchecked")
    static <T> T get(String cls, String field) throws ReflectiveOperationException { return (T) k(cls).getField(field).get(null); }

    static void reset() throws ReflectiveOperationException {
        set("PackUi", "BUSY", false);
        set("SaveUi", "RUNNING", false);
        set("Job", "REMEMBERED", null);
        ((List<?>) get("Game", "LIST")).clear();
        ((Map<?, ?>) get("Folders", "PICKED")).clear();
        ((Map<?, ?>) get("Prefs", "FOLDERS")).clear();
    }

    static void listGame(String gameDir, String packDir) throws ReflectiveOperationException {
        Method put = k("Game").getDeclaredMethod("put", Context.class, k("Game"));
        put.setAccessible(true);
        java.lang.reflect.Constructor<?> c = k("Game").getDeclaredConstructor(String.class, String.class, String.class, String.class);
        c.setAccessible(true);
        put.invoke(null, C, c.newInstance(ID, "Donkey Kong Bananza", gameDir, packDir));
    }

    static List<?> listed() throws ReflectiveOperationException { return get("Game", "LIST"); }

    static String field(Object o, String f) throws ReflectiveOperationException {
        Field x = o.getClass().getDeclaredField(f);
        x.setAccessible(true);
        return String.valueOf(x.get(o));
    }

    /** An import as PackImportUi.start makes it: the title taken (on its main thread, with its last check), then PackImport.run, which gives it back */
    static PackImport.Done imp(FirstImport.Game g, File source, PackImport.Titles titles) throws PackImport.Refused {
        if (!PackImport.take(g.titleId)) throw new AssertionError("taken already: " + g.titleId);
        return PackImport.run(C, g, source, titles);
    }

    interface Run { PackImport.Done run() throws PackImport.Refused; }

    static PackImport.Refused refused(Run r) {
        try {
            r.run();
            return null;
        } catch (PackImport.Refused e) {
            return e;
        }
    }

    static boolean leftovers(File parent) {
        String[] l = parent.list();
        if (l != null) for (String s : l) if (s.endsWith(".partial") || s.endsWith(".replaced")) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        File t = new File(args[0]);
        words();
        nspFolder(new File(t, "nsp-folder"));
        nspZipIntoBuildFolder(new File(t, "nsp-zip"));
        nspVersion(new File(t, "nsp-version"));
        otherTitle(new File(t, "title"));
        busyKey(new File(t, "busy"));
        whoElse(new File(t, "who"));
        stoppedJob(new File(t, "job"));
        pickedPack(new File(t, "picked"));
        dumpFits(new File(t, "dump-fits"));
        dumpOtherBuild(new File(t, "dump-other"));
        damaged(new File(t, "damaged"));
        swapAside(new File(t, "aside"));
        handOver(new File(t, "hand-over"), new File(args[1]), new File(args[2]));
        System.out.println("ok " + n);
    }

    // ---- FirstImport: the rules and the words ----

    static void words() {
        Map<Long, String> builds = new HashMap<>();
        builds.put(0L, "1.0.0");
        builds.put(V, "1.0.1");
        check(FirstImport.label(0, builds).equals("the base game (1.0.0)") && FirstImport.label(0, new HashMap<>()).equals("the base game"), "the base's label");
        check(FirstImport.label(V, builds).equals("update 1.0.1") && FirstImport.label(196608, builds).equals("update v3") && FirstImport.label(65537, builds).equals("update v65537"), "an update's label");
        check(FirstImport.parse("458752") == V && FirstImport.parse(" 0 ") == 0 && FirstImport.parse("") == -1 && FirstImport.parse(null) == -1 && FirstImport.parse("1.0") == -1
                && FirstImport.parse("-3") == -1, "pack.tsv versions");
        File target = new File("/x/packs/" + ID);
        FirstImport.Game nsp = new FirstImport.Game(ID.toLowerCase(), "", null, V, new HashSet<>(Arrays.asList(V, 196608L)), builds, target, null);
        check(nsp.titleId.equals(ID) && nsp.name.equals(ID), "the title upper case, the ID as the name when there is none");
        check(FirstImport.versionProblem(nsp, "458752") == null && FirstImport.versionProblem(nsp, "") == null && FirstImport.versionProblem(nsp, "x") == null, "the version Build uses, or none to tell: in");
        String other = FirstImport.versionProblem(nsp, "196608");
        check(other != null && other.startsWith("That pack was made from update v3 of " + ID + ", and Build uses update 1.0.1 here. Choose update v3 under Update"), "an update that was found: choose it: " + other);
        String unknown = FirstImport.versionProblem(nsp, "262144");
        check(unknown != null && unknown.endsWith("Import a pack made from update 1.0.1, or add the file of update v4 to your update folder."), "an update not found: " + unknown);
        String base = FirstImport.versionProblem(nsp, "0");
        check(base != null && base.endsWith("and Build uses update 1.0.1 here. Import a pack made from update 1.0.1."), "a pack of the base while an update is chosen: " + base);
        FirstImport.Game withFile = new FirstImport.Game(ID, "DK", new File("/g/dk.nup"), V, null, builds, target, null);
        check(FirstImport.versionProblem(withFile, "0") == null, "a game file: its build decides, not the number");
        check(FirstImport.otherBuild(withFile, "0").equals("That pack was made from the base game (1.0.0) of DK, another build than the game file here. Import a pack made from this game file, or use Build."), "another build");
        check(FirstImport.otherBuild(withFile, "?").startsWith("That pack was made from another build of DK than the game file here."), "another build, no version");
        check(FirstImport.otherTitle(withFile, MKW, "Mario Kart World", FirstImport.Other.TO_BUILD).equals("That pack is for Mario Kart World, not DK. Import it from Mario Kart World's sheet."), "another game to build");
        check(FirstImport.otherTitle(withFile, MKW, "Mario Kart World", FirstImport.Other.BUILT).equals("That pack is for Mario Kart World, not DK. Import it on Mario Kart World's page, on its Storage tab."), "another game that is built");
        check(FirstImport.otherTitle(withFile, MKW, "Mario Kart World", FirstImport.Other.NOT_LISTED).endsWith("Add Mario Kart World's game file to your game folder, then import the pack from its sheet."), "another game not in the library");
        check(FirstImport.otherTitle(withFile, "0100000000001000", null, FirstImport.Other.NOT_LISTED).equals("That pack is for a game this app version does not include (0100000000001000), not DK."), "a game this app does not have");
        check(FirstImport.refusal("the zip has no pack.tsv").equals("The zip has no pack.tsv. Nothing was changed.") && FirstImport.refusal("Done.").equals("Done. Nothing was changed.")
                && FirstImport.refusal(null).equals("The pack could not be read. Nothing was changed."), "a refusal's form");
        check(FirstImport.fits(100, 100 + FirstImport.SPARE) && !FirstImport.fits(101, 100 + FirstImport.SPARE), "the spare room");
        check(FirstImport.lowSpace(0, 1 << 20).equals("The pack needs 256.0 MB free, and its folder's storage has 1.0 MB."), "low space: " + FirstImport.lowSpace(0, 1 << 20));
        check(FirstImport.size(512).equals("512 bytes") && FirstImport.size(3L << 30).equals("3.00 GB"), "sizes");
        check(FirstImport.lists(withFile, null, false) && FirstImport.lists(withFile, "old", true) && !FirstImport.lists(withFile, "none", false) && !FirstImport.lists(nsp, null, false), "which games get a list entry");
        check(FirstImport.imported(withFile, true, null, false).endsWith("ready to play."), "listed, this app's shaders");
        check(FirstImport.imported(nsp, false, null, false).equals("Build makes the game's .nup from your files and uses this pack, so its shaders are not made again."), "an NSP: Build takes it");
        check(FirstImport.imported(nsp, false, "Old.", true).endsWith("Build brings them up to date.") && FirstImport.imported(nsp, false, "Odd.", false).endsWith("Odd. Build makes them again for this app."), "shaders of another version");
        File nup = new File("/g/dk.nup"), build = new File("/b/" + ID), home = new File("/games/DK");
        check(FirstImport.buildFile(nup, build, home, ID) == nup, "a game file: Build prepares it where it is");
        check(FirstImport.buildFile(null, build, home, ID).getParentFile().equals(build), "an NSP with a build folder: the .nup and its job go there (BuildService.stepImport)");
        check(FirstImport.buildFile(null, null, home, ID).equals(new File(home, "DK.nup")), "an NSP without one: beside the game (GameSheet.state)");
        check(FirstImport.buildFile(null, null, null, ID) == null, "no folder for it at all");
        check(FirstImport.replaces(nsp).endsWith("if it is refused, the one there stays."), "the replace question");
        FirstImport.Pick asked = new FirstImport.Pick(ID.toLowerCase(), target), none = new FirstImport.Pick(ID, null);
        check(asked.titleId.equals(ID) && asked.asked(target) && !asked.asked(new File("/x/builds/" + ID + "/pack")) && !none.asked(target),
                "the pick: asked about this pack folder only, none when no pack was there");
        FirstImport.Pick kept = FirstImport.Pick.of(asked.text()), keptNone = FirstImport.Pick.of(none.text());
        check(kept != null && kept.titleId.equals(ID) && kept.asked(target) && keptNone != null && keptNone.titleId.equals(ID) && keptNone.replaces == null,
                "the pick as kept while the picker is open, and read back by a new process: " + asked.text());
        check(FirstImport.Pick.of(null) == null && FirstImport.Pick.of("") == null && FirstImport.Pick.of("0100\t/x") == null && FirstImport.Pick.of(ID) == null
                && FirstImport.Pick.of("040074A01BF1200G\t") == null, "no pick from none or from a text that is not one");
        check(FirstImport.swapFailed(nsp).contains("could not be put back") && !FirstImport.swapFailed(nsp).contains("Nothing was changed"), "a failed swap never says nothing changed");
        for (String s : new String[] {other, unknown, base, FirstImport.stoppedBuild(nsp, false), FirstImport.inUse(nsp), FirstImport.imported(withFile, true, "x", true), FirstImport.running(nsp),
                FirstImport.replaces(nsp), FirstImport.swapFailed(nsp)})
            check(s.indexOf('·') < 0 && s.indexOf('—') < 0 && s.indexOf('–') < 0 && !s.contains("!"), "UI-RULES: no middle dot, no dashes, no exclamation: " + s);
    }

    // ---- PackImport: the whole import, with upstream's PackZip ----

    static void nspFolder(File t) throws Exception {
        reset();
        File src = pack(new File(t, "pc/" + ID + "-work/android-pack"), ID, V, "new"), target = new File(t, "app/packs/" + ID);
        pack(target, ID, V, "old");
        PackImport.Done d = imp(game(null, V, target, new File(t, "games/DK/DK.nup")), src, TITLES);
        check(!d.listed && d.text.startsWith("Build makes the game's .nup"), "an NSP game: in, not listed: " + d.text);
        check(read(new File(target, "main.patched.bin")).equals("code new") && read(new File(target, "shaders/v-c0ffee/dmsh_vk.lib")).equals("library new"), "the new pack replaced the old");
        check(!leftovers(target.getParentFile()), "no .partial or .replaced left");
        check(read(new File(src, "main.patched.bin")).equals("code new"), "the source is only read");
        check(listed().isEmpty(), "no list entry for a game with no .nup yet");
        check(d.detail.contains("Pack folder: " + target), "the details name the pack folder");
        check(!PackImport.busy(ID), "busy again after it");
    }

    static void nspZipIntoBuildFolder(File t) throws Exception {
        reset();
        File src = pack(new File(t, "src"), ID, V, "zip");
        Method export = k("PackZip").getDeclaredMethod("export", File.class, File.class);
        export.setAccessible(true);
        File zip = (File) export.invoke(null, src, new File(t, "out"));
        File target = new File(t, "build/" + ID + "/pack");   // a build folder's <title ID>/pack: not there yet
        PackImport.Done d = imp(game(null, V, target, null), zip, TITLES);
        check(!d.listed && read(new File(target, "main.patched.bin")).equals("code zip"), "a zip into a build folder's pack folder");
        check(!leftovers(target.getParentFile()) && !new File(t, "build/" + ID + "/" + ID + ".partial").exists(), "staged beside the pack folder, nothing left");
    }

    static void nspVersion(File t) throws Exception {
        reset();
        File src = pack(new File(t, "src"), ID, V, "new"), target = new File(t, "app/packs/" + ID);
        pack(target, ID, V, "old");
        PackImport.Refused r = refused(() -> imp(game(null, 0, target, null), src, TITLES));
        check(r != null && r.getMessage().startsWith("That pack was made from update 1.0.1 of Donkey Kong Bananza, and Build uses the base game here.")
                && r.getMessage().endsWith("Nothing was changed."), "a pack of another version than Build uses: " + (r == null ? null : r.getMessage()));
        check(read(new File(target, "main.patched.bin")).equals("code old") && !leftovers(target.getParentFile()), "the old pack as it was, nothing left");
        check(r.detail.contains("The pack's version: " + V), "the details: the pack's version");
    }

    /**
     * The pack Build's Prepare takes for a game file when no Build destination is set (its step 3: Folders.packFor, whose search is upstream's Folders.find):
     * PackFind.candidates with the app's packs/&lt;id&gt;, the build folder's &lt;id&gt;/pack (PEANUTS in Folders.find), and PackFind.best judged by
     * BuildCheck.build, as PackCheck.blocking ends for a pack that is finished and not being rewritten. null: none, Build makes its own
     */
    static File chosen(File app, File buildPack, File game) throws ReflectiveOperationException {
        Class<?> judge = k("PackFind$Judge"), pack = k("Pack");
        Method candidates = k("PackFind").getDeclaredMethod("candidates", File.class, File.class, File.class, String.class, File.class),
                root = k("PackFind").getDeclaredMethod("volumeRoot", File.class), best = k("PackFind").getDeclaredMethod("best", List.class, String.class, boolean.class, judge),
                build = k("BuildCheck").getDeclaredMethod("build", pack, File.class), isDump = pack.getDeclaredMethod("isDump", File.class);
        for (Method m : new Method[] {candidates, root, best, build, isDump}) m.setAccessible(true);
        @SuppressWarnings("unchecked") List<File> c = new ArrayList<>((List<File>) candidates.invoke(null, null, app, game, ID, root.invoke(null, game)));
        if (!c.contains(buildPack) && new File(buildPack, "pack.tsv").isFile()) c.add(buildPack);
        Object j = Proxy.newProxyInstance(judge.getClassLoader(), new Class<?>[] {judge}, (o, m, a) -> {   // PackFind.Judge.code: BuildCheck's message, null when it fits
            Object r = build.invoke(null, a[0], game);
            Field msg = r.getClass().getDeclaredField("message");
            msg.setAccessible(true);
            return msg.get(r);
        });
        Object f = best.invoke(null, c, ID, isDump.invoke(null, game), j);
        if (f == null) return null;
        Field dir = f.getClass().getDeclaredField("dir");
        dir.setAccessible(true);
        return (File) dir.get(f);
    }

    /**
     * After the import, Build makes the .nup of an NSP game where FirstImport.buildFile says and looks for a pack: it takes the imported one (its code fits the
     * .nup, its library is this APK's: nothing to prepare, the shaders are not made again), in the app's packs/&lt;id&gt; and in a build folder's &lt;id&gt;/pack.
     * A .nup of another build gets no pack (Build makes its own, the imported one is never started with it)
     */
    static void handOver(File t, File fits, File other) throws Exception {
        reset();
        File app = new File(t, "app/packs/" + ID), builds = new File(t, "builds/" + ID), home = new File(t, "games/DK");
        // the app's packs/<id>, no build folder: the .nup goes beside the game
        File nup = FirstImport.buildFile(null, null, home, ID);
        PackImport.Done d = imp(game(null, V, app, nup), pack(new File(t, "src"), ID, V, "pc"), TITLES);
        check(!d.listed, "an NSP game: imported, not listed");
        Files.copy(fits.toPath(), mkdirs(nup).toPath());
        check(app.equals(chosen(app, new File(builds, "pack"), nup)), "Build takes the imported pack in the app's storage for the .nup it made: " + chosen(app, new File(builds, "pack"), nup));
        Files.copy(other.toPath(), nup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check(chosen(app, new File(builds, "pack"), nup) == null, "a .nup of another build: the imported pack is not taken");
        // a build folder: the pack in <build folder>/<id>/pack, the .nup and its job beside it
        reset();
        File target = new File(builds, "pack"), inBuild = FirstImport.buildFile(null, builds, home, ID);
        imp(game(null, V, target, inBuild), pack(new File(t, "src2"), ID, V, "pc"), TITLES);
        Files.copy(fits.toPath(), mkdirs(inBuild).toPath());
        check(target.equals(chosen(new File(t, "app2/packs/" + ID), target, inBuild)), "Build takes the imported pack in the build folder: " + chosen(new File(t, "app2/packs/" + ID), target, inBuild));
        check(read(new File(target, "shaders/v-c0ffee/dmsh_vk.lib")).equals("library pc"), "its library is the imported one");
    }

    static File mkdirs(File f) {
        f.getParentFile().mkdirs();
        return f;
    }

    static void otherTitle(File t) throws Exception {
        reset();
        File src = pack(new File(t, "src"), MKW, 0, "mkw"), target = new File(t, "app/packs/" + ID);
        PackImport.Refused r = refused(() -> imp(game(null, V, target, null), src, TITLES));
        check(r != null && r.getMessage().equals("That pack is for Mario Kart World, not Donkey Kong Bananza. Import it from Mario Kart World's sheet. Nothing was changed."), "another game: " + (r == null ? null : r.getMessage()));
        check(!target.exists() && !target.getParentFile().exists(), "nothing written for another game's pack");
        check(r.detail.contains(MKW) && r.detail.contains(ID), "the details: both title IDs");
    }

    /**
     * The busy mark has one spelling (PackImport.key): while an import runs (Titles is asked in the middle of it, for a pack of another game), the title is
     * busy in upper and in lower case, a second import of it cannot be taken in either case, and both are free again after it. An import nobody took does
     * not run (PackImportUi.start takes it on the main thread with its last check, so no Build starts in between)
     */
    static void busyKey(File t) throws Exception {
        reset();
        File src = pack(new File(t, "src"), MKW, 0, "mkw"), target = new File(t, "app/packs/" + ID);
        String lower = ID.toLowerCase(java.util.Locale.ROOT);
        Map<String, Boolean> seen = new HashMap<>();
        PackImport.Titles asks = new PackImport.Titles() {
            @Override public String name(String id) {
                seen.put("upper", PackImport.busy(ID));
                seen.put("lower", PackImport.busy(lower));
                seen.put("second", PackImport.take(lower));
                return TITLES.name(id);
            }
            @Override public FirstImport.Other where(String id) { return TITLES.where(id); }
        };
        check(refused(() -> imp(game(null, V, target, null), src, asks)) != null, "a pack of another game: refused");
        check(seen.get("upper") && seen.get("lower"), "busy while it runs, asked in either case: " + seen);
        check(!seen.get("second"), "a second import of the title, in lower case, is not taken");
        check(!PackImport.busy(ID) && !PackImport.busy(lower), "free again after it, in either case");
        IllegalStateException notTaken = null;
        try { PackImport.run(C, game(null, V, target, null), src, TITLES); } catch (IllegalStateException e) { notTaken = e; } catch (PackImport.Refused e) { /* it ran */ }
        check(notTaken != null && !target.exists(), "an import nobody took does not run");
        check(PackImport.take(lower) && PackImport.busy(ID) && !PackImport.take(ID), "taken in lower case, busy and not taken again in upper case");
        check(refused(() -> PackImport.run(C, game(null, V, target, null), src, TITLES)) != null && !PackImport.busy(ID), "run gives it back, refused or not");
    }

    static void whoElse(File t) throws Exception {
        File src = pack(new File(t, "src"), ID, V, "new"), target = new File(t, "app/packs/" + ID);
        pack(target, ID, V, "old");
        FirstImport.Game g = game(null, V, target, null);
        reset();
        set("PackUi", "BUSY", true);
        PackImport.Refused r = refused(() -> imp(g, src, TITLES));
        check(r != null && r.getMessage().startsWith("An export or import of Donkey Kong Bananza's pack is still working."), "the Storage tab's import or export works");
        reset();
        set("SaveUi", "RUNNING", true);
        r = refused(() -> imp(g, src, TITLES));
        check(r != null && r.getMessage().startsWith("Donkey Kong Bananza is running: stop it first"), "a game of the title runs");
        reset();
        listGame(new File(t, "elsewhere/DK.nup").getPath(), target.getPath());
        r = refused(() -> imp(g, src, TITLES));
        check(r != null && r.getMessage().startsWith("Another copy of Donkey Kong Bananza in your list plays from this pack folder."), "another list entry plays from the pack folder");
        check(r.detail.contains("The other copy: " + new File(t, "elsewhere/DK.nup")), "the details: the other copy");
        check(read(new File(target, "main.patched.bin")).equals("code old"), "its pack as it was");
        reset();
        listGame(new File(t, "elsewhere/DK.nup").getPath(), new File(t, "elsewhere/" + ID + "-work/android-pack").getPath());
        check(refused(() -> imp(g, src, TITLES)) == null, "an entry with a pack folder of its own is no matter");
    }

    static void stoppedJob(File t) throws Exception {
        reset();
        File src = pack(new File(t, "src"), ID, V, "new"), target = new File(t, "app/packs/" + ID), nup = new File(t, "games/DK/DK.nup");
        File job = new File(t, "games/DK/" + ID + "-work/ondevice/job.json");
        write(job, "failed\t" + new File(t, "elsewhere/pack"));
        PackImport.Refused r = refused(() -> imp(game(null, V, target, nup), src, TITLES));
        check(r != null && r.getMessage().startsWith("A build of Donkey Kong Bananza stopped part way, and the next Build would go on with it"), "the game's own unfinished job: " + (r == null ? null : r.getMessage()));
        check(r.detail.contains("Working files: " + new File(t, "games/DK/" + ID + "-work")) && !target.exists(), "the details: the working files; nothing written");
        write(job, "running\t" + target);
        r = refused(() -> imp(game(null, V, target, nup), src, TITLES));
        check(r != null && r.getMessage().startsWith("Donkey Kong Bananza is being built now."), "a running job");
        write(job, "done\t" + target);
        check(refused(() -> imp(game(null, V, target, nup), src, TITLES)) == null, "a done job: in");
        reset();
        File other = new File(t, "other-work/ondevice/job.json");
        write(other, "paused\t" + target);
        set("Job", "REMEMBERED", other);
        r = refused(() -> imp(game(null, V, target, null), src, TITLES));
        check(r != null && r.getMessage().startsWith("A build of Donkey Kong Bananza stopped part way"), "Home's Prepare job writes to the pack folder");
        write(other, "paused\t" + new File(t, "another/pack"));
        check(refused(() -> imp(game(null, V, target, null), src, TITLES)) == null, "Home's Prepare job of another pack folder is no matter");
        write(other, "not a job");
        check(refused(() -> imp(game(null, V, target, null), src, TITLES)) == null, "a job file of another format writes nothing");
    }

    static void pickedPack(File t) throws Exception {
        reset();
        File src = pack(new File(t, "src"), ID, V, "new"), target = new File(t, "app/packs/" + ID), picked = pack(new File(t, "picked"), ID, V, "picked");
        Map<String, String> p = get("Folders", "PICKED"), f = get("Prefs", "FOLDERS");
        p.put(ID, picked.getPath());
        f.put("pack", picked.getPath());
        PackImport.Done d = imp(game(null, V, target, null), src, TITLES);
        check(!p.containsKey(ID) && !f.containsKey("pack"), "the picked pack folder let go (it would win at the next Build)");
        check(d.detail.contains("The pack folder picked before (" + picked + ") is kept as it was") && read(new File(picked, "main.patched.bin")).equals("code picked"), "it is kept as it was");
    }

    static void dumpFits(File t) throws Exception {
        reset();
        File game = dump(new File(t, "games/DK"), 22), src = pack(new File(t, "src"), ID, V, "new"), target = new File(t, "app/packs/" + ID);
        listGame(game.getPath(), target.getPath());   // its own entry from before (not ready): replaced, never "another copy"
        pack(target, ID, V, "old");
        PackImport.Done d = imp(game(game, 0, target, game), src, TITLES);
        check(d.listed && d.text.equals("It fits Donkey Kong Bananza's game file and has this app's shaders: ready to play."), "a dump folder the pack fits: listed, ready: " + d.text);
        List<?> l = listed();
        check(l.size() == 1 && field(l.get(0), "gameDir").equals(game.getPath()) && field(l.get(0), "packDir").equals(target.getPath()) && field(l.get(0), "titleId").equals(ID), "its list entry");
        check(read(new File(target, "main.patched.bin")).equals("code new"), "the pack is in");
    }

    static void dumpOtherBuild(File t) throws Exception {
        reset();
        File game = dump(new File(t, "games/DK"), 23), src = pack(new File(t, "src"), ID, V, "new"), target = new File(t, "app/packs/" + ID);
        PackImport.Refused r = refused(() -> imp(game(game, V, target, game), src, TITLES));
        check(r != null && r.getMessage().equals("That pack was made from update 1.0.1 of Donkey Kong Bananza, another build than the game file here. Import a pack made from this game file, "
                + "or use Build. Nothing was changed."), "a dump of another build: " + (r == null ? null : r.getMessage()));
        check(r.detail.contains("Game file: " + game) && r.detail.contains("main: pack"), "the details: the build IDs and the game file: " + r.detail);
        check(!target.exists() && !leftovers(target.getParentFile()) && listed().isEmpty(), "nothing in, nothing listed");
    }

    /** A swap that stopped with the old pack aside (PackZip.swapIn could rename neither back): the refusal says so, never "Nothing was changed." */
    static void swapAside(File t) throws Exception {
        File target = new File(t, "app/packs/" + ID), old = new File(target.getPath() + ".replaced");
        FirstImport.Game g = game(null, V, target, null);
        check(PackImport.failed(g, "x", "d").getMessage().equals("X. Nothing was changed."), "a plain refusal while the pack folder is as it was");
        pack(old, ID, V, "old");
        check(PackImport.aside(target), "the old pack aside, no pack folder: aside");
        PackImport.Refused r = PackImport.failed(g, "cannot name the new pack", "Pack folder: " + target);
        check(r.getMessage().equals(FirstImport.swapFailed(g)) && r.detail.startsWith("Kept as: " + old.getPath() + "\n"), "the old pack's place in the details: " + r.getMessage());
        pack(target, ID, V, "new");
        check(!PackImport.aside(target), "both there (a removal that stopped): not aside");
    }

    static void damaged(File t) throws Exception {
        reset();
        File target = new File(t, "app/packs/" + ID);
        File notZip = new File(t, "pack.zip");
        write(notZip, "not a zip");
        PackImport.Refused r = refused(() -> imp(game(null, V, target, null), notZip, TITLES));
        check(r != null && r.getMessage().startsWith("Pack.zip is not a zip file") && r.getMessage().endsWith("Nothing was changed."), "not a zip: " + (r == null ? null : r.getMessage()));
        File unfinished = pack(new File(t, "unfinished"), ID, V, "u");
        new File(unfinished, "main.android.key").delete();
        r = refused(() -> imp(game(null, V, target, null), unfinished, TITLES));
        check(r != null && r.getMessage().startsWith("The pack's code is unfinished"), "unfinished code: upstream's own check");
        File noDir = new File(t, "nothing-here");
        r = refused(() -> imp(game(null, V, target, null), noDir, TITLES));
        check(r != null && r.getMessage().endsWith("Nothing was changed."), "a source that is not there");
        check(!target.exists() && !leftovers(target.getParentFile()), "nothing written by a damaged source");
        Set<String> none = new HashSet<>();
        check(none.isEmpty() && !PackImport.busy(ID), "busy again after refusals");
    }
}
// PEANUTS end
