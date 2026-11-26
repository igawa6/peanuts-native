"""The Android launcher's save backup / restore (SaveStore.java: verified copies of the save and its device save, the automatic backup before a restore, the lock, the
roll-forward journal it shares with the host's plat_rename_swap) and its game table (Profile.java), on the JVM: javac compiles them with a stub BuildConfig and
android/app/src/jvmtest's LauncherJvmTest. Also: the lock held by another process, and the journal's recovery rules checked against the host's own plat_swap_recover
(PEANUTS: its swap code cut out of dm/host/plat/plat_android.c and compiled for the local POSIX host), case by case, so the two sides keep one format and one set of rules.
Skipped without a JDK (the C half: without cc)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/LauncherJvmTest.java'
RES = REPO / 'android/app/src/main/res/values/strings.xml'   # the labels LauncherJvmTest checks (Profile.renderOutLabel)
PLAT = REPO / 'dm/host/plat/plat_android.c'
STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa";
}
'''
# PEANUTS(upstream-candidate): glibc exposes nftw and FTW_DEPTH/FTW_PHYS with XSI declarations enabled.
HARNESS = '''#define _XOPEN_SOURCE 700
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#define LOG(...) (printf(__VA_ARGS__), printf("\\n"))
static void swap_dir_meta(const char *a, const char *b) { (void)a; (void)b; }
%s
int main(int argc, char **argv) { if (argc == 4 && !strcmp(argv[1], "swap")) { int r = plat_rename_swap(argv[2], argv[3]); return r == -2 ? 2 : r ? 1 : 0; } plat_swap_recover(argv[1]); return 0; }
'''
# PEANUTS: swap_dir_meta (Linux xattrs) is stubbed; plat_rename_swap's -2 (failed after its marker) exits 2


def swap_code():
    """plat_android.c's journal: swap_rm_cb … plat_swap_recover, plat_rename_swap included (it makes no Linux-only call). macOS's nftw refuses a file as its root
    (ENOTDIR), which bionic's and glibc's take, so on the Mac swap_rm_tree removes a file itself"""
    s = PLAT.read_text()
    a = s.index('static int swap_rm_cb'); b = s.index('// Linux keeps an application')
    code = s[a:b]   # PEANUTS: plat_rename_swap included (#485)
    assert 'RENAME_EXCHANGE */' not in code, 'a commit keeps the save folder itself (#485): no directory exchange'
    code = 'int plat_rename_swap(const char *a, const char *b);\n' + code
    rm = 'static int swap_rm_tree(const char *p) { return nftw(p, swap_rm_cb, 16, FTW_DEPTH | FTW_PHYS); }'
    assert rm in code
    return code.replace(rm, 'static int swap_rm_tree(const char *p) { struct stat st; if (!lstat(p, &st) && !S_ISDIR(st.st_mode)) return unlink(p); '
                            'return nftw(p, swap_rm_cb, 16, FTW_DEPTH | FTW_PHYS); }')


def tree(d):
    """{relative path: bytes or None for a folder} of everything under d (and beside it: the save's markers and siblings); d's own path in a marker reads ROOT, and the
    launcher's lock file (the host takes none) is left out"""
    out = {}
    for p in sorted(Path(d).rglob('*')):
        if p.name.endswith('.lock'): continue
        out[str(p.relative_to(d))] = None if p.is_dir() else p.read_bytes().replace(str(d).encode(), b'ROOT')
    return out


def scenarios(root):
    """name → a function making that state under root/<name>; returns the save folder"""
    def base(r, tag='old'):
        s = r / 'saves/040074A01BF12000'; s.mkdir(parents=True)
        (s / 'slot0.bin').write_text('slot0 ' + tag); (s / 'stale.bin').write_text('stale'); return s

    def copy(s, suffix, files):
        a = Path(str(s) + suffix)
        for k, v in files.items(): (a / k).parent.mkdir(parents=True, exist_ok=True); (a / k).write_text(v)
        return a

    def pend1(r):
        s = base(r); a = copy(s, '.restore', {'slot0.bin': 'new', 'd/x.bin': 'x'}); Path(str(s) + '.pend1').write_text(str(a)); return s

    def pend2_partial(r):
        s = base(r); a = copy(s, '.new', {'slot0.bin': 'newer', 'c.bin': 'c'}); (s / 'stale.bin').unlink()
        Path(str(s) + '.pend2').write_text(str(a)); (a / 'c.bin').rename(s / 'c.bin'); return s

    def empty_pend2(r):
        s = base(r); a = copy(s, '.new', {'only.bin': 'only'}); Path(str(s) + '.pend1').write_text(str(a)); Path(str(s) + '.pend2').write_text(''); return s

    def pend1_gone(r):
        s = base(r); Path(str(s) + '.pend1').write_text(str(s) + '.new'); return s

    def foreign(r):
        s = base(r); f = r / 'foreign'; f.mkdir(); (f / 'keep.bin').write_text('keep'); Path(str(s) + '.pend1').write_text(str(f)); return s

    def foreign_dir(r):
        s = base(r); f = r / 'elsewhere' / (s.name + '.new'); f.mkdir(parents=True); (f / 'keep.bin').write_text('keep')
        Path(str(s) + '.pend1').write_text(str(f)); return s

    def both_markers(r):   # a stop after .pend2 was written and before .pend1 went: .pend1 must go before stage 2
        s = base(r); a = copy(s, '.new', {'slot0.bin': 'new'}); (s / 'stale.bin').unlink()
        Path(str(s) + '.pend1').write_text(str(a)); Path(str(s) + '.pend2').write_text(str(a)); return s

    def emptied_copy(r):   # what an older recovery left by stopping after its stage 2, before deleting .pend1: stage 1 against the emptied a would delete the save
        s = base(r, 'new'); a = Path(str(s) + '.new'); (a / 'd/e').mkdir(parents=True); Path(str(s) + '.pend1').write_text(str(a)); return s

    def legacy_old(r):
        s = base(r); o = Path(str(s) + '.old'); shutil.move(str(s), str(o)); return s

    # PEANUTS begin: delta copies (#571), with their .dm-keep
    def delta(r):
        s = base(r); (s / 'keep.bin').write_text('keep'); (s / 'old').mkdir(); (s / 'old/x.bin').write_text('x')
        a = copy(s, '.new', {'slot0.bin': 'new', 'sub/y.bin': 'y'}); (a / 'empty').mkdir()
        (a / '.dm-keep').write_bytes(b'\0'.join([b'keep.bin', b'slot0.bin', b'sub/', b'sub/y.bin', b'empty/']) + b'\0'); return s, a

    def delta_pend1(r):
        s, a = delta(r); Path(str(s) + '.pend1').write_text(str(a)); return s

    def delta_pend2_partial(r):
        s, a = delta(r); (s / 'stale.bin').unlink(); shutil.rmtree(s / 'old')
        Path(str(s) + '.pend2').write_text(str(a)); (a / 'slot0.bin').rename(s / 'slot0.bin'); return s

    def delta_done(r):
        s, a = delta(r); (s / 'stale.bin').unlink(); shutil.rmtree(s / 'old'); (a / 'slot0.bin').rename(s / 'slot0.bin')
        (s / 'sub').mkdir(); (a / 'sub/y.bin').rename(s / 'sub/y.bin'); (a / 'empty').rename(s / 'empty'); Path(str(s) + '.pend1').write_text(str(a)); return s
    # PEANUTS end

    return {'pend1': pend1, 'pend2_partial': pend2_partial, 'empty_pend2': empty_pend2, 'pend1_gone': pend1_gone, 'foreign': foreign, 'foreign_dir': foreign_dir,
            'legacy_old': legacy_old, 'both_markers': both_markers, 'emptied_copy': emptied_copy, 'delta_pend1': delta_pend1,
            'delta_pend2_partial': delta_pend2_partial, 'delta_done': delta_done}   # PEANUTS: the delta_ ones


DELTA_RESULT = {'keep.bin': b'keep', 'slot0.bin': b'new', 'sub': None, 'sub/y.bin': b'y', 'empty': None}   # PEANUTS


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class LauncherJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        d = Path(cls.tmp.name)
        (d / 'stub').mkdir()
        (d / 'stub/BuildConfig.java').write_text(STUB)
        cls.classes = d / 'classes'
        subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-d', str(cls.classes), str(SRC / 'SaveStore.java'), str(SRC / 'SaveMigrate.java'), str(SRC / 'Profile.java'), str(TEST), str(d / 'stub/BuildConfig.java')],
                       check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def java(self, *args, **kw):
        return subprocess.run(['java', '-Dnx.res=' + str(RES), '-cp', str(self.classes), 'dev.nxnative.android.LauncherJvmTest', *map(str, args)], capture_output=True, text=True, **kw)

    def test_saves_and_profiles(self):
        with tempfile.TemporaryDirectory() as d:
            r = self.java(d)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)

    def test_lock_held_by_another_process(self):
        with tempfile.TemporaryDirectory() as d:
            s = Path(d) / 'saves/040074A01BF12000'; s.mkdir(parents=True); (s / 'slot0.bin').write_text('x')
            (Path(d) / 'src').mkdir(); (Path(d) / 'src/a.bin').write_text('a')
            h = subprocess.Popen(['java', '-cp', str(self.classes), 'dev.nxnative.android.LauncherJvmTest', 'holdtwice', str(s)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            # PEANUTS(upstream-candidate): PIPE creates both streams for this holder process.
            assert h.stdout is not None and h.stdin is not None
            try:
                self.assertEqual(h.stdout.readline().strip(), 'held')
                r = self.java('locked', s)
                self.assertEqual(r.stdout.strip(), 'refused 3 inUse', r.stdout + r.stderr)
                self.assertEqual((s / 'slot0.bin').read_text(), 'x')
            finally:
                h.stdin.close(); h.wait(10)
                h.stdout.close()   # PEANUTS: close both PIPE streams after the holder exits
            r = self.java('locked', s)   # released when the holder ended
            self.assertEqual(r.stdout.strip(), 'refused 0', r.stdout + r.stderr)

    @unittest.skipUnless(shutil.which('cc'), 'no C compiler')
    def test_journal_matches_the_host(self):
        """Each stopped swap, recovered by the host's plat_swap_recover and by SaveStore.recover from the same state: the same files after, the same refusals"""
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'recover.c').write_text(HARNESS % swap_code())
            subprocess.run(['cc', '-std=gnu11', '-w', '-o', str(d / 'recover'), str(d / 'recover.c')], check=True, capture_output=True, text=True)
            refused = {'pend1_gone', 'foreign', 'foreign_dir'}
            for name, make in scenarios(d).items():
                with self.subTest(name):
                    host, app = d / 'host' / name, d / 'app' / name
                    sh, sa = make(host), make(app)
                    before = tree(host)
                    rh = subprocess.run([str(d / 'recover'), str(sh)], capture_output=True, text=True)
                    ra = self.java('recover', sa)
                    self.assertEqual(tree(host), tree(app), f'{name}: host {rh.stdout!r}, app {ra.stdout!r}')
                    if name in refused:
                        self.assertIn('[RED]', rh.stdout); self.assertTrue(ra.stdout.startswith('refused'), ra.stdout)
                        self.assertEqual(tree(host), before, f'{name}: a refused marker changes nothing')
                    else:
                        if name != 'emptied_copy': self.assertNotIn('[RED]', rh.stdout)   # the emptied copy is logged: stage 1 refused, the marker dropped
                        self.assertFalse(ra.stdout.startswith('refused'), ra.stdout)
                        if name == 'emptied_copy': self.assertEqual((sh / 'slot0.bin').read_text(), 'slot0 new', 'the save survives')
                        if name == 'both_markers': self.assertEqual((sh / 'slot0.bin').read_text(), 'new')
                        if name.startswith('delta'): self.assertEqual(tree(sh), DELTA_RESULT, name)   # PEANUTS
                        self.assertFalse(any(k.endswith(('.pend1', '.pend2', '.old', '.restore', '.new')) for k in tree(host)), f'{name}: {list(tree(host))}')


@unittest.skipUnless(shutil.which('cc'), 'no C compiler')
class HostSwap(unittest.TestCase):
    def test_commit_keeps_the_save_folder(self):
        """A commit (plat_rename_swap of <save>.new over <save>) brings the save to the new version in place: the folder itself stays (its inode), so a folder sync tool
        watching it with inotify sees the commit (#485); files the new version lacks go, its subfolders come, no marker stays"""
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'recover.c').write_text(HARNESS % swap_code())
            subprocess.run(['cc', '-std=gnu11', '-w', '-o', str(d / 'swap'), str(d / 'recover.c')], check=True, capture_output=True, text=True)
            s = d / 'saves/040074A01BF12000'; (s / 'old').mkdir(parents=True)
            (s / 'slot0.bin').write_text('old'); (s / 'stale.bin').write_text('stale'); (s / 'old/x.bin').write_text('x')
            a = Path(str(s) + '.new'); (a / 'sub').mkdir(parents=True); (a / 'slot0.bin').write_text('new'); (a / 'sub/y.bin').write_text('y')
            ino = s.stat().st_ino
            r = subprocess.run([str(d / 'swap'), 'swap', str(a), str(s)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(s.stat().st_ino, ino, 'the save folder is the same folder after the commit')
            self.assertEqual({k: v for k, v in tree(s).items()}, {'slot0.bin': b'new', 'sub': None, 'sub/y.bin': b'y'})
            self.assertFalse(Path(str(s) + '.pend1').exists() or Path(str(s) + '.pend2').exists())
            self.assertFalse(any(p.is_file() for p in a.rglob('*')), 'the new version moved out of <save>.new')

    # PEANUTS begin
    def build(self, d):
        (d / 'recover.c').write_text(HARNESS % swap_code())
        subprocess.run(['cc', '-std=gnu11', '-w', '-o', str(d / 'swap'), str(d / 'recover.c')], check=True, capture_output=True, text=True)
        return d / 'swap'

    def test_failure_after_the_marker_keeps_the_copy(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); x = self.build(d)
            s = d / 'saves/040074A01BF12000'; (s / 'ro').mkdir(parents=True); (s / 'ro/gone.bin').write_text('gone'); (s / 'slot0.bin').write_text('old')
            a = Path(str(s) + '.new'); (a / 'ro').mkdir(parents=True); (a / 'slot0.bin').write_text('new')
            (s / 'ro').chmod(0o555)
            try:
                r = subprocess.run([str(x), 'swap', str(a), str(s)], capture_output=True, text=True)
                self.assertEqual(r.returncode, 2, 'a failure after the marker is told apart (-2)')
                self.assertTrue(Path(str(s) + '.pend1').exists() and (a / 'slot0.bin').exists(), 'the marker and the copy stay')
            finally:
                (s / 'ro').chmod(0o755)
            subprocess.run([str(x), str(s)], check=True, capture_output=True, text=True)
            self.assertEqual(tree(s), {'ro': None, 'slot0.bin': b'new'})
            self.assertFalse(Path(str(s) + '.pend1').exists() or Path(str(s) + '.pend2').exists() or a.exists())

    def test_folder_mode_follows_the_new_version(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d); x = self.build(d)
            s = d / 'saves/040074A01BF12000'; (s / 'sub').mkdir(parents=True); (s / 'sub').chmod(0o755)
            a = Path(str(s) + '.new'); (a / 'sub').mkdir(parents=True); (a / 'sub/y.bin').write_text('y'); (a / 'sub').chmod(0o700)
            subprocess.run([str(x), 'swap', str(a), str(s)], check=True, capture_output=True, text=True)
            self.assertEqual((s / 'sub').stat().st_mode & 0o777, 0o700)
    # PEANUTS end

    # PEANUTS begin: delta commits (#571)
    def test_delta_commit_touches_only_changes(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            (d / 'recover.c').write_text(HARNESS % swap_code())
            subprocess.run(['cc', '-std=gnu11', '-w', '-o', str(d / 'swap'), str(d / 'recover.c')], check=True, capture_output=True, text=True)
            s = scenarios(d)['delta_pend1'](d / 'x')
            a = Path(str(s) + '.new'); Path(str(s) + '.pend1').unlink()
            ino, keep = s.stat().st_ino, (s / 'keep.bin').stat().st_ino
            r = subprocess.run([str(d / 'swap'), 'swap', str(a), str(s)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(tree(s), DELTA_RESULT)
            self.assertEqual(s.stat().st_ino, ino)
            self.assertEqual((s / 'keep.bin').stat().st_ino, keep, 'an unchanged file is left where it is')
            self.assertFalse(Path(str(s) + '.pend1').exists() or Path(str(s) + '.pend2').exists())

    # PEANUTS end


# PEANUTS begin: the delta commit and the mount's update with real folder attributes: plat_android.c's copy and journal code, the Linux xattr calls mapped to macOS's
DELTA_HARNESS = """#ifdef __APPLE__
#define _DARWIN_C_SOURCE
#include <sys/xattr.h>
#define llistxattr(p, b, n) listxattr(p, b, n, XATTR_NOFOLLOW)
#define lgetxattr(p, k, v, n) getxattr(p, k, v, n, 0, XATTR_NOFOLLOW)
#define lsetxattr(p, k, v, n, f) setxattr(p, k, v, n, 0, (f) | XATTR_NOFOLLOW)
#define lremovexattr(p, k) removexattr(p, k, XATTR_NOFOLLOW)
#define st_atim st_atimespec
#define st_mtim st_mtimespec
#else
#define _GNU_SOURCE
#include <sys/xattr.h>
#endif
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#define LOG(...) (printf(__VA_ARGS__), printf("\\n"))
%s
int main(int argc, char **argv) {
    if (argc == 4 && !strcmp(argv[1], "commit")) {   // as hle_fs.c commits: the changes of argv[2] (.work) since argv[3] (the save) into <save>.new, then the journal
        char neu[4200]; snprintf(neu, sizeof neu, "%%s.new", argv[3]);
        if (plat_clone_changed(argv[2], argv[3], neu)) return 1;
        int r = plat_rename_swap(neu, argv[3]); if (!r) swap_rm_tree(neu); return r == -2 ? 2 : r ? 1 : 0;
    }
    if (argc == 4 && !strcmp(argv[1], "update")) return plat_update_tree(argv[2], argv[3]) ? 1 : 0;
    return 3;
}
"""


def delta_code():
    s = PLAT.read_text()
    code = s[s.index('// No copy-on-write clone on ext4'):s.index('// Linux keeps an application')]
    rm = 'static int swap_rm_tree(const char *p) { return nftw(p, swap_rm_cb, 16, FTW_DEPTH | FTW_PHYS); }'
    assert rm in code
    return 'int plat_rename_swap(const char *a, const char *b);\n' + code.replace(rm, 'static int swap_rm_tree(const char *p) { struct stat st; if (!lstat(p, &st) && !S_ISDIR(st.st_mode)) return unlink(p); '
                                                                                   'return nftw(p, swap_rm_cb, 16, FTW_DEPTH | FTW_PHYS); }')


GUEST = 'user.guest'


def setx(p, v):
    if hasattr(__import__('os'), 'setxattr'): __import__('os').setxattr(str(p), GUEST, v.encode())
    else: subprocess.run(['xattr', '-w', GUEST, v, str(p)], check=True, capture_output=True)


def getx(p):
    if hasattr(__import__('os'), 'getxattr'):
        try: return __import__('os').getxattr(str(p), GUEST).decode()
        except OSError: return None
    r = subprocess.run(['xattr', '-p', GUEST, str(p)], capture_output=True, text=True)
    return r.stdout.strip() if r.returncode == 0 else None


@unittest.skipUnless(shutil.which('cc'), 'no C compiler')
class HostDelta(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.d = Path(self.tmp.name)
        (self.d / 'delta.c').write_text(DELTA_HARNESS % delta_code())
        r = subprocess.run(['cc', '-std=gnu11', '-w', '-o', str(self.d / 'delta'), str(self.d / 'delta.c')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        try: setx(self.d, 'probe')
        except (OSError, subprocess.CalledProcessError): self.skipTest('no user xattrs here')

    def tearDown(self):
        self.tmp.cleanup()

    def save_and_work(self):
        s = self.d / 'saves/040074A01BF12000'; (s / 'sub').mkdir(parents=True); (s / 'sub/x.bin').write_text('x'); (s / 'slot0.bin').write_text('old')
        setx(s / 'sub', 'Foo'); (s / 'sub').chmod(0o755)
        w = Path(str(s) + '.work'); shutil.copytree(s, w); setx(w / 'sub', 'Foo')
        return s, w

    def run_delta(self, *a):
        return subprocess.run([str(self.d / 'delta'), *map(str, a)], capture_output=True, text=True)

    def test_folder_guest_name_and_mode_reach_the_save(self):
        s, w = self.save_and_work()
        setx(w / 'sub', 'foo'); (w / 'sub').chmod(0o700)
        x = (s / 'sub/x.bin').stat().st_ino
        r = self.run_delta('commit', w, s); self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(getx(s / 'sub'), 'foo')
        self.assertEqual((s / 'sub').stat().st_mode & 0o777, 0o700)
        self.assertEqual((s / 'sub/x.bin').stat().st_ino, x, 'only the folder changed: its file stays where it is')

    def test_changed_file_keeps_its_folder_guest_name(self):
        s, w = self.save_and_work()
        (w / 'sub/x.bin').write_text('x2')
        r = self.run_delta('commit', w, s); self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual((s / 'sub/x.bin').read_text(), 'x2')
        self.assertEqual(getx(s / 'sub'), 'Foo', 'the folder made for a changed file carries its guest name')

    def test_mount_update_brings_folder_mode(self):
        s, w = self.save_and_work()
        (s / 'sub').chmod(0o700)
        x = (w / 'sub/x.bin').stat().st_ino
        r = self.run_delta('update', s, w); self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual((w / 'sub').stat().st_mode & 0o777, 0o700)
        self.assertEqual(getx(w / 'sub'), 'Foo')
        self.assertEqual((w / 'sub/x.bin').stat().st_ino, x, 'only the mode changed: the folder is not copied again')

    def test_mount_update_brings_folder_guest_name(self):
        s, w = self.save_and_work()
        setx(s / 'sub', 'foo')
        r = self.run_delta('update', s, w); self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(getx(w / 'sub'), 'foo')
# PEANUTS end


if __name__ == '__main__':
    unittest.main()
