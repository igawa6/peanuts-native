// Included before every host source on Linux (linux/host/CMakeLists.txt, -include): what Bionic's headers give that glibc's do not
#pragma once
#ifndef __ASSEMBLER__
#include <stdbool.h>
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#define M_HEAP_TAGGING_LEVEL_NONE 0
#include <pthread.h>
#include <sys/types.h>
pid_t nx_pthread_gettid(pthread_t t);   // Bionic's pthread_gettid_np (glibc has it from 2.42): linux/shim/shim_sys.c
#define pthread_gettid_np nx_pthread_gettid
#endif
