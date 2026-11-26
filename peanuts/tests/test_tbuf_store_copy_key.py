"""PEANUTS: the game profile key `tbuf_store_copy` (dm/games/README.md): a compute dispatch's storage texel buffer at an offset the device cannot view is bound through
an aligned scratch copy. It is a profile key the host reads (vka_draw.c TBUF_STORE_SW), not a player setting; it is off unless a game's profile turns it on.

The mechanism tests always run, on any profile of the checkout: the key is off by default, a value other than on or off is refused, and the per-title header (game.py) and
the generic host's runtime profile (gen_runtime_profile.py: the field is in profile.bin, after tbuf_ssbo_copy) carry it; no game offers the switch in the launcher. The
assertions about Mario + Rabbids Kingdom Battle (the game that turns it on) run only where that game's profile has the key's row."""
from pathlib import Path
import sys
import unittest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'dm/games'))
sys.path.insert(0, str(REPO / 'dm/peanuts/profile'))
import game as gamepy   # noqa: E402
import gen_runtime_profile as gen   # noqa: E402

KINGDOM_BATTLE = '010067300059A000'   # Mario + Rabbids Kingdom Battle


def macro(g):
    for line in gamepy.header(g).splitlines():
        if line.startswith('#define DM_GAME_TBUF_STORE_COPY '): return line.split()[-1]
    return None


def without_key(g):
    g.rows = [r for r in g.rows if r[0] != 'tbuf_store_copy']
    return g


def a_profile():
    """Any profile of the checkout that does not set the key (the key is off for it): the base the mechanism tests set the key on."""
    for g in gamepy.all_games():
        if not g.values('tbuf_store_copy'): return g
    return None


def kingdom_battle_has_row():
    if not (REPO / 'dm/games' / KINGDOM_BATTLE / 'game.tsv').is_file(): return False
    return bool(gamepy.load(KINGDOM_BATTLE).values('tbuf_store_copy'))


@unittest.skipUnless(a_profile() is not None, 'no profile without the key in this checkout')
class TbufStoreCopyMechanism(unittest.TestCase):
    def test_off_by_default(self):
        g = a_profile()
        self.assertEqual(macro(g), '0')
        g.rows.append(('tbuf_store_copy', ['off']))
        self.assertEqual(macro(g), '0')

    def test_on_sets_the_macro(self):
        g = a_profile()
        g.rows.append(('tbuf_store_copy', ['on']))
        self.assertEqual(macro(g), '1')

    def test_a_value_other_than_on_or_off_is_refused(self):
        for bad in ('yes', '1', 'ON', ''):
            g = a_profile()
            g.rows.append(('tbuf_store_copy', [bad]))
            with self.assertRaises(SystemExit, msg=repr(bad)):
                gamepy.header(g)

    def test_the_runtime_profile_carries_it(self):
        off = gen.build_profile_bytes(a_profile())
        g = a_profile()
        g.rows.append(('tbuf_store_copy', ['on']))
        on = gen.build_profile_bytes(g)
        self.assertEqual(len(on), len(off))
        diff = [i for i in range(len(on)) if on[i] != off[i]]
        self.assertEqual(len(diff), 1, 'the key changes one u32 of the blob (1 against 0)')
        self.assertEqual((on[diff[0]], off[diff[0]]), (1, 0))
        names = [n for n, _ in gen.LAYOUT]
        self.assertIn('tbuf_store_copy', names)
        self.assertEqual(names[names.index('tbuf_ssbo_copy') + 1], 'tbuf_store_copy', 'the field after tbuf_ssbo_copy')
        self.assertEqual(names[-1], 'tbuf_store_copy', 'the last field')

    def test_independent_of_tbuf_ssbo_copy(self):
        g = a_profile()
        g.rows.append(('tbuf_ssbo_copy', ['on']))
        self.assertEqual(macro(g), '0', 'the other texel-buffer key does not turn this one on')
        ssbo = gen.build_profile_bytes(g)
        g.rows.append(('tbuf_store_copy', ['on']))
        both = gen.build_profile_bytes(g)
        self.assertEqual(len([i for i in range(len(both)) if both[i] != ssbo[i]]), 1, 'a field of its own')

    def test_no_launcher_option_for_the_switch(self):
        for g in gamepy.all_games():
            self.assertEqual([r for r in g.values('option') if r and r[0] == 'DM_VK_TBUF_STORE_COPY'], [], g.id)
            self.assertEqual([r for r in g.values('recommend') if any('DM_VK_TBUF_STORE_COPY' in c for c in r)], [], g.id)


@unittest.skipUnless(kingdom_battle_has_row(), 'the profile of Mario + Rabbids Kingdom Battle has no tbuf_store_copy row in this checkout')
class TbufStoreCopyKingdomBattle(unittest.TestCase):
    def test_on_for_kingdom_battle_and_off_without_the_key(self):
        g = gamepy.load(KINGDOM_BATTLE)
        self.assertEqual(g.get('tbuf_store_copy'), 'on')
        self.assertEqual(macro(g), '1')
        self.assertEqual(macro(without_key(g)), '0')

    def test_kingdom_battle_blob_carries_it(self):
        g = gamepy.load(KINGDOM_BATTLE)
        on = gen.build_profile_bytes(g)
        off = gen.build_profile_bytes(without_key(g))
        self.assertEqual([(on[i], off[i]) for i in range(len(on)) if on[i] != off[i]], [(1, 0)])


if __name__ == '__main__':
    unittest.main()
