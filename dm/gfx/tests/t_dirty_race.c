// Ordering race between "set read-only" and "unprotect before write" in dirty-page tracking (originally a test of the host this one started as, stubs adapted to this repository's tracking table).
// Scene: thread A, in nvn_dirty_protect, has already recorded the page state as "protected" while its mprotect has not landed yet; thread B now arrives with prep_write (the unprotect done before the host writes into guest memory,
// for example the movie decoder thread writing a frame into texture storage, or a disk read into a guest buffer). Old implementation: B sets the state back to "none", then A's mprotect lands => the page is read-only while its state is "none" --
//   when the host reads a file into it again: prep_write sees state "none" and skips, the system call does not go through the page-fault handler and fails with EFAULT; when a game thread writes directly: the page-fault handler only returns "re-execute" and spins forever.
// How it is staged: the mprotect in nvn_dirty.c is replaced by t_mprotect here -- before the call with which A sets read-only lands, B is let in and given 100 ms, so the order is deterministic.
// Expected values do not depend on the implementation under test: after B's prep_write returns, this page must really be writable (reading 16 bytes into it gives 16; writing one byte directly does not hang and reads back as written),
// and this write must be recorded as dirty (nvn_dirty_since returns 1 for a stamp taken before the page was set read-only).
#include <stdatomic.h>
static void t_before_store(const volatile void *object);
// Observe the clock increment before its value reaches the page generation, at the real publication seam in both unprotect paths.
#undef atomic_store
#define atomic_store(object, desired) do { \
    __auto_type object_ = (object); \
    __auto_type desired_ = (desired); \
    t_before_store(object_); \
    __c11_atomic_store(object_, desired_, __ATOMIC_SEQ_CST); \
} while (0)
#define mprotect t_mprotect
#include "../nvn/nvn_dirty.c"
#undef mprotect
int mprotect(void *, size_t, int);
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>

module_t *g_mod;
int g_nmod;
module_t *module_of(const void *p) { (void)p; return NULL; }
void crash_install(void);

static int g_fail;
#define EXPECT(name, want, got) do { \
    long long want_ = (long long)(want), got_ = (long long)(got); \
    printf("%-58s expected %-4lld → got %-4lld %s\n", name, want_, got_, want_ == got_ ? "✓" : "✗"); \
    if (want_ != got_) g_fail++; \
} while (0)

static uint8_t *g_page; static _Atomic int g_armed, g_go, g_b_done;
static int g_publish_armed, g_observed_clean;
static uint8_t *g_publish_page;
static const volatile void *g_publish_gen;
static size_t g_publish_len;
static uint64_t g_publish_stamp, g_observed_now;
static void t_before_store(const volatile void *object) {
    if (!g_publish_armed || object != g_publish_gen) return;
    g_publish_armed = 0;
    g_observed_now = nvn_dirty_now();
    g_observed_clean = !nvn_dirty_since(g_publish_page, g_publish_len, g_publish_stamp);
}
static void *thread_b(void *arg) { (void)arg; while (!atomic_load(&g_go)) usleep(200); prep_write(g_page, 16); atomic_store(&g_b_done, 1); return NULL; }
int t_mprotect(void *a, size_t n, int prot) {
    if (prot == PROT_READ && atomic_exchange(&g_armed, 0)) { atomic_store(&g_go, 1); usleep(100000); }   // Before A's mprotect lands: let B in and wait until it runs to the end or gets stuck waiting
    return mprotect(a, n, prot);
}

// A page another thread is unprotecting (its mprotect to writable done and the new generation stored, the state still BUSY) must be waited out by nvn_dirty_protect and
// protected again: skipped, it stayed writable with a generation the caller's stamp covers, and later writes to it were never seen (MacNativeGames a166602, missing letters
// in a glyph cache). Thread C plays the last step of that unprotect 50 ms late
static _Atomic uint8_t *g_c_state;
static void *thread_c(void *arg) { (void)arg; usleep(50000); atomic_store(g_c_state, PG_NONE); return NULL; }
static void busy_protect(size_t pg) {
    uint8_t *page = mmap(NULL, pg, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (page == MAP_FAILED) { perror("mmap"); g_fail++; return; }
    nvn_dirty_add_range(page, pg);
    range_t *r = find_span((uintptr_t)page, 1); uintptr_t i = r ? ((uintptr_t)page - r->base) >> g_shift : 0;
    nvn_dirty_protect(page, pg);
    uint8_t st = PG_PROT;
    if (!r || !atomic_compare_exchange_strong(&r->state[i], &st, PG_BUSY)) { printf("busy page: not protected\n"); g_fail++; return; }
    mprotect(page, pg, PROT_READ | PROT_WRITE);   // what unprotect_page does before it stores the generation
    atomic_store(&r->gen[i], atomic_fetch_add(&g_now, 1) + 1);
    g_c_state = &r->state[i];
    pthread_t tc; pthread_create(&tc, NULL, thread_c, NULL);
    uint64_t stamp = nvn_dirty_now();   // an uploader's stamp, then its protect
    nvn_dirty_protect(page, pg);
    pthread_join(tc, NULL);
    EXPECT("protect waits out a page being unprotected (state PROT)", PG_PROT, atomic_load(&r->state[i]));
    EXPECT("no write after the stamp yet: clean", 0, nvn_dirty_since(page, 16, stamp));
    page[0] = 7;
    EXPECT("a later write to that page is seen", 1, nvn_dirty_since(page, 16, stamp));
}
// Why the uploads take their stamp before nvn_dirty_protect: a write that faults while the pages are protected or read gets a generation above a stamp taken before and
// leaves its page writable; a stamp taken after covers it, so that write and every later one are missed (the second check is the old order, expected to miss)
static void stamp_order(size_t pg) {
    uint8_t *page = mmap(NULL, pg, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (page == MAP_FAILED) { perror("mmap"); g_fail++; return; }
    nvn_dirty_add_range(page, pg);
    uint64_t before = nvn_dirty_now();
    nvn_dirty_protect(page, pg);
    page[0] = 1;   // faults during the upload
    uint64_t after = nvn_dirty_now();
    page[0] = 2;   // after the read: no fault, the page is writable
    EXPECT("stamp before protect: the writes are seen", 1, nvn_dirty_since(page, 16, before));
    EXPECT("stamp after protect (the old order): the writes are missed", 0, nvn_dirty_since(page, 16, after));
}

// A lookup can see the new clock before the page generation is stored and cache a clean result. Completing either unprotect path must invalidate that cached result.
static void generation_publish(size_t pg, int batch) {
    size_t len = batch ? 2 * pg : pg;
    uint8_t *page = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (page == MAP_FAILED) { perror("mmap"); g_fail++; return; }
    nvn_dirty_add_range(page, len);
    g_publish_page = page + len - pg; g_publish_len = pg; g_publish_stamp = nvn_dirty_now();
    range_t *r = find_span((uintptr_t)page, len);
    g_publish_gen = &r->gen[((uintptr_t)g_publish_page - r->base) >> g_shift];
    nvn_dirty_protect(page, len);
    g_observed_clean = 0; g_publish_armed = 1;
    if (batch) prep_write(page, len); else on_fault(page, 1, NULL);
    page[0] = 3;
    if (batch) page[pg] = 4;
    printf("generation publication (%s):\n", batch ? "host pre-write, batched pages" : "page fault");
    EXPECT("observer ran before the generation was stored", 1, g_observed_clean);
    EXPECT("publication invalidates the observer's cached clock", 1, nvn_dirty_now() != g_observed_now);
    EXPECT("lookup using the cached clock detects the write", 0,
           nvn_dirty_now() == g_observed_now || !nvn_dirty_since(g_publish_page, pg, g_publish_stamp));
    nvn_dirty_remove_range(page); munmap(page, len);
}

static void nprot_accounting(size_t pg) {   // PEANUTS: bounded WriteTexels dirty prep, #403 (0.1.8)
    uint8_t *buf = mmap(NULL, 4 * pg, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (buf == MAP_FAILED) { perror("mmap"); g_fail++; return; }
    nvn_dirty_add_range(buf, 4 * pg);
    range_t *r = find_span((uintptr_t)buf, 4 * pg);
    EXPECT("nprot starts at 0", 0, r ? (long long)atomic_load(&r->nprot) : -1);
    nvn_dirty_protect(buf, 4 * pg);
    EXPECT("nprot is 4 after protecting 4 pages", 4, r ? (long long)atomic_load(&r->nprot) : -1);
    nvn_dirty_protect(buf, 4 * pg);   // re-protecting already-protected pages does not double-count
    EXPECT("nprot stays 4 after re-protecting already-protected pages", 4, r ? (long long)atomic_load(&r->nprot) : -1);
    prep_write(buf, 2 * pg);
    EXPECT("nprot drops to 2 after prep_write on 2 pages", 2, r ? (long long)atomic_load(&r->nprot) : -1);
    uint64_t f0, p0, f1, p1;
    nvn_dirty_stats(&f0, &p0);
    prep_write(buf, 2 * pg);   // already PG_NONE: skips CAS and leaves g_preps unchanged
    nvn_dirty_stats(&f1, &p1);
    EXPECT("prep_write on PG_NONE pages skips unprotect work", 0, (long long)(p1 - p0));
    EXPECT("nprot stays 2", 2, r ? (long long)atomic_load(&r->nprot) : -1);
    ((volatile uint8_t *)buf)[2 * pg] = 0x11;   // faults on page 2
    ((volatile uint8_t *)buf)[3 * pg] = 0x22;   // faults on page 3 (or released in fault_run with page 2)
    EXPECT("nprot drops to 0 after remaining pages unprotect", 0, r ? (long long)atomic_load(&r->nprot) : -1);
    nvn_dirty_stats(&f0, &p0);
    prep_write(buf, 4 * pg);   // entire range has nprot == 0: fast-skipped
    nvn_dirty_stats(&f1, &p1);
    EXPECT("prep_write fast-skips when nprot == 0", 0, (long long)(p1 - p0));
    nvn_dirty_remove_range(buf); munmap(buf, 4 * pg);
}

static uint8_t *g_stress_mem;
static size_t g_stress_pg;
static void *stress_protect(void *arg) { (void)arg; for (int i = 0; i < 2000; i++) nvn_dirty_protect(g_stress_mem, 4 * g_stress_pg); return NULL; }
static void *stress_write(void *arg) {
    (void)arg;
    for (int i = 0; i < 2000; i++) {
        size_t p = (size_t)(i & 3) * g_stress_pg;
        prep_write(g_stress_mem + p, 16);
        ((volatile uint8_t *)g_stress_mem)[p] = (uint8_t)i;
    }
    return NULL;
}
static void *stress_prep(void *arg) {
    (void)arg;
    for (int i = 0; i < 2000; i++) {
        size_t p = (size_t)((i * 3) & 3) * g_stress_pg;
        prep_write(g_stress_mem + p, 64);
    }
    return NULL;
}
static void concurrent_stress(size_t pg) {
    g_stress_pg = pg;
    g_stress_mem = mmap(NULL, 4 * pg, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (g_stress_mem == MAP_FAILED) { perror("mmap"); g_fail++; return; }
    nvn_dirty_add_range(g_stress_mem, 4 * pg);
    uint64_t stamp = nvn_dirty_now();
    pthread_t t1, t2, t3;
    pthread_create(&t1, NULL, stress_protect, NULL);
    pthread_create(&t2, NULL, stress_write, NULL);
    pthread_create(&t3, NULL, stress_prep, NULL);
    pthread_join(t1, NULL); pthread_join(t2, NULL); pthread_join(t3, NULL);
    prep_write(g_stress_mem, 4 * pg);
    range_t *r = find_span((uintptr_t)g_stress_mem, 4 * pg);
    EXPECT("stress: nprot returns to 0 after full prep_write", 0, r ? (long long)atomic_load(&r->nprot) : -1);
    int all_none = r != NULL, all_writable = 1;
    int fd = open("/dev/zero", O_RDONLY);
    for (int k = 0; k < 4; k++) {
        if (r && atomic_load(&r->state[k]) != PG_NONE) all_none = 0;
        if (fd < 0 || read(fd, g_stress_mem + (size_t)k * pg, 16) != 16) all_writable = 0;
    }
    if (fd >= 0) close(fd);
    EXPECT("stress: all 4 pages in PG_NONE state", 1, all_none);
    EXPECT("stress: all 4 pages writable via read()", 1, all_writable);
    EXPECT("stress: writes recorded as dirty", 1, nvn_dirty_since(g_stress_mem, 4 * pg, stamp));
    nvn_dirty_remove_range(g_stress_mem); munmap(g_stress_mem, 4 * pg);
}

int main(void) {
    crash_install();
    size_t pg = (size_t)getpagesize();
    g_page = mmap(NULL, 2 * pg, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (g_page == MAP_FAILED) { perror("mmap"); return 2; }
    nvn_dirty_add_range(g_page, 2 * pg);
    uint64_t stamp = nvn_dirty_now();
    pthread_t tb; pthread_create(&tb, NULL, thread_b, NULL);
    atomic_store(&g_armed, 1);
    nvn_dirty_protect(g_page, 2 * pg);                       // A: set the state -> (B cuts in) -> mprotect lands
    for (int i = 0; i < 2000 && !atomic_load(&g_b_done); i++) usleep(1000);
    EXPECT("B's unprotect-before-write returns within 2 seconds", 1, atomic_load(&g_b_done));
    if (!atomic_load(&g_b_done)) { printf("t_dirty_race: B did not return, skipping the remaining checks\n"); return 1; }
    pthread_join(tb, NULL);
    int fd = open("/dev/zero", O_RDONLY); g_page[pg] = 0;     // Nobody has unprotected the second page, so this write goes through the page-fault handler (control: the normal path works)
    ssize_t r = read(fd, g_page, 16);                        // The host write that B is about to make: a system call writing straight into the page
    EXPECT("file read into the page after B unprotects it: bytes read = the 16 requested", 16, r);
    if (r == 16) { ((volatile uint8_t *)g_page)[1] = 0x5a; EXPECT("direct write to the page does not hang and reads back as written", 0x5a, g_page[1]); }
    else { printf("  (read failed errno=%d; the page is read-only while its state is 'none', a direct write would spin in the page-fault handler, so this check is skipped)\n", errno); g_fail++; }
    EXPECT("this write is recorded as dirty (against a stamp from before set-read-only)", 1, nvn_dirty_since(g_page, 16, stamp));
    close(fd);
    busy_protect(pg);
    stamp_order(pg);
    generation_publish(pg, 0);
    generation_publish(pg, 1);
    nprot_accounting(pg);   // PEANUTS: bounded WriteTexels dirty prep, #403 (0.1.8)
    concurrent_stress(pg);
    printf("t_dirty_race: %s\n", g_fail ? "FAILED" : "all passed"); return g_fail != 0;
}
