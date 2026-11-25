// PEANUTS: when the Mii renderer tries again after a failed start (Settings › Users, Miis)
package dev.peanuts.mii;

import java.io.File;
import java.nio.file.Files;

/** MiiGlRetry on the JVM (peanuts/tests/test_mii_gl.py): a failed start waits, and tries again once the firmware's Mii files change or the wait is over */
public final class MiiGlRetryJvmTest {
    static int fails;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("miimodel").toFile();
        File gone = new File(dir, "none");
        check(MiiGlRetry.stamp(dir).isEmpty() && MiiGlRetry.stamp(gone).isEmpty(), "no Mii files, an empty stamp");

        File tex = new File(dir, "NXTextureMidSRGB.dat"), shape = new File(dir, "ShapeMid.dat"), other = new File(dir, "notes.txt");
        Files.write(tex.toPath(), new byte[64]); Files.write(shape.toPath(), new byte[32]); Files.write(other.toPath(), new byte[8]);
        String s0 = MiiGlRetry.stamp(dir);
        check(s0.contains("NXTextureMidSRGB.dat:64:") && s0.contains("ShapeMid.dat:32:") && !s0.contains("notes.txt"), "the stamp names the Mii files, not others");
        Files.write(other.toPath(), new byte[9]);
        check(MiiGlRetry.stamp(dir).equals(s0), "another file changing leaves the stamp");
        Files.write(shape.toPath(), new byte[33]);
        check(!MiiGlRetry.stamp(dir).equals(s0), "a Mii file of another size changes it");
        String s1 = MiiGlRetry.stamp(dir);
        check(shape.setLastModified(shape.lastModified() - 60_000) && !MiiGlRetry.stamp(dir).equals(s1), "a Mii file written at another time changes it");

        MiiGlRetry r = new MiiGlRetry();
        check(!r.waiting(0, dir), "a renderer that never failed does not wait");
        r.failed(1_000, dir);
        check(r.waiting(1_000, dir) && r.waiting(1_000 + MiiGlRetry.WAIT_MS - 1, dir), "after a failure it waits");
        check(!r.waiting(1_000 + MiiGlRetry.WAIT_MS, dir), "the wait ends");
        check(!r.waiting(1_000 + MiiGlRetry.WAIT_MS + 1, dir), "and the next try is not held back");

        r.failed(5_000, dir);
        Files.write(tex.toPath(), new byte[128]);   // the firmware imported again
        check(!r.waiting(5_001, dir), "other Mii files end the wait at once");

        File empty = Files.createTempDirectory("miimodel-empty").toFile();
        r.failed(9_000, empty);
        check(r.waiting(9_001, empty), "no files: it waits");
        Files.write(new File(empty, "ShapeHigh.dat").toPath(), new byte[16]);
        check(!r.waiting(9_002, empty), "files that come end the wait");

        r.failed(20_000, dir);
        r.ok();
        check(!r.waiting(20_001, dir), "a start that worked clears the failure");

        System.out.println(fails == 0 ? "PASS" : fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }
}
