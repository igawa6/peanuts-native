package dev.peanuts.ui;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A tap's work that reads files or asks Android (a log's tail, the launcher's list, the running processes), off the main thread: on an SD card or a USB
 * drive that can take seconds, and the screen would stall that long. The work runs on a worker, its result comes back on the main thread, and only while
 * the screen it was for is still there. A second tap while the first one's work runs starts nothing, so a slow folder opens one sheet, not two.
 * No Android class: OffMainJvmTest drives it with executors of its own (Android: a worker thread, and the main thread's Handler.post).
 */
final class OffMain {
    private final Executor work, main;
    private boolean busy;   // read and written on the main thread only: set by run, cleared when the result is back

    OffMain(Executor work, Executor main) {
        this.work = work;
        this.main = main;
    }

    /**
     * load on the worker, then done with its result on the main thread, while alive still says yes there. A load that throws hands its exception to
     * failed instead (on the main thread, by the same rule). False when the work of an earlier call has not come back yet: then nothing starts
     */
    <T> boolean run(Callable<T> load, BooleanSupplier alive, Consumer<T> done, Consumer<Exception> failed) {
        if (busy) return false;
        busy = true;
        work.execute(() -> {
            T got = null;
            Exception error = null;
            boolean ok = false;
            try {
                got = load.call();
                ok = true;
            } catch (Exception e) {
                error = e;
            } finally {   // also after an Error: the next tap works again
                T g = got;
                Exception e = error;
                boolean fine = ok;
                main.execute(() -> {
                    busy = false;
                    if (!alive.getAsBoolean()) return;
                    if (fine) done.accept(g);
                    else if (e != null) failed.accept(e);
                });
            }
        });
        return true;
    }
}
