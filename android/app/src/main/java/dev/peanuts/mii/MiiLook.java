package dev.peanuts.mii;

/**
 * What the firmware's Mii parts do not say: the colours behind a CharInfo's colour numbers, where each face part sits on the face texture, and how each part is
 * coloured. These numbers live in the console's Mii code, not in its data files; they come from the vee project (github.com/j0lol/vee: vee_resources/src/color.rs,
 * vee_models/src/positioning.rs and building.rs, MIT or Apache-2.0), which took them from the decompilation of the Wii U's face library (FFL). The colour tables
 * are vee's sRGB ones as 0xRRGGBB. No Android classes: MiiLookJvmTest checks them on the JVM.
 */
public final class MiiLook {
    private MiiLook() {}

    /** The 100 common colours: hair, eyes, eyebrows, mouth, glasses, beard (CharInfo colour 0 to 99) */
    static final int[] COMMON_COLOR = {  // 100
        0x2D2828, 0x402010, 0x5C180A, 0x7C3A14, 0x787880, 0x4E3E10, 0x885818, 0xD0A04A, 0x000000, 0x6C7070,
        0x663C2C, 0x605E30, 0x4654A8, 0x387058, 0x603810, 0xA81008, 0x203068, 0xA86000, 0x787068, 0xD85208,
        0xF00C08, 0xF54848, 0xF09A74, 0x8C5040, 0x842626, 0xFF7366, 0xFFA6A6, 0xFFC0BA, 0x732E3B, 0x991F3D,
        0x8A173E, 0xB53E42, 0xC71E56, 0xB05381, 0xC7546E, 0xFA7597, 0xFCACC9, 0xFFC9D8, 0x311C40, 0x37283D,
        0x4C184D, 0x6F42B3, 0x855CB8, 0xC083CC, 0xA893C9, 0xC5ACE6, 0xEEBEFA, 0xD2C5ED, 0x191F40, 0x123F66,
        0x2A82D4, 0x57B4F2, 0x7AC5DE, 0x89A6FA, 0x84BDFA, 0xA1E3FF, 0x0B2E36, 0x013D3B, 0x0D4F59, 0x236663,
        0x307E8C, 0x4FAEB0, 0x7AC49E, 0x7FD4C0, 0x87E5B6, 0x0A4A35, 0x437A00, 0x027562, 0x369970, 0x4BAD1A,
        0x92BF0A, 0x63C788, 0x9EE042, 0x96DE7E, 0xBBF2AA, 0x99932B, 0xA69563, 0xCCC039, 0xCCB987, 0xD9CC82,
        0xD5D96F, 0xD5E683, 0xD8FA9D, 0x7D4500, 0xE6BB7A, 0xFEE24A, 0xFADE82, 0xF7EA9C, 0xFAF89B, 0xA64D1E,
        0xFF960D, 0xD19B69, 0xFFB266, 0xFFC28C, 0xE5CFB1, 0x414141, 0x9B9B9B, 0xBEBEBE, 0xDCD7CD, 0xFFFFFF,
    };
    /** The upper lip's shade of each common colour (the mouth texture's second layer) */
    static final int[] UPPER_LIP_COLOR = {  // 100
        0x171414, 0x201008, 0x2E0C05, 0x4A230C, 0x54545A, 0x271F08, 0x52350E, 0xB18028, 0x000000, 0x4C4E4E,
        0x331E16, 0x3A381D, 0x2A3265, 0x274E3E, 0x301C08, 0x650A05, 0x101834, 0x764300, 0x544E49, 0x823018,
        0x780C0C, 0x882028, 0xDC7850, 0x461E0A, 0x4F1717, 0x99453D, 0xE68585, 0xE6A19B, 0x451C23, 0x5C1325,
        0x530E25, 0x6D2528, 0x771234, 0x6A324D, 0x773242, 0xAF526A, 0xE38CAC, 0xE6ABBB, 0x190E20, 0x1C141F,
        0x260C27, 0x43286B, 0x50376E, 0x865C8F, 0x76678D, 0xAB90CF, 0xD4A0E1, 0xB8AAD5, 0x0D1020, 0x092033,
        0x1D5B94, 0x3297DA, 0x5CADC8, 0x6786E1, 0x629FE1, 0x80C7E6, 0x06171B, 0x011F1E, 0x07282D, 0x184745,
        0x225862, 0x308B8D, 0x60B087, 0x63BFA9, 0x69CE9B, 0x05251B, 0x284900, 0x01463B, 0x266B4E, 0x2C8A00,
        0x6E9900, 0x47B36F, 0x82CA1F, 0x7AC860, 0x9EDA8C, 0x6B671E, 0x746845, 0xA39816, 0xB8A36D, 0xC3B565,
        0xBFC351, 0xBDCF64, 0xBCE17D, 0x4B2900, 0xCFA15A, 0xE5C622, 0xE1C35F, 0xDED07C, 0xE1DF7A, 0x642E12,
        0xCC780A, 0xBC824C, 0xE69240, 0xE6A469, 0xCEB696, 0x212121, 0x7C7C7C, 0xABABAB, 0xC6C1B6, 0xD9D9D9,
    };
    /** Skin colours (CharInfo faceline_color 0 to 9) */
    static final int[] FACELINE_COLOR = {  // 10
        0xFFD3AD, 0xFFB66B, 0xDE7942, 0xFFAA8C, 0xAD5129, 0x632C18, 0xFFBEA5, 0xFFC58F, 0x8C3C23, 0x3C2D23,
    };
    /** Favourite colours (CharInfo favorite_color 0 to 11): red, orange, yellow, light green, green, blue, light blue, pink, purple, brown, white, black */
    static final int[] FAVORITE_COLOR = {  // 12
        0xD21E14, 0xFF6E19, 0xFFD820, 0x78D220, 0x007830, 0x0A48B4, 0x3CAADE, 0xF55A7D, 0x7328AD, 0x483818,
        0xE0E0E0, 0x181814,
    };
    static final int WHITE = COMMON_COLOR[99], BLACK = COMMON_COLOR[8];

    public static int common(int i) { return COMMON_COLOR[clamp(i, 99)]; }
    public static int upperLip(int i) { return UPPER_LIP_COLOR[clamp(i, 99)]; }
    public static int skin(int i) { return FACELINE_COLOR[clamp(i, 9)]; }
    public static int favorite(int i) { return FAVORITE_COLOR[clamp(i, 11)]; }
    static int clamp(int i, int max) { return Math.max(0, Math.min(max, i)); }

    // ---- How a texture becomes colour (FFL's modulate modes) ----
    /** COLOR: one colour; DIRECT: the texture as it is; LAYERED: red, green and blue each a colour of its own; ALPHA: red is coverage of one colour;
     *  LUM_ALPHA: red is brightness, green coverage; LUM: red is brightness, opaque */
    public static final int COLOR = 0, DIRECT = 1, LAYERED = 2, ALPHA = 3, LUM_ALPHA = 4, LUM = 5;

    /** A part's colouring: the mode and up to three colours (0xRRGGBB; unused ones 0) */
    public static final class Tint {
        public final int mode, r, g, b;
        Tint(int mode, int r, int g, int b) { this.mode = mode; this.r = r; this.g = g; this.b = b; }
    }

    public static Tint eye(MiiStore.Mii m) { return new Tint(LAYERED, BLACK, WHITE, common(m.get("eye_color"))); }
    public static Tint eyebrow(MiiStore.Mii m) { return new Tint(ALPHA, common(m.get("eyebrow_color")), 0, 0); }
    public static Tint mouth(MiiStore.Mii m) { return new Tint(LAYERED, common(m.get("mouth_color")), upperLip(m.get("mouth_color")), WHITE); }
    public static Tint glass(MiiStore.Mii m) { return new Tint(LUM_ALPHA, common(m.get("glass_color")), 0, 0); }
    public static Tint noseline() { return new Tint(ALPHA, BLACK, 0, 0); }
    public static Tint beardTexture(MiiStore.Mii m) { return new Tint(ALPHA, common(m.get("beard_color")), 0, 0); }
    public static Tint wrinkle(MiiStore.Mii m) { return new Tint(ALPHA, common(m.get("faceline_color")), 0, 0); }
    public static Tint makeup() { return new Tint(DIRECT, 0, 0, 0); }
    public static Tint mole() { return new Tint(ALPHA, BLACK, 0, 0); }
    public static Tint mustache(MiiStore.Mii m) { return new Tint(ALPHA, common(m.get("beard_color")), 0, 0); }
    public static Tint hat(MiiStore.Mii m) { return new Tint(LUM, favorite(m.get("favorite_color")), 0, 0); }

    // ---- Where the face parts sit on the face texture (FFL's mask) ----
    static final float TEX_SCALE_X = 0.88961464f, TEX_SCALE_Y = 0.9276675f, MAGIC_Y = 1.1600001f;
    static float scale2dim(float scale) { return 1f + 0.4f * scale; }
    static float rotate2deg(int rotate) { return (360f / 32f) * (rotate % 32); }
    static float unit(float x) { return x / 64f; }
    static final float EYE_Y = 18.451525f, EYE_W = unit(342), EYE_H = unit(288);
    static final float BROW_Y = 16.549807f, BROW_W = unit(324), BROW_H = unit(288);
    static final float MOUTH_Y = 29.25885f, MOUTH_W = unit(396), MOUTH_H = unit(288);
    static final float MUSTACHE_Y = 31.763554f, MUSTACHE_W = unit(288), MUSTACHE_H = unit(576);
    static final float MOLE_X = 17.766165f, MOLE_Y = 17.95986f;
    /** Each eye type's own tilt, which the rotation adds to (FFL GetEyeRotateOffset: 32 minus these) */
    private static final byte[] EYE_ROT = {
        3, 4, 4, 4, 3, 4, 4, 4, 3, 4, 4, 4, 4, 3, 3, 4, 4, 4, 3, 3, 4, 3, 4, 3, 3, 4, 3, 4, 4, 3,
        4, 4, 4, 3, 3, 3, 4, 4, 3, 3, 3, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 3, 4, 4, 3,
        4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
    };
    private static final byte[] BROW_ROT = {26, 26, 27, 25, 26, 25, 26, 25, 28, 25, 26, 24, 27, 27, 26, 26, 25, 25, 26, 26, 27, 26, 25, 27};

    /** Which edge of the picture is the anchor: its centre, its left edge (the face's left part, drawn as it is) or its right edge (the mirrored one) */
    public static final int CENTER = 0, LEFT = 1, RIGHT = 2;

    /** One face part on the face texture: anchor x and y (y downward), size and angle in the texture's pixels and degrees; mirrored for the right-side copy */
    public static final class Place {
        public final float x, y, w, h, deg; public final int origin;
        Place(float x, float y, float w, float h, float deg, int origin) { this.x = x; this.y = y; this.w = w; this.h = h; this.deg = deg; this.origin = origin; }
    }

    /** The face's parts on a face texture of size px (the console draws it at 256 or 512): two eyes, two eyebrows, the mouth, two mustache halves, the mole */
    public static final class Mask {
        public Place eyeL, eyeR, browL, browR, mouth, mustacheL, mustacheR, mole;
    }

    public static Mask mask(MiiStore.Mii m, float px) {
        float base = unit(px);
        Mask k = new Mask();
        float es = scale2dim(m.get("eye_scale")), ea = 0.12f * m.get("eye_aspect") + 0.64f;
        float ex = TEX_SCALE_X * m.get("eye_x"), ey = EYE_Y + MAGIC_Y * TEX_SCALE_Y * m.get("eye_y");
        float edeg = rotate2deg(m.get("eye_rotate") + 32 - EYE_ROT[clamp(m.get("eye_type"), EYE_ROT.length - 1)]);
        k.eyeL = new Place(base * (32 + ex), base * ey, base * EYE_W * es, base * EYE_H * es * ea, 360 - edeg, LEFT);
        k.eyeR = new Place(base * (32 - ex), base * ey, base * EYE_W * es, base * EYE_H * es * ea, edeg, RIGHT);
        float bs = scale2dim(m.get("eyebrow_scale")), ba = 0.12f * m.get("eyebrow_aspect") + 0.64f;
        float bx = TEX_SCALE_X * m.get("eyebrow_x"), by = BROW_Y + MAGIC_Y * TEX_SCALE_Y * m.get("eyebrow_y");
        float bdeg = rotate2deg(m.get("eyebrow_rotate") + BROW_ROT[clamp(m.get("eyebrow_type"), BROW_ROT.length - 1)]);
        k.browL = new Place(base * (32 + bx), base * by, base * BROW_W * bs, base * BROW_H * bs * ba, 360 - bdeg, LEFT);
        k.browR = new Place(base * (32 - bx), base * by, base * BROW_W * bs, base * BROW_H * bs * ba, bdeg, RIGHT);
        float ms = scale2dim(m.get("mouth_scale")), ma = 0.12f * m.get("mouth_aspect") + 0.64f;
        float my = MOUTH_Y + MAGIC_Y * TEX_SCALE_Y * m.get("mouth_y");
        k.mouth = new Place(base * 32, base * my, base * MOUTH_W * ms, base * MOUTH_H * ms * ma, 0, CENTER);
        float us = scale2dim(m.get("mustache_scale")), uy = MUSTACHE_Y + MAGIC_Y * TEX_SCALE_Y * m.get("mustache_y");
        k.mustacheL = new Place(base * 32, base * uy, base * MUSTACHE_W * us, base * MUSTACHE_H * us, 0, LEFT);
        k.mustacheR = new Place(base * 32, base * uy, base * MUSTACHE_W * us, base * MUSTACHE_H * us, 0, RIGHT);
        float os = scale2dim(m.get("mole_scale"));
        float ox = MOLE_X + 2 * TEX_SCALE_X * m.get("mole_x"), oy = MOLE_Y + MAGIC_Y * TEX_SCALE_Y * m.get("mole_y");
        k.mole = new Place(base * ox, base * oy, base * os, base * os, 0, CENTER);
        return k;
    }

    // ---- Where the 3D parts sit on the head (vee building / draw model) ----
    /** The nose's offset from the face's nose anchor: the nose position moves it up or down */
    public static float noseDy(MiiStore.Mii m) { return (m.get("nose_y") - 8) * -1.5f; }
    public static float noseScale(MiiStore.Mii m) { return 0.175f * m.get("nose_scale") + 0.4f; }
    public static float glassDy(MiiStore.Mii m) { return (m.get("glass_y") - 11) * -1.5f + 5f; }
    public static float glassScale(MiiStore.Mii m) { return 0.15f * m.get("glass_scale") + 0.4f; }
    /** A beard of type 1 to 3 is a shape; 4 and 5 are drawn on the face texture */
    public static boolean beardShape(MiiStore.Mii m) { int t = m.get("beard_type"); return t > 0 && t < 4; }
}
