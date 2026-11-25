// PEANUTS: the firmware's profile pictures, Yaz0-packed (Settings › Users)
package dev.peanuts.users;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

/**
 * Yaz0 on the JVM (peanuts/tests/test_users_page.py): data packed here (a small encoder of the test's own: literals, short and long back references, ones that
 * overlap what they copy) comes back as it was, and what a broken or hostile file holds is refused: not Yaz0, a size past the limit, a reference before the
 * start, an input that ends early. A reference running past the end stops there; nothing is written past the size the header gives.
 */
public final class Yaz0JvmTest {
    static int fails;
    /** The limit FirmwarePictures gives Yaz0: a 256 x 256 RGBA picture (FirmwarePictures itself makes a Bitmap, so the test leaves it out) */
    static final int PICTURE = 256 * 256 * 4;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) throws Exception {
        roundTrips();
        refusals();
        System.out.println(fails == 0 ? "PASS" : fails + " failures");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void roundTrips() throws IOException {
        check(Yaz0.decode(yaz0(new byte[0]), 16).length == 0, "nothing packed is nothing");
        byte[] text = "a Mii, a Mii, a Mii and a picture of a peanut".getBytes("US-ASCII");
        check(Arrays.equals(Yaz0.decode(yaz0(text), 1 << 10), text), "text with repeats");
        byte[] run = new byte[1000];
        Arrays.fill(run, (byte) 7);
        check(Arrays.equals(Yaz0.decode(yaz0(run), 1 << 10), run), "a run: references that overlap what they copy, the long form (18 to 273)");
        Random r = new Random(3);
        byte[] noise = new byte[5000];
        r.nextBytes(noise);
        check(Arrays.equals(Yaz0.decode(yaz0(noise), 1 << 13), noise), "noise: literals only");
        byte[] picture = new byte[PICTURE];   // a firmware picture's size: transparent with a few coloured rows
        for (int y = 100; y < 140; y++) for (int x = 0; x < 256; x++) { int i = (y * 256 + x) * 4; picture[i] = (byte) x; picture[i + 1] = (byte) y; picture[i + 3] = (byte) 255; }
        byte[] packed = yaz0(picture);
        check(packed.length < picture.length / 4, "a picture packs small, with references (" + packed.length + " bytes)");
        check(Arrays.equals(Yaz0.decode(packed, PICTURE), picture), "a 256 x 256 RGBA picture, at the limit FirmwarePictures gives");
        byte[] mixed = new byte[20000];
        for (int i = 0; i < mixed.length; i++) mixed[i] = (byte) (r.nextInt(5) == 0 ? r.nextInt(256) : i % 300);
        check(Arrays.equals(Yaz0.decode(yaz0(mixed), mixed.length), mixed), "short and long references far back, mixed with literals");
    }

    static void refusals() {
        refused(new byte[] {'Y', 'a', 'z', '0'}, 16, "not Yaz0", "a header cut short");
        byte[] notYaz = yaz0(new byte[4]);
        notYaz[3] = '1';
        refused(notYaz, 16, "not Yaz0", "another magic");
        refused(yaz0(new byte[17]), 16, "more than 16", "a size past the limit");
        byte[] huge = yaz0(new byte[0]);
        huge[4] = (byte) 0xff; huge[5] = (byte) 0xff; huge[6] = (byte) 0xff; huge[7] = (byte) 0xf0;   // a u32 size near 4 GiB, never a negative array
        refused(huge, 1 << 20, "more than", "a size near 4 GiB");
        try { check(Yaz0.decode(yaz0(new byte[16]), 16).length == 16, "a size at the limit is fine"); } catch (IOException e) { check(false, "a size at the limit: " + e.getMessage()); }

        // hand-made streams: header with size n, then groups
        refused(stream(4, 0x00, 0x10, 0x00), 64, "before the start", "a reference at the start points before it");
        refused(stream(4, 0x80, 'A', 0x10, 0x01), 64, "before the start", "a reference two bytes back after one byte points before the start");
        refused(stream(4, 0xc0, 'A', 'B', 0x10, 0x02), 64, "before the start", "a reference three back after two");
        refused(stream(8, 0x80, 'A'), 64, "ends early", "the input ends before the size is reached");
        refused(stream(8, 0x80, 'A', 0x00), 64, "ends early", "a reference cut after its first byte");
        refused(stream(30, 0x80, 'A', 0x00, 0x00), 64, "ends early", "a long reference without its length byte");
        try {
            byte[] out = Yaz0.decode(stream(3, 0x80, 'A', 0xf0, 0x00), 64);   // a reference of 17 when two bytes are left
            check(out.length == 3 && out[0] == 'A' && out[1] == 'A' && out[2] == 'A', "a reference past the end stops at the size");
            out = Yaz0.decode(stream(2, 0xff, 'A', 'B', 'C', 'D'), 64);
            check(out.length == 2 && out[1] == 'B', "literals past the end are not written");
        } catch (IOException e) { check(false, "a stream that runs past its size: " + e.getMessage()); }
    }

    /** in is refused with an IOException that says why (its message holds because), never with another exception */
    static void refused(byte[] in, int max, String because, String what) {
        try { Yaz0.decode(in, max); check(false, "not refused: " + what); }
        catch (IOException e) { check(String.valueOf(e.getMessage()).contains(because), what + ": refused as \"" + e.getMessage() + "\", not for \"" + because + "\""); }
        catch (RuntimeException e) { check(false, what + ": " + e); }
    }

    /** "Yaz0", size n, 8 zero bytes, then the given bytes */
    static byte[] stream(int n, int... body) {
        byte[] out = new byte[16 + body.length];
        out[0] = 'Y'; out[1] = 'a'; out[2] = 'z'; out[3] = '0';
        out[4] = (byte) (n >>> 24); out[5] = (byte) (n >>> 16); out[6] = (byte) (n >>> 8); out[7] = (byte) n;
        for (int i = 0; i < body.length; i++) out[16 + i] = (byte) body[i];
        return out;
    }

    /** A plain Yaz0 encoder: at each byte the longest match up to 4096 back (3 to 273 bytes, the nearest of equal ones), else a literal */
    static byte[] yaz0(byte[] in) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int n = in.length;
        o.write('Y'); o.write('a'); o.write('z'); o.write('0');
        o.write(n >>> 24); o.write(n >>> 16); o.write(n >>> 8); o.write(n);
        for (int i = 0; i < 8; i++) o.write(0);
        int s = 0;
        while (s < n) {
            int flags = 0;
            ByteArrayOutputStream group = new ByteArrayOutputStream();
            for (int bit = 7; bit >= 0 && s < n; bit--) {
                int best = 0, dist = 0;
                for (int from = s - 1; from >= Math.max(0, s - 4096) && best < 273; from--) {   // the nearest first
                    int len = 0;
                    while (len < 273 && s + len < n && in[from + len] == in[s + len]) len++;
                    if (len > best) { best = len; dist = s - from; }
                }
                if (best < 3) { flags |= 1 << bit; group.write(in[s++]); continue; }
                int d = dist - 1;
                if (best <= 17) { group.write((best - 2) << 4 | d >> 8); group.write(d & 0xff); }
                else { group.write(d >> 8); group.write(d & 0xff); group.write(best - 0x12); }
                s += best;
            }
            o.write(flags);
            byte[] g = group.toByteArray();
            o.write(g, 0, g.length);
        }
        return o.toByteArray();
    }
}
