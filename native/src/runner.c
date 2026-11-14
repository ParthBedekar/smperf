#include "include/runner.h"
#include "include/stats.h"
#include "include/platform.h"

int run_job(const struct job *j, struct run_result *r){
    memset(r, 0, sizeof *r);

    if (j->repeats < 1 || j->repeats > MAX_REPEATS) {
        return RUN_BAD_PARAMS;
    }

    for (int i = 0; i < j->warmup; i++) {
        struct run_sample s;
        int rc = run_once(j, &s);
        if (rc == RUN_ONCE_CANCELLED) return RUN_CANCELLED;
        if (rc == -1)                 return RUN_RUNNER_ERR;
        if (s.started == 0)           return RUN_BAD_PATH;
    }

    for (int i = 0; i < j->repeats; i++) {
        struct run_sample s;
        int rc = run_once(j, &s);
        if (rc == RUN_ONCE_CANCELLED) return RUN_CANCELLED;
        if (rc == -1)                 return RUN_RUNNER_ERR;
        if (s.started == 0)           return RUN_BAD_PATH;

        if (s.timed_out || s.term_signal != 0 || s.exit_code != 0) {
            r->failed_runs++;
        }
        r->samples[i] = s;
        r->runs++;
    }

    stats_compute(r);
    return 0;
}
/* Copy src to dst, replacing each "{threads}" with t. Returns 0, or -1 if it doesn't fit. */
static int expand_arg(const char *src, int t, char *dst, size_t size) {
    char num[16];
    snprintf(num, sizeof num, "%d", t);
    const char *tag = "{threads}";
    size_t taglen = strlen(tag), numlen = strlen(num), used = 0;

    while (*src) {
        const char *rep;
        size_t len;
        if (strncmp(src, tag, taglen) == 0) {
            rep = num;  len = numlen;  src += taglen;
        } else {
            rep = src;  len = 1;       src++;
        }
        if (used + len >= size) return -1;
        memcpy(dst + used, rep, len);
        used += len;
    }
    dst[used] = '\0';
    return 0;
}

int job_expand_args(struct job *j, const char *const *tmpl, int tmpl_count) {
    if (j == NULL || tmpl_count < 0 || tmpl_count > MAX_ARGS - 2)
        return -1;

    j->args[0] = j->path;
    for (int i = 0; i < tmpl_count; i++) {
        if (expand_arg(tmpl[i], j->threads, j->arg_store[i], ARG_LEN) != 0)
            return -1;
        j->args[i + 1] = j->arg_store[i];
    }
    j->args[tmpl_count + 1] = NULL;   /* execv needs a NULL terminator */
    return 0;
}
