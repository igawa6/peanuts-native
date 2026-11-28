"""PEANUTS: legacy PAK v7 footer and relative compression blocks."""
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'gfx/tools'))
import uepak


def fstr(s):
    b = s.encode() + b'\0'
    return struct.pack('<i', len(b)) + b


class PakV7(unittest.TestCase):
    def test_legacy_entries(self):
        for version in (4, 7):
            for compressed in (False, True):
                with self.subTest(version=version, compressed=compressed):
                    raw = b'LSLG-test-shader' * 64
                    data = zlib.compress(raw) if compressed else raw
                    off = 128
                    head = 73 if compressed else 53

                    entry = struct.pack(
                        '<qqqi', off, len(data), len(raw),
                        int(compressed)
                    ) + hashlib.sha1(data).digest()

                    if compressed:
                        start = head + (off if version < 5 else 0)
                        entry += struct.pack(
                            '<iqq', 1, start, start + len(data)
                        )

                    entry += struct.pack('<BI', 0, len(raw))
                    body = bytes(off) + entry + data
                    index = (
                        fstr('../../../') +
                        struct.pack('<i', 1) +
                        fstr('test.bin') + entry
                    )
                    footer = struct.pack(
                        '<BIIQQ', 0, uepak.PAK_MAGIC,
                        version, len(body), len(index)
                    ) + hashlib.sha1(index).digest()

                    with tempfile.TemporaryDirectory() as tmp:
                        p = Path(tmp) / 'test.pak'
                        p.write_bytes(body + index + footer)
                        pak = uepak.Pak(str(p))
                        try:
                            self.assertEqual(pak.version, version)
                            self.assertEqual(pak.count, 1)
                            self.assertEqual(pak.read('test.bin'), raw)
                        finally:
                            pak.f.close()


if __name__ == '__main__':
    unittest.main()
