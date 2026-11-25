package dev.peanuts;

import android.app.ActivityManager;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a companion package: downloads the .dsmod.zip, verifies its sha256, checks that it holds package.json and dualscreen/manifest.json for the expected
 * title, checks it against this app's runtime before ever activating it, and activates it. Layout (dev.peanuts.ui.CompanionVersions, rt_find_package
 * note, dm/peanuts/peanuts.c's PEANUTS companion-versions comment):
 * <ul>
 * <li>&lt;root&gt;/&lt;package&gt;/ - the one *active* version the host (dm/peanuts/peanuts.c rt_find_package) loads at the next game start. Exactly one folder of
 * this exact name ever exists; this is also the pre-existing on-disk shape, so old installs keep working untouched.</li>
 * <li>&lt;root&gt;/.peanuts-versions/&lt;package&gt;/&lt;version&gt;/ - every other version this device has ever downloaded, archived here so Switch to…/Roll
 * back needs no re-download. The leading dot keeps the whole tree outside rt_find_package's scan (it already skips dotfiles) and outside Companions.installed()'s
 * listing (no package.json directly inside it).</li>
 * <li>&lt;package&gt;.new / &lt;package&gt;.old - transient, only ever live for the moment {@link #swap} takes; a killed process can leave one behind with a
 * complete manifest (rt_find_package also refuses to ever load either, native-side belt and suspenders), and {@link #repair} completes or rolls back whichever
 * one it finds the next time this package is touched - it never leaves "no active companion" standing.</li>
 * </ul>
 */
public final class Installer {
    public interface Progress { void on(String what, long done, long total); }

    /** dm/peanuts/rt/mods/mod_runtime.h (DualScreenRuntimeVersion) - the runtime this app's native module host actually implements. No Java binding for the
     * C++ constant exists (it would need new JNI surface, out of this file's scope), so this is kept in sync by hand; a mismatch would make a compatible package
     * look incompatible or vice versa, so check both still agree after any runtime bump. */
    static final int RUNTIME_VERSION = 25;

    private static final String VERSIONS_DIR = ".peanuts-versions";
    private static final Pattern SHA256_LINE = Pattern.compile("([0-9A-Fa-f]{64})");

    /** One lock per package name (found in review): install/activateLocalVersion/remove/forgetLocalVersion hold it for their whole operation, so a
     * repair() triggered by the UI opening a screen can never run concurrently with one of them - without this, repair()'s delete(newSlot) could wipe a
     * half-extracted ".new" an install() is still writing into, or restore ".old" while a swap mid-flight is between its own two renames, making its own
     * error message lie about what just happened. A plain static map keyed by package name is a reasonable simplification for a device with one Peanuts
     * install; if two different companionRoot()s ever legitimately shared a package name, they would serialize unnecessarily but never incorrectly. */
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static ReentrantLock lockFor(String packageName) { return LOCKS.computeIfAbsent(packageName, k -> new ReentrantLock()); }

    private Installer() {}

    public static File installedDir(File root, String packageName) { return new File(root, packageName); }
    private static File versionsRoot(File root, String packageName) { return new File(new File(root, VERSIONS_DIR), packageName); }
    private static File versionDir(File root, String packageName, String version) { return new File(versionsRoot(root, packageName), version); }

    /** The installed version of a package in root, or null */
    public static String installedVersion(File root, String packageName) { return versionOf(installedDir(root, packageName)); }

    private static String versionOf(File dir) {
        File f = new File(dir, "installed.json");
        if (!f.isFile()) return new File(dir, "package.json").isFile() ? "?" : null;
        try { return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optString("version", "?"); } catch (Exception e) { return "?"; }
    }

    /** True when installed.json recorded a digest actually checked against a publisher-provided source. Installs made after this fix always have one (an
     * unverified package is refused, never installed); this stays for packages installed by an older build of this app. */
    public static boolean installedVerified(File root, String packageName) {
        File f = new File(installedDir(root, packageName), "installed.json");
        if (!f.isFile()) return false;
        try { return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).optBoolean("verified", false); } catch (Exception e) { return false; }
    }

    /** Every version downloaded onto this device for a package: the active one plus every archived one, newest first (Catalog.compareVersions) */
    public static List<String> localVersions(File root, String packageName) {
        List<String> out = new ArrayList<>();
        String active = installedVersion(root, packageName);
        if (active != null && !active.equals("?")) out.add(active);
        File[] dirs = versionsRoot(root, packageName).listFiles();
        if (dirs != null) for (File d : dirs) if (d.isDirectory() && new File(d, "dualscreen/manifest.json").isFile()) out.add(d.getName());
        out.sort((a, b) -> Catalog.compareVersions(b, a));
        return out;
    }

    /** Downloads item, verifies it, checks runtime compatibility, and activates it. Kept for callers with no known running build id and no Context to check
     * whether the game is running (the build pipeline: a build-time install has nothing to compare build_ids against yet, and the title being built can't
     * be running). */
    public static String install(Catalog.Item item, File root, File cacheDir, Progress progress) throws IOException { return install(item, root, cacheDir, progress, null, null); }

    /** As above, plus a build-id warning: knownBuildId (PN_BUILD_ID, 16-64 hex digits; null when not known) is checked against the package's
     * dualscreen/manifest.json "module".build_ids the same way dm/peanuts/rt/mods/mod_module.cpp does (either id accepted as a prefix of the other, never below
     * 16 digits). Returns that warning (null: none, or unknown), never silently - the caller decides how loudly to show it. Throws, and activates nothing, if the
     * package's min_runtime is above this app's runtime ({@link #RUNTIME_VERSION}), or if it fails verification. Kept for callers with no Context. */
    public static String install(Catalog.Item item, File root, File cacheDir, Progress progress, String knownBuildId) throws IOException { return install(item, root, cacheDir, progress, knownBuildId, null); }

    /** As above, plus ctx (nullable): when given, refuses with "close the game first" if this app's own game process ({@link #gameRunning}) is alive -
     * swapping the active folder out from under a host that has it open would break lazily-loaded assets mid-session. */
    public static String install(Catalog.Item item, File root, File cacheDir, Progress progress, String knownBuildId, Context ctx) throws IOException {
        ReentrantLock lock = lockFor(item.packageName);
        lock.lock();
        try {
            refuseIfGameRunning(ctx);
            repairLocked(root, item.packageName);
            File zip = new File(cacheDir, item.titleId + "-" + item.packageName + "-" + item.version + ".dsmod.zip");
            try {
                download(item.url, zip, item.size, progress);
                verify(zip, item, progress);   // throws on a mismatch or a missing digest - never "installs as unverified"
                return extractAndActivate(zip, item.titleId, item.packageName, item.version, item.tag, item.url, true, root, knownBuildId, ctx, progress);
            } finally {
                zip.delete();
            }
        } finally {
            lock.unlock();
        }
    }

    /** What {@link #installFile} did: the package and version now active, the version before (null: none), and the build-id warning (null: none) */
    public static final class FileInstall {
        public final CompanionFile.Kind kind;
        public final String packageName, version, was, warning;
        FileInstall(CompanionFile.Kind kind, String packageName, String version, String was, String warning) { this.kind = kind; this.packageName = packageName; this.version = version; this.was = was; this.warning = warning; }
        public boolean changed() { return kind != CompanionFile.Kind.SAME; }
    }

    /**
     * Installs a companion from a file on the device (Install from file, the companion command's path) into root with the catalog install's own steps:
     * the same per-package lock, repair, "close the game first", extraction (zip slip refused), package.json and manifest check for the title, min_runtime
     * refusal and atomic swap that archives the version it replaces. What it cannot have is the catalog's digest: {@link CompanionFile#read} checked every
     * entry's CRC and the module library's sha256 instead, and installed.json says "verified": false. info: CompanionFile.read of zip. wantTitle (null:
     * any game) refuses a companion of another game; an older version than the installed one is refused (CompanionFile.Refused.OLDER) unless allowOlder.
     * Off the main thread.
     */
    public static FileInstall installFile(File zip, CompanionFile.Info info, File root, String wantTitle, String wantName, boolean allowOlder, Context ctx, Progress progress) throws IOException {
        String pkg = CompanionFile.packageName(info, dev.peanuts.settings.CompanionSection.packageFor(root, info.titleId));
        ReentrantLock lock = lockFor(pkg);
        lock.lock();
        try {
            refuseIfGameRunning(ctx);
            repairLocked(root, pkg);
            String have = installedVersion(root, pkg);
            CompanionFile.Kind kind = CompanionFile.decide(info, wantTitle, wantName, have, RUNTIME_VERSION, allowOlder);
            if (kind == CompanionFile.Kind.SAME) return new FileInstall(kind, pkg, have, have, null);
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
            String warning = extractAndActivate(zip, info.titleId, pkg, info.version, "file", "file:" + (info.fileName == null ? "" : info.fileName), false, root, null, ctx, progress);
            delete(versionDir(root, pkg, info.version));   // an archived copy of the version now active would list it twice under Versions
            return new FileInstall(kind, pkg, info.version, have, warning);
        } finally {
            lock.unlock();
        }
    }

    /** Roll back / switch to a version already on this device (localVersions()): no network, no re-extraction, just the atomic swap. Kept for callers with no
     * known running build id and no Context. */
    public static void activateLocalVersion(File root, String packageName, String version) throws IOException { activateLocalVersion(root, packageName, version, null, null); }

    /** As above, plus the same min_runtime refusal and build-id warning install() applies - "before activating any version, install or rollback". Kept for
     * callers with no Context. */
    public static String activateLocalVersion(File root, String packageName, String version, String knownBuildId) throws IOException { return activateLocalVersion(root, packageName, version, knownBuildId, null); }

    /** As above, plus ctx (nullable) - see install()'s overload: refuses while the game is running */
    public static String activateLocalVersion(File root, String packageName, String version, String knownBuildId, Context ctx) throws IOException {
        ReentrantLock lock = lockFor(packageName);
        lock.lock();
        try {
            refuseIfGameRunning(ctx);
            repairLocked(root, packageName);
            String active = installedVersion(root, packageName);
            if (version.equals(active)) return null;
            File src = versionDir(root, packageName, version);
            if (!src.isDirectory()) throw new IOException(packageName + " " + version + " is not on this device");
            refuseIfIncompatible(src);
            String warning = buildIdWarning(src, knownBuildId);
            refuseIfGameRunning(ctx);   // re-check right before the swap (found in review): this window is tiny, but still a window
            swap(root, packageName, src, version);
            return warning;
        } finally {
            lock.unlock();
        }
    }

    /** Kept for callers with no Context (never refuses for a running game) */
    public static void remove(File root, String packageName) {
        try { remove(root, packageName, null); } catch (IOException ignored) { /* ctx == null: remove(..., Context) never actually throws without one */ }
    }

    /** As above, plus ctx (nullable): refuses ("close the game first") while this app's own game process is alive, same reasoning as install()'s overload -
     * removing the companion the host currently has open is the same hazard as switching it */
    public static void remove(File root, String packageName, Context ctx) throws IOException {
        ReentrantLock lock = lockFor(packageName);
        lock.lock();
        try {
            refuseIfGameRunning(ctx);
            delete(installedDir(root, packageName));
            delete(versionsRoot(root, packageName));
            delete(new File(root, packageName + ".new"));
            delete(new File(root, packageName + ".old"));
        } finally {
            lock.unlock();
        }
    }

    /** Removes one archived (non-active) version from this device, freeing its storage; refuses to remove the active one */
    public static void forgetLocalVersion(File root, String packageName, String version) throws IOException {
        ReentrantLock lock = lockFor(packageName);
        lock.lock();
        try {
            if (version.equals(installedVersion(root, packageName))) throw new IOException("cannot remove the installed version; switch away from it first");
            delete(versionDir(root, packageName, version));
        } finally {
            lock.unlock();
        }
    }

    // ---- Refuse a switch/remove while the game is open: the host has the active folder's files open, and swapping it out from under a live session would
    // break anything it loads lazily by path afterwards. The same risk applies to peanuts.c's ".old" fallback (round 3): if the host is currently running
    // from a parked ".old" and this process's own repair() archives it away, a lazy load after that would fail too - refusing here while any game runs
    // covers that case as well, since repair()/archiveOldSlot() only ever run under this same per-package lock. ----

    /** True when this app's own ":game" process is alive. ActivityManager.getRunningAppProcesses() has only ever returned the calling app's own processes
     * since API 21 (no permission needed, and it cannot see another app's processes), so this is reliable and cheap. There is no per-title "which game is
     * running" marker in the files this round is scoped to (dev.peanuts.Companion/GameHold/GameActivity aren't Installer's to edit, and Android only ever
     * runs one game at a time in the single ":game" process this app declares), so this cannot tell two different titles apart - "a game is running" and
     * "the title being switched is the one running" are the same question in practice on this app, and refusing either way is the safe default. */
    public static boolean gameRunning(Context ctx) {
        if (ctx == null) return false;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return false;
            String want = ctx.getPackageName() + ":game";
            for (ActivityManager.RunningAppProcessInfo p : procs) if (want.equals(p.processName)) return true;
        } catch (RuntimeException e) { /* best effort: a transient ActivityManager failure should not permanently block switching */ }
        return false;
    }

    private static void refuseIfGameRunning(Context ctx) throws IOException {
        if (gameRunning(ctx)) throw new IOException("close the game first. Its companion cannot be changed while the game runs.");
    }

    // ---- Compatibility: refuse a package this runtime cannot load, warn when it may not support the running game's build ----

    /** The larger "min_runtime" of dualscreen/manifest.json and package.json (dm/peanuts/rt/mods/mod_runtime.h's PackageMinRuntime rule); 0 when neither names one */
    private static int minRuntimeOf(File pkgDir) {
        return Math.max(minRuntimeField(new File(pkgDir, "dualscreen/manifest.json")), minRuntimeField(new File(pkgDir, "package.json")));
    }

    private static int minRuntimeField(File f) {
        if (!f.isFile()) return 0;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return o.has("min_runtime") && !o.isNull("min_runtime") ? o.optInt("min_runtime", 0) : 0;
        } catch (Exception e) { return 0; }
    }

    /** Refuses before anything is touched (pkgDir is still just a staging/archived folder, safe to abandon) when the package needs a newer runtime than this app has */
    private static void refuseIfIncompatible(File pkgDir) throws IOException {
        int min = minRuntimeOf(pkgDir);
        if (min > RUNTIME_VERSION) throw new IOException("this version needs a newer app (companion runtime " + min + ", this app has " + RUNTIME_VERSION + "). Update the app first.");
    }

    /** Null when ok, unknown (knownBuildId null), or the package declares no native module (asset-only, nothing to check); else a warning the caller should
     * surface - this is advisory, the real gate is the native host's own check at load time (mod_module.cpp), which this only tries to predict */
    private static String buildIdWarning(File pkgDir, String knownBuildId) {
        if (knownBuildId == null || knownBuildId.length() < 16) return null;
        File mf = new File(pkgDir, "dualscreen/manifest.json");
        if (!mf.isFile()) return null;
        try {
            JSONObject manifest = new JSONObject(new String(Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8));
            JSONObject module = manifest.optJSONObject("module");
            if (module == null) return null;   // asset-only package: no native module build-id gate applies
            JSONArray ids = module.optJSONArray("build_ids");
            if (ids == null || ids.length() == 0) return null;
            String host = knownBuildId.toUpperCase(Locale.ROOT);
            String trimmedHost = host;
            while (trimmedHost.length() > 16 && trimmedHost.endsWith("0")) trimmedHost = trimmedHost.substring(0, trimmedHost.length() - 1);
            for (int i = 0; i < ids.length(); i++) {
                String b = ids.optString(i, "").toUpperCase(Locale.ROOT);
                if (b.isEmpty()) continue;
                if (host.startsWith(b) || b.startsWith(trimmedHost)) return null;   // a match: same prefix rule as mod_module.cpp's GameModule::Load
            }
            return "this package's module does not list your game's build; it may fail to load (dualscreen/manifest.json's module.build_ids)";
        } catch (Exception e) { return null; }
    }

    // ---- Verification: sha256 from the release asset's digest field, else from a sibling "<asset>.sha256" asset; neither existing refuses the install ----

    /** Downloads nothing itself (zip is already on disk); checks its sha256 against the best source Catalog found. Throws - never installs as "unverified" -
     * on a mismatch, on a missing digest everywhere, or if the .sha256 sibling asset itself could not be fetched/parsed (fails closed, not open) */
    private static void verify(File zip, Catalog.Item item, Progress progress) throws IOException {
        String want = item.sha256;
        if (want == null && item.sha256Url != null) want = parseSha256(fetchText(item.sha256Url));   // a network/parse failure here throws out of fetchText/is left null by parseSha256 - either way "want" stays null and the check below refuses, it does not silently downgrade
        if (want == null) throw new IOException("no checksum published for " + item.packageName + " " + item.version + " - refusing to install an unverified native package (no release digest and no .sha256 asset)");
        String got = sha256(zip, progress);
        if (!want.equalsIgnoreCase(got)) throw new IOException("sha256 mismatch for " + item.packageName + " " + item.version + ": the download does not match the published checksum - refusing to install it");
    }

    private static String sha256(File f, Progress progress) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536]; int n; long done = 0;
                while ((n = in.read(buf)) > 0) { md.update(buf, 0, n); done += n; if (progress != null) progress.on("verify", done, f.length()); }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException("sha256: " + e); }
    }

    private static String parseSha256(String text) {
        Matcher m = SHA256_LINE.matcher(text == null ? "" : text);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "PeanutsNative");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    // ---- Extraction and the atomic active-slot swap ----

    private static void download(String url, File to, long total, Progress progress) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "PeanutsNative");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000); c.setReadTimeout(60000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] buf = new byte[65536]; long done = 0; int n;
            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); done += n; if (progress != null) progress.on("download", done, total); }
        } finally { c.disconnect(); }
    }

    /** verified: the zip matched a publisher's digest (the catalog's install); false for a file from the device (installFile), shown as "unverified" */
    private static String extractAndActivate(File zip, String titleId, String packageName, String version, String tag, String url, boolean verified, File root, String knownBuildId, Context ctx, Progress progress) throws IOException {
        File fresh = new File(root, packageName + ".new");
        delete(fresh);
        if (!fresh.mkdirs()) throw new IOException("cannot create " + fresh);
        try {
            extract(zip, fresh, progress);
            if (!new File(fresh, "package.json").isFile() || !new File(fresh, "dualscreen/manifest.json").isFile()) throw new IOException("this file is not a dual-screen companion (no package.json and dualscreen/manifest.json)");
            String manifest = new String(Files.readAllBytes(new File(fresh, "dualscreen/manifest.json").toPath()), StandardCharsets.UTF_8);
            String title = new JSONObject(manifest).optString("title_id", "").toUpperCase(Locale.ROOT);
            if (!title.isEmpty() && !title.equals(titleId)) throw new IOException("package is for title " + title + ", not " + titleId);
            refuseIfIncompatible(fresh);   // before anything is activated - fresh is still just a staging folder, safe to abandon on throw (the finally below deletes it)
            String warning = buildIdWarning(fresh, knownBuildId);
            try (OutputStream o = new FileOutputStream(new File(fresh, "installed.json"))) {
                o.write(new JSONObject().put("version", version).put("tag", tag).put("url", url).put("verified", verified).toString(2).getBytes(StandardCharsets.UTF_8));
            }
            // Found in review: the game-running check at install()'s entry only covers the moment the download started; download+verify+extract
            // above can take minutes, long enough for the user to start the game in the meantime. Re-check right before the swap, which is the actual
            // hazard, rather than trust a check that is now stale.
            refuseIfGameRunning(ctx);
            swap(root, packageName, fresh, version);
            return warning;
        } catch (org.json.JSONException e) {
            throw new IOException("manifest: " + e.getMessage());
        } finally {
            delete(fresh);   // a no-op once swap() has already renamed it away
        }
    }

    /** The one atomic activation point (install, Switch to, Roll back): archives whatever is active now (if anything) and promotes newActiveSource in its
     * place. Never leaves "no active companion" standing - if promoting the new one fails, the archived one goes straight back before this returns. The
     * previous version's final archive-into-.peanuts-versions step (after promotion already succeeded) is best effort: if it can't complete, the previous
     * version is left parked in "&lt;package&gt;.old" rather than lost, and {@link #repair} finishes that step (or gives up on it) the next time this package
     * is touched. */
    private static void swap(File root, String packageName, File newActiveSource, String newVersion) throws IOException {
        File active = installedDir(root, packageName);
        File oldSlot = new File(root, packageName + ".old");
        if (oldSlot.exists()) {
            // Found in review: oldSlot can already be occupied by a version an earlier repair()/archiveOldSlot() could not file away (a rename
            // failure, not expected but not impossible) - try to archive it before clearing the slot, rather than silently discarding that rollback copy.
            archiveOldSlot(root, packageName, oldSlot);
            if (oldSlot.exists()) {
                android.util.Log.w(Companion.TAG, "swap: could not archive the parked " + oldSlot + "; discarding it to free the slot for " + packageName + " " + newVersion);
                delete(oldSlot);
            }
        }
        boolean movedOld = false;
        if (active.isDirectory()) {
            if (!active.renameTo(oldSlot)) throw new IOException("cannot activate " + packageName + " " + newVersion + ": the currently active version would not move aside");
            movedOld = true;
        }
        if (!newActiveSource.renameTo(active)) {
            if (movedOld && oldSlot.renameTo(active)) throw new IOException("cannot activate " + packageName + " " + newVersion + "; the previous version is still active");
            // both renames failed - the rarest case. The previous version (if any) stays parked in "<package>.old", never deleted, so repair() can restore it later
            throw new IOException("cannot activate " + packageName + " " + newVersion + (movedOld ? "; the previous version is safely parked and will be restored automatically" : ""));
        }
        if (movedOld) archiveOldSlot(root, packageName, oldSlot);
    }

    /** Best effort: files the just-displaced active folder into the version archive so Switch to/Roll back can find it later. Never throws - the activation
     * this follows has already succeeded, so a problem here only costs that one version's instant-rollback convenience, not safety */
    private static void archiveOldSlot(File root, String packageName, File oldSlot) {
        String ver = versionOf(oldSlot);
        if (ver == null || ver.equals("?")) { delete(oldSlot); return; }   // unreadable: nothing worth keeping
        File dest = versionDir(root, packageName, ver);
        if (dest.exists()) { delete(oldSlot); return; }   // already archived (e.g. a previous repair() got to it): this is a duplicate
        File parent = dest.getParentFile();
        if ((parent.isDirectory() || parent.mkdirs()) && oldSlot.renameTo(dest)) return;
        // leaves oldSlot in place on failure; repair() picks it up next time instead of this call deleting a real version's only copy
    }

    /** Self-heal entry point for callers outside an install/switch/remove - dev.peanuts.ui.CompanionVersions.load() and dev.peanuts.settings.CompanionSection
     * call this when their screen opens, on their own worker thread, so a half-done swap is noticed without waiting for the next install. Kept for callers
     * with no Context (never skips for a running game - see the Context overload below for why that matters). */
    public static void repair(File root, String packageName) { repair(root, packageName, null); }

    /** As above, plus ctx (nullable). Two separate guards apply, for two different reasons:
     * <ul>
     * <li>tryLock(): this must never run while install()/activateLocalVersion()/remove()/forgetLocalVersion() holds the package's lock - repair()'s
     * delete(newSlot) could otherwise wipe a half-extracted ".new" an install() is still writing into, or restore ".old" mid-swap and make that swap's own
     * "the previous version is safely parked" error lie. If the package is busy, this is a no-op - the operation holding the lock will finish the job
     * itself (install()/activateLocalVersion() call {@link #repairLocked} directly, under the same lock, before doing anything else).</li>
     * <li>gameRunning(ctx): the lock above only serializes repair with another Installer call on this process - it says nothing about whether the game is
     * open. A kill mid-swap can leave no active folder, after which peanuts.c's own fallback runs the host from the parked ".old"; if the game is then
     * started from that state and the user opens CompanionSection or the Versions sheet, repair() would rename ".old" to active (or archive it) right
     * out from under the running host. Skip entirely while a game is running, same as install()/activateLocalVersion()/remove() refuse outright - the
     * fallback in peanuts.c is enough until the game closes and repair() can run safely.</li>
     * </ul> */
    public static void repair(File root, String packageName, Context ctx) {
        ReentrantLock lock = lockFor(packageName);
        if (!lock.tryLock()) return;
        try {
            if (gameRunning(ctx)) return;
            repairLocked(root, packageName);
        } finally {
            lock.unlock();
        }
    }

    /** The actual self-heal: completes or rolls back a swap a killed process left half-done, and files away a previous version still parked in
     * "&lt;package&gt;.old". Callable only with the package's lock already held (install()/activateLocalVersion() call this directly; everyone else goes
     * through {@link #repair}). */
    private static void repairLocked(File root, String packageName) {
        File active = installedDir(root, packageName);
        File oldSlot = new File(root, packageName + ".old");
        File newSlot = new File(root, packageName + ".new");
        if (!active.isDirectory()) {
            if (isUsablePackage(oldSlot) && oldSlot.renameTo(active)) { delete(newSlot); return; }   // roll back to the pre-swap state: the safest default
            // Found in review: isUsablePackage alone is not enough here. extractAndActivate() writes installed.json only after extraction and
            // every check pass, so a FIRST install (nothing active yet, no ".old" to roll back to) killed mid-extract can leave a ".new" that already has
            // package.json + dualscreen/manifest.json (zip entry order often puts those early) but is missing assets/modules that come later in the zip.
            // Promoting that partial folder would make the host load a broken package. Require installed.json too - it is written last, after the whole
            // extraction and the manifest/runtime checks, so its presence really does mean "this is a complete, checked package", not just "this has
            // some of the right files".
            if (isPromotableNew(newSlot) && newSlot.renameTo(active)) { delete(oldSlot); return; }   // nothing to roll back to: complete the swap forward instead
            delete(oldSlot); delete(newSlot);   // neither is usable (or newSlot is an incomplete first install): both are garbage, not a version worth keeping
            return;
        }
        delete(newSlot);   // always safe scratch once something is active: a .new that never got promoted never became load-bearing
        if (oldSlot.isDirectory()) archiveOldSlot(root, packageName, oldSlot);
    }

    private static boolean isUsablePackage(File d) { return d.isDirectory() && new File(d, "package.json").isFile() && new File(d, "dualscreen/manifest.json").isFile(); }

    /** isUsablePackage() plus installed.json - the one marker extractAndActivate() only ever writes after a full, checked extraction. Only ever used to
     * decide whether a ".new" is safe to promote; ".old" doesn't need this (it was the active folder a moment ago, which always had installed.json). */
    private static boolean isPromotableNew(File d) { return isUsablePackage(d) && new File(d, "installed.json").isFile(); }

    /** Unzips into dir; an entry escaping dir (zip slip) fails the install */
    private static void extract(File zip, File dir, Progress progress) throws IOException {
        String base = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e; long files = 0;
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dir, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new IOException("zip entry escapes the package: " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(f)) { byte[] buf = new byte[65536]; int n; while ((n = z.read(buf)) > 0) o.write(buf, 0, n); }
                if (progress != null) progress.on("extract", ++files, 0);
            }
        }
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
