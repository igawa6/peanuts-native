"""dm/peanuts/reuse/shader_reuse.py (issue #120): the shaders step keeps the shaders of the pack's older library whose translation comes out the same and translates the
others, with a sample translated to check. The libraries here are small ones in the layout of dmsh_abi_vk.h (raw modules, no zstd needed); vk_android_build.py is
stood in for by a script that does what its delta path does with a manifest (keep an older entry whose hash is the manifest's, translate the rest) and "translates" a
shader as a function of its key, its facts, its pixel shaders' facts and one input the reuse rule does not know (unseen.tsv), so a change there is caught by the
check alone. No game files."""
import contextlib
import hashlib
import io
import json
import os
import shutil
import struct
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(REPO / 'dm/peanuts/reuse'), str(REPO / 'dm/gfx/tools'), str(REPO / 'dm/games')]
import shader_reuse as sr   # noqa: E402
import vk_android_delta as delta   # noqa: E402

VK = delta.VK
GAME = '0100000000AA0000'
VERSION = sr.vk_version()


def spirv_of(seed):
    """A SPIR-V-looking module whose words follow from seed"""
    b = hashlib.sha256(seed.encode()).digest() * 4
    return struct.pack('<5I', 0x07230203, 0x00010300, 0, 8, 0) + b


def write_lib(path, shaders, inputs, version=VERSION, game=GAME):
    """A library of shaders [(key, SPIR-V)] (raw modules), as write_library lays it out"""
    entries, binds, mods, blob = bytearray(), bytearray(), bytearray(), bytearray()
    for n, (key, s) in enumerate(sorted(shaders)):
        blob += b'\0' * (-len(blob) % 16); o = len(blob); blob += s
        mods += delta.MOD.pack(o, len(s), len(s), 0, VK['CODEC_RAW'], 0)
        binds += struct.pack('<BBHBBHhHBBhHHI', 1, 6, 4 * 64, 0, 0, 1, 0, 0, 0, 0, -1, 0xFFFF, 0, 0)
        entries += delta.ENTRY.pack(int(key, 16), 0, 4, 1, 0x40, n, n, 1, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    eo = 128; bo = eo + len(entries); mo = bo + len(binds); blo = (mo + len(mods) + 15) // 16 * 16
    pad = lambda s: s.encode().ljust(16, b'\0')
    head = delta.HEAD.pack(VK['LIB_MAGIC'], VK['LIB_VERSION'], pad('a139922bad5c'), pad(version), pad(game), pad(inputs), len(shaders), eo, len(shaders), bo,
                           len(shaders), mo, blo, len(blob), 64, 64, b'cb5a89b39291')
    data = head + entries + binds + mods
    data += b'\0' * (blo - len(data)) + blob
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_bytes(data)


FAKE = textwrap.dedent('''
    # vk_android_build.py's delta path, small: an older library's entry whose hash is the manifest's is kept, every other shader translated
    import hashlib, json, os, sys
    sys.path[:0] = [{tools!r}, {games!r}, {reuse!r}, {tests!r}]
    import game, dmsh_chain, vk_android_delta as delta, test_shader_reuse as t
    a = sys.argv[1:]
    g = game.load(); inputs = dmsh_chain.inputs(g)
    keys = [l.split('\\t')[3] for l in open(g.table('shader_map.tsv')) if l.split('\\t')[0].endswith('.bin')]
    absent = set(open(os.environ['DMSH_VK_ABSENT_FILE']).read().split()) if os.environ.get('DMSH_VK_ABSENT_FILE') else set()   # validated absences: not built
    keys = [k for k in keys if k not in absent]
    try: m = delta.parse_manifest(open(g.table('vk_delta.tsv')).read())
    except (OSError, ValueError): m = None
    if m and (m['version'], m['game'], m['inputs']) != (t.VERSION, g.id, inputs): m = None
    # Try anyway (DMSH_VK_REQUIRE_ALL=0): a key the split's package lacks is left out of the translation (open_packs), but a kept one never goes through it
    split_map = os.path.join(os.path.dirname(a[2]), 'shader_map.tsv')
    if os.path.isfile(split_map):
        found = {{l.split('\\t')[3] for l in open(split_map) if l.split('\\t')[0].endswith('.bin')}}
        keys = [k for k in keys if k in found or (m and k in m['hashes'])]
    libs = []
    for r in os.environ.get('DMSH_VK_DELTA_FROM', '').split(os.pathsep):
        for n in sorted(os.listdir(r)) if r and os.path.isdir(r) else []:
            p = os.path.join(r, n, 'dmsh_vk.lib')
            if not os.path.isfile(p) or os.path.realpath(os.path.dirname(p)) == os.path.realpath(os.path.join(a[3], 'v-' + t.VERSION)): continue   # as the real tool: not its own output
            try: libs.append(delta.Library(p))
            except (OSError, ValueError): pass   # as the real tool: a library it cannot read is skipped
    res = os.environ['DMSH_VK_RESULTS']; os.makedirs(res, exist_ok=True)
    out, made = [], []
    for k in keys:
        s, rp = None, os.path.join(res, k + '.r')
        if os.path.isfile(rp):   # a result of an earlier run (the real tool's result cache)
            with open(rp) as f: s = bytes.fromhex(f.read().split()[1])
        if s is None and m and k in m['hashes']:
            for L in libs:
                if k in L.index:
                    _, h, ent = L.hash_of(L.index[k], None)
                    if h == m['hashes'][k]:
                        s = bytes(ent[4][0][2])
                        with open(rp, 'w') as f: f.write('kept ' + s.hex())
        if s is None:
            s = t.translate(g, a[2], k, set(keys)); made.append(k)
            with open(rp, 'w') as f: f.write('made ' + s.hex())
        out.append((k, s))
    t.write_lib(os.path.join(a[3], 'v-' + t.VERSION, 'dmsh_vk.lib'), out, inputs, game=g.id)
    with open(os.environ['FAKE_LOG'], 'a') as f: f.write(json.dumps(made) + '\\n')
''')


FAKE_PAD = textwrap.dedent('''
    # as FAKE, but as vk_android_build.py translate_keys pads: the pixel shaders first, then each vertex shader padded with its paired pixel shaders as this run
    # has them (translated now, a result of an earlier run, or kept from an older library: PS_INPUTS), not with their facts
    import json, os, sys
    sys.path[:0] = [{tools!r}, {games!r}, {reuse!r}, {tests!r}]
    import game, dmsh_chain, vk_android_delta as delta, test_shader_reuse as t
    a = sys.argv[1:]
    g = game.load(); inputs = dmsh_chain.inputs(g)
    rows = [l.split('\\t') for l in open(g.table('shader_map.tsv')) if l.split('\\t')[0].endswith('.bin')]
    absent = set(open(os.environ['DMSH_VK_ABSENT_FILE']).read().split()) if os.environ.get('DMSH_VK_ABSENT_FILE') else set()
    keys = [c[3] for c in rows if c[0].startswith('ps') and c[3] not in absent] + [c[3] for c in rows if c[0].startswith('vs') and c[3] not in absent]
    try: m = delta.parse_manifest(open(g.table('vk_delta.tsv')).read())
    except (OSError, ValueError): m = None
    if m and (m['version'], m['game'], m['inputs']) != (t.VERSION, g.id, inputs): m = None
    libs = []
    for r in os.environ.get('DMSH_VK_DELTA_FROM', '').split(os.pathsep):
        for n in sorted(os.listdir(r)) if r and os.path.isdir(r) else []:
            p = os.path.join(r, n, 'dmsh_vk.lib')
            if not os.path.isfile(p) or os.path.realpath(os.path.dirname(p)) == os.path.realpath(os.path.join(a[3], 'v-' + t.VERSION)): continue
            try: libs.append(delta.Library(p))
            except (OSError, ValueError): pass
    res = os.environ['DMSH_VK_RESULTS']; os.makedirs(res, exist_ok=True)
    out, made = {{}}, []
    for k in keys:
        s, rp = None, os.path.join(res, k + '.r')
        if os.path.isfile(rp):   # the real tool's result cache: keyed by the tools, the inputs and the key, not by the pixel shaders that padded it
            with open(rp) as f: s = bytes.fromhex(f.read().split()[1])
        if s is None and m and k in m['hashes']:
            for L in libs:
                if k in L.index:
                    _, h, ent = L.hash_of(L.index[k], None)
                    if h == m['hashes'][k]:
                        s = bytes(ent[4][0][2])
                        with open(rp, 'w') as f: f.write('kept ' + s.hex())
                        break
        if s is None:
            s = t.translate_padded(g, a[2], k, out); made.append(k)
            with open(rp, 'w') as f: f.write('made ' + s.hex())
        out[k] = s
    t.write_lib(os.path.join(a[3], 'v-' + t.VERSION, 'dmsh_vk.lib'), list(out.items()), inputs, game=g.id)
    with open(os.environ['FAKE_LOG'], 'a') as f: f.write(json.dumps(made) + '\\n')
''')


def translate_padded(g, pkg, k, done):
    """FAKE_PAD's translator: a pixel shader from its key, its facts and unseen.tsv; a vertex shader from its key, its facts and its paired pixel shaders' output
    as this run has it (done)"""
    d = sr.Deps(g, pkg, VERSION, b'')
    if d.stage.get(k) == 'ps':
        unseen = g.table('unseen.tsv').read_text() if g.table('unseen.tsv').exists() else ''
        return spirv_of('\0'.join([k, d.fact_row(k), unseen]))
    return spirv_of('\0'.join([k, d.fact_row(k)] + [hashlib.sha256(done[p]).hexdigest() for p in d.pairs.get(k, ()) if p in done]))


_VIEW = {}


def translate(g, pkg, k, present=None):
    """The stand-in translator: the shader's key, its facts, its pixel shaders' facts (for a vertex shader: the pairs as load_pairs takes them, the split's
    pso_pairs.tsv when the profile has none; of them only those this build makes, present, as vk_android_build.py pads with PS_INPUTS) and unseen.tsv"""
    tag = tuple(p.read_bytes() if p.exists() else b'' for p in (g.table('shader_facts.tsv'), g.table('shader_pairs.tsv'), g.table('unseen.tsv'), g.table('shader_map.tsv'),
                                                               g.table('pso_pairs.tsv'), Path(pkg).parent / 'pso_pairs.tsv'))
    if tag not in _VIEW: _VIEW.clear(); _VIEW[tag] = sr.Deps(g, pkg, VERSION, b'')
    d = _VIEW[tag]
    unseen = tag[2].decode()
    return spirv_of('\0'.join([k, d.fact_row(k)] + [d.fact_row(p) for p in d.pairs.get(k, ()) if present is None or p in present] + [unseen]))


class Base(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp(prefix='pnreuse_'))
        self.prof = self.d / 'games' / GAME; self.prof.mkdir(parents=True)
        (self.prof / 'game.tsv').write_text(f'title_id\t{GAME}\nname\tTest\n')
        self.pkg = self.d / 'split' / 'pkg'; self.pkg.mkdir(parents=True)
        self.pack = self.d / 'pack' / 'shaders'; self.out = self.d / 'lib'; self.res = self.d / 'xlate'; self.reuse = self.d / 'reuse' / GAME   # as no_backup/reuse/<title ID>
        self.log = self.d / 'fake.log'
        fake = self.d / 'fake_build.py'
        fake.write_text(FAKE.format(tools=str(REPO / 'dm/gfx/tools'), games=str(REPO / 'dm/games'), reuse=str(REPO / 'dm/peanuts/reuse'), tests=str(Path(__file__).parent)))
        self.saved_build, self.saved_env = sr.BUILD, dict(os.environ)
        sr.BUILD = fake
        os.environ.update({'DM_GAME': str(self.prof), 'DMSH_VK_DELTA_FROM': str(self.pack), 'DMSH_VK_RESULTS': str(self.res), 'PN_REUSE_DIR': str(self.reuse),
                           'FAKE_LOG': str(self.log)})
        os.environ.pop('PN_REUSE', None)

    def tearDown(self):
        sr.BUILD = self.saved_build
        os.environ.clear(); os.environ.update(self.saved_env)
        shutil.rmtree(self.d, ignore_errors=True)

    def profile(self, vs=200, ps=200, facts=None, pairs=None, unseen=None):
        """A profile of vs vertex and ps pixel shaders: shader_map.tsv, shader_facts.tsv (facts {key: dict}), shader_pairs.tsv (pairs [(vs, ps)])"""
        self.vs = [f'{0x1000 + i:016x}' for i in range(vs)]; self.ps = [f'{0x9000 + i:016x}' for i in range(ps)]
        rows = ['package\tindex\tstage\tdm_ctl_sha16\tsource'] + [f'vs_main.bin\t{i}\tvertex\t{k}\t/a#{i}' for i, k in enumerate(self.vs)] \
            + [f'ps_main.bin\t{i}\tpixel\t{k}\t/b#{i}' for i, k in enumerate(self.ps)]
        (self.prof / 'shader_map.tsv').write_text('\n'.join(rows) + '\n')
        f = {k: {'tex': [1]} for k in self.vs + self.ps}
        f.update(facts or {})
        (self.prof / 'shader_facts.tsv').write_text(''.join(f'{k}\t{json.dumps(v, sort_keys=True, separators=(",", ":"))}\n' for k, v in sorted(f.items())))
        pr = pairs if pairs is not None else [(self.vs[i], self.ps[i]) for i in range(min(vs, ps))]
        (self.prof / 'shader_pairs.tsv').write_text(''.join(f'{v}\t{p}\n' for v, p in pr))
        if unseen is not None: (self.prof / 'unseen.tsv').write_text(unseen)
        elif (self.prof / 'unseen.tsv').exists(): (self.prof / 'unseen.tsv').unlink()

    def build(self, *extra):
        """One shaders step through shader_reuse.main → (exit code, the keys translated per tool run, the library {key: SPIR-V})"""
        if self.log.exists(): self.log.unlink()
        code = sr.main(['--target', 'vulkan', str(self.pkg), str(self.out), *extra])
        runs = [json.loads(l) for l in self.log.read_text().splitlines()] if self.log.exists() else []
        return code, runs, self.library(self.out / f'v-{VERSION}' / 'dmsh_vk.lib')

    def library(self, p):
        L = delta.Library(str(p))
        try: return {k: bytes(L.entry(i)[4][0][2]) for k, i in L.index.items()}
        finally: L.close()

    def full(self):
        """The library a build without any older library makes (what every reuse must equal)"""
        game = sr.load_modules()[0]
        g = game.load()
        return {k: translate(g, str(self.pkg), k) for k in self.vs + self.ps}

    def sample(self, keys):
        """The sample a build checks among keys (spread over the classes of this profile's shaders)"""
        game = sr.load_modules()[0]
        return sr.sample_of(keys, sr.sample_class(sr.Deps(game.load(), str(self.pkg), VERSION, b'')))

    def split_pairs(self, pairs):
        """The split's own pso_pairs.tsv (load_pairs takes it when the profile has none)"""
        (self.pkg.parent / 'pso_pairs.tsv').write_text(''.join(f'{v}\t{p}\n' for v, p in pairs))

    def into_pack(self):
        """The finished build's library goes into the pack (the pack step), as the older library of the next build"""
        dst = self.pack / f'v-{VERSION}'; dst.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(self.out / f'v-{VERSION}' / 'dmsh_vk.lib', dst / 'dmsh_vk.lib')
        shutil.rmtree(self.out, ignore_errors=True)
        shutil.rmtree(self.res, ignore_errors=True)   # a finished job deletes its results (PackService)


class Reuse(Base):
    def test_first_build_translates_all_and_keeps_a_record(self):
        self.profile()
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(len(runs), 1); self.assertEqual(len(runs[0]), 400)
        self.assertEqual(lib, self.full())
        self.assertEqual(len(list((self.reuse / 'shaders').glob('*.tsv'))), 1)

    def test_same_inputs_keeps_all_but_the_sample(self):
        self.profile(); self.build(); self.into_pack()
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(len(runs), 1)
        self.assertEqual(sorted(runs[0]), sorted(self.sample(self.vs + self.ps)))   # only the sample was translated
        self.assertEqual(lib, self.full())
        self.assertFalse((self.res / sr.MARK).exists())
        self.assertFalse((self.reuse / 'profile').exists())

    def test_new_version_rows_translate_only_what_changed(self):
        self.profile(); self.build(); self.into_pack()
        # the APK's profile gains a game version: 20 new pixel shaders, one changed fact, one new pair of an old vertex shader with a new pixel shader
        old_vs, old_ps = list(self.vs), list(self.ps)
        self.profile(ps=220, facts={old_ps[5]: {'tex': [2]}}, pairs=[(old_vs[i], old_ps[i]) for i in range(200)] + [(old_vs[7], f'{0x9000 + 210:016x}')])
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(lib, self.full())
        made = set(runs[0])
        must = set(self.ps[200:]) | {old_ps[5], old_vs[5], old_vs[7]}   # the new ones, the changed one, the vertex shaders whose pixel shaders changed
        self.assertTrue(must <= made, must - made)
        self.assertLessEqual(len(made - must), len(self.sample(old_vs + old_ps)))   # the rest is the sample
        self.assertEqual(len(runs), 1)

    def test_a_change_the_rule_does_not_see_is_caught(self):
        self.profile(); self.build(); self.into_pack()
        self.profile(ps=210, unseen='another translation for every shader\n')
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(len(runs), 2)   # the checked run, then the full one
        self.assertEqual(lib, self.full())
        self.assertEqual(set(runs[1]) | set(runs[0]), set(self.vs + self.ps))   # the second run translated what the first had kept
        padded = {self.vs[i] for i in range(200) if self.ps[i] not in runs[0]}   # vertex shaders whose pixel shader the first run kept (Padding)
        self.assertEqual(set(runs[0]) & set(runs[1]), set(runs[0]) & padded)   # twice only those: the first run's other translations stay as results

    def test_inputs_changed_without_a_record_keeps_nothing(self):
        self.profile(); self.build(); self.into_pack()
        shutil.rmtree(self.reuse / 'shaders')
        self.profile(ps=210)
        code, runs, lib = self.build()
        self.assertEqual((code, len(runs), len(runs[0])), (0, 1, 410))
        self.assertEqual(lib, self.full())

    def test_split_pairs_are_not_taken_for_the_same_inputs(self):
        # a profile without its own pso_pairs.tsv: the split's pads the vertex shaders, and an added DLC can add pairs there while the inputs digest stays
        self.profile(); self.split_pairs([(self.vs[i], self.ps[i + 1]) for i in range(100)]); self.build(); self.into_pack()
        self.split_pairs([(self.vs[i], self.ps[i + 1]) for i in range(100)] + [(self.vs[150], self.ps[60]), (self.vs[151], self.ps[61])])
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(lib, self.full())
        self.assertTrue({self.vs[150], self.vs[151]} <= set(runs[0]))   # their pairs changed: translated, not kept by "same inputs"
        self.assertEqual(len(runs), 1)

    def test_a_pixel_shader_that_comes_with_an_addon(self):
        # the first build without an add-on: two pixel shaders absent (validated against the split), their vertex shaders padded without them
        self.profile()
        absent = self.d / 'absent.txt'; absent.write_text(f'{self.ps[3]}\n{self.ps[4]}\n')
        os.environ['DMSH_VK_ABSENT_FILE'] = str(absent)
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertNotIn(self.ps[3], lib)
        self.into_pack()
        del os.environ['DMSH_VK_ABSENT_FILE']   # the add-on installed: the profile is the same, its pixel shaders are built now
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(lib, self.full())
        self.assertTrue({self.ps[3], self.ps[4], self.vs[3], self.vs[4]} <= set(runs[0]))   # and the vertex shaders they pad
        self.assertEqual(len(runs), 1)

    def test_same_inputs_without_a_record_keeps_nothing(self):
        self.profile(); self.build(); self.into_pack()
        shutil.rmtree(self.reuse / 'shaders')   # a pack made before this app: no record
        code, runs, lib = self.build()
        self.assertEqual((code, len(runs[0])), (0, 400))   # the split's pairs could have changed: nothing kept
        self.into_pack()
        (self.prof / 'pso_pairs.tsv').write_text(''.join(f'{self.vs[i]}\t{self.ps[i + 2]}\n' for i in range(50)))
        self.build(); self.into_pack()
        code, runs, lib = self.build()
        self.assertEqual(sorted(runs[0]), sorted(self.sample(self.vs + self.ps)))   # the profile's own pairs are in the inputs digest: all but the sample kept
        self.into_pack()
        shutil.rmtree(self.reuse / 'shaders')
        code, runs, lib = self.build()
        self.assertEqual((code, len(runs[0])), (0, 400))   # without a record not even then: the translator it was made with cannot be told (Records)
        self.assertEqual(lib, self.full())

    def test_try_anyway_keeps_no_shader_this_dump_lacks(self):
        # Try anyway: the split need not hold every shader of the profile's map, and vk_android_build.py leaves out one its package lacks. A kept one never
        # goes through that check: kept from an older library (a dump with the add-on), it padded the vertex shaders, and every full check after it found the
        # library other than a translation of this dump and replaced it (found in review)
        self.profile(); self.build(); self.into_pack()
        rows = (self.prof / 'shader_map.tsv').read_text().splitlines()
        (self.pkg.parent / 'shader_map.tsv').write_text('\n'.join(l for l in rows if self.ps[3] not in l) + '\n')   # this dump's split: no ps[3]
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertNotIn(self.ps[3], lib)
        game = sr.load_modules()[0]
        present = set(self.vs + self.ps) - {self.ps[3]}
        self.assertEqual(lib[self.vs[3]], translate(game.load(), str(self.pkg), self.vs[3], present))   # padded without it

    def test_off(self):
        self.profile(); self.build(); self.into_pack()
        os.environ['PN_REUSE'] = '0'
        code, runs, lib = self.build()
        self.assertEqual((code, len(runs[0])), (0, 400))

    def test_a_stop_before_the_check_leaves_nothing_unchecked(self):
        self.profile(); self.build(); self.into_pack()
        self.res.mkdir(exist_ok=True)
        (self.res / (self.vs[3] + '.r')).write_text('kept by a stopped run')
        (self.res / sr.MARK).write_text(json.dumps([self.vs[3]]))
        os.environ['PN_REUSE'] = '0'
        self.build()
        self.assertTrue((self.res / (self.vs[3] + '.r')).read_text().startswith('made '))   # the stopped run's kept result went; this run translated it
        self.assertFalse((self.res / sr.MARK).exists())

    def test_too_few_to_check_keeps_nothing(self):
        self.profile(vs=10, ps=10); self.build(); self.into_pack()
        code, runs, lib = self.build()
        self.assertEqual(len(runs[0]), 20)

    def test_other_version_library_is_not_used(self):
        self.profile()
        write_lib(self.pack / 'v-aaaaaaaaaaaa' / 'dmsh_vk.lib', list(self.full().items()), '000000000000', version='aaaaaaaaaaaa')
        code, runs, lib = self.build()
        self.assertEqual(len(runs[0]), 400)


class Padding(Base):
    """With FAKE_PAD: a vertex shader translated in a run that kept its pixel shaders is padded with the kept entries' inputs. When the kept shaders turn out
    wrong (the check) or unchecked (a stop before it), that vertex shader's result must not stay for the run after it"""
    def setUp(self):
        super().setUp()
        fake = self.d / 'fake_pad.py'
        fake.write_text(FAKE_PAD.format(tools=str(REPO / 'dm/gfx/tools'), games=str(REPO / 'dm/games'), reuse=str(REPO / 'dm/peanuts/reuse'), tests=str(Path(__file__).parent)))
        sr.BUILD = fake

    def full(self):
        game = sr.load_modules()[0]
        g = game.load()
        done = {}
        for k in self.ps + self.vs: done[k] = translate_padded(g, str(self.pkg), k, done)
        return done

    def test_a_failed_check_translates_the_vertex_shaders_kept_pixel_shaders_padded(self):
        self.profile(); code, _, lib = self.build(); self.assertEqual(lib, self.full()); self.into_pack()
        self.profile(unseen='a change of the pixel shaders the rule does not see\n')
        code, runs, lib = self.build()
        self.assertEqual((code, len(runs)), (0, 2))   # the check failed: the full run after it
        self.assertEqual(lib, self.full())
        padded = {self.vs[i] for i in range(200) if self.ps[i] not in runs[0]} & set(runs[0])   # translated in the checked run against a kept pixel shader
        self.assertTrue(padded and padded <= set(runs[1]))   # translated again

    def test_a_stop_before_the_check_then_a_run_that_keeps_nothing(self):
        self.profile(); self.build(); self.into_pack()
        self.profile(unseen='a change of the pixel shaders the rule does not see\n')
        saved = sr.check

        def stopped(*a, **k): raise KeyboardInterrupt('the step stopped after the library was written, before its check')
        sr.check = stopped
        try:
            with self.assertRaises(KeyboardInterrupt): self.build()
        finally: sr.check = saved
        self.assertTrue((self.res / sr.MARK).exists())
        os.environ['PN_REUSE'] = '0'
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(lib, self.full())


class Leftover(Base):
    """The results folder is the title's (PackService: Outputs.results, no_backup/xlate/<title ID>) and goes only when a job is done: a job that did not finish
    leaves its results to the next job of the title, whose split pairs, absences or older library may be others. vk_android_build.py keys a result by the
    tools, the profile inputs and the key alone"""
    def test_an_addon_after_a_job_that_did_not_finish(self):
        self.profile()
        absent = self.d / 'absent.txt'; absent.write_text(f'{self.ps[3]}\n{self.ps[4]}\n')
        os.environ['DMSH_VK_ABSENT_FILE'] = str(absent)
        self.build()   # job A without the add-on: its shaders step done, the job not (no into_pack: its results stay)
        del os.environ['DMSH_VK_ABSENT_FILE']
        self.out = self.d / 'lib-b'   # job B: the add-on imported, another Build folder
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(lib, self.full())
        self.assertTrue({self.vs[3], self.vs[4]} <= set(runs[0]))   # padded with the add-on's pixel shaders now

    def test_the_sample_is_translated_not_taken_from_an_earlier_job(self):
        self.profile(); self.build(); self.into_pack()
        self.build()   # job A kept and checked; it did not finish: its results stay (its translations and the entries it kept)
        self.profile(unseen='a change the rule and the result key do not see\n')
        self.out = self.d / 'lib-b'
        code, runs, lib = self.build()
        self.assertTrue(self.sample(self.vs + self.ps) <= set(runs[0]))   # the check compared a translation of now, not job A's
        self.assertEqual((code, len(runs)), (0, 2))   # so it failed, and the full run after it
        self.assertEqual(lib, self.full())

    def test_a_resume_after_the_check_translates_nothing_again(self):
        # a pause after the check (heat, battery: the packer runs the step again) kept the sample's translations; they were deleted at every run that kept shaders
        self.profile(); self.build(); self.into_pack()
        saved = sr.note_check

        def stopped(*a, **k): raise KeyboardInterrupt('a pause after the check, before the step ended')
        sr.note_check = stopped
        try:
            with self.assertRaises(KeyboardInterrupt): self.build()
        finally: sr.note_check = saved
        code, runs, lib = self.build()   # the same job again
        self.assertEqual((code, runs), (0, [[]]))
        self.assertEqual(lib, self.full())

    def test_an_off_run_between_jobs_leaves_no_tags_of_another_build(self):
        # job A without the add-on, then job B with it and PN_REUSE=0 (its vertex shaders padded with the add-on's pixel shaders), then job C without it: the tags
        # B left were A's, so C took B's vertex shaders (found in review)
        self.profile()
        sr.BUILD.write_text(FAKE_PAD.format(tools=str(REPO / 'dm/gfx/tools'), games=str(REPO / 'dm/games'), reuse=str(REPO / 'dm/peanuts/reuse'),
                                            tests=str(Path(__file__).parent)))
        absent = self.d / 'absent.txt'; absent.write_text(f'{self.ps[3]}\n{self.ps[4]}\n')
        os.environ['DMSH_VK_ABSENT_FILE'] = str(absent)
        self.build()   # A, not finished
        (self.res / f'{self.vs[3]}.r').unlink()   # (stopped before it got to vs[3])
        del os.environ['DMSH_VK_ABSENT_FILE']
        os.environ['PN_REUSE'] = '0'
        self.build()   # B with the add-on, off, not finished
        del os.environ['PN_REUSE']
        os.environ['DMSH_VK_ABSENT_FILE'] = str(absent)
        code, runs, lib = self.build()   # C
        self.assertEqual(code, 0)
        game = sr.load_modules()[0]
        g, done = game.load(), {}
        for k in self.ps + self.vs:
            if k not in (self.ps[3], self.ps[4]): done[k] = translate_padded(g, str(self.pkg), k, done)
        self.assertEqual(lib, done)

    def test_entries_an_earlier_job_kept_are_no_translation_for_another(self):
        # job A kept shaders and checked them, and did not finish: its results folder holds the older library's entries it kept. Job B (another Build folder)
        # keeps nothing: it took them as translations, unchecked, and its build left no full check (found in review)
        self.profile(); self.build(); self.into_pack()
        self.build()   # A keeps, checks, does not finish
        shutil.rmtree(self.pack.parent); shutil.rmtree(self.reuse / sr.KEEP_DIR)   # nothing for B to keep from
        self.profile(unseen='a change the rule and the result key do not see\n')
        self.out = self.d / 'lib-b'
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(lib, self.full())

    def test_another_translator_takes_no_result_of_before(self):
        # the translator's sources are in neither vk_android_build.py's result key nor DMSH_VK_CHAIN: an app update that brings another translator library
        # must not take an unfinished job's translations
        self.profile(); self.build()
        shutil.rmtree(self.reuse / sr.KEEP_DIR)
        saved = sr.translator_tree
        sr.translator_tree = lambda: 'f' * 16
        try: code, runs, lib = self.build()   # the same job, after the app update
        finally: sr.translator_tree = saved
        self.assertEqual((code, len(runs[0])), (0, 400))

    def test_the_same_job_goes_on_from_its_results(self):
        self.profile(); self.build()   # a job's shaders step, the job not done
        shutil.rmtree(self.reuse / sr.KEEP_DIR)   # no older library: only the results can spare a translation
        code, runs, lib = self.build()   # the same job again (a Resume after the process was killed)
        self.assertEqual((code, runs), (0, [[]]))   # every result taken, none translated again
        self.assertEqual(lib, self.full())


class Kept(Base):
    """The copy of the last library in <PN_REUSE_DIR>/library (keep_library): the next build keeps from it when the pack's library is gone"""
    def copy(self): return self.reuse / sr.KEEP_DIR / f'v-{VERSION}' / 'dmsh_vk.lib'

    def other_title(self, title, mb, age):
        """Another title's kept copy of mb MB, age seconds old (only its size and time count)"""
        f = self.reuse.parent / title / sr.KEEP_DIR / f'v-{VERSION}' / 'dmsh_vk.lib'
        f.parent.mkdir(parents=True); f.write_bytes(b'\0' * int(mb * (1 << 20)))
        t = os.stat(f).st_mtime - age; os.utime(f, (t, t))
        return f

    def test_a_build_keeps_a_copy_of_its_library(self):
        self.profile()
        code, _, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(self.library(self.copy()), lib)
        self.assertEqual(os.environ['DMSH_VK_DELTA_FROM'], str(self.pack))   # the environment as the app set it, for the next step

    def test_the_copy_when_the_packs_library_is_gone(self):
        self.profile(); self.build(); self.into_pack()
        shutil.rmtree(self.pack.parent)   # the build deleted, or another Build folder
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(sorted(runs[0]), sorted(self.sample(self.vs + self.ps)))   # only the sample translated
        self.assertEqual(lib, self.full())

    def test_the_copy_and_the_packs_library_count_once(self):
        self.profile(); self.build(); self.into_pack()
        game, _, d = sr.load_modules()
        os.environ['DMSH_VK_DELTA_FROM'] = os.pathsep.join([str(self.pack), str(self.reuse / sr.KEEP_DIR)])
        libs = sr.older_libraries(d, str(self.out / f'v-{VERSION}'), GAME)
        try: self.assertEqual(len(libs), 1)
        finally:
            for L in libs: L.close()

    def test_a_damaged_copy_is_skipped(self):
        self.profile(); self.build(); self.into_pack()
        self.copy().write_bytes(b'not a library')
        code, runs, lib = self.build()   # kept from the pack's library
        self.assertEqual((code, sorted(runs[0])), (0, sorted(self.sample(self.vs + self.ps))))
        self.assertEqual(self.library(self.copy()), lib)   # and the copy is a good one again
        self.into_pack(); self.copy().write_bytes(b'not a library'); shutil.rmtree(self.pack.parent)
        code, runs, lib = self.build()   # neither: every shader translated
        self.assertEqual((code, len(runs[0])), (0, 400))
        self.assertEqual(lib, self.full())

    def test_only_this_versions_copy_stays(self):
        old = self.reuse / sr.KEEP_DIR / 'v-aaaaaaaaaaaa' / 'dmsh_vk.lib'
        old.parent.mkdir(parents=True); old.write_bytes(b'x')
        self.profile(); self.build()
        self.assertFalse(old.parent.exists())
        self.assertTrue(self.copy().is_file())

    def test_the_copies_of_every_title_together_stay_under_the_cap(self):
        oldest, newer = self.other_title('0100000000BB0000', 1.5, 3600), self.other_title('0100000000CC0000', 1.5, 60)
        os.environ['PN_REUSE_KEEP_MB'] = '2'
        self.profile(); self.build()
        self.assertTrue(self.copy().is_file())
        self.assertFalse(oldest.parent.parent.exists())   # the title built longest ago went
        self.assertTrue(newer.is_file())

    def test_a_library_larger_than_the_cap_is_not_kept(self):
        self.profile(); self.build()
        os.environ['PN_REUSE_KEEP_MB'] = '0'
        self.build()
        self.assertFalse((self.reuse / sr.KEEP_DIR).exists())   # nor an older copy that would no longer be this build's

    def test_a_copy_cut_short_leaves_nothing(self):
        self.profile(); self.build()
        saved = sr.shutil.copyfile

        def full_storage(src, dst):   # the library's copy only (the profile copy goes through copyfile too)
            if Path(dst).name != 'dmsh_vk.tmp': return saved(src, dst)
            Path(dst).write_bytes(b'part of it')
            raise OSError(28, 'No space left on device')
        sr.shutil.copyfile = full_storage
        try: code, _, _ = self.build()
        finally: sr.shutil.copyfile = saved
        self.assertEqual(code, 0)   # the step stands: the copy is only for the next build
        self.assertEqual(list((self.reuse / sr.KEEP_DIR).rglob('*')), [])

    def test_no_copy_when_storage_is_low(self):
        other = self.other_title('0100000000BB0000', 1.5, 3600)
        os.environ['PN_REUSE_KEEP_MB'] = '1'   # this copy would take the other title's place: not when it is not made after all
        saved = sr.KEEP_FREE_MB
        sr.KEEP_FREE_MB = 1 << 40
        try: self.profile(); code, _, _ = self.build()
        finally: sr.KEEP_FREE_MB = saved
        self.assertEqual(code, 0)
        self.assertFalse(self.copy().exists())
        self.assertTrue(other.is_file())

    def test_without_delta_from_the_tools_own_folder_stays(self):
        del os.environ['DMSH_VK_DELTA_FROM']   # a run on a computer: vk_android_build.py takes the other v-<V> folders beside its output
        self.profile(); self.build()
        shutil.rmtree(self.res)
        code, runs, lib = self.build()
        self.assertEqual((code, sorted(runs[0])), (0, sorted(self.sample(self.vs + self.ps))))   # from the copy (the output's own library is not an older one)
        self.assertNotIn('DMSH_VK_DELTA_FROM', os.environ)

    def test_the_environment_is_put_back_after_an_error(self):
        self.profile()
        saved = sr.run_build

        def stops(argv, env): raise RuntimeError('the tool stopped')
        sr.run_build = stops
        try:
            with self.assertRaises(RuntimeError): self.build()
        finally: sr.run_build = saved
        self.assertEqual(os.environ['DMSH_VK_DELTA_FROM'], str(self.pack))


class FullCheck(Base):
    """shader_reuse.py --check: a library a build wrote with kept shaders (check.todo) is translated again in full and compared entry by entry"""
    def check(self):
        if self.log.exists(): self.log.unlink()
        code = sr.main(['--check', '--target', 'vulkan', str(self.pkg), str(self.out)])
        runs = [json.loads(l) for l in self.log.read_text().splitlines()] if self.log.exists() else []
        return code, runs

    def todo(self): return sr.read_todo(str(self.reuse))

    def test_due_only_after_a_build_that_kept_shaders(self):
        self.profile(); self.build()
        self.assertIsNone(self.todo())   # every shader translated: nothing to check
        self.into_pack(); self.build()
        t = self.todo()
        L = delta.Library(str(self.out / f'v-{VERSION}' / 'dmsh_vk.lib'))
        try: self.assertEqual((t['library'], t['kept']), (sr.library_id(L), 400 - len(self.sample(self.vs + self.ps))))
        finally: L.close()

    def test_a_library_that_is_a_full_translation(self):
        self.profile(); self.build(); self.into_pack(); self.build(); self.into_pack()
        code, runs = self.check()
        self.assertEqual(code, 0)
        self.assertEqual(sorted(runs[0]), sorted(self.vs + self.ps))   # every shader translated again, none kept
        self.assertIsNone(self.todo())
        self.assertFalse((self.reuse / sr.CHECK_DIR).exists())
        self.assertEqual(len((self.reuse / sr.CHECKED).read_text().splitlines()), 1)
        self.assertEqual(os.environ['DMSH_VK_DELTA_FROM'], str(self.pack))
        self.assertEqual(self.check(), (0, []))   # then nothing is due

    def test_a_wrong_kept_shader_the_sample_missed(self):
        self.profile()
        (self.prof / 'pso_pairs.tsv').write_text(''.join(f'{self.vs[i]}\t{self.ps[i]}\n' for i in range(50)))   # the profile's own pairs: "same inputs" keeps every entry
        self.build(); self.into_pack()
        lib_path = self.pack / f'v-{VERSION}' / 'dmsh_vk.lib'
        L = delta.Library(str(lib_path)); inputs = L.inputs; L.close()
        wrong = next(k for k in self.vs[60:] if k not in self.sample(self.vs + self.ps))
        lib = self.library(lib_path); lib[wrong] = spirv_of('a translation that went wrong')
        self.went_wrong(lib_path, lib, inputs)
        shutil.rmtree(self.reuse / sr.KEEP_DIR)   # only the pack's library, with the wrong entry
        code, runs, kept_lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(kept_lib[wrong], lib[wrong])   # kept: the sample did not take it
        self.into_pack()
        code, runs = self.check()
        self.assertEqual(code, sr.DIFFER)
        failed = json.loads((self.reuse / sr.FAILED).read_text())
        self.assertEqual(failed['differ'], [wrong])
        self.assertEqual(self.library(lib_path), self.full())   # the full translation took the wrong library's place in the pack
        self.assertEqual(self.library(self.copy()), self.full())   # and is the kept copy
        L = delta.Library(str(lib_path))
        try: self.assertIsNotNone(sr.read_record(str(self.reuse), sr.library_id(L)))   # with its record: the next build keeps from it again
        finally: L.close()
        self.assertIsNone(self.todo())
        self.assertFalse((self.reuse / sr.CHECK_DIR).exists())
        self.assertFalse(lib_path.with_name('dmsh_vk.lib.partial').exists())

    def copy(self): return self.reuse / sr.KEEP_DIR / f'v-{VERSION}' / 'dmsh_vk.lib'

    def went_wrong(self, lib_path, lib, inputs):
        """The pack's library lib with an entry a translation got wrong, as its build made it: the library and its record name the same entry (a rule
        miss, or a translator fault the version did not show), so only the full check can find it"""
        write_lib(lib_path, list(lib.items()), inputs)
        L = delta.Library(str(lib_path))
        try:
            lib_id = sr.library_id(L)
            hs = {k: L.hash_of(i, None)[1] for k, i in L.index.items()}
        finally: L.close()
        rec = sr.read_record(str(self.reuse), lib_id)   # (the same id: the same sizes)
        sr.write_record(str(self.reuse), lib_id, rec['version'], rec['game'], rec['inputs'], rec['tree'], {k: (d, hs[k]) for k, (d, _) in rec['deps'].items()})

    def test_a_wrong_kept_copy_with_the_pack_changed_since(self):
        # the same start as above, then the pack's library replaced by another one before the check
        self.profile()
        (self.prof / 'pso_pairs.tsv').write_text(''.join(f'{self.vs[i]}\t{self.ps[i]}\n' for i in range(50)))
        self.build(); self.into_pack()
        lib_path = self.pack / f'v-{VERSION}' / 'dmsh_vk.lib'
        L = delta.Library(str(lib_path)); inputs = L.inputs; L.close()
        wrong = next(k for k in self.vs[60:] if k not in self.sample(self.vs + self.ps))
        lib = self.library(lib_path); lib[wrong] = spirv_of('a translation that went wrong')
        self.went_wrong(lib_path, lib, inputs)
        shutil.rmtree(self.reuse / sr.KEEP_DIR)
        self.build()   # its library kept the wrong entry, and is the kept copy (not put into the pack)
        other = dict(lib); other[self.vs[0]] = spirv_of('another build of the pack') + bytes(16)   # another size: another library (library_id), as a real one would be
        write_lib(lib_path, list(other.items()), inputs)
        self.assertEqual(self.check()[0], sr.DIFFER)
        self.assertEqual(self.library(lib_path), other)   # the pack holds another library: it stays
        self.assertEqual(self.library(self.copy()), self.full())

    def test_a_stopped_check_goes_on(self):
        self.profile(); self.build(); self.into_pack(); self.build(); self.into_pack()
        saved = sr.run_build

        def stopped(argv, env):   # a game started: the tool is stopped after some shaders
            os.makedirs(env['DMSH_VK_RESULTS'], exist_ok=True)
            return 130
        sr.run_build = stopped
        try: self.assertEqual(self.check()[0], 130)
        finally: sr.run_build = saved
        self.assertIsNotNone(self.todo())
        self.assertTrue((self.reuse / sr.CHECK_DIR / 'results').is_dir())
        self.assertEqual(self.check()[0], 0)
        self.assertIsNone(self.todo())

    def kept_wrong(self):
        """A build that kept an entry a translation got wrong, which the sample did not take; its library in the pack (and the kept copy) → (its path, the key)"""
        self.profile()
        (self.prof / 'pso_pairs.tsv').write_text(''.join(f'{self.vs[i]}\t{self.ps[i]}\n' for i in range(50)))
        self.build(); self.into_pack()
        lib_path = self.pack / f'v-{VERSION}' / 'dmsh_vk.lib'
        L = delta.Library(str(lib_path)); inputs = L.inputs; L.close()
        wrong = next(k for k in self.vs[60:] if k not in self.sample(self.vs + self.ps))
        lib = self.library(lib_path); lib[wrong] = spirv_of('a translation that went wrong')
        self.went_wrong(lib_path, lib, inputs)
        shutil.rmtree(self.reuse / sr.KEEP_DIR)
        self.build(); self.into_pack()
        self.assertEqual(self.library(lib_path)[wrong], lib[wrong])
        return lib_path, wrong

    def test_an_app_update_that_changes_the_profile_drops_the_check(self):
        # the version the same, the profile not (a fact): a translation now is not what the build should have made, so it says nothing of the library
        self.profile(); self.build(); self.into_pack(); self.build(); self.into_pack()
        lib_path = self.pack / f'v-{VERSION}' / 'dmsh_vk.lib'
        before = self.library(lib_path)
        self.profile(facts={self.ps[5]: {'tex': [2]}})
        self.assertEqual(self.check(), (0, []))
        self.assertIsNone(self.todo())
        self.assertEqual(self.library(lib_path), before)   # not replaced
        self.assertFalse((self.reuse / sr.FAILED).exists())
        self.assertTrue(list((self.reuse / 'shaders').glob('*.tsv')))   # nor its records deleted

    def test_another_translator_drops_the_check(self):
        self.profile(); self.build(); self.into_pack(); self.build(); self.into_pack()
        saved = sr.translator_tree
        sr.translator_tree = lambda: 'f' * 16
        try: self.assertEqual(self.check(), (0, []))
        finally: sr.translator_tree = saved
        self.assertIsNone(self.todo())
        self.assertFalse((self.reuse / sr.FAILED).exists())

    def test_storage_full_while_the_full_translation_takes_the_librarys_place(self):
        lib_path, wrong = self.kept_wrong()
        saved = sr.shutil.copyfile

        def full(src, dst):
            if not str(dst).endswith('.partial'): return saved(src, dst)
            Path(dst).write_bytes(b'part of it'); raise OSError(28, 'No space left on device')
        sr.shutil.copyfile = full
        try: code, _ = self.check()
        finally: sr.shutil.copyfile = saved
        self.assertEqual(code, 1)
        self.assertEqual(list(self.pack.rglob('*.partial')), [])
        self.assertIsNotNone(self.todo())   # still due
        self.assertFalse((self.reuse / sr.FAILED).exists())   # nothing replaced: no notice of it
        code, runs = self.check()   # the next check, with room: from the results it kept
        self.assertEqual((code, runs), (sr.DIFFER, [[]]))
        self.assertEqual(self.library(lib_path), self.full())
        failed = json.loads((self.reuse / sr.FAILED).read_text())
        self.assertEqual((failed['replaced'], failed['differ_count'], failed['differ']), (True, 1, [wrong]))

    def test_a_stop_while_the_full_translation_takes_the_librarys_place(self):
        # the stop (a game that starts: an exception in the tool's thread, sent again every 3 s) can land in the copy: its .partial must not stay in the pack
        lib_path, wrong = self.kept_wrong()
        saved = sr.shutil.copyfile

        def stopped(src, dst):
            if not str(dst).endswith('.partial'): return saved(src, dst)
            Path(dst).write_bytes(b'part of it'); raise KeyboardInterrupt('a game started')
        sr.shutil.copyfile = stopped
        try:
            with self.assertRaises(KeyboardInterrupt): self.check()
        finally: sr.shutil.copyfile = saved
        self.assertEqual(list(self.pack.rglob('*.partial')), [])
        self.assertIsNotNone(self.todo())

    def test_a_stop_after_the_library_is_replaced_leaves_no_wrong_copy(self):
        lib_path, wrong = self.kept_wrong()
        self.assertEqual(self.library(self.copy())[wrong], self.library(lib_path)[wrong])   # the kept copy is the wrong library too
        saved = sr.write_record

        def stopped(*a, **k): raise KeyboardInterrupt('a game started right after the library was replaced')
        sr.write_record = stopped
        try:
            with self.assertRaises(KeyboardInterrupt): self.check()
        finally: sr.write_record = saved
        self.assertEqual(self.library(lib_path), self.full())
        self.assertFalse(self.copy().exists())   # gone before the pack's library was replaced: a later build cannot keep from it
        shutil.rmtree(self.pack.parent)   # then another Build folder
        code, runs, lib = self.build()
        self.assertEqual(lib, self.full())

    def test_a_library_another_build_replaced(self):
        self.profile(); self.build(); self.into_pack(); self.build()
        game, dmsh_chain, _ = sr.load_modules()
        (self.reuse / sr.TODO).write_text(json.dumps({'library': 'ab' * 16, 'version': VERSION, 'game': GAME, 'inputs': dmsh_chain.inputs(game.load()),
                                                      'tree': sr.translator_tree(), 'kept': 3}))
        out = io.StringIO()
        with contextlib.redirect_stdout(out): self.assertEqual(self.check(), (0, []))
        self.assertIn('the library to check is gone', out.getvalue())
        self.assertIsNone(self.todo())


class Parts(Base):
    def test_vertex_shader_digest_follows_its_pixel_shaders(self):
        self.profile()
        game = sr.load_modules()[0]
        a = sr.Deps(game.load(), str(self.pkg), VERSION, b'')
        self.profile(facts={self.ps[3]: {'tex': [9]}})
        b = sr.Deps(game.load(), str(self.pkg), VERSION, b'')
        self.assertNotEqual(a.digest(self.vs[3]), b.digest(self.vs[3]))
        self.assertNotEqual(a.digest(self.ps[3]), b.digest(self.ps[3]))
        self.assertEqual(a.digest(self.vs[4]), b.digest(self.vs[4]))
        self.assertNotEqual(a.digest(self.vs[4]), sr.Deps(game.load(), str(self.pkg), VERSION, b'nvn_x\t1\n').digest(self.vs[4]))   # a profile row: every shader

    def test_sample_bounds(self):
        ks = [f'{i:016x}' for i in range(5000)]
        self.assertEqual(len(sr.sample_of(ks)), 100)
        self.assertEqual(len(sr.sample_of(ks[:100])), 16)
        self.assertEqual(sr.sample_of(ks), sr.sample_of(list(reversed(ks))))
        few = ks[:30]   # a class of 30 among 5,000: one in 50 would likely take none of it
        s = sr.sample_of(ks, lambda k: 'few' if k in few else 'many')
        self.assertEqual(len(set(few) & s), sr.SAMPLE_CLASS_MIN)
        self.assertTrue(sr.sample_of(ks) <= s)

    def test_record_round_trip(self):
        sr.write_record(self.reuse, 'ab' * 16, VERSION, GAME, '123456789abc', '0123456789abcdef', {'00000000000000aa': ('cd' * 16, 'ef' * 8)})
        r = sr.read_record(self.reuse, 'ab' * 16)
        self.assertEqual((r['version'], r['game'], r['inputs'], r['tree'], r['deps']),
                         (VERSION, GAME, '123456789abc', '0123456789abcdef', {'00000000000000aa': ('cd' * 16, 'ef' * 8)}))
        self.assertIsNone(sr.read_record(self.reuse, 'ef' * 16))
        p = sr.record_path(self.reuse, 'ab' * 16)
        p.write_text(p.read_text().replace(sr.FORMAT, 'pn-shader-deps 1'))
        self.assertIsNone(sr.read_record(self.reuse, 'ab' * 16))   # a record of the format before: none


class Records(Base):
    """What a record must match besides each shader's part: the translator's sources (xlate_tree.py: not in DMSH_CHAIN, so an app update can change the
    translator library and keep the version) and each entry (library_id hashes the library up to its blob)"""
    def test_another_translator_keeps_nothing(self):
        self.profile()
        (self.prof / 'pso_pairs.tsv').write_text(''.join(f'{self.vs[i]}\t{self.ps[i]}\n' for i in range(50)))   # "same inputs" too
        self.build(); self.into_pack()
        saved = sr.translator_tree
        sr.translator_tree = lambda: 'f' * 16   # an app update: the translator library built from other sources, the version the same
        try: code, runs, lib = self.build()
        finally: sr.translator_tree = saved
        self.assertEqual((code, len(runs[0])), (0, 400))
        self.assertEqual(lib, self.full())

    def test_no_translator_sources_keep_nothing(self):
        self.profile(); self.build(); self.into_pack()
        saved = sr.translator_tree
        sr.translator_tree = lambda: None
        try: code, runs, lib = self.build()
        finally: sr.translator_tree = saved
        self.assertEqual((code, len(runs[0])), (0, 400))

    def test_a_record_of_another_library_with_the_same_tables(self):
        # libraries equal up to their blob (the same keys and sizes) share a library_id: folder A with the split's pairs P1, folder B with P2, which adds a pair
        # of vs[150]; B's record (the same id) says vs[150] was made under P2. Then folder A gets P2 too, its kept copy gone: A's vs[150] was made under P1
        self.profile()
        p1 = [(self.vs[i], self.ps[i + 1]) for i in range(100)]
        self.split_pairs(p1); self.build(); self.into_pack()
        pack_a = self.pack
        self.pack = self.d / 'pack-b' / 'shaders'; os.environ['DMSH_VK_DELTA_FROM'] = str(self.pack)
        shutil.rmtree(self.reuse / sr.KEEP_DIR)
        self.split_pairs(p1 + [(self.vs[150], self.ps[60])]); self.build(); self.into_pack()
        ids = []
        for p in (pack_a, self.pack):
            L = delta.Library(str(p / f'v-{VERSION}' / 'dmsh_vk.lib')); ids.append(sr.library_id(L)); L.close()
        self.assertEqual(ids[0], ids[1])
        self.pack = pack_a; os.environ['DMSH_VK_DELTA_FROM'] = str(self.pack)
        shutil.rmtree(self.reuse / sr.KEEP_DIR)
        code, runs, lib = self.build()
        self.assertEqual(code, 0)
        self.assertEqual(lib, self.full())
        self.assertIn(self.vs[150], runs[0])


if __name__ == '__main__':
    unittest.main()
