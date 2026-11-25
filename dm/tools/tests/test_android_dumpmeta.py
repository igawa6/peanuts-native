"""A folder dump's control data and other programs in the Android launcher, on the JVM (Nacp, DumpLayout.controlDir and DumpLayout.programDir with
android/app/src/jvmtest's DumpMetaJvmTest), and dm/tools/nacp.py on the same control data: the title, publisher and display version in the phone's language (a
compressed title area too), the icon of that language, else American English's, else any; the newest version's control/ in the console's and the flattened
layout; program 1 at program/1/, program1/ or Yoink's switch2/. Skipped without a JDK (the nacp.py part runs without one)."""
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/DumpMetaJvmTest.java'
sys.path.insert(0, str(REPO / 'dm/tools'))
import nacp  # noqa: E402

STUB = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040015100B514001\\tSuper Mario Bros. Wonder - Nintendo Switch 2 Edition\\t0\\tabcdef012345\\t327680 main=aa";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee000001", NX_DMSH_VK_ABI = "abc123abc123", NX_DMSH_VK_ABI_COMPAT = "";
}
'''


def nacp_bytes(titles, version, compressed=False):
    """A control.nacp: {language index: (name, publisher)}, the display version at 0x3060; compressed: the title area as newer SDKs keep it (u16 size, raw deflate of 32 entries)"""
    area = bytearray(0x300 * (32 if compressed else 16))
    for i, (n, p) in titles.items():
        area[i * 0x300:i * 0x300 + len(n.encode())] = n.encode()
        area[i * 0x300 + 0x200:i * 0x300 + 0x200 + len(p.encode())] = p.encode()
    b = bytearray(0x4000)
    if compressed:
        c = zlib.compressobj(9, zlib.DEFLATED, -15); z = c.compress(bytes(area)) + c.flush()
        struct.pack_into('<H', b, 0, len(z)); b[2:2 + len(z)] = z
    else: b[:0x3000] = area
    b[0x3060:0x3060 + len(version)] = version.encode()
    return bytes(b)


def control(d, titles, version, icons=(), compressed=False):
    d.mkdir(parents=True, exist_ok=True)
    (d / 'control.nacp').write_bytes(nacp_bytes(titles, version, compressed))
    for lang, size in icons: (d / f'icon_{lang}.dat').write_bytes(b'\xff\xd8' + b'\0' * (size - 2))
    return d


def npdm(tid):   # a main.npdm: META with its ACI0 at 0x80, the program ID at ACI0+0x10 (Pack.programId)
    d = bytearray(0x100); d[:4] = b'META'; struct.pack_into('<II', d, 0x70, 0x80, 0x20); d[0x80:0x84] = b'ACI0'; struct.pack_into('<Q', d, 0x90, tid)
    return bytes(d)


def program(d, tid=None):
    (d / 'exefs').mkdir(parents=True); (d / 'exefs/main.npdm').write_bytes(b'META' if tid is None else npdm(tid)); (d / 'romfs').mkdir()
    if tid is not None: (d / 'exefs/main').write_bytes(b'NSO0' + bytes(0x100))


class NacpPy(unittest.TestCase):
    def test_name_publisher_version(self):
        with tempfile.TemporaryDirectory() as t:
            f = control(Path(t) / 'c', {0: ('Game', 'Studio'), 2: ('ゲーム', 'スタジオ')}, '1.2.1', compressed=True) / 'control.nacp'
            self.assertEqual((nacp.name(f), nacp.publisher(f), nacp.version(f)), ('Game', 'Studio', '1.2.1'))
            g = control(Path(t) / 'd', {3: ('Jeu', 'Studio FR')}, '2.0') / 'control.nacp'   # no American English entry: the first with a name
            self.assertEqual((nacp.name(g), nacp.publisher(g)), ('Jeu', 'Studio FR'))


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class DumpMetaJvm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix='dumpmeta_'))
        (cls.tmp / 'stub').mkdir()
        (cls.tmp / 'stub/BuildConfig.java').write_text(STUB)
        srcs = [SRC / f'{n}.java' for n in ('Nacp', 'BuildCheck', 'DumpLayout', 'RomfsCheck', 'Nup', 'Nsp', 'Zstd', 'Pack', 'Profile', 'ShaderLibs')]
        r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.tmp / 'classes'), *map(str, srcs), str(TEST), str(cls.tmp / 'stub/BuildConfig.java')],
                           capture_output=True, text=True)
        assert r.returncode == 0, r.stdout + r.stderr

    @classmethod
    def tearDownClass(cls): shutil.rmtree(cls.tmp, ignore_errors=True)

    def java(self, *a):
        r = subprocess.run(['java', '-Dstdout.encoding=UTF-8', '-cp', str(self.tmp / 'classes'), 'dev.nxnative.android.DumpMetaJvmTest', *map(str, a)],
                           capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.strip()

    def test_nacp(self):
        d = self.tmp / 'nacp'
        c = control(d / 'a', {0: ('Game', 'Studio'), 2: ('ゲーム', 'スタジオ')}, '1.2.1', [('AmericanEnglish', 100), ('Japanese', 200)], compressed=True)
        self.assertEqual(self.java('nacp', c, 'en-US'), 'Game|Studio|1.2.1|100')
        self.assertEqual(self.java('nacp', c, 'ja-JP'), 'ゲーム|スタジオ|1.2.1|200')   # the phone's language, its icon
        self.assertEqual(self.java('nacp', c, 'de-DE'), 'Game|Studio|1.2.1|100')   # no German entry or icon: American English's
        u = control(d / 'b', {3: ('Jeu', 'Studio FR')}, '2.0', [('French', 50)])   # uncompressed; no American English: the first entry and any icon
        self.assertEqual(self.java('nacp', u, 'en-US'), 'Jeu|Studio FR|2.0|50')
        self.assertEqual(self.java('nacp', d / 'none', 'en-US'), 'null')

    def test_control_dir(self):
        k = self.tmp / 'console'   # the console's layout: the newest version's program/0/control
        program(k / 'base/program/0'); program(k / 'updates/65536/program/0'); program(k / 'updates/131072/program/0')
        control(k / 'base/program/0/control', {0: ('Game', 'S')}, '1.0.0')
        control(k / 'updates/65536/program/0/control', {0: ('Game', 'S')}, '1.0.1')
        self.assertEqual(self.java('control', k), 'updates/65536/program/0/control')   # 131072 has no control/: the newest that has one
        f = self.tmp / 'flat'   # the flattened layout: <version>/control
        program(f / 'base'); program(f / 'updates/65536'); control(f / 'updates/65536/control', {0: ('Game', 'S')}, '1.0.1')
        self.assertEqual(self.java('control', f), 'updates/65536/control')
        self.assertEqual(self.java('control', self.tmp / 'nothing'), '-')

    def test_program_dir(self):
        c = self.tmp / 'p-console'; program(c / 'base/program/0'); program(c / 'updates/65536/program/1')
        self.assertEqual(self.java('program', c, 1), 'updates/65536/program/1')
        f = self.tmp / 'p-flat'; program(f / 'base'); program(f / 'updates/65536'); program(f / 'updates/65536/program01')
        self.assertEqual(self.java('program', f, 1), 'updates/65536/program01')
        y = self.tmp / 'p-yoink'; program(y / 'base'); program(y / 'switch2/base'); program(y / 'switch2/updates/131072')
        self.assertEqual(self.java('program', y, 1), 'switch2/updates/131072')
        self.assertEqual(self.java('program', y, 2), '-')

    # The APK of v0.3.24: Super Mario Bros. Wonder - Nintendo Switch 2 Edition's profile (program 1, 040015100B514001), not the game's (010015100B514000)
    def test_edition_card_without_the_game(self):
        """A dump with program 1: its card is program 1 (no refusal for the game's ID, no edition switch); Prepare says what it said before for the game"""
        d = self.tmp / 'w-edition'; program(d / 'base/program/0', 0x010015100B514000); program(d / 'updates/327680/program/0', 0x010015100B514000)
        program(d / 'updates/327680/program/1', 0x040015100B514001)
        self.assertEqual(self.java('playable', d), '040015100B514001')
        self.assertEqual(self.java('find', d), 'This app version does not include this game, 010015100B514000. Install an app version that includes it.')  # PEANUTS: build text in the player's words (0.1.8)

    def test_switch1_only_dump(self):
        """A dump of the Switch game alone: its card stays the game's, and the sentence says the dump lacks the Edition this app runs"""
        d = self.tmp / 'w-switch1'; program(d / 'base/program/0', 0x010015100B514000); program(d / 'updates/327680/program/0', 0x010015100B514000)
        self.assertEqual(self.java('playable', d), '010015100B514000')
        why = self.java('missing', '010015100B514000')
        self.assertTrue(why.startswith('These game files hold only the Switch game.'), why)  # PEANUTS: build text in the player's words (0.1.8)
        self.assertIn('This app version runs Super Mario Bros. Wonder - Nintendo Switch 2 Edition, which they do not hold.', why)
        self.assertEqual(self.java('find', d), why)   # Prepare says the same
        self.assertTrue(self.java('missing', '0100000000010000').startswith('This app version does not include this game'))   # another game: as before
        self.assertEqual(self.java('missing', '040015100B514001'), 'null')


if __name__ == '__main__':
    unittest.main()
