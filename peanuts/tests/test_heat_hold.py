"""The app's heat pause holds the code step where it is (GitHub #803). It used to interrupt the rewrite at every pause, and the step began again from 0 on the
next start: an AYN Odin 3 on its charger looped 74 times in 4 hours. PackService now makes PN_HOLD_FILE while the pause lasts, and scripts/pack_android.py
stops the rewrite's worker processes (SIGSTOP, SIGCONT when the file goes), or, for a rewrite in its own process, waits at the tool's next progress line.
The work goes on from where it was: these tests run a stand-in tool that counts, and look that it neither moves while held nor starts again. A stop (the
app's interrupt) still ends a held tool. No game files."""
import contextlib
import ctypes
import io
import os
import shutil
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_rewrite_forks import load_pack_android   # noqa: E402

REPO = Path(__file__).resolve().parents[2]

# counts to 40, one step every 50 ms, into count.txt; start.txt gets a line at each start (a tool begun again would write two)
COUNTER = ("import os, sys, time\n"
           "d = os.path.dirname(os.path.abspath(sys.argv[0]))\n"
           "open(os.path.join(d, 'start.txt'), 'a').write('start\\n')\n"
           "for i in range(1, 41):\n"
           "    time.sleep(0.05)\n"
           "    open(os.path.join(d, 'count.txt'), 'w').write(str(i))\n"
           "    print(f'progress {i} 40 code', flush=True)\n")


class Hold(unittest.TestCase):
    def setUp(self):
        self.pa = load_pack_android()
        self.d = Path(tempfile.mkdtemp(prefix='pnhold_'))
        self.addCleanup(lambda: shutil.rmtree(self.d, ignore_errors=True))
        self.env = dict(os.environ)
        self.addCleanup(lambda: (os.environ.clear(), os.environ.update(self.env)))
        self.hold = self.d / 'pack-hold'
        os.environ['PN_HOLD_FILE'] = str(self.hold)
        (self.d / 'tool.py').write_text(COUNTER)

    def count(self):
        try: return int((self.d / 'count.txt').read_text() or 0)
        except (OSError, ValueError): return 0

    def start(self, run):
        got = {}

        def go():
            out = io.StringIO()
            try:
                with contextlib.redirect_stdout(out): got['end'] = run()
            except BaseException as e: got['end'] = type(e).__name__
            got['out'] = out.getvalue()
        t = threading.Thread(target=go)
        t.start()
        return t, got

    def wait_for(self, n, limit=10):
        end = time.monotonic() + limit
        while self.count() < n and time.monotonic() < end: time.sleep(0.02)
        self.assertGreaterEqual(self.count(), n, 'the tool did not get that far')

    def held_then_on(self, run):
        t, got = self.start(run)
        self.wait_for(5)
        self.hold.write_text('')
        time.sleep(1.0)   # the hold is seen within 0.5 s (the worker) or at the next progress line (in process)
        at = self.count()
        time.sleep(1.0)
        self.assertEqual(self.count(), at, 'the tool went on while held')
        self.assertLess(at, 40)
        self.hold.unlink()
        t.join(30)
        self.assertFalse(t.is_alive(), 'the tool did not end')
        self.assertEqual(got['end'], 0, got)
        self.assertEqual(self.count(), 40)
        self.assertEqual((self.d / 'start.txt').read_text(), 'start\n', 'the tool began again')
        self.assertIn('held where it is', got['out'])
        self.assertIn('going on from where it was held', got['out'])
        return got

    @unittest.skipUnless(hasattr(os, 'killpg'), 'no process groups here')
    def test_the_worker_is_held_and_goes_on_where_it_was(self):   # the phone's rewrite: pn_rewrite_worker (DM_SPLIT_WORKER, PN_REWRITE_J 2 or more)
        os.environ.update(DM_SPLIT_WORKER=sys.executable, PN_REWRITE_J='2')
        got = self.held_then_on(lambda: self.pa.pn_rewrite_worker(self.d / 'tool.py', [], {}, self.d / 'tool.log', (0, 1000)))
        self.assertIn('progress 1000 1000 code', got['out'])

    def test_the_rewrite_in_this_process_waits_at_its_progress_line(self):   # run_in_process (one worker): the tool runs in this thread
        self.held_then_on(lambda: self.pa.run_in_process(self.d / 'tool.py', [], {}, self.d / 'tool.log', (0, 1000)))

    @unittest.skipUnless(hasattr(os, 'killpg'), 'no process groups here')
    def test_a_stop_ends_a_held_worker(self):   # Pause, Stop or the watchdog while held: the interrupt ends the tool and its stopped worker at once
        os.environ.update(DM_SPLIT_WORKER=sys.executable, PN_REWRITE_J='2')
        t, got = self.start(lambda: self.pa.pn_rewrite_worker(self.d / 'tool.py', [], {}, self.d / 'tool.log', (0, 1000)))
        self.wait_for(5)
        self.hold.write_text('')
        time.sleep(1.0)
        asked = time.monotonic()
        ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(t.ident), ctypes.py_object(KeyboardInterrupt))
        t.join(10)
        self.assertFalse(t.is_alive(), 'the held tool did not end')
        self.assertEqual(got['end'], 'KeyboardInterrupt')
        self.assertLess(time.monotonic() - asked, 3)
        at = self.count()
        self.hold.unlink()
        time.sleep(0.5)
        self.assertEqual(self.count(), at, 'the worker lived on after the stop')

    def test_the_held_line_is_the_apps(self):   # PackService (Tail) takes "held where it is" at the start of a trimmed line as the tool being held (HELD_LINE)
        out = io.StringIO()
        self.hold.write_text('')
        t = threading.Thread(target=lambda: self.pa.wait_while_held(out))
        t.start()
        time.sleep(0.3)
        self.hold.unlink()
        t.join(5)
        lines = [l.strip() for l in out.getvalue().splitlines()]
        self.assertTrue(lines[0].startswith('held where it is'), lines)
        self.assertFalse(lines[1].startswith('held where it is'), lines)

    def test_no_hold_file_no_wait(self):   # no PN_HOLD_FILE (a desktop run, an older app): nothing waits
        del os.environ['PN_HOLD_FILE']
        self.assertFalse(self.pa.held())
        self.assertFalse(self.pa.wait_while_held())


class Wiring(unittest.TestCase):
    """The app's side, read from the sources: the hold file is the tool's (PN_HOLD_FILE), the card offers Go on anyway, and the watchdog takes a hold for a pause"""
    def test_service_and_card(self):
        svc = (REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java').read_text(encoding='utf-8')
        hold = svc[svc.index('private void hold(Job j)'):]
        hold = hold[:hold.index('\n    }\n')]
        self.assertNotIn('engine.interrupt()', hold)
        # job.json says paused once the tool says it is held (its line, as these tests' tools print it), not while it still runs (found in review):
        # Prepare treats it as a pause, the watchdog as no hang
        self.assertNotIn('j.state("paused"', hold)
        taken = svc[svc.index('private void holdTaken(Job j)'):]
        taken = taken[:taken.index('\n    }\n')]
        self.assertIn('j.state("paused", "thermal", null);', taken)
        self.assertIn('j.reasonDetail(', taken)
        self.assertIn('static final String HELD_LINE = "held where it is";', svc)
        tail = svc[svc.index('private final class Tail'):]
        self.assertIn('else if (line.startsWith(HELD_LINE)) {', tail)
        self.assertIn('holdTaken(j);', tail)
        pa = (REPO / 'scripts/pack_android.py').read_text(encoding='utf-8')
        self.assertEqual(pa.count("'  held where it is (the phone is too hot): it goes on from here'"), 2, 'the line both ways of holding print')
        # at CRITICAL or worse a step not held is stopped; a held one stays held
        pol = svc[svc.index('private void policy()'):]
        pol = pol[:pol.index('\n    }\n')]
        rule = pol[pol.index('thermal >= PackPolicy.CRITICAL'):]
        self.assertIn('held && !holdSeen && inTool', pol)
        self.assertLess(rule.index('unhold();'), rule.index('engine.interrupt();'))
        prep = (REPO / 'android/app/src/main/java/dev/peanuts/build/Prepare.java').read_text(encoding='utf-8')
        self.assertEqual(prep.count('BuildPipeline.heatPaused(goOn(state, reason, o) ? logId : null);'), 2, 'a build and a build with mods')
        self.assertEqual(prep.count('BuildPipeline.heatPaused(null);'), 2)
        # a pause the packer no longer holds (its process ended while held or waiting) is watched as a running job is: it ends as a packer that ended, not
        # "too hot, goes on when cooled" for good (BuildWatch.Watch.paused; BuildWatchJvmTest runs the rule)
        self.assertNotIn('w.changed(SystemClock.uptimeMillis());', prep[prep.index('p.on(pct, pausedLine(reason, o)'):][:400])
        for mods in ('false', 'true'):
            at = prep.index('w.paused(SystemClock.uptimeMillis(), packerHolds());', prep.index('p.on(pct, pausedLine(reason, o)') if mods == 'false' else prep.index('MODS_AGAIN, BuildListener.CANCELLED);'))
            self.assertIn(f'watch(w, file, step, stage, startedAt, {mods});', prep[at:at + 300])
        self.assertIn('boolean waited = why.equals("thermal") || why.equals("battery");', prep)   # the watchdog's end keeps such a job as paused for its own reason
        lib = (REPO / 'android/app/src/main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        self.assertIn('BuildPipeline.goOnAnyway(a)', lib)
        self.assertIn('BuildPipeline.goOnOffered(b.titleId)', lib)
        up = (REPO / 'android/app/src/main/java/dev/peanuts/build/Upstream.java').read_text(encoding='utf-8')
        self.assertIn('"dev.nxnative.android.pack.GO_ON"', up)
        self.assertIn('static final String ACTION_GO_ON = "dev.nxnative.android.pack.GO_ON"', svc)


if __name__ == '__main__':
    unittest.main()
