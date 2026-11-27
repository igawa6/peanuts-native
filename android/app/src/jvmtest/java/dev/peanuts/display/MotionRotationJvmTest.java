// PEANUTS: a test of the fork's own classes (GitHub issue #811); upstream has no dev.peanuts tests
package dev.peanuts.display;

import java.util.ArrayList;
import java.util.List;

/** MotionRotation on the JVM (no argument; prints "ok" or throws): the host hears each real turn, the half turn between the landscape sides included, and
 *  nothing for a display event that is no turn; a resume tells it again. */
public final class MotionRotationJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        final List<Integer> told = new ArrayList<>();
        MotionRotation m = new MotionRotation(told::add);
        check(m.set(1) && told.equals(List.of(1)), "the first rotation is told");
        check(!m.set(1) && told.size() == 1, "the same rotation again (a refresh rate change) tells nothing");
        check(m.set(3) && told.equals(List.of(1, 3)), "ROTATION_90 -> ROTATION_270, the half turn no configuration change reports, is told");
        check(m.set(1) && told.equals(List.of(1, 3, 1)), "and back");
        check(m.set(0) && told.get(3) == 0, "portrait is told");
        check(m.set(7) && told.get(4) == 3, "only the two rotation bits count");
        m.forget();
        check(m.set(3) && told.size() == 6 && told.get(5) == 3, "after forget (a resume) the same rotation is told again");
        check(!m.set(3), "then it is the last one told");
        System.out.println("ok");
    }
}
