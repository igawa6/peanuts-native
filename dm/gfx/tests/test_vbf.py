"""VBF block decoding and unaligned Phyre NVN programs, without game files."""
from pathlib import Path
import os
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
os.environ.setdefault('DM_GAME', '010015100B514000')
import shader_split as split
import vbf

try: import lz4.block   # PEANUTS(upstream-candidate): only the comparison with the lz4 package below needs it
except ImportError: lz4 = None

# PEANUTS(upstream-candidate): 1,000 bytes of b'last block' * 100 as an LZ4 block written by hand: 10 literals, a match of 985 bytes at offset 10 (token 0xAF, then the length bytes 255 255 255 201), 5 literals.
LZ4_LAST = b'\xaflast block\x0a\x00\xff\xff\xff\xc9\x50block'


def program(stage):
    code = bytearray(0x100); code[:4] = split.GFX_MAGIC
    struct.pack_into('<I', code, 0x30, {'vs': 1, 'ps': 5}[stage] << 10)
    ctl = bytearray(0x880); ctl[:4] = split.CTL_MAGIC
    struct.pack_into('<I', ctl, 0x20, len(ctl))
    struct.pack_into('<I', ctl, 0x704, len(code))
    return bytes(code + ctl)


class Vbf(unittest.TestCase):
    def test_raw_compressed_and_shared_members(self):
        payload = bytes(range(256)) * 256 + b'last block' * 100
        compressed = zlib.compress(payload[65536:])
        names = b'a.fx.phyre\0shared.fx.phyre\0'
        hlen = 16 + 2 * 64 + 4 + len(names) + 6
        header = bytearray(struct.pack('<4sIQ', b'SRYK', hlen, 2) + bytes(64))
        header += struct.pack('<IIQQQ', 0, 0, len(payload), hlen, 0)
        header += struct.pack('<IIQQQ', 2, 0, 5, 0xffffffffffffffff, 11)
        header += struct.pack('<I', len(names) + 4) + names + struct.pack('<3H', 0, len(compressed), 5)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'test.vbf'; path.write_bytes(header + payload[:65536] + compressed)
            entries = vbf.entries(path)
            self.assertEqual(len(entries), 1)
            with path.open('rb') as f: self.assertEqual(vbf.read(f, entries[0]), payload)
            path.write_bytes(path.read_bytes()[:-2])
            with path.open('rb') as f:
                with self.assertRaises(ValueError): vbf.read(f, entries[0])

    # PEANUTS(upstream-candidate): FFXII The Zodiac Age's VBF blocks are LZ4, not zlib: the block kind is read from the block, not from an exception.
    def test_zlib_header(self):
        for level in (0, 1, 6, 9): self.assertTrue(vbf.zlib_header(zlib.compress(b'x' * 100, level)), level)
        self.assertTrue(vbf.zlib_header(zlib.compress(b'x' * 100, wbits=9)))
        self.assertFalse(vbf.zlib_header(LZ4_LAST))
        self.assertFalse(vbf.zlib_header(b'\xff' * 8))
        self.assertFalse(vbf.zlib_header(b'\x78'))
        self.assertFalse(vbf.zlib_header(b'\x78\xbb'))   # a multiple of 31 but with the preset dictionary flag
        self.assertFalse(vbf.zlib_header(b'\x88\x1c'))   # a window of 64 KB, which deflate does not have

    def test_lz4_block(self):
        self.assertEqual(vbf.lz4_block(LZ4_LAST, 1000), b'last block' * 100)
        lits = (bytes(range(256)) * 2)[:300]
        self.assertEqual(vbf.lz4_block(b'\xf0\xff\x1e' + lits, 300), lits)   # literals only, the count (15 + 255 + 30) in extension bytes
        self.assertIsNone(vbf.lz4_block(b'\x10a\x01\x00\x50aaaaa', 10))   # a match starts at least 12 bytes before the end
        bad = [LZ4_LAST[:-1], LZ4_LAST[:-6], LZ4_LAST[:11] + b'\x00\x00' + LZ4_LAST[13:],   # truncated, ends after a match, offset 0
               LZ4_LAST[:11] + b'\x0b\x00' + LZ4_LAST[13:], b'', b'\xff']                    # offset beyond the output, empty, a count with no bytes
        for block in bad: self.assertIsNone(vbf.lz4_block(block, 1000), block)
        self.assertIsNone(vbf.lz4_block(LZ4_LAST, 999)); self.assertIsNone(vbf.lz4_block(LZ4_LAST, 1001))
        self.assertIsNone(vbf.lz4_block(zlib.compress(b'last block' * 100), 1000))

    @unittest.skipIf(lz4 is None, 'the lz4 package is not installed')
    def test_lz4_block_matches_the_lz4_package(self):
        import random
        rng = random.Random(7)
        for i in range(300):
            n = rng.choice([1, 13, 100, 5000, 65536]); raw = bytes(rng.choice([0, 1, 2, rng.randrange(256)]) for _ in range(n))
            block = lz4.block.compress(raw, store_size=False)
            self.assertEqual(vbf.lz4_block(block, n), raw)

    def test_lz4_compressed_and_raw_blocks(self):
        payload = bytes(range(256)) * 256 + b'last block' * 100
        compressed = LZ4_LAST
        names = b'a.fx.phyre\0'
        hlen = 16 + 64 + 4 + len(names) + 4
        header = bytearray(struct.pack('<4sIQ', b'SRYK', hlen, 1) + bytes(32))
        header += struct.pack('<IIQQQ', 0, 0, len(payload), hlen, 0)
        header += struct.pack('<I', len(names) + 4) + names + struct.pack('<2H', 0, len(compressed))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'test.vbf'; path.write_bytes(header + payload[:65536] + compressed)
            entries = vbf.entries(path)
            self.assertEqual(len(entries), 1)
            with path.open('rb') as f: self.assertEqual(vbf.read(f, entries[0]), payload)
            corrupted = bytearray(path.read_bytes())
            start = hlen + 65536
            corrupted[start:start + len(compressed)] = b'\xff' * len(compressed)
            path.write_bytes(bytes(corrupted))
            with path.open('rb') as f:
                with self.assertRaises(ValueError): vbf.read(f, entries[0])

    def test_effect_and_loose_programs_use_same_key(self):
        vs, ps = program('vs'), program('ps')
        effect = b'RYHP' + bytes(9) + vs + bytes(7) + ps
        found = split.phyre_shaders(effect, 'effect')
        self.assertEqual([x[1] for x in found], ['vs', 'ps'])
        loose = struct.pack('<II', 0x100, 0x880) + vs
        self.assertEqual(split.skey(*found[0][2:]), split.skey(*split.phyre_shaders(loose, 'loose')[0][2:]))
        bad = bytearray(effect); struct.pack_into('<I', bad, 13 + 0x100 + 0x704, 0x104)
        self.assertEqual([x[1] for x in split.phyre_shaders(bad, 'bad')], ['ps'])
        self.assertEqual(split.phyre_shaders(b'RYHP' + split.CTL_MAGIC, 'short'), [])


if __name__ == '__main__': unittest.main()
