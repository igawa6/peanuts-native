package io.github.davdxpx.mistel.mncf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scalars of the format: integers that may be written as strings, bytes strings, patterns and
 * typed values, and how numbers compare and fit.
 *
 * <p>A number is a {@link Long}, a {@link BigInteger} only where the integer does not fit in a
 * long (a u64 value of 2^63 or more), a {@link Double}, or a {@link Boolean} where a value of
 * type bool is meant. Numbers compare exactly, also an integer with a float, as Python does.
 */
final class Values {

    static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");
    static final Pattern BYTES = Pattern.compile("[0-9A-Fa-f]{2}( [0-9A-Fa-f]{2})*");
    static final Pattern PATTERN = Pattern.compile("([0-9A-Fa-f]{2}|\\?\\?)( ([0-9A-Fa-f]{2}|\\?\\?))*");
    static final Pattern BIG_INT = Pattern.compile("0x([0-9A-Fa-f]{1,16})");
    static final Pattern EXTENSION = Pattern.compile("(x-)?[a-z0-9][a-z0-9-]*/[0-9]+");
    static final Pattern PLAYER_VALUE = Pattern.compile("\\$([a-z][a-z0-9-]*)");

    static final List<String> INT_TYPES = list("u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64");
    static final List<String> FLOAT_TYPES = list("f32", "f64");
    static final List<String> NUMBER_TYPES = join(INT_TYPES, FLOAT_TYPES);
    static final List<String> WRITE_TYPES = join(NUMBER_TYPES, list("bytes"));
    static final List<String> VALUE_TYPES = join(NUMBER_TYPES, list("bool"));

    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger U64_END = BigInteger.ONE.shiftLeft(64);

    private Values() {
    }

    static List<String> list(String... items) {
        return Collections.unmodifiableList(Arrays.asList(items));
    }

    private static List<String> join(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return Collections.unmodifiableList(all);
    }

    /** Whether the value is a whole number, not a bool: Python's type(v) is int. */
    static boolean isInt(Object v) {
        return v instanceof Long || v instanceof BigInteger;
    }

    /** Whether the value is a whole or a floating point number, not a bool. */
    static boolean isNumber(Object v) {
        return isInt(v) || v instanceof Double;
    }

    /** The number with the smallest type that holds it: a Long where it fits, else the BigInteger. */
    static Number normalize(BigInteger v) {
        return v.bitLength() < 64 ? (Number) v.longValue() : v;
    }

    /** A whole number or a bool as a BigInteger, a bool being 0 or 1. */
    static BigInteger big(Object v) {
        if (v instanceof Long) {
            return BigInteger.valueOf((Long) v);
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? BigInteger.ONE : BigInteger.ZERO;
        }
        return (BigInteger) v;
    }

    /** The low 64 bits of a whole number or a bool. */
    static long bits(Object v) {
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1 : 0;
        }
        return ((Number) v).longValue();
    }

    /** An unsigned 64-bit number as a number: a Long below 2^63, else a BigInteger. */
    static Number unsigned(long bits) {
        return bits >= 0 ? (Number) bits : new BigInteger(Long.toUnsignedString(bits));
    }

    /**
     * An integer of the file: a TOML integer, or a string such as "0xFFFFFFFF80000000" with up
     * to 16 hexadecimal digits. Null if it is neither.
     */
    static Number integer(Object v) {
        if (v instanceof Long) {
            return (Long) v;
        }
        Matcher m = Py.match(BIG_INT, v);
        if (m == null) {
            return null;
        }
        return normalize(new BigInteger(m.group(1), 16));
    }

    /** A bytes string as bytes, or null if it is not one. */
    static byte[] parseBytes(Object text) {
        Matcher m = Py.match(BYTES, text);
        if (m == null) {
            return null;
        }
        String s = m.group();
        byte[] out = new byte[(s.length() + 1) / 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(3 * i, 3 * i + 2), 16);
        }
        return out;
    }

    /**
     * A pattern as byte values, with -1 for each ??, or null if it is not a valid pattern: at
     * least four items, the first and the last a fixed byte.
     */
    static int[] parsePattern(Object text) {
        Matcher m = Py.match(PATTERN, text);
        if (m == null) {
            return null;
        }
        List<String> items = Py.split(m.group(), ' ');
        int[] out = new int[items.size()];
        for (int i = 0; i < out.length; i++) {
            String t = items.get(i);
            out[i] = t.equals("??") ? -1 : Integer.parseInt(t, 16);
        }
        if (out.length < 4 || out[0] < 0 || out[out.length - 1] < 0) {
            return null;
        }
        return out;
    }

    /** Bytes in the file's notation: upper-case pairs separated by single spaces. */
    static String hexBytes(byte[] data) {
        StringBuilder b = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(Character.forDigit((data[i] >> 4) & 0xF, 16)));
            b.append(Character.toUpperCase(Character.forDigit(data[i] & 0xF, 16)));
        }
        return b.toString();
    }

    static boolean isIntType(Object t) {
        return INT_TYPES.contains(t);
    }

    static boolean isFloatType(Object t) {
        return FLOAT_TYPES.contains(t);
    }

    /** The size in bytes of a number type or bool, or 0 for any other name. */
    static int typeSize(String t) {
        switch (t) {
            case "u8":
            case "s8":
            case "bool":
                return 1;
            case "u16":
            case "s16":
                return 2;
            case "u32":
            case "s32":
            case "f32":
                return 4;
            case "u64":
            case "s64":
            case "f64":
                return 8;
            default:
                return 0;
        }
    }

    private static boolean signed(String t) {
        return t.charAt(0) == 's';
    }

    /** Whether the number v can be stored as type t. */
    static boolean fits(String t, Object v) {
        if (t.equals("bool")) {
            if (v instanceof Boolean) {
                return true;
            }
            if (!isInt(v)) {
                return false;
            }
            BigInteger n = big(v);
            return n.equals(BigInteger.ZERO) || n.equals(BigInteger.ONE);
        }
        if (isIntType(t)) {
            if (!isInt(v)) {
                return false;
            }
            int width = 8 * typeSize(t);
            BigInteger n = big(v);
            BigInteger low = signed(t) ? BigInteger.ONE.shiftLeft(width - 1).negate() : BigInteger.ZERO;
            BigInteger high = (signed(t) ? BigInteger.ONE.shiftLeft(width - 1) : BigInteger.ONE.shiftLeft(width))
                    .subtract(BigInteger.ONE);
            return n.compareTo(low) >= 0 && n.compareTo(high) <= 0;
        }
        if (isFloatType(t)) {
            if (!isNumber(v)) {
                return false;
            }
            if (t.equals("f32") && v instanceof Double) {
                double d = (Double) v;
                // A finite value that rounds to an infinite float is too big, as struct.pack says.
                return !(Float.isInfinite((float) d) && !Double.isInfinite(d) && !Double.isNaN(d));
            }
            return true;
        }
        return false;
    }

    /** The number as a double, rounded to the nearest one as Python's float() does. */
    static double toDouble(Object v) {
        if (v instanceof Double) {
            return (Double) v;
        }
        if (v instanceof Long) {
            return (double) (Long) v;
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1 : 0;
        }
        BigInteger n = (BigInteger) v;
        if (n.signum() > 0 && n.bitLength() == 64) {
            long bits = n.longValue();
            // Halve with the lowest bit kept as a sticky bit, so that the rounding stays exact.
            return (double) ((bits >>> 1) | (bits & 1)) * 2.0;
        }
        return Double.parseDouble(n.toString());
    }

    /**
     * The number that the bytes hold as a number type, in the platform's byte order: a Long, a
     * BigInteger for a u64 of 2^63 or more, or a Double for f32 and f64.
     */
    static Object decode(String t, byte[] raw, boolean bigEndian) {
        long bits = 0;
        int size = raw.length;
        for (int i = 0; i < size; i++) {
            int b = raw[bigEndian ? i : size - 1 - i] & 0xFF;
            bits = (bits << 8) | b;
        }
        if (t.equals("f32")) {
            return (double) Float.intBitsToFloat((int) bits);
        }
        if (t.equals("f64")) {
            return Double.longBitsToDouble(bits);
        }
        if (signed(t)) {
            int shift = 64 - 8 * size;
            return (bits << shift) >> shift;
        }
        return unsigned(bits);
    }

    /** The bytes of the number v as type t, in the platform's byte order. */
    static byte[] encode(String t, Object v, boolean bigEndian) {
        int size = typeSize(t);
        long bits;
        if (t.equals("bool")) {
            return new byte[] {(byte) (truthy(v) ? 1 : 0)};
        } else if (t.equals("f32")) {
            bits = Float.floatToRawIntBits((float) toDouble(v)) & 0xFFFFFFFFL;
        } else if (t.equals("f64")) {
            bits = Double.doubleToRawLongBits(toDouble(v));
        } else {
            bits = bits(v);
        }
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            byte b = (byte) (bits >>> (8 * i));
            out[bigEndian ? size - 1 - i : i] = b;
        }
        return out;
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Double) {
            return (Double) v != 0;
        }
        return big(v).signum() != 0;
    }

    /**
     * Compares two numbers as Python does: exactly, also between a whole number and a float, a
     * bool counting as 0 or 1.
     *
     * @return a negative number, zero or a positive number, or null when either is NaN
     */
    static Integer compare(Object a, Object b) {
        if (a instanceof Double || b instanceof Double) {
            double x = a instanceof Double ? (Double) a : 0;
            double y = b instanceof Double ? (Double) b : 0;
            if (Double.isNaN(x) || Double.isNaN(y)) {
                return null;
            }
            if (a instanceof Double && b instanceof Double) {
                return x < y ? -1 : x > y ? 1 : 0;
            }
            if (a instanceof Double && Double.isInfinite(x)) {
                return x > 0 ? 1 : -1;
            }
            if (b instanceof Double && Double.isInfinite(y)) {
                return y > 0 ? -1 : 1;
            }
            return exact(a).compareTo(exact(b));
        }
        return big(a).compareTo(big(b));
    }

    private static BigDecimal exact(Object v) {
        return v instanceof Double ? new BigDecimal((Double) v) : new BigDecimal(big(v));
    }

    /** Python's a &lt; b for numbers: false when either is NaN. */
    static boolean less(Object a, Object b) {
        Integer c = compare(a, b);
        return c != null && c < 0;
    }

    /** Python's a &lt;= b for numbers: false when either is NaN. */
    static boolean lessOrEqual(Object a, Object b) {
        Integer c = compare(a, b);
        return c != null && c <= 0;
    }

    /** Python's a == b for numbers. */
    static boolean same(Object a, Object b) {
        Integer c = compare(a, b);
        return c != null && c == 0;
    }

    /** Whether the whole number lies in the signed or unsigned 64-bit range. */
    static boolean inLongOrU64(BigInteger n) {
        return n.compareTo(LONG_MIN) >= 0 && n.compareTo(U64_END) < 0;
    }

    /** Whether the whole number fits in a long. */
    static boolean inLong(BigInteger n) {
        return n.compareTo(LONG_MIN) >= 0 && n.compareTo(LONG_MAX) <= 0;
    }

    /**
     * A copy of a parsed document in which every table and array is unmodifiable, so that it can
     * be handed to callers.
     */
    @SuppressWarnings("unchecked")
    static Object freeze(Object v) {
        if (v instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                copy.put(e.getKey(), freeze(e.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (v instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<Object>) v) {
                copy.add(freeze(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return v;
    }
}
