// PEANUTS: the audio renderer's output follows the game's pace (peanuts_audiosync.h has the why).
//
// Measuring (the render thread of renderer 0, every call, under g_mu):
//   - game time: each presented frame adds 1 / fps (pn_as_frame, present thread);
//   - the pace q: game time / real time over the last 0.5 s;
//   - the lead e: sound consumed by the renderer minus game time, summed while the pace is the game's (a load, a pause, no frame for 250 ms or a pace outside
//     0.45-1.05 is not, and sets e back to 0: the game shows no timeline then).
// The tempo T = q - e / 1.5 s, within 0.5-1.03, moving at most 30 % a second; outside the game's pace it goes back to 1.
// Playing at T: the pitch part p = T within 0.99-1 goes to a 16-tap windowed-sinc resampler, the rest w = T / p to a WSOLA time-stretch (30 ms sequences,
// 8 ms cross-fades, the best of 10 ms of offsets by normalized cross-correlation). At w = 1 the stretch takes the continuing offset, so its output is its input
// 40 ms later, sample for sample; at p = 1 the resampler passes frames untouched until it first stretches. Each renderer has its own stretch and resampler.
#include "peanuts_audiosync.h"
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define AS_LOG(...) do { flockfile(stderr); fprintf(stderr, "[dm] " __VA_ARGS__); fputc('\n', stderr); funlockfile(stderr); } while (0)

enum { AS_R = 4, RATE = 48000 };
// the resampler
enum { TAPS = 16, HALF = TAPS / 2, PH = 512, RBUF = 8192 };
// the stretch: sequence, cross-fade and search in frames of 48 kHz; its input holds a sequence and the search plus a call's frames, its output a few sequences
enum { WS_S = 1440, WS_O = 384, WS_W = 480, WS_IN = 8192, WS_OUT = 8192 };
// the measurement
#define HIST 11                  // samples of (real, game) time ...
#define SAMPLE_NS 50000000ull    // ... every 50 ms: a 0.5 s window
#define STALE_NS 250000000ull    // no frame for this long: not the game's pace
#define Q_LO 0.45
#define Q_HI 1.05
#define T_LO 0.5
#define T_HI 1.03
#define P_LO 0.99                // the resampler's share of the tempo (pitch)
#define TAU_S 2.0                // the lead is taken back over this long
#define LEAD_RESET_NS 400000000.0   // a lead this large is not drift: start over
#define SLEW_PER_S 0.30

static _Atomic uint64_t g_game_ns, g_last_frame_ns;   // present thread → render threads
static _Atomic int g_on = -1;    // PN_AUDIO_FOLLOW: 0 off, 1 automatic, 2 always (cfg stores it last, after the taps and g_mark)
static pthread_mutex_t g_cfg_mu = PTHREAD_MUTEX_INITIALIZER;   // cfg runs once; its first callers can be the render thread and the game's thread (pn_as_resync) at once
static _Atomic int g_coupled;    // automatic: the game showed that it keeps its sound in step with its frames
static _Atomic uint64_t g_rs_t[1];   // the last resync seen while the game ran slow (pn_as_resync)
static char g_mark[600];         // <DM_TEMP>/audio_follow: the finding, kept for the next starts
static float g_h[PH + 1][TAPS];
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static struct { uint64_t real, game; } g_hist[HIST]; static int g_hn, g_hw;
static uint64_t g_last_sample, g_last_step, g_last_log, g_prev_game;
static double g_t = 1.0, g_q = 1.0, g_e, g_force;
static _Atomic uint64_t g_qs_bits;   // the pace smoothed over about 2 s (for pn_as_resync, which runs on the game's thread)
static double g_qs = 1.0;
static int was_coupled;   // the mode tempo_step last ran in (render threads, under g_mu)
static _Atomic uint64_t g_t_bits;

typedef struct {
    // resampler
    float x[2][RBUF]; int len, active; double pos; int16_t tail[TAPS * 2]; int ntail;
    // stretch
    float in[2][WS_IN]; int ws_on, in_len, have_mid, prev_off, prev_skip; float mid[2][WS_O]; double skip_acc;
    int16_t out[WS_OUT * 2]; int out_len;
} as_t;
static as_t *g_st[AS_R];

static uint64_t mono_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }

void pn_as_frame(uint64_t now_ns, int fps) {
    if (fps <= 0) fps = 60;
    atomic_fetch_add_explicit(&g_game_ns, 1000000000ull / (uint64_t)fps, memory_order_relaxed);
    atomic_store_explicit(&g_last_frame_ns, now_ns, memory_order_relaxed);
}

static double bessel_i0(double x) { double s = 1, t = 1; for (int k = 1; k < 30; k++) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; } return s; }
static void taps_init(void) {
    const double beta = 7.0, pi = 3.14159265358979323846;
    for (int p = 0; p <= PH; p++) {
        double frac = (double)p / PH, sum = 0, h[TAPS];
        for (int j = 0; j < TAPS; j++) {   // tap j reads the frame at floor(pos) + j - (HALF - 1)
            double t = (j - (HALF - 1)) - frac, u = t / HALF;
            double s = fabs(t) < 1e-9 ? 1 : sin(pi * t) / (pi * t);
            double w = fabs(u) >= 1 ? 0 : bessel_i0(beta * sqrt(1 - u * u)) / bessel_i0(beta);
            h[j] = s * w; sum += h[j];
        }
        for (int j = 0; j < TAPS; j++) g_h[p][j] = (float)(h[j] / sum);   // unity gain at DC in every phase
    }
}

static void cfg(void) {
    if (atomic_load_explicit(&g_on, memory_order_acquire) >= 0) return;
    pthread_mutex_lock(&g_cfg_mu);   // a second first caller waits here, not reading the taps while the first one writes them
    if (atomic_load_explicit(&g_on, memory_order_relaxed) >= 0) { pthread_mutex_unlock(&g_cfg_mu); return; }
    const char *e = getenv("PN_AUDIO_FOLLOW");
    int on = e ? atoi(e) : 1;
    if (on < 0 || on > 2) on = 1;
    if (on) taps_init();
    const char *t = getenv("DM_TEMP");
    if (t && *t) { snprintf(g_mark, sizeof g_mark, "%s/audio_follow", t); FILE *f = on == 1 ? fopen(g_mark, "r") : NULL; if (f) { fclose(f); atomic_store(&g_coupled, 1); } }
    AS_LOG("peanuts: the sound follows the game's pace: %s", on == 0 ? "off (PN_AUDIO_FOLLOW=0)" : on == 2 ? "always (PN_AUDIO_FOLLOW=2)"
           : atomic_load(&g_coupled) ? "yes, this game keeps its sound in step with its frames (found on an earlier start: <DM_TEMP>/audio_follow)"
           : "within 1 % until the game shows that it keeps its sound in step with its frames (PN_AUDIO_FOLLOW=1; 0 off, 2 always)");
    atomic_store_explicit(&g_on, on, memory_order_release);
    pthread_mutex_unlock(&g_cfg_mu);
}

static double clampd(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }

// The tempo (caller holds g_mu); consumed_ns: the sound renderer 0 just handed over (0 for the other renderers)
static void tempo_step(uint64_t now, double consumed_ns, int master) {
    if (!g_last_step) { g_last_step = now; g_prev_game = atomic_load_explicit(&g_game_ns, memory_order_relaxed); }
    uint64_t game = atomic_load_explicit(&g_game_ns, memory_order_relaxed);
    if (now - g_last_sample >= SAMPLE_NS) {
        g_last_sample = now;
        g_hist[g_hw].real = now; g_hist[g_hw].game = game;
        g_hw = (g_hw + 1) % HIST; if (g_hn < HIST) g_hn++;
    }
    double target = 1.0; int pace = 0;
    uint64_t lf = atomic_load_explicit(&g_last_frame_ns, memory_order_relaxed);
    if (g_hn == HIST && lf && now - lf < STALE_NS) {
        int newest = (g_hw + HIST - 1) % HIST, oldest = g_hw;
        uint64_t dr = g_hist[newest].real - g_hist[oldest].real, dg = g_hist[newest].game - g_hist[oldest].game;
        g_q = dr ? (double)dg / (double)dr : 1.0;
        pace = g_q >= Q_LO && g_q <= Q_HI;
    } else g_q = 1.0;
    int coupled = g_on == 2 || atomic_load_explicit(&g_coupled, memory_order_relaxed);
    if (coupled != was_coupled) { was_coupled = coupled; g_e = 0; }   // the lead built up while only the inaudible part followed is the game's to resync, not ours to take back
    if (master) {
        if (pace) { g_e += consumed_ns - (double)(game - g_prev_game); if (fabs(g_e) > LEAD_RESET_NS) g_e = 0; }
        else g_e = 0;
        g_prev_game = game;
    }
    if (pace) target = clampd(g_q - clampd(g_e / (TAU_S * 1e9), -0.05, 0.05), T_LO, T_HI);   // the lead is taken back at most 5 % off the game's pace
    if (!coupled) target = clampd(target, P_LO, 1.0);   // not (yet) a game that keeps its sound in step: the inaudible part only, the resampler alone   // automatic, nothing found yet: the inaudible part only
    if (g_force > 0) target = g_force;
    double dt = (now - g_last_step) / 1e9; g_last_step = now;
    if (dt > 0.1) dt = 0.1;
    if (master) { g_qs += ((pace ? g_q : 1.0) - g_qs) * (dt / 2.0); uint64_t qb; memcpy(&qb, &g_qs, 8); atomic_store_explicit(&g_qs_bits, qb, memory_order_relaxed); }
    double step = g_force > 0 ? 1 : SLEW_PER_S * dt;
    g_t = g_t < target ? (g_t + step > target ? target : g_t + step) : (g_t - step < target ? target : g_t - step);
    uint64_t bits; memcpy(&bits, &g_t, 8); atomic_store_explicit(&g_t_bits, bits, memory_order_relaxed);
    if (master && now - g_last_log >= 5000000000ull && (fabs(g_t - 1) > 0.001 || fabs(g_e) > 20e6)) {
        g_last_log = now;
        AS_LOG("peanuts: the sound follows the game's pace: game %.3f of real time over 0.5 s, sound %.0f ms ahead, tempo %.3f", g_q, g_e / 1e6, g_t);
    }
}

// ---- the stretch (WSOLA) ----
static void ws_push(as_t *s, const int16_t *b, int n) {
    if (s->in_len + n > WS_IN) n = WS_IN - s->in_len;   // (never: the input holds a sequence and the search plus a few calls)
    for (int i = 0; i < n; i++) { s->in[0][s->in_len + i] = b[2 * i]; s->in[1][s->in_len + i] = b[2 * i + 1]; }
    s->in_len += n;
}
// The offset in [0, WS_W) whose first WS_O frames match the last sequence's tail best (normalized cross-correlation of L + R)
static int ws_best(const as_t *s) {
    float m[WS_O];
    for (int i = 0; i < WS_O; i++) m[i] = s->mid[0][i] + s->mid[1][i];
    double best = -1e300; int bo = 0;
    for (int o = 0; o < WS_W; o += 2) {   // every other offset first ...
        double c = 0, n = 0;
        for (int i = 0; i < WS_O; i += 2) { float v = s->in[0][o + i] + s->in[1][o + i]; c += m[i] * v; n += v * v; }
        c = c / sqrt(n + 1e-9);
        if (c > best) { best = c; bo = o; }
    }
    int lo = bo > 0 ? bo - 1 : 0, hi = bo + 1 < WS_W ? bo + 1 : bo; best = -1e300; int b2 = bo;
    for (int o = lo; o <= hi; o++) {   // ... then its neighbours, every frame
        double c = 0, n = 0;
        for (int i = 0; i < WS_O; i++) { float v = s->in[0][o + i] + s->in[1][o + i]; c += m[i] * v; n += v * v; }
        c = c / sqrt(n + 1e-9);
        if (c > best) { best = c; b2 = o; }
    }
    return b2;
}
static int16_t s16(float v) { long l = lrintf(v); return (int16_t)(l > 32767 ? 32767 : l < -32768 ? -32768 : l); }
static void ws_run(as_t *s, double w) {
    while (s->in_len >= WS_W + WS_S && s->out_len + (WS_S - WS_O) <= WS_OUT) {
        int off, cont = s->prev_off + (WS_S - WS_O) - s->prev_skip;
        if (!s->have_mid) off = 0;
        else if (w == 1.0 && cont >= 0 && cont < WS_W) off = cont;   // the continuing offset: the output is the input, sample for sample
        else off = ws_best(s);
        int16_t *o = s->out + 2 * s->out_len;
        int i = 0;
        if (s->have_mid) for (; i < WS_O; i++) {   // cross-fade from the last sequence's tail
            float a = (float)i / WS_O;
            o[2 * i] = s16(s->mid[0][i] * (1 - a) + s->in[0][off + i] * a);
            o[2 * i + 1] = s16(s->mid[1][i] * (1 - a) + s->in[1][off + i] * a);
        }
        for (; i < WS_S - WS_O; i++) { o[2 * i] = s16(s->in[0][off + i]); o[2 * i + 1] = s16(s->in[1][off + i]); }
        s->out_len += WS_S - WS_O;
        for (int k = 0; k < WS_O; k++) { s->mid[0][k] = s->in[0][off + WS_S - WS_O + k]; s->mid[1][k] = s->in[1][off + WS_S - WS_O + k]; }
        s->have_mid = 1; s->prev_off = off;
        s->skip_acc += w * (WS_S - WS_O);
        int sk = (int)s->skip_acc; s->skip_acc -= sk;
        if (sk > s->in_len) sk = s->in_len;
        memmove(s->in[0], s->in[0] + sk, (size_t)(s->in_len - sk) * sizeof(float));
        memmove(s->in[1], s->in[1] + sk, (size_t)(s->in_len - sk) * sizeof(float));
        s->in_len -= sk; s->prev_skip = sk;
    }
}

// ---- the resampler (in place; buf holds n frames and room for cap) ----
static int rs_run(as_t *s, int16_t *buf, int n, int cap, double r) {
    if (!s->active) {
        if (r == 1.0 || n > RBUF - TAPS * 2) {   // passes untouched; its last TAPS frames are kept for a seamless start
            if (n >= TAPS) { memcpy(s->tail, buf + 2 * (n - TAPS), TAPS * 4); s->ntail = TAPS; return n; }
            int keep = s->ntail + n > TAPS ? TAPS - n : s->ntail;
            memmove(s->tail, s->tail + 2 * (s->ntail - keep), (size_t)keep * 4);
            memcpy(s->tail + 2 * keep, buf, (size_t)n * 4); s->ntail = keep + n;
            return n;
        }
        s->active = 1; s->len = TAPS;   // the history is the frames already played (zeros before the first ones); the first new frame is next
        for (int i = 0; i < TAPS; i++) {
            int j = i - (TAPS - s->ntail);
            s->x[0][i] = j >= 0 ? s->tail[2 * j] : 0; s->x[1][i] = j >= 0 ? s->tail[2 * j + 1] : 0;
        }
        s->pos = TAPS;
    }
    if (s->len + n > RBUF) n = RBUF - s->len;
    for (int i = 0; i < n; i++) { s->x[0][s->len + i] = buf[2 * i]; s->x[1][s->len + i] = buf[2 * i + 1]; }
    s->len += n;
    int m = 0;
    while (m < cap) {
        int i = (int)s->pos;
        if (i + HALF >= s->len) break;   // the last tap reads x[i + HALF]
        double fp = (s->pos - i) * PH; int p = (int)fp; float f = (float)(fp - p);
        const float *a = g_h[p], *b = g_h[p + 1], *x0 = &s->x[0][i - (HALF - 1)], *x1 = &s->x[1][i - (HALF - 1)];
        float l = 0, rr = 0;
        for (int j = 0; j < TAPS; j++) { float c = a[j] + f * (b[j] - a[j]); l += c * x0[j]; rr += c * x1[j]; }
        buf[2 * m] = s16(l); buf[2 * m + 1] = s16(rr);
        m++; s->pos += r;
    }
    int k0 = (int)s->pos - (HALF - 1);   // the first frame a later output still reads
    if (k0 > 0) {
        memmove(s->x[0], s->x[0] + k0, (size_t)(s->len - k0) * sizeof(float));
        memmove(s->x[1], s->x[1] + k0, (size_t)(s->len - k0) * sizeof(float));
        s->len -= k0; s->pos -= k0;
    }
    return m;
}

int pn_audio_follow(int index, int16_t *buf, int n, int cap) {
    cfg();
    if (!g_on || index < 0 || index >= AS_R || n < 0) return n;
    as_t *s = g_st[index];
    if (!s && !(s = g_st[index] = calloc(1, sizeof *s))) return n;
    pthread_mutex_lock(&g_mu);
    tempo_step(mono_ns(), n * (1e9 / RATE), index == 0);
    double t = g_t;
    pthread_mutex_unlock(&g_mu);
    double p = clampd(t, P_LO, 1.0), w = t / p;
    if (fabs(w - 1) < 1e-9) w = 1.0;
    if (!s->ws_on && w == 1.0) return rs_run(s, buf, n, cap, p);   // the stretch is not needed yet: no look-ahead, no latency
    if (!s->ws_on && index == 0) { pthread_mutex_lock(&g_mu); g_e -= (WS_W + WS_S) * (1e9 / RATE); pthread_mutex_unlock(&g_mu); }   // its look-ahead is taken, not played
    s->ws_on = 1;   // from its first use on it stays in the chain (40 ms of look-ahead; at a tempo of 1 its output is its input)
    ws_push(s, buf, n);
    ws_run(s, w);
    // What the stretch has ready goes to the resampler, as much as fits `cap` after it (it lengthens by up to 1 / P_LO)
    int k = s->out_len, room = (int)((cap - TAPS) * P_LO);
    if (k > room) k = room;
    memcpy(buf, s->out, (size_t)k * 4);
    memmove(s->out, s->out + 2 * k, (size_t)(s->out_len - k) * 4); s->out_len -= k;
    return rs_run(s, buf, k, cap, p);
}

double pn_as_ratio(void) { uint64_t b = atomic_load_explicit(&g_t_bits, memory_order_relaxed); double r; memcpy(&r, &b, 8); return b ? r : 1.0; }

void pn_as_resync(uint64_t now_ns) {
    cfg();
    if (g_on != 1 || atomic_load(&g_coupled)) return;
    uint64_t qb = atomic_load_explicit(&g_qs_bits, memory_order_relaxed); double q; memcpy(&q, &qb, 8);
    if (!qb || !(q < 0.98)) return;   // the game ran at 98 % of its pace or more over the last 2 s: the inaudible part keeps up, a resync then has another reason
    uint64_t last = atomic_exchange(&g_rs_t[0], now_ns);
    if (!last || now_ns - last > 60000000000ull) return;
    atomic_store(&g_coupled, 1);
    if (g_mark[0]) { FILE *f = fopen(g_mark, "w"); if (f) { fputs("1\n", f); fclose(f); } }
    AS_LOG("peanuts: the game paused and restarted its sound twice within %.1f s while it ran at %.3f of its pace: it keeps its sound in step with its frames, "
           "so the sound follows the game's pace from now on (kept in %s)", (now_ns - last) / 1e9, q, g_mark[0] ? g_mark : "this run only");
}

void pn_as_force(double t) { pthread_mutex_lock(&g_mu); g_force = t > 0 ? t : 0; pthread_mutex_unlock(&g_mu); }

void pn_as_reset(void) {
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < AS_R; i++) { free(g_st[i]); g_st[i] = NULL; }
    g_hn = g_hw = 0; g_last_sample = g_last_step = g_last_log = g_prev_game = 0; g_t = 1.0; g_q = 1.0; g_qs = 1.0; atomic_store(&g_qs_bits, 0); g_e = 0; g_force = 0; was_coupled = 0; g_on = -1; atomic_store(&g_coupled, 0); atomic_store(&g_rs_t[0], 0); g_mark[0] = 0;
    atomic_store(&g_t_bits, 0); atomic_store(&g_game_ns, 0); atomic_store(&g_last_frame_ns, 0);
    pthread_mutex_unlock(&g_mu);
}
