package io.github.davdxpx.mistel.mncf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A patch file as {@link Patches} reads it: its format, its patches and what else it says. Immutable. */
public final class PatchFile {

    /** A line of an IPSwitch file, by its number counted from 1 and its text. Immutable. */
    public static final class Line {
        private final int line;
        private final String text;

        /** Makes a line of its number and text. */
        public Line(int line, String text) {
            this.line = line;
            this.text = text;
        }

        /** Returns the number of the line, counted from 1. */
        public int line() {
            return line;
        }

        /** Returns the text. */
        public String text() {
            return text;
        }

        @java.lang.Override
        public boolean equals(Object o) {
            return o instanceof Line && ((Line) o).line == line && ((Line) o).text.equals(text);
        }

        @java.lang.Override
        public int hashCode() {
            return line * 31 + text.hashCode();
        }

        @java.lang.Override
        public String toString() {
            return "(" + line + ", " + Py.repr(text) + ")";
        }
    }

    private final String format;
    private final List<Patch> patches;
    private final String buildId;
    private final List<Line> notes;
    private final List<Line> comments;

    PatchFile(String format, List<Patch> patches, String buildId, List<Line> notes, List<Line> comments) {
        this.format = format;
        this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
        this.buildId = buildId;
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        this.comments = Collections.unmodifiableList(new ArrayList<>(comments));
    }

    /** Returns "ips", "ips32" or "pchtxt". */
    public String format() {
        return format;
    }

    /** Returns the patches: one for an IPS file, each patch of an IPSwitch file in order. */
    public List<Patch> patches() {
        return patches;
    }

    /** Returns the build ID the file names itself (@nsobid-), in upper-case hexadecimal, or null. */
    public String buildId() {
        return buildId;
    }

    /** Returns forced comments (#) and the lines that are no patch, with what they say. */
    public List<Line> notes() {
        return notes;
    }

    /** Returns the other comments, for credits. */
    public List<Line> comments() {
        return comments;
    }
}
