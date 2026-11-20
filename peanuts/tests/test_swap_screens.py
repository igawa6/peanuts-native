"""Swap screens (GitHub issue #131): the game's start goes through dev.peanuts.Screens, and the companion follows the game to the other display."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'android/app/src/main/java'


class SwapScreens(unittest.TestCase):
    def test_both_game_starts_use_the_same_fallback(self):
        for f in ('dev/nxnative/android/MainActivity.java', 'dev/nxnative/android/FrontendLaunch.java'):
            src = (JAVA / f).read_text(encoding='utf-8')
            self.assertRegex(src, r'dev\.peanuts\.Screens\.startGame\(this, \w+\);\s+// PEANUTS:', f)

    def test_off_means_the_start_as_before(self):
        src = (JAVA / 'dev/peanuts/Screens.java').read_text(encoding='utf-8')
        body = src[src.index('public static void startGame'):]
        self.assertLess(body.index('PeanutsPrefs.swapScreens(launcher) ? external(launcher) : null'), body.index('setLaunchDisplayId'))
        self.assertLess(body.index('manager.isActivityStartAllowedOnDisplay'), body.index('setLaunchDisplayId'))
        self.assertLess(body.index('catch (SecurityException | IllegalArgumentException e)'), body.index('launcher.startActivity(intent);'))

    def test_launcher_panel_yields_to_an_external_game_without_hiding_home_settings(self):
        bottom = (JAVA / 'dev/peanuts/ui/BottomScreen.java').read_text(encoding='utf-8')
        self.assertIn('d.getDisplayId() == activeGameDisplay', bottom)
        self.assertNotIn('applyHeader', bottom)
        main = (JAVA / 'dev/nxnative/android/MainActivity.java').read_text(encoding='utf-8')
        self.assertIn('headerAction(this, R.drawable.ic_settings, "Settings"', main)
        self.assertIn('"Settings (X)", dev.peanuts.ui.BottomScreen.settingsGlyph(this, 14)', main)
        panel = (JAVA / 'dev/peanuts/ui/BottomPanel.java').read_text(encoding='utf-8')
        self.assertIn('pad ? BottomScreen.settingsGlyph(c, 22)', panel)
        dispatch = main.split('public boolean dispatchKeyEvent(KeyEvent e)', 1)[1].split('return super.dispatchKeyEvent(e);', 1)[0]
        self.assertIn('KeyEvent.KEYCODE_BUTTON_X && !settingsPage && settingsGame == null && !advancedOpen', dispatch)
        self.assertIn('e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) page(true)', dispatch)

    def test_companion_takes_the_display_the_game_is_not_on(self):
        src = (JAVA / 'dev/peanuts/Companion.java').read_text(encoding='utf-8')
        self.assertIn('if (own != Display.DEFAULT_DISPLAY) {', src)
        self.assertIn('new android.content.Intent(game, CompanionScreen.class)', src)
        screen = (JAVA / 'dev/peanuts/CompanionScreen.java').read_text(encoding='utf-8')
        self.assertIn('FLAG_NOT_FOCUSABLE', screen)
        self.assertIn('Companion::refocusGame', screen)

    def test_companion_screen_runs_in_the_game_process(self):
        manifest = (ROOT / 'android/app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
        line = next(l for l in manifest.splitlines() if 'dev.peanuts.CompanionScreen' in l)
        self.assertIn('android:process=":game"', line)
        self.assertIn('<!-- PEANUTS:', line)

    def test_presentation_and_screen_share_one_page(self):
        pres = (JAVA / 'dev/peanuts/CompanionPresentation.java').read_text(encoding='utf-8')
        screen = (JAVA / 'dev/peanuts/CompanionScreen.java').read_text(encoding='utf-8')
        for src in (pres, screen):
            self.assertRegex(src, re.compile(r'new CompanionPage\('))


if __name__ == '__main__':
    unittest.main()
