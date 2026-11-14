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

    def test_icon(self):
        Plan = namedtuple('Plan', 'name app version base_file update_file dlc_files')
        self.assertEqual(pb.icon_path(Path('/x/Game [0100][v1].nup')), Path('/x/Game [0100][v1].png'))
        with tempfile.TemporaryDirectory() as d:
            base = Path(d, 'a.nsp'); base.write_text('x')
            nup = Path(d, 'Game [0100][v1].nup'); nup.write_text('n')
            plan = Plan('Game', '0100', 1, base, None, [])
            calls = []
            def fail(cmd, dry, what, capture=False): calls.append(cmd); return 1, ''
            def ok(cmd, dry, what, capture=False): calls.append(cmd); Path(cmd[-1]).write_bytes(b'png'); return 0, ''
            real = pb.run
            try:
                pb.run = fail
                pb.write_icon('imp', 'k', nup, plan, False, False)   # failure: a warning, no exception, no file
                self.assertFalse(Path(d, 'Game [0100][v1].png').exists()); self.assertFalse(Path(d, 'Game [0100][v1].png.tmp').exists())
                pb.run = ok
                pb.write_icon('imp', 'k', nup, plan, False, False)
                png = Path(d, 'Game [0100][v1].png')
                self.assertEqual(png.read_bytes(), b'png')
                self.assertEqual(calls[-1][:6], ['imp', '--keys', 'k', 'icon', str(base), '0100'])
                n = len(calls)
                os.utime(png, (png.stat().st_mtime + 100,) * 2)
                pb.write_icon('imp', 'k', nup, plan, False, False)   # up to date: reused
                self.assertEqual(len(calls), n)
                pb.write_icon('imp', 'k', nup, plan, False, True)    # --force-nup: again
                self.assertEqual(len(calls), n + 1)
                # base fails, the update serves
                png.unlink(); upd = Path(d, 'u.nsp'); upd.write_text('u')
                plan2 = plan._replace(update_file=upd)
                def second(cmd, dry, what, capture=False):
                    if cmd[4] == str(base): return 1, ''
                    return ok(cmd, dry, what)
                pb.run = second
                pb.write_icon('imp', 'k', nup, plan2, False, False)
                self.assertTrue(png.is_file())
                self.assertEqual(calls[-1][4:6], [str(upd), '-'])
            finally: pb.run = real


if __name__ == '__main__': unittest.main()
