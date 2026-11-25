// PEANUTS: the overlay's frame-time graph drawn by the host, one new bar per game frame (peanuts_graph.c). The launcher still draws the whole top-right block twice a second
// (Native.overlaySet slot 1); it tells the host where the bars sit in that bitmap (Bridge.graphRect), and the host paints fresh bars there on every presented game frame.
#pragma once
#include <stdint.h>

// The bars' columns and the frames they show, as dev.peanuts.overlay.Hud.BARS
#define PN_GRAPH_BARS 48

// Draws the graph into premultiplied RGBA pixels (stride bytes a row) in the box x, y, w, h: the box is first filled with the block's own background, each row from
// its pixel at x - 2 (the background left of the bars, so a vertical gradient carries on); then the last min(n, bars) frames of ms (oldest first) are pn_graph_bars' equal columns (PN_GRAPH_BARS until set), newest on the right, a 1 px gap when a
// column is over 3 px wide. Like FrameStats: the target is the median of all n frames given; a bar is 1/8..8/8 of h (bucket = min(7, ms / (2 * target) * 8)); green up to
// 115 % of the target, red from 200 %, else yellow; alpha 217 (the overlay's 0.85)
void pn_graph_draw(uint8_t *px, int stride, int x, int y, int w, int h, const float *ms, int n);
// The block the launcher set for slot 1 (the host's nxhost_overlay_set arguments): keeps a copy and paints the current bars into it and into px
void peanuts_ovl_block(int slot, void *px, int w, int h, int stride);
// Once per presented game frame (peanuts_tick): the bars of the newest frames into the kept copy, which is handed to the host's overlay
void pn_graph_frame(void);
// The columns of the bars, 1..PN_GRAPH_BARS (anything else: PN_GRAPH_BARS): the table's 48, the pill's fewer and wider; Bridge.graphBars calls it
void pn_graph_bars(int n);
// On-pace bars light grey (1: the pill) or green (0: the table, the default); Bridge.graphPlain calls it
void pn_graph_plain(int on);
// The bars' box in the slot-1 block, in its pixels (w or h 0 = none); Bridge.graphRect calls it
void pn_graph_rect(int x, int y, int w, int h);
// The place of the stats block (slot 1), as the host's vka_ovl.h: 0 top left, 1 top right (the default), 2 bottom left, 3 bottom right, 4 top centre, 6 bottom centre;
// Bridge.overlayCorner calls it
void pn_graph_corner(int corner);
// The corner of an overlay slot for the host's present: the launcher's choice for slot 1, and the build tag (slot 0) out of the way of a top-left block
int peanuts_ovl_corner(int slot, int dflt);
// The first `lead` rows of w-pixel rows (stride bytes apart) moved below the others, in place
void pn_graph_rotate_rows(uint8_t *px, int stride, int w, int h, int lead);
