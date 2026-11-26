package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One change entry ([[changes]], section 17.1): what a version of a file or pack changed.
 * Immutable. In a file, the lists name cheat ids; in a pack, the paths of its files.
 */
public final class Release {

    private final String version;
    private final String date;
    private final String text;
    private final List<String> added;
    private final List<String> fixed;
    private final List<String> removed;
    private final Map<String, String> renamed;

    Release(String version, String date, String text, List<String> added, List<String> fixed, List<String> removed,
            Map<String, String> renamed) {
        this.version = version;
        this.date = date;
        this.text = text;
        this.added = Collections.unmodifiableList(new ArrayList<>(added));
        this.fixed = Collections.unmodifiableList(new ArrayList<>(fixed));
        this.removed = Collections.unmodifiableList(new ArrayList<>(removed));
        this.renamed = Collections.unmodifiableMap(new LinkedHashMap<>(renamed));
    }

    /** Returns the version the entry describes. */
    public String version() {
        return version;
    }

    /** Returns the day the version was made, as "YYYY-MM-DD", or null. */
    public String date() {
        return date;
    }

    /**
     * Returns what changed, for the player, in the file's own language, or null. {@link
     * Updates#text} gives it in the player's language where the file translates it.
     */
    public String text() {
        return text;
    }

    /** Returns what is new in this version, in the entry's order. */
    public List<String> added() {
        return added;
    }

    /** Returns what works again in this version, or works better, in the entry's order. */
    public List<String> fixed() {
        return fixed;
    }

    /** Returns what the version before had and this one no longer has, in the entry's order. */
    public List<String> removed() {
        return removed;
    }

    /** Returns the renames: from the name in the version before to the name in this one, in the entry's order. */
    public Map<String, String> renamed() {
        return renamed;
    }

    @Override
    public String toString() {
        return version;
    }
}
