package io.github.davdxpx.mistel.mncf.toml;

/** A document is not valid TOML 1.0.0. Says what is wrong and where, by line and column. */
public final class TomlException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String reason;
    private final int line;
    private final int column;

    TomlException(String reason, int line, int column) {
        super("line " + line + ", column " + column + ": " + reason);
        this.reason = reason;
        this.line = line;
        this.column = column;
    }

    /** Returns what is wrong, without the position. */
    public String getReason() {
        return reason;
    }

    /** Returns the line of the error, counted from 1. */
    public int getLine() {
        return line;
    }

    /** Returns the column of the error in Unicode code points, counted from 1. */
    public int getColumn() {
        return column;
    }
}
