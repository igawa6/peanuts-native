#!/usr/bin/env python3
# Peanuts Native in one command: game files -> a .nup -> a pack for the Android app -> (with --push) the phone.
#   .nsp / .xci files      nximport_cli (dm/peanuts/import, built here with cmake when it is missing or older than its sources) makes the .nup
#   a .nup or a dump       the import is skipped; the pack is made from it as it is
#   the pack               scripts/pack_android.py (its own options go after --)
#   --push                 peanuts/scripts/push-pack.py copies the pack and the game to the phone over adb
# Stdlib only. This file's own syntax is Python 3.9's (peanuts/tests/test_peanuts_build.py checks it), but it starts scripts/pack_android.py with the interpreter it
# runs on, and the packer needs its environment (android/environment.yml: Python 3.14). The launchers peanuts-build.command (macOS), peanuts-build.sh (Linux) and
# peanuts-build.bat (Windows) run this with the packer's Python.
#   peanuts_build.py "Game [01006BB00C6F0000].nsp" "Game [01006BB00C6F0000] [Update v1.1.0].nsp" --keys ~/prod.keys   base + update: the .nup beside the first file, then the pack
#   peanuts_build.py "Game folder with .nsp files" --update 131072 --push                                              pick an update, then copy the pack and the .nup to the phone
#   peanuts_build.py "Game [01006BB00C6F0000][v131072].nup" --push --dest "/sdcard/Peanut Roms/Game" -- -j 8         a .nup; the words after -- go to pack_android.py
#   peanuts_build.py "Dump folder" --dry-run --push                                                                    print the commands, write nothing
# Keys: --keys, else NX_KEYS, else ~/.switch/prod.keys: the importer needs your own prod.keys (the import only; packs need none).
# Importer: --importer, else NX_IMPORTER, else built into peanuts/.build/nximport (needs cmake and a C compiler).
# Oodle and Unreal games (PC_DECODE below), on Linux: the Oodle decoding worker unoodle (dm/third_party/ooz, a GPL-3.0-or-later program of its own, built into
# peanuts/.build/ooz with a C++ compiler and patch) and, for the Unreal ones, the _nxaes module (peanuts/.build/pymod, a C compiler and Python's headers); the pack
# step then gets DM_OODLE_OOZ and DM_UE_DECODE. Every other game with such a step, and every other system, is refused as before (peanuts/docs/BUILDER.md).
# --any-version packs an update the game's profile (dm/games/<titleID>/game.tsv) has no build row for. --force-nup rebuilds a .nup that is already there.
# The icon: for .nsp/.xci inputs the title's icon is written beside the .nup as "<the .nup's name without .nup>.png" (the app and push-pack.py use it; a failure only warns).
# Output: the .nup (beside the first input unless --nup) and the pack (<titleID>-work/android-pack beside the .nup, as pack_android.py writes it).
# --dry-run prints each command instead of importing, packing or pushing; probing and the importer build still run. Errors print [RED] and exit 1.
import argparse, os, platform, re, shlex, shutil, subprocess, sys, sysconfig, time
from collections import namedtuple
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
IMPORT_SRC = REPO / 'dm' / 'peanuts' / 'import'
IMPORT_BUILD = REPO / 'peanuts' / '.build' / 'nximport'
PACK_SCRIPT = REPO / 'scripts' / 'pack_android.py'
PUSH_SCRIPT = REPO / 'peanuts' / 'scripts' / 'push-pack.py'
OOZ_SRC = REPO / 'dm' / 'third_party' / 'ooz'
OOZ_BUILD = REPO / 'peanuts' / '.build' / 'ooz'
NXAES_SRC = REPO / 'dm' / 'peanuts' / 'pack_worker' / 'nxaes.c'
PYMOD_BUILD = REPO / 'peanuts' / '.build' / 'pymod'
WINDOWS = sys.platform == 'win32'
LINUX = sys.platform.startswith('linux')
GAME_EXTS = ('.nsp', '.xci')
DEVICE_ROOT = '/sdcard/Peanut Roms'   # the default folder on the phone; push-pack.py's --dest default is the same one (the test compares them)
ENV = dict(os.environ, PYTHONUTF8='1', PYTHONIOENCODING='utf-8', PYTHONUNBUFFERED='1')   # the children print UTF-8, unbuffered, on every OS


def env_dotnet(prefix=None):
    """The folder of the Python environment's own .NET (conda keeps it in <prefix>/lib/dotnet, on Windows in <prefix>/dotnet or <prefix>/Library/dotnet), or None.
    Its activate script sets DOTNET_ROOT and PATH; run with the environment's python alone, the shader translator would not find .NET"""
    prefix = prefix or sys.prefix
    exe = 'dotnet.exe' if WINDOWS else 'dotnet'
    for sub in (('lib', 'dotnet'), ('dotnet',), ('Library', 'dotnet')):
        d = os.path.join(prefix, *sub)
        if os.path.isfile(os.path.join(d, exe)): return d
    return None


def with_dotnet(env, prefix=None):
    """env with the environment's .NET found by the tools started from here: unless DOTNET_ROOT is set (it wins), DOTNET_ROOT and PATH point at it
    and DOTNET_MULTILEVEL_LOOKUP=0 (as conda's activate script does)"""
    d = None if env.get('DOTNET_ROOT') else env_dotnet(prefix)
    if d is None: return env
    return dict(env, DOTNET_ROOT=d, DOTNET_MULTILEVEL_LOOKUP='0', PATH=d + os.pathsep + env.get('PATH', ''))


ENV = with_dotnet(ENV)

PROBE_FILE = re.compile(r'^(?P<path>.+)  \((?P<n>\d+) titles?, [0-9.]+s\)$')
PROBE_TITLE = re.compile(r'^\s+title (?P<title>[0-9A-Fa-f]{16})\s+app (?P<app>[0-9A-Fa-f]{16})\s+kind (?P<kind>\S+)\s+version (?P<version>\d+)\s+name "(?P<name>.*)"\s+publisher ".*"\s*$')
PACK_DONE = re.compile(r'^done: (?:the modules in )?(?P<path>.*?) \((?:[0-9.]+ MiB|--code-only)')
NAME_BAD = re.compile(r'[<>:"/\\|?*]')
TITLE_IN_NAME = re.compile(r'\[([0-9A-Fa-f]{16})\]')
NAME_TAGS = re.compile(r'\s*\[[^\]]*\]')

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


STEP_MARKERS = (
    ('/glslc_capture/', 'GLSLC capture', 'DM_GLSLC_CAPTURE', 'glslc_capture', '*.glslc', 'the game compiles its shaders with GLSLC at run time, and the pack needs the outputs'),
    ('/ue_shaders/', 'Unreal shader archive decoding', 'DM_UE_SHADERS', 'ue_shaders', '*.ushaderbytecode', 'the game keeps its shaders in Unreal archives, which the phone decodes with the game\'s own code'),
)


# The games whose Oodle or Unreal shader step this computer does itself (Linux only), each one checked on a PC against its profile's shader keys
# (dm/third_party/ooz/README.md "Checked"). True: it has Oodle streams, which the unoodle worker decodes; False: an Unreal game whose archives need no Oodle decoder.
PC_DECODE = {
    '01001F5010DFA000': True,    # Pokémon Legends: Arceus (GFPAK entries, Oodle Kraken)
    '01009720213B0000': True,    # Captain Tsubasa 2: World Fighters (Unreal IoStore, Oodle)
    '0100AD80208A8000': True,    # PEANUTS: Demon Slayer 2 (inline Unreal IoStore shaders, Oodle)
    '0100606026F12000': True,    # FINAL FANTASY RESONANCE (Unreal IoStore, Oodle Mermaid)
    '01005270232F2000': True,    # OCTOPATH TRAVELER 0 (Unreal pak, Oodle Mermaid): its streams and keys were checked, a build from its game files was not run
    '01003E601E324000': False,   # DRAGON QUEST III HD-2D Remake (Unreal pak, LZ4)
}


def profile_has(app, marker):
    tsv = REPO / 'dm' / 'games' / app / 'shader_map.tsv'
    return tsv.is_file() and marker in tsv.read_text(encoding='utf-8', errors='replace')


def folder_given(var, pat):
    given = os.environ.get(var)
    return bool(given and Path(given).expanduser().is_dir() and any(Path(given).expanduser().glob(pat)))


def stale(out, srcs):
    return not out.is_file() or any(s.is_file() and s.stat().st_mtime > out.stat().st_mtime for s in srcs)


def build_unoodle():
    """-> (peanuts/.build/ooz/unoodle, '') built by dm/third_party/ooz/build.sh when it is missing or older than its sources, or (None, why this computer has none)"""
    if not LINUX or platform.machine().lower() not in ('x86_64', 'amd64'):
        return None, ('the Oodle worker (dm/third_party/ooz) is built on x86-64 Linux only: its decoder uses x86 SSE2 intrinsics, '
                      f'and this is {platform.machine() or "an unknown processor"} on {sys.platform}')
    exe = OOZ_BUILD / 'unoodle'
    if not stale(exe, [OOZ_SRC / f for f in ('kraken.cpp', 'stdafx.h', 'unoodle.cpp', 'ooz-port.patch', 'ooz-guards.patch', 'build.sh')]): return exe, ''
    cxx = os.environ.get('CXX') or next((c for c in ('g++', 'clang++', 'c++') if shutil.which(c)), None)
    if not cxx: return None, 'the Oodle worker (dm/third_party/ooz) needs a C++ compiler (g++ or clang++) on PATH'
    if not shutil.which('patch'): return None, 'the Oodle worker (dm/third_party/ooz) needs the patch program on PATH'
    say(f'building the Oodle worker into {OOZ_BUILD}')
    rc, _ = run(['sh', str(OOZ_SRC / 'build.sh'), str(exe), cxx], False, 'the Oodle worker build')
    if rc or not exe.is_file(): return None, f'the Oodle worker build failed (exit code {rc}; its messages are above)'
    return exe, ''


def build_nxaes():
    """-> (peanuts/.build/pymod, '') holding _nxaes for this Python (uepak.py reads an Unreal pak's encrypted index with it off the Mac), built from
    dm/peanuts/pack_worker/nxaes.c when it is missing or older than its source, or (None, why this computer has none)"""
    if not LINUX: return None, 'the _nxaes module (dm/peanuts/pack_worker/nxaes.c) is built on Linux only'
    inc, suffix = sysconfig.get_paths().get('include') or '', sysconfig.get_config_var('EXT_SUFFIX') or '.so'
    out = PYMOD_BUILD / ('_nxaes' + suffix)
    if not stale(out, [NXAES_SRC]): return PYMOD_BUILD, ''
    cc = os.environ.get('CC') or next((c for c in ('gcc', 'clang', 'cc') if shutil.which(c)), None)
    if not cc: return None, 'the _nxaes module needs a C compiler (gcc or clang) on PATH'
    if not os.path.isfile(os.path.join(inc, 'Python.h')):
        return None, f'the _nxaes module needs the headers of {sys.executable} (no Python.h in {inc or "its include folder"}: python3-dev or python3-devel)'
    PYMOD_BUILD.mkdir(parents=True, exist_ok=True)
    say(f'building _nxaes into {PYMOD_BUILD}')
    tmp = PYMOD_BUILD / ('_nxaes.tmp' + suffix)
    rc, _ = run([cc, '-O2', '-Wall', '-fPIC', '-shared', f'-ffile-prefix-map={NXAES_SRC.parent}=dm/peanuts/pack_worker', '-I', inc, '-o', str(tmp), str(NXAES_SRC)], False, 'the _nxaes build')
    if rc or not tmp.is_file():
        tmp.unlink(missing_ok=True)
        return None, f'the _nxaes build failed (exit code {rc}; its messages are above)'
    os.replace(tmp, out)
    return PYMOD_BUILD, ''


def pc_decode(app, name=None):
    """What this computer does itself for a game whose shaders are Oodle-compressed or in Unreal archives -> the settings its pack step gets ({} = nothing: every
    other game, and whatever check_device_steps then refuses).
      DM_OODLE_OOZ   the unoodle worker, for a PC_DECODE game with Oodle streams on Linux when the worker builds; one already set (your own worker) is used
                     for any game. pack_android.py takes that decoder's output only when the split's shader keys are exactly the profile's.
      DM_UE_DECODE   for a game with /ue_shaders/ rows: the split decodes the Unreal archives itself, as the phone's does ('' here; main() names the folder);
                     with PYTHONPATH naming the folder of _nxaes.
    A PC_DECODE game without /ue_shaders/ rows (Arceus) whose worker cannot be had stops here, before the import, unless the game's own decoder is set (DM_OODLE_HOST)."""
    label = f'{name or app} ({app})'
    given = os.environ.get('DM_OODLE_OOZ')
    if given and not Path(given).expanduser().is_file(): die(f'DM_OODLE_OOZ names {given}, which is not a file')
    if not given and not (LINUX and app in PC_DECODE): return {}
    ue = profile_has(app, '/ue_shaders/')
    env = {}
    if given:
        if str(Path(given).expanduser()) != given: env['DM_OODLE_OOZ'] = given = str(Path(given).expanduser())   # the tools start it as it is written: no ~ for them
        say(f'{label}: Oodle streams go to the worker DM_OODLE_OOZ names ({given}); the pack is made only if every shader key is the profile\'s')
    elif PC_DECODE[app]:
        if not ue and os.environ.get('DM_OODLE_HOST') and os.environ.get('DM_OODLE_MODULES'): return {}   # the game's own decoder is set: nothing to add
        exe, why = build_unoodle()
        if not exe and ue:
            say(f'[RED] {why}')
            return {}
        if not exe:
            die(f'{label} keeps its shaders in Oodle-compressed files, and this computer has no decoder for them: {why}. '
                'Use Build in the Peanuts app on the device: it decodes them with the game\'s own code.')
        env['DM_OODLE_OOZ'] = str(exe)
        say(f'{label}: its Oodle streams are decoded here by {exe} (the open-source ooz decoder, not the game\'s own; dm/third_party/ooz); '
            'the pack is made only if every shader key is the profile\'s')
    if ue and not folder_given('DM_UE_SHADERS', '*.ushaderbytecode'):
        pymod, why = build_nxaes()
        if not pymod:
            say(f'[RED] {why}')
            return {}
        env['PYTHONPATH'] = str(pymod) + (os.pathsep + os.environ['PYTHONPATH'] if os.environ.get('PYTHONPATH') else '')
        env['DM_UE_DECODE'] = ''
    return env


def check_device_steps(app, name=None, have=()):
    """Refuse, before any import, a game whose shaders need a step that runs the game or the phone's decoder (GLSLC capture, Unreal Oodle archives), which
    a computer cannot do here: the profile's shader_map.tsv has /glslc_capture/ or /ue_shaders/ rows. Not refused when the folder of those files is
    given (DM_GLSLC_CAPTURE / DM_UE_SHADERS), nor for the Unreal step when this computer does it itself (have: pc_decode's settings with DM_UE_DECODE)"""
    for marker, step, var, folder, pat, why in STEP_MARKERS:
        if not profile_has(app, marker): continue
        if folder_given(var, pat): continue
        if var == 'DM_UE_SHADERS' and 'DM_UE_DECODE' in have: continue
        die(f'{name or app} ({app}) needs a step this computer cannot run: {step} ({why}). Use Build in the Peanuts app on the device: '
            f'it runs that step there. (To make the pack here anyway, set {var} to a folder that holds the {pat} files.)')


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
    given = Path(args.nup).expanduser().resolve() if args.nup else None
    if given and not given.is_dir(): return given
    # 'Zelda: Link's Awakening' → 'Zelda - Link's Awakening', as dumps name it; any other character Windows refuses in a name → '-'
    clean = NAME_BAD.sub('-', plan.name.replace(': ', ' - ')).strip().rstrip('.').strip() or plan.app
    return ((given or first_input.resolve().parent) / f'{clean} [{plan.app}][v{plan.version or 0}].nup')


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


def icon_path(nup):
    """The title's icon beside the .nup: the .nup's path with .png in place of .nup (the app and push-pack.py look there)"""
    return nup.with_suffix('.png')


def write_icon(importer, keys, nup, plan, dry, force):
    """nximport_cli icon: the base file, else the update file -> <nup minus .nup>.png. A failure is a warning: the game runs without an icon."""
    png = icon_path(nup)
    inputs = [plan.base_file] + ([plan.update_file] if plan.update_file else [])
    if png.is_file() and png.stat().st_size > 0 and not force and png.stat().st_mtime > max(f.stat().st_mtime for f in inputs):
        say(f'reusing {png} (newer than its inputs)')
        return
    tries = [(plan.base_file, plan.app)] + ([(plan.update_file, '-')] if plan.update_file else [])
    tmp = Path(str(png) + '.tmp')
    for src, tid in tries:
        try:
            rc, _ = run([str(importer), '--keys', str(keys), 'icon', str(src), tid, str(tmp)], dry, 'icon')
        except (OSError, SystemExit): rc = 1
        if dry: return
        if rc == 0 and tmp.is_file() and tmp.stat().st_size > 0:
            os.replace(tmp, png)
            say(f'icon: {png}')
            return
    tmp.unlink(missing_ok=True)
    say(f'[RED] no icon for {plan.app} (the game goes on without one)')


def device_folder(stem):
    """The game's own folder on the phone for a .nup: DEVICE_ROOT/<the .nup's name without its [title ID][version] tags>. A name that is empty, '.' or '..'
    without the tags keeps them (push-pack.py refuses a '.' or '..' part in a device path)"""
    name = NAME_TAGS.sub('', stem).strip()
    if name in ('', '.', '..'): name = stem
    return f'{DEVICE_ROOT}/{name}'


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
    ap.add_argument('--nup', help='the .nup to write or reuse; an existing folder: the .nup goes there, named by default (default: beside the first input, "<name> [<titleID>][v<version>].nup")')
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
        pc = pc_decode(plan.app, plan.name)
        check_device_steps(plan.app, plan.name, pc)
        check_profile(plan.app, plan.version, args.any_version)
        app = plan.app
        nup = nup_path(args, plan, files[0])
        import_nup(importer, keys, nup, plan, args.dry_run, args.force_nup)
        write_icon(importer, keys, nup, plan, args.dry_run, args.force_nup)
        timings['import'] = time.monotonic() - t
        target = nup
        pack_extra = list(extra)
    elif mode == 'nup':
        nup = single.resolve()
        target = nup
        m = TITLE_IN_NAME.search(nup.name)
        app = m.group(1).upper() if m else None
        pc = pc_decode(app) if app else {}
        if app: check_device_steps(app, None, pc)
        pack_extra = list(extra)
        if args.update is not None: say('[RED] --update is ignored for a .nup: it holds the updates it was made with')
    else:
        dump = single.resolve()
        target = dump
        m = TITLE_IN_NAME.search(dump.name)
        app = m.group(1).upper() if m else None
        pc = pc_decode(app) if app else {}
        if app: check_device_steps(app, None, pc)
        pack_extra = list(extra)
        if args.update is not None and '--version' not in pack_extra:
            pack_extra += ['--version', str(parse_version(args.update))]

    if 'DM_UE_DECODE' in pc:   # the folder the split decodes the Unreal archives into, kept beside the shader package and reused while the paks are the same
        ue = target.resolve().parent / f'{app}-work' / 'android-shaders' / 'ue_shaders'
        pc['DM_UE_DECODE'] = str(ue)
        if not args.dry_run: ue.parent.mkdir(parents=True, exist_ok=True)
    for k, v in pc.items():
        ENV[k] = v
        say(f'  {k}={v}')
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
        dest = args.dest or (DEVICE_ROOT if dump is not None else device_folder(target.stem))
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
