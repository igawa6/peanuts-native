// PEANUTS: a test of the fork's own classes (what a key on CompanionScreen's window does, GitHub issue #183); upstream has no dev.peanuts tests
package dev.peanuts;

/** ScreenKeys.route on the JVM (no argument; prints "ok" or throws): Back from the navigation bar is the game's Back on its release only, a controller's key
 *  (Back included) is the game's, the rest stays with the activity. */
public final class ScreenKeysJvmTest {
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        final int keyboard = 0x101, down = 0, up = ScreenKeys.ACTION_UP, buttonA = 96, volumeUp = 24;
        // the navigation bar's Back (SystemUI injects it as a keyboard key)
        check(ScreenKeys.route(ScreenKeys.KEYCODE_BACK, keyboard, down, 0) == ScreenKeys.EAT, "Back's press is taken, nothing more");
        check(ScreenKeys.route(ScreenKeys.KEYCODE_BACK, keyboard, up, 0) == ScreenKeys.BACK, "Back's release is the game's Back");
        check(ScreenKeys.route(ScreenKeys.KEYCODE_BACK, keyboard, up, ScreenKeys.FLAG_CANCELED) == ScreenKeys.EAT, "a cancelled Back does nothing");
        // a controller's keys go to the game, whatever the key
        check(ScreenKeys.route(buttonA, ScreenKeys.SOURCE_GAMEPAD | keyboard, down, 0) == ScreenKeys.GAME, "a gamepad button is the game's");
        check(ScreenKeys.route(ScreenKeys.KEYCODE_BACK, ScreenKeys.SOURCE_GAMEPAD | keyboard, up, 0) == ScreenKeys.GAME, "a controller's Back is the game's key");
        check(ScreenKeys.route(19, ScreenKeys.SOURCE_DPAD, down, 0) == ScreenKeys.GAME, "a d-pad is the game's");
        check(ScreenKeys.route(buttonA, ScreenKeys.SOURCE_JOYSTICK, down, 0) == ScreenKeys.GAME, "a joystick is the game's");
        // the rest is the activity's own
        check(ScreenKeys.route(volumeUp, keyboard, down, 0) == ScreenKeys.OWN, "a keyboard key other than Back stays with the activity");
        check(!ScreenKeys.gamepad(keyboard), "a keyboard is no gamepad (its class bits overlap the d-pad's)");
        System.out.println("ok");
    }
}
