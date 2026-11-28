"""PEANUTS (issue #120): the rewrite beside the split and the shaders (RewriteBesidePlan.java, android/app/src/ondevice; PackService runs it through RewriteBeside):
only where the memory plan gives the full worker count (14 GB and more), never after a low-memory kill, for the full check or with the simulated engine, never
without a worker program, and only while the split or the shaders are still to do; PN_REWRITE_BESIDE=0 turns it off. The command writes its pid and becomes the
worker running the rewrite step's own tool and argv. PackService waits for it before the pack step. Skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/RewriteBesidePlan.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/RewriteBesidePlanJvmTest.java'
SERVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Plan(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='rewrite_beside_'))
        subprocess.run(['javac', '-d', str(cls.tmp / 'classes'), str(SRC), str(TEST)], check=True)
        r = subprocess.run(['java', '-cp', str(cls.tmp / 'classes'), 'dev.nxnative.android.RewriteBesidePlanJvmTest'], capture_output=True, text=True, check=True)
        cls.out = dict(l.split(' ', 1) for l in r.stdout.splitlines())

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_only_with_the_memory_for_both(self):
        self.assertEqual(self.out['16gb'], 'true')
        self.assertEqual(self.out['16gb_on'], 'true')
        for k in ('12gb', '8gb', 'killed_before'):
            self.assertEqual(self.out[k], 'false', k)

    def test_never_for_the_check_the_fake_engine_or_without_python(self):
        for k in ('checking', 'simulated', 'no_python', '16gb_off'):
            self.assertEqual(self.out[k], 'false', k)

    def test_only_with_something_to_run_beside(self):
        self.assertEqual(self.out['split_done'], 'true')   # the shaders are still to do
        self.assertEqual(self.out['both_done'], 'false')

    def test_command(self):
        self.assertEqual(self.out['cmd_shell'], '/system/bin/sh|-c|/w/ondevice/rewrite-beside.pid')
        self.assertEqual(self.out['cmd_worker'], '/system/bin/linker64|/data/x/python/bin/nxpack_worker|-c')
        self.assertEqual(self.out['cmd_tool'], '/repo/scripts/pack_android.py|/g.nup,--version,65536,--code-only')
        self.assertEqual(self.out['cmd_boot_runs_tool'], 'true')
        self.assertEqual(self.out['cmd_no_worker'], 'null')


class Service(unittest.TestCase):
    """PackService's wiring, read from its source: the step answers BESIDE, the loop goes on past it and waits for the rewrite before the pack, a stop ends it"""
    src = SERVICE.read_text(encoding='utf-8')

    def test_wiring(self):
        self.assertIn('RewriteBesidePlan.allowed(checking, engine.simulated(), pyLib, mem.workers, j.memLevel()', self.src)
        self.assertIn('if (fail == BESIDE) { save(j, true); continue; }', self.src)
        self.assertIn('String fail = joining ? joinBeside(j) : step(j, i);', self.src)
        self.assertRegex(self.src, r'while \(\(i = nextStep\(j\)\) >= 0\)')
        self.assertIn('stopBeside(j);', self.src)
        self.assertIn('RewriteBeside.stopLeftover(besidePid(j));', self.src)
        self.assertIn('PackEngine.setenv("PN_REWRITE_BESIDE_DONE", beside != null ? RewriteBeside.doneFile(besidePid(j)).getPath() : "")', self.src)

    def test_next_step_keeps_the_pack_after_the_rewrite(self):
        body = re.search(r'private int nextStep\(Job j\) \{(.*?)\n    \}', self.src, re.S).group(1)
        self.assertIn('!Job.STEPS[k].equals("pack")', body)


if __name__ == '__main__':
    unittest.main()
