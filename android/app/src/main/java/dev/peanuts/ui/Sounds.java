package dev.peanuts.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.EnumMap;

/**
 * The app's feedback sounds, the one place that plays them, as a console's menus sound: every word of the launcher's feedback ({@link Haptics}: a step, a
 * choice, Play, an edge, a job done, a refusal) has its tone, played with it; the in-game menus' steps, tabs, choices, openings and Back through
 * {@link MenuFeel}. The tones are {@link UiTones}, made here once (no files) into a static AudioTrack each, as a sonification, so they follow the system's
 * volume for those.
 * <p>
 * One setting for all of them, Sounds (Settings › App › Feel, and the in-game menu's own Menu sounds row: the same switch), on by default. Nothing plays
 * when Android's Touch sounds are off or the phone is set to silent or vibrate, and no step or edge while a screen reader speaks. The setting lives in the
 * in-game panel's prefs file, which the game's process writes too: it is read again at every {@link #refresh} (Home and every resume, every menu opening).
 * Main thread only.
 */
public final class Sounds {
    private Sounds() {}

    private static final String FILE = "peanuts_panel", KEY = "menu_sounds";
    private static boolean ready, on = true, systemOff, silent, reader;
    private static final EnumMap<UiTones, AudioTrack> tracks = new EnumMap<>(UiTones.class);

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_MULTI_PROCESS);   // read again from the file: the game's process writes it too (the in-game menu's row)
    }

    /** The setting: Sounds, on by default */
    public static boolean on(Context c) { return prefs(c).getBoolean(KEY, true); }

    public static void on(Context c, boolean value) {
        prefs(c).edit().putBoolean(KEY, value).apply();
        on = value;
        if (value) warm();
    }

    /** The setting, Android's Touch sounds, the ringer and a screen reader, read again: at every Home refresh and resume (Haptics.refresh), every menu opening */
    public static void refresh(Context c) {
        on = on(c);
        try { systemOff = Settings.System.getInt(c.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0; }
        catch (RuntimeException e) { systemOff = false; }
        AudioManager am = c.getSystemService(AudioManager.class);
        silent = am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
        AccessibilityManager acc = c.getSystemService(AccessibilityManager.class);
        reader = acc != null && acc.isEnabled() && acc.isTouchExplorationEnabled();
        ready = true;
        if (on && !systemOff && !silent) warm();
    }

    /** The tone of a feedback word (Haptics) */
    static UiTones of(Haptics.Word w) {
        switch (w) {
            case TICK: return UiTones.MOVE;
            case CLICK: return UiTones.SELECT;
            case HEAVY: return UiTones.HEAVY;
            case EDGE: return UiTones.EDGE;
            case DONE: return UiTones.DONE;
            default: return UiTones.REJECT;
        }
    }

    /** Plays t when the setting, Android and the ringer allow it */
    static void play(Context c, UiTones t) {
        if (t == null || c == null) return;
        if (!ready) refresh(c);
        if (!on || systemOff || silent || (reader && t.light())) return;
        AudioTrack track = make(t);
        if (track == null) return;
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.stop();
            track.setPlaybackHeadPosition(0);
            track.play();
        } catch (RuntimeException ignored) { /* a track the system took back: silence */ }
    }

    /** Every tone made ahead, so the first press does not wait for it */
    private static void warm() { for (UiTones t : UiTones.values()) make(t); }

    private static AudioTrack make(UiTones t) {
        AudioTrack track = tracks.get(t);
        if (track != null) return track;
        short[] pcm = t.pcm();
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(UiTones.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
            track.write(pcm, 0, pcm.length);
        } catch (RuntimeException e) {
            android.util.Log.w("peanuts", "sound " + t + ": " + e);
            return null;
        }
        tracks.put(t, track);
        return track;
    }
}
