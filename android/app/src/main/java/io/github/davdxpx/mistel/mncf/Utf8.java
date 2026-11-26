package io.github.davdxpx.mistel.mncf;

/**
 * A strict UTF-8 check (RFC 3629): no overlong forms, no surrogates, nothing above U+10FFFF.
 * Written out here so that the result does not depend on a platform's decoder.
 */
final class Utf8 {

    private Utf8() {
    }

    /** Returns null when the bytes are UTF-8, else what is wrong and at which byte, as Python says it. */
    static String check(byte[] data) {
        int i = 0;
        while (i < data.length) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            int need;
            int low = 0x80;
            int high = 0xBF;
            if (b >= 0xC2 && b <= 0xDF) {
                need = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
                need = 2;
                if (b == 0xE0) {
                    low = 0xA0;
                } else if (b == 0xED) {
                    high = 0x9F;
                }
            } else if (b >= 0xF0 && b <= 0xF4) {
                need = 3;
                if (b == 0xF0) {
                    low = 0x90;
                } else if (b == 0xF4) {
                    high = 0x8F;
                }
            } else {
                return "invalid start byte at byte " + i;
            }
            for (int k = 1; k <= need; k++) {
                if (i + k >= data.length) {
                    return "unexpected end of data at byte " + i;
                }
                int c = data[i + k] & 0xFF;
                int lo = k == 1 ? low : 0x80;
                int hi = k == 1 ? high : 0xBF;
                if (c < lo || c > hi) {
                    return "invalid continuation byte at byte " + i;
                }
            }
            i += need + 1;
        }
        return null;
    }
}
