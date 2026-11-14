/* run_once() for Linux and macOS: fork + (Linux only) sched_setaffinity + execv + wait4. */
#define _GNU_SOURCE
#include "include/runner.h"
#include "include/stats.h"
#ifdef __linux__
#include <sched.h>
#endif
#include <fcntl.h>
#include <signal.h>

static double now_sec(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

int run_once(const struct job *j, struct run_sample *s) {
    memset(s, 0, sizeof *s);

    double first = now_sec();
    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        return -1;
    }

    if (pid == 0) {
        if (j->threads > 0) {
            char buf[16];
            snprintf(buf, sizeof buf, "%d", j->threads);
            setenv("OMP_NUM_THREADS", buf, 1);
        }

#ifdef __linux__
        if (j->cpus != NULL) {
            cpu_set_t set;
            CPU_ZERO(&set);
            for (const struct cpu_range *r = j->cpus; r != NULL; r = r->next) {
                for (int c = r->start; c <= r->end; c++) {
                    if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
                }
            }
            if (sched_setaffinity(0, sizeof set, &set) != 0)
                _exit(AFFINITY_FAILED_EXIT);
        }
#endif
        /* macOS has no CPU affinity API (THREAD_AFFINITY_POLICY is only a hint and is
         * ignored on Apple Silicon), so j->cpus is not applied there. The topology JSON
         * reports "pinning": "unsupported" so the UI can say so. */

        int fd = open("/dev/null", O_WRONLY);
        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            close(fd);
        }

        execv(j->path, j->args);
        _exit(EXEC_FAILED_EXIT);
    }

    int status;
    struct rusage ru;
    int use_timeout = j->timeout_sec > 0;

    for (;;) {
        pid_t w = wait4(pid, &status, use_timeout ? WNOHANG : 0, &ru);
        if (w == pid) break;
        if (w < 0) {
            if (errno == EINTR) continue;
            perror("wait4");
            return -1;
        }
        if (!s->timed_out && now_sec() - first > j->timeout_sec) {
            kill(pid, SIGKILL);
            s->timed_out = 1;
        }
        struct timespec nap = {0, 1000000};
        nanosleep(&nap, NULL);
    }

    s->wall_sec = now_sec() - first;
    s->user_sec = ru.ru_utime.tv_sec + ru.ru_utime.tv_usec / 1e6;
    s->sys_sec  = ru.ru_stime.tv_sec + ru.ru_stime.tv_usec / 1e6;
#ifdef __APPLE__
    s->max_rss_kb = ru.ru_maxrss / 1024;   /* macOS reports bytes, Linux reports KB */
#else
    s->max_rss_kb = ru.ru_maxrss;
#endif

    if (WIFEXITED(status))        s->exit_code = WEXITSTATUS(status);
    else if (WIFSIGNALED(status)) s->term_signal = WTERMSIG(status);

    s->started = !(WIFEXITED(status) && s->exit_code == EXEC_FAILED_EXIT);

    if (j->cpus != NULL && WIFEXITED(status) &&
        s->exit_code == AFFINITY_FAILED_EXIT) {
        fprintf(stderr, "sched_setaffinity failed in child\n");
    return -1;
        }
        return 0;
}
