package io.github.davdxpx.mistel.mncf;

/** An LZ4 block in a code module is broken or does not hold the size its segment gives. */
public final class Lz4Exception extends CodeModuleException {

    private static final long serialVersionUID = 1L;

    /** Makes an exception with a message for people. */
    public Lz4Exception(String message) {
        super(message);
    }
}
