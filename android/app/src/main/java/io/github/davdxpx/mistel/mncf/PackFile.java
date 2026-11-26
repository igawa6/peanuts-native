package io.github.davdxpx.mistel.mncf;

/** One file that a pack's manifest lists, and whether the pack holds it as listed. Immutable. */
public final class PackFile {

    /** The pack holds the file and its bytes match the manifest. */
    public static final String OK = "ok";
    /** The pack holds the file, but its bytes differ from the manifest; it must not be applied. */
    public static final String HASH = "hash";
    /** The manifest lists the file, but the pack does not hold it. */
    public static final String MISSING = "missing";

    private final String path;
    private final String sha256;
    private final byte[] data;
    private final String status;

    PackFile(String path, String sha256, byte[] data, String status) {
        this.path = path;
        this.sha256 = sha256;
        this.data = data;
        this.status = status;
    }

    /** Returns the file's path in the pack. */
    public String path() {
        return path;
    }

    /** Returns the SHA-256 hash the manifest gives, in lower-case hexadecimal. */
    public String sha256() {
        return sha256;
    }

    /** Returns a copy of the file's bytes when the status is ok, else null. */
    public byte[] data() {
        return data == null ? null : data.clone();
    }

    /** Returns ok, hash or missing. */
    public String status() {
        return status;
    }
}
