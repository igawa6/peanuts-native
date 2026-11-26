package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a cheat does in one runtime: its state, the reason, and its resolved operations. Immutable. */
public final class CheatResult {

    /** The cheat is not switched on. */
    public static final String OFF = "off";
    /** The cheat is on and its operations apply. */
    public static final String ON = "on";
    /** The cheat is on but waits: for held buttons, or for a pointer path that cannot be followed yet. */
    public static final String WAITING = "waiting";
    /** The cheat cannot be used here; the reason says why. */
    public static final String REFUSED = "refused";

    private final String id;
    private final String state;
    private final String reason;
    private final String uses;
    private final List<ResolvedOp> ops;

    CheatResult(String id, String state, String reason, String uses, List<ResolvedOp> ops) {
        this.id = id;
        this.state = state;
        this.reason = reason;
        this.uses = uses;
        this.ops = Collections.unmodifiableList(new ArrayList<>(ops));
    }

    /** Returns the cheat's id. */
    public String id() {
        return id;
    }

    /** Returns off, on, waiting or refused. */
    public String state() {
        return state;
    }

    /**
     * Returns why the cheat is refused or waits, such as capability-missing:code-hook or
     * path-broken; empty when there is no reason.
     */
    public String reason() {
        return reason;
    }

    /** Returns core when the cheat's own operations apply, raw when its raw code does, or empty when refused. */
    public String uses() {
        return uses;
    }

    /** Returns the resolved operations, in file order, or a single raw operation. Empty when refused. */
    public List<ResolvedOp> ops() {
        return ops;
    }

    @Override
    public String toString() {
        return id + ": " + state + (reason.isEmpty() ? "" : " (" + reason + ")");
    }
}
