"""PEANUTS: scripts/pack_android.py's folder for the library build's temporary files follows --shader-cache. A pack made with --out and --shader-cache
in another place than the game (a .nup read in place on a drive the pack must not be written to) still made <title ID>-work/android-shaders/work beside the
.nup, because that folder was always taken from the game's folder. Without --shader-cache it is where it was. No game files needed."""
import argparse
import inspect
import importlib
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'scripts'))
pack_android = importlib.import_module('pack_android')


class ShaderWork(unittest.TestCase):
    def test_default_is_where_it_was(self):
        work = Path('/games/Game/0100000000010000-work')
        self.assertEqual(pack_android.shader_work(argparse.Namespace(shader_cache=None), work), work / 'android-shaders' / 'work')

    def test_beside_the_cache_that_was_asked_for(self):
        with tempfile.TemporaryDirectory() as d:
            game, mine = Path(d) / 'drive/Game', Path(d) / 'mine/0100000000010000-work/android-shaders'
            game.mkdir(parents=True)
            got = pack_android.shader_work(argparse.Namespace(shader_cache=mine / 'lib'), game / '0100000000010000-work')
            self.assertEqual(got, (mine / 'work').resolve())
            got.mkdir(parents=True)
            self.assertEqual([p.name for p in game.iterdir()], [])   # nothing beside the game

    def test_the_default_cache_named_gives_the_default_folder(self):   # --shader-cache <work>/android-shaders/lib is the default spelled out
        with tempfile.TemporaryDirectory() as d:
            work = Path(d).resolve() / 'w'
            self.assertEqual(pack_android.shader_work(argparse.Namespace(shader_cache=work / 'android-shaders' / 'lib'), work), work / 'android-shaders' / 'work')

    def test_the_library_build_uses_it(self):
        src = inspect.getsource(pack_android.pack_shaders)
        self.assertIn('shader_work(a, work)', src)
        self.assertNotIn("work / 'android-shaders' / 'work'", src)
        self.assertNotIn("work / 'android-shaders' / 'keys.txt'", src)

    def test_optional_absences_reach_builder_without_relaxing_package_checks(self):  # PEANUTS
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            pkg = root / 'pkg'
            pkg.mkdir()
            profile = argparse.Namespace(id='test', dir=root)
            args = argparse.Namespace(shader_cache=root / 'cache', shader_pkg=pkg,
                                      shader_python=None, in_process=False, J=1)

            def run(cmd, env):
                self.assertEqual(Path(env['DMSH_VK_ABSENT_FILE']).read_text().splitlines(), ['a', 'b'])
                self.assertEqual(env['DMSH_VK_REQUIRE_ALL'], '1')
                return argparse.Namespace(returncode=1)

            with patch.multiple(pack_android, require_shaders=lambda g: None,
                                vk_chain=lambda: 'test', library_current=lambda *a: False,
                                shader_python=lambda p: 'python', child_env=lambda **kw: kw,
                                peanuts_env=lambda s: {'DMSH_VK_REQUIRE_ALL': '1'},
                                selected_keys=lambda p: None, absent_optional=lambda *a: {'b', 'a'}):
                with patch.object(pack_android.subprocess, 'run', side_effect=run) as child:
                    with self.assertRaisesRegex(pack_android.Error, 'exit code 1'):
                        pack_android.pack_shaders(args, profile, root / 'game', root / 'out', root / 'v')
                    child.assert_called_once()

    def test_windows_sibling_caches_keep_each_child_selection_until_exit(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            pkg = root / 'pkg'; pkg.mkdir()
            profiles = [argparse.Namespace(id=str(i), dir=root / str(i)) for i in range(2)]
            args = [argparse.Namespace(shader_cache=root / 'cache' / str(i), shader_pkg=pkg,
                                       shader_python=None, in_process=False, J=1) for i in range(2)]
            files = []

            def build(i):
                with self.assertRaises(pack_android.Error):
                    pack_android.pack_shaders(args[i], profiles[i], root / 'game', root / 'out', root / 'v')

            def run(cmd, env):
                kf = Path(cmd[-1][1:])
                files.append(kf)
                if len(files) == 1:
                    build(1)   # a second child finishes while the first still needs its selection
                    self.assertFalse(files[1].exists())
                    self.assertEqual(kf.read_text(), '0\n')
                else:
                    self.assertEqual(kf.read_text(), '1\n')
                return argparse.Namespace(returncode=1)

            with patch.multiple(pack_android, WINDOWS=True, require_shaders=lambda g: None,
                                vk_chain=lambda: 'test', library_current=lambda *a: False,
                                shader_python=lambda p: 'python', child_env=lambda **kw: kw,
                                peanuts_env=lambda s: {}, selected_keys=lambda p: [p.name],
                                absent_optional=lambda *a: set()):
                with patch.object(pack_android.subprocess, 'run', side_effect=run):
                    build(0)
            self.assertEqual(len(set(files)), 2)
            self.assertTrue(all(not p.exists() for p in files))


if __name__ == '__main__':
    unittest.main()
