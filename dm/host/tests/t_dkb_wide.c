// Donkey Kong Bananza camera and UI callback (android/dkb_wide.c): opt-in and build gates, Hor+ / Vert+ on the scene camera's updater, repeated callbacks, a game that writes 16:9 back,
// objects that are no 16:9 lens, and the layout system's perspective projection squeezed into the middle 16:9 (orthographic and other shapes, scaled ones and null pointers untouched). Every case runs in its own process
// (the picture shape is read once). The layouts' panes: full-screen picture panes made as tall as the canvas and panes near the top and bottom moved to its edges, for shapes narrower
// than 16:9 only, on pane trees linked as the game links them (groups kept whole, parts, origins, rotation and scale, trees the walk refuses, repeats).
#include "../android/dkb_wide.c"
#include <assert.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

static module_t test_modules[1];
module_t *g_mod = test_modules;
int g_nmod;
static void (*callbacks[OBS_MAX])(uint64_t *, int);

void gate_obs_set(int id, void (*fn)(uint64_t *, int)) {
    assert(id > 0 && id < OBS_MAX);
    callbacks[id] = fn;
}

static void gate(uint64_t obj) {   // the camera gate as the rewriter's block calls it: the updater in x19, the register in the gate number's upper bits
    uint64_t r[31] = {0};
    r[19] = obj;
    callbacks[OBS_DKB_WIDE](r, 19);
}
static void hud_gate(uint64_t dinfo) {   // the HUD gate: x0 the draw info, kind 1
    uint64_t r[31] = {0};
    r[0] = dinfo;
    callbacks[OBS_DKB_WIDE](r, 1 << 5);
}

static void make(unsigned char *p, double fovy_deg, double asp) { ws_wrf((uintptr_t)p + C_FOVY, (float)fovy_deg); ws_wrf((uintptr_t)p + C_ASPECT, (float)asp); }
// what the projection update turns them into: m00 = 1 / (tan(fovy / 2) * aspect), m11 = 1 / tan(fovy / 2)
static double m00(const unsigned char *p) { return 1.0 / (tan(ws_rdf((uintptr_t)p + C_FOVY) * M_PI / 360.0) * ws_rdf((uintptr_t)p + C_ASPECT)); }
static double m11(const unsigned char *p) { return 1.0 / tan(ws_rdf((uintptr_t)p + C_FOVY) * M_PI / 360.0); }

static const char *shape[] = {"21:9", "1:1", "4:3", "13:6", "34:15", "181:196"};
static double shape_a[] = {21.0 / 9, 1.0, 4.0 / 3, 13.0 / 6, 34.0 / 15, 181.0 / 196};

static void setup(const char *env, const char *build) {
    g_nmod = 1;
    strcpy(g_mod[0].name, "main");
    strcpy(g_mod[0].build_id, build);
    if (env) setenv("DM_VK_PICTURE_ASPECT", env, 1); else unsetenv("DM_VK_PICTURE_ASPECT");
}

static void check(int which) {   // 0..13 no change; 14 another build; 15..20 the shapes
    static const char *off[] = {NULL, "", "0", "0:9", "16:0", "abc", "16:9", "1778:1000", "4:3x", "nan", "-4:3", "100:1", "1:100", "32:18"};
    _Alignas(16) unsigned char p[0x120], before[0x120];
    uint64_t a = (uintptr_t)p;
    memset(p, 0, sizeof p);
    if (which < 14) setup(off[which], DKB_MAIN_BUILD);
    else if (which == 14) setup("4:3", "0000");
    else setup(shape[which - 15], DKB_MAIN_BUILD);
    make(p, 60, K169);
    memcpy(before, p, sizeof p);
    gate(a);
    if (which < 15) { assert(!memcmp(before, p, sizeof p)); return; }

    double A = shape_a[which - 15];
    assert(fabs(ws_rdf(a + C_ASPECT) - A) < 1e-6);
    if (A > K169) {   // Hor+: the vertical field of view stays
        assert(ws_rdf(a + C_FOVY) == 60.0f);
        assert(fabs(m11(p) - m11(before)) < 1e-9);
    } else {         // Vert+: the horizontal reach stays what a 16:9 picture shows
        assert(fabs(m00(p) - 1.0 / (tan(60 * M_PI / 360.0) * K169)) < 1e-5);
        assert(ws_rdf(a + C_FOVY) > 60.0f);
    }
    // the gate fires again and again on the same updater: nothing scales twice
    unsigned char once[0x120];
    memcpy(once, p, sizeof p);
    for (int i = 0; i < 1000; i++) gate(a);
    assert(!memcmp(once, p, sizeof p));
    // the game's setter writes 16:9 back and keeps the fovy we wrote: only the aspect is set again
    ws_wrf(a + C_ASPECT, (float)K169);
    gate(a);
    assert(!memcmp(once, p, sizeof p));
    // fresh authored values (a new zoom) are converted once
    make(p, 45, K169);
    gate(a); gate(a);
    if (A < K169) assert(fabs(m00(p) - 1.0 / (tan(45 * M_PI / 360.0) * K169)) < 1e-5);
    else assert(fabs(m11(p) - 1.0 / tan(45 * M_PI / 360.0)) < 1e-5);
    assert(fabs(ws_rdf(a + C_ASPECT) - A) < 1e-6);
    // not a 16:9 perspective lens: another aspect (a split view), an impossible fovy, a null or unaligned pointer
    make(p, 60, 1.0); memcpy(before, p, sizeof p); gate(a); assert(!memcmp(before, p, sizeof p));
    make(p, 0.0, K169); memcpy(before, p, sizeof p); gate(a); assert(!memcmp(before, p, sizeof p));
    make(p, 400.0, K169); memcpy(before, p, sizeof p); gate(a); assert(!memcmp(before, p, sizeof p));
    gate(0);
    gate(a + 1);
    // a huge field of view is held under 172 degrees
    if (A < K169) { make(p, 150, K169); gate(a); assert(ws_rdf(a + C_FOVY) <= 172.0f && isfinite(ws_rdf(a + C_FOVY))); }
}

// the draw info: the projection copy at +0x10 (4x4 floats row by row), the layout system's perspective (main+0x45f840: fovy 15 degrees, aspect 1920 / 1080, near 1, far 10000)
static void layout_proj(float *m) {
    double c = 1.0 / tan(15.0 * M_PI / 360.0), n = 1, f = 10000;
    memset(m, 0, 64);
    m[0] = (float)(c / (1920.0 / 1080.0)); m[5] = (float)c; m[10] = (float)(-(f + n) / (f - n)); m[11] = (float)(-2 * f * n / (f - n)); m[14] = -1.0f;
}
static void ui_check(int which) {   // 0..2 no change (unset, 16:9, another build), 3 21:9, 4 1:1, 5 4:3
    _Alignas(16) float d[0x20] = {0}, keep[0x20];
    float *m = d + 4;
    layout_proj(m);
    memcpy(keep, d, sizeof d);
    setup(which == 0 ? NULL : which == 1 ? "16:9" : which == 2 ? "21:9" : which == 3 ? "21:9" : which == 4 ? "1:1" : "4:3", which == 2 ? "0000" : DKB_MAIN_BUILD);
    double A = which == 4 ? 1.0 : which == 5 ? 4.0 / 3 : 21.0 / 9;
    int on = which >= 3;
    hud_gate((uintptr_t)d);
    if (!on) { assert(!memcmp(d, keep, sizeof d)); return; }
    float q = (float)(A > K169 ? K169 / A : A / K169);
    for (int j = 0; j < 16; j++) {
        int xrow = j < 4, yrow = j >= 4 && j < 8;
        float want = keep[4 + j] * ((A > K169 ? xrow : yrow) ? q : 1.0f);
        assert(fabs(m[j] - want) < 1e-6);
    }
    assert(fabs(m[5] / m[0] - A) < 1e-4);   // the layer's shape is now the picture's: 16:9 content in the middle 16:9
    assert(!memcmp(d, keep, 16) && !memcmp(d + 20, keep + 20, 12 * 4));   // the head and the rows past the second are untouched
    // the scaled projection no longer has the 16:9 shape: a second setup of the same draw info (or the gate again) does not scale it twice
    float once[0x20]; memcpy(once, d, sizeof d);
    for (int i = 0; i < 100; i++) hud_gate((uintptr_t)d);
    assert(!memcmp(once, d, sizeof d));
    // an orthographic projection (a capture's 2/1920 x 2/1080), a perspective of another shape, null and unaligned pointers: untouched
    memset(d, 0, sizeof d); m[0] = 2.0f / 1920.0f; m[3] = -1.0f; m[5] = 2.0f / 1080.0f; m[7] = -1.0f; m[10] = -1.0f; m[15] = 1.0f; memcpy(once, d, sizeof d);
    hud_gate((uintptr_t)d); assert(!memcmp(once, d, sizeof d));
    memcpy(d, keep, sizeof d); m[0] = m[5]; memcpy(once, d, sizeof d);   // a square layout
    hud_gate((uintptr_t)d); assert(!memcmp(once, d, sizeof d));
    hud_gate(0);
    hud_gate((uintptr_t)d + 1);
    // an unknown kind and a negative parameter do nothing
    uint64_t r[31] = {0}; r[0] = (uintptr_t)d; r[19] = (uintptr_t)d;
    memcpy(d, keep, sizeof d); memcpy(once, d, sizeof d);
    callbacks[OBS_DKB_WIDE](r, 5 << 5); callbacks[OBS_DKB_WIDE](r, -1); callbacks[OBS_DKB_WIDE](r, 29);
    assert(!memcmp(once, d, sizeof d));
}

// ---- The layouts' panes (kinds 2 and 3) ----
// Panes as the game lays them out, 0x100 bytes each: own node at +0x8 (prev, next), parent +0x18, child list head +0x20 (prev, next), translate +0x30, rotate +0x3c, scale +0x48,
// size +0x50, flags +0x58, origin byte +0x5b, name +0xb0. Children are linked as AppendChild (main+0x148394) links them
#define NP 2048
static uint8_t (*P)[0x100];
static int np;
static uint64_t A(uint8_t *p) { return (uintptr_t)p; }
static void put64(uint8_t *p, int off, uint64_t v) { memcpy(p + off, &v, 8); }
static uint64_t get64(uint8_t *p, int off) { uint64_t v; memcpy(&v, p + off, 8); return v; }
static uint8_t *mk(const char *nm, uint8_t *parent, float tx, float ty, float w, float h) {
    assert(np < NP);
    uint8_t *p = P[np++];
    memset(p, 0, 0x100);
    put64(p, 8, A(p) + 8); put64(p, 0x10, A(p) + 8); put64(p, 0x20, A(p) + 0x20); put64(p, 0x28, A(p) + 0x20);
    float v[10] = {tx, ty, 0, 0, 0, 0, 1, 1, w, h};
    memcpy(p + 0x30, v, sizeof v); p[0x58] = 0x01; strncpy((char *)p + 0xb0, nm, 24);
    if (parent) {   // the child's node goes before the parent's head, at the tail
        uint64_t node = A(p) + 8, head = A(parent) + 0x20, tail = get64(parent, 0x20);
        put64(p, 8, tail); put64(p, 0x10, head); memcpy((void *)(uintptr_t)(tail + 8), &node, 8); put64(parent, 0x20, node); put64(p, 0x18, A(parent));
    }
    return p;
}
static float ty_of(uint8_t *p) { return ws_rdf(A(p) + 0x34); }
static float h_of(uint8_t *p) { return ws_rdf(A(p) + 0x54); }
static void setf(uint8_t *p, int off, float v) { ws_wrf(A(p) + off, v); }
static int near(float a, float b) { return fabsf(a - b) < 1e-2f; }
static void built(uint8_t *root) {   // the layout gate: x19 the layout, its root pane at +0x18
    static _Alignas(8) uint8_t layout[0x40];
    uint64_t r[31] = {0};
    memset(layout, 0, sizeof layout); put64(layout, 0x18, root ? A(root) : 0);
    r[19] = A(layout);
    callbacks[OBS_DKB_WIDE](r, 3 << 5);
}
static void sized(uint8_t *pane, const char *tag) {   // the pane gate: x19 the pane, x23 its record (the section tag first)
    _Alignas(8) uint8_t rec[0x60] = {0};
    uint64_t r[31] = {0};
    memcpy(rec, tag, 4);
    r[19] = A(pane); r[23] = A(rec);
    callbacks[OBS_DKB_WIDE](r, 2 << 5);
}
// the screens the rules were written for: a menu with bands at the 16:9 edges and a list in the middle, a HUD corner under a centred group
static uint8_t *root, *all, *head, *foot, *bg, *list, *row, *hud_g, *heart, *icon, *at119, *at120, *side, *sidekid;
static void screen(void) {
    np = 0;
    root = mk("RootPane", NULL, 0, 0, 1920, 1080); all = mk("N_All", root, 0, 0, 1920, 1080);
    head = mk("P_HeaderBg_00", all, 0, 483, 1920, 290); foot = mk("P_FooterBg_00", all, 0, -498, 1920, 290); bg = mk("P_Bg_00", all, 0, 0, 1920, 1080);
    list = mk("N_List_00", all, 0, 65, 1920, 550); row = mk("L_Row_00", list, 0, 200, 1500, 80);
    hud_g = mk("N_Hud_00", root, 0, 0, 1920, 1080); heart = mk("N_Life_00", hud_g, -800, 450, 300, 80); icon = mk("P_Heart_00", heart, 0, 300, 60, 60);
    at119 = mk("N_At119", all, 0, 119, 10, 10); at120 = mk("N_At120", all, 0, -120, 10, 10);
    side = mk("N_Side_00", all, 700, 0, 100, 100); sidekid = mk("P_SideKid", side, 0, 400, 10, 10);
}
static void pane_check(int which) {   // 0..5 nothing changes; 6..9 the shapes narrower than 16:9
    static const char *env[] = {NULL, "16:9", "21:9", "4:3", "4:3", "4:3", "4:3", "1:1", "16:10", "153:116"};
    static const char *panes[] = {NULL, NULL, NULL, NULL, "0", "on", NULL, NULL, "1", "log"};
    P = calloc(NP, 0x100);
    assert(P);
    setup(env[which], which == 3 ? "0000" : DKB_MAIN_BUILD);
    if (panes[which]) setenv("DM_DKB_PANES", panes[which], 1); else unsetenv("DM_DKB_PANES");
    screen();
    built(root); sized(bg, "pic1");
    if (which < 6) {   // unset, 16:9, wider, another build, DM_DKB_PANES=0, an invalid DM_DKB_PANES: every pane as authored
        assert(ty_of(head) == 483 && ty_of(foot) == -498 && ty_of(heart) == 450 && ty_of(at120) == -120 && ty_of(sidekid) == 400 && h_of(bg) == 1080);
        assert(!(head[0x58] & 0x10));
        return;
    }
    double a = which == 6 ? 4.0 / 3 : which == 7 ? 1.0 : which == 8 ? 1.6 : 153.0 / 116, k = K169 / a;
    float by = (float)(540 * (k - 1));
    if (which == 6) assert(near(by, 180));
    if (which == 8) assert(near(by, 60));
    // the edge rule
    assert(near(ty_of(head), 483 + by) && (head[0x58] & 0x10) && (head[0x58] & 0x01));   // a header band, with the matrix-dirty bit set and the visible bit kept
    assert(near(ty_of(foot), -498 - by));
    assert(ty_of(bg) == 0 && ty_of(list) == 65 && ty_of(row) == 200);   // a centred backdrop, and a list 65 off the line with its row
    assert(near(ty_of(heart), 450 + by) && ty_of(icon) == 300);            // a HUD corner under a centred group; nothing under a moved pane moves again
    assert(ty_of(at119) == 119 && near(ty_of(at120), -120 - by));
    assert(ty_of(side) == 0 && near(ty_of(sidekid), 400 + by));             // a pane beside the centre is on the line: its child moves
    // both build ends see the same tree, and the gate may fire again: nothing moves twice
    for (int i = 0; i < 50; i++) built(root);
    assert(near(ty_of(head), 483 + by) && near(ty_of(heart), 450 + by));
    // the tree built again with its authored values is moved again
    setf(head, 0x34, 483); setf(foot, 0x34, -498); setf(heart, 0x34, 450); setf(at120, 0x34, -120); setf(sidekid, 0x34, 400);
    built(root);
    assert(near(ty_of(head), 483 + by) && near(ty_of(heart), 450 + by));
    // the tall rule: a picture pane of the layout's size at its centre covers the canvas; once
    assert(near(h_of(bg), (float)(1080 * k)));
    sized(bg, "pic1");
    assert(near(h_of(bg), (float)(1080 * k)));
    { uint8_t *x;
      x = mk("P_Bg_01", NULL, 0, 0, 1920, 1080); sized(x, "pan1"); assert(h_of(x) == 1080);                 // not a picture pane (a null pane, a window, a text box)
      x = mk("P_Bg_02", NULL, 0, 0, 1900, 1080); sized(x, "pic1"); assert(h_of(x) == 1080);                 // narrower than the layout
      x = mk("P_Bg_03", NULL, 0, 0, 1920, 1440); sized(x, "pic1"); assert(h_of(x) == 1440);                 // taller already
      x = mk("P_Bg_04", NULL, 0, 30, 1920, 1080); sized(x, "pic1"); assert(h_of(x) == 1080);                // off the centre
      x = mk("P_Bg_05", NULL, 0, 0, 1920, NAN); sized(x, "pic1"); assert(isnan(h_of(x)));
      x = mk("P_Fade_00", NULL, 1, -1, 1922, 1078); sized(x, "pic1"); assert(near(h_of(x), (float)(1078 * k)));   // within the tolerance
      uint64_t r[31] = {0}; r[19] = A(x); callbacks[OBS_DKB_WIDE](r, 2 << 5); r[19] = 0; r[23] = A(x); callbacks[OBS_DKB_WIDE](r, 2 << 5);   // no record, no pane
      r[19] = A(x) + 1; callbacks[OBS_DKB_WIDE](r, 2 << 5);
      assert(near(h_of(x), (float)(1078 * k))); }
    // rotation, scale and the parent-relative origin
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080);
    { uint8_t *sc = mk("N_Thumb_00", root, 0, 0, 1920, 1080), *k1 = mk("P_In", sc, 0, 400, 10, 10); setf(sc, 0x48, 0.6f); setf(sc, 0x4c, 0.6f);
      uint8_t *ro = mk("N_Rot_00", root, 0, 0, 100, 100), *k2 = mk("P_In2", ro, 0, 400, 10, 10); setf(ro, 0x44, 90);
      uint8_t *top = mk("N_FromTop", root, 0, -57, 100, 100), *bot = mk("N_FromBottom", root, 0, 40, 100, 100), *mid = mk("N_NotEdge", root, 0, 500, 100, 100);
      top[0x5b] = 1 << 6; bot[0x5b] = 2 << 6; mid[0x5b] = 2 << 6;
      built(root);
      assert(ty_of(k1) == 400 && ty_of(k2) == 400);         // nothing moves under a scaled or a rotated pane
      assert(near(ty_of(top), -57 + by));                    // origin at the parent's top: y -57 + 540, moved up
      assert(near(ty_of(bot), 40 - by));                     // origin at the parent's bottom: y 40 - 540, moved down
      assert(ty_of(mid) == 500); }                           // 500 from the parent's bottom is 40 below the centre
    // a part's layout (its root has a parent) is left to the outer build
    np = 0;
    { uint8_t *outer = mk("L_Btn_00", NULL, 0, 0, 100, 100), *proot = mk("L_Part_00", outer, 0, 0, 1720, 1033), *key = mk("P_Key", proot, 0, -480, 100, 40);
      built(proot); assert(ty_of(key) == -480);
      built(outer); assert(near(ty_of(key), -480 - by)); }
    // a group whose pieces belong together stays whole, with everything under it, except the child named free for it; its neighbours move
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080);
    { uint8_t *dia = mk("N_1PBothLine_00", root, 0, 0, 1920, 1080), *l1 = mk("P_Line_00", dia, 0, 290, 400, 4), *l2 = mk("P_Line_01", dia, 0, -310, 400, 4);
      uint8_t *card = mk("N_Lake_00", root, 0, 320, 600, 300), *f2 = mk("P_FooterBg_00", root, 0, -498, 1920, 290);
      uint8_t *all = mk("N_All", root, 0, 0, 1920, 1080), *gd = mk("N_Guide_00", all, 0, 0, 1920, 1080), *g1 = mk("T_Guide_04", gd, 0, -395, 600, 40);
      uint8_t *hint = mk("A_Guide_00", gd, 0, 485, 300, 40), *h1 = mk("T_Hint_00", hint, 0, 130, 100, 20), *g2 = mk("T_Head_00", gd, 0, 430, 600, 40);
      uint8_t *far = mk("N_Guide_00", root, 0, 200, 600, 300), *fh = mk("A_Guide_00", far, 0, 285, 300, 40);   // a kept group off the line is not opened
      uint8_t *other = mk("N_InOut_00", root, 0, 0, 1920, 1080), *oh = mk("A_Guide_00", other, 0, 485, 300, 40);   // free for N_Guide_00 only
      built(root);
      assert(ty_of(l1) == 290 && ty_of(l2) == -310 && ty_of(card) == 320 && near(ty_of(f2), -498 - by));
      assert(near(ty_of(hint), 485 + by) && ty_of(h1) == 130 && ty_of(g1) == -395 && ty_of(g2) == 430 && ty_of(gd) == 0);
      assert(ty_of(far) == 200 && ty_of(fh) == 285 && ty_of(oh) == 485); }
    // trees the walk refuses: nothing in them moves
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080);
    { uint8_t *x = mk("P_A", root, 0, 483, 10, 10), *y = mk("P_B", root, 0, 483, 10, 10); put64(y, 0x18, A(x)); built(root); assert(ty_of(x) == 483 && ty_of(y) == 483); }   // a child whose parent is another pane
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080);
    { uint8_t *c = root, *leaf; for (int i = 0; i < 60; i++) c = mk("N_Deep", c, 0, 0, 10, 10);
      leaf = mk("P_Leaf", c, 0, 483, 10, 10); uint8_t *x = mk("P_A", root, 0, 483, 10, 10); built(root); assert(ty_of(leaf) == 483 && ty_of(x) == 483); }                 // deeper than the walk follows
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080);
    { uint8_t *last = NULL; for (int i = 0; i < 600; i++) last = mk("P_Many", root, (float)i, 483, 10, 10); built(root); assert(ty_of(last) == 483); }                    // more moves than one walk makes
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080);
    { uint8_t *x = mk("P_A", root, 0, 483, 10, 10); put64(root, 0x28, 0x10); built(root); assert(ty_of(x) == 483); }                                                    // a child link that is no pane
    if (which == 6) {
        np = 0;
        root = mk("RootPane", NULL, 0, 0, 1920, 1080);
        uint8_t *first = mk("P_A", root, 0, 483, 10, 10);
        put64(first, 0x10, 0x1000);
        built(root);
        assert(ty_of(first) == 483 && first[P_FLAGS] == 1);
        sized((uint8_t *)(uintptr_t)0x1000, "pic1");

        // The last pane's y is writable, but its flags start on a read-only page.
        size_t page = (size_t)sysconf(_SC_PAGESIZE);
        uint8_t *pages = mmap(NULL, 2 * page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
        assert(pages != MAP_FAILED);
        uint8_t (*saved)[0x100] = P;
        P = (void *)(pages + page - 0x258);
        np = 0;
        root = mk("RootPane", NULL, 0, 0, 1920, 1080);
        first = mk("P_A", root, 0, 483, 10, 10);
        uint8_t *last = mk("P_B", root, 0, -483, 10, 10);
        assert(last + P_FLAGS == pages + page);
        assert(mprotect(pages + page, page, PROT_READ) == 0);
        built(root);
        assert(ty_of(first) == 483 && first[P_FLAGS] == 1);
        assert(ty_of(last) == -483 && last[P_FLAGS] == 1);
        assert(mprotect(pages + page, page, PROT_READ | PROT_WRITE) == 0);
        built(root);
        assert(near(ty_of(first), 483 + by) && near(ty_of(last), -483 - by));
        assert(munmap(pages, 2 * page) == 0);
        P = saved;
    }
    if (which != 9) {   // (not with DM_DKB_PANES=log: 400 lines)
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080);
    { uint8_t *first = mk("P_0", root, 0, 483, 10, 10), *last = NULL; for (int i = 1; i < 400; i++) last = mk("P_N", root, 0, i & 1 ? 483.f : -483.f, 10, 10);
      built(root); assert(near(ty_of(first), 483 + by) && near(ty_of(last), 483 + by)); }                                                                               // 400 edge panes under one root all move
    }
    np = 0; root = mk("RootPane", NULL, 0, 30, 1920, 1080); { uint8_t *x = mk("P_A", root, 0, 483, 10, 10); built(root); assert(ty_of(x) == 483); }                        // a root off the line
    np = 0; root = mk("RootPane", NULL, 0, 0, 1920, 1080); built(root);                                                                                                    // no children
    built(NULL);                                                                                                                                                         // a layout without a root
    { uint64_t r[31] = {0}; callbacks[OBS_DKB_WIDE](r, 3 << 5); r[19] = 1; callbacks[OBS_DKB_WIDE](r, 3 << 5); }                                                           // no layout, an unaligned one
    assert(edge_shift(NAN, 180) == 0 && edge_shift(120, 180) == 180 && edge_shift(-120, 180) == -180 && edge_shift(119.9f, 180) == 0);
}

int main(void) {
    for (int i = 0; i < 6; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { ui_check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) { fprintf(stderr, "dkb wide UI case %d failed\n", i); return 1; }
    }
    puts("dkb wide: 6 layout projection cases passed");
    for (int i = 0; i < 21; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) { fprintf(stderr, "dkb wide case %d failed\n", i); return 1; }
    }
    puts("dkb wide: 21 opt-in, build, Hor+, Vert+ and repeat cases passed");
    for (int i = 0; i < 10; i++) {
        pid_t child = fork();
        assert(child >= 0);
        if (!child) { pane_check(i); _exit(0); }
        int status;
        assert(waitpid(child, &status, 0) == child);
        if (!WIFEXITED(status) || WEXITSTATUS(status)) { fprintf(stderr, "dkb wide pane case %d failed\n", i); return 1; }
    }
    puts("dkb wide: 10 layout pane cases passed (off, 16:9, wider, another build, switched off; full-screen and edge panes at 4:3, 1:1, 16:10 and 153:116)");
    return 0;
}
