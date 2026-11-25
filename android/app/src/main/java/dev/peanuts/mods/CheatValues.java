package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The number a Value cheat writes, set by the player (SPEC-mod-menu.md Package C, the in-game Mods tab's number field). A Value cheat is one static data write
 * of a number (CheatFile.Kind.VALUE); a code cheat keeps its words. The numbers are kept per game in &lt;files&gt;/cheats/&lt;title ID&gt;/values.tsv by the
 * merged file's key, with the place of the number in the cheat's words and the number the file had there: "key \t number \t word \t width \t original". A start
 * writes a kept number into the merged file where the cheat still has that original number (CheatSources.merge; the start reads the files without main's code
 * size, so it does not tell a Value cheat in main's data from code by itself). While the game runs, the tab patches that cheat's block of the merged file and
 * has the host read it again (Part B's cheatsReload). No Android here, so CheatLoaderJvmTest reads it.
 */
public final class CheatValues {
    private CheatValues() {}

    public static final String FILE = "values.tsv";

    /** A kept number: what the player set, where it goes in the cheat's words (index, width in bytes), and what the file had there */
    public static final class Kept {
        public final long number, original;
        public final int word, width;

        Kept(long number, int word, int width, long original) {
            this.number = number;
            this.word = word;
            this.width = width;
            this.original = original;
        }
    }

    /**
     * Whether the cheat writes a plain number the player can set: a Value cheat whose number is not a float. A 4-byte number with its low 12 bits clear and an
     * exponent between 2^-8 and 2^31 reads as a float (1.5 is 0x3FC00000) and keeps its words; a count rarely looks like that
     */
    public static boolean plain(CheatFile.Cheat c) {
        if (c.kind != CheatFile.Kind.VALUE || c.valueWord < 0) return false;
        int w = c.valueWidth;
        if (w != 1 && w != 2 && w != 4 && w != 8) return false;
        if (w == 4) {
            long v = c.value();
            int exp = (int) ((v >>> 23) & 0xFF);
            if (v != 0 && (v & 0xFFF) == 0 && exp >= 119 && exp <= 158) return false;
        }
        return true;
    }

    /** The largest number a width holds (8 bytes: a long's largest, the field's limit) */
    public static long max(int width) { return width >= 8 ? Long.MAX_VALUE : (1L << (8 * width)) - 1; }

    /** The number at word (width bytes) of words; -1 when it is not there */
    static long at(long[] words, int word, int width) {
        if (word < 0 || word + (width == 8 ? 1 : 0) >= words.length) return -1;
        if (width == 8) return (words[word] << 32) | words[word + 1];
        return width == 4 ? words[word] : words[word] & max(width);
    }

    /** The cheat's lines with v as its number (c a Value cheat: its own place) */
    public static List<String> lines(CheatFile.Cheat c, long v) { return lines(c.lines, c.valueWord, c.valueWidth, v); }

    /** lines with v at word (width bytes); the other words as written, the number's in eight upper-case hex digits */
    static List<String> lines(List<String> lines, int word0, int width, long v) {
        v = Math.max(0, Math.min(v, max(width)));
        String[] put = width == 8 ? new String[] {hex(v >>> 32), hex(v & 0xFFFFFFFFL)} : new String[] {hex(v)};
        List<String> out = new ArrayList<>();
        int word = 0;
        for (String line : lines) {
            String[] t = line.trim().split("[ \t\r]+");   // as CheatFile.words: a token holds one word per eight hex digits
            boolean changed = false;
            for (int i = 0; i < t.length; i++) {
                StringBuilder tok = new StringBuilder(t[i]);
                for (int j = 0; j + 8 <= t[i].length(); j += 8, word++) {
                    int k = word - word0;
                    if (k >= 0 && k < put.length) { tok.replace(j, j + 8, put[k]); changed = true; }
                }
                t[i] = tok.toString();
            }
            out.add(changed ? String.join(" ", t) : line);
        }
        return out;
    }

    private static String hex(long w) { return String.format(Locale.ROOT, "%08X", w & 0xFFFFFFFFL); }

    /** The cheat's lines with the kept number, when the cheat still has the original number at its place; else null (the file's own lines stay) */
    public static List<String> apply(CheatFile.Cheat c, Kept k) {
        if (k == null || at(c.words, k.word, k.width) != k.original) return null;
        return lines(c.lines, k.word, k.width, k.number);
    }

    /** The number the cheat writes now: the kept one when it applies, else the file's */
    public static long of(Map<String, Kept> kept, String key, CheatFile.Cheat c) {
        Kept k = key == null ? null : kept.get(key.toLowerCase(Locale.ROOT));
        return k != null && apply(c, k) != null ? Math.min(k.number, max(k.width)) : c.value();
    }

    /** The merged file's text with the block of [key] holding c's lines for v; null when the file has no such block of c's length */
    public static String patch(String merged, String key, CheatFile.Cheat c, long v) {
        String[] l = merged.split("\n", -1);
        String head = "[" + key + "]";
        for (int i = 0; i < l.length; i++) {
            if (!l[i].trim().equals(head)) continue;
            if (i + c.lines.size() >= l.length) return null;
            List<String> put = lines(c, v);
            for (int j = 0; j < put.size(); j++) l[i + 1 + j] = put.get(j);
            return String.join("\n", l);
        }
        return null;
    }

    /** The kept numbers by key in lower case (the host's keys compare without case) */
    public static Map<String, Kept> read(File folder) {
        Map<String, Kept> out = new LinkedHashMap<>();
        File f = new File(folder, FILE);
        if (!f.isFile()) return out;
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        for (String line : text.split("\n")) {
            String[] t = line.split("\t");
            if (t.length < 5 || t[0].isEmpty()) continue;
            try {
                int width = Integer.parseInt(t[3].trim());
                if (width != 1 && width != 2 && width != 4 && width != 8) continue;
                out.put(t[0].toLowerCase(Locale.ROOT), new Kept(Long.parseLong(t[1].trim()), Integer.parseInt(t[2].trim()), width, Long.parseLong(t[4].trim())));
            } catch (NumberFormatException ignored) {
                // a line this reader cannot read is left out
            }
        }
        return out;
    }

    /** Keeps v as the number of c (a Value cheat) under key; v equal to the file's own number forgets it */
    public static void keep(File folder, String key, CheatFile.Cheat c, long v) throws IOException {
        Map<String, Kept> all = read(folder);
        String k = key.toLowerCase(Locale.ROOT);
        if (v == c.value()) all.remove(k);
        else all.put(k, new Kept(Math.max(0, Math.min(v, max(c.valueWidth))), c.valueWord, c.valueWidth, c.value()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Kept> e : all.entrySet()) {
            Kept x = e.getValue();
            sb.append(e.getKey()).append('\t').append(x.number).append('\t').append(x.word).append('\t').append(x.width).append('\t').append(x.original).append('\n');
        }
        write(new File(folder, FILE), sb.toString());
    }

    /** text into f through a file beside it, so a reader never sees half of it */
    public static void write(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
