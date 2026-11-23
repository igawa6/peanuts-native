// PEANUTS: how short of memory the phone is, for caches that can shrink (idea 11, 60; switch PN_OPT_MEM_WATCH). Two sources: the kernel's pressure stall
// information (/proc/pressure/memory), which stock Android may not let an app read, and /proc/meminfo's MemAvailable, which it does. The thresholds are starting
// points taken from how lmkd and apps commonly read these numbers, not measurements on a phone: tune them with the telemetry before relying on them
#pragma once
#include <stdint.h>

typedef struct {
    float some10, some60, some300;   // share of time (percent) some task waited for memory, averaged over 10, 60 and 300 s
    float full10, full60, full300;   // the same for all non-idle tasks at once
    uint64_t some_total_us, full_total_us;
    int have_some, have_full;
} pn_psi;

typedef struct { uint64_t total_kb, avail_kb, swap_total_kb, swap_free_kb; } pn_meminfo;

enum { PN_MEM_OK = 0, PN_MEM_MILD = 1, PN_MEM_SEVERE = 2 };

// Parses the text of /proc/pressure/memory: 0 when the "some" line was read (the "full" line is optional), -1 otherwise. out is zeroed first
int pn_psi_parse(const char *text, pn_psi *out);
// Parses the text of /proc/meminfo: 0 when MemTotal and MemAvailable were read, -1 otherwise (the swap lines are optional). out is zeroed first
int pn_meminfo_parse(const char *text, pn_meminfo *out);
// The level of either source, the higher one; a NULL source counts as OK.
// PSI: severe at full10 >= 5 or some10 >= 25; mild at full10 >= 1 or some10 >= 8. Meminfo: severe below 8 % of MemTotal available, mild below 15 %
int pn_mem_level(const pn_psi *psi, const pn_meminfo *mi);
// Reads the two files, parses what it can and returns the level (OK when neither could be read). NULL paths: the system's own. Sets *sources to a bitmask
// (1 PSI, 2 meminfo) when not NULL
int pn_mem_read(const char *psi_path, const char *meminfo_path, int *sources);
// pn_mem_read on the system's files, only while PN_OPT_MEM_WATCH is on (OK otherwise); the level is also kept for pn_mem_last. Meant for a low-rate tick
int pn_mem_poll(void);
// The level of the last pn_mem_poll (OK before any)
int pn_mem_last(void);
