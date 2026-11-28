# PEANUTS(upstream-candidate): Exercise the stored-member padding and Viper fallback.
import io
import struct
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'gfx/tools'))
from shader_split import zip_member


class ZipPadding(unittest.TestCase):
    def stored(self, payload, stored):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('shader.bin', payload)
        data = bytearray(stream.getvalue())
        central = data.index(b'PK\x01\x02')
        end = data.index(b'PK\x05\x06')
        struct.pack_into('<I', data, 18, len(stored))
        struct.pack_into('<I', data, central + 20, len(stored))
        offset = 30 + len('shader.bin')
        struct.pack_into('<I', data, end + 16, offset + len(stored))
        data[offset:central] = stored
        return data

    def test_stored_prefix_must_match_crc(self):
        payload = b'shader'
        data = self.stored(payload, payload + b'\0' * 8)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            self.assertEqual(zip_member(archive, 'shader.bin'), payload)
        data[30 + len('shader.bin')] ^= 1
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            with self.assertRaisesRegex(SystemExit, 'LZ4 chunk'):
                zip_member(archive, 'shader.bin')

    def test_viper_chunk_fallback(self):
        import lz4.block

        payload = b'shader' * 64
        compressed = lz4.block.compress(payload, store_size=False)
        member = self.stored(payload, struct.pack('<I', len(compressed)) + compressed)
        with zipfile.ZipFile(io.BytesIO(member)) as archive:
            self.assertEqual(zip_member(archive, 'shader.bin'), payload)
