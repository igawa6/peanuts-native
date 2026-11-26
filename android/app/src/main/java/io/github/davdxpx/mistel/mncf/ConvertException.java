package io.github.davdxpx.mistel.mncf;

/**
 * Cheat or patch files cannot become one .mncf file. {@link #code()} says why: "source" for
 * sources that do not fit together, such as two files that name the same build, "patch" for a
 * file that is no patch file ({@link PatchFileException}), or "atmosphere" for a file that is not
 * an Atmosphère cheat file ({@link AtmosphereException}).
 */
public class ConvertException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String code;

    /** Makes an exception with the code "source" and a message for people. */
    public ConvertException(String message) {
        this("source", message);
    }

    /** Makes an exception with a code and a message for people. */
    protected ConvertException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Returns "source", "atmosphere" or "patch". */
    public String code() {
        return code;
    }
}
