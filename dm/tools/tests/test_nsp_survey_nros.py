"""PEANUTS: the NSP script's survey lists the RomFS's NRO files, not every path that ends in .nro. The Binding of Isaac: Afterbirth+ keeps its two NROs in
a RomFS folder that is itself named `.nro` (`.nro/AfterbirthPlus.nro`, `.nro/Repentance.nro`); the survey's manifest pass copies them to
<survey>/extract/romfs_nro/.nro/, rglob('*.nro') matched that folder as well, and profile_guess.nro_modules read it as a module: the survey stopped with
IsADirectoryError before it wrote survey.md. No game files or Xcode needed."""
import importlib.util
import inspect
from pathlib import Path
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'dm/games'))
spec = importlib.util.spec_from_file_location('build_ipa_from_nsp', REPO / 'scripts/build_ipa_from_nsp.py')
if spec is None or spec.loader is None: raise ImportError('scripts/build_ipa_from_nsp.py')
nsp = importlib.util.module_from_spec(spec); spec.loader.exec_module(nsp)


class SurveyNros(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.d = Path(self.tmp.name) / 'romfs_nro'
        for rel in ('.nro/AfterbirthPlus.nro', '.nro/Repentance.nro', 'nro/engine.nro'):
            (self.d / rel).parent.mkdir(parents=True, exist_ok=True); (self.d / rel).write_bytes(b'nro ' + rel.encode())
        (self.d / 'mods.nro').mkdir()   # an empty folder with the ending
        (self.d / 'nro/readme.txt').write_bytes(b'x')

    def test_the_fault(self):   # what the survey's own expression gave: the folders too
        names = {p.name for p in self.d.rglob('*.nro')}
        self.assertIn('.nro', names); self.assertIn('mods.nro', names)

    def test_files_only(self):
        got = nsp.survey_nros(self.d)
        self.assertEqual(sorted(got), ['AfterbirthPlus.nro', 'Repentance.nro', 'engine.nro'])
        self.assertEqual(got['AfterbirthPlus.nro'], self.d / '.nro/AfterbirthPlus.nro')
        for p in got.values(): self.assertTrue(p.read_bytes().startswith(b'nro '))   # each one can be read as a module

    def test_no_folder_no_nro(self):
        self.assertEqual(nsp.survey_nros(self.d / 'missing'), {})
        self.assertEqual(nsp.survey_nros(self.d / 'mods.nro'), {})

    def test_the_survey_uses_it(self):
        src = inspect.getsource(nsp.survey)
        self.assertIn("survey_nros(ex / 'romfs_nro')", src)
        self.assertNotIn("rglob('*.nro')", src)


if __name__ == '__main__':
    unittest.main()
