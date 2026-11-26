package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the runtime that applies a file has and knows (section 11). A reader's caller fills it
 * in with {@link #builder}. Immutable once built.
 *
 * <p>Without a {@link GuestMemory}, the reader reads module bytes at their base address and
 * nothing else.
 */
public final class Runtime {

    private final String platform;
    private final String gameId;
    private final String arch;
    private final Map<String, Module> modules;
    private final Map<String, long[]> regions;
    private final GuestMemory memory;
    private final Set<String> capabilities;
    private final Set<String> extensions;
    private final Set<String> rawSyntaxes;
    private final String build;
    private final Set<String> held;
    private final Set<String> unsupported;
    private final Map<String, List<long[]>> rewritten;

    private Runtime(Builder b) {
        platform = b.platform;
        gameId = b.gameId;
        arch = b.arch;
        modules = Collections.unmodifiableMap(new LinkedHashMap<>(b.modules));
        regions = Collections.unmodifiableMap(new LinkedHashMap<>(b.regions));
        memory = b.memory;
        capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(b.capabilities));
        extensions = Collections.unmodifiableSet(new LinkedHashSet<>(b.extensions));
        rawSyntaxes = Collections.unmodifiableSet(new LinkedHashSet<>(b.rawSyntaxes));
        build = b.build;
        held = Collections.unmodifiableSet(new LinkedHashSet<>(b.held));
        unsupported = Collections.unmodifiableSet(new LinkedHashSet<>(b.unsupported));
        Map<String, List<long[]>> runs = new LinkedHashMap<>();
        for (Map.Entry<String, List<long[]>> e : b.rewritten.entrySet()) {
            runs.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        rewritten = Collections.unmodifiableMap(runs);
    }

    /**
     * Starts a runtime of a platform of Appendix A, such as "switch". It has every capability,
     * every extension this reader knows and the raw syntax "atmosphere" until told otherwise.
     */
    public static Builder builder(String platform) {
        return new Builder(platform);
    }

    /** Builds a {@link Runtime}. */
    public static final class Builder {
        private final String platform;
        private String gameId;
        private String arch;
        private final Map<String, Module> modules = new LinkedHashMap<>();
        private final Map<String, long[]> regions = new LinkedHashMap<>();
        private GuestMemory memory;
        private Collection<String> capabilities = Mncf.CAPABILITIES;
        private Collection<String> extensions = Mncf.KNOWN_EXTENSIONS;
        private Collection<String> rawSyntaxes = Collections.singletonList("atmosphere");
        private String build;
        private Collection<String> held = Collections.emptyList();
        private Collection<String> unsupported = Collections.emptyList();
        private final Map<String, List<long[]>> rewritten = new LinkedHashMap<>();

        private Builder(String platform) {
            this.platform = platform;
        }

        /** Sets the ID of the game that runs; without it, any game ID of the platform matches. */
        public Builder gameId(String id) {
            this.gameId = id;
            return this;
        }

        /** Sets the architecture; the default is the platform's first. */
        public Builder arch(String arch) {
            this.arch = arch;
            return this;
        }

        /** Adds a code module that runs, under its name such as "main". */
        public Builder module(String name, Module module) {
            modules.put(name, module);
            return this;
        }

        /** Adds a memory region, such as "heap", with its base address and size. */
        public Builder region(String name, long base, long size) {
            regions.put(name, new long[] {base, size});
            return this;
        }

        /** Sets the guest memory. */
        public Builder memory(GuestMemory memory) {
            this.memory = memory;
            return this;
        }

        /** Sets the capabilities the runtime has, out of {@link Mncf#CAPABILITIES}. */
        public Builder capabilities(Collection<String> names) {
            this.capabilities = new ArrayList<>(names);
            return this;
        }

        /** Sets the capabilities the runtime has, out of {@link Mncf#CAPABILITIES}. */
        public Builder capabilities(String... names) {
            return capabilities(Arrays.asList(names));
        }

        /** Sets the extensions the runtime supports, with their versions. */
        public Builder extensions(Collection<String> names) {
            this.extensions = new ArrayList<>(names);
            return this;
        }

        /** Sets the extensions the runtime supports, with their versions. */
        public Builder extensions(String... names) {
            return extensions(Arrays.asList(names));
        }

        /** Sets the raw syntaxes the runtime can apply, such as "atmosphere". */
        public Builder rawSyntaxes(Collection<String> names) {
            this.rawSyntaxes = new ArrayList<>(names);
            return this;
        }

        /** Sets the raw syntaxes the runtime can apply, such as "atmosphere". */
        public Builder rawSyntaxes(String... names) {
            return rawSyntaxes(Arrays.asList(names));
        }

        /**
         * Names the build that runs, as a recompilation does that knows its build itself. The
         * modules are then not compared.
         */
        public Builder build(String key) {
            this.build = key;
            return this;
        }

        /** Sets the buttons the player holds now. */
        public Builder held(Collection<String> buttons) {
            this.held = new ArrayList<>(buttons);
            return this;
        }

        /** Sets the buttons the player holds now. */
        public Builder held(String... buttons) {
            return held(Arrays.asList(buttons));
        }

        /**
         * Sets the parts of the core the runtime does not have yet, out of {@link Mncf#FEATURES}.
         * A cheat that uses one is refused with "runtime:unsupported:" and the part.
         */
        public Builder unsupported(Collection<String> features) {
            this.unsupported = new ArrayList<>(features);
            return this;
        }

        /** Sets the parts of the core the runtime does not have yet, out of {@link Mncf#FEATURES}. */
        public Builder unsupported(String... features) {
            return unsupported(Arrays.asList(features));
        }

        /**
         * Adds a run of code that the runtime rewrote for its own reasons, as an offset and a
         * length in bytes in a module, both read as unsigned. A patch or a stub over it is refused
         * with "runtime:rewritten".
         */
        public Builder rewritten(String module, long offset, long length) {
            List<long[]> runs = rewritten.get(module);
            if (runs == null) {
                runs = new ArrayList<>();
                rewritten.put(module, runs);
            }
            runs.add(new long[] {offset, length});
            return this;
        }

        /** Returns the runtime. */
        public Runtime build() {
            return new Runtime(this);
        }
    }

    /** Returns the platform's name. */
    public String platform() {
        return platform;
    }

    /** Returns the ID of the game that runs, or null. */
    public String gameId() {
        return gameId;
    }

    /** Returns the architecture, or null for the platform's default. */
    public String arch() {
        return arch;
    }

    /** Returns the modules that run, by name. */
    public Map<String, Module> modules() {
        return modules;
    }

    /** Returns the names of the memory regions. */
    public Set<String> regionNames() {
        return regions.keySet();
    }

    /** Returns the base address of a region. */
    public long regionBase(String name) {
        return region(name)[0];
    }

    /** Returns the size of a region in bytes. */
    public long regionSize(String name) {
        return region(name)[1];
    }

    private long[] region(String name) {
        long[] r = regions.get(name);
        if (r == null) {
            throw new IllegalArgumentException("the runtime has no region " + name);
        }
        return r;
    }

    /** Returns the guest memory, or null. */
    public GuestMemory memory() {
        return memory;
    }

    /** Returns the capabilities. */
    public Set<String> capabilities() {
        return capabilities;
    }

    /** Returns the extensions the runtime supports. */
    public Set<String> extensions() {
        return extensions;
    }

    /** Returns the raw syntaxes the runtime can apply. */
    public Set<String> rawSyntaxes() {
        return rawSyntaxes;
    }

    /** Returns the build the runtime names itself, or null. */
    public String build() {
        return build;
    }

    /** Returns the buttons held now. */
    public Set<String> held() {
        return held;
    }

    /** Returns the parts of the core the runtime does not have yet. */
    public Set<String> unsupported() {
        return unsupported;
    }

    /**
     * Whether the runtime rewrote any byte of a module from an offset on for a length, all read
     * as unsigned.
     */
    public boolean wasRewritten(String module, long offset, long length) {
        List<long[]> runs = rewritten.get(module);
        if (runs == null) {
            return false;
        }
        for (long[] r : runs) {
            if (below(r[0], offset, length) && below(offset, r[0], r[1])) {
                return true;
            }
        }
        return false;
    }

    /** Whether a is less than b plus c, all unsigned, without overflow. */
    private static boolean below(long a, long b, long c) {
        long sum = b + c;
        return Long.compareUnsigned(sum, b) < 0 || Long.compareUnsigned(a, sum) < 0;
    }

    long[] regionOrNull(String name) {
        return regions.get(name);
    }

    /** Bytes of guest memory, or without guest memory the bytes of a module, or null. */
    byte[] read(long address, int size) {
        if (memory != null) {
            return memory.read(address, size);
        }
        for (Module m : modules.values()) {
            byte[] data = m.image();
            if (data == null || Long.compareUnsigned(address, m.base()) < 0) {
                continue;
            }
            long offset = address - m.base();
            if (Long.compareUnsigned(offset, data.length) <= 0 && data.length - offset >= size) {
                byte[] out = new byte[size];
                System.arraycopy(data, (int) offset, out, 0, size);
                return out;
            }
        }
        return null;
    }
}
