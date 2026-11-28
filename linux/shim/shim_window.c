// ANativeWindow: a counted record of the window (android/native_window.h in this folder); whoever made it frees what display and surface point at
#include <android/native_window.h>
#include <stdatomic.h>
void ANativeWindow_acquire(ANativeWindow *w) { if (w) atomic_fetch_add((_Atomic int *)&w->refs, 1); }
void ANativeWindow_release(ANativeWindow *w) { if (w) atomic_fetch_sub((_Atomic int *)&w->refs, 1); }
int32_t ANativeWindow_getWidth(ANativeWindow *w) { return w ? w->width : 0; }
int32_t ANativeWindow_getHeight(ANativeWindow *w) { return w ? w->height : 0; }
