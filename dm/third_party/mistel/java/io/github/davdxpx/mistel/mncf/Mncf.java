package io.github.davdxpx.mistel.mncf;

import io.github.davdxpx.mistel.mncf.toml.Toml;
import io.github.davdxpx.mistel.mncf.toml.TomlDateTime;
import io.github.davdxpx.mistel.mncf.toml.TomlException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Reads the Mistel Native Cheat Format, version 1 ({@code mncf/1}).
 *
 * <pre>{@code
 * MncfFile file = Mncf.load(bytes, trust);              // throws MncfException
 * Runtime runtime = Runtime.builder("switch").module("main", module).build();
 * Evaluation ev = Evaluator.evaluate(file, runtime, Choices.none());
 * }</pre>
 */
public final class Mncf {

    /** The format version this reader reads. */
    public static final String FORMAT = "mncf/1";

    /** The most bytes a file, and an entry of a pack, may have: 16 MiB. */
    public static final int MAX_FILE = 16 * 1024 * 1024;

    /** The extensions this reader knows, with their versions. */
    public static final List<String> KNOWN_EXTENSIONS = Schema.KNOWN_EXTENSIONS;

    /** The capabilities a runtime can have (section 11), in the order reasons name them. */
    public static final List<String> CAPABILITIES =
            Values.list("guest-read", "guest-write", "code-bytes", "code-hook", "buttons", "display");

    /**
     * The parts of the core a runtime may not have yet (section 11.1), in the order they are
     * checked. {@link Runtime.Builder#unsupported} takes them.
     */
    public static final List<String> FEATURES = Values.list("run.once", "run.action", "run.timed",
            "run.monitor", "hold", "toggle", "press", "when", "when.many", "match.any", "trigger.enter",
            "interval", "override");

    private Mncf() {
    }

    /**
     * Reads and checks an .mncf file without trusted keys: a signed file is then "unknown-key".
     *
     * @throws MncfException when the file cannot be used at all
     */
    public static MncfFile load(byte[] data) throws MncfException {
        return load(data, null);
    }

    /**
     * Reads and checks an .mncf file and its signature lines, against the trusted keys and the
     * keys of the file's signers (section 12).
     *
     * @param data the bytes of the file, at most {@link #MAX_FILE} of them
     * @param trust the keys the reader trusts, or null for none
     * @throws MncfException when the file cannot be used at all, with the code "size" for a
     *     file of more than {@link #MAX_FILE} bytes
     */
    public static MncfFile load(byte[] data, TrustList trust) throws MncfException {
        if (data.length > MAX_FILE) {
            throw new MncfException("size", "the file has more than " + MAX_FILE + " bytes");
        }
        byte[] copy = data.clone();
        String text = decode(copy);
        Map<String, Object> doc = parseToml(text, "toml");
        MncfFile.Parts parts = Schema.buildFile(doc);
        SignatureResult signature = Signatures.check(copy, trust, parts.signers);
        byte[] signed = Signatures.split(copy).signed;
        return new MncfFile(copy, signed, signature, parts);
    }

    /**
     * The text of a file's bytes, checked against section 2: UTF-8 without a byte order mark,
     * LF line ends.
     */
    static String decode(byte[] data) throws MncfException {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            throw new MncfException("encoding", "the file starts with a byte order mark");
        }
        String problem = Utf8.check(data);
        if (problem != null) {
            throw new MncfException("encoding", "the file is not UTF-8 (" + problem + ")");
        }
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) {
            throw new MncfException("encoding", "the file has CR characters; line ends are LF only");
        }
        return text;
    }

    /**
     * Parses TOML text for the format: a document without date or time values, nested at most
     * {@link #MAX_DEPTH} levels (section 2).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseToml(String text, String code) throws MncfException {
        Map<String, Object> doc;
        try {
            doc = Toml.parse(text);
        } catch (TomlException e) {
            throw new MncfException(code, e.getMessage());
        }
        checkDepthAndDates(doc, 0, code);
        return (Map<String, Object>) Values.freeze(doc);
    }

    /** How deep tables and arrays may nest: the root table is depth 0, each table or array one more. */
    static final int MAX_DEPTH = 128;

    private static void checkDepthAndDates(Object v, int depth, String code) throws MncfException {
        if (depth > MAX_DEPTH) {
            throw new MncfException(code, "arrays and tables nest deeper than " + MAX_DEPTH + " levels");
        }
        if (v instanceof TomlDateTime) {
            throw new MncfException(code, "the format has no date or time values");
        }
        if (v instanceof Map) {
            for (Object item : ((Map<?, ?>) v).values()) {
                checkDepthAndDates(item, depth + 1, code);
            }
        } else if (v instanceof List) {
            for (Object item : (List<?>) v) {
                checkDepthAndDates(item, depth + 1, code);
            }
        }
    }
}
