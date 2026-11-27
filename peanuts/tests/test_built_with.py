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


def table(name):
    src = read(BW)
    at = src.index(name + ' = {')
    return src[at:src.index('};', at)]


def releases():
    """[(DMSH_VK_CHAIN, DMSH_CHAIN, version)], oldest first"""
    return re.findall(r'\{"([0-9a-f]{12})", "([0-9a-f]{12})", "([0-9.]+)"\}', table('RELEASES'))


def define(header, name):
    return re.search(r'#define ' + name + r' "([0-9a-f]{12})"', read(REPO / 'dm/gfx/nvn' / header)).group(1)


def tool_hops():
    hops = read(TOOL)
    return re.findall(r"\('([0-9a-f]{12})', '([0-9a-f]{12})'", hops[hops.index('HOPS = ['):hops.index('\n\n', hops.index('HOPS = ['))])


def path_reaches(start, target, hops):
    """Whether the hops lead from start to target, as vk_android_upgrade.py path_of walks them"""
    nxt, v, seen = dict(hops), start, set()
    while v != target:
        if v not in nxt or v in seen: return False
        seen.add(v); v = nxt[v]
    return True


def quick(vk, dmsh, cur_vk, cur_dmsh, hops):
    return vk != cur_vk and dmsh == cur_dmsh and path_reaches(vk, cur_vk, hops)


class Source(unittest.TestCase):
    def test_the_sheet_uses_it_off_the_main_thread(self):
        s = read(SHEET)
        self.assertIn('BuiltWith.of(pack, titleId, dev.nxnative.android.BuildConfig.NX_DMSH_VK_CHAIN, dev.nxnative.android.BuildConfig.NX_DMSH_CHAIN)', s)
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
        # this release's versions are the build's, never written in the table
        src = read(BW)
        self.assertNotIn(define('dmsh_chain_vk.h', 'DMSH_VK_CHAIN'), src)
        self.assertIn('BuildConfig.NX_DMSH_CHAIN', read(SHEET))

    def test_table(self):
        r = releases()
        self.assertEqual([v for _, _, v in r], ['0.1.0', '0.1.1', '0.1.3', '0.1.4', '0.1.5', '0.1.6', '0.1.7', '0.1.8'])
        self.assertEqual(len({c for c, _, _ in r}), len(r))
        # the newest release's chains are the ones this tree moved from (#572: 0.1.8 to 0.1.9 moved both)
        self.assertEqual(r[-1][:2], ('327c6383b614', 'cdcf140b1558'))

    @unittest.skipUnless(TOOL.is_file(), 'no upgrade tool')
    def test_hops_are_the_tools(self):
        java = re.findall(r'\{"([0-9a-f]{12})", "([0-9a-f]{12})"\}', table('HOPS'))
        self.assertEqual(java, tool_hops())

    @unittest.skipUnless(TOOL.is_file(), 'no upgrade tool')
    def test_quick_means_a_whole_path_to_this_version(self):
        cur_vk, cur_dmsh, hops = define('dmsh_chain_vk.h', 'DMSH_VK_CHAIN'), define('dmsh_chain.h', 'DMSH_CHAIN'), tool_hops()
        fast = [v for vk, dmsh, v in releases() if quick(vk, dmsh, cur_vk, cur_dmsh, hops)]
        cycle = re.findall(r'\{"([0-9a-f]{12})", "([0-9a-f]{12})"\}', table('THIS_CYCLE'))
        for vk, dmsh in cycle: self.assertTrue(quick(vk, dmsh, cur_vk, cur_dmsh, hops), vk + ' reads as this app version, but the tool has no whole path from it')
        # 0.1.9: the translator rebuilt (#572) moved DMSH_CHAIN and DMSH_VK_CHAIN, and the hops end at 0.1.8's: no older library upgrades in place
        if (cur_vk, cur_dmsh) == ('c1eff1d2d8c1', 'f36b2a1bcf19'):
            self.assertEqual(fast, [])
            self.assertEqual(cycle, [])
        # the check itself: 0.1.8's hops reach 0.1.8 from 0.1.7, and not from 0.1.6 or past 0.1.8
        self.assertTrue(quick('c679ddfbc5db', 'cdcf140b1558', '327c6383b614', 'cdcf140b1558', hops))
        self.assertFalse(quick('c679ddfbc5db', 'cdcf140b1558', '327c6383b614', 'f36b2a1bcf19', hops), 'another DMSH_CHAIN')
        self.assertFalse(quick('e084efc69b46', 'cdcf140b1558', '327c6383b614', 'cdcf140b1558', hops), 'no hop from 0.1.6')
        self.assertFalse(quick('327c6383b614', 'cdcf140b1558', 'c1eff1d2d8c1', 'cdcf140b1558', hops), 'no hop past 0.1.8')

    def test_remake_from_is_a_release(self):
        remake = re.search(r'REMAKE_FROM = "([0-9.]+)"', read(BW)).group(1)
        self.assertIn(remake, [v for _, _, v in releases()])
        self.assertEqual(remake, '0.1.7')

    def test_voice(self):
        s = read(BW)
        self.assertNotIn('—', s)
        note = s[s.index('public static String note('):]
        self.assertNotIn('(', note[note.index('"'):note.index(';')].replace('" +', '').replace('+ "', ''))


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
