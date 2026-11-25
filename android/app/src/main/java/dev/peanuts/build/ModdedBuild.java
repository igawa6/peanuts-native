package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.peanuts.mods.ModStore;

/**
 * Plain-Java glue between {@link ModBuild}'s module-list plan and the on-device build (DESIGN-MODS.md "Code injection"/"ExeFS binary patch"): the
 * variant's own folder layout, the extra argv a modded "rewrite" step needs ({@code scripts/pack_android.py --modules/--mod-exefs/--mod-patches}),
 * and the exlaunch-style-hook warning (point 4 of the mods build task: the hooks a module such as exlaunch's {@code subsdk9} writes into main while
 * the game runs never take effect on this host — main is read-only once rewritten and {@code svcSetProcessMemoryPermission} is not implemented;
 * a mod whose branches are in its files already, an IPS patch of main and a module they go to, is another matter and works: dm/tools/xmod.py). Kept free of {@code android.*} so it is
 * exercised on the JVM alone, the same pattern as {@link ModBuild} and {@code dev.peanuts.mods.ModStore} (javac/java tests, {@code
 * peanuts/tests/test_modded_build.py}). The actual job wiring (creating/resuming the variant's {@code Job}, starting {@code PackService}) is
 * {@code Prepare.runModded}, which this class's static methods feed.
 */
public final class ModdedBuild {
    private ModdedBuild() {}

    /** The variant's own work folder (never the normal build's &lt;dump&gt;/../&lt;id&gt;-work, so the two jobs never share one job.json): {@code
     *  <game home>/<variantId>-work}, gameHome being {@code dev.peanuts.mods.ModRuntime.home}'s return for this title. */
    public static File workDir(File gameHome, String variantId) { return new File(gameHome, variantId + "-work"); }

    /** The normal pack's shader library, reused as-is by the variant build (code mods never change shaders, DESIGN-MODS.md "Shaders are build-time
     *  only"): {@code <normalPack>/shaders}, the same relative path {@code pack_android.py}'s own {@code pack.tsv} "shaders" row records. */
    public static File normalShadersDir(File normalPack) { return new File(normalPack, "shaders"); }

    /**
     * The "modules" row of a pack's {@code pack.tsv} (its tab-separated {@code key\tvalue} rows, {@code pack_android.py}'s own writer): the title's
     * normal module list, in profile order, e.g. {@code [main, subsdk0, sdk]} for Pokemon Brilliant Diamond. Null when the pack has no modules row
     * (not a finished pack, or a pack.tsv this reader cannot find) - the caller then has nothing to plan a variant from and should refuse the build.
     */
    public static List<String> normalModules(File normalPack) {
        File f = new File(normalPack, "pack.tsv");
        if (!f.isFile()) return null;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                if (line.substring(0, tab).equals("modules")) {
                    String rest = line.substring(tab + 1).trim();
                    List<String> out = new ArrayList<>();
                    for (String m : rest.split(" ")) if (!m.isEmpty()) out.add(m);
                    return out;
                }
            }
        } catch (IOException e) { return null; }
        return null;
    }

    /**
     * The "build" rows of a pack's {@code pack.tsv} ({@code build}, a tab, {@code <module>=<build id>}; one per module): module → build ID, in the pack's
     * order, for {@code dev.peanuts.mods.ModImporter}'s check that a mod's IPS patches are for this game's build. Empty when the pack has none or cannot be read.
     */
    public static java.util.Map<String, String> buildIds(File normalPack) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(new File(normalPack, "pack.tsv").toPath(), StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                int eq = c.length < 2 ? -1 : c[1].indexOf('=');
                if (c[0].equals("build") && eq > 0) out.put(c[1].substring(0, eq).trim(), c[1].substring(eq + 1).trim());
            }
        } catch (IOException e) { /* no pack.tsv: nothing to compare a mod with */ }
        return out;
    }

    /** The file a variant's pack folder holds besides the pack ({@link #markVariant}): its variant id */
    public static final String VARIANT_MARK = ".variant";

    private static final java.util.regex.Pattern IN_APP = java.util.regex.Pattern.compile("([0-9A-Fa-f]{16})-mod-[0-9a-f]{6,}"), IN_BUILD_FOLDER = java.util.regex.Pattern.compile("mod-[0-9a-f]{6,}");

    /** Marks a finished variant's pack folder as one ({@link #isVariantPack}); quiet when it cannot be written (the folder's place still says what it is) */
    public static void markVariant(File variantPack, String variantId) {
        try { Files.write(new File(variantPack, VARIANT_MARK).toPath(), (variantId + "\n").getBytes(StandardCharsets.UTF_8)); } catch (IOException | RuntimeException e) { /* by its place */ }
    }

    /**
     * Whether a pack folder is a build with mods' and not a game's own: it holds the mark, or it lies where the app puts a variant, packs/&lt;title ID&gt;-mod-&lt;hash&gt;
     * in the app's storage or &lt;title ID&gt;/mod-&lt;hash&gt;/pack in the build folder (dev.nxnative.android.AppStorage.pack; the variants made before the mark).
     * A variant's pack is a complete pack of the same game, which the launcher's list once took for the game's own (Ondevice.Panel.onMessage): whoever is
     * handed "the game's pack" asks this first, since the Mods section plans its builds from the game's pack and deletes variants' folders
     */
    public static boolean isVariantPack(File pack) {
        if (pack == null) return false;
        if (new File(pack, VARIANT_MARK).isFile() || IN_APP.matcher(pack.getName()).matches()) return true;
        File parent = pack.getParentFile();
        return pack.getName().equals("pack") && parent != null && IN_BUILD_FOLDER.matcher(parent.getName()).matches();
    }

    /** Where the game's own pack is when variantPack lies in one of the app's two places for a variant: packs/&lt;title ID&gt;, or &lt;title ID&gt;/pack; else null */
    public static File normalPackBeside(File variantPack) {
        if (variantPack == null) return null;
        java.util.regex.Matcher m = IN_APP.matcher(variantPack.getName());
        if (m.matches()) return new File(variantPack.getParentFile(), m.group(1));
        File parent = variantPack.getParentFile();
        if (variantPack.getName().equals("pack") && parent != null && IN_BUILD_FOLDER.matcher(parent.getName()).matches() && parent.getParentFile() != null) return new File(parent.getParentFile(), "pack");
        return null;
    }

    /** {@link #autoBuild}'s answers: nothing to do by itself, start the build with mods now, or start it when what is going on has ended */
    public static final String AUTO_NONE = "none", AUTO_START = "start", AUTO_WAIT = "wait";

    /**
     * Whether the build with mods of the code mods that are on starts by itself (after an install, a switch, a delete, or when the game's page opens), so that
     * a mod that needs a build gets it without a tap on "Build with mods". buildable: code mods are on and there is something to build from them (the game is
     * built, the plan has no refusal and names a module or a patch); ready: their variant's pack can be played ({@link #variantProblem} null); failed: the build
     * of this very variant failed or was stopped since the app started, and nothing was changed or tapped since (no second try by itself: it would fail the same
     * way, for ever); building: this variant is being built now; busy: something else must end first (another build, of any game, with or without mods: they
     * share one packer; an install or a delete, whose files the build reads; or the app is not on screen, where Android starts no build).
     */
    public static String autoBuild(boolean buildable, boolean ready, boolean failed, boolean building, boolean busy) {
        if (!buildable || ready || failed || building) return AUTO_NONE;
        return busy ? AUTO_WAIT : AUTO_START;
    }

    /** {@link #variantProblem}'s answer for a variant that has no pack yet */
    public static final String NOT_BUILT = "not built";

    /**
     * Whether a start of the game can take a variant's pack: null when it can, else why not, in a few words for "Build with mods" to show. NOT_BUILT: no
     * pack.tsv there. Else the pack is there and cannot be started as it is: a module of its modules row has no rewritten code or no key, which is a Rebuild
     * with mods that was stopped or failed part way (scripts/pack_android.py takes a module's key away before it rewrites it and writes it last; the pack.tsv
     * of the build before stays). The launcher refuses such a pack with "The pack cannot start this game" and offers Prepare again, which makes the normal
     * build and never this one: the game did not start at all until its mods were turned off. A start with the normal pack that says the mods are not built
     * is the answer for it too. One small file and two names per module, at each start.
     */
    public static String variantProblem(File variantPack) {
        if (!new File(variantPack, "pack.tsv").isFile()) return NOT_BUILT;
        List<String> modules = normalModules(variantPack);
        if (modules == null || modules.isEmpty()) return "its build is not complete";
        for (String m : modules)
            if (!new File(variantPack, m + ".patched.bin").isFile() || !new File(variantPack, m + ".android.key").isFile()) return "its build did not finish: " + m;
        return null;
    }

    /**
     * Removes the builds with mods of a game that no choice of its mods' switches gives any more (keep: dev.peanuts.mods.ModStore.reachableVariantIds, from the
     * mods installed now; null: unknown, nothing is removed): after a code mod is deleted its builds stayed, a pack of modules and a copy of the shader library
     * each, with nothing in the app to remove them, and a mod installed again under the same name got the old mod's build as "Ready". A variant
     * {@code <TITLE ID>-mod-<hash>} has its pack in the app's storage (appPacks/&lt;variant id&gt;, dev.nxnative.android.AppStorage.pack) or in the build folder
     * (buildGame/mod-&lt;hash&gt;/pack, dev.peanuts.PeanutsPrefs.buildPackDir), and its work folder in the game's home ({@link #workDir}); any of the three folders
     * may be null or missing. Only names of exactly that shape are touched. Returns how many variants went. Deletes: off the main thread.
     */
    public static int sweepVariants(String titleId, java.util.Set<String> keep, File appPacks, File buildGame, File gameHome) {
        if (keep == null || titleId == null) return 0;
        String prefix = titleId.toUpperCase(Locale.ROOT) + "-mod-";
        java.util.Set<String> gone = new java.util.TreeSet<>();
        for (File dir : new File[] {appPacks, gameHome}) {
            File[] kids = dir == null ? null : dir.listFiles(File::isDirectory);
            if (kids != null) for (File k : kids) {
                String n = k.getName(), id = dir == gameHome ? (n.endsWith("-work") ? n.substring(0, n.length() - 5) : "") : n;
                if (isVariantId(id, prefix) && !keep.contains(id)) { ModStore.deleteQuietly(k); gone.add(id); }
            }
        }
        File[] kids = buildGame == null ? null : buildGame.listFiles(File::isDirectory);
        if (kids != null) for (File k : kids) {
            String id = k.getName().startsWith("mod-") ? prefix + k.getName().substring(4) : "";
            if (isVariantId(id, prefix) && !keep.contains(id) && (new File(k, "pack").isDirectory() || k.list() == null || k.list().length == 0)) { ModStore.deleteQuietly(k); gone.add(id); }
        }
        return gone.size();
    }

    /** {@code <prefix><lower-case hex>}: a variant id as ModStore.variantId writes it */
    private static boolean isVariantId(String id, String prefix) {
        return id.startsWith(prefix) && id.substring(prefix.length()).matches("[0-9a-f]{6,}");
    }

    /**
     * The modded "rewrite" step's extra argv, one token per line (joined by the caller into {@code Job.modArgs}; {@code PackService}'s "rewrite"
     * case splits it back on "\n"). Empty when plan has nothing a mod supplies ({@code plan.source} empty): a plain replace-only mod still produces
     * {@code --modules}/{@code --mod-exefs} lines (its module stays in the same position, but its bytes come from the mod), while a mod that adds
     * no module and ships no exefs/exefs_patches at all (impossible per {@code ModStore.enabledCodeMods}'s own filter, but defensive here too)
     * yields nothing, i.e. a build that would do exactly what the normal pack already does.
     */
    public static List<String> rewriteArgv(ModBuild.Plan plan) {
        List<String> out = new ArrayList<>();
        if (plan == null || plan.refusal != null || plan.source == null || (plan.source.isEmpty() && plan.patchSets.isEmpty())) return out;
        out.add("--modules");
        out.add(String.join(",", plan.modules));
        for (String name : plan.modules) {
            ModStore.Mod mod = plan.source.get(name);
            File exefsFile = mod == null ? null : new File(mod.exefs(), name);
            if (exefsFile != null && exefsFile.isFile()) { out.add("--mod-exefs"); out.add(name + "=" + exefsFile.getPath()); }
            // every patch set is offered to every module: pack_android.py applies only the files named for that module's build ID (Plan.patchSets)
            for (File set : plan.patchSets) { out.add("--mod-patches"); out.add(name + "=" + set.getPath()); }
        }
        return out;
    }

    /** {@link #rewriteArgv}, joined one token per line, ready for {@code Job.modArgs}/{@code Upstream.jobModArgs} ("" when there is nothing to add) */
    public static String rewriteArgvJoined(ModBuild.Plan plan) { return String.join("\n", rewriteArgv(plan)); }

    /**
     * Point 4 of the mods build task: a warning for each module a mod supplies (added or replaced; never main) that looks like an exlaunch-style hook
     * module, whose runtime hooks never take effect on this host (main is read-only once rewritten, {@code svcSetProcessMemoryPermission} is
     * unimplemented) - never a build refusal, just a clear sentence for the build log/UI, one per such module, joined by a blank line. A module counts
     * when its own bytes hold an ASCII "exlaunch" or "exl::" marker (the framework's loader strings and its namespace in the names it exports), under
     * any name: a mod may ship its hook module as subsdk1 as well. {@code subsdk9} also counts when the profile did not already have one (an ADDED
     * subsdk9 is the shape exlaunch itself takes by default). Null when no supplied module gives a signal: a plain replacement, or an added module
     * whose branches into main are in its code already (Luminescent Platinum's subsdk1, which dm/tools/xmod.py handles).
     */
    public static String exlaunchWarning(List<String> profileModules, ModBuild.Plan plan) {
        if (plan == null || plan.refusal != null || plan.source == null) return null;
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, ModStore.Mod> e : plan.source.entrySet()) {
            String name = e.getKey();
            ModStore.Mod mod = e.getValue();
            if (name.equals("main") || mod == null) continue;
            boolean added = name.equals("subsdk9") && (profileModules == null || !profileModules.contains(name));
            if (!added && !containsExlaunchMarker(new File(mod.exefs(), name))) continue;
            out.add(name + " (from " + mod.name + ") looks like an exlaunch-style hook module. Its hooks do not work in this app: it loads and runs, "
                    + "but what it means to change in the game's code while the game runs does not happen.\n"
                    + "Technical: main is read-only once converted, and svcSetProcessMemoryPermission, which exlaunch's hooking needs, is not implemented.");
        }
        return out.isEmpty() ? null : String.join("\n\n", out);
    }

    /** containsExlaunchMarker's answers by the file's path, size and time: the Mods section asks at every render (each second of a build), and the answer
     *  takes a read of the whole module */
    private static final java.util.Map<String, Boolean> EXLAUNCH = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean containsExlaunchMarker(File f) {
        if (f == null || !f.isFile()) return false;
        String key = f.getPath() + '|' + f.length() + '|' + f.lastModified();
        Boolean known = EXLAUNCH.get(key);
        if (known != null) return known;
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            boolean has = s.contains("exlaunch") || s.contains("exl::");
            EXLAUNCH.put(key, has);
            return has;
        } catch (IOException e) {
            return false;
        }
    }
}
