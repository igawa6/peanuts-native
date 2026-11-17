package dev.peanuts.overlay;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * PEANUTS: where the overlay's SoC temperature comes from (SocTemp): the thermal zones in /sys/class/thermal whose type starts with "cpu" or "gpu", not a trip point,
 * not disabled, whose temp reads. A value above 1000 is millidegrees; 200 °C or more (or below -100) is no reading. The hottest zone counts. Pure Java: checked on the
 * JVM (SocZonesJvmTest).
 */
public final class SocZones {
    private SocZones() {}

    static final int NONE = Integer.MIN_VALUE;

    /** Whether a zone of this type and mode counts (as read from its files; mode null when it has none) */
    static boolean counts(String type, String mode) {
        if (type == null) return false;
        String t = type.trim();
        if (!t.startsWith("cpu") && !t.startsWith("gpu")) return false;
        if (t.contains("trip")) return false;
        return mode == null || !mode.trim().startsWith("disabled");
    }

    /** One zone's temp in whole °C, NONE when it does not parse or is out of range */
    static int celsius(String raw) {
        if (raw == null) return NONE;
        long v;
        try { v = Long.parseLong(raw.trim()); } catch (RuntimeException e) { return NONE; }
        if (v > 1000) v /= 1000;
        return v < 200 && v > -100 ? (int) v : NONE;
    }

    /** The temp files of the zones that count under root: thermal_zone0, 1, ... up to the first without a type file (at most 64 zones) */
    static List<File> discover(File root) {
        List<File> out = new ArrayList<>();
        for (int i = 0; i < 200 && out.size() < 64; i++) {
            File z = new File(root, "thermal_zone" + i);
            File type = new File(z, "type");
            if (!type.exists()) break;
            if (!counts(read(type), read(new File(z, "mode")))) continue;
            File temp = new File(z, "temp");
            if (celsius(read(temp)) != NONE) out.add(temp);
        }
        return out;
    }

    /** The hottest of these temp files in °C, NONE when none reads */
    static int hottest(List<File> temps) {
        int hi = NONE;
        for (File f : temps) { int c = celsius(read(f)); if (c > hi) hi = c; }
        return hi;
    }

    static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII); }
        catch (IOException | RuntimeException e) { return null; }
    }
}
