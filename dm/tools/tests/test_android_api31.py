"""The launcher runs on Android 12 (API 31/32): no Java call that exists only from API 33 without a version check (PEANUTS: Android 12 support; after
upstream v0.3.41's test of the same name, which checks its -PnxMinSdk=31 build. Here minSdk 31 is the default build's).

- A source scan that needs no SDK, for the calls that crash there (NoSuchMethodError) or need a guard. A line with a `SDK_INT` check on it or within the three
  lines before passes; Streams.java is where the stream helpers live (from API 33 they make the platform call itself).
- Streams' Android 12 paths on the JVM (javac), against the JDK's readAllBytes / readNBytes.
- The lint gate: `./gradlew lintDebug` (minSdk 31) with 0 NewApi (an error by default) and 0 InlinedApi in build/reports/lint-results-debug.xml. It needs the
  Android SDK (ANDROID_HOME or ANDROID_SDK_ROOT, else skipped); PN_SKIP_LINT=1 skips it. It writes android/app/build (delete it afterwards if you like).
- The prebuilt pack stage (PN_STAGE, else skipped): no shared library imports a symbol the NDK's API 31 libraries lack.
"""
import glob
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
APP = REPO / 'android/app'
SRC = APP / 'src'
# (pattern, what to use instead)
BAD = [
    (re.compile(r'(?<!Files)\.(readAllBytes|readNBytes)\s*\('), 'Streams.readAll / readUpTo / readInto (InputStream.readAllBytes is API 33 on Android)'),
    (re.compile(r'getParcelable(Extra)?\([^;]*\.class\)|getSerializable(Extra)?\([^;]*\.class\)|getParcelableArrayListExtra\([^;]*\.class\)'), 'the untyped call below API 33'),
    (re.compile(r'RECEIVER_NOT_EXPORTED|RECEIVER_EXPORTED'), 'registerReceiver without the flag below API 33'),
    (re.compile(r'\bLocaleManager\b'), 'a guard: no per-app language below API 33'),
    (re.compile(r'\bVibrationAttributes\b'), 'a guard: API 33'),
    (re.compile(r'POST_NOTIFICATIONS'), 'a guard: the permission exists from API 33'),
    (re.compile(r'PackageInfoFlags|ResolveInfoFlags|ApplicationInfoFlags'), 'the int-flag overload below API 33'),
    (re.compile(r'getOnBackInvokedDispatcher|OnBackInvokedCallback'), 'a guard: API 33'),
]


def sdk_dir():
    d = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    return d if d and os.path.isdir(d) else None


def ndk_bin():
    d = sdk_dir()
    found = sorted(glob.glob(os.path.join(d, 'ndk/*/toolchains/llvm/prebuilt/*/bin'))) if d else []
    return found[-1] if found else None


class Api31(unittest.TestCase):
    def test_no_unguarded_api33_calls(self):
        bad = []
        for f in sorted(SRC.glob('*/java/**/*.java')):
            if 'jvmtest' in f.parts or f.name == 'Streams.java':
                continue
            lines = f.read_text(encoding='utf-8').splitlines()
            for n, line in enumerate(lines, 1):
                code = line.split('//')[0]
                if code.lstrip().startswith(('*', '/*', 'import ')) or any('SDK_INT' in l for l in lines[max(0, n - 4):n]):
                    continue   # a comment, an import, or a version check on this line or the three before
                for rx, use in BAD:
                    if rx.search(code):
                        bad.append(f'{f.relative_to(SRC)}:{n}: use {use}')
        self.assertEqual(bad, [], '\n'.join(bad))

    def test_min_sdk_31_target_unchanged(self):
        s = (APP / 'build.gradle').read_text(encoding='utf-8')
        self.assertIn("providers.gradleProperty('nxMinSdk').getOrElse('31')", s)
        self.assertIn('minSdk nxMinSdk', s)
        self.assertIn('targetSdk 35', s)

    def test_internal_receivers(self):
        """Below API 33 no receiver takes broadcasts from other apps: every registerReceiver with a receiver goes through dev.peanuts.InternalBroadcast,
        whose Android 12 call is the 4-argument one with the app's signature-level permission (never the plain 2-argument call); from API 33 the flag as before"""
        ib = (SRC / 'main/java/dev/peanuts/InternalBroadcast.java').read_text(encoding='utf-8')
        self.assertIn('if (Build.VERSION.SDK_INT >= 33) return c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);', ib)
        self.assertIn('return c.registerReceiver(r, f, permission(c), null);', ib)
        self.assertIn('public static final String SUFFIX = ".permission.INTERNAL_BROADCAST";', ib)
        bad = []
        for f in sorted(SRC.glob('*/java/**/*.java')):
            if 'jvmtest' in f.parts or f.name == 'InternalBroadcast.java':
                continue
            for n, line in enumerate(f.read_text(encoding='utf-8').splitlines(), 1):
                code = line.split('//')[0]
                for m in re.finditer(r'(?<!un)registerReceiver\(\s*([^,\s)]+)', code):
                    if m.group(1) == 'null':   # a sticky read with no receiver
                        continue
                    bad.append(f'{f.relative_to(SRC)}:{n}: register through dev.peanuts.InternalBroadcast.register')
        self.assertEqual(bad, [], '\n'.join(bad))
        for rel, count in (('main/java/dev/nxnative/android/Updater.java', 1), ('ondevice/java/dev/nxnative/android/PackService.java', 1),
                           ('main/java/dev/peanuts/build/BuildService.java', 1), ('main/java/dev/peanuts/ctl/GameLink.java', 1),
                           ('main/java/dev/peanuts/ui/BottomScreen.java', 2)):
            self.assertEqual((SRC / rel).read_text(encoding='utf-8').count('dev.peanuts.InternalBroadcast.register('), count, rel)

    def test_internal_permission_signature(self):
        """The permission is declared signature-level and requested, named after the application ID (the dev build and the release each their own)"""
        root = ET.parse(SRC / 'main/AndroidManifest.xml').getroot()
        A = '{http://schemas.android.com/apk/res/android}'
        name = '${applicationId}.permission.INTERNAL_BROADCAST'
        decl = [p for p in root.findall('permission') if p.get(A + 'name') == name]
        self.assertEqual(len(decl), 1)
        self.assertEqual(decl[0].get(A + 'protectionLevel'), 'signature')
        self.assertTrue(any(u.get(A + 'name') == name for u in root.findall('uses-permission')))

    def test_internal_senders(self):
        """The in-app senders of those broadcasts name this package (sent as this app, they hold the permission); the installer's PendingIntent too"""
        for rel, needle in (('main/java/dev/peanuts/Companion.java', 'new android.content.Intent(STATE).setPackage(c.getPackageName())'),
                            ('main/java/dev/peanuts/ui/AppScreens.java', 'new Intent(STATE).setPackage(a.getPackageName())'),
                            ('main/java/dev/peanuts/ctl/Ctl.java', 'new Intent(GameLive.ACTION).setPackage(app.getPackageName())'),
                            ('main/java/dev/nxnative/android/Updater.java', 'new Intent(app.getPackageName() + ACTION).setPackage(app.getPackageName())')):
            self.assertIn(needle, (SRC / rel).read_text(encoding='utf-8'), rel)

    def test_streams_guard(self):
        """Each helper makes the platform call from API 33 (no other path runs there)"""
        s = (SRC / 'main/java/dev/nxnative/android/Streams.java').read_text(encoding='utf-8')
        for call in ('if (SDK >= 33) return in.readAllBytes();', 'if (SDK >= 33) return in.readNBytes(max);', 'if (SDK >= 33) return in.readNBytes(b, off, len);'):
            self.assertIn(call, s)
        self.assertIn('Class.forName("android.os.Build$VERSION").getField("SDK_INT")', s)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'no JDK')
    def test_streams_android12_paths(self):
        test = r'''package dev.nxnative.android;
import java.io.*;
import java.util.Arrays;
public final class StreamsApi31JvmTest {
    /** Hands out at most 3 bytes a read, as a network stream may */
    static final class Slow extends ByteArrayInputStream {
        Slow(byte[] b) { super(b); }
        @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 3)); }
    }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] a) throws IOException {
        byte[] data = new byte[20000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        check(Streams.SDK == Integer.MAX_VALUE, "off Android the JDK's calls are used");
        check(Arrays.equals(Streams.loop(new Slow(data), Integer.MAX_VALUE), new ByteArrayInputStream(data).readAllBytes()), "readAll");
        check(Arrays.equals(Streams.loop(new Slow(data), 9001), new ByteArrayInputStream(data).readNBytes(9001)), "readUpTo");
        check(Streams.loop(new Slow(data), 0).length == 0 && Streams.loop(new Slow(new byte[0]), 5).length == 0, "nothing to read");
        check(Arrays.equals(Streams.loop(new Slow(data), 99999), data), "readUpTo past the end");
        byte[] x = new byte[10], y = new byte[10];
        int n = Streams.loopInto(new Slow(data), x, 2, 7), m = new ByteArrayInputStream(data).readNBytes(y, 2, 7);
        check(n == m && Arrays.equals(x, y), "readInto");
        check(Streams.loopInto(new Slow(new byte[] {1, 2}), x, 0, 4) == 2, "readInto at the end");
        try { Streams.loopInto(new Slow(data), x, 8, 4); check(false, "bounds"); } catch (IndexOutOfBoundsException e) { }
        check(Arrays.equals(Streams.readAll(new Slow(data)), data), "readAll on the JDK");
        System.out.println("OK");
    }
}
'''
        with tempfile.TemporaryDirectory() as d:
            t = Path(d) / 'StreamsApi31JvmTest.java'
            t.write_text(test, encoding='utf-8')
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-d', str(Path(d) / 'classes'), str(SRC / 'main/java/dev/nxnative/android/Streams.java'), str(t)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(Path(d) / 'classes'), 'dev.nxnative.android.StreamsApi31JvmTest'], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn('OK', r.stdout)

    @unittest.skipIf(os.environ.get('PN_SKIP_LINT') == '1', 'PN_SKIP_LINT=1')
    @unittest.skipUnless(sdk_dir() and shutil.which('java'), 'no Android SDK (ANDROID_HOME) or JDK')
    def test_lint_new_api(self):
        """Lint's NewApi / InlinedApi at the build's minSdk (31): it knows the SDK_INT guards, so 0 means every API 32+ use has one"""
        report = APP / 'build/reports/lint-results-debug.xml'
        if report.exists():
            report.unlink()
        r = subprocess.run(['./gradlew', 'lintDebug', '-PnxApp=peanuts', '-PnxGenericHost=true', '-PnxPackEngine=fake', '--no-daemon', '-q'],
                           cwd=APP, capture_output=True, text=True, timeout=1800)
        self.assertEqual(r.returncode, 0, r.stdout[-3000:] + r.stderr[-3000:])
        self.assertTrue(report.exists(), 'lint wrote no report')
        found = []
        for i in ET.parse(report).getroot().iter('issue'):
            if i.get('id') in ('NewApi', 'InlinedApi'):
                loc = i.find('location')
                found.append(f"{i.get('id')} {loc.get('file') if loc is not None else '?'}:{loc.get('line') if loc is not None else '?'}: {i.get('message')}")
        self.assertEqual(found, [], '\n'.join(found))

    @unittest.skipUnless(os.environ.get('PN_STAGE') and ndk_bin(), 'PN_STAGE (the on-device packer stage) or the NDK not set')
    def test_stage_libs_api31(self):
        """The prebuilt stage's shared libraries (android/ondevice/build.sh, built for android33) import nothing the API 31 libraries lack"""
        bindir = ndk_bin()
        nm = os.path.join(bindir, 'llvm-nm')
        sysroot = glob.glob(os.path.join(bindir, '../sysroot/usr/lib/aarch64-linux-android/31'))[0]

        def syms(path, defined):
            out = subprocess.run([nm, '-D', '--defined-only' if defined else '--undefined-only', path], capture_output=True, text=True).stdout
            got = set()
            for line in out.splitlines():
                p = line.split()
                if not p or (not defined and p[0] == 'w'):   # a weak reference may stay unresolved
                    continue
                got.add(p[-1].split('@')[0])
            return got
        def elf(f):
            with open(f, 'rb') as h:
                return h.read(4) == b'\x7fELF'
        files = [f for f in glob.glob(os.path.join(os.environ['PN_STAGE'], '**/*'), recursive=True) if os.path.isfile(f) and elf(f)]
        have = set()
        for so in glob.glob(sysroot + '/*.so') + files:
            have |= syms(so, True)
        missing_libs = {'libcrypto_python.so', 'libssl_python.so', 'libsqlite3_python.so'}   # not in the stage on any Android: those modules never load
        bad = []
        for f in files:
            needed = subprocess.run([os.path.join(bindir, 'llvm-readelf'), '-d', f], capture_output=True, text=True).stdout
            if any(m in needed for m in missing_libs):
                continue
            left = sorted(syms(f, False) - have)
            if left:
                bad.append(f'{f}: {left[:10]}')
        self.assertEqual(bad, [], '\n'.join(bad))


if __name__ == '__main__':
    unittest.main()
