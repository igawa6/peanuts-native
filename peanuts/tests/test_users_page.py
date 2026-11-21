"""Settings › Users (dev.peanuts.users): the profile pictures' file (a reference to a Mii or a firmware picture and a background, never pixels) and the decision
"Who's playing?" makes at a game's start, on the JVM with UserPicturesJvmTest. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
USERS = REPO / 'android/app/src/main/java/dev/peanuts/users'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/users/UserPicturesJvmTest.java'
SOURCES = [USERS / 'UserPictures.java', USERS / 'WhoPlays.java']


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class UsersJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, SOURCES), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.users.UserPicturesJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


class Sources(unittest.TestCase):
    def test_no_android_classes(self):
        for f in SOURCES:
            self.assertNotIn('import android.', f.read_text(encoding='utf-8'), f.name)


if __name__ == '__main__':
    unittest.main()
