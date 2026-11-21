// Peanuts Native: what the Android host gives the companion runtime's smaller services (rt/shim/shim_services.cpp calls these through weak references):
//   pn_host_power        the battery level and charging state, for a package's "@device.battery_pct" / "@device.charging"
//   pn_host_perf_totals  running frame totals, for "@perf.fps" / "@perf.frametime_ms"
//   pn_host_aoc_open     an add-on content index of DM_AOC_DIR that is a .nup or a dump, as the runtime's RomFS callbacks, for "aoc:"
//   Bridge.opMode        the console mode the game sees now, for the launcher's stats page (dev.peanuts.ui.StatsView)
// Nothing here runs on the game's frame path: the runtime's tick thread asks about once a second (power), twice a second (totals) or once (add-on content).
#if defined(__ANDROID__)
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "peanuts_frametime.h"   // pn_ft_read: whether the game has shown a frame (Bridge.opMode)
#include "peanuts_nup.h"
#include "peanuts_services.h"   // the parts that only compute (tested on a PC)

// ---- battery ----------------------------------------------------------------------------------------------------------------------------------------------
// The app knows the battery from Android's sticky broadcast and pushes it (Bridge.battery, below): level 0..100, or -1 for a device without one. Until it has pushed
// (or when it stopped for a minute) the kernel's power supply files are read, where the system lets an app read them. The answer is the emulator's on Android:
// percent = level / scale, charging = the status is "charging" or "full".
static _Atomic int g_bat_pct = -2, g_bat_chg;   // -2: never pushed
static _Atomic uint64_t g_bat_t;
static uint64_t mono_ns(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec; }

void pn_host_battery_set(int percent, int charging) {
    atomic_store(&g_bat_pct, percent < 0 ? -1 : percent > 100 ? 100 : percent); atomic_store(&g_bat_chg, charging != 0); atomic_store(&g_bat_t, mono_ns());
}
JNIEXPORT void JNICALL Java_dev_peanuts_Bridge_battery(JNIEnv *env, jclass cls, jint percent, jboolean charging) { (void)env; (void)cls; pn_host_battery_set(percent, charging); }

// ---- console mode -------------------------------------------------------------------------------------------------------------------------------------------
// What oe::GetOperationMode answers the game now (dm/host/hle_oe.c): 1 docked (console), 0 handheld. It changes while the game runs when the player picks the
// other Console mode in the in-game panel (nxhost_graphics_mode_set), so the stats page asks it rather than the setting.
// -1 until the game has shown a frame: dm_opmode() keeps the first value it reads, and in the generic host its default is the runtime profile's (g_profile,
// loaded at the start of nxhost_main). The stats page asks from the moment the game's window opens, before that (during the shader preparing step too): an
// early ask fixed the mode at the zeroed profile's 0, so a game set to Docked ran handheld (Kirby and the Forgotten Land on the AYN Thor, 2026-11-21). Once a
// frame is shown the profile is long loaded and the game has asked itself, so this ask cannot change what it sees.
int dm_opmode(void);
JNIEXPORT jint JNICALL Java_dev_peanuts_Bridge_opMode(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    float last;
    return pn_ft_read(&last, 1) > 0 ? dm_opmode() : -1;
}

static int read_line(const char *path, char *out, size_t n) {
    FILE *f = fopen(path, "re"); if (!f) return 0;
    int ok = fgets(out, (int)n, f) != NULL; fclose(f);
    if (ok) out[strcspn(out, "\r\n")] = 0;
    return ok;
}
int pn_host_power(int *percent, int *charging) {
    int p = atomic_load(&g_bat_pct);
    if (pn_battery_pushed_fresh(p, atomic_load(&g_bat_t), mono_ns())) { *percent = p; *charging = atomic_load(&g_bat_chg); return p >= 0; }
    char cap[32], st[32];
    if (!read_line("/sys/class/power_supply/battery/capacity", cap, sizeof cap)) return 0;
    return pn_battery_parse(cap, read_line("/sys/class/power_supply/battery/status", st, sizeof st) ? st : NULL, percent, charging);
}

// ---- frame totals -----------------------------------------------------------------------------------------------------------------------------------------
// game_frames: the replay's frame number (one per frame the game presents). The frame time the runtime publishes is, on the emulator, the time a frame took without
// its waits; the nearest the host measures is the GPU's busy time a frame (the overlay's "GPU ms": two timestamps per submission, in about 8 frames after the frame),
// so system_frames counts the frames whose time came in and frametime_ns sums them. A driver without timestamps counts none: no frame time is published then
uint64_t vka_frame(void); double mtl_gpu_ms(uint64_t f);
void pn_host_perf_totals(uint64_t *game_frames, uint64_t *system_frames, int64_t *frametime_ns) {
    static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER; static pn_perf_acc acc;
    pthread_mutex_lock(&mu);
    uint64_t f = vka_frame();
    pn_perf_step(&acc, f, mtl_gpu_ms);
    *game_frames = f; *system_frames = acc.frames; *frametime_ns = acc.sum_ns;
    pthread_mutex_unlock(&mu);
}

// ---- add-on content ---------------------------------------------------------------------------------------------------------------------------------------
// The opened container lives as long as the process: the runtime opens "aoc:" once a game and keeps it
int pn_host_aoc_open(const char *aoc_dir, int index, PnRtRomfs *out, char *why, size_t why_size) {
    char spec[1300];
    if (!pn_aoc_spec(spec, sizeof spec, aoc_dir, index)) { snprintf(why, why_size, "neither a .nup nor a dump the host reads (a RomFS image is not read for a companion)"); return 0; }
    pn_nup *n = pn_nup_open(spec, why, why_size);
    if (!n) return 0;
    pn_nup_provider(n, out);
    return 1;
}
#endif
