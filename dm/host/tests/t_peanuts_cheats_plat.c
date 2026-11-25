// PEANUTS: the services of plat_android.c that hle_cheats.c calls, for t_cheats.c built as if for Android on a Linux PC (make GAME=<id> peanuts-cheats). The copies
// through the kernel are plat_android.c's (process_vm_readv / process_vm_writev on this process, which fail on a page that is not mapped or not writable instead of
// faulting); the mapping lookup reads /proc/self/maps as it does; the files queue runs each job at once (the test drains before it reads a file anyway); there is no
// alias of main, as on Android (but for make peanuts-cheats-alias, below), and the instruction cache of this PC needs no flush.
#define _GNU_SOURCE
#include "../plat/plat.h"
#include <stdio.h>
#include <sys/mman.h>
#include <sys/uio.h>
#include <unistd.h>

static int vm_copy(uint64_t addr, void *buf, size_t len, int write) {
    struct iovec local = {buf, len}, remote = {(void *)(uintptr_t)addr, len};
    ssize_t n = write ? process_vm_writev(getpid(), &local, 1, &remote, 1, 0) : process_vm_readv(getpid(), &local, 1, &remote, 1, 0);
    return n == (ssize_t)len ? 0 : -1;
}
int plat_mem_read(uint64_t addr, void *out, size_t len) { return vm_copy(addr, out, len, 0); }
int plat_mem_write(uint64_t addr, const void *src, size_t len) { return vm_copy(addr, (void *)src, len, 1); }
int plat_mem_region(uint64_t addr, uint64_t *start, uint64_t *size, int *prot) {
    FILE *f = fopen("/proc/self/maps", "re"); if (!f) return -1;
    char line[512]; int r = -1;
    while (fgets(line, sizeof line, f)) {
        unsigned long long a, b; char perm[5] = "";
        if (sscanf(line, "%llx-%llx %4s", &a, &b, perm) != 3 || b <= addr) continue;
        *start = a; *size = b - a; *prot = (perm[0] == 'r' ? PROT_READ : 0) | (perm[1] == 'w' ? PROT_WRITE : 0) | (perm[2] == 'x' ? PROT_EXEC : 0);
        r = 0; break;
    }
    fclose(f); return r;
}
#if PN_TEST_ALIAS
// make peanuts-cheats-alias: the path of every platform but Android (PN_CODE 0), which writes code through a writable alias of main (the Mac's mach_vm_remap).
// Linux has no remap of private memory, so the stand-in moves main's pages into a memfd at the same address (same bytes, same protection per range) and maps the
// memfd a second time, read + write: the same pages twice, as mach_vm_remap gives
#include <stdlib.h>
#include <string.h>
void *plat_mem_alias(void *addr, size_t len) {
    uint64_t a = (uint64_t)(uintptr_t)addr, at = a; struct { uint64_t start, size; int prot; } r[16]; int n = 0;
    while (at < a + len && n < 16) { uint64_t s, z; int p; if (plat_mem_region(at, &s, &z, &p) || s > at) return NULL; r[n].start = at; r[n].size = (s + z < a + len ? s + z : a + len) - at; r[n++].prot = p; at += r[n - 1].size; }
    if (at < a + len) return NULL;
    int fd = memfd_create("main-alias", MFD_CLOEXEC); if (fd < 0) return NULL;
    void *copy = malloc(len), *alias = MAP_FAILED;
    if (copy && !ftruncate(fd, (off_t)len) && !plat_mem_read(a, copy, len) && mmap(addr, len, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0) == addr) {
        memcpy(addr, copy, len);
        for (int i = 0; i < n; i++) mprotect((void *)(uintptr_t)r[i].start, r[i].size, r[i].prot);
        alias = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    }
    free(copy); close(fd);
    return alias == MAP_FAILED ? NULL : alias;
}
#else
void *plat_mem_alias(void *addr, size_t len) { (void)addr; (void)len; return NULL; }
#endif
void plat_icache_invalidate(void *p, size_t len) { (void)p; (void)len; }
struct plat_queue { int unused; };
plat_queue_t *plat_queue_create(const char *name) { (void)name; static plat_queue_t q; return &q; }
void plat_queue_async(plat_queue_t *q, void (*fn)(void *), void *ctx) { (void)q; fn(ctx); }
void plat_queue_drain(plat_queue_t *q) { (void)q; }
