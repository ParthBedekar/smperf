/* topology_windows.c - detect_topology() for Windows, built on the Win32 API.
 *
 *  - Cores, SMT siblings, packages and caches come from GetLogicalProcessorInformationEx
 *    (RelationAll). Each RelationProcessorCore record is one physical core, each RelationCache
 *    record is one cache *instance*, so no de-duplication is needed.
 *  - Logical CPU ids are global indexes: processor_group * (bits per KAFFINITY) + bit. Windows
 *    splits machines with more than 64 logical CPUs into processor groups; run_once maps the
 *    ids back to (group, bit) when pinning. On machines with a single group, ids are simply
 *    0..N-1.
 *  - Hybrid CPUs: EfficiencyClass is 0 on every core of a non-hybrid CPU. On hybrid parts the
 *    highest class is "performance" and every lower class is "efficiency".
 *  - Max clock: CallNtPowerInformation(ProcessorInformation).MaxMhz, or -1 when unavailable.
 *    (Windows reports the rated maximum, which on many CPUs is the base clock, not the boost.)
 *  - Model: HKLM\HARDWARE\DESCRIPTION\System\CentralProcessor\0 ProcessorNameString.
 *  - Also adds "warnings" about conditions that make timings unreliable (battery power,
 *    Power saver plan), which the UI shows in the run log.
 */
#include "platform/windows/win_target.h"
#include <windows.h>
#include <powrprof.h>

#include "include/topology.h"
#include "include/util.h"

#define BITS_PER_GROUP ((int)(sizeof(KAFFINITY) * 8))

typedef struct {
    ULONG Number;
    ULONG MaxMhz;
    ULONG CurrentMhz;
    ULONG MhzLimit;
    ULONG MaxIdleState;
    ULONG CurrentIdleState;
} PPI;

static struct cpu_range *range_node(int start, int end) {
    struct cpu_range *r = malloc(sizeof *r);
    if (r != NULL) {
        r->start = start;
        r->end = end;
        r->next = NULL;
    }
    return r;
}

/* Appends the CPU ids of one group's affinity mask to a range list (merging runs). */
static int append_mask(struct cpu_range **head, struct cpu_range **tail, WORD group, KAFFINITY mask) {
    for (int bit = 0; bit < BITS_PER_GROUP; bit++) {
        if (!(mask & ((KAFFINITY)1 << bit))) continue;
        int id = group * BITS_PER_GROUP + bit;
        if (*tail != NULL && (*tail)->end + 1 == id) {
            (*tail)->end = id;
            continue;
        }
        struct cpu_range *r = range_node(id, id);
        if (r == NULL) return -1;
        if (*tail != NULL) (*tail)->next = r; else *head = r;
        *tail = r;
    }
    return 0;
}

static struct cpu_range *ranges_from_masks(const GROUP_AFFINITY *ga, int count) {
    struct cpu_range *head = NULL, *tail = NULL;
    for (int i = 0; i < count; i++) {
        if (append_mask(&head, &tail, ga[i].Group, ga[i].Mask) != 0) {
            cpulist_free(head);
            return NULL;
        }
    }
    return head;
}

static PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX load_info(DWORD *len) {
    DWORD size = 0;
    GetLogicalProcessorInformationEx(RelationAll, NULL, &size);
    if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || size == 0) return NULL;

    PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX buf = malloc(size);
    if (buf == NULL) return NULL;
    if (!GetLogicalProcessorInformationEx(RelationAll, buf, &size)) {
        free(buf);
        return NULL;
    }
    *len = size;
    return buf;
}

static const char *cache_type_name(PROCESSOR_CACHE_TYPE t) {
    switch (t) {
        case CacheUnified:     return "Unified";
        case CacheInstruction: return "Instruction";
        case CacheData:        return "Data";
        case CacheTrace:       return "Trace";
        default:               return "unknown";
    }
}

static int read_cpus_and_caches(struct machine *m) {
    DWORD len = 0;
    PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX info = load_info(&len);
    if (info == NULL) return -1;
    BYTE *base = (BYTE *)info, *end = base + len;

    int present[MAX_LOGICAL] = {0};
    int core_of[MAX_LOGICAL] = {0};
    int pkg_of[MAX_LOGICAL] = {0};
    int cls_of[MAX_LOGICAL] = {0};
    struct cpu_range *sib[MAX_LOGICAL] = {0};
    int core_idx = 0, pkg_idx = 0, rc = -1;

    /* ---- pass 1: cores and packages */
    for (BYTE *p = base; p < end; ) {
        PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX rec = (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)p;
        if (rec->Size == 0) goto out;
        p += rec->Size;

        if (rec->Relationship != RelationProcessorCore && rec->Relationship != RelationProcessorPackage)
            continue;

        const PROCESSOR_RELATIONSHIP *pr = &rec->Processor;
        for (int g = 0; g < pr->GroupCount; g++) {
            KAFFINITY mask = pr->GroupMask[g].Mask;
            for (int bit = 0; bit < BITS_PER_GROUP; bit++) {
                if (!(mask & ((KAFFINITY)1 << bit))) continue;
                int id = pr->GroupMask[g].Group * BITS_PER_GROUP + bit;
                if (id >= MAX_LOGICAL) goto out;              /* more CPUs than the engine supports */

                if (rec->Relationship == RelationProcessorCore) {
                    present[id] = 1;
                    core_of[id] = core_idx;
                    cls_of[id]  = pr->EfficiencyClass;
                    sib[id] = ranges_from_masks(pr->GroupMask, pr->GroupCount);
                } else {
                    pkg_of[id] = pkg_idx;
                }
            }
        }
        if (rec->Relationship == RelationProcessorCore) core_idx++; else pkg_idx++;
    }

    /* ---- logical CPUs in ascending id order */
    int n = 0;
    for (int id = 0; id < MAX_LOGICAL; id++) {
        if (!present[id]) continue;
        struct logical_cpu *c = &m->cpus[n++];
        c->id = id;
        c->package = pkg_of[id];
        c->core_id = core_of[id];
        c->max_khz = -1;
        c->siblings = sib[id];
        sib[id] = NULL;                                     /* ownership moved to the machine */
        c->type = CORE_UNIFORM;
        m->logical_count = n;
    }
    if (n == 0) goto out;

    /* ---- hybrid classification from EfficiencyClass */
    int lo = 1 << 30, hi = -1;
    for (int id = 0; id < MAX_LOGICAL; id++) {
        if (!present[id]) continue;
        if (cls_of[id] < lo) lo = cls_of[id];
        if (cls_of[id] > hi) hi = cls_of[id];
    }
    if (hi > lo) {
        for (int i = 0; i < n; i++)
            m->cpus[i].type = cls_of[m->cpus[i].id] == hi ? CORE_PERFORMANCE : CORE_EFFICIENCY;
    }

    /* ---- pass 2: caches (one record per instance) */
    for (BYTE *p = base; p < end; ) {
        PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX rec = (PSYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX)p;
        p += rec->Size;
        if (rec->Relationship != RelationCache) continue;

        if (m->cache_count >= MAX_CACHES) goto out;
        struct cache_info *k = &m->caches[m->cache_count];
        k->level = rec->Cache.Level;
        snprintf(k->type, sizeof k->type, "%s", cache_type_name(rec->Cache.Type));
        k->size_bytes = rec->Cache.CacheSize;
        k->shared_cpus = ranges_from_masks(&rec->Cache.GroupMask, 1);
        if (k->shared_cpus == NULL) continue;               /* empty mask: skip the record */
        m->cache_count++;
    }
    rc = 0;

out:
    for (int id = 0; id < MAX_LOGICAL; id++) cpulist_free(sib[id]);
    free(info);
    return rc;
}

static void read_model(struct machine *m) {
    char buf[128];
    DWORD size = sizeof buf;
    m->model[0] = '\0';
    if (RegGetValueA(HKEY_LOCAL_MACHINE, "HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                     "ProcessorNameString", RRF_RT_REG_SZ, NULL, buf, &size) == ERROR_SUCCESS) {
        const char *s = buf;
        while (*s == ' ') s++;                              /* Intel pads the name with spaces */
        snprintf(m->model, sizeof m->model, "%s", s);
        for (size_t n = strlen(m->model); n > 0 && m->model[n - 1] == ' '; n--) m->model[n - 1] = '\0';
    }
    if (m->model[0] == '\0') snprintf(m->model, sizeof m->model, "unknown");
}

static void read_ram(struct machine *m) {
    ULONGLONG kb = 0;
    m->ram_bytes = 0;
    if (GetPhysicallyInstalledSystemMemory(&kb) && kb > 0) {
        m->ram_bytes = (uint64_t)kb * 1024ULL;
        return;
    }
    MEMORYSTATUSEX ms;
    ms.dwLength = sizeof ms;
    if (GlobalMemoryStatusEx(&ms)) m->ram_bytes = ms.ullTotalPhys;
}

static void read_clocks(struct machine *m) {
    DWORD count = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    if (count == 0 || count > 4096) return;

    PPI *ppi = calloc(count, sizeof *ppi);
    if (ppi == NULL) return;

    /* Entries are in processor order (group 0 first), which is ascending CPU id. */
    if (CallNtPowerInformation(ProcessorInformation, NULL, 0, ppi, count * sizeof *ppi) == 0) {
        for (int i = 0; i < m->logical_count && (DWORD)i < count; i++)
            if (ppi[i].MaxMhz > 0) m->cpus[i].max_khz = (long)ppi[i].MaxMhz * 1000L;
    }
    free(ppi);
}

/* Power plan GUIDs: Power saver / Balanced / High performance. */
static const GUID PLAN_SAVER = {0xa1841308, 0x3541, 0x4fab, {0xbc, 0x81, 0xf7, 0x15, 0x56, 0xf2, 0x0b, 0x4a}};

static void read_power_warnings(struct machine *m) {
    SYSTEM_POWER_STATUS sps;
    if (GetSystemPowerStatus(&sps) && sps.ACLineStatus == 0)
        machine_add_warning(m, "Running on battery power: the CPU may be throttled and timings will be unreliable. Plug in before benchmarking.");

    GUID *scheme = NULL;
    if (PowerGetActiveScheme(NULL, &scheme) == ERROR_SUCCESS && scheme != NULL) {
        if (IsEqualGUID(scheme, &PLAN_SAVER))
            machine_add_warning(m, "Windows power plan is \"Power saver\": CPU clocks are capped. Switch to Balanced or High performance for steadier results.");
        LocalFree(scheme);
    }
}

int detect_topology(struct machine *m) {
    if (m == NULL) return -1;
    memset(m, 0, sizeof *m);

    read_model(m);
    read_ram(m);

    if (read_cpus_and_caches(m) != 0) {
        machine_free(m);
        return -1;
    }

    group_physical_cores(m);          /* dense physical_core from (package, core_id) */
    read_clocks(m);
    read_power_warnings(m);
    m->pinning_supported = 1;         /* Job Object / process affinity */
    return 0;
}
