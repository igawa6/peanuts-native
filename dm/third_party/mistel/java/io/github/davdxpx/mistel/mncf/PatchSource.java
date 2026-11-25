package io.github.davdxpx.mistel.mncf;

/**
 * One patch file of a code module for the converter: its bytes, the module it patches, and what
 * else is known. Immutable.
 *
 * <p>A patch needs expect, which a patch file does not hold, so the module is required: the
 * original bytes come from it. The build ID comes from the file itself (@nsobid-), else from its
 * name ("&lt;build ID&gt;.ips"), else from {@link Builder#buildId}, else from the module.
 */
public final class PatchSource {

    private final byte[] data;
    private final CodeModule module;
    private final String fileName;
    private final String setName;
    private final String moduleName;
    private final String buildId;
    private final String version;
    private final String buildKey;

    private PatchSource(Builder b) {
        data = b.data;
        module = b.module;
        fileName = b.fileName;
        setName = b.setName;
        moduleName = b.moduleName;
        buildId = b.buildId;
        version = b.version;
        buildKey = b.buildKey;
    }

    /** Starts a source of the bytes of an IPS, IPS32 or IPSwitch file. */
    public static Builder builder(byte[] data) {
        return new Builder(data);
    }

    /** Builds a {@link PatchSource}. */
    public static final class Builder {
        private final byte[] data;
        private CodeModule module;
        private String fileName;
        private String setName;
        private String moduleName = "main";
        private String buildId;
        private String version;
        private String buildKey;

        private Builder(byte[] data) {
            this.data = data.clone();
        }

        /** Sets the module the file patches, for expect and patterns. */
        public Builder module(CodeModule module) {
            this.module = module;
            return this;
        }

        /** Sets the file's name or path, such as "exefs_patches/60fps/&lt;build ID&gt;.ips". */
        public Builder fileName(String name) {
            this.fileName = name;
            return this;
        }

        /** Sets the patch set: its folder in exefs_patches, or the mod's name. The default comes from the file name. */
        public Builder setName(String name) {
            this.setName = name;
            return this;
        }

        /** Sets the module's name: main, the default, sdk, or subsdk0 to subsdk9. */
        public Builder moduleName(String name) {
            this.moduleName = name;
            return this;
        }

        /** Sets the module's build ID, for a file whose text and name do not give it. */
        public Builder buildId(String id) {
            this.buildId = id;
            return this;
        }

        /** Sets the version the player sees, which also names the build in the file. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /** Sets the build's name in the file; the default is the version, else the build ID. */
        public Builder buildKey(String key) {
            this.buildKey = key;
            return this;
        }

        /** Returns the source. */
        public PatchSource build() {
            return new PatchSource(this);
        }
    }

    /** Returns the bytes of the file. */
    public byte[] data() {
        return data.clone();
    }

    byte[] dataShared() {
        return data;
    }

    /** Returns the module the file patches, or null. */
    public CodeModule module() {
        return module;
    }

    /** Returns the file's name, or null. */
    public String fileName() {
        return fileName;
    }

    /** Returns the patch set's name, or null. */
    public String setName() {
        return setName;
    }

    /** Returns the module's name. */
    public String moduleName() {
        return moduleName;
    }

    /** Returns the build ID given, or null. */
    public String buildId() {
        return buildId;
    }

    /** Returns the version, or null. */
    public String version() {
        return version;
    }

    /** Returns the build's name in the file, or null. */
    public String buildKey() {
        return buildKey;
    }
}
