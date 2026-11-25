// PEANUTS: a GPU clock floor that takes effect on Adreno kernels where the queue's own power constraint never reaches the clock. It shortens the serial chain
// of a game that waits for the GPU end of the frame it just submitted (Super Mario Odyssey 1.4.1), and holds the clock a dynamic frame rate needs (#113).
//
// The clock floor. vka_dev.c sets KGSL_PROP_PWR_CONSTRAINT {PWRLEVEL, highest} on the kernel contexts Turnip made for the device's queue. On the 8 Gen 2's kernel
// (graphics-kernel, gen7 hwsched: gen7_hwsched_submit_drawobj -> adreno_drawobj_set_constraint) the stored constraint is applied to the clock only when a command is
// submitted on a context created with KGSL_CONTEXT_PWR_CONSTRAINT (0x800) or a command carries KGSL_CMDBATCH_PWR_CONSTRAINT (the same bit); Turnip sets neither, so the
// log said "GPU clock floor (KGSL): 100 % (highest)" while the GPU ran 401-550 of 680 MHz (AYN Thor, Adreno 740; GitHub issues #113 and #257 on 8 Gen 2 / 8 Gen 3).
// So this file makes a kernel context of its own on its own descriptor, with that flag and the constraint, and submits one CP_NOP to it once per replayed submission
// (at most every PN_GPU_FLOOR_BEAT_MS, default 8). Each submit applies the constraint device-wide (kgsl_pwrctrl_set_constraint): the clock goes to the highest
// level at once, and the kernel holds it until the owning command has retired and its interval (QCOM_KGSL_IDLE_TIMEOUT, 80 ms by default) has passed, re-armed by
// every later submit. With no frames (paused, in the background) the beat stops and the floor lapses within that interval. The game's own commands are untouched.
// PN_GPU_FLOOR_BEAT=0 turns it off (the old behaviour; live). It runs only where vka_dev.c found the "highest only" form of the constraint (g_kg_host) and DM_VK_GPU_FLOOR > 0.
#if defined(__ANDROID__)
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>

#include "../host/dm.h"
#define PLOG(...) LOG("[peanuts] " __VA_ARGS__)

// msm_kgsl.h (uapi), the parts used here
struct pn_kgsl_drawctxt_create { unsigned int flags, drawctxt_id; };
struct pn_kgsl_gpumem_alloc_id { unsigned int id, flags; size_t size, mmapsize; unsigned long gpuaddr, pad[2]; };
struct pn_kgsl_command_object { uint64_t offset, gpuaddr, size; unsigned int flags, id; };
struct pn_kgsl_gpu_command { uint64_t flags, cmdlist; unsigned int cmdsize, numcmds; uint64_t objlist; unsigned int objsize, numobjs; uint64_t synclist;
                             unsigned int syncsize, numsyncs, context_id, timestamp; };
struct pn_kgsl_device_constraint { unsigned int type, context_id; void *data; size_t size; };
struct pn_kgsl_setprop { unsigned int type; void *value; size_t sizebytes; };
#define PN_KGSL_DRAWCTXT_CREATE _IOWR(0x09, 0x13, struct pn_kgsl_drawctxt_create)
#define PN_KGSL_GPUMEM_ALLOC_ID _IOWR(0x09, 0x34, struct pn_kgsl_gpumem_alloc_id)
#define PN_KGSL_GPU_COMMAND     _IOWR(0x09, 0x4A, struct pn_kgsl_gpu_command)
#define PN_KGSL_SETPROPERTY     _IOW(0x09, 0x32, struct pn_kgsl_setprop)
enum { PN_CTX_NO_GMEM_ALLOC = 0x2, PN_CTX_PREAMBLE = 0x10, PN_PWR_CONSTRAINT = 0x800, PN_CMDLIST_IB = 0x1, PN_PROP_PWR_CONSTRAINT = 0x12, PN_CONSTRAINT_PWRLEVEL = 1, PN_PWR_MAX = 1 };
#define PN_CP_NOP 0x70108000u   // type-7 packet, CP_NOP (0x10), no payload, with its parity bits

static int ioc(int fd, unsigned long req, void *arg) { int r; do r = ioctl(fd, req, arg); while (r < 0 && (errno == EINTR || errno == EAGAIN)); return r < 0 ? -errno : 0; }
static uint64_t mono_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }
static int env_int(const char *k, int def) { const char *e = getenv(k); return e && *e ? atoi(e) : def; }

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_state;   // 0 not set up, 1 ready, -1 off (switched off, or set-up failed)
static int g_fd = -1; static unsigned g_ctx, g_buf_id; static uint64_t g_buf_va; static uint64_t g_last_ns, g_beats, g_fail;

static int setup(void) {
    if (!env_int("PN_GPU_FLOOR_BEAT", 1)) { PLOG("GPU clock floor beat: off (PN_GPU_FLOOR_BEAT=0)"); return -1; }
    g_fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (g_fd < 0) { PLOG("GPU clock floor beat: /dev/kgsl-3d0 not opened (%d): off", errno); return -1; }
    struct pn_kgsl_drawctxt_create c = {PN_CTX_NO_GMEM_ALLOC | PN_CTX_PREAMBLE | PN_PWR_CONSTRAINT, 0};
    int r = ioc(g_fd, PN_KGSL_DRAWCTXT_CREATE, &c);
    if (r) { PLOG("GPU clock floor beat: no kernel context (%d): off", r); goto fail; }
    g_ctx = c.drawctxt_id;
    struct pn_kgsl_gpumem_alloc_id a = {0}; a.size = 4096;
    r = ioc(g_fd, PN_KGSL_GPUMEM_ALLOC_ID, &a);
    if (r || !a.gpuaddr) { PLOG("GPU clock floor beat: no command buffer (%d): off", r); goto fail; }
    g_buf_id = a.id; g_buf_va = a.gpuaddr;
    void *m = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, g_fd, (off_t)a.id << 12);
    if (m == MAP_FAILED) { PLOG("GPU clock floor beat: command buffer not mapped (%d): off", errno); goto fail; }
    for (int i = 0; i < 4; i++) ((volatile uint32_t *)m)[i] = PN_CP_NOP;
    munmap(m, 4096);
    unsigned lv = PN_PWR_MAX; struct pn_kgsl_device_constraint dc = {PN_CONSTRAINT_PWRLEVEL, g_ctx, &lv, sizeof lv}; struct pn_kgsl_setprop sp = {PN_PROP_PWR_CONSTRAINT, &dc, sizeof dc};
    r = ioc(g_fd, PN_KGSL_SETPROPERTY, &sp);
    if (r) { PLOG("GPU clock floor beat: the constraint was refused (%d): off", r); goto fail; }
    PLOG("GPU clock floor beat: kernel context %u with KGSL_CONTEXT_PWR_CONSTRAINT, highest clock; one CP_NOP per replayed submission, at most every %d ms (PN_GPU_FLOOR_BEAT=0: off)",
         g_ctx, env_int("PN_GPU_FLOOR_BEAT_MS", 8));
    return 1;
fail:
    close(g_fd); g_fd = -1;   // (closing the descriptor destroys its context and frees its memory)
    return -1;
}

// vka_dev.c vka_gpu_floor, at each replayed submission's start, on kernels that take only the "highest" constraint: pct is DM_VK_GPU_FLOOR's level (0: no floor)
void peanuts_gpu_floor_beat(int pct) {
    if (pct <= 0 || g_state < 0 || !env_int("PN_GPU_FLOOR_BEAT", 1)) return;   // (live through DM_CTL "env": the floor lapses within the kernel's interval)
    uint64_t now = mono_ns();
    if (g_state && now - g_last_ns < (uint64_t)env_int("PN_GPU_FLOOR_BEAT_MS", 8) * 1000000ull) return;
    pthread_mutex_lock(&g_mu);
    if (!g_state) g_state = setup();
    if (g_state > 0) {
        struct pn_kgsl_command_object o = {0, g_buf_va, 4, PN_CMDLIST_IB, g_buf_id};
        struct pn_kgsl_gpu_command gc = {0}; gc.flags = PN_PWR_CONSTRAINT; gc.cmdlist = (uint64_t)(uintptr_t)&o; gc.cmdsize = sizeof o; gc.numcmds = 1; gc.context_id = g_ctx;
        int r = ioc(g_fd, PN_KGSL_GPU_COMMAND, &gc);
        g_last_ns = now;
        if (r) { if (g_fail++ < 4) PLOG("GPU clock floor beat: submit refused (%d)%s", r, g_fail >= 4 ? ": off" : ""); if (g_fail >= 4) g_state = -1; }
        else if (++g_beats == 1) PLOG("GPU clock floor beat: first command submitted (timestamp %u)", gc.timestamp);
    }
    pthread_mutex_unlock(&g_mu);
}

#endif
