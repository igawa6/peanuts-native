// PEANUTS: thread placement by name (MD1, ideas 3, 7, 21, 54, 57; DM_THREADS): a table of rules, each naming a family of the host's threads by the start of its name (the
// names the host gives them: vk-replay, vk-enc, vk-present, vk-prewarm, vk-psoasync, vk-upload, "dm movie pump", ...) and what to do to every thread of it: the CPUs it may run on,
// its nice value and its I/O priority. Off unless DM_THREADS is set. Format, rules separated by ";":
//     <name start>:<cpu mask>[:<nice>[:<io>]]       e.g. vk-prewarm:0x0f:10;vk-psoasync:0x0f:10;vk-replay:0xf0:-4
// An empty field leaves that one alone. The mask is a number (decimal or 0x hex, bit n = CPU n; 0xf0 = CPUs 4 to 7). io is idle, be0 to be7 or rt0 to rt7 (rt may be refused
// to an app). The first rule whose name start begins the thread's name applies. The parsing and matching are plain functions (tested on a PC)
#pragma once
#include <stdint.h>

#define PN_THR_MAX_RULES 16
typedef struct {
    char prefix[16];
    int has_mask; uint64_t mask;
    int has_nice; int nice;
    int has_io; int io_class, io_level;   // class 1 realtime, 2 best effort, 3 idle (the kernel's)
} pn_thr_rule;

// Parses the spec into out (at most max rules); returns the rules read. *bad (when not NULL) counts the rules that were wrong and left out. NULL or empty spec: 0
int pn_thr_parse(const char *spec, pn_thr_rule *out, int max, int *bad);
// The first rule whose prefix begins comm (a trailing newline is ignored); NULL when none
const pn_thr_rule *pn_thr_match(const pn_thr_rule *rules, int n, const char *comm);
// Applies the rules to this process's threads that match and were not done yet (by thread id); returns how many it did. Reads /proc/self/task
int pn_thr_scan(const pn_thr_rule *rules, int n);
// Reads DM_THREADS once, then about once a second scans; cheap to call every frame
void pn_thr_tick(void);
