// SPDX-License-Identifier: MIT
// Mario Tennis Fever (Switch 2), nro0 of 1.1.1 (build ID 250a510a934129cb38e930c9a29082b6): its scenes drawn at a size below what its graphics scales give.
//
// The game sizes its scenes in one function of nro0 (nro0+0x125e980..): a base size by the operation mode (console 2560x1440, handheld 1920x1080; 1920x1080 and 1280x720 while an
// object at nro0+0x3e17e80 exists), capped by vi::QueryDisplayBufferMaxResolution in one performance mode, then the graphics scale (nn::oe::GetCurrentGraphicsScale, stored at
// nro0+0x3ecb31c and read only here): scale 1 takes 4/5 of it (console 2048x1152, the game's start), scale 2 takes 2/3 (handheld 1280x720), any other value keeps it. It writes the
// width and height as u16 at +0x1c and +0x1e of the scene's size record (x20) and keeps the width in w9; nro0+0x125eb98 (`ldr w10,[x19,#0x108]`) is where every path joins, before
// the width is compared with the scene's current one (equal: nothing to resize). So the smallest size a Horizon answer gives is 1280x720 (handheld, scale 2).
//
// The gate sits there (patches.py OBS row, kind OBS_MTF_SIZE). With a graphics size (DM_GRAPHICS_SIZE, or the in-game panel's: dm_graphics_size) smaller than the game's pick in both
// sides, it writes that size into the record and w9, so the game sizes its scenes at it; a size at or above the game's pick leaves the game's own (the gate never draws more than the
// game chose, so the display buffer cap above still holds). Gate frame (entry.S dm_gate): x0..x30 at r[0..30]; a write to a register takes effect when the gate returns.
#include "hle.h"
#include "dm.h"
#include <stdatomic.h>
#include <string.h>

#define MTF_NRO_BUILD "250a510a934129cb38e930c9a29082b6"
enum { R_WIDTH = 0x1c, R_HEIGHT = 0x1e };

static _Atomic int g_build_ok = -1;   // -1 not checked yet, 0 another build (the gate does nothing), 1 nro0 1.1.1
static _Atomic uint64_t g_logged;      // the last game size and result logged (gw, gh, w, h in 16 bits each): each new pair is logged once

static int build_ok(void) {
    int v = g_build_ok;
    if (v < 0) {
        v = 0;
        for (int i = 0; i < g_nmod; i++) if (g_mod[i].nro && !strcmp(g_mod[i].name, "nro0") && !strcmp(g_mod[i].build_id, MTF_NRO_BUILD)) v = 1;
        if (!v) LOG("[RED] mtf size: the gate ran outside nro0 1.1.1 (%s); the game's own size", MTF_NRO_BUILD);
        g_build_ok = v;
    }
    return v;
}

static void mtf_size_gate(uint64_t *r, int param) {
    (void)param;
    int w, h;
    dm_graphics_size(&w, &h);
    if (!w || !h || !build_ok()) return;
    uint64_t rec = r[20];
    if (!rec || (rec & 1)) return;
    uint16_t gw, gh;
    memcpy(&gw, (void *)(uintptr_t)(rec + R_WIDTH), 2); memcpy(&gh, (void *)(uintptr_t)(rec + R_HEIGHT), 2);
    int smaller = w < gw && h < gh;
    uint64_t key = (uint64_t)gw << 48 | (uint64_t)gh << 32 | (uint64_t)(uint16_t)w << 16 | (uint16_t)h;
    if (atomic_exchange(&g_logged, key) != key)
        { if (smaller) LOG("mtf size: scene %ux%u -> %dx%d (graphics size)", gw, gh, w, h); else LOG("mtf size: scene %ux%u kept (graphics size %dx%d is not smaller)", gw, gh, w, h); }
    if (!smaller) return;
    uint16_t nw = (uint16_t)w, nh = (uint16_t)h;
    memcpy((void *)(uintptr_t)(rec + R_WIDTH), &nw, 2); memcpy((void *)(uintptr_t)(rec + R_HEIGHT), &nh, 2);
    r[9] = nw;   // w9 (a 32-bit write clears the upper half): the width the rest of the function compares and uses
}

__attribute__((constructor)) static void mtf_size_attach(void) { gate_obs_set(OBS_MTF_SIZE, mtf_size_gate); }
