"""PEANUTS: read archived NVN shaders, not just GLSLC signatures in stored bytes."""
import struct
import tempfile
import unittest
from pathlib import Path

import lz4.block
from test_unity_text_glslc import glslc_output, ss


class P3a(unittest.TestCase):
    def test_lz4_shader_and_truncated_archive(self):
        shader = glslc_output()
        packed = lz4.block.compress(shader, store_size=False)
        name = b'asset/nx/shader/effect.nvn'
        header = struct.pack('<8sIIQQQII', b'PH3ARCV\0', 0, 1200, 1, 0, 0, 16, 0x130)
        entry = struct.pack('<256s6Q', name, 1, len(packed), len(shader), len(header) + 0x130, 0, 0)
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'asset_shader.p3a'
            p.write_bytes(header + entry + packed)
            _, keys, _, extra = ss.scan_file((str(p), p.name))
            self.assertEqual(extra, {})
            self.assertEqual([(v[0], v[1]) for v in keys.values()], [('/asset_shader.p3a/asset/nx/shader/effect.nvn#GLSLC@0x0', 'vs')])
            p.write_bytes(header + entry + packed[:-1])
            self.assertEqual(ss.scan_file((str(p), p.name))[3][0], 'unreadable')


if __name__ == '__main__':
    unittest.main()
