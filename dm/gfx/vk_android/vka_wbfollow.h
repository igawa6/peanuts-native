// Whether a tracked texture's storage, which the CPU wrote, is uploaded into its image again (vka_tex.c, the prepare's scan of tracked images), free of Vulkan so the Mac's
// unit test (dm/host/tests/t_android_wbfollow.c) can check it.
#ifndef VKA_WBFOLLOW_H
#define VKA_WBFOLLOW_H
#include <stdint.h>

// gpu_owned: the GPU has written the image; linear_wb: a linear texture whose GPU writes are copied back into its storage (vka_tex.c img_wb_kind); wb_pending: a copy-back is
// queued and not yet recorded; wb_fence: the fence of the submission that holds the last copy-back (0: none ran); done: the fence the GPU has passed
static inline int wbf_follows(int gpu_owned, int linear_wb, int wb_pending, uint64_t wb_fence, uint64_t done) {
    if (!gpu_owned) return 1;
    // a GPU-written linear texture: its storage holds the image's content once the copy-back has run on the GPU (as on the Switch, where a linear texture is plain memory),
    // so the CPU's later writes to it are the texture's. Before that (queued, or its submission unfinished) the storage is older than the image; with no copy-back it never held it
    return linear_wb && !wb_pending && wb_fence && done >= wb_fence;
}

// Whether the prepare's scan visits a live image. A full scan (the dirty-page clock, the sparse page table or the video pools moved) visits every image. A scan that only runs
// because a GPU-written linear texture waited for its copy-back at the last one (vka_img.wbf_waiting) visits just those: the CPU wrote nothing else, so walking the other
// ~1,000 images was the whole cost of the wait (Mario Kart World: 20-34 ms a frame of texture prepare with the waits, 0.5-4 ms without)
static inline int wbf_visit(int full_scan, int waiting) { return full_scan || waiting; }

// The view a texture ID is asked for (vka_tex.c vka_tex_id_view's storage argument): VKA_VIEW_STORAGE a storage view, else sampled; VKA_VIEW_RO a storage view whose shader
// only reads it. The host's own passes ask for VKA_VIEW_STORAGE alone: they write their output
#define VKA_VIEW_STORAGE 1
#define VKA_VIEW_RO 2
// A draw's image binding as that argument: storage_desc a storage-image descriptor, flags the binding's DMSH_VK_BF_*, rw DMSH_VK_BF_RW (vk_android_build.py m_writes: an
// OpImageWrite or an atomic on the variable). A shader image the shader only reads is read-only: Mario Tennis Fever's text compute reads its linear glyph cache as image 0
static inline int wbf_view_of_binding(int storage_desc, unsigned flags, unsigned rw) { return !storage_desc ? 0 : (flags & rw) ? VKA_VIEW_STORAGE : VKA_VIEW_STORAGE | VKA_VIEW_RO; }
// Whether the view marks its image GPU-written (vka_img_written): a storage view the shader writes. A read leaves the storage the image's content; marked written, a linear
// texture was copied back whole after the draw (vka_tex_writeback), over what the CPU wrote into its storage meanwhile (the glyph cache: letters of new text lost)
static inline int wbf_view_writes(int view) { return (view & VKA_VIEW_STORAGE) && !(view & VKA_VIEW_RO); }
#endif
