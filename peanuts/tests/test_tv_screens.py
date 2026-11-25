"""TV and monitors (GitHub issue #358): which display is a screen of the device and which a TV (ScreenRole, on the JVM with android/app/src/jvmtest's
ScreenRoleJvmTest), and that Peanuts' pages only ever use the device's own screens while a game may play on the TV."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'android/app/src/main/java'
PEANUTS = JAVA / 'dev/peanuts'
JVMTEST = ROOT / 'android/app/src/jvmtest/java/dev/peanuts'


def read(rel):
    return (JAVA / rel).read_text(encoding='utf-8')


class ScreenRoleJvm(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as t:
            subprocess.run(['javac', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', t, str(PEANUTS / 'ScreenRole.java'), str(JVMTEST / 'ScreenRoleJvmTest.java')], check=True)
            r = subprocess.run(['java', '-cp', t, 'dev.peanuts.ScreenRoleJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')

    def test_types_match_android(self):
        # android.view.Display.TYPE_INTERNAL .. TYPE_VIRTUAL (hidden, unchanged since Android 4.2)
        self.assertIn('UNKNOWN = 0, INTERNAL = 1, EXTERNAL = 2, WIFI = 3, OVERLAY = 4, VIRTUAL = 5', (PEANUTS / 'ScreenRole.java').read_text(encoding='utf-8'))


class OnlyTheDevicesScreens(unittest.TestCase):
    def test_no_first_other_display_rule_left(self):
        # the old rule (any valid display besides the default one) took a TV for the second screen
        for rel in ('dev/peanuts/Companion.java', 'dev/peanuts/ui/BottomScreen.java', 'dev/peanuts/Screens.java'):
            src = read(rel)
            self.assertNotRegex(src, r'if \(d\.getDisplayId\(\) != own && d\.getDisplayId\(\) != Display\.DEFAULT_DISPLAY && d\.isValid\(\)\) return d;', rel)

    def test_bottom_screen_and_companion_use_device_screens(self):
        bottom = read('dev/peanuts/ui/BottomScreen.java')
        second = bottom[bottom.index('static Display secondDisplay(Activity a)'):]
        self.assertIn('dev.peanuts.Screens.deviceScreen(a, m, own)', second[:second.index('\n    }')])
        comp = read('dev/peanuts/Companion.java')
        has = comp[comp.index('public static boolean hasSecondDisplay'):comp.index('public static void env(')]
        self.assertIn('Screens.deviceScreen(c, dm, Display.DEFAULT_DISPLAY) != null', has)

    def test_device_screen_is_a_device_role(self):
        src = read('dev/peanuts/Screens.java')
        dev = src[src.index('public static Display deviceScreen('):src.index('static Display tv(')]
        self.assertIn('role(c, d) == ScreenRole.DEVICE', dev)
        self.assertIn('id != Display.DEFAULT_DISPLAY && id != not', dev)
        role = src[src.index('static int role(Context c, Display d)'):src.index('private static Method getType')]
        self.assertIn('if (d.getDisplayId() == Display.DEFAULT_DISPLAY) return ScreenRole.DEVICE;', role)
        self.assertIn('Display.FLAG_PRIVATE', role)

    def test_screens_off_covers_only_device_screens(self):
        src = read('dev/peanuts/ui/ScreensOff.java')
        self.assertIn('!dev.peanuts.Screens.ownScreen(a, d)) continue;', src)


class PlayOnTheTv(unittest.TestCase):
    def test_tv_first_then_swap_then_plain(self):
        src = read('dev/peanuts/Screens.java')
        start = src[src.index('public static void startGame'):src.index('static boolean startOn')]
        tv, swap, plain = start.index('PeanutsPrefs.tvPlay(launcher)'), start.index('PeanutsPrefs.swapScreens(launcher)'), start.index('launcher.startActivity(intent);')
        self.assertLess(tv, swap)
        self.assertLess(swap, plain)
        # a launcher already on a TV (Samsung DeX) starts the game where it is
        self.assertIn("role(launcher, launcher.getDisplay()) != ScreenRole.TV", start)
        # the game knows it was started on the TV (Companion makes the screen it left black), and the app's icon brings it back there
        self.assertIn('new Intent(intent).putExtra(TV, true), tv.getDisplayId())', start)
        self.assertIn('GameReturn.startedOn(launcher, tv.getDisplayId());', start)

    def test_default_follows_the_system(self):
        # David, 2026-11-23: what the system does with a TV comes first (Android's mirror, the AYN firmware turning its screens off); Play on the TV is a choice
        self.assertIn('getBoolean("tv_play", false)', read('dev/peanuts/PeanutsPrefs.java'))

    def test_no_black_screen_over_one_the_system_turned_off(self):
        comp = read('dev/peanuts/Companion.java')
        cover = comp[comp.index('private static void cover(Activity game)'):comp.index('private static void toTv(')]
        self.assertLess(cover.index('builtIn.getState() == Display.STATE_OFF'), cover.index('startScreen(game, decidingGameName)'))

    def test_built_in_screen_black_while_on_the_tv(self):
        comp = read('dev/peanuts/Companion.java')
        cover = comp[comp.index('private static void cover(Activity game)'):comp.index('private static void toTv(')]
        self.assertIn('tvRun && Screens.onTv(game)', cover)
        self.assertIn('screenPage = false;', cover)
        # the black screen goes as soon as the TV does, before Android moves the game under it
        removed = comp[comp.index('public void onDisplayRemoved(int id)'):comp.index('public void onDisplayChanged(int id)')]
        self.assertLess(removed.index('CompanionScreen.close()'), removed.index('if (id == home) home = Display.INVALID_DISPLAY;'))
        place = comp[comp.index('private static void place(Activity game)'):comp.index('private static void placeCompanion(')]
        self.assertIn('cover(game);', place)
        # a companion takes the black screen's place on a phone
        show = comp[comp.index('private static void show(Activity game, String gameName, DisplayManager dm)'):comp.index('static Display secondDisplay(')]
        self.assertIn('screenUp && screenPage', show)

    def test_black_screen_never_takes_the_controller(self):
        src = (PEANUTS / 'CompanionScreen.java').read_text(encoding='utf-8')
        content = src[src.index('private void content('):src.index('protected void onNewIntent(')]
        self.assertIn('FLAG_NOT_FOCUSABLE', content)
        self.assertIn('Color.BLACK', content)

    def test_only_a_game_with_its_own_task_moves_to_a_tv_that_came(self):
        comp = read('dev/peanuts/Companion.java')
        move = comp[comp.index('private static void toTv('):comp.index('/** CompanionScreen is up:')]
        self.assertLess(move.index('PeanutsPrefs.tvPlay(game)'), move.index('moveHome(game, id)'))
        self.assertLess(move.index('game.isTaskRoot()'), move.index('moveHome(game, id)'))

    def test_a_tv_already_there_takes_the_game_at_its_start(self):
        comp = read('dev/peanuts/Companion.java')
        start = comp[comp.index('public static void start(Activity game, String gameName)'):comp.index('public static void stop()')]
        self.assertIn('PeanutsPrefs.tvPlay(game) && !Screens.onTv(game) ? Screens.tv(game, home) : null', start)
        self.assertLess(start.index('cover(game);'), start.index('toTv(game, tv.getDisplayId())'))

    def test_hold_never_stopped_before_it_is_in_the_foreground(self):
        # two screen rebuilds in a row (a TV off and another on) stopped it early, and Android ended the game for it
        hold = (PEANUTS / 'GameHold.java').read_text(encoding='utf-8')
        stop = hold[hold.index('public static void stop(Context c)'):hold.index('public int onStartCommand(')]
        self.assertLess(stop.index('if (pending || !up) return;'), stop.index('c.stopService('))
        command = hold[hold.index('public int onStartCommand('):hold.index('public void onDestroy()')]
        self.assertLess(command.index('startForeground(NOTE, n'), command.index('if (!wanted) { stopping = true; stopSelf(); }'))
        start = hold[hold.index('public static void start(Context c, String gameName)'):hold.index('public static void stop(Context c)')]
        self.assertLess(start.index('if (pending || up && !stopping) return;'), start.index('startForegroundService('))

    def test_picture_hooks(self):
        game = read('dev/nxnative/android/GameActivity.java')
        self.assertRegex(game, r'setContentView\(root\);\s+dev\.peanuts\.TvPicture\.fit\(this\);\s+// PEANUTS:')
        changed = game[game.index('public void onConfigurationChanged(Configuration c)'):]
        self.assertRegex(changed[:changed.index('\n    }')], r'dev\.peanuts\.TvPicture\.fit\(this\);\s+// PEANUTS:')
        pic = (PEANUTS / 'TvPicture.java').read_text(encoding='utf-8')
        self.assertIn('setPreferMinimalPostProcessing(tv)', pic)
        self.assertIn('setSizeFromLayout()', pic)

    def test_row_text_follows_the_ui_rules(self):
        src = read('dev/peanuts/ui/LibrarySettings.java')
        row = src[src.index('if (tvRow != null) tvRow.value('):]
        row = row[:row.index(';') + 1]
        texts = ''.join(re.findall(r'"([^"]*)"', row))
        self.assertIn('Play on the TV', texts)
        self.assertIn('System:', texts)
        for bad in ('—', '–', '·', '•', '!'):
            self.assertNotIn(bad, texts)
        self.assertIn('new PUi.Setting(a, "", "TV and monitors"', src)   # no emoji: the tile is an icon of Peanuts' set
        self.assertTrue((ROOT / 'android/app/src/peanuts/res/drawable/pi_tv.xml').is_file())
        sections = read('dev/peanuts/ui/SettingsSections.java')
        self.assertIn('new Group("TV", r("TV and monitors", "tv"))', sections)   # Settings › Screens
        self.assertIn('put(m, "TV and monitors",', read('dev/peanuts/ui/SettingsHelp.java'))


if __name__ == '__main__':
    unittest.main()
