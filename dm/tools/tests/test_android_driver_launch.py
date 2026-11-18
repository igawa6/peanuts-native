"""PEANUTS: the launch warning follows the effective driver, including per-game overrides."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
class DriverLaunchTest(unittest.TestCase):
    def test_effective_driver(self):
        source = '''package dev.nxnative.android;
import java.util.List;
public class DriverLaunchTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(DriverLaunch.usesSystem(List.of()));
        check(DriverLaunch.usesSystem(List.of("DM_VK_DRIVER=system")));
        check(!DriverLaunch.usesSystem(List.of("DM_VK_DRIVER=/drivers/turnip")));
        check(DriverLaunch.usesSystem(List.of("DM_VK_DRIVER=/drivers/turnip", "DM_VK_DRIVER=system")));
        check(!DriverLaunch.usesSystem(List.of("DM_VK_DRIVER=system", "DM_VK_DRIVER=/drivers/turnip")));
        check(DriverLaunch.usesSystem(List.of("DM_VK_DRIVER=/drivers/turnip", "DM_VK_DRIVER=")));
        check(!DriverLaunch.usesSystem(List.of("DM_VK_DRIVER=/drivers/turnip", "DM_OTHER=system")));
    }
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            test = pathlib.Path(tmp) / 'DriverLaunchTest.java'
            test.write_text(source)
            subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', tmp,
                            str(ROOT / 'android/app/src/main/java/dev/nxnative/android/DriverLaunch.java'), str(test)], check=True)
            subprocess.run(['java', '-cp', tmp, 'dev.nxnative.android.DriverLaunchTest'], check=True)
