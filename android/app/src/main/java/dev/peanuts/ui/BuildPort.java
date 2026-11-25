package dev.peanuts.ui;

import android.content.Context;
import android.util.Log;

/**
 * The game sheet's Build button → the build pipeline (dev.peanuts.build.BuildPipeline: import the NSP/XCI into a .nup, add it to upstream's list and Prepare it,
 * install the companion). Found through reflection: dev.peanuts.build.BuildPipeline implementing this interface with a public no-arg constructor (HOOKS.md); without
 * it the stub ends the build at once with "not available", so the sheet works before the pipeline lands.
 */
public interface BuildPort {
    String PIPELINE_CLASS = "dev.peanuts.build.BuildPipeline";

    /** What the pipeline tells the sheet; every call may come from any thread (the sheet posts to the main one) */
    interface Listener {
        /** A step's progress: step in words ("Importing", "Shaders"), done/total in whatever unit the step counts (total 0: unknown) */
        void progress(String step, long done, long total);
        /** The end: ok, or the error in a sentence */
        void done(boolean ok, String message);
    }

    /** Starts the build for the sheet's state; returns at once, the listener hears the rest */
    void build(Context c, GameSheet.State state, Listener l);

    static BuildPort get() { return Holder.PORT; }

    final class Holder {
        static final BuildPort PORT = make();
        private Holder() {}
        private static BuildPort make() {
            try {
                Class<?> k = Class.forName(PIPELINE_CLASS);
                if (BuildPort.class.isAssignableFrom(k)) return (BuildPort) k.getDeclaredConstructor().newInstance();
                return new Pipeline(k);   // the build pipeline's static API: start(Context, BuildRequest, BuildListener)
            } catch (Throwable t) {
                Log.i("peanuts", "build pipeline not available: " + t);
            }
            return new Stub();
        }
    }

    /**
     * dev.peanuts.build.BuildPipeline.start(Context, BuildRequest, BuildListener) through reflection: BuildRequest's public fields (titleId, name, base, update, dlcs,
     * gameDir = the library root, companionPackage) from the sheet's State; BuildListener (progress(step, percent, detail), done(ok, message)) as a Proxy over ours
     */
    final class Pipeline implements BuildPort {
        private final java.lang.reflect.Method start;
        private final Class<?> request, listener;

        Pipeline(Class<?> k) throws ReflectiveOperationException {
            request = Class.forName("dev.peanuts.build.BuildRequest");
            listener = Class.forName("dev.peanuts.build.BuildListener");
            start = k.getMethod("start", Context.class, request, listener);
        }

        @Override public void build(Context c, GameSheet.State s, Listener l) {
            try {
                Object r = request.getDeclaredConstructor().newInstance();
                set(r, "titleId", s.titleId); set(r, "name", s.name); set(r, "base", s.base); set(r, "update", s.update);
                set(r, "dlcs", new java.util.ArrayList<>(s.dlcs));
                String root = dev.peanuts.PeanutsPrefs.gameDir(c);
                set(r, "gameDir", root == null ? null : new java.io.File(root));
                set(r, "companionPackage", s.installCompanion ? s.companionPackage : null);
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "progress": l.progress(a[2] == null || a[2].toString().isEmpty() ? String.valueOf(a[0]) : a[0] + ": " + a[2], ((Number) a[1]).longValue(), 100); return null;
                        case "done": l.done(Boolean.TRUE.equals(a[0]), a[1] == null ? "" : a[1].toString()); return null;
                        case "toString": return "BuildListener(peanuts.ui)";
                        case "hashCode": return System.identityHashCode(p);
                        case "equals": return p == a[0];
                        default: return null;
                    }
                });
                start.invoke(null, c, r, proxy);
            } catch (Throwable t) {
                Throwable x = t.getCause() != null ? t.getCause() : t;
                Log.w("peanuts", "build: " + x);
                l.done(false, "The build could not start. Tap Build again.");   // the reason is in the log line above
            }
        }

        private void set(Object r, String f, Object v) throws ReflectiveOperationException { request.getField(f).set(r, v); }
    }

    final class Stub implements BuildPort {
        @Override public void build(Context c, GameSheet.State s, Listener l) {
            Log.w("peanuts", "build: no " + PIPELINE_CLASS + " in this APK");
            l.done(false, "This app version cannot build games on the phone.");
        }
    }
}
