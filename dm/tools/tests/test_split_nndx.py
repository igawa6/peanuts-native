"""Level-5's single-stage shader objects. Synthetic code and control data only."""
import struct
import unittest

from dm.tools.tests.test_split_gzip import fz, ss


def member(stage, ctl, code):
    header = bytearray(0x80)
    struct.pack_into('<8sII', header, 0, b'NNDXDHSC', 1, stage)
    struct.pack_into('<4I', header, 0x48, len(header), len(code), len(header) + len(code), len(ctl))
    return header + code + ctl


class NndxShader(unittest.TestCase):
    def test_stages_from_header_not_filename(self):
        for tag, stage, (ctl, code) in ((1, 'vs', fz.VS), (2, 'ps', fz.PS), (3, 'gs', fz.stage(0x1061)), (6, 'cs', (fz.VS[0], ss.CS_MAGIC + fz.VS[1][4:]))):
            with self.subTest(stage=stage):
                found = []
                ss.scan_blob(member(tag, ctl, code), '/unknown', found)
                self.assertEqual(found, [('/unknown#NNDX', stage, ctl, code)])

    def test_rejects_invalid_ranges_and_stage_disagreement(self):
        for offset, val in ((8, 2), (12, 2), (12, 7), (0x48, 16), (0x4c, 0), (0x50, 0x80), (0x54, 0xffffffff)):
            with self.subTest(offset=offset, val=val):
                data = member(1, *fz.VS)
                struct.pack_into('<I', data, offset, val)
                with self.assertRaisesRegex(SystemExit, 'broken'):
                    ss.scan_blob(data, '/broken', [])
