// PEANUTS: new file. Resolution scale (DM_RES_SCALE=0.5 | 0.75 | 1.25 | 1.5 | 2, read once at the device's start): the game's render targets are made larger or
// smaller than the game asked for, for every game, without the game knowing. Part of vka_tex.c (included there: it works on the texture cache's own tables under its
// lock); the arithmetic is vka_resscale.h, the hooks are in vka_draw.c (the pass, viewport, scissor, the shaders' support buffer), vka_exec.c (clears, copies) and
// vka_present.c (the crop).
//
// What it rests on: an image here is never the texture's storage (vka_tex.c: its own device memory, content copied in or drawn), so its size can differ from the
// texture's; and the translated shaders already carry Ryujinx's own resolution scale code, read from the support buffer (render_scale[0] divides gl_FragCoord.xy;
// render_scale[1 + i] scales the integer coordinates of a fetch from a plain 2D texture and unscales its size query), which the replay only had filled with 1.0.
//
// Which images: only one the GPU fills (content ST_ZERO, never uploaded from the storage), a plain 2D texture with one level, one layer and one sample, of a colour   // PEANUTS: #734
// format the device can blit (float, normalized or integer: an integer one is blitted nearest, #703) or a depth format, with none of the linear, image (shader storage), sparse or video flags, and not a power-of-two
// square (shadow maps, cube faces, bakes: scaling those costs memory for nothing on screen; DM_RES_SCALE_POT=1 takes them too). It is scaled at its first use as a
// render target (or as the other side of a copy with a scaled image), never when it is made: a texture that is only ever sampled or uploaded stays as it is.
//
// One scale per pass: the targets of a pass are all scaled or all not (vka_draw.c vka_rp_rs). A scaled image that meets a use this slice has no scaled form of is
// made 1x again, its content kept, and its shape (size, format, flags) goes into the deny table so the next image of that shape starts and stays at 1x: nothing
// changes scale every frame. The reasons are counted (RS_WHY_*) and the first 16 of each logged; the `res scale:` line of the replay statistics has the totals.
//
// A change of scale makes the image again (as img_storage_upgrade and soft_harden do): a new VkImage of the other size, the content blitted (linear for colour,
// nearest for depth and stencil where the format has blits, else the new image starts cleared, logged once), swapped in place so every texture, view and pool slot
// follows. A holder outside the recording thread (the present, frame generation, a texel read) reads the image's size and handle together under vka_rs_mu, which the
// change only tries to take: when it cannot, the change is not made this time and its caller leaves its work out once (counted as busy / skipped).
//
// Lifetimes, checked against the three swaps the cache already had (img_own, soft_harden, img_storage_upgrade): the new vka_img from img_create is in no table (the
// serial map and the live list take an image only in vka_tex_get), so after the swap it is only the holder of the old VkImage, its memory and its views, destroyed
// once: by the callback queued for the submission after the last one reserved, or, while im is held, from im->zold when the last holder lets go (im_unhold_locked) or
// im itself goes (img_destroy). im keeps its identity: its references, holds, map entries, place in the live list and lingering are untouched. Nothing is evicted
// here (no prune: the next new image's creation makes room), so no other image is freed while a caller holds pointers from this pass.
uint32_t vka_rs_q = VKRS_ONE;
vka_rs_stat vka_rs_st;
_Atomic uint32_t vka_rs_sh[3];
pthread_mutex_t vka_rs_mu = PTHREAD_MUTEX_INITIALIZER;
static vkrs_deny g_rs_deny;   // (under g_tmu)
static const char *const g_rs_why[RS_WHY_N] = {"its pass has a target that cannot be scaled", "a buffer is copied into it", "it is copied into a buffer", "a shader writes it as an image",
                                               "a copy with an unscaled image that a blit cannot convert", "a multisample resolve into it", "a shader fetches from it by pixel beside unscaled textures",
                                               "an upscaler stand-in reads or writes it", "its storage is uploaded into it",
                                               "a pass reduces a render target of at least twice its size into it (a bloom, blur or average chain, whose taps the game steps by its 1x texel sizes)"};
static const char *const g_rs_short[RS_WHY_N] = {"pass mix", "B2T", "T2B", "storage", "copy kind", "resolve", "fetch mix", "stand-in", "upload", "reduce"};
static vkrs_deny g_rs_redok;   // shapes a fetch beside scaled images took out of the reduction rule (vka_tex_rs_unkeep): reductions leave them as they are (under g_tmu)

static int rs_blit_ok(VkFormat f) { VkFormatFeatureFlags ff = feats(f); return (ff & VK_FORMAT_FEATURE_BLIT_SRC_BIT) && (ff & VK_FORMAT_FEATURE_BLIT_DST_BIT); }
// The device's start (vka_dev.c vka_init_once, once V.pd is there): the factor, and which depth formats can be blitted, said in every run so the first log of a device
// tells whether a depth target keeps its content when it changes scale
void vka_rs_init(void) {
    static const struct { VkFormat f; const char *n; } df[] = {{VK_FORMAT_D16_UNORM, "D16"}, {VK_FORMAT_X8_D24_UNORM_PACK32, "X8D24"}, {VK_FORMAT_D32_SFLOAT, "D32F"}, {VK_FORMAT_S8_UINT, "S8"},
                                                               {VK_FORMAT_D24_UNORM_S8_UINT, "D24S8"}, {VK_FORMAT_D32_SFLOAT_S8_UINT, "D32FS8"}};
    char b[256]; size_t k = 0; b[0] = 0;
    for (size_t i = 0; i < sizeof df / sizeof *df && k < sizeof b; i++) {
        VkFormatProperties p = {0}; vk.vkGetPhysicalDeviceFormatProperties(V.pd, df[i].f, &p); VkFormatFeatureFlags ff = p.optimalTilingFeatures;
        int at = (ff & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0, s = (ff & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0, d = (ff & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;
        k += (size_t)snprintf(b + k, sizeof b - k, "%s%s %s", i ? ", " : "", df[i].n, !at ? "no attachment" : s && d ? "blit" : s ? "blit source only" : d ? "blit destination only" : "no blit");
    }
    VLOG("res scale: depth and stencil formats of this device: %s (a scaled depth target keeps its content across a change of scale only with a blit)", b);
    const char *e = getenv("DM_RES_SCALE"); uint32_t q = vkrs_parse(e);
    if (e && *e && q == VKRS_ONE && strtod(e, NULL) != 1.0) VRED("res scale: DM_RES_SCALE=%s is not one of 0.5, 0.75, 1, 1.25, 1.5, 2: off", e);
    if (q == VKRS_ONE) return;
    vka_rs_q = q;
    VLOG("res scale: x%.2f (DM_RES_SCALE=%s): render targets are made at %u/4 of the size the game asks for (a 1920x1080 target: %ux%u), from their first use as a target; restart only",
         q / 4.0, e, q, vkrs_dim(1920, q), vkrs_dim(1080, q));
}
// im may be scaled: what it is and how it is filled, and its shape is not denied. Caller holds g_tmu
static int rs_can_locked(vka_img *im) {
    if (im->rs_no || !im->inited) return 0;
    const nvn_texdesc *d = &im->d; int no = 0;
    static int pot = -1; if (pot < 0) { const char *e = getenv("DM_RES_SCALE_POT"); pot = e && atoi(e) != 0; }
    uint32_t lim = V.props.limits.maxImageDimension2D;
    if (im->mode != ST_ZERO || im->from_mem || im->track || im->vtrack || im->wt || im->is_video || im->rs || im->vpool || im->soft || im->base) no = 1;   // not GPU content alone
    else if (d->target != NVN_TEXTURE_TARGET_2D || im->layers != 1 || im->levels != 1 || im->samples != 1 || im->depth != 1 || compressed(&im->f)) no = 1;   // PEANUTS: #734
    else if (d->flags & (NVN_TEXTURE_FLAGS_LINEAR | NVN_TEXTURE_FLAGS_LINEAR_RENDER_TARGET | NVN_TEXTURE_FLAGS_IMAGE | NVN_TEXTURE_FLAGS_SPARSE | NVN_TEXTURE_FLAGS_VIDEO_DECODE)) no = 1;
    else if (!pot && d->width == d->height && !(d->width & (d->width - 1))) no = 1;                      // a power-of-two square
    else if (vkrs_dim((uint32_t)d->width, vka_rs_q) > lim || vkrs_dim((uint32_t)d->height, vka_rs_q) > lim) no = 1;
    else if (im->f.kind <= 2 && !rs_blit_ok(im->f.vk)) no = 1;                                           // a colour format without blits: no scaled copy into or out of it (an integer one blits nearest, #703)   // PEANUTS: #734
    else { int di = vkrs_deny_find(&g_rs_deny, (uint32_t)d->width, (uint32_t)d->height, (uint32_t)im->f.vk, (uint32_t)d->flags);   // PEANUTS: a shape a reduction denied: rs_red
           if (di >= 0) { no = 1; if (g_rs_deny.e[di].why == RS_WHY_REDUCE) im->rs_red = 1; } }
    if (!no && (im->usage & VK_IMAGE_USAGE_STORAGE_BIT)) no = 1;   // (remade with storage use since: a shader writes it in the texture's pixels; every swap keeps the use, so it is not asked again either)
    if (no) { im->rs_no = 1; return 0; }   // (none of these changes for an image; a shape denied later is found at the next question)
    return 1;
}
static void rs_event(const vka_img *im, int up, int why, uint32_t ow, uint32_t oh, const char *content) {
    uint64_t *n = up ? &vka_rs_st.up : &vka_rs_st.down[why];
    if ((*n)++ >= 16) return;
    char key[40] = ""; if (!up && (why == RS_WHY_FETCH_MIX || why == RS_WHY_REDUCE) && vka_rs_st.ctx_key) snprintf(key, sizeof key, ", shader %016llx", (unsigned long long)vka_rs_st.ctx_key);
    if (up) VLOG("res scale: up: texture serial %llu %ux%u NVN format %#x (VkFormat %d) flags %#x: image %ux%u -> %ux%u, %.1f MiB, %s (frame %llu)", (unsigned long long)im->serial, im->d.width, im->d.height,
                 im->d.format, im->f.vk, im->d.flags, ow, oh, im->w, im->h, im->bytes / 1048576.0, content, (unsigned long long)vka_frame());
    else VLOG("res scale: down (%s): texture serial %llu %ux%u NVN format %#x (VkFormat %d) flags %#x: %s%s; image %ux%u -> %ux%u, %s; its shape stays at 1x from now on (frame %llu)", g_rs_short[why],
              (unsigned long long)im->serial, im->d.width, im->d.height, im->d.format, im->f.vk, im->d.flags, g_rs_why[why], key, ow, oh, im->w, im->h, content, (unsigned long long)vka_frame());
}
// The image made again at the other scale (up: scaled; else 1x, why = RS_WHY_*), its content kept, swapped in place. self: the holds the caller itself has on im
// (id_view's). Caller holds g_tmu; no render pass may be open in cb. 1 = done (or it is at that scale already), 0 = not, and not later either (up: it cannot be
// scaled; down: no image could be made), -1 = not this time (a holder is reading it)
static int rs_set_locked(vka_img *im, VkCommandBuffer cb, int up, int why, int self) {
    if (!!im->rsc == !!up) return 1;
    if (!cb) return 0;
    if (up && !rs_can_locked(im)) return 0;
    uint64_t now = im->m.z ? im->m.size : im->bytes;
    if (up && g_img_lim && vkrs_bytes(now, vka_rs_q) > g_img_lim / 4) {   // one image of a quarter of all the cache may hold: not worth what it would push out
        if (vka_rs_st.up_no_mem++ < 16) VLOG("res scale: texture serial %llu %ux%u format %#x stays at 1x: scaled it would take %.0f MiB of the image cache's %llu MiB", (unsigned long long)im->serial,
                                             im->d.width, im->d.height, im->d.format, vkrs_bytes(now, vka_rs_q) / 1048576.0, (unsigned long long)(g_img_lim >> 20));
        im->rs_no = 1; return 0;
    }
    int locked = 0;
    if (im->holds > self) { if (pthread_mutex_trylock(&vka_rs_mu)) { vka_rs_st.busy++; return -1; } locked = 1; }
    tex_t t = {0}; t.serial = im->serial; t.mem = (uint8_t *)im->mem; t.d = im->d; t.L = im->L;
    if (im->usage & VK_IMAGE_USAGE_STORAGE_BIT) t.d.flags |= NVN_TEXTURE_FLAGS_IMAGE;   // (down only: made with storage use, or upgraded to it)
    g_rswant = up; vka_img *n = img_create(&t, &im->f, 0); g_rswant = 0;
    if (!n) { if (locked) pthread_mutex_unlock(&vka_rs_mu);
              static _Atomic int k; if (atomic_fetch_add(&k, 1) < 8) VRED("res scale: texture serial %llu %ux%u format %#x: no image at %s could be made: it stays as it is", (unsigned long long)im->serial, im->d.width, im->d.height, im->d.format, up ? "the scaled size" : "1x");
              if (up) im->rs_no = 1;
              return 0; }
    vka_img_barrier(cb, n->img, n->aspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
    const char *content = "cleared (nothing drawn into it yet)";
    int color = im->f.kind == 0, blit = im->gpu_owned && rs_blit_ok(im->f.vk);
    if (blit) {   // what the GPU drew, at the new size
        VkImageSubresourceLayers sr = {im->aspect, 0, 0, 1};
        VkImageBlit b = {sr, {{0, 0, 0}, {(int32_t)im->w, (int32_t)im->h, 1}}, sr, {{0, 0, 0}, {(int32_t)n->w, (int32_t)n->h, 1}}};
        int lin = color && (feats(im->f.vk) & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
        vk.vkCmdBlitImage(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, n->img, VK_IMAGE_LAYOUT_GENERAL, 1, &b, lin ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
        content = lin ? "content blitted (linear)" : "content blitted (nearest)";
    } else {
        VkImageSubresourceRange rg = {n->aspect, 0, VK_REMAINING_MIP_LEVELS, 0, VK_REMAINING_ARRAY_LAYERS};
        if (n->f.kind >= 3) { VkClearDepthStencilValue z = {0, 0}; vk.vkCmdClearDepthStencilImage(cb, n->img, VK_IMAGE_LAYOUT_GENERAL, &z, 1, &rg); }
        else { VkClearColorValue z = {{0}}; vk.vkCmdClearColorImage(cb, n->img, VK_IMAGE_LAYOUT_GENERAL, &z, 1, &rg); }
        if (im->gpu_owned) { content = "CONTENT DROPPED (the format has no blit): cleared";
            VRED_ONCE("res scale: texture serial %llu %ux%u VkFormat %d changes scale without its content: the device has no blit for the format, the image starts cleared (first case; the game draws it again or a frame shows the gap)",
                      (unsigned long long)im->serial, im->d.width, im->d.height, im->f.vk); }
    }
    vka_barrier(cb);
    n->inited = 1;
    // From here n is no image of the cache but the old VkImage's holder until the GPU is done with it: in no table, reached only through im->zold or the callback
    // below. Its count is set where no stray release can bring it to zero and destroy it a second time (the first device run aborted in a second img_destroy; the
    // one way found by reading: a region write's reference to an evicted image, whose memory this holder had taken; vka_tex.c rw_pend)
    n->refs = INT32_MAX / 2;
    VkImage oi = im->img; vka_imem om = im->m; void *ov = im->views; uint64_t ob = im->bytes; uint32_t ow = im->w, oh = im->h;
    im->img = n->img; im->m = n->m; im->views = NULL; im->usage = n->usage; im->cflags = n->cflags; im->nvf = n->nvf; memcpy(im->vf, n->vf, sizeof im->vf); im->bytes = n->bytes; im->fl = n->fl;
    im->w = n->w; im->h = n->h; im->pool_fit = n->pool_fit; im->rsc = (uint8_t)(up != 0);
    n->img = oi; n->m = om; n->views = ov; n->bytes = 0; n->w = ow; n->h = oh; g_img_bytes -= ob;
    if (om.z) vka_imem_zowned();   // (it left the zeroed memory it shared: the new image has its own)
    if (im->holds) { n->zold = im->zold; im->zold = n; }   // (a holder may have recorded commands that read the old image, or read im->img unlocked on the recording thread: it stays until they let go)
    else vka_after(vka_fence_last() + 1, img_destroy, n);   // (after the submission being recorded, which holds the blit that reads it)
    if (locked) pthread_mutex_unlock(&vka_rs_mu);
    atomic_fetch_add_explicit(&g_tgen, 1, memory_order_release);   // views of the old image are gone with it: the recording memo and the pool slots that name the image are made again
    redirty_ids(im);
    if (!up) { int r = vkrs_deny_add(&g_rs_deny, (uint32_t)im->d.width, (uint32_t)im->d.height, (uint32_t)im->f.vk, (uint32_t)im->d.flags, (uint8_t)why); im->rs_no = 1;
               if (r < 0 && !vka_rs_st.deny_full++) VRED("res scale: the table of shapes kept at 1x is full (%d): further shapes may change scale again and again", VKRS_DENY_MAX); }
    rs_event(im, up, why, ow, oh, content);
    return 1;
}
int vka_tex_rs_can(vka_img *im) { if (!im || im->rs_no) return 0;   // (rs_no is set on the recording thread only, which is the one asking, as rsc in vka_tex_rs_up: a target that cannot be scaled costs a pass no lock)
    pthread_mutex_lock(&g_tmu); int r = rs_can_locked(im); pthread_mutex_unlock(&g_tmu); return r; }
int vka_tex_rs_up(vka_img *im, VkCommandBuffer cb) {
    if (!im || !vka_rs_on()) return 0;
    if (im->rsc) return 1;   // (changes on the recording thread only, which is the one asking)
    pthread_mutex_lock(&g_tmu); int r = rs_set_locked(im, cb, 1, 0, 0); pthread_mutex_unlock(&g_tmu); return r;
}
int vka_tex_rs_down(vka_img *im, VkCommandBuffer cb, int why) {
    if (!im || !im->rsc) return 1;
    pthread_mutex_lock(&g_tmu); int r = rs_set_locked(im, cb, 0, why, 0); pthread_mutex_unlock(&g_tmu); return r > 0;
}
// PEANUTS begin: reductions (vka_draw.c rs_reduce_fix). im is a target of a pass that reduces a render target of at least twice its size: at 1x for good, its shape
// denied with RS_WHY_REDUCE so that the next image of that shape (and so the rest of a pyramid, each level reduced from the one above) starts at 1x. One that is
// scaled already goes back to 1x as for any other reason; one not scaled yet is kept from it before its first use as a target. A shape a fetch took out of the rule
// (g_rs_redok) and one not to be scaled for another reason are left as they are
int vka_tex_rs_keep(vka_img *im, VkCommandBuffer cb) {
    if (!im || im->rs_red || (im->rs_no && !im->rsc)) return 1;
    pthread_mutex_lock(&g_tmu);
    const nvn_texdesc *d = &im->d; int r = 1;
    if (vkrs_deny_find(&g_rs_redok, (uint32_t)d->width, (uint32_t)d->height, (uint32_t)im->f.vk, (uint32_t)d->flags) >= 0) { pthread_mutex_unlock(&g_tmu); return 1; }
    if (im->rsc) r = rs_set_locked(im, cb, 0, RS_WHY_REDUCE, 0) > 0;
    else if (im->inited && !rs_can_locked(im)) { pthread_mutex_unlock(&g_tmu); return 1; }   // not to be scaled for another reason: its shape is not denied for this one
    else {
        int a = vkrs_deny_add(&g_rs_deny, (uint32_t)d->width, (uint32_t)d->height, (uint32_t)im->f.vk, (uint32_t)d->flags, RS_WHY_REDUCE); im->rs_no = 1;
        if (a < 0 && !vka_rs_st.deny_full++) VRED("res scale: the table of shapes kept at 1x is full (%d): further shapes may change scale again and again", VKRS_DENY_MAX);
        rs_event(im, 0, RS_WHY_REDUCE, im->w, im->h, "kept at 1x before its first use as a target");
    }
    if (r) im->rs_red = 1;
    pthread_mutex_unlock(&g_tmu);
    return r;
}
// im is at 1x only because a reduction drew it, and a shader fetches from it by pixel beside scaled images (vka_draw.c rs_fetch_fix), which would otherwise take the
// scaled ones back to 1x (a half-size occlusion target upsampled beside the scaled depth, for one): its shape leaves the rule for the run, every live image of that
// shape may be scaled again, and im is scaled now. 1 = scaled
int vka_tex_rs_unkeep(vka_img *im, VkCommandBuffer cb) {
    if (!im || !im->rs_red || im->rsc) return im && im->rsc;
    pthread_mutex_lock(&g_tmu);
    uint32_t w = (uint32_t)im->d.width, h = (uint32_t)im->d.height, f = (uint32_t)im->f.vk, fl = (uint32_t)im->d.flags;
    int di = vkrs_deny_find(&g_rs_deny, w, h, f, fl);
    if (di >= 0 && g_rs_deny.e[di].why == RS_WHY_REDUCE) vkrs_deny_del(&g_rs_deny, w, h, f, fl);
    vkrs_deny_add(&g_rs_redok, w, h, f, fl, RS_WHY_REDUCE);
    for (int i = 0; i < g_nlive; i++) { vka_img *x = g_live[i];
        if (x->rs_red && (uint32_t)x->d.width == w && (uint32_t)x->d.height == h && (uint32_t)x->f.vk == f && (uint32_t)x->d.flags == fl) x->rs_red = x->rs_no = 0; }
    im->rs_red = im->rs_no = 0;
    int r = rs_set_locked(im, cb, 1, 0, 0);
    if (r < 0) im->rs_red = 1;   // (a holder reads it: asked again at the next such fetch; its shape is out of the rule already)
    if (vka_rs_st.red_back++ < 16) VLOG("res scale: texture serial %llu %ux%u format %#x, kept at 1x by a reduction, is fetched by pixel beside scaled images: its shape is scaled after all (%s)",
                                        (unsigned long long)im->serial, im->d.width, im->d.height, im->d.format, r > 0 ? "scaled now" : r < 0 ? "not this time" : "it cannot be scaled");
    pthread_mutex_unlock(&g_tmu);
    return r > 0;
}
// A pass that reduces a scaled image into targets at 1x reads it through a 1x copy, as the game drew it: the engine's first step down (Unity URP's bloom prefilter, one
// bilinear tap per target texel) reads 2x2 texels of a 1x source, its box, but only the middle 2x2 of the 4x4 a texel covers at 2x, and a thin bright line that moves
// slips in and out between the taps: the glow flickers from frame to frame. The copy is a linear blit (at 2x each texel the average of its 2x2), made again for each pass
// that reduces the image (rp: the render pass number it is for, vka_draw.c rs_reduce_fix). Made at first use as the texture at 1x, in no table, and kept out of the image
// cache's bytes (it is small beside its scaled image); destroyed with the image (img_destroy). Caller: no render pass open in cb
int vka_tex_rs_lo_fill(vka_img *im, VkCommandBuffer cb, uint64_t rp) {
    if (!im || !im->rsc || !cb) return 0;
    pthread_mutex_lock(&g_tmu);
    vka_img *lo = im->rs_lo;
    if (!lo) {
        tex_t t = {0}; t.serial = im->serial; t.mem = (uint8_t *)im->mem; t.d = im->d; t.L = im->L;
        g_rswant = 0; lo = img_create(&t, &im->f, 0);
        if (!lo) { pthread_mutex_unlock(&g_tmu); VRED_ONCE("res scale: no 1x copy of texture serial %llu %ux%u format %#x could be made: a pass that reduces it reads the scaled image (its glow may flicker)",
                                                           (unsigned long long)im->serial, im->d.width, im->d.height, im->d.format); return 0; }
        vka_img_barrier(cb, lo->img, lo->aspect, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL);
        lo->inited = 1; lo->gpu_owned = 1; lo->refs = INT32_MAX / 2;   // (only im reaches it: no release may destroy it, as rs_set_locked's holder)
        g_img_bytes -= lo->bytes; lo->bytes = 0;
        im->rs_lo = lo;
        static _Atomic int k; if (atomic_fetch_add(&k, 1) < 16) VLOG("res scale: texture serial %llu %ux%u format %#x (image %ux%u) gets a 1x copy for the passes that reduce it", (unsigned long long)im->serial,
                                                                     im->d.width, im->d.height, im->d.format, im->w, im->h);
    }
    vka_barrier(cb);   // what drew im, before the blit reads it
    VkImageSubresourceLayers sr = {im->aspect, 0, 0, 1};
    VkImageBlit b = {sr, {{0, 0, 0}, {(int32_t)im->w, (int32_t)im->h, 1}}, sr, {{0, 0, 0}, {(int32_t)lo->w, (int32_t)lo->h, 1}}};
    int lin = im->f.kind == 0 && (feats(im->f.vk) & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
    vk.vkCmdBlitImage(cb, im->img, VK_IMAGE_LAYOUT_GENERAL, lo->img, VK_IMAGE_LAYOUT_GENERAL, 1, &b, lin ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
    vka_barrier(cb);   // the blit, before the pass reads the copy
    lo->rs_lo_rp = rp; lo->last_frame = im->last_frame = vka_frame(); lo->last_fence = im->last_fence = vka_fence_last();
    im->rs_lo_rp = rp; vka_rs_st.lo_fill++;
    pthread_mutex_unlock(&g_tmu);
    return 1;
}
// PEANUTS end
// The `res scale:` line of the replay statistics (vka_exec.c mtl_set_frame, every 300 frames): empty while the scale is off
void vka_rs_stats(char *b, size_t n, double frames) {
    if (n) b[0] = 0;
    if (!vka_rs_on() || frames <= 0) return;
    static vka_rs_stat p;   // the counts at the last line (the per-frame rates)
    const vka_rs_stat *s = &vka_rs_st;
    int ni = 0; uint64_t by = 0;
    pthread_mutex_lock(&g_tmu);
    for (int i = 0; i < g_nlive; i++) if (g_live[i]->rsc) { ni++; by += g_live[i]->bytes; }
    uint32_t nd = g_rs_deny.n;
    pthread_mutex_unlock(&g_tmu);
    uint64_t downs = 0; for (int i = 0; i < RS_WHY_N; i++) downs += s->down[i];
    size_t k = (size_t)snprintf(b, n, "res scale: x%.2f, %d images scaled %.1f MiB (%.1f MiB at 1x); passes per frame %.1f scaled / %.1f at 1x / %.1f forced to 1x by a mix; scale-ups %llu (refused: memory %llu, busy %llu); scale-downs %llu [",
                                vka_rs_q / 4.0, ni, by / 1048576.0, by * 16.0 / (vka_rs_q * vka_rs_q) / 1048576.0, (s->pass_s - p.pass_s) / frames, (s->pass_1 - p.pass_1) / frames, (s->pass_forced - p.pass_forced) / frames,
                                (unsigned long long)s->up, (unsigned long long)s->up_no_mem, (unsigned long long)s->busy, (unsigned long long)downs);
    for (int i = 0; i < RS_WHY_N && k < n; i++) k += (size_t)snprintf(b + k, n - k, "%s%s %llu", i ? ", " : "", g_rs_short[i], (unsigned long long)s->down[i]);
    if (k < n) snprintf(b + k, n - k, "]; work left out %llu; shapes kept at 1x %u; reductions a fetch scaled after all %llu; 1x copies for reducing passes per frame %.1f, read %.1f; converting copies per frame %.1f; draws and dispatches that fetch by pixel, per frame: all scaled %.1f / none %.1f / mixed %.1f; "   // PEANUTS: reductions
                        "shaders made %u: %u with the fetch helper, %u with a fetch or size query and no helper",
                        (unsigned long long)s->skip, nd, (unsigned long long)s->red_back, (s->lo_fill - p.lo_fill) / frames, (s->lo_read - p.lo_read) / frames, (s->conv - p.conv) / frames, (s->fd_all - p.fd_all) / frames, (s->fd_none - p.fd_none) / frames, (s->fd_mixed - p.fd_mixed) / frames,
                        (unsigned)atomic_load(&vka_rs_sh[0]), (unsigned)atomic_load(&vka_rs_sh[1]), (unsigned)atomic_load(&vka_rs_sh[2]));
    p = *s;
}
// A 1x scratch image for a texel read of a scaled image (rt_gpu): transfer use only, kept and grown; under g_rtmu, whose holder waits for its copy before returning
static struct { VkImage img; VkDeviceMemory dm; VkFormat f; uint32_t w, h; } g_rs_rt;
static VkImage rs_rt_scratch(VkFormat f, uint32_t w, uint32_t h) {
    if (g_rs_rt.img && g_rs_rt.f == f && g_rs_rt.w >= w && g_rs_rt.h >= h) return g_rs_rt.img;
    if (g_rs_rt.img) { vk.vkDestroyImage(V.dev, g_rs_rt.img, NULL); vk.vkFreeMemory(V.dev, g_rs_rt.dm, NULL); g_rs_rt.img = VK_NULL_HANDLE; g_rs_rt.dm = VK_NULL_HANDLE; }
    VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D, .format = f, .extent = {w, h, 1}, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL, .usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED};
    VkImage img = VK_NULL_HANDLE; VkDeviceMemory dm = VK_NULL_HANDLE;
    VkResult r = vk.vkCreateImage(V.dev, &ci, NULL, &img);
    VkMemoryRequirements mr = {0}; if (!r) vk.vkGetImageMemoryRequirements(V.dev, img, &mr);
    VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = mr.size, .memoryTypeIndex = vka_mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0)};
    if (!r && ai.memoryTypeIndex == UINT32_MAX) r = VK_ERROR_OUT_OF_DEVICE_MEMORY;
    if (!r) r = vk.vkAllocateMemory(V.dev, &ai, NULL, &dm);
    if (!r) r = vk.vkBindImageMemory(V.dev, img, dm, 0);
    if (r) { VRED_ONCE("res scale: no 1x scratch image %ux%u of VkFormat %d for a texel read (%d): the read falls back to the texture's storage", w, h, f, r);
             if (img) vk.vkDestroyImage(V.dev, img, NULL); if (dm) vk.vkFreeMemory(V.dev, dm, NULL); return VK_NULL_HANDLE; }
    g_rs_rt.img = img; g_rs_rt.dm = dm; g_rs_rt.f = f; g_rs_rt.w = w; g_rs_rt.h = h;
    return img;
}
