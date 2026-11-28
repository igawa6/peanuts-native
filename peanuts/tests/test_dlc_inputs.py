"""Reject unsupported DLC inputs before import (issue #923)."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
JAVA = REPO / 'android/app/src/main/java/dev/peanuts'

RUNNER = r'''
package dev.peanuts.build;

import java.io.File;

public final class DlcInputsTest {
    public static void main(String[] args) {
        BuildRequest r = new BuildRequest();
        for (String arg : args) r.dlcs.add(new File(arg));
        System.out.println(r.dlcInputError());
    }
}
'''


@unittest.skipUnless(
    shutil.which('javac') and shutil.which('java'), 'no JDK')
class Logic(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        runner = cls.root / 'DlcInputsTest.java'
        runner.write_text(RUNNER, encoding='utf-8')
        subprocess.run([
            'javac', '-Xlint:all', '-Werror',
            '-encoding', 'UTF-8',
            '-d', str(cls.root),
            str(JAVA / 'build/BuildRequest.java'),
            str(runner)
        ], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def check_inputs(self, *files):
        return subprocess.check_output([
            'java', '-cp', str(self.root),
            'dev.peanuts.build.DlcInputsTest',
            *map(str, files)
        ], text=True).strip()

    def file(self, name):
        p = self.root / name
        p.touch()
        return p

    def test_supported_formats(self):
        files = [
            self.file('DLC.' + ext)
            for ext in ('nsp', 'nsz', 'xci', 'xcz', 'NSP')
        ]
        self.assertEqual(self.check_inputs(*files), 'null')

    def test_no_dlc(self):
        self.assertEqual(self.check_inputs(), 'null')

    def test_issue_923_url(self):
        valid = self.file('DLC.nsp')
        invalid = self.file('Ziperto - A Digital Park for Gamers.URL')
        self.assertIn(
            'Invalid DLC file: ' + invalid.name,
            self.check_inputs(valid, invalid)
        )

    def test_unrelated_file(self):
        p = self.file('README.txt')
        self.assertIn('Invalid DLC file:', self.check_inputs(p))

    def test_missing_file(self):
        p = self.root / 'missing.nsp'
        self.assertIn('Invalid DLC file:', self.check_inputs(p))

    def test_directory(self):
        p = self.root / 'folder.nsp'
        p.mkdir()
        self.assertIn('Invalid DLC file:', self.check_inputs(p))


class Wiring(unittest.TestCase):

    def test_picker_validates_before_saving(self):
        s = (JAVA / 'ui/GameSheet.java').read_text()
        start = s.index('} else if (request == REQ_DLC)')
        end = s.index('saveDlc();', start)
        self.assertIn('BuildRequest.isDlcFile(f)', s[start:end])

    def test_builder_validates_before_import(self):
        s = (JAVA / 'build/BuildService.java').read_text()
        start = s.index('private File stepImport(')
        end = s.index('List<File> inputs = r.inputs();', start)
        self.assertIn('r.dlcInputError()', s[start:end])


if __name__ == '__main__':
    unittest.main()
