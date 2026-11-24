// PEANUTS: where the CPU time goes (idea 8, MD1; DM_PC_SAMPLE=<Hz>): the process's running threads are sampled by the profiling timer (SIGPROF, CPU time), the interrupted
// program counter is kept in a ring, and every DM_PC_SAMPLE_DUMP_S seconds (default 10) a thread logs the share of samples in the game's memory against the rest of the host,
// and the busiest 64-byte blocks with the map region they are in. Off unless DM_PC_SAMPLE is set. The aggregation and the map lookup are plain functions (tested on a PC)
#pragma once
#include <stddef.h>
#include <stdint.h>

// Reads DM_PC_SAMPLE (20 to 1000 Hz; unset, 0 or anything else: nothing) once and starts the sampler. Cheap to call every frame
void pn_pcsamp_start_once(void);

typedef struct { uint64_t block; uint32_t count; } pn_pc_bin;   // block = pc >> shift
// The `max_out` most frequent blocks of the n samples, by count (ties: the lower block first). Returns the number written. shift 0 to 63; out may not be NULL
int pn_pc_top(const uint64_t *pcs, int n, unsigned shift, pn_pc_bin *out, int max_out);
// The line of a /proc/self/maps text that holds addr: its start, its end, and its name (the path or the bracketed name; "" for an anonymous map with none) in name. 0 when none
int pn_maps_find(const char *maps, uint64_t addr, uint64_t *start, uint64_t *end, char *name, size_t cap);
