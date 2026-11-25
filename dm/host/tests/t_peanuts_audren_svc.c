// PEANUTS: the audio renderer service of an SDK with no renderer of its own (hle_audren_svc.c, with hle_sf.c), without a guest:
//   - the mixer: a PCM16 voice through two submixes (mix 2 -> mix 1 -> the final mix 0) to the device sink: the submixes are added deepest first, so the voice
//     reaches the output through both volume matrices; a voice whose channel count is absurd (0x80000000) plays silence and does not divide by zero;
//   - a renderer opened on a plain session lives on the host session it moves to the client (sf_session_slot), answers there, and closes with it (its thread joined).
// make GAME=<id> peanuts-audren-svc (x86 Linux, built as if for Android arm64 like the other peanuts tests)
#include "../hle.h"
#undef HLE_FORCE
#define HLE_FORCE(mangled, why, ret, cname, ...) static ret cname(__VA_ARGS__)
#undef HLE_FORCE_BIG
#define HLE_FORCE_BIG HLE_FORCE
#include "../hle_sf.c"
#include "../hle_audren_svc.c"
#include <math.h>

static int checks, fails;
#define CHECK(c, msg) do { checks++; if (!(c)) { fails++; fprintf(stderr, "FAIL: %s (line %d)\n", msg, __LINE__); } } while (0)

// The host's side the service calls, as stand-ins
static int events;
uint32_t hle_new_event(uint32_t *write_end) { events++; if (write_end) *write_end = 0x2000 + events; return 0x1000 + events; }
int hle_signal_event_handle(uint32_t write_end) { (void)write_end; return 1; }
uint32_t hle_dup_readable_handle(uint32_t h) { return h; }
static int g_pcm_opened, g_pcm_closed;
void *dm_pcmout_open(int ch) { (void)ch; g_pcm_opened++; return &g_pcm_opened; }
void dm_pcmout_close(void *k) { (void)k; g_pcm_closed++; }
void dm_pcmout_start(void *k) { (void)k; }
int dm_pcmout_append(void *k, void *b) { (void)k; (void)b; return 0; }
void *dm_pcmout_released(void *k) { (void)k; return NULL; }
void dm_pcmout_wait(void *k, int ms) { (void)k; (void)ms; }
void *guest_sym(const char *name) { (void)name; return NULL; }   // no ExecuteAudioRendererRendering: the service serves
uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + (uint64_t)t.tv_nsec; }

static void mix_set(rmix *m, int32_t id, int32_t dest, uint32_t nbuf, uint32_t off, float vol) {
    memset(m, 0, sizeof *m); m->use = 1; m->id = id; m->dest = dest; m->nbuf = nbuf; m->off = off; m->vol = vol;
}

int main(void) {
    // ---- the mixer, on a renderer set up by hand ----
    ren_t *r = ren_new(0, 9, 1);
    CHECK(r != NULL, "a renderer slot");
    r->rate = 48000; r->samples = 240; r->nv = 2;
    r->v = calloc(2, sizeof *r->v); r->res = calloc(RES_MAX, sizeof *r->res);
    static int16_t wave[480]; for (int i = 0; i < 480; i++) wave[i] = 16384;   // 0.5, mono
    vstate *s = &r->v[0];
    s->use = 1; s->play = 0; s->fmt = 2; s->rate = 48000; s->nch = 1; s->pitch = 1; s->vol = 1; s->dest = 2; s->resid[0] = 5;
    s->wb[0] = (rwb){(uint64_t)(uintptr_t)wave, sizeof wave, 0, 0, 0, 480, 0, 0}; s->valid[0] = 1;
    r->res[5][0] = 1.0f;                                     // the voice into mix 2's buffer 0
    r->nmix = 3;
    mix_set(&r->mix[0], 0, -1, 2, 0, 1.0f);                  // the final mix: buffers 0, 1
    mix_set(&r->mix[1], 1, 0, 1, 2, 1.0f);                   // mix 1 -> final: buffer 2 into 0 and 1, halves
    r->mix[1].m[0][0] = 0.5f; r->mix[1].m[0][1] = 0.5f;
    mix_set(&r->mix[2], 2, 1, 1, 3, 0.5f);                   // mix 2 -> mix 1: buffer 3 into 2 at volume 0.5
    r->mix[2].m[0][0] = 1.0f;
    r->sink_n = 2; r->sink_in[0] = 0; r->sink_in[1] = 1;
    int16_t out[FRAME_MAX * 2];
    int n = render_frame(r, out);
    CHECK(n == 240, "a frame of 240 samples at 48000 Hz");
    // 0.5 (the wave) x 0.5 (mix 2's volume) x 0.5 (mix 1's matrix) = 0.125 -> 4096 on each side
    CHECK(abs(out[0] - 4096) <= 1 && abs(out[1] - 4096) <= 1 && abs(out[2 * 200] - 4096) <= 1, "the voice reaches the sink through both submixes, the deepest first");
    // an absurd channel count from the game: silence, no division by zero (wb_len)
    s->nch = 0x80000000u;
    n = render_frame(r, out);
    CHECK(n == 240, "a voice with channel count 0x80000000 renders a frame");
    free(r->v); free(r->res); r->v = NULL; r->res = NULL; r->used = 0;

    // ---- a renderer opened on a plain session: it lives on the session it moves to the client ----
    uint32_t mgr = 0;
    CHECK(h_sf_Connect(&mgr, "audren:u") == RES_OK && mgr, "audren:u is served by the host");
    int mslot = sf_session_slot(mgr);
    CHECK(mslot >= 0, "the manager's session has a slot");
    int32_t par[8] = {48000, 240, 24, 0, 16, 1, 0, 0};
    sf_req q; memset(&q, 0, sizeof q);
    q.name = "audren:u"; q.slot = mslot; q.cmd = 0; q.in = (const uint8_t *)par; q.nin = sizeof par;
    CHECK(dm_audren_serve(&q) == RES_OK && q.move, "OpenAudioRenderer moves a session to the client");
    int rslot = sf_session_slot(q.move);
    CHECK(rslot >= 0 && rslot != mslot, "the renderer's session is its own slot");
    sf_req g; memset(&g, 0, sizeof g); g.name = "audren:r"; g.slot = rslot; g.cmd = 0;
    uint32_t rate = 0;
    CHECK(dm_audren_serve(&g) == RES_OK && g.nout == 4, "GetSampleRate on the renderer's session");
    memcpy(&rate, g.out, 4);
    CHECK(rate == 48000, "the renderer found by its session answers its own rate");
    sf_req c; memset(&c, 0, sizeof c); c.name = "audren:r"; c.slot = rslot; c.cmd = SF_CLOSE;
    CHECK(dm_audren_serve(&c) == RES_OK, "the session's close");
    CHECK(ren_find(rslot, 0) == NULL, "the renderer closed with its session");
    CHECK(g_pcm_opened == 1 && g_pcm_closed == 1, "its render thread ran and ended (the output opened and closed once)");

    fprintf(stderr, "t_peanuts_audren_svc: %d checks, %d failed\n", checks, fails);
    return fails != 0;
}
