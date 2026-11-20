package dev.peanuts.build;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * The .nup importer's Java face (dev.peanuts.importer.Native, the JNI over dm/peanuts/import) reached through reflection, so this package compiles and
 * runs without it: without the class, {@link #available()} is false and an NSP/XCI build says so.
 * <pre>
 *   static int keys(String prodKeys);  static String probe(String file);  static String lastError();
 *   static int writeNup(String out, String[] inputs, String baseTitleId, Native.Progress cb);  interface Progress { void on(String step, long done, long total); }
 * </pre>
 */
final class Importer {
    interface Progress { void on(String step, long done, long total); }

    static final String CLASS = "dev.peanuts.importer.Native";

    private Importer() {}

    static boolean available() {
        try { Class.forName(CLASS); return true; } catch (Throwable t) { return false; }
    }

    static String lastError() {
        try {
            Object s = Class.forName(CLASS).getMethod("lastError").invoke(null);
            return s == null ? "" : s.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 0 ok, else the importer's code (lastError says why) */
    static int keys(File prodKeys) throws ReflectiveOperationException {
        return (Integer) Class.forName(CLASS).getMethod("keys", String.class).invoke(null, prodKeys.getPath());
    }

    static String probe(File f) throws ReflectiveOperationException {
        Object s = Class.forName(CLASS).getMethod("probe", String.class).invoke(null, f.getPath());
        return s == null ? "" : s.toString();
    }

    /** Stop (on), or clear a Stop meant for an earlier import (off, before one starts). An older importer without it: nothing (Stop then waits for the end) */
    static void cancel(boolean on) {
        try { dev.peanuts.importer.Native.cancel(on); } catch (Throwable t) { /* no such native method: the import runs to its end, as before */ }
    }

    /**
     * What an import of these files takes, from the probe (the importer loaded with keys): {the .nup's bytes (the CNMT's sizes of the titles of appId), the
     * bytes of solid compressed files it unpacks into a temporary file, the bytes of block ones it reads once more to check}. A file the probe cannot
     * size (an older importer, no title of appId in it) counts with its own length.
     */
    static long[] sizes(List<File> inputs, String appId) {
        long[] s = new long[3];
        for (File f : inputs) {
            long nup = -1;
            try {
                org.json.JSONArray a = new org.json.JSONArray(probe(f));
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.getJSONObject(i);
                    if (!o.has("size") || !appId.equalsIgnoreCase(o.optString("app_id"))) continue;
                    nup = Math.max(0, nup) + o.optLong("size"); s[1] += o.optLong("unpack"); s[2] += o.optLong("check");
                }
            } catch (Throwable t) { /* not sized: its length below */ }
            s[0] += nup >= 0 ? nup : f.length();
        }
        return s;
    }

    static int writeNup(File out, List<File> inputs, String baseTitleId, Progress cb) throws ReflectiveOperationException {
        // A direct call: the callback must be a real class implementing Native.Progress (ART's CheckJNI aborts a CallVoidMethod on a java.lang.reflect.Proxy)
        String[] in = new String[inputs.size()];
        for (int i = 0; i < in.length; i++) in[i] = inputs.get(i).getPath();
        return dev.peanuts.importer.Native.writeNup(out.getPath(), in, baseTitleId, (step, done, total) -> cb.on(step, done, total));
    }
}
