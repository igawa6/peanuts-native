// PEANUTS: the panel's refresh grid (dm/gfx/vk_android/vka_panel.h): the period estimate from actual present times (jitter, frames held for several refreshes,
// readings off the grid, pauses, a mode change, a period that jumps), a pacing period as whole refreshes, and the chain of desired present times
#include <stdio.h>
#include <stdint.h>
#include "../../gfx/vk_android/vka_panel.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static uint32_t g_seed = 12345;
static uint32_t rnd(void) { g_seed = g_seed * 1664525u + 1013904223u; return g_seed >> 8; }
static int64_t jitter(int64_t max) { return (int64_t)(rnd() % (uint32_t)(2 * max + 1)) - max; }

enum { THOR = 16714000, SIXTY = 16666666 };   // an AYN Thor's panel (59.83 Hz) and the driver's round figure

// frames presented on a grid of period r from t on, each held for 1 to 3 refreshes, read with +-30 us of jitter; returns the grid time reached
static uint64_t run(vkpanel *p, uint64_t t, uint64_t r, int frames, int *lost) {
    for (int i = 0; i < frames; i++) {
        uint32_t x = rnd() % 16; t += r * (x < 12 ? 1 : x < 15 ? 2 : 3);
        int l = vkpanel_sample(p, (uint64_t)((int64_t)t + jitter(30000)));
        if (lost) *lost += l;
    }
    return t;
}

// PEANUTS: exercise the compositor's actual queue deadline, including a producer stall and metadata-free fallback.
static void deadline_grid_cases(uint64_t r, uint64_t deadline, const char *mode) {
    const uint64_t anchor = 11000000000ull, period = vkpanel_fit(33333333, r);
    const uint64_t hold = vkpanel_hold(33333333, r);
    uint64_t now = anchor + 1000000;
    vkgrid old = {0}, aware = {0}; int late;
    uint64_t target = vkgrid_next(&old, anchor, r, 33333333, now, &late);
    CHECK(target > now && target < now + deadline, "%s: the old future target misses the compositor deadline", mode);
    target = vkgrid_next_deadline(&aware, anchor, r, 33333333, now, deadline, &late);
    CHECK(target >= now + deadline && !late && (target - anchor) % r == 0,
          "%s: the first reachable target is on the measured grid", mode);

    aware.last = 0;
    uint64_t prev = 0; int wrong_hold = 0, early = 0, nlate = 0;
    for (int i = 0; i < 2000; i++) {
        target = vkgrid_next_deadline(&aware, anchor, r, 33333333, now, deadline, &late);
        wrong_hold += prev && target - prev != hold * r;
        early += target < now + deadline;
        nlate += late; prev = target; now += period;
    }
    CHECK(!wrong_hold && !early && !nlate, "%s: 2000 steady frames: %d wrong holds, %d too early, %d late",
          mode, wrong_hold, early, nlate);

    aware.last = 0; prev = 0; now = anchor + 1000000;
    wrong_hold = early = nlate = 0;
    for (int i = 0; i < 2000; i++) {
        // One delayed producer reaches QueuePresent just after the next target's deadline.
        // The producer then recovers its old cadence; presentation must retain the new phase.
        uint64_t ready = i == 1000 ? prev + hold * r - deadline + 1 : now;
        target = vkgrid_next_deadline(&aware, anchor, r, 33333333, ready, deadline, &late);
        if (i == 1000) CHECK(late && target - prev == (hold + 1) * r,
                             "%s: missed deadline moves one refresh once", mode);
        else wrong_hold += prev && target - prev != hold * r;
        early += target < ready + deadline;
        nlate += late; prev = target; now += period;
    }
    CHECK(nlate == 1 && !wrong_hold && !early, "%s: producer delay: %d late, %d short/long catch-up holds, %d too early",
          mode, nlate, wrong_hold, early);

    old.last = aware.last = 0; now = anchor + 1000000;
    for (int i = 0; i < 2000; i++) {
        if (i == 1000) now += 7 * r;
        int old_late, new_late;
        uint64_t fallback = vkgrid_next(&old, anchor, r, 33333333, now, &old_late);
        target = vkgrid_next_deadline(&aware, anchor, r, 33333333, now, 0, &new_late);
        CHECK(target == fallback && aware.last == old.last && new_late == old_late,
              "%s: zero deadline preserves fallback at frame %d", mode, i);
        now += period;
    }
}

int main(void) {
    // ---- the estimate ----
    vkpanel p = {0}; int lost = 0;
    CHECK(vkpanel_sample(&p, 1000000000) == 0 && !p.period && !p.last, "no seed: nothing is measured");
    vkpanel_seed(&p, SIXTY);
    uint64_t t = run(&p, 5000000000ull, THOR, 12, &lost);
    CHECK(!p.period, "under 32 refreshes: no estimate yet (%llu)", (unsigned long long)p.period);
    t = run(&p, t, THOR, 30, &lost);
    CHECK(p.period && vkpn_absdiff(p.period, THOR) < 5000, "first estimate within 5 us: %llu", (unsigned long long)p.period);
    t = run(&p, t, THOR, 600, &lost);
    CHECK(vkpn_absdiff(p.period, THOR) < 500 && !lost && p.blocks >= 2, "after blocks within 0.5 us: %llu, %u blocks, lost %d", (unsigned long long)p.period, p.blocks, lost);
    CHECK(vkpn_absdiff(p.last, t) <= 30000, "the phase is the newest reading");
    vkpanel_seed(&p, SIXTY + 20000);
    CHECK(p.period, "the driver's figure wandering under 1 %% keeps the estimate");

    // readings off the grid (a few, not in a row long enough) and repeats are left out
    uint64_t was = p.period;
    for (int i = 0; i < 5; i++) { lost += vkpanel_sample(&p, p.last + 5000000 + (uint64_t)i * 100); lost += vkpanel_sample(&p, p.last); }
    t = run(&p, t, THOR, 300, &lost);
    CHECK(!lost && vkpn_absdiff(p.period, was) < 500, "5 readings off the grid: %llu → %llu, lost %d", (unsigned long long)was, (unsigned long long)p.period, lost);

    // a pause of 2 s (not a whole number of refreshes): the estimate stays, the phase follows
    t += 2000000000ull + 4321000;
    CHECK(vkpanel_sample(&p, t) == 0 && p.period && p.last == t, "pause");
    t = run(&p, t, THOR, 300, &lost);
    CHECK(!lost && vkpn_absdiff(p.period, THOR) < 500, "after the pause: %llu", (unsigned long long)p.period);

    // the panel goes to 119.66 Hz on its own: off the old grid, the estimate is dropped within a few frames, and the driver's new figure starts a new one
    lost = 0; { uint64_t r = THOR / 2, t2 = t + 3000000; for (int i = 0; i < 12 && !lost; i++) { t2 += r; lost += vkpanel_sample(&p, t2); } t = t2; }
    CHECK(lost == 1 && !p.period && p.resets == 1, "mode change: lost %d, period %llu", lost, (unsigned long long)p.period);
    vkpanel_seed(&p, SIXTY / 2); lost = 0;
    t = run(&p, t, THOR / 2, 400, &lost);
    CHECK(!lost && vkpn_absdiff(p.period, THOR / 2) < 500, "120 Hz: %llu", (unsigned long long)p.period);

    // a period that moves by 0.8 % passes each reading but not the block: dropped, then measured again
    vkpanel q = {0}; vkpanel_seed(&q, SIXTY); lost = 0;
    t = run(&q, 1000000000, THOR, 400, &lost);
    CHECK(!lost && q.period, "before the jump");
    t = run(&q, t, THOR + 134000, 400, &lost);
    CHECK(lost == 1, "a jump of 0.8 %% drops the estimate once: %d", lost);
    t = run(&q, t, THOR + 134000, 400, &lost);
    CHECK(lost == 1 && vkpn_absdiff(q.period, THOR + 134000) < 500, "measured again after the jump: %llu", (unsigned long long)q.period);

    // implausible: 250 Hz and 30 Hz figures are not taken
    vkpanel z = {0}; vkpanel_seed(&z, 4000000); run(&z, 1000000000, 4000000, 200, NULL);
    CHECK(!z.seed && !z.period, "4 ms");
    vkpanel_seed(&z, 33333333); run(&z, 1000000000, 33333333, 200, NULL);
    CHECK(!z.seed && !z.period, "33 ms");
    vkpanel_seed(&z, SIXTY); run(&z, 1000000000, THOR, 100, NULL); CHECK(z.period, "a good seed after bad ones");
    vkpanel_seed(&z, 2000000); CHECK(!z.period && !z.seed, "a bad seed drops the estimate");

    // ---- a pacing period as whole refreshes ----
    CHECK(vkpanel_fit(SIXTY, THOR) == THOR && vkpanel_fit(33333333, THOR) == 2 * THOR && vkpanel_fit(50000000, THOR) == 3 * THOR, "60 / 30 / 20 on 59.83 Hz");
    CHECK(vkpanel_fit(SIXTY, THOR / 2) == THOR && vkpanel_fit(8333333, THOR / 2) == THOR / 2 && vkpanel_fit(25000000, THOR / 2) == 3 * (THOR / 2), "60 / 120 / 40 on 119.66 Hz");
    CHECK(vkpanel_fit(22222222, THOR) == 22222222 && vkpanel_fit(25000000, THOR) == 25000000, "45 and 40 on 59.83 Hz stay");
    CHECK(vkpanel_fit(SIXTY, 11111111) == SIXTY && vkpanel_fit(8333333, THOR) == 8333333, "60 on 90 Hz, 120 on 60 Hz stay");
    CHECK(vkpanel_fit(SIXTY, 0) == SIXTY && vkpanel_fit(0, THOR) == 0, "nothing measured, no pacing");
    // PEANUTS: nominal display modes can differ from the measured panel by more than 1 %.
    // Thor's 120 Hz mode measures 8.4375 ms (118.52 Hz). Producer clocks and the four-refresh
    // present grid must share that period; 33.333 ms against 33.75 ms accumulates phase error.
    { const uint64_t r = 8437500, nominal = 8333333;
      vkpanel measured = {0}; vkpanel_seed(&measured, nominal);
      uint64_t stamp = 1000000000ull;
      for (int i = 0; i < 300; i++) { stamp += 4 * r; vkpanel_sample(&measured, stamp); }
      CHECK(measured.period == r, "nominal 120 Hz measures 118.52 Hz: %llu", (unsigned long long)measured.period);
      CHECK(vkpanel_fit(nominal, r) == r && vkpanel_fit(SIXTY, r) == 2 * r &&
            vkpanel_fit(25000000, r) == 3 * r && vkpanel_fit(33333333, r) == 4 * r,
            "120 / 60 / 40 / 30 Hz producer clocks follow measured 118.52 Hz refreshes");
      vkgrid g = {0}; uint64_t now = stamp + 1000000, prev = 0; int late, bad = 0, nlate = 0;
      const uint64_t producer = vkpanel_fit(33333333, measured.period);
      for (int i = 0; i < 2000; i++) {
          uint64_t tg = vkgrid_next(&g, stamp, r, 33333333, now, &late);
          if (prev && tg - prev != 4 * r) bad++;
          nlate += late; prev = tg; now += producer;
      }
      CHECK(!bad && !nlate, "30 Hz producer and grid keep one phase over 2000 frames: %d wrong holds, %d late", bad, nlate);
    }
    CHECK(vkpanel_fit(10000000, 10200000) == 10200000 && vkpanel_fit(10000000, 9800000) == 9800000, "2 %% fit boundary in both directions");
    CHECK(vkpanel_fit(10000000, 10200001) == 10000000 && vkpanel_fit(10000000, 9799999) == 10000000, "outside 2 %% retains the requested clock");
    CHECK(vkpanel_fit(SIXTY, 16000000) == SIXTY, "60 on a 62.5 Hz panel stays");

    // ---- the grid ----
    CHECK(vkpanel_near(1000, 100, 1049) == 1000 && vkpanel_near(1000, 100, 1050) == 1100 && vkpanel_near(1000, 100, 940) == 900 && vkpanel_near(1000, 100, 951) == 1000, "nearest");
    CHECK(vkpanel_up(1000, 100, 1000) == 1000 && vkpanel_up(1000, 100, 1001) == 1100 && vkpanel_up(1000, 100, 999) == 1000 && vkpanel_up(1000, 100, 900) == 900 && vkpanel_up(1000, 100, 899) == 900, "first at or after");
    CHECK(vkpanel_hold(SIXTY, THOR) == 1 && vkpanel_hold(33333333, THOR) == 2 && vkpanel_hold(SIXTY, THOR / 2) == 2 && vkpanel_hold(34000000, THOR) == 2, "holds");
    CHECK(vkpanel_hold(22222222, THOR) == 0 && vkpanel_hold(8333333, THOR) == 0 && vkpanel_hold(SIXTY, 11111111) == 0 && vkpanel_hold(SIXTY, 0) == 0, "no whole hold");

    // ---- desired present times ----
    // 30 fps on the Thor's panel: each frame comes 3 ms after a refresh, the anchor moves on with jitter; every target is two refreshes after the last (the last
    // desired time plus 33.333 ms slipped a refresh every 350 frames), never less than half a refresh ahead
    { vkgrid g = {0}; uint64_t a = 7000000000ull, now = a + 3000000, prev = 0; int late, nlate = 0, bad = 0, early = 0;
      for (int i = 0; i < 2000; i++) {
          uint64_t anchor = a + (uint64_t)(i / 4) * 8 * THOR, seen = (uint64_t)((int64_t)anchor + jitter(30000));   // the newest reading, a few frames old
          uint64_t tg = vkgrid_next(&g, seen, THOR, 33333333, now, &late);
          nlate += late; early += tg < now + THOR / 2;
          if (prev && vkpn_absdiff(tg - prev, 2 * THOR) > 70000) bad++;
          prev = tg; now += 2 * THOR;
      }
      CHECK(!nlate && !bad && !early, "30 fps: %d late, %d not two refreshes apart, %d too early", nlate, bad, early); }
    // 60 fps: one frame comes 1.2 refreshes late: it takes the first grid point it can have, once, and the chain goes on one refresh apart from there
    { vkgrid g = {0}; uint64_t a = 9000000000ull, now = a + 2000000, prev = 0; int late, nlate = 0, bad = 0;
      for (int i = 0; i < 600; i++) {
          if (i == 300) now += THOR * 12 / 10;
          uint64_t tg = vkgrid_next(&g, a, THOR, SIXTY, now, &late);
          nlate += late;
          if (i == 300) CHECK(late && tg == vkpanel_up(a, THOR, now + THOR / 2) && tg - prev >= 2 * THOR, "the late frame: %llu after the last", (unsigned long long)(tg - prev));
          else if (prev && tg - prev != THOR) bad++;
          CHECK(tg >= now + THOR / 2 && (tg - a) % THOR == 0, "frame %d: on the grid and not in the past", i);
          prev = tg; now += THOR;
      }
      CHECK(nlate == 1 && !bad, "60 fps: %d late, %d not one refresh apart", nlate, bad); }
    // a game slower than the panel (16.83 ms a frame on 16.714 ms): one frame in about 144 is held a refresh longer, the others one refresh
    { vkgrid g = {0}; uint64_t a = 1000000000ull, now = a + 1000000, prev = 0; int late, nlate = 0, one = 0, two = 0, other = 0;
      for (int i = 0; i < 3000; i++) {
          uint64_t tg = vkgrid_next(&g, a, THOR, SIXTY, now, &late);
          nlate += late;
          if (prev) { uint64_t d = (tg - prev) / THOR; if (d == 1) one++; else if (d == 2) two++; else other++; }
          prev = tg; now += 16830000;
      }
      CHECK(!other && two >= 19 && two <= 22 && nlate == two, "a slower game: %d at one refresh, %d at two, %d other, %d late", one, two, other, nlate); }
    // a game on the host's 120 Hz tick held to 60 fps by the limiter (the caller passes the limit's period): two refreshes of a 119.66 Hz panel a frame
    { vkgrid g = {0}; uint64_t r = THOR / 2, a = 3000000000ull, now = a + 1000000, prev = 0; int late, bad = 0;
      for (int i = 0; i < 300; i++) { uint64_t tg = vkgrid_next(&g, a, r, SIXTY, now + (uint64_t)(rnd() % 5000000), &late); if (prev && tg - prev != 2 * r) bad++; prev = tg; now += 2 * r; }
      CHECK(bad <= 1, "60 fps on 120 Hz, frames ready up to 5 ms apart: %d not two refreshes apart (one start over at most, then there is room)", bad); }
    // no whole hold, or no anchor: no time, and the chain ends
    { vkgrid g = {0}; int late; uint64_t a = 1000000000ull;
      CHECK(vkgrid_next(&g, a, THOR, SIXTY, a + 1000, &late) && g.last, "a start");
      CHECK(vkgrid_next(&g, a, THOR, 22222222, a + 20000000, &late) == 0 && !g.last && !late, "45 fps on 60 Hz");
      CHECK(vkgrid_next(&g, 0, THOR, SIXTY, a, &late) == 0, "no anchor");
      // the clock far behind the chain (a stale chain after a long stop is over eight holds ahead of what a frame can have): starts again
      uint64_t tg = vkgrid_next(&g, a, THOR, SIXTY, a + 1000, &late); g.last = tg + 100 * THOR;
      CHECK(vkgrid_next(&g, a, THOR, SIXTY, a + THOR, &late) == vkpanel_up(a, THOR, a + THOR + THOR / 2) && late, "a chain too far ahead starts again"); }

    // PEANUTS: the measured Thor grids and Android-reported queue deadlines at nominal 120/60 Hz.
    deadline_grid_cases(8437500, 11333333, "Thor 118.52 Hz");
    deadline_grid_cases(16710000, 16666666, "Thor 59.84 Hz");

    printf(fails ? "t_android_panel: %d FAILED\n" : "t_android_panel: all passed\n", fails);
    return fails != 0;
}
