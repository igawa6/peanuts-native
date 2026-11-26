package io.github.davdxpx.mistel.mncf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A value the player sets (section 8): its type, range and default. Immutable.
 *
 * <p>Numbers are a {@link Long}, or a {@link java.math.BigInteger} for a u64 value of 2^63 or
 * more, a {@link Double} for a float, and a {@link Boolean} for type bool. For any whole number,
 * {@link Number#longValue()} gives its 64 bits. A float type may have whole numbers as its
 * limits and default, as the file wrote them.
 */
public final class ValueSpec {

    private final String id;
    private final String type;
    private final Object defaultValue;
    private final Number min;
    private final Number max;
    private final Number step;
    private final String unit;
    private final String display;
    private final Map<Number, String> choices;
    private final Map<Number, String> flags;

    ValueSpec(String id, String type, Object defaultValue, Number min, Number max, Number step, String unit,
            String display, Map<Number, String> choices, Map<Number, String> flags) {
        this.id = id;
        this.type = type;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.step = step;
        this.unit = unit;
        this.display = display;
        this.choices = choices == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(choices));
        this.flags = flags == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(flags));
    }

    /** Returns the value's id within its cheat. */
    public String id() {
        return id;
    }

    /** Returns the type: u8 to u64, s8 to s64, f32, f64 or bool. */
    public String type() {
        return type;
    }

    /** Returns the default value: a number, or a Boolean for type bool. */
    public Object defaultValue() {
        return defaultValue;
    }

    /** Returns the smallest value allowed, or null. */
    public Number min() {
        return min;
    }

    /** Returns the largest value allowed, or null. */
    public Number max() {
        return max;
    }

    /** Returns the step between values, or null. */
    public Number step() {
        return step;
    }

    /** Returns the unit shown after the value, or null. */
    public String unit() {
        return unit;
    }

    /** Returns how the value is shown: "dec" or "hex". */
    public String display() {
        return display;
    }

    /** Returns the named choices by their number, in file order, or null when the value has none. */
    public Map<Number, String> choices() {
        return choices;
    }

    /** Returns the named bit flags by bit number, in file order, or null when the value has none. */
    public Map<Number, String> flags() {
        return flags;
    }
}
