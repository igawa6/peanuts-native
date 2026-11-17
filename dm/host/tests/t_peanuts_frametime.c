// PEANUTS: the ring of the game's presented-frame intervals (dm/peanuts/peanuts_frametime.c), run on a Linux PC: pn_ft_push with given times (the first push only
// remembers, a gap over 1 s is not stored but still moves the previous time), the newest-first window of pn_ft_read, the ring's wrap at PN_FT_CAP, and n = 0.
// The source is included, as the other peanuts tests include theirs; its JNI function is compiled out here (no __ANDROID__).
#include "../../peanuts/peanuts_frametime.c"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }
static int near_ms(float v, float want) { return fabsf(v - want) < 0.05f; }

static float g_out[PN_FT_CAP];

int main(void) {
    // reset: nothing stored, reads give 0
    pn_ft_reset();
    check(pn_ft_read(g_out, 8) == 0, "reset: read gives 0 values");

    // three intervals of 33 ms
    pn_ft_reset();
    pn_ft_push(0); pn_ft_push(33000000ull); pn_ft_push(66000000ull); pn_ft_push(99000000ull);
    int m = pn_ft_read(g_out, PN_FT_CAP);
    check(m == 3, "3 intervals stored after 4 pushes (the first only remembers)");
    check(m == 3 && near_ms(g_out[0], 33.0f) && near_ms(g_out[1], 33.0f) && near_ms(g_out[2], 33.0f), "each is about 33.0 ms");

    // a gap of 2.0 s is not stored, the next 33 ms interval is
    pn_ft_push(2099000000ull); pn_ft_push(2132000000ull);
    m = pn_ft_read(g_out, PN_FT_CAP);
    check(m == 4, "a gap over 1 s is not stored: 4 values");
    check(m == 4 && near_ms(g_out[3], 33.0f), "the newest value after the gap is about 33.0 ms");

    // the newest n only, oldest first
    m = pn_ft_read(g_out, 2);
    check(m == 2 && near_ms(g_out[0], 33.0f) && near_ms(g_out[1], 33.0f), "read with n = 2 gives the newest 2");

    // a gap of exactly 1 s is stored (only over 1 s is dropped)
    pn_ft_reset();
    pn_ft_push(0); pn_ft_push(1000000000ull);
    check(pn_ft_read(g_out, 4) == 1 && near_ms(g_out[0], 1000.0f), "a gap of exactly 1 s is stored");

    // the ring's wrap: 600 pushes 16.666667 ms apart, 599 intervals, the newest 512 read
    pn_ft_reset();
    for (uint64_t i = 0; i < 600; i++) pn_ft_push(i * 16666667ull);
    m = pn_ft_read(g_out, PN_FT_CAP);
    int all = m == PN_FT_CAP;
    for (int i = 0; all && i < m; i++) all = near_ms(g_out[i], 16.67f);
    check(m == PN_FT_CAP, "600 pushes: read(512) gives 512 values");
    check(all, "all 512 are about 16.67 ms");

    // n = 0 and a NULL output
    check(pn_ft_read(g_out, 0) == 0, "read with n = 0 gives 0");
    check(pn_ft_read(NULL, 4) == 0, "read into NULL gives 0");

    // the real clock: two calls to pn_ft_note a few ms apart give one interval, above 1 ms and under 1 s
    pn_ft_reset();
    pn_ft_note(); usleep(5000); pn_ft_note();
    m = pn_ft_read(g_out, 4);
    check(m == 1 && g_out[0] >= 4.0f && g_out[0] < 1000.0f, "pn_ft_note: one interval of a few ms from the clock");

    printf(g_fail ? "FAILED: %d\n" : "all passed\n", g_fail);
    fflush(stdout);
    return g_fail ? 1 : 0;
}
