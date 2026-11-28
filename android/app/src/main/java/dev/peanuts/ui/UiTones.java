package dev.peanuts.ui;

/**
 * The app's feedback sounds as numbers (plain Java, so they run on the JVM: android/app/src/jvmtest's UiTonesJvmTest): each a few milliseconds of a sine
 * per note at a soft level, with a quick rise and a fade, the way a console's menus sound. {@link Sounds} plays them. MOVE, TAB, SELECT, OPEN and BACK are
 * the in-game menu's own tones (MenuFeel's, unchanged); the others are new for the rest of the app.
 */
enum UiTones {
    /** The focus moved one step */
    MOVE(new float[] {2093}, 14, 0.10f),
    /** Another tab */
    TAB(new float[] {1568}, 22, 0.12f),
    /** A choice taken: a row, a button, a switch */
    SELECT(new float[] {1047, 1568}, 34, 0.13f),
    /** A menu or a sheet opens */
    OPEN(new float[] {784, 1175}, 40, 0.11f),
    /** A menu closes, or one step back */
    BACK(new float[] {1175, 784}, 34, 0.11f),
    /** A weighty choice: Play, a build, a game's page: three notes up */
    HEAVY(new float[] {659, 988, 1319}, 36, 0.13f),
    /** The focus cannot go further: one low, soft note */
    EDGE(new float[] {523}, 18, 0.07f),
    /** A long job ended well: a chord's notes up */
    DONE(new float[] {1047, 1319, 1568, 2093}, 46, 0.11f),
    /** The app refused, or a job failed: two low notes down */
    REJECT(new float[] {392, 311}, 46, 0.12f);

    static final int RATE = 48000;

    final float[] notes;
    final int ms;
    final float level;

    UiTones(float[] notes, int ms, float level) { this.notes = notes; this.ms = ms; this.level = level; }

    /** Its samples: 16-bit mono at RATE, each note ms long, a 1.5 ms rise and an exponential fade so no note clicks */
    short[] pcm() {
        int per = RATE * ms / 1000, n = per * notes.length;
        short[] out = new short[n];
        for (int k = 0; k < notes.length; k++) {
            for (int i = 0; i < per; i++) {
                double t = (double) i / RATE, attack = Math.min(1.0, i / (RATE * 0.0015)), fade = Math.exp(-5.0 * i / per);
                out[k * per + i] = (short) (Math.sin(2 * Math.PI * notes[k] * t) * attack * fade * level * Short.MAX_VALUE);
            }
        }
        return out;
    }

    /** A light step (MOVE, EDGE): no sound while a screen reader speaks, it has its own */
    boolean light() { return this == MOVE || this == EDGE; }
}
