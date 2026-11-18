# PEANUTS: new file (code mods: dm/tools/pack_android.py's --modules/--mod-exefs/--mod-patches, DESIGN-MODS.md)
"""scripts/pack_android.py's mod-aware helpers (merge_mod_modules, apply_mod_patches): the pure module-list/source-map merge, and the exefs_patches
layer wired to a real (synthetic) NSO the same way test_patch_ips.py does. Does not exercise the CLI end to end (that needs a full dump/profile,
exercised on a real device per the mods build report) - this is the two helper functions pack_android.py's main() calls."""
import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import unittest

import lz4.block

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'scripts'))
sys.path.insert(0, str(REPO / 'dm/tools'))
import pack_android as pa   # noqa: E402
import ips, nso   # noqa: E402  # PEANUTS(upstream-candidate): patch_ips is exercised through the packer.

TEXT, RO, DATA = bytes(range(256)) * 20, b'read-only ' * 300, b'\x01\x02' * 0x900


def page(x): return (x + 0xfff) & ~0xfff


def make_nso(segs=(TEXT, RO, DATA), compress=(1, 0, 1), bss=0x5000, build_id=bytes(range(0x20))):
    """Same layout as dm/tools/tests/test_nso.py's make_nso() (and test_patch_ips.py's own copy)."""
    hdr, body, mo = bytearray(0x100), b'', 0
    hdr[:4] = b'NSO0'
    hdr[0x40:0x60] = build_id
    for i, s in enumerate(segs):
        stored = lz4.block.compress(s, store_size=False) if compress[i] else s
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, 0x100 + len(body), mo, len(s))
        struct.pack_into('<I', hdr, 0x60 + 4 * i, len(stored))
        hdr[0xa0 + 0x20 * i:0xc0 + 0x20 * i] = hashlib.sha256(s).digest()
        body += stored
        mo = page(mo + len(s))
    struct.pack_into('<I', hdr, 0xc, 0x38 | sum(c << i for i, c in enumerate(compress)))
    struct.pack_into('<I', hdr, 0x3c, bss)
    return bytes(hdr) + body


BUILD_ID = bytes.fromhex('0123456789abcdef' + '00' * 24)
BUILD_ID16 = '0123456789abcdef'


class MergeModModules(unittest.TestCase):
    def setUp(self):
        self.mods = ['main', 'subsdk0', 'sdk']
        self.srcs = {m: Path('/dump/exefs') / m for m in self.mods}

    def test_nothing_given_is_a_no_op(self):
        mods, srcs = pa.merge_mod_modules(self.mods, self.srcs, None, {})
        self.assertEqual(mods, self.mods)
        self.assertEqual(srcs, self.srcs)
        self.assertIsNot(mods, self.mods)   # never mutates the caller's list/dict

    def test_mod_exefs_replaces_a_known_module_without_changing_the_list(self):
        mods, srcs = pa.merge_mod_modules(self.mods, self.srcs, None, {'main': '/mods/Lumi/exefs/main'})
        self.assertEqual(mods, self.mods)
        self.assertEqual(srcs['main'], Path('/mods/Lumi/exefs/main'))
        self.assertEqual(srcs['subsdk0'], self.srcs['subsdk0'])   # untouched

    def test_modules_override_with_an_added_name_and_its_source(self):
        mods, srcs = pa.merge_mod_modules(self.mods, self.srcs, ['main', 'subsdk0', 'sdk', 'subsdk9'], {'subsdk9': '/mods/Lumi/exefs/subsdk9'})
        self.assertEqual(mods, ['main', 'subsdk0', 'sdk', 'subsdk9'])   # the override's own order is kept
        self.assertEqual(srcs['subsdk9'], Path('/mods/Lumi/exefs/subsdk9'))

    def test_mod_exefs_alone_appends_a_name_not_in_the_override(self):
        # a caller that forgets --modules for an added module still gets it in the list (mod_exefs is the source of truth for "does this module exist")
        mods, srcs = pa.merge_mod_modules(self.mods, self.srcs, None, {'subsdk9': '/mods/Lumi/exefs/subsdk9'})
        self.assertEqual(mods, ['main', 'subsdk0', 'subsdk9', 'sdk'])   # where the console loads it (dm/tools/xmod.py insert)


class ApplyModPatches(unittest.TestCase):
    def test_no_matching_patch_returns_the_source_unchanged(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / 'main'
            src.write_bytes(make_nso(build_id=BUILD_ID))
            empty = d / 'empty'; empty.mkdir()
            out = d / 'out'; out.mkdir()
            got = pa.apply_mod_patches('main', src, [empty], out)
            self.assertEqual(got, src)   # unchanged: no .mod-src/ written

    def test_a_matching_patch_is_applied_and_written_under_mod_src(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / 'main'
            src.write_bytes(make_nso(build_id=BUILD_ID))
            patches = d / 'LumiPatches'; patches.mkdir()
            (patches / (BUILD_ID16 + '.ips')).write_bytes(ips.build([(0x100, b'\x7f')]))
            out = d / 'out'; out.mkdir()
            got = pa.apply_mod_patches('main', src, [patches], out)
            self.assertEqual(got, out / '.mod-src' / 'main' / 'main')
            self.assertTrue(got.is_file())
            m = nso.read(got)
            self.assertEqual(m.image[0], 0x7f)
            self.assertEqual(m.build_id, BUILD_ID)

    def test_malformed_patch_raises(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src = d / 'main'
            src.write_bytes(make_nso(build_id=BUILD_ID))
            patches = d / 'patches'; patches.mkdir()
            (patches / (BUILD_ID16 + '.ips')).write_bytes(b'not an ips file at all')
            out = d / 'out'; out.mkdir()
            with self.assertRaises(pa.Error):
                pa.apply_mod_patches('main', src, [patches], out)


class ModMemoryRows(unittest.TestCase):
    """Which titles' builds with mods get a profile.bin of their own for mod_memory.tsv's numbers alone (a mod that adds no module)"""
    def test_a_title_with_rows_and_one_without(self):
        rows = [c for line in (REPO / 'dm/peanuts/profile/mod_memory.tsv').read_text(encoding='utf-8').splitlines() if len(c := line.split('#', 1)[0].split()) == 3]
        self.assertTrue(rows, 'mod_memory.tsv has no rows')
        self.assertTrue(pa.mod_memory_rows(rows[0][0]))
        self.assertTrue(pa.mod_memory_rows(rows[0][0].lower()))
        self.assertFalse(pa.mod_memory_rows('0100FFFFFFFFF000'))


if __name__ == '__main__':
    unittest.main()
