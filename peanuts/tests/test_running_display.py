"""Running-cap display mitigation and requested FG state, independent of renderer warm-up."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RunningDisplay(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_running_policy_window_changes_and_ui_fg_release(self):
        java = ROOT / 'android/app/src/main/java'
        lsfg = (java / 'dev/nxnative/android/Lsfg.java').read_text()
        push = lsfg[lsfg.index('    static void push(Context c) {'):lsfg.rfind('\n}')]
        sources = {
            'android/content/Context.java': 'package android.content; public class Context {public boolean enabled;}',
            'android/app/Activity.java': '''package android.app;
public class Activity extends android.content.Context {
    public final android.view.Window window=new android.view.Window();
    public android.view.Display display;
    public android.view.Window getWindow(){return window;}
    public android.view.Display getDisplay(){return display;}
    public boolean isFinishing(){return false;}
}''',
            'dev/peanuts/Screens.java': '''package dev.peanuts;
public class Screens {public static boolean tv; public static boolean onTv(android.app.Activity a){return tv;}}''',
            'android/view/WindowManager.java': '''package android.view;
public class WindowManager {public static class LayoutParams {public int preferredDisplayModeId;}}''',
            'android/view/Window.java': '''package android.view;
public class Window {
    public final WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();
    public int updates;
    public WindowManager.LayoutParams getAttributes(){return attrs;}
    public void setAttributes(WindowManager.LayoutParams value){updates++;}
}''',
            'android/view/Display.java': '''package android.view;
public class Display {
    final Mode current; final Mode[] supported;
    public Display(Mode current,Mode[] supported){this.current=current;this.supported=supported;}
    public Mode getMode(){return current;} public Mode[] getSupportedModes(){return supported;}
    public static class Mode {
        final int id,width,height; final float hz;
        public Mode(int id,int width,int height,float hz){this.id=id;this.width=width;this.height=height;this.hz=hz;}
        public int getModeId(){return id;} public int getPhysicalWidth(){return width;}
        public int getPhysicalHeight(){return height;} public float getRefreshRate(){return hz;}
    }
}''',
            'dev/nxnative/android/DisplayTest.java': '''package dev.nxnative.android;
import android.content.Context;
import android.app.Activity;
import dev.peanuts.display.RunningDisplayMode;
import dev.peanuts.display.RunningDisplayPolicy;
import dev.peanuts.display.RunningDisplayPolicy.Mode;
class Native {
    static Activity activity; static int calls;
    static void frameGenSet(boolean on,int mult,int target,int flow) {
        if(on && activity!=null && activity.window.attrs.preferredDisplayModeId!=0)
            throw new AssertionError("native enable preceded request release");
        calls++;
    }
}
class Lsfg {
    static boolean on(Context c){return c.enabled;}
    static int multiplier(Context c){return 2;}
    static int target(Context c){return 0;}
    static int flow(Context c){return 50;}
'''+push+'''
}
public class DisplayTest {
    static final Mode[] MODES={new Mode(1,1920,1080,120),new Mode(2,1280,720,60),
        new Mode(3,1920,1080,59.83f),new Mode(4,1920,1080,60.00001f)};
    static int mode(String status,int fg){return RunningDisplayPolicy.mode(RunningDisplayPolicy.effectiveCap(status),fg,1920,1080,MODES);}
    static void expect(int actual,int expected){if(actual!=expected)throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args) {
        expect(mode("120 0 30 30",0),4); // effective fourth field, never the first or a pending preference
        for(String s:new String[]{"30 0 120 120","30 0 60 60","30 0 30 0","0 0 0 30",null,"","bad","30 0 30","0 0 x 30"})expect(mode(s,0),0);
        expect(mode("0 0 30 30",1),0); expect(mode("0 0 30 30",-1),0);
        expect(RunningDisplayPolicy.mode(30,0,1280,720,MODES),2);
        expect(RunningDisplayPolicy.mode(30,0,1600,900,MODES),0);
        expect(RunningDisplayPolicy.mode(30,0,1920,1080,new Mode[]{MODES[0],MODES[1]}),0);
        expect(RunningDisplayPolicy.mode(30,0,1920,1080,new Mode[]{MODES[2]}),3);
        expect(RunningDisplayPolicy.mode(30,0,1920,1080,new Mode[]{new Mode(8,1920,1080,59)}),0);
        expect(RunningDisplayPolicy.mode(30,0,1920,1080,new Mode[0]),0);
        Activity activity=new Activity();
        android.view.Display.Mode high=new android.view.Display.Mode(1,1920,1080,120);
        activity.display=new android.view.Display(high,new android.view.Display.Mode[]{high,new android.view.Display.Mode(4,1920,1080,60)});
        RunningDisplayMode window=new RunningDisplayMode();
        window.update(activity,"120 0 30 30",0); expect(activity.window.attrs.preferredDisplayModeId,4);
        int updates=activity.window.updates;
        window.update(activity,"120 0 30 30",0); expect(activity.window.updates,updates);
        window.update(activity,"120 0 60 60",0); expect(activity.window.attrs.preferredDisplayModeId,0);
        window.update(activity,"120 0 30 30",0);
        window.update(activity,"120 0 30 30",1); expect(activity.window.attrs.preferredDisplayModeId,0);
        window.update(activity,"120 0 30 30",0); activity.display=null;
        window.update(activity,"120 0 30 30",0); expect(activity.window.attrs.preferredDisplayModeId,0);
        activity.window.attrs.preferredDisplayModeId=4; window.clear(activity);
        expect(activity.window.attrs.preferredDisplayModeId,0);
        Native.activity=activity; activity.window.attrs.preferredDisplayModeId=4;
        updates=activity.window.updates; Lsfg.push(activity); // disabled retains request
        expect(activity.window.updates,updates); expect(activity.window.attrs.preferredDisplayModeId,4);
        activity.enabled=true; Lsfg.push(activity); expect(activity.window.updates,updates+1);
        Lsfg.push(activity); expect(activity.window.updates,updates+1); // no redundant layout
        Native.activity=null; Context context=new Context(); context.enabled=true; Lsfg.push(context);
        expect(Native.calls,4);
        // Play on the TV (#358): 60 Hz when the TV runs 30 or 50 Hz, at its own resolution when it has it, else the largest below; kept while paused
        Mode[] tvModes={new Mode(1,3840,2160,30),new Mode(2,1920,1080,60),new Mode(3,3840,2160,60),new Mode(4,1920,1080,50),new Mode(5,1280,720,60)};
        expect(RunningDisplayPolicy.tvMode(3840,2160,30,tvModes),3);
        expect(RunningDisplayPolicy.tvMode(3840,2160,30,new Mode[]{tvModes[0],tvModes[1],tvModes[4]}),2);
        expect(RunningDisplayPolicy.tvMode(1920,1080,50,tvModes),2);
        for(float hz:new float[]{60,59.94f,120,119.88f,240})expect(RunningDisplayPolicy.tvMode(1920,1080,hz,tvModes),0);
        expect(RunningDisplayPolicy.tvMode(1920,1080,144,new Mode[]{new Mode(6,1920,1080,144),tvModes[1]}),2);
        expect(RunningDisplayPolicy.tvMode(3840,2160,30,new Mode[]{tvModes[0]}),0);
        dev.peanuts.Screens.tv=true;
        Activity tv=new Activity();
        android.view.Display.Mode k30=new android.view.Display.Mode(1,3840,2160,30), p60=new android.view.Display.Mode(2,1920,1080,60);
        tv.display=new android.view.Display(k30,new android.view.Display.Mode[]{k30,p60});
        window.update(tv,"60 0 60 60",0); expect(tv.window.attrs.preferredDisplayModeId,2);
        tv.display=new android.view.Display(p60,new android.view.Display.Mode[]{k30,p60});   // the TV switched: the request stays, no back and forth
        window.update(tv,"60 0 60 60",0); expect(tv.window.attrs.preferredDisplayModeId,2);
        window.clear(tv); expect(tv.window.attrs.preferredDisplayModeId,2);
        window.update(tv,"60 0 60 60",1); expect(tv.window.attrs.preferredDisplayModeId,0);   // frame generation decides its own rate
        dev.peanuts.Screens.tv=false;
        window.update(tv,"60 0 60 60",0); expect(tv.window.attrs.preferredDisplayModeId,0);
    }
}
''',
        }
        with tempfile.TemporaryDirectory(prefix='running-display-java-') as td:
            paths = []
            for name, content in sources.items():
                path = Path(td) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                paths.append(str(path))
            paths += [str(java / 'dev/peanuts/display' / name) for name in ('RunningDisplayMode.java', 'RunningDisplayPolicy.java')]
            subprocess.run(['javac', '-d', td, *paths], check=True)
            subprocess.run(['java', '-cp', td, 'dev.nxnative.android.DisplayTest'], check=True)

    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_requested_frame_generation_state_precedes_rendering(self):
        text = (ROOT / 'dm/gfx/vk_android/vka_framegen.c').read_text()
        init = text[text.index('static void settings_init(void) {'):text.index('// PEANUTS: let window display policy')]
        requested = text[text.index('int nxhost_framegen_enabled(void) {'):text.index('void nxhost_framegen_set(')]
        setter = text[text.index('void nxhost_framegen_set('):text.index('int nxhost_framegen_status(')]
        harness = r'''
#include <assert.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
static _Atomic int g_on=-1,g_mult=2,g_target,g_flow=75;
static int initial;
static const char *environment(const char *key) {return !strcmp(key,"DM_LSFG") ? initial ? "1" : "0" : NULL;}
#define getenv environment
#define VLOG(...) ((void)0)
'''+init+requested+setter+r'''
int main(void) {
    initial=1;
    assert(nxhost_framegen_enabled()==1); // requested state before renderer initialization
    nxhost_framegen_set(0,2,0,75); assert(nxhost_framegen_enabled()==0);
    nxhost_framegen_set(1,4,120,50); assert(nxhost_framegen_enabled()==1);
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='running-display-fg-') as td:
            source, exe = Path(td) / 'requested.c', Path(td) / 'requested'
            source.write_text(harness)
            compiled = subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', str(source), '-o', str(exe)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            subprocess.run([str(exe)], check=True)


if __name__ == '__main__':
    unittest.main()
