package io.github.davdxpx.mistel.mncf;

/**
 * Whether a file or pack is a new version of the one a runtime holds (section 17.2), as {@link
 * Updates#check} and {@link Updates#checkPack} give it. Immutable.
 */
public final class UpdateCheck {

    private final String reason;
    private final MncfFile file;
    private final Pack pack;
    private final String fromVersion;
    private final String toVersion;

    UpdateCheck(String reason, MncfFile file, Pack pack, String fromVersion, String toVersion) {
        this.reason = reason;
        this.file = file;
        this.pack = pack;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    /**
     * Returns the empty string when the new version is taken, else a reason of Appendix E:
     * update:error:&lt;code&gt;, update:signature, update:game, update:id, update:version or
     * update:signer.
     */
    public String reason() {
        return reason;
    }

    /** Returns whether the new version is taken: the reason is empty. */
    public boolean ok() {
        return reason.isEmpty();
    }

    /**
     * Returns the new file where it loads, also when it is not taken, so that a file the player
     * adds by hand can still be used; null where it does not load or for a pack.
     */
    public MncfFile file() {
        return file;
    }

    /** Returns the new pack where it can be read, also when it is not taken; null where not, or for a file. */
    public Pack pack() {
        return pack;
    }

    /** Returns the version of the file or pack the runtime holds, or null when it has none. */
    public String fromVersion() {
        return fromVersion;
    }

    /** Returns the version of the new file or pack, or null when it has none or does not load. */
    public String toVersion() {
        return toVersion;
    }

    @Override
    public String toString() {
        return (reason.isEmpty() ? "ok" : reason) + " (" + fromVersion + " to " + toVersion + ")";
    }
}
