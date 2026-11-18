#!/usr/bin/env python3
"""NROs a boot stub packs into main (dm/tools/boot_nro.py), on synthetic streams made by the inverse of its decoding, and what nro_collect.py takes from them:
the registered hashes of a version 1 NRR, and a decoded NRO kept only when an NRR registers it. The real check is Mario Tennis Fever 1.1.1's main, whose six parts make
the NRO its NRR registers (boot_nro.py's docstring); this keeps the format from drifting without the game's files."""
import hashlib, os, struct, sys, tempfile, unittest
from unittest import mock
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import boot_nro, nro_collect


def lz_pack(data):
    """Camelot's LZ (cra.lz_undo) for data: literals, and a match where the bytes four back repeat, then the end code"""
    out, i, items = bytearray(), 0, []
    while i < len(data):
        n = 0
        while i >= 4 and i + n < len(data) and n < 15 and data[i + n] == data[i + n - 4]: n += 1
        if n >= 2: items.append(bytes([4 << 4 | n, 0])); i += n
        else: items.append(bytes([data[i]])); i += 1
    items.append(bytes([0, 0]))   # distance 0: the end
    for k in range(0, len(items), 8):
        group = items[k:k + 8]; f = 0
        for j, it in enumerate(group):
            if len(it) == 2: f |= 0x80 >> j
        out.append(-f & 0xff)
        for it in group: out += it
    return bytes(out)


def put(path, data):
    with open(path, 'wb') as f: f.write(data)


def rotr(b): return (b >> 1 | b << 7) & 0xff


def scramble(plain, size, key0, step, xor, seed):
    """The stream bytes (low address first), its end right after them: the inverse of boot_nro's reading"""
    mt = None
    if seed is not None:
        mt = boot_nro.mt19937(seed)
        for _ in range(boot_nro.MT_UNUSED): next(mt)
    w = size ^ xor
    # the size: bytes end-4 … end-8, each unscrambled against the next one down, so they are chosen from end-8 (free) upwards
    words = [(w >> s) & 0xff for s in (24, 16, 8, 0)]   # for the pairs (end-4,end-5) … (end-7,end-8)
    ks = [next(mt) & 0xff if mt else 0 for _ in range(4)]
    up = [0] * 5; up[4] = 0x5a   # up[j]: byte end-4-j
    for j in range(3, -1, -1):
        b = words[j] ^ ks[j] ^ up[j + 1]   # rotr(up[j]) = b
        up[j] = (b << 1 | b >> 7) & 0xff
    raw = [0] * len(plain); prev = up[4]; key = key0
    for i, p in enumerate(plain):
        r = p ^ rotr(prev) ^ key
        if mt: r ^= next(mt) & 0xff
        raw[i] = r; prev = r; key = (key + step) & 0xff
    # reading order is end-9 downwards: raw[0] is at end-9
    return bytes(reversed(raw)) + bytes([up[4], up[3], up[2], up[1], up[0]]) + b'\0\0\0'   # end-8 … end-4, then end-3 … end-1


class BootNro(unittest.TestCase):
    def image(self, chunks, parts):
        """An image: a data area, then each part's stream; the table of the stream ends after them"""
        img = bytearray(b'\0' * 0x40); ends = []
        for data, (key0, step, xor, seed) in zip(chunks, parts):
            img += scramble(lz_pack(data), len(data), key0, step, xor, seed); ends.append(len(img))
        table = len(img)
        for e in ends: img += struct.pack('<Q', e)
        return bytes(img), table

    def test_parts_decode_in_order(self):
        parts = [(0x68, 0x0d, 0x34414e5b, None), (0x90, 0x12, 0x485a6c7e, 0x76)]
        chunks = [b'NRO header and code ' * 9 + bytes(range(40)), b'abcdabcdabcdXYZ' * 20 + b'tail']
        img, table = self.image(chunks, parts)
        self.assertEqual(boot_nro.decode(img, 0x40, table, parts), b''.join(chunks))

    def test_wrong_size_is_an_error(self):
        parts = [(0x68, 0x0d, 0x34414e5b, None)]
        img, table = self.image([b'0123456789' * 5], parts)
        with self.assertRaises(boot_nro.Error): boot_nro.decode(img, 0x40, table, [(0x68, 0x0d, 0x34414e5c, None)])

    def test_mt19937_reference(self):
        # init_genrand(5489), the C++ standard's default_seed: its 10,000th output is 4,123,659,995
        g = boot_nro.mt19937(5489)
        for _ in range(9999): next(g)
        self.assertEqual(next(g), 4123659995)


class Collect(unittest.TestCase):
    def nrr(self, hashes, version):
        hdr = 0x4c0 if version == 1 else 0x340
        d = bytearray(hdr + 8); d[:4] = b'NRR0'; d[5] = version
        struct.pack_into('<II', d, hdr, len(d), len(hashes))
        for h in hashes: d += h
        return bytes(d)

    def test_nrr_version_1_hashes(self):
        h = hashlib.sha256(b'module').digest()
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, '.nrr'))
            put(os.path.join(t, '.nrr', 'a.nrr'), self.nrr([h], 1))
            self.assertEqual(nro_collect.nrr_hashes(t), {h: 'a.nrr'})

    def test_nrr_version_0_hashes(self):
        h = hashlib.sha256(b'module').digest()
        with tempfile.TemporaryDirectory() as t:
            put(os.path.join(t, 'a.nrr'), self.nrr([h], 0))
            self.assertEqual(nro_collect.nrr_hashes(t), {h: 'a.nrr'})

    def test_boot_stub_nro_beside_the_romfs(self):
        body = bytearray(0x100); body[0x10:0x14] = b'NRO0'; struct.pack_into('<I', body, 0x18, len(body)); body = bytes(body)
        other = b'not registered'
        with tempfile.TemporaryDirectory() as t:
            rom, exe = os.path.join(t, 'RomFS'), os.path.join(t, 'ExeFS')
            os.makedirs(rom); os.makedirs(exe)
            put(os.path.join(exe, 'main'), b'NSO0' + bytes(0x3c) + bytes.fromhex('ab' * 16) + bytes(0x10))
            put(os.path.join(rom, 'a.nrr'), self.nrr([hashlib.sha256(body).digest()], 1))
            out = os.path.join(t, 'out')
            with mock.patch.dict(boot_nro.BUILDS, {'ab' * 16: ('test', 0, [])}), \
                 mock.patch.object(boot_nro, 'nros', lambda d, n: [('main boot stub', body), ('main other', other)] if n == 'main' else []), \
                 mock.patch.object(sys, 'argv', ['nro_collect.py', rom, out]):
                nro_collect.main()
            with open(os.path.join(out, 'nro.tsv')) as f: rows = [l.split('\t') for l in f if not l.startswith('#')]
            self.assertEqual([(r[0], r[1], r[4].strip()) for r in rows], [('nro0', hashlib.sha256(body).hexdigest(), 'boot-stub main boot stub')])
            with open(os.path.join(out, 'nro0.nro'), 'rb') as f: self.assertEqual(f.read(), body)


if __name__ == '__main__':
    unittest.main()
