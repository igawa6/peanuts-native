package dev.peanuts.users;

import java.io.IOException;

/**
 * Nintendo's Yaz0 compression, undone: "Yaz0", the u32 big-endian size, 8 reserved bytes, then groups of a flag byte and eight items, each a literal byte (flag 1)
 * or a back reference (flag 0: two bytes, distance minus one in the low 12 bits; a length of 2 to 17 in the high 4 bits, or 18 to 273 from a third byte when they
 * are 0). No Android classes: Yaz0JvmTest checks it.
 */
final class Yaz0 {
    private Yaz0() {}

    /** The data inside; refused when it is not Yaz0, larger than max, or a reference points before the start or the input ends early */
    static byte[] decode(byte[] in, int max) throws IOException {
        if (in.length < 16 || in[0] != 'Y' || in[1] != 'a' || in[2] != 'z' || in[3] != '0') throw new IOException("not Yaz0");
        long size = (in[4] & 0xffL) << 24 | (in[5] & 0xff) << 16 | (in[6] & 0xff) << 8 | (in[7] & 0xff);
        if (size > max) throw new IOException("Yaz0 of " + size + " bytes, more than " + max);
        byte[] out = new byte[(int) size];
        int s = 16, d = 0;
        try {
            while (d < out.length) {
                int flags = in[s++] & 0xff;
                for (int bit = 7; bit >= 0 && d < out.length; bit--) {
                    if ((flags >> bit & 1) != 0) { out[d++] = in[s++]; continue; }
                    int b1 = in[s++] & 0xff, b2 = in[s++] & 0xff;
                    int dist = ((b1 & 0x0f) << 8 | b2) + 1, n = b1 >> 4;
                    n = n == 0 ? (in[s++] & 0xff) + 0x12 : n + 2;
                    int from = d - dist;
                    if (from < 0) throw new IOException("Yaz0 reference before the start");
                    for (int k = 0; k < n && d < out.length; k++) out[d++] = out[from + k];
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) { throw new IOException("Yaz0 ends early"); }
        return out;
    }
}
