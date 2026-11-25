"""TT Games' DAT archives (ttdat.py) give the split the BNSH files of their RFPK-compressed resources: the index's name tree and file table, each of unref's four ops (overlapping
copies included), stored chunks, and ZIPX (RC4) chunks. Synthetic bytes only."""
# PEANUTS: Synthetic archive coverage for LEGO CITY UNDERCOVER's DAT reader.
import contextlib
import io
import struct
import sys
import tempfile
import unittest
from pathlib import Path

from dm.tools.tests.test_split_gzip import REPO, fz, ss

sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
import ttdat  # noqa: E402


def lits(b):
    """Literal ops (111xxxxx: 4..112 bytes, a multiple of 4) for all but the last len(b) % 4 bytes, which the caller attaches to the next op"""
    out, i = bytearray(), 0
    while len(b) - i >= 4:
        k = min(112, (len(b) - i) // 4 * 4); out.append(0xe0 | ((k - 4) >> 2)); out += b[i:i + k]; i += k
    return bytes(out), b[i:]


def copy_op(r, off, n):
    """One copy op with the r (0..3) literals before it, in the smallest form that holds (off, n)"""
    o = off - 1; lr = len(r)
    if 3 <= n <= 10 and o < 0x400: return bytes([((n - 3) << 4) | (lr << 2) | (o >> 8), o & 0xff]) + r
    if 4 <= n <= 67 and o < 0x4000: return bytes([0x80 | (n - 4), (lr << 6) | (o >> 8), o & 0xff]) + r
    m = n - 5
    assert 0 <= m < 0x400 and o < 0x20000
    return bytes([0xc0 | (lr << 3) | ((m >> 8) << 1) | (o >> 16), (o >> 8) & 0xff, o & 0xff, m & 0xff]) + r


def ref(data):
    """A greedy encoder of unref's format (matches of 3..1028 bytes up to 128 KB back), so that every op kind and overlapping copies occur"""
    out, pend, i, last = bytearray(), bytearray(), 0, {}
    while i < len(data):
        best = (0, 0)
        j = last.get(data[i:i + 3]) if i + 3 <= len(data) else None
        if j is not None and i - j <= 0x20000:
            n = 0
            while i + n < len(data) and n < 1028 and data[j + n] == data[i + n]: n += 1
            best = (i - j, n)
        if i + 3 <= len(data): last[data[i:i + 3]] = i
        off, n = best
        if n >= 3 and not (n < 4 and off > 0x400) and not (n < 5 and off > 0x4000):
            ops, r = lits(bytes(pend)); out += ops + copy_op(r, off, n); pend = bytearray()
            for k in range(i + 1, min(i + n, len(data) - 2)): last[data[k:k + 3]] = k
            i += n
        else:
            pend.append(data[i]); i += 1
    ops, r = lits(bytes(pend)); out += ops + bytes([0xfc | len(r)]) + r
    return bytes(out)


def rfpk(data, chunk=0x8000, store=False):
    out = bytearray()
    for at in range(0, len(data), chunk):
        c = data[at:at + chunk]; z = c if store else ref(c)
        out += b'RFPK' + struct.pack('<II', len(z), len(c)) + z
    return bytes(out)


def zipx(data):
    return b'ZIPX' + struct.pack('<II', len(data), len(data)) + ttdat.rc4(struct.pack('<I', len(data)), data)


def archive(files, top_bit=True):
    """A DAT: [(path 'folder/name', stored bytes, length, compressed)] → the file, with its data, then the big-endian index"""
    body, ft = bytearray(0x100), []
    for _, stored, size, packed in files:
        ft.append((len(body), len(stored), size | (0x80000000 if packed else 0))); body += stored
    body += bytes(-len(body) % 0x100)
    names, st = [], bytearray()

    def name(s):
        at = len(st); st.extend(s.encode() + b'\0\0'); return at
    names.append([0, 0, 0xffffffff, 0, 0])   # the root
    folders = {}
    for fid, (path, *_) in enumerate(files):
        parent = 0
        for part in path.split('/')[:-1]:
            if (parent, part) not in folders:
                folders[(parent, part)] = len(names); names.append([0, 0, name(part), parent, 0])
                if parent: names[parent][0] = len(names) - 1
            parent = folders[(parent, part)]
        names.append([0, 0, name(path.split('/')[-1]), parent, fid])
        if parent: names[parent][0] = len(names) - 1
    names[0][0] = len(names) - 1
    idx = bytearray(b'.CC40TAD' + struct.pack('>iIIII', -11, 2, len(files), len(names), len(st))) + st
    for e in names: idx += struct.pack('>HHIHH', *e)
    idx += struct.pack('>iiI', 0, -11, len(files))
    for o, z, s in ft: idx += struct.pack('>QII', o, z, s)
    idx = struct.pack('>I', len(idx)) + idx
    off = len(body)
    head = ((off - 0x100) >> 8) ^ 0xffffffff if top_bit else off
    body[:8] = struct.pack('<II', head, len(idx))
    return bytes(body) + idx


SAMPLE = b'hijklhijkl!' + bytes(range(7)) * 3 + b'abcabcabcabcabcabc' + bytes(5000) + b'.CC4HSER' + bytes(range(256)) * 600 + b'tail'


class Unref(unittest.TestCase):
    def test_round_trip(self):
        z = ref(SAMPLE)
        kinds = set()
        i = 0
        while i < len(z):   # walk the ops: every kind must occur
            b0 = z[i]
            if b0 < 0x80: kinds.add('a'); i += 2 + ((b0 >> 2) & 3)
            elif b0 < 0xc0: kinds.add('b'); i += 3 + (z[i + 1] >> 6)
            elif b0 < 0xe0: kinds.add('c'); i += 4 + ((b0 >> 3) & 3)
            elif b0 < 0xfc: kinds.add('lit'); i += 1 + ((b0 & 0x1f) << 2) + 4
            else: kinds.add('end'); break
        self.assertEqual(kinds, {'a', 'b', 'c', 'lit', 'end'})
        self.assertEqual(bytes(ttdat.unref(z, len(SAMPLE))), SAMPLE)

    def test_overlap(self):   # a copy from 1 byte back repeats it; from 3 back repeats the pattern
        z = bytes([0xe0]) + b'abcd' + copy_op(b'', 1, 9) + copy_op(b'xy', 3, 10) + bytes([0xfc])
        self.assertEqual(bytes(ttdat.unref(z, 25)), b'abcd' + b'd' * 9 + b'xy' + b'dxydxydxyd')

    def test_wrong_length(self):
        with self.assertRaises(ValueError): ttdat.unref(ref(b'hello world, hello world'), 99)

    def test_truncated(self):   # a damaged chunk is a ValueError (ttdat_shaders skips that one file), not an IndexError or struct.error that drops the whole range
        z = ref(SAMPLE)
        with self.assertRaises(ValueError): ttdat.unref(z[:len(z) // 2], len(SAMPLE))
        with self.assertRaises(ValueError): ttdat.unpack(rfpk(SAMPLE) + b'RFPK\1\0', len(SAMPLE))
        with self.assertRaises(ValueError): ttdat.unref(bytes([0x80, 0x00]), 4)   # a 3-byte op with 2 bytes

    def test_chunks(self):
        self.assertEqual(ttdat.unpack(rfpk(SAMPLE), len(SAMPLE)), SAMPLE)
        self.assertEqual(ttdat.unpack(rfpk(SAMPLE, store=True), len(SAMPLE)), SAMPLE)
        self.assertEqual(ttdat.unpack(zipx(b'//---- text'), 11), b'//---- text')


class Archive(unittest.TestCase):
    def files(self, top_bit=True):
        sh = b'\0\0\0\x85.CC4HSER' + bytes(0x20) + fz.bnsh() + bytes(0x40)
        self.files_in = [('levels/city/city_nxg.nnx_shaders', rfpk(sh, chunk=0x400), len(sh), True),
                         ('levels/city/city_nxg.txt', zipx(b'some text'), 9, True),
                         ('stuff/loose.bin', b'stored bytes', 12, False)]
        return archive(self.files_in, top_bit)

    def test_index(self):
        for top_bit in (True, False):
            with self.subTest(top_bit=top_bit), tempfile.TemporaryDirectory() as t:
                p = Path(t) / 'GAME.DAT'; p.write_bytes(self.files(top_bit))
                self.assertTrue(ttdat.is_ttdat(str(p)))
                es = ttdat.files(str(p))
                self.assertEqual([e[0] for e in es], [f[0] for f in self.files_in])
                self.assertEqual(ttdat.read(str(p), es[1]), b'some text')
                self.assertEqual(ttdat.read(str(p), es[2]), b'stored bytes')

    def test_parent_out_of_range(self):   # a name whose parent index is past the name table: ValueError, which ttdat_jobs reports (an IndexError ended the whole split)
        with tempfile.TemporaryDirectory() as t, contextlib.redirect_stdout(io.StringIO()) as log:
            d = bytearray(self.files())
            off, n = ttdat.index_at(d)
            nnames, nsize = struct.unpack_from('>II', d, off + 24)
            struct.pack_into('>H', d, off + 0x20 + nsize + 12 * (nnames - 1) + 8, 0xfff0)   # the last name's parent
            p = Path(t) / 'GAME.DAT'; p.write_bytes(bytes(d))
            with self.assertRaises(ValueError): ttdat.files(str(p))
            jobs, paths = ss.ttdat_jobs([(str(p), 'GAME.DAT')])
        self.assertEqual((jobs, paths), ([], set()))
        self.assertIn('the DAT index cannot be read', log.getvalue())

    def test_not_an_archive(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t) / 'GAME.DAT'; p.write_bytes(b'\xff\xff\xff\xff' + bytes(60))
            self.assertFalse(ttdat.is_ttdat(str(p)))

    def test_split(self):
        with tempfile.TemporaryDirectory() as t, contextlib.redirect_stdout(io.StringIO()) as log:
            p = Path(t) / 'GAME.DAT'; p.write_bytes(self.files())
            jobs, paths = ss.ttdat_jobs([(str(p), 'GAME.DAT')])
            self.assertEqual(paths, {str(p)})
            keys = {}
            for job in jobs:
                rel, found, pairs, extra = ss.scan_file(job)
                keys.update(found)
        self.assertEqual(sorted(v[1] for v in keys.values()), ['ps', 'vs'])
        self.assertTrue(all(v[0].startswith('/GAME.DAT/levels/city/city_nxg.nnx_shaders') for v in keys.values()))
        self.assertNotIn('[RED]', log.getvalue())


if __name__ == '__main__':
    unittest.main()
