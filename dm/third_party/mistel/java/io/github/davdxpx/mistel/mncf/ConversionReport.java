package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the converter made of the cheat files: one report per entry, groups, credits, warnings,
 * lines that are not code and entries that could not be read. Immutable.
 */
public final class ConversionReport {

    private final List<CheatReport> cheats;
    private final List<String> groups;
    private final List<String> credits;
    private final List<String> warnings;
    private final List<String> notes;
    private final List<String> unreadable;

    ConversionReport(List<CheatReport> cheats, List<String> groups, List<String> credits, List<String> warnings,
            List<String> notes, List<String> unreadable) {
        this.cheats = Collections.unmodifiableList(new ArrayList<>(cheats));
        this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        this.credits = Collections.unmodifiableList(new ArrayList<>(credits));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        this.unreadable = Collections.unmodifiableList(new ArrayList<>(unreadable));
    }

    /** Returns a report for each entry with code, in the order of the files and their entries. */
    public List<CheatReport> cheats() {
        return cheats;
    }

    /** Returns the names of the headings that became groups. */
    public List<String> groups() {
        return groups;
    }

    /** Returns the entries that credit someone, as their names. */
    public List<String> credits() {
        return credits;
    }

    /** Returns warnings, such as a module whose build ID is not the cheat file's. */
    public List<String> warnings() {
        return warnings;
    }

    /** Returns the lines of the files that are neither names nor code, each once. */
    public List<String> notes() {
        return notes;
    }

    /** Returns the names of the entries whose code mixes words with other text; they are left out. */
    public List<String> unreadable() {
        return unreadable;
    }

    /** Returns the number of entries with this outcome. */
    public int count(String outcome) {
        int n = 0;
        for (CheatReport c : cheats) {
            n += c.outcome().equals(outcome) ? 1 : 0;
        }
        return n;
    }
}
