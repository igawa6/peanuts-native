package dev.peanuts.overlay;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** SocZones (the overlay's SoC temperature rule: which thermal zones count, their temps, the hottest); prints "ok" */
public final class SocZonesJvmTest {
    static void check(boolean c, String what) { if (!c) { System.err.println("FAIL: " + what); System.exit(1); } }

    /** Writes thermal_zone<i>/ with a type file, a mode file when mode is not null, and a temp file */
    static void zone(File root, int i, String type, String mode, String temp) throws Exception {
        File z = new File(root, "thermal_zone" + i);
        if (!z.mkdirs()) throw new IllegalStateException(z.getPath());
        Files.write(new File(z, "type").toPath(), type.getBytes(StandardCharsets.US_ASCII));
        if (mode != null) Files.write(new File(z, "mode").toPath(), mode.getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(z, "temp").toPath(), temp.getBytes(StandardCharsets.US_ASCII));
    }

    public static void main(String[] a) throws Exception {
        // counts: the type prefix, trip points, disabled zones
        check(SocZones.counts("cpu-0-0-usr\n", null), "a cpu zone with no mode counts");
        check(SocZones.counts("gpuss-0", "enabled\n"), "an enabled gpu zone counts");
        check(!SocZones.counts("cpu-trip", null), "a trip point does not count");
        check(!SocZones.counts("battery", null), "a battery zone does not count");
        check(!SocZones.counts("cpu0", "disabled\n"), "a disabled zone does not count");
        check(!SocZones.counts(null, null), "no type does not count");

        // celsius: millidegrees above 1000, no reading at 200 or more, or below -100, or when it does not parse
        check(SocZones.celsius("45000\n") == 45, "millidegrees: " + SocZones.celsius("45000\n"));
        check(SocZones.celsius("52") == 52, "whole degrees: " + SocZones.celsius("52"));
        check(SocZones.celsius("250000") == SocZones.NONE, "250 C is no reading");
        check(SocZones.celsius("abc") == SocZones.NONE, "text is no reading");
        check(SocZones.celsius(null) == SocZones.NONE, "a missing file is no reading");
        check(SocZones.celsius("1000") == SocZones.NONE, "1000 is 200 C or more, no reading");

        // discover + hottest on a fake /sys/class/thermal
        File root = Files.createTempDirectory("soczones").toFile();
        zone(root, 0, "battery\n", null, "30000\n");
        zone(root, 1, "cpu-1-0\n", null, "61000\n");
        zone(root, 2, "gpuss-0\n", "disabled\n", "90000\n");
        zone(root, 3, "cpu-trip\n", null, "99000\n");
        zone(root, 4, "gpuss-1\n", null, "58000\n");
        List<File> z = SocZones.discover(root);
        check(z.size() == 2, "discover counts two zones: " + z.size());
        check(SocZones.hottest(z) == 61, "hottest is 61: " + SocZones.hottest(z));
        check(SocZones.hottest(List.of()) == SocZones.NONE, "no zone reads: no reading");

        System.out.println("ok");
    }
}
