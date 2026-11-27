"""upstream-update.sh --check: hunks inside two PEANUTS begin/end spans pass, an unmarked hunk fails, on the system awk.
Dropped upstream content (peanuts/upstream-dropped.txt, Switch 2 profiles): --check fails while it is tracked and accepts its deletion;
the rebase resolves an upstream edit of a dropped file as a deletion and removes a dropped file upstream adds.

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


DROPPED = '# header\ndm/games/04*\tSwitch 2 profiles\ndm/host/android/s2_hook.c\ta Switch 2 hook\n'


@unittest.skipUnless(shutil.which('git') and shutil.which('sh') and shutil.which('awk'), 'needs git, sh and awk')
class DroppedContent(unittest.TestCase):
    def setUp(self):
        top = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, top, ignore_errors=True)
        self.up = os.path.join(top, 'up')
        self.repo = os.path.join(top, 'pn')
        os.makedirs(self.up)
        self.git(self.up, 'init', '-q', '-b', 'android')
        self.files(self.up, {'dm/games/0100000000000000/game.tsv': 'name\tswitch 1\n', 'dm/games/0400000000000000/game.tsv': 'name\tswitch 2\n',
                             'dm/host/android/s2_hook.c': 'int hook;\n', 'dm/host/main.c': 'int main;\n'}, 'upstream 1')
        subprocess.run(['git', 'clone', '-q', '-o', 'upstream', self.up, self.repo], check=True, capture_output=True)
        self.git(self.repo, 'checkout', '-q', '-b', 'overlay')
        os.makedirs(os.path.join(self.repo, 'peanuts', 'scripts'))
        shutil.copy(SCRIPT, os.path.join(self.repo, 'peanuts', 'scripts', 'upstream-update.sh'))
        self.files(self.repo, {'peanuts/upstream-dropped.txt': DROPPED}, 'overlay: sync rules')

    def git(self, cwd, *a):
        for k, v in (('user.name', 'Tester'), ('user.email', 'tester@example.org')):
            subprocess.run(['git', 'config', k, v], cwd=cwd, check=False, capture_output=True)
        return subprocess.run(['git', *a], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()

    def files(self, cwd, files, msg):
        for name, text in files.items():
            path = os.path.join(cwd, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(text)
        self.git(cwd, 'add', '-A')
        self.git(cwd, 'commit', '-q', '-m', msg)

    def drop(self):
        self.git(self.repo, 'rm', '-q', '-r', 'dm/games/0400000000000000', 'dm/host/android/s2_hook.c')
        self.git(self.repo, 'commit', '-q', '-m', 'overlay: drop Switch 2')

    def run_script(self, *a):
        return subprocess.run(['sh', 'peanuts/scripts/upstream-update.sh', *a], cwd=self.repo, capture_output=True, text=True)

    def test_check_fails_while_dropped_content_is_tracked(self):
        r = self.run_script('--check', 'upstream/android')
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn('2 tracked file(s) match peanuts/upstream-dropped.txt', r.stdout)

    def test_check_accepts_the_deletion(self):
        self.drop()
        r = self.run_script('--check', 'upstream/android')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('check passed', r.stdout)

    def test_rebase_keeps_dropped_content_out(self):
        self.drop()
        # upstream edits a dropped profile and the hook (a modify/delete stop each), adds a new Switch 2 profile and edits a kept file
        self.files(self.up, {'dm/games/0400000000000000/game.tsv': 'name\tswitch 2 v2\n', 'dm/host/android/s2_hook.c': 'int hook2;\n',
                             'dm/games/0400000000001000/game.tsv': 'name\tnew switch 2\n', 'dm/host/main.c': 'int main2;\n'}, 'upstream 2')
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        tracked = self.git(self.repo, 'ls-files').split('\n')
        self.assertIn('dm/games/0100000000000000/game.tsv', tracked)
        self.assertFalse([f for f in tracked if f.startswith('dm/games/04') or f.endswith('s2_hook.c')], tracked)
        with open(os.path.join(self.repo, 'dm/host/main.c'), encoding='utf-8') as f:
            self.assertEqual(f.read(), 'int main2;\n')
        self.assertIn('dropped after the rebase', r.stdout)
        self.assertEqual(self.run_script('--check', 'upstream/android').returncode, 0)


if __name__ == '__main__':
    unittest.main()
