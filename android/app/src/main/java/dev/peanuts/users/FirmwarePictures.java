package dev.peanuts.users;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The console's own profile pictures, from the player's firmware: the AvatarImage system data (010000000000080A) that the firmware import extracts into
 * files/firmware/avatar (nximport.c), its chara/*.szs files, each a 256 x 256 RGBA picture packed with Yaz0. Nothing of it ships with the app. The pictures
 * have a transparent background, so the user's background colour shows behind them.
 */
public final class FirmwarePictures {
    private FirmwarePictures() {}

    static final int SIZE = 256;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    public static File dir(Context c) { return new File(new File(new File(c.getFilesDir(), "firmware"), "avatar"), "chara"); }

    /** The pictures' file names in the firmware's order; empty when the firmware gave none */
    public static List<String> names(Context c) {
        List<String> out = new ArrayList<>();
        String[] all = dir(c).list();
        if (all == null) return out;
        Arrays.sort(all);
        for (String n : all) if (n.endsWith(".szs")) out.add(n);
        return out;
    }

    /** The picture named name as a bitmap, on the main thread; null when it is missing or broken */
    public static void bitmap(Context c, String name, Consumer<Bitmap> done) {
        File f = new File(dir(c), name);
        IO.execute(() -> {
            Bitmap b = null;
            try { b = decode(Files.readAllBytes(f.toPath())); }
            catch (IOException | RuntimeException e) { Log.w("peanuts", "firmware picture " + name + ": " + e.getMessage()); }
            Bitmap out = b;
            UI.post(() -> done.accept(out));
        });
    }

    static Bitmap decode(byte[] szs) throws IOException {
        byte[] rgba = Yaz0.decode(szs, SIZE * SIZE * 4);
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < rgba.length; i += 4) {   // Bitmap holds premultiplied colour
            int a = rgba[i + 3] & 0xff;
            for (int k = 0; k < 3; k++) rgba[i + k] = (byte) ((rgba[i + k] & 0xff) * a / 255);
        }
        b.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
        return b;
    }
}
