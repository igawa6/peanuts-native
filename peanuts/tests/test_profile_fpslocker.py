"""Every profile's reviewed fpslocker rows, through the host's own parser (fpslocker_rows.h), and the guard on Kirby and the Forgotten Land's frame timer.

A row the parser refuses makes the host refuse the whole set ("invalid reviewed rows; no memory writes applied"), so a limit then runs without any of the game's
writes; the launcher still offers the limits. This compiles every profile's rows for their builds and checks that each set parses in full.

Kirby and the Forgotten Land's limits keep the game's own frame timer (mode 2, the least wait at [main+0xA76728]+0x2c set to 1). The FPSLocker
Warehouse row that sets the mode to 0 ([main+0xA76728]+0x50) selects a fixed 1/60 s step that catches a late frame up with up to 10 updates and no drawing, which
collapsed the overworld to 4-9 fps (GitHub issue #153; dm/games/01004D300C5AE000/README.md, docs/reference/frame-rate.md). These checks keep it from coming back."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
GAMES = ROOT / 'dm/games'
KIRBY = GAMES / '01004D300C5AE000/game.tsv'
KIRBY_MAIN = 'a6ce40dc3aedb1becacd2f1ff4515307'


def rows_of(tsv):
    """{build: [spec, ...]} from a game.tsv's fpslocker rows, in the host's spec form (build,kind,path,copies,value)"""
    out = {}
    for line in tsv.read_text().splitlines():
        c = line.split('\t')
        if c[0] == 'fpslocker' and len(c) >= 6:
            out.setdefault(c[1], []).append(','.join(c[1:6]))
    return out


class EveryProfile(unittest.TestCase):
    def test_rows_parse_in_full(self):
        cases = []
        for tsv in sorted(GAMES.glob('*/game.tsv')):
            for build, specs in rows_of(tsv).items():
                cases.append((tsv.parent.name, build, ';'.join(specs), len(specs)))
        self.assertTrue(cases, 'no profile has fpslocker rows')
        body = '\n'.join(
            f'    n = fpw_parse("{spec}", "{build}", &r); if (n != {count}) {{ printf("%s %s: %d of {count} rows parsed\\n", "{gid}", "{build}", n); bad++; }}'
            for gid, build, spec, count in cases)
        source = ('#include <stdio.h>\n#include "fpslocker_rows.h"\n'
                  'int main(void) {\n    struct fpw_rows r; int n, bad = 0;\n' + body + '\n    return bad;\n}\n')
        with tempfile.TemporaryDirectory() as tmp:
            src, exe = pathlib.Path(tmp, 'rows.c'), pathlib.Path(tmp, 'rows')
            src.write_text(source)
            subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Werror', '-fsanitize=undefined',
                            '-I', str(ROOT / 'dm/host'), str(src), '-lm', '-o', str(exe)], check=True)
            r = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout)


class KirbyFrameTimer(unittest.TestCase):
    def test_own_timer_not_the_catch_up_mode(self):
        specs = rows_of(KIRBY).get(KIRBY_MAIN, [])
        self.assertIn(f'{KIRBY_MAIN},constant_i32,0xa76728/0x2c,1,1:1', specs, "the least wait of the game's own mode (+0x2c) at 1")
        for s in specs:
            self.assertNotIn('0xa76728/0x50,', s, f'{s}: [main+0xA76728]+0x50 is the timer mode; 0 is the fixed-step catch-up that collapsed to 4 fps (#153)')
            self.assertNotIn('0xa76728/0x10,', s, f'{s}: +0x10 bounds the mode-0 catch-up; the profile keeps mode 2 instead')


if __name__ == '__main__':
    unittest.main()
