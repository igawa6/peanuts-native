package io.github.davdxpx.mistel.mncf;

import java.util.Map;

/** The result of converting cheat files: the new document, its text and the report. Immutable. */
public final class Conversion {

    private final Map<String, Object> doc;
    private final String text;
    private final ConversionReport report;

    Conversion(Map<String, Object> doc, String text, ConversionReport report) {
        this.doc = doc;
        this.text = text;
        this.report = report;
    }

    /** Returns the new document as a map of TOML values, unmodifiable. */
    public Map<String, Object> doc() {
        return doc;
    }

    /** Returns the text of the new .mncf file. */
    public String text() {
        return text;
    }

    /** Returns what the converter made of each entry. */
    public ConversionReport report() {
        return report;
    }
}
