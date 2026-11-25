"""Share log asks which log (dev.peanuts.ui.BuildLogUi.shareLog, its words in dev.peanuts.ui.LogChoice; peanuts/UPSTREAM-EDITS.md Round 42): every place that
shares a game's log offers its newest run and its build when it has both, and shares the one there is when it has one. LogChoiceJvmTest drives the words on
the JVM (javac compiles LogChoice and BuildLog, no Android types); the wiring part reads the call sites. The JVM part is skipped without a JDK."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UI = APP / 'main/java/dev/peanuts/ui'
SRC = [UI / 'LogChoice.java', APP / 'main/java/dev/peanuts/build/BuildLog.java']
TEST = APP / 'jvmtest/java/dev/peanuts/ui/LogChoiceJvmTest.java'


def read(p):
    return p.read_text(encoding='utf-8')


def body(text, head):
    # a method's body in a Java file: from its first line to the closing brace at its indent
    return text.split(head)[1].split('\n    }\n')[0]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Words(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='logchoice_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t] + [str(s) for s in SRC] + [str(TEST)], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.LogChoiceJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')

    def test_worker_jvm(self):
        # OffMain: the reading on a worker, the answer on the main thread while the screen is there, one tap at a time
        with tempfile.TemporaryDirectory(prefix='offmain_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(UI / 'OffMain.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/ui/OffMainJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ui.OffMainJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def setUp(self):
        self.main = read(APP / 'main/java/dev/nxnative/android/MainActivity.java')
        self.exit = read(APP / 'main/java/dev/nxnative/android/ExitWatch.java')
        self.ond = read(APP / 'ondevice/java/dev/nxnative/android/Ondevice.java')
        self.lib = read(UI / 'Library.java')
        self.ui = read(UI / 'BuildLogUi.java')

    def test_every_place_asks(self):
        # Settings › Help: the same hook as before, its choice inside BuildLogUi
        self.assertIn('v -> dev.peanuts.ui.BuildLogUi.shareLog(this, this::shareLog)', self.main)
        # the game's Storage tab: one row, upstream's own Share log of the game when the run is picked or there is no log
        self.assertIn('"Share log…", v -> dev.peanuts.ui.BuildLogUi.shareLog(this, g.titleId, g.name, null, () -> shareLog(g.titleId))', self.main)
        self.assertNotIn('"Share build log"', self.main, 'the second Storage row is gone')
        # the "Game stopped" notice: that run, with its digest, through upstream's own shareLog(log, digest)
        self.assertIn('dev.peanuts.ui.BuildLogUi.shareLog(a, rec.get("title"), rec.get("name"), log, () -> a.shareLog(log, dg != null && dg.isFile() ? dg : null))', self.exit)
        # the Prepare card (BuildLogUi.card) and its Manage sheet, Home's build card
        self.assertIn('dev.peanuts.ui.BuildLogUi.card(a, card, ', self.ond)
        self.assertIn('dev.peanuts.ui.BuildLogUi.shareLog(a, j.titleId(), j.name(), null, null)', self.ond)
        self.assertIn('BuildLogUi.shareLog(a, b.titleId, name, null, null)', self.lib)
        card = self.ui.split('public static void card(')[1].split('\n    }\n')[0]
        self.assertIn('shareLog(a, j[0], j[1], null, null)', card)

    def test_no_place_shares_directly(self):
        # a place that sent one log without asking: the old Storage rows, the notice's own button, the build cards' Share build log
        self.assertNotIn('v -> shareLog(g.titleId)).value(', self.main)
        self.assertNotIn('(d, w) -> a.shareLog(log', self.exit)
        self.assertNotIn('BuildLogUi.share(a, j.titleId(), j.name())', self.ond)
        self.assertNotIn('BuildLogUi.share(a, b.titleId, name)', self.lib)
        self.assertNotIn('"Share build log"', self.ond + self.lib + self.ui)

    def test_marked(self):
        for f, text in (('MainActivity', self.main), ('ExitWatch', self.exit), ('Ondevice', self.ond)):
            for line in text.splitlines():
                if 'BuildLogUi' in line:
                    self.assertIn('PEANUTS', line, f + ': an upstream line without its marker: ' + line.strip())

    def test_ask_only_with_both(self):
        game = body(self.ui, 'private static Pick gamePick(Context c, String titleId, String name, File run) {')
        self.assertIn('LogChoice.choose(p.runLog != null, b != null)', game)
        tap = body(self.ui, 'public static void shareLog(Activity a, String titleId, String name, File run, Runnable shareRun) {')
        self.assertIn('case LogChoice.RUN: runShare.run(); return;', tap)
        self.assertIn('case LogChoice.BUILD: share(a, titleId, name, p.buildName); return;', tap)
        help_ = body(self.ui, 'private static Pick anyPick(Context c) {')
        self.assertIn('LogChoice.choose(run != null, b != null)', help_)
        self.assertNotIn('lastModified() >=', help_, 'Help no longer asks only when the build is newer')
        # both logs of one game: its name in the title, so each line keeps room for when and how (the sheet's value has two lines)
        self.assertIn('rt.equalsIgnoreCase(b.titleId)', help_)
        self.assertIn('p.title = "Share a log of " + p.buildName;', help_)

    def test_read_on_a_worker(self):
        # A tap reads nothing itself: the launcher's list, the work folders, job.json, a log's tail, last-exit.txt and the running processes are read
        # in anyPick and gamePick, on Share log's worker (OffMain), and the sheet opens with what they found (#312's review)
        reads = ('newestRunLog(', 'BuildLogShare.found(', 'BuildLogShare.gameName(', 'runLine(', 'buildLine(', 'digestOf(', '.isFile()',
                 'getRunningAppProcesses', 'BuildLog.tail(')
        taps = ('public static void shareLog(Activity a, Runnable runLog) {',
                'public static void shareLog(Activity a, String titleId, String name, File run, Runnable shareRun) {')
        sheets = ('private static void share(Activity a, String titleId, String name, String gameName) {',
                  'private static void ask(Activity a, CharSequence title, CharSequence run, Runnable shareRun, CharSequence build, Runnable shareBuild) {',
                  'static void shareRun(Activity a, File log, File digest) {')
        for head in taps + sheets:
            text = body(self.ui, head)
            if head.startswith('private static void share('):
                text = text.split('new Thread(')[0]   # the bundle is made on its own thread, as before
            for r in reads:
                self.assertNotIn(r, text, head + ' reads on the main thread: ' + r)
        for head in taps:
            self.assertIn('LOOK.run(() -> ', body(self.ui, head), head + ' hands its reading to the worker')
        self.assertIn('new OffMain(java.util.concurrent.Executors.newSingleThreadExecutor(', self.ui)
        self.assertIn('MAIN::post)', self.ui)
        self.assertIn('BuildLogShare.make(app, titleId, name, BuildLogShare.outDir(app))', body(self.ui, sheets[0]).split('new Thread(')[1])

    def test_upstream_names_it_reads(self):
        # BuildLogUi reads upstream's run logs and exit record by their names, and shares a run through MainActivity.shareLog(File, File) (reflection)
        self.assertIn('void shareLog(File log, File digest) {', self.main)
        self.assertIn('getDeclaredMethod("shareLog", File.class, File.class)', self.ui)
        self.assertIn('return new File(c.getFilesDir(), "last-exit.txt");', self.exit)
        self.assertIn('static File digestOf(File log) { return new File(log.getParentFile(), log.getName().replaceFirst("\\\\.log$", "") + LogProvider.DIGEST); }', self.exit)
        self.assertIn('static final String DIGEST = ".digest.txt";', read(APP / 'main/java/dev/nxnative/android/LogProvider.java'))
        self.assertIn('new File(c.getExternalFilesDir(null), "logs")', read(APP / 'main/java/dev/nxnative/android/LogProvider.java'))
        game = read(APP / 'main/java/dev/nxnative/android/GameActivity.java')
        self.assertIn('id + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".log"', game, 'a run log is named <title ID>-<start>.log')

    def test_steps_match_prepare(self):
        # LogChoice names the job's steps as Job and Prepare do
        lc = read(UI / 'LogChoice.java')
        steps = re.search(r'STEP_TEXT = (\{[^}]*\})', lc).group(1)
        self.assertIn('STEP_TEXT = ' + steps, read(APP / 'main/java/dev/peanuts/build/Prepare.java'))
        self.assertIn('STEP_TEXT = ' + steps, read(APP / 'ondevice/java/dev/nxnative/android/Job.java'))
        keys = re.search(r'STEPS = (\{[^}]*\})', lc).group(1)
        self.assertIn('STEPS = ' + keys, read(APP / 'main/java/dev/peanuts/build/Prepare.java'))
        self.assertIn('log("prepare: the job failed at " + step + ": "', read(APP / 'main/java/dev/peanuts/build/Prepare.java'))
        self.assertIn('"progress " + step + " " + percent + " %: " + detail + " ("', read(APP / 'main/java/dev/peanuts/build/BuildService.java'))


if __name__ == '__main__':
    unittest.main()
