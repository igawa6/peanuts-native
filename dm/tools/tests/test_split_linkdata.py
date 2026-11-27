# PEANUTS: new file (LINKDATA with its index inside, DEAD OR ALIVE Xtreme 3 Scarlet's .lnk archives)
"""shader_split: LINKDATA archives. Three Houses' DATA1.bin is read through the index beside it (DATA0.bin) and its entries reach kt_shaders as they are stored;
DEAD OR ALIVE Xtreme 3 Scarlet's data_NN.lnk carries its index inside, and its compressed entries are inflated from their 0x10-aligned chunks (kt_lnk) first. No game files needed."""
import os
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '0100199023B2E000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had


def lnk_chunks(data, plain_from=None):
    """data as .lnk chunks: 16 KB each, a u32 0x8000 | the zlib stream's length, or the plain length from chunk plain_from on, each padded to 0x10"""
    out = b''
    for k, o in enumerate(range(0, len(data), 0x4000)):
        c = data[o:o + 0x4000]
        body, u = (c, len(c)) if plain_from is not None and k >= plain_from else (zlib.compress(c), None)
        out += struct.pack('<I', u if u is not None else 0x8000 | len(body)) + body
        out += bytes(-len(out) & 0xf)
    return out


def archive(blobs):
    """[(stored bytes, inflated length, compressed)] → (the entries' bytes laid out at 0x800, their 32-byte entries)"""
    data, ents = b'', []
    for stored, n, comp in blobs:
        ents.append((len(data), n, len(stored), comp)); data += stored + bytes(-len(stored) & 0x7ff)
    return data, ents


PAYLOAD = bytes(range(256)) * 160 + b'tail'   # 40,964 bytes: two full chunks and a short one


class Linkdata(unittest.TestCase):
    def scan(self, path, rel):
        seen = []
        real = ss.kt_shaders
        ss.kt_shaders = lambda d, label, out: seen.append((label, bytes(d)))
        try:
            for job in ss.linkdata_jobs(path, rel, parts=2) or []: ss.linkdata_shaders(job[0], job[1], job[2], [])
        finally: ss.kt_shaders = real
        return seen

    def test_kt_lnk(self):
        self.assertEqual(ss.kt_lnk(lnk_chunks(PAYLOAD), len(PAYLOAD)), PAYLOAD)
        self.assertEqual(ss.kt_lnk(lnk_chunks(PAYLOAD, plain_from=2), len(PAYLOAD)), PAYLOAD)   # the last chunk stored plain, as data_00.lnk entry 3709's
        self.assertEqual(ss.kt_lnk(lnk_chunks(b'x' * 0x4000, plain_from=0), 0x4000), b'x' * 0x4000)   # a whole plain chunk: the u32 0x4000, bit 15 clear
        self.assertIsNone(ss.kt_lnk(lnk_chunks(PAYLOAD)[:-40], len(PAYLOAD)))   # cut short
        self.assertIsNone(ss.kt_lnk(lnk_chunks(PAYLOAD), len(PAYLOAD) + 1))   # fewer bytes than the entry says
        bad = bytearray(lnk_chunks(PAYLOAD)); bad[8] ^= 0xff
        self.assertIsNone(ss.kt_lnk(bytes(bad), len(PAYLOAD)))   # a broken zlib stream

    def test_lnk_archive(self):
        with tempfile.TemporaryDirectory() as t:
            data, ents = archive([(lnk_chunks(PAYLOAD, plain_from=2), len(PAYLOAD), 1), (b'plain entry', 11, 0), (b'', 0, 0)])
            head = 32 + 32 * len(ents); head += -head & 0x7ff
            ents = [(off + head, n, s, c) for off, n, s, c in ents]
            body = b''.join(struct.pack('<QQQQ', *e) for e in ents)
            p = os.path.join(t, 'data_00.lnk')
            size = head + len(data)
            with open(p, 'wb') as f: f.write(b'b000\0\0\0\0' + struct.pack('<QQQ', len(ents), size, 0x800) + body + bytes(head - 32 - len(body)) + data)
            self.assertEqual(ss.lnk_entries(p), ents)
            self.assertEqual(self.scan(p, '00/data_00.lnk'), [('/00/data_00.lnk#0', PAYLOAD), ('/00/data_00.lnk#1', b'plain entry')])
            with open(p, 'ab') as f: f.write(b'x')
            self.assertIsNone(ss.lnk_entries(p))   # the size in the header no longer matches
            self.assertIsNone(ss.linkdata_jobs(p, '00/data_00.lnk'))
            q = os.path.join(t, 'data_00.bin')
            os.rename(p, q); self.assertIsNone(ss.lnk_entries(q))   # not a .lnk

    def test_index_beside(self):
        stored = zlib.compress(PAYLOAD)
        with tempfile.TemporaryDirectory() as t:
            data, ents = archive([(stored, len(PAYLOAD), 1), (b'plain entry', 11, 0), (b'', 0, 0)])
            with open(os.path.join(t, 'DATA1.bin'), 'wb') as f: f.write(data)
            with open(os.path.join(t, 'DATA0.bin'), 'wb') as f: f.write(b''.join(struct.pack('<QQQQ', *e) for e in ents) + b'part')   # a partial entry at the end is not read
            self.assertEqual(self.scan(os.path.join(t, 'DATA1.bin'), 'DATA1.bin'), [('/DATA1.bin#0', stored), ('/DATA1.bin#1', b'plain entry')])   # as stored: kt_shaders inflates
            with open(os.path.join(t, 'DATA0.bin'), 'wb'): pass
            self.assertEqual(ss.linkdata_jobs(os.path.join(t, 'DATA1.bin'), 'DATA1.bin'), [])   # an empty index plans no job, and the file is scanned whole


if __name__ == '__main__':
    unittest.main()
