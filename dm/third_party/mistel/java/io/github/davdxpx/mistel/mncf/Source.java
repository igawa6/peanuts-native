package io.github.davdxpx.mistel.mncf;

/**
 * One Atmosphère cheat file for the converter: the build ID of the main module it is for, its
 * text, and what else is known about its build. Immutable.
 */
public final class Source {

    private final String buildId;
    private final String text;
    private final String version;
    private final CodeModule module;
    private final String buildKey;

    private Source(Builder b) {
        buildId = b.buildId;
        text = b.text;
        version = b.version;
        module = b.module;
        buildKey = b.buildKey;
    }

    /**
     * Starts a source.
     *
     * @param buildId the build ID of the main module, in hexadecimal, as cheat files name it
     * @param text the text of the cheat file
     */
    public static Builder builder(String buildId, String text) {
        return new Builder(buildId, text);
    }

    /** Builds a {@link Source}. */
    public static final class Builder {
        private final String buildId;
        private final String text;
        private String version;
        private CodeModule module;
        private String buildKey;

        private Builder(String buildId, String text) {
            this.buildId = buildId;
            this.text = text;
        }

        /** Sets the version the player sees, which also names the build in the file. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** Sets the main module of the build, for expect and patterns. */
        public Builder module(CodeModule module) {
            this.module = module;
            return this;
        }

        /** Sets the build's name in the file; the default is the version, else the build ID. */
        public Builder buildKey(String key) {
            this.buildKey = key;
            return this;
        }

        /** Returns the source. */
        public Source build() {
            return new Source(this);
        }
    }

    /** Returns the build ID of the main module. */
    public String buildId() {
        return buildId;
    }

    /** Returns the text of the cheat file. */
    public String text() {
        return text;
    }

    /** Returns the version the player sees, or null. */
    public String version() {
        return version;
    }

    /** Returns the main module, or null. */
    public CodeModule module() {
        return module;
    }

    /** Returns the build's name in the file, or null for the default. */
    public String buildKey() {
        return buildKey;
    }
}
