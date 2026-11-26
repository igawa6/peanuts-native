package io.github.davdxpx.mistel.mncf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A value a monitor read in a pass (section 7.7). Immutable. */
public final class Shown {

    private final String label;
    private final String type;
    private final Object value;

    Shown(String label, String type, Object value) {
        this.label = label;
        this.type = type;
        this.value = value;
    }

    /** Returns the read's label, or null. */
    public String label() {
        return label;
    }

    /** Returns the type the value was read as. */
    public String type() {
        return type;
    }

    /**
     * Returns the value: a Long, a BigInteger for a u64 of 2^63 or more, or a Double for f32
     * and f64.
     */
    public Object value() {
        return value;
    }

    /** Returns the fields that are set, by name, as the conformance suite writes them. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("value", value);
        if (label != null) {
            out.put("label", label);
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
