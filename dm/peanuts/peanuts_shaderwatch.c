// Smoother next start (the quick menu's Peanuts tab, dev.peanuts.shaders.ShaderWatch): the host's prewarm list as the tab shows it.
//
// The Vulkan replay learns while a game runs: every pipeline it makes on the frame path or in the background goes into the game's prewarm list
// (DM_TEMP/vk_pipelines.keys, dm/gfx/vk_android/vka_pso.c pw_note), and the next start makes the list ahead of the game (pw_start, the startup prewarm behind
// "Preparing shaders"). With learning on for the game (PN_SHADERWATCH=1, the tab's switch) each run also keeps the pipelines it used, and the next start makes those
// first and before the game starts, also on a driver that does not prewarm the list (vka_pso.c sw_*). vka_pso_watch counts what this run learned and what the next
// start makes ahead on this driver; this file hands that to dev.peanuts.Bridge.shaderWatch for dev.peanuts.shaders.HostWatch, the backend the tab reads, and the
// switch to vka_pso_watch_learn.
#include <stdint.h>

// dm/gfx/vk_android/vka_pso.c (PEANUTS): weak, so a host without the Vulkan replay (the Mac's unit tests) links and answers "not known"
void vka_pso_watch(int out[4]) __attribute__((weak));
void vka_pso_watch_learn(int on) __attribute__((weak));

// out[0] the list (1 kept, 0 none, -1 not read yet), out[1] pipelines learned this run, out[2] made ahead at the next start on this driver, out[3] its records
int pn_shaderwatch(int out[4]) {
    if (!vka_pso_watch) { out[0] = -1; out[1] = out[2] = out[3] = 0; return 0; }
    vka_pso_watch(out);
    return 1;
}

// Learning turned on or off while the game runs (the start reads PN_SHADERWATCH): 0 when this host cannot
int pn_shaderwatch_learn(int on) {
    if (!vka_pso_watch_learn) return 0;
    vka_pso_watch_learn(on);
    return 1;
}

#if defined(__ANDROID__)
#include <jni.h>
// dev.peanuts.Bridge.shaderWatch(int[] out): false when this host has no prewarm list to report (out untouched)
JNIEXPORT jboolean JNICALL Java_dev_peanuts_Bridge_shaderWatch(JNIEnv *env, jclass cls, jintArray out) {
    (void)cls;
    if (!out || (*env)->GetArrayLength(env, out) < 4) return JNI_FALSE;
    int v[4];
    if (!pn_shaderwatch(v)) return JNI_FALSE;
    jint j[4] = {v[0], v[1], v[2], v[3]};
    (*env)->SetIntArrayRegion(env, out, 0, 4, j);
    return JNI_TRUE;
}
// dev.peanuts.Bridge.shaderWatchLearn(boolean on): false when this host cannot
JNIEXPORT jboolean JNICALL Java_dev_peanuts_Bridge_shaderWatchLearn(JNIEnv *env, jclass cls, jboolean on) {
    (void)env; (void)cls;
    return pn_shaderwatch_learn(on ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
}
#endif
