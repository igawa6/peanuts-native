"""Import pack… before the first build (GitHub issue #44; dev.peanuts.pack.FirstImport, PackImport and its Upstream, dev.peanuts.ui.PackImportUi).

On the JVM (FirstImportJvmTest): the rules and the words, and the whole import through upstream's own PackZip, Pack, BuildCheck and SaveStore: a pack folder
and a zip in, into the app's packs/<id> and into a build folder's <id>/pack (staged beside it), the old pack kept until the new one is in, a pack of another
version or another game refused with nothing written, the Storage tab's import, a running game, another list entry on the same pack folder and an unfinished
Prepare job refusing it, a picked pack folder let go, a dump folder the pack fits listed (ready to play) and one of another build refused. The upstream classes
that need Android are stand-ins written here; the source checks below say that upstream's still have the names and parameters Upstream.java reflects on, and
that Build still takes a pack it finds where the import puts it. The hand-over to Build: a real .nup (dm/tools/nup_pack.py of a dump made here, the profile's
build in its update) put where Build writes it, and upstream's own PackFind and BuildCheck choose the imported pack for it, the way Folders.packFor does for
Prepare; a .nup of another build gets none. Skipped without a JDK."""
import hashlib
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UP = APP / 'main/java/dev/nxnative/android'
OND = APP / 'ondevice/java/dev/nxnative/android'
OWN = APP / 'main/java/dev/peanuts'
SRC = [OWN / 'pack' / n for n in ('FirstImport.java', 'PackImport.java', 'Upstream.java')]
REAL = [UP / f'{n}.java' for n in ('PackZip', 'Pack', 'DumpLayout', 'BuildCheck', 'RomfsCheck', 'Nup', 'Nsp', 'Zstd', 'Profile', 'ShaderLibs', 'SaveStore', 'PackFind')]
TEST = APP / 'jvmtest/java/dev/peanuts/pack/FirstImportJvmTest.java'

BUILD_CONFIG = '''package dev.nxnative.android;
public final class BuildConfig {
    public static final String NX_PROFILES = "040074A01BF12000\\tDonkey Kong Bananza\\t22852\\tabcdef012345\\t458752 main=aa\\n0400C3F00006E000\\tMario Kart World\\t0\\tunknown\\t";
    public static final String NX_DMSH_VK_CHAIN = "c0ffee", NX_DMSH_VK_ABI = "abi1", NX_DMSH_VK_ABI_COMPAT = "";
}
'''

# Stand-ins for upstream's classes that need Android: each method has the parameters of upstream's (checked by Reflection.test_names); their state is public
STUBS = {
    'android/content/Context.java': 'package android.content;\npublic abstract class Context {}\n',
    'android/util/Log.java': '''package android.util;
public final class Log {
    public static int i(String t, String m) { return 0; }
    public static int w(String t, String m) { return 0; }
    public static int w(String t, String m, Throwable e) { return 0; }
}
''',
    'dev/nxnative/android/Game.java': '''package dev.nxnative.android;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
public final class Game {
    public static final List<Game> LIST = new ArrayList<>();
    final String titleId, name, gameDir, packDir;
    Game(String titleId, String name, String gameDir, String packDir) { this.titleId = titleId; this.name = name; this.gameDir = gameDir; this.packDir = packDir; }
    static List<Game> load(Context c) { return new ArrayList<>(LIST); }
    static Game put(Context c, Game g) { LIST.removeIf(x -> x.gameDir.equals(g.gameDir)); LIST.add(g); return g; }
}
''',
    'dev/nxnative/android/GameList.java': '''package dev.nxnative.android;
import java.io.File;
import java.io.IOException;
final class GameList {
    static boolean samePath(String a, String b) {
        if (a == null || b == null) return false;
        try { return new File(a).getCanonicalPath().equals(new File(b).getCanonicalPath()); } catch (IOException e) { return a.equals(b); }
    }
}
''',
    'dev/nxnative/android/PackUi.java': '''package dev.nxnative.android;
public final class PackUi {
    public static boolean BUSY;
    static synchronized boolean busy(String titleId) { return BUSY; }
}
''',
    'dev/nxnative/android/SaveUi.java': '''package dev.nxnative.android;
import android.content.Context;
public final class SaveUi {
    public static boolean RUNNING;
    static boolean gameRunning(Context c, Game g) { return RUNNING; }
}
''',
    'dev/nxnative/android/Folders.java': '''package dev.nxnative.android;
import android.content.Context;
import java.util.HashMap;
import java.util.Map;
public final class Folders {
    public static final Map<String, String> PICKED = new HashMap<>();
    static String pickedPack(Context a, String id) { return PICKED.get(id); }
    static void pickedPack(Context a, String id, String path) { if (path == null) PICKED.remove(id); else PICKED.put(id, path); }
}
''',
    'dev/nxnative/android/Prefs.java': '''package dev.nxnative.android;
import android.content.Context;
import java.util.HashMap;
import java.util.Map;
public final class Prefs {
    public static final Map<String, String> FOLDERS = new HashMap<>();
    static String folder(Context c, String key) { return FOLDERS.get(key); }
    static void folder(Context c, String key, String path) { if (path == null) FOLDERS.remove(key); else FOLDERS.put(key, path); }
}
''',
    'dev/nxnative/android/DumpCheck.java': '''package dev.nxnative.android;
import java.io.File;
final class DumpCheck {
    static File workOf(File dump, String titleId) {
        File parent = dump.getAbsoluteFile().getParentFile();
        return new File(parent != null ? parent : dump, titleId + "-work");
    }
}
''',
    # job.json here is one line, "<state>\\t<pack folder>" (Job.read's own format is JSON; the import asks only state() and pack())
    'dev/nxnative/android/Job.java': '''package dev.nxnative.android;
import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
public final class Job {
    public static File REMEMBERED;
    private final String state;
    private final File pack;
    private Job(String state, File pack) { this.state = state; this.pack = pack; }
    static File fileOf(File work) { return new File(new File(work, "ondevice"), "job.json"); }
    static File remembered(Context c) { return REMEMBERED; }
    static Job read(File f) throws IOException {
        String[] c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim().split("\\t");
        if (c.length != 2) throw new IOException(f + " is not a job");
        return new Job(c[0], new File(c[1]));
    }
    synchronized String state() { return state; }
    synchronized File pack() { return pack; }
}
''',
}


def text(p):
    return p.read_text(encoding='utf-8')


def method(src, signature):
    """A method of a Java source at the class's indentation: from its signature to its closing brace"""
    start = src.index(signature)
    return src[start:src.index('\n    }\n', start) + 6]


class Reflection(unittest.TestCase):
    """Upstream.java reaches upstream's package-private classes by name: each name and its parameters, as upstream's sources have them"""

    def test_names(self):
        want = {
            UP / 'PackZip.java': [r'static String titleOf\(File source\) throws IOException', r'static Pack stage\(File source, File packs, String want\) throws IOException',
                                  r'static void swapIn\(File part, File target\) throws IOException'],
            UP / 'SaveStore.java': [r'static void deleteTree\(File f\)'],
            UP / 'Pack.java': [r'final File dir;', r'final String titleId, name, version;', r'static String libraryProblem\(File dir, String titleId\)',
                               r'static File playableLibrary\(File dir, String titleId\)', r'static File legacyLibrary\(File dir, String titleId\)'],
            UP / 'BuildCheck.java': [r'static Result build\(Pack p, File game\)', r'final Kind kind;', r'final String message, detail;',
                                     r'enum Kind \{ OK, OTHER_BUILD, MISSING, UNREADABLE, UNSUPPORTED, ROMFS \}'],
            UP / 'PackUi.java': [r'static synchronized boolean busy\(String titleId\)'],
            UP / 'SaveUi.java': [r'static boolean gameRunning\(Context c, Game g\)'],
            UP / 'Game.java': [r'final String titleId, name, gameDir, packDir;', r'Game\(String titleId, String name, String gameDir, String packDir\) \{',
                               r'static List<Game> load\(Context c\)', r'static Game put\(Context c, Game g\) \{'],
            UP / 'GameList.java': [r'static boolean samePath\(String a, String b\)'],
            UP / 'Folders.java': [r'static String pickedPack\(Context a, String id\)', r'static void pickedPack\(Context a, String id, String path\)'],
            UP / 'Prefs.java': [r'static String folder\(Context c, String key\)', r'static void folder\(Context c, String key, String path\)'],
            OND / 'DumpCheck.java': [r'static File workOf\(File dump, String titleId\)'],
            OND / 'Job.java': [r'static File fileOf\(File work\)', r'static File remembered\(Context c\)', r'static Job read\(File f\) throws IOException',
                               r'synchronized String state\(\)', r'synchronized File pack\(\)'],
        }
        for f, pats in want.items():
            src = text(f)
            for p in pats:
                self.assertRegex(src, p, f'{f.name}: {p}')

    def test_reflected_names_are_checked(self):
        """Every class Upstream.java names is one test_names reads: a new reflective call comes with its check"""
        up = text(OWN / 'pack/Upstream.java')
        named = set(re.findall(r'k\("(\w+)"\)', up))
        self.assertEqual(named, {'PackZip', 'SaveStore', 'Pack', 'BuildCheck', 'PackUi', 'SaveUi', 'Game', 'GameList', 'Folders', 'Prefs', 'DumpCheck', 'Job'})

    def test_stubs_match(self):
        """The stand-ins have upstream's parameters (the JVM test calls them through Upstream.java, as the app calls upstream's)"""
        for rel, src in STUBS.items():
            if not rel.startswith('dev/'):
                continue
            real = text((OND if rel.endswith(('DumpCheck.java', 'Job.java')) else UP) / Path(rel).name)
            for sig in re.findall(r'^\s*((?:static |synchronized )+[\w<>]+ \w+\([^)]*\))', src, re.M):
                self.assertIn(sig.strip(), real, f'{rel}: {sig.strip()}')


class Wiring(unittest.TestCase):
    def test_build_takes_the_pack_where_it_goes(self):
        """The import puts the pack where Build looks first: Prepare adopts a pack that Folders.packFor finds (AppStorage.pack, the build folders) or the Build
        destination's, and only makes what does not fit"""
        prep = text(OWN / 'build/Prepare.java')
        self.assertIn('try { packDir = Upstream.packFor(c, dumpR, id); }', prep)
        self.assertIn('job = Upstream.jobAdopt(r, packDir, code, sh);', prep)
        self.assertIn('File packDir = destination;', prep)
        svc = text(OWN / 'build/BuildService.java')
        self.assertIn('File pack = dir == null ? null : new File(dir, "pack");', svc)
        self.assertIn('return r.buildDestination == null ? PeanutsPrefs.buildGameDir(this, r.titleId) : PeanutsPrefs.buildGameDir(r.buildDestination, r.titleId);', svc)
        folders = text(UP / 'Folders.java')
        self.assertIn('PackFind.candidates(picked, AppStorage.pack(a, id), game, id, PackFind.volumeRoot(game))', folders)
        ui = text(OWN / 'ui/PackImportUi.java')
        self.assertIn('File dest = chosen == null ? null : PeanutsPrefs.buildGameDir(chosen, t.titleId);', ui)
        self.assertIn('File target = dest != null ? new File(dest, "pack") : dev.nxnative.android.AppStorage.pack(a, t.titleId);', ui)
        # the job Build would go on with: an NSP's .nup goes into the build folder's <title ID> when one applies, and its work folder beside it
        self.assertIn('File dir = again ? importDest : buildGameDir != null ? buildGameDir : new File(r.gameDir, r.fileName())', svc)
        self.assertIn('File buildDir = dest != null ? dest : PeanutsPrefs.buildGameDir(a, t.titleId);', ui)
        self.assertIn('if (chosenUpdate != null && chosenUpdate.needsImport()) { s.update = chosenUpdate.file;', text(OWN / 'ui/GameSheet.java'))
        self.assertIn('long version = chosenUpdate != null && chosenUpdate.needsImport() ? chosenUpdate.version : t.base.version;', ui)

    def test_entry_points(self):
        sheet = text(OWN / 'ui/GameSheet.java')
        self.assertIn('if (PackImportUi.offered(title)) {', sheet)
        self.assertIn('PUi.button(a, "Import pack…", PUi.Kind.QUIET', sheet)
        lib = text(OWN / 'ui/LibrarySettings.java')
        self.assertIn('if (PackImportUi.result(a, request, result, data)) return true;', lib)
        self.assertLess(lib.index('PackImportUi.result('), lib.index('if (request < REQ_KEYS || request > REQ_MOD_ZIP) return false;'))
        self.assertIn('if (dev.peanuts.pack.PackImport.busy(s.titleId))', text(OWN / 'ui/Library.java'))

    def test_request_codes_are_free(self):
        """72 and 73 are no other picker's: LibrarySettings' 60 to 70, BuildLogUi's 71, upstream's own (MainActivity, PackUi, SaveUi, Folders, GameCopy...)"""
        used = {}
        for f in list((APP / 'main/java').rglob('*.java')) + list((APP / 'ondevice/java').rglob('*.java')):
            if f.name == 'PackImportUi.java':
                continue
            for name, value in re.findall(r'\b((?:REQ|PICK|REQUEST)\w*)\s*=\s*(\d+)', text(f)):
                used.setdefault(int(value), []).append(f'{f.name}:{name}')
        for code in (72, 73):
            self.assertNotIn(code, used, used.get(code))

    def test_ctl(self):
        req = text(OWN / 'ctl/Request.java')
        self.assertIn('"import_pack"', req)
        self.assertIn('case "import_pack": titleId(true); need("path"); break;\n', req)
        # the comment about the question stays on reimport's line, where #44 had moved it from (igawa6's review of #260)
        self.assertIn('case "reimport": titleId(true); flag("confirm"); flag("delete_first"); break;   // the question itself is answered where the plan is known '
                      '(CtlPort.reimport: needs_confirm)\n', req)
        self.assertIn('case "import_pack": importPack(a, r, res); break;', text(OWN / 'ctl/Ctl.java'))
        self.assertIn('`import_pack`', text(REPO / 'peanuts/docs/CONTROL.md'))

    def test_busy_has_one_spelling(self):
        """Every use of PackImport's busy set goes through key(): busy() upper-cased the title ID and take() and give() did not (igawa6's review of #260)"""
        src = text(OWN / 'pack/PackImport.java')
        uses = re.findall(r'BUSY\.(add|remove|contains)\((.*?)\);', src)
        self.assertEqual(sorted(u[0] for u in uses), ['add', 'contains', 'remove'])
        for op, arg in uses:
            self.assertEqual(arg, 'key(titleId)', op)

    def test_checked_again_after_the_picker(self):
        """The sheet's checks run again when the picker answers, against the library as it is then, and once more right before the import with the title
        taken on the main thread, before the import's own thread starts (igawa6's review of #260: they ran only before the picker, so a build started while
        it was open could still have had its pack swapped). A pack that came into the pack folder meanwhile is asked about"""
        ui = text(OWN / 'ui/PackImportUi.java')
        result = method(ui, 'static boolean result(')
        self.assertIn('picked(a, p, new File(path));', result)
        self.assertNotIn('start(', result)
        picked = method(ui, 'private static void picked(')
        self.assertIn('LibraryScanner.Title t = lib == null ? null : lib.title(p.titleId);', picked)
        self.assertIn('String[] no = refusal(a, lib, t, p.titleId);', picked)
        self.assertIn('if (!p.asked(g.target) && new File(g.target, "pack.tsv").isFile())', picked)
        start = method(ui, 'static void start(')
        order = [start.index(s) for s in ('= refusal(a, lib,', 'wait.show();', 'if (!PackImport.take(g.titleId))', 'new Thread(', 'PackImport.run(')]
        self.assertEqual(order, sorted(order), 'checked, taken, then the thread')
        self.assertEqual(start.count('UI.post('), 1, 'nothing between the check and the take runs later on the main thread')
        imp = text(OWN / 'pack/PackImport.java')
        self.assertIn('if (!busy(g.titleId)) throw new IllegalStateException(', imp)
        self.assertNotIn('if (!take(', imp, 'run takes nothing: the caller took the title on its main thread')
        self.assertIn('if (dev.peanuts.pack.PackImport.busy(s.titleId))', text(OWN / 'ui/Library.java'))

    def test_the_pick_outlives_the_process(self):
        """Android may end the process while the system picker is open; its answer then comes to a new process, where the static pick is gone (igawa6's
        review of #260: the pick did nothing and said nothing). The pick is kept in PeanutsPrefs before the picker opens and read back when the memory has
        none, and an answer that comes before the library's first scan waits for it: both ends of a scan (done or failed) let it go on"""
        ui = text(OWN / 'ui/PackImportUi.java')
        choose = method(ui, 'private static void choose(')
        self.assertLess(choose.index('PeanutsPrefs.packImportPick(a, pending.text());'), choose.index('LibrarySettings.pickFolder('))
        result = method(ui, 'static boolean result(')
        self.assertIn('FirstImport.Pick p = pending != null ? pending : FirstImport.Pick.of(PeanutsPrefs.packImportPick(a));', result)
        self.assertIn('PeanutsPrefs.packImportPick(a, null);', result)
        self.assertLess(result.index('PeanutsPrefs.packImportPick(a, null);'), result.index('return true;'), 'read once, whatever the answer')
        self.assertIn('if (!Library.scannedOnce() && PeanutsPrefs.gameDir(a) != null) {', result)
        self.assertIn('if (p != null) picked(a, p, source);', method(ui, 'static void scanned(Activity a) {'))
        self.assertIn('static boolean scannedOnce() { return everScanned; }', text(OWN / 'ui/Library.java'))
        scan = method(text(OWN / 'ui/Library.java'), 'private void scan() {')
        self.assertEqual(scan.count('PackImportUi.scanned(a);'), 2, 'after the scan, and after a failed one')
        prefs = text(OWN / 'PeanutsPrefs.java')
        self.assertIn('public static String packImportPick(Context c) { return of(c).getString("pack_import_pick", null); }', prefs)
        self.assertIn('public static void packImportPick(Context c, String pick) { put(c, "pack_import_pick", pick); }', prefs)

    def test_no_upstream_file_changed_for_it(self):
        """The feature reaches upstream only through reflection: no upstream source names it"""
        for f in list(UP.glob('*.java')) + list(OND.glob('*.java')):
            src = text(f)
            self.assertNotIn('PackImport', src, f.name)
            self.assertNotIn('FirstImport', src, f.name)


TID, VER = 0x040074A01BF12000, 458752   # the stub BuildConfig's Donkey Kong Bananza and its update


def nso(build):
    """A valid NSO (three uncompressed segments with their hashes, as dm/tools/tests/test_nup.py's module) whose build ID is build"""
    hdr, body, mo = bytearray(0x100), b'', 0
    hdr[:4] = b'NSO0'; hdr[0x40:0x60] = build
    for i, s in enumerate((bytes(range(256)) * 20 + build, b'read-only ' * 300, b'\x01\x02' * 0x900)):
        struct.pack_into('<III', hdr, 0x10 + i * 0x10, 0x100 + len(body), mo, len(s)); struct.pack_into('<I', hdr, 0x60 + 4 * i, len(s))
        hdr[0xa0 + 0x20 * i:0xc0 + 0x20 * i] = hashlib.sha256(s).digest()
        body += s; mo = (mo + len(s) + 0xfff) & ~0xfff
    return bytes(hdr) + body


def npdm(tid):
    d = bytearray(0x100)
    d[:4] = b'META'
    struct.pack_into('<II', d, 0x70, 0x80, 0x20)
    d[0x80:0x84] = b'ACI0'
    struct.pack_into('<Q', d, 0x90, tid)
    return bytes(d)


def game_nup(root, seed):
    """The .nup Build would make of an NSP and its update: base and update VER, the update's main of FirstImportJvmTest.nso(seed)'s build (22: the profile's)"""
    dump = root / f'dump-{seed}'
    for sub, build in (('base', bytes(32)), (f'updates/{VER}', bytes((seed * 31 + i) & 0xff for i in range(32)))):
        (dump / sub / 'exefs').mkdir(parents=True); (dump / sub / 'romfs').mkdir()
        (dump / sub / 'exefs/main').write_bytes(nso(build)); (dump / sub / 'exefs/main.npdm').write_bytes(npdm(TID))
        (dump / sub / 'romfs/data.bin').write_text(f'{sub}\n', encoding='utf-8')
    out = root / f'game-{seed}.nup'
    r = subprocess.run([sys.executable, str(REPO / 'dm/tools/nup_pack.py'), '--raw', str(dump), str(out)], capture_output=True, text=True)
    if r.returncode: raise AssertionError(r.stdout + r.stderr)
    return out


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class FirstImportJvm(unittest.TestCase):
    def test_import(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            nups = [str(game_nup(d, 22)), str(game_nup(d, 9))]   # the pack's build, another
            stub = d / 'stub'
            for rel, src in {**STUBS, 'dev/nxnative/android/BuildConfig.java': BUILD_CONFIG}.items():
                (stub / rel).parent.mkdir(parents=True, exist_ok=True)
                (stub / rel).write_text(src, encoding='utf-8')
            srcs = [str(p) for p in SRC + REAL + [TEST]] + [str(p) for p in stub.rglob('*.java')]
            c = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *srcs], capture_output=True, text=True)
            self.assertEqual(c.returncode, 0, c.stdout + c.stderr)
            (d / 'run').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.pack.FirstImportJvmTest', str(d / 'run'), *nups], capture_output=True, text=True, timeout=300)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertTrue(r.stdout.startswith('ok '), r.stdout)


if __name__ == '__main__':
    unittest.main()
