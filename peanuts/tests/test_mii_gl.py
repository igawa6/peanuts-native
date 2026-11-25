"""The Mii renderer (dev.peanuts.mii.MiiGl) when something fails: out of memory gives no picture instead of ending its thread (and the app), and a start
that failed is tried again once the firmware's Mii files change or a while later, instead of staying off until the process ends (MiiGlRetry on the JVM with
MiiGlRetryJvmTest; MiiGl's own lines read from the source). Skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MII = REPO / 'android/app/src/main/java/dev/peanuts/mii'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/mii/MiiGlRetryJvmTest.java'
SOURCES = [MII / n for n in ('MiiStore.java', 'MiiParts.java', 'Tegra.java', 'MiiGlRetry.java')]
GL = MII / 'MiiGl.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class MiiGlRetryJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, SOURCES), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.mii.MiiGlRetryJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


class Renderer(unittest.TestCase):
    def setUp(self):
        self.src = GL.read_text(encoding='utf-8')

    def method(self, signature):
        start = self.src.index(signature)
        return self.src[start:self.src.index('\n    }\n', start)]

    def test_out_of_memory_is_caught_where_pictures_are_made(self):
        """The picture for a tile and the editor's preview: an OutOfMemoryError on the renderer's thread would end the app"""
        self.assertIn('catch (OutOfMemoryError e)', self.method('public void picture(MiiStore.Mii m0, int px, float yaw, float zoom, Consumer<Bitmap> done)'))
        self.assertIn('catch (OutOfMemoryError e)', self.method('private void apply()'))

    def test_a_half_made_head_gives_its_textures_back(self):
        head = self.method('private Head head(byte[] info, MiiStore.Mii m)')
        self.assertRegex(head, r'catch \([^)]*OutOfMemoryError e\) \{ h\.release\(\); throw e; \}')
        self.assertIn('finally { h.release(); }', self.method('public void picture(MiiStore.Mii m0, int px, float yaw, float zoom, Consumer<Bitmap> done)'))

    def test_no_failure_lasts_until_the_process_ends(self):
        self.assertNotRegex(self.src, r'\bbroken\s*=\s*true')
        ready = self.method('private boolean ready()')
        self.assertEqual(ready.split('\n')[1].strip(), 'if (retry.waiting(System.currentTimeMillis(), dir)) return false;')
        self.assertNotIn('return false; }', re.sub(r'if \(parts == null\) return false;', '', ready))
        self.assertIn('retry.ok();', ready)

    def test_a_failed_start_leaves_nothing_half_made(self):
        failed = self.method('private boolean failed()')
        for line in ('EGL14.eglDestroyContext(dpy, ctx)', 'EGL14.eglDestroySurface(dpy, pbuf)', 'ctx = EGL14.EGL_NO_CONTEXT', 'parts = null', 'retry.failed('):
            self.assertIn(line, failed)
        self.assertNotIn('eglTerminate', failed)   # the display is the process's own, Android's views draw with it too

    def test_no_android_classes(self):
        self.assertNotIn('import android.', (MII / 'MiiGlRetry.java').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
