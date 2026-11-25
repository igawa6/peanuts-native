"""The Peanuts icons (android/app/src/peanuts/res/drawable/pi_*.xml) are what peanuts/scripts/icons.py writes: every file, byte for byte, and no
pi_*.xml without its icon there. The files said they came from an icons.py that was not in the repository (igawa6's note on #315); the script is
now the one source, so a new icon or a changed one goes through it."""
import contextlib
import importlib.util
import io
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'peanuts/scripts/icons.py'
DRAWABLE = REPO / 'android/app/src/peanuts/res/drawable'


def load():
    spec = importlib.util.spec_from_file_location('peanuts_icons', SCRIPT)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class Icons(unittest.TestCase):
    def test_files_are_what_the_script_writes(self):
        r = subprocess.run([sys.executable, str(SCRIPT), '--check'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_each_file_names_its_source(self):
        files = sorted(DRAWABLE.glob('pi_*.xml'))
        self.assertGreater(len(files), 30)
        for f in files:
            self.assertIn('written by peanuts/scripts/icons.py', f.read_text(encoding='utf-8').splitlines()[1], f.name)

    def test_check_finds_a_hand_edit_and_a_stray_file(self):
        m = load()
        with tempfile.TemporaryDirectory(prefix='icons_') as t:
            out = Path(t)
            for f in DRAWABLE.glob('pi_*.xml'):
                shutil.copy(f, out / f.name)
            m.OUT = out
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(m.main(['icons.py', '--check']), 0)
            (out / 'pi_key.xml').write_text((out / 'pi_key.xml').read_text(encoding='utf-8').replace('1.75', '2'), encoding='utf-8')
            (out / 'pi_stray.xml').write_text('<vector/>', encoding='utf-8')
            said = io.StringIO()
            with contextlib.redirect_stdout(said):
                self.assertEqual(m.main(['icons.py', '--check']), 1)
            self.assertIn('pi_key.xml', said.getvalue())
            self.assertIn('pi_stray.xml', said.getvalue())
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(m.main(['icons.py']), 0)   # written again: the key as before, the stray file gone
                self.assertEqual(m.main(['icons.py', '--check']), 0)
            self.assertFalse((out / 'pi_stray.xml').exists())
            self.assertEqual((out / 'pi_key.xml').read_bytes(), (DRAWABLE / 'pi_key.xml').read_bytes())

    def test_layers_follow_the_rules(self):
        m = load()
        for name, layers in m.ICONS.items():
            self.assertRegex(name, r'^[a-z][a-z_]*$', 'a resource name: pi_' + name)
            kinds = [k for k, _ in layers]
            self.assertTrue(set(kinds) <= {'s', 'k', 'f'}, name)
            self.assertIn('s', kinds, name + ': every icon has a shell')
            for _, d in layers:
                self.assertRegex(d, r'^M', name + ': path data starts with a move')
                self.assertNotIn('"', d, name)


if __name__ == '__main__':
    unittest.main()
