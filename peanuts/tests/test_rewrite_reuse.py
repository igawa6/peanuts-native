"""dm/peanuts/reuse/rewrite_reuse.py (issue #120): the rewrite step keeps a module whose key moved only with other builds' rows of the profile, and rewrites it on any
change of what its own rewrite reads (its build's patches, its module's build rows, main's fps_decouple rows, its bytes, the rewriter) or of its patched.bin. The
profiles and modules here are small stand-ins (an NSO header with a build ID); no game files."""
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(REPO / 'dm/peanuts/reuse'), str(REPO / 'dm/games')]
import rewrite_reuse as rr   # noqa: E402
import game   # noqa: E402

REWRITER = ('patch_nso.py', 'nso.py')
SDK, MAIN_A, MAIN_B = 'aa' * 16, 'b1' * 16, 'b2' * 16


def module(bid, body=b'code'):
    h = bytearray(0x100); h[:4] = b'NSO0'; h[0x40:0x50] = bytes.fromhex(bid[:32]); return bytes(h) + body


class Rewrite(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp(prefix='pnrw_'))
        self.prof = self.d / 'games' / '0100000000BB0000'; self.prof.mkdir(parents=True)
        self.out = self.d / 'pack'; self.out.mkdir()
        self.saved = dict(os.environ); os.environ['PN_REUSE_DIR'] = str(self.d / 'reuse')
        rr._LOADED.clear()

    def tearDown(self):
        os.environ.clear(); os.environ.update(self.saved)
        shutil.rmtree(self.d, ignore_errors=True)

    def profile(self, rows, patches=None, fps=None):
        """game.tsv with build rows [(version, display, main ID, sdk ID)]; patches.py text (None: no file); fps_decouple value"""
        t = 'title_id\t0100000000BB0000\n' + ''.join(f'build\t{v}\t{d}\tmain={m}\tsdk={s}\n' for v, d, m, s in rows) + (f'fps_decouple\t{fps}\n' if fps else '')
        (self.prof / 'game.tsv').write_text(t)
        p = self.prof / 'patches.py'
        if patches is None: p.unlink(missing_ok=True)
        else: p.write_text(patches)
        rr._LOADED.pop(os.path.realpath(p), None)
        return game.Game(self.prof)

    def built(self, name, src, g, key='key-1', binary=b'patched'):
        """A module the rewrite made: its files and key in the pack, and its record"""
        (self.out / f'{name}.patched.bin').write_bytes(binary)
        (self.out / f'{name}.android.key').write_text(key + '\n')
        rr.record(name, src, self.out, g, REWRITER)

    def src(self, name, b):
        p = self.d / 'src' / name; p.parent.mkdir(exist_ok=True); p.write_bytes(b); return p

    def keeps(self, name, src, g):
        return rr.keep(name, src, self.out, g, 'key-2', REWRITER)

    def test_a_new_version_row_keeps_the_unchanged_sdk(self):
        sdk = self.src('sdk', module(SDK))
        self.built('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)]))
        self.assertTrue(self.keeps('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK), ('v2', '1.0.1', MAIN_B, SDK)])))
        self.assertEqual((self.out / 'sdk.android.key').read_text(), 'key-2\n')

    def test_main_is_rewritten_when_another_main_build_is_added(self):
        main = self.src('main', module(MAIN_A))
        self.built('main', main, self.profile([('v1', '1.0.0', MAIN_A, SDK)]))
        self.assertFalse(self.keeps('main', main, self.profile([('v1', '1.0.0', MAIN_A, SDK), ('v2', '1.0.1', MAIN_B, SDK)])))
        self.assertEqual((self.out / 'main.android.key').read_text(), 'key-1\n')

    def test_patches_for_this_build_rewrite_it(self):
        sdk = self.src('sdk', module(SDK))
        self.built('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)], patches='CUTS = {}\n'))
        self.assertTrue(self.keeps('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)], patches=f'CUTS = {{{MAIN_B!r}: []}}\n')))   # another build's cuts: kept
        self.built('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)], patches='CUTS = {}\n'))
        self.assertFalse(self.keeps('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)], patches=f'CUTS = {{{SDK[:32]!r}: [(0, "00", "00", "x")]}}\n')))

    def test_another_profiles_patches_for_this_build_count(self):
        # patch_nso.py merges CUTS by build ID from every patch file: a module whose build another profile registers follows that file too
        os.environ['DM_GAMES_DIR'] = str(self.d / 'games')
        other = self.d / 'games' / '0100000000CC0000'; other.mkdir()
        (other / 'patches.py').write_text(f'CUTS = {{{SDK!r}: []}}\n')
        sdk = self.src('sdk', module(SDK))
        self.built('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)]))
        self.assertTrue(self.keeps('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK), ('v2', '1.0.1', MAIN_B, SDK)])))
        self.built('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)]))
        (other / 'patches.py').write_text(f'CUTS = {{{SDK!r}: [(0, "00", "00", "x")]}}\n'); rr._LOADED.clear()
        self.assertFalse(self.keeps('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK), ('v2', '1.0.1', MAIN_B, SDK)])))

    def test_match_cuts_of_the_module_rewrite_it(self):
        sdk = self.src('sdk', module(SDK))
        self.built('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)], patches='MATCH_CUTS = {}\n'))
        self.assertFalse(self.keeps('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)], patches="MATCH_CUTS = {'sdk': [{'rule': 1}]}\n")))

    def test_its_bytes_its_patched_bin_and_fps_rows(self):
        main = self.src('main', module(MAIN_A))
        g = self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.built('main', main, g)
        self.assertFalse(self.keeps('main', self.src('main2', module(MAIN_A, b'other code')), g))
        (self.out / 'main.patched.bin').write_bytes(b'changed')
        self.assertFalse(self.keeps('main', main, g))
        self.built('main', main, g)
        self.assertFalse(self.keeps('main', main, self.profile([('v1', '1.0.0', MAIN_A, SDK)], fps='sead')))
        self.assertTrue(self.keeps('main', main, self.profile([('v1', '1.0.0', MAIN_A, SDK)])))

    def test_no_record_no_folder_other_key(self):
        sdk = self.src('sdk', module(SDK))
        g = self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        (self.out / 'sdk.patched.bin').write_bytes(b'p'); (self.out / 'sdk.android.key').write_text('key-1\n')
        self.assertFalse(self.keeps('sdk', sdk, g))   # no record
        self.built('sdk', sdk, g)
        (self.out / 'sdk.android.key').write_text('key-0\n')   # the pack's key is not the recorded one
        self.assertFalse(self.keeps('sdk', sdk, g))
        self.built('sdk', sdk, g)
        os.environ['PN_REUSE'] = '0'   # env.txt: off
        self.assertFalse(self.keeps('sdk', sdk, g))
        del os.environ['PN_REUSE']
        self.assertTrue(self.keeps('sdk', sdk, g))
        self.built('sdk', sdk, g)
        del os.environ['PN_REUSE_DIR']
        self.assertFalse(self.keeps('sdk', sdk, g))


class Restore(Rewrite):
    """The copy of a module's files its record keeps (<PN_REUSE_DIR>/rewrite/<module>.files): a pack without the module (another Build folder, a build deleted)
    gets them back instead of a rewrite, when the module's part and every file are the recorded ones"""
    FILES = {'main.patched.bin': b'patched main', 'main.fixups.tsv': b'layout\n', 'main.changed.tsv': b'0x10\t1\n', 'build_src.sha256': b'tools\n'}

    def made(self, g, main):
        for f, b in self.FILES.items(): (self.out / f).write_bytes(b)
        (self.out / 'main.android.key').write_text('key-1\n')
        (self.out / 'sdk.patched.bin').write_bytes(b'not main\'s')   # another module's file beside it: not in main's copy
        rr.record('main', main, self.out, g, REWRITER)

    def new_pack(self):
        p = self.d / 'pack2'; p.mkdir(exist_ok=True); return p

    def test_a_pack_without_the_module_gets_the_copy(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        p = self.new_pack()
        self.assertTrue(rr.restore('main', main, p, g, 'key-2', REWRITER))
        self.assertEqual({f.name: f.read_bytes() for f in p.iterdir() if f.name != 'main.android.key'}, self.FILES)
        self.assertEqual((p / 'main.android.key').read_text(), 'key-2\n')   # the current key, written last
        self.assertFalse((p / 'sdk.patched.bin').exists())

    def test_another_part_is_rewritten(self):
        main = self.src('main', module(MAIN_A))
        self.made(self.profile([('v1', '1.0.0', MAIN_A, SDK)]), main)
        g = self.profile([('v1', '1.0.0', MAIN_A, SDK)], patches=f'CUTS = {{{MAIN_A[:32]!r}: [(0, "00", "00", "x")]}}\n')   # cuts for this build now
        p = self.new_pack()
        self.assertFalse(rr.restore('main', main, p, g, 'key-2', REWRITER))
        self.assertEqual(list(p.iterdir()), [])
        self.assertFalse(rr.restore('main', self.src('main2', module(MAIN_A, b'other code')), p, self.profile([('v1', '1.0.0', MAIN_A, SDK)]), 'key-2', REWRITER))

    def test_a_copy_that_changed_is_not_taken(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        (self.d / 'reuse' / 'rewrite' / 'main.files' / 'main.fixups.tsv').write_bytes(b'damaged\n')
        p = self.new_pack()
        self.assertFalse(rr.restore('main', main, p, g, 'key-2', REWRITER))
        self.assertFalse((p / 'main.android.key').exists())

    def test_only_into_a_pack_without_the_module(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        self.assertFalse(rr.restore('main', main, self.out, g, 'key-2', REWRITER))   # the pack has it: keep() decides there
        p = self.new_pack(); (p / 'main.patched.bin').write_bytes(b'a cut rewrite')   # a rewrite stopped part way: no key, rewritten
        self.assertFalse(rr.restore('main', main, p, g, 'key-2', REWRITER))

    def test_the_shared_file_of_the_pack_stays(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        p = self.new_pack(); (p / 'build_src.sha256').write_bytes(b'written by another module\'s rewrite\n')
        self.assertTrue(rr.restore('main', main, p, g, 'key-2', REWRITER))
        self.assertEqual((p / 'build_src.sha256').read_bytes(), b'written by another module\'s rewrite\n')

    def test_off_and_no_folder(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        os.environ['PN_REUSE'] = '0'
        self.assertFalse(rr.restore('main', main, self.new_pack(), g, 'key-2', REWRITER))
        del os.environ['PN_REUSE']; del os.environ['PN_REUSE_DIR']
        self.assertFalse(rr.restore('main', main, self.new_pack(), g, 'key-2', REWRITER))

    def test_the_same_files_are_copied_once(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        saved, n = rr.shutil.copyfile, []
        rr.shutil.copyfile = lambda a, b: (n.append(b), saved(a, b))[1]
        try:
            rr.record('main', main, self.out, g, REWRITER)   # a later build that kept the module: its files as they were
            self.assertEqual(n, [])
            (self.out / 'main.patched.bin').write_bytes(b'rewritten again')
            rr.record('main', main, self.out, g, REWRITER)
            self.assertEqual(len(n), len(self.FILES))
        finally: rr.shutil.copyfile = saved
        self.assertEqual((self.d / 'reuse' / 'rewrite' / 'main.files' / 'main.patched.bin').read_bytes(), b'rewritten again')

    def test_no_copy_when_storage_is_low(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        saved = rr.KEEP_FREE_MB; rr.KEEP_FREE_MB = 1 << 40
        try: self.made(g, main)
        finally: rr.KEEP_FREE_MB = saved
        self.assertFalse((self.d / 'reuse' / 'rewrite' / 'main.files').exists())
        self.assertFalse(rr.restore('main', main, self.new_pack(), g, 'key-2', REWRITER))
        self.assertTrue(self.keeps('main', main, g))   # the record still keeps it in place


class Tables(Rewrite):
    """The module's tables besides patched.bin: a kept or restored module must have the ones a rewrite from nothing makes (found in review)"""
    def test_the_cut_count_of_another_build_of_the_module(self):
        # main's build MAIN_B is in no build row and no patch file: patch_nso.py writes, for each build row's main build with CUTS (MAIN_A), its cut count
        # into main.match.tsv. A fix to MAIN_A's cuts changes that file but not main's own rows
        src = self.src('main', module(MAIN_B))
        cut = '(0x100, "00000000", "1f2003d5", "a fix")'
        self.built('main', src, self.profile([('v1', '1.0.0', MAIN_A, SDK)], f'CUTS = {{{MAIN_A!r}: [{cut}]}}\n'))
        self.assertFalse(self.keeps('main', src, self.profile([('v1', '1.0.0', MAIN_A, SDK)], f'CUTS = {{{MAIN_A!r}: [{cut}, {cut.replace("0x100", "0x104")}]}}\n')))

    def test_a_cross_module_table_is_never_copied(self):
        # cross_module writes <module>.xmod.tsv after the rewrites, for a build with code mods (whose modules are never recorded); one left in the pack must not
        # reach another pack, where the loader would apply it to a module rewritten without it
        src = self.src('main', module(MAIN_A))
        g = self.profile([('v1', '1.0.0', MAIN_A, SDK)], f'CUTS = {{{MAIN_A!r}: []}}\n')
        (self.out / 'main.xmod.tsv').write_text('left by a build with mods\n')
        self.built('main', src, g)
        other = self.d / 'other-build-folder'; other.mkdir()
        self.assertTrue(rr.restore('main', src, other, g, 'key-2', REWRITER))
        self.assertFalse((other / 'main.xmod.tsv').exists())

    def test_a_match_table_of_an_earlier_build_is_not_copied(self):
        # a registered build gets no main.match.tsv from patch_nso.py: one in the pack is an earlier, unregistered build's
        src = self.src('main', module(MAIN_A))
        g = self.profile([('v1', '1.0.0', MAIN_A, SDK)], f'CUTS = {{{MAIN_A!r}: []}}\n')
        (self.out / 'main.match.tsv').write_text(f'# main {MAIN_B}: not a registered build\n')
        self.built('main', src, g)
        other = self.d / 'other-build-folder'; other.mkdir()
        self.assertTrue(rr.restore('main', src, other, g, 'key-2', REWRITER))
        self.assertFalse((other / 'main.match.tsv').exists())


if __name__ == '__main__':
    unittest.main()
