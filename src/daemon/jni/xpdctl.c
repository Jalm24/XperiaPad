/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stddef.h>

#ifndef XPD_SOCKET_NAME
#define XPD_SOCKET_NAME "xperiapad"
#endif

static int send_line(int fd, const char *line) {
    size_t len = strlen(line);
    if (len == 0) return 0;
    char buf[600];
    if (len + 2 > sizeof(buf)) len = sizeof(buf) - 2;
    memcpy(buf, line, len);
    if (buf[len-1] != '\n') buf[len++] = '\n';
    if (write(fd, buf, len) < 0) return -1;
    char r[512];
    ssize_t n = read(fd, r, sizeof(r)-1);
    if (n > 0) { r[n]='\0'; fputs(r, stdout); fflush(stdout); }
    return 0;
}

int main(int argc, char **argv) {
    int interactive = 0;
    int argi = 1;
    if (argc > 1 && strcmp(argv[1], "-i") == 0) { interactive = 1; argi = 2; }

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, XPD_SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(XPD_SOCKET_NAME));
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) { perror("connect"); close(fd); return 1; }

    if (interactive) {
        /* send remaining args as first command if present */
        if (argi < argc) {
            char msg[512]; size_t off=0; msg[0]='\0';
            for (int i=argi;i<argc;i++){ size_t l=strlen(argv[i]); if(off+l+2>=sizeof(msg))break; if(i>argi)msg[off++]=' '; memcpy(msg+off,argv[i],l); off+=l; }
            msg[off]='\0';
            send_line(fd, msg);
        }
        char line[512];
        while (fgets(line, sizeof(line), stdin)) {
            /* strip CRLF */
            size_t l = strlen(line);
            while (l>0 && (line[l-1]=='\n' || line[l-1]=='\r')) line[--l]='\0';
            if (l==0) continue;
            if (strcmp(line, "QUITCTL")==0) break;
            if (send_line(fd, line) < 0) break;
        }
    } else {
        if (argc < 2) { fprintf(stderr, "uso: %s [-i] <COMANDO...>\n", argv[0]); close(fd); return 2; }
        char msg[512]; size_t off=0; msg[0]='\0';
        for (int i=1;i<argc;i++){ size_t l=strlen(argv[i]); if(off+l+2>=sizeof(msg))break; if(i>1)msg[off++]=' '; memcpy(msg+off,argv[i],l); off+=l; }
        msg[off]='\0';
        send_line(fd, msg);
    }
    close(fd);
    return 0;
}
