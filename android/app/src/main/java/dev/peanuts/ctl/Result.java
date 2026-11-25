package dev.peanuts.ctl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a command came to, as status.json's last_result: the caller's token, the command, ok (null while the answer is still open: pending is then true),
 * a short error code with a sentence, and the time. A script waits for its own id with pending false.
 * Synchronized: a command that answers later (companion, share_build_log) fills its result on a thread of its own while the main thread copies it into the
 * status file as pending (Ctl.done right after the thread starts).
 */
public final class Result {
    public final String id, cmd;
    public final long time;
    public Boolean ok;
    public String error, message;
    public final Map<String, Object> data = new LinkedHashMap<>();

    public Result(Request r) { id = r.id; cmd = r.cmd; time = r.time; }

    public synchronized Result ok(String message) { ok = true; error = null; this.message = message; return this; }
    public synchronized Result fail(String code, String message) { ok = false; error = code; this.message = message; return this; }
    public synchronized Result put(String key, Object value) { data.put(key, value); return this; }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("cmd", cmd);
        m.put("ok", ok);
        m.put("pending", ok == null);
        m.put("error", error);
        m.put("message", message);
        m.put("time", time);
        if (!data.isEmpty()) m.put("data", new LinkedHashMap<>(data));
        return m;
    }
}
