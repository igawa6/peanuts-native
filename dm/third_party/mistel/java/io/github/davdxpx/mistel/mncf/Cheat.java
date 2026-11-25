package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One cheat of a file, as it was read and checked (sections 7 to 9). Immutable.
 *
 * <p>When {@link #problem()} is not null, the cheat breaks a rule of the format and is refused
 * whatever the runtime; its fields after the one that broke the rule keep their defaults.
 */
public final class Cheat {

    private final int index;
    private final String id;
    private final Map<String, Object> table;
    private final String problem;
    private final String name;
    private final String about;
    private final String group;
    private final List<String> tags;
    private final boolean defaultOn;
    private final boolean master;
    private final String run;
    private final Number duration;
    private final String hold;
    private final String toggle;
    private final String press;
    private final List<String> requires;
    private final List<String> excludes;
    private final String radio;
    private final String match;
    private final String trigger;
    private final long interval;
    private final Map<String, ValueSpec> values;
    private final List<Model.Op> ops;
    private final List<Model.Op> coreOps;
    private final List<Model.Raw> raw;

    /** The fields of a cheat while the schema fills them in. */
    static final class Draft {
        int index;
        String id;
        Map<String, Object> table;
        String problem;
        String name;
        String about;
        String group;
        List<String> tags = new ArrayList<>();
        boolean defaultOn;
        boolean master;
        String run = "on";
        Number duration;
        String hold;
        String toggle;
        String press;
        List<String> requires = new ArrayList<>();
        List<String> excludes = new ArrayList<>();
        String radio;
        String match = "all";
        String trigger = "while";
        long interval = 1;
        Map<String, ValueSpec> values = new LinkedHashMap<>();
        List<Model.Op> ops = new ArrayList<>();
        List<Model.Raw> raw = new ArrayList<>();
    }

    Cheat(Draft d) {
        index = d.index;
        id = d.id;
        table = d.table;
        problem = d.problem;
        name = d.name;
        about = d.about;
        group = d.group;
        tags = Collections.unmodifiableList(new ArrayList<>(d.tags));
        defaultOn = d.defaultOn;
        master = d.master;
        run = d.run;
        duration = d.duration;
        hold = d.hold;
        toggle = d.toggle;
        press = d.press;
        requires = Collections.unmodifiableList(new ArrayList<>(d.requires));
        excludes = Collections.unmodifiableList(new ArrayList<>(d.excludes));
        radio = d.radio;
        match = d.match;
        trigger = d.trigger;
        interval = d.interval;
        values = Collections.unmodifiableMap(new LinkedHashMap<>(d.values));
        ops = Collections.unmodifiableList(new ArrayList<>(d.ops));
        List<Model.Op> core = new ArrayList<>();
        for (Model.Op op : d.ops) {
            if (!(op instanceof Model.Condition)) {
                core.add(op);
            }
        }
        coreOps = Collections.unmodifiableList(core);
        raw = Collections.unmodifiableList(new ArrayList<>(d.raw));
    }

    /** Returns the position of the cheat in the file, counted from 0. */
    public int index() {
        return index;
    }

    /** Returns the cheat's id. */
    public String id() {
        return id;
    }

    /** Returns the cheat's table as the file wrote it, unmodifiable. */
    public Map<String, Object> table() {
        return table;
    }

    /**
     * Returns why the cheat is refused whatever the runtime, or null: unknown-key:&lt;key&gt;,
     * extension-missing:&lt;name&gt;, invalid:&lt;key&gt; or invalid.
     */
    public String problem() {
        return problem;
    }

    /** Returns the name shown to players. */
    public String name() {
        return name;
    }

    /** Returns the description, or null. */
    public String about() {
        return about;
    }

    /** Returns the group path such as "Items/Money", or null. */
    public String group() {
        return group;
    }

    /** Returns the tags, such as touches_save. */
    public List<String> tags() {
        return tags;
    }

    /** Returns whether the cheat is on unless the player switches it off. */
    public boolean isDefault() {
        return defaultOn;
    }

    /** Returns whether this is a master cheat, on only while a cheat that needs it is on. */
    public boolean isMaster() {
        return master;
    }

    /** Returns how the cheat runs: on, once, action, timed or monitor. */
    public String run() {
        return run;
    }

    /** Returns the seconds a timed cheat runs, or null. */
    public Number duration() {
        return duration;
    }

    /** Returns the buttons to hold, joined by "+", or null. */
    public String hold() {
        return hold;
    }

    /** Returns the buttons that switch the cheat, joined by "+", or null. */
    public String toggle() {
        return toggle;
    }

    /** Returns the buttons that start an action, joined by "+", or null. */
    public String press() {
        return press;
    }

    /** Returns the ids of the cheats this one needs. */
    public List<String> requires() {
        return requires;
    }

    /** Returns the ids of the cheats that cannot be on with this one. */
    public List<String> excludes() {
        return excludes;
    }

    /** Returns the radio group, in which only one cheat can be on, or null. */
    public String radio() {
        return radio;
    }

    /** Returns how conditions combine: all or any. */
    public String match() {
        return match;
    }

    /** Returns when conditions fire: while or enter. */
    public String trigger() {
        return trigger;
    }

    /** Returns every how many frames the cheat runs. */
    public long interval() {
        return interval;
    }

    /** Returns the values the player sets, by id, in file order. */
    public Map<String, ValueSpec> values() {
        return values;
    }

    List<Model.Op> ops() {
        return ops;
    }

    List<Model.Op> coreOps() {
        return coreOps;
    }

    List<Model.Raw> raw() {
        return raw;
    }
}
