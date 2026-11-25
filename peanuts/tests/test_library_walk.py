"""The library's walk with mods beside the games (issue #46): a game folder that also holds the update and DLC folders and a Mods folder, the way Eden is
set up. A mod for Eden is a folder with exefs/ (patches, sometimes its own main.npdm) and romfs/; the walk took one as a dump folder, and a dump wins over
an NSP of the same title, so Breath of the Wild's base became "!!!BOTW Optimizer, dump folder". dev.peanuts.ui.LibraryWalk runs here on the JVM (javac/java
only, the pattern of test_compressed.py) over a made-up tree: empty files with game-like names, no game data. The source check says the scanner walks with it.

The dump folders are found in every layout upstream's DumpLayout reads (igawa6's review of #249): the dump tools' base/ and updates/<version>/, the console's
as OunceCake serves it (base/program/0/), an update alone, and one version's folder (exefs/ at its top)."""
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
public final class LibraryWalkTest {
    public static void main(String[] a) {
        File root = new File(a[0]);
        Map<Long, Map<String, String>> builds = new LinkedHashMap<>();   // the game's builds: <version>=<main's build ID> arguments
        for (int i = 1; i < a.length; i++) { String[] kv = a[i].split("=", 2); builds.put(Long.parseLong(kv[0]), Collections.singletonMap("main", kv[1])); }
        int cut = root.getPath().length() + 1;
        LibraryWalk.walk(root, 0, new LibraryWalk.Sink() {
            public void dump(File d) { System.out.println("dump " + LibraryWalk.versionOf(LibraryWalk.mainBuildId(d), builds) + " " + d.getPath().substring(cut).replace('\\', '/')); }
            public void file(File f) { System.out.println("file " + f.getPath().substring(cut).replace('\\', '/')); }
        });
    }
}
'''

# Main's build ID in a dump (GitHub issue #370): game.tsv keeps its first 16 bytes; the NSO header has 32 at 0x40
V190, V0, OTHER = '0123456789abcdef0123456789abcdef', 'fedcba9876543210fedcba9876543210', '00112233445566778899aabbccddeeff'
BUILDS = {1114112: V190.upper(), 0: V0}   # as game.tsv may write them; the walk compares in lower case


def nso(build):
    h = bytearray(0x100)
    h[:4] = b'NSO0'
    h[0x40:0x50] = bytes.fromhex(build)
    h[0x50:0x60] = bytes(range(16))
    return bytes(h)

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
    'One Version/exefs/sdk',
    'One Version/romfs/data.bin',
    # mods loose in the game folder that bring a whole main of their own (igawa6's review of #249): no sdk, so no dump
    'Loose Code Mod/exefs/main',
    'Loose Code Mod/exefs/main.npdm',
    'Loose Code Mod/exefs/subsdk9',
    'Loose Code Mod/romfs/Actor/x.bin',
    # Eden's load/<title ID>/<mod>/ copied without its load folder
    '01007EF00011E000/60 FPS Code/exefs/main',
    '01007EF00011E000/60 FPS Code/exefs/main.npdm',
    # a mod that loads its own code module beside the game's (subsdk9 and its main.npdm)
    'Plugin Mod/exefs/main.npdm',
    'Plugin Mod/exefs/subsdk9',
    # the console's layout as OunceCake serves it: each version's package at program/0/ (the walk did not see it before)
    'Ounce/base/program/0/exefs/main',
    'Ounce/base/program/0/exefs/main.npdm',
    'Ounce/base/program/0/romfs/data.bin',
    'Ounce/updates/65536/program/0/exefs/main',
    'Ounce/updates/65536/program/0/control/control.nacp',
    # the same with a data content at the program's index: the program's package one level down
    'Ounce Data/base/program/0/program/exefs/main',
    'Ounce Data/base/program/0/program/romfs/data.bin',
    'Ounce Data/base/program/0/data/romfs/x.bin',
    # an update alone that carries the whole game (it was listed as its version's folder, Update Only/updates/196608)
    'Update Only/updates/196608/exefs/main',
    'Update Only/updates/196608/exefs/main.npdm',
    'Update Only/updates/196608/romfs/data.bin',
]

DUMPS = ['Kart', 'One Version', 'Ounce', 'Ounce Data', 'Update Only']

# The mains with an NSO header (the others are empty files): the issue's layout, one version's folder updated to 1.9.0 on a PC; an OunceCake base of the
# base game; a base of a build the game's profile does not list
MAINS = {'One Version/exefs/main': V190, 'Ounce/base/program/0/exefs/main': V0, 'Ounce Data/base/program/0/program/exefs/main': OTHER}
VERSIONS = {'One Version': 1114112, 'Ounce': 0, 'Ounce Data': -1, 'Kart': -1, 'Update Only': -1}


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
            f.write_bytes(nso(MAINS[p]) if p in MAINS else b'')
        r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', str(cls.classes), 'dev.peanuts.ui.LibraryWalkTest', str(cls.root),
                            *(f'{v}={m}' for v, m in BUILDS.items())], capture_output=True, text=True, encoding='utf-8', check=True)
        lines = r.stdout.splitlines()
        cls.versions = {l.split(' ', 2)[2]: int(l.split(' ', 2)[1]) for l in lines if l.startswith('dump ')}
        cls.dumps = list(cls.versions)
        cls.files = [l[5:] for l in lines if l.startswith('file ')]

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_mods_are_no_dumps(self):
        self.assertEqual(sorted(self.dumps), DUMPS, 'only the real dump folders, each once, as the dump and not one of its versions')

    def test_the_version_from_main(self):
        """A dump folder without an updates/<version> folder takes the version of its main's build (GitHub issue #370: one version's folder updated to 1.9.0
        was the base game's version, and the sheet said Wrong version); a main of no listed build, an empty file or none: -1, the base's version stays"""
        self.assertEqual(self.versions, VERSIONS)

    def test_nothing_from_a_mods_folder(self):
        self.assertEqual([f for f in self.files if f.lower().startswith(('mods/', 'atmosphere/'))], [])

    def test_the_games_are_still_found(self):
        for p in (BOTW, 'Metroid Dread [010093801237C000][v0].xci', BOTW_UPDATE, 'DLC/Some Game Pass [0100A61018681001][v0].nsp'):
            self.assertIn(p, self.files)


class Source(unittest.TestCase):
    def test_every_game_has_sdk(self):
        """One version's folder counts as a dump only with sdk beside main (LibraryWalk.wholeCode), because every profile's build checks the SDK's module:
        a folder without it never plays. If a profile without sdk comes, the rule has to change with it"""
        tsvs = sorted((REPO / 'dm/games').glob('*/game.tsv'))
        self.assertTrue(tsvs)
        for tsv in tsvs:
            mods = [l.split('\t')[1].split() for l in tsv.read_text(encoding='utf-8').splitlines() if l.startswith('modules\t')]
            self.assertEqual(len(mods), 1, tsv.parent.name)
            self.assertIn('main', mods[0], tsv.parent.name)
            self.assertIn('sdk', mods[0], tsv.parent.name)
        self.assertIn('return new File(exefs, "main").isFile() && new File(exefs, "main.npdm").isFile() && new File(exefs, "sdk").isFile();',
                      (UI / 'LibraryWalk.java').read_text(encoding='utf-8'))

    def test_the_layouts_are_upstreams(self):
        """The walk finds a version's package where upstream's DumpLayout does (root, versions): the same three places, the same version folders"""
        walk = (UI / 'LibraryWalk.java').read_text(encoding='utf-8')
        up = (REPO / 'android/app/src/main/java/dev/nxnative/android/DumpLayout.java').read_text(encoding='utf-8')
        for line in ('for (String r : new String[] {"", "program/0", "program/0/program"}) {',
                     'if (new File(d, "exefs").isDirectory() || new File(d, "romfs").isDirectory()) return d;',
                     r'u.getName().matches("\\d+") && new File(root(u), "exefs/main").isFile()'):
            self.assertTrue(line in up, 'upstream changed: ' + line)
            self.assertTrue(line in walk, 'walk: ' + line)
        self.assertTrue('new File(root(new File(dump, "base")), "exefs/main").isFile()' in up, 'upstream changed: the base')
        self.assertTrue('new File(root(new File(d, "base")), "exefs/main").isFile()' in walk, 'walk: the base')

    def test_the_scanner_walks_with_it(self):
        s = (UI / 'LibraryScanner.java').read_text(encoding='utf-8')
        self.assertIn('LibraryWalk.walk(root, 0,', s)
        self.assertNotIn('boolean isDump(File', s, 'one dump rule, LibraryWalk\'s')
        self.assertNotIn('listFiles();', s.split('LibraryWalk.walk(')[0].split('// ---- The walk ----')[-1], 'no walk of its own')
        # a dump folder with no updates/<version> takes its main's build's version (GitHub issue #370)
        self.assertIn('if (v == 0) v = Math.max(0, LibraryWalk.versionOf(LibraryWalk.mainBuildId(d), profileBuilds(id)));', s)
        self.assertIn('Method buildMap = profileClass.getDeclaredMethod("buildMap");', s)
        self.assertIn('Map<Long, Map<String, String>> buildMap() {', (REPO / 'android/app/src/main/java/dev/nxnative/android/Profile.java').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
