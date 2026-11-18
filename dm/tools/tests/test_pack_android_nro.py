"""scripts/pack_android.py's NROs (the modules a game loads at run time with nn::ro): collected from the dump by dm/tools/nro_collect.py into the work folder (reused while
its inputs are unchanged), rewritten with the modules, and listed in the pack's nro.tsv, which the host reads beside the modules (loader.c preload_nros). The phone's pack step
(--keep-code) checks that every NRO's rewrite finished, as it does for the modules."""
import errno
import hashlib
import os
import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('pack_android', REPO / 'scripts/pack_android.py')
assert spec and spec.loader
pack_android = importlib.util.module_from_spec(spec); spec.loader.exec_module(pack_android)


def nro(text=0x2000, tag=b'x'):
    d = bytearray(0x100); d[0x10:0x14] = b'NRO0'; struct.pack_into('<I', d, 0x18, len(d)); struct.pack_into('<II', d, 0x20, 0, text); d[0x80:0x81] = tag
    return bytes(d)


def nrr(hashes):
    d = bytearray(0x348); d[:4] = b'NRR0'; struct.pack_into('<II', d, 0x340, len(d), len(hashes))
    return bytes(d) + b''.join(hashes)


class Collect(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        t = Path(self.tmp.name)
        self.exe, self.rom, self.work = t / 'game/exefs', t / 'game/romfs', t / 'work'
        self.exe.mkdir(parents=True); self.rom.mkdir(); (self.exe / 'main').write_bytes(b'NSO0' + bytes(0x100))
        self.body = nro()
        (self.rom / 'mod.nro').write_bytes(self.body); (self.rom / 'game.nrr').write_bytes(nrr([hashlib.sha256(self.body).digest()]))

    def collect(self): return pack_android.collect_nros(self.exe, self.rom, self.work, in_process=True)

    def test_collected_once_then_reused(self):
        got = self.collect()
        self.assertEqual(list(got), ['nro0']); self.assertEqual(got['nro0'].read_bytes(), self.body)
        with mock.patch.object(pack_android, 'run_in_process', side_effect=AssertionError('collected again')): self.assertEqual(list(self.collect()), ['nro0'])

    def test_new_nrr_collects_again(self):
        self.collect()
        other = nro(tag=b'y'); (self.rom / 'two.nro').write_bytes(other)
        (self.rom / 'game.nrr').write_bytes(nrr([hashlib.sha256(self.body).digest(), hashlib.sha256(other).digest()]))
        self.assertEqual(sorted(self.collect()), ['nro0', 'nro1'])

    def test_no_nrr_no_nros(self):
        (self.rom / 'game.nrr').unlink()
        self.assertEqual(self.collect(), {})
        self.assertFalse((self.work / 'android-nro').exists())

    def test_no_folder_rename(self):
        """A folder renamed under /storage/emulated may be refused with EPERM by MediaProvider (issues #1, #296): the collection must not rename one"""
        real = os.replace
        def replace(src, dst, *a, **kw):
            if os.path.isdir(src): raise PermissionError(errno.EPERM, 'Operation not permitted', str(src), None, str(dst))
            return real(src, dst, *a, **kw)
        # PEANUTS(upstream-candidate): the replacement function already has the Path method's argument shape.
        with mock.patch.object(pack_android.os, 'replace', replace), mock.patch.object(pack_android.os, 'rename', replace), \
             mock.patch.object(pack_android.shutil, 'move', side_effect=AssertionError('folder moved')), \
             mock.patch.object(Path, 'rename', replace), mock.patch.object(Path, 'replace', replace):
            got = self.collect()
        self.assertEqual(list(got), ['nro0']); self.assertEqual(got['nro0'].read_bytes(), self.body)
        self.assertEqual(pack_android.collected_nros(self.work), self.work / 'android-nro')

    def test_unfinished_collection_is_redone(self):
        self.collect()
        (self.work / 'android-nro.key').unlink()   # stopped after nro_collect.py wrote its files, before the marker
        self.assertIsNone(pack_android.collected_nros(self.work), 'a collection without its key is not used')
        (self.work / 'android-nro' / 'stale.nro').write_bytes(b'old')
        (self.work / 'android-nro.partial').mkdir(); (self.work / 'android-nro.partial' / 'nro.tsv').write_text('# old\n')   # left by the earlier rename
        self.assertEqual(list(self.collect()), ['nro0'])
        self.assertFalse((self.work / 'android-nro' / 'stale.nro').exists()); self.assertFalse((self.work / 'android-nro.partial').exists())
        self.assertTrue((self.work / 'android-nro.key').is_file())

    def test_missing_nro_is_an_error(self):
        (self.rom / 'mod.nro').unlink()
        with self.assertRaises(pack_android.Error): self.collect()
        self.assertIsNone(pack_android.collected_nros(self.work), 'a failed collection is not used')


class Manifest(unittest.TestCase):
    def test_placed_and_checked(self):
        with tempfile.TemporaryDirectory() as d:
            coll, out = Path(d) / 'coll', Path(d) / 'pack'; coll.mkdir(); out.mkdir()
            (coll / 'nro.tsv').write_text('# name\tsha256\tsize\tnrr\twhere\nnro0\t' + 'ab' * 32 + '\t256\tgame.nrr\tloose mod.nro\nrefused\t' + 'cd' * 32 + '\t9\tgame.nrr\tloose eos.nro (EOS)\n')
            pack_android.place_nros(coll, out)
            self.assertEqual((out / 'nro.tsv').read_text(), (coll / 'nro.tsv').read_text())
            self.assertEqual(pack_android.pack_nros(out), ['nro0'], 'a refused row has no files')
            self.assertEqual(pack_android.unfinished_nros(out), ['nro0 (nro0.patched.bin and nro0.android.key missing)'])
            (out / 'nro0.patched.bin').write_bytes(b'x'); (out / 'nro0.android.key').write_text('k\n')
            self.assertEqual(pack_android.unfinished_nros(out), [])
            pack_android.place_nros(None, out)
            self.assertFalse((out / 'nro.tsv').exists(), 'a game without NROs leaves no manifest behind')

    def test_nro_text_size(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'nro0.nro'; f.write_bytes(nro(text=0x4321000))
            self.assertEqual(pack_android.nso_text_size(f), 0x4321000)


class Split(unittest.TestCase):
    """The shader split reads a CRA archive's file table from the game's NROs (shader_split.py, DM_NRO_DIR): the collected ones, when the work folder has them"""
    def split_env(self, with_nros):
        with tempfile.TemporaryDirectory() as d:
            work = Path(d) / 'work'; (work / 'android-nro').mkdir(parents=True)
            if with_nros: (work / 'android-nro' / 'nro.tsv').write_text('# name\n'); (work / 'android-nro.key').write_text('k\n')
            seen = {}
            def run(argv, env=None, **kw): seen.update(env or {}); return mock.Mock(returncode=1)
            a = mock.Mock(shader_python=None); g = mock.Mock(id='0400D4201C6EA000', dir=Path(d) / 'profile')
            with mock.patch.object(pack_android, 'shader_python', return_value='py'), mock.patch.object(pack_android.subprocess, 'run', run), \
                 mock.patch.dict(pack_android.os.environ, {}, clear=False), self.assertRaises(pack_android.Error):
                pack_android.os.environ.pop('DM_NRO_DIR', None)
                pack_android.split_shaders(a, g, Path(d) / 'v', work / 'android-shaders' / 'pkg', nros=pack_android.collected_nros(work))
            return seen

    def test_collected_nros_reach_the_split(self):
        self.assertEqual(Path(self.split_env(True)['DM_NRO_DIR']).name, 'android-nro')

    def test_no_nros_no_switch(self):
        self.assertNotIn('DM_NRO_DIR', self.split_env(False))


if __name__ == '__main__':
    unittest.main()
