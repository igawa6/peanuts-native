package dev.peanuts.ui;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.view.View;

/**
 * How the in-game menus feel (dev.peanuts.ingame: the game menu, the quick menu, the bottom screen's menu), as a console's menus do: a fine tick of the
 * motor for each step of the focus and a firmer one for a choice ({@link Haptics}, the launcher's words, its gaps and its respect for the system's touch
 * feedback, battery saver and heat), and, when the player turns them on, soft menu sounds made here (a few milliseconds of a sine, no files). Two settings
 * in the menu's own settings: Vibrate on each step (on) and Menu sounds (off), kept in the game's prefs file (its process writes it). Main thread only.
 */
public final class MenuFeel {
    private MenuFeel() {}

    private static boolean ready, haptics = true, sounds;
    private static long lastMove, lastOther;   // a step's tick waits for neither a held direction nor the firmer word of the same press

    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("peanuts_panel", Context.MODE_PRIVATE); }

    public static boolean haptics(Context c) { return prefs(c).getBoolean("menu_haptics", true); }
    public static boolean sounds(Context c) { return prefs(c).getBoolean("menu_sounds", false); }
    public static void haptics(Context c, boolean on) { prefs(c).edit().putBoolean("menu_haptics", on).apply(); haptics = on; }
    public static void sounds(Context c, boolean on) { prefs(c).edit().putBoolean("menu_sounds", on).apply(); sounds = on; if (on) Tone.warm(); }

    /** A menu opens: the settings read again (the app's haptics row, the system's touch feedback, battery saver, heat) */
    public static void refresh(Context c) {
        Haptics.refresh(c);
        haptics = haptics(c);
        sounds = sounds(c);
        ready = true;
        if (sounds) Tone.warm();
    }

    private static void ready(View v) { if (!ready && v != null) refresh(v.getContext()); }

    /** The focus moved one step */
    public static void move(View v) {
        ready(v);
        long now = SystemClock.uptimeMillis();
        boolean held = now - lastMove < 90;   // a held direction: no buzz and no rattle at the key repeat rate
        lastMove = now;
        if (held || now - lastOther < 150) return;   // the focus a tab, an opening or a press put somewhere: that press has its word already
        if (haptics) Haptics.play(v, Haptics.Word.TICK);
        if (sounds) Tone.MOVE.play();
    }

    /** The focus cannot go further that way */
    public static void edge(View v) { ready(v); if (haptics) Haptics.play(v, Haptics.Word.EDGE); }

    /** A row pressed, a choice or a switch taken */
    public static void select(View v) { ready(v); lastOther = SystemClock.uptimeMillis(); if (haptics) Haptics.play(v, Haptics.Word.CLICK); if (sounds) Tone.SELECT.play(); }

    /** Another tab (the rail, L and R) */
    public static void tab(View v) { ready(v); lastOther = SystemClock.uptimeMillis(); if (haptics) Haptics.play(v, Haptics.Word.TICK); if (sounds) Tone.TAB.play(); }

    /** A menu opens */
    public static void open(View v) { if (v != null) refresh(v.getContext()); lastOther = SystemClock.uptimeMillis(); if (sounds) Tone.OPEN.play(); }

    /** A menu closes, or B goes one step back */
    public static void back(View v) { ready(v); if (sounds) Tone.BACK.play(); }

    /**
     * The menu sounds: each a few milliseconds of a sine at a soft level with a quick fade, made once into its own static AudioTrack (a sonification, so it
     * follows the system's own sound settings for those). Nothing is loaded until the setting is on.
     */
    private enum Tone {
        MOVE(new float[] {2093}, 14, 0.10f),
        TAB(new float[] {1568}, 22, 0.12f),
        SELECT(new float[] {1047, 1568}, 34, 0.13f),
        OPEN(new float[] {784, 1175}, 40, 0.11f),
        BACK(new float[] {1175, 784}, 34, 0.11f);

        private static final int RATE = 48000;
        private final float[] notes;
        private final int ms;
        private final float level;
        private AudioTrack track;

        Tone(float[] notes, int ms, float level) { this.notes = notes; this.ms = ms; this.level = level; }

        static void warm() { for (Tone t : values()) t.make(); }

        private void make() {
            if (track != null) return;
            int per = RATE * ms / 1000, n = per * notes.length;
            short[] pcm = new short[n];
            for (int k = 0; k < notes.length; k++) {
                for (int i = 0; i < per; i++) {
                    double t = (double) i / RATE, attack = Math.min(1.0, i / (RATE * 0.0015)), fade = Math.exp(-5.0 * i / per);
                    pcm[k * per + i] = (short) (Math.sin(2 * Math.PI * notes[k] * t) * attack * fade * level * Short.MAX_VALUE);
                }
            }
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build();
                track.write(pcm, 0, n);
            } catch (RuntimeException e) {
                android.util.Log.w("peanuts", "menu sound " + this + ": " + e);
                track = null;
            }
        }

        void play() {
            make();
            AudioTrack t = track;
            if (t == null) return;
            try {
                if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) t.stop();
                t.setPlaybackHeadPosition(0);
                t.play();
            } catch (RuntimeException ignored) { /* a track the system took back: silence */ }
        }
    }
}
