package dev.nxnative.android;

/**
 * PackPolicy on the JVM: each argument line "<battery %> <charging 0|1> <thermal status> <was: - | battery | thermal> <override 0|1>" prints the reason
 * (or "-"), then whether Go on anyway applies at that status, then the readings for headroom 0.5. Run by dm/tools/tests/test_android_packpolicy.py.
 * PEANUTS (#803): an optional sixth field is how long the heat pause has lasted in ms (without it, the five-argument rule: no cool-down known); a line
 * "stops <step>" prints whether a heat pause stops that step's tool (PackPolicy.heatStops) instead of holding it; "restart <state> [reason]" whether a sticky restart
 * runs the job again (PackPolicy.resumesAfterRestart).
 */
public final class PackPolicyJvmTest {
    public static void main(String[] a) {
        for (String line : a) {
            String[] f = line.split(" ");
            if (f[0].equals("stops")) { System.out.println("stops\t" + PackPolicy.heatStops(f[1]) + "\t-"); continue; }   // PEANUTS: #803
            if (f[0].equals("restart")) { System.out.println("restart\t" + PackPolicy.resumesAfterRestart(f[1], f.length > 2 ? f[2] : "") + "\t-"); continue; }   // PEANUTS: a sticky restart
            int pct = Integer.parseInt(f[0]), th = Integer.parseInt(f[2]);
            boolean ch = f[1].equals("1"), ov = f[4].equals("1");
            String was = f[3].equals("-") ? null : f[3];   // PEANUTS
            String r = f.length > 5 ? PackPolicy.reason(pct, ch, th, was, ov, Long.parseLong(f[5])) : PackPolicy.reason(pct, ch, th, was, ov);   // PEANUTS: the cool-down (#803)
            System.out.println((r == null ? "-" : r) + "\t" + PackPolicy.canOverride(r, th) + "\t" + PackPolicy.readings(pct, ch, th, 0.5f));
        }
        System.out.println(PackPolicy.readings(50, false, 1, Float.NaN));
    }
}
