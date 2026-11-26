#!/usr/bin/env python3
# PEANUTS: generates the generic host's runtime profile data from dm/games/game.py's own header() output - never from game.tsv directly, so this stays
# coupled only to header()'s public text contract, not to game.py's internal variable names (which churn, found in review:
# upstream added DM_GAME_* macros 56->62 across 73 commits). dm/games/game.py is upstream and must not be edited for this (PEANUTS.md rule 4).
#
# Usage: gen_runtime_profile.py profile <title id> <out.bin>   (one title's dm_game_profile_t, binary, packed - see profile_rt.h)
#        gen_runtime_profile.py profile-modules <title id> <out.bin> <mod1,mod2,...>
#                                                          (same, with the MODULES/NMOD macros replaced by the given list - a modded variant build
#                                                          whose mods add a module past game.tsv's own row, dm/tools/pack_android.py --modules; the
#                                                          generic host reads this file instead of the title's shipped asset when it sits beside the
#                                                          pack as profile.bin, dev.peanuts.build.GenericProfile.envLines(Context,String,File))
#        gen_runtime_profile.py seed <title id> <out.bin>      (the title's vk_prewarm.bin, copied verbatim; empty file when it has none)
#        gen_runtime_profile.py schema-stamp <out.c>           (the schema version this script's field tables imply, as a small .c; compiled once into the generic host)
#        gen_runtime_profile.py schema                          (prints the schema version, for scripts)
#        gen_runtime_profile.py check-all                        (every dm/games/* profile: the build guard - fails if header() emits a macro this script
#                                                                  does not know, so an upstream addition is never silently wrong; see
#                                                                  peanuts/scripts/check-runtime-profile.sh, which also builds and runs the equivalence test)
import re, struct, sys, zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent.parent.parent / 'dm' / 'games'))
import game as gamepy   # dm/games/game.py: upstream, read-only (header(), load(), all_games())

# --- Known fields -----------------------------------------------------------------------------------------------------------------------------------
# SCALAR: macro name -> (struct field, kind). kind is 'str' (quoted C string literal), 'int' (decimal or 0x…/…ull integer). These map 1:1 onto a
# dm_game_profile_t field and need no call-site edit: game_profile_generic.h defines each as "(g_profile.field)".
SCALAR = {
    'ID': ('id', 'str'), 'NAME': ('name', 'str'), 'PROGRAM_ID': ('program_id', 'int'), 'NMOD': ('nmod', 'int'),
    'ROMFS_MARKER': ('romfs_marker', 'str'), 'MEM_RESERVE': ('mem_reserve', 'int'), 'MEM_ALIAS': ('mem_alias', 'int'),
    'MEM_STACK': ('mem_stack', 'int'), 'APP_POOL': ('app_pool', 'int'), 'MEM_USED': ('mem_used', 'int'), 'SYS_RESOURCE': ('sys_resource', 'int'),
    'ARGS': ('args', 'str'), 'ENV': ('env', 'str'), 'FASTBOOT_ENV': ('fastboot_env', 'str'), 'DEVTOOLS': ('devtools', 'str'),
    'MEM_MAP_LIMIT': ('mem_map_limit', 'int'), 'STDALLOC_CAP': ('stdalloc_cap', 'int'), 'PRESENT_DISPLAY': ('present_display', 'int'),
    'CORE_PREEMPT': ('core_preempt', 'int'), 'ALLOC_BIG_ALIGN': ('alloc_big_align', 'int'), 'FPS_TARGET': ('fps_target', 'int'),
    'FPS_HOLD': ('fps_hold', 'int'), 'FPS_LIMIT': ('fps_limit', 'int'), 'UE_TEXTURE_POOL': ('ue_texture_pool', 'int'),
    'UE_FPS_CVAR': ('ue_fps_cvar', 'str'), 'PRESENT_SNAP': ('present_snap', 'int'), 'PSO_MTL4': ('pso_mtl4', 'int'),
    'ENC_THREAD': ('enc_thread', 'int'), 'PAD_LAYOUT': ('pad_layout', 'int'), 'OPMODE': ('opmode', 'int'), 'PERF_BOOST': ('perf_boost', 'int'),
    'TEX_FLUSH_UPLOAD': ('tex_flush_upload', 'int'), 'SHADER_KEY_SKIP_OFF': ('shader_key_skip_off', 'int'),
    'SHADER_KEY_SKIP_LEN': ('shader_key_skip_len', 'int'), 'RENDER_COND': ('render_cond', 'int'), 'TEX_DIRTY_UPLOAD': ('tex_dirty_upload', 'int'),
    'TEX_DYN_SLOTS': ('tex_dyn_slots', 'int'), 'TEX_SOFT': ('tex_soft', 'int'), 'GPU_TS_SEGMENT': ('gpu_ts_segment', 'int'),
    'DRAW_BARRIERS_GAME': ('draw_barriers_game', 'int'), 'VCONV_RANGE': ('vconv_range', 'int'), 'AV1_DAV1D': ('av1_dav1d', 'int'),
    'ROMFS_SUBST': ('romfs_subst', 'str'), 'NWINDOW_FN': ('nwindow_fn', 'int'), 'NSUBGROUP_ONE_LANE': ('nsubgroup_one_lane', 'int'),
    'NMESH_VEGETATION_CUT': ('nmesh_vegetation_cut', 'int'), 'NMESH_PREPASS_CUT': ('nmesh_prepass_cut', 'int'), 'NFPS_STEPS': ('nfps_steps', 'int'),
    'NDRS_CTL': ('ndrs_ctl', 'int'), 'NRES_LEVELS': ('nres_levels', 'int'),
    'EVENT_WAIT': ('event_wait', 'int'), 'OCCLUSION_SPLIT_PASS': ('occlusion_split_pass', 'int'), 'LOW_RAM_PLACE_PART_MB': ('low_ram_place_part_mb', 'int'),
    'LOW_RAM_IMG_CACHE_MAX_MB': ('low_ram_img_cache_max_mb', 'int'), 'LOW_RAM_APP_POOL_MB': ('low_ram_app_pool_mb', 'int'),
    'RENDER_DEFAULT_OUT': ('render_default_out', 'str'), 'NRENDER_CFG': ('nrender_cfg', 'int'), 'NRT_POOL': ('nrt_pool', 'int'),
    'IMAGE_RAW32': ('image_raw32', 'int'),
}
# PRESENCE: macros header() emits only when the game.tsv key is set (optional; absent means "the host decides some other way", e.g. by SDK version).
PRESENCE = {'SHARED_FONT_TTF': 'shared_font_ttf', 'NVN_API_MAJOR': 'nvn_api_major', 'NVN_API_MINOR': 'nvn_api_minor'}
# ARRAY: macros used upstream only as a `static const T x[] = DM_GAME_FOO;` initializer - the nine sites game_profile_generic.h leaves undefined
# on purpose (see its header comment). Each needs its own literal parser (the C-literal shapes are fixed by game.py, checked against game.py's own
# tests by check-all below) and a fixed-capacity encoder matching profile_rt.h.
ARRAY_NAMES = {'MODULES', 'DISPLAY_VERSIONS', 'WINDOW_FN', 'SUBGROUP_ONE_LANE', 'MESH_VEGETATION_CUT', 'MESH_PREPASS_CUT', 'FPS_STEPS', 'DRS_CTL', 'RES_LEVELS',
               'RENDER_CFG', 'RENDER_QUALITY', 'RT_POOL'}

KNOWN = set(SCALAR) | set(PRESENCE) | ARRAY_NAMES

# Found in review: the schema must cover the *layout* (field order, sizes, capacities), not only the known-macro-name set - a same-size field
# reorder or type-width change keeps every macro name the same and would otherwise pass unnoticed. LAYOUT is profile_rt.h's dm_game_profile_t field list,
# by hand, in its exact declared order, as (field name, C type) pairs: both this hash and DM_PROFILE_LAYOUT_SIZE (schema_stamp, from the real encoder
# output) feed into the schema, and profile_rt.c's _Static_assert(sizeof(dm_game_profile_t) == DM_PROFILE_LAYOUT_SIZE, ...) catches the struct drifting
# from this list even when a profile.bin built from an old or new layout happens to be the same total size.
LAYOUT = [
    ('magic', 'u32'), ('schema', 'u32'), ('id', 'char[32]'), ('name', 'char[128]'), ('romfs_marker', 'char[192]'), ('program_id', 'u64'),
    ('mem_reserve', 'u64'), ('mem_alias', 'u64'), ('mem_stack', 'u64'), ('app_pool', 'u64'), ('mem_used', 'u64'), ('sys_resource', 'u64'), ('stdalloc_cap', 'u64'),
    ('args', 'char[256]'), ('env', 'char[256]'), ('fastboot_env', 'char[256]'), ('devtools', 'char[256]'), ('romfs_subst', 'char[512]'),
    ('mem_map_limit', 'u32'), ('present_display', 'u32'), ('core_preempt', 'u32'), ('alloc_big_align', 'u32'), ('fps_target', 'u32'), ('fps_hold', 'u32'), ('fps_limit', 'u32'),
    ('ue_texture_pool', 'u32'), ('ue_fps_cvar', 'char[32]'), ('present_snap', 'u32'), ('pso_mtl4', 'u32'), ('enc_thread', 'u32'),
    ('pad_layout', 'u32'), ('opmode', 'u32'), ('perf_boost', 'u32'), ('tex_flush_upload', 'u32'), ('shader_key_skip_off', 'u64'), ('shader_key_skip_len', 'u64'),
    ('render_cond', 'u32'), ('tex_dirty_upload', 'u32'), ('tex_dyn_slots', 'u32'), ('tex_soft', 'u32'), ('gpu_ts_segment', 'u32'), ('draw_barriers_game', 'u32'),
    ('vconv_range', 'u32'), ('av1_dav1d', 'u32'), ('has_shared_font_ttf', 'u32'), ('shared_font_ttf', 'u32'), ('has_nvn_api', 'u32'), ('nvn_api_major', 'u32'), ('nvn_api_minor', 'u32'),
    ('nmod', 'u32'), ('modules', f"char[{8}][32]"), ('ndisplay_versions', 'u32'), ('display_versions', 'row[16]:char[48]+char[16]'),
    ('nwindow_fn', 'u32'), ('window_fn', 'row[8]:char[48]+u64+u64+u32+u32'), ('nsubgroup_one_lane', 'u32'), ('subgroup_one_lane', 'u64[16]'),
    ('nmesh_vegetation_cut', 'u32'), ('mesh_vegetation_cut', 'u64[128]'), ('nmesh_prepass_cut', 'u32'), ('mesh_prepass_cut', 'row[64]:u64x4'),
    ('nfps_steps', 'u32'), ('fps_steps', 'row[8]:char[48]+u64+u32+u32+u32+u64[3]'), ('ndrs_ctl', 'u32'), ('drs_ctl', 'row[8]:char[48]+u64x4'),
    ('nres_levels', 'u32'), ('res_levels', 'row[8]:char[48]+u64+u32+u32+row[12]:u64+u32'),
    ('event_wait', 'u32'), ('occlusion_split_pass', 'u32'), ('low_ram_place_part_mb', 'u32'), ('low_ram_img_cache_max_mb', 'u32'), ('low_ram_app_pool_mb', 'u32'),
    ('render_default_out', 'char[16]'), ('render_quality', 'u32[4]'),
    ('nrender_cfg', 'u32'), ('render_cfg', 'row[4]:char[48]+u64+u32x4+u32[8][4]+u32x5'),
    ('nrt_pool', 'u32'), ('rt_pool', 'row[4]:char[48]+char[16]+u64+u32x3+u32[4]x3+u32'),
    ('image_raw32', 'u32'),
]


def schema_version():
    # A hash of the known macro set (names only) plus the layout list above (names, types, orders, capacities): bumps whenever either changes, so a
    # profile.bin built by an old or differently-shaped generator is refused by this host (or vice versa) instead of being silently misread.
    text = ','.join(sorted(KNOWN)) + '|' + '|'.join(f'{n}:{t}' for n, t in LAYOUT)
    return zlib.crc32(text.encode()) & 0xffffffff


def layout_size():
    """The fixed total byte size dm_game_profile_t must have - independent of any one title's data (every field is fixed-capacity), computed by
    actually building one profile with the real Packer-based encoder, so it can never drift from what profile.bin files actually contain."""
    games = gamepy.all_games()
    if not games: sys.exit('gen_runtime_profile.py: no profiles under dm/games to size the layout from')
    return len(build_profile_bytes(games[0]))


# --- header() text parsing ---------------------------------------------------------------------------------------------------------------------------
DEFINE_RE = re.compile(r'^#define\s+DM_GAME_([A-Z0-9_]+)\s*(.*)$')


def split_top(s):   # top-level comma-separated items of "a, {b, c}, d" (no outer braces expected)
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch == '{': depth += 1
        elif ch == '}': depth -= 1
        if ch == ',' and depth == 0: out.append(cur.strip()); cur = ''
        else: cur += ch
    if cur.strip(): out.append(cur.strip())
    return out


def unbrace(s):   # "{...}" -> "...", raises if s isn't braced
    s = s.strip()
    assert s.startswith('{') and s.endswith('}'), s
    return s[1:-1]


def unq(s):   # '"a\\"b"' -> 'a"b'
    s = s.strip()
    assert s.startswith('"') and s.endswith('"'), s
    return s[1:-1].replace('\\"', '"').replace('\\\\', '\\')


def as_int(s):   # "0x10ull" / "0x10" / "10" -> int
    s = s.strip().rstrip('uUlL')
    return int(s, 0)


def parse_defines(header_text):
    """{macro name: raw RHS text}, in header() order"""
    out = {}
    for line in header_text.splitlines():
        m = DEFINE_RE.match(line)
        if m: out[m[1]] = m[2].strip()
    return out


# --- array parsers: raw RHS text -> python value ------------------------------------------------------------------------------------------------------
def parse_modules(rhs): return [unq(x) for x in split_top(unbrace(rhs))] if rhs.strip() else []


def parse_display_versions(rhs):
    # Unlike WINDOW_FN/DRS_CTL/RES_LEVELS, game.py's header() does not wrap this one in an outer brace (game.py:96 `vers`, :287): the RHS is a bare
    # top-level comma list of already-braced {build, display} rows, or empty text when the profile has no main builds.
    rhs = rhs.strip()
    if not rhs: return []
    return [tuple(unq(x) for x in split_top(unbrace(row))) for row in split_top(rhs)]


def parse_window_fn(rhs, n):
    if n == 0: return []
    rows = split_top(unbrace(rhs))
    out = []
    for row in rows[:n]:
        p = split_top(unbrace(row))
        out.append((unq(p[0]), as_int(p[1]), as_int(p[2]), as_int(p[3]), as_int(p[4])))
    return out


def parse_flat_u64(rhs, n):
    if n == 0: return []
    return [as_int(x) for x in split_top(unbrace(rhs))][:n]


def parse_mesh_prepass_cut(rhs, n):
    if n == 0: return []
    out = []
    for row in split_top(unbrace(rhs))[:n]:
        p = split_top(unbrace(row))
        out.append((as_int(p[0]), as_int(p[1]), as_int(p[2]), as_int(p[3])))
    return out


def parse_fps_steps(rhs, n):
    if n == 0: return []
    out = []
    for row in split_top(unbrace(rhs))[:n]:
        p = split_top(unbrace(row))
        offs = [as_int(x) for x in split_top(unbrace(p[5]))] if as_int(p[4]) else []
        out.append((unq(p[0]), as_int(p[1]), as_int(p[2]), as_int(p[3]), offs))
    return out


def parse_drs_ctl(rhs, n):
    if n == 0: return []
    out = []
    for row in split_top(unbrace(rhs))[:n]:
        p = split_top(unbrace(row))
        out.append((unq(p[0]), as_int(p[1]), as_int(p[2]), as_int(p[3]), as_int(p[4])))
    return out


def parse_res_levels(rhs, n):
    if n == 0: return []
    out = []
    for row in split_top(unbrace(rhs))[:n]:
        p = split_top(unbrace(row))
        build, table, lvln, nl = unq(p[0]), as_int(p[1]), as_int(p[2]), as_int(p[3])
        lists = [tuple(as_int(x) for x in split_top(unbrace(pair))) for pair in split_top(unbrace(p[4]))]
        out.append((build, table, lvln, nl, lists))
    return out


# --- binary encoding: natural C struct alignment, not packed (found in review: #pragma pack(1) let call sites take a typed pointer into an
# unaligned uint64_t field, UB even though AArch64 tolerates the actual load). Packer mirrors the C compiler's own layout rules (each field aligned to its
# own size, a struct's size rounded up to its own largest member alignment, so an array of structs has uniform stride) - the one thing that must stay in
# step with profile_rt.h's field order by hand; DM_PROFILE_LAYOUT_SIZE (schema_stamp) and the _Static_assert in profile_rt.c catch the two drifting apart.
CAPS = dict(modules=8, display_versions=16, window_fn=8, subgroup_one_lane=16, mesh_vegetation_cut=128, mesh_prepass_cut=64, fps_steps=8, drs_ctl=8, res_levels=8, render_cfg=4, rt_pool=4)


def pad(s, n):
    b = s.encode('utf-8')
    if len(b) >= n: sys.exit(f'profile: {s!r} does not fit in {n} bytes (raise the cap in profile_rt.h)')
    return b + b'\0' * (n - len(b))


class Packer:
    """Builds one struct's bytes with natural C alignment. u32/u64 align themselves; raw() is a char[] (byte-aligned); sub() splices in an already-
    packed nested value (e.g. one array row, or a whole array of them) at its own alignment. finish() rounds the total size up to the struct's own
    alignment (the largest alignment any field used), exactly as a C compiler pads a struct's size so arrays of it have uniform stride."""
    def __init__(self): self.buf = bytearray(); self.align = 1

    def _align(self, a):
        self.align = max(self.align, a)
        self.buf += b'\0' * ((-len(self.buf)) % a)

    def u32(self, v): self._align(4); self.buf += struct.pack('<I', v & 0xffffffff)
    def u64(self, v): self._align(8); self.buf += struct.pack('<Q', v & 0xffffffffffffffff)
    def raw(self, s, n): self._align(1); self.buf += pad(s, n)
    def sub(self, b, a): self._align(a); self.buf += b

    def finish(self):
        self.buf += b'\0' * ((-len(self.buf)) % self.align)
        return bytes(self.buf), self.align


def row_display_version(b, v):
    p = Packer(); p.raw(b, 48); p.raw(v, 16); return p.finish()


def row_window_fn(build, wfn, hfn, w, h):
    p = Packer(); p.raw(build, 48); p.u64(wfn); p.u64(hfn); p.u32(w); p.u32(h); return p.finish()


def row_mesh_prepass_cut(prepass, colour, mapv, has):
    p = Packer()
    for v in (prepass, colour, mapv, has): p.u64(v)
    return p.finish()


def row_fps_steps(build, ptr_off, mode, count, offs):
    p = Packer(); p.raw(build, 48); p.u64(ptr_off); p.u32(mode); p.u32(count); p.u32(len(offs))
    offs3 = (list(offs) + [0, 0, 0])[:3]
    sub = Packer(); [sub.u64(o) for o in offs3]; b, a = sub.finish()
    p.sub(b, a)
    return p.finish()


def row_drs_ctl(build, params, pick, budget, preset):
    p = Packer(); p.raw(build, 48)
    for v in (params, pick, budget, preset): p.u64(v)
    return p.finish()


def row_res_levels(build, table, n, nl, lists12):
    p = Packer(); p.raw(build, 48); p.u64(table); p.u32(n); p.u32(nl)
    sub = Packer()
    for off, ln in lists12:
        s = Packer(); s.u64(off); s.u32(ln); b, a = s.finish(); sub.sub(b, a)
    b, a = sub.finish()
    p.sub(b, a)
    return p.finish()


RCFG_SLOTS = 8   # rcfg_row_t's RCFG_MAX (dm/host/render_cfg.h)
RTP_TIERS = 4    # rtpool_row_t's RTP_TIERS (dm/host/rt_pool.h)


def parse_render_cfg(rhs, n):
    if n == 0: return []
    out = []
    for row in split_top(unbrace(rhs))[:n]:
        p = split_top(unbrace(row))
        sz = [tuple(as_int(x) for x in split_top(unbrace(e))) for e in split_top(unbrace(p[6]))]
        out.append((unq(p[0]), as_int(p[1]), as_int(p[2]), as_int(p[3]), as_int(p[4]), as_int(p[5]), sz, [as_int(x) for x in p[7:12]]))
    return out


def parse_rt_pool(rhs, n):
    if n == 0: return []
    out = []
    for row in split_top(unbrace(rhs))[:n]:
        p = split_top(unbrace(row))
        out.append((unq(p[0]), unq(p[1]), as_int(p[2]), as_int(p[3]), as_int(p[4]), as_int(p[5]),
                    [as_int(x) for x in split_top(unbrace(p[6]))], [as_int(x) for x in split_top(unbrace(p[7]))], [as_int(x) for x in split_top(unbrace(p[8]))], as_int(p[9])))
    return out


def row_render_cfg(build, table, n, stride, out_off, ren_off, sz, tail):
    p = Packer(); p.raw(build, 48); p.u64(table)
    for v in (n, stride, out_off, ren_off): p.u32(v)
    for i in range(RCFG_SLOTS):
        for v in (list(sz[i]) if i < len(sz) else [0, 0, 0, 0]): p.u32(v)
    for v in tail: p.u32(v)
    return p.finish()


def row_rt_pool(build, module, off, word, base, nt, mb, w, h, out_h):
    p = Packer(); p.raw(build, 48); p.raw(module, 16); p.u64(off)
    for v in (word, base, nt): p.u32(v)
    for a in (mb, w, h):
        for i in range(RTP_TIERS): p.u32(a[i] if i < len(a) else 0)
    p.u32(out_h)
    return p.finish()


def pack_rows(rows, cap, zero_row):
    """cap already-built (bytes, alignment) rows into one (bytes, alignment); slots past len(rows) get zero_row (a (bytes, alignment) of the same row
    type with every field 0/"", built even when there are zero real rows - a title's count alone must not change this table's per-row size/alignment)."""
    p = Packer()
    for i in range(cap):
        b, a = rows[i] if i < len(rows) else zero_row
        p.sub(b, a)
    return p.finish()


MOD_MEMORY_KEYS = {'mem_reserve': 'MEM_RESERVE', 'mem_alias': 'MEM_ALIAS', 'app_pool': 'APP_POOL'}


def mod_memory(title_id):
    """{macro: value text} from mod_memory.tsv for this title: rows `<title ID> <key> <value>`, key one of MOD_MEMORY_KEYS, '#' comments"""
    out = {}
    f = HERE / 'mod_memory.tsv'
    if not f.is_file(): return out
    for line in f.read_text().splitlines():
        cells = line.split('#', 1)[0].split()
        if len(cells) != 3 or cells[0].upper() != str(title_id).upper(): continue
        if cells[1] not in MOD_MEMORY_KEYS: sys.exit(f'gen_runtime_profile.py: mod_memory.tsv: {cells[1]} is not one of {sorted(MOD_MEMORY_KEYS)}')
        try: as_int(cells[2])   # a number, or this stops here
        except ValueError: sys.exit(f'gen_runtime_profile.py: mod_memory.tsv: {cells[0]} {cells[1]}: {cells[2]} is not a number')
        out[MOD_MEMORY_KEYS[cells[1]]] = cells[2]
    return out


def build_profile_bytes(g, modules_override=None):
    """modules_override: a modded variant build's own module list (dm/tools/pack_android.py --modules, dev.peanuts.build.ModBuild.Plan.modules),
    replacing game.tsv's MODULES/NMOD for this one profile.bin; None (the normal path) uses header()'s own values, byte for byte as before."""
    d = parse_defines(gamepy.header(g))
    unknown = set(d) - KNOWN
    if unknown: sys.exit(f'gen_runtime_profile.py: {g.id}: game.py header() emits unknown macro(s) {sorted(unknown)} - add them to SCALAR/PRESENCE/ARRAY_NAMES '
                         f'(and a converted call site if they are an ARRAY one) before trusting a generic build')
    missing = {n for n in SCALAR if n not in d}
    if missing: sys.exit(f'gen_runtime_profile.py: {g.id}: header() did not emit expected scalar macro(s) {sorted(missing)} (game.py changed shape?)')

    # A modded variant's memory (mod_memory.tsv beside this file): a mod's larger files can need more of the application pool than the game's profile gives the
    # game itself, and the profile's own numbers stay as tested for the game without mods (its memory lies where it did)
    if modules_override is not None:
        for key, value in mod_memory(g.id).items(): d[key] = value

    def val(name): return d[name]

    nmod_count = len(modules_override) if modules_override is not None else as_int(val('NMOD'))
    for cnt, cap, label in ((nmod_count, CAPS['modules'], 'modules'), (len(parse_display_versions(val('DISPLAY_VERSIONS'))), CAPS['display_versions'], 'display_versions'),
                            (as_int(val('NWINDOW_FN')), CAPS['window_fn'], 'window_fn'), (as_int(val('NSUBGROUP_ONE_LANE')), CAPS['subgroup_one_lane'], 'subgroup_one_lane'),
                            (as_int(val('NMESH_VEGETATION_CUT')), CAPS['mesh_vegetation_cut'], 'mesh_vegetation_cut'),
                            (as_int(val('NMESH_PREPASS_CUT')), CAPS['mesh_prepass_cut'], 'mesh_prepass_cut'), (as_int(val('NFPS_STEPS')), CAPS['fps_steps'], 'fps_steps'),
                            (as_int(val('NDRS_CTL')), CAPS['drs_ctl'], 'drs_ctl'), (as_int(val('NRES_LEVELS')), CAPS['res_levels'], 'res_levels'),
                            (as_int(val('NRENDER_CFG')), CAPS['render_cfg'], 'render_cfg'), (as_int(val('NRT_POOL')), CAPS['rt_pool'], 'rt_pool')):
        if cnt > cap: sys.exit(f"gen_runtime_profile.py: {g.id}: {label} has {cnt} rows, past this script's fixed capacity {cap} (raise CAPS['{label}'] and the matching cap in profile_rt.h)")

    p = Packer()
    p.u32(0x504e5052); p.u32(schema_version())
    p.raw(unq(val('ID')), 32); p.raw(unq(val('NAME')), 128); p.raw(unq(val('ROMFS_MARKER')), 192)
    p.u64(as_int(val('PROGRAM_ID')))
    for n in ('MEM_RESERVE', 'MEM_ALIAS', 'MEM_STACK', 'APP_POOL', 'MEM_USED', 'SYS_RESOURCE', 'STDALLOC_CAP'): p.u64(as_int(val(n)))
    p.raw(unq(val('ARGS')), 256); p.raw(unq(val('ENV')), 256); p.raw(unq(val('FASTBOOT_ENV')), 256); p.raw(unq(val('DEVTOOLS')), 256); p.raw(unq(val('ROMFS_SUBST')), 512)
    p.u32(as_int(val('MEM_MAP_LIMIT')))
    for n in ('PRESENT_DISPLAY', 'CORE_PREEMPT', 'ALLOC_BIG_ALIGN', 'FPS_TARGET', 'FPS_HOLD', 'FPS_LIMIT'): p.u32(as_int(val(n)))
    p.u32(as_int(val('UE_TEXTURE_POOL'))); p.raw(unq(val('UE_FPS_CVAR')), 32)
    for n in ('PRESENT_SNAP', 'PSO_MTL4', 'ENC_THREAD', 'PAD_LAYOUT', 'OPMODE', 'PERF_BOOST', 'TEX_FLUSH_UPLOAD'): p.u32(as_int(val(n)))
    p.u64(as_int(val('SHADER_KEY_SKIP_OFF'))); p.u64(as_int(val('SHADER_KEY_SKIP_LEN')))
    for n in ('RENDER_COND', 'TEX_DIRTY_UPLOAD', 'TEX_DYN_SLOTS', 'TEX_SOFT', 'GPU_TS_SEGMENT', 'DRAW_BARRIERS_GAME', 'VCONV_RANGE', 'AV1_DAV1D'): p.u32(as_int(val(n)))

    p.u32(int('SHARED_FONT_TTF' in d)); p.u32(as_int(d.get('SHARED_FONT_TTF', '0')))
    has_api = 'NVN_API_MAJOR' in d
    p.u32(int(has_api)); p.u32(as_int(d.get('NVN_API_MAJOR', '0'))); p.u32(as_int(d.get('NVN_API_MINOR', '0')))

    if modules_override is not None: nmod, mods = len(modules_override), list(modules_override)
    else: nmod = as_int(val('NMOD')); mods = parse_modules(val('MODULES'))
    p.u32(nmod)
    rows = Packer()
    for i in range(CAPS['modules']): rows.raw(mods[i] if i < len(mods) else '', 32)
    b, a = rows.finish(); p.sub(b, a)

    dv = parse_display_versions(val('DISPLAY_VERSIONS'))
    p.u32(len(dv))
    zdv = row_display_version('', '')
    b, a = pack_rows([row_display_version(b_, v_) for b_, v_ in dv], CAPS['display_versions'], zdv); p.sub(b, a)

    nwfn = as_int(val('NWINDOW_FN')); wfn = parse_window_fn(val('WINDOW_FN'), nwfn)
    p.u32(nwfn)
    zwfn = row_window_fn('', 0, 0, 0, 0)
    b, a = pack_rows([row_window_fn(*r) for r in wfn], CAPS['window_fn'], zwfn); p.sub(b, a)

    nsol = as_int(val('NSUBGROUP_ONE_LANE')); sol = parse_flat_u64(val('SUBGROUP_ONE_LANE'), nsol)
    p.u32(nsol)
    rows = Packer()
    for i in range(CAPS['subgroup_one_lane']): rows.u64(sol[i] if i < len(sol) else 0)
    b, a = rows.finish(); p.sub(b, a)

    nveg = as_int(val('NMESH_VEGETATION_CUT')); veg = parse_flat_u64(val('MESH_VEGETATION_CUT'), nveg)
    p.u32(nveg)
    rows = Packer()
    for i in range(CAPS['mesh_vegetation_cut']): rows.u64(veg[i] if i < len(veg) else 0)
    b, a = rows.finish(); p.sub(b, a)

    nmpc = as_int(val('NMESH_PREPASS_CUT')); mpc = parse_mesh_prepass_cut(val('MESH_PREPASS_CUT'), nmpc)
    p.u32(nmpc)
    zmpc = row_mesh_prepass_cut(0, 0, 0, 0)
    b, a = pack_rows([row_mesh_prepass_cut(*r) for r in mpc], CAPS['mesh_prepass_cut'], zmpc); p.sub(b, a)   # "has" is 0/1 stored as the fourth uint64, as the C macro itself does

    nfst = as_int(val('NFPS_STEPS')); fst = parse_fps_steps(val('FPS_STEPS'), nfst)
    p.u32(nfst)
    zfst = row_fps_steps('', 0, 0, 0, [])
    b, a = pack_rows([row_fps_steps(*r) for r in fst], CAPS['fps_steps'], zfst); p.sub(b, a)   # dm_pf_fps_steps_t matches hle_fpslock.c's own steps_row_t field order

    ndrs = as_int(val('NDRS_CTL')); drs = parse_drs_ctl(val('DRS_CTL'), ndrs)
    p.u32(ndrs)
    zdrs = row_drs_ctl('', 0, 0, 0, 0)
    b, a = pack_rows([row_drs_ctl(*r) for r in drs], CAPS['drs_ctl'], zdrs); p.sub(b, a)

    nrl = as_int(val('NRES_LEVELS')); rl = parse_res_levels(val('RES_LEVELS'), nrl)
    p.u32(nrl)
    zrl = row_res_levels('', 0, 0, 0, [(0, 0)] * 12)
    rl_rows = [row_res_levels(b_, table, lvln, nl, (lists + [(0, 0)] * 12)[:12]) for b_, table, lvln, nl, lists in rl]
    b, a = pack_rows(rl_rows, CAPS['res_levels'], zrl); p.sub(b, a)

    for n in ('EVENT_WAIT', 'OCCLUSION_SPLIT_PASS', 'LOW_RAM_PLACE_PART_MB', 'LOW_RAM_IMG_CACHE_MAX_MB', 'LOW_RAM_APP_POOL_MB'): p.u32(as_int(val(n)))
    p.raw(unq(val('RENDER_DEFAULT_OUT')), 16)
    rq = [as_int(x) for x in split_top(unbrace(val('RENDER_QUALITY')))]
    assert len(rq) == 4, rq
    for v in rq: p.u32(v)

    nrc = as_int(val('NRENDER_CFG')); rc = parse_render_cfg(val('RENDER_CFG'), nrc)
    p.u32(nrc)
    zrc = row_render_cfg('', 0, 0, 0, 0, 0, [], [0] * 5)
    b, a = pack_rows([row_render_cfg(*r) for r in rc], CAPS['render_cfg'], zrc); p.sub(b, a)

    nrt = as_int(val('NRT_POOL')); rt = parse_rt_pool(val('RT_POOL'), nrt)
    p.u32(nrt)
    zrt = row_rt_pool('', '', 0, 0, 0, 0, [], [], [], 0)
    b, a = pack_rows([row_rt_pool(*r) for r in rt], CAPS['rt_pool'], zrt); p.sub(b, a)
    p.u32(as_int(val('IMAGE_RAW32')))

    out, _ = p.finish()
    return out


def schema_stamp_h():
    # A header, not a .c (G-2/G-3 fix): both are compile-time constants (DM_PROFILE_SCHEMA_VERSION for the schema check, DM_PROFILE_LAYOUT_SIZE for
    # profile_rt.h's _Static_assert against sizeof(dm_game_profile_t)), generated once per generic host build, not per title.
    return (f'// PEANUTS: generated by dm/peanuts/profile/gen_runtime_profile.py schema-stamp. One per generic host build, not per title. Do not edit.\n'
            f'#pragma once\n#define DM_PROFILE_SCHEMA_VERSION {schema_version()}u\n#define DM_PROFILE_LAYOUT_SIZE {layout_size()}\n')


def main():
    a = sys.argv[1:]
    if len(a) == 3 and a[0] == 'profile':
        g = gamepy.load(a[1]); Path(a[2]).write_bytes(build_profile_bytes(g))
    elif len(a) == 4 and a[0] == 'profile-modules':
        g = gamepy.load(a[1]); modules = [m for m in a[3].split(',') if m]
        Path(a[2]).write_bytes(build_profile_bytes(g, modules_override=modules))
    elif len(a) == 3 and a[0] == 'seed':
        g = gamepy.load(a[1]); p = g.dir / gamepy.SEED
        Path(a[2]).write_bytes(p.read_bytes() if p.is_file() else b'')
    elif len(a) == 2 and a[0] == 'schema-stamp':
        Path(a[1]).write_text(schema_stamp_h())
    elif a == ['schema']:
        print(schema_version())
    elif a == ['check-all']:
        games = gamepy.all_games()
        for g in games: build_profile_bytes(g)   # raises/exits on any unknown macro or shape mismatch
        print(f'gen_runtime_profile.py check-all: {len(games)} profiles, all macros known, schema {schema_version():#010x}, layout {layout_size()} bytes')
    else:
        sys.exit('usage: gen_runtime_profile.py profile <id> <out.bin> | profile-modules <id> <out.bin> <mod1,mod2,...> | seed <id> <out.bin> | '
                 'schema-stamp <out.h> | schema | check-all')


if __name__ == '__main__':
    main()
