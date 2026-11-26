package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A named set of cheats and values (section 10). Immutable. */
public final class Preset {

    private final String id;
    private final String name;
    private final List<String> cheats;
    private final Map<String, Object> values;

    Preset(String id, String name, List<String> cheats, Map<String, Object> values) {
        this.id = id;
        this.name = name;
        this.cheats = Collections.unmodifiableList(new ArrayList<>(cheats));
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Returns the preset's id. */
    public String id() {
        return id;
    }

    /** Returns the name shown to players. */
    public String name() {
        return name;
    }

    /** Returns the ids of the cheats the preset switches on. */
    public List<String> cheats() {
        return cheats;
    }

    /**
     * Returns the values the preset sets, by "cheat.value", as numbers of the value's type: a
     * Long, a BigInteger at 2^63 and above, a Double or a Boolean. Each one lies in its value's
     * range, so it can go into {@link Choices} as it is.
     */
    public Map<String, Object> values() {
        return values;
    }
}
