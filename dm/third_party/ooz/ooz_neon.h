// PEANUTS: the x86 SSE2 intrinsics ooz's kraken.cpp uses, written with AArch64 NEON, so unoodle builds on arm64 (Apple Silicon Macs, arm64 Linux).
// Included by stdafx.h in place of <x86intrin.h> on __aarch64__ (ooz-arm64.patch, applied by build.sh on arm64 only). Only the intrinsics kraken.cpp calls are
// here, each with SSE2's exact result: the byte, 16-bit and 32-bit lanes are little-endian as on x86, and every load and store is unaligned.
// Licence: GPL-3.0-or-later, as the program it is compiled into (COPYING).
#pragma once
#include <arm_neon.h>
#include <stdint.h>
#include <string.h>

typedef int64x2_t __m128i;
typedef float32x4_t __m128;
typedef int64x1_t __m64;

#define _MM_HINT_T0 3

#define OOZ_U8(a) vreinterpretq_u8_s64(a)
#define OOZ_M(a) vreinterpretq_s64_u8(a)

static inline __m128i _mm_loadu_si128(const __m128i *p) { return OOZ_M(vld1q_u8((const uint8_t *)p)); }
static inline void _mm_storeu_si128(__m128i *p, __m128i a) { vst1q_u8((uint8_t *)p, OOZ_U8(a)); }

// the low 8 bytes; the high 8 are zero
static inline __m128i _mm_loadl_epi64(const __m128i *p) { uint64_t v; memcpy(&v, p, 8); return vreinterpretq_s64_u64(vcombine_u64(vcreate_u64(v), vcreate_u64(0))); }
static inline void _mm_storel_epi64(__m128i *p, __m128i a) { uint64_t v = vgetq_lane_u64(vreinterpretq_u64_s64(a), 0); memcpy(p, &v, 8); }
// the high 8 bytes of a float vector
static inline void _mm_storeh_pi(__m64 *p, __m128 a) { uint64_t v = vgetq_lane_u64(vreinterpretq_u64_f32(a), 1); memcpy(p, &v, 8); }
static inline __m128 _mm_castsi128_ps(__m128i a) { return vreinterpretq_f32_s64(a); }

static inline __m128i _mm_set1_epi8(char c) { return OOZ_M(vdupq_n_u8((uint8_t)c)); }
static inline __m128i _mm_set1_epi32(int i) { return vreinterpretq_s64_s32(vdupq_n_s32(i)); }

static inline __m128i _mm_add_epi8(__m128i a, __m128i b) { return OOZ_M(vaddq_u8(OOZ_U8(a), OOZ_U8(b))); }
static inline __m128i _mm_sub_epi8(__m128i a, __m128i b) { return OOZ_M(vsubq_u8(OOZ_U8(a), OOZ_U8(b))); }
static inline __m128i _mm_and_si128(__m128i a, __m128i b) { return OOZ_M(vandq_u8(OOZ_U8(a), OOZ_U8(b))); }
static inline __m128i _mm_xor_si128(__m128i a, __m128i b) { return OOZ_M(veorq_u8(OOZ_U8(a), OOZ_U8(b))); }
static inline __m128i _mm_max_epu8(__m128i a, __m128i b) { return OOZ_M(vmaxq_u8(OOZ_U8(a), OOZ_U8(b))); }
static inline __m128i _mm_cmpeq_epi8(__m128i a, __m128i b) { return OOZ_M(vceqq_u8(OOZ_U8(a), OOZ_U8(b))); }

// interleave the low (high) 8 bytes of a and b: a0 b0 a1 b1 ...
static inline __m128i _mm_unpacklo_epi8(__m128i a, __m128i b) { return OOZ_M(vzip1q_u8(OOZ_U8(a), OOZ_U8(b))); }
static inline __m128i _mm_unpackhi_epi8(__m128i a, __m128i b) { return OOZ_M(vzip2q_u8(OOZ_U8(a), OOZ_U8(b))); }

// each 16-bit lane shifted right by n (0 when n > 15, as SSE2)
static inline __m128i _mm_srli_epi16(__m128i a, int n) {
  if (n > 15) return vdupq_n_s64(0);
  return vreinterpretq_s64_u16(vshlq_u16(vreinterpretq_u16_s64(a), vdupq_n_s16((int16_t)-n)));
}

// the whole register shifted left by n bytes (towards the higher lanes), zeros in; kraken.cpp passes 1, 2, 4 and 8
static inline __m128i _mm_slli_si128(__m128i a, int n) {
  if (n <= 0) return a;
  if (n > 15) return vdupq_n_s64(0);
  uint8_t in[32] = {0}, out[16];
  vst1q_u8(in + 16, OOZ_U8(a));
  memcpy(out, in + 16 - n, 16);
  return OOZ_M(vld1q_u8(out));
}

// lane i of the result is lane (imm >> 2i) & 3 of a
static inline __m128i _mm_shuffle_epi32(__m128i a, int imm) {
  uint32_t in[4], out[4];
  vst1q_u32(in, vreinterpretq_u32_s64(a));
  for (int i = 0; i < 4; i++) out[i] = in[(imm >> (2 * i)) & 3];
  return vreinterpretq_s64_u32(vld1q_u32(out));
}

// bit i is the top bit of byte i
static inline int _mm_movemask_epi8(__m128i a) {
  uint8_t b[16];
  vst1q_u8(b, OOZ_U8(a));
  int r = 0;
  for (int i = 0; i < 16; i++) r |= (b[i] >> 7) << i;
  return r;
}

static inline void _mm_prefetch(const void *p, int hint) { (void)hint; __builtin_prefetch(p); }

// MSVC's 32-bit rotate (stdafx.h maps it to x86's __rold elsewhere)
static inline unsigned int _rotl(unsigned int x, int n) { n &= 31; return n ? (x << n) | (x >> (32 - n)) : x; }
#define _rotl _rotl

#undef OOZ_U8
#undef OOZ_M
