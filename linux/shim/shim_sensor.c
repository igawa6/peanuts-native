// ALooper / ASensor: no motion sensors yet on Linux (no default sensor, as on a device without one)
#include <android/sensor.h>
#include <stddef.h>
ALooper *ALooper_prepare(int opts) { (void)opts; return NULL; }
int ALooper_pollOnce(int ms, int *fd, int *ev, void **data) { (void)ms; (void)fd; (void)ev; (void)data; return ALOOPER_POLL_ERROR; }
void ALooper_wake(ALooper *l) { (void)l; }
ASensorManager *ASensorManager_getInstanceForPackage(const char *p) { (void)p; return NULL; }
const ASensor *ASensorManager_getDefaultSensor(ASensorManager *m, int type) { (void)m; (void)type; return NULL; }
ASensorEventQueue *ASensorManager_createEventQueue(ASensorManager *m, ALooper *l, int ident, ALooper_callbackFunc cb, void *data) { (void)m; (void)l; (void)ident; (void)cb; (void)data; return NULL; }
int ASensorManager_destroyEventQueue(ASensorManager *m, ASensorEventQueue *q) { (void)m; (void)q; return 0; }
int ASensorEventQueue_registerSensor(ASensorEventQueue *q, const ASensor *s, int32_t us, int64_t lat) { (void)q; (void)s; (void)us; (void)lat; return -1; }
int ASensorEventQueue_disableSensor(ASensorEventQueue *q, const ASensor *s) { (void)q; (void)s; return 0; }
ssize_t ASensorEventQueue_getEvents(ASensorEventQueue *q, ASensorEvent *e, size_t n) { (void)q; (void)e; (void)n; return 0; }
