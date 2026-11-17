"""PEANUTS: vk_android_spv.nonuniform_arrays: a texture taken from a descriptor array at a computed index is marked NonUniform (the access chain and its load), one at a constant
index is not, the capabilities and SPV_EXT_descriptor_indexing come once, and a second run marks nothing."""
import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'dm/gfx/tools'))
import vk_android_spv as spv  # noqa: E402


def ins(op, *w): return [(1 + len(w)) << 16 | op, *w]


def module():
    words = [ins(17, 1),                                   # OpCapability Shader
             ins(14, 0, 1),                                # OpMemoryModel Logical GLSL450
             ins(15, 4, 17, *spv.lit_string('main'), 15),  # OpEntryPoint Fragment %17 "main" %15
             ins(16, 17, 7),                               # OpExecutionMode OriginUpperLeft
             ins(71, 10, 34, 0), ins(71, 10, 33, 1),       # %10 set 0 binding 1
             ins(19, 1), ins(33, 2, 1), ins(22, 3, 32), ins(25, 4, 3, 1, 0, 0, 0, 1, 0), ins(27, 5, 4), ins(21, 6, 32, 0),
             ins(43, 6, 7, 4), ins(28, 8, 5, 7), ins(32, 9, 0, 8), ins(59, 9, 10, 0), ins(32, 11, 0, 5), ins(32, 14, 1, 6), ins(59, 14, 15, 1),
             ins(43, 6, 16, 0),
             ins(54, 1, 17, 0, 2), ins(248, 18),
             ins(61, 6, 19, 15),                           # the index, from an input
             ins(65, 11, 20, 10, 19), ins(61, 5, 21, 20),  # computed index: marked
             ins(65, 11, 22, 10, 16), ins(61, 5, 23, 22),  # constant index: not
             ins(253), ins(56)]
    flat = [v for x in words for v in x]
    return spv.Module(struct.pack('<%dI' % (5 + len(flat)), spv.MAGIC, 0x10300, 0, 24, 0, *flat))


class NonUniformArrays(unittest.TestCase):
    def test_marks_computed_index_only(self):
        m = module()
        self.assertEqual(spv.nonuniform_arrays(m), 2)
        nu = {i for i, d in m.decos.items() if spv.DEC_NON_UNIFORM in d}
        self.assertEqual(nu, {20, 21})
        self.assertIn(spv.CAP_SHADER_NON_UNIFORM, m.caps)
        self.assertIn(spv.CAP_SAMPLED_IMAGE_ARRAY_NU, m.caps)
        self.assertNotIn(spv.CAP_STORAGE_IMAGE_ARRAY_NU, m.caps)
        self.assertEqual(m.exts, ['SPV_EXT_descriptor_indexing'])
        again = spv.Module(m.data())
        self.assertEqual(spv.nonuniform_arrays(again), 0)
        self.assertEqual(again.data(), m.data())


if __name__ == '__main__':
    unittest.main()
