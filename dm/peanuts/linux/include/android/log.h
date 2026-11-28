// Peanuts Native on aarch64 Linux (linux/host/CMakeLists.txt): the part of Android's <android/log.h> that dm/peanuts uses, written to stderr
// (the Linux host's log, linux/README.md). Only the Peanuts sources see this directory; the Banana Native host does not log through it.
#pragma once
#include <stdarg.h>
#include <stdio.h>
enum { ANDROID_LOG_UNKNOWN, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT };
__attribute__((format(printf, 3, 4))) static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    static const char lv[] = "??VDIWEFS";
    va_list ap; va_start(ap, fmt);
    flockfile(stderr);
    fprintf(stderr, "%c/%s: ", prio >= 0 && prio <= 8 ? lv[prio] : '?', tag ? tag : "");
    int n = vfprintf(stderr, fmt, ap); fputc('\n', stderr);
    funlockfile(stderr);
    va_end(ap); return n;
}
