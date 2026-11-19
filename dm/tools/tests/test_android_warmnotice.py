"""PEANUTS: the launcher's "Preparing graphics" notice (android/README.md, In-game notices; dev.peanuts.ui.WarmNotice with android/app/src/jvmtest's WarmNoticeJvmTest):
when to show it, the text, and the wiring from the host (vka_warmnote.h, nxhost_warm_status) to the game screen. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/peanuts/ui/WarmNotice.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/WarmNoticeJvmTest.java'


class Wiring(unittest.TestCase):
    def test_status_is_wired(self):
        self.assertIn('nxhost_warm_status', (REPO / 'dm/gfx/vk_android/vka_exec.c').read_text(encoding='utf-8'))
        self.assertIn('Native_warmStatus', (REPO / 'android/app/src/main/cpp/nxlaunch.c').read_text(encoding='utf-8'))
        self.assertIn('native int warmStatus', (REPO / 'android/app/src/main/java/dev/nxnative/android/Native.java').read_text(encoding='utf-8'))
        self.assertIn('WarmNotice.text', (REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java').read_text(encoding='utf-8'))

    def test_background_builds_are_not_a_driver_cause(self):
        d = (REPO / 'dm/gfx/vk_android/vka_draw.c').read_text(encoding='utf-8')
        self.assertIn('if (warm) g_skip_warm++; else g_skip_dev[VKS_PIPELINE]++;', d)
        self.assertIn('if (vka_pso_last_pending()) g_skip_warm++; else g_skip_dev[VKS_PIPELINE]++;', d)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Notice(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='warmnotice_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.tmp / 'classes'), str(SRC), str(TEST)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, n):
        r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', str(self.tmp / 'classes'), 'dev.peanuts.ui.WarmNoticeJvmTest', str(n)], capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.rstrip('\n')

    def test_shown_while_pipelines_wait(self):
        self.assertEqual(self.java(517), 'true true Preparing graphics: 517 pipelines left. Parts of the picture may be missing until they are ready (first run only).')
        self.assertTrue(self.java(1).startswith('true true Preparing graphics: 1 pipeline left.'))

    def test_hidden(self):
        self.assertTrue(self.java(0).startswith('false true '))    # nothing waits: hidden, still asked
        self.assertTrue(self.java(-1).startswith('false false '))  # a host without the entry point: no more asking


if __name__ == '__main__':
    unittest.main()
