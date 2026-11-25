package io.github.davdxpx.mistel.mncf;

/**
 * The [update] table of a file or a pack manifest (section 17.1): the address of the newest
 * version, and its host. Immutable; only an address that section 17.3 allows becomes one.
 */
public final class Update {

    private final String url;
    private final String host;

    Update(String url, String host) {
        this.url = url;
        this.host = host;
    }

    /** Returns the address of the newest version, as the file wrote it. */
    public String url() {
        return url;
    }

    /** Returns the host the address names, the one a runtime asks the player about. */
    public String host() {
        return host;
    }

    @Override
    public String toString() {
        return url;
    }
}
