// PEANUTS: Settings › Users (profile pictures, Who's playing?)
package dev.peanuts.users;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** UserPictures and WhoPlays on the JVM (peanuts/tests/test_users_page.py): the pictures file, what it refuses, the decision at a game's start */
public final class UserPicturesJvmTest {
    static int fails;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    static final String A = "78626c000000000153574e4154495645", B = "0123456789abcdef0123456789abcdef", C = "fedcba9876543210fedcba9876543210";
    static final String MII = "00112233445566778899aabbccddeeff";

    public static void main(String[] a) throws Exception {
        pictures();
        whoPlays();
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("PASS");
    }

    static void pictures() throws Exception {
        File dir = Files.createTempDirectory("users").toFile();
        UserPictures.Picture p = UserPictures.of(dir, A, 0);
        check(p.none() && p.background == 0 && UserPictures.of(dir, B, 9).background == 1, "no file: no picture, the background of the user's place");
        UserPictures.set(dir, A, UserPictures.mii(MII, 2));
        UserPictures.set(dir, B, UserPictures.firmware("Mario.szs", 7));
        p = UserPictures.of(dir, A, 0);
        check(MII.equals(p.mii()) && p.firmware() == null && p.background == 2 && p.color() == UserPictures.BACKGROUNDS[2], "a Mii as the picture, its background");
        check("Mario.szs".equals(UserPictures.of(dir, B, 0).firmware()), "a firmware picture by its file name");
        String text = new String(Files.readAllBytes(UserPictures.file(dir).toPath()), StandardCharsets.UTF_8);
        check(text.equals(A + "\tmii:" + MII + "\t2\n" + B + "\tfw:Mario.szs\t7\n"), "the file: a line a user");
        check(UserPictures.clean("mii:XYZ").isEmpty() && UserPictures.clean("fw:../x").isEmpty() && UserPictures.clean("fw:a/b").isEmpty() && UserPictures.clean("http://x").isEmpty(),
                "a source that is not a Mii ID or a plain name is none");
        check(new UserPictures.Picture("", -1).background == 7 && new UserPictures.Picture("", 8).background == 0, "a background out of range wraps");
        UserPictures.forgetMii(dir, MII);
        p = UserPictures.of(dir, A, 0);
        check(p.none() && p.background == 2, "a deleted Mii: the user keeps the background, shows no picture");
        UserPictures.remove(dir, B);
        check(UserPictures.of(dir, B, 3).none() && UserPictures.of(dir, B, 3).background == 3, "a removed user's line goes");
        Files.write(UserPictures.file(dir).toPath(), ("junk\n" + C + "\tmii:" + MII + "\tx\n").getBytes(StandardCharsets.UTF_8));
        check(MII.equals(UserPictures.of(dir, C, 0).mii()) && UserPictures.of(dir, C, 0).background == 0, "a broken line is skipped, a broken background is the first");
    }

    static void whoPlays() {
        List<String> one = Arrays.asList(A), two = Arrays.asList(A, B), three = Arrays.asList(A, B, C);
        WhoPlays.Decision d = WhoPlays.decide(one, A, WhoPlays.ASK, null, null, true);
        check(!d.ask && A.equals(d.uid), "one user: never asked");
        d = WhoPlays.decide(two, A, WhoPlays.ASK, null, null, true);
        check(d.ask && A.equals(d.uid), "two users: asked, the user games start with first");
        d = WhoPlays.decide(three, A, WhoPlays.ASK, null, C, true);
        check(d.ask && C.equals(d.uid), "the game's last player picked already");
        d = WhoPlays.decide(three, A, WhoPlays.ASK, B, C, true);
        check(!d.ask && B.equals(d.uid), "Always B for this game: not asked");
        d = WhoPlays.decide(two, A, WhoPlays.ASK, C, null, true);
        check(d.ask && A.equals(d.uid), "an Always user that was removed is ignored");
        d = WhoPlays.decide(two, B, WhoPlays.DONT_ASK, null, A, true);
        check(!d.ask && B.equals(d.uid), "Don't ask: the user games start with");
        d = WhoPlays.decide(two, C, WhoPlays.ASK, null, null, true);
        check(d.ask && A.equals(d.uid), "a start user that is gone: the first");
        // a start in the app plays as the user playing now, without a question, whatever the game's last user
        d = WhoPlays.decide(three, B, WhoPlays.ASK, null, C, false);
        check(!d.ask && d.uid.equals(B), "a start in the app: the user playing now");
        // when the app opens: a question with two or more users and the setting on, the user playing now picked
        check(!WhoPlays.atAppStart(one, A, WhoPlays.ASK).ask, "the app opens, one user: no question");
        d = WhoPlays.atAppStart(three, B, WhoPlays.ASK);
        check(d.ask && d.uid.equals(B), "the app opens, three users: asks, the user playing now picked");
        check(!WhoPlays.atAppStart(two, A, WhoPlays.DONT_ASK).ask, "the app opens, Don't ask: no question");
        check(WhoPlays.atAppStart(two, C, WhoPlays.ASK).uid.equals(A), "the app opens, an unknown user playing now: the first");
        check(WhoPlays.uid(B, A).equals(B) && WhoPlays.uid(null, A).equals(A) && WhoPlays.uid("../x", A).equals(A), "the start's user: the extra when it is an ID");
    }
}
