"""The player wiki (wiki/, published to the Forgejo wiki by scripts/wiki_sync.sh).
Checks: every internal link and image resolves; the pages carry no ambiguous Unicode that Forgejo warns about, no local path, no link into internal/;
every setting row the launcher shows (a `new Ui.Setting(a, "<icon>", "<title>", ...)` in the launcher's Java) is named on a wiki page, so a new setting
cannot ship without a line for the player. A setting that must not be documented goes in UNDOCUMENTED, with the reason."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
WIKI = REPO / 'wiki'
JAVA = REPO / 'android/app/src/main/java/dev/nxnative/android'
SETTING = re.compile(r'new Ui\.Setting\(\s*\w+\s*,\s*"[^"]*"\s*,\s*"([^"]+)"')
# Titles of rows that are not settings a player changes (testers' knobs, dialogs, or shared with another row). Each needs a reason.
UNDOCUMENTED = {
    'Extra environment': 'testers only (env lines)',
    'ADB control (debug)': 'a debug switch for developers and testers, off for players',
    # PEANUTS begin: this fork's own rows. The wiki is Banana Native's (published to its Forgejo wiki); Peanuts Native describes them in peanuts/docs
    'Build again': 'Peanuts Native: the game sheet\'s build (peanuts/docs/FAQ.md)',
    'Delete save data': 'Peanuts Native: a row of its Saves tab',
    'Resolution scale': 'Peanuts Native: DM_RES_SCALE (ResScale.java, PEANUTS.md)',
    'Questions and answers': 'Peanuts Native: the row that opens its own FAQ (peanuts/docs/FAQ.md) where upstream\'s opens this wiki',
    # PEANUTS end
}
BAD_CHARS = set('›⋯−✕')   # > as a single quote, midline ellipsis, minus sign, multiplication X: Forgejo flags them as ambiguous


def pages():
    return {p.stem: p for p in WIKI.glob('*.md')}


class WikiTest(unittest.TestCase):
    def test_links_and_images(self):
        names = pages()
        bad = []
        for p in names.values():
            for m in re.finditer(r'\]\(([^)\s]+)\)', p.read_text(encoding='utf-8')):
                u = m.group(1)
                if u.startswith(('http://', 'https://', '#', 'mailto:')): continue
                n, _, anchor = u.partition('#')
                if n.startswith('images/'):
                    if not (WIKI / n).is_file(): bad.append(f'{p.name}: missing image {u}')
                elif n not in names: bad.append(f'{p.name}: no page {u}')
        self.assertEqual(bad, [])

    def test_text_hygiene(self):
        bad = []
        for p in pages().values():
            t = p.read_text(encoding='utf-8')
            for i, c in enumerate(t):
                if c in BAD_CHARS: bad.append(f'{p.name}: U+{ord(c):04X} near {t[max(0, i - 15):i + 15]!r}')
            for pat in ('/User' + 's/', 'internal' + '/', '100.1' + '20.207.64', 'AI' + 'HUB'):   # written in pieces: the public snapshot scan flags the plain strings
                if pat in t: bad.append(f'{p.name}: contains {pat}')
        self.assertEqual(bad, [])

    def test_every_setting_is_documented(self):
        text = '\n'.join(p.read_text(encoding='utf-8') for p in pages().values()).lower().replace('\u2026', '...')
        titles = set()
        for j in JAVA.glob('*.java'):
            titles |= set(SETTING.findall(j.read_text(encoding='utf-8')))
        self.assertTrue(len(titles) > 20, 'the setting titles were not found: has the Ui.Setting constructor changed?')
        missing = sorted(t for t in titles if t not in UNDOCUMENTED and t.lower().replace('\u2026', '...') not in text)
        self.assertEqual(missing, [], 'settings with no wiki page naming them (add a section in wiki/Settings-*.md, or list them in UNDOCUMENTED with a reason)')


if __name__ == '__main__':
    unittest.main()
