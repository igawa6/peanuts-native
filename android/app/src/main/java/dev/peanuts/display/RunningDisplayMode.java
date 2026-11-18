package dev.peanuts.display;

import android.app.Activity;
import android.content.Context;
import android.view.Display;
import android.view.WindowManager;

/** Platform mitigation for uneven 30 fps presentation observed in a high-refresh display mode; its cause remains open. */
public final class RunningDisplayMode {
    public void update(Activity activity, String status, int frameGenerationEnabled) {
        Display display = activity.getDisplay();
        int selected = 0;
        if (display != null) {
            Display.Mode current = display.getMode();
            Display.Mode[] supported = display.getSupportedModes();
            RunningDisplayPolicy.Mode[] modes = new RunningDisplayPolicy.Mode[supported.length];
            for (int i = 0; i < supported.length; i++) {
                Display.Mode mode = supported[i];
                modes[i] = new RunningDisplayPolicy.Mode(mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate());
            }
            selected = RunningDisplayPolicy.mode(RunningDisplayPolicy.effectiveCap(status), frameGenerationEnabled,
                    current.getPhysicalWidth(), current.getPhysicalHeight(), modes);
        }
        apply(activity, selected);
    }

    public void clear(Activity activity) { apply(activity, 0); }

    /** Release the window request before an in-app FG enable; the display switch itself is asynchronous. */
    public static void beforeFrameGeneration(Context context, boolean enabled) {
        if (enabled && context instanceof Activity) apply((Activity) context, 0);
    }

    private static void apply(Activity activity, int mode) {
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        if (attributes.preferredDisplayModeId == mode) return;
        attributes.preferredDisplayModeId = mode;
        activity.getWindow().setAttributes(attributes);
    }
}
