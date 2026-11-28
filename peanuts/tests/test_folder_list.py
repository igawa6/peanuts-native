"""Several game, update and DLC, and build folders (issue #546, dev.peanuts.folders): the list logic on the JVM (android/app/src/jvmtest's FolderListJvmTest;
skipped without a JDK), and against the sources: an install that had one folder of each keeps it (the first stays in its old key), every place that read the one
game, update or DLC folder reads the lists, the folder pickers keep their request codes, and the new text keeps UI-RULES.md."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
PEANUTS = APP / 'main/java/dev/peanuts'
FOLDERS = PEANUTS / 'folders'


def src(rel):
    return (PEANUTS / rel).read_text(encoding='utf-8')


def between(text, start, end):
    i = text.index(start)
    return text[i:text.index(end, i)]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory(prefix='folderlist_') as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(FOLDERS / 'FolderList.java'),
                            str(APP / 'jvmtest/java/dev/peanuts/folders/FolderListJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.folders.FolderListJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertRegex(r.stdout, r'^ok \d+')


class Keep(unittest.TestCase):
    def test_an_older_install_keeps_its_folders(self):
        """The one game folder and the update and DLC folders an older version kept are the lists' first entries: nothing to move, nothing lost"""
        prefs = src('PeanutsPrefs.java')
        games = between(prefs, 'public static List<String> gameDirs(Context c) {', '\n    }\n')
        self.assertIn('getString("game_dir", null)', games)
        addons = between(prefs, 'public static List<String> addonDirs(Context c) {', '\n    }\n')
        self.assertIn('p.getString("update_dir", null), p.getString("dlc_dir", null)', addons)
        self.assertIn('list(c, new String[] {"game_dir"}, "game_dirs_extra", dirs)', prefs)
        self.assertIn('list(c, new String[] {"update_dir", "dlc_dir"}, "addon_dirs_extra", dirs)', prefs)
        lst = between(prefs, 'private static void list(Context c, String[] singles, String extraKey, List<String> dirs) {', '\n    }\n')
        self.assertIn('if (i < dirs.size()) e.putString(singles[i], dirs.get(i)); else e.remove(singles[i]);', lst,
                      'the second update and DLC folder stays in dlc_dir, so 0.1.8 still finds its DLC folder (igawa6, #696)')
        self.assertIn('for (int i = singles.length; i < dirs.size(); i++) extra.put(dirs.get(i));', lst, 'the extra list starts after them')
        roots = between(prefs, 'public static List<File> roots(Context c) {', '\n    }\n')
        for f in ('gameDirs(c)', 'addonDirs(c)', 'buildDirs(c)'):
            self.assertIn(f, roots, f'the scan reads {f}')

    def test_no_reader_of_one_folder_is_left(self):
        java = list(PEANUTS.rglob('*.java')) + list((APP / 'main/java/dev/nxnative/android').glob('*.java'))
        for f in java:
            text = f.read_text(encoding='utf-8')
            self.assertNotRegex(text, r'PeanutsPrefs\.(updateDir|dlcDir)\(', f'{f.name} reads the one update or DLC folder')
            self.assertNotIn('extraFolders(', text, f'{f.name}: the first-run sheet of the two folders is gone')
        # where an import writes: the game folder that holds the game's file (PeanutsPrefs.homeFor), in every place that starts a build or estimates it
        for rel, n in (('ui/Library.java', 2), ('ui/BuildPort.java', 1), ('ui/GameSheet.java', 1), ('ui/LibraryScanner.java', 1)):
            self.assertEqual(src(rel).count('PeanutsPrefs.homeFor('), n, rel)
        scan = between(src('ui/LibraryScanner.java'), 'List<Title> scan() {', '\n    }\n')
        self.assertIn('walk(root, sources, seen)', scan, 'a folder inside another: each file once')
        share = src('build/BuildLogShare.java')
        self.assertIn('for (String gd : dev.peanuts.PeanutsPrefs.gameDirs(c)) {', share)

    def test_pickers_keep_their_request_codes(self):
        kinds = (FOLDERS / 'FolderKinds.java').read_text(encoding='utf-8')
        codes = dict(re.findall(r'new FolderKind\("(\w+)",[^;]*?"Pick [^"]+", (\d+),', kinds, re.S))
        self.assertEqual(codes, {'games': '61', 'addons': '62', 'builds': '68'})
        lib = src('ui/LibrarySettings.java')
        self.assertIn('FolderKinds.ofRequest(request)', lib)
        self.assertNotRegex(lib, r'\bREQ_(GAME|UPDATE_DIR|DLC_DIR|BUILD_DIR)\b')

    def test_settings_rows(self):
        lib = src('ui/LibrarySettings.java')
        add = between(lib, 'public static void add(Activity a, LinearLayout content)', 'private static CharSequence companionsValue')
        self.assertIn('LibraryFolders.rows(a, card);', add)
        folders = src('ui/LibraryFolders.java')
        self.assertIn('SettingsPage.adopt(r, titleOf(r));', folders, 'a row added later has its icon and explanation')
        self.assertIn('SettingsPage.drop(r);', folders)
        self.assertIn('k.hooks.removeNote()', folders)
        self.assertIn('Remove takes the folder off the list. The files in it stay where they are.', (FOLDERS / 'FolderKind.java').read_text(encoding='utf-8'))
        self.assertNotRegex(folders, r'\.delete\(\)', 'removing a folder never deletes a file')


class Text(unittest.TestCase):
    def test_ui_rules(self):
        for f in (PEANUTS / 'ui/LibraryFolders.java', FOLDERS / 'FolderKinds.java', FOLDERS / 'FolderKind.java'):
            text = f.read_text(encoding='utf-8')
            strings = ''.join(re.findall(r'"((?:[^"\\]|\\.)*)"', text))
            for bad in ('·', '•', ' - ', '–', '—', '!', '…'):
                self.assertNotIn(bad, strings, f'{f.name}: {bad!r} in its text')
            self.assertIsNone(re.search('[\U0001F300-\U0001FAFF☀-➿]', strings), f'{f.name}: an emoji in its text')


if __name__ == '__main__':
    unittest.main()
