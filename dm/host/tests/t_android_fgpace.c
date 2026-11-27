// PEANUTS(upstream-candidate): frame-generation pacing loop (dm/gfx/vk_android/vka_fg_pace.h + lsfg/lsfg_pacer.c):
// closed-loop simulation of FIFO swapchain + VK_GOOGLE_display_timing desiredPresentTime backpressure. Verifies
// recovery from temporary slowdowns across x2/x3/x4, target-FPS mode, and 60/90/120 Hz panels without breaking
// genuinely GPU-bound, decoupled, or host-capped games.
#include <math.h>
#include <stdio.h>
#include <string.h>
#include "../../gfx/vk_android/vka_fg_pace.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

typedef struct {
    lsfg_pacer pacer;
    uint64_t count;
    uint32_t warm_streak;
    size_t prev_ng;
    double now;
    double prev_fg_t;
    double prev_acq_s;
    double prev_game_wait_s;
    double last_desired;
    double swap_free[4];   // when each swapchain image becomes free after SurfaceFlinger displays it
    int nimg;
    int img_cur;
    vka_fg_pace_out last;
    double last_dt;
} sim_t;

static void sim_init(sim_t *s, int nimg, int decoupled) {
    memset(s, 0, sizeof *s);
    s->pacer.decoupled = decoupled;
    s->pacer.min_base = 30.0f;
    s->nimg = nimg;
    s->now = 10.0;
    for (int i = 0; i < nimg; i++) s->swap_free[i] = 0.0;
}

// Simulate one image going through present_one (vkAcquireNextImageKHR + VK_GOOGLE_display_timing desiredPresentTime).
static void sim_present_one(sim_t *s, double hold_s, double refresh_s, double *acq_acc) {
    double avail = s->swap_free[s->img_cur];
    if (avail > s->now) {
        *acq_acc += avail - s->now;
        s->now = avail;
    }
    double per = hold_s;
    if (refresh_s > 0 && per < refresh_s) per = refresh_s;
    double want = s->last_desired > 0 ? s->last_desired + per : s->now;
    if (want < s->now) want = s->now;
    s->last_desired = want;
    // SurfaceFlinger releases this image after it has been displayed for its hold (quantized to at least 1 refresh).
    s->swap_free[s->img_cur] = want + per;
    s->img_cur = (s->img_cur + 1) % s->nimg;
}

// Advance one game frame: the game needs game_dt seconds from the previous frame's start to produce its next frame.
static void sim_step(sim_t *s, int mult, int target, double period, double refresh_s, double game_dt) {
    double pq_gpu_wait = 0.0;
    if (s->count > 0) {
        double game_ready = s->prev_fg_t + game_dt;
        if (game_ready > s->now) {
            pq_gpu_wait = game_ready - s->now;
            s->now = game_ready;
        }
    }
    double fg_t = s->now;
    s->last_dt = s->count > 0 ? (fg_t - s->prev_fg_t) : 0.0;
    s->prev_fg_t = fg_t;
    uint64_t rns = refresh_s > 0 ? (uint64_t)(refresh_s * 1e9 + 0.5) : 0;
    vka_fg_pace_out o = vka_fg_pace_step(&s->pacer, &s->count, &s->warm_streak, 3, mult, target, period, rns,
                                         fg_t, s->prev_ng, s->prev_acq_s, pq_gpu_wait);
    s->last = o;
    s->prev_ng = o.ng;

    double acq = 0.0;
    for (size_t g = 0; g < o.ng; g++) sim_present_one(s, o.hold, refresh_s, &acq);
    sim_present_one(s, o.ng ? o.hold : period, refresh_s, &acq);

    s->prev_acq_s = acq;
    s->prev_game_wait_s = pq_gpu_wait;
}

static void sim_run(sim_t *s, int frames, int mult, int target, double period, double refresh_s, double game_dt) {
    for (int i = 0; i < frames; i++) sim_step(s, mult, target, period, refresh_s, game_dt);
}

int main(void) {
    const double R120 = 1.0 / 120.0, R90 = 1.0 / 90.0, R60 = 1.0 / 60.0;
    const double P60 = 1.0 / 60.0, P30 = 1.0 / 30.0;

    // 1. 60 fps game on 120 Hz, x2: temporary 30 fps slowdown (photo mode) must recover to 60 fps base / 120 fps output
    {
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 60, 2, 0, P60, R120, P60);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005, "120Hz x2 warmup: ng=%zu hold=%.4f", s.last.ng, s.last.hold);
        sim_run(&s, 45, 2, 0, P60, R120, P30);   // 1.5 s slowdown at 30 fps
        CHECK(s.last.ng == 1 && fabs(s.last.hold - P60) < 0.001, "120Hz x2 slowdown: ng=%zu hold=%.4f", s.last.ng, s.last.hold);
        sim_run(&s, 60, 2, 0, P60, R120, P60);   // slowdown ends
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005 && fabs(s.last_dt - P60) < 0.001,
              "120Hz x2 recovery: ng=%zu hold=%.4f dt=%.4f sm=%.4f", s.last.ng, s.last.hold, s.last_dt, s.pacer.smoothed_interval);
    }

    // 2. 60 fps game on 120 Hz, x3 and x4: slowdown raises fit to 2/3, recovery must drop back to ng=1, 60 fps base
    for (int mult = 3; mult <= 4; mult++) {
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 60, mult, 0, P60, R120, P60);
        CHECK(s.last.ng == 1, "120Hz x%d warmup: ng=%zu", mult, s.last.ng);
        sim_run(&s, 45, mult, 0, P60, R120, P30);
        CHECK(s.last.ng == (size_t)(mult - 1), "120Hz x%d slowdown: ng=%zu", mult, s.last.ng);
        sim_run(&s, 90, mult, 0, P60, R120, P60);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005 && fabs(s.last_dt - P60) < 0.001,
              "120Hz x%d recovery: ng=%zu hold=%.4f dt=%.4f sm=%.4f", mult, s.last.ng, s.last.hold, s.last_dt, s.pacer.smoothed_interval);
    }

    // 3. 60 fps game on 60 Hz panel (or LTPO stuck at 60 Hz), x2: slowdown starts ng=1, recovery must drop back to ng=0, 60 fps
    {
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 60, 2, 0, P60, R60, P60);
        CHECK(s.last.ng == 0 && s.last.capped, "60Hz x2 warmup: ng=%zu capped=%d", s.last.ng, s.last.capped);
        sim_run(&s, 45, 2, 0, P60, R60, P30);
        CHECK(s.last.ng == 1, "60Hz x2 slowdown: ng=%zu", s.last.ng);
        sim_run(&s, 60, 2, 0, P60, R60, P60);
        CHECK(s.last.ng == 0 && fabs(s.last_dt - P60) < 0.001,
              "60Hz x2 recovery: ng=%zu dt=%.4f sm=%.4f", s.last.ng, s.last_dt, s.pacer.smoothed_interval);
    }

    // 4. 60 fps game on 90 Hz panel, x2: slowdown starts ng=1, recovery must return to ng=0, 60 fps
    {
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 60, 2, 0, P60, R90, P60);
        CHECK(s.last.ng == 0 && s.last.capped, "90Hz x2 warmup: ng=%zu capped=%d", s.last.ng, s.last.capped);
        sim_run(&s, 45, 2, 0, P60, R90, P30);
        CHECK(s.last.ng == 1, "90Hz x2 slowdown: ng=%zu", s.last.ng);
        sim_run(&s, 60, 2, 0, P60, R90, P60);
        CHECK(s.last.ng == 0 && fabs(s.last_dt - P60) < 0.001,
              "90Hz x2 recovery: ng=%zu dt=%.4f sm=%.4f", s.last.ng, s.last_dt, s.pacer.smoothed_interval);
    }

    // 5. Target-FPS mode (target=120) on 120 Hz panel: 2.5 s slowdown probes higher limit, recovery returns to ng=1, 60 fps
    {
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 180, 2, 120, P60, R120, P60);   // 3 s: lets pacer probe limit=1
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005, "target 120 warmup: ng=%zu hold=%.4f", s.last.ng, s.last.hold);
        sim_run(&s, 90, 2, 120, P60, R120, P30);    // 3 s slowdown at 30 fps
        sim_run(&s, 120, 2, 120, P60, R120, P60);   // recovery
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005 && fabs(s.last_dt - P60) < 0.001,
              "target 120 recovery: ng=%zu hold=%.4f dt=%.4f sm=%.4f", s.last.ng, s.last.hold, s.last_dt, s.pacer.smoothed_interval);
    }

    // 6. Severe slowdown (15 fps -> 60 fps) on 120 Hz, x2: must not get stuck in burst filter or at 15 fps
    {
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 60, 2, 0, P60, R120, P60);
        sim_run(&s, 20, 2, 0, P60, R120, 1.0 / 15.0);
        sim_run(&s, 90, 2, 0, P60, R120, P60);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005 && fabs(s.last_dt - P60) < 0.001,
              "15->60 recovery: ng=%zu hold=%.4f dt=%.4f sm=%.4f", s.last.ng, s.last.hold, s.last_dt, s.pacer.smoothed_interval);
    }

    // 7. Genuinely GPU-bound / decoupled game (DM_PRESENT_INTERVAL=display, period=8.33 ms on 120 Hz)
    {
        sim_t s; sim_init(&s, 3, 1);
        double p35 = 1.0 / 35.0;
        sim_run(&s, 60, 2, 0, R120, R120, p35);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - p35 / 2.0) < 0.001,
              "decoupled 35fps x2: ng=%zu hold=%.4f want=%.4f", s.last.ng, s.last.hold, p35 / 2.0);
        // Transition to 50 fps: must smoothly speed up to 50 fps -> 100 fps
        double p50 = 1.0 / 50.0;
        sim_run(&s, 60, 2, 0, R120, R120, p50);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - p50 / 2.0) < 0.001 && fabs(s.last_dt - p50) < 0.001,
              "decoupled 35->50fps x2: ng=%zu hold=%.4f dt=%.4f", s.last.ng, s.last.hold, s.last_dt);
    }

    // 8. Genuinely 30 fps decoupled game with x4 on 3-image swapchain (acq_s and game_wait_s both ~16.7 ms): must stay ng=3
    {
        sim_t s; sim_init(&s, 3, 1);
        sim_run(&s, 60, 4, 0, R120, R120, P30);
        CHECK(s.last.ng == 3 && !s.last.throttled && fabs(s.last.hold - R120) < 0.0005,
              "decoupled 30fps x4 3-img: ng=%zu throttled=%d hold=%.4f", s.last.ng, s.last.throttled, s.last.hold);
    }

    // 9. Host FPS cap (30 fps: period=33.33 ms) on 120 Hz and 60 Hz with x2
    {
        sim_t s120, s60; sim_init(&s120, 3, 0); sim_init(&s60, 3, 0);
        sim_run(&s120, 60, 2, 0, P30, R120, P30);
        sim_run(&s60, 60, 2, 0, P30, R60, P30);
        CHECK(s120.last.ng == 1 && fabs(s120.last.hold - P60) < 0.0005, "capped 30fps 120Hz: ng=%zu hold=%.4f", s120.last.ng, s120.last.hold);
        CHECK(s60.last.ng == 1 && fabs(s60.last.hold - P60) < 0.0005, "capped 30fps 60Hz: ng=%zu hold=%.4f", s60.last.ng, s60.last.hold);
    }

    // 10. Downward-only refresh snap: 1.86 refreshes does not snap UP to 2.0; 2.04 refreshes snaps DOWN to 2.0
    {
        lsfg_pacer p = {0}; uint64_t cnt = 0; uint32_t ws = 0;
        double t = 1.0;
        uint64_t r120_ns = (uint64_t)(R120 * 1e9 + 0.5);
        for (int i = 0; i < 30; i++, t += 0.0310) vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 0, P60, r120_ns, t, 1, 0, 0.030);
        vka_fg_pace_out up = vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 0, P60, r120_ns, t, 1, 0, 0.030);
        CHECK(up.hold < 0.0160, "1.86 refreshes must not snap up to 2.0: hold=%.5f", up.hold);

        memset(&p, 0, sizeof p); cnt = 0; ws = 0; t = 1.0;
        for (int i = 0; i < 30; i++, t += 0.0340) vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 0, P30, r120_ns, t, 1, 0, 0.033);
        vka_fg_pace_out dn = vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 0, P30, r120_ns, t, 1, 0, 0.033);
        CHECK(fabs(dn.hold - 2.0 * R120) < 0.0001, "2.04 refreshes must snap down to 2.0: hold=%.5f", dn.hold);
    }

    // 11. 4-image swapchain (typical Android SurfaceFlinger depth), 120 Hz x2 and x4: slowdown -> recovery
    for (int mult = 2; mult <= 4; mult += 2) {
        sim_t s; sim_init(&s, 4, 0);
        sim_run(&s, 60, mult, 0, P60, R120, P60);
        sim_run(&s, 45, mult, 0, P60, R120, P30);
        sim_run(&s, 90, mult, 0, P60, R120, P60);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005 && fabs(s.last_dt - P60) < 0.001,
              "4-img 120Hz x%d recovery: ng=%zu hold=%.4f dt=%.4f sm=%.4f", mult, s.last.ng, s.last.hold, s.last_dt, s.pacer.smoothed_interval);
    }

    // 12. Target-FPS mode (target=120) on a >120 Hz panel (121.41 Hz = 8.236 ms) with a fast display-paced game:
    // must not collapse smoothed_interval below P60 into a 5-frame ng=0 limit cycle (~104 fps).
    {
        const double R121 = 0.008236;
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 180, 2, 120, P60, R121, 0.004);   // 3 s: let pacer probe limit=1 and settle
        int gen_ok = 0;
        for (int i = 0; i < 60; i++) {
            sim_step(&s, 2, 120, P60, R121, 0.004);
            if (s.last.ng == 1) gen_ok++;
        }
        CHECK(gen_ok == 60 && fabs(s.pacer.smoothed_interval - P60) < 0.0002,
              "target 120 on 121.4Hz: gen=%d/60 sm=%.5f", gen_ok, s.pacer.smoothed_interval);
    }

    // 13. Display-interval mode with no cap: must have zero on/off flips at 61.5 fps / 120 Hz x2 and 31 fps / 60 Hz x2
    {
        sim_t s120; sim_init(&s120, 3, 1);
        sim_run(&s120, 90, 2, 0, R120, R120, 1.0 / 61.5);
        int flips120 = 0; size_t prev = s120.last.ng;
        for (int i = 0; i < 300; i++) {
            sim_step(&s120, 2, 0, R120, R120, 1.0 / 61.5);
            if (s120.last.ng != prev) { flips120++; prev = s120.last.ng; }
        }
        CHECK(flips120 == 0 && s120.last.ng == 1, "display 61.5fps 120Hz x2: flips=%d ng=%zu", flips120, s120.last.ng);

        sim_t s60; sim_init(&s60, 3, 1);
        sim_run(&s60, 90, 2, 0, R60, R60, 1.0 / 31.0);
        int flips60 = 0; prev = s60.last.ng;
        for (int i = 0; i < 300; i++) {
            sim_step(&s60, 2, 0, R60, R60, 1.0 / 31.0);
            if (s60.last.ng != prev) { flips60++; prev = s60.last.ng; }
        }
        CHECK(flips60 == 0 && s60.last.ng == 1, "display 31fps 60Hz x2: flips=%d ng=%zu", flips60, s60.last.ng);

        // Display-interval mode 60 -> 30 -> 60 fps slowdown recovery
        sim_t srec; sim_init(&srec, 3, 1);
        sim_run(&srec, 90, 2, 0, R120, R120, P60);
        sim_run(&srec, 45, 2, 0, R120, R120, P30);
        sim_run(&srec, 90, 2, 0, R120, R120, P60);
        CHECK(srec.last.ng == 1 && fabs(srec.last.hold - R120) < 0.0005 && fabs(srec.last_dt - P60) < 0.001,
              "display 60->30->60 recovery: ng=%zu hold=%.4f dt=%.4f", srec.last.ng, srec.last.hold, srec.last_dt);
    }

    // 14. Live FPS cap change (30 -> 60 fps with x2 on 120 Hz)
    {
        sim_t s; sim_init(&s, 3, 0);
        sim_run(&s, 90, 2, 0, P30, R120, P30);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - P60) < 0.0005, "live cap 30fps: ng=%zu hold=%.4f", s.last.ng, s.last.hold);
        sim_run(&s, 90, 2, 0, P60, R120, P60);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005 && fabs(s.last_dt - P60) < 0.001,
              "live cap 30->60fps: ng=%zu hold=%.4f dt=%.4f", s.last.ng, s.last.hold, s.last_dt);
    }

    // 15. Frame-time jitter (+-1.5 ms) on 60 fps / 120 Hz x2 and slowdown recovery
    {
        sim_t s; sim_init(&s, 3, 0);
        for (int i = 0; i < 60; i++) sim_step(&s, 2, 0, P60, R120, P60 + ((i % 3) - 1) * 0.0015);
        CHECK(s.last.ng == 1, "jitter warmup: ng=%zu", s.last.ng);
        for (int i = 0; i < 45; i++) sim_step(&s, 2, 0, P60, R120, P30 + ((i % 3) - 1) * 0.0015);
        for (int i = 0; i < 90; i++) sim_step(&s, 2, 0, P60, R120, P60 + ((i % 3) - 1) * 0.0015);
        CHECK(s.last.ng == 1 && fabs(s.last.hold - R120) < 0.0005 && fabs(s.last_dt - P60) < 0.002,
              "jitter recovery: ng=%zu hold=%.4f dt=%.4f", s.last.ng, s.last.hold, s.last_dt);
    }

    // 16. Off-nominal panels (119.88 Hz and 59.94 Hz): hold snaps to exact whole refresh without ~10 s drift
    {
        const double R11988 = 1001.0 / 120000.0, R5994 = 1001.0 / 60000.0;
        sim_t s1; sim_init(&s1, 3, 0);
        sim_run(&s1, 90, 2, 0, P30, R11988, P30);
        CHECK(s1.last.ng == 1 && fabs(s1.last.hold - 2.0 * R11988) < 1e-6,
              "119.88Hz 30fps x2 snap: ng=%zu hold=%.6f want=%.6f", s1.last.ng, s1.last.hold, 2.0 * R11988);

        sim_t s2; sim_init(&s2, 3, 0);
        sim_run(&s2, 90, 2, 0, P60, R11988, P60);
        CHECK(s2.last.ng == 1 && fabs(s2.last.hold - R11988) < 1e-6,
              "119.88Hz 60fps x2 snap: ng=%zu hold=%.6f want=%.6f", s2.last.ng, s2.last.hold, R11988);

        sim_t s3; sim_init(&s3, 3, 0);
        sim_run(&s3, 90, 2, 0, 1.0 / 15.0, R5994, 1.0 / 15.0);
        CHECK(s3.last.ng == 1 && fabs(s3.last.hold - 2.0 * R5994) < 1e-6,
              "59.94Hz 15fps x2 snap: ng=%zu hold=%.6f want=%.6f", s3.last.ng, s3.last.hold, 2.0 * R5994);
    }

    // 17. prev_ng == 0 guard: throttling detector must not fire when frame generation was not generating
    {
        lsfg_pacer p = {0}; uint64_t cnt = 0; uint32_t ws = 0;
        uint64_t r120_ns = (uint64_t)(R120 * 1e9 + 0.5);
        double t = 1.0;
        for (int i = 0; i < 10; i++, t += P30) vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 0, P60, r120_ns, t, 0, 0.0, P30);
        vka_fg_pace_out o = vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 0, P60, r120_ns, t + P30, 0, 0.016, 0.0);
        CHECK(!o.throttled, "prev_ng==0 must not mark throttled: throttled=%d", o.throttled);
    }

    // 18. Drained-queue burst filter in target-FPS mode: 4 back-to-back presents (0.2 ms apart) must not skew smoothed_interval
    {
        lsfg_pacer p = {0}; uint64_t cnt = 0; uint32_t ws = 0;
        uint64_t r120_ns = (uint64_t)(R120 * 1e9 + 0.5);
        double t = 1.0;
        for (int i = 0; i < 120; i++, t += P30) vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 120, P30, r120_ns, t, 1, 0.0, P30);
        float before = p.smoothed_interval;
        for (int i = 0; i < 4; i++) {
            t += 0.0002;
            vka_fg_pace_step(&p, &cnt, &ws, 3, 2, 120, P30, r120_ns, t, 1, 0.0, 0.0);
        }
        CHECK(fabsf(p.smoothed_interval - before) < 0.0001f,
              "drained-queue burst skewed smoothed_interval: before=%.5f after=%.5f", before, p.smoothed_interval);
    }

    if (fails) { printf("t_android_fgpace: %d failed\n", fails); return 1; }
    printf("t_android_fgpace: ok\n");
    return 0;
}
