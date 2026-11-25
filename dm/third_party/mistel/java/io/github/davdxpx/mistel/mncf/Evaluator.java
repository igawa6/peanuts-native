package io.github.davdxpx.mistel.mncf;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Identifies the build that runs and gives every cheat its state, reason and resolved
 * operations (sections 5, 6, 11 and Appendix B).
 */
public final class Evaluator {

    private Evaluator() {
    }

    /** A cheat cannot be used here, for the reason given. */
    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        final String reason;

        Refused(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /**
     * The result of one cheat while the evaluation decides its state. A cheat whose own
     * operations apply also has its planned operations; one that uses raw code, its entry.
     */
    static final class Result {
        final String id;
        String state;
        String reason;
        final String uses;
        final List<ResolvedOp> ops;
        boolean pathBroken;
        List<Plan> plans;
        Model.Raw rawEntry;

        Result(String id, String state, String reason, String uses, List<ResolvedOp> ops) {
            this.id = id;
            this.state = state;
            this.reason = reason;
            this.uses = uses;
            this.ops = ops;
        }
    }

    /** A resolved place: its address and, for a place given in a module, the module and offset. */
    static final class Place {
        final long address;
        final String module;
        final Long offset;
        final Module inModule;

        Place(long address, String module, Long offset, Module inModule) {
            this.address = address;
            this.module = module;
            this.offset = offset;
            this.inModule = inModule;
        }
    }

    static final class SearchKey {
        final Module module;
        final int[] items;
        final String section;

        SearchKey(Module module, int[] items, String section) {
            this.module = module;
            this.items = items;
            this.section = section;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SearchKey)) {
                return false;
            }
            SearchKey k = (SearchKey) o;
            return module == k.module && Arrays.equals(items, k.items) && Objects.equals(section, k.section);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(module) * 31 + Arrays.hashCode(items) * 7 + Objects.hashCode(section);
        }
    }

    private static Result refused(String id, String reason) {
        return new Result(id, CheatResult.REFUSED, reason, "", Collections.<ResolvedOp>emptyList());
    }

    /** A cheat's own result when it is refused for the reason given. */
    static Result refusedResult(String id, String reason) {
        return refused(id, reason);
    }

    /** Returns the key of the build that runs, or null (section 5). */
    public static String identify(MncfFile f, Runtime rt) {
        if (rt.build() != null) {
            return f.builds().containsKey(rt.build()) ? rt.build() : null;
        }
        for (Build b : f.builds().values()) {
            boolean all = true;
            for (Map.Entry<String, String> e : b.modules().entrySet()) {
                Module m = rt.modules().get(e.getKey());
                if (m == null || m.id() == null || !f.platform().moduleIdMatches(e.getValue(), m.id())) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return b.key();
            }
        }
        return null;
    }

    /** Evaluates a file in a runtime with every cheat as its default. */
    public static Evaluation evaluate(MncfFile f, Runtime rt) {
        return evaluate(f, rt, Choices.none());
    }

    /** Returns the build that runs and every cheat's state, reason and resolved operations. */
    public static Evaluation evaluate(MncfFile f, Runtime rt, Choices choices) {
        return load(f, rt, choices == null ? Choices.none() : choices, new HashMap<SearchKey, List<Long>>())
                .evaluation;
    }

    /** What loading a file gives: the evaluation, the cheats that are on, and each cheat's own result. */
    static final class Loaded {
        final Evaluation evaluation;
        final List<String> on;
        final Map<String, Result> own;

        Loaded(Evaluation evaluation, List<String> on, Map<String, Result> own) {
            this.evaluation = evaluation;
            this.on = on;
            this.own = own;
        }
    }

    /**
     * Evaluates a file, and keeps the cheats that are on and each cheat's own result before its
     * relations to other cheats were looked at.
     */
    static Loaded load(MncfFile f, Runtime rt, Choices choices, Map<SearchKey, List<Long>> cache) {
        String build = identify(f, rt);
        Map<String, Result> own = new LinkedHashMap<>();
        for (Cheat c : f.cheats()) {
            own.put(c.id(), ownResult(f, c, rt, build, choices, cache));
        }
        Map<String, Result> results = relations(f, own);
        List<String> on = switchStates(f, results, choices, rt);
        List<CheatResult> out = new ArrayList<>();
        for (Cheat c : f.cheats()) {
            Result r = results.get(c.id());
            out.add(new CheatResult(r.id, r.state, r.reason, r.uses, r.ops));
        }
        return new Loaded(new Evaluation(build, out), on, own);
    }

    /** A cheat's own result for the choices: refused with a reason, or its planned operations or raw entry. */
    static Result ownResult(MncfFile f, Cheat c, Runtime rt, String build, Choices choices,
            Map<SearchKey, List<Long>> cache) {
        String fileReason = fileReason(f, rt);
        if (fileReason != null) {
            return refused(c.id(), fileReason);
        }
        if (c.problem() != null) {
            return refused(c.id(), c.problem());
        }
        String arch = rt.arch() != null && !rt.arch().isEmpty() ? rt.arch() : f.platform().archs().get(0);
        return cheat(f, c, rt, build, arch, choices, cache);
    }

    private static String fileReason(MncfFile f, Runtime rt) {
        if (!f.platform().name().equals(rt.platform())
                || (rt.gameId() != null && !Py.lower(rt.gameId()).equals(Py.lower(f.gameId())))) {
            return "game-mismatch";
        }
        String sig = f.signature().result();
        if (sig.equals(SignatureResult.INVALID) || sig.equals(SignatureResult.REVOKED)) {
            return "signature";
        }
        for (String name : f.requiredExtensions()) {
            if (!rt.extensions().contains(name)) {
                return "extension-missing:" + name;
            }
        }
        return null;
    }

    /** Whether the runtime applies this override by writing a stub over the function's first bytes. */
    private static boolean byBytes(Model.Override op, Runtime rt, String arch) {
        return rt.capabilities().contains("code-bytes") && op.expect != null && Encode.hasStub(arch);
    }

    /** Returns the parts of the core in {@link Mncf#FEATURES} that a cheat uses, in that order. */
    public static List<String> uses(Cheat c) {
        int conditions = 0;
        boolean override = false;
        for (Model.Op op : c.ops()) {
            if (op instanceof Model.Condition) {
                conditions++;
            }
            override |= op instanceof Model.Override;
        }
        Set<String> used = new HashSet<>();
        if (!"on".equals(c.run())) {
            used.add("run." + c.run());
        }
        if (c.hold() != null) {
            used.add("hold");
        }
        if (c.toggle() != null) {
            used.add("toggle");
        }
        if (c.press() != null) {
            used.add("press");
        }
        if (conditions > 0) {
            used.add("when");
            if (conditions > 1) {
                used.add("when.many");
            }
            if ("any".equals(c.match())) {
                used.add("match.any");
            }
            if ("enter".equals(c.trigger())) {
                used.add("trigger.enter");
            }
            if (c.interval() != 1) {
                used.add("interval");
            }
        }
        if (override) {
            used.add("override");
        }
        List<String> out = new ArrayList<>();
        for (String k : Mncf.FEATURES) {
            if (used.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static Result cheat(MncfFile f, Cheat c, Runtime rt, String build, String arch, Choices choices,
            Map<SearchKey, List<Long>> cache) {
        for (String k : uses(c)) {
            if (rt.unsupported().contains(k)) {
                return refused(c.id(), "runtime:unsupported:" + k);
            }
        }
        List<String> buttonCaps = c.hold() != null || c.toggle() != null || c.press() != null
                ? Collections.singletonList("buttons") : Collections.<String>emptyList();
        List<String> caps = new ArrayList<>();
        List<String> exts = new ArrayList<>();
        for (Model.Op op : c.ops()) {
            boolean path = op.location().hasPath();
            if (op instanceof Model.Condition && ((Model.Condition) op).value instanceof Model.Place) {
                path |= ((Model.Place) ((Model.Condition) op).value).at.hasPath();
            }
            if (path || op instanceof Model.Condition || op instanceof Model.Read) {
                caps.add("guest-read");
            }
            if (op instanceof Model.Write) {
                caps.add("guest-write");
            }
            if (op instanceof Model.Patch) {
                caps.add("code-bytes");
                if (((Model.Patch) op).encode != null) {
                    exts.add("arm64-imm/1");
                }
            }
            if (op instanceof Model.Read) {
                caps.add("display");
            }
            if (op instanceof Model.Override && !byBytes((Model.Override) op, rt, arch)) {
                caps.add("code-hook");
            }
        }
        caps.addAll(buttonCaps);
        List<String> missing = new ArrayList<>();
        for (String cap : Mncf.CAPABILITIES) {
            if (caps.contains(cap) && !rt.capabilities().contains(cap)) {
                missing.add(cap);
            }
        }
        List<String> missingExt = new ArrayList<>();
        for (String e : exts) {
            if (!rt.extensions().contains(e)) {
                missingExt.add(e);
            }
        }
        boolean runsRaw = false;
        if (rt.extensions().contains("raw/1")) {
            for (Model.Raw r : c.raw()) {
                runsRaw |= rt.rawSyntaxes().contains(r.syntax);
            }
        }
        Model.Raw entry = null;
        for (Model.Raw r : c.raw()) {
            if (runsRaw && r.build.equals(build) && rt.rawSyntaxes().contains(r.syntax)) {
                entry = r;
                break;
            }
        }
        List<String> buttonsMissing = new ArrayList<>();
        for (String cap : buttonCaps) {
            if (!rt.capabilities().contains(cap)) {
                buttonsMissing.add(cap);
            }
        }
        if (!c.coreOps().isEmpty()) {
            if (missing.isEmpty() && missingExt.isEmpty()) {
                return core(f, c, rt, build, arch, choices, cache);
            }
            if (entry != null && buttonsMissing.isEmpty()) {
                return raw(c, entry);
            }
            return refused(c.id(), !missing.isEmpty() ? "capability-missing:" + missing.get(0)
                    : "extension-missing:" + missingExt.get(0));
        }
        if (!runsRaw) {
            return refused(c.id(), "extension-missing:raw/1");
        }
        if (!buttonsMissing.isEmpty()) {
            return refused(c.id(), "capability-missing:" + buttonsMissing.get(0));
        }
        if (entry == null) {
            return refused(c.id(), "build-unknown");
        }
        return raw(c, entry);
    }

    private static Result raw(Cheat c, Model.Raw r) {
        ResolvedOp.Builder op = new ResolvedOp.Builder("raw");
        op.syntax = r.syntax;
        op.build = r.build;
        op.code = r.code;
        Result result = new Result(c.id(), CheatResult.OFF, "", "raw", Collections.singletonList(op.done()));
        result.rawEntry = r;
        return result;
    }

    /**
     * The value the player chose, or the default. A chosen value that the player cannot choose
     * refuses the cheat: not of the value's kind, NaN, too wide, outside min and max, or not
     * among the choices.
     */
    private static Object value(Cheat c, Model.ValueRef ref, Choices choices) throws Refused {
        ValueSpec spec = c.values().get(ref.name);
        String key = c.id() + "." + ref.name;
        if (!choices.values().containsKey(key)) {
            return spec.defaultValue();
        }
        Object v = choices.values().get(key);
        if (!misfit(spec, v).isEmpty()) {
            throw new Refused("value-out-of-range");
        }
        return v;
    }

    /**
     * Why a value the player chose is not one the player can choose for this value, which
     * refuses its cheat with value-out-of-range: "type" for one not of the value's kind, NaN or
     * too wide, else "range" for one outside min and max, else "choice" for one not among the
     * choices; the empty string where it fits. {@link Updates#fits} gives the same (section 17.5).
     */
    static String misfit(ValueSpec spec, Object v) {
        boolean ok;
        if (spec.type().equals("bool")) {
            ok = v instanceof Boolean;
        } else if (Values.isFloatType(spec.type())) {
            ok = Values.isNumber(v) && !Schema.isNan(v) && Values.fits(spec.type(), v);
        } else {
            ok = Values.isInt(v) && Values.fits(spec.type(), v);
        }
        if (!ok) {
            return "type";
        }
        if ((spec.min() != null && Values.less(v, spec.min())) || (spec.max() != null && Values.less(spec.max(), v))) {
            return "range";
        }
        if (spec.choices() != null && !spec.choices().containsKey(v)) {
            return "choice";
        }
        return "";
    }

    /**
     * The value the player chose, or the default, as {@link #value} gives it; null when the
     * chosen value is not one the player can choose.
     */
    static Object chosenValue(Cheat c, Model.ValueRef ref, Choices choices) {
        try {
            return value(c, ref, choices);
        } catch (Refused r) {
            return null;
        }
    }

    /**
     * Resolves a cheat's operations for the runtime as it is now: plans them, then follows their
     * pointer paths with the runtime's guest memory.
     */
    private static Result core(MncfFile f, Cheat c, Runtime rt, String build, String arch, Choices choices,
            Map<SearchKey, List<Long>> cache) {
        List<ResolvedOp> ops = new ArrayList<>();
        List<Plan> plans = new ArrayList<>();
        boolean waiting = false;
        GuestMemory memory = runtimeMemory(rt);
        try {
            int index = 0;
            for (Model.Op op : c.ops()) {
                Plan p = plan(f, c, index++, op, rt, build, arch, choices, cache);
                plans.add(p);
                List<Place> places = followAll(f.platform(), p, memory);
                if (places == null) {
                    waiting = true;
                    ops.add(new ResolvedOp.Builder(p.kind).done());
                    continue;
                }
                if (p.pathExpect) {
                    checkExpect(rt, places.get(0), p.expect, p.kind.equals("patch") ? p.data : null);
                }
                ops.add(p.resolved(places));
            }
        } catch (Refused r) {
            return refused(c.id(), r.reason);
        }
        Result result = new Result(c.id(), CheatResult.OFF, "", "core", ops);
        result.pathBroken = waiting;
        result.plans = Collections.unmodifiableList(plans);
        return result;
    }

    /** The runtime's reads as a {@link GuestMemory}. */
    static GuestMemory runtimeMemory(final Runtime rt) {
        return new GuestMemory() {
            @Override
            public byte[] read(long address, int size) {
                return rt.read(address, size);
            }
        };
    }

    /**
     * A place an operation acts on before its pointer path is followed: the place of its base,
     * the path, and how many bytes the operation reads or writes there.
     */
    static final class Target {
        final Place base;
        final long[] path;
        final int size;

        Target(Place base, long[] path, int size) {
            this.base = base;
            this.path = path;
            this.size = size;
        }

        boolean hasPath() {
            return path != null && path.length > 0;
        }
    }

    /**
     * One operation of a cheat, resolved as far as it can be without reading guest memory that
     * changes. The targets are the operation's place and, for a condition that compares with
     * another place, that place too. The data is what a patch, a write or an override stub
     * writes; an override that the runtime hooks has none. A condition's value is a number, a
     * Boolean, a Between, or the Target of the other place.
     */
    static final class Plan {
        final String kind;
        final int index;
        final Model.Op op;
        final List<Target> targets;
        byte[] data;
        byte[] expect;
        String type;
        String action;
        Object value;
        String label;
        boolean pathExpect;

        Plan(String kind, int index, Model.Op op, List<Target> targets) {
            this.kind = kind;
            this.index = index;
            this.op = op;
            this.targets = targets;
        }

        ResolvedOp resolved(List<Place> places) {
            Place p = places.get(0);
            ResolvedOp.Builder out = new ResolvedOp.Builder(kind).place(p.address, p.module, p.offset);
            if (kind.equals("patch")) {
                out.expect = expect;
                out.bytes = data;
            } else if (kind.equals("write")) {
                out.type = type;
                out.expect = expect;
                out.bytes = data;
            } else if (kind.equals("override")) {
                out.action = action;
                out.value = value;
                out.expect = expect;
                out.bytes = data;
            } else if (kind.equals("read")) {
                out.type = type;
                out.label = label;
            } else {
                Object v = value;
                if (v instanceof Target) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("address", Values.unsigned(places.get(1).address));
                    m.put("type", ((Model.Place) ((Model.Condition) op).value).type);
                    v = Collections.unmodifiableMap(m);
                } else if (v instanceof Model.Between) {
                    v = ResolvedOp.pair(((Model.Between) v).low, ((Model.Between) v).high);
                }
                out.type = type;
                out.op = ((Model.Condition) op).op;
                out.value = v;
            }
            return out.done();
        }
    }

    private static Plan plan(MncfFile f, Cheat c, int index, Model.Op op, Runtime rt, String build, String arch,
            Choices choices, Map<SearchKey, List<Long>> cache) throws Refused {
        boolean big = f.platform().bigEndian();
        if (op instanceof Model.Patch) {
            Model.Patch p = (Model.Patch) op;
            Target target = target(f, p.at, rt, build, p.expect.length, cache);
            byte[] data;
            if (p.encode != null) {
                Model.Encoding e = p.encode;
                Object v = e.value instanceof Model.ValueRef ? value(c, (Model.ValueRef) e.value, choices) : e.value;
                data = Encode.arm64Imm(e.insn, e.width, e.rd, v);
                if (data == null) {
                    throw new Refused("value-out-of-range");
                }
            } else {
                data = p.bytes;
            }
            if (!target.hasPath()) {
                checkRewritten(rt, target.base, p.expect.length);
                checkExpect(rt, target.base, p.expect, data);
            }
            Plan out = new Plan("patch", index, op, Collections.singletonList(target));
            out.data = data;
            out.expect = p.expect;
            out.pathExpect = target.hasPath();
            return out;
        }
        if (op instanceof Model.Write) {
            Model.Write w = (Model.Write) op;
            boolean isBytes = w.type.equals("bytes");
            int size = isBytes ? ((byte[]) w.value).length : Values.typeSize(w.type);
            Target target = target(f, w.at, rt, build, size, cache);
            byte[] data;
            byte[] expect;
            if (isBytes) {
                data = (byte[]) w.value;
                expect = (byte[]) w.expect;
            } else {
                Object v = w.value;
                if (v instanceof Model.ValueRef) {
                    v = value(c, (Model.ValueRef) v, choices);
                    if (v instanceof Boolean) {
                        v = ((Boolean) v) ? 1L : 0L;
                    }
                    if (!Values.fits(w.type, v)) {
                        throw new Refused("value-out-of-range");
                    }
                }
                data = Values.encode(w.type, v, big);
                expect = w.expect != null ? Values.encode(w.type, w.expect, big) : null;
            }
            Plan out = new Plan("write", index, op, Collections.singletonList(target));
            out.data = data;
            out.expect = expect;
            out.type = w.type;
            return out;
        }
        if (op instanceof Model.Override) {
            Model.Override o = (Model.Override) op;
            Object v = o.value;
            if (v instanceof Model.ValueRef) {
                v = whole(value(c, (Model.ValueRef) v, choices));
            }
            boolean patches = byBytes(o, rt, arch);
            byte[] stub = patches ? Encode.stub(arch, o.action, v == null ? 0 : Values.bits(v)) : null;
            int size = o.expect != null ? o.expect.length : 4;
            Target target = target(f, o.function, rt, build, size, cache);
            if (patches) {
                if (stub.length > o.expect.length) {
                    throw new Refused(o.value instanceof Model.ValueRef ? "value-out-of-range" : "invalid:override.expect");
                }
                if (!target.hasPath()) {
                    checkRewritten(rt, target.base, stub.length);
                    checkExpect(rt, target.base, o.expect, null);
                }
            }
            Plan out = new Plan("override", index, op, Collections.singletonList(target));
            out.data = stub;
            out.expect = o.expect;
            out.action = o.action;
            out.value = v;
            out.pathExpect = patches && target.hasPath();
            return out;
        }
        if (op instanceof Model.Read) {
            Model.Read r = (Model.Read) op;
            Target target = target(f, r.at, rt, build, Values.typeSize(r.type), cache);
            Plan out = new Plan("read", index, op, Collections.singletonList(target));
            out.type = r.type;
            out.label = r.label;
            return out;
        }
        Model.Condition cond = (Model.Condition) op;
        List<Target> targets = new ArrayList<>();
        targets.add(target(f, cond.at, rt, build, Values.typeSize(cond.type), cache));
        Object v = cond.value;
        if (v instanceof Model.ValueRef) {
            v = value(c, (Model.ValueRef) v, choices);
        } else if (v instanceof Model.Place) {
            Model.Place p = (Model.Place) v;
            Target other = target(f, p.at, rt, build, Values.typeSize(p.type), cache);
            targets.add(other);
            v = other;
        }
        Plan out = new Plan("when", index, op, Collections.unmodifiableList(targets));
        out.type = cond.type;
        out.value = v;
        return out;
    }

    /** A value as a whole number, as Python's int() makes one: a float loses its fraction. */
    private static Object whole(Object v) throws Refused {
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1L : 0L;
        }
        if (v instanceof Double) {
            double d = (Double) v;
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new Refused("value-out-of-range");
            }
            return Values.normalize(new BigDecimal(d).toBigInteger());
        }
        return v;
    }

    /** Whether size bytes from offset lie inside a range of the given length, all unsigned. */
    private static boolean inside(long offset, long need, long length) {
        return Long.compareUnsigned(offset, length) <= 0 && Long.compareUnsigned(length - offset, need) >= 0;
    }

    /** The place of a location's base (section 6.2), checked, and the pointer path that follows it. */
    private static Target target(MncfFile f, Model.Location loc, Runtime rt, String build, int size,
            Map<SearchKey, List<Long>> cache) throws Refused {
        Platform plat = f.platform();
        int pointer = plat.pointerSize();
        int need = loc.hasPath() ? pointer : size;
        Place place;
        if (loc.kind.equals("address")) {
            place = new Place(loc.address, null, null, null);
            if (rt.read(loc.address, need) == null) {
                throw new Refused("outside");
            }
        } else if (loc.kind.equals("module") || loc.kind.equals("module-builds") || loc.kind.equals("pattern")) {
            Module m = rt.modules().get(loc.module);
            if (m == null) {
                throw new Refused("module-unknown");
            }
            long off;
            boolean negative = false;
            if (loc.kind.equals("module")) {
                off = loc.offset;
            } else if (loc.kind.equals("module-builds")) {
                if (build == null || !loc.offsets.containsKey(build)) {
                    throw new Refused("build-unknown");
                }
                off = loc.offsets.get(build);
            } else {
                long found = search(m, loc, cache);
                off = found + loc.offset;
                negative = loc.offset < 0 && off < 0;
            }
            if (negative || (m.size() != null && !inside(off, need, m.size()))) {
                throw new Refused("outside");
            }
            place = new Place(m.base() + off, loc.module, off, m);
        } else {
            long[] r = rt.regionOrNull(loc.region);
            if (r == null) {
                throw new Refused("region-unknown");
            }
            long off;
            if (loc.kind.equals("region")) {
                off = loc.offset;
            } else {
                if (build == null || !loc.offsets.containsKey(build)) {
                    throw new Refused("build-unknown");
                }
                off = loc.offsets.get(build);
            }
            if (!inside(off, need, r[1])) {
                throw new Refused("outside");
            }
            place = new Place(r[0] + off, null, null, null);
        }
        return new Target(place, loc.hasPath() ? loc.path : null, size);
    }

    /**
     * The place a target names now: its base, or where its pointer path leads. Null where the
     * path does not resolve at the moment: a null pointer, or a pointer or the place outside
     * guest memory.
     */
    static Place follow(Platform plat, Target target, GuestMemory memory) {
        if (!target.hasPath()) {
            return target.base;
        }
        int pointer = plat.pointerSize();
        long address = target.base.address;
        long mask = pointer >= 8 ? -1L : (1L << (8 * pointer)) - 1;
        for (long step : target.path) {
            byte[] raw = memory.read(address, pointer);
            if (raw == null || isZero(raw)) {
                return null;
            }
            address = (number(raw, plat.bigEndian()) + step) & mask;
        }
        if (memory.read(address, target.size) == null) {
            return null;
        }
        return new Place(address, null, null, null);
    }

    /** The places of every target of a planned operation, or null if one of them does not resolve now. */
    static List<Place> followAll(Platform plat, Plan plan, GuestMemory memory) {
        List<Place> places = new ArrayList<>();
        for (Target t : plan.targets) {
            Place p = follow(plat, t, memory);
            if (p == null) {
                return null;
            }
            places.add(p);
        }
        return places;
    }

    private static boolean isZero(byte[] raw) {
        for (byte b : raw) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static long number(byte[] raw, boolean bigEndian) {
        long v = 0;
        int n = Math.min(raw.length, 8);
        for (int i = 0; i < n; i++) {
            int b = raw[bigEndian ? raw.length - 1 - i : i] & 0xFF;
            v |= (long) b << (8 * i);
        }
        return v;
    }

    private static long search(Module m, Model.Location loc, Map<SearchKey, List<Long>> cache) throws Refused {
        SearchKey key = new SearchKey(m, loc.find, loc.section);
        List<Long> found = cache.get(key);
        if (found == null) {
            found = findAll(m, loc.find, loc.section, 2);
            cache.put(key, found);
        }
        if (found.isEmpty()) {
            throw new Refused("pattern-not-found");
        }
        if (found.size() > 1) {
            throw new Refused("pattern-ambiguous");
        }
        return found.get(0);
    }

    /**
     * Returns every module offset where a pattern matches, within the searched sections, up to
     * a limit. Matches may overlap, and a match lies inside one section. Without a section, the
     * code and rodata sections are searched, in that order.
     *
     * @param pattern a pattern such as "68 ?? 00 B9": at least four items, fixed bytes at both ends
     * @param section code, rodata or data, or null for code and rodata
     * @param limit the most matches to return, or 0 for all
     * @throws IllegalArgumentException if the pattern is not valid
     */
    public static List<Long> findAll(Module module, String pattern, String section, int limit) {
        int[] items = Values.parsePattern(pattern);
        if (items == null) {
            throw new IllegalArgumentException("not a valid pattern: " + pattern);
        }
        return findAll(module, items, section, limit);
    }

    static List<Long> findAll(Module m, int[] items, String section, int limit) {
        List<Long> found = new ArrayList<>();
        // Sections that overlap count a place once.
        Set<Integer> seen = new HashSet<>();
        byte[] data = m.image();
        if (data == null) {
            return found;
        }
        // The longest run of fixed bytes is searched for, with a Horspool skip table, and
        // each place where it lies is then checked against the whole pattern.
        int anchorAt = 0;
        int anchorLength = 0;
        for (int i = 0; i < items.length; ) {
            if (items[i] < 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < items.length && items[j] >= 0) {
                j++;
            }
            if (j - i > anchorLength) {
                anchorAt = i;
                anchorLength = j - i;
            }
            i = j;
        }
        byte[] anchor = new byte[anchorLength];
        for (int k = 0; k < anchorLength; k++) {
            anchor[k] = (byte) items[anchorAt + k];
        }
        int[] shift = new int[256];
        Arrays.fill(shift, anchorLength);
        for (int k = 0; k < anchorLength - 1; k++) {
            shift[anchor[k] & 0xFF] = anchorLength - 1 - k;
        }
        int lastAnchorByte = anchor[anchorLength - 1];
        int n = items.length;
        for (long[] range : m.searchRanges(section)) {
            long start = Math.max(0, Math.min(range[0], data.length));
            long end = Math.max(0, Math.min(range[1], data.length));
            if (end - start < n) {
                continue;
            }
            int q = (int) start + anchorAt;
            int last = (int) end - n + anchorAt;
            while (q <= last) {
                byte b = data[q + anchorLength - 1];
                if (b == lastAnchorByte && headMatches(data, q, anchor) && matches(data, q - anchorAt, items)
                        && seen.add(q - anchorAt)) {
                    found.add((long) (q - anchorAt));
                    if (limit > 0 && found.size() >= limit) {
                        return found;
                    }
                }
                int next = q + shift[b & 0xFF];
                if (next < q) {
                    break;
                }
                q = next;
            }
        }
        return found;
    }

    /** Whether the run's bytes but its last, which the caller has checked, lie at the position. */
    private static boolean headMatches(byte[] data, int at, byte[] run) {
        for (int k = 0; k < run.length - 1; k++) {
            if (data[at + k] != run[k]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(byte[] data, int at, int[] items) {
        for (int k = 0; k < items.length; k++) {
            if (items[k] >= 0 && (data[at + k] & 0xFF) != items[k]) {
                return false;
            }
        }
        return true;
    }

    /** Refuses a change over code the runtime rewrote for its own reasons (section 11.3). */
    private static void checkRewritten(Runtime rt, Place place, int length) throws Refused {
        if (place.module != null && rt.wasRewritten(place.module, place.offset, length)) {
            throw new Refused("runtime:rewritten");
        }
    }

    private static void checkExpect(Runtime rt, Place place, byte[] expect, byte[] data) throws Refused {
        byte[] actual;
        if (place.inModule != null && place.inModule.image() != null) {
            byte[] image = place.inModule.image();
            long start = place.offset;
            if (Long.compareUnsigned(start, image.length) >= 0) {
                actual = new byte[0];
            } else {
                int s = (int) start;
                int e = (int) Math.min(image.length, s + (long) expect.length);
                actual = Arrays.copyOfRange(image, s, e);
            }
        } else {
            actual = rt.read(place.address, expect.length);
            if (actual == null) {
                throw new Refused("outside");
            }
        }
        if (!Arrays.equals(actual, expect) && (data == null || !Arrays.equals(actual, data))) {
            throw new Refused("expect-mismatch");
        }
    }

    // Relations between cheats (section 9)

    /** The cheat and every cheat it requires, directly or not, in file order. */
    private static List<String> closure(MncfFile f, String id) {
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.push(id);
        while (!todo.isEmpty()) {
            String x = todo.pop();
            if (!seen.add(x)) {
                continue;
            }
            for (String r : f.cheat(x).requires()) {
                todo.push(r);
            }
        }
        List<String> out = new ArrayList<>();
        for (Cheat c : f.cheats()) {
            if (seen.contains(c.id())) {
                out.add(c.id());
            }
        }
        return out;
    }

    /** The results after the relations between cheats (section 7.8), from each cheat's own result. */
    static Map<String, Result> relations(MncfFile f, Map<String, Result> own) {
        Map<String, Result> results = new LinkedHashMap<>(own);
        relate(f, results);
        return results;
    }

    private static void relate(MncfFile f, Map<String, Result> results) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Cheat c : f.cheats()) {
                if (results.get(c.id()).state.equals(CheatResult.REFUSED)) {
                    continue;
                }
                for (String req : c.requires()) {
                    if (results.get(req).state.equals(CheatResult.REFUSED)) {
                        results.put(c.id(), refused(c.id(), "requires:" + req));
                        changed = true;
                        break;
                    }
                }
            }
        }
        for (Cheat c : f.cheats()) {
            if (results.get(c.id()).state.equals(CheatResult.REFUSED)) {
                continue;
            }
            String conflict = conflictIn(f, closure(f, c.id()), c.id());
            if (conflict != null) {
                results.put(c.id(), refused(c.id(), "excludes:" + conflict));
            }
        }
    }

    /** A cheat of the group that can never be on together with the rest: what excludes:&lt;id&gt; names. */
    private static String conflictIn(MncfFile f, List<String> group, String own) {
        Set<String> members = new HashSet<>(group);
        for (String x : group) {
            for (String y : f.cheat(x).excludes()) {
                if (members.contains(y)) {
                    return !y.equals(own) ? y : x;
                }
            }
        }
        for (int i = 0; i < group.size(); i++) {
            String radio = f.cheat(group.get(i)).radio();
            if (radio == null) {
                continue;
            }
            for (int j = i + 1; j < group.size(); j++) {
                String y = group.get(j);
                if (radio.equals(f.cheat(y).radio())) {
                    return !y.equals(own) ? y : group.get(i);
                }
            }
        }
        return null;
    }

    private static boolean conflicts(MncfFile f, String a, String b) {
        Cheat ca = f.cheat(a);
        Cheat cb = f.cheat(b);
        return !a.equals(b) && (ca.excludes().contains(b) || cb.excludes().contains(a)
                || (ca.radio() != null && ca.radio().equals(cb.radio())));
    }

    /**
     * Switches on the cheats that are on when the file is loaded (section 11.2) and gives every
     * cheat its state. Returns the cheats that are on.
     */
    private static List<String> switchStates(MncfFile f, Map<String, Result> results, Choices choices, Runtime rt) {
        List<String> on = new ArrayList<>();
        for (Cheat c : f.cheats()) {
            if (!c.isMaster() && ((c.isDefault() && !choices.off().contains(c.id())) || choices.on().contains(c.id()))
                    && !results.get(c.id()).state.equals(CheatResult.REFUSED)) {
                on = switchOn(f, on, c.id());
            }
        }
        for (Cheat c : f.cheats()) {
            Result r = results.get(c.id());
            if (r.state.equals(CheatResult.REFUSED)) {
                continue;
            }
            if (!on.contains(c.id())) {
                r.state = CheatResult.OFF;
            } else if (r.pathBroken) {
                r.state = CheatResult.WAITING;
                r.reason = "path-broken";
            } else if (r.uses.equals("core") && waitsForButtons(c, rt.held(), false)) {
                r.state = CheatResult.WAITING;
            } else {
                r.state = CheatResult.ON;
            }
        }
        return on;
    }

    /** Whether a cheat that is on waits for buttons: held buttons it needs, or the first press of an action. */
    static boolean waitsForButtons(Cheat c, java.util.Collection<String> held, boolean pressedOnce) {
        if (c.hold() != null && !held.containsAll(Py.split(c.hold(), '+'))) {
            return true;
        }
        return c.run().equals("action") && !pressedOnce;
    }

    /**
     * The cheats that are on after x is switched on (section 7.8): x and the cheats it requires
     * are on, and every cheat that conflicts with one of them is off, with the cheats that
     * require it.
     */
    static List<String> switchOn(MncfFile f, List<String> on, String x) {
        List<String> group = closure(f, x);
        List<String> kept = new ArrayList<>();
        for (String y : on) {
            boolean clash = false;
            for (String g : group) {
                clash |= conflicts(f, y, g);
            }
            if (!clash) {
                kept.add(y);
            }
        }
        List<String> out = new ArrayList<>(dropUnmet(f, kept));
        for (String g : group) {
            if (!out.contains(g)) {
                out.add(g);
            }
        }
        return dropMasters(f, out);
    }

    /**
     * The cheats that are on after x is switched off: without x, the cheats that require it, and
     * the master cheats that nothing on needs any more.
     */
    static List<String> switchOff(MncfFile f, List<String> on, String x) {
        List<String> kept = new ArrayList<>(on);
        kept.remove(x);
        return dropMasters(f, dropUnmet(f, kept));
    }

    private static List<String> dropMasters(MncfFile f, List<String> on) {
        Set<String> needed = new HashSet<>();
        for (String y : on) {
            if (!f.cheat(y).isMaster()) {
                needed.addAll(closure(f, y));
            }
        }
        List<String> kept = new ArrayList<>();
        for (String y : on) {
            if (needed.contains(y)) {
                kept.add(y);
            }
        }
        return kept;
    }

    private static List<String> dropUnmet(MncfFile f, List<String> on) {
        while (true) {
            List<String> kept = new ArrayList<>();
            for (String y : on) {
                if (on.containsAll(f.cheat(y).requires())) {
                    kept.add(y);
                }
            }
            if (kept.equals(on)) {
                return on;
            }
            on = kept;
        }
    }
}
