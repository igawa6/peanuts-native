// PEANUTS: new file (a frame rate that starts a variant: Animal Crossing: New Horizons' 60 fps speed fix, dm/games/01006F8002326000/speedfix60)
package dev.nxnative.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Profile.fpsVariant with FrameRateCaps' list for such a profile, and Pack's variant rows, on the JVM. args[0]: an empty folder. Prints "ok" or throws */
public final class FpsVariantJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }
    private static String caps(int[] v) { StringBuilder b = new StringBuilder(); for (int x : v) b.append(b.length() > 0 ? "," : "").append(x); return b.toString(); }

    /** A profile line as build.gradle writes it: columns 13 (paced), 15 (fps_limit), 17 (fps_limit_present), 25 (variants), 35 (fps_modes) */
    static Profile line(String present, String variants, String modes) {
        String[] c = new String[43];
        java.util.Arrays.fill(c, "");
        c[0] = "01006F8002326000"; c[1] = "Animal Crossing: New Horizons"; c[2] = "0"; c[3] = "x"; c[4] = "v2228224 main=ff1d1c05670db6021c85b624a710b963";
        c[17] = present; c[25] = variants; c[35] = modes;
        return Profile.parse(String.join("\t", c)).get(0);
    }

    public static void main(String[] args) throws Exception {
        Profile p = line("1", "60=60fps", "60");
        check("60".equals(p.fpsVariant(60)) && p.fpsVariant(30) == null && p.hasFpsVariant(), "the 60 cap starts variant 60: " + p.variants);
        check(p.aspectVariant("4:3") == null && p.aspectVariant("16:9") == null, "not an aspect ratio");
        check(FrameRateCaps.limited(p) && p.optInPresentRate(), "the frame limit list, 60 fps by default since 0.1.8 (FrameRateCaps.defaultLimit)");
        for (int hz : new int[] {60, 90, 120, 144})
            check(caps(FrameRateCaps.paceOffered(p, hz)).equals(FrameRateCaps.GAME_OWNED + ",0"), hz + " Hz: Game owned and 60 only: " + caps(FrameRateCaps.paceOffered(p, hz)));
        check(p.fpsRestart(FrameRateCaps.GAME_OWNED, 0) && p.fpsRestart(0, FrameRateCaps.GAME_OWNED) && !p.fpsRestart(0, 0), "to and from Game owned at the next start");
        check(p.fpsMode(45) == 0 && p.fpsMode(FrameRateCaps.GAME_OWNED) == FrameRateCaps.GAME_OWNED, "a saved 45 reads as 60, Game owned stays");
        check(p.fpsVariant(FrameRateCaps.limitRate(0, 60)) != null && p.fpsVariant(FrameRateCaps.limitRate(0, 120)) != null, "the stored 0 is 60 on any display");
        Profile plain = line("", "43=4:3", "");
        check(!plain.hasFpsVariant() && plain.fpsVariant(60) == null && "43".equals(plain.aspectVariant("4:3")), "an aspect variant is no frame rate's");

        // Pack's variant rows (pack_android.py variant_overlays) and folders
        File pack = new File(args[0], "pack");
        check(new File(pack, "60/romfs/Bcsv").mkdirs(), "mkdirs");
        Files.write(new File(pack, "main.patched.bin").toPath(), new byte[] {0});
        String head = "format\t1\ntitle_id\t01006F8002326000\nname\tx\nversion\t2228224\ntarget\tandroid\nmodules\tmain\nromfs\tx.nup:update/2228224/romfs\n";
        Files.write(new File(pack, "pack.tsv").toPath(), (head + "variant\t60\t60/romfs\nvariant\tevil\t../outside\nvariant\tnone\t-\n").getBytes(StandardCharsets.UTF_8));
        Pack pk = Pack.read(pack);
        check(!pk.variantReady("60"), "a row without its main is not ready");
        Files.write(new File(pack, "60/main.patched.bin").toPath(), new byte[] {0});
        check(pk.variantReady("60") && new File(pack, "60/romfs").equals(pk.variantRomfs("60")), "ready, with its overlay: " + pk.variantRomfs("60"));
        check(pk.variantRomfs("evil") == null && pk.variantRomfs("none") == null && pk.variantRomfs("gone") == null, "never outside the pack, '-' is none");
        check(!pk.variantReady("43") && !pk.variantReady(null), "no row: not ready");
        Files.write(new File(pack, "pack.tsv").toPath(), head.getBytes(StandardCharsets.UTF_8));
        check(!Pack.read(pack).variantReady("60"), "a pack without the row (another version, an older pack) is not ready though its 60/main is there");
        System.out.println("ok");
    }
}
