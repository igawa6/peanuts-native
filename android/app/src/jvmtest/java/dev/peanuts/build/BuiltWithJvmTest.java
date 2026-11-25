// PEANUTS: BuiltWith on the JVM: a library version's release and what the game's sheet says of it (this app version, one the upgrade takes, an older one,
// an unknown one), and which of a pack's libraries it reads. peanuts/tests/test_built_with.py compiles and runs it. CUR is made up: nothing here depends on
// this tree's DMSH_VK_CHAIN
package dev.peanuts.build;

import java.io.File;
import java.io.IOException;

public final class BuiltWithJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static final String CUR = "aaaaaaaaaaaa", GAME = "0100ABCD00000000", ABI = "111111111111";

    static void lib(File pack, String version, String game) throws IOException { ShaderUpgradeJvmTest.lib(new File(pack, "shaders/v-" + version + "/dmsh_vk.lib"), version, game, ABI, 0); }

    public static void main(String[] args) throws IOException {
        // the table
        check(BuiltWith.of(CUR, CUR).kind == BuiltWith.Kind.CURRENT && BuiltWith.of(CUR, CUR).release == null, "this app version");
        check(BuiltWith.of("c679ddfbc5db", CUR).kind == BuiltWith.Kind.FAST && "0.1.7".equals(BuiltWith.of("c679ddfbc5db", CUR).release), "0.1.7 upgrades");
        for (String c : new String[] {"c9337a3f4e01", "68162be2976c"}) check(BuiltWith.of(c, CUR).kind == BuiltWith.Kind.FAST && BuiltWith.of(c, CUR).release == null, c + ": this cycle");
        String[][] old = {{"4b1886941d10", "0.1.0"}, {"c3959e8b8f88", "0.1.1"}, {"4d3a924dcd04", "0.1.3"}, {"c13dc8b37b0f", "0.1.4"}, {"dfebef5d68a9", "0.1.5"}, {"e084efc69b46", "0.1.6"}};
        for (String[] o : old) check(BuiltWith.of(o[0], CUR).kind == BuiltWith.Kind.OLD && o[1].equals(BuiltWith.of(o[0], CUR).release), o[0] + " is " + o[1]);
        for (String c : new String[] {"327c6383b614", "66d1dd2cd5ff", "", null}) check(BuiltWith.of(c, CUR).kind == BuiltWith.Kind.UNKNOWN, c + ": unknown");
        check(BuiltWith.of("327c6383b614", "327c6383b614").kind == BuiltWith.Kind.CURRENT, "the build's own version wins over the table");

        // the words
        check("Built with 0.1.3".equals(BuiltWith.label(BuiltWith.of("4d3a924dcd04", CUR))), "old label");
        check(("This game's graphics were prepared with Peanuts Native 0.1.3. It still plays, but it misses the graphics fixes since then. Rebuild it to get them.")
                .equals(BuiltWith.warning(BuiltWith.of("4d3a924dcd04", CUR))), "old warning");
        check("Built with 0.1.7".equals(BuiltWith.label(BuiltWith.of("c679ddfbc5db", CUR))) && BuiltWith.warning(BuiltWith.of("c679ddfbc5db", CUR)) == null, "0.1.7 is quiet");
        check("Built with this app version".equals(BuiltWith.label(BuiltWith.of(CUR, CUR))) && BuiltWith.warning(BuiltWith.of(CUR, CUR)) == null, "current is quiet");
        check(BuiltWith.label(BuiltWith.of("327c6383b614", CUR)) == null && BuiltWith.warning(BuiltWith.of("327c6383b614", CUR)) == null, "unknown says nothing");
        for (String[] o : old) check(!BuiltWith.warning(BuiltWith.of(o[0], CUR)).contains("—") && !BuiltWith.warning(BuiltWith.of(o[0], CUR)).contains("("), "voice");

        // a pack's libraries
        File root = new File(args.length > 0 ? args[0] : "built-with-run");
        File p = new File(root, "none");
        check(BuiltWith.of(p, GAME, CUR).kind == BuiltWith.Kind.UNKNOWN, "no shaders folder");
        check(BuiltWith.of(null, GAME, CUR).kind == BuiltWith.Kind.UNKNOWN, "no pack");

        p = new File(root, "current");
        lib(p, CUR, GAME); lib(p, "e084efc69b46", GAME);
        check(BuiltWith.of(p, GAME, CUR).kind == BuiltWith.Kind.CURRENT, "this version's library wins");
        check(BuiltWith.of(p, GAME.toLowerCase(), CUR).kind == BuiltWith.Kind.CURRENT, "title IDs in either case");

        p = new File(root, "current-other-game");
        lib(p, CUR, "0100FFFF00000000"); lib(p, "e084efc69b46", GAME);
        check(BuiltWith.of(p, GAME, CUR).kind == BuiltWith.Kind.UNKNOWN, "this version's path with another game's library: nothing");

        p = new File(root, "old");
        lib(p, "4d3a924dcd04", GAME); lib(p, "e084efc69b46", GAME); lib(p, "c13dc8b37b0f", "0100FFFF00000000");
        BuiltWith.Result r = BuiltWith.of(p, GAME, CUR);
        check(r.kind == BuiltWith.Kind.OLD && "0.1.6".equals(r.release), "the newest of the game's older libraries: " + r.release);

        p = new File(root, "fast");
        lib(p, "4d3a924dcd04", GAME); lib(p, "c679ddfbc5db", GAME);
        r = BuiltWith.of(p, GAME, CUR);
        check(r.kind == BuiltWith.Kind.FAST && "0.1.7".equals(r.release), "0.1.7 beside 0.1.3");

        p = new File(root, "cycle");
        lib(p, "c679ddfbc5db", GAME); lib(p, "68162be2976c", GAME);
        r = BuiltWith.of(p, GAME, CUR);
        check(r.kind == BuiltWith.Kind.FAST && r.release == null, "this cycle's build beside 0.1.7");

        p = new File(root, "old-and-unknown");
        lib(p, "4d3a924dcd04", GAME); lib(p, "123456789abc", GAME);
        check(BuiltWith.of(p, GAME, CUR).kind == BuiltWith.Kind.UNKNOWN, "an unknown library could be newer: no warning");

        p = new File(root, "fast-and-unknown");
        lib(p, "c679ddfbc5db", GAME); lib(p, "123456789abc", GAME);
        check(BuiltWith.of(p, GAME, CUR).kind == BuiltWith.Kind.FAST, "0.1.7 beside an unknown one is still quiet");

        p = new File(root, "misplaced");
        ShaderUpgradeJvmTest.lib(new File(p, "shaders/v-4d3a924dcd04/dmsh_vk.lib"), "c679ddfbc5db", GAME, ABI, 0);
        check(BuiltWith.of(p, GAME, CUR).kind == BuiltWith.Kind.UNKNOWN, "a header whose version is not its folder's is skipped");

        p = new File(root, "short");
        File s = new File(p, "shaders/v-4d3a924dcd04/dmsh_vk.lib");
        s.getParentFile().mkdirs();
        java.nio.file.Files.write(s.toPath(), new byte[16]);
        check(BuiltWith.of(p, GAME, CUR).kind == BuiltWith.Kind.UNKNOWN, "an unreadable header says nothing");
        System.out.println("ok");
    }
}
