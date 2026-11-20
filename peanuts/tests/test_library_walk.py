"""The library's walk with mods beside the games (issue #46): a game folder that also holds the update and DLC folders and a Mods folder, the way Eden is
set up. A mod for Eden is a folder with exefs/ (patches, sometimes its own main.npdm) and romfs/; the walk took one as a dump folder, and a dump wins over
an NSP of the same title, so Breath of the Wild's base became "!!!BOTW Optimizer, dump folder". dev.peanuts.ui.LibraryWalk runs here on the JVM (javac/java
only, the pattern of test_compressed.py) over a made-up tree: empty files with game-like names, no game data. The source check says the scanner walks with it."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
UI = REPO / 'android/app/src/main/java/dev/peanuts/ui'
SRC = [UI / 'LibraryWalk.java']

RUNNER = r'''package dev.peanuts.ui;
import java.io.File;
public final class LibraryWalkTest {
    public static void main(String[] a) {
        File root = new File(a[0]);
        int cut = root.getPath().length() + 1;
        LibraryWalk.walk(root, 0, new LibraryWalk.Sink() {
            public void dump(File d) { System.out.println("dump " + d.getPath().substring(cut).replace('\\', '/')); }
            public void file(File f) { System.out.println("file " + f.getPath().substring(cut).replace('\\', '/')); }
        });
    }
}
'''

BOTW = 'The Legend of Zelda Breath of the Wild [01007EF00011E000][v0].nsp'
BOTW_UPDATE = 'Updates/The Legend of Zelda_ Breath of the Wild 1.9.0 [01007EF00011E800][v1114112][UPD].nsp'

# The reporter's layout (issue #46), the games as empty files, every mod in one of the layouts Eden and Atmosphere use
TREE = [
    BOTW,
    'Metroid Dread [010093801237C000][v0].xci',
    BOTW_UPDATE,
    'DLC/Some Game Pass [0100A61018681001][v0].nsp',
    'Saves/01007EF00011E000/game_data.sav',
    # Eden's load/ layout, under a folder per game: the mod's own main.npdm (a heap change) beside its RomFS
    'Mods/The Legend of Zelda Breath of the Wild/!!!BOTW Optimizer/exefs/main.npdm',
    'Mods/The Legend of Zelda Breath of the Wild/!!!BOTW Optimizer/romfs/Pack/Bootup.pack',
    # the same, under the title ID
    'Mods/01007EF00011E000/60 FPS/exefs/0123456789abcdef.ips',
    # a mod that replaces the game's code too
    'Mods/0100A61018680000/Full Code Mod/exefs/main',
    'Mods/0100A61018680000/Full Code Mod/exefs/main.npdm',
    # a mod put straight into the game folder: patches only
    'Loose Mod/exefs/main.npdm',
    'Loose Mod/exefs/patch.pchtxt',
    'Loose Mod/romfs/Actor/x.bin',
    # Atmosphere's layout copied as it is
    'atmosphere/contents/01007EF00011E000/exefs/main.npdm',
    # real dump folders: the dump tools' layout, and one version's folder (exefs/ and romfs/ at its top, played with a pack made elsewhere)
    'Kart/base/exefs/main',
    'Kart/base/exefs/main.npdm',
    'Kart/base/romfs/data.bin',
    'Kart/updates/65536/exefs/main',
    'One Version/exefs/main',
    'One Version/exefs/main.npdm',
    'One Version/romfs/data.bin',
]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Walk(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='library_walk_')
        base = Path(cls.tmp.name)
        cls.classes = base / 'classes'
        cls.classes.mkdir()
        runner = base / 'LibraryWalkTest.java'
        runner.write_text(RUNNER, encoding='utf-8')
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.classes), *map(str, SRC), str(runner)], check=True)
        cls.root = base / 'switch'
        for p in TREE:
            f = cls.root / p
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(b'')
        r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', str(cls.classes), 'dev.peanuts.ui.LibraryWalkTest', str(cls.root)],
                           capture_output=True, text=True, encoding='utf-8', check=True)
        lines = r.stdout.splitlines()
        cls.dumps = [l[5:] for l in lines if l.startswith('dump ')]
        cls.files = [l[5:] for l in lines if l.startswith('file ')]

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_mods_are_no_dumps(self):
        self.assertEqual(self.dumps, ['Kart', 'One Version'], 'only the real dump folders')

    def test_nothing_from_a_mods_folder(self):
        self.assertEqual([f for f in self.files if f.lower().startswith(('mods/', 'atmosphere/'))], [])

    def test_the_games_are_still_found(self):
        for p in (BOTW, 'Metroid Dread [010093801237C000][v0].xci', BOTW_UPDATE, 'DLC/Some Game Pass [0100A61018681001][v0].nsp'):
            self.assertIn(p, self.files)


class Source(unittest.TestCase):
    def test_the_scanner_walks_with_it(self):
        s = (UI / 'LibraryScanner.java').read_text(encoding='utf-8')
        self.assertIn('LibraryWalk.walk(root, 0,', s)
        self.assertNotIn('boolean isDump(File', s, 'one dump rule, LibraryWalk\'s')
        self.assertNotIn('listFiles();', s.split('LibraryWalk.walk(')[0].split('// ---- The walk ----')[-1], 'no walk of its own')


if __name__ == '__main__':
    unittest.main()
