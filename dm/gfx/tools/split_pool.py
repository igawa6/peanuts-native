"""A process pool over pipes for a Python that has no python executable and no _multiprocessing: the on-device packer (android/ONDEVICE.md) runs the tools in
CPython embedded in the app, and the official Android build has no sem_open, so multiprocessing's queues, locks and pools (and concurrent.futures'
ProcessPoolExecutor on them) cannot run there. shader_split.py's scan uses this pool when DM_SPLIT_WORKER=<command> names what runs a worker: a program that
takes `<flags> -c <code> <args>` as python3 does (the phone's nxpack_worker, dm/peanuts/pack_worker, through /system/bin/linker64; python3 itself on a computer).

J workers, each started as `<command> -c BOOT <read fd> <write fd>` and fed one job at a time over a pipe of its own; a thread of this process per worker feeds
it and fulfils its futures. submit / map / with as concurrent.futures' executors. vk_android_build.py's shaders step uses it too (DMSH_VK_WORKER, a script: PipePool argv).
Each worker imports the calling tool as __mp_main__ (and __main__, as multiprocessing's spawn does), so the tool's functions pickle by name as to any pool worker; it runs the initializer, says it is ready, then answers each
(function, args) with (True, result) or (False, exception, traceback text). A worker that does not come up (the command does not run, its Python does not start,
the tool does not import) raises at construction, with the worker's own words in the caller's log: its stdout and stderr are the caller's."""
import collections, concurrent.futures as cf, os, pickle, subprocess, sys, threading

BOOT = r'''
import os, pickle, runpy, sys, traceback, types
if os.name == 'nt':   # PEANUTS(upstream-candidate): Windows passes the pipes as inherited handles (PipePool), not descriptors
    import msvcrt
    r, w = os.fdopen(msvcrt.open_osfhandle(int(sys.argv[1]), os.O_RDONLY), 'rb'), os.fdopen(msvcrt.open_osfhandle(int(sys.argv[2]), 0), 'wb')
else: r, w = os.fdopen(int(sys.argv[1]), 'rb'), os.fdopen(int(sys.argv[2]), 'wb')
path, tool, *script = pickle.load(r)
sys.path[:] = path
def serve():
    init, args = pickle.load(r)
    if init: init(*args)
    w.write(pickle.dumps((True, 'ready'))); w.flush()
    while True:
        try: fn, a = pickle.load(r)
        except EOFError: break
        try: out = (True, fn(*a))
        except BaseException as e: out = (False, e, traceback.format_exc())
        try: data = pickle.dumps(out)
        except Exception as e: data = pickle.dumps((False, RuntimeError(f'{getattr(fn, "__name__", fn)}: its answer does not pickle: {e!r}'), traceback.format_exc()))
        w.write(data); w.flush()
if script:   # a tool that is a script (PipePool argv): run as __main__ with the caller's arguments; it calls PIPE_POOL_SERVE() where its setup ends, and does not return
    sys.argv[:], g = script
    runpy.run_path(tool, run_name='__main__', init_globals=dict(g, PIPE_POOL_SERVE=serve))
    raise SystemExit('the tool ended without serving (PIPE_POOL_SERVE)')
m = types.ModuleType('__mp_main__'); m.__dict__.update(runpy.run_path(tool, run_name='__mp_main__')); sys.modules['__main__'] = sys.modules['__mp_main__'] = m
serve()
'''


def _fileno(stream):
    try: return stream.fileno()
    except (AttributeError, OSError, ValueError): return None


def _start(args, cr, cw, pass_fds, out, err):
    """A worker reading jobs from descriptor cr and writing answers to cw, its stdout and stderr this process's. PEANUTS(upstream-candidate): Windows has no
    pass_fds: the pipes' handles are made inheritable and handed to that worker alone (handle_list), and the worker opens them by handle (BOOT). Before, the
    pool could not start there, so vk_android_build.py ran every shader in one interpreter and its Python half on one core (CPU at 25-50 % with J=8)"""
    if os.name != 'nt':
        return subprocess.Popen(args + [str(cr), str(cw)], pass_fds=(cr, cw, *pass_fds), stdin=subprocess.DEVNULL, stdout=out, stderr=err)
    if pass_fds: raise ValueError('pass_fds: a Windows worker receives only its two pipes')
    import msvcrt
    hs = [msvcrt.get_osfhandle(fd) for fd in (cr, cw)]
    for h in hs: os.set_handle_inheritable(h, True)
    si = subprocess.STARTUPINFO(); si.lpAttributeList = {'handle_list': list(hs)}   # subprocess adds the standard handles
    return subprocess.Popen(args + [str(h) for h in hs], startupinfo=si, close_fds=True, stdin=subprocess.DEVNULL, stdout=out, stderr=err)


class PipePool:
    """J worker processes of command (a list: the program and its leading arguments), each running tool's module as __mp_main__ and initializer(*initargs) first.
    pass_fds: descriptors of this process the workers need too (shader_split.py: a .nup read through DM_NUP_FD).
    argv: tool is a script with no import form (vk_android_build.py): each worker runs it as __main__ with sys.argv = argv and init_globals (a dict of plain data) plus
    PIPE_POOL_SERVE, a function the script calls once its setup is done (it serves the jobs, as a module tool's worker does after the import) and that returns at the end;
    the functions submitted are the script's own, by name, as the caller runs it as __main__ too"""
    def __init__(self, command, J, tool, initializer=None, initargs=(), pass_fds=(), argv=None, init_globals=None):
        self.jobs, self.cv, self.closed = collections.deque(), threading.Condition(), False
        self.workers, self.threads, self.live = [], [], 0
        out, err = _fileno(sys.stdout), _fileno(sys.stderr)
        try:
            for _ in range(J):
                cr, pw = os.pipe()   # jobs: this process writes pw, the worker reads cr
                pr, cw = os.pipe()   # answers: the worker writes cw, this process reads pr
                try: p = _start(list(command) + ['-c', BOOT], cr, cw, pass_fds, out, err)
                finally: os.close(cr); os.close(cw)
                self.workers.append((p, os.fdopen(pw, 'wb'), os.fdopen(pr, 'rb')))
            for p, w, r in self.workers:   # the tool and the initializer to each, once every worker is started: they import the tool at the same time
                w.write(pickle.dumps((sys.path, tool) if argv is None else (sys.path, tool, list(argv), dict(init_globals or {})))); w.write(pickle.dumps((initializer, initargs))); w.flush()
            for p, w, r in self.workers: self._answer(p, r)   # ready, or why not
        except BaseException:
            self.shutdown(cancel=True)
            raise
        self.live = len(self.workers)
        for i, wk in enumerate(self.workers):
            t = threading.Thread(target=self._serve, args=wk, name=f'split-pool-{i}', daemon=True)
            t.start(); self.threads.append(t)

    def _answer(self, p, r):
        """The worker's next answer; a worker that ended instead raises, with its exit code (its last words are in the log: its stderr is this process's)"""
        try: return pickle.load(r)
        except (EOFError, pickle.UnpicklingError, OSError) as e:
            try: code = p.wait(timeout=10)
            except subprocess.TimeoutExpired: p.kill(); code = p.wait()
            raise RuntimeError(f'the worker (pid {p.pid}) ended with exit code {code} instead of answering ({e!r})') from None

    def _serve(self, p, w, r):
        while True:
            with self.cv:
                while not self.jobs and not self.closed: self.cv.wait()
                if not self.jobs: return
                f, fn, a = self.jobs.popleft()
            if not f.set_running_or_notify_cancel(): continue
            try:
                w.write(pickle.dumps((fn, a))); w.flush()
                ok, *rest = self._answer(p, r)
            except BaseException as e:   # the worker is gone: this job fails; the queued ones go to the other workers, or fail when it was the last
                f.set_exception(e)
                with self.cv:
                    self.live -= 1
                    if self.live == 0:
                        for g, *_ in self.jobs: g.set_exception(RuntimeError('every pool worker has ended'))
                        self.jobs.clear(); self.closed = True; self.cv.notify_all()
                return
            if ok: f.set_result(rest[0])
            else:
                e, tb = rest
                e.__cause__ = RuntimeError('in the pool worker:\n' + tb)
                f.set_exception(e)

    def submit(self, fn, *a):
        f = cf.Future()
        with self.cv:
            if self.closed: raise RuntimeError('the pool is shut down')
            self.jobs.append((f, fn, a)); self.cv.notify()
        return f

    def map(self, fn, it):
        fs = [self.submit(fn, x) for x in it]
        return (f.result() for f in fs)

    def shutdown(self, cancel=False):
        """Ends the workers once the queued jobs are done; cancel: at once, the queued jobs cancelled and the running ones ended with their workers"""
        with self.cv:
            self.closed = True
            if cancel:
                for f, *_ in self.jobs: f.cancel()
                self.jobs.clear()
            self.cv.notify_all()
        if cancel:
            for p, w, r in self.workers: p.terminate()
        for t in self.threads: t.join()
        for p, w, r in self.workers:
            for s in (w, r):
                try: s.close()
                except OSError: pass
            try: p.wait(timeout=30)
            except subprocess.TimeoutExpired: p.kill(); p.wait()

    def __enter__(self): return self
    def __exit__(self, et, ev, tb): self.shutdown(cancel=et is not None)
