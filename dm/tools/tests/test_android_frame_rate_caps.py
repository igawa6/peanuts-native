"""Saved frame-limit values and opt-in high-rate choices, using the production Java policy (FrameRateCaps.java with Profile.java). PEANUTS: also the rules of the
fps cap audit fixes (2026-11-24): saved caps moved between the two lists (migrate), caps above the display (B5), the fps_target list's 40 and 45 and the host's answer,
the profile's fps_target as the default (B3), opt-in present pacing up to 60 (B1). Per release game: test_android_frame_rate_games.py."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / 'android/app/src/main/java/dev/nxnative/android/FrameRateCaps.java'
PROFILE = REPO / 'android/app/src/main/java/dev/nxnative/android/Profile.java'
STUB = 'package dev.nxnative.android;\npublic final class BuildConfig { public static final String NX_PROFILES = ""; }\n'
MAIN = '''package dev.nxnative.android;
import java.util.Arrays;
public class FrameRateCapsTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static void same(int[] a, int... b) { if (!Arrays.equals(a, b)) throw new AssertionError(Arrays.toString(a) + " != " + Arrays.toString(b)); }
    static Profile profile(String fps) {   // the frame rate columns of a NX_PROFILES line: "13=1,15=60,..."
        String[] c = new String[43]; Arrays.fill(c, ""); c[0] = "0100000000001000"; c[2] = "0";
        for (String kv : fps.split(";")) if (!kv.isEmpty()) c[Integer.parseInt(kv.substring(0, kv.indexOf('=')))] = kv.substring(kv.indexOf('=') + 1);
        return Profile.parse(String.join("\\t", c)).get(0);
    }
    public static void main(String[] args) {
        check(Arrays.equals(FrameRateCaps.rates(60), new int[] {-2, 0, 45, 40, 30, 20}));
        check(Arrays.equals(FrameRateCaps.rates(90), new int[] {-2, 90, 0, 45, 40, 30, 20}));
        check(Arrays.equals(FrameRateCaps.rates(120), new int[] {-2, 120, 90, 0, 45, 40, 30, 20}));
        check(FrameRateCaps.normalize(120, 60) == 0); // legacy profiles still stop at 60
        check(FrameRateCaps.normalize(120, 90) == 90);
        check(FrameRateCaps.normalize(120, 120) == 120);
        check(FrameRateCaps.normalize(90, 120) == 90);
        check(FrameRateCaps.normalize(60, 120) == 0); // saved zero retains its old meaning
        check(FrameRateCaps.normalize(0, 120) == 0 && FrameRateCaps.normalize(-1, 120) == 0);
        for (int rate : new int[] {45, 40, 30, 20}) check(FrameRateCaps.normalize(rate, 120) == rate);
        check(FrameRateCaps.normalize(15, 120) == 20);
        int[] changed = FrameRateCaps.rates(120); changed[0] = 999;
        check(FrameRateCaps.rates(120)[0] == -2);
        check(FrameRateCaps.normalize(-2, 60) == -2 && FrameRateCaps.normalize(-2, 120) == -2);
        check(FrameRateCaps.contains(FrameRateCaps.menu(), 90));
        check(!FrameRateCaps.contains(FrameRateCaps.rates(120, 30), 20));
        check(FrameRateCaps.contains(FrameRateCaps.rates(120, 30), -2));
        check(FrameRateCaps.contains(FrameRateCaps.rates(120, 30), 30));
        check(FrameRateCaps.contains(FrameRateCaps.rates(120, 30), 120));

        // PEANUTS: B1, opt-in present pacing: fps_limit_present with no fps_limit, whatever fps_limit_max (before 0.1.8: only above 60)
        Profile trails1 = profile("17=1"), ycw = profile("17=1;35=20,30,45,60"), dave = profile("17=1;31=120"), limit = profile("15=60;31=120"), plain = profile("41=60");
        check(FrameRateCaps.limited(trails1) && !FrameRateCaps.legacyLimited(trails1));
        check(FrameRateCaps.limited(ycw) && !FrameRateCaps.legacyLimited(ycw));
        check(FrameRateCaps.limited(dave) && FrameRateCaps.legacyLimited(dave));
        check(FrameRateCaps.limited(limit) && FrameRateCaps.legacyLimited(limit));
        check(!FrameRateCaps.limited(plain) && !FrameRateCaps.legacyLimited(plain) && !FrameRateCaps.limited(null));
        check(FrameRateCaps.limited(profile("13=1")) && FrameRateCaps.paceMax(profile("13=1;17=1;31=120")) == 60);   // a paced game: limited, at most 60
        same(FrameRateCaps.paceCaps(trails1), -2, 0, 45, 40, 30, 20);
        same(FrameRateCaps.paceCaps(ycw), -2, 0, 45, 30, 20);
        check(FrameRateCaps.paceMax(trails1) == 60 && FrameRateCaps.paceMax(dave) == 120);

        // B5: nothing above the display's highest refresh rate is offered; a stored 120 is kept and runs at the display's rate
        same(FrameRateCaps.paceOffered(limit, 60), -2, 0, 45, 40, 30, 20);
        same(FrameRateCaps.paceOffered(limit, 90), -2, 90, 0, 45, 40, 30, 20);
        same(FrameRateCaps.paceOffered(limit, 120), -2, 120, 90, 0, 45, 40, 30, 20);
        same(FrameRateCaps.paceOffered(limit, 144), -2, 120, 90, 0, 45, 40, 30, 20);
        same(FrameRateCaps.paceOffered(limit, 48), -2, 0, 45, 40, 30, 20);   // a display below 60 still gets 60 and under
        check(FrameRateCaps.hidesAbove(FrameRateCaps.paceCaps(limit), 60) && FrameRateCaps.hidesAbove(FrameRateCaps.paceCaps(limit), 90));
        check(!FrameRateCaps.hidesAbove(FrameRateCaps.paceCaps(limit), 120) && !FrameRateCaps.hidesAbove(FrameRateCaps.paceCaps(trails1), 60));
        check(FrameRateCaps.onScreen(120, 60) == 0 && FrameRateCaps.onScreen(120, 90) == 90 && FrameRateCaps.onScreen(120, 120) == 120 && FrameRateCaps.onScreen(90, 60) == 0);
        check(FrameRateCaps.onScreen(-2, 60) == -2 && FrameRateCaps.onScreen(0, 60) == 0 && FrameRateCaps.onScreen(45, 60) == 45);
        check(FrameRateCaps.limitRate(120, 60) == 60 && FrameRateCaps.limitRate(120, 90) == 90 && FrameRateCaps.limitRate(0, 120) == 60 && FrameRateCaps.limitRate(30, 60) == 30);
        check(FrameRateCaps.screenMax(60) == 60 && FrameRateCaps.screenMax(48) == 60 && FrameRateCaps.screenMax(120) == 120);
        // "Above 60 fps is experimental" only where something above 60 is offered
        check(FrameRateCaps.above60(FrameRateCaps.paceOffered(limit, 120)) && !FrameRateCaps.above60(FrameRateCaps.paceOffered(limit, 60)) && !FrameRateCaps.above60(FrameRateCaps.paceCaps(trails1)));

        // The fps_target list: Game owned, 60, 30, 20, 15 before a run; 40 and 45 with the engine rows' rates (fps_rates); the host's answer when it has one; fps_modes restricts
        same(FrameRateCaps.targetCaps(plain, -1), 0, 60, 30, 20, 15);
        Profile sword = profile("41=60;42=40,45"), sparking = profile("41=60;35=60");
        same(FrameRateCaps.targetCaps(sword, -1), 0, 60, 45, 40, 30, 20, 15);
        same(FrameRateCaps.targetCaps(sparking, -1), 0, 60);
        same(FrameRateCaps.targetCaps(plain, 0), 0);   // the game picks its own rate
        same(FrameRateCaps.targetCaps(plain, 63), 0, 60, 45, 40, 30, 20, 15);   // an Unreal game whose t.MaxFPS was found: all six
        same(FrameRateCaps.targetCaps(sword, 1 | 2 | 4 | 32), 0, 60, 30, 20, 15);   // a vsync row: divisors of 60 only
        same(FrameRateCaps.targetCaps(null, -1), 0, 60, 30, 20, 15);
        check(FrameRateCaps.rateBit(15) == 0 && FrameRateCaps.rateBit(45) == 4 && FrameRateCaps.rateBit(60) == 5 && FrameRateCaps.rateBit(90) == -1);
        // a stored cap the list does not offer: the nearest offered at or below, else the lowest; Default, Game owned and a Game owned only list leave it
        int[] base = {0, 60, 30, 20, 15};
        check(FrameRateCaps.targetCap(45, base) == 30 && FrameRateCaps.targetCap(120, base) == 60 && FrameRateCaps.targetCap(-1, base) == -1 && FrameRateCaps.targetCap(0, base) == 0);
        check(FrameRateCaps.targetCap(45, FrameRateCaps.targetCaps(sword, -1)) == 45 && FrameRateCaps.targetCap(30, new int[] {0, 60}) == 60 && FrameRateCaps.targetCap(30, new int[] {0}) == 30);

        // B3: with no own or recommended cap, the profile's fps_target wins over the main screen's default 60; a main screen cap the player set wins over it
        check(FrameRateCaps.targetDefault(profile("41=30"), null) == -1);
        check(FrameRateCaps.targetDefault(profile("41=30"), 60) == 60 && FrameRateCaps.targetDefault(profile("41=30"), 0) == 0);
        check(FrameRateCaps.targetDefault(profile(""), null) == 60 && FrameRateCaps.targetDefault(null, null) == 60);
        check(profile("41=30").fpsTarget == 30 && profile("").fpsTarget == 0 && profile("42=40,45").fpsRates.equals("40,45"));

        // The migration: a saved cap keeps the player's choice when the game moves between the lists
        // fps_target list -> frame limit list (Yoshi's Crafted World, Star Ocean The Second Story R, Trails in the Sky 1st Chapter, Hogwarts Legacy in 0.1.8)
        check(FrameRateCaps.migrate(0, false, true) == -2);       // Game owned stays Game owned (0 would be 60 fps there)
        check(FrameRateCaps.migrate(-1, false, true) == null);    // Default: the game's default again (Game owned for opt-in pacing)
        check(FrameRateCaps.migrate(60, false, true) == 0);       // 60 fps is stored as 0 there
        check(FrameRateCaps.migrate(30, false, true) == 30 && FrameRateCaps.migrate(20, false, true) == 20 && FrameRateCaps.migrate(15, false, true) == 20);
        check(FrameRateCaps.migrate(45, false, true) == 45 && FrameRateCaps.migrate(40, false, true) == 40 && FrameRateCaps.migrate(120, false, true) == 0);
        // frame limit list -> fps_target list
        check(FrameRateCaps.migrate(-2, true, false) == 0);       // Game owned
        check(FrameRateCaps.migrate(0, true, false) == 60);       // 60 fps (0 would be Game owned there)
        check(FrameRateCaps.migrate(120, true, false) == 60 && FrameRateCaps.migrate(90, true, false) == 60);
        check(FrameRateCaps.migrate(45, true, false) == 45 && FrameRateCaps.migrate(30, true, false) == 30 && FrameRateCaps.migrate(20, true, false) == 20);
        // the same list: as it is
        for (int v : new int[] {-2, -1, 0, 15, 20, 30, 45, 60, 90, 120}) check(FrameRateCaps.migrate(v, true, true) == v && FrameRateCaps.migrate(v, false, false) == v);
        // Once per game (Prefs.migrateFpsCap): a cap saved before the marker was saved for the list of the rule before 0.1.8; the marker then names the game's list
        FrameRateCaps.Saved s = FrameRateCaps.migrateSaved(ycw, null, 0);   // Yoshi's Crafted World's saved "Game owned" from 0.1.7
        check(s != null && s.marker.equals("limit") && s.cap == -2);
        check(FrameRateCaps.migrateSaved(ycw, "limit", -2) == null);   // the second read: nothing more
        s = FrameRateCaps.migrateSaved(ycw, null, 60);   // its 60 fps
        check(s.marker.equals("limit") && s.cap == 0);
        s = FrameRateCaps.migrateSaved(trails1, null, -1);   // its "Default": the game's own default now (Game owned)
        check(s.marker.equals("limit") && s.cap == null);
        s = FrameRateCaps.migrateSaved(dave, null, 0);   // a game on the same list as before: marked, the cap as it is
        check(s.marker.equals("limit") && s.cap == 0);
        s = FrameRateCaps.migrateSaved(plain, null, 0);
        check(s.marker.equals("target") && s.cap == 0);
        check(FrameRateCaps.migrateSaved(ycw, null, null) == null);   // nothing saved: no marker until a cap is
        s = FrameRateCaps.migrateSaved(ycw, "target", null);   // a stale marker with nothing saved moves along
        check(s.marker.equals("limit") && s.cap == null);
        s = FrameRateCaps.migrateSaved(plain, "limit", -2);   // a later move back (as Tomodachi Life's in #182): Game owned stays Game owned
        check(s.marker.equals("target") && s.cap == 0);
        s = FrameRateCaps.migrateSaved(plain, "limit", 0);
        check(s.marker.equals("target") && s.cap == 60);
        check(FrameRateCaps.migrateSaved(null, null, 0) == null);
    }
}'''


class FrameRateCaps(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'needs a JDK')
    def test_limits_and_saved_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            main = path / 'FrameRateCapsTest.java'
            main.write_text(MAIN)
            (path / 'BuildConfig.java').write_text(STUB)
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(path), str(SOURCE), str(PROFILE), str(path / 'BuildConfig.java'), str(main)], check=True, capture_output=True)
            run = subprocess.run(['java', '-ea', '-cp', str(path), 'dev.nxnative.android.FrameRateCapsTest'], capture_output=True, text=True)
            self.assertEqual(run.returncode, 0, run.stderr)


class MigrationInPrefs(unittest.TestCase):
    """Prefs.migrateFpsCap is Android code (SharedPreferences): its shape, so the rule above is what it runs, once per game"""
    def test_prefs_migrates_once_per_game(self):
        src = (REPO / 'android/app/src/main/java/dev/nxnative/android/Prefs.java').read_text(encoding='utf-8')
        body = src[src.index('    static void migrateFpsCap('):]
        body = body[:body.index('\n    }\n') + 6]
        self.assertIn('FrameRateCaps.migrateSaved(Profile.of(titleId), sp.getString(marker, null), saved ? sp.getInt(key, -1) : null)', body)
        self.assertIn('if (s == null) return;', body)
        self.assertIn('putString(marker, s.marker)', body)
        self.assertIn('if (saved) { if (s.cap == null) e.remove(key); else e.putInt(key, s.cap); }', body)
        for getter in ('static int paceCap(Context c, String titleId) {\n        migrateFpsCap(c, titleId);', 'static int fpsCap(Context c, String titleId) {\n        migrateFpsCap(c, titleId);',
                       'static boolean ownFpsCap(Context c, String titleId) { migrateFpsCap(c, titleId);'):
            self.assertIn(getter, src)
        self.assertEqual(src.count('putString(gameKey(FPS_LIST, titleId), fpsList(titleId))'), 2)   # a cap set (or cleared) now is marked with the game's list


if __name__ == '__main__':
    unittest.main()
