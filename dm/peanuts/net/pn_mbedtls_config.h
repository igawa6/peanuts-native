// PEANUTS: additions to Mbed TLS's default configuration (include/mbedtls/mbedtls_config.h) for dm/peanuts/net, read through MBEDTLS_USER_CONFIG_FILE.
// The CMake hook defines MBEDTLS_USER_CONFIG_FILE for the library's sources and for dm/peanuts/net's alike, so both see the same structure layouts.
#pragma once

// Games open connections from several threads at once (and the DRBG and PSA's key store are shared): Mbed TLS's own locks, on pthreads
#define MBEDTLS_THREADING_C
#define MBEDTLS_THREADING_PTHREAD
