"""dm/tools/advanced_fix.tsv, the fix finder's table (columns in its header and in dm/tools/advanced_fix.py): each rule is a log_rules.tsv id (or exit:<kind>, a verdict kind of
LogDigest.java), each row a friendly or advanced Advanced row the player can set and that accepts the value (advanced_switches.accepts, as AdvancedTable.Row.accepts), the
sentence is short plain ASCII, and wiki/Troubleshooting.md carries the table advanced_fix.py writes, so every row's label is named there."""
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools'))
import advanced_fix  # noqa: E402
import advanced_switches  # noqa: E402


class AdvancedFix(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.head, cls.rows = advanced_fix.load()
        cls.adv = {r['name']: r for r in advanced_switches.load()}
        cls.rules = advanced_fix.rules()

    def test_rules_of_the_table(self):
        self.assertTrue(self.rows, 'the table is empty')
        self.assertEqual(advanced_fix.problems(self.head, self.rows, self.adv, self.rules, advanced_fix.exit_kinds()), [])

    def test_the_checks_catch_bad_rows(self):
        ok = dict(self.rows[0])
        def bad(**kw):
            return advanced_fix.problems(advanced_fix.COLS, [dict(ok, **kw)], self.adv, self.rules, advanced_fix.exit_kinds())
        self.assertEqual(bad(), [])
        self.assertTrue(bad(rule_id='no-such-rule'))
        self.assertTrue(bad(rule_id='exit:no_such_kind'))
        self.assertTrue(bad(advanced_id='DM_NO_SUCH_SWITCH'))
        self.assertTrue(bad(advanced_id='DM_MEM_FILEBACK'))        # owned by a launcher control
        self.assertTrue(bad(advanced_id='DM_VK_TEX_STATS'))        # a developer row
        self.assertTrue(bad(value='7'))
        self.assertTrue(bad(scope='both'))
        self.assertTrue(bad(sentence='x' * 101 + '.'))
        self.assertTrue(bad(sentence='Try this.'))
        self.assertTrue(bad(sentence='No full stop'))

    def test_accepts_matches_the_launcher(self):
        a = self.adv
        self.assertTrue(advanced_switches.accepts(a['DM_VK_TEX_SOFT'], '1'))
        self.assertFalse(advanced_switches.accepts(a['DM_VK_TEX_SOFT'], '2'))
        self.assertTrue(advanced_switches.accepts(a['DM_VK_IMG_LIMIT_MB'], '1024'))
        self.assertFalse(advanced_switches.accepts(a['DM_VK_IMG_LIMIT_MB'], '100'))
        self.assertFalse(advanced_switches.accepts(a['DM_VK_IMG_LIMIT_MB'], 'x'))
        self.assertTrue(advanced_switches.accepts(a['DM_VK_TEX_STATS'], 'a=b'))

    def test_wiki_names_every_row_and_is_current(self):
        wiki = (REPO / 'wiki/Troubleshooting.md').read_text(encoding='utf-8')
        for r in self.rows: self.assertIn(self.adv[r['advanced_id']]['label'], wiki)
        self.assertEqual(wiki, advanced_fix.wiki_text(advanced_fix.table(self.rows, self.adv, self.rules)), 'run dm/tools/advanced_fix.py --write')


if __name__ == '__main__':
    unittest.main()
