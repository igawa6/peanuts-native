// PEANUTS(upstream-candidate): pure-C frame-generation pacing math (used by vka_framegen.c and tested by dm/host/tests/t_android_fgpace.c).
// Breaks the positive feedback loop between lsfg_pacer's smoothed_interval, VK_GOOGLE_display_timing hold,
// and FIFO swapchain backpressure: when vkAcquireNextImageKHR blocks while the present thread spent no time
// waiting for the game (neither in the present queue nor on the game's GPU fence), the measured arrival interval
// is bounded by our own display pacing rather than the game's speed, so the pacer relaxes smoothed_interval
// back toward the game's unthrottled cadence.
#pragma once
#include "lsfg/lsfg.h"
#include <math.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    int warm;
    size_t ng;
    int capped;
    int throttled;
    double interval;
    double hold;
} vka_fg_pace_out;

static inline vka_fg_pace_out vka_fg_pace_step(lsfg_pacer *pacer, uint64_t *count, uint32_t *warm_streak,
                                               size_t capacity, int mult, int target, double period, uint64_t rns,
                                               double now, size_t prev_ng, double acq_s, double game_wait_s) {
    vka_fg_pace_out o = {0};
    double acq_thresh = rns ? 0.5 * ((double)rns / 1e9) : 0.004;
    o.throttled = prev_ng > 0 && acq_s >= acq_thresh && game_wait_s < 0.002;
    double raw_iv = pacer->last_frame > 0 ? now - pacer->last_frame : 0.0;
    double deblocked = raw_iv > acq_s ? raw_iv - acq_s : 0.0;
    double cand = period > deblocked ? period : deblocked;
    // In uncapped display-interval mode (decoupled, or <=1.05 refreshes on >100 Hz panels where period is the host tick),
    // never pull smoothed_interval below 2.0 refreshes or generation will flip on/off around 1.9 refreshes (e.g. 61-62 fps / 120 Hz or 31 fps / 60 Hz).
    if (rns && (pacer->decoupled || (rns < 10000000ull && period * 1e9 <= 1.05 * (double)rns)) && period * 1e9 < 1.9 * (double)rns) {
        double floor_iv = 2.0 * (double)rns / 1e9;
        if (cand < floor_iv) cand = floor_iv;
    }
    float sample_iv = o.throttled && cand > 0 ? (float)cand : 0.0f;
    lsfg_plan plan = lsfg_pacer_plan_paced(pacer, capacity, mult, target, now, sample_iv);
    uint64_t c = (*count)++;
    o.warm = plan.warm && c + 1 >= 2;
    *warm_streak = o.warm ? *warm_streak + 1 : 0;
    size_t ng = o.warm && *warm_streak >= 2 ? plan.generations : 0;
    if (ng > capacity) ng = capacity;
    if (ng && rns && period > 0) {
        double gp = pacer->smoothed_interval > period ? pacer->smoothed_interval : period;
        double n = gp * 1e9 / (double)rns;   // refreshes in a game frame (60 fps on 120.46 Hz: 2.01; on 60.00 Hz: 1.00)
        size_t fit = n < 1.9 ? 0 : (size_t)(n + 0.1) - 1;
        if (ng > fit) { ng = fit; o.capped = 1; pacer->issued = ng; }   // the pacer counts what was generated (its unloaded rate is measured on frames without)
    }
    o.ng = ng;
    double interval = pacer->smoothed_interval > 0 ? pacer->smoothed_interval : period;
    if (interval < period) interval = period;
    o.interval = interval;
    if (ng) o.hold = interval / (double)(ng + 1);
    // Snap a hold slightly above a whole number of refreshes down to that whole number (e.g. 34 ms at a 30 fps frame step -> 33.3 ms),
    // and snap up only within 0.02 refresh for off-nominal panels (119.88 / 59.94 Hz) without lengthening shorter intervals (e.g. 1.86 -> 2.0).
    if (ng && rns) {
        double r = o.hold * 1e9 / (double)rns, w = floor(r), n = round(r);
        if (w >= 1.0 && r - w <= 0.15) o.hold = w * (double)rns / 1e9;
        else if (n >= 1.0 && n > r && n - r <= 0.02) o.hold = n * (double)rns / 1e9;
    }
    return o;
}
