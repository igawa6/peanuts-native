#!/usr/bin/env python3
"""peanuts/scripts/nup-extract.py: a dump folder packed into a .nup by dm/tools/nup_pack.py (a second, clean-room writer) comes back file for file in the
layout scripts/pack_dump.py reads (base/, updates/<version>/, dlc/<title ID>/<version>/); --exefs-only leaves the RomFS out; --heads keeps the code modules and the NRO files whole and every other RomFS file as a file of its real size with its first
32 bytes (what the survey reads of a game whose dump does not fit on the disk); a folder with files is refused"""
import contextlib, importlib.util, io, subprocess, sys, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'peanuts' / 'scripts' / 'nup-extract.py'
PACK = REPO / 'dm' / 'tools' / 'nup_pack.py'

spec = importlib.util.spec_from_file_location('nup_extract', SCRIPT)
ne = importlib.util.module_from_spec(spec); spec.loader.exec_module(ne)

TITLE, DLC = '0100000000AB0000', '0100000000AB1001'
FILES = {   # a dump in the flattened layout: two versions and one add-on
    'base/exefs/main': b'NSO0 base main',
    'base/exefs/main.npdm': b'META base',
    'base/romfs/data/a.bin': b'a' * 5000,
    'base/romfs/data/sub/b.txt': b'hello',
    'base/romfs/.nro/Game.nro': b'NRO0' * 50,   # a module the game loads, in a folder that is itself named .nro
    'updates/65536/exefs/main': b'NSO0 update main',
    'updates/65536/exefs/main.npdm': b'META update',
    'updates/65536/romfs/data/a.bin': b'b' * 5000,
    f'dlc/{DLC}/0/romfs/extra.bin': b'dlc',
}


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


class Extract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        t = Path(cls.tmp.name)
        dump = t / 'dump'
        for rel, data in FILES.items():
            (dump / rel).parent.mkdir(parents=True, exist_ok=True); (dump / rel).write_bytes(data)
        cls.nup = t / f'Test [{TITLE}][v65536].nup'
        r = subprocess.run([sys.executable, str(PACK), str(dump), str(cls.nup), '--raw'], capture_output=True, text=True)
        if r.returncode: raise unittest.SkipTest(f'nup_pack.py could not write the fixture: {(r.stderr or r.stdout).strip()[-300:]}')

    @classmethod
    def tearDownClass(cls): cls.tmp.cleanup()

    def run_main(self, *args):
        with contextlib.redirect_stdout(io.StringIO()): ne.main([str(a) for a in args])

    def test_round_trip(self):
        out = Path(self.tmp.name) / 'out-all'
        self.run_main(self.nup, out)
        self.assertEqual(tree(out), FILES)
        self.assertFalse(out.with_name(out.name + '.tmp').exists())

    def test_exefs_only(self):
        out = Path(self.tmp.name) / 'out-exefs'
        self.run_main(self.nup, out, '--exefs-only')
        self.assertEqual(tree(out), {k: v for k, v in FILES.items() if '/exefs/' in k})

    def test_heads(self):
        out = Path(self.tmp.name) / 'out-heads'
        self.run_main(self.nup, out, '--heads')
        got = tree(out)
        self.assertEqual(sorted(got), sorted(FILES))   # every file is there
        for rel, data in FILES.items():
            if '/exefs/' in rel or rel.endswith('.nro'): self.assertEqual(got[rel], data, rel)   # whole
            else: self.assertEqual(got[rel], data[:32] + bytes(max(0, len(data) - 32)), rel)   # its size, its first 32 bytes, zeros after them
        self.assertEqual(got['base/romfs/data/sub/b.txt'], b'hello')   # a file shorter than 32 bytes is whole
        with self.assertRaises(SystemExit): self.run_main(self.nup, Path(self.tmp.name) / 'out-both', '--heads', '--exefs-only')

    def test_refuses_a_folder_with_files(self):
        out = Path(self.tmp.name) / 'out-busy'
        out.mkdir(); (out / 'keep.txt').write_bytes(b'mine')
        with self.assertRaises(SystemExit) as e: self.run_main(self.nup, out)
        self.assertIn('[RED]', str(e.exception))
        self.assertEqual(tree(out), {'keep.txt': b'mine'})


if __name__ == '__main__':
    unittest.main()
