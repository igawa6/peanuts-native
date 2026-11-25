package dev.peanuts.overlay;

import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PEANUTS: the overlay's SoC temperature line ("soc" in Overlay.ITEMS): the hottest CPU / GPU thermal zone (SocZones), read every 2 s on a thread of its own while the
 * item is on (Overlay.tick asks twice a second); the UI thread only formats the last reading. The same source as nx2android's SOC row.
 */
public final class SocTemp {
    private SocTemp() {}

    /** No reading (SocZones.NONE), for callers outside this package */
    public static final int NONE = SocZones.NONE;
    static final int HOT = 80;                    // °C: the line turns red
    private static final int RED = 0xFFF44336;    // Overlay's red dot
    private static ExecutorService thread;
    private static volatile List<File> zones;     // found once, on the thread
    private static volatile int last = SocZones.NONE;
    private static volatile long askedAt;
    private static volatile boolean busy;

    /** Adds the line to the overlay's text ("TEMP 52°C", red from HOT; "TEMP ?" when no zone reads; nothing before the first reading) and asks for the next one */
    public static void append(SpannableStringBuilder s) {
        ask();
        if (zones == null) return;
        int c = last;
        if (s.length() > 0) s.append('\n');
        if (c == SocZones.NONE) { s.append("TEMP ?"); return; }
        int start = s.length();
        s.append("TEMP ").append(String.valueOf(c)).append("°C");
        if (c >= HOT) s.setSpan(new ForegroundColorSpan(RED), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** The last reading in °C (SocZones.NONE when there is none yet or no zone reads); asks for the next one */
    public static int celsius() {
        ask();
        return zones == null ? SocZones.NONE : last;
    }

    private static void ask() {
        long now = SystemClock.elapsedRealtime();
        if (busy || (zones != null && now - askedAt < 2000)) return;
        askedAt = now;
        busy = true;
        synchronized (SocTemp.class) {
            if (thread == null) thread = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "overlay-soc"); t.setDaemon(true); return t; });
        }
        thread.execute(() -> {
            try {
                List<File> z = zones;
                if (z == null) z = SocZones.discover(new File("/sys/class/thermal"));
                last = SocZones.hottest(z);
                zones = z;
            } finally { busy = false; }
        });
    }
}
