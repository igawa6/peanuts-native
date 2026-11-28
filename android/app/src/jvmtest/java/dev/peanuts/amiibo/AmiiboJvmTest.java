package dev.peanuts.amiibo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The amiibo store without Android (peanuts/tests/test_amiibo.py): the app's table (AmiiboAPI's, from the repository), what a dump tells without keys,
 * imports of files and ZIP packs, the player's amiibo with the games' data the host writes, and the description the host's stand reads.
 * The dumps are made here, with made-up IDs and UIDs: no amiibo data of any kind is in the test.
 * args: the table (res/raw/amiibo_db.tsv), a work folder.
 */
public final class AmiiboJvmTest {
    private static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    /** A dump of 540 bytes (an NTAG215's pages) with this UID (7 bytes) and ID (8 bytes); the rest made-up */
    static byte[] dump(byte[] uid, String id, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) b[i] = (byte) (i * 31 + 7);
        b[0] = uid[0]; b[1] = uid[1]; b[2] = uid[2]; b[3] = (byte) (0x88 ^ uid[0] ^ uid[1] ^ uid[2]);
        b[4] = uid[3]; b[5] = uid[4]; b[6] = uid[5]; b[7] = uid[6]; b[8] = (byte) (uid[3] ^ uid[4] ^ uid[5] ^ uid[6]);
        for (int i = 0; i < 8; i++) b[0x54 + i] = (byte) Integer.parseInt(id.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] args) throws Exception {
        AmiiboDb db;
        try (Reader r = new InputStreamReader(new FileInputStream(args[0]), StandardCharsets.UTF_8)) { db = AmiiboDb.parse(r); }
        File work = new File(args[1]);

        // ---- the table ----
        check(db.size() > 900, "the table has every amiibo: " + db.size());
        check(db.source().contains("AmiiboAPI") && db.source().contains("MIT"), "where it comes from");
        String smo = "0100000000010000", ssbu = "01006A800016E000", wedding = "0000000003710102";
        AmiiboDb.Amiibo a = db.get(wedding);
        check(a.known && a.name.equals("Mario - Wedding"), "a name as AmiiboAPI writes it");
        check(a.title().equals("Mario (Wedding)") && a.nickname().equals("Mario"), "shown without \" - \", the nickname the character's");
        check("Figure".equals(db.type(a)), "type");
        check(db.series(a) != null && db.gameSeries(a) != null, "series and game series");
        check(db.usedBy(smo) && !db.usedBy("0100F43008C44000"), "a game with amiibo, a game without");
        List<AmiiboDb.Amiibo> fit = db.fitting(smo);
        check(fit.size() > 20 && fit.stream().anyMatch(x -> x.id.equals(wedding)), "the amiibo that fit Super Mario Odyssey");
        check(db.usesIn(smo, wedding).contains("Gain temporary invincibility"), "what it does there");
        check(!db.keepsData(smo, wedding) && db.keepsData(ssbu, wedding), "a game that keeps data on it, one that does not");
        check(db.titles(wedding).contains(ssbu), "the games that use it");
        check("Super Mario Odyssey".equals(db.gameName(smo)), "a game's name");
        AmiiboDb.Amiibo unknown = db.get("ff00ff00ff00ff02");
        check(!unknown.known && unknown.name.startsWith("amiibo "), "an ID the table does not know gets a name from the ID");
        check(AmiiboDb.nick("Princess Peach - Showtime").equals("Princess P"), "a nickname is at most 10 characters");
        check(AmiiboDb.display("Zelda").equals("Zelda"), "a name without a variant as it is");
        check(AmiiboDb.id("0x00000000037101 02".replace(" ", "")) != null && AmiiboDb.id("xyz") == null, "IDs read");

        // ---- a dump ----
        byte[] uid1 = {0x04, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66}, uid2 = {0x04, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
        String madeUp = "0a0b0c0d0e0f1002";
        AmiiboBin.Read rd = AmiiboBin.read(dump(uid1, madeUp, 540));
        check(rd != null && rd.id.equals(madeUp) && rd.uid.equals("04112233445566"), "ID and UID from a dump, without keys");
        check(AmiiboBin.read(dump(uid1, madeUp, 532)) != null && AmiiboBin.read(dump(uid1, madeUp, 572)) != null, "the other dump sizes");
        check(AmiiboBin.read(dump(uid1, madeUp, 500)) == null, "another size is no amiibo");
        check(AmiiboBin.read(dump(new byte[] {0x05, 1, 2, 3, 4, 5, 6}, madeUp, 540)) == null, "not an NXP tag");
        check(AmiiboBin.read(dump(uid1, "0a0b0c0d0e0f1004", 540)) == null, "an ID that does not end in 02 or 03");

        // ---- imports ----
        File files = new File(work, "files");
        AmiiboImport.Result one = new AmiiboImport.Result();
        AmiiboImport.bytes(files, "made-up.bin", dump(uid1, madeUp, 540), one);
        check(one.added == 1 && one.keys.size() == 1, "a file imported");
        ByteArrayOutputStream zb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zb)) {
            z.putNextEntry(new ZipEntry("pack/one.bin")); z.write(dump(uid1, madeUp, 540)); z.closeEntry();          // already there
            z.putNextEntry(new ZipEntry("pack/two.bin")); z.write(dump(uid2, wedding, 540)); z.closeEntry();         // new
            z.putNextEntry(new ZipEntry("pack/broken.bin")); z.write(new byte[12]); z.closeEntry();                  // no amiibo
            z.putNextEntry(new ZipEntry("pack/readme.txt")); z.write("x".getBytes(StandardCharsets.UTF_8)); z.closeEntry();   // not counted
            z.putNextEntry(new ZipEntry("__MACOSX/pack/._two.bin")); z.write(new byte[540]); z.closeEntry();         // not counted
        }
        AmiiboImport.Result pack = new AmiiboImport.Result();
        AmiiboImport.zip(files, new ByteArrayInputStream(zb.toByteArray()), pack);
        check(pack.added == 1 && pack.known == 1 && pack.skipped == 1, "a pack: one new, one already there, one no amiibo: " + pack.words());
        check(pack.words().equals("1 amiibo added. 1 was already there. 1 file is no amiibo."), "its line: " + pack.words());
        check(new AmiiboImport.Result().words().equals("No amiibo found there."), "nothing found");
        // a folder by its path (the folder registry's): its subfolders, a pack in it, other files left alone
        File tree = new File(work, "tree"), deep = new File(tree, "a/b");
        check(deep.mkdirs(), "a folder tree");
        byte[] uid3 = {0x04, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36};
        Files.write(new File(deep, "three.bin").toPath(), dump(uid3, madeUp, 540));
        Files.write(new File(tree, "again.bin").toPath(), dump(uid1, madeUp, 540));
        Files.write(new File(tree, "pack.zip").toPath(), zb.toByteArray());
        Files.write(new File(tree, "notes.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File treeFiles = new File(work, "files-tree");
        AmiiboImport.Result walked = AmiiboImport.folder(treeFiles, tree);
        check(walked.added == 3 && walked.known == 1 && walked.skipped == 1, "a folder: three new, one twice (its pack has it too), one no amiibo: " + walked.words());
        check(AmiiboImport.folder(treeFiles, new File(work, "missing")).words().equals("No amiibo found there."), "a folder that is not there");

        // ---- the player's amiibo ----
        List<AmiiboStore.Own> own = AmiiboStore.list(files);
        check(own.size() == 2, "two of the player's own");
        AmiiboStore.Own mario = AmiiboStore.find(files, wedding + "-04010203040506");
        check(mario != null && mario.imported() && mario.file.equals("two.bin"), "found by key, from its file");
        AmiiboStore.Own listed = AmiiboStore.fromList(files, "0000000000340102");
        check(!listed.imported() && listed.uid.startsWith("04") && listed.uid.length() == 14, "one from the list gets a UID of its own");
        check(AmiiboStore.listed(files, "0000000000340102").key.equals(listed.key), "and is found again");
        Set<String> fits = new HashSet<>();
        for (AmiiboDb.Amiibo x : db.fitting(smo)) fits.add(x.id);
        List<AmiiboStore.Own> forSmo = AmiiboStore.forGame(AmiiboStore.list(files), smo, fits);
        check(forSmo.size() == 2 && forSmo.get(0).key.equals(listed.key), "the two that fit, the newest first while none was placed");
        AmiiboStore.placed(mario, smo, 1000); AmiiboStore.placed(mario, smo, 2000);
        forSmo = AmiiboStore.forGame(AmiiboStore.list(files), smo, fits);
        check(forSmo.get(0).key.equals(mario.key), "the last placed in this game first");
        check(AmiiboStore.placedIn(mario, smo)[0] == 2 && AmiiboStore.placedIn(mario, smo)[1] == 2000, "placed twice, last at 2000");
        AmiiboStore.Own other = AmiiboStore.find(files, madeUp + "-04112233445566");
        AmiiboStore.placed(other, smo, 3000);
        check(AmiiboStore.forGame(AmiiboStore.list(files), smo, fits).get(0).key.equals(other.key), "one placed in the game counts though the table does not list it");
        check(AmiiboStore.forGame(AmiiboStore.list(files), "0100F43008C44000", Collections.emptySet()).isEmpty(), "none for a game without amiibo");

        // the games' data as the host writes it
        byte[] area = new byte[0xd8]; area[0] = 0x11; area[5] = 0x22;
        Files.write(new File(mario.dir, "area-0a0b0c0d.bin").toPath(), area);
        Files.write(new File(mario.dir, "area-0a0b0c0d.title").toPath(), smo.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(mario.dir, "area-00000099.bin").toPath(), new byte[] {1, 2, 3});
        Files.write(new File(mario.dir, "area-00000099.title").toPath(), ssbu.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(mario.dir, "common.txt").toPath(), "writes=4\nwritten=2026-11-20\n".getBytes(StandardCharsets.UTF_8));
        check(AmiiboStore.areas(mario).size() == 2 && AmiiboStore.hasData(mario, smo) && AmiiboStore.hasData(mario, ssbu.toLowerCase()), "two games keep data on it");
        check(AmiiboStore.common(mario)[0].equals("4") && AmiiboStore.common(mario)[1].equals("2026-11-20"), "written four times");

        // ---- the description for the host ----
        byte[] mii = new byte[0x58]; mii[0] = (byte) 0xab; mii[0x57] = 0x01;
        String d = AmiiboTag.describe(mario, a, mii, smo, 1764000000000L);
        check(d.contains("key=" + mario.key + "\n") && d.contains("id=" + wedding + "\n") && d.contains("uid=04010203040506\n"), "key, ID, UID");
        check(d.contains("name=Mario\n") && d.contains("mii=ab") && d.contains("01\n") && d.contains("writes=4\n") && d.contains("written=2026-11-20\n"), "nickname, Mii, writes");
        check(d.contains("title=" + smo + "\n") && d.contains("dir=" + mario.dir.getAbsolutePath() + "\n"), "the game and where to write");
        check(d.contains("area=0a0b0c0d:1100000000220000") && d.contains("area=00000099:010203\n"), "the areas in hex");
        check(AmiiboTag.fit("ééé", 4).equals("éé"), "a nickname cut at a character's edge");

        // reset one game's data, the other stays
        check(AmiiboStore.reset(mario, smo) == 1 && !AmiiboStore.hasData(mario, smo) && AmiiboStore.hasData(mario, ssbu), "one game's data reset");
        AmiiboStore.remove(other);
        check(AmiiboStore.list(files).size() == 2, "one removed");
        System.out.println("ok");
        System.err.println(checks + " checks");
    }
}
