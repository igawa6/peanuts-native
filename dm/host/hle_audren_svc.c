// PEANUTS(upstream-candidate): the audio renderer service (audren:u) for an SDK with no renderer of its own, with its own CPU mixer.
// SDK 1.3.1 (LEGO CITY UNDERCOVER, sdk 9f841a65…) links only the renderer's IPC client: no AudioRendererManagerImpl, no ExecuteAudioRendererRendering, so the host cannot run the
// SDK's renderer on the CPU as it does for every later SDK (hle_audren.c). Its client connects to audren:u through sm, which the host answers here (hle_sf.c sf_serving), and keeps
// all of its own work: it builds each update's input and checks every byte count of the output. This service answers as Horizon's does and mixes the voices itself:
//   · IAudioRendererManager: 0 OpenAudioRenderer (the parameter, the work buffer as transfer memory) → an IAudioRenderer (a domain object or a session), 1 GetWorkBufferSize → u64,
//     2 GetAudioDeviceService → an object whose requests all succeed with nothing (the SDK's device calls are taken over at their API, hle_audren.c);
//   · IAudioRenderer: 0 GetSampleRate, 1 GetSampleCount, 2 GetMixBufferCount, 3 GetState (0 started, 1 stopped), 4 RequestUpdate, 5 Start, 6 Stop, 7 QuerySystemEvent (a copy of the
//     renderer event's read end), 8 / 9 Set / GetRenderingTimeLimit. A render thread (ren_tick) renders one frame (samples a frame, 240 at 48000 Hz = 5 ms) every 5 ms while the
//     renderer is started, queues it on the host's PCM output (dm_pcmout_*, as hle_audren.c's render_main) and signals the event.
// RequestUpdate, as SDK 1.3.1's client builds and reads it (RequestUpdateAudioRenderer, sdk+0x1617d0): a 0x40-byte header each way. Input header +0 revision, +4 behavior, +8 memory
// pools, +0xc voices, +0x10 voice channel resources, +0x14 effects, +0x18 mixes, +0x1c sinks, +0x20 performance, +0x24 splitter, +0x3c total (sizes in bytes); the sections follow in
// the order behavior, memory pools, voice channel resources, voices, effects, splitter, mixes, sinks, performance. Elements, each read from the SDK's writers (docs/reference/audio.md):
//   memory pool 0x20 (+0 address, +8 size, +0x10 state);
//   voice 0x170 (UpdateVoiceInParameter, sdk+0x15f684): +8 new, +9 in use, +0xa play state 0 started / 1 stopped / 2 paused, +0xb sample format (2 PCM16, 6 ADPCM), +0xc sample rate,
//     +0x18 channels, +0x1c pitch, +0x20 volume, +0x24 two biquads (0xc each), +0x48 / +0x50 ADPCM coefficients (address, size), +0x58 destination mix id, +0x140 six channel resource
//     ids, wave buffers from +0x60, 0x38 each: +0 address, +8 size, +0x10 start and +0x14 end sample, +0x18 loop, +0x19 end of stream, +0x1a sent to the server (the client sets it for
//     every slot once it has read an output: a slot it has not marked is a buffer appended since), +0x20 / +0x28 ADPCM context address and size (six bytes: predictor and scale, two
//     history samples);
//   voice channel resource 0x70 (sdk+0x15f840): +0 id, +4 24 volumes (one per destination mix buffer), +0x64 in use;
//   mix 0x930 (a copy of the SDK's MixInfo): +0 volume, +4 rate, +8 buffer count, +0xc in use, +0x10 id (the final mix 0), +0x24 24 × 24 volumes (source buffer × 0x60 + destination ×
//     4), +0x924 destination mix id; there is no buffer offset in it: a mix's first buffer is the sum of the buffer counts of the mixes before it (the final mix first);
//   sink 0x140 (SinkInfo::StoreInParameter, sdk+0x15d4f8): +0 type (1 device), +1 in use, +0x20 name, +0x120 input count (at most 6), +0x124 input buffer indices of the final mix;
//   effect 0xc0 (the game uses none; ignored).
// Output header +4 behavior (0xb0), +8 memory pools (0x10 each: +0 state), +0xc voices (0x10 each: u64 played samples, u32 wave buffers consumed, u32 drops), +0x14 effects (0x10
// each), +0x1c sinks (0x20 each), +0x20 performance (0x10), +0x3c total, which must equal the client's output buffer (sdk+0x161b30); the sections follow in the order memory pools,
// voices, effects, sinks, performance, behavior (MemoryPoolManager / VoiceInfoManager / EffectManager / SinkManager / PerformanceBufferManager / BehaviorManager UpdateOutStatus).
// A memory pool asked to attach (4) is attached (5), one asked to detach (2) detached (3).
// Rendering (render_frame): the voices' PCM16 or DSP-ADPCM wave buffers are decoded and resampled (linear, voice rate / renderer rate × pitch), run through the voice's two biquad filters, scaled by the voice volume and each
// channel resource's destination volumes, added into the destination mix's buffers; the submixes are added into their destinations through their volume matrices, and the sink's
// input buffers of the final mix are downmixed to stereo (L = FL + 0.707 FC + 0.707 BL, R = FR + 0.707 FC + 0.707 BR, no LFE) and clamped to PCM16. The voices' played samples and
// consumed wave buffers come from this rendering, so the game's GetReleasedWaveBuffer and played sample counts move as on a console; a stopped voice gives back all its buffers.
// Open: effects, splitters and the performance buffer are ignored; a renderer rate other than 48000 is only
// linearly resampled per block (docs/reference/audio.md "An SDK with no renderer of its own")
#include "hle.h"
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <time.h>

void *dm_pcmout_open(int ch);
void dm_pcmout_close(void *k);
void dm_pcmout_start(void *k);
int dm_pcmout_append(void *k, void *b);
void *dm_pcmout_released(void *k);
void dm_pcmout_wait(void *k, int ms);

enum { REN_MAX = 4, VOICE_MAX = 1024, WB = 4, IN_POOL = 0x20, IN_VOICE = 0x170, IN_EFFECT = 0xc0, IN_SINK = 0x140, IN_VRES = 0x70, IN_MIX = 0x930, OUT_BEHAVIOR = 0xb0, OUT_PERF = 0x10,
       FRAME_NS = 5000000, FRAME_MAX = 1024, NBUF = PLAT_APPLE ? 4 : 8, MIXBUF = 24, MIX_MAX = 32, RES_MAX = 1024, OUT_RATE = 48000, DATA_MAX = 0x40000000 };
typedef struct { void *ptr; uint64_t cap, size; } gbuf;   // same as hle_audren.c's
typedef struct { uint64_t addr, size, ctx, ctxsz; int32_t start, end; uint8_t loop, eos; } rwb;
typedef struct {   // one voice: its parameters from the last update, and where the render thread is in its wave buffers
    uint8_t valid[WB]; rwb wb[WB]; unsigned idx; double pos, pf; uint64_t played; uint32_t consumed;
    uint8_t use, play, fmt; uint32_t rate, nch, resid[6]; int32_t dest; float pitch, vol;
    int16_t coef[16]; int bufinit; int64_t dnext; int16_t d0, d1, h1, h2;   // the ADPCM decoder: the next sample to decode, the last two decoded, the history
    uint8_t warned;
    float bq[2][5];                          // the two biquads: b0 b1 b2 a1 a2, Q14 divided out (a1, a2 stored negated by the game)
    uint8_t bq_on[2]; float bqh[2][6][4];   // enable; per filter and channel the history x1 x2 y1 y2
} vstate;
typedef struct { uint8_t use; int32_t id, dest; uint32_t nbuf, off; float vol, m[MIXBUF][MIXBUF]; } rmix;
typedef struct {
    int used, slot, kind;                    // kind 1 renderer, 2 audio device object
    unsigned obj;
    int32_t rate, samples, mixbufs, voices;
    uint32_t ev_r, ev_w, limit;
    _Atomic int open, started;
    _Atomic uint64_t frames;
    pthread_t tick; int ticking;
    pthread_mutex_t mu;                      // the state below: RequestUpdate writes it, the render thread reads it (never held across the output's wait)
    vstate *v; int nv;
    float (*res)[MIXBUF];                    // channel resource volumes, RES_MAX × 24
    rmix mix[MIX_MAX]; int nmix;
    int sink_n; int8_t sink_in[6];
    int logged;
    float mb[MIXBUF][FRAME_MAX], tmp[6][FRAME_MAX];   // the mix buffers of a frame, a voice's decoded channels
    int16_t pcm[NBUF][FRAME_MAX * 2]; gbuf bufs[NBUF];   // the blocks its output plays from (the output keeps the pointer until the block is released)
} ren_t;
static ren_t g_ren[REN_MAX * 2];
Result dm_audren_serve(sf_req *q);
static pthread_mutex_t g_ren_mu = PTHREAD_MUTEX_INITIALIZER;

int dm_audren_served(void) {   // the SDK has no renderer of its own (hle_audren.c runs the SDK's on the CPU otherwise)
    static int v = -1;
    if (v < 0) v = !guest_sym("_ZN2nn5audio6server24AudioRendererManagerImpl17AudioRendererImpl29ExecuteAudioRendererRenderingEv")
                && !guest_sym("_ZN2nn5audio6server17AudioRenderSystem29ExecuteAudioRendererRenderingEv");
    return v;
}

static ren_t *ren_find(int slot, unsigned obj) {
    for (int i = 0; i < REN_MAX * 2; i++) if (g_ren[i].used && g_ren[i].slot == slot && g_ren[i].obj == obj) return &g_ren[i];
    return NULL;
}
static ren_t *ren_new(int slot, unsigned obj, int kind) {
    for (int i = 0; i < REN_MAX * 2; i++) if (!g_ren[i].used) { memset(&g_ren[i], 0, sizeof g_ren[i]); g_ren[i].used = 1; g_ren[i].slot = slot; g_ren[i].obj = obj; g_ren[i].kind = kind; return &g_ren[i]; }
    return NULL;
}

// ---------- decoding ----------
static float pcm16_at(const rwb *w, unsigned nch, unsigned c, int64_t i) {
    uint64_t o = (uint64_t)i * nch + c;
    if (i < 0 || (o + 1) * 2 > w->size) return 0;
    int16_t v; memcpy(&v, (const uint8_t *)(uintptr_t)w->addr + o * 2, 2);
    return (float)v * (1.0f / 32768.0f);
}
static int adpcm_step(vstate *s, const rwb *w) {   // decodes sample s->dnext of the buffer (8-byte frames of 14 samples); 0 past the data
    int64_t f = s->dnext / 14; int k = (int)(s->dnext % 14);
    if (s->dnext < 0 || (uint64_t)(f + 1) * 8 > w->size) return 0;
    const uint8_t *fr = (const uint8_t *)(uintptr_t)w->addr + f * 8;
    int pred = (fr[0] >> 4) & 7, sc = fr[0] & 15, nib = fr[1 + k / 2]; nib = (k & 1) ? nib & 15 : nib >> 4; nib = (nib ^ 8) - 8;
    int x = nib * (1 << sc) * 2048 + 1024 + s->coef[2 * pred] * s->h1 + s->coef[2 * pred + 1] * s->h2; x >>= 11;
    int16_t o = (int16_t)(x > 32767 ? 32767 : x < -32768 ? -32768 : x);
    s->h2 = s->h1; s->h1 = o; s->d0 = s->d1; s->d1 = o; s->dnext++;
    return 1;
}
static float adpcm_at(const vstate *s, int64_t i) { return i == s->dnext - 1 ? s->d1 * (1.0f / 32768.0f) : i == s->dnext - 2 ? s->d0 * (1.0f / 32768.0f) : 0; }
static int64_t wb_len(const vstate *s, const rwb *w) {   // samples of the buffer from its start sample, within its data
    int64_t have = s->fmt == 6 ? (int64_t)(w->size / 8) * 14 : (int64_t)(w->size / (2 * (uint64_t)(s->nch ? s->nch : 1)));   // (64-bit: a channel count of 0x80000000 from the game made the 32-bit product 0, a division by zero)
    int64_t e = w->end < have ? w->end : have;
    return e > w->start ? e - w->start : 0;
}
static void wb_begin(vstate *s, const rwb *w, int restart) {   // a buffer starts, or loops
    s->bufinit = 1; s->pos = 0;
    if (s->fmt != 6) return;
    s->dnext = (int64_t)(w->start / 14) * 14; s->d0 = s->d1 = 0;
    if (w->ctxsz >= 6 && w->ctx && (restart || w->start > 0)) {   // the context is the history at a loop or mid-stream start; a stream's first buffer starts from the carried history
        int16_t c[3]; memcpy(c, (const void *)(uintptr_t)w->ctx, 6); s->h1 = c[1]; s->h2 = c[2];
    }
}
// PEANUTS(upstream-candidate): the voice biquads (LEGO CITY UNDERCOVER's low-pass), Q14 with a1/a2 stored negated
// Filters the channels x[c][0..n) of a voice with its enabled biquads, filter 0 then filter 1: y = b0 x + b1 x1 + b2 x2 + a1 y1 + a2 y2. A result that is not finite resets that filter's history and passes the input through.
static void voice_biquads(vstate *s, float (*x)[FRAME_MAX], unsigned nch, int n) {
    for (int f = 0; f < 2; f++) {
        if (!s->bq_on[f]) continue;
        const float *k = s->bq[f];
        for (unsigned c = 0; c < nch; c++) {
            float *h = s->bqh[f][c];
            for (int j = 0; j < n; j++) {
                float xn = x[c][j];
                float y = k[0] * xn + k[1] * h[0] + k[2] * h[1] + k[3] * h[2] + k[4] * h[3];
                if (!isfinite(y)) { memset(h, 0, 4 * sizeof(float)); continue; }
                h[1] = h[0]; h[0] = xn; h[3] = h[2]; h[2] = y;
                x[c][j] = y;
            }
        }
    }
}
// Decodes one frame of a voice into r->tmp[ch][0..n) and moves it on; returns the number of channels decoded (0: silent)
static void voice_render(ren_t *r, vstate *s, int n) {
    unsigned nch = s->nch > 6 ? 6 : s->nch ? s->nch : 1;
    for (unsigned c = 0; c < nch; c++) memset(r->tmp[c], 0, (size_t)n * sizeof(float));
    double step = (double)(s->rate ? s->rate : (uint32_t)r->rate) / r->rate * s->pitch;
    for (int j = 0; j < n; j++) {
        int64_t len = 0; const rwb *w = NULL;
        for (int guard = 0; guard < 2 * WB + 2; guard++) {
            if (!s->valid[s->idx]) {   // the next buffer in ring order, if any
                int k = 1; while (k < WB && !s->valid[(s->idx + k) % WB]) k++;
                if (k == WB) break;
                s->idx = (s->idx + k) % WB; s->bufinit = 0;
            }
            w = &s->wb[s->idx];
            if (!s->bufinit) wb_begin(s, w, 0);
            len = wb_len(s, w);
            if (s->pos < len) break;
            if (w->loop && len > 0) { wb_begin(s, w, 1); if (s->pos < len) break; }
            s->valid[s->idx] = 0; s->consumed++; s->idx = (s->idx + 1) % WB; s->bufinit = 0; w = NULL;
        }
        if (!w || !s->valid[s->idx] || s->pos >= len) break;   // nothing left to play: the rest of the frame is silent
        int64_t i0 = (int64_t)s->pos, i1 = i0 + 1 < len ? i0 + 1 : i0; float fr = (float)(s->pos - (double)i0);
        if (s->fmt == 6) {
            while (s->dnext <= w->start + i1) if (!adpcm_step(s, w)) break;
            float a = adpcm_at(s, w->start + i0), b = adpcm_at(s, w->start + i1);
            r->tmp[0][j] = a + (b - a) * fr;
        } else for (unsigned c = 0; c < nch; c++) {
            float a = pcm16_at(w, s->nch, c, w->start + i0), b = pcm16_at(w, s->nch, c, w->start + i1);
            r->tmp[c][j] = a + (b - a) * fr;
        }
        s->pos += step; s->pf += step; s->played = (uint64_t)s->pf;
    }
    voice_biquads(s, r->tmp, nch, n);
}
static rmix *mix_by_id(ren_t *r, int32_t id) {
    for (int k = 0; k < r->nmix; k++) if (r->mix[k].use && r->mix[k].id == id) return &r->mix[k];
    return NULL;
}
static int mix_depth(ren_t *r, const rmix *m) {   // hops to a mix with no destination
    int d = 0; for (const rmix *x = m; x && d < MIX_MAX; d++) x = mix_by_id(r, x->dest);
    return d;
}
// One frame (r->samples samples at the renderer's rate) into out (interleaved stereo PCM16 at 48000 Hz); returns its frames. r->mu is held.
static int render_frame(ren_t *r, int16_t *out) {
    int n = r->samples > FRAME_MAX ? FRAME_MAX : r->samples > 0 ? r->samples : 240;
    for (int b = 0; b < MIXBUF; b++) memset(r->mb[b], 0, (size_t)n * sizeof(float));
    for (int i = 0; i < r->nv; i++) {
        vstate *s = &r->v[i];
        if (!s->use || s->play != 0 || (s->fmt != 2 && s->fmt != 6)) continue;
        voice_render(r, s, n);
        const rmix *d = mix_by_id(r, s->dest);
        unsigned nch = s->nch > 6 ? 6 : s->nch ? s->nch : 1; if (s->fmt == 6) nch = 1;
        if (!d) continue;
        for (unsigned c = 0; c < nch; c++) {
            if (s->resid[c] >= RES_MAX) continue;
            const float *rv = r->res[s->resid[c]];
            for (uint32_t b = 0; b < d->nbuf && d->off + b < MIXBUF; b++) {
                float g = s->vol * rv[b]; if (g == 0) continue;
                float *dst = r->mb[d->off + b]; const float *src = r->tmp[c];
                for (int j = 0; j < n; j++) dst[j] += src[j] * g;
            }
        }
    }
    int dep[MIX_MAX], deepest = 0;   // each mix's depth, once a frame (the loop below asked mix_depth for every mix at every depth: up to 32 x 32 walks of 32 hops, 200 frames a second)
    for (int k = 0; k < r->nmix; k++) { dep[k] = mix_depth(r, &r->mix[k]); if (dep[k] > deepest) deepest = dep[k]; }
    for (int depth = deepest; depth >= 1; depth--)   // the submixes, the deepest first
        for (int k = 0; k < r->nmix; k++) {
            const rmix *m = &r->mix[k]; rmix *d;
            if (!m->use || dep[k] != depth || !(d = mix_by_id(r, m->dest))) continue;
            for (uint32_t s = 0; s < m->nbuf && m->off + s < MIXBUF; s++)
                for (uint32_t b = 0; b < d->nbuf && d->off + b < MIXBUF; b++) {
                    float g = m->vol * m->m[s][b]; if (g == 0) continue;
                    float *dst = r->mb[d->off + b]; const float *src = r->mb[m->off + s];
                    for (int j = 0; j < n; j++) dst[j] += src[j] * g;
                }
        }
    const rmix *fin = mix_by_id(r, 0);
    float l[FRAME_MAX], rr[FRAME_MAX];
    memset(l, 0, (size_t)n * sizeof(float)); memset(rr, 0, (size_t)n * sizeof(float));
    if (fin && r->sink_n > 0) {
        float fv = fin->vol; const float *ch[6] = {0};
        for (int i = 0; i < r->sink_n && i < 6; i++) { int ix = r->sink_in[i]; if (ix >= 0 && (uint32_t)ix < fin->nbuf && fin->off + ix < MIXBUF) { ch[i] = r->mb[fin->off + ix]; } }
        for (int j = 0; j < n; j++) {
            float a = ch[0] ? ch[0][j] : 0, b = ch[1] ? ch[1][j] : (r->sink_n == 1 ? a : 0);
            if (r->sink_n >= 3) { float c = ch[2] ? ch[2][j] * 0.707f : 0; a += c; b += c; }
            if (r->sink_n >= 6) { a += ch[4] ? ch[4][j] * 0.707f : 0; b += ch[5] ? ch[5][j] * 0.707f : 0; }
            l[j] = a * fv; rr[j] = b * fv;
        }
    }
    int no = r->rate == OUT_RATE || r->rate <= 0 ? n : (int)((int64_t)n * OUT_RATE / r->rate); if (no > FRAME_MAX) no = FRAME_MAX;
    for (int j = 0; j < no; j++) {   // 48000 Hz output; another renderer rate is resampled linearly per block (open)
        float a, b;
        if (no == n) { a = l[j]; b = rr[j]; }
        else { float p = (float)j * (float)r->rate / OUT_RATE; int i0 = (int)p; int i1 = i0 + 1 < n ? i0 + 1 : i0; float f = p - (float)i0; a = l[i0] + (l[i1] - l[i0]) * f; b = rr[i0] + (rr[i1] - rr[i0]) * f; }
        a *= 32768.0f; b *= 32768.0f;
        out[2 * j] = (int16_t)(a > 32767 ? 32767 : a < -32768 ? -32768 : a);
        out[2 * j + 1] = (int16_t)(b > 32767 ? 32767 : b < -32768 ? -32768 : b);
    }
    return no;
}

// ---------- the render thread ----------
// One block per 5 ms by the clock (0.5 % fast, so the output's queue fills in the end and the output paces the thread), as hle_audren.c's render_main
static void *ren_tick(void *arg) {
    ren_t *r = arg;
    void *out = dm_pcmout_open(2);
    gbuf *free_list[NBUF]; int nfree = NBUF;
    for (int i = 0; i < NBUF; i++) { r->bufs[i] = (gbuf){r->pcm[i], sizeof r->pcm[i], 0}; free_list[i] = &r->bufs[i]; }
    dm_pcmout_start(out);
    uint64_t next = now_ns(); int was = 0, rate_logged = 0;
    while (atomic_load(&r->open)) {
        if (!atomic_load(&r->started)) { was = 0; struct timespec ts = {0, FRAME_NS}; nanosleep(&ts, NULL); continue; }
        if (!was) { next = now_ns(); was = 1; }
        int held = 0;
        while (!nfree) {   // wait for the output to hand a block back
            gbuf *b = dm_pcmout_released(out);
            if (b) { free_list[nfree++] = b; break; }
            held = 1; dm_pcmout_wait(out, 10);
            if (!atomic_load(&r->open)) break;
        }
        if (!nfree) break;
        uint64_t t = now_ns();
        if (t > next + (uint64_t)FRAME_NS * NBUF) next = t;
        else if (held && t > next) next = t;
        else if (next > t) { struct timespec ts = {(time_t)((next - t) / 1000000000ull), (long)((next - t) % 1000000000ull)}; nanosleep(&ts, NULL); }
        next += FRAME_NS - FRAME_NS / 200;
        gbuf *b = free_list[--nfree];
        pthread_mutex_lock(&r->mu);
        int n = render_frame(r, (int16_t *)b->ptr);
        pthread_mutex_unlock(&r->mu);
        b->size = (uint64_t)n * 4;
        if (!dm_pcmout_append(out, b)) free_list[nfree++] = b;
        if (r->rate != OUT_RATE && !rate_logged++) LOG("[RED] audren: the renderer runs at %d Hz; its blocks are resampled linearly per block to %d Hz", r->rate, OUT_RATE);
        atomic_fetch_add(&r->frames, 1);
        if (r->ev_w) hle_signal_event_handle(r->ev_w);
    }
    dm_pcmout_close(out);
    return NULL;
}
static void ren_close(ren_t *r) {
    if (r->kind == 1) {
        atomic_store(&r->open, 0);
        if (r->ticking) pthread_join(r->tick, NULL);
        LOG("audren: renderer closed (%llu frames)", (unsigned long long)atomic_load(&r->frames));
    }
    free(r->v); free(r->res); r->v = NULL; r->res = NULL; r->used = 0;
}

static Result open_renderer(sf_req *q) {
    if (q->nin < 0x20) { LOG("[RED] audren: OpenAudioRenderer with %zu bytes of arguments", q->nin); return RES_FAIL; }
    const int32_t *p = (const int32_t *)q->in;
    pthread_mutex_lock(&g_ren_mu);
    unsigned obj = q->domain ? sf_new_object(q->slot) : 0;
    uint32_t sess = q->domain ? 0 : sf_new_session("audren:r", dm_audren_serve);
    ren_t *r = ren_new(q->domain ? q->slot : sf_session_slot(sess), obj, 1);
    if (!r) { pthread_mutex_unlock(&g_ren_mu); LOG("[RED] audren: more than %d renderers open", REN_MAX); return RES_FAIL; }
    r->rate = p[0] > 0 ? p[0] : 48000; r->samples = p[1] > 0 ? p[1] : 240; r->mixbufs = p[2]; r->voices = p[4]; r->limit = 100;
    r->nv = r->voices > 0 && r->voices <= VOICE_MAX ? r->voices : VOICE_MAX;
    r->v = calloc((size_t)r->nv, sizeof *r->v); r->res = calloc(RES_MAX, sizeof *r->res);
    if (!r->v || !r->res) die("audren: voice state calloc");
    pthread_mutex_init(&r->mu, NULL);
    r->ev_r = hle_new_event(&r->ev_w);
    atomic_store(&r->open, 1);
    r->ticking = !pthread_create(&r->tick, NULL, ren_tick, r);
    pthread_mutex_unlock(&g_ren_mu);
    if (q->domain) q->child = obj; else q->move = sess;
    char hx[3 * 0x40 + 1] = ""; for (size_t k = 0; k < 0x40 && k < q->nin; k++) snprintf(hx + 3 * k, 4, "%02x ", q->in[k]);
    LOG("audren: OpenAudioRenderer served by the host (the SDK has no renderer of its own): rate %d, %d samples a frame, %d mix buffers, %d voices, mixed on the CPU. Arguments: %s",
        r->rate, r->samples, r->mixbufs, r->voices, hx);
    return RES_OK;
}

// ---------- the update ----------
static void voice_update(ren_t *r, int i, const uint8_t *vin, uint8_t *out) {
    vstate *s = &r->v[i];
    if (vin[8] || !vin[9]) { memset(s, 0, sizeof *s); if (!vin[9]) { memset(out, 0, 16); return; } }   // a new voice, or a free one
    s->use = vin[9]; s->play = vin[0xa]; s->fmt = vin[0xb];
    memcpy(&s->rate, vin + 0xc, 4); memcpy(&s->nch, vin + 0x18, 4); memcpy(&s->pitch, vin + 0x1c, 4); memcpy(&s->vol, vin + 0x20, 4);
    memcpy(&s->dest, vin + 0x58, 4); memcpy(s->resid, vin + 0x140, sizeof s->resid);
    if (!(s->pitch > 0 && s->pitch < 16)) s->pitch = 1;
    if (!(s->vol >= 0 && s->vol <= 16)) s->vol = 1;
    for (int f = 0; f < 2; f++) {   // the in-parameter's biquad[f]: enable, then b0 b1 b2 a1 a2 (s16 at +2..+0xb)
        const uint8_t *b = vin + 0x24 + 0xc * f; int16_t c[5]; memcpy(c, b + 2, sizeof c);
        uint8_t on = b[0] ? 1 : 0;
        if (on && !s->bq_on[f]) memset(s->bqh[f], 0, sizeof s->bqh[f]);   // switched on: its history starts again (a new voice was cleared above)
        s->bq_on[f] = on;
        for (int k = 0; k < 5; k++) s->bq[f][k] = c[k] * (1.0f / 16384.0f);
    }
    if (s->fmt == 6) {
        uint64_t ca, cs; memcpy(&ca, vin + 0x48, 8); memcpy(&cs, vin + 0x50, 8);
        if (ca && cs >= 0x20) memcpy(s->coef, (const void *)(uintptr_t)ca, 0x20);
    }
    if (s->fmt != 2 && s->fmt != 6 && !s->warned++) LOG("[RED] audren: voice %d has sample format %u, which is not mixed (2 PCM16 and 6 ADPCM are)", i, s->fmt);
    for (int k = 0; k < WB; k++) {
        const uint8_t *wb = vin + 0x60 + 0x38 * k;
        if (wb[0x1a] || s->valid[k]) continue;   // already known
        rwb *w = &s->wb[k];
        memcpy(&w->addr, wb, 8); memcpy(&w->size, wb + 8, 8); memcpy(&w->start, wb + 0x10, 4); memcpy(&w->end, wb + 0x14, 4);
        memcpy(&w->ctx, wb + 0x20, 8); memcpy(&w->ctxsz, wb + 0x28, 8); w->loop = wb[0x18]; w->eos = wb[0x19];
        if (!w->addr && !w->size && w->end <= w->start) continue;   // an empty slot
        if (w->addr && w->size && w->size <= DATA_MAX && w->start >= 0 && w->end > w->start && (s->fmt == 2 || s->fmt == 6)) { if (!s->valid[s->idx]) s->idx = (unsigned)k; s->valid[k] = 1; }
        else s->consumed++;   // not playable (no data, an absurd size, a format not mixed): handed back at once
    }
    if (s->play == 1) {   // stopped: every buffer goes back, the count starts again
        for (int k = 0; k < WB; k++) if (s->valid[k]) { s->valid[k] = 0; s->consumed++; }
        s->played = 0; s->pf = 0; s->pos = 0; s->bufinit = 0;
    }
    memcpy(out, &s->played, 8); memcpy(out + 8, &s->consumed, 4); memset(out + 12, 0, 4);
}

static Result request_update(ren_t *r, sf_req *q) {
    sf_buf in = q->na ? q->a[0] : q->x[0], out = q->nb ? q->b[0] : q->c[0];
    if (!in.addr || in.size < 0x40 || !out.addr || out.size < 0x40) { LOG("[RED] audren: RequestUpdate without its buffers (in %#llx+%#llx, out %#llx+%#llx; A %d B %d X %d C %d)",
        (unsigned long long)in.addr, (unsigned long long)in.size, (unsigned long long)out.addr, (unsigned long long)out.size, q->na, q->nb, q->nx, q->nc); return RES_FAIL; }
    const uint8_t *ib = (const uint8_t *)(uintptr_t)in.addr; uint8_t *ob = (uint8_t *)(uintptr_t)out.addr;
    uint32_t h[16]; memcpy(h, ib, sizeof h);
    uint32_t beh = h[1], pools = h[2], voices = h[3], vres = h[4], effects = h[5], mixes = h[6], sinks = h[7], perf = h[8], split = h[9];
    size_t o_pool = 0x40 + beh, o_vres = o_pool + pools, o_voice = o_vres + vres, o_mix = o_voice + voices + effects + split, o_sink = o_mix + mixes,
           o_end = o_sink + sinks + perf;
    if (o_end > in.size) { LOG("[RED] audren: RequestUpdate input of %#llx bytes says %#zx", (unsigned long long)in.size, o_end); return RES_FAIL; }
    unsigned np = pools / IN_POOL, nvo = voices / IN_VOICE, ne = effects / IN_EFFECT, ns = sinks / IN_SINK, nres = vres / IN_VRES, nmx = mixes / IN_MIX;
    size_t total = 0x40 + np * 0x10 + nvo * 0x10 + ne * 0x10 + ns * 0x20 + OUT_PERF + OUT_BEHAVIOR;
    if (total > out.size) { LOG("[RED] audren: RequestUpdate output needs %#zx bytes, the client gave %#llx", total, (unsigned long long)out.size); return RES_FAIL; }
    memset(ob, 0, total);
    uint32_t oh[16] = {h[0], OUT_BEHAVIOR, np * 0x10, nvo * 0x10, 0, ne * 0x10, 0, ns * 0x20, OUT_PERF};
    oh[15] = (uint32_t)total; memcpy(ob, oh, sizeof oh);
    uint8_t *o = ob + 0x40;
    pthread_mutex_lock(&r->mu);
    for (unsigned k = 0; k < np; k++, o += 0x10) {
        uint32_t st; memcpy(&st, ib + o_pool + k * IN_POOL + 0x10, 4);
        st = st == 4 ? 5 : st == 2 ? 3 : st;   // attach / detach as asked
        memcpy(o, &st, 4);
    }
    for (unsigned k = 0; k < nres; k++) {   // channel resources: 24 destination volumes each
        const uint8_t *e = ib + o_vres + k * IN_VRES; uint32_t id; memcpy(&id, e, 4);
        if (id >= RES_MAX) continue;
        if (e[0x64]) memcpy(r->res[id], e + 4, sizeof r->res[id]); else memset(r->res[id], 0, sizeof r->res[id]);
    }
    for (unsigned k = 0; k < nvo; k++, o += 0x10)
        if ((int)k < r->nv) voice_update(r, (int)k, ib + o_voice + k * IN_VOICE, o);
    r->nmix = nmx > MIX_MAX ? MIX_MAX : (int)nmx;
    uint32_t off = 0;
    for (int k = 0; k < r->nmix; k++) {   // a mix's first buffer is the running sum of the buffer counts of the in-use mixes before it
        const uint8_t *e = ib + o_mix + (size_t)k * IN_MIX; rmix *m = &r->mix[k];
        m->use = e[0xc]; memcpy(&m->vol, e, 4); memcpy(&m->nbuf, e + 8, 4); memcpy(&m->id, e + 0x10, 4); memcpy(&m->dest, e + 0x924, 4);
        for (int s = 0; s < MIXBUF; s++) memcpy(m->m[s], e + 0x24 + s * 0x60, 0x60);
        if (m->nbuf > MIXBUF) m->nbuf = MIXBUF;
        m->off = off; if (m->use) off += m->nbuf;
    }
    r->sink_n = 0;
    for (unsigned k = 0; k < ns; k++) {   // the first device sink in use
        const uint8_t *e = ib + o_sink + k * IN_SINK; uint32_t cnt;
        if (e[0] != 1 || !e[1]) continue;
        memcpy(&cnt, e + 0x120, 4); r->sink_n = cnt > 6 ? 6 : (int)cnt; memcpy(r->sink_in, e + 0x124, 6);
        break;
    }
    pthread_mutex_unlock(&r->mu);
    if (!r->logged++) LOG("audren: first RequestUpdate: revision %.4s, %u memory pools, %u voices, %u channel resources, %u effects, %u mixes, %u sinks (%d inputs: %d %d %d %d %d %d); output %#zx of %#llx bytes; %llu frames since the start",
        (const char *)ib, np, nvo, nres, ne, nmx, ns, r->sink_n, r->sink_in[0], r->sink_in[1], r->sink_in[2], r->sink_in[3], r->sink_in[4], r->sink_in[5], total, (unsigned long long)out.size,
        (unsigned long long)atomic_load(&r->frames));
    return RES_OK;   // effects, sinks, performance and behavior (no errors) stay zero
}

static void put32(sf_req *q, uint32_t v) { memcpy(q->out, &v, 4); q->nout = 4; }
Result dm_audren_serve(sf_req *q) {
    pthread_mutex_lock(&g_ren_mu);
    ren_t *r = ren_find(q->slot, q->obj);
    if (q->cmd == SF_CLOSE) {   // an object, or a whole session with its objects
        for (int i = 0; i < REN_MAX * 2; i++)
            if (g_ren[i].used && g_ren[i].slot == q->slot && (q->obj == 0 || g_ren[i].obj == q->obj)) { ren_t *c = &g_ren[i]; pthread_mutex_unlock(&g_ren_mu); ren_close(c); pthread_mutex_lock(&g_ren_mu); }
        pthread_mutex_unlock(&g_ren_mu); return RES_OK;
    }
    pthread_mutex_unlock(&g_ren_mu);
    if (r && r->kind == 2) return RES_OK;   // the audio device object: every request succeeds with nothing
    if (!r) switch (q->cmd) {   // IAudioRendererManager
        case 0: return open_renderer(q);
        case 1: { uint64_t n = 0x100000; memcpy(q->out, &n, 8); q->nout = 8; return RES_OK; }   // the work buffer: the service uses none of it here
        case 2: {
            pthread_mutex_lock(&g_ren_mu);
            unsigned obj = q->domain ? sf_new_object(q->slot) : 0;
            uint32_t sess = q->domain ? 0 : sf_new_session("audren:d", dm_audren_serve);
            ren_new(q->domain ? q->slot : sf_session_slot(sess), obj, 2);
            pthread_mutex_unlock(&g_ren_mu);
            if (q->domain) q->child = obj; else q->move = sess;
            return RES_OK;
        }
        default: LOG("[RED] audren: manager command %u not served", q->cmd); return RES_OK;
    }
    switch (q->cmd) {   // IAudioRenderer
        case 0: put32(q, (uint32_t)r->rate); return RES_OK;
        case 1: put32(q, (uint32_t)r->samples); return RES_OK;
        case 2: put32(q, (uint32_t)r->mixbufs); return RES_OK;
        case 3: put32(q, atomic_load(&r->started) ? 0 : 1); return RES_OK;
        case 4: return request_update(r, q);
        case 5: atomic_store(&r->started, 1); return RES_OK;
        case 6: atomic_store(&r->started, 0); return RES_OK;
        case 7: q->copy = hle_dup_readable_handle(r->ev_r); return q->copy ? RES_OK : RES_FAIL;
        case 8: if (q->nin >= 4) memcpy(&r->limit, q->in, 4); return RES_OK;
        case 9: put32(q, r->limit); return RES_OK;
        default: LOG("[RED] audren: renderer command %u not served", q->cmd); return RES_OK;
    }
}
