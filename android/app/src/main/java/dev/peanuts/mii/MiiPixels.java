package dev.peanuts.mii;

import java.util.Arrays;

/**
 * A face part's pixels out of its stored format. R8, RG8 and RGBA8 are as they are; BC4, BC5 and BC7 (block-compressed formats the console's parts use) are
 * decoded here, ported from bcdec (github.com/iOrange/bcdec, MIT or Unlicense), so a phone whose GPU has no BC formats draws them too. ASTC 4x4 is left to the
 * GPU (every OpenGL ES 3.2 phone has it). The result keeps the stored channels: one for R8 and BC4, two for RG8 and BC5, four for RGBA8 and BC7.
 * No Android classes: MiiPartsJvmTest checks it on the JVM.
 */
public final class MiiPixels {
    private MiiPixels() {}

    /** Channels a decoded part has; 0 for ASTC, which stays compressed */
    public static int channels(int format) {
        switch (format) {
            case MiiParts.F_R8: case MiiParts.F_BC4: return 1;
            case MiiParts.F_RG8: case MiiParts.F_BC5: return 2;
            case MiiParts.F_RGBA8: case MiiParts.F_BC7: return 4;
            default: return 0;
        }
    }

    /** The part's pixels, rows top to bottom, channels(format) bytes a pixel; null for ASTC */
    public static byte[] decode(MiiParts.Texture t) {
        int ch = channels(t.format);
        if (ch == 0) return null;
        if (t.format <= MiiParts.F_RGBA8) return t.data;
        int w = t.width, h = t.height, bw = (w + 3) / 4, bh = (h + 3) / 4, bpb = MiiParts.bytesPerBlock(t.format);
        byte[] out = new byte[w * h * ch], px = new byte[16 * ch];
        for (int by = 0; by < bh; by++)
            for (int bx = 0; bx < bw; bx++) {
                int at = (by * bw + bx) * bpb;
                if (t.format == MiiParts.F_BC4) bc4(t.data, at, px, 0, 1);
                else if (t.format == MiiParts.F_BC5) { bc4(t.data, at, px, 0, 2); bc4(t.data, at + 8, px, 1, 2); }
                else bc7(t.data, at, px);
                for (int y = 0; y < 4; y++) {
                    int oy = by * 4 + y;
                    if (oy >= h) break;
                    for (int x = 0; x < 4; x++) {
                        int ox = bx * 4 + x;
                        if (ox >= w) break;
                        System.arraycopy(px, (y * 4 + x) * ch, out, (oy * w + ox) * ch, ch);
                    }
                }
            }
        return out;
    }

    private static long u64(byte[] b, int at) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = v << 8 | (b[at + i] & 0xff);
        return v;
    }

    /** One BC4 block (8 bytes) into channel c of 16 pixels of stride bytes: two endpoints, 3-bit indices (bcdec's smooth alpha block) */
    static void bc4(byte[] in, int at, byte[] out, int c, int stride) {
        long block = u64(in, at);
        int a0 = (int) (block & 0xff), a1 = (int) (block >>> 8 & 0xff);
        int[] a = new int[8];
        a[0] = a0; a[1] = a1;
        if (a0 > a1) for (int i = 1; i < 7; i++) a[i + 1] = ((7 - i) * a0 + i * a1) / 7;
        else { for (int i = 1; i < 5; i++) a[i + 1] = ((5 - i) * a0 + i * a1) / 5; a[6] = 0; a[7] = 255; }
        long idx = block >>> 16;
        for (int i = 0; i < 16; i++) { out[i * stride + c] = (byte) a[(int) (idx & 7)]; idx >>>= 3; }
    }

    // ---- BC7 ----
    private static final int[] BITS_RGB = {4, 6, 5, 7, 5, 7, 7, 5}, BITS_A = {0, 0, 0, 0, 6, 8, 7, 5};
    private static final int[] W2 = {0, 21, 43, 64}, W3 = {0, 9, 18, 27, 37, 46, 55, 64}, W4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};
    private static final int HAS_PBITS = 0b11001011;
    /** bcdec's partition tables, 64 partitions of 16 texels (rows top to bottom): '0' to '2' the texel's subset, 'A' to 'C' that subset's anchor texel */
    private static final String P2 =
        "A01100110011001BA00100010001000BA11101110111011BA00100110011011BA00000010001001BA01101110111111BA00100110111111BA00000010011011B" +
        "A00000000001001BA01101111111111BA00000010111111BA00000000001011BA00101111111111BA00000001111111BA00011111111111BA00000000000111B" +
        "A00010001110111BA1B1000100000000A0000000B0001110A1B1001100010000A0B1000100000000A0001000B1001110A0000000B0001100A11100110011000B" +
        "A0B1000100010000A0001000B0001100A1B0011001100110A0B1011001101100A0010111B1101000A0001111B1110000A1B1000110001110A0B1100110011100" +
        "A10101010101010BA00011110000111BA10110B001011010A0110011B1001100A0B1110000111100A1010101B0101010A11010010110100BA10110101010010B" +
        "A1B1001111001110A0010011B1001000A0B1001001001100A0B1101111011100A1B0100110010110A01111001100001BA11001101001100BA00001B001100000" +
        "A10011B001000000A0B0011100100000A00000B001110010A0000100B1100100A11011001001001BA01101101100100BA1B0001110011100A0B1100111000110" +
        "A11011001100100BA11000110011100BA11111101000000BA00110001110011BA00011110011001BA0B1001111110000A0B0001011101110A10001000111011B";
    private static final String P3 =
        "A01B00110221222CA00B0011C2112221A0002001C211221BA22C00220011011BA0000000B122112CA01B00110022002CA02C00221111111BA0110011C211221B" +
        "A0000000B111222CA0001111B111222CA00011B12222222CA01200B20012001CA11201B20112011CA1220B220122012CA01B01121122122CA01B2001C2002220" +
        "A00B00110112112CA11B0011C0012200A0001122B122112CA02C00220022111BA11B01110222022CA00B0001C2212221A00000B10122012CA0001100C2B02210" +
        "A12C0B2200110000A0120012B122222CA11012C1B2210110A00001B012C11221A0221102B102002CA1100B102002222CA011012201C2001BA0002000C211222B" +
        "A0000002B122122CA22C00220012001BA01B00120022022CA1200B2001C00120A00011B122C20000A1201201C0B20120A1202012BC010120A011220011C2001B" +
        "A01111C22200001BA10B01012222222CA0000000C121212BA0221B220022112CA02C00110022001BA22012C10220122BA10122C22222010BA0002121C121212B" +
        "A10B01010101222CA22C01110222011BA0021B120002111CA0002B122112211CA2220B110111022CA0021112B112000CA1100B100110222CA000000021B2211C" +
        "A1100B102222222CA022001100B1002CA0221122B122002CA000000000002B1CA00C00010002000BA22212220222B22CA10B22222222222CA11B2011C2012220";

    /** Subset of texel i in partition p of an n-subset block, with 0x80 set at a subset's anchor (its index has one bit less) */
    private static int partition(int n, int p, int i) {
        if (n == 1) return i == 0 ? 0x80 : 0;
        char ch = (n == 2 ? P2 : P3).charAt(p * 16 + i);
        return ch >= 'A' ? 0x80 | (ch - 'A') : ch - '0';
    }

    /** A 128-bit little-endian bit stream, read from the low end */
    private static final class Bits {
        long lo, hi;
        int read(int n) {
            if (n == 0) return 0;
            long mask = (1L << n) - 1;
            int v = (int) (lo & mask);
            lo = lo >>> n | (hi & mask) << (64 - n);
            hi >>>= n;
            return v;
        }
    }

    private static int lerp(int a, int b, int[] w, int i) { return (a * (64 - w[i]) + b * w[i] + 32) >> 6; }

    /** One BC7 block (16 bytes) into 16 RGBA pixels (bcdec_bc7) */
    static void bc7(byte[] in, int at, byte[] out) {
        Bits s = new Bits();
        s.lo = u64(in, at); s.hi = u64(in, at + 8);
        int mode = 0;
        while (mode < 8 && s.read(1) == 0) mode++;
        if (mode >= 8) { Arrays.fill(out, 0, 64, (byte) 0); return; }   // a reserved mode: transparent black
        int partition = 0, subsets = 1, rotation = 0, isb = 0;
        if (mode == 0 || mode == 1 || mode == 2 || mode == 3 || mode == 7) { subsets = mode == 0 || mode == 2 ? 3 : 2; partition = s.read(mode == 0 ? 4 : 6); }
        int ends = subsets * 2;
        if (mode == 4 || mode == 5) { rotation = s.read(2); if (mode == 4) isb = s.read(1); }
        int[][] e = new int[6][4];
        for (int c = 0; c < 3; c++) for (int j = 0; j < ends; j++) e[j][c] = s.read(BITS_RGB[mode]);
        if (BITS_A[mode] > 0) for (int j = 0; j < ends; j++) e[j][3] = s.read(BITS_A[mode]);
        if (mode == 0 || mode == 1 || mode == 3 || mode == 6 || mode == 7) {
            for (int j = 0; j < ends; j++) for (int c = 0; c < 4; c++) e[j][c] <<= 1;
            if (mode == 1) {
                int p0 = s.read(1), p1 = s.read(1);
                for (int c = 0; c < 3; c++) { e[0][c] |= p0; e[1][c] |= p0; e[2][c] |= p1; e[3][c] |= p1; }
            } else if ((HAS_PBITS & (1 << mode)) != 0) {
                for (int j = 0; j < ends; j++) { int p = s.read(1); for (int c = 0; c < 4; c++) e[j][c] |= p; }
            }
        }
        int pb = (HAS_PBITS >> mode) & 1;
        for (int j = 0; j < ends; j++) {
            int bits = BITS_RGB[mode] + pb;
            for (int c = 0; c < 3; c++) { e[j][c] = e[j][c] << (8 - bits); e[j][c] = (e[j][c] | e[j][c] >> bits) & 0xff; }
            bits = BITS_A[mode] + pb;
            e[j][3] = e[j][3] << (8 - bits); e[j][3] = (e[j][3] | e[j][3] >> bits) & 0xff;
        }
        if (BITS_A[mode] == 0) for (int j = 0; j < ends; j++) e[j][3] = 0xff;
        int ib = mode == 0 || mode == 1 ? 3 : mode == 6 ? 4 : 2, ib2 = mode == 4 ? 3 : mode == 5 ? 2 : 0;
        int[] w = ib == 2 ? W2 : ib == 3 ? W3 : W4, w2 = ib2 == 2 ? W2 : W3;
        int[] idx = new int[16];
        for (int i = 0; i < 16; i++) { int set = partition(subsets, partition, i); idx[i] = s.read((set & 0x80) != 0 ? ib - 1 : ib); }
        for (int i = 0; i < 16; i++) {
            int set = partition(subsets, partition, i) & 3, k = idx[i];
            int[] a0 = e[set * 2], a1 = e[set * 2 + 1];
            int r, g, b, a;
            if (ib2 == 0) { r = lerp(a0[0], a1[0], w, k); g = lerp(a0[1], a1[1], w, k); b = lerp(a0[2], a1[2], w, k); a = lerp(a0[3], a1[3], w, k); }
            else {
                int k2 = s.read(i != 0 ? ib2 : ib2 - 1);
                if (isb == 0) { r = lerp(a0[0], a1[0], w, k); g = lerp(a0[1], a1[1], w, k); b = lerp(a0[2], a1[2], w, k); a = lerp(a0[3], a1[3], w2, k2); }
                else { r = lerp(a0[0], a1[0], w2, k2); g = lerp(a0[1], a1[1], w2, k2); b = lerp(a0[2], a1[2], w2, k2); a = lerp(a0[3], a1[3], w, k); }
            }
            int t;
            if (rotation == 1) { t = a; a = r; r = t; } else if (rotation == 2) { t = a; a = g; g = t; } else if (rotation == 3) { t = a; a = b; b = t; }
            out[i * 4] = (byte) r; out[i * 4 + 1] = (byte) g; out[i * 4 + 2] = (byte) b; out[i * 4 + 3] = (byte) a;
        }
    }
}
