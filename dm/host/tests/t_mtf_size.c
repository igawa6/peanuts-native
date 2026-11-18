// Mario Tennis Fever scene size gate (android/mtf_size.c): no graphics size leaves the game's own; a smaller one is written into the size record and w9; a size at or above
// the game's pick on either side keeps the game's; another nro0 build does nothing. Every build case runs in its own process (the build is checked once).
#include "../android/mtf_size.c"
#include <assert.h>
#include <sys/wait.h>
#include <unistd.h>

static module_t test_modules[2];
module_t *g_mod = test_modules;
int g_nmod;
static void (*callbacks[OBS_MAX])(uint64_t *, int);
void gate_obs_set(int id, void (*fn)(uint64_t *, int)) { assert(id > 0 && id < OBS_MAX); callbacks[id] = fn; }
static int g_w, g_h;
void dm_graphics_size(int *w, int *h) { *w = g_w; *h = g_h; }

static void setup(const char *build) {
    g_nmod = 2;
    strcpy(g_mod[0].name, "main"); strcpy(g_mod[0].build_id, "104bce6874603701935eb76cb7d8b59e");
    strcpy(g_mod[1].name, "nro0"); g_mod[1].nro = 1; strcpy(g_mod[1].build_id, build);
}

// One call of the gate with the game's size gw x gh: the record's size and x9 after it
static void run(int gw, int gh, uint16_t *ow, uint16_t *oh, uint64_t *x9) {
    _Alignas(16) unsigned char rec[0x40];
    memset(rec, 0, sizeof rec);
    uint16_t w = (uint16_t)gw, h = (uint16_t)gh;
    memcpy(rec + R_WIDTH, &w, 2); memcpy(rec + R_HEIGHT, &h, 2);
    uint64_t r[100];
    memset(r, 0, sizeof r);
    r[20] = (uintptr_t)rec; r[9] = (uint64_t)gw;
    callbacks[OBS_MTF_SIZE](r, 0);
    memcpy(ow, rec + R_WIDTH, 2); memcpy(oh, rec + R_HEIGHT, 2); *x9 = r[9];
}

static void cases(void) {
    uint16_t w, h; uint64_t x9;
    setup(MTF_NRO_BUILD);
    g_w = g_h = 0; run(1280, 720, &w, &h, &x9); assert(w == 1280 && h == 720 && x9 == 1280);   // no graphics size: the game's own
    g_w = 960; g_h = 540; run(1280, 720, &w, &h, &x9); assert(w == 960 && h == 540 && x9 == 960);   // 540p below handheld scale 2's 1280x720
    run(1920, 1080, &w, &h, &x9); assert(w == 960 && h == 540 && x9 == 960);   // below handheld scale 0's too
    g_w = 2560; g_h = 1440; run(2048, 1152, &w, &h, &x9); assert(w == 2048 && h == 1152 && x9 == 2048);   // above the game's pick: kept
    g_w = 1280; g_h = 720; run(1280, 720, &w, &h, &x9); assert(w == 1280 && h == 720 && x9 == 1280);   // equal: kept
    g_w = 960; g_h = 1200; run(1280, 720, &w, &h, &x9); assert(w == 1280 && h == 720 && x9 == 1280);   // smaller on one side only: kept
}

static void other_build(void) {
    uint16_t w, h; uint64_t x9;
    setup("00000000000000000000000000000000");
    g_w = 960; g_h = 540; run(1280, 720, &w, &h, &x9); assert(w == 1280 && h == 720 && x9 == 1280);
}

static void in_child(void (*fn)(void), const char *what) {
    fflush(stdout);
    pid_t p = fork();
    if (!p) { fn(); _exit(0); }
    int st = 0; waitpid(p, &st, 0);
    if (!(p > 0 && WIFEXITED(st) && WEXITSTATUS(st) == 0)) { printf("FAIL t_mtf_size: %s\n", what); exit(1); }
}

int main(void) {
    assert(callbacks[OBS_MTF_SIZE]);
    in_child(cases, "nro0 1.1.1: sizes below the game's pick written, others kept");
    in_child(other_build, "another nro0 build: nothing written");
    printf("t_mtf_size: OK\n");
    return 0;
}
