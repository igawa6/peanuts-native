// PEANUTS: the build queue (BuildQueue, issue #348) on the JVM; peanuts/tests/test_build_queue.py compiles and runs it
package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The rules of the build queue that need no phone (one argument, a scratch folder; prints "ok" or throws):
 * <ul>
 * <li>order: first in, first built; the next item only once the one before has ended;</li>
 * <li>the file: every field of a request read back as it was written (paths with tabs, line breaks and backslashes, no update, DLC, Build anyway, Import
 *     again), and the queue goes on from the file alone;</li>
 * <li>a run the process did not live through: back to the front, and stopped after MAX_TRIES starts while the rest goes on;</li>
 * <li>a failed or stopped item does not stop the queue; the batch's summary names it;</li>
 * <li>removing, Cancel all, the same game asked twice, a game that runs asked again;</li>
 * <li>the batch's places ("2 of 5") and a new batch once the queue ran empty;</li>
 * <li>a damaged file: what reads is kept, nothing throws;</li>
 * <li>Build all of 50 games: one write of the file, the same queue as 50 adds one by one, and the same file after a restart.</li>
 * </ul>
 */
public final class BuildQueueJvmTest {
    private static final String A = "0100152000022000", B = "01006F8002326000", C = "0100F2C0115B6000", D = "01006BB00C6F0000";
    private static long now = 1_000;

    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    private static void same(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": want <" + want + ">, got <" + got + ">");
    }

    private static BuildRequest req(String titleId, String name, File dir) {
        BuildRequest r = new BuildRequest(titleId, name, new File(dir, name + ".nsp"), new File(dir, name + " update.nsp"), Arrays.asList(new File(dir, name + " dlc1.nsp")), dir, null);
        return r;
    }

    private static List<String> ids(List<BuildQueue.Item> l) {
        List<String> out = new ArrayList<>();
        for (BuildQueue.Item i : l) out.add(i.titleId());
        return out;
    }

    private static File fresh(File root, String name) throws IOException {
        File d = new File(root, name);
        Files.createDirectories(d.toPath());
        File f = new File(d, BuildQueue.FILE);
        Files.deleteIfExists(f.toPath());
        return f;
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : System.getProperty("java.io.tmpdir"), "build-queue-test");
        Files.createDirectories(root.toPath());
        order(root);
        file(root);
        killed(root);
        failures(root);
        removing(root);
        twice(root);
        batches(root);
        damaged(root);
        buildAll(root);
        System.out.println("ok");
    }

    /** First in, first built; the next one only after the one before ended; each start saved */
    private static void order(File root) throws IOException {
        File f = fresh(root, "order");
        BuildQueue q = new BuildQueue(f);
        check(!q.active() && q.next() == null && q.running() == null, "an empty queue has nothing to do");
        BuildQueue.Item a = q.add(req(A, "Mario Kart", root), false, now++), b = q.add(req(B, "Animal Crossing", root), false, now++), c = q.add(req(C, "Kirby", root), false, now++);
        same(Arrays.asList(A, B, C), ids(q.waiting()), "waiting in the order asked");
        same(a.id, q.next().id, "the first asked is next");
        q.start(a.id, now++);
        same(a.id, q.running().id, "it runs");
        same(b.id, q.next().id, "the second waits");
        same(Arrays.asList(B, C), ids(q.waiting()), "the others wait");
        q.finish(a.id, true, "", "Mario Kart is ready to play.", now++);
        check(q.running() == null, "nothing runs between two items");
        same(b.id, q.next().id, "then the second");
        q.start(b.id, now++);
        q.finish(b.id, true, "", "ok", now++);
        q.start(c.id, now++);
        q.finish(c.id, true, "", "ok", now++);
        check(!q.active(), "all built: nothing to do");
        same("All 3 games are ready to play.", q.summary(), "the summary");
        same(3, q.readyCount(), "three ready");
        BuildQueue again = new BuildQueue(f);
        check(!again.active() && !again.damaged(), "read back: nothing to do, nothing damaged");
    }

    /** Every field of a request comes back from the file as it was written, and the queue goes on from the file alone */
    private static void file(File root) throws IOException {
        File f = fresh(root, "file");
        BuildQueue q = new BuildQueue(f);
        BuildRequest odd = new BuildRequest(A, "Tab\there, line\nbreak and a back\\slash \\0", new File(root, "a\tb\\c\nd.nsp"), null, new ArrayList<>(), null, "dev.example.companion");
        odd.buildDestination = new File(root, "builds").getPath();
        odd.replaceNup = new File(root, "Mario Kart.nup");
        odd.deleteOldFirst = true;
        BuildQueue.Item one = q.add(odd, true, 42);
        BuildRequest plain = req(B, "Animal Crossing", root);
        plain.dlcs = new ArrayList<>(Arrays.asList(new File(root, "dlc 1.nsp"), new File(root, "dlc 2.nsp")));
        BuildQueue.Item two = q.add(plain, false, 43);
        q.step(one.id, "import");
        BuildQueue r = new BuildQueue(f);
        check(!r.damaged(), "the file reads whole");
        BuildQueue.Item x = r.get(one.id), y = r.get(two.id);
        check(x != null && y != null, "both items read back");
        same(odd.name, x.request.name, "a name with a tab, a line break and backslashes");
        same(odd.base, x.request.base, "a base path with them");
        same(null, x.request.update, "no update stays none");
        same(0, x.request.dlcs.size(), "no DLC stays none");
        same(null, x.request.gameDir, "no game folder stays none");
        same(odd.buildDestination, x.request.buildDestination, "the build destination");
        same("dev.example.companion", x.request.companionPackage, "the companion");
        same(odd.replaceNup, x.request.replaceNup, "Import again's .nup");
        check(x.request.deleteOldFirst, "Import again's second choice");
        check(x.buildAnyway, "Build anyway");
        same(42L, x.added, "when it was asked");
        same("import", x.step, "the step");
        same(plain.dlcs, y.request.dlcs, "two DLC in their order");
        same(plain.update, y.request.update, "the update");
        same(plain.gameDir, y.request.gameDir, "the game folder");
        same(null, y.request.companionPackage, "no companion stays none");
        check(!y.buildAnyway && !y.request.deleteOldFirst, "no consent asked for is none");
        BuildQueue.Item n = r.add(req(C, "Kirby", root), false, 44);
        check(n.id > two.id, "ids go on after the file's: " + n.id);
        check(!new File(f.getPath() + ".part").exists(), "no half-written file left");
    }

    /** A run the process did not live through: back to the front, then stopped after MAX_TRIES starts while the rest goes on */
    private static void killed(File root) throws IOException {
        File f = fresh(root, "killed");
        BuildQueue q = new BuildQueue(f);
        BuildQueue.Item a = q.add(req(A, "Mario Kart", root), false, now++), b = q.add(req(B, "Animal Crossing", root), false, now++);
        q.start(a.id, now++);
        q.step(a.id, "prepare");
        for (int t = 1; t < BuildQueue.MAX_TRIES; t++) {
            BuildQueue r = new BuildQueue(f);   // the process ended while it ran: the next one reads the file
            check(r.running() == null, "a cut-off run does not count as running");
            same(a.id, r.next().id, "the cut-off item goes first again (try " + t + ")");
            same(Arrays.asList(A, B), ids(r.waiting()), "before the others");
            same(t, r.get(a.id).tries, "its tries");
            same(1, r.place(r.get(a.id)), "its place stays");
            r.start(a.id, now++);
        }
        BuildQueue last = new BuildQueue(f);
        BuildQueue.Item s = last.get(a.id);
        check(s.stopped(), "stopped after " + BuildQueue.MAX_TRIES + " starts");
        same(BuildListener.OTHER, s.code, "its code");
        check(s.message.contains("ended " + BuildQueue.MAX_TRIES + " times"), "it says why: " + s.message);
        same(b.id, last.next().id, "the queue goes on with the next");
        same("1 of 2", last.placeText(s), "it keeps its place in the batch");
    }

    /** A failed or stopped item does not stop the queue */
    private static void failures(File root) throws IOException {
        File f = fresh(root, "failures");
        BuildQueue q = new BuildQueue(f);
        BuildQueue.Item a = q.add(req(A, "Mario Kart", root), false, now++), b = q.add(req(B, "Animal Crossing", root), false, now++), c = q.add(req(C, "Kirby", root), false, now++);
        q.start(a.id, now++);
        q.step(a.id, "prepare");
        q.finish(a.id, false, BuildListener.TOOL_STOPPED, "Translating shaders stopped.\nThe tool said: no space", now++);
        same(b.id, q.next().id, "a failure: the next one starts");
        q.start(b.id, now++);
        q.finish(b.id, false, BuildListener.CANCELLED, "Build cancelled.", now++);
        same(c.id, q.next().id, "stopped by the player: the next one starts");
        q.start(c.id, now++);
        q.finish(c.id, true, "", "ok", now++);
        BuildQueue r = new BuildQueue(f);
        same(Arrays.asList(A, B), ids(r.stopped()), "both stopped ones kept, in order");
        same("prepare", r.get(a.id).step, "the step it stopped in");
        same(BuildListener.TOOL_STOPPED, r.get(a.id).code, "its code");
        same("1 of 3 games are ready to play. Mario Kart stopped: Translating shaders stopped. Animal Crossing was stopped.", r.summary(), "the summary");
        same(A, r.of(A).titleId(), "a stopped game has its item");
        // built again, and ready: its stopped item goes
        BuildQueue.Item again = r.add(req(A, "Mario Kart", root), false, now++);
        check(r.get(a.id) == null, "asked again: the stopped item goes, the new one stands for it");
        r.start(again.id, now++);
        r.finish(again.id, true, "", "ok", now++);
        same(Arrays.asList(B), ids(r.stopped()), "only the other stopped one is left");
        BuildQueue one = new BuildQueue(fresh(root, "failures-one"));
        BuildQueue.Item only = one.add(req(D, "Link's Awakening", root), false, now++);
        one.start(only.id, now++);
        one.finish(only.id, false, BuildListener.OUT_OF_SPACE, "Not enough free space to import Link's Awakening", now++);
        same("The game is not ready. Link's Awakening stopped: Not enough free space to import Link's Awakening.", one.summary(), "a batch of one");
    }

    /** Remove, Cancel all, the daily limit's requeue */
    private static void removing(File root) throws IOException {
        File f = fresh(root, "removing");
        BuildQueue q = new BuildQueue(f);
        BuildQueue.Item a = q.add(req(A, "Mario Kart", root), false, now++), b = q.add(req(B, "Animal Crossing", root), false, now++), c = q.add(req(C, "Kirby", root), false, now++);
        q.start(a.id, now++);
        check(!q.remove(a.id), "the running item is not removed");
        check(q.remove(b.id), "a waiting one is");
        same(Arrays.asList(C), ids(q.waiting()), "removed");
        check(new BuildQueue(f).get(b.id) == null, "removed for good");
        same("2 of 2", q.placeText(c), "the places close up");
        check(!q.remove(999), "an unknown id");
        q.requeue(a.id);
        same(Arrays.asList(A, C), ids(q.waiting()), "requeued: first again");
        same(0, q.get(a.id).tries, "a requeue is no try");
        // a pause: nothing starts until a game is asked for (or Resume)
        long before = System.currentTimeMillis();
        q.hold();
        check(new BuildQueue(f).held(), "the pause is kept in the file");
        check(new BuildQueue(f).heldAt() >= before, "with when it began");
        q.release();
        check(!q.held() && !new BuildQueue(f).held(), "Resume");
        q.hold();
        q.add(req(C, "Kirby", root), false, now++);
        check(!q.held(), "a game asked for ends the pause");
        same(Arrays.asList(A, C), ids(q.waiting()), "and keeps the order");
        q.add(req(D, "Link's Awakening", root), false, now++);
        same(3, q.removeWaiting(), "Cancel all: every waiting one");
        check(!q.active() && new BuildQueue(f).items().isEmpty(), "nothing left");
    }

    /** The same game asked twice keeps its place; one that runs cannot be asked again */
    private static void twice(File root) throws IOException {
        File f = fresh(root, "twice");
        BuildQueue q = new BuildQueue(f);
        BuildQueue.Item a = q.add(req(A, "Mario Kart", root), false, now++), b = q.add(req(B, "Animal Crossing", root), false, now++), c = q.add(req(C, "Kirby", root), false, now++);
        BuildRequest newer = req(B, "Animal Crossing", root);
        newer.update = new File(root, "Animal Crossing 3.0.3.nsp");
        BuildQueue.Item b2 = q.add(newer, true, now++);
        same(b.id, b2.id, "the same item");
        same(Arrays.asList(A, B, C), ids(q.waiting()), "in its place");
        same(newer.update, q.get(b.id).request.update, "with the newer request");
        check(q.get(b.id).buildAnyway, "and its consent");
        same(3, q.total(), "not counted twice");
        q.start(a.id, now++);
        check(q.add(req(A, "Mario Kart", root), false, now++) == null, "the running game is refused");
        same(A, q.running().titleId(), "and still runs");
        check(q.of(c.request.titleId.toLowerCase()) != null, "title IDs compare without case");
    }

    /** "2 of 5", a batch of one, and a new batch once the queue ran empty */
    private static void batches(File root) throws IOException {
        File f = fresh(root, "batches");
        BuildQueue q = new BuildQueue(f);
        BuildQueue.Item a = q.add(req(A, "Mario Kart", root), false, now++);
        same("", q.placeText(a), "a batch of one has no place to say");
        BuildQueue.Item b = q.add(req(B, "Animal Crossing", root), false, now++);
        same("1 of 2", q.placeText(a), "two: the first");
        same("2 of 2", q.placeText(b), "two: the second");
        q.start(a.id, now++);
        q.finish(a.id, true, "", "ok", now++);
        BuildQueue.Item c = q.add(req(C, "Kirby", root), false, now++);
        same("3 of 3", q.placeText(c), "added while the batch runs: it joins it");
        q.start(b.id, now++);
        q.finish(b.id, false, BuildListener.TOOL_STOPPED, "Rewriting the game's code stopped.", now++);
        q.start(c.id, now++);
        q.finish(c.id, true, "", "ok", now++);
        BuildQueue.Item d = q.add(req(D, "Link's Awakening", root), false, now++);
        same("", q.placeText(d), "after the queue ran empty a new batch begins");
        same(1, q.total(), "the new batch has one");
        same(0, q.place(q.get(b.id)), "the last batch's stopped item has no place in it");
        check(q.get(a.id) == null && q.get(c.id) == null, "the last batch's ready items went");
        check(q.get(b.id) != null && q.get(b.id).stopped(), "its stopped one stays for its card");
        BuildQueue r = new BuildQueue(f);
        same("", r.placeText(r.get(d.id)), "the batch reads back");
        same(1, r.total(), "with its one item");
    }

    /** What reads is kept, nothing throws */
    private static void damaged(File root) throws IOException {
        File f = fresh(root, "damaged");
        Files.write(f.toPath(), "not a queue\n".getBytes(StandardCharsets.UTF_8));
        BuildQueue q = new BuildQueue(f);
        check(q.damaged() && q.items().isEmpty(), "another file: empty, damaged");
        BuildQueue.Item a = q.add(req(A, "Mario Kart", root), false, now++);
        check(new BuildQueue(f).get(a.id) != null, "and written anew on the next change");
        q.add(req(B, "Animal Crossing", root), false, now++);
        List<String> lines = new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        lines.add(2, lines.get(2).substring(0, 30));   // a line cut off
        lines.add("1\t1\twaiting\tx");   // a line of nonsense
        lines.add(lines.get(1));   // an id twice
        Files.write(f.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        BuildQueue r = new BuildQueue(f);
        check(r.damaged(), "damaged lines noticed");
        same(Arrays.asList(A, B), ids(r.waiting()), "the whole lines kept, once each");
        Files.write(f.toPath(), new byte[0]);
        check(new BuildQueue(f).items().isEmpty(), "an empty file: empty");
        check(new BuildQueue(new File(root, "nothing/here/" + BuildQueue.FILE)).items().isEmpty(), "no file: empty");
        BuildQueue none = new BuildQueue(null);
        none.add(req(A, "Mario Kart", root), false, now++);
        same(1, none.items().size(), "a queue without a file works in memory");
    }

    /** Build all of 50 games (BuildPipeline.startAll): one write of the file, not 50; the queue, its places and its file as 50 adds one by one would leave them */
    private static void buildAll(File root) throws IOException {
        List<BuildRequest> reqs = new ArrayList<>();
        List<Boolean> anyway = new ArrayList<>();
        for (int k = 0; k < 50; k++) {
            reqs.add(req(String.format("01000000%04X0000", k), "Game " + k, root));
            anyway.add(k % 7 == 0);
        }
        // one by one, as before
        File f1 = fresh(root, "build-all-one-by-one");
        BuildQueue one = new BuildQueue(f1);
        one.add(req(A, "Mario Kart", root), false, now);
        one.start(one.next().id, now);   // a game builds already: the 50 wait behind it
        int before = one.saves;
        for (int k = 0; k < reqs.size(); k++) one.add(reqs.get(k), anyway.get(k), now);
        same(50, one.saves - before, "one by one: a write a game");
        // all at once
        File f2 = fresh(root, "build-all-at-once");
        BuildQueue all = new BuildQueue(f2);
        all.add(req(A, "Mario Kart", root), false, now);
        all.start(all.next().id, now);
        before = all.saves;
        List<BuildQueue.Item> got = all.addAll(reqs, anyway, now);
        same(1, all.saves - before, "Build all of 50: one write of the queue");
        same(50, got.size(), "an answer a request");
        same(one.toString(), all.toString(), "the same queue as one by one");
        for (int k = 0; k < 50; k++) {
            same(reqs.get(k).titleId, got.get(k).titleId(), "the answers in the order asked");
            same(anyway.get(k), got.get(k).buildAnyway, "Build anyway kept with its request");
            same(one.placeText(one.get(got.get(k).id)), all.placeText(got.get(k)), "the same places");
        }
        same("2 of 51", all.placeText(got.get(0)), "the first of them goes second");
        same(new String(Files.readAllBytes(f1.toPath()), StandardCharsets.UTF_8), new String(Files.readAllBytes(f2.toPath()), StandardCharsets.UTF_8), "the same file");
        // a killed app goes on with all of them, in order, the running one first again
        BuildQueue again = new BuildQueue(f2);
        same(51, again.waiting().size(), "the whole batch read back");
        same(A, again.next().titleId(), "the cut-off build goes first again");
        same(reqs.get(49).titleId, again.waiting().get(50).titleId(), "the last asked is last");
        // the game that builds is refused within the batch too, and a game asked twice keeps one place with the newer request
        List<BuildQueue.Item> more = all.addAll(Arrays.asList(req(A, "Mario Kart", root), req(B, "Animal Crossing", root), req(B, "Animal Crossing again", root)),
                Arrays.asList(false, false, true), now);
        check(more.get(0) == null, "the game that builds is refused");
        same(more.get(1).id, more.get(2).id, "asked twice: one place");
        same("Animal Crossing again", all.get(more.get(2).id).name(), "with the newer request");
        same(2, all.saves - before, "one more write for the second batch");
        // a batch Android did not let start leaves at once
        int left = all.removeAll(Arrays.asList(more.get(1).id, all.running().id));
        same(1, left, "the waiting one leaves, the running one stays");
        same(3, all.saves - before, "one write for the removal");
        before = all.saves;
        check(all.addAll(Arrays.asList(req(A, "Mario Kart", root)), Arrays.asList(false), now).get(0) == null && all.saves == before, "nothing added: nothing written");
    }
}
