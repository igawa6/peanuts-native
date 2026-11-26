package io.github.davdxpx.mistel.mncf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One build of the game that a file knows (section 5). Immutable. */
public final class Build {

    private final String key;
    private final String version;
    private final Map<String, String> modules;

    Build(String key, String version, Map<String, String> modules) {
        this.key = key;
        this.version = version;
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }

    /** Returns the key of the build in the file's builds table. */
    public String key() {
        return key;
    }

    /** Returns the version name shown to players; the key when the file gives none. */
    public String version() {
        return version;
    }

    /** Returns the identity of each module of the build, by module name, in file order. */
    public Map<String, String> modules() {
        return modules;
    }
}
