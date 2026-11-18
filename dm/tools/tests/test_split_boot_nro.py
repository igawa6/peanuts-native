#!/usr/bin/env python3
"""The NROs the shader split reads (dm/gfx/tools/shader_split.py runtime_nros): DM_NRO_DIR's when it is set (the desktop packer's collection, the NSP script's capture),
else the ones a boot stub packs into main that an NRR registers (dm/tools/boot_nro.py, nro_collect.boot_stub_nros). No RomFS file holds those, so without them the split
of Mario Tennis Fever finds neither its archive's file table nor the shaders in the game's own code; the phone's Prepare runs the split without DM_NRO_DIR."""
import hashlib, os, struct, sys, tempfile, unittest
from unittest import mock
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, '..'))
sys.path.insert(0, os.path.join(here, '..', '..', 'gfx', 'tools'))
sys.path.insert(0, here)
os.environ.setdefault('DM_GAME', '0400D4201C6EA000')
import boot_nro, shader_split
from test_boot_nro import put
from test_split_cra import Image


def nrr(hashes):   # a version 1 NRR (nro_collect.nrr_hashes)
    d = bytearray(0x4c0 + 8); d[:4] = b'NRR0'; d[5] = 1
    struct.pack_into('<II', d, 0x4c0, len(d), len(hashes))
    for h in hashes: d += h
    return bytes(d)


class RuntimeNros(unittest.TestCase):
    def dump(self, t, registered, decoded):
        """<t>/romfs with an NRR registering `registered`, <t>/exefs/main of a build boot_nro knows that decodes to `decoded`"""
        rom, exe = os.path.join(t, 'romfs'), os.path.join(t, 'exefs')
        os.makedirs(os.path.join(rom, '.nrr')); os.makedirs(exe)
        put(os.path.join(exe, 'main'), b'NSO0' + bytes(0x3c) + bytes.fromhex('ab' * 16) + bytes(0x10))
        put(os.path.join(rom, '.nrr', 'a.nrr'), nrr([hashlib.sha256(b).digest() for b in registered]))
        return rom, os.path.join(exe, 'main')

    def patched(self, decoded):
        return [mock.patch.dict(boot_nro.BUILDS, {'ab' * 16: ('test', 0, [])}),
                mock.patch.object(boot_nro, 'nros', lambda d, n: [(f'main part {i}', b) for i, b in enumerate(decoded)] if n == 'main' else [])]

    def run_with(self, patches, fn):
        for p in patches: p.start()
        try: return fn()
        finally:
            for p in reversed(patches): p.stop()

    def test_boot_stub_nro_without_dm_nro_dir(self):
        code, stray = Image([(0, 0, '', -1, 0, 0)]).bytes, b'not registered'
        with tempfile.TemporaryDirectory() as t, mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop('DM_NRO_DIR', None)
            rom, main = self.dump(t, [code], [code, stray])
            got = self.run_with(self.patched([code, stray]), lambda: shader_split.runtime_nros(rom, main))
        self.assertEqual([b for _, b in got], [code])   # registered only: the stray decode is not the game's

    def test_names_follow_the_collector(self):   # nro_collect.py names them nro<i> in hash order: the map's source column must be the desktop split's
        a, b = b'NRO first' + bytes(8), b'NRO second' + bytes(8)
        with tempfile.TemporaryDirectory() as t, mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop('DM_NRO_DIR', None)
            rom, main = self.dump(t, [a, b], [a, b])
            got = self.run_with(self.patched([a, b]), lambda: shader_split.runtime_nros(rom, main))
        order = sorted([a, b], key=lambda x: hashlib.sha256(x).digest())
        self.assertEqual(got, [('nro0.nro', order[0]), ('nro1.nro', order[1])])

    def test_no_nrr_no_nro(self):
        with tempfile.TemporaryDirectory() as t, mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop('DM_NRO_DIR', None)
            rom, main = self.dump(t, [], [b'x'])
            os.remove(os.path.join(rom, '.nrr', 'a.nrr'))
            self.assertEqual(self.run_with(self.patched([b'x']), lambda: shader_split.runtime_nros(rom, main)), [])

    def test_dm_nro_dir_wins(self):
        with tempfile.TemporaryDirectory() as t:
            rom, main = self.dump(t, [b'boot'], [b'boot'])
            d = os.path.join(t, 'collected'); os.makedirs(d)
            put(os.path.join(d, 'nro0.nro'), b'collected')
            with mock.patch.dict(os.environ, {'DM_NRO_DIR': d}):
                got = self.run_with(self.patched([b'boot']), lambda: shader_split.runtime_nros(rom, main))
        self.assertEqual(got, [('nro0.nro', b'collected')])

    def test_cra_table_from_the_given_nros(self):
        rows = [(0, 0, '', -1, 0, 0), (0, 0, 'shader/font/$', 0, 0x20, 0x100)]
        with tempfile.TemporaryDirectory() as t:
            arc = os.path.join(t, '42'); put(arc, shader_split.CRA + bytes(0x400))
            with mock.patch.dict(os.environ, {}, clear=False):
                os.environ.pop('DM_NRO_DIR', None)
                jobs, arcs = shader_split.cra_jobs([(arc, '42')], nros=[('main part 0', Image(rows).bytes)])
        self.assertEqual(arcs, {arc})
        self.assertEqual([e[1] for _, _, es in jobs for e in es], ['shader/font/font.narc'])

    def test_scan_jobs_passes_the_nros(self):   # the split's main reaches cra_jobs through scan_jobs
        rows = [(0, 0, '', -1, 0, 0), (0, 0, 'shader/font/$', 0, 0x20, 0x100)]
        with tempfile.TemporaryDirectory() as t:
            arc = os.path.join(t, '42'); put(arc, shader_split.CRA + bytes(0x400))
            with mock.patch.dict(os.environ, {}, clear=False):
                os.environ.pop('DM_NRO_DIR', None)
                big, todo = shader_split.scan_jobs([(arc, '42')], nros=[('main part 0', Image(rows).bytes)])
        self.assertTrue(any(j[0] == arc for j in big), big)


if __name__ == '__main__':
    unittest.main()
