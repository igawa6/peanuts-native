// Motion sensors: none yet on Linux (ASensorManager_getDefaultSensor gives NULL, as on a device without the sensor)
#pragma once
#include <stdint.h>
#include <sys/types.h>
#include <android/looper.h>
typedef struct ASensorManager ASensorManager;
typedef struct ASensorEventQueue ASensorEventQueue;
typedef struct ASensor ASensor;
enum { ASENSOR_TYPE_ACCELEROMETER = 1, ASENSOR_TYPE_MAGNETIC_FIELD = 2, ASENSOR_TYPE_GYROSCOPE = 4 };
typedef struct ASensorVector { union { float v[3]; struct { float x, y, z; }; struct { float azimuth, pitch, roll; }; }; int8_t status; uint8_t reserved[3]; } ASensorVector;
typedef struct ASensorEvent {
    int32_t version, sensor, type, reserved0; int64_t timestamp;
    union { union { float data[16]; ASensorVector vector, acceleration, gyro, magnetic; float temperature, distance, light, pressure; }; union { uint64_t data[8]; uint64_t step_counter; } u64; };
    uint32_t flags; int32_t reserved1[3];
} ASensorEvent;
ASensorManager *ASensorManager_getInstanceForPackage(const char *packageName);
const ASensor *ASensorManager_getDefaultSensor(ASensorManager *m, int type);
ASensorEventQueue *ASensorManager_createEventQueue(ASensorManager *m, ALooper *looper, int ident, ALooper_callbackFunc cb, void *data);
int ASensorManager_destroyEventQueue(ASensorManager *m, ASensorEventQueue *q);
int ASensorEventQueue_registerSensor(ASensorEventQueue *q, const ASensor *s, int32_t samplingPeriodUs, int64_t maxBatchReportLatencyUs);
int ASensorEventQueue_disableSensor(ASensorEventQueue *q, const ASensor *s);
ssize_t ASensorEventQueue_getEvents(ASensorEventQueue *q, ASensorEvent *events, size_t count);
