"""PEANUTS: known bad GPU driver builds (KnownDrivers.java with android/app/src/jvmtest's KnownDriversJvmTest): Turnip v27.0.0-R3 on Adreno 7xx, matched by its Mesa
git hash in the driver's own driverInfo or meta.json, never by a folder name; the driver pickers mark it, a start with it asks (Play anyway / Change driver) and the
game's log gets one [RED] line. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/KnownDriversJvmTest.java'


def read(p):
    return p.read_text(encoding='utf-8')


class Wiring(unittest.TestCase):
    def test_start_notice(self):
        m = read(SRC / 'MainActivity.java')
        start = m[m.index('private void start(Game g) {'):m.index('private void startWithDriver(Game g)')]
        self.assertIn('GpuDriver.problem(this, GpuDriver.selected(this, g.titleId))', start)
        self.assertIn('KnownDrivers.notice(bad)', start)
        self.assertIn('setTitle(KnownDrivers.NOTICE_TITLE)', start)
        self.assertIn('"Change driver"', start)
        self.assertIn('setNeutralButton("Play anyway", (d, w) -> startWithDriver(g))', start)
        self.assertLess(start.index('Turnip recommended'), start.index('KnownDrivers.notice(bad)'), 'the system-driver notice first: a bad build is a custom driver')

    def test_log_line(self):
        g = read(SRC / 'GameActivity.java')
        self.assertIn('badDriverLine = bad == null ? null : KnownDrivers.logLine(bad', g)
        self.assertIn('if (badDriverLine != null) Native.note(badDriverLine);', g)

    def test_pickers(self):
        self.assertIn('static String problemText(Context c, Info i)', read(SRC / 'GpuDriver.java'))
        self.assertIn('String bad = problemText(a, inst.get(k));', read(SRC / 'GpuDriver.java'), 'the main screen dialog')
        self.assertIn('method("GpuDriver", "problemText", Context.class, Class.forName(P + "GpuDriver$Info"))', read(UI / 'DriverPort.java'))
        self.assertIn('withProblem(about(d), problem(d))', read(UI / 'DriverActivity.java'), 'Settings › GPU driver and a game\'s GPU driver page')

    def test_driver_info_kept(self):
        p = read(SRC / 'Prefs.java')
        self.assertIn('"vk_info@" + driverChoice', p)
        self.assertIn('static java.util.Map<String, String> driverFacts(Context c, String driverChoice)', p)
        self.assertIn('Prefs.driverFacts(c, i.dir.getName())', read(SRC / 'GpuDriver.java'))

    def test_documented(self):
        self.assertIn('KnownDrivers', read(REPO / 'android/README.md'))
        self.assertIn('Turnip v27.0.0-R3', read(REPO / 'peanuts/docs/FAQ.md'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class KnownDriversJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            srcs = [SRC / f'{n}.java' for n in ('KnownDrivers', 'Recommend', 'Readiness')]
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, srcs), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.KnownDriversJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
