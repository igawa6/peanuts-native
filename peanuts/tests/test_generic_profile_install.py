"""Exercise the launch and pack-service profile cache across a same-version APK update."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class GenericProfileInstall(unittest.TestCase):
    def test_install_update_and_modded_pack(self):
        sources = {
            'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager {
    public static class NameNotFoundException extends Exception {}
    public long lastUpdateTime = 1700000000123L;
    public PackageManager getPackageInfo(String pkg, int flags) throws NameNotFoundException { return this; }
}''',
            'android/content/Context.java': '''package android.content;
import android.content.pm.PackageManager;
import java.io.*;
public class Context {
    public int opens;
    public byte[] profile = {1}, seed = {2}, pairs = {6}, programs = {7};
    public final PackageManager pm = new PackageManager();
    public PackageManager getPackageManager() { return pm; }
    public String getPackageName() { return "test"; }
    public File getFilesDir() { return new File("private"); }
    public Context getAssets() { return this; }
    public InputStream open(String path) throws IOException {
        opens++;
        byte[] bytes = path.endsWith(".seed") ? seed : path.endsWith(".pairs") ? pairs : path.endsWith(".programs") ? programs : profile;
        if (bytes == null) throw new FileNotFoundException(path);
        return new ByteArrayInputStream(bytes);
    }
}''',
            'dev/nxnative/android/BuildConfig.java': '''package dev.nxnative.android;
public class BuildConfig { public static boolean NX_GENERIC_HOST = true; }''',
            'Check.java': '''import android.content.Context;
import dev.peanuts.build.GenericProfile;
import dev.nxnative.android.BuildConfig;
import java.nio.file.*;
import java.util.List;

public class Check {
    static Path file(String env) { return Path.of(env.split("=", 2)[1]); }
    public static void main(String[] args) throws Exception {
        Context c = new Context();
        List<String> initial = GenericProfile.envLines(c, "title");
        assert Files.readAllBytes(file(initial.get(0)))[0] == 1;
        assert Files.readAllBytes(file(initial.get(1)))[0] == 2;
        assert Files.readAllBytes(file(initial.get(2)))[0] == 6 && initial.get(2).startsWith("DM_PROFILE_PAIRS=");
        assert Files.readAllBytes(file(initial.get(3)))[0] == 7 && initial.get(3).startsWith("DM_PROFILE_PROGRAMS=");
        assert GenericProfile.envLines(c, "title").equals(initial) && c.opens == 4;

        c.pm.lastUpdateTime++;
        c.profile[0] = 3;
        c.seed = null;
        c.programs = null;
        List<String> updated = GenericProfile.envLines(c, "title");
        assert Files.readAllBytes(file(updated.get(0)))[0] == 3;
        assert updated.get(1).equals("DM_PROFILE_SEED=");
        assert updated.get(3).equals("DM_PROFILE_PROGRAMS=");

        Path pack = Files.createDirectory(Path.of("mod"));
        Path override = pack.resolve("profile.bin");
        Files.write(override, new byte[]{4});
        Path mod = file(GenericProfile.envLines(c, "title", pack.toFile()).get(0));
        assert Files.readAllBytes(mod)[0] == 4 && !mod.equals(file(updated.get(0)));
        Files.write(override, new byte[]{5});
        GenericProfile.envLines(c, "title", pack.toFile());
        assert Files.readAllBytes(mod)[0] == 5;
        BuildConfig.NX_GENERIC_HOST = false;
        assert GenericProfile.envLines(c, "title", pack.toFile()).isEmpty();
    }
}''',
        }
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            for name, code in sources.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
            production = ROOT / 'android/app/src/main/java/dev/peanuts/build/GenericProfile.java'
            subprocess.run(['javac', '-d', '.', *sources, production], cwd=work, check=True)
            subprocess.run(['java', '-ea', 'Check'], cwd=work, check=True)
