#!/usr/bin/env python3
"""The on-device packer's Python (android/ondevice/build.sh) carries every python-lz4 submodule the tools it runs import.

build.sh once built only lz4 and lz4.block, while shader_split.py opens HAL's LZ4-framed .cmp containers with lz4.frame: on the phone every such
container stayed closed (Kirby's Return to Dream Land Deluxe lost its 9,693 ptcl/ shaders and its Prepare stopped at "the dump's shaders are not
the ones this app knows")."""
import os, re, unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
# The tool folders the APK's tools.zip carries (android/app/build.gradle nxPackTools)
TOOL_DIRS = ['scripts', 'dm/tools', 'dm/games', 'dm/gfx/tools']


def imported_lz4_submodules():
    found = {}
    pat = re.compile(r'^\s*(?:import\s+lz4\.(\w+)|from\s+lz4\.(\w+)\s+import|from\s+lz4\s+import\s+(\w+))', re.M)
    for d in TOOL_DIRS:
        top = os.path.join(ROOT, d)
        for name in sorted(os.listdir(top)):
            if not name.endswith('.py'): continue
            src = open(os.path.join(top, name), encoding='utf-8').read()
            for m in pat.finditer(src):
                found.setdefault(next(g for g in m.groups() if g), f'{d}/{name}')
    return found


class OndeviceLz4(unittest.TestCase):
    def test_every_imported_submodule_is_built(self):
        sh = open(os.path.join(ROOT, 'android/ondevice/build.sh'), encoding='utf-8').read()
        subs = imported_lz4_submodules()
        self.assertIn('frame', subs)   # shader_split.py's unsized(); the scan itself must keep finding it
        for sub, where in subs.items():
            self.assertTrue(re.search(rf'-o \$SP/lz4/{sub}/_{sub}\.\$SFX ', sh), f'{where} imports lz4.{sub}, which build.sh does not build for the phone')
            self.assertTrue(re.search(rf'cp \$L/lz4/{sub}/\S*\.py \$SP/lz4/{sub}/', sh), f'build.sh does not copy lz4.{sub}\'s Python files')


if __name__ == '__main__':
    unittest.main()
