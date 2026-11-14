/* Unit tests for portable helpers. Run with `make test`. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "include/util.h"

/* Reference implementation of the MSVC CRT / CommandLineToArgvW parsing rules
 * (2n backslashes + quote -> n backslashes, quote toggles; 2n+1 -> n backslashes + literal quote). */
static int parse_args(const char *line, char out[][512], int max) {
    int n = 0;
    const char *p = line;
    while (*p) {
        while (*p == ' ' || *p == '\t') p++;
        if (!*p) break;
        char *o = out[n];
        int in_quotes = 0;
        while (*p && (in_quotes || (*p != ' ' && *p != '\t'))) {
            int bs = 0;
            while (*p == '\\') { bs++; p++; }
            if (*p == '"') {
                for (int i = 0; i < bs / 2; i++) *o++ = '\\';
                if (bs % 2) *o++ = '"'; else in_quotes = !in_quotes;
                p++;
            } else {
                for (int i = 0; i < bs; i++) *o++ = '\\';
                if (*p && (in_quotes || (*p != ' ' && *p != '\t'))) *o++ = *p++;
            }
        }
        *o = '\0';
        if (++n >= max) break;
    }
    return n;
}

static void roundtrip(const char *const *args, int count) {
    char line[8192];
    size_t used = 0;
    for (int i = 0; i < count; i++) {
        if (i) line[used++] = ' ';
        int n = quote_windows_arg(args[i], line + used, sizeof line - used);
        assert(n >= 0);
        used += (size_t)n;
    }
    line[used] = '\0';

    char parsed[16][512];
    int got = parse_args(line, parsed, 16);
    if (got != count) { fprintf(stderr, "FAIL count %d != %d for line: %s\n", got, count, line); exit(1); }
    for (int i = 0; i < count; i++)
        if (strcmp(parsed[i], args[i]) != 0) {
            fprintf(stderr, "FAIL arg %d: [%s] != [%s]\n  line: %s\n", i, parsed[i], args[i], line);
            exit(1);
        }
}

int main(void) {
    const char *cases[] = {
        "plain", "with space", "", "C:\\Program Files\\App\\app.exe", "C:\\dir\\", "C:\\dir with space\\",
        "say \"hi\"", "trail\\\\", "\\\\server\\share\\x y", "a\\\"b", "\\\"", "\"", "\\\\\"", "tab\there",
        "--size=100", "{threads}", "ünïcödé path", "end\\", "\\", "\\\\", "x\\\\\\\"y z"
    };
    int n = (int)(sizeof cases / sizeof cases[0]);
    for (int i = 0; i < n; i++) roundtrip(&cases[i], 1);
    roundtrip(cases, 8);                                 /* several args in one line */

    /* fuzz over the troublesome alphabet */
    const char alpha[] = {'\\', '"', ' ', 'a', '\t', 'b'};
    srand(12345);
    for (int it = 0; it < 20000; it++) {
        char a[40], b[40];
        int la = rand() % 12, lb = rand() % 12;
        for (int i = 0; i < la; i++) a[i] = alpha[rand() % 6];
        for (int i = 0; i < lb; i++) b[i] = alpha[rand() % 6];
        a[la] = b[lb] = '\0';
        /* the reference parser drops empty/whitespace-only tokens only if unquoted; ours always quotes them */
        const char *pair[2] = {a, b};
        roundtrip(pair, 2);
    }

    /* overflow is reported, not truncated */
    char tiny[4];
    assert(quote_windows_arg("abcdef", tiny, sizeof tiny) == -1);
    assert(quote_windows_arg("ab", tiny, sizeof tiny) == 2);

    /* cpu list helpers that the Windows topology also relies on */
    struct cpu_range *l = NULL;
    assert(parse_cpulist("0-3,8", &l) == 0 && cpulist_count(l) == 5 && cpulist_contains(l, 8) && !cpulist_contains(l, 5));
    cpulist_free(l);

    puts("all util tests passed");
    return 0;
}
