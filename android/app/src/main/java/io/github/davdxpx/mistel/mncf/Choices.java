package io.github.davdxpx.mistel.mncf;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the player chose: cheats switched on or off against their default, and values by
 * "cheat.value". Immutable once built.
 *
 * <p>A value is a {@link Long} (or a smaller whole number type, which becomes a Long), a
 * {@link BigInteger}, a {@link Double} or {@link Float}, or a {@link Boolean}. A chosen value
 * that the player cannot choose refuses its cheat with value-out-of-range: one of another kind
 * (a Boolean is the only value of type bool, a whole number the only value of an integer type),
 * NaN, one too wide for its type, one outside min and max, or one that is not among the choices.
 */
public final class Choices {

    private static final Choices NONE = new Builder().build();

    private final List<String> on;
    private final List<String> off;
    private final Map<String, Object> values;

    private Choices(Builder b) {
        on = Collections.unmodifiableList(new ArrayList<>(b.on));
        off = Collections.unmodifiableList(new ArrayList<>(b.off));
        values = Collections.unmodifiableMap(new LinkedHashMap<>(b.values));
    }

    /** Returns choices that change nothing: every cheat as its default, every value its default. */
    public static Choices none() {
        return NONE;
    }

    /** Starts a set of choices. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds {@link Choices}. */
    public static final class Builder {
        private final List<String> on = new ArrayList<>();
        private final List<String> off = new ArrayList<>();
        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder() {
        }

        /** Switches cheats on. */
        public Builder on(String... ids) {
            return on(Arrays.asList(ids));
        }

        /** Switches cheats on. */
        public Builder on(Collection<String> ids) {
            on.addAll(ids);
            return this;
        }

        /** Switches cheats off that are on by default. */
        public Builder off(String... ids) {
            return off(Arrays.asList(ids));
        }

        /** Switches cheats off that are on by default. */
        public Builder off(Collection<String> ids) {
            off.addAll(ids);
            return this;
        }

        /**
         * Sets a value the player chose.
         *
         * @param key the cheat's id and the value's id, joined by a dot: "money.count"
         * @param value a whole number, a float or a Boolean
         * @throws IllegalArgumentException for any other kind of value
         */
        public Builder value(String key, Object value) {
            values.put(key, number(value));
            return this;
        }

        /** Sets a value the player chose from its 64 bits, read as an unsigned number (for u64). */
        public Builder unsignedValue(String key, long bits) {
            values.put(key, Values.unsigned(bits));
            return this;
        }

        /** Returns the choices. */
        public Choices build() {
            return new Choices(this);
        }
    }

    static Object number(Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof BigInteger) {
            return Values.normalize((BigInteger) v);
        }
        if (v instanceof Double || v instanceof Float) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof Boolean) {
            return v;
        }
        throw new IllegalArgumentException("a value is a whole number, a float or a Boolean, not " + v);
    }

    /** Returns the ids of the cheats switched on. */
    public List<String> on() {
        return on;
    }

    /** Returns the ids of the cheats switched off. */
    public List<String> off() {
        return off;
    }

    /** Returns the values the player chose, by "cheat.value". */
    public Map<String, Object> values() {
        return values;
    }
}
