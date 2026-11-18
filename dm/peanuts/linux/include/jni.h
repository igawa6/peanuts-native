// Peanuts Native on aarch64 Linux (linux/host/CMakeLists.txt): the JNI names dm/peanuts/peanuts_services.c declares its one Java entry point with
// (Java_dev_peanuts_Bridge_battery). There is no Java side on Linux: the entry point is compiled and never called, the battery is read from the kernel's files.
#pragma once
#include <stdint.h>
typedef int32_t jint; typedef uint8_t jboolean; typedef void *jclass; typedef void *jobject; typedef struct JNINativeInterface_ *JNIEnv;
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
