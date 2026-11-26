package io.github.davdxpx.mistel.mncf.toml;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A parser for TOML 1.0.0 documents.
 *
 * <p>A table becomes a {@link LinkedHashMap} whose keys keep the order in which they first appear
 * in the document, an array becomes an {@link ArrayList}, and the other values become
 * {@link String}, {@link Long}, {@link Double}, {@link Boolean} or {@link TomlDateTime}. A new
 * line inside a multi-line string is returned as a line feed, also where the document has a
 * carriage return and a line feed.
 */
public final class Toml {

    /** How deep arrays and inline tables may nest, so that no document can exhaust the stack. */
    private static final int MAX_NESTING = 128;

    private static final int BYTE_ORDER_MARK = 0xFEFF;

    private static final String DECIMAL = "[+-]?(?:0|[1-9](?:_?[0-9])*)";
    private static final String DIGITS = "[0-9](?:_?[0-9])*";
    private static final Pattern DECIMAL_INTEGER = Pattern.compile(DECIMAL);
    private static final Pattern HEX_INTEGER = Pattern.compile("0x[0-9A-Fa-f](?:_?[0-9A-Fa-f])*");
    private static final Pattern OCTAL_INTEGER = Pattern.compile("0o[0-7](?:_?[0-7])*");
    private static final Pattern BINARY_INTEGER = Pattern.compile("0b[01](?:_?[01])*");
    private static final Pattern FLOAT =
            Pattern.compile(DECIMAL + "(?:\\." + DIGITS + ")?(?:[eE][+-]?" + DIGITS + ")?");
    private static final Pattern SPECIAL_FLOAT = Pattern.compile("[+-]?(?:inf|nan)");

    /** How a table or an array came to be, which decides what later lines may add to it. */
    private enum Origin {
        /** A table that so far is only named on the way to a table header. */
        IMPLICIT,
        /** A table defined by a table header, or an element of an array of tables. */
        HEADER,
        /** A table created by a dotted key. */
        DOTTED,
        /** An inline table, complete as written. */
        INLINE,
        /** An array of tables, which later headers may append to. */
        TABLE_ARRAY
    }

    private final String src;
    private final Map<Object, Origin> origins = new IdentityHashMap<>();
    private int pos;
    private int nesting;

    private Toml(String src) {
        this.src = src;
    }

    /**
     * Parses a whole TOML 1.0.0 document.
     *
     * @param text the document, already decoded from UTF-8; a byte order mark at the start is skipped
     * @return the root table
     * @throws TomlException if the document is not valid TOML 1.0.0
     */
    public static Map<String, Object> parse(String text) throws TomlException {
        return new Toml(text).document();
    }

    private Map<String, Object> document() throws TomlException {
        if (at(0) == BYTE_ORDER_MARK) {
            pos = 1;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> table = root;
        while (pos < src.length()) {
            skipSpaces();
            int c = peek();
            if (c == '[') {
                table = tableHeader(root);
            } else if (c != '#' && c != '\n' && c != '\r' && c != -1) {
                keyValue(table);
            }
            endOfLine();
        }
        return root;
    }

    // Tables and keys

    private Map<String, Object> tableHeader(Map<String, Object> root) throws TomlException {
        int start = pos;
        boolean isArray = src.startsWith("[[", pos);
        pos += isArray ? 2 : 1;
        skipSpaces();
        List<String> key = key();
        expect(isArray ? "]]" : "]");
        Map<String, Object> parent = root;
        for (int i = 0; i < key.size() - 1; i++) {
            parent = headerStep(parent, key, i, start);
        }
        String last = key.get(key.size() - 1);
        Object existing = parent.get(last);
        if (isArray) {
            List<Object> tables;
            if (existing == null) {
                tables = new ArrayList<>();
                origins.put(tables, Origin.TABLE_ARRAY);
                parent.put(last, tables);
            } else if (origins.get(existing) == Origin.TABLE_ARRAY) {
                tables = asList(existing);
            } else {
                throw error(start, "cannot append a table to " + name(key, key.size())
                        + ", it is " + describe(existing));
            }
            Map<String, Object> table = newTable(Origin.HEADER);
            tables.add(table);
            return table;
        }
        if (existing == null) {
            Map<String, Object> table = newTable(Origin.HEADER);
            parent.put(last, table);
            return table;
        }
        if (origins.get(existing) == Origin.IMPLICIT) {
            origins.put(existing, Origin.HEADER);
            return asTable(existing);
        }
        throw error(start, "cannot define table " + name(key, key.size())
                + ", it is already " + describe(existing));
    }

    /** Steps from a table to the child that a table header names with key part i. */
    private Map<String, Object> headerStep(Map<String, Object> parent, List<String> key, int i,
            int start) throws TomlException {
        Object child = parent.get(key.get(i));
        if (child == null) {
            Map<String, Object> table = newTable(Origin.IMPLICIT);
            parent.put(key.get(i), table);
            return table;
        }
        Origin origin = origins.get(child);
        if (origin == Origin.TABLE_ARRAY) {
            List<Object> tables = asList(child);
            return asTable(tables.get(tables.size() - 1));
        }
        if (origin != Origin.IMPLICIT && origin != Origin.HEADER && origin != Origin.DOTTED) {
            throw error(start, "cannot add a table to " + name(key, i + 1) + ", it is " + describe(child));
        }
        return asTable(child);
    }

    private void keyValue(Map<String, Object> table) throws TomlException {
        int start = pos;
        List<String> key = key();
        expect("=");
        skipSpaces();
        Map<String, Object> target = table;
        for (int i = 0; i < key.size() - 1; i++) {
            target = dottedStep(target, key, i, start);
        }
        String last = key.get(key.size() - 1);
        if (target.containsKey(last)) {
            throw error(start, "duplicate key " + name(key, key.size()));
        }
        target.put(last, value());
    }

    /** Steps from a table to the child that a dotted key names with key part i. */
    private Map<String, Object> dottedStep(Map<String, Object> parent, List<String> key, int i,
            int start) throws TomlException {
        Object child = parent.get(key.get(i));
        if (child == null) {
            Map<String, Object> table = newTable(Origin.DOTTED);
            parent.put(key.get(i), table);
            return table;
        }
        Origin origin = origins.get(child);
        if (origin == Origin.IMPLICIT) {
            origins.put(child, Origin.DOTTED);
        } else if (origin != Origin.DOTTED) {
            throw error(start, "cannot add keys to " + name(key, i + 1) + ", it is " + describe(child));
        }
        return asTable(child);
    }

    /** Reads a bare, quoted or dotted key and the spaces after it. */
    private List<String> key() throws TomlException {
        List<String> parts = new ArrayList<>();
        while (true) {
            parts.add(simpleKey());
            skipSpaces();
            if (peek() != '.') {
                return parts;
            }
            pos++;
            skipSpaces();
        }
    }

    private String simpleKey() throws TomlException {
        int c = peek();
        if (c == '"' || c == '\'') {
            if (src.startsWith(c == '"' ? "\"\"\"" : "'''", pos)) {
                throw error(pos, "a key cannot be a multi-line string");
            }
            return c == '"' ? basicString() : literalString();
        }
        int start = pos;
        while (isBareKeyChar(peek())) {
            pos++;
        }
        if (pos == start) {
            throw error(pos, "expected a key, found " + found());
        }
        return src.substring(start, pos);
    }

    // Values

    private Object value() throws TomlException {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        switch (peek()) {
            case '"':
                return src.startsWith("\"\"\"", pos) ? multiLineBasicString() : basicString();
            case '\'':
                return src.startsWith("'''", pos) ? multiLineLiteralString() : literalString();
            case '[':
                return array();
            case '{':
                return inlineTable();
            default:
                return startsDateTime() ? dateTime() : number();
        }
    }

    private List<Object> array() throws TomlException {
        enter();
        pos++;
        List<Object> values = new ArrayList<>();
        skipBlank();
        while (peek() != ']') {
            values.add(value());
            skipBlank();
            if (peek() == ',') {
                pos++;
                skipBlank();
            } else if (peek() == -1) {
                throw error(pos, "unterminated array");
            } else if (peek() != ']') {
                throw error(pos, "expected ',' or ']', found " + found());
            }
        }
        pos++;
        nesting--;
        return values;
    }

    private Map<String, Object> inlineTable() throws TomlException {
        enter();
        pos++;
        Map<String, Object> table = newTable(Origin.INLINE);
        skipSpaces();
        if (peek() != '}') {
            while (true) {
                keyValue(table);
                skipSpaces();
                if (peek() != ',') {
                    break;
                }
                pos++;
                skipSpaces();
            }
            if (peek() != '}') {
                throw error(pos, "expected ',' or '}', found " + found());
            }
        }
        pos++;
        nesting--;
        return table;
    }

    private Object number() throws TomlException {
        int start = pos;
        while (isNumberChar(peek())) {
            pos++;
        }
        String token = src.substring(start, pos);
        if (token.isEmpty()) {
            throw error(start, "expected a value, found " + found());
        }
        String plain = token.replace("_", "");
        try {
            if (DECIMAL_INTEGER.matcher(token).matches()) {
                return Long.parseLong(plain);
            }
            if (HEX_INTEGER.matcher(token).matches()) {
                return Long.parseLong(plain.substring(2), 16);
            }
            if (OCTAL_INTEGER.matcher(token).matches()) {
                return Long.parseLong(plain.substring(2), 8);
            }
            if (BINARY_INTEGER.matcher(token).matches()) {
                return Long.parseLong(plain.substring(2), 2);
            }
        } catch (NumberFormatException e) {
            throw error(start, "integer " + token + " does not fit in 64 bits");
        }
        if (FLOAT.matcher(token).matches()) {
            return Double.parseDouble(plain);
        }
        if (SPECIAL_FLOAT.matcher(token).matches()) {
            if (plain.endsWith("nan")) {
                // TOML leaves the sign of a NaN to the parser; -nan keeps its sign bit, as in Python.
                return plain.startsWith("-") ? Double.longBitsToDouble(0xFFF8000000000000L) : Double.NaN;
            }
            return plain.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        throw error(start, "invalid value " + token);
    }

    private boolean startsDateTime() {
        return digitsAt(pos, 4) && at(pos + 4) == '-' || digitsAt(pos, 2) && at(pos + 2) == ':';
    }

    private TomlDateTime dateTime() throws TomlException {
        int start = pos;
        boolean hasDate = at(pos + 2) != ':';
        boolean hasTime = !hasDate;
        boolean hasOffset = false;
        int year = 0, month = 0, day = 0, hour = 0, minute = 0, second = 0, nanosecond = 0, offset = 0;
        if (hasDate) {
            year = digits(4);
            expect("-");
            month = digits(2);
            expect("-");
            day = digits(2);
            if (month < 1 || month > 12) {
                throw error(start, "invalid month " + month);
            }
            if (day < 1 || day > daysInMonth(year, month)) {
                throw error(start, "invalid day " + day + " in month " + month + " of " + year);
            }
            int c = peek();
            hasTime = c == 'T' || c == 't' || c == ' ' && isDigit(at(pos + 1));
            if (hasTime) {
                pos++;
            }
        }
        if (hasTime) {
            hour = digits(2);
            expect(":");
            minute = digits(2);
            expect(":");
            second = digits(2);
            if (peek() == '.') {
                pos++;
                int from = pos;
                while (isDigit(peek())) {
                    pos++;
                }
                if (pos == from) {
                    throw error(pos, "expected a digit, found " + found());
                }
                for (int i = 0; i < 9; i++) {
                    nanosecond = nanosecond * 10 + (from + i < pos ? src.charAt(from + i) - '0' : 0);
                }
            }
            if (hour > 23) {
                throw error(start, "invalid hour " + hour);
            }
            if (minute > 59) {
                throw error(start, "invalid minute " + minute);
            }
            if (second > 60) {
                throw error(start, "invalid second " + second);
            }
            int sign = peek();
            if (hasDate && (sign == 'Z' || sign == 'z')) {
                pos++;
                hasOffset = true;
            } else if (hasDate && (sign == '+' || sign == '-')) {
                int offsetStart = pos++;
                int offsetHour = digits(2);
                expect(":");
                int offsetMinute = digits(2);
                if (offsetHour > 23 || offsetMinute > 59) {
                    throw error(offsetStart, "invalid offset " + src.substring(offsetStart, pos));
                }
                offset = (sign == '-' ? -1 : 1) * (offsetHour * 60 + offsetMinute);
                hasOffset = true;
            }
        }
        TomlDateTime.Kind kind = !hasDate ? TomlDateTime.Kind.LOCAL_TIME
                : !hasTime ? TomlDateTime.Kind.LOCAL_DATE
                : hasOffset ? TomlDateTime.Kind.OFFSET_DATE_TIME
                : TomlDateTime.Kind.LOCAL_DATE_TIME;
        return new TomlDateTime(kind, src.substring(start, pos), year, month, day, hour, minute,
                second, nanosecond, offset);
    }

    // Strings

    private String basicString() throws TomlException {
        int start = pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            int c = peek();
            if (c == '"') {
                pos++;
                return out.toString();
            }
            if (c == -1 || c == '\n' || c == '\r') {
                throw error(start, "unterminated string");
            }
            if (c == '\\') {
                escape(out);
            } else {
                appendTextChar(out);
            }
        }
    }

    private String literalString() throws TomlException {
        int start = pos++;
        while (true) {
            int c = peek();
            if (c == '\'') {
                pos++;
                return src.substring(start + 1, pos - 1);
            }
            if (c == -1 || c == '\n' || c == '\r') {
                throw error(start, "unterminated string");
            }
            pos += textChar();
        }
    }

    private String multiLineBasicString() throws TomlException {
        int start = pos;
        pos += 3;
        newline();
        StringBuilder out = new StringBuilder();
        while (true) {
            int c = peek();
            if (c == -1) {
                throw error(start, "unterminated multi-line string");
            }
            if (c == '"') {
                if (closingQuotes('"', out)) {
                    return out.toString();
                }
            } else if (c == '\\') {
                if (!lineEndingBackslash()) {
                    escape(out);
                }
            } else if (!appendNewline(out)) {
                appendTextChar(out);
            }
        }
    }

    private String multiLineLiteralString() throws TomlException {
        int start = pos;
        pos += 3;
        newline();
        StringBuilder out = new StringBuilder();
        while (true) {
            int c = peek();
            if (c == -1) {
                throw error(start, "unterminated multi-line string");
            }
            if (c == '\'') {
                if (closingQuotes('\'', out)) {
                    return out.toString();
                }
            } else if (!appendNewline(out)) {
                appendTextChar(out);
            }
        }
    }

    /**
     * Reads a run of quotes inside a multi-line string. Three quotes close the string, and up to
     * two more before them still belong to it. Returns whether the string is closed.
     */
    private boolean closingQuotes(char quote, StringBuilder out) {
        int run = 0;
        while (run < 5 && at(pos + run) == quote) {
            run++;
        }
        boolean closes = run >= 3;
        for (int i = closes ? 3 : 0; i < run; i++) {
            out.append(quote);
        }
        pos += run;
        return closes;
    }

    /** Skips a backslash at the end of a line together with all spaces and new lines after it. */
    private boolean lineEndingBackslash() throws TomlException {
        int p = pos + 1;
        while (at(p) == ' ' || at(p) == '\t') {
            p++;
        }
        if (at(p) != '\n' && at(p) != '\r') {
            return false;
        }
        pos = p;
        while (true) {
            if (peek() == ' ' || peek() == '\t') {
                pos++;
            } else if (!newline()) {
                return true;
            }
        }
    }

    private void escape(StringBuilder out) throws TomlException {
        int start = pos;
        int c = at(pos + 1);
        pos += 2;
        switch (c) {
            case 'b':
                out.append('\b');
                return;
            case 't':
                out.append('\t');
                return;
            case 'n':
                out.append('\n');
                return;
            case 'f':
                out.append('\f');
                return;
            case 'r':
                out.append('\r');
                return;
            case '"':
                out.append('"');
                return;
            case '\\':
                out.append('\\');
                return;
            case 'u':
                unicodeEscape(out, start, 4);
                return;
            case 'U':
                unicodeEscape(out, start, 8);
                return;
            default:
                throw error(start, c > ' ' && c < 0x7F
                        ? "invalid escape sequence \\" + (char) c
                        : "invalid escape sequence");
        }
    }

    private void unicodeEscape(StringBuilder out, int start, int digits) throws TomlException {
        long code = 0;
        for (int i = 0; i < digits; i++) {
            int digit = hexValue(peek());
            if (digit < 0) {
                throw error(start, "a \\" + src.charAt(start + 1) + " escape needs " + digits + " hex digits");
            }
            code = code * 16 + digit;
            pos++;
        }
        if (code > 0x10FFFF || code >= 0xD800 && code <= 0xDFFF) {
            throw error(start, "escape " + src.substring(start, pos) + " is not a Unicode scalar value");
        }
        out.appendCodePoint((int) code);
    }

    /** Appends a new line as a line feed, if there is one at pos. */
    private boolean appendNewline(StringBuilder out) throws TomlException {
        if (!newline()) {
            return false;
        }
        out.append('\n');
        return true;
    }

    private void appendTextChar(StringBuilder out) throws TomlException {
        int length = textChar();
        out.append(src, pos, pos + length);
        pos += length;
    }

    /**
     * Checks that the character at pos may stand in a string or a comment, and returns its length:
     * 2 for a surrogate pair, else 1.
     */
    private int textChar() throws TomlException {
        char c = src.charAt(pos);
        if (c < ' ' && c != '\t' || c == 0x7F) {
            throw error(pos, "control character " + codePoint(c) + " is not allowed");
        }
        if (!Character.isSurrogate(c)) {
            return 1;
        }
        if (Character.isHighSurrogate(c) && pos + 1 < src.length()
                && Character.isLowSurrogate(src.charAt(pos + 1))) {
            return 2;
        }
        throw error(pos, "unpaired surrogate " + codePoint(c));
    }

    // Spaces, comments and new lines

    private void skipSpaces() {
        while (peek() == ' ' || peek() == '\t') {
            pos++;
        }
    }

    /** Skips spaces, comments and new lines, as they may stand between the values of an array. */
    private void skipBlank() throws TomlException {
        do {
            skipSpaces();
            if (peek() == '#') {
                comment();
            }
        } while (newline());
    }

    private void comment() throws TomlException {
        pos++;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n' || c == '\r' && at(pos + 1) == '\n') {
                return;
            }
            pos += textChar();
        }
    }

    /** Reads what may follow a key-value pair or a table header: spaces, a comment, a new line. */
    private void endOfLine() throws TomlException {
        skipSpaces();
        if (peek() == '#') {
            comment();
        }
        if (pos < src.length() && !newline()) {
            throw error(pos, "expected the end of the line, found " + found());
        }
    }

    /** Skips a line feed, or a carriage return and a line feed, if there is one at pos. */
    private boolean newline() throws TomlException {
        int c = peek();
        if (c == '\n') {
            pos++;
            return true;
        }
        if (c != '\r') {
            return false;
        }
        if (at(pos + 1) != '\n') {
            throw error(pos, "a carriage return must be followed by a line feed");
        }
        pos += 2;
        return true;
    }

    // Small helpers

    private void enter() throws TomlException {
        if (++nesting > MAX_NESTING) {
            throw error(pos, "arrays and inline tables nest deeper than " + MAX_NESTING + " levels");
        }
    }

    private void expect(String token) throws TomlException {
        if (!src.startsWith(token, pos)) {
            throw error(pos, "expected '" + token + "', found " + found());
        }
        pos += token.length();
    }

    /** Reads exactly count ASCII digits as a number. */
    private int digits(int count) throws TomlException {
        int value = 0;
        for (int i = 0; i < count; i++) {
            int c = peek();
            if (!isDigit(c)) {
                throw error(pos, "expected a digit, found " + found());
            }
            value = value * 10 + c - '0';
            pos++;
        }
        return value;
    }

    private boolean digitsAt(int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (!isDigit(at(i))) {
                return false;
            }
        }
        return true;
    }

    private int peek() {
        return at(pos);
    }

    /** Returns the character at index i, or -1 past the end. */
    private int at(int i) {
        return i < src.length() ? src.charAt(i) : -1;
    }

    private Map<String, Object> newTable(Origin origin) {
        Map<String, Object> table = new LinkedHashMap<>();
        origins.put(table, origin);
        return table;
    }

    /** Says what an existing value is, for an error message. */
    private String describe(Object value) {
        Origin origin = origins.get(value);
        if (origin == null) {
            return value instanceof List ? "an array" : "a value";
        }
        switch (origin) {
            case IMPLICIT:
                return "a table";
            case HEADER:
                return "a table defined by a header";
            case DOTTED:
                return "a table defined by dotted keys";
            case INLINE:
                return "an inline table";
            default:
                return "an array of tables";
        }
    }

    /** Describes the character at pos, for an error message. */
    private String found() {
        int c = peek();
        if (c == -1) {
            return "the end of the file";
        }
        if (c == '\n' || c == '\r') {
            return "the end of the line";
        }
        if (c > ' ' && c < 0x7F) {
            return "'" + (char) c + "'";
        }
        return codePoint(src.codePointAt(pos));
    }

    private TomlException error(int index, String reason) {
        int end = Math.min(index, src.length());
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < end; i++) {
            if (src.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new TomlException(reason, line, src.codePointCount(lineStart, end) + 1);
    }

    /** Writes the first count parts of a key as TOML would, for an error message. */
    private static String name(List<String> key, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String part = key.get(i);
            boolean bare = !part.isEmpty();
            for (int j = 0; j < part.length() && bare; j++) {
                bare = isBareKeyChar(part.charAt(j));
            }
            if (i > 0) {
                out.append('.');
            }
            if (bare) {
                out.append(part);
            } else {
                out.append('"').append(part.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return out.toString();
    }

    private static String codePoint(int c) {
        return String.format(Locale.ROOT, "U+%04X", c);
    }

    private static int daysInMonth(int year, int month) {
        if (month == 2) {
            return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) ? 29 : 28;
        }
        return month == 4 || month == 6 || month == 9 || month == 11 ? 30 : 31;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static int hexValue(int c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static boolean isBareKeyChar(int c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || isDigit(c) || c == '_' || c == '-';
    }

    private static boolean isNumberChar(int c) {
        return isBareKeyChar(c) || c == '+' || c == '.';
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asTable(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }
}
