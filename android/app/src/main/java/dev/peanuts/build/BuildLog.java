package dev.peanuts.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One game's build log: &lt;app files&gt;/build-logs/&lt;title ID&gt;.log, written by the build services of the launcher's process ({@link BuildService},
 * {@link ModBuildService}, {@link Prepare}) as they go, so that a build that fails, is stopped or ends with the app's process can be told afterwards
 * ({@link BuildLogBundle}, Share build log). It lives in the app's own storage, which is always writable, and is there before any job is: an import that fails
 * has a log too. Each run starts with a {@link #START} line and ends with an {@link #END} line; a run without its end line is one whose process ended
 * during it. At {@link #ROLL} bytes the file moves to &lt;title ID&gt;.1.log (one older file is kept). No Android class here: the JVM tests drive it.
 */
public final class BuildLog {
    /** The size at which the log moves to its .1.log */
    static final long ROLL = 2L << 20;
    public static final String START = "[start] ", END = "[end] ";
    private static final String TITLE = "[0-9A-F]{16}";

    private BuildLog() {}

    /** A title ID as the file names have it (upper case), or null when it is not one */
    public static String title(String titleId) {
        if (titleId == null) return null;
        String t = titleId.trim().toUpperCase(Locale.ROOT);
        return t.matches(TITLE) ? t : null;
    }

    public static File file(File dir, String titleId) { return new File(dir, title(titleId) + ".log"); }
    public static File older(File dir, String titleId) { return new File(dir, title(titleId) + ".1.log"); }

    /** One line (several for a text with line breaks, each with the time) at the end of the title's log; quiet when it cannot be written */
    public static void append(File dir, String titleId, String text) { append(dir, titleId, text, System.currentTimeMillis()); }

    static synchronized void append(File dir, String titleId, String text, long now) {
        if (dir == null || title(titleId) == null || text == null) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(now));
        StringBuilder b = new StringBuilder();
        for (String l : text.split("\n", -1)) b.append(stamp).append(' ').append(l).append('\n');
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = file(dir, titleId);
            if (f.length() >= ROLL) {
                File o = older(dir, titleId);
                if (o.exists() && !o.delete()) return;
                if (!f.renameTo(o)) return;
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) { out.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        } catch (IOException | RuntimeException e) {
            // a log line is never worth a failed build
        }
    }

    /** The title whose log changed last, or null when there is none */
    public static String newest(File dir) {
        File[] l = dir == null ? null : dir.listFiles((d, n) -> n.matches(TITLE + "\\.log"));
        if (l == null || l.length == 0) return null;
        File best = l[0];
        for (File f : l) if (f.lastModified() > best.lastModified()) best = f;
        return best.getName().substring(0, 16);
    }

    /** What the newest run of a log says of its end: the end line's text, or null when the newest run has no end line (or the log has no run) */
    public static String lastEnd(String text) {
        if (text == null) return null;
        int s = text.lastIndexOf(START);
        if (s < 0) return null;
        int e = text.indexOf(END, s);
        if (e < 0) return null;
        int nl = text.indexOf('\n', e);
        return text.substring(e + END.length(), nl < 0 ? text.length() : nl).trim();
    }

    /** Whether the log has a run at all, and the newest run's start line (null when none) */
    public static String lastStart(String text) {
        if (text == null) return null;
        int s = text.lastIndexOf(START);
        if (s < 0) return null;
        int nl = text.indexOf('\n', s);
        return text.substring(s + START.length(), nl < 0 ? text.length() : nl).trim();
    }

    /** The last bytes of a file as text (the newest run of a log is near its end), "" when it cannot be read */
    public static String tail(File f, int bytes) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length(), from = Math.max(0, len - bytes);
            byte[] b = new byte[(int) (len - from)];
            r.seek(from);
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
