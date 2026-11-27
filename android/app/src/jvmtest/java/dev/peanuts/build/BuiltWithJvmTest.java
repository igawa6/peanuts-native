// PEANUTS: BuiltWith on the JVM: a library version's release and what the game's sheet says of it (this app version, one the upgrade takes, one made again
// once while it plays, an older one, an unknown one), and which of a pack's libraries it reads. peanuts/tests/test_built_with.py compiles and runs it. CUR and
// DM are made up: nothing here depends on this tree's DMSH_VK_CHAIN or DMSH_CHAIN; V18 / DM18 are 0.1.8's, the end of the upgrade tool's hops
package dev.peanuts.build;

import java.io.File;
import java.io.IOException;

public final class BuiltWithJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    // PEANUTS begin: 0.1.9, a whole upgrade path to this version with its DMSH_CHAIN, and the REMAKE line for 0.1.7 and 0.1.8
    static final String CUR = "aaaaaaaaaaaa", DM = "bbbbbbbbbbbb", V18 = "327c6383b614", DM18 = "cdcf140b1558", GAME = "0100ABCD00000000", ABI = "111111111111";

    static void lib(File pack, String version, String game) throws IOException { ShaderUpgradeJvmTest.lib(new File(pack, "shaders/v-" + version + "/dmsh_vk.lib"), version, game, ABI, 0); }

    public static void main(String[] args) throws IOException {
        // the table, for an app version the upgrade has no path to (0.1.9: another translator front half, no hop to its version)
        check(BuiltWith.of(CUR, CUR, DM).kind == BuiltWith.Kind.CURRENT && BuiltWith.of(CUR, CUR, DM).release == null, "this app version");
        for (String[] r : new String[][] {{"c679ddfbc5db", "0.1.7"}, {V18, "0.1.8"}})
            check(BuiltWith.of(r[0], CUR, DM).kind == BuiltWith.Kind.REMAKE && r[1].equals(BuiltWith.of(r[0], CUR, DM).release), r[1] + " is made again");
        String[][] old = {{"4b1886941d10", "0.1.0"}, {"c3959e8b8f88", "0.1.1"}, {"4d3a924dcd04", "0.1.3"}, {"c13dc8b37b0f", "0.1.4"}, {"dfebef5d68a9", "0.1.5"}, {"e084efc69b46", "0.1.6"}};
        for (String[] o : old) check(BuiltWith.of(o[0], CUR, DM).kind == BuiltWith.Kind.OLD && o[1].equals(BuiltWith.of(o[0], CUR, DM).release), o[0] + " is " + o[1]);
        for (String c : new String[] {"c9337a3f4e01", "68162be2976c", "66d1dd2cd5ff", "", null}) check(BuiltWith.of(c, CUR, DM).kind == BuiltWith.Kind.UNKNOWN, c + ": unknown");
        check(BuiltWith.of(V18, V18, DM18).kind == BuiltWith.Kind.CURRENT, "the build's own version wins over the table");

        // the upgrade's path: whole, to this version, and the same DMSH_CHAIN
        check(BuiltWith.of("c679ddfbc5db", V18, DM18).kind == BuiltWith.Kind.FAST, "on 0.1.8, 0.1.7 upgrades (three hops)");
        check(BuiltWith.of("c679ddfbc5db", V18, DM).kind == BuiltWith.Kind.REMAKE, "another DMSH_CHAIN: no upgrade");
        check(BuiltWith.of("e084efc69b46", V18, DM18).kind == BuiltWith.Kind.OLD, "no hop from 0.1.6, though its DMSH_CHAIN is 0.1.8's");
        check(BuiltWith.upgradable("c679ddfbc5db", DM18, V18, DM18) && BuiltWith.upgradable("68162be2976c", DM18, V18, DM18), "paths to 0.1.8");
        check(!BuiltWith.upgradable(V18, DM18, CUR, DM18), "the hops stop at 0.1.8: none to a later version");
        check(!BuiltWith.upgradable("c679ddfbc5db", DM18, "68162be2976c" + "x", DM18), "a target the hops never reach is none");
        check(!BuiltWith.upgradable("c679ddfbc5db", null, V18, DM18), "no DMSH_CHAIN: no upgrade");

        // the words
        check("Built with 0.1.3".equals(BuiltWith.label(BuiltWith.of("4d3a924dcd04", CUR, DM))), "old label");
        check(("This game's graphics were prepared with Peanuts Native 0.1.3. It still plays, but it misses the graphics fixes since then. Rebuild it to get them.")
                .equals(BuiltWith.warning(BuiltWith.of("4d3a924dcd04", CUR, DM))), "old warning");
        String note = "This app version makes shaders in a new way, so this game's shaders are made again once, with Rebuild or Update shaders. It plays with the shaders it has until then.";
        for (String c : new String[] {"c679ddfbc5db", V18}) {
            BuiltWith.Result r = BuiltWith.of(c, CUR, DM);
            check(("Built with " + r.release).equals(BuiltWith.label(r)) && BuiltWith.warning(r) == null && note.equals(BuiltWith.note(r)), c + ": a plain line, no warning");
        }
        check("Built with 0.1.7".equals(BuiltWith.label(BuiltWith.of("c679ddfbc5db", V18, DM18))) && BuiltWith.warning(BuiltWith.of("c679ddfbc5db", V18, DM18)) == null
                && BuiltWith.note(BuiltWith.of("c679ddfbc5db", V18, DM18)) == null, "a quick upgrade is quiet");
        check("Built with this app version".equals(BuiltWith.label(BuiltWith.of(CUR, CUR, DM))) && BuiltWith.warning(BuiltWith.of(CUR, CUR, DM)) == null
                && BuiltWith.note(BuiltWith.of(CUR, CUR, DM)) == null, "current is quiet");
        check(BuiltWith.label(BuiltWith.of("66d1dd2cd5ff", CUR, DM)) == null && BuiltWith.warning(BuiltWith.of("66d1dd2cd5ff", CUR, DM)) == null, "unknown says nothing");
        for (String[] o : old) check(BuiltWith.note(BuiltWith.of(o[0], CUR, DM)) == null, o[1] + ": the warning, no note");
        for (String w : new String[] {BuiltWith.warning(BuiltWith.of("4d3a924dcd04", CUR, DM)), note}) check(!w.contains("\u2014") && !w.contains("("), "voice");

        // a pack's libraries
        File root = new File(args.length > 0 ? args[0] : "built-with-run");
        File p = new File(root, "none");
        check(BuiltWith.of(p, GAME, CUR, DM).kind == BuiltWith.Kind.UNKNOWN, "no shaders folder");
        check(BuiltWith.of(null, GAME, CUR, DM).kind == BuiltWith.Kind.UNKNOWN, "no pack");

        p = new File(root, "current");
        lib(p, CUR, GAME); lib(p, "e084efc69b46", GAME);
        check(BuiltWith.of(p, GAME, CUR, DM).kind == BuiltWith.Kind.CURRENT, "this version's library wins");
        check(BuiltWith.of(p, GAME.toLowerCase(), CUR, DM).kind == BuiltWith.Kind.CURRENT, "title IDs in either case");

        p = new File(root, "current-other-game");
        lib(p, CUR, "0100FFFF00000000"); lib(p, "e084efc69b46", GAME);
        check(BuiltWith.of(p, GAME, CUR, DM).kind == BuiltWith.Kind.UNKNOWN, "this version's path with another game's library: nothing");

        p = new File(root, "old");
        lib(p, "4d3a924dcd04", GAME); lib(p, "e084efc69b46", GAME); lib(p, "c13dc8b37b0f", "0100FFFF00000000");
        BuiltWith.Result r = BuiltWith.of(p, GAME, CUR, DM);
        check(r.kind == BuiltWith.Kind.OLD && "0.1.6".equals(r.release), "the newest of the game's older libraries: " + r.release);

        p = new File(root, "remake");
        lib(p, "4d3a924dcd04", GAME); lib(p, "c679ddfbc5db", GAME); lib(p, V18, GAME);
        r = BuiltWith.of(p, GAME, CUR, DM);
        check(r.kind == BuiltWith.Kind.REMAKE && "0.1.8".equals(r.release), "0.1.8 beside 0.1.7 and 0.1.3: " + r.release);

        p = new File(root, "fast");
        lib(p, "4d3a924dcd04", GAME); lib(p, "c679ddfbc5db", GAME);
        r = BuiltWith.of(p, GAME, V18, DM18);
        check(r.kind == BuiltWith.Kind.FAST && "0.1.7".equals(r.release), "on 0.1.8, 0.1.7 beside 0.1.3");

        p = new File(root, "old-and-unknown");
        lib(p, "4d3a924dcd04", GAME); lib(p, "123456789abc", GAME);
        check(BuiltWith.of(p, GAME, CUR, DM).kind == BuiltWith.Kind.UNKNOWN, "an unknown library could be newer: no warning");

        p = new File(root, "fast-and-unknown");
        lib(p, "c679ddfbc5db", GAME); lib(p, "123456789abc", GAME);
        check(BuiltWith.of(p, GAME, V18, DM18).kind == BuiltWith.Kind.FAST, "0.1.7 beside an unknown one is still quiet");
        check(BuiltWith.of(p, GAME, CUR, DM).kind == BuiltWith.Kind.REMAKE, "and the plain line, no warning, when it is made again");

        p = new File(root, "misplaced");
        ShaderUpgradeJvmTest.lib(new File(p, "shaders/v-4d3a924dcd04/dmsh_vk.lib"), "c679ddfbc5db", GAME, ABI, 0);
        check(BuiltWith.of(p, GAME, CUR, DM).kind == BuiltWith.Kind.UNKNOWN, "a header whose version is not its folder's is skipped");

        p = new File(root, "short");
        File s = new File(p, "shaders/v-4d3a924dcd04/dmsh_vk.lib");
        s.getParentFile().mkdirs();
        java.nio.file.Files.write(s.toPath(), new byte[16]);
        check(BuiltWith.of(p, GAME, CUR, DM).kind == BuiltWith.Kind.UNKNOWN, "an unreadable header says nothing");
        System.out.println("ok");
    }
    // PEANUTS end
}
