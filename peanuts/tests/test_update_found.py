"""android/app/src/main/java/dev/peanuts/build/UpdateFound.java (issue #120): a built game with a newer update or new add-ons beside its .nup asks before it takes
them in (UpdateFoundJvmTest on the JVM), and the library uses it on the cover. The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
SRC = APP / 'main/java/dev/peanuts/build/UpdateFound.java'
TEST = APP / 'jvmtest/java/dev/peanuts/build/UpdateFoundJvmTest.java'


class Wiring(unittest.TestCase):
    def test_the_cover_asks(self):
        lib = (APP / 'main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        self.assertIn('UpdateFound.of(', lib)
        self.assertIn('askFound(', lib)
        self.assertIn('k.update = UpdateFound.what(', lib)   # the bottom screen's card says it too, and its A asks first
        panel = (APP / 'main/java/dev/peanuts/ui/BottomPanel.java').read_text(encoding='utf-8')
        self.assertIn('k.update != null ? k.update', panel)
        sheet = (APP / 'main/java/dev/peanuts/ui/GameSheet.java').read_text(encoding='utf-8')
        self.assertIn('static void importAgain(Activity a, Library lib, LibraryScanner.Title t, CompanionPort.Item companion, Runnable started, UpdateFound.Found named)', sheet)
        self.assertIn('if (UpdateFound.takesWhatItNamed(named, had, p.update != null ? p.updateVersion : -1))', sheet)   # else Import again's confirmation
        self.assertIn('GameSheet.importAgain(a, this, r.title, CompanionPort.forTitle(catalog, r.id()), null, f))', lib)

    def test_the_marks_follow_the_text_rules(self):
        # UI-RULES 1: no separator glyph between values (the cover's mark and the second screen's card had a middle dot before "later")
        lib = (APP / 'main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        self.assertNotIn('· later', lib)
        self.assertIn('"Update later"', lib)
        self.assertIn('", for later"', lib)

    def test_the_cover_knows_the_profiles_updates(self):
        # the cover asks for a READY title, whose supportedBuilds the scanner never fills (a BUILD title's only): an update was never counted (found in review)
        lib = (APP / 'main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        self.assertIn('UpdateFound.of(parts, t.profileBuilds.keySet())', lib)
        self.assertNotIn('UpdateFound.of(parts, t.supportedBuilds', lib)
        scanner = (APP / 'main/java/dev/peanuts/ui/LibraryScanner.java').read_text(encoding='utf-8')
        self.assertRegex(scanner, r'else \{\s*VersionInfo vi = versionInfo\(t\.titleId\);\s*t\.profileBuilds = vi\.builds;[^\n]*\n\s*if \(t\.status == Status\.BUILD\)')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.build.UpdateFoundJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('ok', r.stdout)


if __name__ == '__main__':
    unittest.main()
