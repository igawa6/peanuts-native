#!/usr/bin/env python3
"""A damaged or incomplete game file says so, and Retry is not offered for it (GitHub issue #561: Pokémon Legends: Z-A's data.trpfs, its index garbled by a
damaged update, stopped the shader split with a raw struct.error and a Retry that failed the same way every time).

- The split (dm/gfx/tools/shader_split.py): the FlatBuffers readers of Trinity's data.trpfd / data.trpfs check every offset against the file. A synthetic pair
  written here from the format trpfs_jobs documents is read as before; an index that points past the end of either file ends the split with one [RED] line
  that says the game file is damaged and names it, and a pack table that points outside the file does the same for trinity_shaders.
- The app: the source checks say the importer's messages carry the words dev.peanuts.build.Compressed.failure looks for (its card words, beside the cut-off
  file's of #697, are tested in test_compressed.py), and that the build and the split's failure end as FILE_DAMAGED (the game's sheet, no Retry).
The importer's check itself is tested in C (dm/peanuts/import/tests/verify_test.c); PrepareText's words in PrepareTextJvmTest. No game data."""
import os
import re
import struct
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'dm' / 'gfx' / 'tools'))
# shader_split takes DM_GAME at its import (its GAME profile); the Trinity readers need nothing of it: Pokémon Legends: Z-A's own profile, the game of #561
_SAVED = {k: os.environ.pop(k, None) for k in ('DM_GAME', 'DM_GAMES_DIR')}
os.environ['DM_GAME'] = '0100F43008C44000'
import shader_split as ss
for k, v in _SAVED.items():
    if v is None: os.environ.pop(k, None)
    else: os.environ[k] = v

JAVA = REPO / 'android/app/src/main/java'


class FB:
    """A FlatBuffers writer just big enough for the Trinity files: tables of 8-byte slots (an inline u64, or a uoffset filled by link), u64 vectors, offset
    vectors, strings. Objects are written after what points at them, as uoffsets are unsigned"""
    def __init__(self, head=b''): self.b = bytearray(head)
    def put(self, x): p = len(self.b); self.b += x; return p
    def table(self, n):
        vt = self.put(struct.pack('<HH', 4 + 2 * n, 4 + 8 * n) + b''.join(struct.pack('<H', 4 + 8 * i) for i in range(n)))
        t = len(self.b); self.put(struct.pack('<i', t - vt) + b'\0' * 8 * n)
        return t, [t + 4 + 8 * i for i in range(n)]
    def u64(self, at, v): struct.pack_into('<Q', self.b, at, v)
    def link(self, at, target): struct.pack_into('<I', self.b, at, target - at)
    def vec_q(self, vals): return self.put(struct.pack('<I', len(vals)) + b''.join(struct.pack('<Q', v) for v in vals))
    def vec_r(self, n): p = self.put(struct.pack('<I', n) + b'\0' * 4 * n); return p, [p + 4 + 4 * k for k in range(n)]
    def string(self, s): e = s.encode(); return self.put(struct.pack('<I', len(e)) + e + b'\0')


PACKS = [('arc/a.trpak', 0x3000, [0x1111, 0x3333]), ('arc/b.trpak', 0x5000, [0x2222])]


def descriptor(packs):
    """data.trpfd: the root table {0 file hashes [u64], 1 pack names [string], 2 file infos [{0 pack index}] (in file hash order), 3 pack infos [{0 size, 1 file count}]}"""
    f = FB(b'\0' * 4)
    root, fl = f.table(4); f.link(0, root)
    order = sorted((h, i) for i, (_, _, hs) in enumerate(packs) for h in hs)
    f.link(fl[0], f.vec_q([h for h, _ in order]))
    v, refs = f.vec_r(len(packs)); f.link(fl[1], v)
    for r, (name, _, _) in zip(refs, packs): f.link(r, f.string(name))
    v, refs = f.vec_r(len(order)); f.link(fl[2], v)
    for r, (_, i) in zip(refs, order): t, tf = f.table(1); f.u64(tf[0], i); f.link(r, t)
    v, refs = f.vec_r(len(packs)); f.link(fl[3], v)
    for r, (_, size, hs) in zip(refs, packs): t, tf = f.table(2); f.u64(tf[0], size); f.u64(tf[1], len(hs)); f.link(r, t)
    return bytes(f.b)


def filesystem(packs, data=None):
    """data.trpfs: 'ONEPACK\\0', the u64 place of the root's uoffset, the root {0 pack name hashes [u64], 1 pack offsets [u64]}; each pack's uoffset at its
    offset, the pack {0 file hashes [u64], 1 files [{1 compression, 3 length, 4 data [u8]}]}. data: one file's (stored length, bytes) for the first pack.
    → (the bytes, the packs' offsets, where the root's pack offsets vector lies)"""
    f = FB(ss.ONEPACK + b'\0' * 8)
    offs = []
    for k, (_, _, hs) in enumerate(packs):
        at = f.put(b'\0' * 4); t, tf = f.table(2); f.link(at, t)
        f.link(tf[0], f.vec_q(hs))
        v, refs = f.vec_r(1 if data and not k else 0); f.link(tf[1], v)
        if data and not k:
            ft, ff = f.table(5); f.u64(ff[3], data[0]); f.link(refs[0], ft)
            f.link(ff[4], f.put(struct.pack('<I', len(data[1])) + data[1]))
        offs.append(at)
    x = f.put(b'\0' * 4); struct.pack_into('<Q', f.b, 8, x)
    root, rf = f.table(2); f.link(x, root)
    f.link(rf[0], f.vec_q([0xAA00 + k for k in range(len(packs))]))
    vec = f.vec_q(offs); f.link(rf[1], vec)
    return bytes(f.b), offs, vec


class Trinity(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='trpfs_')
        self.fs = Path(self.tmp.name) / 'data.trpfs'
        self.fd = Path(self.tmp.name) / 'data.trpfd'
        self.fd.write_bytes(descriptor(PACKS))

    def tearDown(self): self.tmp.cleanup()

    def jobs(self): return ss.trpfs_jobs([(str(self.fs), 'arc/data.trpfs')])

    def stops(self, *words):
        with self.assertRaises(SystemExit) as e: self.jobs()
        said = str(e.exception.code)
        for w in words: self.assertIn(w, said)
        return said

    def test_good_files_read(self):
        b, offs, _ = filesystem(PACKS); self.fs.write_bytes(b)
        jobs, taken = self.jobs()
        got = sorted(p for _, _, ps in jobs for p in ps)
        self.assertEqual(got, sorted(zip(offs, ['arc/a.trpak', 'arc/b.trpak'])))
        self.assertEqual(taken, {str(self.fs), str(self.fd)})

    def test_root_past_the_end(self):
        b, _, _ = filesystem(PACKS); b = bytearray(b); struct.pack_into('<Q', b, 8, len(b) + 0x1000); self.fs.write_bytes(bytes(b))
        said = self.stops('[RED] /arc/data.trpfs: the game file is damaged: its index points outside it', f'lie outside the {len(b):#x} bytes', 'import it again')
        self.assertNotIn('struct.error', said)

    def test_garbled_root_as_in_561(self):
        # #561's index: the root's offset inside the file, the table it names garbled, so its vtable lands far past the end (8,725,344,218 of 4,753,821,072)
        b, _, _ = filesystem(PACKS); b = bytearray(b); x = struct.unpack_from('<Q', b, 8)[0]; root = x + struct.unpack_from('<I', b, x)[0]
        struct.pack_into('<i', b, root, -0x7FFF0000); self.fs.write_bytes(bytes(b))
        self.stops('[RED] /arc/data.trpfs: the game file is damaged')

    def test_pack_offset_past_the_end(self):
        b, _, vec = filesystem(PACKS); b = bytearray(b); struct.pack_into('<Q', b, vec + 4 + 8, len(b) + 4); self.fs.write_bytes(bytes(b))
        self.stops('[RED] /arc/data.trpfs: the game file is damaged')

    def test_descriptor_past_the_end(self):
        b, _, _ = filesystem(PACKS); self.fs.write_bytes(b)
        d = bytearray(self.fd.read_bytes()); struct.pack_into('<I', d, 0, len(d) + 64); self.fd.write_bytes(bytes(d))
        self.stops('[RED] /arc/data.trpfd: the game file is damaged')

    def test_descriptor_cut(self):
        b, _, _ = filesystem(PACKS); self.fs.write_bytes(b)
        self.fd.write_bytes(self.fd.read_bytes()[:len(self.fd.read_bytes()) * 2 // 3])
        self.stops('[RED] /arc/data.trpfd: the game file is damaged')

    def test_pack_table_outside_the_file(self):
        b, offs, _ = filesystem(PACKS, data=(16, b'x' * 16)); b = bytearray(b)
        st, n = ss.fb_vec(b, ss.fb_root(b, offs[0]), 1); t = ss.fb_refs(b, ss.fb_root(b, offs[0]), 1)[0]; p, _ = ss.fb_vec(b, t, 4)
        struct.pack_into('<I', b, p - 4, 0x7FFFFFF0)   # the file's data vector claims more bytes than the file has
        self.fs.write_bytes(bytes(b))
        with self.assertRaises(ss.FbOutside): ss.trinity_shaders(str(self.fs), 'arc/data.trpfs', [(offs[0], 'arc/a.trpak')], [])
        with self.assertRaises(SystemExit) as e: ss.damaged('/arc/data.trpfs', ss.FbOutside(p, 0x7FFFFFF0, len(b)))
        self.assertIn('/arc/data.trpfs: the game file is damaged', str(e.exception.code))


class Wiring(unittest.TestCase):
    def src(self, rel): return (REPO / rel).read_text(encoding='utf-8')

    def test_the_import_and_the_split_end_as_file_damaged(self):
        bs = self.src('android/app/src/main/java/dev/peanuts/build/BuildService.java')
        self.assertRegex(bs, r'damaged = Compressed\.failure\(why, name, !deleted\);(?:[^\n]*\n){1,6}?\s*if \(damaged != null\) throw new BuildListener\.Failure\([^;]*BuildListener\.FILE_DAMAGED\);')
        self.assertRegex(self.src('android/app/src/main/java/dev/peanuts/build/Prepare.java'),
                         r'if \(dev\.nxnative\.android\.PrepareText\.CAUSE_DAMAGED\.equals\(cause\)\) throw new BuildListener\.Failure\(err, BuildListener\.FILE_DAMAGED\);')
        pt = self.src('android/app/src/main/java/dev/nxnative/android/PrepareText.java')
        phrase = re.search(r'String DAMAGED = "([^"]+)"', pt).group(1)
        self.assertIn(phrase.strip(': '), self.src('dm/gfx/tools/shader_split.py'))
        self.assertRegex(pt, r'if \(red\.contains\(DAMAGED\)\)\s*\n\s*return new Failure\([^;]*FIX_NONE, CAUSE_DAMAGED')

    def test_the_importers_words(self):   # every message of the check carries the words Compressed.failure looks for, after "<file>: <role>"
        mark = re.search(r'String DAMAGED = "([^"]+)"', self.src('android/app/src/main/java/dev/peanuts/build/Compressed.java')).group(1)
        v = self.src('dm/peanuts/import/nx_verify.c'); n = self.src('dm/peanuts/import/nximport.c')
        self.assertIn(f'nx_err("%s{mark}%s", what, why)', v)
        self.assertGreaterEqual(n.count(f'"%s{mark}%s is %llu bytes'), 2)
        for role in ('"%s: the base game"', '"%s: the update v%u"', '"%s: the DLC %016llx"'): self.assertIn(role, n)


if __name__ == '__main__':
    unittest.main()
