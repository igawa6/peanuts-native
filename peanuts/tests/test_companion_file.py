"""Install from file: a dual-screen companion from a .dsmod.zip on the device, without the catalog (peanuts/docs/FAQ.md). dev.peanuts.CompanionFile decides
what a file is (CompanionFileJvmTest on the JVM, with fixture zips it makes: good, another game's, damaged, needs a newer app, older than the installed one,
not a companion); the install itself is the catalog's installer (Installer.installFile into extractAndActivate), never a second one. The JVM part is
skipped without a JDK."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
PKG = APP / 'main/java/dev/peanuts'
FILE = PKG / 'CompanionFile.java'
TEST = APP / 'jvmtest/java/dev/peanuts/CompanionFileJvmTest.java'


def read(p): return p.read_text(encoding='utf-8')


def body(src, head):
    s = src[src.index(head):]
    return s[:s.index('\n    }\n')]


class SharedInstaller(unittest.TestCase):
    def test_install_file_uses_the_catalog_install_steps(self):
        inst = read(PKG / 'Installer.java')
        f = body(inst, 'public static FileInstall installFile(')
        for step in ('lockFor(pkg)', 'refuseIfGameRunning(ctx)', 'repairLocked(root, pkg)', 'CompanionFile.decide(', 'extractAndActivate(zip, info.titleId, pkg, info.version, "file"'):
            self.assertIn(step, f)
        self.assertLess(f.index('CompanionFile.decide('), f.index('extractAndActivate('))
        self.assertIn(', false, root, null, ctx, progress)', f, 'a file is installed as unverified')
        self.assertIn('item.tag, item.url, true, root, knownBuildId, ctx, progress)', body(inst, 'public static String install(Catalog.Item item, File root, File cacheDir, Progress progress, String knownBuildId, Context ctx)'))
        self.assertIn('put("verified", verified)', inst)

    def test_no_second_installer(self):
        for name in ('CompanionImport.java', 'CatalogActivity.java', 'ctl/CompanionCmd.java', 'settings/CompanionSection.java'):
            s = read(PKG / name)
            for word in ('ZipInputStream', 'ZipFile', 'renameTo(', 'ZipEntry'):
                self.assertNotIn(word, s, f'{name} has its own {word}')
        imp = read(PKG / 'CompanionImport.java')
        self.assertIn('CompanionFile.read(zip, fileName)', imp)
        self.assertIn('Installer.installFile(zip, info, t.root, wantTitle, wantName, allowOlder, c, progress)', imp)

    def test_the_page_the_section_and_the_command_go_through_it(self):
        cat = read(PKG / 'CatalogActivity.java')
        self.assertIn('CompanionImport.install(this, zip, name, forTitle, allowOlder,', cat)
        self.assertIn('Intent.ACTION_OPEN_DOCUMENT', cat)
        self.assertIn('"application/zip"', cat)
        self.assertIn('"application/octet-stream"', cat)
        self.assertIn('setType("*/*")', cat)
        self.assertIn('"Install from file"', cat)
        self.assertIn('CompanionImport.copy(this, uri)', body(cat, 'protected void onActivityResult('))
        on = body(cat, 'protected void onActivityResult(')
        self.assertLess(on.index('new Thread('), on.index('CompanionImport.copy('), 'the copy runs off the main thread')
        f = body(cat, 'private void installFile(')
        self.assertLess(f.index('new Thread('), f.index('CompanionImport.install('))
        self.assertIn('"Install an older version?"', f)
        self.assertIn('CompanionFile.Refused.OLDER.equals(e.code)', f)
        sec = read(PKG / 'settings/CompanionSection.java')
        self.assertIn('"Install from file"', sec)
        self.assertIn('dev.peanuts.CatalogActivity.EXTRA_TITLE, titleId', sec)
        cmd = read(PKG / 'ctl/CompanionCmd.java')
        self.assertIn('CompanionImport.install(c, f, f.getName(), id, confirm, null)', cmd)

    def test_one_rule_for_names_and_versions(self):
        cat = read(PKG / 'Catalog.java')
        self.assertIn('ASSET = CompanionFile.ASSET', cat)
        self.assertIn('return CompanionFile.compareVersions(a, b);', cat)
        self.assertNotIn('Integer.parseInt', cat)

    def test_runtime_is_the_installer_s(self):
        self.assertIn('RUNTIME_VERSION, allowOlder)', read(PKG / 'Installer.java'))

    def test_voice(self):
        for name in ('CompanionFile.java', 'CompanionImport.java', 'CatalogActivity.java', 'ctl/CompanionCmd.java', 'settings/CompanionSection.java'):
            self.assertNotIn('—', read(PKG / name), name)

    def test_faq(self):
        faq = read(REPO / 'peanuts/docs/FAQ.md')
        self.assertIn('How do I install a companion without the internet?', faq)
        self.assertIn('Install from file', faq)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class Jvm(unittest.TestCase):
    def test_jvm(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), str(FILE), str(TEST)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.CompanionFileJvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


if __name__ == '__main__':
    unittest.main()
