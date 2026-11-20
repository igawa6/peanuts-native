# PEANUTS: the launcher's own Miis (Settings › Miis)
"""The launcher's own Miis (Settings › Miis: dev.peanuts.mii.MiiStore) on the JVM: the console's CharInfo ranges and layout, the database file the host reads
(dm/host/hle_mii.c, DM_MII_DB) and the round trip through miis.tsv."""
import shutil, subprocess, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/peanuts/mii/MiiStore.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/mii/MiiStoreJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MiiStoreJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.mii.MiiStoreJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


if __name__ == '__main__':
    unittest.main()
