package dev.nxnative.android;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PEANUTS: KnownDrivers (known bad GPU driver builds) on the JVM: Turnip v27.0.0-R3 matches on the AYN Thor's Adreno 740 by its driverInfo and, before any check
 * on it, by its meta.json; Turnip 27.0.0-R2, Qualcomm's driver and another GPU do not. Prints "ok" or throws
 */
public final class KnownDriversJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static Map<String, String> facts(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // What the readiness check reads on the Thor (Adreno 740) with each driver, 2026-11-24/25
    static final String R3_INFO = "Mesa 27.0.0-devel (git-c5ef4bdfab)", R2_INFO = "Mesa 27.0.0-devel (git-2648b2059f)";
    static Map<String, String> thor(String id, String version, String info) {
        return facts("gpu.name", "Adreno (TM) 740", "gpu.vendor", "0x5143", "gpu.device", "0x43050a01", "driver.id", id, "driver.version", version, "driver.info", info);
    }

    // The two zips' meta.json texts: name, description, driverVersion
    static final String[] R3_META = {"Turnip v27.0.0-R3", "Mesa c5ef4bdfabf2e1a76d5e41ab8e5d75ddbc121275", "Vulkan 1.4.368"};
    static final String[] R2_META = {"Turnip 27.0.0-R2", "StevenMXZ A7xx Android/Bionic", "Vulkan 1.4.367"};

    public static void main(String[] args) {
        check(KnownDrivers.BAD.size() >= 1 && KnownDrivers.BAD.get(0).name.equals("Turnip v27.0.0-R3"), "the R3 row");
        check(Recommend.conditions(KnownDrivers.ADRENO_7XX) != null, "the GPU match is a valid Recommend match");
        for (KnownDrivers.Bad b : KnownDrivers.BAD) check(b.build.matches("[0-9a-f]{10,40}") && Recommend.conditions(b.gpu) != null || "any".equals(b.gpu), "row " + b.name + ": a hash and a GPU match");

        Map<String, String> r3 = thor("18", "0x6863063", R3_INFO), r2 = thor("18", "0x6863063", R2_INFO);
        Map<String, String> qcom = thor("8", "0x802a4035", "Driver Build: 718a07726a");
        Map<String, String> sysThor = facts("gpu.vendor", "0x5143", "gpu.device", "0x43050a01", "driver.id", "8", "driver.version", "0x802a4035");

        // By the driver's own driverInfo (the start-up check)
        KnownDrivers.Bad b = KnownDrivers.find(r3, null);
        check(b != null && b.name.equals("Turnip v27.0.0-R3"), "R3 on the Adreno 740 matches");
        check(KnownDrivers.find(r2, null) == null, "R2 does not");
        check(KnownDrivers.find(qcom, null) == null, "Qualcomm's driver does not");
        check(KnownDrivers.find(thor("18", "0x6863063", "Mesa 27.0.0-devel (git-0123456789)"), null) == null, "another Mesa build does not");
        check(KnownDrivers.find(thor("18", "0x6863063", "MESA 27.0.0-DEVEL (GIT-C5EF4BDFAB)"), null) != null, "the hash in any case");

        // Another GPU: an Adreno 840 (0x44050a31), an Adreno 650 (0x06050000), a Mali
        Map<String, String> a840 = new LinkedHashMap<>(r3); a840.put("gpu.device", "0x44050a31");
        Map<String, String> a650 = new LinkedHashMap<>(r3); a650.put("gpu.device", "0x06050000");
        Map<String, String> mali = new LinkedHashMap<>(r3); mali.put("gpu.vendor", "0x13b5"); mali.put("gpu.device", "0x43050a01");
        check(KnownDrivers.find(a840, null) == null && KnownDrivers.find(a650, null) == null && KnownDrivers.find(mali, null) == null, "R3 on another GPU does not match");

        // The pickers: the facts kept for the driver (vk_facts@ + vk_info@), the phone's own driver's for the GPU, the meta.json texts
        Map<String, String> keptR3 = Readiness.facts(Recommend.keep(String.join("\n", lines(r3))));
        check(!keptR3.containsKey("driver.info"), "Recommend.keep still keeps no names");
        keptR3.put("driver.info", R3_INFO);   // Prefs.driverFacts
        check(KnownDrivers.find(keptR3, sysThor, R3_META) != null, "picker: R3 with its kept facts");
        check(KnownDrivers.find(facts(), sysThor, R3_META) != null, "picker: R3 before any check on it, by its meta.json");
        check(KnownDrivers.find(facts(), sysThor, R2_META) == null, "picker: R2 by its meta.json does not");
        check(KnownDrivers.find(r2, sysThor, R3_META) == null, "the driver's own driverInfo wins over its meta.json");
        check(KnownDrivers.find(facts(), sysThor, "Turnip_v27.0.0-R3") == null, "a folder name alone never matches");
        Map<String, String> sys840 = new LinkedHashMap<>(sysThor); sys840.put("gpu.device", "0x44050a31");
        check(KnownDrivers.find(facts(), sys840, R3_META) == null, "picker: another GPU (the phone's own driver's facts) does not");
        check(KnownDrivers.find(facts(), facts("gpu.vendor", "0x5143"), R3_META) != null, "picker: an Adreno without its device ID yet (GLES facts) is marked");
        check(KnownDrivers.find(facts(), facts("gpu.vendor", "0x13b5"), R3_META) == null, "picker: a Mali is not");
        check(KnownDrivers.find(facts(), null, R3_META) != null && KnownDrivers.find(null, null, R3_META) != null, "no facts at all: marked by its meta.json");
        check(KnownDrivers.find(null, null) == null && KnownDrivers.find(facts(), sysThor, (String) null) == null, "nothing to go on: none");

        // The texts
        check(KnownDrivers.pickerText(b).equals("Turnip v27.0.0-R3: draws broken on this phone (striped picture, broken menus). Use another driver."), KnownDrivers.pickerText(b));
        check(KnownDrivers.notice(b).equals("This GPU driver draws broken on this phone: the picture and the app's own screens can be striped or blink. Turnip 27.0.0-R2 or another driver works."), KnownDrivers.notice(b));
        String log = KnownDrivers.logLine(b, "Turnip v27.0.0-R3 (Vulkan 1.4.368)");
        check(log.startsWith("[RED] GPU driver: Turnip v27.0.0-R3 (Vulkan 1.4.368) is a known broken build") && log.contains("git-c5ef4bdfab") && log.contains("Adreno 7xx"), log);
        for (String t : new String[] {KnownDrivers.pickerText(b), KnownDrivers.notice(b), log, KnownDrivers.NOTICE_TITLE}) check(t.indexOf('—') < 0 && t.indexOf('–') < 0, "no dashes: " + t);
        System.out.println("ok");
    }

    private static String[] lines(Map<String, String> f) {
        String[] out = new String[f.size()];
        int i = 0;
        for (Map.Entry<String, String> e : f.entrySet()) out[i++] = e.getKey() + "=" + e.getValue();
        return out;
    }
}
