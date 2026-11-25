package dev.nxnative.android;

import java.util.Locale;

/**
 * Prepare's words for the player (PrepareText): short main texts with no file path or tool output in them (those go to Details), the right offer after a failure
 * (Retry, Try anyway, nothing), no "0 of 0" in the progress line, and an older job's long error moved to Details. Also Profile.missing's one sentence. Run by
 * dm/tools/tests/test_android_preparetext.py, which compiles this with PrepareText.java, Profile.java and a stub BuildConfig.
 */
public final class PrepareTextJvmTest {
    static int n;

    static void check(boolean ok, String what) {
        if (!ok) { System.out.println("FAIL: " + what); System.exit(1); }
        n++;
    }

    /** A main text the player reads: no path, no exit code, no [RED], at most three sentences */
    static void plain(String s, String what) {
        check(!s.contains("/") && !s.contains("[RED]") && !s.contains("exit code") && !s.contains("log:"), what + ": no technical part in the main text: " + s);
        check(s.split("\\. ").length <= 3, what + ": short: " + s);
    }

    public static void main(String[] a) {
        Locale.setDefault(Locale.US);
        String log = "/storage/emulated/0/Switch2/0400A3A01EE4C000-work/ondevice/logs/shaders.log";

        PrepareText.Failure f = PrepareText.toolStopped("Translating shaders", 1, "[RED] 12 of 8157 shaders did not build: no library written (DMSH_VK_REQUIRE_ALL)", log);
        plain(f.text, "shaders that did not build");
        check(f.fix.equals(PrepareText.FIX_ANYWAY) && f.detail.contains(log) && f.detail.contains("12 of 8157") && f.detail.contains("ended with code 1"), "Try anyway, the tool's line in Details: " + f.detail);   // PEANUTS: build text in the player's words (0.1.8)

        f = PrepareText.toolStopped("Finding the shaders", 1, "[RED] MemoryError in 0x38f972af.fdata", log);
        plain(f.text, "memory");
        check(f.fix.equals(PrepareText.FIX_RETRY) && f.text.contains("ran out of memory") && f.text.contains("Retry"), "memory: close apps, Retry: " + f.text);

        f = PrepareText.toolStopped("Translating shaders", 1, "[RED] 3 of the 8157 shaders to build are not in the package /x/pkg: it is not a split", log);
        plain(f.text, "not in the package");
        check(f.fix.equals(PrepareText.FIX_RETRY) && f.text.contains("dumped the game again"), "a dump of other shaders: dump again: " + f.text);

        // Try anyway on another game version: vk_android_build.py open_packs' line (DMSH_VK_REQUIRE_ALL=0), and the stop without it is not taken for it
        long[] miss = PrepareText.missingShaders("[RED] 120 of the 8161 shaders to build are not in the package /x/pkg (a game version the profile's shader_map.tsv was not made from): "
                + "the library is written without them (DMSH_VK_REQUIRE_ALL=0). abc: not in the package");
        check(miss != null && miss[0] == 120 && miss[1] == 8161, "missing shaders counted");
        String keep = PrepareText.keepText(12000, 42800);
        check(keep.equals(String.format("Keeping the shaders made before: %,d of %,d", 12000, 42800)), "a delta's kept shaders counted: " + keep);   // PEANUTS: build text in the player's words (0.1.8)
        check(PrepareText.keepText(0, 0).equals("Keeping the shaders made before"), "no count before the first line: " + PrepareText.keepText(0, 0));
        check(PrepareText.withNote("Finding the shaders", "reading the game's program", "").equals("Finding the shaders: reading the game's program"), "a stage line on the card");
        check(PrepareText.withNote("Finishing the pack", "", "").equals("Finishing the pack"), "no stage: the step alone");
        check(PrepareText.withNote("Finding the shaders", "reading 1,442 files", "203 of 1,442 files").equals("Finding the shaders: reading 1,442 files, 203 of 1,442 files"), "stage and count");
        check(PrepareText.countText(true, 203, 1442, null).equals(String.format("%,d of %,d files", 203, 1442)), "a file count alone: " + PrepareText.countText(true, 203, 1442, null));
        check(PrepareText.countText(true, 1, 5, "40%").equals("1 of 5 files, 40 % of a large file"), "inside a large file: " + PrepareText.countText(true, 1, 5, "40%"));
        check(PrepareText.countText(false, 0, 0, null).isEmpty(), "no count yet");
        check(PrepareText.quietText(59).isEmpty(), "under a minute: nothing");
        check(PrepareText.quietText(180).equals(", still working (no news for 3 min)"), "quiet for 3 min: " + PrepareText.quietText(180));
        check(PrepareText.quietText(3900).equals(String.format(", still working (no news for %d h %02d min)", 1, 5)), "quiet for over an hour: " + PrepareText.quietText(3900));
        check(PrepareText.missingShaders("[RED] 3 of the 8157 shaders to build are not in the package /x/pkg: it is not a split") == null, "the stop is not a count");
        String mt = PrepareText.missingShadersText(120, 8161, "update 655360");
        plain(mt, "missing shaders");
        check(mt.contains("120 of its 8,161") && mt.endsWith("All of them are in update 655360."), "missing shaders text: " + mt);

        // An APK whose translator library is not the one its own tools expect (2026-11-10: a stage made before a translator change): vk_android_build.py check_translator's
        // lines, the last one the reason. Retry could never succeed: its own text, no Retry (FIX_UPDATE); every form of the refusal (tree, embedded source, no
        // --tree-sha, the program form's dlls) is the same kind
        String[] stale = {"[RED] translator does not match the lock file: translator library: embedded source c55667af1b257a46 / lock a50d55af6f34ac59 / in repo a50d55af6f34ac59",
                "[RED] translator does not match the lock file: translator library: built from source tree 0295868e4589b4fa, this tree is e67cccd60c5f19ae (dmsh_xlate_android/build.sh builds it again)"};
        PrepareText.StopReason xr = new PrepareText.StopReason();
        for (String l : stale) xr.line(l);
        check(stale[1].equals(xr.reason), "the last refusal line is the reason: " + xr.reason);
        for (String red : new String[] {xr.reason, stale[0],
                "[RED] translator does not match the lock file: translator library: built from source tree unknown (no --tree-sha: exit code 1, a library from before it), this tree is e67cccd60c5f19ae (dmsh_xlate_android/build.sh builds it again)",
                "[RED] translator does not match the lock file: dmsh_xlate.dll: lock 0123456789abcdef / actual fedcba9876543210"}) {
            f = PrepareText.toolStopped("Translating shaders", 1, red, log);
            plain(f.text, "a translator that does not match its tools");
            check(f.fix.equals(PrepareText.FIX_UPDATE), "app build mismatch: its own fix, not Retry: " + f.fix + " for " + red);
            check(f.text.equals("Translating shaders stopped. This app version's shader maker does not match the rest of the app, so Retry will not help. "   // PEANUTS: build text in the player's words (0.1.8)
                    + "Install the latest release of the app, or share the build log with a bug report."), "the mismatch text: " + f.text);
            check(!f.text.contains("Tap Retry") && !f.text.contains("Try anyway"), "no Retry offered: " + f.text);
            check(f.detail.contains(red.substring(6)) && f.detail.endsWith("Log: " + log), "the tool's line and the log in Details: " + f.detail);
        }
        f = PrepareText.toolStopped("Translating shaders", 1, "[RED] shader 1234: translator does not match the lock file: a phrase inside another line", log);
        check(f.fix.equals(PrepareText.FIX_RETRY), "only the refusal's own line, at its start");

        f = PrepareText.toolStopped("Rewriting the game code", 2, null, log);
        plain(f.text, "any other stop");
        check(f.fix.equals(PrepareText.FIX_RETRY) && f.text.startsWith("Rewriting the game code stopped.") && f.detail.endsWith("Log: " + log), "any other stop: Retry, Details has the log: " + f.detail);

        // A tool that stops on an uncaught exception: the card had "Finding the shaders: exit code 1" and no reason (a player's split of Ocarina of Time from a .nup).
        // The runner's [RED] line (dm_pack.c) is the reason; without one, the traceback's last line; an earlier red line gives way to a later traceback
        String[] traced = {"targeted split: 1,442 of 186,583 RomFS files hold the shaders", "[RED] /Shader/a.bfsha: bad header (its shaders are left out)",
                "progress: 16/1442", "Traceback (most recent call last):", "File \"/x/repo/dm/gfx/tools/shader_split.py\", line 3150, in main",
                "want, why = profile_files(files, main_v)", "~~~~~~~~~~~~~^^^^^^^^^^^^^^^^", "raise KeyError(s)", "KeyError: 'Shader/b.bfsha'"};
        PrepareText.StopReason sr = new PrepareText.StopReason();
        for (String l : traced) sr.line(l);
        check("KeyError: 'Shader/b.bfsha'".equals(sr.reason), "the traceback's last line, after an earlier red line: " + sr.reason);
        sr.line("[RED] KeyError: 'Shader/b.bfsha' (shader_split.py:3150)");
        check("[RED] KeyError: 'Shader/b.bfsha' (shader_split.py:3150)".equals(sr.reason), "the runner's red line: " + sr.reason);
        f = PrepareText.toolStopped("Finding the shaders", 1, sr.reason, log);
        plain(f.text, "an uncaught exception");
        check(f.fix.equals(PrepareText.FIX_RETRY) && f.detail.startsWith("Finding the shaders ended with code 1.\nLast error: KeyError: 'Shader/b.bfsha' (shader_split.py:3150)\nLog: "), "its reason in Details: " + f.detail);   // PEANUTS: build text in the player's words (0.1.8)
        sr = new PrepareText.StopReason();
        for (String l : new String[] {"Traceback (most recent call last):", "File \"t.py\", line 1, in <module>", "MemoryError"}) sr.line(l);
        f = PrepareText.toolStopped("Finding the shaders", 1, sr.reason, log);
        check(f.text.contains("ran out of memory"), "a MemoryError traceback: the memory text: " + f.text);
        sr = new PrepareText.StopReason();
        for (String l : new String[] {"KeyError: 'x'", "shaders: 42848 (compute 4125)", "SystemExit"}) sr.line(l);
        check(sr.reason == null, "no traceback, no reason: " + sr.reason);
        check(sr.line("[RED] the dump has no RomFS") && !sr.line("progress: 1/2"), "red lines are said so (the Tail's missing-shader count)");

        f = PrepareText.crashed("Translating shaders", "java.io.IOException: /data/user/0/x: EACCES");
        plain(f.text, "an exception");
        check(f.detail.contains("EACCES") && f.fix.equals(PrepareText.FIX_RETRY), "the exception in Details");

        f = PrepareText.killed("Rewriting the game code", 2);   // PEANUTS
        plain(f.text, "killed");
        check(f.text.contains("2 times") && !f.text.contains("memory") && f.fix.equals(PrepareText.FIX_RETRY), "killed: " + f.text);

        f = PrepareText.shadersDiffer("Pokemon Pokopia", "update 655360", "The split's shader_map.tsv differs from the app's dm/games/0400A3A01EE4C000/shader_map.tsv.");
        plain(f.text, "shaders differ");
        check(f.fix.equals(PrepareText.FIX_ANYWAY) && f.text.contains("Try anyway"), "shaders differ: Try anyway");

        // PEANUTS begin: what is missing is counted and named; shaders from files the import does not have, with no add-on content in it, point at the DLC
        f = PrepareText.addonNotImported("Some Game", 12, 3400, "/Course/Extra_Town/course.ptcl", true, "detail");
        plain(f.text, "add-on content not imported");
        check(f.text.startsWith("12 of the 3,400 shaders this app knows for Some Game are missing, all of them from files that are not in your game files, for example "
                + "Course › Extra_Town › course.ptcl. One possible reason is DLC that was not part of the import: if you have DLC for this game, set the DLC folder "
                + "in Settings under Library, then tap Import again in the game's sheet"), "add-on content: " + f.text);
        check(!f.text.contains("usually"), "add-on content is one possible reason, not the usual one: " + f.text);
        check(!f.text.contains("delete the game's .nup"), "no file to delete by hand: Import again replaces it");
        check(f.cause.equals(PrepareText.CAUSE_ADDON) && f.lacks == 12 && f.of == 3400, "add-on content: the cause and its counts, for the build to stop and ask");
        check(f.fix.equals(PrepareText.FIX_ANYWAY) && f.text.contains("Try anyway builds without them") && !f.text.contains("dump the game again"), "add-on content: " + f.text);
        f = PrepareText.addonNotImported("Some Game", 12, 3400, "/Course/Extra_Town/course.ptcl", false, "detail");
        plain(f.text, "add-on content not in a dump folder");
        check(f.text.contains("files that are not in your game files") && f.text.contains("One possible reason is DLC that was not part of the dump")
                && f.text.contains("dump the game again with its DLC and tap Retry") && !f.text.contains(".nup"), "a dump folder: " + f.text);
        check(f.cause.equals(PrepareText.CAUSE_ADDON), "a dump folder: the same cause");
        f = PrepareText.shadersDiffer("Pokemon Pokopia", "version 1.2.0", 7, 131426, "/shader/a.bfsha", "detail");
        plain(f.text, "shaders differ, counted");
        check(f.text.startsWith("7 of the 131,426 shaders this app knows for Pokemon Pokopia, version 1.2.0, are not in your game files, for example one from shader › a.bfsha. "
                + "The game files may be incomplete or changed") && !f.text.contains("DLC"), "shaders differ, counted: " + f.text);
        String byKey = PrepareText.shadersDiffer("X", "", 1, 2, "00000000000000aa", "").text;
        check(!byKey.contains("00000000000000aa") && byKey.startsWith("1 of the 2 shaders this app knows for X are not in your game files."), "a shader without a file: its key stays in Details: " + byKey);
        check(!PrepareText.shadersDiffer("Pokemon Pokopia", "", "detail").text.contains("update"), "no raw version number on the main line");
        f = PrepareText.shadersDifferUnread("Pokemon Pokopia", "version 1.2.0", "detail");
        plain(f.text, "shaders differ, the dump not read");
        check(f.text.startsWith(PrepareText.shadersDiffer("Pokemon Pokopia", "version 1.2.0", "detail").text) && f.text.endsWith("If you have DLC for this game, it may be missing from the game files.")
                && f.fix.equals(PrepareText.FIX_ANYWAY), "the dump not read: " + f.text);
        check(f.cause.isEmpty() && PrepareText.shadersDiffer("X", "base game", 1, 2, "/a", "").cause.isEmpty() && PrepareText.crashed("Split", "x").cause.isEmpty(),
                "only add-on content that was not imported carries a cause: the other failures are tried anyway as before");
        f = PrepareText.noShadersRead("Some Game", "detail");
        plain(f.text, "no shader read");
        check(f.text.startsWith("The app found no shaders in the game files of Some Game") && f.fix.equals(PrepareText.FIX_RETRY) && f.cause.isEmpty() && !f.text.contains("Try anyway"),
                "no shader read: no pack, and no Try anyway: " + f.text);
        // PEANUTS end

        // The trigger: a job of a game this APK does not include gets one sentence and no Retry
        String missing = Profile.missing("0400A3A01EE4C000", "Pokemon Pokopia");
        check(missing.equals("This app version does not include Pokemon Pokopia. Install an app version that includes it."), "Profile.missing: " + missing);
        check(Profile.missing("0400A3A01EE4C000", null).startsWith("This app version does not include this game, 0400A3A01EE4C000."), "Profile.missing without a name: its title ID is all there is to name it");
        check(Profile.missing("040074A01BF12000", "Donkey Kong Bananza") == null, "a game the APK has");
        f = PrepareText.notIncluded(missing);
        check(f.fix.equals(PrepareText.FIX_NONE) && f.text.equals(missing), "not included: nothing to retry");

        // An older job (no "fix"): its long error goes to Details, the card says where it stopped
        String old = "cannot compare the dump's shaders with the app's list: /data/user/0/dev.nxnative.android/files/pack/d8f39dfec5d562c3/repo/dm/games/0400A3A01EE4C000/shader_map.tsv";
        String text = PrepareText.failedText("Translating shaders", old, false);
        plain(text, "an older job's card");
        check(text.equals("The build stopped while translating shaders. Tap Retry. Details says why."), "older job: " + text);   // PEANUTS: build text in the player's words (0.1.8)
        check(PrepareText.failedDetail(old, "", false).equals(old), "older job: its error in Details");
        check(PrepareText.failedText("Translating shaders", "Some text.", true).equals("Some text.") && PrepareText.failedDetail("Some text.", "d", true).equals("d"), "a job of this version");

        // PEANUTS begin: the failure card receives the fields persisted in job.json.
        String step = "Finding the shaders";
        f = PrepareText.toolStopped(step, 1, "[RED] ValueError: invalid shader archive", log);
        // the card's main line says what stopped and what to do; the tool's own line is in Details (and the build log), never on the card (2026-11-24)
        String said = PrepareText.failureSummary(step, f.text, f.detail);
        check(said.equals(step + " stopped on an unexpected error. " + PrepareText.RETRY_SHARE) && f.detail.contains("ValueError: invalid shader archive"), "an exception: plain on the card, in Details: " + said);
        plain(said, "an exception's card");
        f = PrepareText.toolStopped(step, 9, null, log);
        check(PrepareText.failureSummary(step, f.text, f.detail).equals(step + " stopped. " + PrepareText.RETRY_SHARE), "no reason in the log: " + PrepareText.failureSummary(step, f.text, f.detail));
        f = PrepareText.toolStopped(step, 1, "[RED] ENOSPC while writing", log);
        String full = PrepareText.failureSummary(step, f.text, f.detail);
        check(full.contains("Free some space") && !full.contains("ENOSPC") && f.detail.contains("ENOSPC while writing"), "the recovery action on the card, the tool's words in Details: " + full);
        f = PrepareText.toolStopped(step, -9, "Killed", log);
        check(f.text.contains("Android closed it") && !f.text.contains("memory"), "killed: Android closed it, no claim of why: " + f.text);
        check(PrepareText.failureSummary(step, "Legacy error", "").equals("Legacy error"), "old job without separate details");
        check(PrepareText.failureSummary(step, "", "").equals(step + " stopped. " + PrepareText.RETRY_SHARE), "empty failure");
        // PEANUTS end

        // The progress line never says "0 of 0"
        check(PrepareText.progressLine(18, false, 0, 0, "", 0).equals("18 %"), "no total: no shader count: " + PrepareText.progressLine(18, false, 0, 0, "", 0));
        check(PrepareText.progressLine(5, false, 0, 22852, "", 0).equals("5 %, 22,852 shaders to make"), PrepareText.progressLine(5, false, 0, 22852, "", 0));
        check(PrepareText.progressLine(37, true, 1304, 22852, "about 35 min left", 70).equals("37 %, 21,548 shaders left, about 35 min left, 70 kept from before"),
                PrepareText.progressLine(37, true, 1304, 22852, "about 35 min left", 70));
        check(PrepareText.progressLine(100, false, 0, 0, "", 22852).equals("100 %, shaders are up to date"),
                PrepareText.progressLine(100, false, 0, 0, "", 22852));
        check(PrepareText.progressLine(40, false, 1234, 22852, "about 1 min left", 0).equals("40 %, 1,234 of 22,852 shaders"), "outside the step: no time left");

        // The split's count is of files, not shaders (the card read "Finding the shaders 186,583" while the game has 42,898 shaders); the other steps keep theirs
        check(PrepareText.stepCountText("Finding the shaders", true, 1203, 1890, "").equals("Finding the shaders: 1,203 of 1,890 files"), PrepareText.stepCountText("Finding the shaders", true, 1203, 1890, ""));
        check(PrepareText.stepCountText("Finding the shaders", true, 0, 1, "").equals("Finding the shaders: 0 of 1 file"), "one file");
        check(PrepareText.stepCountText("Finding the shaders", true, 0, 0, "").equals("Finding the shaders"), "no count yet: no 0 of 0");
        check(PrepareText.stepCountText("Finding the shaders", true, 2, 5, "40%").equals("Finding the shaders: 2 of 5 files, 40 % of a large file"), PrepareText.stepCountText("Finding the shaders", true, 2, 5, "40%"));   // PEANUTS: build text in the player's words (0.1.8)
        check(PrepareText.stepCountText("Converting the game's code", false, 3, 12, "").equals("Converting the game's code: 3 of 12"), "other steps: a count with no slash");
        plain(PrepareText.stepCountText("Converting the game's code", false, 3, 12, ""), "a step's count");
        check(PrepareText.stepCountText("Checking the dump", false, 1, 1, "identify").equals("Checking the dump"), "a step of one");

        // Needs preparing says why
        check(PrepareText.needsPreparing(false, false, true).contains("Tap Continue preparing"), "a stopped Prepare continues, by its button's name");   // PEANUTS: build text in the player's words (0.1.8)
        check(PrepareText.needsPreparing(true, true, false).contains("unfinished"), "unfinished code");
        check(PrepareText.needsPreparing(true, false, false).contains("cannot be read"), "unreadable pack");
        check(PrepareText.needsPreparing(false, false, false).startsWith("Not built yet"), "no pack");

        System.out.println("ok " + n);
    }
}
