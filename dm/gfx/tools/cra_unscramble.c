/* Banana Native: libcra, the unscramble of Camelot's archive (Mario Tennis Fever's rom:/42) in C. dm/gfx/tools/cra.py loads it through ctypes (DM_CRA_LIB, or
 * libcra.so on Android, where the APK's lib/ has it) and keeps the Python version for machines without it. The phone's shader split runs in the app's
 * process, one job after another, and the Python loop over 16-byte blocks was 77 % of its time.
 *
 * cra.py chooses the variant (the multiplier, which word is the source, which word comes first) and passes the CRC table; this is only the loop of
 * cra.unscramble, block for block: s = the source word, m = crc64(s ^ w) * mul, e = the other word ^ (m >> 3); out = ~(m ^ s << 32) and e, in the variant's
 * order; the sum of every e is returned (modulo 2^64). n whole blocks are read from raw and written to out; a shorter tail is not touched. */
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#if !defined(__BYTE_ORDER__) || __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "libcra reads the archive's words in the host's byte order: little-endian only (arm64, x86-64)"
#endif

__attribute__((visibility("default")))
uint64_t cra_unscramble(const uint8_t *raw, size_t n, uint8_t *out, uint32_t w, const uint32_t *tab, uint64_t mul, int src, int first)
{
    const uint32_t *t0 = tab, *t1 = tab + 256, *t2 = tab + 512, *t3 = tab + 768, *t4 = tab + 1024, *t5 = tab + 1280, *t6 = tab + 1536, *t7 = tab + 1792;
    uint64_t total = 0;
    src &= 1; first &= 1;
    for (size_t i = 0; i < n; i++) {
        uint64_t q[2], o[2];
        memcpy(q, raw + 16 * i, 16);
        uint64_t s = q[src], x = s ^ w;
        uint64_t c = t7[x & 0xff] ^ t6[x >> 8 & 0xff] ^ t5[x >> 16 & 0xff] ^ t4[x >> 24 & 0xff] ^ t3[x >> 32 & 0xff] ^ t2[x >> 40 & 0xff] ^ t1[x >> 48 & 0xff] ^ t0[x >> 56];
        uint64_t m = c * mul;
        uint64_t e = q[src ^ 1] ^ (m >> 3);
        o[first] = ~(m ^ (s << 32));
        o[first ^ 1] = e;
        memcpy(out + 16 * i, o, 16);
        total += e;
    }
    return total;
}
