package dev.peanuts.mii;

import java.io.IOException;

/**
 * The Switch GPU's block-linear texture layout, undone for one mip level. A surface is cut into GOBs of 64 bytes by 8 rows (512 bytes); a block is a column of
 * 1 to 32 GOBs; blocks run left to right, then down. Inside a GOB the bytes follow the Tegra X1 order: 16-byte pieces of two rows, in pairs of rows, in halves
 * of 32 bytes. Width is in bytes (blocks x bytes a block), height in rows of blocks. No Android classes: MiiPartsJvmTest swizzles and unswizzles with it.
 */
final class Tegra {
    private Tegra() {}

    static final int GOB_W = 64, GOB_H = 8, GOB = GOB_W * GOB_H;

    /** The block height (GOBs) the GPU picks for a mip 0 of this many rows: the smallest that covers one and a half times the rows, at most 16 */
    static int blockHeightMip0(int rows) {
        int hh = rows + rows / 2;
        return hh >= 128 ? 16 : hh >= 64 ? 8 : hh >= 32 ? 4 : hh >= 16 ? 2 : 1;
    }

    /** Where byte x of row y lies in the swizzled surface */
    static int offset(int x, int y, int widthBytes, int blockHeight) {
        int gobsWide = (widthBytes + GOB_W - 1) / GOB_W, blockRows = GOB_H * blockHeight;
        int block = (y / blockRows) * gobsWide + x / GOB_W;
        int inBlock = ((y % blockRows) / GOB_H) * GOB;
        int gx = x % GOB_W, gy = y % GOB_H;
        int inGob = (gx / 32) * 256 + (gy / 2) * 64 + ((gx % 32) / 16) * 32 + (gy % 2) * 16 + gx % 16;
        return block * GOB * blockHeight + inBlock + inGob;
    }

    /** The bytes a swizzled surface takes, padded to whole blocks */
    static int size(int widthBytes, int rows, int blockHeight) {
        int gobsWide = (widthBytes + GOB_W - 1) / GOB_W, blockRows = GOB_H * blockHeight;
        return gobsWide * ((rows + blockRows - 1) / blockRows) * GOB * blockHeight;
    }

    static byte[] deswizzle(byte[] in, int widthBytes, int rows, int blockHeight) throws IOException {
        if (in.length < size(widthBytes, rows, blockHeight)) throw new IOException("block-linear texture: " + in.length + " bytes, " + size(widthBytes, rows, blockHeight) + " needed");
        byte[] out = new byte[widthBytes * rows];
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < widthBytes; x += 16) {   // a 16-byte piece stays together inside a GOB
                int n = Math.min(16, widthBytes - x);
                System.arraycopy(in, offset(x, y, widthBytes, blockHeight), out, y * widthBytes + x, n);
            }
        return out;
    }

    static byte[] swizzle(byte[] in, int widthBytes, int rows, int blockHeight) {
        byte[] out = new byte[size(widthBytes, rows, blockHeight)];
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < widthBytes; x += 16) {
                int n = Math.min(16, widthBytes - x);
                System.arraycopy(in, y * widthBytes + x, out, offset(x, y, widthBytes, blockHeight), n);
            }
        return out;
    }
}
