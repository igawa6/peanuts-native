// PEANUTS: the host side of the companion runtime (dm/peanuts/peanuts.c) on a Linux PC, with the runtime itself and the rest of the host replaced by stand-ins that
// record what they are asked. peanuts.c is built as it is, as if for Android. What is checked:
//   1. the runtime is made on the tick thread, not on the thread that presents (making it stops the game's threads to patch main: from the presenting thread that stop
//      waited for a thread that waited for the present);
//   2. it ticks 60 times a second whatever the presents do, and not at all while the game is suspended;
//   3. a tick that overruns its period is not followed at once by the next one;
//   4. the page's presence: hidden until its window asks, shown while it asks, hidden when the launcher says so, and a hide that comes while the game is suspended is
//      the runtime's state after the resume, with the next show taken up again (it stuck at hidden);
//   5. the runtime reads the pad through the host each time it asks (no sample before the tick);
//   6. a read of a range the read cache still holds but the game has unmapped fails instead of faulting, and the same address reads again once it is mapped again.
#define _GNU_SOURCE
#include <signal.h>
#include <sys/prctl.h>
#include <sys/uio.h>
#include "../../peanuts/peanuts.c"
// the overlay graph (peanuts_frametime.c, peanuts_graph.c) has tests of its own (t_peanuts_frametime, t_peanuts_graph); built as if for Android those files want jni.h
void pn_ft_note(void) {}
void pn_graph_frame(void) {}
void pn_as_frame(uint64_t now_ns, int fps) { (void)now_ns; (void)fps; }   // PEANUTS: the audio follower (peanuts_audiosync.c) has its own test, t_peanuts_audiosync

// ---- the runtime (rt/peanuts_rt.h): what was asked of it ----
struct PnRt { int unused; };
static struct PnRt g_fake_rt;
static _Atomic int n_create, n_tick, n_present_calls, last_present = -1, n_lifecycle, tick_ms;
static _Atomic uint64_t create_thread, min_gap_ns = UINT64_MAX, last_tick_end;
static PnRtConfig g_cfg;
PnRt *pn_rt_create(const PnRtConfig *c) { g_cfg = *c; atomic_store(&create_thread, (uint64_t)pthread_self()); atomic_fetch_add(&n_create, 1); return &g_fake_rt; }
void pn_rt_destroy(PnRt *rt) { (void)rt; }
void pn_rt_tick(PnRt *rt) {
    (void)rt; uint64_t t = (uint64_t)mono_ns(), e = atomic_load(&last_tick_end);
    if (e && t - e < atomic_load(&min_gap_ns)) atomic_store(&min_gap_ns, t - e);
    if (atomic_load(&tick_ms)) usleep((useconds_t)atomic_load(&tick_ms) * 1000);
    atomic_fetch_add(&n_tick, 1); atomic_store(&last_tick_end, (uint64_t)mono_ns());
}
void pn_rt_set_present(PnRt *rt, int shown) { (void)rt; atomic_store(&last_present, shown); atomic_fetch_add(&n_present_calls, 1); }
void pn_rt_lifecycle(PnRt *rt, int suspended) { (void)rt; (void)suspended; atomic_fetch_add(&n_lifecycle, 1); }
void pn_rt_set_panel(PnRt *rt, uint32_t w, uint32_t h) { (void)rt; (void)w; (void)h; }
void pn_rt_set_companions_disabled(int d) { (void)d; }
void pn_rt_touch(PnRt *rt, uint32_t f, uint32_t x, uint32_t y, int down) { (void)rt; (void)f; (void)x; (void)y; (void)down; }
int pn_rt_pad_gate(int slot, uint64_t *b, int32_t a[4]) { (void)slot; (void)b; (void)a; return 0; }
void pn_rt_pad_state(PnRt *rt, uint32_t npad, uint64_t b, const int32_t a[4]) { (void)rt; (void)npad; (void)b; (void)a; }
void pn_rt_stop_stats(uint64_t *s, uint64_t *f, uint64_t *l) { if (s) *s = 0; if (f) *f = 0; if (l) *l = 0; }
size_t pn_rt_status(PnRt *rt, char *buf, size_t n) { (void)rt; return (size_t)snprintf(buf, n, "fake"); }

// ---- the host (dm.h and friends) ----
static module_t g_one_mod; module_t *g_mod = &g_one_mod; int g_nmod;
static char g_pack[512];
const char *dm_module_dir(void) { return g_pack; }
const char *dm_main_dir(void) { return g_pack; }
void dm_guest_heap_range(uint64_t *base, size_t *open_len) { *base = 0; *open_len = 0; }
void dm_guest_write_prepare(void *addr, size_t len) { (void)addr; (void)len; }
static int (*g_hook)(void *, int, void *);
void dm_fault_hook_register(int (*fn)(void *addr, int is_write, void *uc)) { g_hook = fn; }
int plat_mem_read(uint64_t addr, void *out, size_t len) { struct iovec l = {out, len}, r = {(void *)(uintptr_t)addr, len}; return process_vm_readv(getpid(), &l, 1, &r, 1, 0) == (ssize_t)len ? 0 : -1; }
int plat_mem_write(uint64_t addr, const void *src, size_t len) { struct iovec l = {(void *)src, len}, r = {(void *)(uintptr_t)addr, len}; return process_vm_writev(getpid(), &l, 1, &r, 1, 0) == (ssize_t)len ? 0 : -1; }
uint64_t plat_slot_key_entry(unsigned slot) { (void)slot; return 0x40; }
void *dm_module_arena_tail(size_t size) { (void)size; return NULL; }
void dm_input_set(uint64_t b, int32_t lx, int32_t ly, int32_t rx, int32_t ry) { (void)b; (void)lx; (void)ly; (void)rx; (void)ry; }
static _Atomic uint64_t g_pad_now; static _Atomic int n_pad_raw;
void dm_pad_raw(uint32_t *npad, uint64_t *b, int32_t a[4]) { atomic_fetch_add(&n_pad_raw, 1); if (npad) *npad = 0x20; if (b) *b = atomic_load(&g_pad_now); if (a) a[0] = a[1] = a[2] = a[3] = 0; }
void (*_Atomic dm_pad_gate)(int slot, uint64_t *buttons, int32_t axes[4]);
int dm_guest_stop_all(uint64_t t) { (void)t; return 0; }
void dm_guest_resume_all(void) {}
int dm_guest_stopped_in(uint64_t lo, uint64_t hi) { (void)lo; (void)hi; return 0; }
void dm_guest_code_range(uint64_t lo, uint64_t hi) { (void)lo; (void)hi; }
int pn_nup_is_spec(const char *spec) { (void)spec; return 0; }
pn_nup *pn_nup_open_for(const char *spec, uint64_t t, const char *b, char *why, size_t wn) { (void)spec; (void)t; (void)b; (void)why; (void)wn; return NULL; }
const char *pn_nup_describe(const pn_nup *n) { (void)n; return ""; }
void pn_nup_provider(pn_nup *n, PnRtRomfs *out) { (void)n; (void)out; }

// The host's crash handler as far as a fault goes (crash.c): the hooks first; a fault no hook takes ends the run
static void on_fault(int sig, siginfo_t *si, void *uc) { (void)sig; if (g_hook && g_hook(si->si_addr, 0, uc)) return; static const char m[] = "FAIL an unhandled fault\n"; if (write(1, m, sizeof m - 1)) {} _exit(98); }

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }
static void ms(int n) { usleep((useconds_t)n * 1000); }
static _Atomic int g_poll;   // the page's window: asks for its size every 33 ms while set
static void *window(void *arg) { (void)arg; for (;;) { if (atomic_load(&g_poll)) { int w, h; nxhost_peanuts_frame_size(&w, &h); } usleep(33000); } return NULL; }

int main(int argc, char **argv) {
    const char *tmp = argc > 1 ? argv[1] : getenv("TMPDIR"); if (!tmp) tmp = "/tmp";
    char cmd[2048], root[400]; snprintf(root, sizeof root, "%s/t_peanuts_host.%d", tmp, (int)getpid());
    snprintf(g_pack, sizeof g_pack, "%s/0100000000000001-work/android-pack", root);
    snprintf(cmd, sizeof cmd, "mkdir -p '%s' '%s/0100000000000001-work/peanuts/Pkg/dualscreen' && printf '{\"title_id\": \"0100000000000001\"}' > '%s/0100000000000001-work/peanuts/Pkg/dualscreen/manifest.json'", g_pack, root, root);
    if (system(cmd)) { printf("FAIL cannot make the scratch folders\n"); return 2; }
    setenv("HOME", root, 1); unsetenv("DM_ROMFS"); unsetenv("PN_COMPANION"); unsetenv("DM_AOC_DIR"); setenv("DM_TEMP", root, 1);
    struct sigaction sa = {.sa_sigaction = on_fault, .sa_flags = SA_SIGINFO}; sigemptyset(&sa.sa_mask); sigaction(SIGSEGV, &sa, NULL); sigaction(SIGBUS, &sa, NULL);
    snprintf(g_one_mod.name, sizeof g_one_mod.name, "main"); snprintf(g_one_mod.build_id, sizeof g_one_mod.build_id, "0011223344556677");
    static uint8_t image[1 << 16]; g_one_mod.base = image; g_one_mod.size = sizeof image; g_one_mod.loaded = 1; g_one_mod.text_end = 0x4000;
    pthread_t win; pthread_create(&win, NULL, window, NULL);

    // 1
    g_nmod = 1;
    for (int i = 0; i < 5; i++) { peanuts_tick(); ms(16); }   // the game presents
    for (int i = 0; i < 100 && !atomic_load(&n_create); i++) ms(10);
    check(atomic_load(&n_create) == 1 && atomic_load(&create_thread) != (uint64_t)pthread_self(), "the runtime is made once, on a thread that is not the presenting one");
    check(nxhost_peanuts_rt_state() == 1 && g_cfg.guest.stop && g_cfg.guest.stopped_in && g_cfg.pad.read && g_cfg.helper.sites_tsv, "with the host's stop, its look at the threads, its pad reader and the rewriter's site table handed to it");

    // 2: no present at all from here on
    ms(100); int t0 = atomic_load(&n_tick); ms(1000); int per_s = atomic_load(&n_tick) - t0;
    check(per_s >= 55 && per_s <= 62, "60 ticks a second with no present at all");
    printf("     (%d ticks in one second)\n", per_s);

    // 4a: hidden until the window asks
    check(atomic_load(&last_present) == 0, "the page's window has not asked yet: the runtime is told the page is not shown");
    atomic_store(&g_poll, 1); ms(200);
    check(atomic_load(&last_present) == 1, "the window asks: shown");

    // 5
    int r0 = atomic_load(&n_pad_raw); uint32_t npad = 0; uint64_t b = 0; int32_t a[4];
    atomic_store(&g_pad_now, 0x1); g_cfg.pad.read(g_cfg.pad.user, &npad, &b, a); int held = b == 0x1 && npad == 0x20;
    atomic_store(&g_pad_now, 0); g_cfg.pad.read(g_cfg.pad.user, &npad, &b, a);
    check(held && b == 0 && atomic_load(&n_pad_raw) == r0 + 2, "the runtime's pad reads go to the host each time: a release is read at once");
    ms(100);
    check(atomic_load(&n_pad_raw) == r0 + 2, "and the tick takes no sample of its own");

    // 3
    atomic_store(&min_gap_ns, UINT64_MAX); atomic_store(&tick_ms, 25); ms(500); atomic_store(&tick_ms, 0);
    uint64_t gap = atomic_load(&min_gap_ns);
    check(gap != UINT64_MAX && gap >= 1900000ull, "ticks that overrun their period: at least 2 ms between the end of one and the start of the next");
    printf("     (shortest gap %.2f ms)\n", gap / 1e6);
    ms(100);

    // 2 and 4b: suspended; the launcher hides the page meanwhile; resumed; shown again
    peanuts_lifecycle(1); ms(150); t0 = atomic_load(&n_tick); ms(400);
    check(atomic_load(&n_tick) == t0 && atomic_load(&n_lifecycle) == 1, "the game is suspended: no tick, and the runtime was told of the pause");
    nxhost_peanuts_present(0);
    ms(100);
    peanuts_lifecycle(0); ms(300);
    check(atomic_load(&n_tick) > t0 && atomic_load(&n_lifecycle) == 2, "resumed: it ticks again, and was told");
    check(atomic_load(&last_present) == 0, "the hide that came during the suspension is what the runtime has after it");
    nxhost_peanuts_present(1); ms(200);
    check(atomic_load(&last_present) == 1, "and the next show is taken up (it stayed hidden for good)");
    nxhost_peanuts_present(0); ms(100);
    check(atomic_load(&last_present) == 0, "a hide while running: within a tick");
    nxhost_peanuts_present(1); ms(100);
    atomic_store(&g_poll, 0); ms(1800);
    check(atomic_load(&last_present) == 0, "a window that stopped asking (a launcher that never tells): not shown after 1.5 s");

    // 6
    size_t len = 1 << 16; uint8_t *g = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    int named = !prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, (unsigned long)g, len, (unsigned long)"nxnative guest");
    if (!named) printf("     (this kernel does not name anonymous memory: the cached path cannot be reached, the checks below are skipped)\n");
    else {
        memset(g, 0x5a, len); rt_ranges_refresh();
        uint32_t v = 0;
        check(rt_range_has((uint64_t)(uintptr_t)g + 0x100, 4) && rt_mem_read(NULL, (uint64_t)(uintptr_t)g + 0x100, &v, 4) == 1 && v == 0x5a5a5a5a, "a guest range is read from the cache");
        munmap(g, len);   // the game gives the memory back; the cache still has the range
        v = 7;
        int got = rt_mem_read(NULL, (uint64_t)(uintptr_t)g + 0x100, &v, 4);
        check(got == 0 && atomic_load(&g_rd_faults) == 1, "the range unmapped under the cache: the read fails, the process lives");
        check(!rt_range_has((uint64_t)(uintptr_t)g + 0x100, 4), "and the cache was read again: the range is gone from it");
        uint8_t *g2 = mmap(g, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
        prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, (unsigned long)g2, len, (unsigned long)"nxnative guest");
        memset(g2, 0x33, len);
        check(rt_mem_read(NULL, (uint64_t)(uintptr_t)g + 0x100, &v, 4) == 1 && v == 0x33333333, "mapped again, it reads again");
        mprotect(g2, len, PROT_NONE); rt_ranges_refresh(); mprotect(g2, len, PROT_READ); rt_ranges_refresh(); mprotect(g2, len, PROT_NONE);
        check(rt_mem_read(NULL, (uint64_t)(uintptr_t)g + 0x100, &v, 4) == 0 && atomic_load(&g_rd_faults) == 2, "made inaccessible under the cache: the same");
        v = 1; int after = rt_mem_read(NULL, (uint64_t)(uintptr_t)image + 8, &v, 4);
        check(after == 1 && v == 0, "the tick thread's reads go on afterwards (a second fault would not be blocked)");
    }
    snprintf(cmd, sizeof cmd, "rm -rf '%s'", root); if (system(cmd)) {}
    if (g_fail) printf("FAILED: %d\n", g_fail); else printf("all passed\n");
    fflush(stdout); _exit(g_fail ? 1 : 0);
}
