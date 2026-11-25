package dev.peanuts.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import dev.peanuts.PeanutsPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The library: every game in the roots (the game folder, then the update and DLC folders; each walked two levels deep) as *.nsp, *.xci, *.nsz, *.xcz, *.nup or a dump folder
 * (a version with exefs/main in a layout upstream's DumpLayout reads; mods left out: LibraryWalk), grouped by the base game's title ID, with a status per title.
 * <p>
 * An NSP/XCI's titles come from the importer's probe (ImporterPort.probe: title id, application id, kind, version, name), kept in files/library-cache.json by
 * path+size+mtime so a scan reads each file once; without the importer the title ID is taken from a [16 hex digits] in the file name (common dump naming) and the kind
 * from its low bits (…000 base, …800 update, else DLC). A .nup's packages come from upstream's Nup reader, a dump's title ID from its main.npdm (Pack.titleIdOf), its
 * updates from updates/&lt;version&gt;. Updates and DLC are listed under the base they name (the probe's application id; for an update without one, the title ID with
 * bit 11 cleared; for a DLC, the title ID minus 0x1000 with its low 12 bits cleared).
 * <p>
 * Status: NO_PROFILE when the APK has no profile for the title (BuildConfig.NX_PROFILES); READY when the game is a dump or .nup and upstream's list has it ready
 * (Game.ready) or a pack.tsv lies in &lt;home&gt;/&lt;title ID&gt;-work/&lt;any&gt;/ (home: the dump folder or the .nup's folder, or that folder's parent); else BUILD.
 * Names: the probe's NACP name, else the catalog's catalog.json name, else the profile's, else the title ID.
 * <p>
 * P1 (version truth): a BUILD title also gets a {@link Title#versionState}, read through upstream's {@code Profile} (reflection, same pattern
 * as {@link Upstream}) rather than a new game.tsv parser. One gap: game.tsv's own "package" line (the version the shipped shader tables were built for) is not
 * exposed by {@code Profile} today, so {@link Title#recommendedVersion} is an approximation — the highest version in the profile's build list — not the real
 * package line. Verified true for all 131 profiles as of the phase-2 review. A round-2 runtime guard for this was removed (found in review: it never
 * actually ran, and even fixed would have decompressed assets/pack/tools.zip once per title on the phone for a check that only needs to run once); the
 * check now lives in {@code peanuts/scripts/check-profiles.sh}, a build-time script, zero runtime cost.
 */
final class LibraryScanner {
    static final int BASE = 0, UPDATE = 1, DLC = 2;
    enum Status { READY, BUILD, NO_PROFILE }
    /** The sub-states a BUILD title can be in (corrected in review): NOT_IN_APP mirrors NO_PROFILE (kept distinct so a
     *  caller that only has a Title, not the Status too, can still tell); FILE_GONE: the base or newest update file the scanner once found no longer resolves;
     *  UNSUPPORTED: the chosen version is not one of the profile's builds at all (today's silent "otherBuild"); SUPPORTED_OTHER: it is a listed build, just not
     *  the one the shipped shader tables were made for (expect a slower first build: a full shader scan); RECOMMENDED: the version Build is tuned for;
     *  UNKNOWN: no profile, or the reflective read of its builds failed (never blocks Build; the sheet just has nothing extra to say) */
    enum VersionState { NOT_IN_APP, FILE_GONE, UNSUPPORTED, SUPPORTED_OTHER, RECOMMENDED, UNKNOWN }
    private static final Pattern HEX16 = Pattern.compile("(?<![0-9A-Fa-f])([0-9A-Fa-f]{16})(?![0-9A-Fa-f])");
    private static final Set<String> EXT = new HashSet<>(Arrays.asList(".nsp", ".xci", ".nsz", ".xcz", ".nup"));   // .nsz / .xcz: compressed with nsz (issue #149)

    /** One title inside a file or folder: a base, an update or a DLC */
    static final class Source {
        final File file; final int kind; final String titleId, appId; final long version; final String name, publisher;
        Source(File file, int kind, String titleId, String appId, long version, String name, String publisher) {
            this.file = file; this.kind = kind; this.titleId = titleId; this.appId = appId; this.version = version; this.name = name == null ? "" : name; this.publisher = publisher == null ? "" : publisher;
        }
        /** From the probe (issue #149): the title's content as the .nup will hold it (the CNMT's sizes; -1 unknown: an older cache entry, no keys, a .nup or
         *  a dump), and of a compressed file what unpacking (solid) and checking (block) it takes */
        long size = -1, unpack, check;
        boolean isNup() { return file.getName().toLowerCase(Locale.ROOT).endsWith(".nup"); }
        boolean isDump() { return file.isDirectory(); }
        /** An NSP, XCI, NSZ or XCZ: the importer makes the .nup from it */
        boolean needsImport() { return !isNup() && !isDump(); }
        /** An .nsz or .xcz */
        boolean isCompressed() { return dev.peanuts.build.Compressed.is(file); }
        /** What the .nup gets from it: the probe's size, else the file's own length */
        long importBytes() { return size >= 0 ? size : file.length(); }
    }

    /** A game of the library: its base and what belongs to it, where its files live, its status */
    static final class Title {
        final String titleId;
        String name = "";
        Source base;
        final List<Source> updates = new ArrayList<>(), dlcs = new ArrayList<>();
        /** The original base dumps (NSP/XCI) of a game whose base is a .nup made from them: the game no longer needs them to play or rebuild, only to take a
         *  later update (an update's patch reads the base NCA's raw bytes, which the .nup does not keep). Settings › Library › Free up space offers them */
        final List<Source> originals = new ArrayList<>();
        Status status = Status.BUILD;
        VersionState versionState = VersionState.UNKNOWN;
        /** The profile's builds (game.tsv build rows, via Profile.buildMap/display): version → display ("1.0.1"), null when game.tsv names none. Empty: no
         *  profile, or the reflective read failed */
        Map<Long, String> supportedBuilds = Collections.emptyMap();
        /** The profile's builds as supportedBuilds has them, for every title with a profile, a READY one too (supportedBuilds is a BUILD title's only: its
         *  version state and its sheet read it). What the cover's "update found" question counts (Library.found, UpdateFound): an update the profile knows */
        Map<Long, String> profileBuilds = Collections.emptyMap();
        /** The highest version in supportedBuilds (see the class doc's gap note); -1 without supportedBuilds */
        long recommendedVersion = -1;
        /** The folder that holds (or will hold) the game and its <title ID>-work: the dump folder, the .nup's folder, or <game folder>/<name> for an NSP/XCI */
        File home;
        /** What upstream starts: the dump folder or the .nup (null before the import) */
        File launch;
        /** The pack found for it (null until READY) */
        File packDir;
        Title(String titleId) { this.titleId = titleId; }
        /** The newest update's version, else the base's */
        long version() { long v = base != null ? base.version : 0; for (Source u : updates) v = Math.max(v, u.version); return v; }
        /** The version in words: the Switch form when it is one (upper 16 bits = release), else the number */
        String versionText() { long v = version(); return v == 0 ? "" : (v & 0xFFFF) == 0 ? "v" + (v >> 16) : "v" + v; }
        File workDir() { return home == null ? null : new File(home, titleId + "-work"); }
        /** Where the title's companions live: <work>/peanuts (beside android-pack) */
        File companionRoot() { return packDir != null ? new File(packDir.getParentFile(), "peanuts") : workDir() == null ? null : new File(workDir(), "peanuts"); }
    }

    private final Context ctx;
    private final ImporterPort importer = ImporterPort.get();
    private final Map<String, JSONArray> cache = new HashMap<>();
    private boolean cacheDirty;

    LibraryScanner(Context c) { ctx = c.getApplicationContext(); }

    /** The whole library, titles sorted by name (reads files and the network for names: off the main thread) */
    List<Title> scan() {
        loadCache();
        List<Source> sources = new ArrayList<>();
        for (File root : PeanutsPrefs.roots(ctx)) walk(root, sources);
        // the user's own picks (the sheet's Choose… / Add…), probed like the scan's files
        for (Title t : groupPreview(sources)) {
            String up = PeanutsPrefs.chosenUpdate(ctx, t.titleId);
            if (up != null && new File(up).isFile()) sources.addAll(probe(new File(up)));
            for (String d : PeanutsPrefs.chosenDlc(ctx, t.titleId)) if (new File(d).isFile()) sources.addAll(probe(new File(d)));
        }
        saveCache();
        Map<String, Title> titles = group(sources);
        Map<String, String> profiles = Upstream.profiles();
        Map<String, String> catalogNames = CompanionPort.names(ctx);
        List<Upstream.Entry> list = Upstream.games(ctx);
        String gameDir = PeanutsPrefs.gameDir(ctx);
        for (Title t : titles.values()) {
            // the home folder and what to launch
            if (t.base != null && t.base.isDump()) { t.home = t.base.file; t.launch = t.base.file; }
            else if (t.base != null && t.base.isNup()) { t.home = t.base.file.getParentFile(); t.launch = t.base.file; }
            else {
                String n = !t.base.name.isEmpty() ? t.base.name : catalogNames.getOrDefault(t.titleId, profiles.getOrDefault(t.titleId, t.titleId));
                t.home = gameDir == null ? null : new File(gameDir, safeName(n));
            }
            // the name
            t.name = t.base != null && !t.base.name.isEmpty() ? t.base.name : null;
            if (t.name == null) for (Source u : t.updates) if (!u.name.isEmpty()) { t.name = u.name; break; }
            if (t.name == null) t.name = catalogNames.get(t.titleId);
            if (t.name == null) t.name = profiles.get(t.titleId);
            if (t.name == null) for (Upstream.Entry e : list) if (e.titleId.equals(t.titleId) && !e.name.isEmpty()) { t.name = e.name; break; }
            if (t.name == null || t.name.isEmpty()) t.name = t.titleId;
            // the status
            if (!profiles.containsKey(t.titleId)) t.status = Status.NO_PROFILE;
            else {
                t.packDir = packOf(t, list);
                t.status = t.launch != null && t.packDir != null ? Status.READY : Status.BUILD;
            }
            Collections.sort(t.updates, (a, b) -> Long.compare(b.version, a.version));
            // P1: the version state (not computed for READY: the pack already fits, and for NO_PROFILE the sheet's Profile.missing sentence is the whole story)
            if (t.status == Status.NO_PROFILE) t.versionState = VersionState.NOT_IN_APP;
            else {
                VersionInfo vi = versionInfo(t.titleId);
                t.profileBuilds = vi.builds;   // a READY title's too: the cover asks about an update the profile knows (issue #120; found in review: it never did)
                if (t.status == Status.BUILD) t.versionState = versionState(t, vi);
            }
        }
        List<Title> out = new ArrayList<>(titles.values());
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Only the base titles, before the user's picks are added (to know which titles have picks) */
    private List<Title> groupPreview(List<Source> sources) { return new ArrayList<>(group(sources).values()); }

    /** Sources → titles by application ID; a base that is a dump or .nup wins over an NSP/XCI of the same title (it needs no import), and an uncompressed one
     *  over a compressed one (nothing to unpack); an update or DLC found both ways is listed once, uncompressed */
    private Map<String, Title> group(List<Source> sources) {
        Map<String, Title> titles = new LinkedHashMap<>();
        for (Source s : sources) {
            if (s.kind != BASE) continue;
            Title t = titles.computeIfAbsent(s.appId, Title::new);
            if (t.base == null || (s.isNup() || s.isDump()) && t.base.needsImport() || t.base.isCompressed() && s.needsImport() && !s.isCompressed()) t.base = s;
        }
        for (Source s : sources) {   // a .nup base: the NSPs/XCIs of the same title it was made from (see Title.originals)
            if (s.kind != BASE || !s.needsImport() || !s.file.isFile()) continue;
            Title t = titles.get(s.appId);
            if (t != null && t.base != null && t.base.isNup() && t.base.titleId.equals(s.titleId)) t.originals.add(s);
        }
        for (Source s : sources) {
            if (s.kind == BASE) continue;
            Title t = titles.get(s.appId);
            if (t == null) continue;   // an update or DLC without its base: nothing to build
            List<Source> into = s.kind == UPDATE ? t.updates : t.dlcs;
            boolean dup = false; int twin = -1;
            for (int i = 0; i < into.size(); i++) {
                Source x = into.get(i);
                if (x.file.equals(s.file) && x.titleId.equals(s.titleId) && x.version == s.version) dup = true;
                else if (x.needsImport() && s.needsImport() && x.isCompressed() != s.isCompressed() && x.titleId.equals(s.titleId) && x.version == s.version) twin = i;
            }
            if (dup) continue;
            if (twin < 0) into.add(s);   // a .nup's own updates/DLC are listed too: the sheet shows what is inside it
            else if (into.get(twin).isCompressed()) into.set(twin, s);   // the same title as .nsp and .nsz: the uncompressed one
        }
        return titles;
    }

    /** The pack for a title: upstream's list when it has the game ready, else a pack.tsv in <home>/<ID>-work/<any>/ or <home's parent>/<ID>-work/<any>/, else the build folder's own pack when it is complete */
    private File packOf(Title t, List<Upstream.Entry> list) {
        if (t.launch == null) return null;
        for (Upstream.Entry e : list) if (e.ready && samePath(e.gameDir, t.launch.getPath())) return new File(e.packDir);
        for (File h : new File[] {t.home, t.home == null ? null : t.home.getParentFile()}) {
            if (h == null) continue;
            File[] kids = new File(h, t.titleId + "-work").listFiles(File::isDirectory);
            if (kids != null) for (File k : kids) if (new File(k, "pack.tsv").isFile()) return k;
        }
        // The build folder's own place (<build folder>/<title ID>/pack, where a build with that folder set writes): a pack put there that no finished build
        // listed (copied from a PC, or left when its list entry went) was not seen until it was moved. Taken only when it starts the game as it is (Game.ready,
        // the check Play makes: the title, no unfinished module, the game's build, a shader library), so a pack a build is still writing is not called ready
        for (File dir : PeanutsPrefs.buildGameDirs(ctx, t.titleId)) {
            File built = new File(dir, "pack");
            if (new File(built, "pack.tsv").isFile() && ready(t.titleId, t.launch, built)) return built;
        }
        return null;
    }

    /** Game.ready for a game file and a pack folder that are not in upstream's list (reflection: Game is upstream's); false when it cannot be asked */
    private static boolean ready(String titleId, File game, File pack) {
        try {
            Class<?> k = Class.forName("dev.nxnative.android.Game");
            java.lang.reflect.Constructor<?> ctor = k.getDeclaredConstructor(String.class, String.class, String.class, String.class);
            java.lang.reflect.Method m = k.getDeclaredMethod("ready", k);
            ctor.setAccessible(true); m.setAccessible(true);
            return Boolean.TRUE.equals(m.invoke(null, ctor.newInstance(titleId, "", game.getPath(), pack.getPath())));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static boolean samePath(String a, String b) {
        try { return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath()); } catch (IOException e) { return a.equals(b); }
    }

    // ---- P1: version truth ----

    private static final String NXP = "dev.nxnative.android.";

    /** A BUILD title's version state (class doc): FILE_GONE first (nothing else matters once the file is not where it was found), then whether the newest
     *  version found on disk is in the profile's builds at all, and if so whether it is the one the shipped shader tables were made for. This is the
     *  library-grid pill's state only — it is about the newest version this title has anywhere, not about any one file a sheet's "Choose another…" picked
     *  (found in review: the sheet computes its own, from {@code chosenUpdate}, in {@code GameSheet.chosenVersionState}; this one must not be
     *  read as "the version Build will actually use") */
    private static VersionState versionState(Title t, VersionInfo vi) {
        if (fileGone(t.base) || fileGone(t.updates.isEmpty() ? null : t.updates.get(0))) return VersionState.FILE_GONE;
        t.supportedBuilds = vi.builds;
        t.recommendedVersion = vi.recommended;
        if (vi.builds.isEmpty()) return VersionState.UNKNOWN;   // no profile builds data (the reflective read failed, or game.tsv names none): never blocks Build
        long v = t.version();
        if (!vi.builds.containsKey(v)) return VersionState.UNSUPPORTED;
        return v == vi.recommended ? VersionState.RECOMMENDED : VersionState.SUPPORTED_OTHER;
    }

    private static boolean fileGone(Source s) { return s != null && !s.file.exists(); }

    /** The profile's builds (game.tsv build rows) for a title, via upstream's Profile (reflection, Upstream.java's own pattern): version → display, and the
     *  highest version (the class doc's gap note: an approximation of game.tsv's "package" line, which Profile does not expose — checked at build time
     *  instead of at runtime, see the class doc) */
    static final class VersionInfo {
        final Map<Long, String> builds = new LinkedHashMap<>();
        long recommended = -1;
    }

    private static VersionInfo versionInfo(String titleId) {
        VersionInfo info = new VersionInfo();
        try {
            Class<?> profileClass = Class.forName(NXP + "Profile");
            Method of = profileClass.getDeclaredMethod("of", String.class); of.setAccessible(true);
            Object p = of.invoke(null, titleId);
            if (p == null) return info;
            Method buildMap = profileClass.getDeclaredMethod("buildMap"); buildMap.setAccessible(true);
            Method display = profileClass.getDeclaredMethod("display", long.class); display.setAccessible(true);
            Map<?, ?> bm = (Map<?, ?>) buildMap.invoke(p);
            for (Object k : bm.keySet()) {
                long v = (Long) k;
                Object d = display.invoke(p, v);
                info.builds.put(v, d == null ? null : d.toString());
                if (v > info.recommended) info.recommended = v;
            }
        } catch (Throwable t) {
            Log.w("peanuts", "profile builds " + titleId + ": " + t);
        }
        return info;
    }

    // ---- The walk ----

    /** A root's dump folders and game files (LibraryWalk: two levels deep, mods left out) */
    private void walk(File root, List<Source> out) {
        LibraryWalk.walk(root, 0, new LibraryWalk.Sink() {
            @Override public void dump(File d) { out.addAll(LibraryScanner.this.dump(d)); }
            @Override public void file(File f) {
                String l = f.getName().toLowerCase(Locale.ROOT);
                int dot = l.lastIndexOf('.');
                if (dot > 0 && EXT.contains(l.substring(dot))) out.addAll(probe(f));
            }
        });
    }

    /** A dump folder: one base source (its updates are inside it, updates/<version>), version = the newest update's; with no update folder, the version its
     *  main's build is (LibraryWalk.versionOf: a base or one version's folder that holds an update's whole code, GitHub issue #370), else the base's */
    private List<Source> dump(File d) {
        String id = Upstream.titleIdOf(d);
        if (id == null) return Collections.emptyList();
        long v = 0;
        File[] ups = new File(d, "updates").listFiles(File::isDirectory);
        if (ups != null) for (File u : ups) try { v = Math.max(v, Long.parseLong(u.getName())); } catch (NumberFormatException e) { /* not a version */ }
        if (v == 0) v = Math.max(0, LibraryWalk.versionOf(LibraryWalk.mainBuildId(d), profileBuilds(id)));
        return Collections.singletonList(new Source(d, BASE, id, id, v, "", ""));
    }

    /** The profile's builds (game.tsv build rows: version to module to build ID) through upstream's Profile (reflection, as versionInfo); null when it has none */
    private static Map<Long, Map<String, String>> profileBuilds(String titleId) {
        try {
            Class<?> profileClass = Class.forName(NXP + "Profile");
            Method of = profileClass.getDeclaredMethod("of", String.class); of.setAccessible(true);
            Object p = of.invoke(null, titleId);
            if (p == null) return null;
            Method buildMap = profileClass.getDeclaredMethod("buildMap"); buildMap.setAccessible(true);
            @SuppressWarnings("unchecked") Map<Long, Map<String, String>> bm = (Map<Long, Map<String, String>>) buildMap.invoke(p);
            return bm;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w("peanuts", "profile builds " + titleId + ": " + e);
            return null;
        }
    }

    /** The titles in a file: a .nup's packages, or an NSP/XCI's probe (cached), or its name's title ID */
    List<Source> probe(File f) {
        String l = f.getName().toLowerCase(Locale.ROOT);
        if (l.endsWith(".nup")) {
            List<Source> out = new ArrayList<>();
            String app = Upstream.titleIdOf(f);
            boolean hasBase = false;
            long standaloneVersion = -1;
            for (Upstream.NupPkg p : Upstream.nupPackages(f)) {
                if (p.kind == BASE) {
                    hasBase = true;
                    if (app == null) app = p.titleId;
                }
                // NUP update flag 1 identifies a complete snapshot that does not need the base.
                if (p.kind == UPDATE && (p.flags & 1) != 0) standaloneVersion = Math.max(standaloneVersion, p.version);
                out.add(new Source(f, p.kind, p.titleId, app != null ? app : baseOf(p.titleId, p.kind), p.version, "", ""));
            }
            if (app != null && !hasBase && standaloneVersion >= 0) {
                out.add(new Source(f, BASE, app, app, standaloneVersion, "", ""));
            } else if (out.isEmpty() && app != null) {
                out.add(new Source(f, BASE, app, app, 0, "", ""));
            }
            return out;
        }
        String key = f.getPath() + "|" + f.length() + "|" + f.lastModified();
        JSONArray a = cache.get(key);
        if (a == null && importer.available() && ImporterPort.ensureKeys(ctx)) {
            String json = importer.probe(f.getPath());
            if (json != null) {
                try { a = new JSONArray(json); cache.put(key, a); cacheDirty = true; } catch (org.json.JSONException e) { Log.w("peanuts", "probe " + f + ": " + e); }
            } else Log.w("peanuts", "probe " + f + ": " + importer.lastError());
        }
        List<Source> out = new ArrayList<>();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("title_id").toUpperCase(Locale.ROOT);
                int kind = o.optInt("kind", BASE);
                String app = o.optString("app_id", "").toUpperCase(Locale.ROOT);
                if (!app.matches("[0-9A-F]{16}")) app = baseOf(id, kind);
                Source s = new Source(f, kind, id, app, o.optLong("version", 0), o.optString("name"), o.optString("publisher"));
                s.size = o.optLong("size", -1); s.unpack = o.optLong("unpack", 0); s.check = o.optLong("check", 0);
                out.add(s);
            }
        }
        if (out.isEmpty()) {   // no importer (or a file it could not read): the name's [title ID], its kind by the low bits
            Matcher m = HEX16.matcher(f.getName());
            if (m.find()) {
                String id = m.group(1).toUpperCase(Locale.ROOT);
                int kind = id.endsWith("000") ? BASE : id.endsWith("800") ? UPDATE : DLC;
                out.add(new Source(f, kind, id, baseOf(id, kind), 0, "", ""));
            }
        }
        return out;
    }

    /** The base title ID an update or DLC belongs to, from its own ID: an update clears bit 11; a DLC is base + 0x1000 + n */
    static String baseOf(String titleId, int kind) {
        try {
            long id = Long.parseUnsignedLong(titleId, 16);
            if (kind == UPDATE) id &= ~0x800L;
            else if (kind == DLC) id = (id - 0x1000L) & ~0xFFFL;
            return String.format(Locale.ROOT, "%016X", id);
        } catch (NumberFormatException e) { return titleId; }
    }

    /** A folder name for a game (the .nup's folder under the game folder): letters, digits, space, - _ . ( ) only */
    static String safeName(String n) {
        String s = n.replaceAll("[^A-Za-z0-9 ._()\\-]", "").trim();
        return s.isEmpty() ? "game" : s;
    }

    // ---- Icons ----

    /** The app's copy of a title's icon: files/icons/&lt;title ID&gt;.png (app storage: Clear data and an uninstall empty it, an update keeps it) */
    private static File iconFile(Context c, Title t) { return new File(PeanutsPrefs.iconsDir(c), t.titleId + ".png"); }

    /**
     * The title's icon as files/icons/&lt;ID&gt;.png, null without one (off the main thread). In order: the one already there; the PNG beside a .nup of the title
     * (the PC builder's, or one {@link #keep} put there); the icon inside a .nup of the title (its meta block: the importer's since #158, the nup packer's); the
     * importer's from an NSP/XCI of the title, the originals of a .nup base among them (before #158 they were not asked, so an original put back did not bring
     * the icon back). A file kept in files/icons is never read again, so every way in writes a whole PNG the decoder reads, or nothing.
     * A title whose base is a .nup without an icon of its own gets the one found here beside it ({@link #keep}).
     */
    static File icon(Context c, Title t) {
        File out = iconFile(c, t);
        if (out.isFile() && out.length() > 0) { keep(c, t); return out; }
        Set<File> nups = new java.util.LinkedHashSet<>();   // a .nup that holds the base and its update is one file
        if (t.base != null && t.base.isNup()) nups.add(t.base.file);
        for (Source s : t.updates) if (s.isNup()) nups.add(s.file);
        for (File f : nups) {
            try { if (NupIcon.copyPng(NupIcon.beside(f), out)) return out; } catch (IOException | RuntimeException e) { Log.w("peanuts", "icon beside " + f.getName() + ": " + e); }
        }
        for (File f : nups) if (fromInside(f, out)) return out;
        ImporterPort imp = ImporterPort.get();
        if (!imp.available() || !ImporterPort.ensureKeys(c)) return null;
        List<Source> all = new ArrayList<>();   // the base, else the NSPs/XCIs a .nup base was made from (Title.originals), then the updates
        if (t.base != null) all.add(t.base);
        all.addAll(t.originals);
        all.addAll(t.updates);
        for (Source s : all) {
            if (!s.needsImport()) continue;
            out.getParentFile().mkdirs();
            if (imp.icon(s.file.getPath(), s.titleId, out.getPath()) == 0 && out.isFile() && out.length() > 0) { keep(c, t); return out; }
            out.delete();
        }
        return null;
    }

    /** The icon inside a .nup (NupIcon.inside: a JPEG or a PNG) as a PNG at out; false when it carries none, or one the decoder does not read */
    private static boolean fromInside(File nup, File out) {
        byte[] b;
        try { b = NupIcon.inside(nup); } catch (IOException | RuntimeException e) { Log.w("peanuts", "icon inside " + nup.getName() + ": " + e); return false; }
        if (b == null) return false;
        Bitmap bm;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth > 2048 || o.outHeight > 2048) { Log.w("peanuts", "icon inside " + nup.getName() + ": " + o.outWidth + "x" + o.outHeight + " is too large, skipped"); return false; }
            bm = BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (OutOfMemoryError e) { Log.w("peanuts", "icon inside " + nup.getName() + ": out of memory decoding it"); return false; }
        if (bm == null) { Log.w("peanuts", "icon inside " + nup.getName() + ": " + b.length + " bytes the decoder does not read"); return false; }
        File tmp = null;
        try {
            File dir = out.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            tmp = File.createTempFile(out.getName() + ".", ".tmp", dir);
            try (FileOutputStream o = new FileOutputStream(tmp)) { if (!bm.compress(Bitmap.CompressFormat.PNG, 100, o)) throw new IOException("the PNG encoder refused it"); }
            if (!tmp.renameTo(out)) throw new IOException("cannot rename " + tmp.getName());
            tmp = null;
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w("peanuts", "icon inside " + nup.getName() + ": " + e);
            return false;
        } finally {
            if (tmp != null) tmp.delete();
            bm.recycle();
        }
    }

    /**
     * A title whose base is a .nup without an icon of its own gets the app's copy of its icon beside it (NupIcon.keep), once that copy is there: its cover then
     * outlives files/icons and the NSP or XCI it came from (issue #158). Library calls it for the icons it already had from files/icons; off the main thread
     */
    static void keep(Context c, Title t) {
        if (t.base == null || !t.base.isNup()) return;
        try { if (NupIcon.keep(t.base.file, iconFile(c, t))) Log.i("peanuts", "icon: kept beside " + t.base.file.getName() + " (it carries none)"); }
        catch (IOException | RuntimeException e) { Log.w("peanuts", "icon beside " + t.base.file.getName() + ": " + e); }
    }

    // ---- The probe cache ----

    private File cacheFile() { return new File(ctx.getFilesDir(), "library-cache.json"); }

    private void loadCache() {
        cache.clear(); cacheDirty = false;
        File f = cacheFile();
        if (!f.isFile()) return;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); JSONArray a = o.optJSONArray(k); if (a != null) cache.put(k, a); }
        } catch (Exception e) { Log.w("peanuts", "library cache: " + e); }
    }

    private void saveCache() {
        if (!cacheDirty) return;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, JSONArray> e : cache.entrySet()) if (new File(e.getKey().substring(0, e.getKey().indexOf('|'))).isFile()) o.put(e.getKey(), e.getValue());   // entries of files that are gone fall out
            Files.write(cacheFile().toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { Log.w("peanuts", "library cache: " + e); }
        cacheDirty = false;
    }
}
