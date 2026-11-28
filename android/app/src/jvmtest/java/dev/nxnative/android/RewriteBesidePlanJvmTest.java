package dev.nxnative.android;

import java.util.List;

/** PEANUTS (issue #120): RewriteBesidePlan on the JVM (dm/tools/tests/test_android_rewrite_beside.py). Prints "name value" lines; the Python test checks them. */
public class RewriteBesidePlanJvmTest {
    static void out(String name, Object v) { System.out.println(name + " " + v); }

    public static void main(String[] a) {
        String lib = "/data/app/x/lib/arm64/libpython3.14.so";
        out("16gb", RewriteBesidePlan.allowed(false, false, lib, 4, 0, "pending", "pending", null));
        out("16gb_off", RewriteBesidePlan.allowed(false, false, lib, 4, 0, "pending", "pending", "0"));
        out("16gb_on", RewriteBesidePlan.allowed(false, false, lib, 4, 0, "pending", "pending", "1"));
        out("12gb", RewriteBesidePlan.allowed(false, false, lib, 3, 0, "pending", "pending", null));
        out("8gb", RewriteBesidePlan.allowed(false, false, lib, 2, 0, "pending", "pending", null));
        out("killed_before", RewriteBesidePlan.allowed(false, false, lib, 4, 1, "pending", "pending", null));
        out("checking", RewriteBesidePlan.allowed(true, false, lib, 4, 0, "pending", "pending", null));
        out("simulated", RewriteBesidePlan.allowed(false, true, lib, 4, 0, "pending", "pending", null));
        out("no_python", RewriteBesidePlan.allowed(false, false, null, 4, 0, "pending", "pending", null));
        out("split_done", RewriteBesidePlan.allowed(false, false, lib, 4, 0, "done", "pending", null));
        out("both_done", RewriteBesidePlan.allowed(false, false, lib, 4, 0, "done", "done", null));
        List<String> c = RewriteBesidePlan.command("/system/bin/linker64 /data/x/python/bin/nxpack_worker", "/w/ondevice/rewrite-beside.pid",
                "/repo/scripts/pack_android.py", new String[] {"/g.nup", "--version", "65536", "--code-only"});
        out("cmd_shell", c.get(0) + "|" + c.get(1) + "|" + c.get(3));
        out("cmd_worker", c.get(4) + "|" + c.get(5) + "|" + c.get(6));
        out("cmd_tool", c.get(8) + "|" + String.join(",", c.subList(9, c.size())));
        out("cmd_boot_runs_tool", c.get(7).contains("runpy.run_path(sys.argv[0], run_name='__main__')"));
        out("cmd_no_worker", RewriteBesidePlan.command("", "/p", "/t", new String[0]));
    }
}
