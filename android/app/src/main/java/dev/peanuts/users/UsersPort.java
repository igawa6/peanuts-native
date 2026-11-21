package dev.peanuts.users;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Upstream's users (dev.nxnative.android.Users, package-private) read and changed through reflection, as dev.peanuts.ui.Upstream reads the launcher's classes, so
 * the console's users stay where upstream keeps them (its preferences, DM_USERS / DM_USER for the host) and no upstream file needs a hook for Settings › Users.
 */
public final class UsersPort {
    private UsersPort() {}

    private static final String USERS = "dev.nxnative.android.Users";
    public static final int MAX = 8;
    /** The first user, the host's fixed one (its save is the game's save folder every earlier run used): it can be renamed, never removed */
    public static final String FIRST = "78626c000000000153574e4154495645";

    public static final class User {
        public final String uid, name;
        User(String uid, String name) { this.uid = uid; this.name = name; }
        public boolean first() { return FIRST.equals(uid); }
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = Class.forName(USERS).getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    /** The users, the first always the fixed one; on a failure the fixed one alone, so the page still shows */
    public static List<User> list(Context c) {
        List<User> out = new ArrayList<>();
        try {
            for (Object u : (List<?>) call("list", new Class<?>[] {Context.class}, c)) {
                Field id = u.getClass().getDeclaredField("uid"), nm = u.getClass().getDeclaredField("name");
                id.setAccessible(true); nm.setAccessible(true);
                out.add(new User((String) id.get(u), (String) nm.get(u)));
            }
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w("peanuts", "Users.list", e); }
        if (out.isEmpty()) out.add(new User(FIRST, "Player"));
        return out;
    }

    /** The user games start with when nobody is asked */
    public static String defaultUid(Context c) {
        try { return (String) call("defaultUid", new Class<?>[] {Context.class}, c); }
        catch (ReflectiveOperationException | RuntimeException e) { return FIRST; }
    }

    public static void setDefault(Context c, String uid) { run("setDefault", new Class<?>[] {Context.class, String.class}, c, uid); }

    /** A name as the console keeps it (Users.clean): trimmed, at most 32 bytes; "" when nothing is left */
    public static String clean(String name) {
        try { return (String) call("clean", new Class<?>[] {String.class}, name); }
        catch (ReflectiveOperationException | RuntimeException e) { return name == null ? "" : name.trim(); }
    }

    /** A new user with a fresh ID; null when there are already eight or it failed */
    public static User add(Context c, String name) {
        try {
            Object u = call("add", new Class<?>[] {Context.class, String.class}, c, name);
            if (u == null) return null;
            Field id = u.getClass().getDeclaredField("uid"), nm = u.getClass().getDeclaredField("name");
            id.setAccessible(true); nm.setAccessible(true);
            return new User((String) id.get(u), (String) nm.get(u));
        } catch (ReflectiveOperationException | RuntimeException e) { Log.w("peanuts", "Users.add", e); return null; }
    }

    public static void rename(Context c, String uid, String name) { run("rename", new Class<?>[] {Context.class, String.class, String.class}, c, uid, name); }

    /** Removes a user (never the first); its saves stay on the phone */
    public static void remove(Context c, String uid) { run("remove", new Class<?>[] {Context.class, String.class}, c, uid); }

    private static void run(String name, Class<?>[] types, Object... args) {
        try { call(name, types, args); } catch (ReflectiveOperationException | RuntimeException e) { Log.w("peanuts", "Users." + name, e); }
    }
}
