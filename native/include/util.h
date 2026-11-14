#ifndef UTIL_H
#define UTIL_H

#include <stdbool.h>
#include <string.h>
#include <ctype.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>

#ifdef _WIN32
#define strtok_r strtok_s
#endif

/* Appends one argument to a Windows command line, quoted so that CommandLineToArgvW
 * (and the MSVC CRT) read it back as exactly `arg`. Portable so it can be unit-tested
 * on any OS. Returns the number of bytes appended (without the NUL), or -1 if it does
 * not fit in `size` bytes (including the NUL). */
int quote_windows_arg(const char *arg, char *dst, size_t size);

int read_line(const char *path, char *buf, size_t size);
int read_long(const char *path, long *out);
int parse_size(const char *s, uint64_t *bytes);
#define MAX_CPUS 1024

struct cpu_range {
    int start;
    int end;
    struct cpu_range* next;
};

void cpulist_free(struct cpu_range* head);
int cpulist_count(const struct cpu_range* head);
bool cpulist_contains(const struct cpu_range* head, int cpu);
int parse_cpulist(const char* in, struct cpu_range** out);

#endif
