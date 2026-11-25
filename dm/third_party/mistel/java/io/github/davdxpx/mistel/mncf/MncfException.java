package io.github.davdxpx.mistel.mncf;

/**
 * A file or a pack cannot be used at all. {@link #code()} is one of the codes of Appendix D,
 * such as "encoding", "toml", "format", "schema:game.id" or "zip".
 */
public final class MncfException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String detail;

    /** Makes an exception with its code and a message for people. */
    public MncfException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
        this.detail = detail;
    }

    /** Returns the code of Appendix D. */
    public String code() {
        return code;
    }

    /** Returns what is wrong, in words, without the code. */
    public String detail() {
        return detail;
    }
}
