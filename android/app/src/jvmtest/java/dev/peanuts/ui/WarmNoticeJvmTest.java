package dev.peanuts.ui;

/**
 * WarmNotice on the JVM, driven by dm/tools/tests/test_android_warmnotice.py. One command per run: "&lt;waiting&gt;" → "&lt;shown&gt; &lt;poll&gt; &lt;the notice&gt;"
 */
public final class WarmNoticeJvmTest {
    public static void main(String[] a) {
        int n = Integer.parseInt(a[0]);
        System.out.println(WarmNotice.shown(n) + " " + WarmNotice.poll(n) + " " + WarmNotice.text(n));
    }
}
