"""The game sheet's "Built with" line or chip: android/app/src/main/java/dev/peanuts/build/BuiltWith.java (a library version's release, BuiltWithJvmTest on
the JVM) and its use in dev/peanuts/ui/GameSheet.java. The releases the sheet calls quick to upgrade must be ones vk_android_upgrade.py has a path from. The
JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
BW = APP / 'main/java/dev/peanuts/build/BuiltWith.java'
UP = APP / 'main/java/dev/peanuts/build/ShaderUpgrade.java'
DUE = APP / 'main/java/dev/peanuts/build/CheckDue.java'
SHEET = APP / 'main/java/dev/peanuts/ui/GameSheet.java'
TEST = APP / 'jvmtest/java/dev/peanuts/build/BuiltWithJvmTest.java'
UP_TEST = APP / 'jvmtest/java/dev/peanuts/build/ShaderUpgradeJvmTest.java'
TOOL = REPO / 'dm/gfx/tools/vk_android_upgrade.py'


def read(p): return p.read_text(encoding='utf-8')


def releases():
    src = read(BW)
    table = src[src.index('RELEASES = {'):src.index('};', src.index('RELEASES = {'))]
    return re.findall(r'\{"([0-9a-f]{12})", "([0-9.]+)"\}', table)


class Source(unittest.TestCase):
    def test_the_sheet_uses_it_off_the_main_thread(self):
        s = read(SHEET)
        self.assertIn('BuiltWith.of(pack, titleId, dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN)', s)
        body = s[s.index('private void renderBuiltWith()'):]
        body = body[:body.index('\n    }\n')]
        self.assertLess(body.index('new Thread('), body.index('BuiltWith.of('))
        self.assertIn('PUi.warnChip(a, label, label, warning)', body)
        self.assertIn('if (ready) renderBuiltWith();', s)

    def test_one_header_reader(self):
        s = read(BW)
        self.assertIn('ShaderUpgrade.header(', s)
        self.assertNotIn('RandomAccessFile', s)

    def test_the_build_names_this_version(self):
        # this release's version is the build's, never written in the table
        cur = re.search(r'#define DMSH_VK_CHAIN "([0-9a-f]{12})"', read(REPO / 'dm/gfx/nvn/dmsh_chain_vk.h')).group(1)
        self.assertNotIn(cur, read(BW))

    def test_table(self):
        r = releases()
        self.assertEqual([v for _, v in r], ['0.1.0', '0.1.1', '0.1.3', '0.1.4', '0.1.5', '0.1.6', '0.1.7'])
        self.assertEqual(len({c for c, _ in r}), len(r))

    @unittest.skipUnless(TOOL.is_file(), 'no upgrade tool')
    def test_quick_releases_have_an_upgrade_path(self):
        src = read(BW)
        fast_from = re.search(r'FAST_FROM = "([0-9.]+)"', src).group(1)
        cycle = re.findall(r'"([0-9a-f]{12})"', src[src.index('THIS_CYCLE = {'):src.index('};', src.index('THIS_CYCLE = {'))])
        r = releases()
        idx = [v for _, v in r].index(fast_from)
        fast = [c for c, _ in r[idx:]] + cycle
        hops = read(TOOL)
        sources = set(re.findall(r"\('([0-9a-f]{12})', '[0-9a-f]{12}'", hops[hops.index('HOPS = ['):]))
        for c in fast: self.assertIn(c, sources, c + ' reads as quick to upgrade, but the tool has no path from it')
        for c, _ in r[:idx]: self.assertNotIn(c, sources, c + ' is upgradable: the sheet should not ask for a rebuild')

    def test_voice(self):
        s = read(BW)
        self.assertNotIn('—', s)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), str(BW), str(UP), str(DUE), str(UP_TEST), str(TEST)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.build.BuiltWithJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('ok', r.stdout)


if __name__ == '__main__':
    unittest.main()
