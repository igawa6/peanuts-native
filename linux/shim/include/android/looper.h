#pragma once
typedef struct ALooper ALooper;
typedef int (*ALooper_callbackFunc)(int fd, int events, void *data);
enum { ALOOPER_POLL_WAKE = -1, ALOOPER_POLL_CALLBACK = -2, ALOOPER_POLL_TIMEOUT = -3, ALOOPER_POLL_ERROR = -4 };
enum { ALOOPER_PREPARE_ALLOW_NON_CALLBACKS = 1 };
ALooper *ALooper_prepare(int opts);
int ALooper_pollOnce(int timeoutMillis, int *outFd, int *outEvents, void **outData);
void ALooper_wake(ALooper *looper);
