"""PEANUTS: shader archives are decoded one record at a time, with the same offsets and codec fallback."""
import io
from pathlib import Path
import struct
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'gfx/tools'))
import uepak


def archive(records):
    header = struct.pack('<5I', 2, 0, 0, 0, len(records))
    offset = 0
    entries = bytearray()
    for stored, raw in records:
        entries.extend(struct.pack('<QIIB', offset, len(stored), len(raw), 3))
        offset += len(stored)
    return header + entries + struct.pack('<II', 0, 0) + b''.join(stored for stored, _ in records)


class Decode(unittest.TestCase):
    def test_writes_each_record_before_decoding_the_next(self):
        raws = [b'LSLG-shader-a', b'LSLG-shader-b']
        packed = archive([(b'aa', raws[0]), (b'bbb', raws[1])])
        dest = io.BytesIO()

        def records():
            yield raws[0]
            self.assertTrue(dest.getvalue().endswith(raws[0]))
            yield raws[1]

        uepak.write_decoded_archive(dest, packed, uepak.archive(packed), records())
        self.assertEqual(dest.getvalue(), archive([(r, r) for r in raws]))

    def test_allocation_failure_is_not_an_oodle_request(self):
        try:
            import lz4.block
        except ImportError:
            self.skipTest('no LZ4 module')
        packed = archive([(b'xyz', b'LSLG-shader')])
        meta = uepak.archive(packed)
        with patch.object(lz4.block, 'decompress', side_effect=MemoryError):
            with self.assertRaises(MemoryError):
                uepak.lz4_shaders(packed, meta)
        with patch.object(lz4.block, 'decompress', side_effect=lz4.block.LZ4BlockError):
            self.assertIsNone(uepak.lz4_shaders(packed, meta))
