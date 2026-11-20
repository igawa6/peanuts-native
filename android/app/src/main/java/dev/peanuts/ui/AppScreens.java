package dev.peanuts.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import java.util.Set;

/** The second display keeps Peanuts' page while a launcher settings page is in front. */
public final class AppScreens {
    static final String STATE = "dev.peanuts.APP_PAGE";
    private static final Set<String> PAGES = Set.of("dev.peanuts.CatalogActivity", DriverActivity.class.getName(),
            "dev.nxnative.android.ReadinessActivity", "dev.nxnative.android.SelftestActivity");

    private AppScreens() {}

    static boolean isPage(Activity a) { return PAGES.contains(a.getClass().getName()); }

    static String key(Activity a) { return Application.getProcessName() + "/" + a.getClass().getName() + "@" + System.identityHashCode(a); }

    /** NxApp.onCreate (PEANUTS hook), every process. Pages in another process than the launcher's tell BottomScreen when they come and go */
    public static void install(Application app) {
        String p = Application.getProcessName();
        if (p == null || p.equals(app.getPackageName()) || p.endsWith(":game")) return;   // the launcher sees its own; a game has GameScreen
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { tell(a, true); }
            @Override public void onActivityStopped(Activity a) { tell(a, false); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static void tell(Activity a, boolean front) {
        if (!isPage(a)) return;
        try { a.sendBroadcast(new Intent(STATE).setPackage(a.getPackageName()).putExtra("page", key(a)).putExtra("front", front)); } catch (RuntimeException ignored) { }
    }
}
