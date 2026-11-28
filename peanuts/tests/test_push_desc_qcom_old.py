"""The profile key push_desc_qcom_old (dm/games/README.md): on Qualcomm's own driver before 512.800 (V.qcom_old), `off` makes the Android replay create draw and
compute pipeline layouts without push descriptors, as the default of DM_VK_PUSH_DESC (dm/gfx/vk_android/vka_pso.c layout_slow). Untitled Goose Game and Sea of
Stars set it (#888, #907); every other profile keeps push descriptors on every driver, as before the switch existed: off cost Breath of the Wild about 45 % more
replay CPU on 512.676. The player's own DM_VK_PUSH_DESC line (env.txt, Extra environment) still wins both ways.

Checked here: game.py's values and macro, the macro for every real profile, the generic host's runtime profile field (gen_runtime_profile.py), the layout_slow
expression cut from vka_pso.c and compiled against the expression before the switch (V.push_desc && ndesc <= V.max_push) over every driver state, and the
vka_auto.h rule (the [auto] line) agreeing with that expression's default."""
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
GAME = ROOT / 'dm/games/game.py'
GEN = ROOT / 'dm/peanuts/profile/gen_runtime_profile.py'
PSO = ROOT / 'dm/gfx/vk_android/vka_pso.c'
AUTO = ROOT / 'dm/gfx/vk_android/vka_auto.h'
SET = ('010082400BCC6000', '01008C0016544000')   # Untitled Goose Game, Sea of Stars
ID = '0100000000F0F000'
TSV = ('title_id\t' + ID + '\nname\tTest\nprogram_id\t' + ID + '\nmodules\tmain\nromfs_marker\tromfs.img\n'
       'mem_reserve\t0x100000\nmem_alias\t0x100000\nmem_stack\t0x100000\napp_pool\t0x100000\n')


def header(rows=None, title=ID):
    """(exit code, output, the DM_GAME_PUSH_DESC_QCOM_OLD_OFF lines) of game.py header: a synthetic profile with these rows, or a real one (rows None)"""
    with tempfile.TemporaryDirectory() as d:
        env = dict(os.environ)
        if rows is not None:
            Path(d, title).mkdir()
            (Path(d, title) / 'game.tsv').write_text(TSV + rows)
            env['DM_GAMES_DIR'] = d
        out = Path(d, 'game.h')
        r = subprocess.run([sys.executable, str(GAME), 'header', title, str(out)], env=env, capture_output=True, text=True)
        lines = [ln for ln in out.read_text().splitlines() if 'PUSH_DESC_QCOM_OLD' in ln] if out.exists() else []
    return r.returncode, (r.stderr + r.stdout).strip(), lines


class GameKey(unittest.TestCase):
    def test_unset_is_on(self):
        self.assertEqual(header('')[::2], (0, ['#define DM_GAME_PUSH_DESC_QCOM_OLD_OFF 0']))

    def test_values(self):
        self.assertEqual(header('push_desc_qcom_old\ton\n')[::2], (0, ['#define DM_GAME_PUSH_DESC_QCOM_OLD_OFF 0']))
        self.assertEqual(header('push_desc_qcom_old\toff\n')[::2], (0, ['#define DM_GAME_PUSH_DESC_QCOM_OLD_OFF 1']))

    def test_rejected(self):
        for v in ('0', '1', 'yes', 'OFF'):
            rc, out, _ = header(f'push_desc_qcom_old\t{v}\n')
            self.assertNotEqual(rc, 0, v)
            self.assertIn('push_desc_qcom_old must be on or off', out, v)

    def test_real_profiles(self):   # off for the two games it fixes, on (0) for every other game
        titles = sorted(p.name for p in (ROOT / 'dm/games').iterdir() if re.fullmatch(r'[0-9A-F]{16}', p.name) and (p / 'game.tsv').exists())
        self.assertGreater(len(titles), 100)
        for t in titles:
            rc, out, lines = header(None, t)
            self.assertEqual(rc, 0, f'{t}: {out}')
            self.assertEqual(lines, [f'#define DM_GAME_PUSH_DESC_QCOM_OLD_OFF {int(t in SET)}'], t)


READ = r'''
#include "profile_rt.h"
#include <stdio.h>
int main(int argc, char **argv) {   // the field at its offset in the host's own struct, from a profile.bin the generator wrote
    static unsigned char b[sizeof(dm_game_profile_t)]; FILE *f = fopen(argv[1], "rb");
    if (!f || fread(b, 1, sizeof b, f) != sizeof b) return 2;
    printf("%u\n", ((const dm_game_profile_t *)b)->push_desc_qcom_old_off); return 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class RuntimeProfile(unittest.TestCase):   # the generic host's profile.bin carries the value (gen_runtime_profile.py; all fields: check-runtime-profile.sh)
    def test_value(self):
        with tempfile.TemporaryDirectory() as d:
            c = Path(d, 'read.c'); c.write_text(READ)
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Werror', '-I', str(GEN.parent), '-o', str(Path(d, 'read')), str(c)], check=True)
            for title, want in ((SET[0], 1), (SET[1], 1), ('01007EF00011E000', 0)):   # (Breath of the Wild: no row)
                out = Path(d, title + '.bin')
                subprocess.run([sys.executable, str(GEN), 'profile', title, str(out)], check=True, capture_output=True)
                got = subprocess.run([str(Path(d, 'read')), str(out)], check=True, capture_output=True, text=True).stdout
                self.assertEqual(int(got), want, title)


PROG = r'''
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "vka_auto.h"
#define VKA_ENV(k, def) (getenv(k) && *getenv(k) ? atoi(getenv(k)) : (def))
static struct { int push_desc, qcom_old; uint32_t max_push; } V;
static int push_now(uint32_t ndesc) { struct { int push; uint32_t ndesc; } L_, *L = &L_; %(LINE)s return L->push; }
static int push_before(uint32_t ndesc) { return V.push_desc && ndesc <= V.max_push; }   // vka_pso.c before the switch (github/dev 393a90a43)
int main(void) {   // per state: the replay's push (now / before) and whether vka_auto.h prints DM_VK_PUSH_DESC=0
    static const int drivers[] = {VKA_DRV_QCOM, VKA_DRV_TURNIP, 9, 5};
    for (int d = 0; d < 4; d++) for (int qo = 0; qo < 2; qo++) for (int pd = 0; pd < 2; pd++) for (uint32_t mp = 0; mp <= 32; mp += 16) for (uint32_t nd = 0; nd <= 33; nd += 11) {
        if (qo && drivers[d] != VKA_DRV_QCOM) continue;   // V.qcom_old is only set for driverID 8 (vka_dev.c)
        V.push_desc = pd; V.qcom_old = qo; V.max_push = mp;
        vka_auto_rule r[16]; int n = vka_auto_rules(&(vka_auto_in){.driver_id = drivers[d], .qcom_old = qo, .prof_push_desc_off = DM_GAME_PUSH_DESC_QCOM_OLD_OFF}, r, 16), rule = 0;
        for (int i = 0; i < n; i++) if (!strcmp(r[i].key, "DM_VK_PUSH_DESC")) rule = 1 + !strcmp(r[i].val, "0");
        printf("%%d %%d %%d %%u %%u %%d %%d %%d\n", drivers[d], qo, pd, mp, nd, push_now(nd), push_before(nd), rule);
    }
    return 0;
}
'''


@unittest.skipUnless(shutil.which('cc'), 'needs a C compiler')
class LayoutPush(unittest.TestCase):   # vka_pso.c layout_slow's L->push line as it is in the production file
    @classmethod
    def setUpClass(cls):
        m = re.search(r'^\s*L->ndesc = ndesc; (L->push = [^;]*DM_VK_PUSH_DESC[^;]*;)', PSO.read_text(), re.M)
        assert m, 'the L->push line with DM_VK_PUSH_DESC not found in vka_pso.c'
        cls.tmp = tempfile.TemporaryDirectory()
        cls.bins = {}
        for prof in (0, 1):
            c = Path(cls.tmp.name, f'p{prof}.c')
            c.write_text(f'#define DM_GAME_PUSH_DESC_QCOM_OLD_OFF {prof}\n' + PROG % {'LINE': m[1]})
            b = Path(cls.tmp.name, f'p{prof}')
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Werror', '-I', str(AUTO.parent), '-o', str(b), str(c)], check=True)
            cls.bins[prof] = b

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def rows(self, prof, player=None):
        env = {k: v for k, v in os.environ.items() if k != 'DM_VK_PUSH_DESC'}
        if player is not None: env['DM_VK_PUSH_DESC'] = player
        out = subprocess.run([str(self.bins[prof])], env=env, capture_output=True, text=True, check=True).stdout
        return [tuple(int(x) for x in ln.split()) for ln in out.splitlines()]

    def test_without_the_key_as_before(self):   # every driver, every device state: the same push as before the switch, and no [auto] line
        rows = self.rows(0)
        self.assertGreater(len(rows), 100)
        for drv, qo, pd, mp, nd, now, before, rule in rows:
            self.assertEqual(now, before, (drv, qo, pd, mp, nd))
            self.assertEqual(rule, 0, (drv, qo, pd, mp, nd))

    def test_with_the_key(self):   # off only on Qualcomm before 512.800; the [auto] line exactly there
        for drv, qo, pd, mp, nd, now, before, rule in self.rows(1):
            self.assertEqual(now, 0 if qo else before, (drv, qo, pd, mp, nd))
            self.assertEqual(rule, 2 if qo else 0, (drv, qo, pd, mp, nd))

    def test_player_wins(self):
        for drv, qo, pd, mp, nd, now, before, rule in self.rows(1, '1'): self.assertEqual(now, before)   # back on for the profile's games
        for prof in (0, 1):
            for drv, qo, pd, mp, nd, now, before, rule in self.rows(prof, '0'): self.assertEqual(now, 0)   # off on any driver
            for drv, qo, pd, mp, nd, now, before, rule in self.rows(prof, ''): self.assertEqual(now, 0 if prof and qo else before)   # empty = unset


if __name__ == '__main__':
    unittest.main()
