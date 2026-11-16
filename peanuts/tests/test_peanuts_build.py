#!/usr/bin/env python3
"""peanuts/scripts/peanuts_build.py and push-pack.py: the environment's .NET, the games refused up front, --nup as a folder, the icon beside the .nup, the one
default folder on the device, the device paths push-pack.py refuses, and the scripts' syntax (Python 3.9's; nup-extract.py too)"""
import contextlib, importlib.util, io, os, sys, tempfile, tokenize, unittest
from collections import namedtuple
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / 'peanuts' / 'scripts'


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / file)
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m


pb = load('peanuts_build', 'peanuts_build.py')
pp = load('push_pack', 'push-pack.py')
TITLE = '01006BB00C6F0000'


class Build(unittest.TestCase):
    def test_dotnet(self):
        with tempfile.TemporaryDirectory() as d:
            sub = ('dotnet',) if pb.WINDOWS else ('lib', 'dotnet')
            f = Path(d, *sub, 'dotnet.exe' if pb.WINDOWS else 'dotnet'); f.parent.mkdir(parents=True); f.write_text('')
            e = pb.with_dotnet({'PATH': 'x'}, d)
            self.assertEqual(e['DOTNET_ROOT'], str(f.parent)); self.assertTrue(e['PATH'].startswith(str(f.parent)))
            self.assertEqual(pb.with_dotnet({'DOTNET_ROOT': 'mine'}, d)['DOTNET_ROOT'], 'mine')
            self.assertNotIn('DOTNET_ROOT', pb.with_dotnet({}, d + '-none'))

    def test_refuse(self):
        """A profile with /glslc_capture/ or /ue_shaders/ rows is refused; one without is not. The profiles are made here, so the test does not depend on a game's"""
        real, env = pb.REPO, {k: os.environ.pop(k, None) for k in ('DM_GLSLC_CAPTURE', 'DM_UE_SHADERS')}
        try:
            with tempfile.TemporaryDirectory() as d:
                pb.REPO = Path(d)
                rows = {'0100000000000001': 'a\tromfs/x/glslc_capture/1.glslc\n', '0100000000000002': 'a\tromfs/x/ue_shaders/1.ushaderbytecode\n', '0100000000000003': 'a\tromfs/x.bfres\n'}
                for app, row in rows.items():
                    g = Path(d, 'dm', 'games', app); g.mkdir(parents=True); (g / 'shader_map.tsv').write_text(row)
                for app in ('0100000000000001', '0100000000000002'):
                    with self.assertRaises(SystemExit) as c: pb.check_device_steps(app, 'Game')
                    self.assertIn('Build in the Peanuts app', str(c.exception)); self.assertTrue(str(c.exception).startswith('[RED] '))
                pb.check_device_steps('0100000000000003')   # no such rows
                pb.check_device_steps('0100000000000004')   # no profile
                caps = Path(d, 'caps'); caps.mkdir(); (caps / '1.glslc').write_text('x')
                os.environ['DM_GLSLC_CAPTURE'] = str(caps)
                pb.check_device_steps('0100000000000001')   # the files are given: not refused
        finally:
            pb.REPO = real
            for k, v in env.items():
                os.environ.pop(k, None)
                if v is not None: os.environ[k] = v

    def test_refuse_real_profiles(self):
        """The two games the guide names, while this tree has their profiles"""
        for app, marker in (('010055D009F78000', '/glslc_capture/'), ('01005270232F2000', '/ue_shaders/')):
            tsv = REPO / 'dm' / 'games' / app / 'shader_map.tsv'
            if not tsv.is_file() or marker not in tsv.read_text(encoding='utf-8', errors='replace'): continue
            if os.environ.get('DM_GLSLC_CAPTURE') or os.environ.get('DM_UE_SHADERS'): continue
            with self.assertRaises(SystemExit): pb.check_device_steps(app, 'Game')

    def test_nup_folder(self):
        Plan = namedtuple('Plan', 'name app version')
        plan = Plan('Metroid Dread', '010093801237C000', 131328)
        with tempfile.TemporaryDirectory() as d:
            A = namedtuple('A', 'nup')
            self.assertEqual(pb.nup_path(A(d), plan, Path('/x/a.nsp')), Path(d).resolve() / 'Metroid Dread [010093801237C000][v131328].nup')
            self.assertEqual(pb.nup_path(A(d + '/f.nup'), plan, Path('/x/a.nsp')), Path(d).resolve() / 'f.nup')

    def test_icon(self):
        Plan = namedtuple('Plan', 'name app version base_file update_file dlc_files')
        self.assertEqual(pb.icon_path(Path('/x/Game [0100][v1].nup')), Path('/x/Game [0100][v1].png'))
        with tempfile.TemporaryDirectory() as d:
            base = Path(d, 'a.nsp'); base.write_text('x')
            nup = Path(d, 'Game [0100][v1].nup'); nup.write_text('n')
            plan = Plan('Game', '0100', 1, base, None, [])
            calls = []
            def fail(cmd, dry, what, capture=False): calls.append(cmd); return 1, ''
            def ok(cmd, dry, what, capture=False): calls.append(cmd); Path(cmd[-1]).write_bytes(b'png'); return 0, ''
            real = pb.run
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    pb.run = fail
                    pb.write_icon('imp', 'k', nup, plan, False, False)   # failure: a warning, no exception, no file
                    self.assertFalse(Path(d, 'Game [0100][v1].png').exists()); self.assertFalse(Path(d, 'Game [0100][v1].png.tmp').exists())
                    pb.run = ok
                    pb.write_icon('imp', 'k', nup, plan, False, False)
                    png = Path(d, 'Game [0100][v1].png')
                    self.assertEqual(png.read_bytes(), b'png')
                    self.assertEqual(calls[-1][:6], ['imp', '--keys', 'k', 'icon', str(base), '0100'])
                    n = len(calls)
                    os.utime(png, (png.stat().st_mtime + 100,) * 2)
                    pb.write_icon('imp', 'k', nup, plan, False, False)   # up to date: reused
                    self.assertEqual(len(calls), n)
                    pb.write_icon('imp', 'k', nup, plan, False, True)    # --force-nup: again
                    self.assertEqual(len(calls), n + 1)
                    # base fails, the update serves
                    png.unlink(); upd = Path(d, 'u.nsp'); upd.write_text('u')
                    plan2 = plan._replace(update_file=upd)
                    def second(cmd, dry, what, capture=False):
                        if cmd[4] == str(base): return 1, ''
                        return ok(cmd, dry, what)
                    pb.run = second
                    pb.write_icon('imp', 'k', nup, plan2, False, False)
                    self.assertTrue(png.is_file())
                    self.assertEqual(calls[-1][4:6], [str(upd), '-'])
            finally: pb.run = real

    def test_probe_lines(self):
        """The lines nximport_cli probe prints (nximport_cli.c), as the builder reads them"""
        m = pb.PROBE_FILE.match('/x/Game [0100].nsp  (2 titles, 0.31s)')
        self.assertEqual((m.group('path'), m.group('n')), ('/x/Game [0100].nsp', '2'))
        self.assertTrue(pb.PROBE_FILE.match('C:\\g\\a.xci  (1 title, 0.02s)'))
        m = pb.PROBE_TITLE.match('  title 01006bb00c6f0800  app 01006bb00c6f0000  kind update  version 131072  name "A "quoted" name"  publisher "P"')
        self.assertEqual((m.group('title'), m.group('app'), m.group('kind'), m.group('version'), m.group('name')),
                         ('01006bb00c6f0800', '01006bb00c6f0000', 'update', '131072', 'A "quoted" name'))

    def test_pack_done_line(self):
        """pack_android.py's last line, in both of its forms"""
        self.assertEqual(pb.PACK_DONE.match('done: /w/0100-work/android-pack (151.2 MiB); RomFS x and 0 DLC stay in the game folder').group('path'), '/w/0100-work/android-pack')
        self.assertEqual(pb.PACK_DONE.match('done: the modules in /w/a pack (--code-only: pack.tsv and the shaders unchanged)').group('path'), '/w/a pack')

    def test_device_folder(self):
        self.assertEqual(pb.DEVICE_ROOT, pp.DEVICE_ROOT)   # one default folder on the device in both scripts
        self.assertEqual(pb.device_folder("Zelda - Link's Awakening [01006BB00C6F0000][v131072]"), pb.DEVICE_ROOT + "/Zelda - Link's Awakening")
        self.assertEqual(pb.device_folder('[01006BB00C6F0000][v0]'), pb.DEVICE_ROOT + '/[01006BB00C6F0000][v0]')   # nothing but tags: the tags stay
        self.assertEqual(pb.device_folder('.. [01006BB00C6F0000]'), pb.DEVICE_ROOT + '/.. [01006BB00C6F0000]')    # never a '..' folder


class Push(unittest.TestCase):
    def pack(self, d):
        p = Path(d, TITLE + '-work', 'android-pack'); p.mkdir(parents=True); (p / 'pack.tsv').write_text(f'title_id\t{TITLE}\n')
        return p

    def dry(self, *argv):
        """push-pack.py --dry-run with a serial (adb is not started): the adb commands it prints"""
        out = io.StringIO()
        with contextlib.redirect_stdout(out): pp.main([*argv, '--serial', 'S', '--dry-run'])
        return [l[2:] for l in out.getvalue().splitlines() if l.startswith('+ ')]

    def test_device_path(self):
        self.assertEqual(pp.device_path('/sdcard/Peanut Roms/x'), '/sdcard/Peanut Roms/x')
        self.assertEqual(pp.device_path('/sdcard/x', depth=2), '/sdcard/x')
        for bad in ('', '/', '/sdcard', '/sdcard/x', 'sdcard/a/b/c', '/sdcard/a/../b', '/sdcard/a/./b', '/sdcard//a/b', '/sdcard/a/b/', '-rf/a/b', '/sdcard/a/b\nc'):
            with self.assertRaises(SystemExit, msg=bad): pp.device_path(bad)

    def test_default_dest(self):
        with tempfile.TemporaryDirectory() as d:
            cmds = self.dry(str(self.pack(d)))
        work = f'/sdcard/Peanut Roms/{TITLE}-work/android-pack'
        self.assertIn(f"adb -s S shell 'rm -rf '\"'\"'{work}.tmp'\"'\"''", cmds)   # the path is one quoted word for the device's shell
        removed = [c for c in cmds if 'rm -' in c]
        self.assertEqual(len(removed), 2)
        for c in removed: self.assertIn(work, c)   # nothing but the pack folder and its .tmp is removed

    def test_refused_dest_and_version(self):
        with tempfile.TemporaryDirectory() as d:
            pack = self.pack(d)
            game = Path(d, 'Game'); (game / 'base' / 'exefs').mkdir(parents=True); (game / 'updates' / '65536').mkdir(parents=True)
            for dest in ('', '/', 'sdcard/x', '/sdcard/../data', '/sdcard/./x', '/sdcard//x'):
                with self.assertRaises(SystemExit, msg=dest): self.dry(str(pack), '--dest', dest)
            for ver in ('..', '65536/..', '', 'v65536'):
                with self.assertRaises(SystemExit, msg=ver): self.dry(str(pack), '--game', str(game), '--version', ver)
            cmds = self.dry(str(pack), '--game', str(game), '--dest', '/sdcard/SW2')
            self.assertTrue(any(c.endswith("/sdcard/SW2/Game/updates/65536.tmp /sdcard/SW2/Game/updates/65536'") for c in cmds), cmds)

    def test_nup_and_icon(self):
        with tempfile.TemporaryDirectory() as d:
            pack = self.pack(d)
            nup = Path(d, f'Game [{TITLE}][v0].nup'); nup.write_text('n'); nup.with_suffix('.png').write_bytes(b'\x89PNG\r\n\x1a\n')
            cmds = self.dry(str(pack), '--game', str(nup), '--dest', '/sdcard/Peanut Roms/Game')
        self.assertTrue(any(c.startswith('adb -s S push ') and c.endswith(f"'/sdcard/Peanut Roms/Game/Game [{TITLE}][v0].nup.tmp'") for c in cmds), cmds)
        self.assertTrue(any(c.startswith('adb -s S push ') and c.endswith(f"'/sdcard/Peanut Roms/Game/Game [{TITLE}][v0].png'") for c in cmds), cmds)


class Syntax(unittest.TestCase):
    @unittest.skipIf(sys.version_info < (3, 12), 'the tokenizer splits f-strings into tokens from Python 3.12 on')
    def test_python39(self):
        """What Python 3.12 allows inside an f-string and 3.9 does not: a backslash in a replacement field, and a string in a field that reuses the f-string's quote.
        ast.parse(feature_version=(3, 9)) covers the grammar (match, parenthesised with items, except*)"""
        import ast
        for name in ('peanuts_build.py', 'push-pack.py', 'nup-extract.py'):
            src = (SCRIPTS / name).read_text(encoding='utf-8')
            ast.parse(src, name, feature_version=(3, 9))
            open_quotes = []
            for tok in tokenize.generate_tokens(io.StringIO(src).readline):
                where = f'{name}:{tok.start[0]}'
                if tok.type == tokenize.FSTRING_START:
                    q = tok.string.lstrip('rRfFbBuU')
                    self.assertFalse(open_quotes, f'{where}: an f-string inside an f-string field')
                    open_quotes.append(q)
                elif tok.type == tokenize.FSTRING_END: open_quotes.pop()
                elif open_quotes and tok.type != tokenize.FSTRING_MIDDLE:
                    self.assertNotIn('\\', tok.string, f'{where}: a backslash inside an f-string field')
                    self.assertNotIn('\n', tok.string, f'{where}: a line break inside an f-string field')
                    if tok.type == tokenize.STRING: self.assertNotIn(open_quotes[-1][0], tok.string, f'{where}: the f-string\'s own quote inside its field')


if __name__ == '__main__': unittest.main()
