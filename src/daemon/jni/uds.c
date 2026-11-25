/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include "uds.h"
#include "log.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <stddef.h>
#include <fcntl.h>
#include <sys/types.h>

/* C2: SO_PEERCRED — solo el UID de la app (o root/shell en modo debug) */
static uid_t g_allowed_uid = 0;
static int g_allow_shell = 1;

void uds_set_allowed_uid(uid_t uid, int allow_shell) {
    g_allowed_uid = uid;
    g_allow_shell = allow_shell;
}

/* C2: verificacion comun de peer para ambos sockets */
static int peer_allowed(int fd) {
    struct ucred cr;
    socklen_t crlen = sizeof(cr);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cr, &crlen) != 0) {
        xpd_log("WARN", "uds: SO_PEERCRED failed; connection rejected");
        return 0;
    }
    int ok = (cr.uid == g_allowed_uid) || (g_allow_shell && (cr.uid == 0 || cr.uid == 2000));
    if (!ok) xpd_log("WARN", "uds: connection rejected uid=%d", (int)cr.uid);
    return ok;
}

int uds_listen(xpd_state_t *st) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        xpd_log("ERR", "uds socket: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* abstract namespace: sun_path[0] = 0, name follows */
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, XPD_SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(XPD_SOCKET_NAME);
    if (bind(fd, (struct sockaddr *)&addr, len) < 0) {
        xpd_log("ERR", "uds bind: %s", strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 1) < 0) {
        xpd_log("ERR", "uds listen: %s", strerror(errno));
        close(fd);
        return -1;
    }
    st->uds_fd = fd;
    st->client_fd = -1;
    xpd_log("INFO", "uds listening @%s", XPD_SOCKET_NAME);
    return 0;
}

int uds_accept(xpd_state_t *st) {
    if (st->uds_fd < 0) return -1;
    int c = accept(st->uds_fd, NULL, NULL);
    if (c < 0) return -1;
    if (!peer_allowed(c)) { close(c); return -1; }
    int flags = fcntl(c, F_GETFL, 0);
    if (flags >= 0) fcntl(c, F_SETFL, flags | O_NONBLOCK);
    if (st->client_fd >= 0) close(st->client_fd);
    st->client_fd = c;
    xpd_log("INFO", "uds client connected");
    return 0;
}

int uds_handle(xpd_state_t *st) {
    /* TODO: leer comandos START/STOP/PAIR/... y responder */
    (void)st;
    return 0;
}

void uds_close(xpd_state_t *st) {
    if (st->client_fd >= 0) { close(st->client_fd); st->client_fd = -1; }
    if (st->uds_fd >= 0) { close(st->uds_fd); st->uds_fd = -1; }
    uds_ctl_close(st);
}

/* Socket de SONDEO: los PING/STATE/SCAN no desplazan al cliente principal. */
int uds_listen_ctl(xpd_state_t *st) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, XPD_CTL_SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(XPD_CTL_SOCKET_NAME);
    if (bind(fd, (struct sockaddr *)&addr, len) < 0 || listen(fd, 4) < 0) {
        close(fd); return -1;
    }
    st->ctl_fd = fd;
    st->ctl_client_fd = -1;
    st->ctl_buf_len = 0;
    return 0;
}

int uds_accept_ctl(xpd_state_t *st) {
    if (st->ctl_fd < 0) return -1;
    int c = accept(st->ctl_fd, NULL, NULL);
    if (c < 0) return -1;
    if (!peer_allowed(c)) { close(c); return -1; }
    if (st->ctl_client_fd >= 0) close(st->ctl_client_fd);
    st->ctl_client_fd = c;
    st->ctl_buf_len = 0;
    int fl = fcntl(c, F_GETFL, 0);
    if (fl >= 0) fcntl(c, F_SETFL, fl | O_NONBLOCK);
    return 0;
}

void uds_ctl_close(xpd_state_t *st) {
    if (st->ctl_client_fd >= 0) { close(st->ctl_client_fd); st->ctl_client_fd = -1; }
    if (st->ctl_fd >= 0) { close(st->ctl_fd); st->ctl_fd = -1; }
}
