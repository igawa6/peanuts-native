# PEANUTS: new file (a frame rate that starts a variant: Animal Crossing: New Horizons' 60 fps speed fix, dm/games/01006F8002326000/speedfix60)
"""A variant's game data as rows of changed bytes (dm/tools/romfs_rows.py), a third-party IPS as cut rows (dm/tools/ips_rows.py), the pack step's variant rows
(scripts/pack_android.py variant_overlays) and Animal Crossing: New Horizons' data, licence notice and wiring. No game data: the files here are made up, and the
ACNH checks read only the repository's own rows."""
import hashlib
import importlib.util
import io
import os
import random
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/tools')); sys.path.insert(0, str(REPO / 'scripts'))
import romfs_rows   # noqa: E402
import ips_rows   # noqa: E402
import pack_android   # noqa: E402

ACNH = REPO / 'dm/games/01006F8002326000'
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'


def made_up(n=5000, seed=1):
    r = random.Random(seed)
    a = bytes(r.randrange(256) for _ in range(n))
    b = bytearray(a)
    for off in (3, 4, 5, 100, 110, 2000, n - 1):   # runs, a gap shorter than GAP (100..110: one row) and the last byte
        b[off] ^= 0x5A
    return a, bytes(b)


class Rows(unittest.TestCase):
    def test_rows_turn_the_original_into_the_modded_file(self):
        a, b = made_up()
        fr = romfs_rows.file_rows('Bcsv/X.bcsv', a, b)
        self.assertEqual(romfs_rows.apply(fr, a), b)
        self.assertEqual([r[0] for r in fr.rows], [3, 100, 2000, len(a) - 1], 'nearby changes are one row, far ones their own')
        text = romfs_rows.format_rows([fr], '# made up\n')
        back = romfs_rows.parse(text)
        self.assertEqual(len(back), 1)
        self.assertEqual(romfs_rows.apply(back[0], a), b, 'through the text form')

    def test_a_file_of_another_version_is_refused(self):
        a, b = made_up()
        fr = romfs_rows.file_rows('X', a, b)
        other = bytearray(a); other[4000] ^= 1   # a byte no row touches: the size and hash check catches it
        with self.assertRaisesRegex(romfs_rows.Error, 'another game version'): romfs_rows.apply(fr, bytes(other))
        with self.assertRaisesRegex(romfs_rows.Error, 'another game version'): romfs_rows.apply(fr, a + b'\0')
        bad = romfs_rows.FileRows(fr.path, fr.orig_size, fr.orig_sha, fr.new_size, fr.new_sha, [(3, b'\0\0\0', fr.rows[0][2])] + fr.rows[1:])
        with self.assertRaisesRegex(romfs_rows.Error, 'original bytes differ at 0x3'): romfs_rows.apply(bad, a)

    def test_a_file_that_changes_its_size(self):
        a, _ = made_up()
        b = a[:300] + b'inserted bytes' + a[310:]
        fr = romfs_rows.file_rows('Pack/Y.pack', a, b)
        self.assertEqual(len(fr.rows), 1)
        self.assertEqual(romfs_rows.apply(romfs_rows.parse(romfs_rows.format_rows([fr]))[0], a), b)
        fr = romfs_rows.file_rows('Z', a, a[:-7])
        self.assertEqual(fr.rows[0][2], b'', 'a deletion: no new bytes')
        self.assertIn('\t-\n', romfs_rows.format_rows([fr]))

    def test_malformed_rows(self):
        h = '0' * 64
        for text, why in ((f'0x0\t00\t01\n', 'before the first file line'), (f'file\t/abs\t1\t{h}\t1\t{h}\n', 'file line'), (f'file\ta/../b\t1\t{h}\t1\t{h}\n', 'file line'),
                          (f'file\ta\t4\t{h}\t4\t{h}\n0x3\t0000\t0101\n', 'past the original'), (f'file\ta\t9\t{h}\t9\t{h}\n0x4\t00\t01\n0x2\t00\t01\n', 'ascending'),
                          (f'file\ta\t9\t{h}\t9\t{h}\n0x4\tzz\t01\n', 'not hex'), (f'file\ta\t9\t{h}\t9\t{h}\n0x4\t-\t-\n', 'changes nothing'),
                          (f'file\ta\t9\t{h}\t9\t{h}\n-0x4\t00\t01\n', 'not an offset'), (f'file\ta\t\u00b2\t{h}\t9\t{h}\n', 'file line'),
                          (f'file\ta\t9\t{h}\t9\t{h}\n0x4\t00\n', 'a row takes'), (f'file\ta\t9\t{h}\n', 'file line')):
            with self.subTest(why=why), self.assertRaisesRegex(romfs_rows.Error, why): romfs_rows.parse(text)
        with tempfile.TemporaryDirectory() as d:   # not UTF-8: an Error too, which variant_overlays turns into no variant
            (Path(d) / 'rows.tsv').write_bytes(b'file\t\xff\n')
            with self.assertRaisesRegex(romfs_rows.Error, 'not UTF-8'): romfs_rows.load(Path(d) / 'rows.tsv')

    def test_make_is_all_or_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); (d / 'romfs/Bcsv').mkdir(parents=True); (d / 'mod/Bcsv').mkdir(parents=True)
            a1, b1 = made_up(seed=2); a2, b2 = made_up(seed=3)
            (d / 'romfs/Bcsv/A.bcsv').write_bytes(a1); (d / 'romfs/Bcsv/B.bcsv').write_bytes(a2)
            (d / 'mod/Bcsv/A.bcsv').write_bytes(b1); (d / 'mod/Bcsv/B.bcsv').write_bytes(b2)
            with redirect_stdout(io.StringIO()): romfs_rows.main(['romfs_rows.py', 'diff', str(d / 'rows.tsv'), str(d / 'romfs'), str(d / 'mod'), 'Bcsv/A.bcsv', 'Bcsv/B.bcsv'])
            self.assertEqual(romfs_rows.make(d / 'rows.tsv', str(d / 'romfs'), d / 'out'), ['Bcsv/A.bcsv', 'Bcsv/B.bcsv'])
            self.assertEqual((d / 'out/Bcsv/A.bcsv').read_bytes(), b1); self.assertEqual((d / 'out/Bcsv/B.bcsv').read_bytes(), b2)
            (d / 'romfs/Bcsv/B.bcsv').write_bytes(a2[:-1] + b'\0')   # another version of the second file
            with self.assertRaises(romfs_rows.Error): romfs_rows.make(d / 'rows.tsv', str(d / 'romfs'), d / 'out')
            self.assertEqual((d / 'out/Bcsv/A.bcsv').read_bytes(), b1, 'nothing written when a file does not apply')
            (d / 'romfs/Bcsv/B.bcsv').unlink()
            with self.assertRaisesRegex(romfs_rows.Error, 'cannot be read'): romfs_rows.make(d / 'rows.tsv', str(d / 'romfs'), d / 'out')


def nso_bytes(text, build_id=b'\x11' * 32):
    """An uncompressed NSO0 with one text segment and empty ro and data (enough for nso.py's load)"""
    hdr = bytearray(0x100); hdr[:4] = b'NSO0'; hdr[0x40:0x60] = build_id
    off = 0x100
    for i, (mo, data) in enumerate(((0, text), (len(text), b''), (len(text), b''))):
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, off, mo, len(data)); struct.pack_into('<I', hdr, 0x60 + 4 * i, len(data)); off += len(data)
    return bytes(hdr) + text


class IpsRows(unittest.TestCase):
    def test_records_become_rows_with_the_original_bytes(self):
        import ips
        image = bytes(range(256)) * 4
        patch = ips.build([(0x100 + 0x10, b'\xaa\xbb\xcc\xdd'), (0x100 + 0x200, b'\x01\x02')], ips32=True)
        rs = ips_rows.rows(patch, image, {0x110: 'speed x1/2'})
        self.assertEqual(rs, [(0x10, image[0x10:0x14], b'\xaa\xbb\xcc\xdd', 'speed x1/2'), (0x200, image[0x200:0x202], b'\x01\x02', '')])
        text = ips_rows.format_rows(rs)
        self.assertEqual(text, '# speed x1/2\n0x10\t10111213\taabbccdd\n0x200\t0001\t0102\n')
        with self.assertRaisesRegex(ips_rows.Error, 'NSO header'): ips_rows.rows(ips.build([(0x10, b'\0')], ips32=True), image)
        with self.assertRaisesRegex(ips_rows.Error, 'past the module'): ips_rows.rows(ips.build([(0x100 + len(image) - 1, b'\0\0')], ips32=True), image)
        with self.assertRaisesRegex(ips_rows.Error, 'overlaps'): ips_rows.rows(ips.build([(0x110, b'\0\0'), (0x111, b'\0')], ips32=True), image)

    def test_the_cli_reads_a_module_and_checks_the_pchtxt(self):
        import ips
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'main').write_bytes(nso_bytes(bytes(range(256)) * 16))
            (d / 'p.ips').write_bytes(ips.build([(0x100 + 0x40, b'\x08\x10\x2c\x1e')], ips32=True))
            (d / 'p.pchtxt').write_text('@nsobid-11\n@flag offset_shift 0x100\n// step 1.0 -> 0.5\n@enabled\n00000040 08102C1E\n@stop\n')
            with redirect_stdout(io.StringIO()): ips_rows.main(['ips_rows.py', str(d / 'p.ips'), str(d / 'main'), str(d / 'rows.tsv'), '--pchtxt', str(d / 'p.pchtxt')])
            self.assertEqual((d / 'rows.tsv').read_text(), '# step 1.0 -> 0.5\n0x40\t40414243\t08102c1e\n')
            (d / 'p.pchtxt').write_text('@flag offset_shift 0x100\n@enabled\n00000040 00000000\n')
            with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()): ips_rows.main(['ips_rows.py', str(d / 'p.ips'), str(d / 'main'), str(d / 'rows.tsv'), '--pchtxt', str(d / 'p.pchtxt')])


class G:   # the profile parts variant_overlays reads (dm/games/game.py)
    def __init__(self, d, rows): self.dir, self.id, self._rows = d, '01006F8002326000', rows
    def values(self, key): return [r[1:] for r in self._rows if r[0] == key]


class Overlays(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp()); self.addCleanup(shutil.rmtree, self.d)
        prof = self.d / 'profile'; (prof / 'rows').mkdir(parents=True)
        (prof / 'patches.py').write_text("VARIANT_CUTS = {'60': {'aa' * 16: [(0, '00', '01', 'x')]}}\n")
        self.romfs = self.d / 'romfs'; (self.romfs / 'Bcsv').mkdir(parents=True); (self.d / 'mod/Bcsv').mkdir(parents=True)
        a, b = made_up(seed=4)
        (self.romfs / 'Bcsv/A.bcsv').write_bytes(a); (self.d / 'mod/Bcsv/A.bcsv').write_bytes(b); self.modded = b
        with redirect_stdout(io.StringIO()): romfs_rows.main(['romfs_rows.py', 'diff', str(prof / 'rows/romfs.tsv'), str(self.romfs), str(self.d / 'mod'), 'Bcsv/A.bcsv'])
        self.g = G(prof, [('variant', '60', 'main', '60fps'), ('variant_romfs', '60', 'rows/romfs.tsv')])
        self.out = self.d / 'pack'; (self.out / '60').mkdir(parents=True)
        (self.out / '60/main.patched.bin').write_bytes(b'x'); (self.out / '60/main.android.key').write_text('k variant=60\n')

    def run_(self, ids):
        buf = io.StringIO()
        with redirect_stdout(buf): r = pack_android.variant_overlays(self.g, self.romfs, self.out, ids)
        return r, buf.getvalue()

    def test_a_complete_variant_is_listed_with_its_game_data(self):
        r, _ = self.run_({'main': 'aa' * 16})
        self.assertEqual(r, [('60', '60/romfs')])
        self.assertEqual((self.out / '60/romfs/Bcsv/A.bcsv').read_bytes(), self.modded)

    def test_another_main_build_or_other_files_are_not_listed(self):
        r, log = self.run_({'main': 'bb' * 16})
        self.assertEqual(r, []); self.assertIn('[YELLOW] variant 60 not offered: no cuts for build', log)
        (self.romfs / 'Bcsv/A.bcsv').write_bytes(b'another version')
        r, log = self.run_({'main': 'aa' * 16})
        self.assertEqual(r, []); self.assertIn('do not apply to these game files', log)
        self.assertFalse((self.out / '60/romfs').exists(), 'no game data of an earlier run stays')

    def test_an_unfinished_rewrite_is_not_listed(self):
        (self.out / '60/main.android.key').unlink()
        r, log = self.run_({'main': 'aa' * 16})
        self.assertEqual(r, []); self.assertIn('no rewritten main', log)

    def test_a_variant_without_game_data(self):
        self.g = G(self.g.dir, [('variant', '60', 'main', '60fps')])
        self.assertEqual(self.run_({'main': 'aa' * 16})[0], [('60', '-')])



class VariantRewrite(unittest.TestCase):
    """rewrite_variants on the phone (in_process): the variant's module in the rewrite worker on the fast cores (pn_rewrite_worker), as the module's first
    rewrite, with DM_PATCH_VARIANT; in this process (run_in_process, one core) only when there is no worker"""
    def setUp(self):
        self.d = Path(tempfile.mkdtemp()); self.addCleanup(shutil.rmtree, self.d)
        self.src = self.d / 'main'; self.src.write_bytes(b'module')
        self.calls = []
        def fake(kind, rc):
            def run(script, args, env, log, progress=None):
                self.calls.append((kind, env)); stage = Path(args[1]); stage.mkdir(parents=True, exist_ok=True); (stage / 'main.patched.bin').write_bytes(b'v')
                Path(log).write_text('')
                return rc
            return run
        self.fake = fake
        for name in ('pn_rewrite_worker', 'run_in_process', 'rewrite_key'):
            self.addCleanup(setattr, pack_android, name, getattr(pack_android, name))
        pack_android.rewrite_key = lambda src, g: 'k'

    def run_(self, modded=()):
        with redirect_stdout(io.StringIO()):
            pack_android.rewrite_variants(sys.executable, {'main': self.src}, self.d / 'pack', G(self.d, [('variant', '60', 'main', '60fps')]), in_process=True,
                                          modded=modded)

    def test_in_the_worker(self):
        (self.d / 'pack').mkdir()
        pack_android.pn_rewrite_worker = self.fake('worker', 0); pack_android.run_in_process = self.fake('in process', 0)
        self.run_()
        self.assertEqual([(k, e.get('DM_PATCH_VARIANT')) for k, e in self.calls], [('worker', '60')])
        self.assertEqual((self.d / 'pack/60/main.android.key').read_text().strip(), 'k variant=60')

    def test_in_this_process_without_a_worker(self):
        (self.d / 'pack').mkdir()
        pack_android.pn_rewrite_worker = lambda *a, **k: None; pack_android.run_in_process = self.fake('in process', 0)
        self.run_()
        self.assertEqual([(k, e.get('DM_PATCH_VARIANT'), e.get('DM_REWRITE_J')) for k, e in self.calls], [('in process', '60', '1')])
        self.assertTrue((self.d / 'pack/60/main.android.key').is_file())

    def test_a_failed_worker_is_an_error_with_no_second_try(self):
        for rc, modded in ((1, ()), (-9, ()), (-9, ('main',))):   # -9: the low-memory killer ended the worker, also in a build whose mods change main
            with self.subTest(rc=rc, modded=modded):
                self.calls.clear(); shutil.rmtree(self.d / 'pack', ignore_errors=True); (self.d / 'pack').mkdir()
                pack_android.pn_rewrite_worker = self.fake('worker', rc); pack_android.run_in_process = self.fake('in process', 0)
                with self.assertRaises(pack_android.Error): self.run_(modded)
                self.assertEqual([k for k, _ in self.calls], ['worker'])
                self.assertFalse((self.d / 'pack/60/main.android.key').exists())

    def test_a_mod_that_changes_the_module_drops_the_variant(self):
        (self.d / 'pack').mkdir()
        pack_android.pn_rewrite_worker = self.fake('worker', 1); pack_android.run_in_process = self.fake('in process', 0)
        self.run_(('main',))
        self.assertEqual([k for k, _ in self.calls], ['worker'])
        self.assertFalse((self.d / 'pack/60').exists())


def load_patches():
    spec = importlib.util.spec_from_file_location('acnh_patches', ACNH / 'patches.py'); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m


class AnimalCrossing(unittest.TestCase):
    def test_the_variant_cuts_are_the_mods_rows_for_303_only(self):
        m = load_patches()
        self.assertEqual(list(m.VARIANT_CUTS), ['60'])
        self.assertEqual(list(m.VARIANT_CUTS['60']), ['ff1d1c05670db6021c85b624a710b963'])
        cuts = m.VARIANT_CUTS['60']['ff1d1c05670db6021c85b624a710b963']
        self.assertEqual(len(cuts), 6810, "the IPS's 6,810 records")
        self.assertTrue(all(len(ob) == len(nb) and ob != nb for _, ob, nb, _ in cuts), 'every row changes bytes in place')
        self.assertTrue(all(why.startswith('ACNH 60 FPS + SpeedFix (tmouh): ') for *_, why in cuts))
        self.assertEqual(len({o for o, *_ in cuts}), len(cuts), 'no offset twice')
        self.assertFalse(getattr(m, 'CUTS', {}), "the game's own code has no cut")
        for rel, sha in m._SPEEDFIX_SHA256.items():
            self.assertEqual(hashlib.sha256((ACNH / rel).read_bytes()).hexdigest(), sha, rel)

    def test_the_game_data_rows(self):
        frs = romfs_rows.load(ACNH / 'speedfix60/romfs.tsv')
        self.assertEqual(sorted(f.path for f in frs), ['Bcsv/ItemNpcFtrActionParam.bcsv', 'Bcsv/ItemNpcWherearenFtrActionParam.bcsv', 'Bcsv/NpcInterest.bcsv', 'Pack/StaticParam.pack'])
        new = sum(len(nb) for f in frs for _, _, nb in f.rows)
        self.assertLess(new, sum(f.new_size for f in frs) // 4, 'only changed bytes, no whole game file')
        self.assertLess((ACNH / 'speedfix60/romfs.tsv').stat().st_size, 512 * 1024)

    def test_the_licence_notice(self):
        n = (ACNH / 'speedfix60/NOTICE.md').read_text(encoding='utf-8')
        for want in ('CC BY-NC 4.0', 'tmouh', 'https://github.com/tmouh/acnh-60fps', 'v1.4.5', 'https://creativecommons.org/licenses/by-nc/4.0/', 'converted', 'unchanged',
                     'FF1D1C05670DB6021C85B624A710B963.ips', 'No game file is in this folder'):
            self.assertIn(want, n)
        for f in ('main.tsv', 'romfs.tsv'):
            self.assertIn('NOTICE.md', (ACNH / 'speedfix60' / f).read_text(encoding='utf-8').splitlines()[0], f + ' points to the notice')
        self.assertIn('CC BY-NC 4.0', (ACNH / 'patches.py').read_text(encoding='utf-8'))

    def test_credits(self):
        for f, want in ((ACNH / 'README.md', 'tmouh'), (REPO / 'README.md', 'tmouh'), (REPO / 'android/NOTICE.md', 'tmouh'),
                        (REPO / 'android/app/src/main/java/dev/peanuts/ui/AboutData.java', '{"ACNH 60 FPS + SpeedFix", "tmouh\'s 60 fps for Animal Crossing", "CC BY-NC 4.0", "https://github.com/tmouh/acnh-60fps"}')):
            with self.subTest(f=f.name):
                t = f.read_text(encoding='utf-8')
                self.assertIn(want, t); self.assertIn('acnh-60fps', t)

    def test_profile_rows(self):
        t = (ACNH / 'game.tsv').read_text(encoding='utf-8')
        for row in ('fps_limit_present\t1', 'fps_modes\t60', 'variant\t60\tmain\t60fps', 'variant_romfs\t60\tspeedfix60/romfs.tsv'):
            self.assertIn('\n' + row + '\n', t)
        self.assertNotRegex(t, r'(?m)^fps_limit\t', 'no fps_limit: opt-in present pacing (60 fps by default since 0.1.8)')
        self.assertNotIn('A226048605D3597F', (ACNH / 'speedfix60/main.tsv').read_text(encoding='utf-8'), "the mod's sdk patch is not used")
        self.assertFalse(list((ACNH / 'speedfix60').glob('*.ips')), 'no IPS shipped')

    def test_the_start(self):
        act = (SRC / 'GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('env.add("DM_PATCH_VARIANT=" + fpsVariantAtStart);', act)
        self.assertIn('env.add("DM_PRESENT_INTERVAL=display");', act)
        self.assertIn('pack.variantReady(fv)', act)
        self.assertLess(act.index('pack.variantRomfs(fpsVariantAtStart)'), act.index('env.addAll(user);'), "the player's own DM_ROMFS_OVERLAY line comes later and wins")
        self.assertLess(act.index('if (modOverlay != null) env.add(modOverlay);'), act.index('pack.variantRomfs(fpsVariantAtStart)'), "the variant's data goes first, before the mods' chain")
        prefs = (SRC / 'Prefs.java').read_text(encoding='utf-8')
        self.assertIn('applies at the next start', prefs.split('FPS_VARIANT_NOTE = ')[1].split(';')[0])
        self.assertIn("('variant', f'{v}\\t{p}')", (REPO / 'scripts/pack_android.py').read_text(encoding='utf-8'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class FpsVariantJvm(unittest.TestCase):
    def test_jvm(self):
        stub = ('package dev.nxnative.android;\npublic final class BuildConfig {\n    public static final String NX_PROFILES = "";\n'
                '    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123", NX_DMSH_VK_ABI_COMPAT = "";\n}\n')
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); (d / 'stub').mkdir(); (d / 'run').mkdir()
            (d / 'stub/BuildConfig.java').write_text(stub)
            srcs = [SRC / f'{n}.java' for n in ('Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Nup', 'Nsp', 'Zstd', 'Profile', 'ShaderLibs', 'FrameRateCaps')]
            test = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/FpsVariantJvmTest.java'
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, srcs), str(test), str(d / 'stub/BuildConfig.java')],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.nxnative.android.FpsVariantJvmTest', str(d / 'run')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
