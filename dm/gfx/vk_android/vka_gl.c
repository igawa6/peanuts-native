// PEANUTS: EGL and OpenGL ES forwarding on Android, and the GL side of NVN's GL interop (hle_nvn.c "GL interop").
// Some NVN games draw part of the frame with OpenGL through EGL: NARUTO X BORUTO Ultimate Ninja STORM CONNECTIONS draws its whole UI with Scaleform's GL renderer (90 GL ES 3.0 functions,
// counted with dm/tools/loader_census.py) into one texture that NVN samples over the 3D frame, with fences both ways. NVIDIA's GL driver (its subsdk2) is never loaded; main's EGL
// imports and eglGetProcAddress bind to the functions here (the host's import table, hle.h swhle), which forward to the phone's own EGL and OpenGL ES. Nothing here runs, and the
// system's GL libraries are not opened, until a game calls eglGetDisplay.
//   EGL: the desktop GL API (eglBindAPI EGL_OPENGL_API) becomes OpenGL ES, and in the game's attribute lists only what ES cannot take changes (the renderable type's desktop bit
//     becomes the ES 3 bit, the context's desktop profile mask goes); a context the game makes current with no surface gets a 1x1 pbuffer (eglMakeCurrent below)
//   GL: one wrapper per function the game calls, run on the thread's big host stack (hle_bigstack.c: glTexImage2D has nine arguments) with the host's FPCR (the driver's shader
//     compiler runs inside these calls: vka_fpenv.h); every other name the system has goes to its function as it is, through a stub on the big host stack
//   the shared image (nvn_gl_tex_desc): the GL texture an NVN texture names is given an AHardwareBuffer: a GL texture over it (an EGLImage) takes the game's texture name in the
//     calls that take one (glBindTexture, glFramebufferTexture2D, glDeleteTextures), with the game's current content copied in once, and the replay imports the same buffer as
//     the NVN texture's image (vka_tex.c img_create_gl). No copy per frame
//   GL -> NVN (nvn_gl_sync_from_gl): an EGL fence at the game's glFenceSync; QueueWaitSync waits for it before NVN commands that read the image are recorded
//   NVN -> GL (nvn_gl_sync_to_gl): a GLsync of this layer's own; glWaitSync / glClientWaitSync on it wait for the NVN fence on the CPU (mtl_wait)
#include "vka.h"
#include "vka_glattr.h"
#include "../nvn/nvn_mtl.h"
#include "../../host/hle.h"
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <string.h>

_Static_assert(VKG_RENDERABLE_TYPE == EGL_RENDERABLE_TYPE && VKG_CONFORMANT == EGL_CONFORMANT && VKG_OPENGL_BIT == EGL_OPENGL_BIT && VKG_OPENGL_ES3_BIT == EGL_OPENGL_ES3_BIT && VKG_OPENGL_API == EGL_OPENGL_API &&
               VKG_OPENGL_ES_API == EGL_OPENGL_ES_API && VKG_CONTEXT_FLAGS == EGL_CONTEXT_FLAGS_KHR && VKG_CONTEXT_PROFILE_MASK == EGL_CONTEXT_OPENGL_PROFILE_MASK &&
               VKG_FORWARD_COMPATIBLE_BIT == EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR && VKG_NONE == EGL_NONE, "vka_glattr.h names EGL's values");
#define GLOG(...) LOG("[gl] " __VA_ARGS__)
#define GRED(...) do { static _Atomic int once_; if (!atomic_exchange(&once_, 1)) LOG("[RED] [gl] " __VA_ARGS__); } while (0)

// ---------- The system's libraries ----------
static void *g_egl_lib, *g_gles_lib;
static pthread_once_t g_lib_once = PTHREAD_ONCE_INIT;
static void lib_open(void) {
    g_egl_lib = dlopen("libEGL.so", RTLD_NOW | RTLD_LOCAL);
    g_gles_lib = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);   // (libGLESv3.so is the same library)
    if (!g_egl_lib || !g_gles_lib) LOG("[RED] [gl] the system's EGL / OpenGL ES libraries did not open (%s): the game's GL calls do nothing", dlerror());
}
static __eglMustCastToProperFunctionPointerType (*r_eglGetProcAddress)(const char *);
static void *sym(const char *name) {   // a system EGL / GL function: exported, else the driver's (extensions)
    pthread_once(&g_lib_once, lib_open);
    void *f = g_egl_lib ? dlsym(g_egl_lib, name) : NULL;
    if (!f && g_gles_lib) f = dlsym(g_gles_lib, name);
    if (!f && g_egl_lib) { if (!r_eglGetProcAddress) r_eglGetProcAddress = dlsym(g_egl_lib, "eglGetProcAddress"); if (r_eglGetProcAddress) f = (void *)r_eglGetProcAddress(name); }
    return f;
}
#define REAL(name) static __typeof__(&name) r_ = NULL; if (!r_) r_ = (__typeof__(&name))sym(#name)   // r_: the system's function of that name (NULL when it has none)

// ---------- Entry points ----------
// A hooked function runs on the big host stack (the system driver's calls can be deep) with the host's FPCR around the driver's code
#define GLSTUB(fn) extern const char fn##_bs[];                                                       \
    __asm__(".text\n.p2align 2\n.globl " PLAT_ASM_SYM(fn##_bs) "\n" PLAT_ASM_HIDDEN " " PLAT_ASM_SYM(fn##_bs) "\n" PLAT_ASM_SYM(fn##_bs) ":\n" \
            "    adrp x16, " PLAT_ASM_PAGE(fn) "\n    add x16, x16, " PLAT_ASM_PAGEOFF(fn) "\n    b " PLAT_ASM_SYM(dm_bigstack_enter) "\n")
#define HOOK(name, ret, ...)                                                                       \
    static ret h_##name(__VA_ARGS__) __attribute__((used));                                       \
    GLSTUB(h_##name);                                                                              \
    PLAT_TABLE(swhle) static const hle_ent e_##name = {#name, (void *)h_##name##_bs};            \
    static ret h_##name(__VA_ARGS__)
#define FP_IN uint64_t fp_ = vka_fp_enter(vka_fp_guest())
#define FP_OUT vka_fp_leave(fp_)
#define UNPAREN(...) __VA_ARGS__
// A plain forward: the system's function with the game's arguments. Arguments narrower than 32 bits (GLboolean) are taken as uint32_t (hle.h) and passed on as they are: the callee reads the low byte
#define FWD(ret, name, params, args) HOOK(name, ret, UNPAREN params) {                                     static ret (*fwd_) params; if (!fwd_ && !(fwd_ = (ret (*) params)sym(#name))) { GRED(#name ": not in the system's OpenGL ES"); return (ret)0; }         FP_IN; ret fwd_r_ = fwd_ args; FP_OUT; return fwd_r_; }
#define FWDV(name, params, args) HOOK(name, void, UNPAREN params) {                                        static void (*fwd_) params; if (!fwd_ && !(fwd_ = (void (*) params)sym(#name))) { GRED(#name ": not in the system's OpenGL ES"); return; }         FP_IN; fwd_ args; FP_OUT; }

// ---------- EGL ----------
static EGLDisplay g_dpy = EGL_NO_DISPLAY;
static int g_surfaceless, g_fence_sync, g_native_buffer;   // (surfaceless: logged only, see eglMakeCurrent)
static int has_word(const char *list, const char *w) { size_t n = strlen(w); for (const char *p = list; p && (p = strstr(p, w)); p += n) if ((p == list || p[-1] == ' ') && (p[n] == ' ' || !p[n])) return 1; return 0; }

HOOK(eglGetDisplay, EGLDisplay, void *native) {
    (void)native;   // the game's EGL_DEFAULT_DISPLAY
    REAL(eglGetDisplay); if (!r_) return EGL_NO_DISPLAY;
    FP_IN; g_dpy = r_(EGL_DEFAULT_DISPLAY); FP_OUT;
    return g_dpy;
}
HOOK(eglInitialize, EGLBoolean, EGLDisplay d, EGLint *major, EGLint *minor) {
    REAL(eglInitialize); if (!r_) return EGL_FALSE;
    FP_IN; EGLint ma = 0, mi = 0; EGLBoolean ok = r_(d, &ma, &mi);
    if (ok) {
        REAL(eglQueryString); const char *x = r_ ? r_(d, EGL_EXTENSIONS) : NULL;
        g_surfaceless = has_word(x, "EGL_KHR_surfaceless_context");
        g_fence_sync = has_word(x, "EGL_KHR_fence_sync");
        g_native_buffer = has_word(x, "EGL_ANDROID_get_native_client_buffer") && has_word(x, "EGL_ANDROID_image_native_buffer") && has_word(x, "EGL_KHR_image_base");
        GLOG("EGL %d.%d of the system (%s): the game's OpenGL is forwarded to OpenGL ES; surfaceless contexts %d, fence syncs %d, AHardwareBuffer images %d",
             ma, mi, r_ ? r_(d, EGL_VENDOR) : "?", g_surfaceless, g_fence_sync, g_native_buffer);
    }
    FP_OUT;
    if (major) *major = ma;
    if (minor) *minor = mi;
    return ok;
}
HOOK(eglTerminate, EGLBoolean, EGLDisplay d) { REAL(eglTerminate); if (!r_) return EGL_FALSE; FP_IN; EGLBoolean ok = r_(d); FP_OUT; return ok; }
HOOK(eglReleaseThread, EGLBoolean, void) { REAL(eglReleaseThread); if (!r_) return EGL_FALSE; FP_IN; EGLBoolean ok = r_(); FP_OUT; return ok; }
HOOK(eglGetError, EGLint, void) { REAL(eglGetError); return r_ ? r_() : EGL_NOT_INITIALIZED; }
// NVIDIA's driver takes the desktop GL API with ES shaders; the system's takes ES
HOOK(eglBindAPI, EGLBoolean, uint32_t api) {
    REAL(eglBindAPI); if (!r_) return EGL_FALSE;
    FP_IN; EGLBoolean ok = r_(vkg_api(api)); FP_OUT; return ok;
}
// The game's attributes with the renderable type's (and conformance's) desktop OpenGL bit replaced by the OpenGL ES 3 bit (vka_glattr.h)
HOOK(eglChooseConfig, EGLBoolean, EGLDisplay d, const EGLint *attr, EGLConfig *cfg, EGLint n, EGLint *num) {
    REAL(eglChooseConfig); if (!r_) return EGL_FALSE;
    EGLint a[64]; int k = vkg_config_attrs(attr, a, 64);
    FP_IN; EGLBoolean ok = r_(d, a, cfg, n, num); FP_OUT;
    if (!ok || (num && !*num)) GRED("eglChooseConfig: the system has no configuration for the game's attributes (%d of them)", k);
    return ok;
}
// The game's attributes without the desktop ones (the profile mask, the forward-compatible flag; vka_glattr.h); it asks for version 3
HOOK(eglCreateContext, EGLContext, EGLDisplay d, EGLConfig cfg, EGLContext share, const EGLint *attr) {
    REAL(eglCreateContext); if (!r_) return EGL_NO_CONTEXT;
    EGLint a[64]; vkg_context_attrs(attr, a, 64);
    FP_IN; EGLContext c = r_(d, cfg, share, a); FP_OUT;
    if (c == EGL_NO_CONTEXT) GRED("eglCreateContext failed (%#x)", h_eglGetError());
    return c;
}
HOOK(eglDestroyContext, EGLBoolean, EGLDisplay d, EGLContext c) { REAL(eglDestroyContext); if (!r_) return EGL_FALSE; FP_IN; EGLBoolean ok = r_(d, c); FP_OUT; return ok; }
// A context made current with no surface (the game's way: it draws only into framebuffer objects) gets a 1x1 pbuffer of its configuration on this thread. Qualcomm's OpenGL ES takes
// surfaceless contexts but faults reading the default framebuffer's state without one: Scaleform's glGetIntegerv(GL_RED_BITS) (main+0xd87ad4) read address 0x38 in
// libGLESv2_adreno.so (Adreno 740, V@0676.32). With the pbuffer the query answers the configuration's sizes, as the console's driver does for its surfaceless contexts
HOOK(eglMakeCurrent, EGLBoolean, EGLDisplay d, EGLSurface dr, EGLSurface rd, EGLContext c) {
    REAL(eglMakeCurrent); if (!r_) return EGL_FALSE;
    FP_IN;
    if (c != EGL_NO_CONTEXT && dr == EGL_NO_SURFACE && rd == EGL_NO_SURFACE) {
        static _Thread_local EGLSurface pb = EGL_NO_SURFACE; static _Thread_local EGLint pb_id = -1;
        __typeof__(&eglQueryContext) qc = (__typeof__(&eglQueryContext))sym("eglQueryContext");
        __typeof__(&eglChooseConfig) choose = (__typeof__(&eglChooseConfig))sym("eglChooseConfig");
        __typeof__(&eglCreatePbufferSurface) mk = (__typeof__(&eglCreatePbufferSurface))sym("eglCreatePbufferSurface");
        EGLint id = -1, n = 0; EGLConfig cfg = NULL;
        if (qc && choose && mk && qc(d, c, EGL_CONFIG_ID, &id) && (pb == EGL_NO_SURFACE || id != pb_id)) {   // (a pbuffer of another configuration is kept: contexts of one thread share it otherwise)
            const EGLint q[] = {EGL_CONFIG_ID, id, EGL_NONE}, one[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            EGLSurface s = choose(d, q, &cfg, 1, &n) && n ? mk(d, cfg, one) : EGL_NO_SURFACE;
            if (s != EGL_NO_SURFACE) { pb = s; pb_id = id; }
            else GRED("no 1x1 pbuffer for configuration %d (%#x): the context is made current without a surface", id, h_eglGetError());
        }
        if (pb != EGL_NO_SURFACE && id == pb_id) dr = rd = pb;
    }
    EGLBoolean ok = r_(d, dr, rd, c);
    if (ok && c != EGL_NO_CONTEXT) {
        static _Atomic int told;
        __typeof__(&glGetString) gs = (__typeof__(&glGetString))sym("glGetString");
        if (gs && !atomic_exchange(&told, 1)) GLOG("context current: %s, %s (a 1x1 pbuffer stands for the game's missing surface)", (const char *)gs(GL_VERSION), (const char *)gs(GL_RENDERER));
    }
    FP_OUT;
    return ok;
}

// ---------- The shared image ----------
// A GL texture an NVN texture names (nvn_gl_tex_desc), and the textures of NVN that show it (nvn_gl_tex_bind)
typedef struct { GLuint game, ours; AHardwareBuffer *ahb; EGLImageKHR img; uint32_t w, h; } share_t;
typedef struct { uint64_t serial; AHardwareBuffer *ahb; } bind_t;
typedef struct { EGLContext ctx; GLuint fbo; GLenum att; GLuint tex; GLint level; } fbatt_t;   // the game's glFramebufferTexture2D calls: re-attached when their texture becomes shared
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static share_t g_sh[8]; static int g_nsh;
static bind_t g_bind[32]; static int g_nbind;
static fbatt_t g_fba[64]; static int g_nfba;
static _Atomic int g_any_share;   // a share exists: the name-taking calls look names up

static GLuint ours_of(GLuint game) {   // caller holds g_mu
    for (int i = 0; i < g_nsh; i++) if (g_sh[i].game == game) return g_sh[i].ours;
    return game;
}
static GLuint remap(GLuint name) {
    if (!name || !atomic_load_explicit(&g_any_share, memory_order_acquire)) return name;
    pthread_mutex_lock(&g_mu); GLuint r = ours_of(name); pthread_mutex_unlock(&g_mu); return r;
}

typedef struct {
    __typeof__(&glGetIntegerv) GetIntegerv; __typeof__(&glBindTexture) BindTexture; __typeof__(&glGetTexLevelParameteriv) GetTexLevelParameteriv;
    __typeof__(&glGetTexParameteriv) GetTexParameteriv; __typeof__(&glTexParameteri) TexParameteri; __typeof__(&glGenTextures) GenTextures; __typeof__(&glDeleteTextures) DeleteTextures;
    __typeof__(&glCopyImageSubData) CopyImageSubData; __typeof__(&glBindFramebuffer) BindFramebuffer; __typeof__(&glFramebufferTexture2D) FramebufferTexture2D;
    __typeof__(&glGetError) GetError; __typeof__(&glFlush) Flush; __typeof__(&glFinish) Finish;
    PFNGLEGLIMAGETARGETTEXSTORAGEEXTPROC ImageTexStorage; PFNGLEGLIMAGETARGETTEXTURE2DOESPROC ImageTexture2D;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC NativeClientBuffer; PFNEGLCREATEIMAGEKHRPROC CreateImage; PFNEGLDESTROYIMAGEKHRPROC DestroyImage;
    PFNEGLCREATESYNCKHRPROC CreateSync; PFNEGLCLIENTWAITSYNCKHRPROC ClientWaitSync; PFNEGLDESTROYSYNCKHRPROC DestroySync;
    __typeof__(&eglGetCurrentContext) CurrentContext;
} glfn_t;
static glfn_t G;
static pthread_once_t g_fn_once = PTHREAD_ONCE_INIT;
static void fn_load(void) {
#define L(f, n) G.f = (__typeof__(G.f))sym(n)
    L(GetIntegerv, "glGetIntegerv"); L(BindTexture, "glBindTexture"); L(GetTexLevelParameteriv, "glGetTexLevelParameteriv"); L(GetTexParameteriv, "glGetTexParameteriv");
    L(TexParameteri, "glTexParameteri"); L(GenTextures, "glGenTextures"); L(DeleteTextures, "glDeleteTextures"); L(CopyImageSubData, "glCopyImageSubData");
    L(BindFramebuffer, "glBindFramebuffer"); L(FramebufferTexture2D, "glFramebufferTexture2D"); L(GetError, "glGetError"); L(Flush, "glFlush"); L(Finish, "glFinish");
    L(ImageTexStorage, "glEGLImageTargetTexStorageEXT"); L(ImageTexture2D, "glEGLImageTargetTexture2DOES");
    L(NativeClientBuffer, "eglGetNativeClientBufferANDROID"); L(CreateImage, "eglCreateImageKHR"); L(DestroyImage, "eglDestroyImageKHR");
    L(CreateSync, "eglCreateSyncKHR"); L(ClientWaitSync, "eglClientWaitSyncKHR"); L(DestroySync, "eglDestroySyncKHR"); L(CurrentContext, "eglGetCurrentContext");
#undef L
}

static void share_free(share_t *s) {   // caller holds g_mu, with a context current (GL objects); the NVN textures' imports keep their own references to the buffer
    if (s->ours && G.DeleteTextures) G.DeleteTextures(1, &s->ours);
    if (s->img != EGL_NO_IMAGE_KHR && G.DestroyImage) G.DestroyImage(g_dpy, s->img);
    if (s->ahb) AHardwareBuffer_release(s->ahb);
    *s = g_sh[--g_nsh];
}
// The game's texture name -> a GL texture over a new AHardwareBuffer of its size, with its content; the game's FBOs that attach it attach ours. Caller holds g_mu
static share_t *share_make(GLuint game, uint32_t w, uint32_t h) {
    if (g_nsh == (int)(sizeof g_sh / sizeof *g_sh)) { GRED("more than %d GL textures shared with NVN at once: not shared", g_nsh); return NULL; }
    if (!g_native_buffer || !G.NativeClientBuffer || !G.CreateImage || !(G.ImageTexStorage || G.ImageTexture2D)) {
        GRED("the system's EGL cannot make an image of an AHardwareBuffer: GL texture %u is not shared with NVN (its NVN texture stays empty)", game); return NULL; }
    // CPU_READ_RARELY: a linear buffer (Qualcomm's allocator compresses GPU-only RGBA8 buffers), the one layout both GL and Vulkan drivers read the same way without metadata
    AHardwareBuffer_Desc ad = {.width = w, .height = h, .layers = 1, .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
                               .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_CPU_READ_RARELY};
    share_t s = {.game = game, .img = EGL_NO_IMAGE_KHR, .w = w, .h = h};
    int e = AHardwareBuffer_allocate(&ad, &s.ahb);
    if (e || !s.ahb) { GRED("AHardwareBuffer_allocate %ux%u RGBA8: %d: GL texture %u is not shared with NVN", w, h, e, game); return NULL; }
    const EGLint ia[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    s.img = G.CreateImage(g_dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, G.NativeClientBuffer(s.ahb), ia);
    if (s.img == EGL_NO_IMAGE_KHR) { GRED("eglCreateImageKHR of an AHardwareBuffer failed (%#x): GL texture %u is not shared with NVN", h_eglGetError(), game); AHardwareBuffer_release(s.ahb); return NULL; }
    GLint was = 0, minf = GL_LINEAR, magf = GL_LINEAR, ws = GL_CLAMP_TO_EDGE, wt = GL_CLAMP_TO_EDGE;
    G.GetIntegerv(GL_TEXTURE_BINDING_2D, &was);
    G.BindTexture(GL_TEXTURE_2D, game);
    G.GetTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, &minf); G.GetTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, &magf);
    G.GetTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, &ws); G.GetTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, &wt);
    G.GenTextures(1, &s.ours);
    G.BindTexture(GL_TEXTURE_2D, s.ours);
    if (G.ImageTexStorage) G.ImageTexStorage(GL_TEXTURE_2D, s.img, NULL); else G.ImageTexture2D(GL_TEXTURE_2D, s.img);   // immutable like the game's own (glTexStorage2D), where the driver can
    G.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minf); G.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, magf);
    G.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, ws); G.TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wt);
    G.BindTexture(GL_TEXTURE_2D, (GLuint)was);
    GLenum err = G.GetError();
    if (err) { GRED("the GL texture over the AHardwareBuffer: GL error %#x: GL texture %u is not shared with NVN", err, game); g_sh[g_nsh++] = s; share_free(&g_sh[g_nsh - 1]); return NULL; }
    if (G.CopyImageSubData) G.CopyImageSubData(game, GL_TEXTURE_2D, 0, 0, 0, 0, s.ours, GL_TEXTURE_2D, 0, 0, 0, 0, (GLsizei)w, (GLsizei)h, 1);   // what the game drew into it so far (its clear)
    // The game's framebuffers of this context that attach its texture attach ours (both bindings restored)
    EGLContext ctx = G.CurrentContext(); GLint dfb = 0, rfb = 0; int moved = 0;
    G.GetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &dfb); G.GetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &rfb);
    for (int i = 0; i < g_nfba; i++) if (g_fba[i].tex == game) {
        if (g_fba[i].ctx != ctx) { GRED("GL texture %u is attached to a framebuffer of another context: that framebuffer keeps drawing into the unshared texture", game); continue; }
        G.BindFramebuffer(GL_FRAMEBUFFER, g_fba[i].fbo); G.FramebufferTexture2D(GL_FRAMEBUFFER, g_fba[i].att, GL_TEXTURE_2D, s.ours, g_fba[i].level); moved++;
    }
    G.BindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)dfb); G.BindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)rfb);
    if ((err = G.GetError())) GRED("sharing GL texture %u: GL error %#x after the copy and %d framebuffer attachments", game, err, moved);
    g_sh[g_nsh++] = s; atomic_store_explicit(&g_any_share, 1, memory_order_release);
    GLOG("GL texture %u (%ux%u RGBA8) shared with NVN through an AHardwareBuffer: GL draws into texture %u over it (%d framebuffer attachments moved)", game, w, h, s.ours, moved);
    return &g_sh[g_nsh - 1];
}

// hle_nvn.c calls these on the game thread's own stack; the parts that call into the system's drivers (gralloc, EGL, GL) run on its big host stack, as the hooked functions do
#define BIG(fn, ret, ...) static ret fn(__VA_ARGS__) __attribute__((used)); GLSTUB(fn)
BIG(tex_desc, int, uint32_t name, nvn_texdesc *d);
BIG(sync_from_gl, uint64_t, const void *glsync);
BIG(sync_wait, int, uint64_t h, uint64_t timeout_ns);
BIG(sync_free, void, uint64_t h);
int nvn_gl_tex_desc(uint32_t name, nvn_texdesc *d) { return ((int (*)(uint32_t, nvn_texdesc *))(const void *)tex_desc_bs)(name, d); }
uint64_t nvn_gl_sync_from_gl(const void *glsync) { return ((uint64_t (*)(const void *))(const void *)sync_from_gl_bs)(glsync); }
int nvn_gl_sync_wait(uint64_t h, uint64_t timeout_ns) { return ((int (*)(uint64_t, uint64_t))(const void *)sync_wait_bs)(h, timeout_ns); }
void nvn_gl_sync_free(uint64_t h) { ((void (*)(uint64_t))(const void *)sync_free_bs)(h); }

// hle_nvn.c: the NVN description of the GL texture `name` (TextureBuilderSetGLTextureName), and the shared image made for it
static int tex_desc(uint32_t name, nvn_texdesc *d) {
    pthread_once(&g_fn_once, fn_load);
    if (!G.CurrentContext || G.CurrentContext() == EGL_NO_CONTEXT || !G.GetTexLevelParameteriv) { GRED("TextureBuilderSetGLTextureName(%u) with no GL context current on the thread: not shared", name); return 0; }
    FP_IN;
    pthread_mutex_lock(&g_mu);
    share_t *s = NULL; for (int i = 0; i < g_nsh; i++) if (g_sh[i].game == name) s = &g_sh[i];
    GLint w = 0, h = 0, fmt = 0, lv = 1, was = 0;
    if (!s) {
        G.GetIntegerv(GL_TEXTURE_BINDING_2D, &was); G.BindTexture(GL_TEXTURE_2D, name);
        G.GetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, &w); G.GetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, &h);
        G.GetTexLevelParameteriv(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT, &fmt); G.GetTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_IMMUTABLE_LEVELS, &lv);
        G.BindTexture(GL_TEXTURE_2D, (GLuint)was);
        GLenum err = G.GetError();
        if (err || w <= 0 || h <= 0) GRED("TextureBuilderSetGLTextureName(%u): not a 2D texture with storage (GL error %#x, %dx%d): not shared", name, err, w, h);
        else if (fmt != GL_RGBA8) GRED("TextureBuilderSetGLTextureName(%u): internal format %#x: only GL_RGBA8 is shared", name, fmt);
        else { if (lv > 1) GRED("GL texture %u has %d levels: only level 0 is shared with NVN", name, lv); s = share_make(name, (uint32_t)w, (uint32_t)h); }
    }
    if (s) { d->target = NVN_TEXTURE_TARGET_2D; d->format = NVN_FORMAT_RGBA8; d->width = s->w; d->height = s->h; d->depth = 1; d->levels = 1; d->samples = 0; d->stride = 0; }
    pthread_mutex_unlock(&g_mu);
    FP_OUT;
    return s != NULL;
}
void nvn_gl_tex_bind(uint64_t serial, uint32_t name) {
    pthread_mutex_lock(&g_mu);
    share_t *s = NULL; for (int i = 0; i < g_nsh; i++) if (g_sh[i].game == name) s = &g_sh[i];
    if (s && g_nbind < (int)(sizeof g_bind / sizeof *g_bind)) { AHardwareBuffer_acquire(s->ahb); g_bind[g_nbind++] = (bind_t){serial, s->ahb}; }
    else if (s) GRED("more than %d NVN textures of GL textures: serial %llu stays empty", g_nbind, (unsigned long long)serial);
    pthread_mutex_unlock(&g_mu);
    if (s && !V.gl_ahb) GRED("the Vulkan driver cannot import AHardwareBuffers (VK_ANDROID_external_memory_android_hardware_buffer): NVN shows nothing of GL texture %u", name);
}
void nvn_gl_tex_release(uint64_t serial) {
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < g_nbind; i++) if (g_bind[i].serial == serial) { AHardwareBuffer_release(g_bind[i].ahb); g_bind[i--] = g_bind[--g_nbind]; }
    pthread_mutex_unlock(&g_mu);
}
// vka_tex.c: the buffer of an NVN texture made from a GL texture, acquired (the caller releases it); NULL for any other texture
AHardwareBuffer *vka_gl_ahb(uint64_t serial) {
    AHardwareBuffer *b = NULL;
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < g_nbind && !b; i++) if (g_bind[i].serial == serial) { b = g_bind[i].ahb; AHardwareBuffer_acquire(b); }
    pthread_mutex_unlock(&g_mu);
    return b;
}

// ---------- Syncs ----------
// GL -> NVN: an EGL fence right after the game's glFenceSync and glFlush (the same point in the GL stream). Without fence syncs: glFinish here, and a handle that is already signalled
#define SYNC_DONE 1u
static uint64_t sync_from_gl(const void *glsync) {
    (void)glsync;
    pthread_once(&g_fn_once, fn_load);
    FP_IN; uint64_t h = 0;
    EGLSyncKHR s = g_fence_sync && G.CreateSync ? G.CreateSync(g_dpy, EGL_SYNC_FENCE_KHR, NULL) : EGL_NO_SYNC_KHR;
    if (s != EGL_NO_SYNC_KHR) { if (G.Flush) G.Flush(); h = (uint64_t)(uintptr_t)s; }
    else { GRED("no EGL fence sync (%#x): GL finishes on the CPU at every SyncInitializeFromFencedGLSync", h_eglGetError()); if (G.Finish) G.Finish(); h = SYNC_DONE; }
    FP_OUT;
    return h;
}
static int sync_wait(uint64_t h, uint64_t timeout_ns) {
    if (h == SYNC_DONE) return 1;
    if (!h || !G.ClientWaitSync) return 0;
    FP_IN; EGLint r = G.ClientWaitSync(g_dpy, (EGLSyncKHR)(uintptr_t)h, 0, timeout_ns == UINT64_MAX ? EGL_FOREVER_KHR : (EGLTimeKHR)timeout_ns); FP_OUT;
    if (r == EGL_FALSE) GRED("eglClientWaitSyncKHR failed (%#x)", h_eglGetError());
    return r == EGL_CONDITION_SATISFIED_KHR;
}
static void sync_free(uint64_t h) { if (h && h != SYNC_DONE && G.DestroySync) { FP_IN; G.DestroySync(g_dpy, (EGLSyncKHR)(uintptr_t)h); FP_OUT; } }

// NVN -> GL: GLsync handles of this layer, which glWaitSync / glClientWaitSync / glDeleteSync / glIsSync recognize by address; any other GLsync is the system's
typedef struct { uint64_t fence; uint32_t fenced, used; } nsync_t;
static nsync_t g_ns[64];
static nsync_t *ns_of(const void *p) { return (const char *)p >= (const char *)g_ns && (const char *)p < (const char *)(g_ns + 64) ? (nsync_t *)p : NULL; }
uint64_t nvn_gl_sync_to_gl(uint64_t fence, uint32_t fenced) {
    pthread_mutex_lock(&g_mu);
    nsync_t *s = NULL; for (int i = 0; i < 64 && !s; i++) if (!g_ns[i].used) s = &g_ns[i];
    if (s) *s = (nsync_t){fence, fenced, 1};
    pthread_mutex_unlock(&g_mu);
    if (!s) GRED("SyncCreateGLSync: 64 of its GL syncs are alive (the game deletes none?): no more");
    return (uint64_t)(uintptr_t)s;
}
static int ns_wait(const nsync_t *s, uint64_t timeout_ns) { return s->fenced == 2 ? mtl_wait_acq(s->fence, timeout_ns) : mtl_wait(s->fence, timeout_ns); }

// ---------- GL ----------
static _Thread_local GLenum g_pend_err;   // an error this layer's own check took from the driver, given to the game's next glGetError
HOOK(glGetError, GLenum, void) {
    if (g_pend_err) { GLenum e = g_pend_err; g_pend_err = 0; return e; }
    REAL(glGetError); if (!r_) return GL_NO_ERROR;
    FP_IN; GLenum e = r_(); FP_OUT; return e;
}
HOOK(glBindTexture, void, GLenum target, GLuint name) { REAL(glBindTexture); if (!r_) return; FP_IN; r_(target, remap(name)); FP_OUT; }
HOOK(glFramebufferTexture2D, void, GLenum target, GLenum att, GLenum textarget, GLuint tex, GLint level) {
    REAL(glFramebufferTexture2D); if (!r_) return;
    pthread_once(&g_fn_once, fn_load);
    FP_IN;
    GLint fbo = 0; G.GetIntegerv(target == GL_READ_FRAMEBUFFER ? GL_READ_FRAMEBUFFER_BINDING : GL_DRAW_FRAMEBUFFER_BINDING, &fbo);
    EGLContext ctx = G.CurrentContext ? G.CurrentContext() : EGL_NO_CONTEXT;
    pthread_mutex_lock(&g_mu);
    int i = 0; while (i < g_nfba && !(g_fba[i].ctx == ctx && g_fba[i].fbo == (GLuint)fbo && g_fba[i].att == att)) i++;
    if (tex && i == g_nfba && g_nfba < 64) g_nfba++;
    if (tex && i < g_nfba) g_fba[i] = (fbatt_t){ctx, (GLuint)fbo, att, tex, level};
    else if (!tex && i < g_nfba) g_fba[i] = g_fba[--g_nfba];
    GLuint t = ours_of(tex);
    pthread_mutex_unlock(&g_mu);
    r_(target, att, textarget, t, level);
    FP_OUT;
}
HOOK(glDeleteFramebuffers, void, GLsizei n, const GLuint *fbs) {
    REAL(glDeleteFramebuffers); if (!r_) return;
    pthread_once(&g_fn_once, fn_load);
    EGLContext ctx = G.CurrentContext ? G.CurrentContext() : EGL_NO_CONTEXT;
    pthread_mutex_lock(&g_mu);
    for (GLsizei k = 0; k < n && fbs; k++) for (int i = 0; i < g_nfba; i++) if (g_fba[i].ctx == ctx && g_fba[i].fbo == fbs[k]) g_fba[i--] = g_fba[--g_nfba];
    pthread_mutex_unlock(&g_mu);
    FP_IN; r_(n, fbs); FP_OUT;
}
// The game's texture goes, and with it a shared one over it (the NVN texture's image keeps its own reference to the buffer until the game finalizes it)
HOOK(glDeleteTextures, void, GLsizei n, const GLuint *names) {
    REAL(glDeleteTextures); if (!r_) return;
    FP_IN;
    if (atomic_load_explicit(&g_any_share, memory_order_acquire)) {
        pthread_once(&g_fn_once, fn_load);
        pthread_mutex_lock(&g_mu);
        for (GLsizei k = 0; k < n && names; k++) {
            for (int i = 0; i < g_nsh; i++) if (g_sh[i].game == names[k]) { GLOG("GL texture %u deleted: its shared image goes", names[k]); share_free(&g_sh[i]); break; }
            for (int i = 0; i < g_nfba; i++) if (g_fba[i].tex == names[k]) g_fba[i--] = g_fba[--g_nfba];
        }
        pthread_mutex_unlock(&g_mu);
    }
    r_(n, names);
    FP_OUT;
}
// The game's syncs are the system's; this layer's own (SyncCreateGLSync) wait for an NVN fence on the CPU
HOOK(glWaitSync, void, GLsync s, GLbitfield flags, GLuint64 timeout) {
    nsync_t *o = ns_of(s);
    if (o) { if (!ns_wait(o, 5000000000ull)) GRED("glWaitSync on an NVN fence: not reached in 5 s, GL goes on"); return; }
    REAL(glWaitSync); if (!r_) return; FP_IN; r_(s, flags, timeout); FP_OUT;
}
HOOK(glClientWaitSync, GLenum, GLsync s, GLbitfield flags, GLuint64 timeout) {
    nsync_t *o = ns_of(s);
    if (o) return ns_wait(o, 0) ? GL_ALREADY_SIGNALED : ns_wait(o, timeout) ? GL_CONDITION_SATISFIED : GL_TIMEOUT_EXPIRED;
    REAL(glClientWaitSync); if (!r_) return GL_WAIT_FAILED; FP_IN; GLenum r = r_(s, flags, timeout); FP_OUT; return r;
}
HOOK(glDeleteSync, void, GLsync s) {
    nsync_t *o = ns_of(s);
    if (o) { pthread_mutex_lock(&g_mu); o->used = 0; pthread_mutex_unlock(&g_mu); return; }
    REAL(glDeleteSync); if (!r_) return; FP_IN; r_(s); FP_OUT;
}
HOOK(glIsSync, GLboolean, GLsync s) { if (ns_of(s)) return ns_of(s)->used ? GL_TRUE : GL_FALSE; REAL(glIsSync); if (!r_) return GL_FALSE; FP_IN; GLboolean r = r_(s); FP_OUT; return r; }
// A compressed format the system's driver lacks (the console's driver had BC): said once per format, the error kept for the game's glGetError
HOOK(glCompressedTexImage2D, void, GLenum target, GLint level, GLenum fmt, GLsizei w, GLsizei h, GLint border, GLsizei size, const void *data) {
    REAL(glCompressedTexImage2D); if (!r_) return;
    pthread_once(&g_fn_once, fn_load);
    FP_IN; r_(target, level, fmt, w, h, border, size, data); GLenum e = G.GetError ? G.GetError() : 0; FP_OUT;
    if (e) {
        static uint32_t seen[16]; static int nseen; int k = 0;
        pthread_mutex_lock(&g_mu); while (k < nseen && seen[k] != fmt) k++; int fresh = k == nseen && nseen < 16; if (fresh) seen[nseen++] = fmt; pthread_mutex_unlock(&g_mu);
        if (fresh) LOG("[RED] [gl] glCompressedTexImage2D format %#x %dx%d: GL error %#x (the system's OpenGL ES may not have the format)", fmt, w, h, e);
        if (!g_pend_err) g_pend_err = e;
    }
}

// The rest of the functions the game calls, forwarded as they are
FWDV(glActiveTexture, (GLenum t), (t))
FWDV(glAttachShader, (GLuint p, GLuint s), (p, s))
FWDV(glBindAttribLocation, (GLuint p, GLuint i, const GLchar *n), (p, i, n))
FWDV(glBindBuffer, (GLenum t, GLuint b), (t, b))
FWDV(glBindFramebuffer, (GLenum t, GLuint f), (t, f))
FWDV(glBindRenderbuffer, (GLenum t, GLuint r), (t, r))
FWDV(glBlendEquation, (GLenum m), (m))
FWDV(glBlendEquationSeparate, (GLenum a, GLenum b), (a, b))
FWDV(glBlendFunc, (GLenum s, GLenum d), (s, d))
FWDV(glBlendFuncSeparate, (GLenum a, GLenum b, GLenum c, GLenum d), (a, b, c, d))
FWDV(glBufferData, (GLenum t, GLsizeiptr n, const void *p, GLenum u), (t, n, p, u))
FWDV(glBufferSubData, (GLenum t, GLintptr o, GLsizeiptr n, const void *p), (t, o, n, p))
FWD(GLenum, glCheckFramebufferStatus, (GLenum t), (t))
FWDV(glClear, (GLbitfield m), (m))
FWDV(glClearColor, (GLfloat r, GLfloat g, GLfloat b, GLfloat a), (r, g, b, a))
FWDV(glColorMask, (uint32_t r, uint32_t g, uint32_t b, uint32_t a), (r, g, b, a))
FWDV(glCompileShader, (GLuint s), (s))
FWD(GLuint, glCreateProgram, (void), ())
FWD(GLuint, glCreateShader, (GLenum t), (t))
FWDV(glDeleteBuffers, (GLsizei n, const GLuint *b), (n, b))
FWDV(glDeleteProgram, (GLuint p), (p))
FWDV(glDeleteRenderbuffers, (GLsizei n, const GLuint *r), (n, r))
FWDV(glDeleteShader, (GLuint s), (s))
FWDV(glDepthFunc, (GLenum f), (f))
FWDV(glDepthMask, (uint32_t f), (f))
FWDV(glDisable, (GLenum c), (c))
FWDV(glDisableVertexAttribArray, (GLuint i), (i))
FWDV(glDrawArrays, (GLenum m, GLint f, GLsizei n), (m, f, n))
FWDV(glDrawElements, (GLenum m, GLsizei n, GLenum t, const void *i), (m, n, t, i))
FWDV(glEnable, (GLenum c), (c))
FWDV(glEnableVertexAttribArray, (GLuint i), (i))
FWD(GLsync, glFenceSync, (GLenum c, GLbitfield f), (c, f))
FWDV(glFinish, (void), ())
FWDV(glFlush, (void), ())
FWDV(glFramebufferRenderbuffer, (GLenum t, GLenum a, GLenum rt, GLuint r), (t, a, rt, r))
FWDV(glGenBuffers, (GLsizei n, GLuint *b), (n, b))
FWDV(glGenFramebuffers, (GLsizei n, GLuint *f), (n, f))
FWDV(glGenRenderbuffers, (GLsizei n, GLuint *r), (n, r))
FWDV(glGenTextures, (GLsizei n, GLuint *t), (n, t))
FWDV(glGenerateMipmap, (GLenum t), (t))
FWDV(glGetActiveUniform, (GLuint p, GLuint i, GLsizei n, GLsizei *len, GLint *sz, GLenum *ty, GLchar *name), (p, i, n, len, sz, ty, name))
FWDV(glGetFloatv, (GLenum p, GLfloat *v), (p, v))
FWDV(glGetFramebufferAttachmentParameteriv, (GLenum t, GLenum a, GLenum p, GLint *v), (t, a, p, v))
FWDV(glGetIntegerv, (GLenum p, GLint *v), (p, v))
FWDV(glGetProgramInfoLog, (GLuint p, GLsizei n, GLsizei *len, GLchar *log), (p, n, len, log))
FWDV(glGetProgramiv, (GLuint p, GLenum q, GLint *v), (p, q, v))
FWDV(glGetRenderbufferParameteriv, (GLenum t, GLenum p, GLint *v), (t, p, v))
FWDV(glGetShaderInfoLog, (GLuint s, GLsizei n, GLsizei *len, GLchar *log), (s, n, len, log))
FWDV(glGetShaderiv, (GLuint s, GLenum q, GLint *v), (s, q, v))
FWD(const GLubyte *, glGetString, (GLenum n), (n))
FWDV(glGetTexParameteriv, (GLenum t, GLenum p, GLint *v), (t, p, v))
FWD(GLint, glGetUniformLocation, (GLuint p, const GLchar *n), (p, n))
FWD(GLboolean, glIsFramebuffer, (GLuint f), (f))
FWD(GLboolean, glIsProgram, (GLuint p), (p))
FWD(GLboolean, glIsRenderbuffer, (GLuint r), (r))
FWDV(glLinkProgram, (GLuint p), (p))
FWDV(glPixelStorei, (GLenum p, GLint v), (p, v))
FWDV(glReadPixels, (GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum t, void *p), (x, y, w, h, f, t, p))
FWDV(glReleaseShaderCompiler, (void), ())
FWDV(glRenderbufferStorage, (GLenum t, GLenum f, GLsizei w, GLsizei h), (t, f, w, h))
FWDV(glScissor, (GLint x, GLint y, GLsizei w, GLsizei h), (x, y, w, h))
FWDV(glShaderSource, (GLuint s, GLsizei n, const GLchar *const *src, const GLint *len), (s, n, src, len))
FWDV(glStencilFunc, (GLenum f, GLint r, GLuint m), (f, r, m))
FWDV(glStencilMask, (GLuint m), (m))
FWDV(glStencilOp, (GLenum a, GLenum b, GLenum c), (a, b, c))
FWDV(glTexImage2D, (GLenum t, GLint l, GLint ifmt, GLsizei w, GLsizei h, GLint b, GLenum f, GLenum ty, const void *p), (t, l, ifmt, w, h, b, f, ty, p))
FWDV(glTexParameteri, (GLenum t, GLenum p, GLint v), (t, p, v))
FWDV(glTexStorage2D, (GLenum t, GLsizei l, GLenum f, GLsizei w, GLsizei h), (t, l, f, w, h))
FWDV(glTexSubImage2D, (GLenum t, GLint l, GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum ty, const void *p), (t, l, x, y, w, h, f, ty, p))
FWDV(glUniform1f, (GLint l, GLfloat a), (l, a))
FWDV(glUniform1fv, (GLint l, GLsizei n, const GLfloat *v), (l, n, v))
FWDV(glUniform1i, (GLint l, GLint a), (l, a))
FWDV(glUniform1iv, (GLint l, GLsizei n, const GLint *v), (l, n, v))
FWDV(glUniform2f, (GLint l, GLfloat a, GLfloat b), (l, a, b))
FWDV(glUniform2fv, (GLint l, GLsizei n, const GLfloat *v), (l, n, v))
FWDV(glUniform3fv, (GLint l, GLsizei n, const GLfloat *v), (l, n, v))
FWDV(glUniform4fv, (GLint l, GLsizei n, const GLfloat *v), (l, n, v))
FWDV(glUniformMatrix4fv, (GLint l, GLsizei n, uint32_t tr, const GLfloat *v), (l, n, tr, v))
FWDV(glUseProgram, (GLuint p), (p))
FWDV(glVertexAttribPointer, (GLuint i, GLint sz, GLenum t, uint32_t norm, GLsizei st, const void *p), (i, sz, t, norm, st, p))
FWDV(glViewport, (GLint x, GLint y, GLsizei w, GLsizei h), (x, y, w, h))

// Every other GL name the game asks for that the system's OpenGL ES has: a stub of its own that runs the system's function as it is, on the big host stack (the registers and two
// stack slots carried over). The functions the code reaches through its GOT (loader_census.py) are wrapped above; Scaleform also keeps pointers in objects of its own and calls
// them from there (glGetStringi at main+0xdb497c, through a table at +0x258), which no census of GOT loads sees, so a name left null crashed the first frame (strlen of NULL).
// These run with the game's FPCR: none of them compiles shaders (glCompileShader, glLinkProgram and the draws are wrapped above)
#define NFWD 512
void *vka_gl_fwd[NFWD] __attribute__((visibility("hidden")));
extern const char vka_gl_fwd_stubs[];   // 8 bytes a stub: mov x9, #k; b vka_gl_fwd_entry
__asm__(".text\n.p2align 2\n" PLAT_ASM_SYM(vka_gl_fwd_entry) ":\n"
        "    adrp x16, " PLAT_ASM_PAGE(vka_gl_fwd) "\n    add x16, x16, " PLAT_ASM_PAGEOFF(vka_gl_fwd) "\n    ldr x16, [x16, x9, lsl #3]\n    b " PLAT_ASM_SYM(dm_bigstack_enter) "\n"
        ".p2align 3\n.globl " PLAT_ASM_SYM(vka_gl_fwd_stubs) "\n" PLAT_ASM_HIDDEN " " PLAT_ASM_SYM(vka_gl_fwd_stubs) "\n" PLAT_ASM_SYM(vka_gl_fwd_stubs) ":\n"
        ".set vka_gl_fwd_k, 0\n.rept 512\n    mov x9, #vka_gl_fwd_k\n    b " PLAT_ASM_SYM(vka_gl_fwd_entry) "\n.set vka_gl_fwd_k, vka_gl_fwd_k + 1\n.endr\n");
static const char *g_fwd_name[NFWD]; static int g_nfwd;
static void *fwd_of(const char *name) {   // caller holds g_mu
    for (int i = 0; i < g_nfwd; i++) if (!strcmp(g_fwd_name[i], name)) return (void *)(vka_gl_fwd_stubs + 8 * i);
    void *f = sym(name);
    if (!f) return NULL;
    if (g_nfwd == NFWD) { GRED("more than %d GL names forwarded as they are: %s gets a null pointer", NFWD, name); return NULL; }
    g_fwd_name[g_nfwd] = name; vka_gl_fwd[g_nfwd] = f;   // (the game's own string: its loader's name table)
    return (void *)(vka_gl_fwd_stubs + 8 * g_nfwd++);
}

// eglGetProcAddress: the GL names above from the host's import table, then the system's functions as they are; a name the system lacks gets a null pointer
HOOK(eglGetProcAddress, void *, const char *name) {
    static const hle_ent *sec; static unsigned long sz; static int asked, wrapped, plain, missing;
    if (!sec) sec = (const hle_ent *)plat_table("swhle", &sz);
    void *fn = NULL; int gl = name && name[0] == 'g' && name[1] == 'l';
    if (gl) for (unsigned long i = 0; sec && i < sz / sizeof *sec && !fn; i++) if (!strcmp(sec[i].name, name)) fn = sec[i].fn;
    pthread_mutex_lock(&g_mu);
    asked++;
    if (fn) wrapped++;
    else if (gl) { FP_IN; fn = fwd_of(name); FP_OUT; if (fn) plain++; else missing++; }
    if (asked % 128 == 0) GLOG("eglGetProcAddress: %d names asked: %d wrapped, %d forwarded as they are, %d the system's OpenGL ES lacks (null)", asked, wrapped, plain, missing);
    pthread_mutex_unlock(&g_mu);
    return fn;
}
