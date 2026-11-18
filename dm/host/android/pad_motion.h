// A controller's own motion sensors (Android InputDevice.getSensorManager: a DualSense's gyroscope and accelerometer) on their way to the host's six-axis sensor.
// Pure C, no host state, so a host build checks it without a phone (tests/t_pad_motion.c).
// In: acceleration in m/s² as the specific force (+9.81 on the axis pointing up at rest, Android's convention) and rotation rate in rad/s, in the controller's own frame.
// Out: acceleration in G as gravity's direction ((0, 0, -1) when the controller lies flat and face up, what dm_motion_set takes) and the rotation rate with its rest offset taken off.
// The rest offset: a gyroscope reads a small constant rate while still (the kernel driver applies the factory calibration; what is left drifts with temperature). While the controller
// is still (rate under STILL_W after the offset, |acceleration| within STILL_G of 1 G) for HOLD_NS, the mean rate over that time becomes the new offset (half way on the next ones),
// so a controller that rests on a table stays calm and one in the hand is not trimmed. A hand's tremor stays over STILL_W, and any sample over it restarts the wait.
#pragma once
#include <math.h>
#include <stdint.h>

#define PAD_MOTION_STILL_W 0.06f     // rad/s (3.4 degrees a second)
#define PAD_MOTION_STILL_G 0.06f     // G
#define PAD_MOTION_HOLD_NS 400000000ull
#define PAD_MOTION_MIN_N 20          // samples in a window (the sensors run at 200 Hz or so)
#define PAD_MOTION_GAP_NS 100000000ull   // a gap between samples this long restarts the window

typedef struct { float bias[3]; double sum[3]; int n, have_bias; uint64_t t0, last; } pad_motion_cal;

static inline void pad_motion_cal_reset(pad_motion_cal *c) { *c = (pad_motion_cal){{0}}; }

// One sample: fills acc_g and w (rad/s, offset taken off). t_ns: any steady clock
static inline void pad_motion_feed(pad_motion_cal *c, const float acc_ms2[3], const float gyro[3], uint64_t t_ns, float acc_g[3], float w[3]) {
    for (int i = 0; i < 3; i++) { acc_g[i] = -acc_ms2[i] / 9.80665f; w[i] = gyro[i] - c->bias[i]; }
    float n = sqrtf(acc_g[0] * acc_g[0] + acc_g[1] * acc_g[1] + acc_g[2] * acc_g[2]);
    float r = sqrtf(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
    int still = isfinite(r) && isfinite(n) && r < PAD_MOTION_STILL_W && fabsf(n - 1.f) < PAD_MOTION_STILL_G;
    if (!still || (c->last && t_ns - c->last > PAD_MOTION_GAP_NS)) { c->n = 0; c->sum[0] = c->sum[1] = c->sum[2] = 0; }
    c->last = t_ns;
    if (!still) return;
    if (!c->n) c->t0 = t_ns;
    for (int i = 0; i < 3; i++) c->sum[i] += gyro[i];   // the raw rate: the offset is the mean of what the sensor says at rest
    c->n++;
    if (c->n >= PAD_MOTION_MIN_N && t_ns - c->t0 >= PAD_MOTION_HOLD_NS) {
        for (int i = 0; i < 3; i++) {
            float m = (float)(c->sum[i] / c->n);
            c->bias[i] = c->have_bias ? c->bias[i] + 0.5f * (m - c->bias[i]) : m;
        }
        c->have_bias = 1; c->n = 0; c->sum[0] = c->sum[1] = c->sum[2] = 0;
        for (int i = 0; i < 3; i++) w[i] = gyro[i] - c->bias[i];   // this sample too
    }
}
