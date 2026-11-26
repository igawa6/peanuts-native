package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The cheats of a loaded file in one runtime, from pass to pass (sections 7.2, 7.3, 7.9, 11.3
 * and 11.5). Between passes the caller says what the player does; {@link #tick} runs one pass
 * and returns the changes to make, every cheat's state and what the monitors read.
 *
 * <pre>{@code
 * Engine engine = new Engine(file, runtime, Choices.builder().on("money").build(), 60);
 * engine.switchCheat("speed", true);           // what the player does between passes
 * engine.setValue("money.count", 5000);
 * engine.hold("ZL");
 * Pass pass = engine.tick(memory, null);        // one pass
 * for (Change c : pass.changes()) {
 *     // patch, restore, hook, unhook, write, raw: all code changes in one stop
 * }
 * engine.stop();                                // the changes that put back every place
 * }</pre>
 *
 * <p>The engine reads guest memory only through the reader it is given, and changes nothing
 * itself. It is not thread-safe: one thread calls it.
 */
public final class Engine {

    private static final Comparator<Long> UNSIGNED = new Comparator<Long>() {
        @Override
        public int compare(Long a, Long b) {
            return Long.compareUnsigned(a, b);
        }
    };

    /** What the engine keeps for one cheat between passes. */
    private static final class Run {
        String refused;
        String state;
        String reason;
        long passes;
        Double started;
        boolean pressed;
        boolean heldBefore;
        Map<Integer, byte[]> last = new HashMap<>();
        boolean active;
        /** Operation index to the address and bytes of an applied patch or stub. */
        Map<Integer, Object[]> covers = new HashMap<>();
        /** Operation index to the address, action and value of a hook. */
        Map<Integer, Object[]> hooks = new HashMap<>();

        void restart() {
            passes = 0;
            started = null;
            pressed = false;
            heldBefore = false;
            last = new HashMap<>();
        }
    }

    /** What one cheat does in a pass. */
    private static final class Work {
        boolean active;
        boolean keep;
        Map<Integer, List<Evaluator.Place>> places;
        List<Change> out = new ArrayList<>();
        List<Shown> shown;
        boolean acted;
    }

    /** Guest memory as one pass reads it: each place once. */
    private static final class Reads implements GuestMemory {
        private final GuestMemory memory;
        private final Map<List<Long>, byte[]> seen = new HashMap<>();

        Reads(GuestMemory memory) {
            this.memory = memory;
        }

        @Override
        public byte[] read(long address, int size) {
            List<Long> key = Arrays.asList(address, (long) size);
            if (!seen.containsKey(key)) {
                byte[] raw = memory.read(address, size);
                seen.put(key, raw == null ? null : raw.clone());
            }
            return seen.get(key);
        }
    }

    private final MncfFile file;
    private final Runtime runtime;
    private final double rate;
    private final String build;
    private final Evaluation evaluation;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<Evaluator.SearchKey, List<Long>> cache = new HashMap<>();
    private final Map<String, Evaluator.Result> own;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private List<String> on;
    private Set<String> held;
    private Set<String> heldBefore;
    private List<Set<String>> presses = new ArrayList<>();
    private long number;
    private final TreeMap<Long, Integer> kept = new TreeMap<>(UNSIGNED);
    private final TreeMap<Long, Integer> now = new TreeMap<>(UNSIGNED);
    private TreeMap<Long, Object[]> hooked = new TreeMap<>(UNSIGNED);

    /**
     * Loads a file's cheats into an engine, as {@link Evaluator#evaluate} does.
     *
     * @param choices what the player chose before, or null
     * @param rate the passes per second, for timed cheats when the caller gives no time
     * @throws IllegalArgumentException if the rate is not above 0
     */
    public Engine(MncfFile file, Runtime runtime, Choices choices, double rate) {
        if (!(rate > 0)) {
            throw new IllegalArgumentException("rate is the number of passes per second, above 0");
        }
        if (choices == null) {
            choices = Choices.none();
        }
        this.file = file;
        this.runtime = runtime;
        this.rate = rate;
        values.putAll(choices.values());
        Evaluator.Loaded loaded = Evaluator.load(file, runtime, choices, cache);
        this.evaluation = loaded.evaluation;
        this.build = loaded.evaluation.build();
        this.own = new LinkedHashMap<>(loaded.own);
        this.on = loaded.on;
        for (CheatResult r : loaded.evaluation.cheats()) {
            Run run = new Run();
            run.refused = r.state().equals(CheatResult.REFUSED) ? r.reason() : null;
            run.state = r.state();
            run.reason = r.reason();
            runs.put(r.id(), run);
        }
        held = new HashSet<>(runtime.held());
        heldBefore = new HashSet<>(runtime.held());
    }

    /** Loads a file's cheats into an engine at 60 passes per second. */
    public Engine(MncfFile file, Runtime runtime, Choices choices) {
        this(file, runtime, choices, 60);
    }

    /** Returns the evaluation of the file when it was loaded. */
    public Evaluation evaluation() {
        return evaluation;
    }

    /** Returns the key of the build that runs, or null. */
    public String build() {
        return build;
    }

    // What the player does between passes

    /**
     * Switches a cheat on or off as the player does (section 7.8). A master cheat is not a
     * switch, and a refused cheat cannot be switched on: both stay as they are.
     *
     * @return whether the cheat is on afterwards
     * @throws IllegalArgumentException if the file has no such cheat
     */
    public boolean switchCheat(String id, boolean switchOn) {
        Cheat c = cheat(id);
        Run run = runs.get(id);
        if (c.isMaster() || run.refused != null) {
            return on.contains(id);
        }
        if (switchOn && !on.contains(id)) {
            setOn(Evaluator.switchOn(file, on, id), null);
        } else if (!switchOn && on.contains(id)) {
            setOn(Evaluator.switchOff(file, on, id), null);
        }
        return on.contains(id);
    }

    /**
     * Sets a value the player chose. A value the player cannot choose refuses the cheat with
     * value-out-of-range, until a value that fits is set.
     *
     * @param key the cheat's id and the value's id, joined by a dot: "money.count"
     * @param value a whole number, a float or a Boolean
     * @throws IllegalArgumentException if the cheat has no such value, or for another kind of value
     */
    public void setValue(String key, Object value) {
        int dot = key.indexOf('.');
        String id = dot < 0 ? key : key.substring(0, dot);
        Cheat c = cheat(id);
        if (dot < 0 || !c.values().containsKey(key.substring(dot + 1))) {
            throw new IllegalArgumentException("cheat " + id + " has no value " + (dot < 0 ? "" : key.substring(dot + 1)));
        }
        values.put(key, Choices.number(value));
        Choices.Builder b = Choices.builder();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            b.value(e.getKey(), e.getValue());
        }
        own.put(id, Evaluator.ownResult(file, c, runtime, build, b.build(), cache));
        refresh();
    }

    /** Sets the buttons the player holds now. */
    public void hold(Collection<String> buttons) {
        held = new HashSet<>(buttons);
    }

    /** Sets the buttons the player holds now. */
    public void hold(String... buttons) {
        hold(Arrays.asList(buttons));
    }

    /**
     * Says that the player pressed these buttons together since the last pass, for a press
     * shorter than a pass.
     *
     * @param buttons names joined by "+", such as "ZL+R"
     */
    public void press(String buttons) {
        presses.add(new HashSet<>(Py.split(buttons, '+')));
    }

    /** Says that the player pressed these buttons together since the last pass. */
    public void press(Collection<String> buttons) {
        presses.add(new HashSet<>(buttons));
    }

    // What the engine says

    /** Returns every cheat's state now, in file order. */
    public List<CheatState> states() {
        List<CheatState> out = new ArrayList<>();
        for (Cheat c : file.cheats()) {
            Run run = runs.get(c.id());
            out.add(new CheatState(c.id(), run.state, run.reason));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns a cheat's state now.
     *
     * @throws IllegalArgumentException if the file has no such cheat
     */
    public CheatState state(String id) {
        cheat(id);
        Run run = runs.get(id);
        return new CheatState(id, run.state, run.reason);
    }

    // Passes

    /**
     * Runs one pass and returns what to change.
     *
     * @param memory guest memory as it is at this pass, or null for the runtime's
     * @param time the time of the pass in seconds, or null: then the passes before it divided by
     *     the rate. Give the time at every pass or at none.
     */
    public Pass tick(GuestMemory memory, Double time) {
        number++;
        double t = time != null ? time : (number - 1) / rate;
        Reads read = new Reads(memory != null ? memory : Evaluator.runtimeMemory(runtime));
        Set<Set<String>> pressed = pressed();
        heldBefore = new HashSet<>(held);
        presses = new ArrayList<>();

        for (Cheat c : file.cheats()) {
            if (c.toggle() != null && !c.isMaster() && runs.get(c.id()).refused == null
                    && pressed.contains(buttons(c.toggle()))) {
                switchCheat(c.id(), !on.contains(c.id()));
            }
        }
        for (Cheat c : file.cheats()) {
            Run run = runs.get(c.id());
            if (c.run().equals("timed") && on.contains(c.id())) {
                if (run.started == null) {
                    run.started = t;
                }
                if (Values.lessOrEqual(c.duration(), t - run.started)) {
                    setOn(Evaluator.switchOff(file, on, c.id()), c.id());
                }
            }
        }

        Map<String, Work> work = new LinkedHashMap<>();
        for (Cheat c : file.cheats()) {
            if (on.contains(c.id()) && runs.get(c.id()).refused == null) {
                work.put(c.id(), work(c, read, pressed));
            }
        }
        Map<List<Object>, byte[]> checked = check(work, read);
        for (Cheat c : file.cheats()) {
            if (work.containsKey(c.id()) && (!on.contains(c.id()) || runs.get(c.id()).refused != null)) {
                work.remove(c.id());
            }
        }
        List<Change> changes = code(work, checked);
        Map<String, List<Shown>> shown = new LinkedHashMap<>();
        for (Cheat c : file.cheats()) {
            Work w = work.get(c.id());
            if (w == null) {
                continue;
            }
            changes.addAll(w.out);
            if (w.shown != null) {
                shown.put(c.id(), w.shown);
            }
        }
        for (Cheat c : file.cheats()) {
            Work w = work.get(c.id());
            if (c.run().equals("once") && w != null && w.acted && on.contains(c.id())) {
                setOn(Evaluator.switchOff(file, on, c.id()), c.id());
            }
        }
        return new Pass(number, changes, states(), shown);
    }

    /**
     * Switches every cheat off and returns the changes that put back every place the cheats
     * changed, as before a file is loaded again or the game stops (section 11.3).
     */
    public List<Change> stop() {
        setOn(new ArrayList<String>(), null);
        return Collections.unmodifiableList(code(new LinkedHashMap<String, Work>(),
                new HashMap<List<Object>, byte[]>()));
    }

    // The parts of a pass

    private static Set<String> buttons(String text) {
        return new HashSet<>(Py.split(text, '+'));
    }

    /** The button combinations of the file that were pressed since the last pass. */
    private Set<Set<String>> pressed() {
        Set<Set<String>> combos = new LinkedHashSet<>();
        for (Cheat c : file.cheats()) {
            for (String text : new String[] {c.toggle(), c.press()}) {
                if (text != null) {
                    combos.add(buttons(text));
                }
            }
        }
        Set<Set<String>> out = new HashSet<>();
        for (Set<String> combo : combos) {
            boolean now = held.containsAll(combo) && !heldBefore.containsAll(combo);
            for (Set<String> p : presses) {
                now |= p.containsAll(combo);
            }
            if (now) {
                out.add(combo);
            }
        }
        return out;
    }

    /** What a cheat that is on does in this pass, and its state after it. */
    private Work work(Cheat c, Reads read, Set<Set<String>> pressed) {
        Run run = runs.get(c.id());
        Evaluator.Result result = own.get(c.id());
        run.passes++;
        if (result.uses.equals("raw")) {
            run.state = CheatResult.ON;
            run.reason = "";
            Work w = new Work();
            w.out.add(Change.raw(c.id(), result.rawEntry));
            w.acted = true;
            return w;
        }
        if ((run.passes - 1) % c.interval() != 0) {
            Work w = new Work();
            w.active = run.active;
            w.keep = true;
            return w;
        }
        boolean pressing = c.run().equals("action") && pressed.contains(buttons(c.press()));
        if (Evaluator.waitsForButtons(c, held, run.pressed || pressing)) {
            return waiting(run, "");
        }
        Platform plat = file.platform();
        Map<Integer, List<Evaluator.Place>> places = new HashMap<>();
        for (Evaluator.Plan p : result.plans) {
            List<Evaluator.Place> ps = Evaluator.followAll(plat, p, read);
            if (ps == null) {
                return waiting(run, "path-broken");
            }
            places.put(p.index, ps);
        }
        Map<Integer, byte[]> last = new HashMap<>();
        Boolean holds = test(c, result.plans, places, read, last);
        if (holds == null) {
            return waiting(run, "path-broken");
        }
        boolean acts = holds && (c.trigger().equals("while") || !run.heldBefore)
                && (!c.run().equals("action") || pressing);
        List<Change> out = new ArrayList<>();
        List<Shown> shown = null;
        if (acts) {
            boolean big = plat.bigEndian();
            for (Evaluator.Plan p : result.plans) {
                long address = places.get(p.index).get(0).address;
                if (p.kind.equals("write")) {
                    if (p.expect != null) {
                        byte[] there = read.read(address, p.expect.length);
                        if (there == null) {
                            return waiting(run, "path-broken");
                        }
                        if (!Arrays.equals(there, p.expect)) {
                            continue;
                        }
                    }
                    out.add(Change.write(c.id(), address, p.data));
                } else if (p.kind.equals("read")) {
                    byte[] raw = read.read(address, Values.typeSize(p.type));
                    if (raw == null) {
                        return waiting(run, "path-broken");
                    }
                    if (shown == null) {
                        shown = new ArrayList<>();
                    }
                    shown.add(new Shown(p.label, p.type, Values.decode(p.type, raw, big)));
                }
            }
        }
        run.heldBefore = holds;
        run.last = last;
        if (acts && c.run().equals("action")) {
            run.pressed = true;
        }
        run.state = c.run().equals("action") && !run.pressed ? CheatResult.WAITING : CheatResult.ON;
        run.reason = "";
        Work w = new Work();
        w.active = holds && (!c.run().equals("action") || run.pressed);
        w.places = places;
        w.out = out;
        w.shown = shown;
        w.acted = acts;
        return w;
    }

    private static Work waiting(Run run, String reason) {
        run.state = CheatResult.WAITING;
        run.reason = reason;
        return new Work();
    }

    /**
     * Whether the cheat's conditions hold now, with the bytes its changed conditions read put
     * into last; null if a place cannot be read. Every condition is tested, so that the result
     * does not depend on the order of the tests.
     */
    private Boolean test(Cheat c, List<Evaluator.Plan> plans, Map<Integer, List<Evaluator.Place>> places, Reads read,
            Map<Integer, byte[]> last) {
        boolean big = file.platform().bigEndian();
        Run run = runs.get(c.id());
        List<Boolean> results = new ArrayList<>();
        for (Evaluator.Plan p : plans) {
            if (!p.kind.equals("when")) {
                continue;
            }
            Model.Condition cond = (Model.Condition) p.op;
            byte[] raw = read.read(places.get(p.index).get(0).address, Values.typeSize(p.type));
            if (raw == null) {
                return null;
            }
            if (cond.op.equals("changed")) {
                last.put(p.index, raw);
                byte[] before = run.last.get(p.index);
                results.add(before != null && !Arrays.equals(before, raw));
                continue;
            }
            Object v = p.value;
            if (v instanceof Evaluator.Target) {
                String type = ((Model.Place) cond.value).type;
                byte[] other = read.read(places.get(p.index).get(1).address, Values.typeSize(type));
                if (other == null) {
                    return null;
                }
                v = Values.decode(type, other, big);
            }
            results.add(holds(cond.op, Values.decode(p.type, raw, big), v));
        }
        if (results.isEmpty()) {
            return true;
        }
        if (c.match().equals("all")) {
            return !results.contains(false);
        }
        return results.contains(true);
    }

    /** Whether a condition's operator holds between the value read and the value it compares with. */
    private static boolean holds(String op, Object a, Object v) {
        if (v instanceof Boolean) {
            v = ((Boolean) v) ? 1L : 0L;
        }
        switch (op) {
            case "between":
                Model.Between b = (Model.Between) v;
                return Values.lessOrEqual(b.low, a) && Values.lessOrEqual(a, b.high);
            case "==":
                return Values.same(a, v);
            case "!=":
                return !Values.same(a, v);
            case "<":
                return Values.less(a, v);
            case "<=":
                return Values.lessOrEqual(a, v);
            case ">":
                return Values.less(v, a);
            case ">=":
                return Values.lessOrEqual(v, a);
            case "&":
                return Values.big(a).and(Values.big(v)).signum() != 0;
            default:
                return Values.big(a).and(Values.big(v)).signum() == 0;
        }
    }

    /**
     * Checks expect for every patch and override stub that covers a place it did not cover in
     * the pass before (section 7.4), and refuses the cheats where it differs. Returns the
     * original bytes found, by cheat and operation.
     */
    private Map<List<Object>, byte[]> check(Map<String, Work> work, Reads read) {
        Map<List<Object>, byte[]> checked = new HashMap<>();
        List<String[]> refused = new ArrayList<>();
        for (Cheat c : file.cheats()) {
            Work w = work.get(c.id());
            if (w == null || !w.active || w.keep) {
                continue;
            }
            Run run = runs.get(c.id());
            for (Evaluator.Plan p : own.get(c.id()).plans) {
                if (!(p.kind.equals("patch") || p.kind.equals("override")) || p.data == null) {
                    continue;
                }
                long address = w.places.get(p.index).get(0).address;
                Object[] cover = run.covers.get(p.index);
                if (cover != null && (Long) cover[0] == address && ((byte[]) cover[1]).length == p.data.length) {
                    continue;
                }
                int n = p.expect.length;
                byte[] there = null;
                boolean all = true;
                for (int i = 0; i < n; i++) {
                    all &= kept.containsKey(address + i);
                }
                if (!all) {
                    there = read.read(address, n);
                    if (there == null) {
                        refused.add(new String[] {c.id(), "outside"});
                        break;
                    }
                }
                byte[] found = new byte[n];
                for (int i = 0; i < n; i++) {
                    Integer k = kept.get(address + i);
                    found[i] = k != null ? (byte) (int) k : there[i];
                }
                if (!Arrays.equals(found, p.expect) && (p.kind.equals("override") || !Arrays.equals(found, p.data))) {
                    refused.add(new String[] {c.id(), "expect-mismatch"});
                    break;
                }
                checked.put(Arrays.<Object>asList(c.id(), p.index), found);
            }
        }
        for (String[] r : refused) {
            own.put(r[0], Evaluator.refusedResult(r[0], r[1]));
        }
        if (!refused.isEmpty()) {
            refresh();
        }
        return checked;
    }

    /**
     * The code changes of a pass (section 11.5). Every code byte that a cheat that is active
     * covers gets that cheat's byte, the last such cheat in file order winning; every other byte
     * the engine changed gets its original back. A patch or stub is written whole when one of
     * its bytes is not what it should be; the bytes no cheat covers any more are put back whole,
     * in runs, except a run whose bytes all hold their originals already.
     */
    private List<Change> code(Map<String, Work> work, Map<List<Object>, byte[]> checked) {
        Map<Long, Integer> want = new HashMap<>();
        TreeMap<Long, Object[]> hooks = new TreeMap<>(UNSIGNED);
        Map<Long, Integer> newKept = new HashMap<>();
        Map<Long, Integer> newNow = new HashMap<>();
        List<long[]> ranges = new ArrayList<>();
        for (Cheat c : file.cheats()) {
            Work w = work.get(c.id());
            Run run = runs.get(c.id());
            if (w == null || !w.active) {
                run.active = false;
                run.covers = new HashMap<>();
                run.hooks = new HashMap<>();
                continue;
            }
            Map<Integer, Object[]> covers = new HashMap<>();
            Map<Integer, Object[]> hookedHere = new HashMap<>();
            for (Evaluator.Plan p : own.get(c.id()).plans) {
                if (!(p.kind.equals("patch") || p.kind.equals("override"))) {
                    continue;
                }
                long address;
                byte[] data;
                if (w.keep) {
                    // Between two tests: as the last test left it.
                    if (p.data == null) {
                        Object[] h = run.hooks.get(p.index);
                        if (h != null) {
                            hooks.put((Long) h[0], new Object[] {h[1], h[2]});
                            hookedHere.put(p.index, h);
                        }
                        continue;
                    }
                    Object[] cover = run.covers.get(p.index);
                    if (cover == null) {
                        continue;
                    }
                    address = (Long) cover[0];
                    data = (byte[]) cover[1];
                } else {
                    address = w.places.get(p.index).get(0).address;
                    data = p.data;
                }
                if (data == null) {
                    hooks.put(address, new Object[] {p.action, p.value});
                    hookedHere.put(p.index, new Object[] {address, p.action, p.value});
                    continue;
                }
                byte[] found = checked.get(Arrays.<Object>asList(c.id(), p.index));
                for (int i = 0; i < data.length; i++) {
                    long a = address + i;
                    want.put(a, data[i] & 0xFF);
                    if (!kept.containsKey(a) && !newKept.containsKey(a)) {
                        newKept.put(a, p.expect[i] & 0xFF);
                        newNow.put(a, (found != null ? found[i] : p.expect[i]) & 0xFF);
                    }
                }
                covers.put(p.index, new Object[] {address, data});
                ranges.add(new long[] {address, data.length});
            }
            run.active = true;
            run.covers = covers;
            run.hooks = hookedHere;
        }
        kept.putAll(newKept);
        now.putAll(newNow);

        TreeMap<Long, String> marked = new TreeMap<>(UNSIGNED);
        for (long[] range : ranges) {
            boolean differs = false;
            for (int i = 0; i < range[1]; i++) {
                long a = range[0] + i;
                differs |= !want.get(a).equals(now.get(a));
            }
            if (differs) {
                for (int i = 0; i < range[1]; i++) {
                    marked.put(range[0] + i, Change.PATCH);
                }
            }
        }
        for (Long a : kept.keySet()) {
            if (!want.containsKey(a)) {
                marked.put(a, Change.RESTORE);
            }
        }
        List<Object[]> code = new ArrayList<>();      // address, order, change
        long start = 0;
        String kind = null;
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        boolean differs = false;
        for (Map.Entry<Long, String> e : marked.entrySet()) {
            long a = e.getKey();
            int target = e.getValue().equals(Change.PATCH) ? want.get(a) : kept.get(a);
            if (kind != null && kind.equals(e.getValue()) && start + data.size() == a) {
                data.write(target);
            } else {
                if (kind != null && differs) {
                    code.add(new Object[] {start, 0, Change.code(kind, start, data.toByteArray())});
                }
                start = a;
                kind = e.getValue();
                data = new java.io.ByteArrayOutputStream();
                data.write(target);
                differs = false;
            }
            differs |= target != now.get(a);
        }
        if (kind != null && differs) {
            code.add(new Object[] {start, 0, Change.code(kind, start, data.toByteArray())});
        }
        for (Map.Entry<Long, String> e : marked.entrySet()) {
            long a = e.getKey();
            if (e.getValue().equals(Change.PATCH)) {
                now.put(a, want.get(a));
            } else {
                kept.remove(a);
                now.remove(a);
            }
        }
        Set<Long> addresses = new TreeSet<>(UNSIGNED);
        addresses.addAll(hooked.keySet());
        addresses.addAll(hooks.keySet());
        for (long a : addresses) {
            Object[] h = hooks.get(a);
            if (h != null) {
                if (!sameHook(hooked.get(a), h)) {
                    code.add(new Object[] {a, 1, Change.hook(a, (String) h[0], h[1])});
                }
            } else {
                code.add(new Object[] {a, 1, Change.unhook(a)});
            }
        }
        hooked = hooks;
        Collections.sort(code, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] x, Object[] y) {
                int c = Long.compareUnsigned((Long) x[0], (Long) y[0]);
                return c != 0 ? c : Integer.compare((Integer) x[1], (Integer) y[1]);
            }
        });
        List<Change> out = new ArrayList<>();
        for (Object[] item : code) {
            out.add((Change) item[2]);
        }
        return out;
    }

    private static boolean sameHook(Object[] a, Object[] b) {
        if (a == null || !Objects.equals(a[0], b[0])) {
            return false;
        }
        if (a[1] == null || b[1] == null) {
            return a[1] == null && b[1] == null;
        }
        return Values.same(a[1], b[1]);
    }

    // Switching

    /** Makes these the cheats that are on, and gives the ones that changed their state. */
    private void setOn(List<String> next, String done) {
        Set<String> before = new HashSet<>(on);
        on = next;
        for (Cheat c : file.cheats()) {
            Run run = runs.get(c.id());
            if (run.refused != null) {
                continue;
            }
            if (before.contains(c.id()) && !next.contains(c.id())) {
                run.state = CheatResult.OFF;
                run.reason = c.id().equals(done) ? "done" : "";
            } else if (!before.contains(c.id()) && next.contains(c.id())) {
                run.restart();
                boolean waits = own.get(c.id()).uses.equals("core") && Evaluator.waitsForButtons(c, held, false);
                run.state = waits ? CheatResult.WAITING : CheatResult.ON;
                run.reason = "";
            }
        }
    }

    /**
     * Gives every cheat the result of its relations after a cheat's own result changed, and
     * switches off the cheats that are refused now.
     */
    private void refresh() {
        Map<String, Evaluator.Result> results = Evaluator.relations(file, own);
        for (Cheat c : file.cheats()) {
            Evaluator.Result r = results.get(c.id());
            Run run = runs.get(c.id());
            if (r.state.equals(CheatResult.REFUSED)) {
                run.refused = r.reason;
                run.state = CheatResult.REFUSED;
                run.reason = r.reason;
            } else if (run.refused != null) {
                run.refused = null;
                run.state = CheatResult.OFF;
                run.reason = "";
            }
        }
        List<String> next = on;
        for (Cheat c : file.cheats()) {
            if (next.contains(c.id()) && runs.get(c.id()).refused != null) {
                next = Evaluator.switchOff(file, next, c.id());
            }
        }
        setOn(next, null);
    }

    private Cheat cheat(String id) {
        Cheat c = file.cheat(id);
        if (c == null) {
            throw new IllegalArgumentException("the file has no cheat " + id);
        }
        return c;
    }
}
