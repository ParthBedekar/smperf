#include "include/util.h"


void cpulist_free(struct cpu_range* head){
    while (head != NULL) {
        struct cpu_range* tmp = head;
        head = head->next;
        free(tmp);
    }
}

int cpulist_count(const struct cpu_range* head){
    int c = 0;
    while (head != NULL) {
        c += (head->end - head->start + 1);
        head = head->next;
    }
    return c;
}

bool cpulist_contains(const struct cpu_range* head, int cpu){
    while (head != NULL) {
        if (cpu >= head->start && cpu <= head->end) {
            return true;
        }
        head = head->next;
    }
    return false;
}

int parse_cpulist(const char* in, struct cpu_range** out) {
    if (in == NULL || out == NULL) {
        return -1;
    }

    *out = NULL;
    struct cpu_range* tail = NULL;
    const char* current_ptr = in;
    char* end_ptr;

    while (*current_ptr != '\0' && *current_ptr != '\n') {
        while (*current_ptr != '\0' && isspace((unsigned char)*current_ptr)) {
            current_ptr++;
        }
        if (*current_ptr == '\0' || *current_ptr == '\n') {
            break;
        }

        if (!isdigit((unsigned char)*current_ptr)) {
            goto error_cleanup;
        }

        long start_val = strtol(current_ptr, &end_ptr, 10);
        if (current_ptr == end_ptr || start_val < 0 || start_val >= MAX_CPUS) {
            goto error_cleanup;
        }
        current_ptr = end_ptr;

        long end_val = start_val;

        if (*current_ptr == '-') {
            current_ptr++;
            if (!isdigit((unsigned char)*current_ptr)) {
                goto error_cleanup;
            }
            end_val = strtol(current_ptr, &end_ptr, 10);
            if (current_ptr == end_ptr || end_val < start_val || end_val >= MAX_CPUS) {
                goto error_cleanup;
            }
            current_ptr = end_ptr;
        }

        struct cpu_range* node = malloc(sizeof(struct cpu_range));
        if (node == NULL) {
            goto error_cleanup;
        }
        node->start = (int)start_val;
        node->end = (int)end_val;
        node->next = NULL;

        if (*out == NULL) {
            *out = node;
            tail = node;
        } else {
            tail->next = node;
            tail = node;
        }

        while (*current_ptr != '\0' && isspace((unsigned char)*current_ptr)) {
            current_ptr++;
        }

        if (*current_ptr == ',') {
            current_ptr++;
            while (*current_ptr != '\0' && isspace((unsigned char)*current_ptr)) {
                current_ptr++;
            }
            if (*current_ptr == '\0' || *current_ptr == '\n' || *current_ptr == ',') {
                goto error_cleanup;
            }
        } else if (*current_ptr != '\0' && *current_ptr != '\n') {
            goto error_cleanup;
        }
    }

    return 0;

    error_cleanup:
    cpulist_free(*out);
    *out = NULL;
    return -1;
}
int read_line(const char *path, char *buf, size_t size) {
    if (path == NULL || buf == NULL || size < 2) return -1;

    FILE *f = fopen(path, "r");
    if (f == NULL) return -1;

    if (fgets(buf, (int)size, f) == NULL) {
        fclose(f);
        return -1;
    }

    size_t len = strcspn(buf, "\n");
    int truncated = 0;
    if (buf[len] != '\n' && len == size - 1) {
        int next = fgetc(f);
        truncated = next != EOF && next != '\n';
    }
    fclose(f);

    if (truncated) return -1;
    buf[len] = '\0';
    return 0;
}

int read_long(const char *path, long *out) {
    char buf[64];
    if (out == NULL || read_line(path, buf, sizeof buf) != 0) return -1;

    char *end;
    errno = 0;
    long v = strtol(buf, &end, 10);
    if (end == buf || *end != '\0' || errno == ERANGE) return -1;

    *out = v;
    return 0;
}

int parse_size(const char *s, uint64_t *bytes) {
    if (s == NULL || bytes == NULL || !isdigit((unsigned char)*s)) return -1;

    char *end;
    errno = 0;
    unsigned long long v = strtoull(s, &end, 10);
    if (errno == ERANGE) return -1;

    unsigned long long mult = 1;
    switch (*end) {
        case '\0': break;
        case 'K': mult = 1ULL << 10; end++; break;
        case 'M': mult = 1ULL << 20; end++; break;
        case 'G': mult = 1ULL << 30; end++; break;
        default: return -1;
    }
    if (*end != '\0') return -1;
    if (v > UINT64_MAX / mult) return -1;

    *bytes = v * mult;
    return 0;
}

int quote_windows_arg(const char *arg, char *dst, size_t size) {
    size_t used = 0;
#define PUT(ch) do { if (used + 1 >= size) return -1; dst[used++] = (char)(ch); } while (0)

    int needs_quotes = (*arg == '\0');
    for (const char *p = arg; *p && !needs_quotes; p++)
        if (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\v' || *p == '"') needs_quotes = 1;

    if (!needs_quotes) {
        for (const char *p = arg; *p; p++) PUT(*p);
        dst[used] = '\0';
        return (int)used;
    }

    PUT('"');
    for (const char *p = arg; ; p++) {
        size_t backslashes = 0;
        while (*p == '\\') { backslashes++; p++; }

        if (*p == '\0') {                      /* trailing backslashes precede the closing quote */
            for (size_t i = 0; i < backslashes * 2; i++) PUT('\\');
            break;
        }
        if (*p == '"') {                       /* backslashes before a quote are doubled, quote escaped */
            for (size_t i = 0; i < backslashes * 2 + 1; i++) PUT('\\');
            PUT('"');
        } else {                               /* backslashes not before a quote stay literal */
            for (size_t i = 0; i < backslashes; i++) PUT('\\');
            PUT(*p);
        }
    }
    PUT('"');
    dst[used] = '\0';
    return (int)used;
#undef PUT
}
