"""PEANUTS(upstream-candidate): a domain shader's tessellation mode on Vulkan. The translator compiles every domain shader as triangles, equal spacing, clockwise; the game's mode is in
the shader's control section (+0x738 primitive mode, +0x73c spacing, +0x744 vertex order, OpenGL enums), and vk_android_build.py tess_mode_read / tess_modes_set put it into the
module's execution modes. No game files needed."""
import re
import struct
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS))
import vk_android_spv as spv   # noqa: E402

BUILD = TOOLS / 'vk_android_build.py'


def helpers():
    """tess_mode_read, tess_modes_set and their tables, from the script's source (vk_android_build.py refuses to be imported)"""
    src = BUILD.read_text()
    a = src.index('TESS_MODES = {}'); b = src.index('\n# PEANUTS(upstream-candidate) end', a)
    ns = {'struct': struct, 're': re}; exec(src[a:b], ns)
    return ns


def record(prim, spacing, order):
    """A package record's control section (from the 'BTCDBTCD' magic 34127698 on) with the three words set"""
    ctl = bytearray(0x800); ctl[0:4] = bytes.fromhex('34127698')
    struct.pack_into('<2I', ctl, 0x738, prim, spacing); struct.pack_into('<I', ctl, 0x744, order)
    return b'\0' * 0x40 + bytes(ctl)


def domain_module():
    """A minimal SPIR-V domain module: OpEntryPoint TessellationEvaluation %1 "main", then the translator's three execution modes"""
    name = spv.lit_string('main')
    ins = [[(3 + len(name)) << 16 | 15, 2, 1, *name]]                              # OpEntryPoint TessellationEvaluation %1 "main"
    ins += [[3 << 16 | 16, 1, mode] for mode in (22, 1, 4)]                         # OpExecutionMode %1 Triangles / SpacingEqual / VertexOrderCw
    words = [spv.MAGIC, 0x10000, 0, 2, 0] + [w for x in ins for w in x]
    return spv.Module(struct.pack('<%dI' % len(words), *words))


def modes(m): return sorted(x[0] for x in m.modes)   # (Module.modes: each mode without its entry point's id)


class TessMode(unittest.TestCase):
    def test_quads_fractional_even(self):   # The Witcher 3's terrain domains
        h = helpers(); h['tess_mode_read']('k', record(0x0007, 0x8E7C, 0x0900))
        self.assertEqual(h['TESS_MODES']['k'], (24, 2, 4))
        m = domain_module(); self.assertEqual(h['tess_modes_set'](m, h['TESS_MODES']['k']), 2)
        self.assertEqual(modes(m), [2, 4, 24])   # SpacingFractionalEven, VertexOrderCw, Quads

    def test_ccw_fractional_odd(self):
        h = helpers(); h['tess_mode_read']('k', record(0x0004, 0x8E7B, 0x0901))
        m = domain_module(); self.assertEqual(h['tess_modes_set'](m, h['TESS_MODES']['k']), 2)
        self.assertEqual(modes(m), [3, 5, 22])

    def test_default_unchanged(self):   # the translator's answer was already right: nothing changes
        h = helpers(); h['tess_mode_read']('k', record(0x0004, 0x0202, 0x0900))
        m = domain_module(); self.assertEqual(h['tess_modes_set'](m, h['TESS_MODES']['k']), 0)

    def test_unknown_values_keep_the_translator_s(self):
        h = helpers(); h['tess_mode_read']('k', record(0x1234, 0x5678, 0x9abc))
        self.assertEqual(h['TESS_MODES']['k'], (None, None, None))
        m = domain_module(); self.assertEqual(h['tess_modes_set'](m, h['TESS_MODES']['k']), 0)
        self.assertEqual(modes(m), [1, 4, 22])

    def test_short_record(self):
        h = helpers(); h['tess_mode_read']('k', bytes.fromhex('34127698') + b'\0' * 0x100)
        self.assertNotIn('k', h['TESS_MODES'])


if __name__ == '__main__':
    unittest.main()
