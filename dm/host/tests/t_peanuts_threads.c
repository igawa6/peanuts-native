// PEANUTS: the thread rules (dm/peanuts/peanuts_threads.c), run on a Linux PC: the parsing of the spec (every field, every kind of mistake), the matching by name start and the
// order of the rules, and a live scan of this process: a thread given a name, a mask and a nice value that the kernel then reports back.
#include "../../peanuts/peanuts_threads.c"
#include <pthread.h>
#include <sys/prctl.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }

static volatile int g_tid, g_stop;
static void *worker(void *a) { (void)a; pthread_setname_np(pthread_self(), "pn-test-thr"); g_tid = (int)syscall(SYS_gettid); while (!g_stop) usleep(2000); return NULL; }

int main(void) {
    pn_thr_rule r[PN_THR_MAX_RULES]; int bad;
    int n = pn_thr_parse("vk-prewarm:0x0f:10;vk-replay:0xf0:-4:be0;vk-up:3::idle", r, 16, &bad);
    check(n == 3 && bad == 0, "parse: three good rules");
    check(!strcmp(r[0].prefix, "vk-prewarm") && r[0].has_mask && r[0].mask == 0x0f && r[0].has_nice && r[0].nice == 10 && !r[0].has_io, "parse: mask in hex, nice, no io");
    check(r[1].mask == 0xf0 && r[1].nice == -4 && r[1].has_io && r[1].io_class == 2 && r[1].io_level == 0, "parse: negative nice, best-effort io level 0");
    check(r[2].mask == 3 && !r[2].has_nice && r[2].has_io && r[2].io_class == 3, "parse: a decimal mask, an empty nice, idle io");
    n = pn_thr_parse("a:1;;  b:2  ; ", r, 16, &bad);
    check(n == 2 && bad == 0 && !strcmp(r[1].prefix, "b"), "parse: empty rules and spaces are skipped");
    n = pn_thr_parse("a:0x10:5:rt7", r, 16, &bad);
    check(n == 1 && r[0].io_class == 1 && r[0].io_level == 7, "parse: realtime level 7");
    n = pn_thr_parse(":1;x;x:;x::;x:0;x:zz;x:-1;x:1:99;x:1:-21;x:1:abc;x:1::be8;x:1::foo;x:1::be;x:1:2:3:4;averyveryverylongname:1;ok:1", r, 16, &bad);
    check(n == 1 && bad == 15 && !strcmp(r[0].prefix, "ok"), "parse: 15 kinds of mistake are counted and left out, the good rule stays");
    n = pn_thr_parse("a:1;b:1;c:1;d:1", r, 2, &bad);
    check(n == 2, "parse: no more rules than the room");
    check(pn_thr_parse(NULL, r, 16, &bad) == 0 && bad == 0 && pn_thr_parse("", r, 16, &bad) == 0 && pn_thr_parse("a:1", NULL, 16, &bad) == 0 && pn_thr_parse("a:1", r, 0, &bad) == 0, "parse: NULL, empty, no room");

    pn_thr_rule m[3]; pn_thr_parse("vk-psoasync:1;vk-pso:2;vk:4", m, 3, &bad);
    check(pn_thr_match(m, 3, "vk-psoasync") == &m[0], "match: the longest listed first wins by order");
    check(pn_thr_match(m, 3, "vk-psopt") == &m[1] && pn_thr_match(m, 3, "vk-replay\n") == &m[2], "match: a name start, a trailing newline ignored");
    check(pn_thr_match(m, 3, "main") == NULL && pn_thr_match(m, 3, "") == NULL && pn_thr_match(m, 3, NULL) == NULL && pn_thr_match(NULL, 3, "vk") == NULL, "match: no rule, empty, NULL");
    check(pn_thr_match(m, 3, "v") == NULL, "match: a name shorter than the prefix does not match");

    // a live scan
    pthread_t t; pthread_create(&t, NULL, worker, NULL);
    for (int i = 0; i < 200 && !g_tid; i++) usleep(2000);
    check(g_tid != 0, "live: the test thread runs");
    cpu_set_t was; CPU_ZERO(&was); sched_getaffinity(0, sizeof was, &was);
    int first = -1; for (int c = 0; c < CPU_SETSIZE; c++) if (CPU_ISSET(c, &was)) { first = c; break; }
    pn_thr_rule lr[1]; char spec[64]; snprintf(spec, sizeof spec, "pn-test:%#x:7", 1u << first);
    pn_thr_parse(spec, lr, 1, &bad);
    int did = pn_thr_scan(lr, 1);
    check(did == 1, "live: one thread done");
    cpu_set_t now; CPU_ZERO(&now); sched_getaffinity(g_tid, sizeof now, &now);
    check(CPU_COUNT(&now) == 1 && CPU_ISSET(first, &now), "live: the thread's CPUs are the one asked for");
    errno = 0; int pr = getpriority(PRIO_PROCESS, (id_t)g_tid);
    check(pr == 7, "live: its nice value is 7");
    check(pn_thr_scan(lr, 1) == 0, "live: a second scan does nothing for a thread that was done");
    pn_thr_rule other[1]; pn_thr_parse("zzz:1:3", other, 1, &bad);
    check(pn_thr_scan(other, 1) == 0, "live: a rule no thread matches does nothing");
    g_stop = 1; pthread_join(t, NULL);
    pn_thr_scan(lr, 1);   // the thread is gone: forgetting it must not break the next scan
    check(1, "live: a scan after the thread ended");
    // the tick with no DM_THREADS does nothing and stays off
    unsetenv("DM_THREADS"); pn_thr_tick(); pn_thr_tick();
    check(g_inited == 2 && g_nrules == 0, "tick: no DM_THREADS: off");

    printf("%s\n", g_fail ? "FAILED" : "all passed");
    return g_fail != 0;
}
