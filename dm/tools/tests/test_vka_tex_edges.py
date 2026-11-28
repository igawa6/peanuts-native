# PEANUTS(upstream-candidate): Exercise tracked texture edge validation.
"""Exercise tracked edge validation without a Vulkan device."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[3] / 'dm/gfx/vk_android/vka_tex.c'
FIXTURE = r'''
#include <assert.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#define VLOG(...) ((void)0)

typedef struct {
    const void *vpool, *span;
    const uint8_t *mem;
    struct { size_t size; } L;
    uint64_t stamp, edge_h[2];
    uintptr_t edge_block;
    int edged;
} vka_img;

static uintptr_t block = 16;
static uintptr_t changed;
static uint64_t generation = 1;
static unsigned protections;

static int getpagesize(void) { return 16; }
static uintptr_t nvn_dirty_block_bytes(void) { return block; }
static uint64_t nvn_dirty_now(void) { return generation; }
static int dm_guest_unmapped(uintptr_t start, size_t size) {
    (void)start; (void)size;
    return 0;
}
static void nvn_dirty_protect_span(const void *span, const void *addr, size_t size) {
    assert(span && addr && size);
    protections++;
}
static int nvn_dirty_since_span(const void *span, const void *addr, size_t size, uint64_t stamp) {
    assert(span);
    uintptr_t start = (uintptr_t)addr;
    return generation > stamp && changed >= start && changed - start < size;
}
'''
MAIN = r'''
int main(void) {
    _Alignas(32) uint8_t storage[128] = {0};
    vka_img im = {.span = storage, .mem = storage + 1, .L.size = 62, .stamp = generation};
    img_edges_take(&im);
    assert(im.edged && im.edge_block == 16);

    // A dirty shared block with unchanged texture bytes can skip upload.
    changed = (uintptr_t)(storage + 1);
    generation++;
    assert(img_edges_clean(&im));
    assert(im.stamp == generation && protections == 1);

    // Real content changes in either edge or in the tracked interior cannot skip.
    storage[62] = 1;
    generation++;
    assert(!img_edges_clean(&im) && im.stamp == generation - 1);
    storage[62] = 0;
    changed = (uintptr_t)(storage + 16);
    generation++;
    assert(!img_edges_clean(&im));

    // Growth of the tracker block invalidates hashes made for the old edges.
    block = 32;
    assert(!img_edges_clean(&im) && protections == 3);
    im.stamp = generation;
    img_edges_take(&im);
    assert(im.edged && im.edge_block == 32);
    return 0;
}
'''


class TextureEdges(unittest.TestCase):
    def test_dirty_edges_and_tracking_block_growth(self):
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('needs a C compiler')
        source = SOURCE.read_text()
        functions = source[source.index('static uint64_t ft_hash('):source.index('static void upload_tracked(')]
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / 'edges.c'
            binary = Path(tmp) / 'edges'
            fixture.write_text(FIXTURE + functions + MAIN)
            build = subprocess.run(
                [cc, '-std=c11', '-O1', '-fsanitize=undefined', '-fsanitize-undefined-trap-on-error',
                 str(fixture), '-o', str(binary)], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)
