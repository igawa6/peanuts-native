// The part of AAudio the host uses (dm/host/hle_audio.c), with the NDK's values; linux/shim/shim_aaudio.c implements it
#pragma once
#include <stdint.h>
typedef int32_t aaudio_result_t, aaudio_stream_state_t, aaudio_direction_t, aaudio_format_t, aaudio_sharing_mode_t, aaudio_performance_mode_t, aaudio_usage_t, aaudio_content_type_t, aaudio_data_callback_result_t;
enum { AAUDIO_OK = 0, AAUDIO_ERROR_BASE = -900, AAUDIO_ERROR_DISCONNECTED = -899, AAUDIO_ERROR_ILLEGAL_ARGUMENT = -898, AAUDIO_ERROR_INTERNAL = -896, AAUDIO_ERROR_INVALID_STATE = -895,
       AAUDIO_ERROR_INVALID_HANDLE = -892, AAUDIO_ERROR_UNIMPLEMENTED = -890, AAUDIO_ERROR_UNAVAILABLE = -889, AAUDIO_ERROR_NO_FREE_HANDLES = -888, AAUDIO_ERROR_NO_MEMORY = -887,
       AAUDIO_ERROR_NULL = -886, AAUDIO_ERROR_TIMEOUT = -885, AAUDIO_ERROR_WOULD_BLOCK = -884, AAUDIO_ERROR_INVALID_FORMAT = -883, AAUDIO_ERROR_OUT_OF_RANGE = -882,
       AAUDIO_ERROR_NO_SERVICE = -881, AAUDIO_ERROR_INVALID_RATE = -880 };
enum { AAUDIO_STREAM_STATE_UNINITIALIZED = 0, AAUDIO_STREAM_STATE_UNKNOWN, AAUDIO_STREAM_STATE_OPEN, AAUDIO_STREAM_STATE_STARTING, AAUDIO_STREAM_STATE_STARTED, AAUDIO_STREAM_STATE_PAUSING,
       AAUDIO_STREAM_STATE_PAUSED, AAUDIO_STREAM_STATE_FLUSHING, AAUDIO_STREAM_STATE_FLUSHED, AAUDIO_STREAM_STATE_STOPPING, AAUDIO_STREAM_STATE_STOPPED, AAUDIO_STREAM_STATE_CLOSING,
       AAUDIO_STREAM_STATE_CLOSED, AAUDIO_STREAM_STATE_DISCONNECTED };
enum { AAUDIO_DIRECTION_OUTPUT = 0, AAUDIO_DIRECTION_INPUT = 1 };
enum { AAUDIO_FORMAT_PCM_I16 = 1, AAUDIO_FORMAT_PCM_FLOAT = 2 };
enum { AAUDIO_SHARING_MODE_EXCLUSIVE = 0, AAUDIO_SHARING_MODE_SHARED = 1 };
enum { AAUDIO_PERFORMANCE_MODE_NONE = 10, AAUDIO_PERFORMANCE_MODE_POWER_SAVING = 11, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 12 };
enum { AAUDIO_USAGE_MEDIA = 1, AAUDIO_USAGE_GAME = 14 };
enum { AAUDIO_CONTENT_TYPE_SPEECH = 1, AAUDIO_CONTENT_TYPE_MUSIC = 2 };
enum { AAUDIO_CALLBACK_RESULT_CONTINUE = 0, AAUDIO_CALLBACK_RESULT_STOP = 1 };
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;
typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(AAudioStream *stream, void *userData, void *audioData, int32_t numFrames);
typedef void (*AAudioStream_errorCallback)(AAudioStream *stream, void *userData, aaudio_result_t error);
const char *AAudio_convertResultToText(aaudio_result_t r);
const char *AAudio_convertStreamStateToText(aaudio_stream_state_t s);
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **builder);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *b, aaudio_direction_t v);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t v);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t v);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, aaudio_format_t v);
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *b, aaudio_sharing_mode_t v);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, aaudio_performance_mode_t v);
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *b, aaudio_usage_t v);
void AAudioStreamBuilder_setContentType(AAudioStreamBuilder *b, aaudio_content_type_t v);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *userData);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *userData);
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **stream);
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b);
aaudio_result_t AAudioStream_close(AAudioStream *s);
aaudio_result_t AAudioStream_requestStart(AAudioStream *s);
aaudio_result_t AAudioStream_requestStop(AAudioStream *s);
aaudio_stream_state_t AAudioStream_getState(AAudioStream *s);
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *s, aaudio_stream_state_t inputState, aaudio_stream_state_t *nextState, int64_t timeoutNanoseconds);
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *s, int32_t numFrames);
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *s);
int32_t AAudioStream_getFramesPerBurst(AAudioStream *s);
int32_t AAudioStream_getSampleRate(AAudioStream *s);
int32_t AAudioStream_getDeviceId(AAudioStream *s);
aaudio_sharing_mode_t AAudioStream_getSharingMode(AAudioStream *s);
aaudio_performance_mode_t AAudioStream_getPerformanceMode(AAudioStream *s);
