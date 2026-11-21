// PEANUTS: the Mii parts from the player's firmware (Settings › Users, Miis)
package dev.peanuts.mii;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * MiiParts, Tegra and MiiLook on the JVM (peanuts/tests/test_mii_parts.py). The texture and shape files are made here in the firmware's layout (no firmware
 * file is ever part of the repository): parts in block-linear and in row order, an absent part, a part outside the file, a mesh with an index past its vertices.
 */
public final class MiiPartsJvmTest {
    static int fails;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

    static byte[] zlib(byte[] in) {
        Deflater d = new Deflater();
        d.setInput(in); d.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) o.write(buf, 0, d.deflate(buf));
        d.end();
        return o.toByteArray();
    }

    /** A file: header and tables (size tables bytes), then the parts' data appended */
    static final class Builder {
        final ByteBuffer head; final ByteArrayOutputStream data = new ByteArrayOutputStream(); final int tables;
        Builder(String magic, int tables) { this.tables = tables; head = ByteBuffer.allocate(tables).order(ByteOrder.LITTLE_ENDIAN); head.putInt(0, MiiParts.magic(magic)); }
        int add(byte[] plain) { int at = tables + data.size(); byte[] z = zlib(plain); data.write(z, 0, z.length); return at; }
        byte[] bytes() { ByteArrayOutputStream o = new ByteArrayOutputStream(); o.write(head.array(), 0, tables); byte[] d = data.toByteArray(); o.write(d, 0, d.length); return o.toByteArray(); }
    }

    static void texture(Builder b, int kind, int index, int w, int h, int format, int tile, byte[] linear) {
        int bs = MiiParts.blockSize(format), bpb = MiiParts.bytesPerBlock(format), wb = (w + bs - 1) / bs, hb = (h + bs - 1) / bs;
        byte[] stored = tile == 0 ? Tegra.swizzle(linear, wb * bpb, hb, Tegra.blockHeightMip0(hb)) : linear;
        int at = MiiParts.TEX_ENTRIES + MiiParts.TEX_ENTRY * MiiParts.entry(MiiParts.TEX_COUNT, kind, index);
        int off = b.add(stored);
        b.head.putInt(at, off).putInt(at + 4, stored.length).putInt(at + 8, b.tables + b.data.size() - off);
        b.head.putShort(at + 0x14, (short) w).putShort(at + 0x16, (short) h).put(at + 0x18, (byte) format).put(at + 0x19, (byte) 1).put(at + 0x1a, (byte) tile);
    }

    static short half(float f) {   // the few values used here (exact in half precision)
        int bits = Float.floatToIntBits(f), s = bits >>> 16 & 0x8000, e = (bits >>> 23 & 0xff) - 127 + 15, m = bits >>> 13 & 0x3ff;
        return f == 0 ? (short) s : (short) (s | e << 10 | m);
    }

    static int snorm(float x, float y, float z) { return (Math.round(x * 511) & 0x3ff) | (Math.round(y * 511) & 0x3ff) << 10 | (Math.round(z * 511) & 0x3ff) << 20; }

    /** A mesh: positions, normals, texture coordinates and the index list in one inflated block, the entry pointing at each */
    static void shape(Builder b, int kind, int index, float[] pos, float[] nor, float[] uv, int[] idx) {
        int n = pos.length / 3;
        ByteBuffer d = ByteBuffer.allocate(n * 8 + n * 4 + n * 4 + idx.length * 2 + 16).order(ByteOrder.LITTLE_ENDIAN);
        int po = 0, no = n * 8, uo = no + n * 4, io = uo + n * 4;
        for (int v = 0; v < n; v++) {
            for (int c = 0; c < 3; c++) d.putShort(po + 8 * v + 2 * c, half(pos[3 * v + c]));
            d.putShort(po + 8 * v + 6, half(1));
            d.putInt(no + 4 * v, snorm(nor[3 * v], nor[3 * v + 1], nor[3 * v + 2]));
            d.putShort(uo + 4 * v, half(uv[2 * v])); d.putShort(uo + 4 * v + 2, half(uv[2 * v + 1]));
        }
        for (int i = 0; i < idx.length; i++) d.putShort(io + 2 * i, (short) idx[i]);
        byte[] plain = d.array();
        int at = MiiParts.SHAPE_ENTRIES + MiiParts.SHAPE_ENTRY * MiiParts.entry(MiiParts.SHAPE_COUNT, kind, index);
        int off = b.add(plain);
        b.head.putInt(at, off).putInt(at + 4, plain.length).putInt(at + 8, b.tables + b.data.size() - off);
        int[] ao = {po, no, uo, 0, 0}, as = {n * 8, n * 4, n * 4, 0, 0};
        for (int i = 0; i < 5; i++) { b.head.putInt(at + 0x10 + 4 * i, ao[i]); b.head.putInt(at + 0x24 + 4 * i, as[i]); }
        b.head.putInt(at + 0x38, io).putInt(at + 0x3c, idx.length * 2);
        for (int i = 0; i < 6; i++) b.head.putFloat(at + 0x40 + 4 * i, i < 3 ? -1 : 1);
    }

    /** Builds a BC block from the low bit up, as the decoder reads it */
    static final class BitWriter {
        final byte[] b; int at;
        BitWriter(int bytes) { b = new byte[bytes]; }
        BitWriter put(int bits, int value) { for (int i = 0; i < bits; i++, at++) if ((value >> i & 1) != 0) b[at >> 3] |= (byte) (1 << (at & 7)); return this; }
    }

    static void blocks() {
        byte[] px = new byte[64];
        BitWriter w = new BitWriter(8).put(8, 200).put(8, 100).put(3, 0).put(3, 1).put(3, 2);
        MiiPixels.bc4(w.b, 0, px, 0, 1);
        check((px[0] & 0xff) == 200 && (px[1] & 0xff) == 100 && (px[2] & 0xff) == (6 * 200 + 100) / 7 && (px[15] & 0xff) == 200, "BC4, eight values");
        w = new BitWriter(8).put(8, 10).put(8, 20).put(3, 6).put(3, 7).put(3, 2);
        MiiPixels.bc4(w.b, 0, px, 0, 1);
        check(px[0] == 0 && (px[1] & 0xff) == 255 && (px[2] & 0xff) == (4 * 10 + 20) / 5, "BC4, six values and 0 and 255");
        // BC7 mode 6: one subset, 7-bit RGBA endpoints with a p-bit each, 4-bit indices (the first one 3 bits)
        w = new BitWriter(16).put(7, 0x40);
        for (int c = 0; c < 4; c++) w.put(7, 0x7f).put(7, 0);
        w.put(1, 1).put(1, 0).put(3, 0);
        for (int i = 1; i < 16; i++) w.put(4, i == 5 ? 15 : 0);
        MiiPixels.bc7(w.b, 0, px);
        check((px[0] & 0xff) == 255 && (px[3] & 0xff) == 255 && px[20] == 0 && px[23] == 0 && (px[24] & 0xff) == 255, "BC7 mode 6: endpoint 0 white, index 15 at texel 5 black");
        // BC7 mode 1: two subsets, partition 0 (columns 2 and 3 are subset 1), 6-bit RGB endpoints, shared p-bits
        w = new BitWriter(16).put(2, 2).put(6, 0);
        int[][] rgb = {{63, 63, 0, 0}, {0, 0, 32, 32}, {0, 0, 0, 0}};   // subset 0: red, subset 1: green (both endpoints the same)
        for (int c = 0; c < 3; c++) for (int j = 0; j < 4; j++) w.put(6, rgb[c][j]);
        w.put(1, 1).put(1, 0);
        for (int i = 0; i < 16; i++) w.put(i == 0 || i == 15 ? 2 : 3, 0);
        MiiPixels.bc7(w.b, 0, px);
        boolean ok = true;
        for (int i = 0; i < 16; i++) {
            boolean one = (i & 3) >= 2;
            ok &= one ? (px[i * 4] == 0 && (px[i * 4 + 1] & 0xff) == 129) : ((px[i * 4] & 0xff) == 255 && px[i * 4 + 1] == 2);   // subset 0's p-bit 1 makes its 0 a 2
            ok &= (px[i * 4 + 3] & 0xff) == 255;
        }
        check(ok, "BC7 mode 1: partition 0 splits the block into columns, p-bits widen the endpoints");
        MiiPixels.bc7(new byte[16], 0, px);
        check(px[0] == 0 && px[3] == 0, "BC7 reserved mode: transparent black");
        MiiParts.Texture t = new MiiParts.Texture(6, 5, MiiParts.F_BC4, 1, new byte[2 * 2 * 8]);
        check(MiiPixels.decode(t).length == 30 && MiiPixels.channels(MiiParts.F_ASTC4X4) == 0, "a part not a multiple of four is cut to its size; ASTC stays for the GPU");
    }

    public static void main(String[] a) throws Exception {
        tegra();
        packing();
        blocks();
        files();
        look();
        if (fails > 0) { System.out.println(fails + " failed"); System.exit(1); }
        System.out.println("PASS");
    }

    static void tegra() throws IOException {
        check(Tegra.offset(0, 1, 64, 1) == 16 && Tegra.offset(16, 0, 64, 1) == 32 && Tegra.offset(32, 0, 64, 1) == 256 && Tegra.offset(0, 2, 64, 1) == 64,
                "inside a GOB: two rows of 16 bytes, pairs of rows, halves of 32 bytes");
        check(Tegra.offset(0, 8, 64, 2) == 512 && Tegra.offset(64, 0, 128, 1) == 512 && Tegra.offset(0, 8, 128, 1) == 1024, "GOBs stack down a block, blocks run across, then down");
        check(Tegra.blockHeightMip0(8) == 1 && Tegra.blockHeightMip0(16) == 2 && Tegra.blockHeightMip0(64) == 8 && Tegra.blockHeightMip0(128) == 16 && Tegra.blockHeightMip0(512) == 16,
                "mip 0 block height from its rows");
        Random r = new Random(7);
        for (int t = 0; t < 40; t++) {
            int wb = 1 + r.nextInt(300), rows = 1 + r.nextInt(140), bh = Tegra.blockHeightMip0(rows);
            byte[] lin = new byte[wb * rows]; r.nextBytes(lin);
            byte[] sw = Tegra.swizzle(lin, wb, rows, bh);
            check(sw.length == Tegra.size(wb, rows, bh) && sw.length % (512 * bh) == 0, "swizzled size is whole blocks");
            check(Arrays.equals(Tegra.deswizzle(sw, wb, rows, bh), lin), "swizzle and back: " + wb + " bytes x " + rows);
        }
        boolean threw = false;
        try { Tegra.deswizzle(new byte[100], 64, 8, 1); } catch (IOException e) { threw = true; }
        check(threw, "a short block-linear texture is refused, not read past");
    }

    static void packing() {
        check(MiiParts.half((short) 0x3c00) == 1f && MiiParts.half((short) 0xc000) == -2f && near(MiiParts.half((short) 0x3555), 0.33325f), "halves");
        check(MiiParts.half((short) 0x0001) == 1f / (1 << 24) && MiiParts.half((short) 0) == 0f, "half subnormal and zero");
        check(MiiParts.snorm10(511) == 1f && MiiParts.snorm10(0x201) == -1f && MiiParts.snorm10(0x200) == -1f && MiiParts.snorm10(0) == 0f, "10-bit signed normal, -512 clamped to -1");
    }

    static void files() throws IOException {
        File dir = Files.createTempDirectory("miiparts").toFile();
        check(MiiParts.open(dir) == null && !MiiParts.present(dir), "no files: no parts (a firmware imported before the app took MiiModel)");

        Builder t = new Builder("NFTR", MiiParts.TEX_ENTRIES + MiiParts.TEX_ENTRY * MiiParts.TEX_PARTS);
        Random r = new Random(3);
        byte[] eye = new byte[8 * 8 * 4]; r.nextBytes(eye);
        byte[] mouth = new byte[16 * 4]; r.nextBytes(mouth);
        byte[] wrinkle = new byte[64 * 64 * 4]; r.nextBytes(wrinkle);
        byte[] brow = new byte[(32 / 4) * (16 / 4) * 8]; r.nextBytes(brow);   // BC4: 8 bytes a 4x4 block
        texture(t, MiiParts.T_EYE, 0, 8, 8, MiiParts.F_RGBA8, 0, eye);
        texture(t, MiiParts.T_MOUTH, 1, 16, 4, MiiParts.F_R8, 1, mouth);
        texture(t, MiiParts.T_WRINKLE, 0, 64, 64, MiiParts.F_RGBA8, 0, wrinkle);
        texture(t, MiiParts.T_EYEBROW, 23, 32, 16, MiiParts.F_BC4, 0, brow);
        int bad = MiiParts.TEX_ENTRIES + MiiParts.TEX_ENTRY * MiiParts.entry(MiiParts.TEX_COUNT, MiiParts.T_NOSELINE, 17);
        t.head.putInt(bad, 1 << 30).putInt(bad + 4, 64).putInt(bad + 8, 64).putShort(bad + 0x14, (short) 4).putShort(bad + 0x16, (short) 4);

        Builder s = new Builder("NFSR", MiiParts.SHAPE_TABLES_END);
        float[] pos = {0, 1, 2, -1.5f, 0.5f, 3, 4, -2, 0.25f}, nor = {0, 0, 1, 1, 0, 0, 0, -1, 0}, uv = {0, 0, 1, 0.5f, 0.25f, 1};
        shape(s, MiiParts.S_FACELINE, 0, pos, nor, uv, new int[] {0, 1, 2});
        shape(s, MiiParts.S_NOSE, 0, pos, nor, uv, new int[] {0, 1, 5});
        for (int i = 0; i < 18; i++) s.head.putFloat(MiiParts.HAIR_TRANSFORMS_AT + MiiParts.HAIR_TRANSFORM * 3 + 4 * i, i + 0.5f);
        for (int i = 0; i < 9; i++) s.head.putFloat(MiiParts.FACELINE_TRANSFORMS_AT + MiiParts.FACELINE_TRANSFORM * 11 + 4 * i, -i);

        Files.write(new File(dir, "NXTextureMidSRGB.dat").toPath(), t.bytes());
        Files.write(new File(dir, "ShapeMid.dat").toPath(), s.bytes());
        MiiParts p = MiiParts.open(dir);
        check(p != null && MiiParts.present(dir) && p.textureFile.equals("NXTextureMidSRGB.dat") && p.shapeFile.equals("ShapeMid.dat"), "the Mid files are read");

        MiiParts.Texture e = p.texture(MiiParts.T_EYE, 0);
        check(e != null && e.width == 8 && e.height == 8 && e.format == MiiParts.F_RGBA8 && Arrays.equals(e.data, eye), "a block-linear RGBA8 part comes out in row order");
        MiiParts.Texture m = p.texture(MiiParts.T_MOUTH, 1);
        check(m != null && m.format == MiiParts.F_R8 && Arrays.equals(m.data, mouth), "a part in row order (tile mode 1) stays as it is");
        check(Arrays.equals(p.texture(MiiParts.T_WRINKLE, 0).data, wrinkle), "a 64x64 part over eight GOBs a block");
        MiiParts.Texture b = p.texture(MiiParts.T_EYEBROW, 23);
        check(b != null && b.format == MiiParts.F_BC4 && Arrays.equals(b.data, brow), "a BC4 part: blocks in row order, still compressed");
        check(p.texture(MiiParts.T_BEARD, 0) == null && p.texture(MiiParts.T_EYE, 62) == null && p.texture(99, 0) == null, "an absent part, an index past the kind, an unknown kind: none");
        boolean threw = false;
        try { p.texture(MiiParts.T_NOSELINE, 17); } catch (IOException x) { threw = true; }
        check(threw, "a part whose data lies outside the file is refused");

        MiiParts.Shape f = p.shape(MiiParts.S_FACELINE, 0);
        check(f != null && f.vertices() == 3 && f.index.length == 3 && f.index[2] == 2, "a mesh: three vertices, one triangle");
        boolean same = true;
        for (int i = 0; i < 9; i++) same &= near(f.position[i], pos[i]) && Math.abs(f.normal[i] - nor[i]) < 0.01f;
        for (int i = 0; i < 6; i++) same &= near(f.uv[i], uv[i]);
        check(same, "positions, normals and texture coordinates as written");
        check(f.box[0] == -1 && f.box[5] == 1, "the bounding box");
        threw = false;
        try { p.shape(MiiParts.S_NOSE, 0); } catch (IOException x) { threw = true; }
        check(threw, "an index past the vertices is refused");
        check(p.shape(MiiParts.S_HAIR_NORMAL, 5) == null && p.shape(MiiParts.S_GLASS, 1) == null, "absent meshes: none");
        float[] h = p.hairTransform(3), fl = p.facelineTransform(11);
        check(h.length == 18 && h[0] == 0.5f && h[17] == 17.5f, "hair transform 3");
        check(fl.length == 9 && fl[8] == -8 && p.facelineTransform(99)[8] == -8, "faceline transform 11 (an index past the last is the last)");

        File other = Files.createTempDirectory("miiparts-bad").toFile();   // a folder of its own: the first one's files stay mapped
        Files.write(new File(other, "NXTextureLowLinear.dat").toPath(), new byte[MiiParts.TEX_ENTRIES + MiiParts.TEX_ENTRY * MiiParts.TEX_PARTS]);
        Files.write(new File(other, "ShapeHigh.dat").toPath(), s.bytes());
        threw = false;
        try { MiiParts.open(other); } catch (IOException x) { threw = true; }
        check(threw, "a file without the texture magic is refused");
    }

    static void look() {
        check(MiiLook.COMMON_COLOR.length == 100 && MiiLook.UPPER_LIP_COLOR.length == 100 && MiiLook.FACELINE_COLOR.length == 10 && MiiLook.FAVORITE_COLOR.length == 12, "colour tables");
        check(MiiLook.common(8) == 0 && MiiLook.common(99) == 0xFFFFFF && MiiLook.common(500) == 0xFFFFFF && MiiLook.skin(-1) == MiiLook.FACELINE_COLOR[0], "colour lookups clamp");
        for (int d = 0; d < 6; d++) {
            MiiStore.Mii m = MiiStore.fromDefault(d, "M");
            MiiLook.Mask k = MiiLook.mask(m, 256);
            check(near(k.eyeL.x + k.eyeR.x, 256) && near(k.browL.x + k.browR.x, 256), "default " + d + ": eyes and eyebrows mirror around the middle");
            check(k.eyeL.origin == MiiLook.LEFT && k.eyeR.origin == MiiLook.RIGHT && near(k.eyeL.deg + k.eyeR.deg, 360), "the right eye is the left one mirrored");
            check(k.browL.y < k.eyeL.y && k.eyeL.y < k.mouth.y && k.mouth.y < 256 && k.browL.y > 0, "default " + d + ": eyebrows over the eyes over the mouth, inside the texture");
            check(k.eyeL.w > 0 && k.eyeL.h > 0 && k.mouth.w > 0 && k.mole.w > 0, "sizes");
        }
        MiiStore.Mii m = MiiStore.fromDefault(0, "M");
        float y0 = MiiLook.mask(m, 256).eyeL.y; m.set("eye_y", m.get("eye_y") + 4);
        check(MiiLook.mask(m, 256).eyeL.y > y0, "a larger eye position moves the eyes down");
        float w0 = MiiLook.mask(m, 512).mouth.w; m.set("mouth_scale", m.get("mouth_scale") + 2);
        check(MiiLook.mask(m, 512).mouth.w > w0 && near(MiiLook.mask(m, 512).mouth.x, 256), "a larger mouth size widens it, centred");
        m.set("beard_type", 2); check(MiiLook.beardShape(m), "beards 1 to 3 are shapes");
        m.set("beard_type", 4); check(!MiiLook.beardShape(m), "beards 4 and 5 are drawn on the face");
        check(MiiLook.eye(m).mode == MiiLook.LAYERED && MiiLook.eye(m).g == 0xFFFFFF, "the eye: black outline, white, eye colour");
    }
}
