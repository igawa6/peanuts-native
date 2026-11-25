package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A code module that runs: its identity, where it is loaded, and its bytes as the module file
 * gave them. Immutable once built.
 *
 * <p>Sections map "code", "rodata" and "data" to ranges of offsets. Without any, the whole
 * module is code. The module keeps the image array it was given without copying it, so that a
 * large module costs no second copy; the caller must not change the array afterwards.
 */
public final class Module {

    private final String id;
    private final long base;
    private final byte[] image;
    private final Long size;
    private final Map<String, long[]> sections;

    private Module(Builder b) {
        id = b.id;
        base = b.base;
        image = b.image;
        size = b.size != null ? b.size : (image != null ? Long.valueOf(image.length) : null);
        Map<String, long[]> s = new LinkedHashMap<>(b.sections);
        if (s.isEmpty() && size != null) {
            s.put("code", new long[] {0, size});
        }
        sections = Collections.unmodifiableMap(s);
    }

    /**
     * Starts a module.
     *
     * @param id the module's identity: a build ID for a Switch module, "sha256:" and the hash of
     *     the file elsewhere; null when it is not known
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Builds a {@link Module}. */
    public static final class Builder {
        private final String id;
        private long base;
        private byte[] image;
        private Long size;
        private final Map<String, long[]> sections = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
        }

        /** Sets the address the module is loaded at. The default is 0. */
        public Builder base(long address) {
            this.base = address;
            return this;
        }

        /** Sets the module's bytes as its file gave them, which patterns are searched in. */
        public Builder image(byte[] bytes) {
            this.image = bytes;
            return this;
        }

        /** Sets the module's size in bytes. The default is the size of the image. */
        public Builder size(long bytes) {
            this.size = bytes;
            return this;
        }

        /** Adds a section, such as "code", "rodata" or "data", from start up to but not including end. */
        public Builder section(String name, long start, long end) {
            sections.put(name, new long[] {start, end});
            return this;
        }

        /** Returns the module. */
        public Module build() {
            return new Module(this);
        }
    }

    /** Returns the module's identity, or null when it is not known. */
    public String id() {
        return id;
    }

    /** Returns the address the module is loaded at. */
    public long base() {
        return base;
    }

    /** Returns whether the module has its bytes. */
    public boolean hasImage() {
        return image != null;
    }

    /** Returns the module's size in bytes, or null when it is not known. */
    public Long size() {
        return size;
    }

    /** Returns the names of the module's sections. */
    public List<String> sectionNames() {
        return Collections.unmodifiableList(new ArrayList<>(sections.keySet()));
    }

    /** Returns whether the module has a section of that name. */
    public boolean hasSection(String name) {
        return sections.containsKey(name);
    }

    /** Returns the first offset of a section. */
    public long sectionStart(String name) {
        return section(name)[0];
    }

    /** Returns the offset just after a section. */
    public long sectionEnd(String name) {
        return section(name)[1];
    }

    private long[] section(String name) {
        long[] range = sections.get(name);
        if (range == null) {
            throw new IllegalArgumentException("the module has no section " + name);
        }
        return range;
    }

    byte[] image() {
        return image;
    }

    /** The ranges a pattern is searched in: the named section, or code and rodata. */
    List<long[]> searchRanges(String section) {
        List<long[]> ranges = new ArrayList<>();
        String[] names = section != null ? new String[] {section} : new String[] {"code", "rodata"};
        for (String n : names) {
            if (sections.containsKey(n)) {
                ranges.add(sections.get(n));
            }
        }
        return ranges;
    }
}
