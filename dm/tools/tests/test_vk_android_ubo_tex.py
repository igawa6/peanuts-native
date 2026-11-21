"""PEANUTS: vk_android_build.py's ubo_tex_c2_sampler: a type-1 handle whose texture word is in the game's own uniform buffer and whose separate sampler word is in the driver
table c2 (Unreal Engine 4's materials in Hogwarts Legacy) gets the texture's uniform slot and offset; other handles are left to the decoders before it."""
import ast
import os
import re
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS))
import dmsh_abi as abi  # noqa: E402


def functions(path, *names):
    tree = ast.parse(path.read_text())
    return [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]


NS = {'re': re, 'os': os, 'abi': abi, 'TEX_BASE': abi.C2_TEX, 'IMG_BASE': abi.C2_IMG, 'IMG_SLOTS': abi.C2_IMG_SLOTS, 'SEPTEX_BASE': abi.C2_SEPTEX,
      'SEPTEX_SLOT0': abi.SEPTEX_SLOT0, 'SAMP_BASE': abi.C2_SAMP}
exec(compile(ast.Module(body=functions(TOOLS / 'dmsh_build.py', 'decode_tex', 'decode_ubo_tex'), type_ignores=[]), 'dmsh_build.py', 'exec'), NS)
NS['NS'] = NS   # vk_android_build.py reaches the borrowed decoders through NS[...]
exec(compile(ast.Module(body=functions(TOOLS / 'vk_android_build.py', 'ubo_tex_c2_sampler'), type_ignores=[]), 'vk_android_build.py', 'exec'), NS)
f = NS['ubo_tex_c2_sampler']


class UboTexC2Sampler(unittest.TestCase):
    def test_hogwarts_arrays(self):   # c7 (uniform slot 4) from byte 0, separate samplers 0-3 at c2 words 0x15A-0x160
        self.assertEqual(f('fp_t2d_cb196615_10568000'), ((4, 0), 0))
        self.assertEqual(f('fp_t2d_cb196615_10570000'), ((4, 0), 1))
        self.assertEqual(f('fp_t2d_cb196615_10580000'), ((4, 0), 3))

    def test_offset(self):   # texture word 0x2A of c4 (uniform slot 1), separate sampler 2
        self.assertEqual(f('fp_t_cb196612_1057802A'), ((1, 0xA8), 2))

    def test_left_to_the_others(self):
        self.assertIsNone(f('fp_t_cb7_2A'))                 # type 0 in a uniform buffer: decode_ubo_tex
        self.assertIsNone(f('fp_t_cb262151_1000C02A'))      # type 1, sampler in another uniform buffer (c3): decode_ubo_tex
        self.assertIsNone(f('fp_t_cb196610_1017815A'))      # both in c2: decode_tex
        self.assertIsNone(f('fp_t_cb65538_3041005C'))       # type 3 (constant sampler)
        self.assertIsNone(NS['decode_ubo_tex']('fp_t2d_cb196615_10568000'))   # why the branch exists


if __name__ == '__main__':
    unittest.main()
