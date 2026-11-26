package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one pass did (section 11.5): its number, its changes in the order to make them, every
 * cheat's state after it in file order, and the values the monitors read, by cheat. Immutable.
 */
public final class Pass {

    private final long number;
    private final List<Change> changes;
    private final List<CheatState> cheats;
    private final Map<String, List<Shown>> shown;

    Pass(long number, List<Change> changes, List<CheatState> cheats, Map<String, List<Shown>> shown) {
        this.number = number;
        this.changes = Collections.unmodifiableList(new ArrayList<>(changes));
        this.cheats = Collections.unmodifiableList(new ArrayList<>(cheats));
        Map<String, List<Shown>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Shown>> e : shown.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.shown = Collections.unmodifiableMap(copy);
    }

    /** Returns the pass's number, from 1. */
    public long number() {
        return number;
    }

    /** Returns the changes: code changes first, in address order, then writes and raw entries. */
    public List<Change> changes() {
        return changes;
    }

    /** Returns every cheat's state after the pass, in file order. */
    public List<CheatState> cheats() {
        return cheats;
    }

    /** Returns the state of the cheat with that id, or null. */
    public CheatState cheat(String id) {
        for (CheatState c : cheats) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** Returns the values each monitor that worked in the pass read, in the order of its reads. */
    public Map<String, List<Shown>> shown() {
        return shown;
    }
}
