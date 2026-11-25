"""PEANUTS: scripts/pack_android.py copies the shader library into a pack on a microSD card (#495). The card refuses to set a file's time (EPERM from os.utime,
as Android's FUSE daemon answers for a card's FAT file system), and shutil.copystat after the copy stopped the pack at its very last step, after the whole
build. The copy now keeps the time only where the storage takes it. No game files needed: the card's refusal is simulated."""
import argparse
import errno
import importlib
import os
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'scripts'))
pack_android = importlib.import_module('pack_android')

real_utime = os.utime


def card_utime(path, *a, **kw):
    """os.utime as a microSD card on Android answers it: EPERM, for any file under a folder named 'card'"""
    if 'card' in Path(path).parts: raise PermissionError(errno.EPERM, 'Operation not permitted', str(path))
    return real_utime(path, *a, **kw)


class CopyTime(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory(); self.root = Path(self.d.name)
        self.src = self.root / 'cache' / 'lib.bin'; self.src.parent.mkdir(parents=True)
        self.src.write_bytes(os.urandom(3 << 20))
        old = time.time() - 1000; real_utime(self.src, (old, old))

    def tearDown(self): self.d.cleanup()

    def test_the_copy_keeps_the_time_where_the_storage_takes_it(self):
        dst = self.root / 'internal' / 'lib.bin'; dst.parent.mkdir()
        pack_android.copy_counted(self.src, dst, 'copying')
        self.assertEqual(dst.read_bytes(), self.src.read_bytes())
        self.assertEqual(dst.stat().st_mtime_ns, self.src.stat().st_mtime_ns)

    def test_a_card_that_refuses_the_time_keeps_the_copy(self):   # the case of #495
        dst = self.root / 'card' / 'lib.bin'; dst.parent.mkdir()
        with patch.object(pack_android.os, 'utime', card_utime):
            pack_android.copy_counted(self.src, dst, 'copying')
        self.assertEqual(dst.read_bytes(), self.src.read_bytes())
        self.assertNotEqual(dst.stat().st_mtime_ns, self.src.stat().st_mtime_ns)   # without the time: the next pack copies it again

    def test_another_error_still_stops(self):
        dst = self.root / 'internal' / 'lib.bin'; dst.parent.mkdir()
        def broken(path, *a, **kw): raise OSError(errno.EIO, 'Input/output error', str(path))
        with patch.object(pack_android.os, 'utime', broken), self.assertRaises(OSError):
            pack_android.copy_counted(self.src, dst, 'copying')

    def test_pack_shaders_finishes_on_a_card(self):
        """The library and its stamp copied into a pack on a card whose storage refuses times: the pack is complete, and a second pack copies both again"""
        lib = self.root / 'cache' / 'lib' / 'v-test'; lib.mkdir(parents=True)
        (lib / pack_android.VK_LIB).write_bytes(os.urandom(1 << 20))
        pack_android.dmsh_chain.write_stamp(str(lib), 'test', 'x')
        out = self.root / 'card' / 'pack'
        args = argparse.Namespace(shader_cache=self.root / 'cache' / 'lib', shader_pkg=None, shader_python=None, in_process=False, J=1)
        g = argparse.Namespace(id='test', dir=self.root / 'profile')
        copied = []
        real_replace = os.replace
        def replace(a, b): copied.append(Path(b).name); return real_replace(a, b)
        with patch.multiple(pack_android, require_shaders=lambda g: None, vk_chain=lambda: 'test', library_current=lambda *a: True), \
             patch.object(pack_android.dmsh_chain, 'inputs', lambda g: 'x'), patch.object(pack_android.os, 'utime', card_utime), \
             patch.object(pack_android.os, 'replace', replace):
            for _ in range(2): pack_android.pack_shaders(args, g, self.root / 'game', out, self.root / 'v')
        dst = out / 'shaders' / 'v-test'
        self.assertEqual((dst / pack_android.VK_LIB).read_bytes(), (lib / pack_android.VK_LIB).read_bytes())
        self.assertEqual((dst / pack_android.dmsh_chain.STAMP).read_bytes(), (lib / pack_android.dmsh_chain.STAMP).read_bytes())
        self.assertEqual(sorted(copied), sorted([pack_android.VK_LIB, pack_android.dmsh_chain.STAMP] * 2))
        self.assertFalse(list(dst.glob('*.partial')))


if __name__ == '__main__':
    unittest.main()
