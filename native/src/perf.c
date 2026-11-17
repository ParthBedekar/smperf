#include "include/perf.h"
#include <string.h>

#ifdef __linux__
#include <linux/perf_event.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>

static const uint64_t CFG[PERF_EVENTS] = {
    PERF_COUNT_HW_CPU_CYCLES, PERF_COUNT_HW_INSTRUCTIONS,
    PERF_COUNT_HW_CACHE_REFERENCES, PERF_COUNT_HW_CACHE_MISSES
};

static int read_pmu_type(const char *name, uint32_t *type) {
    char path[128];
    snprintf(path, sizeof path, "/sys/devices/%s/type", name);
    FILE *f = fopen(path, "r");
    if (f == NULL) return -1;
    unsigned t;
    int ok = fscanf(f, "%u", &t) == 1;
    fclose(f);
    if (!ok) return -1;
    *type = t;
    return 0;
}

static int open_event(uint32_t pmu_type, uint64_t config, int pid) {
    struct perf_event_attr a;
    memset(&a, 0, sizeof a);
    a.size = sizeof a;
    a.type = PERF_TYPE_HARDWARE;
    a.config = config | ((uint64_t)pmu_type << 32);   /* pmu_type 0 on non-hybrid CPUs */
    a.disabled = 1;
    a.inherit = 1;            /* count the benchmark's threads too */
    a.enable_on_exec = 1;     /* start at exec, so the engine itself is not counted */
    a.exclude_kernel = 1;     /* works with perf_event_paranoid = 2 */
    a.exclude_hv = 1;
    return (int)syscall(SYS_perf_event_open, &a, pid, -1, -1, 0);
}

void perf_open(struct perf_counters *pc, int pid) {
    pc->n = 0;
    uint32_t types[2];
    int nt = 0;
    if (read_pmu_type("cpu_core", &types[nt]) == 0) nt++;
    if (read_pmu_type("cpu_atom", &types[nt]) == 0) nt++;
    if (nt == 0) types[nt++] = 0;

    for (int t = 0; t < nt; t++) {
        for (int e = 0; e < PERF_EVENTS; e++) {
            int fd = open_event(types[t], CFG[e], pid);
            if (fd < 0) { perf_close(pc); return; }   /* all or nothing */
            pc->fd[pc->n] = fd;
            pc->slot[pc->n] = e;
            pc->n++;
        }
    }
}

void perf_read(const struct perf_counters *pc, struct perf_values *v) {
    memset(v, 0, sizeof *v);
    if (pc->n == 0) return;
    uint64_t sum[PERF_EVENTS] = {0};
    for (int i = 0; i < pc->n; i++) {
        uint64_t val;
        if (read(pc->fd[i], &val, sizeof val) != (ssize_t)sizeof val) return;
        sum[pc->slot[i]] += val;
    }
    v->available = 1;
    v->cycles = sum[0];
    v->instructions = sum[1];
    v->cache_refs = sum[2];
    v->cache_misses = sum[3];
}

void perf_close(struct perf_counters *pc) {
    for (int i = 0; i < pc->n; i++) close(pc->fd[i]);
    pc->n = 0;
}

#else   /* Windows, macOS: no counters */

void perf_open(struct perf_counters *pc, int pid) { (void)pid; pc->n = 0; }
void perf_read(const struct perf_counters *pc, struct perf_values *v) {
    (void)pc;
    memset(v, 0, sizeof *v);
}
void perf_close(struct perf_counters *pc) { pc->n = 0; }

#endif