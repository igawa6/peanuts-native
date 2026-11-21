"""Miis from another Switch emulator (dev.peanuts.mii.MiiImport, GitHub issue #320) on the JVM: the console's Mii database (MiiDatabase.dat, "NFDB"), a zip
of an emulator's user data that holds it, one StoreData, one CharInfo, and which found Miis an import adds. The test packs its own files in the console's layout.
Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MII = REPO / 'android/app/src/main/java/dev/peanuts/mii'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/mii/MiiImportJvmTest.java'
SOURCES = [MII / n for n in ('MiiStore.java', 'MiiImport.java')]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MiiImportJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, SOURCES), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.mii.MiiImportJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


class Sources(unittest.TestCase):
    def test_no_android_classes(self):
        """The reader runs on the JVM: no android.* import"""
        self.assertNotIn('import android.', (MII / 'MiiImport.java').read_text(encoding='utf-8'))

    def test_the_miis_page_imports(self):
        """The Miis page offers the import and hands the picked file to MiiImport.read"""
        page = (MII / 'MiiActivity.java').read_text(encoding='utf-8')
        self.assertIn('MiiImport.read(', page)
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', page)


if __name__ == '__main__':
    unittest.main()
