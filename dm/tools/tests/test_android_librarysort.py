# PEANUTS: Home's Sort (#557, intermission88)
"""Home's Sort (LibrarySort.java with android/app/src/jvmtest's LibrarySortJvmTest): the four orders, ties A-Z, All grouped or as one list with the building
games first, and the first-seen time behind Recently added. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/peanuts/ui/LibrarySort.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/LibrarySortJvmTest.java'
LIBRARY = REPO / 'android/app/src/main/java/dev/peanuts/ui/Library.java'


class Wiring(unittest.TestCase):
    def test_home_uses_it(self):
        t = LIBRARY.read_text(encoding='utf-8')
        self.assertIn('LibrarySort.order(sort,', t)
        self.assertIn('LibrarySort.all(bl, rd, bd, np, order, PeanutsPrefs.librarySortGrouped(a))', t)
        self.assertIn('LibrarySort.firstSeen(', t)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class LibrarySortJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.ui.LibrarySortJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
