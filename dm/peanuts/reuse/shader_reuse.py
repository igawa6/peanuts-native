#!/usr/bin/env python3
# PEANUTS (issue #120, peanuts/UPSTREAM-EDITS.md Round 26): the phone's shaders step keeps every shader of the pack's older library whose translation would come
# out the same, and translates only the others. Run in place of dm/gfx/tools/vk_android_build.py with the same arguments and environment; it runs that tool in
# this process (as scripts/pack_android.py run_in_process runs the rewriter) and changes none of its files, so DMSH_VK_CHAIN does not move.
#
# Why: upstream's delta update (android/SHADERS.md#delta-updates) keeps an older library's entry only when the profile's manifest (vk_delta.tsv, from a full Mac
# build) has its hash. No Switch 1 profile has one, so every Build after an update, an added DLC or an Import again translated every shader again.
#
# Which entries are kept (each a library entry of the pack's shaders/, DMSH_VK_DELTA_FROM, of this DMSH_VK_CHAIN and this game):
#   same inputs  the older library was built from the same profile inputs (dmsh_chain.inputs) and the profile has its own pso_pairs.tsv: a shader's result is
#                then a function of the tools, the inputs and its key alone (vk_android_build.py result_file keys its result cache on exactly these), so the entry is
#                the one a translation makes now. Without the profile's own pso_pairs.tsv, load_pairs takes the split's copy, which the inputs digest does not
#                cover (an added DLC can add pairs, which pad a vertex shader's outputs): then only "same deps" keeps, whose digest has the pairs as taken.
#   same deps    the profile changed (an APK that adds a game version adds rows to the shader tables, which moves the inputs digest of every shader), but this
#                shader's own part of it did not: its stage, its shader_facts row, its pairs, its dual_want / bf_want membership, the geo_mesh and conflict rows
#                that name it, the facts of the mesh programs drawn with it, the profile's nvn_* / shader_* rows, and for a vertex shader the same of each of its
#                pixel shaders (dmsh_build.py shader_key and pair_keyed take the same parts for the Mac's per-shader cache). The digest of each shader is
#                recorded when a library is made (<PN_REUSE_DIR>/shaders/<library id>.tsv), so the next build compares the old digest with the new one.
# Both need the library's record: it names the translator's source tree (xlate_tree.py: not in DMSH_CHAIN, so an app update can bring another translator
# library with the same version), the translation options (XLATE_DEBUG / XLATE_REDUCED, translation_options) and each entry's hash (library_id covers the
# library up to its blob). A library without one, or of another tree or other options, keeps nothing.
# Either way only a shader this build makes (wanted_keys: vk_android_build.py's own selection, less the absences validated against the split), and a vertex
# shader only when its paired pixel shaders that this build makes are those the older library holds: its outputs are padded with theirs (PS_INPUTS), so an
# add-on installed since (its pixel shaders absent before) changes it while its part of the profile stays the same (same_pixel_shaders).
# The kept keys go into a manifest of this build's version, game and inputs whose hashes are the older library's own entries, in a copy of the profile folder
# (<PN_REUSE_DIR>/profile/<title ID>, DM_GAME pointed at it): vk_android_build.py's delta path then keeps them byte for byte and translates the rest.
#
# The check: a sample of the keys that could be kept (2 %, at least 16, at most 1,000, picked by a hash of the key, and at least 4 of each class: a stage, and a
# vertex shader with pixel shaders that pad it or one without) is left out of the manifest, so it is translated; when the library is written, each sample entry must be the older library's byte for byte (vk_android_delta.py entry_hash). One that differs, or
# one the new library lacks or has unreadable, means the rule did not hold for this game: the kept results are deleted and the tool runs again with the
# profile as it is, which translates them (a full build; the shaders translated already are kept by its own result cache). A library that cannot be read, an
# entry that cannot be read, a record that is not this library's: that part is translated. Nothing here can make the step pass with a shader the tool did not
# make or keep by its own rules.
#
# The older libraries: the pack's (DMSH_VK_DELTA_FROM), and a copy of the last library this title's shaders step wrote, kept in the app's own storage
# (<PN_REUSE_DIR>/library/v-<V>/, keep_library): it is there when the pack's is not (the build deleted, another Build folder, a build that stopped after the
# pack's library went), at most KEEP_TOTAL_MB for every title together, the titles built longest ago going first.
#
# Environment: PN_REUSE_DIR (the app's no_backup/reuse/<title ID>; unset: no record or copy is kept, and nothing is kept from an older library), PN_REUSE=0 (env.txt: off, the
# tool runs as before), PN_REUSE_KEEP_MB (the copies' size for every title together). The profile's own manifest, when it is of this build, wins: the step is
# then upstream's delta update as it was.
import collections
import hashlib
import json
import os
import re
import runpy
import shutil
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
BUILD = TOOLS / 'vk_android_build.py'
FORMAT = 'pn-shader-deps 3'   # 2: the translator's source tree (translator_tree) and each entry's hash; 3: the translation options (translation_options)
RULES = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()[:32]   # this file, the keep rules: a record (and each shader's part) another file wrote is not
# trusted, as split_reuse.py's key covers its own source (found in review: a later app with other rules would have kept by what the earlier rules wrote)
SAMPLE_SHARE, SAMPLE_MIN, SAMPLE_MAX = 50, 16, 1000   # one key in SAMPLE_SHARE, within these bounds
SAMPLE_CLASS_MIN = 4   # and at least this many of each class of shader (sample_class), so a rule that misses one class is checked on it too
KEY = re.compile(r'[0-9a-f]{16}')
KEEP_DIR = 'library'   # <PN_REUSE_DIR>/library/v-<V>/dmsh_vk.lib: the copy of the last library of this title (keep_library)
RECORDS_DIR = 'shaders'   # <PN_REUSE_DIR>/shaders/<library id>.tsv: the records (record_path), without which nothing is kept from a library
SPLIT_DIR = 'split'    # <PN_REUSE_DIR>/split/: the split's kept results (split_reuse.py), the first to go when a copy needs room (room)
KEEP_TOTAL_MB = 4096   # every title's copies together (the library's, the modules', the split's results), at most (PN_REUSE_KEEP_MB)
KEEP_FREE_MB = 2048    # and no copy that would leave less than this free


def say(text): print(text, flush=True)


def vk_version():
    """DMSH_VK_CHAIN of this tree (dm/gfx/nvn/dmsh_chain_vk.h, which vk_android_build.py refuses to run without)"""
    m = re.search(r'#define DMSH_VK_CHAIN "([0-9a-f]+)"', (REPO / 'dm/gfx/nvn/dmsh_chain_vk.h').read_text())
    return m.group(1) if m else ''


_TREE = []


def translator_tree():
    """The translator library's source tree digest (dm/gfx/tools/xlate_tree.py), None when it cannot be told. Not in DMSH_CHAIN (the lock fingerprints the Mac's
    dlls): an app update can bring a translator library built from other sources with the same version, so a record keeps only for the tree it names. It is
    the one vk_android_build.py checks the library against (check_translator), so a shaders step that ran had a library of this tree"""
    if not _TREE:
        try:
            sys.path.insert(0, str(TOOLS))
            import xlate_tree
            _TREE.append(xlate_tree.sha())
        except Exception as e:   # (the tools without the translator's sources)
            say(f'reuse: the translator\'s source tree cannot be told ({e}): nothing kept')
            _TREE.append(None)
    return _TREE[0]


def load_modules():
    sys.path.insert(0, str(TOOLS)); sys.path.insert(0, str(REPO / 'dm/games'))
    import game, dmsh_chain, vk_android_delta   # noqa: E401
    return game, dmsh_chain, vk_android_delta


def translation_options():
    """The translator's options of this run (XLATE_DEBUG / XLATE_REDUCED: dmsh_chain.py options, as dmsh_build.py job_for puts them in each shader's job), which
    a record must name (read_record, plan). They are in DMSH_CHAIN's version too, so vk_android_build.py refuses others than its header was written with; the
    record says it itself (igawa6's review of #150: the options were in no key here)"""
    return load_modules()[1].options()


def zstd_module():
    try: from compression import zstd   # Python 3.14 (the phone's)
    except ImportError:
        try: from backports import zstd   # pyright: ignore[reportMissingImports]
        except ImportError: zstd = None
    return zstd


_BORROWED = {}


def borrowed(*names):
    """Functions of dmsh_build.py by name, taken the way vk_android_build.py borrows them, so the pairs are the ones the build reads"""
    if names in _BORROWED: return _BORROWED[names]
    import ast
    src = (TOOLS / 'dmsh_build.py').read_text()
    ns = {'os': os, 'collections': collections}
    exec(compile(ast.Module(body=[n for n in ast.parse(src).body if isinstance(n, ast.FunctionDef) and n.name in names], type_ignores=[]), 'dmsh_build.py', 'exec'), ns)
    _BORROWED[names] = out = [ns[n] for n in names]
    return out


def rows(path):
    """A table's rows: no blank line, no comment"""
    try: text = Path(path).read_text(encoding='utf-8')
    except FileNotFoundError: return []
    return [l for l in text.split('\n') if l.strip() and not l.lstrip().startswith('#')]


class Deps:
    """Each shader's part of the profile (see the head of this file) → its digest. g: the game profile, pkg: the split's package folder (the pairs tables'
    fallback, as load_pairs takes it), version: DMSH_VK_CHAIN"""

    def __init__(self, g, pkg, version, profile_rows):
        self.stage = {}
        for l in rows(g.table('shader_map.tsv')):
            c = l.split('\t')
            if len(c) > 4 and c[0].endswith('.bin'): self.stage.setdefault(c[3], c[0][:2])   # the package's name gives the stage, as in vk_android_build.py
        self.facts = {}
        for l in rows(g.table('shader_facts.tsv')):
            k, v = l.split('\t', 1)
            self.facts[k] = json.loads(v)
        load_pairs, = borrowed('load_pairs')
        self.pairs = {k: sorted(v) for k, v in load_pairs(g, pkg).items()}
        self.vs_of = collections.defaultdict(set)
        for v, ps in self.pairs.items():
            for p in ps: self.vs_of[p].add(v)
        self.mesh_of = collections.defaultdict(set)   # the mesh programs a pixel shader is drawn with (their facts' "pair"): MESH_PRIM in vk_android_build.py
        for k, f in self.facts.items():
            for p in f.get('pair', ()) if isinstance(f, dict) else ():
                self.mesh_of[p].add(k)
        self.dual = {l.split()[0] for l in rows(g.table('dual_want.tsv'))}
        self.bf = {l.strip() for l in rows(g.table('bf_want.tsv'))}
        self.named = collections.defaultdict(list)   # rows of the tables read by key whose layout this file does not model: every row that names the key
        glob = hashlib.sha256()
        for t in ('geo_mesh.tsv', 'shader_map.tsv.conflicts.tsv'):
            for l in rows(g.table(t)):
                ks = set(KEY.findall(l))
                for k in ks: self.named[k].append(f'{t}\0{l}')
                if not ks: glob.update(f'{t}\0{l}\n'.encode())   # a row that names no key counts for every shader
        self.common = hashlib.sha256(b'%s\0%s\0%s\0profile\0%s\0rows\0%s' % (FORMAT.encode(), RULES.encode(), version.encode(), profile_rows,
                                                                          glob.hexdigest().encode())).hexdigest()
        self._plain, self._dep = {}, {}

    def fact_row(self, k):
        f = self.facts.get(k)
        return 'missing' if f is None else json.dumps(f, sort_keys=True, separators=(',', ':'), ensure_ascii=False)

    def plain(self, k):
        d = self._plain.get(k)
        if d is None:
            h = hashlib.sha256()
            for part in (self.common, k, self.stage.get(k, 'missing'), self.fact_row(k), ','.join(self.pairs.get(k, ())), '1' if k in self.dual else '0',
                         '1' if k in self.bf else '0', '\n'.join(sorted(self.named.get(k, ()))), ','.join(sorted(self.vs_of.get(k, ()))),
                         '\n'.join(f'{m}\t{self.fact_row(m)}' for m in sorted(self.mesh_of.get(k, ())))):
                h.update(part.encode() + b'\0')
            d = self._plain[k] = h.hexdigest()[:32]
        return d

    def digest(self, k):
        """A shader's digest; a vertex shader's takes its pixel shaders' (their inputs pad its outputs), as dmsh_build.py pair_keyed"""
        d = self._dep.get(k)
        if d is None:
            d = self.plain(k)
            if self.pairs.get(k): d = hashlib.sha256((d + '\0' + ','.join(self.plain(p) for p in self.pairs[k])).encode()).hexdigest()[:32]
            self._dep[k] = d
        return d


def library_id(L):
    """A library's name for its record: the SHA-256 of everything before its blob (header, entries, bindings, module records) and its size. Another build of the
    library has other module offsets or sizes; a library that is the same up to its blob holds the same entries, or hash_of refuses them"""
    h = hashlib.sha256(bytes(L.m[:L.blob_off]))
    h.update(str(L.size).encode())
    return h.hexdigest()[:32]


def record_path(d, lib_id): return Path(d) / RECORDS_DIR / f'{lib_id}.tsv'


UNCHECKED = '.unchecked'   # <lib>.unchecked beside a library: the ids (library_id, a line each) of libraries written at that path with kept shaders whose full check has not
# passed. vk_android_upgrade.py refuses to carry a library its marker names into a later app version, as the app does (ShaderUpgrade.candidates; critique B1). Written when a
# build writes such a library (reuse), a line taken out only when the full check passes or replaces it (full_check): a check that is dropped, replaced by another build's
# (note_check) or never runs leaves it. The app copies it beside the pack's library with the pack step (PackService) and writes one when it drops a check (CheckDue)


def marker_ids(lib):
    """The library ids the marker beside lib names (its lines of 32 hex characters; CheckDue.ids, vk_android_upgrade.py unchecked read it the same)"""
    try: text = Path(str(lib) + UNCHECKED).read_text(encoding='utf-8', errors='replace')
    except OSError: return []
    out = []
    for l in text.splitlines():
        if re.fullmatch(r'[0-9a-f]{32}', l.strip()) and l.strip() not in out: out.append(l.strip())
    return out


def mark_unchecked(lib, lib_id):
    """lib_id into the marker beside lib (written whole: a temporary file, then a rename)"""
    ids = marker_ids(lib)
    if lib_id in ids: return
    p = Path(str(lib) + UNCHECKED); tmp = Path(str(p) + '.tmp')
    tmp.write_text(''.join(i + '\n' for i in ids + [lib_id]), encoding='utf-8'); os.replace(tmp, p)


def clear_unchecked(lib, lib_id):
    """lib_id out of the marker beside lib; the marker goes when it names no id left (one that never named lib's id, or no id at all, is left as it is)"""
    p = Path(str(lib) + UNCHECKED)
    if not p.exists(): return
    ids = marker_ids(lib)
    rest = [i for i in ids if i != lib_id]
    if rest == ids: return
    if not rest: p.unlink(missing_ok=True); return
    tmp = Path(str(p) + '.tmp'); tmp.write_text(''.join(i + '\n' for i in rest), encoding='utf-8'); os.replace(tmp, p)


def read_record(d, lib_id):
    """{'version', 'game', 'inputs', 'tree', 'options', 'deps': {key: (digest, entry hash)}} of a library's record, None when it has none or it does not read as one"""
    if not d: return None
    try: text = record_path(d, lib_id).read_text()
    except OSError: return None
    out = {'deps': {}}
    for l in text.split('\n'):
        c = l.split('\t')
        if l.startswith('#'): continue
        if len(c) == 2 and c[0] in ('format', 'rules', 'version', 'game', 'inputs', 'tree', 'options'): out[c[0]] = c[1]
        elif len(c) == 3 and KEY.fullmatch(c[0]) and re.fullmatch(r'[0-9a-f]{32}', c[1]) and re.fullmatch(r'[0-9a-f]{16}', c[2]): out['deps'][c[0]] = (c[1], c[2])
    return out if out.get('format') == FORMAT and out.get('rules') == RULES and all(x in out for x in ('version', 'game', 'inputs', 'tree', 'options')) else None


def write_record(d, lib_id, version, game, inputs, tree, deps):
    """deps: {key: (its digest, its entry's hash)}; the options are this run's (translation_options), which made the library"""
    p = record_path(d, lib_id); p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix('.tmp')
    tmp.write_text(f'# what each shader of a library was built from (dm/peanuts/reuse/shader_reuse.py): the next build keeps the shaders whose part is the same\n'
                   f'format\t{FORMAT}\nrules\t{RULES}\nversion\t{version}\ngame\t{game}\ninputs\t{inputs}\ntree\t{tree}\noptions\t{translation_options()}\n'
                   + ''.join(f'{k}\t{deps[k][0]}\t{deps[k][1]}\n' for k in sorted(deps)))
    os.replace(tmp, p)


def entry_hashes(lib_dir, L, zstd):
    """{key: entry hash} of every entry of the library L in lib_dir: from the manifest vk_android_build.py wrote beside it when it is of this library (its
    version, game and inputs, and every key of L with a hash), else each entry hashed here"""
    delta = load_modules()[2]
    try:
        m = delta.parse_manifest(Path(lib_dir, delta.MANIFEST).read_text(encoding='utf-8'))
        hs = {k: h for k, h in m['hashes'].items() if h != '-'}
        if (m['version'], m['game'], m['inputs']) == (L.version, L.game, L.inputs) and set(hs) == set(L.index): return hs
    except (OSError, ValueError, KeyError, TypeError): pass
    return {k: L.hash_of(i, zstd)[1] for k, i in L.index.items()}


def record_of(L, lib_dir, deps, zstd):
    """The record's rows of a library just written: each shader the profile maps, its digest and its entry's hash"""
    hs = entry_hashes(lib_dir, L, zstd)
    return {k: (deps.digest(k), hs[k]) for k in L.index if k in deps.stage and k in hs}


def sample_class(deps):
    """A shader's class for the sample: its stage, and for a vertex shader whether pixel shaders pad its outputs"""
    return lambda k: (deps.stage.get(k), bool(deps.pairs.get(k)))


def sample_of(keys, cls=None):
    """The keys the check translates: one in SAMPLE_SHARE by a hash of the key (the same keys on every run of the same build), within SAMPLE_MIN..SAMPLE_MAX;
    with cls (sample_class), topped up to SAMPLE_CLASS_MIN of each class (all of a smaller class) in the same order"""
    order = sorted(keys, key=lambda k: hashlib.sha256(b'pn-sample\0' + k.encode()).digest())
    n = min(len(order), max(SAMPLE_MIN, min(SAMPLE_MAX, -(-len(order) // SAMPLE_SHARE))))
    out = set(order[:n])
    if cls is not None:
        classes = collections.defaultdict(list)
        for k in order: classes[cls(k)].append(k)
        for ks in classes.values():
            short = min(len(ks), SAMPLE_CLASS_MIN) - sum(1 for k in ks if k in out)
            for k in ks:
                if short <= 0: break
                if k not in out: out.add(k); short -= 1
    return out


def same_pixel_shaders(L, k, deps, wanted):
    """A vertex shader's outputs are padded with the inputs of its paired pixel shaders that its build makes (vk_android_build.py PS_INPUTS): the older library
    holds those its build made. The same set, or no pairs: True. A pixel shader that was absent then (DMSH_VK_ABSENT_FILE: an add-on not installed) and is
    built now, or the other way round, changes the vertex shader's translation while its own part of the profile stays the same"""
    ps = deps.pairs.get(k)
    if not ps: return True
    return {p for p in ps if p in L.index} == {p for p in ps if wanted is None or p in wanted}


def plan(libs, version, game_id, inputs, deps, reuse_dir, zstd, wanted=None, pairs_in_inputs=True):
    """→ (kept {key: entry hash} for the manifest, sample {key: (older library's version, its entry hash)}, [lines for the log]). libs: delta.Library objects,
    newest first; deps: Deps of this build; wanted: the keys this build makes, None for all the profile maps; pairs_in_inputs: the profile has its own
    pso_pairs.tsv (else load_pairs takes the split's, which the inputs digest does not cover, and "same inputs" does not hold)"""
    kept, sample, lines, taken = {}, {}, [], set()
    tree, options = translator_tree(), translation_options()
    for L in libs:
        if L.version != version or L.game != game_id:
            lines.append(f'reuse: {L.path} is of version {L.version}, game {L.game}: not this build\'s'); continue
        if tree is None: continue
        same = L.inputs == inputs and pairs_in_inputs
        rec = read_record(reuse_dir, library_id(L))
        # a record of this app's: without one the translator a library was made with cannot be told (the translator's sources are not in its version)
        if rec is None or (rec['version'], rec['game'], rec['tree'], rec['options']) != (version, game_id, tree, options):
            lines.append(f'reuse: {L.path} has no record of the translator and the parts of the profile its shaders were made with'
                         + (f' (its record names the translator {rec["tree"]}, this build\'s is {tree})' if rec and rec['tree'] != tree else '')
                         + (f' (its record names the translation options {rec["options"]}, this build\'s are {options})' if rec and rec['options'] != options else '')
                         + ': none kept from it')
            continue
        cand = [k for k in L.index if k not in taken and k in deps.stage and (wanted is None or k in wanted) and k in rec['deps']
                and (same or rec['deps'][k][0] == deps.digest(k)) and same_pixel_shaders(L, k, deps, wanted)]
        hashes, bad = {}, 0
        for k in cand:
            try: _, h, _ = L.hash_of(L.index[k], zstd)
            except Exception: bad += 1; continue   # an entry that cannot be read (damaged, no zstd here) is translated
            if h != rec['deps'][k][1]: continue   # not the entry the record names (another library with the same tables: library_id)
            hashes[k] = h
        if len(hashes) < SAMPLE_MIN * 2:
            lines.append(f'reuse: {L.path}: {len(hashes)} shaders could be kept, too few to check: none kept'); continue
        s = sample_of(hashes, sample_class(deps))
        for k, h in hashes.items():
            if k in s: sample[k] = (L.version, h)
            else: kept[k] = h
        taken |= set(hashes)
        lines.append(f'reuse: {L.path}: {len(hashes) - len(s)} shaders kept ({"the same profile inputs" if same else "their own part of the profile unchanged"}), '
                     f'{len(s)} translated to check them' + (f', {bad} entries unreadable' if bad else ''))
    return kept, sample, lines


def overlay(g, reuse_dir, manifest_text):
    """A copy of the profile folder's files with the manifest in place of its own; → its path"""
    root = Path(reuse_dir) / 'profile'
    dst, tmp = root / g.id, root / (g.id + '.tmp')
    shutil.rmtree(tmp, ignore_errors=True); tmp.mkdir(parents=True)
    for f in Path(g.dir).iterdir():
        if f.is_file() and f.name != 'vk_delta.tsv': shutil.copyfile(f, tmp / f.name)
    (tmp / 'vk_delta.tsv').write_text(manifest_text)
    shutil.rmtree(dst, ignore_errors=True); os.replace(tmp, dst)
    return dst


def run_build(argv, env):
    """vk_android_build.py with argv in this process, env added to the environment; everything put back after it (scripts/pack_android.py run_in_process). → exit code"""
    saved = (sys.argv, list(sys.path), dict(os.environ), set(sys.modules), os.getcwd())
    code = 0
    try:
        os.environ.update(env); sys.argv = [str(BUILD)] + list(argv); sys.path.insert(0, str(TOOLS))
        runpy.run_path(str(BUILD), run_name='__main__')
    except SystemExit as e:
        code = 0 if e.code is None else e.code if isinstance(e.code, int) else (print(e.code, flush=True), 1)[1]
    finally:
        argv0, path, environ, mods, cwd = saved
        sys.argv, sys.path[:] = argv0, path
        os.environ.clear(); os.environ.update(environ); os.chdir(cwd)
        std = os.path.realpath(sys.base_prefix) + os.sep
        for m in set(sys.modules) - mods:   # the tool's own modules read DM_GAME when imported: the next run imports them afresh
            if not os.path.realpath(getattr(sys.modules.get(m), '__file__', None) or '').startswith(std): sys.modules.pop(m, None)
    return code


def older_libraries(delta, out_lib_dir, game_id):
    """The libraries of DMSH_VK_DELTA_FROM (as vk_android_build.py older_libraries reads it), newest first, without this build's own output"""
    files = []
    for r in [p for p in os.environ.get('DMSH_VK_DELTA_FROM', '').split(os.pathsep) if p]:
        if os.path.isfile(r): files.append(r); continue
        if os.path.isfile(os.path.join(r, 'dmsh_vk.lib')): files.append(os.path.join(r, 'dmsh_vk.lib')); continue
        try: subs = os.listdir(r)
        except OSError: continue
        files += [os.path.join(r, n, 'dmsh_vk.lib') for n in subs if n.startswith('v-') and os.path.isfile(os.path.join(r, n, 'dmsh_vk.lib'))]
    libs, ids = [], set()
    for f in sorted(set(os.path.realpath(f) for f in files), key=lambda f: -os.stat(f).st_mtime):
        if os.path.dirname(f) == os.path.realpath(out_lib_dir): continue
        try: L = delta.Library(f)
        except Exception as e: say(f'reuse: {f} skipped: {e}'); continue
        if L.game != game_id: L.close(); continue
        i = library_id(L)
        if i in ids: L.close(); continue   # the kept copy of the pack's library (keep_library): the same entries
        ids.add(i); libs.append(L)
    return libs


def dir_size(d):
    """The bytes of the files under d (0 when there is none)"""
    size = 0
    for r, _, names in os.walk(d):
        for n in names:
            try: size += os.stat(os.path.join(r, n)).st_size
            except OSError: pass
    return size


def copies(t):
    """A title's kept copies → (the time of its newest file, their size, their folders): its library's (KEEP_DIR), its modules' (rewrite_reuse.py,
    rewrite/<module>.files), the split's kept results (split_reuse.py, SPLIT_DIR) and, while it is there, the full check's own folder (CHECK_DIR: its results,
    kept between its tries, and its library; found in review, it was outside the cap)"""
    dirs = [d for d in [t / KEEP_DIR, *sorted((t / 'rewrite').glob('*.files')), t / SPLIT_DIR, t / CHECK_DIR] if d.is_dir()]
    newest, size = 0.0, 0
    for d in dirs:
        for r, _, names in os.walk(d):
            for n in names:
                try: st = os.stat(os.path.join(r, n))
                except OSError: continue
                newest, size = max(newest, st.st_mtime), size + st.st_size
    return newest, size, dirs


def cap_bytes():
    """Every title's kept copies together, at most (PN_REUSE_KEEP_MB, else KEEP_TOTAL_MB)"""
    try: return int(os.environ.get('PN_REUSE_KEEP_MB') or KEEP_TOTAL_MB) << 20
    except ValueError: return KEEP_TOTAL_MB << 20


def room(reuse_dir, size, replaced=(), free_mb=None):
    """Whether a copy of size bytes fits among the kept copies, for the title of reuse_dir and in place of its folders in replaced: every title's copies together
    at most cap_bytes(), the room under it made first by this title's split results (SPLIT_DIR: a few seconds of a scan to make again, so they never take another
    copy's place), then by the copies of the titles built longest ago; and none that would leave less than free_mb (KEEP_FREE_MB) free. Decided before anything is
    deleted, so a copy that is not made takes no other's place. The library's copy (keep_library), each module's (rewrite_reuse.py), the split's results
    (split_reuse.py, size 0 once they are written) and the full check before it writes (full_check, check_bytes) all ask here. → (None, [the folders to delete
    before it is made], [the titles whose copies go]) or (why not, [], [])"""
    me, cap = Path(reuse_dir), cap_bytes()
    free_mb = KEEP_FREE_MB if free_mb is None else free_mb
    if size > cap: return f'it is larger than the kept copies may be ({cap >> 20} MB, PN_REUSE_KEEP_MB)', [], []
    replaced = [Path(d) for d in replaced]
    _, mine, _ = copies(me)
    have = sum(dir_size(d) for d in replaced)
    split = me / SPLIT_DIR
    spare = dir_size(split) if split not in replaced else 0
    others = sorted((copies(t) + (t.name,) for t in me.parent.iterdir() if t.is_dir() and t.name != me.name), key=lambda o: o[0]) if me.parent.is_dir() else []
    others = [o for o in others if o[1]]
    total, drop, gone, freed = size + mine - have + sum(o[1] for o in others), [], [], have
    if total > cap and spare: drop.append(split); total -= spare; freed += spare
    while total > cap and others:
        o = others.pop(0); drop += o[2]; gone.append(o[3]); total -= o[1]; freed += o[1]
    if total > cap: return f'with this game\'s other copies it is more than {cap >> 20} MB (PN_REUSE_KEEP_MB)', [], []
    if shutil.disk_usage(me.parent if me.parent.is_dir() else me).free + freed - size < free_mb << 20: return f'less than {free_mb} MB would be free', [], []
    return None, drop, gone


CHECK_ENTRY_BYTES = 4096   # what the full check writes for each shader besides its modules: a result file's JSON head, and the storage a file of its own rounds up to


def check_bytes(L):
    """About what the full check of the library L (a delta.Library) writes into CHECK_DIR: a library of its own, L's size, and a result file for each shader
    (vk_android_build.py write_result: the same stored modules as L's entry, and a JSON head), so twice L and CHECK_ENTRY_BYTES for each entry. Measured
    libraries on the phone: ACNH 3.0.3's 96 MB of 9,766 shaders, Mario Kart 8 Deluxe 4.0.1's 233 MB of 29,651 (by this count about 230 and 580 MB for their checks)"""
    return 2 * os.path.getsize(L.path) + CHECK_ENTRY_BYTES * len(L.index)


def keep_library(reuse_dir, out_dir, version, moved=False):
    """A copy of the library this step wrote in <PN_REUSE_DIR>/library/v-<V>/ (the pack may be on shared storage or a card, so no link): the next build of this title
    keeps from it when the pack's library is gone. Only this version's (no build of this app keeps from another's). Under the cap of every title's copies (room):
    a copy that is not made takes no other's place. moved: out_dir is the full check's own (full_check, in CHECK_DIR: the same storage, counted under the cap
    already), and its library takes the copy's place by a rename, with no room to ask and no second copy written. → a line for the log"""
    src = os.path.join(out_dir, 'dmsh_vk.lib')
    me, root = Path(reuse_dir), Path(reuse_dir) / KEEP_DIR
    dst = root / ('v-' + version) / 'dmsh_vk.lib'
    size = os.path.getsize(src)
    drop, gone = [], []
    if not moved:
        if size > cap_bytes():
            shutil.rmtree(root, ignore_errors=True)   # nor an older copy, which would no longer be this build's
            return f'reuse: this library ({size >> 20} MB) is larger than the kept copies may be ({cap_bytes() >> 20} MB, PN_REUSE_KEEP_MB): not kept'
        why, drop, gone = room(me, size, [root])   # this title's libraries: replaced
        if why: return f'reuse: this library ({size >> 20} MB) not kept: {why}'
        for d in drop: shutil.rmtree(d, ignore_errors=True)
    shutil.rmtree(root, ignore_errors=True)   # the copies before (another version's, or the last library: this one takes its place)
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix('.tmp')
    try:
        if moved: os.replace(src, dst)
        else: shutil.copyfile(src, tmp); os.replace(tmp, dst)
    except OSError:
        shutil.rmtree(dst.parent, ignore_errors=True)   # never a cut copy (storage full after all): the next build keeps from the pack's library
        raise
    return (f'reuse: this library kept for the next build of this game ({size >> 20} MB)'
            + (f'; the kept copies of {", ".join(gone)} deleted (at most {cap_bytes() >> 20} MB together)' if gone else '')
            + ('; this game\'s kept split results deleted to make room' if me / SPLIT_DIR in drop else ''))


def wanted_keys(argv, deps, delta):
    """The keys this build makes, as vk_android_build.py takes them: the key list's (none: every key of the map) with the pixel shaders paired with them
    (dmsh_build.py build_keys), less the absences validated against the split (DMSH_VK_ABSENT_FILE, vk_android_delta.py package_keys), and of them only the ones
    the split found (its shader_map.tsv beside the package). None when that cannot be
    told (an absence file that cannot be read, an empty selection: the tool says why), and then nothing is kept"""
    absent = set()
    if f := os.environ.get('DMSH_VK_ABSENT_FILE'):
        try: absent = set(Path(f).read_text(encoding='utf-8').splitlines())
        except OSError: return None
    build_keys, = borrowed('build_keys')
    loc = dict.fromkeys(deps.stage)
    try:
        if hasattr(delta, 'package_keys'): w = set(delta.package_keys(set(argv[4:]), loc, deps.pairs, absent, build_keys))
        else: w = set(build_keys(set(argv[4:]), loc, deps.pairs))
    except ValueError: return None
    # only a shader the split found in this dump (its own shader_map.tsv): with Try anyway (DMSH_VK_REQUIRE_ALL=0) vk_android_build.py leaves out one its
    # package lacks, but a kept one never goes through that check, so an older library's (a dump with an add-on) would pad the vertex shaders and stay
    # (found in review). Without Try anyway the split holds every key the build makes, and this changes nothing
    found = Path(argv[2]).parent / 'shader_map.tsv'
    if found.is_file(): w &= {c[3] for c in (l.split('\t') for l in rows(found)) if len(c) > 4 and c[0].endswith('.bin')}
    return w


def check(new_lib, sample, zstd, delta):
    """→ ([keys whose translated entry differs], [keys not in the library just written, or whose entry there cannot be read]) for the sample against it"""
    L = delta.Library(new_lib)
    try:
        differ, missing = [], []
        for k, (_, h) in sorted(sample.items()):
            i = L.index.get(k)
            if i is None: missing.append(k); continue
            try: _, nh, _ = L.hash_of(i, zstd)
            except Exception: missing.append(k); continue
            if nh != h: differ.append(k)
        return differ, missing
    finally: L.close()


MARK = '.pn-reuse-unchecked'   # in the results folder: the keys a run kept before its check ran, and the vertex shaders it padded with them; a stop before the check leaves it


def results_dir(out_dir): return os.environ.get('DMSH_VK_RESULTS') or os.path.join(out_dir, 'results')


def drop_unchecked(res):
    """The kept results of a run that stopped before its check (MARK) are deleted: they are written again (seconds) if this run keeps them, and never used unchecked"""
    p = os.path.join(res, MARK)
    try: keys = json.loads(Path(p).read_text())
    except (OSError, ValueError): keys = None
    if keys is None and not os.path.exists(p): return
    for k in keys or []:
        if isinstance(k, str) and KEY.fullmatch(k):
            try: os.unlink(os.path.join(res, k + '.r'))
            except OSError: pass
    try: os.unlink(p)
    except OSError: pass
    say(f'reuse: {len(keys or [])} shaders kept, or padded with kept ones, by a run that did not pass its check: deleted (kept again or translated now)')


TAGS = '.pn-reuse-deps'   # in the results folder: the run's output, what it kept, and {key: the part of the profile its result was made with (result_tag)}


def result_tag(deps, wanted, k):
    """What a shader's result was made with beyond what vk_android_build.py keys it by (the tools, the profile inputs, the key): its part of the profile
    (Deps.digest: its pairs as load_pairs takes them, with the split's) and, for a vertex shader, the paired pixel shaders the build makes (PS_INPUTS)"""
    ps = deps.pairs.get(k)
    return deps.digest(k) + (':' + ','.join(p for p in ps if wanted is None or p in wanted) if ps else '')


def read_tags(res):
    """TAGS → {'out': the run's output folder, 'kept': {key: entry hash}, 'tags': {key: result_tag}}, empty parts when it is missing or does not read"""
    try: t = json.loads(Path(res, TAGS).read_text(encoding='utf-8'))
    except (OSError, ValueError): t = None
    if not isinstance(t, dict) or not isinstance(t.get('tags'), dict) or not isinstance(t.get('kept'), dict): t = {'out': None, 'kept': {}, 'tags': {}}
    return t


def drop_stale(res, deps, wanted, out, kept):
    """The results folder is the title's (PackService Outputs.results) and stays until a job is done: a job that did not finish leaves its results to the next
    job of the title, which may have other split pairs or absences (an add-on imported since), and the tool would take them (a review found this before it
    was published; Leftover in peanuts/tests/test_shader_reuse.py). Before the tool runs (kept this run's, out its output folder), deleted:
    - every result, unless this run is a Resume of the run that wrote the tags (the same output folder: the same job) with the same translator (its sources
      are not in vk_android_build.py's result key, nor in DMSH_VK_CHAIN): another job's translations are not this one's to take, the sample's least, which
      the check must translate now (TAGS missing: an earlier app's run, or one that did not go by these rules);
    - each result whose tag (result_tag) is not this build's;
    - each entry the earlier run kept that this one does not keep with the same hash (vk_android_build.py's delta path writes a kept entry as a result: taken
      as a translation, it would reach the library unchecked, and a build that kept nothing would leave no full check for it).
    Then this run's tags are written, so a run stopped after them leaves results with the right tags, and a Resume of it keeps its translations, the sample's
    too (found in review: the sample was translated again at every pause)"""
    old, tree = read_tags(res), translator_tree()
    fresh = old['out'] != out or old.get('tree') != tree or tree is None
    drop = {k for k, h in old['kept'].items() if kept.get(k) != h}
    try: names = os.listdir(res)
    except OSError: names = []
    gone = 0
    for f in names:
        k = f[:-2]
        if f.endswith('.r') and KEY.fullmatch(k) and (fresh or k not in deps.stage or old['tags'].get(k) != result_tag(deps, wanted, k) or k in drop):
            try: os.unlink(os.path.join(res, f)); gone += 1
            except OSError: pass
    if gone: say(f'reuse: {gone} results of an earlier run that this one cannot take ({"another job, or another translator" if fresh else "another part of the profile, or entries that run kept"}): deleted')
    os.makedirs(res, exist_ok=True)
    tmp = os.path.join(res, TAGS + '.tmp')
    Path(tmp).write_text(json.dumps({'out': out, 'tree': tree, 'kept': kept,
                                     'tags': {k: result_tag(deps, wanted, k) for k in deps.stage if wanted is None or k in wanted}}), encoding='utf-8')
    os.replace(tmp, os.path.join(res, TAGS))


def forget_tags(res):
    """A run that does not go by these rules (PN_REUSE=0, the profile's own manifest): the entries an earlier run kept go (no translation of this one), and
    the tags too, so the next run takes none of this run's results by tags it did not write (found in review)"""
    old = read_tags(res)
    for k in old['kept']:
        if KEY.fullmatch(k):
            try: os.unlink(os.path.join(res, k + '.r'))
            except OSError: pass
    try: os.unlink(os.path.join(res, TAGS))
    except OSError: pass


TODO = 'check.todo'   # <PN_REUSE_DIR>/check.todo: the library a build wrote with kept shaders, for the full check (full_check)
CHECKED = 'checked.tsv'   # the libraries the full check found a full translation's byte for byte
FAILED = 'check.failed'   # what the last full check found different
CHECK_DIR = 'check'   # the full check's own results (a stopped check resumes from them) and library
DIFFER = 3   # full_check's exit code when the library is not a full translation's byte for byte (PackService then has the game's shaders made again)


def note_check(reuse_dir, lib_id, version, game_id, kept, inputs, tree):
    """After a library is written: with kept shaders (checked by the sample alone), the full check is due for it; without, a full translation needs none. The
    todo names what the library was made with besides its version (the profile inputs, the translator's sources): a check with others translates what another
    build would make, which says nothing of this library"""
    p = Path(reuse_dir) / TODO
    # PEANUTS: a due check of another library that this build replaces or clears never runs: that library's kept shaders were checked by the sample alone, so
    # its record goes and no later build keeps from it (another Build folder's build in between left it trusted; found in review of #150, as drop_check)
    was = read_todo(reuse_dir)
    if was and was.get('library') and was.get('library') != lib_id: record_path(reuse_dir, was['library']).unlink(missing_ok=True)
    if not kept:
        p.unlink(missing_ok=True); shutil.rmtree(Path(reuse_dir) / CHECK_DIR, ignore_errors=True)
        return
    tmp = p.with_suffix('.tmp')
    tmp.write_text(json.dumps({'library': lib_id, 'version': version, 'game': game_id, 'inputs': inputs, 'tree': tree, 'kept': kept}))
    os.replace(tmp, p)
    shutil.rmtree(Path(reuse_dir) / CHECK_DIR, ignore_errors=True)   # an earlier library's check: this one replaces it


def read_todo(reuse_dir):
    try: t = json.loads((Path(reuse_dir) / TODO).read_text())
    except (OSError, ValueError): return None
    return t if isinstance(t, dict) and all(isinstance(t.get(k), str) for k in ('library', 'version', 'game')) else None


DROPPED = 'dropped, with the records of the kept shaders: the next build of this game translates every shader'   # what a check that will never run says


def drop_check(reuse_dir):
    """A due full check that will never run (full_check: the library to check gone, an app update since the build): its kept shaders are then checked by the
    sample alone, so every record of this title's shaders and the kept copy of its library go too, and the next build of the game keeps no shader and
    translates every one. Before, only check.todo went: the records stayed, the next build kept the same entries again, and a wrong one the sample missed
    could stay in the pack for good (found in review). PackService's CheckDue.drop does the same for a check its job cannot make and one that failed
    CheckDue.MAX_TRIES times. The records go first: a stop in between leaves the check due, and the next start drops it again"""
    d = Path(reuse_dir)
    for n in (RECORDS_DIR, KEEP_DIR): shutil.rmtree(d / n, ignore_errors=True)
    (d / TODO).unlink(missing_ok=True)
    shutil.rmtree(d / CHECK_DIR, ignore_errors=True)


def pack_library(roots, version, lib_id):
    """The pack's library file of this id among the app's DMSH_VK_DELTA_FROM folders (the kept copy is not one of them), None when the pack holds another"""
    delta = load_modules()[2]
    for r in [p for p in roots.split(os.pathsep) if p]:
        for f in (Path(r) / f'v-{version}' / 'dmsh_vk.lib', Path(r) / 'dmsh_vk.lib', Path(r)):
            if not f.is_file(): continue
            try: L = delta.Library(str(f))
            except Exception: continue
            try:
                if library_id(L) == lib_id: return f
            finally: L.close()
    return None


def full_check(argv):
    """The full check of a library a build wrote with kept shaders (check.todo): every shader translated again, as a build from nothing, into <PN_REUSE_DIR>/check/
    (its own results: a check stopped by a game that starts, the battery or heat resumes from them; DMSH_VK_DELTA_FROM pointed at nothing, so no entry is kept),
    then each entry of the library compared with that translation. All the same: checked.tsv says so and the check's files go. One that differs, or a shader one
    of them lacks: the full translation is what the game should have, so it takes the checked library's place in the pack (as the pack step copies one: a
    .partial, then a rename), and becomes the record and the kept copy; check.failed says which shaders differed, and the exit code is DIFFER. A check that can
    never run (its library gone, another app since the build) is dropped with the records of the kept shaders (drop_check).
    argv: the shaders step's (--target vulkan <pkg> <lib> [keys]); the caller runs it only while no game runs"""
    reuse_dir = os.environ.get('PN_REUSE_DIR', '')
    if not reuse_dir or len(argv) < 4 or argv[:2] != ['--target', 'vulkan']:
        say('reuse full check: no PN_REUSE_DIR or not a shaders step\'s arguments: nothing checked'); return 0
    todo = read_todo(reuse_dir)
    if todo is None: say('reuse full check: nothing to check'); return 0
    game, dmsh_chain, delta = load_modules()
    g, version, zstd = game.load(), vk_version(), zstd_module()
    if (todo['version'], todo['game']) != (version, g.id):
        say(f'reuse full check: the library to check is of version {todo["version"]}, game {todo["game"]}: not this build\'s, {DROPPED}')
        drop_check(reuse_dir); return 0
    now = (dmsh_chain.inputs(g), translator_tree())
    if (todo.get('inputs'), todo.get('tree')) != now or now[1] is None:   # an app update since the build: another profile, or another translator library
        say(f'reuse full check: the library was made from the profile inputs {todo.get("inputs")} and the translator {todo.get("tree")}, this app has {now[0]} '
            f'and {now[1]}: a translation now says nothing of it, {DROPPED}')
        drop_check(reuse_dir); return 0
    roots = os.environ.get('DMSH_VK_DELTA_FROM', '')
    os.environ['DMSH_VK_DELTA_FROM'] = os.pathsep.join(p for p in (roots, str(Path(reuse_dir) / KEEP_DIR)) if p)
    root = Path(reuse_dir) / CHECK_DIR
    new_dir = root / 'lib' / ('v-' + version)
    libs = older_libraries(delta, str(new_dir), g.id)
    target = next((L for L in libs if library_id(L) == todo['library']), None)
    for L in libs:
        if L is not target: L.close()
    if target is None:
        say(f'reuse full check: the library to check is gone (neither the pack nor the kept copy holds it): nothing checked, {DROPPED}')
        drop_check(reuse_dir); return 0
    # its own folder under the cap of the kept copies, as any copy, before it writes (what it holds already counts toward it: a stopped check goes on from its
    # results). Found in review: outside the cap and with no free space asked, a large game's check could fill the phone and fail its tries
    try:
        need = check_bytes(target)
        why, drop, gone = room(reuse_dir, need, [root])
    except BaseException:
        target.close(); raise
    if why:
        target.close()   # (before its kept copy goes)
        say(f'reuse full check: no room for its {need >> 20} MB ({why}): {DROPPED}')
        drop_check(reuse_dir); return 0
    for d in drop: shutil.rmtree(d, ignore_errors=True)
    made = ([f'the kept copies of {", ".join(gone)} deleted (at most {cap_bytes() >> 20} MB together)'] if gone else []) \
        + (['this game\'s kept split results deleted'] if Path(reuse_dir) / SPLIT_DIR in drop else [])
    if made: say(f'reuse full check: room made for its {need >> 20} MB: {"; ".join(made)}')
    try:
        say(f'reuse full check: {target.path}: every shader translated again ({todo.get("kept", "?")} of them were kept from earlier builds)')
        code = run_build(argv[:3] + [str(root / 'lib')] + argv[4:], {'DMSH_VK_RESULTS': str(root / 'results'), 'DMSH_VK_DELTA_FROM': str(root / 'none')})
        if code != 0: return code   # stopped or failed: check.todo and the results stay, the next check goes on from them
        N = delta.Library(str(new_dir / 'dmsh_vk.lib'))
        try:
            differ, missing = [], []
            for k, i in sorted(target.index.items()):
                j = N.index.get(k)
                if j is None: missing.append(k); continue
                try: same = target.hash_of(i, zstd)[1] == N.hash_of(j, zstd)[1]
                except Exception: same = False
                if not same: differ.append(k)
            extra = sorted(k for k in N.index if k not in target.index)
            n, new_id = len(target.index), library_id(N)
            new_record = record_of(N, str(new_dir), Deps(g, argv[2], version, dmsh_chain.profile_rows(g)), zstd) if differ or missing or extra else None
        finally: N.close()
    finally: target.close()
    if not (differ or missing or extra):
        with open(Path(reuse_dir) / CHECKED, 'a', encoding='utf-8', newline='\n') as f:
            f.write(f'{todo["library"]}\t{n}\t{todo.get("kept", "")}\t{time.strftime("%Y-%m-%d %H:%M:%S")}\n')
        (Path(reuse_dir) / TODO).unlink(missing_ok=True); shutil.rmtree(root, ignore_errors=True)
        lib = pack_library(roots, version, todo['library'])
        if lib is not None: clear_unchecked(lib, todo['library'])   # checked: no longer unchecked (UNCHECKED)
        say(f'reuse full check: all {n} shaders of the library are a full translation\'s byte for byte ({todo.get("kept", "?")} of them kept from earlier builds)')
        return 0
    say(f'[RED] reuse full check: {len(differ)} of {n} shaders differ from a full translation ({", ".join(differ[:3])}), {len(missing)} are not in it, {len(extra)} only in it')
    shutil.rmtree(Path(reuse_dir) / RECORDS_DIR, ignore_errors=True)   # the records that kept them are not trusted again
    # the kept copy is the checked library or one the same records made: gone before the pack's library is replaced, so a stop after that leaves none to keep
    # from (with the same sizes the new library has the old one's id, and the next check would find the pack's equal and end; a review found this)
    shutil.rmtree(Path(reuse_dir) / KEEP_DIR, ignore_errors=True)
    dst = pack_library(roots, version, todo['library'])
    if dst is not None:
        tmp = dst.with_name(dst.name + '.partial')
        try:
            shutil.copyfile(new_dir / 'dmsh_vk.lib', tmp)
            with open(tmp, 'rb+') as f: os.fsync(f.fileno())   # whole on storage before it takes the library's name (#82: shared storage can keep a rename before the data)
            os.replace(tmp, dst)
        except BaseException as e:   # storage full, or a stop (a game started): the pack keeps its library; check.todo and the check's results stay
            try: tmp.unlink()
            except OSError: pass
            if not isinstance(e, OSError): raise
            say(f'[RED] reuse full check: the full translation cannot take the library\'s place ({e}): the pack keeps the library it has; the check is still due')
            return 1
        say(f'reuse full check: the full translation is the game\'s library now ({dst})')
        clear_unchecked(dst, todo['library'])   # the checked library is gone from that path; its replacement is a full translation
    else: say('reuse full check: the pack holds another library now: it stays as it is')
    tmp = Path(reuse_dir) / (FAILED + '.tmp')   # what the launcher's notification says: written once the pack holds what it says
    tmp.write_text(json.dumps({'library': todo['library'], 'replaced': dst is not None, 'differ_count': len(differ), 'missing_count': len(missing),
                               'extra_count': len(extra), 'kept': todo.get('kept'), 'differ': differ[:50], 'missing': missing[:50], 'extra': extra[:50], 'of': n,
                               'at': time.strftime('%Y-%m-%d %H:%M:%S')}))
    os.replace(tmp, Path(reuse_dir) / FAILED)
    tree = translator_tree()
    if tree is not None: write_record(reuse_dir, new_id, version, g.id, dmsh_chain.inputs(g), tree, new_record)
    try: say(keep_library(reuse_dir, str(new_dir), version, moved=True))   # moved out of the check's folder: already under the cap, no second copy
    except OSError as e: say(f'reuse: this library not kept for the next build ({e})')
    (Path(reuse_dir) / TODO).unlink(missing_ok=True); shutil.rmtree(root, ignore_errors=True)
    return DIFFER


def main(argv):
    saved = os.environ.get('DMSH_VK_DELTA_FROM')
    try: return full_check(argv[1:]) if argv[:1] == ['--check'] else reuse(argv)
    finally:   # the app runs the tools in its own process: the next step finds the environment as the app set it
        if saved is None: os.environ.pop('DMSH_VK_DELTA_FROM', None)
        else: os.environ['DMSH_VK_DELTA_FROM'] = saved


def reuse(argv):
    if os.environ.get('PN_REUSE') == '0' or len(argv) < 4 or argv[:2] != ['--target', 'vulkan']:
        if os.environ.get('PN_REUSE') == '0':
            say('reuse: off (PN_REUSE=0)')
            if len(argv) >= 4:
                drop_unchecked(results_dir(os.path.join(argv[3], 'v-' + vk_version())))
                forget_tags(results_dir(os.path.join(argv[3], 'v-' + vk_version())))
        return run_build(argv, {})
    game, dmsh_chain, delta = load_modules()
    g = game.load()
    version, inputs, reuse_dir, zstd = vk_version(), dmsh_chain.inputs(g), os.environ.get('PN_REUSE_DIR', ''), zstd_module()
    out_dir = os.path.join(argv[3], 'v-' + version)
    res = results_dir(out_dir)
    drop_unchecked(res)
    deps = Deps(g, argv[2], version, dmsh_chain.profile_rows(g))
    own = g.table(delta.MANIFEST)
    try: m = delta.parse_manifest(own.read_text())
    except (OSError, ValueError): m = None
    kept, sample = {}, {}
    if m and (m['version'], m['game'], m['inputs']) == (version, g.id, inputs):
        say('reuse: the profile\'s own manifest is of this build: upstream\'s delta update')
        forget_tags(res)
        code = run_build(argv, {})
    else:
        if reuse_dir:   # the kept copy beside the pack's libraries, for this tool and for vk_android_build.py's delta path, which takes the kept entries from it;
            # without DMSH_VK_DELTA_FROM that tool takes the other v-<V> folders beside its output (argv[3]): those stay
            os.environ['DMSH_VK_DELTA_FROM'] = os.pathsep.join([os.environ.get('DMSH_VK_DELTA_FROM') or argv[3], str(Path(reuse_dir) / KEEP_DIR)])
        libs = older_libraries(delta, out_dir, g.id)
        wanted = wanted_keys(argv, deps, delta)
        try:
            # a shader is kept only where its full check can follow: the check asks for room beside this game's copies that stay (the kept copy of the library
            # this build writes, about the older one's size, and its modules'; room() frees only the split's results and other games' copies), and one that
            # never fits is dropped before it runs (full_check). A build that kept shaders anyway would leave them checked by the sample alone
            # (PN_REUSE_KEEP_MB=0, a library of about a third of the cap or more)
            mods = sum(dir_size(d) for d in (Path(reuse_dir) / 'rewrite').glob('*.files')) if reuse_dir else 0
            big = next((L for L in libs if (L.version, L.game) == (version, g.id) and check_bytes(L) + os.path.getsize(L.path) + mods > cap_bytes()), None)
            if wanted is None: kept, sample, lines = {}, {}, ['reuse: this build\'s keys cannot be told (its absences, its selection): none kept']
            elif big is not None:
                kept, sample, lines = {}, {}, [f'reuse: the full check of shaders kept from {big.path} would need about {check_bytes(big) >> 20} MB beside this game\'s '
                                               f'copies of {(os.path.getsize(big.path) + mods) >> 20} MB, more than the kept copies may be ({cap_bytes() >> 20} MB, '
                                               f'PN_REUSE_KEEP_MB): none kept']
            else: kept, sample, lines = plan(libs, version, g.id, inputs, deps, reuse_dir, zstd, wanted, g.table('pso_pairs.tsv').is_file())
        finally:
            for L in libs: L.close()
        for l in lines: say(l)
        prof = None
        if kept and reuse_dir:
            try:
                prof = overlay(g, reuse_dir, delta.format_manifest(version, g.id, inputs, kept))
                if dmsh_chain.inputs(game.Game(prof)) != inputs: say('[RED] reuse: the profile copy is not the profile: none kept'); prof = None
            except (OSError, ValueError) as e: say(f'[RED] reuse: cannot write the profile copy ({e}): none kept'); prof = None
        elif kept: say('reuse: no PN_REUSE_DIR for the profile copy: none kept')
        if prof is None: kept, sample = {}, {}
        drop_stale(res, deps, wanted, os.path.realpath(out_dir), kept)
        if kept:
            # unchecked with the kept shaders: each vertex shader paired with a kept pixel shader, which this run translates padded with the kept entry's inputs
            # (vk_android_build.py translate_keys reads them from it); its result is keyed without them, so a failed check or a stop before it would leave it
            # for the run after (a review found this before it was published; Padding in peanuts/tests/test_shader_reuse.py)
            unchecked = set(kept) | {v for v, ps in deps.pairs.items() if any(p in kept for p in ps)}
            tmp = os.path.join(res, MARK + '.tmp'); Path(tmp).write_text(json.dumps(sorted(unchecked))); os.replace(tmp, os.path.join(res, MARK))
        code = run_build(argv, {'DM_GAME': str(prof)} if prof else {})
        if code != 0 and kept: drop_unchecked(res)   # a failed step: its kept results are not left for a Retry that may not check them
        if code == 0 and kept:
            differ, missing = check(os.path.join(out_dir, 'dmsh_vk.lib'), sample, zstd, delta)
            # every sample entry must be there and the older library's: the sample's keys are shaders this build makes (wanted_keys: of them only the ones the
            # split found), and an older library had each of them, so one the new library lacks (its translation failed now, or the tool left it out) says the
            # build did not make what it made before. Before, up to half of the sample could be missing and the kept shaders still stood (found in review)
            if differ or missing or not sample:
                parts = ([f'{len(differ)} {"differs" if len(differ) == 1 else "differ"} from the older library ({", ".join(differ[:3])})'] if differ else []) \
                    + ([f'{len(missing)} {"is" if len(missing) == 1 else "are"} not in the library or cannot be read there ({", ".join(missing[:3])})'] if missing else [])
                why = f'of the {len(sample)} shaders translated to check, {" and ".join(parts)}' if parts else 'no shader was translated to check them'
                say(f'[RED] reuse check: {why}: the {len(kept)} kept shaders are translated again')
                drop_unchecked(res)
                if reuse_dir: shutil.rmtree(Path(reuse_dir) / RECORDS_DIR, ignore_errors=True)   # a record that led here is not trusted again
                kept = {}
                code = run_build(argv, {})
            else:
                try: os.unlink(os.path.join(res, MARK))
                except OSError: pass
                say(f'reuse check: {len(sample)} of {len(sample)} shaders translated to check are the older library\'s byte for byte; {len(kept)} shaders kept')
        if reuse_dir: shutil.rmtree(Path(reuse_dir) / 'profile', ignore_errors=True)
    if code == 0 and reuse_dir:
        try:
            L = delta.Library(os.path.join(out_dir, 'dmsh_vk.lib'))
            try:
                tree = translator_tree()
                note_check(reuse_dir, library_id(L), version, g.id, len(kept), inputs, tree)   # first: a library with kept shaders has its check due whatever comes next
                lib = os.path.join(out_dir, 'dmsh_vk.lib')   # PEANUTS: its marker, until the full check passes (UNCHECKED); a library that kept nothing is a full translation
                if kept: mark_unchecked(lib, library_id(L))
                else: clear_unchecked(lib, library_id(L))
                if tree is None: raise ValueError('the translator\'s source tree cannot be told')
                write_record(reuse_dir, library_id(L), version, g.id, inputs, tree, record_of(L, out_dir, deps, zstd))
            finally: L.close()
        except Exception as e: say(f'reuse: no record of this library\'s shaders ({e}): the next build keeps nothing from it')
        try: say(keep_library(reuse_dir, out_dir, version))
        except OSError as e: say(f'reuse: this library not kept for the next build ({e}): it keeps from the pack\'s')
    return code


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
