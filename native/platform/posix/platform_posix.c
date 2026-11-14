#define _GNU_SOURCE
#include <signal.h>
#include <string.h>
#include "include/platform.h"

static volatile sig_atomic_t stop_requested = 0;

static void on_signal(int sig) {
    (void)sig;
    stop_requested = 1;
}

void platform_init(int *argc, char ***argv) {
    (void)argc;
    (void)argv;
}

void platform_cancel_init(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
}

int platform_cancel_requested(void) {
    return stop_requested != 0;
}
