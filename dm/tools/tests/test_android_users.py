"""Settings › Users (android/app Users.java with android/app/src/jvmtest's UsersJvmTest): the stored list, the first user always the host's fixed one, at most 8 users, the host's
DM_USERS / DM_USER lines (dm/host/hle_account.c reads them) with the names percent-encoded, nothing for the one default user, names cut to 32 bytes, each user's save folder.
javac compiles Users.java with stand-ins for Android's Context and SharedPreferences and for Prefs. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/Users.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/UsersJvmTest.java'
STUBS = {
    'android/content/Context.java': 'package android.content;\npublic class Context {}\n',
    'android/content/SharedPreferences.java': 'package android.content;\npublic interface SharedPreferences {\n  String getString(String k, String d);\n'
                                              '  Editor edit();\n  interface Editor { Editor putString(String k, String v); void apply(); }\n}\n',
    'dev/nxnative/android/Prefs.java': 'package dev.nxnative.android;\nfinal class Prefs {\n  static android.content.SharedPreferences of(android.content.Context c) { return null; }\n}\n',
}


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class UsersJvm(unittest.TestCase):
    def test_users(self):
        with tempfile.TemporaryDirectory() as t:
            t = Path(t)
            for rel, text in STUBS.items():
                p = t / 'stub' / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(t / 'classes'), str(SRC), str(TEST), *map(str, (t / 'stub').rglob('*.java'))],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(t / 'classes'), 'dev.nxnative.android.UsersJvmTest'], capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
