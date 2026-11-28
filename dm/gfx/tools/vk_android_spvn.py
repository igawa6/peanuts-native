"""vk_android_spv.py's module and post-passes from libdmsh_spvn (vk_android_spv_native/spvn.c) through ctypes, for vk_android_build.py finish(): the module is parsed once
into the library, every pass runs there, and its words come back once per module the library stores. The functions have vk_android_spv.py's names and results, so
finish() runs the same code with either (vk_android_build.py spv_of). The library writes every module byte for byte as the Python does (android/SHADERS.md, Native
post-passes); a pass it does not have, or a shape the Python would raise on, raises Fallback, and finish() then makes that shader again with vk_android_spv.py.

Which library (DMSH_VK_SPVN): unset, on Android libdmsh_spvn.so (the APK's lib/, as libdmsh_xlate.so), elsewhere one built from spvn.c with the C compiler into a
cache folder named after the source's SHA-256 (DMSH_VK_SPVN_CACHE, default ~/.cache/dmsh_spvn; built once per source, by `cc`, or CC); 0 = none (the Python passes);
a path = that library. The library must carry spvn.c's SHA-256 (spvn_source), so one built from other sources is not used. Without one the Python runs: same output."""
import ctypes, hashlib, os, struct, subprocess, sys, tempfile
import vk_android_spv as spv

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, 'vk_android_spv_native', 'spvn.c')


class Fallback(Exception):
    """The library does not make this module (a pass it does not have, or a shape the Python would raise on): make the shader with vk_android_spv.py"""


def source_sha():
    with open(SOURCE, 'rb') as f: return hashlib.sha256(f.read()).hexdigest()


def compile_lib(out, cc=None):
    """spvn.c → a shared library at out with the host's C compiler (as vk_android_spv_native/build.sh host does)"""
    cc = cc or os.environ.get('CC') or 'cc'
    flags = ['-shared', '-fPIC', '-O2', '-std=gnu11', '-fvisibility=hidden', f'-DSPVN_SOURCE="{source_sha()}"']
    if sys.platform == 'darwin': flags += ['-undefined', 'dynamic_lookup']
    os.makedirs(os.path.dirname(out), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(out), suffix='.tmp'); os.close(fd)
    try:
        subprocess.run([cc, *flags, '-o', tmp, SOURCE], check=True, capture_output=True)
        os.replace(tmp, out)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)
    return out


def _find():
    """→ (path, why) of the library to load, path None for the Python passes"""
    want = os.environ.get('DMSH_VK_SPVN')
    if want == '0': return None, 'DMSH_VK_SPVN=0'
    if want: return want, ''
    if sys.platform == 'android': return 'libdmsh_spvn.so', ''
    if not os.path.exists(SOURCE): return None, 'no spvn.c'
    ext = '.dylib' if sys.platform == 'darwin' else '.dll' if sys.platform == 'win32' else '.so'
    cache = os.environ.get('DMSH_VK_SPVN_CACHE') or os.path.join(os.path.expanduser('~'), '.cache', 'dmsh_spvn')
    path = os.path.join(cache, source_sha()[:16], 'libdmsh_spvn' + ext)
    if not os.path.exists(path):
        try: compile_lib(path)
        except (OSError, subprocess.CalledProcessError) as e: return None, f'no C compiler build ({type(e).__name__})'
    return path, ''


LIB, WHY = None, ''


def _load():
    global LIB, WHY
    path, WHY = _find()
    if path is None: return
    try: L = ctypes.CDLL(path)
    except OSError as e: WHY = f'{path}: {e}'; return
    L.spvn_source.restype = ctypes.c_char_p
    src = L.spvn_source().decode()
    if os.path.exists(SOURCE) and src != source_sha(): WHY = f'{path} was built from other sources than {SOURCE}'; return
    L.spvn_new.argtypes, L.spvn_new.restype = [ctypes.c_char_p, ctypes.c_size_t], ctypes.c_void_p
    L.spvn_free.argtypes, L.spvn_free.restype = [ctypes.c_void_p], None
    L.spvn_copy.argtypes, L.spvn_copy.restype = [ctypes.c_void_p], ctypes.c_void_p
    L.spvn_data.argtypes, L.spvn_data.restype = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t)], ctypes.c_void_p
    L.spvn_res.argtypes, L.spvn_res.restype = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t)], ctypes.POINTER(ctypes.c_int64)
    L.spvn_call.argtypes, L.spvn_call.restype = [ctypes.c_void_p, ctypes.c_int, ctypes.POINTER(ctypes.c_int64), ctypes.c_size_t], ctypes.c_int64
    L.spvn_why.argtypes, L.spvn_why.restype = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)], ctypes.c_char_p
    LIB = L


_load()
available = LIB is not None

(P_STRIP_UNUSED_CAPS, P_FORMATLESS_STORAGE_CAPS, P_EXTENDED_FORMAT_CAPS, P_WORKGROUP_PLAIN_ARRAYS, P_PRECISE_MINMAX, P_TXQ_EXTRA_COMPONENTS, P_HMMA_USED,
 P_PROMOTE_LOCALS, P_LOOP_POOL_SEARCH, P_BUFFER_TEXTURES_SPLIT, P_NONUNIFORM_ARRAYS, P_RESOURCES, P_SET_BINDING, P_IMAGE_ATOMIC, P_IMAGE_WRITES, P_CACHE_RAW_LOADS,
 P_INIT_OUTPUTS, P_IO, P_PER_PRIMITIVE_INPUTS, P_GL_DEPTH, P_HAS_SPEC_ID, P_ALPHA_TEST, P_INT_OUTPUTS, P_CONST_WRITTEN_OUTPUTS, P_RETYPE_OUTPUTS, P_SHUFFLE_PRECHECK,
 P_INTEGER_ONLY_INPUTS, P_PRIVATE_VIEWPORT_MASK, P_CAPS, P_EXTS, P_INT_TYPE, P_SCALAR_OF, P_BLOCK_ROWS, P_FUNCTIONS_NAMED, P_SUBGROUP_USED, P_TESS_MODES_SET,
 P_INS_WORDS, P_NINS) = range(1, 39)
NONE = 0xFFFFFFFF


class Module:
    """A module held by the library (vk_android_spv.Module's interface as finish() uses it)"""
    __slots__ = ('h', 'why')

    def __init__(self, data):
        self.h = LIB.spvn_new(data, len(data)); self.why = ''
        if not self.h: raise Fallback('the library did not read the module')

    def __del__(self):
        if getattr(self, 'h', None): LIB.spvn_free(self.h); self.h = None

    def _reload(self, data):   # the module becomes data (a pass of vk_android_spv.py ran on a fresh copy)
        h = LIB.spvn_new(data, len(data))
        if not h: raise Fallback('the library did not read the module')
        LIB.spvn_free(self.h); self.h = h

    def call(self, p, *args):
        a = (ctypes.c_int64 * max(1, len(args)))(*args)
        r = LIB.spvn_call(self.h, p, a, len(args))
        if r < 0:
            line = ctypes.c_int(); fn = LIB.spvn_why(self.h, ctypes.byref(line)).decode()
            raise Fallback(f'{fn}:{line.value}')
        return r

    def res(self):
        n = ctypes.c_size_t(); p = LIB.spvn_res(self.h, ctypes.byref(n))
        return p[:n.value] if n.value else []

    def data(self):
        n = ctypes.c_size_t(); p = LIB.spvn_data(self.h, ctypes.byref(n))
        if not p: raise Fallback('data')
        return ctypes.string_at(p, n.value)

    def copy(self): return Module(self.data())

    @property
    def caps(self): self.call(P_CAPS); return self.res()

    @property
    def exts(self):
        self.call(P_EXTS); r = self.res(); out, i = [], 0
        while i < len(r): n = r[i]; out.append(spv.string_at(r[i + 1:i + 1 + n], 1)[0]); i += 1 + n
        return out

    def io(self, sc):
        n = self.call(P_IO, sc); r = self.res()
        return {r[3 * i]: (r[3 * i + 1], r[3 * i + 2]) for i in range(n)}

    def int_type(self, t):
        if t is None or not 0 <= t < NONE: return None
        return tuple(self.res()) if self.call(P_INT_TYPE, t) else None

    def scalar_of(self, t): return self.call(P_SCALAR_OF, t)

    def resources(self):
        n = self.call(P_RESOURCES); r = self.res(); out, i = [], 0
        for _ in range(n):
            vid, st, b, count, desc, has_dim, dim, depth, arr, ms, has_t, iw, isg, nw = r[i:i + 14]; i += 14
            name = spv.string_at(r[i:i + nw], 2)[0] if nw >= 0 else ''
            if nw > 0: i += nw
            e = {'id': vid, 'name': name, 'set': st, 'binding': b, 'count': count, 'dim': None}
            if has_dim: e['dim'] = {'dim': dim, 'depth': depth == 1, 'array': bool(arr), 'ms': bool(ms), 'type': (iw, isg) if has_t else None}
            e['desc'] = desc
            out.append(e)
        return out

    def set_binding(self, vid, s, b): self.call(P_SET_BINDING, vid, s, b)

    def tess_modes_set(self, modes): return tess_modes_set(self, modes)   # (vk_android_build.py tess_modes_set)
    def image_atomic(self, vid): return image_atomic(self, vid)   # (vk_android_build.py m_atomic)
    def image_writes(self, vid): return image_writes(self, vid)   # (m_writes)


def _caps_names(m): return sorted(spv.CAPS.get(c, str(c)) for c in m.res())


# ---- vk_android_spv.py's functions, as finish() calls them
def strip_unused_caps(m): m.call(P_STRIP_UNUSED_CAPS); return _caps_names(m)
def formatless_storage_caps(m): m.call(P_FORMATLESS_STORAGE_CAPS); return sorted(spv.CAPS[c] for c in m.res())
def extended_format_caps(m): m.call(P_EXTENDED_FORMAT_CAPS); return [spv.CAPS[c] for c in m.res()]
def workgroup_plain_arrays(m): return m.call(P_WORKGROUP_PLAIN_ARRAYS)
def precise_minmax(m): return m.call(P_PRECISE_MINMAX)
def txq_extra_components(m): return m.call(P_TXQ_EXTRA_COMPONENTS)
def promote_locals(m): m.call(P_PROMOTE_LOCALS)
def loop_pool_search(m): return m.call(P_LOOP_POOL_SEARCH)
def buffer_textures_split(m): return m.call(P_BUFFER_TEXTURES_SPLIT)
def nonuniform_arrays(m): return m.call(P_NONUNIFORM_ARRAYS)
def cache_raw_loads(m, tables): return m.call(P_CACHE_RAW_LOADS, len(tables))
def init_outputs(m, extra_locations=()): m.call(P_INIT_OUTPUTS, *sorted(set(extra_locations))); return m.res()
def gl_depth(m, spec_id): return bool(m.call(P_GL_DEPTH, spec_id))
def alpha_test(m, spec_id): return bool(m.call(P_ALPHA_TEST, spec_id))
def int_outputs(m): m.call(P_INT_OUTPUTS); return set(m.res())
def const_written_outputs(m): m.call(P_CONST_WRITTEN_OUTPUTS); return set(m.res())
def retype_outputs(m, locs): m.call(P_RETYPE_OUTPUTS, *sorted(locs)); return m.res()
def integer_only_inputs(m): m.call(P_INTEGER_ONLY_INPUTS); return {str(loc): 'uint' for loc in m.res()}
def private_viewport_mask(m): return bool(m.call(P_PRIVATE_VIEWPORT_MASK))
def subgroup_used(m): return bool(m.call(P_SUBGROUP_USED))
def has_spec_id(m, spec_id): return bool(m.call(P_HAS_SPEC_ID, spec_id))
def image_atomic(m, vid): return bool(m.call(P_IMAGE_ATOMIC, vid))
def image_writes(m, vid): return bool(m.call(P_IMAGE_WRITES, vid))


def hmma_lds(m, keep=None):
    m.call(P_HMMA_USED)   # (raises Fallback when the module has the helper: the lowering is the Python's)
    return 0


def per_primitive_inputs(m, locs): m.call(P_PER_PRIMITIVE_INPUTS, *sorted(locs)); return []


def block_rows(m, vid):
    dyn = m.call(P_BLOCK_ROWS, vid)
    return m.res(), bool(dyn)


def functions_named(m):
    n = m.call(P_FUNCTIONS_NAMED); r = m.res(); out, i = {}, 0
    for _ in range(n):
        f, nw = r[i], r[i + 1]; i += 2
        out[spv.string_at(r[i:i + nw], 2)[0] if nw >= 0 else ''] = f
        if nw > 0: i += nw
    return out


def tess_modes_set(m, modes): return m.call(P_TESS_MODES_SET, *[-1 if v is None else v for v in modes])


def shuffle_lds(m, shared_max):
    """On a fresh copy (finish's), as vk_android_spv.py does it: the library checks whether it applies, the Python rewrites"""
    if not m.call(P_SHUFFLE_PRECHECK): return 0
    py = spv.Module(m.data()); n = spv.shuffle_lds(py, shared_max)
    if n: m._reload(py.data())
    return n


def dual_source(m):
    """On a fresh copy (ps_variants'), by vk_android_spv.py"""
    py = spv.Module(m.data()); done = spv.dual_source(py)
    if done: m._reload(py.data())
    return done
