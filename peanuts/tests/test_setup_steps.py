"""The first-run setup (dev.peanuts.setup.SetupSteps, drawn by dev.peanuts.ui.SetupPage): the steps on the JVM (android/app/src/jvmtest's
SetupStepsJvmTest; skipped without a JDK), and against the sources: Home shows the setup in place of the library until a game folder is set and Show my
games is pressed, it uses the folder lists of Settings › Library, B goes a step back through the one Back hook there is, a wrong keys file
shows on the keys step (the check itself: test_keys_check.py), the second screen explains the step, and the new text keeps UI-RULES.md."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UI = APP / 'main/java/dev/peanuts/ui'
SETUP = APP / 'main/java/dev/peanuts/setup'


def src(path):
    return path.read_text(encoding='utf-8')


def between(text, start, end):
    i = text.index(start)
    return text[i:text.index(end, i)]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='setupsteps_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(SETUP / 'SetupSteps.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/setup/SetupStepsJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.setup.SetupStepsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_home_shows_the_setup(self):
        lib = src(UI / 'Library.java')
        render = between(lib, 'private void render() {', '\n    }\n')
        self.assertIn('if (SetupPage.needed(a)) {', render)
        self.assertIn('SetupPage.fill(a, box);', render)
        self.assertIn('SetupPage.leave(box);', render)
        self.assertNotIn('setupCard', lib, 'the old first-run card is gone')
        page = src(UI / 'SetupPage.java')
        self.assertIn('return PeanutsPrefs.gameDirs(a).isEmpty() || !PeanutsPrefs.setupDone(a);', page)

    def test_folders_are_the_lists(self):
        page = src(UI / 'SetupPage.java')
        self.assertIn('LibraryFolders.pick(a, FolderKinds.GAMES, false)', page)
        self.assertIn('LibraryFolders.pick(a, FolderKinds.ADDONS, false)', page, 'the update and DLC folders: a link in the games step')
        self.assertIn('LibraryFolders.folderSheet(a, k, d)', page, "a folder's chip opens its sheet, with Remove")

    def test_the_peanut_cracks(self):
        page = src(UI / 'SetupPage.java')
        self.assertIn('peanut.to(SetupSteps.crack(s, st), s == Step.DONE);', page, 'one peanut for the whole setup: its seam runs on')
        self.assertIn('if (!sig.equals(shown)) {', page, 'a step is built again only when what it shows changed')
        go = between(page, 'private static void go(Activity a, Step s) {', '\n    }\n')
        self.assertIn('fill(a, (LinearLayout) root.getParent());', go, 'another step swaps the step part only: Home is not built again')
        self.assertIn('SetupPeanut.endLeft() + 80', page, 'the last wait on the end'"'"'s clock, whatever Home builds again')
        self.assertIn('"Setting up Peanuts Native"', page, 'the last wait, as switching users')
        nut = src(UI / 'SetupPeanut.java')
        self.assertIn('private final PeanutArt art = new PeanutArt();', nut, "About's peanut")
        self.assertIn('Motion.on() && ValueAnimator.areAnimatorsEnabled()', nut, 'still under reduced motion')
        self.assertIn('Haptics.Word.DONE', nut)
        self.assertIn('float fall = shells - KERNELS_LATER_MS;', nut, 'at the end the halves fall first, the kernels a moment after them')
        art = src(UI / 'PeanutArt.java')
        self.assertIn('void draw(Canvas c, float crackT, float swing, float deg, int alpha, float shellDrop, float dropTop, float dropBottom) {', art)
        self.assertNotIn('class Rail', page, 'no list of steps at the side')

    def test_file_access_first(self):
        page = src(UI / 'SetupPage.java')
        access = between(page, 'private static void access(Activity a,', '\n    }\n')
        self.assertIn('LibrarySettings.openAccessSettings(a)', access, "the first step: Android's page for the app")
        self.assertIn('st.access = Environment.isExternalStorageManager();', page)
        self.assertIn('go(a, SetupSteps.next(Step.WELCOME))', page, 'Set up leads to the first step')
        self.assertIn('PeanutsPrefs.setupFurthest(a, s.key)', page, 'the peanut cracks only for a step the player reached')

    def test_back_and_keys(self):
        settings = src(UI / 'SettingsPage.java')
        on_back = between(settings, 'public static boolean onBack(Activity a) {', '\n    }\n')
        self.assertIn('if (SetupPage.onBack(a)) return true;', on_back, 'B in the setup: the step before (no new upstream hook)')
        main = (APP / 'main/java/dev/nxnative/android/MainActivity.java').read_text(encoding='utf-8')
        self.assertNotIn('SetupPage', main, 'no upstream file changes for the setup')
        page = src(UI / 'SetupPage.java')
        self.assertNotIn('"Start"', page)
        self.assertNotIn('"Select"', page)
        self.assertNotRegex(page, r'BUTTON_(START|SELECT)')

    def test_a_wrong_keys_file_shows_on_the_keys_step(self):
        lib = src(UI / 'LibrarySettings.java')
        refused = between(lib, 'private static void keysRefused(Activity a, String why) {', '\n    }\n')
        self.assertIn('if (SetupPage.needed(a)) { SetupPage.keysRejected = true;', refused, 'the setup says so on its keys step, not in a dialog over it')

    def test_second_screen(self):
        bottom = src(UI / 'BottomScreen.java')
        self.assertIn('static void setup(Activity a, Explain e) {', bottom)
        self.assertIn('else if (started && explain != null && (openSettings == null || setup)) panel.showExplain(explain);', bottom)
        page = src(UI / 'SetupPage.java')
        self.assertIn('BottomScreen.setup(a, explain(a, s, st));', page)


class Text(unittest.TestCase):
    def test_ui_rules(self):
        for f in (UI / 'SetupPage.java', SETUP / 'SetupSteps.java'):
            text = src(f)
            strings = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', text))
            for bad in ('·', '•', ' - ', '–', '—', '!', '…', 'Loading'):
                self.assertNotIn(bad, strings, f'{f.name}: {bad!r} in its text')
            self.assertIsNone(re.search('[\U0001F300-\U0001FAFF☀-➿]', strings), f'{f.name}: an emoji in its text')


if __name__ == '__main__':
    unittest.main()
