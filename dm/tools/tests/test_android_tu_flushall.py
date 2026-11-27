"""PEANUTS: DM_VK_TU_FLUSHALL=1 (a game option) is TU_DEBUG=flushall in the game's environment: GameActivity.settingsEnv adds it after the option lines, and the readiness
check, which makes the process's first Vulkan device (when Turnip reads TU_DEBUG), takes settingsEnv before the user's lines, so a TU_DEBUG line of theirs wins."""
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ACT = REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java'
LINE = 'if (env.contains("DM_VK_TU_FLUSHALL=1")) env.add("TU_DEBUG=flushall");'


class TuFlushall(unittest.TestCase):
    def test_settings_env_adds_it_after_the_option_lines(self):
        s = ACT.read_text(encoding='utf-8')
        body = s[s.index('private String settingsEnv('):]
        self.assertLess(body.index('prof.optionsEnv('), body.index(LINE))
        self.assertLess(body.index(LINE), body.index('fpsModeAtStart = onScreen('), 'the line is inside settingsEnv')

    def test_readiness_check_takes_it_before_the_user_lines(self):
        s = ACT.read_text(encoding='utf-8')
        hold = s[s.index('private boolean readinessHold('):]
        self.assertLess(hold.index('settingsEnv(id, pack, genv);'), hold.index('genv.addAll(quietEnv(new File(files, "env.txt")));'))
        self.assertLess(hold.index('genv.addAll(envLines(Prefs.gameEnv(this, id)));'), hold.index('Readiness.gameEnv(genv, PROTECTED)'))


if __name__ == '__main__':
    unittest.main()
