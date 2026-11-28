# PEANUTS: the Android loader advertises and consumes Android WSI, never Switch VI.
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class GuestVulkan(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_extension_translation(self):
        root = Path(__file__).resolve().parents[3]
        code = r'''
#include <assert.h>
#include <stdarg.h>
#include "vka_guest_crop.h"
#include "vka_guest_ext.h"
#include "vka_guest_wsi.h"
static int submit_crop(struct ANativeWindow *win, int op, ...) {
    assert(win && op == 3);
    va_list ap; va_start(ap, op);
    const guest_crop_rect *r = va_arg(ap, const guest_crop_rect *);
    va_end(ap);
    if (!r) return 0;
    assert(r->left == 384 && r->top == 216 && r->right == 1536 && r->bottom == 864);
    return 1;
}
int main(void) {
    guest_window_prefix window = {.magic = 0x5f776e64, .version = sizeof window, .perform = submit_crop};
    struct ANativeWindow *win = (struct ANativeWindow *)&window;
    guest_crop_rect r = guest_buffer_crop((int[]){384,216,1152,648}, 1920, 1080);
    assert(guest_set_buffer_crop(win, &r) == 1);
    r = guest_buffer_crop((int[]){0,0,1920,1080}, 1920, 1080);
    assert(guest_set_buffer_crop(win, &r) == 0);  // reset after cropping
    r = guest_buffer_crop((int[]){480,270,960,540}, 1280, 720);
    assert(guest_set_buffer_crop(win, &r) == 0);  // outside this swapchain
    window.version = 8;
    assert(guest_set_buffer_crop(win, &r) < 0);  // no call through a truncated producer
    assert(guest_title_selected("010090C022978001", "010090C022978001"));
    assert(!guest_title_selected("010090C022978001", "010090C022978003"));
    assert(guest_title_selected("010090C022978003", "010090C022978003"));
    assert(!guest_title_selected("010090C022978003", "010090C022978000"));
    assert(!guest_title_selected("1", "010090C022978003"));
    assert(!guest_title_selected(NULL, "010090C022978003"));
    guest_window_state w = {0};
    guest_window_invalidate(&w); w.available = 1;
    uint64_t old_surface = w.epoch, old_swap = w.epoch;
    assert(guest_window_enter(&w, old_swap));  // one present is in the driver
    guest_window_invalidate(&w);
    assert(w.users == 1 && !guest_window_enter(&w, old_swap));  // teardown drains, no new present or acquire
    --w.users;
    assert(!guest_window_enter(&w, old_surface));
    guest_window_invalidate(&w); w.available = 1;  // Android supplies another window
    assert(!guest_window_enter(&w, old_surface) && !guest_window_enter(&w, old_swap));
    assert(guest_window_enter(&w, w.epoch));  // a newly bound guest surface/swapchain works
    VkExtensionProperties props[] = {{"VK_KHR_surface", 25}, {"VK_KHR_android_surface", 6}};
    guest_extension_names(props, 2);
    assert(!strcmp(props[0].extensionName, "VK_KHR_surface") && props[0].specVersion == 25);
    assert(!strcmp(props[1].extensionName, "VK_NN_vi_surface") && props[1].specVersion == 1);
    assert(!strcmp(android_extension_name(props[1].extensionName), "VK_KHR_android_surface"));
    assert(android_extension_name(props[0].extensionName) == props[0].extensionName);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            src, exe = Path(tmp) / 'wsi.c', Path(tmp) / 'wsi'
            src.write_text(code)
            subprocess.run(['cc', '-std=c11', '-Wall', '-Werror', '-I', str(root / 'dm/gfx/vk_android'),
                            '-I', str(root / 'dm/third_party/vulkan-headers/include'), str(src), '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True)


if __name__ == '__main__':
    unittest.main()
