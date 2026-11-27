package dev.peanuts.mii;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Miis from another Switch emulator (GitHub issue #320). Eden, yuzu and Ryujinx keep the console's Mii database as the Switch does: MiiDatabase.dat in the
 * mii service's system save 8000000000000030, a NintendoFigurineDatabase ("NFDB": u32 magic, 100 StoreData of 0x44 bytes, u8 version 1, u8 count, u16 CRC;
 * 0x1A98 bytes). Accepted here: that file, a zip that holds it (an emulator's exported user data; the zip is read as a stream, only that entry is kept), one
 * StoreData (0x44 bytes) or one CharInfo (0x58 bytes, a ".charinfo" file). A StoreData's CoreData packs the fields into seven little-endian words
 * ({@link #fromStoreData}); a CharInfo has them a byte each at MiiStore's offsets. The layout follows the console's nn::mii types as Eden's mii service
 * (core_data.h, store_data.h, mii_database.h) describes them; no code of it is used. No Android classes: MiiImportJvmTest runs it on the JVM.
 */
public final class MiiImport {
    private MiiImport() {}

    public static final int DATABASE = 0x1A98, STORE_DATA = 0x44, MAGIC = 0x4244464E;   // 'NFDB' little-endian
    private static final int ENTRY_MAX = 1 << 20;   // the database is 6.8 KiB; anything else that large is not one

    /** What a file held: its Miis (in the file's order, create IDs kept) and what it was, for the player ("a Mii database", "a Mii") */
    public static final class Found {
        public final List<MiiStore.Mii> miis;
        public final String kind;
        Found(List<MiiStore.Mii> miis, String kind) { this.miis = miis; this.kind = kind; }
    }

    /** Reads a picked file: a zip is searched for MiiDatabase.dat (the one in 8000000000000030 first), anything else is read whole up to 1 MiB */
    public static Found read(InputStream in) throws IOException {
        BufferedInputStream b = new BufferedInputStream(in, 1 << 16);
        b.mark(4);
        byte[] head = new byte[4];
        int n = dev.nxnative.android.Streams.readInto(b, head, 0, 4);   // b.readNBytes from API 33, a loop on Android 12
        b.reset();
        if (n == 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) return fromZip(b);
        byte[] all = readAtMost(b, ENTRY_MAX);
        return all == null ? new Found(new ArrayList<>(), "too large") : parse(all);
    }

    private static Found fromZip(InputStream in) throws IOException {
        byte[] best = null;
        boolean bestInSave = false;
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String name = e.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                if (e.isDirectory() || !name.endsWith("miidatabase.dat")) continue;
                boolean inSave = name.contains("8000000000000030");
                if (best != null && (bestInSave || !inSave)) continue;
                byte[] d = readAtMost(z, ENTRY_MAX);
                if (d != null && d.length == DATABASE) { best = d; bestInSave = inSave; }
                if (bestInSave) break;
            }
        }
        if (best == null) return new Found(new ArrayList<>(), "a zip without a Mii database");
        return parse(best);
    }

    private static byte[] readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 14];
        for (int r; (r = in.read(buf)) > 0; ) {
            o.write(buf, 0, r);
            if (o.size() > max) return null;
        }
        return o.toByteArray();
    }

    /** One file's bytes: a database, one StoreData or one CharInfo; anything else holds no Mii */
    public static Found parse(byte[] d) {
        List<MiiStore.Mii> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d.length == DATABASE && b.getInt(0) == MAGIC) {
            int count = Math.min(d[4 + 100 * STORE_DATA + 1] & 0xff, 100);
            for (int i = 0; i < count; i++) {
                MiiStore.Mii m = fromStoreData(d, 4 + i * STORE_DATA);
                if (m != null) out.add(m);
            }
            return new Found(out, "a Mii database");
        }
        if (d.length == STORE_DATA) { MiiStore.Mii m = fromStoreData(d, 0); if (m != null) out.add(m); return new Found(out, "a Mii"); }
        if (d.length == MiiStore.CHARINFO) { MiiStore.Mii m = fromCharInfo(d, 0); if (m != null) out.add(m); return new Found(out, "a Mii"); }
        return new Found(out, "no Mii file");
    }

    private static int bits(int word, int at, int n) { return (word >>> at) & ((1 << n) - 1); }

    /** A StoreData at off: CoreData (seven words of fields, then the nickname of 10 UTF-16 units), the create ID, two CRCs. Null for an empty slot */
    static MiiStore.Mii fromStoreData(byte[] d, int off) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id = new byte[16];
        System.arraycopy(d, off + 0x30, id, 0, 16);
        if (empty(id)) return null;
        int w0 = b.getInt(off), w1 = b.getInt(off + 4), w2 = b.getInt(off + 8), w3 = b.getInt(off + 12), w4 = b.getInt(off + 16), w5 = b.getInt(off + 20), w6 = b.getInt(off + 24);
        MiiStore.Mii m = new MiiStore.Mii(MiiStore.cleanName(name(b, off + 0x1C, 10)), id);
        m.set("hair_type", bits(w0, 0, 8)); m.set("height", bits(w0, 8, 7)); m.set("mole_type", bits(w0, 15, 1)); m.set("build", bits(w0, 16, 7));
        m.set("hair_flip", bits(w0, 23, 1)); m.set("hair_color", bits(w0, 24, 7));
        m.set("eye_color", bits(w1, 0, 7)); m.set("gender", bits(w1, 7, 1)); m.set("eyebrow_color", bits(w1, 8, 7)); m.set("mouth_color", bits(w1, 16, 7));
        m.set("beard_color", bits(w1, 24, 7));
        m.set("glass_color", bits(w2, 0, 7)); m.set("eye_type", bits(w2, 8, 6)); m.set("mouth_type", bits(w2, 16, 6)); m.set("eye_y", bits(w2, 24, 5));
        m.set("glass_scale", bits(w2, 29, 3));
        m.set("eyebrow_type", bits(w3, 0, 5)); m.set("mustache_type", bits(w3, 5, 3)); m.set("nose_type", bits(w3, 8, 5)); m.set("beard_type", bits(w3, 13, 3));
        m.set("nose_y", bits(w3, 16, 5)); m.set("mouth_aspect", bits(w3, 21, 3)); m.set("mouth_y", bits(w3, 24, 5)); m.set("eyebrow_aspect", bits(w3, 29, 3));
        m.set("mustache_y", bits(w4, 0, 5)); m.set("eye_rotate", bits(w4, 5, 3)); m.set("glass_y", bits(w4, 8, 5)); m.set("eye_aspect", bits(w4, 13, 3));
        m.set("mole_x", bits(w4, 16, 5)); m.set("eye_scale", bits(w4, 21, 3)); m.set("mole_y", bits(w4, 24, 5));
        m.set("glass_type", bits(w5, 0, 5)); m.set("favorite_color", bits(w5, 8, 4)); m.set("faceline_type", bits(w5, 12, 4)); m.set("faceline_color", bits(w5, 16, 4));
        m.set("faceline_wrinkle", bits(w5, 20, 4)); m.set("faceline_make", bits(w5, 24, 4)); m.set("eye_x", bits(w5, 28, 4));
        m.set("eyebrow_scale", bits(w6, 0, 4)); m.set("eyebrow_rotate", bits(w6, 4, 4)); m.set("eyebrow_x", bits(w6, 8, 4));
        m.set("eyebrow_y", bits(w6, 12, 4) + 3);   // kept 3 lower than the CharInfo's (whose range starts at 3)
        m.set("nose_scale", bits(w6, 16, 4)); m.set("mouth_scale", bits(w6, 20, 4)); m.set("mustache_scale", bits(w6, 24, 4)); m.set("mole_scale", bits(w6, 28, 4));
        return m;
    }

    /** A CharInfo at off: create ID, nickname of 11 UTF-16 units, then a byte a field at MiiStore's offsets. Null for an empty one */
    static MiiStore.Mii fromCharInfo(byte[] d, int off) {
        byte[] id = new byte[16];
        System.arraycopy(d, off, id, 0, 16);
        if (empty(id)) return null;
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        MiiStore.Mii m = new MiiStore.Mii(MiiStore.cleanName(name(b, off + 16, 11)), id);
        for (MiiStore.Field f : MiiStore.FIELDS) m.set(f.key, d[off + f.offset] & 0xff);
        return m;
    }

    private static String name(ByteBuffer b, int at, int units) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < units; i++) { char c = (char) b.getShort(at + 2 * i); if (c == 0) break; s.append(c); }
        return s.toString();
    }

    private static boolean empty(byte[] id) { for (byte x : id) if (x != 0) return false; return true; }

    /**
     * What an import adds to mine: the found Miis whose create ID is not among mine yet, as many as fit under MiiStore.MAX_MIIS. A Mii already there (the same
     * create ID) is skipped, not replaced
     */
    public static List<MiiStore.Mii> newOnes(List<MiiStore.Mii> mine, List<MiiStore.Mii> found) {
        List<MiiStore.Mii> out = new ArrayList<>();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (MiiStore.Mii m : mine) have.add(MiiStore.hex(m.createId));
        for (MiiStore.Mii m : found) {
            if (mine.size() + out.size() >= MiiStore.MAX_MIIS) break;
            if (have.add(MiiStore.hex(m.createId))) out.add(m);
        }
        return out;
    }
}
