// The part of AMediaCodec the host uses; linux/shim/shim_media.c implements it (no decoder yet: AMediaCodec_createDecoderByType gives NULL, as for a type the device lacks)
#pragma once
#include <sys/types.h>
#include <android/native_window.h>
#include <media/NdkMediaFormat.h>
typedef struct AMediaCodec AMediaCodec;
typedef struct AMediaCrypto AMediaCrypto;
typedef struct AMediaCodecBufferInfo { int32_t offset, size; int64_t presentationTimeUs; uint32_t flags; } AMediaCodecBufferInfo;
enum { AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG = 2, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM = 4, AMEDIACODEC_BUFFER_FLAG_PARTIAL_FRAME = 8 };
enum { AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED = -3, AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED = -2, AMEDIACODEC_INFO_TRY_AGAIN_LATER = -1 };
AMediaCodec *AMediaCodec_createDecoderByType(const char *mime);
media_status_t AMediaCodec_delete(AMediaCodec *c);
media_status_t AMediaCodec_configure(AMediaCodec *c, const AMediaFormat *format, ANativeWindow *surface, AMediaCrypto *crypto, uint32_t flags);
media_status_t AMediaCodec_start(AMediaCodec *c);
media_status_t AMediaCodec_stop(AMediaCodec *c);
media_status_t AMediaCodec_flush(AMediaCodec *c);
uint8_t *AMediaCodec_getInputBuffer(AMediaCodec *c, size_t idx, size_t *out_size);
uint8_t *AMediaCodec_getOutputBuffer(AMediaCodec *c, size_t idx, size_t *out_size);
ssize_t AMediaCodec_dequeueInputBuffer(AMediaCodec *c, int64_t timeoutUs);
media_status_t AMediaCodec_queueInputBuffer(AMediaCodec *c, size_t idx, off_t offset, size_t size, uint64_t time, uint32_t flags);
ssize_t AMediaCodec_dequeueOutputBuffer(AMediaCodec *c, AMediaCodecBufferInfo *info, int64_t timeoutUs);
AMediaFormat *AMediaCodec_getOutputFormat(AMediaCodec *c);
media_status_t AMediaCodec_releaseOutputBuffer(AMediaCodec *c, size_t idx, bool render);
media_status_t AMediaCodec_getName(AMediaCodec *c, char **out_name);
void AMediaCodec_releaseName(AMediaCodec *c, char *name);
