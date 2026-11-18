# PEANUTS: exercise the production unbound-image placeholders with strict format lists.
"""Unbound float/integer images need valid typed-zero views on the first run."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class Placeholder(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_numeric_classes_without_learned_formats(self):
        src = (ROOT / 'dm/gfx/vk_android/vka_draw.c').read_text()
        fn = src[src.index('VkImageView placeholder(uint8_t dim, int storage) {'):src.index('// Where a draw\'s CPU time goes:')]
        pre = r'''
        #include <assert.h>
        #include <stdint.h>
        #include <string.h>
        #include "dm/gfx/nvn/include/nvn.h"
        #include "dm/gfx/nvn/dmsh_abi_vk.h"
        typedef uintptr_t VkImageView;
        typedef int VkFormat;
        typedef int VkImageViewType;
        enum { VK_NULL_HANDLE = 0, VK_IMAGE_ASPECT_COLOR_BIT = 1,
            VK_FORMAT_R8G8B8A8_UNORM = 37, VK_FORMAT_R8G8B8A8_UINT = 41,
            VK_FORMAT_R8G8B8A8_SINT = 42, VK_IMAGE_VIEW_TYPE_1D = 0,
            VK_IMAGE_VIEW_TYPE_2D = 1, VK_IMAGE_VIEW_TYPE_3D = 2,
            VK_IMAGE_VIEW_TYPE_CUBE = 3, VK_IMAGE_VIEW_TYPE_1D_ARRAY = 4,
            VK_IMAGE_VIEW_TYPE_2D_ARRAY = 5, VK_IMAGE_VIEW_TYPE_CUBE_ARRAY = 6 };
        typedef struct { struct { int target, format, width, height, depth, levels, flags; } d;
            int L; uint64_t serial; } tex_t;
        typedef struct { struct { VkFormat vk; } f; uint32_t layers; } vka_img;
        static int g_cb, made, wrong_format, views;
        static vka_img images[9];
        static void nvn_tex_layout(const void *d, void *l) { (void)d; (void)l; }
        static uint64_t nvn_new_serial(void) { static uint64_t serial; return ++serial; }
        static vka_img *vka_tex_get(tex_t *t, int cb) {
            (void)cb; assert(made < 9);
            assert(t->d.width == 1 && t->d.height == 1 && t->d.levels == 1);
            assert(t->d.flags & NVN_TEXTURE_FLAGS_IMAGE);
            vka_img *im = &images[made++];
            switch (t->d.format) {
            case NVN_FORMAT_RGBA8: im->f.vk = VK_FORMAT_R8G8B8A8_UNORM; break;
            case NVN_FORMAT_RGBA8I: im->f.vk = VK_FORMAT_R8G8B8A8_SINT; break;
            case NVN_FORMAT_RGBA8UI: im->f.vk = VK_FORMAT_R8G8B8A8_UINT; break;
            default: assert(0);
            }
            im->layers = t->d.target == NVN_TEXTURE_TARGET_3D ? 1 : 6;
            return im;
        }
        // A cold image with only its own format allowed: no learned pairs and
        // no driver null descriptors to hide an invalid integer placeholder.
        static VkImageView vka_view(vka_img *im, VkFormat fmt, VkImageViewType type,
                int aspect, uint32_t l, uint32_t nl, uint32_t a, uint32_t na, const void *swz) {
            (void)aspect; (void)swz; assert(l == 0 && nl == 1 && a == 0);
            assert(na <= im->layers);
            if (type == VK_IMAGE_VIEW_TYPE_CUBE || type == VK_IMAGE_VIEW_TYPE_CUBE_ARRAY) assert(na == 6);
            if (fmt != im->f.vk) { wrong_format++; return VK_NULL_HANDLE; }
            return (VkImageView)++views;
        }
        '''
        post = r'''
        int main(void) {
            const uint8_t shapes[] = {1, 1 | DMSH_VK_DIM_ARRAY, 2, 3, 3 | DMSH_VK_DIM_ARRAY};
            const uint8_t classes[] = {0, DMSH_VK_DIM_SINT, DMSH_VK_DIM_UINT};
            for (int storage = 0; storage < 2; storage++)
                for (unsigned s = 0; s < sizeof shapes; s++)
                    for (unsigned c = 0; c < sizeof classes; c++) {
                        uint8_t dim = shapes[s] | classes[c];
                        VkImageView v = placeholder(dim, storage);
                        assert(v != VK_NULL_HANDLE);
                        assert(placeholder(dim, storage) == v);
                    }
            assert(wrong_format == 0 && made == 6);
            assert(!placeholder(0, 0));
            assert(!placeholder(1 | DMSH_VK_DIM_MS, 0));
            assert(made == 6);
            return 0;
        }
        '''
        with tempfile.TemporaryDirectory() as tmp:
            c, exe = Path(tmp) / 'placeholder.c', Path(tmp) / 'placeholder'
            c.write_text(pre + fn + post)
            build = subprocess.run(['cc', '-std=c11', '-Wall', '-Werror', '-I', str(ROOT), str(c), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            result = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__':
    unittest.main()
