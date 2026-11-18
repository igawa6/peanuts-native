"""PEANUTS: unityfs.py's Shader layout of Unity 2021.3.26f1 (KNOWN_TREES, type hash f0c184272a05e54447e36366ec4876a6; My Time at Sandrock, Unity
2021.3.26f1, whose serialized files and asset bundles all come without type trees). A synthetic Shader object is written field by field from the layout,
with values in the three fields the layout adds to 2021.2's (a program's m_PlayerSubPrograms and m_ParameterBlobIndices, the Shader's stageCounts), put in
a serialized file without type trees, and read back the way the shader split's second pass reads a stripped file: the tree comes from KNOWN_TREES by the
type hash. The object must read to its exact end, the 2021.2 layout must not read it so, and shader_split.unity_code_blobs must take the player programs'
blob indices. No game files needed."""
import os
import struct
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '01007BB017812000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
import unityfs
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had

HASH = bytes.fromhex('f0c184272a05e54447e36366ec4876a6')
HASH_2021_2 = bytes.fromhex('d8236c9bd87d85edb203acf352348124')


def write(n, v, out):
    """The inverse of unityfs._read: value v of type tree node n, appended to out (a bytearray; the object starts at its offset 0). A missing value is the
    type's zero: 0, '', no elements, a struct of zeros"""
    def align():
        out.extend(b'\0' * (-len(out) % 4))
    if n.type in unityfs.PRIM and not n.kids:
        out.extend(struct.pack('<' + unityfs.PRIM[n.type], v or 0))
    elif n.type == 'string':
        b = (v or '').encode() if not isinstance(v, bytes) else v
        out.extend(struct.pack('<i', len(b)) + b)
        if n.kids and n.kids[0].flags & 0x4000: align()
    elif n.kids and n.kids[0].type == 'Array':
        arr = n.kids[0]; el = arr.kids[1]; v = v or []
        out.extend(struct.pack('<i', len(v)))
        if el.type in ('UInt8', 'char', 'SInt8') and not el.kids: out.extend(bytes(v))
        else:
            for x in v: write(el, x, out)
        if arr.flags & 0x4000: align()
    else:
        v = v or {}
        if n.type == 'pair': v = {'first': v[0], 'second': v[1]} if isinstance(v, tuple) else v
        for k in n.kids: write(k, v.get(k.name), out)
    if n.flags & 0x4000: align()


def player(blob, kw=()):
    return {'m_BlobIndex': blob, 'm_KeywordIndices': list(kw), 'm_ShaderRequirements': 0x21, 'm_GpuProgramType': 26}


def shader():
    """A Shader of one subshader with one pass: the vertex program's player subprograms are blobs 0 and 2 (two tiers), its parameters blob 1; the fragment
    program's player subprogram is blob 3. Odd-length strings and arrays put the alignment rules to work"""
    vert = {'m_PlayerSubPrograms': [[player(0, (1, 2, 3))], [player(2)]], 'm_ParameterBlobIndices': [[1], []]}
    frag = {'m_PlayerSubPrograms': [[player(3, (7,))]], 'm_ParameterBlobIndices': [[4]]}
    p = {'m_Platforms': [19], 'm_NameIndices': [('_MainTex', 5)], 'm_Type': 0, 'm_ProgramMask': 6, 'progVertex': vert, 'progFragment': frag,
         'm_HasInstancingVariant': True, 'm_Name': 'FORWARD', 'm_Tags': {'tags': [('LightMode', 'ForwardBase')]}, 'm_SerializedKeywordStateMask': [9, 8, 7]}
    parsed = {'m_SubShaders': [{'m_Passes': [p], 'm_LOD': 200}], 'm_KeywordNames': ['A', 'BC'], 'm_KeywordFlags': [1, 0, 1], 'm_Name': 'Test/Shader',
              'm_FallbackName': 'Diffuse', 'm_DisableNoSubshadersMessage': True}
    return {'m_Name': 'TestShader', 'm_ParsedForm': parsed, 'platforms': [19], 'offsets': [[0, 16]], 'compressedLengths': [[16, 5]], 'decompressedLengths': [[32, 5]],
            'compressedBlob': list(b'0123456789abcdefghijk'), 'stageCounts': [2], 'm_Dependencies': [{'m_FileID': 0, 'm_PathID': 77}],
            'm_NonModifiableTextures': [('_Tex', {'m_FileID': 1, 'm_PathID': 5})], 'm_ShaderIsBaked': True}


def shader_bytes(tree=None):
    out = bytearray(); write(tree or unityfs.known_tree(unityfs.CLASS_SHADER, HASH), shader(), out); return bytes(out)


def serialized_file(objects, cid=unityfs.CLASS_SHADER, h=HASH):
    """A serialized file (format 17) without type trees whose objects are all of class cid with type hash h, as unityfs._tables reads it"""
    meta = bytearray(b'2021.3.26f1\0') + struct.pack('<i', 38) + b'\0' + struct.pack('<i', 1) + struct.pack('<i', cid) + b'\0' + struct.pack('<h', 0) + h
    meta += struct.pack('<i', len(objects))
    start = 0
    for i, b in enumerate(objects):
        meta += b'\0' * (-len(meta) % 4)
        meta += struct.pack('<qIIi', i + 1, start, len(b), 0)
        start += len(b)
    doff = 20 + len(meta)
    return struct.pack('>IIII', len(meta), doff + start, 17, doff) + b'\0\0\0\0' + bytes(meta) + b''.join(objects)


class Layout(unittest.TestCase):
    def test_known(self):
        self.assertEqual(unityfs.KNOWN_TREES[unityfs.CLASS_SHADER, HASH][0], 'Shader')
        self.assertIs(unityfs.KNOWN_TREES[unityfs.CLASS_SHADER, HASH][1], unityfs.SHADER_2021_3)

    def test_what_it_adds_to_2021_2(self):
        def fields(layout, name):
            line = next(l for l in layout.strip().split('\n') if l.startswith(name + ': '))
            return [f.rsplit(' ', 1)[1] for f in line.split(': ', 1)[1].split('; ')]
        a, b = unityfs.SHADER_2021_2, unityfs.SHADER_2021_3
        self.assertEqual(fields(b, 'SerializedProgram'), ['m_SubPrograms', 'm_PlayerSubPrograms', 'm_ParameterBlobIndices', 'm_CommonParameters'])
        self.assertEqual(fields(b, 'SerializedPlayerSubProgram'), ['m_BlobIndex', 'm_KeywordIndices', 'm_ShaderRequirements', 'm_GpuProgramType'])
        sa, sb = fields(a, 'Shader'), fields(b, 'Shader')
        self.assertEqual(sb, sa[:sa.index('compressedBlob') + 1] + ['stageCounts'] + sa[sa.index('compressedBlob') + 1:])
        for name in ('SerializedPass', 'SerializedSubProgram', 'SerializedShader', 'SerializedSubShader'):   # the pass keeps its keyword state mask, as 2021.2
            self.assertEqual(fields(b, name), fields(a, name))
        self.assertEqual(unityfs.SHADER_2021_2.count('\n') + 1, unityfs.SHADER_2021_3.count('\n'))   # one struct more, none lost

    def test_reads_to_the_exact_end(self):
        body = shader_bytes()
        r = unityfs.Reader(body, True)
        o = unityfs._read(r, unityfs.known_tree(unityfs.CLASS_SHADER, HASH))
        assert isinstance(o, dict)
        self.assertEqual(r.p, len(body))
        self.assertEqual(o['stageCounts'], [2])
        self.assertEqual(o['compressedBlob'], b'0123456789abcdefghijk')
        self.assertEqual(o['m_Dependencies'], [{'m_FileID': 0, 'm_PathID': 77}])
        self.assertTrue(o['m_ShaderIsBaked'])
        p = o['m_ParsedForm']['m_SubShaders'][0]['m_Passes'][0]
        self.assertEqual(p['m_Name'], 'FORWARD')
        self.assertEqual(p['m_SerializedKeywordStateMask'], [9, 8, 7])
        self.assertEqual([[s['m_BlobIndex'] for s in tier] for tier in p['progVertex']['m_PlayerSubPrograms']], [[0], [2]])
        self.assertEqual(p['progVertex']['m_PlayerSubPrograms'][0][0]['m_KeywordIndices'], [1, 2, 3])
        self.assertEqual(p['progVertex']['m_ParameterBlobIndices'], [[1], []])
        self.assertEqual(p['progFragment']['m_PlayerSubPrograms'][0][0]['m_GpuProgramType'], 26)

    def test_the_2021_2_layout_does_not_read_it(self):
        body = shader_bytes()
        r = unityfs.Reader(body, True)
        try: o = unityfs._read(r, unityfs.known_tree(unityfs.CLASS_SHADER, HASH_2021_2))
        except (struct.error, MemoryError, ValueError, OverflowError): return   # it ran off the object
        self.assertFalse(r.p == len(body) and isinstance(o, dict) and o.get('m_ShaderIsBaked') is True and o['m_ParsedForm']['m_Name'] == 'Test/Shader')

    def test_a_stripped_file_borrows_it_by_hash(self):
        d = serialized_file([shader_bytes(), shader_bytes()])
        with self.assertRaises(unityfs.NoTree): list(unityfs.serialized_objects(d, {}, {unityfs.CLASS_SHADER}, {}))   # the first pass: no tree
        trees = {k: unityfs.known_tree(*k) for k in unityfs.KNOWN_TREES}   # the second pass (shader_split.split_tables)
        got = list(unityfs.serialized_objects(d, {}, {unityfs.CLASS_SHADER}, trees))
        names = []
        for cid, pid, o in got:
            assert isinstance(o, dict)
            names.append((cid, pid, o['m_Name']))
        self.assertEqual(names, [(48, 1, 'TestShader'), (48, 2, 'TestShader')])

    def test_the_split_takes_the_player_programs(self):
        d = serialized_file([shader_bytes()])
        trees = {k: unityfs.known_tree(*k) for k in unityfs.KNOWN_TREES}
        (_, _, o), = unityfs.serialized_objects(d, {}, {unityfs.CLASS_SHADER}, trees)
        self.assertEqual(ss.unity_code_blobs(o), {0, 2, 3})   # blobs 1 and 4 hold parameters, not code


if __name__ == '__main__':
    unittest.main()
