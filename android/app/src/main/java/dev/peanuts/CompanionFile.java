// PEANUTS: a dual-screen companion picked as a file (Install from file, the companion command's path): what the .dsmod.zip holds and whether it may go in
package dev.peanuts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A companion package from a file on the device instead of the catalog. {@link #read} goes through the whole zip once (every entry's CRC, the module
 * libraries' sha256 against dualscreen/manifest.json) and takes the game, version and runtime from package.json and the manifest; {@link #decide} says
 * what installing it would do against the installed version. Installer.installFile then puts it in with the catalog install's own extraction, checks and
 * swap. No Android class and no org.json here: the JVM test drives it (CompanionFileJvmTest).
 * <p>A file has no published digest, so it is installed as "unverified" (installed.json "verified": false), the word the catalog uses for a package
 * whose checksum it could not check.
 */
public final class CompanionFile {
    /** A catalog asset's name, `<TITLEID>-<Name>-<version>.dsmod.zip` (older ones `<TITLEID>.dsmod.zip`): Catalog's rule, kept here so both read names alike */
    static final Pattern ASSET = Pattern.compile("^([0-9A-Fa-f]{16})(?:-([A-Za-z0-9]+)-([0-9][0-9A-Za-z.\\-]*))?\\.dsmod\\.zip$");
    /** The version names a folder (Installer's version archive): nothing but a plain version string, Catalog's rule */
    static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.\\-]{1,32}");
    private static final Pattern TITLE = Pattern.compile("[0-9A-Fa-f]{16}");
    private static final long MAX_JSON = 64L << 20;   // the biggest manifest today is under 2 MB

    /** A refusal with a short code for scripts (the companion command) and a sentence for people */
    public static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;
        public static final String NOT_COMPANION = "not_companion", DAMAGED = "damaged", WRONG_TITLE = "wrong_title", NEEDS_NEWER_APP = "needs_newer_app", OLDER = "older";
        public final String code;
        Refused(String code, String message) { super(message); this.code = code; }
    }

    /** What a companion file holds */
    public static final class Info {
        public final String titleId, version, name, fileName;
        /** The package name its file name gives (catalog naming), null for any other name */
        public final String filePackage;
        public final int minRuntime;
        Info(String titleId, String version, String name, String fileName, String filePackage, int minRuntime) {
            this.titleId = titleId; this.version = version; this.name = name; this.fileName = fileName; this.filePackage = filePackage; this.minRuntime = minRuntime;
        }
        /** The companion's own name, else "the companion" */
        public String label() { return name.isEmpty() ? "the companion" : name; }
    }

    /** What installing it does: a first install, a newer version, the installed version again (nothing changes), an older version (asked first) */
    public enum Kind { INSTALL, UPDATE, SAME, OLDER }

    private CompanionFile() {}

    /** Reads and checks the whole file (off the main thread). fileName: the picked file's name, for the package name (null when not known) */
    public static Info read(File zip, String fileName) throws IOException {
        String pkgJson = null, manifest = null;
        Map<String, String> hashes = new HashMap<>();
        int entries = 0;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                entries++;
                if (e.isDirectory()) continue;
                String n = e.getName();
                boolean keep = n.equals("package.json") || n.equals("dualscreen/manifest.json");
                ByteArrayOutputStream text = keep ? new ByteArrayOutputStream() : null;
                MessageDigest md = sha256();
                long size = 0;
                for (int k; (k = z.read(buf)) > 0; ) {   // to the entry's end: ZipInputStream checks its CRC there
                    md.update(buf, 0, k);
                    size += k;
                    if (text != null) {
                        if (size > MAX_JSON) throw new Refused(Refused.DAMAGED, damaged(n + " is far too large"));
                        text.write(buf, 0, k);
                    }
                }
                hashes.put(n, hex(md.digest()));
                if (n.equals("package.json")) pkgJson = text.toString("UTF-8");
                else if (n.equals("dualscreen/manifest.json")) manifest = text.toString("UTF-8");
            }
        } catch (Refused r) {
            throw r;
        } catch (IOException | IllegalArgumentException e) {   // a bad CRC, a cut-off file, an entry name that is not UTF-8
            if (entries == 0) throw notCompanion();
            throw new Refused(Refused.DAMAGED, damaged(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        if (pkgJson == null || manifest == null) throw notCompanion();
        String pkgTitle = field(pkgJson, "title_id"), manTitle = field(head(manifest), "title_id");
        if (pkgTitle != null && !TITLE.matcher(pkgTitle).matches()) pkgTitle = null;
        if (manTitle != null && manTitle.startsWith("0x")) manTitle = manTitle.substring(2);
        if (manTitle != null && !TITLE.matcher(manTitle).matches()) manTitle = null;
        if (pkgTitle == null && manTitle == null) throw new Refused(Refused.NOT_COMPANION, "This file names no game, so it is not a dual-screen companion. Pick a .dsmod.zip file.");
        if (pkgTitle != null && manTitle != null && !pkgTitle.equalsIgnoreCase(manTitle)) throw new Refused(Refused.DAMAGED, damaged("its package.json and manifest name different games"));
        String title = (manTitle != null ? manTitle : pkgTitle).toUpperCase(Locale.ROOT);

        String base = fileName == null ? null : new File(fileName).getName();
        Matcher m = base == null ? null : ASSET.matcher(base);
        boolean named = m != null && m.matches() && m.group(1).equalsIgnoreCase(title);
        String version = field(pkgJson, "version");
        if (version == null && named) version = m.group(3);
        if (version == null || !VERSION.matcher(version).matches() || version.contains("..")) throw new Refused(Refused.DAMAGED, damaged("it names no version"));
        String filePackage = named ? m.group(2) : null;

        // The module's library for this device (dm/peanuts/rt/mods/mod_module.cpp checks the same sha256 when it loads): a missing or changed one is damage
        Matcher lib = Pattern.compile("\"android-arm64-v8a\"\\s*:\\s*\\{([^{}]*)\\}").matcher(manifest);
        if (lib.find()) {
            String path = field(lib.group(1), "path"), want = field(lib.group(1), "sha256");
            if (path != null && want != null) {
                String got = hashes.get("dualscreen/" + path);   // the path is the manifest folder's (the host reads it from dualscreen/)
                if (got == null) throw new Refused(Refused.DAMAGED, damaged(path + " is missing"));
                if (!got.equalsIgnoreCase(want)) throw new Refused(Refused.DAMAGED, damaged(path + " does not match its checksum"));
            }
        }
        int min = Math.max(number(pkgJson, "min_runtime"), number(manifest, "min_runtime"));
        String name = field(pkgJson, "name");
        return new Info(title, version, name == null ? "" : name, base, filePackage, min);
    }

    /**
     * What installing info does against the installed version have (null: none, "?": one without installed.json). Refuses a companion for another game
     * than wantTitle (null: any game; wantName names it in the sentence), one that needs a newer runtime than this app's, and an older version unless
     * allowOlder (code {@link Refused#OLDER}: ask, then try again with allowOlder).
     */
    public static Kind decide(Info info, String wantTitle, String wantName, String have, int runtime, boolean allowOlder) throws Refused {
        if (wantTitle != null && !wantTitle.equalsIgnoreCase(info.titleId))
            throw new Refused(Refused.WRONG_TITLE, "This companion is for another game (title ID " + info.titleId + "), not " + (wantName == null || wantName.isEmpty() ? wantTitle : wantName) + ".");
        if (info.minRuntime > runtime)
            throw new Refused(Refused.NEEDS_NEWER_APP, "This companion needs a newer version of the app (companion runtime " + info.minRuntime + ", this app has " + runtime + "). Update the app first.");
        if (have == null) return Kind.INSTALL;
        if (have.equals("?")) return Kind.UPDATE;
        int c = compareVersions(info.version, have);
        if (c >= 0) return info.version.equals(have) ? Kind.SAME : Kind.UPDATE;   // 1.0 against 1.0.0 (the same number written another way) goes in as the file has it
        if (!allowOlder) throw new Refused(Refused.OLDER, "Version " + info.version + " is older than the installed version " + have + ".");
        return Kind.OLDER;
    }

    /** The package folder it goes into: the one of this game that is there already (one active folder a game), else its file name's, else the catalog's
     *  name for a package without one (Catalog: DualScreen-&lt;title ID&gt;) */
    public static String packageName(Info info, String existing) {
        if (existing != null) return existing;
        return info.filePackage != null ? info.filePackage : "DualScreen-" + info.titleId;
    }

    private static Refused notCompanion() { return new Refused(Refused.NOT_COMPANION, "This file is not a dual-screen companion. Pick a .dsmod.zip file."); }

    private static String damaged(String why) { return "This companion file is damaged (" + why + "). Download it again."; }

    /** The first 4 KiB, where the runtime looks for the manifest's title_id (dm/peanuts/peanuts.c rt_scan_root, CompanionSection.packageFor) */
    private static String head(String s) { return s.length() > 4095 ? s.substring(0, 4095) : s; }

    /** The first "key": "value" string in json, null without one (package.json and the manifest's header are flat; a JSON parser would need org.json) */
    static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return null;
        String v = m.group(1).replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\").trim();
        return v.isEmpty() ? null : v;
    }

    /** The first "key": number in json, 0 without one */
    private static int number(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]{1,9})\\b").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IOException("sha256: " + e); }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Copies in to the file at most max bytes, refusing a larger file (a picked document's stream: the copy into app storage) */
    public static long copy(InputStream in, File to, long max) throws IOException {
        long total = 0;
        try (java.io.OutputStream o = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                total += n;
                if (total > max) throw new IOException("the file is larger than any companion (" + (max >> 20) + " MB)");
                o.write(buf, 0, n);
            }
        }
        return total;
    }

    /** &gt;0 when version a is newer than b (Catalog.compareVersions, which calls this): numbers between the dots and dashes, a missing one is 0 */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }
    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("[^0-9].*", "")); } catch (NumberFormatException e) { return 0; } }
}
