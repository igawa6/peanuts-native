#!/usr/bin/env python3
# Peanuts Native in one command: game files -> a .nup -> a pack for the Android app -> (with --push) the phone.
#   .nsp / .xci files      nximport_cli (dm/peanuts/import, built here with cmake when it is missing or older than its sources) makes the .nup
#   a .nup or a dump       the import is skipped; the pack is made from it as it is
#   the pack               scripts/pack_android.py (its own options go after --)
#   --push                 peanuts/scripts/push-pack.py copies the pack and the game to the phone over adb
# Stdlib only, Python 3.9+. The launchers peanuts-build.command (macOS), peanuts-build.sh (Linux) and peanuts-build.bat (Windows) run this with the packer's Python.
#   peanuts_build.py "Game [01006BB00C6F0000].nsp" "Game [01006BB00C6F0000] [Update v1.1.0].nsp" --keys ~/prod.keys   base + update: the .nup beside the first file, then the pack
#   peanuts_build.py "Game folder with .nsp files" --update 131072 --push                                              pick an update, then copy the pack and the .nup to the phone
#   peanuts_build.py "Game [01006BB00C6F0000][v131072].nup" --push --dest "/sdcard/Peanut Roms/Game" -- -j 8         a .nup; the words after -- go to pack_android.py
#   peanuts_build.py "Dump folder" --dry-run --push                                                                    print the commands, write nothing
# Keys: --keys, else NX_KEYS, else ~/.switch/prod.keys: the importer needs your own prod.keys (the import only; packs need none).
# Importer: --importer, else NX_IMPORTER, else built into peanuts/.build/nximport (needs cmake and a C compiler).
# --any-version packs an update the game's profile (dm/games/<titleID>/game.tsv) has no build row for. --force-nup rebuilds a .nup that is already there.
# Output: the .nup (beside the first input unless --nup) and the pack (<titleID>-work/android-pack beside the .nup, as pack_android.py writes it).
# --dry-run prints each command instead of importing, packing or pushing; probing and the importer build still run. Errors print [RED] and exit 1.
import argparse, os, re, shlex, shutil, subprocess, sys, time
from collections import namedtuple
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
IMPORT_SRC = REPO / 'dm' / 'peanuts' / 'import'
IMPORT_BUILD = REPO / 'peanuts' / '.build' / 'nximport'
PACK_SCRIPT = REPO / 'scripts' / 'pack_android.py'
PUSH_SCRIPT = REPO / 'peanuts' / 'scripts' / 'push-pack.py'
WINDOWS = sys.platform == 'win32'
GAME_EXTS = ('.nsp', '.xci')
DEVICE_ROOT = '/sdcard/Peanut Roms'
ENV = dict(os.environ, PYTHONUTF8='1', PYTHONIOENCODING='utf-8', PYTHONUNBUFFERED='1')   # the children print UTF-8, unbuffered, on every OS

PROBE_FILE = re.compile(r'^(?P<path>.+)  \((?P<n>\d+) titles?, [0-9.]+s\)$')
PROBE_TITLE = re.compile(r'^\s+title (?P<title>[0-9A-Fa-f]{16})\s+app (?P<app>[0-9A-Fa-f]{16})\s+kind (?P<kind>\S+)\s+version (?P<version>\d+)\s+name "(?P<name>.*)"\s+publisher ".*"\s*$')
PACK_DONE = re.compile(r'^done: (?:the modules in )?(?P<path>.*?) \((?:[0-9.]+ MiB|--code-only)')
NAME_BAD = re.compile(r'[<>:"/\\|?*]')
TITLE_IN_NAME = re.compile(r'\[([0-9A-Fa-f]{16})\]')

Title = namedtuple('Title', 'file title app kind version name')
Plan = namedtuple('Plan', 'app name base_file version update_file dlc_files')


def die(msg):
    sys.exit(f'[RED] {msg}')


def say(msg):
    print(msg, flush=True)


def shown(cmd):
    return subprocess.list2cmdline(cmd) if WINDOWS else shlex.join(cmd)


def run(cmd, dry, what, capture=False):
    """Runs cmd on this console (or captures its stdout). Prints the command first; a dry run stops there. Returns (exit code, captured text)."""
    print(('[dry run] ' if dry else '') + '+ ' + shown(cmd), flush=True)
    if dry: return 0, ''
    try:
        if capture:
            r = subprocess.run(cmd, stdout=subprocess.PIPE, env=ENV)
            return r.returncode, r.stdout.decode('utf-8', 'replace')
        return subprocess.call(cmd, env=ENV), ''
    except OSError as e:
        die(f'{what}: cannot run {cmd[0]}: {e}')


def parse_version(s):
    s = str(s).strip()
    if s[:1].lower() == 'v': s = s[1:]
    if not s.isdigit(): die(f'not a version: {s!r} (a number such as 131072 or v131072)')
    return int(s)


def is_dump(d):
    return (d / 'base' / 'exefs').is_dir() or (d / 'exefs').is_dir()


def visible(p):
    return not p.name.startswith('._')   # macOS's AppleDouble files on an exFAT / FAT drive


def classify(inputs):
    """-> (mode, the .nup or dump folder, the .nsp/.xci files). mode: 'nup', 'dump' or 'nsp'"""
    paths = [Path(x).expanduser() for x in inputs]
    for p in paths:
        if not p.exists(): die(f'{p} does not exist')
    if len(paths) == 1:
        p = paths[0]
        if p.is_file() and p.suffix.lower() == '.nup': return 'nup', p, []
        if p.is_dir() and is_dump(p): return 'dump', p, []
    files = []
    for p in paths:
        if p.is_dir():
            if is_dump(p): die(f'{p} is a dump folder: give it alone (a dump is packed as it is)')
            found = sorted(f for f in p.iterdir() if f.is_file() and visible(f) and f.suffix.lower() in GAME_EXTS)
            if not found: die(f'{p} has no .nsp or .xci files (top level only), and is no dump folder (base/exefs)')
            files += found
        elif p.suffix.lower() == '.nup':
            die(f'{p}: a .nup is given alone')
        elif visible(p) and p.suffix.lower() in GAME_EXTS:
            files.append(p)
        else:
            die(f'{p} is not a .nsp, .xci, .nup, a folder of them, or a dump folder')
    unique = []
    for f in files:
        if f not in unique: unique.append(f)
    return 'nsp', None, unique


def importer_sources_newer(exe):
    srcs = sorted(IMPORT_SRC.glob('*.c')) + sorted(IMPORT_SRC.glob('*.h')) + [IMPORT_SRC / 'CMakeLists.txt']
    t = exe.stat().st_mtime
    return any(s.is_file() and s.stat().st_mtime > t for s in srcs)


def build_importer():
    cmake = shutil.which('cmake')
    if not cmake:
        die('the importer needs cmake and a C compiler: cmake (brew install cmake on macOS, your package manager on Linux, cmake.org on Windows), '
            'a C compiler (clang or gcc; on Windows MinGW-w64 GCC from MSYS2 UCRT64 or WinLibs), on PATH; or pass --importer / NX_IMPORTER')
    conf = [cmake, '-S', str(IMPORT_SRC), '-B', str(IMPORT_BUILD), '-DCMAKE_BUILD_TYPE=Release']
    if WINDOWS and not os.environ.get('CMAKE_GENERATOR'): conf += ['-G', 'MinGW Makefiles']
    build = [cmake, '--build', str(IMPORT_BUILD), '--target', 'nximport_cli', '--config', 'Release']
    say(f'building the importer into {IMPORT_BUILD}')
    for cmd in (conf, build):
        rc, _ = run(cmd, False, 'the importer build')
        if rc: die(f'the importer build failed (exit code {rc}): {shown(cmd)}')


def find_importer(args):
    given = args.importer or os.environ.get('NX_IMPORTER')
    if given:
        p = Path(given).expanduser()
        if not p.is_file(): die(f'the importer {p} is not a file (--importer / NX_IMPORTER)')
        return p
    exe = IMPORT_BUILD / ('nximport_cli.exe' if WINDOWS else 'nximport_cli')
    if not exe.is_file() or importer_sources_newer(exe): build_importer()
    if not exe.is_file(): die(f'the importer build left no {exe}')
    return exe


def find_keys(args):
    raw = args.keys or os.environ.get('NX_KEYS') or os.path.join('~', '.switch', 'prod.keys')
    p = Path(raw).expanduser()
    if not p.is_file(): die(f'no keys file at {p}: pass --keys, or set NX_KEYS to your own prod.keys (the importer needs it)')
    return p


def probe(importer, keys, files):
    say(f'probing {len(files)} file(s)')
    rc, out = run([str(importer), '--keys', str(keys), 'probe', *map(str, files)], False, 'probe', capture=True)
    if rc: die(f'probe failed (exit code {rc})')
    titles, with_titles, cur = [], set(), None
    for line in out.splitlines():
        m = PROBE_FILE.match(line)
        if m:
            cur = m.group('path')
            continue
        m = PROBE_TITLE.match(line)
        if m and cur is not None:
            titles.append(Title(Path(cur), m.group('title').upper(), m.group('app').upper(), m.group('kind'), int(m.group('version')), m.group('name')))
            with_titles.add(cur)
    for f in files:
        if str(f) not in with_titles: die(f'the importer found no title in {f} (its message is above)')
    return titles


def choose(titles, args):
    bases = [t for t in titles if t.kind == 'base']
    if not bases: die('no base game among the inputs: an update or DLC needs its base .nsp / .xci with it')
    names = {}
    for t in bases: names.setdefault(t.app, t.name)
    apps = sorted(names)
    if len(apps) > 1:
        die('several games among the inputs (give one game\'s files): ' + '; '.join(f'{a} "{names[a]}"' for a in apps))
    app, name = apps[0], names[apps[0]]
    base_file = next(t.file for t in bases)
    ups = {}
    for t in titles:
        if t.kind == 'update' and t.app == app: ups.setdefault(t.version, t.file)
    if args.update is not None:
        v = parse_version(args.update)
        if v not in ups: die(f'no update v{v} among the inputs (have: {", ".join("v%d" % x for x in sorted(ups)) or "none"})')
        chosen = v
    else:
        chosen = max(ups) if ups else None
    update_file = ups.get(chosen) if chosen is not None else None
    dlc = []
    for t in titles:
        if t.kind not in ('base', 'update') and t.app == app and t.file not in (base_file, update_file) and t.file not in dlc:
            dlc.append(t.file)
    for t in titles:
        if t.kind == 'update' and t.app == app:
            say(f'  update v{t.version}: {t.file}' + ('  (chosen)' if t.version == chosen else ''))
    say(f'  base {app} "{name}": {base_file}')
    for f in dlc: say(f'  dlc: {f}')
    return Plan(app, name, base_file, chosen, update_file, dlc)


def check_profile(app, version, any_version):
    tsv = REPO / 'dm' / 'games' / app / 'game.tsv'
    if not tsv.is_file(): die(f'no profile for {app} in dm/games: the pack cannot be made')
    builds = []
    for line in tsv.read_text(encoding='utf-8', errors='replace').splitlines():
        f = line.split('\t')
        if len(f) >= 3 and f[0] == 'build' and re.fullmatch(r'v\d+', f[1]):
            builds.append((int(f[1][1:]), f[2]))
    want = version or 0
    if any(v == want for v, _ in builds): return
    have = ', '.join(f'v{v} ({disp})' for v, disp in builds) or 'none'
    msg = f'profile {app} has no build for update v{want} (the profile supports: {have})'
    if not any_version: die(msg + '; pass --any-version to pack it anyway')
    say(f'[RED] {msg}: going on with --any-version')


def nup_path(args, plan, first_input):
    if args.nup: return Path(args.nup).expanduser().resolve()
    # 'Zelda: Link's Awakening' → 'Zelda - Link's Awakening', as dumps name it; any other character Windows refuses in a name → '-'
    clean = NAME_BAD.sub('-', plan.name.replace(': ', ' - ')).strip().rstrip('.').strip() or plan.app
    return (first_input.resolve().parent / f'{clean} [{plan.app}][v{plan.version or 0}].nup')


def import_nup(importer, keys, nup, plan, dry, force):
    inputs = [plan.base_file] + ([plan.update_file] if plan.update_file else []) + plan.dlc_files
    if nup.is_file() and not force and nup.stat().st_mtime > max(f.stat().st_mtime for f in inputs):
        say(f'reusing {nup} (newer than its inputs; --force-nup rebuilds it)')
        return
    tmp = Path(str(nup) + '.tmp')
    cmd = [str(importer), '--keys', str(keys), 'nup', str(tmp), plan.app.lower(), str(plan.base_file)]
    if plan.update_file: cmd.append(str(plan.update_file))
    cmd += [str(f) for f in plan.dlc_files]
    say(f'importing into {nup}')
    ok = False
    try:
        rc, _ = run(cmd, dry, 'nup')
        if rc: die(f'nup failed (exit code {rc})')
        if not dry: os.replace(tmp, nup)
        ok = True
    finally:
        if not ok and not dry: tmp.unlink(missing_ok=True)
    rc, out = run([str(importer), 'verify', str(nup)], dry, 'verify', capture=True)
    if dry: return
    lines = out.strip().splitlines()
    last = lines[-1] if lines else ''
    if rc or 'ok,' not in last:
        nup.unlink(missing_ok=True)   # so the next run rebuilds it rather than reusing a broken file
        die(f'verify failed for {nup} (the .nup was removed): {last or "no output"}')
    say(last)


def fallback_pack(folder, app, extra):
    """The pack folder pack_android.py writes when it prints no done: line (the same rule as its main())"""
    for i, a in enumerate(extra):
        if a == '--out' and i + 1 < len(extra): return Path(extra[i + 1]).expanduser().resolve()
        if a.startswith('--out='): return Path(a[len('--out='):]).expanduser().resolve()
    if app is None: return None
    return (folder.resolve().parent / f'{app}-work' / 'android-pack').resolve()


def pack(target, extra, dry):
    cmd = [sys.executable, str(PACK_SCRIPT), str(target), *extra]
    print(('[dry run] ' if dry else '') + '+ ' + shown(cmd), flush=True)
    if dry: return None
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=ENV, text=True, encoding='utf-8', errors='replace')
    except OSError as e:
        die(f'pack: cannot run {sys.executable}: {e}')
    done = None
    for line in p.stdout:
        line = line.rstrip('\r\n')
        print(line, flush=True)
        m = PACK_DONE.match(line)
        if m: done = m.group('path')
    rc = p.wait()
    if rc: die(f'pack_android.py failed (exit code {rc})')
    return done


def main():
    argv = sys.argv[1:]
    extra = []
    if '--' in argv:
        i = argv.index('--')
        extra, argv = argv[i + 1:], argv[:i]
    ap = argparse.ArgumentParser(description='Peanuts Native in one command: .nsp/.xci -> .nup -> pack -> (--push) the phone. Words after -- go to pack_android.py.')
    ap.add_argument('inputs', nargs='+', help='a .nup; a dump folder (base/exefs); a folder of .nsp/.xci files; or .nsp/.xci files')
    ap.add_argument('--keys', help='prod.keys for the importer (default: NX_KEYS, else ~/.switch/prod.keys)')
    ap.add_argument('--update', help='the update version to use (default the highest); for a dump, passed to pack_android.py as --version')
    ap.add_argument('--nup', help='the .nup to write or reuse (default: beside the first input, "<name> [<titleID>][v<version>].nup")')
    ap.add_argument('--importer', help='nximport_cli to use (default: NX_IMPORTER, else built into peanuts/.build/nximport)')
    ap.add_argument('--force-nup', action='store_true', help='make the .nup again even if it is newer than its inputs')
    ap.add_argument('--any-version', action='store_true', help='pack even when the profile has no build row for the update')
    ap.add_argument('--push', action='store_true', help='copy the pack and the game to the phone with push-pack.py (adb)')
    ap.add_argument('--dest', help='the folder on the phone for the game (default: /sdcard/Peanut Roms/<game name>, or /sdcard/Peanut Roms for a dump)')
    ap.add_argument('--serial', help='the adb serial of the phone (default: the one device adb lists)')
    ap.add_argument('--dry-run', action='store_true', help='print the commands instead of importing, packing or pushing')
    args = ap.parse_args(argv)

    t0 = time.monotonic()
    timings = {}
    mode, single, files = classify(args.inputs)
    app = None
    nup = None
    dump = None
    if mode == 'nsp':
        t = time.monotonic()
        keys = find_keys(args)
        importer = find_importer(args)
        say('probing the inputs')
        titles = probe(importer, keys, files)
        plan = choose(titles, args)
        check_profile(plan.app, plan.version, args.any_version)
        app = plan.app
        nup = nup_path(args, plan, files[0])
        import_nup(importer, keys, nup, plan, args.dry_run, args.force_nup)
        timings['import'] = time.monotonic() - t
        target = nup
        pack_extra = list(extra)
    elif mode == 'nup':
        nup = single.resolve()
        target = nup
        m = TITLE_IN_NAME.search(nup.name)
        app = m.group(1).upper() if m else None
        pack_extra = list(extra)
        if args.update is not None: say('[RED] --update is ignored for a .nup: it holds the updates it was made with')
    else:
        dump = single.resolve()
        target = dump
        m = TITLE_IN_NAME.search(dump.name)
        app = m.group(1).upper() if m else None
        pack_extra = list(extra)
        if args.update is not None and '--version' not in pack_extra:
            pack_extra += ['--version', str(parse_version(args.update))]

    t = time.monotonic()
    done = pack(target, pack_extra, args.dry_run)
    timings['pack'] = time.monotonic() - t
    if args.dry_run:
        pack_dir = fallback_pack(target, app, pack_extra) or Path('<pack folder>')
    else:
        pack_dir = Path(done) if done else fallback_pack(target, app, pack_extra)
        if pack_dir is None or not pack_dir.is_dir(): die(f'the pack folder was not found (pack_android.py printed no done: line; expected {pack_dir})')

    device = None
    if args.push:
        # the game's own folder on the phone, named without the .nup name's [title ID][version] tags
        dest = args.dest or (DEVICE_ROOT if dump is not None else f'{DEVICE_ROOT}/{re.sub(r"\s*\[[^\]]*\]", "", target.stem).strip() or target.stem}')
        cmd = [sys.executable, str(PUSH_SCRIPT), str(pack_dir), '--game', str(target), '--dest', dest]
        if args.serial: cmd += ['--serial', args.serial]
        t = time.monotonic()
        rc, _ = run(cmd, args.dry_run, 'push')
        timings['push'] = time.monotonic() - t
        if rc: die(f'push-pack.py failed (exit code {rc})')
        device = dest

    total = time.monotonic() - t0
    print()
    print(f'game:   {nup if nup else dump}' + ('  (dry run: nothing was written)' if args.dry_run else ''))
    print(f'pack:   {pack_dir}')
    if device: print(f'device: {device}')
    print('time:   ' + ', '.join(f'{k} {v:.1f}s' for k, v in timings.items()) + f'; total {total:.1f}s')


if __name__ == '__main__':
    try: main()
    except KeyboardInterrupt: sys.exit('[RED] stopped')
