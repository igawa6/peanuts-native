"""PEANUTS: the game profile key `tbuf_ssbo_copy` (dm/games/README.md): buffer textures over a pass's storage buffer stores read through a copy the GPU makes. It is a
profile key the host reads (vka_draw.c TBUF_SSBO_SW), not a player setting; it is off unless a game's profile turns it on.

The mechanism tests always run, on any profile of the checkout: the key is off by default, a value other than on or off is refused, and the per-title header (game.py) and
the generic host's runtime profile (gen_runtime_profile.py: the field is in profile.bin) carry it. The assertions about Fire Emblem Warriors: Three Hopes (the game that
turns it on) run only where that game's profile is present."""
from pathlib import Path
import sys
import unittest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'dm/games'))
sys.path.insert(0, str(REPO / 'dm/peanuts/profile'))
import game as gamepy   # noqa: E402
import gen_runtime_profile as gen   # noqa: E402

THREE_HOPES = '010071F0143EA000'   # Fire Emblem Warriors: Three Hopes


def macro(g):
    for line in gamepy.header(g).splitlines():
        if line.startswith('#define DM_GAME_TBUF_SSBO_COPY '): return line.split()[-1]
    return None


def without_key(g):
    g.rows = [r for r in g.rows if r[0] != 'tbuf_ssbo_copy']
    return g


def a_profile():
    """Any profile of the checkout that does not set the key (the key is off for it): the base the mechanism tests set the key on."""
    for g in gamepy.all_games():
        if not g.values('tbuf_ssbo_copy'): return g
    return None


@unittest.skipUnless(a_profile() is not None, 'no profile without the key in this checkout')
class TbufSsboCopyMechanism(unittest.TestCase):
    def test_off_by_default(self):
        g = a_profile()
        self.assertEqual(macro(g), '0')
        g.rows.append(('tbuf_ssbo_copy', ['off']))
        self.assertEqual(macro(g), '0')

    def test_on_sets_the_macro(self):
        g = a_profile()
        g.rows.append(('tbuf_ssbo_copy', ['on']))
        self.assertEqual(macro(g), '1')

    def test_a_value_other_than_on_or_off_is_refused(self):
        for bad in ('yes', '1', 'ON', ''):
            g = a_profile()
            g.rows.append(('tbuf_ssbo_copy', [bad]))
            with self.assertRaises(SystemExit, msg=repr(bad)):
                gamepy.header(g)

    def test_the_runtime_profile_carries_it(self):
        off = gen.build_profile_bytes(a_profile())
        g = a_profile()
        g.rows.append(('tbuf_ssbo_copy', ['on']))
        on = gen.build_profile_bytes(g)
        self.assertEqual(len(on), len(off))
        diff = [i for i in range(len(on)) if on[i] != off[i]]
        self.assertEqual(len(diff), 1, 'the key changes one u32 of the blob (1 against 0)')
        self.assertEqual((on[diff[0]], off[diff[0]]), (1, 0))
        names = [n for n, _ in gen.LAYOUT]
        self.assertIn('tbuf_ssbo_copy', names)
        self.assertEqual(names[names.index('view_scale_fixed') + 1], 'tbuf_ssbo_copy', 'the first field after the shared groups')

    def test_no_launcher_option_for_the_switch(self):
        for g in gamepy.all_games():
            self.assertEqual([r for r in g.values('option') if r and r[0] == 'DM_VK_TBUF_GW_SSBO'], [], g.id)
            self.assertEqual([r for r in g.values('recommend') if any('DM_VK_TBUF_GW_SSBO' in c for c in r)], [], g.id)


@unittest.skipUnless((REPO / 'dm/games' / THREE_HOPES / 'game.tsv').is_file(), 'the profile of Fire Emblem Warriors: Three Hopes is not in this checkout')
class TbufSsboCopyThreeHopes(unittest.TestCase):
    def test_on_for_three_hopes_and_off_without_the_key(self):
        g = gamepy.load(THREE_HOPES)
        self.assertEqual(g.get('tbuf_ssbo_copy'), 'on')
        self.assertEqual(macro(g), '1')
        self.assertEqual(macro(without_key(g)), '0')

    def test_three_hopes_blob_carries_it(self):
        g = gamepy.load(THREE_HOPES)
        on = gen.build_profile_bytes(g)
        off = gen.build_profile_bytes(without_key(g))
        self.assertEqual([(on[i], off[i]) for i in range(len(on)) if on[i] != off[i]], [(1, 0)])


if __name__ == '__main__':
    unittest.main()
