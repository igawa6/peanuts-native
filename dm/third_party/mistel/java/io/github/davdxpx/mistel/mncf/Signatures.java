package io.github.davdxpx.mistel.mncf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Signature lines and trust (section 12). */
public final class Signatures {

    private static final byte[] PREFIX = "# mncf-signature: ".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern LINE =
            Pattern.compile("# mncf-signature: (ed25519) ([0-9a-f]{16}) ([A-Za-z0-9+/]{86}==)");
    private static final Pattern ANY_LINE = Pattern.compile("# mncf-signature: ([a-z0-9-]+) .*", Pattern.DOTALL);

    private Signatures() {
    }

    /**
     * Returns the key id of a 32-byte public key: the first 16 hexadecimal digits of its SHA-256
     * hash, in lower case.
     */
    public static String keyId(byte[] publicKey) {
        byte[] hash = sha256(publicKey);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            b.append(String.format("%02x", hash[i] & 0xFF));
        }
        return b.toString();
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The parts of a file: the signed bytes, the lines from the first signature line on, and whether all are signature lines. */
    static final class Split {
        final byte[] signed;
        final List<byte[]> lines;
        final boolean clean;

        Split(byte[] signed, List<byte[]> lines, boolean clean) {
            this.signed = signed;
            this.lines = lines;
            this.clean = clean;
        }
    }

    static Split split(byte[] data) {
        int start = startsWith(data, 0) ? 0 : -1;
        if (start < 0) {
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n' && startsWith(data, i + 1)) {
                    start = i + 1;
                    break;
                }
            }
        }
        if (start < 0) {
            return new Split(data, new ArrayList<byte[]>(), true);
        }
        List<byte[]> lines = new ArrayList<>();
        int from = start;
        for (int i = start; i <= data.length; i++) {
            if (i == data.length || data[i] == '\n') {
                lines.add(Arrays.copyOfRange(data, from, i));
                from = i + 1;
            }
        }
        if (!lines.isEmpty() && lines.get(lines.size() - 1).length == 0) {
            lines.remove(lines.size() - 1);
        }
        boolean clean = true;
        for (byte[] line : lines) {
            clean &= startsWith(line, 0);
        }
        return new Split(Arrays.copyOf(data, start), lines, clean);
    }

    private static boolean startsWith(byte[] data, int at) {
        if (data.length - at < PREFIX.length) {
            return false;
        }
        for (int i = 0; i < PREFIX.length; i++) {
            if (data[at + i] != PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the bytes a signature covers: everything before the first line that starts with
     * "# mncf-signature: ", or all of the data when there is none.
     */
    public static byte[] signedBytes(byte[] data) {
        return split(data).signed.clone();
    }

    /**
     * Checks the signature lines of a file's or a pack manifest's bytes against the trust list.
     * When lines disagree, invalid wins over revoked, revoked over verified, and verified over
     * unknown-key.
     *
     * @param trust the trusted keys, or null for none
     */
    public static SignatureResult check(byte[] data, TrustList trust) {
        return check(data, trust, null);
    }

    /**
     * Checks the signature lines of a file's or a pack manifest's bytes against the trust list
     * and the public keys of its signers key (section 12). A line by a key of the signers that
     * the reader does not trust is checked with that key: it counts as unknown-key where it
     * verifies, and as invalid where it does not. When lines disagree, invalid wins over revoked,
     * revoked over verified, and verified over unknown-key.
     *
     * @param trust the trusted keys, or null for none
     * @param signers the 32-byte public keys of the signers key, or null for none
     */
    public static SignatureResult check(byte[] data, TrustList trust, List<byte[]> signers) {
        if (trust == null) {
            trust = new TrustList();
        }
        Map<String, byte[]> own = new LinkedHashMap<>();
        if (signers != null) {
            for (byte[] key : signers) {
                own.put(keyId(key), key.clone());
            }
        }
        Split split = split(data);
        List<String> none = new ArrayList<>();
        if (split.lines.isEmpty()) {
            return new SignatureResult(SignatureResult.UNSIGNED, none, none);
        }
        if (!split.clean) {
            return new SignatureResult(SignatureResult.INVALID, none, none);
        }
        List<String> results = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> keyIds = new ArrayList<>();
        Map<String, byte[]> verified = new LinkedHashMap<>();
        for (byte[] raw : split.lines) {
            // A signature line is ASCII; any other byte keeps it from matching, as in a bytes pattern.
            String line = new String(raw, StandardCharsets.ISO_8859_1);
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                Matcher other = ANY_LINE.matcher(line);
                if (other.matches() && !other.group(1).equals("ed25519")) {
                    results.add(SignatureResult.UNKNOWN_KEY);       // an algorithm this reader does not know
                } else {
                    results.add(SignatureResult.INVALID);
                }
                continue;
            }
            String kid = m.group(2);
            String sig64 = m.group(3);
            byte[] sig = Base64.getDecoder().decode(sig64);
            keyIds.add(kid);
            if (!Base64.getEncoder().encodeToString(sig).equals(sig64)) {
                results.add(SignatureResult.INVALID);
            } else if (trust.isRevoked(kid)) {
                results.add(SignatureResult.REVOKED);
            } else if (!trust.keys(kid).isEmpty()) {
                List<String> good = new ArrayList<>();
                for (TrustList.Key key : trust.keys(kid)) {
                    if (Ed25519.verify(key.publicKey, split.signed, sig)) {
                        good.add(key.name);
                        if (!verified.containsKey(kid)) {
                            verified.put(kid, key.publicKey);
                        }
                    }
                }
                results.add(good.isEmpty() ? SignatureResult.INVALID : SignatureResult.VERIFIED);
                names.addAll(good);
            } else if (own.containsKey(kid)) {
                // A key of the file's own signers: not trusted, but a line it does not verify is bad.
                boolean ok = Ed25519.verify(own.get(kid), split.signed, sig);
                results.add(ok ? SignatureResult.UNKNOWN_KEY : SignatureResult.INVALID);
                if (ok && !verified.containsKey(kid)) {
                    verified.put(kid, own.get(kid));
                }
            } else {
                results.add(SignatureResult.UNKNOWN_KEY);
            }
        }
        for (String result : new String[] {SignatureResult.INVALID, SignatureResult.REVOKED,
                SignatureResult.VERIFIED, SignatureResult.UNKNOWN_KEY}) {
            if (results.contains(result)) {
                return new SignatureResult(result, result.equals(SignatureResult.VERIFIED) ? names : none, keyIds,
                        verified);
            }
        }
        return new SignatureResult(SignatureResult.UNSIGNED, none, none);
    }
}
