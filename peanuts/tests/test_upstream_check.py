"""upstream-update.sh --check: hunks inside two PEANUTS begin/end spans pass, an unmarked hunk fails, on the system awk.

With two or more spans the old script handed them to `awk -v`, and macOS's awk rejects a value holding a newline, so
a fully marked file was reported as unmarked.
"""
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, 'peanuts', 'scripts', 'upstream-update.sh')

UPSTREAM = ['upstream line %d;' % i for i in range(1, 61)]
# (after this many upstream lines, the lines added there). Five unchanged lines between edits keep each in its own
# -U1 hunk, so the two inner hooks are covered only by their spans, not by a marker inside the hunk.
SPANS = [
    (5, ['// PEANUTS begin: first hook']),
    (10, ['hook_one();']),
    (15, ['// PEANUTS end']),
    (25, ['// PEANUTS begin: second hook']),
    (30, ['hook_two();']),
    (35, ['// PEANUTS end']),
]
STRAY = [(50, ['stray();'])]


def edited(inserts):
    out = list(UPSTREAM)
    for at, added in sorted(inserts, reverse=True):
        out[at:at] = added
    return out


@unittest.skipUnless(shutil.which('git') and shutil.which('sh') and shutil.which('awk'), 'needs git, sh and awk')
class UpstreamCheck(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Tester')
        self.git('config', 'user.email', 'tester@example.org')
        os.makedirs(os.path.join(self.repo, 'peanuts', 'scripts'))
        shutil.copy(SCRIPT, os.path.join(self.repo, 'peanuts', 'scripts', 'upstream-update.sh'))
        self.write('marked.c', UPSTREAM)
        self.write('unmarked.c', UPSTREAM)
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'upstream')
        self.git('update-ref', 'refs/remotes/upstream/android', 'HEAD')

    def git(self, *a):
        return subprocess.run(['git', *a], cwd=self.repo, check=True, capture_output=True, text=True).stdout.strip()

    def write(self, name, lines):
        with open(os.path.join(self.repo, name), 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines) + '\n')

    def commit(self, files):
        for name, lines in files.items():
            self.write(name, lines)
        self.git('add', '-A')
        self.git('commit', '-q', '-m', 'overlay')

    def check(self):
        return subprocess.run(['sh', 'peanuts/scripts/upstream-update.sh', '--check', 'upstream/android'],
                              cwd=self.repo, capture_output=True, text=True)

    def test_two_spans_pass(self):
        self.commit({'marked.c': edited(SPANS)})
        r = self.check()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('check passed', r.stdout)
        self.assertNotIn('newline in string', r.stderr)

    def test_unmarked_hunk_fails(self):
        self.commit({'marked.c': edited(SPANS), 'unmarked.c': edited(SPANS + STRAY)})
        r = self.check()
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('hunk(s) without a PEANUTS marker in unmarked.c', r.stdout)
        self.assertNotIn('marker in marked.c', r.stdout)
        self.assertNotIn('newline in string', r.stderr)


if __name__ == '__main__':
    unittest.main()
