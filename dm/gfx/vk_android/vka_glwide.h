// PEANUTS: the GL layer's wrappers of the OpenGL ES 3.2 functions with more than two stack-passed arguments (vka_gl.c), free of GL's headers so dm/host/tests/t_bigstack.c can check them.
// dm_bigstack_enter carries the caller's registers and its first two stack slots onto the big host stack (hle_bigstack.c), which is not enough for these (AAPCS64: integers and
// pointers in x0-x7, the rest one 8-byte slot each on the caller's stack, a 32-bit value in its low half). The stub VKG_STK_STUB makes gives the implementation the address of
// the caller's stack arguments as a 9th argument instead (in its own frame's first slot, which dm_bigstack_enter carries), on either path (switched to the big host stack, or
// straight through off the guest block); the implementation reads them from there and calls the system's function with all of them
#ifndef VKA_GLWIDE_H
#define VKA_GLWIDE_H
#include <stdint.h>

// fn##_bs: fn(x0, ..., x7, the caller's stack arguments) on the big host stack. Needs plat.h (hle.h) for the PLAT_ASM_ names
#define VKG_STK_STUB(fn) extern const char fn##_bs[];                                                                                                  \
    __asm__(".text\n.p2align 2\n.globl " PLAT_ASM_SYM(fn##_bs) "\n" PLAT_ASM_HIDDEN " " PLAT_ASM_SYM(fn##_bs) "\n" PLAT_ASM_SYM(fn##_bs) ":\n"             \
            "    sub sp, sp, #32\n    stp x29, x30, [sp, #16]\n    add x29, sp, #16\n"                                                                      \
            "    add x9, sp, #32\n    str x9, [sp]\n"   /* the caller's sp: its stack arguments */                                                          \
            "    adrp x16, " PLAT_ASM_PAGE(fn) "\n    add x16, x16, " PLAT_ASM_PAGEOFF(fn) "\n    bl " PLAT_ASM_SYM(dm_bigstack_enter) "\n"                    \
            "    ldp x29, x30, [sp, #16]\n    add sp, sp, #32\n    ret\n")

// The prototypes of gl32.h (GLenum, GLuint: uint32_t; GLint, GLsizei: int32_t); vka_gl.c checks them against the NDK's
typedef void (*vkg_tex_sub_image_3d_fn)(uint32_t target, int32_t level, int32_t xoffset, int32_t yoffset, int32_t zoffset, int32_t width, int32_t height, int32_t depth,
                                        uint32_t format, uint32_t type, const void *pixels);
typedef void (*vkg_compressed_tex_sub_image_3d_fn)(uint32_t target, int32_t level, int32_t xoffset, int32_t yoffset, int32_t zoffset, int32_t width, int32_t height, int32_t depth,
                                                   uint32_t format, int32_t imageSize, const void *data);
typedef void (*vkg_copy_image_sub_data_fn)(uint32_t srcName, uint32_t srcTarget, int32_t srcLevel, int32_t srcX, int32_t srcY, int32_t srcZ, uint32_t dstName, uint32_t dstTarget,
                                           int32_t dstLevel, int32_t dstX, int32_t dstY, int32_t dstZ, int32_t srcWidth, int32_t srcHeight, int32_t srcDepth);

// f with the eight register arguments and the caller's stack arguments stk (3 for the two TexSubImage3D, 7 for CopyImageSubData)
static inline void vkg_tex_sub_image_3d(vkg_tex_sub_image_3d_fn f, uint32_t target, int32_t level, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d, const uint64_t *stk) {
    f(target, level, x, y, z, w, h, d, (uint32_t)stk[0], (uint32_t)stk[1], (const void *)(uintptr_t)stk[2]);
}
static inline void vkg_compressed_tex_sub_image_3d(vkg_compressed_tex_sub_image_3d_fn f, uint32_t target, int32_t level, int32_t x, int32_t y, int32_t z, int32_t w, int32_t h, int32_t d,
                                                   const uint64_t *stk) {
    f(target, level, x, y, z, w, h, d, (uint32_t)stk[0], (int32_t)(uint32_t)stk[1], (const void *)(uintptr_t)stk[2]);
}
static inline void vkg_copy_image_sub_data(vkg_copy_image_sub_data_fn f, uint32_t sname, uint32_t starget, int32_t slevel, int32_t sx, int32_t sy, int32_t sz, uint32_t dname,
                                           uint32_t dtarget, const uint64_t *stk) {
    f(sname, starget, slevel, sx, sy, sz, dname, dtarget, (int32_t)(uint32_t)stk[0], (int32_t)(uint32_t)stk[1], (int32_t)(uint32_t)stk[2], (int32_t)(uint32_t)stk[3],
      (int32_t)(uint32_t)stk[4], (int32_t)(uint32_t)stk[5], (int32_t)(uint32_t)stk[6]);
}
#endif
