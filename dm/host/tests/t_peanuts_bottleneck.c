// PEANUTS: the frame-limit classifier (dm/peanuts/peanuts_bottleneck.c), run on a Linux PC: frames on budget, each class on its own, the 0.8 / 0.5 shares, which
// stage wins when both are full, the half rule for MIXED, unusable samples (NaN, infinity, negative, zero interval) and empty input.
#include "../../peanuts/peanuts_bottleneck.c"
#include <math.h>
#include <stdio.h>
#include <string.h>

static int g_fail;
static void check(int ok, const char *what) { printf("%s %s\n", ok ? "ok  " : "FAIL", what); g_fail += !ok; fflush(stdout); }

#define B 16.67f
static pn_bn_sample smp(float frame, float cpu, float gpu, float pw) { pn_bn_sample s = { frame, cpu, gpu, pw }; return s; }

int main(void) {
    pn_bn_result r;
    pn_bn_sample a[64] = { { 0 } };

    pn_bn_classify(NULL, 0, B, &r);                       check(r.verdict == PN_BN_NODATA && r.n == 0, "NULL input: no data");
    pn_bn_classify(a, 0, B, &r);                          check(r.verdict == PN_BN_NODATA, "n = 0: no data");
    a[0] = smp(20, 10, 10, 0);
    pn_bn_classify(a, 1, 0.0f, &r);                       check(r.verdict == PN_BN_NODATA, "budget 0: no data");
    pn_bn_classify(a, 1, -5.0f, &r);                      check(r.verdict == PN_BN_NODATA, "negative budget: no data");
    pn_bn_classify(a, 1, NAN, &r);                        check(r.verdict == PN_BN_NODATA, "NaN budget: no data");
    pn_bn_classify(a, 1, B, NULL);                        check(1, "NULL result does not crash");

    // on budget: 16.67 and 18.3 (just under budget * 1.10) are not late
    for (int i = 0; i < 10; i++) a[i] = smp(i % 2 ? 18.3f : 16.67f, 5, 8, 8);
    pn_bn_classify(a, 10, B, &r);                         check(r.verdict == PN_BN_NONE && r.valid == 10 && r.late == 0, "frames within budget * 1.10: on budget");
    a[0] = smp(18.4f, 5, 8, 8);
    pn_bn_classify(a, 1, B, &r);                          check(r.late == 1, "18.4 ms against 16.67 is late");

    // each class
    for (int i = 0; i < 10; i++) a[i] = smp(33.3f, 10, 31, 1);
    pn_bn_classify(a, 10, B, &r);                         check(r.verdict == PN_BN_GPU && r.gpu == 10 && r.late == 10, "GPU filled the interval: GPU");
    for (int i = 0; i < 10; i++) a[i] = smp(33.3f, 30, 12, 1);
    pn_bn_classify(a, 10, B, &r);                         check(r.verdict == PN_BN_CPU && r.cpu == 10, "CPU filled the interval: CPU");
    for (int i = 0; i < 10; i++) a[i] = smp(33.3f, 12, 14, 20);
    pn_bn_classify(a, 10, B, &r);                         check(r.verdict == PN_BN_PRESENT && r.present == 10, "stages not full, long present wait: present");
    for (int i = 0; i < 10; i++) a[i] = smp(33.3f, 12, 14, 3);
    pn_bn_classify(a, 10, B, &r);                         check(r.verdict == PN_BN_OTHER && r.other == 10, "nothing explains the time: other stall");

    // the shares: 0.8 of 33.3 is 26.64
    a[0] = smp(33.3f, 26.7f, 5, 0);  pn_bn_classify(a, 1, B, &r);  check(r.cpu == 1, "cpu at 0.80 of the frame counts");
    a[0] = smp(33.3f, 26.5f, 5, 0);  pn_bn_classify(a, 1, B, &r);  check(r.cpu == 0 && r.other == 1, "cpu just under 0.80 does not");
    a[0] = smp(33.3f, 5, 5, 16.7f);  pn_bn_classify(a, 1, B, &r);  check(r.present == 1, "present wait at 0.50 counts");
    a[0] = smp(33.3f, 5, 5, 16.5f);  pn_bn_classify(a, 1, B, &r);  check(r.other == 1, "present wait just under 0.50 does not");
    a[0] = smp(33.3f, 30, 31, 0);    pn_bn_classify(a, 1, B, &r);  check(r.gpu == 1 && r.cpu == 0, "both stages full: the larger one (GPU)");
    a[0] = smp(33.3f, 31, 30, 0);    pn_bn_classify(a, 1, B, &r);  check(r.cpu == 1 && r.gpu == 0, "both stages full: the larger one (CPU)");
    a[0] = smp(33.3f, 30, 30, 0);    pn_bn_classify(a, 1, B, &r);  check(r.cpu == 1, "a tie goes to the CPU");
    a[0] = smp(33.3f, 28, 5, 25);    pn_bn_classify(a, 1, B, &r);  check(r.cpu == 1 && r.present == 0, "a full stage comes before the present wait");

    // the half rule: 5 GPU and 5 CPU of 10 late: top * 2 >= late holds, the first of the tie wins (CPU); 4, 3, 3: mixed
    for (int i = 0; i < 4; i++) a[i] = smp(33.3f, 10, 31, 1);
    for (int i = 4; i < 7; i++) a[i] = smp(33.3f, 30, 10, 1);
    for (int i = 7; i < 10; i++) a[i] = smp(33.3f, 10, 10, 20);
    pn_bn_classify(a, 10, B, &r);                         check(r.verdict == PN_BN_MIXED && r.gpu == 4 && r.cpu == 3 && r.present == 3, "4 / 3 / 3: no class holds half: mixed");
    for (int i = 0; i < 5; i++) a[i] = smp(33.3f, 10, 31, 1);
    for (int i = 5; i < 10; i++) a[i] = smp(33.3f, 10, 10, 20);
    pn_bn_classify(a, 10, B, &r);                         check(r.verdict != PN_BN_MIXED && r.late == 10, "5 / 5 holds half: not mixed");
    // late frames among many on-budget ones: the verdict is about the late ones
    for (int i = 0; i < 60; i++) a[i] = smp(16.67f, 8, 9, 7);
    for (int i = 60; i < 64; i++) a[i] = smp(40.0f, 10, 38, 1);
    pn_bn_classify(a, 64, B, &r);                         check(r.verdict == PN_BN_GPU && r.late == 4 && r.valid == 64, "4 late GPU frames among 60 on budget: GPU");

    // unusable samples are skipped and counted out of valid
    a[0] = smp(NAN, 1, 1, 1); a[1] = smp(33.3f, INFINITY, 1, 1); a[2] = smp(33.3f, -1, 1, 1); a[3] = smp(0.0f, 1, 1, 1); a[4] = smp(-33.3f, 1, 1, 1);
    a[5] = smp(33.3f, 1, NAN, 1); a[6] = smp(33.3f, 1, 1, -0.5f);
    pn_bn_classify(a, 7, B, &r);                          check(r.n == 7 && r.valid == 0 && r.verdict == PN_BN_NODATA, "seven unusable samples: valid 0, no data");
    a[7] = smp(33.3f, 10, 31, 1);
    pn_bn_classify(a, 8, B, &r);                          check(r.n == 8 && r.valid == 1 && r.verdict == PN_BN_GPU, "one usable sample among unusable ones decides");

    // names
    check(!strcmp(pn_bn_name(PN_BN_GPU), "GPU") && !strcmp(pn_bn_name(PN_BN_NONE), "on budget") && !strcmp(pn_bn_name(PN_BN_MIXED), "mixed"), "names");
    check(!strcmp(pn_bn_name((pn_bn_verdict)99), "?"), "an unknown verdict has a placeholder name");

    printf("%s\n", g_fail ? "FAILED" : "all passed");
    return g_fail != 0;
}
