package io.github.davdxpx.mistel.mncf;

/** LZ4 block decompression, as Switch code modules (NSO) use it for their segments. */
final class Lz4 {

    private Lz4() {
    }

    /** The size bytes that the LZ4 block src holds. */
    static byte[] decompress(byte[] src, int size) throws Lz4Exception {
        byte[] dst = new byte[size];
        int n = 0;
        int i = 0;
        int end = src.length;
        while (i < end) {
            int token = src[i++] & 0xFF;
            long lit = token >>> 4;
            if (lit == 15) {
                long[] r = length(src, i, lit);
                i = (int) r[0];
                lit = r[1];
            }
            if (i + lit > end) {
                throw new Lz4Exception("the block ends inside literals");
            }
            // A block that grows past its segment can never end at its size again.
            if (n + lit > size) {
                throw new Lz4Exception("the block is longer than its segment");
            }
            System.arraycopy(src, i, dst, n, (int) lit);
            n += (int) lit;
            i += (int) lit;
            if (i >= end) {
                break;
            }
            if (i + 2 > end) {
                throw new Lz4Exception("the block ends inside an offset");
            }
            int off = (src[i] & 0xFF) | (src[i + 1] & 0xFF) << 8;
            i += 2;
            long match = token & 15;
            if (match == 15) {
                long[] r = length(src, i, match);
                i = (int) r[0];
                match = r[1];
            }
            match += 4;
            int start = n - off;
            if (off == 0 || start < 0) {
                throw new Lz4Exception("a match points before the start");
            }
            if (n + match > size) {
                throw new Lz4Exception("the block is longer than its segment");
            }
            for (int k = 0; k < match; k++) {
                dst[n + k] = dst[start + k];
            }
            n += (int) match;
        }
        if (n != size) {
            throw new Lz4Exception("the block holds " + n + " bytes, the segment " + size);
        }
        return dst;
    }

    /** Reads the bytes that extend a length: (the next position, the length). */
    private static long[] length(byte[] src, int i, long n) throws Lz4Exception {
        while (true) {
            if (i >= src.length) {
                throw new Lz4Exception("the block ends inside a length");
            }
            int b = src[i++] & 0xFF;
            n += b;
            if (b != 255) {
                return new long[] {i, n};
            }
        }
    }
}
