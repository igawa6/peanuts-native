// PEANUTS: the switches of peanuts_opt.h. Reads are lock-free (one relaxed atomic per option); initialization is once, from the first caller
#include "peanuts_opt.h"
#include <ctype.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

static const char *const k_name[PN_OPT_COUNT] = {
#define X(id, name, def, doc) name,
    PN_OPT_LIST(X)
#undef X
};
static const char *const k_doc[PN_OPT_COUNT] = {
#define X(id, name, def, doc) doc,
    PN_OPT_LIST(X)
#undef X
};
static const int k_def[PN_OPT_COUNT] = {
#define X(id, name, def, doc) def,
    PN_OPT_LIST(X)
#undef X
};

static _Atomic int g_on[PN_OPT_COUNT];
static _Atomic int g_state;   // 0 not read yet, 1 being read, 2 ready

static int find(const char *s, size_t n) {
    for (int i = 0; i < PN_OPT_COUNT; i++)
        if (strlen(k_name[i]) == n && strncmp(k_name[i], s, n) == 0) return i;
    return -1;
}

static void set_all(int on) { for (int i = 0; i < PN_OPT_COUNT; i++) atomic_store_explicit(&g_on[i], on, memory_order_relaxed); }

static int parse(const char *spec) {
    for (int i = 0; i < PN_OPT_COUNT; i++) atomic_store_explicit(&g_on[i], k_def[i], memory_order_relaxed);
    int unknown = 0;
    const char *p = spec ? spec : "";
    while (*p) {
        while (*p && (*p == ',' || *p == ';' || isspace((unsigned char)*p))) p++;
        const char *b = p;
        while (*p && *p != ',' && *p != ';' && !isspace((unsigned char)*p)) p++;
        size_t n = (size_t)(p - b);
        if (!n) continue;
        int on = 1;
        if (*b == '-' || *b == '!') { on = 0; b++; n--; if (!n) { unknown++; continue; } }
        if (n == 3 && !strncmp(b, "all", 3)) { set_all(on); continue; }
        if (n == 4 && !strncmp(b, "none", 4)) { set_all(0); continue; }
        if (n == 7 && !strncmp(b, "default", 7)) { for (int i = 0; i < PN_OPT_COUNT; i++) atomic_store_explicit(&g_on[i], k_def[i], memory_order_relaxed); continue; }
        int i = find(b, n);
        if (i < 0) { unknown++; continue; }
        atomic_store_explicit(&g_on[i], on, memory_order_relaxed);
    }
    return unknown;
}

int pn_opt_init(const char *spec) {
    atomic_store_explicit(&g_state, 1, memory_order_relaxed);
    int u = parse(spec ? spec : getenv("PN_OPTS"));
    atomic_store_explicit(&g_state, 2, memory_order_release);
    return u;
}

static void ensure(void) {
    int s = atomic_load_explicit(&g_state, memory_order_acquire);
    if (s == 2) return;
    int zero = 0;
    if (atomic_compare_exchange_strong(&g_state, &zero, 1)) { parse(getenv("PN_OPTS")); atomic_store_explicit(&g_state, 2, memory_order_release); return; }
    while (atomic_load_explicit(&g_state, memory_order_acquire) != 2) { }   // another thread is reading the environment: a few microseconds
}

int pn_opt_enabled(pn_opt_id id) {
    if ((int)id < 0 || (int)id >= PN_OPT_COUNT) return 0;
    ensure();
    return atomic_load_explicit(&g_on[id], memory_order_relaxed);
}

int pn_opt_set(const char *name, int on) {
    if (!name) return -1;
    int i = find(name, strlen(name));
    if (i < 0) return -1;
    ensure();
    atomic_store_explicit(&g_on[i], on ? 1 : 0, memory_order_relaxed);
    return 0;
}

const char *pn_opt_name(int i) { return i >= 0 && i < PN_OPT_COUNT ? k_name[i] : NULL; }
const char *pn_opt_doc(int i) { return i >= 0 && i < PN_OPT_COUNT ? k_doc[i] : NULL; }
int pn_opt_default(int i) { return i >= 0 && i < PN_OPT_COUNT ? k_def[i] : 0; }
