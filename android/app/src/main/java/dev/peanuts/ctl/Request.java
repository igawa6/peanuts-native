package dev.peanuts.ctl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One command as the intent carried it: the extras as text (am's -e, --es, --ei and --ez all read as their text), the command's name, the caller's token.
 * {@link #check} refuses what a command cannot use before anything runs. No Android class here: the JVM tests drive it.
 */
public final class Request {
    /** A refusal: a short code for scripts and a sentence for people */
    public static final class Bad extends Exception {
        public final String code;
        public Bad(String code, String message) { super(message); this.code = code; }
    }

    public static final List<String> COMMANDS = Arrays.asList("status", "list", "scan", "nav", "build", "reimport", "prepare", "job", "play", "game", "screens_off", "set", "companion", "share_build_log");
    static final List<String> FILTERS = Arrays.asList("all", "building", "ready", "build", "not_in_app");
    static final List<String> COMPANION_ACTIONS = Arrays.asList("list", "install", "update", "remove");
    static final List<String> TABS = Arrays.asList("performance", "game", "saves", "storage");
    private static final Set<String> YES = new HashSet<>(Arrays.asList("true", "1", "on", "yes")), NO = new HashSet<>(Arrays.asList("false", "0", "off", "no"));

    public final String cmd, id;
    public final long time;
    private final Map<String, String> args = new LinkedHashMap<>();

    /** extras: every extra of the intent, its value as text. "id" (or "req") is the caller's own token, given back in last_result */
    public Request(Map<String, String> extras, long time) {
        for (Map.Entry<String, String> e : extras.entrySet()) if (e.getKey() != null && e.getValue() != null) args.put(e.getKey(), e.getValue().trim());
        String c = args.get("cmd");
        cmd = c == null ? "" : c.toLowerCase(Locale.ROOT);
        String i = args.get("id");
        id = i != null && !i.isEmpty() ? i : args.get("req");
        this.time = time;
    }

    /** An argument's text, null when it was not given or is empty */
    public String str(String key) {
        String v = args.get(key);
        return v == null || v.isEmpty() ? null : v;
    }

    /** An argument that names one of a few words, in lower case; null when it was not given */
    public String word(String key, List<String> allowed) throws Bad {
        String v = str(key);
        if (v == null) return null;
        v = v.toLowerCase(Locale.ROOT);
        if (!allowed.contains(v)) throw new Bad("bad_args", key + " is " + String.join(", ", allowed) + ", not " + v);
        return v;
    }

    public String need(String key) throws Bad {
        String v = str(key);
        if (v == null) throw new Bad("bad_args", cmd + " needs -e " + key + " <value>");
        return v;
    }

    /** The title ID (16 hex digits, given back in upper case); null when optional and not given */
    public String titleId(boolean required) throws Bad {
        String v = required ? need("title_id") : str("title_id");
        if (v == null) return null;
        if (!v.matches("[0-9A-Fa-f]{16}")) throw new Bad("bad_args", "title_id is 16 hex digits, not " + v);
        return v.toUpperCase(Locale.ROOT);
    }

    /** true / false for an argument given as true, 1, on, yes or false, 0, off, no; null when it was not given */
    public Boolean flag(String key) throws Bad {
        String v = str(key);
        if (v == null) return null;
        v = v.toLowerCase(Locale.ROOT);
        if (YES.contains(v)) return true;
        if (NO.contains(v)) return false;
        throw new Bad("bad_args", key + " is on or off, not " + v);
    }

    public boolean on(String key) throws Bad { return Boolean.TRUE.equals(flag(key)); }

    /** What the job, game and companion commands are asked to do (job's stop is given back as pause); null for the other commands */
    public String action() throws Bad {
        if (cmd.equals("job")) {
            need("do");
            String a = word("do", Arrays.asList("pause", "stop", "resume", "retry", "try_anyway"));
            return "stop".equals(a) ? "pause" : a;   // one button: the build card calls it Stop, the Prepare card Pause
        }
        if (cmd.equals("game")) { need("do"); return word("do", Arrays.asList("stop", "ctl")); }
        if (cmd.equals("companion")) { need("do"); return word("do", COMPANION_ACTIONS); }
        return null;
    }

    /** A game's companion against the catalog: none (the catalog has none and none is installed), not_installed, installed, update */
    public static String companionState(String catalog, String installed) {
        if (catalog == null) return installed == null ? "none" : "installed";
        if (installed == null) return "not_installed";
        return installed.equals(catalog) ? "installed" : "update";
    }

    /** Refuses a command this version does not have, or one whose arguments are missing or malformed */
    public void check() throws Bad {
        if (cmd.isEmpty()) throw new Bad("bad_args", "no command: -e cmd <name> (" + String.join(", ", COMMANDS) + ")");
        if (!COMMANDS.contains(cmd)) throw new Bad("unknown_command", cmd + " is not a command (" + String.join(", ", COMMANDS) + ")");
        if (id != null && id.length() > 64) throw new Bad("bad_args", "id is at most 64 characters");
        flag("force");
        switch (cmd) {
            case "nav": {
                need("to");
                String to = word("to", Arrays.asList("home", "settings", "game"));
                word("filter", FILTERS);
                word("tab", TABS);
                if (to.equals("game")) titleId(true);
                break;
            }
            case "build": case "prepare": case "play": case "share_build_log": titleId(true); break;
            case "reimport": titleId(true); flag("confirm"); flag("delete_first"); break;   // the question itself is answered where the plan is known (CtlPort.reimport: needs_confirm)
            case "job": action(); titleId(false); break;
            case "game": {
                String a = action();
                if (a.equals("ctl")) {
                    String line = need("line");
                    if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) throw new Bad("bad_args", "line is one line");
                } else if (!on("confirm")) throw new Bad("needs_confirm", "game stop ends the game and unsaved progress is lost: add -e confirm true");
                break;
            }
            case "screens_off": if (flag("on") == null) throw new Bad("bad_args", "screens_off needs -e on true or -e on false"); break;
            case "set": need("key"); need("value"); titleId(false); break;
            case "companion": {
                String a = action();
                titleId(!a.equals("list"));
                if (a.equals("remove") && !on("confirm")) throw new Bad("needs_confirm", "companion remove deletes the game's companion package: add -e confirm true");
                break;
            }
            default: break;
        }
    }

    /** Whether the command works on the launcher's screen (its pages, the build sheet's choices, the Prepare card), so the launcher has to be in front */
    public boolean needsScreen() throws Bad {
        switch (cmd) {
            case "nav": case "build": case "reimport": case "prepare": case "play": case "scan": return true;
            case "screens_off": return on("on");
            case "job": return !"pause".equals(action());
            default: return false;
        }
    }

    /** The command in one line for the log: the names of its arguments, not their values (a value can be a path or a control line) */
    public String logLine() { return "cmd=" + cmd + (id == null ? "" : " id=" + id) + " args=" + new java.util.TreeSet<>(args.keySet()); }
}
