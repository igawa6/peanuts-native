package io.github.davdxpx.mistel.mncf;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the converter needs to know about ARM64 instructions: which ones are relative to their
 * own address, and which of their bytes change when the code moves in another build of the game.
 */
final class Arm64 {

    private Arm64() {
    }

    /** The little-endian word at an offset, from the bytes that are there (fewer than 4 at the end). */
    static long word(byte[] data, int offset) {
        long w = 0;
        for (int i = 0; i < 4 && offset + i < data.length; i++) {
            w |= (long) (data[offset + i] & 0xFF) << (8 * i);
        }
        return w;
    }

    private static long signed(long value, int bits) {
        return (value & (1L << (bits - 1))) != 0 ? value - (1L << bits) : value;
    }

    /**
     * The address a PC-relative instruction at pc refers to, or null for an instruction that is
     * not PC-relative.
     */
    static Long pcRelativeTarget(long w, long pc) {
        if ((w & 0x7C000000L) == 0x14000000L) {                                     // B, BL
            return pc + 4 * signed(w & 0x3FFFFFF, 26);
        }
        if ((w & 0xFF000010L) == 0x54000000L || (w & 0x7E000000L) == 0x34000000L) { // B.cond, CBZ, CBNZ
            return pc + 4 * signed((w >> 5) & 0x7FFFF, 19);
        }
        if ((w & 0x7E000000L) == 0x36000000L) {                                     // TBZ, TBNZ
            return pc + 4 * signed((w >> 5) & 0x3FFF, 14);
        }
        if ((w & 0x3B000000L) == 0x18000000L || (w & 0xFF000000L) == 0xD8000000L) { // LDR and PRFM (literal)
            return pc + 4 * signed((w >> 5) & 0x7FFFF, 19);
        }
        if ((w & 0x1F000000L) == 0x10000000L) {                                     // ADR, ADRP
            long imm = signed(((w >> 5) & 0x7FFFF) << 2 | (w >> 29) & 3, 21);
            if ((w & 0x80000000L) != 0) {
                return (pc & ~0xFFFL) + (imm << 12);
            }
            return pc + imm;
        }
        return null;
    }

    static boolean isAdrp(long w) {
        return (w & 0x9F000000L) == 0x90000000L;
    }

    /** The base register of an ADD (immediate) or of a load or store with an unsigned 12-bit offset, or -1. */
    private static int low12Base(long w) {
        if ((w & 0x7F800000L) == 0x11000000L || (w & 0x3B000000L) == 0x39000000L) {
            return (int) ((w >> 5) & 31);
        }
        return -1;
    }

    /**
     * The offsets in [start, end) whose bytes change when the code moves: the address fields of
     * PC-relative instructions and the low 12 bits of the page offset that follow an ADRP. Code
     * is read in words aligned to 4.
     */
    static Set<Integer> movingBytes(byte[] data, int start, int end) {
        Set<Integer> moving = new TreeSet<>();
        int first = Math.max(0, (start - 32) & ~3);
        Map<Integer, Integer> pages = new HashMap<>();        // register, set by an ADRP
        for (int off = first; off < ((end + 3) & ~3); off += 4) {
            if (off + 4 > data.length) {
                break;
            }
            long w = word(data, off);
            int[] fields = {};
            if (isAdrp(w) || (w & 0x9F000000L) == 0x10000000L) {
                fields = new int[] {0, 1, 2, 3};
            } else if (pcRelativeTarget(w, off) != null) {
                fields = new int[] {0, 1, 2};
            } else {
                int base = low12Base(w);
                if (base >= 0 && pages.containsKey(base)) {
                    fields = new int[] {1, 2};
                    if ((w & 31) == base) {                    // add x8, x8, #lo or ldr x8, [x8, #lo]: x8 changed
                        pages.remove(base);
                    }
                }
            }
            if (isAdrp(w)) {
                pages.put((int) (w & 31), off);
            }
            for (int i : fields) {
                if (start <= off + i && off + i < end) {
                    moving.add(off + i);
                }
            }
        }
        return moving;
    }

    /**
     * Whether bytes written at an offset hold a PC-relative instruction whose target lies
     * outside them, so that the same bytes mean something else at another place.
     */
    static boolean relativeOutside(byte[] data, long offset, int length) {
        if (Math.floorMod(offset, 4) != 0) {
            return false;
        }
        for (int i = 0; i < length - length % 4; i += 4) {
            Long target = pcRelativeTarget(word(data, i), offset + i);
            if (target != null && !(offset <= target && target < offset + length)) {
                return true;
            }
        }
        return false;
    }
}
