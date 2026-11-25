package dev.peanuts.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.peanuts.mods.ModStore;

/**
 * Plain-Java logic for a "Build with mods" run (DESIGN-MODS.md "ExeFS binary patch"/"Code injection"): composing the module list and deciding which
 * mod, if any, supplies each module's bytes for a title's modded variant build (its own pack, {@code ModStore.variantId}, never the normal pack). Kept
 * free of {@code android.*} and of any native pack-engine call so it is exercised on the JVM alone (the same pattern as {@link RuntimeShaderMap} and
 * {@code dev.peanuts.mods.ModStore}: {@code peanuts/tests/test_mod_build.py}). The actual file work for one module —
 * {@code dm/tools/patch_ips.py} (an enabled mod's {@code exefs_patches/<build id>.ips(32)}, by build ID, before the rewrite) then
 * {@code dm/tools/patch_nso.py --target android} (the usual ARM64 rewrite, exactly as the normal pack's own modules go through it) — is the packer's:
 * {@code scripts/pack_android.py --modules/--mod-exefs/--mod-patches}, which the phone's PackService runs with the arguments {@link ModdedBuild#rewriteArgv}
 * makes from a {@link Plan} (the variant's job carries them: Prepare.runModded).
 * <p>
 * The cap below is {@code DM_PROFILE_MODULES_CAP} (dm/peanuts/profile/profile_rt.h): the generic host's runtime profile (-PnxGenericHost=true, which
 * Peanuts builds with by default) has eight fixed module-name slots. The per-title (non-generic) host instead compiles {@code DM_GAME_NMOD} as a fixed
 * constant equal to the profile's own module count (dm/games/game.py, dm/host/pack_modules.h) with *no* spare slots at all — on that build, a mod that
 * adds a module the shipped profile does not already list cannot be loaded at all, modded pack or not, without recompiling the host. This class assumes
 * the generic host's eight slots (Peanuts' own default); the caller must refuse a "code" mod outright on a non-generic build whose module list does not
 * already include every module the mod adds.
 */
public final class ModBuild {
    private ModBuild() {}

    /** dm/peanuts/profile/profile_rt.h DM_PROFILE_MODULES_CAP: the generic host's fixed module-name array size */
    public static final int MODULES_CAP = 8;

    /** A code mod's exefs/ may hold these; dm/host/loader.c load_modules has no scan of its own, so a mod can only add a module from this known set
     *  (one a game.tsv profile already uses the name for — subsdk0..subsdk9, rtld, sdk, main) */
    private static final String[] KNOWN_MODULE_NAMES = {"main", "rtld", "sdk", "subsdk0", "subsdk1", "subsdk2", "subsdk3", "subsdk4", "subsdk5", "subsdk6",
            "subsdk7", "subsdk8", "subsdk9"};

    public static boolean isKnownModuleName(String name) {
        for (String k : KNOWN_MODULE_NAMES) if (k.equals(name)) return true;
        return false;
    }

    /** The result of {@link #compose}: either {@link #modules}/{@link #source} (success), or {@link #refusal} (a clear reason, nothing to build) */
    public static final class Plan {
        /** The modded build's full module list: the profile's, with each added module where the console loads it ({@link #insertInConsoleOrder}); null on refusal */
        public final List<String> modules;
        /** module name → the mod supplying its exefs bytes, absent (not present as a key) for a module the normal pack supplies unchanged; null on refusal */
        public final Map<String, ModStore.Mod> source;
        /** Every enabled code mod's exefs_patches/&lt;set&gt;/ folder, in priority order, and its exefs/ folder when that holds .ips files (the emulators'
         *  layout: Luminescent Platinum ships exefs/&lt;build id&gt;.ips beside the subsdk1 it adds). Atmosphère names the set folders after the patch set, not
         *  a module (exefs_patches/&lt;set&gt;/&lt;build id&gt;.ips), and matches each file to a module by build ID: pack_android.py --mod-patches does the same, so
         *  every set is offered to every module and only the files named for that module's build ID apply. Empty on refusal */
        public final List<java.io.File> patchSets;
        /** Why nothing can be built; null on success */
        public final String refusal;

        private Plan(List<String> modules, Map<String, ModStore.Mod> source, List<java.io.File> patchSets, String refusal) {
            this.modules = modules; this.source = source; this.patchSets = patchSets; this.refusal = refusal;
        }
    }

    private static Plan refuse(String why) { return new Plan(null, null, new ArrayList<>(), why); }

    /**
     * profileModules: the title's normal module list, in its game.tsv/profile order (e.g. {@code [main, subsdk0, sdk]} for Pokémon Brilliant
     * Diamond/Shining Pearl). codeMods: {@code ModStore.enabledCodeMods} for this title, already in first-wins priority order. Each mod's
     * {@code exefs()} directory is scanned for files named exactly one of {@link #isKnownModuleName}'s names (a module the mod replaces, when the name
     * is already in profileModules, or adds, when it is not): the first mod in priority order to name a given module wins it (replace or add), later
     * mods naming the same module are ignored for it — the same first-enabled-wins rule as the romfs overlay and {@code ModStore.enabledRomfsDirs}, so
     * a player whose mods are in another order gets one consistent rule everywhere, not a different one for code than for files. An added module (not
     * already in profileModules) goes where the console loads it ({@link #insertInConsoleOrder}). genericHost: Peanuts' own default build (-PnxGenericHost=true);
     * false refuses outright as soon as any mod would add a module, since the per-title host has no spare module slot to give it (see the class doc).
     */
    public static Plan compose(List<String> profileModules, List<ModStore.Mod> codeMods, boolean genericHost) {
        List<String> modules = new ArrayList<>(profileModules);
        Map<String, ModStore.Mod> source = new LinkedHashMap<>();
        List<java.io.File> patchSets = new ArrayList<>();
        for (ModStore.Mod mod : codeMods) {
            java.io.File[] sets = mod.exefsPatches().listFiles(java.io.File::isDirectory);
            if (sets != null) { java.util.Arrays.sort(sets); java.util.Collections.addAll(patchSets, sets); }
            String[] names = mod.exefs().list();
            if (names == null) continue;   // no exefs/ (an exefs_patches-only mod never changes the module list, only a module's own bytes)
            for (String name : names) if (isIpsName(name)) { patchSets.add(mod.exefs()); break; }   // exefs/<build id>.ips: the folder is a patch set too
            for (String name : names) {
                if (!isKnownModuleName(name)) continue;   // exefs/ may also hold main.npdm, not a module name the loader takes
                if (source.containsKey(name)) continue;   // an earlier (higher-priority) mod already claimed this module
                boolean adds = !modules.contains(name);
                if (adds) {
                    if (!genericHost) return refuse(mod.name + " adds a part of the game's code that this app version cannot load for this game, so it cannot "
                            + "be built with that mod. Turn " + mod.name + " off, or remove " + name + " from its exefs folder.\n"
                            + "Technical: " + name + " has no module slot in this game's build, which was not made with the generic host.");
                    insertInConsoleOrder(modules, name);
                }
                source.put(name, mod);
            }
        }
        if (modules.size() > MODULES_CAP)
            return refuse("The mods that are on add too many parts of the game's code: " + modules.size() + ", and the app can load " + MODULES_CAP
                    + ". Turn a mod off, then build again.\nTechnical: modules " + String.join(" ", modules) + ", the generic host's limit is DM_PROFILE_MODULES_CAP "
                    + "in dm/peanuts/profile/profile_rt.h.");
        return new Plan(modules, source, patchSets, null);
    }

    /** A patch file's name: .ips or .ips32, in either letter case (dm/tools/patch_ips.py find_patches looks for the two) */
    static boolean isIpsName(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".ips") || n.endsWith(".ips32");
    }

    /** The order Horizon's loader maps a process's modules in (dm/tools/xmod.py CONSOLE_ORDER): each right after the one before it */
    private static final String[] CONSOLE_ORDER = {"rtld", "main", "subsdk0", "subsdk1", "subsdk2", "subsdk3", "subsdk4", "subsdk5", "subsdk6", "subsdk7", "subsdk8",
            "subsdk9", "sdk"};

    private static int consoleRank(String name) {
        for (int i = 0; i < CONSOLE_ORDER.length; i++) if (CONSOLE_ORDER[i].equals(name)) return i;
        return CONSOLE_ORDER.length;
    }

    /**
     * name added to modules where the console loads it: before the first module that comes later in the console's order, at the end when there is none
     * (dm/tools/xmod.py insert, the same rule). An added subsdk1 then stands between subsdk0 and sdk here as there: its symbols take the same precedence
     * (a lookup goes through the modules in load order), and the module is as near to main as the mod's own branches into it expect. The profile's
     * modules keep their order.
     */
    static void insertInConsoleOrder(List<String> modules, String name) {
        int at = modules.size();
        for (int i = 0; i < modules.size(); i++) if (consoleRank(modules.get(i)) > consoleRank(name)) { at = i; break; }
        modules.add(at, name);
    }

    /**
     * Point 3 of the mods design (a replaced main whose build does not match the title's registered build IDs): refuses unless registeredBuildIds16
     * (the title's profile {@code build} rows, each NSO build ID's first 16 hex characters, lower case) already lists moddedMainBuildId16. A mod that
     * only patches main via exefs_patches (never replaces the whole file) keeps the original build ID and always passes this check — only a mod
     * shipping a complete replacement main (a different compile, a different build ID) can fail it.
     */
    public static String checkMainBuildId(String moddedMainBuildId16, List<String> registeredBuildIds16) {
        for (String id : registeredBuildIds16) if (id.equalsIgnoreCase(moddedMainBuildId16)) return null;
        return "The mod replaces the game's code with a version the app has not tested, so the game may not run correctly with it. It was not built.\n"
                + "Technical: the mod's main is build " + moddedMainBuildId16 + ", the app's list for this game has " + String.join(", ", registeredBuildIds16) + ".";
    }
}
