"""Issue #514: Play while code mods are on and their build with mods is not ready. The game without its mods started then (a toast said so), and Pokemon
Brilliant Diamond read Luminescent Platinum's save and wrote a new one over it about 11 s after the start, before its first picture. Now no launch path
starts the normal pack while code mods are on: Home's Play asks (ModdedBuild.waitText) and offers Play without mods only after a warning, a frontend or a
shortcut hands the game to Home's Play, and the first start of a new build backs the save up first (SaveStore.beforeNewBuild, its JVM test in
dm/tools/tests/test_android_saves.py). The texts on the JVM (ModdedBuild has no Android class; skipped without a JDK), the wiring by source."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / 'android/app/src/main/java'
MOD_STORE = JAVA / 'dev/peanuts/mods/ModStore.java'
MOD_BUILD = JAVA / 'dev/peanuts/build/ModBuild.java'
MODDED_BUILD = JAVA / 'dev/peanuts/build/ModdedBuild.java'
MAIN = JAVA / 'dev/nxnative/android/MainActivity.java'
FRONTEND = JAVA / 'dev/nxnative/android/FrontendLaunch.java'
GAME = JAVA / 'dev/nxnative/android/GameActivity.java'
SECTION = JAVA / 'dev/peanuts/settings/ModsSection.java'

DRIVER = r'''
import dev.peanuts.build.ModdedBuild;
public class Driver {
    public static void main(String[] a) {
        String name = "Pokemon Brilliant Diamond";
        System.out.println(ModdedBuild.waitText(name, false, 40, 120_000, false, null));
        System.out.println(ModdedBuild.waitText(name, true, -1, 0, true, null));
        System.out.println(ModdedBuild.waitText(name, false, -1, 0, false, "the packer stopped"));
        System.out.println(ModdedBuild.waitText(name, false, -1, 0, false, null));
        System.out.println(ModdedBuild.waitText(name, false, 2, 5_000, false, null));
        System.out.println(ModdedBuild.withoutModsWarning(name));
        System.out.println(ModdedBuild.eta(60_000, 50) + "|" + ModdedBuild.eta(30_000, 50) + "|" + ModdedBuild.eta(1_000, 4) + "|" + ModdedBuild.eta(1_000, 100));
    }
}
'''


def method(src, signature):
    """The body of the method that starts with signature, up to its closing brace at the same indent"""
    a = src.index(signature)
    b = src.index('\n    }\n', a)
    return src[a:b]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class WaitTextTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='mod_play_gate_')
        d = Path(cls.tmp.name)
        (d / 'Driver.java').write_text(DRIVER)
        subprocess.run(['javac', '-d', str(d), str(MOD_STORE), str(MOD_BUILD), str(MODDED_BUILD), str(d / 'Driver.java')], check=True, capture_output=True, text=True)
        cls.lines = subprocess.run(['java', '-cp', str(d), 'Driver'], check=True, capture_output=True, text=True).stdout.splitlines()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_building_says_how_far_and_when(self):
        self.assertIn('It is being made now: 40 %, ready in about 3 min.', self.lines[0])
        self.assertIn('write over the save', self.lines[0])

    def test_stale_waiting_failed_and_not_started(self):
        self.assertIn('made for another version of the app or the game', self.lines[1])
        self.assertIn('starts by itself when the build that is running now finishes', self.lines[1])
        self.assertIn('The last try failed: the packer stopped. ', self.lines[2])
        self.assertIn("Mods & Cheats area makes it", self.lines[3])
        self.assertIn('It is being made now: 2 %.', self.lines[4])   # too early for a time

    def test_warning_names_the_save_and_the_backup(self):
        w = self.lines[5]
        self.assertIn('starts without its code mods', w)
        self.assertIn('before its first picture shows', w)
        self.assertIn('Restore saves', w)

    def test_eta(self):
        self.assertEqual(self.lines[6], 'about a minute|about a minute|null|null')

    def test_plain_text(self):
        for line in self.lines[:6]:
            self.assertNotIn('—', line)
            self.assertNotIn('(', line)


class WiringTest(unittest.TestCase):
    def test_home_play_does_not_start_the_normal_pack_while_code_mods_wait(self):
        launch = method(MAIN.read_text(), '    void launch(Game card) {')
        self.assertNotIn('the game starts without them', launch)
        self.assertNotIn('it starts without its code mods', launch)
        gate = launch.index('if (notBuilt[0] || stale) { modsNotReady(asked, gm, stale); return; }')
        self.assertLess(launch.index('ModsSection.packToPlay('), gate)
        self.assertLess(gate, launch.index('final Game g = gm;'))
        self.assertIn('withoutMods.remove(g0.gameDir)', launch)

    def test_play_without_mods_warns_first_and_is_one_start(self):
        src = MAIN.read_text()
        ask = method(src, '    private void modsNotReady(Game card, Game g, boolean stale) {')
        self.assertIn('ModsSection.due(', ask)   # the build starts when it is due, as the game's page starts it
        self.assertIn('ModsSection.waitText(', ask)
        self.assertRegex(ask, r'Ui\.confirmDanger\(this, "Play without mods\?", dev\.peanuts\.build\.ModdedBuild\.withoutModsWarning')
        self.assertRegex(ask, r'withoutMods\.add\(g\.gameDir\); launch\(card\); withoutMods\.remove\(g\.gameDir\);')

    def test_frontend_and_shortcut_hand_over_to_home(self):
        play = method(FRONTEND.read_text(), '    private void play(Game g) {')
        self.assertNotIn('starts without', play)
        self.assertIn('if (notBuilt[0]) { home(new java.io.File(g.gameDir)); return; }', play)
        self.assertLess(play.index('if (notBuilt[0])'), play.index('new Intent(this, GameActivity.class)'))

    def test_a_running_build_with_mods_is_not_played(self):
        """A variant whose build is still running is not ready: its last module key comes before its shaders, game.tsv and mark (#514, Thor)"""
        src = (JAVA / 'dev/peanuts/settings/ModsSection.java').read_text()
        body = src[src.index('    public static String packToPlay('):]
        body = body[:body.index('\n    }\n') + 6]
        self.assertIn('boolean running = variantId.equals(buildVariant);', body)
        self.assertIn('if (!running && variantProblem(c, titleId, p, variantPack) == null) return variantPack.getPath();', body)

    def test_every_play_goes_through_launch_or_the_frontend(self):
        """The game's screen with a player's pack is started in two places only: MainActivity.startWithDriver (after launch) and FrontendLaunch.play"""
        starts = []
        for f in JAVA.rglob('*.java'):
            for m in re.finditer(r'new Intent\(this, GameActivity\.class\)|setClassName\([^;\n]*"dev\.nxnative\.android\.GameActivity"', f.read_text()):
                starts.append(f.name)
        # ShaderCapture opens the game's screen with its own empty save (ShaderCapture.save), never the player's
        self.assertEqual(sorted(starts), ['FrontendLaunch.java', 'MainActivity.java', 'ShaderCapture.java'])

    def test_game_backs_up_before_the_host_has_the_save(self):
        src = GAME.read_text()
        start = method(src, '    private void start() {')
        hook = start.index('beforeNewBuild(save, game, id, saveId, packDir)')
        self.assertLess(start.index('save = saveDir(files, game, saveId)'), hook)
        self.assertLess(hook, start.index('"DM_SAVE=" + save'))
        body = method(src, '    private void beforeNewBuild(File save, File game, String id, String saveId, File packDir) throws IOException {')
        # a failed copy stops a start of the game's own pack while code mods are on (Play without mods), the start #514 lost saves to
        self.assertIn('if (!dev.peanuts.build.ModdedBuild.isVariantPack(packDir))\n                throw new IOException(', body)
        self.assertIn('ModsSection.codeModsOn(this, id, game, packDir)', body)
        self.assertIn('SaveStore.beforeNewBuild(', body)
        self.assertLess(body.index('ModsSection.codeModsOn('), body.index('SaveStore.beforeNewBuild('), 'only games with code mods are backed up')
        self.assertIn('UserSaveDirs.others(save)', body)
        self.assertIn('SaveZip.appBackups(appRoot, saveId) : shared', body)   # where Back up saves puts them: Restore saves lists it

    def test_mods_section_says_play_waits(self):
        src = SECTION.read_text()
        self.assertNotIn('Play starts the game without', src)
        self.assertIn('Until then Play waits for it.', src)


if __name__ == '__main__':
    unittest.main()
