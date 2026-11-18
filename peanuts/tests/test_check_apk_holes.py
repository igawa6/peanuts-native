"""peanuts/scripts/check-apk-holes.py: an APK whose bytes all belong to an entry of its central directory passes; one packaged over an earlier file (old entries
left between the live ones, or before the central directory) fails. The zips are made here; no APK is needed."""
import importlib.util
import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'peanuts/scripts/check-apk-holes.py'
spec = importlib.util.spec_from_file_location('check_apk_holes', SCRIPT)
holes = importlib.util.module_from_spec(spec); spec.loader.exec_module(holes)


def make(extra=0, descriptor=False):
    b = io.BytesIO()
    with zipfile.ZipFile(b, 'w') as z:
        for i, (name, comp) in enumerate((('classes.dex', zipfile.ZIP_DEFLATED), ('lib/arm64-v8a/libnxhost.so', zipfile.ZIP_STORED), ('assets/pack/tools.zip', zipfile.ZIP_STORED))):
            zi = zipfile.ZipInfo(name); zi.compress_type = comp
            if extra and comp == zipfile.ZIP_STORED: zi.extra = struct.pack('<HH', 0xD935, extra) + bytes(extra)   # zipalign's padding lives in the extra field
            if descriptor:
                with z.open(zi, 'w') as f: f.write(os.urandom(40000 + i))   # a stream: its sizes come after the data (a data descriptor)
            else: z.writestr(zi, os.urandom(40000 + i))
    return b.getvalue()


def cd_of(d):
    e = d.rfind(b'PK\x05\x06'); n, size, off = struct.unpack_from('<HII', d, e + 10)
    return e, n, size, off


def insert(d, at, junk):
    """d with junk put in at offset at (between two entries, or before the central directory): the directory's offsets after it moved on"""
    e, n, size, off = cd_of(d)
    out = bytearray(d[:at] + junk + d[at:]); L = len(junk)
    p = off + L
    for _ in range(n):
        nl, xl, cl = struct.unpack_from('<HHH', out, p + 28); o, = struct.unpack_from('<I', out, p + 42)
        if o >= at: struct.pack_into('<I', out, p + 42, o + L)
        p += 46 + nl + xl + cl
    struct.pack_into('<I', out, e + L + 16, off + L)
    return bytes(out)


def second_entry(d):
    e, n, size, off = cd_of(d); p = off; offs = []
    for _ in range(n):
        nl, xl, cl = struct.unpack_from('<HHH', d, p + 28); offs.append(struct.unpack_from('<I', d, p + 42)[0]); p += 46 + nl + xl + cl
    return sorted(offs)[1]


class Holes(unittest.TestCase):
    def test_a_clean_zip_has_none(self):
        for d in (make(), make(extra=3000), make(descriptor=True)):
            gaps, sig, n = holes.holes(d)
            self.assertEqual((gaps, sig, n), ([], 0, 3))
            zipfile.ZipFile(io.BytesIO(d)).testzip()

    def test_a_dead_entry_between_two(self):
        d = make(); old = d[:second_entry(d)]   # the first entry's whole local record, as an earlier packaging left it
        h = insert(d, second_entry(d), old)
        self.assertIsNone(zipfile.ZipFile(io.BytesIO(h)).testzip(), 'still a zip every reader takes')
        gaps, _, _ = holes.holes(h)
        self.assertEqual(gaps, [(second_entry(d), len(old), True)])

    def test_dead_bytes_before_the_directory(self):
        d = make(); _, _, _, off = cd_of(d)
        self.assertEqual(holes.holes(insert(d, off, bytes(20000)))[0], [(off, 20000, False)])
        self.assertEqual(holes.holes(insert(d, off, bytes(100)))[0], [(off, 100, False)], 'a few bytes are reported and taken for padding')

    def test_the_signing_block_is_accounted_for(self):
        d = make(); _, _, _, off = cd_of(d)
        pairs = struct.pack('<QI', 4 + 5000, 0x7109871a) + bytes(5000)
        size = len(pairs) + 8 + 16
        block = struct.pack('<Q', size) + pairs + struct.pack('<Q', size) + b'APK Sig Block 42'
        gaps, sig, _ = holes.holes(insert(d, off, block))
        self.assertEqual((gaps, sig), ([], len(block)))

    def run_script(self, data):
        with tempfile.NamedTemporaryFile(suffix='.apk', delete=False) as f: f.write(data)
        try: return subprocess.run([sys.executable, str(SCRIPT), f.name], capture_output=True, text=True)
        finally: os.unlink(f.name)

    def test_the_command(self):
        d = make()
        r = self.run_script(d); self.assertEqual(r.returncode, 0, r.stdout); self.assertIn('no holes: 3 entries', r.stdout)
        r = self.run_script(insert(d, second_entry(d), d[:second_entry(d)])); self.assertEqual(r.returncode, 1); self.assertIn('[RED]', r.stdout); self.assertIn('local file header', r.stdout)
        r = self.run_script(insert(d, cd_of(d)[3], bytes(100))); self.assertEqual(r.returncode, 0, r.stdout); self.assertIn('100 bytes of padding', r.stdout)
        r = self.run_script(insert(d, cd_of(d)[3], bytes(70000))); self.assertEqual(r.returncode, 1)
        r = self.run_script(b'not a zip at all'); self.assertEqual(r.returncode, 1); self.assertIn('not a zip', r.stdout)


if __name__ == '__main__':
    unittest.main()
