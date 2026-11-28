package dev.peanuts.amiibo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every amiibo, offline: the table peanuts/scripts/amiibo-db.py makes from AmiiboAPI's database (MIT), res/raw/amiibo_db.tsv of the peanuts source set.
 * An ID is the 8 bytes a figure's data holds at 0x54, as 16 hex digits: the game series (the first 12 bits), the character, its variant, the type, the model
 * number and the amiibo series (the 7th byte). What each amiibo does in a Switch game comes with it ("U" rows), with whether the game keeps data on it.
 * No Android class: the JVM tests read the table from the repository; the app's copy is AmiiboStand.db.
 */
public final class AmiiboDb {
    /** One amiibo of the table, or one the table does not know (an imported file of a newer figure): then its name is made from its ID */
    public static final class Amiibo {
        public final String id, name;   // 16 lower-case hex digits; AmiiboAPI's name ("Link - Archer")
        public final boolean known;
        Amiibo(String id, String name, boolean known) { this.id = id; this.name = name; this.known = known; }
        /** The name as the app shows it: AmiiboAPI's "Link - Archer" as "Link (Archer)" (no " - " between two values, UI-RULES) */
        public String title() { return display(name); }
        /** The nickname a game reads (at most 10 characters): the character's name without the variant */
        public String nickname() { return nick(name); }
        public String typeCode() { return id.substring(6, 8); }
        public String seriesCode() { return id.substring(12, 14); }
        public String gameSeriesCode() { return id.substring(0, 3); }
        @Override public String toString() { return id + " " + name; }
    }

    private final Map<String, Amiibo> byId = new LinkedHashMap<>();
    private final Map<String, String> series = new HashMap<>(), gameSeries = new HashMap<>(), types = new HashMap<>(), games = new HashMap<>();
    private final List<String> words = new ArrayList<>();
    private final Map<String, Map<String, List<int[]>>> uses = new HashMap<>();   // title ID -> amiibo ID -> {word, writes}
    private final Map<String, Set<String>> titlesOf = new HashMap<>();
    private String source = "";

    public static AmiiboDb parse(Reader r) throws IOException {
        AmiiboDb db = new AmiiboDb();
        BufferedReader in = new BufferedReader(r);
        for (String line; (line = in.readLine()) != null; ) {
            if (line.startsWith("# The amiibo of")) db.source = line.substring(2);
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "A": if (f.length >= 3) db.byId.put(f[1], new Amiibo(f[1], f[2], true)); break;
                case "S": if (f.length >= 3) db.series.put(f[1], f[2]); break;
                case "G": if (f.length >= 3) db.gameSeries.put(f[1], f[2]); break;
                case "T": if (f.length >= 3) db.types.put(f[1], f[2]); break;
                case "N": if (f.length >= 3) db.games.put(f[1], f[2]); break;
                case "W": if (f.length >= 3) { int n = Integer.parseInt(f[1]); while (db.words.size() <= n) db.words.add(""); db.words.set(n, f[2]); } break;
                case "U":
                    if (f.length < 5) break;
                    int w = Integer.parseInt(f[2]), keeps = "1".equals(f[3]) ? 1 : 0;
                    Map<String, List<int[]>> per = db.uses.computeIfAbsent(f[1].toUpperCase(Locale.ROOT), k -> new LinkedHashMap<>());
                    for (String id : f[4].split(",")) {
                        per.computeIfAbsent(id, k -> new ArrayList<>()).add(new int[] {w, keeps});
                        db.titlesOf.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(f[1].toUpperCase(Locale.ROOT));
                    }
                    break;
                default: break;
            }
        }
        return db;
    }

    /** Where the table comes from (AmiiboAPI, its commit), for About */
    public String source() { return source; }
    public int size() { return byId.size(); }

    /** Every amiibo the table knows, by name */
    public List<Amiibo> all() {
        List<Amiibo> l = new ArrayList<>(byId.values());
        l.sort(BY_NAME);
        return l;
    }

    public static final Comparator<Amiibo> BY_NAME = Comparator.comparing((Amiibo a) -> a.title().toLowerCase(Locale.ROOT)).thenComparing(a -> a.id);

    /** The amiibo of an ID (16 hex digits); one made from the ID when the table does not know it */
    public Amiibo get(String id) {
        String k = id.toLowerCase(Locale.ROOT);
        Amiibo a = byId.get(k);
        return a != null ? a : new Amiibo(k, "amiibo " + k.substring(0, 8) + " " + k.substring(8), false);
    }

    /** Whether the table knows a use of any amiibo in this game */
    public boolean usedBy(String titleId) { return titleId != null && uses.containsKey(titleId.toUpperCase(Locale.ROOT)); }

    /** The amiibo that do something in this game, by name */
    public List<Amiibo> fitting(String titleId) {
        Map<String, List<int[]>> per = titleId == null ? null : uses.get(titleId.toUpperCase(Locale.ROOT));
        if (per == null) return Collections.emptyList();
        List<Amiibo> l = new ArrayList<>();
        for (String id : per.keySet()) l.add(get(id));
        l.sort(BY_NAME);
        return l;
    }

    /** What an amiibo does in this game, each use once; empty when the table knows none */
    public List<String> usesIn(String titleId, String id) {
        Map<String, List<int[]>> per = titleId == null ? null : uses.get(titleId.toUpperCase(Locale.ROOT));
        List<int[]> u = per == null ? null : per.get(id.toLowerCase(Locale.ROOT));
        if (u == null) return Collections.emptyList();
        Set<String> out = new LinkedHashSet<>();
        for (int[] x : u) if (x[0] < words.size()) out.add(words.get(x[0]));
        return new ArrayList<>(out);
    }

    /** Whether this game keeps data on the amiibo, as the table says */
    public boolean keepsData(String titleId, String id) {
        Map<String, List<int[]>> per = titleId == null ? null : uses.get(titleId.toUpperCase(Locale.ROOT));
        List<int[]> u = per == null ? null : per.get(id.toLowerCase(Locale.ROOT));
        if (u != null) for (int[] x : u) if (x[1] == 1) return true;
        return false;
    }

    /** The Switch games that use this amiibo (title IDs) */
    public List<String> titles(String id) {
        Set<String> s = titlesOf.get(id.toLowerCase(Locale.ROOT));
        return s == null ? Collections.emptyList() : new ArrayList<>(s);
    }

    /** A game's name as AmiiboAPI writes it, null when it has none */
    public String gameName(String titleId) { return titleId == null ? null : games.get(titleId.toUpperCase(Locale.ROOT)); }
    public String series(Amiibo a) { return series.get(a.seriesCode()); }
    public String type(Amiibo a) { return types.get(a.typeCode()); }
    public String gameSeries(Amiibo a) { return gameSeries.get(a.gameSeriesCode()); }

    /** "Link - Archer" as "Link (Archer)"; a name without " - " as it is */
    public static String display(String name) {
        int i = name.indexOf(" - ");
        return i < 0 ? name : name.substring(0, i) + " (" + name.substring(i + 3) + ")";
    }

    /** The character's name (before " - "), at most 10 characters, as a figure's nickname holds it */
    public static String nick(String name) {
        int i = name.indexOf(" - ");
        String s = (i < 0 ? name : name.substring(0, i)).trim();
        return s.codePointCount(0, s.length()) <= 10 ? s : s.substring(0, s.offsetByCodePoints(0, 10)).trim();
    }

    /** An ID as the table writes it (16 lower-case hex digits), or null when the text is none */
    public static String id(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("0x")) t = t.substring(2);
        return t.matches("[0-9a-f]{16}") ? t : null;
    }
}
