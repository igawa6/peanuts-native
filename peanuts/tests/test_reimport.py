"""Import again and what goes with it (dev.peanuts.build's AutoRelax, PackNote and Reimport: plain Java, javac/java only, the pattern of test_mod_store.py):
which failed job the build tries anyway by itself and which it stops and asks about; the pack folder's note (a pack whose game file was imported again is not
up to date, a pack built without the DLC's shaders says how many); and the plan of an import again: which dumps go in, what is missing and where it was looked
for, the free space for the new file beside the old one, and the words of its questions. The source checks say that the build keeps to them."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / 'android/app/src'
BUILD = JAVA / 'main/java/dev/peanuts/build'
SRC = [BUILD / n for n in ('AutoRelax.java', 'PackNote.java', 'Reimport.java')]

RUNNER = r'''package dev.peanuts.build;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
public final class ReimportTest {
    /** part: <kind b|u|d>,<title id>,<version>,<file or - for a package inside the .nup> */
    static Reimport.Part part(String s) {
        String[] c = s.split(",", -1);
        int kind = c[0].equals("b") ? Reimport.BASE : c[0].equals("u") ? Reimport.UPDATE : Reimport.DLC;
        return new Reimport.Part(kind, c[1], Long.parseLong(c[2]), c[3].equals("-") ? null : new File(c[3]));
    }
    public static void main(String[] a) throws Exception {
        switch (a[0]) {
            case "relax":   // relax <fix> <cause> <tried anyway 0|1> <relaxed before 0|1>  ->  "<auto> <asks>"
                System.out.println(AutoRelax.auto(a[1], a[2], a[3].equals("1"), a[4].equals("1")) + " " + AutoRelax.asks(a[1], a[2], a[3].equals("1")));
                break;
            case "note": {   // note <pack dir> stale|mark|built <n> <total>|without
                File d = new File(a[1]);
                switch (a[2]) {
                    case "stale": System.out.println(PackNote.stale(d)); break;
                    case "mark": System.out.println(PackNote.markStale(d)); break;
                    case "built": PackNote.built(d, Long.parseLong(a[3]), Long.parseLong(a[4])); break;
                    case "without": { long[] w = PackNote.without(d); System.out.println(w == null ? "none" : w[0] + " of " + w[1]); break; }
                    default: throw new IllegalArgumentException(a[2]);
                }
                break;
            }
            case "plan": {   // plan <nup> <free bytes> <chosen update or -> <root>[:<root>...] <part>...  ->  the plan's fields, a line each, then its texts
                List<Reimport.Part> inside = new ArrayList<>(), found = new ArrayList<>();
                for (int i = 5; i < a.length; i++) { Reimport.Part p = part(a[i]); (p.file == null ? inside : found).add(p); }
                List<File> roots = new ArrayList<>();
                for (String r : a[4].split(":")) if (!r.isEmpty()) roots.add(new File(r));
                Reimport.Plan p = Reimport.plan(new File(a[1]), inside, found, a[3].equals("-") ? null : new File(a[3]), roots, Long.parseLong(a[2]));
                System.out.println("missing=" + p.missing);
                System.out.println("base=" + (p.base == null ? null : p.base.getName()));
                System.out.println("update=" + (p.update == null ? null : p.update.getName() + " " + p.updateVersion));
                List<String> d = new ArrayList<>();
                for (File f : p.dlcs) d.add(f.getName());
                System.out.println("dlc=" + String.join(",", d));
                System.out.println("old=" + p.oldBytes + " need=" + p.needBytes + " low=" + p.lowSpace() + " fits_without_old=" + p.fitsWithoutOld());
                if (p.missing == null) {
                    System.out.println("confirm=" + Reimport.confirm(p, "Kart Game").replace("\n", "|"));
                    System.out.println("low_space=" + Reimport.lowSpace(p).replace("\n", "|"));
                    System.out.println("delete_first=" + Reimport.deleteFirst(p, "Kart Game"));
                }
                break;
            }
            case "size": System.out.println(Reimport.size(Long.parseLong(a[1]))); break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
'''
MB = 1 << 20


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Logic(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='reimport_')
        cls.root = Path(cls.tmp.name)
        cls.classes = cls.root / 'classes'
        cls.classes.mkdir()
        runner = cls.root / 'ReimportTest.java'
        runner.write_text(RUNNER, encoding='utf-8')
        subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(cls.classes), *map(str, SRC), str(runner)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def java(self, *args):
        r = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-cp', str(self.classes), 'dev.peanuts.build.ReimportTest', *map(str, args)],
                           capture_output=True, text=True, encoding='utf-8')
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.rstrip('\n')

    # ---- which failure is tried anyway for the player ----

    def test_addon_content_is_asked_not_tried(self):
        self.assertEqual(self.java('relax', 'anyway', 'addon_not_imported', 0, 0), 'false true')   # the card and its choice
        self.assertEqual(self.java('relax', 'anyway', 'addon_not_imported', 1, 0), 'false false')   # the player chose Try anyway: the job runs relaxed, a later failure is a failure

    def test_other_failures_as_before(self):
        self.assertEqual(self.java('relax', 'anyway', '', 0, 0), 'true false')    # shaders that differ, shaders that did not translate, an untested version
        self.assertEqual(self.java('relax', 'anyway', '', 0, 1), 'false false')   # once a run
        self.assertEqual(self.java('relax', 'anyway', '', 1, 0), 'false false')   # relaxed already
        self.assertEqual(self.java('relax', 'retry', '', 0, 0), 'false false')
        self.assertEqual(self.java('relax', 'retry', 'addon_not_imported', 0, 0), 'false false')   # the cause alone offers nothing
        self.assertEqual(self.java('relax', 'none', '', 0, 0), 'false false')
        self.assertEqual(self.java('relax', 'update', '', 0, 0), 'false false')

    # ---- the pack's note ----

    def pack(self, name, tsv=True):
        d = self.root / name
        d.mkdir()
        if tsv: (d / 'pack.tsv').write_text('format\t1\n')
        return d

    def test_an_older_pack_says_nothing(self):
        d = self.pack('old')
        self.assertEqual(self.java('note', d, 'stale'), 'false')
        self.assertEqual(self.java('note', d, 'without'), 'none')
        self.assertEqual(sorted(f.name for f in d.iterdir()), ['pack.tsv'], 'reading writes nothing')

    def test_reimport_marks_and_a_build_clears(self):
        d = self.pack('marked')
        self.assertEqual(self.java('note', d, 'mark'), 'true')
        self.assertEqual(self.java('note', d, 'stale'), 'true')
        self.assertEqual((d / 'pack.tsv').read_text(), 'format\t1\n', 'pack.tsv is the packer\'s: not touched')
        self.java('note', d, 'built', 0, 0)
        self.assertEqual(self.java('note', d, 'stale'), 'false')
        self.assertFalse((d / 'peanuts-pack.tsv').exists(), 'nothing to say: no file')

    def test_only_a_pack_is_marked(self):
        d = self.pack('nopack', tsv=False)
        self.assertEqual(self.java('note', d, 'mark'), 'false')   # a job that stopped before its pack step: it is resumed, nothing is stale
        self.assertEqual(list(d.iterdir()), [])
        self.assertEqual(self.java('note', self.root / 'absent', 'mark'), 'false')

    def test_built_without_addon_shaders(self):
        d = self.pack('without')
        self.java('note', d, 'mark')
        self.java('note', d, 'built', 12, 3400)
        self.assertEqual(self.java('note', d, 'without'), '12 of 3400')
        self.assertEqual(self.java('note', d, 'stale'), 'false')
        self.assertEqual((d / 'peanuts-pack.tsv').read_text(), 'without\t12\t3400\n')
        self.java('note', d, 'mark')   # imported again with the DLC: stale, and still without until the build has run
        self.assertEqual(self.java('note', d, 'stale'), 'true')
        self.assertEqual(self.java('note', d, 'without'), '12 of 3400')
        self.java('note', d, 'built', 0, 0)
        self.assertEqual(self.java('note', d, 'without'), 'none')
        self.assertFalse((d / 'peanuts-pack.tsv').exists())

    def test_a_damaged_note_says_nothing(self):
        d = self.pack('damaged')
        (d / 'peanuts-pack.tsv').write_text('without\tmany\n\nno tab here\n')
        self.assertEqual(self.java('note', d, 'without'), 'none')
        self.assertEqual(self.java('note', d, 'stale'), 'false')

    # ---- the plan ----

    def files(self, name, **sizes):
        d = self.root / name
        d.mkdir()
        out = {}
        for n, mb in sizes.items():
            f = d / (n.replace('_', '.', 1) if '_' in n else n)
            with open(f, 'wb') as h: h.truncate(mb * MB)
            out[n] = f
        return d, out

    def plan(self, nup, free, chosen, roots, *parts):
        out = self.java('plan', nup, free, chosen or '-', ':'.join(map(str, roots)), *parts)
        return dict(l.split('=', 1) for l in out.split('\n'))

    def test_all_found(self):
        d, f = self.files('found', game_nup=40, base_nsp=30, up3_nsp=10, up4_nsp=12, dlc1_nsp=5, dlc2_nsp=6)
        p = self.plan(f['game_nup'], 500 * MB, None, [d],
                      'u,0100152000022800,196608,-', 'b,0100152000022000,0,' + str(f['base_nsp']), 'u,0100152000022800,196608,' + str(f['up3_nsp']),
                      'u,0100152000022800,262144,' + str(f['up4_nsp']), 'd,0100152000023001,0,' + str(f['dlc1_nsp']), 'd,0100152000023002,0,' + str(f['dlc2_nsp']))
        self.assertEqual(p['missing'], 'null')
        self.assertEqual(p['base'], 'base.nsp')
        self.assertEqual(p['update'], 'up4.nsp 262144', 'the newest update found')
        self.assertEqual(p['dlc'], 'dlc1.nsp,dlc2.nsp', 'the DLC found now, though the old .nup had none')
        self.assertEqual(p['old'], f'{40 * MB} need={53 * MB} low=false fits_without_old=true')
        self.assertEqual(p['confirm'], 'This makes the .nup of Kart Game again from your game files, with the DLC found now, then builds the game again. It takes about 25 minutes, in '
                         f'the background.||Deleted: the .nup that is there now (40 MB, {f["game_nup"]}). It is replaced only when the new one is complete: if the import '
                         'fails or is stopped, the game stays as it is.||Kept: your saves, mods and settings, and the built game until the new build replaces it.||'
                         'Found: the base game (base.nsp), update v4 (up4.nsp), 2 DLC. These files must stay in their folders until the import is done.')

    def test_the_chosen_update_wins(self):
        d, f = self.files('chosen', game_nup=1, base_nsp=1, up3_nsp=1, up4_nsp=1)
        p = self.plan(f['game_nup'], 500 * MB, f['up3_nsp'], [d], 'u,0100152000022800,196608,-', 'b,0100152000022000,0,' + str(f['base_nsp']),
                      'u,0100152000022800,262144,' + str(f['up4_nsp']), 'u,0100152000022800,196608,' + str(f['up3_nsp']))
        self.assertEqual((p['missing'], p['update']), ('null', 'up3.nsp 196608'))

    def test_no_update_no_dlc(self):
        d, f = self.files('plain', game_nup=1, base_nsp=1)
        p = self.plan(f['game_nup'], 500 * MB, None, [d], 'b,0100152000022000,0,' + str(f['base_nsp']))
        self.assertEqual((p['missing'], p['update'], p['dlc']), ('null', 'null', ''))
        self.assertIn('Found: the base game (base.nsp), no update, no DLC.', p['confirm'])

    def test_missing_dumps_are_named_with_where_it_looked(self):
        d, f = self.files('gone', game_nup=1, up3_nsp=1)
        other = self.root / 'gone-dlc'
        p = self.plan(f['game_nup'], 500 * MB, None, [d, other], 'u,0100152000022800,262144,-', 'd,0100152000023001,0,-',
                      'b,0100152000022000,0,' + str(d / 'deleted.nsp'), 'u,0100152000022800,196608,' + str(f['up3_nsp']))
        self.assertEqual(p['missing'], 'Import again makes the .nup from your game files, and not all of them were found. Missing: the base game\'s file (an .nsp, .nsz, .xci or .xcz '
                         'file); an update as new as the one the .nup has (v4; up3.nsp is v3); 1 of the DLC the .nup has (0100152000023001). Looked in '
                         f'{d}, {other}, two folders deep, and at the files added in the game\'s sheet. Put the files back, or set their folders in Settings › Library, '
                         'then tap Import again. Nothing was changed.')
        self.assertEqual(p['update'], 'null', 'an older update is not taken: the game would go back a version')

    def test_the_nup_s_update_must_be_found(self):
        d, f = self.files('noup', game_nup=1, base_nsp=1)
        p = self.plan(f['game_nup'], 500 * MB, None, [], 'u,0100152000022800,262144,-', 'b,0100152000022000,0,' + str(f['base_nsp']))
        self.assertIn('Missing: the update the .nup has (v4). Looked in no folder (none is set in Settings › Library), two folders deep', p['missing'])

    def test_a_file_named_for_its_title_only_counts_as_found(self):   # no importer probe: the version is 0, the file is taken
        d, f = self.files('unknown', game_nup=1, base_nsp=1, up_nsp=1)
        p = self.plan(f['game_nup'], 500 * MB, None, [d], 'u,0100152000022800,262144,-', 'b,0100152000022000,0,' + str(f['base_nsp']), 'u,0100152000022800,0,' + str(f['up_nsp']))
        self.assertEqual((p['missing'], p['update']), ('null', 'up.nsp 0'))

    def test_low_space_and_the_second_choice(self):
        d, f = self.files('low', game_nup=40, base_nsp=30, dlc1_nsp=20)
        parts = ['b,0100152000022000,0,' + str(f['base_nsp']), 'd,0100152000023001,0,' + str(f['dlc1_nsp'])]
        p = self.plan(f['game_nup'], 20 * MB, None, [d], *parts)   # 50 needed, 20 free, 40 more without the old file
        self.assertEqual(p['old'], f'{40 * MB} need={50 * MB} low=true fits_without_old=true')
        self.assertEqual(p['low_space'], 'Import again keeps the old .nup until the new one is complete, so both are on the storage for a while. The new one needs about '
                         f'50 MB free in {d}, and 20 MB is free now. Free up 30 MB and tap Import again. Nothing was changed.||Or delete the old .nup first (40 MB): '
                         'then there is room. The risk: if the import fails or is stopped, the game has no .nup and cannot be played until a build from its sheet has '
                         'made one.')
        self.assertEqual(p['delete_first'], f'{f["game_nup"]} (40 MB) is deleted before the new one is made. If the import fails or is stopped, Kart Game cannot be '
                         'played until a build from its sheet has made the .nup again. Your saves, mods and settings are kept.')
        p = self.plan(f['game_nup'], 5 * MB, None, [d], *parts)   # 45 with the old file gone: still short, so no second choice
        self.assertEqual(p['old'], f'{40 * MB} need={50 * MB} low=true fits_without_old=false')
        self.assertNotIn('delete the old', p['low_space'])
        self.assertIn('Free up 45 MB and tap Import again.', p['low_space'])
        p = self.plan(f['game_nup'], 50 * MB, None, [d], *parts)   # exactly enough
        self.assertIn('low=false', p['old'])

    def test_sizes(self):
        self.assertEqual(self.java('size', 0), '0 MB')
        self.assertEqual(self.java('size', 1), '1 MB')   # never "0 MB" for something that is there
        self.assertEqual(self.java('size', 310 * MB), '310 MB')
        self.assertEqual(self.java('size', int(6.2 * (1 << 30))), '6.2 GB')


class Wiring(unittest.TestCase):
    def test_prepare_asks_before_it_relaxes(self):
        src = (BUILD / 'Prepare.java').read_text(encoding='utf-8')
        run = src[src.index('private File run0('):src.index('File runModded(')]
        asks, auto = run.index('if (AutoRelax.asks(fix, cause, o.optBoolean("try_anyway"))) {'), run.index('if (AutoRelax.auto(fix, cause, o.optBoolean("try_anyway"), relaxed)) {')
        self.assertLess(asks, auto, 'the job that asks is stopped before the automatic Try anyway')
        self.assertIn('BuildListener.ADDON_MISSING);', run[asks:auto])
        self.assertNotIn('"anyway".equals(o.optString("fix"))', src, 'no second rule beside AutoRelax')
        self.assertIn('String fix = o.optString("fix"), cause = o.optString("cause");', run)
        # the card's Try anyway comes back as consent, and relaxes only a failed job whose failure offers it
        self.assertIn('if (consent && job == existing && Upstream.jobState(job).equals("failed") && AutoRelax.FIX_ANYWAY.equals(Upstream.jobFix(job)) && !Upstream.jobTryAnyway(job)) {', run)

    def test_constants_are_upstream_s(self):
        text = (JAVA / 'main/java/dev/nxnative/android/PrepareText.java').read_text(encoding='utf-8')
        relax = (BUILD / 'AutoRelax.java').read_text(encoding='utf-8')
        self.assertIn('static final String CAUSE_ADDON = "addon_not_imported";', text)
        self.assertIn('public static final String CAUSE_ADDON = "addon_not_imported";', relax)
        self.assertIn('FIX_ANYWAY = "anyway"', text)
        self.assertIn('public static final String FIX_ANYWAY = "anyway";', relax)

    def test_a_reimported_pack_is_not_already_prepared(self):
        src = (BUILD / 'Prepare.java').read_text(encoding='utf-8')
        run = src[src.index('private File run0('):src.index('File runModded(')]
        read, drop, already = run.index('reimported = PackNote.stale(Upstream.jobPack(existing));'), run.index('if (reimported) existing = null;'), run.index('p.on(100, "Already built");')
        self.assertLess(read, drop)
        self.assertLess(drop, already, 'the old job is left before it can answer Already prepared')
        self.assertIn('boolean stale = reimported || PackNote.stale(packDir);', run)
        self.assertIn('sh = shaders != null || stale', run, 'the shaders are made again, so the pack is never taken as it is')
        self.assertLess(run.index('sh = shaders != null || stale'), run.index('p.on(100, "The game\'s build is up to date, nothing to do");'))
        self.assertIn('if (reimported) { anyBuild = false;', run, 'the old job\'s Try anyway is not carried to the new file')
        self.assertIn('if (!consent && !had) {', run, 'a version the player already agreed to build is not asked about again')
        self.assertIn('note(Upstream.jobPack(j), o);', run, 'a finished build clears the mark and records what it lacks')
        self.assertIn('job.optBoolean("try_anyway") ? job.optJSONArray("addon_missing") : null', src)

    def test_the_old_file_is_replaced_only_by_a_complete_one(self):
        src = (BUILD / 'BuildService.java').read_text(encoding='utf-8')
        imp = src[src.index('private File stepImport('):src.index('private static boolean place(')]
        self.assertEqual(imp.count('out.delete()'), 1, 'one place deletes the old .nup')
        first = imp.index('if (oldThere && r.deleteOldFirst) {')
        self.assertLess(first, imp.index('out.delete()'))
        self.assertLess(imp.index('out.delete()'), imp.index('deleted = true;'), 'and only inside the player\'s second choice')
        write, mark, place = imp.index('Importer.writeNup(tmp, inputs'), imp.index('if (out.isFile()) markPacksStale(r, out);'), imp.index('if (!place(tmp, out))')
        self.assertLess(write, imp.index('if (rc != 0) { tmp.delete();'))
        self.assertLess(imp.index('if (rc != 0) { tmp.delete();'), mark, 'a failed import marks nothing and leaves the old file')
        self.assertLess(mark, place, 'the pack is marked before the new file takes the old one\'s place')
        self.assertIn('if (!again && out.isFile() && out.length() > 0 && out.lastModified() >= newest) {', imp, 'Import again never answers Imported already')
        self.assertIn('if (oldThere && !r.deleteOldFirst && SpaceEstimate.freeBytes(dir) < importNeed)', imp, 'both files must fit')
        self.assertIn('StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE', src)
        self.assertIn('PackNote.markStale(p)', src)

    def test_sheet_and_card(self):
        sheet = (JAVA / 'main/java/dev/peanuts/ui/GameSheet.java').read_text(encoding='utf-8')
        self.assertNotIn('makes the .nup and the pack again', sheet, 'Rebuild keeps the .nup')
        self.assertIn('"Import again…"', sheet)
        self.assertIn('PUi.confirmDanger(a, "Import again?", Reimport.confirm(p, t.name), "Import again"', sheet)
        ask = sheet[sheet.index('static void importAgain('):sheet.index('static String reimport(')]
        self.assertLess(ask.index('if (p.missing != null)'), ask.index('if (p.lowSpace())'))
        self.assertLess(ask.index('if (p.lowSpace())'), ask.index('"Import again?"'))
        self.assertIn('if (p.fitsWithoutOld()) b.setNeutralButton("Delete the old file first…", (d, w) -> PUi.confirmDanger(a, "Delete the old game file first?", Reimport.deleteFirst(p, t.name),', ask,
                      'deleting first is offered only when it makes room, and asks again with its risk')
        self.assertIn('PackNote.without(title.packDir)', sheet)
        lib = (JAVA / 'main/java/dev/peanuts/ui/Library.java').read_text(encoding='utf-8')
        card = lib[lib.index('case BuildListener.ADDON_MISSING: {'):lib.index('case BuildListener.FILE_MISSING:')]
        self.assertIn('"Set the DLC folder"', card)
        self.assertIn('LibrarySettings.extraFolders(a)', card)
        self.assertIn('"Import again with DLC"', card)
        self.assertIn('PUi.confirmDanger(a, "Try anyway?"', card, 'Try anyway stays a choice, asked once')
        self.assertIn('r.replaceNup = s.replaceNup; r.deleteOldFirst = s.deleteOldFirst;', lib)
        main = (JAVA / 'main/java/dev/nxnative/android/MainActivity.java').read_text(encoding='utf-8')
        self.assertNotIn('Remakes the .nup', main)

    def test_control_command_is_documented(self):
        doc = (REPO / 'peanuts/docs/CONTROL.md').read_text(encoding='utf-8')
        req = (JAVA / 'main/java/dev/peanuts/ctl/Request.java').read_text(encoding='utf-8')
        self.assertIn('"reimport"', req)
        self.assertIn('| `reimport` | `-e cmd reimport -e title_id 0100F2C0115B6000 -e confirm true` |', doc)
        port = (JAVA / 'main/java/dev/peanuts/ui/CtlPort.java').read_text(encoding='utf-8')
        for code in ('dumps_missing', 'low_space', 'needs_confirm', 'not_a_nup'):
            self.assertIn(f'"{code}"', port)
            self.assertIn(f'`{code}`', doc, code)
        for field in ('nup', 'old_bytes', 'base', 'update', 'update_version', 'dlc', 'need_bytes', 'free_bytes', 'delete_first'):
            self.assertIn(f'data.put("{field}"', port)
            self.assertIn(f'`{field}`', doc, field)
        faq = (REPO / 'peanuts/docs/FAQ.md').read_text(encoding='utf-8')
        self.assertNotIn('Makes the `.nup` and the pack again', faq)
        self.assertNotIn('Delete the game\'s `.nup` file with a file manager', faq)
        self.assertIn('**Import again**', faq)


if __name__ == '__main__':
    unittest.main()
