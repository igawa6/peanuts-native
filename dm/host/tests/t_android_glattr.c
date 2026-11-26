// PEANUTS: the EGL attribute lists the Android GL layer hands the system's EGL (vka_glattr.h; vka_gl.c eglBindAPI, eglChooseConfig, eglCreateContext)
#include "../../gfx/vk_android/vka_glattr.h"
#include <stdio.h>

static int checks, fails;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s\n", msg); } } while (0)

int main(void) {
    CHECK(vkg_api(VKG_OPENGL_API) == VKG_OPENGL_ES_API, "the desktop GL API becomes OpenGL ES");
    CHECK(vkg_api(VKG_OPENGL_ES_API) == VKG_OPENGL_ES_API && vkg_api(0) == 0, "other APIs pass");

    // a config list as a desktop GL game gives it: RGBA8, depth 24, window and pbuffer surfaces, the desktop GL renderable type, conformant to GL and ES 2
    const int32_t cfg[] = {0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 24, 0x3033, 0x5, VKG_RENDERABLE_TYPE, VKG_OPENGL_BIT, VKG_CONFORMANT, VKG_OPENGL_BIT | 0x4, VKG_NONE};
    int32_t out[64];
    int n = vkg_config_attrs(cfg, out, 64);
    CHECK(n == 8 && out[16] == VKG_NONE, "every pair kept, the list ended");
    CHECK(out[12] == VKG_RENDERABLE_TYPE && out[13] == VKG_OPENGL_ES3_BIT, "the renderable type's desktop bit becomes the ES 3 bit");
    CHECK(out[14] == VKG_CONFORMANT && out[15] == (VKG_OPENGL_ES3_BIT | 0x4), "conformance too, its other bits kept");
    CHECK(out[0] == 0x3024 && out[1] == 8 && out[10] == 0x3033 && out[11] == 0x5, "the other attributes as the game gave them");
    CHECK(vkg_config_attrs(NULL, out, 64) == 0 && out[0] == VKG_NONE, "no list: an empty one");
    CHECK(vkg_config_attrs(cfg, out, 5) == 2 && out[4] == VKG_NONE, "a small output keeps whole pairs and its end");

    // the game's context list (client version 3), with the desktop attributes a GL 4 game could add
    const int32_t ctx[] = {0x3098, 3, VKG_CONTEXT_PROFILE_MASK, 0x1, VKG_CONTEXT_FLAGS, VKG_FORWARD_COMPATIBLE_BIT | 0x1, VKG_NONE};
    n = vkg_context_attrs(ctx, out, 64);
    CHECK(n == 2 && out[0] == 0x3098 && out[1] == 3, "the version kept");
    CHECK(out[2] == VKG_CONTEXT_FLAGS && out[3] == 0x1 && out[4] == VKG_NONE, "the profile mask gone, forward compatibility dropped from the flags");

    printf("GL layer attributes: %d/%d checks %s\n", checks - fails, checks, fails ? "FAIL" : "ok");
    return fails != 0;
}
