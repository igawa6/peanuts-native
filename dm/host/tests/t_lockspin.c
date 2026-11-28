// PEANUTS: unit test of ArbitrateLock's short spin (PN_LOCK_SPIN in hle_thread.c, GitHub issue #392), judged only by behaviour: the SVC's return, the lock word and the timing.
// Cases: a spin on, running owner; b spin off; c spin on, blocked owner; e owner in host work; f owner word is the thread ID (SDK 22); d 3-thread lock stress.
// The guest threads are real CreateThread / StartThread SVCs whose entries are C functions run by dm_thread_enter below (the Makefile builds this with DM_TEST_THREAD_ENTER).
// peanuts_lock_spin_ns() is strong here and overrides hle_thread.c's weak definition; this test links hle_thread.c and does not include it.
#include "../dm.h"
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sched.h>
#include <unistd.h>

int svc_thread(int num, uint64_t *regs);
uint32_t svc_thread_main_init(void);
uint64_t now_ns(void);
void (*dm_test_cv_after_release)(void);   // hle_thread.c refers to it under DM_TEST_HOOKS (t_condvar sets it; this test never does)
extern __thread _Atomic int dm_host_work; // the owner's host-work depth (case e)

#define WAITERS_BIT 0x40000000u
#define MAIN_TAG    0x2001u               // this test's main thread (the waiter of cases a to c, e, f) takes the lock with this tag
#define OWNER_TAG2  0x3001u               // the blocked owner's tag on its own lock word (case c)
#define P(x) ((uint64_t)(uintptr_t)(x))

static uint64_t g_spin_ns;
uint64_t peanuts_lock_spin_ns(void) { return g_spin_ns; }   // overrides hle_thread.c's weak one

// The guest thread entry: run the C function, then ExitThread (as t_notify_sched does)
void dm_thread_enter(uint64_t entry, uint64_t arg, uint64_t sp) {
    (void)sp;
    ((void (*)(uint64_t))(uintptr_t)entry)(arg);
    uint64_t r[32] = {0}; svc_thread(0x0A, r);
    abort();
}

static uint32_t create_thread(void (*fn)(uint64_t), uint64_t arg, uint64_t core) {
    uint64_t r[32] = {0};
    r[1] = P(fn); r[2] = arg; r[3] = 0; r[4] = 44; r[5] = core;   // entry, argument, stack top, priority, core
    svc_thread(0x08, r);
    return (uint32_t)r[1];
}
static void start_thread(uint32_t h) { uint64_t r[32] = {h}; svc_thread(0x09, r); }

static int g_fails, g_total;
static void verdict(char letter, const char *what, int ok, const char *detail) {
    printf("%s %c: %s -- %s\n", ok ? "PASS" : "FAIL", letter, what, detail);
    g_total++;
    if (!ok) g_fails++;
}

static _Atomic uint32_t g_lock;           // the lock word (cases a to c, e, f)
static _Atomic int g_go, g_done, g_in;
static uint64_t g_busy_ns, g_t_unlock;   // how long the owner runs before it unlocks; when it unlocked (its clock, before svc 0x1B)
static int g_owner_hostwork, g_tag_is_id;

// Owner of cases a, b, e, f: holds the lock, runs (busy, so it stays runnable) for g_busy_ns, then unlocks through svc 0x1B
static void owner_fn(uint64_t arg) {
    (void)arg;
    while (!atomic_load(&g_go)) sched_yield();
    if (g_owner_hostwork) dm_host_work++;
    uint64_t t0 = now_ns(); while (now_ns() - t0 < g_busy_ns) { }
    if (g_owner_hostwork) dm_host_work--;
    g_t_unlock = now_ns();
    uint64_t r[32] = {P(&g_lock)}; svc_thread(0x1B, r);
    atomic_store(&g_done, 1);
}

// The main thread waits for the owner's lock with ArbitrateLock. Returns the SVC result, the lock word right after it, and the time from the owner's unlock to the return
static void owner_case(uint64_t spin, uint64_t busy, int64_t *ret, uint32_t *word, int64_t *lat) {
    g_spin_ns = spin; g_busy_ns = busy; atomic_store(&g_go, 0); atomic_store(&g_done, 0);
    uint32_t owner = create_thread(owner_fn, 0, 1), tag = owner;
    if (g_tag_is_id) { uint64_t q[32] = {0, owner}; svc_thread(0x25, q); tag = (uint32_t)q[1]; }   // GetThreadId: SDK 22 stores the ID
    atomic_store(&g_lock, tag | WAITERS_BIT);   // held by the owner, with a waiter
    start_thread(owner);
    atomic_store(&g_go, 1);
    uint64_t r[32] = {tag, P(&g_lock), MAIN_TAG};
    svc_thread(0x1A, r);
    *word = atomic_load(&g_lock);
    uint64_t tr = now_ns();
    *ret = (int64_t)r[0]; *lat = (int64_t)(tr - g_t_unlock);
    while (!atomic_load(&g_done)) usleep(100);
}

// Cases a, e, f: spin on, the owner runs 200 us and unlocks. The spin ends with the change: the SVC returns 0 within 2 ms of the unlock and the word is 0 (no handoff)
static void spin_case(char letter, const char *what, int hostwork, int tag_is_id) {
    int64_t ret, lat; uint32_t word; char d[160];
    g_owner_hostwork = hostwork; g_tag_is_id = tag_is_id;
    owner_case(2000000, 200000, &ret, &word, &lat);
    g_owner_hostwork = 0; g_tag_is_id = 0;
    int ok = ret == 0 && lat >= 0 && lat < 2000000 && word == 0;
    snprintf(d, sizeof d, "returned %lld, %lld us after the owner's unlock, word %#x (expected 0)", (long long)ret, (long long)(lat / 1000), word);
    verdict(letter, what, ok, d);
}

// Case b: spin off, the same setup: the waiter sleeps and the owner's unlock hands it the lock (the word is MAIN_TAG)
static void handoff_case_b(void) {
    int64_t ret, lat; uint32_t word; char d[160];
    owner_case(0, 1000000, &ret, &word, &lat);
    int ok = ret == 0 && lat >= 0 && word == MAIN_TAG;
    snprintf(d, sizeof d, "returned %lld, %lld us after the owner's unlock, word %#x (expected MAIN_TAG %#x)", (long long)ret, (long long)(lat / 1000), word, MAIN_TAG);
    verdict('b', "spin off, handoff", ok, d);
}

// Case c: the owner is blocked in svc 0x1C, so the waiter does not spin. A helper releases the lock with svc 0x1B after 5 ms, as the owner would
static _Atomic uint32_t g_lock2, g_key;
static void owner_block_fn(uint64_t arg) {
    (void)arg;
    atomic_store(&g_in, 1);
    uint64_t r[32] = {P(&g_lock2), P(&g_key), OWNER_TAG2, (uint64_t)-1};   // no timeout: blocked until svc 0x1D on the key
    svc_thread(0x1C, r);
    atomic_store(&g_done, 1);
}
static void helper_fn(uint64_t arg) {
    (void)arg;
    uint64_t s[32] = {5000000}; svc_thread(0x0B, s);   // SleepThread 5 ms
    uint64_t u[32] = {P(&g_lock)}; svc_thread(0x1B, u);
}
static void blocked_case_c(void) {
    atomic_store(&g_in, 0); atomic_store(&g_done, 0); atomic_store(&g_lock2, 0); atomic_store(&g_key, 0); g_spin_ns = 2000000;
    uint32_t owner = create_thread(owner_block_fn, 0, 1);
    start_thread(owner);
    while (!atomic_load(&g_in)) sched_yield();
    usleep(5000);   // the owner is linked into its wait by now
    atomic_store(&g_lock, owner | WAITERS_BIT);
    uint32_t helper = create_thread(helper_fn, 0, 2);
    start_thread(helper);
    uint64_t r[32] = {owner, P(&g_lock), MAIN_TAG};
    uint64_t t0 = now_ns();
    svc_thread(0x1A, r);
    uint64_t dt = now_ns() - t0;
    uint32_t word = atomic_load(&g_lock);
    uint64_t k[32] = {P(&g_key), 0}; svc_thread(0x1D, k);   // release the owner: wake all waiters on its key
    while (!atomic_load(&g_done)) usleep(100);
    int ok = r[0] == 0 && word == MAIN_TAG && dt >= 4000000;
    char d[160];
    snprintf(d, sizeof d, "returned %lld after %.1f ms, word %#x (expected MAIN_TAG %#x)", (long long)(int64_t)r[0], dt / 1e6, word, MAIN_TAG);
    verdict('c', "spin on, blocked owner", ok, d);
}

// Case d: the SDK's lock protocol over one word. Every thread that waits sets the waiters bit and calls 0x1A; the lock is handed over or the spin ends with the word changed
static _Atomic uint32_t g_slock;
static uint64_t g_counter;                // guarded by g_slock
static _Atomic int g_sdone;
static uint32_t g_stag[3];
static void stress_fn(uint64_t idx) {
    uint32_t tag = g_stag[idx];
    for (int i = 0; i < 20000; i++) {
        for (;;) {
            uint32_t z = 0;
            if (atomic_compare_exchange_strong(&g_slock, &z, tag)) break;   // free: take it
            uint32_t v = atomic_load(&g_slock);
            if (v == 0) continue;
            if (!(v & WAITERS_BIT) && !atomic_compare_exchange_strong(&g_slock, &v, v | WAITERS_BIT)) continue;
            uint64_t r[32] = {v & ~WAITERS_BIT, P(&g_slock), tag};
            svc_thread(0x1A, r);
            if ((atomic_load(&g_slock) & ~WAITERS_BIT) == tag) break;   // handed over; otherwise read again and retry
        }
        g_counter++;
        int freed = 0;
        if (!(atomic_load(&g_slock) & WAITERS_BIT)) { uint32_t t = tag; freed = atomic_compare_exchange_strong(&g_slock, &t, 0); }
        if (!freed) { uint64_t u[32] = {P(&g_slock)}; svc_thread(0x1B, u); }   // waiters: hand the lock on through 0x1B
    }
    atomic_fetch_add(&g_sdone, 1);
}
static void stress_case(char letter, const char *what, uint64_t spin) {
    atomic_store(&g_slock, 0); g_counter = 0; atomic_store(&g_sdone, 0); g_spin_ns = spin;
    uint32_t h[3];
    for (int i = 0; i < 3; i++) { h[i] = create_thread(stress_fn, (uint64_t)i, (uint64_t)i); g_stag[i] = h[i]; }
    for (int i = 0; i < 3; i++) start_thread(h[i]);
    while (atomic_load(&g_sdone) < 3) usleep(1000);   // the watchdog (30 s) ends a hang
    int ok = g_counter == 60000 && atomic_load(&g_slock) == 0;
    char d[160];
    snprintf(d, sizeof d, "counter %llu (expected 60000), lock word at the end %#x (expected 0)", (unsigned long long)g_counter, (unsigned)atomic_load(&g_slock));
    verdict(letter, what, ok, d);
}

static void on_alarm(int sig) {
    (void)sig;
    static const char m[] = "FAIL watchdog: a case did not finish within 30 s\n";
    if (write(1, m, sizeof m - 1) < 0) { }
    _exit(1);
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);   // unbuffered: a watchdog exit still shows which case hung
    signal(SIGALRM, on_alarm); alarm(30);
    svc_thread_main_init();
    spin_case('a', "spin on, running owner", 0, 0);
    handoff_case_b();
    blocked_case_c();
    spin_case('e', "spin on, owner in host work", 1, 0);
    spin_case('f', "spin on, owner word is the thread ID (SDK 22)", 0, 1);
    stress_case('d', "3 threads, stress, spin 2 ms", 2000000);
    stress_case('d', "3 threads, stress, spin 2 us", 2000);
    stress_case('d', "3 threads, stress, no spin", 0);
    alarm(0);
    if (g_fails) printf("FAILED: %d of %d cases\n", g_fails, g_total);
    else printf("all passed: %d/%d\n", g_total, g_total);
    fflush(stdout); _exit(g_fails ? 1 : 0);
}
