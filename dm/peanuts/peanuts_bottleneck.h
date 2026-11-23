// PEANUTS: what limits the frame rate (ideas 72, 73; switch PN_OPT_BOTTLENECK). One sample per presented frame carries the frame interval and how long the frame spent
// on the CPU side (recording and submitting), on the GPU (timestamp queries) and blocked in acquire / present. The classifier looks at the frames over the budget and
// names the stage that filled most of the interval. The 0.8 and 0.5 shares below are starting points to tune on real captures
#pragma once

typedef struct { float frame_ms, cpu_ms, gpu_ms, present_wait_ms; } pn_bn_sample;

typedef enum {
    PN_BN_NODATA = 0,   // no valid sample
    PN_BN_NONE,         // every frame is within the budget
    PN_BN_CPU,          // most late frames: the CPU side filled the interval
    PN_BN_GPU,          // most late frames: the GPU filled the interval
    PN_BN_PRESENT,      // most late frames: neither stage was full, the frame waited on the present
    PN_BN_OTHER,        // most late frames: neither stage nor the present explains the time (a stall: I/O, a compile, a sync)
    PN_BN_MIXED         // late frames, no class holds half of them
} pn_bn_verdict;

typedef struct {
    int n, valid, late;                 // samples given, usable ones, usable ones over budget * 1.10
    int cpu, gpu, present, other;       // the late frames by class
    pn_bn_verdict verdict;
} pn_bn_result;

// A sample is usable when every field is finite, frame_ms > 0 and the others >= 0. A late frame is a stage's when that stage took 0.8 or more of the interval (the
// larger one when both did), the present's when neither did and the wait took 0.5 or more, and "other" otherwise. budget_ms <= 0: no verdict (NODATA)
void pn_bn_classify(const pn_bn_sample *s, int n, float budget_ms, pn_bn_result *r);
const char *pn_bn_name(pn_bn_verdict v);
