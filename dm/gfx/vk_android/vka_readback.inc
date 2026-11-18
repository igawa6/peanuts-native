// PEANUTS: texture readback for the companion runtime, the replay's side (the whole file; included by vka_tex.c, whose table lock and live list it reads; the contract
// and the pure rules are in vka_readback.h). Not a file of its own because the picker walks the texture cache's private live list.
// dm/peanuts/rt/test/vka_readback_test.c compiles this file on a PC against a stub Vulkan table and drives every path below.
//
// The path of one request:
//   vka_rb_submit (any thread, the runtime's tick): claims one of two slots, copies the request, nothing else. No Vulkan call, no lock of the replay's. A request
//     that has sat queued for the runtime's timeout (no present came) is answered FAILED here and its slot taken back, so a game that stops presenting never
//     leaves the slots taken.
//   vka_rb_record (present thread, in the present's command buffer, where DM_VK_IMG_DUMP's diagnostic copy is recorded): under the table lock the live images at the
//     address (or of the size) become candidates and the request's own rule picks one; the image gets a reference and a hold (while held it is not evicted and not
//     given up with its pool; an image that is swapped meanwhile, for storage use, a change of scale or memory of its own, leaves its old VkImage alive until the hold
//     is dropped), and its VkImage handle is read under that same lock. One vkCmdCopyImageToBuffer of level 0 into a host-visible buffer follows, after the barrier that
//     orders it behind the replay's earlier writes. The copy runs on the GPU with the present's own submission: no extra submission, no wait.
//   vka_rb_poll (present thread): when the present comes back to the frame slot whose fence the copy rode on, it has just waited for that fence; with that wait's
//     result the copy is handed to a worker thread, which calls the request's done callback (the conversion of the pixels never runs on the present thread) and frees
//     the slot. No fence is ever queried: a status query blocks on the drivers vka_dev.c probes for.
// What the picker may believe. The present's command buffer goes to the queue ahead of the batch the replay is still recording, but the texture cache's state is
// the recording's: a write noted for an image may not be on the queue yet. Every image therefore carries the fence of the batch that last wrote it (rb_fence, and the
// one before, rb_pfence, each with its generation): a copy reports the newest generation whose batch is on the queue (vka_fence_queued), and a request whose image has
// no such state yet (made, or first written, in a batch still being recorded) stays queued for the next present. So a copy never carries a generation newer than its
// pixels. These notes are kept from a companion's first request on; that request waits until everything recorded before it is on the queue.
// Cost: with no request queued or in flight the present pays one relaxed load and a branch (vka_present.c); a GPU write's note (vka_img_written_at, under the lock it
// already holds) and a new image's first use one each, and a colour target one more at its site (vka_draw.c, vka_exec.c), all false until a companion asks.
// The runtime accepts at most two requests a second and two at once, so the worst case is 2 x 16 MiB a second copied out (a 1600x900 target: 5.8 MB a copy).
// Memory: two buffers at most; one of up to 1 MiB is kept for the next request, a larger one is freed when its copy has been handed over.
// Failures answer with a status and one log line per kind: nothing here aborts, waits or retries.
#include "vka_readback.h"

_Static_assert(VKA_RB_VK_R8_UNORM == VK_FORMAT_R8_UNORM && VKA_RB_VK_R8G8B8A8_UNORM == VK_FORMAT_R8G8B8A8_UNORM && VKA_RB_VK_R8G8B8A8_SRGB == VK_FORMAT_R8G8B8A8_SRGB &&
               VKA_RB_VK_B8G8R8A8_UNORM == VK_FORMAT_B8G8R8A8_UNORM && VKA_RB_VK_B8G8R8A8_SRGB == VK_FORMAT_B8G8R8A8_SRGB &&
               VKA_RB_VK_A8B8G8R8_UNORM_PACK32 == VK_FORMAT_A8B8G8R8_UNORM_PACK32 && VKA_RB_VK_A8B8G8R8_SRGB_PACK32 == VK_FORMAT_A8B8G8R8_SRGB_PACK32, "vka_readback.h's VkFormat numbers");

enum { RB_FREE, RB_CLAIM, RB_QUEUED, RB_PICKING, RB_RECORDED, RB_FLIGHT, RB_PACKING };
enum { RB_KEEP = 1 << 20 };
typedef struct {
    // RB_FREE -> CLAIM -> QUEUED (the asker) -> PICKING -> RECORDED -> FLIGHT -> PACKING (the present thread; PICKING -> QUEUED when the image is not settled yet)
    // -> FREE (the worker). QUEUED -> CLAIM -> FREE is the asker's again when no present took the request within the timeout
    _Atomic int st;
    vka_rb_request rq; vka_rb_result res;
    uint64_t t_queued;       // now_ns at the request
    vka_img *im;             // held from the pick until the copy's fence has been waited for
    VkFence fence;           // the present's fence of the submission that holds the copy
    VkBuffer buf; VkDeviceMemory mem; uint8_t *map; VkDeviceSize cap;
} rb_slot;
static rb_slot g_rb[VKA_RB_MAX_IN_FLIGHT];
_Atomic int vka_rb_on;       // slots that are not free: the present's three hooks run only then
_Atomic int vka_rb_track;    // the images' write notes are kept (from the first request to the end of the process)
_Thread_local uint8_t vka_rb_color;   // set by a colour target's site for the vka_img_written that follows it: 1 a linear view, 2 an sRGB one
static _Atomic uint64_t g_rb_start;   // the fence by which everything recorded before the notes began is on the queue (0: notes not begun)
static uint64_t g_rb_tick;   // (under g_tmu) the last generation given
static pthread_mutex_t g_rbwmu = PTHREAD_MUTEX_INITIALIZER; static pthread_cond_t g_rbwcv = PTHREAD_COND_INITIALIZER; static int g_rbw_up;   // the hand-over worker

#define RB_ONCE(...) do { static _Atomic int once_; if (!atomic_exchange(&once_, 1)) VLOG(__VA_ARGS__); } while (0)

// The GPU writes im (vka_img_written_at, under g_tmu, while vka_rb_track): the batch being recorded holds the write, and its fence is the next one reserved or the
// last (vka_exec.c reserves at the submission, or earlier): the later of the two is taken, so the note is never early
static void rb_written_locked(vka_img *im) {
    uint64_t f = vka_fence_last() + 1;
    if (f != im->rb_fence) { im->rb_pgen = im->rb_gen; im->rb_pfence = im->rb_fence; im->rb_pok = 1; im->rb_fence = f; }
    im->rb_gen = ++g_rb_tick;
    uint8_t c = vka_rb_color;
    if (!c) return;
    vka_rb_color = 0;
    uint64_t fr = vka_frame();
    if (!im->rb_rt) { im->rb_rt = 1; im->rb_frame = fr; }
    else if (im->rb_frame != fr) im->rb_redrawn = 1;
    im->rb_srgb = c;   // the format of the view the game draws it through: what a request's sRGB / linear flag is about, whichever texture first made the image
}
// im's first use (vka_tex_get, under g_tmu, while vka_rb_track): it holds nothing a copy may read before this batch is on the queue
static void rb_created_locked(vka_img *im) { im->rb_fence = vka_fence_last() + 1; im->rb_gen = 0; im->rb_pok = 0; }
// The newest generation of im whose batch is on the queue (q = vka_fence_queued); 0 = none yet (under g_tmu)
static int rb_settled_locked(const vka_img *im, uint64_t q, uint64_t *gen) {
    if (im->rb_fence <= q) { *gen = im->rb_gen; return 1; }
    if (im->rb_pok && im->rb_pfence <= q) { *gen = im->rb_pgen; return 1; }
    *gen = im->rb_gen; return 0;
}

int vka_rb_available(void) { return vka_ok() && !vka_hung(); }

static void rb_buf_free(rb_slot *s) {
    if (!s->buf) return;
    vk.vkUnmapMemory(V.dev, s->mem); vk.vkDestroyBuffer(V.dev, s->buf, NULL); vk.vkFreeMemory(V.dev, s->mem, NULL);
    s->buf = VK_NULL_HANDLE; s->mem = VK_NULL_HANDLE; s->map = NULL; s->cap = 0;
}
static int rb_buf(rb_slot *s, VkDeviceSize need) {
    if (s->buf && s->cap >= need && s->cap <= (need > RB_KEEP ? need : RB_KEEP)) return 1;
    rb_buf_free(s);
    VkDeviceSize cap = need > 65536 ? need : 65536;
    VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = cap, .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT};
    VkMemoryRequirements mr; void *map = NULL;
    if (vk.vkCreateBuffer(V.dev, &bi, NULL, &s->buf)) { s->buf = VK_NULL_HANDLE; return 0; }
    vk.vkGetBufferMemoryRequirements(V.dev, s->buf, &mr);
    // Cached memory when the device has it (the hand-over reads every byte once; uncached write-combined memory reads slowly), else any coherent host-visible type
    uint32_t mt = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, 0);
    if (mt == UINT32_MAX) mt = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = mt};
    if (mt == UINT32_MAX || vk.vkAllocateMemory(V.dev, &ai, NULL, &s->mem)) { vk.vkDestroyBuffer(V.dev, s->buf, NULL); s->buf = VK_NULL_HANDLE; s->mem = VK_NULL_HANDLE; return 0; }
    if (vk.vkBindBufferMemory(V.dev, s->buf, s->mem, 0) || vk.vkMapMemory(V.dev, s->mem, 0, VK_WHOLE_SIZE, 0, &map)) {
        vk.vkDestroyBuffer(V.dev, s->buf, NULL); vk.vkFreeMemory(V.dev, s->mem, NULL); s->buf = VK_NULL_HANDLE; s->mem = VK_NULL_HANDLE; return 0; }
    s->map = map; s->cap = cap; return 1;
}
// The readback's hold on an image let go (the count that keeps it out of img_storage_upgrade's "held by a reader of im->img" goes with it)
static void rb_drop(vka_img *im) {
    pthread_mutex_lock(&g_tmu);
    if (im->rb_holds) im->rb_holds--;
    im_unhold_locked(im);
    unref_locked(im);
    pthread_mutex_unlock(&g_tmu);
}
// The slot back to free (its owner of the moment: the present thread, or the worker after a hand-over): its image let go, a large buffer freed
static void rb_release(rb_slot *s) {
    if (s->im) { rb_drop(s->im); s->im = NULL; }
    if (s->cap > RB_KEEP) rb_buf_free(s);
    s->fence = VK_NULL_HANDLE;
    atomic_store(&s->st, RB_FREE); atomic_fetch_sub(&vka_rb_on, 1);
}
// An answer without pixels (the slot's owner of the moment)
static void rb_fail(rb_slot *s, int status) { s->res.status = status; s->res.rows = NULL; s->res.row_bytes = 0; s->rq.done(s->rq.user, &s->res); rb_release(s); }

int vka_rb_submit(const vka_rb_request *rq) {
    if (!rq || !rq->pick || !rq->done || !vka_rb_in_bounds(rq->width, rq->height, rq->format) || !vka_rb_available()) return 0;
    if (!atomic_exchange(&vka_rb_track, 1)) {
        atomic_store(&g_rb_start, vka_fence_last() + 2);   // (a batch being recorded now has the last fence or the next: one more, and all of it is on the queue)
        VLOG("readback: a companion asks for a game texture: GPU writes and colour targets are noted from now on");
    }
    uint64_t t = now_ns();
    for (int i = 0; i < VKA_RB_MAX_IN_FLIGHT; i++) {   // requests no present took within the timeout (the game is paused, or the replay stopped presenting)
        rb_slot *s = &g_rb[i]; int want = RB_QUEUED;
        if (atomic_load(&s->st) != RB_QUEUED || t - s->t_queued < (uint64_t)VKA_RB_TIMEOUT_MS * 1000000ull || !atomic_compare_exchange_strong(&s->st, &want, RB_CLAIM)) continue;
        RB_ONCE("readback: a request waited %d ms without a present: answered as failed (first case)", VKA_RB_TIMEOUT_MS);
        rb_fail(s, VKA_RB_FAILED);
    }
    for (int i = 0; i < VKA_RB_MAX_IN_FLIGHT; i++) {
        int want = RB_FREE;
        if (!atomic_compare_exchange_strong(&g_rb[i].st, &want, RB_CLAIM)) continue;
        g_rb[i].rq = *rq; g_rb[i].t_queued = t;
        g_rb[i].res = (vka_rb_result){.status = VKA_RB_FAILED, .width = rq->width, .height = rq->height};
        atomic_fetch_add(&vka_rb_on, 1);
        atomic_store(&g_rb[i].st, RB_QUEUED);
        return 1;
    }
    return 0;
}

// The hand-over worker: a finished copy's done callback (the asker's conversion of up to 16 MiB) runs here, never on the present thread; then the slot is free
static void *rb_worker(void *arg) {
    (void)arg; pthread_setname_np(pthread_self(), "vk-readback");
    for (;;) {
        rb_slot *s = NULL;
        pthread_mutex_lock(&g_rbwmu);
        for (;;) {
            for (int i = 0; i < VKA_RB_MAX_IN_FLIGHT && !s; i++) if (atomic_load(&g_rb[i].st) == RB_PACKING) s = &g_rb[i];
            if (s) break;
            pthread_cond_wait(&g_rbwcv, &g_rbwmu);
        }
        pthread_mutex_unlock(&g_rbwmu);
        s->rq.done(s->rq.user, &s->res);
        rb_release(s);
    }
    return NULL;
}
static void rb_hand(rb_slot *s) {   // present thread
    pthread_mutex_lock(&g_rbwmu);
    if (!g_rbw_up) { pthread_t th; pthread_attr_t at; if (!pthread_attr_init(&at)) { pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED); g_rbw_up = !pthread_create(&th, &at, rb_worker, NULL); pthread_attr_destroy(&at); } }
    int up = g_rbw_up;
    if (up) { atomic_store(&s->st, RB_PACKING); pthread_cond_signal(&g_rbwcv); }
    pthread_mutex_unlock(&g_rbwmu);
    if (!up) { RB_ONCE("readback: no worker thread: copies are handed over on the present thread"); s->rq.done(s->rq.user, &s->res); rb_release(s); }
}

// DM_VK_READBACK_LOG=1 (live): what a request looked at, at most every 5 s: the images at its address, or sharing a side with it (the texture cache locked)
static void rb_list_locked(const vka_rb_request *rq, const char *what, uint32_t ncand) {
    static uint64_t next; uint64_t t = now_ns();
    if (!VKA_ENV("DM_VK_READBACK_LOG", 0) || t < next) return;
    next = t + 5000000000ull;
    char b[3600]; size_t p = 0; int shown = 0;
    for (int i = 0; i < g_nlive && shown < 24 && p + 160 < sizeof b; i++) {
        const vka_img *im = g_live[i]; uint64_t a = (uint64_t)(uintptr_t)im->mem;
        if (!((rq->addr && a == rq->addr) || (uint32_t)im->d.width == rq->width || (uint32_t)im->d.height == rq->height)) continue;
        p += (size_t)snprintf(b + p, sizeof b - p, " [%#llx serial %llu %dx%d VkFormat %d%s target %d levels %u layers %u samples %u rt %d (view %s) redrawn %d gen %llu%s%s]", (unsigned long long)a,
                              (unsigned long long)im->serial, im->d.width, im->d.height, (int)im->f.vk, im->f.srgb ? " sRGB" : "", im->d.target, im->levels, im->layers, im->samples,
                              im->rb_rt, im->rb_srgb == 2 ? "sRGB" : im->rb_srgb ? "linear" : "-", im->rb_redrawn, (unsigned long long)im->rb_gen, im->linger ? " lingering" : "", im->rsc ? " scaled" : "");
        shown++;
    }
    VLOG("readback: %s for %#llx %ux%u layout %u (%u candidates); %d related of %d live images:%s", what, (unsigned long long)rq->addr, rq->width, rq->height, rq->format, ncand, shown, g_nlive, p ? b : " none");
}
// The request's image, held, with its VkImage handle; NULL with s->res.status set, or with *later set: nothing can be said yet, the request stays queued. Present thread
static vka_img *rb_pick(rb_slot *s, VkImage *img, int *scaled, int *later) {
    static vka_rb_cand c[VKA_RB_MAX_CAND]; static vka_img *ci[VKA_RB_MAX_CAND]; static uint8_t settled[VKA_RB_MAX_CAND];   // (present thread only)
    const vka_rb_request *rq = &s->rq; uint32_t n = 0; int over = 0; vka_img *im = NULL;
    uint64_t q = vka_fence_queued();
    *later = 0;
    if (q < atomic_load(&g_rb_start)) { *later = 1; return NULL; }   // what was recorded before the notes began is not all on the queue yet
    pthread_mutex_lock(&g_tmu);
    for (int i = 0; i < g_nlive; i++) {
        vka_img *o = g_live[i]; uint64_t a = (uint64_t)(uintptr_t)o->mem;
        if (rq->addr ? a != rq->addr : ((uint32_t)o->d.width != rq->width || (uint32_t)o->d.height != rq->height)) continue;
        if (!a || !o->img || o->linger || img_pool_dead(o)) continue;   // no storage to name it by, or no texture of the game's names it any more
        if (n == VKA_RB_MAX_CAND) { over = 1; break; }
        uint64_t gen; settled[n] = (uint8_t)rb_settled_locked(o, q, &gen);
        // Its format class: of the view the game last drew it through when it is a colour target (textures of an sRGB and a linear format over one storage share
        // one image, made by whichever came first), else of the image itself
        int srgb = o->rb_srgb ? o->rb_srgb == 2 : o->f.srgb;
        c[n] = (vka_rb_cand){.addr = a, .width = (uint32_t)o->d.width, .height = (uint32_t)o->d.height, .format = (uint8_t)vka_rb_format_class((int)o->f.vk, srgb),
                             .plain_2d = o->d.target == NVN_TEXTURE_TARGET_2D && o->aspect == VK_IMAGE_ASPECT_COLOR_BIT && o->samples == 1 && o->layers == 1 && o->depth <= 1 &&
                                         o->levels >= 1 && !o->base && !o->rs && !o->vpool && !o->is_video && !o->par,   // (par: a level child's image is its parent's, whose level 0 is not this texture's)
                             .current = o->inited && !o->cpu_dirty, .render_target = o->rb_rt, .redrawn = o->rb_redrawn, .generation = gen};
        ci[n++] = o;
    }
    vka_rb_pick pk = rq->pick(rq->user, c, n);
    s->res.candidates = pk.candidates; s->res.status = pk.status;
    if (pk.status == VKA_RB_READY && pk.index < n) {
        im = ci[pk.index];
        if (!vka_rb_copyable(c[pk.index].format, rq->format)) { s->res.status = VKA_RB_UNSUPPORTED; im = NULL; }
        else if (!settled[pk.index]) { *later = 1; im = NULL; }   // made or first written in a batch not on the queue yet: the next present asks again
        else {
            im->refs++; im->holds++; im->rb_holds++; im->last_frame = vka_frame(); im->last_fence = vka_fence_last();   // (as vka_tex_acquire, and counted as the readback's)
            *img = im->img; *scaled = im->rsc;
            s->res.addr = c[pk.index].addr; s->res.generation = c[pk.index].generation; s->res.bgra = c[pk.index].format >= VKA_RB_F_BGRA8;
        }
        if (pk.candidates > 1) rb_list_locked(rq, "more than one image matches", pk.candidates);
    } else {
        if (pk.status == VKA_RB_READY) s->res.status = VKA_RB_FAILED;
        rb_list_locked(rq, "no image", pk.candidates);
    }
    pthread_mutex_unlock(&g_tmu);
    if (over) RB_ONCE("readback: more than %d live images at one address or of one size: the rest were not looked at", VKA_RB_MAX_CAND);
    return im;
}

// In the present's command buffer, before it ends (present thread; vka_rs_mu held when DM_RES_SCALE is on, as for the window texture)
void vka_rb_record(VkCommandBuffer cb) {
    int any = 0;
    for (int i = 0; i < VKA_RB_MAX_IN_FLIGHT; i++) {
        rb_slot *s = &g_rb[i]; int want = RB_QUEUED;
        if (!atomic_compare_exchange_strong(&s->st, &want, RB_PICKING)) continue;   // (the asker may take a stale request back at the same moment: one of the two has it)
        VkImage img = VK_NULL_HANDLE; int scaled = 0, later = 0;
        vka_img *im = rb_pick(s, &img, &scaled, &later);
        if (later) { atomic_store(&s->st, RB_QUEUED); continue; }
        if (!im) {
            if (s->res.status == VKA_RB_NOT_FOUND) RB_ONCE("readback: no live texture %ux%u (layout %u) at %#llx matches a companion's request: it keeps its fallback (first case; DM_VK_READBACK_LOG=1 lists what is there)",
                                                           s->rq.width, s->rq.height, s->rq.format, (unsigned long long)s->rq.addr);
            else if (s->res.status == VKA_RB_MISMATCH) RB_ONCE("readback: the texture at %#llx is not a plain %ux%u 8-bit one of the layout asked (%u), or not what the request's flags want: not read (first case)",
                                                               (unsigned long long)s->rq.addr, s->rq.width, s->rq.height, s->rq.format);
            else RB_ONCE("readback: a texture's format has no copy in the layout asked (%u): not read (first case)", s->rq.format);
            rb_fail(s, s->res.status); continue;
        }
        s->im = im;
        // A rescaled image (DM_RES_SCALE) is not the texture's pixels: it would need a blit to the texture's size first, which is not done here
        if (scaled) { RB_ONCE("readback: texture serial %llu is rescaled (DM_RES_SCALE): not read (first case)", (unsigned long long)im->serial); rb_fail(s, VKA_RB_UNSUPPORTED); continue; }
        VkDeviceSize row = (VkDeviceSize)s->rq.width * vka_rb_bpp(s->rq.format), need = row * s->rq.height;
        if (!rb_buf(s, need)) { RB_ONCE("readback: no host-visible buffer of %llu bytes: not read (first case)", (unsigned long long)need); rb_fail(s, VKA_RB_FAILED); continue; }
        if (!any) { vka_barrier(cb); any = 1; }   // the replay's writes to the image (earlier submissions, in submission order)
        VkBufferImageCopy r = {0, 0, 0, {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {0, 0, 0}, {s->rq.width, s->rq.height, 1}};   // tightly packed rows, the image's first row first
        vk.vkCmdCopyImageToBuffer(cb, img, VK_IMAGE_LAYOUT_GENERAL, s->buf, 1, &r);
        s->res.row_bytes = (size_t)row;
        RB_ONCE("readback: texture serial %llu %ux%u VkFormat %d%s at %#llx is copied for a companion (first copy)", (unsigned long long)im->serial, s->rq.width, s->rq.height, (int)im->f.vk,
                s->res.bgra ? " (B, G, R, A)" : "", (unsigned long long)s->res.addr);
        atomic_store(&s->st, RB_RECORDED);
    }
    if (any) {
        VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
        vk.vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0, NULL);
    }
}
// After the present's submission (present thread): its fence is the copies' fence; a submission that failed answers them now
void vka_rb_submitted(VkFence fence, int ok) {
    for (int i = 0; i < VKA_RB_MAX_IN_FLIGHT; i++) {
        rb_slot *s = &g_rb[i];
        if (atomic_load(&s->st) != RB_RECORDED) continue;
        if (!ok) { RB_ONCE("readback: the present's submission failed (the device is lost?): not read (first case)"); rb_fail(s, VKA_RB_FAILED); continue; }
        s->fence = fence; atomic_store(&s->st, RB_FLIGHT);
    }
}
// At a present, after its wait for frame slot's fence `reuse` ended with `waited` and before the fence is reset: the copies that rode on that fence are done
// (VK_SUCCESS) or lost (anything else). Copies on the other frame slots' fences wait for their own slot's turn: no fence is queried here
void vka_rb_poll(VkFence reuse, VkResult waited) {
    for (int i = 0; i < VKA_RB_MAX_IN_FLIGHT; i++) {
        rb_slot *s = &g_rb[i];
        if (atomic_load(&s->st) != RB_FLIGHT || s->fence != reuse) continue;
        if (waited != VK_SUCCESS) { RB_ONCE("readback: the wait for a present's fence ended with %d: its copy is not handed over (first case)", (int)waited); rb_fail(s, VKA_RB_FAILED); continue; }
        rb_drop(s->im); s->im = NULL;   // the copy is done: the image may be remade or go
        s->res.status = VKA_RB_READY; s->res.rows = s->map;
        rb_hand(s);
    }
}
