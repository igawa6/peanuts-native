"""Mistel's MIT library for .mncf cheat files (dm/third_party/mistel): the files of Mistel's format/lib/java the launcher needs, never edited here, shipped
like the other code in dm/third_party. MISTEL.sha256 names the Mistel commit and every file's SHA-256 (peanuts/scripts/mncf-vendor.sh writes it, with the
README and the commit in dm/third_party/README.md's row); a file changed, added or taken away fails here, so a fix goes to Mistel first and comes back with
the script. The MIT notice travels with the copy, and nothing of Mistel's GPL parts (tools/, muninn/, ...) is in the repository. With MISTEL set to a Mistel
checkout, every file is also compared with that commit in Mistel itself."""
import hashlib
import os
import re
import subprocess
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LIB = REPO / 'dm/third_party/mistel'
PKG = 'java/io/github/davdxpx/mistel/mncf/'
UPSTREAM = 'format/lib/java/src/main/java/io/github/davdxpx/mistel/mncf/'


class Vendored(unittest.TestCase):
    def commit(self):
        first = (LIB / 'MISTEL.sha256').read_text(encoding='utf-8').splitlines()[0]
        m = re.match(r'^# mistel ([0-9a-f]{40}) ', first)
        self.assertIsNotNone(m, 'the first line names the Mistel commit')
        return m.group(1)

    def listed(self):
        out = {}
        for line in (LIB / 'MISTEL.sha256').read_text(encoding='utf-8').splitlines()[1:]:
            m = re.fullmatch(r'([0-9a-f]{64})  (\S+)', line)
            self.assertIsNotNone(m, line)
            out[m.group(2)] = m.group(1)
        return out

    def test_the_copy_is_unchanged(self):
        listed = self.listed()
        here = {p.relative_to(LIB).as_posix() for p in LIB.rglob('*') if p.is_file() and p.name not in ('MISTEL.sha256', 'README.md')}
        self.assertEqual(here, set(listed), 'files added to or taken from the copy: run peanuts/scripts/mncf-vendor.sh')
        for path, digest in listed.items():
            self.assertEqual(hashlib.sha256((LIB / path).read_bytes()).hexdigest(), digest, f'{path} differs from Mistel: fix it there, then copy it again')

    def test_the_same_as_in_mistel(self):
        mistel = os.environ.get('MISTEL')
        if not mistel:
            self.skipTest('MISTEL is not set to a Mistel checkout')
        commit = self.commit()
        for path, digest in self.listed().items():
            up = 'format/LICENSE' if path == 'LICENSE' else UPSTREAM + path[len(PKG):]
            data = subprocess.run(['git', '-C', mistel, 'show', f'{commit}:{up}'], capture_output=True, check=True).stdout
            self.assertEqual(hashlib.sha256(data).hexdigest(), digest, f'{path} is not {up} of Mistel {commit[:7]}')

    def test_the_mit_notice_travels_with_it(self):
        lic = (LIB / 'LICENSE').read_text(encoding='utf-8')
        self.assertTrue(lic.startswith('MIT License'))
        self.assertIn('Permission is hereby granted, free of charge', lic)
        self.assertIn('LICENSE', self.listed())
        notice = (REPO / 'android/NOTICE.md').read_text(encoding='utf-8')
        self.assertIn('dm/third_party/mistel/', notice, 'android/NOTICE.md names the copy and its license')

    def test_only_the_mit_library(self):
        self.assertEqual(sorted(p.name for p in LIB.iterdir()), ['LICENSE', 'MISTEL.sha256', 'README.md', 'java'])
        java = sorted((LIB / 'java').rglob('*'))
        self.assertTrue(java)
        for p in java:
            if p.is_file():
                self.assertEqual(p.suffix, '.java', p)
                self.assertTrue(p.relative_to(LIB).as_posix().startswith(PKG), p)
                self.assertRegex(p.read_text(encoding='utf-8'), r'(?m)^package io\.github\.davdxpx\.mistel\.mncf(\.toml)?;', p.name)

    def test_the_readme_and_the_table_say_what_is_here(self):
        commit, listed = self.commit(), self.listed()
        readme = (LIB / 'README.md').read_text(encoding='utf-8')
        self.assertIn(commit, readme)
        self.assertIn('**unmodified**', readme)
        self.assertIn('PEANUTS', readme.splitlines()[2])
        for path in listed:
            if path != 'LICENSE':
                self.assertIn(f'`{path[len(PKG):-len(".java")]}`', readme, f'README.md names {path}')
        table = (REPO / 'dm/third_party/README.md').read_text(encoding='utf-8')
        row = [line for line in table.splitlines() if line.startswith('| `mistel/` |')]
        self.assertEqual(len(row), 1, 'one row for mistel/ in dm/third_party/README.md')
        self.assertIn('[PEANUTS]', row[0])
        self.assertIn(f'Mistel commit `{commit[:7]}`', row[0])
        self.assertIn('unmodified', row[0])
        self.assertIn('`mistel/LICENSE` (MIT)', row[0])

    def test_the_app_compiles_it(self):
        gradle = (REPO / 'android/app/build.gradle').read_text(encoding='utf-8')
        line = [s for s in gradle.splitlines() if "'../../dm/third_party/mistel/java'" in s]
        self.assertEqual(len(line), 1)
        self.assertIn('java.srcDirs +=', line[0])
        self.assertIn('PEANUTS', line[0])

    def test_the_overlay_owns_it(self):
        script = (REPO / 'peanuts/scripts/upstream-update.sh').read_text(encoding='utf-8')
        self.assertIn('dm/third_party/mistel/*', script, 'the hunk-marker check treats the copy as Peanuts-owned')
        self.assertIn('dm/third_party/mistel/', (REPO / 'PEANUTS.md').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
