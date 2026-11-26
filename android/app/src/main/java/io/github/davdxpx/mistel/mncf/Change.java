package io.github.davdxpx.mistel.mncf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One change of a pass (section 11.5), as {@link Engine#tick} gives it. Fields that do not
 * apply to the kind are null. Immutable.
 *
 * <ul>
 *   <li>{@code patch} and {@code restore} write {@link #bytes()} into code at {@link #address()};
 *   <li>{@code hook} makes the function at the address do {@link #action()}, returning
 *       {@link #value()} for "return", and {@code unhook} lets it run as it was;
 *   <li>{@code write} writes the bytes into guest memory for {@link #cheat()};
 *   <li>{@code raw} runs the cheat's raw entry ({@link #syntax()}, {@link #build()},
 *       {@link #code()}) once.
 * </ul>
 */
public final class Change {

    /** Write bytes into code. */
    public static final String PATCH = "patch";
    /** Put original bytes back into code. */
    public static final String RESTORE = "restore";
    /** Make a function return at once, with a value or without. */
    public static final String HOOK = "hook";
    /** Let a hooked function run as it was. */
    public static final String UNHOOK = "unhook";
    /** Write bytes into guest memory. */
    public static final String WRITE = "write";
    /** Run a raw entry once. */
    public static final String RAW = "raw";

    private final String kind;
    private final Long address;
    private final byte[] bytes;
    private final String cheat;
    private final String action;
    private final Object value;
    private final String syntax;
    private final String build;
    private final String code;

    private Change(String kind, Long address, byte[] bytes, String cheat, String action, Object value,
            String syntax, String build, String code) {
        this.kind = kind;
        this.address = address;
        this.bytes = bytes == null ? null : bytes.clone();
        this.cheat = cheat;
        this.action = action;
        this.value = value;
        this.syntax = syntax;
        this.build = build;
        this.code = code;
    }

    static Change code(String kind, long address, byte[] bytes) {
        return new Change(kind, address, bytes, null, null, null, null, null, null);
    }

    static Change hook(long address, String action, Object value) {
        return new Change(HOOK, address, null, null, action, value, null, null, null);
    }

    static Change unhook(long address) {
        return new Change(UNHOOK, address, null, null, null, null, null, null, null);
    }

    static Change write(String cheat, long address, byte[] bytes) {
        return new Change(WRITE, address, bytes, cheat, null, null, null, null, null);
    }

    static Change raw(String cheat, Model.Raw r) {
        return new Change(RAW, null, null, cheat, null, null, r.syntax, r.build, r.code);
    }

    /** Returns patch, restore, hook, unhook, write or raw. */
    public String kind() {
        return kind;
    }

    /** Returns the address, an unsigned 64-bit number, or null for raw. */
    public Long address() {
        return address;
    }

    /** Returns a copy of the bytes to write, or null for hook, unhook and raw. */
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    /** Returns the cheat a write or raw entry is for, or null. */
    public String cheat() {
        return cheat;
    }

    /** Returns the action of a hook, return or skip, or null. */
    public String action() {
        return action;
    }

    /** Returns the whole number a hooked function returns, or null. */
    public Object value() {
        return value;
    }

    /** Returns the syntax of a raw entry, or null. */
    public String syntax() {
        return syntax;
    }

    /** Returns the build of a raw entry, or null. */
    public String build() {
        return build;
    }

    /** Returns the code of a raw entry, or null. */
    public String code() {
        return code;
    }

    /**
     * Returns the fields that are set, by name, as the conformance suite writes them: bytes as
     * upper-case hexadecimal pairs, the address as a number that is never negative.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        put(out, "address", address == null ? null : Values.unsigned(address));
        put(out, "bytes", bytes == null ? null : Values.hexBytes(bytes));
        put(out, "cheat", cheat);
        put(out, "action", action);
        put(out, "value", value);
        put(out, "syntax", syntax);
        put(out, "build", build);
        put(out, "code", code);
        return Collections.unmodifiableMap(out);
    }

    private static void put(Map<String, Object> out, String key, Object v) {
        if (v != null) {
            out.put(key, v);
        }
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
