#!/usr/bin/env python3
# A pack for the Android launcher (android/app) from a dumped game folder (the layout scripts/pack_dump.py reads: base/, updates/<version>/, dlc/).
# The pack holds what the host needs beside the game files and nothing the phone can read from the game folder itself:
#   <module>.patched.bin, <module>.fixups.tsv, <module>.gates.tsv and the rewriter's other tables, for every module of the profile, rewritten with patch_nso.py --target android
#   game.tsv     the profile the host was built with (for reference: the host has it compiled in)
#   pack.tsv     what the launcher reads: title ID, version, build IDs, and the RomFS and DLC folders relative to the game folder; the shader library's path in the pack
#   shaders/v-<DMSH_VK_CHAIN>/dmsh_vk.lib, inputs.tsv   the game's shaders as SPIR-V for the Vulkan replay (dm/gfx/tools/vk_android_build.py; android/SHADERS.md), the library
#                of this source tree's version: an up-to-date one in the shader cache is copied, else it is built from the Switch shader package
#   nro.tsv, nro<i>.patched.bin and the rest   the NROs the game loads at run time with nn::ro (dm/tools/nro_collect.py: by the SHA-256s its NRRs list, from the RomFS or packed in
#                a boot stub's main), collected into <work>/android-nro/ and rewritten like the modules; the host maps what nro.tsv lists (loader.c preload_nros). Not in pack.tsv's
#                modules row: the launcher checks those against the dump's own files, and an NRO is not one
#   build_src.sha256, rewrite_<module>.log
#   scene_res/<preset>/<RomFS path>, scene_res.tsv   for a profile with scene_scale_palette rows: the game's dynamic resolution palette rewritten, one RomFS overlay folder per
#                scene_scale row and per res_preset row with a floor or fixed scene (dm/tools/scene_res.py), which the launcher's Resolution passes as DM_ROMFS_OVERLAY; made from the dump's own RomFS
#   cheats/<build>.txt   the profile's Atmosphère-format cheat files (game.tsv cheats rows; place_cheats), for the launcher's Game settings › Cheats; not in the rewrite key
# The RomFS is not in the pack (the app gives the host <game folder>/<romfs> as DM_ROMFS) and neither is anything else of the dump but the rewritten code.
# The game may be a NUP file instead of a folder (android/NUP.md): its packages are read in place (pack_dump.scan, dm/tools/nup.py), the modules and the RomFS are
# named `<name>.nup:update/<version>/...` (or `:base/...`), the DLC `<name>.nup:addon/<title ID>/romfs`, and pack.tsv's paths are relative to the folder holding the file.
# Writes <game folder>/../<title ID>-work/android-pack/ unless --out names another folder. A repeat run keeps modules whose inputs are unchanged.
# Needs Python with Capstone 5 and lz4 (pyproject.toml, dependency group android-pack). Packs are for the user's own phone: never commit or share one.
# The shader library: <work>/android-shaders/lib/v-<V>/ is reused when its inputs.tsv names this game and its profile's current inputs (a full build writes it last);
# otherwise it is built there from <work>/android-shaders/pkg (shader_split.py's output, as dmsh_build.py takes) by vk_android_build.py, which needs Python 3.14 (or
# backports.zstd), the .NET 10 runtime and spirv-val: --shader-python names that Python (default: the first of python3.14 / python3 on PATH that has zstd). About 10 minutes
# on DKB with -J 12. Without a package (the default folder or --shader-pkg) the dump is split into it first (split_shaders), and the split must hold every shader of the
# profile's shader_map.tsv; vk_android_build.py reads each shader from the package by its key, so a package of another split (base, update and DLC RomFS together) builds the
# same library and one that lacks a shader stops the build. --no-shaders leaves them out.
# --in-process (the phone's packer, android/ONDEVICE.md: an app may not start programs, and its process must not fork): the rewriter runs in this Python, one module after the other
# (runpy, with DM_REWRITE_J=1), and the shader library is not built here (it must be current in the cache, or --no-shaders).
# Each module is rewritten into <out>/.rewrite-<module>/ and moved into the pack when complete, its <module>.android.key last (a module without its key is
# unfinished: the launcher refuses it, the next run rewrites it). Libraries of other versions in the pack's shaders/ are kept.
# --code-only rewrites the modules and stops (pack.tsv, game.tsv and the shaders untouched); --keep-code packs without rewriting (the phone's two steps).
# Usage: pack_android.py <game folder | .nup file> [--version N] [--out DIR] [-j N] [--shader-cache DIR] [--shader-pkg DIR] [--shader-python PY] [-J N] [--no-shaders] [--code-only | --keep-code] [--in-process]   (exit 1 on error)
import argparse, hashlib, os, runpy, shutil, signal, struct, subprocess, sys, tempfile, time   # PEANUTS: struct (cross_module), tempfile (shader selection)
from pathlib import Path
REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / 'scripts')); sys.path.insert(0, str(REPO / 'dm/games'))
import pack_dump
import game
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
import dmsh_chain

REWRITER = ('patch_nso.py', 'nso.py', 'nso_code.py', 'nso_asm.py', 'nso_match.py', 'sdk_patches.py', 'ue_cvar.py', 'ue_malloc.py', 'fps_target.py', 'ue_region.py', 'loader_census.py')
FORMAT = 1   # pack.tsv format; the launcher refuses another


class Error(Exception): pass


WINDOWS = os.name == 'nt'


def write_lf(path, text):   # a text file with '\n' line ends in UTF-8 on every system (Windows' text mode writes CR LF in its ANSI code page): the launcher reads these bytes
    with open(path, 'w', encoding='utf-8', newline='\n') as f: f.write(text)


_stage_at = [0.0]


def stage(text, every=0.0):
    """'stage: <text>': what this step is doing now, which Prepare's card shows after the step's name (PackService, Job.stepNote). every: at most once in that many
    seconds (a count inside a long phase); 0: always (a new phase)"""
    now = time.monotonic()
    if every and now - _stage_at[0] < every: return
    _stage_at[0] = now
    print(f'stage: {text}', flush=True)


def copy_counted(src, dst, what):
    """shutil.copy2 in 8 MiB pieces with 'stage: <what>: <n> of <m> MB' at most twice a second: the shader library (700 MB for Ocarina of Time) to shared storage"""
    total = os.path.getsize(src); done = 0
    stage(f'{what}: 0 of {total >> 20:,} MB')
    with open(src, 'rb') as i, open(dst, 'wb') as o:
        while b := i.read(8 << 20):
            o.write(b); done += len(b)
            stage(f'{what}: {done >> 20:,} of {total >> 20:,} MB', 0.5)
    shutil.copystat(src, dst)


def child_env(**kw):
    """The environment of a tool this script starts: this one's and kw; on Windows also PYTHONUTF8=1, so a Python tool's text, its files and its output to the log or
    to this script's output, is UTF-8 there too (the ANSI code page has no '→' and stops the tool), and the environment's Library\\bin first on PATH (a conda
    environment keeps its programs there, spirv-val among them, and they are on PATH only when it is activated)"""
    if not WINDOWS: return dict(os.environ, **kw)
    env = dict(os.environ, PYTHONUTF8='1')
    lib = os.path.join(sys.prefix, 'Library', 'bin')
    if os.path.isdir(lib): env['PATH'] = lib + os.pathsep + env.get('PATH', '')
    return dict(env, **kw)


# PEANUTS begin: the phone's packer settings (android/app PackService) that change what a pack holds, so a pack made here is the one the Thor makes; a value
# already in the environment wins (as the phone's ondevice/env.txt does). The phone's worker settings (DM_SPLIT_WORKER, DMSH_VK_WORKER) are left out: they stand in
# for the process pools a computer's Python already has
def peanuts_env(step):
    """step 'split': DM_SPLIT_DIRECT=1 (the targeted split reads a file's recorded containers only); 'shaders': DMSH_VK_ZSTD_LEVEL=6 (the phone's level)"""
    kw = dict(DM_SPLIT_DIRECT='1') if step == 'split' else dict(DMSH_VK_ZSTD_LEVEL='6')
    # PEANUTS: a desktop checkout can build the locked translator. The phone has its own native one.
    helper = REPO / 'peanuts/scripts'
    if step == 'shaders' and 'XLATE' not in os.environ and (helper / 'prepare_pc_translator.py').is_file():
        sys.path.insert(0, str(helper))
        import prepare_pc_translator
        try:
            kw['XLATE'] = str(prepare_pc_translator.resolve())
        except (OSError, RuntimeError, subprocess.SubprocessError) as error:
            raise Error(f'desktop translator: {error}') from error
    return {k: v for k, v in kw.items() if k not in os.environ}
# PEANUTS end


def windows_job():
    """Windows: this process and every process it starts from here on (the rewriter, the split, the shader build with its translator and spirv-val) in one job that
    the system ends as a whole when this process ends, however it ends. A GUI's stop is then Popen.terminate() on this process alone (TerminateProcess, which runs
    no finally: without the job its tools would go on). A pack stopped at any point is safe: each module is swapped in whole, its key last, and every other file goes
    in by rename. Best effort: where the job cannot be made, the tools are stopped as before (the finally of rewrite, subprocess.run on KeyboardInterrupt)"""
    import ctypes
    from ctypes import wintypes
    class Basic(ctypes.Structure):   # JOBOBJECT_BASIC_LIMIT_INFORMATION
        _fields_ = [('PerProcessUserTimeLimit', ctypes.c_int64), ('PerJobUserTimeLimit', ctypes.c_int64), ('LimitFlags', wintypes.DWORD), ('MinimumWorkingSetSize', ctypes.c_size_t),
                    ('MaximumWorkingSetSize', ctypes.c_size_t), ('ActiveProcessLimit', wintypes.DWORD), ('Affinity', ctypes.c_size_t), ('PriorityClass', wintypes.DWORD), ('SchedulingClass', wintypes.DWORD)]
    class Extended(ctypes.Structure):   # JOBOBJECT_EXTENDED_LIMIT_INFORMATION (its IO_COUNTERS as six 64-bit counts)
        _fields_ = [('Basic', Basic), ('Io', ctypes.c_uint64 * 6), ('ProcessMemoryLimit', ctypes.c_size_t), ('JobMemoryLimit', ctypes.c_size_t),
                    ('PeakProcessMemoryUsed', ctypes.c_size_t), ('PeakJobMemoryUsed', ctypes.c_size_t)]
    try:
        k = ctypes.WinDLL('kernel32', use_last_error=True)   # pyright: ignore[reportAttributeAccessIssue]  # PEANUTS: Windows-only API absent from Darwin stubs.
        k.CreateJobObjectW.restype, k.CreateJobObjectW.argtypes = wintypes.HANDLE, [ctypes.c_void_p, wintypes.LPCWSTR]
        k.SetInformationJobObject.restype, k.SetInformationJobObject.argtypes = wintypes.BOOL, [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        k.AssignProcessToJobObject.restype, k.AssignProcessToJobObject.argtypes = wintypes.BOOL, [wintypes.HANDLE, wintypes.HANDLE]
        k.GetCurrentProcess.restype = wintypes.HANDLE
        job = k.CreateJobObjectW(None, None)   # not inheritable: this process holds the only handle, which closes when it ends
        info = Extended(); info.Basic.LimitFlags = 0x2000   # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not job or not k.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info)) or not k.AssignProcessToJobObject(job, k.GetCurrentProcess()):   # 9: JobObjectExtendedLimitInformation
            print(f'the tools this packer starts are not in a job (Windows error {ctypes.get_last_error()}): a stop by TerminateProcess leaves them running', flush=True)   # pyright: ignore[reportAttributeAccessIssue]  # PEANUTS: Windows-only API absent from Darwin stubs.
            return None
        return job   # kept open for this process's life
    except (OSError, AttributeError) as e:
        print(f'the tools this packer starts are not in a job ({e})', flush=True)
        return None


# The rewrite key, <module>.android.key: SHA-256 over name\0bytes of the REWRITER files, the module, the profile's patches.py (when there is one) and the part of the
# profile's game.tsv the rewriter reads: its build rows (patch_nso.py reads them for a module whose build has no CUTS) and its fps_decouple rows (whether main's sead
# frame-rate controller is gated: a profile without one keeps the key it had), normalized by build_rows and hashed under the name ROWS. The rest of game.tsv is the host's (GPU timing, window size, ...): a change there must not make every pack's code stale.
# Packs made before (the v1 key) hash the whole game.tsv; one counts as current with this APK's game.tsv, or with the copy the pack keeps (<pack>/game.tsv,
# written after the rewrite) when the copy's build rows are the profile's: the key proves the copy is the file it was made with, the rows that the rewrite read
# the same. upgrade_keys rewrites such a key as the current one, before the pack's copy of game.tsv is replaced.
ROWS = 'game.tsv build rows'


def build_rows(b):
    """game.tsv's build and fps_decouple rows as patch_nso.py reads them (rows split at tabs, each cell stripped, first cell `build` or `fps_decouple`), one a line;
    bytes in, bytes out.
    A narrower strip and split than the rewriter's own: two tables that differ for it never come out the same here"""
    rows = []
    for row in b.split(b'\n'):
        c = [x.strip(b' \r\x0b\x0c') for x in row.split(b'\t')]
        if c[0] in (b'build', b'fps_decouple'): rows.append(b'\t'.join(c) + b'\n')
    return b''.join(rows)


def part(h, name, b): h.update(name.encode() + b'\0' + b)


def rewrite_key(src, g):
    """The key a module rewritten now carries (the current formula, the current rewriter files)"""
    return rewrite_keys(src, g)[0]


# Earlier bytes of a REWRITER file whose rewrite output is the same as the current file's: dm/tools/rewriter_equiv/<file>.<commit> (that commit's version of the
# file). A file goes there ONLY when the rewrite output with it was verified byte-identical to the current one (each game's modules); a pack whose key was made
# with it then counts as up to date (rewrite_keys, the launcher's PackCheck.rewriteKeys), so a change that only saves memory or time does not redo every pack.
EQUIV = REPO / 'dm/tools/rewriter_equiv'


def equiv_files():
    """{REWRITER file: [earlier bytes, ...]} from EQUIV"""
    out = {}
    for p in sorted(EQUIV.glob('*.*')) if EQUIV.is_dir() else []:
        name = p.name.rsplit('.', 1)[0]
        if name in REWRITER: out.setdefault(name, []).append(p.read_bytes())
    return out


def rewrite_keys(src, g, pack=None):
    """Every key that counts as the current rewriter's, rewrite_key's first: for the current REWRITER files and each with earlier equivalent bytes (EQUIV) in
    their place, the current key (ROWS), then the v1 key with the profile's game.tsv, then the v1 key with pack/game.tsv when pack names a pack folder that has
    one whose build rows are the profile's (the same rule as the launcher's RewriteKey.keys)"""
    eq = equiv_files()
    variants = [[]]
    for f in REWRITER:
        cur = (REPO / 'dm/tools' / f).read_bytes()
        variants = [v + [(f, b)] for v in variants for b in [cur] + eq.get(f, [])]
    hs = []
    for v in variants:
        h = hashlib.sha256()
        for name, b in v: part(h, name, b)
        hs.append(h)
    for p in [src] + [p for p in (g.dir / 'patches.py',) if p.is_file()]:
        b = p.read_bytes()
        for h in hs: part(h, p.name, b)
    gt = (g.dir / 'game.tsv').read_bytes()
    tails = [(ROWS, build_rows(gt)), ('game.tsv', gt)]
    pg = pack / 'game.tsv' if pack is not None else None
    if pg is not None and pg.is_file() and (pb := pg.read_bytes()) != gt and build_rows(pb) == build_rows(gt): tails.append(('game.tsv', pb))
    out = []
    for name, b in tails:
        for h in hs:
            t = h.copy(); part(t, name, b); out.append(t.hexdigest() + ' target=android')
    return out


def upgrade_keys(srcs, out, g, xmod=None):   # PEANUTS: xmod, as rewrite's
    """Each module's key that counts as current (rewrite_keys with the pack's game.tsv) but is not rewrite_key's is written as rewrite_key's (by rename), so the
    pack stays current when its copy of game.tsv is replaced. Other keys are left as they are"""
    for m, src in srcs.items():
        kf = out / f'{m}.android.key'
        if not kf.is_file(): continue
        have, keys = kf.read_text().strip(), rewrite_keys(src, g, out)
        if xmod and m in xmod: keys = xmod_keys(keys, xmod[m])   # PEANUTS
        if have in keys and have != keys[0]:
            tmp = out / f'{m}.android.key.tmp'; write_lf(tmp, keys[0] + '\n'); os.replace(tmp, kf)
            print(f'  {m}: key brought to the current formula (same rewrite)')


def nso_text_size(path):   # the module's code segment, decompressed (NSO header +0x18; an NRO's at +0x24): what the rewriter's time follows
    with open(path, 'rb') as f: b = f.read(0x28)
    if b[:4] == b'NSO0': return int.from_bytes(b[0x18:0x1C], 'little')
    return int.from_bytes(b[0x24:0x28], 'little') if b[0x10:0x14] == b'NRO0' else 0


# ---- NROs: the modules a game loads at run time (nn::ro) ----
NRO_TOOLS = ('dm/tools/nro_collect.py', 'dm/tools/boot_nro.py', 'dm/tools/nso.py', 'dm/tools/nso_syms.py', 'dm/tools/romfs.py', 'dm/gfx/tools/smash_arc.py', 'dm/gfx/tools/cra.py')


def rfs():   # dm/tools/romfs.py: the os, open of plain folders and of paths inside a .nup package or a RomFS image
    sys.path.insert(0, str(REPO / 'dm/tools'))
    import romfs
    return romfs


def nrr_files(romfs):   # [(path in the RomFS, bytes)] of its NRR files (*.nrr: Super Smash Bros. Ultimate keeps them in a folder named .nrr), as nro_collect.py reads them
    R = rfs(); out = []
    for dp, _, fs in R.os.walk(str(romfs)):
        for f in sorted(fs):
            if f.endswith('.nrr'):
                p = R.os.path.join(dp, f)
                with R.open(p, 'rb') as h: out.append((R.os.path.relpath(p, str(romfs)), h.read()))
    return out


def nro_key(exe, romfs, nrrs):
    """What the collection depends on: its tools, the NRRs, the ExeFS's modules (a boot stub's NRO: their headers, which hold the build ID, and sizes) and the RomFS's
    file list with sizes (where a registered NRO is found)"""
    R = rfs(); h = hashlib.sha256()
    for f in NRO_TOOLS: part(h, f, (REPO / f).read_bytes())
    for n, b in nrrs: part(h, n, b)
    for n in sorted(R.os.listdir(str(exe))):
        p = R.os.path.join(str(exe), n)
        if R.os.path.isfile(p):
            with R.open(p, 'rb') as f: part(h, n, f.read(0x100) + str(R.os.path.getsize(p)).encode())
    for dp, _, fs in R.os.walk(str(romfs)):
        for f in sorted(fs):
            p = R.os.path.join(dp, f); part(h, R.os.path.relpath(p, str(romfs)), str(R.os.path.getsize(p)).encode())
    return h.hexdigest()


def pack_nros(d):   # the NROs d/nro.tsv lists with files (a 'refused' row, an online SDK, has none)
    t = Path(d) / 'nro.tsv'
    if not t.is_file(): return []
    return [l.split('\t')[0] for l in t.read_text(encoding='utf-8').splitlines() if l and not l.startswith('#') and not l.startswith('refused\t')]


def collect_nros(exe, romfs, work, in_process=False):
    """The NROs the game registers: {name: collected .nro} in <work>/android-nro/ (dm/tools/nro_collect.py, with the version's own ExeFS for a boot stub's NRO),
    {} without NRRs. Reused while nro_key is unchanged (Super Smash Bros. Ultimate's scan takes minutes). in_process: in this Python, without a pool (the phone).
    Written in place, its key file the marker (deleted first, written last, by a file's rename): a folder renamed under /storage/emulated goes through MediaProvider,
    which may refuse it with EPERM (BananaNative issues #1 and #296; android/app Outputs.java)"""
    work = Path(work); coll, kf = work / 'android-nro', work / 'android-nro.key'
    nrrs = nrr_files(romfs)
    if not nrrs:
        kf.unlink(missing_ok=True); shutil.rmtree(coll, ignore_errors=True); return {}
    key = nro_key(exe, romfs, nrrs)
    if collected_nros(work) is None or kf.read_text().strip() != key:
        work.mkdir(parents=True, exist_ok=True); kf.unlink(missing_ok=True)
        shutil.rmtree(work / 'android-nro.partial', ignore_errors=True)   # left by a version that renamed the folder into place
        if coll.exists(): shutil.rmtree(coll)
        coll.mkdir()
        stage(f'finding the NROs {len(nrrs)} NRR file{"s" if len(nrrs) != 1 else ""} register{"" if len(nrrs) != 1 else "s"}')
        args, log = [str(romfs), str(coll), '--exefs', str(exe)], work / 'nro_collect.log'
        if in_process: rc = run_in_process(REPO / 'dm/tools/nro_collect.py', args, {'DM_NRO_COLLECT_J': '1'}, log)
        else:
            with open(log, 'w') as f: rc = subprocess.run([sys.executable, str(REPO / 'dm/tools/nro_collect.py'), *args], env=child_env(), stdout=f, stderr=subprocess.STDOUT).returncode
        if rc:
            tail = log.read_text(errors='replace').splitlines()[-20:]
            raise Error(f'nro_collect.py exit code {rc} (full output: {log}):\n' + '\n'.join(tail))
        tmp = work / 'android-nro.key.tmp'; write_lf(tmp, key + '\n'); os.replace(tmp, kf)
    got = {n: coll / f'{n}.nro' for n in pack_nros(coll)}
    print(f'  NROs: {len(got)} the game loads at run time' + (f' ({", ".join(got)})' if len(got) <= 8 else ''), flush=True)
    return got


def place_nros(coll, out):   # the collection's nro.tsv into the pack (after the NROs' rewrite), or none for a game without NROs
    t = Path(out) / 'nro.tsv'
    if coll is None or not (Path(coll) / 'nro.tsv').is_file(): t.unlink(missing_ok=True); return
    tmp = Path(out) / 'nro.tsv.partial'; shutil.copyfile(Path(coll) / 'nro.tsv', tmp); os.replace(tmp, t)


def unfinished_nros(out):   # the pack's NROs whose rewrite did not finish: '<name> (<files> missing)'
    out = Path(out)
    return [f'{n} ({" and ".join(f for f in (f"{n}.patched.bin", f"{n}.android.key") if not (out / f).is_file())} missing)'
            for n in pack_nros(out) if not (out / f'{n}.patched.bin').is_file() or not (out / f'{n}.android.key').is_file()]


def nso_build_id(path):   # the module's build ID (NSO header +0x40), as the profile's build rows name it
    b = path.read_bytes()[:0x60]
    if b[:4] != b'NSO0': raise Error(f'{path}: not an NSO')
    return b[0x40:0x50].hex()


def unregistered(g, ids):
    """{module: the profile's builds of it} for each module of ids (module → NSO build ID) whose build no build row names; a module no row names is not listed.
    Every row counts (one row a version: an update's and the base's), not only the last"""
    known = {}
    for v in g.builds().values():
        for m, i in v.items():
            if m != 'display': known.setdefault(m, set()).add(i)
    return {m: sorted(known[m]) for m, i in ids.items() if known.get(m) and i not in known[m]}


def dump_modules(g, exe):
    """The profile's modules that a dump loads. All of them, unless the profile registers builds with different module sets (Mario Kart World: 1.8.0 has a subsdk1 that 1.0.0 lacks):
    then the modules of the build row the dump matches (the modules that row names are all there, with its build IDs, and the others are not)"""
    mods = g.words('modules'); have = [m for m in mods if (exe / m).is_file()]
    if len(have) == len(mods): return mods
    for row in g.builds().values():
        want = [m for m in mods if m in row]
        if want and want == have and all(nso_build_id(exe / m) == row[m] for m in want): return have
    # No row has this set of modules (a build the profile does not register, e.g. Mario Kart World 1.0.0, whose row was dropped): "Try anyway" takes the
    # modules the dump has, when main is among them; build-gated cuts do not apply (the caller flags it in red). Without main the caller names what is missing.
    return have if 'main' in have else mods


def optional_keys(g):
    """The keys of the profile's shader_map.tsv that only some of its registered builds have (shader_optional.tsv): a dump may lack them"""
    f = g.dir / 'shader_optional.tsv'
    return {l.split('\t')[0] for l in f.read_text().splitlines() if l and not l.startswith('#')} if f.is_file() else set()


# PEANUTS begin: a profile whose shader tables are the union of several versions of the game (game.tsv shader_versions, dm/games/README.md), packed from a dump
# of one of them. scripts/build_ipa_from_nsp.py derives a profile with that version's own tables then; a pack needs less: the map's rows say which shaders only
# another version has. shader_split.py prefixes the source of a shader that first appears in a later version with that version (/v1507328/Common/..., main@v1507328;
# the first version's rows carry none), so a dump of one listed version may lack exactly the rows tagged with the other listed versions, and they join the keys
# the dump may lack (optional_keys' use below). Mario Kart 8 Deluxe's map is the union of 4.0.0 and 4.0.1: 135 of its 29,785 rows are tagged /v1507328/, and a
# dump of 4.0.0 alone was refused as "another version or incomplete" for exactly those 135 (2026-11-15). What the tags cannot say: which untagged rows a later
# version dropped. A dump of a later version that lacks those is refused as before, unless shader_optional.tsv names them (Super Mario Bros. Wonder's does)
def shader_versions(g):
    """The versions the profile's shader tables are the union of (game.tsv shader_versions: 'v0', 'v327680'), [] when it names none"""
    try: return [v for v in g.words('shader_versions') if v[:1] == 'v' and v[1:].isdigit()]
    except Exception: return []


def other_versions(g, ver):
    """The listed versions other than the dump's (ver: a number), when the dump's version is listed; else [] (a single-version profile, a version the tables were not made from)"""
    listed = shader_versions(g)
    return [v for v in listed if v != f'v{ver}'] if ver is not None and f'v{ver}' in listed else []


def other_version_keys(g, ver):
    """The keys of the profile's shader_map.tsv whose source is tagged with a listed version other than the dump's: shaders only those versions have"""
    others, keys, f = other_versions(g, ver), set(), g.dir / 'shader_map.tsv'
    if not others or not f.is_file(): return keys
    tagged = lambda s: any(s.startswith(f'/{v}/') or (i := s.find(f'@{v}')) >= 0 and not s[i + len(v) + 1:i + len(v) + 2].isdigit() for v in others)
    with open(f, encoding='utf-8') as h:
        for c in (l.rstrip('\n').split('\t') for l in h):
            if len(c) > 4 and c[0].endswith('.bin') and tagged(c[4]): keys.add(c[3])
    return keys


def may_lack(g, ver=None):
    """The keys a dump of version ver may lack: shader_optional.tsv's, and the other listed versions' own (other_version_keys)"""
    return optional_keys(g) | other_version_keys(g, ver)
# PEANUTS end


def absent_optional(g, pkg, ver=None):   # PEANUTS: ver, the dump's version (may_lack)
    """The keys a split's <package>/_absent.tsv lists (split_shaders), when every one is in the profile's shader_optional.tsv; else (none, or a list naming other keys) empty"""
    f = pkg / '_absent.tsv'
    keys = {l for l in f.read_text().splitlines() if l} if f.is_file() else set()
    return keys if keys and keys <= may_lack(g, ver) else set()   # PEANUTS: may_lack (was optional_keys): also the shaders only another listed version has


# PEANUTS begin: code mods (DESIGN-MODS.md "ExeFS binary patch (IPS/IPS32)"/"Code injection"), the modded variant build dev.peanuts.build.ModdedBuild
# drives through --modules/--mod-exefs/--mod-patches below. A normal (unmodded) pack passes none of these three flags, so this block never runs for it.
def merge_mod_modules(mods, srcs, modules_override, mod_exefs):
    """mods/srcs (dump_modules()'s own list and the exefs-path dict) with a modded build's overrides folded in: modules_override (None, or the
    variant's full module list, the profile's with any module a mod adds in the console's order - dev.peanuts.build.ModBuild.Plan.modules) replaces mods when given;
    mod_exefs ({module name: source path}, --mod-exefs NAME=PATH) replaces or adds that module's source file. Returns (mods, srcs); never mutates
    its arguments. An added module not in mod_exefs is left for the caller's usual "missing" check to catch (a plan that adds a module always
    supplies its source, dev.peanuts.build.ModBuild.compose only adds a name when some mod's exefs/ has that file)."""
    out_mods = list(modules_override) if modules_override is not None else list(mods)
    out_srcs = dict(srcs)
    sys.path.insert(0, str(REPO / 'dm/tools'))
    import xmod
    for name, path in mod_exefs.items():
        out_mods = xmod.insert(out_mods, name)   # where the console loads it (an added subsdk1 before sdk), not after the profile's last module
        out_srcs[name] = Path(path)
    return out_mods, out_srcs


def apply_mod_patches(name, src, patch_dirs, out, changed=None):
    """src (a module's NSO path, stock or --mod-exefs-overridden) with name's exefs_patches applied (dm/tools/patch_ips.py's own IPS/IPS32 layer),
    before the rewrite reads it - patch_nso.py's CUTS assert the module's original bytes and would otherwise abort on a mod's own edits. patch_dirs:
    the directories to search for '<build id>.ips(32)' (patch_ips.stem_matches: Atmosphère's rule for the name), every enabled mod's patch sets in
    priority order (a set is offered to every module and applies to the one whose build ID its file names). changed: a list that gets the offsets of
    the text words the patches changed (cross_module searches them for branches into another module). Returns src unchanged when none match (not an error: patch_ips.py's own
    documented case, e.g. a mod shipping exefs_patches for a build the dump is not). Writes the patched module (when any patch applies) to
    out/.mod-src/<name>/<name> and prints one line this module's rewrite_key then hashes by that file's bytes and name, so a different patch result
    gets its own key and really is rewritten (dm/tools/pack_android.py rewrite_keys: part(h, p.name, p.read_bytes()))."""
    sys.path.insert(0, str(REPO / 'dm/tools'))
    import ips as ips_mod, patch_ips, nso
    try: mod = nso.load(src.read_bytes(), str(src))   # read_bytes: a module inside a .nup is a path of its own kind (nso_build_id), not one open() takes
    except nso.Error as e: raise Error(f'{src}: {e}') from None
    build_id16 = patch_ips.build_id_hex16(mod.build_id)
    paths = patch_ips.patches_for([str(d) for d in patch_dirs], mod.build_id)
    if not paths:
        print(f'mod: no exefs_patches for {name} build {build_id16} in {", ".join(str(d) for d in patch_dirs)}: unpatched', flush=True)
        return src
    try:
        image = patch_ips.apply_patches(mod.image, paths)
    except ips_mod.Error as e:
        raise Error(f'{name}: {e}') from None
    dest_dir = out / '.mod-src' / name
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / name
    patch_ips.write_nso(mod, image, str(dest))
    if changed is not None:
        import xmod
        changed += xmod.changed_words(mod.image, image, mod.text_end)
    print(f'mod: {len(paths)} exefs_patches applied to {name} (build {build_id16}): {", ".join(paths)}', flush=True)
    return dest


def mod_memory_rows(title_id):
    """Whether a build with code mods of this title has memory numbers of its own (dm/peanuts/profile/mod_memory.tsv: rows of <title ID> <key> <value>, which
    gen_runtime_profile.py profile-modules reads). main() then writes the variant its own profile.bin whatever its mods do, a patch as well as an added module"""
    f = REPO / 'dm/peanuts/profile/mod_memory.tsv'
    if not f.is_file(): return False
    rows = (line.split('#', 1)[0].split() for line in f.read_text(encoding='utf-8').splitlines())
    return any(len(c) == 3 and c[0].upper() == title_id.upper() for c in rows)


XMOD_KEY = 'cross-module layout'


def cross_module(mods, srcs, supplied, patched, out):
    """A build with code mods: the branches that go straight from one module into another (dm/tools/xmod.py), searched in every module a mod supplies (supplied: all
    of its code) and in the words a patch changed in one of the game's (patched: {module: those offsets}). → {module: its layout file}, written to
    out/.mod-src/<module>/<module>.xmod-layout for each module that has such branches or is the target of one: rewrite() passes it to patch_nso.py (--xmod),
    which lists the branches in <module>.xmod.tsv for the loader, and hashes it into that module's key, so a change of any module's size or of the branches
    rewrites it. A module that takes no part is rewritten as in a build without mods and gets its usual key; a table an earlier build left for it is removed
    (the loader would apply it)."""
    sys.path.insert(0, str(REPO / 'dm/tools'))
    import nso, xmod
    try: lay = xmod.layout({m: xmod.header(srcs[m].read_bytes()[:0x100], m) for m in mods})
    except ValueError as e: raise Error(str(e)) from None
    far, ptrs = {}, {}
    for m in mods:
        if m not in supplied and not patched.get(m): continue
        try: mod = nso.load(srcs[m].read_bytes(), str(srcs[m]))
        except nso.Error as e: raise Error(f'{srcs[m]}: {e}') from None
        words = None if m in supplied else patched[m]
        far[m] = ('all' if words is None else 'sites', words or [], xmod.far_branches(mod.image, mod.text_end, lay[m][2], words))
        # the addresses a mod's module keeps as data (its relocations). Not searched in a module of the game that a patch changed: its relocations are the game's
        # own, which never leave the module, and a patch of code adds none
        if m in supplied:
            try: ptrs[m] = xmod.far_pointers(mod.image, lay[m][2])
            except (ValueError, struct.error) as e: raise Error(f'{srcs[m]}: its relocations cannot be read ({e})') from None
    files = {}
    for m, text in xmod.plan(lay, far, ptrs).items():
        d = out / '.mod-src' / m; d.mkdir(parents=True, exist_ok=True)
        files[m] = d / f'{m}.xmod-layout'
        if not files[m].is_file() or files[m].read_text(encoding='utf-8') != text: write_lf(files[m], text)
    for m in mods:
        if m not in files: (out / f'{m}.xmod.tsv').unlink(missing_ok=True)
    if files:
        print('mod: cross-module branches: ' + '; '.join(f'{m} at main{lay[m][0]:+#x} on the console' + (f', {len(far[m][2])} out' if m in far and far[m][2] else '') + (f', {len(ptrs[m])} pointers out' if ptrs.get(m) else '') for m in files), flush=True)
    return files


def xmod_keys(keys, layout):
    """A module's keys (rewrite_keys) when it is rewritten with a cross-module layout: each with the layout file's bytes hashed in, and xmod.py's, which
    the rewriter reads such a module's relocations with (not one of REWRITER: no other module's key depends on it)"""
    b = layout.read_bytes() + b'\0' + (REPO / 'dm/tools/xmod.py').read_bytes()
    return [hashlib.sha256(k.encode() + b'\0' + XMOD_KEY.encode() + b'\0' + b).hexdigest() + ' target=android xmod' for k in keys]
# PEANUTS end


def nso_zbic(path):   # whether the module's segments are ZBIC (NSO flag bit 7, system 22.0.0 on), which nso.py needs a decoder for (nso.zbic_decoder)
    b = path.read_bytes()[:0x10]   # read_bytes: a module inside a .nup is a path of its own kind, as in nso_build_id
    return b[:4] == b'NSO0' and bool(int.from_bytes(b[0xc:0x10], 'little') & 0x80)


class ProgressTee:
    """A tool's output stream in run_in_process: everything to the file f; its "progress <done> <total> ..." lines also to out (this script's own output, the
    step log Prepare reads), as "progress <n> 1000 code" with the tool's fraction placed in [base, base + width) of the step's 1000"""
    def __init__(self, f, out, base, width): self.f, self.out, self.base, self.width, self.part = f, out, base, width, ''
    def write(self, s):
        self.f.write(s)
        self.part += s
        while '\n' in self.part:
            line, self.part = self.part.split('\n', 1)
            w = line.split()
            if len(w) >= 3 and w[0] == 'progress' and w[1].isdigit() and w[2].isdigit() and int(w[2]):
                print(f'progress {self.base + self.width * min(int(w[1]), int(w[2])) // int(w[2])} 1000 code', file=self.out, flush=True)
        return len(s)
    def flush(self): self.f.flush()
    def isatty(self): return False


def run_in_process(script, args, env, log, progress=None):
    """script run as `python3 script args` would run it, in this process: its sys.argv, env added to os.environ, stdout and stderr to the file log;
    everything put back after it (the modules it imported from outside the standard library are dropped, so the next run starts afresh). → its exit code.
    progress: (base, width) to pass the tool's progress lines on to this script's output (ProgressTee)"""
    saved = (sys.argv, list(sys.path), dict(os.environ), set(sys.modules), sys.stdout, sys.stderr, os.getcwd())
    code = 0
    with open(log, 'w', buffering=1, encoding='utf-8', errors='backslashreplace') as f:
        sys.stdout = sys.stderr = ProgressTee(f, saved[4], *progress) if progress else f
        try:
            os.environ.update(env); sys.argv = [str(script)] + [str(a) for a in args]; sys.path.insert(0, str(Path(script).parent))
            runpy.run_path(str(script), run_name='__main__')
        except SystemExit as e:
            if e.code is None: code = 0
            elif isinstance(e.code, int): code = e.code
            else: print(e.code, file=f); code = 1
        except BaseException:
            import traceback; traceback.print_exc(); code = 1
        finally:
            argv, path, environ, mods, so, se, cwd = saved
            sys.argv, sys.path[:], sys.stdout, sys.stderr = argv, path, so, se
            os.environ.clear(); os.environ.update(environ); os.chdir(cwd)
            std = os.path.realpath(sys.base_prefix) + os.sep
            for m in set(sys.modules) - mods:
                if not os.path.realpath(getattr(sys.modules.get(m), '__file__', None) or '').startswith(std): sys.modules.pop(m, None)
    return code


def staging(out, m):   # where a module is rewritten before it goes into the pack (inside it: same file system, so the swap is renames)
    return out / f'.rewrite-{m}'


def swap_in(out, m, key):
    """A module's rewritten files from staging(out, m) into out, each by rename, then its key file, written last and also by rename. The old key went
    before the rewrite started, so a pack stopped at any point has either the old module with no key, a mix with no key, or the new module with its key:
    a module without its key is one the launcher refuses (PackCheck) and the next run rewrites"""
    st = staging(out, m)
    for f in sorted(st.iterdir()):
        if f.exists() or f.is_symlink(): os.replace(f, out / f.name)   # PEANUTS(upstream-candidate): an AppleDouble ._<name> on an exFAT/FAT drive goes with its file (split_shaders)
    st.rmdir()
    tmp = out / f'{m}.android.key.tmp'; write_lf(tmp, key + '\n'); os.replace(tmp, out / f'{m}.android.key')


def rewriter_src(src, out, m):
    """The file patch_nso.py reads for module m: src itself, or for a module in a NUP package (`game.nup:update/<v>/exefs/main`) a copy in the pack's
    .nup-src/<module>/ under the module's own name. The rewriter then reads a plain file, so its files (REWRITER, rewrite_key) are the same for a NUP
    file and a folder, and a pack made from either keeps the other's rewritten modules"""
    if '.nup:' not in str(src) and '.nsp:' not in str(src): return src
    d = out / '.nup-src' / m; d.mkdir(parents=True, exist_ok=True)
    f = d / src.name; f.write_bytes(src.read_bytes()); return f


def write_orig(src, out, m):   # PEANUTS
    """<m>.orig.bin for a pack that has none: the bytes the module's file has at the runs of <m>.changed.tsv, in the table's order, as patch_nso.py writes them with a
    rewrite (the companion host reads them: dm/peanuts/peanuts.c rt_orig). The table must be of this file's build. Returns the bytes written"""
    sys.path.insert(0, str(REPO / 'dm/tools'))
    import nso
    mod = nso.read(str(rewriter_src(src, out, m)))
    text = bytes(mod.image[:mod.text_end]); bid = mod.build_id[:0x10].hex(); buf = bytearray()
    for line in (out / f'{m}.changed.tsv').read_text().splitlines():
        if line.startswith('# BUILD_ID'):
            if line.split('\t')[1].strip() != bid: raise Error(f'{m}.changed.tsv is of build {line.split(chr(9))[1].strip()}, the file is {bid}')
            continue
        if not line or line.startswith('#'): continue
        off, n = (int(x, 16) for x in line.split('\t')[:2])
        if off + n > len(text): raise Error(f'{m}.changed.tsv names {off:#x}+{n:#x}, past the code')
        buf += text[off:off + n]
    tmp = out / f'{m}.orig.bin.tmp'; tmp.write_bytes(buf); os.replace(tmp, out / f'{m}.orig.bin')
    return len(buf)


def rewrite(py, srcs, out, jobs, g, in_process=False, xmod=None):
    """patch_nso.py --target android on each module into out (each its own <module>.* files); a module whose key file matches is kept. Each module is
    rewritten into a staging folder and swapped in when it is complete (swap_in), so a run stopped part way never leaves new code with old tables.
    in_process: each in this process (run_in_process) with one worker, one module after the other.
    PEANUTS: xmod = {module: layout file} (cross_module, a build with code mods): those modules are rewritten with --xmod and keyed with the file (xmod_keys)"""
    env = child_env(DM_GAME=g.id, DM_REWRITE_J=str(max(1, (os.cpu_count() or 4) // max(1, len(srcs)))))
    todo = {}
    xmod = xmod or {}; xargs = lambda m: ['--xmod', str(xmod[m])] if m in xmod else []   # PEANUTS
    for m, src in srcs.items():
        keys = rewrite_keys(src, g, out)
        if m in xmod: keys = xmod_keys(keys, xmod[m])   # PEANUTS
        key = keys[0]; kf = out / f'{m}.android.key'
        if (out / f'{m}.patched.bin').is_file() and kf.is_file() and (have := kf.read_text().strip()) in keys:   # the current rewriter's key, an equivalent one's (EQUIV), an earlier formula's
            # PEANUTS: a main kept from the rewriter before main.orig.bin (EQUIV: its pack counts as up to date) gets the file here, from the game's own file and the
            # pack's main.changed.tsv (write_orig: seconds, where a rewrite of Octopath Traveler 0's 111 MB of code for it took the phone 25 minutes); rewritten only if that fails
            ok = True
            if m == 'main' and (out / 'main.changed.tsv').is_file() and not (out / 'main.orig.bin').is_file():
                try: n = write_orig(src, out, m); print(f'  {m}: main.orig.bin made from the game\'s file ({n} bytes of the rewritten runs)')
                except Exception as e: ok = False; print(f'  {m}: main.orig.bin could not be made ({e}): rewriting')
            if ok:
                if have != key: tmp = out / f'{m}.android.key.tmp'; write_lf(tmp, key + '\n'); os.replace(tmp, kf)   # the same rewrite: its key as rewrite_key's (upgrade_keys)
                print(f'  {m}: unchanged, kept'); continue
        todo[m] = (src, key)
    for m in todo: (out / f'{m}.android.key').unlink(missing_ok=True)   # every module to rewrite is unfinished from here on: no mix of old and new modules passes
    if in_process:
        if not todo:
            print('progress 1000 1000 code', flush=True)
            return
        # Prepare's bar (the phone's rewrite step): each module a share of the step's 1000 by the size of its code, the rewriter's progress lines placed in it
        stage('reading the game\'s code')   # a .nup's modules are copied out first (rewriter_src)
        files = {m: rewriter_src(src, out, m) for m, (src, key) in todo.items()}
        size = {m: max(1, nso_text_size(f)) for m, f in files.items()}
        base, total = 0, sum(size.values())
        if todo: print('progress 0 1000 code', flush=True)
        for n, (m, (src, key)) in enumerate(todo.items(), 1):
            kf = out / f'{m}.android.key'; kf.unlink(missing_ok=True)
            shutil.rmtree(staging(out, m), ignore_errors=True)
            stage(f'rewriting {m} ({n} of {len(todo)})')
            print(f'  {m}: rewriting {src} (in process)', flush=True)
            t0 = time.monotonic()
            width = 1000 * size[m] // total
            rc = run_in_process(REPO / 'dm/tools/patch_nso.py', [str(files[m]), staging(out, m), m, '--target', 'android', *xargs(m)], {'DM_GAME': g.id, 'DM_REWRITE_J': '1'}, out / f'rewrite_{m}.log', (base, width))   # PEANUTS: xargs
            if rc:
                tail = (out / f'rewrite_{m}.log').read_text(errors='replace').splitlines()[-20:]
                raise Error(f'patch_nso.py exit code {rc} on {m}:\n' + '\n'.join(tail))
            swap_in(out, m, key)
            base += width
            print(f'  {m}: done ({time.monotonic() - t0:.1f} s)', flush=True)
        if todo: print('progress 1000 1000 code', flush=True)
        return
    procs = {}
    try:
        pending = list(todo)
        while pending or procs:
            while pending and len(procs) < jobs:
                m = pending.pop(0); kf = out / f'{m}.android.key'; kf.unlink(missing_ok=True); shutil.rmtree(staging(out, m), ignore_errors=True)
                print(f'  {m}: rewriting {todo[m][0]}', flush=True)
                log = open(out / f'rewrite_{m}.log', 'w')
                procs[m] = (subprocess.Popen([py, str(REPO / 'dm/tools/patch_nso.py'), str(rewriter_src(todo[m][0], out, m)), str(staging(out, m)), m, '--target', 'android', *xargs(m)], env=env, stdout=log, stderr=subprocess.STDOUT), log)   # PEANUTS: xargs
            for m in list(procs):
                p, log = procs[m]
                if p.poll() is None: continue
                log.close(); del procs[m]
                if p.returncode:
                    tail = (out / f'rewrite_{m}.log').read_text(errors='replace').splitlines()[-20:]
                    raise Error(f'patch_nso.py exit code {p.returncode} on {m}:\n' + '\n'.join(tail))
                swap_in(out, m, todo[m][1])
                print(f'  {m}: done', flush=True)
            if procs: time.sleep(0.2)
    finally:
        for p, log in procs.values():
            if p.poll() is None: p.terminate(); p.wait()
            log.close()


def variants(g):
    """The profile's game.tsv variant rows (`variant <name> <modules> <label>`): [(name, [module, ...])]"""
    return [(v[0], (v[1] if len(v) > 1 and v[1] else 'main').replace(',', ' ').split()) for v in g.values('variant') if v and v[0]]


def rewrite_variants(py, srcs, out, g, in_process=False):
    """Each variant row's modules rewritten again with DM_PATCH_VARIANT=<name> (patch_nso.py VARIANT_CUTS: a game's native 4:3 mod) into out/<name>/,
    keyed '<the module's rewrite_key> variant=<name>' (the launcher's PackCheck checks it the same way); a module whose key matches is kept. Both versions
    stay in the pack, so the launcher's Game settings › Aspect ratio switches between them without another Prepare: the host loads a module from
    out/<name>/ when DM_PATCH_VARIANT=<name> (loader.c)"""
    for v, mods in variants(g):
        if not v.isalnum(): raise Error(f'{g.id}: variant name {v!r} is not a plain folder name')
        vout = out / v; vout.mkdir(exist_ok=True)
        for m in mods:
            if m not in srcs: raise Error(f'{g.id}: variant {v} names module {m}, which the profile does not have')
            key = rewrite_key(srcs[m], g) + f' variant={v}'; kf = vout / f'{m}.android.key'
            if (vout / f'{m}.patched.bin').is_file() and kf.is_file() and kf.read_text().strip() == key:
                print(f'  {m} ({v}): unchanged, kept'); continue
            kf.unlink(missing_ok=True); shutil.rmtree(staging(vout, m), ignore_errors=True)
            print(f'  {m} ({v}): rewriting', flush=True)
            t0, src, log = time.monotonic(), rewriter_src(srcs[m], out, m), vout / f'rewrite_{m}.log'
            args = [str(src), str(staging(vout, m)), m, '--target', 'android']
            if in_process: rc = run_in_process(REPO / 'dm/tools/patch_nso.py', args, {'DM_GAME': g.id, 'DM_REWRITE_J': '1', 'DM_PATCH_VARIANT': v}, log)
            else:
                with open(log, 'w') as f:
                    rc = subprocess.call([py, str(REPO / 'dm/tools/patch_nso.py'), *args], env=child_env(DM_GAME=g.id, DM_PATCH_VARIANT=v), stdout=f, stderr=subprocess.STDOUT)
            if rc:
                tail = log.read_text(errors='replace').splitlines()[-20:]
                raise Error(f'patch_nso.py exit code {rc} on {m} (variant {v}):\n' + '\n'.join(tail))
            swap_in(vout, m, key)
            print(f'  {m} ({v}): done ({time.monotonic() - t0:.1f} s)', flush=True)


VK_BUILD = REPO / 'dm/gfx/tools/vk_android_build.py'
VK_BUILD_WIN = REPO / 'dm/gfx/tools/vk_android_win.py'   # vk_android_build.py with what Windows needs put around it (that file's header)
SPLIT = REPO / 'dm/gfx/tools/shader_split.py'
VK_LIB, VK_PREFIX = 'dmsh_vk.lib', 'v-'   # dmsh_abi_vk.h DMSH_VK_LIB_FILE / DMSH_VK_DIR_PREFIX


def vk_chain():   # DMSH_VK_CHAIN of this source tree, as the host is compiled with (dm/gfx/nvn/dmsh_chain_vk.h)
    import re
    m = re.search(r'#define DMSH_VK_CHAIN "([0-9a-f]+)"', (REPO / 'dm/gfx/nvn/dmsh_chain_vk.h').read_text())
    if not m: raise Error('dm/gfx/nvn/dmsh_chain_vk.h has no DMSH_VK_CHAIN')
    return m.group(1)


def shader_python(want, split=False):
    """A Python that can run vk_android_build.py (zstd: Python 3.14's compression.zstd or backports.zstd), or with split shader_split.py (zstd and lz4; this one first)"""
    if want: cands = [want]
    elif WINDOWS:   # python.exe is the name there; the python3 / python3.14 a bare Windows has are the Store's stubs (WindowsApps), which open the Store
        cands = [sys.executable] + [p for p in (shutil.which('python3.14'), shutil.which('python')) if p and 'windowsapps' not in p.lower()]
    else: cands = ([sys.executable] if split else []) + [shutil.which('python3.14'), shutil.which('python3'), sys.executable]
    test = 'import sys\ntry: from compression import zstd\nexcept ImportError: from backports import zstd' + ('\nimport lz4.block' if split else '')
    for py in filter(None, cands):
        r = subprocess.run([py, '-c', test], capture_output=True)
        if r.returncode == 0: return py
    raise Error(f'no Python with zstd{" and lz4" if split else ""} for {"shader_split.py" if split else "vk_android_build.py"} among {", ".join(filter(None, cands))}: '
                'use the environment of android/README.md or pass --shader-python')


def lib_stamp(lib_dir):
    """{'game', 'inputs'} of a library directory: its inputs.tsv, else the library's own header (game and inputs fields, dmsh_abi_vk.h), which a library copied
    without its inputs.tsv still carries"""
    st = dmsh_chain.read_stamp(str(lib_dir))
    if st: return st
    try: h = (lib_dir / VK_LIB).open('rb').read(72)
    except OSError: return {}
    return {'game': h[40:56].rstrip(b'\0').decode(errors='replace'), 'inputs': h[56:72].rstrip(b'\0').decode(errors='replace')} if len(h) == 72 else {}


def library_current(lib_dir, g):
    """True when lib_dir holds a finished full library of this game and its profile's current inputs"""
    st = lib_stamp(lib_dir)
    return (lib_dir / VK_LIB).is_file() and st.get('game') == g.id and st.get('inputs') == dmsh_chain.inputs(g)


def map_keys(path):
    """The keys of a shader_map.tsv (column 4 of its package rows)"""
    with open(path) as f: return {c[3] for c in (l.rstrip('\n').split('\t') for l in f) if len(c) > 4 and c[0].endswith('.bin')}


def table_lines(path):
    """A table's lines less its comment lines ('#'), as shader_split.py's targeted scan compares them"""
    with open(path, encoding='utf-8') as f: return [l for l in f.read().splitlines() if not l.startswith('#')]


def selected_keys(profile_dir):
    """The shaders the phone's shaders step builds for a profile with a selected set (shader_seen.tsv): its keys that the map has, sorted, each once, as
    ShaderLibs.selection reads them (the map's column 4 of every row but comments and the header); None without a shader_seen.tsv (the whole map). vk_android_build.py
    adds the pixel shaders paired with them. The profile's manifest (vk_delta.tsv) holds exactly that set (vk_android_delta.phone_keys), so a build of the whole map
    reuses nothing outside it from an older library and translates every other shader (Pokopia: 123,000 of its 131,426, about 6 hours on a Mac, at each new version)"""
    seen = Path(profile_dir) / 'shader_seen.tsv'
    if not seen.is_file(): return None
    import vk_android_delta
    with open(Path(profile_dir) / 'shader_map.tsv', encoding='utf-8') as f:
        keys = {c[3] for c in (l.rstrip('\n').split('\t', 4) for l in f if l.strip() and not l.startswith('#') and not l.startswith('package\t')) if len(c) > 3}
    return vk_android_delta.selection(seen.read_text(encoding='utf-8'), keys)


# PEANUTS begin: the check of a split whose Oodle streams the ooz worker decoded (DM_OODLE_OOZ, dm/third_party/ooz/README.md)
def ooz_split_checked(ooz, g, tmp, ver=None):
    """An Oodle stream carries no checksum, and the ooz decoder is not the game's own: a shader's key (the hash of its bytes, shader_split.py skey) is the only
    check there is. So the split in <tmp> counts only when its keys are exactly the profile's, but for the keys shader_optional.tsv gives to another build; one
    key missing or one key more stops the pack, and nothing of that split is used. (vk_android_build.py then hashes every record against its key again.)"""
    want, have = map_keys(g.dir / 'shader_map.tsv'), map_keys(tmp / 'shader_map.tsv')
    lost, more = sorted(k for k in want - have if k not in may_lack(g, ver)), sorted(have - want)
    if lost or more:
        eg = lambda ks: f' (e.g. {", ".join(ks[:3])})' if ks else ''
        raise Error(f'the shaders decoded with the ooz Oodle decoder ({ooz}, DM_OODLE_OOZ) are not the profile\'s: {len(lost)} of the {len(want)} keys in its shader_map.tsv '
                    f'are missing{eg(lost)} and {len(more)} are not in it{eg(more)}. Either the game files are of another version than the profile\'s, or the ooz decoder '
                    f'decoded a stream wrongly; its output is not used. Build this game with Build in the Peanuts app on the device, which decodes with the game\'s own '
                    f'code (the split is in {tmp})')
    print(f'shaders: decoded with the ooz worker ({ooz}); checked: the split has all {len(want & have):,} of the profile\'s shader keys'
          + (f' it can have ({len(want - have)} belong to another build)' if want - have else '') + ' and no other', flush=True)
# PEANUTS end


# PEANUTS begin: what a split that lacks shaders of the profile's map says. The map's rows name the file each shader comes from; shaders whose files the game
# files do not have, in a dump without add-on content, may be DLC that was not imported, which "another version or incomplete" did not say (2026-11-15). No game
# is known to need this: the case it was written for, Mario Kart 8 Deluxe's 135 of 29,785, was the shaders of another listed version (may_lack above, checked
# first), not DLC. So add-on content is named, as one possible reason and no more, only when nothing else is known to explain the missing shaders: the profile's
# tables are of one version and the dump is that version (multi: a union of versions, or a dump of a version the tables were not made from: a missing file is
# then more likely another version's), the dump has no add-on content at all, and every missing shader comes from a file the dump does not have (one whose
# file is there differs for another reason). The phone's words: PackService.splitMatchesProfile
def lacking_shaders(g, rd, hard, total, dlc, tmp, multi=False):
    """The message for a split without the keys hard (sorted) of the profile's total: rd is the RomFS the split read, dlc how many add-on contents the dump has
    (None: not known). A source is a RomFS path up to its first '#', and may go on into a container: its longest part that exists decides whether the file is there"""
    src = {}
    with open(g.dir / 'shader_map.tsv', encoding='utf-8') as f:
        for c in (l.rstrip('\n').split('\t') for l in f):
            if len(c) > 4 and c[0].endswith('.bin') and c[3] not in src: src[c[3]] = c[4].split('#')[0]
    there = {}
    def has(p):
        if p not in there:
            parts = p.strip('/').split('/')
            while parts and not (hit := rd / '/'.join(parts)).exists(): parts.pop()
            there[p] = bool(parts) and hit.is_file()
        return there[p]
    try: gone = [k for k in hard if src.get(k, '').startswith('/') and not has(src[k])]
    except Exception: gone, dlc = [], None   # the game files cannot be asked: only what the tables say
    lack = f'the dump\'s shaders lack {len(hard)} of the {total} in the profile\'s shader_map.tsv (e.g. {", ".join(hard[:3])})'
    if gone and len(gone) == len(hard) and dlc == 0 and not multi:
        return (f'{lack}: all of them come from files the game files do not have (e.g. {src[gone[0]]}), and the game was imported without add-on content. '
                f'The dump is incomplete; one possible reason (not seen with any game yet) is that these files belong to add-on content (DLC) that was not part '
                f'of the import. If you have DLC for this game, give its file to the builder too (peanuts-build: add the DLC .nsp to the command line, with '
                f'--force-nup when the .nup is there already; a dump folder: its dlc/ folder), then build again (the split is in {tmp})')
    what = f', {len(gone)} of them from files the game files do not have (e.g. {src[gone[0]]})' if gone else ''
    maybe = ('' if dlc is not None else '; when the game has add-on content (DLC), it may not have been part of the import')
    return (f'{lack}{what}: the dump is of another version or incomplete, and the profile\'s shader library cannot be built from it{maybe} (the split is in {tmp})')
# PEANUTS end


# PEANUTS begin: a split that reads no shader at all. It made a pack with an empty shader library, which cannot draw (Farming Simulator's provisional profile,
# 2026-11-15: its shaders are in a file shader_split.py has no reader for, and its map has no rows, so nothing was "lacking"). A game that draws through the
# Vulkan or OpenGL layer has no NVN shaders for the split to find, and its profile says so
def no_nvn_shaders(g):
    """Whether the profile says the game has no NVN shaders: its shader_note says "no NVN shaders" (Hades, Digimon Story Cyber Sleuth, Super Mario Galaxy, Balatro),
    or it lists the shaders captured from a run instead (vk_capture.tsv, gl_capture.tsv: Well Dweller)"""
    try: note = g.get('shader_note', '')
    except Exception: note = ''
    return 'no nvn shaders' in note.lower() or any((g.dir / f).is_file() for f in ('vk_capture.tsv', 'gl_capture.tsv'))


def empty_split(g, rd, tmp):
    """The message for a split that read no shader, of a game whose profile does not say it has none"""
    return (f'the shader split read no shader from {rd} or main: the pack would have an empty shader library, and the game could not draw. Either the game keeps '
            f'its shaders in a form shader_split.py does not read yet (the profile\'s README.md says where they are, when that is known), or the dump is incomplete. '
            f'--no-shaders packs the code alone; a game that draws through the Vulkan or OpenGL layer says "no NVN shaders" in its profile\'s shader_note '
            f'(dm/games/{g.id}/game.tsv) (the split is in {tmp})')


def require_shaders(g):
    """Stops a pack of a profile whose shader_map.tsv has no rows and that does not say the game has no NVN shaders, before a shader package or a library left by
    an earlier run (both empty, and current by every check) is packed"""
    f = g.dir / 'shader_map.tsv'
    if f.is_file() and not map_keys(f) and not no_nvn_shaders(g):
        raise Error(f'the profile {g.id} has no shader in its shader_map.tsv, and does not say that the game has none: its shader library would be empty, and the game '
                    f'could not draw. The game keeps its shaders in a form shader_split.py does not read yet (the profile\'s README.md says where, when that is known). '
                    f'--no-shaders packs the code alone')
# PEANUTS end


def collected_nros(work):
    """The folder of the NROs collect_nros collected into work, or None when it has none or its collection did not finish (no key file) (shader_split.py reads a CRA
    archive's file table from them: DM_NRO_DIR)"""
    d = Path(work) / 'android-nro'
    return d if (d / 'nro.tsv').is_file() and (Path(work) / 'android-nro.key').is_file() else None


def split_shaders(a, g, vdir, pkg, parts=None, aoc=(), nros=None):   # parts: the version's (romfs, exefs) when they are not vdir's own (pack_dump.version_part); aoc: add-on RomFS roots to split too; nros: collected_nros
    """The Switch shader package from the dump, as the phone's split step makes it (the version's romfs and main, android/app PackService "split"): shader_split.py into
    <pkg> (written beside it, then renamed, so a stopped split leaves no half package). Its tables go beside the package. The library is the profile's (its shader_map.tsv
    decides what is built, and vk_android_build.py finds each of its shaders in the package by key), so the split must hold every shader of the profile's map: one that
    lacks any (a dump of another version, or incomplete) stops here"""
    rd, ed = parts or (vdir / 'romfs', vdir / 'exefs')
    py = shader_python(a.shader_python, split=True)
    tmp = pkg.with_name(pkg.name + '.tmp'); shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir(parents=True)
    print(f'shaders: splitting the shaders out of {rd} into {pkg} with {py} (a few minutes)', flush=True)
    env = child_env(DM_GAME=g.id, DM_SPLIT_TARGETED='1', **({'DM_NRO_DIR': str(nros)} if nros else {}))   # as the phone: only the files the profile's map names, when the build is the profile's and the tables come out the same
    env.update(peanuts_env('split'))   # PEANUTS: as the phone's split (PackService): the direct scan
    # PEANUTS: DM_OODLE_OOZ (the PC builder's worker from the open-source ooz decoder, dm/third_party/ooz) decodes in place of the game's own decoder, so its
    # split counts only against a profile's map (ooz_split_checked below); a game without one is not split with it at all
    if (ooz := env.get('DM_OODLE_OOZ')) and not (g.dir / 'shader_map.tsv').is_file():
        raise Error(f'DM_OODLE_OOZ is set ({ooz}), and the profile {g.id} has no shader_map.tsv to check the ooz decoder\'s output against: this game\'s shaders are not decoded with it')
    # a program n > 0 (--program) keeps its shaders in the application's add-ons: a Switch 2 Edition's NVN2 archives are in its add-on (Wonder's 010015100B515001)
    r = subprocess.run([py, str(SPLIT), str(rd), str(ed / 'main'), str(tmp / 'pkg'),
                        *[str(tmp / f) for f in ('shader_map.tsv', 'shader_facts.tsv', 'shader_pairs.tsv', 'pso_pairs.tsv')], *([','.join(map(str, aoc))] if aoc else [])], env=env)
    if r.returncode: raise Error(f'shader_split.py exit code {r.returncode}')
    if not map_keys(tmp / 'shader_map.tsv') and not no_nvn_shaders(g): raise Error(empty_split(g, rd, tmp))   # PEANUTS: no shader read is a failure, not an empty library
    ver = getattr(a, 'pack_ver', None)   # PEANUTS: the dump's version (main), for the shaders only another listed version has (may_lack)
    if ooz: ooz_split_checked(ooz, g, tmp, ver)   # PEANUTS: the ooz decoder's output is taken only when the split's keys are the profile's
    want: set[str] = set(); have: set[str] = set()   # PEANUTS(upstream-candidate): no profile table means no absent keys.
    if (g.dir / 'shader_map.tsv').is_file():
        want, have = map_keys(g.dir / 'shader_map.tsv'), map_keys(tmp / 'shader_map.tsv')
        missing = sorted(want - have)
        if hard := [k for k in missing if k not in may_lack(g, ver)]:   # PEANUTS: may_lack (was optional_keys): the other listed versions' shaders are checked first
            raise Error(lacking_shaders(g, rd, hard, len(want), getattr(a, 'dlc_count', None), tmp, shader_versions(g) != [f'v{ver}']))   # PEANUTS: names DLC that was not imported as a possible reason, only for tables of this one version
        # PEANUTS: the one plain line for a dump of one of the versions the tables are the union of (the phone's: PackService.splitMatchesProfile)
        if theirs := [k for k in missing if k in other_version_keys(g, ver)]:
            print(f'shaders: {len(theirs):,} of {len(want):,} shaders in this app\'s table belong to other versions of the game ({", ".join(other_versions(g, ver))}): not needed for v{ver}', flush=True)
        for f in ('shader_map.tsv', 'shader_facts.tsv', 'shader_pairs.tsv'):
            if (g.dir / f).is_file() and table_lines(g.dir / f) != table_lines(tmp / f):   # the comment lines name the game and the split's wording at the time
                print(f'shaders: the dump\'s {f} differs from the profile\'s ({len(have - want)} shaders more); the library is the profile\'s, its shaders found in the package by key')
    shutil.rmtree(pkg, ignore_errors=True); (tmp / 'pkg').rename(pkg)
    absent = sorted(want - have) if (g.dir / 'shader_map.tsv').is_file() else []   # the keys only another registered build has (shader_optional.tsv): listed for a library build to leave out
    if absent:
        (pkg / '_absent.tsv').write_text(''.join(k + '\n' for k in absent))
        print(f'shaders: {len(absent)} of the profile\'s {len(want)} shaders are not in this dump (they belong to another registered build of the game, shader_optional.tsv): listed in {pkg.name}/_absent.tsv')
    for f in list(tmp.iterdir()):
        if f.exists() or f.is_symlink(): f.replace(pkg.with_name(f.name))   # PEANUTS(upstream-candidate): macOS on an exFAT/FAT drive moves a file's AppleDouble ._<name> with it, so the listing's ._ entry is gone by its turn
    tmp.rmdir()


def shader_work(a, work):
    """PEANUTS(upstream-candidate): the folder of the library build's temporary files. Beside the library cache when --shader-cache names one, as <work>/android-shaders/work
    is beside the default cache: with --out and --shader-cache elsewhere (a .nup on a drive the pack is not made on) nothing is made beside the game any more.
    Before, a pack with both still made <title ID>-work/android-shaders/work beside the .nup"""
    return a.shader_cache.resolve().parent / 'work' if a.shader_cache else work / 'android-shaders' / 'work'


def pack_shaders(a, g, work, out, vdir, parts=None, aoc=()):
    """shaders/v-<V>/ in the pack: an up-to-date library from the cache (built there first when it is missing or stale, from the shader package, split from the dump
    first when there is none and --shader-pkg names none). → the library path in the pack, or None"""
    require_shaders(g)   # PEANUTS: an empty map without the profile's word for it is no library to pack
    v = vk_chain(); name = VK_PREFIX + v
    cache = (a.shader_cache or work / 'android-shaders' / 'lib').resolve(); lib = cache / name
    dst = out / 'shaders' / name
    stage('checking the shader library')
    if not library_current(lib, g):
        if a.in_process: raise Error(f'no current shader library {lib}: --in-process does not build one yet (vk_android_build.py starts the translator and spirv-val as programs); --no-shaders packs without')
        pkg = (a.shader_pkg or work / 'android-shaders' / 'pkg').resolve()
        if not pkg.is_dir(): split_shaders(a, g, vdir, pkg, parts, aoc, collected_nros(work))   # also into a --shader-pkg folder that is not there yet
        py = shader_python(a.shader_python)
        print(f'shaders: building the Vulkan library {lib} from {pkg} with {py} (J={a.J}; about 10 minutes on DKB)', flush=True)
        swork = shader_work(a, work); swork.mkdir(parents=True, exist_ok=True)   # PEANUTS(upstream-candidate): beside a --shader-cache folder (shader_work)
        env = child_env(DM_GAME=g.id, J=str(a.J), WORK=str(swork))
        env.update(peanuts_env('shaders'))   # PEANUTS: as the phone's shaders step (PackService): zstd 6
        keys = selected_keys(g.dir)   # a profile with a selected set (shader_seen.tsv): only its shaders, as the phone builds them (PackService, ShaderLibs.selection)
        if keys is not None:
            if not keys: raise Error('the profile\'s shader_seen.tsv names none of the shaders of its shader_map.tsv')   # PEANUTS(upstream-candidate): no substitutions.
            print(f'shaders: the profile selects {len(keys):,} shaders (shader_seen.tsv) and the pixel shaders paired with them, as the phone builds', flush=True)
        absent = absent_optional(g, pkg, getattr(a, 'pack_ver', None))
        # PEANUTS: pass the validated keys to each child, so pair expansion cannot restore them.
        with tempfile.TemporaryDirectory(prefix='selection-', dir=swork) as selection_dir:
            env.pop('DMSH_VK_ABSENT_FILE', None)
            if absent:
                excluded = Path(selection_dir) / 'absent.tsv'
                write_lf(excluded, ''.join(k + '\n' for k in sorted(absent)))
                env['DMSH_VK_ABSENT_FILE'] = str(excluded)
                print(f'shaders: excluding {len(absent):,} validated missing shaders from this build', flush=True)
            args = []
            if keys:   # on Windows a file the wrapper reads (vk_android_win.py "@<file>"): thousands of keys pass a command line's 32,767 characters there
                if WINDOWS:
                    kf = Path(selection_dir) / 'keys.txt'; write_lf(kf, ''.join(k + '\n' for k in keys)); args = ['@' + str(kf)]   # PEANUTS: private to this child; removed after it exits
                else: args = keys
            r = subprocess.run([py, str(VK_BUILD_WIN if WINDOWS or sys.platform.startswith('linux') else VK_BUILD), '--target', 'vulkan', str(pkg), str(cache), *args], env=env)   # PEANUTS: on Linux too, for the translator's start (the repository's apphost is the Mac's; vk_android_win.py starts the dlls with dotnet)
        if r.returncode: raise Error(f'vk_android_build.py exit code {r.returncode} (a package that lacks the profile\'s shaders stops it: delete {pkg} and the split runs again)')
        if not library_current(lib, g): raise Error(f'vk_android_build.py finished but {lib} is not a full library of {g.id} with the current inputs')
    else: print(f'shaders: {lib} is current (game {g.id}, inputs {dmsh_chain.inputs(g)})')
    dst.mkdir(parents=True, exist_ok=True)
    if not (lib / dmsh_chain.STAMP).is_file():   # a library copied without its stamp: written from its header (library_current took it from there)
        st = lib_stamp(lib); dmsh_chain.write_stamp(str(lib), st['game'], st['inputs'])
        if WINDOWS: write_lf(lib / dmsh_chain.STAMP, (lib / dmsh_chain.STAMP).read_text(encoding='utf-8'))   # written in text mode: CR LF there
    for f in (VK_LIB, dmsh_chain.STAMP):
        s_, d_ = lib / f, dst / f
        if d_.is_file() and d_.stat().st_size == s_.stat().st_size and d_.stat().st_mtime_ns == s_.stat().st_mtime_ns: continue
        tmp = d_.with_name(f + '.partial')
        if f == VK_LIB: copy_counted(s_, tmp, 'copying the shader library into the pack')
        else: shutil.copy2(s_, tmp)
        os.replace(tmp, d_)   # replace: Windows' rename does not take an existing name
    others = sorted(o.name for o in (out / 'shaders').iterdir() if o.name != name and o.name.startswith(VK_PREFIX) and o.is_dir())
    if others:   # kept: another APK (the test and the play app, an older build) may load one of them from this pack; delete by hand to save the space
        print(f'shaders: kept the libraries of other versions in {out / "shaders"}: {", ".join(others)} (about 350 MB each)')
    return dst / VK_LIB


def scene_overlays(g, romfs, out):
    """The Scene resolution overlays (dm/tools/scene_res.py) of a profile with scene_scale_palette rows, from this RomFS into <out>/scene_res/<preset>/: [(preset, folder in the
    pack)]. A palette that is not the profile's (another build) or a failed check is a [RED] line and no overlay: the pack is made all the same, the launcher's Scene resolution
    then offers nothing. Folders of presets the profile no longer has are removed"""
    d = out / 'scene_res'
    if not g.values('scene_scale_palette'):
        shutil.rmtree(d, ignore_errors=True); return []
    stage('writing the scene resolution overlays')
    sys.path.insert(0, str(REPO / 'dm/tools'))
    import scene_res
    try:
        names = {p[0] for p in scene_res.presets(g)}
        if d.is_dir():
            for f in d.iterdir():
                if f.is_dir() and f.name not in names: shutil.rmtree(f)
        made = scene_res.make(str(romfs), d, g)
    except (scene_res.Error, scene_res.nup.Error, OSError) as e:
        print(f'[RED] scene resolution overlays not made: {e}', flush=True)
        shutil.rmtree(d, ignore_errors=True); return []
    except Exception as e:   # anything else: the option is optional, the pack is made all the same (scene_res turns what it knows into Error; this is the last guard)
        print(f'[RED] scene resolution overlays not made: {type(e).__name__}: {e}', flush=True)
        shutil.rmtree(d, ignore_errors=True); return []
    return [(m[0], f'scene_res/{m[0]}') for m in made]


def work_dir(out, app):
    """The work folder the shader split and library cache go to (android-shaders/): the pack's own <title ID>-work, so a pack folder picked elsewhere (--out) takes
    the extracted shaders with it instead of leaving them beside the game. The default pack, <game folder>/../<title ID>-work/android-pack, gives the same folder as before"""
    name = f'{app:016X}-work'
    return out.parent if out.parent.name.upper() == name.upper() else out.parent / name


def commit():   # the packer's commit; DM_PACKER_COMMIT when the repository's tool has none (the phone: no git there, the app passes the commit it was built from)
    try: r = subprocess.run(['git', '-C', str(REPO), 'rev-parse', '--short=12', 'HEAD'], capture_output=True, text=True).stdout.strip()
    except OSError: r = ''
    return r or os.environ.get('DM_PACKER_COMMIT') or 'unknown'


def place_cheats(g, out):
    """The profile's cheat files (game.tsv cheats rows, game.Game.cheats: Atmosphère-format, named <build>.txt) into <out>/cheats/, from where the launcher copies them into
    its own cheats folder for the game when Game settings › Cheats is on (Cheats.java). Not in the rewrite key: they change no code, only what the host writes while the game
    runs, so a new or changed cheat file needs a Prepare (this copy) but never makes a pack's code stale. A file of a row the profile no longer has is removed"""
    try: files = g.cheats()
    except ValueError as e: raise Error(str(e)) from None
    d = out / 'cheats'
    if files: d.mkdir(exist_ok=True)
    for f in files:
        tmp = d / (f.name + '.partial'); shutil.copyfile(f, tmp); os.replace(tmp, d / f.name)
    if d.is_dir():
        for old in d.iterdir():
            if old.name not in {f.name for f in files}: old.unlink()
        if not files: d.rmdir()
    if files: print(f'cheats: {", ".join(f.name for f in files)} into {d}')


def main():
    ap = argparse.ArgumentParser(description='Pack a dumped game for the Android launcher: its code modules rewritten with --target android, no RomFS')
    ap.add_argument('game', type=Path, help='the game folder (base/exefs, base/romfs or an update\'s romfs, updates/<version>/, dlc/), or a .nup file')
    ap.add_argument('--version', type=int, help='the update version to pack (default: the newest in updates/; 0 = the base game)')
    ap.add_argument('--program', type=int, default=0, help='pack program n > 0 of a dump folder (a Switch 2 Edition\'s program 1: updates/<version>/program/1/ in the console\'s layout) instead of the game '
                                                            '(program 0): its own profile and pack folder; program 0\'s newest RomFS and the add-ons are named beside its own RomFS')
    ap.add_argument('--out', type=Path, help='the pack folder (default: <game folder>/../<title ID>-work/android-pack)')
    ap.add_argument('-j', type=int, default=2, help='modules rewritten at once (default 2)')
    ap.add_argument('--shader-cache', type=Path, help='the folder of Vulkan shader libraries v-<V>/ (default: <title ID>-work/android-shaders/lib, the <title ID>-work beside or holding the pack folder)')
    ap.add_argument('--shader-pkg', type=Path, help='the Switch shader package to build the library from, split from the dump into it when it is not there (default: <title ID>-work/android-shaders/pkg)')
    ap.add_argument('--shader-python', help='the Python 3.14 (or one with backports.zstd) that runs vk_android_build.py')
    ap.add_argument('-J', type=int, default=8, help='shaders translated at once when the library is built (default 8)')
    ap.add_argument('--no-shaders', action='store_true', help='make the pack without the shader library')
    ap.add_argument('--code-only', action='store_true', help='only rewrite the modules (the phone\'s rewrite step): pack.tsv, game.tsv and the shaders stay as they are')
    ap.add_argument('--keep-code', action='store_true', help='do not rewrite: the pack\'s modules must be there with their key files (the phone\'s pack step)')
    ap.add_argument('--made-by', help='who makes this pack, written as its made_by row (the phone\'s pack step: "app <version>"; the launcher then updates the pack\'s code by itself when only the tools changed)')
    ap.add_argument('--in-process', action='store_true', help='run the rewriter in this process, no subprocesses (the phone\'s packer); the shader library must be current or left out')
    # PEANUTS begin: code mods (DESIGN-MODS.md), a modded variant build (dev.peanuts.build.ModdedBuild composes these from ModBuild.Plan). None given: this
    # function behaves exactly as before - no mod ever changes a normal pack's modules, sources or build.
    ap.add_argument('--modules', help='the modded build\'s full module list, comma-separated, profile order first then any module a mod adds '
                     '(dev.peanuts.build.ModBuild.Plan.modules); default: the dump\'s own modules, unchanged')
    ap.add_argument('--mod-exefs', action='append', default=[], metavar='NAME=PATH',
                     help='a mod\'s exefs/NAME as that module\'s source, replacing the dump\'s own file or adding a module the dump does not have; repeatable')
    ap.add_argument('--mod-patches', action='append', default=[], metavar='NAME=DIR',
                     help='a mod\'s exefs_patches/NAME directory, applied (dm/tools/patch_ips.py) to NAME\'s image before the rewrite; repeatable')
    # PEANUTS end
    a = ap.parse_args()
    if WINDOWS:
        for s in (sys.stdout, sys.stderr): s.reconfigure(encoding='utf-8', errors='backslashreplace')   # as the tools' output (child_env): one encoding for the reader   # pyright: ignore[reportAttributeAccessIssue]  # PEANUTS: standard streams use TextIOWrapper.reconfigure.
        if not a.in_process: _job = windows_job()   # noqa: F841  (held until the process ends)
        signal.signal(signal.SIGBREAK, signal.default_int_handler)   # CTRL_BREAK_EVENT (a GUI that shares a console): stop as on Ctrl-C, the tools stopped in the finally blocks   # pyright: ignore[reportAttributeAccessIssue]  # PEANUTS: Windows-only API absent from Darwin stubs.
    folder = a.game.resolve()
    try:
        try: import capstone, lz4.block   # noqa: F401  (patch_nso.py needs both; checked here so a missing one fails before any work)
        except ImportError as e: raise Error(f'{e.name} is missing in {sys.executable}: see android/README.md for the environment') from None
        if capstone.cs_version()[0] != 5: raise Error(f'Capstone {capstone.__version__}: the rewriter needs Capstone 5')
        app, vers, dlc = pack_dump.scan(folder)
        app0, conorig = app, None
        vdir = folder   # PEANUTS(upstream-candidate): both version branches replace this before use; keep the path's type explicit
        if a.program:   # program n > 0: its package program/<n> stands where a version folder would; program 0's newest RomFS is its "conorig"
            if pack_dump.nup.is_nup(folder): raise Error(f'--program {a.program}: {folder} is a .nup, which holds program 0 only; pack from the dump folder (updates/<version>/program/{a.program}/)')
            found = pack_dump.program_dir(folder, a.program)
            if found is None: raise Error(f'{folder}: no program/{a.program}/exefs/main.npdm in updates/<version>/ or base/ (the console\'s layout, docs/agents/01-extract.md)')
            ver, vdir = found
            conorig = pack_dump.version_part(folder, vers, max(vers, default=0), 'romfs')
            app = pack_dump.program_id(vdir / 'exefs/main.npdm')
            if (app & pack_dump.TITLE_NUMBER & ~0xFF) != (app0 & pack_dump.TITLE_NUMBER) or (app & 0xFF) != a.program:
                raise Error(f'{vdir}: program ID {app:016X} is not program {a.program} of {app0:016X}')
        try: g = game.load(f'{app:016X}')
        except SystemExit: raise Error(f'no profile dm/games/{app:016X}: the Android pack needs one (build_ipa_from_nsp.py makes a provisional one)') from None
        if not a.program:
            ver = a.version if a.version is not None else max(vers, default=0)
            if ver and ver not in vers: raise Error(f'no updates/{ver} (have: {", ".join(map(str, sorted(vers))) or "none"})')
        for tid, p in sorted(dlc.items()):   # the add-on's content meta record: the game version it needs (pack_dump.dlc_required_version)
            if (need := pack_dump.dlc_required_version(p)) > ver:
                print(f'[RED] DLC {tid:016X} needs game version {need} or later (its content meta record); this pack is version {ver}: the game may not use it', flush=True)
        a.dlc_count = len(dlc)   # PEANUTS: for split_shaders' message (lacking_shaders): a dump without add-on content whose missing shaders come from files it does not have
        a.pack_ver = ver   # PEANUTS: the version packed, for the shaders only another version of the profile's shader_versions has (may_lack)
        if a.program: exe, romfs = vdir / 'exefs', vdir / 'romfs'
        else:
            vdir = pack_dump.version_dir(folder, vers, ver)
            exe, romfs = pack_dump.version_part(folder, vers, ver, 'exefs'), pack_dump.version_part(folder, vers, ver, 'romfs')   # its own, else the base's
        if not (exe / 'main.npdm').is_file() or not romfs.is_dir(): raise Error(f'{vdir}: no exefs/main.npdm and romfs (neither its own nor the base\'s)')
        if (pid := pack_dump.program_id(exe / 'main.npdm')) != app: raise Error(f'{vdir}: program ID {pid:016X}, the base is {app:016X}')
        mods = dump_modules(g, exe)
        srcs = {m: exe / m for m in mods}
        out = (a.out or folder.parent / f'{app:016X}-work' / 'android-pack').resolve()
        try: out.relative_to(REPO); raise Error(f'{out} is inside the repository: packs never go there')
        except ValueError: pass
        out.mkdir(parents=True, exist_ok=True)
        # PEANUTS begin: code mods (DESIGN-MODS.md) - a modded variant build's module overrides, applied before the usual missing/ZBIC checks so they see
        # the mod's own files, not the stock dump's. Stock call sites (no --modules/--mod-exefs/--mod-patches) are unaffected: mod_exefs/mod_patches_by_name
        # are then both empty and modules_override is None, so merge_mod_modules returns (mods, srcs) unchanged.
        mod_exefs = {}
        for kv in a.mod_exefs:
            name, _, path = kv.partition('=')
            if not path: raise Error(f'--mod-exefs {kv!r}: expected NAME=PATH')
            mod_exefs[name] = path
        mod_patches_by_name = {}
        for kv in a.mod_patches:
            name, _, d = kv.partition('=')
            if not d: raise Error(f'--mod-patches {kv!r}: expected NAME=DIR')
            mod_patches_by_name.setdefault(name, []).append(d)
        modules_override = a.modules.split(',') if a.modules else None
        mods, srcs = merge_mod_modules(mods, srcs, modules_override, mod_exefs)
        # said here in a sentence: the reads below (the patches, the layout) come before the usual check for missing modules, and ended in a traceback
        if lost := [m for m in mods if m not in srcs]: raise Error(f'--modules names {", ".join(lost)}, which neither the game nor a mod has')
        if gone := [f'{m} ({p})' for m, p in mod_exefs.items() if not srcs[m].is_file()]:
            raise Error(f'a mod\'s module is not there: {", ".join(gone)}. The mod was deleted or changed since the build was started: build with mods again')
        patched = {}
        for name, dirs in mod_patches_by_name.items():
            if name not in mods: raise Error(f'--mod-patches {name}=...: {name} is not in the module list ({" ".join(mods)})')
            srcs[name] = apply_mod_patches(name, srcs[name], dirs, out, patched.setdefault(name, []))
        # the patches go onto the module before anything reads it for the rewrite, and the branches between modules are worked out from the patched code
        xmod_files = cross_module(mods, srcs, set(mod_exefs), patched, out) if mod_exefs or mod_patches_by_name else {}
        # PEANUTS end
        if missing := [m for m in mods if not srcs[m].is_file()]: raise Error(f'{exe}: no {", ".join(missing)} (the profile\'s modules are {" ".join(mods)})')
        if not a.keep_code and (zb := [m for m in mods if nso_zbic(srcs[m])]):
            sys.path.insert(0, str(REPO / 'dm/tools')); import nso   # its ZBIC decoder, checked here so a missing one fails before any work
            try: ok = nso.zbic_decoder()
            except nso.Error as e: raise Error(f'{" ".join(zb)}: ZBIC-compressed (system 22.0.0+): {e}') from None
            if not ok: raise Error(f'{" ".join(zb)}: ZBIC-compressed (system 22.0.0+), and {sys.executable} has no decoder: {nso.ZBIC_MISSING} (android/README.md)')
        work = work_dir(out, app)
        ids = {m: nso_build_id(srcs[m]) for m in mods}
        # PEANUTS begin: code mods (DESIGN-MODS.md) - a modded build adding a module past the profile's own game.tsv row gets its own profile.bin (the
        # generic host's per-title data, dm/peanuts/profile/gen_runtime_profile.py) written beside the pack, read instead of the APK asset
        # (dev.peanuts.build.GenericProfile.envLines(Context,String,File) when <packDir>/profile.bin exists). A plain replace-only mod (mods unchanged
        # from the dump's own list) needs none of this: the shipped per-title asset already lists every module the mod replaces.
        # The same for a variant of a title that has memory numbers for its builds with mods (mod_memory.tsv, which profile-modules reads), whatever its mods
        # do: only a build that adds a module was given them. A variant that needs no profile of its own keeps none that an earlier build of it left.
        modded = bool(mod_exefs or mod_patches_by_name)
        own_profile = mods != dump_modules(g, exe) or modded and mod_memory_rows(f'{app:016X}')
        if modded and not own_profile: (out / 'profile.bin').unlink(missing_ok=True)
        if own_profile:
            gen = REPO / 'dm/peanuts/profile/gen_runtime_profile.py'
            profile_bin, gen_log = out / 'profile.bin', out / 'rewrite_profile.log'
            gen_args = ['profile-modules', f'{app:016X}', str(profile_bin), ','.join(mods)]
            if a.in_process: rc = run_in_process(gen, gen_args, {}, gen_log)
            else:
                with open(gen_log, 'w') as f: rc = subprocess.call([sys.executable, str(gen), *gen_args], stdout=f, stderr=subprocess.STDOUT)
            if rc: raise Error(f'gen_runtime_profile.py profile-modules failed (exit {rc}); see {gen_log}')
            print(f'mod: wrote {profile_bin} with modules {" ".join(mods)} (the generic host\'s profile for this variant)', flush=True)
        # PEANUTS end
        print(f'{app:016X} {g.get("name", "?")} version {ver}: {vdir}', flush=True)
        for m, known in unregistered(g, ids).items(): print(f'[RED] {m} build {ids[m]} is none of the profile\'s builds ({", ".join(known)}): build-gated cuts do not apply')
        if a.keep_code:
            stage('checking the rewritten code')
            # each module needs its rewritten code and, written last by the rewrite, its key: a module without the key is a rewrite that did not finish (or one from before keys)
            gaps = [f'{m} ({" and ".join(f for f in (f"{m}.patched.bin", f"{m}.android.key") if not (out / f).is_file())} missing)'
                    for m in mods if not (out / f'{m}.patched.bin').is_file() or not (out / f'{m}.android.key').is_file()]
            gaps += unfinished_nros(out)
            if gaps: raise Error(f'--keep-code: the rewrite step has not finished for {", ".join(gaps)} in {out}: run the rewrite step again')
            print('  modules kept as they are (--keep-code)')
        else:
            work = folder.parent / f'{app:016X}-work'
            nros = collect_nros(exe, romfs, work, a.in_process)
            try:
                rewrite(sys.executable, srcs | nros, out, max(1, a.j), g, a.in_process, xmod_files)   # PEANUTS: xmod_files
                rewrite_variants(sys.executable, srcs, out, g, a.in_process)   # game.tsv variant rows (a native 4:3 mod) into <pack>/<variant>/
            finally: shutil.rmtree(out / '.nup-src', ignore_errors=True)   # the module copies a NUP game's rewrite read (rewriter_src)
            place_nros(work / 'android-nro' if nros else None, out)
        if a.code_only: print(f'done: the modules in {out} (--code-only: pack.tsv and the shaders unchanged)'); return
        if a.keep_code:
            coll = collected_nros(folder.parent / f'{app:016X}-work')   # the collected NROs the keys were made from, when this machine collected them
            upgrade_keys(srcs | {n: coll / f'{n}.nro' for n in pack_nros(out) if coll and (coll / f'{n}.nro').is_file()}, out, g, xmod_files)   # PEANUTS: xmod_files. Before the copy below replaces the game.tsv an earlier key was made with (a rewrite run did it in rewrite)
        shutil.copyfile(g.dir / 'game.tsv', out / 'game.tsv')
        place_cheats(g, out)
        # a program n > 0 splits program 0's RomFS (its conorig) and the add-ons with its own main: its own RomFS holds no shaders (Wonder's program 1: one text file)
        shaders = None if a.no_shaders else pack_shaders(a, g, work, out, vdir, (conorig, exe) if a.program else (romfs, exe), list(dlc.values()) if a.program else ())
        scenes = scene_overlays(g, romfs, out)
        top = folder.parent if pack_dump.nup.is_nup(folder) else folder   # a NUP file's paths are `<name>.nup:<package>/...`, relative to its folder
        rel = lambda p: p.relative_to(top).as_posix()
        rows = [('format', str(FORMAT)), ('title_id', f'{app:016X}'), ('name', g.get('name', '')), ('version', str(ver)), ('target', 'android'),
                # PEANUTS: a module a mod supplies is listed as mod_build, not build: the launcher compares every build row with the game's own file of that name
                # (BuildCheck.build), which an added module does not have and a replaced one differs from, and would refuse to start the pack
                ('modules', ' '.join(mods)), *[('mod_build' if m in mod_exefs else 'build', f'{m}={ids[m]}') for m in mods], ('romfs', rel(romfs)),
                *[('aoc', f'{pack_dump.dlc_index(tid, app)}\t{rel(p)}') for tid, p in sorted(dlc.items())],
                # program n > 0: which program it is, the application (its save and add-ons), and each program index's RomFS (DM_ROMFS_PROGRAM_<i>: 0 is the game's, "conorig")
                *([('program', str(a.program)), ('application', f'{app0:016X}'), ('romfs_program', f'0\t{rel(conorig)}'), ('romfs_program', f'{a.program}\t{rel(romfs)}')] if a.program else []),
                *([('shaders', shaders.relative_to(out).as_posix())] if shaders else []), *[('scene_res', f'{n}\t{p}') for n, p in scenes], ('packer', commit()), *([('made_by', a.made_by)] if a.made_by else [])]
        stage('writing the pack\'s list of files')
        tmp = out / 'pack.tsv.partial'
        write_lf(tmp, ''.join(f'{k}\t{v}\n' for k, v in rows)); os.replace(tmp, out / 'pack.tsv')
        size = sum(f.stat().st_size for f in out.rglob('*') if f.is_file())
    except (Error, pack_dump.Error) as e: sys.exit(f'[RED] {e}')
    print(f'done: {out} ({size / 2**20:.1f} MiB); RomFS {rel(romfs)} and {len(dlc)} DLC stay in the game folder')


if __name__ == '__main__':
    main()
