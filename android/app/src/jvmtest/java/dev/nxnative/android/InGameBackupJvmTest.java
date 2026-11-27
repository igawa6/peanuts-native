// PEANUTS: new file. The game menu's Back up the save (InGamePanel, SaveStore.backupRunning) on the JVM, run by peanuts/tests/test_ingame_menus.py with SaveStore.
package dev.nxnative.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A backup of the save while its game runs. The game's start recovered the save, so a marker beside it is the host's own swap at a Commit (plat_android.c
 * plat_rename_swap), still being written: the backup leaves it, the save and the host's .new exactly as they are and fails as a changed save does, so the game
 * menu tries again. Each state a Commit passes through (the marker written, stage 1 part way, .pend2 and stage 2 part way, the device save's own), then a host
 * that commits again and again while backups run beside it: every backup that finishes holds one whole version of the save, never a mix, one that does not
 * leaves nothing behind, and the host's swaps all go through. The race needs File.renameTo to replace a file the backup may be reading (Android, Linux, the
 * Mac): on Windows it is left out. Prints "PASS" and the number of checks.
 */
public final class InGameBackupJvmTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    /** Everything at and beside the save (its markers, its .new, the device save and theirs): path → content, null for a folder */
    private static Map<String, String> beside(File save) throws IOException {
        Map<String, String> m = new TreeMap<>();
        File[] l = save.getParentFile().listFiles();
        if (l != null) for (File f : l) if (f.getName().startsWith(save.getName()) && !f.getName().endsWith(SaveStore.LOCK_SUFFIX)) walk(f, f.getName(), m);
        return m;
    }

    private static void walk(File f, String rel, Map<String, String> m) throws IOException {
        if (f.isDirectory()) {
            m.put(rel, null);
            String[] n = f.list();
            if (n != null) for (String x : n) walk(new File(f, x), rel + "/" + x, m);
        } else m.put(rel, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    /** A save of version v: slots of a few KiB, a file only that version has, and a folder */
    private static void version(File dir, String v) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 4096; k++) b.append(v);
        for (int i = 0; i < 6; i++) write(new File(dir, "slot" + i + ".bin"), v + i + b);
        write(new File(dir, "only-" + v + ".bin"), v);
        write(new File(dir, "sub/options.bin"), "options " + v);
    }

    public static void main(String[] args) throws Exception {
        File t = Files.createTempDirectory("ingamebackup").toFile();
        try {
            plain(new File(t, "plain"));
            commitAtTheStart(new File(t, "start"));
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) race(new File(t, "race"));
            else System.out.println("race left out: Windows' File.renameTo does not replace a file");
        } finally {
            SaveStore.deleteTree(t);
        }
        System.out.println("PASS " + checks);
    }

    /** No Commit: a backup of the save, verified, the save only read */
    static void plain(File t) throws Exception {
        File s = new File(t, "saves/0100F43008C44000"), bk = new File(t, "save-backups/0100F43008C44000");
        version(s, "A");
        version(SaveStore.device(s), "D");
        Map<String, String> before = beside(s);
        SaveStore.Backup b = SaveStore.backupRunning(s, bk, "20261126-100000");
        check(b.dir.equals(new File(bk, "20261126-100000")) && b.device && b.files == 16, "the backup, with the device save: " + b.files);
        check(SaveStore.scan(new File(b.dir, "main")).diff(SaveStore.scan(s)) == null && SaveStore.scan(new File(b.dir, "device")).diff(SaveStore.scan(SaveStore.device(s))) == null,
                "the backup holds the save and its device save");
        check(beside(s).equals(before), "the save is only read");
    }

    /** Each state a Commit passes through, as the copy starts: refused as a changed save, the save, the host's .new and its markers left exactly as they are */
    static void commitAtTheStart(File t) throws Exception {
        String[] states = {"marker", "stage 1", "pend2", "stage 2", "device", "empty marker"};
        for (String state : states) {
            File r = new File(t, state.replace(' ', '-'));
            File s = new File(r, "saves/0100F43008C44000"), bk = new File(r, "save-backups/0100F43008C44000");
            version(s, "A");
            File b = s;
            if (state.equals("device")) { version(SaveStore.device(s), "D"); b = SaveStore.device(s); }
            File a = new File(b.getPath() + ".new");   // the host's transaction, complete before its marker
            version(a, "B");
            File m1 = SaveStore.marker(b, 1), m2 = SaveStore.marker(b, 2);
            switch (state) {
                case "marker": case "device": write(m1, a.getAbsolutePath()); break;
                case "empty marker": write(m1, ""); break;   // written, its content not yet on the storage
                case "stage 1": write(m1, a.getAbsolutePath()); Files.delete(new File(b, "only-A.bin").toPath()); break;
                case "pend2": write(m2, a.getAbsolutePath()); Files.delete(new File(b, "only-A.bin").toPath()); break;
                case "stage 2":
                    write(m2, a.getAbsolutePath()); Files.delete(new File(b, "only-A.bin").toPath());
                    Files.move(new File(a, "slot0.bin").toPath(), new File(b, "slot0.bin").toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.move(new File(a, "only-B.bin").toPath(), new File(b, "only-B.bin").toPath());
                    break;
                default: throw new AssertionError(state);
            }
            Map<String, String> before = beside(s);
            try {
                SaveStore.backupRunning(s, bk, "20261126-110000");
                check(false, state + ": a backup beside the host's Commit is refused");
            } catch (IOException e) {
                check(String.valueOf(e.getMessage()).contains("changed while"), state + ": refused as a changed save, so the game menu tries again: " + e.getMessage());
            }
            check(beside(s).equals(before), state + ": the save, the host's .new and its markers are exactly as they were (no recovery beside the host)");
            String[] left = bk.list();
            check(left == null || left.length == 0, state + ": no backup and no partial copy: " + (left == null ? "" : Arrays.toString(left)));
        }
    }

    /** The host's swap at a Commit (plat_android.c plat_rename_swap on shared storage), with a pause between its steps so backups meet it part way */
    private static void hostSwap(File a, File b) throws Exception {
        write(SaveStore.marker(b, 1), a.getAbsolutePath());
        String[] have = b.list();
        if (have != null) for (String n : have) if (!new File(a, n).exists()) { SaveStore.deleteTree(new File(b, n)); Thread.sleep(1); }   // stage 1
        write(SaveStore.marker(b, 2), a.getAbsolutePath());
        Files.delete(SaveStore.marker(b, 1).toPath());
        move(a, b);   // stage 2
        Files.delete(SaveStore.marker(b, 2).toPath());
        SaveStore.deleteTree(a);
    }

    private static void move(File a, File b) throws Exception {
        String[] names = a.list();
        if (names == null) return;
        Arrays.sort(names);
        for (String n : names) {
            File pa = new File(a, n), pb = new File(b, n);
            if (pa.isDirectory()) { pb.mkdirs(); move(pa, pb); continue; }
            if (!pa.renameTo(pb)) throw new IOException("cannot move " + pa + " over " + pb);
            Thread.sleep(1);
        }
    }

    /** A host committing again and again (versions A and B in turn) while backups run beside it */
    static void race(File t) throws Exception {
        File s = new File(t, "saves/0100F43008C44000"), bk = new File(t, "save-backups/0100F43008C44000");
        version(s, "A");
        File oa = new File(t, "A"), ob = new File(t, "B");
        version(oa, "A");
        version(ob, "B");
        SaveStore.Manifest va = SaveStore.scan(oa), vb = SaveStore.scan(ob);
        AtomicReference<Throwable> hostFailed = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger commits = new AtomicInteger();
        Thread host = new Thread(() -> {
            try {
                for (int i = 0; !stop.get(); i++) {
                    File a = new File(s.getPath() + ".new");
                    version(a, i % 2 == 0 ? "B" : "A");
                    hostSwap(a, s);
                    commits.incrementAndGet();
                    Thread.sleep(i % 4 == 3 ? 400 : 2);   // now and then a pause long enough for a whole backup, also on a slow runtime
                }
            } catch (Throwable e) {
                hostFailed.set(e);
            }
        }, "host");
        host.start();
        int done = 0, changed = 0, moved = 0;
        List<String> mixes = new ArrayList<>();
        long until = System.currentTimeMillis() + 30_000;
        try {
            for (int k = 0; System.currentTimeMillis() < until && (done < 5 || changed + moved < 5 || k < 40) && hostFailed.get() == null; k++) {
                try {
                    SaveStore.Backup b = SaveStore.backupRunning(s, bk, "20261126-12" + String.format("%04d", k));
                    SaveStore.Manifest got = SaveStore.scan(new File(b.dir, "main"));
                    if (got.diff(va) != null && got.diff(vb) != null) mixes.add(b.name);
                    done++;
                } catch (IOException e) {   // a changed save, or a file the swap moved away under the copy (the game menu tries both again)
                    if (String.valueOf(e.getMessage()).contains("changed while")) changed++; else moved++;
                    check(!String.valueOf(e.getMessage()).contains("no save folder"), "a Commit never reads as no save: " + e.getMessage());
                    Thread.sleep(3);   // the game menu waits too before it tries again
                }
            }
        } finally {
            stop.set(true);
            host.join();
        }
        check(hostFailed.get() == null, "every one of the host's swaps went through (nothing else touched its journal): " + hostFailed.get());
        check(mixes.isEmpty(), "every finished backup holds one whole version, never a mix: " + mixes);
        check(done >= 5 && changed + moved >= 5, "backups finished (" + done + ") and backups met a Commit (" + changed + " + " + moved + "), over " + commits.get() + " commits");
        for (String n : bk.list()) check(!n.startsWith(SaveStore.PARTIAL), "no partial copy left: " + n);
        System.out.println("race: " + done + " backups, " + changed + " saw the save change, " + moved + " lost a file to the swap, " + commits.get() + " commits");
    }
}
