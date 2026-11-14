/* platform.h - the small OS hooks the portable CLI needs. */
#ifndef PLATFORM_H
#define PLATFORM_H

/* Called first thing in main(). On Windows this replaces argv with UTF-8 strings built
 * from the wide command line (the CRT's argv is ANSI and would mangle non-ASCII paths).
 * No-op elsewhere. */
void platform_init(int *argc, char ***argv);

/* Starts listening for a cancel request:
 *   POSIX:   SIGTERM / SIGINT set a flag.
 *   Windows: a "stop" line on stdin, Ctrl+C / Ctrl+Break, or stdin closing while it is a
 *            pipe (the UI died) sets the flag. */
void platform_cancel_init(void);

/* Non-zero once a cancel was requested. */
int platform_cancel_requested(void);

#endif
