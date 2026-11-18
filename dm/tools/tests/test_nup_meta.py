"""dm/tools/nup_meta.py: setting a package's icon and text in a .nup in place; the file still reads, its blobs are untouched, old records stay."""
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(REPO / 'dm/tools'), str(REPO / 'scripts')]
import nup
import nup_meta   # pyright: ignore[reportMissingImports]  # PEANUTS(upstream-candidate): scripts/ is added to sys.path above.

ICON = b'\xff\xd8\xff\xe0' + b'x' * 3000 + b'\xff\xd9'


class MetaTest(unittest.TestCase):
    def make(self, d):
        src = Path(d) / 'g'
        (src / 'base/exefs').mkdir(parents=True); (src / 'base/romfs').mkdir(parents=True)
        npdm = bytearray(0x100); npdm[:4] = b'META'; struct.pack_into('<II', npdm, 0x70, 0x80, 0x20); npdm[0x80:0x84] = b'ACI0'; struct.pack_into('<Q', npdm, 0x90, 0x040074A01BF12000)
        (src / 'base/exefs/main.npdm').write_bytes(npdm); (src / 'base/romfs/a.bin').write_bytes(b'hello' * 100)
        out = Path(d) / 't.nup'
        subprocess.run([sys.executable, str(REPO / 'dm/tools/nup_pack.py'), '--raw', str(src), str(out)], check=True, capture_output=True)
        return out

    def test_set_twice(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.make(d)
            nup_meta.set_meta(str(p), None, ICON, 'Title', None, None)
            nup_meta.set_meta(str(p), None, None, None, '1.2', 'Pub')   # keeps the icon and the title
            n = nup.open_nup(str(p))
            off = n.o_pkg
            moff, msize = nup.PKG.unpack_from(n.toc, off)[9:11]
            recs = dict(nup_meta.records(p.read_bytes()[moff:moff + msize]))
            self.assertEqual(recs[3], ICON); self.assertEqual(recs[5], b'Title'); self.assertEqual(recs[4], b'1.2'); self.assertEqual(recs[6], b'Pub')
            self.assertEqual(n.read(n.node('base/romfs/a.bin')), b'hello' * 100)

    def test_bad_icon_refused_file_unchanged(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.make(d); before = p.read_bytes()
            with self.assertRaises(nup.Error): nup_meta.set_meta(str(p), None, b'not an image', None, None, None)
            self.assertEqual(p.read_bytes(), before)


    def test_same_again_changes_nothing_and_unfinished_run_is_cut(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.make(d)
            self.assertTrue(nup_meta.set_meta(str(p), None, ICON, 'T', None, None))
            done = p.read_bytes()
            self.assertFalse(nup_meta.set_meta(str(p), None, ICON, 'T', None, None)); self.assertEqual(p.read_bytes(), done)
            with open(p, 'ab') as f: f.write(b'junk' * 100)   # a killed run's appended block
            self.assertFalse(nup_meta.set_meta(str(p), None, ICON, 'T', None, None)); self.assertEqual(p.read_bytes(), done)


if __name__ == '__main__':
    unittest.main()
