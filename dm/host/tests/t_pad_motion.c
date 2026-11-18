// A controller's own motion sensors (android/pad_motion.h): units and sign of the acceleration, the gyro's rest offset (found while still, kept while moving, not found in a hand's
// tremor), and bad samples. No host, no controller
#include "../android/pad_motion.h"
#include <stdio.h>

static int checks, fails;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s (line %d)\n", msg, __LINE__); } } while (0)
static int near(float a, float b, float eps) { return fabsf(a - b) <= eps; }

#define DT 5000000ull   // 200 Hz
// seconds of one constant sample; the clock carries on from *t
static void run(pad_motion_cal *c, uint64_t *t, const float acc[3], const float gyro[3], float secs, float a_g[3], float w[3]) {
    int n = (int)(secs * 200 + 0.5f);
    for (int i = 0; i < n; i++) { *t += DT; pad_motion_feed(c, acc, gyro, *t, a_g, w); }
}

int main(void) {
    pad_motion_cal c; pad_motion_cal_reset(&c);
    uint64_t t = 1000000000ull; float a[3], w[3];
    const float flat[3] = {0, 0, 9.80665f};   // Android: +g up on z, flat and face up

    pad_motion_feed(&c, flat, (float[3]){0, 0, 0}, t, a, w);
    CHECK(near(a[2], -1, 1e-5f) && near(a[0], 0, 1e-5f), "specific force +9.81 on z is gravity (0, 0, -1) G");
    pad_motion_feed(&c, (float[3]){9.80665f, 0, 0}, (float[3]){0, 0, 0}, t + DT, a, w);
    CHECK(near(a[0], -1, 1e-5f), "on its side the sign follows");

    // a gyro with a constant offset, still: the offset is found after HOLD_NS and the output goes to zero
    pad_motion_cal_reset(&c); t = 1000000000ull;
    const float off[3] = {0.012f, -0.020f, 0.008f};
    run(&c, &t, flat, off, 0.2f, a, w);
    CHECK(near(w[0], 0.012f, 1e-5f), "before the wait the offset is still on");
    run(&c, &t, flat, off, 0.5f, a, w);
    CHECK(near(w[0], 0, 1e-4f) && near(w[1], 0, 1e-4f) && near(w[2], 0, 1e-4f), "after the wait a still controller reads zero");
    CHECK(c.have_bias && near(c.bias[1], -0.020f, 1e-5f), "the offset is the mean rate at rest");

    // turning: the offset stays, the rate comes out without it
    const float turn[3] = {0, 1.5f, 0};
    run(&c, &t, flat, turn, 1.0f, a, w);
    CHECK(near(w[1], 1.5f + 0.020f, 1e-4f), "a real turn keeps its size (offset only subtracted)");
    CHECK(near(c.bias[1], -0.020f, 1e-5f), "the offset does not follow a turn");

    // a tremor over the threshold never trains the offset
    pad_motion_cal_reset(&c); t = 1000000000ull;
    for (int i = 0; i < 400; i++) { t += DT; float g[3] = {0, (i & 1) ? 0.1f : -0.1f, 0}; pad_motion_feed(&c, flat, g, t, a, w); }
    CHECK(!c.have_bias, "a shaky hand does not set an offset");

    // not 1 G (a throw, a hit): not still, even with a quiet gyro
    pad_motion_cal_reset(&c); t = 1000000000ull;
    run(&c, &t, (float[3]){0, 0, 20}, off, 1.0f, a, w);
    CHECK(!c.have_bias, "acceleration far from 1 G is not rest");

    // a gap in the samples restarts the wait
    pad_motion_cal_reset(&c); t = 1000000000ull;
    run(&c, &t, flat, off, 0.3f, a, w);
    t += 300000000ull;
    run(&c, &t, flat, off, 0.3f, a, w);
    CHECK(!c.have_bias, "a gap restarts the window");

    // NaN: no offset, no crash
    pad_motion_cal_reset(&c); t = 1000000000ull;
    float nanv[3] = {NAN, 0, 0};
    run(&c, &t, flat, nanv, 1.0f, a, w);
    CHECK(!c.have_bias, "NaN is not rest");

    // a second offset moves half way
    pad_motion_cal_reset(&c); t = 1000000000ull;
    run(&c, &t, flat, (float[3]){0.01f, 0, 0}, 0.6f, a, w);
    run(&c, &t, flat, (float[3]){0.03f, 0, 0}, 0.6f, a, w);
    CHECK(c.bias[0] > 0.01f && c.bias[0] < 0.03f, "a later offset blends in");

    printf("t_pad_motion: %d checks, %d failed\n", checks, fails);
    return fails != 0;
}
