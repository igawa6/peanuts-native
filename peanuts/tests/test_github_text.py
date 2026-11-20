"""Text from GitHub as the app shows it (dev.peanuts.ui.GitHubText: Markdown and its HTML as blocks and one plain line) and a companion release's notes
(dev.peanuts.ui.CompanionNotes: the game versions as a fact, without Eden Duo's install steps and the checksum line). javac compiles both classes (no
Android types) with android/app/src/jvmtest's GitHubTextJvmTest. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/GitHubTextJvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Text(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='githubtext_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t,
                            str(UI / 'GitHubText.java'), str(UI / 'CompanionNotes.java'), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.GitHubTextJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_versions_use_it(self):
        src = (UI / 'CompanionVersions.java').read_text(encoding='utf-8')
        self.assertIn('CompanionNotes.summary(item.notes)', src)
        self.assertIn('CompanionNotes.body(item.notes)', src)
        self.assertIn('GitHubTextStyle.view(', src)

    def test_no_api_34_matcher_calls(self):
        # Matcher.appendReplacement/appendTail(StringBuilder) and replaceAll(Function) are API 34; minSdk is 33 (UPSTREAM-EDITS round 12)
        for f in ('GitHubText.java', 'CompanionNotes.java'):
            src = (UI / f).read_text(encoding='utf-8')
            self.assertNotIn('new StringBuilder();\n        while (m.find())', src, f)
            self.assertNotRegex(src, r'replaceAll\(\s*(m|r|x)\s*->', f)


if __name__ == '__main__':
    unittest.main()
