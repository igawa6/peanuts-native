package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Updates (section 17): a file's identity, its versions, the address of its newest version and
 * its change entries, and how a runtime takes a new version and keeps what the player set.
 *
 * <pre>{@code
 * UpdateCheck check = Updates.check(installed, bytes, trust);    // is it a new version of the installed file?
 * check.reason();                                                // "" when it is, else a reason of Appendix E
 * MncfFile next = check.file();
 * for (Release r : Updates.since(next, installed.fileVersion())) {        // the entries to show, newest first
 *     show(r.version(), r.date(), Updates.text(next, r, "de"));
 * }
 * Marks marks = Updates.marks(next, installed.fileVersion());              // cheats to mark as new or fixed
 * Carry carried = Updates.carry(next, installed.fileVersion(), choices, preset, kept);
 * }</pre>
 *
 * <p>The library makes no connection itself. A runtime asks for {@link Update#url()} as section
 * 17.3 says, with {@link #USER_AGENT} and at most {@link #MAX_REDIRECTS} redirects, and hands the
 * answer to {@link #check}.
 */
public final class Updates {

    /** The User-Agent a runtime sends when it looks for a new version, the same in every runtime. */
    public static final String USER_AGENT = "mncf-update/1";

    /** The most redirects a runtime follows when it looks for a new version, each to an https address. */
    public static final int MAX_REDIRECTS = 5;

    /** The most characters a file's id may have. */
    public static final int MAX_ID = 128;

    /** The most characters an update address may have. */
    public static final int MAX_URL = 2048;

    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}(\\.[0-9]{1,9}){0,7}");
    private static final Pattern FILE_ID = Pattern.compile("[a-z0-9._-]+(/[a-z0-9._-]+)*");
    private static final Pattern DATE = Pattern.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})");
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern URL = Pattern.compile("https://(" + LABEL + "(?:\\." + LABEL + ")+)(?::([0-9]{1,5}))?"
            + "(/[A-Za-z0-9\\-._~!$&'()*+,;=:@/%]*)?(\\?[A-Za-z0-9\\-._~!$&'()*+,;=:@/?%]*)?");
    private static final Pattern BAD_ESCAPE = Pattern.compile("%(?![0-9A-Fa-f]{2})");
    private static final List<String> UPDATE_KEYS = Values.list("url");
    private static final List<String> CHANGE_KEYS =
            Values.list("version", "date", "text", "added", "fixed", "removed", "renamed");
    private static final int[] DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private Updates() {
    }

    // Versions

    /**
     * Returns whether the text is a version of section 17.1: 1 to 8 whole numbers of 1 to 9
     * decimal digits, separated by dots.
     */
    public static boolean isVersion(String v) {
        return v != null && VERSION.matcher(v).matches();
    }

    private static int[] numbers(String v) {
        List<String> parts = Py.split(v, '.');
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Integer.parseInt(parts.get(i));
        }
        return out;
    }

    /**
     * Compares two versions number by number from the left, a number that is missing counting
     * as 0, so that "2" and "2.0" are the same version.
     *
     * @return -1, 0 or 1 as version a is below, the same as, or above version b
     * @throws IllegalArgumentException when either is not a version
     */
    public static int compare(String a, String b) {
        if (!isVersion(a) || !isVersion(b)) {
            throw new IllegalArgumentException("not a version");
        }
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    /**
     * Returns whether version a is above version b. A b that is null or not a version counts as
     * below every version; an a that is not a version is above none.
     */
    public static boolean above(String a, String b) {
        if (!isVersion(a)) {
            return false;
        }
        return !isVersion(b) || compare(a, b) > 0;
    }

    // The forms of the keys

    /**
     * Returns whether the text is a file id of section 17.1: 1 to {@link #MAX_ID} characters,
     * lower-case ASCII letters, digits, ".", "-" and "_", in parts separated by "/", none of them
     * empty, "." or "..".
     */
    public static boolean isFileId(String v) {
        if (v == null || v.isEmpty() || v.length() > MAX_ID || !FILE_ID.matcher(v).matches()) {
            return false;
        }
        for (String part : Py.split(v, '/')) {
            if (part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the host of an address that section 17.3 allows for [update], or null where it
     * does not allow it: https only, a host name of lower-case parts and no IP address, a port
     * from 1 to 65535, a path and a query of the allowed characters, no user name and no "#",
     * and at most {@link #MAX_URL} characters.
     */
    public static String urlHost(String v) {
        if (v == null || v.length() > MAX_URL) {
            return null;
        }
        Matcher m = URL.matcher(v);
        if (!m.matches() || BAD_ESCAPE.matcher(v).find()) {
            return null;
        }
        String host = m.group(1);
        String port = m.group(2);
        if (digits(host.substring(host.lastIndexOf('.') + 1))) {
            return null;
        }
        if (port != null && (Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)) {
            return null;
        }
        return host;
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Returns whether the text is a date of section 17.1: "YYYY-MM-DD", a day of the Gregorian calendar. */
    public static boolean isDate(String v) {
        Matcher m = Py.match(DATE, v);
        if (m == null) {
            return false;
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12) {
            return false;
        }
        boolean leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        int days = month == 2 && leap ? 29 : DAYS[month - 1];
        return day >= 1 && day <= days;
    }

    private static String str(Object v) {
        return v instanceof String ? (String) v : null;
    }

    // Reading the keys

    /** A check of one name in the lists of a change entry: a cheat id in a file, a path in a pack. */
    interface Name {
        boolean ok(Object name);
    }

    /** The names of a file's change entries: cheat ids (section 2). */
    static final Name CHEAT_ID = new Name() {
        @Override
        public boolean ok(Object name) {
            return Py.matches(Values.ID, name);
        }
    };

    /** The keys of section 17.1 of a file or a pack manifest, as {@link #readKeys} reads them. */
    static final class Keys {
        String id;
        Update update;
        List<Release> changes = new ArrayList<>();
    }

    /**
     * The public keys of the signers key (section 12), or none with the warning signers when the
     * key is not an array of 32-byte keys in standard base64, each once.
     */
    @SuppressWarnings("unchecked")
    static List<byte[]> readSigners(Map<String, Object> doc, List<String> warnings) {
        List<byte[]> keys = new ArrayList<>();
        if (!doc.containsKey("signers")) {
            return keys;
        }
        Object v = doc.get("signers");
        boolean good = v instanceof List;
        if (good) {
            for (Object s : (List<Object>) v) {
                byte[] raw = new byte[0];
                if (s instanceof String) {
                    try {
                        raw = Base64.getDecoder().decode((String) s);
                    } catch (IllegalArgumentException e) {
                        raw = new byte[0];
                    }
                }
                if (raw.length != 32 || !Base64.getEncoder().encodeToString(raw).equals(s) || holds(keys, raw)) {
                    good = false;
                    break;
                }
                keys.add(raw);
            }
        }
        if (!good) {
            warnings.add("signers");
            return new ArrayList<>();
        }
        return keys;
    }

    private static boolean holds(List<byte[]> keys, byte[] key) {
        for (byte[] k : keys) {
            if (Arrays.equals(k, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The keys of section 17.1 of a file or a pack manifest: id, update and changes. What breaks
     * a rule is left out with a warning: id, update, changes, changes:&lt;version or position&gt;,
     * unknown-key:update.&lt;key&gt; and unknown-key:changes.&lt;key&gt;.
     *
     * @param item checks one name in the lists of a change entry
     */
    @SuppressWarnings("unchecked")
    static Keys readKeys(Map<String, Object> doc, List<String> warnings, Name item) {
        Keys out = new Keys();
        if (doc.containsKey("id")) {
            if (isFileId(str(doc.get("id")))) {
                out.id = (String) doc.get("id");
            } else {
                warnings.add("id");
            }
        }
        String version = str(doc.get("file_version"));
        boolean versioned = isVersion(version);
        if (doc.containsKey("update")) {
            Object u = doc.get("update");
            String host = null;
            if (u instanceof Map) {
                for (String k : ((Map<String, Object>) u).keySet()) {
                    if (!UPDATE_KEYS.contains(k)) {
                        warnings.add("unknown-key:update." + k);
                    }
                }
                host = urlHost(str(((Map<String, Object>) u).get("url")));
            }
            if (host == null || out.id == null || !versioned) {
                warnings.add("update");
            } else {
                out.update = new Update((String) ((Map<String, Object>) u).get("url"), host);
            }
        }
        if (doc.containsKey("changes")) {
            Object entries = doc.get("changes");
            boolean good = entries instanceof List;
            if (good) {
                for (Object e : (List<Object>) entries) {
                    good &= e instanceof Map;
                }
                good &= ((List<Object>) entries).isEmpty() || versioned;
            }
            if (!good) {
                warnings.add("changes");
            } else {
                List<Object> tables = (List<Object>) entries;
                List<Release> releases = new ArrayList<>();
                for (int i = 0; i < tables.size(); i++) {
                    Map<String, Object> e = (Map<String, Object>) tables.get(i);
                    for (String k : e.keySet()) {
                        if (!CHANGE_KEYS.contains(k)) {
                            warnings.add("unknown-key:changes." + k);
                        }
                    }
                    Release r = release(e, version, releases, item);
                    if (r == null) {
                        Object v = e.get("version");
                        warnings.add("changes:" + (v instanceof String ? (String) v : String.valueOf(i + 1)));
                    } else {
                        releases.add(r);
                    }
                }
                Collections.sort(releases, new Comparator<Release>() {
                    @Override
                    public int compare(Release a, Release b) {
                        return Updates.compare(b.version(), a.version());
                    }
                });
                out.changes = releases;
            }
        }
        return out;
    }

    /** One change entry, or null where it breaks a rule of section 17.1. */
    @SuppressWarnings("unchecked")
    private static Release release(Map<String, Object> e, String fileVersion, List<Release> seen, Name item) {
        String v = str(e.get("version"));
        if (!isVersion(v) || compare(v, fileVersion) > 0) {
            return null;
        }
        for (Release r : seen) {
            if (compare(v, r.version()) == 0) {
                return null;
            }
        }
        String date = null;
        if (e.containsKey("date")) {
            if (!isDate(str(e.get("date")))) {
                return null;
            }
            date = (String) e.get("date");
        }
        String text = null;
        if (e.containsKey("text")) {
            if (!(e.get("text") instanceof String)) {
                return null;
            }
            text = (String) e.get("text");
        }
        List<List<String>> lists = new ArrayList<>();
        for (String key : new String[] {"added", "fixed", "removed"}) {
            List<String> names = new ArrayList<>();
            if (e.containsKey(key)) {
                names = names(e.get(key), item);
                if (names == null) {
                    return null;
                }
            }
            lists.add(names);
        }
        Set<String> all = new HashSet<>();
        int count = 0;
        for (List<String> names : lists) {
            all.addAll(names);
            count += names.size();
        }
        if (all.size() != count) {
            return null;
        }
        List<String> removed = lists.get(2);
        Map<String, String> renamed = new LinkedHashMap<>();
        if (e.containsKey("renamed")) {
            Object ren = e.get("renamed");
            if (!(ren instanceof Map)) {
                return null;
            }
            for (Map.Entry<String, Object> x : ((Map<String, Object>) ren).entrySet()) {
                if (!item.ok(x.getKey()) || !item.ok(x.getValue())) {
                    return null;
                }
                renamed.put(x.getKey(), (String) x.getValue());
            }
            if (new HashSet<>(renamed.values()).size() != renamed.size()) {
                return null;
            }
            for (String from : renamed.keySet()) {
                if (removed.contains(from)) {
                    return null;
                }
            }
        }
        return new Release(v, date, text, lists.get(0), lists.get(1), removed, renamed);
    }

    private static List<String> names(Object v, Name item) {
        if (!(v instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object x : (List<?>) v) {
            if (!item.ok(x)) {
                return null;
            }
            out.add((String) x);
        }
        return new HashSet<>(out).size() == out.size() ? out : null;
    }

    // A new version (section 17.2)

    /**
     * Returns whether the bytes are a new version of the loaded file the runtime holds (section
     * 17.2). Load the installed file with the same trust list, so that both signatures are
     * checked against the keys the runtime trusts now.
     *
     * @param installed the file the runtime holds
     * @param data the bytes of the new file, such as the answer to a request for its update url
     * @param trust the keys the reader trusts, or null for none
     */
    public static UpdateCheck check(MncfFile installed, byte[] data, TrustList trust) {
        MncfFile next;
        try {
            next = Mncf.load(data, trust);
        } catch (MncfException e) {
            return new UpdateCheck("update:error:" + e.code(), null, null, installed.fileVersion(), null);
        }
        boolean otherGame = !installed.platform().name().equals(next.platform().name())
                || !Py.lower(installed.gameId()).equals(Py.lower(next.gameId()));
        String reason = steps(installed.signature(), installed.id(), installed.fileVersion(), next.signature(),
                next.id(), next.fileVersion(), otherGame);
        return new UpdateCheck(reason, next, null, installed.fileVersion(), next.fileVersion());
    }

    /**
     * Returns whether the bytes are a new version of the pack the runtime holds (section 17.6):
     * the steps of section 17.2 for its manifest, without the game.
     *
     * @param installed the pack the runtime holds
     * @param data the bytes of the new .mncp file
     * @param trust the keys the reader trusts, or null for none
     */
    public static UpdateCheck checkPack(Pack installed, byte[] data, TrustList trust) {
        Pack next;
        try {
            next = PackReader.read(data, trust);
        } catch (MncfException e) {
            return new UpdateCheck("update:error:" + e.code(), null, null, installed.fileVersion(), null);
        }
        String reason = steps(installed.signature(), installed.id(), installed.fileVersion(), next.signature(),
                next.id(), next.fileVersion(), false);
        return new UpdateCheck(reason, null, next, installed.fileVersion(), next.fileVersion());
    }

    private static String steps(SignatureResult oldSig, String oldId, String oldVersion, SignatureResult newSig,
            String newId, String newVersion, boolean otherGame) {
        String result = newSig.result();
        if (result.equals(SignatureResult.INVALID) || result.equals(SignatureResult.REVOKED)) {
            return "update:signature";
        }
        if (otherGame) {
            return "update:game";
        }
        if (oldId == null || !oldId.equals(newId)) {
            return "update:id";
        }
        if (!above(newVersion, oldVersion)) {
            return "update:version";
        }
        if (!signedBy(oldSig, newSig)) {
            return "update:signer";
        }
        return "";
    }

    /** Step 6: whether a signature line of the new file verifies with a key that signed the old one. */
    private static boolean signedBy(SignatureResult old, SignatureResult next) {
        if (old.result().equals(SignatureResult.UNSIGNED)) {
            return true;
        }
        Map<String, byte[]> newKeys = next.verifiedKeys();
        Map<String, byte[]> oldKeys = old.verifiedKeys();
        for (String kid : old.keyIds()) {
            byte[] key = oldKeys.get(kid);
            if (key != null) {
                for (byte[] k : newKeys.values()) {
                    if (Arrays.equals(k, key)) {
                        return true;
                    }
                }
            } else if (newKeys.containsKey(kid)) {
                return true;
            }
        }
        return false;
    }

    // What to show and what the player set (sections 17.4 and 17.5)

    /** Returns the change entries of a file above a version (null: all of them), newest first. */
    public static List<Release> since(MncfFile f, String version) {
        return since(f.changes(), version);
    }

    /** Returns the change entries of a pack above a version (null: all of them), newest first. */
    public static List<Release> since(Pack p, String version) {
        return since(p.changes(), version);
    }

    private static List<Release> since(List<Release> changes, String version) {
        List<Release> out = new ArrayList<>();
        for (Release r : changes) {
            if (above(r.version(), version)) {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns the text of a change entry in a language, from the file's translations
     * (i18n.&lt;tag&gt;.changes."&lt;version&gt;".text), or else the entry's own text, which may
     * be null.
     *
     * @param language a BCP 47 tag, or null for the entry's own text
     */
    public static String text(MncfFile f, Release r, String language) {
        if (language != null && !language.isEmpty()) {
            Object tr = f.i18n().get(language);
            Object changes = tr instanceof Map ? ((Map<?, ?>) tr).get("changes") : null;
            Object entry = changes instanceof Map ? ((Map<?, ?>) changes).get(r.version()) : null;
            if (entry instanceof Map && ((Map<?, ?>) entry).get("text") instanceof String) {
                return (String) ((Map<?, ?>) entry).get("text");
            }
        }
        return r.text();
    }

    /**
     * Returns where each cheat id of the version the player has is in the new version f: the
     * ids follow the change entries above that version, in version order.
     *
     * @param version the version the player has, or null for none
     * @param ids cheat ids of that version
     * @return each id, in the order given, with its id in f, or null where it is gone
     */
    public static Map<String, String> ids(final MncfFile f, String version, Collection<String> ids) {
        return Collections.unmodifiableMap(follow(f.changes(), version, ids, new Name() {
            @Override
            public boolean ok(Object name) {
                return f.cheat((String) name) != null;
            }
        }));
    }

    private static Map<String, String> follow(List<Release> changes, String version, Collection<String> names,
            Name have) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String n : names) {
            out.put(n, n);
        }
        List<Release> shown = since(changes, version);
        for (int i = shown.size() - 1; i >= 0; i--) {
            Release r = shown.get(i);
            for (Map.Entry<String, String> e : out.entrySet()) {
                String cur = e.getValue();
                if (cur == null) {
                    continue;
                }
                if (r.removed().contains(cur)) {
                    e.setValue(null);
                } else if (r.renamed().containsKey(cur)) {
                    e.setValue(r.renamed().get(cur));
                }
            }
        }
        for (Map.Entry<String, String> e : out.entrySet()) {
            if (e.getValue() != null && !have.ok(e.getValue())) {
                e.setValue(null);
            }
        }
        return out;
    }

    /**
     * Returns the cheats a runtime may mark in the new version f for a player who had a version
     * (section 17.4): those in added and those in fixed of the entries above it, under their ids
     * in f. A cheat that is new and fixed is marked new only.
     *
     * @param version the version the player has, or null for none
     */
    public static Marks marks(MncfFile f, String version) {
        Set<String> added = new HashSet<>();
        Set<String> fixed = new HashSet<>();
        List<Release> shown = since(f, version);
        for (int i = shown.size() - 1; i >= 0; i--) {
            Release r = shown.get(i);
            added = follow(added, r, r.added());
            fixed = follow(fixed, r, r.fixed());
        }
        List<String> marksNew = new ArrayList<>();
        List<String> marksFixed = new ArrayList<>();
        for (Cheat c : f.cheats()) {
            if (added.contains(c.id())) {
                marksNew.add(c.id());
            } else if (fixed.contains(c.id())) {
                marksFixed.add(c.id());
            }
        }
        return new Marks(marksNew, marksFixed);
    }

    private static Set<String> follow(Set<String> names, Release r, List<String> more) {
        Set<String> out = new HashSet<>();
        for (String x : names) {
            if (!r.removed().contains(x)) {
                out.add(r.renamed().containsKey(x) ? r.renamed().get(x) : x);
            }
        }
        out.addAll(more);
        return out;
    }

    /**
     * Returns whether a value the player chose fits a value of the file, by the rules the
     * evaluator refuses a cheat by (value-out-of-range): the empty string where it fits, else
     * why not: {@link Carry#TYPE} for another kind of value (a Boolean is the only value of type
     * bool, a whole number the only value of an integer type), NaN, or one too wide for the type;
     * else {@link Carry#RANGE} outside min and max; else {@link Carry#CHOICE} where it is not
     * among the choices.
     */
    public static String fits(ValueSpec spec, Object value) {
        Object v;
        try {
            v = Choices.number(value);
        } catch (IllegalArgumentException e) {
            return Carry.TYPE;
        }
        return Evaluator.misfit(spec, v);
    }

    /**
     * Returns what the player set for a version of a file, moved to the new version f (section
     * 17.5): the cheats switched on and off and the values, under their new ids, and the preset.
     * What the new version does not have is dropped; a value that no longer fits goes back to its
     * default.
     *
     * @param version the version the player has, or null for none
     * @param choices what the player chose for that version, or null for nothing
     * @param preset the preset the player chose, or null
     * @param kept cheats of that version for which the runtime keeps something else, such as the
     *     answer to a crash question, so that their new ids are in {@link Carry#ids()} too; or null
     */
    public static Carry carry(MncfFile f, String version, Choices choices, String preset, Collection<String> kept) {
        Choices chosen = choices == null ? Choices.none() : choices;
        List<String> seen = new ArrayList<>();
        List<String> look = new ArrayList<>(chosen.on());
        look.addAll(chosen.off());
        for (String key : chosen.values().keySet()) {
            look.add(cheatOf(key));
        }
        if (kept != null) {
            look.addAll(kept);
        }
        for (String cid : look) {
            if (!seen.contains(cid)) {
                seen.add(cid);
            }
        }
        Map<String, String> m = ids(f, version, seen);
        List<String> dropped = new ArrayList<>();
        for (String cid : seen) {
            if (m.get(cid) == null) {
                dropped.add(cid);
            }
        }
        List<String> on = moved(chosen.on(), m);
        List<String> off = moved(chosen.off(), m);
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> gone = new ArrayList<>();
        Map<String, String> reset = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : chosen.values().entrySet()) {
            String key = e.getKey();
            String to = m.get(cheatOf(key));
            if (to == null) {
                continue;
            }
            int dot = key.indexOf('.');
            String vid = dot < 0 ? "" : key.substring(dot + 1);
            ValueSpec spec = f.cheat(to).values().get(vid);
            if (spec == null) {
                gone.add(key);
                continue;
            }
            String newKey = to + "." + vid;
            if (values.containsKey(newKey) || reset.containsKey(newKey)) {
                continue;
            }
            String why = Evaluator.misfit(spec, e.getValue());
            if (why.isEmpty()) {
                values.put(newKey, e.getValue());
            } else {
                reset.put(newKey, why);
            }
        }
        String keptPreset = null;
        for (Preset p : f.presets()) {
            if (p.id().equals(preset)) {
                keptPreset = preset;
            }
        }
        Choices.Builder b = Choices.builder().on(on).off(off);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            b.value(e.getKey(), e.getValue());
        }
        return new Carry(b.build(), keptPreset, m, dropped, gone, reset);
    }

    private static String cheatOf(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(0, dot);
    }

    private static List<String> moved(List<String> ids, Map<String, String> m) {
        List<String> out = new ArrayList<>();
        for (String cid : ids) {
            String to = m.get(cid);
            if (to != null && !out.contains(to)) {
                out.add(to);
            }
        }
        return out;
    }

    /**
     * Matches the files of an old pack with those of a new one (section 17.6): first by the id
     * and game of the files that load and have an id, then by path, following the renamed of the
     * new pack's change entries, where one of the two has no id.
     *
     * @param trust the keys the reader trusts, or null for none, for loading the files
     * @return each path of the old pack, in its order, with the path of its match in the new
     *     pack, or null where it has none and is gone
     */
    public static Map<String, String> pairs(Pack old, Pack next, TrustList trust) {
        Map<String, List<String>> olds = new LinkedHashMap<>();
        for (PackFile pf : old.files()) {
            olds.put(pf.path(), identity(pf, trust));
        }
        final Map<String, List<String>> news = new LinkedHashMap<>();
        for (PackFile pf : next.files()) {
            news.put(pf.path(), identity(pf, trust));
        }
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (Map.Entry<String, List<String>> e : olds.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> n : news.entrySet()) {
                if (e.getValue().equals(n.getValue()) && !taken.contains(n.getKey())) {
                    out.put(e.getKey(), n.getKey());
                    taken.add(n.getKey());
                    break;
                }
            }
        }
        List<String> rest = new ArrayList<>();
        for (String path : olds.keySet()) {
            if (!out.containsKey(path)) {
                rest.add(path);
            }
        }
        Map<String, String> paths = follow(next.changes(), old.fileVersion(), rest, new Name() {
            @Override
            public boolean ok(Object name) {
                return news.containsKey(name);
            }
        });
        for (Map.Entry<String, String> e : paths.entrySet()) {
            String target = e.getValue();
            if (target != null && !taken.contains(target)
                    && (olds.get(e.getKey()) == null || news.get(target) == null)) {
                out.put(e.getKey(), target);
                taken.add(target);
            } else {
                out.put(e.getKey(), null);
            }
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String path : olds.keySet()) {
            ordered.put(path, out.get(path));
        }
        return Collections.unmodifiableMap(ordered);
    }

    /** The id, platform and game of a pack's file that loads and has an id, or null. */
    private static List<String> identity(PackFile pf, TrustList trust) {
        if (!pf.status().equals(PackFile.OK)) {
            return null;
        }
        MncfFile f;
        try {
            f = Mncf.load(pf.data(), trust);
        } catch (MncfException e) {
            return null;
        }
        return f.id() == null ? null : Arrays.asList(f.id(), f.platform().name(), Py.lower(f.gameId()));
    }
}
