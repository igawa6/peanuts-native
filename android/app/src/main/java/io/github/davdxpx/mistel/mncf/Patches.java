package io.github.davdxpx.mistel.mncf;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Patch files of the Switch's code modules (section 15): IPS, IPS32 and IPSwitch text
 * (.pchtxt), as Atmosphère's exefs_patches and the emulators load them.
 */
public final class Patches {

    /** What a patch file's offsets count before the module's image: the NSO file's header. */
    public static final int NSO_HEADER = 0x100;

    private static final Pattern HEX = Pattern.compile("[0-9A-Fa-f]+");
    private static final BigInteger HEADER = BigInteger.valueOf(NSO_HEADER);

    private Patches() {
    }

    /**
     * Reads the patch file in these bytes: IPS or IPS32 by their magic, else IPSwitch text in
     * UTF-8, with or without a byte order mark.
     *
     * @throws PatchFileException for bytes that are neither
     */
    public static PatchFile read(byte[] data) throws PatchFileException {
        if (startsWith(data, "PATCH") || startsWith(data, "IPS32")) {
            return readIps(data);
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            data = Arrays.copyOfRange(data, 3, data.length);
        }
        if (Utf8.check(data) != null) {
            throw new PatchFileException("neither an IPS file nor IPSwitch text in UTF-8");
        }
        return readPchtxt(new String(data, StandardCharsets.UTF_8));
    }

    private static boolean startsWith(byte[] data, String magic) {
        if (data.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (data[i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads an IPS or IPS32 file: PATCH or IPS32, then records up to EOF or EEOF. A record is an
     * offset (3 or 4 bytes, big-endian), a size (2 bytes) and that many bytes; a size of 0 is a
     * run: a count (2 bytes) and the byte it repeats. Whatever follows the end marker is ignored.
     *
     * @throws PatchFileException for a file without the magic, or one that ends too early
     */
    public static PatchFile readIps(byte[] data) throws PatchFileException {
        String format;
        int width;
        String end;
        if (startsWith(data, "IPS32")) {
            format = "ips32";
            width = 4;
            end = "EEOF";
        } else if (startsWith(data, "PATCH")) {
            format = "ips";
            width = 3;
            end = "EOF";
        } else {
            throw new PatchFileException("not an IPS file: it starts with neither PATCH nor IPS32");
        }
        List<Patch.Record> records = new ArrayList<>();
        int i = 5;
        while (true) {
            if (startsAt(data, i, end)) {
                return new PatchFile(format, Collections.singletonList(new Patch("", true, records)), null,
                        Collections.<PatchFile.Line>emptyList(), Collections.<PatchFile.Line>emptyList());
            }
            if ((long) i + width + 2 > data.length) {
                throw new PatchFileException(String.format("the file ends at 0x%X without %s", i, end));
            }
            long offset = 0;
            for (int k = 0; k < width; k++) {
                offset = offset << 8 | (data[i + k] & 0xFF);
            }
            int size = (data[i + width] & 0xFF) << 8 | (data[i + width + 1] & 0xFF);
            i += width + 2;
            if (size == 0) {
                if ((long) i + 3 > data.length) {
                    throw new PatchFileException(String.format("a run at 0x%X is cut off", i));
                }
                int count = (data[i] & 0xFF) << 8 | (data[i + 1] & 0xFF);
                byte value = data[i + 2];
                i += 3;
                if (count > 0) {
                    byte[] run = new byte[count];
                    Arrays.fill(run, value);
                    records.add(new Patch.Record(BigInteger.valueOf(offset), run));
                }
            } else {
                if ((long) i + size > data.length) {
                    throw new PatchFileException(String.format("a record at 0x%X claims %d bytes past the end of the "
                            + "file", i, size));
                }
                records.add(new Patch.Record(BigInteger.valueOf(offset), Arrays.copyOfRange(data, i, i + size)));
                i += size;
            }
        }
    }

    private static boolean startsAt(byte[] data, int at, String marker) {
        if ((long) at + marker.length() > data.length) {
            return false;
        }
        for (int k = 0; k < marker.length(); k++) {
            if (data[at + k] != (byte) marker.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    // IPSwitch text

    /** The index of the double quote that closes the string opening at start, or -1. */
    private static int stringEnd(String text, int start) {
        int k = start + 1;
        while (k < text.length()) {
            char c = text.charAt(k);
            if (c == '\\') {
                k += 2;
            } else if (c == '"') {
                return k;
            } else {
                k++;
            }
        }
        return -1;
    }

    /** The line without a // comment that is not inside a string, and the comment, or null without one. */
    private static String[] uncomment(String line) {
        int k = 0;
        while (k < line.length()) {
            char c = line.charAt(k);
            if (c == '"') {
                int end = stringEnd(line, k);
                if (end < 0) {
                    return new String[] {line, null};
                }
                k = end + 1;
            } else if (line.startsWith("//", k)) {
                return new String[] {line.substring(0, k), Py.strip(line.substring(k + 2))};
            } else {
                k++;
            }
        }
        return new String[] {line, null};
    }

    private static char escape(char c) {
        switch (c) {
            case 'n':
                return '\n';
            case 't':
                return '\t';
            case 'r':
                return '\r';
            case '0':
                return (char) 0;
            case '\\':
            case '"':
            case '\'':
                return c;
            default:
                return (char) 0xFFFF;
        }
    }

    private static byte[] string(String text) {
        StringBuilder out = new StringBuilder();
        int k = 0;
        while (k < text.length()) {
            char c = text.charAt(k);
            if (c == '\\' && k + 1 < text.length() && escape(text.charAt(k + 1)) != (char) 0xFFFF) {
                out.append(escape(text.charAt(k + 1)));
                k += 2;
            } else {
                out.append(c);
                k++;
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The bytes of a patch line's value, or null: a string in double quotes, or hexadecimal
     * digits in pairs, which @little-endian reverses.
     */
    private static byte[] value(String text, boolean little) {
        text = Py.strip(text);
        if (text.startsWith("\"")) {
            int end = stringEnd(text, 0);
            return end > 0 && Py.strip(text.substring(end + 1)).isEmpty() ? string(text.substring(1, end)) : null;
        }
        String digits = String.join("", Py.splitSpace(text));
        if (digits.isEmpty() || digits.length() % 2 != 0 || !Py.matches(HEX, digits)) {
            return null;
        }
        byte[] raw = new byte[digits.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
        }
        if (little) {
            for (int i = 0, j = raw.length - 1; i < j; i++, j--) {
                byte t = raw[i];
                raw[i] = raw[j];
                raw[j] = t;
            }
        }
        return raw;
    }

    /** A patch of an IPSwitch file while it is read. */
    private static final class Draft {
        final String name;
        final boolean enabled;
        final List<Patch.Record> records = new ArrayList<>();

        Draft(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }
    }

    /**
     * Reads IPSwitch text: directives, comments, and patches that @enabled or @disabled start and
     * the next of them or @stop ends. The last comment on a line of its own before a patch names
     * it. A patch line is an offset of 8 hexadecimal digits, a space and a value; @flag
     * offset_shift adds to every offset after it.
     *
     * @throws PatchFileException for an @nsobid- without a build ID in hexadecimal, or an
     *     offset_shift without a number
     */
    public static PatchFile readPchtxt(String text) throws PatchFileException {
        List<Draft> patches = new ArrayList<>();
        List<PatchFile.Line> notes = new ArrayList<>();
        List<PatchFile.Line> comments = new ArrayList<>();
        String buildId = null;
        BigInteger shift = BigInteger.ZERO;
        boolean little = false;
        String last = "";
        Draft current = null;
        List<String> lines = Py.split(text.replace("\r\n", "\n"), '\n');
        for (int n = 1; n <= lines.size(); n++) {
            String rawLine = lines.get(n - 1);
            String[] parts = uncomment(rawLine);
            String stripped = Py.strip(parts[0]);
            String comment = parts[1];
            if (comment != null && !comment.isEmpty()) {
                comments.add(new PatchFile.Line(n, comment));
                if (stripped.isEmpty()) {
                    last = comment;       // a comment on a line of its own names the next patch; one after code does not
                }
            }
            if (stripped.isEmpty()) {
                continue;
            }
            String lower = stripped.toLowerCase(Locale.ROOT);
            if (lower.startsWith("@stop")) {
                break;
            }
            if (lower.startsWith("@nsobid-")) {
                String bid = Py.strip(stripped.substring(8));
                if (!Py.matches(HEX, bid) || bid.length() > 64) {
                    throw new PatchFileException("line " + n + ": @nsobid- needs a build ID in hexadecimal");
                }
                buildId = trimBuildId(bid);
            } else if (lower.startsWith("@flag")) {
                List<String> words = Py.splitSpace(stripped);
                if (words.size() >= 3 && words.get(1).toLowerCase(Locale.ROOT).equals("offset_shift")) {
                    BigInteger v = Py.parseIntAuto(words.get(2));
                    if (v == null) {
                        throw new PatchFileException("line " + n + ": offset_shift needs a number");
                    }
                    shift = v;
                }
            } else if (lower.startsWith("@little-endian")) {
                little = true;
            } else if (lower.startsWith("@big-endian")) {
                little = false;
            } else if (lower.startsWith("@enabled") || lower.startsWith("@disabled")) {
                current = new Draft(last, lower.startsWith("@enabled"));
                patches.add(current);
            } else if (stripped.startsWith("#")) {
                notes.add(new PatchFile.Line(n, Py.strip(stripped.substring(1))));
            } else {
                String[] split = splitOnce(stripped);
                byte[] value = split.length == 2 ? value(split[1], little) : null;
                if (current == null || split[0].length() != 8 || !Py.matches(HEX, split[0]) || value == null) {
                    notes.add(new PatchFile.Line(n, Py.strip(rawLine)));
                    continue;
                }
                current.records.add(new Patch.Record(new BigInteger(split[0], 16).add(shift), value));
            }
        }
        List<Patch> out = new ArrayList<>();
        for (Draft d : patches) {
            out.add(new Patch(d.name, d.enabled, d.records));
        }
        return new PatchFile("pchtxt", out, buildId, notes, comments);
    }

    /** Python's str.split(None, 1) of a text without white space at its ends. */
    private static String[] splitOnce(String s) {
        int i = 0;
        while (i < s.length() && !Py.isSpace(s.charAt(i))) {
            i++;
        }
        if (i == s.length()) {
            return new String[] {s};
        }
        int j = i;
        while (j < s.length() && Py.isSpace(s.charAt(j))) {
            j++;
        }
        return new String[] {s.substring(0, i), s.substring(j)};
    }

    // Build IDs

    /** The suffixes of patch file names, with each letter as Python's re.IGNORECASE matches it. */
    private static final String[][] SUFFIXES = {{".", "i", "p", "s"}, {".", "i", "p", "s", "3", "2"},
        {".", "p", "c", "h", "t", "x", "t"}};

    private static boolean sameLetter(char c, String letter) {
        char l = letter.charAt(0);
        if (Character.toLowerCase(c) == l && c < 128) {
            return true;
        }
        if (l == 'i') {
            return c == 0x130 || c == 0x131;       // capital I with a dot, small dotless i
        }
        if (l == 's') {
            return c == 0x17F;                     // long s
        }
        return false;
    }

    /** The file name without .ips, .ips32 or .pchtxt at its end, in any case. */
    private static String stem(String name) {
        for (String[] suffix : SUFFIXES) {
            int at = name.length() - suffix.length;
            if (at < 0) {
                continue;
            }
            boolean ok = true;
            for (int k = 0; k < suffix.length && ok; k++) {
                ok = sameLetter(name.charAt(at + k), suffix[k]);
            }
            if (ok) {
                return name.substring(0, at);
            }
        }
        return name;
    }

    /**
     * Returns the build ID that a patch file's name gives, "&lt;build ID&gt;.ips": an even number of
     * hexadecimal digits, 16 to 64. The zero bytes at its end are left out, down to 16 digits.
     * Null when the name is no build ID.
     */
    public static String buildIdOf(String fileName) {
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        String stem = stem(name);
        if (stem.length() < 16 || stem.length() > 64 || stem.length() % 2 != 0 || !Py.matches(HEX, stem)) {
            return null;
        }
        return trimBuildId(stem);
    }

    /** Returns a build ID in upper case, without the zero bytes at its end, down to 16 digits. */
    public static String trimBuildId(String text) {
        String t = text.toUpperCase(Locale.ROOT);
        while (t.codePointCount(0, t.length()) > 16 && t.endsWith("00")) {
            t = t.substring(0, t.length() - 2);
        }
        return t;
    }

    // Into a module

    /** A run of consecutive bytes a patch writes into a module's image. Immutable. */
    public static final class Run {
        private final long offset;
        private final byte[] data;

        Run(long offset, byte[] data) {
            this.offset = offset;
            this.data = data;
        }

        /** Returns the offset in the image. */
        public long offset() {
            return offset;
        }

        /** Returns the bytes. */
        public byte[] data() {
            return data.clone();
        }

        byte[] dataShared() {
            return data;
        }
    }

    /** What a patch writes into a module's image, and what it leaves out. Immutable. */
    public static final class ImageRuns {
        private final List<Run> runs;
        private final List<String> dropped;

        ImageRuns(List<Run> runs, List<String> dropped) {
            this.runs = Collections.unmodifiableList(new ArrayList<>(runs));
            this.dropped = Collections.unmodifiableList(new ArrayList<>(dropped));
        }

        /** Returns the runs of consecutive bytes, in order of offset. */
        public List<Run> runs() {
            return runs;
        }

        /** Returns what was left out, such as "0x10: 4 bytes in the NSO header". */
        public List<String> dropped() {
            return dropped;
        }
    }

    private static String bytes(BigInteger n) {
        return n.equals(BigInteger.ONE) ? "1 byte" : n + " bytes";
    }

    private static String hex(BigInteger n) {
        return (n.signum() < 0 ? "-0x" : "0x") + n.abs().toString(16).toUpperCase(Locale.ROOT);
    }

    /**
     * Returns the bytes a patch writes into a module's image of size bytes, as runs of
     * consecutive bytes in order of offset, and what it left out. A later record wins over an
     * earlier one at the same byte; the part of a record inside the NSO header or past the image
     * is left out, as loaders do.
     */
    public static ImageRuns imageRuns(Patch patch, long size) {
        TreeMap<Long, Byte> written = new TreeMap<>();
        List<String> dropped = new ArrayList<>();
        BigInteger big = BigInteger.valueOf(size);
        for (Patch.Record r : patch.records()) {
            byte[] data = r.dataShared();
            BigInteger start = r.offset().subtract(HEADER);
            if (start.bitLength() < 62) {
                long s = start.longValue();
                for (int k = 0; k < data.length; k++) {
                    long at = s + k;
                    if (at >= 0 && at < size) {
                        written.put(at, data[k]);
                    }
                }
            }
            BigInteger length = BigInteger.valueOf(data.length);
            if (start.signum() < 0) {
                dropped.add(hex(r.offset()) + ": " + bytes(length.min(start.negate())) + " in the NSO header");
            }
            if (start.add(length).compareTo(big) > 0) {
                dropped.add(hex(r.offset()) + ": " + bytes(length.min(start.add(length).subtract(start.max(big))))
                        + " past the module");
            }
        }
        List<Run> runs = new ArrayList<>();
        long runStart = -1;
        ByteArrayOutputStream run = new ByteArrayOutputStream();
        for (Map.Entry<Long, Byte> e : written.entrySet()) {
            long at = e.getKey();
            if (runStart >= 0 && runStart + run.size() == at) {
                run.write(e.getValue());
                continue;
            }
            if (runStart >= 0) {
                runs.add(new Run(runStart, run.toByteArray()));
            }
            runStart = at;
            run.reset();
            run.write(e.getValue());
        }
        if (runStart >= 0) {
            runs.add(new Run(runStart, run.toByteArray()));
        }
        return new ImageRuns(runs, dropped);
    }
}
