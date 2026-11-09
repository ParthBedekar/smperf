/* topology_macos.c - detect_topology() for macOS, built on sysctlbyname().
 *
 * macOS does not expose a per-CPU topology table the way Linux sysfs does, so the
 * cpus[] list is synthesized from aggregate counts. Assumptions (documented in the
 * README's macOS notes):
 *
 *  - Logical ids are 0..logical_count-1, assigned perflevel by perflevel:
 *    hw.perflevel0 (Performance) first, then hw.perflevel1 (Efficiency), ...
 *    The real kernel numbering may differ, but since macOS cannot pin threads to
 *    CPUs the id -> type mapping is cosmetic.
 *  - On CPUs with SMT (Intel Macs) the hyperthreads of one core get adjacent ids.
 *  - Caches are reported per instance: L1 per physical core, L2 per group of
 *    hw.perflevelN.cpusperl2 (Apple Silicon) or per hw.cacheconfig[2] logical CPUs
 *    (Intel), L3 per hw.cacheconfig[3] logical CPUs when present.
 *  - max_khz comes from hw.cpufrequency_max on Intel; Apple Silicon does not expose
 *    it, so it is -1 (the UI shows "N/A").
 *  - Pinning is unsupported, so pinning_supported is 0.
 */
#include "include/topology.h"
#include "include/util.h"

#include <sys/types.h>
#include <sys/sysctl.h>

#define MAX_LEVELS 8

static int sysctl_i64(const char *name, int64_t *out) {
    int64_t v = 0;
    size_t len = sizeof v;
    if (sysctlbyname(name, &v, &len, NULL, 0) != 0) return -1;
    /* Some keys are 32-bit; sysctl fills only the first len bytes (little endian). */
    if (len == sizeof(int32_t)) v = (int32_t)v;
    *out = v;
    return 0;
}

static int64_t sysctl_or(const char *name, int64_t fallback) {
    int64_t v;
    return sysctl_i64(name, &v) == 0 ? v : fallback;
}

static int64_t level_value(int level, const char *key, int64_t fallback) {
    char name[64];
    snprintf(name, sizeof name, "hw.perflevel%d.%s", level, key);
    return sysctl_or(name, fallback);
}

static struct cpu_range *range(int start, int end) {
    struct cpu_range *r = malloc(sizeof *r);
    if (r != NULL) {
        r->start = start;
        r->end = end;
        r->next = NULL;
    }
    return r;
}

static int add_cache(struct machine *m, int level, const char *type,
                     int64_t size, int first_cpu, int last_cpu) {
    if (size <= 0) return 0;
    if (m->cache_count >= MAX_CACHES) return -1;

    struct cache_info *k = &m->caches[m->cache_count];
    k->level = level;
    snprintf(k->type, sizeof k->type, "%s", type);
    k->size_bytes = (uint64_t)size;
    k->shared_cpus = range(first_cpu, last_cpu);
    if (k->shared_cpus == NULL) return -1;
    m->cache_count++;
    return 0;
}

/* Emits one cache instance for every `share` consecutive logical CPUs in [first, last]. */
static int add_cache_groups(struct machine *m, int level, const char *type, int64_t size,
                            int first, int last, int share) {
    if (share < 1) share = 1;
    for (int c = first; c <= last; c += share) {
        int end = c + share - 1;
        if (end > last) end = last;
        if (add_cache(m, level, type, size, c, end) != 0) return -1;
    }
    return 0;
}

static void read_model(struct machine *m) {
    size_t len = sizeof m->model;
    if (sysctlbyname("machdep.cpu.brand_string", m->model, &len, NULL, 0) != 0 ||
        m->model[0] == '\0')
        snprintf(m->model, sizeof m->model, "unknown");
}

/* One perflevel (or the whole CPU when perflevels are not exposed). */
struct level_desc {
    int logical;
    int physical;
    enum core_type type;
    int64_t l1d, l1i, l2;
    int l2_share;              /* logical CPUs per L2 instance */
};

static int describe_levels(struct level_desc *lv, int *out_count) {
    int n = (int)sysctl_or("hw.nperflevels", 0);
    int64_t cachecfg[10] = {0};
    size_t cfglen = sizeof cachecfg;
    int have_cfg = sysctlbyname("hw.cacheconfig", cachecfg, &cfglen, NULL, 0) == 0;

    if (n >= 1 && n <= MAX_LEVELS) {
        /* Apple Silicon: one entry per performance level. */
        for (int i = 0; i < n; i++) {
            lv[i].logical  = (int)level_value(i, "logicalcpu", 0);
            lv[i].physical = (int)level_value(i, "physicalcpu", lv[i].logical);
            lv[i].type = n == 1 ? CORE_UNIFORM
                       : i == 0 ? CORE_PERFORMANCE : CORE_EFFICIENCY;
            lv[i].l1d = level_value(i, "l1dcachesize", 0);
            lv[i].l1i = level_value(i, "l1icachesize", 0);
            lv[i].l2  = level_value(i, "l2cachesize", 0);
            lv[i].l2_share = (int)level_value(i, "cpusperl2", lv[i].logical);
            if (lv[i].logical <= 0) return -1;
        }
        *out_count = n;
        return 0;
    }

    /* Intel Mac (or older macOS): a single uniform level. */
    lv[0].logical  = (int)sysctl_or("hw.logicalcpu", 0);
    lv[0].physical = (int)sysctl_or("hw.physicalcpu", lv[0].logical);
    if (lv[0].logical <= 0) return -1;
    lv[0].type = CORE_UNIFORM;
    lv[0].l1d = sysctl_or("hw.l1dcachesize", 0);
    lv[0].l1i = sysctl_or("hw.l1icachesize", 0);
    lv[0].l2  = sysctl_or("hw.l2cachesize", 0);
    lv[0].l2_share = have_cfg && cachecfg[2] > 0 ? (int)cachecfg[2] : 1;
    *out_count = 1;
    return 0;
}

int detect_topology(struct machine *m) {
    if (m == NULL) return -1;
    memset(m, 0, sizeof *m);

    read_model(m);
    m->ram_bytes = (uint64_t)sysctl_or("hw.memsize", 0);
    m->pinning_supported = 0;

    struct level_desc lv[MAX_LEVELS];
    int levels = 0;
    if (describe_levels(lv, &levels) != 0) return -1;

    /* Max clock: Intel Macs only. */
    int64_t hz = sysctl_or("hw.cpufrequency_max", -1);
    long max_khz = hz > 0 ? (long)(hz / 1000) : -1;

    int id = 0, core = 0;
    for (int l = 0; l < levels; l++) {
        int physical = lv[l].physical > 0 ? lv[l].physical : lv[l].logical;
        int smt = lv[l].logical / physical;
        if (smt < 1) smt = 1;
        int first = id;

        for (int p = 0; p < physical && id < first + lv[l].logical; p++, core++) {
            int sib_first = id;
            int sib_last = id + smt - 1;
            if (sib_last >= first + lv[l].logical) sib_last = first + lv[l].logical - 1;

            for (; id <= sib_last; id++) {
                if (id >= MAX_LOGICAL) { machine_free(m); return -1; }
                struct logical_cpu *c = &m->cpus[id];
                c->id = id;
                c->package = 0;
                c->core_id = core;
                c->type = lv[l].type;
                c->max_khz = max_khz;
                c->siblings = range(sib_first, sib_last);
                m->logical_count = id + 1;
                if (c->siblings == NULL) { machine_free(m); return -1; }
            }

            if (add_cache(m, 1, "Data", lv[l].l1d, sib_first, sib_last) != 0 ||
                add_cache(m, 1, "Instruction", lv[l].l1i, sib_first, sib_last) != 0) {
                machine_free(m);
                return -1;
            }
        }

        if (add_cache_groups(m, 2, "Unified", lv[l].l2, first, id - 1, lv[l].l2_share) != 0) {
            machine_free(m);
            return -1;
        }
    }

    /* L3: Intel Macs report hw.l3cachesize; Apple Silicon's system-level cache is not
     * exposed through sysctl, so nothing is emitted there. */
    int64_t l3 = sysctl_or("hw.l3cachesize", 0);
    if (l3 > 0) {
        int64_t cachecfg[10] = {0};
        size_t cfglen = sizeof cachecfg;
        int share = m->logical_count;
        if (sysctlbyname("hw.cacheconfig", cachecfg, &cfglen, NULL, 0) == 0 && cachecfg[3] > 0)
            share = (int)cachecfg[3];
        if (add_cache_groups(m, 3, "Unified", l3, 0, m->logical_count - 1, share) != 0) {
            machine_free(m);
            return -1;
        }
    }

    group_physical_cores(m);
    return 0;
}

