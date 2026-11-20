"""No "·" between values in Peanuts' own screens: PUi.joined draws a thin bar instead, and skips parts that are not there."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
OWN = ROOT / 'android/app/src/main/java/dev/peanuts'
BS, MID = chr(92), chr(0xB7)   # a backslash and the middle dot, spelled out so no editor turns them into something else
DOT = re.compile('"(?:[^"' + BS + BS + ']|' + BS + BS + '.)*(?:' + MID + '|' + BS + BS + 'u00[bB]7)(?:[^"' + BS + BS + ']|' + BS + BS + '.)*"')


class Separators(unittest.TestCase):
    def test_no_middle_dot_in_string_literals(self):
        hits = []
        for f in sorted(OWN.rglob('*.java')):
            for n, line in enumerate(f.read_text(encoding='utf-8').splitlines(), 1):
                code = line.split('//', 1)[0]
                if not code.lstrip().startswith(('*', '/*')) and DOT.search(code):
                    hits.append(f'{f.relative_to(ROOT)}:{n}')
        self.assertEqual(hits, [], 'use PUi.joined(...) instead of " · "')

    def test_joined_skips_missing_parts(self):
        src = (OWN / 'ui/PUi.java').read_text(encoding='utf-8')
        body = src[src.index('public static CharSequence joined('):]
        self.assertIn('if (parts[i] == null || parts[i].length() == 0) continue;', body[:600])
        self.assertIn('if (s.length() > 0) {', body[:600])


if __name__ == '__main__':
    unittest.main()
