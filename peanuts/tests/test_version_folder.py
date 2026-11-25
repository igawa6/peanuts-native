"""One version's folder (GitHub issue #370): a game extracted on a PC, exefs/ and romfs/ at the top of its folder, updated to 1.9.0. The library lists it, but
upstream's Prepare starts only from base/ or updates/<version>/ and said "Pick the dump folder above it, the one with base/", and there is none.
dev.peanuts.build.VersionFolder says where the files go instead; it runs here on the JVM (javac/java only, the pattern of test_compressed.py) over made-up
folders with empty files, no game data. The source checks say that Prepare uses it for that refusal only, and that upstream still refuses such a folder."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BUILD = REPO / 'android/app/src/main/java/dev/peanuts/build'
UP = REPO / 'android/app/src/main/java/dev/nxnative/android'

RUNNER = r'''package dev.peanuts.build;
import java.io.File;
public final class VersionFolderTest {
    public static void main(String[] a) {
        for (String p : a) System.out.println(new File(p).getName() + "\t" + VersionFolder.is(new File(p)) + "\t" + VersionFolder.why(new File(p)));
    }
}
'''

FOLDERS = {
    'extracted': ['exefs/main', 'exefs/main.npdm', 'exefs/sdk', 'romfs/Pack/Bootup.pack'],   # the issue's layout
    'in base': ['base/exefs/main', 'base/exefs/main.npdm', 'base/romfs/data.bin'],
    'update only': ['updates/1114112/exefs/main', 'updates/1114112/romfs/data.bin'],
    'base beside': ['base/exefs/main', 'exefs/main', 'romfs/data.bin'],
    'no main': ['exefs/main.npdm', 'romfs/data.bin'],
}


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Words(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='version_folder_')
        base = Path(cls.tmp.name)
        (base / 'classes').mkdir()
        runner = base / 'VersionFolderTest.java'
        runner.write_text(RUNNER, encoding='utf-8')
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(base / 'classes'), str(BUILD / 'VersionFolder.java'), str(runner)], check=True)
        paths = []
        for name, files in FOLDERS.items():
            for f in files:
                (base / name / f).parent.mkdir(parents=True, exist_ok=True)
                (base / name / f).write_bytes(b'')
            paths.append(str(base / name))
        (base / 'a file.nsp').write_bytes(b'')
        paths.append(str(base / 'a file.nsp'))
        r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', str(base / 'classes'), 'dev.peanuts.build.VersionFolderTest', *paths],
                           capture_output=True, text=True, encoding='utf-8', check=True)
        cls.out = {l.split('\t')[0]: l.split('\t')[1:] for l in r.stdout.splitlines()}

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_only_one_versions_folder(self):
        self.assertEqual({n: o[0] for n, o in self.out.items()},
                         {'extracted': 'true', 'in base': 'false', 'update only': 'false', 'base beside': 'false', 'no main': 'false', 'a file.nsp': 'false'})
        for n, o in self.out.items():
            if n != 'extracted':
                self.assertEqual(o[1], 'null', n)

    def test_where_the_files_go(self):
        why = self.out['extracted'][1]
        self.assertEqual(why, 'extracted has one version\'s exefs and romfs folders at its top, and Build looks for them in a folder named base. '
                              'Make a folder named base in extracted, move exefs and romfs into it, then Rescan and build again.')
        for ch in ('—', '–', '·', '!'):   # UI-RULES.md: no dashes, no middle dot, no exclamation
            self.assertNotIn(ch, why)


class Source(unittest.TestCase):
    def test_prepare_says_it_for_that_refusal(self):
        prep = (BUILD / 'Prepare.java').read_text(encoding='utf-8')
        self.assertIn('String flat = other ? null : VersionFolder.why(dump);', prep)
        self.assertIn('String head = flat != null ? flat :', prep)
        self.assertLess(prep.index('VersionFolder.why(dump)'), prep.index('"Checking the game files stopped. The app cannot use these game files as they are."'))
        # the build card adds no "dump the game again" to it: the folder is whole, its files are one folder too high
        lib = (REPO / 'android/app/src/main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        self.assertIn('BuildListener.INVALID_DUMP.equals(b.code) ? (bt != null && bt.base != null && dev.peanuts.build.VersionFolder.is(bt.base.file) ? ""', lib)

    def test_upstream_still_refuses_it(self):
        """Upstream's Prepare does not start from one version's folder (DumpLayout.versions); when it does, this message has to go"""
        dl = (UP / 'DumpLayout.java').read_text(encoding='utf-8')
        self.assertIn('a one-version folder (exefs/ at its top) has no version number: not here', dl)
        self.assertIn('This folder holds one version\'s files. Pick the folder above it, the one that holds a folder named base.', dl)


if __name__ == '__main__':
    unittest.main()
