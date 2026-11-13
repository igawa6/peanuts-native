# Mbed TLS (for dm/peanuts/net)

- Version: 3.6.7 (LTS), the release archive `mbedtls-3.6.7.tar.bz2` from
  <https://github.com/Mbed-TLS/mbedtls/releases/tag/mbedtls-3.6.7>, SHA-256
  `a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6` (checked against the release's own sum file).
- Kept: `include/` and `library/*.c`, `library/*.h`, `LICENSE`, unmodified. Left out: programs, tests, docs, scripts,
  `3rdparty/` (the default configuration builds none of it), and `include/CMakeLists.txt`.
- License: Apache-2.0 OR GPL-2.0-or-later (`LICENSE`), compatible with this project's GPL-3.0-or-later.
- Configuration: the default `include/mbedtls/mbedtls_config.h`, plus `dm/peanuts/net/pn_mbedtls_config.h` through
  `MBEDTLS_USER_CONFIG_FILE` (threading on pthreads). Built by `android/host/CMakeLists.txt` (`-O2 -w`).
- Used by: `dm/peanuts/net/pn_ssl.c` (TLS), `pn_account.c` (the id_token's RSA key and signature).
