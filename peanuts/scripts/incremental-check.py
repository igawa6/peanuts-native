#!/usr/bin/env python3
"""Incremental builds on a phone, measured and compared byte for byte (peanuts/docs/INCREMENTAL-BUILD.md).

Runs on a computer with adb against a development build of the app installed beside the player's (`-PnxAppId=<suffix>`, debuggable: the script reads and
writes its settings with run-as) whose packer is the real one (`-PnxPackStage`). It changes only that app's settings, builds in a folder of its own on the
phone (Tests/<title ID>/ in the app's Build folder), and puts the settings back at the end, also after an error or Ctrl-C. It never sends anything to the player's app.

Scenarios (--scenarios, run in this order; `again`, `new-folder` and `dlc-later` need `full` before them):
  full       a build from nothing: the reference (each step's time, the SHA-256 of the shader library and of each rewritten module)
  again      Import again of that build: its time and what was kept; the library and the modules must be the full build's byte for byte
  new-folder a Build of the same game in another, empty Build folder, the app's build caches kept (no_backup/reuse: another Build folder, a build deleted):
             the .nup made again, the shaders kept from the caches' copy of the library; the library and the modules must be the full build's byte for byte
  dlc-later  copies of the base and the update without the DLC, in the check's folder: a build from them (its addon_missing answered with Try anyway),
             then the DLC added and Import again; the library must be the full build's byte for byte
After each build that kept shaders, the app's full check of them (dev.peanuts.build.ReuseCheck: the packer translates every shader again while the phone
charges) is followed to its end before the next step: its time, and its word that every shader is a full translation's byte for byte. The ready message must
say what came from earlier builds.
Each step's time comes from the app's status file, read every two seconds (the times are good to about that).

Usage: incremental-check.py --package <development build's id> --title <title ID> [--serial <adb serial>] [--scenarios full,again,new-folder,dlc-later]
                           [--out <report.json>] [--keep] [--backup <folder>]
  --serial is needed when adb sees more than one phone; the phone is chosen once, at the start (a device appearing later does not change it).
  --keep leaves the check's folder on the phone (its builds, the copies of the game's files); by default it is deleted at the end.
  --backup is where the app's settings are kept on this computer while the check runs (default: a folder in the temp folder), to put back by hand if the
  computer or adb goes away before the end.
Exit status: 0 when every comparison held, 1 when one did not or a build failed, 2 on a setup error (nothing was changed then).
"""
import argparse
import json
import os
import posixpath
import re
import shlex
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

CTL = 'dev.peanuts.ctl.Command'
NOT_THESE = {'dev.nxnative.android.peanuts', 'dev.nxnative.android.play', 'dev.bananative.android'}   # the player's apps: never driven by this check
TESTS = 'Tests'   # the check's folder in the app's Build folder: <build folder>/Tests/<title ID>/<scenario>/... (the library walks two folders deep, so a check's
                  # .nup files, four deeper, are never listed as a game; nor is it a <title ID>/pack the launcher looks for)
POLL = 2.0
CHECK_END = r'^\[pack\] [0-9-]+ [0-9:]+ check: '   # the line ReuseCheck.log writes when a full check ends (grep -E and re alike)
STEPS = ('import', 'identify', 'rewrite', 'split', 'shaders', 'pack')


class SetupError(Exception): pass


class Phone:
    def __init__(self, serial, package):
        self.serial, self.pkg = serial, package

    def adb(self, *args, binary=False, check=True, timeout=600):
        cmd = ['adb'] + (['-s', self.serial] if self.serial else []) + list(args)
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        if check and r.returncode != 0:
            raise RuntimeError(f'{" ".join(cmd[:6])}...: {r.stderr.decode(errors="replace").strip() or r.stdout.decode(errors="replace").strip()}')
        return r.stdout if binary else r.stdout.decode(errors='replace').replace('\r\n', '\n')

    def sh(self, line, check=True, timeout=600):
        return self.adb('shell', line, check=check, timeout=timeout)

    def run_as(self, line, binary=False, check=True):
        return self.adb('exec-out' if binary else 'shell', f'run-as {self.pkg} sh -c {shlex.quote(line)}', binary=binary, check=check)

    def push_bytes(self, data, dst):
        with tempfile.NamedTemporaryFile(delete=False, suffix='.tmp') as f:
            f.write(data); local = f.name
        try: self.adb('push', local, dst)
        finally: os.unlink(local)

    def status(self):
        """The app's status file; None when it cannot be read now (adb, a file being written): never taken for "nothing runs\""""
        try: raw = self.adb('exec-out', f'cat /sdcard/Android/data/{self.pkg}/files/ctl/status.json', binary=True)
        except (RuntimeError, subprocess.TimeoutExpired): return None
        try: return json.loads(raw.decode('utf-8'))
        except (UnicodeDecodeError, ValueError): return None

    def ctl(self, cmd, wait=True, **args):
        """One command of the control interface (peanuts/docs/CONTROL.md) → its answer (status.json last_result), waited for by its id"""
        token = f'ic{int(time.time() * 1000) % 10**9}'
        extra = []
        for k, v in args.items(): extra += ['-e', k, 'true' if v is True else str(v)]
        self.sh(' '.join(shlex.quote(x) for x in ['am', 'start', '-n', f'{self.pkg}/{CTL}', '-e', 'cmd', cmd, *extra, '-e', 'id', token]))
        if not wait: return None
        for _ in range(60):
            time.sleep(1)
            lr = (self.status() or {}).get('last_result') or {}
            if lr.get('id') == token and not lr.get('pending'): return lr
        raise RuntimeError(f'no answer to {cmd} in 60 s')


def pick_serial(serial):
    """The phone to drive: the one given, else the only phone adb has (an emulator never), else none: another device appearing later must not change it"""
    r = subprocess.run(['adb', 'devices'], capture_output=True, text=True)
    devices = [l.split()[0] for l in r.stdout.splitlines()[1:] if l.strip().endswith('device')]
    if serial:
        if serial not in devices: raise SetupError(f'{serial} is not connected (adb devices: {", ".join(devices) or "none"})')
        return serial
    phones = [d for d in devices if not d.startswith('emulator-')]
    if len(phones) != 1: raise SetupError(f'{len(phones)} phones connected: give --serial')
    return phones[0]


def prefs_with(xml_bytes, strings, drop=None):
    """The app's shared_prefs file with these <string> values set (None: the key removed) and the keys that drop (a regular expression) matches removed"""
    root = ET.fromstring(xml_bytes)
    for e in list(root):
        n = e.get('name', '')
        if n in strings or (drop and re.fullmatch(drop, n)): root.remove(e)
    for k, v in strings.items():
        if v is None: continue
        e = ET.SubElement(root, 'string', name=k); e.text = v
    return b"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" + ET.tostring(root, encoding='utf-8')


EMPTY_LIST = b"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n"


def durations(timeline):
    """[(seconds since start, build step, job step)] → {step: seconds}: import while the build says import, then each of the packer's steps while the job is in it"""
    out = {}
    for (t0, bstep, jstep), (t1, _, _) in zip(timeline, timeline[1:]):
        step = 'import' if bstep == 'import' else jstep if jstep in STEPS else None
        if step: out[step] = out.get(step, 0.0) + (t1 - t0)
    return {k: round(v) for k, v in out.items()}


class Check:
    def __init__(self, phone, title, keep, backup):
        self.p, self.title, self.keep, self.backup = phone, title, keep, backup
        self.saved, self.root = None, None
        self.report = {'title': title, 'package': phone.pkg, 'runs': {}, 'checks': []}

    # settings --------------------------------------------------------------------------------------------------------------------------------------
    def save_settings(self):
        """The app's settings as they are, also into the backup folder on this computer: a check that dies (the computer, adb) leaves them there"""
        self.p.sh(f'am force-stop {self.p.pkg}')
        self.saved = {f: self.p.run_as(f'cat shared_prefs/{f}', binary=True) for f in ('peanuts.xml', 'games.xml')}
        if not self.saved['peanuts.xml'].strip(): raise SetupError('the app has no settings yet: set it up once by hand (game folder, keys, firmware)')
        build = re.search(rb'<string name="build_dir">([^<]+)</string>', self.saved['peanuts.xml'])
        if not build: raise SetupError('the app has no Build folder set (Settings › Library › Build folder)')
        self.root = posixpath.join(build.group(1).decode(), TESTS, self.title)
        if not re.fullmatch(r'/[\w./+-]+', self.root): raise SetupError(f'the Build folder must be a path of letters, digits and ._+-/ for this check: {self.root}')   # its globs
        print(f'the check builds in {self.root}', flush=True)
        os.makedirs(self.backup, exist_ok=True)
        for name, data in self.saved.items():
            with open(os.path.join(self.backup, name), 'wb') as f: f.write(data)
        print(f'the app\'s settings are kept in {self.backup} (put back at the end; by hand: adb push, then run-as cat into shared_prefs/)', flush=True)

    def put_settings(self, files):
        self.p.sh(f'am force-stop {self.p.pkg}')
        for name, data in files.items():
            tmp = f'/data/local/tmp/ic-{name}'
            self.p.push_bytes(data, tmp)
            self.p.sh(f'chmod 644 {tmp}')
            self.p.run_as(f'cat {tmp} > shared_prefs/{name}')
            self.p.sh(f'rm -f {tmp}')
            back = self.p.run_as(f'cat shared_prefs/{name}', binary=True)
            if back != data: raise RuntimeError(f'shared_prefs/{name} did not take the new settings')

    def use(self, folder, game_dir=None, update_dir=None, dlc_dir=None, keep_dirs=True):
        """The app's settings for a run: its own build folder and an empty game list; the game, update and DLC folders as they were, or these"""
        s = {'build_dir': folder}
        if not keep_dirs: s.update({'game_dir': game_dir, 'update_dir': update_dir, 'dlc_dir': dlc_dir})
        # only this folder: the other Build folders (build_dirs_extra) and a game's own one (t.<ID>.build_dir) would be scanned too, and their .nup files hold
        # what the check leaves out (PeanutsPrefs.buildDirs, LibraryScanner roots)
        self.put_settings({'peanuts.xml': prefs_with(self.saved['peanuts.xml'], s, drop=r'build_dirs_extra|t\..*\.build_dir'), 'games.xml': EMPTY_LIST})

    def restore(self):
        """Every step tried, each on its own: one that fails is said, and the others still run"""
        if self.saved is None: return True
        ok = True
        steps = [('the app\'s settings', lambda: self.put_settings(self.saved)),
                 ('the app\'s build caches', lambda: self.p.run_as('rm -rf no_backup/reuse no_backup/xlate no_backup/split.scan'))]
        if not self.keep and self.root: steps.append((self.root, lambda: self.p.sh(f'rm -rf {shlex.quote(self.root)}')))
        for what, step in steps:
            for attempt in range(3):
                try: step(); break
                except Exception as e:
                    if attempt == 2: ok = False; print(f'[RED] could not put back {what}: {e}', flush=True)
                    else: time.sleep(5)
        print(('put back: ' if ok else 'put back in part: ') + ', '.join(w for w, _ in steps) + (f'; the settings are also in {self.backup}' if not ok else ''), flush=True)
        return ok

    # a build ---------------------------------------------------------------------------------------------------------------------------------------
    def build(self, label, cmd, folder, **args):
        """One build through the control interface, followed to its end → its record (times, what was kept, the outputs' hashes)"""
        checks_before = (self.check_count(folder, 'every shader translated again'), self.check_ends(folder))
        ans = self.p.ctl(cmd, title_id=self.title, **args)
        if not ans.get('ok'): raise RuntimeError(f'{label}: {cmd} refused: {ans.get("error")}: {ans.get("message")}')
        t0, timeline, seen, end, paused, unread = time.monotonic(), [], False, None, None, 0
        while True:
            time.sleep(POLL)
            st = self.p.status()
            if st is None:   # not readable now: never an end; a phone gone for 5 minutes is
                unread += 1
                if unread * POLL > 300: raise RuntimeError(f'{label}: the app\'s status could not be read for 5 minutes')
                continue
            unread = 0
            b = st.get('build') or {}; j = st.get('job') or {}
            timeline.append((time.monotonic() - t0, b.get('step'), j.get('step') if j.get('state') == 'running' else None))
            if b.get('running') or j.get('state') == 'running': seen = True; paused = None
            if seen and j.get('state') == 'paused':   # paused from outside (a watcher that lets the phone cool down): wait, up to 20 minutes
                paused = paused or time.monotonic()
                if time.monotonic() - paused < 1200: continue
            if seen and not b.get('running') and j.get('state') != 'running':
                end = b; break
            if time.monotonic() - t0 > 4 * 3600: raise RuntimeError(f'{label}: still building after 4 h')
        total = round(time.monotonic() - t0)
        rec = {'seconds': total, 'steps': durations(timeline), 'failed': bool(end.get('failed')), 'code': end.get('code'), 'error': end.get('error'),
               'message': end.get('detail')}
        print(f'{label}: {total} s, steps {rec["steps"]}' + (f', stopped: {rec["code"]} {rec["error"]}' if rec['failed'] else ''), flush=True)
        if rec['message']: print(f'  the ready message: {rec["message"]}', flush=True)
        rec['check'] = None if rec['failed'] else self.wait_check(label, folder, checks_before)
        rec.update(self.outputs(folder))   # after the check: a library it replaced is the one the pack has
        for l in rec['reuse']: print(f'  {l}', flush=True)
        return rec

    def check_log(self, folder): return f'{folder}/{self.title}/{self.title}-work/ondevice/logs/check.log'

    def check_count(self, folder, words):
        """How many lines of this folder's job's check.log hold words (fixed text)"""
        out = self.p.sh(f"grep -cF {shlex.quote(words)} {shlex.quote(self.check_log(folder))} 2>/dev/null || true").strip()
        return int(out) if out.isdigit() else 0

    def check_ends(self, folder):
        """How many full checks of this folder's job have ended (ReuseCheck.log's "[pack] <date> <time> check: …" lines in its check.log)"""
        out = self.p.sh(f"grep -cE {shlex.quote(CHECK_END)} {shlex.quote(self.check_log(folder))} 2>/dev/null || true").strip()
        return int(out) if out.isdigit() else 0

    def wait_check(self, label, folder, before):
        """The full check a build that kept shaders leaves due (check.todo), which the app starts at the build's end while the phone is plugged in with 40 % or
        more: followed to its end (up to 40 minutes) → {'seconds', 'lines', 'ok', 'replaced'}; None when none was due; {'started': False} when none started
        within 3 minutes (the launcher's timer tries every two). before: (the check.log's start lines, its end lines) before the build"""
        starts, ends = before
        due = self.p.run_as(f'test -f no_backup/reuse/{self.title}/check.todo && echo due || echo none', check=False).strip() == 'due'
        if not due and self.check_ends(folder) == ends: return None
        t0 = time.monotonic()
        while self.check_ends(folder) == ends:
            time.sleep(5)
            if time.monotonic() - t0 > 180 and self.check_count(folder, 'every shader translated again') == starts:
                print(f'{label}: the full check did not start within 3 minutes (below 40 % or not plugged in? the build log says why)', flush=True)
                return {'started': False}
            if time.monotonic() - t0 > 2400: raise RuntimeError(f'{label}: the full check still runs after 40 minutes')
        text = self.p.sh(f'cat {shlex.quote(self.check_log(folder))} 2>/dev/null || true')
        last = text[text.rfind('\n== ') + 1:] if '\n== ' in text else text
        lines = [l.strip() for l in last.splitlines() if 'reuse full check' in l or re.search(CHECK_END, l)]
        rec = {'seconds': round(time.monotonic() - t0), 'lines': lines, 'ok': any("are a full translation's byte for byte" in l for l in lines),
               'replaced': any('[RED] reuse full check' in l for l in lines)}
        print(f'{label}: the full check: {rec["seconds"]} s after the build', flush=True)
        for l in lines: print(f'  {l}', flush=True)
        return rec

    def checked(self, what, rec):
        """The full check after a build that kept shaders ran and found every shader a full translation's"""
        c = rec.get('check') or {}
        ok = bool(c.get('ok'))
        self.report['checks'].append({'check': what, 'ok': ok})
        print(f'{"OK  " if ok else "FAIL"} {what}', flush=True)
        return ok

    def said(self, what, rec, words):
        ok = words in (rec.get('message') or '')
        self.report['checks'].append({'check': what, 'ok': ok})
        print(f'{"OK  " if ok else "FAIL"} {what}', flush=True)
        return ok

    def outputs(self, folder):
        """The pack's library and rewritten modules (SHA-256) and the log's reuse lines; adb failing here is an error, not an empty pack"""
        base = f'{folder}/{self.title}'
        lib = self.p.sh(f'ls {base}/pack/shaders/*/dmsh_vk.lib >/dev/null 2>&1 && sha256sum {base}/pack/shaders/*/dmsh_vk.lib || true').split()
        mods = {}
        for l in self.p.sh(f'ls {base}/pack/*.patched.bin >/dev/null 2>&1 && sha256sum {base}/pack/*.patched.bin || true').splitlines():
            c = l.split()
            if len(c) == 2: mods[posixpath.basename(c[1]).split('.')[0]] = c[0]
        logs = f'{base}/{self.title}-work/ondevice/logs'
        reuse = [l.strip() for l in self.p.sh(f"cat {logs}/split.log {logs}/shaders.log {logs}/rewrite.log 2>/dev/null | grep -E 'reuse|kept,|kept from' || true").splitlines()]
        return {'library': lib[0] if lib else None, 'modules': mods, 'reuse': reuse[-40:]}

    def used(self, what, rec, pattern):
        """A cache this run had to use, by its log lines: the outputs alone would be the same from a translation of everything"""
        ok = any(re.search(pattern, l) for l in rec.get('reuse') or [])
        self.report['checks'].append({'check': what, 'ok': ok})
        print(f'{"OK  " if ok else "FAIL"} {what}', flush=True)
        return ok

    def same(self, what, a, b, field):
        ok = a.get(field) is not None and a.get(field) == b.get(field)
        self.report['checks'].append({'check': what, 'ok': ok})
        print(f'{"OK  " if ok else "FAIL"} {what}', flush=True)
        return ok

    # scenarios -------------------------------------------------------------------------------------------------------------------------------------
    def fresh(self, name, keep_caches=False, **dirs):
        folder = f'{self.root}/{name}'
        self.p.run_as('rm -rf ' + ('no_backup/xlate no_backup/split.scan' if keep_caches else 'no_backup/reuse no_backup/xlate no_backup/split.scan'), check=False)
        q = shlex.quote(folder)
        self.p.sh(f'rm -rf {q}; mkdir -p {q}')
        self.use(folder, **dirs)
        self.p.ctl('scan'); self.wait_scan()
        return folder

    def wait_scan(self):
        for _ in range(120):
            time.sleep(1)
            st = self.p.status()
            if st is not None and not ((st.get('screen') or {}).get('home') or {}).get('scanning'): return

    def run(self, scenarios):
        runs = self.report['runs']
        if 'full' in scenarios:
            self.full_folder = self.fresh('full')
            runs['full'] = self.build('full build', 'build', self.full_folder)
        if 'again' in scenarios:
            runs['again'] = self.build('Import again', 'reimport', self.full_folder, confirm=True)
            self.same('Import again: the shader library is the full build\'s byte for byte', runs['again'], runs['full'], 'library')
            self.same('Import again: the modules are the full build\'s byte for byte', runs['again'], runs['full'], 'modules')
            self.checked('Import again: the full check found every shader a full translation\'s', runs['again'])
            self.said('Import again: the ready message says what came from earlier builds', runs['again'], 'came from earlier builds')
        if 'new-folder' in scenarios:
            folder = self.fresh('new-folder', keep_caches=True)
            runs['new-folder'] = self.build('another Build folder, the caches kept: Build', 'build', folder)
            self.same('Another Build folder: the shader library is the full build\'s byte for byte', runs['new-folder'], runs['full'], 'library')
            self.same('Another Build folder: the modules are the full build\'s byte for byte', runs['new-folder'], runs['full'], 'modules')
            self.used('Another Build folder: shaders kept from the app\'s copy of the library', runs['new-folder'], r'/library/v-[0-9a-f]+/dmsh_vk\.lib: [1-9]\d* shaders kept')
            self.used('Another Build folder: modules put back from the app\'s copies', runs['new-folder'], r'kept from the app\'s copy of its last rewrite')
            self.used('Another Build folder: game files kept by the split', runs['new-folder'], r'split reuse: [1-9]\d* of \d+ files kept')
            self.checked('Another Build folder: the full check found every shader a full translation\'s', runs['new-folder'])
            self.said('Another Build folder: the ready message says what came from earlier builds', runs['new-folder'], 'came from earlier builds')
        if 'dlc-later' in scenarios:
            files = (self.p.ctl('reimport', title_id=self.title).get('data') or {})   # without confirm: what an Import again would read, nothing changed
            base, update, dlc = files.get('base'), files.get('update'), files.get('dlc') or []
            if not base or not dlc: raise RuntimeError(f'dlc-later needs a game with DLC: base {base}, DLC {dlc}')
            games = f'{self.root}/games'
            q = shlex.quote(games)
            self.p.sh(f'rm -rf {q}; mkdir -p {q}')
            for f in [base] + ([update] if update else []):
                print(f'copying {posixpath.basename(f)}', flush=True)
                self.p.sh(f'cp {shlex.quote(f)} {shlex.quote(games + "/")}', timeout=3600)
            folder = self.fresh('dlc-later', keep_dirs=False, game_dir=games, update_dir=games, dlc_dir=None)
            first = self.build('build without the DLC', 'build', folder)
            if first['failed'] and first['code'] == 'addon_missing':
                first = self.build('build without the DLC (Try anyway)', 'job', folder, do='try_anyway')
            runs['dlc-before'] = first
            self.use(folder, keep_dirs=False, game_dir=games, update_dir=games, dlc_dir=posixpath.dirname(dlc[0]))
            self.p.ctl('scan'); self.wait_scan()
            runs['dlc-after'] = self.build('the DLC added: Import again', 'reimport', folder, confirm=True)
            self.same('DLC added later: the shader library is the full build\'s byte for byte', runs['dlc-after'], runs['full'], 'library')
            self.same('DLC added later: the modules are the full build\'s byte for byte', runs['dlc-after'], runs['full'], 'modules')
            self.checked('DLC added later: the full check found every shader a full translation\'s', runs['dlc-after'])
        return all(c['ok'] for c in self.report['checks']) and not any(r['failed'] for r in runs.values() if r is not runs.get('dlc-before'))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--package', required=True); ap.add_argument('--title', required=True); ap.add_argument('--serial')
    ap.add_argument('--scenarios', default='full,again,new-folder,dlc-later'); ap.add_argument('--out'); ap.add_argument('--keep', action='store_true')
    ap.add_argument('--backup', help='where the app\'s settings are kept on this computer while the check runs (default: a folder in the temp folder)')
    a = ap.parse_args()
    if a.package in NOT_THESE: sys.exit(f'{a.package} is a player\'s app: give the development build\'s id (-PnxAppId)')
    if not re.fullmatch(r'[0-9A-Fa-f]{16}', a.title): sys.exit(f'{a.title} is no title ID')
    scenarios = [s for s in a.scenarios.split(',') if s]
    if any(s not in ('full', 'again', 'new-folder', 'dlc-later') for s in scenarios): sys.exit(f'unknown scenario in {a.scenarios}')
    if any(s in scenarios for s in ('again', 'new-folder', 'dlc-later')) and 'full' not in scenarios: sys.exit('again, new-folder and dlc-later compare with full: run it too')
    try: serial = pick_serial(a.serial)
    except SetupError as e: print(f'setup: {e}', file=sys.stderr); return 2
    phone = Phone(serial, a.package)
    check = Check(phone, a.title.upper(), a.keep, a.backup or os.path.join(tempfile.gettempdir(), f'incremental-check-{a.package}'))
    try:
        if f'package:{a.package}' not in phone.sh('pm list packages').split(): raise SetupError(f'{a.package} is not installed')   # whole lines: a.package must not pass as the start of another app's id
        if phone.run_as('echo ok', check=False).strip() != 'ok': raise SetupError(f'{a.package} is not debuggable (run-as)')
        check.save_settings()
    except SetupError as e:
        print(f'setup: {e}', file=sys.stderr); return 2
    ok = False
    try: ok = check.run(scenarios)
    except KeyboardInterrupt: print('stopped', flush=True)
    except Exception as e: print(f'error: {e}', flush=True); check.report['error'] = str(e)
    finally:
        try: phone.ctl('job', do='stop', wait=False)
        except Exception: pass
        if not check.restore(): ok = False
    check.report['ok'] = ok
    if a.out:
        with open(a.out, 'w', encoding='utf-8') as f: json.dump(check.report, f, indent=1)
    print('all comparisons held' if ok else 'a comparison or a build failed', flush=True)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
