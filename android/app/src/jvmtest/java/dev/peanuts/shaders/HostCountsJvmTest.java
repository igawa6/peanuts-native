// PEANUTS: a test of the fork's own classes (HostCounts: the host's numbers as the Peanuts tab shows them); upstream has no dev.peanuts tests
package dev.peanuts.shaders;

/** HostCounts on the JVM (peanuts/tests/test_shader_watch.py): the host's four numbers as the Peanuts tab's */
public final class HostCountsJvmTest {
    private static int failed;

    private static void check(boolean ok, String what) {
        if (!ok) { failed++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] args) {
        // the host cannot say (no libnxhost of this version, or not loaded)
        check(!HostCounts.available(null), "no host: not available");
        check(HostCounts.learned(null) == HostCounts.UNKNOWN && HostCounts.ready(null) == HostCounts.UNKNOWN, "no host: unknown numbers");
        check(!HostCounts.available(new int[3]), "a short answer is no answer");

        // the list not read yet: the game has not drawn its first program (its menus, a logo); a list is still to come
        int[] early = {-1, 0, 0, 0};
        check(HostCounts.available(early), "not read yet: still available");
        check(HostCounts.learned(early) == HostCounts.UNKNOWN && HostCounts.ready(early) == HostCounts.UNKNOWN, "not read yet: numbers unknown");

        // no list at all (no DM_TEMP: the host keeps nothing)
        int[] none = {0, 0, 0, 0};
        check(!HostCounts.available(none), "no list: not available");
        check(HostCounts.learned(none) == HostCounts.UNKNOWN, "no list: learned unknown");

        // a list: this run's new pipelines and what the next start makes ahead
        int[] list = {1, 12, 642, 868};
        check(HostCounts.available(list), "a list: available");
        check(HostCounts.learned(list) == 12, "learned this run");
        check(HostCounts.ready(list) == 642, "made ahead next start on this driver, not every driver's records");

        // a driver whose next start makes none ahead (prewarm off there): ready 0, learned still counts
        int[] off = {1, 5, 0, 868};
        check(HostCounts.ready(off) == 0 && HostCounts.learned(off) == 5, "prewarm off: 0 ready, learned counted");

        // never a negative count from a host that misbehaves
        check(HostCounts.learned(new int[]{1, -3, -1, 0}) == 0 && HostCounts.ready(new int[]{1, -3, -1, 0}) == 0, "no negative counts");

        if (failed > 0) { System.out.println(failed + " failed"); System.exit(1); }
        System.out.println("ok");
    }
}
