// The controller applet's join screen (nn::hid::ShowControllerSupport), pure so tests/t_hid.c checks it without controllers: the game's ControllerSupportArg and the rules by
// which controllers become its players. The front end draws the screen and feeds it the controllers' L, R, A and B (input_android.c).
//
// ControllerSupportArg, 0x430 bytes, as the SDK's ShowControllerSupportCore pushes it to the applet (Orbitals' SDK 21.4.2 sdk+0x73ec00; SetDefault sdk+0x73f250 and
// SetExplainText sdk+0x73f040 give the fields): +0 player count min (s8, default 0), +1 max (s8, 4), +2 take over the connection (1), +3 left justify (1), +4 permit dual
// Joy-Con (1), +5 single mode (0), +6 identification colour on (0), +7 eight colours of 4 bytes (R, G, B, A), +0x27 explain text on (0), +0x28 eight texts of 0x81 bytes (UTF-8,
// 0x80 and a NUL). The result is { playerCount, selectedId, code }: code 0 OK, 2 → 2202-3102, anything else → canceled (2202-3101).
//
// The rules, as the console's applet: the players are numbered in the order the controllers press L and R together (held at once, in either order): the first is player 1.
// With take over, the controllers already on players the game reads start on them provisionally (left justify: numbered from player 1 without gaps; without it they keep
// their numbers), so A keeps them as they are; the first L + R clears them and the numbering starts again from that controller. A on a joined controller ends it once at least
// min (and at least one) controllers have joined; B cancels it only from player 1's controller (on a console the others cannot), and so does the front end (Back, the screen's
// Cancel). A controller that disconnects leaves its slot (left justify: the ones after it move up). Single mode is one player. The joined controllers become the players at
// the end; the others are not connected.
#pragma once
#include <stdint.h>
#include <string.h>
#include "dm_input.h"

typedef struct {
    int min, max, take_over, left_justify, permit_dual, single, color_on, text_on;
    uint8_t color[DM_PLAYERS][4];
    char text[DM_PLAYERS][0x81];
} ctrl_arg;
enum { CTRL_ARG_SIZE = 0x430 };

static void ctrl_arg_parse(ctrl_arg *a, const uint8_t *b) {   // b: the game's argument (CTRL_ARG_SIZE bytes), or NULL for SetDefault's values
    memset(a, 0, sizeof *a);
    if (!b) { a->max = 4; a->take_over = a->left_justify = a->permit_dual = 1; return; }
    a->min = (int8_t)b[0]; a->max = (int8_t)b[1];
    a->take_over = b[2] != 0; a->left_justify = b[3] != 0; a->permit_dual = b[4] != 0; a->single = b[5] != 0; a->color_on = b[6] != 0;
    memcpy(a->color, b + 7, sizeof a->color);
    a->text_on = b[0x27] != 0;
    for (int i = 0; i < DM_PLAYERS; i++) { memcpy(a->text[i], b + 0x28 + i * 0x81, 0x80); a->text[i][0x80] = 0; }
}

enum { JOIN_KEYS = 16 };   // controllers whose L / R the screen follows at once
typedef struct {
    ctrl_arg a;
    int max, min;                          // the bounds in force: max 1..DM_PLAYERS (1 in single mode), 0 <= min <= max
    const void *player[DM_PLAYERS];        // the controller joined on each player, NULL: open
    struct { const void *o; int l, r; } k[JOIN_KEYS];
    int done;                              // 0 open, 1 OK, -1 canceled
    int provisional;                       // the players are the taken-over ones until the first L + R
} pad_join;

static int pad_join_count(const pad_join *j) { int n = 0; for (int p = 0; p < DM_PLAYERS; p++) n += j->player[p] != NULL; return n; }
static int pad_join_of(const pad_join *j, const void *o) { for (int p = 0; o && p < DM_PLAYERS; p++) if (j->player[p] == o) return p; return -1; }
static void pad_join_justify(pad_join *j) {   // left justify: the joined players numbered from 1 without gaps, in their order
    if (!j->a.left_justify) return;
    int n = 0; for (int p = 0; p < DM_PLAYERS; p++) if (j->player[p]) { const void *o = j->player[p]; j->player[p] = NULL; j->player[n++] = o; }
}
static int pad_join_can_end(const pad_join *j) { int n = pad_join_count(j); return n >= j->min && n >= 1; }
static void pad_join_finish(pad_join *j) { if (!j->done && pad_join_can_end(j)) j->done = 1; }   // A on a joined controller, or the screen's own OK (a touch)
// held[p]: the controller on player p before the screen (NULL: none); usable: the players the game reads (dm_pad_usable)
static void pad_join_start(pad_join *j, const ctrl_arg *a, const void *const held[DM_PLAYERS], uint32_t usable) {
    memset(j, 0, sizeof *j); j->a = *a;
    j->max = a->single ? 1 : a->max < 1 ? 1 : a->max > DM_PLAYERS ? DM_PLAYERS : a->max;
    j->min = a->min < 0 ? 0 : a->min > j->max ? j->max : a->min;
    if (a->take_over) {
        int n = 0;
        for (int p = 0; p < DM_PLAYERS; p++) if (held && held[p] && usable >> p & 1) {
            int q = a->left_justify ? n : p;
            if (q < j->max) { j->player[q] = held[p]; n++; }
        }
        j->provisional = n > 0;
    }
}
// A controller's L, R, A or B (Switch buttons, DM_BTN_*) going down or up. Returns the player a controller joined on, else -1
static int pad_join_key(pad_join *j, const void *o, uint64_t button, int down) {
    if (j->done || !o) return -1;
    int s = -1, freek = -1;
    for (int i = 0; i < JOIN_KEYS; i++) { if (j->k[i].o == o) s = i; else if (!j->k[i].o && freek < 0) freek = i; }
    if (s < 0 && (button == DM_BTN_L || button == DM_BTN_R)) { if (freek < 0) return -1; s = freek; j->k[s].o = o; j->k[s].l = j->k[s].r = 0; }
    if (button == DM_BTN_L) j->k[s].l = down;
    if (button == DM_BTN_R) j->k[s].r = down;
    if ((button == DM_BTN_L || button == DM_BTN_R) && down && j->k[s].l && j->k[s].r && (j->provisional || pad_join_of(j, o) < 0)) {
        if (j->provisional) { memset(j->player, 0, sizeof j->player); j->provisional = 0; }   // the first L + R: the numbering starts again from this controller
        for (int p = 0; p < j->max; p++) if (!j->player[p]) { j->player[p] = o; return p; }
        return -1;   // every player taken
    }
    if (button == DM_BTN_A && down && pad_join_of(j, o) >= 0) pad_join_finish(j);
    if (button == DM_BTN_B && down && pad_join_of(j, o) == 0) j->done = -1;   // only player 1's controller
    return -1;
}
static void pad_join_gone(pad_join *j, const void *o) {   // a controller disconnected
    int p = pad_join_of(j, o); if (p >= 0) { j->player[p] = NULL; pad_join_justify(j); }
    for (int i = 0; i < JOIN_KEYS; i++) if (j->k[i].o == o) j->k[i].o = NULL;
}
static void pad_join_cancel(pad_join *j) { if (!j->done) j->done = -1; }
