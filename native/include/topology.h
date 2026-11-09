// topology.h
#ifndef TOPOLOGY_H
#define TOPOLOGY_H

#include <stdio.h>
#include <stdint.h>
#include "util.h"
#define SYS_CPU "/sys/devices/system/cpu"
#define MAX_LOGICAL 256
#define MAX_CACHES  512

enum core_type { CORE_UNIFORM, CORE_PERFORMANCE, CORE_EFFICIENCY };

struct logical_cpu {
    int id;
    int package;
    int core_id;
    int physical_core;
    enum core_type type;
    long max_khz;
    struct cpu_range *siblings;
};

struct cache_info {
    int level;
    char type[16];
    uint64_t size_bytes;
    struct cpu_range *shared_cpus;
};

struct machine {
    char model[128];
    uint64_t ram_bytes;
    int logical_count;
    int physical_count;
    int pinning_supported;     /* 1 if run_once can really pin to cpus (emitted as "pinning") */
    struct logical_cpu cpus[MAX_LOGICAL];
    int cache_count;
    struct cache_info caches[MAX_CACHES];
};

int  detect_topology(struct machine *m);
void machine_free(struct machine *m);
void machine_print_json(const struct machine *m, FILE *out);

void group_physical_cores(struct machine *m);
void classify_from_lists(struct machine *m,
                         const struct cpu_range *perf,
                         const struct cpu_range *eff);
void classify_from_freq(struct machine *m);
void classify_core_types(struct machine *m);

#endif
