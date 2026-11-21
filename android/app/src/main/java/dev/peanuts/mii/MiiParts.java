package dev.peanuts.mii;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The console's Mii parts, read from the player's own firmware: the MiiModel system data (0100000000000802) that Setup extracts into files/firmware/miimodel
 * (nximport.c font_try_mii). Nothing of it ships with the app. Two files are read: a texture file (NXTexture*.dat, magic NFTR) with the face parts as pictures, and
 * a shape file (Shape*.dat, magic NFSR) with the head's meshes. The layout is the one dm/host/hle_mii.c describes for its stand-in, field by field as the
 * vee project reads it (github.com/j0lol/vee, vee_resources tex.rs / shape.rs, MIT or Apache-2.0):
 * <ul>
 * <li>texture file: 11 part kinds, from +0x64 one 0x1c-byte entry a part: u32 data offset, u32 inflated size, u32 stored size, 4 bytes of zlib parameters, u32
 * alignment, u16 width, u16 height, u8 format, u8 mip count, u8 tile mode (0: block-linear, as the Switch's GPU lays textures out), a pad byte;</li>
 * <li>shape file: 12 part kinds, from +0x6c one 0x58-byte entry a part: the same 16 bytes, then five vertex attributes' offsets and sizes (position: four
 * halves; normal: 10:10:10:2 signed; texture coordinate: two halves; tangent; colour parameter), the u16 index list's offset and size and a bounding box;
 * then the hair transforms (132 x 0x48) and the faceline transforms (12 x 0x24).</li>
 * </ul>
 * Each part's data is a zlib stream of its own. No Android classes: MiiPartsJvmTest runs it on the JVM.
 */
public final class MiiParts {
    // texture kinds, in the file's order
    public static final int T_HAT = 0, T_EYE = 1, T_EYEBROW = 2, T_BEARD = 3, T_WRINKLE = 4, T_MAKEUP = 5, T_GLASS = 6, T_MOLE = 7, T_MOUTH = 8, T_MUSTACHE = 9, T_NOSELINE = 10;
    static final int[] TEX_COUNT = {132, 62, 24, 2, 12, 12, 20, 2, 37, 6, 18};
    // shape kinds, in the file's order
    public static final int S_BEARD = 0, S_FACELINE = 1, S_MASK = 2, S_HAT_NORMAL = 3, S_HAT_CAP = 4, S_FOREHEAD_NORMAL = 5, S_FOREHEAD_CAP = 6, S_HAIR_NORMAL = 7,
            S_HAIR_CAP = 8, S_GLASS = 9, S_NOSE = 10, S_NOSELINE = 11;
    static final int[] SHAPE_COUNT = {4, 12, 12, 132, 132, 132, 132, 132, 132, 1, 18, 18};
    // texture formats
    public static final int F_R8 = 0, F_RG8 = 1, F_RGBA8 = 2, F_BC4 = 3, F_BC5 = 4, F_BC7 = 5, F_ASTC4X4 = 6;

    static final int TEX_ENTRIES = 0x64, TEX_ENTRY = 0x1c, SHAPE_ENTRIES = 0x6c, SHAPE_ENTRY = 0x58, HAIR_TRANSFORM = 0x48, FACELINE_TRANSFORM = 0x24;
    static final int TEX_PARTS = sum(TEX_COUNT), SHAPE_PARTS = sum(SHAPE_COUNT);
    static final int HAIR_TRANSFORMS_AT = SHAPE_ENTRIES + SHAPE_ENTRY * SHAPE_PARTS, FACELINE_TRANSFORMS_AT = HAIR_TRANSFORMS_AT + HAIR_TRANSFORM * 132;
    static final int SHAPE_TABLES_END = FACELINE_TRANSFORMS_AT + FACELINE_TRANSFORM * 12;

    /** The texture file read first: the "Mid" set the console's own renderer uses; the "Low" set when it is missing */
    static final String[] TEXTURE_FILES = {"NXTextureMidSRGB.dat", "NXTextureMidLinear.dat", "NXTextureLowSRGB.dat", "NXTextureLowLinear.dat"};
    static final String[] SHAPE_FILES = {"ShapeMid.dat", "ShapeHigh.dat"};
    static final int MAX_FILE = 64 << 20;

    /** One face part as a picture: mip 0 in row order (deswizzled), still in its own format (a block-compressed one is decoded by MiiPixels) */
    public static final class Texture {
        public final int width, height, format, mips;
        public final byte[] data;
        Texture(int width, int height, int format, int mips, byte[] data) { this.width = width; this.height = height; this.format = format; this.mips = mips; this.data = data; }
    }

    /** One mesh: positions and normals (x, y, z a vertex), texture coordinates (u, v a vertex; null when the part has none), triangles as u16 indices */
    public static final class Shape {
        public final float[] position, normal, uv, box;
        public final short[] index;
        Shape(float[] position, float[] normal, float[] uv, short[] index, float[] box) { this.position = position; this.normal = normal; this.uv = uv; this.index = index; this.box = box; }
        public int vertices() { return position.length / 3; }
    }

    private final ByteBuffer tex, shape;
    public final String textureFile, shapeFile;

    MiiParts(ByteBuffer tex, ByteBuffer shape, String textureFile, String shapeFile) throws IOException {
        this.tex = tex.order(ByteOrder.LITTLE_ENDIAN); this.shape = shape.order(ByteOrder.LITTLE_ENDIAN);
        this.textureFile = textureFile; this.shapeFile = shapeFile;
        if (tex.capacity() < TEX_ENTRIES + TEX_ENTRY * TEX_PARTS || tex.getInt(0) != magic("NFTR")) throw new IOException(textureFile + ": not a Mii texture file");
        if (shape.capacity() < SHAPE_TABLES_END || shape.getInt(0) != magic("NFSR")) throw new IOException(shapeFile + ": not a Mii shape file");
    }

    /** The parts in dir (files/firmware/miimodel); null when the folder lacks a texture or a shape file (a firmware imported before the app took MiiModel) */
    public static MiiParts open(File dir) throws IOException {
        File t = first(dir, TEXTURE_FILES), s = first(dir, SHAPE_FILES);
        if (t == null || s == null) return null;
        return new MiiParts(map(t), map(s), t.getName(), s.getName());
    }

    /** Whether dir holds what open needs */
    public static boolean present(File dir) { return first(dir, TEXTURE_FILES) != null && first(dir, SHAPE_FILES) != null; }

    private static File first(File dir, String[] names) {
        for (String n : names) { File f = new File(dir, n); if (f.isFile() && f.length() > 0) return f; }
        return null;
    }

    private static ByteBuffer map(File f) throws IOException {
        if (f.length() > MAX_FILE) throw new IOException(f.getName() + ": " + f.length() + " bytes, more than a Mii file has");
        try (RandomAccessFile r = new RandomAccessFile(f, "r"); FileChannel c = r.getChannel()) { return c.map(FileChannel.MapMode.READ_ONLY, 0, c.size()); }
    }

    static int magic(String s) { return (s.charAt(0) & 0xff) | (s.charAt(1) & 0xff) << 8 | (s.charAt(2) & 0xff) << 16 | (s.charAt(3) & 0xff) << 24; }

    private static int sum(int[] a) { int n = 0; for (int x : a) n += x; return n; }

    static int entry(int[] counts, int kind, int index) {
        if (kind < 0 || kind >= counts.length || index < 0 || index >= counts[kind]) return -1;
        int n = 0; for (int k = 0; k < kind; k++) n += counts[k];
        return n + index;
    }

    /** How many parts of a texture kind the file has (the console's: 62 eyes, 37 mouths, ...) */
    public static int textures(int kind) { return kind >= 0 && kind < TEX_COUNT.length ? TEX_COUNT[kind] : 0; }
    public static int shapes(int kind) { return kind >= 0 && kind < SHAPE_COUNT.length ? SHAPE_COUNT[kind] : 0; }

    /** A face part as a picture, or null when the firmware has none at that index (an absent part has size 0: no beard, no mole) */
    public Texture texture(int kind, int index) throws IOException {
        int e = entry(TEX_COUNT, kind, index);
        if (e < 0) return null;
        int at = TEX_ENTRIES + TEX_ENTRY * e;
        int off = tex.getInt(at), size = tex.getInt(at + 4), stored = tex.getInt(at + 8);
        int w = tex.getShort(at + 0x14) & 0xffff, h = tex.getShort(at + 0x16) & 0xffff;
        int format = tex.get(at + 0x18) & 0xff, mips = tex.get(at + 0x19) & 0xff, tile = tex.get(at + 0x1a) & 0xff;
        if (size == 0 || w == 0 || h == 0) return null;
        if (format > F_ASTC4X4) throw new IOException("texture " + kind + "/" + index + ": format " + format + " is none the app knows");
        byte[] raw = inflate(tex, off, stored, size, "texture " + kind + "/" + index);
        int bw = blockSize(format), bpb = bytesPerBlock(format);
        int wb = (w + bw - 1) / bw, hb = (h + bw - 1) / bw;
        byte[] mip0;
        if (tile == 0) mip0 = Tegra.deswizzle(raw, wb * bpb, hb, Tegra.blockHeightMip0(hb));
        else { mip0 = new byte[wb * bpb * hb]; if (raw.length < mip0.length) throw new IOException("texture " + kind + "/" + index + ": " + raw.length + " bytes for " + w + "x" + h); System.arraycopy(raw, 0, mip0, 0, mip0.length); }
        return new Texture(w, h, format, mips, mip0);
    }

    public static int blockSize(int format) { return format >= F_BC4 ? 4 : 1; }
    public static int bytesPerBlock(int format) {
        switch (format) { case F_R8: return 1; case F_RG8: return 2; case F_RGBA8: return 4; case F_BC4: return 8; default: return 16; }
    }

    /** A mesh, or null when the firmware has none at that index (most hair styles have no hat, glasses have one shape for every type) */
    public Shape shape(int kind, int index) throws IOException {
        int e = entry(SHAPE_COUNT, kind, index);
        if (e < 0) return null;
        int at = SHAPE_ENTRIES + SHAPE_ENTRY * e;
        int off = shape.getInt(at), size = shape.getInt(at + 4), stored = shape.getInt(at + 8);
        if (size == 0) return null;
        int[] ao = new int[5], as = new int[5];
        for (int i = 0; i < 5; i++) { ao[i] = shape.getInt(at + 0x10 + 4 * i); as[i] = shape.getInt(at + 0x24 + 4 * i); }
        int io = shape.getInt(at + 0x38), is = shape.getInt(at + 0x3c);
        float[] box = new float[6];
        for (int i = 0; i < 6; i++) box[i] = shape.getFloat(at + 0x40 + 4 * i);
        ByteBuffer d = ByteBuffer.wrap(inflate(shape, off, stored, size, "shape " + kind + "/" + index)).order(ByteOrder.LITTLE_ENDIAN);
        int n = as[0] / 8;
        if (!fits(d, ao[0], n * 8) || !fits(d, io, is) || (as[1] != 0 && !fits(d, ao[1], n * 4)) || (as[2] != 0 && !fits(d, ao[2], n * 4)))
            throw new IOException("shape " + kind + "/" + index + ": an attribute lies outside its " + size + " bytes");
        float[] pos = new float[n * 3], nor = new float[n * 3], uv = as[2] != 0 ? new float[n * 2] : null;
        for (int v = 0; v < n; v++) {
            for (int c = 0; c < 3; c++) pos[3 * v + c] = half(d.getShort(ao[0] + 8 * v + 2 * c));
            if (as[1] != 0) { int p = d.getInt(ao[1] + 4 * v); nor[3 * v] = snorm10(p); nor[3 * v + 1] = snorm10(p >> 10); nor[3 * v + 2] = snorm10(p >> 20); }
            if (uv != null) { uv[2 * v] = half(d.getShort(ao[2] + 4 * v)); uv[2 * v + 1] = half(d.getShort(ao[2] + 4 * v + 2)); }
        }
        short[] idx = new short[is / 2];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = d.getShort(io + 2 * i);
            if ((idx[i] & 0xffff) >= n) throw new IOException("shape " + kind + "/" + index + ": index " + (idx[i] & 0xffff) + " of " + n + " vertices");
        }
        return new Shape(pos, nor, uv, idx, box);
    }

    private static boolean fits(ByteBuffer d, int off, int len) { return off >= 0 && len >= 0 && (long) off + len <= d.capacity(); }

    /** Hair style i's placement for a hat (front, side, top: translate and rotate each, 18 floats) */
    public float[] hairTransform(int i) { return floats(shape, HAIR_TRANSFORMS_AT + HAIR_TRANSFORM * clamp(i, 131), 18); }

    /** Face shape i's anchors: where the hair, the nose and the beard sit on it (x, y, z each, 9 floats) */
    public float[] facelineTransform(int i) { return floats(shape, FACELINE_TRANSFORMS_AT + FACELINE_TRANSFORM * clamp(i, 11), 9); }

    private static int clamp(int i, int max) { return Math.max(0, Math.min(max, i)); }
    private static float[] floats(ByteBuffer b, int at, int n) { float[] f = new float[n]; for (int i = 0; i < n; i++) f[i] = b.getFloat(at + 4 * i); return f; }

    static byte[] inflate(ByteBuffer file, int off, int stored, int size, String what) throws IOException {
        if (off < 0 || stored <= 0 || size <= 0 || (long) off + stored > file.capacity() || size > MAX_FILE) throw new IOException(what + ": lies outside the file");
        byte[] in = new byte[stored];
        ByteBuffer b = file.duplicate(); b.position(off); b.get(in);
        Inflater z = new Inflater();
        try {
            z.setInput(in);
            byte[] out = new byte[size];
            int got = 0;
            while (got < size && !z.finished()) {
                int k = z.inflate(out, got, size - got);
                if (k == 0 && (z.needsInput() || z.needsDictionary())) break;
                got += k;
            }
            if (got != size) throw new IOException(what + ": " + got + " of " + size + " bytes inflated");
            return out;
        } catch (DataFormatException e) {
            throw new IOException(what + ": not a zlib stream (" + e.getMessage() + ")");
        } finally { z.end(); }
    }

    /** IEEE 754 half to float */
    static float half(short h) {
        int s = (h >> 15) & 1, e = (h >> 10) & 0x1f, m = h & 0x3ff;
        float v;
        if (e == 0) v = m * (1f / (1 << 24));                  // subnormal
        else if (e == 31) v = m == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = Float.intBitsToFloat((e - 15 + 127) << 23 | m << 13);
        return s != 0 ? -v : v;
    }

    /** The low 10 bits as a signed normalized value, -1 to 1 */
    static float snorm10(int bits) { int v = bits << 22 >> 22; return Math.max(-1f, v / 511f); }
}
