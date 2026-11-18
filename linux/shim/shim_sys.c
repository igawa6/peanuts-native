// Bionic services with no glibc counterpart: system properties and a thread's kernel ID
#include <dlfcn.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/system_properties.h>

static int read_line(const char *path, char *out, size_t n) {
    FILE *f = fopen(path, "r"); if (!f) return 0;
    size_t k = fread(out, 1, n - 1, f); fclose(f); out[k] = 0;
    for (size_t i = 0; i < k; i++) if (out[i] == '\n' || out[i] == '\r') { out[i] = 0; break; }
    return (int)strlen(out);
}
// Only the product names are asked for (plat_device_model): the board's from DMI or the device tree
int __system_property_get(const char *name, char *value) {
    value[0] = 0;
    if (!strcmp(name, "ro.product.manufacturer")) { if (!read_line("/sys/class/dmi/id/sys_vendor", value, PROP_VALUE_MAX)) strcpy(value, "Linux"); }
    else if (!strcmp(name, "ro.product.model")) { if (!read_line("/sys/class/dmi/id/product_name", value, PROP_VALUE_MAX)) read_line("/proc/device-tree/model", value, PROP_VALUE_MAX); }
    return (int)strlen(value);
}

// glibc 2.42 has pthread_gettid_np; older ones keep the ID in the thread descriptor pthread_t points at. Its offset is found once on the calling thread, whose ID gettid gives:
// the first int there equal to it, with the same search on a second thread agreeing
static _Atomic int g_tid_off = -1;
static int tid_offset(pthread_t self, pid_t tid) { const int *p = (const int *)self; for (int i = 0; i < 512; i++) if (p[i] == tid) return i * 4; return -1; }
static void *tid_probe(void *out) { *(int *)out = tid_offset(pthread_self(), gettid()); return NULL; }
pid_t nx_pthread_gettid(pthread_t t) {
    static pid_t (*real)(pthread_t); static _Atomic int looked;
    if (!atomic_load(&looked)) { real = (pid_t (*)(pthread_t))dlsym(RTLD_DEFAULT, "pthread_gettid_np"); atomic_store(&looked, 1); }
    if (real) return real(t);
    if (pthread_equal(t, pthread_self())) return gettid();
    int off = atomic_load(&g_tid_off);
    if (off < 0) {
        int other = -1; pthread_t th;
        off = tid_offset(pthread_self(), gettid());
        if (pthread_create(&th, NULL, tid_probe, &other) == 0) pthread_join(th, NULL);
        if (off < 0 || off != other) { fprintf(stderr, "[RED] pthread_gettid_np: the thread ID is not where expected in glibc's thread descriptor (%d, %d)\n", off, other); return -1; }
        atomic_store(&g_tid_off, off);
    }
    return *(const pid_t *)((const char *)t + off);
}
