package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The in-place shader upgrade (2026-11-24, ~/pn-build/xver-upgrade-spec.md): a game whose build has the shaders of an older app version and none of this one
 * gets this version's from the older ones in seconds, by dm/gfx/tools/vk_android_upgrade.py in the packer's process (PackService MSG_UPGRADE), with no game
 * files, split or translation, so packs made on a computer or imported are upgraded too. What is decided here, without Android (ShaderUpgradeJvmTest):
 * <ul>
 * <li>{@link #candidates}: which libraries of a pack may be upgraded: none when the pack has this version's, or the profile's shader inputs are not known; else
 * each older v-&lt;V&gt;/dmsh_vk.lib of the game (header magic, game and version as its folder says, the profile's shader inputs: a library of other shader
 * tables is not what a full build makes) that its {@link #UNCHECKED} marker does not name, newest first.</li>
 * <li>{@link #run}: the tool's two calls for each candidate in turn: {@code --path <old>} (exit 0: a path exists, 3: none, 1: unreadable), then
 * {@code <old> <new>} into shaders/v-&lt;this version&gt;/dmsh_vk.lib (the tool writes &lt;new&gt;.tmp and renames it). The written library counts only when its
 * header reads as this version's, this game's, this ABI's and of the profile's inputs; else it goes. The older library always stays (it is what the game plays
 * while nothing better is there, and what Settings › Storage offers to delete once this version's is there, StorageScan OLD_SHADERS, as after a delta update
 * or a restamp).</li>
 * <li>The marker (critique B1, review M3/M4): &lt;lib&gt;.unchecked names, one line each, the library ids (shader_reuse.py library_id) of libraries written at
 * that path with kept shaders whose full check has not passed. A marker refuses only the library it names (or every library when it names none: written by
 * nothing this app makes now), so a library rebuilt at that path is not refused for the one before. shader_reuse.py writes and clears it; CheckDue copies it
 * into the pack and writes one when it drops a check.</li>
 * </ul>
 * Exit 3 or 1: nothing changes; the game goes on as before (the older library plays, the shader update is offered or runs as it did) and the log says why.
 */
public final class ShaderUpgrade {
    private ShaderUpgrade() {}

    /** The tool, as the packer runs it (a path in the APK's tools.zip: build.gradle's nxPackTools takes dm/gfx/tools/*.py) */
    public static final String TOOL = "dm/gfx/tools/vk_android_upgrade.py";
    /** Beside a library whose kept shaders were never checked: &lt;lib&gt;.unchecked (vk_android_upgrade.py and shader_reuse.py UNCHECKED) */
    public static final String UNCHECKED = CheckDue.UNCHECKED;
    /** The library file in a v-&lt;V&gt; folder */
    public static final String LIB = "dmsh_vk.lib";
    /** The tool's exit codes (vk_android_upgrade.py main), and the packer's answer when it cannot run one now */
    public static final int UPGRADED = 0, ERROR = 1, NO_PATH = 3, BUSY = -2;
    /** What the player reads while it runs (build-text-style.md) */
    public static final String TEXT = "Updating the game's shaders for this app version";
    /** The packer's message keys (PackService MSG_UPGRADE / MSG_UPGRADE_DONE) */
    public static final String KEY_TITLE = "title", KEY_PACK = "pack", KEY_LIBS = "libs", KEY_CODE = "code", KEY_LINE = "line", KEY_FROM = "from";
    /** Room asked beside the new library's size before it is written (as ShaderLibs.restamp) */
    static final long MARGIN = 64L << 20;
    /** A .tmp in v-&lt;current&gt; this much older than now is a killed upgrade's (a running write moves its time all along) */
    static final long STALE_MS = 5 * 60_000L;

    static final int MAGIC = 0x4B56534D, FORMAT = 1;   // dmsh_abi_vk.h DMSH_VK_LIB_MAGIC, DMSH_VK_LIB_VERSION (ShaderLibs.MAGIC, VERSION)

    /** What is read of a library's header (dmsh_vk_header: vk_chain at 24, game at 40, inputs at 56, blob_off at 96, abi at 116), as ShaderLibs.header reads it */
    static final class Header {
        String vkChain, game, inputs, abi;
        long blobOff;
    }

    private static String text(byte[] h, int off, int n) {
        int e = off;
        while (e < off + n && h[e] != 0) e++;
        return new String(h, off, e - off, StandardCharsets.US_ASCII);
    }

    /** The header of a library file, null when it is not one (short, another magic or format) */
    static Header header(File lib) {
        byte[] h = new byte[128];
        try (RandomAccessFile f = new RandomAccessFile(lib, "r")) {
            f.readFully(h);
        } catch (IOException e) {
            return null;
        }
        int magic = (h[0] & 0xff) | (h[1] & 0xff) << 8 | (h[2] & 0xff) << 16 | (h[3] & 0xff) << 24, format = (h[4] & 0xff) | (h[5] & 0xff) << 8 | (h[6] & 0xff) << 16 | (h[7] & 0xff) << 24;
        if (magic != MAGIC || format != FORMAT) return null;
        Header r = new Header();
        r.vkChain = text(h, 24, 16);
        r.game = text(h, 40, 16);
        r.inputs = text(h, 56, 16);
        r.abi = text(h, 116, 12);
        long b = 0;
        for (int i = 7; i >= 0; i--) b = b << 8 | (h[96 + i] & 0xff);
        r.blobOff = b;
        return r;
    }

    /**
     * A library's id as shader_reuse.py library_id makes it: the SHA-256 of everything before its blob (header and tables) then its size in decimal, the first 32
     * hex digits; null when it cannot be read
     */
    public static String libraryId(File lib) {
        Header h = header(lib);
        long size = lib.length();
        if (h == null || h.blobOff < 128 || h.blobOff > size) return null;
        try (InputStream in = Files.newInputStream(lib.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (long left = h.blobOff; left > 0; ) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) return null;
                md.update(buf, 0, n);
                left -= n;
            }
            md.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return b.substring(0, 32);
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** This version's library of a pack: shaders/v-&lt;current&gt;/dmsh_vk.lib (what the host maps first, vka_shader.c lib_load) */
    public static File target(File packDir, String current) { return new File(new File(packDir, "shaders/v-" + current), LIB); }

    /** The marker beside a library */
    public static File marker(File lib) { return CheckDue.marker(lib); }

    /** Whether lib's marker refuses it: the marker names lib's id, or names no id at all (or cannot be read); false without a marker */
    public static boolean unchecked(File lib) {
        File m = marker(lib);
        if (!m.exists()) return false;
        String t = read(m);
        if (t == null) return true;
        List<String> ids = CheckDue.ids(t);
        return ids.isEmpty() || ids.contains(libraryId(lib));
    }

    /**
     * The libraries of a pack an upgrade may start from, newest first: none when the pack has this version's library (current: DMSH_VK_CHAIN) or inputs (the
     * profile's shader inputs, Profile.shaderInputs) is not known, else each shaders/v-&lt;V&gt;/dmsh_vk.lib with V another version of 12 hex digits whose header is
     * a library's, names titleId's game, V as its version and inputs as its shader inputs, and that its marker does not refuse ({@link #unchecked}). Which of
     * them a path to this version exists for is the tool's to say (--path)
     */
    public static List<File> candidates(File packDir, String titleId, String current, String inputs) {
        List<File> out = new ArrayList<>();
        if (packDir == null || titleId == null || current == null || !current.matches("[0-9a-f]{12}") || inputs == null || inputs.isEmpty() || inputs.equals("unknown")
                || target(packDir, current).isFile()) return out;
        File[] dirs = new File(packDir, "shaders").listFiles(f -> f.getName().matches("v-[0-9a-f]{12}"));
        if (dirs == null) return out;
        for (File d : dirs) {
            String v = d.getName().substring(2);
            File lib = new File(d, LIB);
            if (v.equals(current) || !lib.isFile()) continue;
            Header h = header(lib);
            if (h == null || !titleId.equalsIgnoreCase(h.game) || !v.equals(h.vkChain) || !inputs.equals(h.inputs) || unchecked(lib)) continue;
            out.add(lib);
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /**
     * The candidates less a library whose full check is still due from an earlier app version (todo: check.todo's library id and version, CheckDue.todo; null:
     * none due): this app can never run that check (PackService drops it at its next start, shader_reuse.py full_check drops a check of another version), so its
     * kept shaders stay unchecked for good. The marker naming the todo's library is written now, before a Play could upgrade it ahead of that drop
     */
    public static List<File> withoutDueCheck(List<File> libs, String[] todo, String current, File packDir) {
        if (todo == null || todo[1].equals(current)) return libs;
        List<File> out = new ArrayList<>();
        for (File lib : libs) {
            if (lib.getParentFile().getName().equals("v-" + todo[1])) CheckDue.markUnchecked(packDir, todo[1], todo[0]);
            else out.add(lib);
        }
        return out;
    }

    /**
     * Removes what an upgrade killed half way left in v-&lt;current&gt; (its dmsh_vk.lib.tmp, older than STALE_MS at now), and then the folder when that left it empty,
     * so a restamp can take its name and an export carries no half file. → whether something went
     */
    public static boolean cleanStale(File packDir, String current, long now) {
        if (packDir == null || current == null || !current.matches("[0-9a-f]{12}")) return false;
        File lib = target(packDir, current), tmp = new File(lib.getPath() + ".tmp"), dir = lib.getParentFile();
        if (!tmp.isFile() || now - tmp.lastModified() < STALE_MS || !tmp.delete()) return false;
        String[] left = dir.list();
        if (left != null && left.length == 0) dir.delete();
        return true;
    }

    /**
     * Why a library the tool wrote is not this version's of the game (null: it is): its header must be a library's with vk_chain current, the game titleId, the
     * abi of this app (DMSH_VK_ABI) and the profile's shader inputs. A library that is not is deleted (run): the launcher would take it for current by its path
     * alone (Pack.libraryOf)
     */
    public static String verify(File lib, String titleId, String current, String abi, String inputs) {
        if (!lib.isFile()) return "no " + lib.getName() + " written";
        Header h = header(lib);
        if (h == null) return lib + " is not a shader library";
        if (!current.equals(h.vkChain)) return lib + " is of version " + h.vkChain + ", not " + current;
        if (!titleId.equalsIgnoreCase(h.game)) return lib + " is of game " + h.game + ", not " + titleId;
        if (abi != null && !abi.equals(h.abi)) return lib + " carries the ABI " + h.abi + ", not " + abi;
        if (inputs != null && !inputs.equals(h.inputs)) return lib + " has the shader inputs " + h.inputs + ", not " + inputs;
        return null;
    }

    /** One run of the tool in the packer's process (argv after the tool's path) → its exit code; its output goes to the log run was given */
    public interface Tool { int run(String[] argv); }

    /** How an upgrade ended: code (UPGRADED, NO_PATH, ERROR), the library it started from (null when none), the tool's summary line, and why, for the log */
    public static final class Result {
        public final int code;
        public final File from;
        public final String line, why;

        Result(int code, File from, String line, String why) { this.code = code; this.from = from; this.line = line; this.why = why; }
    }

    /** The free space where the new library goes (its nearest folder that exists) */
    static long usable(File target) {
        File d = target.getParentFile();
        while (d != null && !d.exists()) d = d.getParentFile();
        return d == null ? 0 : d.getUsableSpace();
    }

    /**
     * The upgrade of a pack from its candidates (in this order) to target (shaders/v-&lt;current&gt;/dmsh_vk.lib), with the tool's output in log (read back for its
     * summary). For each: --path; on 0, room for a library its size and MARGIN, then the upgrade itself, whose library is checked (verify) and deleted when it
     * is wrong; the first one written ends it. A library already at target ends it at once with nothing done (NO_PATH: a build or a restamp got there first).
     * The older libraries are never touched; a folder the run made for target goes again when it stays empty, and a &lt;new&gt;.tmp left behind goes too. Every
     * outcome but UPGRADED leaves the pack as it was. inputs: the profile's shader inputs (the tool is told them too: DM_VK_UPGRADE_INPUTS)
     */
    public static Result run(List<File> libs, File target, String titleId, String current, String abi, String inputs, Tool tool, File log) {
        if (target.isFile()) return new Result(NO_PATH, null, "", target + " is there already");
        List<String> why = new ArrayList<>();
        int code = NO_PATH;
        File dir = target.getParentFile(), tmp = new File(target.getPath() + ".tmp");
        for (File lib : libs) {
            int p = tool.run(new String[] {"--path", lib.getPath()});
            if (p != UPGRADED) {
                why.add(lib.getParentFile().getName() + ": " + (p == NO_PATH ? "no upgrade path" : "cannot be read (exit " + p + ")") + lastLine(log));
                if (p != NO_PATH) code = ERROR;
                continue;
            }
            long free = usable(target);
            if (free < lib.length() + MARGIN) {
                why.add(lib.getParentFile().getName() + ": not enough free space (" + (free >> 20) + " MB free, " + ((lib.length() + MARGIN) >> 20) + " MB needed)");
                code = ERROR;
                continue;
            }
            boolean made = !dir.isDirectory();
            tmp.delete();
            int u = tool.run(new String[] {lib.getPath(), target.getPath()});
            String bad = u == UPGRADED ? verify(target, titleId, current, abi, inputs) : null;
            if (u == UPGRADED && bad == null) return new Result(UPGRADED, lib, summary(read(log)), "");
            if (bad != null) target.delete();   // whatever it is, the launcher must not take it for this version's library
            tmp.delete();
            if (made) { new File(dir, "inputs.tsv").delete(); dir.delete(); }   // a folder this run made: the tool's stamp beside it goes too, then the folder when empty
            why.add(lib.getParentFile().getName() + ": " + (bad != null ? bad : u == NO_PATH ? "no upgrade path" : "the upgrade stopped (exit " + u + ")") + lastLine(log));
            if (u != NO_PATH) code = ERROR;
        }
        return new Result(code, null, "", String.join("; ", why));
    }

    /**
     * Whether a done job's record of the app that made it (job.json "apk", Job.apk) differs from this app's in the shader version alone, so a library upgraded
     * in place makes the job current again (Job.apkChanged compares engine, dmsh_chain, dmsh_vk_chain and xlate_source): job and app read those keys
     */
    public static boolean onlyVkChainDiffers(Function<String, String> job, Function<String, String> app) {
        for (String k : new String[] {"engine", "dmsh_chain", "xlate_source"}) if (!app.apply(k).equals(job.apply(k))) return false;
        return !app.apply("dmsh_vk_chain").equals(job.apply("dmsh_vk_chain"));
    }

    private static final Pattern PROGRESS = Pattern.compile("(?m)^(?:progress:? (\\d+)(?: |/)(\\d+)|stage: saving the shaders: ([\\d,]+) of ([\\d,]+) MB)");

    /**
     * The tool's last progress of a log's text → {done, total}, null when it has none yet: its "progress: done/total" lines while it upgrades the entries, then
     * write_library's "stage: saving the shaders: X of Y MB" while it writes the file (minutes for a library of gigabytes)
     */
    public static long[] progress(String log) {
        if (log == null) return null;
        Matcher m = PROGRESS.matcher(log);
        long[] last = null;
        while (m.find()) {
            if (m.group(1) != null) last = new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
            else last = new long[] {Long.parseLong(m.group(3).replace(",", "")), Long.parseLong(m.group(4).replace(",", ""))};
        }
        return last;
    }

    /** The tool's summary line ("upgraded &lt;game&gt; v-&lt;old&gt; -&gt; v-&lt;new&gt;: ..."), "" when the log has none */
    public static String summary(String log) {
        if (log == null) return "";
        String found = "";
        for (String l : log.split("\n")) if (l.startsWith("upgraded ")) found = l.trim();
        return found;
    }

    private static String lastLine(File log) {
        String t = read(log);
        if (t == null) return "";
        String[] l = t.trim().split("\n");
        String last = l.length == 0 ? "" : l[l.length - 1].trim();
        return last.isEmpty() ? "" : " (" + last + ")";
    }

    /** A file's text, null when it cannot be read */
    public static String read(File f) {
        try { return f == null ? null : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    /** The log of a title's upgrade in the app's own files (the packer writes it, the launcher reads its progress): &lt;files&gt;/upgrade/&lt;title ID&gt;.log */
    public static File logFile(File filesDir, String titleId) { return new File(filesDir, "upgrade/" + titleId.toUpperCase(Locale.ROOT) + ".log"); }

    /**
     * What the launcher remembers of a library the tool found no path for (NO_PATH), so it is not asked again at every Play: the library (its path, size and
     * time) and this app's version. Another library, or another app version (whose tool may have a path), asks again
     */
    public static String refusedKey(File lib, String current) { return current + "\t" + lib.getPath() + "\t" + lib.length() + "\t" + lib.lastModified(); }

    /** The progress text: TEXT, then the share done once the tool says it ("Updating the game's shaders for this app version, 37 %.") */
    public static String progressText(long[] p) {
        if (p == null || p[1] <= 0) return TEXT + ".";
        return String.format(Locale.ROOT, "%s, %d %%.", TEXT, Math.min(100, p[0] * 100 / p[1]));
    }

    /** Above this, the wait is told as a minute, not seconds (the file is written whole) */
    static final long BIG = 1L << 30;

    /**
     * The line under it: how long (bytes: the largest library it may start from; the first start of the app's builder after an update also unpacks it, so even a
     * small one is "usually" seconds), then what follows when play (Play: the game starts; the game page's Update shaders: nothing more to say)
     */
    public static String waitText(long bytes, boolean play) {
        String t = bytes > BIG ? "This can take a minute." : "This usually takes a few seconds.";
        return play ? t + " Then the game starts." : t;
    }

    /** Writes the marker naming id beside shaders/v-&lt;version&gt;/dmsh_vk.lib of a pack (CheckDue.markUnchecked) → the marker written, null when none was */
    public static File markUnchecked(File packDir, String version, String id) { return CheckDue.markUnchecked(packDir, version, id); }
}
