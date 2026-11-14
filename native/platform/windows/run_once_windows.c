/* run_once_windows.c - run_once() for Windows.
 *
 * Linux pins the child itself between fork() and exec(). Windows has no fork, so the parent
 * creates the child SUSPENDED, puts it in a Job Object (which applies the CPU affinity and
 * the kill-on-close rule), and only then resumes it. The benchmark can therefore never start
 * a thread outside the requested CPUs.
 *
 * The Job Object also gives us:
 *   - timeout / cancel that kills the whole process tree, not just the direct child,
 *   - no orphaned benchmarks if the engine dies (JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE),
 *   - user/sys CPU time that includes child processes (closest to what wait4 reports).
 */
#include "platform/windows/win_target.h"
#include <windows.h>
#include <psapi.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "include/runner.h"

HANDLE platform_windows_cancel_event(void);

#define BITS_PER_GROUP ((int)(sizeof(KAFFINITY) * 8))

static double now_sec(void) {
    static LARGE_INTEGER freq;
    LARGE_INTEGER t;
    if (freq.QuadPart == 0) QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&t);
    return (double)t.QuadPart / (double)freq.QuadPart;
}

static WCHAR *widen(const char *s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n <= 0) return NULL;
    WCHAR *w = malloc((size_t)n * sizeof *w);
    if (w != NULL && MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n) <= 0) {
        free(w);
        return NULL;
    }
    return w;
}

/* Full command line (UTF-8), each argument quoted for CommandLineToArgvW. */
static char *build_command_line(const struct job *j) {
    size_t cap = 4;
    for (int i = 0; j->args[i] != NULL; i++) cap += strlen(j->args[i]) * 2 + 4;

    char *line = malloc(cap);
    if (line == NULL) return NULL;

    size_t used = 0;
    for (int i = 0; j->args[i] != NULL; i++) {
        if (i > 0) line[used++] = ' ';
        int n = quote_windows_arg(j->args[i], line + used, cap - used);
        if (n < 0) { free(line); return NULL; }
        used += (size_t)n;
    }
    line[used] = '\0';
    return line;
}

/* Collects the requested CPU ids into one affinity mask per processor group. */
struct group_masks {
    KAFFINITY mask[64];
    int used_groups;      /* number of groups with at least one CPU */
    int first_group;
};

static void collect_masks(const struct cpu_range *cpus, struct group_masks *g) {
    memset(g, 0, sizeof *g);
    g->first_group = -1;
    for (const struct cpu_range *r = cpus; r != NULL; r = r->next) {
        for (int c = r->start; c <= r->end; c++) {
            int group = c / BITS_PER_GROUP, bit = c % BITS_PER_GROUP;
            if (c < 0 || group >= 64) continue;
            if (g->mask[group] == 0) {
                g->used_groups++;
                if (g->first_group < 0 || group < g->first_group) g->first_group = group;
            }
            g->mask[group] |= (KAFFINITY)1 << bit;
        }
    }
}

/* Applies the CPU set to the job (before the child is assigned to it, so the very first
 * instruction of the benchmark already runs inside the mask). Returns 0 on success.
 * *single_group0_mask is set to the mask when everything lives in processor group 0 (the
 * common case), so the caller can also set it on the process itself. */
static int apply_job_affinity(HANDLE job, const struct cpu_range *cpus, KAFFINITY *single_group0_mask) {
    struct group_masks g;
    collect_masks(cpus, &g);
    *single_group0_mask = 0;
    if (g.used_groups == 0) return 0;

    if (g.used_groups == 1 && g.first_group == 0) {
        /* <= 64 logical CPUs: a classic affinity mask. Set on the job so grandchildren inherit it. */
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION li;
        memset(&li, 0, sizeof li);
        li.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_AFFINITY;
        li.BasicLimitInformation.Affinity = g.mask[0];
        if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &li, sizeof li)) return -1;
        *single_group0_mask = g.mask[0];
        return 0;
    }

    /* CPUs in a non-zero group, or spread over several groups: use a group-affinity list.
     * 14 == JobObjectGroupInformationEx (not named in every SDK/MinGW header). A process that is
     * not processor-group aware still runs in a single group; see the README. */
    GROUP_AFFINITY ga[64];
    int n = 0;
    for (int group = 0; group < 64; group++) {
        if (g.mask[group] == 0) continue;
        memset(&ga[n], 0, sizeof ga[n]);
        ga[n].Group = (WORD)group;
        ga[n].Mask = g.mask[group];
        n++;
    }
    return SetInformationJobObject(job, (JOBOBJECTINFOCLASS)14, ga, (DWORD)(n * sizeof ga[0])) ? 0 : -1;
}

/* .bat / .cmd cannot be passed as lpApplicationName; CreateProcess starts cmd.exe for them
 * only when it works out the program from the command line. */
static int is_batch_file(const char *path) {
    size_t n = strlen(path);
    if (n < 4) return 0;
    const char *e = path + n - 4;
    return _stricmp(e, ".bat") == 0 || _stricmp(e, ".cmd") == 0;
}

static HANDLE open_nul(void) {
    SECURITY_ATTRIBUTES sa = { sizeof sa, NULL, TRUE };
    return CreateFileW(L"NUL", GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                       &sa, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
}

int run_once(const struct job *j, struct run_sample *s) {
    memset(s, 0, sizeof *s);

    int rc = -1;
    HANDLE job = NULL, nul = INVALID_HANDLE_VALUE, err = INVALID_HANDLE_VALUE;
    LPPROC_THREAD_ATTRIBUTE_LIST attrs = NULL;
    PROCESS_INFORMATION pi;
    memset(&pi, 0, sizeof pi);
    WCHAR *app = NULL, *cmd = NULL;
    char *cmd8 = NULL;
    int attrs_ready = 0;

    double first = now_sec();

    app = widen(j->path);
    cmd8 = build_command_line(j);
    cmd = cmd8 ? widen(cmd8) : NULL;
    if (app == NULL || cmd == NULL) {
        fprintf(stderr, "could not build the command line\n");
        goto done;
    }

    job = CreateJobObjectW(NULL, NULL);
    if (job == NULL) { fprintf(stderr, "CreateJobObject failed (%lu)\n", GetLastError()); goto done; }
    {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION li;
        memset(&li, 0, sizeof li);
        li.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, &li, sizeof li)) {
            fprintf(stderr, "SetInformationJobObject failed (%lu)\n", GetLastError());
            goto done;
        }
    }

    KAFFINITY proc_mask = 0;
    if (j->cpus != NULL && apply_job_affinity(job, j->cpus, &proc_mask) != 0) {
        fprintf(stderr, "could not set the CPU affinity on the job object (%lu)\n", GetLastError());
        goto done;
    }

    /* The child gets: stdin = NUL (so it can never swallow our "stop" command), stdout = NUL,
     * stderr = ours. Only these handles are inherited. */
    nul = open_nul();
    if (nul == INVALID_HANDLE_VALUE) { fprintf(stderr, "cannot open NUL (%lu)\n", GetLastError()); goto done; }

    {
        HANDLE e = GetStdHandle(STD_ERROR_HANDLE);
        if (e != NULL && e != INVALID_HANDLE_VALUE) {
            if (!DuplicateHandle(GetCurrentProcess(), e, GetCurrentProcess(), &err, 0, TRUE,
                                 DUPLICATE_SAME_ACCESS))
                err = INVALID_HANDLE_VALUE;
        }
    }
    HANDLE inherit[2];
    int inherit_count = 0;
    inherit[inherit_count++] = nul;
    if (err != INVALID_HANDLE_VALUE) inherit[inherit_count++] = err;

    SIZE_T attr_size = 0;
    InitializeProcThreadAttributeList(NULL, 1, 0, &attr_size);
    attrs = malloc(attr_size);
    if (attrs == NULL || !InitializeProcThreadAttributeList(attrs, 1, 0, &attr_size)) {
        fprintf(stderr, "InitializeProcThreadAttributeList failed (%lu)\n", GetLastError());
        goto done;
    }
    attrs_ready = 1;
    if (!UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit,
                                   (SIZE_T)inherit_count * sizeof inherit[0], NULL, NULL)) {
        fprintf(stderr, "UpdateProcThreadAttribute failed (%lu)\n", GetLastError());
        goto done;
    }

    STARTUPINFOEXW si;
    memset(&si, 0, sizeof si);
    si.StartupInfo.cb = sizeof si;
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput = nul;
    si.StartupInfo.hStdOutput = nul;
    si.StartupInfo.hStdError = (err != INVALID_HANDLE_VALUE) ? err : nul;
    si.lpAttributeList = attrs;

    if (j->threads > 0) {
        WCHAR buf[16];
        swprintf(buf, 16, L"%d", j->threads);
        SetEnvironmentVariableW(L"OMP_NUM_THREADS", buf);
    }

    if (!CreateProcessW(is_batch_file(j->path) ? NULL : app, cmd, NULL, NULL, TRUE,
                        CREATE_SUSPENDED | CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT,
                        NULL, NULL, &si.StartupInfo, &pi)) {
        s->started = 0;               /* program did not start (bad path, not a Win32 program...) */
        rc = 0;
        goto done;
    }
    s->started = 1;

    if (!AssignProcessToJobObject(job, pi.hProcess)) {
        fprintf(stderr, "AssignProcessToJobObject failed (%lu)\n", GetLastError());
        TerminateProcess(pi.hProcess, 1);
        goto done;
    }
    if (proc_mask != 0 && !SetProcessAffinityMask(pi.hProcess, proc_mask)) {
        fprintf(stderr, "could not pin the benchmark to the requested CPUs (%lu)\n", GetLastError());
        TerminateJobObject(job, 1);
        goto done;
    }
    if (ResumeThread(pi.hThread) == (DWORD)-1) {
        fprintf(stderr, "ResumeThread failed (%lu)\n", GetLastError());
        TerminateJobObject(job, 1);
        goto done;
    }

    /* Wait for exit, timeout, or cancel. */
    HANDLE waits[2] = { pi.hProcess, platform_windows_cancel_event() };
    DWORD nwaits = waits[1] != NULL ? 2 : 1;
    DWORD limit = j->timeout_sec > 0 ? (DWORD)(j->timeout_sec * 1000.0) : INFINITE;

    int cancelled = 0;
    DWORD w = WaitForMultipleObjects(nwaits, waits, FALSE, limit);
    if (w == WAIT_TIMEOUT) {
        s->timed_out = 1;
        TerminateJobObject(job, 1);
    } else if (w == WAIT_OBJECT_0 + 1) {
        cancelled = 1;
        TerminateJobObject(job, 1);
    } else if (w != WAIT_OBJECT_0) {
        fprintf(stderr, "wait failed (%lu)\n", GetLastError());
        TerminateJobObject(job, 1);
        WaitForSingleObject(pi.hProcess, INFINITE);
        goto done;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);   /* returns at once unless a kill is still in flight */
    s->wall_sec = now_sec() - first;

    if (cancelled) { rc = RUN_ONCE_CANCELLED; goto done; }

    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    s->exit_code = (int)code;

    JOBOBJECT_BASIC_ACCOUNTING_INFORMATION acct;
    if (QueryInformationJobObject(job, JobObjectBasicAccountingInformation, &acct, sizeof acct, NULL)) {
        s->user_sec = (double)acct.TotalUserTime.QuadPart / 1e7;    /* 100 ns units */
        s->sys_sec  = (double)acct.TotalKernelTime.QuadPart / 1e7;
    } else {
        FILETIME created, exited, kernel, user;
        if (GetProcessTimes(pi.hProcess, &created, &exited, &kernel, &user)) {
            ULARGE_INTEGER u, k;
            u.LowPart = user.dwLowDateTime;   u.HighPart = user.dwHighDateTime;
            k.LowPart = kernel.dwLowDateTime; k.HighPart = kernel.dwHighDateTime;
            s->user_sec = (double)u.QuadPart / 1e7;
            s->sys_sec  = (double)k.QuadPart / 1e7;
        }
    }

    PROCESS_MEMORY_COUNTERS pmc;
    if (GetProcessMemoryInfo(pi.hProcess, &pmc, sizeof pmc))
        s->max_rss_kb = (long)(pmc.PeakWorkingSetSize / 1024);      /* bytes -> KB */

    rc = 0;

done:
    if (pi.hThread != NULL) CloseHandle(pi.hThread);
    if (pi.hProcess != NULL) CloseHandle(pi.hProcess);
    if (job != NULL) CloseHandle(job);            /* kill-on-close reaps any stragglers */
    if (attrs != NULL) {
        if (attrs_ready) DeleteProcThreadAttributeList(attrs);
        free(attrs);
    }
    if (nul != INVALID_HANDLE_VALUE) CloseHandle(nul);
    if (err != INVALID_HANDLE_VALUE) CloseHandle(err);
    free(app);
    free(cmd);
    free(cmd8);
    return rc;
}
