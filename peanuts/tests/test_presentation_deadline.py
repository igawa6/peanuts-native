"""The display's queue lead reaches the native renderer without a mode override."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PresentationDeadline(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_activity_forwards_current_display_deadline(self):
        activity = (ROOT / 'android/app/src/main/java/dev/nxnative/android/GameActivity.java').read_text()
        begin = activity.index('    private void presentationDeadlineUpdate() {')
        helper = activity[begin:activity.index('    // PEANUTS end', begin)]
        harness = '''package dev.nxnative.android;
class Native {static long deadline; static void presentationDeadlineSet(long ns){deadline=ns;}}
public class DeadlineTest {
    android.view.Display display;
    android.view.Display getDisplay(){return display;}
'''+helper+'''
    public static void main(String[] args) {
        DeadlineTest activity=new DeadlineTest();
        activity.display=new android.view.Display(11333333L);
        activity.presentationDeadlineUpdate();
        if(Native.deadline!=11333333L)throw new AssertionError();
        activity.display=new android.view.Display(16666666L);
        activity.presentationDeadlineUpdate();
        if(Native.deadline!=16666666L)throw new AssertionError();
        activity.display=new android.view.Display(5000000000L);
        activity.presentationDeadlineUpdate();
        if(Native.deadline!=5000000000L)throw new AssertionError("truncated nanoseconds");
        activity.display=null; activity.presentationDeadlineUpdate();
        if(Native.deadline!=0)throw new AssertionError("stale deadline for missing display");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='presentation-deadline-java-') as td:
            display = Path(td) / 'Display.java'
            display.write_text('''package android.view;
public class Display {final long deadline; public Display(long ns){deadline=ns;}
public long getPresentationDeadlineNanos(){return deadline;}}''')
            test = Path(td) / 'DeadlineTest.java'
            test.write_text(harness)
            subprocess.run(['javac', '-d', td, str(display), str(test)], check=True)
            subprocess.run(['java', '-cp', td, 'dev.nxnative.android.DeadlineTest'], check=True)

    @unittest.skipUnless(shutil.which('cc'), 'C compiler required')
    def test_jni_64_bit_values_and_unavailable_host(self):
        native = (ROOT / 'android/app/src/main/cpp/nxlaunch.c').read_text()
        begin = native.index('JNIEXPORT void JNICALL Java_dev_nxnative_android_Native_presentationDeadlineSet(')
        bridge = native[begin:native.index('\n}', begin)+2]
        harness = '''
#include <assert.h>
#include <stddef.h>
#include "vk_android.h"
#pragma weak nxhost_presentation_deadline_set
typedef void JNIEnv;
typedef void *jclass;
typedef int64_t jlong;
#define JNIEXPORT
#define JNICALL
uint64_t received;
'''+bridge+'''
int main(void) {
    const int64_t values[]={11333333,16666666,INT64_C(5000000000),INT64_MAX,0,-1,INT64_MIN};
    for(unsigned i=0;i<sizeof(values)/sizeof(values[0]);i++) {
        received=UINT64_MAX;
        Java_dev_nxnative_android_Native_presentationDeadlineSet(NULL,NULL,values[i]);
#ifdef AVAILABLE
        assert(received==(values[i]>0 ? (uint64_t)values[i] : 0));
#else
        assert(received==UINT64_MAX);
#endif
    }
    return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='presentation-deadline-c-') as td:
            source = Path(td) / 'bridge.c'
            source.write_text(harness)
            host = Path(td) / 'host.c'
            host.write_text('#include "vk_android.h"\nextern uint64_t received;\nvoid nxhost_presentation_deadline_set(uint64_t ns){received=ns;}\n')
            for available in (False, True):
                with self.subTest(available=available):
                    exe = Path(td) / ('available' if available else 'unavailable')
                    args = ['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', '-I', str(ROOT / 'dm/gfx/vk_android'), str(source)]
                    if available:
                        args += ['-DAVAILABLE', str(host)]
                    compiled = subprocess.run([*args, '-o', str(exe)], capture_output=True, text=True)
                    self.assertEqual(compiled.returncode, 0, compiled.stderr)
                    subprocess.run([str(exe)], check=True)


if __name__ == '__main__':
    unittest.main()
