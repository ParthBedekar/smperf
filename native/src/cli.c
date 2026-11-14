#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "include/platform.h"
#include "include/runner.h"
#include "include/topology.h"

#define MAX_STEPS 64

static int usage(const char *prog) {
    fprintf(stderr,
            "usage:\n"
            "  %s topology\n"
            "  %s run [--threads auto|1,2,4] [--repeats N] [--warmup N] [--timeout SEC] -- <program> [args...]\n",
            prog, prog);
    return 2;
}

static int parse_long(const char *s, long lo, long hi, long *out) {
    char *end;
    errno = 0;
    long v = strtol(s, &end, 10);
    if (end == s || *end != '\0' || errno == ERANGE || v < lo || v > hi) return -1;
    *out = v;
    return 0;
}

static int parse_double(const char *s, double lo, double hi, double *out) {
    char *end;
    errno = 0;
    double v = strtod(s, &end);
    if (end == s || *end != '\0' || errno == ERANGE || v < lo || v > hi) return -1;
    *out = v;
    return 0;
}

static int placement_order(const struct machine *m, int *order) {
    int seen[MAX_LOGICAL] = {0};
    int used[MAX_LOGICAL] = {0};
    int n = 0;

    for (int i = 0; i < m->logical_count; i++) {
        int pc = m->cpus[i].physical_core;
        if (!seen[pc]) {
            seen[pc] = 1;
            used[i] = 1;
            order[n++] = m->cpus[i].id;
        }
    }
    for (int i = 0; i < m->logical_count; i++) {
        if (!used[i]) order[n++] = m->cpus[i].id;
    }
    return n;
}

static int parse_threads(const char *s, int n, int *steps, int max) {
    int count = 0;

    if (strcmp(s, "auto") == 0) {
        for (int t = 1; count < max; t *= 2) {
            if (t > n) t = n;
            steps[count++] = t;
            if (t == n) break;
        }
        return count;
    }

    char buf[256];
    if (snprintf(buf, sizeof buf, "%s", s) >= (int)sizeof buf) return -1;

    char *save = NULL;
    for (char *tok = strtok_r(buf, ",", &save); tok != NULL; tok = strtok_r(NULL, ",", &save)) {
        long v;
        if (count >= max || parse_long(tok, 1, n, &v) != 0) return -1;
        steps[count++] = (int)v;
    }
    return count > 0 ? count : -1;
}

static const char *status_message(int rc) {
    switch (rc) {
        case RUN_BAD_PARAMS: return "repeats out of range";
        case RUN_RUNNER_ERR: return "runner failed (fork, wait or pinning)";
        case RUN_BAD_PATH:   return "program did not start";
        default:             return "unknown error";
    }
}

static void emit_result(int threads, const struct run_result *r) {
    printf("{\"event\":\"result\",\"threads\":%d,\"runs\":%d,\"failed\":%d,"
    "\"wall_best\":%.6f,\"wall_mean\":%.6f,\"wall_max\":%.6f,\"wall_stddev\":%.6f,"
    "\"user_mean\":%.6f,\"sys_mean\":%.6f,\"max_rss_kb\":%ld}\n",
    threads, r->runs, r->failed_runs,
    r->wall_best, r->wall_mean, r->wall_max, r->wall_stddev,
    r->user_mean, r->sys_mean, r->max_rss_kb);
    fflush(stdout);
}

static void emit_error(int threads, int code, const char *message) {
    printf("{\"event\":\"error\",\"threads\":%d,\"code\":%d,\"message\":\"%s\"}\n",
           threads, code, message);
    fflush(stdout);
}

static void emit_done(int ok, int cancelled) {
    printf("{\"event\":\"done\",\"ok\":%s,\"cancelled\":%s}\n",
           ok ? "true" : "false", cancelled ? "true" : "false");
    fflush(stdout);
}

static int cmd_topology(void) {
    struct machine m;
    if (detect_topology(&m) != 0) {
        fprintf(stderr, "topology detection failed\n");
        return 1;
    }
    machine_print_json(&m, stdout);
    fflush(stdout);
    machine_free(&m);
    return 0;
}

static int cmd_run(int argc, char **argv) {
    const char *threads_arg = "auto";
    long repeats = 5, warmup = 1;
    double timeout = 60;
    int prog = -1;

    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], "--") == 0) {
            prog = i + 1;
            break;
        }
        if (i + 1 >= argc) return usage(argv[0]);

        const char *opt = argv[i];
        const char *val = argv[++i];

        if (strcmp(opt, "--threads") == 0) {
            threads_arg = val;
        } else if (strcmp(opt, "--repeats") == 0) {
            if (parse_long(val, 1, MAX_REPEATS, &repeats) != 0) return usage(argv[0]);
        } else if (strcmp(opt, "--warmup") == 0) {
            if (parse_long(val, 0, 100, &warmup) != 0) return usage(argv[0]);
        } else if (strcmp(opt, "--timeout") == 0) {
            if (parse_double(val, 0, 86400, &timeout) != 0) return usage(argv[0]);
        } else {
            return usage(argv[0]);
        }
    }
    if (prog < 0 || prog >= argc) return usage(argv[0]);

    struct machine m;
    if (detect_topology(&m) != 0) {
        fprintf(stderr, "topology detection failed\n");
        return 1;
    }

    int order[MAX_LOGICAL];
    int n = placement_order(&m, order);

    int steps[MAX_STEPS];
    int step_count = parse_threads(threads_arg, n, steps, MAX_STEPS);
    if (step_count < 0) {
        fprintf(stderr, "invalid --threads (valid range is 1..%d)\n", n);
        machine_free(&m);
        return 2;
    }

    struct job j;
    memset(&j, 0, sizeof j);
    if (snprintf(j.path, sizeof j.path, "%s", argv[prog]) >= (int)sizeof j.path) {
        fprintf(stderr, "program path too long\n");
        machine_free(&m);
        return 2;
    }
    j.warmup = (int)warmup;
    j.repeats = (int)repeats;
    j.timeout_sec = timeout;

    platform_cancel_init();

    struct cpu_range nodes[MAX_LOGICAL];
    int ok = 1, cancelled = 0;

    for (int s = 0; s < step_count; s++) {
        if (platform_cancel_requested()) {
            cancelled = 1;
            break;
        }

        int t = steps[s];
        for (int k = 0; k < t; k++) {
            nodes[k].start = nodes[k].end = order[k];
            nodes[k].next = (k + 1 < t) ? &nodes[k + 1] : NULL;
        }
        j.threads = t;
        j.cpus = nodes;

        if (job_expand_args(&j, (const char *const *)&argv[prog + 1], argc - prog - 1) != 0) {
            emit_error(t, -4, "too many arguments, or one is too long");
            ok = 0;
            break;
        }

        struct run_result r;
        int rc = run_job(&j, &r);
        if (rc == RUN_CANCELLED) {      /* aborted mid-step (Windows); the partial step is discarded */
            cancelled = 1;
            break;
        }
        if (rc != RUN_OK) {
            emit_error(t, rc, status_message(rc));
            ok = 0;
            break;
        }
        emit_result(t, &r);
    }

    emit_done(ok, cancelled);
    machine_free(&m);
    return ok ? 0 : 1;
}

int main(int argc, char **argv) {
    platform_init(&argc, &argv);
    if (argc < 2) return usage(argv[0]);
    if (strcmp(argv[1], "topology") == 0) return cmd_topology();
    if (strcmp(argv[1], "run") == 0) return cmd_run(argc, argv);
    return usage(argv[0]);
}
