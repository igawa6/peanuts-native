"""The app side of the in-place shader upgrade (2026-11-24, ~/pn-build/xver-upgrade-spec.md): android/app/src/main/java/dev/peanuts/build/ShaderUpgrade.java
(the decision and the tool's calls, ShaderUpgradeJvmTest on the JVM), ShaderUpgradeUi.java (before a game starts), PackService MSG_UPGRADE (the packer's
process runs dm/gfx/tools/vk_android_upgrade.py) and the unchecked marker a dropped full check leaves (CheckDue, critique B1). Android is needed to run the
wiring, so these tests read it; the APK's tools.zip must carry the tool. No game files. The JVM part is skipped without a JDK."""
import fnmatch
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UP = APP / 'main/java/dev/peanuts/build/ShaderUpgrade.java'
UI = APP / 'main/java/dev/peanuts/build/ShaderUpgradeUi.java'
DUE = APP / 'main/java/dev/peanuts/build/CheckDue.java'
PACK = APP / 'ondevice/java/dev/nxnative/android/PackService.java'
MAIN = APP / 'main/java/dev/nxnative/android/MainActivity.java'
GRADLE = REPO / 'android/app/build.gradle'
TOOL = REPO / 'dm/gfx/tools/vk_android_upgrade.py'
TEST = APP / 'jvmtest/java/dev/peanuts/build/ShaderUpgradeJvmTest.java'


def read(p): return p.read_text(encoding='utf-8')


def java_string(src, name):
    m = re.search(r'\b' + name + r' = "([^"]*)"', src)
    assert m, name
    return m.group(1)


def method(src, head):
    i = src.index(head)
    return src[i:src.index('\n    }\n', i)]


class Packaged(unittest.TestCase):
    """The tool reaches the phone the way vk_android_build.py does: in assets/pack/tools.zip (build.gradle nxPackTools), unpacked to repo/ and run by path"""
    def tools_globs(self):
        g = read(GRADLE)
        block = g[g.index("def toolsZip = tasks.register('nxPackTools', Zip) {"):]
        block = block[:block.index("archiveFileName = 'tools.zip'")]
        inc = [x for line in re.findall(r"^\s*include (.+)$", block, re.M) for x in re.findall(r"'([^']+)'", line)]
        exc = [x for line in re.findall(r"^\s*exclude (.+)$", block, re.M) for x in re.findall(r"'([^']+)'", line)]
        return inc, exc

    def packaged(self, rel):
        inc, exc = self.tools_globs()
        # gradle's '*' does not cross '/', '**' does
        def match(pat):
            rx = re.escape(pat).replace(r'\*\*/', '(?:.*/)?').replace(r'\*\*', '.*').replace(r'\*', '[^/]*')
            return re.fullmatch(rx, rel) is not None
        return any(match(p) for p in inc) and not any(match(p) for p in exc)

    def test_the_tool_is_in_tools_zip_like_the_build_tool(self):
        rel = java_string(read(UP), 'TOOL')
        self.assertEqual(rel, 'dm/gfx/tools/vk_android_upgrade.py')
        self.assertTrue(self.packaged('dm/gfx/tools/vk_android_build.py'))
        self.assertTrue(self.packaged(rel), 'build.gradle nxPackTools must take ' + rel)
        self.assertFalse(self.packaged('dm/gfx/tools/__pycache__/vk_android_upgrade.cpython-314.pyc'))

    def test_the_packer_runs_it_by_that_path(self):
        up = method(read(PACK), '    private void upgrade(Message m) {')
        self.assertIn('engine.run(dev.peanuts.build.ShaderUpgrade.TOOL, argv, log.getPath())', up)

    @unittest.skipUnless(TOOL.is_file(), 'vk_android_upgrade.py not written yet')
    def test_the_tool_is_where_the_app_looks(self):
        t = read(TOOL)
        self.assertIn("'--path'", t)
        self.assertRegex(t, r"UNCHECKED = '\.unchecked'")   # the marker the app writes (ShaderUpgrade.UNCHECKED)
        self.assertEqual(java_string(read(DUE), 'UNCHECKED'), '.unchecked')   # CheckDue writes it, ShaderUpgrade.UNCHECKED is it
        self.assertIn('return 3', t)
        self.assertIn('progress: ', t)


class Decision(unittest.TestCase):
    def test_exit_codes_are_the_tools(self):
        s = read(UP)
        self.assertRegex(s, r'UPGRADED = 0, ERROR = 1, NO_PATH = 3, BUSY = -2;')

    def test_nothing_hard_codes_a_version(self):
        # the versions come from BuildConfig.NX_DMSH_VK_CHAIN and the tool's --path answer, never from the source
        for p in (UP, UI):
            s = read(p)
            self.assertNotRegex(s, r'"[0-9a-f]{12}"', p.name)
        self.assertIn('BuildConfig.NX_DMSH_VK_CHAIN', read(UI))


class Wiring(unittest.TestCase):
    def test_launch_upgrades_before_its_shader_check(self):
        s = read(MAIN)
        launch = s[s.index('    void launch(Game card) {'):s.index('    private void launchShaders(')]
        self.assertRegex(launch, r'if \(dev\.peanuts\.build\.ShaderUpgradeUi\.before\(this, g\.titleId, new File\(g\.packDir\), shaderInputs\(g\.titleId\), true, \(\) -> launchShaders\(g, gd, dump, pickPack\)\)\) return;\s*launchShaders\(g, gd, dump, pickPack\);')
        self.assertIn('return p == null ? null : p.shaderInputs;', method(s, '    private static String shaderInputs(String titleId) {'))
        rest = method(s, '    private void launchShaders(')
        self.assertLess(rest.index('isFinishing()'), rest.index('Pack.libraryProblem('))   # the old path unchanged after it
        self.assertIn('Ondevice.autoUpdate(this, gd, new File(g.packDir), g.titleId);', rest)
        self.assertIn('ShaderUpgradeUi.before(this, g.titleId, new File(g.packDir), shaderInputs(g.titleId), false, sheet)', method(s, '    void updateShaders(Game g) {'))   # no "the game starts" there

    def test_a_killed_upgrades_file_goes_before_restamp_and_upgrade(self):
        s = read(MAIN)
        chk = method(s, '    private void check(Game card, TextView st, Ui.Action play, LinearLayout info) {')
        self.assertLess(chk.index('ShaderUpgrade.cleanStale('), chk.index('Pack.restamp('))
        u = method(read(UI), '    public static boolean before(')
        self.assertLess(u.index('ShaderUpgrade.cleanStale('), u.index('ShaderUpgrade.candidates('))

    def test_a_second_start_under_the_dialog_is_dropped(self):
        u = method(read(UI), '    public static boolean before(')
        self.assertRegex(u, r'if \(running\) \{[^\n]*return true; \}')

    def test_message_ids_are_the_packers(self):
        p, u = read(PACK), read(UI)
        ids = re.search(r'static final int MSG_UPGRADE = (\d+), MSG_UPGRADE_DONE = (\d+);', p)
        self.assertTrue(ids)
        self.assertIn(f'Message.obtain(null, {ids.group(1)});   // PackService.MSG_UPGRADE', u)
        self.assertIn(f'if (m.what != {ids.group(2)}) return false;   // PackService.MSG_UPGRADE_DONE', u)
        self.assertRegex(p, r'case MSG_UPGRADE:[^\n]*\n\s*upgrade\(m\);\s*return true;')

    def test_one_tool_at_a_time(self):
        p = read(PACK)
        up = method(p, '    private void upgrade(Message m) {')
        self.assertIn('worker != null ? "a build or a full check runs"', up)
        self.assertIn('!upgrading.compareAndSet(false, true)', up)
        self.assertRegex(up, r'\} finally \{\s*upgrading\.set\(false\);')
        # a job's or a check's start during it waits (a job) or is left for later (a check), before any worker starts
        start = p[p.index('    @Override public int onStartCommand('):p.index('    @Override public void onTimeout(')]
        self.assertLess(start.index('if (upgrading.get()) {'), start.index('worker = new Thread(() -> checkKept(cj), "nxpack-check");'))
        self.assertLess(start.index('if (upgrading.get()) {'), start.index('worker = new Thread(this::work, "nxpack-job");'))
        self.assertIn('afterUpgrade.add(() -> {', start)
        self.assertIn('private static final List<Runnable> afterUpgrade = new CopyOnWriteArrayList<>();', p)   # every start that waited (review m6)
        self.assertRegex(up, r'List<Runnable> next = new ArrayList<>\(afterUpgrade\);\s*afterUpgrade\.clear\(\);\s*for \(Runnable n : next\) n\.run\(\);')
        # re-checked when it runs (review m9): a job with only the shaders left, which the upgrade made, is done instead of translating them
        held = start[start.index('afterUpgrade.add(() -> {'):]
        self.assertLess(held.index('Pack.libraryProblem(w.pack(), w.titleId()) == null && w.doneByUpgrade()'), held.index('startForegroundService(again)'))

    def test_the_build_settings_that_make_its_bytes(self):
        up = method(read(PACK), '    private void upgrade(Message m) {')
        self.assertIn('PackEngine.setenv("DMSH_VK_ZSTD_LEVEL", "6");', up)   # as the shaders step: the result must be a full build's byte for byte
        self.assertRegex(read(PACK), r'PackEngine\.setenv\("DMSH_VK_ZSTD_LEVEL", "6"\);   // PEANUTS: the library')
        self.assertIn('BuildConfig.NX_DMSH_VK_CHAIN, BuildConfig.NX_DMSH_VK_ABI', up)
        self.assertIn('PackEngine.setenv("DM_VK_UPGRADE_VALIDATE", "1");', up)   # review m1: spirv-val on the rewritten modules, the APK's SPIRV-Tools by ctypes
        self.assertIn('PackEngine.setenv("SPIRV_VAL", "libSPIRV-Tools-shared.so");', up)
        self.assertIn('PackEngine.setenv("DM_VK_UPGRADE_INPUTS", inputs);', up)   # review M2: the tool refuses another profile's library too
        self.assertIn('prof.shaderInputs', up)

    def test_a_done_job_takes_the_upgraded_version(self):
        # review M1: Home's panel says Ready, not Resume, after an upgrade
        up = method(read(PACK), '    private void upgrade(Message m) {')
        self.assertIn('if (code == dev.peanuts.build.ShaderUpgrade.UPGRADED) upgradedJobs(title, new File(pack));', up)
        jobs = method(read(PACK), '    private void upgradedJobs(String title, File pack) {')
        self.assertIn('Job.remembered(this)', jobs); self.assertIn('DumpCheck.workOf(', jobs); self.assertRegex(jobs, r'j\.takeUpgrade\(\)\) continue;\s*j\.save\(\);')
        job = read(APP / 'ondevice/java/dev/nxnative/android/Job.java')
        self.assertIn('ShaderUpgrade.onlyVkChainDiffers(a::optString, b::optString)', job)
        ap = method(job, '    synchronized boolean apkChanged() {')
        self.assertIn('{"engine", "dmsh_chain", "dmsh_vk_chain", "xlate_source"}', ap)   # the keys onlyVkChainDiffers knows
        self.assertIn('{"engine", "dmsh_chain", "xlate_source"}', read(UP))

    def test_fallbacks_go_on_as_before(self):
        u = read(UI)
        self.assertIn('then.run();', u)
        self.assertIn('WAIT_MS', u)
        self.assertIn('onServiceDisconnected', u)


class Unchecked(unittest.TestCase):
    """Critique B1: a library whose kept shaders a dropped check never checked is marked in the pack, so no upgrade carries it"""
    def test_every_drop_marks(self):
        p = read(PACK)
        self.assertRegex(p, r'CheckDue\.drop\(new File\(getNoBackupFilesDir\(\), "reuse/" \+ j\.titleId\(\)\), j\.pack\(\)\)')
        kept = method(p, '    private void checkKept(Job j) {')
        self.assertLess(kept.index('CheckDue.todo(rd)'), kept.index('CheckDue.died(rd)'))   # before anything can drop it
        self.assertLess(kept.index('CheckDue.ended('), kept.index('CheckDue.markIfDropped('))
        self.assertEqual(len(re.findall(r'CheckDue\.drop\(', p)), 1)

    def test_the_pack_step_takes_the_marker_into_the_pack(self):
        # review M3: shader_reuse.py marks the library it writes in the job's cache; the pack step copies the library, and the marker goes with it
        step = read(PACK)
        self.assertRegex(step, r'if \(s\.equals\("pack"\) && !checking\) \{[^\n]*\n[^\n]*\n\s*int n = dev\.peanuts\.build\.CheckDue\.copyMarker\(new File\(j\.shaderCache\(\), lv\), new File\(pack, "shaders/" \+ lv\)\);')

    def test_marker_names_agree(self):
        sys.path.insert(0, str(REPO / 'dm/peanuts/reuse'))
        try: import shader_reuse as sr
        finally: sys.path.remove(str(REPO / 'dm/peanuts/reuse'))
        self.assertEqual(sr.UNCHECKED, java_string(read(DUE), 'UNCHECKED'))
        if TOOL.is_file(): self.assertIn(f"UNCHECKED = '{sr.UNCHECKED}'", read(TOOL))

    def test_names_are_the_tools(self):
        sys.path.insert(0, str(REPO / 'dm/peanuts/reuse'))
        try: import shader_reuse as sr
        finally: sys.path.remove(str(REPO / 'dm/peanuts/reuse'))
        self.assertEqual(java_string(read(DUE), 'CHECKED'), sr.CHECKED)


class Words(unittest.TestCase):
    """build-text-style.md: what the player reads"""
    def test_player_text(self):
        u, s = read(UI), read(UP)
        self.assertEqual(java_string(s, 'TEXT'), "Updating the game's shaders for this app version")
        shown = [java_string(s, 'TEXT')] + re.findall(r'PUi\.(?:body|small|sheet)\(a, "([^"]+)"', u) + re.findall(r'"(This [^"]+)"', s) + [" Then the game starts."]
        self.assertGreaterEqual(len(shown), 4)
        self.assertNotIn('a few seconds. The game starts right after', u + s)
        pack = read(APP / 'main/java/dev/nxnative/android/Pack.java')
        shown += re.findall(r'return "([^"]+)";', method(pack, '    static String libraryText(File dir, String titleId) {'))
        for t in shown:
            for bad in ('—', '–', '·', '!', 'library', 'packer', 'job', 'tool', 'chain', 'APK', 'pack ', '/'):
                self.assertNotIn(bad, t, t)

    def test_adding_a_game_says_it_plainly(self):
        # the toast after adding a game with a build folder and the Build folder set message: no folder path, no "library" on the main line
        m = read(MAIN)
        add = method(m, '    private boolean addWithPack(File game, String id, File packDir, List<String> replace) {')
        self.assertIn('note += " " + Pack.libraryText(packDir, id);', add)
        self.assertNotIn('note += " " + lp;', add)
        f = read(APP / 'main/java/dev/nxnative/android/Folders.java')
        self.assertNotIn('setMessage(dir + "\\n\\n"', f)
        self.assertIn('Ui.explain(a, "Build folder set", lp == null ? "It has this app version\'s shaders. The game is ready to play." : Pack.libraryText(dir, p.titleId),', f)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), str(UP), str(DUE), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.build.ShaderUpgradeJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('ok', r.stdout)
            # a library's id is shader_reuse.py's, which the marker lines hold (Java reads them, Python writes them)
            sys.path.insert(0, str(REPO / 'dm/gfx/tools')); sys.path.insert(0, str(REPO / 'dm/peanuts/reuse'))
            try:
                import struct, vk_android_delta as delta, shader_reuse as sr
            finally: sys.path.pop(0); sys.path.pop(0)
            head = delta.HEAD.pack(delta.VK['LIB_MAGIC'], delta.VK['LIB_VERSION'], b'c' * 12, b'b' * 12, b'0100ABCD00000000', b'0123456789ab', 0, 128, 0, 128, 0, 128,
                                   128, 64, 32, 256, b'1' * 12)
            lib = d / 'id' / 'dmsh_vk.lib'; lib.parent.mkdir(); lib.write_bytes(head + bytes(range(64)))
            L = delta.Library(str(lib))
            try: want = sr.library_id(L)
            finally: L.close()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.build.ShaderUpgradeJvmTest', '--id', str(lib)], capture_output=True, text=True)
            self.assertEqual(r.stdout.strip(), want)
            if TOOL.is_file():
                sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
                try: import vk_android_upgrade as U
                finally: sys.path.pop(0)
                self.assertEqual(U.library_id(str(lib)), want)


if __name__ == '__main__':
    unittest.main()
