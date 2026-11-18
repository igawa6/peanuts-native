package dev.nxnative.android;

/**
 * BananaGrid on the JVM: {@code hit <x> <y> <w> <h> <wx> <wy> <density>} prints "key <row> <col>" for the banana the tap is on, or "key none";
 * {@code centre <row> <col> <w> <h> <wx> <wy> <density>} prints "at <x> <y>": that banana's centre on the screen (the grid rotated about the view's centre).
 * Run by dm/tools/tests/test_android_bananagrid.py.
 */
public final class BananaGridJvmTest {
    public static void main(String[] a) {
        float w = Float.parseFloat(a[3]), h = Float.parseFloat(a[4]);
        double wx = Double.parseDouble(a[5]), wy = Double.parseDouble(a[6]);
        float d = Float.parseFloat(a[7]);
        if (a[0].equals("hit")) {
            long k = BananaGrid.hit(Float.parseFloat(a[1]), Float.parseFloat(a[2]), w, h, wx, wy, d);
            System.out.println(k == BananaGrid.NONE ? "key none" : "key " + (int) (k >> 32) + " " + (int) k);
        } else {
            int r = Integer.parseInt(a[1]), c = Integer.parseInt(a[2]);
            double gx = BananaGrid.centreX(r, c, BananaGrid.STEP_DP * d, wx), gy = BananaGrid.centreY(r, BananaGrid.ROW_DP * d, wy);
            double t = Math.toRadians(BananaGrid.GRID_DEG), cos = Math.cos(t), sin = Math.sin(t);
            System.out.printf("at %.3f %.3f%n", w / 2 + gx * cos - gy * sin, h / 2 + gx * sin + gy * cos);
        }
    }
}
