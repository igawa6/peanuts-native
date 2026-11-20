// The launcher's input, motion, rumble and lifecycle calls (input_android.h) into the host: gamepads → dm_pad_set_player, the on-screen controls and phone shakes → dm_input_set
// (the touch gamepad's path, as on iOS), touchscreen fingers → dm_touch_set, the phone's sensors → dm_motion_set_switch, game vibration → the launcher's rumble callback
#include "../hle.h"
#include "../front.h"
#include "../hle_haptics.h"
#include "../pad_slots.h"
#include "../pad_join.h"
#include "input_android.h"
#include "pad_motion.h"
#include <android/looper.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/sensor.h>
#include <media/NdkMediaCodec.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;   // pads, slots, touch, shake
static pad_slots g_slots;
static int g_labelled[DM_PLAYERS];
static nxin_pad g_pad[DM_PLAYERS];
static float g_dz = -1;
static nxin_touch g_touch; static int g_touch_init;
static uint64_t g_shake_btn;
static pad_motion_cal g_pm_cal[DM_PLAYERS];   // each controller's rest offset of the gyro (pad_motion.h), under g_mu
static int g_pm_log[DM_PLAYERS];
static int g_held;   // the text input box is up: pad and touch events are dropped (android_text_input)
static uint64_t g_since[DM_PLAYERS];   // when each player's controller connected (now_ns), for pad_slot_back
// The controller applet's join screen (android_controller_support), under g_mu: while g_joining the pad keys go to g_join, and the guest thread waits on g_join_cv
static pad_join g_join; static int g_joining;
static pthread_cond_t g_join_cv = PTHREAD_COND_INITIALIZER;
static const char g_handheld_tag;
#define HANDHELD ((const void *)&g_handheld_tag)   // the on-screen controls as the console's handheld controller: player 1's when no controller holds it
// The connected devices' names and labels: a controller the game let go (DisconnectNpad) or the join screen left out has no player until its next press, which connects it again as it was
static struct { int device, labelled; char name[64]; } g_known[32];
static void known_set(int device, const char *name, int labelled) {   // under g_mu
    int k = -1; for (int i = 0; i < 32; i++) if (g_known[i].name[0] && g_known[i].device == device) k = i;
    for (int i = 0; k < 0 && i < 32; i++) if (!g_known[i].name[0]) k = i;
    if (k < 0) return;
    g_known[k].device = device; g_known[k].labelled = labelled; snprintf(g_known[k].name, sizeof g_known[k].name, "%s", name && *name ? name : "unnamed");
}
static int known_of(int device) { for (int i = 0; i < 32; i++) if (g_known[i].name[0] && g_known[i].device == device) return i; return -1; }

static void touch_once(void) { if (!g_touch_init) { nxin_touch_init(&g_touch); g_touch_init = 1; } }
static const void *owner(int device) { return (const void *)(intptr_t)((int64_t)device + 0x100000000ll); }   // never NULL, even for device 0
static float deadzone(void) {
    if (g_dz < 0) { const char *e = getenv("DM_PAD_DEADZONE"); g_dz = e ? (float)atof(e) : 0.1f; }
    return g_dz;
}
static void publish_pad(int p) {   // under g_mu
    uint64_t b; int32_t lx, ly, rx, ry;
    nxin_pad_out(&g_pad[p], deadzone(), &b, &lx, &ly, &rx, &ry);
    dm_pad_set_player(p, b, lx, ly, rx, ry);
}
static void publish_touch_pad(void) {   // under g_mu
    uint64_t b; int32_t lx, ly, rx, ry;
    nxin_touch_pad(&g_touch, &b, &lx, &ly, &rx, &ry);
    dm_input_set(b | g_shake_btn, lx, ly, rx, ry);
}
static void publish_fingers(void) {   // under g_mu
    _Static_assert(sizeof(nxin_finger) == sizeof(dm_touch), "finger layout");
    dm_touch t[16]; int n = nxin_touch_fingers(&g_touch, (nxin_finger *)t, 16);
    dm_touch_set(t, n);
}
static nxin_map g_map; static int g_map_on = -1;   // DM_PAD_MAP (the launcher's Button mapping), read once
static const nxin_map *pad_map(void) {   // under g_mu
    if (g_map_on < 0) {
        const char *e = getenv("DM_PAD_MAP"); char bad[64];
        g_map_on = e && *e && nxin_map_parse(&g_map, e, bad, sizeof bad) > 0;
        if (e && *e) LOG("input: DM_PAD_MAP %s: %d button(s) remapped", e, __builtin_popcount(g_map.set));
        if (e && *e && *bad) LOG("[RED] input: DM_PAD_MAP: \"%s\" is not <Android button>=<Switch button> (names in docs/env_switches.tsv): left out", bad);
    }
    return g_map_on ? &g_map : NULL;
}
// Controllers on players the game does not read move to free players it reads (pad_slot_fill), with their state, in the order they press a button, as a Switch's controller
// applet takes them: one moves on its own press (nxhost_input_key), and from hle_hid.c, when the supported npads change or the controller applet opens, the ones that pressed
// last move first. A disconnect moves no one: the freed player stays kept for its controller until another one presses. The launcher sees the new player through
// nxhost_input_device_player (its rumble follows: GameActivity.padPlayers)
static void move_state(int from, int to) {   // under g_mu: a controller's state follows it from player from to player to (pad_slots has moved it already)
    g_pad[to] = g_pad[from]; g_labelled[to] = g_labelled[from]; g_pm_cal[to] = g_pm_cal[from]; g_pm_log[to] = g_pm_log[from]; g_since[to] = g_since[from];
    memset(&g_pad[from], 0, sizeof g_pad[from]); g_labelled[from] = 0; pad_motion_cal_reset(&g_pm_cal[from]); g_pm_log[from] = 0;
    dm_pad_set_player(from, 0, 0, 0, 0, 0); dm_motion_clear(from);
    dm_pad_players(pad_slot_mask(&g_slots)); publish_pad(to);
}
static void fill(const void *only) {   // under g_mu
    uint32_t usable = dm_pad_usable(); int from, to;
    while ((to = pad_slot_fill(&g_slots, usable, only, &from)) >= 0) {
        move_state(from, to);
        LOG("input: device %d (%s) moves from player %d to player %d: the game reads no player %d", (int)((intptr_t)g_slots.owner[to] - 0x100000000ll),
            g_slots.last[to][0] ? g_slots.last[to] : "unnamed", from + 1, to + 1, from + 1);
    }
}
static void on_usable(void) { pthread_mutex_lock(&g_mu); fill(NULL); pthread_mutex_unlock(&g_mu); }
// The game's own assignments (hle_hid.c): SwapNpadAssignment trades two players' controllers with their state (a game that makes the controller pressing at its title player 1
// swaps it there); DisconnectNpad lets a player's controller go: it has no player until its next press, which connects it again (take, pad_slot_take: its kept player first)
static void on_assign(int op, int a, int b) {
    pthread_mutex_lock(&g_mu);
    if (op == DM_PAD_SWAP && a >= 0 && b >= 0 && a < DM_PLAYERS && b < DM_PLAYERS && a != b) {
        pad_slot_swap(&g_slots, a, b);
        nxin_pad tp = g_pad[a]; g_pad[a] = g_pad[b]; g_pad[b] = tp;
        int tl = g_labelled[a]; g_labelled[a] = g_labelled[b]; g_labelled[b] = tl;
        pad_motion_cal tc = g_pm_cal[a]; g_pm_cal[a] = g_pm_cal[b]; g_pm_cal[b] = tc;
        int tg = g_pm_log[a]; g_pm_log[a] = g_pm_log[b]; g_pm_log[b] = tg;
        uint64_t ts = g_since[a]; g_since[a] = g_since[b]; g_since[b] = ts;
        dm_motion_clear(a); dm_motion_clear(b);
        dm_pad_players(pad_slot_mask(&g_slots));
        if (g_slots.owner[a]) publish_pad(a); else dm_pad_set_player(a, 0, 0, 0, 0, 0);
        if (g_slots.owner[b]) publish_pad(b); else dm_pad_set_player(b, 0, 0, 0, 0, 0);
        LOG("input: the game swaps players %d and %d: player %d is device %d (%s), player %d is device %d (%s)", a + 1, b + 1,
            a + 1, g_slots.owner[a] ? (int)((intptr_t)g_slots.owner[a] - 0x100000000ll) : -1, g_slots.owner[a] ? g_slots.last[a] : "none",
            b + 1, g_slots.owner[b] ? (int)((intptr_t)g_slots.owner[b] - 0x100000000ll) : -1, g_slots.owner[b] ? g_slots.last[b] : "none");
    } else if (op == DM_PAD_DISCONNECT && a >= 0 && a < DM_PLAYERS && g_slots.owner[a]) {
        int device = (int)((intptr_t)g_slots.owner[a] - 0x100000000ll);
        pad_slot_free(&g_slots, g_slots.owner[a]);
        memset(&g_pad[a], 0, sizeof g_pad[a]); dm_pad_set_player(a, 0, 0, 0, 0, 0);
        dm_pad_players(pad_slot_mask(&g_slots)); dm_motion_clear(a); pad_motion_cal_reset(&g_pm_cal[a]); g_pm_log[a] = 0;
        LOG("input: the game disconnects player %d: device %d (%s) has no player until it presses a button", a + 1, device, g_slots.last[a]);
    }
    pthread_mutex_unlock(&g_mu);
}
static int take(int device, const char *name, int labelled) {   // under g_mu
    int had = pad_slot_of(&g_slots, owner(device)) >= 0, k = known_of(device);
    if (name && !had) known_set(device, name, labelled);
    else if (!name && !had && k >= 0) { name = g_known[k].name; labelled = g_known[k].labelled; }   // connected again after the game or the join screen let it go
    int p = pad_slot_take(&g_slots, owner(device), name);
    if (p < 0) { if (!had) LOG("[RED] input: device %d (%s): every player is taken", device, name ? name : "?"); return -1; }
    if (!had) {
        const char *e = getenv("DM_PAD_LABELLED");
        g_labelled[p] = e ? atoi(e) : labelled; memset(&g_pad[p], 0, sizeof g_pad[p]); g_pad[p].map = pad_map();   // 1: all four face buttons by label; 2: A and B by label, X and Y by position
        g_since[p] = now_ns();
        dm_pad_players(pad_slot_mask(&g_slots));
        LOG("input: device %d (%s) is player %d%s", device, name && *name ? name : "unnamed", p + 1, g_labelled[p] == 1 ? ", face buttons by label" : g_labelled[p] == 2 ? ", A and B by label, X and Y by position" : "");
        static int registered; if (!registered) { registered = 1; dm_pad_usable_callback(on_usable); dm_pad_assign_callback(on_assign); }
    }
    return p;
}
int nxhost_input_device_added(int device, const char *name, int labelled) {
    pthread_mutex_lock(&g_mu); int p = take(device, name, labelled); pthread_mutex_unlock(&g_mu); return p;
}
void nxhost_input_device_removed(int device) {
    pthread_mutex_lock(&g_mu);
    int p = pad_slot_free(&g_slots, owner(device));
    if (p >= 0) {
        memset(&g_pad[p], 0, sizeof g_pad[p]); dm_pad_set_player(p, 0, 0, 0, 0, 0);
        dm_pad_players(pad_slot_mask(&g_slots)); dm_motion_clear(p); pad_motion_cal_reset(&g_pm_cal[p]); g_pm_log[p] = 0;
        LOG("input: device %d (player %d) removed", device, p + 1);
        // The same controller listed again as a new device a moment before this one went (Bluetooth): it takes this player back (pad_slot_back)
        int q = pad_slot_back(&g_slots, p, g_since, now_ns(), 3000000000ull);
        if (q >= 0) {
            move_state(q, p);
            LOG("input: device %d (%s) moves from player %d to player %d: the same controller, listed again as a new device before device %d went",
                (int)((intptr_t)g_slots.owner[p] - 0x100000000ll), g_slots.last[p], q + 1, p + 1, device);
        }
    }
    if (g_joining) { pad_join_gone(&g_join, owner(device)); pthread_cond_broadcast(&g_join_cv); }
    int k = known_of(device); if (k >= 0) g_known[k].name[0] = 0;
    pthread_mutex_unlock(&g_mu);
}
int nxhost_input_device_first(int device, const char *name) {
    pthread_mutex_lock(&g_mu);
    int p = take(device, name, 0), from = p, moved = -1;   // take: connected (labels as its added call gave them, or none)
    if (p > 0) {
        int lab = g_labelled[0]; g_labelled[0] = g_labelled[p]; g_labelled[p] = lab;
        p = pad_slot_first(&g_slots, owner(device), name, &moved);
        for (int q = 0; q < DM_PLAYERS; q++) if (q == 0 || q == from) { memset(&g_pad[q], 0, sizeof g_pad[q]); g_pad[q].map = pad_map(); publish_pad(q); }
        dm_pad_players(pad_slot_mask(&g_slots));
        if (moved > 0) LOG("input: device %d (%s) is player 1; player 1's device moves to player %d", device, name && *name ? name : "unnamed", moved + 1);
        else LOG("input: device %d (%s) is player 1", device, name && *name ? name : "unnamed");
    }
    pthread_mutex_unlock(&g_mu); return p;
}
int nxhost_input_device_player(int device) { pthread_mutex_lock(&g_mu); int p = pad_slot_of(&g_slots, owner(device)); pthread_mutex_unlock(&g_mu); return p; }
bool nxhost_input_key(int device, int keycode, bool down) {
    pthread_mutex_lock(&g_mu);
    const nxin_map *m = pad_map(); int src = nxin_src_of_key(keycode);
    if (!nxin_key_button(keycode, 0) && !(m && src >= 0 && (m->set & (1u << src)))) { pthread_mutex_unlock(&g_mu); return false; }   // BUTTON_C / Z only when mapped
    if (g_joining) {   // the join screen: L, R, A and B as the game's buttons (Button mapping, labels) go to it, nothing to the game
        int q = pad_slot_of(&g_slots, owner(device)); if (q < 0) q = take(device, NULL, 0);
        nxin_pad probe = {0}; probe.map = m;
        uint64_t b = nxin_pad_button(&probe, keycode, q >= 0 ? g_labelled[q] : 0);
        if (b == DM_BTN_L || b == DM_BTN_R || b == DM_BTN_A || b == DM_BTN_B) {
            int p = pad_join_key(&g_join, owner(device), b, down);
            int k = known_of(device);
            if (p >= 0) LOG("controller applet: device %d (%s) joins as player %d", device, k >= 0 ? g_known[k].name : "unnamed", p + 1);
            if (g_join.done) pthread_cond_broadcast(&g_join_cv);
        }
        pthread_mutex_unlock(&g_mu); return true;
    }
    if (g_held) { pthread_mutex_unlock(&g_mu); return true; }
    int p = take(device, NULL, 0);
    if (p >= 0 && down) {   // a press: on a player the game does not read, the controller takes a free one it reads (its press goes on to the game there)
        pad_slot_press(&g_slots, owner(device), now_ns());
        if (!(dm_pad_usable() >> p & 1)) { fill(owner(device)); p = pad_slot_of(&g_slots, owner(device)); }
    }
    if (p >= 0 && nxin_pad_key(&g_pad[p], keycode, down, g_labelled[p])) publish_pad(p);
    pthread_mutex_unlock(&g_mu);
    return true;
}
bool nxhost_input_axes(int device, const int *axes, const float *values, int n) {
    nxin_pad probe = {0};
    if (!nxin_pad_axes(&probe, axes, values, n)) return false;
    pthread_mutex_lock(&g_mu);
    if (g_held) { pthread_mutex_unlock(&g_mu); return true; }
    // A known controller without a player (the game or the join screen let it go) connects again on a button press, as on a console, not on a stick
    int p = pad_slot_of(&g_slots, owner(device)) < 0 && known_of(device) >= 0 ? -1 : take(device, NULL, 0);
    if (p >= 0) { nxin_pad_axes(&g_pad[p], axes, values, n); publish_pad(p); }
    pthread_mutex_unlock(&g_mu);
    return true;
}
bool nxhost_input_axis(int device, int axis, float value) { return nxhost_input_axes(device, &axis, &value, 1); }
void nxhost_input_deadzone(float dz) { pthread_mutex_lock(&g_mu); g_dz = dz < 0 ? 0 : dz > 0.9f ? 0.9f : dz; pthread_mutex_unlock(&g_mu); }

void nxhost_input_touch(int action, int id, float x, float y) {
    pthread_mutex_lock(&g_mu); touch_once();
    if (g_held) { pthread_mutex_unlock(&g_mu); return; }
    int r = nxin_touch_event(&g_touch, action, id, x, y);
    if (r & 1) publish_touch_pad();
    if (r & 2) publish_fingers();
    pthread_mutex_unlock(&g_mu);
}
void nxhost_touch_layout(float vw, float vh, float px, float py, float pw, float ph) {
    pthread_mutex_lock(&g_mu); touch_once();
    if (vw > 0 && vh > 0) { g_touch.sw = vw; g_touch.sh = vh; }
    if (pw > 0 && ph > 0) { g_touch.gx = px; g_touch.gy = py; g_touch.gw = pw; g_touch.gh = ph; }
    pthread_mutex_unlock(&g_mu);
}
static void touch_release_all(void) {   // under g_mu
    if (!g_touch_init) return;
    nxin_touch_event(&g_touch, AM_CANCEL, 0, 0, 0); publish_touch_pad(); publish_fingers();
}
void nxhost_touch_controls_enable(bool on) {
    pthread_mutex_lock(&g_mu); touch_once();
    if (g_touch.enabled != on) { touch_release_all(); g_touch.enabled = on; }
    pthread_mutex_unlock(&g_mu);
}
int nxhost_touch_controls_set(const nxhost_touch_control *c, int n) {
    pthread_mutex_lock(&g_mu); touch_once();
    int kept = 0, r = nxin_touch_set_layout(&g_touch, c, n, &kept);
    if (r & 1) publish_touch_pad();
    if (r & 2) publish_fingers();
    pthread_mutex_unlock(&g_mu);
    LOG("input: on-screen controls: %d, %s layout", kept, c && n > 0 ? "the launcher's" : "the default");
    return kept;
}
int nxhost_touch_controls(nxhost_touch_control *out, int max) {
    pthread_mutex_lock(&g_mu); touch_once();
    int n = g_touch.n < max ? g_touch.n : max; if (out && n > 0) memcpy(out, g_touch.c, (size_t)n * sizeof *out);
    int all = g_touch.n;
    pthread_mutex_unlock(&g_mu);
    return all;
}
void nxhost_touch_controls_state(uint64_t *pressed, int max, float stick[4]) {
    pthread_mutex_lock(&g_mu); touch_once();
    for (int i = 0; pressed && i < max && i < g_touch.n; i++) pressed[i] = g_touch.pressed[i];
    if (stick) { stick[0] = g_touch.sx[0]; stick[1] = g_touch.sy[0]; stick[2] = g_touch.sx[1]; stick[3] = g_touch.sy[1]; }
    pthread_mutex_unlock(&g_mu);
}

// ---- Text input: the launcher's input box for the keyboard applet (front_text_input) ----
// The box takes the window's focus, so the game screen never gets the key-up of the pad button or the touch-up of the tap that opened it: everything the game saw held
// is let go when the box comes up, and pad and touch events are dropped until it goes (on the Mac the input box clears the keys the same way, front_mac.m), so nothing
// stays pressed and nothing typed or pressed in the box reaches the game. The guest clock keeps running, as on the Switch (hle_swkbd.c)
static nxhost_text_input_fn _Atomic g_ti_fn; static void *_Atomic g_ti_ctx;
void nxhost_text_input_callback(nxhost_text_input_fn fn, void *ctx) {
    atomic_store(&g_ti_ctx, ctx); atomic_store(&g_ti_fn, fn);
    LOG("input: the keyboard applet goes to the launcher's input box%s", fn ? "" : " (none: cancel)");
}
static void input_hold(int on) {
    pthread_mutex_lock(&g_mu);
    g_held = on;
    for (int p = 0; p < DM_PLAYERS; p++) if (pad_slot_mask(&g_slots) >> p & 1) { memset(&g_pad[p], 0, sizeof g_pad[p]); g_pad[p].map = pad_map(); publish_pad(p); }
    touch_release_all();
    pthread_mutex_unlock(&g_mu);
}
int android_text_input(const char *title, const char *sub, const char *guide, const char *initial, int max_chars, int min_chars, char *out, int out_cap) {
    nxhost_text_input_fn fn = atomic_load(&g_ti_fn);
    if (!fn) return -1;
    input_hold(1);
    int r = fn(atomic_load(&g_ti_ctx), title ? title : "", sub ? sub : "", guide ? guide : "", initial ? initial : "", max_chars, min_chars, out, out_cap);
    input_hold(0);
    if (r > 0 && out_cap > 0) out[out_cap - 1] = 0;
    return r > 0 ? 1 : 0;
}
// ---- User selector: the launcher's picker (front_user_select), the input held around it as for the input box ----
static nxhost_user_select_fn _Atomic g_us_fn; static void *_Atomic g_us_ctx;
void nxhost_user_select_callback(nxhost_user_select_fn fn, void *ctx) {
    atomic_store(&g_us_ctx, ctx); atomic_store(&g_us_fn, fn);
    LOG("input: the user selector goes to the launcher's picker%s", fn ? "" : " (none: the preselected user)");
}
__attribute__((weak)) void dm_thread_native_wait(int entering);   // PEANUTS: hle_thread.c; absent in the unit tests of this file, which have no game threads
int android_user_select(const char *const *names, const int *offered, int n, int def) {
    nxhost_user_select_fn fn = atomic_load(&g_us_fn);
    if (!fn) return -2;
    input_hold(1);
    if (dm_thread_native_wait) dm_thread_native_wait(1);   // PEANUTS: the game's thread waits for the player here (a native wait, as hle_nvn.c's pacing sleeps): a stop of all threads does not wait the screen out
    int r = fn(atomic_load(&g_us_ctx), names, offered, n, def);
    if (dm_thread_native_wait) dm_thread_native_wait(0);   // PEANUTS
    input_hold(0);
    return r;
}

// ---- Controller applet: the join screen (pad_join.h), drawn by the launcher (CtrlScreen.java), fed here by the pad keys (nxhost_input_key) and the screen's buttons ----
static nxhost_ctrl_fn _Atomic g_ctrl_fn; static void *_Atomic g_ctrl_ctx;
void nxhost_ctrl_callback(nxhost_ctrl_fn fn, void *ctx) {
    atomic_store(&g_ctrl_ctx, ctx); atomic_store(&g_ctrl_fn, fn);
    LOG("input: the controller applet goes to the launcher's join screen%s", fn ? "" : " (none: it answers at once)");
}
static int device_of(const void *o) { return o == HANDHELD ? -2 : o ? (int)((intptr_t)o - 0x100000000ll) : -1; }
static const char *name_of(const void *o) {   // under g_mu
    if (o == HANDHELD) return "On-screen controls";
    int k = known_of(device_of(o)); if (k >= 0) return g_known[k].name;
    int q = pad_slot_of(&g_slots, o); return q >= 0 && g_slots.last[q][0] ? g_slots.last[q] : "Controller";
}
// The joined controllers become the players (the on-screen controls on player 1 leave its slot empty: they feed player 1 as before); the others are not connected until they
// press again. Their buttons start released (the input was held), motion calibrations go with them
static void join_apply(void) {   // under g_mu
    pad_slots ns; memset(&ns, 0, sizeof ns);
    int lab[DM_PLAYERS] = {0}, plog[DM_PLAYERS] = {0}; pad_motion_cal cal[DM_PLAYERS]; uint64_t since[DM_PLAYERS] = {0};
    for (int p = 0; p < DM_PLAYERS; p++) {
        pad_motion_cal_reset(&cal[p]);
        const void *o = g_join.player[p]; int q = o && o != HANDHELD ? pad_slot_of(&g_slots, o) : -1;
        if (q < 0) continue;
        ns.owner[p] = o; memcpy(ns.last[p], g_slots.last[q], sizeof ns.last[p]); ns.pressed[p] = g_slots.pressed[q];
        lab[p] = g_labelled[q]; cal[p] = g_pm_cal[q]; plog[p] = g_pm_log[q]; since[p] = g_since[q];
    }
    for (int q = 0; q < DM_PLAYERS; q++) if (g_slots.owner[q] && pad_slot_of(&ns, g_slots.owner[q]) < 0)
        LOG("controller applet: device %d (%s) did not join: not connected until it presses a button", device_of(g_slots.owner[q]), name_of(g_slots.owner[q]));
    g_slots = ns;
    for (int p = 0; p < DM_PLAYERS; p++) {
        g_labelled[p] = lab[p]; g_pm_cal[p] = cal[p]; g_pm_log[p] = plog[p]; g_since[p] = since[p];
        memset(&g_pad[p], 0, sizeof g_pad[p]); g_pad[p].map = pad_map(); publish_pad(p); dm_motion_clear(p);
        if (g_join.player[p]) LOG("controller applet: player %d is device %d (%s)", p + 1, device_of(g_join.player[p]), name_of(g_join.player[p]));
    }
    dm_pad_players(pad_slot_mask(&g_slots));
}
int android_controller_support(const void *arg, int *count) {
    nxhost_ctrl_fn fn = atomic_load(&g_ctrl_fn);
    if (!fn || !arg) return -2;
    input_hold(1);
    pthread_mutex_lock(&g_mu);
    const void *held[DM_PLAYERS]; for (int p = 0; p < DM_PLAYERS; p++) held[p] = g_slots.owner[p];
    if (!held[0]) held[0] = HANDHELD;   // no controller on player 1: the on-screen controls are its controller, as a console's handheld one
    pad_join_start(&g_join, (const ctrl_arg *)arg, held, dm_pad_usable()); g_joining = 1;
    LOG("controller applet: the join screen opens for %d to %d players%s, %d already joined", g_join.min, g_join.max, g_join.a.take_over ? "" : " (no take over)", pad_join_count(&g_join));
    pthread_mutex_unlock(&g_mu);
    fn(atomic_load(&g_ctrl_ctx), 1);
    if (dm_thread_native_wait) dm_thread_native_wait(1);   // PEANUTS: the wait for the join screen is a native wait of the game's thread, as the user selector's above
    pthread_mutex_lock(&g_mu);
    while (!g_join.done) pthread_cond_wait(&g_join_cv, &g_mu);
    pthread_mutex_unlock(&g_mu);
    if (dm_thread_native_wait) dm_thread_native_wait(0);   // PEANUTS: left without the lock (the leave waits out a stop under way); done stays set until this thread clears the screen below
    pthread_mutex_lock(&g_mu);
    int ok = g_join.done == 1;
    if (ok) { join_apply(); if (count) *count = pad_join_count(&g_join); }
    g_joining = 0;
    pthread_mutex_unlock(&g_mu);
    fn(atomic_load(&g_ctrl_ctx), 0);
    input_hold(0);
    return ok;
}
static void tabless(char *d, size_t n, const char *s) {   // a field of the state text: no tabs or line breaks
    size_t i = 0; for (; s && s[i] && i + 1 < n; i++) d[i] = s[i] == '\t' || s[i] == '\n' || s[i] == '\r' ? ' ' : s[i];
    if (n) d[i < n ? i : n - 1] = 0;
}
int nxhost_ctrl_state(char *buf, int n) {
    if (!buf || n <= 0) return 0;
    pthread_mutex_lock(&g_mu);
    int len;
    if (!g_joining) len = snprintf(buf, (size_t)n, "closed\n");
    else {
        len = snprintf(buf, (size_t)n, "open\t%d\t%d\t%d\t%d\t%d\n", g_join.min, g_join.max, g_join.a.single, pad_join_can_end(&g_join),
                       !g_join.player[0] && pad_join_of(&g_join, HANDHELD) < 0);
        for (int p = 0; p < g_join.max && len < n; p++) {
            const void *o = g_join.player[p]; char name[64], text[0x81], col[12] = "-";
            tabless(name, sizeof name, o ? name_of(o) : ""); tabless(text, sizeof text, g_join.a.text_on ? g_join.a.text[p] : "");
            if (g_join.a.color_on) snprintf(col, sizeof col, "%02x%02x%02x%02x", g_join.a.color[p][0], g_join.a.color[p][1], g_join.a.color[p][2], g_join.a.color[p][3]);
            len += snprintf(buf + len, (size_t)(n - len), "%d\t%d\t%s\t%s\t%s\n", p + 1, device_of(o), name, col, text);
        }
    }
    pthread_mutex_unlock(&g_mu);
    return len < n ? len : n - 1;
}
void nxhost_ctrl_button(int what) {
    pthread_mutex_lock(&g_mu);
    if (g_joining) {
        if (what == NXHOST_CTRL_CANCEL) pad_join_cancel(&g_join);
        else if (what == NXHOST_CTRL_OK) pad_join_finish(&g_join);
        else if (what == NXHOST_CTRL_JOIN_TOUCH && !g_join.player[0] && pad_join_of(&g_join, HANDHELD) < 0) {
            g_join.player[0] = HANDHELD; g_join.provisional = 0; LOG("controller applet: the on-screen controls join as player 1");
        }
        pthread_cond_broadcast(&g_join_cv);
    }
    pthread_mutex_unlock(&g_mu);
}

// ---- Motion: ASensorManager on a looper thread of its own ----
static pthread_mutex_t g_mo_mu = PTHREAD_MUTEX_INITIALIZER;   // start / stop
static pthread_t g_mo_th; static _Atomic int g_mo_run; static ALooper *_Atomic g_mo_looper; static int g_mo_on;
static _Atomic int g_rotation = 1;   // a phone held in landscape, its top to the left, until the launcher says
void nxhost_motion_sens(float f) { dm_motion_sens(f); }
void nxhost_motion_rotation(int r) { atomic_store(&g_rotation, r & 3); }
static int env_off(const char *n) { const char *e = getenv(n); return e && !strcmp(e, "0"); }

// ---- A controller's own motion sensors (the launcher's PadMotion.java reads them): the six-axis sensor of the player the device has ----
static int pad_motion_off(void) { static int off = -1; if (off < 0) off = env_off("DM_MOTION") || env_off("DM_PAD_MOTION"); return off; }
bool nxhost_input_motion(int device, const float acc[3], const float gyro[3]) {
    if (pad_motion_off()) return false;
    pthread_mutex_lock(&g_mu);
    int p = pad_slot_of(&g_slots, owner(device));
    if (p >= 0 && !g_held) {
        float a[3], w[3]; pad_motion_feed(&g_pm_cal[p], acc, gyro, now_ns(), a, w);
        if (!g_pm_log[p]) { g_pm_log[p] = 1; LOG("input: player %d: six-axis sensor from the controller's own gyroscope and accelerometer", p + 1); }
        dm_motion_set(p, a, w);   // GameController's path: DM_MOTION_AXES / DM_MOTION_ACC_SIGN apply, and the controller's motion outranks the phone's
    }
    pthread_mutex_unlock(&g_mu);
    return true;
}
static void *motion_thread(void *arg) {
    (void)arg; plat_thread_set_name("dm sensors");
    // PEANUTS: ALLOW_NON_CALLBACKS, not 0: the sensor queue below is added to this looper without a callback, which a looper prepared with 0 refuses ("Looper: Invalid attempt
    // to set NULL callback but not allowed for this looper" in logcat). The queue then never woke the poll: the thread read the sensors when its 200 ms timeout ran out, so
    // motion reached the game in bursts five times a second, up to 200 ms late (aiming by gyroscope trailed the hand and moved in steps)
    ALooper *lp = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS); atomic_store(&g_mo_looper, lp);
    ASensorManager *m = ASensorManager_getInstanceForPackage("dev.nxnative.host");
    const ASensor *acc = m ? ASensorManager_getDefaultSensor(m, ASENSOR_TYPE_ACCELEROMETER) : NULL, *gyr = m ? ASensorManager_getDefaultSensor(m, ASENSOR_TYPE_GYROSCOPE) : NULL;
    ASensorEventQueue *q = m ? ASensorManager_createEventQueue(m, lp, 1, NULL, NULL) : NULL;
    int gyro = gyr && !env_off("DM_MOTION") && !env_off("DM_PHONE_MOTION");
    if (!q || !acc) { LOG("[RED] input: no accelerometer (sensor manager %p): no phone motion", (void *)m); atomic_store(&g_mo_run, 0); }
    else {
        // PEANUTS: 200 Hz, the console's own six-axis rate and the most Android gives without the high-sampling-rate permission (was 100 Hz): a sample per entry of the history a game reads
        ASensorEventQueue_registerSensor(q, acc, 5000, 0);
        if (gyro) ASensorEventQueue_registerSensor(q, gyr, 5000, 0);
        LOG("input: phone sensors on: accelerometer for shakes%s", gyro ? ", gyroscope as player 1's six-axis sensor" : gyr ? " (gyroscope off by DM_MOTION / DM_PHONE_MOTION)" : " (no gyroscope)");
    }
    nxin_shake sh = {0}; const char *e = getenv("DM_SHAKE_G"); sh.thr = e && atof(e) > 0 ? (float)atof(e) : 1.5f;
    float a[3] = {0, 0, 9.80665f}; int have_a = 0;
    // PEANUTS: the gyroscope's delivery, logged every 10 s: how many samples, their spacing by the sensor's clock, and how long after its measurement a sample got here (10 s after the start, then every minute)
    struct { uint64_t t0, last, n, late_max, gap_max, late_sum, every; } ms = {.every = 10000000000ull};   // the first line after 10 s, then one a minute
    while (atomic_load(&g_mo_run)) {
        ALooper_pollOnce(200, NULL, NULL, NULL);
        ASensorEvent ev[32]; ssize_t n;
        while (q && (n = ASensorEventQueue_getEvents(q, ev, 32)) > 0) {
          // PEANUTS: a sensor event carries the time it was measured (CLOCK_BOOTTIME); off moves it onto the host clock, so events that waited for this thread keep their real
          // spacing: stamped on arrival, events read together were turning counted as none
          struct timespec bt; clock_gettime(CLOCK_BOOTTIME, &bt);
          uint64_t now = now_ns(); int64_t off = (int64_t)now - ((int64_t)bt.tv_sec * 1000000000ll + bt.tv_nsec);
          for (ssize_t i = 0; i < n; i++) {
            if (ev[i].type == ASENSOR_TYPE_ACCELEROMETER) {
                memcpy(a, ev[i].acceleration.v, sizeof a); have_a = 1;
                float g[3] = {-a[0] / 9.80665f, -a[1] / 9.80665f, -a[2] / 9.80665f};
                uint64_t want = nxin_shake_feed(&sh, g, (uint64_t)ev[i].timestamp) ? DM_BTN_SHAKE : 0;
                pthread_mutex_lock(&g_mu);
                if (want != g_shake_btn) { g_shake_btn = want; touch_once(); publish_touch_pad(); }
                pthread_mutex_unlock(&g_mu);
            } else if (ev[i].type == ASENSOR_TYPE_GYROSCOPE && have_a) {
                float acc_s[3], rot_s[3];
                nxin_motion_to_switch(atomic_load(&g_rotation), a, ev[i].vector.v, acc_s, rot_s);
                int64_t ts = ev[i].timestamp + off; uint64_t t = ts > 0 && (uint64_t)ts < now ? (uint64_t)ts : now;   // PEANUTS: the sample's own time, never in the future
                dm_motion_set_switch_at(0, acc_s, rot_s, t);
                if (!ms.t0) ms.t0 = now;
                if (ms.last && t > ms.last && t - ms.last > ms.gap_max) ms.gap_max = t - ms.last;
                if (now - t > ms.late_max) ms.late_max = now - t;
                ms.last = t; ms.n++; ms.late_sum += now - t;
                if (now - ms.t0 >= ms.every) {
                    LOG("input: phone gyroscope: %.0f samples a second, the longest gap %.1f ms; a sample arrives %.1f ms after it was measured on average, %.1f ms at most",
                        (double)ms.n * 1e9 / (double)(now - ms.t0), (double)ms.gap_max / 1e6, (double)ms.late_sum / (double)ms.n / 1e6, (double)ms.late_max / 1e6);
                    ms.t0 = now; ms.n = ms.late_max = ms.gap_max = ms.late_sum = 0; ms.every = 60000000000ull;
                }
            }
          }
        }
    }
    if (q) { if (acc) ASensorEventQueue_disableSensor(q, acc); if (gyro) ASensorEventQueue_disableSensor(q, gyr); ASensorManager_destroyEventQueue(m, q); }
    atomic_store(&g_mo_looper, NULL);
    return NULL;
}
bool nxhost_motion_start(void) {
    pthread_mutex_lock(&g_mo_mu);
    if (!g_mo_on) {
        atomic_store(&g_mo_run, 1);
        g_mo_on = !pthread_create(&g_mo_th, NULL, motion_thread, NULL);
    }
    int on = g_mo_on;
    pthread_mutex_unlock(&g_mo_mu);
    return on;
}
void nxhost_motion_stop(void) {
    pthread_mutex_lock(&g_mo_mu);
    if (g_mo_on) {
        atomic_store(&g_mo_run, 0);
        ALooper *lp = atomic_load(&g_mo_looper); if (lp) ALooper_wake(lp);
        pthread_join(g_mo_th, NULL); g_mo_on = 0;
        pthread_mutex_lock(&g_mu); if (g_shake_btn) { g_shake_btn = 0; touch_once(); publish_touch_pad(); } pthread_mutex_unlock(&g_mu);
    }
    pthread_mutex_unlock(&g_mo_mu);
}

// ---- Rumble: the host's snapshots (hle_haptics.h) go to a thread of their own, which calls the launcher ----
static pthread_mutex_t g_rb_mu = PTHREAD_MUTEX_INITIALIZER; static pthread_cond_t g_rb_cv = PTHREAD_COND_INITIALIZER;
static nxhost_rumble_fn g_rb_fn; static void *g_rb_ctx;
static dm_haptic_state g_rb_state[DM_PLAYERS]; static uint32_t g_rb_dirty; static float g_rb_gain = 1;
static void rumble_sink(dm_haptic_state s) {   // under the haptics lock: copy and wake only
    if (s.player < 0 || s.player >= DM_PLAYERS) return;
    pthread_mutex_lock(&g_rb_mu); g_rb_state[s.player] = s; g_rb_dirty |= 1u << s.player; pthread_cond_signal(&g_rb_cv); pthread_mutex_unlock(&g_rb_mu);
}
static void *rumble_thread(void *arg) {
    (void)arg; plat_thread_set_name("dm rumble");
    dm_haptic_level sent[DM_PLAYERS] = {0};
    for (;;) {
        pthread_mutex_lock(&g_rb_mu);
        while (!g_rb_dirty) pthread_cond_wait(&g_rb_cv, &g_rb_mu);
        uint32_t d = g_rb_dirty; g_rb_dirty = 0; dm_haptic_state st[DM_PLAYERS]; memcpy(st, g_rb_state, sizeof st);
        nxhost_rumble_fn fn = g_rb_fn; void *ctx = g_rb_ctx;
        pthread_mutex_unlock(&g_rb_mu);
        for (int p = 0; p < DM_PLAYERS; p++) {
            if (!(d & 1u << p)) continue;
            dm_haptic_level l = dm_haptics_level(st[p].motor, 2, g_rb_gain);
            l.intensity = roundf(l.intensity * 255) / 255; l.sharpness = roundf(l.sharpness * 32) / 32;   // the vibrator's amplitude steps: no call for a change it cannot show
            if (l.intensity == sent[p].intensity && (l.intensity == 0 || l.sharpness == sent[p].sharpness)) continue;
            sent[p] = l;
            if (fn) fn(ctx, p, l.intensity, l.sharpness);
        }
    }
    return NULL;
}
static void rumble_start(void) {
    const char *e = getenv("DM_RUMBLE_GAIN"); if (e) { float v = strtof(e, NULL); if (isfinite(v)) g_rb_gain = fmaxf(0, fminf(3, v)); }
    pthread_t t; if (!pthread_create(&t, NULL, rumble_thread, NULL)) pthread_detach(t);
    e = getenv("DM_RUMBLE"); dm_haptics_init(rumble_sink, !e || atoi(e) != 0);
    LOG("input: rumble goes to the launcher's vibrator callback%s", e && !atoi(e) ? " (off: DM_RUMBLE=0)" : "");
}
void nxhost_rumble_callback(nxhost_rumble_fn fn, void *ctx) {
    pthread_mutex_lock(&g_rb_mu); g_rb_fn = fn; g_rb_ctx = ctx; pthread_mutex_unlock(&g_rb_mu);
    static pthread_once_t once = PTHREAD_ONCE_INIT;
    if (fn) pthread_once(&once, rumble_start);
}
void nxhost_rumble_route(int player, bool on) { dm_haptics_route(player, on); }

// ---- Lifecycle ----
static pthread_mutex_t g_lc_mu = PTHREAD_MUTEX_INITIALIZER;
// The app is suspended while it is in the background (onStop) or paused (the user's Pause, or the screen coming back with pause-on-return on: events 4 / 5): the guest clock
// frozen, the graphics drained and gated, the guest threads stopped (dm_guest_suspend), audio suspended, and the game told Background (focus 3, after OutOfFocus 2 as the
// system does). Coming out of it (neither left) the game is OutOfFocus (2), and InFocus (1) only once the activity is resumed as well
static int g_bg, g_intr, g_pause, g_susp, g_resumed;
static void audio_apply(void) { dm_audio_suspend(g_susp || g_intr); }
// CPU time of every thread of the process since the last call, from /proc/self/task/<tid>/stat (utime + stime): the busiest named, for the pause / resume lines
#define CPU_TIDS 512
static struct { int tid; uint64_t ticks; } g_cpu[CPU_TIDS]; static int g_ncpu; static uint64_t g_cpu_at;
static void cpu_line(const char *what) {
    static long hz; if (!hz) hz = sysconf(_SC_CLK_TCK) > 0 ? sysconf(_SC_CLK_TCK) : 100;
    DIR *d = opendir("/proc/self/task"); if (!d) return;
    struct { char name[20]; uint64_t dt; } top[6] = {{{0}}}; uint64_t total = 0, now = now_ns(); int n = 0, threads = 0;
    static struct { int tid; uint64_t ticks; } cur[CPU_TIDS];
    for (struct dirent *e; (e = readdir(d)) && n < CPU_TIDS;) {
        int tid = atoi(e->d_name); if (tid <= 0) continue;
        char p[64], b[512]; snprintf(p, sizeof p, "/proc/self/task/%d/stat", tid);
        FILE *f = fopen(p, "r"); if (!f) continue;
        size_t k = fread(b, 1, sizeof b - 1, f); fclose(f); b[k] = 0;
        char *o = strchr(b, '('), *c = strrchr(b, ')'); if (!o || !c) continue;
        char name[20]; size_t nl = (size_t)(c - o - 1) < sizeof name - 1 ? (size_t)(c - o - 1) : sizeof name - 1; memcpy(name, o + 1, nl); name[nl] = 0;
        unsigned long ut = 0, st = 0;   // fields 14 and 15, counted after the name: state is field 3
        if (sscanf(c + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %lu %lu", &ut, &st) != 2) continue;
        uint64_t ticks = (uint64_t)ut + st, was = 0;
        for (int i = 0; i < g_ncpu; i++) if (g_cpu[i].tid == tid) { was = g_cpu[i].ticks; break; }
        uint64_t dt = ticks > was ? ticks - was : 0;
        cur[n].tid = tid; cur[n].ticks = ticks; n++; threads++; total += dt;
        for (int i = 0; i < 6; i++) if (dt > top[i].dt) { memmove(&top[i + 1], &top[i], (5 - i) * sizeof top[0]); snprintf(top[i].name, sizeof top[i].name, "%s", name); top[i].dt = dt; break; }
    }
    closedir(d);
    double wall = g_cpu_at ? (now - g_cpu_at) / 1e9 : 0;
    char tl[512]; size_t tlen = 0; tl[0] = 0;
    for (int i = 0; i < 6 && top[i].dt; i++) tlen += (size_t)snprintf(tl + tlen, sizeof tl - tlen, "%s'%s' %.2f s", i ? ", " : "", top[i].name, (double)top[i].dt / hz);
    if (g_cpu_at) LOG("[lifecycle] %s: threads' CPU since the last pause or resume %.2f s in %.1f s (%d threads)%s%s", what, (double)total / hz, wall, threads, tlen ? "; busiest: " : "", tl);
    else LOG("[lifecycle] %s: threads' CPU since the process started %.2f s (%d threads)%s%s", what, (double)total / hz, threads, tlen ? "; busiest: " : "", tl);   // the first line: no earlier reading
    memcpy(g_cpu, cur, (size_t)n * sizeof cur[0]); g_ncpu = n; g_cpu_at = now;
}
__attribute__((weak)) void peanuts_lifecycle(int suspended);   // PEANUTS: dm/peanuts/peanuts.c; absent in a build without it
void vka_pso_pause(int on);   // PEANUTS: resume can precede the first guest frame
static void suspend_apply(const char *why) {
    int want = g_bg || g_pause;
    if (want == g_susp) return;
    g_susp = want;
    if (want) {
        dm_focus_set(2); dm_focus_set(3); audio_apply(); guest_clock_freeze_for(1, 1); dm_gfx_suspend(1); dm_guest_suspend(1); crash_watch_hold(1);
        if (peanuts_lifecycle) peanuts_lifecycle(1);   // PEANUTS: the companion runtime learns of the pause after the game is told to stop (its mailbox epoch, its tick)
        cpu_line(why);
    } else {
        cpu_line(why);
        if (peanuts_lifecycle) peanuts_lifecycle(0);   // PEANUTS: and of the resume before the game runs again, the order Eden Duo's Pause and Run keep
        crash_watch_hold(0); dm_guest_suspend(0); dm_gfx_suspend(0); guest_clock_freeze_for(1, 0); audio_apply(); dm_focus_set(2);
        vka_pso_pause(0);   // PEANUTS: let shader preparation finish so the guest can start
        if (g_resumed) dm_focus_set(1);
    }
}
void nxhost_audio_interrupt(bool on) {
    pthread_mutex_lock(&g_lc_mu);
    if (g_intr != on) { g_intr = on; LOG("audio: %s", on ? "focus lost (interruption)" : "focus back"); audio_apply(); dm_haptics_suspend(DM_HAPTICS_INTERRUPTED, on); }
    pthread_mutex_unlock(&g_lc_mu);
}
void nxhost_audio_route_changed(void) { LOG("audio: the output device changed; opening the streams again"); dm_audio_reset(); }
static void input_release(void) {   // what is held when the app leaves the screen is let go: the game must not see a button stuck down
    pthread_mutex_lock(&g_mu);
    for (int p = 0; p < DM_PLAYERS; p++) if (g_slots.owner[p]) { memset(&g_pad[p], 0, sizeof g_pad[p]); g_pad[p].map = pad_map(); dm_pad_set_player(p, 0, 0, 0, 0, 0); }
    touch_release_all();
    pthread_mutex_unlock(&g_mu);
}
void nxhost_lifecycle(int ev) {
    pthread_mutex_lock(&g_lc_mu);
    switch (ev) {
    case 0: g_resumed = 0; input_release(); if (!g_susp) dm_focus_set(2); dm_haptics_suspend(DM_HAPTICS_INACTIVE, 1); break;   // onPause
    case 1: g_bg = 1; suspend_apply(g_pause ? "background (paused)" : "background"); break;                                    // onStop
    case 2: g_bg = 0; suspend_apply("foreground"); if (g_pause) LOG("[lifecycle] foreground, paused: the game waits for a tap"); break;   // onStart
    case 3: g_resumed = 1; dm_haptics_suspend(DM_HAPTICS_INACTIVE, 0); if (!g_susp) dm_focus_set(1); break;                     // onResume
    case 4: if (!g_pause) { g_pause = 1; input_release(); suspend_apply("paused"); } break;                                   // Pause (the in-game panel, or the return)
    case 5: if (g_pause) { g_pause = 0; suspend_apply("continued"); } break;                                                    // the tap that continues
    default: LOG("[RED] nxhost_lifecycle: unknown event %d", ev);
    }
    pthread_mutex_unlock(&g_lc_mu);
}
int nxhost_paused(void) {
    pthread_mutex_lock(&g_lc_mu); int p = g_pause; pthread_mutex_unlock(&g_lc_mu);
    return p;
}

// Which decoders the phone has, for a bring-up log (input_android.h): created and deleted at once
void nxhost_codecs_log(void) {
    static const char *types[] = {"video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01", "audio/mp4a-latm"};
    for (size_t i = 0; i < sizeof types / sizeof *types; i++) {
        AMediaCodec *c = AMediaCodec_createDecoderByType(types[i]); char *name = NULL;
        if (c) AMediaCodec_getName(c, &name);
        LOG("codecs: %-20s %s", types[i], c ? (name ? name : "(unnamed)") : "none");
        if (c) { if (name) AMediaCodec_releaseName(c, name); AMediaCodec_delete(c); }
    }
}

// ---- Frame rate cap ----
void dm_fps_target_set(int t, const char *src); int dm_fps_target(void); int dm_fps_lock_rates(void); int nvn_game_fps(void); int nxhost_fps_limit_get(void);
void nxhost_fps_cap_set(int fps) { dm_fps_target_set(fps, "the launcher's in-game panel"); }
int nxhost_fps_cap_status(char *buf, int n) {
    int loaded = dm_main_build()[0] != 0, cap = dm_fps_target(), rates = loaded ? dm_fps_lock_rates() : 0;   // the limiters are looked for once, so only after main is loaded
    // the fourth: the rate the frame limit holds (hle_fpslock.c nxhost_fps_limit_get; a frame-step game holds 60, 30 or 20 only, so 45 and 40 run at 30), 0 = none
    if (buf && n > 0) snprintf(buf, (size_t)n, "%d %d %d %d", cap, rates, loaded ? nvn_game_fps() : 0, loaded ? nxhost_fps_limit_get() : 0);
    return cap;
}
