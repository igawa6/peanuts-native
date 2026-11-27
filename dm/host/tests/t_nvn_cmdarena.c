// PEANUTS begin: rooms for empty-pool command memory grants (dm/gfx/nvn/nvn_cmdarena.h; hle_nvn.c cmdarena_grant / cmdarena_end / cmdarena_reopen). Super Mario Odyssey
// grants a null pool of 512 bytes before each display list once its display-list heap is full; a 4 KB segment for each took 504 MB in six minutes in the Wooded Kingdom
// (GitHub issue #807). The command buffers here are driven the way hle_nvn.c drives the arena: grant, records, END, with the command buffer initialized again before a list
// (Odyssey's case), lists that overflow their room into a host segment, many command buffers granting in turn, and a list recorded without a grant of its own.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../gfx/nvn/nvn_cmdarena.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

enum { CHUNK = 256 << 10, ROOM = 4 << 10, JUMP = 16 };
typedef struct { uint8_t *cur, *end; int recording; } cmdbuf;   // as cmd_t holds them
static nvn_cmdarena A; static uint8_t *made[4096]; static int nmade; static unsigned chunks, back;
static uint8_t hostseg[64 << 10];   // a host segment for lists that outgrow their room (hostseg_take); never checked
static void grant(cmdbuf *c) {   // cmdarena_grant
    int lane = nvn_cmdarena_lane_pick(&A); uint8_t *p = nvn_cmdarena_take(&A, lane, ROOM);
    if (!p) { uint8_t *m = made[nmade++] = malloc(CHUNK); chunks++; nvn_cmdarena_add(&A, lane, m, CHUNK); p = nvn_cmdarena_take(&A, lane, ROOM); }
    c->cur = p; c->end = p + ROOM;
}
static void end_rec(cmdbuf *c) { c->recording = 0; if (nvn_cmdarena_end(&A, c->cur, c->end)) { c->end = c->cur; back++; } }   // cmdarena_end
// a record of n bytes, as cmd_alloc_keep: OP_RESERVE (16) kept after it, else a JUMP at the write pointer and the rest in the host segment
static uint8_t *rec(cmdbuf *c, size_t n, int tag) {
    if (c->cur + n + JUMP > c->end) {
        int lane = c->recording == 2 && n + JUMP <= ROOM && c->cur && c->cur == c->end ? nvn_cmdarena_lane_at(&A, c->end) : -1;
        if (lane >= 0) {   // cmdarena_reopen
            uint8_t *p = nvn_cmdarena_take(&A, lane, ROOM); c->cur = p; c->end = p + ROOM;
        } else {
            if (c->recording == 1) memset(c->cur, 0x7A, JUMP);   // the JUMP linking the list to the host segment
            c->cur = hostseg; c->end = hostseg + sizeof hostseg;
        }
    }
    uint8_t *p = c->cur; memset(p, tag, n); c->cur += n; c->recording = 1; return p;
}
static uint8_t *list(cmdbuf *c, size_t n, int tag) { c->recording = 2; uint8_t *p = rec(c, n, tag); end_rec(c); return p; }   // one record and its END, as one block

int main(void) {
    // 1. 2000 lists of 200 to 600 bytes, the command buffer initialized again before half of them: no list overwritten, about their own bytes each
    cmdbuf c = {0};
    uint8_t *lists[2000]; size_t lens[2000];
    for (int i = 0; i < 2000; i++) {
        if (i & 1) c = (cmdbuf){0};
        lens[i] = (200 + (size_t)(i * 37 % 400) + 7) & ~(size_t)7;
        grant(&c); lists[i] = list(&c, lens[i], 1 + i % 250);
    }
    int broken = 0; for (int i = 0; i < 2000; i++) for (size_t k = 0; k < lens[i]; k++) if (lists[i][k] != 1 + i % 250) { broken++; break; }
    CHECK(!broken, "%d of 2000 lists were overwritten by a later grant", broken);
    CHECK(chunks <= 4, "%u chunks of 256 KB for 2000 lists of about 400 bytes (a room each would take 32)", chunks);

    // 2. a list whose record does not fit its room JUMPs into a host segment; the next grant must not land on that JUMP
    cmdbuf d = {0}; grant(&d); uint8_t *room = d.cur;
    d.recording = 2; rec(&d, 64, 0x31); rec(&d, ROOM + 256, 0x32); end_rec(&d);   // a 4,352-byte uniform update, as t_nvn 5b''
    uint8_t *jump = room + 64; int jump_ok = 1;
    cmdbuf e = {0}; grant(&e);
    CHECK(e.cur >= room + ROOM || e.cur + ROOM <= room, "the grant after an overflowing list overlaps its room at %ld (its JUMP is at 64)", (long)(e.cur - room));
    list(&e, 512, 0x33);
    for (int k = 0; k < JUMP; k++) jump_ok &= jump[k] == 0x7A;
    CHECK(jump_ok && room[0] == 0x31, "the overflowing list's JUMP or first record was overwritten by the next list");

    // 3. 80 command buffers granting in turn before any records (threads): each a room of its own, all kept, chunks bounded by 4 KB a grant
    unsigned ch0 = chunks; cmdbuf many[80] = {{0}}; uint8_t *first[80];
    for (int r = 0; r < 20; r++) {
        for (int i = 0; i < 80; i++) grant(&many[i]);
        for (int i = 0; i < 80; i++) { first[i] = list(&many[i], 300, i + 1); }
        int bad = 0; for (int i = 0; i < 80; i++) for (int k = 0; k < 300; k++) if (first[i][k] != i + 1) { bad++; break; }
        CHECK(!bad, "round %d: %d of 80 interleaved lists overwritten", r, bad);
    }
    CHECK(chunks - ch0 <= 1600 * ROOM / CHUNK + NVN_CMDARENA_LANES, "1,600 interleaved grants took %u chunks (at most a 4 KB room each: %d)", chunks - ch0, 1600 * ROOM / CHUNK + NVN_CMDARENA_LANES);

    // 3b. Odyssey's pattern: a grant for a second list before the first one ends, 2000 times: both rooms go back (lanes), and the lists keep their bytes
    unsigned ch1 = chunks, b0 = back; cmdbuf p1 = {0}, p2 = {0}; uint8_t *pl[4000];
    for (int i = 0; i < 2000; i++) {
        p1 = (cmdbuf){0}; p2 = (cmdbuf){0}; grant(&p1); grant(&p2);
        pl[2 * i] = list(&p1, 400, 1 + (2 * i) % 250); pl[2 * i + 1] = list(&p2, 400, 1 + (2 * i + 1) % 250);
    }
    int bad2 = 0; for (int i = 0; i < 4000; i++) for (int k = 0; k < 400; k++) if (pl[i][k] != 1 + i % 250) { bad2++; break; }
    CHECK(!bad2, "%d of 4000 paired lists overwritten", bad2);
    CHECK(back - b0 == 4000 && chunks - ch1 <= 4000 * 400 / CHUNK + 2 * NVN_CMDARENA_LANES, "paired grants: %u of 4000 rooms given back, %u chunks", back - b0, chunks - ch1);

    // 4. a second list on the same command buffer without a grant: a new room where the tail went back; after another grant in between, not there
    cmdbuf f = {0}; grant(&f); uint8_t *l1 = list(&f, 400, 0x41), *l2 = list(&f, 400, 0x42);
    CHECK(l2 == l1 + 400, "a list recorded without a grant did not reopen the room after the first (%ld)", (long)(l2 - l1));
    cmdbuf g = {0}; grant(&g); uint8_t *gl = list(&g, 200, 0x43); uint8_t *l3 = list(&f, 400, 0x44);
    CHECK(l3 < gl || l3 >= gl + 200, "a list recorded without a grant overwrote another command buffer's list");
    int ok = 1; for (int k = 0; k < 400; k++) ok &= l1[k] == 0x41 && l2[k] == 0x42; for (int k = 0; k < 200; k++) ok &= gl[k] == 0x43;
    CHECK(ok, "lists overwritten in the reopen case");

    while (nmade) free(made[--nmade]);
    if (fails) { printf("t_nvn_cmdarena: %d failures\n", fails); return 1; }
    printf("t_nvn_cmdarena: ok\n"); return 0;
}
// PEANUTS end
