package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The build that runs and every cheat's result, as {@link Evaluator#evaluate} gives them. Immutable. */
public final class Evaluation {

    private final String build;
    private final List<CheatResult> cheats;

    Evaluation(String build, List<CheatResult> cheats) {
        this.build = build;
        this.cheats = Collections.unmodifiableList(new ArrayList<>(cheats));
    }

    /** Returns the key of the build that runs, or null when no build of the file matches. */
    public String build() {
        return build;
    }

    /** Returns the result of each cheat, in file order. */
    public List<CheatResult> cheats() {
        return cheats;
    }

    /** Returns the result of the cheat with that id, or null. */
    public CheatResult cheat(String id) {
        for (CheatResult c : cheats) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }
}
