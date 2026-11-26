package io.github.davdxpx.mistel.mncf;

/**
 * A file that is not an Atmosphère cheat file, or that holds no cheat with code. Its code is
 * "atmosphere".
 */
public final class AtmosphereException extends ConvertException {

    private static final long serialVersionUID = 1L;

    /** Makes an exception with a message for people. */
    public AtmosphereException(String message) {
        super("atmosphere", message);
    }
}
