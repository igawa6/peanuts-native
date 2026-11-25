// PEANUTS: new file (the stats page's frame rate cap for a game on the frame limit list whose cap applies at the next start)
package dev.nxnative.android;

/**
 * Star Ocean The Second Story R and Hogwarts Legacy (opt-in present pacing: fps_limit_present with no fps_limit) on the JVM: they start at 60 fps with no pick
 * (FrameRateCaps.defaultLimit, Game owned before 0.1.8's release, now a choice), as Kirby and the Forgotten Land (fps_owned_default) and Animal Crossing do, 30 for
 * a game whose caps have no 60; the stats page offers their 60; a 60 picked while the game runs Game owned waits for the next start (so the pop-up opens again with
 * its note); the stored value keeps its meaning at that start (the list marker, Prefs.migrateFpsCap); FrameRateCaps.savedIn finds it in a settings file as Android
 * writes one. Prints "ok" or throws
 */
public final class StatsCapJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    /** A profile line as build.gradle writes it: columns 15 (fps_limit), 17 (fps_limit_present), 31 (fps_limit_max), 35 (fps_modes), 38 (fps_owned_default) */
    static Profile line(String id, String name, String limit, String present, String max, String modes, String ownedDefault) {
        String[] c = new String[43];
        java.util.Arrays.fill(c, "");
        c[0] = id; c[1] = name; c[2] = "0"; c[3] = "x"; c[4] = "v0";
        c[15] = limit; c[17] = present; c[31] = max; c[35] = modes; c[38] = ownedDefault;
        return Profile.parse(String.join("\t", c)).get(0);
    }
    static Profile line(String id, String name, String max, String modes) { return line(id, name, "", "1", max, modes, ""); }

    public static void main(String[] args) {
        Profile so2r = line("010065301A2E0000", "STAR OCEAN THE SECOND STORY R", "", "20,30,60");
        Profile hogwarts = line("0100F7E00C70E000", "Hogwarts Legacy", "60", "");
        for (Profile p : new Profile[] {so2r, hogwarts}) {
            String n = p.name;
            check(FrameRateCaps.limited(p) && p.optInPresentRate() && FrameRateCaps.ownedBefore(p), n + ": the frame limit list, Game owned by default before 0.1.8");
            check(FrameRateCaps.defaultLimit(p) == 0, n + ": 60 fps with no pick (0.1.8)");
            check(FrameRateCaps.contains(FrameRateCaps.menu(), 0) && FrameRateCaps.contains(FrameRateCaps.paceOffered(p, 60), 0), n + ": the stats page offers 60 fps (stored 0)");
            check(p.fpsRestart(FrameRateCaps.GAME_OWNED, 0), n + ": a 60 picked while the game runs Game owned applies at the next start");
            check(!p.fpsRestart(0, 30), n + ": 60 to 30 applies at once");
            // the pick as Prefs.fpsCap stores it (the cap and the list marker), read at the next start: nothing to move, the 60 stays
            check(FrameRateCaps.migrateSaved(p, FrameRateCaps.list(p), 0) == null, n + ": the stored 60 is kept at the next start");
            check(FrameRateCaps.normalize(0, FrameRateCaps.paceMax(p)) == 0, n + ": the stored 0 reads as 60");
        }
        // the other games that started Game owned: Kirby (fps_owned_default, fps_limit 60), Animal Crossing (fps_modes 60: its 60 starts the variant), Diablo III (120)
        Profile kirby = line("01004D300C5AE000", "Kirby and the Forgotten Land", "60", "", "120", "", "1");
        Profile acnh = line("01006F8002326000", "Animal Crossing: New Horizons", "", "60");
        Profile diablo = line("01001B300B9BE000", "Diablo III: Eternal Collection", "120", "");
        for (Profile p : new Profile[] {kirby, acnh, diablo}) check(FrameRateCaps.ownedBefore(p) && FrameRateCaps.defaultLimit(p) == 0, p.name + ": 60 fps with no pick");
        check(!kirby.optInPresentRate() && kirby.ownedByDefault, "Kirby: fps_owned_default, not opt-in present pacing");
        check(FrameRateCaps.defaultLimit(line("0100000000000001", "thirty", "", "30")) == 30, "caps with no 60: 30");
        check(FrameRateCaps.defaultLimit(line("0100000000000002", "twenty", "", "20")) == FrameRateCaps.GAME_OWNED, "neither 60 nor 30: Game owned");
        Profile plain = line("0100000000000003", "limit 30", "30", "", "", "", "");
        check(!FrameRateCaps.ownedBefore(plain) && !FrameRateCaps.ownedBefore(null), "a profile's own fps_limit stays its default");
        check(so2r.fpsMode(0) == 0 && so2r.fpsMode(FrameRateCaps.GAME_OWNED) == FrameRateCaps.GAME_OWNED, "Star Ocean: 60 and Game owned are its modes");

        // the settings file as Android writes it (XmlUtils.writeMapXml)
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"fps_cap_list@010065301A2E0000\">limit</string>\n"
                + "    <int name=\"fps_cap@010065301A2E0000\" value=\"0\" />\n    <int name=\"fps_cap@0100F7E00C70E000\" value=\"-2\" />\n</map>\n";
        check(FrameRateCaps.savedIn(xml, "fps_cap@010065301A2E0000", 0), "the stored 60 is found");
        check(FrameRateCaps.savedIn(xml, "fps_cap@0100F7E00C70E000", FrameRateCaps.GAME_OWNED), "a stored Game owned is found");
        check(!FrameRateCaps.savedIn(xml, "fps_cap@0100F7E00C70E000", 0), "another value is not the pick");
        check(!FrameRateCaps.savedIn(xml, "fps_cap@01006F8002326000", 0), "another game's key is not the pick");
        check(!FrameRateCaps.savedIn(null, "fps_cap@010065301A2E0000", 0), "a file not read holds nothing");
        System.out.println("ok");
    }
}
