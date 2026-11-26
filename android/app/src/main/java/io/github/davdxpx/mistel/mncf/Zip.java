package io.github.davdxpx.mistel.mncf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A ZIP archive in memory, read through its central directory as the reference reader's ZIP
 * module reads it: the end record is searched for in the last 64 KiB, data before the archive
 * is allowed, and names are UTF-8 when the entry says so and code page 437 otherwise. An entry's
 * name is its raw name; the reader does not shorten it or take another from an extra field.
 * Stored and deflated entries that are not encrypted can be read; their CRC-32 is checked.
 */
final class Zip {

    /** The archive cannot be read. */
    static final class ZipError extends Exception {
        private static final long serialVersionUID = 1L;

        ZipError(String message) {
            super(message);
        }
    }

    /** One entry of the central directory. */
    static final class Entry {
        String name;
        String originalName;
        int flags;
        int method;
        long crc;
        long compressedSize;
        long size;
        long headerOffset;
        /** Where the next entry's local header starts, or the central directory. */
        long endOffset;
    }

    private static final int END_SIZE = 22;
    private static final int END64_SIZE = 56;
    private static final int LOCATOR_SIZE = 20;
    private static final int CENTRAL_SIZE = 46;
    private static final int LOCAL_SIZE = 30;
    private static final int MAX_COMMENT = 0xFFFF;
    private static final int MAX_EXTRACT_VERSION = 63;

    /** The characters of code page 437 from 0x80 to 0xFF; the bytes below are ASCII. */
    private static final String CP437_HIGH =
            "\u00C7\u00FC\u00E9\u00E2\u00E4\u00E0\u00E5\u00E7\u00EA\u00EB\u00E8\u00EF\u00EE\u00EC\u00C4\u00C5"
            + "\u00C9\u00E6\u00C6\u00F4\u00F6\u00F2\u00FB\u00F9\u00FF\u00D6\u00DC\u00A2\u00A3\u00A5\u20A7\u0192"
            + "\u00E1\u00ED\u00F3\u00FA\u00F1\u00D1\u00AA\u00BA\u00BF\u2310\u00AC\u00BD\u00BC\u00A1\u00AB\u00BB"
            + "\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255D\u255C\u255B\u2510"
            + "\u2514\u2534\u252C\u251C\u2500\u253C\u255E\u255F\u255A\u2554\u2569\u2566\u2560\u2550\u256C\u2567"
            + "\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256B\u256A\u2518\u250C\u2588\u2584\u258C\u2590\u2580"
            + "\u03B1\u00DF\u0393\u03C0\u03A3\u03C3\u00B5\u03C4\u03A6\u0398\u03A9\u03B4\u221E\u03C6\u03B5\u2229"
            + "\u2261\u00B1\u2265\u2264\u2320\u2321\u00F7\u2248\u00B0\u2219\u00B7\u221A\u207F\u00B2\u25A0\u00A0";

    private final byte[] data;
    private final List<Entry> entries;
    private final Map<String, Entry> byName;

    private Zip(byte[] data, List<Entry> entries) {
        this.data = data;
        this.entries = Collections.unmodifiableList(entries);
        Map<String, Entry> names = new LinkedHashMap<>();
        for (Entry e : entries) {
            names.put(e.name, e);
        }
        this.byName = names;
    }

    List<Entry> entries() {
        return entries;
    }

    Entry entry(String name) {
        return byName.get(name);
    }

    // Little-endian fields

    private static int u16(byte[] b, long at) {
        int i = (int) at;
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, long at) {
        return u16(b, at) | (long) u16(b, at + 2) << 16;
    }

    private static long u64(byte[] b, long at) {
        return u32(b, at) | u32(b, at + 4) << 32;
    }

    private static boolean signature(byte[] b, long at, int third, int fourth) {
        int i = (int) at;
        return b[i] == 'P' && b[i + 1] == 'K' && b[i + 2] == third && b[i + 3] == fourth;
    }

    /** The bytes from a position, at most length of them, fewer where the data ends. */
    private static byte[] slice(byte[] b, long from, long length) {
        if (from < 0 || from >= b.length || length <= 0) {
            return new byte[0];
        }
        long end = Math.min(b.length, from + length);
        return Arrays.copyOfRange(b, (int) from, (int) end);
    }

    /** Reads the central directory of an archive. */
    static Zip open(byte[] data) throws ZipError {
        long[] end = endRecord(data);
        if (end == null) {
            throw new ZipError("File is not a zip file");
        }
        long sizeCd = end[0];
        long offsetCd = end[1];
        long location = end[2];
        boolean zip64 = end[3] != 0;
        long concat = location - sizeCd - offsetCd;
        if (zip64) {
            concat -= END64_SIZE + LOCATOR_SIZE;
        }
        long startDir = offsetCd + concat;
        if (startDir < 0 || sizeCd < 0) {
            throw new ZipError("Bad offset for central directory");
        }
        byte[] cd = slice(data, startDir, sizeCd);
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        int pos = 0;
        while (total < sizeCd) {
            if (cd.length - pos < CENTRAL_SIZE) {
                throw new ZipError("Truncated central directory");
            }
            if (!signature(cd, pos, 1, 2)) {
                throw new ZipError("Bad magic number for central directory");
            }
            int extractVersion = cd[pos + 6] & 0xFF;
            int flags = u16(cd, pos + 8);
            int method = u16(cd, pos + 10);
            long crc = u32(cd, pos + 16);
            long compressedSize = u32(cd, pos + 20);
            long size = u32(cd, pos + 24);
            int nameLength = u16(cd, pos + 28);
            int extraLength = u16(cd, pos + 30);
            int commentLength = u16(cd, pos + 32);
            long headerOffset = u32(cd, pos + 42);
            int at = pos + CENTRAL_SIZE;
            byte[] rawName = slice(cd, at, nameLength);
            at += rawName.length;
            byte[] extra = slice(cd, at, extraLength);
            at += extra.length;
            at += slice(cd, at, commentLength).length;
            pos = at;
            Entry e = new Entry();
            e.originalName = name(rawName, flags);
            e.name = e.originalName;
            e.flags = flags;
            e.method = method;
            e.crc = crc;
            e.compressedSize = compressedSize;
            e.size = size;
            e.headerOffset = headerOffset;
            if (extractVersion > MAX_EXTRACT_VERSION) {
                throw new ZipError("zip file version " + extractVersion / 10.0 + " is not supported");
            }
            decodeExtra(e, extra, crc32(rawName));
            e.headerOffset += concat;
            entries.add(e);
            total += CENTRAL_SIZE + nameLength + extraLength + commentLength;
        }
        List<Entry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(a.headerOffset, b.headerOffset);
            }
        });
        long endOffset = startDir;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            sorted.get(i).endOffset = endOffset;
            endOffset = sorted.get(i).headerOffset;
        }
        return new Zip(data, entries);
    }

    /** The size and offset of the central directory, the end record's position and whether it is ZIP64. */
    private static long[] endRecord(byte[] data) throws ZipError {
        int size = data.length;
        if (size >= END_SIZE) {
            int at = size - END_SIZE;
            if (signature(data, at, 5, 6) && data[size - 2] == 0 && data[size - 1] == 0) {
                return end64(data, at, new long[] {u32(data, at + 12), u32(data, at + 16), at, 0});
            }
        }
        int from = Math.max(size - MAX_COMMENT - END_SIZE, 0);
        int start = -1;
        for (int i = size - 4; i >= from; i--) {
            if (signature(data, i, 5, 6)) {
                start = i;
                break;
            }
        }
        if (start < 0 || size - start < END_SIZE) {
            return null;
        }
        return end64(data, start, new long[] {u32(data, start + 12), u32(data, start + 16), start, 0});
    }

    private static long[] end64(byte[] data, int endAt, long[] record) throws ZipError {
        byte[] locator = slice(data, Math.max(0, endAt - LOCATOR_SIZE), LOCATOR_SIZE);
        if (locator.length != LOCATOR_SIZE || !signature(locator, 0, 6, 7)) {
            return record;
        }
        long disk = u32(locator, 4);
        long disks = u32(locator, 16);
        if (disk != 0 || disks > 1) {
            throw new ZipError("zipfiles that span multiple disks are not supported");
        }
        byte[] end = slice(data, Math.max(0, endAt - LOCATOR_SIZE - END64_SIZE), END64_SIZE);
        if (end.length != END64_SIZE || !signature(end, 0, 6, 6)) {
            return record;
        }
        return new long[] {u64(end, 40), u64(end, 48), record[2], 1};
    }

    private static void decodeExtra(Entry e, byte[] extra, long nameCrc) throws ZipError {
        int at = 0;
        while (extra.length - at >= 4) {
            int type = u16(extra, at);
            int length = u16(extra, at + 2);
            if (length + 4 > extra.length - at) {
                throw new ZipError(String.format("Corrupt extra field %04x (size=%d)", type, length));
            }
            byte[] field = Arrays.copyOfRange(extra, at + 4, at + 4 + length);
            if (type == 0x0001) {
                int f = 0;
                if (e.size == 0xFFFFFFFFL) {
                    f = need(field, f, "File size");
                    e.size = u64(field, f - 8);
                }
                if (e.compressedSize == 0xFFFFFFFFL) {
                    f = need(field, f, "Compress size");
                    e.compressedSize = u64(field, f - 8);
                }
                if (e.headerOffset == 0xFFFFFFFFL) {
                    f = need(field, f, "Header offset");
                    e.headerOffset = u64(field, f - 8);
                }
            } else if (type == 0x7075) {
                if (field.length < 5) {
                    throw new ZipError("Corrupt unicode path extra field (0x7075)");
                }
                if (field[0] == 1 && u32(field, 1) == nameCrc) {
                    byte[] name = Arrays.copyOfRange(field, 5, field.length);
                    if (Utf8.check(name) != null) {
                        throw new ZipError("Corrupt unicode path extra field (0x7075): invalid utf-8 bytes");
                    }
                }
            }
            at += 4 + length;
        }
    }

    private static int need(byte[] field, int f, String what) throws ZipError {
        if (field.length - f < 8) {
            throw new ZipError("Corrupt zip64 extra field. " + what + " not found.");
        }
        return f + 8;
    }

    private static long crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static String name(byte[] raw, int flags) throws ZipError {
        if ((flags & 0x800) != 0) {
            if (Utf8.check(raw) != null) {
                throw new ZipError("an entry name is not UTF-8");
            }
            return new String(raw, StandardCharsets.UTF_8);
        }
        StringBuilder b = new StringBuilder(raw.length);
        for (byte x : raw) {
            int c = x & 0xFF;
            b.append(c < 0x80 ? (char) c : CP437_HIGH.charAt(c - 0x80));
        }
        return b.toString();
    }

    /** Whether an entry is stored or deflated and not encrypted: the only entries this reader reads. */
    static boolean readable(Entry e) {
        return (e.method == 0 || e.method == 8) && (e.flags & 0x1) == 0;
    }

    /** Reads an entry's bytes, checking its local header, its place in the archive and its CRC-32. */
    byte[] read(Entry e) throws ZipError {
        return read(e, Long.MAX_VALUE);
    }

    /**
     * Reads at most limit bytes of an entry, whatever its header claims. The CRC-32 is checked
     * when the read reaches the entry's end.
     */
    byte[] read(Entry e, long limit) throws ZipError {
        if (!readable(e)) {
            throw new ZipError(e.name + ": neither stored nor deflated, or encrypted");
        }
        long h = e.headerOffset;
        if (h < 0 || h > data.length - LOCAL_SIZE) {
            throw new ZipError("Truncated file header");
        }
        if (!signature(data, h, 3, 4)) {
            throw new ZipError("Bad magic number for file header");
        }
        int localFlags = u16(data, h + 6);
        int nameLength = u16(data, h + 26);
        int extraLength = u16(data, h + 28);
        byte[] rawName = slice(data, h + LOCAL_SIZE, nameLength);
        if ((e.flags & 0x20) != 0) {
            throw new ZipError("compressed patched data (flag bit 5)");
        }
        if ((e.flags & 0x40) != 0) {
            throw new ZipError("strong encryption (flag bit 6)");
        }
        if (!name(rawName, localFlags).equals(e.originalName)) {
            throw new ZipError("File name in directory and header differ");
        }
        long start = h + LOCAL_SIZE + rawName.length + extraLength;
        // Sizes of 2^63 and more come only from a ZIP64 field; they are as good as endless.
        boolean beyond = e.compressedSize < 0 || start + e.compressedSize > e.endOffset;
        if (beyond && e.endOffset != e.headerOffset) {
            throw new ZipError("Overlapped entries (possible zip bomb)");
        }
        if ((e.flags & 0x1) != 0) {
            throw new ZipError("the entry is encrypted");
        }
        if (e.compressedSize < 0 || start + e.compressedSize > data.length) {
            throw new ZipError("the entry's data is cut off");
        }
        long size = e.size < 0 ? Long.MAX_VALUE : e.size;
        long cap = Math.min(size, limit);
        byte[] out;
        if (e.method == 0) {
            out = Arrays.copyOfRange(data, (int) start, (int) (start + Math.min(e.compressedSize, cap)));
        } else if (e.method == 8) {
            out = inflate(Arrays.copyOfRange(data, (int) start, (int) (start + e.compressedSize)), cap);
        } else {
            throw new ZipError("compression type " + e.method + " is not supported");
        }
        if (cap == size && crc32(out) != e.crc) {
            throw new ZipError("Bad CRC-32 for file " + Py.repr(e.name));
        }
        return out;
    }

    private static byte[] inflate(byte[] compressed, long size) throws ZipError {
        Inflater inflater = new Inflater(true);
        try {
            // The extra byte is the dummy input that zlib may want after raw deflate data.
            inflater.setInput(Arrays.copyOf(compressed, compressed.length + 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long left = size;
            while (left > 0 && !inflater.finished()) {
                int n = inflater.inflate(buffer, 0, (int) Math.min(buffer.length, left));
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
                left -= n;
            }
            return out.toByteArray();
        } catch (DataFormatException ex) {
            throw new ZipError("the entry's deflate data is broken: " + ex.getMessage());
        } finally {
            inflater.end();
        }
    }
}
