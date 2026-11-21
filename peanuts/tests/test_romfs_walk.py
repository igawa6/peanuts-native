"""dm/tools/romfs.py walk over a .nup by its nodes (issue #120): the same directories, names and order as the walk it replaces (an is_dir stat of each entry's
whole path, which the .nup resolved from its root each time), top down and bottom up, a caller's pruning of dirs kept, and the same tree as os.walk of the folder
the .nup was packed from. The .nup is made with dm/tools/nup_pack.py (raw). No game files."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'dm/tools'))
import romfs   # noqa: E402


def old_walk(top, topdown=True, onerror=None):
    """The walk before: scandir, then each entry's is_dir (a stat of its path); its top resolved first, outside onerror"""
    romfs._resolve(top)
    def visit(path):
        dirs, files = [], []
        try:
            with romfs.scandir(path) as entries:
                for e in entries: (dirs if e.is_dir() else files).append(e.name)
        except OSError as error:
            if onerror: onerror(error)
            return
        if topdown: yield str(path), dirs, files
        for name in dirs: yield from visit(os.path.join(path, name))
        if not topdown: yield str(path), dirs, files
    yield from visit(top)


class Walk(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='pnwalk_'))
        cls.rom = cls.tmp / 'dump' / 'base' / 'romfs'
        for rel in ('a.bin', 'B/b1.bin', 'B/b2.bin', 'B/C/c.bin', 'B/C/D/d.bin', 'E/e.bin', 'E/F/f.bin', 'Z/z.bin'):
            p = cls.rom / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(rel.encode())
        (cls.rom / 'Empty').mkdir()
        exefs = cls.tmp / 'dump' / 'base' / 'exefs'; exefs.mkdir(parents=True)
        (exefs / 'main').write_bytes(b'NSO0' + bytes(0xfc))
        cls.nup = cls.tmp / 'game.nup'
        r = subprocess.run([sys.executable, REPO / 'dm/tools/nup_pack.py', cls.tmp / 'dump', cls.nup, '--raw'], capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr
        cls.top = f'{cls.nup}:base/romfs'

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_the_same_as_the_walk_before(self):
        for topdown in (True, False):
            self.assertEqual(list(romfs.walk(self.top, topdown=topdown)), list(old_walk(self.top, topdown=topdown)))

    def test_the_tree_of_the_folder_packed(self):
        def rel(rows, top): return sorted((os.path.relpath(p, top).replace('\\', '/'), sorted(d), sorted(f)) for p, d, f in rows)
        self.assertEqual(rel(romfs.walk(self.top), self.top), rel(os.walk(self.rom), self.rom))

    def test_a_pruned_dir_is_not_walked(self):
        seen = []
        for path, dirs, files in romfs.walk(self.top):
            if 'B' in dirs: dirs.remove('B')
            seen += [os.path.join(path, f).replace('\\', '/') for f in files]
        self.assertFalse(any('/B/' in p for p in seen))
        self.assertTrue(any(p.endswith('/E/F/f.bin') for p in seen))

    def test_names_a_caller_adds_are_walked_as_before(self):
        # os.walk walks a name a caller puts into dirs, and the walk before found each by its whole path: a nested one is walked, a missing one or a file's
        # name goes to onerror. The walk by nodes skipped them (found in review)
        def rows(walk):
            out, errors = [], []
            for path, dirs, files in walk(self.top, onerror=lambda e: errors.append(type(e).__name__)):
                if path == self.top: dirs += ['B/C', 'nothing', 'a.bin']
                out.append((path.replace('\\', '/'), list(dirs), files))
            return out, errors
        new, old = rows(romfs.walk), rows(old_walk)
        self.assertEqual(new, old)
        self.assertTrue(any(p.endswith('B/C/D') for p, _, _ in new[0]))   # the nested one, walked
        self.assertEqual(len(new[1]), 2)   # the missing one and the file's name

    def test_a_missing_top_raises_as_before(self):   # _resolve of the top raised before the walk began, and still does
        with self.assertRaises(FileNotFoundError): list(old_walk(f'{self.nup}:base/romfs/nothing'))
        with self.assertRaises(FileNotFoundError): list(romfs.walk(f'{self.nup}:base/romfs/nothing'))


if __name__ == '__main__':
    unittest.main()
