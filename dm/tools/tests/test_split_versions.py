"""PEANUTS: shader_split DM_SPLIT_TARGETED=1 with a profile whose tables are the union of two versions (game.tsv shader_versions, Mario Kart 8 Deluxe's 4.0.0 and
4.0.1) and a dump of one of them (single_shader_version). The union tags a later version's first appearances (/v65536/...), which a dump of that version alone
names untagged; the shaders only the other version has are absent, and the map's rows may sit at another place (version_map_rows). The outputs are the full
scan's of that dump, byte for byte, either way; anything else still takes the full scan. No game files needed: a synthetic two-version RomFS and profile."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_split_targeted as T   # its synthetic BNSH and SARC writers, and shader_split (as T.ss)

ss = T.ss
REPO = T.REPO
GAME = '0100F00DF00E0000'   # a profile only this test has (DM_GAMES_DIR)
OLD, NEW = 'v0', 'v65536'
BUILDS = {OLD: bytes(range(0x10, 0x20)), NEW: bytes(range(0x20, 0x30))}
TABLES = T.TABLES


def at(off, *blobs):   # a file whose BNSH containers start at off, one after the other (aligned as the game's .ptcl holds them)
    d = bytearray(off)
    for b in blobs: d += b + bytes(-len(b) % 0x100)
    return bytes(d)


class Versions(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp()); self.addCleanup(lambda: shutil.rmtree(self.tmp))
        self.prof = self.tmp / 'profiles' / GAME; self.prof.mkdir(parents=True)
        (self.prof / 'game.tsv').write_text(f'title_id\t{GAME}\n' + ''.join(f'build\t{v}\t1.{i}.0\tmain={b.hex()}\n' for i, (v, b) in enumerate(BUILDS.items()))
                                            + f'shader_versions\t{OLD} {NEW}\nshader_groups\t/Shader/=shader /Effect/=effect\nshader_note\tTest shaders\n')
        for v, b in BUILDS.items():
            m = self.tmp / v / 'exefs' / 'main'; m.parent.mkdir(parents=True); m.write_bytes(b'NSO0' + bytes(0x3c) + b + bytes(0x100 - 0x50))
            for i in range(5): self.write(v, f'Model/m{i}.bin', b'no shaders here %d' % i)
        self.write(OLD, 'Shader/a.bnsh', T.bnsh(T.vs(1), T.ps(1))); self.write(NEW, 'Shader/a.bnsh', T.bnsh(T.vs(1), T.ps(1)))
        # the effects file, rebuilt by the update: its shared container moved (0x1000 -> 0x2000), one container only the old version has, one only the new one has
        self.write(OLD, 'Effect/common.ptcl', at(0x1000, T.bnsh(T.vs(2), T.ps(2)), T.bnsh(T.vs(5), T.ps(5))))
        self.write(NEW, 'Effect/common.ptcl', at(0x2000, T.bnsh(T.vs(2), T.ps(2)), T.bnsh(T.vs(6), T.ps(6))))

    def write(self, v, rel, data):
        p = self.tmp / v / 'romfs' / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)

    def split(self, name, versions, targeted, direct=False, files=None):
        out = self.tmp / 'out' / name
        env = dict(os.environ, DM_GAME=GAME, DM_GAMES_DIR=str(self.tmp / 'profiles'), J='1', DM_SPLIT_SELECTED='0')
        for k in ('DM_SPLIT_RESUME', 'DM_SPLIT_TARGETED', 'DM_SPLIT_FILES', 'DM_SPLIT_DIRECT', 'DM_SPLIT_DIFF_DIR'): env.pop(k, None)
        if targeted: env['DM_SPLIT_TARGETED'] = '1'
        if direct: env['DM_SPLIT_DIRECT'] = '1'
        if files: env['DM_SPLIT_FILES'] = str(files)
        r = subprocess.run([sys.executable, REPO / 'dm/gfx/tools/shader_split.py', ','.join(str(self.tmp / v / 'romfs') for v in versions),
                            ','.join(str(self.tmp / v / 'exefs' / 'main') for v in versions), out / 'pkg', *[out / t for t in TABLES]],
                           capture_output=True, text=True, env=env)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return out, r.stdout

    def union(self, optional=True, files=False):   # the profile: the union split of both versions, and shader_optional.tsv naming the old version's own shaders
        full, _ = self.split('union', [OLD, NEW], False, files=self.prof / 'shader_files.tsv' if files else None)
        for t in TABLES: shutil.copyfile(full / t, self.prof / t)
        m = (self.prof / 'shader_map.tsv').read_text()
        self.assertIn(f'/{NEW}/Effect/common.ptcl#BNSH@0x', m)   # the new version's own container, tagged
        self.assertIn('/Effect/common.ptcl#BNSH@0x1000#var0', m)
        if optional: (self.prof / 'shader_optional.tsv').write_text('# key <TAB> which dumps have it\n' + ''.join(f'{self.key(s)}\t{OLD} only\n' for s in (T.vs(5), T.ps(5))))

    def same(self, a, b):
        self.assertEqual(sorted(os.listdir(a / 'pkg')), sorted(os.listdir(b / 'pkg')))
        for f in os.listdir(a / 'pkg'): self.assertEqual((a / 'pkg' / f).read_bytes(), (b / 'pkg' / f).read_bytes(), f)
        for t in TABLES: self.assertEqual((a / t).read_bytes(), (b / t).read_bytes(), t)

    def test_a_dump_of_the_later_version_takes_the_targeted_scan(self):   # Mario Kart 8 Deluxe 4.0.1: every file was scanned
        self.union()
        full, _ = self.split('full', [NEW], False)
        self.assertNotIn('/Effect/common.ptcl#BNSH@0x1000', (full / 'shader_map.tsv').read_text())   # the shared shaders are at the new place
        for direct in (False, True):   # the direct scan reads the recorded containers: the moved one is not where the map says, so that file is read whole
            t, said = self.split(f'targeted{direct}', [NEW], True, direct)
            self.assertIn('targeted split: 2 of 7 RomFS files hold the shaders', said)
            self.assertIn("compared with the profile's tables less the 2 shader(s) of the other listed builds this dump does not have", said)
            self.assertIn(f'its tables match the profile for {NEW}', said)
            self.assertNotIn('not taken', said)
            if direct: self.assertIn('direct: /Effect/common.ptcl: a recorded member or container is not where the map says; the file is scanned whole', said)
            self.same(full, t)

    def test_a_dump_of_the_first_version_takes_the_targeted_scan(self):   # 4.0.0: the new version's tagged rows are absent
        self.union()
        full, _ = self.split('full', [OLD], False)
        t, said = self.split('targeted', [OLD], True)
        self.assertIn("compared with the profile's tables less the 2 shader(s) of the other listed builds this dump does not have", said)
        self.assertIn(f'its tables match the profile for {OLD}', said)
        self.same(full, t)

    def test_without_shader_optional_the_later_version_takes_the_full_scan(self):   # the old version's own shaders are not known to be absent
        self.union(optional=False)
        full, _ = self.split('full', [NEW], False)
        t, said = self.split('targeted', [NEW], True)
        self.assertIn("not taken, the targeted scan made a shader_map.tsv that differs from the profile's: every file is scanned", said)
        self.same(full, t)

    def test_a_shader_moved_to_a_file_the_map_does_not_name_takes_the_full_scan(self):
        self.union()
        (self.tmp / NEW / 'romfs/Shader/a.bnsh').unlink(); self.write(NEW, 'Shader/a.bnsh', b'no shaders any more')
        self.write(NEW, 'Shader/z.bnsh', T.bnsh(T.vs(1), T.ps(1)))   # the update keeps them in another file
        full, _ = self.split('full', [NEW], False)
        t, said = self.split('targeted', [NEW], True)
        self.assertIn('not taken, the targeted scan made a shader_map.tsv that differs', said)
        self.same(full, t)

    def test_a_shader_first_found_in_another_named_file_takes_the_full_scan(self):   # the same keys, but a source in another file: not only another place
        self.union()
        self.write(NEW, 'Effect/common.ptcl', at(0x2000, T.bnsh(T.vs(1), T.ps(1)), T.bnsh(T.vs(2), T.ps(2)), T.bnsh(T.vs(6), T.ps(6))))   # sorts before Shader/a.bnsh
        full, _ = self.split('full', [NEW], False)
        t, said = self.split('targeted', [NEW], True)
        self.assertIn('not taken, the targeted scan made a shader_map.tsv that differs', said)
        self.same(full, t)

    def test_a_copy_that_pairs_anew_is_read_from_the_unions_file_list(self):   # Mario Kart 8 Deluxe 4.0.1: 1,024 files read whole, and the pairs still differed
        self.write(NEW, 'Zdup/cross.bnsh', T.bnsh(T.vs(1), T.ps(2)))   # the update's file of copies only, with a pair no other file has
        self.union()
        full, _ = self.split('full', [NEW], False)
        self.assertIn(f'{self.key(T.vs(1))}\t{self.key(T.ps(2))}\n', (full / 'shader_pairs.tsv').read_text())
        t, said = self.split('without', [NEW], True)
        self.assertIn("not taken, the targeted scan made a shader_pairs.tsv that differs from the profile's: every file is scanned", said)
        self.same(full, t)
        self.union(files=True)   # with the union's shader_files.tsv (DM_SPLIT_FILES), whose later version's files carry its tag
        self.assertIn(f'{NEW}/Zdup/cross.bnsh', (self.prof / 'shader_files.tsv').read_text().splitlines())
        t, said = self.split('with', [NEW], True)
        self.assertIn('targeted split: 3 of 8 RomFS files hold the shaders', said)
        self.assertIn(f'its tables match the profile for {NEW}', said)
        self.same(full, t)

    def test_a_union_dump_and_an_unlisted_build_compare_byte_for_byte(self):   # both versions given (the Mac's split), or one listed version only: as before
        self.union()
        full, _ = self.split('full2', [OLD, NEW], False)
        t, said = self.split('targeted', [OLD, NEW], True)
        self.assertNotIn('match the profile for', said)
        self.assertIn("its tables are the profile's, byte for byte", said)
        self.same(full, t)
        g = self.prof / 'game.tsv'; g.write_text(g.read_text().replace(f'shader_versions\t{OLD} {NEW}', f'shader_versions\t{NEW}'))
        t, said = self.split('one', [NEW], True)
        self.assertNotIn('match the profile for', said)
        self.assertIn('and the dump has no such file: every file is scanned', said)   # the tagged rows name no file of an untagged dump, as before

    def test_the_version_helpers(self):   # profile_source_version, source_for_version and version_map_rows on their own
        self.assertEqual(ss.profile_source_version(f'main@{NEW}#BNSH@0x10#var0'), NEW)
        self.assertEqual(ss.profile_source_version(f'/{NEW}/Effect/x.ptcl#BNSH@0x10'), NEW)
        self.assertIsNone(ss.profile_source_version('/Effect/x.ptcl#BNSH@0x10'))
        self.assertIsNone(ss.profile_source_version(f'/{NEW}0x/Effect/x.ptcl'))
        self.assertEqual(ss.source_for_version(f'/{NEW}/Effect/x.ptcl#BNSH@0x10', NEW), '/Effect/x.ptcl#BNSH@0x10')
        self.assertEqual(ss.source_for_version(f'/{NEW}/Effect/x.ptcl#BNSH@0x10', OLD), f'/{NEW}/Effect/x.ptcl#BNSH@0x10')   # another version's row keeps its tag
        row = lambda pkg, i, stage, key, src: f'{pkg}\t{i}\t{stage}\t{key}\t{src}\n'
        head = 'package\tindex\tstage\tdm_ctl_sha16\tsource\n'
        want = '# comment\n' + head + row('ps_effect.bin', 0, 'pixel', 'aa', '/Effect/c.ptcl#BNSH@0x1000#var3') \
            + row('ps_effect.bin', 1, 'pixel', 'bb', f'/{NEW}/Effect/c.ptcl#BNSH@0x3000#var0') + row('vs_main.bin', 0, 'vertex', 'cc', 'main#BNSH@0x10#var0')
        ok = head + row('ps_effect.bin', 0, 'pixel', 'bb', '/Effect/c.ptcl#BNSH@0x3000#var0') + row('ps_effect.bin', 1, 'pixel', 'aa', '/Effect/c.ptcl#BNSH@0x2000#var4') \
            + row('vs_main.bin', 0, 'vertex', 'cc', 'main#BNSH@0x20#var1')
        self.assertEqual(ss.version_map_rows(want), ss.version_map_rows(ok))   # other indices and other places: the same rows
        for bad in (ok.replace('ps_effect.bin\t1', 'ps_shader.bin\t1'),                   # another package
                    ok.replace('\tpixel\taa', '\tvertex\taa'),                            # another stage
                    ok + row('ps_effect.bin', 2, 'pixel', 'dd', '/Effect/c.ptcl#BNSH@0x3000#var1'),   # a key more
                    ok.replace(row('vs_main.bin', 0, 'vertex', 'cc', 'main#BNSH@0x20#var1'), '')):    # a key less
            self.assertNotEqual(ss.version_map_rows(want), ss.version_map_rows(bad), bad)

    @staticmethod
    def key(s): return ss.skey(*s)


if __name__ == '__main__': unittest.main()
