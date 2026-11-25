"""The build queue (issue #348): a build asked while another runs waits its turn, kept in the app's own storage so a killed app goes on with it.

The pure half runs on the JVM (android/app/src/jvmtest/java/dev/peanuts/build/BuildQueueJvmTest.java: order, the file, a run the process did not live
through, a failure that does not stop the queue, removing, Cancel all, the same game twice, the batch's places, a pause, a damaged file); it is skipped
without a JDK. The source checks say that the service, the pipeline, the library and the control interface keep to it."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
BUILD = APP / 'main/java/dev/peanuts/build'
SRC = [BUILD / n for n in ('BuildQueue.java', 'BuildRequest.java', 'BuildListener.java')]
TEST = APP / 'jvmtest/java/dev/peanuts/build/BuildQueueJvmTest.java'


def read(p):
    return Path(p).read_text(encoding='utf-8')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class QueueJvm(unittest.TestCase):
    def test_rules(self):
        with tempfile.TemporaryDirectory() as d:
            c = subprocess.run(['javac', '-Xlint:all,-serial', '-Werror', '-encoding', 'UTF-8', '-d', d] + [str(s) for s in SRC] + [str(TEST)], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.peanuts.build.BuildQueueJvmTest', d], capture_output=True, text=True, timeout=300)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


class Wiring(unittest.TestCase):
    def test_no_android_in_the_queue(self):
        src = read(BUILD / 'BuildQueue.java')
        self.assertNotIn('import android', src)
        self.assertNotIn('org.json', src)   # not on a plain JVM: the file is its own tab-separated format

    def test_service_runs_the_queue(self):
        s = read(BUILD / 'BuildService.java')
        self.assertNotIn('is being built now. Wait for it to finish', s, 'a second build is queued, not refused')
        self.assertIn('return START_STICKY;', s, 'Android starts the service again after its process ended')
        self.assertIn('while (!cancelAll && !q.held())', s)
        self.assertIn('waitTurn(q, next)', s)
        # the wait reuses the packer's own pause rule (PackPolicy) with its remembered Go on anyway (#93), and waits for a running game and a build with mods
        self.assertIn('Upstream.k("PackPolicy"), null, "reason"', s)
        self.assertIn('peanuts_heat.xml', s)
        self.assertIn('":game"', s)
        self.assertIn('ModBuildPipeline.running() != 0', s)
        # and while a pack of the next game is being imported (Import pack, #44): the import writes the folder the build would use
        self.assertIn('PackImport.busy(next.titleId())) return BuildQueue.WAIT_PACK;', s)
        # the storage check before each game: the import's exact size, and a floor for the pack
        self.assertIn('Not enough free space to import " + name + ": its game file needs about', s)
        self.assertIn('packFree < MIN_PACK_FREE', s)
        # a Stop in Prepare pauses the packer's job too (the next game's Prepare would wait for it), but only once this game's job was started:
        # before, the packer runs another game's job
        self.assertIn('boolean own = p.jobStarted();', s)
        self.assertIn('if (own) pausePacker();', s)
        # a pause is not a game's end: back to the front, the queue held; a finished game is never requeued
        self.assertIn('} else if (!ok && (pausing || packerPaused)) {', s)
        self.assertIn('if (timedOut && !ok) {', s)
        # "2 of 5", Cancel all, one alert for the queue's end
        self.assertIn('q.placeText(it)', s)
        self.assertIn('action("Cancel all", ACTION_CANCEL_ALL, 2)', s)
        self.assertIn('queueDone(q.readyCount() == total, q.summary(), cancelAll)', s)

    def test_packer_jobs_one_at_a_time(self):
        p = read(BUILD / 'Prepare.java')
        self.assertEqual(p.count('waitForPacker(p);'), 2, 'the plain and the modded Prepare wait while the packer runs another job')
        self.assertIn('ownFile = file;', p)
        self.assertIn('!new File(f).getAbsolutePath().equals(own.getAbsolutePath())', p, "another job's messages are not this run's state")
        self.assertIn('boolean before = o.optLong("updated") < startedAt', p, "the last run's pause is not this run's")
        lib = read(APP / 'main/java/dev/peanuts/ui/Library.java')
        self.assertIn('if (b == null || b.id != id) return;', lib, "a build's end goes to its own card only")

    def test_pipeline(self):
        p = read(BUILD / 'BuildPipeline.java')
        self.assertNotIn('AtomicInteger', p, "the ids are the queue's, kept in its file")
        for name in ('public static BuildQueue queue(Context c)', 'public static boolean busy()', 'public static void resume(Context ctx)',
                     'public static boolean remove(Context ctx, int id)', 'public static void cancelAll(Context ctx)', 'public static void pause(Context ctx)',
                     'public static void release(Context ctx)', 'public static void followPacker(Context c)', 'public static Map<String, Object> queueSnapshot()'):
            self.assertIn(name, p)

    def test_library(self):
        lib = read(APP / 'main/java/dev/peanuts/ui/Library.java')
        self.assertNotIn('if (BuildPipeline.running() != 0) return "A build is running now.', lib)
        self.assertIn('BuildPipeline.watch(queueChanged)', lib)
        self.assertIn('BuildPipeline.resume(a);', lib)
        self.assertIn('"Queued, " + p', lib)
        self.assertIn('"Up next"', lib)
        self.assertIn('"Build all…"', lib)
        self.assertIn('"Dismiss"', lib)
        sheet = read(APP / 'main/java/dev/peanuts/ui/GameSheet.java')
        self.assertIn('"Add to queue"', sheet)
        self.assertIn('"Remove from queue"', sheet)
        mods = read(APP / 'main/java/dev/peanuts/settings/ModsSection.java')
        self.assertIn('dev.peanuts.build.BuildPipeline.busy()', mods, 'a build with mods waits while the queue builds or waits')

    def test_build_all_is_one_change(self):
        # Build all of 50 games went through start() 50 times: 50 writes of the queue's file, 50 starts of the build service (each one its foreground
        # notification), and Home drawn again about 100 times (startBuild's render and the queue watcher's), all on the main thread
        p = read(BUILD / 'BuildPipeline.java')
        start = p[p.index('public static int[] startAll('):]
        start = start[:start.index('\n    }\n') + 6]
        self.assertIn('q.addAll(go, anyway,', start, 'the queue takes the batch with one write')
        self.assertEqual(start.count('startForegroundService('), 1, 'the build service is asked once for the batch')
        self.assertNotIn('for (', start[start.index('startForegroundService('):start.index('} catch')], 'not once a game')
        self.assertEqual(start.count('changed();'), 2, 'the library is told once (the refused branch and the started one)')
        self.assertIn('q.removeAll(added);', start, 'a batch Android did not let start leaves with one write')
        self.assertIn('return startAll(ctx, java.util.Collections.singletonList(req)', p, 'one game is a batch of one: the same rules')
        # changes that come before the watchers ran are told once
        self.assertIn('if (TELL.compareAndSet(false, true)) MAIN.post(', p)
        lib = read(APP / 'main/java/dev/peanuts/ui/Library.java')
        ask = lib[lib.index('private void buildAllAnswer('):lib.index('/** "A", "A and B", "A, B and C" */')]
        self.assertIn('int queued = startBuilds(states);', ask)
        self.assertNotIn('startBuild(st', ask, 'not one start (and one redraw of Home) a game')
        self.assertEqual(ask.count('render();'), 1, 'Home drawn once after Build all')
        many = lib[lib.index('private int startBuilds('):lib.index('private String refusal(')]
        self.assertIn('BuildPipeline.startAll(a, reqs, anyway, ls)', many)
        self.assertNotIn('BuildPipeline.start(', many)
        self.assertEqual(many.count('render();'), 1, 'at most one redraw, and only when the caller does not draw')

    def test_new_text_rules(self):
        # UI-RULES: no separator glyphs and no dashes in the queue's own words
        for f in (BUILD / 'BuildQueue.java', BUILD / 'BuildService.java'):
            for line in read(f).splitlines():
                for lit in re.findall(r'"([^"\\]*(?:\\.[^"\\]*)*)"', line):
                    self.assertNotRegex(lit, '[·•–—]', f.name + ': ' + lit)

    def test_upstream_hook(self):
        ond = read(APP / 'ondevice/java/dev/nxnative/android/Ondevice.java')
        hook = [l for l in ond.splitlines() if 'Library.queuePrepare' in l]
        self.assertEqual(len(hook), 1)
        self.assertIn('PEANUTS', hook[0])
        edits = read(REPO / 'peanuts/UPSTREAM-EDITS.md')
        self.assertIn('Library.queuePrepare', edits)

    def test_ctl(self):
        ctl = read(APP / 'main/java/dev/peanuts/ctl/Ctl.java')
        self.assertIn('queuedAnswer(res, id)', ctl)
        self.assertIn('dev.peanuts.ui.Library.queuePrepare(a, dump)', ctl)
        self.assertIn('r.stopAsked()', ctl)
        status = read(APP / 'main/java/dev/peanuts/ctl/Status.java')
        self.assertIn('m.put("queue", BuildPipeline.queueSnapshot());', status)
        doc = read(REPO / 'peanuts/docs/CONTROL.md')
        self.assertIn('`queue`', doc)
        self.assertIn('queued', doc)


if __name__ == '__main__':
    unittest.main()
