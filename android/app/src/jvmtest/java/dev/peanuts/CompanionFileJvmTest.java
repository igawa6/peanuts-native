// PEANUTS: CompanionFile on the JVM (peanuts/tests/test_companion_file.py): what a picked .dsmod.zip is, and whether it may go in
package dev.peanuts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fixture companion files made here, in a temporary folder (no argument; prints "ok" or throws): a good one, one for another game, a damaged one (a bad
 * CRC, a module library that does not match the manifest's sha256), one that needs a newer app, an older one than the installed version, and files that
 * are not companions. Every check is CompanionFile.read then CompanionFile.decide, the two steps Installer.installFile runs before its extraction.
 */
public final class CompanionFileJvmTest {
    private static final String TITLE = "010015100B514000", OTHER = "0100F2C0115B6000";
    private static final int RUNTIME = 25;
    private static final byte[] LIB = "not really a library, but bytes with a checksum".getBytes(StandardCharsets.UTF_8);

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static String sha(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String pkgJson(String title, String version, Integer minRuntime) {
        return "{\n \"format\": 1,\n \"type\": \"dual-screen-mod\",\n \"title_id\": \"" + title + "\",\n \"name\": \"Super Mario Wonder companion\",\n \"version\": \"" + version + "\""
                + (minRuntime == null ? "" : ",\n \"min_runtime\": " + minRuntime) + ",\n \"requires_module\": true\n}\n";
    }

    private static String manifest(String title, String libSha) {
        return "{\"title_id\": \"" + title + "\", \"module\": {\"abi\": 1, \"build_ids\": [\"D4B150B29A931CD3\"], \"libraries\": {\"android-arm64-v8a\": {\"path\": \"modules/android-arm64-v8a/"
                + title + ".so\", \"sha256\": \"" + libSha + "\"}}}, \"pages\": []}";
    }

    /** A zip of the given entries, stored (not deflated) so a flipped byte is a CRC error at that entry */
    private static File zip(File dir, String name, Map<String, byte[]> entries) throws IOException {
        File f = new File(dir, name);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                ze.setMethod(ZipEntry.STORED);
                ze.setSize(e.getValue().length);
                CRC32 crc = new CRC32();
                crc.update(e.getValue());
                ze.setCrc(crc.getValue());
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return f;
    }

    private static File companion(File dir, String name, String pkgTitle, String manTitle, String version, Integer minRuntime, String libSha) throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("package.json", pkgJson(pkgTitle, version, minRuntime).getBytes(StandardCharsets.UTF_8));
        m.put("dualscreen/manifest.json", manifest(manTitle, libSha).getBytes(StandardCharsets.UTF_8));
        m.put("dualscreen/pages/main.json", "{}".getBytes(StandardCharsets.UTF_8));
        m.put("dualscreen/modules/android-arm64-v8a/" + manTitle + ".so", LIB);
        return zip(dir, name, m);
    }

    private static String refusal(File f, String fileName, String want, String have, boolean allowOlder) {
        try {
            CompanionFile.decide(CompanionFile.read(f, fileName), want, null, have, RUNTIME, allowOlder);
            return "none";
        } catch (CompanionFile.Refused r) {
            return r.code;
        } catch (IOException e) {
            return "io " + e;
        }
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("companion-file").toFile();
        try {
            String good = sha(LIB);
            String goodName = TITLE + "-SuperMarioWonderDS-1.0.1.dsmod.zip";

            // good: game, version, name and package from the file; a first install, an update, the same version, all against this app's runtime
            File ok = companion(dir, goodName, TITLE, TITLE, "1.0.1", 3, good);
            CompanionFile.Info info = CompanionFile.read(ok, goodName);
            check(info.titleId.equals(TITLE) && info.version.equals("1.0.1") && info.name.equals("Super Mario Wonder companion") && info.minRuntime == 3, "good: info");
            check("SuperMarioWonderDS".equals(info.filePackage), "good: package from the catalog's file name");
            check(CompanionFile.packageName(info, null).equals("SuperMarioWonderDS"), "good: the file's package name");
            check(CompanionFile.packageName(info, "DualScreen-" + TITLE).equals("DualScreen-" + TITLE), "good: the game's package that is there already");
            check(CompanionFile.decide(info, null, null, null, RUNTIME, false) == CompanionFile.Kind.INSTALL, "good: first install");
            check(CompanionFile.decide(info, TITLE.toLowerCase(), null, "1.0.0", RUNTIME, false) == CompanionFile.Kind.UPDATE, "good: update for its game");
            check(CompanionFile.decide(info, null, null, "1.0.1", RUNTIME, false) == CompanionFile.Kind.SAME, "good: the installed version");
            check(CompanionFile.decide(info, null, null, "?", RUNTIME, false) == CompanionFile.Kind.UPDATE, "good: over one without installed.json");

            // any other file name: no package name from it, the catalog's default
            File plain = companion(dir, "companion (1).zip", TITLE, TITLE, "1.0.1", null, good);
            CompanionFile.Info pi = CompanionFile.read(plain, "companion (1).zip");
            check(pi.filePackage == null && CompanionFile.packageName(pi, null).equals("DualScreen-" + TITLE), "plain name: default package");
            check(CompanionFile.read(plain, null).filePackage == null, "no name at all");
            // a catalog name for another game does not name this one's package
            check(CompanionFile.read(plain, OTHER + "-Other-2.0.0.dsmod.zip").filePackage == null, "another game's file name");

            // wrong title: for another game than the one asked for
            check(refusal(ok, goodName, OTHER, null, false).equals(CompanionFile.Refused.WRONG_TITLE), "wrong title");
            try { CompanionFile.decide(info, OTHER, "Persona 5 Royal", null, RUNTIME, false); throw new AssertionError("wrong title: not refused"); }
            catch (CompanionFile.Refused r) { check(r.getMessage().contains("not Persona 5 Royal") && r.getMessage().contains(TITLE), "wrong title: names both: " + r.getMessage()); }
            // package.json and manifest naming different games: damaged
            check(refusal(companion(dir, "mixed.dsmod.zip", OTHER, TITLE, "1.0.1", null, good), null, null, null, false).equals(CompanionFile.Refused.DAMAGED), "two games in one file");

            // newer app needed
            File newer = companion(dir, "newer.dsmod.zip", TITLE, TITLE, "2.0.0", RUNTIME + 1, good);
            check(refusal(newer, null, null, null, false).equals(CompanionFile.Refused.NEEDS_NEWER_APP), "needs a newer app");
            try { CompanionFile.decide(CompanionFile.read(newer, null), null, null, null, RUNTIME, true); throw new AssertionError("newer app: not refused"); }
            catch (CompanionFile.Refused r) { check(r.getMessage().contains("companion runtime " + (RUNTIME + 1)) && r.getMessage().contains("Update the app"), "newer app: says so: " + r.getMessage()); }

            // older than installed: asked first, then allowed
            File older = companion(dir, TITLE + "-SuperMarioWonderDS-1.0.0.dsmod.zip", TITLE, TITLE, "1.0.0", null, good);
            check(refusal(older, null, null, "1.0.1", false).equals(CompanionFile.Refused.OLDER), "older: asked first");
            check(CompanionFile.decide(CompanionFile.read(older, null), null, null, "1.0.1", RUNTIME, true) == CompanionFile.Kind.OLDER, "older: after Yes");
            check(CompanionFile.decide(CompanionFile.read(older, null), null, null, "1.0.0.1", RUNTIME, true) == CompanionFile.Kind.OLDER, "older: a fourth number");

            // damaged: a flipped byte in the library (a CRC error), a library that does not match the manifest, a missing library, a cut-off file
            File crc = companion(dir, "crc.dsmod.zip", TITLE, TITLE, "1.0.1", null, good);
            byte[] all = Files.readAllBytes(crc.toPath());
            int at = indexOf(all, LIB);
            try (RandomAccessFile r = new RandomAccessFile(crc, "rw")) { r.seek(at + 4); r.write(all[at + 4] ^ 0x5a); }
            check(refusal(crc, null, null, null, false).equals(CompanionFile.Refused.DAMAGED), "damaged: bad CRC");
            check(refusal(companion(dir, "hash.dsmod.zip", TITLE, TITLE, "1.0.1", null, sha("other".getBytes(StandardCharsets.UTF_8))), null, null, null, false).equals(CompanionFile.Refused.DAMAGED), "damaged: library checksum");
            Map<String, byte[]> noLib = new LinkedHashMap<>();
            noLib.put("package.json", pkgJson(TITLE, "1.0.1", null).getBytes(StandardCharsets.UTF_8));
            noLib.put("dualscreen/manifest.json", manifest(TITLE, good).getBytes(StandardCharsets.UTF_8));
            check(refusal(zip(dir, "nolib.dsmod.zip", noLib), null, null, null, false).equals(CompanionFile.Refused.DAMAGED), "damaged: library missing");
            File cut = new File(dir, "cut.dsmod.zip");
            Files.write(cut.toPath(), java.util.Arrays.copyOf(all, at + 10));
            String cutCode = refusal(cut, null, null, null, false);
            check(cutCode.equals(CompanionFile.Refused.DAMAGED), "damaged: cut off: " + cutCode);
            Map<String, byte[]> noVersion = new LinkedHashMap<>();
            noVersion.put("package.json", ("{\"title_id\": \"" + TITLE + "\"}").getBytes(StandardCharsets.UTF_8));
            noVersion.put("dualscreen/manifest.json", ("{\"title_id\": \"" + TITLE + "\"}").getBytes(StandardCharsets.UTF_8));
            File nv = zip(dir, "nover.zip", noVersion);
            check(refusal(nv, null, null, null, false).equals(CompanionFile.Refused.DAMAGED), "no version");
            check(CompanionFile.read(nv, TITLE + "-Pkg-1.2.0.dsmod.zip").version.equals("1.2.0"), "version from the file name when package.json has none");
            Map<String, byte[]> badVersion = new LinkedHashMap<>(noVersion);
            badVersion.put("package.json", ("{\"title_id\": \"" + TITLE + "\", \"version\": \"../../x\"}").getBytes(StandardCharsets.UTF_8));
            check(refusal(zip(dir, "badver.zip", badVersion), null, null, null, false).equals(CompanionFile.Refused.DAMAGED), "a version that is a path");

            // not a companion: a text file, an empty file, a zip of something else, a zip that names no game
            File text = new File(dir, "notes.dsmod.zip");
            Files.write(text.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            check(refusal(text, null, null, null, false).equals(CompanionFile.Refused.NOT_COMPANION), "not a zip");
            File empty = new File(dir, "empty.zip");
            Files.write(empty.toPath(), new byte[0]);
            check(refusal(empty, null, null, null, false).equals(CompanionFile.Refused.NOT_COMPANION), "empty");
            Map<String, byte[]> other = new LinkedHashMap<>();
            other.put("readme.txt", "a mod".getBytes(StandardCharsets.UTF_8));
            check(refusal(zip(dir, "mod.zip", other), null, null, null, false).equals(CompanionFile.Refused.NOT_COMPANION), "a zip of something else");
            Map<String, byte[]> noGame = new LinkedHashMap<>();
            noGame.put("package.json", "{\"version\": \"1.0.0\"}".getBytes(StandardCharsets.UTF_8));
            noGame.put("dualscreen/manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
            check(refusal(zip(dir, "nogame.zip", noGame), null, null, null, false).equals(CompanionFile.Refused.NOT_COMPANION), "names no game");

            // the catalog and a file read names and versions with one rule
            check(CompanionFile.ASSET.matcher(TITLE + ".dsmod.zip").matches() && !CompanionFile.ASSET.matcher("x.dsmod.zip").matches(), "asset names");
            check(CompanionFile.compareVersions("1.0.10", "1.0.9") > 0 && CompanionFile.compareVersions("1.0", "1.0.0") == 0 && CompanionFile.compareVersions("0.9", "1.0") < 0, "versions");

            // the copy of a picked file stops at its limit
            File big = new File(dir, "big.bin");
            try { CompanionFile.copy(new java.io.ByteArrayInputStream(new byte[2048]), big, 1024); throw new AssertionError("copy: no limit"); }
            catch (IOException e) { check(e.getMessage().contains("larger"), "copy: limit: " + e.getMessage()); }
            check(CompanionFile.copy(new java.io.ByteArrayInputStream(new byte[100]), big, 1024) == 100 && big.length() == 100, "copy");
            System.out.println("ok");
        } finally {
            File[] kids = dir.listFiles();
            if (kids != null) for (File k : kids) k.delete();
            dir.delete();
        }
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        throw new AssertionError("not found");
    }
}
