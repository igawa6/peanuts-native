package dev.peanuts.ui;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Upstream's GPU driver classes (dev.nxnative.android GpuDriver, DriverFetch, DriverZip, GpuGl, all package-private) read through reflection, as Upstream
 * does for the launcher's: the installed drivers and the pick, an install through GpuDriver.install (so DriverZip's checks stay the only way in), a removal,
 * the download sources (DriverFetch.REPOS, the one list) and the GPU's name. Nothing here touches the network; installs and file reads go off the main thread.
 */
final class DriverPort {
    private DriverPort() {}

    private static final String P = "dev.nxnative.android.";

    /** An installed driver: its folder and its meta.json's facts; info is upstream's GpuDriver.Info for it */
    static final class Driver {
        final File dir; final String name, version, vendor, author, description; final boolean recommended; final Object info;
        Driver(Object info) throws ReflectiveOperationException {
            this.info = info;
            dir = (File) get(info, "dir");
            name = str(get(info, "name")); version = str(get(info, "version")); vendor = str(get(info, "vendor"));
            recommended = (Boolean) method("GpuDriver", "recommended", info.getClass()).invoke(null, info);
            JSONObject m = meta(dir);
            author = m == null ? "" : m.optString("author", "").trim();
            description = m == null ? "" : m.optString("description", "").trim();
        }
    }

    static List<Driver> installed(Context c) throws ReflectiveOperationException {
        List<Driver> out = new ArrayList<>();
        for (Object i : (List<?>) method("GpuDriver", "installed", Context.class).invoke(null, c)) out.add(new Driver(i));
        return out;
    }

    /** The picked driver's folder; null for the phone's own */
    static File selected(Context c) throws ReflectiveOperationException {
        Object i = method("GpuDriver", "selected", Context.class).invoke(null, c);
        return i == null ? null : (File) get(i, "dir");
    }

    /** Picks a driver for every game without its own; null picks the phone's own */
    static void select(Context c, Driver d) throws ReflectiveOperationException {
        method("GpuDriver", "select", Context.class, Class.forName(P + "GpuDriver$Info")).invoke(null, c, d == null ? null : d.info);
    }

    static String gameChoice(Context c, String titleId) throws ReflectiveOperationException {
        return (String) method("GpuDriver", "choice", Context.class, String.class).invoke(null, c, titleId);
    }

    static void gameChoice(Context c, String titleId, String choice) throws ReflectiveOperationException {
        method("GpuDriver", "choice", Context.class, String.class, String.class).invoke(null, c, titleId, choice);
    }

    static String gameRecommendation(Context c, String titleId) throws ReflectiveOperationException {
        return (String) method("Prefs", "recommendedDriver", Context.class, String.class).invoke(null, c, titleId);
    }

    static String systemChoice() throws ReflectiveOperationException {
        Field system = Class.forName(P + "GpuDriver").getDeclaredField("SYSTEM");
        system.setAccessible(true);
        return (String) system.get(null);
    }

    /** Unpacks a driver zip (GpuDriver.install: meta.json read, every entry checked, an earlier one of its name replaced only once this one is in) */
    static Driver install(Context c, InputStream zip, String fallbackName) throws IOException {
        return installed(() -> method("GpuDriver", "install", Context.class, InputStream.class, String.class).invoke(null, c, zip, fallbackName));
    }

    static Driver install(Context c, Uri uri) throws IOException {
        return installed(() -> method("GpuDriver", "install", Context.class, Uri.class).invoke(null, c, uri));
    }

    static Driver install(Context c, File zip) throws IOException {
        return installed(() -> method("GpuDriver", "install", Context.class, File.class).invoke(null, c, zip));
    }

    /** Deletes the driver's copy in the app; the phone's own driver when it was the pick (as upstream's Remove does) */
    static void remove(Context c, Driver d) throws ReflectiveOperationException {
        boolean picked = d.dir.equals(selected(c));
        method("DriverZip", "deleteTree", File.class).invoke(null, d.dir);
        if (picked) select(c, null);
    }

    /** The download sources: { GitHub owner/name, label } */
    static String[][] sources() throws ReflectiveOperationException {
        Field f = Class.forName(P + "DriverFetch").getDeclaredField("REPOS");
        f.setAccessible(true);
        return (String[][]) f.get(null);
    }

    /** Driver zips in the app's own folder (Android/data/<app>/files/gpu_drivers/), by name only */
    @SuppressWarnings("unchecked")
    static List<File> zips(Context c) throws ReflectiveOperationException { return (List<File>) method("GpuDriver", "found", Context.class).invoke(null, c); }

    static File zipFolder(Context c) throws ReflectiveOperationException { return (File) method("GpuDriver", "zipFolder", Context.class).invoke(null, c); }

    /** The GPU's short name ("Adreno 740"), null until the launcher has asked GLES (it does at its start) */
    static String gpu() {
        try { return (String) method("GpuGl", "name").invoke(null); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    // ---- reflection ----

    private interface Call { Object run() throws ReflectiveOperationException; }

    /** An install's driver, or its reason as an IOException (a zip without meta.json, a path that leaves its folder, …) */
    private static Driver installed(Call call) throws IOException {
        try { return new Driver(call.run()); }
        catch (InvocationTargetException e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(), t);   // a damaged zip throws unchecked exceptions too
        } catch (ReflectiveOperationException e) { throw new IOException("the app's driver installer is missing: " + e, e); }
    }

    private static Method method(String cls, String name, Class<?>... types) throws ReflectiveOperationException {
        Method m = Class.forName(P + cls).getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    private static Object get(Object o, String field) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private static JSONObject meta(File dir) {
        try { return new JSONObject(new String(Files.readAllBytes(new File(dir, "meta.json").toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return null; }
    }
}
