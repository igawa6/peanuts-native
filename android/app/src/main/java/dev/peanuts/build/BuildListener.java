package dev.peanuts.build;

import java.io.IOException;

/** Progress of a build back to the game sheet; called on the main thread. step: import | prepare | companion | done; percent: 0..100 over the whole build */
public interface BuildListener {
    void progress(String step, int percent, String detail);

    /**
     * The end: ok, the message for the card's body, and a failure category (one of the constants below; "" when ok). The card uses code to pick one recovery
     * action instead of always offering Retry: a thrown {@link Failure} carries its own code through BuildService/Prepare's steps, anything
     * else falls back to {@link #OTHER}.
     */
    void done(boolean ok, String message, String code);

    // ---- Failure categories (P4: categorized build failures) ----
    /** The base or update file the scanner once found no longer resolves (deleted, an SD card unmounted, …): the sheet's "Pick the file again" */
    String FILE_MISSING = "file_missing";
    /** The dump's version is not one of the profile's builds, and the player did not say "Build anyway" for it: the sheet's version row, not a silent retry */
    String WRONG_VERSION = "wrong_version";
    /** DumpLayout refused the dump for a reason that is not a version mismatch (incomplete, no RomFS, wrong title ID, …): a bad or incomplete dump, not a
     *  missing file (found in review — this used to share FILE_MISSING's "pick the file again", which promises a fix that will not help) */
    String INVALID_DUMP = "invalid_dump";
    /** SpaceEstimate's warn-only pre-flight, or a real ENOSPC/"No space left" I/O error: "Free up space" */
    String OUT_OF_SPACE = "out_of_space";
    /** The packer stopped reporting progress for longer than Prepare's stall timeout: "Resume" (it already picks up where it stopped) */
    String STALLED = "stalled";
    /** The player tapped Stop, or Android paused the job for a reason that will not clear itself without the user (daily limit, app updated): "Resume" */
    String CANCELLED = "cancelled";
    /** A packer tool exited with an error (shaders, rewrite, pack): today's "Retry", with the detail in Details */
    String TOOL_STOPPED = "tool_stopped";
    /** Prepare stopped because shaders come from files the import does not have and no add-on content (DLC) was imported ({@link AutoRelax}): the card offers
     *  "Import again with DLC" (or where to set the DLC folder) and "Try anyway"; nothing is relaxed for the player here */
    String ADDON_MISSING = "addon_missing";
    /** A compressed game file (.nsz, .xcz) that does not check out: damaged, cut off, not the player's keys, or compressed with settings the importer cannot
     *  read ({@link Compressed#failure}, whose words say what to do): the card opens the sheet, with nothing added to the message */
    String FILE_DAMAGED = "file_damaged";
    /** Anything not categorized above: today's "Retry" */
    String OTHER = "other";

    /** A failure that already knows its category, so run()'s catch does not have to pattern-match the message (Prepare/BuildService throw this at the sites that know) */
    class Failure extends IOException {
        public final String code;
        public Failure(String message, String code) { super(message); this.code = code; }
    }
}
