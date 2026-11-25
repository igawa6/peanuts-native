// PEANUTS: CheckDue (issue #120) on the JVM: a due full check that cannot be made is dropped (with the records and the kept copy), one that fails counts its
// tries, and the notification after one that replaced a library; peanuts/tests/test_reuse_check.py compiles and runs it
package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class CheckDueJvmTest {
    private static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    private static void put(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static final String LIB = "0123456789abcdef0123456789abcdef", OTHER = "fedcba9876543210fedcba9876543210";

    /** check.todo as shader_reuse.py note_check writes it (json.dumps), with check.job and the check's results beside it, and the library's record and kept copy */
    private static void due(File d, String lib) throws IOException {
        put(new File(d, CheckDue.TODO), "{\"library\": \"" + lib + "\", \"version\": \"dfebef5d68a9\", \"game\": \"0100000000AA0000\", \"inputs\": \"x\", \"tree\": \"y\", \"kept\": 9564}");
        put(new File(d, CheckDue.JOB), "/storage/emulated/0/x/job.json");
        put(new File(d, CheckDue.DIR + "/results/0000000000001000.r"), "a result");
        put(new File(d, CheckDue.RECORDS + "/" + lib + ".tsv"), "a record");
        put(new File(d, CheckDue.KEPT + "/v-dfebef5d68a9/dmsh_vk.lib"), "a library");
    }

    /** Whether the check went, and with it what a build keeps by (the records) and from (the kept copy) */
    private static boolean gone(File d) {
        return !new File(d, CheckDue.TODO).exists() && !new File(d, CheckDue.JOB).exists() && !new File(d, CheckDue.DIR).exists()
                && !new File(d, CheckDue.RECORDS).exists() && !new File(d, CheckDue.KEPT).exists();
    }

    /** Whether the check stays as it was, with the records and the kept copy */
    private static boolean kept(File d) {
        return new File(d, CheckDue.TODO).isFile() && new File(d, CheckDue.DIR).isDirectory() && new File(d, CheckDue.RECORDS).isDirectory() && new File(d, CheckDue.KEPT).isDirectory();
    }

    public static void main(String[] args) throws IOException {
        File d = Files.createTempDirectory("checkdue").toFile();

        // the ends that leave the check as it was: done (the tool ended it), stopped, waiting
        due(d, LIB);
        for (String w : new String[] {"done", "interrupted", "waits (a game runs)", null}) check(CheckDue.ended(d, w) == null && kept(d), "stays: " + w);
        check(CheckDue.library(d).equals(LIB), "check.todo's library");

        // one this job can never make: dropped at once, with the records and the kept copy (found in review: the next build kept the same entries again)
        String end = CheckDue.ended(d, "no split to check with");
        check(end.contains("dropped") && end.contains("the next build of this game translates every shader") && gone(d), "no split: " + end);
        due(d, LIB);
        check(CheckDue.ended(d, "the split holds another selection").contains("dropped") && gone(d), "another selection");

        // one that fails: two tries, the third drops it
        due(d, LIB);
        String fail = "shaders stopped with code 1: storage full";
        check(CheckDue.ended(d, fail).contains("1 of 3") && kept(d), "first try");
        check(CheckDue.ended(d, fail).contains("2 of 3") && kept(d), "second try");
        end = CheckDue.ended(d, fail);
        check(end.contains("dropped") && end.contains("the next build of this game translates every shader") && gone(d), "the third drops it: " + end);
        check(!new File(d, CheckDue.TRIES).exists(), "the count goes with it");

        // an app update since the build (PackService): the same
        due(d, LIB);
        CheckDue.drop(d);
        check(gone(d), "an app update");

        // the count is the library's: a build since leaves another check.todo, whose check starts over
        due(d, LIB);
        CheckDue.ended(d, fail); CheckDue.ended(d, fail);
        due(d, OTHER);
        check(CheckDue.ended(d, fail).contains("1 of 3"), "another library starts over");

        // a check whose process ended (a low-memory kill, the 20 s end of a stop, a crash) runs no end of its own: the next start counts it (found in review)
        due(d, LIB);
        check(CheckDue.died(d) == null, "no check ran before");
        CheckDue.begin(d);
        check(CheckDue.ended(d, "done") == null && CheckDue.died(d) == null, "a check that ended takes its mark with it");
        CheckDue.begin(d);
        check(CheckDue.ended(d, "interrupted") == null && CheckDue.died(d) == null, "a stopped one too");
        CheckDue.begin(d);
        String died = CheckDue.died(d);
        check(died != null && died.contains("1 of 3") && kept(d), "a check whose process ended counts a try: " + died);
        check(CheckDue.died(d) == null, "once");
        CheckDue.begin(d); CheckDue.died(d); CheckDue.begin(d);
        // a check interrupted three times this way (a game that starts and outlives the 20 s of the stop ends the process): the records go with it
        check(CheckDue.died(d).contains("dropped") && gone(d), "the third drops it");

        // not due (the check ended it): nothing counted
        CheckDue.drop(d);
        check(CheckDue.ended(d, fail) == null && !new File(d, CheckDue.TRIES).exists(), "not due");

        // the notification: only when the pack's library was replaced, after the check began, with the real counts
        long since = System.currentTimeMillis() - 60_000;
        File failed = new File(d, CheckDue.FAILED);
        put(failed, "{\"library\": \"" + LIB + "\", \"replaced\": true, \"differ_count\": 300, \"missing_count\": 0, \"extra_count\": 2, \"kept\": 9564, \"differ\": [], \"of\": 9766}");
        String t = CheckDue.replacedText(d, "Animal Crossing: New Horizons", since);
        check(t != null && t.startsWith("302 of its 9,766 shaders came out different"), String.valueOf(t));
        check(t.contains("Animal Crossing: New Horizons now uses the ones made from the start"), t);
        check(!t.contains("·") && !t.contains("—") && !t.contains("–") && !t.contains("!"), "UI rules: " + t);
        check(CheckDue.replacedText(d, "x", System.currentTimeMillis() + 60_000) == null, "an older check.failed says nothing");
        put(failed, "{\"library\": \"" + LIB + "\", \"replaced\": false, \"differ_count\": 3, \"of\": 9766}");
        check(CheckDue.replacedText(d, "x", since) == null, "the pack held another library: nothing replaced, nothing to say");
        put(failed, "not json");
        check(CheckDue.replacedText(d, "x", since) == null, "a check.failed that does not read");
        check(CheckDue.text("x", 0, 0).startsWith("Some of its shaders"), "no counts");

        CheckDue.drop(d); failed.delete(); d.delete();
        System.out.println("ok");
    }
}
