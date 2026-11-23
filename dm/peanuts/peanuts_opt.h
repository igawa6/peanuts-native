// PEANUTS: experimental optimizations behind switches (peanuts/docs/IDEAS.md lists every idea and where it stands). Each one is off unless its default says
// otherwise, so a build carrying all of them behaves like the build before them until one is switched on. A switch is read from the PN_OPTS environment variable
// (or the spec given to pn_opt_init): names separated by commas, spaces or semicolons, applied in order. "name" switches one on, "-name" or "!name" off,
// "all" every one on, "none" every one off, "default" back to the table's defaults. Unknown names are counted and ignored.
#pragma once
#include <stddef.h>

// X(ID, "name", default_on, what it is). Keep the ideas' numbers from IDEAS.md in the last field
#define PN_OPT_LIST(X) \
    X(MEM_WATCH,  "mem_watch",  0, "ideas 11, 60: read the memory pressure (PSI, else /proc/meminfo) and give a level that caches shrink on") \
    X(BOTTLENECK, "bottleneck", 0, "ideas 72, 73: classify the late frames as CPU, GPU or present limited")

typedef enum {
#define X(id, name, def, doc) PN_OPT_##id,
    PN_OPT_LIST(X)
#undef X
    PN_OPT_COUNT
} pn_opt_id;

// Reads the spec (NULL: getenv("PN_OPTS"), none set: the defaults). Replaces what was set before. Returns the number of unknown names in it
int pn_opt_init(const char *spec);
// Whether the option is on. Initializes from PN_OPTS on the first call when pn_opt_init was not called; out-of-range ids are off
int pn_opt_enabled(pn_opt_id id);
// Switches one option by name: 0 when it exists, -1 when not
int pn_opt_set(const char *name, int on);
// The table, for the launcher and the logs: i in [0, PN_OPT_COUNT)
const char *pn_opt_name(int i);
const char *pn_opt_doc(int i);
int pn_opt_default(int i);
