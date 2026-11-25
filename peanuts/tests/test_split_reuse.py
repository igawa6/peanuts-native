"""dm/peanuts/reuse/split_reuse.py (issue #120): the shader split keeps the scan results of game files that did not change across builds and scans only the
others; its outputs are always the ones a split without it makes, byte for byte. shader_split.py runs as on the phone (a .nup's RomFS, PN_REUSE_DIR), on a
synthetic game packed with dm/tools/nup_pack.py (raw files, so no zstd): BNSH and SARC files with shaders made as the targeted split's test makes them, and files
without any. No game files."""
import os
import pickle
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(REPO / 'dm/tools/tests'), str(REPO / 'dm/peanuts/reuse')]
from test_split_targeted import BUILD, GAME, TABLES, bnsh, ps, sarc, ss, vs   # noqa: E402  (its synthetic shader files)
import split_reuse   # noqa: E402

MODELS = 60   # files with no shader: four batches of 16 with the others, so the check has batches to take


def nlg(name, shader):
    """A Next Level Games archive as shader_split.py nlg_dict and nlg_shaders read it (stored as is): its index, of one variant whose control section (section 8)
    and code area (section 7) of one stage lie in the file the index names, name (the reader opens the index's path less five characters followed by it); and
    that file's bytes"""
    ctl, code = shader
    head = ss.NLG_DICT + bytes([4, 1, 0, 0]) + struct.pack('<I', len(ctl)) + bytes([3, 1, 1, 0])   # not compressed; 3 chunks, 1 record, 1 name
    secs = bytearray(16); secs[7], secs[8] = 2, 1
    chunks = [(0, 0), (0, len(ctl)), (len(ctl), len(code))]   # 0: the variant's entry table (not read without a patch), 1: the control section, 2: the code area
    return head + struct.pack('<IHH', 0, 0, 0) + bytes(secs) + b''.join(struct.pack('<3IHBB', o, n, 0, 0, 0, 0) for o, n in chunks) + name.encode() + b'\0', ctl + code


class SplitReuse(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix='pnsplit_')); self.addCleanup(lambda: shutil.rmtree(self.tmp, ignore_errors=True))
        self.reuse = self.tmp / 'reuse'
        self.prof = self.tmp / 'profiles' / GAME; self.prof.mkdir(parents=True)
        (self.prof / 'game.tsv').write_text(f'title_id\t{GAME}\nbuild\tv0\t1.0.0\tmain={BUILD.hex()}\nshader_groups\t/Shader/=shader\nshader_note\tTest shaders\n')
        self.files = {'Shader/a.bnsh': bnsh(vs(1), ps(1)), 'Shader/pack.sarc': sarc([(b'b.bnsh', bnsh(vs(2), ps(2)))])}
        self.files.update({f'Model/m{i:02}.bin': b'no shaders here %d' % i for i in range(MODELS)})
        self.n = 0

    def nup(self, files):
        """A .nup of the base with these RomFS files and a main of the profile's build"""
        self.n += 1
        dump = self.tmp / f'dump{self.n}'
        for rel, data in files.items():
            p = dump / 'base' / 'romfs' / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)
        main = dump / 'base' / 'exefs' / 'main'; main.parent.mkdir(parents=True, exist_ok=True)
        main.write_bytes(b'NSO0' + bytes(0x3c) + BUILD + bytes(0x100 - 0x50))
        out = self.tmp / f'game{self.n}.nup'
        r = subprocess.run([sys.executable, REPO / 'dm/tools/nup_pack.py', dump, out, '--raw'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return out

    def split(self, nup, name, reuse=True, **extra):
        out = self.tmp / name
        env = dict(os.environ, DM_GAME=str(self.prof), J='1', DM_SPLIT_SELECTED='0')
        for k in ('DM_SPLIT_RESUME', 'DM_SPLIT_TARGETED', 'DM_SPLIT_FILES', 'DM_GAMES_DIR', 'PN_REUSE', 'PN_REUSE_DIR'): env.pop(k, None)
        if reuse: env['PN_REUSE_DIR'] = str(self.reuse)
        else: env['PN_REUSE'] = '0'
        env.update(extra)
        r = subprocess.run([sys.executable, REPO / 'dm/gfx/tools/shader_split.py', f'{nup}:base/romfs', f'{nup}:base/exefs/main', out / 'pkg',
                            *[out / t for t in TABLES]], capture_output=True, text=True, env=env)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        line = [l for l in r.stdout.splitlines() if l.startswith('split reuse')]
        return out, line[-1] if line else '', r.stdout

    def same(self, a, b):
        self.assertEqual(sorted(os.listdir(a / 'pkg')), sorted(os.listdir(b / 'pkg')))
        for f in os.listdir(a / 'pkg'): self.assertEqual((a / 'pkg' / f).read_bytes(), (b / 'pkg' / f).read_bytes(), f)
        for t in TABLES: self.assertEqual((a / t).read_bytes(), (b / t).read_bytes(), t)

    @staticmethod
    def kept(line):   # 'split reuse: K of N files kept ...' → K
        return int(line.split()[2])

    def test_a_second_split_keeps_every_file_and_makes_the_same_outputs(self):
        nup = self.nup(self.files)
        ref, _, _ = self.split(nup, 'ref', reuse=False)
        first, line, _ = self.split(nup, 'first')
        self.assertEqual(self.kept(line), 0)
        self.same(first, ref)
        again, line, _ = self.split(nup, 'again')
        self.assertGreater(self.kept(line), MODELS // 2)   # all but the batches scanned to check them
        self.same(again, ref)

    def test_a_changed_file_and_a_new_one_are_scanned(self):
        self.split(self.nup(self.files), 'first')
        changed = dict(self.files)
        changed['Shader/a.bnsh'] = bnsh(vs(3), ps(1))   # another vertex shader in a file that had one
        changed['Shader/new.bnsh'] = bnsh(vs(4), ps(4))   # a file that was not there
        changed['Model/m05.bin'] = b'changed'   # a file beside many unchanged ones
        nup = self.nup(changed)
        after, line, _ = self.split(nup, 'after')
        ref, _, _ = self.split(nup, 'ref', reuse=False)
        self.same(after, ref)
        self.assertGreaterEqual(self.kept(line), MODELS - 16 - 1)   # the unchanged files of the Model folder (less a batch scanned to check them and m05)

    def test_past_the_cap_this_games_kept_results_go(self):
        # the kept results count in the cap of every title's kept copies (igawa6's review of #150: they were outside it), and they never take another copy's place
        other = self.tmp / '0100000000CC0000' / 'library' / 'v-aaaaaaaaaaaa' / 'dmsh_vk.lib'
        other.parent.mkdir(parents=True); other.write_bytes(b'\0' * (1 << 20))
        nup = self.nup(self.files)
        _, _, out = self.split(nup, 'first', PN_REUSE_KEEP_MB='1')
        self.assertIn('split reuse: this game\'s kept results deleted', out)
        self.assertFalse((self.reuse / 'split').exists())
        self.assertTrue(other.is_file())
        _, line, _ = self.split(nup, 'again', PN_REUSE_KEEP_MB='1')
        self.assertEqual(self.kept(line), 0)
        _, _, out = self.split(nup, 'roomy')   # under the cap: kept
        self.assertNotIn('kept results deleted', out)
        self.assertGreater(self.kept(self.split(nup, 'kept')[1]), MODELS // 2)

    def test_off(self):
        nup = self.nup(self.files)
        self.split(nup, 'first')
        off, line, _ = self.split(nup, 'off', reuse=False)
        self.assertEqual(line, '')
        self.same(off, self.split(nup, 'ref', reuse=False)[0])

    def test_kept_results_that_differ_are_caught_and_scanned_again(self):
        nup = self.nup(self.files)
        ref, _, _ = self.split(nup, 'ref', reuse=False)
        self.split(nup, 'first')
        # every kept result altered: the batches scanned to check differ from them
        for f in self.reuse.rglob('*.pkl'):
            data = pickle.loads(f.read_bytes())
            if f.name.startswith('empty-'): data = {k: (r[0], r[1], {('0' * 16, '1' * 16)}, r[3]) for k, r in data.items()}
            else: data = (data[0], {}, set(), data[3])
            f.write_bytes(pickle.dumps(data))
        again, _, out = self.split(nup, 'again')
        self.assertIn('[RED] split reuse:', out)
        self.same(again, ref)
        self.assertFalse(any(self.reuse.rglob('*.pkl')) and 'kept results deleted' not in out)

    def test_a_files_key_follows_the_files_of_its_stem(self):
        # a reader may open stem + '.dict' or stem + '.patch' beside a file (Next Level Games): their change changes its key; another file of the folder does not
        base = {'Data/a.data': b'data', 'Data/a.dict': b'dict 1', 'Data/b.bin': b'other 1'}
        keys = []
        for files in (base, dict(base, **{'Data/b.bin': b'other 2'}), dict(base, **{'Data/a.dict': b'dict 2'})):
            nup = self.nup(files)
            os.environ['PN_REUSE_DIR'] = str(self.reuse)
            try:
                c = split_reuse.Context(None, {}, None, None, [], pickle.Unpickler)
                keys.append(c.key((f'{nup}:base/romfs/Data/a.data', 'Data/a.data')))
            finally: del os.environ['PN_REUSE_DIR']
        self.assertIsNotNone(keys[0])
        self.assertEqual(keys[0], keys[1])   # b.bin is not a.data's
        self.assertNotEqual(keys[0], keys[2])   # a.dict is

    def test_a_folder_of_its_stem_counts_too(self):
        # shader_split.py scan_file_in reads a .data file as empty when stem + '.dict' exists (os.path.exists: a folder too, found in review)
        keys = []
        for dirs in ((), ('Data/a.dict',)):
            dump = self.tmp / f'dump-dir{len(dirs)}'
            for rel, data in {'Data/a.data': b'data', 'Data/b.bin': b'other'}.items():
                p = dump / 'base' / 'romfs' / rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)
            for rel in dirs:
                (dump / 'base' / 'romfs' / rel).mkdir(parents=True)
                (dump / 'base' / 'romfs' / rel / 'x').write_bytes(b'x')   # (a folder with a file in it)
            main = dump / 'base' / 'exefs' / 'main'; main.parent.mkdir(parents=True, exist_ok=True)
            main.write_bytes(b'NSO0' + bytes(0x3c) + BUILD + bytes(0x100 - 0x50))
            nup = self.tmp / f'dir{len(dirs)}.nup'
            r = subprocess.run([sys.executable, REPO / 'dm/tools/nup_pack.py', dump, nup, '--raw'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            os.environ['PN_REUSE_DIR'] = str(self.reuse)
            try: keys.append(split_reuse.Context(None, {}, None, None, [], pickle.Unpickler).key((f'{nup}:base/romfs/Data/a.data', 'Data/a.data')))
            finally: del os.environ['PN_REUSE_DIR']
        self.assertIsNotNone(keys[0])
        self.assertNotEqual(keys[0], keys[1])

    def test_what_the_next_level_games_reader_opens_is_in_the_key(self):
        # nlg_shaders goes by the index's magic, not the name, and opens the index's path less five characters (as if it ended in '.dict') followed by each name
        # the index lists: for Shader/level.idx that is Shader/leve.data, which does not start with the stem "level"; and a name may lead into a folder (lv + _d/x.data).
        # Neither was in the key, so a changed data file left the archive's kept result from before (two files: too few for the check to take one)
        for n, (index, data) in enumerate((('Shader/level.idx', 'Shader/leve.data'), ('Shader/lv.dict', 'Shader/lv_d/x.data'))):
            with self.subTest(index):
                shutil.rmtree(self.reuse, ignore_errors=True)
                name = data[len(index) - len('.dict'):]
                files = dict(zip((index, data), nlg(name, vs(5))))
                self.split(self.nup(files), f'first{n}')
                files[data] = nlg(name, vs(6))[1]   # another shader in the data file, the index the same
                nup = self.nup(files)
                after, line, _ = self.split(nup, f'after{n}')
                ref, _, _ = self.split(nup, f'ref{n}', reuse=False)
                self.assertIn(f'/{index}#NLG0:', (ref / 'shader_map.tsv').read_text())   # the reader took it
                self.same(after, ref)
                self.assertEqual(self.kept(line), 0)

    def test_the_magic_is_the_readers_and_a_name_shorter_than_dict_is_not_kept(self):
        # the archive's magic as shader_split.py has it; nlg_shaders would cut a name of fewer than five characters in its folder's name and open files a folder up
        self.assertEqual(split_reuse.NLG, ss.NLG_DICT)
        idx, data = nlg('.data', vs(5))
        nup = self.nup({'Data/abc': idx, 'Data/abcd.data': data, 'Data/a.b': b'not an archive', 'Data/abcde': idx})
        os.environ['PN_REUSE_DIR'] = str(self.reuse)
        try:
            c = split_reuse.Context(None, {}, None, None, [], pickle.Unpickler)
            keys = {rel: c.key((f'{nup}:base/romfs/{rel}', rel)) for rel in ('Data/abc', 'Data/a.b', 'Data/abcde')}
        finally: del os.environ['PN_REUSE_DIR']
        self.assertIsNone(keys['Data/abc'])
        self.assertIsNotNone(keys['Data/a.b'])   # another reader's: its stem
        self.assertIsNotNone(keys['Data/abcde'])   # five: the reader's path ends at the folder

    def test_the_files_scanned_to_check_change_from_split_to_split(self):
        # the split has no full check later: a sample that took the same files on every split would never meet a kept result that went stale elsewhere
        nup = self.nup(self.files)
        self.split(nup, 'first')
        seen = set()
        for i in range(6):
            _, line, _ = self.split(nup, f'again{i}')
            m = re.search(r'scanned to check \(the same: ([^)]*)\)', line)
            self.assertIsNotNone(m, line)
            names = [n for n in m.group(1).split(', ') if n]
            self.assertEqual(len(names), 2)   # one in 50 of the 62 that could be kept, rounded up
            seen |= set(names)
        self.assertGreater(len(seen), 2)

    def test_a_split_that_kept_nothing_names_no_files_scanned_to_check(self):
        # a phone's first build logged "0 of them scanned to check (the same: )", an empty list (seen on the Thor)
        nup = self.nup(self.files)
        _, line, _ = self.split(nup, 'first')
        self.assertIn('0 of them scanned to check,', line)
        self.assertNotIn('the same', line)

    def test_a_damaged_kept_file_is_scanned_again_not_the_reuse_turned_off(self):
        nup = self.nup(self.files)
        ref, _, _ = self.split(nup, 'ref', reuse=False)
        self.split(nup, 'first')
        kept = [p for p in self.reuse.rglob('*.pkl') if not p.name.startswith('empty-')]
        self.assertTrue(kept)
        for p in kept: p.write_bytes(b'')   # what a rename that reached storage before its data leaves after a reboot
        for name in ('second', 'third'):
            got, line, out = self.split(nup, name)
            self.assertNotIn('off for this split', out)
            self.same(got, ref)
        self.assertTrue(all(p.stat().st_size for p in kept if p.exists()))   # kept anew, whole

    def test_the_common_part_covers_the_reuse_and_the_yaz0_module(self):
        # what split_reuse.py keeps (canon, its rules) and the C Yaz0 decoder (its bytes are the loop's, or None) decide results as the tools do
        os.environ['PN_REUSE_DIR'] = str(self.reuse)
        try:
            parts = [split_reuse.Context(None, {}, None, None, [], pickle.Unpickler).part]
            copy = self.tmp / 'mod' / 'split_reuse.py'; copy.parent.mkdir()
            copy.write_text((REPO / 'dm/peanuts/reuse/split_reuse.py').read_text(encoding='utf-8') + '\n# another rule\n', encoding='utf-8')
            import importlib.util
            spec = importlib.util.spec_from_file_location('split_reuse_changed', copy)
            changed = importlib.util.module_from_spec(spec); spec.loader.exec_module(changed)
            parts.append(changed.Context(None, {}, None, None, [], pickle.Unpickler).part)
            fake = self.tmp / 'yaz0'; fake.mkdir()
            (fake / '_nxyaz0.py').write_text('def decode(s): return None\n')
            sys.path.insert(0, str(fake))
            try: parts.append(split_reuse.Context(None, {}, None, None, [], pickle.Unpickler).part)
            finally: sys.path.remove(str(fake))
        finally: del os.environ['PN_REUSE_DIR']
        self.assertEqual(len(set(parts)), 3, parts)

    def test_a_decode_that_failed_once_is_not_kept(self):
        # a size-prefixed container (HAL's .cmp: shader_split.py unsized) whose LZ4 frame the decoder could not open this once (no memory): the file is
        # unreadable for this split, never a result to keep, and the next split reads its shaders. The stand-in lz4 undoes a XOR, or fails as asked
        fake = self.tmp / 'fake' / 'lz4'; fake.mkdir(parents=True)
        (fake / '__init__.py').write_text('')
        (fake / 'frame.py').write_text("import os\n"
                                       "def decompress(f):\n"
                                       "    if os.environ.get('FAKE_LZ4_FAIL') == '1': raise MemoryError('the decoder could not allocate its output')\n"
                                       "    return bytes(b ^ 0x5A for b in f[7:])\n")
        payload = bnsh(vs(7), ps(7))
        files = dict(self.files, **{'Shader/z.cmp': struct.pack('<I', len(payload)) + bytes.fromhex('04224d18404000') + bytes(b ^ 0x5A for b in payload)})
        nup = self.nup(files)
        path = os.pathsep.join([str(fake.parent)] + ([os.environ['PYTHONPATH']] if os.environ.get('PYTHONPATH') else []))
        ref, _, _ = self.split(nup, 'ref', reuse=False, PYTHONPATH=path)
        failed, _, out1 = self.split(nup, 'failed', PYTHONPATH=path, FAKE_LZ4_FAIL='1')
        self.assertIn('Shader/z.cmp', out1)   # its red line
        self.assertNotEqual([(failed / t).read_bytes() for t in TABLES], [(ref / t).read_bytes() for t in TABLES])   # that split lacks z.cmp's shaders
        after, line, out2 = self.split(nup, 'after', PYTHONPATH=path)
        self.assertNotIn('[RED] split reuse', out2)   # nothing kept that a check finds different
        self.same(after, ref)

    def test_canon_ignores_the_order_of_sets_and_dicts(self):
        a = ('x', {'k2': (1, b'a'), 'k1': (2, b'b')}, {('v', 'p'), ('w', 'q')}, {})
        b = ('x', {'k1': (2, b'b'), 'k2': (1, b'a')}, {('w', 'q'), ('v', 'p')}, {})
        self.assertEqual(split_reuse.canon(a), split_reuse.canon(b))
        self.assertNotEqual(split_reuse.canon(a), split_reuse.canon(('x', {}, set(), {})))


if __name__ == '__main__':
    unittest.main()
