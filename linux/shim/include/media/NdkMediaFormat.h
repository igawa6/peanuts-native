// The part of AMediaFormat the host uses; linux/shim/shim_media.c implements it
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
typedef struct AMediaFormat AMediaFormat;
typedef enum { AMEDIA_OK = 0, AMEDIA_ERROR_BASE = -10000, AMEDIA_ERROR_UNKNOWN = -10000, AMEDIA_ERROR_MALFORMED = -10001, AMEDIA_ERROR_UNSUPPORTED = -10002, AMEDIA_ERROR_INVALID_OBJECT = -10003,
               AMEDIA_ERROR_INVALID_PARAMETER = -10004, AMEDIA_ERROR_INVALID_OPERATION = -10005 } media_status_t;
AMediaFormat *AMediaFormat_new(void);
media_status_t AMediaFormat_delete(AMediaFormat *f);
const char *AMediaFormat_toString(AMediaFormat *f);
bool AMediaFormat_getInt32(AMediaFormat *f, const char *name, int32_t *out);
bool AMediaFormat_getRect(AMediaFormat *f, const char *name, int32_t *left, int32_t *top, int32_t *right, int32_t *bottom);
void AMediaFormat_setInt32(AMediaFormat *f, const char *name, int32_t value);
void AMediaFormat_setString(AMediaFormat *f, const char *name, const char *value);
void AMediaFormat_setBuffer(AMediaFormat *f, const char *name, const void *data, size_t size);
extern const char *AMEDIAFORMAT_KEY_CHANNEL_COUNT, *AMEDIAFORMAT_KEY_COLOR_FORMAT, *AMEDIAFORMAT_KEY_COLOR_RANGE, *AMEDIAFORMAT_KEY_COLOR_STANDARD, *AMEDIAFORMAT_KEY_DISPLAY_CROP,
    *AMEDIAFORMAT_KEY_HEIGHT, *AMEDIAFORMAT_KEY_IS_ADTS, *AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, *AMEDIAFORMAT_KEY_MIME, *AMEDIAFORMAT_KEY_PCM_ENCODING, *AMEDIAFORMAT_KEY_SAMPLE_RATE,
    *AMEDIAFORMAT_KEY_SLICE_HEIGHT, *AMEDIAFORMAT_KEY_STRIDE, *AMEDIAFORMAT_KEY_WIDTH;
