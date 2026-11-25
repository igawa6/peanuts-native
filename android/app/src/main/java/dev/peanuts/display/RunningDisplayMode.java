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
            if (selected == 0 && frameGenerationEnabled == 0) selected = tv(activity, display, modes);
        }
        apply(activity, selected);
    }

    /** Released while the game is paused or in the background; on a TV the 60 Hz request stays, so the TV does not switch its mode back and forth (each switch is a moment of black) */
    public void clear(Activity activity) {
        Display display = activity.getDisplay();
        apply(activity, display == null || activity.isFinishing() ? 0 : tv(activity, display, null));
    }

    /** Play on the TV (#358): 60 Hz on a TV or monitor that runs 30 or 50 Hz (RunningDisplayPolicy.tvMode); 0 elsewhere */
    private static int tv(Activity activity, Display display, RunningDisplayPolicy.Mode[] modes) {
        if (!dev.peanuts.Screens.onTv(activity)) return 0;
        Display.Mode current = display.getMode();
        if (modes == null) {
            Display.Mode[] supported = display.getSupportedModes();
            modes = new RunningDisplayPolicy.Mode[supported.length];
            for (int i = 0; i < supported.length; i++) modes[i] = new RunningDisplayPolicy.Mode(supported[i].getModeId(), supported[i].getPhysicalWidth(), supported[i].getPhysicalHeight(), supported[i].getRefreshRate());
        }
        int requested = activity.getWindow().getAttributes().preferredDisplayModeId;
        if (requested != 0) for (RunningDisplayPolicy.Mode m : modes) if (m.id == requested && RunningDisplayPolicy.evenFor60(m.hz)) return requested;   // the display already runs what was asked
        return RunningDisplayPolicy.tvMode(current.getPhysicalWidth(), current.getPhysicalHeight(), current.getRefreshRate(), modes);
    }

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
