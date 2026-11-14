#!/usr/bin/env python3
"""peanuts/scripts/peanuts_build.py: the environment's .NET, the games refused up front, --nup as a folder"""
import importlib.util, os, sys, tempfile, unittest
from collections import namedtuple
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('peanuts_build', ROOT / 'peanuts' / 'scripts' / 'peanuts_build.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)


class T(unittest.TestCase):
    def test_dotnet(self):
        with tempfile.TemporaryDirectory() as d:
            sub = ('dotnet',) if pb.WINDOWS else ('lib', 'dotnet')
            f = Path(d, *sub, 'dotnet.exe' if pb.WINDOWS else 'dotnet'); f.parent.mkdir(parents=True); f.write_text('')
            e = pb.with_dotnet({'PATH': 'x'}, d)
            self.assertEqual(e['DOTNET_ROOT'], str(f.parent)); self.assertTrue(e['PATH'].startswith(str(f.parent)))
            self.assertEqual(pb.with_dotnet({'DOTNET_ROOT': 'mine'}, d)['DOTNET_ROOT'], 'mine')
            self.assertNotIn('DOTNET_ROOT', pb.with_dotnet({}, d + '-none'))

    def test_refuse(self):
        os.environ.pop('DM_GLSLC_CAPTURE', None); os.environ.pop('DM_UE_SHADERS', None)
        for app in ('010055D009F78000', '01005270232F2000'):
            with self.assertRaises(SystemExit) as c: pb.check_device_steps(app, 'Game')
            self.assertIn('Build in the Peanuts app', str(c.exception)); self.assertTrue(str(c.exception).startswith('[RED] '))
        pb.check_device_steps('040074A01BF12000')   # no such steps (or no profile): no refusal

    def test_nup_folder(self):
        Plan = namedtuple('Plan', 'name app version')
        plan = Plan('Metroid Dread', '010093801237C000', 131328)
        with tempfile.TemporaryDirectory() as d:
            A = namedtuple('A', 'nup')
            self.assertEqual(pb.nup_path(A(d), plan, Path('/x/a.nsp')), Path(d).resolve() / 'Metroid Dread [010093801237C000][v131328].nup')
            self.assertEqual(pb.nup_path(A(d + '/f.nup'), plan, Path('/x/a.nsp')), Path(d).resolve() / 'f.nup')


if __name__ == '__main__': unittest.main()
