// PEANUTS: the classifier of peanuts_bottleneck.h
#include "peanuts_bottleneck.h"
#include <math.h>
#include <string.h>

#define STAGE_SHARE 0.8f
#define WAIT_SHARE 0.5f
#define LATE_FACTOR 1.10f

void pn_bn_classify(const pn_bn_sample *s, int n, float budget_ms, pn_bn_result *r) {
    if (!r) return;
    memset(r, 0, sizeof *r);
    r->verdict = PN_BN_NODATA;
    if (!s || n <= 0 || !(budget_ms > 0.0f)) return;
    r->n = n;
    for (int i = 0; i < n; i++) {
        const pn_bn_sample *f = &s[i];
        if (!isfinite(f->frame_ms) || !isfinite(f->cpu_ms) || !isfinite(f->gpu_ms) || !isfinite(f->present_wait_ms)) continue;
        if (!(f->frame_ms > 0.0f) || f->cpu_ms < 0.0f || f->gpu_ms < 0.0f || f->present_wait_ms < 0.0f) continue;
        r->valid++;
        if (!(f->frame_ms > budget_ms * LATE_FACTOR)) continue;
        r->late++;
        float cf = f->cpu_ms / f->frame_ms, gf = f->gpu_ms / f->frame_ms, pf = f->present_wait_ms / f->frame_ms;
        float best = cf > gf ? cf : gf;
        if (best >= STAGE_SHARE) { if (cf >= gf) r->cpu++; else r->gpu++; }
        else if (pf >= WAIT_SHARE) r->present++;
        else r->other++;
    }
    if (!r->valid) return;
    if (!r->late) { r->verdict = PN_BN_NONE; return; }
    int top = r->cpu, v = PN_BN_CPU;
    if (r->gpu > top) { top = r->gpu; v = PN_BN_GPU; }
    if (r->present > top) { top = r->present; v = PN_BN_PRESENT; }
    if (r->other > top) { top = r->other; v = PN_BN_OTHER; }
    r->verdict = top * 2 >= r->late ? (pn_bn_verdict)v : PN_BN_MIXED;
}

const char *pn_bn_name(pn_bn_verdict v) {
    switch (v) {
    case PN_BN_NODATA: return "no data";
    case PN_BN_NONE: return "on budget";
    case PN_BN_CPU: return "CPU";
    case PN_BN_GPU: return "GPU";
    case PN_BN_PRESENT: return "present";
    case PN_BN_OTHER: return "other stall";
    case PN_BN_MIXED: return "mixed";
    }
    return "?";
}
