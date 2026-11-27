package dev.peanuts.display;

/** The display rotation the host maps the phone's gyroscope and accelerometer with (Native.motionRotation, Surface.ROTATION_0..3): told again only when it
 *  changed, so a display event that is no turn (a refresh rate change) costs a compare. Plain Java (MotionRotationJvmTest); RotationWatch feeds it. */
public final class MotionRotation {
    public interface Sink { void rotation(int r); }

    private final Sink sink;
    private int last = -1;   // none told yet

    public MotionRotation(Sink sink) { this.sink = sink; }

    /** The display's rotation now: true when it differed from the last one told, and the sink was told. */
    public boolean set(int r) {
        r &= 3;
        if (r == last) return false;
        last = r;
        sink.rotation(r);
        return true;
    }

    /** The next set() tells the sink whatever the rotation (the screen resumed: it may have turned while away). */
    public void forget() { last = -1; }
}
