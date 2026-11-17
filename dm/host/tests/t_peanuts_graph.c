// PEANUTS: the host's drawing of the overlay's frame-time bars (dm/peanuts/peanuts_graph.c), run on a PC: the background sampled left of the box, the 48 columns and
// their places, the colours, premultiplied bytes, a short window, a box that does not fit, and the block / frame path with a stand-in for the host's overlay.
#include "../../peanuts/peanuts_frametime.c"
#include "../../peanuts/peanuts_graph.c"
#include <stdio.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }

static int g_sets, g_set_w, g_set_h; static uint8_t g_set_px[64 * 24 * 4];
int nxhost_overlay_set(int slot, const void *px, int w, int h, int stride) {
    (void)stride;
    if (slot == 1 && px && w * h * 4 <= (int)sizeof g_set_px) { memcpy(g_set_px, px, (size_t)w * h * 4); g_set_w = w; g_set_h = h; }
    g_sets++;
    return 1;
}

#define W 64
#define H 24
static uint8_t px[W * H * 4];
static const uint8_t BG[4] = {10, 20, 30, 200};
static void fill_bg(void) { for (int i = 0; i < W * H; i++) memcpy(px + i * 4, BG, 4); }
static uint8_t *at(int x, int y) { return px + ((size_t)y * W + x) * 4; }
static int is(const uint8_t *p, int r, int g, int b, int a) { return p[0] == r && p[1] == g && p[2] == b && p[3] == a; }

int main(void) {
    // a box at x = 8, 48 wide (1 px a column, no gap), 16 high at y = 4; target 16.7 ms
    float ms[48];
    for (int i = 0; i < 48; i++) ms[i] = 16.7f;
    ms[10] = 22.0f;   // yellow (1.32x)
    ms[47] = 40.0f;   // red (2.4x)
    fill_bg();
    memset(at(6, 12), 99, 4);   // the sample pixel (x - 2, y + h / 2) is another colour
    pn_graph_draw(px, W * 4, 8, 4, 48, 16, ms, 48);
    check(is(at(10, 0), 10, 20, 30, 200) == 1, "outside the box stays");
    uint8_t s[4] = {99, 99, 99, 99};
    check(memcmp(at(9, 4), s, 4) == 0 || is(at(9, 4), 99, 99, 99, 99), "box filled from the sampled pixel (top of an empty column)");
    // column 0: a frame at the target = bucket 4 -> h * 5 / 8 = 10 rows, bottom aligned, green premultiplied
    check(is(at(8, 19), 65, 119, 56, 217) || (at(8, 19)[3] == 217 && at(8, 19)[1] == (uint8_t)((0xAF * 217 + 127) / 255)), "green bar, premultiplied, alpha 217");
    check(at(8, 10)[3] == 217 && at(8, 9)[3] == 99, "a green bar at the target: bucket 4, 5/8 of h = 10 rows");
    check(at(8 + 10, 19)[0] == (uint8_t)((0xFF * 217 + 127) / 255) && at(8 + 10, 19)[1] == (uint8_t)((0xC1 * 217 + 127) / 255), "yellow bar in column 10");
    check(at(8 + 47, 19)[0] == (uint8_t)((0xF4 * 217 + 127) / 255) && at(8 + 47, 19)[1] == (uint8_t)((0x43 * 217 + 127) / 255), "red newest bar on the right");
    check(at(8 + 47, 4)[3] == 217, "red bar 40 / (2 * 16.7) -> bucket 7: full height");
    check(at(8 + 47 + 1, 19)[3] == 200, "nothing right of the box");

    // a wide box: 96 px would not fit the 64 wide block; use 4 px a column in a 192 px buffer instead
    static uint8_t big[192 * 8 * 4];
    for (int i = 0; i < 192 * 8; i++) memcpy(big + i * 4, BG, 4);
    pn_graph_draw(big, 192 * 4, 0, 0, 192, 8, ms, 48);
    check(big[(7 * 192 + 3) * 4 + 3] == BG[3], "wide columns keep a 1 px gap (x = 3 of column 0)");
    check(big[(7 * 192 + 0) * 4 + 3] == 217 && big[(7 * 192 + 2) * 4 + 3] == 217, "wide column 0 is x 0..2");

    // a short window: 12 frames occupy the 12 right-hand columns
    fill_bg();
    pn_graph_draw(px, W * 4, 8, 4, 48, 16, ms, 12);
    check(at(8 + 35, 19)[3] == 200 && at(8 + 36, 19)[3] == 217, "n = 12: the last 12 columns only");

    // rects that do not fit are not drawn, through the block path
    pn_ft_reset();
    uint64_t t = 0; for (int i = 0; i < 60; i++) { pn_ft_push(t); t += 16700000ull; }
    uint8_t blk[W * H * 4]; for (int i = 0; i < W * H; i++) memcpy(blk + i * 4, BG, 4);
    pn_graph_rect(8, 4, 48, 16);
    peanuts_ovl_block(0, blk, W, H, W * 4);
    check(blk[(19 * W + 8) * 4 + 3] == 200, "slot 0 is not touched");
    peanuts_ovl_block(1, blk, W, H, W * 4);
    check(blk[(19 * W + 8) * 4 + 3] == 217, "slot 1: the caller's pixels get the bars");
    g_sets = 0; pn_graph_frame();
    check(g_sets == 1 && g_set_w == W && g_set_h == H && g_set_px[(19 * W + 8) * 4 + 3] == 217, "pn_graph_frame hands the copy to the host");
    pn_graph_rect(40, 4, 48, 16);   // beyond the block
    g_sets = 0; pn_graph_frame();
    check(g_sets == 0, "a box outside the block draws nothing");
    pn_graph_rect(8, 4, 0, 0);
    g_sets = 0; pn_graph_frame();
    check(g_sets == 0, "size 0 = off");
    pn_graph_rect(8, 4, 48, 16);
    peanuts_ovl_block(1, NULL, 0, 0, 0);
    g_sets = 0; pn_graph_frame();
    check(g_sets == 0, "w = 0 drops the copy");

    // corners: the build tag moves out of a top-left block's way; the stats block follows the launcher
    pn_graph_corner(1);
    check(peanuts_ovl_corner(1, 1) == 1 && peanuts_ovl_corner(0, 0) == 0, "top right: slots keep their corners");
    pn_graph_corner(0);
    check(peanuts_ovl_corner(1, 1) == 0 && peanuts_ovl_corner(0, 0) == 1, "top left: the tag goes to the top right");
    pn_graph_corner(3);
    check(peanuts_ovl_corner(1, 1) == 3 && peanuts_ovl_corner(0, 0) == 0, "bottom right: the tag stays top left");
    pn_graph_corner(9);
    check(peanuts_ovl_corner(1, 1) == 1, "an unknown corner is top right");

    // the row rotation: 3 rows of 2 pixels, stride 12, first row 1 moved below
    uint8_t rot[4 * 12]; memset(rot, 0, sizeof rot);
    for (int r = 0; r < 4; r++) for (int c = 0; c < 8; c++) rot[r * 12 + c] = (uint8_t)(r * 10 + c + 1);
    uint8_t pad_before = rot[8]; (void)pad_before;
    pn_graph_rotate_rows(rot, 12, 2, 4, 1);
    check(rot[0] == 11 && rot[12] == 21 && rot[24] == 31 && rot[36] == 1 && rot[43] == 8, "rotate 1 row: the first row is last");
    pn_graph_rotate_rows(rot, 12, 2, 4, 3);
    check(rot[0] == 1 && rot[12] == 11 && rot[24] == 21 && rot[36] == 31, "rotate 3 more rows restores the order");

    // a bottom corner: the transparent top rows of the block go to the bottom, the graph box moves up by them
    pn_graph_corner(3);
    memset(blk, 0, sizeof blk);   // rows 0..2 fully transparent (the margin), the rest the pill
    for (int r = 3; r < H; r++) for (int c = 0; c < W; c++) memcpy(blk + (r * W + c) * 4, BG, 4);
    pn_graph_rect(8, 8, 48, 14);   // in the original bitmap: rows 8..21
    peanuts_ovl_block(1, blk, W, H, W * 4);
    check(blk[(H - 1) * W * 4 + 3] == 0 && blk[(H - 3) * W * 4 + 3] == 0 && blk[(H - 4) * W * 4 + 3] != 0, "the margin rows are now the bottom three");
    check(blk[(0 * W + 8) * 4 + 3] == 200 && blk[((8 - 3 + 13) * W + 8) * 4 + 3] == 217, "the bars are drawn 3 rows higher (rows 5..18)");
    check(blk[((8 - 3 + 14) * W + 8) * 4 + 3] == 200, "and not below the box");
    g_sets = 0; pn_graph_frame();
    check(g_sets == 1 && g_set_px[((8 - 3 + 13) * W + 8) * 4 + 3] == 217 && g_set_px[((H - 1) * W) * 4 + 3] == 0, "pn_graph_frame uses the shifted box and the rotated copy");
    pn_graph_corner(1);
    peanuts_ovl_block(1, NULL, 0, 0, 0);

    printf(g_fail ? "FAILED\n" : "all ok\n");
    return g_fail ? 1 : 0;
}
