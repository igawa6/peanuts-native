package io.github.davdxpx.mistel.mncf;

/** A file that is not a patch file of a format {@link Patches} reads. Its code is "patch". */
public final class PatchFileException extends ConvertException {

    private static final long serialVersionUID = 1L;

    /** Makes an exception with a message for people. */
    public PatchFileException(String message) {
        super("patch", message);
    }
}
