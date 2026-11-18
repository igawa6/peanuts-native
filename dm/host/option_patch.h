// PEANUTS: opt-in profile code words, independent of the FPS controller.
// Reuse the expected-byte transaction; allow only literal words in main text.
#pragma once
#include "fpslocker_plan.h"
static inline int dm_option_patch(const char *spec, const char *build,
                                  uint8_t *image, uint64_t text_end) {
    struct fpw_plan p = {0};
    int n = fpw_plan_parse(spec, build, &p);
    if (n < 0) return n;
    if (p.clock || p.aux) return -4;
    for (unsigned i = 0; i < p.n; i++)
        if (p.row[i].where != 'm' || p.row[i].op != 'w') return -4;
    return fpw_plan_apply(&p, image, text_end, 0, 0, 0);
}
