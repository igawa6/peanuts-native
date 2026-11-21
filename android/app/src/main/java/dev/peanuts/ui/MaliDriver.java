package dev.peanuts.ui;

import java.util.Map;

/**
 * Placed memory maps on an Arm GPU (Mali, Immortalis; GitHub issues #20 and #262). Arm's own driver has neither VK_KHR_map_memory2 nor
 * VK_EXT_map_memory_placed, so on these phones System is no way out of a missing placed map: only a PanVK build that has them is, and not every
 * PanVK build does (on Arm's kbase kernel driver a build needs its own way to place memory). The start-up notice (GameActivity.readinessDecide) and
 * the GPU driver page take their words from here. No Android classes, so it runs on the JVM.
 */
public final class MaliDriver {
    private MaliDriver() {}

    /** Arm's PCI vendor ID, as VkPhysicalDeviceProperties.vendorID reports it (gpu.vendor); PanVK reports it too */
    static final long VENDOR_ARM = 0x13b5;

    /** Whether the readiness facts are an Arm GPU's: its vendor ID, else its name */
    public static boolean arm(Map<String, String> f) {
        if (f == null) return false;
        String v = f.get("gpu.vendor");
        if (v != null && !v.trim().isEmpty()) {
            try { return Long.decode(v.trim()) == VENDOR_ARM; } catch (NumberFormatException e) { /* the name decides */ }
        }
        return DriverMatch.MALI.equals(DriverMatch.family(f.get("gpu.name")));
    }

    /**
     * The start-up notice's words after "Missing: ..." when placed memory maps are missing on an Arm GPU. driver: the picked driver's name, null for
     * System; f: the check's facts, whose driver.custom names the custom driver that really ran (empty: it did not load, and Android's driver ran).
     * Never "Pick System": the phone's own driver lacks them as well.
     */
    public static String advice(String driver, Map<String, String> f) {
        String where = "Settings › GPU driver › Add a driver";
        if (driver == null)
            return ".\n\nThe phone's own Mali driver has no placed memory maps, so nothing is drawn: the screen stays black while the game runs. "
                    + "Install a PanVK driver that has them (" + where + ") and pick it there, or in this game's Game settings.";
        boolean loaded = f != null && f.containsKey("gpu.name") && !f.getOrDefault("driver.custom", "").isEmpty();
        if (!loaded && f != null && f.containsKey("gpu.name"))
            return ".\n\nThe picked GPU driver (" + driver + ") did not load, so the phone's own Mali driver ran, and it has no placed memory maps: "
                    + "the screen stays black while the game runs. Try another PanVK build (" + where + "), there or in this game's Game settings.";
        return ".\n\nWith this GPU driver (" + driver + ") nothing is drawn: the screen stays black while the game runs. "
                + "The phone's own Mali driver has no placed memory maps either. Pick a PanVK build that has them (" + where + "), "
                + "there or in this game's Game settings.";
    }

    /** The GPU driver page's hint under Add a driver on a Mali or Immortalis phone */
    static String hint() {
        return "Games need a PanVK build with placed memory maps, and not every build has them. Check the release notes for your GPU model.";
    }
}
