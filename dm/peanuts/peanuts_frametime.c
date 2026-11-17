// PEANUTS: the time between the game's own presented frames (peanuts_frametime.h). Written on the present thread (pn_ft_note from peanuts_tick), read from
// the UI thread (Bridge.frameTimes over JNI, below). Each entry is one atomic word, stored relaxed; the write counter is stored with release after the entry and
// read with acquire, so a reader sees every entry the counter covers. An entry being overwritten while it is read can tear into an older or newer value: accepted.
#include "peanuts_frametime.h"
#include <stdatomic.h>
#include <time.h>

#define FT_MAX_DELTA_NS 1000000000ull   // a gap over 1 s is a pause, not a frame time

static _Atomic uint32_t g_ft[PN_FT_CAP];   // intervals in microseconds, entry i of the sequence at i % PN_FT_CAP
static _Atomic uint64_t g_w;               // how many intervals were ever stored
static uint64_t g_last_ns;                 // the previous presented frame (present thread only)
static int g_have_last;

void pn_ft_push(uint64_t now_ns) {
    if (!g_have_last) { g_last_ns = now_ns; g_have_last = 1; return; }
    uint64_t last = g_last_ns;
    g_last_ns = now_ns;
    if (now_ns < last || now_ns - last > FT_MAX_DELTA_NS) return;   // a clock that went back, or a pause
    uint64_t us = (now_ns - last + 500) / 1000;   // rounded to the microsecond
    if (us > UINT32_MAX) us = UINT32_MAX;
    uint64_t w = atomic_load_explicit(&g_w, memory_order_relaxed);
    atomic_store_explicit(&g_ft[w % PN_FT_CAP], (uint32_t)us, memory_order_relaxed);
    atomic_store_explicit(&g_w, w + 1, memory_order_release);
}

void pn_ft_note(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    pn_ft_push((uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec);
}

int pn_ft_read(float *out_ms, int n) {
    if (!out_ms || n <= 0) return 0;
    uint64_t w = atomic_load_explicit(&g_w, memory_order_acquire);
    uint64_t stored = w < PN_FT_CAP ? w : PN_FT_CAP;
    uint64_t m = (uint64_t)n < stored ? (uint64_t)n : stored;
    for (uint64_t i = 0; i < m; i++) {
        uint32_t us = atomic_load_explicit(&g_ft[(w - m + i) % PN_FT_CAP], memory_order_relaxed);
        out_ms[i] = (float)us / 1000.0f;
    }
    return (int)m;
}

void pn_ft_reset(void) {
    atomic_store_explicit(&g_w, 0, memory_order_release);
    g_have_last = 0;
}

// ---- JNI: dev.peanuts.Bridge.frameTimes(float[] out) -> the number of values written (oldest first, milliseconds) ----
// Same guard as peanuts_services.c, whose JNI functions this library also holds.
#if defined(__ANDROID__)
#include <jni.h>
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_frameTimes(JNIEnv *env, jclass cls, jfloatArray out) {
    (void)cls;
    if (!out) return 0;
    jsize n = (*env)->GetArrayLength(env, out);
    if (n > PN_FT_CAP) n = PN_FT_CAP;
    float buf[PN_FT_CAP];
    int m = pn_ft_read(buf, (int)n);
    if (m > 0) (*env)->SetFloatArrayRegion(env, out, 0, m, buf);
    return (jint)m;
}
#endif
