// AAudio on SDL3 (PipeWire underneath): one SDL audio stream per AAudio stream, whose callback asks the host's data callback for the frames the device wants
#include <aaudio/AAudio.h>
#include <SDL3/SDL.h>
#include <stdatomic.h>
#include <stdlib.h>
enum { BURST = 256, CAPACITY = 4096 };
struct AAudioStreamBuilderStruct { int rate, ch; AAudioStream_dataCallback data; AAudioStream_errorCallback err; void *data_ctx, *err_ctx; };
struct AAudioStreamStruct { SDL_AudioStream *s; int rate, ch, buffer; _Atomic int state; AAudioStream_dataCallback data; void *data_ctx; int16_t *buf; };
const char *AAudio_convertResultToText(aaudio_result_t r) {
    switch (r) { case AAUDIO_OK: return "AAUDIO_OK"; case AAUDIO_ERROR_DISCONNECTED: return "AAUDIO_ERROR_DISCONNECTED"; case AAUDIO_ERROR_NO_SERVICE: return "AAUDIO_ERROR_NO_SERVICE (SDL has no audio device)";
    case AAUDIO_ERROR_NO_MEMORY: return "AAUDIO_ERROR_NO_MEMORY"; case AAUDIO_ERROR_INVALID_STATE: return "AAUDIO_ERROR_INVALID_STATE"; case AAUDIO_ERROR_TIMEOUT: return "AAUDIO_ERROR_TIMEOUT"; }
    return "AAUDIO_ERROR";
}
const char *AAudio_convertStreamStateToText(aaudio_stream_state_t s) {
    switch (s) { case AAUDIO_STREAM_STATE_OPEN: return "OPEN"; case AAUDIO_STREAM_STATE_STARTED: return "STARTED"; case AAUDIO_STREAM_STATE_STOPPED: return "STOPPED"; case AAUDIO_STREAM_STATE_CLOSED: return "CLOSED"; }
    return "?";
}
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { *b = calloc(1, sizeof **b); if (!*b) return AAUDIO_ERROR_NO_MEMORY; (*b)->ch = 2; return AAUDIO_OK; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *b, aaudio_direction_t v) { (void)b; (void)v; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t v) { b->rate = v; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t v) { b->ch = v; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, aaudio_format_t v) { (void)b; (void)v; }   // PCM16 is the only one asked for
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *b, aaudio_sharing_mode_t v) { (void)b; (void)v; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, aaudio_performance_mode_t v) { (void)b; (void)v; }
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *b, aaudio_usage_t v) { (void)b; (void)v; }
void AAudioStreamBuilder_setContentType(AAudioStreamBuilder *b, aaudio_content_type_t v) { (void)b; (void)v; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *u) { b->data = cb; b->data_ctx = u; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *u) { b->err = cb; b->err_ctx = u; }
static void SDLCALL feed(void *ctx, SDL_AudioStream *as, int additional, int total) {
    (void)total; AAudioStream *s = ctx; int frame = 2 * s->ch, frames = additional / frame;
    while (frames > 0) {
        int n = frames < CAPACITY ? frames : CAPACITY;
        SDL_memset(s->buf, 0, (size_t)n * frame);
        if (s->data) s->data(s, s->data_ctx, s->buf, n);
        SDL_PutAudioStreamData(as, s->buf, n * frame);
        frames -= n;
    }
}
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **out) {
    *out = NULL;
    static _Atomic int inited;
    if (!atomic_exchange(&inited, 1)) { SDL_SetHint(SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES, "512"); SDL_SetHint(SDL_HINT_AUDIO_DEVICE_STREAM_NAME, "Banana Native"); }
    if (!SDL_WasInit(SDL_INIT_AUDIO) && !SDL_InitSubSystem(SDL_INIT_AUDIO)) return AAUDIO_ERROR_NO_SERVICE;
    AAudioStream *s = calloc(1, sizeof *s); if (!s) return AAUDIO_ERROR_NO_MEMORY;
    s->rate = b->rate ? b->rate : 48000; s->ch = b->ch; s->buffer = 4 * BURST; s->data = b->data; s->data_ctx = b->data_ctx;
    s->buf = malloc((size_t)CAPACITY * 2 * s->ch);
    SDL_AudioSpec spec = {SDL_AUDIO_S16, s->ch, s->rate};
    s->s = s->buf ? SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, feed, s) : NULL;   // the device starts paused
    if (!s->s) { free(s->buf); free(s); return AAUDIO_ERROR_NO_SERVICE; }
    atomic_store(&s->state, AAUDIO_STREAM_STATE_OPEN);
    *out = s; return AAUDIO_OK;
}
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { free(b); return AAUDIO_OK; }
aaudio_result_t AAudioStream_close(AAudioStream *s) { if (!s) return AAUDIO_OK; SDL_DestroyAudioStream(s->s); free(s->buf); free(s); return AAUDIO_OK; }
aaudio_result_t AAudioStream_requestStart(AAudioStream *s) { if (!SDL_ResumeAudioStreamDevice(s->s)) return AAUDIO_ERROR_DISCONNECTED; atomic_store(&s->state, AAUDIO_STREAM_STATE_STARTED); return AAUDIO_OK; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *s) { if (!SDL_PauseAudioStreamDevice(s->s)) return AAUDIO_ERROR_DISCONNECTED; SDL_ClearAudioStream(s->s); atomic_store(&s->state, AAUDIO_STREAM_STATE_STOPPED); return AAUDIO_OK; }
aaudio_stream_state_t AAudioStream_getState(AAudioStream *s) { return atomic_load(&s->state); }
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *s, aaudio_stream_state_t in, aaudio_stream_state_t *next, int64_t ns) { (void)in; (void)ns; if (next) *next = atomic_load(&s->state); return AAUDIO_OK; }
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *s, int32_t n) { s->buffer = n < BURST ? BURST : n > CAPACITY ? CAPACITY : n; return s->buffer; }
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *s) { (void)s; return CAPACITY; }
int32_t AAudioStream_getFramesPerBurst(AAudioStream *s) { (void)s; return BURST; }
int32_t AAudioStream_getSampleRate(AAudioStream *s) { return s->rate; }
int32_t AAudioStream_getDeviceId(AAudioStream *s) { (void)s; return 0; }
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *s) { (void)s; return AAUDIO_SHARING_MODE_SHARED; }
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *s) { (void)s; return AAUDIO_PERFORMANCE_MODE_LOW_LATENCY; }
