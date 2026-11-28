// PEANUTS: direct 4-RTT dual-screen capture and GPU second-swapchain presentation (PN_DUAL_RTT=1).
// For dual-screen RE Engine DS collections (Mega Man Star Force Legacy Collection), the game renders
// each DS screen into a dedicated (512x384 DS layer + 1440x1080 HD UI layer) pair before its final
// 1920x1080 composite pass. This module latches those 4 pre-composite targets, composites the top
// pair at native 4:3 onto the main swapchain, and composites the bottom pair at native 4:3 onto a
// second Vulkan swapchain owned by the companion display's SurfaceView — zero CPU readback, zero
// composite cropping, and automatic passthrough whenever menus/overlays replace the 4-RTT composite.
#pragma once
#include <stdint.h>
#if defined(__ANDROID__)
#include <vulkan/vulkan.h>
#endif

struct vka_img;
struct ANativeWindow;

// Live switch (PN_DUAL_RTT=1)
int pn_dual_wanted(void);
// L2 (ZL) button swap toggle hooks called from hle_hid.c
void pn_dual_pad_note(uint64_t raw_p1_buttons);
uint64_t pn_dual_pad_mask(uint64_t buttons);

#if defined(__ANDROID__)
// Called from vka_draw.c (replay thread) on each draw while pn_dual_wanted() is on.
void pn_dual_observe_draw(struct vka_img *dst, VkFormat dst_fmt, uint32_t dst_w, uint32_t dst_h,
                          struct vka_img *tex, int blend_en, int src_rgb, int dst_rgb);
// Called from vka_present_fill (replay thread) at the end of a recorded frame.
void pn_dual_frame_fill(uint64_t ticket);
// Present thread lifecycle for main swapchain slot k (0..2).
void pn_dual_slot_done(unsigned k);
void pn_dual_slot_drop_all(void);
// Takes the frame's 4-RTT refs into main slot k; returns 1 when dual-screen gameplay is active.
int pn_dual_take(uint64_t ticket, unsigned k);
// Composites the top DS screen (RTOMain + RTOMainUI) at centered 4:3 into the main swapchain image.
// Leaves dst_img in VK_IMAGE_LAYOUT_PRESENT_SRC_KHR. Returns 1 if drawn, 0 to fall back to normal blit.
int pn_dual_draw_top(VkCommandBuffer cb, VkImage dst_img, VkImageView dst_view, VkFormat fmt,
                     VkExtent2D ext, VkSurfaceTransformFlagBitsKHR pre, unsigned k);
// Composites and presents the bottom DS screen (RTOSub + RTOSubUI) onto the aux swapchain (or clears black on menus).
void pn_dual_present_aux(uint64_t wait_fence, unsigned k);
#endif

// Launcher entry points (libnxhost.map: nxhost_*; host builds with -fvisibility=hidden)
__attribute__((visibility("default"))) int nxhost_peanuts_dual_wanted(void);
__attribute__((visibility("default"))) void nxhost_peanuts_aux_window(struct ANativeWindow *w);
__attribute__((visibility("default"))) void nxhost_peanuts_dual_touch(int id, float u, float v, int down);
