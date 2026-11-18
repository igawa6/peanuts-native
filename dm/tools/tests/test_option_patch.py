"""Real option-code transaction: build filtering, literal text bounds, all-or-none writes."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


class OptionPatches(unittest.TestCase):
    def test_independent_literal_transaction(self):
        source = r'''
#include <assert.h>
#include "option_patch.h"
int main(void) {
    uint32_t image[4] = {1, 2, 3, 4};
    const char *bid = "d4b150b29a931cd381e2a30318fc299f";
    const char *spec = "d4b150b29a931cd381e2a30318fc299f,m0x0,01000000,05000000;"
                       "d4b150b29a931cd381e2a30318fc299f,m0x4,02000000,06000000";
    assert(dm_option_patch(spec, "00000000000000000000000000000000", (void *)image, 16) == 0);
    assert(image[0] == 1 && image[1] == 2);
    image[1] = 7;
    assert(dm_option_patch(spec, bid, (void *)image, 16) == -2);
    assert(image[0] == 1 && image[1] == 7);
    image[1] = 2;
    assert(dm_option_patch(spec, bid, (void *)image, 4) == -1);
    assert(image[0] == 1 && image[1] == 2);
    assert(dm_option_patch("d4b150b29a931cd381e2a30318fc299f,r0x0,01000000,05000000", bid, (void *)image, 16) == -4);
    assert(dm_option_patch("d4b150b29a931cd381e2a30318fc299f,c0x0,-,05000000", bid, (void *)image, 16) == -4);
    assert(dm_option_patch("d4b150b29a931cd381e2a30318fc299f,clock,-,1", bid, (void *)image, 16) == -4);
    assert(dm_option_patch(spec, bid, (void *)image, 16) == 2);
    assert(image[0] == 5 && image[1] == 6 && image[2] == 3 && image[3] == 4);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            src = pathlib.Path(tmp) / 'option.c'
            exe = pathlib.Path(tmp) / 'option'
            src.write_text(source)
            subprocess.run(['cc', '-std=c11', '-D_POSIX_C_SOURCE=200809L', '-Wall', '-Wextra', '-Werror', '-I', str(ROOT / 'dm/host'), str(src), '-o', str(exe)], check=True)
            subprocess.run([str(exe)], check=True)
