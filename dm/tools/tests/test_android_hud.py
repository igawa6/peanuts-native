"""The in-game overlay's label / value table (Hud, HudSettings, FrameStats, HistogramSpan in android/app/src/main/java/dev/peanuts/overlay; the hooks in
Overlay.java, MainActivity.java, SidePanel.java, GameActivity.java): the hooks are there, the settings' label table mirrors Overlay.ITEMS, the preferences file is
Prefs', the maths run on a JVM, and the Android classes compile against android.jar. The JVM parts are skipped without a JDK, the compile check without android.jar."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
APP = REPO / 'android/app/src'
PEA = APP / 'main/java/dev/peanuts'
OV = PEA / 'overlay'
NX = APP / 'main/java/dev/nxnative/android'
TEST = APP / 'jvmtest/java/dev/peanuts/overlay/FrameStatsJvmTest.java'
ANDROID_JAR = Path.home() / 'Library/Android/sdk/platforms/android-36/android.jar'


def jar():
    j = os.environ.get('ANDROID_JAR')
    if j and Path(j).exists():
        return Path(j)
    if ANDROID_JAR.exists():
        return ANDROID_JAR
    for sdk in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT')):   # a machine whose SDK is elsewhere: its newest platform
        jars = sorted(Path(sdk).glob('platforms/android-*/android.jar')) if sdk else []
        if jars:
            return jars[-1]
    return None


class Wiring(unittest.TestCase):
    def test_hooks(self):
        o = (NX / 'Overlay.java').read_text()
        self.assertIn('{"graph", "Frame time graph and 1% low"},', o)
        self.assertIn('{"driver", "GPU driver"},', o)
        self.assertIn('&& !dev.peanuts.overlay.Hud.extra(ctx)) return null;', o)
        self.assertIn('return dev.peanuts.overlay.Hud.table(ctx, s, rate, fresh, level, mainCap(), batTemp, batLevel, charging, rss, memTotal, memAvail, memLow);', o)
        for f, ctx in (('MainActivity.java', 'this'), ('SidePanel.java', 'act')):
            t = (NX / f).read_text()
            self.assertRegex(t, r'Overlay\.rows\(%s, box, null\);\n\s*dev\.peanuts\.overlay\.HudSettings\.rows\(%s, box\);   // PEANUTS' % (ctx, ctx), f)
        sp = (NX / 'SidePanel.java').read_text()
        self.assertRegex(sp, r'dev\.peanuts\.ui\.PanelPolish\.apply\(box\);   // PEANUTS')
        g = (NX / 'GameActivity.java').read_text()
        self.assertRegex(g, r'dev\.peanuts\.overlay\.Hud\.driver\(.*\);\s+// PEANUTS')
        for f in ('Overlay.java', 'MainActivity.java', 'SidePanel.java', 'GameActivity.java'):
            for line in (NX / f).read_text().splitlines():
                if 'dev.peanuts.overlay.Hud' in line:
                    self.assertIn('PEANUTS', line, f)
        b = (PEA / 'Bridge.java').read_text()
        self.assertIn('public static native int frameTimes(float[] out);', b)
        self.assertIn('public static native void graphRect(int x, int y, int w, int h);', b)

    def test_graph_hook(self):
        n = (REPO / 'android' / 'app' / 'src' / 'main' / 'cpp' / 'nxlaunch.c').read_text()
        self.assertRegex(n, r'#pragma weak peanuts_ovl_block\s+// PEANUTS')
        self.assertRegex(n, r'if \(peanuts_ovl_block\) peanuts_ovl_block\(slot, \(void \*\)p, p \? w : 0, p \? h : 0, stride\);\s+// PEANUTS')
        self.assertLess(n.index('if (peanuts_ovl_block)'), n.index('return nxhost_overlay_set(slot'))
        self.assertRegex((REPO / 'android' / 'host' / 'libnxhost.map').read_text(), r'peanuts_ovl_block;\s+/\* PEANUTS')
        self.assertIn('peanuts_graph.c', (REPO / 'android' / 'host' / 'CMakeLists.txt').read_text())
        self.assertIn('pn_graph_frame();', (REPO / 'dm' / 'peanuts' / 'peanuts.c').read_text())
        v = (REPO / 'dm' / 'gfx' / 'vk_android' / 'vka_present.c').read_text()
        self.assertRegex(v, r'__attribute__\(\(weak\)\) int peanuts_ovl_corner\(int slot, int dflt\);\s+// PEANUTS')
        self.assertIn('(peanuts_ovl_corner ? peanuts_ovl_corner(s, g_ocorner[s]) : g_ocorner[s]) /* PEANUTS', v)
        self.assertIn('public static native void overlayCorner(int corner);', (PEA / 'Bridge.java').read_text())
        h = (OV / 'Hud.java').read_text()
        for k in ('"pn_overlay_corner"', '"pn_overlay_scale"', '{"tl", "tr", "bl", "br"}', 'Bridge.overlayCorner(c)', 'new RelativeSizeSpan(scale / 100f)'):
            self.assertIn(k, h)
        self.assertIn('Java_dev_peanuts_Bridge_overlayCorner', (REPO / 'dm' / 'peanuts' / 'peanuts_graph.c').read_text())
        self.assertIn('VKO_TOP_LEFT = 0, VKO_TOP_RIGHT = 1, VKO_BOTTOM_LEFT = 2, VKO_BOTTOM_RIGHT = 3', (REPO / 'dm' / 'gfx' / 'vk_android' / 'vka_ovl.h').read_text())
        self.assertIn('Java_dev_peanuts_Bridge_graphRect', (REPO / 'dm' / 'peanuts' / 'peanuts_graph.c').read_text())

    def test_peanuts_md(self):
        t = (REPO / 'PEANUTS.md').read_text()
        for w in ('Hud.extra', 'Hud.table', 'HudSettings.rows', 'Hud.driver', 'SidePanel.java', 'Bridge.frameTimes', 'peanuts_ovl_block', 'peanuts_graph.c', 'peanuts_ovl_corner'):
            self.assertIn(w, t)

    def test_label_table_matches_items(self):
        o = (NX / 'Overlay.java').read_text()
        block = o[o.index('ITEMS = {'):]
        block = block[:block.index('};')]
        items = re.findall(r'\{"(\w+)", "([^"]*)"\}', block)
        h = (OV / 'HudSettings.java').read_text()
        block = h[h.index('LABELS = {'):]
        block = block[:block.index('};')]
        labels = re.findall(r'\{"(\w+)", "([^"]*)"\}', block)
        self.assertEqual(sorted(items), sorted(labels))
        self.assertEqual(len(items), 10)

    def test_prefs_file_name(self):
        p = (NX / 'Prefs.java').read_text()
        m = re.search(r'static SharedPreferences of\(Context c\) \{ return open\(c, "(\w+)"\); \}', p)
        self.assertIsNotNone(m)
        self.assertIn('PREFS = "%s";' % m.group(1), (OV / 'Hud.java').read_text())
        self.assertIn('return c.getSharedPreferences(name, Context.MODE_MULTI_PROCESS)', p)
        self.assertIn('"overlay_" + key', p)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class FrameStatsJvm(unittest.TestCase):
    def test_stats(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(OV / 'FrameStats.java'), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.overlay.FrameStatsJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


@unittest.skipUnless(shutil.which('javac') and jar(), 'no JDK or android.jar')
class Compiles(unittest.TestCase):
    def test_android_classes(self):
        srcs = [OV / n for n in ('Hud.java', 'HudSettings.java', 'HistogramSpan.java', 'SocTemp.java', 'SocZones.java', 'FrameStats.java')] + [PEA / 'Bridge.java', PEA / 'ui/PanelPolish.java']
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:-deprecation', '-cp', str(jar()), '-d', d] + [str(s) for s in srcs], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)


if __name__ == '__main__':
    unittest.main()
