package dev.nxnative.android;

import java.util.List;

/**
 * Settings › Users' list and the host's lines on the JVM (dm/tools/tests/test_android_users.py, with stand-ins for Android's Context and SharedPreferences): the stored
 * lines read back, the first user always the host's fixed one, at most 8, DM_USERS / DM_USER with the names percent-encoded, nothing for the one default user, names cut
 * to 32 bytes of UTF-8 at a character, and the save folders.
 */
public final class UsersJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] a) {
        String b = "0123456789abcdef0123456789abcdef";
        List<Users.User> l = Users.parse("");
        check(l.size() == 1 && l.get(0).uid.equals(Users.FIRST) && l.get(0).name.equals("Player"), "empty: the first user alone");
        check(Users.env(l, Users.FIRST).isEmpty(), "one default user: no lines (the host's own default)");
        l = Users.parse(b + "\tLuigi\n" + Users.FIRST + "\tMario\nnot a line\n" + b + "\tdup\n");
        check(l.size() == 2 && l.get(0).uid.equals(Users.FIRST) && l.get(0).name.equals("Mario") && l.get(1).name.equals("Luigi"), "the first user moved to the front, a duplicate dropped: " + Users.format(l));
        List<String> env = Users.env(l, b);
        check(env.get(0).equals("DM_USERS=" + Users.FIRST + ":Mario," + b + ":Luigi") && env.get(1).equals("DM_USER=" + b), "env: " + env);
        check(Users.pct("a,b%c dé").equals("a%2Cb%25c d%C3%A9"), "pct: " + Users.pct("a,b%c dé"));
        check(Users.env(Users.parse(Users.FIRST + "\tPeach\n"), Users.FIRST).get(0).equals("DM_USERS=" + Users.FIRST + ":Peach"), "one renamed user: its name goes to the host");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 12; i++) many.append(String.format("%032x", i + 1)).append("\tU").append(i).append('\n');
        check(Users.parse(many.toString()).size() == Users.MAX, "at most 8 users");
        String longName = "é".repeat(20);   // 40 bytes
        String cut = Users.clean(longName);
        check(cut.equals("é".repeat(16)) && cut.getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 32, "cut at 32 bytes, at a character: " + cut);
        check(Users.clean("  a\tb\n ").equals("a b"), "tabs and line breaks to spaces, trimmed");
        java.io.File save = new java.io.File("/x/saves/010015100B514000");
        check(Users.saveDir(save, Users.FIRST).equals(save), "the first user: the game's save folder");
        check(Users.saveDir(save, b).getPath().replace('\\', '/').equals("/x/saves/010015100B514000_user_" + b), "another user: beside it");
        System.out.println("ok");
    }
}
