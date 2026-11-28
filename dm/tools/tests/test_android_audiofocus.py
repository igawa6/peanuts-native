"""The game's audio focus on Android (AudioFocus.java with android/app/src/jvmtest's AudioFocusJvmTest): a permanent loss (another app playing while the game is minimized)
gets no gain from Android, so the return to the game asks for the focus again; otherwise the sound stayed suspended (Native.audioInterrupt) with the game running.
The JVM part is skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android/AudioFocus.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/AudioFocusJvmTest.java'
ACTIVITY = REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java'
PREFS = REPO / 'android/app/src/main/java/dev/peanuts/PeanutsPrefs.java'   # PEANUTS: Play with other apps' sound (#819)


class Wiring(unittest.TestCase):
    def test_activity_uses_it(self):
        t = ACTIVITY.read_text(encoding='utf-8')
        self.assertIn('Native.audioInterrupt(focusState.changed(f))', t)   # the listener goes through AudioFocus
        self.assertIn('focusState.requested(g)', t)                          # every request's answer is recorded
        start = t[t.index('protected void onStart()'):t.index('protected void onResume()')]
        self.assertIn('focusState.askAgain()', start)                        # the return asks again
        self.assertLess(start.index('focusState.askAgain()'), start.index('Native.lifecycle(2)'))   # before the host resumes the sound

    # PEANUTS begin: Settings › App › Extras › Play with other apps' sound (#819). Off (the default) the focus above as it was; on, no focus, and the audio
    # mode stands in for a phone call
    def test_play_with_other_apps_sound(self):
        t = ACTIVITY.read_text(encoding='utf-8')
        i = t.index('if (dev.peanuts.PeanutsPrefs.mixAudio(this)) {')
        on, off = t[i:t.index('// PEANUTS end', i)].split('} else {')
        self.assertIn('Native.audioInterrupt(AudioFocus.interrupts(m))', on)              # the mode listener goes through AudioFocus
        self.assertIn('addOnModeChangedListener(', on)
        self.assertNotIn('requestFocus()', on)                                              # on: no focus asked for
        self.assertIn('new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)', off)   # off: the request as before
        self.assertIn('if (requestFocus()) Native.audioInterrupt(false);', off)
        self.assertIn('removeOnModeChangedListener(audioMode)', t)                          # the listener goes with the screen
        start = t[t.index('protected void onStart()'):t.index('protected void onResume()')]
        self.assertIn('if (audio != null && audioMode != null) Native.audioInterrupt(AudioFocus.interrupts(audio.getMode()));', start)
        self.assertLess(start.index('AudioFocus.interrupts(audio.getMode())'), start.index('Native.lifecycle(2)'))   # the return follows the mode as it is now
        self.assertIn('getBoolean("mix_audio", false)', PREFS.read_text(encoding='utf-8'))  # off by default
    # PEANUTS end

    def test_constants_match_android(self):
        # AudioManager.AUDIOFOCUS_GAIN / _LOSS / _LOSS_TRANSIENT / _LOSS_TRANSIENT_CAN_DUCK
        t = SRC.read_text(encoding='utf-8')
        self.assertIn('GAIN = 1, LOSS = -1, LOSS_TRANSIENT = -2, LOSS_TRANSIENT_CAN_DUCK = -3', t)
        # PEANUTS: AudioManager.MODE_NORMAL / _RINGTONE / _IN_CALL / _IN_COMMUNICATION / _CALL_SCREENING / _CALL_REDIRECT / _COMMUNICATION_REDIRECT (#819)
        self.assertIn('MODE_NORMAL = 0, MODE_RINGTONE = 1, MODE_IN_CALL = 2, MODE_IN_COMMUNICATION = 3, MODE_CALL_SCREENING = 4, MODE_CALL_REDIRECT = 5', t)
        self.assertIn('MODE_COMMUNICATION_REDIRECT = 6', t)   # PEANUTS


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class AudioFocusJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', d, str(SRC), str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.AudioFocusJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
