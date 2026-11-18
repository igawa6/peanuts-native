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
SRC = [CTL / n for n in ('Request.java', 'Settings.java', 'Result.java', 'Json.java', 'ExitRecord.java')]
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
                    Settings.Change c = Settings.parse(a[1], a[2], own, paced ? new int[] {-2, 0, 45, 40, 30, 20} : new int[] {0, 60, 30, 20, 15}, paced);
                    System.out.println("ok " + c.key + " " + c.perGame + " " + c.value + " " + c.effective);
                } catch (Request.Bad e) { System.out.println("bad " + e.code); }
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

    def set(self, key, value, own=False, paced=False):
        return self.run_cmd('set', key, value, '1' if own else '0', '1' if paced else '0').strip()

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
        for key in ('env', 'gpu_driver', 'game_dir', 'build_dir', 'pad_map', 'adb_control', 'lang', 'window', 'folder_save_' + TITLE):
            self.assertEqual(self.set(key, 'x'), 'bad unknown_key', key)

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
