// AMediaCodec / AMediaFormat: no decoder yet on Linux. AMediaCodec_createDecoderByType gives NULL, as for a type the device has no decoder for, so the host's
// own paths run (dav1d and libvpx for AV1 / VP9; H.264 and AAC movies and sounds stay off until this file decodes them)
#include <media/NdkMediaCodec.h>
#include <stdlib.h>
#include <string.h>
struct AMediaFormat { struct { char name[40]; int32_t v; } i[32]; int n; };
#define K(n, s) const char *AMEDIAFORMAT_KEY_##n = s
K(CHANNEL_COUNT, "channel-count"); K(COLOR_FORMAT, "color-format"); K(COLOR_RANGE, "color-range"); K(COLOR_STANDARD, "color-standard"); K(DISPLAY_CROP, "crop"); K(HEIGHT, "height");
K(IS_ADTS, "is-adts"); K(MAX_INPUT_SIZE, "max-input-size"); K(MIME, "mime"); K(PCM_ENCODING, "pcm-encoding"); K(SAMPLE_RATE, "sample-rate"); K(SLICE_HEIGHT, "slice-height");
K(STRIDE, "stride"); K(WIDTH, "width");
AMediaFormat *AMediaFormat_new(void) { return calloc(1, sizeof(AMediaFormat)); }
media_status_t AMediaFormat_delete(AMediaFormat *f) { free(f); return AMEDIA_OK; }
const char *AMediaFormat_toString(AMediaFormat *f) { (void)f; return "{}"; }
bool AMediaFormat_getInt32(AMediaFormat *f, const char *name, int32_t *out) { for (int k = 0; f && k < f->n; k++) if (!strcmp(f->i[k].name, name)) { *out = f->i[k].v; return true; } return false; }
bool AMediaFormat_getRect(AMediaFormat *f, const char *name, int32_t *l, int32_t *t, int32_t *r, int32_t *b) { (void)f; (void)name; (void)l; (void)t; (void)r; (void)b; return false; }
void AMediaFormat_setInt32(AMediaFormat *f, const char *name, int32_t v) {
    if (!f) return;
    for (int k = 0; k < f->n; k++) if (!strcmp(f->i[k].name, name)) { f->i[k].v = v; return; }
    if (f->n < 32) { strncpy(f->i[f->n].name, name, sizeof f->i[0].name - 1); f->i[f->n++].v = v; }
}
void AMediaFormat_setString(AMediaFormat *f, const char *name, const char *value) { (void)f; (void)name; (void)value; }
void AMediaFormat_setBuffer(AMediaFormat *f, const char *name, const void *data, size_t size) { (void)f; (void)name; (void)data; (void)size; }
AMediaCodec *AMediaCodec_createDecoderByType(const char *mime) { (void)mime; return NULL; }
media_status_t AMediaCodec_delete(AMediaCodec *c) { (void)c; return AMEDIA_OK; }
media_status_t AMediaCodec_configure(AMediaCodec *c, const AMediaFormat *f, ANativeWindow *s, AMediaCrypto *y, uint32_t flags) { (void)c; (void)f; (void)s; (void)y; (void)flags; return AMEDIA_ERROR_INVALID_OBJECT; }
media_status_t AMediaCodec_start(AMediaCodec *c) { (void)c; return AMEDIA_ERROR_INVALID_OBJECT; }
media_status_t AMediaCodec_stop(AMediaCodec *c) { (void)c; return AMEDIA_OK; }
media_status_t AMediaCodec_flush(AMediaCodec *c) { (void)c; return AMEDIA_OK; }
uint8_t *AMediaCodec_getInputBuffer(AMediaCodec *c, size_t idx, size_t *n) { (void)c; (void)idx; if (n) *n = 0; return NULL; }
uint8_t *AMediaCodec_getOutputBuffer(AMediaCodec *c, size_t idx, size_t *n) { (void)c; (void)idx; if (n) *n = 0; return NULL; }
ssize_t AMediaCodec_dequeueInputBuffer(AMediaCodec *c, int64_t us) { (void)c; (void)us; return AMEDIACODEC_INFO_TRY_AGAIN_LATER; }
media_status_t AMediaCodec_queueInputBuffer(AMediaCodec *c, size_t idx, off_t off, size_t size, uint64_t t, uint32_t flags) { (void)c; (void)idx; (void)off; (void)size; (void)t; (void)flags; return AMEDIA_ERROR_INVALID_OBJECT; }
ssize_t AMediaCodec_dequeueOutputBuffer(AMediaCodec *c, AMediaCodecBufferInfo *info, int64_t us) { (void)c; (void)info; (void)us; return AMEDIACODEC_INFO_TRY_AGAIN_LATER; }
AMediaFormat *AMediaCodec_getOutputFormat(AMediaCodec *c) { (void)c; return NULL; }
media_status_t AMediaCodec_releaseOutputBuffer(AMediaCodec *c, size_t idx, bool render) { (void)c; (void)idx; (void)render; return AMEDIA_ERROR_INVALID_OBJECT; }
media_status_t AMediaCodec_getName(AMediaCodec *c, char **name) { (void)c; if (name) *name = NULL; return AMEDIA_ERROR_INVALID_OBJECT; }
void AMediaCodec_releaseName(AMediaCodec *c, char *name) { (void)c; (void)name; }
