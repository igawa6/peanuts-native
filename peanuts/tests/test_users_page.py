"""Settings › Users (dev.peanuts.users): the profile pictures' file (a reference to a Mii or a firmware picture and a background, never pixels) and the decision
"Who's playing?" makes at a game's start, on the JVM with UserPicturesJvmTest; the firmware pictures' Yaz0 with Yaz0JvmTest. Skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
USERS = REPO / 'android/app/src/main/java/dev/peanuts/users'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/users/UserPicturesJvmTest.java'
SOURCES = [USERS / 'UserPictures.java', USERS / 'WhoPlays.java']


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class UsersJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, SOURCES), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.users.UserPicturesJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


class AlwaysSaysWhatItDoes(unittest.TestCase):
    """"Always X" when the app opens is the setting's Don't ask, which also stops the question before a game from a shortcut or a frontend (WhoPlays.decide,
    checked on the JVM above). The texts say so: the switch's line and the Who's playing? setting name both questions"""

    def setUp(self):
        self.activity = (USERS / 'WhoPlaysActivity.java').read_text(encoding='utf-8')
        self.page = (USERS / 'UsersPage.java').read_text(encoding='utf-8')

    def test_always_is_dont_ask(self):
        play = self.activity[self.activity.index('private void play()'):]
        self.assertIn('if (always) UsersPrefs.whoMode(this, WhoPlays.DONT_ASK);', play[:play.index('return;')])

    def test_the_switch_names_both_questions(self):
        self.assertIn('.value("Skips this question and the one before games from shortcuts or frontends")', self.activity)
        self.assertNotIn('"Skips this question when the app opens"', self.activity)

    def test_the_setting_names_both_questions(self):
        self.assertIn('return "Asks when the app opens and before games from shortcuts";', self.page)
        self.assertIn('{"Ask when the app opens and before games from shortcuts or frontends", "Don\'t ask"}', self.page)

    def test_the_question_for_one_game_keeps_its_own_line(self):
        self.assertIn('.value("Skips this question for this game")', self.activity)


YAZ0_TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/users/Yaz0JvmTest.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Yaz0Jvm(unittest.TestCase):
    """The firmware's profile pictures are Yaz0-packed: data packed by the test comes back, and a reference before the start, a size past the limit, another
    magic and an input that ends early are refused; nothing is written past the size the header gives"""

    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(USERS / 'Yaz0.java'), str(YAZ0_TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.users.Yaz0JvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('PASS', r.stdout)


class Sources(unittest.TestCase):
    def test_no_android_classes(self):
        for f in SOURCES + [USERS / 'Yaz0.java']:
            self.assertNotIn('import android.', f.read_text(encoding='utf-8'), f.name)


LIBRARY_SETTINGS = REPO / 'android/app/src/main/java/dev/peanuts/ui/LibrarySettings.java'


class FirmwareReadOnce(unittest.TestCase):
    """The firmware's profile pictures (AvatarImage) are read with the fonts. A firmware read before the app took them is read again once; one this importer has
    read is not, even when it has no AvatarImage (it was read again at every cold start)"""

    def setUp(self):
        self.src = LIBRARY_SETTINGS.read_text(encoding='utf-8')

    def body(self, signature):
        start = self.src.index(signature)
        return self.src[start:self.src.index('\n    }\n', start)]

    def test_a_read_that_worked_is_kept(self):
        b = self.body('private static void extractFonts(')
        read = b.index('imp.firmwareFonts(source, out.getPath())')
        mark = b.index('if (r == 0) PeanutsPrefs.firmwareRead(a, FIRMWARE_READ);')
        self.assertLess(read, mark)

    def test_a_firmware_read_whole_is_not_read_again(self):
        b = self.body('public static void addMissingFonts(Activity a)')
        self.assertLess(b.index('if (PeanutsPrefs.firmwareRead(a) >= FIRMWARE_READ) return;'), b.index('extractFonts(a, src, null);'))

    def test_the_older_check_stays_for_a_firmware_read_before(self):
        """Without the mark (a firmware read by an older app), the files decide: the pictures' folder missing reads it once more"""
        b = self.body('public static void addMissingFonts(Activity a)')
        self.assertIn('dev.peanuts.users.FirmwarePictures.dir(a).isDirectory()', b)


if __name__ == '__main__':
    unittest.main()
