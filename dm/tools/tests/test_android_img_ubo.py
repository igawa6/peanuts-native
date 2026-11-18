"""Images whose handle the game keeps in its own uniform buffer (dmsh_abi_vk.h DMSH_VK_KIND_IMG_UBO; Unreal Engine 5.6 on the Switch 2: Orbitals' compute shaders read every
image's handle from c[3]): the binding name the translator gives them decodes to the uniform slot and byte offset (vk_android_build.py ubo_img), and only those names do.
The function is taken from the tool's source by name, as the delta tests take theirs; the tool itself sets up a game at import."""
import ast
from pathlib import Path
import re
import unittest

REPO = Path(__file__).resolve().parents[3]


def tool_function(name):
    src = (REPO / 'dm/gfx/tools/vk_android_build.py').read_text(encoding='utf-8')
    node = next(n for n in ast.parse(src).body if isinstance(n, ast.FunctionDef) and n.name == name)
    ns = {'re': re}
    exec(compile(ast.Module([node], []), 'vk_android_build.py', 'exec'), ns)
    # PEANUTS(upstream-candidate): the extracted function must be callable before staticmethod binds it.
    fn = ns[name]
    assert callable(fn)
    return fn


class UboImg(unittest.TestCase):
    ubo_img = staticmethod(tool_function('ubo_img'))

    def test_orbitals_names(self):   # cp_i_cb3_<handle>_: c[3] (uniform slot 0), the handle's word
        self.assertEqual(self.ubo_img('cp_i_cb3_0_'), (0, 0))
        self.assertEqual(self.ubo_img('cp_i_cb3_E_'), (0, 0x38))
        self.assertEqual(self.ubo_img('fp_i_cb3_4_'), (0, 0x10))

    def test_other_uniform_slots(self):
        self.assertEqual(self.ubo_img('cp_i2d_cb7_2A'), (4, 0xA8))
        self.assertEqual(self.ubo_img('vp_i_cb16_1'), (13, 4))

    def test_not_an_image_in_a_uniform_buffer(self):
        self.assertIsNone(self.ubo_img('cp_i_cb2_10'))         # bank 2: the driver's image slots (dmsh_build.decode_tex)
        self.assertIsNone(self.ubo_img('cp_t_cb3_0'))          # a texture: tex_ubo's (decode_ubo_tex)
        self.assertIsNone(self.ubo_img('cp_i_cb17_0'))         # past the 14 uniform slots
        self.assertIsNone(self.ubo_img('cp_i_cb3_1000000A'))   # a handle that is not one word (type 1)
        self.assertIsNone(self.ubo_img('cp_s3'))


if __name__ == '__main__':
    unittest.main()
