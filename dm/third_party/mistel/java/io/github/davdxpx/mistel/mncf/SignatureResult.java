package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The result of section 12 for a file or a pack manifest. Immutable. */
public final class SignatureResult {

    /** A trusted key signed it, and no signature line is bad. */
    public static final String VERIFIED = "verified";
    /** It has no signature line. */
    public static final String UNSIGNED = "unsigned";
    /** It is signed, but by no key the reader knows. */
    public static final String UNKNOWN_KEY = "unknown-key";
    /** A signature line is malformed or does not match. A runtime must not apply it. */
    public static final String INVALID = "invalid";
    /** A revoked key signed it. A runtime must not apply it. */
    public static final String REVOKED = "revoked";

    private final String result;
    private final List<String> signers;
    private final List<String> keyIds;
    private final Map<String, byte[]> verified;

    SignatureResult(String result, List<String> signers, List<String> keyIds) {
        this(result, signers, keyIds, Collections.<String, byte[]>emptyMap());
    }

    SignatureResult(String result, List<String> signers, List<String> keyIds, Map<String, byte[]> verified) {
        this.result = result;
        this.signers = Collections.unmodifiableList(new ArrayList<>(signers));
        this.keyIds = Collections.unmodifiableList(new ArrayList<>(keyIds));
        this.verified = Collections.unmodifiableMap(copy(verified));
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> keys) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : keys.entrySet()) {
            out.put(e.getKey(), e.getValue().clone());
        }
        return out;
    }

    /** Returns verified, unsigned, unknown-key, invalid or revoked. */
    public String result() {
        return result;
    }

    /** Returns the names of the trusted keys that signed it, when the result is verified. */
    public List<String> signers() {
        return signers;
    }

    /** Returns the key id of every well-formed signature line, in file order. */
    public List<String> keyIds() {
        return keyIds;
    }

    /**
     * Returns the key id and public key of each signature line that verified, in file order: with
     * a key the reader trusts, or with a key of the file's signers (section 12). A runtime
     * compares them to see that a new version of a file was signed by a key that signed the one
     * it holds (section 17.2). Each call gives a new map with copies of the public keys.
     */
    public Map<String, byte[]> verified() {
        return Collections.unmodifiableMap(copy(verified));
    }

    /** The map of {@link #verified()} without copies, for the library itself. */
    Map<String, byte[]> verifiedKeys() {
        return verified;
    }

    @Override
    public String toString() {
        return result + (signers.isEmpty() ? "" : " " + signers);
    }
}
