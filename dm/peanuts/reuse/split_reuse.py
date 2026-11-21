#!/usr/bin/env python3
# PEANUTS (issue #120, peanuts/UPSTREAM-EDITS.md Round 26): the shader split keeps, across builds, the scan result of every game file whose content did not change,
# and scans only the others. dm/gfx/tools/shader_split.py's pooled() asks it before a scan (fill) and gives it the results after (store); see
# peanuts/docs/INCREMENTAL-BUILD.md, "The split".
#
# Why: the split scans every file of the profile's list (targeted) or of the RomFS (full) on every build, also an Import again or an added DLC that changes no file
# (ACNH on an AYN Thor: 56 s of an Import again's 2 minutes; Donkey Kong Bananza 4 min 51 s; a full scan of a large game an hour and more). Its ScanCache keeps
# results only to resume a stopped split, keyed by the job's index and the container's modification time, and is deleted when the split is done.
#
# Which results are kept: those of a plain file job (path, relative path), the file inside a .nup, whose table of contents has each file's SHA-256 (dm/tools/nup.py
# record). A job's key: that SHA-256 and size; the names and SHA-256s of the files beside it whose name starts with its stem (what a plain file's reader opens
# besides it: stem + '.dict', stem + '.patch', each name a Next Level Games index lists; the readers that take other files, LINKDATA, .bdt, Unreal paks, Trinity
# packs, are archive jobs); the job's DIRECT entry (the direct scan's recorded containers); and a common part: the tools' and the profile's sources
# (shader_split.py digest_sources), Unity's common strings from main (COMMON), the zstd dictionaries (ZDICTS), DM_SPLIT_SELECTED (KEEP), the split's environment
# and Python's version. Archive ranges (several files, ranges planned over the whole archive), a plaintext NSP or a folder dump (no hashes) and the Unity second
# pass are scanned as before. A result with no shader goes into a list file, one with shaders into a file of its own (<PN_REUSE_DIR>/split/<common part>/).
#
# The check: whole batches that could be kept, one in SAMPLE_SHARE (at least SAMPLE_MIN, picked by a hash of their keys and a salt drawn at each split, so
# that the split, which has no full check after it, meets every kept file over its builds), are scanned anyway, and each of their
# results must equal the kept one (as values: sets and dicts in any order). One that differs raises Stale: the caller drops what was filled in and scans the
# files again, and the kept results of this common part are deleted. The split's own checks after it (its tables against the profile, the absences) stay.
# Anything else that goes wrong here (a file that cannot be read or written) turns the reuse off for the run with one line; it is never a reason to fail.
import hashlib
import io
import os
import pickle
import posixpath
import shutil
import sys
import time
from pathlib import Path

FORMAT = 'pn-split 1'
SAMPLE_SHARE, SAMPLE_MIN, CHECK_FROM = 50, 1, 3   # batches: one in SAMPLE_SHARE checked, at least SAMPLE_MIN, when at least CHECK_FROM could be kept
REPO = Path(__file__).resolve().parents[3]
ENV = ('DM_GAME', 'DM_GLSLC_CAPTURE', 'DM_UE_SHADERS', 'DM_NRO_DIR', 'DM_PACKER_COMMIT')


class Stale(Exception):
    """A result scanned to check differs from the kept one: the kept results are not trusted for this split"""


def canon(x):
    """x as plain, ordered data: the same for equal values whatever the order of a set's or a dict's items (pickle's bytes are not: a set of strings iterates
    in an order that changes with each process's hash seed); an object (unityfs.Node) by its class and attributes"""
    if isinstance(x, dict): return ('d', sorted(((canon(k), canon(v)) for k, v in x.items()), key=repr))
    if isinstance(x, (set, frozenset)): return ('s', sorted((canon(v) for v in x), key=repr))
    if isinstance(x, (list, tuple)): return (type(x).__name__[0], [canon(v) for v in x])
    if isinstance(x, (bytes, bytearray, memoryview)): return ('b', bytes(x))
    if hasattr(x, '__dict__'): return ('o', type(x).__name__, canon(vars(x)))
    return x


def digest(*parts):
    h = hashlib.sha256()
    for p in parts: h.update(repr(p).encode() if not isinstance(p, bytes) else p); h.update(b'\0')
    return h.hexdigest()


def own_sources():
    """What decides a result besides the tools digest_sources lists: this module (what is kept, canon) and the C Yaz0 decoder shader_split.py takes when it is
    there (_nxyaz0: its bytes are the loop's or None, but a module of another build must not keep results it made). → [(name, its SHA-256)]"""
    out = [('split_reuse.py', hashlib.sha256(Path(__file__).read_bytes()).digest())]
    import importlib.util
    spec = importlib.util.find_spec('_nxyaz0')
    if spec is None: out.append(('_nxyaz0', b'none'))
    elif spec.origin and os.path.isfile(spec.origin): out.append(('_nxyaz0', hashlib.sha256(Path(spec.origin).read_bytes()).digest()))
    else: out.append(('_nxyaz0', f'module {spec.origin}'.encode()))   # (built in: no file to hash)
    return out


class Context:
    """One split's reuse: the common part of its keys, the folder of that part's kept results, and what fill() put into the ScanCache (forget() takes it out)"""

    def __init__(self, common, zdicts, direct, keep, sources, unpickler):
        sys.path.insert(0, str(REPO / 'dm/tools'))
        import nup
        self.nup, self.unpickler = nup, unpickler
        self.direct = direct or {}
        h = hashlib.sha256(FORMAT.encode() + b'\0')
        for p in sources:
            h.update(f'{os.path.basename(p)}\0'.encode())
            with open(p, 'rb') as f: h.update(hashlib.sha256(f.read()).digest())
        for name, sha in own_sources(): h.update(f'{name}\0'.encode() + sha)
        h.update(repr(canon(common)).encode()); h.update(repr(canon(zdicts)).encode())
        h.update(repr(sorted(keep) if keep is not None else None).encode())
        h.update(repr([(k, os.environ.get(k)) for k in ENV] + sorted((k, v) for k, v in os.environ.items() if k.startswith('DM_SPLIT_'))).encode())
        h.update(sys.version.encode())
        self.part = h.hexdigest()[:32]
        self.root = Path(os.environ['PN_REUSE_DIR']) / 'split' / self.part
        self.off, self.loads_ok, self.keys = None, False, []
        self.dirs, self.filled, self.expect, self.kept, self.checked = {}, [], {}, 0, 0
        try:
            for d in (self.root.parent.iterdir() if self.root.parent.is_dir() else []):   # one common part a game: older ones (other tools, another main) go
                if d.name != self.part: shutil.rmtree(d, ignore_errors=True)
            self.root.mkdir(parents=True, exist_ok=True)
            for f in self.root.rglob('*.tmp'): f.unlink(missing_ok=True)   # what a split stopped while it wrote left (the packer runs one job at a time)
            self.empty = self._load_empty()
        except Exception as e: self._stop(e)

    # keys -----------------------------------------------------------------------------------------------------------------------------------------------
    def _node(self, path):
        r = self.nup.resolve(path)
        if r is None: return None
        n, k = r
        if n.is_dir(k) or getattr(n, 'synthetic', False): return None
        return n, k

    def _siblings(self, path):
        """The digest of the files and folders beside path whose name starts with its stem (the name without its last extension): what a reader may open besides the file
        (shader_split.py: stem + '.dict', stem + '.patch', Next Level Games' stem + each name its index lists), path itself among them; None when one has no hash"""
        p = path.replace('\\', '/')
        parent, stem = posixpath.dirname(p), posixpath.splitext(posixpath.basename(p))[0]
        if parent not in self.dirs:
            try:
                file, rest = self.nup.split(parent)
                n = self.nup.open_nup(file); k = n.node(rest)
                # a folder too: a reader's os.path.exists(stem + '.dict') is true for one (found in review); a folder's mark is never a file's hash
                self.dirs[parent] = sorted((name, b'folder' if n.is_dir(c) else n.record(c)[3]) for name, c in n.children(k))
            except Exception: self.dirs[parent] = None
        files = self.dirs[parent]
        if files is None: return None
        near = [(name, sha) for name, sha in files if name.startswith(stem)]
        if any(sha == bytes(32) for _, sha in near): return None
        return digest(*[x for name, sha in near for x in (name, sha)])

    def key(self, job):
        """A plain file job's key, or None (an archive range, a file without a hash: scanned as before)"""
        if self.off or len(job) != 2: return None
        path, rel = job
        try:
            r = self._node(path)
            if r is None: return None
            n, k = r
            size, _, _, sha, _ = n.record(k)
            if sha == bytes(32): return None
            near = self._siblings(path)
            if near is None: return None
        except Exception: return None
        return digest(self.part, rel, sha, size, near, canon(self.direct.get(rel)))

    # the kept results -----------------------------------------------------------------------------------------------------------------------------------
    def _load_empty(self):
        out = {}
        for f in sorted(self.root.glob('empty-*.pkl')):
            try:
                with open(f, 'rb') as fh: part = self.unpickler(fh).load()
                if isinstance(part, dict): out.update(part)
            except Exception: f.unlink(missing_ok=True)
        if len(list(self.root.glob('empty-*.pkl'))) > 16 and out:   # many small lists: one again
            self._write('empty-0.pkl', pickle.dumps(out, pickle.HIGHEST_PROTOCOL))
            for f in self.root.glob('empty-*.pkl'):
                if f.name != 'empty-0.pkl': f.unlink(missing_ok=True)
        return out

    def _path(self, key): return self.root / key[:2] / f'{key}.pkl'

    def _has(self, key): return key in self.empty or self._path(key).is_file()

    def _get(self, key):
        """The kept result of key; None when its file does not read as one (cut short by a reboot after its rename, or not a result): the file goes and the
        job is scanned, so one damaged file does not turn the reuse off on every later split"""
        if key in self.empty: return self.empty[key]
        try:
            with open(self._path(key), 'rb') as f: r = self.unpickler(f).load()
            if isinstance(r, tuple) and len(r) == 4: return r
        except Exception: pass
        self._path(key).unlink(missing_ok=True)
        return None

    def _write(self, rel, data):
        """data to rel, whole or not at all: a temporary file of this process, flushed to storage, then renamed"""
        p = self.root / rel; p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(f'{p.name}.{os.getpid()}.tmp')
        with open(tmp, 'wb') as f: f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(tmp, p)

    def _stop(self, e):
        self.off = str(e)
        print(f'[RED] split reuse: {e}: off for this split (every file is scanned)', flush=True)

    # the split's side -----------------------------------------------------------------------------------------------------------------------------------
    def fill(self, cache, jobs, start, chunk):
        """The kept results into the ScanCache (pooled() then scans only the others); the files to check are left out and their kept results noted"""
        if self.off: return
        try:
            self.keys = [self.key(j) for j in jobs]
            got = {i: r for i, k in enumerate(self.keys) if k is not None and start + i not in cache.have and self._has(k) and (r := self._get(k)) is not None}
            can = sorted(got)
            salt = os.urandom(16)   # another sample at every split: there is no full check after it, so a kept result that went stale is met within builds, not never
            order = sorted(can, key=lambda i: digest('pn-sample', salt, self.keys[i]))
            n = min(len(can), max(SAMPLE_MIN, -(-len(can) // SAMPLE_SHARE)))
            check = set(order[:n]) if len(can) >= CHECK_FROM else set()   # too few to spare one: all kept (the split's tables are still compared with the profile)
            for i in can:
                if i in check: self.expect[i] = got[i]
                else: cache.have[start + i] = got[i]; self.filled.append(start + i)
            self.kept += len(self.filled); self.checked += len(self.expect)
        except Exception as e:
            self.forget(cache); self._stop(e)

    def batches(self, jobs, start, chunk, cache):
        """pooled()'s batches: each job the cache holds a batch of its own (kept, not scanned), the others in runs of up to chunk. The ScanCache keeps results by
        the job's index, so batches cut otherwise than a split before still resume it"""
        out, run = [], None
        for i, j in enumerate(jobs):
            if start + i in cache.have: out.append((start + i, [j])); run = None
            elif run is None or len(run[1]) >= chunk: run = (start + i, [j]); out.append(run)
            else: run[1].append(j)
        return out

    def store(self, jobs, results, start):
        """After the scan: the results to check compared with the kept ones (Stale when one differs), the new ones kept"""
        if self.off: return
        differ = [jobs[i][1] for i, old in self.expect.items() if canon(results[i]) != canon(old)]
        if differ:
            print(f'[RED] split reuse: {len(differ)} of {len(self.expect)} files scanned to check differ from their kept results ({", ".join(differ[:3])}): the files are '
                  f'scanned again and this split\'s kept results deleted', flush=True)
            shutil.rmtree(self.root, ignore_errors=True)
            raise Stale(differ)
        try:
            filled = set(self.filled)
            empty = {}
            for i, (k, r) in enumerate(zip(self.keys, results)):
                if k is None or start + i in filled or self._has(k): continue
                if isinstance(r[3], tuple): continue   # ('unreadable', why): maybe a read that failed this once; scanned again next time
                if not r[1] and not r[2]: empty[k] = r
                else:
                    data = pickle.dumps(r, pickle.HIGHEST_PROTOCOL)
                    if not self.loads_ok: check_pickle(data, self.unpickler); self.loads_ok = True   # a type the reader refuses: nothing kept (_stop)
                    self._write(f'{k[:2]}/{k}.pkl', data)
            if empty:
                data = pickle.dumps(empty, pickle.HIGHEST_PROTOCOL)
                check_pickle(data, self.unpickler)
                self._write(f'empty-{int(time.time() * 1000)}-{os.getpid()}.pkl', data)
                self.empty.update(empty)
        except Exception as e: self._stop(e)
        cached = sum(1 for k in self.keys if k is not None)
        same = f' (the same: {", ".join(jobs[i][1] for i in sorted(self.expect)[:8])})' if self.expect else ''   # none scanned: no empty list
        print(f'split reuse: {len(self.filled)} of {len(jobs)} files kept (unchanged since a split before), {len(self.expect)} of them scanned to check{same}, '
              f'{len(jobs) - cached} not keyed (archives, files without a hash)', flush=True)

    def forget(self, cache):
        """What fill() put into the ScanCache taken out again: those files are scanned. cache None: the split keeps no ScanCache (no DM_SPLIT_RESUME), and the
        one pooled() made for the call went with it"""
        if cache is not None:
            for i in self.filled: cache.have.pop(i, None)
        self.filled, self.expect = [], {}


def check_pickle(data, unpickler):
    """data loads through the split's ResultUnpickler (its allow-list): a result type it refuses is not kept"""
    unpickler(io.BytesIO(data)).load()
