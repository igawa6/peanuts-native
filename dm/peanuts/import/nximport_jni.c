// libnximport's JNI: dev.peanuts.importer.Native (static natives). Every call returns 0 on success, -1 with Native.lastError() set.
//   static native int    keys(String prodKeysPath);
//   static native String probe(String file);                                  // a JSON array of {title_id, app_id, kind, version, name, publisher}; null on failure
//   static native int    icon(String file, String titleId, String outPng);     // titleId may be null (the first title with an icon)
//   static native int    writeNup(String out, String[] inputs, String baseTitleId, Progress cb);
//   static native int    firmwareFonts(String firmwareDir, String outDir);
//   static native String lastError();
//   static native void   setThreads(int n);                                     // optional: writeNup's reader threads (0 = automatic: online CPUs, at most 6); the .nup is the same
//   interface Progress { void on(String step, long done, long total); }        // called from the thread that called writeNup (the readers never call back)
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "nximport.h"

static int json_str(char **buf, size_t *n, size_t *cap, const char *s) {   // append a JSON string (UTF-8 passed through, control characters escaped)
    size_t need = 2; for (const char *p = s; *p; p++) need += (*p == '"' || *p == '\\') ? 2 : ((unsigned char)*p < 0x20 ? 6 : 1);
    if (*n + need + 1 > *cap) { size_t nc = *cap ? *cap * 2 : 1024; while (nc < *n + need + 1) nc *= 2; char *nb = realloc(*buf, nc); if (!nb) return -1; *buf = nb; *cap = nc; }
    char *o = *buf + *n; *o++ = '"';
    for (const char *p = s; *p; p++) {
        unsigned char c = (unsigned char)*p;
        if (c == '"' || c == '\\') { *o++ = '\\'; *o++ = (char)c; }
        else if (c < 0x20) { static const char hx[] = "0123456789abcdef"; *o++ = '\\'; *o++ = 'u'; *o++ = '0'; *o++ = '0'; *o++ = hx[c >> 4]; *o++ = hx[c & 15]; }
        else *o++ = (char)c;
    }
    *o++ = '"'; *n = (size_t)(o - *buf); (*buf)[*n] = 0; return 0;
}
static int json_raw(char **buf, size_t *n, size_t *cap, const char *s) {
    size_t l = strlen(s);
    if (*n + l + 1 > *cap) { size_t nc = *cap ? *cap * 2 : 1024; while (nc < *n + l + 1) nc *= 2; char *nb = realloc(*buf, nc); if (!nb) return -1; *buf = nb; *cap = nc; }
    memcpy(*buf + *n, s, l + 1); *n += l; return 0;
}

static const char *cstr(JNIEnv *env, jstring s) { return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL; }
static void cfree(JNIEnv *env, jstring s, const char *c) { if (s && c) (*env)->ReleaseStringUTFChars(env, s, c); }

JNIEXPORT jint JNICALL Java_dev_peanuts_importer_Native_keys(JNIEnv *env, jclass cls, jstring path) {
    const char *p = cstr(env, path); int rc = p ? nximport_keys(p) : -1; cfree(env, path, p); return rc;
}

JNIEXPORT jstring JNICALL Java_dev_peanuts_importer_Native_probe(JNIEnv *env, jclass cls, jstring file) {
    const char *p = cstr(env, file); if (!p) return NULL;
    NxImportInfo *info = calloc(64, sizeof *info); int n = 0; jstring out = NULL;
    if (info && !nximport_probe(p, info, 64, &n)) {
        char *b = NULL; size_t bn = 0, bc = 0; int bad = json_raw(&b, &bn, &bc, "[");
        char num[160];
        for (int i = 0; i < n && i < 64 && !bad; i++) {
            bad |= json_raw(&b, &bn, &bc, i ? ",{\"title_id\":" : "{\"title_id\":") | json_str(&b, &bn, &bc, info[i].title_id);
            bad |= json_raw(&b, &bn, &bc, ",\"app_id\":") | json_str(&b, &bn, &bc, info[i].app_id);
            snprintf(num, sizeof num, ",\"kind\":%d,\"version\":%u,\"name\":", info[i].kind, info[i].version); bad |= json_raw(&b, &bn, &bc, num) | json_str(&b, &bn, &bc, info[i].name);
            bad |= json_raw(&b, &bn, &bc, ",\"publisher\":") | json_str(&b, &bn, &bc, info[i].publisher);
            snprintf(num, sizeof num, ",\"size\":%llu,\"unpack\":%llu,\"check\":%llu}", info[i].size, info[i].unpack, info[i].check); bad |= json_raw(&b, &bn, &bc, num);
        }
        bad |= json_raw(&b, &bn, &bc, "]");
        if (!bad) out = (*env)->NewStringUTF(env, b);
        free(b);
    }
    free(info); cfree(env, file, p); return out;
}

JNIEXPORT jint JNICALL Java_dev_peanuts_importer_Native_icon(JNIEnv *env, jclass cls, jstring file, jstring tid, jstring out) {
    const char *f = cstr(env, file), *t = cstr(env, tid), *o = cstr(env, out);
    int rc = f && o ? nximport_icon(f, t, o) : -1;
    cfree(env, file, f); cfree(env, tid, t); cfree(env, out, o); return rc;
}

typedef struct Cb { JNIEnv *env; jobject cb; jmethodID on; } Cb;
static void jprogress(void *user, const char *step, long long done, long long total) {
    Cb *c = user; if (!c->cb) return;
    jstring s = (*c->env)->NewStringUTF(c->env, step);
    (*c->env)->CallVoidMethod(c->env, c->cb, c->on, s, (jlong)done, (jlong)total);
    if (s) (*c->env)->DeleteLocalRef(c->env, s);
    if ((*c->env)->ExceptionCheck(c->env)) (*c->env)->ExceptionClear(c->env);   // a throwing callback does not stop the writer
}

JNIEXPORT jint JNICALL Java_dev_peanuts_importer_Native_writeNup(JNIEnv *env, jclass cls, jstring out, jobjectArray inputs, jstring base, jobject cb) {
    const char *o = cstr(env, out), *b = cstr(env, base); int n = inputs ? (*env)->GetArrayLength(env, inputs) : 0, rc = -1;
    const char **in = calloc(n ? (size_t)n : 1, sizeof *in); jstring *js = calloc(n ? (size_t)n : 1, sizeof *js);
    if (o && b && in && js) {
        for (int i = 0; i < n; i++) { js[i] = (*env)->GetObjectArrayElement(env, inputs, i); in[i] = cstr(env, js[i]); }
        Cb c = { env, cb, NULL };
        if (cb) { jclass k = (*env)->GetObjectClass(env, cb); c.on = (*env)->GetMethodID(env, k, "on", "(Ljava/lang/String;JJ)V"); if (!c.on) { (*env)->ExceptionClear(env); c.cb = NULL; } }
        rc = nximport_write_nup(o, in, n, b, c.cb ? jprogress : NULL, &c);
        for (int i = 0; i < n; i++) { cfree(env, js[i], in[i]); if (js[i]) (*env)->DeleteLocalRef(env, js[i]); }
    }
    free(in); free(js); cfree(env, out, o); cfree(env, base, b); return rc;
}

JNIEXPORT jint JNICALL Java_dev_peanuts_importer_Native_firmwareFonts(JNIEnv *env, jclass cls, jstring dir, jstring out) {
    const char *d = cstr(env, dir), *o = cstr(env, out); int rc = d && o ? nximport_firmware_fonts(d, o) : -1;
    cfree(env, dir, d); cfree(env, out, o); return rc;
}

JNIEXPORT jstring JNICALL Java_dev_peanuts_importer_Native_lastError(JNIEnv *env, jclass cls) {
    return (*env)->NewStringUTF(env, nximport_last_error());
}

JNIEXPORT void JNICALL Java_dev_peanuts_importer_Native_setThreads(JNIEnv *env, jclass cls, jint n) { nximport_set_threads((int)n); }
// Stop (issue #149): any thread; writeNup then returns -1 within about a second ("the import was stopped"). on = false before an import starts
JNIEXPORT void JNICALL Java_dev_peanuts_importer_Native_cancel(JNIEnv *env, jclass cls, jboolean on) { nximport_cancel(on ? 1 : 0); }
