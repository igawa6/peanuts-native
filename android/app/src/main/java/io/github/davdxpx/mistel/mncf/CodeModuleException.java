package io.github.davdxpx.mistel.mncf;

/** The bytes of a code module, or a list of runs, cannot be read. */
public class CodeModuleException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Makes an exception with a message for people. */
    public CodeModuleException(String message) {
        super(message);
    }
}
