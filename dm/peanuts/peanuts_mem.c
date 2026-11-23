// PEANUTS: the memory-pressure reader of peanuts_mem.h
#include "peanuts_mem.h"
#include "peanuts_opt.h"
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static _Atomic int g_last;

int pn_psi_parse(const char *text, pn_psi *out) {
    if (!out) return -1;
    memset(out, 0, sizeof *out);
    if (!text) return -1;
    const char *p = text;
    while (*p) {
        const char *e = strchr(p, '\n');
        unsigned long long total = 0;
        float a, b, c;
        if (!strncmp(p, "some ", 5) && sscanf(p, "some avg10=%f avg60=%f avg300=%f total=%llu", &a, &b, &c, &total) >= 3) {
            out->some10 = a; out->some60 = b; out->some300 = c; out->some_total_us = total; out->have_some = 1;
        } else if (!strncmp(p, "full ", 5) && sscanf(p, "full avg10=%f avg60=%f avg300=%f total=%llu", &a, &b, &c, &total) >= 3) {
            out->full10 = a; out->full60 = b; out->full300 = c; out->full_total_us = total; out->have_full = 1;
        }
        if (!e) break;
        p = e + 1;
    }
    return out->have_some ? 0 : -1;
}

int pn_meminfo_parse(const char *text, pn_meminfo *out) {
    if (!out) return -1;
    memset(out, 0, sizeof *out);
    if (!text) return -1;
    int have_total = 0, have_avail = 0;
    const char *p = text;
    while (*p) {
        const char *e = strchr(p, '\n');
        char key[48];
        unsigned long long v;
        if (sscanf(p, "%47[^:]: %llu", key, &v) == 2) {
            if (!strcmp(key, "MemTotal")) { out->total_kb = v; have_total = 1; }
            else if (!strcmp(key, "MemAvailable")) { out->avail_kb = v; have_avail = 1; }
            else if (!strcmp(key, "SwapTotal")) out->swap_total_kb = v;
            else if (!strcmp(key, "SwapFree")) out->swap_free_kb = v;
        }
        if (!e) break;
        p = e + 1;
    }
    return have_total && have_avail ? 0 : -1;
}

int pn_mem_level(const pn_psi *psi, const pn_meminfo *mi) {
    int lv = PN_MEM_OK;
    if (psi && psi->have_some) {
        int l = PN_MEM_OK;
        if (psi->full10 >= 5.0f || psi->some10 >= 25.0f) l = PN_MEM_SEVERE;
        else if (psi->full10 >= 1.0f || psi->some10 >= 8.0f) l = PN_MEM_MILD;
        if (l > lv) lv = l;
    }
    if (mi && mi->total_kb > 0) {
        int l = PN_MEM_OK;
        if (mi->avail_kb * 100 < mi->total_kb * 8) l = PN_MEM_SEVERE;
        else if (mi->avail_kb * 100 < mi->total_kb * 15) l = PN_MEM_MILD;
        if (l > lv) lv = l;
    }
    return lv;
}

// The file's text in a stack buffer (these files are small: /proc/meminfo is about 1.5 KB); 0 when it cannot be read
static int slurp(const char *path, char *buf, size_t cap) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    size_t n = fread(buf, 1, cap - 1, f);
    fclose(f);
    buf[n] = 0;
    return n > 0;
}

int pn_mem_read(const char *psi_path, const char *meminfo_path, int *sources) {
    char buf[8192];
    pn_psi psi, *pp = NULL;
    pn_meminfo mi, *pm = NULL;
    int src = 0;
    if (slurp(psi_path ? psi_path : "/proc/pressure/memory", buf, sizeof buf) && pn_psi_parse(buf, &psi) == 0) { pp = &psi; src |= 1; }
    if (slurp(meminfo_path ? meminfo_path : "/proc/meminfo", buf, sizeof buf) && pn_meminfo_parse(buf, &mi) == 0) { pm = &mi; src |= 2; }
    if (sources) *sources = src;
    return pn_mem_level(pp, pm);
}

int pn_mem_poll(void) {
    if (!pn_opt_enabled(PN_OPT_MEM_WATCH)) return PN_MEM_OK;
    int lv = pn_mem_read(NULL, NULL, NULL);
    atomic_store_explicit(&g_last, lv, memory_order_relaxed);
    return lv;
}

int pn_mem_last(void) { return atomic_load_explicit(&g_last, memory_order_relaxed); }
