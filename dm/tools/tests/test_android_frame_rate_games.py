"""The frame rate caps every release game offers (peanuts/release-titles.txt), pinned: each profile's game.tsv turned into the launcher's profile columns the way
android/app/build.gradle does (checked against build.gradle's own closures when Gradle's Groovy is at hand), parsed by Profile.java and passed through FrameRateCaps.java,
the policy Game settings, the in-game panel, the stats page and adb share (Prefs.offeredCaps). Before a first run: the fps_target list from the profile alone (a run can add
an Unreal game's 40 and 45 from the host's answer). From the fps cap audit (pn-build/fps-cap-audit.md, 2026-11-24): B1 opt-in present pacing up to 60, B3 the profile's
fps_target as the default, B4 a frame step only from a FRAME_STEP the patches define, B5 caps above 60 only on a display that shows them, 40 and 45 where engine rows hold them."""
import glob
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
JAVA = REPO / 'android/app/src/main/java/dev/nxnative/android'
GRADLE = REPO / 'android/app/build.gradle'
GAMES = REPO / 'dm/games'
VSYNC_KINDS = {'VSYNCS_I32', 'MIN_VSYNCS_I32', 'STEPS_I32', 'EXTRA_VSYNCS_U8', 'MODE_U32', 'TICKS_U32'}   # build.gradle vsyncKinds (TICKS_U32 presents in whole vsyncs)
STUB = 'package dev.nxnative.android;\npublic final class BuildConfig { public static final String NX_PROFILES = ""; }\n'
HARNESS = r'''package dev.nxnative.android;
import java.nio.file.*;
import java.util.*;
public class FrameRateGames {
    static String word(int v, boolean limited) { return limited ? (v == -2 ? "own" : v == 0 ? "60" : String.valueOf(v)) : (v == 0 ? "own" : String.valueOf(v)); }
    static String list(int[] menu, int[] offered, boolean limited) {
        StringBuilder b = new StringBuilder();
        for (int v : menu) if (FrameRateCaps.contains(offered, v)) b.append(b.length() > 0 ? "," : "").append(word(v, limited));
        return b.toString();
    }
    public static void main(String[] a) throws Exception {
        for (Profile p : Profile.parse(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"))) {
            boolean lim = FrameRateCaps.limited(p);
            StringBuilder out = new StringBuilder(p.titleId).append('|').append(lim ? "limit" : "target").append('|').append(FrameRateCaps.steps(p) ? "steps" : "-");
            for (int hz : new int[] {60, 90, 120}) {
                int[] offered = lim ? FrameRateCaps.paceOffered(p, hz) : FrameRateCaps.targetCaps(p, -1);
                out.append('|').append(list(lim ? FrameRateCaps.menu() : FrameRateCaps.targetMenu(), offered, lim));
            }
            int d = FrameRateCaps.targetDefault(p, null);
            out.append('|').append(lim ? "-" : d < 0 ? "profile " + p.fpsTarget : String.valueOf(d));
            out.append('|').append(FrameRateCaps.above60(lim ? FrameRateCaps.paceOffered(p, 120) : new int[0]) ? "high" : "-");
            System.out.println(out);
        }
    }
}
'''

# Per release game: list (frame limit list "limit", fps_target list "target"), Hold 60 fps row, the caps on a 60 Hz / 90 Hz / 120 Hz display, the fps_target list's default
# with no choice and no main screen cap set ("profile N": the profile's own target), whether "Above 60 fps is experimental" shows on a 120 Hz display


def rows(t, key):
    p = GAMES / t / 'game.tsv'
    if not p.is_file(): return []
    return [l.rstrip('\n').split('\t') for l in p.read_text(encoding='utf-8').splitlines() if l.startswith(key + '\t')]


def lock_kinds():
    kinds = {}
    for l in (REPO / 'dm/host/hle_fpslock.c').read_text(encoding='utf-8').splitlines():
        m = re.match(r'^\s*\{\s*"([0-9a-f]{32})",\s*"[^"]*",[^,]*,[^,]*,\s*([A-Z0-9_]+)', l)
        if m: kinds.setdefault(m.group(1), []).append(m.group(2))
    return kinds


def columns(t, kinds):
    """The frame rate columns of t's BuildConfig.NX_PROFILES line, as build.gradle writes them: {column index: value}"""
    def one(k):
        rs = rows(t, k)
        return rs[0][1].strip() if rs and len(rs[0]) > 1 else ''
    fl = one('fps_limit')
    ft = rows(t, 'fps_target')
    pp = GAMES / t / 'patches.py'
    text = pp.read_text(encoding='utf-8', errors='replace') if pp.is_file() else ''
    steps = bool(rows(t, 'fps_steps')) or (bool(ft) and len(ft[0]) > 1 and ft[0][1].strip() != '0' and re.search(r'(?m)^\s*FRAME_STEP\s*=', text) is not None)
    only = [w for r in rows(t, 'android_builds') for v in r[1:] for w in v.split()]
    mains = [next((x[5:] for x in c[3:] if x.startswith('main=')), None) for c in rows(t, 'build') if not only or c[1].strip() in only]
    mains = [m for m in mains if m]
    rates = '40,45' if mains and all(any(k not in VSYNC_KINDS for k in kinds.get(m, [])) for m in mains) else ''
    target = one('fps_target')
    return {13: '1' if rows(t, 'fps_decouple') else '', 15: '' if fl in ('', '0') else fl, 17: '1' if rows(t, 'fps_limit_present') else '', 18: '1' if steps else '',
            31: one('fps_limit_max'), 32: ';'.join(','.join(c[1:6]) for c in rows(t, 'fpslocker') + rows(t, 'fps_memory')),
            33: ';'.join(','.join(c[1:5]) for c in rows(t, 'fpslocker_plan') + rows(t, 'fps_plan')), 34: one('fps_limit_min'), 35: one('fps_modes'),
            36: ';'.join(','.join(c[1:5]) for c in rows(t, 'fps_plan_120')), 37: '1' if rows(t, 'fps_step_elapsed') else '', 38: '1' if rows(t, 'fps_owned_default') else '',
            41: target if re.fullmatch(r'[1-9][0-9]*', target) else '', 42: rates}


def release():
    return [t for t in (REPO / 'peanuts/release-titles.txt').read_text().split() if t.strip()]


def table(titles):
    kinds = lock_kinds()
    out = []
    for t in titles:
        c = ['' for _ in range(43)]
        c[0], c[1], c[2] = t, t, '0'
        for k, v in columns(t, kinds).items(): c[k] = v
        out.append('\t'.join(c))
    return '\n'.join(out)


def run_harness(titles):
    with tempfile.TemporaryDirectory() as d:
        (Path(d) / 'BuildConfig.java').write_text(STUB)
        (Path(d) / 'FrameRateGames.java').write_text(HARNESS)
        (Path(d) / 'table.tsv').write_text(table(titles), encoding='utf-8')
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', d, str(JAVA / 'Profile.java'), str(JAVA / 'FrameRateCaps.java'), str(Path(d) / 'BuildConfig.java'), str(Path(d) / 'FrameRateGames.java')],
                       check=True, capture_output=True, text=True)
        r = subprocess.run(['java', '-cp', d, 'dev.nxnative.android.FrameRateGames', str(Path(d) / 'table.tsv')], capture_output=True, text=True, check=True)
        return {l.split('|')[0]: tuple(l.split('|')[1:]) for l in r.stdout.splitlines()}


def groovy_jar():
    found = sorted(glob.glob(os.path.expanduser('~/.gradle/wrapper/dists/gradle-*/*/gradle-*/lib/groovy-3*.jar')))
    found = [f for f in found if re.search(r'/groovy-3[0-9.]*\.jar$', f)]
    return found[-1] if found else None


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'needs a JDK')
class ReleaseGameCaps(unittest.TestCase):
    def test_every_release_game_offers_its_pinned_caps(self):
        got = run_harness(release())
        self.assertEqual(sorted(got), sorted(release()))
        for t in release():
            with self.subTest(game=t, name=EXPECTED[t][0]):
                self.assertEqual(got[t], EXPECTED[t][1:])

    def test_must_haves_where_the_game_holds_them(self):
        """30 and 60 on every game whose list has more than Game owned, unless its profile names the rates (fps_modes: Sparking! ZERO's battle step is 1/60 s)"""
        got = run_harness(release())
        for t, v in got.items():
            caps = v[3].split(',')
            if caps != ['own'] and not rows(t, 'fps_modes'):
                with self.subTest(game=t):
                    self.assertIn('30', caps); self.assertIn('60', caps)

    def test_b1_present_pacing_profiles_reach_the_frame_limit_list(self):
        got = run_harness(release())
        for t in release():
            if rows(t, 'fps_limit_present'):
                with self.subTest(game=t):
                    self.assertEqual(got[t][0], 'limit')
                    self.assertTrue(got[t][3].startswith('own,'))

    def test_b4_only_a_defined_frame_step_gives_hold_60(self):
        """Hold 60 fps (Prefs.steps): Age of Calamity only. Link's Awakening's patches.py names FRAME_STEP in its header comment only (it had the row, doing nothing);
        Astral Chain, Breath of the Wild, Metroid Dread and Tears of the Kingdom define a frame step but step by elapsed time or a clock plan"""
        got = run_harness(release())
        self.assertEqual(sorted(t for t, v in got.items() if v[1] == 'steps'), ['01002B00111A2000'])
        kinds = lock_kinds()
        self.assertEqual(sorted(t for t in release() if columns(t, kinds)[18] == '1'),
                         sorted(['01002B00111A2000', '01007300020FA000', '01007EF00011E000', '010093801237C000', '0100F2C0115B6000']))

    def test_b5_nothing_above_the_display(self):
        got = run_harness(release())
        for t, v in got.items():
            with self.subTest(game=t):
                self.assertFalse({'90', '120'} & set(v[2].split(',')))
                self.assertNotIn('120', v[3].split(','))

    @unittest.skipUnless(groovy_jar(), "needs Gradle's Groovy (~/.gradle/wrapper/dists)")
    def test_columns_match_build_gradle(self):
        """The emulation above against build.gradle's own closures, run by Groovy, for every release game"""
        src = GRADLE.read_text(encoding='utf-8').splitlines()
        names = ['tsvRows', 'androidBuildsOf', 'decoupleOf', 'fpsLimitOf', 'fpsLimitMaxOf', 'fpsLockerOf', 'fpsLimitMinOf', 'fpsModesOf', 'fpsPlan120Of', 'fpsLockerPlanOf',
                 'presentOnlyOf', 'stepsOf', 'fpsTargetOf', 'lockKinds', 'vsyncKinds', 'fpsRatesOf']
        picked = []
        for n in names:
            i = next(k for k, l in enumerate(src) if l.startswith('def ' + n + ' '))
            if src[i].rstrip().endswith(('->', '{')):
                j = next(k for k in range(i + 1, len(src)) if src[k] == '}')
                picked += src[i:j + 1]
            else:
                picked.append(src[i])
            if n == 'lockKinds': picked.append(next(l for l in src if l.startswith("new File(repo, 'dm/host/hle_fpslock.c')")))
        cols = {13: 'decoupleOf', 15: 'fpsLimitOf', 17: 'presentOnlyOf', 18: 'stepsOf', 31: 'fpsLimitMaxOf', 32: 'fpsLockerOf', 33: 'fpsLockerPlanOf', 34: 'fpsLimitMinOf',
                35: 'fpsModesOf', 36: 'fpsPlan120Of', 41: 'fpsTargetOf', 42: 'fpsRatesOf'}
        script = ['class GradleException extends RuntimeException { GradleException(String m) { super(m) } }', f'def repo = new File({str(REPO)!r})'] + picked
        script.append('args.each { g -> println(([g] + [' + ', '.join(f'{f}(g)' for f in cols.values()) + "]).join('|')) }")
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / 'cols.groovy').write_text('\n'.join(script).replace("'" + str(REPO) + "'", '"' + str(REPO) + '"'), encoding='utf-8')
            r = subprocess.run(['java', '-cp', groovy_jar(), 'groovy.ui.GroovyMain', str(Path(d) / 'cols.groovy')] + release(), capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr[-2000:])
        kinds = lock_kinds()
        for line in r.stdout.splitlines():
            f = line.split('|')
            mine = columns(f[0], kinds)
            with self.subTest(game=f[0]):
                self.assertEqual(f[1:], [mine[k] for k in cols])


EXPECTED = {
    '0100000000010000': ('Super Mario Odyssey', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100000011D90000': ('Pokémon Brilliant Diamond', 'limit', '-', 'own,60,45,40,30', 'own,90,60,45,40,30', 'own,120,90,60,45,40,30', '-', 'high'),
    '010015100B514000': ('Super Mario Bros. Wonder', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100152000022000': ('Mario Kart 8 Deluxe', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '010018E011D92000': ('Pokémon Shining Pearl', 'limit', '-', 'own,60,45,40,30', 'own,90,60,45,40,30', 'own,120,90,60,45,40,30', '-', 'high'),
    '01001B300B9BE000': ('Diablo III: Eternal Collection', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01001F5010DFA000': ('Pokémon Legends: Arceus', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '010028600EBDA000': ("Super Mario 3D World + Bowser's Fury", 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '01002DA013484000': ('The Legend of Zelda: Skyward Sword HD', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01003E601E324000': ('DRAGON QUEST III HD-2D Remake', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100453019AA8000': ('Xenoblade Chronicles X: Definitive Edition', 'limit', '-', 'own,60,30', 'own,60,30', 'own,120,60,30', '-', 'high'),
    '01004D300C5AE000': ('Kirby and the Forgotten Land', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '010051F0207B2000': ('Tomodachi Life: Living the Dream', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '01005270232F2000': ('OCTOPATH TRAVELER 0', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '010055D009F78000': ('Fire Emblem: Three Houses', 'limit', '-', 'own,60,45,40,30,20', 'own,60,45,40,30,20', 'own,60,45,40,30,20', '-', '-'),
    '01005CA01580E000': ('Persona 5 Royal', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100606026F12000': ('FINAL FANTASY RESONANCE', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '010069401ADB8000': ('Unicorn Overlord', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01006B601380E000': ("Kirby's Return to Dream Land Deluxe", 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '01006BB00C6F0000': ("The Legend of Zelda: Link's Awakening", 'limit', '-', 'own,60,30', 'own,60,30', 'own,60,30', '-', '-'),
    '01006F8002326000': ('Animal Crossing: New Horizons', 'limit', '-', 'own,60', 'own,60', 'own,60', '-', '-'),   # PEANUTS: Game owned or the 60 fps variant (speedfix60)
    '01007300020FA000': ('Astral Chain', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01007EF00011E000': ('The Legend of Zelda: Breath of the Wild', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01008CF01BAAC000': ('The Legend of Zelda: Echoes of Wisdom', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', 'profile 60', '-'),
    '01008DB008C2C000': ('Pokémon Shield', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', 'profile 60', '-'),
    '010093801237C000': ('Metroid Dread', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01009720213B0000': ('Captain Tsubasa 2: World Fighters', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '010097F018538000': ('DAVE THE DIVER', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '010099A00BC1E000': ('Resident Evil 4', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100A0F00DA68000': ('Monster Sanctuary', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100A6301214E000': ('Fire Emblem Engage', 'limit', '-', 'own,60,30,20', 'own,60,30,20', 'own,60,30,20', '-', '-'),
    '0100ABF008968000': ('Pokémon Sword', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', 'profile 60', '-'),
    '0100AD80208A8000': ('Demon Slayer -Kimetsu no Yaiba- The Hinokami Chronicles 2', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100B63016916000': ('My Time at Sandrock', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100B8E016F76000': ('NieR:Automata The End of YoRHa Edition (Asian release)', 'limit', '-', 'own,60,45,40,30', 'own,60,45,40,30', 'own,60,45,40,30', '-', '-'),
    '0100BA4026E94000': ("Castlevania: Belmont's Curse", 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100C2901153C000': ('Yoru, Tomosu', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100C49025D3E000': ('EA Sports FC 27', 'target', '-', 'own,60,45,40,30,20,15', 'own,60,45,40,30,20,15', 'own,60,45,40,30,20,15', 'profile 30', '-'),
    '0100C510166F0000': ('Chained Echoes', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100DCA0064A6000': ("Luigi's Mansion 3", 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100F2C0115B6000': ('The Legend of Zelda: Tears of the Kingdom', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100F43008C44000': ('Pokémon Legends: Z-A', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100F9F00C696000': ('Crash Team Racing Nitro-Fueled', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100DAE01E4C8000': ('STORY OF SEASONS: Grand Bazaar', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01006AE02236C000': ('Double Dragon Revive', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01007A2027548000': ('Trails in the Sky 2nd Chapter', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '010035F022078000': ('DRAGON BALL: Sparking! ZERO', 'target', '-', 'own,60', 'own,60', 'own,60', 'profile 60', '-'),
    '0100A3D008C5C000': ('Pokémon Scarlet', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01008F6008C5E000': ('Pokémon Violet', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '010051C0134F8000': ('DRAGON BALL Z: KAKAROT + A NEW POWER AWAKENS SET', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01008BC024FDE000': ('STEINS;GATE RE:BOOT', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '01002B00111A2000': ('Hyrule Warriors: Age of Calamity', 'limit', 'steps', 'own,60,30', 'own,60,30', 'own,60,30', '-', '-'),
    '01002C9022770000': ('Trails in the Sky 1st Chapter', 'limit', '-', 'own,60,45,40,30,20', 'own,60,45,40,30,20', 'own,60,45,40,30,20', '-', '-'),
    '01006000040C2000': ("Yoshi's Crafted World", 'limit', '-', 'own,60,45,30,20', 'own,60,45,30,20', 'own,60,45,30,20', '-', '-'),
    '0100DE501433A000': ('Tetris Effect: Connected', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100F4300BF2C000': ('New Pokémon Snap', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '010062E01FE0C000': ('Digimon Story Time Stranger', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100F7E00C70E000': ('Hogwarts Legacy', 'limit', '-', 'own,60,45,40,30,20', 'own,60,45,40,30,20', 'own,60,45,40,30,20', '-', '-'),
    '010065301A2E0000': ('Star Ocean The Second Story R', 'limit', '-', 'own,60,30,20', 'own,60,30,20', 'own,60,30,20', '-', '-'),
    '010075101EF84000': ('Stray', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', 'profile 60', '-'),
    '01003FC01670C000': ('13 Sentinels: Aegis Rim', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '01009BF0072D4000': ('Captain Toad: Treasure Tracker', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100300012F2A000': ('Advance Wars 1+2: Re-Boot Camp', 'limit', '-', 'own,60,30,20', 'own,60,30,20', 'own,60,30,20', '-', '-'),
    '0100755017EE0000': ('FANTASY LIFE i: The Girl Who Steals Time', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '010003F003A34000': ("Pokémon: Let's Go, Pikachu!", 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100187003A36000': ("Pokémon: Let's Go, Eevee!", 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100F9D023176000': ('Viewfinder', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '010089B01AB44000': ('SPY×ANYA: Operation Memories', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100BC0018138000': ('Super Mario RPG', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100563010E0C000': ('WarioWare: Get It Together!', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '0100739018020000': ('LEGO 2K Drive', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '010085500130A000': ('LEGO CITY UNDERCOVER', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01006F600FFC8000': ('LEGO MARVEL Super Heroes', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '0100A7C01B792000': ('Minecraft Dungeons II', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
    '01003D100E9C6000': ('The Witcher 3: Wild Hunt — Complete Edition', 'limit', '-', 'own,60,45,40,30,20', 'own,90,60,45,40,30,20', 'own,120,90,60,45,40,30,20', '-', 'high'),
    '01003D200BAA2000': ('Pokémon Mystery Dungeon: Rescue Team DX', 'target', '-', 'own,60,30,20,15', 'own,60,30,20,15', 'own,60,30,20,15', '60', '-'),
}

if __name__ == '__main__':
    unittest.main()
