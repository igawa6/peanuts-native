package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the player set for a file, moved to a new version of it (section 17.5), as {@link
 * Updates#carry} gives it. Immutable.
 */
public final class Carry {

    /** A value is no longer of its value's type: another kind of value, NaN, or too wide. */
    public static final String TYPE = "type";
    /** A value lies outside its value's min and max. */
    public static final String RANGE = "range";
    /** A value is not among its value's choices. */
    public static final String CHOICE = "choice";

    private final Choices choices;
    private final String preset;
    private final Map<String, String> ids;
    private final List<String> dropped;
    private final List<String> gone;
    private final Map<String, String> reset;

    Carry(Choices choices, String preset, Map<String, String> ids, List<String> dropped, List<String> gone,
            Map<String, String> reset) {
        this.choices = choices;
        this.preset = preset;
        this.ids = Collections.unmodifiableMap(new LinkedHashMap<>(ids));
        this.dropped = Collections.unmodifiableList(new ArrayList<>(dropped));
        this.gone = Collections.unmodifiableList(new ArrayList<>(gone));
        this.reset = Collections.unmodifiableMap(new LinkedHashMap<>(reset));
    }

    /**
     * Returns what the player chose, under the ids of the new version: the cheats switched on
     * and off, and the values that still fit.
     */
    public Choices choices() {
        return choices;
    }

    /** Returns the preset the player chose, where the new version has it; else null. */
    public String preset() {
        return preset;
    }

    /**
     * Returns every cheat id of the old version that was looked at, in the order seen: its id in
     * the new version, or null where it is gone. The cheats named in kept are among them.
     */
    public Map<String, String> ids() {
        return ids;
    }

    /** Returns the old ids of the cheats whose settings were dropped, as they are gone, in the order seen. */
    public List<String> dropped() {
        return dropped;
    }

    /** Returns the old "cheat.value" keys of values that their cheat no longer has, in the order of the choices. */
    public List<String> gone() {
        return gone;
    }

    /**
     * Returns the new "cheat.value" keys of values that went back to their default, with why:
     * {@link #TYPE}, {@link #RANGE} or {@link #CHOICE}. A runtime names them when it shows the
     * new version.
     */
    public Map<String, String> reset() {
        return reset;
    }
}
