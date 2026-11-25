package dev.nxnative.android;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PEANUTS: GPU driver builds known to draw broken on a GPU (android/README.md "Custom GPU drivers", peanuts/docs/FAQ.md "GPU drivers"). Nothing is blocked: the driver pickers
 * mark such a driver (GpuDriver.problemText: Settings › GPU driver, a game's GPU driver page, the main screen's dialog), a start with it on a matching GPU asks once
 * (MainActivity.start: Play anyway / Change driver) and the game's log gets one [RED] line from the start-up check (GameActivity.readinessDecide).
 *
 * A build is matched by what the driver says about itself, never by the folder the player installed it in: its Mesa git hash in the Vulkan driverInfo the readiness
 * check reads ("Mesa 27.0.0-devel (git-c5ef4bdfab)"), and, before any check has run on the driver, in its meta.json (name, description, driverVersion; the R3 zip's
 * description is "Mesa c5ef4bdfabf2e1a76d5e41ab8e5d75ddbc121275"). Where the driver's own driverInfo is known it alone decides. The GPU is a Recommend match on
 * gpu.vendor / gpu.device (the driver's facts, else the phone's own driver's); a GPU not reported yet counts as matching unless its vendor is known not to be
 * Qualcomm's (these are Adreno drivers). No Android classes: the JVM test runs it.
 *
 * A new bad build is one more row in BAD.
 */
final class KnownDrivers {
    private KnownDrivers() {}

    static final class Bad {
        /** A Mesa git hash (its first 10 or more hex digits, lower case) the driver's identity carries */
        final String build;
        /** Where it draws broken: a Recommend match on gpu.vendor / gpu.device */
        final String gpu;
        /** Its name for the texts, and the GPU family for the log */
        final String name, where;
        /** What works instead, for the start notice */
        final String instead;
        Bad(String build, String gpu, String name, String where, String instead) {
            this.build = build.toLowerCase(Locale.ROOT); this.gpu = gpu; this.name = name; this.where = where; this.instead = instead;
        }
    }

    /** gpu.device's top byte on Adreno 7xx (Turnip's chip ID, Qualcomm's device ID: 0x43050a01 on the Adreno 740) */
    static final String ADRENO_7XX = "gpu.vendor=0x5143,gpu.device&0xff000000=0x43000000";

    static final List<Bad> BAD = Collections.unmodifiableList(Arrays.asList(
            // StevenMXZ Turnip v27.0.0-R3 (Mesa 27.0.0-devel git-c5ef4bdfab, Vulkan 1.4.368) on the AYN Thor's Adreno 740, 2026-11-24/25: the game's picture striped with
            // tile patterns, the second screen's stats page blinking, the in-game panel's radio buttons broken. Turnip 27.0.0-R2 (git-2648b2059f) is clean there
            new Bad("c5ef4bdfab", ADRENO_7XX, "Turnip v27.0.0-R3", "Adreno 7xx", "Turnip 27.0.0-R2")));

    /**
     * The known bad build a driver is on this GPU, null for none. driver: the driver's facts (driver.info and gpu.* from a readiness check on it; may be empty or null);
     * phone: the phone's own driver's facts, for the GPU when the driver's do not say it (may be null); meta: the driver's meta.json texts, read only when driver has no
     * driver.info
     */
    static Bad find(Map<String, String> driver, Map<String, String> phone, String... meta) {
        String info = driver == null ? "" : driver.getOrDefault("driver.info", "").trim();
        for (Bad b : BAD) if ((info.isEmpty() ? metaHas(meta, b.build) : has(info, b.build)) && gpuMatches(b, driver, phone)) return b;
        return null;
    }

    private static boolean has(String s, String build) { return s != null && s.toLowerCase(Locale.ROOT).contains(build); }

    private static boolean metaHas(String[] meta, String build) {
        if (meta != null) for (String m : meta) if (has(m, build)) return true;
        return false;
    }

    static boolean gpuMatches(Bad b, Map<String, String> driver, Map<String, String> phone) {
        for (Map<String, String> f : Arrays.asList(driver, phone)) if (f != null && f.containsKey("gpu.device") && Recommend.known(f)) return Recommend.matches(b.gpu, f);
        return Readiness.customDriversFit(driver != null && driver.containsKey("gpu.vendor") ? driver : phone);
    }

    /** The pickers' line for it */
    static String pickerText(Bad b) { return b.name + ": draws broken on this phone (striped picture, broken menus). Use another driver."; }

    static final String NOTICE_TITLE = "GPU driver problem";

    /** The notice when a game starts with it */
    static String notice(Bad b) {
        return "This GPU driver draws broken on this phone: the picture and the app's own screens can be striped or blink. " + b.instead + " or another driver works.";
    }

    /** The game's log line (label: the driver as the launcher names it, null when another driver than the launcher's pick was checked) */
    static String logLine(Bad b, String label) {
        return "[RED] GPU driver: " + (label != null ? label + " is " : "") + "a known broken build, " + b.name + " (Mesa git-" + b.build + ") on " + b.where
                + ": the picture and the app's own screens can be striped or blink; " + b.instead + " or another driver works";
    }
}
