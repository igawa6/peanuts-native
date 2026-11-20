"""Check the desktop translator the Peanuts packer will use against the committed tool lock."""
import hashlib
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
# PEANUTS: resolve a source-built translator when the inherited desktop binaries predate the lock.
sys.path.insert(0, str(ROOT / 'peanuts/scripts'))
import prepare_pc_translator


class XlateBinLock(unittest.TestCase):
    def test_desktop_dlls_are_the_locked_ones(self):
        # PEANUTS: verify the same tool directory the desktop packer selected.
        directory = prepare_pc_translator.resolve().parent
        lock = ROOT / 'dm/gfx/tools/dmsh_chain.lock'
        want = dict(line.split('\t', 1) for line in lock.read_text().splitlines() if line and not line.startswith('#'))
        for name in ('dmsh_xlate.dll', 'Ryujinx.Graphics.Shader.dll', 'Ryujinx.Common.dll'):
            with self.subTest(dll=name):
                self.assertEqual(hashlib.sha256((directory / name).read_bytes()).hexdigest()[:16], want[name])

    # PEANUTS begin: the committed DLLs themselves, without a source build to stand in for them. A build of the locked source matches the
    # lock only with the SDK build that wrote it, so a commit that changes the lock without these DLLs stops every other machine (#161)
    def test_bundled_dlls_are_the_locked_ones(self):
        directory = ROOT / 'dm/gfx/tools/dmsh_xlate_bin'
        lock = ROOT / 'dm/gfx/tools/dmsh_chain.lock'
        want = dict(line.split('\t', 1) for line in lock.read_text().splitlines() if line and not line.startswith('#'))
        for name in ('dmsh_xlate.dll', 'Ryujinx.Graphics.Shader.dll', 'Ryujinx.Common.dll'):
            with self.subTest(dll=name):
                self.assertEqual(hashlib.sha256((directory / name).read_bytes()).hexdigest()[:16], want[name])
    # PEANUTS end

    def test_resolve_only_when_the_packer_needs_its_default_shader_translator(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        import pack_android
        with patch.dict(os.environ, clear=True), patch.object(prepare_pc_translator, 'resolve', return_value='/test/translator') as resolve:
            self.assertNotIn('XLATE', pack_android.peanuts_env('split'))
            resolve.assert_not_called()
            self.assertEqual(pack_android.peanuts_env('shaders')['XLATE'], '/test/translator')
            os.environ['XLATE'] = '/chosen/translator'
            self.assertNotIn('XLATE', pack_android.peanuts_env('shaders'))
            resolve.assert_called_once_with()


if __name__ == '__main__':
    unittest.main()
