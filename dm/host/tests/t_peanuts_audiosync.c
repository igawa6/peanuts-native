// PEANUTS: the sound following the game's pace (dm/peanuts/peanuts_audiosync.c), run on a Linux PC. The source is included, as the other peanuts tests include theirs.
// 1. Fixed tempos (pn_as_force) on a sine: the output's length is the input's / tempo, its pitch stays (only the resampler's 1 % below 0.99), no step larger than
//    the sine's own (no click where the stretch starts, splices or stops).
// 2. A tempo of 1 on noise: the output is the input, sample for sample.
// 3. The automatic mode in real time (about 8 s): a game at 0.85 of its pace keeps the sound within 1 % until two resyncs, then the sound follows the game's pace
//    and <DM_TEMP>/audio_follow is written; the next start follows from the start; a game at its full pace is not taken for one that resyncs.
#include "../../peanuts/peanuts_audiosync.c"
#include <stdio.h>
#include <unistd.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }

static int16_t g_in[48000 * 25], g_out[48000 * 40];

// A sine at hz through `frames` renderer frames at a fixed tempo; length ratio, measured frequency and the largest step
static void sine(double tempo, double hz, int frames, double *len, double *freq, double *step, double *sine_step) {
    pn_as_reset(); setenv("PN_AUDIO_FOLLOW", "2", 1); pn_as_force(tempo);
    double ph = 0, w = 2 * 3.14159265358979 * hz / 48000; long in = 0, out = 0, zc = 0; int16_t last = 0; int have = 0; double mx = 0;
    for (int f = 0; f < frames; f++) {
        int16_t buf[1024 * 2];
        for (int i = 0; i < 240; i++) { int16_t v = (int16_t)lrint(12000 * sin(ph)); ph += w; buf[2 * i] = buf[2 * i + 1] = v; }
        in += 240;
        int m = pn_audio_follow(0, buf, 240, 1024);
        for (int i = 0; i < m; i++) {
            if (have) { if ((last < 0) != (buf[2 * i] < 0)) zc++; double d = fabs((double)buf[2 * i] - last); if (d > mx) mx = d; }
            last = buf[2 * i]; have = 1;
        }
        out += m;
    }
    // the stretch holds its look-ahead (WS_W + WS_S frames) back: count it as given out
    *len = (double)(out + (tempo < P_LO || tempo > 1.0 ? (WS_W + WS_S) / tempo : 0)) / in; *freq = zc / 2.0 / (out / 48000.0); *step = mx; *sine_step = 12000 * w;
    unsetenv("PN_AUDIO_FOLLOW");
}

// Frames presented at `pace` of 60 fps against real time, the renderer called every 5 ms (sleeping)
static uint64_t g_t0, g_shown;
static void play(int frames, double pace) {
    for (int f = 0; f < frames; f++) {
        uint64_t want = (uint64_t)((mono_ns() - g_t0) / 1e9 * 60 * pace);
        if (want < g_shown) g_shown = want;
        while (g_shown < want) { pn_as_frame(mono_ns(), 60); g_shown++; }
        int16_t buf[2048] = {0};
        pn_audio_follow(0, buf, 240, 1024);
        struct timespec ts = {0, 5000000}; nanosleep(&ts, 0);
    }
}
static void start(double pace) { pn_as_reset(); g_t0 = mono_ns(); g_shown = 0; (void)pace; }

int main(void) {
    char what[200]; double len, freq, step, sstep;
    const double tempos[] = {0.995, 0.9, 0.8, 0.5, 1.03};
    for (size_t k = 0; k < sizeof tempos / sizeof *tempos; k++) {
        double t = tempos[k], hz = 440;
        sine(t, hz, 3000, &len, &freq, &step, &sstep);
        double want_pitch = hz * (t < P_LO ? P_LO : t > 1 ? 1 : t);
        snprintf(what, sizeof what, "tempo %.3f: length x%.4f (want x%.4f), %.1f Hz (want %.1f), largest step %.0f (the sine's %.0f)", t, len, 1 / t, freq, want_pitch, step, sstep);
        check(fabs(len - 1 / t) < 0.004 && fabs(freq - want_pitch) < 1.0 && step <= sstep * 1.01, what);
    }

    // a tempo of 1 on noise: sample for sample
    pn_as_reset(); setenv("PN_AUDIO_FOLLOW", "2", 1); pn_as_force(1.0);
    unsigned seed = 1; long ni = 0, no = 0;
    for (int f = 0; f < 2000; f++) {
        int16_t buf[2048];
        for (int i = 0; i < 240; i++) { seed = seed * 1103515245 + 12345; int16_t v = (int16_t)(((seed >> 16) & 0x7fff) - 16384); buf[2 * i] = v; buf[2 * i + 1] = (int16_t)-v; g_in[ni + i] = v; }
        ni += 240;
        int m = pn_audio_follow(0, buf, 240, 1024);
        for (int i = 0; i < m; i++) g_out[no + i] = buf[2 * i];
        no += m;
    }
    long diff = 0; for (long k = 0; k < no; k++) diff += g_out[k] != g_in[k];
    snprintf(what, sizeof what, "tempo 1: %ld of %ld frames differ from the input", diff, no);
    check(no == ni && diff == 0, what);
    unsetenv("PN_AUDIO_FOLLOW");

    // the automatic mode
    char dir[] = "/tmp/t_audiosync_XXXXXX", mark[300];
    if (!mkdtemp(dir)) { perror("mkdtemp"); return 1; }
    setenv("DM_TEMP", dir, 1); snprintf(mark, sizeof mark, "%s/audio_follow", dir);
    start(0.85); play(500, 0.85);
    snprintf(what, sizeof what, "automatic, a game at 0.85 of its pace, no resync yet: tempo %.3f (the inaudible part only, 0.99)", pn_as_ratio());
    check(fabs(pn_as_ratio() - P_LO) < 0.002, what);
    pn_as_resync(mono_ns()); play(100, 0.85); pn_as_resync(mono_ns()); play(500, 0.85);
    snprintf(what, sizeof what, "after two resyncs within 60 s: tempo %.3f follows the game (0.85, within 5 %%), the finding is kept", pn_as_ratio());
    check(pn_as_ratio() < 0.9 && pn_as_ratio() > 0.78 && access(mark, F_OK) == 0, what);
    start(0.85); play(500, 0.85);
    snprintf(what, sizeof what, "the next start follows from the start: tempo %.3f", pn_as_ratio());
    check(pn_as_ratio() < 0.9 && pn_as_ratio() > 0.78, what);
    unlink(mark);
    start(1.0); play(500, 1.0); pn_as_resync(mono_ns()); play(100, 1.0); pn_as_resync(mono_ns()); play(100, 1.0);
    snprintf(what, sizeof what, "a game at its full pace: two resyncs are not taken as the game's (tempo %.3f, no finding kept)", pn_as_ratio());
    check(!atomic_load(&g_coupled) && access(mark, F_OK) != 0 && pn_as_ratio() >= P_LO && pn_as_ratio() <= 1.0, what);
    rmdir(dir);

    printf("%s\n", g_fail ? "FAILED" : "all passed");
    return g_fail != 0;
}
