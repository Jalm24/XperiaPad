/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

/* dbus_trusted.c — auto-trust sin reiniciar bluetoothd (E27 mejorado).
 *
 * BlueZ 4 expone org.bluez.Device.SetProperty("Trusted", <b>true</b>) en el
 * bus sistema. Llamarlo evita el killall bluetoothd que hacia que el framework
 *interpretara el respawn como crash y apagara/encendiera el adaptador.
 *
 * Cliente D-Bus minimo: habla el protocolo de cable directamente sobre
 * /dev/socket/dbus. Sin libdbus: el APK solo lleva libxpd.so.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <ctype.h>
#include <dirent.h>
#include <time.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>

#include "dbus_trusted.h"
#include "log.h"

#define DBUS_SOCKET "/dev/socket/dbus"
#define BT_STORAGE  "/data/misc/bluetoothd"

typedef struct {
    uint8_t *p;
    size_t len;
    size_t cap;
} Buf;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void b_align(Buf *b, size_t a) {
    size_t pad = (a - (b->len % a)) % a;
    while (pad--) b->p[b->len++] = 0;
}

static void b_put(Buf *b, const void *data, size_t n) {
    memcpy(b->p + b->len, data, n);
    b->len += n;
}

static void b_byte(Buf *b, uint8_t v) { b->p[b->len++] = v; }

static size_t b_u32(Buf *b, uint32_t v) {
    b_align(b, 4);
    size_t off = b->len;
    b->p[b->len++] = (uint8_t)(v & 0xff);
    b->p[b->len++] = (uint8_t)((v >> 8) & 0xff);
    b->p[b->len++] = (uint8_t)((v >> 16) & 0xff);
    b->p[b->len++] = (uint8_t)((v >> 24) & 0xff);
    return off;
}

static void put_u32_at(uint8_t *p, size_t off, uint32_t v) {
    p[off]     = (uint8_t)(v & 0xff);
    p[off + 1] = (uint8_t)((v >> 8) & 0xff);
    p[off + 2] = (uint8_t)((v >> 16) & 0xff);
    p[off + 3] = (uint8_t)((v >> 24) & 0xff);
}

/* STRING y OBJECT_PATH comparten marshalling (u32 len + bytes + NUL). */
static void b_string(Buf *b, const char *s) {
    uint32_t n = (uint32_t)strlen(s);
    b_u32(b, n);
    b_put(b, s, n);
    b_byte(b, 0);
}

/* SIGNATURE: byte len + bytes + NUL (alineacion 1). */
static void b_signature(Buf *b, const char *s) {
    uint8_t n = (uint8_t)strlen(s);
    b_byte(b, n);
    b_put(b, s, n);
    b_byte(b, 0);
}

static int write_all(int fd, const void *data, size_t n) {
    const uint8_t *p = (const uint8_t *)data;
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (w == 0) return -1;
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

static int read_line(int fd, char *out, size_t outlen, int timeout_ms) {
    size_t len = 0;
    long long deadline = now_ms() + timeout_ms;
    while (len + 1 < outlen) {
        int left = (int)(deadline - now_ms());
        if (left < 0) left = 0;
        struct pollfd pfd;
        pfd.fd = fd; pfd.events = POLLIN; pfd.revents = 0;
        int pr = poll(&pfd, 1, left);
        if (pr < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (pr == 0) return -1;
        ssize_t n = read(fd, out + len, 1);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            return -1;
        }
        if (n == 0) return -1;
        if (out[len] == '\n') {
            out[len] = '\0';
            if (len > 0 && out[len - 1] == '\r') out[len - 1] = '\0';
            return 0;
        }
        len++;
    }
    return -1;
}

static int read_available(int fd, uint8_t *buf, size_t cap, size_t *out, int ms) {
    *out = 0;
    long long deadline = now_ms() + ms;
    while (*out < cap) {
        int left = (int)(deadline - now_ms());
        if (left < 0) left = 0;
        struct pollfd pfd;
        pfd.fd = fd; pfd.events = POLLIN; pfd.revents = 0;
        int pr = poll(&pfd, 1, left);
        if (pr < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (pr == 0) break;
        ssize_t n = read(fd, buf + *out, cap - *out);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            return -1;
        }
        if (n == 0) break;
        *out += (size_t)n;
        deadline = now_ms() + 50; /* drenar la respuesta completa */
    }
    return 0;
}

static int dbus_connect(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, DBUS_SOCKET, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    /* D-Bus spec: el PRIMER byte debe ser un NUL (opcionalmente con
     * SCM_CREDENTIALS). Sin el, el daemon corta la conexion antes de AUTH. */
    char nul = '\0';
    if (write_all(fd, &nul, 1) != 0) { close(fd); return -1; }

    /* SASL EXTERNAL: el UID decimal se envia como cadena hex-coded.
     * uid=0 -> "0" (0x30) -> "30"; el reto termina en CRLF. */
    char uid_dec[32], auth[80];
    snprintf(uid_dec, sizeof(uid_dec), "%u", (unsigned)getuid());
    size_t alen = (size_t)snprintf(auth, sizeof(auth), "AUTH EXTERNAL ");
    for (const char *p = uid_dec; *p && alen + 3 < sizeof(auth); p++) {
        alen += (size_t)snprintf(auth + alen, sizeof(auth) - alen, "%02x", (unsigned char)*p);
    }
    alen += (size_t)snprintf(auth + alen, sizeof(auth) - alen, "\r\n");
    if (write_all(fd, auth, alen) != 0) { close(fd); return -1; }
    char line[128];
    if (read_line(fd, line, sizeof(line), 2000) != 0 || strncmp(line, "OK ", 3) != 0) {
        close(fd);
        return -1;
    }
    if (write_all(fd, "BEGIN\r\n", 7) != 0) { close(fd); return -1; }
    return fd;
}

static int find_bt_pid(void) {
    DIR *d = opendir("/proc");
    if (!d) return -1;
    struct dirent *e;
    int pid = -1;
    while ((e = readdir(d)) != NULL) {
        if (!isdigit((unsigned char)e->d_name[0])) continue;
        /* Este kernel (2.6.32) no expone /proc/<pid>/comm: usar cmdline. */
        char path[80];
        snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        char cmd[256];
        size_t n = fread(cmd, 1, sizeof(cmd) - 1, f);
        cmd[n] = '\0';
        fclose(f);
        if (strstr(cmd, "bluetoothd") != NULL) {
            pid = atoi(e->d_name);
            break;
        }
    }
    closedir(d);
    return pid;
}

static void mac_to_path(char *out, size_t outlen, const char *mac, int pid) {
    char dev[80];
    snprintf(dev, sizeof(dev), "dev_%s", mac);
    for (char *p = dev; *p; p++) if (*p == ':') *p = '_';
    snprintf(out, outlen, "/org/bluez/%d/hci0/%s", pid, dev);
}

/* field: 1=PATH(o,s), 2=INTERFACE(s,s), 3=MEMBER(s,s), 6=DESTINATION(s,s),
 *        8=SIGNATURE(g,s signature). kind 1=string/path, 2=signature value. */
static void append_field(Buf *h, uint8_t code, const char *sig, int kind, const char *val) {
    /* Cada field es un STRUCT (y v): alinear a 8 SOLO el inicio del struct.
     * El VARIANT interno tiene alineacion 1; forzar 8 aqui corrompe el mensaje
     * y el daemon D-Bus cierra la conexion sin responder. */
    b_align(h, 8);
    b_byte(h, code);
    b_signature(h, sig);
    if (kind == 1) {
        b_align(h, 4); /* string / object_path */
        b_string(h, val);
    } else {
        b_signature(h, val); /* 'g': alineacion 1 */
    }
}

/* dbus-daemon de Android exige Hello como primer mensaje tras BEGIN; sin el,
 * responde AccessDenied y cierra la conexion al siguiente mensaje. */
static int dbus_hello(int fd) {
    uint8_t hdrbuf[512];
    Buf h;
    h.p = hdrbuf; h.len = 0; h.cap = sizeof(hdrbuf);
    b_byte(&h, 'l'); b_byte(&h, 1); b_byte(&h, 0); b_byte(&h, 1);
    size_t body_off = b_u32(&h, 0);
    b_u32(&h, 1); /* serial de Hello */
    size_t arr_off = b_u32(&h, 0);
    b_align(&h, 8);
    size_t arr_start = h.len;
    append_field(&h, 1, "o", 1, "/org/freedesktop/DBus");
    append_field(&h, 2, "s", 1, "org.freedesktop.DBus");
    append_field(&h, 3, "s", 1, "Hello");
    append_field(&h, 6, "s", 1, "org.freedesktop.DBus");
    append_field(&h, 8, "g", 2, "");
    put_u32_at(hdrbuf, arr_off, (uint32_t)(h.len - arr_start));
    b_align(&h, 8);
    put_u32_at(hdrbuf, body_off, 0);
    if (write_all(fd, h.p, h.len) != 0) return -1;
    uint8_t rbuf[512];
    size_t rlen = 0;
    if (read_available(fd, rbuf, sizeof(rbuf), &rlen, 2000) != 0 ||
        rlen < 2 || rbuf[1] != 2) return -1;
    return 0;
}

static int dbus_call_set_trusted(const char *mac, const char *path) {
    uint8_t bodybuf[128];
    Buf body;
    body.p = bodybuf; body.len = 0; body.cap = sizeof(bodybuf);
    b_string(&body, "Trusted");
    /* 'g' tiene alineacion 1: alinear el VARIANT completo a 8 inserta
     * relleno no valido para dbus-daemon de Android y corta la conexion. */
    b_signature(&body, "b");
    b_align(&body, 4);
    b_u32(&body, 1); /* true */

    uint8_t hdrbuf[1024];
    Buf h;
    h.p = hdrbuf; h.len = 0; h.cap = sizeof(hdrbuf);
    b_byte(&h, 'l');  /* little endian */
    b_byte(&h, 1);    /* METHOD_CALL */
    b_byte(&h, 0);    /* flags */
    b_byte(&h, 1);    /* protocol version */
    size_t body_len_off = b_u32(&h, (uint32_t)body.len);
    b_u32(&h, 2);     /* serial: 1 lo usa Hello */
    size_t arr_len_off = b_u32(&h, 0);
    b_align(&h, 8);
    size_t arr_start = h.len;
    append_field(&h, 1, "o", 1, path);
    append_field(&h, 2, "s", 1, "org.bluez.Device");
    append_field(&h, 3, "s", 1, "SetProperty");
    append_field(&h, 6, "s", 1, "org.bluez");
    append_field(&h, 8, "g", 2, "sv");
    put_u32_at(hdrbuf, arr_len_off, (uint32_t)(h.len - arr_start));
    /* El body empieza en un limite de 8 bytes; el padding final del array
     * no cuenta en arr_len. Sin esto el mensaje es invalido. */
    b_align(&h, 8);
    put_u32_at(hdrbuf, body_len_off, (uint32_t)body.len);
    b_put(&h, body.p, body.len);

    int fd = dbus_connect();
    if (fd < 0) {
        xpd_log("WARN", "D-Bus trust: connect failed (%s)", strerror(errno));
        return -1;
    }
    if (dbus_hello(fd) != 0) {
        xpd_log("WARN", "D-Bus Hello failed for %s", mac);
        close(fd);
        return -1;
    }
    if (write_all(fd, h.p, h.len) != 0) { close(fd); return -1; }

    uint8_t rbuf[2048];
    size_t rlen = 0;
    int rc = read_available(fd, rbuf, sizeof(rbuf), &rlen, 2000);
    close(fd);
    if (rc != 0 || rlen < 2 || rbuf[1] != 2) { /* 2 = METHOD_RETURN */
        xpd_log("WARN", "D-Bus SetProperty failed for %s at %s (rc=%d len=%u type=%u)",
                mac, path, rc, (unsigned)rlen, rlen > 1 ? (unsigned)rbuf[1] : 0u);
        return -1;
    }
    xpd_log("INFO", "trusted: %s set via D-Bus at %s (no stack restart)", mac, path);
    return 0;
}

static int set_trusted(const char *mac) {
    int pid = find_bt_pid();
    if (pid <= 0) {
        xpd_log("WARN", "D-Bus trust: bluetoothd pid not found");
        return -1;
    }
    char path[128];
    /* BlueZ 4 en Android incluye el PID del daemon en la ruta. */
    mac_to_path(path, sizeof(path), mac, pid);
    if (dbus_call_set_trusted(mac, path) == 0) return 0;

    /* Algunas builds de BlueZ 4 usan la ruta sin PID: segundo intento. */
    char dev[80];
    snprintf(dev, sizeof(dev), "dev_%s", mac);
    for (char *p = dev; *p; p++) if (*p == ':') *p = '_';
    snprintf(path, sizeof(path), "/org/bluez/hci0/%s", dev);
    return dbus_call_set_trusted(mac, path);
}

static int find_adapter_dir(char *out, size_t outlen) {
    DIR *d = opendir(BT_STORAGE);
    if (!d) return -1;
    struct dirent *e;
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        if (strlen(e->d_name) != 17) continue;
        snprintf(out, outlen, "%s/%s", BT_STORAGE, e->d_name);
        found = 1;
        break;
    }
    closedir(d);
    return found ? 0 : -1;
}

int dbus_trust_all(void) {
    char dir[128], trpath[192];
    if (find_adapter_dir(dir, sizeof(dir)) != 0) return -1;
    snprintf(trpath, sizeof(trpath), "%s/trusted", dir);
    FILE *f = fopen(trpath, "r");
    if (!f) return -1;
    int applied = 0, failed = 0;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        char mac[32], val[16];
        if (sscanf(line, "%31s %15s", mac, val) != 2) continue;
        if (strcmp(val, "true") != 0 || strlen(mac) != 17) continue;
        if (set_trusted(mac) == 0) applied++;
        else failed++;
    }
    fclose(f);
    return (applied > 0 && failed == 0) ? 0 : -1;
}
