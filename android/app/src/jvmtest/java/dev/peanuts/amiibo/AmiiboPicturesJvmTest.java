package dev.peanuts.amiibo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The amiibo pictures as a strict opt-in, on the JVM (peanuts/tests/test_amiibo.py, with stand-ins for Android's classes): AmiiboPictures.load opens no
 * connection, makes no cache folder and answers at once with none (Peanuts' own card) unless the player turned the pictures on and amiibo is on
 * somewhere (Settings' row or a game's own switch). Every http and https connection the JVM opens is counted here, and none reaches the network.
 */
public final class AmiiboPicturesJvmTest {
    private static final List<String> OPENED = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);   // the loader's threads are not daemons
        }
        System.out.println("ok");
        System.exit(0);
    }

    private static void run() throws Exception {
        URL.setURLStreamHandlerFactory(p -> !"http".equals(p) && !"https".equals(p) ? null : new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL u) throws IOException {
                OPENED.add(u.toString());
                throw new IOException("no network in this test");
            }
        });
        File cache = Files.createTempDirectory("amiibo-pictures").toFile();
        Ctx c = new Ctx(cache);
        String acnh = "01006F8002326000";

        check(!AmiiboSwitch.pictures(c) && !AmiiboSwitch.all(c) && AmiiboSwitch.game(c, acnh) == null, "everything is off by default");
        none(c, cache, "by default");

        AmiiboSwitch.pictures(c, true);
        none(c, cache, "with the pictures on while amiibo is off everywhere");
        AmiiboSwitch.game(c, acnh, false);
        none(c, cache, "with the pictures on and a game's own switch set to off");

        AmiiboSwitch.pictures(c, false);
        AmiiboSwitch.all(c, true);
        none(c, cache, "with amiibo on and the pictures off");
        AmiiboSwitch.all(c, false);
        AmiiboSwitch.game(c, acnh, true);
        none(c, cache, "with amiibo on for a game and the pictures off");

        AmiiboSwitch.pictures(c, true);
        fetched(c, "0000000000000002", "with the pictures on and amiibo on for a game");
        AmiiboSwitch.game(c, acnh, false);
        AmiiboSwitch.all(c, true);
        fetched(c, "0000000100000002", "with the pictures on and amiibo on in Settings");
    }

    /** load answers at once with none, and nothing was opened or made */
    private static void none(Ctx c, File cache, String when) {
        OPENED.clear();
        Object[] got = {"not answered"};
        AmiiboPictures.load(c, "0000000000000002", 100, b -> got[0] = b);
        check(got[0] == null, "load answers at once with none " + when);
        check(OPENED.isEmpty(), "no connection " + when + ": " + OPENED);
        check(!new File(cache, "amiibo").exists(), "no cache folder " + when);
    }

    /** load tries AmiiboAPI's picture on GitHub (offline here, so none comes) */
    private static void fetched(Ctx c, String id, String when) throws InterruptedException {
        OPENED.clear();
        CountDownLatch done = new CountDownLatch(1);
        Object[] got = {"not answered"};
        AmiiboPictures.load(c, id, 100, b -> { got[0] = b; done.countDown(); });
        check(done.await(20, TimeUnit.SECONDS), "load answers " + when);
        check(got[0] == null, "offline, none comes " + when);
        check(OPENED.size() == 1, "one connection " + when + ": " + OPENED);
        String u = OPENED.get(0);
        check(u.startsWith("https://raw.githubusercontent.com/N3evin/AmiiboAPI/") && u.endsWith(id.substring(0, 8) + "-" + id.substring(8) + ".png"),
                "AmiiboAPI's image on GitHub " + when + ": " + u);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    /** A Context with one preferences file in memory and a cache folder */
    static final class Ctx extends Context {
        private final File cache;
        private final Map<String, Prefs> files = new HashMap<>();

        Ctx(File cache) { this.cache = cache; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return files.computeIfAbsent(name, n -> new Prefs()); }
        @Override public File getCacheDir() { return cache; }
        @Override public Context getApplicationContext() { return this; }
    }

    static final class Prefs implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public boolean getBoolean(String k, boolean d) { Object v = values.get(k); return v instanceof Boolean ? (Boolean) v : d; }
        @Override public boolean contains(String k) { return values.containsKey(k); }
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public Editor edit() {
            return new Editor() {
                @Override public Editor putBoolean(String k, boolean v) { values.put(k, v); return this; }
                @Override public void apply() {}
            };
        }
    }
}
