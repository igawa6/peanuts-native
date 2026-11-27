package dev.nxnative.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * PEANUTS: Android 12 support (upstream v0.3.41's Streams, kept public for dev.peanuts and with the platform call kept on Android 13 and newer).
 * InputStream.readAllBytes / readNBytes are API 33 on Android (Java 9 methods the platform got with Android 13): from API 33 each helper makes
 * exactly that call; on Android 12 (API 31/32) it reads the same bytes in a loop. Plain Java (no android.* import) so the JVM tests compile it.
 */
public final class Streams {
    private Streams() {}

    /** Build.VERSION.SDK_INT on Android; off Android (the JVM tests, whose JDK has every method) the largest int */
    static final int SDK = sdk();

    private static int sdk() {
        try { return Class.forName("android.os.Build$VERSION").getField("SDK_INT").getInt(null); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return Integer.MAX_VALUE; }
    }

    /** As in.readAllBytes() */
    @SuppressWarnings("NewApi")   // lint: the call is behind SDK >= 33 (Build.VERSION.SDK_INT, read above)
    public static byte[] readAll(InputStream in) throws IOException {
        if (SDK >= 33) return in.readAllBytes();
        return loop(in, Integer.MAX_VALUE);
    }

    /** As in.readNBytes(max): at most max bytes, fewer at the end of the stream */
    @SuppressWarnings("NewApi")
    public static byte[] readUpTo(InputStream in, int max) throws IOException {
        if (SDK >= 33) return in.readNBytes(max);
        return loop(in, max);
    }

    /** As in.readNBytes(b, off, len): reads until len bytes or the end of the stream, returns the count */
    @SuppressWarnings("NewApi")
    public static int readInto(InputStream in, byte[] b, int off, int len) throws IOException {
        if (SDK >= 33) return in.readNBytes(b, off, len);
        return loopInto(in, b, off, len);
    }

    /** Android 12's readAll / readUpTo (package-private for the JVM test, test_android_api31.py) */
    static byte[] loop(InputStream in, int max) throws IOException {
        if (max < 0) throw new IllegalArgumentException("len < 0");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int left = max;
        while (left > 0) {
            int n = in.read(b, 0, Math.min(b.length, left));
            if (n < 0) break;
            o.write(b, 0, n);
            left -= n;
        }
        return o.toByteArray();
    }

    /** Android 12's readInto */
    static int loopInto(InputStream in, byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        int got = 0;
        while (got < len) {
            int n = in.read(b, off + got, len - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }
}
