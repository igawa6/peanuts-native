// Peanuts Native: the companion page's JNI (dev.peanuts.Bridge), built into libnxlaunch_<title ID>.so beside nxlaunch.c (android/app/src/main/cpp/CMakeLists.txt).
// The host's entry points are weak, as the launcher interface's are: a host without Peanuts gives an empty snapshot and no page.
#include <jni.h>
#include <android/native_window_jni.h>
#include <stddef.h>
#include <stdint.h>

#pragma weak nxhost_peanuts_snapshot
size_t nxhost_peanuts_snapshot(char *out, size_t n);
#pragma weak nxhost_peanuts_frame_size
void nxhost_peanuts_frame_size(int *w, int *h);
#pragma weak nxhost_peanuts_frame
int nxhost_peanuts_frame(void *out_rgba, int w, int h);
#pragma weak nxhost_peanuts_frame_again
void nxhost_peanuts_frame_again(void);
#pragma weak nxhost_peanuts_touch
void nxhost_peanuts_touch(int id, float x, float y, int down);
#pragma weak nxhost_peanuts_panel
void nxhost_peanuts_panel(int w, int h);
#pragma weak nxhost_peanuts_present
void nxhost_peanuts_present(int shown);
#pragma weak nxhost_peanuts_rt_state
int nxhost_peanuts_rt_state(void);
#pragma weak nxhost_peanuts_haptic
int nxhost_peanuts_haptic(float *amplitude, int *ms, int *respect_system, int *kind);

JNIEXPORT jstring JNICALL Java_dev_peanuts_Bridge_snapshot(JNIEnv *env, jclass cls) {
    (void)cls;
    char buf[8192] = "";
    if (nxhost_peanuts_snapshot) nxhost_peanuts_snapshot(buf, sizeof buf);
    return (*env)->NewStringUTF(env, buf);
}

// The page's size as w << 16 | h (0 = no page)
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_frameSize(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int w = 0, h = 0;
    if (nxhost_peanuts_frame_size) nxhost_peanuts_frame_size(&w, &h);
    return w > 0 && h > 0 && w < 65536 && h < 65536 ? (w << 16) | h : 0;
}

// The newest frame into a direct ByteBuffer of at least w*h*4 bytes: true when a new frame was written
JNIEXPORT jboolean JNICALL Java_dev_peanuts_Bridge_frame(JNIEnv *env, jclass cls, jobject buffer, jint w, jint h) {
    (void)cls;
    void *p = (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!p || cap < (jlong)w * h * 4 || !nxhost_peanuts_frame) return JNI_FALSE;
    return nxhost_peanuts_frame(p, w, h) ? JNI_TRUE : JNI_FALSE;
}

// The next frame() hands out the frame there is, changed or not (a page window made again: peanuts.h)
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_frameAgain(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (nxhost_peanuts_frame_again) nxhost_peanuts_frame_again();
}

JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_touch(JNIEnv *env, jclass cls, jint id, jfloat x, jfloat y, jboolean down) {
    (void)env; (void)cls;
    if (nxhost_peanuts_touch) nxhost_peanuts_touch(id, x, y, down ? 1 : 0);
}

// The page view's size in pixels (CompanionPresentation, when the view is laid out): the runtime's panel; touches are in these pixels from then on (peanuts.h)
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_panel(JNIEnv *env, jclass cls, jint w, jint h) {
    (void)env; (void)cls;
    if (nxhost_peanuts_panel) nxhost_peanuts_panel(w, h);
}

// The page's window is shown or gone (CompanionPresentation.onStart / onStop): the runtime's presence, and the release of held fingers when it goes (peanuts.h)
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_present(JNIEnv *env, jclass cls, jboolean shown) {
    (void)env; (void)cls;
    if (nxhost_peanuts_present) nxhost_peanuts_present(shown ? 1 : 0);
}

// 0 pending, 1 a companion package loaded (the page matters), 2 none for this title, 3 found but refused (see peanuts.h)
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_rtState(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return nxhost_peanuts_rt_state ? nxhost_peanuts_rt_state() : 0;
}

// One pending haptic pulse packed as kind(4 bits) << 25 | respect_system << 24 | amplitude(8 bits, 1..255) << 16 | ms(16 bits, 1..65535); 0 when there is
// none to play. kind is Core::Mods::HapticKind (0 Tap .. 8 Swipe, see peanuts.h) so the Java side can pick a different device effect for e.g. Hold/Refused.
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_haptic(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    float amp = 0; int ms = 0, respect = 0, kind = 0;
    if (!nxhost_peanuts_haptic || !nxhost_peanuts_haptic(&amp, &ms, &respect, &kind)) return 0;
    int amp8 = (int)(amp * 255.0f + 0.5f); if (amp8 < 1) amp8 = 1; if (amp8 > 255) amp8 = 255;
    if (ms < 1) ms = 1; if (ms > 65535) ms = 65535;
    if (kind < 0) kind = 0; if (kind > 15) kind = 15;
    return (kind << 25) | (respect ? (1 << 24) : 0) | (amp8 << 16) | ms;
}

__attribute__((weak)) int nxhost_peanuts_dual_wanted(void);
__attribute__((weak)) void nxhost_peanuts_aux_window(ANativeWindow *w);
__attribute__((weak)) void nxhost_peanuts_dual_touch(int id, float u, float v, int down);

JNIEXPORT jboolean JNICALL Java_dev_peanuts_Bridge_dualWanted(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return nxhost_peanuts_dual_wanted && nxhost_peanuts_dual_wanted() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_setAuxSurface(JNIEnv *env, jclass cls, jobject surface) {
    (void)cls;
    ANativeWindow *w = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    if (nxhost_peanuts_aux_window) nxhost_peanuts_aux_window(w);
    if (w) ANativeWindow_release(w);
}

JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_dualTouch(JNIEnv *env, jclass cls, jint id, jfloat u, jfloat v, jboolean down) {
    (void)env; (void)cls;
    if (nxhost_peanuts_dual_touch) nxhost_peanuts_dual_touch(id, u, v, down ? 1 : 0);
}
