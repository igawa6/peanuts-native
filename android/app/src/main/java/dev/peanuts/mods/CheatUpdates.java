package dev.peanuts.mods;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.davdxpx.mistel.mncf.Carry;
import io.github.davdxpx.mistel.mncf.Choices;
import io.github.davdxpx.mistel.mncf.Mncf;
import io.github.davdxpx.mistel.mncf.MncfException;
import io.github.davdxpx.mistel.mncf.MncfFile;
import io.github.davdxpx.mistel.mncf.Release;
import io.github.davdxpx.mistel.mncf.UpdateCheck;
import io.github.davdxpx.mistel.mncf.Updates;
import io.github.davdxpx.mistel.mncf.ValueSpec;

/**
 * New versions of the player's cheat files (Mistel's spec, section 17). A file that names its newest version ([update]) can bring the player the version its
 * author made for a game update; Peanuts only ever follows the address the author put into a file the player chose, and never offers cheats of its own. No
 * Android classes: CheatLoaderJvmTest runs it.
 * <ul>
 * <li>Asked once per file and host ({@link #allowed}): with a yes, Peanuts looks when the cheats page opens, at most once a day, and when a game update
 *     stops the file's cheats ({@link #due}); without, only when the player taps Check for updates. The request carries nothing about the player
 *     ({@link Https}).</li>
 * <li>A new version is checked as the spec says (Updates.check: the same game and id, a higher version, the same signer) and kept as offer.mncf beside the
 *     source's file until the player installs it ({@link #offer}). Nothing is installed without the player's tap.</li>
 * <li>{@link #install} keeps the file it replaces as previous.mncf, so {@link #back} can go back to it. Everything the player set is kept by cheat id,
 *     following the file's renames: the cheats switched on and the numbers set. The merged file names cheats by their names, so the state goes into
 *     carry.tsv first and {@link #applyPending} puts it under the new names once the new file is made ready for the build.</li>
 * <li>The cheats a new version brings and the ones it fixed carry a mark on the cheats page until the next start ({@link #marks}).</li>
 * </ul>
 */
public final class CheatUpdates {
    private CheatUpdates() {}

    static final String PREVIOUS = "previous.mncf", OFFER = "offer.mncf", CARRY = "carry.tsv";
    // source.tsv keys: the host the player was asked about and the answer, the last look, the version the player went back from, the marks
    static final String HOST = "update_host", OK = "update_ok", CHECKED = "update_checked", LEFT = "update_left", NEW = "marks_new", FIXED = "marks_fixed";
    /** How often Peanuts looks on its own, at most */
    public static final long DAY = 24L * 60 * 60 * 1000;

    // ---- The file and what the player said ----

    /** The file of a source read, null when it cannot be */
    public static MncfFile file(CheatSources.Source s) { return read(s.file()); }

    static MncfFile parse(byte[] data) {
        try {
            return data == null ? null : Mncf.load(data, MncfBuild.TRUST);
        } catch (MncfException | RuntimeException e) {
            return null;
        }
    }

    static MncfFile read(File f) {
        try {
            return f.isFile() ? Mncf.load(Files.readAllBytes(f.toPath()), MncfBuild.TRUST) : null;
        } catch (IOException | MncfException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The host a source's file names for its new versions, null when it names none a runtime may use, or when the file came from a pack: a pack's file gets
     * new versions only with its pack (the spec, section 17.6; {@link #held})
     */
    public static String host(CheatSources.Source s) {
        if (state(s).containsKey(PACK)) return null;
        MncfFile f = file(s);
        return f == null || f.update() == null ? null : f.update().host();
    }

    /** Whether the player said Peanuts may look at the file's host on its own: true, false, or null when they were not asked about this host yet */
    public static Boolean allowed(CheatSources.Source s) {
        String host = host(s);
        if (host == null) return null;
        Map<String, String> st = state(s);
        if (!host.equals(st.get(HOST)) || !st.containsKey(OK)) return null;
        return "1".equals(st.get(OK));
    }

    /** The player's answer for the host the file names now */
    public static void allowed(CheatSources.Source s, boolean yes) throws IOException {
        String host = host(s);
        if (host == null) return;
        Map<String, String> st = state(s);
        st.put(HOST, host);
        st.put(OK, yes ? "1" : "0");
        writeState(s, st);
    }

    /** The game's cheats folder a source is in (&lt;folder&gt;/sources/&lt;id&gt;) */
    public static File folderOf(CheatSources.Source s) { return s.dir.getParentFile().getParentFile(); }

    static Map<String, String> state(CheatSources.Source s) { return CheatSources.readState(new File(s.dir, CheatSources.STATE)); }

    static void writeState(CheatSources.Source s, Map<String, String> st) throws IOException { CheatSources.writeState(new File(s.dir, CheatSources.STATE), st); }

    /** Whether Peanuts looks for a new version of s on its own now: the player allowed it, and the last look is a day ago, or the game was updated (stuck) */
    public static boolean due(CheatSources.Source s, long now, boolean stuck) {
        if (!Boolean.TRUE.equals(allowed(s))) return false;
        long last;
        try { last = Long.parseLong(state(s).getOrDefault(CHECKED, "0")); } catch (NumberFormatException e) { last = 0; }
        return now - last >= (stuck ? DAY / 24 : DAY);
    }

    /**
     * Whether a game update stopped a block's cheats, so a newer version of its file may know the build (the spec's best moment to look): its file names
     * other builds and not this one, or one of its cheats is refused for a place that is not where the file expects it
     */
    public static boolean stuck(CheatSources.Block b, String build) {
        if (b == null || b.parsed == null || b.parsed.mncf == null) return false;
        MncfBuild.Ready r = b.parsed.mncf;
        if (r.file != null && !MncfBuild.builds(r.file).isEmpty() && build != null && !MncfBuild.builds(r.file).contains(build)) return true;
        for (MncfBuild.Cheat c : r.cheats) {
            String why = c.reason;
            if ("build-unknown".equals(why) || "pattern-not-found".equals(why) || "expect-mismatch".equals(why)) return true;
        }
        return false;
    }

    // ---- Looking ----

    /** The request a runtime makes for a new version: the bytes at an address (Https), stood in for by the tests */
    public interface Http {
        byte[] get(String url, int max) throws IOException;
    }

    /**
     * The spec's request (section 17.3): GET of the address as written, the User-Agent every runtime sends, no cookies, no cache, nothing about the player or
     * this device; at most five redirects, each to an https address; no connection to an address of this device or its network. The app sets no cookie
     * handler and no response cache, so HttpURLConnection sends none.
     */
    public static final class Https implements Http {
        @Override public byte[] get(String url, int max) throws IOException {
            String at = url;
            for (int hop = 0; hop <= Updates.MAX_REDIRECTS; hop++) {
                URL u = new URL(at);
                if (!"https".equals(u.getProtocol()) || u.getHost() == null || u.getHost().isEmpty()) throw new IOException("not an https address: " + at);
                for (InetAddress ip : InetAddress.getAllByName(u.getHost())) {
                    if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || ip.isAnyLocalAddress() || ip.isMulticastAddress() || local6(ip))
                        throw new IOException(u.getHost() + " is an address of this device or its network");
                }
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                try {
                    c.setInstanceFollowRedirects(false);
                    c.setUseCaches(false);
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setRequestProperty("User-Agent", Updates.USER_AGENT);
                    int code = c.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = c.getHeaderField("Location");
                        if (loc == null) throw new IOException("a redirect without an address");
                        at = new URL(u, loc).toString();
                        continue;
                    }
                    if (code != 200) throw new IOException("the address answered " + code);
                    try (InputStream in = c.getInputStream()) { return readMax(in, max); }
                } finally {
                    c.disconnect();
                }
            }
            throw new IOException("more than " + Updates.MAX_REDIRECTS + " redirects");
        }

        private static boolean local6(InetAddress ip) {
            byte[] b = ip.getAddress();
            return b.length == 16 && (b[0] & 0xFE) == 0xFC;   // unique local (fc00::/7)
        }
    }

    static byte[] readMax(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        for (int n; (n = in.read(buf)) > 0; ) {
            o.write(buf, 0, n);
            if (o.size() > max) throw new IOException("the answer is larger than a cheat file may be");
        }
        return o.toByteArray();
    }

    /** What a look found: a new version (offer, kept as offer.mncf), none, or why not */
    public static final class Look {
        public final String reason;     // "" for a new version; "same", "older", "left" (the version the player went back from); an Appendix E reason; "unreachable"
        public final String version;    // the version at the address, null when none could be read
        public final String detail;     // for "unreachable": what went wrong, for the log
        Look(String reason, String version, String detail) { this.reason = reason; this.version = version; this.detail = detail; }
        public boolean found() { return reason.isEmpty(); }
    }

    /** Looks for a new version of s at its address now (off the UI thread), keeps a new one as its offer, and notes the time */
    public static Look look(CheatSources.Source s, Http http, long now) {
        MncfFile f = file(s);
        if (f == null || f.update() == null) return new Look("update:none", null, null);
        Map<String, String> st = state(s);
        st.put(CHECKED, String.valueOf(now));
        try { writeState(s, st); } catch (IOException ignored) { }
        byte[] data;
        try {
            data = http.get(f.update().url(), Mncf.MAX_FILE);
        } catch (IOException | RuntimeException e) {
            return new Look("unreachable", null, e.getMessage());
        }
        UpdateCheck check = Updates.check(f, data, MncfBuild.TRUST);
        String v = check.file() == null ? null : check.file().fileVersion();
        if (check.ok()) {
            String left = st.get(LEFT);
            if (left != null && Updates.isVersion(left) && !Updates.above(v, left)) return new Look("left", v, null);
            try {
                CheatSources.write(new File(s.dir, OFFER), data);
            } catch (IOException e) {
                return new Look("unreachable", v, e.getMessage());
            }
            return new Look("", v, null);
        }
        if ("update:version".equals(check.reason()) && check.file() != null) {
            new File(s.dir, OFFER).delete();
            boolean same = Updates.isVersion(v) && Updates.isVersion(f.fileVersion()) && Updates.compare(v, f.fileVersion()) == 0;
            return new Look(same ? "same" : "older", v, null);
        }
        return new Look(check.reason(), v, null);
    }

    /** The new version kept for s, null when there is none (or it is no longer above the one it has) */
    public static byte[] offered(CheatSources.Source s) {
        File o = new File(s.dir, OFFER);
        if (!o.isFile()) return null;
        try {
            byte[] data = Files.readAllBytes(o.toPath());
            MncfFile f = file(s);
            if (f == null || !Updates.check(f, data, MncfBuild.TRUST).ok()) { o.delete(); return null; }
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- What a new version brings ----

    /** What the player set for a source's cheats, by cheat id: the ones switched on and off, and the numbers ("<cheat id>.<value id>") */
    static final class Had {
        final Choices choices;
        final Map<String, String> keys;   // cheat id → its key in the merged file
        Had(Choices choices, Map<String, String> keys) { this.choices = choices; this.keys = keys; }
    }

    /** The state of source id's cheats in the merge m: by cheat id, from the toggles on and the numbers values (CheatValues.read) */
    static Had stateOf(String id, CheatSources.Merged m, MncfFile file, Set<String> on, Map<String, CheatValues.Kept> values, String sourceName) {
        Choices.Builder b = Choices.builder();
        Map<String, String> keys = new LinkedHashMap<>();
        boolean any = false;
        if (m != null) {
            for (CheatSources.Row r : m.rows) {
                if (!r.block.id.equals(id) || r.cheat.mncf == null || r.cheat.master) continue;
                any = true;
                keys.put(r.cheat.mncf.id, r.key);
            }
        }
        if (!any && file != null) {   // not in the merge (no build known, or not made ready for it): by the names the merge gives
            for (io.github.davdxpx.mistel.mncf.Cheat c : file.cheats()) {
                if (c.isMaster()) continue;
                String base = CheatSources.safe(c.name()), own = CheatSources.fit(base, " (" + CheatSources.safe(sourceName) + ")");
                keys.put(c.id(), on.contains(base) || !on.contains(own) ? base : own);
            }
        }
        for (Map.Entry<String, String> e : keys.entrySet()) {
            String cid = e.getKey(), key = e.getValue();
            if (on.contains(key)) b.on(cid); else b.off(cid);
            CheatValues.Kept k = values.get(key.toLowerCase(Locale.ROOT));
            ValueSpec v = valueOf(file, cid);
            if (k != null && k.width == 0 && v != null) b.value(cid + "." + v.id(), k.number);
        }
        return new Had(b.build(), keys);
    }

    /** A cheat's one whole-number value, the one Peanuts lets the player set (MncfBuild.number), null when it has none */
    static ValueSpec valueOf(MncfFile f, String cheatId) {
        io.github.davdxpx.mistel.mncf.Cheat c = f == null ? null : f.cheat(cheatId);
        return c == null ? null : MncfBuild.number(c);
    }

    /** What the update screen shows: the version and where it comes from, the notes since the player's version, the marks, and what the player set that changes */
    public static final class Offer {
        public final MncfFile now, next;
        public final UpdateCheck check;
        public final String host;
        public final List<Release> since;
        public final List<String> added, fixed;       // the names of the cheats new in it, and fixed
        public final List<String> reset;              // the cheats whose number goes back to the file's own (it no longer fits), by name
        public final List<String> gone;               // the cheats the player had set something for that it no longer has, by name
        public final boolean knows;                   // whether it names the build that runs
        Offer(MncfFile now, MncfFile next, UpdateCheck check, String host, List<Release> since, List<String> added, List<String> fixed, List<String> reset, List<String> gone, boolean knows) {
            this.now = now; this.next = next; this.check = check; this.host = host; this.since = since; this.added = added; this.fixed = fixed; this.reset = reset; this.gone = gone; this.knows = knows;
        }
        /** Whether it is a new version as the spec takes one (else it is older, the same, or by another signer: a hand-added file may still be used) */
        public boolean newer() { return check.ok(); }
    }

    /**
     * What data would bring as the next version of source s, whose cheats are in m with the toggles on and the numbers values (m null: not in a merge);
     * build: the game's running build. Null when data is no cheat file at all
     */
    public static Offer offer(CheatSources.Source s, byte[] data, CheatSources.Merged m, Set<String> on, Map<String, CheatValues.Kept> values, String build) {
        MncfFile now = file(s);
        if (now == null) return null;
        UpdateCheck check = Updates.check(now, data, MncfBuild.TRUST);
        MncfFile next = check.file();
        if (next == null) return null;
        String from = now.fileVersion();
        io.github.davdxpx.mistel.mncf.Marks mk = Updates.marks(next, from);
        Had st = stateOf(s.id, m, now, on, values, s.name);
        Carry carry = Updates.carry(next, from, st.choices, null, Collections.<String>emptyList());
        List<String> reset = new ArrayList<>(), gone = new ArrayList<>();
        for (String key : carry.reset().keySet()) reset.add(nameOf(next, key.substring(0, key.indexOf('.'))));
        Set<String> set = new LinkedHashSet<>(st.choices.on());
        for (String k : st.choices.values().keySet()) set.add(k.substring(0, k.indexOf('.')));
        for (String cid : carry.dropped()) if (set.contains(cid)) gone.add(nameOf(now, cid));
        boolean knows = build != null && MncfBuild.builds(next).contains(build);
        String host = now.update() != null ? now.update().host() : next.update() != null ? next.update().host() : null;   // where it came from
        return new Offer(now, next, check, host, Updates.since(next, from), names(next, mk.added()), names(next, mk.fixed()), reset, gone, knows);
    }

    static String nameOf(MncfFile f, String id) {
        io.github.davdxpx.mistel.mncf.Cheat c = f.cheat(id);
        return c == null ? id : c.name();
    }

    static List<String> names(MncfFile f, List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(nameOf(f, id));
        return out;
    }

    /** A change entry's text in the player's language (the file's translation for it, else its own), null when it has none */
    public static String text(MncfFile f, Release r, Locale l) {
        String t = Updates.text(f, r, l.toLanguageTag());
        if (t != null && !t.equals(r.text())) return t;
        t = Updates.text(f, r, l.getLanguage());
        return t != null ? t : r.text();
    }

    // ---- Installing, going back ----

    /** What installing did: the version now, the cheats whose numbers went back to the file's own, the cheats that are gone with what the player set */
    public static final class Done {
        public final String version;
        public final List<String> reset, gone;
        Done(String version, List<String> reset, List<String> gone) { this.version = version; this.reset = reset; this.gone = gone; }
    }

    /**
     * data installed as the new version of source s (folder: the game's cheats folder): its file now kept as previous.mncf, everything the player set kept by
     * cheat id (Updates.carry) in carry.tsv until the new file is in the merge ({@link #applyPending}), the toggles and numbers under the old names taken out
     * of on and values.tsv now, the cheats it brings and fixed marked. on is changed in place: the caller keeps it (Prefs.cheatsOn)
     */
    public static Done install(File folder, CheatSources.Source s, byte[] data, CheatSources.Merged m, Set<String> on) throws IOException {
        MncfFile now = file(s);
        MncfFile next;
        try {
            next = Mncf.load(data, MncfBuild.TRUST);
        } catch (MncfException e) {
            throw new IOException("the new version cannot be read (" + e.code() + ")", e);
        }
        Map<String, CheatValues.Kept> values = CheatValues.read(folder);
        Had st = stateOf(s.id, m, now, on, values, s.name);
        String from = now == null ? null : now.fileVersion();
        Carry carry = Updates.carry(next, from, st.choices, null, Collections.<String>emptyList());
        Set<String> had = new LinkedHashSet<>(carry.ids().keySet());
        List<String> lines = new ArrayList<>();
        for (String cid : carry.choices().on()) lines.add(cid + "\ton\t1");
        for (Map.Entry<String, Object> e : carry.choices().values().entrySet()) {
            int dot = e.getKey().indexOf('.');
            lines.add(e.getKey().substring(0, dot) + "\tvalue\t" + e.getKey().substring(dot + 1) + "\t" + ((Number) e.getValue()).longValue());
        }
        Set<String> carried = new LinkedHashSet<>(carry.ids().values());
        for (io.github.davdxpx.mistel.mncf.Cheat c : next.cheats()) {   // a cheat new in this version is on when its file says so, as when a file is added
            if (!c.isMaster() && c.isDefault() && !carried.contains(c.id())) lines.add(c.id() + "\ton\t1");
        }
        List<String> reset = new ArrayList<>(), gone = new ArrayList<>();
        for (String key : carry.reset().keySet()) reset.add(nameOf(next, key.substring(0, key.indexOf('.'))));
        Set<String> touched = new LinkedHashSet<>(st.choices.on());
        for (String k : st.choices.values().keySet()) touched.add(k.substring(0, k.indexOf('.')));
        for (String cid : carry.dropped()) if (touched.contains(cid) && now != null) gone.add(nameOf(now, cid));

        // the old names out of the toggles and the numbers, the files swapped, then the state by id kept for the merge
        Set<String> oldKeys = new LinkedHashSet<>(st.keys.values());
        on.removeAll(oldKeys);
        dropValues(folder, oldKeys);
        if (s.file().isFile()) CheatSources.write(new File(s.dir, PREVIOUS), Files.readAllBytes(s.file().toPath()));
        CheatSources.write(s.file(), data);
        new File(s.dir, OFFER).delete();
        CheatSources.write(new File(s.dir, CARRY), String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
        io.github.davdxpx.mistel.mncf.Marks mk = Updates.marks(next, from);
        Map<String, String> state = state(s);
        state.put(NEW, String.join(",", mk.added()));
        state.put(FIXED, String.join(",", mk.fixed()));
        state.remove(LEFT);
        String host = next.update() == null ? null : next.update().host();
        if (host == null || !host.equals(state.get(HOST))) { state.remove(HOST); state.remove(OK); }   // another host is asked about again
        writeState(s, state);
        return new Done(next.fileVersion(), reset, gone);
    }

    /** The version s had before the last install, null when there is none */
    public static MncfFile previous(CheatSources.Source s) { return read(new File(s.dir, PREVIOUS)); }

    /**
     * Back to the version s had before (previous.mncf): what the player set moves back by cheat id (the newer file's renames backwards), a number that does not
     * fit the older file goes back to its own, and the newer version is not offered again (only a version above it)
     */
    public static Done back(File folder, CheatSources.Source s, CheatSources.Merged m, Set<String> on) throws IOException {
        MncfFile now = file(s), before = previous(s);
        if (now == null || before == null) throw new IOException("there is no earlier version");
        Map<String, CheatValues.Kept> values = CheatValues.read(folder);
        Had st = stateOf(s.id, m, now, on, values, s.name);
        List<String> oldIds = new ArrayList<>();
        for (io.github.davdxpx.mistel.mncf.Cheat c : before.cheats()) oldIds.add(c.id());
        Map<String, String> forward = Updates.ids(now, before.fileVersion(), oldIds), backward = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : forward.entrySet()) if (e.getValue() != null && !backward.containsKey(e.getValue())) backward.put(e.getValue(), e.getKey());
        List<String> lines = new ArrayList<>(), reset = new ArrayList<>(), gone = new ArrayList<>();
        for (String cid : st.choices.on()) {
            String old = backward.get(cid);
            if (old != null) lines.add(old + "\ton\t1"); else gone.add(nameOf(now, cid));
        }
        for (Map.Entry<String, Object> e : st.choices.values().entrySet()) {
            int dot = e.getKey().indexOf('.');
            String old = backward.get(e.getKey().substring(0, dot)), vid = e.getKey().substring(dot + 1);
            if (old == null) continue;
            ValueSpec v = valueOf(before, old);
            if (v == null || !v.id().equals(vid) || !Updates.fits(v, e.getValue()).isEmpty()) { reset.add(nameOf(before, old)); continue; }
            lines.add(old + "\tvalue\t" + vid + "\t" + ((Number) e.getValue()).longValue());
        }
        on.removeAll(st.keys.values());
        dropValues(folder, new LinkedHashSet<>(st.keys.values()));
        CheatSources.write(s.file(), Files.readAllBytes(new File(s.dir, PREVIOUS).toPath()));
        new File(s.dir, PREVIOUS).delete();
        new File(s.dir, OFFER).delete();
        CheatSources.write(new File(s.dir, CARRY), String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
        Map<String, String> state = state(s);
        if (now.fileVersion() != null) state.put(LEFT, now.fileVersion());
        state.remove(NEW);
        state.remove(FIXED);
        writeState(s, state);
        return new Done(before.fileVersion(), reset, gone);
    }

    static void dropValues(File folder, Set<String> keys) throws IOException {
        if (keys.isEmpty() || !new File(folder, CheatValues.FILE).isFile()) return;
        Set<String> low = new LinkedHashSet<>();
        for (String k : keys) low.add(k.toLowerCase(Locale.ROOT));
        Map<String, CheatValues.Kept> all = CheatValues.read(folder);
        if (!all.keySet().removeAll(low)) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, CheatValues.Kept> e : all.entrySet()) {
            CheatValues.Kept x = e.getValue();
            sb.append(e.getKey()).append('\t').append(x.number).append('\t').append(x.word).append('\t').append(x.width).append('\t').append(x.original).append('\n');
        }
        CheatValues.write(new File(folder, CheatValues.FILE), sb.toString());
    }

    /**
     * The state kept by cheat id after an install or a step back (carry.tsv), put under the cheats' names in the merge m once their source is in it, made
     * ready for the build: the toggles into on (changed in place), the numbers into values.tsv. Returns whether anything changed. A cheat of the file that
     * cannot work in this build has no name in the merge yet: what was kept for it stays in carry.tsv until it has one (another build), so nothing the player
     * set is lost; what was kept for a cheat the file does not have is dropped
     */
    public static boolean applyPending(File folder, CheatSources.Merged m, Set<String> on) {
        if (m == null) return false;
        boolean changed = false;
        for (CheatSources.Block b : m.blocks) {
            if (b.pending || b.error != null || b.id.startsWith(".") || b.id.startsWith("mod:")) continue;
            File carry = new File(new File(CheatSources.root(folder), b.id), CARRY);
            if (!carry.isFile()) continue;
            Map<String, CheatSources.Row> byId = new LinkedHashMap<>();
            for (CheatSources.Row r : m.rows) if (r.block == b && r.cheat.mncf != null) byId.put(r.cheat.mncf.id, r);
            Set<String> inFile = new LinkedHashSet<>();
            if (b.parsed != null && b.parsed.mncf != null) for (MncfBuild.Cheat c : b.parsed.mncf.cheats) inFile.add(c.id);
            List<String> later = new ArrayList<>();
            try {
                for (String line : new String(Files.readAllBytes(carry.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    String[] t = line.split("\t");
                    CheatSources.Row r = t.length >= 3 ? byId.get(t[0]) : null;
                    if (r == null && t.length >= 3 && inFile.contains(t[0])) later.add(line);   // not possible in this build: kept for one where it is
                    if (r == null || r.cheat.master) continue;
                    if (t[1].equals("on") && t[2].equals("1")) changed |= on.add(r.key);
                    else if (t[1].equals("value") && t.length >= 4 && r.cheat.number != null && r.cheat.mncf.value != null && r.cheat.mncf.value.id().equals(t[2])) {
                        CheatValues.keep(folder, r.key, r.cheat, Long.parseLong(t[3]));
                        changed = true;
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // what cannot be read is left out: the cheats are as their file says
            }
            try {
                if (later.isEmpty()) carry.delete(); else CheatSources.write(carry, String.join("\n", later) + "\n");
            } catch (IOException e) {
                carry.delete();
            }
        }
        return changed;
    }

    /** The source whose file is another version of f (a file the player adds by hand): the same id and game; null when there is none */
    public static CheatSources.Source versionOf(File folder, MncfFile f) {
        if (f == null || f.id() == null) return null;
        for (CheatSources.Source s : CheatSources.list(folder)) {
            MncfFile have = file(s);
            if (have != null && f.id().equals(have.id()) && have.platform().name().equals(f.platform().name()) && have.gameId().equalsIgnoreCase(f.gameId())) return s;
        }
        return null;
    }

    /** Whether the player went back from a version of s (Back to version N): the file's hint after a game update waits for a version above it */
    public static boolean wentBack(CheatSources.Source s) { return state(s).containsKey(LEFT); }

    // ---- Marks ----

    /** The ids of a source's cheats a new version brought (NEW) or fixed (FIXED), shown until the next start */
    public static Set<String> marks(CheatSources.Source s, boolean fixed) {
        Set<String> out = new LinkedHashSet<>();
        for (String x : state(s).getOrDefault(fixed ? FIXED : NEW, "").split(",")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    /** The marks seen: the game started with the new versions (GameActivity.cheatsEnv, through ModCheats.install) */
    public static void seen(File folder) {
        for (CheatSources.Source s : CheatSources.list(folder)) {
            Map<String, String> st = state(s);
            if (st.remove(NEW) == null & st.remove(FIXED) == null) continue;
            try { writeState(s, st); } catch (IOException ignored) { }
        }
    }

    // ---- Packs (the spec, section 17.6) ----
    //
    // Peanuts adds a pack's files of the game as sources of their own. A pack whose manifest has an id is kept too, &lt;folder&gt;/packs/&lt;key&gt;.mncp with
    // its state in &lt;key&gt;.tsv, so its new versions can be found; each of its sources names it (source.tsv: pack, pack_path). A new version of the pack
    // brings each file's new version (what the player set kept as for a file), new files as new sources, and takes out the files it no longer has.

    static final String PACKS = "packs", PACK = "pack", PACK_PATH = "pack_path";

    /** A pack kept for its new versions */
    public static final class Held {
        public final String key;
        final File file, stateFile, offer;
        Held(File folder, String key) {
            this.key = key;
            File d = new File(folder, PACKS);
            file = new File(d, key + ".mncp");
            stateFile = new File(d, key + ".tsv");
            offer = new File(d, key + ".offer.mncp");
        }

        /** The pack as kept, null when it cannot be read */
        public io.github.davdxpx.mistel.mncf.Pack pack() {
            try {
                return file.isFile() ? io.github.davdxpx.mistel.mncf.PackReader.read(Files.readAllBytes(file.toPath()), MncfBuild.TRUST) : null;
            } catch (IOException | MncfException | RuntimeException e) {
                return null;
            }
        }

        /** Its name, as its manifest gives it */
        public String name() {
            io.github.davdxpx.mistel.mncf.Pack p = pack();
            return p == null ? key : p.name();
        }

        /** The host its manifest names for its new versions, null for none */
        public String host() {
            io.github.davdxpx.mistel.mncf.Pack p = pack();
            return p == null || p.update() == null ? null : p.update().host();
        }

        Map<String, String> state() { return CheatSources.readState(stateFile); }

        void state(Map<String, String> st) throws IOException { CheatSources.writeState(stateFile, st); }
    }

    /** The pack a source came from, when it is kept (its manifest has an id), else null */
    public static Held held(File folder, CheatSources.Source s) {
        String key = state(s).get(PACK);
        if (key == null) return null;
        Held h = new Held(folder, key);
        return h.file.isFile() ? h : null;
    }

    /** The sources that came from a kept pack, in the order they were added */
    public static List<CheatSources.Source> sourcesOf(File folder, String key) {
        List<CheatSources.Source> out = new ArrayList<>();
        for (CheatSources.Source s : CheatSources.list(folder)) if (key.equals(state(s).get(PACK))) out.add(s);
        return out;
    }

    /** Keeps the bytes of an .mncp whose manifest has an id, for its new versions: its key, null when it has no id (a pack of its own, then) */
    public static String keep(File folder, byte[] pack) throws IOException {
        io.github.davdxpx.mistel.mncf.Pack p;
        try {
            p = io.github.davdxpx.mistel.mncf.PackReader.read(pack, MncfBuild.TRUST);
        } catch (MncfException e) {
            return null;
        }
        if (p.id() == null) return null;
        String key = p.id().replaceAll("[^a-z0-9._-]", "_");
        if (key.length() > 80) key = key.substring(0, 60) + "-" + MncfBuild.sha256(p.id().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        Held h = new Held(folder, key);
        CheatSources.write(h.file, pack);
        return key;
    }

    /** A source made from the kept pack key's file at path */
    public static void fromPack(CheatSources.Source s, String key, String path) throws IOException {
        Map<String, String> st = state(s);
        st.put(PACK, key);
        st.put(PACK_PATH, path);
        writeState(s, st);
    }

    public static Boolean allowed(Held h) {
        String host = h.host();
        if (host == null) return null;
        Map<String, String> st = h.state();
        if (!host.equals(st.get(HOST)) || !st.containsKey(OK)) return null;
        return "1".equals(st.get(OK));
    }

    public static void allowed(Held h, boolean yes) throws IOException {
        String host = h.host();
        if (host == null) return;
        Map<String, String> st = h.state();
        st.put(HOST, host);
        st.put(OK, yes ? "1" : "0");
        h.state(st);
    }

    public static boolean due(Held h, long now, boolean stuck) {
        if (!Boolean.TRUE.equals(allowed(h))) return false;
        long last;
        try { last = Long.parseLong(h.state().getOrDefault(CHECKED, "0")); } catch (NumberFormatException e) { last = 0; }
        return now - last >= (stuck ? DAY / 24 : DAY);
    }

    /** Looks for a new version of a kept pack at its address now (off the UI thread), and keeps a new one as its offer */
    public static Look look(Held h, Http http, long now) {
        io.github.davdxpx.mistel.mncf.Pack p = h.pack();
        if (p == null || p.update() == null) return new Look("update:none", null, null);
        Map<String, String> st = h.state();
        st.put(CHECKED, String.valueOf(now));
        try { h.state(st); } catch (IOException ignored) { }
        byte[] data;
        try {
            data = http.get(p.update().url(), (int) Math.min(Integer.MAX_VALUE - 8, io.github.davdxpx.mistel.mncf.PackReader.MAX_TOTAL));
        } catch (IOException | RuntimeException e) {
            return new Look("unreachable", null, e.getMessage());
        }
        UpdateCheck check = Updates.checkPack(p, data, MncfBuild.TRUST);
        String v = check.pack() == null ? null : check.pack().fileVersion();
        if (check.ok()) {
            String left = st.get(LEFT);
            if (left != null && Updates.isVersion(left) && !Updates.above(v, left)) return new Look("left", v, null);
            try { CheatSources.write(h.offer, data); } catch (IOException e) { return new Look("unreachable", v, e.getMessage()); }
            return new Look("", v, null);
        }
        if ("update:version".equals(check.reason()) && check.pack() != null) {
            h.offer.delete();
            boolean same = Updates.isVersion(v) && Updates.isVersion(p.fileVersion()) && Updates.compare(v, p.fileVersion()) == 0;
            return new Look(same ? "same" : "older", v, null);
        }
        return new Look(check.reason(), v, null);
    }

    /** The new version kept for a pack, null when there is none */
    public static byte[] offered(Held h) {
        if (!h.offer.isFile()) return null;
        try {
            byte[] data = Files.readAllBytes(h.offer.toPath());
            io.github.davdxpx.mistel.mncf.Pack p = h.pack();
            if (p == null || !Updates.checkPack(p, data, MncfBuild.TRUST).ok()) { h.offer.delete(); return null; }
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    /** What a new version of a pack brings for this game: the notes, and for its files what each brings, the new files and the ones it no longer has */
    public static final class PackOffer {
        public final io.github.davdxpx.mistel.mncf.Pack now, next;
        public final UpdateCheck check;
        public final String host;
        public final List<Release> since;
        public final List<Offer> files = new ArrayList<>();          // the files that change, each as a file's new version
        public final List<String> added = new ArrayList<>(), gone = new ArrayList<>();   // the files new in the pack, and the ones it no longer has, by name
        public boolean knows;                                         // whether a file of it names the build that runs
        final Map<String, String> pairs;                              // old path → new path, null when gone
        final List<String> addedPaths = new ArrayList<>();
        PackOffer(io.github.davdxpx.mistel.mncf.Pack now, io.github.davdxpx.mistel.mncf.Pack next, UpdateCheck check, String host, List<Release> since, Map<String, String> pairs) {
            this.now = now; this.next = next; this.check = check; this.host = host; this.since = since; this.pairs = pairs;
        }
        public boolean newer() { return check.ok(); }
    }

    static byte[] fileOf(io.github.davdxpx.mistel.mncf.Pack p, String path) {
        for (io.github.davdxpx.mistel.mncf.PackFile f : p.files()) if (f.path().equals(path) && io.github.davdxpx.mistel.mncf.PackFile.OK.equals(f.status())) return f.data();
        return null;
    }

    /** The name a pack's file is added under (CheatImport.MncfHit.name): its file name without .mncf */
    static String nameOf(String path) { return new File(path).getName().replaceFirst("(?i)\\.mncf$", ""); }

    /** Whether a pack's file is one of this game's (the pick's rule: CheatImport.mncf) */
    static boolean forGame(byte[] data, String titleId) {
        MncfFile f = parse(data);
        return f != null && "switch".equals(f.platform().name()) && (titleId == null || CheatImport.sameGame(f.gameId(), titleId));
    }

    /** The kept pack with the id of the pack p (an .mncp the player adds by hand), null when there is none */
    public static Held heldOf(File folder, io.github.davdxpx.mistel.mncf.Pack p) {
        if (p == null || p.id() == null) return null;
        File[] l = new File(folder, PACKS).listFiles((d, n) -> n.endsWith(".mncp") && !n.endsWith(".offer.mncp"));
        if (l != null) for (File f : l) {
            Held h = new Held(folder, f.getName().substring(0, f.getName().length() - 5));
            io.github.davdxpx.mistel.mncf.Pack have = h.pack();
            if (have != null && p.id().equals(have.id())) return h;
        }
        return null;
    }

    /** What data would bring as the next version of the kept pack h, for the game titleId whose sources are in folder; null when it is no pack at all */
    public static PackOffer offer(File folder, Held h, byte[] data, CheatSources.Merged m, Set<String> on, Map<String, CheatValues.Kept> values, String build, String titleId) {
        io.github.davdxpx.mistel.mncf.Pack now = h.pack();
        if (now == null) return null;
        UpdateCheck check = Updates.checkPack(now, data, MncfBuild.TRUST);
        io.github.davdxpx.mistel.mncf.Pack next = check.pack();
        if (next == null) return null;
        Map<String, String> pairs = Updates.pairs(now, next, MncfBuild.TRUST);
        String host = now.update() != null ? now.update().host() : next.update() != null ? next.update().host() : null;   // where it came from
        PackOffer o = new PackOffer(now, next, check, host, Updates.since(next, now.fileVersion()), pairs);
        Set<String> targets = new LinkedHashSet<>();
        for (CheatSources.Source s : sourcesOf(folder, h.key)) {
            String to = pairs.get(state(s).get(PACK_PATH));
            byte[] nb = to == null ? null : fileOf(next, to);
            if (nb == null) { o.gone.add(s.name); continue; }
            targets.add(to);
            MncfFile nf = parse(nb);
            if (build != null && nf != null && MncfBuild.builds(nf).contains(build)) o.knows = true;
            byte[] ob;
            try { ob = Files.readAllBytes(s.file().toPath()); } catch (IOException e) { ob = null; }
            if (java.util.Arrays.equals(ob, nb)) continue;
            Offer f = offer(s, nb, m, on, values, build);
            if (f != null) o.files.add(f);
        }
        for (io.github.davdxpx.mistel.mncf.PackFile pf : next.files()) {
            if (targets.contains(pf.path()) || !pf.path().toLowerCase(Locale.ROOT).endsWith(".mncf") || !io.github.davdxpx.mistel.mncf.PackFile.OK.equals(pf.status())) continue;
            if (pairs.containsValue(pf.path()) || !forGame(pf.data(), titleId)) continue;
            o.added.add(nameOf(pf.path()));
            o.addedPaths.add(pf.path());
        }
        return o;
    }

    /** What installing a pack's new version did */
    public static final class PackDone {
        public final String version;
        public final List<String> reset = new ArrayList<>(), gone = new ArrayList<>(), added = new ArrayList<>();
        PackDone(String version) { this.version = version; }
    }

    /**
     * The new version of a kept pack installed for this game: each of its files that changed as a file's new version (install), the files new in it as new
     * sources (their cheats on as their files say, once in the merge), the sources of the files it no longer has removed with their toggles. on is changed in
     * place: the caller keeps it
     */
    public static PackDone install(File folder, Held h, byte[] data, CheatSources.Merged m, Set<String> on, String titleId, long now) throws IOException {
        PackOffer o = offer(folder, h, data, m, on, CheatValues.read(folder), null, titleId);
        if (o == null) throw new IOException("the new version of the pack cannot be read");
        PackDone done = new PackDone(o.next.fileVersion());
        for (CheatSources.Source s : sourcesOf(folder, h.key)) {
            String to = o.pairs.get(state(s).get(PACK_PATH));
            byte[] nb = to == null ? null : fileOf(o.next, to);
            if (nb == null) {
                if (m != null) for (CheatSources.Row r : m.rows) if (r.block.id.equals(s.id)) on.remove(r.key);
                CheatSources.remove(folder, s.id);
                done.gone.add(s.name);
                continue;
            }
            byte[] ob;
            try { ob = Files.readAllBytes(s.file().toPath()); } catch (IOException e) { ob = null; }
            if (!java.util.Arrays.equals(ob, nb)) {
                Done d = install(folder, s, nb, m, on);
                done.reset.addAll(d.reset);
                done.gone.addAll(d.gone);
            }
            Map<String, String> st = state(s);
            st.put(PACK_PATH, to);
            st.remove(HOST);   // a pack's file has no answer of its own
            st.remove(OK);
            writeState(s, st);
        }
        for (String path : o.addedPaths) {
            byte[] nb = fileOf(o.next, path);
            CheatSources.Source s = CheatSources.addMncf(folder, nameOf(path), CheatSources.FROM_ZIP, nb, now++);
            fromPack(s, h.key, path);
            MncfFile f = file(s);
            List<String> lines = new ArrayList<>();
            if (f != null) for (io.github.davdxpx.mistel.mncf.Cheat c : f.cheats()) if (!c.isMaster() && c.isDefault()) lines.add(c.id() + "\ton\t1");
            if (!lines.isEmpty()) CheatSources.write(new File(s.dir, CARRY), String.join("\n", lines) + "\n");
            done.added.add(s.name);
        }
        CheatSources.write(h.file, data);
        h.offer.delete();
        Map<String, String> st = h.state();
        st.remove(LEFT);
        String host = o.next.update() == null ? null : o.next.update().host();
        if (host == null || !host.equals(st.get(HOST))) { st.remove(HOST); st.remove(OK); }
        h.state(st);
        return done;
    }
}
