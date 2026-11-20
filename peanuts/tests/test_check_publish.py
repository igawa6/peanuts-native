"""check-publish.sh's base: upstream/android (the maintainer's clone), else upstream/main (a contributor's), else a message."""
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, 'peanuts', 'scripts', 'check-publish.sh')


@unittest.skipUnless(shutil.which('git') and shutil.which('sh'), 'needs git and sh')
class Base(unittest.TestCase):
    def setUp(self):
        self.repo = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Tester')
        self.git('config', 'user.email', 'tester@example.org')
        os.makedirs(os.path.join(self.repo, 'peanuts', 'scripts'))
        shutil.copy(SCRIPT, os.path.join(self.repo, 'peanuts', 'scripts', 'check-publish.sh'))
        self.commit('base.txt', 'upstream')
        self.base = self.git('rev-parse', 'HEAD')
        self.commit('mine.txt', 'a contributor')

    def git(self, *a):
        return subprocess.run(['git', *a], cwd=self.repo, check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, name, text):
        with open(os.path.join(self.repo, name), 'w', encoding='utf-8') as f:
            f.write(text + '\n')
        self.git('add', '-A')
        self.git('commit', '-q', '-m', name)

    def check(self):
        return subprocess.run(['sh', 'peanuts/scripts/check-publish.sh'], cwd=self.repo, capture_output=True, text=True)

    def test_upstream_main(self):
        self.git('update-ref', 'refs/remotes/upstream/main', self.base)
        r = self.check()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn(self.base, r.stdout)

    def test_upstream_android_first(self):
        self.git('update-ref', 'refs/remotes/upstream/main', 'HEAD')
        self.git('update-ref', 'refs/remotes/upstream/android', self.base)
        r = self.check()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn(self.base, r.stdout)

    def test_neither(self):
        r = self.check()
        self.assertEqual(r.returncode, 1)
        self.assertIn('neither upstream/android nor upstream/main', r.stdout)


if __name__ == '__main__':
    unittest.main()
