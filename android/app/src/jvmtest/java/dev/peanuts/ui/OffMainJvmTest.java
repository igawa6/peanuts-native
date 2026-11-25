// PEANUTS: OffMain on the JVM (peanuts/tests/test_share_log_choice.py); prints "ok <n>" or throws
package dev.peanuts.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class OffMainJvmTest {
    private static int n;

    private static void eq(Object want, Object got) {
        n++;
        if (!String.valueOf(want).equals(String.valueOf(got))) throw new AssertionError("\nwant: " + want + "\n got: " + got);
    }

    /** The main thread's queue (Android: Handler.post): what was posted runs when the test drains it, on the test's own thread */
    private static final ArrayDeque<Runnable> posted = new ArrayDeque<>();

    private static synchronized void post(Runnable r) { posted.add(r); OffMainJvmTest.class.notifyAll(); }

    /** Waits until the worker posted its answer, then runs everything posted */
    private static synchronized void drain() throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (posted.isEmpty() && System.currentTimeMillis() < until) OffMainJvmTest.class.wait(100);
        if (posted.isEmpty()) throw new AssertionError("the worker posted nothing");
        while (!posted.isEmpty()) posted.poll().run();
    }

    public static void main(String[] args) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "worker"));
        // an Error in the work goes on to the worker thread's own handler (Android: the app's crash handler); here it is kept quiet
        Executor work = r -> worker.execute(() -> { try { r.run(); } catch (AssertionError e) { /* the test's stand-in for an Error */ } });
        Thread main = Thread.currentThread();
        OffMain look = new OffMain(work, OffMainJvmTest::post);
        List<String> seen = new ArrayList<>();

        // the work runs on the worker, its answer on the main thread
        String[] loadedOn = new String[1];
        eq(true, look.run(() -> { loadedOn[0] = Thread.currentThread().getName(); return "two logs"; }, () -> true,
                got -> seen.add(got + " on " + (Thread.currentThread() == main ? "main" : "elsewhere")), e -> seen.add("failed")));
        drain();
        eq("worker", loadedOn[0]);
        eq("[two logs on main]", seen);

        // a second tap while the first one's work runs starts nothing (one sheet, not two); once it is back, a tap works again
        seen.clear();
        CountDownLatch slow = new CountDownLatch(1);
        int[] loads = new int[1];
        eq(true, look.run(() -> { loads[0]++; slow.await(5, TimeUnit.SECONDS); return "first"; }, () -> true, seen::add, e -> seen.add("failed")));
        eq(false, look.run(() -> { loads[0]++; return "second"; }, () -> true, seen::add, e -> seen.add("failed")));
        slow.countDown();
        drain();
        eq("[first]", seen);
        eq(1, loads[0]);
        eq(true, look.run(() -> "third", () -> true, seen::add, e -> seen.add("failed")));
        drain();
        eq("[first, third]", seen);

        // the screen went meanwhile (the activity finished): nothing is shown, and the next tap works
        seen.clear();
        eq(true, look.run(() -> "late", () -> false, seen::add, e -> seen.add("failed")));
        drain();
        eq("[]", seen);
        eq(true, look.run(() -> "again", () -> true, seen::add, e -> seen.add("failed")));
        drain();
        eq("[again]", seen);

        // a load that throws: failed has its exception, on the main thread; done is not called
        seen.clear();
        eq(true, look.run(() -> { throw new java.io.IOException("no access to the folder"); }, () -> true, got -> seen.add("done " + got),
                e -> seen.add(e.getMessage() + (Thread.currentThread() == main ? " on main" : " elsewhere"))));
        drain();
        eq("[no access to the folder on main]", seen);

        // an Error in the load still frees the next tap (nothing is shown for it)
        seen.clear();
        eq(true, look.run(() -> { throw new AssertionError("out of memory"); }, () -> true, got -> seen.add("done " + got), e -> seen.add("failed")));
        drain();
        eq("[]", seen);
        eq(true, look.run(() -> "after", () -> true, seen::add, e -> seen.add("failed")));
        drain();
        eq("[after]", seen);

        worker.shutdown();
        System.out.println("ok " + n);
    }
}
