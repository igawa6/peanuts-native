// Fixed maps inside the guest block merge with their neighbours again (dm/host/plat/plat_guestmap.h; hle_svc.c guest_release, vka_mem.c sw_mmap_fixed, plat_android.c
// plat_mem_reserve). Linux only: the kernel's own merge rules, counted in /proc/self/maps. A block reserved as plat_mem_reserve does it (PROT_NONE, PLAT_GUEST_MAP, named)
// goes through what a game does to it: MapPhysicalMemory (mprotect read-write, touched), UnmapPhysicalMemory (guest_release: a fixed PROT_NONE map, named), a pool's pages
// given back by the Vulkan replay (unplace: a fixed read-write map, named, filled), the dirty tracker's protections and their release (nvn_dirty_release_all). With the
// block's flags every piece merges back once its protection matches its neighbours': the block ends as few maps as its protections need. The old flags (MAP_PRIVATE |
// MAP_ANONYMOUS, no MAP_NORESERVE) are run too, as the control: each fixed map stays a mapping of its own (OoT on the phone: 28,000 such maps in the guest block, issue #265)
#define _GNU_SOURCE 1
#include "../plat/plat_guestmap.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#ifndef PR_SET_VMA
#define PR_SET_VMA 0x53564d41
#define PR_SET_VMA_ANON_NAME 0
#endif

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
enum { RUN = 64 << 10, NRUN = 256 };
static const size_t SZ = 64u << 20;
static uintptr_t g_b;
static int named = 1;
static void name(void *p, size_t n) { if (prctl(PR_SET_VMA, PR_SET_VMA_ANON_NAME, p, n, "nxnative guest")) named = 0; }   // plat_mem_name_guest
static int maps_in_block(void) {
    FILE *f = fopen("/proc/self/maps", "re"); if (!f) return -1;
    char l[512]; int n = 0; unsigned long a, b;
    while (fgets(l, sizeof l, f)) if (sscanf(l, "%lx-%lx", &a, &b) == 2 && a < g_b + SZ && b > g_b) n++;
    fclose(f); return n;
}
// The game's churn with the given flags for fixed maps; returns the block's maps at the end, when every page is read-write again
static int churn(int fixed_flags, int *after_release) {
    g_b = (uintptr_t)mmap(NULL, SZ, PROT_NONE, PLAT_GUEST_MAP, -1, 0);   // plat_mem_reserve
    if ((void *)g_b == MAP_FAILED) return -1;
    name((void *)g_b, SZ);
    uint8_t *b = (uint8_t *)g_b;
    for (int i = 0; i < NRUN; i++) { mprotect(b + (size_t)i * RUN, RUN, PROT_READ | PROT_WRITE); memset(b + (size_t)i * RUN, 1, RUN); }   // MapPhysicalMemory, touched
    for (int i = 0; i < NRUN; i += 2) { mmap(b + (size_t)i * RUN, RUN, PROT_NONE, fixed_flags | MAP_FIXED, -1, 0); name(b + (size_t)i * RUN, RUN); }   // guest_release
    for (int i = 0; i < NRUN; i += 2) { mprotect(b + (size_t)i * RUN, RUN, PROT_READ | PROT_WRITE); memset(b + (size_t)i * RUN, 2, RUN); }   // mapped again
    for (int i = 1; i < NRUN; i += 4) {   // unplace: the pool's bytes into fresh pages at its address
        void *p = mmap(b + (size_t)i * RUN, RUN, PROT_READ | PROT_WRITE, fixed_flags | MAP_FIXED, -1, 0); name(p, RUN); memset(p, 3, RUN);
    }
    for (int i = 0; i < NRUN * (RUN / 4096); i += 3) mprotect(b + (size_t)i * 4096, 4096, PROT_READ);   // the dirty tracker
    mprotect(b, (size_t)NRUN * RUN, PROT_READ | PROT_WRITE);   // nvn_dirty_release_all
    *after_release = maps_in_block();
    for (int i = 0; i < NRUN * RUN; i += 4096 * 8) CHECK(b[i] >= 1 && b[i] <= 3, "byte at +%#x is %u", i, b[i]);
    munmap(b, SZ);
    return *after_release;
}

int main(void) {
    int rel = 0, now = churn(PLAT_GUEST_MAP, &rel);
    // (A kernel without anonymous VMA names (CONFIG_ANON_VMA_NAME) leaves every map unnamed: the flags are then all that decides, which is what this checks)
    // The read-write runs and the reservation's PROT_NONE rest: 2 maps
    CHECK(now >= 0 && now <= 2, "with the block's flags the block ends as %d maps (want at most 2: the read-write runs and the reserved rest)", now);
    int old_rel = 0, old = churn(MAP_PRIVATE | MAP_ANONYMOUS, &old_rel);
    // Control: the flags before the fix leave every fixed map apart (NRUN / 2 releases, NRUN / 4 give-backs)
    CHECK(old > NRUN / 2, "the old flags end as %d maps: the control does not show the problem on this kernel", old);
    printf("t_android_guestmap: %s (block's flags: %d maps after the churn; old flags: %d; names %s)\n", fails ? "FAILED" : "ok", now, old, named ? "on" : "not in this kernel");
    return fails ? 1 : 0;
}
