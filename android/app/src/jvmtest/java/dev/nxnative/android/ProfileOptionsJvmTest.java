package dev.nxnative.android;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Profile.parseOptions and Profile.buildOptionsEnv on the JVM. Prints "ok" or throws */
public final class ProfileOptionsJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception {
        check(Profile.parseOptions(null).isEmpty(), "null -> empty");
        check(Profile.parseOptions("").isEmpty(), "empty -> empty");
        check(Profile.parseOptions("   ").isEmpty(), "whitespace -> empty");

        String hdWords = "d4b150b29a931cd381e2a30318fc299f,m0x10a4c80,0978c0f2,09a0c0f2~"
                + "d4b150b29a931cd381e2a30318fc299f,m0x10a4c90,88438052,085a8052";
        List<Profile.Option> hd = Profile.parseOptions("DM_P5R_HD|HD resolution|Higher scene resolution|restart|||" + hdWords);
        check(Profile.buildOptionsEnv(hd, e -> false).isEmpty(), "HD off emits no code patches");
        List<String> hdEnv = Profile.buildOptionsEnv(hd, e -> true);
        check(hdEnv.equals(List.of("DM_P5R_HD=1", "DM_OPTION_PATCHES=" + hdWords.replace('~', ';'))), "HD words serialize separately from FPS plans");
        check(!hd.get(0).live && !hdEnv.toString().contains("DM_FPS"), "HD restart option is independent of FPS owned mode");

        String single = "DM_OOT_MAX_RESOLUTION|Max resolution|Force full 3D resolution|restart|DM_OOT_TEXTURE_POOL_X2|OOT_PATCHES";
        List<Profile.Option> s = Profile.parseOptions(single);
        check(s.size() == 1, "single option count: " + s.size());
        Profile.Option opt0 = s.get(0);
        check(opt0.env.equals("DM_OOT_MAX_RESOLUTION"), "env: " + opt0.env);
        check(opt0.label.equals("Max resolution"), "label: " + opt0.label);
        check(opt0.description.equals("Force full 3D resolution"), "desc: " + opt0.description);
        check(!opt0.live, "restart is not live");
        check(opt0.implies.equals("DM_OOT_TEXTURE_POOL_X2"), "implies: " + opt0.implies);
        check(opt0.needs.equals("OOT_PATCHES"), "needs: " + opt0.needs);
        check(opt0.hasImplies(), "hasImplies");
        check(opt0.hasNeeds(), "hasNeeds");
        check(opt0.envLine().equals("DM_OOT_MAX_RESOLUTION=1"), "env line: " + opt0.envLine());

        String backwardCompat = "DM_OLD|Old opt|No extra fields|restart";
        List<Profile.Option> bc = Profile.parseOptions(backwardCompat);
        check(bc.size() == 1 && bc.get(0).implies.isEmpty() && bc.get(0).needs.isEmpty(), "backward compat parsed");

        String liveStr = "DM_LIVE_TEST|Live switch|A live setting|live";
        List<Profile.Option> l = Profile.parseOptions(liveStr);
        check(l.size() == 1 && l.get(0).live, "live parsed as true");

        String multi = "DM_OOT_MAX_RESOLUTION|Max resolution|Force full 3D resolution|restart|DM_OOT_TEXTURE_POOL_X2|OOT_PATCHES;"
                + "DM_OOT_TEXTURE_POOL_X2|Texture pool ×2|Double graphics allocation|restart||OOT_PATCHES;"
                + "DM_OOT_FPS_UNLOCK|Unlocked frame rate|Update step follows elapsed time|restart||OOT_PATCHES";
        List<Profile.Option> m = Profile.parseOptions(multi);
        check(m.size() == 3, "three options parsed: " + m.size());
        check(m.get(1).env.equals("DM_OOT_TEXTURE_POOL_X2") && m.get(1).label.equals("Texture pool ×2"), "second opt");
        check(m.get(1).needs.equals("OOT_PATCHES") && m.get(1).implies.isEmpty(), "second opt needs OOT_PATCHES");
        check(m.get(2).env.equals("DM_OOT_FPS_UNLOCK") && m.get(2).label.equals("Unlocked frame rate"), "third opt");
        check(m.get(2).needs.equals("OOT_PATCHES"), "third opt needs OOT_PATCHES");

        // Profile.findImpliedBy
        Set<String> testEnabled = new HashSet<>();
        check(Profile.findImpliedBy(m, m.get(1), testEnabled::contains) == null, "not implied when max res off");
        testEnabled.add("DM_OOT_MAX_RESOLUTION");
        Profile.Option source = Profile.findImpliedBy(m, m.get(1), testEnabled::contains);
        check(source != null && source.env.equals("DM_OOT_MAX_RESOLUTION"), "implied by max res when max res on");

        // buildOptionsEnv (verifies that implied options are included even if not explicitly in predicate)
        Set<String> enabled = new HashSet<>();
        check(Profile.buildOptionsEnv(m, enabled::contains).isEmpty(), "none enabled -> empty env");
        enabled.add("DM_OOT_MAX_RESOLUTION");
        enabled.add("DM_OOT_FPS_UNLOCK");
        // DM_OOT_TEXTURE_POOL_X2 is not in enabled, but is implied by DM_OOT_MAX_RESOLUTION!
        List<String> env = Profile.buildOptionsEnv(m, enabled::contains);
        check(env.equals(List.of("DM_OOT_MAX_RESOLUTION=1", "DM_OOT_TEXTURE_POOL_X2=1", "DM_OOT_FPS_UNLOCK=1")), "selected options env: " + env);

        // Profile.parse integration
        String line = "0400486024DEC000\tThe Legend of Zelda: Ocarina of Time\t22852\tabcdef012345\t131072 main=aa\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t1\t" + multi;
        List<Profile> profiles = Profile.parse(line);
        check(profiles.size() == 1, "profile parsed");
        Profile p = profiles.get(0);
        check(p.vegetationDetail, "vegetationDetail kept");
        check(p.options.size() == 3, "options count: " + p.options.size());
        check(p.optionsEnv(enabled::contains).equals(env), "optionsEnv");

        // Profile.parse without option column
        String oldLine = "0400486024DEC000\tThe Legend of Zelda: Ocarina of Time\t22852\tabcdef012345\t131072 main=aa\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t1";
        Profile pOld = Profile.parse(oldLine).get(0);
        check(pOld.fpsLimitMax == 60, "legacy profiles keep their 60 fps maximum");
        check(pOld.fpsLocker.isEmpty(), "legacy profiles do not enable Warehouse writes");
        check(pOld.fpsLockerPlan.isEmpty(), "legacy profiles do not enable code plans");
        String[] highRate = new String[35];
        java.util.Arrays.fill(highRate, "");
        highRate[0] = "0100B63016916000"; highRate[1] = "Sandrock"; highRate[2] = "0";
        highRate[15] = "30"; highRate[31] = "120";
        highRate[33] = "a6bd61cfb142e663f59f4d92b4ed0474,r0x100,33008052,f303002a";
        check(Profile.parse(String.join("\t", highRate)).get(0).fpsLockerPlan.equals(highRate[33]), "reviewed code plan survives profile parsing");
        // PEANUTS: alternate game updates can use stock timing without the other build's code plan.
        Profile reviewed = Profile.parse(String.join("\t", highRate)).get(0);
        check(reviewed.fpsLockerPlanMatches("a6bd61cfb142e663f59f4d92b4ed0474"), "reviewed main build gets its FPSLocker code plan");
        check(!reviewed.fpsLockerPlanMatches("8e9978d50bdd20b4c8395a106c27ffde"), "another main build uses game timing");
        check(reviewed.fpsLockerPlanMatches(null) && pOld.fpsLockerPlanMatches("8e9978d50bdd20b4c8395a106c27ffde"), "older packs and profiles keep their timing behavior");
        highRate[32] = "a6bd61cfb142e663f59f4d92b4ed0474,target_f32,0x62d3b68,2,0";
        check(Profile.parse(String.join("\t", highRate)).get(0).fpsLocker.equals(highRate[32]), "reviewed Warehouse rows survive profile parsing");
        check(Profile.parse(String.join("\t", highRate)).get(0).fpsLimitMax == 120, "profile opts in to 120");
        check(Profile.parse(String.join("\t", highRate)).get(0).fpsLimitMin == 20, "default lower cap remains 20");
        // PEANUTS: Present-only rate opt-in and legacy profile parsing.
        Profile present = new Profile("010051F0207B2000", "Tomodachi Life", 0, "", "", "", "");
        present.fpsLimitMax = 120;
        check(!present.optInPresentRate(), "high cap alone does not change the presentation mode");
        present.presentOnly = true;
        check(present.optInPresentRate(), "present-only caps above 60 can opt in from Game owned");
        present.fpsLimit = 30;
        check(!present.optInPresentRate(), "an existing default cap keeps its presentation mode");
        highRate[34] = "30";
        check(Profile.parse(String.join("\t", highRate)).get(0).fpsLimitMin == 30, "reviewed timing correction lower bound");
        highRate[31] = "90";
        check(Profile.parse(String.join("\t", highRate)).get(0).fpsLimitMax == 90, "profile opts in to 90");
        highRate[31] = "144";
        check(Profile.parse(String.join("\t", highRate)).get(0).fpsLimitMax == 60, "unknown maximum is not enabled");
        highRate = java.util.Arrays.copyOf(highRate, 37);
        highRate[31] = "120";
        highRate[35] = "30,60,120";
        highRate[36] = "909e904af78ac1b8deefe97ab2ccdb51,m0xdb8544,00102e1e,00102c1e";
        Profile la = Profile.parse(String.join("\t", highRate)).get(0);
        check(la.fpsPlan120.equals(highRate[36]), "120 launch plan survives serialization");
        check(java.util.Arrays.equals(la.fpsModeCaps(), new int[] {-2, 120, 0, 30}), "only checked LA rates are offered");
        for (int unsupported : new int[] {20, 40, 45, 90}) check(la.fpsMode(unsupported) == 0, "unsupported saved rate falls back to 60");
        check(la.fpsMode(-2) == -2 && la.fpsMode(30) == 30 && la.fpsMode(60) == 0 && la.fpsMode(120) == 120, "supported rates retained");
        for (int low : new int[] {0, 30}) {
            check(la.fpsRestart(low, 120) && la.fpsRestart(120, low), "crossing static timing requires restart");
            check(!la.fpsRestart(0, low), "30 and 60 share original timing");
            List<String> lowEnv = la.fpsModeEnv(low);
            check(lowEnv.contains("DM_FPSLOCKER_PLAN=") && lowEnv.contains("DM_FPSLOCKER="), "low modes have no static timing or memory patch");
            check(lowEnv.contains("DM_VSYNC_HZ=60") && lowEnv.contains("DM_FPS_TARGET=" + (low == 30 ? 30 : 60)), "original controller clock at low modes");
        }
        check(la.fpsRestart(-2, 0) && la.fpsRestart(120, -2) && !la.fpsRestart(120, 120), "owned and unchanged restart policy");
        check(la.fpsModeTarget(120) == 0 && la.fpsModeTarget(0) == 60 && la.fpsModeTarget(30) == 30, "returning to the running mode keeps its original engine target");
        List<String> highEnv = la.fpsModeEnv(120);
        check(highEnv.contains("DM_FPSLOCKER_PLAN=" + highRate[36]) && highEnv.contains("DM_FPSLOCKER=" + highRate[32]), "120 applies code and typed governor");
        for (String e : new String[] {"DM_FPS_TARGET=0", "DM_FPS_LIMIT=120", "DM_VSYNC_HZ=120", "DM_PRESENT_HZ=120", "DM_FPSLOCKER_INTERVAL=1"}) check(highEnv.contains(e), "120 launch environment " + e);
        // PEANUTS: explicit native modes set the engine target even after the static 120 fps plan is removed.
        check(la.usesFpsModeTarget(), "static 120 mode retains its engine target policy");
        highRate[36] = "";
        Profile dynamic = Profile.parse(String.join("\t", highRate)).get(0);
        check(!dynamic.usesFpsModeTarget(), "dynamic FPSLocker modes drive their own engine timing");
        highRate[0] = "01006BB00C6F0000"; highRate[31] = "60";
        highRate[32] = ""; highRate[33] = ""; highRate[35] = "30,60";
        Profile nativeModes = Profile.parse(String.join("\t", highRate)).get(0);
        check(nativeModes.usesFpsModeTarget(), "Link's Awakening selects its native engine mode");
        check(nativeModes.fpsModeTarget(30) == 30 && nativeModes.fpsModeTarget(0) == 60, "30 and 60 select matching engine targets");
        check(nativeModes.fpsModeTarget(120) == 60, "old 120 preference selects the 60 fps engine mode");
        check(nativeModes.fpsRestart(-2, 30) && !nativeModes.fpsRestart(30, 0), "Game owned still requires restart; 30 and 60 switch live");
        check(!pOld.usesFpsModeTarget(), "ordinary profiles keep their existing limiter policy");
        check(pOld.fpsModes.isEmpty() && pOld.fpsPlan120.isEmpty(), "old profile columns retain legacy launch policy");
        check(pOld.options.isEmpty(), "old line has empty options");

        // Profile.hasFixupMarker & isSupported
        check(!Profile.hasFixupMarker(null, "OOT_PATCHES"), "hasFixupMarker null");
        check(!Profile.hasFixupMarker(null, null), "hasFixupMarker null marker");
        java.io.File nonExistent = new java.io.File("does_not_exist_pack_folder");
        check(!Profile.hasFixupMarker(nonExistent, "OOT_PATCHES"), "hasFixupMarker non-existent");
        java.io.File tmpDir = java.nio.file.Files.createTempDirectory("pack_test").toFile();
        try {
            check(!Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker without main.fixups.tsv");
            java.io.File fixups = new java.io.File(tmpDir, "main.fixups.tsv");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# FPS_STATE_OFF 0x14000\n");
            check(!Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker without OOT_PATCHES row");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# OOT_PATCHES 0\n");
            check(!Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker with OOT_PATCHES 0");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# OOT_PATCHES 1\n");
            check(Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker with OOT_PATCHES 1");
            java.nio.file.Files.writeString(fixups.toPath(), "# TARGET_ANDROID 1\n# OOT_PATCHES\t1\n");
            check(Profile.hasFixupMarker(tmpDir, "OOT_PATCHES"), "hasFixupMarker with OOT_PATCHES tab 1");

            // isSupported
            Profile.Option optWithNeeds = new Profile.Option("DM_A", "A", "Desc", false, "", "OOT_PATCHES");
            check(optWithNeeds.isSupported(tmpDir), "optWithNeeds supported when marker present");
            Profile.Option optOtherNeeds = new Profile.Option("DM_B", "B", "Desc", false, "", "NON_EXISTENT_MARKER");
            check(!optOtherNeeds.isSupported(tmpDir), "optOtherNeeds not supported when marker missing");
            Profile.Option optNoNeeds = new Profile.Option("DM_C", "C", "Desc", false, "", "");
            check(optNoNeeds.isSupported(tmpDir), "optNoNeeds supported with packDir");
            check(optNoNeeds.isSupported(null), "optNoNeeds supported with null packDir");
        } finally {
            new java.io.File(tmpDir, "main.fixups.tsv").delete();
            tmpDir.delete();
        }

        System.out.println("ok");
    }
}
