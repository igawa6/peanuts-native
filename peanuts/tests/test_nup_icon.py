"""A game's icon kept with its .nup (issue #158: a game built from an NSP lost its icon once the NSP was gone and the app's data was cleared).

The importer writes each package's meta block into the .nup it makes (dm/peanuts/import/nximport.c pkg_meta, SPEC §5: main.npdm, the NACP, the display version,
and in the base the icon, title and publisher): its own test, tests/nup_meta_test.c, is compiled here when there is a C compiler, and the .nups it writes are
read with the other readers too (dm/tools/nup.py, scripts/nup_meta.py's records, the launcher's Nup). The library reads the icon back and keeps one beside a
.nup that carries none (dev.peanuts.ui.NupIcon, on the JVM with upstream's Nup reader and android/app/src/jvmtest's NupIconJvmTest; skipped without a JDK).
The source checks say that the library and Free up space use it."""
import contextlib
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(REPO / 'dm/tools'), str(REPO / 'scripts')]
import nup  # noqa: E402
import nup_meta  # noqa: E402  # pyright: ignore[reportMissingImports]  # scripts/ is added to sys.path above.

IMPORT = REPO / 'dm/peanuts/import'
UPSTREAM = REPO / 'android/app/src/main/java/dev/nxnative/android'
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
JVMTEST = REPO / 'android/app/src/jvmtest/java/dev/peanuts/ui/NupIconJvmTest.java'
ICON = b'\xff\xd8\xff\xe0' + bytes(range(256)) * 12 + b'\xff\xd9'   # a JPEG by its first bytes


def png(w=4, h=4):
    """A small PNG, made here (no imaging package needed)"""
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d))
    rows = b''.join(b'\0' + b''.join(bytes([(x * 60) % 256, (y * 60) % 256, 128]) for x in range(w)) for y in range(h))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')


def cc():
    return next((c for c in ('cc', 'gcc', 'clang') if shutil.which(c)), None)


class Icon(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='nupicon_')
        d = cls.dir = Path(cls.tmp.name)
        src = d / 'dump'
        (src / 'base/exefs').mkdir(parents=True); (src / 'base/romfs').mkdir(parents=True)
        npdm = bytearray(0x100); npdm[:4] = b'META'; struct.pack_into('<II', npdm, 0x70, 0x80, 0x20); npdm[0x80:0x84] = b'ACI0'; struct.pack_into('<Q', npdm, 0x90, 0x0100BBBB0000C000)
        (src / 'base/exefs/main.npdm').write_bytes(npdm); (src / 'base/romfs/a.bin').write_bytes(b'hello' * 100)
        subprocess.run([sys.executable, str(REPO / 'dm/tools/nup_pack.py'), '--raw', str(src), str(d / 'plain.nup')], check=True, capture_output=True)
        shutil.copy(d / 'plain.nup', d / 'meta.nup')
        with contextlib.redirect_stdout(io.StringIO()): nup_meta.set_meta(str(d / 'meta.nup'), None, ICON, 'Fixture', None, None)
        (d / 'icon.jpg').write_bytes(ICON)
        (d / 'damaged.nup').write_bytes(b'NUP\x01' + b'\0' * 200)
        (d / 'icon.png').write_bytes(png())
        (d / 'notpng.png').write_text('not a picture')
        # the importer's own test, which leaves its dumps and .nups here (quest.nup, bare.nup, french.nup, update-only.nup)
        cls.importer = None
        if cc():
            exe = d / 'nup_meta_test'
            # NXIMPORT_SOURCES of the importer's CMakeLists: the NCZ reader (nx_ncz.c) and the vendored zstd decoder it uses (.nsz / .xcz, issue #149)
            srcs = [IMPORT / 'tests/nup_meta_test.c'] + [IMPORT / f for f in ('nx_crypto.c', 'nx_lz4.c', 'nx_nca.c', 'nx_ncz.c', 'nx_icon.c', 'nx_zip.c', 'nximport.c')]
            srcs.append(IMPORT / '../../third_party/zstd/zstddeclib.c')
            b = subprocess.run([cc(), '-std=gnu11', '-O1', '-w', '-DZSTDLIB_VISIBLE=', '-DZSTDERRORLIB_VISIBLE=', '-I', str(IMPORT)] + [str(s) for s in srcs]
                               + ['-lz', '-lpthread', '-lm', '-ldl', '-o', str(exe)], capture_output=True, text=True)
            cls.importer = b if b.returncode else subprocess.run([str(exe), str(d)], capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    @unittest.skipUnless(cc(), 'no C compiler')
    def test_importer_writes_meta(self):
        r = self.importer
        self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
        self.assertIn('PASS', r.stdout)
        # the other readers take what it wrote: the reader of the tools (every bound, the meta blocks' too) and the records as scripts/nup_meta.py reads them
        for name, want in (('quest', '123456'), ('bare', '1'), ('french', '1256'), ('update-only', '123456')):
            n = nup.open_nup(str(self.dir / f'{name}.nup'))
            p = n.packages[0]
            moff, msize = nup.PKG.unpack_from(n.toc, n.o_pkg)[9:11]
            recs = nup_meta.records((self.dir / f'{name}.nup').read_bytes()[moff:moff + msize])
            self.assertEqual(''.join(str(t) for t, _ in recs), want, name)
            self.assertFalse(any(t == 0 for t, _ in recs), f'{name}: the spec\'s numbers, no type 0 (that marks the packer\'s shifted ones)')
            if name == 'quest':
                self.assertEqual(p.selector, 'base')
                self.assertEqual(dict(recs)[3], (self.dir / 'quest-icon.jpg').read_bytes())
                self.assertEqual(n.read(n.node('update/131072/romfs/b.txt')), b'hello')

    # the generated class upstream's Nup reaches through Pack and Profile (as dm/tools/tests/test_nup.py stands it in)
    STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "";
    public static final String NX_DMSH_VK_CHAIN = "";
    public static final String NX_DMSH_VK_ABI = "";
    public static final String NX_DMSH_VK_ABI_COMPAT = "";
}
'''

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='nupiconjvm_') as t:
            stub = Path(t) / 'stub/dev/nxnative/android'; stub.mkdir(parents=True); (stub / 'BuildConfig.java').write_text(self.STUB)
            # upstream's Nup and only what it needs (-sourcepath: the launcher's sources, which javac reads as Nup reaches them); NupIcon reaches it by reflection
            subprocess.run(['javac', '-encoding', 'UTF-8', '-nowarn', '-sourcepath', os.pathsep.join([str(REPO / 'android/app/src/main/java'), str(Path(t) / 'stub')]), '-d', str(Path(t) / 'classes'),
                            str(UPSTREAM / 'Nup.java'), str(UI / 'NupIcon.java'), str(JVMTEST)], check=True)
            r = subprocess.run(['java', '-cp', str(Path(t) / 'classes'), 'dev.peanuts.ui.NupIconJvmTest', str(self.dir)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Wiring(unittest.TestCase):
    def test_writer(self):
        c = (IMPORT / 'nximport.c').read_text(encoding='utf-8')
        self.assertIn('enum { TLV_NPDM = 1, TLV_NACP = 2, TLV_ICON = 3, TLV_VERSION = 4, TLV_TITLE = 5, TLV_PUBLISHER = 6 };', c)
        self.assertLess(c.index('if (pkg_meta(&j->pk[p], p == app)) goto out;'), c.index('rc = write_nup(j, out_nup);'))
        self.assertIn('put64(r + 0x20, k->meta_off); put32(r + 0x28, (uint32_t)k->meta_n);', c)

    def test_library(self):
        scanner = (UI / 'LibraryScanner.java').read_text(encoding='utf-8')
        body = scanner[scanner.index('static File icon(Context c, Title t)'):scanner.index('private static boolean fromInside(')]
        order = [body.index(s) for s in ('NupIcon.copyPng(NupIcon.beside(f), out)', 'fromInside(f, out)', 'all.addAll(t.originals);', 'imp.icon(')]
        self.assertEqual(order, sorted(order), 'the PNG beside, then the icon inside, then the importer, the originals of a .nup base among its sources')
        self.assertIn('{ keep(c, t); return out; }', body)
        self.assertIn('if (icons.containsKey(x.titleId)) { LibraryScanner.keep(a, x); continue; }', (UI / 'Library.java').read_text(encoding='utf-8'))
        free = (UI / 'LibrarySettings.java').read_text(encoding='utf-8')
        self.assertLess(free.index('LibraryScanner.icon(a, t);'), free.index('boolean ok = f.isFile() && t.base.file.isFile()'), 'Free up space keeps the icon before the original goes')

    def test_no_android_in_nupicon(self):
        self.assertNotIn('import android.', (UI / 'NupIcon.java').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
