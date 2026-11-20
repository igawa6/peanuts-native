"""Settings › GPU driver as a Peanuts page (dev.peanuts.ui.DriverActivity): which Adreno a driver file is made for (DriverMatch, on the JVM with
android/app/src/jvmtest's DriverMatchJvmTest; skipped without a JDK), and the wiring: the two hooks in MainActivity and the manifest entry, every
install through upstream's GpuDriver.install (DriverPort), no network before a source's page opens."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
MAIN = REPO / 'android/app/src/main/java/dev/nxnative/android/MainActivity.java'
MANIFEST = REPO / 'android/app/src/main/AndroidManifest.xml'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Match(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='drivermatch_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'DriverMatch.java'),
                            str(REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/DriverMatchJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.DriverMatchJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_hooks(self):
        main = MAIN.read_text(encoding='utf-8')
        hooks = [l for l in main.splitlines() if 'DriverActivity.open(this)' in l]
        self.assertEqual(len(hooks), 2, 'the Settings row and the Readiness check open the page')
        for l in hooks:
            self.assertIn('PEANUTS', l)
        self.assertNotIn('GpuDriver.dialog(this, PICK_DRIVER)', main)
        self.assertRegex(MANIFEST.read_text(encoding='utf-8'), r'<activity android:name="dev\.peanuts\.ui\.DriverActivity"[^\n]*android:exported="false"[^\n]*PEANUTS')

    def test_installs_through_upstream(self):
        page = (UI / 'DriverActivity.java').read_text(encoding='utf-8')
        self.assertNotIn('ZipInputStream', page)
        self.assertNotIn('ZipFile', page)
        self.assertIn('DriverPort.install(', page)
        port = (UI / 'DriverPort.java').read_text(encoding='utf-8')
        self.assertIn('method("GpuDriver", "install"', port)

    def test_game_uses_shared_page_and_scopes_both_install_paths(self):
        settings = MAIN.with_name('GameSettings.java').read_text(encoding='utf-8')
        self.assertIn('DriverActivity.openForGame(a, g.titleId, g.name)', settings)
        page = (UI / 'DriverActivity.java').read_text(encoding='utf-8')
        for wiring in ('DriverPort.gameChoice(c, gameTitleId, value)', 'select(this, d, false)', 'select(app, d, false)'):
            self.assertIn(wiring, page)

    def test_network_only_from_a_source(self):
        page = (UI / 'DriverActivity.java').read_text(encoding='utf-8')
        # releases(...) is read in load(), which only a source's page calls; a download only from a file's Install
        self.assertEqual(re.findall(r'\bload\(', page).count('load('), 2, 'load() is defined once and called once')
        self.assertIn('if (r == null || System.currentTimeMillis() - r.at > CACHE_MS) { load(repo);', page)

    def test_file_and_download_share_one_install_guard(self):
        page = (UI / 'DriverActivity.java').read_text(encoding='utf-8')
        for signature in ('private void installFile(', 'private void download('):
            method = page[page.index(signature):]
            self.assertRegex(method, r'\{\s*if \(installing\) return;\s*installing = true;')
        self.assertEqual(page.count('installFinished();'), 2)


if __name__ == '__main__':
    unittest.main()
