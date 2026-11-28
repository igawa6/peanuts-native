"""PEANUTS(upstream-candidate): the spin wait search in C (rwspin_native/rwspin.c, patch_nso.py spin_native) against the Python's, without game files: the
decoder against capstone (spin_word_make, spin_rw) on random and generated words, spin_rmw and spin_lock on generated lock sequences, and which heads have a
region on generated loops. docs/reference/rewriter.md "The spin wait search in C" has the runs over whole modules."""
import ast
from array import array
import ctypes
import hashlib
import os
from pathlib import Path
import random
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM, ARM64_OP_REG

TOOLS = Path(__file__).resolve().parents[1]
SRC = (TOOLS / 'patch_nso.py').read_text(encoding='utf-8')
C = TOOLS / 'rwspin_native' / 'rwspin.c'
tree = ast.parse(SRC)
FUNCS = {'spin_ins', 'spin_norm', 'spin_rw', 'spin_kind', 'spin_rmw', 'spin_lock', 'spin_store', 'spin_quick', 'spin_region', 'spin_region1',
         'spin_bt', 'spin_cached', 'spin_word_make', 'spin_word', 'spin_rw_at', 'spin_word_lite', 'spin_rn', 'spin_quick_class',
         'spin_native_lib', 'spin_native_reg', 'spin_native_mask', 'spin_native_ask', 'spin_native', 'spin_native_free', 'spin_heads_with_region'}
CONSTS = {'SPIN_PATHS', 'SPIN_STEPS', 'SPIN_STORES', 'SPIN_LSE', 'SPIN_BAD', 'SPIN_STOP', 'SPIN_LOCK0', 'SPIN_STR', 'SPIN_MEM', 'spin_rns', 'SPIN_QK', 'spin_wix',
          'spin_wtab', 'RWSPIN_SHA', 'RWSPIN_C', 'spin_nat', 'SPIN_NREG'}
body = [n for n in tree.body if (isinstance(n, ast.FunctionDef) and n.name in FUNCS) or (isinstance(n, ast.ClassDef) and n.name == 'SpinNo')
        or (isinstance(n, ast.Assign) and any(isinstance(t, ast.Tuple) and any(getattr(e, 'id', None) in CONSTS for e in t.elts) or getattr(t, 'id', None) in CONSTS for t in n.targets))]
NAMES = [f'x{i}' for i in range(31)] + ['sp'] + [f'v{i}' for i in range(32)] + ['nzcv']
KIND = {0: None, 1: 'br', 2: 'pc', 3: 'ok'}
SPIN_TOPS = {0x16, 0x17, 0x54, 0x34, 0x35, 0xB4, 0xB5, 0x36, 0x37, 0xB6, 0xB7}


def scope(img, path):
    """the spin wait search's functions over the image img (bytes), with the library at path (None: the Python search)"""
    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM); md.detail = True
    ns = {'re': re, 'struct': struct, 'array': array, 'os': os, 'sys': sys, '__file__': str(TOOLS / 'patch_nso.py'), 'TEXT_END': len(img) - 64,
          'ARM64_OP_IMM': ARM64_OP_IMM, 'ARM64_OP_MEM': ARM64_OP_MEM, 'ARM64_OP_REG': ARM64_OP_REG, 'IMG': img, 'md': md, 'md_lite': Cs(CS_ARCH_ARM64, CS_MODE_ARM),
          'spin_dec': {}, 'DATA_BLOCKS': [], 'spin_touched': set()}
    old = os.environ.get('DM_REWRITE_NATIVE')
    os.environ['DM_REWRITE_NATIVE'] = path or '0'
    try:
        exec(compile(ast.Module(body=body, type_ignores=[]), str(TOOLS / 'patch_nso.py'), 'exec'), ns)
        ns['spin_native']()
    finally:
        if old is None: del os.environ['DM_REWRITE_NATIVE']
        else: os.environ['DM_REWRITE_NATIVE'] = old
    return ns


def names(lo, hi):
    m = (lo & (2**64 - 1)) | ((hi & (2**64 - 1)) << 64)
    return frozenset(NAMES[i] for i in range(128) if m >> i & 1)


# words of the classes rwspin.c decodes, their free fields random: (mask of the fixed bits, their value)
CLASSES = [(0x1F000000, 0x10000000), (0x1F800000, 0x11000000), (0x1F800000, 0x12000000), (0x1F800000, 0x12800000), (0x1F800000, 0x13000000),
           (0x1F800000, 0x13800000), (0x1F000000, 0x0A000000), (0x1F200000, 0x0B000000), (0x1F200000, 0x0B200000), (0x1FE00000, 0x1A000000),
           (0x1FE00000, 0x1A400000), (0x1FE00000, 0x1A800000), (0x5FE00000, 0x1AC00000), (0x5FE00000, 0x5AC00000), (0x1F000000, 0x1B000000),
           (0x3B000000, 0x39000000), (0x3B200000, 0x38000000), (0x3B200C00, 0x38200800), (0x3A000000, 0x28000000), (0x3F000000, 0x08000000),
           (0x3B000000, 0x18000000), (0x7C000000, 0x14000000), (0xFF000000, 0x54000000), (0x7E000000, 0x34000000), (0x7E000000, 0x36000000),
           (0xFE000000, 0xD6000000), (0xFF000000, 0xD4000000), (0xFFE00000, 0xD5000000), (0xFFFFF01F, 0xD503201F), (0xFFFFF01F, 0xD503301F)]


def words(n, seed):
    rnd = random.Random(seed); out = []
    for i in range(n):
        if i % 3 == 0: out.append(rnd.getrandbits(32)); continue
        m, v = rnd.choice(CLASSES); w = v | (rnd.getrandbits(32) & ~m)
        if rnd.random() < 0.3: w = (w & ~0x3E0) | (rnd.choice([31, 31, 8]) << 5)   # register fields of 31 (sp / zr) often
        if rnd.random() < 0.3: w = (w & ~0x1F) | rnd.choice([31, 31, 8])
        out.append(w & 0xFFFFFFFF)
    return out


def lock_words(n, seed):
    """lock, release, exclusive add and test loop sequences with their fields and branch offsets now and then wrong"""
    rnd = random.Random(seed); out = []
    R = lambda: rnd.choice([rnd.randrange(32), 31, 8, 9])
    dd = lambda e: e + rnd.choice([0, 0, 0, 1, -1])
    bc = lambda cond, d: 0x54000000 | ((d & 0x7FFFF) << 5) | cond
    cb = lambda nz, x, rt, d: (0x35000000 if nz else 0x34000000) | (x << 31) | ((d & 0x7FFFF) << 5) | rt
    while len(out) < n:
        t = rnd.randrange(6); x = rnd.choice([0, 0, 0, 1]); rt, rn, rs, rk = R(), R(), R(), R()
        ld = 0x885F7C00 | (rnd.choice([0, 1]) << 15) | (x << 30) | (rn << 5) | rt
        stx = 0x88007C00 | (rnd.choice([0, 1]) << 15) | (rnd.choice([x, x, 1 - x]) << 30) | (rs << 16) | (rn << 5) | rk
        if t == 0:
            seq = [ld, cb(0, rnd.choice([x, 0]), rnd.choice([rt, rt, R()]), dd(4)), 0xD503305F | (rnd.randrange(16) << 8), 0x14000000 | (dd(-3) & 0x3FFFFFF), stx,
                   cb(1, rnd.choice([0, 0, 1]), rnd.choice([rs, rs, R()]), dd(-5))]
        elif t == 1:
            seq = [0xD503305F | (rnd.randrange(16) << 8), ld, 0x7100001F | (x << 31) | (rnd.randrange(4096) << 10) | (rnd.choice([rt, rt, 31]) << 5) | (rnd.choice([0, 0, 1]) << 22),
                   bc(rnd.choice([1, 1, 0]), dd(-3)), 0x88007C00 | (rnd.choice([0, 0, 1]) << 30) | (rs << 16) | (rn << 5) | rnd.choice([31, 31, rk]),
                   cb(1, 0, rnd.choice([rs, rs, R()]), dd(-4)), rnd.getrandbits(32)]
        elif t == 2:
            d = R()
            add = (rnd.choice([0x11000000, 0x51000000, 0x31000000, 0x0B000000]) | (rnd.choice([x, x, 1 - x]) << 31) | (rnd.choice([0, 0, 0, 1]) << 22)
                   | (rnd.choice([0, rnd.randrange(4096)]) << 10) | (rnd.choice([rt, rt, 31, R()]) << 5) | d)
            seq = [ld, add, 0x88007C00 | (rnd.choice([x, x, 1 - x]) << 30) | (rs << 16) | (rnd.choice([rn, rn, R()]) << 5) | rnd.choice([d, d, R()]),
                   cb(1, rnd.choice([0, 0, 1]), rnd.choice([rs, rs, R()]), dd(-3))]
        elif t == 3:
            l = rnd.choice([0xB9400000, 0x39400000, 0x79400000, 0xF9400000, 0xBD400000, 0xB8400400, 0xB8606800, 0x18000000]) | (rnd.randrange(4096) << 10) | (rn << 5) | rt
            seq = [l, 0x7100001F | (rnd.choice([0, 0, 1]) << 31) | (rnd.randrange(4096) << 10) | (rnd.choice([rt, rt, 31, R()]) << 5), bc(rnd.choice([0, 1, 2]), dd(-2))]
        elif t == 4:
            seq = [0x889FFC00 | (rnd.choice([0, 1]) << 30) | (rn << 5) | rnd.choice([31, 31, rt])]
        else:
            seq = [rnd.getrandbits(32) for _ in range(3)]
        for w in seq:
            c = rnd.random()
            out.append((w ^ (1 << rnd.randrange(32)) if c < 0.08 else rnd.getrandbits(32) if c < 0.12 else w) & 0xFFFFFFFF)
    return out


def loops(n, seed):
    """small polls and counted loops (loads, compares, adds, stores, locks, calls), each ending in a backward branch"""
    rnd = random.Random(seed); out = []
    body = [0xB940012E, 0x394262AE, 0x710005DF, 0x7100001F, 0x7A4019C4, 0x1A9FD7EE, 0x4B1901A8, 0x0B19010F, 0xB90096AF, 0x11000529, 0x71000529, 0xF9400268,
            0xB9000020, 0x3900003F, 0xD503201F, 0xD5033BBF, 0x885FFD2E, 0x880E7D2A, 0x94000010, 0x90000000, 0x10000000, 0x4E183E68, 0x1E212000, 0xB8408C20,
            0xA9408420, 0xD65F03C0, 0x885F7C20, 0x11000420, 0x88017C20, 0x889FFD3F, 0x8B020020, 0xAA0103E0, 0x9A820020, 0x52800020, 0xF2A00020, 0xD37FF820]
    while len(out) < n:
        k = rnd.randrange(1, 12); start = len(out)
        for _ in range(k):
            c = rnd.random()
            if c < 0.12: out.append(0x54000000 | ((rnd.randrange(1, 6) & 0x7FFFF) << 5) | rnd.randrange(16))   # an exit forward
            elif c < 0.2: out.append(0x34000000 | ((rnd.randrange(1, 4) & 0x7FFFF) << 5) | rnd.choice([8, 9, 14]))
            else: out.append(rnd.choice(body))
        d = start - len(out) - rnd.choice([0, 0, 1])
        out.append(rnd.choice([0x14000000 | (d & 0x3FFFFFF), 0x54000001 | ((d & 0x7FFFF) << 5), 0x35000000 | ((d & 0x7FFFF) << 5) | 14]))
    return out


def candidates(img):
    """the scan's spin wait candidates (patch_nso.py scan_piece): {head: [backward branches]}"""
    cand = {}
    for o in range(0, len(img) - 64, 4):
        w = struct.unpack_from('<I', img, o)[0]; t = w >> 24
        if t not in SPIN_TOPS: continue
        if t in (0x16, 0x17): d = ((w & 0x3FFFFFF) - (1 << 26)) * 4
        elif t & 0x7E == 0x36: d = (((w >> 5) & 0x3FFF) - (1 << 14)) * 4 if w & 0x40000 else 4
        elif t == 0x54: d = (((w >> 5) & 0x7FFFF) - (1 << 19)) * 4 if w & 0x800000 and not w & 0x10 else 4
        else: d = (((w >> 5) & 0x7FFFF) - (1 << 19)) * 4 if w & 0x800000 else 4
        if -512 <= d <= 0: cand.setdefault(o + d, []).append(o)
    return cand


@unittest.skipUnless(shutil.which(os.environ.get('CC') or 'cc'), 'no C compiler')
class NativeSameAsPython(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.lib = os.path.join(cls.tmp, 'libdmsh_rwspin.so')
        subprocess.run(['sh', str(TOOLS / 'rwspin_native' / 'build.sh'), cls.lib], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_patch_nso_names_this_source(self):
        self.assertIn(f"RWSPIN_SHA = '{hashlib.sha256(C.read_bytes()).hexdigest()}'", SRC, 'rwspin.c changed: write its SHA-256 into patch_nso.py RWSPIN_SHA (the rewrite key follows it)')
        L = ctypes.CDLL(self.lib); L.rws_source.restype = ctypes.c_char_p
        self.assertEqual(L.rws_source().decode(), hashlib.sha256(C.read_bytes()).hexdigest())

    def test_the_decoder_gives_capstone_values(self):
        ws = words(120000, 7)
        img = struct.pack('<%dI' % len(ws), *ws) + bytes(64)
        ns = scope(img, self.lib)
        L = ns['spin_nat']['lib']; out = (ctypes.c_int64 * 12)(); claimed = 0
        for i, w in enumerate(ws):
            a = 0x1000000 + 4 * i
            if not L.rws_word(w, out): continue
            claimed += 1
            x = next(ns['md'].disasm(struct.pack('<I', w), a), None)
            t = ns['spin_word_make'](x, a); t = (t[0], t[1], t[2], t[3], t[4] if t[0] is not None else False, t[5])
            st = None if not out[4] else (None if out[5] < 0 else NAMES[out[5]], NAMES[out[6]])
            self.assertEqual((KIND[out[0]], None, bool(out[1]), st, bool(out[2]), out[3]), t, hex(w))
            if t[0] is not None:
                r, wr = ns['spin_rw'](x)
                self.assertEqual((names(out[8], out[9]), names(out[10], out[11])), (frozenset(r) - {None}, frozenset(wr) - {None}), hex(w))
        self.assertGreater(claimed, 50000)

    def test_spin_rmw_and_spin_lock(self):
        ws = lock_words(60000, 3)
        img = struct.pack('<%dI' % len(ws), *ws) + bytes(64)
        ns = scope(img, self.lib)
        L = ns['spin_nat']['lib']; st = ns['spin_nat']['st']
        L.rws_seq.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int64, ctypes.POINTER(ctypes.c_int64)]
        out = (ctypes.c_int64 * 11)(); found = [0, 0]
        for a in range(8, 4 * len(ws), 4):
            if L.rws_seq(st, 2, a, out):
                t = ns['spin_rmw'](a); found[0] += t is not None
                self.assertEqual(None if not out[0] else (NAMES[out[2]], out[6], names(out[9], out[10])), None if t is None else (t[0], t[1], frozenset(t[2])), hex(a))
            if L.rws_seq(st, 3, a, out):
                t = ns['spin_lock'](a); found[1] += t is not None
                c = None if not out[0] else ({1: 'acq', 2: 'rel', 3: 'tst'}[out[1]], NAMES[out[2]], out[3], out[4], out[5], names(out[7], out[8]), names(out[9], out[10]))
                self.assertEqual(c, None if t is None else (t[0], t[1], len(t[2]), t[3], t[4], frozenset(t[5]) - {None}, frozenset(t[6]) - {None}), hex(a))
            if len(ns['spin_dec']) > 4096: ns['spin_dec'].clear()
        self.assertGreater(found[0], 10); self.assertGreater(found[1], 1000)

    def test_the_same_heads_have_a_region(self):
        for seed in (1, 2):
            ws = loops(30000, seed) + lock_words(6000, seed)
            img = struct.pack('<%dI' % len(ws), *ws) + bytes(64)
            cand = candidates(img); heads = sorted(cand)
            got = {}
            for path in (self.lib, None):
                ns = scope(img, path); ns['spin_cand'] = cand; ns['spin_heads'] = heads
                self.assertEqual(ns['spin_nat']['st'] is not None, path is not None)
                got[path] = ns['spin_heads_with_region'](0, len(heads))
            self.assertEqual(got[self.lib], got[None])
            self.assertGreater(len(got[None]), 20)


if __name__ == '__main__':
    unittest.main()
