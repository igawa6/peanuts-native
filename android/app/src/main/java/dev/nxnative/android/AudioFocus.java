package dev.nxnative.android;

/**
 * The game's audio focus (GameActivity's AudioFocusRequest), without Android types so the JVM test can run it (AudioFocusJvmTest).
 * A transient loss (a call, a navigation prompt, a notification sound) is followed by a gain from Android once the other app is done. A permanent loss (another app
 * started playing music or a video, which a frontend or the home screen may do while the game is minimized) never is: Android drops the request, so the game kept its
 * sound suspended (Native.audioInterrupt) after returning, with everything else running. A focus that is lost for good, or was refused, is asked for again on the return
 * PEANUTS: with Settings › App › Extras › Play with other apps' sound on (PeanutsPrefs.mixAudio, off by default) the game asks for no focus, so it plays beside a video in
 * picture-in-picture, music or a frontend's sounds, and their playing never silences it. No loss reaches the game for a phone call then either, so the audio mode
 * stands in (AudioManager.OnModeChangedListener, interrupts): a call interrupts the sound from the ringing to the hang-up. A voice chat (MODE_IN_COMMUNICATION) does not
 */
final class AudioFocus {
    // AudioManager's values
    static final int GAIN = 1, LOSS = -1, LOSS_TRANSIENT = -2, LOSS_TRANSIENT_CAN_DUCK = -3;
    // PEANUTS begin: AudioManager's MODE_* values, for Play with other apps' sound
    static final int MODE_NORMAL = 0, MODE_RINGTONE = 1, MODE_IN_CALL = 2, MODE_IN_COMMUNICATION = 3, MODE_CALL_SCREENING = 4, MODE_CALL_REDIRECT = 5,
            MODE_COMMUNICATION_REDIRECT = 6;
    // PEANUTS end

    private boolean dropped;   // lost for good or refused: no gain will come without a new request

    /** The listener's change: whether the game's sound is interrupted now */
    boolean changed(int f) {
        if (f == GAIN) { dropped = false; return false; }
        if (f == LOSS) dropped = true;
        return f == LOSS || f == LOSS_TRANSIENT || f == LOSS_TRANSIENT_CAN_DUCK;
    }
    /** The answer to a request: granted ends the interruption (no listener call comes for it), refused leaves it to the next return */
    void requested(boolean granted) { dropped = !granted; }
    /** On returning to the foreground: whether to request the focus again */
    boolean askAgain() { return dropped; }

    /** PEANUTS: Play with other apps' sound (no focus): whether the game's sound is interrupted in this audio mode, a phone call ringing, in progress, screened or on another device */
    static boolean interrupts(int mode) { return mode == MODE_RINGTONE || mode == MODE_IN_CALL || mode == MODE_CALL_SCREENING || mode == MODE_CALL_REDIRECT; }
}
