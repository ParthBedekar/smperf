#ifndef PERF_H
#define PERF_H

#include <stdint.h>

#define PERF_EVENTS 4          /* cycles, instructions, cache refs, cache misses */
#define PERF_MAX_FDS 8         /* 4 events x (cpu_core + cpu_atom) */

struct perf_counters {
    int fd[PERF_MAX_FDS];
    int slot[PERF_MAX_FDS];
    int n;                     /* 0 = counters unavailable */
};

struct perf_values {
    int available;
    uint64_t cycles, instructions, cache_refs, cache_misses;
};

/* Opens counters on `pid` (call from the parent after fork, before the child execs).
 * On any failure n stays 0 and the run simply has no counters. */
void perf_open(struct perf_counters *pc, int pid);
void perf_read(const struct perf_counters *pc, struct perf_values *v);
void perf_close(struct perf_counters *pc);

#endif