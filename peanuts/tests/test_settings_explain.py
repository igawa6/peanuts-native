"""Settings on the second display (dev.peanuts.ui.SettingsPage, BottomScreen.explain, BottomPanel's explanation page): the help table on the JVM
(android/app/src/jvmtest's SettingsHelpJvmTest; skipped without a JDK), and the wiring: what the focus is on is explained, the page leaves when Settings
does, a switch flips from the second display, and PUi.Segmented is upstream's Ui.Segmented without its haptic hook."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UI = APP / 'main/java/dev/peanuts/ui'


def body(text, start):
    """The block that starts at `start` (its braces balanced)"""
    i = text.index(start)
    j = text.index('{', i)
    depth = 0
    for k in range(j, len(text)):
        depth += {'{': 1, '}': -1}.get(text[k], 0)
        if depth == 0:
            return text[i:k + 1]
    raise AssertionError(start)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='settingshelp_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'SettingsSections.java'), str(UI / 'SettingsHelp.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/ui/SettingsHelpJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.SettingsHelpJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_focus_and_taps_are_explained(self):
        page = (UI / 'SettingsPage.java').read_text(encoding='utf-8')
        self.assertIn('addOnGlobalFocusChangeListener((was, now) -> focused(now))', page)
        self.assertIn('BottomScreen.explain(a, e)', page)
        self.assertIn('if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) explainRow(row); return false;', page, 'a tap explains, and the row still gets it')
        self.assertIn('if (explained == row) explainRow(row);', page, 'a value that changes updates the explanation')
        self.assertIn('BottomScreen.explain(a, null)', page, 'leaving Settings gives the display back')

    def test_bottom_screen_page(self):
        screen = (UI / 'BottomScreen.java').read_text(encoding='utf-8')
        self.assertIn('else if (started && explain != null && (openSettings == null || setup)) panel.showExplain(explain);', screen, "Settings' row, or the first-run setup's step on Home")
        for at in ('public static void home(Activity a, Runnable settings)', 'static void page(Activity a)'):
            self.assertIn('explain = null', body(screen, at), f'{at} clears the explanation')
        panel = (UI / 'BottomPanel.java').read_text(encoding='utf-8')
        self.assertIn('LAUNCH = 3, EXPLAIN = 4', panel)
        self.assertIn('page(explainPage())', panel)
        self.assertIn('e.flip.run();', panel)
        self.assertIn('"App default: " + e.deflt', panel)
        # a page of the app's own on this display (Paket 3's Users card): its view, built with this display's context, an equal key replaces it in place
        self.assertIn('public java.util.function.Function<android.content.Context, View> view;', screen)
        self.assertIn('v = e.view.apply(getContext());', panel)
        self.assertIn('e.key.equals(was.key)', screen)

    def test_segmented_is_upstreams(self):
        up = body((APP / 'main/java/dev/nxnative/android/Ui.java').read_text(encoding='utf-8'), 'static final class Segmented')
        ours = body((UI / 'PUi.java').read_text(encoding='utf-8'), 'public static final class Segmented')
        up = up.replace('dev.peanuts.ui.Feel.picked(v); ', '').replace('   // PEANUTS: Feel.picked, the choice\'s haptic', '')
        norm = lambda s: re.sub(r'\bpublic ', '', s)
        self.assertEqual(norm(ours), norm(up))


if __name__ == '__main__':
    unittest.main()
