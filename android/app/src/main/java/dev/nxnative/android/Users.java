package dev.nxnative.android;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The console's users (Settings › Users): each a name and a Uid made once when it is added, and the user games start with. They go to the host as DM_USERS / DM_USER
 * (dm/host/hle_account.c): its account calls list them, the user selector offers them (UserPicker), and each user's save is a folder of its own beside the game's save
 * folder (saves/&lt;application ID&gt;_user_&lt;uid&gt;). The first user is the host's fixed one ({@link #FIRST}): its save is the game's save folder every earlier run used,
 * so it cannot be removed. At most 8, the console's limit. Stored as lines "uid TAB name" in the launcher's preferences.
 */
final class Users {
    private Users() {}

    static final String FIRST = "78626c000000000153574e4154495645";   // hle_account.c k_uid
    static final int MAX = 8, NAME_MAX = 32;   // the console's users; a nickname's bytes (UTF-8, nn::account::Nickname)
    private static final String KEY = "users", KEY_DEFAULT = "user_default";

    static final class User {
        final String uid, name;
        User(String uid, String name) { this.uid = uid; this.name = name; }
    }

    /** The users, the first always the host's fixed one ("Player" until renamed) */
    static List<User> list(Context c) { return parse(Prefs.of(c).getString(KEY, "")); }

    static List<User> parse(String s) {
        List<User> out = new ArrayList<>();
        for (String line : s.split("\n")) {
            int t = line.indexOf('\t');
            if (t != 32 || !line.substring(0, 32).matches("[0-9a-f]{32}") || out.size() >= MAX) continue;
            String uid = line.substring(0, 32);
            boolean dup = false; for (User u : out) dup |= u.uid.equals(uid);
            if (!dup) out.add(new User(uid, line.substring(33)));
        }
        if (out.isEmpty() || !out.get(0).uid.equals(FIRST)) {   // the first user is the fixed one: moved to the front with its name, else made
            User first = new User(FIRST, "Player");
            for (User u : out) if (u.uid.equals(FIRST)) first = u;
            out.removeIf(u -> u.uid.equals(FIRST));
            out.add(0, first);
            while (out.size() > MAX) out.remove(out.size() - 1);
        }
        return out;
    }

    static String format(List<User> l) {
        StringBuilder b = new StringBuilder();
        for (User u : l) b.append(u.uid).append('\t').append(u.name.replace('\n', ' ').replace('\t', ' ')).append('\n');
        return b.toString();
    }

    private static void store(Context c, List<User> l) { Prefs.of(c).edit().putString(KEY, format(l)).apply(); }

    /** The user games start with (the console's preselected user): its uid, the first user's when none is set or it is gone */
    static String defaultUid(Context c) {
        String d = Prefs.of(c).getString(KEY_DEFAULT, FIRST);
        for (User u : list(c)) if (u.uid.equals(d)) return d;
        return FIRST;
    }

    static void setDefault(Context c, String uid) { Prefs.of(c).edit().putString(KEY_DEFAULT, uid).apply(); }

    /** A name as the console keeps it: trimmed, at most 32 bytes of UTF-8 (cut at a character), no line breaks or tabs; "" stays "" (the caller refuses it) */
    static String clean(String name) {
        String s = name.replace('\n', ' ').replace('\t', ' ').trim();
        while (s.getBytes(StandardCharsets.UTF_8).length > NAME_MAX) s = s.substring(0, s.offsetByCodePoints(0, s.codePointCount(0, s.length()) - 1));   // drop the last character
        return s.trim();
    }

    /** Adds a user with a new random Uid; null when there are already 8 */
    static User add(Context c, String name) {
        List<User> l = list(c);
        if (l.size() >= MAX) return null;
        SecureRandom r = new SecureRandom(); byte[] b = new byte[16];
        String uid;
        do { r.nextBytes(b); uid = hex(b); } while (uid.equals("0".repeat(32)) || uid.equals(FIRST));
        User u = new User(uid, clean(name));
        l.add(u); store(c, l);
        return u;
    }

    static void rename(Context c, String uid, String name) {
        List<User> l = list(c);
        for (int i = 0; i < l.size(); i++) if (l.get(i).uid.equals(uid)) l.set(i, new User(uid, clean(name)));
        store(c, l);
    }

    /** Removes a user (not the first); its save folder stays on the phone */
    static void remove(Context c, String uid) {
        if (uid.equals(FIRST)) return;
        List<User> l = list(c);
        l.removeIf(u -> u.uid.equals(uid));
        store(c, l);
        if (uid.equals(Prefs.of(c).getString(KEY_DEFAULT, FIRST))) setDefault(c, FIRST);
    }

    /** A user's save folder for a game whose save folder is save (the host's dm_account_save_dir): the game's own for the first user */
    static java.io.File saveDir(java.io.File save, String uid) {
        return uid.equals(FIRST) ? save : new java.io.File(save.getParentFile(), save.getName() + "_user_" + uid);
    }

    /** The host's lines: DM_USERS (uid:name, the name percent-encoded) and DM_USER. Nothing for one user named Player: the host's own default, as before */
    static List<String> env(Context c) { return env(list(c), defaultUid(c)); }

    static List<String> env(List<User> l, String def) {
        List<String> out = new ArrayList<>();
        if (l.size() == 1 && l.get(0).name.equals("Player")) return out;
        StringBuilder b = new StringBuilder("DM_USERS=");
        for (int i = 0; i < l.size(); i++) b.append(i > 0 ? "," : "").append(l.get(i).uid).append(':').append(pct(l.get(i).name));
        out.add(b.toString());
        out.add("DM_USER=" + def);
        return out;
    }

    /** Percent-encoding of the bytes a name cannot carry in DM_USERS (',' ends an entry; '%' itself; controls and anything outside ASCII, so the line stays ASCII) */
    static String pct(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int v = x & 0xff;
            if (v <= 0x20 && v != 0x20 || v >= 0x7f || v == ',' || v == '%') b.append(String.format(Locale.ROOT, "%%%02X", v));
            else b.append((char) v);
        }
        return b.toString();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }
}
