package io.github.davdxpx.mistel.mncf;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine code the reader makes itself: the override stubs of section 7.5 and Appendix A, and
 * the instructions of the {@code arm64-imm/1} extension.
 */
final class Encode {

    /** The instructions of {@code arm64-imm/1} and the number of bytes each one takes. */
    static final Map<String, Integer> ARM64_IMM_INSNS;

    static {
        Map<String, Integer> insns = new LinkedHashMap<>();
        insns.put("movz", 4);
        insns.put("movz-movk", 8);
        insns.put("fmov", 4);
        ARM64_IMM_INSNS = Collections.unmodifiableMap(insns);
    }

    private static final long RET = 0xD65F03C0L;

    private Encode() {
    }

    private static byte[] le32(long... words) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long w : words) {
            for (int i = 0; i < 4; i++) {
                out.write((int) (w >>> (8 * i)) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    /** Whether Appendix A defines override stubs for this architecture. */
    static boolean hasStub(String arch) {
        return "arm64".equals(arch) || "x86-64".equals(arch);
    }

    /**
     * The stub bytes for this architecture, or null where Appendix A defines none.
     *
     * @param value the 64 bits of the value "return" moves into the result register
     */
    static byte[] stub(String arch, String action, long value) {
        if ("arm64".equals(arch)) {
            return arm64Stub(action, value);
        }
        if ("x86-64".equals(arch)) {
            return x86Stub(action, value);
        }
        return null;
    }

    /** The ARM64 stub: ret for "skip", the value moved into x0 and then ret for "return". */
    static byte[] arm64Stub(String action, long v) {
        if (action.equals("skip")) {
            return le32(RET);
        }
        if (Long.compareUnsigned(v, 0xFFFF) <= 0) {
            return le32(0xD2800000L | v << 5, RET);                     // movz x0, #v
        }
        long inverted = ~v;
        if (Long.compareUnsigned(inverted, 0xFFFF) <= 0) {
            return le32(0x92800000L | inverted << 5, RET);              // movn x0, #~v
        }
        long[] words = new long[5];
        int n = 0;
        words[n++] = 0xD2800000L | (v & 0xFFFF) << 5;                  // movz x0, #(bits 0 to 15)
        for (int hw = 1; hw <= 3; hw++) {
            long chunk = (v >>> (16 * hw)) & 0xFFFF;
            if (chunk != 0) {
                words[n++] = 0xF2800000L | (long) hw << 21 | chunk << 5;  // movk x0, #chunk, lsl #16*hw
            }
        }
        long[] all = new long[n + 1];
        System.arraycopy(words, 0, all, 0, n);
        all[n] = RET;
        return le32(all);
    }

    /** The x86-64 stub: ret for "skip", mov eax or rax and then ret for "return". */
    static byte[] x86Stub(String action, long v) {
        if (action.equals("skip")) {
            return new byte[] {(byte) 0xC3};
        }
        if (Long.compareUnsigned(v, 0xFFFFFFFFL) <= 0) {
            byte[] out = new byte[6];
            out[0] = (byte) 0xB8;
            putLe(out, 1, v, 4);
            out[5] = (byte) 0xC3;
            return out;
        }
        if (v < 0 && v >= Integer.MIN_VALUE) {
            byte[] out = new byte[8];
            out[0] = 0x48;
            out[1] = (byte) 0xC7;
            out[2] = (byte) 0xC0;
            putLe(out, 3, v, 4);
            out[7] = (byte) 0xC3;
            return out;
        }
        byte[] out = new byte[11];
        out[0] = 0x48;
        out[1] = (byte) 0xB8;
        putLe(out, 2, v, 8);
        out[10] = (byte) 0xC3;
        return out;
    }

    private static void putLe(byte[] out, int at, long v, int size) {
        for (int i = 0; i < size; i++) {
            out[at + i] = (byte) (v >>> (8 * i));
        }
    }

    /**
     * The 8-bit immediate of fmov for this value, or -1 if fmov cannot encode it exactly: a
     * value of plus or minus (16 + m) / 16 times 2^e with m from 0 to 15 and e from -3 to 4.
     */
    static int fmovImm8(Object value) {
        if (!Values.isNumber(value)) {
            return -1;
        }
        double v = Values.toDouble(value);
        if (v == 0 || Double.isNaN(v) || Double.isInfinite(v)) {
            return -1;
        }
        int sign = v < 0 ? 1 : 0;
        double magnitude = Math.abs(v);
        if (magnitude < Double.MIN_NORMAL) {
            return -1;
        }
        int e = Math.getExponent(magnitude);                         // magnitude = f * 2^e, 1 <= f < 2
        double m16 = Math.scalb(magnitude, -e) * 16 - 16;            // (f - 1) * 16
        if (e < -3 || e > 4 || m16 != Math.floor(m16) || m16 < 0 || m16 > 15) {
            return -1;
        }
        int bcd = e >= 1 ? e - 1 : (0b100 | (e + 3));                // b = 0 for e in 1..4, b = 1 for e in -3..0
        return sign << 7 | bcd << 4 | (int) m16;
    }

    /** The instruction bytes of an encode table of arm64-imm/1, or null if the value does not fit. */
    static byte[] arm64Imm(String insn, int width, int rd, Object value) {
        long sf = width == 64 ? 1 : 0;
        switch (insn) {
            case "movz": {
                if (!Values.isInt(value) || !inRange(value, 0xFFFFL)) {
                    return null;
                }
                long v = Values.bits(value);
                return le32(sf << 31 | 0x52800000L | v << 5 | rd);
            }
            case "movz-movk": {
                if (!Values.isInt(value) || !inRange(value, 0xFFFFFFFFL)) {
                    return null;
                }
                long v = Values.bits(value);
                long lo = v & 0xFFFF;
                long hi = v >>> 16;
                return le32(sf << 31 | 0x52800000L | lo << 5 | rd, sf << 31 | 0x72A00000L | hi << 5 | rd);
            }
            case "fmov": {
                int imm8 = fmovImm8(value);
                if (imm8 < 0) {
                    return null;
                }
                long ftype = width == 64 ? 1 : 0;
                return le32(0x1E201000L | ftype << 22 | (long) imm8 << 13 | rd);
            }
            default:
                return null;
        }
    }

    private static boolean inRange(Object value, long max) {
        return Values.compare(value, 0L) >= 0 && Values.compare(value, max) <= 0;
    }
}
