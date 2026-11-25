"""PEANUTS: scripts/pack_android.py takes a cached shader library only when it was built from the split package it would be built from now (#670). The library's
own stamp names the profile's inputs only, so a package that changed under the same profile (shaders captured from a run added to it) left a library that was
called current and packed without the new shaders. No game files needed."""
import argparse
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


def write(p, data):
    p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)


class PackageStamp(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory(); root = Path(self.d.name)
        self.lib, self.pkg = root / 'cache' / 'v-test', root / 'pkg'
        write(self.pkg / 'pkg' / 'a.bin', b'shader a'); write(self.pkg / 'shader_map.tsv', b'a\n')
        old = time.time() - 100
        for p in self.pkg.rglob('*'): os.utime(p, (old, old))
        write(self.lib / pack_android.VK_LIB, b'library')

    def tearDown(self): self.d.cleanup()

    def stamp(self): pack_android.write_lf(pack_android.package_stamp(self.lib), pack_android.package_digest(self.pkg) + '\n')

    def test_no_package_folder_leaves_the_library_current(self):
        self.assertTrue(pack_android.package_current(self.lib, self.pkg.with_name('none')))

    def test_the_stamped_package_is_current(self):
        self.stamp()
        self.assertTrue(pack_android.package_current(self.lib, self.pkg))

    def test_a_shader_added_to_the_package_is_not(self):   # the case of #670: new captures in the package, the profile the same
        self.stamp()
        write(self.pkg / 'pkg' / 'b.bin', b'shader b')
        self.assertFalse(pack_android.package_current(self.lib, self.pkg))

    def test_a_changed_file_of_the_same_size_is_not(self):
        self.stamp()
        write(self.pkg / 'pkg' / 'a.bin', b'shader A')
        self.assertFalse(pack_android.package_current(self.lib, self.pkg))

    def test_the_same_package_in_another_folder_is_current(self):   # the digest is of the paths in the package, not of where it is
        self.stamp()
        moved = self.pkg.with_name('pkg2'); self.pkg.rename(moved)
        self.assertTrue(pack_android.package_current(self.lib, moved))

    def test_an_unstamped_library_older_than_its_package_is_current_and_stamped(self):
        self.assertTrue(pack_android.package_current(self.lib, self.pkg))
        self.assertEqual(pack_android.package_stamp(self.lib).read_text(encoding='utf-8').strip(), pack_android.package_digest(self.pkg))
        write(self.pkg / 'pkg' / 'b.bin', b'shader b')
        self.assertFalse(pack_android.package_current(self.lib, self.pkg))

    def test_an_unstamped_library_with_a_newer_package_file_is_not(self):
        write(self.pkg / 'pkg' / 'b.bin', b'shader b')   # written now, after the library
        self.assertFalse(pack_android.package_current(self.lib, self.pkg))
        self.assertFalse(pack_android.package_stamp(self.lib).exists())

    def pack(self, built):
        """pack_shaders with a library whose profile inputs are current; → whether it started the library build"""
        root = Path(self.d.name); started = []
        args = argparse.Namespace(shader_cache=root / 'cache', shader_pkg=self.pkg, shader_python=None, in_process=False, J=1)
        g = argparse.Namespace(id='test', dir=root / 'profile')
        def run(cmd, env):
            started.append(cmd); return argparse.Namespace(returncode=1)
        with patch.multiple(pack_android, require_shaders=lambda g: None, vk_chain=lambda: 'test', library_current=lambda *a: True,
                            lib_stamp=lambda l: {'game': 'test', 'inputs': 'x'}, shader_python=lambda p: 'python', child_env=lambda **kw: kw,
                            peanuts_env=lambda s: {}, selected_keys=lambda p: None, absent_optional=lambda *a: set()), \
             patch.object(pack_android.dmsh_chain, 'inputs', lambda g: 'x'), patch.object(pack_android.subprocess, 'run', side_effect=run):
            if built:
                with self.assertRaisesRegex(pack_android.Error, 'exit code 1'): pack_android.pack_shaders(args, g, root / 'game', root / 'out', root / 'v')
            else: pack_android.pack_shaders(args, g, root / 'game', root / 'out', root / 'v')
        return bool(started)

    def test_pack_shaders_takes_the_library_of_this_package(self):
        self.stamp()
        self.assertFalse(self.pack(built=False))
        self.assertTrue((Path(self.d.name) / 'out' / 'shaders' / 'v-test' / pack_android.VK_LIB).is_file())

    def test_pack_shaders_builds_again_after_the_package_changed(self):
        self.stamp()
        write(self.pkg / 'pkg' / 'b.bin', b'shader b')
        self.assertTrue(self.pack(built=True))


if __name__ == '__main__':
    unittest.main()
