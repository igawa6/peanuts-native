"""The cheats' state and reload for the launcher (Mod menu package A): Native.cheatStatus / Native.cheatsReload reach dm/peanuts/peanuts_cheats.inc through
nxlaunch.c's weak forwarders. The C part compiles the production JNI block against a stand-in host (a status longer than the first buffer, a host without the calls);
the Java part runs Native.java's UTF-8 decoding of the status (a name outside the BMP). The engine itself is tested by dm/host/tests t_cheats.c (make peanuts-cheats)."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
NXLAUNCH = ROOT / 'android/app/src/main/cpp/nxlaunch.c'
NATIVE = ROOT / 'android/app/src/main/java/dev/nxnative/android/Native.java'
INC = ROOT / 'dm/peanuts/peanuts_cheats.inc'


def block(text, begin):
    a = text.index(begin)
    return text[a:text.index('// PEANUTS end', a)]


class Wiring(unittest.TestCase):
    def test_names(self):
        inc = INC.read_text(encoding='utf-8')
        self.assertIn('size_t nxhost_cheat_status(char *out, size_t cap)', inc)
        self.assertIn('int nxhost_cheats_reload(void)', inc)
        self.assertIn('#include "../peanuts/peanuts_cheats.inc"', (ROOT / 'dm/host/hle_cheats.c').read_text(encoding='utf-8'))
        native = NATIVE.read_text(encoding='utf-8')
        self.assertIn('private static native byte[] cheatStatusUtf8();', native)
        self.assertIn('static native int cheatsReload();', native)
        launch = NXLAUNCH.read_text(encoding='utf-8')
        self.assertIn('Java_dev_nxnative_android_Native_cheatStatusUtf8', launch)
        self.assertIn('Java_dev_nxnative_android_Native_cheatsReload', launch)


class Bridge(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_long_status_and_missing_host(self):
        bridge = block(NXLAUNCH.read_text(encoding='utf-8'), '// PEANUTS begin: every cheat\'s state for the in-game panel')
        harness = r'''
#include <assert.h>
#include <stdlib.h>
#include <string.h>
typedef void JNIEnv;
typedef void *jclass;
typedef int jint;
typedef char *jbyteArray;
#define JNIEXPORT
#define JNICALL
static jbyteArray jbytes(JNIEnv *env, const char *s) { (void)env; size_t n = strlen(s) + 1; char *c = malloc(n); if (c) memcpy(c, s, n); return c; }
''' + bridge + r'''
#ifdef AVAILABLE
static char want[40001];
size_t nxhost_cheat_status(char *out, size_t cap) {
    size_t n = strlen(want);
    if (cap) { size_t k = n < cap - 1 ? n : cap - 1; memcpy(out, want, k); out[k] = 0; }
    return n;
}
int nxhost_cheats_reload(void) { return 7; }
#endif
int main(void) {
#ifdef AVAILABLE
    memset(want, 'x', sizeof want - 1); memcpy(want, "Money\ton\tcode\t\n", 15);
    char *s = Java_dev_nxnative_android_Native_cheatStatusUtf8(NULL, NULL);
    assert(s && !strcmp(s, want));
    assert(Java_dev_nxnative_android_Native_cheatsReload(NULL, NULL) == 7);
#else
    char *s = Java_dev_nxnative_android_Native_cheatStatusUtf8(NULL, NULL);
    assert(s && !*s);
    assert(Java_dev_nxnative_android_Native_cheatsReload(NULL, NULL) == -2);
#endif
    free(s);
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='cheat-status-c-') as td:
            source = Path(td) / 'bridge.c'
            source.write_text(harness, encoding='utf-8')
            for available in (False, True):
                with self.subTest(available=available):
                    exe = Path(td) / ('available' if available else 'unavailable')
                    args = ['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', str(source), '-o', str(exe)]
                    if available:
                        args.insert(1, '-DAVAILABLE')
                    compiled = subprocess.run(args, capture_output=True, text=True)
                    self.assertEqual(compiled.returncode, 0, compiled.stderr)
                    subprocess.run([str(exe)], check=True)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_utf8_status(self):
        native = NATIVE.read_text(encoding='utf-8')
        line = next(l for l in native.splitlines() if 'static String cheatStatus()' in l)
        harness = '''package dev.nxnative.android;
public class StatusTest {
    static byte[] bytes;
    private static byte[] cheatStatusUtf8() { return bytes; }
''' + line + '''
    public static void main(String[] args) throws Exception {
        String row = "\\u2116 2. Max Money \\ud83d\\udcb0\\ton\\tcode\\t\\n[--SectionStart:Item--]\\tlabel\\t\\t\\n";
        bytes = row.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!row.equals(cheatStatus())) throw new AssertionError("decoded " + cheatStatus());
        bytes = null;
        if (cheatStatus() != null) throw new AssertionError("no host: null");
        bytes = new byte[0];
        if (cheatStatus() != null) throw new AssertionError("no cheats loaded: null");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='cheat-status-java-') as td:
            test = Path(td) / 'StatusTest.java'
            test.write_text(harness, encoding='utf-8')
            subprocess.run(['javac', '-encoding', 'UTF-8', '-d', td, str(test)], check=True)
            subprocess.run(['java', '-cp', td, 'dev.nxnative.android.StatusTest'], check=True)


if __name__ == '__main__':
    unittest.main()
