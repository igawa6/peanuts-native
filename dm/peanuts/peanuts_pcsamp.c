// PEANUTS: the program-counter sampler of peanuts_pcsamp.h
#define _GNU_SOURCE
#include "peanuts_pcsamp.h"
#include <inttypes.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <ucontext.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#define SLOG(...) __android_log_print(ANDROID_LOG_INFO, "peanuts", __VA_ARGS__)
#else
#define SLOG(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

// ---- the pure parts ----
static int cmp_u64(const void *a, const void *b) { uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b; return x < y ? -1 : x > y; }

int pn_pc_top(const uint64_t *pcs, int n, unsigned shift, pn_pc_bin *out, int max_out) {
    if (!pcs || n <= 0 || !out || max_out <= 0 || shift > 63) return 0;
    uint64_t *b = malloc((size_t)n * sizeof *b);
    if (!b) return 0;
    for (int i = 0; i < n; i++) b[i] = pcs[i] >> shift;
    qsort(b, (size_t)n, sizeof *b, cmp_u64);
    int w = 0;   // run-length of the sorted blocks, then the top max_out by insertion into `out` (kept sorted by count desc, block asc)
    for (int i = 0; i < n;) {
        int j = i; while (j < n && b[j] == b[i]) j++;
        pn_pc_bin c = { b[i], (uint32_t)(j - i) };
        int pos = w < max_out ? w : max_out;
        while (pos > 0 && (out[pos - 1].count < c.count)) pos--;   // equal counts keep the lower block first (b is ascending)
        if (pos < max_out) {
            int last = w < max_out ? w : max_out - 1;
            for (int k = last; k > pos; k--) out[k] = out[k - 1];
            out[pos] = c;
            if (w < max_out) w++;
        }
        i = j;
    }
    free(b);
    return w;
}

int pn_maps_find(const char *maps, uint64_t addr, uint64_t *start, uint64_t *end, char *name, size_t cap) {
    if (!maps) return 0;
    const char *p = maps;
    while (*p) {
        const char *e = strchr(p, '\n'); size_t len = e ? (size_t)(e - p) : strlen(p);
        char line[512]; size_t l = len < sizeof line - 1 ? len : sizeof line - 1; memcpy(line, p, l); line[l] = 0;
        unsigned long long s, f; int off = 0;
        if (sscanf(line, "%llx-%llx %*s %*s %*s %*s %n", &s, &f, &off) >= 2 && addr >= s && addr < f) {
            if (start) *start = s;
            if (end) *end = f;
            if (name && cap) { const char *nm = off > 0 && (size_t)off <= l ? line + off : ""; strncpy(name, nm, cap - 1); name[cap - 1] = 0; }
            return 1;
        }
        if (!e) break;
        p = e + 1;
    }
    return 0;
}

// ---- the sampler ----
#define RING (1u << 16)
static uint64_t g_ring[RING];
static _Atomic uint32_t g_head;
static _Atomic int g_started;
static int g_hz, g_dump_s = 10;

static void on_prof(int sig, siginfo_t *si, void *ctx) {
    (void)sig; (void)si;
    uint64_t pc = 0;
#if defined(__aarch64__)
    pc = ((ucontext_t *)ctx)->uc_mcontext.pc;
#elif defined(__x86_64__)
    pc = (uint64_t)((ucontext_t *)ctx)->uc_mcontext.gregs[REG_RIP];
#else
    (void)ctx;
#endif
    uint32_t i = atomic_fetch_add_explicit(&g_head, 1, memory_order_relaxed);
    g_ring[i & (RING - 1)] = pc;
}

static char *slurp(const char *path) {
    FILE *f = fopen(path, "r"); if (!f) return NULL;
    size_t cap = 1 << 16, n = 0; char *b = malloc(cap);
    while (b) { size_t r = fread(b + n, 1, cap - n - 1, f); n += r; if (n < cap - 1) break; cap *= 2; b = realloc(b, cap); }
    fclose(f); if (b) b[n] = 0; return b;
}

static int is_guest(const char *name) { return strstr(name, "nxnative guest") != NULL; }

static void dump(void) {
    uint32_t head = atomic_exchange_explicit(&g_head, 0, memory_order_relaxed);
    int n = head < RING ? (int)head : (int)RING;
    if (n < 20) return;
    uint64_t *pcs = malloc((size_t)n * sizeof *pcs); char *maps = slurp("/proc/self/maps");
    if (!pcs) { free(maps); return; }
    memcpy(pcs, g_ring, (size_t)n * sizeof *pcs);
    int guest = 0, unknown = 0;
    for (int i = 0; i < n; i++) {
        char nm[160]; uint64_t s, e;
        if (!pcs[i] || !pn_maps_find(maps, pcs[i], &s, &e, nm, sizeof nm)) { unknown++; continue; }
        if (is_guest(nm)) guest++;
    }
    SLOG("[pcsamp] %d samples at %d Hz over %d s: %.1f%% in the game's memory, %.1f%% in the rest of the host%s", n, g_hz, g_dump_s, 100.0 * guest / n,
         100.0 * (n - guest - unknown) / n, unknown ? " (the rest: no map)" : "");
    pn_pc_bin top[16]; int k = pn_pc_top(pcs, n, 6, top, 16);
    for (int i = 0; i < k; i++) {
        uint64_t a = top[i].block << 6, s = 0, e = 0; char nm[160] = "";
        if (pn_maps_find(maps, a, &s, &e, nm, sizeof nm)) SLOG("[pcsamp] %5.1f%%  %#" PRIx64 "  %s+%#" PRIx64, 100.0 * top[i].count / n, a, nm[0] ? nm : "[anon]", a - s);
        else SLOG("[pcsamp] %5.1f%%  %#" PRIx64, 100.0 * top[i].count / n, a);
    }
    free(pcs); free(maps);
}

static void *dump_main(void *arg) {
    (void)arg;
    for (;;) { sleep((unsigned)g_dump_s); dump(); }
    return NULL;
}

void pn_pcsamp_start_once(void) {
    if (atomic_load_explicit(&g_started, memory_order_acquire)) return;
    int zero = 0;
    if (!atomic_compare_exchange_strong(&g_started, &zero, 1)) return;
    const char *e = getenv("DM_PC_SAMPLE");
    int hz = e ? atoi(e) : 0;
    if (hz < 20 || hz > 1000) return;
    const char *d = getenv("DM_PC_SAMPLE_DUMP_S");
    if (d && atoi(d) >= 2 && atoi(d) <= 600) g_dump_s = atoi(d);
    g_hz = hz;
    struct sigaction sa; memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = on_prof; sa.sa_flags = SA_SIGINFO | SA_RESTART; sigemptyset(&sa.sa_mask);
    if (sigaction(SIGPROF, &sa, NULL)) { SLOG("[pcsamp] sigaction failed: the sampler is off"); return; }
    pthread_t t; pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    sigset_t all, old; sigfillset(&all); pthread_sigmask(SIG_BLOCK, &all, &old);   // the dump thread takes no SIGPROF
    int rc = pthread_create(&t, &at, dump_main, NULL);
    pthread_sigmask(SIG_SETMASK, &old, NULL);
    if (rc) { SLOG("[pcsamp] could not start the dump thread: the sampler is off"); return; }
    long us = 1000000L / hz;
    struct itimerval it = { { 0, us }, { 0, us } };
    if (setitimer(ITIMER_PROF, &it, NULL)) { SLOG("[pcsamp] setitimer failed"); return; }
    SLOG("[pcsamp] sampling at %d Hz, a report every %d s (DM_PC_SAMPLE)", hz, g_dump_s);
}
