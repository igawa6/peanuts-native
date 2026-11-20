"""Runtime shader detection reads the APK tools archive without loading PackNative/CPython."""
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SRC = REPO / 'android/app/src/main/java/dev/peanuts/build/RuntimeShaderMap.java'


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class RuntimeShaderMap(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix='runtime_shader_map_')
        cls.root = Path(cls.tmp.name)
        runner = cls.root / 'RuntimeShaderMapTest.java'
        runner.write_text('''package dev.peanuts.build;
import java.io.*;
public final class RuntimeShaderMapTest {
    public static void main(String[] args) throws Exception {
        try {
            if (args.length > 2 && args[2].equals("capture-keys")) System.out.println("[" + RuntimeShaderMap.captureKeys(new FileInputStream(args[0]), args[1]) + "]");
            else if (args.length > 3 && args[3].equals("runtime-files")) System.out.println(RuntimeShaderMap.missingRuntime(new File(args[0]), new File(args[2])));
            else if (args.length > 4) System.out.println(RuntimeShaderMap.matchesBuild(new FileInputStream(args[0]), args[1], java.util.Arrays.asList(args[4].split(","))));
            else if (args.length > 3) System.out.println(RuntimeShaderMap.missingStatic(new FileInputStream(args[0]), args[1], new File(args[2])));
            else if (args.length > 2) System.out.println(RuntimeShaderMap.missing(new FileInputStream(args[0]), args[1], new File(args[2])));
            else System.out.println(RuntimeShaderMap.compilesAtRunTime(new FileInputStream(args[0]), args[1]));
        } catch (IOException e) {
            System.out.println("error: " + e.getMessage());
        }
    }
}
''')
        subprocess.run(['javac', '-d', str(cls.root), str(SRC), str(runner)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def detect(self, entries, title, library=None, static=False, builds=None):
        archive = self.root / 'tools.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, text in entries.items():
                z.writestr(name, text)
        args = ['java', '-cp', str(self.root), 'dev.peanuts.build.RuntimeShaderMapTest', str(archive), title]
        if library is not None:
            args.append(str(library))
        if static:
            args.append("static")
        if builds is not None:
            args.append(builds)
        r = subprocess.run(args,
                           check=True, capture_output=True, text=True)
        return r.stdout.strip()

    def capture_keys(self, entries, title):
        archive = self.root / 'tools-keys.zip'
        with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, text in entries.items():
                z.writestr(name, text)
        r = subprocess.run(['java', '-cp', str(self.root), 'dev.peanuts.build.RuntimeShaderMapTest', str(archive), title, 'capture-keys'],
                           check=True, capture_output=True, text=True)
        return r.stdout.strip()

    def test_capture_keys(self):
        t = '0100F9F00C696000'
        shipped = {f'dm/games/{t}/game.tsv': (REPO / f'dm/games/{t}/game.tsv').read_text()}
        self.assertEqual(self.capture_keys(shipped, t), '[8:A*45/3]')   # issue #111: the empty save's Language Select screen
        fe3h = '010055D009F78000'
        self.assertEqual(self.capture_keys({f'dm/games/{fe3h}/game.tsv': (REPO / f'dm/games/{fe3h}/game.tsv').read_text()}, fe3h), '[]')
        self.assertEqual(self.capture_keys({}, t), '[]')   # an APK without the profile: no input, as before
        self.assertEqual(self.capture_keys({f'dm/games/{t}/game.tsv': 'capture_keys\t8:A\nDM_AUDIO=1\n'}, t), '[8:A]')
        self.assertEqual(self.capture_keys({f'dm/games/{t}/game.tsv': 'capture_keys\t\n'}, t), '[]')   # an empty row: none
        for bad in ('8:A B', '8:A=1', '8:A;rm'):
            self.assertEqual(self.capture_keys({f'dm/games/{t}/game.tsv': f'capture_keys\t{bad}\n'}, t), 'error: Invalid capture_keys in dm/games/' + t + '/game.tsv', bad)

    def test_shipped_profiles(self):
        titles = {'010055D009F78000': 'true', '01008CF01BAAC000': 'false', '0100152000022000': 'false'}
        entries = {f'dm/games/{title}/shader_map.tsv': (REPO / f'dm/games/{title}/shader_map.tsv').read_text()
                   for title in titles}
        for title, expected in titles.items():
            with self.subTest(title=title):
                self.assertEqual(self.detect(entries, title), expected)

    def test_only_target_profile_and_source_column_count(self):
        entries = {'dm/games/other/shader_map.tsv': 'vs_runtime.bin\t0\tvertex\t1234\t/glslc_capture/one.glslc\n',
                   'dm/games/target/shader_map.tsv': '# /glslc_capture/ignored.glslc\nvs_runtime.bin\t0\tvertex\t1234\tmain\n'}
        self.assertEqual(self.detect(entries, 'target'), 'false')

    def test_missing_profile_is_an_error(self):
        self.assertTrue(self.detect({'README': 'no profiles'}, 'target').startswith('error: '))

    def library(self, title, keys):
        header = bytearray(128)
        struct.pack_into('<II', header, 0, 0x4B56534D, 1)
        header[40:56] = title.encode('ascii')
        struct.pack_into('<II', header, 72, len(keys), 128)
        rows = bytearray()
        for key, modules in keys:
            entry = bytearray(64)
            struct.pack_into('<Q', entry, 0, int(key, 16))
            entry[17] = modules
            rows += entry
        p = self.root / 'dmsh_vk.lib'
        p.write_bytes(header + rows)
        return p

    def test_adopted_three_houses_library_missing_runtime_shaders(self):
        title = '010055D009F78000'
        profile = (REPO / f'dm/games/{title}/shader_map.tsv').read_text()
        rows = [line.split('\t') for line in profile.splitlines() if not line.startswith('#')]
        runtime = {r[3] for r in rows if len(r) >= 5 and r[4].startswith('/glslc_capture/')}
        self.assertEqual(len(runtime), 9)
        static = {r[3] for r in rows if len(r) >= 5 and r[0].endswith('.bin') and r[3] not in runtime}
        entries = {f'dm/games/{title}/shader_map.tsv': profile}
        # No job.json is needed: the incomplete library itself is sufficient evidence.
        lib = self.library(title, [(key, 1) for key in sorted(static)])
        self.assertEqual(self.detect(entries, title, lib), '9')
        lib = self.library(title, [(key, 1) for key in sorted(static | runtime)])
        self.assertEqual(self.detect(entries, title, lib), '0')

    def test_entry_without_modules_is_still_missing(self):
        title = '0100000000000001'
        entries = {f'dm/games/{title}/shader_map.tsv': 'vs_runtime.bin\t0\tvertex\tffffffffffffffff\t/glslc_capture/a.glslc\n'}
        lib = self.library(title, [('ffffffffffffffff', 0)])
        self.assertEqual(self.detect(entries, title, lib), '1')
        lib = self.library(title, [('ffffffffffffffff', 1)])
        self.assertEqual(self.detect(entries, title, lib), '0')

    def test_invalid_library_does_not_report_complete(self):
        title = '0100000000000001'
        entries = {f'dm/games/{title}/shader_map.tsv': 'vs_runtime.bin\t0\tvertex\t1234\t/glslc_capture/a.glslc\n'}
        lib = self.library('0100000000000002', [('1234', 1)])
        self.assertTrue(self.detect(entries, title, lib).startswith('error: '))
        lib = self.library(title, [('1234', 1)])
        lib.write_bytes(lib.read_bytes()[:-1])
        self.assertTrue(self.detect(entries, title, lib).startswith('error: '))

    def test_profile_without_captures_does_not_need_library(self):
        title = '0100000000000001'
        entries = {f'dm/games/{title}/shader_map.tsv': 'vs.bin\t0\tvertex\t1234\tmain\n'}
        self.assertEqual(self.detect(entries, title, self.root / 'nonexistent.lib'), '0')


    def test_kirby_library_without_particle_group_needs_rebuild(self):
        title = '01006B601380E000'
        profile = (REPO / f'dm/games/{title}/shader_map.tsv').read_text()
        rows = [x.split('\t') for x in profile.splitlines() if x and not x.startswith('#')]
        all_keys = {r[3] for r in rows if len(r) >= 5 and r[0].endswith('.bin')}
        particle = {r[3] for r in rows if len(r) >= 5 and '/ptcl/' in r[4]}
        self.assertEqual(len(all_keys), 47099)
        self.assertEqual(len(particle), 9693)
        entries = {f'dm/games/{title}/shader_map.tsv': profile}
        lib = self.library(title, [(k, 1) for k in sorted(all_keys - particle)])
        self.assertEqual(self.detect(entries, title, lib, static=True), '9693')
        lib = self.library(title, [(k, 1) for k in sorted(all_keys)])
        self.assertEqual(self.detect(entries, title, lib, static=True), '0')

    def test_static_repair_leaves_runtime_keys_to_capture(self):
        title = '0100000000000001'
        entries = {f'dm/games/{title}/shader_map.tsv':
                   'vs_file.bin\t0\tvertex\t1\t/base/file\nvs_runtime.bin\t1\tvertex\t2\t/glslc_capture/boot.glslc\n'}
        lib = self.library(title, [('1', 1)])
        self.assertEqual(self.detect(entries, title, lib, static=True), '0')
        self.assertEqual(self.detect(entries, title, lib), '1')

    def test_static_repair_respects_optional_and_selected_profiles(self):
        title = '0100000000000001'
        entries = {f'dm/games/{title}/shader_map.tsv':
                   'vs_file.bin\t0\tvertex\t1\t/base/file\nvs_file.bin\t1\tvertex\t2\t/update/file\n',
                   f'dm/games/{title}/shader_optional.tsv': '# optional in a registered build\n2\tolder update\n'}
        lib = self.library(title, [('1', 1)])
        self.assertEqual(self.detect(entries, title, lib, static=True), '0')
        lib = self.library(title, [('1', 0)])
        self.assertEqual(self.detect(entries, title, lib, static=True), '1')
        entries[f'dm/games/{title}/shader_seen.tsv'] = '0000000000000001\n'
        self.assertEqual(self.detect(entries, title, lib, static=True), '0')

    def runtime_files(self, profile, found):
        want, have = self.root / 'want.tsv', self.root / 'have.tsv'
        want.write_text(profile)
        have.write_text(found)
        return subprocess.check_output(['java', '-cp', str(self.root), 'dev.peanuts.build.RuntimeShaderMapTest', str(want), 'unused', str(have), 'runtime-files'], text=True).strip()

    def test_first_build_accepts_only_runtime_generated_deficit(self):
        title = '010055D009F78000'
        profile = (REPO / f'dm/games/{title}/shader_map.tsv').read_text()
        static = '\n'.join(line for line in profile.splitlines() if '/glslc_capture/' not in line)
        result = self.runtime_files(profile, static)
        self.assertEqual(len(result.strip('[]').split(', ')), 9)
        self.assertNotEqual(result, 'null')
        self.assertEqual(self.runtime_files(profile, profile), 'null')
        incomplete = '\n'.join(line for line in static.splitlines() if not line.startswith('vs_data.bin\t0\t'))
        self.assertEqual(self.runtime_files(profile, incomplete), 'null')
        extra = static + '\nvs_other.bin\t0\tvertex\t123456789abcdef0\t/other/file\n'
        self.assertEqual(self.runtime_files(profile, extra), 'null')

    def test_static_repair_map_build_matches_module_ids(self):
        title = '01006B601380E000'
        profile = (REPO / f'dm/games/{title}/game.tsv').read_text()
        entries = {f'dm/games/{title}/game.tsv': profile}
        values = next(line.split('\t')[3:] for line in profile.splitlines() if line.startswith('build\t'))
        lib = self.root / 'unused.lib'
        self.assertEqual(self.detect(entries, title, lib, static=True, builds=','.join(values)), 'true')
        self.assertEqual(self.detect(entries, title, lib, static=True, builds=','.join(values[:-1])), 'false')
        changed = values.copy()
        changed[0] = 'main=' + '1' * 32
        self.assertEqual(self.detect(entries, title, lib, static=True, builds=','.join(changed)), 'false')
        self.assertEqual(self.detect({'README': 'no profile'}, title, lib, static=True, builds=','.join(values)), 'false')

    def test_static_repair_does_not_reject_a_generated_profile(self):
        title = '0100000000000001'
        lib = self.library(title, [('1', 1)])
        self.assertEqual(self.detect({'README': 'no shipped map'}, title, lib, static=True), '0')

if __name__ == '__main__':
    unittest.main()
