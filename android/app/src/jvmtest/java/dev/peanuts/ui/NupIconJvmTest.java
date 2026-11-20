// PEANUTS: NupIcon on the JVM with upstream's own Nup reader (peanuts/tests/test_nup_icon.py, which makes the .nups); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Arguments: a folder with the test's files (test_nup_icon.py): meta.nup (an icon in its base package's meta block, icon.jpg's bytes), plain.nup (no meta block),
 * damaged.nup (no .nup at all), icon.png (a PNG), notpng.png (text); and, when the importer's test ran (a C compiler there), quest.nup (the importer's .nup of
 * dumps with control data, quest-icon.jpg's icon) and bare.nup (of dumps without).
 */
public final class NupIconJvmTest {
    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    private static byte[] read(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    private static boolean throwsIo(File nup) {
        try { NupIcon.inside(nup); return false; } catch (IOException e) { return true; }
    }

    private static int tmpFiles(File dir) {
        int k = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".tmp")) k++;
        return k;
    }

    private static File copy(File from, File to) throws IOException {
        to.getParentFile().mkdirs();
        Files.copy(from.toPath(), to.toPath());
        return to;
    }

    public static void main(String[] a) throws Exception {
        File d = new File(a[0]);
        File png = new File(d, "icon.png"), notPng = new File(d, "notpng.png");

        // the PNG beside a .nup
        check(new File(d, "Game.png").equals(NupIcon.beside(new File(d, "Game.nup"))), "beside: Game.nup -> Game.png");
        check(new File(d, "Game.png").equals(NupIcon.beside(new File(d, "Game.NUP"))), "beside: the extension in any case");
        check(NupIcon.beside(new File(d, "game.nsp")) == null && NupIcon.beside(new File(d, ".nup")) == null, "beside: none for an NSP or a bare extension");

        // the icon inside, read by upstream's Nup
        check(Arrays.equals(read(new File(d, "icon.jpg")), NupIcon.inside(new File(d, "meta.nup"))), "inside: the base package's icon, as it is");
        check(NupIcon.inside(new File(d, "plain.nup")) == null, "inside: none in a .nup without a meta block");
        check(throwsIo(new File(d, "damaged.nup")) && throwsIo(new File(d, "missing.nup")), "inside: a file that is no .nup, or none, is an IOException");
        File quest = new File(d, "quest.nup");
        if (quest.isFile()) {
            check(Arrays.equals(read(new File(d, "quest-icon.jpg")), NupIcon.inside(quest)), "inside: the importer's .nup carries its base's icon (issue #158)");
            check(NupIcon.inside(new File(d, "bare.nup")) == null, "inside: the importer's .nup of dumps without control data carries none");
        }

        // a PNG copied whole, or not at all
        File out = new File(d, "cache/T.png");
        check(NupIcon.copyPng(png, out) && Arrays.equals(read(png), read(out)), "copyPng: a PNG is copied");
        check(!NupIcon.copyPng(notPng, new File(d, "cache/N.png")) && !new File(d, "cache/N.png").exists(), "copyPng: not a PNG, nothing written");
        check(!NupIcon.copyPng(new File(d, "none.png"), new File(d, "cache/M.png")) && !new File(d, "cache/M.png").exists(), "copyPng: no file, nothing written");
        check(tmpFiles(new File(d, "cache")) == 0, "copyPng: no temporary file left");

        // keep: the app's icon beside a .nup that carries none
        File plain = copy(new File(d, "plain.nup"), new File(d, "k1/Plain Game.nup"));
        check(NupIcon.keep(plain, png) && Arrays.equals(read(png), read(new File(d, "k1/Plain Game.png"))), "keep: a .nup without an icon gets the PNG beside it");
        check(!NupIcon.keep(plain, png), "keep: not twice");
        check(tmpFiles(new File(d, "k1")) == 0, "keep: no temporary file left");
        File meta = copy(new File(d, "meta.nup"), new File(d, "k2/Meta.nup"));
        check(!NupIcon.keep(meta, png) && !new File(d, "k2/Meta.png").exists(), "keep: nothing beside a .nup that carries its icon");
        File other = copy(new File(d, "plain.nup"), new File(d, "k3/Other.nup"));
        Files.write(new File(d, "k3/Other.png").toPath(), new byte[] {1, 2, 3});
        check(!NupIcon.keep(other, png) && read(new File(d, "k3/Other.png")).length == 3, "keep: a PNG beside already stays as it is");
        File later = copy(new File(d, "plain.nup"), new File(d, "k4/Later.nup"));
        check(!NupIcon.keep(later, new File(d, "none.png")) && !new File(d, "k4/Later.png").exists(), "keep: nothing while the app has no icon");
        check(NupIcon.keep(later, png) && new File(d, "k4/Later.png").isFile(), "keep: and the icon once it has one, in the same process");
        File odd = copy(new File(d, "plain.nup"), new File(d, "k5/Odd.nup"));
        check(!NupIcon.keep(odd, notPng) && !new File(d, "k5/Odd.png").exists(), "keep: an app copy that is no PNG is not put beside");
        check(!NupIcon.keep(new File(d, "k6/StandIn.nup"), png) && !new File(d, "k6").exists(), "keep: nothing for a .nup that is not a file (a picked one's stand-in path)");
        File damaged = copy(new File(d, "damaged.nup"), new File(d, "k7/Damaged.nup"));
        boolean threw = false;
        try { NupIcon.keep(damaged, png); } catch (IOException e) { threw = true; }
        check(threw && !new File(d, "k7/Damaged.png").exists(), "keep: a .nup that cannot be read gets nothing, and says so");

        System.out.println("ok " + n);
    }
}
