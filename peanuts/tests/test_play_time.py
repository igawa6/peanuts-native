"""Total play time (dev.peanuts.launch.PlayTime): the ledger's format and arithmetic on the JVM (PlayLedger has no Android class; skipped without a JDK),
and the source checks that only running time is counted and the game page shows it."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'android/app/src/main/java'
LEDGER = JAVA / 'dev/peanuts/launch/PlayLedger.java'

DRIVER = r'''
import dev.peanuts.launch.PlayLedger;
import java.util.Map;
public class Driver {
    static void eq(Object want, Object got, String what) { if (!want.equals(got)) throw new AssertionError(what + ": want " + want + ", got " + got); }
    public static void main(String[] a) {
        Map<String, PlayLedger.Entry> m = PlayLedger.parse(null);
        eq(0, m.size(), "empty");
        PlayLedger.add(m, "0100abc000000000", 90_000, 111);
        PlayLedger.add(m, "0100ABC000000000", 30_000, 222);
        PlayLedger.add(m, "0100DEF000000000", 0, 333);         // nothing played: no line
        eq(1, m.size(), "one title, case folded");
        eq(120_000L, m.get("0100ABC000000000").ms, "sum");
        eq(222L, m.get("0100ABC000000000").last, "last");
        String text = PlayLedger.format(m) + "garbage line\n0100X\tnope\t1\n\n";
        Map<String, PlayLedger.Entry> back = PlayLedger.parse(text);
        eq(1, back.size(), "round trip skips bad lines");
        eq(120_000L, back.get("0100ABC000000000").ms, "round trip");
        eq(0L, PlayLedger.step(0, 5000, 5000), "first tick counts nothing");
        eq(500L, PlayLedger.step(1000, 1500, 5000), "a normal tick");
        eq(5000L, PlayLedger.step(1000, 600_000, 5000), "a late tick counts one step");
        eq(0L, PlayLedger.step(2000, 1000, 5000), "clock went back");
        eq("Less than a minute", PlayLedger.text(59_999), "text <1 min");
        eq("42 min", PlayLedger.text(42 * 60_000L), "text min");
        eq("3 h", PlayLedger.text(180 * 60_000L), "text h");
        eq("3 h 5 min", PlayLedger.text(185 * 60_000L), "text h min");
        eq("120 h", PlayLedger.text(120 * 3_600_000L + 7 * 60_000L), "text many h");
        PlayLedger.add(back, "0100DEF000000000", 60_000, 444);
        eq(180_000L, PlayLedger.total(back), "total of every title");
        PlayLedger.lastRun(back, "0100abc000000000", 42 * 60_000L);
        PlayLedger.lastRun(back, "0100FFF000000000", 5);                 // a title with no line gets none
        Map<String, PlayLedger.Entry> again = PlayLedger.parse(PlayLedger.format(back));
        eq(42 * 60_000L, again.get("0100ABC000000000").lastRun, "last run round trip");
        eq(2, again.size(), "no line made by lastRun");
        PlayLedger.add(again, "0100ABC000000000", 1_000, 555);
        eq(42 * 60_000L, again.get("0100ABC000000000").lastRun, "adding keeps the last run");
        eq(0L, PlayLedger.parse("0100AAA000000000\t5\t6\n").get("0100AAA000000000").lastRun, "a v1 line (three columns) reads 0");
        eq("<1m", PlayLedger.compact(59_999), "compact <1m");
        eq("42m", PlayLedger.compact(42 * 60_000L), "compact m");
        eq("12h05m", PlayLedger.compact(725 * 60_000L), "compact h m");
        eq("120h", PlayLedger.compact(120 * 3_600_000L + 7 * 60_000L), "compact many h");
        System.out.println("ok");
    }
}
'''


def jdk():
    """A JDK that works (macOS's /usr/bin/javac is a stub that fails without one)"""
    if not (shutil.which('javac') and shutil.which('java')): return False
    return subprocess.run(['javac', '-version'], capture_output=True).returncode == 0


class Ledger(unittest.TestCase):
    @unittest.skipUnless(jdk(), 'no JDK')
    def test_ledger_on_the_jvm(self):
        with tempfile.TemporaryDirectory(prefix='playtime_') as d:
            d = Path(d)
            (d / 'Driver.java').write_text(DRIVER, encoding='utf-8')
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(d), str(LEDGER), str(d / 'Driver.java')], check=True, capture_output=True)
            r = subprocess.run(['java', '-cp', str(d), 'Driver'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn('ok', r.stdout)


class Hooks(unittest.TestCase):
    def test_counts_only_while_the_game_runs(self):
        src = (JAVA / 'dev/nxnative/android/GameActivity.java').read_text(encoding='utf-8')
        tick = src.split('private void fpsTick() {', 1)[1].split('\n', 2)[1]
        self.assertIn('dev.peanuts.launch.PlayTime.tick(this, titleId(), started && resumedNow && !stopped && !paused && prewarmCover == null);   // PEANUTS:', tick)
        destroy = src.split('protected void onDestroy() {', 1)[1].split('super.onDestroy();', 1)[0]
        self.assertIn('dev.peanuts.launch.PlayTime.flush(this);   // PEANUTS:', destroy)
        own = (JAVA / 'dev/peanuts/launch/PlayTime.java').read_text(encoding='utf-8')
        self.assertIn('!dev.peanuts.build.ShaderCapture.active(a)', own)   # Build's preparation boot is no play
        self.assertIn('StandardCopyOption.ATOMIC_MOVE', own)               # Home reads it from another process

    def test_game_page_shows_it(self):
        main = (JAVA / 'dev/nxnative/android/MainActivity.java').read_text(encoding='utf-8')
        self.assertRegex(main, r'dev\.peanuts\.launch\.PlayTime\.line\(this, g\.titleId\);.*"Played".*// PEANUTS:')

    def test_bottom_screen_card_shows_it_and_reads_it_again_at_resume(self):
        lib = (JAVA / 'dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        self.assertIn('k.play = play(r.id());', lib)
        cell = lib.split('private View coverCell(Row r, int cellDp) {', 1)[1].split('return cell;', 1)[0]
        self.assertNotIn('PlayLedger', cell)                         # the covers stay as they were: the total is on the bottom screen
        resumed = lib.split('public void onActivityResumed(Activity x) {', 1)[1].split('String id = played;', 1)[0]
        self.assertIn('l.playTimesAgain();', resumed)                # in place, before the focus-landing early returns
        panel = (JAVA / 'dev/peanuts/ui/BottomPanel.java').read_text(encoding='utf-8')
        self.assertIn('cardStats = stats(c);', panel)
        bind = panel.split('private void bindCard(BottomScreen.Card k) {', 1)[1].split('\n    }\n', 1)[0]
        self.assertIn('PlayLedger.compact(k.play.ms)', bind)
        self.assertIn('PlayLedger.compact(k.play.lastRun)', bind)

    def test_session_toast_only_after_stop(self):
        own = (JAVA / 'dev/peanuts/launch/PlayTime.java').read_text(encoding='utf-8')
        flush = own.split('public static void flush(Activity a) {', 1)[1].split('\n    }\n', 1)[0]
        self.assertIn('a.isFinishing() && !a.isChangingConfigurations()', flush)   # a recreation while the game runs on is no end of a session
        note = own.split('public static String sessionNote(Context c) {', 1)[1].split('\n    }\n', 1)[0]
        self.assertLess(note.index('f.delete();'), note.index('Long.parseLong'))       # shown once, whatever the line holds
        lib = (JAVA / 'dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        self.assertIn('l.sessionToast();', lib)

    def test_settings_total(self):
        src = (JAVA / 'dev/peanuts/ui/LibrarySettings.java').read_text(encoding='utf-8')
        self.assertIn('dev.peanuts.launch.PlayTime.totalLine(a)', src)


if __name__ == '__main__':
    unittest.main()
