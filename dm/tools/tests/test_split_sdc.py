"""PEANUTS(upstream-candidate): synthetic Saber cache and padded ZIP tests."""
import contextlib
import io
import struct
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

from dm.tools.tests.test_split_gzip import ss
from dm.tools.tests.test_split_viper import member, vzip


def nvn_record(compute=False):
    ctl = bytearray(0x850)
    ctl[:4] = ss.CTL_MAGIC
    struct.pack_into('<I', ctl, 0x20, len(ctl))
    code = bytearray(0x100)
    struct.pack_into('<I', ctl, 0x704, len(code))
    if compute:
        struct.pack_into('<3I', ctl, 0x748, 16, 2, 1)
        code[:8] = b'\x01\x02\x03\x04\x05\x06\x07\x08'
    else:
        code[:4] = ss.GFX_MAGIC
        struct.pack_into('<I', code, 0x30, 0x461)
    padding = bytes(24)
    code_at = 32 + len(ctl) + len(padding)
    record = struct.pack('<4Q', 32, len(ctl) + len(padding), len(code), code_at)
    return record + ctl + padding + code, bytes(ctl), bytes(code)


def cache(blocks, trailing=b''):
    index, streams = bytearray(), bytearray()
    for i, entries in enumerate(blocks):
        packed = zlib.compress(b''.join(data for data, _ in entries))
        if i == len(blocks) - 1:
            packed += trailing
        streams.extend(packed)
        index.extend(struct.pack('<2I', len(packed), len(entries)))
        for key, (data, metadata) in enumerate(entries, 1):
            index.extend(struct.pack('<3I', key, len(data), len(metadata)) + metadata)
    return struct.pack('<4I', len(blocks), sum(map(len, blocks)), len(streams), 1) + index + streams


class SaberCache(unittest.TestCase):
    def test_stored_padding_and_expanding_viper_member(self):
        data = b'synthetic payload'
        padded = ('padded', data + bytes(7), len(data), zlib.crc32(data), 0)
        chained = member('chained', data)
        self.assertGreater(len(chained[1]), len(data))
        self.assertNotEqual(zlib.crc32(chained[1][:len(data)]), chained[3])
        with zipfile.ZipFile(io.BytesIO(vzip([padded, chained]))) as archive:
            self.assertEqual(ss.zip_member(archive, 'padded'), data)
            self.assertEqual(ss.zip_member(archive, 'chained'), data)

    def test_indexed_graphics_and_headerless_compute(self):
        gfx, vctl, vcode = nvn_record()
        compute, cctl, ccode = nvn_record(compute=True)
        data = cache([[(b'source.vsh', b'\x01\x02'), (gfx, b'')], [(compute, b'')]])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'no-extension'
            path.write_bytes(data)
            _, keys, _, extra = ss.scan_file((str(path), path.name))
        self.assertEqual(extra, {})
        self.assertEqual({key: val[1:4] for key, val in keys.items()}, {
            ss.skey(vctl, vcode): ('vs', vctl, vcode),
            ss.skey(cctl, ccode): ('cs', cctl, ccode),
        })

    def test_corrupt_cache_is_skipped_without_losing_other_zip_members(self):
        gfx, ctl, code = nvn_record()
        compute, _, _ = nvn_record(compute=True)
        blocks = [[(compute, b'')], [(gfx, b'')]]
        bad_checksum = bytearray(cache(blocks))
        bad_checksum[-1] ^= 1
        bad_index = bytearray(cache(blocks))
        struct.pack_into('<I', bad_index, 28, len(compute) + 1)
        bad_total = bytearray(cache(blocks))
        struct.pack_into('<I', bad_total, 8, struct.unpack_from('<I', bad_total, 8)[0] + 1)
        bad_stream_header = bytearray(cache(blocks))
        bad_stream_header[56] ^= 1  # after the 16-byte header and two 20-byte index blocks
        valid = cache([[(gfx, b'')]])
        for corrupt in (bad_checksum, bad_index, bad_total, bad_stream_header, cache(blocks, trailing=b'extra')):
            with self.subTest(corrupt=corrupt[-8:]), tempfile.TemporaryDirectory() as tmp:
                binary = vzip([
                    member('bad', corrupt, chain=False),
                    ('good', valid + bytes(5), len(valid), zlib.crc32(valid), 0),
                ])
                path = Path(tmp) / 'archive.pak'
                path.write_bytes(binary)
                jobs, _ = ss.zip_jobs([(str(path), path.name)], parts=1)
                with contextlib.redirect_stdout(io.StringIO()) as log:
                    _, keys, _, extra = ss.scan_file(jobs[0])
                self.assertEqual(extra, {})
                self.assertEqual(list(keys), [ss.skey(ctl, code)])
                self.assertIn('[RED] /archive.pak/bad: SDC block', log.getvalue())
                self.assertIn('cache skipped', log.getvalue())


if __name__ == '__main__':
    unittest.main()
