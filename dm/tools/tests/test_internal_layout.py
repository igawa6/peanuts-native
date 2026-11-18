"""The private notes folder (internal/, kept out of public updates) has a dateless layout: topics/ (living files with a Status line), tools/, history/ (the only
place for dated files) and README.md, which lists every topic. Skipped where internal/ is absent (the public repository). Only tracked files count, so a session's
unsaved note does not fail the check until it is committed."""
import re
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
INT = REPO / 'internal'
DATE = re.compile(r'\d{4}-\d{2}-\d{2}')
STATUS = re.compile(r'^Status: (checked against \S.*|unverified.*)$', re.M)


def tracked():
    out = subprocess.run(['git', 'ls-files', 'internal'], cwd=REPO, capture_output=True, text=True).stdout.split('\n')
    return [Path(p) for p in out if p]


@unittest.skipUnless(INT.is_dir() and (REPO / '.git').exists(), 'no internal/ folder (public repository)')
class Layout(unittest.TestCase):
    def test_places(self):
        for p in tracked():
            rel = p.relative_to('internal')
            top = rel.parts[0]
            if len(rel.parts) == 1: self.assertEqual(rel.name, 'README.md', f'{p}: only README.md sits in internal/; use topics/, tools/ or history/')
            else: self.assertIn(top, ('topics', 'tools', 'history'), f'{p}: not in topics/, tools/ or history/')

    def test_no_dates_outside_history(self):
        for p in tracked():
            rel = p.relative_to('internal')
            if rel.parts[0] != 'history': self.assertFalse(DATE.search(str(rel)), f'{p}: a date in the name outside history/ (a topic is rewritten in place; a dated note goes to history/)')

    def test_history_is_dated(self):
        for p in tracked():
            rel = p.relative_to('internal')
            if rel.parts[0] == 'history' and len(rel.parts) > 1: self.assertTrue(DATE.match(rel.parts[1]), f'{p}: history/ entries start with a date')

    def test_topics_have_status_and_index(self):
        readme = (INT / 'README.md').read_text(encoding='utf-8')
        for p in tracked():
            rel = p.relative_to('internal')
            if rel.parts[0] == 'topics' and p.suffix == '.md':
                head = '\n'.join((REPO / p).read_text(encoding='utf-8').split('\n')[:8])
                self.assertTrue(STATUS.search(head), f'{p}: no "Status: checked against <release>" or "Status: unverified (why)" line in its first lines')
                self.assertIn(f'topics/{rel.name}', readme, f'{p}: not listed in internal/README.md')


if __name__ == '__main__':
    unittest.main()
