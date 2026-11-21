// PEANUTS: Miis from another emulator (GitHub issue #320)
package dev.peanuts.mii;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MiiImport on the JVM (peanuts/tests/test_mii_import.py): a Mii database in the console's layout, a zip of an emulator's user data that holds it, one
 * StoreData, one CharInfo, what is not a Mii file, and which found Miis an import adds. The test packs its own StoreData (the CoreData words as the console's
 * nn::mii lays them out), so every field and its bit position is checked against the reader.
 */
public final class MiiImportJvmTest {
    static int fails;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    /** The fields of each CoreData word: key, first bit, bits (the console's StoreDataBitFields) */
    static final Object[][][] WORDS = {
        {{"hair_type", 0, 8}, {"height", 8, 7}, {"mole_type", 15, 1}, {"build", 16, 7}, {"hair_flip", 23, 1}, {"hair_color", 24, 7}},
        {{"eye_color", 0, 7}, {"gender", 7, 1}, {"eyebrow_color", 8, 7}, {"mouth_color", 16, 7}, {"beard_color", 24, 7}},
        {{"glass_color", 0, 7}, {"eye_type", 8, 6}, {"mouth_type", 16, 6}, {"eye_y", 24, 5}, {"glass_scale", 29, 3}},
        {{"eyebrow_type", 0, 5}, {"mustache_type", 5, 3}, {"nose_type", 8, 5}, {"beard_type", 13, 3}, {"nose_y", 16, 5}, {"mouth_aspect", 21, 3}, {"mouth_y", 24, 5}, {"eyebrow_aspect", 29, 3}},
        {{"mustache_y", 0, 5}, {"eye_rotate", 5, 3}, {"glass_y", 8, 5}, {"eye_aspect", 13, 3}, {"mole_x", 16, 5}, {"eye_scale", 21, 3}, {"mole_y", 24, 5}},
        {{"glass_type", 0, 5}, {"favorite_color", 8, 4}, {"faceline_type", 12, 4}, {"faceline_color", 16, 4}, {"faceline_wrinkle", 20, 4}, {"faceline_make", 24, 4}, {"eye_x", 28, 4}},
        {{"eyebrow_scale", 0, 4}, {"eyebrow_rotate", 4, 4}, {"eyebrow_x", 8, 4}, {"eyebrow_y", 12, 4}, {"nose_scale", 16, 4}, {"mouth_scale", 20, 4}, {"mustache_scale", 24, 4}, {"mole_scale", 28, 4}},
    };

    /** A StoreData as the console writes it: the words, the nickname (10 UTF-16 units), the create ID; the CRCs are left 0 (the reader does not need them) */
    static byte[] storeData(MiiStore.Mii m) {
        ByteBuffer b = ByteBuffer.allocate(MiiImport.STORE_DATA).order(ByteOrder.LITTLE_ENDIAN);
        for (int w = 0; w < 7; w++) {
            int word = 0;
            for (Object[] f : WORDS[w]) {
                int v = m.get((String) f[0]);
                if (f[0].equals("eyebrow_y")) v -= 3;   // the StoreData keeps it 3 lower than the CharInfo
                word |= (v & ((1 << (int) f[2]) - 1)) << (int) f[1];
            }
            b.putInt(4 * w, word);
        }
        for (int i = 0; i < 10 && i < m.name.length(); i++) b.putShort(0x1C + 2 * i, (short) m.name.charAt(i));
        b.position(0x30); b.put(m.createId);
        return b.array();
    }

    static byte[] database(List<MiiStore.Mii> miis) {
        ByteBuffer b = ByteBuffer.allocate(MiiImport.DATABASE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, MiiImport.MAGIC);
        for (int i = 0; i < miis.size(); i++) { b.position(4 + i * MiiImport.STORE_DATA); b.put(storeData(miis.get(i))); }
        b.put(4 + 100 * MiiImport.STORE_DATA, (byte) 1);   // version
        b.put(4 + 100 * MiiImport.STORE_DATA + 1, (byte) miis.size());
        return b.array();
    }

    static boolean same(MiiStore.Mii a, MiiStore.Mii b) {
        if (!a.name.equals(b.name) || !MiiStore.hex(a.createId).equals(MiiStore.hex(b.createId))) return false;
        for (MiiStore.Field f : MiiStore.FIELDS) if (a.get(f.key) != b.get(f.key)) { System.out.println("  differs: " + f.key + " " + a.get(f.key) + " " + b.get(f.key)); return false; }
        return true;
    }

    public static void main(String[] args) throws Exception {
        Random r = new Random(320);
        List<MiiStore.Mii> mine = new ArrayList<>();
        for (int i = 0; i < 6; i++) mine.add(MiiStore.fromDefault(i, "Default " + (i + 1)));
        for (int i = 0; i < 20; i++) mine.add(MiiStore.random("Random " + i, r));   // every field over its range, so each bit position is used
        int bits = 0;
        for (Object[][] w : WORDS) bits += w.length;
        check(bits == MiiStore.FIELDS.size(), "every field of the editor has its place in a StoreData (" + bits + ")");

        // the database
        byte[] db = database(mine);
        MiiImport.Found f = MiiImport.parse(db);
        check(f.miis.size() == mine.size() && f.kind.equals("a Mii database"), "a database: every Mii in it");
        boolean all = true;
        for (int i = 0; i < mine.size() && i < f.miis.size(); i++) all &= same(mine.get(i), f.miis.get(i));
        check(all, "a database: every field, the name and the create ID as they were");
        MiiStore.Mii eb = MiiStore.fromDefault(0, "Brows");
        eb.set("eyebrow_y", 18);
        check(MiiImport.parse(storeData(eb)).miis.get(0).get("eyebrow_y") == 18, "eyebrow position: 3 higher than the StoreData's");

        // through a stream, and in a zip of an emulator's user data (another MiiDatabase.dat first, the one in the mii service's save wins)
        check(MiiImport.read(new ByteArrayInputStream(db)).miis.size() == mine.size(), "read: the database file itself");
        ByteArrayOutputStream zo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zo)) {
            z.putNextEntry(new ZipEntry("user/README.txt")); z.write("x".getBytes()); z.closeEntry();
            z.putNextEntry(new ZipEntry("user/backup/MiiDatabase.dat")); z.write(database(mine.subList(0, 2))); z.closeEntry();
            z.putNextEntry(new ZipEntry("user/nand/system/save/8000000000000030/MiiDatabase.dat")); z.write(db); z.closeEntry();
        }
        MiiImport.Found zf = MiiImport.read(new ByteArrayInputStream(zo.toByteArray()));
        check(zf.miis.size() == mine.size(), "a zip: the database in 8000000000000030, not the other one");
        ByteArrayOutputStream none = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(none)) { z.putNextEntry(new ZipEntry("saves/x.bin")); z.write(new byte[10]); z.closeEntry(); }
        check(MiiImport.read(new ByteArrayInputStream(none.toByteArray())).miis.isEmpty(), "a zip without a database: no Mii");

        // one StoreData, one CharInfo
        check(same(mine.get(7), MiiImport.parse(storeData(mine.get(7))).miis.get(0)), "one StoreData");
        check(same(mine.get(8), MiiImport.parse(MiiStore.charInfo(mine.get(8))).miis.get(0)), "one CharInfo (a .charinfo file)");

        // empty slots and other files
        List<MiiStore.Mii> holes = new ArrayList<>(mine.subList(0, 3));
        byte[] hd = database(holes);
        System.arraycopy(new byte[MiiImport.STORE_DATA], 0, hd, 4 + MiiImport.STORE_DATA, MiiImport.STORE_DATA);   // the second slot empty
        check(MiiImport.parse(hd).miis.size() == 2, "an empty slot (create ID zero) is no Mii");
        byte[] bad = db.clone(); bad[0] ^= 1;
        check(MiiImport.parse(bad).miis.isEmpty(), "a wrong magic: no Mii");
        check(MiiImport.parse(new byte[100]).miis.isEmpty(), "another file: no Mii");

        // what an import adds: new create IDs only, as many as fit under 100
        List<MiiStore.Mii> have = new ArrayList<>(mine.subList(0, 5));
        check(MiiImport.newOnes(have, mine).size() == mine.size() - 5, "Miis already there are skipped");
        List<MiiStore.Mii> full = new ArrayList<>();
        for (int i = 0; i < 95; i++) full.add(MiiStore.random("F" + i, r));
        check(MiiImport.newOnes(full, mine).size() == 5, "at most 100 Miis in all");

        if (fails == 0) System.out.println("PASS");
        System.exit(fails == 0 ? 0 : 1);
    }
}
