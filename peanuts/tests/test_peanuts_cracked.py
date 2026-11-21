"""Settings › About with Peanuts cracked (dev.peanuts.ui.AboutPage, Cracked, CrackedCount, Crack, CrackedPeanut, PeanutArt): the counting on the JVM
(android/app/src/jvmtest's CrackedCountJvmTest; skipped without a JDK), and the wiring: where each kind of point is counted, the one upstream line that lets
a tapped peanut crack, the old count carried over (upstream's own tap counter is part of the total), the credits against the README, and the game catalog
the app reads, the same as peanuts/games.json."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
PEANUTS = APP / 'main/java/dev/peanuts'
UI = PEANUTS / 'ui'
UP = APP / 'main/java/dev/nxnative/android'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='cracked_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'CrackedCount.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/ui/CrackedCountJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.CrackedCountJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Counting(unittest.TestCase):
    def test_the_points_are_counted_where_they_happen(self):
        build = (PEANUTS / 'build/BuildService.java').read_text(encoding='utf-8')
        self.assertRegex(build, r'ok = true;\n\s*cracked\(r, dump, startedAt\);', 'a build that ended well, and only that')
        self.assertIn('dev.peanuts.ui.Cracked.built(this, r.titleId, version < 0 ? null : "v" + version, shaders);', build)
        self.assertIn('if (sh != null && jf.lastModified() >= since)', build, 'shaders only when this build prepared them')
        ret = (PEANUTS / 'GameReturn.java').read_text(encoding='utf-8')
        self.assertIn('dev.peanuts.ui.Cracked.starting(c, titleId);', ret, 'every start from Home, a shortcut or a frontend')
        editor = (PEANUTS / 'mii/MiiEditor.java').read_text(encoding='utf-8')   # a new Mii is made in the editor, and counted once it is saved
        self.assertRegex(editor, r'MiiStore\.save\(getFilesDir\(\), all\); \}\n[^\n]*\n\s*Feel\.done\(this\);\n\s*if \(index < 0\) dev\.peanuts\.ui\.Cracked\.mii\(this\);')
        self.assertIn('Cracked.settle(x);', (UI / 'BottomScreen.java').read_text(encoding='utf-8'), 'play time is counted back in the launcher')

    def test_the_old_count_carries_over(self):
        prefs = (UP / 'Prefs.java').read_text(encoding='utf-8')
        cracked = (UI / 'Cracked.java').read_text(encoding='utf-8')
        key = re.search(r'getLong\("(\w+)", 0\); \}\n\s*static long addBananaPopped', prefs).group(1)
        self.assertIn(f'UP_TAPS = "{key}"', cracked, 'the taps are read from upstream\'s own key')
        self.assertIn('static SharedPreferences of(Context c) { return open(c, "launcher"); }', prefs)
        self.assertIn('UP_FILE = "launcher"', cracked)
        self.assertIn('return CrackedCount.total(state(c), taps(c));', cracked)

    def test_crack_instead_of_burst(self):
        view = (UP / 'BananaBackgroundView.java').read_text(encoding='utf-8')
        hooks = [l for l in view.splitlines() if 'dev.peanuts.ui.Crack.draw' in l]
        self.assertEqual(len(hooks), 1)
        self.assertIn('PEANUTS', hooks[0])
        body = view[view.index('private void drawBanana('):]
        self.assertTrue(body.splitlines()[1].strip().startswith('if (at != null && dev.peanuts.ui.Crack.draw('), 'the first line of drawBanana')
        crack = (UI / 'Crack.java').read_text(encoding='utf-8')
        self.assertIn('BuildConfig.NX_PEANUTS_ID.isEmpty()', crack, 'a Banana build keeps its burst')
        self.assertIn('!ValueAnimator.areAnimatorsEnabled()', crack)

    def test_one_peanut(self):
        """PrepareCover, About and the background crack draw the same peanut"""
        for f in ('PrepareCover.java', 'CrackedPeanut.java', 'Crack.java'):
            self.assertIn('PeanutArt', (UI / f).read_text(encoding='utf-8'), f)
        self.assertNotIn('cubicTo', (UI / 'PrepareCover.java').read_text(encoding='utf-8'), 'the shell is PeanutArt\'s only')
        breath = (UI / 'CrackedPeanut.java').read_text(encoding='utf-8')
        self.assertIn('deg = 24.5f - 2.5f * (float) Math.cos', breath, 'opens and closes calmly, no rocking')
        self.assertNotIn('rotate(', breath)


class About(unittest.TestCase):
    def test_contributors_match_the_readme(self):
        readme = (REPO / 'README.md').read_text(encoding='utf-8')
        listed = re.findall(r'^- \[([\w-]+)\]\(https://github\.com/[\w-]+\):', readme[readme.index('Contributors: <!-- PEANUTS'):], re.M)
        data = (UI / 'AboutData.java').read_text(encoding='utf-8')
        ours = re.findall(r'"([\w-]+)"', re.search(r'CONTRIBUTED = \{([^}]*)\}', data).group(1))
        self.assertEqual(ours[0], 'davdxpx', 'davdxpx first')
        self.assertEqual(sorted(ours), sorted(listed), 'About lists the README\'s contributors')

    def test_the_catalog_copy(self):
        self.assertEqual((APP / 'peanuts/res/raw/peanuts_games.json').read_text(encoding='utf-8'), (REPO / 'peanuts/games.json').read_text(encoding='utf-8'))
        self.assertIn("APP_COPY: render(catalog)", (REPO / 'peanuts/scripts/games_catalog.py').read_text(encoding='utf-8'))
        self.assertIn('android/app/src/peanuts/res/raw/peanuts_games.json', (REPO / '.github/workflows/catalog.yml').read_text(encoding='utf-8'))

    def test_about_page(self):
        page = (UI / 'AboutPage.java').read_text(encoding='utf-8')
        self.assertIn('TABS = {"Overview", "Games", "Credits", "Licenses"}', page)
        self.assertIn('getIdentifier("peanuts_games", "raw"', page)
        settings = (UI / 'SettingsPage.java').read_text(encoding='utf-8')
        self.assertIn('AboutPage.fill(a, col, wide || tablet, buildTag, () -> press("Check for updates"), () -> press("Questions and answers"))', settings)
        self.assertIn('if (sec.equals("about")) { buildTag = firstText(it.view); continue; }', settings, 'upstream\'s About card is left out')
        panel = (UI / 'BottomPanel.java').read_text(encoding='utf-8')
        self.assertIn('page(crackedPage())', panel)

    def test_ui_rules(self):
        for f in ('AboutPage.java', 'AboutData.java', 'Cracked.java', 'Crack.java', 'CrackedPeanut.java'):
            src = (UI / f).read_text(encoding='utf-8')
            strings = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', src))
            for bad in ('·', '•', ' - ', '–', '—', '!'):
                self.assertNotIn(bad, strings, f'{f}: {bad!r} in its text')
            self.assertIsNone(re.search('[\U0001F300-\U0001FAFF☀-➿]', strings), f'{f}: an emoji in its text')


if __name__ == '__main__':
    unittest.main()
