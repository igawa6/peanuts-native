package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The cheats a runtime may mark in a new version of a file (section 17.4), as {@link
 * Updates#marks} gives them. Immutable.
 */
public final class Marks {

    private final List<String> added;
    private final List<String> fixed;

    Marks(List<String> added, List<String> fixed) {
        this.added = Collections.unmodifiableList(new ArrayList<>(added));
        this.fixed = Collections.unmodifiableList(new ArrayList<>(fixed));
    }

    /** Returns the ids of the cheats to mark as new, in file order. */
    public List<String> added() {
        return added;
    }

    /** Returns the ids of the cheats to mark as fixed, in file order; none of them is also new. */
    public List<String> fixed() {
        return fixed;
    }

    @Override
    public String toString() {
        return "new " + added + ", fixed " + fixed;
    }
}
