package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the converter made of one entry of one cheat file. Immutable. */
public final class CheatReport {

    /** The cheat finds its places by patterns, so it fits other builds too. */
    public static final String PATTERN = "pattern";
    /** The cheat has core operations with offsets for this build. */
    public static final String BUILD = "build";
    /** The cheat keeps only its raw code; {@link #why()} says why. */
    public static final String RAW = "raw";
    /** The entry only puts back the original code, so it became no cheat. */
    public static final String RESTORE = "restore";
    /** The entry's code mixes words with other text, so it became no cheat; {@link #why()} names the lines. */
    public static final String UNREADABLE = "unreadable";

    private final String id;
    private final String name;
    private final String build;
    private final String outcome;
    private final String why;
    private final int patterns;
    private final int fixed;
    private final int values;
    private final List<String> notes;

    CheatReport(String id, String name, String build, String outcome, String why, int patterns, int fixed, int values,
            List<String> notes) {
        this.id = id;
        this.name = name;
        this.build = build;
        this.outcome = outcome;
        this.why = why;
        this.patterns = patterns;
        this.fixed = fixed;
        this.values = values;
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
    }

    /** Returns the id of the cheat in the new file, or empty text when the entry became no cheat. */
    public String id() {
        return id;
    }

    /** Returns the entry's name. */
    public String name() {
        return name;
    }

    /** Returns the key of the build the entry is for. */
    public String build() {
        return build;
    }

    /** Returns pattern, build, raw or restore. */
    public String outcome() {
        return outcome;
    }

    /** Returns why the cheat keeps only raw code or became no cheat; empty otherwise. */
    public String why() {
        return why;
    }

    /** Returns how many operations find their place by a pattern. */
    public int patterns() {
        return patterns;
    }

    /** Returns how many operations keep offsets for this build. */
    public int fixed() {
        return fixed;
    }

    /** Returns how many values the player sets the cheat got. */
    public int values() {
        return values;
    }

    /** Returns notes on single places. */
    public List<String> notes() {
        return notes;
    }
}
