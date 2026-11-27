"""The on-device job's automatic pause (android/app/src/ondevice PackPolicy.java, used by PackService) on the JVM: battery below 20 % and not charging; thermal
status SEVERE or worse, held until MODERATE or better; Go on anyway lifts the heat pause below CRITICAL (GitHub #38: a handheld that reports SEVERE while cool).
Also the readings each pause writes to the step's log. Skipped without a JDK.
PEANUTS (#803): the heat pause goes on at LIGHT or better, or at MODERATE once it has lasted PackPolicy.COOL_MS; it stops only the steps that keep what they
did (split, shaders) and holds the others where they are (PackService.hold, pack_android.py held())."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackPolicy.java'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/PackPolicyJvmTest.java'
SERVICE = REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Policy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='packpolicy_'))
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(cls.tmp), str(SRC), str(TEST)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_on(self, *lines):
        r = subprocess.run(['java', '-cp', str(self.tmp), 'dev.nxnative.android.PackPolicyJvmTest', *lines], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.splitlines()

    def reasons(self, *lines):
        return [l.split('\t')[:2] for l in self.run_on(*lines)[:-1]]

    def test_heat(self):   # the documented rule: pause at SEVERE (3), hold until MODERATE (2) or better
        self.assertEqual(self.reasons('80 0 2 - 0', '80 0 3 - 0', '80 0 3 thermal 0', '80 0 2 thermal 0', '80 1 6 - 0'),
                         [['-', 'false'], ['thermal', 'true'], ['thermal', 'true'], ['-', 'false'], ['thermal', 'false']])

    # PEANUTS begin: #803
    def test_heat_cool_down(self):   # with the pause's length known (PackService): MODERATE holds for COOL_MS (120 s), LIGHT goes on at once
        self.assertEqual(self.reasons('80 1 2 thermal 0 7000', '80 1 2 thermal 0 119999', '80 1 2 thermal 0 120000', '80 1 1 thermal 0 7000',
                                      '80 1 0 thermal 0 0', '80 1 3 thermal 0 600000', '80 1 2 - 0 0'),
                         [['thermal', 'true'], ['thermal', 'true'], ['-', 'false'], ['-', 'false'], ['-', 'false'], ['thermal', 'true'], ['-', 'false']])

    def test_heat_cool_down_with_go_on_anyway(self):   # Go on anyway's rule has no cool-down: held at CRITICAL only, until SEVERE or better
        self.assertEqual(self.reasons('80 1 3 thermal 1 0', '80 1 4 thermal 1 600000', '80 1 2 thermal 1 0'), [['-', 'false'], ['thermal', 'false'], ['-', 'false']])

    def test_heat_stops_only_steps_that_keep_their_work(self):   # the code step began again from 0 at every pause: it is held now
        out = [l.split('\t')[:2] for l in self.run_on('stops identify', 'stops rewrite', 'stops split', 'stops shaders', 'stops pack')[:-1]]
        self.assertEqual(out, [['stops', 'false'], ['stops', 'false'], ['stops', 'true'], ['stops', 'true'], ['stops', 'false']])

    def test_service_holds_the_step(self):   # PackService: the hold file for the tool, cleared before each tool and on a stop; the cool-down re-checked
        src = SERVICE.read_text(encoding='utf-8')
        self.assertIn('PackEngine.setenv("PN_HOLD_FILE", holdFile().getPath())', src)
        self.assertIn('!PackPolicy.heatStops(Job.STEPS[at])', src)
        self.assertIn('if (hold) hold(j);', src)
        self.assertIn('else if (inTool) engine.interrupt();', src)
        self.assertIn('main.postDelayed(heatRecheck, 15_000)', src)
        stop = src[src.index('private void requestStop('):]
        self.assertLess(stop.index('unhold();'), stop.index('engine.interrupt();'))
        tool = src[src.index('unhold();   // PEANUTS: no hold left'):]
        self.assertLess(tool.index('unhold();'), tool.index('code = engine.run('))
        # the heat pauses are counted per run, for the card's and the notification's Go on anyway (Job.hotAgain)
        self.assertIn('j.heatPauses(++heatPauses)', src)
        self.assertIn('heatPauses = 0;', src)
        note = src[src.index('private Notification note(Job j)'):]
        self.assertIn('if (again) b.addAction(goOnAction).addAction(pauseAction);', note)

    def test_destroy_lifts_the_hold(self):   # a service that is destroyed leaves no cool-down check behind and no stopped (SIGSTOP) worker group: the hold file goes
        src = SERVICE.read_text(encoding='utf-8')
        d = src[src.index('@Override public void onDestroy() {'):]
        d = d[:d.index('\n    }\n')]
        self.assertIn('main.removeCallbacks(heatRecheck);', d)
        self.assertIn('unhold();', d)
        self.assertLess(d.index('unhold();'), d.index('super.onDestroy();'))
        h = src[src.index('private void unhold() {'):]
        self.assertIn('holdFile().delete()', h[:h.index('\n    }\n')])

    def test_sticky_restart_resumes_a_policy_pause(self):   # Android ended the packer while a heat hold or a battery pause waited: the job runs again and waits there
        out = [l.split('\t')[:2] for l in self.run_on('restart running', 'restart paused thermal', 'restart paused battery', 'restart paused user',
                                                        'restart paused timeout', 'restart paused hung', 'restart paused ended', 'restart done', 'restart failed')[:-1]]
        self.assertEqual([o[1] for o in out], ['true', 'true', 'true', 'false', 'false', 'false', 'false', 'false', 'false'])
        src = SERVICE.read_text(encoding='utf-8')
        self.assertIn('intent == null ? PackPolicy.resumesAfterRestart(j.state(), j.reason()) :', src)
        work = src[src.index('private void work()'):]
        self.assertLess(work.index('j.state("running", null, null);'), work.index('if (!waitWhileAuto(j)) break;'), 'the resumed job goes back into the pause before a step')

    def test_hot_again_words_match(self):   # the card (Prepare) and the packer (PackPolicy) say the same, from the same count
        pol = SRC.read_text(encoding='utf-8')
        prep = (REPO / 'android/app/src/main/java/dev/peanuts/build/Prepare.java').read_text(encoding='utf-8')
        import re
        words = lambda s: re.search(r'HOT_AGAIN = "([^"]+)"', s).group(1)
        self.assertEqual(words(pol), words(prep))
        self.assertEqual(words(pol), 'The phone keeps getting hot; building on a charger heats it more. Go on anyway builds without pausing.')
        at = lambda s: re.search(r'HOT_AGAIN_AT = (\d+)', s).group(1)
        self.assertEqual(at(pol), at(prep))
        self.assertNotIn('\u2014', words(pol))
    # PEANUTS end

    def test_go_on_anyway(self):   # lifted at SEVERE, not at CRITICAL (4) or worse; once paused there, held until SEVERE or better
        self.assertEqual(self.reasons('80 0 3 thermal 1', '80 0 4 - 1', '80 0 4 thermal 1', '80 0 3 thermal 1'),
                         [['-', 'false'], ['thermal', 'false'], ['thermal', 'false'], ['-', 'false']])

    def test_battery(self):   # below 20 % on battery, whatever the heat or the override
        self.assertEqual(self.reasons('19 0 0 - 0', '19 1 0 - 0', '20 0 0 - 0', '10 0 3 - 1'),
                         [['battery', 'false'], ['-', 'false'], ['-', 'false'], ['battery', 'false']])

    def test_readings(self):
        out = self.run_on('80 1 3 - 0')
        self.assertEqual(out[0].split('\t')[2], 'thermal status 3 (severe), headroom 0.50, battery 80 %, charging')
        self.assertEqual(out[-1], 'thermal status 1 (light), headroom not known, battery 50 %')

    def test_service_uses_policy(self):   # PackService decides with PackPolicy, and logs each pause and each going on to the step's log
        src = SERVICE.read_text(encoding='utf-8')
        self.assertIn('PackPolicy.reason(batteryPct, charging, thermal, autoReason, heatOverride, ', src)   # PEANUTS: with the pause's length (#803)
        self.assertIn('stepLog(j, "pausing: "', src)
        self.assertIn('stepLog(j, "going on after the "', src)
        self.assertNotIn('THERMAL_STATUS_SEVERE', src)

    def test_every_pause_logged(self):   # every way the job pauses or stops by itself writes a [pack] line to the step's log (stepLog), not only logcat
        src = SERVICE.read_text(encoding='utf-8')
        for what in ('"pausing: timeout:', '"pausing: refused:', '"paused: " + stopReason', '"the tool did not stop within 20 s of the "', '"stopping: the step was ended by Android "',
                     '"going on after Android ended the job\'s process', '"pause asked (from "', '"going on anyway: the heat pause is lifted'):
            self.assertIn('stepLog(', src[src.index(what) - 40:src.index(what)], what)

    def test_keep_screen_on(self):   # Settings › Keep the screen on while preparing: on by default, applied by Home's card while the job runs
        prefs = (REPO / 'android/app/src/main/java/dev/nxnative/android/Prefs.java').read_text(encoding='utf-8')
        self.assertIn('getBoolean("keep_screen_preparing", true)', prefs)
        card = (REPO / 'android/app/src/ondevice/java/dev/nxnative/android/Ondevice.java').read_text(encoding='utf-8')
        self.assertIn('keepScreen(running && Prefs.keepScreenPreparing(a));', card)
        self.assertIn('FLAG_KEEP_SCREEN_ON', card[card.index('void keepScreen('):])


if __name__ == '__main__':
    unittest.main()
