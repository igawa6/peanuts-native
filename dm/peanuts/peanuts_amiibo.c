// PEANUTS: amiibo for games (igawa6/peanuts-native#582), only for a game the player turned it on for. The nfc and nfp SDK families as hle_sdk16.c takes them
// over, with a virtual tag the player places from the app. hle_sdk16.c's own nfc and nfp block stays in the host unchanged: these take-overs sit in a table of
// their own (the section pnamiibo, not hle.h's swforce), and the loader asks peanuts_amiibo_force before its own table (dm/host/loader.c force_lookup, under
// PN_AMIIBO). Only when the app started the game with PN_AMIIBO=1 (dev.peanuts.amiibo.AmiiboSwitch: Settings › Users › amiibo, off by default, and the
// game's own amiibo switch) does the table answer; any other game gets upstream's block, as a host without this file.
//
// The take-over sits above the SDK's NFC service and above the amiibo encryption: the game asks nn::nfp for plain TagInfo, ModelInfo, CommonInfo, RegisterInfo
// and application area bytes, so a virtual tag needs no amiibo keys. The app (dev.peanuts.amiibo) hands over what it knows of an amiibo as text lines
// (nxhost_peanuts_amiibo_place): its ID (the 8 bytes a figure's data holds at 0x54), the tag's UID, the nickname, the owner's Mii (a CharInfo), the dates and
// the application areas it has kept for it. Areas are kept per access ID, as Ryujinx's virtual amiibo keeps them, so one amiibo serves every game without the
// "data of another game" step. A write (Flush, Create, Recreate) goes to the amiibo's folder from a helper thread, never from a game thread.
//
// The device: one NFC device on the connected controller, as before. States as the SDK's nn::nfp::DeviceState: 0 initialized, 1 searching, 2 tag found,
// 3 tag removed, 4 mounted. A tag placed while the game searches is found at once (the activate event is signalled); one placed in advance (from the quick
// menu) is found 0.3 s after the game's next StartDetection. After the game has unmounted the tag it is taken off on its own 1 s later, as a player lifts a
// figure off the stand (Breath of the Wild waits for that before it lets the next one in); a Mount within that second keeps it on.
// The amiibo settings applets end at once: NicknameAndOwnerSettings as done with the tag's own register info (a virtual amiibo is always registered), the
// eraser and the restorer as done with nothing to change (every game has an area of its own here).
// The app reads nxhost_peanuts_amiibo_status: whether amiibo is on for this game, whether the game is searching, since when, which search it is, and what lies
// on the stand.
#include "../host/hle.h"
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// ---------- the switch: the table the loader asks first ----------
// As hle.h's HLE_FORCE, into the section pnamiibo instead of swforce, so the two tables can hold the same names (the loader dies on a duplicate in one table)
#define PN_FORCE(mangled, why, ret, cname, ...)                                                    \
    static ret cname(__VA_ARGS__);                                                                \
    PLAT_TABLE(pnamiibo) static const hle_force_ent pn_amiibo_##cname = {mangled, (void *)cname, why}; \
    static ret cname(__VA_ARGS__)
extern const hle_force_ent __start_pnamiibo[] __attribute__((weak, visibility("hidden"))), __stop_pnamiibo[] __attribute__((weak, visibility("hidden")));
// PN_AMIIBO=1 in the game's environment, read once by the loader's first lookup: main.c has set the game's environment by then, and the modules are
// resolved before any game code runs. -1 until then: the app's calls can come earlier (its screen starts before the host's main), and they must not
// decide it, or the game would keep the answer of an environment that was not set yet
static _Atomic int g_enabled = -1;
static int enabled(void) { return atomic_load(&g_enabled) == 1; }
// dm/host/loader.c force_lookup: this file's take-over of name when amiibo is on for this game, else NULL (and the loader goes on to its own table)
void *peanuts_amiibo_force(const char *name) {
    if (atomic_load(&g_enabled) < 0) {
        const char *e = getenv("PN_AMIIBO");
        int on = e && !strcmp(e, "1");
        if (atomic_exchange(&g_enabled, on) < 0) LOG("amiibo: %s for this game (PN_AMIIBO)", on ? "on, Peanuts' stand takes nfc and nfp" : "off, hle_sdk16.c's nfc and nfp");
    }
    if (name[0] != '_' || strncmp(name, "_ZN2nn3nf", 9) || !enabled()) return NULL;   // nn::nfc and nn::nfp only: every other name goes by at once
    for (const hle_force_ent *e = __start_pnamiibo; e && e < __stop_pnamiibo; e++)
        if (!strcmp(e->name, name)) return e->fn;
    return NULL;
}

#define NFP_RES(d) ((Result)(115u | (uint32_t)(d) << 9))   // module 115 (nfp)
enum { R_WRONG_SIZE = 68, R_WRONG_STATE = 73, R_TAG_GONE = 97, R_AREA_NONE = 128, R_AREA_EXISTS = 168 };
enum { ST_INIT = 0, ST_SEARCH = 1, ST_FOUND = 2, ST_REMOVED = 3, ST_MOUNTED = 4 };
enum { MOUNT_ROM = 1, MOUNT_RAM = 2 };
enum { AREA = 0xd8, AREAS = 16, WRITES = 8, FIND_NS = 300000000, LIFT_NS = 1000000000 };

typedef struct { uint32_t id; uint8_t reserved[4]; } NfpDeviceHandle;
typedef struct __attribute__((packed)) { uint8_t uid[10]; uint8_t uid_len; uint8_t r1[0x15]; uint32_t protocol, type; uint8_t r2[0x30]; } NfpTagInfo;
typedef struct __attribute__((packed)) { uint8_t character[2], variant, type; uint16_t number; uint8_t series, r[0x39]; } NfpModelInfo;
typedef struct __attribute__((packed)) { uint16_t year; uint8_t month, day; } NfpDate;
typedef struct __attribute__((packed)) { NfpDate written; uint16_t writes; uint8_t version, r0; uint32_t area_size; uint8_t r[0x34]; } NfpCommonInfo;
typedef struct __attribute__((packed)) { uint8_t mii[0x58]; NfpDate created; char name[41]; uint8_t font_region, r[0x7a]; } NfpRegisterInfo;
typedef struct { uint32_t access; uint8_t r0[4]; const void *data; uint32_t size; uint8_t r[0x30]; } NfpAreaCreateInfo;   // nn::nfp::ApplicationAreaCreateInfo
_Static_assert(sizeof(NfpTagInfo) == 0x58, "nn::nfp::TagInfo is 0x58 bytes");
_Static_assert(sizeof(NfpModelInfo) == 0x40, "nn::nfp::ModelInfo is 0x40 bytes");
_Static_assert(sizeof(NfpCommonInfo) == 0x40, "nn::nfp::CommonInfo is 0x40 bytes");
_Static_assert(sizeof(NfpRegisterInfo) == 0x100, "nn::nfp::RegisterInfo is 0x100 bytes");
#define NFP_DEVICE_ID 0x4e465030u   // 'NFP0'

typedef struct { uint32_t access; uint8_t data[AREA]; } area_t;
typedef struct {
    char key[96], dir[512], title[17], name[41];
    uint8_t id[8], uid[7], mii[0x58];
    NfpDate created, written, today;
    uint16_t writes;
    area_t area[AREAS]; int nareas;
} tag_t;
typedef struct { char dir[512], title[17]; uint32_t access; uint8_t data[AREA]; uint16_t writes; NfpDate written; } write_t;

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cv;
static int g_lib, g_state, g_mount, g_open = -1, g_dirty, g_thread;   // g_thread: set once by start_once
static uint32_t g_search, g_reads, g_last_access;
static uint64_t g_search_at, g_find_at, g_lift_at, g_act, g_deact, g_avail;
static tag_t g_on, g_ready;           // the tag on the stand (state FOUND or MOUNTED), and one placed in advance
static int g_has_on, g_has_ready;
static uint8_t g_buf[AREA];           // the open area as the game has set it (written back by Flush)
static write_t g_w[WRITES]; static int g_nw;

uint32_t hle_hid_connected_npad(void);
static Result tag_gone(void) { return NFP_RES(R_TAG_GONE); }
static uint64_t mono_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }

// ---------- the app's description of an amiibo ----------
static int hexval(int c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1; }
static int unhex(const char *s, size_t len, uint8_t *out, size_t n) {   // exactly 2n hex digits
    if (len != 2 * n) return 0;
    for (size_t i = 0; i < n; i++) { int a = hexval(s[2 * i]), b = hexval(s[2 * i + 1]); if (a < 0 || b < 0) return 0; out[i] = (uint8_t)(a << 4 | b); }
    return 1;
}
static int date(const char *s, size_t len, NfpDate *d) {
    unsigned y, m, dd; char buf[16];
    if (len >= sizeof buf) return 0;
    memcpy(buf, s, len); buf[len] = 0;
    if (sscanf(buf, "%4u-%2u-%2u", &y, &m, &dd) != 3 || y < 2000 || y > 2099 || !m || m > 12 || !dd || dd > 31) return 0;
    d->year = (uint16_t)y; d->month = (uint8_t)m; d->day = (uint8_t)dd; return 1;
}
static void copy(char *dst, size_t cap, const char *s, size_t len) { if (len >= cap) len = cap - 1; memcpy(dst, s, len); dst[len] = 0; }
// Lines "key=value": key, id (16 hex), uid (14 hex), name (UTF-8, at most 40 bytes), mii (0x58 bytes in hex), created, written, today (YYYY-MM-DD), writes,
// dir (where the areas are written), title (the running game), area=<access ID, 8 hex>:<the area's bytes in hex>. 0 when id, uid, mii or dir is missing or bad
static int parse(const char *desc, tag_t *t) {
    memset(t, 0, sizeof *t);
    int need = 0;
    for (const char *p = desc; p && *p; ) {
        const char *end = strchr(p, '\n'); size_t n = end ? (size_t)(end - p) : strlen(p);
        const char *eq = memchr(p, '=', n);
        if (eq) {
            size_t k = (size_t)(eq - p), vl = n - k - 1; const char *v = eq + 1;
#define IS(name) (k == sizeof name - 1 && !memcmp(p, name, k))
            if (IS("key")) copy(t->key, sizeof t->key, v, vl);
            else if (IS("id")) need |= unhex(v, vl, t->id, 8) ? 1 : 0;
            else if (IS("uid")) need |= unhex(v, vl, t->uid, 7) ? 2 : 0;
            else if (IS("mii")) need |= unhex(v, vl, t->mii, sizeof t->mii) ? 4 : 0;
            else if (IS("dir")) { copy(t->dir, sizeof t->dir, v, vl); if (vl && vl < sizeof t->dir) need |= 8; }
            else if (IS("name")) copy(t->name, sizeof t->name, v, vl);
            else if (IS("title")) copy(t->title, sizeof t->title, v, vl);
            else if (IS("created")) date(v, vl, &t->created);
            else if (IS("written")) date(v, vl, &t->written);
            else if (IS("today")) date(v, vl, &t->today);
            else if (IS("writes")) { char b[8]; copy(b, sizeof b, v, vl); t->writes = (uint16_t)strtoul(b, NULL, 10); }
            else if (IS("area") && vl > 9 && v[8] == ':' && t->nareas < AREAS) {
                uint8_t a[4]; area_t *ar = &t->area[t->nareas];
                size_t dl = vl - 9;
                if (unhex(v, 8, a, 4) && dl % 2 == 0 && dl / 2 <= AREA && unhex(v + 9, dl, ar->data, dl / 2)) {
                    ar->access = (uint32_t)a[0] << 24 | (uint32_t)a[1] << 16 | (uint32_t)a[2] << 8 | a[3];
                    memset(ar->data + dl / 2, 0, AREA - dl / 2);
                    t->nareas++;
                }
            }
#undef IS
        }
        p = end ? end + 1 : NULL;
    }
    if (!t->today.year) t->today = t->written.year ? t->written : (NfpDate){2026, 1, 1};
    if (!t->created.year) t->created = t->today;
    if (!t->written.year) t->written = t->created;
    return need == 15;
}

// ---------- the helper thread: finds, lifts and writes ----------
static int area_of(const tag_t *t, uint32_t access) { for (int i = 0; i < t->nareas; i++) if (t->area[i].access == access) return i; return -1; }
static void queue_write(int idx) {   // under g_mu: the area idx of the tag on the stand, as it is now
    if (g_nw == WRITES) { memmove(g_w, g_w + 1, sizeof g_w[0] * (WRITES - 1)); g_nw--; LOG("[RED] amiibo: writes pile up; the oldest is dropped"); }
    write_t *w = &g_w[g_nw++];
    snprintf(w->dir, sizeof w->dir, "%s", g_on.dir); snprintf(w->title, sizeof w->title, "%s", g_on.title);
    w->access = g_on.area[idx].access; memcpy(w->data, g_on.area[idx].data, AREA); w->writes = g_on.writes; w->written = g_on.written;
    pthread_cond_signal(&g_cv);
}
static int put(const char *dir, const char *name, const void *data, size_t n) {
    char path[600], tmp[620];
    snprintf(path, sizeof path, "%s/%s", dir, name); snprintf(tmp, sizeof tmp, "%s.tmp", path);
    FILE *f = fopen(tmp, "wb");
    if (!f) { LOG("[RED] amiibo: cannot write %s: %s", tmp, strerror(errno)); return 0; }
    int ok = fwrite(data, 1, n, f) == n; ok &= fclose(f) == 0;
    if (ok && rename(tmp, path) == 0) return 1;
    LOG("[RED] amiibo: %s not written: %s", path, strerror(errno)); remove(tmp); return 0;
}
static void do_write(const write_t *w) {
    char name[64], text[96];
    snprintf(name, sizeof name, "area-%08x.bin", w->access);
    if (!put(w->dir, name, w->data, AREA)) return;
    snprintf(name, sizeof name, "area-%08x.title", w->access);
    put(w->dir, name, w->title, strlen(w->title));
    int n = snprintf(text, sizeof text, "writes=%u\nwritten=%04u-%02u-%02u\n", w->writes, w->written.year, w->written.month, w->written.day);
    put(w->dir, "common.txt", text, (size_t)n);
    LOG("amiibo: area %08x written (%u writes)", w->access, w->writes);
}
static void lift_locked(int *deact) {   // under g_mu: the tag leaves the stand
    if (!g_has_on) return;
    if (g_state == ST_MOUNTED && g_dirty) LOG("amiibo: taken off with unflushed changes; they are dropped, as a real tag's");
    g_has_on = 0; g_mount = 0; g_open = -1; g_dirty = 0; g_lift_at = 0;
    g_state = ST_REMOVED; *deact = 1;
}
static void *ticker(void *arg) {
    (void)arg;
    pthread_mutex_lock(&g_mu);
    for (;;) {
        uint64_t now = mono_ns(); int act = 0, deact = 0;
        if (g_find_at && now >= g_find_at) {
            g_find_at = 0;
            if (g_state == ST_SEARCH && g_has_ready) { g_on = g_ready; g_has_ready = 0; g_has_on = 1; g_state = ST_FOUND; act = 1; }
        }
        if (g_lift_at && now >= g_lift_at) { g_lift_at = 0; if (g_state == ST_FOUND) lift_locked(&deact); }
        if (g_nw) {
            write_t w = g_w[0]; memmove(g_w, g_w + 1, sizeof g_w[0] * (size_t)(g_nw - 1)); g_nw--;
            pthread_mutex_unlock(&g_mu); do_write(&w); pthread_mutex_lock(&g_mu);
        }
        uint64_t act_b = g_act, deact_b = g_deact;
        if (act || deact) {
            pthread_mutex_unlock(&g_mu);
            if (act) { hle_signal_system_event_binding(act_b); LOG("amiibo: placed in advance, found by the game's search"); }
            if (deact) { hle_signal_system_event_binding(deact_b); LOG("amiibo: taken off the stand after the game read it"); }
            pthread_mutex_lock(&g_mu);
            continue;
        }
        if (g_nw) continue;
        uint64_t next = 0;
        if (g_find_at) next = g_find_at;
        if (g_lift_at && (!next || g_lift_at < next)) next = g_lift_at;
        if (!next) { pthread_cond_wait(&g_cv, &g_mu); continue; }
        struct timespec ts = {(time_t)(next / 1000000000ull), (long)(next % 1000000000ull)};
        pthread_cond_timedwait(&g_cv, &g_mu, &ts);
    }
    return NULL;
}
static pthread_once_t g_once = PTHREAD_ONCE_INIT;
static pthread_cond_t g_lib_cv;   // the game's amiibo library opened or closed (nxhost_peanuts_amiibo_wait_lib)
static void start_once(void) {   // the condition variables on the monotonic clock, and the helper thread
    pthread_condattr_t a; pthread_condattr_init(&a); pthread_condattr_setclock(&a, CLOCK_MONOTONIC);
    pthread_cond_init(&g_cv, &a); pthread_cond_init(&g_lib_cv, &a); pthread_condattr_destroy(&a);
    pthread_t t; pthread_attr_t at; pthread_attr_init(&at); pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&t, &at, ticker, NULL) == 0) g_thread = 1; else LOG("[RED] amiibo: no helper thread; placed amiibo are not written back");
    pthread_attr_destroy(&at);
}
static void ensure(void) { pthread_once(&g_once, start_once); }   // before g_mu is taken by an entry that may signal g_cv

// ---------- the app's side (dev.peanuts.Bridge) ----------
// Puts an amiibo on the stand: found at once when the game searches (1), else kept for its next search (2); 0 when the description is not usable
int nxhost_peanuts_amiibo_place(const char *desc) {
    if (!enabled()) { LOG("amiibo: off for this game, or its modules not loaded yet; nothing placed"); return 0; }
    tag_t *t = malloc(sizeof *t);
    if (!t) return 0;
    if (!parse(desc, t)) { LOG("[RED] amiibo: the app's description is incomplete; nothing placed"); free(t); return 0; }
    int r, act = 0, deact = 0; uint64_t ab, db;
    ensure();
    pthread_mutex_lock(&g_mu);
    if (g_has_on) lift_locked(&deact);   // another figure on the stand goes first
    if (g_state == ST_SEARCH) {
        g_on = *t; g_has_on = 1; g_has_ready = 0; g_state = ST_FOUND; g_find_at = 0; act = 1; r = 1;
    } else { g_ready = *t; g_has_ready = 1; r = 2; }
    ab = g_act; db = g_deact;
    pthread_mutex_unlock(&g_mu);
    if (deact) hle_signal_system_event_binding(db);
    if (act) hle_signal_system_event_binding(ab);
    LOG("amiibo: %s placed (%s)", t->key[0] ? t->key : "?", r == 1 ? "the game was searching" : "ready for the game's next search");
    free(t);
    return r;
}
// Takes the amiibo off the stand, and the one placed in advance
void nxhost_peanuts_amiibo_remove(void) {
    int deact = 0; uint64_t db;
    pthread_mutex_lock(&g_mu);
    g_has_ready = 0; g_find_at = 0;
    lift_locked(&deact);
    db = g_deact;
    pthread_mutex_unlock(&g_mu);
    if (deact) hle_signal_system_event_binding(db);
}
// Sleeps until the game's amiibo library is not in state was any more (nfp's Initialize sets 1, Finalize 0), at most ms; the state then. -1 when amiibo is
// off for this game, -2 while the game's modules are not loaded yet (whether it is on is not known before: the caller asks again a moment later). The
// app's waiting thread (dev.peanuts.amiibo.AmiiboWatch) reads the stand only while the library is open: a game that never opens it costs a sleeping thread
int nxhost_peanuts_amiibo_wait_lib(int was, int ms) {
    if (atomic_load(&g_enabled) < 0) return -2;
    if (!enabled()) return -1;
    ensure();
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t end = (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec + (uint64_t)(ms > 0 ? ms : 0) * 1000000ull;
    ts.tv_sec = (time_t)(end / 1000000000ull); ts.tv_nsec = (long)(end % 1000000000ull);
    pthread_mutex_lock(&g_mu);
    while (g_lib == was) if (pthread_cond_timedwait(&g_lib_cv, &g_mu, &ts) == ETIMEDOUT) break;
    int lib = g_lib;
    pthread_mutex_unlock(&g_mu);
    return lib;
}
// "key=value" lines: enabled (1 when amiibo is on for this game; 0 when it is off, and the game has hle_sdk16.c's block, or while the game's modules are not
// loaded yet; with 0 nothing else here counts), lib (1 initialized),
// state (the device state above), search (how many searches so far), searching_ms (0 when not searching), on (the key on the stand), ready (the key placed
// in advance), reads (mounts of the tag on the stand), access (the last area the game opened, hex)
size_t nxhost_peanuts_amiibo_status(char *out, size_t cap) {
    pthread_mutex_lock(&g_mu);
    uint64_t ms = g_state == ST_SEARCH && g_search_at ? (mono_ns() - g_search_at) / 1000000ull : 0;
    int n = snprintf(out, cap, "enabled=%d\nlib=%d\nstate=%d\nsearch=%u\nsearching_ms=%llu\non=%s\nready=%s\nreads=%u\naccess=%08x\n", enabled(), g_lib, g_state, g_search,
                     (unsigned long long)ms, g_has_on ? g_on.key : "", g_has_ready ? g_ready.key : "", g_reads, g_last_access);
    pthread_mutex_unlock(&g_mu);
    return n < 0 ? 0 : (size_t)n;
}

// ---------- nfc: the NFC library beside nfp ----------
// A game can call nn::nfc itself next to nn::nfp (Hyrule Warriors: Definitive Edition, on entering amiibo). NFC is on: nfp answers with a working reader now.
static _Atomic int g_nfc_state;
PN_FORCE("_ZN2nn3nfc10InitializeEv", "NFC library init; amiibo come from the app (peanuts_amiibo.c)", void, h_nfc_Init, void) { atomic_store(&g_nfc_state, 1); }
PN_FORCE("_ZN2nn3nfc8FinalizeEv", "NFC library finalize; no service session to close", void, h_nfc_Fini, void) { atomic_store(&g_nfc_state, 0); }
PN_FORCE("_ZN2nn3nfc8GetStateEv", "NFC library state: uninitialized (0) / initialized (1)", int, h_nfc_State, void) { return atomic_load(&g_nfc_state); }
PN_FORCE("_ZN2nn3nfc12IsNfcEnabledEv", "NFC switched on (the app's virtual amiibo stand, peanuts_amiibo.c)", unsigned, h_nfc_Enabled, void) { return 1; }

// ---------- nfp: amiibo ----------
static void signal_after(int act, int deact) {   // outside g_mu
    pthread_mutex_lock(&g_mu); uint64_t ab = g_act, db = g_deact; pthread_mutex_unlock(&g_mu);
    if (deact) hle_signal_system_event_binding(db);
    if (act) hle_signal_system_event_binding(ab);
}
PN_FORCE("_ZN2nn3nfp10InitializeEv", "amiibo library init; one NFC device, amiibo placed from the app", void, h_nfp_Init, void) {
    ensure();
    pthread_mutex_lock(&g_mu); g_lib = 1; pthread_cond_broadcast(&g_lib_cv); pthread_mutex_unlock(&g_mu);
    LOG("nfp::Initialize: one NFC device; amiibo are placed from the app");
}
PN_FORCE("_ZN2nn3nfp29AttachAvailabilityChangeEventEPNS_2os15SystemEventTypeE", "NFC availability change event; the device never goes away", void, h_nfp_AttachAvail, void *ev) {
    hle_bind_system_event(ev, 0);
    pthread_mutex_lock(&g_mu); g_avail = hle_system_event_binding(ev); pthread_mutex_unlock(&g_mu);
}
PN_FORCE("_ZN2nn3nfp11ListDevicesEPNS0_12DeviceHandleEPii", "NFC device list: one device", Result, h_nfp_List, NfpDeviceHandle *out, int *n, int max) {
    *n = 0; if (max >= 1) { memset(&out[0], 0, sizeof out[0]); out[0].id = NFP_DEVICE_ID; *n = 1; }
    return RES_OK;
}
PN_FORCE("_ZN2nn3nfp9GetNpadIdEPjRKNS0_12DeviceHandleE", "controller the NFC device is on: the one connected now", Result, h_nfp_NpadId, uint32_t *out, const NfpDeviceHandle *h) { (void)h; *out = hle_hid_connected_npad(); return RES_OK; }
PN_FORCE("_ZN2nn3nfp14StartDetectionERKNS0_12DeviceHandleE", "start tag detection: the app sees the search and may place an amiibo", Result, h_nfp_Start, const NfpDeviceHandle *h) {
    (void)h; ensure();
    pthread_mutex_lock(&g_mu);
    if (g_state != ST_FOUND && g_state != ST_MOUNTED) {
        g_state = ST_SEARCH; g_search++; g_search_at = mono_ns();
        if (g_has_ready) { g_find_at = g_search_at + FIND_NS; pthread_cond_signal(&g_cv); }
    }
    pthread_mutex_unlock(&g_mu);
    return RES_OK;
}
PN_FORCE("_ZN2nn3nfp13StopDetectionERKNS0_12DeviceHandleE", "stop tag detection; a tag on the stand is taken off", Result, h_nfp_Stop, const NfpDeviceHandle *h) {
    (void)h; int deact = 0;
    pthread_mutex_lock(&g_mu);
    lift_locked(&deact);
    g_state = ST_INIT; g_search_at = 0; g_find_at = 0;
    pthread_mutex_unlock(&g_mu);
    signal_after(0, deact);
    return RES_OK;
}
PN_FORCE("_ZN2nn3nfp14GetDeviceStateERKNS0_12DeviceHandleE", "device state: initialized / searching / tag found / removed / mounted", int, h_nfp_State, const NfpDeviceHandle *h) {
    (void)h; pthread_mutex_lock(&g_mu); int s = g_state; pthread_mutex_unlock(&g_mu); return s;
}
PN_FORCE("_ZN2nn3nfp10GetTagInfoEPNS0_7TagInfoERKNS0_12DeviceHandleE", "tag info of the amiibo on the stand (its UID)", Result, h_nfp_TagInfo, void *out, const NfpDeviceHandle *h) {
    (void)h; Result r = RES_OK;
    pthread_mutex_lock(&g_mu);
    if (!g_has_on) r = tag_gone();
    else {
        NfpTagInfo t; memset(&t, 0, sizeof t);
        memcpy(t.uid, g_on.uid, 7); t.uid_len = 7; t.protocol = 1; t.type = 2;   // NFC-A, type 2 (NTAG215)
        memcpy(out, &t, sizeof t);
    }
    pthread_mutex_unlock(&g_mu);
    return r;
}
static Result mount(int target) {
    Result r = RES_OK;
    pthread_mutex_lock(&g_mu);
    if (!g_has_on) r = tag_gone();
    else if (g_state != ST_FOUND && g_state != ST_MOUNTED) r = NFP_RES(R_WRONG_STATE);
    else { g_state = ST_MOUNTED; g_mount = target ? target : MOUNT_ROM | MOUNT_RAM; g_open = -1; g_dirty = 0; g_lift_at = 0; g_reads++; }
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp5MountERKNS0_12DeviceHandleENS0_9ModelTypeENS0_11MountTargetE", "mount the amiibo on the stand", Result, h_nfp_Mount, const NfpDeviceHandle *h, int model, int target) { (void)h; (void)model; return mount(target); }
PN_FORCE("_ZN2nn3nfp5MountERKNS0_12DeviceHandleENS0_9ModelTypeE", "mount the amiibo on the stand (two arguments)", Result, h_nfp_Mount2, const NfpDeviceHandle *h, int model) { (void)h; (void)model; return mount(MOUNT_ROM | MOUNT_RAM); }
PN_FORCE("_ZN2nn3nfp7UnmountERKNS0_12DeviceHandleE", "unmount; the tag is lifted off the stand a second later", Result, h_nfp_Unmount, const NfpDeviceHandle *h) {
    (void)h; Result r = RES_OK; ensure();
    pthread_mutex_lock(&g_mu);
    if (!g_has_on) r = tag_gone();
    else if (g_state != ST_MOUNTED) r = NFP_RES(R_WRONG_STATE);
    else { g_state = ST_FOUND; g_mount = 0; g_open = -1; g_dirty = 0; g_lift_at = mono_ns() + LIFT_NS; pthread_cond_signal(&g_cv); }
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp19AttachActivateEventEPNS_2os15SystemEventTypeERKNS0_12DeviceHandleE", "tag placed event: signalled when an amiibo is placed", Result, h_nfp_AttachAct, void *ev, const NfpDeviceHandle *h) {
    (void)h; hle_bind_system_event(ev, 0);
    pthread_mutex_lock(&g_mu); g_act = hle_system_event_binding(ev); pthread_mutex_unlock(&g_mu);
    return RES_OK;
}
PN_FORCE("_ZN2nn3nfp21AttachDeactivateEventEPNS_2os15SystemEventTypeERKNS0_12DeviceHandleE", "tag removed event: signalled when the amiibo leaves the stand", Result, h_nfp_AttachDeact, void *ev, const NfpDeviceHandle *h) {
    (void)h; hle_bind_system_event(ev, 0);
    pthread_mutex_lock(&g_mu); g_deact = hle_system_event_binding(ev); pthread_mutex_unlock(&g_mu);
    return RES_OK;
}
static Result mounted(int need) {   // under g_mu
    if (!g_has_on) return tag_gone();
    if (g_state != ST_MOUNTED || (g_mount & need) != need) return NFP_RES(R_WRONG_STATE);
    return RES_OK;
}
PN_FORCE("_ZN2nn3nfp12GetModelInfoEPNS0_9ModelInfoERKNS0_12DeviceHandleE", "model info from the amiibo's ID", Result, h_nfp_ModelInfo, void *out, const NfpDeviceHandle *h) {
    (void)h;
    pthread_mutex_lock(&g_mu);
    Result r = mounted(0);
    if (r == RES_OK) {
        NfpModelInfo m; memset(&m, 0, sizeof m);
        m.character[0] = g_on.id[0]; m.character[1] = g_on.id[1];   // the game and character ID as the ID holds it (big-endian bytes)
        m.variant = g_on.id[2]; m.type = g_on.id[3]; m.number = (uint16_t)(g_on.id[4] << 8 | g_on.id[5]); m.series = g_on.id[6];
        memcpy(out, &m, sizeof m);
    }
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp13GetCommonInfoEPNS0_10CommonInfoERKNS0_12DeviceHandleE", "common info: last write, write count, area size", Result, h_nfp_CommonInfo, void *out, const NfpDeviceHandle *h) {
    (void)h;
    pthread_mutex_lock(&g_mu);
    Result r = mounted(MOUNT_RAM);
    if (r == RES_OK) {
        NfpCommonInfo c; memset(&c, 0, sizeof c);
        c.written = g_on.written; c.writes = g_on.writes; c.area_size = AREA;
        memcpy(out, &c, sizeof c);
    }
    pthread_mutex_unlock(&g_mu);
    return r;
}
static void register_info(NfpRegisterInfo *ri) {   // under g_mu
    memset(ri, 0, sizeof *ri);
    memcpy(ri->mii, g_on.mii, sizeof ri->mii); ri->created = g_on.created;
    snprintf(ri->name, sizeof ri->name, "%s", g_on.name);
}
PN_FORCE("_ZN2nn3nfp15GetRegisterInfoEPNS0_12RegisterInfoERKNS0_12DeviceHandleE", "register info: the player's Mii as owner, the nickname", Result, h_nfp_RegInfo, void *out, const NfpDeviceHandle *h) {
    (void)h;
    pthread_mutex_lock(&g_mu);
    Result r = mounted(MOUNT_RAM);
    if (r == RES_OK) { NfpRegisterInfo ri; register_info(&ri); memcpy(out, &ri, sizeof ri); }
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp8GetStateEv", "amiibo library state: initialized (1) after Initialize", int, h_nfp_LibState, void) { pthread_mutex_lock(&g_mu); int l = g_lib; pthread_mutex_unlock(&g_mu); return l; }
PN_FORCE("_ZN2nn3nfp8FinalizeEv", "amiibo library finalize: the stand is cleared", void, h_nfp_Fini, void) {
    int deact = 0; ensure();
    pthread_mutex_lock(&g_mu);
    lift_locked(&deact); g_state = ST_INIT; g_lib = 0; g_search_at = 0; g_find_at = 0;
    pthread_cond_broadcast(&g_lib_cv);
    pthread_mutex_unlock(&g_mu);
    (void)deact;   // the game's events go with the library
}
PN_FORCE("_ZN2nn3nfp5FlushERKNS0_12DeviceHandleE", "write the open area back (the app's folder for the amiibo)", Result, h_nfp_Flush, const NfpDeviceHandle *h) {
    (void)h; ensure();
    pthread_mutex_lock(&g_mu);
    Result r = mounted(MOUNT_RAM);
    if (r == RES_OK && g_open >= 0 && g_dirty) {
        memcpy(g_on.area[g_open].data, g_buf, AREA);
        g_on.writes++; g_on.written = g_on.today; g_dirty = 0;
        queue_write(g_open);
    }
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp19OpenApplicationAreaERKNS0_12DeviceHandleEj", "open this game's area on the amiibo", Result, h_nfp_OpenArea, const NfpDeviceHandle *h, uint32_t id) {
    (void)h;
    pthread_mutex_lock(&g_mu);
    Result r = mounted(MOUNT_RAM);
    if (r == RES_OK) {
        int i = area_of(&g_on, id);
        g_last_access = id;
        if (i < 0) r = NFP_RES(R_AREA_NONE);
        else { g_open = i; memcpy(g_buf, g_on.area[i].data, AREA); g_dirty = 0; }
    }
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp18GetApplicationAreaEPvPmRKNS0_12DeviceHandleEm", "read the open area", Result, h_nfp_GetArea, void *buf, uint64_t *n, const NfpDeviceHandle *h, uint64_t sz) {
    (void)h;
    pthread_mutex_lock(&g_mu);
    Result r = mounted(MOUNT_RAM);
    if (r == RES_OK && g_open < 0) r = NFP_RES(R_AREA_NONE);
    if (r == RES_OK) { uint64_t k = sz < AREA ? sz : AREA; memcpy(buf, g_buf, k); if (n) *n = k; }
    else if (n) *n = 0;
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp18SetApplicationAreaERKNS0_12DeviceHandleEPKvm", "change the open area (kept until Flush)", Result, h_nfp_SetArea, const NfpDeviceHandle *h, const void *buf, uint64_t sz) {
    (void)h;
    pthread_mutex_lock(&g_mu);
    Result r = mounted(MOUNT_RAM);
    if (r == RES_OK && g_open < 0) r = NFP_RES(R_AREA_NONE);
    if (r == RES_OK && sz > AREA) r = NFP_RES(R_WRONG_SIZE);
    if (r == RES_OK) { memset(g_buf, 0, AREA); memcpy(g_buf, buf, sz); g_dirty = 1; }
    pthread_mutex_unlock(&g_mu);
    return r;
}
static Result create(const NfpAreaCreateInfo *ci, int again) {
    ensure();
    pthread_mutex_lock(&g_mu);
    Result r = mounted(MOUNT_RAM);
    int i = r == RES_OK ? area_of(&g_on, ci->access) : -1;
    if (r == RES_OK && ci->size > AREA) r = NFP_RES(R_WRONG_SIZE);
    if (r == RES_OK && i >= 0 && !again) r = NFP_RES(R_AREA_EXISTS);
    if (r == RES_OK && i < 0) {
        if (g_on.nareas == AREAS) { memmove(g_on.area, g_on.area + 1, sizeof g_on.area[0] * (AREAS - 1)); g_on.nareas--; if (g_open > 0) g_open--; else g_open = -1; }
        i = g_on.nareas++; g_on.area[i].access = ci->access;
    }
    if (r == RES_OK) {
        memset(g_on.area[i].data, 0, AREA);
        if (ci->data && ci->size) memcpy(g_on.area[i].data, ci->data, ci->size);
        g_on.writes++; g_on.written = g_on.today; g_last_access = ci->access;
        g_open = i; memcpy(g_buf, g_on.area[i].data, AREA); g_dirty = 0;
        queue_write(i);
    }
    pthread_mutex_unlock(&g_mu);
    return r;
}
PN_FORCE("_ZN2nn3nfp21CreateApplicationAreaERKNS0_12DeviceHandleERKNS0_25ApplicationAreaCreateInfoE", "create this game's area on the amiibo (written at once)", Result, h_nfp_CreateArea, const NfpDeviceHandle *h, const void *info) { (void)h; return create(info, 0); }
PN_FORCE("_ZN2nn3nfp23RecreateApplicationAreaERKNS0_12DeviceHandleERKNS0_25ApplicationAreaCreateInfoE", "create this game's area again (written at once)", Result, h_nfp_RecreateArea, const NfpDeviceHandle *h, const void *info) { (void)h; return create(info, 1); }
static Result applet_done(NfpDeviceHandle *h) {
    pthread_mutex_lock(&g_mu); Result r = g_has_on ? RES_OK : tag_gone(); pthread_mutex_unlock(&g_mu);
    if (h) { memset(h, 0, sizeof *h); h->id = NFP_DEVICE_ID; }
    return r;
}
PN_FORCE("_ZN2nn3nfp13StartRestorerEPNS0_12DeviceHandleERKNS0_24AmiiboSettingsStartParamERKNS0_7TagInfoE", "amiibo restorer applet: nothing to restore", Result, h_nfp_Restorer, NfpDeviceHandle *h, const void *p, const void *tag) { DM_APPLET("amiibo"); (void)p; (void)tag; return applet_done(h); }
PN_FORCE("_ZN2nn3nfp19StartGameDataEraserEPNS0_12DeviceHandleERKNS0_24AmiiboSettingsStartParamERKNS0_7TagInfoE", "amiibo game data eraser applet: every game has its own area here", Result, h_nfp_Eraser, NfpDeviceHandle *h, const void *p, const void *tag) { DM_APPLET("amiibo"); (void)p; (void)tag; return applet_done(h); }
static Result nickname(NfpDeviceHandle *h, uint8_t *registered, void *reg) {
    Result r = applet_done(h);
    pthread_mutex_lock(&g_mu);
    if (r == RES_OK && g_has_on && reg) { NfpRegisterInfo ri; register_info(&ri); memcpy(reg, &ri, sizeof ri); }
    pthread_mutex_unlock(&g_mu);
    if (registered) *registered = r == RES_OK;
    return r;
}
PN_FORCE("_ZN2nn3nfp29StartNicknameAndOwnerSettingsEPNS0_12DeviceHandleEPbPNS0_12RegisterInfoERKNS0_24AmiiboSettingsStartParamERKNS0_7TagInfoE", "amiibo nickname and owner settings applet: already registered (the player's Mii)", Result, h_nfp_Nick, NfpDeviceHandle *h, uint8_t *registered, void *reg, const void *p, const void *tag) { DM_APPLET("amiibo"); (void)p; (void)tag; return nickname(h, registered, reg); }
PN_FORCE("_ZN2nn3nfp29StartNicknameAndOwnerSettingsEPNS0_12DeviceHandleEPbPNS0_12RegisterInfoERKNS0_24AmiiboSettingsStartParamERKNS0_7TagInfoERKS4_", "amiibo nickname and owner settings applet (with the old register info): already registered", Result, h_nfp_Nick2, NfpDeviceHandle *h, uint8_t *registered, void *reg, const void *p, const void *tag, const void *old) { DM_APPLET("amiibo"); (void)p; (void)tag; (void)old; return nickname(h, registered, reg); }

#if defined(__ANDROID__)
// ---------- dev.peanuts.Bridge (in the host, as peanuts_services.c's entry points) ----------
#include <jni.h>
JNIEXPORT jstring JNICALL Java_dev_peanuts_Bridge_amiiboStatus(JNIEnv *env, jclass cls) {
    (void)cls; char buf[512];
    nxhost_peanuts_amiibo_status(buf, sizeof buf);
    return (*env)->NewStringUTF(env, buf);
}
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_amiiboPlace(JNIEnv *env, jclass cls, jstring desc) {
    (void)cls;
    if (!desc) return 0;
    const char *s = (*env)->GetStringUTFChars(env, desc, NULL);
    if (!s) return 0;
    int r = nxhost_peanuts_amiibo_place(s);
    (*env)->ReleaseStringUTFChars(env, desc, s);
    return r;
}
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_amiiboRemove(JNIEnv *env, jclass cls) { (void)env; (void)cls; nxhost_peanuts_amiibo_remove(); }
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_amiiboWaitLib(JNIEnv *env, jclass cls, jint was, jint ms) { (void)env; (void)cls; return nxhost_peanuts_amiibo_wait_lib(was, ms); }
#endif
