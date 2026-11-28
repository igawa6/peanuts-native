// PEANUTS: what vka_gl.c changes in a game's EGL calls before the system's EGL gets them (NVIDIA's driver takes the desktop GL API with ES shaders; the system's takes ES).
// Free of EGL's headers so the Mac's unit test (dm/host/tests/t_android_glattr.c) can check it
#ifndef VKA_GLATTR_H
#define VKA_GLATTR_H
#include <stdint.h>

#define VKG_NONE 0x3038
#define VKG_RENDERABLE_TYPE 0x3040
#define VKG_CONFORMANT 0x3042
#define VKG_OPENGL_BIT 0x0008
#define VKG_OPENGL_ES3_BIT 0x0040
#define VKG_OPENGL_API 0x30A2
#define VKG_OPENGL_ES_API 0x30A0
#define VKG_CONTEXT_FLAGS 0x30FC
#define VKG_CONTEXT_PROFILE_MASK 0x30FD
#define VKG_FORWARD_COMPATIBLE_BIT 0x0002

static inline uint32_t vkg_api(uint32_t api) { return api == VKG_OPENGL_API ? VKG_OPENGL_ES_API : api; }

// The game's config attributes (NULL: none) into out (cap ints, at least 1), with the renderable type's and conformance's desktop GL bit replaced by the ES 3 bit; the pairs that do
// not fit are left out. Returns the number of pairs written; out ends with VKG_NONE
static inline int vkg_config_attrs(const int32_t *in, int32_t *out, int cap) {
    int k = 0;
    for (int i = 0; in && in[i] != VKG_NONE && k + 2 < cap; i += 2) {
        out[k] = in[i]; out[k + 1] = in[i + 1];
        if ((in[i] == VKG_RENDERABLE_TYPE || in[i] == VKG_CONFORMANT) && (in[i + 1] & VKG_OPENGL_BIT)) out[k + 1] = (in[i + 1] & ~VKG_OPENGL_BIT) | VKG_OPENGL_ES3_BIT;
        k += 2;
    }
    out[k] = VKG_NONE;
    return k / 2;
}
// The game's context attributes without the desktop ones: the profile mask goes, the flags lose forward compatibility; the rest (the version it asks for) as they are
static inline int vkg_context_attrs(const int32_t *in, int32_t *out, int cap) {
    int k = 0;
    for (int i = 0; in && in[i] != VKG_NONE && k + 2 < cap; i += 2) {
        if (in[i] == VKG_CONTEXT_PROFILE_MASK) continue;
        out[k] = in[i]; out[k + 1] = in[i] == VKG_CONTEXT_FLAGS ? in[i + 1] & ~VKG_FORWARD_COMPATIBLE_BIT : in[i + 1];
        k += 2;
    }
    out[k] = VKG_NONE;
    return k / 2;
}
#endif
