#!/usr/bin/env python3
"""PEANUTS: _nxyaz0 (dm/peanuts/pack_worker/nxyaz0.c), shader_split.yaz0's decoder in C, against yaz0's own Python loop (peanuts/UPSTREAM-EDITS.md Round 31).
The module must give the loop's bytes for every stream the loop decodes, and None for every stream the loop stops on, which yaz0 then hands to the loop, so a
damaged file names the same error with and without it. Built here with this machine's C compiler against this Python's headers, as peanuts_build.py builds
it for the PC builder; skipped without either. No game files needed: synthetic streams from a small compressor below."""
import atexit
import importlib.util
import os
import random
import shutil
import subprocess
import sys
import sysconfig
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools/fuzz'))
import fuzzlib   # imports shader_split with a profile (any profile: yaz0 does not depend on it)

ss = fuzzlib.import_shader_split()
SRC = REPO / 'dm/peanuts/pack_worker/nxyaz0.c'
_built = []


def nxyaz0():
    """_nxyaz0 built from nxyaz0.c in a temporary folder (cc, gcc or clang, -Werror) and imported, made once; None without a compiler or Python.h"""
    if not _built:
        mod = None
        cc = next((c for c in ('cc', 'gcc', 'clang') if shutil.which(c)), None)
        inc = sysconfig.get_paths().get('include') or ''
        if cc and os.path.isfile(os.path.join(inc, 'Python.h')):
            tmp = tempfile.mkdtemp(); atexit.register(shutil.rmtree, tmp, True)
            out = os.path.join(tmp, '_nxyaz0' + (sysconfig.get_config_var('EXT_SUFFIX') or '.so'))
            flags = ['-dynamiclib', '-undefined', 'dynamic_lookup'] if sys.platform == 'darwin' else ['-shared']
            r = subprocess.run([cc, *flags, '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror', '-I', inc, '-o', out, str(SRC)], capture_output=True, text=True)
            if r.returncode: raise AssertionError(f'_nxyaz0 does not build:\n{r.stdout}{r.stderr}')
            spec = importlib.util.spec_from_file_location('_nxyaz0', out)
            assert spec and spec.loader
            mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
        _built.append(mod)
    return _built[0]


def compress(b, size=None, near=4):
    """Yaz0 of b, greedy: at each byte the longest match among distance 1 and the last `near` places its first 3 bytes were seen (within 0x1000), as a 2-byte
    reference (3..0x11 bytes) or a 3-byte one (0x12..0x111), else a literal. A match may overlap the bytes it writes. size: the header's size (default len(b))"""
    out = bytearray(b'Yaz0' + (len(b) if size is None else size).to_bytes(4, 'big') + bytes(8)); seen = {}; i = 0
    while i < len(b):
        head = len(out); out.append(0)
        for bit in range(8):
            if i >= len(b): break
            best, dist = 0, 0
            for p in ([i - 1] if i else []) + seen.get(b[i:i + 3], [])[-near:]:
                if not 1 <= i - p <= 0x1000: continue
                n = 0
                while n < 0x111 and i + n < len(b) and b[p + n] == b[i + n]: n += 1
                if n > best: best, dist = n, i - p
            if best >= 3:
                d = dist - 1
                out += bytes([d >> 8, d & 0xff, best - 0x12]) if best >= 0x12 else bytes([(best - 2) << 4 | d >> 8, d & 0xff])
            else:
                out[head] |= 0x80 >> bit; out.append(b[i]); best = 1
            for j in range(i, i + best): seen.setdefault(b[j:j + 3], []).append(j)
            i += best
    return bytes(out)


def corpus():
    """(name, plain bytes): what shader files are made of (runs, repeats at every distance up to the window, text, random bytes) and the edges of a group"""
    r = random.Random(0x5a5a)
    text = b''.join(r.choice([b'BNSH', b'grsc', b'\x00' * 4, b'vec4 ', b'uniform ', b'\xff\xff', bytes([r.randrange(256)])]) for _ in range(30000))
    words = bytes(r.choice(b'ACGT') for _ in range(50000))
    periods = b''.join(bytes(r.randrange(256) for _ in range(p)) * r.randrange(2, 40) for p in (1, 2, 3, 5, 8, 17, 0x111, 0x400, 0xfff, 0x1000))
    return [('empty', b''), ('one', b'x'), ('seven', b'abcdefg'), ('eight', b'abcdefgh'), ('nine', b'abcdefghi'), ('zeros', bytes(100000)),
            ('random', r.randbytes(70000)), ('text', text), ('four letters', words), ('periods', periods),
            ('mixed', b''.join(r.choice([r.randbytes(r.randrange(1, 40)), bytes(r.randrange(1, 600)), text[:r.randrange(1, 3000)]]) for _ in range(300)))]


def python_loop(s, label='Yaz0'):
    """yaz0 with its Python loop alone: the bytes, or the SystemExit message red() stops with"""
    with mock.patch.object(ss, '_nxyaz0', None):
        try: return ss.yaz0(s, label)
        except SystemExit as e: return ('red', str(e))


class Same(unittest.TestCase):
    def setUp(self):
        self.mod = nxyaz0()
        if not self.mod: self.skipTest('no C compiler or no Python.h for _nxyaz0')

    def check(self, s, why):
        """decode(s) is the loop's bytes, or None where the loop stops; yaz0 with the module gives the loop's result either way"""
        py = python_loop(s)
        c = self.mod.decode(s)
        if isinstance(py, tuple): self.assertIsNone(c, f'{why}: the loop stops ({py[1]}), the module decoded {len(c or b"")} bytes')
        else: self.assertEqual(c, py, why)
        with mock.patch.object(ss, '_nxyaz0', self.mod):
            try: got = ss.yaz0(s)
            except SystemExit as e: got = ('red', str(e))
        self.assertEqual(got, py, why)
        return py

    def test_corpus(self):
        for name, b in corpus():
            for near in (1, 4):
                s = compress(b, near=near)
                self.assertEqual(self.check(s, f'{name}, near {near}'), b, name)
                self.assertEqual(self.check(bytearray(s), f'{name} as a bytearray'), b)
                self.assertEqual(self.check(memoryview(s), f'{name} as a memoryview'), b)

    def test_size_ends_inside_an_operation(self):   # a header size short of what the stream makes: the last reference or literal reaches past it, which is not kept
        b = b''.join(bytes([i]) * 0x111 for i in range(40)) + b'tail'
        s = compress(b)
        for n in (0, 1, 2, 0x110, 0x111, 0x112, 0x200, len(b) - 1):
            self.assertEqual(self.check(s[:4] + n.to_bytes(4, 'big') + s[8:], f'size {n}'), b[:n])

    def test_trailing_bytes(self):   # bytes after the last group (an archive's padding) are not read
        b = corpus()[7][1]
        self.assertEqual(self.check(compress(b) + bytes(0x80), 'padded'), b)

    def test_damaged(self):   # cut anywhere, or a reference before the start: the module gives None and yaz0 names the loop's error
        b = corpus()[10][1][:5000]; s = compress(b)
        stops = 0
        for cut in range(0, len(s)):
            if isinstance(self.check(s[:cut], f'cut at {cut:#x}'), tuple): stops += 1
        self.assertGreater(stops, len(s) - 20)
        for s in (b'Yaz0\0\0\0\x10' + bytes(8) + b'\x00\x10\x00', b'Yaz0\0\0\0\x10' + bytes(8) + b'\x80a\x10\x01', b'Yaz0\0\0\x01\0' + bytes(8) + b'\x00\xf0\x00'):
            r = self.check(s, 'a reference before the start')
            self.assertIsInstance(r, tuple); self.assertIn('reaches', r[1])
        for s in (b'', b'Yaz0', b'Yaz0\0\0', b'Yaz0\0\0\0\x05', b'Yaz0\0\0\0\x05' + bytes(8), b'Yaz0\0\0\0\0'):
            self.check(s, f'a stream of {len(s)} bytes')

    def test_random_streams(self):   # random groups after a header: whatever the loop does, the module does too
        r = random.Random(7)
        for i in range(3000):
            body = r.randbytes(r.randrange(0, 200))
            if i % 3 == 0: body = bytes([0xff]) + body   # a first group of literals, so later references can reach back
            n = r.choice([0, 1, 5, 8, 64, 255, 4096, r.randrange(1 << 16)])
            self.check(b'Yaz0' + n.to_bytes(4, 'big') + bytes(8) + body, f'random stream {i}')

    def test_size_the_stream_cannot_reach(self):   # a damaged header: None without allocating the size first, and the loop's error
        for n in (0xffffffff, 0x80000000, 2184 + 1):
            s = b'Yaz0' + n.to_bytes(4, 'big') + bytes(8)
            self.assertIsNone(self.mod.decode(s))
            self.assertIsInstance(python_loop(s), tuple)
        s = b'Yaz0' + (4096).to_bytes(4, 'big') + bytes(8) + b'\x80A' + bytes([0, 0, 0xff]) * 7 + b'\x00' + bytes([0, 0, 0xff]) * 8   # 1 + 15 references of 0x111
        self.assertEqual(self.check(s, 'two groups of the longest references'), b'A' * 4096)

    def test_a_buffer_that_is_not_one_run_of_bytes(self):   # a strided memoryview: None (it raised BufferError), and yaz0 gives the loop's bytes
        b = b'vec4 uniform ' * 200; s = compress(b)
        v = memoryview(bytes(x for c in s for x in (c, 0xee)))[::2]
        self.assertIsNone(self.mod.decode(v))
        with mock.patch.object(ss, '_nxyaz0', self.mod): self.assertEqual(ss.yaz0(v), b)

    @unittest.skipUnless(sys.platform.startswith('linux'), 'RLIMIT_AS')
    def test_a_size_with_no_memory_for_it(self):   # a damaged size a long stream lets through (47 MiB of it: up to 0xffffffff) and no memory for it: None
        import resource                           # (it raised MemoryError), and the loop names the reference before the start
        s = b'Yaz0\xff\xff\xff\xff' + bytes(8) + b'\x00\x00\x00' + bytes(49_164_000)
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        with open('/proc/self/statm') as f: vm = int(f.read().split()[0]) * resource.getpagesize()
        resource.setrlimit(resource.RLIMIT_AS, (vm + (1 << 30), hard))
        try:
            self.assertIsNone(self.mod.decode(s))
            with mock.patch.object(ss, '_nxyaz0', self.mod):
                with self.assertRaises(SystemExit) as e: ss.yaz0(s)
            self.assertIn('before the start', str(e.exception))
        finally: resource.setrlimit(resource.RLIMIT_AS, (soft, hard))


class Hook(unittest.TestCase):
    def test_used_when_there(self):   # shader_split takes the module when its Python has it (the phone's lib-dynload), and the loop's result when it says None
        fake = mock.Mock(); fake.decode.return_value = b'from C'
        with mock.patch.object(ss, '_nxyaz0', fake): self.assertEqual(ss.yaz0(b'Yaz0\0\0\0\x06' + bytes(8) + b'\xfcfrom C'), b'from C')
        fake.decode.return_value = None
        with mock.patch.object(ss, '_nxyaz0', fake): self.assertEqual(ss.yaz0(b'Yaz0\0\0\0\x06' + bytes(8) + b'\xfcfrom P'), b'from P')

    def test_python_loop_alone(self):   # without the module (a Mac, a PC without a compiler): the loop, as before
        with mock.patch.object(ss, '_nxyaz0', None):
            for name, b in corpus()[:6]: self.assertEqual(ss.yaz0(compress(b)), b, name)


if __name__ == '__main__':
    unittest.main()
