"""PEANUTS: shader_split.unity_compute leaves out a compute kernel variant that has no code, with one counted line, and keeps the file's other kernels.
My Time at Sandrock (Unity 2021.3.26f1) keeps its shaders in one asset bundle, `shader`, whose SkyboxCompute has a kernel of 0 bytes
(CubemapFilteringSecondPassCS); unity_program raised on it (struct.error: a 96-byte header read from nothing), the scan took the whole file as
unreadable, and its 152 Shader objects were left out with it. A synthetic ComputeShader object is written from unityfs.py's 2021.3 layout into a serialized
file without type trees (the writer of test_unity_shader_2021_3.py). No game files needed."""
import contextlib
import io
import os
import struct
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools')); sys.path.insert(0, str(Path(__file__).resolve().parent))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '01007BB017812000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
import unityfs
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had
from test_unity_shader_2021_3 import serialized_file, write

HASH = bytes.fromhex('abd9135b8ce83d043fef4e9ec7f53366')   # COMPUTE_2021_3


def program(n):
    """Unity's Switch program code (2019.3 and later) with a compute stage alone: the 0x60-byte header (six stage offsets, control lengths and code lengths),
    a control section of 0x850 bytes (its own length at +0x20, the code area's at +0x704) and a code area of n bytes that starts with the compute magic"""
    nctl = 0x850
    h = [0] * 6 + [-1] * 5 + [0] + [0] * 5 + [nctl] + [0] * 5 + [n]
    ctl = bytearray(nctl); ctl[:4] = ss.CTL_MAGIC
    struct.pack_into('<I', ctl, 0x20, nctl); struct.pack_into('<I', ctl, 0x704, n)
    return struct.pack('<24i', *h) + bytes(ctl) + ss.CS_MAGIC + bytes(n - 4)


def compute_file(kernels):
    """A serialized file with one ComputeShader `Sky`: kernels = [(name, [code of each keyword variant])]"""
    ks = [{'name': name, 'variantMap': [(f'KW{i}', {'code': list(code), 'threadGroupSize': [8, 8, 1]}) for i, code in enumerate(codes)]} for name, codes in kernels]
    o = {'m_Name': 'Sky', 'variants': [{'targetRenderer': 22, 'kernels': ks, 'resourcesResolved': True}]}
    out = bytearray(); write(unityfs.known_tree(unityfs.CLASS_COMPUTE_SHADER, HASH), o, out)
    return serialized_file([bytes(out)], unityfs.CLASS_COMPUTE_SHADER, HASH)


def run(d):
    trees = {k: unityfs.known_tree(*k) for k in unityfs.KNOWN_TREES}
    had = ss.COMMON; ss.COMMON = {}
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf): got = ss.unity_compute(d, '/Data/shader', trees)
    finally: ss.COMMON = had
    return got, buf.getvalue().splitlines()


class EmptyKernel(unittest.TestCase):
    def test_every_kernel_has_code(self):
        got, lines = run(compute_file([('A', [program(0x40)]), ('B', [program(0x80), program(0xc0)])]))
        self.assertEqual([(w, st, len(cd)) for w, st, _, cd in got], [('/Data/shader#Sky#A.0', 'cs', 0x40), ('/Data/shader#Sky#B.0', 'cs', 0x80), ('/Data/shader#Sky#B.1', 'cs', 0xc0)])
        self.assertEqual([l for l in lines if not l.startswith('unity compute source: ')], [])   # nothing to say

    def test_a_kernel_without_code_is_left_out_and_counted(self):
        got, lines = run(compute_file([('A', [program(0x40)]), ('Second', [b'']), ('B', [b'', program(0xc0), b''])]))
        self.assertEqual([(w, st, len(cd)) for w, st, _, cd in got], [('/Data/shader#Sky#A.0', 'cs', 0x40), ('/Data/shader#Sky#B.1', 'cs', 0xc0)])   # B's variant keeps its number
        notes = [l for l in lines if not l.startswith('unity compute source: ')]
        self.assertEqual(notes, ['unity compute: 3 kernel variant(s) without code left out (first: /Data/shader#Sky#Second.0)'])
        self.assertEqual(len([l for l in lines if l.startswith('unity compute source: ')]), 2)

    def test_code_that_is_there_and_wrong_still_stops(self):
        with self.assertRaises((SystemExit, struct.error)): run(compute_file([('A', [program(0x40)[:-8]])]))   # only a kernel with no code at all is left out


if __name__ == '__main__':
    unittest.main()
