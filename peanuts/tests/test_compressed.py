"""Compressed game files, .nsz and .xcz (issue #149), in the app: dev.peanuts.build's Compressed and SpaceEstimate on the JVM (javac/java only, the pattern of
test_reimport.py): the importer's steps in words, the bar that never goes back, the importer's errors in a player's words, the space a solid file needs while
it is unpacked. The source checks say that the library, the sheet, the build and the log bundle keep to them. The importer itself is tested in C
(dm/peanuts/import/tests/ncz_test.c)."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / 'android/app/src/main/java/dev/peanuts'
SRC = [JAVA / 'build/Compressed.java', JAVA / 'build/SpaceEstimate.java']

RUNNER = r'''package dev.peanuts.build;
import java.io.File;
public final class CompressedTest {
    public static void main(String[] a) {
        switch (a[0]) {
            case "is": System.out.println(Compressed.is(new File(a[1])) + " " + Compressed.kind(new File(a[1]))); break;
            case "step": System.out.println(Compressed.stepText(a[1], Long.parseLong(a[2]), Long.parseLong(a[3]))); break;
            case "pct": System.out.println(Compressed.percent(a[1], Long.parseLong(a[2]), Long.parseLong(a[3]), Long.parseLong(a[4]), Long.parseLong(a[5]), Integer.parseInt(a[6]))); break;
            case "fail": { String f = Compressed.failure(a[1], a[2], a[3].equals("1")); System.out.println(f == null ? "null" : f); break; }
            case "unpack": System.out.println("[" + SpaceEstimate.unpackSentence(Long.parseLong(a[1])) + "]"); break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
'''
GB = 1 << 30


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Logic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='compressed_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'CompressedTest.java'
        runner.write_text(RUNNER, encoding='utf-8')
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.classes), *map(str, SRC), str(runner)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def java(self, *args):
        r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', str(self.classes), 'dev.peanuts.build.CompressedTest', *map(str, args)],
                           capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.rstrip('\n')

    def test_kinds(self):
        self.assertEqual(self.java('is', '/g/Game [0100].NSZ'), 'true NSZ')
        self.assertEqual(self.java('is', '/g/Game.xcz'), 'true XCZ')
        self.assertEqual(self.java('is', '/g/Game.xci'), 'false XCI')
        self.assertEqual(self.java('is', '/g/Game.nsp'), 'false ')
        self.assertEqual(self.java('is', '/g/nsz.nup'), 'false ')

    def test_steps_in_words(self):
        self.assertEqual(self.java('step', 'unpack/Game [0100].nsz', 37, 100), 'Unpacking Game [0100].nsz: 37 %')
        self.assertEqual(self.java('step', 'check/Game.xcz', 1, 8), 'Checking Game.xcz: 12 %')
        self.assertEqual(self.java('step', 'base/romfs', 50, 200), 'Importing the game files: 25 %')   # the writer's steps take turns: one sentence for all
        self.assertEqual(self.java('step', 'update/65536/exefs', 1, 2), 'Importing the game files: 50 %')
        self.assertEqual(self.java('step', 'probe', 0, 0), 'Reading the game files')

    def test_the_bar(self):
        # 6 GB unpacked before a 12 GB .nup: a third of the import's share (44 of 45) for unpacking, the rest for the writer; it never goes back
        seq = [('probe', 0, 0), ('unpack/a.nsz', 0, 6 * GB), ('unpack/a.nsz', 3 * GB, 6 * GB), ('unpack/a.nsz', 6 * GB, 6 * GB),
               ('base/exefs', 0, 12 * GB), ('base/romfs', 6 * GB, 12 * GB), ('toc', 12 * GB, 12 * GB)]
        got = [int(self.java('pct', s, d, t, 6 * GB, 12 * GB, 45)) for s, d, t in seq]
        self.assertEqual(got, [0, 0, 7, 14, 14, 29, 44])
        self.assertEqual([int(self.java('pct', s, d, t, 0, 12 * GB, 45)) for s, d, t in seq[4:]], [0, 22, 44], 'nothing compressed: as before')
        self.assertEqual(int(self.java('pct', 'check/a.xcz', 1, 2, 1 * GB, 0, 45)), 7, 'the .nup\'s size unknown: unpacking and checking take a third')

    def test_errors_in_a_players_words(self):
        cases = {
            'Game.nsz: abc.ncz does not match its checksum: the file is damaged or incomplete.': 'Game.nsz is damaged or incomplete, so Kart was not imported. Nothing was changed. Copy or compress the file again, or build from the uncompressed file.',
            'Game.nsz is cut off: abc.ncz ends after 100 of its 200 bytes': 'Game.nsz is damaged or incomplete, so Kart was not imported. Nothing was changed. Copy or compress the file again, or build from the uncompressed file.',
            'Game.xcz: abc.ncz: block 3 does not decompress (Corrupted block detected): the file is damaged': 'Game.xcz is damaged or incomplete, so Kart was not imported. Nothing was changed. Copy or compress the file again, or build from the uncompressed file.',
            'Game.nsz: the keys inside abc.ncz do not match the ones from its ticket and your keys: the file is damaged, or was compressed from another dump': 'Game.nsz does not match your keys, so Kart was not imported. Nothing was changed. Compress the game again from your own dump, or build from the uncompressed file.',
            'Game.nsz: abc.ncz was compressed with a zstd window larger than 128 MiB, more than an import can hold': 'Game.nsz was compressed with settings the app cannot read, so Kart was not imported. Nothing was changed. Compress it again with nsz\'s default settings, or build from the uncompressed file.',
            'Game.nsz: abc.ncz uses a newer NCZ layout (NCZSECT2) that cannot be read here': 'Game.nsz was compressed with settings the app cannot read, so Kart was not imported. Nothing was changed. Compress it again with nsz\'s default settings, or build from the uncompressed file.',
        }
        for msg, words in cases.items():
            self.assertEqual(self.java('fail', msg, 'Kart', 1), words, msg)
        self.assertEqual(self.java('fail', 'Game.nsz: abc.ncz does not match its checksum', 'Kart', 0),
                         'Game.nsz is damaged or incomplete, so Kart was not imported. Copy or compress the file again, or build from the uncompressed file.', 'the old .nup was deleted first: not "nothing was changed"')
        self.assertEqual(self.java('fail', 'abc.nca is not in /g/Game.nsp', 'Kart', 1), 'null', 'not about a compressed file: the message as it was')
        self.assertEqual(self.java('fail', 'cannot write into /x: No space left on device (unpacking Game.nsz)', 'Kart', 1), 'null', 'out of space is the space code\'s')
        for words in cases.values():   # UI-RULES: no separator glyphs, no dashes, no exclamation marks
            self.assertFalse(re.search(r'[·•—–!]| - ', words), words)

    def test_unpack_sentence(self):
        self.assertEqual(self.java('unpack', 0), '[]')
        self.assertEqual(self.java('unpack', 15 * GB), '[ About 15.0 GB of it only while the compressed game is unpacked.]')


class Wiring(unittest.TestCase):
    def read(self, rel):
        return (JAVA / rel).read_text(encoding='utf-8')

    def test_library_and_sheet(self):
        scan = self.read('ui/LibraryScanner.java')
        self.assertIn('".nsp", ".xci", ".nsz", ".xcz", ".nup"', scan)
        self.assertIn('s.size = o.optLong("size", -1); s.unpack = o.optLong("unpack", 0)', scan)
        self.assertIn('t.base.isCompressed() && s.needsImport() && !s.isCompressed()', scan, 'an uncompressed base wins over a compressed one')
        sheet = self.read('ui/GameSheet.java')
        self.assertIn('importBytes += s.importBytes(); unpackBytes += s.unpack;', sheet)
        self.assertIn('SpaceEstimate.unpackSentence(unpackBytes)', sheet)
        self.assertIn('"Pick the update (.nsp, .nsz, .xci or .xcz)"', sheet)

    def test_build(self):
        svc = self.read('build/BuildService.java')
        self.assertRegex(svc, r'cancelled = true;\s*Importer\.cancel\(true\);', 'Stop reaches the importer')
        self.assertIn('Importer.cancel(false);', svc)
        self.assertIn('Importer.sizes(inputs, r.titleId)', svc)
        self.assertIn('Compressed.percent(step, done, total, unpackBytes + checkBytes, nupBytes, IMPORT_END)', svc)
        self.assertIn('BuildListener.FILE_DAMAGED', svc)
        self.assertIn('deleteUnpacked(tmp);', svc)
        lib = self.read('ui/Library.java')
        self.assertIn('case BuildListener.FILE_DAMAGED:', lib)
        native = self.read('importer/Native.java')
        self.assertIn('public static native void   cancel(boolean on);', native)
        jni = (REPO / 'dm/peanuts/import/nximport_jni.c').read_text(encoding='utf-8')
        self.assertIn('Java_dev_peanuts_importer_Native_cancel(JNIEnv *env, jclass cls, jboolean on)', jni)

    def test_log_bundle_takes_no_game_file(self):
        bundle = self.read('build/BuildLogBundle.java')
        for ext in ('.nsz', '.xcz', '.ncz', '.unpack'):
            self.assertIn(f'n.contains("{ext}")', bundle)


if __name__ == '__main__':
    unittest.main()
