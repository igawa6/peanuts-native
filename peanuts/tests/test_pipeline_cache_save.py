"""Exercise the production cache writer against failed writes, closes and renames."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]

HARNESS = r'''
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int mode, closes, renames, opened;
static FILE *open_save(const char *path, const char *how) {
    if (mode == 4) { errno = EACCES; return NULL; }
    assert(!opened);
    FILE *f = fopen(path, how);
    assert(f); opened = 1; return f;
}
static size_t write_save(const void *data, size_t size, size_t count, FILE *f) {
    assert(opened && size == 1 && count > 1);
    return fwrite(data, size, mode == 1 ? count - 1 : count, f);
}
static int close_save(FILE *f) {
    assert(opened); opened = 0; closes++;
    int r = fclose(f);
    if (mode == 2) { errno = EIO; return EOF; }
    return r;
}
static int rename_save(const char *from, const char *to) {
    assert(!opened); renames++;
    if (mode == 3) { errno = EACCES; return -1; }
    return rename(from, to);
}
// Record shapes are irrelevant to save/retry ownership; keep one valid record.
typedef struct { unsigned bad; } pwrec;
typedef struct { char magic[8]; unsigned gkey_size, n, layout; } pwhdr2;
typedef unsigned vka_gkey;
static char g_pw_path[64] = "pipelines.keys";
static pthread_mutex_t g_pw_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_pw_dirty;
static pwrec record;
static unsigned char *g_pw_buf = (unsigned char *)&record;
static size_t g_pw_len = sizeof record;
static size_t pw_size(const pwrec *r) { (void)r; return sizeof *r; }
static unsigned vka_pw_layout(void) { return 1; }
#define VKA_PW_MAGIC2 "PWTEST00"
#define VKA_ENV(name, fallback) (fallback)
#define VRED(...) ((void)0)
#define fopen open_save
#define fwrite write_save
#define fclose close_save
#define rename rename_save
PRODUCTION
PREWARM
#undef fopen
#undef fwrite
#undef fclose
#undef rename

static void seed(const char *path) {
    FILE *f = fopen(path, "wb"); assert(f);
    assert(fwrite("old", 1, 3, f) == 3); assert(!fclose(f));
}
static void check_contents(const char *path, const char *want) {
    char buf[16] = {0}; FILE *f = fopen(path, "rb"); assert(f);
    size_t n = fread(buf, 1, sizeof buf, f); assert(!fclose(f));
    assert(n == strlen(want) && !memcmp(buf, want, n));
}
int main(void) {
    const char *paths[] = {"pipelines.bin", "pipelines.bin.lib", "pipelines.keys"};
    for (unsigned p = 0; p < 3; p++) {
        char tmp[64]; snprintf(tmp, sizeof tmp, "%s.tmp", paths[p]);
        for (mode = 0; mode <= 5; mode++) {
            seed(paths[p]); seed(tmp); closes = renames = 0;
            int ok = pc_write_atomic(paths[p], tmp, mode == 5 ? NULL : "new cache", 9);
            assert(ok == (mode == 0));
            assert(!opened && closes == (mode >= 4 ? 0 : 1));
            assert(renames == (mode == 0 || mode == 3 ? 1 : 0));
            assert(access(tmp, F_OK) == -1 && errno == ENOENT);
            check_contents(paths[p], ok ? "new cache" : "old");
            assert(!unlink(paths[p]));
        }
    }
    for (int failure = 1; failure <= 4; failure++) {
        seed(g_pw_path); seed("pipelines.keys.tmp");
        mode = failure; closes = renames = 0; g_pw_dirty = 1;
        assert(pw_save() == -1 && g_pw_dirty == 1);
        assert(!opened && closes == (mode == 4 ? 0 : 1));
        assert(access("pipelines.keys.tmp", F_OK) == -1 && errno == ENOENT);
        check_contents(g_pw_path, "old");
        mode = 0; closes = renames = 0;
        assert(pw_save() == 1 && !g_pw_dirty && closes == 1 && renames == 1);
        FILE *f = fopen(g_pw_path, "rb"); assert(f);
        pwhdr2 header; assert(fread(&header, 1, sizeof header, f) == sizeof header);
        assert(!fclose(f) && header.n == 1);
        assert(pw_save() == 0 && closes == 1 && renames == 1);
        assert(!unlink(g_pw_path));
    }
    return 0;
}
'''


class PipelineCacheSave(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_atomic_save_failures(self):
        source = (ROOT / 'dm/gfx/vk_android/vka_pso.c').read_text()
        start = source.index('static int pc_write_atomic(')
        helper = source[start:source.index('// The cache and the prewarm list', start)]
        start = source.index('static int pw_save(void) {')
        prewarm = source[start:source.index('static void pw_mark_bad(', start)]
        with tempfile.TemporaryDirectory(prefix='peanuts-cache-save-') as td:
            path = Path(td)
            src, exe = path / 'save.c', path / 'save'
            src.write_text(HARNESS.replace('PRODUCTION', helper).replace('PREWARM', prewarm))
            subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror',
                            '-pthread', '-fsanitize=address,undefined', str(src), '-o', str(exe)], check=True)
            subprocess.run([str(exe)], cwd=td, check=True)


if __name__ == '__main__':
    unittest.main()
