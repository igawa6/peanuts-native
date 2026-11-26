package io.github.davdxpx.mistel.mncf;

/**
 * The guest memory of a running game, as the original console addressed it. A runtime gives it
 * to the reader so that pointer paths, conditions and absolute addresses can be resolved.
 */
public interface GuestMemory {

    /**
     * Returns the bytes of guest memory at an address.
     *
     * @param address the address, an unsigned 64-bit number
     * @param size how many bytes to read, at least 1
     * @return exactly size bytes, or null when any of them lies outside guest memory
     */
    byte[] read(long address, int size);
}
