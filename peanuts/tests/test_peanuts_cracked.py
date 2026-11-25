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
        self.assertRegex(build, r'ok = true;\n(?:[^\n]*\n){2}\s*dev\.peanuts\.ui\.CrackedCount\.never\(\(\) -> cracked\(r, dump, startedAt\), ',
                         'a build that ended well, and only that; counted after done, and never able to fail the build')
        self.assertIn('progress("done", 100, message);\n', build)
        self.assertLess(build.index('progress("done", 100, message);'), build.index('CrackedCount.never(() -> cracked('), 'counted after done')
        self.assertEqual(build.count('cracked(r, '), 1, 'counted in one place only')
        self.assertIn('dev.peanuts.ui.Cracked.built(this, r.titleId, version < 0 ? null : "v" + version, shaders);', build)
        self.assertIn('if (sh != null && jf.lastModified() >= since)', build, 'shaders only when this build prepared them')
        ret = (PEANUTS / 'GameReturn.java').read_text(encoding='utf-8')
        self.assertIn('dev.peanuts.ui.Cracked.starting(c, titleId);', ret, 'every start from Home, a shortcut or a frontend')
        editor = (PEANUTS / 'mii/MiiEditor.java').read_text(encoding='utf-8')   # a new Mii is made in the editor, and counted once it is saved
        self.assertRegex(editor, r'MiiStore\.save\(getFilesDir\(\), all\); \}\n[^\n]*\n\s*Feel\.done\(this\);\n\s*if \(index < 0\) dev\.peanuts\.ui\.Cracked\.mii\(this\);')
        self.assertIn('Cracked.settle(x);', (UI / 'BottomScreen.java').read_text(encoding='utf-8'), 'play time is counted back in the launcher')

    def test_a_play_reads_nothing_at_its_tap(self):
        # GameReturn.remember runs at every Play on the main thread: Cracked.starting only hands the first start to its worker (#315's review)
        cracked = (UI / 'Cracked.java').read_text(encoding='utf-8')
        starting = cracked.split('public static void starting(Context c, String titleId) {')[1].split('\n    }\n')[0]
        tap = starting.split('BG.execute(')[0]
        self.assertIn('BG.execute(() -> started(app, titleId));', starting)
        for read in ('settleNow(', 'started(', 'prefs(', 'PlayTime', 'state('):
            self.assertNotIn(read, tap, 'read at the Play: ' + read)
        self.assertIn('BG = java.util.concurrent.Executors.newSingleThreadExecutor(', cracked, 'one worker: the start and the settles keep their order')
        settle = cracked.split('public static void settle(Context c) {')[1].split('\n')[0]
        self.assertIn('BG.execute(() -> settleNow(app));', settle)

    def test_play_time_from_the_ledger(self):
        # The play time is #282's ledger's (dev.peanuts.launch.PlayTime: counted only while a game runs, not while Build prepares its shaders): what its
        # total gained since the last look, the rule on the JVM above. Peanuts cracked no longer measures runs itself: no run record, no process list
        cracked = (UI / 'Cracked.java').read_text(encoding='utf-8')
        now = cracked.split('private static synchronized void settleNow(Context c) {')[1].split('\n    }\n')[0]
        self.assertIn('dev.peanuts.launch.PlayLedger.total(dev.peanuts.launch.PlayTime.all(c))', now)
        self.assertIn('CrackedCount.seenAtFirstLook(p.contains(OLD_RUN) || p.contains(OLD_SETTLED), total)', now, "0.1.7's own measure: no hour twice")
        self.assertIn('long add = CrackedCount.fromLedger(seen, total);', now)
        for gone in ('last-exit.txt', 'getHistoricalProcessExitReasons', 'getRunningAppProcesses', 'ActivityManager', 'toCount', 'putString(RUN'):
            self.assertNotIn(gone, cracked)
        play = (PEANUTS / 'launch/PlayTime.java').read_text(encoding='utf-8')
        self.assertIn('public static Map<String, PlayLedger.Entry> all(Context c) { return PlayLedger.parse(read(file(c))); }', play)
        self.assertIn('return new File(c.getFilesDir(), "peanuts-playtime.tsv");', play)
        self.assertIn('public static long total(Map<String, Entry> m) {', (PEANUTS / 'launch/PlayLedger.java').read_text(encoding='utf-8'))

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

    def test_licenses_say_what_the_notices_say(self):
        # About › Licenses named Banana Native GPL-3.0, where the README and NOTICE say GPL-3.0-or-later (#315's review). A GPL row names its version
        # rule as the notices do (SPDX: -only or -or-later), and the two GPL rows match the sentences they come from
        data = (UI / 'AboutData.java').read_text(encoding='utf-8')
        rows = {m.group(1): m.group(2) for m in re.finditer(r'\{"([^"]+)", "[^"]*", "([^"]+)", ', data)}
        for name, lic in rows.items():
            if 'GPL' in lic:
                self.assertRegex(lic, r'^(?:L|A)?GPL-\d\.\d-(?:only|or-later)$', name + ': ' + lic)
        readme = (REPO / 'README.md').read_text(encoding='utf-8')
        self.assertIn('The project as a whole is licensed ' + rows['Banana Native'] + '.', readme)
        notice = (REPO / 'android/NOTICE.md').read_text(encoding='utf-8')
        self.assertIn('**The Android build that includes LSFG is ' + rows['Frame generation'] + '.**', notice)
        lsfg = (REPO / 'dm/gfx/vk_android/lsfg/LICENSE.md').read_text(encoding='utf-8')
        self.assertIn('# License of this folder: ' + rows['Frame generation'], lsfg)

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
