"""PEANUTS: shader_split reads South Park: The Fractured But Whole's bare zlib streams (the shader cache files camel/shadercache/nx/*.mcache in its SDF data
slices): each cache file is one zlib stream, without the 20-byte record header the SNOWDROP reader expects; its decoded head is a 0xabcd00d4 record, then its kind,
0 and twice the length of what follows its 20-byte header, and the shader objects inside have the same layout as in the c5 / c6 records (snowdrop_zlib).
Synthetic cache records written from that layout. No game files needed."""
import io
import os
import struct
import sys
import unittest
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '010029B0118E8000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had

SPH_TYPE = {'vs': 1, 'ps': 5}


def noise(n, seed=1):
    out = bytearray(); x = seed
    while len(out) < n: x = (x * 1103515245 + 12345) & 0x7fffffff; out += struct.pack('<I', x)
    return bytes(out[:n])


def shader_object(stage, seed):
    """One GLSLC shader object: a 0x760-byte control section (CTL_MAGIC, +0x20 its own length, +0x704 the code area's length) then its code area
    (GFX_MAGIC, the SPH word at +0x30 with the stage in bits 10..13). The rest is noise, so the zlib stream stays long enough to damage past its head."""
    code = bytearray(noise(0x40, seed))
    code[0:4] = ss.GFX_MAGIC
    struct.pack_into('<I', code, 0x30, SPH_TYPE[stage] << 10 | 1)
    ctl = bytearray(noise(0x760, seed + 1000))
    ctl[0:4] = ss.CTL_MAGIC
    struct.pack_into('<I', ctl, 0x20, 0x760)
    struct.pack_into('<I', ctl, 0x704, len(code))
    return bytes(ctl), bytes(code)


def cache_record(objects, kind=0x13c):
    """One .mcache file as the SDF slices store it: a 20-byte head {0xabcd00d4, kind, 0, length, length}, then the shader objects."""
    body = b''.join(ctl + code for ctl, code in objects)
    return struct.pack('<IIIII', 0xabcd00d4, kind, 0, len(body), len(body)) + body


def sdf_slice(records):
    """A data slice holding one bare zlib stream per cache file, in order."""
    return b''.join(zlib.compress(record) for record in records)


class SnowdropZlib(unittest.TestCase):
    def test_two_stages_come_back_with_their_keys(self):
        vs = shader_object('vs', 7)
        ps = shader_object('ps', 9)
        d = sdf_slice([cache_record([vs]), cache_record([ps])])
        found = ss.snowdrop_zlib(d, 'test/slice.sdfdata')
        self.assertEqual(len(found), 2)
        self.assertEqual({st for _, st, _, _ in found}, {'vs', 'ps'})
        self.assertEqual({ss.skey(ctl, code) for _, _, ctl, code in found}, {ss.skey(*vs), ss.skey(*ps)})
        # the wrapped entry point finds the same two stages
        wrapped = ss.snowdrop_shaders(d, 'test/slice.sdfdata')
        self.assertEqual({ss.skey(ctl, code) for _, _, ctl, code in wrapped}, {ss.skey(*vs), ss.skey(*ps)})

    def test_a_damaged_stream_stops_with_a_reason(self):
        d = bytearray(sdf_slice([cache_record([shader_object('vs', 7)])]))
        pos = len(d) // 2   # past the head peek, which only reads the stream's first bytes
        d[pos:pos + 16] = bytes(16)
        with self.assertRaises(SystemExit) as e: ss.snowdrop_zlib(bytes(d), 'test/damaged.sdfdata')
        self.assertIn('Snowdrop zlib record', str(e.exception.code))

    def test_a_stream_with_a_wrong_head_is_skipped(self):
        # a zlib stream whose decoded head is not a cache record is not a shader file, and neither is an empty slice
        self.assertEqual(ss.snowdrop_zlib(zlib.compress(b'not a shader cache record' + noise(0x100, 3)), 'test/other.sdfdata'), [])
        self.assertEqual(ss.snowdrop_zlib(b'', 'test/empty.sdfdata'), [])


if __name__ == '__main__':
    unittest.main()
