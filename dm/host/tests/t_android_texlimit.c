// The texture cache's limit and eviction policy (dm/gfx/vk_android/vka_texlimit.h): the limit follows RAM and the heap budget, the override-free floor holds, images used in
// the current or the previous frame are never candidates, and a simulated working set larger than the limit is not uploaded again frame after frame (the thrash the old
// array-order, age-1 policy caused: Mario Kart World at a 1,536 MiB limit, Ocarina of Time at 512 MiB), a 12 GB device keeps an eighth of RAM while the system stalls on
// memory and a 32nd while it does not, memory pressure (PSI) holds the cache and gives back what is not the working set, DM_VK_IMG_PSI=0 is the build before that rule, and
// v0.3.1's limit is re-derived here (limit_031) so the churn it caused stays measurable without shipping it
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../../gfx/vk_android/vka_texlimit.h"

static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)
#define MIB VKTL_MIB
#define GIB (1024 * MIB)

// v0.3.1's own limit (tag 3c85e09, commit 483ce24): the RAM rule with the eighth reserve on a device of 12 GB or less, always; above 5 % some avg10 the limit is whatever
// the cache holds, above 15 % an eighth of all the images comes back, down to the floor. Not a policy the product has any more: the test keeps it so "the fix changes the
// churn" is an assertion and not a story. The sim sets no heap budget, so the budget term of the rule would not bite
static uint64_t limit_031(const vktl_in *in) {
    uint64_t small = in->mem_total / 32; if (small < 512 * MIB) small = 512 * MIB;
    uint64_t res = in->mem_total && in->mem_total <= VKTL_12GB ? in->mem_total / 8 : small;
    uint64_t lim = in->img_bytes + vktl_sub(in->mem_avail, res);
    double psi = in->psi_some10;
    if (psi >= 15.0) { uint64_t l = in->img_bytes - in->img_bytes / 8; if (l < lim) lim = l; }
    else if (psi >= 5.0) { if (in->img_bytes < lim) lim = in->img_bytes; }
    return lim < VKTL_FLOOR ? VKTL_FLOOR : lim;
}

// A cache of n images of one size, a frame using `use` of them (ids 0..use-1, in a rotating order), evicting by the policy whenever over the limit, as vka_tex.c does on a miss
typedef struct { uint64_t last; int live, gpu; } img;
static int cmp(const void *a, const void *b) { const img *x = *(img *const *)a, *y = *(img *const *)b; return vktl_order(x->gpu, x->last, y->gpu, y->last); }

// What a run did, counted the way the thrash costs: an upload is a texture's bytes going to the GPU again. `regret` is the uploads of an image the cache had evicted within the
// working set's window (the churn itself), and `ws_kept` says whether every texture the last VKTL_WS_FRAMES frames drew was still in the cache (bytes resident is not that
// answer: the images the cache evicted are simply not counted in the bytes)
typedef struct { int up, up_steady, ev, regret, gpu_ev, ws_kept; uint64_t resident, ws; } sim_out;

// A scene: n textures of `each` bytes; frame f draws the `use` ids from f*adv on, in a rotating order, so each texture is used for use/adv frames in a row and comes back
// every n/adv frames. MemAvailable falls by what the cache takes (a byte of image memory is a byte less available), and the limit is measured every 60 frames, every 15 while
// the pressure policy is pressed, as img_limit_update does. `old_rule` runs v0.3.1's limit (limit_031) in place of the shipped policy
typedef struct { int n, use, adv, frames, gpu_every, old_rule; uint64_t each, mem_total, avail0; double psi; vktl_cfg cfg; } sim;
static void sim_run(const sim *s, sim_out *o) {
    img *im = calloc((size_t)s->n, sizeof *im); img **c = malloc((size_t)s->n * sizeof *c);
    for (int i = 0; i < s->n; i++) im[i].gpu = s->gpu_every > 1 && i % s->gpu_every == 1;   // (half of them drawn into, never uploaded)
    int *evicted_at = calloc((size_t)s->n, sizeof *evicted_at);
    uint64_t bytes = 0, lim = 0, lim_frame = 0, ws = 0, dry = 0; vktl_psi st = {0};   // (dry: this frame had nothing left to evict, as vka_tex_prune_locked's g_prune_dry)
    memset(o, 0, sizeof *o);
    for (uint64_t f = 1; f <= (uint64_t)s->frames; f++) {
        int pressed = st.pressed;
        if (!lim || f >= lim_frame + (uint64_t)(pressed ? 15 : 60)) {   // img_limit_update
            uint64_t avail = s->avail0 > bytes ? s->avail0 - bytes : 0;
            ws = 0; for (int i = 0; i < s->n; i++) if (im[i].live && im[i].last && f - im[i].last <= VKTL_WS_FRAMES) ws += s->each;
            vktl_in in = {.mem_total = s->mem_total, .mem_avail = avail, .img_bytes = bytes, .ws_bytes = ws, .psi_some10 = s->psi};
            if (s->old_rule) { pressed = s->psi >= 5.0; lim = limit_031(&in); }
            else { pressed = vktl_psi_update(&st, &s->cfg, s->psi, s->mem_total, avail); lim = vktl_limit(&in, &s->cfg, pressed); }
            lim_frame = f;
        }
        // vka_tex_prune_locked: the shipped policy has no working-set image among its candidates, and no guard under a pinned limit
        // vka_tex.c g_psi_shrink: only a policy pressed past its shrink mark may take a GPU-written image; v0.3.1 had no such gate
        int allow_gpu = s->old_rule ? 1 : s->cfg.psi == 1 && st.pressed && s->psi >= s->cfg.shrink;
        for (int k = 0; k < s->use; k++) {
            int id = (int)((f * (uint64_t)s->adv + (uint64_t)k) % (uint64_t)s->n);
            if (!im[id].live) {   // a miss: over the limit, prune down to the target first (once a frame)
                if (bytes > lim && dry != f) {
                    int nc = 0;
                    for (int i = 0; i < s->n; i++) {
                        if (!im[i].live || !vktl_evictable(f, im[i].last, 0, im[i].gpu, 0, 0, allow_gpu)) continue;
                        if (!s->old_rule && vktl_skip_ws(&s->cfg, 0, f, im[i].last)) continue;
                        c[nc++] = &im[i];
                    }
                    qsort(c, (size_t)nc, sizeof *c, cmp);
                    uint64_t target = s->old_rule ? lim - lim / 16 : vktl_target(lim, ws, &s->cfg, pressed);
                    for (int i = 0; i < nc && bytes > target; i++) {
                        if (!s->old_rule && vktl_guard_ws(&s->cfg, 0, bytes, s->each, ws)) continue;   // one image can cross what the target respects
                        c[i]->live = 0; bytes -= s->each; evicted_at[c[i] - im] = (int)f; o->ev++; if (c[i]->gpu) o->gpu_ev++;
                    }
                    if (bytes > lim) dry = f;   // g_prune_dry: set after a prune that left it over, whatever it managed to evict
                }
                im[id].live = 1; bytes += s->each; o->up++;
                if (f > (uint64_t)s->frames / 2) o->up_steady++;
                if (evicted_at[id]) { if (f - (uint64_t)evicted_at[id] <= VKTL_WS_FRAMES) o->regret++; evicted_at[id] = 0; }
            }
            im[id].last = f;
        }
    }
    o->resident = bytes; o->ws = 0;
    for (int i = 0; i < s->n; i++) if (im[i].live && im[i].last && (uint64_t)s->frames - im[i].last <= VKTL_WS_FRAMES) o->ws += s->each;
    o->ws_kept = 1;
    for (int i = 0; i < s->n; i++) if (im[i].last && (uint64_t)s->frames - im[i].last <= VKTL_WS_FRAMES && !im[i].live) o->ws_kept = 0;
    free(im); free(c); free(evicted_at);
}

// DM_VK_PRUNE_MS, as vka_tex.c spreads a prune: a cache of `old` long-unused images at its limit, then `fresh` new images in the first frame and one in each later frame (each a miss that may prune first),
// each eviction costing `cost` ns of a frame's `budget` ns (0: no budget). Returns the frames that evicted; the most one frame evicted, the total evicted and the bytes left
static int run_budget(int old, int fresh, uint64_t each, uint64_t limit, int frames, uint64_t budget, uint64_t cost, int *most, int *evicted, uint64_t *bytes_left) {
    int n = old + fresh + frames, nlive = old, more = 0, busy = 0; img *im = calloc((size_t)n, sizeof *im); img **c = malloc((size_t)n * sizeof *c);
    uint64_t bytes = (uint64_t)old * each; const vktl_cfg dflt = vktl_cfg_default();
    for (int i = 0; i < old; i++) im[i] = (img){.last = 1, .live = 1};
    *most = 0; *evicted = 0;
    for (uint64_t f = 100; f < 100 + (uint64_t)frames; f++) {
        uint64_t spent = 0; int this_frame = 0;
        for (int m = 0; m < (f == 100 ? fresh : 1); m++) {
            if (!budget) more = 0;
            if (!vktl_prune_due(bytes, limit, vktl_target(limit, 0, &dflt, 0), more)) more = 0;
            else if (!budget || spent < budget) {
                int nc = 0, k = 0, cut = 0;
                for (int i = 0; i < nlive; i++) if (im[i].live && vktl_evictable(f, im[i].last, 0, im[i].gpu, 0, 0, 0)) c[nc++] = &im[i];
                qsort(c, (size_t)nc, sizeof *c, cmp);
                for (k = 0; k < nc && bytes > vktl_target(limit, 0, &dflt, 0); k++) {
                    if (vktl_budget_spent(budget, k, spent)) { cut = 1; break; }
                    c[k]->live = 0; bytes -= each; spent += cost; this_frame++;
                }
                if (budget) more = cut;
            }
            im[nlive++] = (img){.last = f, .live = 1}; bytes += each;
        }
        if (this_frame) busy++;
        if (this_frame > *most) *most = this_frame;
        *evicted += this_frame;
    }
    *bytes_left = bytes; free(im); free(c); return busy;
}

int main(void) {
    vktl_cfg fix = vktl_cfg_default();                    // DM_VK_IMG_RESERVE_MB=0, DM_VK_IMG_PSI=1: the shipped policy
    vktl_cfg off = vktl_cfg_default(); off.psi = 0;       // DM_VK_IMG_PSI=0: the build before this rule, as v0.3.24 had it

    // ---- the reserve: a 32nd, at least 512 MiB; on the 12 GB class an eighth while the system stalls on memory ----
    CHECK(vktl_reserve(15 * GIB, &fix, 0) == 512 * MIB && vktl_reserve(15 * GIB, &fix, 1) == 512 * MIB, "a 15 GiB phone keeps 512 MiB, pressure or not");
    CHECK(vktl_reserve(64 * GIB, &fix, 0) == 2 * GIB, "a 64 GiB machine keeps a 32nd");
    CHECK(vktl_reserve(11 * GIB, &fix, 0) == 512 * MIB, "an 11 GiB phone at rest keeps 512 MiB (%llu MiB)", (unsigned long long)(vktl_reserve(11 * GIB, &fix, 0) >> 20));
    CHECK(vktl_reserve(11 * GIB, &fix, 1) == 11 * GIB / 8, "and an eighth under pressure (%llu MiB)", (unsigned long long)(vktl_reserve(11 * GIB, &fix, 1) >> 20));
    CHECK(vktl_reserve(12 * GIB, &fix, 1) == 12 * GIB / 8 && vktl_reserve(12 * GIB + MIB, &fix, 1) == 512 * MIB, "12 GiB is the class's edge");
    vktl_cfg pin = vktl_cfg_default(); pin.mb = 800;
    CHECK(vktl_reserve(11 * GIB, &pin, 0) == 800 * MIB && vktl_reserve(11 * GIB, &pin, 1) == 800 * MIB, "DM_VK_IMG_RESERVE_MB replaces the rule, pressure or not");
    // with the pressure term off there is nothing to tell the reserve when to grow: the eighth is permanent on the class, as the build before this rule had it
    CHECK(vktl_reserve(11 * GIB, &off, 0) == 11 * GIB / 8, "DM_VK_IMG_PSI=0 keeps the eighth always (%llu MiB)", (unsigned long long)(vktl_reserve(11 * GIB, &off, 0) >> 20));
    CHECK(vktl_reserve(3 * GIB, &off, 0) == 3 * GIB / 8 && vktl_reserve(3 * GIB, &fix, 0) == 512 * MIB, "upstream had no floor on the class (3 GiB: 384 MiB there, 512 here)");
    CHECK(vktl_reserve(15 * GIB, &off, 1) == 512 * MIB, "above the class edge, 0 and 1 keep the same reserve");

    // ---- the limit: images plus what RAM can still give past the reserve, the budget's share past other usage, the floor ----
    vktl_in a = {.mem_total = 15 * GIB, .mem_avail = 4 * GIB, .img_bytes = 1 * GIB};
    CHECK(vktl_limit(&a, &fix, 0) == 1 * GIB + 4 * GIB - 512 * MIB, "RAM: images + MemAvailable - reserve (%llu MiB)", (unsigned long long)(vktl_limit(&a, &fix, 0) >> 20));
    vktl_in b = a; b.heap_budget = 6 * GIB; b.heap_usage = 4 * GIB;   // 3 GiB of other use: 6 - 6/16 - 3 = 2.625 GiB
    CHECK(vktl_limit(&b, &fix, 0) == 6 * GIB - 6 * GIB / 16 - 3 * GIB, "the budget's share is the smaller (%llu MiB)", (unsigned long long)(vktl_limit(&b, &fix, 0) >> 20));
    vktl_in h = {.heap_size = 12 * GIB}; CHECK(vktl_limit(&h, &fix, 0) == 6 * GIB, "no budget, no RAM figures: half the heap");
    vktl_in z = {0}; CHECK(vktl_limit(&z, &fix, 0) == VKTL_FLOOR, "nothing known: the floor");
    vktl_in lo = {.mem_total = 4 * GIB, .mem_avail = 300 * MIB, .img_bytes = 100 * MIB}; CHECK(vktl_limit(&lo, &fix, 0) == VKTL_FLOOR, "a tight phone: not under the floor");
    vktl_in full = {.mem_total = 15 * GIB, .mem_avail = 700 * MIB, .img_bytes = 3 * GIB}; CHECK(vktl_limit(&full, &fix, 0) == 3 * GIB + 188 * MIB, "Mario Kart World's measured state: about its images");

    // The S24+ as it measured (MemTotal 11,203 MiB, MemAvailable 2,116 MiB, images 96 MiB): the eighth reserve alone takes 888 MiB of the cache's headroom
    vktl_in s24 = {.mem_total = 11203 * MIB, .mem_avail = 2116 * MIB, .img_bytes = 96 * MIB};
    CHECK(vktl_limit(&s24, &off, 0) == 96 * MIB + 2116 * MIB - 11203 * MIB / 8, "the build that regressed leaves %llu MiB for the images", (unsigned long long)(vktl_limit(&s24, &off, 0) >> 20));
    CHECK(vktl_limit(&s24, &fix, 0) == 96 * MIB + 2116 * MIB - 512 * MIB, "at rest the fix keeps 512 MiB and the images get the headroom (%llu MiB)", (unsigned long long)(vktl_limit(&s24, &fix, 0) >> 20));
    CHECK(vktl_limit(&s24, &fix, 1) == vktl_limit(&s24, &off, 0), "and under pressure it is the eighth, i.e. that build's limit (%llu MiB)", (unsigned long long)(vktl_limit(&s24, &fix, 1) >> 20));

    // ---- the pressure policy's hysteresis: enter at hold, leave only under ease and with the larger reserve's worth of RAM free again ----
    vktl_psi st = {0};
    CHECK(vktl_psi_update(&st, &fix, 5.0, 11 * GIB, 4 * GIB) == 0, "a phone merely busy (5 percent) does not press the cache");
    CHECK(vktl_psi_update(&st, &fix, VKTL_PSI_HOLD, 11 * GIB, 4 * GIB) == 1, "at the hold mark it does");
    CHECK(vktl_psi_update(&st, &fix, 5.0, 11 * GIB, 4 * GIB) == 1, "a value between the marks keeps the state (no flap)");
    CHECK(vktl_psi_update(&st, &fix, 2.0, 11 * GIB, 600 * MIB) == 1, "under the ease mark but without the RAM back: still pressed");
    CHECK(vktl_psi_update(&st, &fix, 2.0, 11 * GIB, 4 * GIB) == 0, "calm and with the RAM free again: eased");
    CHECK(vktl_psi_update(&st, &fix, -1.0, 11 * GIB, 0) == 0, "pressure not known: the state stays");
    st.pressed = 1; CHECK(vktl_psi_update(&st, &fix, -1.0, 11 * GIB, 0) == 1, "pressure not known: the state stays (pressed)");
    st.pressed = 0; CHECK(vktl_psi_update(&st, &off, 40.0, 11 * GIB, 4 * GIB) == 0, "DM_VK_IMG_PSI=0 never presses");
    // the ease condition is the reserve in force plus 256 MiB: with a pinned reserve, that one (800 + 256 = 1,056 MiB here), not the 32nd's 768
    st.pressed = 1; CHECK(vktl_psi_update(&st, &pin, 2.0, 11 * GIB, 1000 * MIB) == 1 && vktl_psi_update(&st, &pin, 2.0, 11 * GIB, 1056 * MIB) == 0, "a pinned reserve is the ease mark's reserve");
    st.pressed = 0;
    vktl_cfg flap = vktl_cfg_default(); flap.hold = 2.0;   // DM_VK_IMG_PSI_HOLD=2 with the default ease: press and ease on every reading
    vktl_cfg ok = vktl_cfg_default(); ok.ease = 2.0; CHECK(vktl_cfg_check(ok).ease == 2.0, "an ease mark under the hold mark is kept");
    vktl_cfg safe = vktl_cfg_check(flap);
    CHECK(safe.hold == VKTL_PSI_HOLD && safe.ease == VKTL_PSI_EASE, "a hold mark at or under the ease mark falls back to the defaults (%.1f, %.1f)", safe.hold, safe.ease);
    vktl_cfg nan = vktl_cfg_default(); nan.shrink = 0.0 / 0.0; vktl_cfg nansafe = vktl_cfg_check(nan);
    CHECK(nansafe.shrink == VKTL_PSI_SHRINK, "and so does a mark that is not a positive number");
    vktl_cfg odd = vktl_cfg_default(); odd.psi = 2;   // (v0.3.1's policy is not a value the switch has any more: the test's limit_031 stands in for it)
    CHECK(vktl_cfg_check(odd).psi == 1, "DM_VK_IMG_PSI is 0 or 1: anything else is the default's");
    st.pressed = 0; CHECK(vktl_psi_update(&st, &safe, 2.5, 11 * GIB, 4 * GIB) == 0, "the checked marks do not flap at 2.5 percent");

    // ---- the pressure term: hold, then an eighth of what is not the working set back, never under the working set or the floor ----
    vktl_in w = {.mem_total = 11 * GIB, .mem_avail = 4 * GIB, .img_bytes = 2 * GIB, .ws_bytes = 1 * GIB, .psi_some10 = 20.0};
    CHECK(vktl_limit(&w, &fix, 0) == 2 * GIB + 4 * GIB - 512 * MIB, "not pressed: the RAM rule (%llu MiB)", (unsigned long long)(vktl_limit(&w, &fix, 0) >> 20));
    CHECK(vktl_limit(&w, &fix, 1) == 2 * GIB + 4 * GIB - 11 * GIB / 8, "pressed: the same rule with the eighth's reserve, which is the cap on growth (%llu MiB)", (unsigned long long)(vktl_limit(&w, &fix, 1) >> 20));
    CHECK(vktl_limit(&w, &fix, 1) == vktl_limit(&w, &off, 0), "which is the build before this rule, at the same state");
    vktl_in just = w; just.psi_some10 = VKTL_PSI_SHRINK - 0.1;
    CHECK(vktl_limit(&just, &fix, 1) == 2 * GIB + 4 * GIB - 11 * GIB / 8, "under the shrink mark it holds and gives nothing back");
    w.psi_some10 = VKTL_PSI_SHRINK;
    CHECK(vktl_limit(&w, &fix, 1) == 2 * GIB - (2 * GIB - 1 * GIB) / VKTL_PSI_STEP, "past the shrink mark: an eighth of what is not the working set (%llu MiB)", (unsigned long long)(vktl_limit(&w, &fix, 1) >> 20));
    w.psi_some10 = 90.0; w.ws_bytes = 2 * GIB;
    CHECK(vktl_limit(&w, &fix, 1) == 2 * GIB + 4 * GIB - 11 * GIB / 8, "a working set that is all of it: nothing taken back (%llu MiB)", (unsigned long long)(vktl_limit(&w, &fix, 1) >> 20));
    vktl_in small = {.mem_total = 11 * GIB, .mem_avail = 4 * GIB, .img_bytes = 300 * MIB, .ws_bytes = 200 * MIB, .psi_some10 = 90.0};
    CHECK(vktl_limit(&small, &fix, 1) == VKTL_FLOOR, "pressure never goes under the floor");
    vktl_in wk = {.mem_total = 11 * GIB, .mem_avail = 700 * MIB, .img_bytes = 2 * GIB, .ws_bytes = 512 * MIB, .psi_some10 = 40.0};
    CHECK(vktl_limit(&wk, &fix, 1) == 2 * GIB - (2 * GIB - 512 * MIB) / VKTL_PSI_STEP, "less available than the reserve: the policy still takes back what is not the working set (%llu MiB)", (unsigned long long)(vktl_limit(&wk, &fix, 1) >> 20));
    vktl_in p4 = w; p4.psi_some10 = -1;
    CHECK(vktl_limit(&p4, &fix, 0) == 2 * GIB + 4 * GIB - 512 * MIB && vktl_limit(&p4, &fix, 1) == vktl_limit(&p4, &off, 0), "pressure not known: the RAM rule, and no term");
    // v0.3.1's own shape (limit_031), the regression's: a 5 percent hold at what it holds, an eighth of everything past 15
    vktl_in v1 = {.mem_total = 11 * GIB, .mem_avail = 4 * GIB, .img_bytes = 2 * GIB, .ws_bytes = 1 * GIB, .psi_some10 = 1.0};
    CHECK(limit_031(&v1) == 2 * GIB + 4 * GIB - 11 * GIB / 8, "v0.3.1, light pressure: the RAM rule with the eighth (%llu MiB)", (unsigned long long)(limit_031(&v1) >> 20));
    v1.psi_some10 = 5.0; CHECK(limit_031(&v1) == 2 * GIB, "at the hold mark: the limit is what it holds");
    v1.psi_some10 = 40.0; CHECK(limit_031(&v1) == 2 * GIB - 2 * GIB / 8, "past the shrink mark: an eighth of everything, working set or not");
    v1.psi_some10 = 20.0;
    CHECK(limit_031(&v1) == 2 * GIB - 2 * GIB / 8, "between 15 and 25 percent v0.3.1 already takes an eighth of everything back (%llu MiB)", (unsigned long long)(limit_031(&v1) >> 20));
    CHECK(vktl_limit(&v1, &fix, 1) == 2 * GIB + 4 * GIB - 11 * GIB / 8, "and the fix only holds there: its shrink mark is 25 (%llu MiB)", (unsigned long long)(vktl_limit(&v1, &fix, 1) >> 20));

    // ---- the prune target: a 16th's margin normally, none while pressed (it is what turned the hold into a storm), never under the working set ----
    CHECK(vktl_target(1536 * MIB, 0, &fix, 0) == 1536 * MIB - 1536 * MIB / 16, "no pressure: a 16th's margin");
    CHECK(vktl_target(1536 * MIB, 0, &fix, 1) == 1536 * MIB, "pressed: evict what went over and no more");
    CHECK(vktl_target(1536 * MIB, 1500 * MIB, &fix, 0) == 1500 * MIB, "a working set above the margin stays");
    CHECK(vktl_target(1792 * MIB, 1800 * MIB, &fix, 0) == 1800 * MIB, "and one above the limit stays too (%.0f MiB)", (double)(vktl_target(1792 * MIB, 1800 * MIB, &fix, 0) >> 20));
    CHECK(vktl_target(1536 * MIB, 0, &off, 0) == 1536 * MIB - 1536 * MIB / 16 && vktl_target(1536 * MIB, 0, &off, 1) == 1536 * MIB - 1536 * MIB / 16, "DM_VK_IMG_PSI=0 keeps the margin under pressure too: no policy, no margin change");

    // ---- candidates ----
    CHECK(!vktl_evictable(100, 100, 0, 0, 0, 0, 0), "used this frame: kept");
    CHECK(!vktl_evictable(100, 99, 0, 0, 0, 0, 0), "used the previous frame: kept");
    CHECK(vktl_evictable(100, 98, 0, 0, 0, 0, 0), "two frames unused: a candidate");
    CHECK(!vktl_evictable(100, 50, 1, 0, 0, 0, 0), "held: kept");
    CHECK(!vktl_evictable(100, 50, 0, 0, 1, 0, 0), "a playing video's: kept");
    CHECK(!vktl_evictable(100, 50, 0, 0, 0, 1, 0), "its submission not done: kept");
    CHECK(!vktl_evictable(100, 0, 0, 0, 0, 0, 0), "never stamped: kept");
    CHECK(!vktl_evictable(100, 50, 0, 1, 0, 0, 0) && !vktl_evictable(200, 50, 0, 1, 0, 0, 0) && !vktl_evictable(100000, 50, 0, 1, 0, 0, 0),
          "GPU content: never a candidate, however old (its storage cannot give it back; #215)");
    CHECK(!vktl_evictable(100, 50, 0, 1, 0, 0, 1) && vktl_evictable(200, 50, 0, 1, 0, 0, 1) && !vktl_evictable(200, 50, 1, 1, 0, 0, 1) && !vktl_evictable(200, 50, 0, 1, 0, 1, 1),
          "GPU content under hard memory pressure: after VKTL_GPU_FRAMES unused frames, never held or in flight");
    CHECK(vktl_order(0, 90, 1, 10) < 0 && vktl_order(0, 10, 0, 90) < 0 && vktl_order(1, 10, 1, 90) < 0, "uploadable before GPU-only, oldest first");

    // ---- the working set's window, its exclusion and its guard, through the same predicates the prune loop and vka_tex_kind_log call ----
    CHECK(vktl_keep_ws(100, 92) && !vktl_keep_ws(100, 91) && !vktl_keep_ws(100, 0), "the working set is this frame and the %d before it (92 is in, 91 is a candidate)", VKTL_WS_FRAMES);
    CHECK(vktl_skip_ws(&fix, 0, 100, 95) && !vktl_skip_ws(&fix, 0, 100, 50), "the shipped policy skips a working-set image");
    CHECK(!vktl_skip_ws(&fix, 1, 100, 95) && !vktl_skip_ws(&off, 0, 100, 95), "a pinned limit and DM_VK_IMG_PSI=0 have no working set: the build before this rule");
    CHECK(vktl_ws_active(&fix, 0) && !vktl_ws_active(&fix, 1) && !vktl_ws_active(&off, 0), "and that is vktl_ws_active, the one answer the prune and the diag share");
    CHECK(vktl_guard_ws(&fix, 0, 600 * MIB, 128 * MIB, 472 * MIB) && !vktl_guard_ws(&fix, 0, 600 * MIB, 32 * MIB, 472 * MIB), "the guard refuses an image that would leave less than the floor (or the working set)");
    CHECK(!vktl_guard_ws(&fix, 1, 600 * MIB, 128 * MIB, 472 * MIB) && !vktl_guard_ws(&off, 0, 600 * MIB, 128 * MIB, 472 * MIB), "not under a pinned limit, and not with the pressure term off");
    CHECK(vktl_guard_ws(&fix, 0, 2000 * MIB, 128 * MIB, 1900 * MIB) && vktl_keep_bytes(1900 * MIB) == 1900 * MIB, "a working set over the floor is what the guard keeps");
    CHECK(vktl_sub(600 * MIB, 600 * MIB) < vktl_keep_bytes(0), "a lone 600 MiB image is the whole cache: evicting it would leave 0, so it stays");
    CHECK(vktl_sub(600 * MIB, 32 * MIB) >= vktl_keep_bytes(0) && vktl_sub(600 * MIB, 128 * MIB) < vktl_keep_bytes(0), "of two images, the one that would cross 512 MiB is the one refused");

    // ---- a working set over the limit: uploaded once, then kept over it, not again every frame ----
    sim w1 = {.n = 1200, .use = 900, .adv = 0, .frames = 120, .each = 2 * MIB, .mem_total = 16 * GIB, .avail0 = 2048 * MIB, .psi = 0.0, .cfg = off};
    sim_out o1; sim_run(&w1, &o1);
    CHECK(o1.up == 900 && o1.up_steady == 0 && o1.ev == 0, "a working set (1,800 MiB) over the limit (1,536 MiB): %d uploads, %d after the first half, %d evicted", o1.up, o1.up_steady, o1.ev);

    // ---- the S24+ at 12 GB with the guest's 6.2 GiB heap: 3 GiB free, 500 textures of 4 MiB (2,000 MiB in all) drawn 100 a frame in a window sliding 50 a frame, so each
    //      is used 2 frames and comes back every 10; the working set is the 450 the last 8 frames touched (the 100 of the frame plus 7 x 50 new: 1,800 MiB). v0.3.1's
    //      eighth reserve leaves a 1,672 MiB limit and a 1,567 MiB target, under that working set, so it evicts what the next frames ask for; the 32nd leaves 2,560 and
    //      2,400, and the scene fits ----
    sim scene = {.n = 500, .use = 100, .adv = 50, .frames = 300, .each = 4 * MIB, .mem_total = 11203 * MIB, .avail0 = 3 * GIB, .psi = 0.0, .cfg = fix};
    sim_out of; sim_run(&scene, &of);
    CHECK(of.up == 500 && of.up_steady == 0 && of.ev == 0 && of.regret == 0, "at rest the fix's reserve leaves the whole scene: %d uploads (%d after the first half), %d evicted", of.up, of.up_steady, of.ev);
    sim s24b = scene; s24b.old_rule = 1;
    sim_out ob; sim_run(&s24b, &ob);
    CHECK(ob.ev >= 14000 && ob.regret >= 14000, "v0.3.1's eighth reserve alone costs the cache its headroom: %d evicted, %d of them re-uploaded within %d frames", ob.ev, ob.regret, VKTL_WS_FRAMES);
    CHECK(ob.up == 500 + ob.regret, "and the scene's 500 textures were uploaded %d times in all", ob.up);
    CHECK(ob.regret > 0 && !ob.ws_kept, "and it takes the working set back: %d uploads of an image it had just evicted, %d of the scene's last 8 frames left", ob.regret, (int)!ob.ws_kept);
    // the same scene under sustained pressure (PSI 20 %, past v0.3.1's 15 % shrink mark): it holds at what it has and prunes a 16th of it at every miss
    sim s24c = s24b; s24c.psi = 20.0;
    sim_out oc; sim_run(&s24c, &oc);
    sim s24d = scene; s24d.psi = 20.0;
    sim_out od; sim_run(&s24d, &od);
    CHECK(od.ws_kept && od.regret == 0 && od.up_steady == 0, "under pressure the fix keeps every texture the scene is drawing: %d uploads after the first half, %d of an image it had just evicted (%d in all)", od.up_steady, od.regret, od.up);
    CHECK(oc.regret > 0 && !oc.ws_kept, "v0.3.1 churns the same scene: %d uploads after the first half, %d of an image it had just evicted, %d evicted", oc.up_steady, oc.regret, oc.ev);
    CHECK(od.ev <= oc.ev, "the fix evicts no more than v0.3.1: %d against %d", od.ev, oc.ev);
    // and past the shrink mark (PSI 40 %) it still gives back only what is not the working set
    sim s24e = scene; s24e.psi = 40.0;
    sim_out oe; sim_run(&s24e, &oe);
    CHECK(oe.ws_kept && oe.regret == 0 && oe.up_steady == 0, "shrinking hard, the fix still keeps the working set: %d uploads after the first half, %d of an image it had just evicted, %.0f MiB resident", oe.up_steady, oe.regret, oe.resident / 1048576.0);
    CHECK(oe.resident >= oe.ws, "and its resident bytes cover the working set (%.0f of %.0f MiB)", oe.resident / 1048576.0, oe.ws / 1048576.0);
    // the same scene with DM_VK_IMG_PSI=0 at PSI 20 %: upstream's rule churns there too, and the fix's reserve has nothing to do with it
    sim s24f = scene; s24f.cfg = off; s24f.psi = 20.0;
    sim_out og; sim_run(&s24f, &og);
    CHECK(og.regret > 0 && !og.ws_kept, "DM_VK_IMG_PSI=0 in the same scene is the build before this rule: %d uploads of an image it had just evicted", og.regret);

    // ---- the pressure reader: the real file's shape, a file with only one line, and a buffer that is not there ----
    const char *real = "some avg10=2.35 avg60=1.02 avg300=0.41 total=12345678\nfull avg10=1.10 avg60=0.44 avg300=0.12 total=2345678\n";
    CHECK(vktl_psi_field(real, VKTL_PSI_SOME) > 2.34 && vktl_psi_field(real, VKTL_PSI_SOME) < 2.36, "some avg10 reads %.2f", vktl_psi_field(real, VKTL_PSI_SOME));
    CHECK(vktl_psi_field(real, VKTL_PSI_FULL) > 1.09 && vktl_psi_field(real, VKTL_PSI_FULL) < 1.11, "full avg10 reads %.2f", vktl_psi_field(real, VKTL_PSI_FULL));
    CHECK(vktl_psi_field("some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n", VKTL_PSI_FULL) < 0, "a file with only the some line (this test's, not a kernel's we know of): not known, not 0");
    CHECK(vktl_psi_field("", VKTL_PSI_SOME) < 0 && vktl_psi_field(NULL, VKTL_PSI_SOME) < 0, "an empty read or no buffer is not known");
    CHECK(vktl_psi_field("garbage\n", VKTL_PSI_SOME) < 0, "a line without the field is not known");
    CHECK(vktl_psi_field("notfull avg10=25.00 some avg10=1.00\n", VKTL_PSI_FULL) < 0, "a key inside another token is not the field");
    CHECK(vktl_psi_field("some avg10=\nsome avg10=0.25\n", VKTL_PSI_SOME) > 0.24 && vktl_psi_field("some avg10=\nsome avg10=0.25\n", VKTL_PSI_SOME) < 0.26, "a field with no number is skipped, not read as 0");
    CHECK(vktl_psi_field("some avg10=garbage\n", VKTL_PSI_SOME) < 0, "and one whose number is not one is not known");
    CHECK(vktl_psi_field("some avg10=\n25.00\n", VKTL_PSI_SOME) < 0, "a number on the next line is not this field's");
    CHECK(vktl_psi_field("some avg10=inf avg60=1\n", VKTL_PSI_SOME) < 0 && vktl_psi_field("some avg10=500.00\n", VKTL_PSI_SOME) < 0, "an infinity or a figure past 100 is not a percentage");

    // ---- a cache over the limit with half its images GPU-written and all of them long unused: only the uploadable ones go, so it stays over the limit (as logged) ----
    { enum { N = 200 }; img gi[N], *gc[N]; uint64_t bytes = N * 8 * MIB, limit = 700 * MIB; int nc = 0, gpu_evicted = 0;
      for (int i = 0; i < N; i++) gi[i] = (img){.last = (uint64_t)(10 + i), .live = 1, .gpu = i % 2};
      for (int i = 0; i < N; i++) if (vktl_evictable(5000, gi[i].last, 0, gi[i].gpu, 0, 0, 0)) gc[nc++] = &gi[i];
      qsort(gc, (size_t)nc, sizeof *gc, cmp);
      uint64_t gtarget = vktl_target(limit, 0, &fix, 0);
      for (int i = 0; i < nc && bytes > gtarget; i++) { gpu_evicted += gc[i]->gpu; gc[i]->live = 0; bytes -= 8 * MIB; }
      CHECK(nc == N / 2 && gpu_evicted == 0 && bytes > limit, "GPU-written images stay over the limit: %d candidates, %d GPU-written evicted, %llu MiB left", nc, gpu_evicted, (unsigned long long)(bytes >> 20)); }

    // ---- a scene over the limit whose images are half GPU-written, each unused for up to 149 frames (past VKTL_GPU_FRAMES): v0.3.1 took them back, and they came back
    //      empty; a GPU-written image is not a candidate unless the policy is pressed past its shrink mark ----
    sim gs = {.n = 600, .use = 4, .adv = 4, .gpu_every = 2, .frames = 600, .each = 4 * MIB, .mem_total = 11203 * MIB, .avail0 = 300 * MIB, .psi = 0.0, .cfg = fix, .old_rule = 1};
    sim_out g0; sim_run(&gs, &g0);
    sim gs_fix = gs; gs_fix.old_rule = 0; sim_out g1; sim_run(&gs_fix, &g1);
    CHECK(g0.gpu_ev > 0, "v0.3.1 takes GPU-written images back after %d unused frames (%d of them, %d images made)", VKTL_GPU_FRAMES, g0.gpu_ev, g0.up);
    CHECK(g1.gpu_ev == 0, "the policy keeps them while the system is at rest (%d taken, %d images made against %d)", g1.gpu_ev, g1.up, g0.up);
    sim gs_off = gs_fix; gs_off.cfg = off; sim_out g3; sim_run(&gs_off, &g3);
    CHECK(g3.gpu_ev == 0, "and DM_VK_IMG_PSI=0 keeps them too, as v0.3.24 did with the pressure term off (%d taken)", g3.gpu_ev);
    sim gs_pressed = gs_fix; gs_pressed.psi = 40.0; sim_out g2; sim_run(&gs_pressed, &g2);
    CHECK(g2.gpu_ev > 0, "and pressed past the shrink mark it may take them (%d of them)", g2.gpu_ev);

    // DM_VK_PRUNE_MS: 1,150 images of 2 MiB at a 2,300 MiB limit, a burst of 10 new ones, then one a frame, an eviction costing 1 ms. Without a budget one frame takes
    // the whole sixteenth (the 0.8-1.2 s freeze seen in Mario Kart World on Turnip); with 3 ms each frame evicts at most the 8 between clock reads, the prune goes on
    // over the next frames, and the cache still reaches its target
    { int most, ev, busy; uint64_t left, lim = 2300 * MIB;
      busy = run_budget(1150, 10, 2 * MIB, lim, 60, 0, 1000000, &most, &ev, &left);
      CHECK(most >= 70 && busy == 1 && ev == most && left <= lim, "no budget: one frame evicts it all (%d at most, %d frames)", most, busy);
      int ev0 = ev;
      busy = run_budget(1150, 10, 2 * MIB, lim, 60, 3000000, 1000000, &most, &ev, &left);
      CHECK(most <= 8 && busy >= 9 && ev >= ev0 && left <= lim, "a 3 ms budget: %d evictions a frame at most, over %d frames, %llu MiB left", most, busy, (unsigned long long)(left >> 20));
      busy = run_budget(1150, 10, 2 * MIB, lim, 60, 1, 1000000, &most, &ev, &left);
      CHECK(most >= 1 && left <= lim, "a budget under one eviction still moves on (%d a frame, %llu MiB left)", most, (unsigned long long)(left >> 20));
      { uint64_t tg = vktl_target(lim, 0, &fix, 0);
      CHECK(!vktl_prune_due(lim, lim, tg, 0) && vktl_prune_due(lim + 1, lim, tg, 0) && vktl_prune_due(lim, lim, tg, 1) && !vktl_prune_due(tg, lim, tg, 1) && !vktl_prune_due(lim + 1, 0, tg, 1),
            "a prune is due over the limit, or while a spread one is above its target"); }
      CHECK(!vktl_budget_spent(0, 8, ~0ull) && !vktl_budget_spent(5, 0, 9) && !vktl_budget_spent(5, 7, 9) && vktl_budget_spent(5, 8, 5) && !vktl_budget_spent(5, 8, 4),
            "the budget: none at 0, the first eviction always, the clock every 8"); }
    if (!fails) printf("t_android_texlimit: ok\n");
    return fails != 0;
}
