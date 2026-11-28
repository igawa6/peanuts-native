package dev.peanuts.amiibo;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.peanuts.Bridge;
import dev.peanuts.mii.MiiStore;
import dev.peanuts.users.UserPictures;
import dev.peanuts.users.UsersPort;

/**
 * The game's amiibo stand, from the app's side (the game's process): what the host's nfp says (whether amiibo is on for this game, whether the game
 * searches, since when, what lies on the stand: Bridge.amiiboStatus), and placing one of the player's amiibo there with the player's Mii as its owner. The
 * stand is there only in a game the player turned amiibo on for (AmiiboSwitch). A game seen initializing nfp is noted (files/amiibo/games.txt), so the game
 * page shows its amiibo area even when AmiiboAPI's table does not list the game.
 */
public final class AmiiboStand {
    private AmiiboStand() {}

    static final String TAG = "peanuts";
    public static final int DEVICE_SEARCH = 1, DEVICE_FOUND = 2, DEVICE_REMOVED = 3, DEVICE_MOUNTED = 4;

    private static AmiiboDb shared;

    /** The app's table (res/raw/amiibo_db.tsv of the peanuts source set), read once; an empty one when it cannot be read */
    public static synchronized AmiiboDb db(Context c) {
        if (shared != null) return shared;
        int id = c.getResources().getIdentifier("amiibo_db", "raw", c.getPackageName());
        if (id != 0) {
            try (java.io.InputStream in = c.getResources().openRawResource(id)) { shared = AmiiboDb.parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)); }
            catch (IOException | RuntimeException e) { Log.w(TAG, "amiibo: the table cannot be read", e); }
        }
        if (shared == null) try { shared = AmiiboDb.parse(new java.io.StringReader("")); } catch (IOException e) { throw new IllegalStateException(e); }
        return shared;
    }

    /** The host's stand as it was at one read */
    public static final class Status {
        public final boolean enabled, lib;   // enabled: amiibo is on for this game (AmiiboSwitch); without it the game has no stand and nothing else counts
        public final int state, search, reads;
        public final long searchingMs;
        public final String on, ready, access;
        Status(Map<String, String> m) {
            enabled = "1".equals(m.get("enabled"));
            lib = enabled && "1".equals(m.get("lib"));
            state = num(m.get("state")); search = num(m.get("search")); reads = num(m.get("reads"));
            long ms; try { ms = Long.parseLong(m.getOrDefault("searching_ms", "0")); } catch (NumberFormatException e) { ms = 0; }
            searchingMs = ms;
            on = m.getOrDefault("on", ""); ready = m.getOrDefault("ready", ""); access = m.getOrDefault("access", "");
        }
        public boolean searching() { return state == DEVICE_SEARCH; }
        public boolean onStand() { return !on.isEmpty(); }
        static int num(String s) { try { return s == null ? 0 : Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
    }

    public static Status parse(String text) {
        Map<String, String> m = new HashMap<>();
        if (text != null) for (String line : text.split("\n")) { int i = line.indexOf('='); if (i > 0) m.put(line.substring(0, i), line.substring(i + 1)); }
        return new Status(m);
    }

    /** The stand now; an empty status without a host that has it */
    public static Status now() { return parse(Bridge.amiiboStatusSafe()); }

    /** Puts one of the player's amiibo on the stand for this game: 1 the searching game found it, 2 kept for its next search, 0 refused */
    public static int place(Context c, AmiiboStore.Own o, String titleId) {
        AmiiboDb.Amiibo a = db(c).get(o.id);
        long now = System.currentTimeMillis();
        int r = Bridge.amiiboPlaceSafe(AmiiboTag.describe(o, a, ownerMii(c), titleId, now));
        if (r > 0) AmiiboStore.placed(o, titleId, now);
        Log.i(TAG, "amiibo: " + a.title() + " placed (" + (r == 1 ? "found" : r == 2 ? "ready" : "refused") + ")");
        return r;
    }

    /** An amiibo of the app's list placed: the player's own copy of it (made the first time, with its game data after that) */
    public static int placeFromList(Context c, String id, String titleId) {
        try {
            AmiiboStore.Own o = AmiiboStore.listed(c.getFilesDir(), id);
            if (o == null) o = AmiiboStore.fromList(c.getFilesDir(), id);
            return place(c, o, titleId);
        } catch (IOException e) {
            Log.w(TAG, "amiibo: cannot keep " + id, e);
            return 0;
        }
    }

    public static void remove() { Bridge.amiiboRemoveSafe(); }

    /** The owner's Mii a game reads: the picture's Mii of the user the game started with, else the player's first Mii, else a default one with the user's name */
    static byte[] ownerMii(Context c) {
        try {
            String uid = UsersPort.defaultUid(c);
            List<MiiStore.Mii> miis = MiiStore.load(c.getFilesDir());
            String want = UserPictures.of(c.getFilesDir(), uid, 0).mii();
            if (want != null) for (MiiStore.Mii m : miis) if (MiiStore.hex(m.createId).equalsIgnoreCase(want)) return MiiStore.charInfo(m);
            if (!miis.isEmpty()) return MiiStore.charInfo(miis.get(0));
            String name = "Player";
            for (UsersPort.User u : UsersPort.list(c)) if (u.uid.equals(uid)) name = u.name;
            return MiiStore.charInfo(MiiStore.fromDefault(0, name));
        } catch (RuntimeException e) {
            Log.w(TAG, "amiibo: no Mii for the owner", e);
            return MiiStore.charInfo(MiiStore.fromDefault(0, "Player"));
        }
    }

    // ---- the games seen using amiibo ----

    static File games(File filesDir) { return new File(new File(filesDir, "amiibo"), "games.txt"); }

    /** Notes that this game initialized nfp (once per title) */
    public static void seen(File filesDir, String titleId) {
        if (titleId == null) return;
        Set<String> s = seenGames(filesDir);
        if (!s.add(titleId.toUpperCase(Locale.ROOT))) return;
        try {
            File f = games(filesDir);
            File d = f.getParentFile();
            if (d != null && !d.isDirectory() && !d.mkdirs()) return;
            AmiiboStore.write(f, (String.join("\n", s) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { /* noted next time */ }
    }

    public static Set<String> seenGames(File filesDir) {
        Set<String> s = new LinkedHashSet<>();
        File f = games(filesDir);
        if (!f.isFile()) return s;
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n"))
                if (line.trim().matches("[0-9A-Fa-f]{16}")) s.add(line.trim().toUpperCase(Locale.ROOT));
        } catch (IOException e) { /* none */ }
        return s;
    }

    /** Whether a game uses amiibo: AmiiboAPI's table lists it, or it was seen initializing nfp */
    public static boolean usesAmiibo(Context c, String titleId) {
        return titleId != null && (db(c).usedBy(titleId) || seenGames(c.getFilesDir()).contains(titleId.toUpperCase(Locale.ROOT)));
    }
}
