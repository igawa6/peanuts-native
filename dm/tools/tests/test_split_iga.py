"""PEANUTS: shader_split reads Alchemy's igArchive version 13 (Crash Team Racing Nitro-Fueled's .pak files): the files are listed with their blocks from the
archive's own block tables (iga_entries), each block is read at its sector and decoded when its entry says it is compressed (iga_member), and the GLSLC outputs
of every file are taken (iga_shaders). A block that is stored as it is can begin with bytes that read as a small length, so only the table says which it is.
Synthetic archives written from the layout in iga_entries. No game files needed."""
import io
import lzma
import os
import struct
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '010029B0118E8000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had

SECTOR, BLOCK = 0x800, 0x8000
PROPS = dict(id=lzma.FILTER_LZMA1, dict_size=1 << 16, lc=3, lp=0, pb=2)


def packed(d):
    """A compressed block: u32 stored length, the 5 property bytes (lc + 9 (lp + 5 pb), u32 dictionary size), the raw LZMA stream"""
    c = lzma.compress(d, format=lzma.FORMAT_RAW, filters=[PROPS])
    return struct.pack('<IBI', len(c), PROPS['lc'] + 9 * (PROPS['lp'] + 5 * PROPS['pb']), PROPS['dict_size']) + c


def archive(files, version=13, kind=2):
    """files = [(name, data, mode)]; mode 'stored': the file as it is (0xFFFFFFFF); else a string of 'c' / 'r' per 0x8000-byte block (compressed, raw).
    A file of more than 0x7F sectors takes its entries from the medium table, a smaller one from the small table"""
    body = bytearray(); recs = []; small = []; medium = []
    for name, data, mode in files:
        while len(body) % SECTOR: body.append(0xee)
        start = len(body)
        if mode == 'stored': body += data; recs.append((start, len(data), 0xFFFFFFFF)); continue
        tab, flag = (small, 0x80) if len(data) <= 0x7f * SECTOR else (medium, 0x8000)
        first = len(tab)
        for i, m in enumerate(mode):
            while len(body) % SECTOR: body.append(0xee)
            blk = data[i * BLOCK:(i + 1) * BLOCK]
            tab.append((len(body) - start) // SECTOR | (flag if m == 'c' else 0))
            body += packed(blk) if m == 'c' else blk
        tab.append((len(body) - start + SECTOR - 1) // SECTOR)   # the entry after the last block: where the file ends
        recs.append((start, len(data), kind << 28 | first))
    n = len(files)
    names = bytearray(4 * n)
    for i, (name, _, _) in enumerate(files):
        struct.pack_into('<I', names, 4 * i, len(names)); names += b'c:/build/' + name.encode() + b'\0' + name.encode() + b'\0'
    toc = 4 * n + 16 * n + 2 * len(medium) + len(small)
    data_at = (0x38 + toc + SECTOR - 1) // SECTOR * SECTOR
    name_at = data_at + len(body)
    out = bytearray(struct.pack('<4s9IQII', ss.IGA, version, toc, n, SECTOR, 1, 0, 0, len(medium), len(small), name_at, len(names), 1))
    out += bytes(4 * n)
    for i, (off, length, mode) in enumerate(recs): out += struct.pack('<4I', data_at + off, i, length, mode)
    out += struct.pack(f'<{len(medium)}H', *medium) + bytes(small)
    out += bytes(data_at - len(out)) + body + names
    return bytes(out)


def noise(n, seed=1):
    out = bytearray(); x = seed
    while len(out) < n: x = (x * 1103515245 + 12345) & 0x7fffffff; out += struct.pack('<I', x)
    return bytes(out[:n])


class Iga(unittest.TestCase):
    def read(self, d):
        f = io.BytesIO(d); sector, ents = ss.iga_entries(f)
        return {name: ss.iga_member(f, sector, off, length, blocks) for name, off, length, blocks in ents}

    def test_files_decode_from_the_small_and_the_medium_table(self):
        a, b, c = b'A' * 100, noise(BLOCK + 0x123), (b'xyz' * 0x30000)[:0x7f * SECTOR + 5]   # c: over 0x7F sectors, so the medium table
        got = self.read(archive([('shaders/a.igz', a, 'c'), ('raw.bin', b, 'stored'), ('maps/big.igz', c, 'c' * 8)]))
        self.assertEqual(got, {'shaders/a.igz': a, 'raw.bin': b, 'maps/big.igz': c})

    def test_a_raw_block_that_looks_like_a_length_is_read_raw(self):
        # an incompressible block is stored as it is; one that starts with a small u32 would pass for a compressed block's length (65 of the game's files have such a block)
        raw = struct.pack('<I', 0x1234) + noise(BLOCK - 4); tail = b'tail' * 100
        got = self.read(archive([('textures/n.igz', raw + tail, 'rc')]))
        self.assertEqual(got['textures/n.igz'], raw + tail)

    def test_other_files_and_versions(self):
        self.assertIsNone(ss.iga_entries(io.BytesIO(b'PAK\0' + bytes(0x40))))
        self.assertIsNone(ss.iga_entries(io.BytesIO(ss.IGA)))
        with self.assertRaises(SystemExit) as e: ss.iga_entries(io.BytesIO(archive([('a', b'x', 'c')], version=12)))
        self.assertIn('version 12', str(e.exception.code))
        with self.assertRaises(SystemExit) as e: ss.iga_entries(io.BytesIO(archive([('a', b'x', 'c')], kind=3)))
        self.assertIn('compression 3', str(e.exception.code))

    def test_a_damaged_block_stops_with_a_reason(self):
        d = bytearray(archive([('a.igz', noise(0x4000), 'c')]))
        f = io.BytesIO(bytes(d)); sector, ((_, off, length, blocks),) = ss.iga_entries(f)
        d[off + 9:off + 0x40] = bytes(0x37)   # the stream's first bytes
        with self.assertRaises(SystemExit) as e: ss.iga_member(io.BytesIO(bytes(d)), sector, off, length, blocks)
        self.assertIn('igArchive', str(e.exception.code))

    def test_only_files_with_a_glslc_output_are_scanned(self):
        seen = []
        real_open, real_glslc = ss.open, ss.glslc_shaders
        mag = b'\x91\x68\x86\x19'
        d = archive([('shaders/Standard.igz', b'igz' + mag + b'output', 'c'), ('textures/t.igz', b'pixels' * 50, 'c'), ('shaders/Stored.igz', mag + b'two', 'stored')])
        ss.open = lambda path, mode='rb': io.BytesIO(d)
        ss.glslc_shaders = lambda m, label, need=True, skip=(), sink=None: seen.append((m, label, need)) or [(label, 'ps', b'ctl', m)]
        try:
            found = []; ss.iga_shaders('/x/archives/shaders_nx.pak', 'archives/shaders_nx.pak', found)
        finally: ss.open, ss.glslc_shaders = real_open, real_glslc
        self.assertEqual([(s[1], s[2]) for s in seen], [('/archives/shaders_nx.pak/shaders/Standard.igz', False), ('/archives/shaders_nx.pak/shaders/Stored.igz', False)])
        self.assertEqual(len(found), 2)


if __name__ == '__main__':
    unittest.main()
