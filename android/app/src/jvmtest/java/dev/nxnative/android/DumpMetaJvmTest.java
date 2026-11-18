package dev.nxnative.android;

import java.io.File;

/**
 * A folder dump's control data and other programs on the JVM (dm/tools/tests/test_android_dumpmeta.py): Nacp.read, DumpLayout.controlDir and
 * DumpLayout.programDir. Commands, each printing one line: "nacp &lt;control folder&gt; &lt;language tag&gt;" → title|publisher|version|icon size;
 * "control &lt;dump&gt;" and "program &lt;dump&gt; &lt;n&gt;" → the folder found, relative to the dump ("-" for none); "playable &lt;dump&gt;" → the title its card is for
 * (Pack.playableIdOf); "missing &lt;title ID&gt;" → Profile.missing's sentence ("null" for none); "find &lt;dump&gt;" → why Prepare cannot start (DumpLayout.find, "null")
 */
public final class DumpMetaJvmTest {
    private static String rel(File dump, File f) {
        if (f == null) return "-";
        String d = dump.getAbsolutePath(), p = f.getAbsolutePath();
        return (p.startsWith(d) ? p.substring(d.length() + 1) : p).replace(File.separatorChar, '/');
    }

    public static void main(String[] a) {
        switch (a[0]) {
            case "nacp": {
                Nacp.Control c = Nacp.read(new File(a[1]), a[2]);
                System.out.println(c == null ? "null" : c.title + "|" + c.publisher + "|" + c.version + "|" + (c.icon == null ? -1 : c.icon.length));
                break;
            }
            case "control": System.out.println(rel(new File(a[1]), DumpLayout.controlDir(new File(a[1])))); break;
            case "program": System.out.println(rel(new File(a[1]), DumpLayout.programDir(new File(a[1]), Integer.parseInt(a[2])))); break;
            case "playable": {   // the title a dump's card is for (Pack.playableIdOf), with the stub's profiles
                try { System.out.println(Pack.playableIdOf(new File(a[1]))); } catch (java.io.IOException e) { System.out.println("error: " + e.getMessage()); }
                break;
            }
            case "missing": System.out.println(String.valueOf(Profile.missing(a[1], null))); break;
            case "find": System.out.println(String.valueOf(DumpLayout.find(new File(a[1]), false).error)); break;
            default: throw new IllegalArgumentException(a[0]);
        }
    }
}
