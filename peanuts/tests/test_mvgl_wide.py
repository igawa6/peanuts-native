"""Media.Vision's 64-bit MVGL layout (Digimon Story Time Stranger): mvgl.py reads its tables and LZ4 entries beside the 32-bit layout, and shader_split.py
finds the NXVS / NXFS containers of its 'shad' entries (mvgl_jobs / mvgl_shaders). Synthetic bytes only."""
import contextlib
import io
import os
import struct
import sys
import tempfile
import unittest
from pathlib import Path

import lz4.block

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'dm/tools/fuzz'))
import fuzz_shader_split as fz
ss = fz.ss
import mvgl


def nxsh(magic, stage):
    """One NX?S container: a 0x40-byte header, the control section, then the code area"""
    ctl, code = stage
    h = bytearray(0x40); h[:4] = magic
    struct.pack_into('<IIIIII', h, 4, 0x40 + len(ctl) + len(code), 0, 0x40, len(ctl), 0x40 + len(ctl), len(code))
    return bytes(h) + ctl + code


def wide(files, de=16):   # PEANUTS: de=24, The Hundred Line's data entries (u64 offset, u64 length, u64 stored length)
    """A 64-bit MVGL archive of {'name.ext': (bytes, compress)}; the tree is a flat list (the reader walks every node, as the Patricia tree's order does not matter to it)"""
    names = sorted(files)
    nt = nn = len(names) + 1; nd = len(names)
    tree, nm, data, blob = bytearray(), bytearray(), bytearray(), bytearray()
    tree += struct.pack('<IIII', 0xffffffff, 0xffffffff, 0, 0); nm += bytes(0x80)
    for i, n in enumerate(names):
        raw, comp = files[n]
        stored = lz4.block.compress(raw, store_size=False) if comp else raw
        stem, ext = n.rsplit('.', 1)
        tree += struct.pack('<IIII', i, i, 0, 0)
        nm += ext.encode().ljust(4, b'\0') + stem.encode().ljust(0x7c, b'\0')
        data += struct.pack('<QII' if de == 16 else '<QQQ', len(blob), len(raw), len(stored)); blob += stored   # PEANUTS: de
    start = 0x20 + nt * 16 + nn * 0x80 + nd * de   # PEANUTS: de
    return struct.pack('<4sIIIQQ', b'MDB1', nt, nn, nd, start, start + len(blob)) + tree + nm + data + blob


class WideMvgl(unittest.TestCase):
    def setUp(self):
        self.vs, self.ps = nxsh(b'NXVS', fz.VS), nxsh(b'NXFS', fz.PS)
        self.arc = wide({'shaders\\a_vp.shad': (self.vs, True), 'shaders\\a_fp.shad': (self.ps, False), 'images\\b.img': (b'not a shader' * 40, True)})

    def write(self, t, data, name='app_0.nx64.mvgl'):
        p = os.path.join(t, name)
        with open(p, 'wb') as f: f.write(data)
        return p

    def test_reader(self):
        with tempfile.TemporaryDirectory() as t:
            a = mvgl.Archive(self.write(t, self.arc))
            self.assertTrue(a.wide)
            self.assertEqual(sorted(a.files), ['images\\b.img', 'shaders\\a_fp.shad', 'shaders\\a_vp.shad'])
            self.assertEqual(a.read('shaders\\a_vp.shad'), self.vs)   # an LZ4 entry
            self.assertEqual(a.read('shaders\\a_fp.shad'), self.ps)   # a stored entry
            self.assertEqual(a.read('images\\b.img'), b'not a shader' * 40)

    # PEANUTS begin: The Hundred Line's 24-byte data entries (mvgl.data_entry)
    def test_reader_24(self):
        arc = wide({'shaders\\a_vp.shad': (self.vs, True), 'shaders\\a_fp.shad': (self.ps, False)}, de=24)
        self.assertTrue(mvgl.wide(arc[:0x20])); self.assertEqual(mvgl.data_entry(arc[:0x20]), 24); self.assertEqual(mvgl.data_entry(self.arc[:0x20]), 16)
        with tempfile.TemporaryDirectory() as t:
            a = mvgl.Archive(self.write(t, arc))
            try:
                self.assertEqual(a.read('shaders\\a_vp.shad'), self.vs)
                self.assertEqual(a.read('shaders\\a_fp.shad'), self.ps)
            finally: a.f.close()
    # PEANUTS end

    def test_narrow_layout_is_not_wide(self):
        """The 32-bit layout (Cyber Sleuth): u16 counts, u32 start and size; its tables do not end at a 64-bit start"""
        h = struct.pack('<4sHHIII', b'MDB1', 2, 2, 1, 0x14 + 16 + 0x80 + 12, 0x1000) + bytes(12)
        self.assertFalse(mvgl.wide(h[:0x20]))

    def test_split_jobs_and_shaders(self):
        with tempfile.TemporaryDirectory() as t, contextlib.redirect_stdout(io.StringIO()):
            p = self.write(t, self.arc)
            jobs, arcs = ss.mvgl_jobs([(p, 'app_0.nx64.mvgl')])
            self.assertEqual(arcs, {p})
            names = [n for _, _, (tag, ns) in jobs for n in ns]
            self.assertEqual(sorted(names), ['shaders\\a_fp.shad', 'shaders\\a_vp.shad'])   # only the 'shad' entries
            out = ss.Shaders()
            for path, rel, (tag, ns) in jobs: ss.mvgl_shaders(path, rel, ns, out)
            stages = sorted(v[1] for v in ss.uniq_keys(out).values())
            self.assertEqual(stages, ['ps', 'vs'])

    def test_corrupt_archive_is_left_out(self):
        """An archive whose tables are cut short: that archive is left out with a red line (and kept out of the loose scan), the other one still gives its jobs"""
        with tempfile.TemporaryDirectory() as t, contextlib.redirect_stdout(io.StringIO()) as log:
            good, cut = self.write(t, self.arc), self.write(t, self.arc[:0x40], 'app_1.nx64.mvgl')
            jobs, arcs = ss.mvgl_jobs([(cut, 'app_1.nx64.mvgl'), (good, 'app_0.nx64.mvgl')])
            self.assertEqual(arcs, {good, cut})
            self.assertEqual({path for path, _, _ in jobs}, {good})
            self.assertIn('[RED] /app_1.nx64.mvgl: the MVGL archive cannot be read', log.getvalue())

    def test_containers_in_main(self):
        """main keeps NX?S containers too, with the control section's assembly text between it and the code area (control length > 0x880): raw_shaders reads them by their
        headers and leaves their control sections out of its own heuristic (no '[RED] … no code area' line)"""
        ctl, code = fz.VS
        text = b'!!NVvp5.0\nEND\n\0\0'
        c = bytearray(0x40); c[:4] = b'NXVS'
        struct.pack_into('<IIIIII', c, 4, 0x40 + len(ctl) + len(text) + len(code), 0, 0x40, len(ctl) + len(text), 0x40 + len(ctl) + len(text), len(code))
        cont = bytes(c) + ctl + text + code
        img = bytes(0x100) + cont + bytes(0x40) + nxsh(b'NXFS', fz.PS) + bytes(0x80)
        with contextlib.redirect_stdout(io.StringIO()) as log:
            got = ss.raw_shaders(img, '/main')
        self.assertEqual(sorted(s[1] for s in got), ['ps', 'vs'])
        self.assertEqual(got[0][0], '/main#NXSH@0x100')
        self.assertNotIn('no code area', log.getvalue())

    def test_bad_container_is_red(self):
        bad = bytearray(self.vs); struct.pack_into('<I', bad, 4, len(bad) + 4)   # the header's length does not match the entry
        with self.assertRaises(SystemExit) as e: ss.nxsh_shader(bytes(bad), '/t/x.shad')
        self.assertIn('NX?S header says', str(e.exception))


if __name__ == '__main__':
    unittest.main()
