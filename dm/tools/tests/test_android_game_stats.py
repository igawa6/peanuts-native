# PEANUTS: the fork's stats page (dev.peanuts.ui)
"""The second screen's stats page for a game with no companion (StatsView and GameStats in android/app/src/main/java/dev/peanuts/ui): GameScreen shows it in
place of the cover when Settings › Library › Games without a companion says Stats, and its maths run on a JVM (skipped without a JDK)."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/GameStatsJvmTest.java'
DEV_TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/DeviceStatsJvmTest.java'
CFG_TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/StatsConfigJvmTest.java'


class Wiring(unittest.TestCase):
    def test_game_screen(self):
        g = (UI / 'GameScreen.java').read_text()
        self.assertIn('new StatsView(getContext())', g)
        self.assertIn('PeanutsPrefs.bottomScreenGamesPage(getContext()).equals(PeanutsPrefs.GAMES_STATS)', g)
        self.assertIn('stats.setRunning(false)', g)   # nothing is read while the game is away

    def test_setting(self):
        l = (UI / 'LibrarySettings.java').read_text()
        self.assertIn('PeanutsPrefs.bottomScreenGamesPage(a, PeanutsPrefs.GAMES_COVER)', l)
        p = (REPO / 'android/app/src/main/java/dev/peanuts/PeanutsPrefs.java').read_text()
        self.assertIn('"bottom_screen_games_page", GAMES_STATS', p)

    def test_burn_in(self):
        s = (UI / 'StatsView.java').read_text()
        for k in ('GameStats.orbit(', 'GameStats.DIM_MS', 'GameStats.OFF_MS', 'onInterceptTouchEvent', 'PeanutsPrefs.statsGuard(', 'PeanutsPrefs.statsShown('):
            self.assertIn(k, s)

    def test_top_overlay_hides(self):
        a = (REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java').read_text()
        for k in ('GameScreen.hidesOverlay(this)', 'GameScreen.buildTag('):
            self.assertIn(k, a)
        self.assertIn('Hud.hideForStats(c) && statsShowing()', (UI / 'GameScreen.java').read_text())
        self.assertIn('GameStats.hidesTop(', (UI / 'GameScreen.java').read_text())
        self.assertIn('Hud.HIDE_FOR_STATS', (REPO / 'android/app/src/main/java/dev/peanuts/overlay/HudSettings.java').read_text())
        self.assertIn('public void buildTag(', (UI / 'StatsView.java').read_text())

    def test_readings(self):
        s = (UI / 'StatsView.java').read_text()
        for k in ('BATTERY_PROPERTY_CURRENT_NOW', 'BATTERY_PROPERTY_CHARGE_COUNTER', 'GameStats.drainPerHour(', 'device.hasGpu()', 'GameStats.throttle(',
                  'Choreographer.getInstance().postFrameCallback', 'GameStats.newFrames('):
            self.assertIn(k, s)

    def test_controls(self):
        a = (REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java').read_text()
        for k in ('dev.peanuts.ui.GameControls.host(controlsHost = peanutsControls())', 'dev.peanuts.ui.GameControls.clear(controlsHost)', 'Native.graphicsModeSet(-1, 1 - m)', 'Native.fpsCapSet(v)'):
            self.assertIn(k, a)
        s = (UI / 'StatsView.java').read_text()
        for k in ('openControl(v, true)', 'openControl(v, false)', 'GameControls.host()', 'popup != null', 'h.capRunning()', 'fpsBlock'):
            self.assertIn(k, s)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class GameStatsJvm(unittest.TestCase):
    def test_stats(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(UI / 'GameStats.java'), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.ui.GameStatsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')

    def test_config(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(UI / 'StatsConfig.java'), str(CFG_TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.ui.StatsConfigJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')

    def test_device(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(UI / 'DeviceStats.java'), str(DEV_TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.ui.DeviceStatsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
