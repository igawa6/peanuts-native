"""Comments and notes say what they mean in words. A few pointed at the owner's design canvas instead ("the canvas's Konzept board
"Variante B"", igawa6's note on #315), which readers of the repository cannot see. This keeps such pointers out of Peanuts' own code, resources,
scripts and notes. A canvas that draws (android.graphics.Canvas) is not one."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
POINTER = re.compile(r'canvas "|canvas\'s board|\bboard "|the board\'s|page Konzept|Variante|App-Look|claude\.ai/(?:artifact|code/artifact)')


def ours():
    for src in APP.iterdir():
        java = src / 'java/dev/peanuts'
        if java.is_dir():
            yield from sorted(java.rglob('*.java'))
    yield from sorted((APP / 'peanuts/res').rglob('*.xml'))
    yield from sorted((APP / 'jvmtest/java/dev/peanuts').rglob('*.java'))
    for d in ('scripts', 'tests', 'docs'):
        yield from sorted(p for p in (REPO / 'peanuts' / d).rglob('*') if p.suffix in ('.py', '.md', '.sh'))
    yield REPO / 'PEANUTS.md'
    yield REPO / 'peanuts/UPSTREAM-EDITS.md'


class Pointers(unittest.TestCase):
    def test_files_are_found(self):
        names = {p.name for p in ours()}
        for n in ('SettingsPage.java', 'SettingsSections.java', 'AboutPage.java', 'PEANUTS.md', 'UPSTREAM-EDITS.md'):
            self.assertIn(n, names)

    def test_no_pointer_to_the_design_canvas(self):
        me = Path(__file__).resolve()
        found = []
        for p in ours():
            if p.resolve() == me:
                continue
            for i, line in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
                if POINTER.search(line):
                    found.append(f'{p.relative_to(REPO).as_posix()}:{i}: {line.strip()[:120]}')
        self.assertEqual(found, [], 'say what the design means in words:\n' + '\n'.join(found))


if __name__ == '__main__':
    unittest.main()
