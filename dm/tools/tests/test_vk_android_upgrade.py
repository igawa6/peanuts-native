"""vk_android_upgrade.py: a shader library of an older DMSH_VK_CHAIN rewritten into this tree's version without the translator. Small synthetic libraries written by
vk_android_build.py's own write_library (through the upgrader's borrow): the hop path for each known version and unknown ones (exit 3), the unchecked marker, a dual module made
again as dual_source of the stored base module, a type-2 sampler record rewritten, the header restamped, and no partial file after an error. No game files needed."""  # PEANUTS: the in-place shader upgrade (0.1.8)
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS))
import dmsh_chain   # noqa: E402
import vk_android_delta as delta   # noqa: E402
import vk_android_spv as spv   # noqa: E402
import vk_android_upgrade as U   # noqa: E402

VK = delta.VK
UPGRADE = TOOLS / 'vk_android_upgrade.py'
OLD, MID, SAMP = 'c679ddfbc5db', 'c9337a3f4e01', '68162be2976c'
OLD_ABI = '3d4e8bb1e1e8'
NEW, NEW_ABI = U.committed('DMSH_VK_CHAIN'), U.committed('DMSH_VK_ABI')
T2 = 'fp_t_cb720898_2000005A'      # handle type 2: sampler ID in uniform slot 7, byte 0 (test_vk_android_type2_sampler.py)
T3 = 'fp_t_cb65538_3041005C'       # handle type 3: left alone

# A dual-source pixel shader as the translator writes one (test_vk_android_abi.py FS_DUAL), with two combined image samplers placed at set SET_DRAW, bindings 5 and 6
FS = f'''OpCapability Shader
OpMemoryModel Logical GLSL450
OpEntryPoint Fragment %main "main" %o0 %o1
OpExecutionMode %main OriginUpperLeft
OpName %t2 "{T2}"
OpName %t3 "{T3}"
OpDecorate %o0 Location 0
OpDecorate %o1 Location 1
OpDecorate %t2 DescriptorSet {VK['SET_DRAW']}
OpDecorate %t2 Binding 5
OpDecorate %t3 DescriptorSet {VK['SET_DRAW']}
OpDecorate %t3 Binding 6
%void = OpTypeVoid
%fn = OpTypeFunction %void
%f32 = OpTypeFloat 32
%v4 = OpTypeVector %f32 4
%img = OpTypeImage %f32 2D 0 0 0 1 Unknown
%simg = OpTypeSampledImage %img
%psimg = OpTypePointer UniformConstant %simg
%t2 = OpVariable %psimg UniformConstant
%t3 = OpVariable %psimg UniformConstant
%pv4 = OpTypePointer Output %v4
%pf = OpTypePointer Output %f32
%o0 = OpVariable %pv4 Output
%o1 = OpVariable %pv4 Output
%int = OpTypeInt 32 1
%c0 = OpConstant %int 0
%c1 = OpConstant %int 1
%f0 = OpConstant %f32 0
%f1 = OpConstant %f32 1
%main = OpFunction %void None %fn
%l = OpLabel
%a0 = OpAccessChain %pf %o0 %c0
OpStore %a0 %f0
%a1 = OpAccessChain %pf %o0 %c1
OpStore %a1 %f1
%b0 = OpAccessChain %pf %o1 %c0
OpStore %b0 %f0
%b1 = OpAccessChain %pf %o1 %c1
OpStore %b1 %f1
OpReturn
OpFunctionEnd
'''


def assemble(text):
    with tempfile.TemporaryDirectory() as d:
        (Path(d) / 'a.spvasm').write_text(text)
        subprocess.run(['spirv-as', '--target-env', 'spv1.3', str(Path(d) / 'a.spvasm'), '-o', str(Path(d) / 'a.spv')], check=True)
        return (Path(d) / 'a.spv').read_bytes()


def old_dual(base):
    """MOD_DUAL as the c679 tools made it: output 1 only moved to Location 0, Index 1"""
    m = spv.Module(base)
    var = m.io(spv.SC_OUTPUT)[1][0]
    for k, x in enumerate(m.ins):
        if x[0] & 0xffff == spv.OP_DECORATE and x[1] == var and x[2] == spv.DEC_LOCATION:
            x[3] = 0; m.ins.insert(k + 1, [4 << 16 | spv.OP_DECORATE, var, spv.DEC_INDEX, 1]); break
    return m.data()


def mod(variant, data): return (variant, *U.packed(data))


def rec(binding, sk, sreg, soff, flags=0):
    """A combined TEX binding record (vk_android_build.py entry_parts' layout)"""
    return U.BIND.pack(VK['KIND_TEX'], spv.VK_COMBINED, binding, VK['SET_DRAW'], flags, 1, 3, 0, sk, 1, sreg, soff, 0, 0)


def entry(key, stage, nbinds, nmods, c1=b''):
    return delta.ENTRY.pack(key, 0, stage, nmods, 0, 0, 0, nbinds, 0, 1, 1, 1, 3 if stage == VK['STAGE_FS'] else 0, 0, 0, 0, len(c1), 0)


def write_lib(path, version, abi, ents, chain=None):
    """A library as vk_android_build.py write_library writes it: ents [(key, stage, [binding records], c1, [(variant, size, stored, codec)])]"""
    L = types.SimpleNamespace(game='0100000000010000', chain=chain or dmsh_chain.version(), inputs='0123456789ab', sg=VK['SUBGROUP_SIZE'], sa=VK['SSBO_ALIGN'])
    U.write(L, [[f'{k:016x}', bytearray(entry(k, st, len(rs), len(ms), c1)), b''.join(rs), c1, [list(m) for m in ms]] for k, st, rs, c1, ms in ents], str(path), version, abi)


@unittest.skipUnless(shutil.which('spirv-as'), 'spirv-as (SPIRV-Tools) not on PATH')
class Upgrade(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp()); self.addCleanup(shutil.rmtree, self.d, True)
        self.base = assemble(FS)
        self.vs = assemble(FS.replace('OpEntryPoint Fragment %main "main" %o0 %o1', 'OpEntryPoint Fragment %main "main" %o0').replace('OpDecorate %o1 Location 1', 'OpDecorate %o1 Location 2'))

    def old_lib(self, version=OLD, abi=OLD_ABI, name='old/dmsh_vk.lib'):
        p = self.d / name
        write_lib(p, version, abi, [
            (0x10, VK['STAGE_FS'], [rec(5, VK['KIND_SAMP'], -1, 0), rec(6, VK['KIND_SAMP_ID'], 3, 0)], b'\x01\x02\x03',
             [mod(VK['MOD_BASE'], self.base), mod(VK['MOD_DUAL'], old_dual(self.base))]),
            (0x20, VK['STAGE_VS'], [rec(6, VK['KIND_SAMP_ID'], 3, 0)], b'', [mod(VK['MOD_BASE'], self.vs)])])
        return p

    def cli(self, *args):
        r = subprocess.run([sys.executable, str(UPGRADE), *map(str, args)], capture_output=True, text=True, env=dict(os.environ, J='2'))
        return r.returncode, r.stdout

    def test_path_known_versions(self):
        self.assertEqual(self.cli('--path', self.old_lib()), (0, f'{OLD} {MID} {SAMP} {NEW}\n'))
        self.assertEqual(self.cli('--path', self.old_lib(MID, name='mid/dmsh_vk.lib')), (0, f'{MID} {SAMP} {NEW}\n'))
        self.assertEqual(self.cli('--path', self.old_lib(SAMP, NEW_ABI, name='samp/dmsh_vk.lib')), (0, f'{SAMP} {NEW}\n'))

    def test_domain_shader_refused(self):
        """68162be2976c -> 327c6383b614 (bb3158fb) reads a domain shader's control section, which the library does not keep: a library with a domain shader has no path"""
        p = self.d / 'ds/dmsh_vk.lib'
        write_lib(p, OLD, OLD_ABI, [(0x20, VK['STAGE_VS'], [], b'', [mod(VK['MOD_BASE'], self.vs)]), (0x30, VK['STAGE_TES'], [], b'', [mod(VK['MOD_BASE'], self.vs)])])
        code, out = self.cli('--path', p)
        self.assertEqual(code, 3); self.assertIn('stage 2', out)
        self.assertEqual(self.cli(p, self.d / 'ds-new/dmsh_vk.lib')[0], 3); self.assertFalse((self.d / 'ds-new').exists())

    def test_path_refused(self):
        for version, abi in (('deadbeef0000', OLD_ABI), (NEW, NEW_ABI), (OLD, NEW_ABI), (OLD, '')):
            p = self.old_lib(version, abi, name=f'{version}-{abi or "none"}/dmsh_vk.lib')
            self.assertEqual(self.cli('--path', p)[0], 3, (version, abi))
            out = self.d / f'out-{version}-{abi}' / 'dmsh_vk.lib'
            self.assertEqual(self.cli(p, out)[0], 3); self.assertFalse(out.parent.exists())
        bad = self.d / 'bad.lib'; bad.write_bytes(b'\0' * 200)
        self.assertEqual(self.cli('--path', bad)[0], 3)   # not a library
        self.assertEqual(self.cli('--path', self.d / 'missing.lib')[0], 1)   # a read error

    def test_other_chain_refused(self):
        p = self.d / 'chain/dmsh_vk.lib'
        write_lib(p, OLD, OLD_ABI, [(0x20, VK['STAGE_VS'], [], b'', [mod(VK['MOD_BASE'], self.vs)])], chain='000000000000')
        self.assertEqual(self.cli('--path', p)[0], 3)

    def test_unchecked_refused(self):
        p = self.old_lib(); Path(str(p) + U.UNCHECKED).write_text('')
        self.assertEqual(self.cli('--path', p)[0], 3)
        out = self.d / 'new/dmsh_vk.lib'
        self.assertEqual(self.cli(p, out)[0], 3); self.assertFalse(out.exists())

    def test_unchecked_marker_names_its_library(self):
        # the marker refuses the library whose id it holds (shader_reuse.py library_id), not a library rebuilt at that path since
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'peanuts/reuse'))
        try: import shader_reuse as sr
        finally: sys.path.pop(0)
        p = self.old_lib(); L = delta.Library(str(p)); lid = sr.library_id(L); L.close()
        self.assertEqual(U.library_id(str(p)), lid)
        m = Path(str(p) + U.UNCHECKED)
        m.write_text('0' * 32 + '\n')
        self.assertEqual(self.cli('--path', p)[0], 0)   # another library's marker
        m.write_text('0' * 32 + '\n' + lid + '\n')
        self.assertEqual(self.cli('--path', p)[0], 3)
        m.write_text('kept shaders never checked\n')   # no id: refused whatever it was for
        self.assertEqual(self.cli('--path', p)[0], 3)

    def test_other_inputs_refused(self):
        p = self.old_lib()
        run = lambda inputs: subprocess.run([sys.executable, str(UPGRADE), '--path', str(p)], capture_output=True, text=True, env=dict(os.environ, J='2', DM_VK_UPGRADE_INPUTS=inputs))
        self.assertEqual(run('0123456789ab').returncode, 0)   # the library's own
        r = run('ba9876543210')
        self.assertEqual(r.returncode, 3); self.assertIn('shader inputs', r.stdout)
        self.assertEqual(run('unknown').returncode, 0)   # an app without the digest checks nothing more

    def test_stale_marker_of_the_target_goes(self):
        old = self.old_lib(); new = self.d / 'stale/v/dmsh_vk.lib'
        new.parent.mkdir(parents=True); Path(str(new) + U.UNCHECKED).write_text('f' * 32 + '\n')
        self.assertEqual(self.cli(old, new)[0], 0)
        self.assertFalse(Path(str(new) + U.UNCHECKED).exists())

    def test_upgrade_is_the_new_build(self):
        """Dual module = dual_source(Module(base)), the type-2 record SAMP_UBO (7, 0) + BF_SAMP_RAW, everything else byte for byte, header version and abi: the whole
        file equal to the library the new tools' writer makes from the same entries"""
        old = self.old_lib(); new = self.d / 'new/v/dmsh_vk.lib'
        code, out = self.cli(old, new)
        self.assertEqual(code, 0, out)
        self.assertIn(f'upgraded 0100000000010000 v-{OLD} -> v-{NEW}: 2 entries, 1 rewritten (1 dual, 1 sampler)', out)
        self.assertIn('stage: upgrading the shaders', out); self.assertIn('progress: 2/2', out)
        d = spv.Module(self.base); self.assertTrue(spv.dual_source(d))
        want = self.d / 'want/dmsh_vk.lib'
        write_lib(want, NEW, NEW_ABI, [
            (0x10, VK['STAGE_FS'], [rec(5, VK['KIND_SAMP_UBO'], 7, 0, VK['BF_SAMP_RAW']), rec(6, VK['KIND_SAMP_ID'], 3, 0)], b'\x01\x02\x03',
             [mod(VK['MOD_BASE'], self.base), mod(VK['MOD_DUAL'], d.data())]),
            (0x20, VK['STAGE_VS'], [rec(6, VK['KIND_SAMP_ID'], 3, 0)], b'', [mod(VK['MOD_BASE'], self.vs)])])
        self.assertEqual(new.read_bytes(), want.read_bytes())
        L = delta.Library(str(new))
        self.assertEqual((L.version, L.abi, L.chain, L.game, L.inputs), (NEW, NEW_ABI, dmsh_chain.version(), '0100000000010000', '0123456789ab'))
        _, _, binds, c1, mods = L.entry(L.index['0000000000000010'])
        self.assertEqual(delta.spirv(mods[1][2], mods[1][3], U.zstd), d.data())
        r = U.BIND.unpack_from(binds, 0)
        self.assertEqual((r[8], r[10], r[11], r[4] & VK['BF_SAMP_RAW']), (VK['KIND_SAMP_UBO'], 7, 0, VK['BF_SAMP_RAW']))
        self.assertEqual(binds[U.BIND.size:], rec(6, VK['KIND_SAMP_ID'], 3, 0))   # type 3: unchanged
        self.assertEqual(L.entry(L.index['0000000000000020'])[1:3], delta.Library(str(old)).entry(1)[1:3])
        L.close()
        self.assertEqual(dmsh_chain.read_stamp(str(new.parent)), {'game': '0100000000010000', 'inputs': '0123456789ab'})
        self.assertEqual(sorted(os.listdir(new.parent)), ['dmsh_vk.lib', 'inputs.tsv'])

    def test_dual_declined_drops_the_module(self):
        """A shader using output 1 in a way the new dual_source does not rewrite (here a function call argument): its MOD_DUAL leaves the entry, nmods with it"""
        ent = ['0000000000000010', bytearray(entry(0x10, VK['STAGE_FS'], 0, 2)), b'', b'', [[VK['MOD_BASE'], *U.packed(self.base)], [VK['MOD_DUAL'], 4, b'x', VK['CODEC_RAW']]]]
        orig = spv.dual_source
        spv.dual_source = lambda m: False
        try: self.assertEqual(U.hop_dual_store(ent, []), {'dual'})
        finally: spv.dual_source = orig
        self.assertEqual([m[0] for m in ent[4]], [VK['MOD_BASE']]); self.assertEqual(ent[1][17], 1)

    def test_no_partial_file(self):
        old = self.old_lib(); new = self.d / 'fresh/dir/dmsh_vk.lib'
        orig, calls = U.stored_bytes, [0]
        def failing(s):
            calls[0] += 1
            if calls[0] == 2: raise OSError('disk full')
            return orig(s)
        U.stored_bytes = failing
        try:
            with self.assertRaises(OSError): U.upgrade(str(old), str(new), 2)
        finally: U.stored_bytes = orig
        self.assertFalse(new.parent.exists())   # the .tmp removed, and the directory made for it
        new.parent.mkdir(parents=True)
        U.stored_bytes = failing; calls[0] = 0
        try:
            with self.assertRaises(OSError): U.upgrade(str(old), str(new), 2)
        finally: U.stored_bytes = orig
        self.assertEqual(os.listdir(new.parent), [])   # an existing directory stays, empty


class Table(unittest.TestCase):
    def test_hops_reach_this_tree(self):
        """Every hop's versions are 12 hex digits and the table reaches this tree's DMSH_VK_CHAIN with its ABI from c679ddfbc5db (0.1.7)"""
        for h in U.HOPS:
            self.assertRegex(h[0] + h[1] + h[2] + h[3], r'^[0-9a-f]{48}$')
        hops = U.path_of(OLD, OLD_ABI)
        self.assertEqual([h[1] for h in hops][-1], NEW)
        self.assertEqual(hops[-1][3], delta.abi_digest())

    def test_refusal(self):
        """A hop that cannot be derived for some entries refuses libraries holding them (has_stage)"""
        L = types.SimpleNamespace(ne=2, eo=0, m=bytes(16) + bytes([VK['STAGE_TES']]) + bytes(47) + bytes(64), path='x')
        hops = [('aaaaaaaaaaaa', 'bbbbbbbbbbbb', OLD_ABI, OLD_ABI, U.pure_restamp, U.has_stage(VK['STAGE_TES']))]
        with self.assertRaises(U.NoPath): U.refusals(L, hops)
        L.m = bytes(128); U.refusals(L, hops)

    def test_malformed_spirv_refused(self):
        """A module whose words run out inside an instruction, or that is no whole number of words, is a refusal (exit 3), not an IndexError (exit 1)"""
        head = struct.pack('<5I', 0x07230203, 0x10300, 0, 100, 0)
        name = struct.pack('<3I', 4 << 16 | 5, 7, 0x41) + b'\0' * 4   # OpName %7 "A"
        self.assertEqual(U.descriptor_names(head + name), {})
        for bad in (head + name[:8], head + name + b'\1', head[:12], head + struct.pack('<I', 0x0005)):
            with self.subTest(bad=bad.hex()), self.assertRaises(U.NoPath): U.descriptor_names(bad)

    def test_not_a_chain_file(self):
        self.assertNotIn('tools/vk_android_upgrade.py', (TOOLS / 'vk_android_build.py').read_text().split('VK_FILES = ')[1].split('\n')[0])


if __name__ == '__main__':
    unittest.main()
