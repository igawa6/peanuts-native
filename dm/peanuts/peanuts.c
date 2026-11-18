// Peanuts Native, host side: see peanuts.h.
//
// Chains: $HOME/peanuts/chains.tsv (HOME is the app's files folder, as nxlaunch sets it), one value a line:
//     <name> <TAB> <type> <TAB> <module>+0x<offset>[,0x<offset>...]
// The first element is a module's base plus an offset (no read); every later element reads a 64-bit pointer at the address so far and adds its offset (the DSMod
// package convention: three dereferences take four elements). The module "abs" is the absolute address 0 (an address a scan found, for this run only). Types: u8 u16 u32
// u64 s8 s16 s32 s64 f32 f64 str (up to 63 bytes). Lines starting with # are comments. The file is re-read when it changes: a new chain is an adb push away.
//
// Scanner: $HOME/peanuts/scan.txt holds one command; a changed file runs it on a worker thread (never the present thread) over the guest memory ([anon:nxnative guest]
// in /proc/self/maps) and the modules' data. Candidates keep their last value, so the next command can compare against it:
//     new <type> <value>          every address holding the value (types u8 u16 u32 u64 s32 f32)
//     next <op> <value>           keep candidates whose current value <op> value, op one of = != < >
//     changed | unchanged | inc | dec     keep candidates by how their value moved since the last command
//     ptr <hex address> [range]   every 8-byte value in [address - range, address] (default range 0x400): who points at a found object; module hits are static roots
//     clear
// Results: the snapshot's scan= line and up to 10 cand lines, and $HOME/peanuts/scan-out.txt (up to 5000 lines, "<address> <module+offset or -> value").
#include "peanuts.h"
#include "../host/dm.h"
#include "../host/plat/plat.h"
#include <inttypes.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>

#define MAX_CHAINS 64
#define MAX_ELEMS 8
#define REFRESH_EVERY 2   // frames between refreshes (a 10 fps game reads 5 times a second; a 60 fps one 30)
#define MAX_CAND (4u << 20)
#define CHUNK (1u << 20)

typedef struct { char name[32]; char type[8]; char module[16]; uint64_t off[MAX_ELEMS]; int n; char value[72]; } chain_t;
typedef struct { uint64_t addr; uint64_t last; } cand_t;

#include "rt/peanuts_rt.h"
#include "peanuts_frametime.h"
#include "peanuts_graph.h"
static void rt_tick(void);   // the companion runtime, below
static PnRt *_Atomic g_rt;   // set once (rt_start, on the presenting thread); read by the tick thread, the page's UI thread (touch, presence) and the lifecycle's thread
static void rt_ui_publish(void *u, uint32_t w, uint32_t h, const uint32_t *px);
static void rt_ui_publish_partial(void *u, uint32_t w, uint32_t h, const uint32_t *px, const int32_t r[4]);

static chain_t g_chains[MAX_CHAINS];
static int g_nchains;
static char g_home[256], g_path[512], g_file_state[64] = "none";
static time_t g_mtime;
static uint64_t g_frames;
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static char g_snapshot[4096];

// ---- scanner state (g_scan_mu) ----
static pthread_mutex_t g_scan_mu = PTHREAD_MUTEX_INITIALIZER;
static cand_t *g_cand; static size_t g_ncand;
static char g_scan_type[8] = "u32";
static char g_scan_state[96] = "idle";
static char g_scan_lines[10][96]; static int g_scan_nlines;
static time_t g_scan_mtime;
static _Atomic int g_scan_busy;
static char g_scan_cmd[128];

static const char *home(void) {
    if (!g_home[0]) { const char *h = getenv("HOME"); snprintf(g_home, sizeof g_home, "%s", h ? h : "."); }
    return g_home;
}

static module_t *module_named(const char *name) {
    for (int i = 0; i < g_nmod; i++) if (!g_mod[i].nro && !strcmp(g_mod[i].name, name)) return &g_mod[i];
    return NULL;
}

// "<module>+0x<offset>" for an address inside a loaded module, else "-"
static void describe(uint64_t addr, char *out, size_t n) {
    for (int i = 0; i < g_nmod; i++) {
        uint64_t b = (uint64_t)(uintptr_t)g_mod[i].base;
        if (g_mod[i].loaded && addr >= b && addr < b + g_mod[i].size) { snprintf(out, n, "%s+%#" PRIx64, g_mod[i].name, addr - b); return; }
    }
    snprintf(out, n, "-");
}

// ---- chains ----
static void load_chains(void) {
    if (!g_path[0]) snprintf(g_path, sizeof g_path, "%s/peanuts/chains.tsv", home());
    struct stat st;
    if (stat(g_path, &st)) { if (g_nchains || strcmp(g_file_state, "missing")) { g_nchains = 0; strcpy(g_file_state, "missing"); } return; }
    if (st.st_mtime == g_mtime) return;
    g_mtime = st.st_mtime;
    FILE *f = fopen(g_path, "r");
    if (!f) { strcpy(g_file_state, "unreadable"); return; }
    int n = 0, bad = 0; char line[512];
    while (n < MAX_CHAINS && fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n' || line[0] == '\r') continue;
        chain_t c = {0};
        char spec[256];
        if (sscanf(line, "%31[^\t]\t%7[^\t]\t%255[^\r\n]", c.name, c.type, spec) != 3) { bad++; continue; }
        char *plus = strchr(spec, '+');
        if (!plus || (size_t)(plus - spec) >= sizeof c.module) { bad++; continue; }
        memcpy(c.module, spec, plus - spec); c.module[plus - spec] = 0;
        for (char *p = plus + 1; p && c.n < MAX_ELEMS; ) { c.off[c.n++] = strtoull(p, NULL, 0); p = strchr(p, ','); if (p) p++; }
        if (!c.n) { bad++; continue; }
        strcpy(c.value, "?");
        g_chains[n++] = c;
    }
    fclose(f);
    g_nchains = n;
    if (bad) snprintf(g_file_state, sizeof g_file_state, "%d chains, %d bad lines", n, bad);
    else snprintf(g_file_state, sizeof g_file_state, "%d chains", n);
}

static void read_chain(chain_t *c) {
    uint64_t addr;
    if (!strcmp(c->module, "abs")) addr = c->off[0];
    else {
        module_t *m = module_named(c->module);
        if (!m) { snprintf(c->value, sizeof c->value, "?no module %s", c->module); return; }
        addr = (uint64_t)(uintptr_t)m->base + c->off[0];
    }
    for (int i = 1; i < c->n; i++) {
        uint64_t p;
        if (plat_mem_read(addr, &p, 8)) { snprintf(c->value, sizeof c->value, "?read %#" PRIx64, addr); return; }
        addr = p + c->off[i];
    }
    union { uint8_t u8; uint16_t u16; uint32_t u32; uint64_t u64; int8_t s8; int16_t s16; int32_t s32; int64_t s64; float f32; double f64; char s[64]; } v;
    const char *t = c->type;
    size_t len = !strcmp(t, "u8") || !strcmp(t, "s8") ? 1 : !strcmp(t, "u16") || !strcmp(t, "s16") ? 2 : !strcmp(t, "u32") || !strcmp(t, "s32") || !strcmp(t, "f32") ? 4
               : !strcmp(t, "str") ? 63 : 8;
    memset(&v, 0, sizeof v);
    if (plat_mem_read(addr, &v, len)) {   // a string may end before a page does: retry shorter
        if (strcmp(t, "str") || plat_mem_read(addr, &v, 16)) { snprintf(c->value, sizeof c->value, "?read %#" PRIx64, addr); return; }
    }
    if (!strcmp(t, "u8")) snprintf(c->value, sizeof c->value, "%u", v.u8);
    else if (!strcmp(t, "u16")) snprintf(c->value, sizeof c->value, "%u", v.u16);
    else if (!strcmp(t, "u32")) snprintf(c->value, sizeof c->value, "%u", v.u32);
    else if (!strcmp(t, "u64")) snprintf(c->value, sizeof c->value, "%" PRIu64, v.u64);
    else if (!strcmp(t, "s8")) snprintf(c->value, sizeof c->value, "%d", v.s8);
    else if (!strcmp(t, "s16")) snprintf(c->value, sizeof c->value, "%d", v.s16);
    else if (!strcmp(t, "s32")) snprintf(c->value, sizeof c->value, "%d", v.s32);
    else if (!strcmp(t, "s64")) snprintf(c->value, sizeof c->value, "%" PRId64, v.s64);
    else if (!strcmp(t, "f32")) snprintf(c->value, sizeof c->value, "%g", v.f32);
    else if (!strcmp(t, "f64")) snprintf(c->value, sizeof c->value, "%g", v.f64);
    else if (!strcmp(t, "str")) { v.s[63] = 0; for (char *p = v.s; *p; p++) if (*p == '\n' || *p == '\r') *p = ' '; snprintf(c->value, sizeof c->value, "%s", v.s); }
    else snprintf(c->value, sizeof c->value, "?type %s", t);
}

// ---- scanner ----
typedef struct { uint64_t a, b; } range_t;

// The guest's memory and the modules: rw regions named "[anon:nxnative guest]" and each loaded module's image
static int scan_ranges(range_t *r, int max) {
    int n = 0;
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    while (f && n < max && fgets(line, sizeof line, f)) {
        uint64_t a, b; char perm[8];
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s", &a, &b, perm) != 3 || perm[0] != 'r' || perm[1] != 'w') continue;
        if (strstr(line, "[anon:nxnative guest]")) { r[n].a = a; r[n].b = b; n++; }
    }
    if (f) fclose(f);
    for (int i = 0; i < g_nmod && n < max; i++) if (g_mod[i].loaded && !g_mod[i].nro) { r[n].a = (uint64_t)(uintptr_t)g_mod[i].base; r[n].b = r[n].a + g_mod[i].size; n++; }
    return n;
}

static size_t type_size(const char *t) { return !strcmp(t, "u8") ? 1 : !strcmp(t, "u16") ? 2 : !strcmp(t, "u64") ? 8 : 4; }

static uint64_t load_val(const uint8_t *p, const char *t) {
    switch (type_size(t)) { case 1: return *p; case 2: { uint16_t v; memcpy(&v, p, 2); return v; } case 8: { uint64_t v; memcpy(&v, p, 8); return v; } default: { uint32_t v; memcpy(&v, p, 4); return v; } }
}

static uint64_t parse_val(const char *t, const char *s) {
    if (!strcmp(t, "f32")) { float f = strtof(s, NULL); uint32_t u; memcpy(&u, &f, 4); return u; }
    if (!strcmp(t, "s32")) { int32_t v = (int32_t)strtol(s, NULL, 0); return (uint32_t)v; }
    return strtoull(s, NULL, 0);
}

static int cmp_val(const char *t, uint64_t cur, const char *op, uint64_t want) {
    if (!strcmp(t, "f32")) { float a, b; uint32_t ua = (uint32_t)cur, ub = (uint32_t)want; memcpy(&a, &ua, 4); memcpy(&b, &ub, 4);
        float d = a - b; if (d < 0) d = -d; float tol = (b < 0 ? -b : b) * 1e-4f + 1e-3f;
        return !strcmp(op, "=") ? d <= tol : !strcmp(op, "!=") ? d > tol : !strcmp(op, "<") ? a < b : a > b; }
    if (!strcmp(t, "s32")) { int32_t a = (int32_t)cur, b = (int32_t)want; return !strcmp(op, "=") ? a == b : !strcmp(op, "!=") ? a != b : !strcmp(op, "<") ? a < b : a > b; }
    return !strcmp(op, "=") ? cur == want : !strcmp(op, "!=") ? cur != want : !strcmp(op, "<") ? cur < want : cur > want;
}

// Full scan: every aligned slot in the ranges holding want (type t); or, for ptr scans (t = "u64", lo..hi), every 8-byte value in [lo, hi]
static void scan_new(const char *t, uint64_t want, uint64_t lo, uint64_t hi, int is_ptr) {
    range_t r[512]; int nr = scan_ranges(r, 512);
    size_t ts = type_size(t);
    uint8_t *buf = malloc(CHUNK + 16);
    if (!g_cand) g_cand = malloc(sizeof(cand_t) * MAX_CAND);
    g_ncand = 0;
    uint64_t scanned = 0;
    for (int i = 0; i < nr && g_cand; i++) {
        for (uint64_t a = r[i].a; a < r[i].b && g_ncand < MAX_CAND; a += CHUNK) {
            size_t len = r[i].b - a < CHUNK ? (size_t)(r[i].b - a) : CHUNK;
            if (plat_mem_read(a, buf, len)) continue;   // a guard or reserved page: skip the chunk
            scanned += len;
            for (size_t o = 0; o + ts <= len && g_ncand < MAX_CAND; o += ts) {
                uint64_t v = load_val(buf + o, t);
                int hit = is_ptr ? (v >= lo && v <= hi) : cmp_val(t, v, "=", want);
                if (hit) { g_cand[g_ncand].addr = a + o; g_cand[g_ncand].last = v; g_ncand++; }
            }
        }
    }
    free(buf);
    snprintf(g_scan_state, sizeof g_scan_state, "%s: %zu candidates (%" PRIu64 " MiB in %d ranges)", g_scan_cmd, g_ncand, scanned >> 20, nr);
}

// Filter the candidates by a predicate on (current, last); keeps their current value as the new last
static void scan_filter(const char *op, uint64_t want, int against_last) {
    size_t kept = 0;
    for (size_t i = 0; i < g_ncand; i++) {
        uint8_t raw[8] = {0};
        if (plat_mem_read(g_cand[i].addr, raw, type_size(g_scan_type))) continue;
        uint64_t cur = load_val(raw, g_scan_type);
        int keep = against_last ? (!strcmp(op, "changed") ? cur != g_cand[i].last : !strcmp(op, "unchanged") ? cur == g_cand[i].last
                                   : !strcmp(op, "inc") ? cmp_val(g_scan_type, cur, ">", g_cand[i].last) : cmp_val(g_scan_type, cur, "<", g_cand[i].last))
                               : cmp_val(g_scan_type, cur, op, want);
        if (keep) { g_cand[kept].addr = g_cand[i].addr; g_cand[kept].last = cur; kept++; }
    }
    g_ncand = kept;
    snprintf(g_scan_state, sizeof g_scan_state, "%s: %zu candidates", g_scan_cmd, g_ncand);
}

static void scan_report(void) {
    char path[512]; snprintf(path, sizeof path, "%s/peanuts/scan-out.txt", home());
    FILE *f = fopen(path, "w");
    g_scan_nlines = 0;
    for (size_t i = 0; i < g_ncand && i < 5000; i++) {
        char where[48]; describe(g_cand[i].addr, where, sizeof where);
        char line[96];
        if (!strcmp(g_scan_type, "f32")) { float v; uint32_t u = (uint32_t)g_cand[i].last; memcpy(&v, &u, 4); snprintf(line, sizeof line, "%#" PRIx64 " %s %g", g_cand[i].addr, where, v); }
        else snprintf(line, sizeof line, "%#" PRIx64 " %s %" PRIu64, g_cand[i].addr, where, g_cand[i].last);
        if (f) fprintf(f, "%s\n", line);
        if (g_scan_nlines < 10) snprintf(g_scan_lines[g_scan_nlines++], sizeof g_scan_lines[0], "%s", line);
    }
    if (f) { fprintf(f, "# %s\n", g_scan_state); fclose(f); }
}

static void *scan_thread(void *arg) {
    (void)arg;
    char cmd[128]; snprintf(cmd, sizeof cmd, "%s", g_scan_cmd);
    char w1[16] = "", w2[32] = "", w3[32] = "";
    sscanf(cmd, "%15s %31s %31s", w1, w2, w3);
    pthread_mutex_lock(&g_scan_mu);
    if (!strcmp(w1, "new") && w3[0]) { snprintf(g_scan_type, sizeof g_scan_type, "%s", w2); scan_new(w2, parse_val(w2, w3), 0, 0, 0); }
    else if (!strcmp(w1, "next") && w3[0]) scan_filter(w2, parse_val(g_scan_type, w3), 0);
    else if (!strcmp(w1, "changed") || !strcmp(w1, "unchanged") || !strcmp(w1, "inc") || !strcmp(w1, "dec")) scan_filter(w1, 0, 1);
    else if (!strcmp(w1, "ptr") && w2[0]) { uint64_t hi = strtoull(w2, NULL, 16), range = w3[0] ? strtoull(w3, NULL, 0) : 0x400; strcpy(g_scan_type, "u64"); scan_new("u64", 0, hi - range, hi, 1); }
    else if (!strcmp(w1, "clear")) { g_ncand = 0; strcpy(g_scan_state, "idle"); }
    else snprintf(g_scan_state, sizeof g_scan_state, "unknown command: %s", cmd);
    scan_report();
    pthread_mutex_unlock(&g_scan_mu);
    atomic_store(&g_scan_busy, 0);
    return NULL;
}

// A changed scan.txt starts its command on a thread of its own (one at a time)
static void scan_poll(void) {
    char path[512]; snprintf(path, sizeof path, "%s/peanuts/scan.txt", home());
    struct stat st;
    if (stat(path, &st) || st.st_mtime == g_scan_mtime || atomic_load(&g_scan_busy)) return;
    g_scan_mtime = st.st_mtime;
    FILE *f = fopen(path, "r");
    if (!f) return;
    g_scan_cmd[0] = 0;
    if (fgets(g_scan_cmd, sizeof g_scan_cmd, f)) g_scan_cmd[strcspn(g_scan_cmd, "\r\n")] = 0;
    fclose(f);
    if (!g_scan_cmd[0]) return;
    atomic_store(&g_scan_busy, 1);
    snprintf(g_scan_state, sizeof g_scan_state, "%s: running", g_scan_cmd);
    pthread_t th; pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &at, scan_thread, NULL)) { atomic_store(&g_scan_busy, 0); strcpy(g_scan_state, "thread failed"); }
    pthread_attr_destroy(&at);
}

// ---- snapshot ----
static void refresh(void) {
    load_chains();
    for (int i = 0; i < g_nchains; i++) read_chain(&g_chains[i]);
    scan_poll();
    module_t *main_mod = module_named("main");
    char *p = g_snapshot, *e = g_snapshot + sizeof g_snapshot;
    p += snprintf(p, e - p, "frames=%" PRIu64 "\nbuild=%s\nmain=%#" PRIx64 "\nchains=%s\n", g_frames, dm_main_build(), main_mod ? (uint64_t)(uintptr_t)main_mod->base : 0, g_file_state);
    for (int i = 0; i < g_nchains && p < e; i++) p += snprintf(p, e - p, "%s=%s\n", g_chains[i].name, g_chains[i].value);
    if (g_rt) { char st[256]; pn_rt_status(g_rt, st, sizeof st); p += snprintf(p, e - p, "rt=%s\n", st); }   // also in logcat on each page change via the runtime's own log
    if (pthread_mutex_trylock(&g_scan_mu) == 0) {   // a running scan keeps its lines until it is done
        p += snprintf(p, e - p, "scan=%s\n", g_scan_state);
        for (int i = 0; i < g_scan_nlines && p < e; i++) p += snprintf(p, e - p, "cand%d=%s\n", i, g_scan_lines[i]);
        pthread_mutex_unlock(&g_scan_mu);
    } else p += snprintf(p, e - p, "scan=%s\n", g_scan_state);
}

void peanuts_tick(void) {
    pn_ft_note();   // the frame time first: it runs whatever the rest of this tick does
    pn_graph_frame();   // the overlay graph's new bar (peanuts_graph.c)
    g_frames++;
    rt_tick();   // the companion runtime: started at the first presented frame after main is loaded; its ticks come from a timer of its own (rt_thread)
    if (g_frames % REFRESH_EVERY) return;
    pthread_mutex_lock(&g_mu);
    refresh();
    pthread_mutex_unlock(&g_mu);
}

// ---- the companion runtime (rt/, the Eden Duo DSMod runtime): one package per title, found beside the pack ----
// The pack is <game>/<title ID>-work/android-pack (dm_module_dir); companion packages are <game>/<title ID>-work/peanuts/<Package>/{package.json,dualscreen/}.
// The game's RomFS (DM_ROMFS) is the runtime's romfs root: a plain folder as a directory, a .nup's inner path through the provider callbacks of peanuts_nup.c.
#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <setjmp.h>
#include <signal.h>
#include <sys/mman.h>
#include <unistd.h>
#include "peanuts_nup.h"

static int g_rt_tried;
static pn_nup *g_nup;   // the .nup RomFS layers behind the runtime's "romfs:" (for the process's life, as the runtime is)
static uint32_t *g_frame; static uint32_t g_frame_w, g_frame_h; static int g_frame_new;
static pthread_mutex_t g_frame_mu = PTHREAD_MUTEX_INITIALIZER;

// Guest memory for the runtime. Guest VA == host VA, so a read inside a mapped readable range is a memcpy; the ranges (the guest block's readable regions and the modules,
// from /proc/self/maps) are cached and refreshed every 2 s (by the tick thread, before a tick) and after a miss. Outside them process_vm_readv (plat_mem_read) decides, as
// before: a syscall per read was the runtime's whole cost (profiled: 15 % of the process in peanuts-tick, mostly process_vm_readv, for ~700 point reads a tick).
// The cache holds what is READABLE, with neighbours joined: it took only the read-write regions, one entry each, up to 512. The GPU's dirty tracking (gfx/nvn/nvn_dirty.c)
// write-protects pages of the game's pools and lifts the protection again all the time, which cuts the guest block into many regions: the read-only ones were never in the
// cache, so every read of a tracked page was a system call and, being "readable but not cached", made the whole of /proc/self/maps be read again every 500 ms; and a block
// in more than 512 pieces lost the rest of its pieces the same way. Joined, the block is a handful of ranges whatever its pages' protection is.
// g_rd is written under g_rd_mu by whoever refreshes and read under it by every reader (the tick thread, the runtime's workers)
// RD_MAX ranges (2026-11-18, was 512): Breath of the Wild's block was in 4,414 anonymous pieces between 2,103 placed GPU segments after a few minutes (the placed
// segments are not the guest's name, so they end a run), the cache kept the lowest 512, and every read above them was a system call: 10,400 of 10,560 reads a tick,
// the companion at 50 % of a core. A block in more pieces than RD_MAX still loses the highest ones, and says so once
enum { RD_MAX = 16384 };
static range_t g_rd[RD_MAX]; static int g_nrd; static _Atomic int64_t g_rd_at_ms;
static pthread_mutex_t g_rd_mu = PTHREAD_MUTEX_INITIALIZER;
static int64_t mono_ns(void) { struct timespec m; clock_gettime(CLOCK_MONOTONIC, &m); return (int64_t)m.tv_sec * 1000000000 + m.tv_nsec; }
static int range_cmp(const void *a, const void *b) { uint64_t x = ((const range_t *)a)->a, y = ((const range_t *)b)->a; return x < y ? -1 : x > y; }
// Adds [a, b) to r[0..n), joined to the entry before it when it begins where that one ends (the lines of /proc/self/maps come in address order)
static int range_add(range_t *r, int n, int max, uint64_t a, uint64_t b) {
    if (n && r[n - 1].b == a) { r[n - 1].b = b; return n; }
    if (n < max) { r[n].a = a; r[n].b = b; n++; }
    return n;
}
static int rt_scan_readable(range_t *r, int max) {
    int n = 0;
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    while (f && fgets(line, sizeof line, f)) {
        if (!strstr(line, "[anon:nxnative guest]")) continue;   // the name first: most lines are not the guest's, and need no parsing
        uint64_t a, b; char perm[8];
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s", &a, &b, perm) != 3 || perm[0] != 'r') continue;
        n = range_add(r, n, max, a, b);
    }
    if (f) fclose(f);
    return n;
}
// One refresh at a time, into a buffer of its own (too large for a worker's stack); a thread that finds one running leaves it to that one
static void rt_ranges_refresh(void) {
    static pthread_mutex_t one = PTHREAD_MUTEX_INITIALIZER; static range_t r[RD_MAX]; static int said;
    if (pthread_mutex_trylock(&one)) return;
    int n = 0;   // the modules first: the block's pieces can fill the table, the modules' code and data are read every tick
    for (int i = 0; i < g_nmod && n < RD_MAX; i++) if (g_mod[i].loaded && !g_mod[i].nro) { r[n].a = (uint64_t)(uintptr_t)g_mod[i].base; r[n].b = r[n].a + g_mod[i].size; n++; }
    int nm = n; n = nm + rt_scan_readable(r + nm, RD_MAX - nm);
    if (n == RD_MAX && !said) { said = 1; __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion: the guest block is in more readable pieces than the read cache holds (%d): reads above them go through the kernel (logged once)", RD_MAX); }
    qsort(r, n, sizeof r[0], range_cmp);
    pthread_mutex_lock(&g_rd_mu); memcpy(g_rd, r, n * sizeof r[0]); g_nrd = n; pthread_mutex_unlock(&g_rd_mu);
    atomic_store(&g_rd_at_ms, mono_ns() / 1000000);
    pthread_mutex_unlock(&one);
}
// The game's code as its file has it. The process runs main rewritten (dm/tools/patch_nso.py: x18 sites, cuts, patches), so the bytes in memory differ from the file's at
// the runs of main.changed.tsv; a package's module that hashes a function to know the build, or decodes its instructions for an address, then fails on every function
// with such a run (Fire Emblem: Three Houses: 2 of the 42 its monastery map needs, so the page waited for ever). main.orig.bin has the file's bytes of those runs, in the
// table's order: a read by the runtime gets them in place of the rewritten ones, and a direct pointer into that part of main is refused (the caller then reads).
// A pack made before the rewriter wrote main.orig.bin has none: the runtime reads the rewritten code, as before, until the game is built again
typedef struct { uint64_t a; uint32_t len, pos; } orig_run;
static orig_run *g_or; static int g_nor; static uint8_t *g_orig; static uint64_t g_or_lo, g_or_hi;
static void rt_orig_load(uint64_t main_base) {
    const char *d = dm_main_dir(); if (!d || g_nor) return;
    char p[900]; snprintf(p, sizeof p, "%s/main.changed.tsv", d);
    FILE *f = fopen(p, "r"); if (!f) return;
    int cap = 0, n = 0; orig_run *r = NULL; uint32_t pos = 0; char line[128];
    while (fgets(line, sizeof line, f)) {
        if (line[0] == '#') continue;
        char *e; uint64_t off = strtoull(line, &e, 16); if (e == line) continue;
        uint64_t len = strtoull(e, NULL, 16); if (!len || len > (1u << 24)) continue;
        if (n == cap) { cap = cap ? cap * 2 : 1024; orig_run *nr = realloc(r, (size_t)cap * sizeof *r); if (!nr) { free(r); fclose(f); return; } r = nr; }
        r[n++] = (orig_run){main_base + off, (uint32_t)len, pos}; pos += (uint32_t)len;
    }
    fclose(f);
    snprintf(p, sizeof p, "%s/main.orig.bin", d);
    int fd = n ? open(p, O_RDONLY) : -1; uint8_t *b = fd >= 0 ? malloc(pos) : NULL;
    ssize_t got = b ? read(fd, b, pos) : -1; char one; int more = b && read(fd, &one, 1) == 1;
    if (fd >= 0) close(fd);
    if (got != (ssize_t)pos || more) {
        __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion: %s (%d changed runs of main, %u bytes): the runtime reads main's code as rewritten; build the game again for the file's own bytes",
                            fd < 0 ? "no main.orig.bin in the pack" : "main.orig.bin is not the size main.changed.tsv says", n, pos);
        free(b); free(r); return;
    }
    g_or = r; g_orig = b; g_or_lo = r[0].a; g_or_hi = r[n - 1].a + r[n - 1].len; g_nor = n;
    __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion: main's code is read as its file has it (%d rewritten runs, %u bytes from main.orig.bin)", n, pos);
}
static void rt_orig_apply(uint64_t addr, uint8_t *buf, size_t len) {
    if (!g_nor || addr >= g_or_hi || addr + len <= g_or_lo) return;
    int lo = 0, hi = g_nor;   // the first run that ends after addr
    while (lo < hi) { int mid = (lo + hi) / 2; if (g_or[mid].a + g_or[mid].len <= addr) lo = mid + 1; else hi = mid; }
    for (int i = lo; i < g_nor && g_or[i].a < addr + len; i++) {
        uint64_t a = g_or[i].a > addr ? g_or[i].a : addr, b = g_or[i].a + g_or[i].len < addr + len ? g_or[i].a + g_or[i].len : addr + len;
        memcpy(buf + (a - addr), g_orig + g_or[i].pos + (a - g_or[i].a), (size_t)(b - a));
    }
}
// For a package's load plan (rt/peanuts_helper.cpp): 1 when [addr, addr + len) holds a run of main the rewriter changed or moved, or when its list is not there
static int rt_code_rewritten(void *u, uint64_t addr, size_t len) {
    (void)u;
    if (!g_nor) return 1;
    if (addr >= g_or_hi || addr + len <= g_or_lo) return 0;
    int lo = 0, hi = g_nor;
    while (lo < hi) { int mid = (lo + hi) / 2; if (g_or[mid].a + g_or[mid].len <= addr) lo = mid + 1; else hi = mid; }
    return lo < g_nor && g_or[lo].a < addr + len;
}
// The game's heap for a module's get_heap_begin (Pokemon Sword's companion reads everything from the heap's start)
static void rt_heap_range(void *u, uint64_t *base, uint64_t *open_len) { (void)u; size_t n = 0; dm_guest_heap_range(base, &n); *open_len = n; }
// Room for a package's guest helper (rt/peanuts_helper.cpp): the end of the gap the loader leaves inaccessible between main's patch area and its base (a main with the patch
// area before the base, loader.c map_image: 16 or 32 MB, of which the patches take a few), else free pages just past the last module. A branch in main reaches either
static uint64_t rt_code_block_find(size_t size);
// The block is guest code for the host's thread pause from now on (hle_thread.c dm_guest_code_range): a game thread running the helper is stopped as one running the game
void dm_guest_code_range(uint64_t lo, uint64_t hi);
__attribute__((weak)) int dm_account_start_save_dir(const char *base, char *out, size_t cap);   // hle_account.c; absent in this file's PC test
static uint64_t rt_code_block(void *u, size_t size) {
    (void)u;
    uint64_t at = rt_code_block_find(size);
    if (at) dm_guest_code_range(at, at + size);
    return at;
}
static uint64_t rt_code_block_find(size_t size) {
    module_t *m = module_named("main");
    if (!m || !size || (size & 0x3FFF)) return 0;
    if (m->patch_off < 0 && (uint64_t)-m->patch_off >= m->patch_len + size) {
        uint8_t *a = (uint8_t *)m->base - size;
        if (mprotect(a, size, PROT_READ | PROT_WRITE)) return 0;
        memset(a, 0, size);
        return (uint64_t)(uintptr_t)a;
    }
    // Past the last module, not past main: the modules stand back to back (loader.c's module arena), so with main first and others after it there is no free page
    // behind main (Pokemon Sword: main, subsdk0, subsdk1, sdk; its companion's helper found no room). A branch reaches 128 MB either way: the search ends 120 MB from
    // main's base, and the helper's own linking refuses a branch that does not reach
    { extern void *dm_module_arena_tail(size_t size);   // loader.c: the pages it keeps after the last module, where nothing else can be in the way
      void *t = dm_module_arena_tail(size);
      if (t) { if ((uint64_t)(uintptr_t)t + size - (uint64_t)(uintptr_t)m->base <= (120u << 20)) return (uint64_t)(uintptr_t)t; } }
    uint64_t top = (uint64_t)(uintptr_t)m->base + m->size, lim = (uint64_t)(uintptr_t)m->base + (120u << 20);
    for (int i = 0; i < g_nmod; i++) if (!g_mod[i].nro && (uint64_t)(uintptr_t)g_mod[i].base + g_mod[i].size > top) top = (uint64_t)(uintptr_t)g_mod[i].base + g_mod[i].size;
    for (uint64_t at = (top + 0xFFFF) & ~0xFFFFull; at + size <= lim; at += 0x10000) {
        void *p = mmap((void *)(uintptr_t)at, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
        if (p == (void *)(uintptr_t)at) return at;
        if (p != MAP_FAILED) munmap(p, size);
    }
    return 0;
}
static int rt_range_has(uint64_t addr, size_t len) {   // [addr, addr+len) inside one cached readable range
    pthread_mutex_lock(&g_rd_mu);
    int lo = 0, hi = g_nrd - 1, ok = 0;
    while (lo <= hi) { int mid = (lo + hi) / 2; if (g_rd[mid].a > addr) hi = mid - 1; else if (g_rd[mid].b <= addr) lo = mid + 1; else { ok = len <= g_rd[mid].b - addr; break; } }
    pthread_mutex_unlock(&g_rd_mu);
    return ok;
}
// The cache says what was readable when /proc/self/maps was last read, up to 2 s ago. A range the game unmapped or made inaccessible since (a heap it shrank, a pool it
// freed) would fault the plain copy, and a fault of this thread ends the process. So the copy is guarded: the host's crash handler asks its fault hooks first (crash.c
// dm_fault_hook_register), and rt_read_fault takes a read fault of a thread that is inside rt_copy back to rt_copy, which answers "not readable"; the caller then asks
// the kernel (plat_mem_read), which says how it is now, and the cache is read again. Per thread (g_rd_guard), so a fault of any other thread, or of this one elsewhere, is
// not ours. _setjmp keeps no signal mask (no system call per read); the two signals are unblocked on the rare way back.
// Not covered: memory a package's module reads through a pointer it was handed (rt_mem_pointer, the module's get_read_pointer): those loads are the module's own
static __thread jmp_buf *g_rd_guard;
static _Atomic uint64_t g_rd_faults;
static int rt_read_fault(void *addr, int is_write, void *uc) {
    (void)addr; (void)uc;
    jmp_buf *j = g_rd_guard; if (!j || is_write) return 0;
    g_rd_guard = NULL; _longjmp(*j, 1);
}
static int rt_copy(void *dst, uint64_t addr, size_t len) {
    jmp_buf j;
    if (_setjmp(j)) {
        sigset_t m; sigemptyset(&m); sigaddset(&m, SIGSEGV); sigaddset(&m, SIGBUS); pthread_sigmask(SIG_UNBLOCK, &m, NULL);
        if (!atomic_fetch_add(&g_rd_faults, 1)) __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion: a range the read cache held was no longer readable (0x%" PRIx64 "): read through the kernel instead, the cache is read again (logged once)", addr);
        return 0;
    }
    g_rd_guard = &j; memcpy(dst, (const void *)(uintptr_t)addr, len); g_rd_guard = NULL;
    return 1;
}
// Read accounting for the 30 s line (2026-11-18: Breath of the Wild's companion took 46-50 % of a core, half of it in process_vm_readv, and nothing said which reads
// those were): reads answered by the cache, by the kernel, and refused by it, with the last few distinct pages of the last two kinds, named from /proc/self/maps
// when the line is printed (rt_read_stats). Relaxed counters; the samples are a ring written without a lock (a torn entry is only a wrong sample address)
static _Atomic uint64_t g_rdn_cached, g_rdn_kernel, g_rdn_refused, g_rdn_kbytes;
static _Atomic uint64_t g_rd_miss[8], g_rd_bad[8]; static _Atomic unsigned g_rd_miss_n, g_rd_bad_n;
static void rd_note(_Atomic uint64_t *ring, _Atomic unsigned *n, uint64_t addr) {
    uint64_t pg = addr & ~0xfffffull;   // (by MiB: neighbours are one sample)
    for (int i = 0; i < 8; i++) if (atomic_load_explicit(&ring[i], memory_order_relaxed) == pg) return;
    atomic_store_explicit(&ring[atomic_fetch_add_explicit(n, 1, memory_order_relaxed) & 7], pg, memory_order_relaxed);
}
static int rt_mem_read(void *u, uint64_t addr, void *buf, size_t len) {
    (void)u;
    if (rt_range_has(addr, len)) {
        if (rt_copy(buf, addr, len)) { rt_orig_apply(addr, buf, len); atomic_fetch_add_explicit(&g_rdn_cached, 1, memory_order_relaxed); return 1; }
        rt_ranges_refresh();   // the cache was wrong: read it again, and let the kernel answer this read
    }
    if (plat_mem_read(addr, buf, len)) { atomic_fetch_add_explicit(&g_rdn_refused, 1, memory_order_relaxed); rd_note(g_rd_bad, &g_rd_bad_n, addr); return 0; }
    atomic_fetch_add_explicit(&g_rdn_kernel, 1, memory_order_relaxed); atomic_fetch_add_explicit(&g_rdn_kbytes, len, memory_order_relaxed); rd_note(g_rd_miss, &g_rd_miss_n, addr);
    rt_orig_apply(addr, buf, len);
    // Readable but not in the cache: maybe a new mapping, so refresh, but at most every 500 ms. Some pages read memory that stays outside the cached ranges
    // (Animal Crossing's, every tick), and a /proc/self/maps parse per such read held the tick thread at 100 % (~140 ms a tick, the page at ~7 Hz).
    static _Atomic int64_t miss_at; int64_t now = mono_ns() / 1000000;
    if (now - atomic_load(&miss_at) >= 500) { atomic_store(&miss_at, now); rt_ranges_refresh(); }
    return 1;
}
// The read accounting as text for the tick thread's 30 s line; the counters start over. Sample addresses are named by their /proc/self/maps line (one pass, only when
// the kernel answered or refused reads in the period)
static void rt_read_stats(char *out, size_t n, unsigned ticks) {
    uint64_t c = atomic_exchange(&g_rdn_cached, 0), k = atomic_exchange(&g_rdn_kernel, 0), r = atomic_exchange(&g_rdn_refused, 0), kb = atomic_exchange(&g_rdn_kbytes, 0);
    double t = ticks ? (double)ticks : 1.0;
    int o = snprintf(out, n, "; reads a tick: %.0f from the cache (%d ranges), %.0f through the kernel (%.0f bytes), %.0f refused", c / t, g_nrd, k / t, kb / t, r / t);
    if (!(k + r) || o < 0 || (size_t)o >= n) return;
    uint64_t a[16]; int na = 0;
    for (int i = 0; i < 8; i++) { uint64_t v = atomic_exchange(&g_rd_miss[i], 0); if (v) a[na++] = v; }
    int nm = na;
    for (int i = 0; i < 8; i++) { uint64_t v = atomic_exchange(&g_rd_bad[i], 0); if (v) a[na++] = v; }
    char what[16][72]; for (int i = 0; i < na; i++) snprintf(what[i], sizeof what[i], "no mapping");
    FILE *f = fopen("/proc/self/maps", "r"); char line[512];
    while (f && fgets(line, sizeof line, f)) {
        uint64_t lo, hi; char perm[8]; int name = 0;
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s %*s %*s %*s %n", &lo, &hi, perm, &name) < 3) continue;
        for (int i = 0; i < na; i++) if (a[i] + 0x100000 > lo && a[i] < hi && !strcmp(what[i], "no mapping")) {
            size_t L = strlen(line); if (L && line[L - 1] == '\n') line[L - 1] = 0;
            snprintf(what[i], sizeof what[i], "%s %.48s", perm, name && line[name] ? line + name : "(anonymous, no name)");
        }
    }
    if (f) fclose(f);
    for (int i = 0; i < na && (size_t)o < n; i++)
        o += snprintf(out + o, n - (size_t)o, "%s0x%" PRIx64 " [%s]", i == 0 ? (nm ? "; kernel-read near " : "; refused near ") : i == nm ? "; refused near " : ", ", a[i], what[i]);
}
// A guest write: process_vm_writev (plat_mem_write) first. Reads of the cached ranges are plain memcpys, so a seccomp policy that refuses process_vm_* (ENOSYS / EPERM)
// was never noticed on the read side, but it fails every write - and a companion's writes (Octopath Traveler 0 hiding its party HUD and raising the command panel on the
// top screen) all go through here. The fallback copies through a pipe: write(2) the bytes in, read(2) them out at the guest address; the kernel copies to the user page and
// fails with EFAULT on an unmapped or read-only one instead of faulting, as process_vm_writev does. The first fallback and the first total failure are logged once
static int rt_pipe_write(uint64_t addr, const void *buf, size_t len) {
    int p[2]; if (pipe2(p, O_CLOEXEC)) return -1;
    size_t done = 0;
    while (done < len) {
        size_t k = len - done > 4096 ? 4096 : len - done;
        if (write(p[1], (const uint8_t *)buf + done, k) != (ssize_t)k) break;
        if (read(p[0], (void *)(uintptr_t)(addr + done), k) != (ssize_t)k) break;
        done += k;
    }
    close(p[0]); close(p[1]);
    return done == len ? 0 : -1;
}
static int rt_mem_write(void *u, uint64_t addr, const void *buf, size_t len) {
    (void)u;
    // The host's rule for its own writes of guest memory (dm.h dm_guest_write_prepare): a page the GPU's dirty tracking holds read-only is given back first and marked as
    // written, since a system call into it fails with EFAULT where the game's own store would fault and be let through. A companion's write into such a page (a value in
    // a block the game also hands the GPU) failed until now. The hooks take no lock: table lookups, atomics and mprotect (crash.c), also with the game's threads stopped
    dm_guest_write_prepare((void *)(uintptr_t)addr, len);
    if (!plat_mem_write(addr, buf, len)) return 1;
    static atomic_int via_pipe, failed;
    int e = errno;
    if (!rt_pipe_write(addr, buf, len)) {
        if (!atomic_exchange(&via_pipe, 1)) __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion guest writes: process_vm_writev failed (errno %d at 0x%" PRIx64 "), writing through a pipe instead", e, addr);
        return 1;
    }
    if (!atomic_exchange(&failed, 1)) __android_log_print(ANDROID_LOG_WARN, "peanuts", "companion guest write of %zu bytes at 0x%" PRIx64 " failed (vm errno %d, pipe errno %d)", len, addr, e, errno);
    return 0;
}
static uint8_t *rt_mem_pointer(void *u, uint64_t addr, size_t len) {
    (void)u; uint8_t b;
    if (!len) return NULL;
    if (g_nor && addr < g_or_hi && addr + len > (g_or_lo & ~(uint64_t)0xfff)) return NULL;   // main's rewritten part: read through rt_mem_read (rt_orig_apply)
    if (rt_range_has(addr, len)) return (uint8_t *)(uintptr_t)addr;
    if (addr + (len - 1) < addr) return NULL;   // the span wraps
    if (plat_mem_read(addr, &b, 1) || plat_mem_read(addr + len - 1, &b, 1)) return NULL;   // both ends read: the guest block is contiguous between
    return (uint8_t *)(uintptr_t)addr;
}
// The companion's virtual pad: the host's keyboard/touch/scripted source (dm_input_set), ORed with player 1's real controller by the HID service — the same semantics as
// Eden Duo's virtual gamepad. The runtime's button ids are nn::hid::NpadButton bit order (dm_input.h), sticks -1..1 → ±DM_STICK_MAX
#include "../host/dm_input.h"
static uint64_t g_vpad_btn; static int32_t g_vpad[4];
static pthread_mutex_t g_vpad_mu = PTHREAD_MUTEX_INITIALIZER;
static void rt_pad_button(void *u, size_t player, int button, int pressed) {
    (void)u; if (player != 0 || button < 0 || button > 15) return;
    pthread_mutex_lock(&g_vpad_mu);
    if (pressed) g_vpad_btn |= 1ull << button; else g_vpad_btn &= ~(1ull << button);
    dm_input_set(g_vpad_btn, g_vpad[0], g_vpad[1], g_vpad[2], g_vpad[3]);
    pthread_mutex_unlock(&g_vpad_mu);
}
static void rt_pad_stick(void *u, size_t player, int stick, float x, float y) {
    (void)u; if (player != 0 || stick < 0 || stick > 1) return;
    pthread_mutex_lock(&g_vpad_mu);
    g_vpad[stick * 2] = (int32_t)(x * DM_STICK_MAX); g_vpad[stick * 2 + 1] = (int32_t)(y * DM_STICK_MAX);
    dm_input_set(g_vpad_btn, g_vpad[0], g_vpad[1], g_vpad[2], g_vpad[3]);
    pthread_mutex_unlock(&g_vpad_mu);
}

static void rt_log(int level, const char *where, const char *msg) {
    __android_log_print(level >= 4 ? ANDROID_LOG_ERROR : level == 3 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "peanuts", "[rt] %s: %s", where ? where : "", msg ? msg : "");
}

// ---- haptics: the runtime's pulse (amplitude/ms/respect_system/kind), polled by dev.peanuts.Bridge.haptic() and played on the second screen's view/vibrator ----
static pthread_mutex_t g_haptic_mu = PTHREAD_MUTEX_INITIALIZER;
static float g_haptic_amp; static int g_haptic_ms, g_haptic_respect, g_haptic_kind, g_haptic_pending;
static void rt_haptic(void *u, float amplitude_0_1, uint32_t ms, int respect_system, int kind) {
    (void)u;
    pthread_mutex_lock(&g_haptic_mu);
    g_haptic_amp = amplitude_0_1; g_haptic_ms = (int)ms; g_haptic_respect = respect_system; g_haptic_kind = kind; g_haptic_pending = 1;   // latest wins: the runtime itself allows at most one per tick
    pthread_mutex_unlock(&g_haptic_mu);
}
int nxhost_peanuts_haptic(float *amplitude, int *ms, int *respect_system, int *kind) {
    int got;
    pthread_mutex_lock(&g_haptic_mu);
    got = g_haptic_pending;
    if (got) {
        if (amplitude) *amplitude = g_haptic_amp; if (ms) *ms = g_haptic_ms;
        if (respect_system) *respect_system = g_haptic_respect; if (kind) *kind = g_haptic_kind;
        g_haptic_pending = 0;
    }
    pthread_mutex_unlock(&g_haptic_mu);
    return got;
}

// ---- rt_state: whether a companion is on second-screen duty (dev.peanuts.Companion gates taking the second display on this) ----
static _Atomic int g_rt_state;   // 0 pending, 1 loaded, 2 no package, 3 pn_rt_create refused
int nxhost_peanuts_rt_state(void) { return atomic_load(&g_rt_state); }

// The running title, from the pack folder's name: the app's packs/<title ID>, or <title ID>-work/android-pack beside the dump. 0 when neither
static uint64_t rt_running_title(void) {
    const char *p = dm_module_dir(); if (!p) return 0;
    char buf[768]; snprintf(buf, sizeof buf, "%s", p);
    for (int up = 0; up < 2; up++) {
        size_t l = strlen(buf); while (l && buf[l - 1] == '/') buf[--l] = 0;
        char *slash = strrchr(buf, '/'); const char *base = slash ? slash + 1 : buf;
        if (strlen(base) >= 16) { char *end; uint64_t t = strtoull(base, &end, 16); if (end - base == 16 && (*end == 0 || *end == '-')) return t; }
        if (!slash) break;
        *slash = 0;
    }
    return 0;
}
// The companion package of the running title: in one of the roots a package can be installed in (beside the pack: <pack>/../peanuts, which is the app's packs/peanuts for a pack
// the app owns; and <title ID>-work/peanuts beside the dump, from DM_ROMFS's path), the one whose manifest names the title. Earlier builds took the first package in the first
// root, so another title's companion loaded (Three Houses' for Echoes of Wisdom)
//
// PEANUTS (companion-versions work package): a root now holds exactly one *active* package folder per package name (dev.peanuts.Installer keeps every other installed
// version archived outside these roots, under a dot-prefixed ".peanuts-versions" folder that readdir() already skips below via the leading-dot check) - so under normal
// operation at most one entry per root ever has a manifest matching the running title. The one unsafe window is a process kill mid-install: Installer briefly has a
// "<package>.new" (being extracted/verified) and/or a "<package>.old" (the previous active folder, about to be archived) sitting beside the real active folder, and a
// killed process can leave either of those non-dot-prefixed stray folders behind with a complete, title-matching manifest - at that point two "live-looking" folders for the
// same title exist in one root and which one readdir() returns first is filesystem order, i.e. random. Skip both known transient suffixes explicitly so a stray never
// competes with the real active folder.
//
// PEANUTS (round 3, "early recovery"): Installer.repair() only runs when the app itself next touches the package (opening a settings
// screen, starting another install) - a player who launches the game straight after a kill that landed between the two renames of a swap gets no companion at
// all, because the active folder is genuinely missing and ".old"/".new" are both skipped. Rather than wait for the app side, a second pass here falls back to
// a parked "<pkg>.old" (never ".new" - an extraction in progress has no business being loaded) when no root has an active folder for the title, logging that
// it did. This can only ever help: ".old" holds a version that was active and working a moment ago, found only once nothing else matched.
static int rt_pkgdir_suffix(const char *name, const char *suf) {
    size_t l = strlen(name), sl = strlen(suf);
    return l > sl && !strcmp(name + l - sl, suf);
}

// One root, one pass; allow_old lets the second (fallback-only) pass accept a "<pkg>.old" match, logging that it fell back. ".new" is never accepted, in
// either pass - it may still be being written.
static int rt_scan_root(const char *root, uint64_t want, int allow_old, char *dir, size_t n, uint64_t *title) {
    DIR *d = opendir(root);
    if (!d) return 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;   // skips dotfiles and the ".peanuts-versions" archive, never the active package
        if (rt_pkgdir_suffix(e->d_name, ".new")) continue;   // PEANUTS: never - an install/switch may still be writing into it
        int is_old = rt_pkgdir_suffix(e->d_name, ".old");
        if (is_old && !allow_old) continue;   // PEANUTS: a stray from a killed swap - the first pass skips it, the fallback pass (below) accepts it
        char manifest[768]; snprintf(manifest, sizeof manifest, "%s/%s/dualscreen/manifest.json", root, e->d_name);
        FILE *f = fopen(manifest, "r");
        if (!f) continue;
        char head[4096] = {0}; size_t got = fread(head, 1, sizeof head - 1, f); fclose(f); (void)got;
        uint64_t t = 0; const char *ts = strstr(head, "\"title_id\"");
        if (ts) { ts = strchr(ts + 10, '"'); if (ts) t = strtoull(ts + 1, NULL, 16); }
        if (want && t != want) { __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion package %s/%s is for title %016" PRIx64 ", not %016" PRIx64 ": passed over", root, e->d_name, t, want); continue; }
        *title = t;
        snprintf(dir, n, "%s/%s", root, e->d_name);
        if (is_old) __android_log_print(ANDROID_LOG_WARN, "peanuts", "no active companion package for title %016" PRIx64 "; falling back to the parked previous version %s (a swap was interrupted before it finished)", want, dir);
        closedir(d);
        return 1;
    }
    closedir(d);
    return 0;
}
static int rt_find_package(char *dir, size_t n, uint64_t *title) {
    uint64_t want = rt_running_title();
    char roots[3][512]; int nroots = 0;
    snprintf(roots[nroots++], sizeof roots[0], "%s/../peanuts", dm_module_dir());
    // A code-mod build in the chosen build folder lives at <title>/mod-<hash>/pack. Its companion is still installed beside the
    // normal pack, at <title>/peanuts, not inside the variant. App-owned packs already share <packs>/peanuts through the first root.
    const char *pack = dm_module_dir(), *end = strrchr(pack, '/');
    if (end && !strcmp(end + 1, "pack")) {
        const char *variant = end;
        while (variant > pack && variant[-1] != '/') variant--;
        if (end - variant == 16 && !strncmp(variant, "mod-", 4)
                && strspn(variant + 4, "0123456789abcdef") == 12) {
            snprintf(roots[nroots++], sizeof roots[0], "%s/../../peanuts", pack);
        }
    }
    const char *romfs = getenv("DM_ROMFS");
    if (romfs && want) {
        const char *nup = strstr(romfs, ".nup:"); const char *slash = NULL;
        if (!strncmp(romfs, "nsp:", 4)) { romfs += 4; nup = strchr(romfs, '|'); }   // a dump spec (peanuts_nsp.h): beside its first dump
        for (const char *q = romfs; q < (nup ? nup : romfs + strlen(romfs)); q++) if (*q == '/') slash = q;
        if (slash) snprintf(roots[nroots++], sizeof roots[0], "%.*s/%016" PRIX64 "-work/peanuts", (int)(slash - romfs), romfs, want);
    }
    for (int r = 0; r < nroots; r++) if (rt_scan_root(roots[r], want, /*allow_old=*/0, dir, n, title)) return 1;
    for (int r = 0; r < nroots; r++) if (rt_scan_root(roots[r], want, /*allow_old=*/1, dir, n, title)) return 1;   // PEANUTS: fall back to ".old" before giving up, see comment above
    return 0;
}

// ---- the game's threads stopped, for the runtime's writes "with the guest stopped" (rt/shim/shim.cpp PnShim::RunWithGuestStopped; rt/peanuts_helper.cpp's patches) ----
// The host's stop (hle_thread.c dm_guest_stop_all, built on the pause the game's own SetThreadActivity uses): every game thread is between two instructions of game code,
// inside an SVC it cannot return from, or in a native wait it cannot come back from, and the host's thread lock is held until the resume. What runs between stop and
// resume here is the runtime's check and stores: rt_mem_read / rt_mem_write, which take g_rd_mu (only ever held by this file's readers, never by a game thread), read
// /proc/self/maps, make system calls and go through the write-prepare hooks (lock-free). Nothing of it takes the host's thread lock or a lock a stopped thread can hold.
// The audio output, the audio renderer's thread and the GPU's workers are not game threads and keep running
int dm_guest_stop_all(uint64_t timeout_ns);
void dm_guest_resume_all(void);
int dm_guest_stopped_in(uint64_t lo, uint64_t hi);
static int rt_guest_stop(void *u, uint32_t timeout_ms) {   // 1 stopped, 0 not all stopped in time, -1 another stop was under way all that time (PnRtGuest)
    (void)u; int r = dm_guest_stop_all((uint64_t)timeout_ms * 1000000ull);
    return r == 0 ? 1 : r == -2 ? -1 : 0;
}
static void rt_guest_resume(void *u) { (void)u; dm_guest_resume_all(); }
static int rt_guest_stopped_in(void *u, uint64_t lo, uint64_t hi) { (void)u; return dm_guest_stopped_in(lo, hi); }

// ---- the controller, both ways (hle_hid.c dm_pad_raw / dm_pad_gate) ----
// To the runtime: player 1's state before the gate, once a tick (rt_thread). From the runtime: its gate over every state the game reads (fill): while a package's
// controller focus mode is on, the game gets a neutral pad and the companion moves its focus with the buttons instead. Runs on the game's reading threads: atomics only
static void rt_pad_gate(int slot, uint64_t *buttons, int32_t axes[4]) { pn_rt_pad_gate(slot, buttons, axes); }
// Read by the runtime whenever it asks, not sampled once before the tick: what the companion itself pressed or released for the game earlier in the same tick
// (rt_pad_button, dm_input_set) is in the answer, so its own release is not read back as a button still held
static void rt_pad_read(void *u, uint32_t *npad, uint64_t *buttons, int32_t axes[4]) { (void)u; dm_pad_raw(npad, buttons, axes); }

// ---- whether the page is shown, and the game's pause ----
// g_present_told: what the launcher said last (nxhost_peanuts_present), 1 before it says anything. g_page_asked_ms: when the page's window last asked for its size or a
// frame (it does every 33 ms while it shows, and nothing else asks). The tick thread puts the two together (rt_thread). g_rt_suspended: the game is suspended
// (peanuts_lifecycle, the launcher's thread): the tick thread makes no tick then, as Eden's timing stops with its emulation. All three are atomics
static _Atomic int g_present_told = 1, g_rt_suspended;
static _Atomic int g_panel_w, g_panel_h;   // the page view's size in pixels as the launcher told it (0: not told), any thread
static _Atomic int64_t g_page_asked_ms;
void peanuts_lifecycle(int suspended) {   // android/input_android.c suspend_apply: after the game was told to stop, and before it runs again
    atomic_store(&g_rt_suspended, suspended != 0);
    if (g_rt) pn_rt_lifecycle(g_rt, suspended);   // the mailbox epoch moves on at both, as on Eden
}

static void rt_start(void) {
    module_t *m = module_named("main");
    if (!m) { atomic_store(&g_rt_state, 2); return; }   // no "main" module loaded yet when g_nmod turned positive: rt_tick never retries (g_rt_tried), so the state must resolve here too
    char dir[768]; uint64_t title = 0;
    // Companions off for this launch (PN_COMPANION=off from the launcher: Eden Duo's dual screen mode "Off"): no package is looked for, so neither a page nor a load
    // plan's patches reach the game; the state reads "no companion", and the launcher leaves the second display alone
    { const char *e = getenv("PN_COMPANION");
      if (e && !strcmp(e, "off")) {
          pn_rt_set_companions_disabled(1);
          __android_log_print(ANDROID_LOG_INFO, "peanuts", "companions are off for this launch (PN_COMPANION=off)");
          atomic_store(&g_rt_state, 2);
          return;
      } }
    if (!rt_find_package(dir, sizeof dir, &title)) {
        __android_log_print(ANDROID_LOG_INFO, "peanuts", "no companion package beside %s", dm_module_dir());
        atomic_store(&g_rt_state, 2);
        return;
    }
    // The runtime's romfs root: the game's base RomFS folder (an update's folder is only its overlay: a few changed files). DM_ROMFS names the running version's folder,
    // <game>/updates/<v>/romfs or <game>/base/romfs. For a .nup ("<game>.nup:update/<v>/romfs") the container is read in place through the provider callbacks
    // (peanuts_nup.c): the named package's RomFS, the container's base package under it
    const char *romfs = getenv("DM_ROMFS");
    static char base_romfs[768];
    PnRtConfig c = {0};
    if (romfs && pn_nup_is_spec(romfs)) {
        char why[256] = "";
        const char *pe = getenv("DM_PROGRAM_ID");   // the title and build the host's rom: mount checks dumps against (hle_fs.c img_open)
        if ((g_nup = pn_nup_open_for(romfs, pe ? strtoull(pe, NULL, 16) : DM_GAME_PROGRAM_ID, dm_main_build(), why, sizeof why))) pn_nup_provider(g_nup, &c.romfs);
        else __android_log_print(ANDROID_LOG_WARN, "peanuts", "companion romfs: cannot open %s: %s (the \"romfs:\" assets stay blank)", romfs, why);
    } else if (romfs) {
        const char *u = strstr(romfs, "/updates/");
        struct stat st;
        if (u) snprintf(base_romfs, sizeof base_romfs, "%.*s/base/romfs", (int)(u - romfs), romfs);
        if (!u || stat(base_romfs, &st)) snprintf(base_romfs, sizeof base_romfs, "%s", romfs);   // a dump whose update folder holds the whole RomFS (no base/romfs)
    }
    c.package_dir = dir;
    c.romfs_dir = base_romfs[0] ? base_romfs : NULL;
    c.romfs_overlay_dirs = getenv("DM_ROMFS_OVERLAY");   // the mods' folders the game's rom: mount reads first (hle_fs.c): a companion sees the files the game sees (Luminescent Platinum's companion tells the mod by one of its files)
    c.title_id = title;
    // The whole NSO build id when the launcher hands it (PN_BUILD_ID, dev.peanuts.build.GameEnv: 64 hex digits; the host's own is the first 8 bytes): a package's native
    // module may list whole ids and refuse a prefix. Taken only when it begins with the host's
    const char *full = getenv("PN_BUILD_ID");
    c.build_id_hex = full && strlen(full) >= strlen(m->build_id) && !strncasecmp(full, m->build_id, strlen(m->build_id)) ? full : m->build_id;
    c.main_base = (uint64_t)(uintptr_t)m->base;
    rt_orig_load(c.main_base);
    c.main_size = m->size;
    // The second screen's size: the page view's real pixels once the launcher has told them (nxhost_peanuts_panel, also after the runtime started), 1240 x 1080 until then
    c.panel_w = (uint32_t)atomic_load(&g_panel_w); c.panel_h = (uint32_t)atomic_load(&g_panel_h);
    if (!c.panel_w || !c.panel_h) { c.panel_w = 1240; c.panel_h = 1080; }
    // R5: the runtime's own data (persisted flags, "user:" assets, and the staging folder a package's native module is copied to before dlopen) lives in the app's
    // private files folder (DM_AOC_DIR = <private files>/aoc/<title>, the one private path the launcher hands the game): shared storage is mounted noexec, so a
    // module staged there would not load. The title's save folder is DM_SAVE; the console's shared fonts come as .ttf here, not .bfttf, so "sysfont:" stays off
    static char data_dir[768];
    const char *aoc = getenv("DM_AOC_DIR");
    if (aoc) {
        const char *a = strstr(aoc, "/aoc/");
        if (a) snprintf(data_dir, sizeof data_dir, "%.*s/peanuts-rt", (int)(a - aoc), aoc);
    }
    if (!data_dir[0]) snprintf(data_dir, sizeof data_dir, "%s/peanuts-rt", getenv("DM_TEMP") ? getenv("DM_TEMP") : "/data/local/tmp");
    mkdir(data_dir, 0700);
    c.data_dir = data_dir;
    // The save folder of the user the game starts with (upstream's users, Settings › Users: the first user's is DM_SAVE, another's DM_SAVE_user_<uid>,
    // hle_account.c). A game that lets the player pick another user later, in its own selector, still shows the companion the starting user's save
    c.save_dir = getenv("DM_SAVE");
    { static char sd[1100]; size_t L = c.save_dir ? strlen(c.save_dir) : 0;
      if (L && dm_account_start_save_dir) {
          char base[1024]; while (L > 1 && c.save_dir[L - 1] == '/') L--;
          if (L < sizeof base) { memcpy(base, c.save_dir, L); base[L] = 0;
              if (!dm_account_start_save_dir(base, sd, sizeof sd)) { LOG("[peanuts] companion: the save folder is the starting user's, %s", sd); c.save_dir = sd; } } } }
    c.memory.read = rt_mem_read; c.memory.write = rt_mem_write; c.memory.pointer_at = rt_mem_pointer; c.memory.heap_range = rt_heap_range;
    c.pad.set_button = rt_pad_button; c.pad.set_stick = rt_pad_stick; c.pad.read = rt_pad_read;
    c.ui.publish_ui = rt_ui_publish; c.ui.publish_ui_partial = rt_ui_publish_partial;
    c.haptics.pulse = rt_haptic;
    c.helper.code_rewritten = rt_code_rewritten; c.helper.slot18_offset = (uint32_t)plat_slot_key_entry(PLAT_SLOT_K18); c.helper.code_block = rt_code_block; c.helper.text_end = m->text_end;   // a package's load plan: rt/peanuts_helper.cpp
    static char sites[900];
    if (dm_main_dir()) { snprintf(sites, sizeof sites, "%s/main.sites.tsv", dm_main_dir()); c.helper.sites_tsv = sites; }   // the rewriter's moved sequences, for what a load plan enters of main
    c.guest.stop = rt_guest_stop; c.guest.resume = rt_guest_resume; c.guest.stopped_in = rt_guest_stopped_in;
    c.log = rt_log; c.log_level = 2;
    { static int hooked; if (!hooked) { hooked = 1; dm_fault_hook_register(rt_read_fault); } }   // before the first read (rt_copy)
    g_rt = pn_rt_create(&c);
    if (g_rt) atomic_store(&dm_pad_gate, rt_pad_gate);   // from now on the game's pad reads pass the runtime's gate (a no-op until a package turns controller focus on)
    atomic_store(&g_rt_state, g_rt ? 1 : 3);
    __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion package %s (title %016" PRIx64 ", build %s, romfs %s): %s", dir, title, m->build_id,
                        c.romfs_dir ? c.romfs_dir : g_nup ? pn_nup_describe(g_nup) : "none", g_rt ? "loaded" : "not loaded");
}

// The runtime's publishes arrive here (its redraw thread), whole or as the changed rectangle; the rows are swizzled to the bytes Android's ARGB_8888 bitmaps hold (R, G, B, A)
// into the page buffer, so the page's poll copies as is and a moving marker costs its rectangle, not the whole page
static inline uint32_t swz(uint32_t p) { return (p & 0xff00ff00u) | ((p >> 16) & 0xffu) | ((p & 0xffu) << 16); }
static void rt_ui_rect(uint32_t w, uint32_t h, const uint32_t *px, int x0, int y0, int rw, int rh) {
    pthread_mutex_lock(&g_frame_mu);
    if (w != g_frame_w || h != g_frame_h) { free(g_frame); g_frame = malloc((size_t)w * h * 4); g_frame_w = w; g_frame_h = h; x0 = 0; y0 = 0; rw = (int)w; rh = (int)h; }
    if (g_frame) {
        if (x0 < 0) { rw += x0; x0 = 0; } if (y0 < 0) { rh += y0; y0 = 0; }
        if (x0 + rw > (int)w) rw = (int)w - x0; if (y0 + rh > (int)h) rh = (int)h - y0;
        for (int y = y0; y < y0 + rh; y++) for (int x = x0; x < x0 + rw; x++) g_frame[(size_t)y * w + x] = swz(px[(size_t)y * w + x]);
        g_frame_new = 1;
    }
    pthread_mutex_unlock(&g_frame_mu);
}
static void rt_ui_publish(void *u, uint32_t w, uint32_t h, const uint32_t *px) { (void)u; rt_ui_rect(w, h, px, 0, 0, (int)w, (int)h); }
static void rt_ui_publish_partial(void *u, uint32_t w, uint32_t h, const uint32_t *px, const int32_t r[4]) { (void)u; rt_ui_rect(w, h, px, r[0], r[1], r[2], r[3]); }

// The runtime ticks on a thread of its own at the rate it counts with, 60 a second (PN_RT_TICK_HZ; Eden: a 60 Hz timing event), whatever the game's frame rate is and
// whether or not it presents. It ticked on presents, at most every 25 ms (about 30 Hz, 20 Hz with no present): everything the runtime counts in ticks then took twice as
// long (a rule's every_ms, retries and timeouts, a module's get_tick), and two places of the lifted code had been corrected by hand for it.
// No tick while the game is suspended (Eden's timing is paused with its emulation). It ticked at 20 Hz then: one log shows peanuts-tick with 70.9 s of CPU in a pause of
// 412.7 s, sampling a game that could not change, nine tenths of the whole process's CPU in that time.
// While the page is not shown the runtime's own tick is short (no sampling, no page; a module only if it asked to tick while hidden).
// What a tick costs is logged every 30 s (count, mean, longest, and the stops of the game's threads): nothing here was measured on a device
static void *rt_thread(void *arg) {
    (void)arg;
    pthread_setname_np(pthread_self(), "peanuts-tick");
    // The runtime is made here, not on the thread that presents (it was: rt_tick called rt_start). Making it applies a package's load plan, which stops every game
    // thread to patch main; from the presenting thread the stop waited for the game's own thread, which was waiting for that very present, so every try timed out and
    // the patch went in beside the running game (seen on a device). This thread is none of the game's and none the game waits for
    rt_start();
    if (!g_rt) return NULL;
    const int64_t period = 1000000000 / PN_RT_TICK_HZ;
    int64_t next = mono_ns(), stat_at = next, busy = 0, longest = 0; unsigned ticks = 0, hidden = 0;
    int shown = 1;   // as pn_rt_create leaves it
    for (;;) {
        if (atomic_load(&g_rt_suspended)) {   // the game is paused or in the background: looked at ten times a second, and the timer starts again from the resume
            struct timespec ts = {0, 100000000}; nanosleep(&ts, NULL);
            next = mono_ns(); stat_at = next; busy = longest = 0; ticks = hidden = 0;
            continue;
        }
        next += period;
        int64_t now = mono_ns();
        // A tick longer than the period: none is made up for, and the next comes no sooner than 2 ms after this one ended. Ticks run back to back held a core at
        // 100 % for as long as each took over 16.7 ms (a module's sweep, a page that reads much), on a phone the game needs the cores of
        if (next < now + 2000000) next = now + 2000000;
        { struct timespec ts = {(time_t)(next / 1000000000), (long)(next % 1000000000)}; clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL); }
        if (atomic_load(&g_rt_suspended)) continue;
        int64_t t0 = mono_ns(), ms = t0 / 1000000;
        // The page is shown while the launcher has not said it is gone and its window keeps asking (it asks every 33 ms). Hidden: the runtime skips its sampling and the
        // page, ends controller focus and releases held fingers (pn_rt_set_present). A launcher that never tells is handled by the polls alone.
        // Only this thread tells the runtime (shown is what it was last told): the launcher's call stores its word and no more, so the two cannot disagree. It did
        // call pn_rt_set_present itself for a hide; one that came while the game was suspended left the runtime hidden and this thread believing "shown" for good
        int want = atomic_load(&g_present_told) && ms - atomic_load(&g_page_asked_ms) < 1500;
        if (want != shown) {
            shown = want; pn_rt_set_present(g_rt, shown);
            __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion: the page is %s", shown ? "shown" : "not shown: the runtime only keeps a module that asked for it ticking");
        }
        if (ms - atomic_load(&g_rd_at_ms) > 2000) rt_ranges_refresh();
        pn_rt_tick(g_rt);   // the frames come through the sink above
        int64_t t1 = mono_ns(), d = t1 - t0;
        busy += d; if (d > longest) longest = d; ticks++; hidden += !shown;
        if (t1 - stat_at >= 30000000000ll) {
            uint64_t stops = 0, failed = 0, stop_us = 0; pn_rt_stop_stats(&stops, &failed, &stop_us);
            char rds[1600]; rt_read_stats(rds, sizeof rds, ticks);
            LOG("[peanuts] companion ticks: %u in %.1f s (%u with the page not shown), %.2f ms each on average, longest %.2f ms, %.1f%% of one core; the game's threads stopped for writes %llu times since the start (%llu given up), longest %.2f ms%s",
                ticks, (t1 - stat_at) / 1e9, hidden, ticks ? busy / 1e6 / ticks : 0.0, longest / 1e6, 100.0 * busy / (double)(t1 - stat_at),
                (unsigned long long)stops, (unsigned long long)failed, stop_us / 1e3, rds);
            stat_at = t1; busy = longest = 0; ticks = hidden = 0;
        }
    }
    return NULL;
}

// A pack made before the rewriter wrote main.orig.bin gets the file at the game's start, on a thread of the game's screen (dev.peanuts.pack.PackUpgrade: seconds, from the
// game's own file and main.changed.tsv). Its unfinished file, main.orig.bin.part, is made before the host starts and is gone (renamed, or removed when the file cannot be
// made) when the work ends: while it is there and less than a minute old the runtime does not start, so rt_orig_load finds the file on the first start already. The game
// is not held, only the companion comes that much later. Looked at ten times a second at most; a file left by a start that was killed is older and holds nothing.
// The file's age is wall-clock time, so the wait is also bounded on the monotonic clock, a minute from the first look that found the file: a clock set back
// meanwhile, or a file dated in the future, cannot keep the companion away for longer
static int rt_orig_pending(void) {
    static int64_t last, since; static int pending;
    const char *d = dm_main_dir(); if (!d) return 0;
    struct timespec m; clock_gettime(CLOCK_MONOTONIC, &m);
    int64_t now = (int64_t)m.tv_sec * 1000 + m.tv_nsec / 1000000;
    if (last && now - last < 100) return pending;
    last = now;
    char p[900]; struct stat st; snprintf(p, sizeof p, "%s/main.orig.bin.part", d);
    int was = pending;
    pending = !stat(p, &st) && time(NULL) - st.st_mtime < 60;
    if (pending && !since) since = now;
    if (pending && now - since >= 60000) pending = 0;
    if (pending && !was) __android_log_print(ANDROID_LOG_INFO, "peanuts", "companion: main.orig.bin is being made for this pack: the runtime starts when it is there");
    return pending;
}

static void rt_tick(void) {   // the presenting thread: starts the runtime's thread once main is loaded (rt_thread makes the runtime and ticks it)
    if (g_rt_tried || g_nmod <= 0) return;
    if (rt_orig_pending()) return;
    g_rt_tried = 1;
    pthread_t th; pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&th, &at, rt_thread, NULL)) { __android_log_print(ANDROID_LOG_ERROR, "peanuts", "tick thread failed"); atomic_store(&g_rt_state, 3); }
    pthread_attr_destroy(&at);
}

void nxhost_peanuts_frame_size(int *w, int *h) {
    atomic_store(&g_page_asked_ms, mono_ns() / 1000000);   // the page's window asks every 33 ms while it shows (rt_thread: presence)
    pthread_mutex_lock(&g_frame_mu);
    if (w) *w = (int)g_frame_w; if (h) *h = (int)g_frame_h;
    pthread_mutex_unlock(&g_frame_mu);
}

// The newest frame as RGBA8 bytes (Android's ARGB_8888 bitmaps hold R, G, B, A in memory; the runtime's pixels are 0xAARRGGBB words)
static _Atomic unsigned g_frame_polls;   // times the launcher's second-screen page asked for a frame: CompanionPresentation polls it while it shows
unsigned peanuts_frame_polls(void) { return atomic_exchange(&g_frame_polls, 0); }   // since the last call (the present's statistics line, vka_present.c)
int nxhost_peanuts_frame(void *out_rgba, int w, int h) {
    int got = 0;
    atomic_fetch_add(&g_frame_polls, 1);
    atomic_store(&g_page_asked_ms, mono_ns() / 1000000);
    pthread_mutex_lock(&g_frame_mu);
    if (g_frame && g_frame_new && (uint32_t)w == g_frame_w && (uint32_t)h == g_frame_h) {
        memcpy(out_rgba, g_frame, (size_t)w * h * 4);   // already R, G, B, A bytes (swizzled on publish)
        g_frame_new = 0; got = 1;
    }
    pthread_mutex_unlock(&g_frame_mu);
    return got;
}

void nxhost_peanuts_frame_again(void) { pthread_mutex_lock(&g_frame_mu); if (g_frame) g_frame_new = 1; pthread_mutex_unlock(&g_frame_mu); }   // (peanuts.h)

void nxhost_peanuts_touch(int id, float x, float y, int down) { if (g_rt && x >= 0 && y >= 0) pn_rt_touch(g_rt, (uint32_t)id, (uint32_t)x, (uint32_t)y, down); }

// The page view's size in pixels (the launcher, when the view is laid out and when it changes). From then on touches are in these pixels (pn_rt_touch scales panel
// pixels to the page), where before they were in page pixels over the fixed 1240 x 1080
void nxhost_peanuts_panel(int w, int h) {
    if (w <= 0 || h <= 0) return;
    atomic_store(&g_panel_w, w); atomic_store(&g_panel_h, h);
    if (g_rt) pn_rt_set_panel(g_rt, (uint32_t)w, (uint32_t)h);
}

// The page's window is shown (1) or gone (0), told by the launcher (dev.peanuts.CompanionPresentation onStart / onStop, dev.peanuts.Companion away / back). The tick
// thread puts it together with the window's polls and tells the runtime (rt_thread); a finger that was down when the window went is released then
void nxhost_peanuts_present(int shown) { atomic_store(&g_present_told, shown != 0); }   // taken up by the tick thread at its next tick (within 17 ms; after a pause, at the first tick)

size_t nxhost_peanuts_snapshot(char *out, size_t n) {
    if (!out || !n) return 0;
    pthread_mutex_lock(&g_mu);
    size_t len = strlen(g_snapshot); if (len >= n) len = n - 1;
    memcpy(out, g_snapshot, len); out[len] = 0;
    pthread_mutex_unlock(&g_mu);
    return len;
}
