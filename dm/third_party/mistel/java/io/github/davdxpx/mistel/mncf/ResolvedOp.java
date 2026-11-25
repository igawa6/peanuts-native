package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One operation of a cheat, resolved for the runtime: where it acts and what it writes or
 * compares. Fields that do not apply to the kind are null. Immutable.
 *
 * <p>An operation whose pointer path cannot be followed yet has only its kind.
 */
public final class ResolvedOp {

    private final String kind;
    private final Long address;
    private final String module;
    private final Long offset;
    private final String type;
    private final byte[] expect;
    private final byte[] bytes;
    private final String action;
    private final Object value;
    private final String op;
    private final String label;
    private final String syntax;
    private final String build;
    private final String code;

    private ResolvedOp(Builder b) {
        kind = b.kind;
        address = b.address;
        module = b.module;
        offset = b.offset;
        type = b.type;
        expect = b.expect == null ? null : b.expect.clone();
        bytes = b.bytes == null ? null : b.bytes.clone();
        action = b.action;
        value = b.value;
        op = b.op;
        label = b.label;
        syntax = b.syntax;
        build = b.build;
        code = b.code;
    }

    /** The fields of a resolved operation while the evaluator fills them in. */
    static final class Builder {
        final String kind;
        Long address;
        String module;
        Long offset;
        String type;
        byte[] expect;
        byte[] bytes;
        String action;
        Object value;
        String op;
        String label;
        String syntax;
        String build;
        String code;

        Builder(String kind) {
            this.kind = kind;
        }

        Builder place(long address, String module, Long offset) {
            this.address = address;
            this.module = module;
            this.offset = offset;
            return this;
        }

        ResolvedOp done() {
            return new ResolvedOp(this);
        }
    }

    /** Returns patch, write, override, read, when or raw. */
    public String kind() {
        return kind;
    }

    /** Returns the address the operation acts on, an unsigned 64-bit number, or null. */
    public Long address() {
        return address;
    }

    /** Returns the module the address lies in, or null when it is not given as a module place. */
    public String module() {
        return module;
    }

    /** Returns the offset of the address in its module, unsigned, or null. */
    public Long offset() {
        return offset;
    }

    /** Returns the type of a write, read or condition, or null. */
    public String type() {
        return type;
    }

    /** Returns a copy of the bytes expected at the address before the operation, or null. */
    public byte[] expect() {
        return expect == null ? null : expect.clone();
    }

    /** Returns a copy of the bytes to write: the patch, the value, or the override stub. Or null. */
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    /** Returns the action of an override: return or skip. Or null. */
    public String action() {
        return action;
    }

    /**
     * Returns the value: for an override the whole number it returns; for a condition a number
     * or Boolean, a list of the two ends of "between", or a map with the "address" and "type"
     * of the other place. Or null. Whole numbers are Long, or BigInteger at 2^63 and above.
     */
    public Object value() {
        return value;
    }

    /** Returns the comparison of a condition, such as "==" or "between". Or null. */
    public String op() {
        return op;
    }

    /** Returns the label of a read, or null. */
    public String label() {
        return label;
    }

    /** Returns the syntax of raw code, or null. */
    public String syntax() {
        return syntax;
    }

    /** Returns the build of raw code, or null. */
    public String build() {
        return build;
    }

    /** Returns the raw code, or null. */
    public String code() {
        return code;
    }

    /**
     * Returns the fields that are set, by name, as the conformance suite writes them: bytes as
     * upper-case hexadecimal pairs, the address and the offset as numbers that are never negative.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        put(out, "address", address == null ? null : Values.unsigned(address));
        put(out, "module", module);
        put(out, "offset", offset == null ? null : Values.unsigned(offset));
        put(out, "type", type);
        put(out, "expect", expect == null ? null : Values.hexBytes(expect));
        put(out, "bytes", bytes == null ? null : Values.hexBytes(bytes));
        put(out, "action", action);
        put(out, "value", value);
        put(out, "op", op);
        put(out, "label", label);
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

    /** A list of the two ends of a "between" condition, unmodifiable. */
    static List<Object> pair(Object low, Object high) {
        List<Object> both = new ArrayList<>();
        both.add(low);
        both.add(high);
        return Collections.unmodifiableList(both);
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
