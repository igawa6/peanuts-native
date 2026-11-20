"""Synthetic Level-5 VFS3 zstd archive. No game files needed."""
import io
import os
from pathlib import Path
import struct
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'gfx/tools'))
_had = os.environ.get('DM_GAME')
os.environ['DM_GAME'] = '01007BB017812000'  # shader_split requires an existing profile at import
import shader_split as split
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had


def fixture(offset=0):
    data = b'shader member with repeated data' * 512
    z = split.zstd_mod()
    compressed = z.compress(data, options={z.CompressionParameter.content_size_flag: 0, z.CompressionParameter.checksum_flag: 0})
    header = int.from_bytes(compressed[6:9], 'little')
    assert compressed[:5] == b'\x28\xb5\x2f\xfd\0' and header & 7 == 5 and header >> 3 == len(compressed) - 9
    body = compressed[9:]; name = b'sample.vfxo'
    archive = bytearray(split.VFS3 + struct.pack('<I', 1) + struct.pack('<IiiiIII', 0, 0, -1, -1, 0, 0, 1))
    archive += struct.pack('<IQQQIIIhh', 1, offset, len(body), len(data), 0, 0, 0, 3, -1)
    base = (len(archive) + 24 + 15) & ~15; chunks = base + len(body); dictionaries = chunks + 12; names = dictionaries + 8
    archive += struct.pack('<QQQ', chunks, dictionaries, names); archive += bytes(base - len(archive)) + body
    archive += struct.pack('<IIiII', 8, 4, len(body), 0, 0) + struct.pack('<II', 1, len(name)) + name + struct.pack('<II', 1, 0)
    return io.BytesIO(archive), data


class Vfs3(unittest.TestCase):
    def test_compressed_member(self):
        archive, data = fixture()
        self.assertEqual(list(split.vfs3_members(archive, 'example.vfs')), [('sample.vfxo', data)])

    def test_member_crossing_index(self):
        archive, _ = fixture(1)
        with self.assertRaisesRegex(SystemExit, 'member range'):
            list(split.vfs3_members(archive, 'example.vfs'))
