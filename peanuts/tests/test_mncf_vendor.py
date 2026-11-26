"""Mistel's MIT library for .mncf cheat files in the launcher (android/app/src/main/java/io/github/davdxpx/mistel): a copy of Mistel's format/lib/java that is
never edited here. MISTEL.sha256 names the Mistel commit and every file's SHA-256 (peanuts/scripts/mncf-vendor.sh writes it); a file changed, added or taken
away fails here, so a fix goes to Mistel first and comes back with the script. The MIT notice travels with the copy, and nothing of Mistel's GPL parts
(tools/, muninn/, ...) is in the repository."""
import hashlib
import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LIB = REPO / 'android/app/src/main/java/io/github/davdxpx/mistel'


class Vendored(unittest.TestCase):
    def listed(self):
        lines = (LIB / 'MISTEL.sha256').read_text(encoding='utf-8').splitlines()
        self.assertRegex(lines[0], r'^# mistel [0-9a-f]{40} ', 'the first line names the Mistel commit')
        out = {}
        for line in lines[1:]:
            m = re.fullmatch(r'([0-9a-f]{64})  (\S+)', line)
            self.assertIsNotNone(m, line)
            out[m.group(2)] = m.group(1)
        return out

    def test_the_copy_is_unchanged(self):
        listed = self.listed()
        here = {p.relative_to(LIB).as_posix() for p in LIB.rglob('*') if p.is_file() and p.name != 'MISTEL.sha256'}
        self.assertEqual(here, set(listed), 'files added to or taken from the copy: run peanuts/scripts/mncf-vendor.sh')
        for path, digest in listed.items():
            self.assertEqual(hashlib.sha256((LIB / path).read_bytes()).hexdigest(), digest, f'{path} differs from Mistel: fix it there, then copy it again')

    def test_the_mit_notice_travels_with_it(self):
        lic = (LIB / 'LICENSE').read_text(encoding='utf-8')
        self.assertTrue(lic.startswith('MIT License'))
        self.assertIn('Permission is hereby granted, free of charge', lic)
        notice = (REPO / 'android/NOTICE.md').read_text(encoding='utf-8')
        self.assertIn('android/app/src/main/java/io/github/davdxpx/mistel', notice, 'android/NOTICE.md names the copy and its license')

    def test_only_the_mit_library(self):
        self.assertEqual(sorted(p.name for p in LIB.iterdir()), ['LICENSE', 'MISTEL.sha256', 'mncf'])
        for p in (LIB / 'mncf').rglob('*.java'):
            self.assertRegex(p.read_text(encoding='utf-8'), r'(?m)^package io\.github\.davdxpx\.mistel\.mncf(\.toml)?;', p.name)

    def test_the_overlay_owns_it(self):
        script = (REPO / 'peanuts/scripts/upstream-update.sh').read_text(encoding='utf-8')
        self.assertIn('android/app/src/main/java/io/github/davdxpx/mistel/*', script, 'the hunk-marker check treats the copy as Peanuts-owned')
        self.assertIn('android/app/src/main/java/io/github/davdxpx/mistel/', (REPO / 'PEANUTS.md').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
