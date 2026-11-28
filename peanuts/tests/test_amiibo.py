"""amiibo in the app (dev.peanuts.amiibo): the JVM part (android/app/src/jvmtest's AmiiboJvmTest, skipped without a JDK) reads the app's table, dumps made in
the test (made-up IDs), imports, the player's amiibo with the games' data and the host's description. Then the table itself: made by
peanuts/scripts/amiibo-db.py from AmiiboAPI (MIT) and noted as such, and the rule that no amiibo keys or dumps come near the repository."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
APP = REPO / 'android/app/src'
PKG = APP / 'main/java/dev/peanuts/amiibo'
TABLE = APP / 'peanuts/res/raw/amiibo_db.tsv'
CORE = [PKG / 'AmiiboDb.java', PKG / 'AmiiboBin.java', PKG / 'AmiiboImport.java', PKG / 'AmiiboStore.java', PKG / 'AmiiboTag.java']
TEST = APP / 'jvmtest/java/dev/peanuts/amiibo/AmiiboJvmTest.java'
PICTURES_TEST = APP / 'jvmtest/java/dev/peanuts/amiibo/AmiiboPicturesJvmTest.java'
# Stand-ins for the Android classes AmiiboPictures and AmiiboSwitch use, and for AmiiboStand (the rest of the app); only what they call
STUBS = {
    'android/content/Context.java': 'package android.content;\npublic class Context {\n  public static final int MODE_MULTI_PROCESS = 4;\n'
                                    '  public SharedPreferences getSharedPreferences(String n, int m) { return null; }\n'
                                    '  public java.io.File getCacheDir() { return null; }\n  public Context getApplicationContext() { return this; }\n}\n',
    'android/content/SharedPreferences.java': 'package android.content;\npublic interface SharedPreferences {\n  boolean getBoolean(String k, boolean d);\n'
                                              '  boolean contains(String k);\n  java.util.Map<String, ?> getAll();\n  Editor edit();\n'
                                              '  interface Editor { Editor putBoolean(String k, boolean v); void apply(); }\n}\n',
    'android/graphics/Bitmap.java': 'package android.graphics;\npublic class Bitmap {\n  public int getByteCount() { return 0; }\n}\n',
    'android/graphics/BitmapFactory.java': 'package android.graphics;\npublic class BitmapFactory {\n'
                                           '  public static class Options { public boolean inJustDecodeBounds; public int outHeight, outWidth, inSampleSize; }\n'
                                           '  public static Bitmap decodeFile(String p, Options o) { return null; }\n}\n',
    'android/os/Handler.java': 'package android.os;\npublic class Handler {\n  public Handler(Looper l) {}\n'
                               '  public boolean post(Runnable r) { r.run(); return true; }\n}\n',
    'android/os/Looper.java': 'package android.os;\npublic class Looper {\n  public static Looper getMainLooper() { return null; }\n}\n',
    'android/util/Log.java': 'package android.util;\npublic class Log {\n  public static int w(String t, String m, Throwable e) { return 0; }\n}\n',
    'android/util/LruCache.java': 'package android.util;\npublic class LruCache<K, V> {\n  public LruCache(int max) {}\n'
                                  '  protected int sizeOf(K k, V v) { return 1; }\n  public final V get(K k) { return null; }\n'
                                  '  public final V put(K k, V v) { return null; }\n}\n',
    'dev/peanuts/amiibo/AmiiboStand.java': 'package dev.peanuts.amiibo;\nfinal class AmiiboStand {\n  static final String TAG = "peanuts";\n'
                                           '  static boolean usesAmiibo(android.content.Context c, String t) { return false; }\n}\n',
}


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'needs a JDK')
class Jvm(unittest.TestCase):
    def test_store(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, CORE), str(TEST)],
                               capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)
            (d / 'work').mkdir()
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.amiibo.AmiiboJvmTest', str(TABLE), str(d / 'work')],
                               capture_output=True, text=True, encoding='utf-8')
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')

    def test_pictures_strictly_opt_in(self):
        """AmiiboPicturesJvmTest: no connection and no cache folder while the pictures or amiibo are off (every http and https connection is counted);
        with both on, one try at AmiiboAPI's image on GitHub"""
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            for rel, text in STUBS.items():
                f = d / 'stub' / rel
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(text, encoding='utf-8')
            src = [PKG / 'AmiiboPictures.java', PKG / 'AmiiboSwitch.java', *CORE, PICTURES_TEST, *(d / 'stub').rglob('*.java')]
            r = subprocess.run(['javac', '-Xlint:all', '-Werror', '-encoding', 'UTF-8', '-d', str(d / 'classes'), *map(str, src)], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            r = subprocess.run(['java', '-cp', str(d / 'classes'), 'dev.peanuts.amiibo.AmiiboPicturesJvmTest'], capture_output=True, text=True,
                               encoding='utf-8', timeout=120)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertEqual(r.stdout.strip(), 'ok')


class Table(unittest.TestCase):
    def test_source_and_shape(self):
        lines = TABLE.read_text(encoding='utf-8').splitlines()
        self.assertTrue(lines[0].startswith('# The amiibo of AmiiboAPI (https://github.com/N3evin/AmiiboAPI, MIT, Copyright (c) 2017 Nevin Vu), commit '))
        kinds = {l.split('\t')[0] for l in lines if not l.startswith('#')}
        self.assertEqual(kinds, {'A', 'S', 'G', 'T', 'N', 'W', 'U'})
        for l in lines:
            if l.startswith('A\t'):
                self.assertRegex(l, r'^A\t[0-9a-f]{14}0[23]\t\S')
            if l.startswith('U\t'):
                self.assertRegex(l, r'^U\t[0-9A-F]{16}\t\d+\t[01]\t[0-9a-f]{16}(,[0-9a-f]{16})*$')

    def test_made_by_the_script(self):
        script = (REPO / 'peanuts/scripts/amiibo-db.py').read_text(encoding='utf-8')
        self.assertIn('N3evin/AmiiboAPI', script)
        self.assertIn('no amiibo data of any kind', script)

    def test_no_keys_and_no_dumps(self):
        """amiibo keys (key_retail.bin and the like) and dumps are never part of the repository; the app never asks for them"""
        for p in list(PKG.glob('*.java')) + [REPO / 'dm/peanuts/peanuts_amiibo.c']:
            text = p.read_text(encoding='utf-8').lower()
            for word in ('key_retail', 'locked-secret', 'unfixed-info', 'retail.bin', 'prod.keys'):
                self.assertNotIn(word, text, f'{p.name} mentions {word}')
        tracked = subprocess.run(['git', 'ls-files'], cwd=REPO, capture_output=True, text=True).stdout.splitlines()
        bins = [f for f in tracked if f.endswith('.bin') and 'amiibo' in f.lower()]
        self.assertEqual(bins, [], 'no amiibo dump in the repository')

    def test_pictures_only_when_turned_on(self):
        """igawa6's review of #711: a new outside host is the player's choice. Only AmiiboPictures reaches out, and only with the pictures turned on
        (off by default); off, it answers at once with none (Peanuts' own card) and reads no cache"""
        for p in sorted(PKG.glob('*.java')) + sorted((APP / 'main/java/dev/peanuts/ui').glob('Amiibo*.java')):
            if p.name == 'AmiiboPictures.java':
                continue
            text = p.read_text(encoding='utf-8')
            for word in ('http://', 'https://', 'HttpURLConnection', 'java.net.URL', 'openConnection'):
                self.assertNotIn(word, text, f'{p.name} reaches out ({word})')
        pictures = (PKG / 'AmiiboPictures.java').read_text(encoding='utf-8')
        load = pictures[pictures.index('public static void load('):]
        self.assertTrue(load.split('\n')[1].strip().startswith('if (!AmiiboSwitch.picturesOn(c)) { done.accept(null); return; }'), 'the first thing load does')
        switch = (PKG / 'AmiiboSwitch.java').read_text(encoding='utf-8')
        self.assertIn('getBoolean(PICTURES, false)', switch, 'off by default')
        on = switch[switch.index('public static boolean picturesOn(Context c) {'):]
        on = on[:on.index('\n    }\n')]
        self.assertIn('if (!p.getBoolean(PICTURES, false)) return false;', on, 'the pictures turned on')
        self.assertIn('if (p.getBoolean(ALL, false)) return true;', on, 'amiibo on in Settings')
        self.assertIn('e.getKey().startsWith("t.") && Boolean.TRUE.equals(e.getValue())', on, 'or for a game')
        self.assertEqual(pictures.count('openConnection'), 1, 'one place reaches out')
        notice = (REPO / 'android/NOTICE.md').read_text(encoding='utf-8')
        line = next(l for l in notice.splitlines() if 'AmiiboAPI' in l)
        self.assertIn('once the player has turned the pictures on', line)

    def test_names_keep_the_ui_rules(self):
        db = PKG / 'AmiiboDb.java'
        self.assertRegex(db.read_text(encoding='utf-8'), r'name\.substring\(0, i\) \+ " \(" \+ name\.substring\(i \+ 3\) \+ "\)"')


if __name__ == '__main__':
    unittest.main()
