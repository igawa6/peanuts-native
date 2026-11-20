"""The game catalog (peanuts/games.json and the README's Supported games table, peanuts/scripts/games_catalog.py) and the GitHub issue forms
(.github/ISSUE_TEMPLATE, written by peanuts/scripts/issue_forms.py).

Fails on what is wrong in the scripts: a form GitHub would refuse, a catalog that misses a shipped game's versions, a problem form without its
log, an app word the forms name that the app no longer shows. Never fails because a game was added, removed or a version released: the
written files are then only out of date, which .github/workflows/catalog.yml fixes on main; the test says so and skips.
"""
from pathlib import Path
import importlib.util
import json
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'android/app/src/main/java'


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / f'peanuts/scripts/{name}.py')
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


forms_mod = load('issue_forms')
catalog_mod = load('games_catalog')

TYPES = {'markdown', 'textarea', 'input', 'dropdown', 'checkboxes'}
LABELS = {'bug', 'app', 'new-game', 'feature-request'}   # labels the repository has; a label it lacks is dropped by GitHub without a word
PROBLEM_FORMS = ('1_game_problem.yml', '2_build_problem.yml', '3_setup_problem.yml')
# Words of the app the forms send players to (buttons, rows, settings, card labels): each must still be in the app's text
APP_WORDS = ('Share build log', 'Share log', 'Save to a folder', 'Readiness check', 'Copy report', 'Ready, with limits', 'Not ready', 'Back up saves',
             'GPU driver', 'Swap screens', 'RAM saver', 'Frame generation', 'Frame rate cap', 'Game owned', 'Hold 60 fps', 'Dynamic resolution',
             'FSR sharpness', 'Upscaler', 'Use fast cores', 'Console mode', 'Advanced switches', 'Tester settings', 'Build again', 'Build anyway',
             'Wrong version', 'File gone', 'Not Supported Yet', 'Firmware', 'Keys', 'Restore saves', 'Import save')


def element(form, id_):
    for e in form['body']:
        if e.get('id') == id_:
            return e
    raise AssertionError(f'{form["name"]}: no element {id_}')


def required(e):
    return bool(e.get('validations', {}).get('required'))


class Catalog(unittest.TestCase):
    def setUp(self):
        self.warnings = []
        self.catalog = catalog_mod.build(warn=self.warnings.append)

    def test_every_shipped_game_with_what_it_plays(self):
        shipped = (ROOT / 'peanuts/release-titles.txt').read_text(encoding='utf-8').split()
        games = self.catalog['games']
        self.assertEqual(sorted(g['title_id'] for g in games), sorted(shipped), self.warnings)
        for g in games:
            self.assertRegex(g['title_id'], r'^[0-9A-F]{16}$')
            self.assertTrue(g['name'] and g['builds'], g['title_id'])
            for b in g['builds']:
                self.assertRegex(b['version'], r'^v\d+$', g['name'])
            self.assertEqual([b['number'] for b in g['builds']], sorted((b['number'] for b in g['builds']), reverse=True), g['name'])
        self.assertEqual([g['name'].casefold() for g in games], sorted(g['name'].casefold() for g in games))

    def test_android_builds_limit_the_versions(self):
        # Super Mario Bros. Wonder's profile knows three builds, Android plays 1.2.1 only (android_builds)
        wonder = next(g for g in self.catalog['games'] if g['title_id'] == '010015100B514000')
        self.assertEqual([b['version'] for b in wonder['builds']], ['v327680'])

    def test_the_written_catalog_and_tables(self):
        path = ROOT / 'peanuts/games.json'
        self.assertEqual(json.loads(path.read_text(encoding='utf-8'))['about'], self.catalog['about'])
        want = catalog_mod.outputs(warn=lambda m: None)
        self.assertEqual(sorted(want), sorted(('peanuts/games.json',) + catalog_mod.READMES), 'a README lost its Supported games markers')
        stale = [n for n, t in want.items() if (ROOT / n).read_text(encoding='utf-8') != t]
        if stale:
            self.skipTest(f'out of date: {", ".join(stale)} (a game, a version or a companion changed): catalog.yml writes them on main, or '
                          'run python3 peanuts/scripts/games_catalog.py')

    def test_the_table_is_every_shipped_game_in_its_order(self):
        rows = list(catalog_mod.game_rows())
        order = self.catalog['readme_order']
        self.assertEqual(sorted(order), sorted(g['title_id'] for g in self.catalog['games']))
        self.assertEqual(order[:len([t for t in rows if t in order])], [t for t in rows if t in order])
        table = catalog_mod.table(self.catalog).splitlines()
        self.assertEqual(len([ln for ln in table if ln.startswith('| ') and '`' in ln]), len(order))
        ver = self.catalog['app_version']
        later = [g for g in self.catalog['games'] if catalog_mod.upcoming(g, ver)]   # games.tsv's since column: coming in a later release
        self.assertIn(f'Peanuts Native {ver} includes {len(order) - len(later)} games' + ('.' if not later else ','), table[0])
        for g in later:   # counted apart and marked in their row
            self.assertTrue(any(ln.startswith(f'| {g["name"]} (coming in {g["since"]}) |') for ln in table), g['name'])
        for g in self.catalog['games']:
            self.assertTrue(g['readme_update'] and '?' not in g['readme_update'], g['name'])

    def test_a_game_since_a_later_release_is_counted_apart(self):
        c = {'app_version': '0.1.5', 'readme_order': ['A', 'B'], 'games': [
            {'title_id': 'A', 'name': 'Old', 'readme_update': '1.0.0', 'companion': False, 'since': None},
            {'title_id': 'B', 'name': 'New', 'readme_update': '1.0.0', 'companion': False, 'since': '0.1.6'}]}
        t = catalog_mod.table(c).splitlines()
        self.assertTrue(t[0].startswith('Peanuts Native 0.1.5 includes 1 games, and 1 more is coming in 0.1.6'), t[0])
        self.assertIn('| New (coming in 0.1.6) | `B` |', '\n'.join(t))
        c['app_version'] = '0.1.6'
        self.assertTrue(catalog_mod.table(c).startswith('Peanuts Native 0.1.6 includes 2 games.'))
        c['app_version'] = '0.1.10'   # numbers, not text: 0.1.10 is after 0.1.6
        self.assertNotIn('coming in', catalog_mod.table(c))

    def test_only_the_table_is_written(self):
        b, e = catalog_mod.TABLE_BEGIN, catalog_mod.TABLE_END
        text = f'# Title\n\nbefore\n{b}\n\nold table\n\n{e}\n\nafter\n'
        out = catalog_mod.readme(text, self.catalog)
        self.assertTrue(out.startswith('# Title\n\nbefore\n' + b + '\n\n'))
        self.assertTrue(out.endswith('\n\n' + e + '\n\nafter\n'))
        self.assertNotIn('old table', out)
        self.assertEqual(catalog_mod.readme(out, self.catalog), out)
        self.assertIsNone(catalog_mod.readme('no markers', self.catalog))
        self.assertIsNone(catalog_mod.readme(f'{e}\n{b}\n', self.catalog))

    def test_refresh(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / 'peanuts').mkdir()
            (root / 'peanuts/release-titles.txt').write_text('0100000000000001\n0100000000000002\n', encoding='utf-8')
            (root / 'peanuts/games.tsv').write_text('# comment\n0100000000000001\tFirst Game\t\tno\n', encoding='utf-8')
            for t, n in (('0100000000000001', 'FIRST GAME'), ('0100000000000002', 'Second Game')):
                (root / 'dm/games' / t).mkdir(parents=True)
                (root / 'dm/games' / t / 'game.tsv').write_text(f'name\t{n}\nbuild\tv65536\t1.0.1\n', encoding='utf-8')
            said = []
            catalog_mod.refresh(root, said.append, fetch=lambda token: {'0100000000000001', '0100000000000002'})
            self.assertEqual((root / 'peanuts/games.tsv').read_text(encoding='utf-8'),
                             '# comment\n0100000000000001\tFirst Game\t\tyes\n0100000000000002\tSecond Game\t\tyes\n')
            self.assertEqual(len(said), 1)

            def offline(token):
                raise OSError('no network')
            catalog_mod.refresh(root, said.append, fetch=offline)   # the column stays, nothing is lost
            self.assertIn('0100000000000002\tSecond Game\t\tyes', (root / 'peanuts/games.tsv').read_text(encoding='utf-8'))
            self.assertIn('not read', said[-1])


class IssueForms(unittest.TestCase):
    def setUp(self):
        self.forms = {name: make() for name, make in forms_mod.FILES.items()}

    def test_the_written_forms(self):
        listed = sorted(p.name for p in forms_mod.FORMS.glob('*.yml') if p.name != 'config.yml')
        self.assertEqual(listed, sorted(forms_mod.FILES), 'a form the script does not write, or one it writes is missing')
        stale = [n for n in forms_mod.FILES if (forms_mod.FORMS / n).read_text(encoding='utf-8') != forms_mod.render(self.forms[n])]
        if stale:
            self.skipTest(f'out of date: {", ".join(stale)} (a game or a version changed): catalog.yml writes them on main, or run '
                          'python3 peanuts/scripts/issue_forms.py')

    def test_structure(self):
        for name, form in self.forms.items():
            self.assertTrue(form['name'] and form['description'], name)
            self.assertLessEqual(set(form.get('labels', [])), LABELS, name)
            ids, labels = set(), set()
            for e in form['body']:
                self.assertIn(e['type'], TYPES, name)
                if e['type'] == 'markdown':
                    self.assertTrue(e['attributes']['value'].strip(), name)
                    continue
                self.assertRegex(e['id'], r'^[a-z][a-z0-9_]*$', name)
                self.assertNotIn(e['id'], ids, f'{name}: id {e["id"]} twice')
                ids.add(e['id'])
                label = e['attributes']['label']
                self.assertNotIn(label, labels, f'{name}: label {label!r} twice')
                labels.add(label)
                if e['type'] in ('dropdown', 'checkboxes'):
                    words = [o['label'] if isinstance(o, dict) else o for o in e['attributes']['options']]
                    self.assertTrue(words and all(w.strip() for w in words), f'{name}: {e["id"]}')
                    self.assertEqual(len(words), len(set(words)), f'{name}: {e["id"]} has an option twice')
                    # GitHub adds its own "None" to a dropdown that is not required and refuses one among the options
                    self.assertNotIn('none', [w.casefold() for w in words], f'{name}: {e["id"]}')
            self.assertTrue(all(o.get('required') for o in element(form, 'rules')['attributes']['options']), name)

    def test_every_form_has_room_to_write(self):
        for name, form in self.forms.items():
            free = [e for e in form['body'] if e['type'] == 'textarea' and not required(e)]
            self.assertTrue(free, f'{name}: no optional text box for more')

    def test_each_problem_form_requires_its_log(self):
        game = self.forms['1_game_problem.yml']
        self.assertTrue(required(element(game, 'log')))
        self.assertIn('Share log', element(game, 'log')['attributes']['description'])
        self.assertFalse(required(element(game, 'build_log')), 'not every game problem follows a build')
        build = self.forms['2_build_problem.yml']
        self.assertTrue(required(element(build, 'build_log')))
        for w in ('Share build log', 'Save to a folder'):
            self.assertIn(w, element(build, 'build_log')['attributes']['description'])
        self.assertTrue(required(element(self.forms['4_builder_problem.yml'], 'output')))
        # the log of another game came three times: a required checkbox asks for this game's
        for name in ('1_game_problem.yml', '2_build_problem.yml'):
            opts = element(self.forms[name], 'checks')['attributes']['options']
            self.assertTrue(any(o.get('required') and 'title ID' in o['label'] for o in opts), name)

    def test_a_save_can_come_along(self):
        # a save lets a problem at a certain point be played again; it is never required, and a build has none
        for name in ('1_game_problem.yml', '3_setup_problem.yml'):
            save = element(self.forms[name], 'save')
            self.assertFalse(required(save), name)
            for w in ('Back up saves', 'save-backups/<title ID>/', '.manifest.tsv', '25 MB'):
                self.assertIn(w, save['attributes']['description'], name)
        self.assertFalse(required(element(self.forms['1_game_problem.yml'], 'save_from')))
        for name in ('2_build_problem.yml', '4_builder_problem.yml'):
            self.assertNotIn('save', [e.get('id') for e in self.forms[name]['body']], name)

    def test_the_game_list_is_the_catalog(self):
        games = catalog_mod.build(warn=lambda m: None)['games']
        want = []
        for g in games:
            want += [f'{g["name"]} {b["display"] or "update v" + str(b["number"])} ({g["title_id"]})' for b in g['builds']]
        want += [forms_mod.OTHER_VERSION, forms_mod.ANOTHER_GAME]
        for name in ('1_game_problem.yml', '2_build_problem.yml'):
            self.assertEqual(element(self.forms[name], 'game')['attributes']['options'], want, name)
            self.assertFalse(required(element(self.forms[name], 'game_other')), name)

    def test_the_newest_version_comes_first(self):
        v = (ROOT / 'peanuts/VERSION').read_text(encoding='utf-8').strip()
        for name in PROBLEM_FORMS:
            opts = element(self.forms[name], 'release')['attributes']['options']
            self.assertEqual(opts[0], f'{v} (the newest)', name)
            self.assertIn('test or development build', opts[-1], name)

    def test_the_problem_forms_ask_the_device_alike(self):
        first = self.forms[PROBLEM_FORMS[0]]
        for name in PROBLEM_FORMS[1:]:
            for id_ in ('release', 'device', 'model', 'chip', 'ram', 'android', 'readiness'):
                self.assertEqual(element(first, id_), element(self.forms[name], id_), f'{name}: {id_}')

    def test_each_form_points_to_the_others(self):
        intro = {n: f['body'][0]['attributes']['value'] for n, f in self.forms.items()}
        names = [f['name'] for f in self.forms.values()]
        for other in ('Game problem', 'Build problem', 'Setup or app problem', 'Desktop builder problem'):
            self.assertIn(other, names)
            self.assertIn(f'**{other}**', intro['7_question.yml'])
        for name, others in (('1_game_problem.yml', ('Build problem', 'Setup or app problem')),
                             ('2_build_problem.yml', ('Game problem', 'Setup or app problem')),
                             ('4_builder_problem.yml', ('Build problem',))):
            for other in others:
                self.assertIn(f'**{other}**', intro[name], name)

    def test_the_app_words_are_the_apps(self):
        files = list(JAVA.rglob('*.java')) + list((ROOT / 'android/app/src/main/res').glob('values*/strings.xml'))
        app = '\n'.join(p.read_text(encoding='utf-8', errors='replace') for p in files)
        forms = '\n'.join(forms_mod.render(f) for f in self.forms.values())
        for w in APP_WORDS:
            self.assertTrue(w in forms, f'{w!r} is no longer in the forms: drop it from APP_WORDS')
            self.assertTrue(f'"{w}' in app or f'>{w}<' in app, f'the forms name {w!r}, which the app no longer shows')

    def test_pyyaml_reads_what_was_meant(self):
        try:
            import yaml
        except ImportError:
            self.skipTest('PyYAML is not installed')
        for name, form in self.forms.items():
            data = yaml.safe_load(forms_mod.render(form))
            self.assertEqual(data['name'], form['name'])
            self.assertEqual(len(data['body']), len(form['body']), name)
            for got, want in zip(data['body'], form['body']):
                self.assertEqual(got.get('id'), want.get('id'), name)
                self.assertEqual(got['attributes'], want['attributes'], f'{name}: {want.get("id")}')


if __name__ == '__main__':
    unittest.main()
