#!/usr/bin/env python3
# PEANUTS (issue #120, peanuts/UPSTREAM-EDITS.md Round 26): the rewrite step keeps a module whose rewrite would come out the same although its key moved.
#
# A module's key (scripts/pack_android.py rewrite_key) covers the rewriter's files, the module, the profile's whole patches.py and every build / fps_decouple row of its
# game.tsv. An app whose profile gains a game version adds a build row (and often cuts for the new main), so the key of every module moves and the phone rewrote sdk
# and subsdk* again although their build IDs, and so their rewrites, had not changed. patch_nso.py reads of the profile only what is keyed by the module's build ID:
#   - the patch tables at that build ID (CUTS, OBS, FRAME_STEP, CAPABILITIES, VARIANT_CUTS), which patch_nso.py merges from every patch file (sdk_patches.py, each
#     profile's patches.py, DM_GAMES_DIR's), and for a build no patch file registers the selected profile's MATCH_CUTS of the module's name: each file that has
#     any of these counts whole (the key takes only the profile's own patches.py; this is stricter); a module none of them names counts none of them;
#   - for a build no patch file registers, the build rows' build IDs of the module's name (which builds the game was brought up on: patch_nso.py own / known);
#   - for main, the fps_decouple rows.
# These, with the rewriter's files and the module's bytes, are the module's "part" (part()). When a module is rewritten (or kept with its current key), its part, its key
# and the SHA-256 of its patched.bin are recorded in <PN_REUSE_DIR>/rewrite/<module>.tsv. A later rewrite whose key differs keeps the module when its part is the
# recorded one and patched.bin is the recorded file: it writes the current key (as upgrade_keys does for an equivalent rewriter, EQUIV) and rewrites nothing. Any
# doubt (no record, another part, another patched.bin, a mod's cross-module layout, no PN_REUSE_DIR) rewrites the module as before; PN_REUSE=0 (the job's env.txt)
# turns it off.
#
# The record also keeps a copy of the module's files (<module>.* and build_src.sha256, each by its SHA-256, in <PN_REUSE_DIR>/rewrite/<module>.files/): a pack that
# has no rewrite of the module (another Build folder, a build deleted) gets them back when its part is the recorded one and every file is the recorded one (restore),
# instead of a rewrite (ACNH's main: 2 to 3 minutes on an AYN Thor, Octopath Traveler 0's: 25). No copy that would leave less than KEEP_FREE_MB free; the copies
# count in the cap of the kept copies of every game (dm/peanuts/reuse/shader_reuse.py keep_library).
import hashlib
import importlib.util
import os
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TABLES = ('CUTS', 'OBS', 'FRAME_STEP', 'CAPABILITIES')
FORMAT = 'pn-rewrite-part 1'
_LOADED = {}
SHARED = 'build_src.sha256'   # written by every module's rewrite, the same for each (patch_nso.py): copied with each, put back when the pack has none
KEEP_FREE_MB = 2048   # as shader_reuse.py's


def patch_files():
    """The patch files patch_nso.py loads (PATCH_FILES): sdk_patches.py, every profile's patches.py, and DM_GAMES_DIR's"""
    tools = REPO / 'dm/tools'
    out = [tools / 'sdk_patches.py'] + sorted((REPO / 'dm/games').glob('*/patches.py'))
    for d in [d for d in os.environ.get('DM_GAMES_DIR', '').split(os.pathsep) if d]: out += sorted(Path(d).glob('*/patches.py'))
    seen, uniq = set(), []
    for p in out:
        r = os.path.realpath(p)
        if r not in seen: seen.add(r); uniq.append(Path(p))
    return uniq


def tables(path):
    """{table: {build ID: value}} and 'MATCH_CUTS', 'VARIANT_CUTS' of one patch file, run once (patch_nso.py load_patches runs them the same way)"""
    key = os.path.realpath(path)
    if key not in _LOADED:
        if str(REPO / 'dm/tools') not in sys.path: sys.path.insert(0, str(REPO / 'dm/tools'))   # patch files import the rewriter's helpers (nso_asm), as patch_nso.py runs from there
        spec = importlib.util.spec_from_file_location(f'pn_patches_{len(_LOADED)}', path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
        _LOADED[key] = {k: getattr(m, k, {}) or {} for k in TABLES + ('MATCH_CUTS', 'VARIANT_CUTS')}
    return _LOADED[key]


def build_id(b):
    """A module's build ID as patch_nso.py keys it (its first 16 bytes, hex): NSO and NRO both keep it at 0x40"""
    return b[0x40:0x50].hex() if len(b) >= 0x50 and (b[:4] == b'NSO0' or b[0x10:0x14] == b'NRO0') else None


def part(name, src_bytes, g, rewriter_files):
    """The module's part of what its rewrite reads (see the head of this file) → hex digest; None when it cannot be told"""
    bid = build_id(src_bytes)
    if bid is None: return None
    registered, touching = False, {}
    own = Path(g.dir) / 'patches.py'
    for p in patch_files() + ([own] if own.is_file() else []):
        try: t = tables(p)
        except Exception: return None   # a patch file that does not load: patch_nso.py would stop; nothing is kept
        if bid in t['CUTS']: registered = True
        if any(bid in t[k] for k in TABLES) or any(bid in bs for bs in t['VARIANT_CUTS'].values()):   # the tables patch_nso.py merges by build ID from every file
            touching[os.path.realpath(p)] = p
    if own.is_file() and not registered and tables(own)['MATCH_CUTS'].get(name): touching[os.path.realpath(own)] = own   # the selected profile's own MATCH_CUTS
    h = hashlib.sha256(FORMAT.encode() + b'\0')
    for f in rewriter_files: h.update(f'{f}\0'.encode() + (REPO / 'dm/tools' / f).read_bytes() + b'\0')
    h.update(b'module\0' + name.encode() + b'\0' + hashlib.sha256(src_bytes).digest())
    for p in sorted(touching.values(), key=lambda p: (Path(p).parent.name, Path(p).name)):   # named by folder and file, not by path: the tools' folder moves with an app update
        h.update(b'patches\0' + f'{Path(p).parent.name}/{Path(p).name}'.encode() + b'\0' + Path(p).read_bytes() + b'\0')
    if not touching: h.update(b'patches\0untouched\0')
    if not registered:
        ids = sorted({dict(x.split('=', 1) for x in r[2:] if '=' in x).get(name, '') for r in g.values('build')} - {''})
        h.update(b'builds\0' + ','.join(ids).encode() + b'\0')
        # <module>.match.tsv names each other build row's build of the module with its cut count (patch_nso.py `known`), from the CUTS every patch file has
        # for it: a fix of another build's cuts changes that table (found in review)
        counts = []
        for p in patch_files() + ([own] if own.is_file() else []):
            c = tables(p)['CUTS']
            counts += [f'{Path(p).parent.name}/{Path(p).name}:{i}={len(c[i])}' for i in ids if i != bid and c.get(i)]
        h.update(b'known\0' + ','.join(sorted(counts)).encode() + b'\0')
        h.update(b'displays\0' + ','.join(sorted(f'{r[1]}={dict(x.split("=", 1) for x in r[2:] if "=" in x).get(name, "")}' for r in g.values('build') if len(r) > 1
                                                     and dict(x.split('=', 1) for x in r[2:] if '=' in x).get(name) not in (None, bid))).encode() + b'\0')
    if name == 'main': h.update(b'fps_decouple\0' + repr(sorted(g.values('fps_decouple'))).encode() + b'\0')
    return h.hexdigest()


def _record_path(name):
    d = os.environ.get('PN_REUSE_DIR', '')
    return Path(d) / 'rewrite' / f'{name}.tsv' if d else None


def _sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()


def _read(rp):
    """A record → ({field: value}, {file: SHA-256} of its copy); ({}, {}) when it cannot be read"""
    try: lines = rp.read_text().splitlines()
    except OSError: return {}, {}
    rec, files = {}, {}
    for l in lines:
        c = l.split('\t')
        if c[0] == 'file' and len(c) == 3: files[c[1]] = c[2]
        elif len(c) == 2: rec[c[0]] = c[1]
    return rec, files


def _files(name, out, registered=False):
    """The module's own files in the pack (<module>.*, as patch_nso.py names them, less its key, which is written last) and the shared one. Never a cross-module
    table (<module>.xmod.tsv: cross_module writes it after the rewrites, for a build with code mods, whose modules are never recorded), nor for a registered
    build a match table (patch_nso.py writes none for it: one in the pack is an earlier build's). A file a rewrite does not make must not reach another pack
    (found in review)"""
    skip = {f'{name}.android.key', f'{name}.xmod.tsv'} | ({f'{name}.match.tsv'} if registered else set())
    own = [f for f in Path(out).iterdir() if f.is_file() and f.name.startswith(name + '.') and f.name not in skip and not f.name.endswith('.tmp')]
    return sorted(own + ([Path(out) / SHARED] if (Path(out) / SHARED).is_file() else []))


def _registered(src_bytes, g):
    """Whether a patch file (or the game profile's own) registers the module's build (its CUTS): patch_nso.py then writes no <module>.match.tsv"""
    bid, own = build_id(src_bytes), Path(g.dir) / 'patches.py'
    try: return bid is not None and any(bid in tables(p)['CUTS'] for p in patch_files() + ([own] if own.is_file() else []))
    except Exception: return False


def _copy(rp, name, out, before, registered=False):
    """The module's files into <record>.files/ (by a .tmp folder, then a rename) → {file: SHA-256}; the copy there when it holds these files already; {} for none"""
    keep = rp.with_suffix('.files')
    files = {f.name: _sha(f) for f in _files(name, out, registered)}
    if f'{name}.patched.bin' not in files: return {}
    if files == before and keep.is_dir() and all((keep / f).is_file() and (keep / f).stat().st_size == (Path(out) / f).stat().st_size for f in files): return files
    shutil.rmtree(keep, ignore_errors=True)
    size = sum((Path(out) / f).stat().st_size for f in files)
    if shutil.disk_usage(rp.parent).free - size < KEEP_FREE_MB << 20:
        print(f'  {name}: no copy of its rewrite kept ({size >> 20} MB): less than {KEEP_FREE_MB} MB would be free', flush=True); return {}
    tmp = rp.with_suffix('.files.tmp'); shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir()
    try:
        for f in files: shutil.copyfile(Path(out) / f, tmp / f)
        os.replace(tmp, keep)
    except OSError as e:
        shutil.rmtree(tmp, ignore_errors=True); print(f'  {name}: no copy of its rewrite kept ({e})', flush=True); return {}
    return files


def record(name, src, out, g, rewriter_files):
    """After a rewrite, or a module kept with its current key: what it was made from, for the next build, and a copy of its files (restore)"""
    rp = _record_path(name)
    kf, pb = Path(out) / f'{name}.android.key', Path(out) / f'{name}.patched.bin'
    if rp is None or not kf.is_file() or not pb.is_file(): return
    try:
        b = src.read_bytes()
        pt = part(name, b, g, rewriter_files)
        if pt is None: return
        rp.parent.mkdir(parents=True, exist_ok=True)
        files = _copy(rp, name, out, _read(rp)[1], _registered(b, g))
        tmp = rp.with_suffix('.tmp')
        tmp.write_text(f'format\t{FORMAT}\nkey\t{kf.read_text().strip()}\npart\t{pt}\nbin\t{_sha(pb)}\n' + ''.join(f'file\t{f}\t{h}\n' for f, h in sorted(files.items())))
        os.replace(tmp, rp)
    except Exception as e: print(f'  {name}: no record of its rewrite ({e}): the next build rewrites it when its key moves', flush=True)


def restore(name, src, out, g, key, rewriter_files):
    """True when the pack has no rewrite of the module and the copy its record keeps is the rewrite the current inputs make (its part the recorded one, every
    file the recorded one): the files go into the pack, the shared one only where there is none, and the current key last. Else False, and the module is rewritten
    (a copy cut short leaves files without a key, which the rewrite replaces)"""
    if os.environ.get('PN_REUSE') == '0': return False
    rp = _record_path(name)
    if rp is None or (Path(out) / f'{name}.patched.bin').exists() or (Path(out) / f'{name}.android.key').exists(): return False
    keep = rp.with_suffix('.files')
    try:
        rec, files = _read(rp)
        if rec.get('format') != FORMAT or f'{name}.patched.bin' not in files or files[f'{name}.patched.bin'] != rec.get('bin'): return False
        pt = part(name, src.read_bytes(), g, rewriter_files)
        if pt is None or pt != rec.get('part'): return False
        if any(_sha(keep / f) != h for f, h in files.items()): return False
        for f in files:
            if f == SHARED and (Path(out) / f).is_file(): continue
            tmp = Path(out) / (f + '.tmp'); shutil.copyfile(keep / f, tmp); os.replace(tmp, Path(out) / f)
    except Exception: return False
    tmp = Path(out) / f'{name}.android.key.tmp'
    with open(tmp, 'w', newline='\n') as f: f.write(key + '\n')
    os.replace(tmp, Path(out) / f'{name}.android.key')
    print(f'  {name}: kept from the app\'s copy of its last rewrite (the pack had none; its build and its part of the profile are unchanged)', flush=True)
    return True


def keep(name, src, out, g, key, rewriter_files):
    """True when the pack's module (whose key file is not the current key) is the rewrite the current inputs make: its current key is then written. Else False"""
    if os.environ.get('PN_REUSE') == '0': return False   # env.txt PN_REUSE=0: off, as for the shaders (records are still written)
    rp = _record_path(name)
    kf, pb = Path(out) / f'{name}.android.key', Path(out) / f'{name}.patched.bin'
    if rp is None or not rp.is_file() or not kf.is_file() or not pb.is_file(): return False
    try:
        rec = _read(rp)[0]
        if rec.get('format') != FORMAT or rec.get('key') != kf.read_text().strip(): return False
        pt = part(name, src.read_bytes(), g, rewriter_files)
        if pt is None or pt != rec.get('part') or _sha(pb) != rec.get('bin'): return False
    except Exception: return False
    tmp = Path(out) / f'{name}.android.key.tmp'
    with open(tmp, 'w', newline='\n') as f: f.write(key + '\n')
    os.replace(tmp, kf)
    print(f'  {name}: kept, its build and its part of the profile are unchanged (the key moved with other builds\' rows)', flush=True)
    return True
