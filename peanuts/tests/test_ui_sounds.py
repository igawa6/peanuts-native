"""The app's feedback sounds (dev.peanuts.ui.Sounds, the tones in UiTones; on the JVM with android/app/src/jvmtest's UiTonesJvmTest, skipped without a
JDK): one place plays them, every feedback word of the launcher (Haptics) has its tone, the in-game menus play theirs through it, and one setting (Settings ›
App › Feel › Sounds, the in-game menu's Menu sounds row) turns them on and off. The checks below read the sources: what must stay wired."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
TEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/UiTonesJvmTest.java'


def method(src, signature):
    """The body of the method whose declaration contains signature (up to the closing brace at four spaces)"""
    i = src.index(signature)
    j = src.index('\n    }\n', i)
    return src[i:j]


def member(src, signature):
    """A method's body, whether it spans lines or stands on its one line"""
    i = src.index(signature)
    line = src[i:src.index('\n', i)]
    return method(src, signature) if line.rstrip().endswith('{') else line


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Tones(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='uitones_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'UiTones.java'), str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.UiTonesJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def setUp(self):
        self.sounds = (UI / 'Sounds.java').read_text(encoding='utf-8')
        self.haptics = (UI / 'Haptics.java').read_text(encoding='utf-8')
        self.menu = (UI / 'MenuFeel.java').read_text(encoding='utf-8')

    def test_every_word_is_heard(self):
        play = method(self.haptics, 'private static void play(View v, Word w, int viewConstant, boolean sound) {')
        self.assertIn('if (sound) Sounds.play(v.getContext(), Sounds.of(w));', play)
        self.assertLess(play.index('Sounds.play('), play.index('if (off || systemOff'), 'the haptics switch does not silence the sounds')
        self.assertIn('static void play(View v, Word w) { play(v, w, constant(w), true); }', self.haptics)
        self.assertIn('static void buzz(View v, Word w) { play(v, w, constant(w), false); }', self.haptics)
        self.assertIn('Sounds.refresh(c);', method(self.haptics, 'static void refresh(Context c) {'))
        of = method(self.sounds, 'static UiTones of(Haptics.Word w) {')
        for word, tone in (('TICK', 'MOVE'), ('CLICK', 'SELECT'), ('HEAVY', 'HEAVY'), ('EDGE', 'EDGE'), ('DONE', 'DONE')):
            self.assertIn(f'case {word}: return UiTones.{tone};', of)
        self.assertIn('default: return UiTones.REJECT;', of)

    def test_one_place(self):
        for f in UI.parent.parent.rglob('*.java'):
            if f.name in ('Sounds.java',):
                continue
            self.assertNotIn('new AudioTrack.Builder()', f.read_text(encoding='utf-8'), f'{f.name}: the app\'s sounds are made in Sounds only')

    def test_the_menus_through_it(self):
        self.assertNotIn('enum Tone', self.menu)
        for call, tone in (('move', 'MOVE'), ('select', 'SELECT'), ('tab', 'TAB'), ('open', 'OPEN'), ('back', 'BACK'), ('edge', 'EDGE')):
            self.assertIn(f'Sounds.play(v.getContext(), UiTones.{tone})', member(self.menu, f'public static void {call}(View v)'), call)
        self.assertNotIn('Haptics.play(', self.menu, 'the menus buzz (their own Vibrate switch); their tones come once, from MenuFeel')
        self.assertIn('public static boolean sounds(Context c) { return Sounds.on(c); }', self.menu)
        self.assertIn('public static void sounds(Context c, boolean on) { Sounds.on(c, on); }', self.menu)

    def test_rules(self):
        self.assertIn('return prefs(c).getBoolean(KEY, true);', self.sounds, 'on by default')
        self.assertIn('FILE = "peanuts_panel", KEY = "menu_sounds"', self.sounds, 'the in-game menu\'s own setting')
        self.assertIn('Context.MODE_MULTI_PROCESS', self.sounds, 'read again: the game\'s process writes it too')
        play = method(self.sounds, 'static void play(Context c, UiTones t) {')
        self.assertIn('if (!on || systemOff || silent || (reader && t.light())) return;', play)
        refresh = method(self.sounds, 'public static void refresh(Context c) {')
        self.assertIn('Settings.System.SOUND_EFFECTS_ENABLED', refresh, "Android's Touch sounds")
        self.assertIn('am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL', refresh, 'silent or vibrate')
        self.assertIn('USAGE_ASSISTANCE_SONIFICATION', self.sounds)

    def test_settings(self):
        lib = (UI / 'LibrarySettings.java').read_text(encoding='utf-8')
        self.assertIn('soundsRow = new PUi.Setting(a, "", "Sounds", v -> {', lib)
        self.assertIn('Sounds.on(a, !Sounds.on(a));', lib)
        sections = (UI / 'SettingsSections.java').read_text(encoding='utf-8')
        self.assertIn('new Group("Feel", r("Haptics", "controller"), r("Motion", "play"), r("Sounds", "sound")),', sections)
        self.assertIn('put(m, "Sounds", ', (UI / 'SettingsHelp.java').read_text(encoding='utf-8'))
        self.assertIn('"sound":', (REPO / 'peanuts/scripts/icons.py').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
