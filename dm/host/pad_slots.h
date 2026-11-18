// Player slots of the physical controllers (front_mac.m; pure, so tests/t_hid.c checks it without controllers): each controller holds one slot, slot p is player p + 1
// (hle_hid.c dm_player_npad gives its npad). A connecting controller takes, in this order: a free slot whose last holder had its name (the same controller reconnected gets its
// player back; GameController gives a controller no identity across a reconnect, so two controllers of one model may trade places), player 1 when it is free, the lowest slot
// no controller has held yet (so a slot stays kept for a controller that went away while others are in use), then the lowest free slot. With every slot held it gets none (-1).
// A disconnected controller frees its slot and leaves its name on it. The owner is any pointer that stays the same while the controller is connected (the front end keeps the
// GCController); a holder that never disconnects (the keyboard with DM_KBD_PLAYER=own) takes slot 0 first and keeps it.
// pad_slot_first makes a controller player 1 (the Android launcher's choice, PadOrder.java): it takes a slot if it has none, then trades it with player 1's holder, which
// moves to the slot it had (or, holding none before, the new slot it took); the names go with them.
// pressed: the time of each slot's last button press (pad_slot_press; 0: none since it connected), which pad_slot_fill picks by.
#pragma once
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "dm_input.h"

typedef struct { const void *owner[DM_PLAYERS]; char last[DM_PLAYERS][64]; uint64_t pressed[DM_PLAYERS]; } pad_slots;

static int pad_slot_of(const pad_slots *s, const void *owner) {
    for (int i = 0; owner && i < DM_PLAYERS; i++) if (s->owner[i] == owner) return i;
    return -1;
}
static int pad_slot_take(pad_slots *s, const void *owner, const char *name) {
    int p = pad_slot_of(s, owner); if (p >= 0 || !owner) return p;
    if (!name) name = "";
    for (int i = 0; p < 0 && *name && i < DM_PLAYERS; i++) if (!s->owner[i] && !strcmp(s->last[i], name)) p = i;
    if (p < 0 && !s->owner[0]) p = 0;
    for (int i = 0; p < 0 && i < DM_PLAYERS; i++) if (!s->owner[i] && !s->last[i][0]) p = i;
    for (int i = 0; p < 0 && i < DM_PLAYERS; i++) if (!s->owner[i]) p = i;
    if (p < 0) return -1;
    s->owner[p] = owner; snprintf(s->last[p], sizeof s->last[p], "%s", name); s->pressed[p] = 0;
    return p;
}
static int pad_slot_free(pad_slots *s, const void *owner) {
    int p = pad_slot_of(s, owner); if (p >= 0) { s->owner[p] = NULL; s->pressed[p] = 0; }
    return p;
}
static void pad_slot_press(pad_slots *s, const void *owner, uint64_t t) {   // a button press of the controller at time t (any clock that only goes forward)
    int p = pad_slot_of(s, owner); if (p >= 0) s->pressed[p] = t ? t : 1;
}
static int pad_slot_first(pad_slots *s, const void *owner, const char *name, int *moved_from) {   // 0, or -1 with every slot held; *moved_from: the slot player 1's old holder went to (-1: none moved)
    if (moved_from) *moved_from = -1;
    int p = pad_slot_take(s, owner, name); if (p <= 0) return p;
    const void *o = s->owner[0]; char l[64]; memcpy(l, s->last[0], sizeof l); uint64_t t = s->pressed[0];
    s->owner[0] = s->owner[p]; memcpy(s->last[0], s->last[p], sizeof l); s->pressed[0] = s->pressed[p];
    s->owner[p] = o; memcpy(s->last[p], l, sizeof l); s->pressed[p] = t;
    if (o && moved_from) *moved_from = p;
    return 0;
}
// pad_slot_fill: a controller on a player the game does not read (bit clear in usable: its npad is not supported) moves to the lowest free player the game reads. On a Switch
// the controller applet hands out the players in the order the controllers press a button; here the one that pressed last moves (only: that controller alone, the front end's
// call on its press; NULL: whichever pressed last, the lowest slot when none pressed). A Bluetooth controller can come back as a new device while the old one is still listed
// (Android: an Xbox controller paired during play was device 12 on player 2, then device 13 on player 3, then device 12 went), and a two-player game (Orbitals) never reads
// player 3. Returns the slot it went to (-1: nothing moved) and *from the slot it left; its name and press time go with it
static int pad_slot_fill(pad_slots *s, uint32_t usable, const void *only, int *from) {
    if (from) *from = -1;
    int to = -1, src = -1;
    for (int i = 0; to < 0 && i < DM_PLAYERS; i++) if (usable >> i & 1 && !s->owner[i]) to = i;
    for (int i = 0; to >= 0 && i < DM_PLAYERS; i++)
        if (!(usable >> i & 1) && s->owner[i] && (!only || s->owner[i] == only) && (src < 0 || s->pressed[i] > s->pressed[src])) src = i;
    if (src < 0) return -1;
    s->owner[to] = s->owner[src]; memcpy(s->last[to], s->last[src], sizeof s->last[to]); s->pressed[to] = s->pressed[src];
    s->owner[src] = NULL; s->last[src][0] = 0; s->pressed[src] = 0;
    if (from) *from = src;
    return to;
}
// pad_slot_back: the slot p a controller just left (pad_slot_free) goes to the controller with the same name on a higher slot that connected less than window ago (since[q]:
// when slot q's holder connected, on the caller's clock), as the same controller come back. Android lists a Bluetooth controller again as a new device while the old one is still
// there (an Xbox controller: device 18 on player 2, device 19 on player 3, then device 18 went), and Android's descriptor does not tell them apart: a controller without a
// unique id gets a new one while the old device is listed. Returns the slot it came from, -1 when none; its name and press time go with it, slot p's since is its
static int pad_slot_back(pad_slots *s, int p, uint64_t *since, uint64_t now, uint64_t window) {
    if (p < 0 || p >= DM_PLAYERS || s->owner[p] || !s->last[p][0]) return -1;
    int q = -1;
    for (int i = p + 1; q < 0 && i < DM_PLAYERS; i++) if (s->owner[i] && !strcmp(s->last[i], s->last[p]) && now - since[i] < window) q = i;
    if (q < 0) return -1;
    s->owner[p] = s->owner[q]; s->pressed[p] = s->pressed[q]; since[p] = since[q];
    s->owner[q] = NULL; s->last[q][0] = 0; s->pressed[q] = 0;
    return q;
}
static void pad_slot_swap(pad_slots *s, int a, int b) {   // the holders of slots a and b trade places (either may be empty), with their names and press times
    if (a < 0 || b < 0 || a >= DM_PLAYERS || b >= DM_PLAYERS || a == b) return;
    const void *o = s->owner[a]; char l[64]; memcpy(l, s->last[a], sizeof l); uint64_t t = s->pressed[a];
    s->owner[a] = s->owner[b]; memcpy(s->last[a], s->last[b], sizeof l); s->pressed[a] = s->pressed[b];
    s->owner[b] = o; memcpy(s->last[b], l, sizeof l); s->pressed[b] = t;
}
static uint32_t pad_slot_mask(const pad_slots *s) {   // bit p: slot p is held
    uint32_t m = 0; for (int i = 0; i < DM_PLAYERS; i++) if (s->owner[i]) m |= 1u << i;
    return m;
}
