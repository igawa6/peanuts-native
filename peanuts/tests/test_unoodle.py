#!/usr/bin/env python3
"""dm/third_party/ooz's unoodle, the PC builder's Oodle decoding worker: it builds from the pinned files, speaks the DM_OODLE_SERVE pipe protocol, and fails closed.
No game data: ooz has no compressor, so the valid streams here are the three forms that can be written by hand from the open decoder's reading of the format
(a block stored whole, a quantum stored whole, a quantum of one repeated byte). Entropy-coded Kraken and Mermaid streams are checked per game instead, by the
shader keys of the profile (dm/third_party/ooz/README.md "Checked"). Skipped where the worker cannot be built (Windows, neither x86-64 nor arm64, no C++ compiler or patch)."""
import os, platform, shutil, struct, subprocess, sys, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OOZ = REPO / 'dm' / 'third_party' / 'ooz'
BLOCK = 0x40000   # Oodle's block size: each block of a stream has its own 2-byte header


def cxx():
    return os.environ.get('CXX') or next((c for c in ('c++', 'g++', 'clang++') if shutil.which(c)), None)


def stored(body, kind=6):
    """A stream of blocks stored whole: 0xCC, the decoder type, the bytes"""
    return b''.join(bytes([0xCC, kind]) + body[i:i + BLOCK] for i in range(0, len(body), BLOCK))


def quantum(size_field, rest, kind=6):
    """One block of one quantum: 0x8C, the decoder type, the 3-byte quantum header (big endian), the rest"""
    return bytes([0x8C, kind]) + size_field.to_bytes(3, 'big') + rest


class Unoodle(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if sys.platform == 'win32' or platform.machine().lower() not in ('x86_64', 'amd64', 'arm64', 'aarch64'): raise unittest.SkipTest('unoodle is built on x86-64 and arm64 Linux and macOS only')
        if not cxx() or not shutil.which('patch') or not shutil.which('sh'): raise unittest.SkipTest('no C++ compiler or no patch program')
        cls.dir = tempfile.TemporaryDirectory()
        cls.exe = os.path.join(cls.dir.name, 'unoodle')
        r = subprocess.run(['sh', str(OOZ / 'build.sh'), cls.exe, cxx()], capture_output=True, text=True)
        if r.returncode: cls.dir.cleanup(); raise AssertionError(f'build.sh failed ({r.returncode}): {r.stdout}{r.stderr}')

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def send(self, data):
        """The worker with data on its input, started as uepak.OodleHost starts it (two arguments, which it ignores) → (exit code, output, messages)"""
        p = subprocess.run([self.exe, 'host', '-'], input=data, capture_output=True, env=dict(os.environ, DM_OODLE_SERVE='1'))
        return p.returncode, p.stdout, p.stderr.decode(errors='replace')

    def ask(self, *reqs):
        """Each (compressed, raw length) in one run → the answers (bytes, or None for 'failed'); the worker must end with exit code 0 and nothing left over"""
        rc, out, err = self.send(b''.join(struct.pack('<QQ', len(c), n) + c for c, n in reqs))
        self.assertEqual(rc, 0, err)
        got, o = [], 0
        for _ in reqs:
            n = struct.unpack_from('<Q', out, o)[0]; o += 8
            got.append(out[o:o + n] if n else None); o += n
        self.assertEqual(o, len(out))
        return got

    def test_pinned_files(self):
        """The two upstream files are the pinned ones, byte for byte (build.sh checks the same hashes before it compiles)"""
        import hashlib
        text = (OOZ / 'build.sh').read_text()
        for f in ('kraken.cpp', 'stdafx.h'):
            self.assertIn(f'check {f} {hashlib.sha256((OOZ / f).read_bytes()).hexdigest()}', text)

    def test_known_answers(self):
        body = bytes(range(256)) * 5
        big = bytes((i * 7 + (i >> 9)) & 0xFF for i in range(BLOCK + 1000))
        reqs = [(stored(body, 6), len(body)), (stored(body, 10), len(body)), (stored(body, 12), len(body)),   # Kraken, Mermaid / Selkie, Leviathan block headers
                (stored(big), len(big)),                                                                    # two blocks
                (quantum(0x07FFFF, b'A'), 5000), (quantum(0x07FFFF, b'\0', 10), BLOCK),                     # a quantum of one repeated byte
                (quantum(len(body) - 1, body), len(body))]                                                  # a quantum stored whole
        want = [body, body, body, big, b'A' * 5000, b'\0' * BLOCK, body]
        self.assertEqual(self.ask(*reqs), want)

    def test_fails_closed(self):
        """A wrong raw length, bytes left over, a cut stream, garbage, an unknown decoder type and an empty request all answer 'failed'; the next stream still decodes"""
        body = bytes(range(256)) * 5
        good = (stored(body), len(body))
        bad = [(stored(body), len(body) + 1), (stored(body), len(body) - 1), (stored(body) + b'\0', len(body)), (stored(body)[:-1], len(body)),
               (quantum(0x07FFFF, b'A') + b'\0', 5000), (quantum(0x07FFFF, b''), 5000), (quantum(len(body) - 1, body[:-7]), len(body)),
               (bytes([0xCC, 7]) + body, len(body)), (bytes([0x9C, 6]) + body, len(body)), (bytes(range(1, 255)) * 9, 6000), (b'', 100), (b'x', 0), (b'', 0)]
        for k, req in enumerate(bad):
            with self.subTest(case=k): self.assertEqual(self.ask(req, good), [None, body])

    def test_other_decoders_refused(self):
        """LZNA (5) and Bitknit (11) are not built in: their quanta fail"""
        for kind in (5, 11):
            self.assertEqual(self.ask((bytes([0x8C, kind]) + bytes(40), 4096)), [None])

    def test_input_ends(self):
        """Input that ends inside a request, or a length over the limit, ends the worker with an error and no answer"""
        for data in (struct.pack('<QQ', 100, 100) + b'abc', struct.pack('<QQ', 5, (1 << 30) + 1) + b'abcde', struct.pack('<QQ', (1 << 30) + 1, 5)):
            rc, out, err = self.send(data)
            self.assertEqual((rc, out), (2, b'')); self.assertIn('unoodle: stream 1', err)
        self.assertEqual(self.send(b''), (0, b'', ''))

    def test_stats(self):
        with tempfile.TemporaryDirectory() as d:
            f = os.path.join(d, 'stats.txt')
            p = subprocess.run([self.exe], input=struct.pack('<QQ', 12, 10) + stored(b'0123456789'), capture_output=True, env=dict(os.environ, UNOODLE_STATS=f))
            self.assertEqual(p.returncode, 0)
            self.assertIn('streams=1 | type=6(kraken) ok=1 failed=0 in=12 out=10', Path(f).read_text())


# Each intrinsic of ooz_neon.h against SSE2's definition written out byte by byte, on random registers (the arm64 build's decoder runs on them; the stream tests
# above reach none of them, as their streams are not entropy-coded)
NEON_CHECK = r'''
#include "ooz_neon.h"
#include <stdio.h>
#include <stdlib.h>
typedef unsigned char u8;
static u8 *B(__m128i *a) { return (u8 *)a; }
static int fails = 0;
static void same(const char *what, const void *got, const void *want, int n) { if (memcmp(got, want, n)) { if (fails++ < 10) printf("differs: %s\n", what); } }
static __m128i rnd(void) { u8 b[16]; for (int i = 0; i < 16; i++) b[i] = (u8)(rand() >> 7); __m128i r; memcpy(&r, b, 16); return r; }
int main(void) {
  srand(1);
  for (int it = 0; it < 20000; it++) {
    __m128i a = rnd(), b = rnd(), r; u8 w[16], *x = B(&a), *y = B(&b); int imm = rand() & 255, n = rand() % 18;
    u8 buf[48]; for (int i = 0; i < 48; i++) buf[i] = (u8)rand();
    r = _mm_loadu_si128((__m128i *)(buf + 3)); same("loadu", &r, buf + 3, 16);
    u8 st[20] = {0}; _mm_storeu_si128((__m128i *)(st + 1), a); same("storeu", st + 1, x, 16);
    r = _mm_loadl_epi64((__m128i *)(buf + 5)); memcpy(w, buf + 5, 8); memset(w + 8, 0, 8); same("loadl_epi64", &r, w, 16);
    memset(st, 0, 20); _mm_storel_epi64((__m128i *)(st + 2), a); same("storel_epi64", st + 2, x, 8); same("storel_epi64 bounds", st + 10, (u8[8]){0}, 8);
    memset(st, 0, 20); _mm_storeh_pi((__m64 *)(st + 1), _mm_castsi128_ps(a)); same("storeh_pi", st + 1, x + 8, 8);
    r = _mm_set1_epi8((char)imm); memset(w, imm, 16); same("set1_epi8", &r, w, 16);
    int v32 = rand() * 7919; r = _mm_set1_epi32(v32); for (int i = 0; i < 4; i++) memcpy(w + 4 * i, &v32, 4); same("set1_epi32", &r, w, 16);
    r = _mm_add_epi8(a, b); for (int i = 0; i < 16; i++) w[i] = (u8)(x[i] + y[i]); same("add_epi8", &r, w, 16);
    r = _mm_sub_epi8(a, b); for (int i = 0; i < 16; i++) w[i] = (u8)(x[i] - y[i]); same("sub_epi8", &r, w, 16);
    r = _mm_and_si128(a, b); for (int i = 0; i < 16; i++) w[i] = x[i] & y[i]; same("and_si128", &r, w, 16);
    r = _mm_xor_si128(a, b); for (int i = 0; i < 16; i++) w[i] = x[i] ^ y[i]; same("xor_si128", &r, w, 16);
    r = _mm_max_epu8(a, b); for (int i = 0; i < 16; i++) w[i] = x[i] > y[i] ? x[i] : y[i]; same("max_epu8", &r, w, 16);
    if (it & 1) b = a;
    r = _mm_cmpeq_epi8(a, b); for (int i = 0; i < 16; i++) w[i] = x[i] == y[i] ? 0xff : 0; same("cmpeq_epi8", &r, w, 16);
    r = _mm_unpacklo_epi8(a, b); for (int i = 0; i < 8; i++) { w[2 * i] = x[i]; w[2 * i + 1] = y[i]; } same("unpacklo_epi8", &r, w, 16);
    r = _mm_unpackhi_epi8(a, b); for (int i = 0; i < 8; i++) { w[2 * i] = x[8 + i]; w[2 * i + 1] = y[8 + i]; } same("unpackhi_epi8", &r, w, 16);
    r = _mm_srli_epi16(a, n); for (int i = 0; i < 8; i++) { unsigned v = x[2 * i] | x[2 * i + 1] << 8; v = n > 15 ? 0 : v >> n; w[2 * i] = (u8)v; w[2 * i + 1] = (u8)(v >> 8); } same("srli_epi16", &r, w, 16);
    r = _mm_slli_si128(a, n); for (int i = 0; i < 16; i++) w[i] = i - n >= 0 && n < 16 ? x[i - n] : 0; same("slli_si128", &r, w, 16);
    r = _mm_shuffle_epi32(a, imm); for (int i = 0; i < 4; i++) memcpy(w + 4 * i, x + 4 * ((imm >> (2 * i)) & 3), 4); same("shuffle_epi32", &r, w, 16);
    int m = 0; for (int i = 0; i < 16; i++) m |= (x[i] >> 7) << i; int got = _mm_movemask_epi8(a); same("movemask_epi8", &got, &m, sizeof m);
    unsigned rx = (unsigned)rand() * 2654435761u, rw = n ? (rx << (n & 31)) | (rx >> ((32 - n) & 31)) : rx, rg = _rotl(rx, n);
    if ((n & 31) == 0) rw = rx; same("_rotl", &rg, &rw, 4);
    _mm_prefetch(buf, _MM_HINT_T0);
  }
  printf("checked, %d differ\n", fails);
  return fails != 0;
}
'''


class NeonIntrinsics(unittest.TestCase):
    def test_each_matches_sse2(self):
        if platform.machine().lower() not in ('arm64', 'aarch64'): self.skipTest('ooz_neon.h is the arm64 build\'s')
        if not cxx(): self.skipTest('no C++ compiler')
        with tempfile.TemporaryDirectory() as d:
            src, exe = os.path.join(d, 'neon_check.cpp'), os.path.join(d, 'neon_check')
            Path(src).write_text(NEON_CHECK)
            r = subprocess.run([cxx(), '-O2', '-std=c++17', '-I', str(OOZ), '-o', exe, src], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            r = subprocess.run([exe], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('checked, 0 differ', r.stdout)


if __name__ == '__main__':
    unittest.main()
