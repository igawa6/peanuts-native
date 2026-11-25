package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The keys a reader trusts, by name, and the key ids it no longer trusts (section 12). A caller
 * fills it in before reading files; reading does not change it.
 */
public final class TrustList {

    private final Map<String, List<Key>> keys = new LinkedHashMap<>();
    private final Set<String> revoked = new LinkedHashSet<>();

    /** A trusted public key and the name it is shown with. */
    static final class Key {
        final String name;
        final byte[] publicKey;

        Key(String name, byte[] publicKey) {
            this.name = name;
            this.publicKey = publicKey;
        }
    }

    /**
     * Trusts a public key under a name. A key may be trusted under several names.
     *
     * @param publicKey the 32 bytes of an Ed25519 public key
     * @return this list
     * @throws IllegalArgumentException if the key is not 32 bytes
     */
    public TrustList trust(String name, byte[] publicKey) {
        if (publicKey.length != 32) {
            throw new IllegalArgumentException("an Ed25519 public key is 32 bytes");
        }
        String kid = Signatures.keyId(publicKey);
        List<Key> list = keys.get(kid);
        if (list == null) {
            list = new ArrayList<>();
            keys.put(kid, list);
        }
        list.add(new Key(name, publicKey.clone()));
        return this;
    }

    /**
     * Stops trusting a key id: a signature by it makes a file "revoked".
     *
     * @param keyId the 16 hexadecimal digits of the key id, in any case
     * @return this list
     */
    public TrustList revoke(String keyId) {
        revoked.add(Py.lower(keyId));
        return this;
    }

    /** Returns the key ids this list no longer trusts, in lower case. */
    public Set<String> revoked() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(revoked));
    }

    boolean isRevoked(String keyId) {
        return revoked.contains(keyId);
    }

    List<Key> keys(String keyId) {
        List<Key> list = keys.get(keyId);
        return list == null ? Collections.<Key>emptyList() : list;
    }

    private static final Pattern KEY_ID = Pattern.compile("[0-9a-f]{16}");

    /**
     * Returns the trust list a trust list file holds ({@code format = "mncf-keys/1"}, section
     * 12): its keys in file order, and its revoked key ids.
     *
     * @throws MncfException with the code toml for text that is not TOML of the format, format
     *     when {@code format = "mncf-keys/1"} is not the first key, schema:key for a key without
     *     a name, without a public key of 32 bytes in canonical base64, or with an id that is not
     *     the public key's, and schema:revoked for a revoked id that is not 16 lower-case
     *     hexadecimal digits
     */
    @SuppressWarnings("unchecked")
    public static TrustList fromToml(String text) throws MncfException {
        Map<String, Object> doc = Mncf.parseToml(text, "toml");
        if (doc.isEmpty() || !doc.keySet().iterator().next().equals("format") || !"mncf-keys/1".equals(doc.get("format"))) {
            throw new MncfException("format", "`format = \"mncf-keys/1\"` must be the first key of a trust list");
        }
        TrustList trust = new TrustList();
        Object keys = doc.containsKey("key") ? doc.get("key") : Collections.emptyList();
        Object revoked = doc.containsKey("revoked") ? doc.get("revoked") : Collections.emptyList();
        if (!isTableArrayOrEmpty(keys)) {
            throw new MncfException("schema:key", "`key` must be an array of tables");
        }
        for (Object item : (List<Object>) keys) {
            Map<String, Object> k = (Map<String, Object>) item;
            Object name = k.get("name");
            Object pub = k.get("public");
            byte[] raw = new byte[0];
            if (pub instanceof String) {
                try {
                    raw = Base64.getDecoder().decode((String) pub);
                } catch (IllegalArgumentException e) {
                    raw = new byte[0];
                }
            }
            if (!(name instanceof String) || ((String) name).isEmpty() || raw.length != 32
                    || !Base64.getEncoder().encodeToString(raw).equals(pub)) {
                throw new MncfException("schema:key", "a key needs a `name` and a `public` key of 32 bytes in base64");
            }
            if (k.containsKey("id") && !Signatures.keyId(raw).equals(k.get("id"))) {
                throw new MncfException("schema:key", "the key id of " + Py.repr(name) + " is " + Signatures.keyId(raw)
                        + ", not " + Py.repr(k.get("id")));
            }
            trust.trust((String) name, raw);
        }
        if (!isTableArrayOrEmpty(revoked)) {
            throw new MncfException("schema:revoked", "`revoked` must be an array of tables");
        }
        for (Object item : (List<Object>) revoked) {
            Object kid = ((Map<String, Object>) item).get("id");
            if (!Py.matches(KEY_ID, kid)) {
                throw new MncfException("schema:revoked", "a revoked key needs an `id` of 16 lower-case hexadecimal digits");
            }
            trust.revoke((String) kid);
        }
        return trust;
    }

    private static boolean isTableArrayOrEmpty(Object v) {
        if (!(v instanceof List)) {
            return false;
        }
        for (Object x : (List<?>) v) {
            if (!(x instanceof Map)) {
                return false;
            }
        }
        return true;
    }
}
