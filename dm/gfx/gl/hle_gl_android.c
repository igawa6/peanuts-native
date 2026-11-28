// PEANUTS probe (not upstream-bound as written): an OpenGL front for the Android host, bring-up only.
//
// Games that render through EGL (Demon Throttle `01001DA015650000`, GameMaker's runner; Balatro, Digimon Story
// Cyber Sleuth, Well Dweller) have no graphics layer on Android at all: the build leaves every Objective-C file
// of dm/gfx out (android/host/CMakeLists.txt), so their EGL imports end in the host's named stubs, that stub
// answers 0, eglGetProcAddress answers 0, and the game's own function table jumps to address 0. Measured on the
// AYN Thor (2026-11-28): the game reaches nnMain, calls eglGetDisplay / eglInitialize / eglChooseConfig /
// eglCreateWindowSurface / eglCreateContext / eglMakeCurrent / eglGetProcAddress (all UNIMPL), then
// `CRASH sig=11 addr=0 pc=0 lr=main+0x808d3c`, where main+0x808d38 is `blr x8` on a pointer loaded from a
// global (acf57a48288c9f5826a7a71618031fb9). Upstream's GL layer for Mac and iOS is dm/gfx/gl/hle_gl.m: 2,069
// lines of Objective-C, 152 GL entry points on Apple's Metal, with the game's GLSL translated to a .metallib
// offline by dm/gfx/tools/gl_shaders.py. None of that is here.
//
// What this does: answers the EGL calls a GL title starts with (display, config, context, surface, the game's
// eglMakeCurrent makes the driver's context current on the game's thread), keeps the few GL names whose answer is
// the measurement this platform has never had — what the driver reports as GL and GLSL, what it makes of the
// shaders this engine writes — and hands every other GL name straight to the driver's own OpenGL ES: the game's own
// code runs natively, so the driver's entry points are callable by it as they are; that is what makes an Android
// GL front a different piece of work from the 2,069-line Metal one. The game's drawing size comes from its own
// viewport, and the frames go to the launcher's window: this file asks the host's Vulkan present for that window
// (nxhost_window_take_for_gl), makes a surface of it, and gives that surface to the game's context — the game's
// frames are then drawn in the window's own back buffer and swapped through the driver's EGL, with no copy in
// between, and its eglQuerySurface answers says the size that buffer really has. Where
// there is no window — a game started before the launcher's surface exists, or a windowless run — the first frames
// are read back from the GL surface and written as images in the app's cache folder, which is how this front was
// first measured on a device.
#include "../../host/hle.h"
#include "../vk_android/vk_android.h"   // nxhost_window_take_for_gl: the window the frames go to, and who holds it
#include <android/native_window.h>      // the window's buffer size and transform
#include <dlfcn.h>
#include <stdint.h>
#include <time.h>                      // the wall clock the present's frame rate is measured against
#include <pthread.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// The per-frame work the host does for a shown frame, which the Vulkan present calls from its own present thread
// (vka_present.c). A front that presents itself has to give the game the same, or the launcher's frame-time graph
// (the overlay's, and the second screen's stats page) has nothing to draw and the companion never starts.
__attribute__((weak)) void dm_guest_frame_tick(void) {}   // hle_os.c provides the strong definition (DM_DETCLOCK deterministic guest clock); a no-op when graphics unit tests do not link the host
__attribute__((weak)) void peanuts_tick(void);            // dm/peanuts/peanuts.c: the frame-time ring, the graph's bars, the companion's tick; absent in a build without it

// The driver's own OpenGL, reached the way the Android host already reaches the GPU: through the driver's EGL, the
// same libEGL.so the launcher swaps a driver for. dlopen'd, not linked: the host build has no EGL headers.
static void *(*xr_eglGetDisplay)(void *);
static int (*xr_eglInitialize)(void *, int *, int *);
static int (*xr_eglChooseConfig)(void *, const int *, void **, int, int *);
static void *(*xr_eglCreateContext)(void *, const void *, void *, const int *);
static void *(*xr_eglCreateWindowSurface)(void *, const void *, void *, const int *);
static void *(*xr_eglCreatePbufferSurface)(void *, const void *, const int *);
static int (*xr_eglMakeCurrent)(void *, void *, void *, void *);
static int (*xr_eglSwapBuffers)(void *, void *);   // the driver's own: this file's eglSwapBuffers answers the game's import
static int (*xr_eglDestroySurface)(void *, void *);
static int (*xr_win_set_transform)(void *, int);   // ANativeWindow_setBuffersTransform(): the NDK declares it but its
                                                   // libandroid stub has no symbol, so the running system's is looked up
static void *(*xr_eglGetProcAddress)(const char *);
static void *xr_libEGL;                     // the dlopen'd libEGL: where the core EGL names (not eglGetProcAddress's) come from
static unsigned (*xr_eglGetError)(void);
static int (*xr_eglGetConfigAttrib)(void *, const void *, unsigned, int *);
static const char *(*xr_eglQueryString)(void *, unsigned);
static int (*xr_eglQuerySurface)(void *, void *, unsigned, int *);   // the driver's: what size a surface really has
// The driver's GL entry points this file calls directly (the rest the game takes from the driver itself).
static const void *(*xr_glGetString)(unsigned);
static void *xr_dpy, *xr_surf, *xr_ctx;      // the host's own EGL display, its pbuffer and context
static void *xr_cfg;                        // the config the context and the pbuffer were made from
static void *xr_cfg_win;                    // a config that takes a window surface: what the game's own surface needs
static void *xr_win_surf;                   // the driver's EGL window surface on the launcher's window
static struct ANativeWindow *g_win;         // that window, taken from the host's present path (vk_android.h)
static int win_w, win_h;                    // the size the surface really has, read back off it
static int win_ask_w, win_ask_h;            // the size the game was last asking for: only a change of that rebuilds
static int win_turn;                        // the degrees this window asks its buffers to be turned (its Vulkan surface's answer)
// The panel's composition grid, which belongs to this surface: what the frames are aimed at. Described with the
// pacer below (panel_grid, panel_aim); declared here because the window's life is what creates and destroys it.
static int g_ts_on;                         // that surface's frame timings are being collected, so the panel can be asked
static long long grid_int, grid_slot;       // the panel's period, and its next composition event, as it last answered
static long long grid_read;                 // when that was answered
static long long aimed_slot, grid_acc;      // the slot the last frame was aimed at, and the fractional part of the walk along the grid
static int g_swap_interval = -1;            // the swap interval the driver was last asked for (-1: never asked); see set_swap_interval below
static int g_interval_refused;              // a number this driver refused (it is not asked again while the pacer wants it)
static int interval_said;                   // a refusal is said once, not per frame
// The frames the panel actually showed, and when it showed them: described with the probes below (show_id_take,
// show_seen, show_report). Declared here because the window's life is what starts and stops following them.
#define SHOW_LAG 4                        // frames between queueing one and asking for its times: the panel needs a few to scan one out
#define SHOW_RING (SHOW_LAG + 1)          // the ids kept: the current one and the SHOW_LAG before it
#define SHOW_BUCKETS 80                   // 0.5 ms buckets up to 40 ms: fine enough to tell 8.33 ms from 8.44 ms apart
// Both timing probes keep their samples in these units: ten seconds of frames in 0.1 ms, so that p99 and p99.5 are
// more than the largest sample again (see pace_measure and show_report below).
#define PACE_WINDOW 600
#define PACE_UNIT 100000LL
static unsigned long long show_ring[SHOW_RING];
static int show_w, show_filled;
static long long show_last;               // the display start of the last frame we learned about
static int show_iv[PACE_WINDOW], show_n;  // the intervals between display starts
static int show_hist[SHOW_BUCKETS];
static int show_none, show_pending;       // a driver that does not answer the call is asked once; pending times are counted
static int xr_pb_w, xr_pb_h;                // that surface's size, which is what a read back would be at
static pthread_t host_gl_thread;
static int host_gl_surface(int w, int h);   // made below: the surface this context draws into
static int g_host_gl;         // a host ES context is current on the thread that made it
static int g_host_gl_tried;
static int g_ctx_major;       // the OpenGL ES major version the host context was created as; 0 = asked with no version attributes

// EGL's constants and types, by their standard values (KHR): this file cannot include EGL/egl.h.
#define EGL_NONE 0x3038
#define EGL_BUFFER_SIZE 0x3020
#define EGL_CONFIG_CAVEAT 0x3027
#define EGL_CONFIG_ID 0x3028
#define EGL_LEVEL 0x3029
#define EGL_NATIVE_VISUAL_ID 0x302E
#define EGL_SURFACE_TYPE 0x3042
#define EGL_RENDERABLE_TYPE 0x3040
#define EGL_SAMPLES 0x3031
#define EGL_RED_SIZE 0x3024
#define EGL_GREEN_SIZE 0x3023
#define EGL_BLUE_SIZE 0x3022
#define EGL_ALPHA_SIZE 0x3021
#define EGL_DEPTH_SIZE 0x3025
#define EGL_STENCIL_SIZE 0x3026
#define EGL_WIDTH 0x3057
#define EGL_HEIGHT 0x3056
#define EGL_LUMINANCE_SIZE 0x30D4
#define EGL_ALPHA_MASK_SIZE 0x303E
#define EGL_COLOR_BUFFER_TYPE 0x303F
#define EGL_RENDER_BUFFER 0x30D8
#define EGL_SINGLE_BUFFER 0x3085
#define EGL_WINDOW_BIT 0x04
#define EGL_PBUFFER_BIT 0x01
#define EGL_OPENGL_ES_API 0x30A0
#define EGL_OPENGL_ES3_BIT 0x0040
#define EGL_CONTEXT_MAJOR_VERSION 0x3098
#define EGL_CONTEXT_MINOR_VERSION 0x3099
#define EGL_EXTENSIONS 0x3055
#define EGL_VENDOR 0x3053
#define EGL_CLIENT_APIS 0x308D
#define EGL_BAD_CONFIG 0x305C
#define EGL_BAD_CURRENT_SURFACE 0x305D
#define EGL_BAD_MATCH 0x3008
#define EGL_BAD_PARAMETER 0x300C
#define EGL_BAD_ACCESS 0x3002
#define EGL_BAD_ALLOC 0x3003
#define EGL_BAD_ATTRIBUTE 0x3004
#define EGL_BAD_NATIVE_PIXMAP 0x300A
#define EGL_BAD_NATIVE_WINDOW 0x300B
#define EGL_CONTEXT_INVISIBLE 0x30E0
#define EGL_CONTEXT_RELEASE_BEHAVIOR 0x32FB
#define EGL_CONTEXT_RELEASE_BEHAVIOR_NONE 0
#define EGL_NO_CONTEXT ((void *)0)
#define EGL_TRUE 1
#define EGL_FALSE 0
#define EGL_SUCCESS 0x3000
#define EGL_BAD_DISPLAY 0x3003
#define EGL_NOT_INITIALIZED 0x3001
#define EGL_NO_ERROR EGL_SUCCESS

// OpenGL tokens this file answers by (the same numbers the game uses).
#define GL_VENDOR 0x1F00
#define GL_RENDERER 0x1F01
#define GL_VERSION 0x1F02
#define GL_EXTENSIONS 0x1F03
#define GL_NUM_EXTENSIONS 0x82DF
#define GL_SHADING_LANGUAGE_VERSION 0x8B8C
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B4F
#define GL_DELETE_STATUS 0x8B80
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_VALIDATE_STATUS 0x8B83
#define GL_INFO_LOG_LENGTH 0x8B84
#define GL_ACTIVE_UNIFORMS 0x8B86
#define GL_ACTIVE_UNIFORM_MAX_LENGTH 0x8B87
#define GL_ACTIVE_ATTRIBUTES 0x8B89
#define GL_ACTIVE_ATTRIBUTE_MAX_LENGTH 0x8B8A
#define GL_SHADER_SOURCE_LENGTH 0x8B88
#define GL_OBJECT_LENGTH 0x8B8B
#define GL_DEBUG_LOG_LENGTH 0x200
#define GL_NO_ERROR 0
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_FRAMEBUFFER 0x8D40
#define GL_READ_FRAMEBUFFER 0x8CA8
#define GL_DRAW_FRAMEBUFFER 0x8CA1
#define GL_RGBA 0x1908
#define GL_UNSIGNED_BYTE 0x1401

// Registration is the upstream pattern (dm/host/hle.h HLE(), dm/gfx/gl/hle_gl.m GL()): a name and a function in
// the swhle table the loader resolves guest imports from.
#define GL(name, ret, ...)                                                                        \
    static ret h_##name(__VA_ARGS__);                                                             \
    PLAT_TABLE(swhle) static const hle_ent e_##name = {#name, (void *)h_##name};                 \
    static ret h_##name(__VA_ARGS__)

uint64_t resolve_import(module_t *m, const char *name);   // the same entry points direct imports get (dm/host/hle.c)

static const char *host_str(unsigned name) {
    if (xr_glGetString) { const void *s = xr_glGetString(name); if (s) return s; }
    return "";   // no driver string to answer from: the caller says which case it is in the log
}

// The last EGL error from the driver, as text: every failure this file reports says why, not just that.
static const char *egl_err(void) {
    static const struct { unsigned v; const char *name; } names[] = {
        { EGL_SUCCESS, "no error" }, { EGL_BAD_DISPLAY, "EGL_BAD_DISPLAY" }, { EGL_NOT_INITIALIZED, "EGL_NOT_INITIALIZED" },
        { EGL_BAD_ACCESS, "EGL_BAD_ACCESS" }, { EGL_BAD_ALLOC, "EGL_BAD_ALLOC" }, { EGL_BAD_ATTRIBUTE, "EGL_BAD_ATTRIBUTE" },
        { EGL_BAD_CONFIG, "EGL_BAD_CONFIG" }, { EGL_BAD_CURRENT_SURFACE, "EGL_BAD_CURRENT_SURFACE" },
        { EGL_BAD_MATCH, "EGL_BAD_MATCH" }, { EGL_BAD_NATIVE_PIXMAP, "EGL_BAD_NATIVE_PIXMAP" },
        { EGL_BAD_NATIVE_WINDOW, "EGL_BAD_NATIVE_WINDOW" }, { EGL_BAD_PARAMETER, "EGL_BAD_PARAMETER" },
        { EGL_CONTEXT_INVISIBLE, "EGL_CONTEXT_INVISIBLE" },
    };
    unsigned e = xr_eglGetError ? xr_eglGetError() : EGL_SUCCESS;
    for (unsigned i = 0; i < sizeof names / sizeof names[0]; i++) if (names[i].v == e) return names[i].name;
    static char other[24];
    snprintf(other, sizeof other, "unknown 0x%x", e);
    return other;
}

static void host_gl_init(void) {
    if (g_host_gl_tried) return;
    g_host_gl_tried = 1;
    void *h = dlopen("libEGL.so", RTLD_NOW);
    if (!h) { LOG("[gl] [RED] libEGL.so cannot be opened: no OpenGL for this game on this Android host"); return; }
    xr_libEGL = h;   // kept: eglGetProcAddress answers no core EGL name (eglSwapInterval included), so core calls come from here
    xr_eglGetError = dlsym(h, "eglGetError");
    xr_eglGetConfigAttrib = dlsym(h, "eglGetConfigAttrib");
    xr_eglQueryString = dlsym(h, "eglQueryString");
    xr_eglQuerySurface = dlsym(h, "eglQuerySurface");
    xr_eglGetDisplay = dlsym(h, "eglGetDisplay");
    xr_eglInitialize = dlsym(h, "eglInitialize");
    xr_eglChooseConfig = dlsym(h, "eglChooseConfig");
    xr_eglCreateContext = dlsym(h, "eglCreateContext");
    xr_eglCreateWindowSurface = dlsym(h, "eglCreateWindowSurface");
    xr_eglCreatePbufferSurface = dlsym(h, "eglCreatePbufferSurface");
    xr_eglMakeCurrent = dlsym(h, "eglMakeCurrent");
    xr_eglSwapBuffers = dlsym(h, "eglSwapBuffers");
    xr_eglDestroySurface = dlsym(h, "eglDestroySurface");
    xr_eglGetProcAddress = dlsym(h, "eglGetProcAddress");
    xr_win_set_transform = dlsym(RTLD_DEFAULT, "ANativeWindow_setBuffersTransform");
    if (!xr_eglGetDisplay || !xr_eglInitialize || !xr_eglCreateContext || !xr_eglMakeCurrent || !xr_eglGetProcAddress) {
        LOG("[gl] [RED] the driver's EGL lacks the calls this probe needs");
        return;
    }
    void *dpy = xr_eglGetDisplay(NULL);   // EGL_DEFAULT_DISPLAY: the whole device, not one window
    int maj = 0, min = 0;
    if (!dpy || !xr_eglInitialize(dpy, &maj, &min)) { LOG("[gl] [RED] the driver's EGL would not initialize (eglGetError %s)", egl_err()); return; }
    // Which EGL this process is talking to, and what it advertises: the Android wrapper over the system GL driver,
    // or a driver's own EGL. A GL front can only use what this answers.
    if (xr_eglQueryString) {
        const char *cv = xr_eglQueryString(NULL /*EGL_NO_DISPLAY*/, EGL_VENDOR);
        const char *apis = xr_eglQueryString(NULL, EGL_CLIENT_APIS);
        const char *dv = xr_eglQueryString(dpy, EGL_VENDOR);
        const char *ext = xr_eglQueryString(dpy, EGL_EXTENSIONS);
        LOG("[gl] the EGL client is %s, APIs %s; the display answers %d.%d, vendor %s",
            cv && *cv ? cv : "(no vendor string)", apis && *apis ? apis : "(none)", maj, min, dv && *dv ? dv : "(unknown)");
        LOG("[gl] EGL extensions: %s", ext && *ext ? ext : "(none)");
    }
    // Two configs: a pbuffer one (what a context with no window needs, which is all this probe can make) and a
    // window one (what the real front will need). Each says what the driver actually handed back.
    int pbattrs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                      EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    int winattrs[] = { EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                       EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    const char *cfg_kind[2] = { "pbuffer", "window" };
    int *cfg_attrs[2] = { pbattrs, winattrs };
    void *cfgs[2] = { NULL, NULL };
    for (unsigned c = 0; c < 2; c++) {
        int n = 0;
        if (!xr_eglChooseConfig || !xr_eglChooseConfig(dpy, cfg_attrs[c], &cfgs[c], 1, &n) || !cfgs[c] || n <= 0) {
            cfgs[c] = NULL;
            LOG("[gl] no %s ES3 config (eglGetError %s)", cfg_kind[c], egl_err());
            continue;
        }
        int id = 0, rt = 0, st = 0, vis = 0;
        if (xr_eglGetConfigAttrib) {
            xr_eglGetConfigAttrib(dpy, cfgs[c], EGL_CONFIG_ID, &id);
            xr_eglGetConfigAttrib(dpy, cfgs[c], EGL_RENDERABLE_TYPE, &rt);
            xr_eglGetConfigAttrib(dpy, cfgs[c], EGL_SURFACE_TYPE, &st);
            xr_eglGetConfigAttrib(dpy, cfgs[c], EGL_NATIVE_VISUAL_ID, &vis);
        }
        LOG("[gl] a %s config: id %d, renderable %#x, surface type %#x, native visual %d", cfg_kind[c], id, rt, st, vis);
    }
    xr_cfg_win = cfgs[1];   // the window one: what the game's own surface is made from, once the window is taken
    // The asks, newest first: what a game wants is an OpenGL ES 3.x context, and an attribute list this EGL does
    // not know is EGL_BAD_ATTRIBUTE, so which list was used and which was refused is part of the answer. An
    // attribute-less ask only ever gives the oldest profile the driver still carries, and that string is what the
    // game would then read as its own GL version.
    const int ask_es32[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE };
    const int ask_es31[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 1, EGL_NONE };
    const int ask_es30[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
    const int ask_es20[] = { EGL_CONTEXT_MAJOR_VERSION, 2, EGL_NONE };
    const int ask_plain[] = { EGL_NONE };
    const struct { const char *what; const int *attrs; } asks[] = {
        { "OpenGL ES 3.2", ask_es32 }, { "OpenGL ES 3.1", ask_es31 }, { "OpenGL ES 3.0", ask_es30 },
        { "OpenGL ES 2.0", ask_es20 }, { "no context attributes", ask_plain },
    };
    void *ctx = NULL; void *cfg = NULL; const char *cfg_found = NULL, *how = NULL;
    int ctx_major = 0;
    for (unsigned c = 0; c < 2 && !ctx; c++) {
        if (!cfgs[c]) continue;
        for (unsigned a = 0; a < sizeof asks / sizeof asks[0] && !ctx; a++) {
            ctx = xr_eglCreateContext(dpy, cfgs[c], EGL_NO_CONTEXT, (int *)asks[a].attrs);
            if (!ctx) {
                LOG("[gl] no context from the %s config asked as %s (eglGetError %s)", cfg_kind[c], asks[a].what, egl_err());
                continue;
            }
            cfg = cfgs[c]; cfg_found = cfg_kind[c]; how = asks[a].what;
            ctx_major = asks[a].attrs[0] == EGL_CONTEXT_MAJOR_VERSION ? asks[a].attrs[1] : 0;   // 0: no version asked, the driver's oldest profile
        }
    }
    if (!ctx) { LOG("[gl] [RED] this EGL makes no context in this process: see the lines above for each config and ask it refused"); return; }
    // What this context really is, kept for the present pass: an ES 2.x context has no VAOs, no ES 3.00 shaders,
    // and no glGetIntegerv for ES 3.x names — asking those answers GL_INVALID_ENUM and leaves the state unknown.
    // (Measured on the AYN Thor, 2026-11-28: this EGL refused every ES 3.x ask and answered ES 2.0, while its
    // eglGetProcAddress still handed out glBindVertexArray — and binding it, and VAO 0 back, silenced the game's
    // client-side vertex arrays: from then on its own framebuffer held only the colours of its per-batch clears.)
    g_ctx_major = ctx_major;
    LOG("[gl] the host's own GL context: the %s config, asked as %s", cfg_found, how);
    xr_dpy = dpy; xr_ctx = ctx; xr_cfg = cfg; host_gl_thread = pthread_self();
    xr_glGetString = xr_eglGetProcAddress("glGetString");
    if (!host_gl_surface(1280, 720)) {
        LOG("[gl] [RED] no surface for this context: the game's GL has nothing to draw into (eglGetError %s)", egl_err());
        return;
    }
    g_host_gl = 1;
    LOG("[gl] the driver's OpenGL is open for this game's GL (EGL %d.%d, GL %s, GLSL %s)",
        maj, min, host_str(GL_VERSION), host_str(GL_SHADING_LANGUAGE_VERSION));
}

// The surface the game's GL draws into: a pbuffer, because the window belongs to the host's Vulkan present. This
// is the one place a real GL front would differ — an EGL window surface on the launcher's ANativeWindow, and the
// host not putting a Vulkan swapchain on that window — which is why it is kept in one function.
static int host_gl_surface(int w, int h) {
    if (!xr_eglCreatePbufferSurface || !xr_eglMakeCurrent) return 0;
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    int pb[] = { EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE };
    void *surf = xr_eglCreatePbufferSurface(xr_dpy, xr_cfg, pb);
    if (!surf) {   // a surfaceless context still answers queries and compiles shaders, just nothing to read back
        LOG("[gl] no %dx%d pbuffer from this EGL (eglGetError %s): continuing with no surface", w, h, egl_err());
        xr_eglGetError();
    }
    if (!xr_eglMakeCurrent(xr_dpy, surf, surf, xr_ctx)) {
        LOG("[gl] [RED] the context would not become current, with a surface or without one: eglGetError %s", egl_err());
        return 0;
    }
    if (xr_surf && xr_eglDestroySurface) xr_eglDestroySurface(xr_dpy, xr_surf);
    xr_surf = surf; xr_pb_w = w; xr_pb_h = h;
    return 1;
}

// The window the game's frames go to. It belongs to the launcher and is normally held by the host's Vulkan
// present; this asks for it (nxhost_window_take_for_gl, vka_present.c), which takes the Vulkan surface and
// swapchain off the window: one buffer queue, one presenter.
//
// The buffer has to be the size the game draws at (1280x720 in Demon Throttle) and the game has to be told that
// same number, which is what its own eglQuerySurface answers here come from. The queue does not hand that over
// for free: this launcher asks its apps for buffers turned to the panel's own orientation — the panel is portrait,
// this game draws landscape, and that is the same contract the Vulkan path follows with preTransform ("surface
// 0x2: pre-rotated" on its swapchain line). A size request is transposed under that hint, so asking for 1280x720
// gets a 720x1280 queue, in which a landscape picture shows only its lower left, stretched to fill. So the
// buffer's transform is set to none before the size is asked for, and the size the surface really has is read
// back off it and logged: what the game draws, what the game is told, and what reaches the screen must be one
// size. With the transform left to the system it rotates the layer on the way out, which is what the Vulkan path
// calls DM_VK_PRETRANSFORM=0.
//
// The window surface stopped working (its swap failed): the launcher destroyed the surface it was made on, which
// is what a rotation or the app leaving and coming back does. Let go of the surface and of this file's reference
// of the window; the next frame asks again and gets the window the launcher has now.
// The window surface becomes the game's own: h_eglMakeCurrent binds it once there is one, the first swap makes it
// current where the game drew its first frames, and eglQuerySurface answers its size. The game's viewport, its
// framebuffers and its own queries then all follow the window — there is no second size to keep in step.
static int g_handed;             // the game's context has been made current on the window surface
static void drop_window(void) {
    if (xr_win_surf && xr_eglDestroySurface) xr_eglDestroySurface(xr_dpy, xr_win_surf);
    xr_win_surf = NULL; win_w = win_h = win_ask_w = win_ask_h = 0;
    g_handed = 0;
    g_ts_on = 0; grid_int = grid_slot = grid_read = aimed_slot = grid_acc = 0;   // that surface was the one the panel's grid was read from
    show_w = show_filled = show_n = show_pending = 0; show_last = 0;
    memset(show_ring, 0, sizeof show_ring); memset(show_hist, 0, sizeof show_hist);   // and its frames were the ones being followed
    // The window surface was the one the game's context drew into: with it destroyed, put the context back on the
    // pbuffer so its next frame lands somewhere until the new window is taken.
    if (g_host_gl && xr_ctx && xr_surf && xr_eglMakeCurrent) xr_eglMakeCurrent(xr_dpy, xr_surf, xr_surf, xr_ctx);
    if (g_win) ANativeWindow_release(g_win);
    g_win = NULL;
}

static int take_window_at(int w, int h) {
    if (!g_host_gl || !(xr_cfg || xr_cfg_win) || !xr_eglCreateWindowSurface || !xr_eglDestroySurface || w <= 0 || h <= 0) return 0;
    if (xr_win_surf && w == win_ask_w && h == win_ask_h) return 1;
    if (!g_win) {
        g_win = nxhost_window_take_for_gl();
        if (!g_win) { LOG("[gl] [RED] this process has no window: the game's frames have nothing to go to"); return 0; }
    }
    int ww = ANativeWindow_getWidth(g_win), wh = ANativeWindow_getHeight(g_win);
    int bw = 0, bh = 0;
    win_turn = 0;
    // What a Vulkan surface of this window would be sized and turned by — read here because it is what the
    // Vulkan path presents by, and a GL front has to know why it does the same or not.
    int qw = 0, qh = 0, qturn = 0;
    if (!nxhost_window_form_for_gl(g_win, &qw, &qh, &qturn))
        LOG("[gl] [RED] this window's own shape is unknown to Vulkan: the game's frames go by the window's size alone");
    // The buffer goes in the shape this queue can be *told* about. A Vulkan surface asks for the panel's shape and
    // hands the turn to the compositor as preTransform; an EGL window surface has no such parameter, and this
    // Android's libandroid does not export ANativeWindow_setBuffersTransform either (measured 2026-11-28 on the
    // AYN Thor's Android 13), so a turned buffer is one the compositor reads as unrotated: it fitted a 1080x1920
    // buffer over the window's 1920x1080 with "geomLayerTransform (SCALE) 1.7778 / 0.5625" — the game's picture
    // turned and stretched over the screen. The window's own shape is the one it fits without changing the aspect,
    // and the panel's turn stays the compositor's (geomBufferTransform 7 on the launcher's own layer too).
    if (xr_win_set_transform && qturn && qw > 0 && qh > 0) {
        bw = qw; bh = qh; win_turn = qturn;   // a queue that can be told: the Vulkan path's contract
        xr_win_set_transform(g_win, qturn == 90 ? ANATIVEWINDOW_TRANSFORM_ROTATE_90
                                  : qturn == 180 ? ANATIVEWINDOW_TRANSFORM_ROTATE_180
                                  : ANATIVEWINDOW_TRANSFORM_ROTATE_270);
    } else {
        bw = ww > 0 ? ww : qw; bh = wh > 0 ? wh : qh;
        if (qturn) LOG("[gl] this window asks its buffers turned %d degrees, which its queue cannot be told: the buffers stay %dx%d and the turn stays the compositor's",
                       qturn, bw, bh);
    }
    if (bw <= 0 || bh <= 0) {
        bw = w; bh = h;
        LOG("[gl] [RED] this window's own size is unknown: the game's frames go to a %dx%d buffer, the size the game asked for", bw, bh);
    }
    if (ANativeWindow_setBuffersGeometry(g_win, bw, bh, WINDOW_FORMAT_RGBA_8888)) {
        LOG("[gl] [RED] the window would not take a %dx%d RGBA_8888 buffer: presenting nothing", bw, bh);
        return 0;
    }
    // A context only draws into a surface made from a compatible config. The context usually comes from the
    // pbuffer config (it is asked for first), so its own config is tried first, then the window one.
    void *cfgs_try[2] = { xr_cfg, xr_cfg_win };
    void *s = NULL; int from = -1;
    for (int c = 0; c < 2; c++) {
        if (!cfgs_try[c] || (c && cfgs_try[c] == cfgs_try[0])) continue;
        s = xr_eglCreateWindowSurface(xr_dpy, cfgs_try[c], g_win, NULL);
        if (s) { from = c; break; }
        LOG("[gl] no window surface from the %s config (eglGetError %s)", c ? "window" : "context's", egl_err());
    }
    if (!s) { LOG("[gl] [RED] no EGL window surface on the launcher's window"); return 0; }
    // What the surface really is, read back rather than assumed: the game is told these numbers, and a queue that
    // answers a different size than the one asked for is the difference between the game's picture and a piece of it.
    int sw = 0, sh = 0;
    if (xr_eglQuerySurface) { xr_eglQuerySurface(xr_dpy, s, EGL_WIDTH, &sw); xr_eglQuerySurface(xr_dpy, s, EGL_HEIGHT, &sh); }
    if (xr_win_surf) xr_eglDestroySurface(xr_dpy, xr_win_surf);
    xr_win_surf = s; win_w = sw ? sw : bw; win_h = sh ? sh : bh; win_ask_w = w; win_ask_h = h;
    LOG("[gl] the launcher's window is taken for the game's frames: an EGL surface of %dx%d (asked %dx%d for the game's %dx%d) on a %dx%d window that asks its buffers turned %d degrees, from the %s config",
        win_w, win_h, bw, bh, w, h, ww, wh, win_turn, from ? "window" : "context's own");
    if (win_w != bw || win_h != bh)
        LOG("[gl] [RED] this window answers %dx%d to a request for %dx%d: the picture has to be drawn into the size the queue really has", win_w, win_h, bw, bh);
    return 1;
}

// The game's GL runs on the driver's own OpenGL: on Android the host already has an OpenGL ES implementation, so
// this front does not implement GL the way the Mac and iOS host does (no GL there at all, hence the 2,069 lines of
// Metal in dm/gfx/gl/hle_gl.m). Everything the game asks for goes to the driver unless this file keeps the name,
// and it keeps only two kinds of name: the game's EGL, which the driver cannot answer for a window the host owns,
// and a look at what the driver really answers for the names and shaders this engine uses.
static void *drv(const char *name) { return xr_eglGetProcAddress ? (void *)xr_eglGetProcAddress(name) : NULL; }
int hle_implements(const char *name);   // a name in the swhle table (dm/host/hle.c)

// What the driver answers for the names this game asks for, counted: the draw path exists if these are non-zero.
// A miss is what leaves the host's named stub in place, and the stub's own UNIMPL line names it once.
static int drv_have, drv_missing;
static void *drv_seen(const char *name) {
    void *p = drv(name);
    if (p) drv_have++;
    else if (!drv_missing++) LOG("[gl] [RED] this driver's eglGetProcAddress answers none of the game's GL names");
    return p;
}

// A name the game imports directly and this file does not keep goes to the driver's own function. The game's code
// runs natively (dm/host/entry.S, no interpreter), so the driver's entry point is callable by it as it stands:
// only the parameter types have to be spelled out for the wrapper, and the types below are the ones the ES 2.0 /
// ES 3.x prototypes use (GLenum/GLuint as unsigned, GLboolean in the low byte of a register, GLsizei/GLint as int).
#define FWD(name, ret, proto, call)                                                   \
    static ret h_##name proto {                                                       \
        static void (*f) proto;                                                       \
        if (!f) { f = (void (*) proto) drv_seen(#name); if (!f) return; }             \
        f call;                                                                       \
    }                                                                                 \
    PLAT_TABLE(swhle) static const hle_ent e_##name = {#name, (void *)h_##name};
#define FWD1(n, a1) FWD(n, void, (a1 v0), (v0))
#define FWD2(n, a1, a2) FWD(n, void, (a1 v0, a2 v1), (v0, v1))
#define FWD3(n, a1, a2, a3) FWD(n, void, (a1 v0, a2 v1, a3 v2), (v0, v1, v2))
#define FWD4(n, a1, a2, a3, a4) FWD(n, void, (a1 v0, a2 v1, a3 v2, a4 v3), (v0, v1, v2, v3))
#define FWD7(n, a1, a2, a3, a4, a5, a6, a7) \
    FWD(n, void, (a1 v0, a2 v1, a3 v2, a4 v3, a5 v4, a6 v5, a7 v6), (v0, v1, v2, v3, v4, v5, v6))
#define FWD9(n, a1, a2, a3, a4, a5, a6, a7, a8, a9) \
    FWD(n, void, (a1 v0, a2 v1, a3 v2, a4 v3, a5 v4, a6 v5, a7 v6, a8 v7, a9 v8), (v0, v1, v2, v3, v4, v5, v6, v7, v8))

// The names this title's imports reach as stubs, measured on the device (2026-11-28): the state, texture and
// draw calls of GameMaker's runner. Anything else it imports shows up as UNIMPL in the same run and joins this
// list; eglGetProcAddress answers everything else from the driver without a wrapper at all.
FWD1(glDepthRangef, float)
FWD1(glEnable, unsigned)
FWD1(glDisable, unsigned)
FWD1(glDepthFunc, unsigned)
FWD1(glDepthMask, unsigned)
FWD4(glBlendFuncSeparate, unsigned, unsigned, unsigned, unsigned)
FWD4(glColorMask, unsigned, unsigned, unsigned, unsigned)
FWD3(glStencilOp, unsigned, unsigned, unsigned)
FWD3(glStencilFunc, unsigned, int, unsigned)
FWD1(glStencilMask, unsigned)
FWD1(glActiveTexture, unsigned)
FWD2(glGenTextures, int, unsigned *)
FWD2(glBindTexture, unsigned, unsigned)
FWD9(glTexImage2D, unsigned, int, int, int, int, int, unsigned, unsigned, const void *)
FWD3(glTexParameterf, unsigned, unsigned, float)
FWD4(glClearColor, float, float, float, float)
FWD1(glClearDepthf, float)
FWD1(glClearStencil, int)
static int n_clear, n_draw, draw_verts, draw_mode_logged;
GL(glClear, void, unsigned mask) {
    void (*f)(unsigned) = (void (*)(unsigned))drv_seen("glClear");
    if (f) f(mask);
    n_clear++;
}
GL(glDrawArrays, void, unsigned mode, int first, int count) {
    void (*f)(unsigned, int, int) = (void (*)(unsigned, int, int))drv_seen("glDrawArrays");
    if (f) f(mode, first, count);
    n_draw++;
    if (count > draw_verts) draw_verts = count;
    if (!draw_mode_logged++) LOG("[gl] the game's first draw: glDrawArrays mode %#x first %d count %d", mode, first, count);
}
FWD4(glScissor, int, int, int, int)
FWD2(glDeleteTextures, int, const unsigned *)
FWD7(glReadPixels, int, int, int, int, unsigned, unsigned, void *)

// What kind each shader the game made is, so the driver's compile results can be named for what they are.
static struct { unsigned id, type; } sh_kind[1024];
static int sh_kind_n;
static const char *sh_kind_of(unsigned id) {
    for (int i = 0; i < sh_kind_n; i++) if (sh_kind[i].id == id)
        return sh_kind[i].type == GL_FRAGMENT_SHADER ? "fragment" : "vertex";
    return "shader";
}

// The strings: the game decides what it can do from these, so they are logged once, from the driver.
static void log_gl_strings(void) {
    static int done;
    if (done || !g_host_gl) return;
    done = 1;
    LOG("[gl] the GL the game is told it has: %s / %s", host_str(GL_VENDOR), host_str(GL_RENDERER));
    LOG("[gl]   version %s, GLSL %s", host_str(GL_VERSION), host_str(GL_SHADING_LANGUAGE_VERSION));
}

GL(glGetString, const unsigned char *, unsigned name) { log_gl_strings(); return (const unsigned char *)host_str(name); }
GL(glGetStringi, const unsigned char *, unsigned name, unsigned i) { (void)i; return (const unsigned char *)host_str(name); }

GL(glCreateShader, unsigned, unsigned type) {
    unsigned (*f)(unsigned) = (unsigned (*)(unsigned))drv("glCreateShader");
    if (!f) return 0;
    unsigned id = (unsigned)(uintptr_t)f(type);
    if (sh_kind_n < (int)(sizeof sh_kind / sizeof sh_kind[0])) {
        sh_kind[sh_kind_n].id = id; sh_kind[sh_kind_n].type = type; sh_kind_n++;
    }
    return id;
}
GL(glDeleteShader, void, unsigned sh) {
    void (*f)(unsigned) = (void (*)(unsigned))drv("glDeleteShader");
    if (f) f(sh);
}

// What the engine writes as GLSL text, and what the driver's compiler makes of it: the question a GL port's size
// depends on. Upstream has no runtime GLSL compiler at all (dm/gfx/tools/gl_shaders.py translates captured GLSL to
// a Metal library offline), so a port that needs no translation is a different piece of work from one that does.
static int src_logged, src_seen;
static int comp_count[2][2];   // [fragment][the driver said ok]
GL(glShaderSource, void, unsigned sh, int count, const char *const *string, const int *length) {
    void (*f)(unsigned, int, const char *const *, const int *) =
        (void (*)(unsigned, int, const char *const *, const int *))drv("glShaderSource");
    if (f) f(sh, count, string, length);
    if (!string || count <= 0) return;
    int total = 0;
    for (int i = 0; i < count; i++) total += length && length[i] >= 0 ? length[i] : (int)strlen(string[i]);
    src_seen++;
    if (src_logged >= 2) return;
    src_logged++;
    char line[200] = "";
    const char *src = string[0];
    for (int i = 0; i < (int)sizeof line - 1 && src[i] && src[i] != '\n'; i++) line[i] = (unsigned char)src[i] > 0x1f ? src[i] : ' ';
    LOG("[gl] GLSL: a %s shader of %d bytes through glShaderSource — first line: %s", sh_kind_of(sh), total, line);
}

// The driver's own answer to the game's shaders and programs, counted: this is the measurement, not a stand-in for
// one, because it is the game's own source compiled by the driver in the game's own calls.
GL(glGetShaderiv, void, unsigned sh, unsigned pname, int *params) {
    void (*f)(unsigned, unsigned, int *) = (void (*)(unsigned, unsigned, int *))drv("glGetShaderiv");
    if (f) f(sh, pname, params);
    else if (params) *params = 0;
    if (pname != GL_COMPILE_STATUS || !params) return;
    int kind = strcmp(sh_kind_of(sh), "fragment") == 0;
    int ok = !!*params;
    if (comp_count[kind][ok]++ < 3) {
        char info[400] = "";
        if (!ok) {
            void (*il)(unsigned, int, int *, char *) = (void (*)(unsigned, int, int *, char *))drv("glGetShaderInfoLog");
            if (il) {
                il(sh, (int)sizeof info, NULL, info);
                for (int i = 0; info[i]; i++) if ((unsigned char)info[i] <= 0x20) info[i] = ' ';
            }
        }
        LOG("[gl] GLSL: the driver's own GLSL ES compiler %s this game's %s shader%s%s",
            ok ? "accepts" : "rejects", sh_kind_of(sh), ok ? "" : ", the driver says:", info);
    }
}
static int link_count[2];
GL(glGetProgramiv, void, unsigned prog, unsigned pname, int *params) {
    void (*f)(unsigned, unsigned, int *) = (void (*)(unsigned, unsigned, int *))drv("glGetProgramiv");
    if (f) f(prog, pname, params);
    else if (params) *params = 0;
    if (pname != GL_LINK_STATUS || !params) return;
    if (link_count[!!*params]++ < 3)
        LOG("[gl] the driver %s this game's program", *params ? "links" : "would not link");
}

// Where the game draws, and at what size: what a present would have to copy. The game's own framebuffer is the
// driver's, so this only watches which one is bound for drawing.
static unsigned g_draw_fb;
static int g_vp[4];
GL(glBindFramebuffer, void, unsigned target, unsigned fb) {
    void (*f)(unsigned, unsigned) = (void (*)(unsigned, unsigned))drv("glBindFramebuffer");
    if (f) f(target, fb);
    if (fb && (target == 0x8D40 /*FRAMEBUFFER*/ || target == 0x8CA0 /*DRAW_FRAMEBUFFER*/)) g_draw_fb = fb;
}
static int vp_logged;
GL(glViewport, void, int x, int y, int w, int h) {
    void (*f)(int, int, int, int) = (void (*)(int, int, int, int))drv("glViewport");
    if (!vp_logged++) LOG("[gl] the game's viewport: %dx%d at %d,%d", w, h, x, y);
    if (w > g_vp[2]) g_vp[2] = w;   // the largest viewport is the picture's size: a game narrows it for its passes
    if (h > g_vp[3]) g_vp[3] = h;
    if (f) f(x, y, w, h);
}

// The EGL front: the display, config, context and surface the game asks for, answered here rather than by a stub
// returning 0. No swapchain: eglSwapBuffers says so, which is where this front stops and the draw path begins.
typedef struct egl_dpy { int initialized; int maj, min; } egl_dpy;
typedef struct egl_ctx { egl_dpy *dpy; } egl_ctx;
typedef struct egl_surf { egl_dpy *dpy; } egl_surf;
typedef struct egl_cfg { egl_dpy *dpy; } egl_cfg;
static egl_dpy g_dpy;
static egl_ctx g_ctx;
static egl_surf g_surf;
static egl_cfg g_cfg;

GL(eglGetDisplay, void *, void *native) { (void)native; host_gl_init(); g_dpy.maj = 1; g_dpy.min = 4; return &g_dpy; }
GL(eglInitialize, int, void *dpy, int *maj, int *min) {
    if (dpy != &g_dpy) return EGL_FALSE;
    g_dpy.initialized = 1;
    if (maj) *maj = g_dpy.maj;
    if (min) *min = g_dpy.min;
    return EGL_TRUE;
}
GL(eglBindAPI, int, unsigned api) { (void)api; return EGL_TRUE; }
GL(eglQueryAPI, unsigned, void) { return EGL_OPENGL_ES_API; }
GL(eglChooseConfig, int, void *dpy, const int *attrib, void **configs, int n, int *num) {
    (void)attrib;
    if (dpy != &g_dpy || n <= 0 || !configs) return EGL_FALSE;
    configs[0] = &g_cfg;
    if (num) *num = 1;
    return EGL_TRUE;
}
GL(eglGetConfigAttrib, int, void *dpy, void *cfg, unsigned attr, int *value) {
    (void)dpy;
    if (cfg != &g_cfg) return EGL_FALSE;
    switch (attr) {
        case EGL_BUFFER_SIZE: *value = 32; break;
        case EGL_CONFIG_ID: *value = 1; break;
        case EGL_RED_SIZE: case EGL_GREEN_SIZE: case EGL_BLUE_SIZE: case EGL_ALPHA_SIZE: *value = 8; break;
        case EGL_DEPTH_SIZE: *value = 24; break;
        case EGL_STENCIL_SIZE: *value = 8; break;
        case EGL_NATIVE_VISUAL_ID: *value = 1; break;
        case EGL_SURFACE_TYPE: *value = EGL_WINDOW_BIT | EGL_PBUFFER_BIT; break;
        case EGL_RENDERABLE_TYPE: *value = EGL_OPENGL_ES3_BIT; break;
        case EGL_SAMPLES: *value = 0; break;
        case EGL_LEVEL: *value = 0; break;
        case EGL_CONFIG_CAVEAT: *value = EGL_NONE; break;
        case EGL_COLOR_BUFFER_TYPE: *value = 0x308B /*RGB_BUFFER*/; break;
        case EGL_RENDER_BUFFER: *value = 0x3086 /*BACK_BUFFER*/; break;
        case EGL_LUMINANCE_SIZE: case EGL_ALPHA_MASK_SIZE: *value = 0; break;
        default: *value = 0; break;
    }
    return EGL_TRUE;
}
GL(eglGetConfigs, int, void *dpy, void **configs, int n, int *num) {
    if (dpy != &g_dpy) return EGL_FALSE;
    if (configs && n > 0) configs[0] = &g_cfg;
    if (num) *num = 1;
    return EGL_TRUE;
}
GL(eglCreateContext, void *, void *dpy, void *cfg, void *other, const int *attrib) {
    (void)cfg; (void)other; (void)attrib;
    if (dpy != &g_dpy) return NULL;
    g_ctx.dpy = &g_dpy;
    LOG("[gl] the game's EGL context: its GL calls run on the host's driver context, current on this thread");
    return &g_ctx;
}
GL(eglCreateWindowSurface, void *, void *dpy, void *cfg, void *native, const int *attrib) {
    (void)cfg; (void)native; (void)attrib;
    if (dpy != &g_dpy) return NULL;
    g_surf.dpy = &g_dpy;
    return &g_surf;
}
GL(eglMakeCurrent, int, void *dpy, void *surf, void *tsurf, void *ctx) {
    (void)tsurf;
    if (dpy != &g_dpy || ctx != &g_ctx || (surf != &g_surf && surf != NULL)) return EGL_FALSE;
    // The game's calls run on the thread it makes its context current on, so that is where the driver's context
    // has to be: without this the game's glClear lands on a thread with no context and nothing is drawn. What
    // becomes current is the window surface once there is one — the surface the game means by its own.
    void *s = xr_win_surf ? xr_win_surf : xr_surf;
    if (g_host_gl && !xr_eglMakeCurrent(xr_dpy, s, s, xr_ctx))
        LOG("[gl] [RED] the driver's context would not become current on the game's thread (eglGetError %s)", egl_err());
    return EGL_TRUE;
}
GL(eglGetCurrentDisplay, void *, void) { return g_dpy.initialized ? &g_dpy : NULL; }
GL(eglGetCurrentContext, void *, void) { return g_dpy.initialized ? &g_ctx : NULL; }
GL(eglGetCurrentSurface, void *, unsigned which) { (void)which; return g_dpy.initialized ? &g_surf : NULL; }
GL(eglQuerySurface, int, void *dpy, void *surf, unsigned attr, int *value) {
    if (dpy != &g_dpy || surf != &g_surf) return EGL_FALSE;
    // What the game reads back is the surface it really draws into, at its real size: the game's own surface is
    // the window surface from the moment the launcher's window is taken (h_eglMakeCurrent), and a game sizes its
    // views by this answer. Before that it is the pbuffer, at the size the game asked for.
    *value = attr == 0x3057 /*WIDTH*/ ? (xr_win_surf ? win_w : xr_pb_w)
           : attr == 0x3056 /*HEIGHT*/ ? (xr_win_surf ? win_h : xr_pb_h) : 0;
    return EGL_TRUE;
}

// The frame, read back: what the game's own GL commands really put on the GPU. A present would copy this same
// image into the launcher's window; here it goes to a file, because the window belongs to the Vulkan path.
static void read_back_frame(int n) {
    void (*gl_bind)(unsigned, unsigned) = (void (*)(unsigned, unsigned))drv("glBindFramebuffer");
    void (*gl_read)(int, int, int, int, unsigned, unsigned, void *) =
        (void (*)(int, int, int, int, unsigned, unsigned, void *))drv("glReadPixels");
    unsigned (*gl_check)(unsigned) = (unsigned (*)(unsigned))drv("glCheckFramebufferStatus");
    unsigned (*gl_error)(void) = (unsigned (*)(void))drv("glGetError");
    if (!gl_read) { LOG("[gl] [RED] this driver has no glReadPixels: nothing can be read back"); return; }
    // The game's own target if it has one — that is where it drew — and its viewport size, capped to the surface
    // it draws into: the window's once the launcher's window is taken, the pbuffer's until then.
    int w = xr_win_surf ? win_w : xr_pb_w, h = xr_win_surf ? win_h : xr_pb_h;
    if (g_vp[2] > 0 && g_vp[2] < w) w = g_vp[2];
    if (g_vp[3] > 0 && g_vp[3] < h) h = g_vp[3];
    if (g_draw_fb && gl_bind) gl_bind(GL_FRAMEBUFFER, g_draw_fb);
    int complete = gl_check ? (int)gl_check(GL_FRAMEBUFFER) : 0;
    unsigned char *px = malloc((size_t)w * (size_t)h * 4);
    if (!px) return;
    gl_read(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
    unsigned err = gl_error ? gl_error() : 0;
    int lit = 0, x0 = w, x1 = -1, y0 = h, y1 = -1;
    unsigned col[4] = {0, 0, 0, 0};
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            const unsigned char *p = px + ((size_t)y * w + x) * 4;
            if (!p[0] && !p[1] && !p[2] && !p[3]) continue;
            lit++;
            if (x < x0) x0 = x; if (x > x1) x1 = x;
            if (y < y0) y0 = y; if (y > y1) y1 = y;
            if (lit == 1 || lit == 1000) { col[0] = p[0]; col[1] = p[1]; col[2] = p[2]; col[3] = p[3]; }
        }
    LOG("[gl] frame %d read back from %s at %dx%d: %s, %d of %d pixels are not black%s", n,
        g_draw_fb ? "the game's own framebuffer" : "the default framebuffer", w, h,
        complete == GL_FRAMEBUFFER_COMPLETE ? "complete" : "not framebuffer-complete",
        lit, w * h, err ? "" : ", the driver reports no GL error");
    LOG("[gl]   the game has called glClear %d times and glDrawArrays %d times (%d vertices at most), through %d of "
        "the driver's own GL functions", n_clear, n_draw, draw_verts, drv_have);
    if (lit) LOG("[gl]   its bounds %d,%d..%d,%d, the first lit pixel rgba %u %u %u %u%s", x0, y0, x1, y1,
        col[0], col[1], col[2], col[3], err ? "" : "");
    if (err) LOG("[gl] [RED] the driver reported GL error %#x while reading the frame back", err);
    const char *dir = getenv("TMPDIR");
    if (lit && dir && *dir) {   // the app's cache folder: a picture of what the game drew, to look at on the PC
        char path[512];
        snprintf(path, sizeof path, "%s/gl-frame-%d.ppm", dir, n);
        FILE *f = fopen(path, "wb");
        if (f) {
            fprintf(f, "P6\n%d %d\n255\n", w, h);
            for (int y = h - 1; y >= 0; y--)   // GL's origin is lower left, an image's is upper left
                for (int x = 0; x < w; x++) {
                    const unsigned char *p = px + ((size_t)y * w + x) * 4;
                    fputc(p[0], f); fputc(p[1], f); fputc(p[2], f);
                }
            fclose(f);
            LOG("[gl]   written to %s", path);
        } else
            LOG("[gl] [RED] %s: %s", path, strerror(errno));
    }
    free(px);
    if (g_draw_fb && gl_bind) gl_bind(GL_FRAMEBUFFER, 0);
}


// ---------- is the picture new on every swap? (DM_GL_MOTION) ----------
// Flat frame times only say a buffer was handed over on time; they say nothing about whether anything new was in
// it. A title that animates 30 times a second while swapping 60 (a room at speed 30, or an animation stepped on a
// timer) shows every picture twice: the graph reads a clean 16.6 ms and the eye sees 30. That cannot be told apart
// from a pacing fault without looking at the pixels, so this does: read thin bands of what is about to be shown and
// count the swaps whose picture is byte-identical to the one before. A diagnostic only — reading a window's back
// buffer costs milliseconds on this driver, so it is not run together with the frame-time window above.
#define MOTION_BANDS 3        // bands across the picture's height: one may sit on a still part of the screen
#define MOTION_BAND_H 16      // rows per band
#define MOTION_WINDOW 120     // swaps per report
static int g_motion;          // PEANUTS: DM_GL_MOTION = watch the picture, not only the clock
static unsigned char *motion_px;                              // one band, read again for the next
static unsigned long long motion_now[MOTION_BANDS], motion_last[MOTION_BANDS];
static int motion_n, motion_same, motion_run, motion_worst, motion_new[MOTION_BANDS];

static void motion_probe(void) {
    void (*gl_read)(int, int, int, int, unsigned, unsigned, void *) =
        (void (*)(int, int, int, int, unsigned, unsigned, void *))drv("glReadPixels");
    if (!gl_read || !xr_win_surf || win_w <= 0 || win_h < MOTION_BAND_H) return;
    if (!motion_px) motion_px = malloc((size_t)win_w * MOTION_BAND_H * 4);
    if (!motion_px) return;
    for (int b = 0; b < MOTION_BANDS; b++) {
        int y = (win_h * (2 * b + 1)) / (2 * MOTION_BANDS) - MOTION_BAND_H / 2;
        if (y < 0) y = 0;
        if (y + MOTION_BAND_H > win_h) y = win_h - MOTION_BAND_H;
        gl_read(0, y, win_w, MOTION_BAND_H, GL_RGBA, GL_UNSIGNED_BYTE, motion_px);
        unsigned long long h = 1469598103934665603ULL;   // FNV-1a: "the same bytes" against "different bytes" is all this needs
        size_t n = (size_t)win_w * MOTION_BAND_H * 4;
        for (size_t i = 0; i < n; i += 2) h = (h ^ motion_px[i]) * 1099511628211ULL;   // every other byte: RGB is 3 bytes, and this halves the work
        motion_now[b] = h;
    }
    if (motion_n) {
        int changed = 0;
        for (int b = 0; b < MOTION_BANDS; b++)
            if (motion_now[b] != motion_last[b]) { changed = 1; motion_new[b]++; }
        if (!changed) { motion_same++; if (++motion_run > motion_worst) motion_worst = motion_run; }
        else motion_run = 1;
    }
    for (int b = 0; b < MOTION_BANDS; b++) motion_last[b] = motion_now[b];
    if (++motion_n >= MOTION_WINDOW) {
        LOG("[gl] the picture across %d swaps: it was byte-identical to the one before on %d of them, the longest run of one picture %d frames, the bands changing on %d, %d and %d of them "
            "(a game that animates on every swap shows none of this; one that animates 30 times a second while swapping 60 shows runs of 2)",
            motion_n, motion_same, motion_worst, motion_new[0], motion_new[1], motion_new[2]);
        motion_n = motion_same = motion_worst = motion_run = 0;
        memset(motion_new, 0, sizeof motion_new);
    }
}


// A GL title whose engine advances the game one step per swap runs at whatever rate its swaps happen at. The panel's
// vsync is no answer: it is a grid of the panel's own rate — 60, 90, 120, 144, 165 Hz — and a Switch title's clock
// is the 60 Hz display it was made for; on a 120 Hz panel it runs at twice the speed (measured on the AYN Thor,
// 2026-11-28: "the game presents at 120.2 per second" with no swap-interval ask in the run, and the window's own
// ask of the system for 60 did not move this panel). So the front paces the swaps itself, and aims each frame at
// one of the panel's composition slots where the driver will name them (the panel's grid below) or at a grid of its
// own on CLOCK_MONOTONIC where it will not: the game's rate then comes from the number of slots a frame steps over
// or from that grid's period, so a target rate is held whatever the panel's rate is, and the frames land on the
// panel's own boundaries rather than drifting across them. Either way the driver's vsync wait is turned off first
// by asking its swap interval for 0 (through libEGL itself: eglGetProcAddress answers no core EGL name, which is
// why the first attempt at this did nothing — it resolved to NULL and stayed quiet). A game that draws slower than
// the target reaches slots that have already passed and waits for nothing: the pacer only ever removes time, never
// adds a stall. A long gap (a load, a pause, the launcher resumed) takes the next slot instead of firing the
// catch-up all at once.
// What a frame's timing is made of, kept apart: this file's sleep to the deadline (which can wake late), the
// driver's swap (which can still wait for the panel), and the game's own work (a frame that reaches the deadline
// already late waits for nothing). Only the first two are ours to fix, so pace_swap leaves word about them here
// and the present measures the rest (pace_measure).
static long long g_pace_late;    // ns the last deadline sleep woke after its deadline; 0 = it did not sleep
static int g_pace_late_frames;   // frames in this window that woke late at all
static int g_pace_missed;        // frames that were already past their deadline: the game's work, not the pacer's

static long long mono_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

// PEANUTS: DM_GL_FPS names the target rate (0 = pace nothing, leave the game and the panel to each other).
static int fps_target(void) {
    static int target = -1;
    if (target < 0) target = getenv("DM_GL_FPS") ? atoi(getenv("DM_GL_FPS")) : 60;
    return target;
}

// ---------- the panel's composition grid ----------
// A frame only looks smooth when the panel shows it at one of its own boundaries. Pacing the swaps by wall clock
// cannot do that, however exactly it holds the average: the two grids drift against each other and a frame that
// arrives a fraction of a millisecond after the compositor latched misses its slot and waits for the next one.
// Measured on the AYN Thor (2026-11-28, Demon Throttle, title screen): the front's own frame times were flat —
// "p50 16.6 ms, p95 16.6 ms ... the deadline sleep woke late 0 times ... the game was past its deadline on 1 of
// 120 frames" — and the picture still did not look smooth, with the swap itself sometimes waiting 7-10 ms for a
// free buffer. A 16.667 ms grid against a 120 Hz panel (8.33 ms, a slot every 16.67) slips by 0.07 ms a frame,
// so about every 120 frames one misses its slot: a hitch twice a second, which is what was felt.
// The driver can name the grid: EGL_ANDROID_get_frame_timestamps (advertised by this driver) answers, for a
// window surface that asks for its timings, EGL_COMPOSITE_INTERVAL_ANDROID (the time between the compositor's
// composition events, the panel's period) and EGL_COMPOSITE_DEADLINE_ANDROID (the next such event). Both are on
// CLOCK_MONOTONIC, the same clock the pacer uses, so the frames can be aimed at the slots instead of at a grid of
// our own: nothing drifts, and the game's rate comes from how many slots a frame steps over. Where the target is
// a whole share of the panel's rate (60 on a 120 or 180 Hz panel) every frame steps the same number and the
// intervals are exact; where it is not (60 on 144 Hz) the walk steps 2, 2, 3 slots and averages the target — the
// intervals then alternate, and no pacer can do better without drawing frames the game never drew.
#define TS_TIMESTAMPS 0x3430              // EGL_TIMESTAMPS_ANDROID, for eglSurfaceAttrib: collect this surface's timings
#define TS_COMPOSITE_DEADLINE 0x3431      // EGL_COMPOSITE_DEADLINE_ANDROID: the next composition event, absolute
#define TS_COMPOSITE_INTERVAL 0x3432      // EGL_COMPOSITE_INTERVAL_ANDROID: the panel's period
static int grid_said;                       // a refusal is said once, not per frame

// This surface's timings are only collected when it asks for them (the extension's default is off), and only for a
// window surface. Without this the panel's timing calls answer EGL_BAD_SURFACE.
static void ask_panel_timings(void) {
    static int (*attrib)(void *, void *, int, int);
    if (!attrib) attrib = xr_libEGL ? (int (*)(void *, void *, int, int))dlsym(xr_libEGL, "eglSurfaceAttrib") : NULL;   // core EGL: eglGetProcAddress answers no core name
    if (!attrib || !xr_dpy || !xr_win_surf) return;
    if (attrib(xr_dpy, xr_win_surf, TS_TIMESTAMPS, EGL_TRUE)) g_ts_on = 1;
    else LOG("[gl] [RED] this driver would not turn on the window surface's frame timings (eglGetError %s): the game's frames cannot be aimed at the panel's composition slots", egl_err());
}

// The panel's grid: 1 when grid_int and grid_slot hold it. Asked twice a second — enough to follow a panel that
// changes its rate, cheap enough not to be a call per frame; between the answers the grid is walked forward.
static int panel_grid(void) {
    static int (*get_timing)(void *, void *, int, const int *, long long *);
    static int looked;
    long long now = mono_ns();
    if (!g_ts_on || !xr_win_surf || !xr_dpy) return 0;
    if (grid_int > 0 && now - grid_read < 500000000LL) return 1;
    if (!get_timing) {
        if (looked++) return 0;
        get_timing = (int (*)(void *, void *, int, const int *, long long *))drv("eglGetCompositorTimingANDROID");
        if (!get_timing) {
            if (!grid_said++) LOG("[gl] [RED] this driver advertises EGL_ANDROID_get_frame_timestamps but does not answer eglGetCompositorTimingANDROID: the game's frames stay on the host clock's grid");
            return 0;
        }
    }
    int names[2] = { TS_COMPOSITE_INTERVAL, TS_COMPOSITE_DEADLINE };
    long long v[2] = { 0, 0 };
    if (!get_timing(xr_dpy, xr_win_surf, 2, names, v) || v[0] <= 0 || v[1] <= 0) {   // 0, EGL_TIMESTAMP_PENDING (-2) or EGL_TIMESTAMP_INVALID (-1): no answer
        if (grid_int > 0 && !grid_said++) LOG("[gl] [RED] the panel's composition timing stopped: the game's frames go back to the host clock's grid");
        grid_int = aimed_slot = grid_acc = 0;
        return 0;
    }
    long long was = grid_int;
    int changed = !was || v[0] > was * 5 / 4 || v[0] < was * 3 / 4;   // more than a quarter of a frame's difference: worth saying
    grid_int = v[0]; grid_slot = v[1]; grid_read = now;
    if (changed) {
        int target = fps_target(), period = target > 0 ? 1000000000 / target : 0;
        int slots = period > 0 ? (int)((double)period / (double)grid_int + 0.5) : 0;
        if (slots < 1) slots = 1;
        long long held = (long long)slots * grid_int;   // what a frame that steps that many slots takes
        LOG("[gl] the panel composes a frame every %d.%d ms (%d.%d Hz) and the next one begins in %d ms: a game frame is aimed every %d of its slots, which holds %d.%d frames a second",
            (int)(grid_int / 1000000), (int)(grid_int / 100000) % 10, (int)(1000000000LL / grid_int), (int)(10000000000LL / grid_int) % 10,
            (int)((v[1] - now) / 1000000), slots, (int)(1000000000LL / held), (int)(10000000000LL / held) % 10);
    }
    return 1;
}

// The first panel slot at or after `when`, walking the grid forward from the composition event the panel just named.
static long long slot_at_or_after(long long when) {
    long long n = (when - grid_slot + grid_int - 1) / grid_int;
    if (n < 0) n = 0;   // the event the panel named is already the next one in the future
    return grid_slot + n * grid_int;
}

// When to hand this frame over: the panel slot the frame is aimed at, minus the time it takes to get the buffer
// into the queue before the composition of that slot begins. The slot is found by walking whole panel periods from
// the slot of the last frame, so the frames stay on the panel's boundaries and cannot drift against it; the
// fractional part of the walk is kept in integers (grid_acc), so no rounding error accumulates either.
static long long panel_aim(long long now, long long period) {
    long long margin = grid_int / 4;   // queue it a quarter of a panel period early: enough for the swap call itself, not so early that a slower frame wastes a slot
    if (margin > 4000000) margin = 4000000;
    if (margin < 500000) margin = 500000;
    if (aimed_slot && grid_slot > aimed_slot) {   // the panel's phase moved out from under the grid we are walking (its rate or mode changed): start again from its answer
        long long drift = (grid_slot - aimed_slot) % grid_int;
        if (drift > grid_int / 2 || drift < -grid_int / 2) aimed_slot = 0;
    }
    if (!aimed_slot) {
        aimed_slot = slot_at_or_after(now + margin);
        grid_acc = 0;
    } else {
        grid_acc += period;
        long long step = grid_acc / grid_int;
        grid_acc -= step * grid_int;
        if (step < 1) step = 1;   // never two frames aimed at one composition: a panel slower than the target shows what it can
        aimed_slot += step * grid_int;
        if (aimed_slot - margin < now) aimed_slot = slot_at_or_after(now + margin);   // the game is past its slot: take the next one, do not fire the missed frames at once
    }
    return aimed_slot - margin;
}

// Whole refreshes of the panel per game frame, 0 when the target is not a whole number of them: the same fit
// vka_panel.h uses (vkpanel_fit) — the nearest whole number of refreshes when that is within 2 % of the period
// asked for, nothing otherwise (60 on a 120 or 180 Hz panel: 2 and 3 refreshes; 60 on 90 or 144: none).
static int panel_hold(void) {
    int target = fps_target();
    if (!target || grid_int <= 0) return 0;
    long long period = 1000000000LL / target, n = (period + grid_int / 2) / grid_int;
    if (n < 1) return 0;
    long long d = n * grid_int - period;
    if (d < 0) d = -d;
    return d * 100 <= period ? (int)n : 0;
}

// The driver's own vsync wait, asked in whole refreshes, is what paces the frames where that is possible. This
// matters more than it looks: EGL's default swap interval is 1, so before this file asked for anything the game's
// swaps *were* locked to the panel — which is why it ran at 120.2 frames a second on a 120 Hz panel. Freeing that
// lock (interval 0) and holding the rate by host clock fixed the speed but threw the phase away: the compositor
// then picks our buffers up at whatever point of its own grid it is at, and one frame in a hundred misses its
// composition. Aiming the host grid at the panel's named slots cannot repair that either, because the period the
// compositor names is not the period the panel runs at: it answers 8333333 ns (120.000 Hz) here while vka_panel.h
// measured this same AYN Thor's 120 Hz mode at 118.52 Hz and its 60 Hz mode at 59.83 Hz from real present times —
// against the real grid a "120.000 Hz" aim slips about 6 ms a second, a missed composition once or twice a second,
// which is exactly the judder that stayed. A wait the driver takes itself cannot land between two boundaries and
// its period is whatever the panel really runs at, so where the target is a whole share of the panel the host adds
// no clock at all. Where it is not (60 on 90 or 144 Hz) no whole number of refreshes gives the target, and the host
// clock paces those frames with the aim above as the best that remains; which of the two is in use is said once.
static void set_swap_interval(int n, int target) {
    static int (*set_interval)(void *, int);
    static int looked;
    if (!set_interval) {
        if (looked++) return;
        set_interval = xr_libEGL ? (int (*)(void *, int))dlsym(xr_libEGL, "eglSwapInterval") : NULL;   // core EGL: eglGetProcAddress answers no core name
        if (!set_interval) LOG("[gl] [RED] this host cannot ask the driver for a swap interval: the game's frames follow whatever the panel does");
    }
    if (!set_interval || !xr_dpy || g_swap_interval == n || n == g_interval_refused) return;
    if (!set_interval(xr_dpy, n)) {
        g_interval_refused = n;   // remembered: this driver is not asked again for that number while the pacer wants it (an Android EGL clamps the interval to 1: Adreno refuses 2 with EGL_BAD_PARAMETER, measured 2026-11-28)
        if (!interval_said++) LOG("[gl] [RED] the driver would not take a swap interval of %d (eglGetError %s): the frames fall back to the host's clock", n, egl_err());
        return;
    }
    g_swap_interval = n;
    if (n >= 1) LOG("[gl] the game's frames are paced by the panel: the driver waits %d of its refreshes per frame, which holds the game near %d frames a second; the host adds no clock", n, target);
    else LOG("[gl] the game's frames are freed from the panel's vsync and paced by the host at %d a second: the panel's named slots where it names them, its own clock otherwise", target);
}

static void pace_swap(void) {
    static long long next_due;   // CLOCK_MONOTONIC ns of the next swap, an absolute grid of our own: the fallback when the panel's is not known
    int target = fps_target();
    if (!target) return;
    long long period = 1000000000LL / target;
    long long now = mono_ns();
    int have_grid = panel_grid();
    int n = have_grid ? panel_hold() : 0;
    if (n >= 1 && g_interval_refused != n) {
        set_swap_interval(n, target);   // the panel's wait is the pacer where the driver takes it: a wait the driver takes cannot land off its own grid
        if (g_swap_interval == n) {
            next_due = 0;             // the host grid starts afresh if it is needed again
            return;
        }
        // not taken (an Android EGL clamps the interval to 1): fall through to the host's clock, which is what the rest of this fn does
    }
    set_swap_interval(0, target);
    long long due;
    if (have_grid) {
        due = panel_aim(now, period);   // the panel's grid, aimed at: better than a grid of our own even when it is not a whole share of the target
        next_due = 0;                   // the panel is the clock now; the host grid starts afresh if it is needed again
    } else {
        if (!next_due || now - next_due > 4 * period) next_due = now;   // first frame, or a gap longer than it can catch up on
        due = next_due;
        next_due = (next_due < now ? now : next_due) + period;
    }
    if (now < due) {
        struct timespec abs = { (time_t)(due / 1000000000LL), (long)(due % 1000000000LL) };
        clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &abs, NULL);
        long long woke = (g_pace_late = mono_ns() - due);   // the scheduler's share of the frame's lateness
        if (woke > 1000000) g_pace_late_frames++;           // 1 ms: worth counting
    } else {
        g_pace_missed++;
        g_pace_late = 0;
    }
}

// The shape of the frame intervals, not only their average: a game can present at exactly 60.0 a second and still
// look wrong, because half the frames go to the panel 8 ms apart and the other half 25 ms apart. The launcher's
// graph shows that to the eye but says nothing about the cause, so the present keeps, for each window, the
// interval between swaps, what the driver's swap itself cost, and what pace_swap reports for its sleep, and names
// the shape when it is not flat: p50/p95/max and the two counts say whether the panel is re-sampling our
// free-running frames (a steady 2-refresh beat: p50 near the target, p95 near 1.6 times it, the swap's own time
// small), whether the scheduler wakes us late, or whether the game's own work passes the deadline
// (2026-11-28, Demon Throttle: "the frame pacing is not a smooth 60 even at the title screen").
// (PACE_WINDOW, PACE_UNIT: declared with the samples at the top of this file.)
static int pace_iv[PACE_WINDOW], pace_wait[PACE_WINDOW], pace_wake[PACE_WINDOW];
static int pace_n;

static void pace_measure(long long interval_ns, long long swap_ns, long long woke_ns) {
    if (pace_n < PACE_WINDOW) {
        long long v = interval_ns / PACE_UNIT, w = swap_ns / PACE_UNIT, k = woke_ns / PACE_UNIT;
        pace_iv[pace_n] = (int)(v > 65000 ? 65000 : v);       // 6.5 s: a load or a pause, not a frame
        pace_wait[pace_n] = (int)(w > 65000 ? 65000 : w);
        pace_wake[pace_n] = (int)(k > 65000 ? 65000 : k);
        pace_n++;
    }
}

static int cmp_int(const void *a, const void *b) { return *(const int *)a - *(const int *)b; }

// A window's percentiles in one sort: the sorted copy stands until the next window is asked for, and pace_at
// indexes it at p in tenths of a percent (500 = p50, 990 = p99, 995 = p99.5, 1000 or more = the largest).
static int *pace_sorted(int *v, int n) {
    static int tmp[PACE_WINDOW];
    if (n <= 0) return NULL;
    memcpy(tmp, v, (size_t)n * sizeof *tmp);
    qsort(tmp, (size_t)n, sizeof *tmp, cmp_int);
    return tmp;
}
static int pace_at(const int *s, int n, int p) { return !s || n <= 0 ? 0 : s[p >= 1000 ? n - 1 : n * p / 1000]; }

// One line per window when the intervals are not flat: what the frame times look like, and which of the three
// parts of the frame owns the difference. Nothing when they are: the rate line already says the average.
static void pace_report(int target_fps) {
    if (!pace_n || target_fps <= 0) { pace_n = 0; return; }
    int period = (int)(1000000000LL / target_fps / PACE_UNIT);
    const int *iv = pace_sorted(pace_iv, pace_n), *wait = pace_sorted(pace_wait, pace_n), *wake = pace_sorted(pace_wake, pace_n);
    int p50 = pace_at(iv, pace_n, 500), p95 = pace_at(iv, pace_n, 950), p99 = pace_at(iv, pace_n, 990);
    int p995 = pace_at(iv, pace_n, 995), pmax = pace_at(iv, pace_n, 1000);
    int flat = p99 <= period + period / 4;   // nine frames in ten within a quarter period of the target: nothing to say
    if (flat && !g_pace_late_frames && !g_pace_missed) { pace_n = 0; return; }
    char grid[160];
    if (grid_int > 0) {
        int slots = (int)((double)(1000000000 / target_fps) / (double)grid_int + 0.5);
        if (slots < 1) slots = 1;
        snprintf(grid, sizeof grid, "the panel names its interval as %d.%d ms and a game frame every %d of them",
                 (int)(grid_int / 1000000), (int)(grid_int / 100000) % 10, slots);
    } else snprintf(grid, sizeof grid, "the panel named no grid: the host clock paced these frames");
    LOG("[gl] %d frame times: p50 %d.%d ms, p95 %d.%d, p99 %d.%d, p99.5 %d.%d, max %d.%d ms (the target is %d.%d ms)"
        " | the swap itself p50 %d.%d max %d.%d ms | the deadline sleep woke late %d times, worst %d.%d ms"
        " | the game was past its deadline on %d of %d frames | %s",
        pace_n, p50 / 10, p50 % 10, p95 / 10, p95 % 10, p99 / 10, p99 % 10, p995 / 10, p995 % 10,
        pmax / 10, pmax % 10, period / 10, period % 10,
        pace_at(wait, pace_n, 500) / 10, pace_at(wait, pace_n, 500) % 10, pace_at(wait, pace_n, 1000) / 10, pace_at(wait, pace_n, 1000) % 10,
        g_pace_late_frames, pace_at(wake, pace_n, 1000) / 10, pace_at(wake, pace_n, 1000) % 10, g_pace_missed, pace_n, grid);
    pace_n = 0;
    g_pace_late_frames = g_pace_missed = 0;
}

// ---------- what the panel showed, and when ----------
// The numbers above say when this process handed a buffer over. Whether the picture was on the screen evenly is a
// different question, and the only one anyone can see: the compositor latches a frame at one of its own boundaries,
// so a frame that arrives a moment after one boundary is shown at the next, and a grid of ours drifts across the
// panel's real one even while our own intervals stay flat. EGL_ANDROID_get_frame_timestamps answers the moment each
// of our frames started scanning out (EGL_DISPLAY_PRESENT_TIME_ANDROID), for the frame id eglGetNextFrameIdANDROID
// names before the swap. The intervals between those moments are the picture's real cadence.
// PEANUTS: nothing here assumes a refresh period. The figure the compositor names for its interval and the period
// the panel really runs at are not the same on this device: vka_panel.h measured an AYN Thor's 60 Hz mode at 59.83 Hz
// and its 120 Hz mode at 118.52 Hz from real present timestamps, and eglGetCompositorTimingANDROID names this one's
// composition interval as exactly 8333333 ns (120.00 Hz, 2026-11-28). So the intervals are only counted into
// buckets: whatever values they pile onto are the panel's own grid, and the gaps between those values are its period.
#define TS_DISPLAY_PRESENT 0x343A          // EGL_DISPLAY_PRESENT_TIME_ANDROID
// (the ring, the buckets and the sample window are declared with the samples at the top of this file)

static void show_report(void);

static unsigned long long show_id_take(void) {
    static unsigned long long (*next_id)(void *, void *, unsigned long long *);
    if (!next_id) {
        if (show_none) return 0;
        next_id = (unsigned long long (*)(void *, void *, unsigned long long *))drv("eglGetNextFrameIdANDROID");
        if (!next_id) {
            show_none = 1;
            LOG("[gl] [RED] this driver does not answer eglGetNextFrameIdANDROID: what the panel showed, and when, cannot be measured");
            return 0;
        }
    }
    unsigned long long id = 0;
    if (!next_id(xr_dpy, xr_win_surf, &id)) {
        if (!show_pending++) LOG("[gl] [RED] the driver would not name this surface's next frame (eglGetError %s)", egl_err());
        return 0;
    }
    return id;
}

// Called after the swap, with the id that swap was given: keeps the ids and asks about the frame queued SHOW_LAG
// swaps ago, whose display time the panel has had time to reach by then.
static void show_seen(unsigned long long id) {
    static int (*get_ts)(void *, void *, unsigned long long, int, const int *, long long *);
    if (!id || show_none) return;
    if (!get_ts) {
        get_ts = (int (*)(void *, void *, unsigned long long, int, const int *, long long *))drv("eglGetFrameTimestampsANDROID");
        if (!get_ts) {
            show_none = 1;
            LOG("[gl] [RED] this driver does not answer eglGetFrameTimestampsANDROID: what the panel showed, and when, cannot be measured");
            return;
        }
    }
    unsigned long long ask = show_filled > SHOW_LAG ? show_ring[(show_w - SHOW_LAG + SHOW_RING) % SHOW_RING] : 0;
    show_ring[show_w] = id;
    show_w = (show_w + 1) % SHOW_RING;
    if (show_filled < SHOW_RING) show_filled++;
    if (!ask) return;
    int name = TS_DISPLAY_PRESENT;
    long long v = 0;
    if (!get_ts(xr_dpy, xr_win_surf, ask, 1, &name, &v)) return;   // EGL_BAD_ACCESS: that frame's history is gone; asked once per frame, so a driver that never answers simply says nothing
    if (v == -1) return;                                           // EGL_TIMESTAMP_INVALID: the event did not happen
    if (v == -2) {   // the panel has not reached that frame yet. A frame queued 2 ms ago always answers this; one queued 66 ms ago may not. What says the probe is useless is *never* completing one: asked once, and the asking stops.
        if (++show_pending == 120) LOG("[gl] [RED] the panel's display times stay pending: its real cadence cannot be measured");
        if (show_pending > 1200) show_none = 1;
        return;
    }
    show_pending = 0;
    if (show_last && v > show_last) {
        long long d = v - show_last;
        if (d > 1000000 && d < (long long)SHOW_BUCKETS * 500000) {   // under 1 ms is not a frame; past 40 ms is a pause, not a frame
            if (show_n < PACE_WINDOW) show_iv[show_n++] = (int)(d / PACE_UNIT);
            show_hist[(int)(d / 500000)]++;
        }
    }
    show_last = v;
    if (show_n >= PACE_WINDOW) show_report();
}

// The panel's real cadence, said every window whether or not it is clean: this is the cadence the eye sees, and the
// buckets show the grid the frames actually land on. The values that appear are the panel's own; nothing was assumed.
static void show_report(void) {
    const int *s = pace_sorted(show_iv, show_n);
    char buckets[160] = "";
    int top[3] = { -1, -1, -1 }, at = 0;
    for (int b = 0; b < SHOW_BUCKETS; b++) {
        if (!show_hist[b]) continue;
        int pos = 0;
        while (pos < at && top[pos] >= 0 && show_hist[top[pos]] >= show_hist[b]) pos++;
        if (at < 3) { for (int k = 3; k > pos + 1; k--) top[k - 1] = top[k - 2]; top[pos] = b; at++; }
        else if (pos < 3) { for (int k = 2; k > pos; k--) top[k] = top[k - 1]; top[pos] = b; }
    }
    for (int i = 0; i < at; i++)
        snprintf(buckets + strlen(buckets), sizeof buckets - strlen(buckets), "%s%d.%d ms on %d frames", i ? ", " : "",
                 top[i] / 2, (top[i] % 2) * 5, show_hist[top[i]]);
    LOG("[gl] %d frames as the panel showed them: p50 %d.%d ms, p95 %d.%d, p99 %d.%d, p99.5 %d.%d, max %d.%d ms | the intervals it used: %s",
        show_n, pace_at(s, show_n, 500) / 10, pace_at(s, show_n, 500) % 10,
        pace_at(s, show_n, 950) / 10, pace_at(s, show_n, 950) % 10,
        pace_at(s, show_n, 990) / 10, pace_at(s, show_n, 990) % 10,
        pace_at(s, show_n, 995) / 10, pace_at(s, show_n, 995) % 10,
        pace_at(s, show_n, 1000) / 10, pace_at(s, show_n, 1000) % 10, buckets[0] ? buckets : "none");
    show_n = 0;
    memset(show_hist, 0, sizeof show_hist);
}

// The panel's rate is not the game's rate: the window can ask the system for the frame rate its content has
// (ANativeWindow_setFrameRate, in libnativewindow, API 30 — not linked, the same route vka_present.c's frame_rate()
// takes). A GL title that never asks for a swap interval draws one frame per refresh, so on a panel set to 120 Hz it
// runs at twice the speed it was made for (measured on the AYN Thor, 2026-11-28: "the game presents at 120.0 per
// second" with no eglSwapInterval call in the run). A Switch title's own target is the 60 Hz its display ran at, so
// a game that free-runs at the panel's rate is asked of the system at that target; a game that paces itself lower
// than the panel is asked at what it actually uses. PEANUTS: DM_GL_FPS names the target (0 = never ask).
static void vote_frame_rate(int measured) {
    static int asked;
    int target = fps_target();
    if (!target) return;
    int want = measured;
    if (measured >= 90) want = target;            // free-running at the panel's rate: the console's own rate instead
    // The panel itself is holding the frames (set_swap_interval): the measured rate is then the panel's real one,
    // which drifts a little from its named one (a "120 Hz" panel here measures 118.52). The ask must name the rate
    // the game was made for, not that measurement, or the system is asked for an odd 59.
    if (g_swap_interval >= 1) want = target;
    if (want < 10 || want > 240 || want == asked) return;
    static int (*set_rate)(void *, float, int8_t);
    static int looked, no_symbol, red_once;
    if (!looked) {
        looked = 1;
        void *h = dlopen("libnativewindow.so", RTLD_NOW);
        if (h) set_rate = (int (*)(void *, float, int8_t))dlsym(h, "ANativeWindow_setFrameRate");
        if (!set_rate) no_symbol = 1;
    }
    if (no_symbol) {
        if (!red_once++) LOG("[gl] [RED] this Android has no ANativeWindow_setFrameRate: the game's frames follow the panel's own rate");
        return;
    }
    int r = set_rate(g_win, (float)want, 1 /*FRAME_RATE_COMPATIBILITY_FIXED_SOURCE*/);
    LOG("[gl] the window is asked for %d frames a second (the game presents at %d per second)%s", want, measured,
        r ? " — the system did not take it" : "");
    asked = want;
}

GL(eglSwapBuffers, int, void *dpy, void *surf) {
    if (dpy != &g_dpy) return EGL_FALSE;
    static int shown, shot;
    if (!g_host_gl || !xr_ctx) {
        if (!shown++) LOG("[gl] [RED] eglSwapBuffers: this game presents through GL, and this host has no GL context for it to draw into");
        return EGL_TRUE;
    }
    // The context is current on the game's own surface, where the game drew its frame; a thread that has neither
    // has no frame to show.
    void *cur = xr_surf;
    if (pthread_self() != host_gl_thread && !xr_eglMakeCurrent(xr_dpy, cur, cur, xr_ctx)) {
        LOG("[gl] [RED] this thread has no GL context current: nothing to present (eglGetError %s)", egl_err());
        return EGL_TRUE;
    }
    shown++;
    if (!xr_win_surf) take_window_at(g_vp[2], g_vp[3]);   // a game that presents before it sets a viewport
    if (xr_win_surf && xr_eglSwapBuffers) {
        static int shot_ask = -1;   // PEANUTS: DM_GL_SHOT = how many of the game's frames to write out as images
        if (shot_ask < 0) {
            shot_ask = getenv("DM_GL_SHOT") ? atoi(getenv("DM_GL_SHOT")) : 0;
            g_motion = getenv("DM_GL_MOTION") ? 1 : 0;   // and DM_GL_MOTION = whether the picture is new on every swap
            if (g_motion) LOG("[gl] watching whether each swap carries a new picture (DM_GL_MOTION): the frame-time window is quiet while this runs");
        }
        // The first <n> in a row, and while the switch is set one every 600 frames after that: frames 1 and 3 of a
        // run are 16 ms apart, and both being identical says nothing about whether the game animates — a minute
        // apart, it says whether the game is drawing new pictures or sitting at a screen (2026-11-28, Demon
        // Throttle: the picture on the screen was byte-identical to the game's own frame 2, which this asks about).
        int read_this_frame = 0;   // a frame this file stopped to read the picture back: see pace_measure below
        if (shot < shot_ask || (shot_ask && shown % 600 == 0 && shot < shot_ask + 20)) {
            read_back_frame(++shot);   // what the game put in this buffer, beside what the panel shows
            read_this_frame = 1;
        }
        // From the window being taken, the surface the game's context is current on *is* the window (ours here,
        // and h_eglMakeCurrent's from the game's side): its next frame is drawn in the window's own back buffer,
        // at the size its queue really has, and the swap shows it. No copy in between. The frame drawn just
        // before the handover was drawn in the pbuffer and is lost — one frame. Carrying even that over by
        // copying (glCopyTexSubImage2D into a texture, one full-screen draw out) meant knowing both sizes
        // perfectly, and when that guess went wrong it showed an enlarged piece of the frame: the lower-left
        // 1280x720 of the game's own full 1920x1080 picture, drawn over the whole window (2026-11-28, AYN
        // Thor). The game sizes its own views by eglQuerySurface, which answers this surface's real size now.
        static int said;   // a failed handover is said once; the next frame asks for a new window and tries again
        if (!g_handed) {
            if (!xr_eglMakeCurrent(xr_dpy, xr_win_surf, xr_win_surf, xr_ctx)) {
                if (!said++) LOG("[gl] [RED] the game's context would not become current on the window surface: its frames cannot be shown (eglGetError %s)", egl_err());
                drop_window();
                return EGL_TRUE;
            }
            g_handed = 1;
            ask_panel_timings();   // the surface the frames go to asks for the panel's composition timing: its grid is what the frames are aimed at
            LOG("[gl] the game's context is current on the window surface: its frames go to the window directly, %dx%d", win_w, win_h);
        }
        if (g_motion) motion_probe();   // whether this swap carries a new picture at all (its cost, like the shot's, is kept out of the frame-time window below)
        pace_swap();   // the frame goes at its deadline: this is what holds a step-per-swap engine at its own rate
        long long woke = g_pace_late; g_pace_late = 0;   // the pacer's own lateness belongs to the frame about to be swapped
        unsigned long long fid = g_ts_on ? show_id_take() : 0;   // the number this frame will be known by at the panel
        long long t0 = mono_ns();
        int ok = xr_eglSwapBuffers(xr_dpy, xr_win_surf);
        long long t1 = mono_ns();
        if (fid) show_seen(fid);   // and what the panel did with the frame queued a few swaps before this one
        static long long last_t;   // the moment of the previous swap: the game's own step cadence
        // Two things that are not the game's pacing are left out of the window: a gap of more than a quarter of a
        // second (a load, a pause, the launcher coming back), and a frame this file stopped to read the picture
        // back — DM_GL_SHOT costs about 155 ms of readback and file write per frame it saves, which is what the
        // "max 167 ms" of the first runs was (2026-11-28), and DM_GL_MOTION reads bands of every frame. The frame
        // is still swapped; only its sample is dropped, so the two picture probes say nothing through the clock.
        if (last_t && !read_this_frame && !g_motion && t0 - last_t < 250000000LL) pace_measure(t0 - last_t, t1 - t0, woke);
        last_t = t0;
        if (pace_n >= PACE_WINDOW) pace_report(fps_target());
        if (shown == 1) LOG("[gl] the first frame goes to the screen through the driver's EGL%s", ok ? "" : " — it failed");
        if (ok) {
            // A frame the panel shows is a frame the host has to account for: the frame-time ring the overlay's
            // graph and the second screen's stats page read, the companion's tick, and the launcher's frame-rate
            // numbers. Without these a GL title showed no graph at all (measured on the AYN Thor, 2026-11-28:
            // "the frametime graph is not working on the second screen"). Same order as the Vulkan present, and
            // one thread: the swap here is the game's own present.
            dm_guest_frame_tick();
            if (peanuts_tick) peanuts_tick();
            nxhost_present_note(1);
        }
        // Whether the driver's swap waits for the panel is what settles a game that "runs too fast": a swap that
        // returns at once is a game drawing as fast as it can, and the game's own swap-interval ask (below) is the
        // only thing that asks for the other.
        static struct timespec last;
        static long since;
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        if (!since) last = now;
        if (++since >= 60) {
            long ms = (now.tv_sec - last.tv_sec) * 1000 + (now.tv_nsec - last.tv_nsec) / 1000000;
            LOG("[gl] %d frames in %ld ms: the game presents at %d.%d per second", since, ms,
                ms ? (int)(since * 1000 / ms) : 0, ms ? (int)(since * 10000 / ms) % 10 : 0);
            if (ms >= 200) {
                int rate = (int)(since * 1000 / ms);
                vote_frame_rate(rate);       // ask the system for the panel rate the content has
            }                               // a block too short to measure is not an answer
            last = now; since = 0;
        }
        if (!ok) {   // the window is gone (rotation, the launcher resumed): the next frame asks for the new one
            LOG("[gl] [RED] eglSwapBuffers on the window surface: eglGetError %s", egl_err());
            drop_window();
        }
        return ok;
    }
    if (shot < 3) {   // no window in this process: what the game drew is still the measurement worth having
        shot++;
        read_back_frame(shot);
    }
    return EGL_TRUE;
}
GL(eglSwapInterval, int, void *dpy, int interval) {
    if (dpy != &g_dpy) return EGL_FALSE;
    // The game's own vsync ask goes to the driver, which is the only thing that can pace the swap. Answering TRUE
    // here without forwarding it (as this file did first) leaves the window's queue at its own default, and the
    // game draws as fast as it can: Demon Throttle ran too fast for its own animation on the device (2026-11-28).
    static int (*drv_swap_interval)(void *, int);
    if (!drv_swap_interval) drv_swap_interval = xr_libEGL ? (int (*)(void *, int))dlsym(xr_libEGL, "eglSwapInterval") : NULL;
    int ok = drv_swap_interval ? drv_swap_interval(xr_dpy, interval) : EGL_FALSE;
    LOG("[gl] the game asks for a swap interval of %d: the driver %s it%s", interval, ok ? "took" : "refused",
        drv_swap_interval ? "" : " — this Android's EGL is not reachable by name");
    // A number the game gives is written for the display it was made for (interval 1 means "one frame per refresh of
    // a 60 Hz screen"); on a panel that refreshes faster it means that faster rate. Where this front has chosen an
    // interval from the panel's own grid, that one goes back in after the game's ask.
    if (g_swap_interval >= 0 && g_swap_interval != interval) {
        int want = g_swap_interval;
        g_swap_interval = -1;   // force the ask again: the game's number is now what the driver holds
        LOG("[gl] [RED] the game's swap interval of %d is its own display's; the host's of %d refreshes of this panel goes back in", interval, want);
        set_swap_interval(want, fps_target());
    }
    return ok;
}
GL(eglDestroyContext, int, void *dpy, void *ctx) { (void)ctx; return dpy == &g_dpy ? EGL_TRUE : EGL_FALSE; }
GL(eglDestroySurface, int, void *dpy, void *surf) { (void)surf; return dpy == &g_dpy ? EGL_TRUE : EGL_FALSE; }
GL(eglGetError, unsigned, void) { return EGL_SUCCESS; }
GL(eglGetProcAddress, void *, const char *name) {
    // Direct imports and eglGetProcAddress answer the same implementations upstream (dm/gfx/tests/t_gl_imports.m).
    // A name this file implements answers from here; a name it does not is the driver's own function, because the
    // game's GL runs on the driver's OpenGL (see the FWD wrappers above for the names the title imports directly).
    // Only a name neither has left becomes the host's named stub, which reports itself once: that is how a device
    // run lists the GL surface a title really uses, and it is what stops the game jumping to address 0.
    if (!name) return NULL;
    if (hle_implements(name)) return (void *)resolve_import(NULL, name);
    void *p = drv_seen(name);
    if (p) return p;
    return (void *)resolve_import(NULL, name);
}
GL(glGetProcAddress, void *, const char *name) { return h_eglGetProcAddress(name); }
GL(glXGetProcAddress, void *, const char *name) { return h_eglGetProcAddress(name); }
GL(glXGetProcAddressARB, void *, const char *name) { return h_eglGetProcAddress(name); }

// The names above reach the loader through their section attribute alone; this only says so in the run log, the
// way dm/gfx/nvn/hle_nvn.c registers its no-ops at load.
__attribute__((constructor)) static void gl_android_say(void) {
    LOG("[gl] hle_gl_android.c is in the host's table: EGL answered here, every other GL name forwarded to the "
        "driver's own OpenGL ES, the game's frames shown on the launcher's window through the driver's EGL");
}
