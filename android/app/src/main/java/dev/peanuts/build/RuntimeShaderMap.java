package dev.peanuts.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads the shipped profile without starting the packer's native engine in the launcher process. */
public final class RuntimeShaderMap {
    private RuntimeShaderMap() {}

    static boolean compilesAtRunTime(InputStream tools, String titleId) throws IOException {
        return !runtimeKeys(tools, titleId).isEmpty();
    }

    private static Set<Long> runtimeKeys(InputStream tools, String titleId) throws IOException {
        Set<Long> keys = new HashSet<>();
        String name = "dm/games/" + titleId + "/shader_map.tsv";
        try (ZipInputStream zip = new ZipInputStream(tools)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!name.equals(e.getName())) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("#")) continue;
                    String[] cols = line.split("\t");
                    if (cols.length >= 5 && cols[4].startsWith("/glslc_capture/")) {
                        try { keys.add(Long.parseUnsignedLong(cols[3], 16)); }
                        catch (NumberFormatException ex) { throw new IOException("The app's list for this game is damaged. Install the app again, then tap Retry.\nInvalid run-time shader key in " + name, ex); }
                    }
                }
                return keys;
            }
        }
        throw new IOException("The app's list for this game is missing. Install the app again, then tap Retry.\n" + titleId);
    }

    /**
     * The profile's capture_keys row (game.tsv): the buttons a capture boot presses, in the host's DM_KEYS form, "" when the profile has none.
     * A capture boot starts on an empty save, and a game that then waits for a button before it compiles its shaders (Crash Team Racing Nitro-Fueled:
     * its Language Select screen) would never compile them (issue #111).
     */
    static String captureKeys(InputStream tools, String titleId) throws IOException {
        String name = "dm/games/" + titleId + "/game.tsv";
        try (ZipInputStream zip = new ZipInputStream(tools)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!name.equals(e.getName())) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String[] cols = line.split("\t");
                    if (cols.length < 2 || !cols[0].equals("capture_keys")) continue;
                    String keys = cols[1].trim();
                    // one environment line of button steps only: no touch of the env file's own syntax
                    if (!keys.matches("[A-Za-z0-9:+~*/.,-]{1,256}")) throw new IOException("The app's list for this game is damaged. Install the app again, then tap Retry.\nInvalid capture_keys in " + name);
                    return keys;
                }
                return "";
            }
        }
        return "";
    }

    /** Check the pack itself: an adopted job may have lost its shaders_missing count (issue #81). Reads only the library's entry table. */
    static int missing(InputStream tools, String titleId, File library) throws IOException {
        return missingKeys(runtimeKeys(tools, titleId), titleId, library);
    }

    /** A first build may lack only boot-generated outputs; every file-based key must still be present. Null preserves normal failure handling. */
    public static java.util.List<String> missingRuntime(File profile, File found) throws IOException {
        Set<String> expected = new HashSet<>(), runtime = new HashSet<>(), have = new HashSet<>();
        for (File file : new File[] {profile, found}) {
            try (BufferedReader r = java.nio.file.Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                for (String line; (line = r.readLine()) != null; ) {
                    String[] cols = line.split("\t");
                    if (cols.length < 5 || !cols[0].endsWith(".bin")) continue;
                    if (file == profile) {
                        expected.add(cols[3]);
                        if (cols[4].startsWith("/glslc_capture/")) runtime.add(cols[3]);
                    } else have.add(cols[3]);
                }
            }
        }
        if (!expected.containsAll(have)) return null;   // a changed or different build
        expected.removeAll(have);
        if (expected.isEmpty() || !runtime.containsAll(expected)) return null;
        return new java.util.ArrayList<>(new java.util.TreeSet<>(expected));
    }

    /** The shipped shader map describes the package build, not every registered older game version. */
    static boolean matchesBuild(InputStream tools, String titleId, java.util.List<String> actual) throws IOException {
        String name = "dm/games/" + titleId + "/game.tsv", primary = null;
        java.util.Map<String, String[]> builds = new java.util.HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(tools)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!name.equals(e.getName())) continue;
                BufferedReader r = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String[] cols = line.split("\t");
                    if (cols.length > 1 && cols[0].equals("package")) primary = cols[1];
                    if (cols.length > 3 && cols[0].equals("build")) builds.put(cols[1], cols);
                }
                break;
            }
        }
        String[] expected = builds.get(primary);
        if (expected == null) return false;
        boolean checked = false;
        for (int i = 3; i < expected.length; i++) {
            String[] pair = expected[i].split("=", 2);
            if (pair.length != 2 || pair[1].length() < 32) continue;
            boolean found = false;
            for (String value : actual) {
                String[] a = value.split("=", 2);
                if (a.length == 2 && a[0].equals(pair[0]) && a[1].length() >= 32 && a[1].substring(0, 32).equalsIgnoreCase(pair[1].substring(0, 32))) found = true;
            }
            if (!found) return false;
            checked = true;
        }
        return checked;
    }

    /** Required file-based shaders: optional keys and deliberately selected libraries are not incomplete packs. */
    static int missingStatic(InputStream tools, String titleId, File library) throws IOException {
        Set<Long> keys = new HashSet<>(), optional = new HashSet<>();
        String root = "dm/games/" + titleId + "/";
        boolean found = false, selected = false;
        try (ZipInputStream zip = new ZipInputStream(tools)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.equals(root + "shader_seen.tsv")) selected = true;
                boolean map = name.equals(root + "shader_map.tsv"), opt = name.equals(root + "shader_optional.tsv");
                if (!map && !opt) continue;
                if (map) found = true;
                BufferedReader r = new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("package\t")) continue;
                    String[] cols = line.split("\t");
                    try {
                        if (map && cols.length >= 5 && cols[0].endsWith(".bin") && !cols[4].startsWith("/glslc_capture/")) keys.add(Long.parseUnsignedLong(cols[3], 16));
                        else if (opt) optional.add(Long.parseUnsignedLong(cols[0], 16));
                    } catch (NumberFormatException ex) { throw new IOException("Invalid shader key in " + name, ex); }
                }
            }
        }
        if (!found) return 0;   // a generated profile may live only with its pack: no shipped map to compare
        keys.removeAll(optional);
        return selected ? 0 : missingKeys(keys, titleId, library);
    }

    private static int missingKeys(Set<Long> keys, String titleId, File library) throws IOException {
        if (keys.isEmpty()) return 0;
        // dmsh_abi_vk.h: 128-byte header; entry_count/off at 72/76; 64-byte entries, key at 0 and module count at 17.
        try (RandomAccessFile f = new RandomAccessFile(library, "r")) {
            if (f.length() < 128 || Integer.reverseBytes(f.readInt()) != 0x4B56534D || Integer.reverseBytes(f.readInt()) != 1)
                throw new IOException("Cannot read the run-time shader library: " + library.getName());
            byte[] game = new byte[16]; f.seek(40); f.readFully(game);
            if (!titleId.equalsIgnoreCase(new String(game, StandardCharsets.US_ASCII)))
                throw new IOException("The shader library belongs to another game.");
            f.seek(72);
            long count = Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()));
            long offset = Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()));
            if (offset < 128 || offset > f.length() || count > (f.length() - offset) / 64)
                throw new IOException("The shader library's entry table is incomplete.");
            for (long i = 0; i < count && !keys.isEmpty(); i++) {
                f.seek(offset + i * 64);
                long key = Long.reverseBytes(f.readLong());
                f.seek(offset + i * 64 + 17);
                if (f.readUnsignedByte() != 0) keys.remove(key);
            }
        }
        return keys.size();
    }
}
