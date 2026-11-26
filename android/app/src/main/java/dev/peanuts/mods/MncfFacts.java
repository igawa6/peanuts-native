package dev.peanuts.mods;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.davdxpx.mistel.mncf.Build;
import io.github.davdxpx.mistel.mncf.MncfFile;
import io.github.davdxpx.mistel.mncf.SignatureResult;

/**
 * What an .mncf file brings, counted from the file itself, for the Add dialog and the sheet after an import (CheatImportUi): its cheats, the versions it
 * names, how many check the game's code before they change it (a patch or an override always names the original bytes), how many find their place by a pattern
 * (so they keep working after a game update), the numbers a player can set, the cheats that say what they do, the ones kept only as the code they came as, and
 * who signed it. No Android classes: CheatLoaderJvmTest counts a file.
 */
public final class MncfFacts {
    public int cheats, checked, patterns, values, described, rawOnly;
    public final List<String> versions = new ArrayList<>();
    public String signer;   // a key Peanuts trusts signed it: the name the trust list gives it; else null (an unsigned file is as any other)

    /** The facts of one file */
    public static MncfFacts of(MncfFile f) { MncfFacts x = new MncfFacts(); x.add(f); return x; }

    /** The facts of several files, added up (a pack) */
    public static MncfFacts of(List<MncfFile> files) { MncfFacts x = new MncfFacts(); for (MncfFile f : files) x.add(f); return x; }

    void add(MncfFile f) {
        for (Build b : f.builds().values()) {
            String main = b.modules().get("main"), v = b.version() != null && !b.version().isEmpty() ? b.version() : b.key();
            if (v != null && !(main != null && main.equalsIgnoreCase(v)) && !versions.contains(v)) versions.add(v);   // a build named by its ID has no version name
        }
        SignatureResult s = f.signature();
        if (signer == null && SignatureResult.VERIFIED.equals(s.result()) && !s.signers().isEmpty()) signer = s.signers().get(0);
        for (io.github.davdxpx.mistel.mncf.Cheat c : f.cheats()) {
            if (c.isMaster()) continue;
            cheats++;
            Map<String, Object> t = c.table();
            boolean code = t.get("patch") instanceof List || t.get("override") instanceof List, core = code || t.get("write") instanceof List || t.get("read") instanceof List;
            if (code) checked++;
            if (finds(t)) patterns++;
            values += c.values().size();
            if (c.about() != null && !c.about().trim().isEmpty()) described++;
            if (!core && t.get("raw") instanceof List) rawOnly++;
        }
    }

    /** Whether any place of the cheat is found by a pattern */
    @SuppressWarnings("unchecked")
    static boolean finds(Map<String, Object> cheat) {
        for (String kind : new String[] {"patch", "write", "override", "read", "when"}) {
            Object ops = cheat.get(kind);
            if (!(ops instanceof List)) continue;
            for (Object o : (List<Object>) ops) {
                if (!(o instanceof Map)) continue;
                Object at = ((Map<String, Object>) o).get(kind.equals("override") ? "function" : "at");
                if (at instanceof Map && ((Map<String, Object>) at).get("find") != null) return true;
            }
        }
        return false;
    }

    /** "2.0.2", "1.0.0 and 2.0.2", "1.0.0, 1.1.0 and 2.0.2"; null when it names none */
    public String versionWords() {
        if (versions.isEmpty()) return null;
        if (versions.size() == 1) return versions.get(0);
        return String.join(", ", versions.subList(0, versions.size() - 1)) + " and " + versions.get(versions.size() - 1);
    }
}
