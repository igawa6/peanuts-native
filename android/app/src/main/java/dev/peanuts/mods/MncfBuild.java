package dev.peanuts.mods;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.davdxpx.mistel.mncf.Atmosphere;
import io.github.davdxpx.mistel.mncf.AtmosphereException;
import io.github.davdxpx.mistel.mncf.Build;
import io.github.davdxpx.mistel.mncf.CheatResult;
import io.github.davdxpx.mistel.mncf.CodeModule;
import io.github.davdxpx.mistel.mncf.Converter;
import io.github.davdxpx.mistel.mncf.Evaluation;
import io.github.davdxpx.mistel.mncf.Evaluator;
import io.github.davdxpx.mistel.mncf.Mncf;
import io.github.davdxpx.mistel.mncf.MncfException;
import io.github.davdxpx.mistel.mncf.MncfFile;
import io.github.davdxpx.mistel.mncf.Module;
import io.github.davdxpx.mistel.mncf.Runtime;
import io.github.davdxpx.mistel.mncf.SignatureResult;
import io.github.davdxpx.mistel.mncf.TrustList;
import io.github.davdxpx.mistel.mncf.ValueSpec;

/**
 * One .mncf cheat file made ready for the build of the game that runs (Mistel's spec, sections 5, 6, 11.2): its cheats' states and reasons, and the Atmosphère
 * code the host's cheat engine runs for them (Part A and B: the host reads one merged Atmosphère file). No Android classes: CheatLoaderJvmTest runs it.
 * <ol>
 * <li>The file is read with Mistel's library (Mncf.load), its signature checked against the keys the app trusts ({@link #TRUST}: none yet).</li>
 * <li>The build: the file's build whose main module is the one that runs, else a build of the running module's ID is added (cheats that find their place by a
 *     pattern still work then; per-build offsets and raw code of other builds do not).</li>
 * <li>Each pattern is searched once in the game's own main (MncfMain) and its place written in as a fixed offset: the file "pinned" to this build. The pinned
 *     file, an .mncf itself, is kept with its states beside the source (build/&lt;BID&gt;.mncf and .tsv), so a later start or the in-game tab reads it without
 *     searching main again (section 6.3 allows it: a runtime MAY keep the result for later runs of the same build).</li>
 * <li>Mistel's evaluator gives each cheat its state and reason for this runtime: guest reads and writes, code bytes, buttons; no display (monitors) and no
 *     code hooks (overrides become a stub patch). The parts of the core Atmosphère code cannot say ({@link #UNSUPPORTED}: toggle buttons, run once or for a
 *     time, several conditions, ...) are refused with runtime:unsupported:&lt;part&gt; until Peanuts runs Mistel's pass engine itself, and a patch or a stub
 *     over a place Peanuts' rewriter changed with runtime:rewritten (section 11.3).</li>
 * <li>The cheats that can run become Atmosphère code (Mistel's Converter.toAtmosphere): their raw code for this build where the evaluator uses it, else code
 *     made from their operations.</li>
 * </ol>
 */
public final class MncfBuild {
    private MncfBuild() {}

    /** The keys Peanuts trusts: Mistel's, once it has one (Mistel's docs/keys.toml is empty so far). An unsigned file loads as any other, without a mark */
    public static final TrustList TRUST = new TrustList();

    /** The pinned files and their states: &lt;source&gt;/build/&lt;BID&gt;.mncf and &lt;BID&gt;.tsv; CACHE names the layout of the .tsv */
    static final String DIR = "build", CACHE = "mncf-build/1";

    /** The capabilities of Peanuts' cheat engine (section 11.1): no display (the monitors wait for the pass engine), no code hooks (stubs are patches) */
    static final List<String> CAPABILITIES = Collections.unmodifiableList(java.util.Arrays.asList("guest-read", "guest-write", "code-bytes", "buttons"));

    // Bases for the evaluator: the host's are not known before the game runs, and nothing here reads guest memory, so any that keep the places apart do
    static final long MAIN_BASE = 0x8000000L, REGION_SIZE = 1L << 40;

    /** What the game is, for a file made ready */
    public static final class Game {
        public final String titleId, build, version;   // build: the running main's build ID, 16 hex digits upper case; version: its name ("2.0.2"), null unknown
        public final File pack;                         // the pack: the game's own main (MncfMain), null for none
        public Game(String titleId, String build, String version, File pack) {
            this.titleId = titleId; this.build = build == null ? null : build.toUpperCase(Locale.ROOT); this.version = version; this.pack = pack;
        }
    }

    /** One cheat of the file for this build */
    public static final class Cheat {
        public final int index;
        public final String id, name, group, about, run;
        public final List<String> tags, requires, excludes;
        public final String radio;
        public final boolean master, isDefault;
        public final String reason;           // the spec's reason it is refused here (Appendix B, or runtime:<why>), null when it can run
        public final List<String> lines;      // its Atmosphère code for this build; empty when refused
        public final ValueSpec value;         // its one whole-number value the player sets (the in-game number field), else null
        public final String valueKey;         // "<cheat id>.<value id>"
        Cheat(int index, io.github.davdxpx.mistel.mncf.Cheat c, String reason, List<String> lines, ValueSpec value) {
            this.index = index; id = c.id(); name = c.name() != null ? c.name() : c.id(); group = c.group() == null ? "" : c.group(); about = c.about(); run = c.run();   // a refused cheat may have no name read: its id
            tags = c.tags(); requires = c.requires(); excludes = c.excludes(); radio = c.radio(); master = c.isMaster(); isDefault = c.isDefault();
            this.reason = reason; this.lines = lines; this.value = value; valueKey = value == null ? null : c.id() + "." + value.id();
        }
        public boolean refused() { return reason != null; }
    }

    /** The file made ready: its cheats, or why it cannot be used at all */
    public static final class Ready {
        public final String error;                 // Appendix D's code when the file cannot be used ("toml", "schema:cheat", ...), else null
        public final MncfFile file;                // the file as stored (its signature, authors, origin)
        public final String signature, signer;     // the signature's result (verified, unsigned, ...) and who signed it, by the trust list's name, null unless verified
        public final String buildKey;              // the file's build for the running one, null when the file has none and one was added
        public final List<Cheat> cheats;
        final String pinned;                       // the pinned file's text
        final String key;                          // the build key in the pinned file
        final Map<String, Boolean> raws;           // which cheats run their raw code (the evaluator's "uses")
        Ready(String error, MncfFile file, String signature, String signer, String buildKey, List<Cheat> cheats, String pinned, String key, Map<String, Boolean> raws) {
            this.error = error; this.file = file; this.signature = signature; this.signer = signer; this.buildKey = buildKey; this.cheats = cheats; this.pinned = pinned; this.key = key;
            this.raws = raws;
        }
        public boolean verified() { return SignatureResult.VERIFIED.equals(signature); }

        boolean usesRaw(Cheat c) { return Boolean.TRUE.equals(raws.get(c.id)); }

        /** The cheat's Atmosphère code with v as its value (the in-game number field); null when v does not fit or the code cannot be made */
        public List<String> lines(Cheat c, long v) {
            if (c.value == null || pinned == null || key == null) return null;
            Map<String, Object> values = new HashMap<>();
            values.put(c.valueKey, v);
            try {
                return lower(pinned, key, values, Collections.singletonMap(c.id, Boolean.FALSE)).get(c.index);
            } catch (MncfException | RuntimeException e) {
                return null;
            }
        }
    }

    // ---- Reading ----

    static String sha256(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The file's build whose main module is the running one (section 5: the shorter ID a prefix of the longer, without case), null when none is */
    static String buildKey(MncfFile f, String build16) {
        if (build16 == null) return null;
        for (Build b : f.builds().values()) {
            String main = b.modules().get("main");
            if (main == null) continue;
            String a = main.toUpperCase(Locale.ROOT), r = build16.toUpperCase(Locale.ROOT);
            if (a.startsWith(r) || r.startsWith(a)) return b.key();
        }
        return null;
    }

    /** The build IDs of main the file names (16 hex digits, upper case), in its order */
    public static List<String> builds(MncfFile f) {
        List<String> out = new ArrayList<>();
        for (Build b : f.builds().values()) {
            String main = b.modules().get("main");
            if (main != null && main.length() >= 16 && !out.contains(main.substring(0, 16).toUpperCase(Locale.ROOT))) out.add(main.substring(0, 16).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** The version name of one of the file's builds by its main's build ID, null when it has none */
    public static String version(MncfFile f, String build16) {
        String k = buildKey(f, build16);
        Build b = k == null ? null : f.builds().get(k);
        if (b == null) return null;
        String v = b.version() != null && !b.version().isEmpty() ? b.version() : k, main = b.modules().get("main");   // the library gives the key when the file names no version
        return main != null && main.equalsIgnoreCase(v) ? null : v;   // a build named by its build ID has no version name
    }

    /**
     * A source file made ready for the game's build: from its kept pinned file when that is of these very bytes, else made now (and kept). One made while the
     * pack had no main yet (a game not built yet) is kept too, so the page does not look it up again and again, and made again once the pack has its main
     */
    public static Ready of(File source, Game g) throws IOException {
        byte[] bytes = Files.readAllBytes(source.toPath());
        File dir = new File(source.getParentFile(), DIR);
        String sha = sha256(bytes);
        Ready kept = g.build == null ? null : kept(dir, g.build, sha, bytes, hasMain(g));
        if (kept != null) return kept;
        CodeModule main = g.pack == null ? null : MncfMain.of(g.pack);
        List<CodeModule.Run> changed = g.pack == null ? Collections.<CodeModule.Run>emptyList() : MncfMain.changed(g.pack);
        Ready r = make(bytes, g, main, changed);
        if (r.error == null && g.build != null) keep(dir, g.build, sha, r, main != null);
        return r;
    }

    /** A source file's kept pinned file for the game's build, when it is of these very bytes; null when it was not made yet (then {@link #of} makes it) */
    public static Ready kept(File source, Game g) throws IOException {
        if (g.build == null) return null;
        byte[] bytes = Files.readAllBytes(source.toPath());
        return kept(new File(source.getParentFile(), DIR), g.build, sha256(bytes), bytes, hasMain(g));
    }

    /** Whether the game's pack has its main now (MncfMain.has) */
    static boolean hasMain(Game g) { return g.pack != null && MncfMain.has(g.pack); }

    /** The bytes made ready (main: the game's own main, null when there is none; changed: the runs the rewriter changed) */
    public static Ready make(byte[] bytes, Game g, CodeModule main, List<CodeModule.Run> changed) {
        MncfFile f;
        try {
            f = Mncf.load(bytes, TRUST);
        } catch (MncfException e) {
            return new Ready(e.code(), null, null, null, null, Collections.<Cheat>emptyList(), null, null, Collections.<String, Boolean>emptyMap());
        }
        SignatureResult sig = f.signature();
        String signer = SignatureResult.VERIFIED.equals(sig.result()) && !sig.signers().isEmpty() ? sig.signers().get(0) : null;
        String fileKey = buildKey(f, g.build);
        Map<String, Object> doc = copy(f.doc());
        String key = fileKey;
        if (key == null && g.build != null) key = addBuild(doc, g.build, g.version);
        Module module = main == null ? Module.builder(g.build == null ? "0" : g.build).base(MAIN_BASE).size(REGION_SIZE).build() : main.toModule(MAIN_BASE);   // no pack: places are not checked against an image
        if (main != null) pin(doc, module);
        String pinned = io.github.davdxpx.mistel.mncf.Writer.dumps(doc);
        MncfFile p;
        try {
            p = Mncf.load(pinned.getBytes(StandardCharsets.UTF_8));
        } catch (MncfException e) {   // the pinned file is the library's own writing of a file it read: not expected
            return new Ready("pinned:" + e.code(), f, sig.result(), signer, fileKey, Collections.<Cheat>emptyList(), null, null, Collections.<String, Boolean>emptyMap());
        }
        Runtime rt = runtime(g, module, changed);
        Evaluation ev = Evaluator.evaluate(p, rt);
        boolean bad = SignatureResult.INVALID.equals(sig.result()) || SignatureResult.REVOKED.equals(sig.result());
        Map<String, String> reasons = new LinkedHashMap<>();
        Map<String, Boolean> raw = new HashMap<>();
        for (io.github.davdxpx.mistel.mncf.Cheat c : p.cheats()) {
            CheatResult r = ev.cheat(c.id());
            String why = bad ? "signature" : r == null ? "invalid" : CheatResult.REFUSED.equals(r.state()) ? r.reason() : null;
            if (main == null && ("pattern-not-found".equals(why) || "expect-mismatch".equals(why) || "outside".equals(why))) why = "runtime:no-main";   // no pack yet: nothing to search or compare
            reasons.put(c.id(), why);
            raw.put(c.id(), r != null && "raw".equals(r.uses()));
        }
        Map<Integer, List<String>> lines = Collections.emptyMap();
        Map<String, String> skipped = new HashMap<>();
        if (key != null) {
            Map<String, Boolean> want = new LinkedHashMap<>();
            for (io.github.davdxpx.mistel.mncf.Cheat c : p.cheats()) if (reasons.get(c.id()) == null) want.put(c.id(), raw.get(c.id()));
            try {
                lines = lower(pinned, key, null, Collections.<String, Object>emptyMap(), want, skipped);
            } catch (MncfException | RuntimeException e) {
                for (String id : want.keySet()) skipped.put(id, "");
            }
        }
        List<Cheat> cheats = new ArrayList<>();
        for (io.github.davdxpx.mistel.mncf.Cheat c : p.cheats()) {
            String why = reasons.get(c.id());
            List<String> l = lines.get(c.index());
            if (why == null && key == null) why = "build-unknown";
            if (why == null && l == null) why = runtimeReason(skipped.get(c.id()), c);
            cheats.add(new Cheat(c.index(), c, why, why == null ? l : Collections.<String>emptyList(), Boolean.TRUE.equals(raw.get(c.id())) ? null : number(c)));
        }
        return new Ready(null, f, sig.result(), signer, fileKey, Collections.unmodifiableList(cheats), pinned, key, raw);
    }

    /**
     * The parts of the core (section 11.1, Mncf.FEATURES) Peanuts' cheat engine does not have yet, because the host runs Atmosphère code and these have no
     * Atmosphère form: a cheat that uses one is refused with runtime:unsupported:&lt;part&gt; until Peanuts runs Mistel's pass engine itself. A single condition,
     * hold and press buttons and an override with its expect do have one (a memory condition, a key block, a stub patch). Monitors are refused for the display
     * capability Peanuts does not offer (capability-missing:display), so run.monitor is not listed
     */
    static final List<String> UNSUPPORTED = Collections.unmodifiableList(java.util.Arrays.asList("run.once", "run.timed", "toggle", "when.many", "match.any",
            "trigger.enter", "interval"));

    /**
     * The runtime the evaluator checks the cheats against: Peanuts' cheat engine on the Switch, the game that runs, its main (with its image for patterns), the
     * parts it lacks, and the runs of main its rewriter changed (a patch or a stub over one is refused with runtime:rewritten, section 11.3)
     */
    static Runtime runtime(Game g, Module main, List<CodeModule.Run> changed) {
        Runtime.Builder b = Runtime.builder("switch").module("main", main).capabilities(CAPABILITIES).extensions(Mncf.KNOWN_EXTENSIONS).rawSyntaxes("atmosphere")
                .unsupported(UNSUPPORTED);
        if (g.titleId != null) b.gameId(g.titleId);
        long base = 1L << 40;
        for (String r : new String[] {"heap", "alias", "aslr"}) { b.region(r, base, REGION_SIZE); base += REGION_SIZE << 1; }
        for (CodeModule.Run run : changed) b.rewritten("main", run.offset(), run.length());
        return b.build();
    }

    /** A cheat's one whole-number value the player sets: one value, an integer type, no choices and no flags (those wait for their own controls) */
    static ValueSpec number(io.github.davdxpx.mistel.mncf.Cheat c) {
        if (c.values().size() != 1) return null;
        ValueSpec v = c.values().values().iterator().next();
        if (!v.type().matches("[us](8|16|32|64)") || v.choices() != null || v.flags() != null) return null;
        if (!(v.defaultValue() instanceof Long)) return null;   // a default of 2^63 or more (a BigInteger) is past the field's numbers
        return v;
    }

    /**
     * The reason of a cheat the evaluator lets run but Atmosphère code cannot say, from the converter's stable reason (Converter.Export.reasons(), spec
     * section 15): the reader's own reasons as they are, runtime:unsupported:&lt;part&gt; for a part of the core that has no Atmosphère form in this shape (a
     * condition on a pointer path, an action that also writes or holds; c names which run or which condition), capability-missing:display for a monitor,
     * runtime:no-main for a pattern with no main to search, else runtime:&lt;what&gt;
     */
    static String runtimeReason(String code, io.github.davdxpx.mistel.mncf.Cheat c) {
        String k = code == null ? "" : code;
        switch (k) {
            case "atmosphere:run": return "runtime:unsupported:run." + (c != null && c.run().equals("timed") ? "timed" : "once");
            case "atmosphere:monitor": return "capability-missing:display";
            case "atmosphere:toggle": return "runtime:unsupported:toggle";
            case "atmosphere:condition": {
                if (c != null) for (String part : Evaluator.uses(c)) if (part.equals("when.many") || part.equals("match.any") || part.equals("trigger.enter") || part.equals("interval")) return "runtime:unsupported:" + part;
                return "runtime:unsupported:when";
            }
            case "atmosphere:action": return "runtime:unsupported:run.action";
            case "atmosphere:override": return "runtime:unsupported:override";
            case "atmosphere:no-module": return "runtime:no-main";
            case "atmosphere:empty": return "invalid";
            case "": return "runtime:code";
            default: return k.startsWith("atmosphere:") ? "runtime:" + k.substring("atmosphere:".length()) : k;   // module, region, address, too-long; the reader's reasons
        }
    }

    // ---- The pinned file ----

    @SuppressWarnings("unchecked")
    static Object copyAny(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) out.put(e.getKey(), copyAny(e.getValue()));
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<Object>) v) out.add(copyAny(x));
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copy(Map<String, Object> doc) { return (Map<String, Object>) copyAny(doc); }

    /** A build of the running main added to the document; its key: the build ID, or that with a number when the file has that key already */
    @SuppressWarnings("unchecked")
    static String addBuild(Map<String, Object> doc, String build16, String version) {
        Map<String, Object> builds = (Map<String, Object>) doc.get("builds");
        if (builds == null) {
            builds = new LinkedHashMap<>();
            Map<String, Object> d = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : doc.entrySet()) {   // builds after game, as the layout writes them; the order of a map the writer orders itself
                d.put(e.getKey(), e.getValue());
            }
            d.put("builds", builds);
            doc.clear();
            doc.putAll(d);
        }
        String key = build16;
        for (int k = 2; builds.containsKey(key); k++) key = build16 + "-" + k;
        Map<String, Object> b = new LinkedHashMap<>();
        if (version != null && !version.isEmpty() && !key.equals(version)) b.put("version", version);
        Map<String, Object> modules = new LinkedHashMap<>();
        modules.put("main", build16);
        b.put("modules", modules);
        builds.put(key, b);
        return key;
    }

    static long longOf(Object o) {
        if (o == null) return 0;
        if (o instanceof Long || o instanceof Integer) return ((Number) o).longValue();
        if (o instanceof BigInteger) return ((BigInteger) o).longValue();
        String s = o.toString().trim();
        boolean neg = s.startsWith("-");
        if (neg) s = s.substring(1);
        long v = s.startsWith("0x") || s.startsWith("0X") ? Long.parseUnsignedLong(s.substring(2), 16) : Long.parseLong(s);
        return neg ? -v : v;
    }

    /** Each location of main that finds its place by a pattern, given that place as a fixed offset where the pattern matches exactly once */
    @SuppressWarnings("unchecked")
    static void pin(Map<String, Object> doc, Module main) {
        Object cheats = doc.get("cheat");
        if (!(cheats instanceof List)) return;
        Map<String, List<Long>> found = new HashMap<>();
        for (Object c : (List<Object>) cheats) {
            if (!(c instanceof Map)) continue;
            Map<String, Object> cheat = (Map<String, Object>) c;
            for (String kind : new String[] {"patch", "write", "override", "read", "when"}) {
                Object ops = cheat.get(kind);
                if (!(ops instanceof List)) continue;
                for (Object o : (List<Object>) ops) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> op = (Map<String, Object>) o;
                    pinAt(op, kind.equals("override") ? "function" : "at", main, found);
                    if (kind.equals("when") && op.get("value") instanceof Map) pinAt((Map<String, Object>) op.get("value"), "at", main, found);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void pinAt(Map<String, Object> op, String name, Module main, Map<String, List<Long>> found) {
        Object a = op.get(name);
        if (!(a instanceof Map)) return;
        Map<String, Object> at = (Map<String, Object>) a;
        if (!(at.get("find") instanceof String) || !"main".equals(at.get("module"))) return;
        String find = (String) at.get("find"), section = at.get("section") instanceof String ? (String) at.get("section") : null;
        List<Long> m;
        try {
            m = found.computeIfAbsent(find + "\n" + section, k -> Evaluator.findAll(main, find, section, 2));
        } catch (IllegalArgumentException e) {
            return;   // not a pattern: the evaluator refuses the cheat for it
        }
        if (m.size() != 1) return;   // not found or more than once: left as it is, the evaluator says which
        long off;
        try {
            off = m.get(0) + longOf(at.get("offset"));
        } catch (NumberFormatException e) {
            return;
        }
        if (off < 0) return;   // before main: the evaluator's outside
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("module", "main");
        to.put("offset", off);
        if (at.containsKey("path")) to.put("path", at.get("path"));
        op.put(name, to);
    }

    // ---- Atmosphère code ----

    /** lower without a list of what it left out */
    static Map<Integer, List<String>> lower(String pinned, String key, Map<String, Object> values, Map<String, Boolean> want) throws MncfException {
        return lower(pinned, key, null, values, want, new HashMap<String, String>());
    }

    /**
     * The Atmosphère code of the cheats in want (id → whether it runs its raw code) for the build key (Converter.toAtmosphere), with the values set
     * (cheat.value → number: the player's values, checked as the reader checks them; one a cheat cannot take leaves that cheat to its raw code for the build,
     * or out); by the cheat's index. main: the build's main for the patterns and for checking each patch against expect, null for a file whose places are
     * fixed already (the pinned file); with it a build the file does not name is written from the patterns. skipped gets the ones left out with the
     * converter's stable reason (Export.reasons(), spec section 15; runtimeReason gives Peanuts' reason for it)
     */
    static Map<Integer, List<String>> lower(String text, String key, CodeModule main, Map<String, Object> values, Map<String, Boolean> want, Map<String, String> skipped) throws MncfException {
        io.github.davdxpx.mistel.mncf.Choices chosen = choices(values);
        Map<Integer, List<String>> out = new HashMap<>();
        for (Map<String, Boolean> part : parts(want)) {
            MncfFile d = renamed(text, part.keySet());
            out.putAll(pick(d, Converter.toAtmosphere(d, key, main, "raw", chosen, cores(part)), skipped));
        }
        return out;
    }

    /**
     * Every build a file names as Atmosphère code (Converter.toAtmosphereAll), as lower does for one: build key → the cheats' code by index. mains: the main of
     * a build, by key, where Peanuts has it (patterns found, patches checked against expect); a build without one writes what is placed already and leaves
     * a pattern out (atmosphere:no-module). reasons gets, by build key, the cheats left out with the converter's stable reason; a build that names no main
     * module has no code and every cheat's reason is the build's (atmosphere:module)
     */
    static Map<String, Map<Integer, List<String>>> lowerAll(String text, Map<String, CodeModule> mains, Map<String, Object> values, Map<String, Boolean> want,
            Map<String, Map<String, String>> reasons) throws MncfException {
        io.github.davdxpx.mistel.mncf.Choices chosen = choices(values);
        Map<String, Map<Integer, List<String>>> out = new LinkedHashMap<>();
        for (Map<String, Boolean> part : parts(want)) {
            MncfFile d = renamed(text, part.keySet());
            for (Converter.BuildExport b : Converter.toAtmosphereAll(d, mains, "raw", chosen, cores(part))) {
                Map<String, String> why = reasons.computeIfAbsent(b.build(), k -> new LinkedHashMap<String, String>());
                if (b.export() == null) {
                    for (String id : part.keySet()) why.put(id, b.reason());
                    continue;
                }
                out.computeIfAbsent(b.build(), k -> new HashMap<Integer, List<String>>()).putAll(pick(d, b.export(), why));
            }
        }
        return out;
    }

    /**
     * At most this many cheats are lowered together: the converter keeps Atmosphère's limit of 127 entries a file (spec section 15), and Peanuts' host
     * takes more (CHEAT_MAX in dm/host/hle_cheats.c), so a source with more is lowered in parts. MncfExport keeps the limit for the whole ZIP file
     */
    static final int LOWER_PART = 127;

    /** want in parts of at most LOWER_PART cheats, in its order */
    private static List<Map<String, Boolean>> parts(Map<String, Boolean> want) {
        List<Map<String, Boolean>> out = new ArrayList<>();
        Map<String, Boolean> part = null;
        for (Map.Entry<String, Boolean> e : want.entrySet()) {
            if (part == null || part.size() >= LOWER_PART) out.add(part = new LinkedHashMap<>());
            part.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The cheats of want that are made from their operations first (Converter's preferCheats; the others run their own raw code for the build first) */
    private static Map<String, String> cores(Map<String, Boolean> want) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Boolean> e : want.entrySet()) if (!Boolean.TRUE.equals(e.getValue())) out.put(e.getKey(), "core");
        return out;
    }

    /** Each cheat's code from the converter's export, by its index in the file (renamed's n&lt;index&gt;); skipped gets the others with their stable reason */
    private static Map<Integer, List<String>> pick(MncfFile d, Converter.Export e, Map<String, String> skipped) {
        Map<String, List<String>> lines = entries(e);
        Map<Integer, List<String>> out = new HashMap<>();
        for (io.github.davdxpx.mistel.mncf.Cheat c : d.cheats()) {
            List<String> l = lines.get(c.name());
            if (l == null || l.isEmpty()) { skipped.put(c.id(), e.reasons().getOrDefault(c.id(), "atmosphere:empty")); continue; }
            out.put(Integer.parseInt(c.name().substring(1)), l);
        }
        return out;
    }

    /**
     * A file's text with only the cheats in keep, each named by its index in the file (n&lt;index&gt;), so the converter's entries are found again by name
     * whatever a cheat's own name holds. Without what would join entries or tie cheats together, as neither matters for one cheat's code: groups (the
     * converter's group entries), master (the converter puts every master into one entry; Peanuts' host and MncfExport.atmosphere keep them apart),
     * requires, excludes and radio (they name cheats that may be left out here), presets and translations
     */
    @SuppressWarnings("unchecked")
    private static MncfFile renamed(String text, Set<String> keep) throws MncfException {
        MncfFile p = Mncf.load(text.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> doc = copy(p.doc());
        List<Object> cheats = (List<Object>) doc.get("cheat"), kept = new ArrayList<>();
        int i = 0;
        for (Object o : cheats) {
            Map<String, Object> c = (Map<String, Object>) o;
            int at = i++;
            if (!keep.contains(c.get("id"))) continue;
            c.put("name", "n" + at);
            for (String k : new String[] {"group", "master", "requires", "excludes", "radio"}) c.remove(k);
            kept.add(c);
        }
        doc.put("cheat", kept);
        doc.remove("preset");   // a preset names values; the converter needs none of them
        doc.remove("i18n");
        return Mncf.load(io.github.davdxpx.mistel.mncf.Writer.dumps(doc).getBytes(StandardCharsets.UTF_8));
    }

    /** The values set, as the player's choices */
    private static io.github.davdxpx.mistel.mncf.Choices choices(Map<String, Object> values) {
        io.github.davdxpx.mistel.mncf.Choices.Builder cb = io.github.davdxpx.mistel.mncf.Choices.builder();
        for (Map.Entry<String, Object> v : values.entrySet()) cb.value(v.getKey(), v.getValue());
        return cb.build();
    }

    private static Map<String, List<String>> entries(Converter.Export e) {
        Map<String, List<String>> out = new HashMap<>();
        if (e == null) return out;
        try {
            for (Atmosphere.Entry x : Atmosphere.parse(e.text())) out.put(x.name(), x.lines());
        } catch (AtmosphereException ex) {
            return out;   // the converter's own text: not expected
        }
        return out;
    }

    // ---- Keeping it for the build ----

    /**
     * The kept pinned file of this build, when it was made from these very bytes, and with the game's main where the pack has it now (mainNow); null otherwise
     * (then it is made again)
     */
    static Ready kept(File dir, String build16, String sha, byte[] bytes, boolean mainNow) {
        File tsv = new File(dir, build16 + ".tsv"), mncf = new File(dir, build16 + ".mncf");
        if (!tsv.isFile() || !mncf.isFile()) return null;
        try {
            String[] lines = new String(Files.readAllBytes(tsv.toPath()), StandardCharsets.UTF_8).split("\n");
            String[] h = lines[0].split("\t", -1);
            if (h.length < 6 || !h[0].equals("# " + CACHE) || !h[1].equals(sha)) return null;
            if (mainNow && h.length > 6 && h[6].equals(NO_MAIN)) return null;   // made before the pack had its main: its patterns were not searched
            MncfFile f = Mncf.load(bytes, TRUST);
            String pinned = new String(Files.readAllBytes(mncf.toPath()), StandardCharsets.UTF_8);
            MncfFile p = Mncf.load(pinned.getBytes(StandardCharsets.UTF_8));
            Map<String, String[]> rows = new HashMap<>();
            for (int i = 1; i < lines.length; i++) { String[] r = lines[i].split("\t", -1); if (r.length >= 3) rows.put(r[0], r); }
            String key = h[2].isEmpty() ? null : h[2];
            Map<String, Boolean> want = new LinkedHashMap<>();
            for (io.github.davdxpx.mistel.mncf.Cheat c : p.cheats()) {
                String[] r = rows.get(c.id());
                if (r == null) return null;   // a kept list of another file: made again
                if (r[1].isEmpty()) want.put(c.id(), "raw".equals(r[2]));
            }
            Map<String, String> skipped = new HashMap<>();
            Map<Integer, List<String>> lines2 = key == null ? Collections.<Integer, List<String>>emptyMap() : lower(pinned, key, null, Collections.<String, Object>emptyMap(), want, skipped);
            List<Cheat> cheats = new ArrayList<>();
            Map<String, Boolean> raws = new HashMap<>();
            for (io.github.davdxpx.mistel.mncf.Cheat c : p.cheats()) {
                String[] r = rows.get(c.id());
                String why = r[1].isEmpty() ? null : r[1];
                List<String> l = lines2.get(c.index());
                if (why == null && l == null) why = runtimeReason(skipped.get(c.id()), c);
                boolean raw = "raw".equals(r[2]);
                cheats.add(new Cheat(c.index(), c, why, why == null ? l : Collections.<String>emptyList(), raw ? null : number(c)));
                raws.put(c.id(), raw);
            }
            return new Ready(null, f, f.signature().result(), h[4].isEmpty() ? null : h[4], h[3].isEmpty() ? null : h[3], Collections.unmodifiableList(cheats), pinned, key, raws);
        } catch (IOException | MncfException | RuntimeException e) {
            return null;
        }
    }

    /** Made without the game's main (the .tsv's last header field; "main" when it was read) */
    static final String NO_MAIN = "no-main";

    /**
     * The pinned file and its states kept for the build: "# mncf-build/1 \t sha \t key \t file's build key \t signer \t signature \t main or no-main", then
     * id \t reason \t uses
     */
    static void keep(File dir, String build16, String sha, Ready r, boolean withMain) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            StringBuilder sb = new StringBuilder("# ").append(CACHE).append('\t').append(sha).append('\t').append(r.key == null ? "" : r.key).append('\t')
                    .append(r.buildKey == null ? "" : r.buildKey).append('\t').append(r.signer == null ? "" : r.signer.replaceAll("[\t\r\n]", " ")).append('\t').append(r.signature).append('\t').append(withMain ? "main" : NO_MAIN).append('\n');
            for (Cheat c : r.cheats) sb.append(c.id).append('\t').append(c.reason == null ? "" : c.reason).append('\t').append(r.usesRaw(c) ? "raw" : "core").append('\n');
            CheatSources.write(new File(dir, build16 + ".mncf"), r.pinned);
            CheatSources.write(new File(dir, build16 + ".tsv"), sb.toString());
        } catch (IOException | RuntimeException e) {
            // not kept: the next reader makes it again
        }
    }
}
