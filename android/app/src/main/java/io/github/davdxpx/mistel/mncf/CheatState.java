package io.github.davdxpx.mistel.mncf;

/** A cheat's state between passes or after one, as the {@link Engine} gives it. Immutable. */
public final class CheatState {

    private final String id;
    private final String state;
    private final String reason;

    CheatState(String id, String state, String reason) {
        this.id = id;
        this.state = state;
        this.reason = reason;
    }

    /** Returns the cheat's id. */
    public String id() {
        return id;
    }

    /** Returns off, on, waiting or refused, as {@link CheatResult} names them. */
    public String state() {
        return state;
    }

    /**
     * Returns the reason: why the cheat is refused or waits, done for a cheat that switched
     * itself off, or empty.
     */
    public String reason() {
        return reason;
    }

    @Override
    public String toString() {
        return id + ": " + state + (reason.isEmpty() ? "" : " (" + reason + ")");
    }
}
