// ANativeWindow on Linux: a counted record of the window the Vulkan surface is made from (linux/shim/shim_window.c)
#pragma once
#include <stdint.h>
typedef struct ANativeWindow ANativeWindow;
struct ANativeWindow { int refs, width, height; void *display, *surface; uint32_t xcb_window; int kind; };   // kind: 0 none, 1 Wayland (wl_display, wl_surface), 2 xcb (xcb_connection_t, window)
void ANativeWindow_acquire(ANativeWindow *w);
void ANativeWindow_release(ANativeWindow *w);
int32_t ANativeWindow_getWidth(ANativeWindow *w);
int32_t ANativeWindow_getHeight(ANativeWindow *w);
enum { ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT = 0, ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE = 1 };
