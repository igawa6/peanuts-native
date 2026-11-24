// PEANUTS: the thread rules of peanuts_threads.h
#define _GNU_SOURCE
#include "peanuts_threads.h"
#include <dirent.h>
#include <errno.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#define TLOG(...) __android_log_print(ANDROID_LOG_INFO, "peanuts", __VA_ARGS__)
#else
#define TLOG(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

static int parse_io(const char *s, int *cls, int *lvl) {
    if (!strcmp(s, "idle")) { *cls = 3; *lvl = 0; return 1; }
    if ((s[0] == 'b' && s[1] == 'e') || (s[0] == 'r' && s[1] == 't')) {
        if (s[2] < '0' || s[2] > '7' || s[3]) return 0;
        *cls = s[0] == 'b' ? 2 : 1; *lvl = s[2] - '0'; return 1;
    }
    return 0;
}

// One rule, text in place (the caller's copy), 1 when it is right
static int parse_rule(char *t, pn_thr_rule *r) {
    memset(r, 0, sizeof *r);
    char *f[4] = { 0 }; int nf = 0;
    for (char *p = t; nf < 4; ) { f[nf++] = p; char *c = strchr(p, ':'); if (!c) break; *c = 0; p = c + 1; if (nf == 4 && strchr(p, ':')) return 0; }
    if (nf > 4 || (nf == 4 && strchr(f[3], ':'))) return 0;
    if (!f[0][0] || strlen(f[0]) > 15) return 0;
    strcpy(r->prefix, f[0]);
    if (nf >= 2 && f[1][0]) {
        char *e; errno = 0; unsigned long long m = strtoull(f[1], &e, 0);
        if (*e || errno || !m || f[1][0] == '-') return 0;
        r->has_mask = 1; r->mask = m;
    }
    if (nf >= 3 && f[2][0]) {
        char *e; long v = strtol(f[2], &e, 10);
        if (*e || v < -20 || v > 19) return 0;
        r->has_nice = 1; r->nice = (int)v;
    }
    if (nf >= 4 && f[3][0]) { if (!parse_io(f[3], &r->io_class, &r->io_level)) return 0; r->has_io = 1; }
    return r->has_mask || r->has_nice || r->has_io;   // a rule that does nothing is a mistake
}

int pn_thr_parse(const char *spec, pn_thr_rule *out, int max, int *bad) {
    if (bad) *bad = 0;
    if (!spec || !out || max <= 0) return 0;
    char buf[512]; strncpy(buf, spec, sizeof buf - 1); buf[sizeof buf - 1] = 0;
    int n = 0;
    for (char *p = buf; *p;) {
        char *e = strchr(p, ';'); if (e) *e = 0;
        while (*p == ' ') p++;
        char *end = p + strlen(p); while (end > p && end[-1] == ' ') *--end = 0;
        if (*p) { pn_thr_rule r; if (n < max && parse_rule(p, &r)) out[n++] = r; else if (bad) (*bad)++; }
        if (!e) break;
        p = e + 1;
    }
    return n;
}

const pn_thr_rule *pn_thr_match(const pn_thr_rule *rules, int n, const char *comm) {
    if (!rules || !comm) return NULL;
    size_t cl = strlen(comm); while (cl && (comm[cl - 1] == '\n' || comm[cl - 1] == '\r')) cl--;
    for (int i = 0; i < n; i++) { size_t pl = strlen(rules[i].prefix); if (pl && pl <= cl && !strncmp(comm, rules[i].prefix, pl)) return &rules[i]; }
    return NULL;
}

#define DONE_MAX 512
static int g_done[DONE_MAX]; static int g_ndone;   // the thread ids already done (one caller at a time: the present thread's tick, or a test)
static int g_logged;

static int is_done(int tid) { for (int i = 0; i < g_ndone; i++) if (g_done[i] == tid) return 1; return 0; }
static void forget(int tid) { for (int i = 0; i < g_ndone; i++) if (g_done[i] == tid) { g_done[i] = g_done[--g_ndone]; return; } }

static void apply(int tid, const char *comm, const pn_thr_rule *r) {
    char what[160]; int w = 0; w += snprintf(what + w, sizeof what - w, "%s (tid %d):", comm, tid);
    if (r->has_mask) {
        cpu_set_t cs; CPU_ZERO(&cs); for (int c = 0; c < 64 && c < CPU_SETSIZE; c++) if (r->mask >> c & 1) CPU_SET(c, &cs);
        int rc = sched_setaffinity(tid, sizeof cs, &cs);
        w += snprintf(what + w, sizeof what - w, " cpus %#llx%s", (unsigned long long)r->mask, rc ? " FAILED" : "");
        if (rc) w += snprintf(what + w, sizeof what - w, "(errno %d)", errno);
    }
    if (r->has_nice) {
        int rc = setpriority(PRIO_PROCESS, (id_t)tid, r->nice);
        w += snprintf(what + w, sizeof what - w, " nice %d%s", r->nice, rc ? " FAILED" : "");
        if (rc) w += snprintf(what + w, sizeof what - w, "(errno %d)", errno);
    }
#ifdef SYS_ioprio_set
    if (r->has_io) {
        long rc = syscall(SYS_ioprio_set, 1 /* IOPRIO_WHO_PROCESS: a thread id */, tid, (r->io_class << 13) | r->io_level);
        w += snprintf(what + w, sizeof what - w, " io %d/%d%s", r->io_class, r->io_level, rc ? " FAILED" : "");
        if (rc) w += snprintf(what + w, sizeof what - w, "(errno %d)", errno);
    }
#endif
    if (g_logged++ < 40) TLOG("[threads] %s", what);
}

int pn_thr_scan(const pn_thr_rule *rules, int n) {
    if (!rules || n <= 0) return 0;
    DIR *d = opendir("/proc/self/task"); if (!d) return 0;
    int did = 0; struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        int tid = atoi(e->d_name); if (tid <= 0) continue;
        char path[64], comm[32] = ""; snprintf(path, sizeof path, "/proc/self/task/%d/comm", tid);
        FILE *f = fopen(path, "r"); if (!f) continue;
        if (!fgets(comm, sizeof comm, f)) comm[0] = 0;
        fclose(f);
        size_t cl = strlen(comm); while (cl && (comm[cl - 1] == '\n' || comm[cl - 1] == '\r')) comm[--cl] = 0;
        const pn_thr_rule *r = pn_thr_match(rules, n, comm);
        if (!r) { forget(tid); continue; }
        if (is_done(tid)) continue;
        apply(tid, comm, r);
        if (g_ndone < DONE_MAX) g_done[g_ndone++] = tid;
        did++;
    }
    closedir(d);
    return did;
}

static pn_thr_rule g_rules[PN_THR_MAX_RULES]; static int g_nrules; static int g_inited; static long long g_next_ns;

void pn_thr_tick(void) {
    if (g_inited == 2) return;
    if (!g_inited) {
        g_inited = 1;
        const char *e = getenv("DM_THREADS");
        if (!e || !*e) { g_inited = 2; return; }
        int bad; g_nrules = pn_thr_parse(e, g_rules, PN_THR_MAX_RULES, &bad);
        TLOG("[threads] DM_THREADS: %d rule%s%s", g_nrules, g_nrules == 1 ? "" : "s", bad ? " (and some that were wrong and left out)" : "");
        if (!g_nrules) { g_inited = 2; return; }
    }
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    long long now = ts.tv_sec * 1000000000ll + ts.tv_nsec;
    if (now < g_next_ns) return;
    g_next_ns = now + 1000000000ll;   // about once a second: a thread named a moment after it starts is met by the next scan
    pn_thr_scan(g_rules, g_nrules);
}
