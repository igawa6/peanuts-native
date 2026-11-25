"""dm/peanuts/reuse/rewrite_reuse.py (issue #120): the rewrite step keeps a module whose key moved only with other builds' rows of the profile, and rewrites it on any
change of what its own rewrite reads (its build's patches, its module's build rows, main's fps_decouple rows, its bytes, the rewriter) or of its patched.bin. The
profiles and modules here are small stand-ins (an NSO header with a build ID); no game files. BuildSrc runs the rewriter itself (skipped without Capstone 5)."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(REPO / 'dm/peanuts/reuse'), str(REPO / 'dm/games'), str(REPO / 'dm/tools/tests')]
import rewrite_reuse as rr   # noqa: E402
import game   # noqa: E402
try:
    import capstone
    HAVE_REWRITER = capstone.cs_version()[0] == 5
except ImportError: HAVE_REWRITER = False

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
    FILES = {'main.patched.bin': b'patched main', 'main.fixups.tsv': b'layout\n', 'main.changed.tsv': b'0x10\t1\n'}
    OLD_SRC = b'tools of the rewrite the copy was made with\n'   # build_src.sha256 as that rewrite wrote it

    def made(self, g, main):
        for f, b in self.FILES.items(): (self.out / f).write_bytes(b)
        (self.out / rr.SHARED).write_bytes(self.OLD_SRC)
        (self.out / 'main.android.key').write_text('key-1\n')
        (self.out / 'sdk.patched.bin').write_bytes(b'not main\'s')   # another module's file beside it: not in main's copy
        rr.record('main', main, self.out, g, REWRITER)

    def new_pack(self):
        p = self.d / 'pack2'; p.mkdir(exist_ok=True); return p

    def current_src(self):
        """build_src.sha256 as write_build_src makes it here (BuildSrc: the rewriter's own, byte for byte)"""
        d = self.d / 'src-now'; d.mkdir(exist_ok=True)
        self.assertTrue(rr.write_build_src(d))
        return (d / rr.SHARED).read_bytes()

    def test_a_pack_without_the_module_gets_the_copy(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        p = self.new_pack()
        self.assertTrue(rr.restore('main', main, p, g, 'key-2', REWRITER))
        self.assertEqual({f.name: f.read_bytes() for f in p.iterdir() if f.name not in ('main.android.key', rr.SHARED)}, self.FILES)
        self.assertEqual((p / rr.SHARED).read_bytes(), self.current_src())   # not the copy's: the copy holds none
        self.assertFalse((self.d / 'reuse' / 'rewrite' / 'main.files' / rr.SHARED).exists())
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

    def test_the_shared_file_of_the_pack_is_the_current_one(self):
        # before, a restore left the pack's file as it was, one written by an earlier rewrite of another module
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        p = self.new_pack(); (p / rr.SHARED).write_bytes(b'written by an earlier rewrite of another module\n')
        self.assertTrue(rr.restore('main', main, p, g, 'key-2', REWRITER))
        self.assertEqual((p / rr.SHARED).read_bytes(), self.current_src())

    def test_no_shared_file_no_restore(self):
        # the shared file cannot be made (patch_nso.py's lines not found): the module is rewritten, its files left without a key
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        saved = rr.write_build_src; rr.write_build_src = lambda out: False
        try:
            p = self.new_pack()
            self.assertFalse(rr.restore('main', main, p, g, 'key-2', REWRITER))
            self.assertFalse((p / 'main.android.key').exists())
            self.assertFalse(self.keeps('main', main, g))
            self.assertEqual((self.out / 'main.android.key').read_text(), 'key-1\n')
        finally: rr.write_build_src = saved

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
            self.assertEqual(len(n), len(self.FILES))   # the module's files, not the shared one
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


class Review(Rewrite):
    """igawa6's review of #150 (2026-11-22): PN_REUSE=0 writes nothing, the copies go under the cap when they are made, and a record other rules wrote is not
    trusted"""
    FILES, OLD_SRC, made, new_pack = Restore.FILES, Restore.OLD_SRC, Restore.made, Restore.new_pack

    def other_title(self, title, mb, age):
        """Another title's kept copy of mb MB, age seconds old (only its size and time count)"""
        f = self.d / 'reuse' / '..' / title / 'library' / 'v-aaaaaaaaaaaa' / 'dmsh_vk.lib'
        f = f.resolve(); f.parent.mkdir(parents=True); f.write_bytes(b'\0' * int(mb * (1 << 20)))
        t = os.stat(f).st_mtime - age; os.utime(f, (t, t))
        return f

    def test_reuse_off_writes_no_record_and_no_copy(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        os.environ['PN_REUSE'] = '0'
        self.made(g, main)
        self.assertFalse((self.d / 'reuse').exists())

    def test_the_copy_goes_under_the_cap_of_every_titles_copies(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        (self.d / 'reuse').mkdir()
        oldest, newer = self.other_title('0100000000CC0000', 1.5, 3600), self.other_title('0100000000DD0000', 1.5, 60)
        os.environ['PN_REUSE_KEEP_MB'] = '2'
        self.made(g, main)
        self.assertTrue((self.d / 'reuse' / 'rewrite' / 'main.files' / 'main.patched.bin').is_file())
        self.assertFalse(oldest.exists())   # the title built longest ago made room
        self.assertTrue(newer.is_file())

    def test_a_copy_larger_than_the_cap_is_not_made(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        (self.d / 'reuse').mkdir()
        other = self.other_title('0100000000CC0000', 0.5, 3600)
        os.environ['PN_REUSE_KEEP_MB'] = '0'
        self.made(g, main)
        self.assertFalse((self.d / 'reuse' / 'rewrite' / 'main.files').exists())
        self.assertTrue(other.is_file())   # a copy that is not made takes no other's place
        self.assertFalse(rr.restore('main', main, self.new_pack(), g, 'key-2', REWRITER))
        self.assertTrue(self.keeps('main', main, g))   # the record still keeps it in place

    def test_this_games_split_results_make_room_first(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        split = self.d / 'reuse' / 'split' / 'part' / 'ab' / 'x.pkl'; split.parent.mkdir(parents=True); split.write_bytes(b'\0' * (1 << 20))
        other = self.other_title('0100000000CC0000', 1.5, 3600)
        os.environ['PN_REUSE_KEEP_MB'] = '2'
        self.made(g, main)
        self.assertTrue((self.d / 'reuse' / 'rewrite' / 'main.files' / 'main.patched.bin').is_file())
        self.assertFalse(split.exists())
        self.assertTrue(other.is_file())

    def test_a_record_other_rules_wrote_is_not_trusted(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        self.made(g, main)
        saved = rr.RULES; rr.RULES = 'another app\'s keep rules'
        try:
            self.assertFalse(self.keeps('main', main, g))
            self.assertFalse(rr.restore('main', main, self.new_pack(), g, 'key-2', REWRITER))
        finally: rr.RULES = saved
        self.assertTrue(self.keeps('main', main, g))


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


@unittest.skipUnless(HAVE_REWRITER, 'the rewriter needs Capstone 5')
class BuildSrc(unittest.TestCase):
    """build_src.sha256 (the SHA-256 of the rewriter's files and every patch file) of a pack whose module was kept or restored is the one a rewrite of the same
    inputs writes, byte for byte: patch_nso.py run here on a small module (test_xmod's), with a profile outside the repository (DM_GAMES_DIR), so the file lists
    its patches.py too. Before, a kept module left the file of the rewrite it was made with, and a restore the copy's where the pack had none (igawa6's review
    of #150). Rewrite's helpers, not its tests (each test here runs the rewriter)"""
    OLD_SRC = b'tools of an earlier rewrite\n'
    tearDown, profile, built, src, keeps = Rewrite.tearDown, Rewrite.profile, Rewrite.built, Rewrite.src, Rewrite.keeps

    def setUp(self):
        Rewrite.setUp(self)
        more = self.d / 'more' / '0100000000EE0000'; more.mkdir(parents=True); (more / 'patches.py').write_text('CUTS = {}\n')
        os.environ['DM_GAMES_DIR'] = str(self.d / 'more')
        import test_xmod
        src = self.d / 'rewrite' / 'main'; src.parent.mkdir(); src.write_bytes(test_xmod.module({0x100: 0xD65F03C0}))   # a RET
        r = subprocess.run([sys.executable, str(REPO / 'dm/tools/patch_nso.py'), str(src), str(self.d / 'rewrite' / 'pack'), 'main', '--target', 'android'],
                           capture_output=True, text=True, env=dict(os.environ, DM_REWRITE_J='1', DM_GAME=''))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.want = (self.d / 'rewrite' / 'pack' / rr.SHARED).read_bytes()
        self.assertIn(b'  patch_nso.py\n', self.want)
        self.assertIn(b'/0100000000EE0000/patches.py\n', self.want)

    def test_after_a_keep(self):
        sdk = self.src('sdk', module(SDK))
        (self.out / rr.SHARED).write_bytes(self.OLD_SRC)
        self.built('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK)]))
        self.assertTrue(self.keeps('sdk', sdk, self.profile([('v1', '1.0.0', MAIN_A, SDK), ('v2', '1.0.1', MAIN_B, SDK)])))
        self.assertEqual((self.out / rr.SHARED).read_bytes(), self.want)

    def test_after_a_restore(self):
        main, g = self.src('main', module(MAIN_A)), self.profile([('v1', '1.0.0', MAIN_A, SDK)])
        (self.out / rr.SHARED).write_bytes(self.OLD_SRC)
        self.built('main', main, g)
        for have in (None, self.OLD_SRC):   # a pack without the file, and one with another rewrite's
            with self.subTest(have=have):
                p = self.d / f'pack-{have is None}'; p.mkdir()
                if have: (p / rr.SHARED).write_bytes(have)
                self.assertTrue(rr.restore('main', main, p, g, 'key-2', REWRITER))
                self.assertEqual((p / rr.SHARED).read_bytes(), self.want)


if __name__ == '__main__':
    unittest.main()
