"""PEANUTS (issue #120): the shader split's ooz Oodle decoder and its safety rule (dm/gfx/tools/ooz_split.py, shader_split.py main): which worker is started
(the PC builder's program, the phone's library in a worker process, none), that only the game's own decoder waits for the rewrite beside the split, that ooz's
output is written only when the split's tables are the profile's, and that otherwise the split runs again with the game's own decoder and remembers that.
The phone's wiring (PackService, android/ondevice/build.sh, build.gradle) is read from the sources."""
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS)); sys.path.insert(0, str(REPO / 'dm/tools'))
GAME_ID = '01005270232F2000'   # OCTOPATH TRAVELER 0: a profile with a shader_map.tsv

# a stand-in worker: answers every request with the raw length of b'z' (uepak.OodleHost's protocol)
FAKE = ("import struct, sys\n"
        "i, o = sys.stdin.buffer, sys.stdout.buffer\n"
        "while len(h := i.read(16)) == 16:\n"
        "    c, n = struct.unpack('<QQ', h); i.read(c); o.write(struct.pack('<Q', n) + b'z' * n); o.flush()\n")
ENV = ('DM_OODLE_OOZ', 'DM_OODLE_OOZ_LIB', 'DM_UE_HOST', 'DM_UE_HOST_LIB', 'DM_UE_MODULES', 'DM_OODLE_HOST', 'DM_OODLE_MODULES', 'PN_REUSE_DIR', 'PN_REUSE',
       'DM_SPLIT_DECODER', 'DM_SPLIT_DECODER_OFF', 'DM_OODLE_OOZ_USED', 'PN_REWRITE_BESIDE_DONE', 'DM_GAME', 'DM_PACKER_COMMIT')


class Env(unittest.TestCase):
    def setUp(self):
        self.saved = {k: os.environ.get(k) for k in ENV}
        for k in ENV: os.environ.pop(k, None)
        os.environ['DM_GAME'] = GAME_ID
        self.tmp = Path(tempfile.mkdtemp(prefix='ooz_split_'))
        self.fake = self.tmp / 'fake.py'; self.fake.write_text(f'#!{sys.executable}\n' + FAKE); self.fake.chmod(0o755)

    def tearDown(self):
        for k, v in self.saved.items():
            if v is None: os.environ.pop(k, None)
            else: os.environ[k] = v
        import shutil; shutil.rmtree(self.tmp, ignore_errors=True)


class Worker(Env):
    def test_which_worker(self):
        import ooz_split
        self.assertIsNone(ooz_split.argv())
        os.environ['DM_OODLE_OOZ_LIB'] = 'libunoodle.so'
        self.assertIsNone(ooz_split.argv(), 'the library needs the worker command')
        os.environ['DM_UE_HOST'] = '/system/bin/linker64 /data/x/python/bin/nxpack_worker'
        a = ooz_split.argv()
        self.assertEqual(a[:2], ['/system/bin/linker64', '/data/x/python/bin/nxpack_worker'])
        self.assertEqual(a[-1], 'libunoodle.so'); self.assertIn('unoodle_main', a[-2])
        os.environ['DM_OODLE_OOZ'] = '/pc/unoodle'
        self.assertEqual(ooz_split.argv(), ['/pc/unoodle'], 'the PC builder\'s program wins')
        os.environ['DM_SPLIT_DECODER_OFF'] = '1'
        self.assertIsNone(ooz_split.argv(), 'the rerun takes the game\'s own decoder')

    def test_ooz_never_waits_for_the_rewrite(self):
        """shader_split.oodle_ask with the ooz worker starts it without rewrite_wait.wait(); without it, the game's own decoder waits first"""
        import rewrite_wait, shader_split
        os.environ.update(DM_UE_HOST=sys.executable, DM_UE_HOST_LIB='libgame.so', DM_UE_MODULES=str(self.tmp), DM_OODLE_OOZ=str(self.fake))
        with mock.patch.object(rewrite_wait, 'wait', side_effect=AssertionError('waited')):
            shader_split._OODLE = None
            self.assertEqual(shader_split.oodle_ask(b'abc', 4), (b'zzzz', False))
            self.assertEqual(shader_split._OODLE[0].args, [str(self.fake)])
            shader_split.oodle_close()
        os.environ.pop('DM_OODLE_OOZ')
        with mock.patch.object(rewrite_wait, 'wait', side_effect=RuntimeError('waited')):
            with self.assertRaisesRegex(RuntimeError, 'waited'): shader_split.oodle_ask(b'abc', 4)
        shader_split._OODLE = None

    def test_unreal_ooz_worker_never_waits(self):
        import rewrite_wait, ue_ondevice
        with mock.patch.object(rewrite_wait, 'wait', side_effect=AssertionError('waited')):
            w = ue_ondevice.OozWorker([sys.executable, '-c', FAKE])
            self.assertEqual(w(b'ab', 3), b'zzz'); w.close()
        with mock.patch.object(rewrite_wait, 'wait', side_effect=RuntimeError('waited')):
            with self.assertRaisesRegex(RuntimeError, 'waited'): ue_ondevice.DeviceOodle('x', 'y', 'z')

    def test_used_markers(self):
        import ooz_split
        os.environ.update(DM_OODLE_OOZ=str(self.fake), PN_REUSE_DIR=str(self.tmp / 'reuse'))
        self.assertTrue(ooz_split.begin(str(REPO / f'dm/games/{GAME_ID}/shader_map.tsv'), [], str(self.tmp / 'scan')))
        self.assertEqual(os.environ['DM_SPLIT_DECODER'], 'ooz')
        self.assertFalse(ooz_split.used())
        os.makedirs(self.tmp / 'reuse'); ooz_split.mark()
        self.assertTrue(ooz_split.used())
        marks = os.environ['DM_OODLE_OOZ_USED'].split(ooz_split.LIST_SEP)
        self.assertIn(str(self.tmp / 'scan.ooz-used'), marks); self.assertIn(str(self.tmp / 'reuse/ooz.used'), marks)
        self.assertTrue((self.tmp / 'scan.ooz-used').exists(), 'a resumed split knows its kept results hold ooz output')
        ooz_split.end()
        for k in ('DM_SPLIT_DECODER', 'DM_SPLIT_DECODER_OFF', 'DM_OODLE_OOZ_USED'): self.assertNotIn(k, os.environ, 'the app process\'s environment is left clean')

    def test_no_profile_map_no_ooz(self):
        import ooz_split
        os.environ['DM_OODLE_OOZ'] = str(self.fake)
        self.assertFalse(ooz_split.begin(str(self.tmp / 'none.tsv'), []))
        self.assertIsNone(ooz_split.argv())

    def test_scan_results_kept_apart(self):
        import shader_split
        a = shader_split.scan_digest(['x'], [], sources=[])
        os.environ['DM_SPLIT_DECODER'] = 'ooz'
        self.assertNotEqual(shader_split.scan_digest(['x'], [], sources=[]), a)
        self.assertIn("'DM_SPLIT_DECODER'", (TOOLS / 'shader_split.py').read_text(encoding='utf-8'))
        self.assertIn("k.startswith('DM_SPLIT_')", (REPO / 'dm/peanuts/reuse/split_reuse.py').read_text(encoding='utf-8'), 'split_reuse keys every DM_SPLIT_ variable')
        self.assertIn("'decoder\\tooz\\n' if ooz", (TOOLS / 'ue_ondevice.py').read_text(encoding='utf-8'), 'archives ooz decoded are not reused by the game decoder\'s split')


class Fallback(Env):
    """shader_split.main with split_once, the comparison and the writing stood in for"""
    def run_main(self, results, used=True, decoder=True, differs=None):
        import shader_split, ooz_split
        os.environ['DM_OODLE_OOZ'] = str(self.fake)
        os.environ['PN_REUSE_DIR'] = str(self.tmp / 'reuse'); os.makedirs(self.tmp / 'reuse', exist_ok=True)
        if decoder: os.environ.update(DM_UE_HOST=sys.executable, DM_UE_HOST_LIB='libgame.so', DM_UE_MODULES=str(self.tmp))
        calls, saved = [], []
        def once():
            calls.append((ooz_split.argv(), os.environ.get('DM_SPLIT_DECODER')))
            if used and ooz_split.argv(): ooz_split.mark()
            r = results[len(calls) - 1]
            if isinstance(r, BaseException): raise r
            return r, ('pkg', 'm', 'f', 'p', 'o')
        with mock.patch.object(shader_split, 'split_once', once), mock.patch.object(shader_split, 'save', lambda out, paths: saved.append(out)), \
             mock.patch.object(shader_split, 'profile_differs', lambda out, *a: differs(out) if differs else None), \
             mock.patch.object(sys, 'argv', ['shader_split.py', 'romfs', 'main', 'pkg', 'm', 'f', 'p', 'o']):
            shader_split.main()
        return calls, saved

    def test_profile_tables_ooz_output_kept(self):
        calls, saved = self.run_main(['ooz'])
        self.assertEqual(len(calls), 1); self.assertEqual(calls[0][1], 'ooz'); self.assertEqual(saved, ['ooz'])
        self.assertFalse((self.tmp / 'reuse/ooz.refused').exists())
        self.assertNotIn('DM_SPLIT_DECODER', os.environ)

    def test_other_tables_game_decoder_again(self):
        calls, saved = self.run_main(['ooz', 'game'], differs=lambda out: 'made a shader_map.tsv that differs' if out == 'ooz' else None)
        self.assertEqual(len(calls), 2)
        self.assertIsNotNone(calls[0][0]); self.assertIsNone(calls[1][0], 'the rerun uses the game\'s own decoder'); self.assertIsNone(calls[1][1])
        self.assertEqual(saved, ['game'], 'ooz\'s output is never written')
        self.assertTrue((self.tmp / 'reuse/ooz.refused').exists())
        # the next split of the same files goes to the game's decoder at once
        calls, saved = self.run_main(['game'])
        self.assertEqual(len(calls), 1); self.assertIsNone(calls[0][0])

    def test_a_split_that_stops_with_ooz_runs_again(self):
        import shader_split
        for e in (SystemExit('[RED] the ooz worker failed'), ValueError('bad stream'), shader_split.OozDiffers('the targeted scan made a shader_map.tsv that differs')):
            calls, saved = self.run_main([e, 'game'])
            self.assertEqual(len(calls), 2, e); self.assertEqual(saved, ['game'])
            os.unlink(self.tmp / 'reuse/ooz.refused'); os.unlink(self.tmp / 'reuse/ooz.used')

    def test_an_interrupt_is_not_a_fallback(self):
        with self.assertRaises(KeyboardInterrupt): self.run_main([KeyboardInterrupt()])

    def test_ooz_not_used_no_rerun(self):
        calls, saved = self.run_main(['plain'], used=False, differs=lambda out: 'differs')
        self.assertEqual(len(calls), 1); self.assertEqual(saved, ['plain'])
        with self.assertRaises(SystemExit): self.run_main([SystemExit('[RED] x')], used=False)

    def test_pc_without_a_game_decoder_writes_as_before(self):
        """The PC builder: no game decoder to fall back to; pack_android.py ooz_split_checked refuses tables that are not the profile's"""
        calls, saved = self.run_main(['ooz'], decoder=False, differs=lambda out: 'differs')
        self.assertEqual(len(calls), 1); self.assertEqual(saved, ['ooz'])
        self.assertIn('ooz_split_checked(ooz, g, tmp, ver)', (REPO / 'scripts/pack_android.py').read_text(encoding='utf-8'))


class Phone(unittest.TestCase):
    """The phone's wiring, from the sources"""
    def test_pack_service_sets_the_library(self):
        s = (REPO / 'android/app/src/ondevice/java/dev/nxnative/android/PackService.java').read_text(encoding='utf-8')
        self.assertIn('findLibrary("unoodle")', s)
        self.assertIn('PackEngine.setenv("DM_OODLE_OOZ_LIB", ooz == null ? "" : "libunoodle.so");', s)
        self.assertLess(s.index('"DM_OODLE_OOZ_LIB"'), s.index('File envFile = new File(od, "env.txt");'), 'env.txt can turn it off')
        self.assertIn('PackEngine.setenv("DM_UE_HOST"', s)

    def test_stage_and_apk(self):
        b = (REPO / 'android/ondevice/build.sh').read_text(encoding='utf-8')
        self.assertIn('dm/third_party/ooz/build.sh $S/lib/libunoodle.so $BIN/clang++ --target=aarch64-linux-android33', b)
        self.assertIn('grep -qaF "$HOME" $S/lib/libunoodle.so', b)
        g = (REPO / 'android/app/build.gradle').read_text(encoding='utf-8')
        self.assertIn("need += ['lib/libunoodle.so']", g)
        self.assertIn("'libunoodle.so' }", g); self.assertIn('unoodleCheck   //', g)
        order = "['kraken.cpp', 'stdafx.h', 'unoodle.cpp', 'ooz-port.patch', 'ooz-guards.patch', 'ooz-arm64.patch', 'ooz_neon.h', 'build.sh']"
        self.assertIn(order, g)
        sh = (REPO / 'dm/third_party/ooz/build.sh').read_text(encoding='utf-8')
        self.assertIn('SOURCES="' + ' '.join(eval(order)) + '"', sh, 'build.gradle hashes the sources in build.sh\'s order')
        self.assertIn('-fno-strict-overflow', sh, 'Leviathan on arm64 (PR #889)')


if __name__ == '__main__':
    unittest.main()
