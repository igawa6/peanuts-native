"""Execute the preparation environment hook with a multi-user player's settings."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ShaderCaptureEnvTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_capture_isolates_accounts_and_saves_without_changing_play(self):
        source = (ROOT / 'android/app/src/main/java/dev/peanuts/build/ShaderCapture.java').read_text()
        hook = source[source.index('    public static void environment('):source.index('    /** Keep the rendering surface')]
        harness = '''
import java.util.*;
public class CaptureEnvTest {
 static final String SAVE="save",DIR="capture";
 static class Activity {
  boolean capture; Activity(boolean c){capture=c;}
  Activity getIntent(){return this;}
  String getStringExtra(String k){return k.equals(SAVE)?"/isolated-save":"/captured";}
 }
 static boolean active(Activity a){return a.capture;}
 HOOK
 public static void main(String[] args) {
  List<String> player=List.of("DM_USERS=alice,bob", "DM_USER=bob", "DM_SAVE=/player-save", "DM_KEYS=A", "DM_AUDIO=1", "PN_COMPANION=on", "DM_GLSLC_CAPTURE=/old", "DM_VK_DRIVER=/driver");
  List<String> normal=new ArrayList<>(player);environment(new Activity(false),normal);
  if(!normal.equals(player))throw new AssertionError("normal Play changed");
  List<String> capture=new ArrayList<>(player);environment(new Activity(true),capture);
  if(capture.stream().anyMatch(s->s.startsWith("DM_USERS=")||s.startsWith("DM_USER=")||s.startsWith("DM_KEYS=")))throw new AssertionError(capture);
  for(String key:List.of("DM_SAVE=","DM_AUDIO=","PN_COMPANION=","DM_GLSLC_CAPTURE="))
   if(capture.stream().filter(s->s.startsWith(key)).count()!=1)throw new AssertionError(capture);
  if(!capture.containsAll(List.of("DM_SAVE=/isolated-save","DM_AUDIO=0","PN_COMPANION=off","DM_GLSLC_CAPTURE=/captured","DM_VK_DRIVER=/driver")))throw new AssertionError(capture);
 }
}
'''.replace('HOOK', hook)
        with tempfile.TemporaryDirectory(prefix='capture-env-') as td:
            path = Path(td) / 'CaptureEnvTest.java'
            path.write_text(harness)
            subprocess.run(['javac', str(path)], check=True)
            subprocess.run(['java', '-cp', td, 'CaptureEnvTest'], check=True)


if __name__ == '__main__':
    unittest.main()
