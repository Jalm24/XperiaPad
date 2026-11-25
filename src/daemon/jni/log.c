/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include "log.h"
#include <stdio.h>
#include <stdarg.h>
#include <time.h>

static FILE *g_logf = NULL;

void xpd_log_init(const char *path) {
    if (!path) return;
    g_logf = fopen(path, "a");
}

void xpd_log(const char *level, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);

    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    char ts[32];
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", &tm);

    fprintf(stderr, "[%s] %s: %s\n", ts, level ? level : "INFO", buf);
    if (g_logf) {
        fprintf(g_logf, "[%s] %s: %s\n", ts, level ? level : "INFO", buf);
        fflush(g_logf);
    }
}

void xpd_log_close(void) {
    if (g_logf) {
        fclose(g_logf);
        g_logf = NULL;
    }
}
