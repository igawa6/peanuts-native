// PEANUTS: source crop of buffers queued by the direct Vulkan guest, in pre-transform pixels.
#pragma once
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

struct ANativeWindow;
typedef struct { int32_t left, top, right, bottom; } guest_crop_rect;

static inline guest_crop_rect guest_buffer_crop(const int xywh[4], uint32_t width, uint32_t height) {
    int x = xywh[0], y = xywh[1], w = xywh[2], h = xywh[3];
    // VI packs four nonnegative 16-bit fields. As in the Metal present, an absent
    // or out-of-bounds crop uses the full image. Null also undoes an earlier crop.
    if (w <= 0 || h <= 0 || x < 0 || y < 0 || (uint64_t)x + w > width || (uint64_t)y + h > height ||
        (!x && !y && (uint32_t)w == width && (uint32_t)h == height)) return (guest_crop_rect){0};
    return (guest_crop_rect){x, y, x + w, y + h};
}

// AOSP frameworks/native/libs/nativewindow/include/system/window.h: native_window_set_crop
// is an inline call to perform(NATIVE_WINDOW_SET_CROP, rect), not an NDK export. EGL drivers
// use this binary-compatible window prefix. Check its magic and size before reading a slot;
// do not look up a private libgui symbol or treat an opaque platform object as our C type.
typedef int (*guest_window_perform)(struct ANativeWindow *, int, ...);
typedef struct {
    int32_t magic, version;
    void *reserved[4];
    void (*inc_ref)(void *), (*dec_ref)(void *);
    uint32_t flags;
    int32_t min_interval, max_interval;
    float xdpi, ydpi;
    intptr_t oem[4];
    void (*swap_interval)(void), (*dequeue_old)(void), (*lock_old)(void), (*queue_old)(void), (*query)(void);
    guest_window_perform perform;
} guest_window_prefix;
_Static_assert(offsetof(guest_window_prefix, perform) == 152, "Android arm64 native window ABI");

static inline int guest_set_buffer_crop(struct ANativeWindow *win, const guest_crop_rect *crop) {
    int32_t header[2];
    memcpy(header, win, sizeof header);
    if (header[0] != 0x5f776e64 /* _wnd */ || header[1] < (int32_t)sizeof(guest_window_prefix)) return -EINVAL;
    guest_window_perform perform;
    memcpy(&perform, (const char *)win + offsetof(guest_window_prefix, perform), sizeof perform);
    return perform ? perform(win, 3 /* NATIVE_WINDOW_SET_CROP */, crop->right ? crop : NULL) : -EINVAL;
}
