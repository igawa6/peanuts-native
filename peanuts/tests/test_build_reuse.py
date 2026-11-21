"""android/app/src/main/java/dev/peanuts/build/BuildReuse.java (issue #120): the ready message says what a build took from earlier builds, read from its steps' logs
(BuildReuseJvmTest on the JVM), and its patterns match the lines the reuse tools print (dm/peanuts/reuse, scripts/pack_android.py). The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
SRC = APP / 'main/java/dev/peanuts/build/BuildReuse.java'
TEST = APP / 'jvmtest/java/dev/peanuts/build/BuildReuseJvmTest.java'


class Lines(unittest.TestCase):
    """The words the Java patterns look for are the ones the tools print: a change on one side breaks this"""
    def test_the_tools_print_what_it_reads(self):
        java = SRC.read_text(encoding='utf-8')
        shader = (REPO / 'dm/peanuts/reuse/shader_reuse.py').read_text(encoding='utf-8')
        rewrite = (REPO / 'dm/peanuts/reuse/rewrite_reuse.py').read_text(encoding='utf-8')
        pack = (REPO / 'scripts/pack_android.py').read_text(encoding='utf-8')
        split = (REPO / 'dm/peanuts/reuse/split_reuse.py').read_text(encoding='utf-8')
        self.assertIn("shaders translated to check are the older library\\'s byte for byte; {len(kept)} shaders kept", shader)
        self.assertIn("[RED] reuse check: ", shader)
        self.assertIn("kept from the app\\'s copy of its last rewrite", rewrite)
        self.assertIn("kept, its build and its part of the profile are unchanged", rewrite)
        self.assertIn("unchanged, kept", pack)
        self.assertRegex(split, r"split reuse: \{[^}]+\} of \{[^}]+\} files kept")
        for words in ("shaders translated to check are the older library's byte for byte; (\\\\d+) shaders kept", "kept from the app's copy of its last rewrite",
                      "kept, its build and its part of the profile are unchanged", "unchanged, kept", "split reuse: (\\\\d+) of (\\\\d+) files kept"):
            self.assertIn(words, java)

    def test_the_ready_message_takes_it(self):
        svc = (APP / 'main/java/dev/peanuts/build/BuildService.java').read_text(encoding='utf-8')
        self.assertTrue(re.search(r'BuildReuse\.text\(BuildReuse\.read\(', svc), 'BuildService adds the sentence to the ready message')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.build.BuildReuseJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('ok', r.stdout)


if __name__ == '__main__':
    unittest.main()
