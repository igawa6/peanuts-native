package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A code module for the converter: its image as loaded, its sections, its identity, and the runs
 * of bytes that are not its own. Made from a Switch NSO file (with LZ4 segments or without) or
 * from a plain image. Immutable.
 *
 * <p>The excluded runs are bytes such as code a runtime rewrote: the converter takes no original
 * bytes and no pattern from them. The module keeps the image array it is given without copying
 * it; the caller must not change it afterwards.
 */
public final class CodeModule {

    private static final int PAGE = 0x1000;
    private static final Pattern HEX = Pattern.compile("(0x)?([0-9A-Fa-f]{1,16})");

    /** A run of bytes of a module: its offset and its length. */
    public static final class Run {
        private final long offset;
        private final long length;

        /** Makes a run; the offset and the length are unsigned 64-bit numbers. */
        public Run(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }

        /** Returns the offset of the run's first byte. */
        public long offset() {
            return offset;
        }

        /** Returns the number of bytes. */
        public long length() {
            return length;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Run && ((Run) o).offset == offset && ((Run) o).length == length;
        }

        @Override
        public int hashCode() {
            return Long.valueOf(offset).hashCode() * 31 + Long.valueOf(length).hashCode();
        }

        @Override
        public String toString() {
            return "(0x" + Long.toHexString(offset) + ", " + length + ")";
        }
    }

    private final byte[] image;
    private final Map<String, long[]> sections;
    private final String buildId;
    private final Long textSize;
    private final List<Run> exclude;

    /**
     * Makes a module from its parts.
     *
     * @param image the module's bytes as loaded
     * @param sections "code", "rodata" and "data" to their offsets from start up to but not
     *     including end, as arrays of two numbers, in the order the module has them
     * @param buildId the Switch build ID in hexadecimal, or null
     * @param textSize where the code itself ends, before the zero bytes up to the next page, or null
     * @param exclude runs whose bytes are not the module's own, or null for none
     */
    public CodeModule(byte[] image, Map<String, long[]> sections, String buildId, Long textSize, List<Run> exclude) {
        this.image = image;
        Map<String, long[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : sections.entrySet()) {
            copy.put(e.getKey(), new long[] {e.getValue()[0], e.getValue()[1]});
        }
        this.sections = copy;
        this.buildId = buildId;
        this.textSize = textSize;
        this.exclude = exclude == null ? Collections.<Run>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(exclude));
    }

    /** Returns the same module with other excluded runs. */
    public CodeModule withExclude(List<Run> runs) {
        return new CodeModule(image, sections, buildId, textSize, runs);
    }

    /** Returns the module's bytes as loaded; the array itself, which callers must not change. */
    public byte[] image() {
        return image;
    }

    /** Returns the names of the sections, in the module's order. */
    public List<String> sectionNames() {
        return Collections.unmodifiableList(new ArrayList<>(sections.keySet()));
    }

    /** Returns the first offset of a section, or throws IllegalArgumentException when it has none of that name. */
    public long sectionStart(String name) {
        return section(name)[0];
    }

    /** Returns the offset just after a section. */
    public long sectionEnd(String name) {
        return section(name)[1];
    }

    private long[] section(String name) {
        long[] r = sections.get(name);
        if (r == null) {
            throw new IllegalArgumentException("the module has no section " + name);
        }
        return r;
    }

    /** Returns whether the module has a section of that name. */
    public boolean hasSection(String name) {
        return sections.containsKey(name);
    }

    /** Returns the Switch build ID in hexadecimal, or null. */
    public String buildId() {
        return buildId;
    }

    /** Returns where the code itself ends, or null when it is not known. */
    public Long textSize() {
        return textSize;
    }

    /** Returns the runs whose bytes are not the module's own. */
    public List<Run> exclude() {
        return exclude;
    }

    /** Returns the build ID as cheat files name it: its first 8 bytes, 16 upper-case hexadecimal digits. Or null. */
    public String shortId() {
        if (buildId == null || buildId.isEmpty()) {
            return null;
        }
        return buildId.substring(0, Math.min(16, buildId.length())).toUpperCase(Locale.ROOT);
    }

    /** Returns the name of the first section that holds an offset, or null. */
    public String sectionOf(long offset) {
        for (Map.Entry<String, long[]> e : sections.entrySet()) {
            if (e.getValue()[0] <= offset && offset < e.getValue()[1]) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * Returns whether any byte from offset on, length of them, lies in an excluded run. Offsets
     * and lengths are unsigned 64-bit numbers.
     */
    public boolean excluded(long offset, long length) {
        for (Run r : exclude) {
            if (belowSum(r.offset, offset, length) && belowSum(offset, r.offset, r.length)) {
                return true;
            }
        }
        return false;
    }

    /** Whether x is below a + b, all unsigned, the sum taken without wrapping. */
    private static boolean belowSum(long x, long a, long b) {
        long sum = a + b;
        return Long.compareUnsigned(sum, a) < 0 || Long.compareUnsigned(x, sum) < 0;
    }

    /**
     * Returns the module as the evaluator reads it, loaded at a base address: named by its short
     * build ID, or by the SHA-256 hash of its image when it has none.
     */
    public Module toModule(long base) {
        String id = shortId();
        if (id == null) {
            StringBuilder b = new StringBuilder("sha256:");
            for (byte x : Signatures.sha256(image)) {
                b.append(String.format("%02x", x & 0xFF));
            }
            id = b.toString();
        }
        Module.Builder m = Module.builder(id).base(base).image(image).size(image.length);
        for (Map.Entry<String, long[]> e : sections.entrySet()) {
            m.section(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        return m.build();
    }

    /** The module as the evaluator reads it at base 0, without an identity, for searching patterns. */
    Module toModuleWithoutId() {
        Module.Builder m = Module.builder(null).image(image).size(image.length);
        for (Map.Entry<String, long[]> e : sections.entrySet()) {
            m.section(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        return m.build();
    }

    // Reading module files

    /** Returns the module the bytes of a file hold: an NSO, or else a plain image that is all code. */
    public static CodeModule fromBytes(byte[] data) throws CodeModuleException {
        if (data.length >= 4 && data[0] == 'N' && data[1] == 'S' && data[2] == 'O' && data[3] == '0') {
            return parseNso(data);
        }
        Map<String, long[]> code = new LinkedHashMap<>();
        code.put("code", new long[] {0, data.length});
        return new CodeModule(data.clone(), code, null, null, null);
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    private static long align(long n) {
        return (n + PAGE - 1) & ~(long) (PAGE - 1);
    }

    /** Returns the module an NSO file holds. */
    public static CodeModule parseNso(byte[] data) throws CodeModuleException {
        if (data.length < 0x100 || data[0] != 'N' || data[1] != 'S' || data[2] != 'O' || data[3] != '0') {
            throw new CodeModuleException("not an NSO file");
        }
        long flags = u32(data, 0x0C);
        long[] mems = new long[3];
        byte[][] segs = new byte[3][];
        for (int i = 0; i < 3; i++) {
            int header = 0x10 + 0x10 * i;
            long fileOff = u32(data, header);
            long memOff = u32(data, header + 4);
            long size = u32(data, header + 8);
            long packed = u32(data, 0x60 + 4 * i);
            boolean compressed = (flags & (1L << i)) != 0;
            long stored = compressed ? packed : size;
            if (fileOff + stored > data.length) {
                throw new CodeModuleException("segment " + i + " lies past the end of the file");
            }
            byte[] raw = java.util.Arrays.copyOfRange(data, (int) fileOff, (int) (fileOff + stored));
            if (compressed && size > Integer.MAX_VALUE - 8) {
                throw new CodeModuleException("segment " + i + " is too large");
            }
            mems[i] = memOff;
            segs[i] = compressed ? Lz4.decompress(raw, (int) size) : raw;
        }
        long bss = u32(data, 0x3C);
        if (!(mems[0] <= mems[1] && mems[1] <= mems[2])) {
            throw new CodeModuleException("the segments are not in the order code, read-only data, data");
        }
        long length = mems[2] + segs[2].length + bss;
        if (length > Integer.MAX_VALUE - 8) {
            throw new CodeModuleException("the module is too large");
        }
        // Each segment goes in as Python's slice assignment puts it: a segment that runs past
        // the end makes the image longer.
        Bytes image = new Bytes((int) length);
        for (int i = 0; i < 3; i++) {
            image.assign(mems[i], segs[i]);
        }
        byte[] bytes = image.toArray();
        Map<String, long[]> sections = new LinkedHashMap<>();
        sections.put("code", new long[] {mems[0], Math.min(align(mems[0] + segs[0].length), mems[1])});
        sections.put("rodata", new long[] {mems[1], Math.min(align(mems[1] + segs[1].length), mems[2])});
        sections.put("data", new long[] {mems[2], bytes.length});
        StringBuilder id = new StringBuilder();
        for (int i = 0x40; i < 0x60; i++) {
            id.append(String.format("%02X", data[i] & 0xFF));
        }
        return new CodeModule(bytes, sections, id.toString(), mems[0] + segs[0].length, null);
    }

    /** A growable byte array with Python's slice assignment. */
    private static final class Bytes {
        private byte[] buf;
        private int size;

        Bytes(int size) {
            this.buf = new byte[size];
            this.size = size;
        }

        void assign(long at, byte[] seg) throws CodeModuleException {
            int start = (int) Math.min(at, size);
            int stop = (int) Math.min(at + seg.length, size);
            long newSize = (long) size - (stop - start) + seg.length;
            if (newSize > Integer.MAX_VALUE - 8) {
                throw new CodeModuleException("the module is too large");
            }
            byte[] out = newSize == size ? buf : new byte[(int) newSize];
            if (out != buf) {
                System.arraycopy(buf, 0, out, 0, start);
                System.arraycopy(buf, stop, out, start + seg.length, size - stop);
            }
            System.arraycopy(seg, 0, out, start, seg.length);
            buf = out;
            size = (int) newSize;
        }

        byte[] toArray() {
            return buf.length == size ? buf : java.util.Arrays.copyOf(buf, size);
        }
    }

    /**
     * Returns runs from lines of two hexadecimal numbers separated by a tab, such as a list of
     * the places a runtime rewrote: up to 16 digits each, maybe after 0x. Lines that are empty
     * or start with # are skipped.
     *
     * @throws CodeModuleException for a line that is not an offset and a length
     */
    public static List<Run> readRuns(String text) throws CodeModuleException {
        List<Run> runs = new ArrayList<>();
        List<String> lines = Py.splitLines(text);
        for (int n = 1; n <= lines.size(); n++) {
            String line = Py.strip(lines.get(n - 1));
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<String> parts = Py.split(line, '\t');
            Matcher offset = parts.size() < 2 ? null : Py.match(HEX, Py.strip(parts.get(0)));
            Matcher length = parts.size() < 2 ? null : Py.match(HEX, Py.strip(parts.get(1)));
            if (offset == null || length == null) {
                throw new CodeModuleException("line " + n + " is not an offset and a length");
            }
            runs.add(new Run(Long.parseUnsignedLong(offset.group(2), 16), Long.parseUnsignedLong(length.group(2), 16)));
        }
        return runs;
    }
}
