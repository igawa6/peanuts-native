"""The adb control interface's plain-Java parts (peanuts/docs/CONTROL.md; dev.peanuts.ctl's Request, Settings, Result, Json, ExitRecord): which commands and
arguments are taken, which setting values are stored, and that what goes into status.json is JSON a script can read. javac/java only, the pattern of
test_mod_store.py."""
import json
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CTL = REPO / 'android/app/src/main/java/dev/peanuts/ctl'
SRC = [CTL / n for n in ('Request.java', 'Settings.java', 'Result.java', 'Json.java', 'ExitRecord.java', 'GameLive.java')]
TITLE = '0100F2C0115B6000'

RUNNER = r'''package dev.peanuts.ctl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public final class CtlTest {
    static Map<String, String> pairs(String[] a, int from) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = from; i < a.length; i++) { int eq = a[i].indexOf('='); m.put(a[i].substring(0, eq), a[i].substring(eq + 1)); }
        return m;
    }
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "request": {   // request k=v ...  ->  "ok <cmd> <id> <needs screen> <title id>" | "bad <code>"
                Request r = new Request(pairs(a, 1), 7L);
                try { r.check(); System.out.println("ok " + r.cmd + " " + r.id + " " + r.needsScreen() + " " + r.titleId(false)); }
                catch (Request.Bad e) { System.out.println("bad " + e.code); }
                break;
            }
            case "set": {   // set <key> <value> <per game 0|1> <paced 0|1>  ->  "ok <key> <per game> <value> <effective>" | "bad <code>"
                boolean own = a[3].equals("1"), paced = a[4].equals("1");
                try {
                    List<String> drivers = a.length > 5 ? (a[5].isEmpty() ? new ArrayList<String>() : Arrays.asList(a[5].split(","))) : null;   // installed GPU drivers
                    Settings.Change c = Settings.parse(a[1], a[2], own, paced ? new int[] {-2, 0, 45, 40, 30, 20} : new int[] {0, 60, 30, 20, 15}, paced, drivers);
                    System.out.println("ok " + c.key + " " + c.perGame + " " + c.value + " " + c.effective);
                } catch (Request.Bad e) { System.out.println("bad " + e.code + " " + e.getMessage()); }
                break;
            }
            case "live": {   // live <limited 0|1> <screen none|on|off> <mode docked|handheld|fixed> <mode applies live 0|1> k=v ...: the game's answer, then the launcher's result
                Fake f = new Fake(a[1].equals("1"), a[2].equals("none") ? null : a[2].equals("on"), a[3], a[4].equals("1"));
                String answer = GameLive.answer(pairs(a, 5), f);
                Map<String, String> ex = new LinkedHashMap<>(pairs(a, 5));
                ex.put("cmd", "game");
                ex.put("id", "g1");
                Result res = new Result(new Request(ex, 5L));
                GameLive.take(answer, res);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("result", res.toMap());
                out.put("stored", f.stored);
                out.put("calls", f.calls);
                System.out.print(Json.write(out));
                break;
            }
            case "nolive": {   // the game's screen has no controls yet, and no answer came at all
                Map<String, String> ex = pairs(a, 1);
                Result res = new Result(new Request(ex, 5L));
                GameLive.take(GameLive.answer(ex, null), res);
                Result none = new Result(new Request(ex, 5L));
                GameLive.take(null, none);
                Result junk = new Result(new Request(ex, 5L));
                GameLive.take("{\"ok\": tru", junk);
                System.out.println(res.error + " " + none.error + " " + junk.error);
                break;
            }
            case "roundtrip": {   // Json.read gives back what Json.write wrote
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("text", "back\\slash \"q\" é\nline\t\u0001  ");
                m.put("n", 42); m.put("big", 51234567890L); m.put("neg", -3); m.put("ratio", 0.25); m.put("yes", true); m.put("no", false); m.put("none", null);
                m.put("list", new ArrayList<Object>(Arrays.asList(1, "two", null, new LinkedHashMap<String, Object>())));
                m.put("empty", new ArrayList<Object>());
                String once = Json.write(m);
                Object back = Json.read(once);
                System.out.println((back.equals(m) && Json.write(back).equals(once)) + " " + new ArrayList<>(((Map<?, ?>) back).keySet()));
                break;
            }
            case "result": {   // result <ok|fail|pending> k=v ...: last_result as JSON
                Map<String, String> ex = pairs(a, 2);
                Result res = new Result(new Request(ex, 1763190000000L));
                if (a[1].equals("ok")) res.ok(null).put("pack", "/storage/x/pack");
                if (a[1].equals("fail")) res.fail("refused", "Shaders missing: \"quoted\"\nsecond line\t… \u0001");
                System.out.print(Json.write(res.toMap()));
                break;
            }
            case "json": {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("schema", 1);
                m.put("big", 51234567890L);
                m.put("ratio", 0.5);
                m.put("nan", Double.NaN);
                m.put("none", null);
                m.put("yes", true);
                m.put("empty_map", new LinkedHashMap<String, Object>());
                m.put("empty_list", new ArrayList<Object>());
                m.put("text", "back\\slash \"q\" é   line\nbreak");
                List<Object> l = new ArrayList<>(Arrays.asList(1, "two", null));
                Map<String, Object> deep = new LinkedHashMap<>();
                deep.put("k", Arrays.asList(1, "two", null));
                l.add(deep);
                Map<String, Object> in = new LinkedHashMap<>();
                in.put("k", l);
                m.put("nested", in);
                System.out.print(Json.write(m));
                break;
            }
            case "exit": {   // exit <alive 0|1> <record, \n as |>: the status file's game part as JSON
                System.out.print(Json.write(ExitRecord.game(ExitRecord.parse(a[2].replace('|', '\n')), a[1].equals("1"))));
                break;
            }
            case "companion_state":   // catalog version against the installed one: (none, none) (none, 1.0.0) (1.0.1, none) (1.0.1, 1.0.1) (1.0.1, 1.0.0)
                System.out.println(Request.companionState(null, null) + " " + Request.companionState(null, "1.0.0") + " " + Request.companionState("1.0.1", null)
                        + " " + Request.companionState("1.0.1", "1.0.1") + " " + Request.companionState("1.0.1", "1.0.0"));
                break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }

    /**
     * A running game's controls as GameActivity's Host has them. limited: the frame limit menu (-2 Game owned, 0 = 60 fps), offered up to 60 fps, started at
     * 60, where Game owned and 90/120 wait for a restart; else the plain menu (0 Game owned), all offered, every pick live. Its default cap is 30
     */
    static final class Fake implements GameLive.Live {
        final boolean limited, modeLive;
        Boolean screen;
        int mode, picked;
        Object stored;   // the game's own stored cap: an Integer, or "default" once forgotten
        final List<String> calls = new ArrayList<>();
        Fake(boolean limited, Boolean screen, String mode, boolean modeLive) {
            this.limited = limited; this.screen = screen; this.modeLive = modeLive;
            this.mode = mode.equals("docked") ? 0 : mode.equals("handheld") ? 1 : -1;
            picked = limited ? 0 : 60;
        }
        @Override public String titleId() { return "0100F2C0115B6000"; }
        @Override public int consoleMode() { return mode; }
        @Override public boolean consoleMode(int m) { calls.add("mode " + m); mode = m; return modeLive; }
        @Override public int[] caps() { return limited ? new int[] {-2, 120, 90, 0, 45, 40, 30, 20} : new int[] {0, 120, 90, 60, 45, 40, 30, 20, 15}; }
        @Override public String capLabel(int v) { return limited ? (v == -2 ? "Game owned" : (v <= 0 ? 60 : v) + " fps") : v == 0 ? "Game owned" : v + " fps"; }
        @Override public boolean capOffered(int v) { return !limited || v != 120 && v != 90; }
        @Override public int cap() { return picked; }
        @Override public int capRunning() { return limited && (picked == -2 || picked > 60) ? 0 : picked; }
        @Override public void cap(int v) { calls.add("cap " + v); picked = v; stored = v; }
        @Override public void forgetCap() { calls.add("forget"); stored = "default"; }
        @Override public Integer defaultCap() { picked = 30; return 30; }
        @Override public Boolean screenShown() { return screen; }
        @Override public Boolean toggleScreen() { calls.add("toggle"); if (screen == null) return null; screen = !screen; return screen; }
    }
}
'''


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class CtlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='ctl_')
        root = Path(cls.tmp.name)
        cls.classes = root / 'classes'
        cls.classes.mkdir()
        runner = root / 'CtlTest.java'
        runner.write_text(RUNNER, encoding='utf-8')
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(cls.classes), *map(str, SRC), str(runner)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_cmd(self, *args):
        r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', str(self.classes), 'dev.peanuts.ctl.CtlTest', *args],
                           check=True, capture_output=True, text=True, encoding='utf-8')
        return r.stdout

    def request(self, *pairs):
        return self.run_cmd('request', *pairs).strip()

    def test_commands_and_their_arguments(self):
        self.assertEqual(self.request('cmd=status'), 'ok status null false null')
        self.assertEqual(self.request('cmd=STATUS', 'id=a17'), 'ok status a17 false null')
        self.assertEqual(self.request('cmd=status', 'req=b2'), 'ok status b2 false null')   # the spec's name for the token
        self.assertEqual(self.request('cmd=list'), 'ok list null false null')
        self.assertEqual(self.request('cmd=scan'), 'ok scan null true null')
        self.assertEqual(self.request('cmd=build', 'title_id=' + TITLE.lower()), 'ok build null true ' + TITLE)
        self.assertEqual(self.request('cmd=prepare', 'title_id=' + TITLE), 'ok prepare null true ' + TITLE)
        self.assertEqual(self.request('cmd=reimport', 'title_id=' + TITLE), 'ok reimport null true ' + TITLE)   # the question (needs_confirm) is answered where the plan is known
        self.assertEqual(self.request('cmd=reimport', 'title_id=' + TITLE, 'confirm=true', 'delete_first=no'), 'ok reimport null true ' + TITLE)
        self.assertEqual(self.request('cmd=play', 'title_id=' + TITLE, 'id=p1'), 'ok play p1 true ' + TITLE)
        self.assertEqual(self.request('cmd=nav', 'to=home', 'filter=ready', 'find=zelda'), 'ok nav null true null')
        self.assertEqual(self.request('cmd=nav', 'to=settings', 'section=System'), 'ok nav null true null')
        self.assertEqual(self.request('cmd=nav', 'to=game', 'title_id=' + TITLE, 'tab=storage'), 'ok nav null true ' + TITLE)
        self.assertEqual(self.request('cmd=job', 'do=pause'), 'ok job null false null')   # a service intent: no screen
        self.assertEqual(self.request('cmd=job', 'do=stop'), 'ok job null false null')    # the build card's word for the same button
        self.assertEqual(self.request('cmd=job', 'do=resume', 'title_id=' + TITLE), 'ok job null true ' + TITLE)
        self.assertEqual(self.request('cmd=job', 'do=try_anyway'), 'ok job null true null')
        self.assertEqual(self.request('cmd=game', 'do=ctl', 'line=A+B~0.5'), 'ok game null false null')
        self.assertEqual(self.request('cmd=game', 'do=stop', 'confirm=true'), 'ok game null false null')
        self.assertEqual(self.request('cmd=screens_off', 'on=true'), 'ok screens_off null true null')
        self.assertEqual(self.request('cmd=screens_off', 'on=off'), 'ok screens_off null false null')
        self.assertEqual(self.request('cmd=set', 'key=overlay', 'value=off'), 'ok set null false null')

    def test_the_companion_command(self):   # no screen: the catalog is read and a package installed on the command's own thread
        self.assertEqual(self.request('cmd=companion', 'do=list'), 'ok companion null false null')
        self.assertEqual(self.request('cmd=companion', 'do=list', 'title_id=' + TITLE), 'ok companion null false ' + TITLE)
        self.assertEqual(self.request('cmd=companion', 'do=install', 'title_id=' + TITLE, 'id=c1'), 'ok companion c1 false ' + TITLE)
        self.assertEqual(self.request('cmd=companion', 'do=UPDATE', 'title_id=' + TITLE.lower()), 'ok companion null false ' + TITLE)
        self.assertEqual(self.request('cmd=companion', 'do=remove', 'title_id=' + TITLE, 'confirm=true'), 'ok companion null false ' + TITLE)
        self.assertEqual(self.request('cmd=companion'), 'bad bad_args')                                     # no do
        self.assertEqual(self.request('cmd=companion', 'do=delete', 'title_id=' + TITLE), 'bad bad_args')    # not one of its words
        self.assertEqual(self.request('cmd=companion', 'do=install'), 'bad bad_args')                        # which game
        self.assertEqual(self.request('cmd=companion', 'do=remove', 'title_id=' + TITLE), 'bad needs_confirm')
        self.assertEqual(self.request('cmd=companion', 'do=remove', 'title_id=' + TITLE, 'confirm=no'), 'bad needs_confirm')
        self.assertEqual(self.run_cmd('companion_state').split(), ['none', 'installed', 'not_installed', 'installed', 'update'])

    def test_the_companion_command_from_a_file(self):   # Install from file without the picker: the file names its game, title_id with it must be that game
        f = 'path=/sdcard/Download/010015100B514000-SuperMarioWonderDS-1.0.1.dsmod.zip'
        self.assertEqual(self.request('cmd=companion', 'do=install', f), 'ok companion null false null')
        self.assertEqual(self.request('cmd=companion', 'do=install', f, 'title_id=' + TITLE, 'id=f1'), 'ok companion f1 false ' + TITLE)
        self.assertEqual(self.request('cmd=companion', 'do=install', f, 'confirm=true'), 'ok companion null false null')   # an older version than the installed one
        self.assertEqual(self.request('cmd=companion', 'do=install', f, 'confirm=maybe'), 'bad bad_args')
        self.assertEqual(self.request('cmd=companion', 'do=update', f, 'title_id=' + TITLE), 'bad bad_args')               # path is for install only
        self.assertEqual(self.request('cmd=companion', 'do=remove', f, 'title_id=' + TITLE, 'confirm=true'), 'bad bad_args')
        self.assertEqual(self.request('cmd=companion', 'do=install', f, 'title_id=12'), 'bad bad_args')
        cmd = (CTL / 'CompanionCmd.java').read_text(encoding='utf-8')
        self.assertIn('CompanionImport.install(c, f, f.getName(), id, confirm, null)', cmd)   # the screen's own install, not a second one
        self.assertIn('new Request.Bad("no_file"', cmd)
        self.assertIn('new Request.Bad("needs_confirm", e.getMessage() + " Add -e confirm true to install it anyway.")', cmd)
        doc = (REPO / 'peanuts/docs/CONTROL.md').read_text(encoding='utf-8')
        self.assertIn('-e cmd companion -e do install -e path ', doc)
        for code in ('no_file', 'not_companion', 'damaged', 'wrong_title', 'needs_newer_app'):   # CompanionFile.Refused's codes and the file's own
            self.assertIn(f'| `{code}` |', doc, code)
        refused = (REPO / 'android/app/src/main/java/dev/peanuts/CompanionFile.java').read_text(encoding='utf-8')
        for code in ('not_companion', 'damaged', 'wrong_title', 'needs_newer_app'):
            self.assertIn(f'"{code}"', refused, code)

    def test_pages_of_the_status_file(self):
        """The pages Up.pageOf names are the ones CONTROL.md lists, and what it reads of upstream's MainActivity by name is there (a rename shows only as a
        command's error on the phone): the Advanced page of Banana Native v0.3.26 is a page of its own, and Up.page closes it before it changes the page"""
        up = (CTL / 'Up.java').read_text(encoding='utf-8')
        body = up[up.index('static String pageOf('):]; body = body[:body.index('\n    }')]
        pages = set(re.findall(r'"([a-z]+)"', body)) - {'settingsGame', 'settingsPage', 'advancedOpen'}
        self.assertEqual(pages, {'home', 'settings', 'game', 'advanced'})
        row = next(l for l in (REPO / 'peanuts/docs/CONTROL.md').read_text(encoding='utf-8').splitlines() if l.startswith('| `screen`'))
        for p in pages: self.assertIn(f'`{p}`', row)
        main = (REPO / 'android/app/src/main/java/dev/nxnative/android/MainActivity.java').read_text(encoding='utf-8')
        for decl in ('private boolean advancedOpen;', 'private void closeAdvanced() {', 'private void page(boolean settings) {', 'private Game settingsGame;', 'private boolean resumed, settingsPage;'):
            self.assertIn(decl, main, 'upstream changed what dev.peanuts.ctl.Up reads by name')
        page = up[up.index('static void page('):]; page = page[:page.index('\n    }')]
        self.assertLess(page.index('closeAdvanced'), page.index('"page"'), 'Up.page closes the Advanced page first')

    def test_refusals(self):
        self.assertEqual(self.request(), 'bad bad_args')
        self.assertEqual(self.request('cmd=delete', 'title_id=' + TITLE), 'bad unknown_command')
        for out in ('saves', 'clean', 'driver', 'mods', 'pack', 'setup', 'reset', 'dialog'):   # not in this version on purpose
            self.assertEqual(self.request('cmd=' + out), 'bad unknown_command', out)
        self.assertEqual(self.request('cmd=build'), 'bad bad_args')
        self.assertEqual(self.request('cmd=reimport'), 'bad bad_args')
        self.assertEqual(self.request('cmd=reimport', 'title_id=' + TITLE, 'confirm=maybe'), 'bad bad_args')
        self.assertEqual(self.request('cmd=reimport', 'title_id=' + TITLE, 'delete_first=2'), 'bad bad_args')
        self.assertEqual(self.request('cmd=build', 'title_id=0100F2C0115B600'), 'bad bad_args')
        self.assertEqual(self.request('cmd=play', 'title_id=../../../../etc/pw'), 'bad bad_args')
        self.assertEqual(self.request('cmd=nav'), 'bad bad_args')
        self.assertEqual(self.request('cmd=nav', 'to=sheet'), 'bad bad_args')
        self.assertEqual(self.request('cmd=nav', 'to=home', 'filter=hidden'), 'bad bad_args')
        self.assertEqual(self.request('cmd=nav', 'to=game'), 'bad bad_args')
        self.assertEqual(self.request('cmd=nav', 'to=game', 'title_id=' + TITLE, 'tab=mods'), 'bad bad_args')
        self.assertEqual(self.request('cmd=job'), 'bad bad_args')
        self.assertEqual(self.request('cmd=job', 'do=delete_pack'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=stop'), 'bad needs_confirm')
        self.assertEqual(self.request('cmd=game', 'do=stop', 'confirm=false'), 'bad needs_confirm')
        self.assertEqual(self.request('cmd=game', 'do=ctl'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=ctl', 'line=A\nenv DM_X=1'), 'bad bad_args')   # one line only
        self.assertEqual(self.request('cmd=game', 'do=pause'), 'bad bad_args')
        self.assertEqual(self.request('cmd=screens_off'), 'bad bad_args')
        self.assertEqual(self.request('cmd=screens_off', 'on=maybe'), 'bad bad_args')
        self.assertEqual(self.request('cmd=set', 'key=overlay'), 'bad bad_args')
        self.assertEqual(self.request('cmd=status', 'id=' + 'x' * 65), 'bad bad_args')
        self.assertEqual(self.request('cmd=status', 'force=perhaps'), 'bad bad_args')

    def set(self, key, value, own=False, paced=False, drivers=None, message=False):
        args = ['set', key, value, '1' if own else '0', '1' if paced else '0'] + ([] if drivers is None else [','.join(drivers)])
        out = self.run_cmd(*args).strip()
        return out if message or not out.startswith('bad ') else ' '.join(out.split()[:2])

    def test_settings_allow_list(self):
        self.assertEqual(self.set('console_mode', 'docked'), 'ok console_mode false 0 next_start')
        self.assertEqual(self.set('console_mode', 'Handheld', own=True), 'ok console_mode true 1 next_start')
        self.assertEqual(self.set('console_mode', 'default'), 'ok console_mode false -1 next_start')   # each game's own
        self.assertEqual(self.set('console_mode', 'default', own=True), 'ok console_mode true null next_start')
        self.assertEqual(self.set('console_mode', 'tv'), 'bad bad_value')
        self.assertEqual(self.set('res_scale', '0.5'), 'ok res_scale false 2 next_start')
        self.assertEqual(self.set('res_scale', '1.5x', own=True), 'ok res_scale true 6 next_start')
        self.assertEqual(self.set('res_scale', '2'), 'ok res_scale false 8 next_start')
        self.assertEqual(self.set('res_scale', 'default'), 'ok res_scale false 4 next_start')
        self.assertEqual(self.set('res_scale', 'default', own=True), 'ok res_scale true null next_start')
        self.assertEqual(self.set('res_scale', '3'), 'bad bad_value')
        self.assertEqual(self.set('res_scale', '1.1'), 'bad bad_value')
        self.assertEqual([self.set('upscaler', v) for v in ('nearest', 'bilinear', 'fsr1', 'native', 'sgsr1')],
                         ['ok upscaler false %d next_start' % i for i in range(5)])
        self.assertEqual(self.set('upscaler', 'default', own=True), 'ok upscaler true null next_start')
        self.assertEqual(self.set('upscaler', 'default'), 'bad bad_value')   # the default for every game is always one of the five
        self.assertEqual(self.set('upscaler', 'dlss'), 'bad bad_value')
        self.assertEqual(self.set('overlay', 'off'), 'ok overlay false false now')
        self.assertEqual(self.set('keep_screen_on', 'on'), 'ok keep_screen_on false true now')
        self.assertEqual(self.set('overlay', 'on', own=True), 'bad bad_args')
        self.assertEqual(self.set('overlay', 'default'), 'bad bad_value')

    def test_frame_rate_cap(self):
        self.assertEqual(self.set('fps_cap', '30'), 'ok fps_cap false 30 next_start')
        self.assertEqual(self.set('fps_cap', 'own'), 'ok fps_cap false 0 next_start')
        self.assertEqual(self.set('fps_cap', 'default'), 'ok fps_cap false -1 next_start')
        self.assertEqual(self.set('fps_cap', '45'), 'bad bad_value')
        self.assertEqual(self.set('fps_cap', '-1'), 'bad bad_value')
        self.assertEqual(self.set('fps_cap', 'fast'), 'bad bad_value')
        self.assertEqual(self.set('fps_cap', '60', own=True), 'ok fps_cap true 60 next_start')
        self.assertEqual(self.set('fps_cap', 'own', own=True), 'ok fps_cap true 0 next_start')
        self.assertEqual(self.set('fps_cap', 'default', own=True), 'ok fps_cap true null next_start')
        self.assertEqual(self.set('fps_cap', '45', own=True), 'bad bad_value')
        # a game with the frame limit list: 60 is stored as 0, 45 and 40 exist, "the game's own" does not
        self.assertEqual(self.set('fps_cap', '60', own=True, paced=True), 'ok fps_cap true 0 next_start')
        self.assertEqual(self.set('fps_cap', '45', own=True, paced=True), 'ok fps_cap true 45 next_start')
        self.assertEqual(self.set('fps_cap', 'own', own=True, paced=True), 'ok fps_cap true -2 next_start')
        self.assertEqual(self.set('fps_cap', '0', own=True, paced=True), 'bad bad_value')
        self.assertEqual(self.set('fps_cap', '15', own=True, paced=True), 'bad bad_value')

    def test_keys_outside_the_list_are_refused(self):
        for key in ('env', 'gpu_driver_zip', 'game_dir', 'build_dir', 'pad_map', 'adb_control', 'lang', 'window', 'folder_save_' + TITLE):
            self.assertEqual(self.set(key, 'x'), 'bad unknown_key', key)

    def test_gpu_driver(self):
        inst = ['Turnip_27.0.0-R2', 'Turnip_26.1']
        self.assertEqual(self.set('gpu_driver', 'turnip_27.0.0-r2', own=True, drivers=inst), 'ok gpu_driver true Turnip_27.0.0-R2 next_start')   # the folder's own spelling is stored
        self.assertEqual(self.set('gpu_driver', 'System', own=True, drivers=inst), 'ok gpu_driver true system next_start')
        self.assertEqual(self.set('gpu_driver', 'default', own=True, drivers=inst), 'ok gpu_driver true null next_start')   # the game follows the recommendation, else every game's
        self.assertEqual(self.set('gpu_driver', 'Turnip_26.1', drivers=inst), 'ok gpu_driver false Turnip_26.1 next_start')
        self.assertEqual(self.set('gpu_driver', 'default', drivers=inst), 'ok gpu_driver false system next_start')   # every game's default is the phone's own
        out = self.set('gpu_driver', 'Turnip_99', own=True, drivers=inst, message=True)
        self.assertTrue(out.startswith('bad bad_value '), out)
        self.assertIn('Installed: Turnip_27.0.0-R2, Turnip_26.1.', out)
        self.assertIn('No driver is installed.', self.set('gpu_driver', 'Turnip_99', drivers=[], message=True))
        self.assertEqual(self.set('gpu_driver', '../../lib', drivers=inst), 'bad bad_value')   # only an installed folder's name

    def test_fast_cores_hold60_bottom_screen_display(self):
        self.assertEqual(self.set('fast_cores', 'on', own=True), 'ok fast_cores true true next_start')
        self.assertEqual(self.set('fast_cores', 'off'), 'ok fast_cores false false next_start')
        self.assertEqual(self.set('fast_cores', 'default', own=True), 'ok fast_cores true null next_start')
        self.assertEqual(self.set('fast_cores', 'default'), 'ok fast_cores false false next_start')   # the main screen's default is off
        self.assertEqual(self.set('fast_cores', 'max'), 'bad bad_value')
        self.assertEqual(self.set('hold60', 'on', own=True), 'ok hold60 true true next_start')
        self.assertEqual(self.set('hold60', 'default', own=True), 'ok hold60 true null next_start')
        self.assertEqual(self.set('hold60', 'on'), 'bad bad_args')   # one game's only
        self.assertEqual(self.set('hold60', 'sometimes', own=True), 'bad bad_value')
        self.assertEqual([self.set('bottom_screen_games', v) for v in ('android', 'Stats', 'cover')],
                         ['ok bottom_screen_games false android next_start', 'ok bottom_screen_games false stats next_start', 'ok bottom_screen_games false cover next_start'])
        self.assertEqual(self.set('bottom_screen_games', 'stats', own=True), 'bad bad_args')   # every game's only
        self.assertEqual(self.set('bottom_screen_games', 'default'), 'bad bad_value')
        self.assertEqual([self.set('display', v, own=True) for v in ('fit', 'stretch', 'fill', 'default')],
                         ['ok display true %d next_start' % i for i in (0, 1, 2, 0)])   # default is Fit (GameSettings.setDisplay's 0)
        self.assertEqual(self.set('display', 'fit'), 'bad bad_args')   # one game's only
        self.assertEqual(self.set('display', 'zoom', own=True), 'bad bad_value')

    def test_set_wiring(self):
        """What Ctl.set and Up do with the new keys, read from the source: the setters the launcher's rows use, Fill only for a game that has it, hold60 only where
        its row shows, the drivers read only for gpu_driver, and GameSettings' Display dialog and the control storing through the same setDisplay"""
        ctl = (CTL / 'Ctl.java').read_text(encoding='utf-8')
        up = (CTL / 'Up.java').read_text(encoding='utf-8')
        gs = (REPO / 'android/app/src/main/java/dev/nxnative/android/GameSettings.java').read_text(encoding='utf-8')
        body = ctl[ctl.index('private static void set('):]; body = body[:body.index('\n    }')]
        self.assertIn('drivers ? Up.drivers(app) : null', body)
        self.assertIn('Up.fillable(id)', body)
        self.assertLess(body.index('Up.fillable(id)'), body.index('Up.apply('), 'Fill is refused before anything is stored')
        self.assertIn('Up.fillUntested(id)', body)
        for call in ('"pinThreadsDefault"', '"pinThreads"', '"hold60"', '"select"', '"choice"', '"setDisplay"', 'bottomScreenInGames(c, false)', 'bottomScreenGamesPage(c,'):
            self.assertIn(call, up)
        self.assertIn('prefsBool("steps", titleId)', up)
        disp = gs[gs.index('private static void display('):]; disp = disp[:disp.index('\n    }')]
        self.assertIn('setDisplay(a, g.titleId, w)', disp)
        self.assertNotIn('Prefs.stretch(a, g.titleId, w == 1)', disp, 'the dialog stores through setDisplay')
        helper = gs[gs.index('static void setDisplay('):]; helper = helper[:helper.index('\n    }')]
        for part in ('Prefs.stretch(a, id, w == 1)', 'Prefs.aspect(a, id, ScreenShape.CHOICE)', 'filling(a, id)', 'Prefs.aspect(a, id, null)'):
            self.assertIn(part, helper)

    # ---- game, the running game's live controls (GameLive) ----

    def live(self, *pairs, limited=False, screen='on', mode='docked', mode_live=True):
        return json.loads(self.run_cmd('live', '1' if limited else '0', screen, mode, '1' if mode_live else '0', *pairs))

    def test_game_live_requests(self):
        self.assertEqual(self.request('cmd=game', 'do=fps_cap', 'value=30', 'id=x1'), 'ok game x1 false null')
        self.assertEqual(self.request('cmd=game', 'do=fps_cap', 'value=OWN'), 'ok game null false null')
        self.assertEqual(self.request('cmd=game', 'do=fps_cap', 'value=default', 'title_id=' + TITLE), 'ok game null false ' + TITLE)
        self.assertEqual(self.request('cmd=game', 'do=console_mode', 'value=handheld'), 'ok game null false null')
        for v in ('toggle', 'on', 'off'):
            self.assertEqual(self.request('cmd=game', 'do=second_screen', 'value=' + v), 'ok game null false null')
        self.assertEqual(self.request('cmd=game', 'do=controls'), 'ok game null false null')
        self.assertEqual(self.request('cmd=game', 'line=A~0.5'), 'ok game null false null')   # a raw line alone is do=ctl, as before
        self.assertEqual(self.request('cmd=game', 'do=fps_cap'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=fps_cap', 'value=fast'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=fps_cap', 'value=-30'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=console_mode', 'value=tv'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=console_mode'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=second_screen', 'value=maybe'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=second_screen'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game', 'do=controls', 'title_id=xyz'), 'bad bad_args')
        self.assertEqual(self.request('cmd=game'), 'bad bad_args')

    def test_game_live_fps_cap(self):
        r = self.live('do=fps_cap', 'value=30')
        self.assertEqual(r['calls'], ['cap 30'])
        res = r['result']
        self.assertEqual((res['id'], res['cmd'], res['ok'], res['pending'], res['message']), ('g1', 'game', True, False, 'The frame rate cap is 30 fps now.'))
        self.assertEqual(res['data'], {'title_id': TITLE, 'cap': '30', 'cap_label': '30 fps', 'running': '30', 'running_label': '30 fps', 'effective': 'now'})
        r = self.live('do=fps_cap', 'value=own')
        self.assertEqual((r['calls'], r['result']['data']['cap'], r['result']['data']['effective']), (['cap 0'], 'own', 'now'))
        # a limited game: 60 is the menu's 0, Game owned waits for the next start (the host's restart rule), 120 is not offered here
        r = self.live('do=fps_cap', 'value=60', limited=True)
        self.assertEqual((r['calls'], r['result']['data']['cap_label'], r['result']['data']['effective']), (['cap 0'], '60 fps', 'now'))
        r = self.live('do=fps_cap', 'value=own', limited=True)
        self.assertEqual(r['calls'], ['cap -2'])
        self.assertEqual(r['result']['message'], 'The frame rate cap is Game owned. It applies at the next start. Until then the game runs at 60 fps.')
        self.assertEqual((r['result']['data']['cap'], r['result']['data']['running'], r['result']['data']['effective']), ('own', '60', 'next_start'))
        r = self.live('do=fps_cap', 'value=120', limited=True)
        self.assertEqual(r['calls'], [])   # refused before anything changes
        res = r['result']
        self.assertEqual((res['ok'], res['error']), (False, 'not_offered'))
        self.assertEqual(res['message'], 'This game does not offer 120 fps. It offers own, 60, 45, 40, 30, 20.')
        self.assertEqual([c['value'] for c in res['data']['offered']], ['own', '60', '45', '40', '30', '20'])
        self.assertEqual(res['data']['offered'][1], {'value': '60', 'label': '60 fps'})
        self.assertEqual(self.live('do=fps_cap', 'value=25')['result']['error'], 'not_offered')   # not in the menu at all
        # default: the game's own choice removed, the default applied live, then removed again so the default stays the default
        r = self.live('do=fps_cap', 'value=default')
        self.assertEqual((r['calls'], r['stored']), (['forget', 'cap 30', 'forget'], 'default'))
        self.assertEqual(r['result']['message'], 'The frame rate cap is back to the default, 30 fps, now.')
        self.assertEqual((r['result']['data']['stored'], r['result']['data']['effective']), ('default', 'now'))

    def test_game_live_console_mode_and_screen(self):
        r = self.live('do=console_mode', 'value=handheld')
        self.assertEqual((r['calls'], r['result']['message'], r['result']['data']['effective']), (['mode 1'], 'The console mode is handheld now.', 'now'))
        r = self.live('do=console_mode', 'value=docked', mode='handheld', mode_live=False)
        self.assertEqual((r['result']['message'], r['result']['data']['effective']), ('The console mode is set to docked. It applies at the next start.', 'next_start'))
        r = self.live('do=console_mode', 'value=docked', mode='fixed')
        self.assertEqual((r['calls'], r['result']['error']), ([], 'not_for_this_game'))
        r = self.live('do=second_screen', 'value=off')
        self.assertEqual((r['calls'], r['result']['data']), (['toggle'], {'title_id': TITLE, 'second_screen': 'off', 'changed': True}))
        r = self.live('do=second_screen', 'value=on')
        self.assertEqual((r['calls'], r['result']['data']['second_screen'], r['result']['data']['changed']), ([], 'on', False))   # shown already: no toggle
        r = self.live('do=second_screen', 'value=toggle', screen='off')
        self.assertEqual((r['calls'], r['result']['data']['second_screen']), (['toggle'], 'on'))
        r = self.live('do=second_screen', 'value=on', screen='none')
        self.assertEqual((r['calls'], r['result']['error']), ([], 'no_second_screen'))

    def test_game_live_controls_and_refusals(self):
        r = self.live('do=controls', limited=True, screen='off')
        d = r['result']['data']
        self.assertEqual(r['calls'], [])
        self.assertEqual((d['console_mode'], d['cap'], d['cap_label'], d['running'], d['second_screen']), ('docked', '60', '60 fps', '60', 'off'))
        self.assertEqual(d['caps'][0], {'value': 'own', 'label': 'Game owned', 'offered': True})
        self.assertEqual([c['value'] for c in d['caps'] if not c['offered']], ['120', '90'])
        self.assertEqual(r['result']['message'], 'Console mode docked. Frame rate cap 60 fps. Second screen hidden.')
        self.assertEqual(self.live('do=controls', 'title_id=0100000000010000')['result']['error'], 'other_game')
        self.assertEqual(self.live('do=controls', 'title_id=' + TITLE.lower())['result']['ok'], True)
        self.assertEqual(self.run_cmd('nolive', 'cmd=game', 'do=controls').split(), ['not_ready', 'no_answer', 'internal'])

    def test_game_live_wiring(self):
        """The cross-process path, read from the source: GameActivity registers GameLink's receiver at onCreate and lets it go at onDestroy; the receiver is not
        exported; Ctl sends an ordered broadcast with the app's package set and a final receiver, and gives up after GameLive.ANSWER_MS"""
        game = (REPO / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java').read_text(encoding='utf-8')
        create = game[game.index('protected void onCreate(Bundle saved) {'):]; create = create[:create.index('\n    }')]
        destroy = game[game.index('protected void onDestroy() {'):]; destroy = destroy[:destroy.index('\n    }')]
        self.assertIn('peanutsCtl = dev.peanuts.ctl.GameLink.register(this);', create)
        self.assertIn('dev.peanuts.ctl.GameLink.unregister(this, peanutsCtl);', destroy)
        self.assertLess(create.index('GameControls.host(controlsHost = peanutsControls())'), create.index('GameLink.register'), 'the controls are there before the receiver')
        link = (CTL / 'GameLink.java').read_text(encoding='utf-8')
        self.assertIn('registerReceiver(r, new IntentFilter(GameLive.ACTION), Context.RECEIVER_NOT_EXPORTED)', link)
        self.assertNotIn('RECEIVER_EXPORTED)', link)
        self.assertIn('setResult(Activity.RESULT_OK, null, b)', link)
        self.assertIn('isOrderedBroadcast()', link)
        self.assertIn('GameControls.host()', link)
        ctl = (CTL / 'Ctl.java').read_text(encoding='utf-8')
        body = ctl[ctl.index('private static void gameLive('):]; body = body[:body.index('\n    }')]
        self.assertIn('new Intent(GameLive.ACTION).setPackage(app.getPackageName())', body)
        self.assertIn('app.sendOrderedBroadcast(i, null, new android.content.BroadcastReceiver()', body)
        self.assertIn('UI.postDelayed(late, GameLive.ANSWER_MS)', body)
        self.assertIn('"not_running"', body)
        self.assertNotIn('sendBroadcast(i)', body)
        hd = ctl[ctl.index('case "game": {'):]; hd = hd[:hd.index('break;')]
        self.assertIn('gameLive(r, res)', hd)
        controls = (REPO / 'android/app/src/main/java/dev/peanuts/ui/GameControls.java').read_text(encoding='utf-8')
        self.assertIn('boolean consoleMode(int m);', controls)

    def test_json_reads_back(self):
        self.assertEqual(self.run_cmd('roundtrip').strip(), "true [text, n, big, neg, ratio, yes, no, none, list, empty]")

    def test_json_is_json(self):
        m = json.loads(self.run_cmd('json'))
        self.assertEqual(list(m), ['schema', 'big', 'ratio', 'nan', 'none', 'yes', 'empty_map', 'empty_list', 'text', 'nested'])
        self.assertEqual((m['schema'], m['big'], m['ratio'], m['nan'], m['none'], m['yes']), (1, 51234567890, 0.5, None, None, True))
        self.assertEqual((m['empty_map'], m['empty_list']), ({}, []))
        self.assertEqual(m['text'], 'back\\slash "q" é   line\nbreak')
        self.assertEqual(m['nested'], {'k': [1, 'two', None, {'k': [1, 'two', None]}]})

    def test_last_result(self):
        ok = json.loads(self.run_cmd('result', 'ok', 'cmd=play', 'id=p1', 'title_id=' + TITLE))
        self.assertEqual(ok, {'id': 'p1', 'cmd': 'play', 'ok': True, 'pending': False, 'error': None, 'message': None, 'time': 1763190000000,
                              'data': {'pack': '/storage/x/pack'}})
        bad = json.loads(self.run_cmd('result', 'fail', 'cmd=play'))
        self.assertEqual((bad['id'], bad['ok'], bad['pending'], bad['error']), (None, False, False, 'refused'))
        self.assertEqual(bad['message'], 'Shaders missing: "quoted"\nsecond line\t… \u0001')
        self.assertNotIn('data', bad)
        wait = json.loads(self.run_cmd('result', 'pending', 'cmd=prepare', 'id=w'))
        self.assertEqual((wait['ok'], wait['pending'], wait['error']), (None, True, None))

    def test_game_from_the_run_record(self):
        rec = 'v=1|title=' + TITLE + '|name=A Game|pid=23456|start=1763189000000|state=running|log=' + TITLE + '-20261115-101500.log'
        self.assertEqual(json.loads(self.run_cmd('exit', '1', rec)),
                         {'running': True, 'title_id': TITLE, 'name': 'A Game', 'pid': 23456, 'since': 1763189000000, 'log': TITLE + '-20261115-101500.log'})
        ended = json.loads(self.run_cmd('exit', '0', rec + '|state=stopped'))   # a later line of a key wins
        self.assertEqual((ended['running'], ended['ended_as']), (False, 'stopped'))
        self.assertEqual(json.loads(self.run_cmd('exit', '0', 'v=1')), None)   # no record of a run: nothing is said of a game
        partial = json.loads(self.run_cmd('exit', '0', 'title=' + TITLE + '|pid=x'))   # what the record lacks is left out, not guessed
        self.assertEqual(partial, {'running': False, 'title_id': TITLE})


if __name__ == '__main__':
    unittest.main()
