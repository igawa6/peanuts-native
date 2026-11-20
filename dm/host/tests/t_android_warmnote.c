// PEANUTS: the "Preparing graphics" notice's counters (dm/gfx/vk_android/vka_warmnote.h): shown while draws are skipped for pipelines still being made, hidden when
// none waits or nothing on screen lacks one
#include "../../gfx/vk_android/vka_warmnote.h"
#include <stdio.h>
#include <stdlib.h>
#define CHECK(c, ...) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); exit(1); } } while (0)

int main(void) {
    vkwarm s = {0}; uint64_t f = 100, warm = 0;
    CHECK(!vkwarm_step(&s, f, warm, 50), "the first frame only starts a window");
    for (int i = 1; i < VKW_WINDOW; i++) { warm += 20; CHECK(!vkwarm_step(&s, f + i, warm, 50), "not before the window ends (frame %d)", i); }
    CHECK(vkwarm_step(&s, f + VKW_WINDOW, warm, 50) && s.waiting == 50, "shown: draws skipped, 50 waiting (%d)", s.waiting);
    f += VKW_WINDOW;
    for (int i = 1; i < VKW_WINDOW; i++) CHECK(vkwarm_step(&s, f + i, warm, 40), "stays shown within a window");
    CHECK(!vkwarm_step(&s, f + VKW_WINDOW, warm, 40), "hidden: pipelines still wait, but no draw lacked one in that window");
    f += VKW_WINDOW; warm += 5;
    CHECK(!vkwarm_step(&s, f + VKW_WINDOW, warm, 0), "hidden: every pipeline made");
    f += VKW_WINDOW; warm += 5;
    CHECK(vkwarm_step(&s, f + VKW_WINDOW, warm, -3) == 0 && s.waiting == 0, "a negative count reads as none");
    vkwarm z = {0};
    CHECK(!vkwarm_step(&z, 0, 0, 9) && z.f0 == 1, "frame 0 starts a window at 1");
    puts("Preparing graphics notice: shown while draws wait for background pipelines, hidden when none waits or none is missing");
    return 0;
}
