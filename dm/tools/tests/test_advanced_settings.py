"""docs/advanced_settings.tsv keeps a player-facing row for every switch of docs/env_switches.tsv (dm/tools/advanced_switches.py): a new DM_ switch cannot ship without a
tier, and a friendly or advanced row cannot ship without a label and a "try this if" line."""
from pathlib import Path
import sys
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import advanced_switches as A

ENV = {'DM_A': ('switch', 'meaning'), 'DM_B': ('diagnostic', 'meaning'), 'DM_P': ('path', 'meaning')}


def row(name, **kw):
    r = {c: '' for c in A.COLS}
    r.update(name=name, tier='developer', area='Diagnostics', type='text', scope='both')
    r.update(kw)
    return r


FRIENDLY = dict(tier='friendly', area='Graphics', label='A thing', try_if='the picture flickers', type='toggle', choices='Off=0;On=1', default='Off')


class AdvancedSettings(unittest.TestCase):
    def test_real_table_is_complete_and_valid(self):
        keys, ids = A.profile_keys()
        wp = REPO / 'wiki/Settings-Advanced.md'
        bad = A.problems(A.load(), A.read_env(), keys, ids, A.java_strings(), wp.read_text() if wp.exists() else '')
        self.assertEqual(bad, [])
        self.assertEqual((A.TABLE).read_text(), A.text(A.load()), 'run dm/tools/advanced_switches.py')

    def test_missing_and_extra_rows_fail(self):
        self.assertEqual(A.problems([row('DM_A'), row('DM_B'), row('DM_P')], ENV), [])
        self.assertTrue(any('DM_P' in b and 'no row' in b for b in A.problems([row('DM_A'), row('DM_B')], ENV)))
        self.assertTrue(any('DM_X' in b for b in A.problems([row('DM_A'), row('DM_B'), row('DM_P'), row('DM_X')], ENV)))

    def test_new_switch_gets_a_hidden_tier(self):
        self.assertEqual(A.seed('DM_NEW', 'switch', 'x')['tier'], 'developer')
        self.assertEqual(A.seed('DM_T', 'test', 'x')['tier'], 'internal')
        self.assertEqual(A.seed('DM_VK_IMG_X', 'switch', 'x')['area'], 'Memory')

    def test_friendly_rules(self):
        ok = [row('DM_A', **FRIENDLY), row('DM_B'), row('DM_P')]
        self.assertEqual(A.problems(ok, ENV), [])
        def fails(**kw):
            r = dict(FRIENDLY); r.update(kw)
            return A.problems([row('DM_A', **r), row('DM_B'), row('DM_P')], ENV)
        self.assertTrue(fails(label=''))
        self.assertTrue(fails(try_if=''))
        self.assertTrue(fails(try_if='Try this'))
        self.assertTrue(fails(label='x' * 33))
        self.assertTrue(fails(default=''))
        self.assertTrue(fails(type='text'))
        self.assertTrue(fails(choices='Off;On'))
        self.assertTrue(fails(label='café'))
        self.assertTrue(fails(tier='bogus'))
        self.assertTrue(fails(type='number', choices='1-5'))
        self.assertEqual(fails(type='number', choices='1..5 MiB'), [])
        # a path or test switch is never shown unless a launcher control owns it
        self.assertTrue(A.problems([row('DM_A'), row('DM_B'), row('DM_P', **FRIENDLY)], ENV))
        self.assertEqual(A.problems([row('DM_A'), row('DM_B'), row('DM_P', tier='advanced', label='GPU driver', owner='Settings > GPU driver')], ENV), [])
        self.assertTrue(A.problems([row('DM_A', tier='internal'), row('DM_B'), row('DM_P')], ENV))   # internal without a reason
        self.assertTrue(A.problems([row('DM_A', warn='careful'), row('DM_B'), row('DM_P')], ENV))     # a developer row carries no warning

    def test_owner_and_profile_checks(self):
        r = row('DM_A', tier='advanced', label='Zzz', owner='Game settings > Zzz', type='toggle')
        self.assertTrue(A.problems([r, row('DM_B'), row('DM_P')], ENV, java='"Other"'))
        self.assertEqual(A.problems([r, row('DM_B'), row('DM_P')], ENV, java='x "Zzz" y'), [])
        self.assertTrue(A.problems([row('DM_A', profile_key='nope'), row('DM_B'), row('DM_P')], ENV, keys={'fps_target'}, ids=set()))
        self.assertTrue(A.problems([row('DM_A', games='0400000000000000'), row('DM_B'), row('DM_P')], ENV, keys=set(), ids={'1'}))

    def test_friendly_label_is_in_the_wiki(self):
        rows = [row('DM_A', **FRIENDLY), row('DM_B'), row('DM_P')]
        self.assertTrue(A.problems(rows, ENV, wiki='nothing'))
        self.assertEqual(A.problems(rows, ENV, wiki='the A thing setting'), [])


if __name__ == '__main__':
    unittest.main()
