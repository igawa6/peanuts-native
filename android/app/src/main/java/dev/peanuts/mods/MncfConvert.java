package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.davdxpx.mistel.mncf.CodeModule;
import io.github.davdxpx.mistel.mncf.ConvertException;
import io.github.davdxpx.mistel.mncf.Converter;
import io.github.davdxpx.mistel.mncf.Source;

/**
 * Every cheat file the player adds that is not .mncf becomes one (Mistel's spec, section 15), with everything that is known about it: one .mncf a source for
 * all of its builds (an Atmosphère, Eden, Yuzu or Ryujinx file of each build ID, upstream's export, an earlier import), the game's ID and name, each build's
 * version, where it came from (origin: the format and the zip's, folder's or file's name, the site its credits link to), and who made it: the people its credits
 * name, else the player who adds it, else nobody. With the game's own main at hand (the pack: MncfMain) the converter fills each patch's expect from it and finds
 * the places by patterns, so a cheat made for one build also works on a later one where its place is found; without it the cheats keep their offsets for their
 * builds. The raw code of every cheat is kept word for word, so no cheat is lost. No Android classes: CheatLoaderJvmTest runs it.
 */
public final class MncfConvert {
    private MncfConvert() {}

    /** What a conversion knows besides the files: the game, its builds' versions, its own main for the pack's build, who adds it */
    public static final class Known {
        public final String titleId, gameName, author;
        public final String build;                       // the pack's main build (16 hex, upper case), whose main is `main`; null without a pack
        final Map<String, String> versions;              // build ID (16 hex) → its version name ("2.0.2"), as far as the app knows them
        final MainSource main;
        public Known(String titleId, String gameName, String author, String build, Map<String, String> versions, MainSource main) {
            this.titleId = titleId; this.gameName = gameName; this.author = author == null || author.trim().isEmpty() ? null : author.trim();
            this.build = build == null ? null : build.toUpperCase(Locale.ROOT); this.versions = versions == null ? Collections.<String, String>emptyMap() : versions; this.main = main;
        }
    }

    /** Gives the game's own main of the pack's build when it is needed (read once: it is the size of the game's code) */
    public interface MainSource {
        CodeModule main() throws IOException;
    }

    /** A pack's main, read when first asked for; null when the pack has none */
    public static MainSource of(File pack) {
        CodeModule[] kept = new CodeModule[1];
        boolean[] read = new boolean[1];
        return () -> {
            if (!read[0]) { read[0] = true; kept[0] = pack == null ? null : MncfMain.of(pack); }
            return kept[0];
        };
    }

    /** The name the origin keeps for a source's format: Atmosphère's code in each of its layouts (Eden, Yuzu and Ryujinx keep the same files) */
    static final String FORMAT = "atmosphere";

    /**
     * The Atmosphère files of one source (build ID → text) as one .mncf text. origin: the source's name as the player had it (a zip's, folder's or file's
     * name), null when none. ConvertException when the files are not Atmosphère code the converter reads (its code() "atmosphere" or "source")
     */
    public static String fromAtmosphere(Map<String, String> byBuild, String origin, Known k) throws ConvertException, IOException {
        List<Source> sources = new ArrayList<>();
        CodeModule main = null;
        boolean mainAsked = false;
        for (Map.Entry<String, String> e : byBuild.entrySet()) {
            String bid = e.getKey().toUpperCase(Locale.ROOT);
            Source.Builder b = Source.builder(bid, CheatSources.body(e.getValue()));   // lines that are not code, EdiZon's sections: Mistel's converter reads them
            String v = k.versions.get(bid);
            if (v != null && !v.isEmpty()) b.version(v);   // two builds of one version name: the converter names them by their build IDs
            if (k.build != null && k.build.equals(bid) && k.main != null) {
                if (!mainAsked) { mainAsked = true; main = k.main.main(); }
                if (main != null && sameBuild(main, bid)) b.module(main);
            }
            sources.add(b.build());
        }
        Converter.Options o = Converter.Options.forGame(k.titleId);
        if (k.gameName != null && !k.gameName.isEmpty()) o = o.gameName(k.gameName);
        if (origin != null && !origin.trim().isEmpty()) o = o.origin(origin.trim());
        if (k.author != null) o = o.authors(Collections.singletonList(k.author));
        return Converter.fromAtmosphere(sources, o).text();
    }

    /** Whether the main read from the pack is of this build (the pack names its build ID; a pack of another version has another main) */
    static boolean sameBuild(CodeModule main, String bid16) {
        String id = main.buildId();
        return id == null || id.toUpperCase(Locale.ROOT).startsWith(bid16.toUpperCase(Locale.ROOT));
    }
}
