package dev.nxnative.android;

/** PEANUTS: the last driver environment line wins, just as it does at game startup. */
final class DriverLaunch {
    private DriverLaunch() {}

    static boolean usesSystem(Iterable<String> environment) {
        String driver = "";
        for (String line : environment) {
            int equals = line.indexOf('=');
            if (equals > 0 && line.substring(0, equals).trim().equals("DM_VK_DRIVER"))
                driver = line.substring(equals + 1).trim();
        }
        return driver.isEmpty() || driver.equals("system");
    }
}
