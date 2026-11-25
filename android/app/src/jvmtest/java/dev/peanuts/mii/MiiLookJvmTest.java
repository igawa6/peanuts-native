// PEANUTS: the Mii colours and the face's layout (Settings › Users, Miis)
package dev.peanuts.mii;

/**
 * MiiLook on the JVM (peanuts/tests/test_mii_parts.py): the colour tables and their lookups, how each part is coloured, and where the face parts sit on the face
 * texture: mirrored halves, sizes and turns that follow the Mii's values, the layout scaling with the texture's size. The table values are vee's (MIT or
 * Apache-2.0); the test checks known ones and the rules, not every number again.
 */
public final class MiiLookJvmTest {
    static int fails;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-3f; }

    public static void main(String[] a) {
        colours();
        tints();
        mask();
        parts3d();
        System.out.println(fails == 0 ? "PASS" : fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void colours() {
        check(MiiLook.COMMON_COLOR.length == 100 && MiiLook.UPPER_LIP_COLOR.length == 100, "100 common colours, each with its upper lip's");
        check(MiiLook.FACELINE_COLOR.length == 10 && MiiLook.FAVORITE_COLOR.length == 12, "10 skin colours, 12 favourite colours");
        check(MiiLook.common(0) == 0x2D2828 && MiiLook.common(8) == 0x000000 && MiiLook.common(99) == 0xFFFFFF, "black hair, black, white");
        check(MiiLook.WHITE == 0xFFFFFF && MiiLook.BLACK == 0x000000, "WHITE and BLACK are the common colours 99 and 8");
        check(MiiLook.skin(0) == 0xFFD3AD && MiiLook.skin(9) == 0x3C2D23, "the lightest and the darkest skin");
        check(MiiLook.favorite(0) == 0xD21E14 && MiiLook.favorite(10) == 0xE0E0E0 && MiiLook.favorite(11) == 0x181814, "favourite red, white, black");
        check(MiiLook.common(-5) == MiiLook.common(0) && MiiLook.common(100) == MiiLook.common(99), "common colours clamp at both ends");
        check(MiiLook.upperLip(-1) == MiiLook.UPPER_LIP_COLOR[0] && MiiLook.upperLip(1000) == MiiLook.UPPER_LIP_COLOR[99], "upper lips clamp");
        check(MiiLook.skin(10) == MiiLook.skin(9) && MiiLook.favorite(-1) == MiiLook.favorite(0) && MiiLook.favorite(12) == MiiLook.favorite(11), "skin and favourite clamp");
        boolean darker = true;
        for (int i = 0; i < 100; i++) if (lum(MiiLook.UPPER_LIP_COLOR[i]) > lum(MiiLook.COMMON_COLOR[i])) darker = false;
        check(darker, "each upper lip is no lighter than its colour");
        boolean rgb = true;
        for (int[] t : new int[][] {MiiLook.COMMON_COLOR, MiiLook.UPPER_LIP_COLOR, MiiLook.FACELINE_COLOR, MiiLook.FAVORITE_COLOR}) for (int c : t) if ((c & 0xff000000) != 0) rgb = false;
        check(rgb, "the tables are 0xRRGGBB, no alpha");
    }

    static int lum(int c) { return (c >> 16 & 0xff) * 299 + (c >> 8 & 0xff) * 587 + (c & 0xff) * 114; }

    static void tints() {
        MiiStore.Mii m = MiiStore.fromDefault(0, "T");
        m.set("eye_color", 12); m.set("mouth_color", 21); m.set("eyebrow_color", 3); m.set("glass_color", 50); m.set("beard_color", 7); m.set("favorite_color", 5);
        MiiLook.Tint e = MiiLook.eye(m);
        check(e.mode == MiiLook.LAYERED && e.r == MiiLook.BLACK && e.g == MiiLook.WHITE && e.b == MiiLook.common(12), "the eye: black outline, white, the eye's colour");
        MiiLook.Tint mo = MiiLook.mouth(m);
        check(mo.mode == MiiLook.LAYERED && mo.r == MiiLook.common(21) && mo.g == MiiLook.upperLip(21) && mo.b == MiiLook.WHITE, "the mouth: its colour, its upper lip, white teeth");
        check(MiiLook.eyebrow(m).mode == MiiLook.ALPHA && MiiLook.eyebrow(m).r == MiiLook.common(3), "eyebrows: coverage of their colour");
        check(MiiLook.glass(m).mode == MiiLook.LUM_ALPHA && MiiLook.glass(m).r == MiiLook.common(50), "glasses: brightness and coverage of their colour");
        check(MiiLook.mustache(m).r == MiiLook.common(7) && MiiLook.beardTexture(m).r == MiiLook.common(7), "mustache and a drawn beard take the beard colour");
        check(MiiLook.hat(m).mode == MiiLook.LUM && MiiLook.hat(m).r == MiiLook.favorite(5), "a hat: brightness of the favourite colour");
        check(MiiLook.makeup().mode == MiiLook.DIRECT && MiiLook.noseline().r == MiiLook.BLACK && MiiLook.mole().r == MiiLook.BLACK, "make-up as it is; nose line and mole black");
        check(MiiLook.wrinkle(m).mode == MiiLook.ALPHA, "wrinkles: coverage");
    }

    static void mask() {
        for (int d = 0; d < 6; d++) {
            MiiStore.Mii m = MiiStore.fromDefault(d, "M");
            for (float px : new float[] {256, 512}) {
                MiiLook.Mask k = MiiLook.mask(m, px);
                String at = "default " + d + " at " + (int) px + ": ";
                check(near(k.eyeL.x + k.eyeR.x, px) && near(k.eyeL.y, k.eyeR.y) && near(k.eyeL.w, k.eyeR.w) && near(k.eyeL.h, k.eyeR.h), at + "the eyes mirror around the middle");
                check(near(k.browL.x + k.browR.x, px) && near(k.browL.y, k.browR.y), at + "the eyebrows mirror around the middle");
                check(near(k.eyeL.deg + k.eyeR.deg, 360) && near(k.browL.deg + k.browR.deg, 360), at + "a right part turns the other way");
                check(k.eyeL.origin == MiiLook.LEFT && k.eyeR.origin == MiiLook.RIGHT && k.browL.origin == MiiLook.LEFT && k.browR.origin == MiiLook.RIGHT, at + "halves anchored at their inner edge");
                check(near(k.mouth.x, px / 2) && k.mouth.origin == MiiLook.CENTER && near(k.mouth.deg, 0), at + "the mouth centred and level");
                check(near(k.mustacheL.x, px / 2) && near(k.mustacheR.x, px / 2) && k.mustacheL.origin == MiiLook.LEFT && k.mustacheR.origin == MiiLook.RIGHT, at + "mustache halves meet in the middle");
                check(k.browL.y > 0 && k.browL.y < k.eyeL.y && k.eyeL.y < k.mouth.y && k.mouth.y < px && k.mustacheL.y > k.eyeL.y && k.mustacheL.y < px,
                        at + "eyebrows over the eyes over the mouth, the mustache under the eyes, all inside the texture");
            }
            MiiLook.Mask small = MiiLook.mask(m, 256), big = MiiLook.mask(m, 512);
            check(near(big.eyeL.x, 2 * small.eyeL.x) && near(big.mouth.w, 2 * small.mouth.w) && near(big.mole.y, 2 * small.mole.y) && near(big.eyeL.deg, small.eyeL.deg),
                    "default " + d + ": the layout scales with the texture, the turns do not");
        }
        MiiStore.Mii m = MiiStore.fromDefault(0, "M");
        float ex = MiiLook.mask(m, 256).eyeL.x;
        m.set("eye_x", m.get("eye_x") + 2);
        check(MiiLook.mask(m, 256).eyeL.x > ex && MiiLook.mask(m, 256).eyeR.x < 256 - ex, "a larger eye spacing moves both eyes out");
        float ew = MiiLook.mask(m, 256).eyeL.w, eh = MiiLook.mask(m, 256).eyeL.h;
        m.set("eye_scale", m.get("eye_scale") + 1);
        check(MiiLook.mask(m, 256).eyeL.w > ew && MiiLook.mask(m, 256).eyeL.h > eh, "a larger eye size grows the eye");
        float ratio = MiiLook.mask(m, 256).eyeL.h / MiiLook.mask(m, 256).eyeL.w;
        m.set("eye_aspect", m.get("eye_aspect") + 1);
        check(MiiLook.mask(m, 256).eyeL.h / MiiLook.mask(m, 256).eyeL.w > ratio, "a larger eye height makes it taller for its width");
        float deg = MiiLook.mask(m, 256).eyeR.deg;
        m.set("eye_rotate", m.get("eye_rotate") + 1);
        check(near(MiiLook.mask(m, 256).eyeR.deg - deg, 360f / 32), "a turn step is a 32nd of a circle");
        m.set("eye_type", 500);
        check(!Float.isNaN(MiiLook.mask(m, 256).eyeR.deg), "an eye type past the table clamps");
        float bx = MiiLook.mask(m, 256).browL.x;
        m.set("eyebrow_x", m.get("eyebrow_x") + 1);
        check(MiiLook.mask(m, 256).browL.x > bx, "a larger eyebrow spacing moves them out");
        float my = MiiLook.mask(m, 256).mouth.y;
        m.set("mouth_y", m.get("mouth_y") + 1);
        check(MiiLook.mask(m, 256).mouth.y > my, "a larger mouth position moves it down");
        float ox = MiiLook.mask(m, 256).mole.x, ow = MiiLook.mask(m, 256).mole.w;
        m.set("mole_x", m.get("mole_x") + 1); m.set("mole_scale", m.get("mole_scale") + 1);
        check(MiiLook.mask(m, 256).mole.x > ox && MiiLook.mask(m, 256).mole.w > ow, "the mole moves across and grows");
        float uw = MiiLook.mask(m, 256).mustacheL.w;
        m.set("mustache_scale", m.get("mustache_scale") + 1);
        check(MiiLook.mask(m, 256).mustacheL.w > uw, "a larger mustache size grows it");
    }

    static void parts3d() {
        MiiStore.Mii m = MiiStore.fromDefault(0, "M");
        m.set("nose_y", 8);
        check(near(MiiLook.noseDy(m), 0), "the nose's middle position does not move it");
        m.set("nose_y", 9);
        check(MiiLook.noseDy(m) < 0, "a larger nose position moves it down");
        m.set("nose_scale", 0); float n0 = MiiLook.noseScale(m); m.set("nose_scale", 8);
        check(n0 > 0 && MiiLook.noseScale(m) > n0, "the nose grows with its size");
        m.set("glass_scale", 0); float g0 = MiiLook.glassScale(m); m.set("glass_scale", 7);
        check(g0 > 0 && MiiLook.glassScale(m) > g0, "glasses grow with their size");
        m.set("glass_y", 11);
        check(near(MiiLook.glassDy(m), 5), "glasses at their middle position sit a little over the nose's anchor");
        for (int t = 0; t <= 5; t++) { m.set("beard_type", t); check(MiiLook.beardShape(m) == (t >= 1 && t <= 3), "beard " + t + ": " + (t >= 1 && t <= 3 ? "a shape" : "drawn or none")); }
    }
}
