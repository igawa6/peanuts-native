"""Saves for each user (issue #422): each user has a save of their own (Users.saveDir), and the game page's Saves tab backs up, restores, imports, copies and
deletes the save of the user picked on it (dev.nxnative.android.UserSaves), not only the first user's. On the JVM (UserSavesJvmTest with SaveStore and
UserSaveDirs): whose a folder and a backup are, the manifest's user line, another user's save as their account save alone (the device save stays the game's),
a restore into another user's save stopped part way and finished at a game's start, the game's lock. From the sources: every place in SaveUi that works on a
save takes the picked user's, under the game's lock for another user's; the hooks in MainActivity and GameActivity; SaveStore still needs no other file.
The JVM half is skipped without a JDK, and on Windows, whose File.renameTo does not replace a file (SaveStore's swap renames over the save's files, as on
Android and the Mac)."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android/app/src/main/java/dev/nxnative/android'
TEST = REPO / 'android/app/src/jvmtest/java/dev/nxnative/android/UserSavesJvmTest.java'


def read(name):
    return (SRC / name).read_text(encoding='utf-8')


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
@unittest.skipIf(os.name == 'nt', "Windows' File.renameTo does not replace a file, which SaveStore's swap needs")
class UserSavesJvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            srcs = [SRC / 'SaveStore.java', SRC / 'UserSaveDirs.java', TEST]
            r = subprocess.run(['javac', '-Xlint:all,-try', '-Werror', '-encoding', 'UTF-8', '-d', d, *map(str, srcs)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.UserSavesJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
            self.assertIn('PASS', r.stdout)


class SaveUiTakesThePickedUser(unittest.TestCase):
    def setUp(self):
        self.src = read('SaveUi.java')

    def test_no_first_user_folder_left(self):
        """Folders.save (the first user's folder) is left only where the game's lock is asked (gameRunning); every other place takes UserSaves.save"""
        uses = [l.strip() for l in self.src.splitlines() if 'Folders.save(' in l and not l.lstrip().startswith(('*', '//'))]
        self.assertEqual(len(uses), 1, uses)
        self.assertIn('static boolean gameRunning', uses[0])
        self.assertGreaterEqual(self.src.count('UserSaves.save(a, g)'), 5)

    def test_changes_hold_the_games_lock(self):
        """each SaveStore.backup / restore that a Saves tab row starts runs inside UserSaves.locked (the game's lock for another user's save)"""
        for m in re.finditer(r'run\(a, g, .*', self.src):
            line = m.group(0)
            if 'SaveStore.backup' in line or 'SaveStore.restore' in line:
                self.assertIn('UserSaves.locked(a, g, save, () -> SaveStore.', line)
        export = self.src[self.src.index('private static void exportTo'):self.src.index('static void restoreList')]
        self.assertIn('UserSaves.locked(a, g, save, () -> SaveStore.backup(save, appBackups(a, g), stamp()))', export)
        delete = self.src[self.src.index('static void delete(MainActivity a, Game g)'):self.src.index('private static void wipe')]
        self.assertIn('UserSaves.locked(a, g, save, () -> {', delete)

    def test_ready_refuses_restore_into_a_removed_user(self):
        ready = self.src[self.src.index('static boolean ready(MainActivity a, Game g, String what)'):]
        ready = ready[:ready.index('\n    }\n')]
        self.assertIn('UserSaves.ready(a, g, what)', ready)

    def test_cards_refresh_after_each_operation(self):
        run = self.src[self.src.index('static void run(MainActivity a, Game g, String wait, Work work, Done done)'):]
        run = run[:run.index('\n    }\n')]
        self.assertLess(run.index('done.done(fr, fe);'), run.index('UserSaves.changed(a, g);'))

    def test_restore_list_names_whose(self):
        self.assertIn('l.sort(UserSaves.order(a, g));', self.src)
        self.assertIn('labels[i] = UserSaves.label(a, g, b) + SaveStore.when(b.name)', self.src)

    def test_with_one_user_the_words_are_as_before(self):
        """UserSaves.saveOf gives upstream's own words with one user: "<game>'s save" """
        us = read('UserSaves.java')
        body = us[us.index('static String saveOf('):]
        body = body[:body.index('\n    }\n')]
        self.assertIn('if (!several(a, g)) return g.name + "\'s " + word;', body)
        self.assertIn(': "Your current save"', us)


class Hooks(unittest.TestCase):
    def test_saves_tab(self):
        """the user cards before the rows, Copy to another user right above Delete save data, the folder line on the picked user's"""
        src = read('MainActivity.java')
        tab = src[src.index('void gameTab(LinearLayout content, Game g, boolean savesTab)'):]
        tab = tab[:tab.index('            return;\n        }')]
        order = ['UserSaves.picker(this, content, g);', 'LinearLayout saves = Ui.add(content, Ui.listCard(this), 16);', '"Import save"',
                 'UserSaves.copyRow(this, saves, g);', '"Delete save data"', 'File save = UserSaves.save(this, g);', 'UserSaves.where(where, g);']
        at = [tab.index(s) for s in order]
        self.assertEqual(at, sorted(at), list(zip(order, at)))
        self.assertNotIn('Folders.save(this, g)', tab)

    def test_game_start_finishes_every_users_restore(self):
        """GameActivity, holding the game's lock, finishes a stopped restore of the game's own save and then of each user's, in the same try; another user's that
        cannot be finished is left and noted (UserSaveDirs.recoverUsers catches it), the game's own still stops the start"""
        src = read('GameActivity.java')
        a = src.index('if (SaveStore.recoverLocked(dir))')
        b = src.index('String users = UserSaveDirs.recoverUsers(dir); if (users != null) saveNote = users;')
        dirs = read('UserSaveDirs.java')
        body = dirs[dirs.index('static String recoverUsers(File base) {'):]
        self.assertIn('catch (IOException | RuntimeException e)', body[:body.index('\n    }\n')])
        self.assertLess(a, b)
        self.assertLess(b, src.index('} catch (IOException e) {', a))
        self.assertLess(src.index('holdLock(dir);'), a)


class SettingsUsers(unittest.TestCase):
    """Part 2: Settings › Users reaches a user's saves (UserSaves.of / has / open), counted from each game's real save folder (Folders.save, a picked one too)"""
    USERS = REPO / 'android/app/src/main/java/dev/peanuts/users'

    def test_counts_come_from_the_games_save_folders(self):
        page = (self.USERS / 'UsersPage.java').read_text(encoding='utf-8')
        self.assertIn('static int saves(Context c, String uid) { return dev.nxnative.android.UserSaves.of(c, uid).size(); }', page)
        self.assertNotIn('getExternalFilesDir', page)
        who = (self.USERS / 'WhoPlaysActivity.java').read_text(encoding='utf-8')
        self.assertIn('return tid != null && dev.nxnative.android.UserSaves.has(this, tid, uid);', who)
        self.assertNotIn('new File(new File(getExternalFilesDir(null), "saves"), tid)', who)
        us = read('UserSaves.java')
        of = us[us.index('public static List<Saved> of(Context c, String uid)'):]
        of = of[:of.index('\n    }\n')]
        for want in ('Game.load(c)', 'Folders.save(c, g)', 'Users.saveDir(base, uid)', 'SaveStore.hasFiles(d)'):
            self.assertIn(want, of)

    def test_show_saves_opens_the_saves_tab_with_the_user_picked(self):
        page = (self.USERS / 'UsersPage.java').read_text(encoding='utf-8')
        self.assertIn('if (!mine.isEmpty()) PUi.add(box, PUi.button(c, "Show saves"', page)
        us = read('UserSaves.java')
        op = us[us.index('public static boolean open(android.app.Activity a, String titleId, String uid)'):]
        op = op[:op.index('\n    }\n')]
        self.assertLess(op.index('picked.put(titleId, uid);'), op.index('GameSettings.openOn((MainActivity) a, g, GameSettings.SAVES);'))
        gs = read('GameSettings.java')
        self.assertRegex(gs, r'static void openOn\(MainActivity a, Game g, String t\) \{ tab = t; (GamePagePort\.at\(t\); )?a\.gameSettings\(g\); \}   // PEANUTS')   # the page in areas: at Saves too


class SaveStoreStaysAlone(unittest.TestCase):
    def setUp(self):
        self.src = read('SaveStore.java')

    def test_no_other_file(self):
        """dm/tools/tests' JVM tests (saves, saf, packzip) compile SaveStore.java without UserSaves, UserSaveDirs or Users"""
        code = '\n'.join(l for l in self.src.splitlines() if not l.lstrip().startswith(('*', '//', '/**')))
        for name in ('UserSaves', 'UserSaveDirs', 'Users.'):
            self.assertNotIn(name, re.sub(r'//.*', '', code), name)

    def test_first_user_and_folder_name_match_users_and_the_host(self):
        first = re.search(r'static final String FIRST_USER = "([0-9a-f]{32})"', self.src).group(1)
        users = read('Users.java')
        self.assertIn(f'static final String FIRST = "{first}"', users)
        self.assertIn('save.getName() + "_user_" + uid', users)
        self.assertIn('static final String USER_SUFFIX = "_user_";', self.src)
        self.assertIn('"%s_user_%s"', (REPO / 'dm/host/hle_account.c').read_text(encoding='utf-8'))

    def test_manifest_user_line(self):
        self.assertIn('s.append("user\\t")', self.src)
        self.assertIn('else if (c[0].equals("user") && c.length == 3)', self.src)

    def test_no_android_in_the_folder_logic(self):
        self.assertNotIn('import android.', read('UserSaveDirs.java'))


class Words(unittest.TestCase):
    """UI-RULES: no separator glyphs, no em or en dash, no emoji in the new text"""

    def test_user_saves_text(self):
        src = read('UserSaves.java')
        strings = re.findall(r'"((?:[^"\\]|\\.)*)"', src)
        self.assertTrue(strings)
        for s in strings:
            self.assertNotRegex(s, '[—–·•]', s)
            self.assertFalse(any(ord(ch) >= 0x2600 and ch not in '›…' for ch in s), s)
        self.assertIn('rowIcon(a, s.view, "user")', src, 'Copy to another user has a Peanuts icon, no emoji')

    def test_a_removed_users_save_is_said_as_such(self):
        """a removed user's card reads "Removed user", a sentence "a removed user's save"; and nothing says the game starts again for them"""
        src = read('UserSaves.java')
        self.assertIn('"Removed user"', src)
        self.assertIn('"a removed user"', src)
        body = src[src.index('static String forWhom('):]
        body = body[:body.index('\n    }\n')]
        self.assertIn('return w.removed ? "" : " for " + w.name;', body)
        for helper in ('static String saveOf(', 'static String noSave(', 'static String yours('):
            h = src[src.index(helper):]
            h = h[:h.index('\n')] + h[h.index('\n'):][:400]
            self.assertNotIn('current(a, g).name', h.split('\n    }')[0], helper)


if __name__ == '__main__':
    unittest.main()
