#!/usr/bin/env python3
# The Android shader library upgraded in place (no translator, no split, no game files): a dmsh_vk.lib of an older DMSH_VK_CHAIN rewritten into this tree's version when every
# tools change between the two is one this file can apply to the library's own entries. Each hop (HOPS) is a version pair and a function of one entry: it rewrites exactly the
# entries that change and leaves the others byte for byte. The library is then written by vk_android_build.py's own write_library (taken from that file's source by name, as
# vk_android_build.py takes dmsh_build.py's functions), with the header's version and abi of the last hop, so the file is byte-identical to a full build of the same game at
# the new version made with the same zstd level (DMSH_VK_ZSTD_LEVEL: only rewritten modules are compressed again; the others keep their stored bytes).
# This file is not one of DMSH_VK_CHAIN's files (vk_android_build.py VK_FILES): it changes no library a build makes. A commit that moves DMSH_VK_CHAIN adds its hop here, or none
# (then no path: exit 3, and the app translates as before).
# Usage: vk_android_upgrade.py --path <old dmsh_vk.lib>    the versions it would go through ("c679ddfbc5db c9337a3f4e01 68162be2976c"), reading the header only;
#                                                        exit 0, 3 when there is no complete path to this tree's DMSH_VK_CHAIN, 1 when the file cannot be read
#        vk_android_upgrade.py <old dmsh_vk.lib> <new dmsh_vk.lib>   the upgraded library to <new>.tmp, then renamed (and inputs.tsv beside it, as a build writes it);
#                                                        stdout: "stage: ...", "progress: <done>/<total>", then "upgraded <game> v-<old> -> v-<new>: <n> entries, <a> rewritten
#                                                        (<dual> dual, <samp> sampler), <s> s"; exit 0, 3 no path (nothing written), 1 error (nothing left behind)
# Refused (exit 3): a library with an unchecked marker beside it that names it (<lib>.unchecked with its library id, or with none: its kept shaders were never checked), one
#   whose shader inputs are not DM_VK_UPGRADE_INPUTS (the profile's, when given), one whose structure does not read as the replay
#   reads it (vka_shlib_check: magic, version, tables in the file, the blob ending it; every entry, binding and module in range, keys sorted, the base module first), one of
#   another DMSH_CHAIN (the translator's front half), subgroup size or storage buffer alignment than this tree's, and one whose abi is not the first hop's
# Environment: J (threads, default 8), DMSH_VK_ZSTD_LEVEL (as vk_android_build.py), DM_VK_UPGRADE_VALIDATE=1 runs spirv-val (SPIRV_VAL, a program or libSPIRV-Tools-shared,
#   default spirv-val on PATH) on every rewritten module; a module that fails is a refusal (exit 3): a full build would not hold that shader
import ast, concurrent.futures as cf, ctypes, mmap, os, re, shutil, struct, subprocess, sys, tempfile, threading, time  # PEANUTS: the in-place shader upgrade (0.1.8)
HERE = os.path.dirname(os.path.abspath(__file__)); GFX = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import dmsh_chain, vk_android_spv as spv, vk_android_delta as delta

try: from compression import zstd   # as vk_android_build.py
except ImportError:
    try: from backports import zstd   # pyright: ignore[reportMissingImports]
    except ImportError: zstd = None

VK = delta.VK
BUILD = os.path.join(HERE, 'vk_android_build.py'); HEADER_VK = os.path.join(GFX, 'nvn', 'dmsh_chain_vk.h')
UNCHECKED = '.unchecked'   # <lib>.unchecked: a library whose kept shaders were never checked (shader_reuse.py, #150)
BIND = struct.Struct('<BBHBBHhHBBhHHI')   # dmsh_vk_bind, as vk_android_build.py entry_parts packs it
assert BIND.size == VK['BIND_SIZE']


class NoPath(Exception):
    """No upgrade for this library (exit 3): the app translates as before"""


# ---- what is taken from vk_android_build.py (a script: it refuses to be imported), by name, so each stays one copy
BORROWED = ('key_int', 'type2_sampler', 'pack_module', 'write_library', 'is_lib', 'spirv_validator', 'SpirvValLib')
BORROWED_VALUES = ('ZSTD_LEVEL', 'BLOB_MAX')


def borrow():
    tree = ast.parse(open(BUILD).read(), BUILD)
    defs = {n.name: n for n in tree.body if isinstance(n, (ast.FunctionDef, ast.ClassDef)) and n.name in BORROWED}
    values = [n for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id in BORROWED_VALUES for t in n.targets)]
    missing = [n for n in BORROWED if n not in defs] + [v for v in BORROWED_VALUES if not any(t.id == v for n in values for t in n.targets if isinstance(t, ast.Name))]
    if missing: raise ImportError(f'{BUILD} no longer defines {", ".join(missing)}: vk_android_upgrade.py takes them from there')
    ns = {'os': os, 're': re, 'struct': struct, 'sys': sys, 'ctypes': ctypes, 'threading': threading, 'tempfile': tempfile, 'subprocess': subprocess,
          'zstd': zstd, 'VK': VK, 'delta': delta}
    exec(compile(ast.Module(body=values + [defs[n] for n in BORROWED], type_ignores=[]), BUILD, 'exec'), ns)
    return ns


NS = borrow()


def committed(name):
    m = re.search(r'#define ' + name + r' "([0-9a-f]+)"', open(HEADER_VK).read())
    return m.group(1) if m else None


# ---- the old library, read as the replay reads it, its stored bytes left in the map
class Ref:
    """A module's stored bytes in the old library's map, read when the new library is written"""
    __slots__ = ('m', 'off', 'n')
    def __init__(self, m, off, n): self.m, self.off, self.n = m, off, n
    def __len__(self): return self.n
    def read(self): return self.m[self.off:self.off + self.n]


def stored_bytes(s): return s.read() if isinstance(s, Ref) else s


def library_id(path):
    """shader_reuse.py library_id of a file: the SHA-256 of everything before its blob (header, tables) and the file's size, 32 hex digits; None when unreadable"""
    import hashlib
    try:
        with open(path, 'rb') as f:
            size = os.fstat(f.fileno()).st_size
            head = f.read(delta.HEAD.size)
            if len(head) < delta.HEAD.size: return None
            blob_off = delta.HEAD.unpack_from(head, 0)[12]
            if blob_off > size: return None
            f.seek(0); h = hashlib.sha256(f.read(blob_off))
    except OSError: return None
    h.update(str(size).encode())
    return h.hexdigest()[:32]


def unchecked(path):
    """Whether <lib>.unchecked says this library's kept shaders were never checked: the marker names it by its library id (a line of 32 hex digits; shader_reuse.py
    writes one per library a build wrote with kept shaders, and takes it out when the full check passes or replaces it), or names no id at all (an older marker: refused,
    whatever library it was written for). A marker of another library at this path (rebuilt since) refuses nothing"""
    try: text = open(path + UNCHECKED, encoding='utf-8', errors='replace').read()
    except FileNotFoundError: return False
    except OSError: return True
    ids = {l.strip() for l in text.splitlines() if re.fullmatch(r'[0-9a-f]{32}', l.strip())}
    return not ids or library_id(path) in ids


def open_library(path):
    """delta.Library (header and tables in the file) plus the blob ending the file; OSError when it cannot be read, NoPath when it does not read as a library"""
    if unchecked(path): raise NoPath(f'{path}{UNCHECKED} exists: its kept shaders were never checked')
    open(path, 'rb').close()   # a missing or unreadable file is a read error (exit 1), not a refusal
    try: L = delta.Library(path)
    except ValueError as e: raise NoPath(str(e)) from e
    if L.blob_off + L.blob_size != L.size: L.close(); raise NoPath(f'{path}: the blob does not end the file')
    if len(L.index) != L.ne: L.close(); raise NoPath(f'{path}: a key twice')
    return L


def entries_of(L):
    """[[key, entry bytearray, binding records, c1 bytes, [[variant, size, stored (Ref), codec]]]] in key order, every range checked (vk_android_build.py check_library)"""
    out, last = [], -1
    for i in range(L.ne):
        o = L.eo + delta.ENTRY.size * i
        e = bytearray(L.m[o:o + delta.ENTRY.size])
        key, _, stage, mc, _, m0, b0, bc = struct.unpack_from('<QQBBHIIH', e)
        c1o, c1n = delta.c1_off(e), struct.unpack_from('<I', e, 48)[0]
        if key <= last: raise NoPath(f'{L.path}: entries not sorted by key, or a key twice')
        last = key
        if m0 + mc > L.nm or b0 + bc > L.nb or stage > VK['STAGE_MS'] or c1o + c1n > L.blob_size: raise NoPath(f'{L.path}: entry {key:016x} out of range')
        mods = []
        for j in range(m0, m0 + mc):
            off, size, n, variant, codec, off_hi = delta.MOD.unpack_from(L.m, L.mo + delta.MOD.size * j)
            off = delta.mod_off(off, off_hi)
            if off + n > L.blob_size or codec not in (VK['CODEC_RAW'], VK['CODEC_ZSTD']): raise NoPath(f'{L.path}: entry {key:016x}: a module out of the blob or of codec {codec}')
            mods.append([variant, size, Ref(L.m, L.blob_off + off, n), codec])
        vs = [m[0] for m in mods]
        if not vs or vs[0] != VK['MOD_BASE'] or len(set(vs)) != len(vs): raise NoPath(f'{L.path}: entry {key:016x}: modules {vs}: the base module first, each variant once')
        out.append([f'{key:016x}', e, bytes(L.m[L.bo + VK['BIND_SIZE'] * b0:L.bo + VK['BIND_SIZE'] * (b0 + bc)]), bytes(L.m[L.blob_off + c1o:L.blob_off + c1o + c1n]), mods])
    return out


def spirv_of(mod):
    variant, size, stored, codec = mod
    try: data = delta.spirv(stored_bytes(stored), codec, zstd)
    except ValueError as e: raise NoPath(f'module {variant}: {e}') from e   # a damaged frame: the library does not read as the replay reads it
    if len(data) != size: raise NoPath(f'module {variant} is {len(data)} bytes, its record says {size}')
    return data


def packed(data):
    """[variant's size, stored, codec] of a rewritten module, compressed as a build compresses it (vk_android_build.py pack_module)"""
    stored, codec = NS['pack_module'](data)
    return len(data), stored, codec


# ---- the hops: (from version, to version) → (the library's abi before, after, the entry function). An entry function takes one entry (entries_of) and the rewritten modules'
# SPIR-V list (for DM_VK_UPGRADE_VALIDATE), rewrites it in place and returns the names of what it changed (a set, empty when the entry is as a build of the new version makes it)
def hop_dual_store(ent, rewritten):
    """c679ddfbc5db → c9337a3f4e01, PR #420 (9cd1aa55, vk_android_spv.py dual_source: output 1 written with one whole-vector store). A build makes MOD_DUAL as
    dual_source(Module(base)) of the stored base module (vk_android_build.py ps_variants copies m.data(), the base module's bytes); the old dual_source returned True for
    every shader the new one does, so the entries with a MOD_DUAL module are the only ones that change: it is made again from the base, or left out when the new
    function declines (the entry's module count with it)"""
    mods = ent[4]
    k = next((i for i, m in enumerate(mods) if m[0] == VK['MOD_DUAL']), None)
    if k is None: return set()
    d = spv.Module(spirv_of(mods[0]))
    if spv.dual_source(d):
        data = d.data(); rewritten.append((f'{ent[0]}_{VK["MOD_DUAL"]}', data))
        mods[k] = [VK['MOD_DUAL'], *packed(data)]
    else:
        del mods[k]; ent[1][17] = len(mods)   # dmsh_vk_entry.nmods
    return {'dual'}


SKIP_AT = set(range(19, 40)) | set(range(41, 53)) | {54, 59}   # types, constants, OpFunction, OpVariable: past the debug names and the decorations (SPIR-V's logical layout)


def descriptor_names(data):
    """{(set, binding): name} of a module's variables, as Module.resources gives them (the last OpName and decoration of an id; a variable without a set decoration is in
    set 0), from the debug and annotation sections only"""
    if len(data) % 4 or len(data) < 20: raise NoPath('malformed SPIR-V: not a whole module header and words')
    w = struct.unpack('<%dI' % (len(data) // 4), data)
    names, sets, binds, i = {}, {}, {}, 5
    while i < len(w):
        n, op = w[i] >> 16, w[i] & 0xffff
        if not n or i + n > len(w): raise NoPath('malformed SPIR-V')   # (an instruction past the end: a refusal, not an IndexError's exit 1)
        if op in SKIP_AT: break
        if op == spv.OP_NAME and n >= 3: names[w[i + 1]] = spv.string_at(list(w[i:i + n]), 2)[0]
        elif op == spv.OP_DECORATE and n >= 4:
            if w[i + 2] == spv.DEC_BINDING: binds[w[i + 1]] = w[i + 3]
            elif w[i + 2] == spv.DEC_SET: sets[w[i + 1]] = w[i + 3]
        i += n
    return {(sets.get(v, 0), b): names.get(v, '') for v, b in binds.items()}


COMBINED_TEX = (VK['KIND_TEX'], VK['KIND_TEX_UBO'])


def hop_type2_sampler(ent, rewritten):
    """c9337a3f4e01 → 68162be2976c (f3f0d99f): a combined image sampler whose texture is TEX / TEX_UBO and whose name is a handle-type-2 one (vk_android_build.py
    type2_sampler) has the sampler half SAMP_UBO at (uniform slot, byte offset) and the flag BF_SAMP_RAW (classify, sampler_half); the SPIR-V does not change. The name
    is the base module's for the variable at the record's set and binding (vk_android_build.py place writes them into the module before it is stored)"""
    binds = ent[2]
    recs = [BIND.unpack_from(binds, o) for o in range(0, len(binds), BIND.size)]
    cand = [j for j, r in enumerate(recs) if r[1] == spv.VK_COMBINED and r[0] in COMBINED_TEX]
    if not cand: return set()
    names = descriptor_names(spirv_of(ent[4][0]))
    out = bytearray(binds); changed = False
    for j in cand:
        kind, desc, binding, set_, flags, count, reg, off, skind, dim, sreg, soff, step, nbytes = recs[j]
        t2 = NS['type2_sampler'](names.get((set_, binding), ''))
        if not t2: continue
        BIND.pack_into(out, j * BIND.size, kind, desc, binding, set_, flags | VK['BF_SAMP_RAW'], count, reg, off, VK['KIND_SAMP_UBO'], dim, t2[0], t2[1], step, nbytes)
        changed = True
    if not changed: return set()
    ent[2] = bytes(out)
    return {'sampler'}


def has_stage(stage):
    """A hop's refusal: the library holds an entry of this stage (the entry table only, as fast as the header)"""
    def refuse(L):
        n = sum(1 for i in range(L.ne) if L.m[L.eo + delta.ENTRY.size * i + 16] == stage)
        return f'{n} entries of stage {stage}, which this hop cannot rewrite from the library alone' if n else None
    return refuse


def pure_restamp(ent, rewritten): return set()   # a hop whose libraries differ in the header only (once its refusal passed)


# The hops, in any order; a path follows them from the library's version to this tree's DMSH_VK_CHAIN. (from, to, the library's abi before, after, the entry function,
# a refusal: None, or a function of the old library → why this library cannot take the hop (exit 3), or None). A tools change that cannot be derived from a library for some
# entries but leaves the others byte for byte is a refusal for libraries holding those entries and a restamp for the rest: e.g. a change of domain shaders that reads
# their control section (which no library keeps) is (old, new, abi, abi, pure_restamp, has_stage(VK['STAGE_TES']))
HOPS = [('c679ddfbc5db', 'c9337a3f4e01', '3d4e8bb1e1e8', '3d4e8bb1e1e8', hop_dual_store, None),
        ('c9337a3f4e01', '68162be2976c', '3d4e8bb1e1e8', '288a138fa7d1', hop_type2_sampler, None),
        # bb3158fb: a domain shader's tessellation mode (primitive, spacing, vertex order) from its control section (+0x738/+0x73c/+0x744), which no library keeps (not in
        # the entry, the c1 bytes or the module: the old module holds the translator's default modes). Only domain modules change (finish, tess_modes_set), so a library
        # without a domain shader is this version's but for the header; one with any is refused
        ('68162be2976c', '327c6383b614', '288a138fa7d1', '288a138fa7d1', pure_restamp, has_stage(VK['STAGE_TES']))]


def path_of(version, abi, target=None, hops_table=None):
    """[(from, to, abi before, abi after, entry function, refusal)] from a library's version to this tree's DMSH_VK_CHAIN, or NoPath"""
    target = target or committed('DMSH_VK_CHAIN')
    if version == target: raise NoPath(f'the library is already of version {target}')
    nxt = {h[0]: h for h in (HOPS if hops_table is None else hops_table)}
    hops, v, seen = [], version, set()
    while v != target:
        if v not in nxt or v in seen: raise NoPath(f'no upgrade from version {version} to {target} (stops at {v})')
        seen.add(v); hops.append(nxt[v]); v = nxt[v][1]
    if abi != hops[0][2]: raise NoPath(f'the library\'s abi {abi or "(none)"} is not {hops[0][2]}, the one its upgrade starts from')
    return hops


def refusals(L, hops):
    why = [f'{a} -> {b}: {r}' for a, b, _, _, _, ref in hops if ref for r in [ref(L)] if r]
    if why: raise NoPath(f'{L.path}: ' + '; '.join(why))


def check_header(L, hops, partial=False):
    """The header fields a build writes from this tree, and the last hop's abi this tree's: else a full build would not give the same file"""
    why = []
    if L.chain != dmsh_chain.version(): why.append(f'chain {L.chain}, this tree\'s is {dmsh_chain.version()}')
    if (L.sg, L.sa) != (VK['SUBGROUP_SIZE'], VK['SSBO_ALIGN']): why.append(f'subgroup size {L.sg} / storage buffer alignment {L.sa}, this tree builds {VK["SUBGROUP_SIZE"]} / {VK["SSBO_ALIGN"]}')
    if not partial and hops[-1][3] not in {delta.abi_digest()} & {committed('DMSH_VK_ABI')}: why.append(f'the last hop gives abi {hops[-1][3]}, this tree\'s is {delta.abi_digest()}')
    want = os.environ.get('DM_VK_UPGRADE_INPUTS', '')   # the profile's shader inputs (the app passes Profile.shaderInputs): a library of other shader tables is not this build's
    if want and want != 'unknown' and L.inputs != want: why.append(f'shader inputs {L.inputs}, the profile\'s are {want}: a full build would differ')
    if why: raise NoPath(f'{L.path}: ' + '; '.join(why))


_plock = threading.Lock(); _at = [0.0]


def say(text, every=0.0):
    with _plock:
        now = time.monotonic()
        if every and now - _at[0] < every: return
        _at[0] = now
        print(text, flush=True)


def upgrade(old, new, nj=None, target=None):
    """old library → new library file; → (game, old version, new version, entries, {change: count}, rewritten entries, seconds). NoPath (nothing written) or another
    exception (nothing left behind). target: an earlier version on the path than this tree's (PC comparisons against an older full build; the header's abi then the hop's)"""
    t0 = time.monotonic(); nj = nj or int(os.environ.get('J', '8'))
    if os.path.abspath(old) == os.path.abspath(new): raise ValueError('the new library must be another file')
    L = open_library(old)
    try:
        hops = path_of(L.version, L.abi, target); check_header(L, hops, target is not None); refusals(L, hops)
        say('stage: upgrading the shaders')
        ents = entries_of(L); total = len(ents); done = [0]
        validate = os.environ.get('DM_VK_UPGRADE_VALIDATE') == '1'
        val = None
        if validate:
            sv = os.environ.get('SPIRV_VAL') or shutil.which('spirv-val')
            if not sv: raise OSError('DM_VK_UPGRADE_VALIDATE=1 and no spirv-val (SPIRV_VAL)')
            val = NS['spirv_validator'](sv)
        def one(ent):
            rewritten, what = [], set()
            for h in hops: what |= h[4](ent, rewritten)
            bad = [f'{tag}: {err}' for tag, data in rewritten for err in [val(data)] if err] if val else []
            with _plock: done[0] += 1; d = done[0]
            say(f'progress: {d}/{total}', 0.5)
            return what, bad
        counts, n_rewritten, bad = {'dual': 0, 'sampler': 0}, 0, []
        with cf.ThreadPoolExecutor(nj) as ex:
            for what, b in ex.map(one, ents, chunksize=64):
                for w in what: counts[w] += 1
                n_rewritten += bool(what); bad += b
        say(f'progress: {total}/{total}')
        if bad: raise NoPath(f'{len(bad)} rewritten modules fail spirv-val (a full build would not hold those shaders), e.g. {bad[0][:200]}')
        version, abi = hops[-1][1], hops[-1][3]
        write(L, ents, new, version, abi)
    finally: L.close()
    return L.game, L.version, version, total, counts, n_rewritten, time.monotonic() - t0


def write(L, ents, new, version, abi):
    """vk_android_build.py write_library with the old header's game, chain and inputs and the new version and abi: the same layout as a full build's; <new>.tmp renamed
    to <new>, nothing left behind on an error (a directory made for it removed again when empty)"""
    d = os.path.dirname(os.path.abspath(new)); made = not os.path.isdir(d)
    os.makedirs(d, exist_ok=True)
    tmp = os.path.join(d, os.path.basename(new) + '.tmp')
    class Game: id = L.game
    ns = dict(NS, VK=dict(VK, LIB_FILE=os.path.basename(new)), out=d, CHAIN=L.chain, VKV=version, GAME=Game, INPUTS=L.inputs, ABI=abi,
              ANSWERS={'subgroup_size': L.sg, 'ssbo_align': L.sa}, stored_bytes=stored_bytes,
              entry_parts=lambda key, refl, nmods: refl, stage=lambda text, every=0.0: say(f'stage: {text}', every))
    fn = type(NS['write_library'])(NS['write_library'].__code__, ns)   # the same function, its globals this library's
    written = False
    try:
        fn([(k, 'ok', '', ((bytes(e), b, c1), [(v, size, stored, codec, None) for v, size, stored, codec in mods])) for k, e, b, c1, mods in ents])
        written = True
        dmsh_chain.write_stamp(d, L.game, L.inputs)
        try: os.unlink(new + UNCHECKED)   # a marker of the library this path held before: this one is checked by construction (every entry the old one's or rewritten)
        except FileNotFoundError: pass
    except BaseException:
        for p in [tmp, os.path.join(d, dmsh_chain.STAMP + '.tmp')] + ([os.path.join(d, os.path.basename(new))] if written else []):
            if os.path.exists(p): os.unlink(p)
        if made:
            try: os.rmdir(d)
            except OSError: pass
        raise


def main(argv):
    try:
        if len(argv) == 2 and argv[0] == '--path':
            L = open_library(argv[1])
            try:
                hops = path_of(L.version, L.abi); check_header(L, hops); refusals(L, hops)
            finally: L.close()
            print(' '.join([hops[0][0]] + [h[1] for h in hops])); return 0
        if len(argv) == 2 and not argv[0].startswith('-'):
            game, a, b, n, counts, nr, secs = upgrade(argv[0], argv[1])
            print(f'upgraded {game} v-{a} -> v-{b}: {n} entries, {nr} rewritten ({counts["dual"]} dual, {counts["sampler"]} sampler), {secs:.1f} s', flush=True)
            return 0
        print('usage: vk_android_upgrade.py --path <old dmsh_vk.lib> | <old dmsh_vk.lib> <new dmsh_vk.lib>', file=sys.stderr); return 1
    except NoPath as e:
        print(f'no upgrade: {e}', flush=True); return 3
    except (Exception, SystemExit) as e:   # write_library's sys.exit lines too
        print(f'[RED] upgrade failed: {type(e).__name__}: {e}', flush=True); return 1


if __name__ == '__main__': sys.exit(main(sys.argv[1:]))
