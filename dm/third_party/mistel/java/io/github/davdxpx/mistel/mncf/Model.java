package io.github.davdxpx.mistel.mncf;

import java.util.Map;

/** The checked operations of a cheat, as the evaluator reads them. Built once by {@link Schema}. */
final class Model {

    private Model() {
    }

    /**
     * A place (section 6.2). The kind is module, module-builds, pattern, region, region-builds or
     * address. Offsets, addresses and path steps hold 64 bits and are unsigned, except the offset
     * of a pattern, which is signed.
     */
    static final class Location {
        String kind;
        String module;
        String region;
        long address;
        long offset;
        Map<String, Long> offsets;
        int[] find;
        String section;
        long[] path;

        boolean hasPath() {
            return path != null && path.length > 0;
        }
    }

    /** A reference to a value the player sets: "$count". */
    static final class ValueRef {
        final String name;

        ValueRef(String name) {
            this.name = name;
        }
    }

    /** One operation of a cheat. */
    abstract static class Op {
        /** The kind as resolved operations name it: patch, write, override, read or when. */
        abstract String kind();

        /** The place the operation acts on. */
        abstract Location location();
    }

    /** The instruction of an encode table (arm64-imm/1). The value is a number or a ValueRef. */
    static final class Encoding {
        String insn;
        int width;
        int rd;
        Object value;
    }

    static final class Patch extends Op {
        Location at;
        byte[] expect;
        byte[] bytes;
        Encoding encode;
        Map<String, Object> asm;

        @java.lang.Override
        String kind() {
            return "patch";
        }

        @java.lang.Override
        Location location() {
            return at;
        }
    }

    /** A write. The value is bytes, a number or a ValueRef; expect is bytes, a number or null. */
    static final class Write extends Op {
        Location at;
        String type;
        Object value;
        Object expect;

        @java.lang.Override
        String kind() {
            return "write";
        }

        @java.lang.Override
        Location location() {
            return at;
        }
    }

    /** An override. The value is a whole number, a ValueRef, or null for "skip". */
    static final class Override extends Op {
        Location function;
        String action;
        Object value;
        byte[] expect;

        @java.lang.Override
        String kind() {
            return "override";
        }

        @java.lang.Override
        Location location() {
            return function;
        }
    }

    static final class Read extends Op {
        Location at;
        String type;
        String label;

        @java.lang.Override
        String kind() {
            return "read";
        }

        @java.lang.Override
        Location location() {
            return at;
        }
    }

    /**
     * A condition. The value is a number, a ValueRef, a Between, a Place, or null for "changed".
     */
    static final class Condition extends Op {
        Location at;
        String type;
        String op;
        Object value;

        @java.lang.Override
        String kind() {
            return "when";
        }

        @java.lang.Override
        Location location() {
            return at;
        }
    }

    /** The two ends of a "between" condition. */
    static final class Between {
        final Object low;
        final Object high;

        Between(Object low, Object high) {
            this.low = low;
            this.high = high;
        }
    }

    /** Another place a condition compares with. */
    static final class Place {
        final Location at;
        final String type;

        Place(Location at, String type) {
            this.at = at;
            this.type = type;
        }
    }

    /** Raw code of an existing cheat format, for one build (raw/1). */
    static final class Raw {
        final String syntax;
        final String build;
        final String code;

        Raw(String syntax, String build, String code) {
            this.syntax = syntax;
            this.build = build;
            this.code = code;
        }
    }
}
