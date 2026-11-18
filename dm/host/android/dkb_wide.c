// SPDX-License-Identifier: GPL-3.0-or-later
// Donkey Kong Bananza (Switch 2), main v458752 (build ID e25c90c1c136638885659fd55bf8a03b): 3D cameras and the 2D layer that follow the picture's shape.
//
// The game draws a 16:9 frame whatever the screen; Game settings > Aspect ratio / Screen shape fit that frame into DM_VK_PICTURE_ASPECT=<w>:<h> (vka_present.c). For the scene to be neither
// stretched nor cropped, the cameras must use the picture's ratio and the HUD must stay in the middle 16:9. Shared parts (the shape, the table of converted lenses, the row squeeze) are in
// wide_shape.h; this file is the game's sites (six gates in patches.py, OBS_DKB_WIDE; the kind in the parameter's bits from 5 up, the register of a camera site in bits 0-4): the camera
// and the HUD projection here, and for a picture narrower than 16:9 the layouts' panes ("The layouts' panes" below):
//   Camera  main+0x45e238 (ldr s0,[x19,#0x104]), in the projection update of the scene camera's updater (x19): near +0xf8, far +0xfc, aspect +0x100, fovy in degrees +0x104. It turns them
//           into a sead::PerspectiveProjection (radians at +0xa0, sin, cos, tan at +0xac, aspect at +0xb0; the next block, +0x1c0..+0x1e0, is the culling frustum made from the same
//           numbers). The gate is before the first of the two reads, so the callback rewrites the INPUTS in the updater, once:
//             wider than 16:9  (Hor+)  the aspect becomes the picture's; the vertical field of view stays;
//             narrower         (Vert+) the aspect becomes the picture's, and the fovy's tangent grows by (16/9) / aspect, so the horizontal reach stays what a 16:9 picture shows.
//           Only an aspect within 0.01 of 16:9 is touched (a split view or an offscreen lens has another one). The game's constructor (main+0x462504, default 16:9) and its aspect setter
//           (main+0x45d5d4) write the 16:9 in again whenever they run; the converted-lens table (the fovy written, per updater) keeps a Vert+ fovy from being scaled twice then.
//   HUD     main+0x45f9e0 and main+0x803c9c, the instruction after the matrix copy main+0xb1314 in the two layout projection setups (main+0x45f840 and main+0x803b60, the
//           same code twice; the HUD's draw info is the second's; x0 the draw info, the matrix at +0x10, 4x4 floats row by row). The game's layouts (HUD, menus) are drawn in
//           perspective, not orthographically: each setup builds a
//           sead::PerspectiveProjection (fovy 15 degrees, near 1, far 10000, aspect = the layout rect's 1920 / 1080) and a camera 4101.7 units away, so the 1920x1080 plane
//           fills the picture. The layout shader (main's own, instanced: one draw per group of panes) gets that projection times the view and pane matrices as its
//           uniform block (seen with DM_VK_UB_PEEK: m00 4.27261, m11 7.59575, m32 -1). Its x row (wider picture: x 16/9 over the picture's ratio) or y row (narrower: the
//           ratio over 16/9) is scaled, which keeps the layer in the middle 16:9 of the picture. Only a perspective matrix with the 16:9 shape (m11 / m00 within 0.01 of
//           16/9, m32 -1) is touched, so a scaled one (its shape is then the picture's) is not scaled twice. A setup runs a few times a run (a layout system made).
//           The orthographic projections the other copies set (main+0xb1260 from the capture pane's calculation main+0xb0efc, 2/1920 x 2/1080 among them) are for panes
//           drawn into textures (captures, dialogue backgrounds, text, icon shadows): scaling them on the phone changed nothing on screen, and they are left alone.
//           The 3D fills the whole picture.
// Credit chain: the sites and the idea (camera aspect at the updater; the UI projection scaled at the copies, main+0x45f9dc among them) are the dkbwide patch set of Lucas-blip-png's dkb-pc-recomp
// (recomp/patches-dkb458752.txt, runtime/dkb_wide.c; GPL-3.0). That project widens the UI and the virtual display; this file squeezes the UI into the middle of our unchanged 16:9 frame and
// adds the Vert+ compensation for narrow pictures. See android/NOTICE.md.
//
// The picture shape is read once at start (restart to change it, as the launcher does). Not set, invalid, or within 0.02 of 16:9: the game stays as it made it.
#include "wide_shape.h"
#include <stddef.h>

#define DKB_MAIN_BUILD "e25c90c1c136638885659fd55bf8a03b"
#define K169 WS_K169
enum { C_ASPECT = 0x100, C_FOVY = 0x104, HUD_MTX = 0x10 };   // the draw info's projection, row by row: m[r][c] at +0x10 + 16r + 4c

static ws_cfg_t g_cfg = WS_CFG_INIT;
static ws_seen_t g_seen;   // updaters whose fovy we scaled: where, and the fovy (degrees) we wrote
static _Atomic unsigned g_fixes, g_huds;

static double aspect(void) { return ws_aspect(&g_cfg, DKB_MAIN_BUILD, "dkb"); }

// One camera updater, in place
static void camera(uint64_t obj) {
    double a = aspect();
    if (!a || !obj || (obj & 3)) return;
    float asp = ws_rdf(obj + C_ASPECT), fov = ws_rdf(obj + C_FOVY);
    if (!ws_close_to(asp, K169, 0.01)) return;
    if (!(fov > 1.0f && fov < 170.0f)) return;   // not a lens the game made (degrees)
    if (a < K169 && !ws_seen_has(g_seen, obj, fov)) {
        double half = ws_vertplus_half((float)tan(fov * M_PI / 360.0), a);   // under 172 degrees
        float nf = (float)(2 * half * 180.0 / M_PI);
        if (atomic_fetch_add_explicit(&g_fixes, 1, memory_order_relaxed) == 0)
            LOG("dkb wide: first camera fovy %.2f -> %.2f deg vertical (aspect %.4f -> %.4f)", fov, nf, asp, a);
        ws_wrf(obj + C_FOVY, nf);
        ws_seen_set(g_seen, obj, nf);
    }
    ws_wrf(obj + C_ASPECT, (float)a);
}

// The layout system's draw info after its projection was set (x0); the matrix is a copy of the game's, row by row
static void hud(uint64_t dinfo) {
    double a = aspect();
    if (!a || !dinfo || (dinfo & 3)) return;
    uint64_t m = dinfo + HUD_MTX;
    float m00 = ws_rdf(m), m11 = ws_rdf(m + 20), m32 = ws_rdf(m + 56), m33 = ws_rdf(m + 60);
    if (!(m00 > 0 && m11 > 0) || m32 != -1.0f || m33 != 0.0f || !ws_close_to(m11 / m00, K169, 0.01)) return;   // not the 16:9 layout perspective (or scaled already)
    float q = ws_squeeze(a);
    int row = a > K169 ? 0 : 1;
    for (int k = 0; k < 4; k++) ws_wrf(m + 16 * row + 4 * k, ws_rdf(m + 16 * row + 4 * k) * q);
    if (atomic_fetch_add_explicit(&g_huds, 1, memory_order_relaxed) == 0)
        LOG("dkb wide: layout perspective (m00 %.5f, m11 %.5f) -> %s row squeezed into the middle 16:9 (x%.4f)", m00, m11, row ? "y" : "x", q);
}

// ---- The layouts' panes on a picture narrower than 16:9 ----
// The squeeze above leaves a 16:9 layer in the middle of a taller picture: the 1920x1080 layout plane is then one part of a 1920 x (1080 x (16/9) / aspect) canvas (1920x1440 at 4:3), and
// what the layouts place for 16:9 stops at the middle rows: black screens, fades and menu backdrops end there (the game's clear colour shows above and below), and the HUD's corners
// and the menus' header and footer bands sit inside the picture. Two rules on nn::ui2d's panes as a layout is built (kinds 2 and 3) put them where a layout made for that canvas has them:
//   Tall    main+0x861a00, the instruction after the pane constructor (main+0x8615e0) stores a pane's height (str s0,[x19,#0x54] at main+0x8619fc; the parts path main+0x861bc4 comes back to
//           the same store). x19 the pane (translate +0x30, size +0x50 / +0x54), x23 its record in the layout file (the section tag first). A picture pane ("pic1") of the layout's
//           own size at its centre is a full-screen one (P_Fade_00, P_BlackBg_00, P_Bg_00, P_PauseBg_00 ...: 65 of the 194 large panes a cold start to Options builds): its height is
//           multiplied by (16/9) / aspect, so it covers the canvas.
//   Edge    main+0x850d3c (nn::ui2d::Layout::Build's mov w0,#1) and main+0x13cb50 (the game's own layout class, ldp x20,x19,[sp,#0x1e0]): the two places a layout's build ends, the
//           layout in x19, its root pane at +0x18. Whether a pane is near an edge depends on where its ancestors are, so this works on the finished tree: from the root, through panes
//           on the layout's centre line (their y and their ancestors' add up to at most 4 units) without rotation or scale, the first pane on each path whose y is at least 120 units
//           from the line moves outward by the canvas's extra half height (180 units at 4:3). Nothing below a moved pane is looked at, nor below one off the line, scaled or rotated.
//           y is the translate plus the parent-relative origin (byte +0x5b bits 6-7: half the parent's height up or down), as the game's matrix routine main+0x862b5c reads it. The
//           write is the translate's y and the matrix-dirty bit (+0x58 bit 4). A part's layout is built by a nested call whose root already has a parent: it is left to the outer
//           build, which ends last. A layout is not moved twice (both sites, or a second pass): the tree's state after a walk is remembered.
//           g_edge_keep names the groups the rule would pull apart: pictures made of pieces above and below their own centre (the controller diagrams' callout lines, the stage cards,
//           the co-op and guide pages). A pane of one of those names, and everything under it, stays as authored, except a child g_edge_free names for that group: an edge
//           item of its own that the group only holds (the title and pause layout's button hint at the top, "DK Artist"), which is looked at like any other pane.
// Pane layout (main e25c90c1): own list node +0x08 / +0x10, parent +0x18, child list head +0x20 (prev) / +0x28 (next; a child is its node - 8, the list ends at pane + 0x20), translate
// +0x30, rotate +0x3c, scale +0x48, size +0x50, flags +0x58, name +0xb0 (24 bytes).
// Wider than 16:9 nothing here runs (the rules are vertical only). DM_DKB_PANES=0 leaves the panes as authored (the layer in the middle 16:9, as before), =log lists every change.
#define LAY_W 1920.0f
#define LAY_H 1080.0f
enum { P_LINK = 0x18, P_HEAD = 0x20, P_TX = 0x30, P_TY = 0x34, P_W = 0x50, P_H = 0x54, P_FLAGS = 0x58, P_NAME = 0xb0, PANE_DIRTY = 0x10, LAYOUT_ROOT = 0x18,
       EDGE_LEAST = 120, EDGE_DEPTH = 48, EDGE_PANES = 8192, EDGE_MOVES = 512, EDGE_ROOTS = 256 };
typedef struct { uint64_t parent, head_prev, first; float t[3], r[3], s[2], size[2]; uint8_t fl[4]; } pane_t;   // a pane from +0x18 to +0x5c
_Static_assert(offsetof(pane_t, t) == P_TX - P_LINK && offsetof(pane_t, size) == P_W - P_LINK && offsetof(pane_t, fl) == P_FLAGS - P_LINK, "pane offsets");
static const char *const g_edge_keep[] = {
    "N_1PBothLine_00", "N_1PProLine_00", "N_1PLeftLine_00", "N_1PRightLine_00", "N_2PBothLine_00", "N_2PProLine_00", "N_2PLeftLine_00", "N_2PRightLine_00", "N_2P_00", "N_OneHandText_00",
    "N_1PControllerBoth_00", "N_2PControllerBoth_00",                                                               // the controller diagrams (1P / 2P Controls)
    "N_Lake_00", "N_Wild_00", "N_Forest_00", "N_Melt_00", "N_Ice_00", "N_Storm_00", "N_Desert_00", "N_Core_00",    // the stage cards
    "N_Guide_00", "N_WindowGuide_00", "N_InOut_00", "N_Contents_00",                                                // guide, co-op, amiibo, save data and action preview groups
};
static const struct { const char *group, *pane; } g_edge_free[] = {
    { "N_Guide_00", "A_Guide_00" },                                                                                 // the button hint at the top of the title and pause layout
};
static _Atomic int g_pane_mode = -1;   // DM_DKB_PANES: 0 off, 1 on (unset), 2 on with every change logged
static _Atomic unsigned g_talls, g_edges;
static pthread_mutex_t g_edge_lock = PTHREAD_MUTEX_INITIALIZER;
static struct { uint64_t root, after; } g_edge_done[EDGE_ROOTS];   // a tree already moved: its root and the hash of its panes' y after the move
static unsigned g_edge_next;

static int pane_mode(void) {
    int m = atomic_load_explicit(&g_pane_mode, memory_order_acquire);
    if (m >= 0) return m;
    const char *e = getenv("DM_DKB_PANES");
    int bad = 0, want = !e || !*e || !strcmp(e, "1") ? 1 : !strcmp(e, "log") ? 2 : 0;
    if (e && *e && strcmp(e, "0") && strcmp(e, "1") && strcmp(e, "log")) bad = 1;
    m = -1;
    if (atomic_compare_exchange_strong_explicit(&g_pane_mode, &m, want, memory_order_acq_rel, memory_order_acquire)) {
        if (bad) LOG("[RED] DM_DKB_PANES=%s: must be 0, 1 or log; the layout panes are left as authored", e);
        return want;
    }
    return m;
}
// What a full-screen pane's height is multiplied by, or 0 when the panes are to stay as authored (not this build, no shape, 16:9 or wider, or DM_DKB_PANES=0)
static double pane_stretch(void) {
    double a = aspect();
    return a && a < K169 && pane_mode() ? K169 / a : 0;
}
static int pane_name(uint64_t pane, char name[25]) {   // printable ASCII, '?' for anything else
    if (plat_mem_read(pane + P_NAME, name, 24)) {
        strcpy(name, "?");
        return -1;
    }
    name[24] = 0;
    for (char *c = name; *c; c++) if (*c < 0x20 || *c > 0x7e || *c == '"') *c = '?';
    return 0;
}

// Kind 2: a pane whose size the constructor has just stored (x19), and its record in the layout file (x23)
static void pane_tall(uint64_t pane, uint64_t rec) {
    double k = pane_stretch();
    if (!k || !pane || !rec || (pane & 3) || (rec & 3)) return;
    char tag[4];
    float size[2], translate[2];
    if (plat_mem_read(rec, tag, sizeof tag) || memcmp(tag, "pic1", sizeof tag) ||
        plat_mem_read(pane + P_W, size, sizeof size) || plat_mem_read(pane + P_TX, translate, sizeof translate)) return;
    float w = size[0], h = size[1], tx = translate[0], ty = translate[1];
    if (!(fabsf(w - LAY_W) <= 4 && fabsf(h - LAY_H) <= 4 && fabsf(tx) <= 2 && fabsf(ty) <= 2)) return;   // (also leaves NaN)
    float nh = (float)(h * k);
    if (plat_mem_write(pane + P_H, &nh, sizeof nh)) {
        static _Atomic unsigned failed;
        if (atomic_fetch_add_explicit(&failed, 1, memory_order_relaxed) == 0)
            LOG("[RED] dkb wide: cannot write a full-screen pane's height");
        return;
    }
    unsigned n = atomic_fetch_add_explicit(&g_talls, 1, memory_order_relaxed);
    if (n == 0 || pane_mode() == 2) {
        char name[25];
        pane_name(pane, name);
        LOG("dkb wide: %s \"%s\" %gx%g -> %gx%g (covers the picture)", n ? "full-screen pane" : "first full-screen pane", name, (double)w, (double)h, (double)w, (double)nh);
    }
}

static float edge_shift(float y, float by) { return !(y >= EDGE_LEAST || y <= -EDGE_LEAST) ? 0 : y > 0 ? by : -by; }   // what is added to a pane's y: 0 leaves it (and NaN)
static int pane_plain(const pane_t *p) {   // no rotation and no scale: a pane the walk may look below
    for (int i = 0; i < 3; i++) if (!(p->r[i] > -0.01f && p->r[i] < 0.01f)) return 0;
    return p->s[0] > 0.99f && p->s[0] < 1.01f && p->s[1] > 0.99f && p->s[1] < 1.01f;
}
static int edge_kept(const char *name) {
    for (size_t i = 0; i < sizeof g_edge_keep / sizeof *g_edge_keep; i++) if (!strcmp(name, g_edge_keep[i])) return 1;
    return 0;
}
static int edge_frees(const char *group, const char *pane) {   // pane NULL: the group has a free child at all
    for (size_t i = 0; i < sizeof g_edge_free / sizeof *g_edge_free; i++)
        if (!strcmp(group, g_edge_free[i].group) && (!pane || !strcmp(pane, g_edge_free[i].pane))) return 1;
    return 0;
}
static uint64_t edge_hash(uint64_t h, const void *p, size_t n) {   // FNV-1a
    for (const uint8_t *b = p; n--; b++) h = (h ^ *b) * 0x100000001b3ull;
    return h;
}

// Kind 3: a layout whose build has just ended (x19). Every move is decided first and written only when the whole walk made sense
static void layout_built(uint64_t layout) {
    double k = pane_stretch();
    if (!k || !layout || (layout & 7)) return;
    uint64_t root;
    if (plat_mem_read(layout + LAYOUT_ROOT, &root, sizeof root)) return;
    if (!root || (root & 7)) return;
    struct { uint64_t pane, node, end; float y, h; char kept[25]; } st[EDGE_DEPTH];   // an open pane: its next child node, the node that ends its list, its place on the y line, its height,
                                                                                      // and its name when it is a kept group (only its free children are looked at)
    struct { uint64_t pane, parent; float from, to; uint8_t flags; } mv[EDGE_MOVES];
    unsigned nmv = 0, seen = 0;
    int d = 0, done = 0, rollback_failed = 0;
    const char *stop = NULL;
    uint64_t before = 0xcbf29ce484222325ull, after = before;
    float by = (float)(LAY_H * (k - 1) / 2);
    pane_t p;
    pthread_mutex_lock(&g_edge_lock);
    const size_t pane_bytes = offsetof(pane_t, fl) + sizeof p.fl;
    if (plat_mem_read(root + P_LINK, &p, pane_bytes)) { stop = "an unreadable root"; goto out; }
    if (p.parent || !pane_plain(&p) || !(p.t[1] >= -4 && p.t[1] <= 4)) goto out;   // a part's layout (the outer build ends last), or a root that is not on the line
    st[0].pane = root; st[0].node = p.first; st[0].end = root + P_HEAD; st[0].y = p.t[1]; st[0].h = p.size[1]; st[0].kept[0] = 0;
    while (d >= 0) {
        if (st[d].node == st[d].end) { d--; continue; }
        if (st[d].node < 0x1000 || (st[d].node & 7)) { stop = "a child link that is no pane"; break; }
        uint64_t pane = st[d].node - 8, next;
        if (++seen > EDGE_PANES) { stop = "more panes than one walk looks at"; break; }
        char name[25];
        if (plat_mem_read(st[d].node + 8, &next, sizeof next) ||
            plat_mem_read(pane + P_LINK, &p, pane_bytes) || pane_name(pane, name)) {
            stop = "an unreadable child pane";
            break;
        }
        st[d].node = next;
        if (p.parent != st[d].pane) { stop = "a child whose parent is another pane"; break; }
        if (st[d].kept[0] && !edge_frees(st[d].kept, name)) continue;
        unsigned v = p.fl[3] >> 6;
        float y = p.t[1] + (v == 1 ? 0.5f * st[d].h : v == 2 ? -0.5f * st[d].h : 0), line = st[d].y + y, add = 0;
        int kept = edge_kept(name), below = line >= -4 && line <= 4 && pane_plain(&p);
        if (!kept) { add = edge_shift(line, by); below = below && add == 0; }
        else below = below && edge_frees(name, NULL);
        float to = p.t[1] + add;
        before = edge_hash(edge_hash(before, &pane, 8), &p.t[1], 4);
        after = edge_hash(edge_hash(after, &pane, 8), &to, 4);
        if (add != 0) {
            if (nmv >= EDGE_MOVES) { stop = "more panes to move than one walk moves"; break; }
            mv[nmv].pane = pane; mv[nmv].parent = st[d].pane; mv[nmv].from = p.t[1]; mv[nmv].to = to; mv[nmv].flags = p.fl[0]; nmv++;
        }
        if (below && p.first != pane + P_HEAD) {
            if (d + 1 >= EDGE_DEPTH) { stop = "a tree deeper than one walk follows"; break; }
            d++; st[d].pane = pane; st[d].node = p.first; st[d].end = pane + P_HEAD; st[d].y = line; st[d].h = p.size[1];
            memcpy(st[d].kept, kept ? name : "", kept ? sizeof name : 1);
        }
    }
    if (stop || !nmv) goto out;
    for (unsigned i = 0; i < EDGE_ROOTS; i++) if (g_edge_done[i].root == root && g_edge_done[i].after == before) { done = 1; goto out; }
    for (unsigned i = 0; i < nmv; i++) {
        uint8_t flags = mv[i].flags | PANE_DIRTY;
        int wrote_y = !plat_mem_write(mv[i].pane + P_TY, &mv[i].to, sizeof mv[i].to);
        if (wrote_y && !plat_mem_write(mv[i].pane + P_FLAGS, &flags, sizeof flags)) continue;
        if (wrote_y) rollback_failed |= !!plat_mem_write(mv[i].pane + P_TY, &mv[i].from, sizeof mv[i].from);
        while (i > 0) {
            i--;
            rollback_failed |= !!plat_mem_write(mv[i].pane + P_TY, &mv[i].from, sizeof mv[i].from);
            rollback_failed |= !!plat_mem_write(mv[i].pane + P_FLAGS, &mv[i].flags, sizeof mv[i].flags);
        }
        stop = "a pane write failed";
        goto out;
    }
    g_edge_done[g_edge_next].root = root; g_edge_done[g_edge_next].after = after;
    g_edge_next = (g_edge_next + 1) % EDGE_ROOTS;
out:
    pthread_mutex_unlock(&g_edge_lock);
    if (rollback_failed) {
        LOG("[RED] dkb wide: cannot restore every pane after a failed write");
        return;
    }
    if (stop) {
        static _Atomic unsigned stopped;
        if (atomic_fetch_add_explicit(&stopped, 1, memory_order_relaxed) < 8) LOG("[RED] dkb wide: a layout's pane walk stopped (%s) after %u panes; none of its panes is moved", stop, seen);
        return;
    }
    if (done || !nmv) return;
    unsigned n = atomic_fetch_add_explicit(&g_edges, 1, memory_order_relaxed);
    if (n == 0 || pane_mode() == 2)
        LOG("dkb wide: %s with edge panes: %u moved %g units outward (y %g -> %g the first)",
            n ? "layout" : "first layout", nmv, (double)by, (double)mv[0].from, (double)mv[0].to);
    if (pane_mode() == 2)
        for (unsigned i = 0; i < nmv; i++) {
            char name[25], par[25];
            pane_name(mv[i].pane, name); pane_name(mv[i].parent, par);
            LOG("dkb wide: edge pane \"%s\" under \"%s\" y %g -> %g", name, par, (double)mv[i].from, (double)mv[i].to);
        }
}

// The gate number's upper bits (param): the site kind from bit 5 (0 camera: the register in bits 0-4, 1 HUD: x0 the draw info, 2 a pane just sized: x19 the pane and x23 its record,
// 3 a layout just built: x19)
static void dkb_wide_gate(uint64_t *r, int param) {
    if (param < 0) return;
    switch (param >> 5) {
    case 0: if ((param & 31) <= 28) camera(r[param & 31]); break;
    case 1: hud(r[0]); break;
    case 2: pane_tall(r[19], r[23]); break;
    case 3: layout_built(r[19]); break;
    }
}

__attribute__((constructor)) static void dkb_wide_attach(void) { gate_obs_set(OBS_DKB_WIDE, dkb_wide_gate); }
