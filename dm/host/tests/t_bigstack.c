// Big-stack entry (hle_bigstack.c, hle.h HLE_FORCE_BIG): a take-over called on a small guest stack runs on the thread's host stack with its register arguments intact, and dm_bigstack_caller
// gives back the game's call site both after a switch and straight through off the guest block (the audio Start / Stop / Close log it, PR #721), and a take-over with a 9th and 10th
// integer argument gets them from the caller's stack on either path (glTexImage2D / glTexSubImage2D have nine)   // PEANUTS
// and the GL layer's wrappers of glTexSubImage3D, glCompressedTexSubImage3D (11 arguments) and glCopyImageSubData (15) pass every argument on (vka_glwide.h)   // PEANUTS
#include "../hle.h"
#include <stdio.h>
#include <string.h>   // PEANUTS: memset
#include <sys/mman.h>

static uintptr_t g_lo, g_hi;   // the fake guest stack: 16 KB above a guard page
int dm_guest_as_contains(uintptr_t a) { return a >= g_lo && a < g_hi; }

static uintptr_t g_fp;
static void *g_site;
// 64 KB of locals: four times the fake guest stack, so running on it would fault in the guard page
HLE_FORCE_BIG("_ZN4test5probeEmmd", "unit test", uint64_t, probe, uint64_t a, uint64_t b, double d) {
    volatile char big[64 << 10];
    big[0] = 1; big[sizeof big - 1] = 2;
    g_fp = (uintptr_t)__builtin_frame_address(0);
    g_site = dm_bigstack_caller(__builtin_return_address(0));
    return a * 3 + b + (uint64_t)d + (uint64_t)(big[0] + big[sizeof big - 1]);
}

// call_on(a, b, d, top, fn): calls fn(a, b, d) with sp = top (0: on this stack); _call_on_site is the return address the call leaves, the "game's call site"
uint64_t call_on(uint64_t a, uint64_t b, double d, uintptr_t top, const void *fn);
extern const char call_on_site[];
__asm__(".text\n.p2align 2\n.globl " PLAT_ASM_SYM(call_on) "\n" PLAT_ASM_SYM(call_on) ":\n"   // PEANUTS: PLAT_ASM_SYM, so the test also builds for ELF (Android, Linux arm64)
        "    stp x29, x30, [sp, #-16]!\n    mov x29, sp\n"
        "    cbz x2, 1f\n    mov sp, x2\n"
        "1:  blr x3\n"
        ".globl " PLAT_ASM_SYM(call_on_site) "\n" PLAT_ASM_SYM(call_on_site) ":\n"
        "    mov sp, x29\n    ldp x29, x30, [sp], #16\n    ret\n");

// PEANUTS begin: a take-over with ten integer arguments, the last two passed on the caller's stack (AAPCS64), as a GL wrapper of glTexImage2D gets them
static uint64_t g_args[10];
HLE_FORCE_BIG("_ZN4test7probe10Emmmmmmmmmm", "unit test", uint64_t, probe10, uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4, uint64_t a5, uint64_t a6, uint64_t a7,
              uint64_t a8, uint64_t a9) {
    volatile char big[64 << 10];   // as probe: running on the fake guest stack would fault in its guard page
    big[0] = 1; big[sizeof big - 1] = 2;
    uint64_t v[10] = {a0, a1, a2, a3, a4, a5, a6, a7, a8, a9};
    for (int i = 0; i < 10; i++) g_args[i] = v[i];
    g_fp = (uintptr_t)__builtin_frame_address(0);
    return (uint64_t)(big[0] + big[sizeof big - 1]);
}
// call_on10(a, top, fn): fn(a[0], ..., a[9]) with sp = top (0: on this stack); a[8] and a[9] go in the two stack slots the call leaves at sp
uint64_t call_on10(const uint64_t *a, uintptr_t top, const void *fn);
__asm__(".text\n.p2align 2\n.globl " PLAT_ASM_SYM(call_on10) "\n" PLAT_ASM_SYM(call_on10) ":\n"
        "    stp x29, x30, [sp, #-16]!\n    mov x29, sp\n    mov x9, x0\n    mov x16, x2\n"
        "    cbz x1, 1f\n    mov sp, x1\n"
        "1:  sub sp, sp, #16\n    ldp x10, x11, [x9, #64]\n    stp x10, x11, [sp]\n"
        "    ldp x0, x1, [x9]\n    ldp x2, x3, [x9, #16]\n    ldp x4, x5, [x9, #32]\n    ldp x6, x7, [x9, #48]\n"
        "    blr x16\n"
        "    mov sp, x29\n    ldp x29, x30, [sp], #16\n    ret\n");
static int args_are(const uint64_t *a) { for (int i = 0; i < 10; i++) if (g_args[i] != a[i]) return 0; return 1; }
// PEANUTS end

// PEANUTS begin: the GL layer's wrappers of the ES 3.2 functions with 11 and 15 arguments (vka_glwide.h, vka_gl.c HOOK_STK), each with a callee that keeps what it got
#include "../../gfx/vk_android/vka_glwide.h"
static uint64_t g_got[15];
#define I32(v) ((uint64_t)(int64_t)(v))   /* a signed argument as the callee saw it */
static void got_tsi3(uint32_t t, int32_t l, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d, uint32_t f, uint32_t ty, const void *p) {
    uint64_t v[11] = {t, I32(l), I32(x), I32(y), I32(z), I32(w), I32(h), I32(d), f, ty, (uint64_t)(uintptr_t)p};
    memcpy(g_got, v, sizeof v);
}
static void got_ctsi3(uint32_t t, int32_t l, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d, uint32_t f, int32_t n, const void *p) {
    uint64_t v[11] = {t, I32(l), I32(x), I32(y), I32(z), I32(w), I32(h), I32(d), f, I32(n), (uint64_t)(uintptr_t)p};
    memcpy(g_got, v, sizeof v);
}
static void got_cisd(uint32_t sn, uint32_t st, int32_t sl, int32_t sx, int32_t sy, int32_t sz, uint32_t dn, uint32_t dt, int32_t dl, int32_t dx, int32_t dy, int32_t dz,
                     int32_t w, int32_t h, int32_t d) {
    uint64_t v[15] = {sn, st, I32(sl), I32(sx), I32(sy), I32(sz), dn, dt, I32(dl), I32(dx), I32(dy), I32(dz), I32(w), I32(h), I32(d)};
    memcpy(g_got, v, sizeof v);
}
// The wrappers as vka_gl.c has them, with the callee in place of the system's function; 64 KB of locals as probe, so running on the fake guest stack would fault
#define BIGLOCALS volatile char big[64 << 10]; big[0] = 1; big[sizeof big - 1] = 2; g_fp = (uintptr_t)__builtin_frame_address(0)
static void w_tsi3(uint32_t t, int32_t l, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d, const uint64_t *stk) __attribute__((used));
VKG_STK_STUB(w_tsi3);
static void w_tsi3(uint32_t t, int32_t l, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d, const uint64_t *stk) { BIGLOCALS; vkg_tex_sub_image_3d(got_tsi3, t, l, x, y, z, w, h, d, stk); }
static void w_ctsi3(uint32_t t, int32_t l, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d, const uint64_t *stk) __attribute__((used));
VKG_STK_STUB(w_ctsi3);
static void w_ctsi3(uint32_t t, int32_t l, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d, const uint64_t *stk) { BIGLOCALS; vkg_compressed_tex_sub_image_3d(got_ctsi3, t, l, x, y, z, w, h, d, stk); }
static void w_cisd(uint32_t sn, uint32_t st, int32_t sl, int32_t sx, int32_t sy, int32_t sz, uint32_t dn, uint32_t dt, const uint64_t *stk) __attribute__((used));
VKG_STK_STUB(w_cisd);
static void w_cisd(uint32_t sn, uint32_t st, int32_t sl, int32_t sx, int32_t sy, int32_t sz, uint32_t dn, uint32_t dt, const uint64_t *stk) { BIGLOCALS; vkg_copy_image_sub_data(got_cisd, sn, st, sl, sx, sy, sz, dn, dt, stk); }
// call_onn(a, n, top, fn): fn(a[0], ..., a[n - 1]) with sp = top (0: on this stack), a[8] and later in the stack slots the call leaves at sp (8 < n <= 24)
void call_onn(const uint64_t *a, uint64_t n, uintptr_t top, const void *fn);
__asm__(".text\n.p2align 2\n.globl " PLAT_ASM_SYM(call_onn) "\n" PLAT_ASM_SYM(call_onn) ":\n"
        "    stp x29, x30, [sp, #-16]!\n    mov x29, sp\n    mov x9, x0\n    mov x16, x3\n"
        "    cbz x2, 1f\n    mov sp, x2\n"
        "1:  sub x10, x1, #8\n    add x11, x10, #1\n    lsr x11, x11, #1\n    lsl x11, x11, #4\n    sub sp, sp, x11\n"   // n - 8 slots, 16-byte aligned
        "    mov x12, #0\n"
        "2:  cmp x12, x10\n    b.ge 3f\n    add x13, x12, #8\n    ldr x14, [x9, x13, lsl #3]\n    str x14, [sp, x12, lsl #3]\n    add x12, x12, #1\n    b 2b\n"
        "3:  ldp x0, x1, [x9]\n    ldp x2, x3, [x9, #16]\n    ldp x4, x5, [x9, #32]\n    ldp x6, x7, [x9, #48]\n"
        "    blr x16\n"
        "    mov sp, x29\n    ldp x29, x30, [sp], #16\n    ret\n");
// What the callee must see of a: a 32-bit argument is the low half of its register or slot (the high halves here hold junk, as a caller may leave them), signed ones sign-extended
static int got_is(const uint64_t *a, int n, const char *sign) {
    for (int i = 0; i < n; i++) {
        uint64_t want = sign[i] == 'p' ? a[i] : sign[i] == 'i' ? I32((int32_t)(uint32_t)a[i]) : (uint32_t)a[i];
        if (g_got[i] != want) { fprintf(stderr, "argument %d: %#llx, wanted %#llx\n", i + 1, (unsigned long long)g_got[i], (unsigned long long)want); return 0; }
    }
    return 1;
}
// PEANUTS end

static int checks, fails;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s\n", msg); } } while (0)

int main(void) {
    size_t guard = 16384, size = 16384;
    uint8_t *m = mmap(NULL, guard + size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (m == MAP_FAILED) { perror("mmap"); return 1; }
    mprotect(m, guard, PROT_NONE);
    g_lo = (uintptr_t)m; g_hi = (uintptr_t)m + guard + size;

    // on the guest stack: switched to the host stack, arguments kept, call site given back
    uint64_t r = call_on(5, 7, 2.0, g_hi, probe_bs);
    CHECK(r == 5 * 3 + 7 + 2 + 3, "arguments carried over through the switch");
    CHECK(g_fp && !dm_guest_as_contains(g_fp), "the implementation ran off the guest stack");
    CHECK(g_site == (void *)call_on_site, "dm_bigstack_caller gives the call site back after a switch");

    // a second call on the same thread reuses the thread's host stack
    g_site = NULL; r = call_on(1, 1, 1.0, g_hi, probe_bs);
    CHECK(r == 3 + 1 + 1 + 3 && g_site == (void *)call_on_site, "second switched call");

    // off the guest block: jumped to straight, the return address is already the caller's
    g_site = NULL; r = call_on(2, 0, 0.0, 0, probe_bs);
    CHECK(r == 6 + 3, "arguments on the direct path");
    CHECK(g_site == (void *)call_on_site, "dm_bigstack_caller passes the call site through on the direct path");

    // PEANUTS begin: stack-passed arguments, through the switch and on the direct path
    const uint64_t a10[10] = {11, 22, 33, 44, 55, 66, 77, 88, 0x9999999999999999ull, 0xaaaaaaaaaaaaaaaaull};
    memset(g_args, 0, sizeof g_args); g_fp = 0;
    r = call_on10(a10, g_hi, probe10_bs);
    CHECK(r == 3 && args_are(a10), "a 9th and 10th integer argument carried over through the switch");
    CHECK(g_fp && !dm_guest_as_contains(g_fp), "the ten-argument implementation ran off the guest stack");
    memset(g_args, 0, sizeof g_args);
    r = call_on10(a10, 0, probe10_bs);
    CHECK(r == 3 && args_are(a10), "a 9th and 10th integer argument on the direct path");
    // PEANUTS end

    // PEANUTS begin: every argument of the three wrappers, through the switch and on the direct path (u: GLenum / GLuint, i: GLint / GLsizei, p: a pointer)
    const uint64_t a11[11] = {0xdead00000de1, 0xbeef00000002, 0x77fffffffffd, 0x1100000000000004, 0xffff000000000005, 0x5500000000000106, 0x0000000700000107,
                              0x0123000000000008, 0xabcd000000001908, 0x9999000000001401, 0x00007f0012345678};
    const uint64_t a15[15] = {0xa00000000000000b, 0xb000000000000de1, 0xc000000000000003, 0xd0000000fffffffc, 0xe000000000000005, 0xf000000000000006,
                              0x100000000000000c, 0x2000000000008d41, 0x3000000000000009, 0x400000000000000a, 0x50000000ffffffef, 0x600000000000000d,
                              0x7000000000000100, 0x8000000000000080, 0x9000000000000001};
    const struct { const char *name; const void *fn; const uint64_t *a; int n; const char *sign; } w[] = {
        {"glTexSubImage3D", w_tsi3_bs, a11, 11, "uiiiiiiiuup"},
        {"glCompressedTexSubImage3D", w_ctsi3_bs, a11, 11, "uiiiiiiiuip"},
        {"glCopyImageSubData", w_cisd_bs, a15, 15, "uuiiiiuuiiiiiii"},
    };
    for (int k = 0; k < 3; k++) {
        char msg[128];
        memset(g_got, 0, sizeof g_got); g_fp = 0;
        call_onn(w[k].a, (uint64_t)w[k].n, g_hi, w[k].fn);
        snprintf(msg, sizeof msg, "%s: all %d arguments through the switch, off the guest stack", w[k].name, w[k].n);
        CHECK(got_is(w[k].a, w[k].n, w[k].sign) && g_fp && !dm_guest_as_contains(g_fp), msg);
        memset(g_got, 0, sizeof g_got);
        call_onn(w[k].a, (uint64_t)w[k].n, 0, w[k].fn);
        snprintf(msg, sizeof msg, "%s: all %d arguments on the direct path", w[k].name, w[k].n);
        CHECK(got_is(w[k].a, w[k].n, w[k].sign), msg);
    }
    // PEANUTS end

    printf("big-stack entry: %d/%d checks %s\n", checks - fails, checks, fails ? "✗" : "✓");
    return fails != 0;
}
