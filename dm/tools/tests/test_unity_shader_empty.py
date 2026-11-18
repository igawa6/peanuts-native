"""PEANUTS: shader_split.unity_shaders leaves out a Shader subprogram without code (a blob entry of 0 bytes, or a subprogram whose program code is 0
bytes), with one counted line, and keeps the file's other shaders: what unity_compute does for a compute kernel without code
(test_unity_compute_empty.py). Reading such an entry raised, and the scan took the whole file as unreadable. No game was met with one; the case is the
same as My Time at Sandrock's empty compute kernel, which cost its `shader` bundle 152 Shader objects. A synthetic Shader object is written from
unityfs.py's 2021.3 layout, its blob an LZ4 segment (the writer of test_unity_shader_2021_3.py). No game files needed."""
import contextlib
import io
import os
import struct
import sys
import unittest
from pathlib import Path

import lz4.block

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools')); sys.path.insert(0, str(Path(__file__).resolve().parent))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '01007BB017812000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
import unityfs
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had
from test_unity_shader_2021_3 import HASH, player, serialized_file, shader, write


def program(stage, n):
    """Unity's Switch program code (2019.3 and later) with one stage: the 0x60-byte header, a control section of 0x850 bytes and a code area of n bytes"""
    nctl = 0x850; i = ss.NV_STAGES.index(stage)
    off = [-1] * 6; off[i] = 0
    ln = [0] * 6; ln[i] = nctl
    cl = [0] * 6; cl[i] = n
    ctl = bytearray(nctl); ctl[:4] = ss.CTL_MAGIC
    struct.pack_into('<I', ctl, 0x20, nctl); struct.pack_into('<I', ctl, 0x704, n)
    return struct.pack('<24i', *([0] * 6 + off + ln + cl)) + bytes(ctl) + ss.GFX_MAGIC + bytes(n - 4)


def subprogram(code):
    """A subprogram blob entry as unityfs.subprogram reads it (version 202012090: no local keyword list): the header, no keywords, the program code"""
    return struct.pack('<ii', 202012090, 26) + bytes(16) + struct.pack('<i', 0) + struct.pack('<i', len(code)) + code


def shader_file(entries):
    """A serialized file with one Shader whose blob holds the entries (bytes each) in one LZ4 segment; the vertex program's player subprograms are the even
    entries, the fragment program's the odd ones"""
    table, data, pos = b'', b'', 4 + 12 * len(entries)
    for e in entries:
        table += struct.pack('<III', pos, len(e), 0); data += e; pos += len(e)
    seg = struct.pack('<I', len(entries)) + table + data
    comp = lz4.block.compress(seg, store_size=False)
    o = shader()
    p = o['m_ParsedForm']['m_SubShaders'][0]['m_Passes'][0]
    p['progVertex'] = {'m_PlayerSubPrograms': [[player(i) for i in range(0, len(entries), 2)]], 'm_ParameterBlobIndices': [[]]}
    p['progFragment'] = {'m_PlayerSubPrograms': [[player(i) for i in range(1, len(entries), 2)]], 'm_ParameterBlobIndices': [[]]}
    o.update(platforms=[19], offsets=[[0]], compressedLengths=[[len(comp)]], decompressedLengths=[[len(seg)]], compressedBlob=list(comp), stageCounts=[2])
    out = bytearray(); write(unityfs.known_tree(unityfs.CLASS_SHADER, HASH), o, out)
    return serialized_file([bytes(out)])


def run(d):
    trees = {k: unityfs.known_tree(*k) for k in unityfs.KNOWN_TREES}
    had = ss.COMMON; ss.COMMON = {}
    buf = io.StringIO(); out = []
    try:
        with contextlib.redirect_stdout(buf): ss.unity_shaders(d, '/Data/shader', out, trees)
    finally: ss.COMMON = had
    return [(w, st, len(cd)) for w, st, _, cd in out], buf.getvalue().splitlines()


VS, PS = subprogram(program('vs', 0x40)), subprogram(program('ps', 0x80))


class EmptySubprogram(unittest.TestCase):
    def test_every_subprogram_has_code(self):
        got, lines = run(shader_file([VS, PS]))
        self.assertEqual(got, [('/Data/shader#Test/Shader#var19.0', 'vs', 0x40), ('/Data/shader#Test/Shader#var19.1', 'ps', 0x80)])
        self.assertEqual(lines, [])

    def test_entries_without_code_are_left_out_and_counted(self):
        # entry 1: a blob entry of 0 bytes; entry 2: a subprogram header whose program code is 0 bytes
        got, lines = run(shader_file([VS, b'', subprogram(b''), PS]))
        self.assertEqual(got, [('/Data/shader#Test/Shader#var19.0', 'vs', 0x40), ('/Data/shader#Test/Shader#var19.3', 'ps', 0x80)])   # entry 3 keeps its number
        self.assertEqual(lines, ['unity shaders: 2 subprogram(s) without code left out (first: /Data/shader#Test/Shader#var19.1)'])

    def test_code_that_is_there_and_wrong_still_stops(self):
        with self.assertRaises((SystemExit, struct.error)): run(shader_file([VS, subprogram(program('ps', 0x80)[:-8])]))


if __name__ == '__main__':
    unittest.main()
