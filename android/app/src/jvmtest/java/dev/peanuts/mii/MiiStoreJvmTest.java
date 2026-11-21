// PEANUTS: the launcher's own Miis (Settings › Miis)
package dev.peanuts.mii;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** MiiStore on the JVM (dm/tools/tests/test_android_mii.py): ranges, the CharInfo layout, the database file the host reads, the round trip */
public final class MiiStoreJvmTest {
    static int fails;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) throws Exception {
        check(MiiStore.FIELDS.size() == 46 && MiiStore.DEFAULTS[0].length == 46, "46 fields, each default has them all");
        for (int i = 0; i < 6; i++) {
            byte[] c = MiiStore.charInfo(MiiStore.fromDefault(i, "Test"));
            check(c.length == 0x58, "CharInfo is 0x58 bytes");
            check((c[6] & 0xf0) == 0x40 && (c[8] & 0xc0) == 0x80, "create ID is a UUID v4");
            check(c[16] == 'T' && c[17] == 0 && c[24] == 0, "nickname UTF-16, NUL after it");
            check(c[40] == (i >= 3 ? 1 : 0), "defaults 4-6 are women");
            check(c[65] == 10, "eyebrow_y as a CharInfo keeps it (10)");
        }
        MiiStore.Mii m = MiiStore.fromDefault(0, "Over");
        m.set("hair_type", 500); m.set("eyebrow_y", 0); m.set("favorite_color", -3);
        check(m.get("hair_type") == 131 && m.get("eyebrow_y") == 3 && m.get("favorite_color") == 0, "values clamp to the console's ranges");
        check(MiiStore.cleanName("  ").equals("Mii") && MiiStore.cleanName("ABCDEFGHIJKL").length() == 10, "names: never empty, at most 10");
        check(MiiStore.cleanName("123456789\uD83D\uDE00").equals("123456789"), "names: no half a surrogate pair at the cut");
        check(MiiStore.copyName("Abcdefghij").equals("Abcdefgh 2") && MiiStore.copyName("Ana").equals("Ana 2"), "a duplicate's name differs and fits");
        Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            byte[] c = MiiStore.charInfo(MiiStore.random("R", r));
            for (MiiStore.Field fd : MiiStore.FIELDS) { int v = c[fd.offset] & 0xff; check(v >= fd.min && v <= fd.max, "random " + fd.key + " in range: " + v); }
        }
        File dir = Files.createTempDirectory("mii").toFile();
        List<MiiStore.Mii> list = new ArrayList<>();
        list.add(MiiStore.fromDefault(3, "Ana")); list.add(MiiStore.random("Bo", r));
        list.get(0).set("hair_type", 77);
        MiiStore.save(dir, list);
        byte[] db = Files.readAllBytes(MiiStore.database(dir).toPath());
        check(new String(db, 0, 6, "US-ASCII").equals("PNMII1") && db[6] == 0 && db[7] == 0, "database magic");
        check(ByteBuffer.wrap(db, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == 2 && db.length == 12 + 2 * 0x58, "database count and size");
        check((db[12 + 49] & 0xff) == 77, "the first record's hair style");
        List<MiiStore.Mii> back = MiiStore.load(dir);
        check(back.size() == 2 && back.get(0).name.equals("Ana") && back.get(0).get("hair_type") == 77, "round trip");
        check(java.util.Arrays.equals(back.get(1).createId, list.get(1).createId), "create IDs survive");
        File tsv = new File(MiiStore.database(dir).getParentFile(), "miis.tsv"); // PEANUTS (review fix)
        String ok1 = Files.readAllLines(tsv.toPath()).get(0), ok2 = Files.readAllLines(tsv.toPath()).get(1);
        String bad = "zz" + ok1.substring(2, 32) + "\tBad\thair_type=1";
        Files.write(tsv.toPath(), (ok1 + "\n" + bad + "\n" + ok2 + "\n").getBytes("UTF-8"));
        check(MiiStore.load(dir).size() == 2, "one bad line in the middle still loads the others");
        check(MiiStore.cleanName("A\rB").equals("A B"), "a CR in a name is cleaned");
        System.out.println(fails == 0 ? "PASS" : fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }
}
