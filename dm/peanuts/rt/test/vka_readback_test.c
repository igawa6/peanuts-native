// vka_readback_test: the replay's texture readback (dm/gfx/vk_android/vka_readback.inc) compiled on a PC and driven through its real state machine.
// The file is included as vka_tex.c includes it, after stand-ins for the little it uses: a Vulkan function table whose buffers are host memory and whose command
// buffer is a list of copies this test "runs on the GPU" when it says so, a live list of fake images, the texture cache's lock, and the fences' counters.
// Covered: submit / record / submitted / poll / hand-over; a request that waits for its image's batch; the generation a copy reports; the timeout's reclaim by the
// asker; every failure (no image, a format with no copy, a rescaled image, no buffer, a failed submission, a fence wait that ends in an error, a hung device);
// the hold and its count; the buffers kept and freed.
#define _GNU_SOURCE
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// ---- Vulkan, as far as the file names it ----
typedef struct fake_image *VkImage; typedef struct fake_buffer *VkBuffer; typedef struct fake_buffer *VkDeviceMemory; typedef void *VkFence; typedef struct fake_cb *VkCommandBuffer; typedef void *VkDevice;
typedef uint64_t VkDeviceSize; typedef uint32_t VkFlags;
typedef enum { VK_SUCCESS = 0, VK_TIMEOUT = 2, VK_ERROR_DEVICE_LOST = -4 } VkResult;
typedef enum { VK_FORMAT_R8_UNORM = 9, VK_FORMAT_R8G8B8A8_UNORM = 37, VK_FORMAT_R8G8B8A8_SRGB = 43, VK_FORMAT_B8G8R8A8_UNORM = 44, VK_FORMAT_B8G8R8A8_SRGB = 50,
               VK_FORMAT_A8B8G8R8_UNORM_PACK32 = 51, VK_FORMAT_A8B8G8R8_SRGB_PACK32 = 57, VK_FORMAT_BC1_RGBA_UNORM_BLOCK = 133 } VkFormat;
#define VK_NULL_HANDLE NULL
#define VK_WHOLE_SIZE (~0ull)
enum { VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO = 12, VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO = 5, VK_STRUCTURE_TYPE_MEMORY_BARRIER = 46 };
enum { VK_BUFFER_USAGE_TRANSFER_DST_BIT = 2, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 2, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 4, VK_MEMORY_PROPERTY_HOST_CACHED_BIT = 8,
       VK_IMAGE_ASPECT_COLOR_BIT = 1, VK_IMAGE_LAYOUT_GENERAL = 1, VK_ACCESS_TRANSFER_WRITE_BIT = 0x1000, VK_ACCESS_HOST_READ_BIT = 0x2000,
       VK_PIPELINE_STAGE_TRANSFER_BIT = 0x1000, VK_PIPELINE_STAGE_HOST_BIT = 0x4000 };
typedef struct { int sType; const void *pNext; VkFlags flags; VkDeviceSize size; VkFlags usage; } VkBufferCreateInfo;
typedef struct { VkDeviceSize size, alignment; uint32_t memoryTypeBits; } VkMemoryRequirements;
typedef struct { int sType; const void *pNext; VkDeviceSize allocationSize; uint32_t memoryTypeIndex; } VkMemoryAllocateInfo;
typedef struct { VkDeviceSize bufferOffset; uint32_t bufferRowLength, bufferImageHeight; struct { VkFlags aspectMask; uint32_t mipLevel, baseArrayLayer, layerCount; } imageSubresource;
                 struct { int32_t x, y, z; } imageOffset; struct { uint32_t width, height, depth; } imageExtent; } VkBufferImageCopy;
typedef struct { int sType; const void *pNext; VkFlags srcAccessMask, dstAccessMask; } VkMemoryBarrier;

// The fake GPU: an image is its texels; a buffer is host memory; a command buffer is the copies recorded into it
struct fake_image { uint8_t *px; uint32_t w, h, bpp; };
struct fake_buffer { uint8_t *mem; VkDeviceSize size; };
struct fake_cb { int n; struct { VkImage img; VkBuffer buf; uint32_t w, h; } copy[4]; int barriers; };
static int g_buffers_live, g_fail_buffer;
static uint64_t g_buffer_bytes;
static VkResult f_CreateBuffer(VkDevice d, const VkBufferCreateInfo *ci, const void *a, VkBuffer *out) {
    (void)d; (void)a; if (g_fail_buffer) return VK_ERROR_DEVICE_LOST;
    *out = calloc(1, sizeof **out); (*out)->size = ci->size; g_buffers_live++; g_buffer_bytes += ci->size; return VK_SUCCESS;
}
static void f_GetBufferMemoryRequirements(VkDevice d, VkBuffer b, VkMemoryRequirements *mr) { (void)d; mr->size = b->size; mr->alignment = 1; mr->memoryTypeBits = 1; }
static VkResult f_AllocateMemory(VkDevice d, const VkMemoryAllocateInfo *ai, const void *a, VkDeviceMemory *out) { (void)d; (void)a; (void)ai; *out = NULL; return VK_SUCCESS; }
static VkResult f_BindBufferMemory(VkDevice d, VkBuffer b, VkDeviceMemory m, VkDeviceSize o) { (void)d; (void)m; (void)o; b->mem = malloc(b->size); memset(b->mem, 0xEE, b->size); return VK_SUCCESS; }
static VkBuffer g_last_buffer;
static VkResult f_MapMemory(VkDevice d, VkDeviceMemory m, VkDeviceSize o, VkDeviceSize n, VkFlags f, void **out) { (void)d; (void)m; (void)o; (void)n; (void)f; *out = g_last_buffer->mem; return VK_SUCCESS; }
static void f_UnmapMemory(VkDevice d, VkDeviceMemory m) { (void)d; (void)m; }
static void f_DestroyBuffer(VkDevice d, VkBuffer b, const void *a) { (void)d; (void)a; g_buffers_live--; g_buffer_bytes -= b->size; free(b->mem); free(b); }
static void f_FreeMemory(VkDevice d, VkDeviceMemory m, const void *a) { (void)d; (void)m; (void)a; }
static void f_CmdCopyImageToBuffer(VkCommandBuffer cb, VkImage img, int layout, VkBuffer buf, uint32_t n, const VkBufferImageCopy *r) {
    (void)layout; (void)n; cb->copy[cb->n].img = img; cb->copy[cb->n].buf = buf; cb->copy[cb->n].w = r->imageExtent.width; cb->copy[cb->n].h = r->imageExtent.height; cb->n++;
}
static void f_CmdPipelineBarrier(VkCommandBuffer cb, VkFlags a, VkFlags b, VkFlags c, uint32_t n, const VkMemoryBarrier *mb, uint32_t n2, const void *p2, uint32_t n3, const void *p3) {
    (void)a; (void)b; (void)c; (void)n; (void)mb; (void)n2; (void)p2; (void)n3; (void)p3; cb->barriers++;
}
// (the buffer the next vkMapMemory maps: the file creates, binds and maps in a row)
static VkResult f_CreateBuffer_note(VkDevice d, const VkBufferCreateInfo *ci, const void *a, VkBuffer *out) { VkResult r = f_CreateBuffer(d, ci, a, out); if (!r) g_last_buffer = *out; return r; }
static struct {
    VkResult (*vkCreateBuffer)(VkDevice, const VkBufferCreateInfo *, const void *, VkBuffer *);
    void (*vkGetBufferMemoryRequirements)(VkDevice, VkBuffer, VkMemoryRequirements *);
    VkResult (*vkAllocateMemory)(VkDevice, const VkMemoryAllocateInfo *, const void *, VkDeviceMemory *);
    VkResult (*vkBindBufferMemory)(VkDevice, VkBuffer, VkDeviceMemory, VkDeviceSize);
    VkResult (*vkMapMemory)(VkDevice, VkDeviceMemory, VkDeviceSize, VkDeviceSize, VkFlags, void **);
    void (*vkUnmapMemory)(VkDevice, VkDeviceMemory);
    void (*vkDestroyBuffer)(VkDevice, VkBuffer, const void *);
    void (*vkFreeMemory)(VkDevice, VkDeviceMemory, const void *);
    void (*vkCmdCopyImageToBuffer)(VkCommandBuffer, VkImage, int, VkBuffer, uint32_t, const VkBufferImageCopy *);
    void (*vkCmdPipelineBarrier)(VkCommandBuffer, VkFlags, VkFlags, VkFlags, uint32_t, const VkMemoryBarrier *, uint32_t, const void *, uint32_t, const void *);
} vk = {f_CreateBuffer_note, f_GetBufferMemoryRequirements, f_AllocateMemory, f_BindBufferMemory, f_MapMemory, f_UnmapMemory, f_DestroyBuffer, f_FreeMemory, f_CmdCopyImageToBuffer, f_CmdPipelineBarrier};
static struct { VkDevice dev; } V;

// ---- the texture cache, as far as the file reads it ----
enum { NVN_TEXTURE_TARGET_2D = 1 };
typedef struct vka_img {
    uint64_t serial; const uint8_t *mem; struct { int width, height, target; } d; struct { VkFormat vk; uint8_t srgb; } f;
    VkImage img; VkFlags aspect; uint32_t levels, layers, samples, depth, base; void *rs; const void *vpool; int is_video, inited, cpu_dirty, refs, holds; struct vka_img *par;   // par: a level child (vka.h; never one here)
    uint64_t linger, last_frame, last_fence; uint8_t rsc; int pool_dead;
    uint64_t rb_gen, rb_fence, rb_pgen, rb_pfence, rb_frame; uint8_t rb_rt, rb_redrawn, rb_pok, rb_srgb; uint16_t rb_holds;
} vka_img;
static pthread_mutex_t g_tmu = PTHREAD_MUTEX_INITIALIZER;
static vka_img *g_live[64]; static int g_nlive;
static int img_pool_dead(vka_img *im) { return im->pool_dead; }
static void im_unhold_locked(vka_img *im) { if (im->holds > 0) im->holds--; }
static void unref_locked(vka_img *im) { im->refs--; }
static uint64_t g_frame, g_fence_last, g_fence_queued, g_now; static int g_ok = 1, g_hung, g_barriers;
static uint64_t vka_frame(void) { return g_frame; }
static uint64_t vka_fence_last(void) { return g_fence_last; }
static uint64_t vka_fence_queued(void) { return g_fence_queued; }
static uint64_t now_ns(void) { return g_now; }
static int vka_ok(void) { return g_ok; }
static int vka_hung(void) { return g_hung; }
static uint32_t vka_mem_type(uint32_t bits, VkFlags want, VkFlags avoid) { (void)bits; (void)avoid; return want & VK_MEMORY_PROPERTY_HOST_CACHED_BIT ? UINT32_MAX : 0; }   // (no cached type: the fallback is taken)
static void vka_barrier(VkCommandBuffer cb) { (void)cb; g_barriers++; }
static char g_log[16384];
#define VLOG(...) do { size_t l_ = strlen(g_log); snprintf(g_log + l_, sizeof g_log - l_, __VA_ARGS__); l_ = strlen(g_log); snprintf(g_log + l_, sizeof g_log - l_, "\n"); } while (0)
#define VKA_ENV(key, def) (1)

#include "../../../gfx/vk_android/vka_readback.inc"

// ---- the test ----
static int g_checks, g_failed;
#define CHECK(c) do { g_checks++; if (!(c)) { g_failed++; printf("FAIL line %d: %s\n", __LINE__, #c); } } while (0)

typedef struct { _Atomic int calls; int status; uint64_t addr, gen; uint32_t cands; uint8_t first[8]; size_t row; int bgra; pthread_t thread; } answer;
static void on_done(void *user, const vka_rb_result *r) {
    answer *a = user; a->status = r->status; a->addr = r->addr; a->gen = r->generation; a->cands = r->candidates; a->row = r->row_bytes; a->bgra = r->bgra; a->thread = pthread_self();
    if (r->rows) memcpy(a->first, r->rows, 8);
    atomic_fetch_add(&a->calls, 1);
}
// The asker's rule here: the newest plain, current candidate (none: not found)
static int g_pick_calls;
static vka_rb_pick on_pick(void *user, const vka_rb_cand *c, uint32_t n) {
    (void)user; g_pick_calls++;
    vka_rb_pick p = {VKA_RB_NOT_FOUND, 0, 0};
    for (uint32_t i = 0; i < n; i++) {
        if (!c[i].plain_2d || !c[i].current) continue;
        if (p.status != VKA_RB_READY || c[i].generation > c[p.index].generation) { p.status = VKA_RB_READY; p.index = i; }
        p.candidates++;
    }
    return p;
}
static vka_img *add_image(uint64_t addr, int w, int h, VkFormat f, int srgb, uint8_t seed) {
    vka_img *im = calloc(1, sizeof *im); struct fake_image *fi = calloc(1, sizeof *fi);
    fi->w = (uint32_t)w; fi->h = (uint32_t)h; fi->bpp = f == VK_FORMAT_R8_UNORM ? 1 : 4; fi->px = malloc((size_t)w * h * fi->bpp); memset(fi->px, seed, (size_t)w * h * fi->bpp);
    im->serial = addr >> 12; im->mem = (const uint8_t *)(uintptr_t)addr; im->d.width = w; im->d.height = h; im->d.target = NVN_TEXTURE_TARGET_2D; im->f.vk = f; im->f.srgb = (uint8_t)srgb;
    im->img = fi; im->aspect = VK_IMAGE_ASPECT_COLOR_BIT; im->levels = im->layers = im->samples = im->depth = 1; im->inited = 1; im->refs = 1;
    g_live[g_nlive++] = im; return im;
}
static void gpu_run(struct fake_cb *cb) {   // the present's submission executes: each copy takes the image's texels as they are now
    for (int i = 0; i < cb->n; i++) memcpy(cb->copy[i].buf->mem, cb->copy[i].img->px, (size_t)cb->copy[i].w * cb->copy[i].h * cb->copy[i].img->bpp);
}
static int wait_calls(answer *a, int n) { for (int i = 0; i < 2000 && atomic_load(&a->calls) < n; i++) usleep(1000); return atomic_load(&a->calls) == n; }
static int wait_free(void) { for (int i = 0; i < 2000 && atomic_load(&vka_rb_on); i++) usleep(1000); return atomic_load(&vka_rb_on) == 0; }
static vka_rb_request request(uint64_t addr, uint32_t w, uint32_t h, uint32_t format, answer *a) { vka_rb_request r = {addr, w, h, format, on_pick, on_done, a}; return r; }
// One present on frame slot `fence`: poll (the wait for that fence ended with `waited`), record, the submission (ok or failed), and the GPU runs it
static void present(VkFence fence, VkResult waited, int submit_ok, struct fake_cb *cb) {
    memset(cb, 0, sizeof *cb);
    if (atomic_load(&vka_rb_on)) { vka_rb_poll(fence, waited); vka_rb_record(cb); vka_rb_submitted(fence, submit_ok); }
    if (submit_ok) gpu_run(cb);
}

int main(void) {
    int f0, f1, f2; VkFence F0 = &f0, F1 = &f1, F2 = &f2; struct fake_cb cb;
    pthread_t me = pthread_self();
    vka_img *fog = add_image(0x900000, 256, 256, VK_FORMAT_R8G8B8A8_UNORM, 1, 0x11);
    vka_img *bgra = add_image(0xA00000, 64, 64, VK_FORMAT_B8G8R8A8_UNORM, 0, 0x22);
    vka_img *bc = add_image(0xB00000, 128, 128, VK_FORMAT_BC1_RGBA_UNORM_BLOCK, 0, 0x33);
    vka_img *big = add_image(0xC00000, 1024, 512, VK_FORMAT_R8G8B8A8_UNORM, 0, 0x44);   // 2 MiB
    (void)bc;
    g_fence_last = 10; g_fence_queued = 10; g_now = 1000000000ull;

    // Nothing asked: no note is kept, the present's hooks do not run
    CHECK(!atomic_load(&vka_rb_on) && !atomic_load(&vka_rb_track));

    // 1. The first request: it waits until what was recorded before the notes began is on the queue, then rides a present and comes back two presents later
    answer a1 = {0}; vka_rb_request r1 = request(0x900000, 256, 256, VKA_RB_RGBA8, &a1);
    CHECK(vka_rb_submit(&r1) == 1 && atomic_load(&vka_rb_on) == 1 && atomic_load(&vka_rb_track) == 1);
    present(F0, VK_SUCCESS, 1, &cb);
    CHECK(cb.n == 0 && g_pick_calls == 0 && a1.calls == 0);                   // batches 11 and 12 may hold writes nobody noted
    g_fence_last = 12; g_fence_queued = 12;
    present(F1, VK_SUCCESS, 1, &cb);
    CHECK(cb.n == 1 && cb.barriers == 1 && g_barriers == 1 && fog->holds == 1 && fog->rb_holds == 1 && fog->refs == 2 && a1.calls == 0);
    present(F2, VK_SUCCESS, 1, &cb);
    CHECK(a1.calls == 0 && cb.n == 0);                                        // another frame slot's fence: not this copy's, and no fence is asked
    present(F0, VK_SUCCESS, 1, &cb);
    CHECK(a1.calls == 0 && fog->holds == 1);
    present(F1, VK_SUCCESS, 1, &cb);                                          // the present has waited for the copy's fence
    CHECK(wait_calls(&a1, 1) && a1.status == VKA_RB_READY && a1.addr == 0x900000 && a1.first[0] == 0x11 && a1.row == 256 * 4 && !a1.bgra && a1.cands == 1);
    CHECK(!pthread_equal(a1.thread, me));                                     // handed over on the worker, not the present thread
    CHECK(wait_free() && fog->holds == 0 && fog->rb_holds == 0 && fog->refs == 1 && g_buffers_live == 1);   // a 256 KiB buffer is kept for the next request

    // 2. Writes noted from now on. A write in a batch not on the queue: the copy reports the generation before it, with the pixels the GPU has
    pthread_mutex_lock(&g_tmu); vka_rb_color = 2; g_frame = 5; rb_written_locked(fog); pthread_mutex_unlock(&g_tmu);   // batch 13, on the queue below
    g_fence_last = 13; g_fence_queued = 13;
    const uint64_t gen13 = fog->rb_gen;
    pthread_mutex_lock(&g_tmu); vka_rb_color = 2; g_frame = 6; rb_written_locked(fog); pthread_mutex_unlock(&g_tmu);   // batch 14, still being recorded
    CHECK(fog->rb_rt && fog->rb_redrawn && fog->rb_srgb == 2 && fog->rb_gen == gen13 + 1 && fog->rb_fence == 14 && fog->rb_pfence == 13 && vka_rb_color == 0);
    answer a2 = {0}; vka_rb_request r2 = request(0x900000, 256, 256, VKA_RB_RGBA8, &a2);
    CHECK(vka_rb_submit(&r2) == 1);
    present(F0, VK_SUCCESS, 1, &cb);
    CHECK(cb.n == 1);
    memset(fog->img->px, 0x99, 16);                                           // batch 14 runs after the present's copy
    present(F1, VK_SUCCESS, 1, &cb); present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_SUCCESS, 1, &cb);
    CHECK(wait_calls(&a2, 1) && a2.status == VKA_RB_READY && a2.gen == gen13 && a2.first[0] == 0x11);   // never a generation newer than the pixels
    CHECK(wait_free());
    // ... and once batch 14 is on the queue, the new generation with the new pixels
    g_fence_last = 14; g_fence_queued = 14;
    answer a3 = {0}; vka_rb_request r3 = request(0x900000, 256, 256, VKA_RB_RGBA8, &a3);
    CHECK(vka_rb_submit(&r3) == 1);
    present(F1, VK_SUCCESS, 1, &cb); present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_SUCCESS, 1, &cb); present(F1, VK_SUCCESS, 1, &cb);
    CHECK(wait_calls(&a3, 1) && a3.gen == gen13 + 1 && a3.first[0] == 0x99 && wait_free());
    // An image made in a batch still being recorded (no layout yet on the GPU) is not copied until that batch is on the queue
    vka_img *fresh = add_image(0xD00000, 32, 32, VK_FORMAT_R8_UNORM, 0, 0x55);
    pthread_mutex_lock(&g_tmu); rb_created_locked(fresh); pthread_mutex_unlock(&g_tmu);   // batch 15
    answer a4 = {0}; vka_rb_request r4 = request(0xD00000, 32, 32, VKA_RB_R8, &a4);
    CHECK(vka_rb_submit(&r4) == 1);
    present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_SUCCESS, 1, &cb);
    CHECK(cb.n == 0 && a4.calls == 0 && fresh->holds == 0 && atomic_load(&vka_rb_on) == 1);   // stays queued
    g_fence_last = 15; g_fence_queued = 15;
    present(F1, VK_SUCCESS, 1, &cb);
    CHECK(cb.n == 1 && fresh->holds == 1);
    present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_SUCCESS, 1, &cb); present(F1, VK_SUCCESS, 1, &cb);
    CHECK(wait_calls(&a4, 1) && a4.status == VKA_RB_READY && a4.first[0] == 0x55 && a4.row == 32 && wait_free());

    // 3. The format class follows the view a colour target is drawn through; B, G, R, A rows are said to be so
    pthread_mutex_lock(&g_tmu); vka_rb_color = 1; rb_written_locked(bgra); pthread_mutex_unlock(&g_tmu);
    g_fence_last = 16; g_fence_queued = 16;
    answer a5 = {0}; vka_rb_request r5 = request(0xA00000, 64, 64, VKA_RB_RGBA8, &a5);
    CHECK(vka_rb_submit(&r5) == 1);
    present(F0, VK_SUCCESS, 1, &cb); present(F1, VK_SUCCESS, 1, &cb); present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_SUCCESS, 1, &cb);
    CHECK(wait_calls(&a5, 1) && a5.status == VKA_RB_READY && a5.bgra == 1 && wait_free());

    // 4. Failures, each with its status and nothing left behind
    answer e1 = {0}, e2 = {0}, e3 = {0}, e4 = {0}, e5 = {0}, e6 = {0}, e7 = {0};
    vka_rb_request q1 = request(0xEEE000, 16, 16, VKA_RB_RGBA8, &e1);          // nothing there
    CHECK(vka_rb_submit(&q1) == 1); present(F1, VK_SUCCESS, 1, &cb);
    CHECK(e1.calls == 1 && e1.status == VKA_RB_NOT_FOUND && cb.n == 0 && pthread_equal(e1.thread, me) && atomic_load(&vka_rb_on) == 0);
    vka_rb_request q2 = request(0xB00000, 128, 128, VKA_RB_RGBA8, &e2);        // block-compressed: the picker here takes it, the copy has no layout for it
    CHECK(vka_rb_submit(&q2) == 1); present(F2, VK_SUCCESS, 1, &cb);
    CHECK(e2.calls == 1 && e2.status == VKA_RB_UNSUPPORTED && cb.n == 0 && atomic_load(&vka_rb_on) == 0);
    fog->rsc = 1;                                                              // a rescaled image
    vka_rb_request q3 = request(0x900000, 256, 256, VKA_RB_RGBA8, &e3);
    CHECK(vka_rb_submit(&q3) == 1); present(F0, VK_SUCCESS, 1, &cb);
    CHECK(e3.calls == 1 && e3.status == VKA_RB_UNSUPPORTED && cb.n == 0 && fog->holds == 0 && fog->rb_holds == 0 && fog->refs == 1);
    fog->rsc = 0;
    g_fail_buffer = 1;                                                         // no host-visible buffer (a new size is needed: 2 MiB)
    vka_rb_request q4 = request(0xC00000, 1024, 512, VKA_RB_RGBA8, &e4);
    CHECK(vka_rb_submit(&q4) == 1); present(F1, VK_SUCCESS, 1, &cb);
    CHECK(e4.calls == 1 && e4.status == VKA_RB_FAILED && cb.n == 0 && big->holds == 0 && big->refs == 1);
    g_fail_buffer = 0;
    vka_rb_request q5 = request(0x900000, 256, 256, VKA_RB_RGBA8, &e5);        // the present's submission fails
    CHECK(vka_rb_submit(&q5) == 1); present(F2, VK_SUCCESS, 0, &cb);
    CHECK(e5.calls == 1 && e5.status == VKA_RB_FAILED && fog->holds == 0 && atomic_load(&vka_rb_on) == 0);
    vka_rb_request q6 = request(0x900000, 256, 256, VKA_RB_RGBA8, &e6);        // the wait for the copy's fence ends in an error: nothing is handed over as pixels
    CHECK(vka_rb_submit(&q6) == 1); present(F0, VK_SUCCESS, 1, &cb); CHECK(cb.n == 1);
    present(F1, VK_SUCCESS, 1, &cb); present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_ERROR_DEVICE_LOST, 1, &cb);
    CHECK(e6.calls == 1 && e6.status == VKA_RB_FAILED && pthread_equal(e6.thread, me) && fog->holds == 0 && atomic_load(&vka_rb_on) == 0);
    g_hung = 1;                                                                // a hung device takes nothing
    vka_rb_request q7 = request(0x900000, 256, 256, VKA_RB_RGBA8, &e7);
    CHECK(!vka_rb_available() && vka_rb_submit(&q7) == 0 && e7.calls == 0);
    g_hung = 0;
    vka_rb_request bad = request(0x900000, 4096, 16, VKA_RB_RGBA8, &e7);
    CHECK(vka_rb_submit(&bad) == 0);

    // 5. A large copy: its buffer is freed when it has been handed over; the small one stays
    answer b1 = {0}; vka_rb_request rb1 = request(0xC00000, 1024, 512, VKA_RB_RGBA8, &b1);
    CHECK(vka_rb_submit(&rb1) == 1);
    present(F1, VK_SUCCESS, 1, &cb);
    CHECK(cb.n == 1 && g_buffer_bytes >= 2u << 20);
    present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_SUCCESS, 1, &cb); present(F1, VK_SUCCESS, 1, &cb);
    CHECK(wait_calls(&b1, 1) && b1.status == VKA_RB_READY && b1.first[7] == 0x44 && wait_free() && g_buffer_bytes <= 1u << 20);

    // 6. The game stops presenting: both slots fill, a third request is refused, and after the timeout the asker itself answers the stale ones and takes a slot
    answer s1 = {0}, s2 = {0}, s3 = {0}, s4 = {0};
    vka_rb_request t1 = request(0x900000, 256, 256, VKA_RB_RGBA8, &s1), t2 = request(0xA00000, 64, 64, VKA_RB_RGBA8, &s2), t3 = request(0x900000, 256, 256, VKA_RB_RGBA8, &s3);
    CHECK(vka_rb_submit(&t1) == 1 && vka_rb_submit(&t2) == 1 && vka_rb_submit(&t3) == 0 && s3.calls == 0);
    g_now += 1999000000ull;
    CHECK(vka_rb_submit(&t3) == 0 && s1.calls == 0);                           // not yet
    g_now += 2000000ull;
    CHECK(vka_rb_submit(&t3) == 1 && s1.calls == 1 && s1.status == VKA_RB_FAILED && s2.calls == 1 && s2.status == VKA_RB_FAILED && pthread_equal(s1.thread, me));
    CHECK(atomic_load(&vka_rb_on) == 1);
    vka_rb_request t4 = request(0xA00000, 64, 64, VKA_RB_RGBA8, &s4);
    CHECK(vka_rb_submit(&t4) == 1);
    // The presents come back: the two new requests are served as any other
    present(F2, VK_SUCCESS, 1, &cb);
    CHECK(cb.n == 2 && cb.barriers == 1);
    present(F0, VK_SUCCESS, 1, &cb); present(F1, VK_SUCCESS, 1, &cb); present(F2, VK_SUCCESS, 1, &cb);
    CHECK(wait_calls(&s3, 1) && wait_calls(&s4, 1) && s3.status == VKA_RB_READY && s4.status == VKA_RB_READY && wait_free());
    CHECK(fog->holds == 0 && fog->refs == 1 && bgra->holds == 0 && g_buffers_live <= 2 && g_buffer_bytes <= 2u << 20);
    // A copy in flight when the presents stop keeps its slot and its image's hold until the next present (it cannot know its fence is done): the other slot serves
    answer h1 = {0}, h2 = {0};
    vka_rb_request u1 = request(0x900000, 256, 256, VKA_RB_RGBA8, &h1), u2 = request(0xA00000, 64, 64, VKA_RB_RGBA8, &h2);
    CHECK(vka_rb_submit(&u1) == 1); present(F0, VK_SUCCESS, 1, &cb); CHECK(cb.n == 1 && fog->holds == 1);
    g_now += 5000000000ull;
    CHECK(vka_rb_submit(&u2) == 1 && h1.calls == 0 && fog->holds == 1);       // (a copy in flight is not a stale request)
    present(F1, VK_SUCCESS, 1, &cb); present(F2, VK_SUCCESS, 1, &cb); present(F0, VK_SUCCESS, 1, &cb); present(F1, VK_SUCCESS, 1, &cb);
    CHECK(wait_calls(&h1, 1) && wait_calls(&h2, 1) && h1.status == VKA_RB_READY && h2.status == VKA_RB_READY && wait_free() && fog->holds == 0);

    CHECK(strstr(g_log, "first copy") && strstr(g_log, "waited 2000 ms without a present") && strstr(g_log, "rescaled") && strstr(g_log, "ended with -4"));
    printf("vka_readback_test: %d checks, %d failed: %s\n", g_checks, g_failed, g_failed ? "FAILED" : "OK");
    return g_failed != 0;
}
