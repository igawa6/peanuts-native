// PEANUTS begin: host chunks for command memory grants from an empty pool (GitHub issue #807)
// A game whose own allocator for command memory comes back empty grants a null pool before it records (hle_nvn.c, "Host-supplied segment"). Such a recording has no guest
// bytes the game could give out again, so the host never reclaims it. Each grant gets a room of its own, carved from a shared chunk at the free start, which moves past the
// whole room at the grant: nothing else is handed those bytes, whoever writes in them. When a recording ends inside the room handed out last (the free start is still that
// room's end), the room's unused tail goes back: the free start moves back to the recording's end, and the caller cuts the command buffer's segment there, so the bytes
// after the END are no longer its to write. A list then takes its own bytes (Super Mario Odyssey's display lists: about 400 on average) instead of a 4 KB segment each.
// A recording that outgrows its room JUMPs into a host segment and keeps the whole room (its JUMP is in it). A room is given back only while it is the last one
// handed out in its lane (see the lanes below); hle_nvn.c keeps one arena per thread. Assumption: only the command
// buffer object that asked for a room writes in it; a copy of that object that wrote past the END of a room given back would overwrite the next room (it would be writing
// into memory the game no longer owns on hardware). Odyssey initializes the object again before every grant (hle_nvn.c counts it in its log line), which is safe.
// Free of NVN; not thread-safe (the caller keeps one arena per thread or holds a lock), so dm/host/tests/t_nvn_cmdarena.c can check it.
#ifndef NVN_CMDARENA_H
#define NVN_CMDARENA_H
#include <stddef.h>
#include <stdint.h>

// Lanes: a grant made while another room is still open takes its room in another lane, so both can give their tails back (Odyssey grants for a second display list
// before the first one ends, on the same thread, for about a third of its lists). With every lane's room open, the lane granted longest ago is used again: its open room
// is kept whole.
#define NVN_CMDARENA_LANES 4
typedef struct { uint8_t *cur, *end, *room; uint64_t seq; } nvn_cmdarena_lane;   // [cur, end): the free bytes of the lane's chunk; room: its open room's start (NULL: none)
typedef struct { nvn_cmdarena_lane l[NVN_CMDARENA_LANES]; uint64_t seq; } nvn_cmdarena;

// The lane for the next grant: one with no open room (the first such), else the one granted longest ago
static inline int nvn_cmdarena_lane_pick(const nvn_cmdarena *a) {
    int old = 0;
    for (int i = 0; i < NVN_CMDARENA_LANES; i++) { if (!a->l[i].room) return i; if (a->l[i].seq < a->l[old].seq) old = i; }
    return old;
}
// A room of `room` bytes at the lane's free start: its start, or NULL when the lane's chunk has too little left (the caller adds a chunk with nvn_cmdarena_add and asks again)
static inline uint8_t *nvn_cmdarena_take(nvn_cmdarena *a, int lane, size_t room) {
    nvn_cmdarena_lane *l = &a->l[lane];
    if (!l->cur) return NULL;
    uint8_t *p = (uint8_t *)(((uintptr_t)l->cur + 7) & ~(uintptr_t)7);
    if (p > l->end || (size_t)(l->end - p) < room) return NULL;
    l->cur = p + room; l->room = p; l->seq = ++a->seq; return p;
}
// A new chunk [mem, mem + n) for the lane: what was left of its old one is dropped
static inline void nvn_cmdarena_add(nvn_cmdarena *a, int lane, uint8_t *mem, size_t n) { a->l[lane] = (nvn_cmdarena_lane){mem, mem + n, NULL, a->l[lane].seq}; }
// A recording ended at write pointer wp in the segment that ends at seg_end: if that segment is a lane's open room, the room's tail after wp goes back. 1: given back
// (the caller ends the command buffer's segment at wp)
static inline int nvn_cmdarena_end(nvn_cmdarena *a, uint8_t *wp, const uint8_t *seg_end) {
    if (!wp) return 0;
    for (int i = 0; i < NVN_CMDARENA_LANES; i++) {
        nvn_cmdarena_lane *l = &a->l[i];
        if (l->room && seg_end == l->cur && wp >= l->room && wp <= l->cur) { l->cur = wp; l->room = NULL; return 1; }
    }
    return 0;
}
// The lane whose free start is p with no open room (a command buffer whose room went back at p, asking again without a grant), or -1
static inline int nvn_cmdarena_lane_at(const nvn_cmdarena *a, const uint8_t *p) {
    for (int i = 0; i < NVN_CMDARENA_LANES; i++) if (!a->l[i].room && a->l[i].cur && a->l[i].cur == p) return i;
    return -1;
}
#endif
// PEANUTS end
