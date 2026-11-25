"""dm/tools/patch_nso.py in_forks (issue #120): where multiprocessing cannot start (no _multiprocessing, as in the phone's embedded CPython) the rewriter's x18 and
spin phases fork their workers themselves. The function is taken out of patch_nso.py (the script rewrites a module when it runs, so it is not imported) and run
with stand-ins for what it uses. The results must be in_serial's, in order; a child that fails, or ends without its results, fails the call, and leaves no child
behind. Skipped where os.fork is missing (Windows). No game files."""
import ast
import ctypes
import importlib.util
import os
import shutil
import signal
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = (REPO / 'dm/tools/patch_nso.py').read_text(encoding='utf-8')


def borrowed(names, workers, os_=os):
    """in_serial and in_forks from patch_nso.py (with the helpers in_forks calls), with WORKERS and a progress that records its calls"""
    tree = ast.parse(SRC)
    fns = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in set(names) | {'follow_parent_cpus'}]
    calls = []
    ns = {'os': os_, 'sys': sys, 'signal': signal, 'WORKERS': workers, 'progress': lambda phase, done=0, total=1: calls.append((phase, done, total))}
    exec(compile(ast.Module(body=fns, type_ignores=[]), 'patch_nso.py', 'exec'), ns)
    return ns, calls


def without_forks(src):
    """patch_nso.py's text without its PEANUTS(upstream-candidate) spans that hold in_forks (a top-level one with the blank line before it): the file as
    upstream has it"""
    lines, out, i = src.split('\n'), [], 0
    while i < len(lines):
        if 'PEANUTS(upstream-candidate) begin' in lines[i]:
            j = next(k for k in range(i, len(lines)) if 'PEANUTS end' in lines[k])
            if any('in_forks' in l for l in lines[i:j + 1]):
                if not lines[i].startswith(' ') and out and out[-1] == '': out.pop()
                i = j + 1
                continue
        out.append(lines[i]); i += 1
    return '\n'.join(out)


def square_plus(a, b): return [a * a + b] * 3


def fails_on_5(a):
    if a == 5: raise ValueError('piece 5')
    return a


def dies_on_3(a):
    if a == 3: os._exit(9)   # a child that ends without writing its results
    return a


@unittest.skipUnless(hasattr(os, 'fork'), 'no os.fork here')
class Forks(unittest.TestCase):
    def test_the_same_results_in_order(self):
        ns, calls = borrowed({'in_serial', 'in_forks'}, 4)
        jobs = [(i, i % 7) for i in range(37)]
        self.assertEqual(ns['in_forks'](square_plus, jobs, 'x18'), ns['in_serial'](square_plus, jobs))
        self.assertEqual([c[1] for c in calls if c[0] == 'x18'], [1, 2, 3, 4])   # a progress line as each child is done

    def test_fewer_jobs_than_workers(self):
        ns, _ = borrowed({'in_serial', 'in_forks'}, 8)
        self.assertEqual(ns['in_forks'](square_plus, [(2, 1), (3, 1)]), [[5] * 3, [10] * 3])

    def test_a_child_that_fails_fails_the_call(self):
        ns, _ = borrowed({'in_forks'}, 3)
        with self.assertRaisesRegex(RuntimeError, 'ValueError: piece 5'): ns['in_forks'](fails_on_5, [(i,) for i in range(9)])
        self.assertEqual(self.children(), [])

    def test_a_child_that_dies_fails_the_call(self):
        ns, _ = borrowed({'in_forks'}, 3)
        with self.assertRaisesRegex(RuntimeError, 'without its results'): ns['in_forks'](dies_on_3, [(i,) for i in range(9)])
        self.assertEqual(self.children(), [])

    def children(self):
        """This process's children left over (none: each was waited for)"""
        try: os.waitpid(-1, os.WNOHANG)
        except ChildProcessError: return []
        return ['a child is left']

    @unittest.skipUnless(os.path.isdir('/proc/self/fd'), 'no /proc')
    def test_a_fork_that_fails_leaves_no_descriptor(self):
        class NoSecondFork:   # os, whose second fork fails (no memory for another process)
            forks = 0
            def __getattr__(self, name): return getattr(os, name)
            def fork(self):
                NoSecondFork.forks += 1
                if NoSecondFork.forks == 2: raise OSError(12, 'Cannot allocate memory')
                return os.fork()
        ns, _ = borrowed({'in_forks'}, 3, NoSecondFork())
        before = len(os.listdir('/proc/self/fd'))
        with self.assertRaises(OSError): ns['in_forks'](square_plus, [(i, 1) for i in range(9)])
        self.assertEqual(len(os.listdir('/proc/self/fd')), before)   # the failed fork's pipe closed too
        self.assertEqual(self.children(), [])

    def test_a_childs_own_output_is_kept(self):
        # multiprocessing's workers flush their output when they end; a child of in_forks left with os._exit, and what it printed was lost
        ns, _ = borrowed({'in_forks'}, 2)
        with tempfile.TemporaryFile('w+') as out:
            saved = sys.stdout
            sys.stdout = out
            try: ns['in_forks'](prints, [(i,) for i in range(4)])
            finally: sys.stdout = saved
            out.seek(0)
            self.assertEqual(sorted(l for l in out.read().splitlines() if l.startswith('piece')), ['piece 0', 'piece 1', 'piece 2', 'piece 3'])

    @unittest.skipUnless(hasattr(os, 'sched_getaffinity') and len(os.sched_getaffinity(0)) > 1, 'one CPU, or no affinity here')
    def test_the_children_follow_the_workers_cpus(self):
        # nxpack_worker's game-yield watch (pack_worker.c yield_watch) moves the worker's own threads to the slow cores when a game starts; the forks are
        # processes of their own, and stayed on every core beside the game
        ns, _ = borrowed({'in_forks'}, 2)
        mine = os.sched_getaffinity(0)
        one = {min(mine)}
        moved = threading.Timer(0.5, lambda: os.sched_setaffinity(os.getpid(), one))   # the watch, from a thread of its own, as in the worker
        moved.start()
        try: got = ns['in_forks'](cpus_after, [(3.5,), (3.5,)])
        finally: moved.cancel(); os.sched_setaffinity(os.getpid(), mine)
        self.assertEqual(got, [sorted(one), sorted(one)])

    def test_the_heap_is_frozen_while_the_children_run(self):
        # a collection in a child walks the parent's objects and copies their pages (copy on write): a parent of 1 GB of objects and two children came to
        # 2.4 GB of memory in a model of it; gc.freeze before the forks keeps them shared
        body = SRC[SRC.index('def in_forks('):SRC.index('\ndef ', SRC.index('def in_forks(') + 1)]
        self.assertIn('gc.freeze()', body)
        self.assertIn('gc.unfreeze()', body)


def prints(a):
    print(f'piece {a}')
    return a


def cpus_after(seconds):
    time.sleep(seconds)
    return sorted(os.sched_getaffinity(0))


def load_pack_android():
    spec = importlib.util.spec_from_file_location('pack_android_for_test', REPO / 'scripts/pack_android.py')
    m = importlib.util.module_from_spec(spec)
    argv = sys.argv
    try: spec.loader.exec_module(m)
    finally: sys.argv = argv
    return m


@unittest.skipUnless(hasattr(os, 'killpg'), 'no process groups here')
class Worker(unittest.TestCase):
    """scripts/pack_android.py pn_rewrite_worker: the packer stops a tool by an exception in its thread (PackEngine: PyThreadState_SetAsyncExc), which Python raises
    at that thread's next bytecode; blocked in a read of the worker's pipe it waited for the worker's next line, and in_forks' parent prints one only as each
    child is done (ACNH's spin phase on two workers: about 30 s), past the 20 s after which PackService ends the process"""
    def setUp(self):
        self.pa = load_pack_android()
        self.d = Path(tempfile.mkdtemp(prefix='pnworker_'))
        self.addCleanup(lambda: shutil.rmtree(self.d, ignore_errors=True))
        self.env = dict(os.environ)
        self.addCleanup(lambda: (os.environ.clear(), os.environ.update(self.env)))
        os.environ.update(DM_SPLIT_WORKER=sys.executable, PN_REWRITE_J='2')

    def run_tool(self, script, stop_after=None, progress=None):
        """pn_rewrite_worker on script in a thread of its own, stopped after stop_after s as the packer stops it → (how it ended, its seconds after the stop)"""
        (self.d / 'tool.py').write_text(script)
        got = {}

        def tool():
            try: got['end'] = self.pa.pn_rewrite_worker(self.d / 'tool.py', [], {}, self.d / 'tool.log', progress)
            except BaseException as e: got['end'] = type(e).__name__
            got['at'] = time.monotonic()
        t = threading.Thread(target=tool); t.start()
        if stop_after is not None:
            time.sleep(stop_after)
            asked = time.monotonic()
            ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(t.ident), ctypes.py_object(KeyboardInterrupt))
        else: asked = time.monotonic()
        t.join(30)
        self.assertFalse(t.is_alive(), 'the tool did not end')
        return got['end'], got['at'] - asked

    def test_a_stop_reaches_it_while_the_worker_prints_nothing(self):
        end, after = self.run_tool('import time\nprint("progress 1 2 spin", flush=True)\ntime.sleep(20)\n', stop_after=1.0)
        self.assertEqual(end, 'KeyboardInterrupt')
        self.assertLess(after, 3)

    def test_its_output_and_exit_code(self):
        end, _ = self.run_tool('import sys\nprint("progress 1 2 spin", flush=True)\nprint("done")\nsys.exit(3)\n', progress=(100, 200))
        self.assertEqual(end, 3)
        self.assertEqual((self.d / 'tool.log').read_text().splitlines(), ['progress 1 2 spin', 'done'])

    # patch_nso.py's second pass (#177: DM_PATCH_FRONT when the patch area does not fit before the base) asks for a rerun with sys.exit({'rerun_env': ...})
    # only where DM_IN_PROCESS is set, and exec is refused in the worker as in the app's process: the worker lost it (found in review, DAVE THE DIVER)
    RERUN = ("import os, sys\n"
             "assert os.environ.get('DM_IN_PROCESS') == '1', 'the tool takes the exec path'\n"
             "if 'DM_PATCH_FRONT' not in os.environ:\n"
             "    print('first pass', flush=True); sys.exit({'rerun_env': {'DM_PATCH_FRONT': '0x1d0000'}})\n"
             "print('second pass', os.environ['DM_PATCH_FRONT'], os.environ['DM_REWRITE_J'])\n")

    def test_a_tool_that_asks_for_a_rerun_runs_again_with_its_variables(self):
        end, _ = self.run_tool(self.RERUN)
        self.assertEqual(end, 0)
        text = (self.d / 'tool.log').read_text()
        self.assertIn('first pass', text, 'the rerun appends to the first run\'s log')
        self.assertIn('second pass 0x1d0000 2', text)
        self.assertNotIn('DM_PATCH_FRONT', os.environ)
        self.assertNotIn('DM_IN_PROCESS', os.environ)

    def test_a_tool_that_asks_again_for_the_same_fails(self):
        end, _ = self.run_tool("import sys\nprint('asks', flush=True)\nsys.exit({'rerun_env': {'X': '1'}})\n")
        self.assertEqual(end, 1)
        self.assertEqual((self.d / 'tool.log').read_text().count('asks'), 2, 'run once again, then failed')

    def test_only_the_boot_asks_for_a_rerun(self):
        # the tool's own exit 75 is its exit code, and a line like the boot's from a tool that ends otherwise is only output
        end, _ = self.run_tool("import sys\nsys.exit(75)\n")
        self.assertEqual(end, 75)
        end, _ = self.run_tool(f"print({self.pa.RERUN_LINE!r} + '{{\"X\": \"1\"}}')\n")
        self.assertEqual(end, 0)
        end, _ = self.run_tool("import sys\nsys.exit({'not': 'a rerun'})\n")
        self.assertEqual(end, 1)

    def test_a_log_that_cannot_be_written_ends_the_worker(self):
        # the pipe is read in a thread: one that fails must end the call and the worker, not leave the worker blocked on a full pipe and the call waiting
        class Broken:
            def write(self, s): raise OSError(28, 'No space left on device')
            def flush(self): pass
        saved = sys.stdout
        sys.stdout = Broken()   # where ProgressTee passes the progress lines on
        try: end, after = self.run_tool('import time\nprint("progress 1 2 spin", flush=True)\nfor i in range(200000): print("x" * 80)\ntime.sleep(30)\n',
                                        progress=(0, 1000))
        finally: sys.stdout = saved
        self.assertEqual(end, 'OSError')
        self.assertLess(after, 10)


class Wiring(unittest.TestCase):
    def test_in_parallel_takes_it_without_multiprocessing(self):
        self.assertIn('except ImportError: return in_forks(fn, jobs, phase)', SRC)

    def test_the_file_before_it_counts_as_the_same_rewriter(self):
        # the equivalent (scripts/pack_android.py EQUIV: a pack whose key was made with it counts as up to date) is the current file without the forks, and
        # nothing else: only then does a rewrite with it come out the same. main's PR #164 changed the rewriter under this branch (2026-11-20), and the file
        # kept here was still the one before #164: every pack rewritten before #164 would have counted as up to date, where main rewrites them. On dev 0.1.7
        # #177 changed it again (its second pass, 2590f2d2), and 0.1.7 with #290's allocator cuts: the file kept was main's at 54b9ce16, and on dev 0.1.8 #481 changed the rewriter again (its worker processes on Windows) and keeps main's file as its own equivalent, so the one kept here is 0.1.8's at e13378e6; it follows every
        # rewriter change on the branch this lands on
        name = 'patch_nso.py.e13378e630c8'   # dev 0.1.8's file (e13378e6, #481); 0.1.7's, patch_nso.py.54b9ce16465e, is #481's equivalent and stays
        equiv = REPO / 'dm/tools/rewriter_equiv' / name
        self.assertTrue(equiv.is_file())
        for before in ('6bc30f06074b', 'eaf706b807d5', '2590f2d24457'):   # the files before #164, before #177 and before #290
            self.assertFalse((REPO / f'dm/tools/rewriter_equiv/patch_nso.py.{before}').exists())
        self.assertEqual(without_forks(SRC), equiv.read_text(encoding='utf-8'), f'{name} is not patch_nso.py without in_forks: the rewriter changed '
                         'on the branch this is on; the equivalent is then its file (git show <its last commit>:dm/tools/patch_nso.py)')
        self.assertIn(f'dm/tools/rewriter_equiv/{name}\t', (REPO / 'peanuts/upstream-unmarked.txt').read_text(encoding='utf-8'))

    def test_the_pack_script_runs_it_in_the_worker(self):
        pack = (REPO / 'scripts/pack_android.py').read_text(encoding='utf-8')
        self.assertIn("rc = pn_rewrite_worker(REPO / 'dm/tools/patch_nso.py'", pack)
        self.assertIn("start_new_session=True", pack)
        svc = (REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java').read_text(encoding='utf-8')
        self.assertIn('PackEngine.setenv("PN_REWRITE_J"', svc)


if __name__ == '__main__':
    unittest.main()
