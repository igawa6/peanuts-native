"""PEANUTS(upstream-candidate): handle type 2 (Ryujinx's SeparateSamplerId) on Vulkan. vk_android_build.py type2_sampler finds the uniform word that holds the sampler ID itself
(the texture word in the driver table c2, the sampler ID in bank (high 16 bits of slots) - 1, word (handle >> 14) & 0x3fff), sampler_half binds it as SAMP_UBO and the caller marks it
DMSH_VK_BF_SAMP_RAW, which vka_bind.c samp_of takes as the ID itself instead of a handle's high 12 bits. No game files needed."""
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
BUILD = REPO / 'dm/gfx/tools/vk_android_build.py'
HEADER = REPO / 'dm/gfx/nvn/dmsh_abi_vk.h'
BIND = REPO / 'dm/gfx/vk_android/vka_bind.c'


def type2_sampler():
    """The function itself, from the script's source (vk_android_build.py refuses to be imported)"""
    src = BUILD.read_text()
    a = src.index('def type2_sampler'); b = src.index('\n# PEANUTS(upstream-candidate) end', a)
    ns = {'re': re}; exec(src[a:b], ns)
    return ns['type2_sampler']


class Type2Sampler(unittest.TestCase):
    def test_decode(self):
        f = type2_sampler()
        # The Witcher 3 (nxnative ca26ef65's example): slots 0xb0002 = texture in c2, sampler in c[10] = uniform slot 7
        self.assertEqual(f('fp_t_cb720898_2000005A'), (7, 0))
        self.assertEqual(f('fp_t_cb720898_20030020'), (7, 0xC * 4))
        self.assertEqual(f('fp_t_cb720898_2003C026'), (7, 0xF * 4))

    def test_other_handles_untouched(self):
        f = type2_sampler()
        self.assertIsNone(f('fp_t_cb65538_3041005C'))      # type 3: a constant sampler ID
        self.assertIsNone(f('fp_t2d_cb196615_10568000'))   # type 1: a separate sampler slot
        self.assertIsNone(f('fp_t_cb720899_2000005A'))     # texture not in c2
        self.assertIsNone(f('fp_t_cb131074_2000005A'))     # sampler bank 1: not a game uniform buffer
        self.assertIsNone(f('fp_i_cb720898_2000005A'))     # an image has no sampler

    def test_flag(self):
        flags = {int(v): k for k, v in re.findall(r'#define DMSH_VK_(BF_\w+)\s+(\d+)', HEADER.read_text())}
        self.assertIn('BF_SAMP_RAW', flags.values())
        bit = next(b for b, k in flags.items() if k == 'BF_SAMP_RAW')
        self.assertEqual(bit & (bit - 1), 0, 'one bit')
        self.assertEqual(sum(1 for b in flags if b == bit), 1, 'no other BF_ flag shares it')
        self.assertLess(bit, 256, 'dmsh_vk_bind.flags is a uint8_t')
        src = BUILD.read_text()
        self.assertIn("if type2_sampler(nm): e['flags'] |= VK['BF_SAMP_RAW']", src)
        self.assertIn('DMSH_VK_BF_SAMP_RAW', BIND.read_text())


if __name__ == '__main__':
    unittest.main()
