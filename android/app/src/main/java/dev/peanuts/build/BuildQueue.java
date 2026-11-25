package dev.peanuts.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The build queue (issue #348): the games the library was asked to build, in order, kept in one file of the app's own storage so that a killed app or process
 * goes on with them ({@link BuildService} runs one at a time). No Android class here: BuildQueueJvmTest drives it.
 * <ul>
 * <li>An item is {@link #WAITING}, {@link #RUNNING}, {@link #READY} or {@link #STOPPED} (failed, or stopped by the player), with the failure's code, its message,
 *     and the step it stopped in. A stopped item does not stop the queue: the next one starts.</li>
 * <li>The batch: the items since the queue last ran empty. Its places give the notification's "2 of 5" and the covers' "Queued, 3 of 5". The batch's ready items
 *     go when a new batch starts; stopped ones stay (their build cards show why) until they are built again or dismissed.</li>
 * <li>A game asked again while it waits keeps its place with the newer request; one that runs cannot be asked again.</li>
 * <li>An item still {@link #RUNNING} when the file is read was cut off with its process: it goes back to the front and runs again (every step resumes). After
 *     {@link #MAX_TRIES} starts it is stopped, so a build that ends the app each time does not loop through the night.</li>
 * </ul>
 * The file: a header line, then one line an item, tab-separated, with tabs, line breaks and backslashes escaped. It is written beside itself and moved into place.
 */
public final class BuildQueue {
    public static final String WAITING = "waiting", RUNNING = "running", READY = "ready", STOPPED = "stopped";
    /** Starts of one item before it is stopped for good (each start after the first is a run that ended with the app's process) */
    public static final int MAX_TRIES = 3;
    /** What the queue waits for before its next game (BuildService.waitTurn): it goes on by itself once that has passed */
    public static final String WAIT_HEAT = "heat", WAIT_BATTERY = "battery", WAIT_GAME = "game", WAIT_MODS = "mods", WAIT_PACK = "pack";
    static final String FILE = "build-queue.tsv";
    private static final String MAGIC = "peanuts-build-queue";
    private static final int FORMAT = 1;

    /** One game to build */
    public static final class Item {
        public final int id, batch;
        public final BuildRequest request;
        /** The player's "Build anyway" for this request (P1): kept with it, never inferred */
        public final boolean buildAnyway;
        public String state = WAITING, code = "", message = "", step = "";
        public long added, started, ended;
        public int tries;

        Item(int id, int batch, BuildRequest request, boolean buildAnyway) { this.id = id; this.batch = batch; this.request = request; this.buildAnyway = buildAnyway; }

        public String titleId() { return request.titleId == null ? "" : request.titleId; }
        public String name() { return request.name == null || request.name.isEmpty() ? titleId() : request.name; }
        public boolean waiting() { return WAITING.equals(state); }
        public boolean running() { return RUNNING.equals(state); }
        public boolean stopped() { return STOPPED.equals(state); }
        public boolean ready() { return READY.equals(state); }
    }

    private final File file;
    private final List<Item> items = new ArrayList<>();
    private int nextId = 1, batch = 1;
    private boolean damaged, held;
    private long heldAt;   // when the pause began (a job of its first game finished after it is the player going on)

    /** The queue of this file, read now; null file: one that is never saved */
    public BuildQueue(File file) {
        this.file = file;
        load();
    }

    // ---- What it holds ----

    /** Every item, in order (a copy) */
    public synchronized List<Item> items() { return new ArrayList<>(items); }

    /** The item that runs now, null when none */
    public synchronized Item running() {
        for (Item i : items) if (i.running()) return i;
        return null;
    }

    /** The next item to run: the first one waiting, null when none waits */
    public synchronized Item next() {
        for (Item i : items) if (i.waiting()) return i;
        return null;
    }

    /** The items waiting, in the order they run */
    public synchronized List<Item> waiting() {
        List<Item> l = new ArrayList<>();
        for (Item i : items) if (i.waiting()) l.add(i);
        return l;
    }

    /** The stopped items (of this batch and earlier ones), oldest first */
    public synchronized List<Item> stopped() {
        List<Item> l = new ArrayList<>();
        for (Item i : items) if (i.stopped()) l.add(i);
        return l;
    }

    public synchronized Item get(int id) {
        for (Item i : items) if (i.id == id) return i;
        return null;
    }

    /** The newest item of a game that is waiting, running or stopped (a ready one is no state of the cover), null when none */
    public synchronized Item of(String titleId) {
        Item hit = null;
        for (Item i : items) if (!i.ready() && i.titleId().equalsIgnoreCase(titleId)) hit = i;
        return hit;
    }

    /** Whether something is to do: an item runs or waits */
    public synchronized boolean active() { return running() != null || next() != null; }

    /** Whether the player paused the queue (the packer's Pause while a game prepared): nothing starts until Resume, or until a game is added */
    public synchronized boolean held() { return held; }

    /** When the pause began (wall clock, ms), 0 when the queue is not paused */
    public synchronized long heldAt() { return held ? heldAt : 0; }

    /** Pause: the queue keeps its games and starts none */
    public synchronized void hold() {
        if (held) return;
        held = true;
        heldAt = System.currentTimeMillis();
        save();
    }

    /** Resume after a pause */
    public synchronized void release() {
        if (!held) return;
        held = false;
        save();
    }

    /** Whether the file could not be read in full when it was loaded (the items that read were kept) */
    public synchronized boolean damaged() { return damaged; }

    // ---- The batch: "2 of 5" ----

    /** The item's place in its batch (1 for the first), 0 for one of an earlier batch */
    public synchronized int place(Item it) {
        if (it == null || it.batch != batch) return 0;
        int n = 0;
        for (Item i : items) if (i.batch == batch) { n++; if (i == it || i.id == it.id) return n; }
        return 0;
    }

    /** How many items the batch has: ready, stopped, running and waiting */
    public synchronized int total() {
        int n = 0;
        for (Item i : items) if (i.batch == batch) n++;
        return n;
    }

    /** "2 of 5", "" for an item of an earlier batch or a batch of one */
    public synchronized String placeText(Item it) {
        int p = place(it), t = total();
        return p == 0 || t <= 1 ? "" : p + " of " + t;
    }

    /** The batch's end, in one sentence or two: how many are ready, which stopped and why (the first line of each message) */
    public synchronized String summary() {
        int ready = 0, total = 0;
        List<Item> stopped = new ArrayList<>();
        for (Item i : items) {
            if (i.batch != batch) continue;
            total++;
            if (i.ready()) ready++;
            else if (i.stopped()) stopped.add(i);
        }
        if (total == 0) return "";
        StringBuilder s = new StringBuilder();
        if (stopped.isEmpty()) s.append(total == 1 ? "The game is ready to play." : total == 2 ? "Both games are ready to play." : "All " + total + " games are ready to play.");
        else s.append(ready == 0 ? (total == 1 ? "The game is not ready." : "None of the " + total + " games is ready.") : ready + " of " + total + " games are ready to play.");
        for (Item i : stopped) {
            String why = firstLine(i.message);
            s.append(' ').append(i.name()).append(BuildListener.CANCELLED.equals(i.code) ? " was stopped." : " stopped" + (why.isEmpty() ? "." : ": " + withStop(why)));
        }
        return s.toString();
    }

    /** How many items of the batch are ready */
    public synchronized int readyCount() {
        int n = 0;
        for (Item i : items) if (i.batch == batch && i.ready()) n++;
        return n;
    }

    // ---- Changes (each one saved) ----

    /**
     * Adds a game's build at the end, or, when the game waits already, puts this request in its place. A game that runs now is refused (null: the caller says
     * it is being built). An earlier ready or stopped item of the game goes: this one stands for it. When nothing waits or runs, a new batch begins
     */
    public synchronized Item add(BuildRequest r, boolean buildAnyway, long now) {
        Item n = put(r, buildAnyway, now);
        if (n != null) save();
        return n;
    }

    /**
     * Several games at once (Build all): each taken in order as {@link #add} takes it, and the file written once at the end instead of once a game (Build all
     * of 50 games wrote the whole file 50 times on the main thread). The answer holds add's answer for each request, in their order (null: that game runs now)
     */
    public synchronized List<Item> addAll(List<BuildRequest> requests, List<Boolean> buildAnyway, long now) {
        List<Item> out = new ArrayList<>(requests.size());
        boolean any = false;
        for (int k = 0; k < requests.size(); k++) {
            Item n = put(requests.get(k), buildAnyway.get(k), now);
            out.add(n);
            any |= n != null;
        }
        if (any) save();
        return out;
    }

    /** add without the save: null for a game that runs now (nothing changed then) */
    private Item put(BuildRequest r, boolean buildAnyway, long now) {
        String id = r.titleId == null ? "" : r.titleId;
        for (Item i : items) if (i.running() && i.titleId().equalsIgnoreCase(id)) return null;
        held = false;   // a game asked for (the paused one's Resume, or another) ends a pause
        if (running() == null && next() == null) newBatch();
        for (int k = 0; k < items.size(); k++) {
            Item i = items.get(k);
            if (i.waiting() && i.titleId().equalsIgnoreCase(id)) {
                Item n = new Item(i.id, i.batch, r, buildAnyway);
                n.added = i.added;
                items.set(k, n);
                return n;
            }
        }
        items.removeIf(i -> !i.running() && !i.waiting() && i.titleId().equalsIgnoreCase(id));
        Item n = new Item(nextId++, batch, r, buildAnyway);
        n.added = now;
        items.add(n);
        return n;
    }

    /** The item runs now (the service took it): one more try */
    public synchronized void start(int id, long now) {
        Item i = get(id);
        if (i == null) return;
        i.state = RUNNING;
        i.started = now;
        i.tries++;
        save();
    }

    /** The step the running item is in (import, prepare, companion), kept for the build card of an item that stops; saved only when it changes */
    public synchronized void step(int id, String step) {
        Item i = get(id);
        if (i == null || step == null || step.equals(i.step)) return;
        i.step = step;
        save();
    }

    /** How the item ended: ready, or stopped with the failure's code and message. A ready game's stopped items of earlier batches go */
    public synchronized void finish(int id, boolean ok, String code, String message, long now) {
        Item i = get(id);
        if (i == null) return;
        i.state = ok ? READY : STOPPED;
        i.code = ok ? "" : code == null ? BuildListener.OTHER : code;
        i.message = message == null ? "" : message;
        i.ended = now;
        if (ok) items.removeIf(x -> x != i && x.stopped() && x.titleId().equalsIgnoreCase(i.titleId()));
        save();
    }

    /** The running item goes back to the front, waiting (a pause, Android's daily limit for the service: the next start goes on with it). Its start was no
     *  run the process did not live through, so it counts no try */
    public synchronized void requeue(int id) {
        Item i = get(id);
        if (i == null || !i.running()) return;
        i.state = WAITING;
        i.tries = Math.max(0, i.tries - 1);
        items.remove(i);
        items.add(firstOpen(), i);
        save();
    }

    /** A waiting or stopped item leaves the queue (the cover's Remove, a stopped card's Dismiss); false for one that runs or is not there */
    public synchronized boolean remove(int id) {
        Item i = get(id);
        if (i == null || i.running()) return false;
        items.remove(i);
        save();
        return true;
    }

    /** Several waiting or stopped items leave at once, with one save (a Build all that Android did not let start); returns how many left */
    public synchronized int removeAll(List<Integer> ids) {
        int n = 0;
        for (int id : ids) {
            Item i = get(id);
            if (i == null || i.running()) continue;
            items.remove(i);
            n++;
        }
        if (n > 0) save();
        return n;
    }

    /** Every waiting item leaves (Cancel all); returns how many */
    public synchronized int removeWaiting() {
        int before = items.size();
        items.removeIf(Item::waiting);
        int n = before - items.size();
        if (n > 0) save();
        return n;
    }

    // ---- Inside ----

    private void newBatch() {
        boolean any = false;
        for (Item i : items) if (i.batch == batch) { any = true; break; }
        if (any) batch++;
        items.removeIf(Item::ready);   // the last batch's ready games are in the library now; its stopped ones keep their cards
    }

    /** Where a waiting item goes to run first: after the stopped and ready items (they stay in the order they came) */
    private int firstOpen() {
        for (int k = 0; k < items.size(); k++) if (items.get(k).waiting() || items.get(k).running()) return k;
        return items.size();
    }

    /** What a file still says is running was cut off with its process: back to the front, or stopped after MAX_TRIES starts */
    private void recover() {
        List<Item> cut = new ArrayList<>();
        for (Item i : items) if (i.running()) cut.add(i);
        if (cut.isEmpty()) return;
        for (Item i : cut) {
            items.remove(i);
            if (i.tries >= MAX_TRIES) {
                i.state = STOPPED;
                i.code = BuildListener.OTHER;
                i.message = "The app ended " + i.tries + " times while building " + i.name() + ", so the queue went on without it. Its build log says how far it got. Tap Retry to build it again.";
                items.add(firstOpen(), i);
            } else {
                i.state = WAITING;
                items.add(firstOpen(), i);
            }
        }
        save();
    }

    private void load() {
        items.clear();
        if (file == null || !file.isFile()) return;
        List<String> lines;
        try { lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8); }
        catch (IOException | RuntimeException e) { damaged = true; return; }
        if (lines.isEmpty()) { damaged = true; return; }
        String[] h = lines.get(0).split("\t", -1);
        if (h.length < 4 || !MAGIC.equals(h[0]) || !String.valueOf(FORMAT).equals(h[1])) { damaged = true; return; }
        held = h.length > 4 && h[4].startsWith("held");
        if (held && h[4].startsWith("held:")) try { heldAt = Long.parseLong(h[4].substring(5)); } catch (NumberFormatException e) { heldAt = 0; }
        try { nextId = Math.max(1, Integer.parseInt(h[2])); batch = Math.max(1, Integer.parseInt(h[3])); }
        catch (NumberFormatException e) { damaged = true; return; }
        for (int k = 1; k < lines.size(); k++) {
            if (lines.get(k).isEmpty()) continue;
            Item i = parse(lines.get(k));
            if (i == null || get(i.id) != null) { damaged = true; continue; }
            items.add(i);
            nextId = Math.max(nextId, i.id + 1);
        }
        recover();
    }

    private static Item parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 20) return null;
        try {
            BuildRequest r = new BuildRequest();
            r.titleId = text(f[9]);
            r.name = text(f[10]);
            r.base = file(f[11]);
            r.update = file(f[12]);
            r.gameDir = file(f[13]);
            r.buildDestination = textOrNull(f[14]);
            r.companionPackage = textOrNull(f[15]);
            r.replaceNup = file(f[16]);
            r.deleteOldFirst = "1".equals(f[8]);
            for (int k = 20; k < f.length; k++) if (!f[k].isEmpty()) r.dlcs.add(new File(text(f[k])));
            if (r.titleId.isEmpty()) return null;
            Item i = new Item(Integer.parseInt(f[0]), Integer.parseInt(f[1]), r, "1".equals(f[7]));
            String s = f[2];
            if (!s.equals(WAITING) && !s.equals(RUNNING) && !s.equals(READY) && !s.equals(STOPPED)) return null;
            i.state = s;
            i.added = Long.parseLong(f[3]);
            i.started = Long.parseLong(f[4]);
            i.ended = Long.parseLong(f[5]);
            i.tries = Integer.parseInt(f[6]);
            i.code = text(f[17]);
            i.message = text(f[18]);
            i.step = text(f[19]);
            return i;
        } catch (RuntimeException e) {   // a number that does not read, an escape cut off
            return null;
        }
    }

    private static String line(Item i) {
        BuildRequest r = i.request;
        StringBuilder s = new StringBuilder();
        s.append(i.id).append('\t').append(i.batch).append('\t').append(i.state).append('\t').append(i.added).append('\t').append(i.started).append('\t').append(i.ended)
                .append('\t').append(i.tries).append('\t').append(i.buildAnyway ? 1 : 0).append('\t').append(r.deleteOldFirst ? 1 : 0);
        for (String v : new String[] {r.titleId, r.name, path(r.base), path(r.update), path(r.gameDir), r.buildDestination, r.companionPackage, path(r.replaceNup), i.code, i.message, i.step})
            s.append('\t').append(esc(v));
        for (File d : r.dlcs) if (d != null) s.append('\t').append(esc(d.getPath()));
        return s.toString();
    }

    /** The file written beside itself, then moved into place: a process killed while it writes leaves the last whole file */
    private void save() {
        if (file == null) return;
        saves++;
        StringBuilder s = new StringBuilder();
        s.append(MAGIC).append('\t').append(FORMAT).append('\t').append(nextId).append('\t').append(batch).append('\t').append(held ? "held:" + heldAt : "").append('\n');
        for (Item i : items) s.append(line(i)).append('\n');
        File tmp = new File(file.getPath() + ".part");
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            Files.write(tmp.toPath(), s.toString().getBytes(StandardCharsets.UTF_8));
            try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException | UnsupportedOperationException e) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException | RuntimeException e) {
            saveFailed = String.valueOf(e);
        }
    }

    /** How many times this queue wrote its file (BuildQueueJvmTest: Build all writes it once) */
    int saves;

    /** Why the last save failed (for the service's log), null when it did not */
    public volatile String saveFailed;

    private static String path(File f) { return f == null ? null : f.getPath(); }
    private static File file(String v) { String t = textOrNull(v); return t == null ? null : new File(t); }
    private static String text(String v) { String t = textOrNull(v); return t == null ? "" : t; }

    /** null is written as a lone "\0" (an empty value stays empty) */
    private static String esc(String v) {
        if (v == null) return "\\0";
        StringBuilder s = new StringBuilder(v.length());
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c == '\\') s.append("\\\\");
            else if (c == '\t') s.append("\\t");
            else if (c == '\n') s.append("\\n");
            else if (c == '\r') s.append("\\r");
            else s.append(c);
        }
        return s.toString();
    }

    private static String textOrNull(String v) {
        if (v.equals("\\0")) return null;
        StringBuilder s = new StringBuilder(v.length());
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            if (c != '\\') { s.append(c); continue; }
            if (++k >= v.length()) throw new IllegalArgumentException("an escape cut off");
            char e = v.charAt(k);
            s.append(e == 't' ? '\t' : e == 'n' ? '\n' : e == 'r' ? '\r' : e);
        }
        return s.toString();
    }

    /** The wait in words, for the notification and the Up next card: why, and that it goes on by itself */
    public static String waitText(String reason) {
        if (WAIT_HEAT.equals(reason)) return "The phone is hot. The next build starts when it has cooled down.";
        if (WAIT_BATTERY.equals(reason)) return "The battery is low. The next build starts when the phone charges.";
        if (WAIT_GAME.equals(reason)) return "A game is running. The next build starts when you close it.";
        if (WAIT_MODS.equals(reason)) return "A build with mods is running. The next build starts when it has finished.";
        if (WAIT_PACK.equals(reason)) return "Import pack is working on the next game. Its build starts when the import has finished.";
        return "";
    }

    private static String firstLine(String m) { return m == null ? "" : m.split("\n")[0].trim(); }

    private static String withStop(String s) { return s.endsWith(".") || s.endsWith("?") ? s : s + "."; }

    /** For tests and logs: the items as "id:titleId:state" */
    @Override public synchronized String toString() {
        List<String> l = new ArrayList<>();
        for (Item i : items) l.add(i.id + ":" + i.titleId() + ":" + i.state);
        return String.join(" ", l) + String.format(Locale.ROOT, " (batch %d)", batch);
    }
}
