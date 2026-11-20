// PEANUTS: DriverMatch on the JVM (peanuts/tests/test_driver_page.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

public final class DriverMatchJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    private static TreeSet<String> set(String... f) { return new TreeSet<>(Arrays.asList(f)); }

    public static void main(String[] a) {
        // a file's own name: a family, Turnip's Gen8, a Snapdragon 8 Gen 2 build, a model with its A
        eq("[8xx]", DriverMatch.families("turnip_a8xx"));
        eq("[8xx]", DriverMatch.families("Turnip_Gen8_V39_Patched"));
        eq("[7xx]", DriverMatch.families("turnip_Oct-12-2025_bbdd688_8g2_ui_glitch"));
        eq("[7xx]", DriverMatch.families("turnip_Sep-09-2025_dcfa4fa_oneui7_8gen2"));
        eq("[7xx]", DriverMatch.families("Balemuni_Apex_v2_ULTIMATE_SD8Gen2"));
        eq("[8xx]", DriverMatch.families("a8xx_0x5_handle with an a830v2 descriptor"));
        eq("[7xx]", DriverMatch.families("Adreno (TM) 740"));
        // driver versions, dates and commits are no GPU
        eq("[]", DriverMatch.families("Turnip_v26.0.0_R8_Gmem"));
        eq("[]", DriverMatch.families("Qualcomm_840_adpkg"));
        eq("[]", DriverMatch.families("adreno-driver-842.8"));
        eq("[]", DriverMatch.families("v891.14"));
        eq("[]", DriverMatch.families("891.11"));
        eq("[]", DriverMatch.families("turnip_mrpurple_T30-toasted.adpkg"));

        // notes: what a release says it is for, not a GPU mentioned in passing
        eq("[7xx]", DriverMatch.noted("Qualcomm drivers v840 extracted from Meta Ray-Ban Glasses.\n\nNOTES\n\n- For a7xx. Maybe a6xx."));
        eq("[8xx]", DriverMatch.noted("Qualcomm Driver v891.14 for Adreno A8xx\nCredits to OnickerMeow"));
        eq("[6xx, 7xx, 8xx]", DriverMatch.noted("• Turnip Driver Unified MrPurple\n• Unified: A6XX, A7XX, A8XX"));
        eq("[]", DriverMatch.noted("a710 and 720 can use regular or Gmem. GameHub's 842.8 Adreno 8xx Driver"));

        // a release's files: the general build beside a named one claims nothing; else the release, its notes, the repository
        List<String> r8 = Arrays.asList("turnip_a8xx.zip", "Turnip_v26.0.0_R8.zip", "Turnip_v26.0.0_R8_Gmem.zip");
        String r8notes = "## The a8xx support included is **very** beta.\n- a710 and 720 can use regular or Gmem.";
        eq("[8xx]", DriverMatch.forFile("turnip_a8xx.zip", r8, "v26.0.0 - Revision 8 + super beta 8xx support", r8notes, "K11MCH1/AdrenoToolsDrivers"));
        eq("[]", DriverMatch.forFile("Turnip_v26.0.0_R8.zip", r8, "v26.0.0 - Revision 8 + super beta 8xx support", r8notes, "K11MCH1/AdrenoToolsDrivers"));
        eq("[7xx]", DriverMatch.forFile("Qualcomm_840_adpkg.zip", List.of("Qualcomm_840_adpkg.zip"), "Qualcomm Driver v840", "- For a7xx. Maybe a6xx.", "K11MCH1/AdrenoToolsDrivers"));
        eq("[8xx]", DriverMatch.forFile("adreno-driver-842.8.zip", List.of("adreno-driver-842.8.zip"), "842.8", "GameHub's 842.8 Adreno 8xx Driver", "crueter/GameHub-8Elite-Drivers"));
        eq("[6xx, 7xx, 8xx]", DriverMatch.forFile("turnip_mrpurple_T30-toasted.adpkg.zip", List.of("turnip_mrpurple_T30-toasted.adpkg.zip"), "turnip_mrpurple_T30-toasted.adpkg",
                "• Unified: A6XX, A7XX, A8XX", "MrPurple666/purple-turnip"));
        eq("[]", DriverMatch.forFile("mainline-turnip-V32.zip", List.of("mainline-turnip-sync-V32.zip", "mainline-turnip-V32.zip"), "Mainline Turnip v32",
                "Built from mesa-unified (turnip/gen8 branch).", "whitebelyash/freedreno_turnip-CI"));

        // this phone, and what is said about a file
        eq("7xx", DriverMatch.family("Adreno 740"));
        eq("8xx", DriverMatch.family("Adreno (TM) 830"));
        eq("mali", DriverMatch.family("Mali-G715"));
        eq("mali", DriverMatch.family("Immortalis-G925"));
        eq(null, DriverMatch.family(null));
        eq("Adreno 7xx", DriverMatch.words(set("7xx")));
        eq("Adreno 6xx, 7xx and 8xx", DriverMatch.words(set("6xx", "7xx", "8xx")));
        eq("", DriverMatch.words(set()));
        eq(true, DriverMatch.otherGpu(set("8xx"), "7xx"));
        eq(false, DriverMatch.otherGpu(set("7xx", "8xx"), "7xx"));
        eq(false, DriverMatch.otherGpu(set(), "7xx"));
        eq(false, DriverMatch.otherGpu(set("8xx"), null));
        String panvk = "mali-panvk-27.0.0-devel-r10.zip";
        TreeSet<String> panvkFamilies = DriverMatch.forFile(panvk, List.of(panvk), "PanVK 1.6.0", "", "JimVulkan/mali-panvk");
        eq("Mali and Immortalis", DriverMatch.words(panvkFamilies));
        eq(true, DriverMatch.otherGpu(panvkFamilies, DriverMatch.family("Adreno 740")));
        eq(false, DriverMatch.otherGpu(panvkFamilies, DriverMatch.family("Mali-G715")));
        String universal = "Balemuni_Apex_v2_UNIVERSAL.zip";
        eq("[6xx, 7xx, 8xx]", DriverMatch.forFile(universal, List.of(universal, "Balemuni_Apex_v2_ULTIMATE_SD8Gen2.zip"),
                "Balemuni Apex V2", "", "Balemuni/Balemunis-Aurora"));
        System.out.println("ok " + n);
    }
}
