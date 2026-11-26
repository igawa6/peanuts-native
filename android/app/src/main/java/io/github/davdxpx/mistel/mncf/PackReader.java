package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads packs (section 13): a ZIP file .mncp with a manifest pack.toml at its root. */
public final class PackReader {

    private static final List<String> PACK_KEYS = Values.list("format", "name", "id", "authors", "about",
            "file_version", "signers", "update", "changes", "file");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** The most entries, directories not counted, a pack may have. */
    public static final int MAX_ENTRIES = 4096;

    /** The most bytes a reader reads from one pack: the manifest and the files it lists, 256 MiB. */
    public static final long MAX_TOTAL = 256L * 1024 * 1024;

    private PackReader() {
    }

    /**
     * Returns whether a path can name a file in a pack: not empty, parts separated by "/", no
     * empty part, no "." or "..", and no backslash.
     */
    public static boolean isValidPath(String path) {
        if (path.isEmpty() || path.indexOf('\\') >= 0) {
            return false;
        }
        for (String part : Py.split(path, '/')) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads a pack's bytes and checks its manifest and files.
     *
     * @param zip the bytes of the .mncp file
     * @param trust the keys the reader trusts, or null for none
     * @throws MncfException when the pack cannot be used at all: zip, manifest, format,
     *     schema:&lt;key&gt; or path:&lt;path&gt;, or size for a pack of more than
     *     {@link #MAX_ENTRIES} entries, an entry of more than {@link Mncf#MAX_FILE} bytes, or
     *     more than {@link #MAX_TOTAL} bytes to read
     */
    public static Pack read(byte[] zip, TrustList trust) throws MncfException {
        return read(zip, trust, MAX_TOTAL);
    }

    /** Reads a pack with another limit for the bytes it reads, so that tests can reach it. */
    @SuppressWarnings("unchecked")
    static Pack read(byte[] zip, TrustList trust, long maxTotal) throws MncfException {
        Zip archive;
        List<String> names = new ArrayList<>();
        try {
            archive = Zip.open(zip);
        } catch (Zip.ZipError e) {
            throw new MncfException("zip", "not a ZIP file (" + e.getMessage() + ")");
        }
        for (Zip.Entry e : archive.entries()) {
            if (!e.name.endsWith("/")) {
                names.add(e.name);
            }
        }
        if (names.size() > MAX_ENTRIES) {
            throw new MncfException("size", "the pack has more than " + MAX_ENTRIES + " entries");
        }
        long[] budget = {0, maxTotal};
        if (new HashSet<>(names).size() != names.size()) {
            throw new MncfException("zip", "two entries have the same name");
        }
        if (!names.contains("pack.toml")) {
            throw new MncfException("manifest", "there is no pack.toml at the root");
        }
        byte[] raw = read(archive, "pack.toml", budget);
        String text;
        try {
            text = Mncf.decode(raw);
        } catch (MncfException e) {
            throw new MncfException("manifest", e.detail());
        }
        Map<String, Object> doc = Mncf.parseToml(text, "manifest");
        if (doc.isEmpty() || !doc.keySet().iterator().next().equals("format") || !"mncf-pack/1".equals(doc.get("format"))) {
            throw new MncfException("format", "`format = \"mncf-pack/1\"` must be the first key of pack.toml");
        }
        List<String> warnings = new ArrayList<>();
        for (String k : doc.keySet()) {
            if (!PACK_KEYS.contains(k)) {
                warnings.add("unknown-key:" + k);
            }
        }
        Object name = doc.get("name");
        if (!(name instanceof String)) {
            throw new MncfException("schema:name", "`name` must be a string");
        }
        String about = null;
        String fileVersion = null;
        for (String key : new String[] {"about", "file_version"}) {
            if (doc.containsKey(key)) {
                if (!(doc.get(key) instanceof String)) {
                    throw new MncfException("schema:" + key, "`" + key + "` must be a string");
                }
                if (key.equals("about")) {
                    about = (String) doc.get(key);
                } else {
                    fileVersion = (String) doc.get(key);
                }
            }
        }
        List<String> authors = new ArrayList<>();
        if (doc.containsKey("authors")) {
            Object a = doc.get("authors");
            boolean good = a instanceof List;
            if (good) {
                for (Object x : (List<Object>) a) {
                    good &= x instanceof String;
                }
            }
            if (!good) {
                throw new MncfException("schema:authors", "`authors` must be an array of strings");
            }
            for (Object x : (List<Object>) a) {
                authors.add((String) x);
            }
        }
        List<byte[]> signers = Updates.readSigners(doc, warnings);
        Updates.Keys keys = Updates.readKeys(doc, warnings, new Updates.Name() {
            @Override
            public boolean ok(Object path) {
                return path instanceof String && isValidPath((String) path) && !path.equals("pack.toml");
            }
        });
        SignatureResult signature = Signatures.check(raw, trust, signers);
        Object entries = doc.get("file");
        boolean good = entries instanceof List && !((List<Object>) entries).isEmpty();
        if (good) {
            for (Object e : (List<Object>) entries) {
                good &= e instanceof Map;
            }
        }
        if (!good) {
            throw new MncfException("schema:file", "the manifest needs at least one [[file]]");
        }
        Set<String> seen = new HashSet<>();
        List<PackFile> files = new ArrayList<>();
        for (Object item : (List<Object>) entries) {
            Map<String, Object> e = (Map<String, Object>) item;
            Object path = e.get("path");
            Object digest = e.get("sha256");
            if (!(path instanceof String)) {
                throw new MncfException("schema:file.path", "`path` must be a string");
            }
            if (!isValidPath((String) path) || path.equals("pack.toml") || seen.contains(path)) {
                throw new MncfException("path:" + path, Py.repr(path) + " is not a usable path in a pack");
            }
            if (!Py.matches(SHA256, digest)) {
                throw new MncfException("schema:file.sha256", path + ": `sha256` must be 64 lower-case hexadecimal digits");
            }
            for (String k : e.keySet()) {
                if (!k.equals("path") && !k.equals("sha256")) {
                    warnings.add("unknown-key:file." + k);
                }
            }
            seen.add((String) path);
            if (!names.contains(path)) {
                files.add(new PackFile((String) path, (String) digest, null, PackFile.MISSING));
                continue;
            }
            byte[] data = read(archive, (String) path, budget);
            if (!hex(Signatures.sha256(data)).equals(digest)) {
                files.add(new PackFile((String) path, (String) digest, null, PackFile.HASH));
            } else {
                files.add(new PackFile((String) path, (String) digest, data, PackFile.OK));
            }
        }
        return new Pack((String) name, signature, files, authors, about, fileVersion, keys, signers, warnings);
    }

    /**
     * The bytes of one entry: only a stored or deflated entry that is not encrypted, and none of
     * more than {@link Mncf#MAX_FILE} bytes, whatever its header claims.
     *
     * @param budget the bytes read from the pack so far, and the most it may read; updated
     */
    private static byte[] read(Zip archive, String name, long[] budget) throws MncfException {
        Zip.Entry e = archive.entry(name);
        if (!Zip.readable(e)) {
            throw new MncfException("zip", name + ": neither stored nor deflated, or encrypted");
        }
        if (Long.compareUnsigned(e.size, Mncf.MAX_FILE) > 0) {
            throw new MncfException("size", name + ": more than " + Mncf.MAX_FILE + " bytes");
        }
        byte[] data;
        try {
            data = archive.read(e, Mncf.MAX_FILE + 1);
        } catch (Zip.ZipError ex) {
            throw new MncfException("zip", name + " cannot be read (" + ex.getMessage() + ")");
        }
        if (data.length > Mncf.MAX_FILE) {
            throw new MncfException("size", name + ": more than " + Mncf.MAX_FILE + " bytes");
        }
        budget[0] += data.length;
        if (budget[0] > budget[1]) {
            throw new MncfException("size", "the pack holds more than " + budget[1] + " bytes");
        }
        return data;
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte x : bytes) {
            b.append(String.format("%02x", x & 0xFF));
        }
        return b.toString();
    }
}
