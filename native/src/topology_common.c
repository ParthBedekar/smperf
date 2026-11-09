#include "include/topology.h"
#include "include/util.h"

/* OS-independent topology helpers shared by every platform's detect_topology(). */

void group_physical_cores(struct machine *m) {
    int n = m->logical_count;
    int next = 0;

    for (int i = 0; i < n; i++)
        m->cpus[i].physical_core = -1;

    for (int i = 0; i < n; i++) {
        if (m->cpus[i].physical_core != -1) continue;

        m->cpus[i].physical_core = next;
        for (int j = i + 1; j < n; j++) {
            if (m->cpus[j].physical_core == -1 &&
                m->cpus[j].package == m->cpus[i].package &&
                m->cpus[j].core_id == m->cpus[i].core_id) {
                m->cpus[j].physical_core = next;
                }
        }
        next++;
    }
    m->physical_count = next;
}

void classify_from_lists(struct machine *m, const struct cpu_range *perf, const struct cpu_range *eff) {
    for (int i = 0; i < m->logical_count; i++) {
        int id = m->cpus[i].id;
        if (cpulist_contains(perf, id))
            m->cpus[i].type = CORE_PERFORMANCE;
        else if (cpulist_contains(eff, id))
            m->cpus[i].type = CORE_EFFICIENCY;
        else
            m->cpus[i].type = CORE_UNIFORM;
    }
}

void classify_from_freq(struct machine *m) {
    int n = m->logical_count;
    if (n == 0) return;

    long lo = m->cpus[0].max_khz;
    long hi = m->cpus[0].max_khz;
    int unknown = 0;

    for (int i = 0; i < n; i++) {
        long f = m->cpus[i].max_khz;
        if (f < 0) unknown = 1;
        if (f < lo) lo = f;
        if (f > hi) hi = f;
    }

    int uniform = unknown || lo == hi;
    for (int i = 0; i < n; i++) {
        if (uniform)
            m->cpus[i].type = CORE_UNIFORM;
        else if (m->cpus[i].max_khz == hi)
            m->cpus[i].type = CORE_PERFORMANCE;
        else
            m->cpus[i].type = CORE_EFFICIENCY;
    }
}

void machine_free(struct machine *m) {
    if (m == NULL) return;
    for (int i = 0; i < m->logical_count; i++) {
        cpulist_free(m->cpus[i].siblings);
        m->cpus[i].siblings = NULL;
    }
    for (int i = 0; i < m->cache_count; i++) {
        cpulist_free(m->caches[i].shared_cpus);
        m->caches[i].shared_cpus = NULL;
    }
}

static const char *type_name(enum core_type t) {
    switch (t) {
        case CORE_PERFORMANCE: return "performance";
        case CORE_EFFICIENCY:  return "efficiency";
        default:               return "uniform";
    }
}

static void print_json_string(FILE *out, const char *s) {
    fputc('"', out);
    for (; *s; s++) {
        unsigned char c = (unsigned char)*s;
        switch (c) {
            case '"':  fputs("\\\"", out); break;
            case '\\': fputs("\\\\", out); break;
            case '\n': fputs("\\n", out);  break;
            case '\r': fputs("\\r", out);  break;
            case '\t': fputs("\\t", out);  break;
            default:
                if (c < 0x20) fprintf(out, "\\u%04x", c);
                else fputc(c, out);
        }
    }
    fputc('"', out);
}

static void print_json_cpulist(FILE *out, const struct cpu_range *list) {
    int first = 1;
    fputc('[', out);
    for (; list != NULL; list = list->next) {
        for (int c = list->start; c <= list->end; c++) {
            if (!first) fputs(", ", out);
            fprintf(out, "%d", c);
            first = 0;
        }
    }
    fputc(']', out);
}

void machine_print_json(const struct machine *m, FILE *out) {
    if (m == NULL || out == NULL) return;

    fputs("{\n  \"model\": ", out);
    print_json_string(out, m->model);
    fprintf(out, ",\n  \"ram_bytes\": %llu,\n", (unsigned long long)m->ram_bytes);
    fprintf(out, "  \"logical_count\": %d,\n", m->logical_count);
    fprintf(out, "  \"physical_count\": %d,\n", m->physical_count);
    fprintf(out, "  \"pinning\": \"%s\",\n", m->pinning_supported ? "supported" : "unsupported");

    fputs("  \"cpus\": [", out);
    for (int i = 0; i < m->logical_count; i++) {
        const struct logical_cpu *c = &m->cpus[i];
        fputs(i ? ",\n    " : "\n    ", out);
        fprintf(out,
                "{\"id\": %d, \"package\": %d, \"core_id\": %d, \"physical_core\": %d, "
                "\"type\": \"%s\", \"max_khz\": %ld, \"siblings\": ",
                c->id, c->package, c->core_id, c->physical_core,
                type_name(c->type), c->max_khz);
        print_json_cpulist(out, c->siblings);
        fputc('}', out);
    }
    fputs(m->logical_count ? "\n  ],\n" : "],\n", out);

    fputs("  \"caches\": [", out);
    for (int i = 0; i < m->cache_count; i++) {
        const struct cache_info *k = &m->caches[i];
        fputs(i ? ",\n    " : "\n    ", out);
        fprintf(out, "{\"level\": %d, \"type\": ", k->level);
        print_json_string(out, k->type);
        fprintf(out, ", \"size_bytes\": %llu, \"shared_cpus\": ", (unsigned long long)k->size_bytes);
        print_json_cpulist(out, k->shared_cpus);
        fputc('}', out);
    }
    fputs(m->cache_count ? "\n  ]\n}\n" : "]\n}\n", out);
}
