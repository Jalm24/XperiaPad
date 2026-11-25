/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include "hci_class.h"
#include "log.h"
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>

/* Definiciones mínimas HCI (compatibles con BlueZ/Linux 2.6.x) */
#define BTPROTO_HCI 1
#define SOL_HCI 0
#define HCI_FILTER 2

#define HCI_COMMAND_PKT 0x01
#define HCI_EVENT_PKT   0x04
#define EVT_CMD_COMPLETE 0x0e

/* OGF Host Controller & Baseband = 0x03 */
#define cmd_opcode(ogf, ocf) ((uint16_t)((ocf & 0x03ff) | (ogf << 10)))
#define OCF_READ_CLASS_OF_DEV  0x0023
#define OCF_WRITE_CLASS_OF_DEV 0x0024
#define OCF_READ_SCAN_ENABLE   0x0019
#define OCF_WRITE_SCAN_ENABLE  0x001A
#define OCF_WRITE_EXT_INQUIRY_RESPONSE 0x0052

struct sockaddr_hci {
    sa_family_t hci_family;
    unsigned short hci_dev;
    unsigned short hci_channel;
};
#define HCI_CHANNEL_RAW 0

struct hci_filter {
    uint32_t type_mask;
    uint64_t event_mask[2];
    uint16_t opcode;
};

static int set_filter(int fd) {
    struct hci_filter flt;
    memset(&flt, 0, sizeof(flt));
    flt.type_mask = (1u << HCI_EVENT_PKT);
    flt.event_mask[0] = 0xffffffff;
    flt.event_mask[1] = 0xffffffff;
    flt.opcode = 0;
    return setsockopt(fd, SOL_HCI, HCI_FILTER, &flt, sizeof(flt));
}

int hci_open_dev(xpd_state_t *st) {
    int fd = socket(AF_BLUETOOTH, SOCK_RAW | SOCK_CLOEXEC, BTPROTO_HCI);
    if (fd < 0) {
        xpd_log("ERR", "hci socket: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_hci addr;
    memset(&addr, 0, sizeof(addr));
    addr.hci_family = AF_BLUETOOTH;
    addr.hci_dev = 0; /* hci0 */
    addr.hci_channel = HCI_CHANNEL_RAW;
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        xpd_log("ERR", "hci bind: %s", strerror(errno));
        close(fd);
        return -1;
    }
    if (set_filter(fd) < 0) {
        xpd_log("WARN", "hci filter: %s", strerror(errno));
    }
    struct timeval tv; tv.tv_sec = 2; tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    st->bt_fd = fd;
    xpd_log("INFO", "hci0 open");
    return 0;
}

void hci_close_dev(xpd_state_t *st) {
    if (st->bt_fd >= 0) { close(st->bt_fd); st->bt_fd = -1; }
}

/* Escritura "dispara y olvida": en esta ROM la respuesta del comando no vuelve
 * al socket raw, pero el comando SI se aplica (verificado: CoD/EIR/scan). */
static int hci_cmd_write(xpd_state_t *st, uint16_t opcode, void *params, uint8_t plen) {
    if (st->bt_fd < 0) return -1;
    uint8_t pkt[260];
    pkt[0] = HCI_COMMAND_PKT;
    pkt[1] = opcode & 0xff;
    pkt[2] = (opcode >> 8) & 0xff;
    pkt[3] = plen;
    if (plen && params) memcpy(pkt + 4, params, plen);
    ssize_t wn = write(st->bt_fd, pkt, 4 + plen);
    if (wn < 0) {
        xpd_log("ERR", "hci_cmd write ocf=0x%04x: %s", opcode, strerror(errno));
        return -1;
    }
    return 0;
}

int hci_disconnect_acl(xpd_state_t *st, uint16_t handle, uint8_t reason) {
    uint8_t params[3];
    params[0] = handle & 0xff;
    params[1] = (handle >> 8) & 0xff;
    params[2] = reason; /* 0x13 = remote user terminated connection */
    return hci_cmd_write(st, cmd_opcode(0x01, 0x0006), params, sizeof(params));
}

static int hci_cmd(xpd_state_t *st, uint16_t opcode, void *params, uint8_t plen, uint8_t *rsp, size_t rsp_sz) {
    if (st->bt_fd < 0) return -1;
    uint8_t pkt[260];
    pkt[0] = HCI_COMMAND_PKT;
    pkt[1] = opcode & 0xff;
    pkt[2] = (opcode >> 8) & 0xff;
    pkt[3] = plen;
    if (plen && params) memcpy(pkt + 4, params, plen);
    ssize_t wn = write(st->bt_fd, pkt, 4 + plen);
    if (wn < 0) {
        xpd_log("ERR", "hci_cmd write ocf=0x%04x: %s", opcode, strerror(errno));
        return -1;
    }
    /* leer eventos hasta Command Complete del opcode.
     * Formato: [0]=0x04 [1]=0x0E [2]=plen [3]=ncmd [4..5]=opcode [6]=status [7..]=params.
     * rsp = copia desde buf+3 => rsp[0]=ncmd rsp[1..2]=opcode rsp[3]=STATUS rsp[4..]=params. */
    for (int i = 0; i < 20; i++) {
        uint8_t buf[260]; ssize_t n = read(st->bt_fd, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) break;
            xpd_log("ERR", "hci_cmd read ocf=0x%04x: %s", opcode, strerror(errno));
            break;
        }
        if (st->hci_dbg < 6) {
            st->hci_dbg++;
            xpd_log("INFO", "hci evt: n=%d type=0x%02x code=0x%02x b3=%02x b4=%02x b5=%02x b6=%02x",
                    (int)n, buf[0], (n > 1 ? buf[1] : 0), (n > 3 ? buf[3] : 0),
                    (n > 4 ? buf[4] : 0), (n > 5 ? buf[5] : 0), (n > 6 ? buf[6] : 0));
        }
        if (n < 7) continue;
        uint16_t rop = buf[4] | (buf[5] << 8);
        if (rop != opcode) continue;
        size_t cpy = (size_t)(n - 3); if (cpy > rsp_sz) cpy = rsp_sz;
        if (rsp && rsp_sz) memcpy(rsp, buf + 3, cpy);
        return 0;
    }
    xpd_log("ERR", "hci_cmd no CMD_COMPLETE ocf=0x%04x", opcode);
    return -1;
}

int hci_read_cod(xpd_state_t *st, uint32_t *cod) {
    uint8_t rsp[16];
    if (hci_cmd(st, cmd_opcode(0x03, OCF_READ_CLASS_OF_DEV), NULL, 0, rsp, sizeof(rsp)) < 0) return -1;
    /* rsp[3]=status, rsp[4..6]=cod */
    if (rsp[3] != 0x00) return -1;
    *cod = rsp[4] | (rsp[5] << 8) | (rsp[6] << 16);
    return 0;
}

int hci_write_cod(xpd_state_t *st, uint32_t cod) {
    uint8_t p[3] = { cod & 0xff, (cod >> 8) & 0xff, (cod >> 16) & 0xff };
    return hci_cmd_write(st, cmd_opcode(0x03, OCF_WRITE_CLASS_OF_DEV), p, 3);
}

/* Scan enable: 0x02 = page (conectable), 0x03 = page+inquiry (visible) */
int hci_write_scan_enable(xpd_state_t *st, uint8_t scan) {
    return hci_cmd_write(st, cmd_opcode(0x03, OCF_WRITE_SCAN_ENABLE), &scan, 1);
}

int hci_read_scan_enable(xpd_state_t *st, uint8_t *out) {
    uint8_t rsp[16];
    if (hci_cmd(st, cmd_opcode(0x03, OCF_READ_SCAN_ENABLE), NULL, 0, rsp, sizeof(rsp)) < 0) return -1;
    if (rsp[3] != 0x00) return -1;
    *out = rsp[4];
    return 0;
}

int hci_write_eir_gamepad(xpd_state_t *st, const char *name) {
    /* EIR: 1 byte FEC + 240 bytes data */
    uint8_t buf[241];
    memset(buf, 0, sizeof(buf));
    buf[0] = 0x00; /* FEC */
    uint8_t *p = buf + 1;
    uint8_t *end = buf + 241;
    /* Complete Local Name (0x09) */
    size_t nlen = name ? strlen(name) : 0; if (nlen > 48) nlen = 48;
    if (nlen > 0 && p + 2 + nlen <= end) {
        p[0] = (uint8_t)(nlen + 1); p[1] = 0x09; memcpy(p + 2, name, nlen); p += 2 + nlen;
    }
    /* 16-bit UUIDs complete (0x03): HID 0x1124 */
    if (p + 4 <= end) { p[0] = 3; p[1] = 0x03; p[2] = 0x24; p[3] = 0x11; p += 4; }
    /* Device ID (0x10): misma identidad que el registro SDP del modo activo
     * (EIR y SDP deben coincidir: antes EIR tenia 0f0d:1011 de GameStick) */
    if (p + 10 <= end) {
        uint16_t vid = st->di_vid ? st->di_vid : 0x0001;
        uint16_t pid = st->di_pid ? st->di_pid : 0x0001;
        p[0] = 9; p[1] = 0x10;
        p[2] = 0x02; p[3] = 0x00; /* source USB */
        p[4] = (uint8_t)(vid & 0xff); p[5] = (uint8_t)(vid >> 8); /* VID */
        p[6] = (uint8_t)(pid & 0xff); p[7] = (uint8_t)(pid >> 8); /* PID */
        p[8] = 0x00; p[9] = 0x01; /* version */
        p += 10;
    }
    return hci_cmd_write(st, cmd_opcode(0x03, OCF_WRITE_EXT_INQUIRY_RESPONSE), buf, sizeof(buf));
}

int hci_write_eir_clear(xpd_state_t *st) {
    uint8_t buf[241];
    memset(buf, 0, sizeof(buf)); /* FEC 0 + datos vacíos */
    return hci_cmd_write(st, cmd_opcode(0x03, OCF_WRITE_EXT_INQUIRY_RESPONSE), buf, sizeof(buf));
}
