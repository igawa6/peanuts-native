"""shader_split DM_SPLIT_TARGETED=1 (the phone's split, pack_android.py's): when main's build is one of the profile's, only the RomFS files its shader_map.tsv names
(a member's source reduced to its RomFS file) are scanned, and the result is kept only when its tables are the profile's byte for byte; otherwise every file is scanned,
with a line saying why. Either way the outputs are the full split's. A file that holds only shaders found earlier but pairs them anew makes the targeted pairs differ,
so that dump takes the full scan; with DM_SPLIT_RESUME a stopped fall back resumes as the full scan. No game files needed: a synthetic RomFS and profile."""
import contextlib
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
_had = os.environ.get('DM_GAME'); os.environ['DM_GAME'] = '01007BB017812000'   # shader_split loads a profile at import; other tests expect no default game
import shader_split as ss   # its magics and skey; the splits below run it as a program, on this test's own profile
if _had is None: del os.environ['DM_GAME']
else: os.environ['DM_GAME'] = _had

GAME = '0100F00DF00D0000'   # a profile only this test has (DM_GAMES_DIR)
BUILD = bytes(range(0x10, 0x20))
TABLES = ('shader_map.tsv', 'shader_facts.tsv', 'shader_pairs.tsv', 'pso_pairs.tsv')


def sarc(members):   # a SARC of (name, bytes) members (dm/tools/fuzz/fuzz_shader_split.py's)
    names = b''.join(n + b'\0' + bytes(-(len(n) + 1) % 4) for n, _ in members)
    sfat = b'SFAT' + struct.pack('<HHI', 0xc, len(members), 0x65)
    data, at, offs = b'', 0, []
    for n, m in members:
        offs.append(at); data += m + bytes(-len(m) % 0x100); at = len(data)
    noff = 0
    for (n, m), o in zip(members, offs):
        sfat += struct.pack('<IIII', 0, 0x01000000 | noff // 4, o, o + len(m)); noff += len(n) + 1 + (-(len(n) + 1) % 4)
    head = 0x14 + len(sfat) + 8 + len(names); doff = (head + 0xff) & ~0xff
    d = b'SARC' + struct.pack('<HHII', 0x14, 0xfeff, doff + len(data), doff) + bytes(4) + sfat + b'SFNT' + struct.pack('<HH', 8, 0) + names
    return d + bytes(doff - len(d)) + data


def stage(sph_type, n):
    """(control section, code area) of one stage as dm/tools/fuzz/fuzz_shader_split.py's stage makes it, with n in an instruction word so each n is another shader"""
    code = bytearray(ss.GFX_MAGIC + bytes(0x2c)); code += struct.pack('<I', sph_type) + bytes(0x4c); code += bytes.fromhex('0f00000000000000') * 7 + struct.pack('<Q', n)
    ctl = bytearray(0x880); ctl[:4] = ss.CTL_MAGIC; struct.pack_into('<I', ctl, 0x20, 0x880); struct.pack_into('<I', ctl, 0x704, len(code))
    return bytes(ctl), bytes(code)


def vs(n): return stage(0x0461, n)
def ps(n): return stage(0x1462, n)


def bnsh(v, p):
    """A BNSH of one variation: vertex v, pixel p (dm/tools/fuzz/fuzz_shader_split.py's layout)"""
    d = bytearray(0x300); d[:8] = b'BNSH\0\0\0\0'; d[0x60:0x64] = b'grsc'
    struct.pack_into('<IQ', d, 0x7c, 1, 0x100); struct.pack_into('<Q', d, 0x110, 0x140)
    for si, desc, (ctl, code) in ((0, 0x200, v), (4, 0x240, p)):
        struct.pack_into('<Q', d, 0x148 + 8 * si, desc)
        at = len(d); d += ctl + code
        struct.pack_into('<QQII', d, desc + 8, at, at + len(ctl), len(code), len(ctl))
    struct.pack_into('<I', d, 0x1c, len(d))
    return bytes(d)


class Targeted(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp()); self.addCleanup(lambda: shutil.rmtree(self.tmp))
        self.rom = self.tmp / 'romfs'
        self.write('Shader/a.bnsh', bnsh(vs(1), ps(1)))
        self.write('Shader/pack.sarc', sarc([(b'b.bnsh', bnsh(vs(2), ps(2)))]))   # a member: its source is /Shader/pack.sarc/b.bnsh#...
        for i in range(20): self.write(f'Model/m{i:02}.bin', b'no shaders here %d' % i)
        self.write('Zdup/dup.bnsh', bnsh(vs(1), ps(1)))   # only copies of shaders found earlier, paired the same way: named by no map row
        self.main = self.tmp / 'main'; self.main.write_bytes(b'NSO0' + bytes(0x3c) + BUILD + bytes(0x100 - 0x50))
        self.prof = self.tmp / 'profiles' / GAME; self.prof.mkdir(parents=True)
        self.game_tsv(BUILD.hex())

    def write(self, rel, data):
        p = self.rom / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)

    def game_tsv(self, build):
        (self.prof / 'game.tsv').write_text(f'title_id\t{GAME}\nbuild\tv0\t1.0.0\tmain={build}\nshader_groups\t/Shader/=shader\nshader_note\tTest shaders\n')

    def split(self, name, targeted, resume=None, files=None):
        out = self.tmp / name
        env = dict(os.environ, DM_GAME=GAME, DM_GAMES_DIR=str(self.tmp / 'profiles'), J='1', DM_SPLIT_SELECTED='0')
        for k in ('DM_SPLIT_RESUME', 'DM_SPLIT_TARGETED', 'DM_SPLIT_FILES'): env.pop(k, None)
        if files: env['DM_SPLIT_FILES'] = str(files)
        if targeted: env['DM_SPLIT_TARGETED'] = '1'
        if resume: env['DM_SPLIT_RESUME'] = str(resume)
        r = subprocess.run([sys.executable, REPO / 'dm/gfx/tools/shader_split.py', self.rom, self.main, out / 'pkg', *[out / t for t in TABLES]],
                           capture_output=True, text=True, env=env)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return out, r.stdout

    def profile_from(self, out):   # the profile's tables: a full split's
        for t in TABLES: shutil.copyfile(out / t, self.prof / t)

    def same(self, a, b):
        self.assertEqual(sorted(os.listdir(a / 'pkg')), sorted(os.listdir(b / 'pkg')))
        for f in os.listdir(a / 'pkg'): self.assertEqual((a / 'pkg' / f).read_bytes(), (b / 'pkg' / f).read_bytes(), f)
        for t in TABLES: self.assertEqual((a / t).read_bytes(), (b / t).read_bytes(), t)

    def test_targeted_equals_full(self):
        full, said = self.split('full', False)
        self.assertNotIn('targeted split', said)
        self.assertIn('/Shader/pack.sarc/b.bnsh#BNSH', (full / 'shader_map.tsv').read_text())
        self.profile_from(full)
        t, said = self.split('targeted', True)
        self.assertIn('targeted split: 2 of 23 RomFS files hold the shaders', said)
        self.assertIn('progress: 2/2', said)   # the count is of the files it reads
        self.assertIn("targeted split: its tables are the profile's, byte for byte", said)
        self.same(full, t)

    def test_older_comment_lines_still_take_the_targeted_scan(self):   # Orbitals Deluxe Edition: its map's first line named the title ID; the split now writes the game's name
        full, _ = self.split('full', False); self.profile_from(full)
        m = self.prof / 'shader_map.tsv'
        m.write_text(''.join('# an older split wrote this line\n' if ln.startswith('#') else ln for ln in m.read_text().splitlines(keepends=True)))
        t, said = self.split('targeted', True)
        self.assertIn("targeted split: its tables are the profile's, byte for byte", said)
        self.same(full, t)
        m.write_text(m.read_text().replace('\t0\t', '\t9\t', 1))   # a data line that differs still sends the dump to the full scan
        t, said = self.split('targeted2', True)
        self.assertIn("made a shader_map.tsv that differs from the profile's", said)

    def test_a_copy_that_pairs_anew_takes_the_full_scan(self):
        self.write('Zdup/cross.bnsh', bnsh(vs(1), ps(2)))   # no shader of its own, but a pair no other file has
        full, _ = self.split('full', False); self.profile_from(full)
        self.assertIn(f'{self.key(vs(1))}\t{self.key(ps(2))}\n', (full / 'shader_pairs.tsv').read_text())
        scan = self.tmp / 'scan'
        t, said = self.split('targeted', True, scan)
        self.assertIn("not taken, the targeted scan made a shader_pairs.tsv that differs from the profile's: every file is scanned", said)
        self.assertIn('progress: 24/24', said)
        self.same(full, t)
        self.assertEqual(sorted(os.listdir(scan)), ['full', 'targeted', 'targeted.failed'])
        t2, said = self.split('again', True, scan)   # a rerun (the phone's after a stop) goes straight to the full scan, from its kept results
        self.assertIn("targeted split: not taken, the targeted scan's tables were found to differ from the profile's by an earlier run on these inputs: every file is scanned", said)   # PEANUTS: the wording since the direct mode (DM_SPLIT_DIRECT: each mode names itself)
        self.assertIn('resuming the split: 24 file(s) already scanned', said)
        self.assertNotIn('only these are scanned', said)
        self.same(full, t2)

    def test_the_profiles_file_list_takes_such_copies(self):   # shader_files.tsv (DM_SPLIT_FILES of a full split): every file a shader was found in, so the targeted scan reads them
        self.write('Zdup/cross.bnsh', bnsh(vs(1), ps(2)))
        full, _ = self.split('full', False, files=self.prof / 'shader_files.tsv'); self.profile_from(full)
        listed = [ln for ln in (self.prof / 'shader_files.tsv').read_text().splitlines() if not ln.startswith('#')]
        self.assertEqual(listed, ['Shader/a.bnsh', 'Shader/pack.sarc', 'Zdup/cross.bnsh', 'Zdup/dup.bnsh'])
        t, said = self.split('targeted', True)
        self.assertIn('targeted split: 4 of 24 RomFS files hold the shaders', said)
        self.assertIn("its tables are the profile's, byte for byte", said)
        self.same(full, t)

    def test_another_build_takes_the_full_scan(self):
        full, _ = self.split('full', False); self.profile_from(full)
        self.game_tsv('00' * 16)
        t, said = self.split('targeted', True)
        self.assertIn(f"targeted split: not taken, main's build {BUILD.hex()} is not one the profile was made from", said)
        self.same(full, t)

    def test_registered_build_outside_shader_versions_scans_new_files(self):   # PEANUTS: a supported executable can have unprofiled RomFS shaders
        known, _ = self.split('known', False)
        self.profile_from(known)
        self.game_tsv('12' * 16)
        with (self.prof / 'game.tsv').open('a') as f:
            f.write(f'build\tv65536\t1.0.1\tmain={BUILD.hex()}\nshader_versions\tv0\n')
        self.write('Update/new.bnsh', bnsh(vs(7), ps(8)))
        full, _ = self.split('full', False)
        actual, log = self.split('targeted', True)
        self.same(full, actual)
        self.assertIn('every file is scanned', log)

    def test_single_update_matches_version_tagged_profile(self):   # PEANUTS: the union profile has v327680 paths, the Android update-only split does not
        present, _ = self.split('present', False)
        self.profile_from(present)
        mapping = self.prof / 'shader_map.tsv'
        mapping.write_text(mapping.read_text().replace('\t/Shader/', '\t/v327680/Shader/').replace('#BNSH@0x0', '#BNSH@0x100'))
        self.write('Old/old.bnsh', bnsh(vs(19), ps(19)))
        full_union, _ = self.split('union', False)
        old_rows = [ln for ln in (full_union / 'shader_map.tsv').read_text().splitlines() if '\t/Old/' in ln]
        self.assertEqual(len(old_rows), 2)
        with mapping.open('a') as f:
            f.write(old_rows[0].replace('\t/Old/', '\t/v65536/Old/') + '\n')
            f.write(old_rows[1].rsplit('\t', 1)[0] + '\tmain#BNSH@0x0#var0@v65536\n')   # PEANUTS: a version tag at the end of an executable source
        for table in TABLES[1:]:
            shutil.copyfile(full_union / table, self.prof / table)
        (self.rom / 'Old/old.bnsh').unlink()
        (self.prof / 'game.tsv').write_text((self.prof / 'game.tsv').read_text().replace('build\tv0', 'build\tv327680')
            + 'build\tv65536\t1.0.0\tmain=' + '12' * 16 + '\nshader_versions\tv65536 v327680\n')
        actual, log = self.split('update-targeted', True)
        self.assertIn("2 shader(s) of the other listed builds", log)
        self.assertIn('targeted split: its tables match the profile for v327680', log)
        self.same(present, actual)

        pso = self.prof / 'pso_pairs.tsv'   # PEANUTS: supplemental pairs can be optional for a particular build
        pair = f'{self.key(vs(1))}\t{self.key(ps(2))}\n'
        with pso.open('a') as f:
            f.write(pair)
        optional_pso = self.prof / 'pso_pairs_optional.tsv'
        optional_pso.write_text('v65536\t' + pair)
        actual, log = self.split('update-with-required-pair', True)
        self.assertIn("made a pso_pairs.tsv that differs from the profile's", log)
        self.same(present, actual)
        optional_pso.write_text('v327680\t' + pair)
        actual, log = self.split('update-with-optional-pair', True)
        self.assertIn('targeted split: its tables match the profile for v327680', log)
        self.same(present, actual)

        # PEANUTS: regular variation pairs between shared shader keys can differ too.
        with (self.prof / 'shader_pairs.tsv').open('a') as f:
            f.write(pair)
        actual, log = self.split('update-with-required-variation', True)
        self.assertIn("made a shader_pairs.tsv that differs from the profile's", log)
        self.same(present, actual)
        (self.prof / 'shader_pairs_optional.tsv').write_text('v327680\t' + pair)
        actual, log = self.split('update-with-optional-variation', True)
        self.assertIn('targeted split: its tables match the profile for v327680', log)
        self.same(present, actual)

        mapping.write_text(mapping.read_text().replace('/v65536/Old/', '/Old/'))
        optional = self.prof / 'shader_optional.tsv'
        optional.write_text(''.join(row.split('\t')[3] + '\t1.0.0 (v65536) only\n' for row in old_rows))
        actual, log = self.split('update-with-optional-base', True)
        self.assertIn('targeted split: its tables match the profile for v327680', log)
        self.same(present, actual)

        optional.write_text(optional.read_text().replace('(v65536)', '(v327680)', 1))
        actual, log = self.split('update-with-missing-source', True)
        self.assertIn('and the dump has no such file: every file is scanned', log)
        self.same(present, actual)

    def test_a_named_file_missing_takes_the_full_scan(self):
        full, _ = self.split('full', False); self.profile_from(full)
        (self.rom / 'Shader/a.bnsh').unlink()
        full2, _ = self.split('full2', False)
        t, said = self.split('targeted', True)
        self.assertIn("names /Shader/a.bnsh#BNSH", said); self.assertIn('and the dump has no such file: every file is scanned', said)
        self.same(full2, t)

    def test_a_build_without_optional_keys_takes_the_targeted_scan(self):   # shader_optional.tsv: Mario Kart World 1.8.0 as update 917504 lacks one shader of the map
        self.write('Shader/pack.sarc', sarc([(b'a0.bnsh', bnsh(vs(3), ps(3))), (b'b.bnsh', bnsh(vs(2), ps(2)))]))   # the extra shaders come first: later rows renumber
        full, _ = self.split('full', False); self.profile_from(full)
        gone = (self.key(vs(3)), self.key(ps(3)))
        self.assertIn(f'{gone[0]}\t{gone[1]}\n', (full / 'shader_pairs.tsv').read_text())
        self.write('Shader/pack.sarc', sarc([(b'b.bnsh', bnsh(vs(2), ps(2)))]))   # the other build's RomFS
        full2, _ = self.split('full2', False)
        t, said = self.split('without', True)
        self.assertIn('not taken, the targeted scan made a shader_map.tsv that differs', said)   # not yet optional: the full scan
        self.same(full2, t)
        (self.prof / 'shader_optional.tsv').write_text('# key <TAB> which dumps have it\n' + ''.join(f'{k}\tthe first build only\n' for k in gone))
        t, said = self.split('optional', True)
        self.assertIn("compared with the profile's tables less the 2 shader(s) of shader_optional.tsv this dump does not have", said)
        self.assertIn("its tables are the profile's, byte for byte", said)
        self.assertIn('progress: 2/2', said)
        self.same(full2, t)
        (self.prof / 'shader_optional.tsv').write_text(f'{gone[0]}\tthe first build only\n')   # one of the two missing keys is not optional: the full scan
        t, said = self.split('half', True)
        self.assertIn('not taken', said)
        self.same(full2, t)

    def test_the_zip_probe_opens_only_wanted_files(self):   # a large RomFS (Mario Kart World: 206,326 files) is not opened file by file before the targeted scan
        files = [(str(self.tmp / 'missing' / f'f{i}'), f'f{i}') for i in range(3)] + [(str(self.rom / 'Shader/a.bnsh'), 'Shader/a.bnsh')]
        big, todo = ss.scan_jobs(files, {'Shader/a.bnsh'})   # the missing files would raise if they were opened
        self.assertEqual((big, [f[1] for f in todo]), ([], ['Shader/a.bnsh']))
        with self.assertRaises(FileNotFoundError): ss.scan_jobs(files)

    def test_a_cpk_source_can_end_with_an_entry_offset(self):   # PEANUTS: Diablo III names a CPK offset before the shader record
        m = self.prof / 'shader_map.tsv'
        m.write_text('ps_main.bin\t0\tps\t0000000000001234\t/CPKs/ClientCommon.cpk@0x1478f23#D3@0x4f54\n')
        with mock.patch.object(ss, 'GAME', ss._game.Game(self.prof)):
            self.assertEqual(ss.profile_files([('archive', 'CPKs/ClientCommon.cpk')], [str(self.main)]), ({'CPKs/ClientCommon.cpk'}, None))

    def test_the_full_zip_probe_prints_progress(self):   # the full scan's probe of every file counts them for the phone's card ('progress: <done>/<total>')
        files = [(str(self.rom / 'Shader/a.bnsh'), f'f{i}') for i in range(1000)]
        clock = iter(range(10 ** 6))   # PEANUTS(upstream-candidate): range already starts at zero.
        out = io.StringIO()
        with mock.patch.object(ss.time, 'monotonic', lambda: next(clock)), contextlib.redirect_stdout(out): ss.zip_jobs(files)
        lines = out.getvalue().splitlines()
        self.assertTrue(lines and all(ln.startswith('progress: ') and ln.endswith('/1000') for ln in lines), lines[:3])
        out = io.StringIO()
        with contextlib.redirect_stdout(out): ss.zip_jobs(files[:999])   # a small RomFS: no lines
        self.assertEqual(out.getvalue(), '')

    def test_no_map_takes_the_full_scan(self):
        full, _ = self.split('full', False)
        t, said = self.split('targeted', True)
        self.assertIn('not taken, the profile has no shader_map.tsv', said)
        self.same(full, t)

    @staticmethod
    def key(s): return ss.skey(*s)


if __name__ == '__main__': unittest.main()
