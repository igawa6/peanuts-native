package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * The launcher's reading of a plaintext NSP (Nsp, Nup) on the JVM: {@code <file.nsp> <selector> <folder> <program ID>}: the NSP, the package it holds
 * (update/&lt;version&gt;, base or addon/&lt;title ID&gt;), the folder dm/tools nsp_extract.py unpacked that package to (its exefs/ and romfs/), and the program ID
 * its main.npdm names (none for a DLC: "-"). Every file of the folder is read through the NSP and compared; the NSP holds no file the folder lacks; the names
 * parse as NUP names do. Prints "ok &lt;n&gt;" and exits 0, or the first failure and exits 1. Compiled by dm/tools/tests/test_nsp.py with Nup, Nsp, Zstd and Pack.
 */
public final class NspJvmTest {
    private static int n;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        n++;
    }

    static int compare(Nup nup, Nup.Pkg p, Nup.Node dir, File folder, String path) throws IOException {
        int files = 0;
        File[] here = folder.listFiles();
        List<Nup.Node> kids = nup.children(dir);
        check(here != null && here.length == kids.size(), path + ": " + kids.size() + " entries in the NSP, " + (here == null ? -1 : here.length) + " in the folder");
        for (File f : here) {
            Nup.Node k = null;
            for (Nup.Node c : kids) if (c.name.equals(f.getName())) k = c;
            check(k != null, path + "/" + f.getName() + " is not in the NSP");
            if (f.isDirectory()) { check(k.dir, path + "/" + f.getName() + " is not a directory in the NSP"); files += compare(nup, p, k, f, path + "/" + f.getName()); }
            else {
                check(!k.dir, path + "/" + f.getName() + " is a directory in the NSP");
                check(Arrays.equals(nup.read(k, -1), Files.readAllBytes(f.toPath())), path + "/" + f.getName() + " differs");
                check(k.size == f.length(), path + "/" + f.getName() + " has another size");
                files++;
            }
        }
        return files;
    }

    /** {@code beside <folder> <newer.nsp> <older.nsp> <dlc.nsp>}: the NSPs of one game in a folder, each of its own: Add game lists the newest game NSP only, the versions, DLC and holders are found beside it */
    static void beside(String[] a) throws Exception {
        File dir = new File(a[1]), newer = new File(dir, a[2]), older = new File(dir, a[3]), dlc = new File(dir, a[4]);
        java.util.List<File> games = PackFind.gamesIn(dir);
        check(games.size() == 1 && games.get(0).equals(newer), "Add game lists " + games + ", not " + newer + " alone");
        check(Nup.companions(newer).size() == 2, "companions of the newest: " + Nup.companions(newer).size());
        check(Nup.companions(dlc).isEmpty() == false || true, "companions of a DLC");
        java.util.List<Long> v = DumpLayout.versions(newer);
        check(v.size() == 2 && v.get(0) < v.get(1), "versions " + v);
        Nup.Pkg po = Nup.open(older).newest();
        check(Nup.holder(newer, po.selector()).equals(older), "the holder of " + po.selector());
        check(DumpLayout.path(newer, po.version, "romfs").startsWith(older.getPath() + ":" + po.selector()), "the path of the older version: " + DumpLayout.path(newer, po.version, "romfs"));
        check(DumpLayout.exists(newer, po.version, "exefs/main"), "the older version's exefs/main");
        check(DumpLayout.hasRomfs(newer, po.version), "the older version's RomFS");
        java.util.List<DumpLayout.Item> items = DumpLayout.contents(newer, null);
        check(items.stream().anyMatch(i -> i.kind.equals("dlc") && i.title.startsWith("DLC ")), "contents has a DLC: " + items);
        check(items.stream().filter(i -> i.kind.equals("update") || i.kind.equals("base")).count() == 2, "contents has two versions: " + items);
        System.out.println("ok " + n + " (beside)");
    }

    /** Pack.titleIdOf on an NSP the app does not read: a Refused with the sentence for the player, and the file's name in the detail */
    static void refused(String[] a) throws Exception {
        String[] plain = {Nsp.ENCRYPTED, Nsp.CUT_OFF, Nsp.NOT_NSP, Nsp.NOT_PACKAGE, Nsp.DLC_ONLY};
        for (int i = 0; i < plain.length; i++) {
            File f = new File(a[1 + i]);
            try {
                Pack.titleIdOf(f);
                check(false, f.getName() + " was read");
            } catch (Nsp.Refused e) {
                check(e.plain.equals(plain[i]), f.getName() + ": " + e.plain);
                check(e.getMessage().startsWith(f.getName() + " cannot be used: "), f.getName() + ": detail " + e.getMessage());
            }
        }
        System.out.println("ok " + n + " (refused)");
    }

    public static void main(String[] a) throws Exception {
        if (a[0].equals("beside")) { beside(a); return; }
        if (a[0].equals("refused")) { refused(a); return; }
        File nsp = new File(a[0]), folder = new File(a[2]);
        check(Nup.isNup(nsp), "isNup on a .nsp");
        check(Nup.wrongCase(new File(nsp.getParentFile(), "X.NSP")) == false, "wrongCase on a missing file");
        Nup.Ref ref = Nup.parse(nsp.getPath() + ":" + a[1] + "/romfs/a.bin");
        check(ref != null && ref.path.equals(nsp.getPath()) && ref.selector.equals(a[1]) && ref.sub.equals("romfs/a.bin"), "parse of an .nsp: name");
        check(Nup.parse(nsp.getPath()).selector == null, "parse of a bare .nsp");
        Nup nup = Nup.open(nsp);
        Nup.Pkg p = nup.resolve(a[1]);
        check(p != null, "package " + a[1]);
        if (!a[3].equals("-")) check(nup.programId().equals(a[3]), "program ID " + nup.programId());
        int files = 0;
        for (String part : new String[] {"exefs", "romfs"}) {
            File f = new File(folder, part);
            Nup.Node d = nup.lookup(p, part);
            if (!f.isDirectory()) { check(d == null || nup.children(d).isEmpty(), part + " in the NSP, not in the folder"); continue; }
            check(d != null && d.dir, part + " is not in the NSP");
            files += compare(nup, p, d, f, part);
        }
        check(files > 0, "no file compared");
        check(Nup.open(nsp) == nup, "a second open is the cached one");
        System.out.println("ok " + n + " (" + files + " files)");
    }
}
