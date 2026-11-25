/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include "hidp_server.h"
#include "log.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>

static unsigned long g_bp_count = 0;

unsigned long hidp_bp_count(void) { return g_bp_count; }
void hidp_bp_reset(void) { g_bp_count = 0; }

#ifndef AF_BLUETOOTH
#define AF_BLUETOOTH 31
#endif
#ifndef BTPROTO_L2CAP
#define BTPROTO_L2CAP 0
#endif

struct sockaddr_l2 {
    unsigned short l2_family;
    unsigned short l2_psm;
    unsigned char  l2_bdaddr[6];
    unsigned short l2_cid;
    unsigned char  l2_bdaddr_type;
};

/* HIDP message types (upper nibble) */
#define HIDP_HANDSHAKE     0x00
#define HIDP_HID_CONTROL   0x10
#define HIDP_GET_REPORT    0x40
#define HIDP_SET_REPORT    0x50
#define HIDP_GET_PROTOCOL  0x60
#define HIDP_SET_PROTOCOL  0x70
#define HIDP_GET_IDLE      0x80
#define HIDP_SET_IDLE      0x90
#define HIDP_DATA          0xA0
#define HIDP_DATC          0xB0

#define HIDP_HS_SUCCESS       0x00
#define HIDP_HS_NOT_READY     0x01
#define HIDP_HS_ERR_INVALID_REPORT_ID 0x02
#define HIDP_HS_ERR_UNSUPPORTED 0x03
#define HIDP_HS_ERR_INVALID_PARAMETER 0x04
#define HIDP_HS_ERR_UNKNOWN   0x0E
#define HIDP_HS_ERR_FATAL     0x0F

static int l2cap_listen_psm(uint16_t psm) {
    int s = socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP);
    if (s < 0) { xpd_log("ERR", "l2cap socket psm 0x%x: %s", psm, strerror(errno)); return -1; }
    struct sockaddr_l2 la;
    memset(&la, 0, sizeof(la));
    la.l2_family = AF_BLUETOOTH;
    la.l2_psm = psm;
    if (bind(s, (struct sockaddr *)&la, sizeof(la)) < 0) {
        xpd_log("ERR", "l2cap bind psm 0x%x: %s", psm, strerror(errno));
        close(s); return -1;
    }
    if (listen(s, 1) < 0) {
        xpd_log("ERR", "l2cap listen psm 0x%x: %s", psm, strerror(errno));
        close(s); return -1;
    }
    xpd_log("INFO", "HIDP listening on PSM 0x%x", psm);
    return s;
}

int hidp_listen(xpd_state_t *st) {
    st->l2cap_ctrl_listen = l2cap_listen_psm(0x11);
    st->l2cap_intr_listen = l2cap_listen_psm(0x13);
    if (st->l2cap_ctrl_listen < 0 || st->l2cap_intr_listen < 0) return -1;
    st->l2cap_ctrl = -1; st->l2cap_intr = -1;
    return 0;
}

void hidp_close_all(xpd_state_t *st) {
    if (st->l2cap_intr >= 0) { close(st->l2cap_intr); st->l2cap_intr = -1; }
    if (st->l2cap_ctrl >= 0) { close(st->l2cap_ctrl); st->l2cap_ctrl = -1; }
    if (st->l2cap_intr_listen >= 0) { close(st->l2cap_intr_listen); st->l2cap_intr_listen = -1; }
    if (st->l2cap_ctrl_listen >= 0) { close(st->l2cap_ctrl_listen); st->l2cap_ctrl_listen = -1; }
}

static void ctrl_write_best_effort(xpd_state_t *st, const uint8_t *buf, size_t len) {
    if (st->l2cap_ctrl < 0) return;
    ssize_t n = write(st->l2cap_ctrl, buf, len);
    if (n == (ssize_t)len) return;
    if (n < 0) {
        int e = errno;
        if (e == EAGAIN || e == EWOULDBLOCK) return; /* drop ctrl response best-effort */
        if (e == EPIPE || e == ECONNRESET || e == ENOTCONN || e == ESHUTDOWN || e == EBADF) {
            close(st->l2cap_ctrl); st->l2cap_ctrl = -1;
        }
    }
    /* parcial: best-effort, no bloquear/reintentar aqui */
}

static void send_handshake(xpd_state_t *st, uint8_t result) {
    if (st->l2cap_ctrl < 0) return;
    uint8_t pkt = HIDP_HANDSHAKE | (result & 0x0f);
    ctrl_write_best_effort(st, &pkt, 1);
}

static void send_data_ctrl(xpd_state_t *st, uint8_t param, const uint8_t *data, size_t len) {
    if (st->l2cap_ctrl < 0) return;
    uint8_t buf[64];
    buf[0] = HIDP_DATA | (param & 0x0f);
    if (len > sizeof(buf) - 1) len = sizeof(buf) - 1;
    memcpy(buf + 1, data, len);
    ctrl_write_best_effort(st, buf, 1 + len);
}

static void handle_control(xpd_state_t *st) {
    uint8_t buf[256];
    ssize_t n = read(st->l2cap_ctrl, buf, sizeof(buf));
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return;
        xpd_log("INFO", "HIDP control disconnected");
        if (st->l2cap_intr >= 0) { close(st->l2cap_intr); st->l2cap_intr = -1; }
        close(st->l2cap_ctrl); st->l2cap_ctrl = -1;
        return;
    }
    if (n == 0) {
        xpd_log("INFO", "HIDP control disconnected");
        if (st->l2cap_intr >= 0) { close(st->l2cap_intr); st->l2cap_intr = -1; }
        close(st->l2cap_ctrl); st->l2cap_ctrl = -1;
        return;
    }
    uint8_t type = buf[0] & 0xF0;
    uint8_t param = buf[0] & 0x0F;
    switch (type) {
    case HIDP_HID_CONTROL:
        xpd_log("INFO", "HID_CONTROL param=0x%x", param);
        send_handshake(st, HIDP_HS_SUCCESS);
        if (param == 0x03) { /* VIRTUAL_CABLE_UNPLUG */
            if (st->l2cap_intr >= 0) { close(st->l2cap_intr); st->l2cap_intr = -1; }
            close(st->l2cap_ctrl); st->l2cap_ctrl = -1;
        }
        break;
    case HIDP_GET_REPORT: {
        xpd_log("INFO", "GET_REPORT param=0x%x", param);
        uint8_t rep_id = (n >= 2) ? buf[1] : st->rpt03_id;
        if (rep_id == st->rpt03_id) {
            size_t l = st->last_report_03_len ? st->last_report_03_len : 10;
            uint8_t out[65];
            out[0] = rep_id; memcpy(out + 1, st->last_report_03, l);
            send_data_ctrl(st, 0x00 /* INPUT */, out, 1 + l);
        } else if (st->rpt01_len > 0 && rep_id == st->rpt01_id) {
            size_t l = st->last_report_01_len ? st->last_report_01_len : 8;
            uint8_t out[65];
            out[0] = rep_id; memcpy(out + 1, st->last_report_01, l);
            send_data_ctrl(st, 0x00, out, 1 + l);
        } else {
            send_handshake(st, HIDP_HS_ERR_INVALID_REPORT_ID);
        }
        break;
    }
    case HIDP_SET_REPORT:
        send_handshake(st, HIDP_HS_SUCCESS);
        break;
    case HIDP_GET_PROTOCOL:
        { uint8_t proto = 0x01; send_data_ctrl(st, 0, &proto, 1); }
        break;
    case HIDP_SET_PROTOCOL:
        send_handshake(st, HIDP_HS_SUCCESS);
        break;
    case HIDP_GET_IDLE:
        { uint8_t idle = 0x00; send_data_ctrl(st, 0, &idle, 1); }
        break;
    case HIDP_SET_IDLE:
        send_handshake(st, HIDP_HS_SUCCESS);
        break;
    case HIDP_DATA:
        /* output report desde host: ignorar */
        send_handshake(st, HIDP_HS_SUCCESS);
        break;
    default:
        xpd_log("WARN", "HIDP ctrl unknown type 0x%x", type);
        send_handshake(st, HIDP_HS_ERR_UNSUPPORTED);
        break;
    }
}

int hidp_handle_fds(xpd_state_t *st, int ctrl_l_ready, int intr_l_ready, int ctrl_ready, int intr_ready) {
    if (ctrl_l_ready && st->l2cap_ctrl_listen >= 0) {
        int fd = accept(st->l2cap_ctrl_listen, NULL, NULL);
        if (fd >= 0) {
            if (st->l2cap_ctrl < 0) {
                int flags = fcntl(fd, F_GETFL, 0);
                if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
                st->l2cap_ctrl = fd;
                xpd_log("INFO", "HIDP control connected");
            } else {
                close(fd); /* ya conectado: evita spin */
            }
        }
    }
    if (intr_l_ready && st->l2cap_intr_listen >= 0) {
        int fd = accept(st->l2cap_intr_listen, NULL, NULL);
        if (fd >= 0) {
            if (st->l2cap_intr < 0) {
                int flags = fcntl(fd, F_GETFL, 0);
                if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
                st->l2cap_intr = fd;
                xpd_log("INFO", "HIDP interrupt connected");
            } else {
                close(fd); /* ya conectado: evita spin */
            }
        }
    }
    if (ctrl_ready && st->l2cap_ctrl >= 0) handle_control(st);
    if (intr_ready && st->l2cap_intr >= 0) {
        uint8_t b[64]; ssize_t n = read(st->l2cap_intr, b, sizeof(b));
        if (n == 0) { close(st->l2cap_intr); st->l2cap_intr = -1; xpd_log("INFO", "HIDP interrupt disconnected"); }
        else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
            close(st->l2cap_intr); st->l2cap_intr = -1; xpd_log("INFO", "HIDP interrupt disconnected");
        }
    }
    return 0;
}

int hidp_send_report(xpd_state_t *st, uint8_t report_id, const uint8_t *data, size_t len) {
    /* cache último report siempre */
    if (report_id == st->rpt03_id && len <= sizeof(st->last_report_03)) {
        memcpy(st->last_report_03, data, len);
        st->last_report_03_len = len;
    } else if (st->rpt01_len > 0 && report_id == st->rpt01_id
            && len <= sizeof(st->last_report_01)) {
        memcpy(st->last_report_01, data, len);
        st->last_report_01_len = len;
    }

    if (st->l2cap_intr < 0) return -1;
    uint8_t buf[64];
    if (len + 2 > sizeof(buf)) return -1;
    buf[0] = 0xA1; /* DATA | INPUT */
    buf[1] = report_id;
    memcpy(buf + 2, data, len);
    ssize_t n = write(st->l2cap_intr, buf, 2 + len);
    if (n == (ssize_t)(2 + len)) return 0;
    if (n < 0) {
        int e = errno;
        if (e == EAGAIN || e == EWOULDBLOCK || e == ENOMEM || e == ENOBUFS) {
            /* backpressure/transitorio: reintentar sin perder estado (sent_* no avanza) */
            g_bp_count++;
            return -1;
        }
        xpd_log("WARN", "HIDP intr write err id=0x%02x: %s", report_id, strerror(e));
        if (e == EPIPE || e == ECONNRESET || e == ENOTCONN || e == ESHUTDOWN || e == EBADF) {
            close(st->l2cap_intr);
            st->l2cap_intr = -1;
        }
    } else {
        xpd_log("WARN", "HIDP intr partial write id=0x%02x: %zd/%zu", report_id, n, 2 + len);
    }
    return -1;
}
