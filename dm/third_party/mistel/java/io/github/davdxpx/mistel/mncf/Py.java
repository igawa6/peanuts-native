package io.github.davdxpx.mistel.mncf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text rules of the Python reference reader that edge cases of the format depend on: which
 * characters are white space, how int() reads a number, how a pattern anchored with ^ and $
 * matches, and how repr() writes a value.
 */
final class Py {

    /** The most decimal digits int() reads, as Python's default int_max_str_digits allows. */
    private static final int MAX_DECIMAL_DIGITS = 4300;

    private Py() {
    }

    /** Whether the code point is white space as Python's str.isspace() and the \s of re see it. */
    static boolean isSpace(int c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0
                || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029
                || c == 0x202F || c == 0x205F || c == 0x3000;
    }

    /** The regular expression class of every character that is not white space, as \S of re. */
    static final String NOT_SPACE =
            "[^\\x09-\\x0D\\x1C-\\x20\\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}"
            + "\\x{202F}\\x{205F}\\x{3000}]";

    /** The text without white space at both ends, as str.strip() gives it. */
    static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /** The words of the text between runs of white space, as str.split() gives them. */
    static List<String> splitSpace(String s) {
        List<String> words = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && isSpace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < s.length() && !isSpace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                words.add(s.substring(start, i));
            }
        }
        return words;
    }

    /**
     * The lines of the text, as str.splitlines() gives them: a line ends at a line feed, a
     * carriage return, both together, or any other line boundary Python knows; no empty line
     * follows a last line end.
     */
    static List<String> splitLines(String s) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            boolean end = c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || (c >= 0x1C && c <= 0x1E)
                    || c == 0x85 || c == 0x2028 || c == 0x2029;
            if (!end) {
                i++;
                continue;
            }
            lines.add(s.substring(start, i));
            i += c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n' ? 2 : 1;
            start = i;
        }
        if (start < s.length()) {
            lines.add(s.substring(start));
        }
        return lines;
    }

    /** The parts of the text between each separator, empty parts included, as str.split(sep). */
    static List<String> split(String s, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == separator) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /**
     * Matches the whole text, as Python's re.match does for a pattern between ^ and \Z.
     *
     * @return the matcher of the match, or null when the value is not text that matches
     */
    static Matcher match(Pattern pattern, Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        Matcher m = pattern.matcher((String) value);
        return m.matches() ? m : null;
    }

    /** Whether the value is text that matches the pattern, as {@link #match} decides. */
    static boolean matches(Pattern pattern, Object value) {
        return match(pattern, value) != null;
    }

    /** The text in lower case, as str.lower() gives it for the text this format uses. */
    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a whole number as Python's int(text, base) does for base 10 or 16: white space at
     * both ends, a sign, for base 16 an optional 0x prefix with one underscore after it, single
     * underscores between digits, and decimal digits of any script.
     *
     * @return the number, or null where int() raises ValueError
     */
    static BigInteger parseInt(String s, int base) {
        StringBuilder t = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c < 127) {
                t.append((char) c);
            } else if (isSpace(c)) {
                t.append(' ');
            } else if (Character.isDigit(c)) {
                t.append((char) ('0' + Character.digit(c, 10)));
            } else {
                return null;
            }
        }
        int n = t.length();
        int i = 0;
        while (i < n && isAsciiSpace(t.charAt(i))) {
            i++;
        }
        boolean negative = false;
        if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            negative = t.charAt(i) == '-';
            i++;
        }
        if (base == 16 && i + 1 < n && t.charAt(i) == '0' && (t.charAt(i + 1) == 'x' || t.charAt(i + 1) == 'X')) {
            i += 2;
            if (i < n && t.charAt(i) == '_') {
                i++;
            }
        }
        if (i < n && t.charAt(i) == '_') {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        char previous = 0;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '_') {
                if (previous == '_') {
                    return null;
                }
            } else if (Character.digit(c, base) >= 0 && c < 128) {
                digits.append(c);
            } else {
                break;
            }
            previous = c;
            i++;
        }
        if (previous == '_' || digits.length() == 0) {
            return null;
        }
        if (base == 10 && digits.length() > MAX_DECIMAL_DIGITS) {
            return null;
        }
        while (i < n && isAsciiSpace(t.charAt(i))) {
            i++;
        }
        if (i != n) {
            return null;
        }
        BigInteger value = new BigInteger(digits.toString(), base);
        return negative ? value.negate() : value;
    }

    /**
     * Reads a whole number as Python's int(text, 0) does: as {@link #parseInt}, with the base
     * from a prefix (0x, 0o or 0b, in either case), else decimal without leading zeros.
     *
     * @return the number, or null where int() raises ValueError
     */
    static BigInteger parseIntAuto(String s) {
        StringBuilder t = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c < 127) {
                t.append((char) c);
            } else if (isSpace(c)) {
                t.append(' ');
            } else if (Character.isDigit(c)) {
                t.append((char) ('0' + Character.digit(c, 10)));
            } else {
                return null;
            }
        }
        int n = t.length();
        int i = 0;
        while (i < n && isAsciiSpace(t.charAt(i))) {
            i++;
        }
        int sign = i;
        if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            i++;
        }
        int base = 10;
        if (i + 1 < n && t.charAt(i) == '0') {
            char p = Character.toLowerCase(t.charAt(i + 1));
            base = p == 'x' ? 16 : p == 'o' ? 8 : p == 'b' ? 2 : 10;
        }
        if (base != 10) {
            int from = i + 2;
            if (from < n && t.charAt(from) == '_') {
                from++;
            }
            return parseDigits(t, sign, from, base);
        }
        BigInteger v = parseDigits(t, sign, i, 10);
        if (v != null && i < n && t.charAt(i) == '0' && v.signum() != 0) {
            return null;                    // a decimal number has no leading zeros, but 0, 00 and 0_0 are 0
        }
        return v;
    }

    /**
     * The number of the text from an index on: digits of the base with single underscores
     * between them, then white space, and the sign at the index given. Null when it is anything
     * else.
     */
    private static BigInteger parseDigits(CharSequence t, int sign, int from, int base) {
        int n = t.length();
        boolean negative = sign < from && t.charAt(sign) == '-';
        int i = from;
        if (i < n && t.charAt(i) == '_') {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        char previous = 0;
        while (i < n) {
            char c = t.charAt(i);
            if (c == '_') {
                if (previous == '_') {
                    return null;
                }
            } else if (Character.digit(c, base) >= 0 && c < 128) {
                digits.append(c);
            } else {
                break;
            }
            previous = c;
            i++;
        }
        if (previous == '_' || digits.length() == 0) {
            return null;
        }
        if (base == 10 && digits.length() > MAX_DECIMAL_DIGITS) {
            return null;
        }
        while (i < n && isAsciiSpace(t.charAt(i))) {
            i++;
        }
        if (i != n) {
            return null;
        }
        BigInteger value = new BigInteger(digits.toString(), base);
        return negative ? value.negate() : value;
    }

    private static boolean isAsciiSpace(char c) {
        return c == ' ' || (c >= 0x09 && c <= 0x0D);
    }

    // str() and repr()

    /** The value as Python's repr() writes it, for values a TOML document holds. */
    static String repr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof String) {
            return stringRepr((String) v);
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "True" : "False";
        }
        if (v instanceof Double) {
            return floatRepr((Double) v);
        }
        if (v instanceof Long || v instanceof BigInteger) {
            return v.toString();
        }
        if (v instanceof List) {
            StringBuilder b = new StringBuilder("[");
            for (Object item : (List<?>) v) {
                if (b.length() > 1) {
                    b.append(", ");
                }
                b.append(repr(item));
            }
            return b.append(']').toString();
        }
        if (v instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (b.length() > 1) {
                    b.append(", ");
                }
                b.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
            }
            return b.append('}').toString();
        }
        return String.valueOf(v);
    }

    private static String stringRepr(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder b = new StringBuilder().append(quote);
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == quote || c == '\\') {
                b.append('\\').append((char) c);
            } else if (c == '\t') {
                b.append("\\t");
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c < ' ' || c == 0x7F) {
                b.append(String.format("\\x%02x", c));
            } else if (c < 0x7F || isPrintable(c)) {
                b.appendCodePoint(c);
            } else if (c <= 0xFF) {
                b.append(String.format("\\x%02x", c));
            } else if (c <= 0xFFFF) {
                b.append(String.format("\\u%04x", c));
            } else {
                b.append(String.format("\\U%08x", c));
            }
        }
        return b.append(quote).toString();
    }

    private static boolean isPrintable(int c) {
        switch (Character.getType(c)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.SURROGATE:
            case Character.PRIVATE_USE:
            case Character.UNASSIGNED:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.SPACE_SEPARATOR:
                return c == ' ';
            default:
                return true;
        }
    }

    /** The shortest text that reads back as the same double, as Python's repr() of a float. */
    static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        String sign = (Double.doubleToRawLongBits(d) < 0) ? "-" : "";
        if (d == 0) {
            return sign + "0.0";
        }
        double magnitude = Math.abs(d);
        BigDecimal exact = new BigDecimal(magnitude);
        BigDecimal best = null;
        for (int precision = 1; precision <= 17 && best == null; precision++) {
            BigDecimal down = exact.round(new MathContext(precision, RoundingMode.FLOOR));
            BigDecimal up = exact.round(new MathContext(precision, RoundingMode.CEILING));
            boolean downOk = Double.parseDouble(down.toString()) == magnitude;
            boolean upOk = Double.parseDouble(up.toString()) == magnitude;
            if (downOk && upOk) {
                int c = exact.subtract(down).compareTo(up.subtract(exact));
                best = c < 0 ? down : c > 0 ? up : exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            } else if (downOk) {
                best = down;
            } else if (upOk) {
                best = up;
            }
        }
        BigDecimal stripped = best.stripTrailingZeros();
        String digits = stripped.unscaledValue().toString();
        // The value is 0.<digits> times ten to the power point.
        int point = digits.length() - stripped.scale();
        StringBuilder b = new StringBuilder(sign);
        if (point <= -4 || point > 16) {
            b.append(digits.charAt(0));
            if (digits.length() > 1) {
                b.append('.').append(digits, 1, digits.length());
            }
            int exponent = point - 1;
            b.append('e').append(exponent < 0 ? '-' : '+');
            String e = Integer.toString(Math.abs(exponent));
            if (e.length() < 2) {
                b.append('0');
            }
            b.append(e);
        } else if (point <= 0) {
            b.append("0.");
            for (int i = 0; i < -point; i++) {
                b.append('0');
            }
            b.append(digits);
        } else if (point >= digits.length()) {
            b.append(digits);
            for (int i = digits.length(); i < point; i++) {
                b.append('0');
            }
            b.append(".0");
        } else {
            b.append(digits, 0, point).append('.').append(digits, point, digits.length());
        }
        return b.toString();
    }
}
