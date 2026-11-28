"""The Frontend folder (dev.peanuts.launch.FrontendFolder, FrontendEntry; GitHub issue #475): the entries' names and texts on the JVM
(android/app/src/jvmtest's FrontendEntryJvmTest; skipped without a JDK), and against the sources: the one upstream hook in FrontendLaunch and its place,
the upstream names the folder reads by reflection (Game's list, its preferences, Game.ready), the places that bring the folder up to date, the Settings
row and its place, and that the folder changes only the app's own files."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
UP = APP / 'main/java/dev/nxnative/android'
LAUNCH = APP / 'main/java/dev/peanuts/launch'
UI = APP / 'main/java/dev/peanuts/ui'
FOLDERS = APP / 'main/java/dev/peanuts/folders'


def read(p):
    return p.read_text(encoding='utf-8')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='frontendentry_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(LAUNCH / 'FrontendEntry.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/launch/FrontendEntryJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.launch.FrontendEntryJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Hook(unittest.TestCase):
    def test_one_marked_line_before_the_title_id_launch(self):
        src = read(UP / 'FrontendLaunch.java')
        lines = [l for l in src.splitlines() if 'FrontendFolder' in l]
        self.assertEqual(len(lines), 1, lines)
        self.assertIn('PEANUTS', lines[0])
        self.assertRegex(lines[0], r'^\s+if \(tid == null\) tid = dev\.peanuts\.launch\.FrontendFolder\.titleIdOf\(this, i\);')
        at = src.index(lines[0])
        # after the extra title_id, before the picked .nup, the All files access check and the path: an entry starts as its title ID does
        self.assertLess(src.index('String tid = Frontend.titleId(i.getStringExtra("title_id"));'), at)
        for later in ('Game pg = tid == null ? pickedGame(i) : null;', 'if (!Environment.isExternalStorageManager() && !copiedGame(tid))',
                      'if (tid != null) { byTitleId(tid); return; }', 'String path = pathOf(i);'):
            self.assertLess(at, src.index(later), later)

    def test_listed_in_peanuts_md(self):
        self.assertIn('FrontendFolder.titleIdOf', read(REPO / 'PEANUTS.md'))
        self.assertIn('FrontendFolder.titleIdOf', read(REPO / 'peanuts/UPSTREAM-EDITS.md'))


class UpstreamNames(unittest.TestCase):
    """What FrontendFolder reaches by reflection or by name in upstream's Game: a rename there would leave the folder quietly unchanged"""

    def test_game(self):
        game = read(UP / 'Game.java')
        self.assertRegex(game, r'\n\s+static Game fromJson\(JSONObject o\) throws JSONException \{')
        self.assertIn('return new Game(o.getString("title_id"), o.getString("name"), o.getString("game"), o.getString("pack"), o.optLong("used", 0));', game)
        self.assertRegex(game, r'\n\s+static boolean ready\(Game g\) \{')
        self.assertIn('private static SharedPreferences prefs(Context c) { return Prefs.open(c, "games"); }', game)
        self.assertIn('new JSONArray(prefs(c).getString("list", "[]"))', game)
        self.assertIn('prefs(c).edit().putString("list", a.toString()).commit();', game)
        self.assertIn('static SharedPreferences open(Context c, String name) { return c.getSharedPreferences(name, Context.MODE_MULTI_PROCESS); }',
                      read(UP / 'Prefs.java'))
        folder = read(LAUNCH / 'FrontendFolder.java')
        self.assertIn('LIST_PREFS = "games", LIST_KEY = "list";', folder)
        self.assertIn('getSharedPreferences(LIST_PREFS, Context.MODE_MULTI_PROCESS).registerOnSharedPreferenceChangeListener(onList)', folder)
        for name in ('getDeclaredMethod("fromJson", JSONObject.class)', 'getDeclaredMethod("ready", g.getClass())', 'o.getString("title_id")', 'o.getString("name")'):
            self.assertIn(name, folder)

    def test_a_list_that_does_not_read_whole_changes_nothing(self):
        folder = read(LAUNCH / 'FrontendFolder.java')
        listed = folder[folder.index('private static Map<String, Listed> listed('):folder.index('/** Title ID → name of every game')]
        self.assertNotIn('"load"', listed, 'Game.load gives the cards before a damaged one: the list is read strictly instead')
        self.assertIn('new JSONArray(', listed)
        self.assertIn('list.getJSONObject(i)', listed)
        run = folder[folder.index('private static void syncNow('):folder.index('/** The folders turned off or swapped for another')]
        self.assertLess(run.index('games = listed(c);'), run.index('.delete()'))
        self.assertIn('catch (ReflectiveOperationException | JSONException | RuntimeException e) {', run)
        self.assertIn('return;', run[run.index('games = listed(c);'):run.index('.delete()')])

    def test_launch_alias_takes_the_extra_game(self):
        src = read(UP / 'FrontendLaunch.java')
        self.assertIn('String p = i.getStringExtra("game");', src)
        self.assertIn('i.getStringExtra("game")', read(LAUNCH / 'FrontendFolder.java'))


class WhenItRuns(unittest.TestCase):
    def test_places(self):
        self.assertIn('dev.peanuts.launch.FrontendFolder.watch(a);', read(UI / 'Library.java'))
        self.assertIn('dev.peanuts.launch.FrontendFolder.changed(a);', read(UI / 'Library.java'))
        build = read(APP / 'main/java/dev/peanuts/build/BuildService.java')
        self.assertLess(build.index('progress("done", 100, message);'), build.index('dev.peanuts.launch.FrontendFolder.changed(this);'))
        # the kind's resumed hook, which LibraryFolders runs from LibrarySettings.resume
        self.assertIn('@Override public void resumed(Activity a) { FrontendFolder.resumed(a); }', read(FOLDERS / 'FolderKinds.java'))
        self.assertIn('LibraryFolders.resumed(a);', read(UI / 'LibrarySettings.java'))


class OwnFilesOnly(unittest.TestCase):
    def test_deletes_and_renames_only_its_own(self):
        src = read(LAUNCH / 'FrontendFolder.java')
        # every delete is of a file ours() or leftovers() found by its text, or of the run's own fresh part file
        for m in re.finditer(r'(\w+)\.delete\(\)', src):
            self.assertIn(m.group(1), ('f', 'part', 'step'), src[max(0, m.start() - 200):m.end()])
        self.assertIn('return FrontendEntry.titleIdOfText(read(in), appId);', src[src.index('private static String idOf('):])
        ours = src[src.index('private static Map<String, List<File>> ours('):src.index('/** The part files a run')]
        self.assertIn('idOf(f, appId)', ours)
        left = src[src.index('private static void leftovers('):src.index('private static boolean isPart(')]
        self.assertIn('idOf(f, appId) != null', left)
        self.assertIn('if (fs != null) for (File f : fs) if (f.isFile()) out.add(f);', src)
        self.assertEqual(src.count('listFiles('), 1, 'one listing only: the folder\'s top level')
        # a name taken by another file is left as it is; part files are made new, never opened over a file that was there
        self.assertIn('if (target.exists()) {', src)
        self.assertIn('if (!same(from, to)) {', src)
        self.assertIn('if (part.createNewFile()) return part;', src)
        self.assertIn('if (!f.exists() && part.renameTo(f)) return true;', src)
        self.assertEqual(src.count('new FileOutputStream('), 1)
        self.assertIn('File part = fresh(f);', src[src.index('private static boolean write('):])

    def test_a_folder_turned_off_is_cleared_later_when_it_cannot_be_now(self):
        src = read(LAUNCH / 'FrontendFolder.java')
        setter = src[src.index('public static void set('):src.index('/** Settings shows the row')]
        self.assertIn('clear.add(old)', setter)
        self.assertIn('clear.remove(dir)', setter)
        self.assertIn('if (!clear(c, new File(p))) still.add(p);', src)
        self.assertIn('frontendClear', read(APP / 'main/java/dev/peanuts/PeanutsPrefs.java'))


class Settings(unittest.TestCase):
    def test_row_and_place(self):
        # a kind of Library › Folders' folder list (issue #546): request 74, one folder at most, after the build folders
        kinds = read(FOLDERS / 'FolderKinds.java')
        self.assertRegex(kinds, r'FRONTEND = new FolderKind\("frontend", "Frontend folder", "Frontend", "external",[^;]*?, 74, 1, false,')
        self.assertIn('Arrays.asList(GAMES, ADDONS, BUILDS, FRONTEND)', kinds)
        for other in ('REQ_SAVE = 71', 'REQ_ZIP = 72, REQ_DIR = 73'):
            self.assertIn(other, read(UI / 'BuildLogUi.java') + read(UI / 'PackImportUi.java'))
        # 74 is outside LibrarySettings' own range (60 to 70): its picker's answer finds the kind before that range is checked
        result = read(UI / 'LibrarySettings.java')
        result = result[result.index('public static boolean result('):]
        self.assertLess(result.index('FolderKinds.ofRequest(request);'), result.index('if (request < REQ_KEYS || request > REQ_MOD_ZIP) return false;'))
        # All files access is asked for with the kind's own line (it writes into the folder)
        self.assertIn('LibrarySettings.askAccess(a, k.hooks.accessNeed(', read(UI / 'LibraryFolders.java'))
        self.assertIn("to write the games' files into", kinds)
        sections = read(UI / 'SettingsSections.java')
        self.assertRegex(sections, r'r\("Build folder", "build"\),\s+r\("Frontend folder", "external"\)')
        self.assertIn('put(m, "Frontend folder", ', read(UI / 'SettingsHelp.java'))
        self.assertTrue((APP / 'peanuts/res/drawable/pi_external.xml').is_file() or any(APP.glob('*/res/drawable*/pi_external.xml')))


if __name__ == '__main__':
    unittest.main()
