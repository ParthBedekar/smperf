/* platform_windows.c - process-level hooks for the Windows engine.
 *
 *  - UTF-8 argv. The CRT hands main() an ANSI argv, which turns any non-ASCII path
 *    (e.g. C:\Users\Zoë\...) into '?'. We re-read the wide command line instead.
 *  - Cancel. Java's Process.destroy() is TerminateProcess on Windows, so the engine would
 *    never get to print its final "done" line. Instead the UI writes "stop\n" to our stdin.
 *    Ctrl+C / Ctrl+Break also work when run by hand. If stdin is a pipe and the other end
 *    closes (the UI died) that counts as a stop too, so no benchmark is left running.
 */
#include "platform/windows/win_target.h"
#include <windows.h>
#include <shellapi.h>
#include <fcntl.h>
#include <io.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "include/platform.h"

static volatile LONG g_stop = 0;
static HANDLE g_cancel_event = NULL;   /* manual-reset; signalled on cancel */

HANDLE platform_windows_cancel_event(void) {
    return g_cancel_event;
}

static void request_stop(void) {
    InterlockedExchange(&g_stop, 1);
    if (g_cancel_event != NULL) SetEvent(g_cancel_event);
}

static BOOL WINAPI ctrl_handler(DWORD type) {
    switch (type) {
        case CTRL_C_EVENT:
        case CTRL_BREAK_EVENT:
        case CTRL_CLOSE_EVENT:
            request_stop();
            return TRUE;
        default:
            return FALSE;
    }
}

static DWORD WINAPI stdin_watcher(LPVOID arg) {
    (void)arg;
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    if (in == NULL || in == INVALID_HANDLE_VALUE) return 0;
    int is_pipe = GetFileType(in) == FILE_TYPE_PIPE;

    char line[64];
    size_t n = 0;
    for (;;) {
        char c;
        DWORD got = 0;
        if (!ReadFile(in, &c, 1, &got, NULL) || got == 0) {
            if (is_pipe) request_stop();       /* parent went away */
            return 0;
        }
        if (c == '\n') {
            line[n] = '\0';
            if (n > 0 && line[n - 1] == '\r') line[n - 1] = '\0';
            if (strcmp(line, "stop") == 0) {
                request_stop();
                return 0;
            }
            n = 0;
        } else if (n < sizeof line - 1) {
            line[n++] = c;
        }
    }
}

void platform_init(int *argc, char ***argv) {
    _setmode(_fileno(stdout), _O_BINARY);      /* JSON lines end in \n, not \r\n */

    int wn = 0;
    LPWSTR *w = CommandLineToArgvW(GetCommandLineW(), &wn);
    if (w == NULL) return;

    char **a = calloc((size_t)wn + 1, sizeof *a);
    if (a == NULL) { LocalFree(w); return; }

    for (int i = 0; i < wn; i++) {
        int len = WideCharToMultiByte(CP_UTF8, 0, w[i], -1, NULL, 0, NULL, NULL);
        a[i] = len > 0 ? malloc((size_t)len) : NULL;
        if (a[i] == NULL || WideCharToMultiByte(CP_UTF8, 0, w[i], -1, a[i], len, NULL, NULL) <= 0) {
            LocalFree(w);
            return;                             /* keep the CRT argv on any failure */
        }
    }
    LocalFree(w);
    *argc = wn;
    *argv = a;
}

void platform_cancel_init(void) {
    g_cancel_event = CreateEventW(NULL, TRUE, FALSE, NULL);
    SetConsoleCtrlHandler(ctrl_handler, TRUE);
    HANDLE t = CreateThread(NULL, 0, stdin_watcher, NULL, 0, NULL);
    if (t != NULL) CloseHandle(t);
}

int platform_cancel_requested(void) {
    return g_stop != 0;
}
