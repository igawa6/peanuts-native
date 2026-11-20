package dev.nxnative.android;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words of Prepare (the on-device job's card and Home's game cards) for the player: what happened and what to do, in one or two short sentences, with the
 * technical part (file paths, a tool's last [RED] line, exit codes, build IDs) kept apart for the card's Details, which the player can open and copy. Also what a
 * failure offers: Retry when running the step again can help, Try anyway when the job can go on with a check relaxed (with an honest warning), nothing when this app
 * version does not include the game, and an update when this app build itself is broken (its files do not match each other). No Android classes: LauncherJvmTest
 * runs it on the Mac.
 */
public final class PrepareText {   // PEANUTS: Home and Prepare use the same failure summary
    private PrepareText() {}

    /**
     * What a failed job's card offers: run the step again, go on with the check relaxed (Job "try_anyway"), nothing (this app cannot do it), or another app build
     * (this build is broken: no Retry; the release app offers Check for updates)
     */
    static final String FIX_RETRY = "retry", FIX_ANYWAY = "anyway", FIX_NONE = "none", FIX_UPDATE = "update";

    /**
     * The start of vk_android_build.py check_translator's refusal after "[RED] ": the APK's translator library is not the one its own tools expect (a library from
     * another source tree, an embedded source that is not the lock's, a library without --tree-sha). Nothing on the phone can mend it; Gradle's nxPackXlateCheck
     * stops such a build (android/ONDEVICE.md, In the APK). test_android_preparetext.py checks the tool still prints this text
     */
    static final String XLATE_MISMATCH = "translator does not match the lock file: ";

    /** The card's text for an APK whose translator does not match its tools (XLATE_MISMATCH) */
    static final String XLATE_MISMATCH_TEXT = "This app build's shader translator does not match its own tools, so retrying will not help. Install the latest release "
            + "of the app, or report this with the log from Details.";

    /** A failure: text for the card (short, plain), detail for Details (may be long), fix: FIX_* */
    static final class Failure {
        final String text, detail, fix;
        // PEANUTS begin: why, for a failure whose cause the player can mend (CAUSE_*; "" for the others), with its counts: Job.fail keeps them in job.json, where
        // the build reads them (dev.peanuts.build.AutoRelax: such a job is not tried anyway for the player) and the pack's note gets them (dev.peanuts.build.PackNote)
        final String cause;
        final long lacks, of;

        Failure(String text, String detail, String fix, String cause, long lacks, long of) {
            this.text = text; this.detail = detail == null ? "" : detail; this.fix = fix; this.cause = cause; this.lacks = lacks; this.of = of;
        }

        Failure(String text, String detail, String fix) { this(text, detail, fix, "", 0, 0); }
        // PEANUTS end
    }

    /** PEANUTS: a failure's cause (Failure.cause): shaders that come from files the import does not have, in an import without add-on content (addonNotImported) */
    static final String CAUSE_ADDON = "addon_not_imported";

    /** The warning Try anyway shows before it relaxes a check: what may go wrong, plainly */
    static final String ANYWAY_WARNING = "This goes on without the check that stopped it. The game may not start, may crash, or may draw parts wrongly or not at all. "
            + "It has not been tested this way. Your dump and saves are not changed.";

    static String lower(String s) { return s.isEmpty() ? s : s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1); }

    static String capital(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    /**
     * A tool step (step: Job.STEP_TEXT's words) that ended with a non-zero exit code; lastRed: its log's last [RED] line (or null), log: the step's log file. The
     * kinds the tools report most (memory, storage, shaders that did not translate, a dump whose shaders are not the profile's) get a sentence of their own
     */
    static Failure toolStopped(String step, int code, String lastRed, String log) {
        String red = lastRed == null ? "" : lastRed.replaceFirst("^\\[RED\\]\\s*", "").trim();
        String detail = step + ": exit code " + code + (red.isEmpty() ? "" : "\n" + red) + (log == null ? "" : "\nLog: " + log);
        String what = step + " stopped.";
        if (red.startsWith(XLATE_MISMATCH))
            return new Failure(what + " " + XLATE_MISMATCH_TEXT, detail, FIX_UPDATE);
        if (red.contains("MemoryError") || red.contains("out of memory") || code == 137)
            return new Failure(what + " The phone ran out of memory. Close other apps, then tap Retry.", detail, FIX_RETRY);
        if (red.contains("No space left") || red.contains("ENOSPC"))
            return new Failure(what + " The storage is full. Free some space, then tap Retry.", detail, FIX_RETRY);
        if (red.contains("did not build: no library written"))
            return new Failure(what + " Some shaders could not be translated, so no shader library was made. Try anyway makes it without them (those parts may not show).", detail, FIX_ANYWAY);
        if (red.contains("are not in the package"))
            return new Failure(what + " Your dump's shaders are not the ones this app expects for this game version. If you dumped the game again, tap Retry.", detail, FIX_RETRY);
        return new Failure(what + " Tap Retry. If it stops again, Details has the log.", detail, FIX_RETRY);
    }

    /**
     * A step's log read for the reason of a stop: the last [RED] line, or a Python traceback's last line ("KeyError: 'x'") when it comes later. The packer's runner
     * (android/ondevice/dm_pack.c) writes an uncaught exception as a [RED] line of its own; the traceback is read too, so a tool run without it (or whose [RED] line
     * could not be written) still gives its card a reason. Lines as PackService's Tail hands them over: trimmed, one at a time
     */
    static final class StopReason {
        private static final Pattern EXCEPTION = Pattern.compile("^(?:[A-Za-z_][A-Za-z0-9_]*\\.)*[A-Za-z_][A-Za-z0-9_]*(?:Error|Exception|Exit|Interrupt)(?::\\s.*)?$");
        private boolean inTrace;
        volatile String reason;   // read by the step once its Tail thread is done

        /** line: one trimmed log line; true when it is a [RED] line */
        boolean line(String line) {
            if (line.startsWith("[RED] ")) { reason = line; inTrace = false; return true; }
            if (line.startsWith("Traceback (most recent call last):")) inTrace = true;
            else if (inTrace && EXCEPTION.matcher(line).matches()) { reason = line; inTrace = false; }   // frames and code lines before it never match: "File …", "raise …"
            return false;
        }
    }

    /** The job itself failed (an exception in the service): plain words, the exception in Details */
    static Failure crashed(String step, String exception) {
        return new Failure(step + " stopped because of an error in the app. Tap Retry. If it stops again, Details has the error.", exception, FIX_RETRY);
    }

    /** PEANUTS: repeated service restarts can follow a crash, memory pressure, or an unreported exit. */
    static Failure killed(String step, int times) {
        return new Failure(step + " was stopped by Android " + times + " times. Check the build log, then tap Retry.",
                "If Android reported low memory, close other apps before retrying.", FIX_RETRY);
    }

    /** The split's shaders differ from the profile's list: a dump of another version, modified or incomplete; Try anyway builds what the profile's list finds in it */
    static Failure shadersDiffer(String name, String version, String detail) {
        return new Failure("Your dump's shaders are not the ones this app knows for " + name + " (" + version + "). The dump may be incomplete or modified: dump the game again "
                + "and tap Retry, or Try anyway.", detail, FIX_ANYWAY);
    }

    // PEANUTS begin: a split that lacks some of the profile's shaders says how many and names one, and tells add-on content (DLC) that was not imported from a dump
    // of another version (2026-11-15). PackService.splitMatchesProfile gathers the evidence. No game is known that needs the add-on words: the case they were
    // written for (Mario Kart 8 Deluxe, 135 of 29,785) was the shaders of another version in the profile's shader_versions, which is no failure at all now
    // (ShaderLibs.otherVersionKeys, checked first). They are said only when nothing else is known to explain the missing shaders (ShaderLibs.addonCase, the rule of
    // scripts/pack_android.py lacking_shaders), as one possible reason and no more, and are untested against a real game
    private static String count(long n) { return String.format(Locale.getDefault(), "%,d", n); }

    /** A RomFS path for the card's main text, which carries no slash: "/Course/Gu_Cloud/course.ptcl" → "Course › Gu_Cloud › course.ptcl" */
    static String shown(String romfsPath) { return romfsPath.replaceFirst("^/+", "").replace("/", " › "); }

    /**
     * absent of the profile's total shaders are missing, every one of them from a file (file: one of them, a RomFS path) that the import does not have, and the
     * import has no add-on content. nup: the game is a .nup file (the app's own import, or the PC builder's), which has to be made again with the DLC (the game
     * sheet's Import again); else a dump folder. No profile says whether its game has add-on content, so it is named as one possible reason, not as a fact. The
     * failure carries its cause and counts (CAUSE_ADDON): the build stops with this card and lets the player choose, where it presses Try anyway by itself for
     * the other failures
     */
    static Failure addonNotImported(String name, long absent, long total, String file, boolean nup, String detail) {
        String what = nup ? "import" : "dump";
        return new Failure(count(absent) + " of the " + count(total) + " shaders this app knows for " + name + " are missing, all of them from files your " + what
                + " does not have (for example " + shown(file) + "). The " + what + " is incomplete; one possible reason is that the game's add-on content (DLC) was not "
                + "part of the " + what + ": if you have DLC for this game, " + (nup ? "set the DLC folder in Settings (Library), then tap Import again in the game's sheet"
                : "dump the game again with its DLC and tap Retry") + ". Try anyway builds without them: the game runs, and what those files hold is drawn without "
                + "its materials.", detail, FIX_ANYWAY, CAUSE_ADDON, absent, total);
    }

    /** shadersDiffer with what is missing: missing of the profile's total shaders, example: a RomFS path of one, or its key */
    static Failure shadersDiffer(String name, String version, long missing, long total, String example, String detail) {
        return new Failure("Your dump's shaders are not the ones this app knows for " + name + " (" + version + "): " + count(missing) + " of " + count(total)
                + " are missing (for example " + (example.startsWith("/") ? "from " + shown(example) : example) + "). The dump may be incomplete or modified: dump the game "
                + "again and tap Retry, or Try anyway.", detail, FIX_ANYWAY);
    }

    /** shadersDiffer when the dump could not be asked what it lacks: the same words, and the add-on content named as a possible reason */
    static Failure shadersDifferUnread(String name, String version, String detail) {
        return new Failure(shadersDiffer(name, version, detail).text + " When the game has add-on content (DLC), it may not have been part of the import.", detail, FIX_ANYWAY);
    }

    /** The split read no shader at all, of a game whose profile does not say it has none (ShaderLibs.noNvnShaders): no pack is made, since it could not draw */
    static Failure noShadersRead(String name, String detail) {
        return new Failure("The app found no shaders in your dump of " + name + ", so the game could not draw and no pack was made. Either the dump is incomplete, or "
                + "this app version cannot read this game's shaders yet: dump the game again and tap Retry, or share the log (Details).", detail, FIX_RETRY);
    }
    // PEANUTS end

    /** A job for a game this app version does not include (Profile.missing): nothing to retry */
    static Failure notIncluded(String sentence) { return new Failure(sentence, "", FIX_NONE); }

    /**
     * The card's main text for a failed job. A job of this version keeps its short text in "error" (structured) and the rest in "error_detail"; an older job kept
     * everything in "error": then the card says only where it stopped, and the old text goes to Details (failedDetail)
     */
    static String failedText(String step, String error, boolean structured) {
        if (structured && !error.isEmpty()) return error;
        return "Preparing stopped while " + lower(step) + ". Tap Retry. Details says why.";
    }

    // PEANUTS begin: show the recorded tool error beside the failed step on both build cards.
    public static String failureSummary(String step, String error, String detail) {
        String summary = error.split("\n", 2)[0].trim();
        String generic = " Tap Retry. If it stops again, Details has the log.";
        if (summary.endsWith(generic)) summary = summary.substring(0, summary.length() - generic.length());
        if (summary.isEmpty()) summary = step + " stopped.";
        else if (!summary.startsWith(step)) summary = step + " · " + summary;
        String[] lines = detail.trim().split("\n");
        String reason = lines[0].trim();
        int exit = reason.indexOf(": exit code ");
        if (exit > 0) {
            // toolStopped stores the exit status, the last tool error (if any), then the log location.
            reason = capital(reason.substring(exit + 2));
            if (lines.length > 1 && !lines[1].startsWith("Log: ")) reason = lines[1].trim();
        }
        if (reason.isEmpty() || reason.startsWith("Log: ") || summary.contains(reason)) return summary;
        if (reason.length() > 300) reason = reason.substring(0, 299) + "…";
        return summary.endsWith(".") ? summary + " " + reason : summary + " · " + reason;
    }
    // PEANUTS end

    static String failedDetail(String error, String detail, boolean structured) { return structured ? detail : error; }

    /**
     * The line under the progress bar: "37 %", then the shaders when the job knows how many there are ("21,548 shaders left" during the step, "1,234 of 22,852
     * shaders" otherwise), the time left and the unchanged ones a shader update keeps. Never "0 of 0": without a total the shaders are left out
     */
    static String progressLine(long percent, boolean during, long done, long total, String eta, long kept) {
        StringBuilder s = new StringBuilder(String.format(Locale.getDefault(), "%d %%", percent));
        if (total > 0) {
            if (during) s.append(String.format(Locale.getDefault(), "  ·  %,d shaders left", Math.max(0, total - done)));
            else if (done > 0) s.append(String.format(Locale.getDefault(), "  ·  %,d of %,d shaders", done, total));
            else s.append(String.format(Locale.getDefault(), "  ·  %,d shaders to make", total));
        } else if (kept > 0) {
            s.append("  ·  shaders up to date: nothing to translate");
        }
        if (during && eta != null && !eta.isEmpty()) s.append("  ·  ").append(eta);
        if (kept > 0 && total > 0) s.append(String.format(Locale.getDefault(), "  ·  the other %,d are reused", kept));
        return s.toString();
    }

    /**
     * A step and what its tool says it is doing now (a "stage: <text>" line), with a count when there is one: "Finding the shaders: reading the game's program",
     * "Rewriting the game's code: rewriting main (1 of 3)". The tools write the text in lower case after the colon
     */
    static String withNote(String step, String note, String count) {
        return step + (note == null || note.isEmpty() ? "" : ": " + note) + (count == null || count.isEmpty() ? "" : " · " + count);
    }

    /**
     * " · still working, last update 3 min ago" after a minute or more without a line from the running step's tool, else "": a phase that prints nothing for long
     * (a large file read, a storage flush) shows that the app has not stopped watching it, and how long it has been
     */
    static String quietText(long seconds) {
        if (seconds < 60) return "";
        long m = seconds / 60;
        return m < 60 ? " · still working, last update " + m + " min ago"
                : String.format(Locale.getDefault(), " · still working, last update %d h %02d min ago", m / 60, m % 60);
    }

    /**
     * The shaders step while a delta update writes out the shaders it keeps from the older library (vk_android_build.py's "keep" stage, before it translates the
     * others): "Keeping the shaders already translated: 12,000 of 42,800"
     */
    static String keepText(long done, long total) {
        return "Keeping the shaders already translated" + (total > 0 ? String.format(Locale.getDefault(), ": %,d of %,d", Math.min(done, total), total) : "");
    }

    /**
     * A step and its count from the tool's progress lines (Job.stepText; not the shaders step's, which counts shaders). The split counts the dump's files it
     * reads, not shaders (shader_split.py's "progress: <files done>/<files>"), so its count says so: "Finding the shaders: 1,203 of 1,890 files". part: the
     * tool's "<n>%" inside a large file, added as "(40 % of a large file)"
     */
    /** A step's count alone, for after its stage text (withNote): "203 of 1,442 files", "40 % of a large file"; "" while it has none */
    static String countText(boolean files, long done, long total, String part) {
        String c = total > 0 ? String.format(Locale.getDefault(), files ? "%,d of %,d " + (total == 1 ? "file" : "files") : "%,d / %,d", done, total) : "";
        String p = part != null && part.matches("\\d+%") ? part.replace("%", " %") + " of a large file" : "";
        return c.isEmpty() ? p : p.isEmpty() ? c : c + " (" + p + ")";
    }

    static String stepCountText(String step, boolean files, long done, long total, String part) {
        String count = files ? (total > 0 ? String.format(Locale.getDefault(), ": %,d of %,d %s", done, total, total == 1 ? "file" : "files") : "")
                : total > 1 ? String.format(Locale.getDefault(), " %,d / %,d", done, total) : total == 1 || done == 0 ? "" : String.format(Locale.getDefault(), " %,d", done);
        return step + count + (part != null && part.matches("\\d+%") ? " (" + part.replace("%", " %") + " of a large file)" : "");
    }

    /**
     * Why a game card needs preparing, in a few words for the card (the full reason goes to its Details): no pack yet, a pack that cannot be read, a pack whose code
     * was left unfinished, or a Prepare that stopped part way (a job beside the dump that is not done)
     */
    static String needsPreparing(boolean packThere, boolean unfinished, boolean jobStopped) {
        if (jobStopped) return "Preparing was not finished. Tap Continue to go on where it stopped.";
        if (unfinished) return "The pack's game code was left unfinished. Prepare finishes it (a few minutes; shaders are kept).";
        if (packThere) return "The pack cannot be read. Prepare makes it again.";
        return "No pack yet. Prepare makes one from your dump.";
    }

    /** vk_android_build.py open_packs under Try anyway (DMSH_VK_REQUIRE_ALL=0): "[RED] <n> of the <m> shaders to build are not in the package …" */
    private static final Pattern MISSING = Pattern.compile("^\\[RED\\] (\\d+) of the (\\d+) shaders to build are not in the package .*library is written without them");

    /** {n, m} from the shaders step's line naming the shaders this game version lacks (Try anyway), else null */
    static long[] missingShaders(String line) {
        Matcher m = MISSING.matcher(line);
        return m.find() ? new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))} : null;
    }

    /** One sentence for the player after a Try anyway whose game version lacks some of the profile's shaders; supported: Profile.supportedShort */
    static String missingShadersText(long n, long of, String supported) {
        return String.format(Locale.US, "%,d of its %,d shaders are not in this game version, so some things may not draw. All of them are in %s.", n, of, supported);
    }
}
