package dev.peanuts.amiibo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * An amiibo's picture, downloaded when the player opens an amiibo page or tab: from AmiiboAPI's images on GitHub (the same database as the table), kept
 * in the app's cache (cacheDir/amiibo), and never downloaded again while it is there. A picture AmiiboAPI does not have is noted for a day. Offline, or
 * without one, the caller shows Peanuts' own card. Nothing is downloaded before a page asks, and nothing at all unless the player turned the pictures on
 * and amiibo is on somewhere (AmiiboSwitch.picturesOn: Settings › Users › amiibo pictures, off by default; igawa6's review of #711: a new outside host
 * is the player's choice, strictly opt-in).
 */
public final class AmiiboPictures {
    private AmiiboPictures() {}

    static final String URL = "https://raw.githubusercontent.com/N3evin/AmiiboAPI/master/images/icon_%s-%s.png";
    static final long MISSING_MS = 24L * 3600 * 1000;
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    static File dir(Context c) { return new File(c.getCacheDir(), "amiibo"); }

    /** The picture of this ID about px pixels tall, on the main thread; null when there is none (the pictures or amiibo are off, offline, or AmiiboAPI has none) */
    public static void load(Context c, String id, int px, Consumer<Bitmap> done) {
        if (!AmiiboSwitch.picturesOn(c)) { done.accept(null); return; }   // off, or amiibo off everywhere: Peanuts' own card, nothing downloaded, no cache read
        String key = id + "@" + px;
        Bitmap m = MEM.get(key);
        if (m != null) { done.accept(m); return; }
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            Bitmap b = null;
            try { b = decode(file(app, id), px); } catch (RuntimeException e) { Log.w(AmiiboStand.TAG, "amiibo picture " + id, e); }
            if (b != null) MEM.put(key, b);
            Bitmap out = b;
            MAIN.post(() -> done.accept(out));
        });
    }

    /** The cached file, fetched first when it is not there; null when there is none */
    static File file(Context c, String id) {
        File d = dir(c), f = new File(d, id + ".png"), missing = new File(d, id + ".none");
        if (f.isFile() && f.length() > 0) return f;
        if (missing.isFile() && System.currentTimeMillis() - missing.lastModified() < MISSING_MS) return null;
        if (!d.isDirectory() && !d.mkdirs()) return null;
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(String.format(URL, id.substring(0, 8), id.substring(8))).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(15000);
            int code = h.getResponseCode();
            if (code == 404) { Files.write(missing.toPath(), new byte[0]); return null; }
            if (code != 200) return null;
            File tmp = new File(d, id + ".part");
            try (InputStream in = h.getInputStream()) {
                byte[] b = AmiiboImport.read(in, MAX_BYTES);
                if (b == null) return null;
                Files.write(tmp.toPath(), b);
            }
            return tmp.renameTo(f) ? f : null;
        } catch (IOException e) {
            return null;   // offline: the card shows, and the next opening tries again
        } finally {
            if (h != null) h.disconnect();
        }
    }

    static Bitmap decode(File f, int px) {
        if (f == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outHeight <= 0) return null;
        int s = 1;
        while (o.outHeight / (s * 2) >= px && o.outWidth / (s * 2) >= px / 2) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), d);
    }
}
