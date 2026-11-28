"""PEANUTS: the native post-passes (vk_android_spvn.py, libdmsh_spvn from vk_android_spv_native/spvn.c) write the same modules as vk_android_spv.py. Real translated
modules (peanuts/tests/fixtures/spvn: Yoshi's Crafted World, Trails in the Sky 1st Chapter, Hyrule Warriors: Age of Calamity; every stage the builds have and the
rewrites that fire on them) go through finish()'s steps with both, and every step's result and the module's bytes after it must be equal; the steps the library
does not make raise Fallback (finish() then makes the shader with the Python), and DMSH_VK_SPVN=0 turns the library off."""
import os
import struct
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'dm/gfx/tools'))
import vk_android_spv as spv  # noqa: E402
import vk_android_spvn as spvn  # noqa: E402

FIXTURES = ROOT / 'peanuts/tests/fixtures/spvn'


def steps(S, data, stage):
    """finish()'s calls of the passes (vk_android_build.py finish_with), the reflection's bindings made up → [(step, result, module bytes after it)]"""
    log = []
    m = S.Module(data)
    def rec(name, v): log.append((name, v, m.data()))
    if stage == 'vs': rec('integer_only_inputs', S.integer_only_inputs(m))
    rec('private_viewport_mask', stage == 'gs' and S.private_viewport_mask(m))
    rec('strip_unused_caps', S.strip_unused_caps(m))
    rec('formatless_storage_caps', S.formatless_storage_caps(m))
    rec('extended_format_caps', S.extended_format_caps(m))
    rec('workgroup_plain_arrays', S.workgroup_plain_arrays(m))
    rec('precise_minmax', S.precise_minmax(m))
    rec('txq_extra_components', S.txq_extra_components(m))
    hmma = stage == 'cs' and S.hmma_lds(m); rec('hmma_lds', hmma)
    if not hmma:
        S.promote_locals(m); rec('promote_locals', None)
        rec('loop_pool_search', S.loop_pool_search(m))
    rec('buffer_textures_split', S.buffer_textures_split(m))
    rec('nonuniform_arrays', S.nonuniform_arrays(m))
    rs = m.resources(); rec('resources', rs)
    for i, r in enumerate(sorted(rs, key=lambda e: e['binding'])): m.set_binding(r['id'], 1, 100 + i)
    rec('set_binding', None)
    rec('cache_raw_loads', S.cache_raw_loads(m, set()))
    if stage == 'vs': rec('init_outputs', S.init_outputs(m, {0, 1, 2, 3, 5, 9, 12}))
    elif stage == 'ps': rec('io', set(m.io(spv.SC_INPUT)))
    if stage in ('vs', 'ds', 'gs'): rec('gl_depth', S.gl_depth(m, 5))
    if stage == 'ps':
        rec('alpha_test', S.alpha_test(m, 6))
        fl = {l for l, (_, t) in m.io(spv.SC_OUTPUT).items() if not m.int_type(m.scalar_of(t))}
        fi = S.int_outputs(m); rec('outputs', (fl, fi, S.const_written_outputs(m)))
        for name, locs in (('retype_outputs', fl), ('retype_outputs mixed', fi & fl)):
            u = S.Module(m.data()); log.append((name, S.retype_outputs(u, locs), u.data()))
        d = S.Module(m.data()); log.append(('dual_source', S.dual_source(d), d.data()))
    if stage == 'cs':
        x = S.Module(m.data()); log.append(('shuffle_lds', S.shuffle_lds(x, 32768), x.data()))
    rec('facts', (sorted(set(m.caps)), sorted(set(m.exts)), S.functions_named(m), S.subgroup_used(m), S.has_spec_id(m, 5)))
    rec('block_rows', [S.block_rows(m, r['id']) for r in rs])
    return log


def ins(op, *w): return [(1 + len(w)) << 16 | op, *w]


def module(words, bound):
    flat = [v for x in words for v in x]
    return struct.pack('<%dI' % (5 + len(flat)), spv.MAGIC, 0x10300, 0, bound, 0, *flat)


def with_helper(op_in_helper, name='helper'):
    """A vertex module with one helper function (named name) holding op_in_helper"""
    return module([ins(17, 1), ins(14, 0, 1), ins(15, 0, 20, *spv.lit_string('main')), ins(5, 30, *spv.lit_string(name)),
                   ins(19, 1), ins(33, 2, 1),
                   ins(54, 1, 30, 0, 2), ins(248, 31), op_in_helper, ins(253), ins(56),
                   ins(54, 1, 20, 0, 2), ins(248, 21), ins(57, 1, 22, 30), ins(253), ins(56)], 40)


@unittest.skipUnless(spvn.available, f'no libdmsh_spvn: {spvn.WHY}')
class NativeSameAsPython(unittest.TestCase):
    def test_fixtures_step_by_step(self):
        files = sorted(FIXTURES.glob('*.spv'))
        self.assertGreaterEqual(len(files), 10)
        for f in files:
            key, stage = f.name.split('.')[:2]
            with self.subTest(fixture=f.name):
                data = f.read_bytes()
                py, nat = steps(spv, data, stage), steps(spvn, data, stage)
                self.assertEqual([s for s, _, _ in py], [s for s, _, _ in nat])
                for (s, a, ab), (_, b, bb) in zip(py, nat):
                    self.assertEqual(a, b, f'{s}: result')
                    self.assertEqual(ab, bb, f'{s}: module bytes')

    def test_rewrites_fire(self):
        """The fixtures change modules in every common pass (so the comparison above is not of untouched modules)"""
        fired = set()
        for f in sorted(FIXTURES.glob('*.spv')):
            stage = f.name.split('.')[1]
            log = steps(spv, f.read_bytes(), stage); before = f.read_bytes()
            for s, v, after in log:
                if after != before or (v and s not in ('resources', 'io', 'outputs', 'facts', 'block_rows')): fired.add(s)
                before = after
        for s in ('strip_unused_caps', 'formatless_storage_caps', 'precise_minmax', 'promote_locals', 'set_binding', 'init_outputs', 'gl_depth', 'alpha_test',
                  'retype_outputs', 'retype_outputs mixed'):
            self.assertIn(s, fired)

    def test_synthetic(self):
        """The rewrites no fixture needs, on the other tests' modules: a descriptor array at a computed index (test_vk_android_nonuniform)"""
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import test_vk_android_nonuniform as nu
        data = nu.module().data()
        for stage in ('ps', 'vs', 'cs'):
            with self.subTest(stage=stage):
                py, nat = steps(spv, data, stage), steps(spvn, data, stage)
                self.assertEqual(py, nat)
                self.assertNotEqual(dict((s, b) for s, _, b in py)['nonuniform_arrays'], dict((s, b) for s, _, b in py)['buffer_textures_split'])

    def test_fallbacks(self):
        """What the library does not make: the GlobalToStorage raw helpers (an OpConvertUToPtr outside the entry point), the HMMA helper, a module it cannot read"""
        raw = spvn.Module(with_helper(ins(120, 3, 32, 33)))
        with self.assertRaises(spvn.Fallback): spvn.promote_locals(raw)
        hm = spvn.Module(with_helper(ins(249, 31), name=spv.HMMA_NAME))
        with self.assertRaises(spvn.Fallback): spvn.hmma_lds(hm)
        self.assertEqual(spvn.functions_named(hm), spv.functions_named(spv.Module(with_helper(ins(249, 31), name=spv.HMMA_NAME))))
        with self.assertRaises(spvn.Fallback): spvn.Module(b'\x03\x02\x23\x07' + b'\0' * 15)
        ok = with_helper(ins(249, 31))   # no raw helper: the same as the Python
        p, n = spv.Module(ok), spvn.Module(ok)
        spv.promote_locals(p); spvn.promote_locals(n)
        self.assertEqual(p.data(), n.data())

    def test_in_the_digest(self):
        """The library makes the modules too, so its sources move DMSH_VK_CHAIN as vk_android_spv.py does (android/SHADERS.md, Versions)"""
        src = (ROOT / 'dm/gfx/tools/vk_android_build.py').read_text()
        for f in ('tools/vk_android_spvn.py', 'tools/vk_android_spv_native/spvn.c'): self.assertIn(f"'{f}'", src)
        self.assertIn("include 'dm/gfx/tools/vk_android_spv_native/spvn.c'", (ROOT / 'android/app/build.gradle').read_text())


class Switch(unittest.TestCase):
    def test_off(self):
        env = dict(os.environ, DMSH_VK_SPVN='0')
        r = subprocess.run([sys.executable, '-c', 'import vk_android_spvn as s; print(s.available, s.WHY)'], cwd=ROOT / 'dm/gfx/tools', env=env,
                           capture_output=True, text=True, check=True)
        self.assertEqual(r.stdout.strip(), 'False DMSH_VK_SPVN=0')

    def test_other_sources_refused(self):
        """A library whose embedded SHA-256 is not spvn.c's is not used (the Python runs)"""
        if not spvn.available: self.skipTest(spvn.WHY)
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            lib = os.path.join(d, 'libother.so')
            cc = os.environ.get('CC') or 'cc'
            subprocess.run([cc, '-shared', '-fPIC', '-O2', '-DSPVN_SOURCE="0000"', '-o', lib, spvn.SOURCE], check=True, capture_output=True)
            env = dict(os.environ, DMSH_VK_SPVN=lib)
            r = subprocess.run([sys.executable, '-c', 'import vk_android_spvn as s; print(s.available)'], cwd=ROOT / 'dm/gfx/tools', env=env,
                               capture_output=True, text=True, check=True)
            self.assertEqual(r.stdout.strip(), 'False')


if __name__ == '__main__':
    unittest.main()
