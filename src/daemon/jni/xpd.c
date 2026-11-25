/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

#include "xpd.h"
#include "log.h"
#include "uds.h"
#include "hci_class.h"
#include "sdp_register.h"
#include "hidp_server.h"
#include "cleanup.h"
#include "trusted.h"
#include "dbus_trusted.h"
#include "bluetooth.h"
#include "hci.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <sys/select.h>
#include <sys/time.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <ctype.h>

static xpd_state_t g_st;

static uint8_t sent_03[64];
static size_t sent_03_len = 0;
static uint8_t sent_01[64];
static size_t sent_01_len = 0;
static int sent_valid = 0;

static long long now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000LL + tv.tv_usec / 1000;
}

static int reports_pending(void) {
    if (!g_st.armed || g_st.l2cap_intr < 0) return 0;
    if (!sent_valid) return 1;
    if (sent_03_len != g_st.last_report_03_len
            || memcmp(sent_03, g_st.last_report_03, sent_03_len) != 0) return 1;
    if (g_st.rpt01_len > 0 && (sent_01_len != g_st.last_report_01_len
            || memcmp(sent_01, g_st.last_report_01, sent_01_len) != 0)) return 1;
    return 0;
}

static void reply_client(const char *msg) {
    if (g_st.client_fd < 0 || !msg) return;
    size_t len = strlen(msg);
    ssize_t n = write(g_st.client_fd, msg, len);
    (void)n; /* best-effort: no bloquear si la app no drena */
}

static void mark_sent_reset(void) {
    sent_valid = 0;
    memset(sent_03, 0, sizeof(sent_03));
    memset(sent_01, 0, sizeof(sent_01));
    sent_03_len = 0;
    sent_01_len = 0;
}

static void set_neutral_reports(void) {
    memset(g_st.last_report_03, 0, sizeof(g_st.last_report_03));
    g_st.last_report_03_len = g_st.rpt03_len ? g_st.rpt03_len : 10;
    if (g_st.rpt03_id == 0x01) {
        /* DS3: sticks centro 128, batería "cargando/llena" (0xEE), acelerómetro
         * en centro (SwapEndian==511 -> bytes 01 FF por eje) */
        g_st.last_report_03[5] = 128; g_st.last_report_03[6] = 128;
        g_st.last_report_03[7] = 128; g_st.last_report_03[8] = 128;
        g_st.last_report_03[29] = 0xEE;
        g_st.last_report_03[40] = 0x01; g_st.last_report_03[41] = 0xFF;
        g_st.last_report_03[42] = 0x01; g_st.last_report_03[43] = 0xFF;
        g_st.last_report_03[44] = 0x01; g_st.last_report_03[45] = 0xFF;
        g_st.last_report_03[46] = 0x01; g_st.last_report_03[47] = 0xFF;
    } else {
        /* generico/gamestick/gembox: LX,LY,RX,RY centro 128 */
        g_st.last_report_03[1] = 128; g_st.last_report_03[2] = 128;
        g_st.last_report_03[3] = 128; g_st.last_report_03[4] = 128;
        if (g_st.rpt03_id == 0x03) g_st.last_report_03[5] = 0x08; /* hat null */
    }
    memset(g_st.last_report_01, 0, sizeof(g_st.last_report_01));
    g_st.last_report_01_len = g_st.rpt01_len;
    mark_sent_reset();
}

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + c - 'a';
    if (c >= 'A' && c <= 'F') return 10 + c - 'A';
    return -1;
}

static int parse_hex(const char *s, uint8_t *out, size_t outlen) {
    size_t n = 0;
    while (*s && n < outlen) {
        while (*s && isspace((unsigned char)*s)) s++;
        if (!*s) break;
        int h1 = hexval(*s++); if (h1 < 0) return -1;
        if (!*s) return -1;
        int h2 = hexval(*s++); if (h2 < 0) return -1;
        out[n++] = (uint8_t)((h1 << 4) | h2);
    }
    return (int)n;
}

static void send_reports_if_armed(void) {
    if (!g_st.armed) return;
    if (g_st.l2cap_intr < 0) return;
    size_t l3 = g_st.last_report_03_len ? g_st.last_report_03_len : 10;
    if (!sent_valid || sent_03_len != l3
            || memcmp(sent_03, g_st.last_report_03, l3) != 0) {
        if (hidp_send_report(&g_st, g_st.rpt03_id, g_st.last_report_03, l3) == 0) {
            memcpy(sent_03, g_st.last_report_03, l3);
            sent_03_len = l3;
            sent_valid = 1;
        } else {
            return; /* reintento luego, conserva estado pendiente */
        }
    }
    if (g_st.rpt01_len == 0) return; /* modo sin report secundario (ds3) */
    size_t l1 = g_st.last_report_01_len ? g_st.last_report_01_len : 8;
    if (!sent_valid || sent_01_len != l1
            || memcmp(sent_01, g_st.last_report_01, l1) != 0) {
        if (hidp_send_report(&g_st, g_st.rpt01_id, g_st.last_report_01, l1) == 0) {
            memcpy(sent_01, g_st.last_report_01, l1);
            sent_01_len = l1;
        }
    }
}

static void send_neutral_and_disarm(void) {
    set_neutral_reports();
    /* enviar neutro aunque se desarme, para soltar teclas si hay enlace */
    if (g_st.l2cap_intr >= 0) {
        hidp_send_report(&g_st, g_st.rpt03_id, g_st.last_report_03,
                g_st.last_report_03_len ? g_st.last_report_03_len : 10);
        if (g_st.rpt01_len > 0) {
            hidp_send_report(&g_st, g_st.rpt01_id, g_st.last_report_01,
                    g_st.last_report_01_len ? g_st.last_report_01_len : 8);
        }
    }
    g_st.armed = 0;
    g_st.keepalive_ticks = 0;
}

static void on_signal(int sig) {
    (void)sig;
    g_st.running = 0;
}

/** Reafirma la identidad gamepad tras cualquier cambio bluetoothd de CoD/EIR
 * (H1, docs/03 §6.1). Solo escrituras: sin reads que bloqueen el bucle. */
static void reassert_gamepad_identity(xpd_state_t *st) {
    if (st->bt_fd < 0) return;
    hci_write_cod(st, COD_GAMEPAD);
    hci_write_eir_gamepad(st, "Xperia PLAY Gamepad");
}

/* H3: el host cachea SDP al conectar. Re-registrar con enlace vivo deja al
 * host sin perfil HID hasta un reconnect. Persistimos la identidad para no
 * tocar SDP cuando no cambio. */
static char g_statepath[256];

static void sdp_state_path(const char *logpath, char *out, size_t n) {
    const char *slash = strrchr(logpath, '/');
    if (!slash) { snprintf(out, n, "xpd.sdp.state"); return; }
    size_t dl = (size_t)(slash - logpath);
    if (dl + 32 > n) dl = n - 32;
    memcpy(out, logpath, dl);
    snprintf(out + dl, n - dl, "/xpd.sdp.state");
}

static int sdp_state_matches(const char *path, uint16_t vid, uint16_t pid) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[64], want[32];
    int ok = 0;
    if (fgets(line, sizeof(line), f)) {
        snprintf(want, sizeof(want), "%04x:%04x", vid, pid);
        ok = (strncmp(line, want, strlen(want)) == 0);
    }
    fclose(f);
    return ok;
}

static void sdp_state_save(const char *path, uint16_t vid, uint16_t pid) {
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "%04x:%04x\n", vid, pid);
    fclose(f);
}

/** H3: bajar los ACL activos para que el host vuelva a resolver SDP y abra
 * HIDP. Se usa solo bajo accion explicita desde la app. */
static int drop_all_acl(xpd_state_t *st) {
    if (st->bt_fd < 0) return -1;
    uint32_t buf[(sizeof(struct hci_conn_list_req) + 16 * sizeof(struct hci_conn_info) + 3) / 4];
    struct hci_conn_list_req *cl = (struct hci_conn_list_req *)buf;
    memset(buf, 0, sizeof(buf));
    cl->dev_id = 0; /* hci0 */
    cl->conn_num = 16;
    if (ioctl(st->bt_fd, HCIGETCONNLIST, cl) < 0) {
        xpd_log("WARN", "LINKRESET: HCIGETCONNLIST failed (%s)", strerror(errno));
        return -1;
    }
    int dropped = 0;
    for (int i = 0; i < cl->conn_num && i < 16; i++) {
        struct hci_conn_info *ci = &cl->conn_info[i];
        if (ci->type != ACL_LINK) continue;
        if (hci_disconnect_acl(st, ci->handle, 0x13 /* remote user term */) == 0) dropped++;
        else xpd_log("WARN", "LINKRESET: disconnect 0x%x failed",
                     (unsigned)ci->handle);
    }
    return dropped;
}

/* Auto-trust (E27): marca hosts enlazados sin entrada en trusted. Tras
 * agregar entradas hay que reiniciar bluetoothd (el framework lo respawnea
 * y carga el flag) y re-registrar nuestro SDP + CoD/EIR. Solo cuando no
 * hay sesion HIDP activa: reiniciar el stack con una sesion viva la corta. */
static void trusted_watch(xpd_state_t *st) {
    if (st->bt_fd < 0) return;
    /* nunca re-registrar SDP ni matar bluetoothd con sesion HIDP viva */
    if (st->l2cap_intr >= 0 || st->l2cap_ctrl >= 0) return;
    long long tnow = now_ms();

    if (st->sdp_rereg_pending) {
        if (tnow >= st->sdp_rereg_at) {
            sdp_cleanup_handles(st);
            int sdpok = (sdp_register_all(st) == 0);
            int hciok = 0;
            if (sdpok) {
                hciok = (hci_write_cod(st, COD_GAMEPAD) == 0);
                if (hciok) hci_write_eir_gamepad(st, "Xperia PLAY Gamepad");
                if (!hciok) {
                    /* el restart del stack invalido el socket HCI: reabrir y reintentar */
                    if (st->bt_fd >= 0) close(st->bt_fd);
                    st->bt_fd = -1;
                    if (hci_open_dev(st) == 0) {
                        hciok = (hci_write_cod(st, COD_GAMEPAD) == 0);
                        if (hciok) hci_write_eir_gamepad(st, "Xperia PLAY Gamepad");
                    }
                }
            }
            if (sdpok && hciok) {
                st->sdp_rereg_pending = 0;
                sdp_state_save(g_statepath, st->di_vid, st->di_pid);
                xpd_log("INFO", "SDP/CoD/EIR re-asserted after bluetoothd respawn");
            } else {
                st->sdp_rereg_at = tnow + 4000;
                xpd_log("WARN", "re-assertion incomplete (sdp=%d hci=%d); retrying", sdpok, hciok);
            }
        }
        return;
    }

    if (tnow < st->first_sweep_at) return; /* gracia inicial (bluetoothd recien arrancado) */

    /* Tras cualquier respawn de bluetoothd la memoria pierde el flag trusted;
     * reaplicar una vez desde el archivo persistente sin reiniciar el stack. */
    static int dbus_initial_applied = 0;
    if (!dbus_initial_applied) {
        dbus_initial_applied = 1;
        if (dbus_trust_all() == 0) xpd_log("INFO", "D-Bus trust initial apply OK");
        else xpd_log("WARN", "D-Bus trust initial apply failed; will retry on next bond");
    }

    int written = 0;
    int added = trusted_sweep(&written);
    if (added <= 0) return;

    /* E27 mejorado: aplicar Trusted por D-Bus. El killall hacia que Android
     * interpretara el respawn como crash y apagara/encendiera el BT. */
    if (dbus_trust_all() == 0) {
        xpd_log("INFO", "trusted: %d new host(s); applied via D-Bus (no stack restart)", added);
        return;
    }
    xpd_log("WARN", "D-Bus trust failed; falling back to bluetoothd restart");
    system("killall bluetoothd 2>/dev/null; busybox killall bluetoothd 2>/dev/null; true");
    st->sdp_rereg_pending = 1;
    st->sdp_rereg_at = tnow + 6000; /* dar tiempo al respawn del framework */
}

static void usage(const char *argv0) {
    fprintf(stderr, "Usage: %s --uid UID [--log PATH]\n", argv0);
}

int main(int argc, char **argv) {
    const char *logpath = XPD_LOG_PATH_DEFAULT;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--log") && i + 1 < argc) { logpath = argv[++i]; }
        /* C2: la app pasa su UID: solo ella podra hablar por @xperiapad */
        else if (!strcmp(argv[i], "--uid") && i + 1 < argc) {
            uid_t u = (uid_t)strtoul(argv[++i], NULL, 10);
            if (u > 0) uds_set_allowed_uid(u, 0);
        }
        else if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) { usage(argv[0]); return 0; }
    }

    memset(&g_st, 0, sizeof(g_st));
    g_st.bt_fd = -1; g_st.uds_fd = -1; g_st.client_fd = -1;
    g_st.ctl_fd = -1; g_st.ctl_client_fd = -1; g_st.had_client = 0; g_st.last_client_ms = 0;
    g_st.client_buf_len = 0;
    g_st.l2cap_ctrl_listen = -1; g_st.l2cap_intr_listen = -1;
    g_st.l2cap_ctrl = -1; g_st.l2cap_intr = -1;
    g_st.running = 1;
    g_st.active = 0;
    g_st.armed = 0;
    g_st.assert_ticks = 0;
    g_st.keepalive_ticks = 0;
    g_st.di_vid = 0x0001; g_st.di_pid = 0x0001; /* generico por defecto (E20) */
    g_st.rpt03_id = 0x03; g_st.rpt03_len = 10;
    g_st.rpt01_id = 0x01; g_st.rpt01_len = 8;
    g_st.reassert_ticks = 0;
    set_neutral_reports();

    xpd_log_init(logpath);
    xpd_log("INFO", "xpd starting (uid=%d euid=%d)", (int)getuid(), (int)geteuid());

    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);

    if (hci_open_dev(&g_st) == 0) {
        uint32_t cod = 0;
        if (hci_read_cod(&g_st, &cod) == 0) {
            g_st.original_cod = cod;
            g_st.have_original_cod = 1;
            xpd_log("INFO", "original CoD: 0x%06x", cod);
        } else {
            g_st.original_cod = COD_PHONE_DEFAULT;
            g_st.have_original_cod = 1;
            xpd_log("WARN", "could not read CoD; using default 0x%06x", g_st.original_cod);
        }
    } else {
        xpd_log("ERR", "could not open HCI (root? BT on?)");
        /* seguimos: la app puede reintentar tras activar BT */
    }

    if (uds_listen(&g_st) != 0 || uds_listen_ctl(&g_st) != 0) {
        xpd_cleanup(&g_st);
        xpd_log_close();
        return 1;
    }

    /* Auto-start: activar modo gamepad (equivalente a START) para validación.
     * H3: si la identidad no cambio, reutilizar los records SDP existentes y
     * no invalidar el cache del host. */
    sdp_state_path(logpath, g_statepath, sizeof(g_statepath));
    /* El archivo de estado alone no basta: STOP borra los records del server
     * SDP. Reutilizar solo si los dos records siguen realmente registrados. */
    int sdp_cached = sdp_state_matches(g_statepath, g_st.di_vid, g_st.di_pid)
            && sdp_our_records_present();
    if (!sdp_cached) sdp_cleanup_handles(&g_st);
    if ((sdp_cached || sdp_register_all(&g_st) == 0) && hidp_listen(&g_st) == 0) {
        g_st.active = 1;
        g_st.assert_ticks = 0;
        reassert_gamepad_identity(&g_st);
        sdp_state_save(g_statepath, g_st.di_vid, g_st.di_pid);
        xpd_log("INFO", "auto-start: gamepad mode active (SDP %s)",
                sdp_cached ? "reused" : "registered");
    } else {
        xpd_log("ERR", "auto-start failed; waiting for commands");
    }

    xpd_log("INFO", "xpd ready; waiting for commands");

    long long last_keepalive_ms = now_ms();
    long long last_stats_ms = now_ms();
    g_st.first_sweep_at = now_ms() + 15000; /* gracia inicial para el sweep trusted */
    hidp_bp_reset();

    while (g_st.running) {
        fd_set rfds;
        FD_ZERO(&rfds);
        int maxfd = -1;
        if (g_st.uds_fd >= 0) { FD_SET(g_st.uds_fd, &rfds); if (g_st.uds_fd > maxfd) maxfd = g_st.uds_fd; }
        if (g_st.ctl_fd >= 0) { FD_SET(g_st.ctl_fd, &rfds); if (g_st.ctl_fd > maxfd) maxfd = g_st.ctl_fd; }
        if (g_st.ctl_client_fd >= 0) { FD_SET(g_st.ctl_client_fd, &rfds); if (g_st.ctl_client_fd > maxfd) maxfd = g_st.ctl_client_fd; }
        if (g_st.client_fd >= 0) { FD_SET(g_st.client_fd, &rfds); if (g_st.client_fd > maxfd) maxfd = g_st.client_fd; }
        if (g_st.l2cap_ctrl_listen >= 0) { FD_SET(g_st.l2cap_ctrl_listen, &rfds); if (g_st.l2cap_ctrl_listen > maxfd) maxfd = g_st.l2cap_ctrl_listen; }
        if (g_st.l2cap_intr_listen >= 0) { FD_SET(g_st.l2cap_intr_listen, &rfds); if (g_st.l2cap_intr_listen > maxfd) maxfd = g_st.l2cap_intr_listen; }
        if (g_st.l2cap_ctrl >= 0) { FD_SET(g_st.l2cap_ctrl, &rfds); if (g_st.l2cap_ctrl > maxfd) maxfd = g_st.l2cap_ctrl; }
        if (g_st.l2cap_intr >= 0) { FD_SET(g_st.l2cap_intr, &rfds); if (g_st.l2cap_intr > maxfd) maxfd = g_st.l2cap_intr; }

        struct timeval tv;
        tv.tv_sec = 0; tv.tv_usec = 5000; /* 5ms: baseline estable */
        int r = select(maxfd + 1, &rfds, NULL, NULL, &tv);

        /* reintento si quedó estado pendiente (p.ej. backpressure) */
        if (g_st.armed && g_st.l2cap_intr >= 0) {
            send_reports_if_armed();
        }

        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }

        long long tnow = now_ms();
        if (g_st.armed && g_st.l2cap_intr >= 0) {
            if (tnow - last_keepalive_ms >= 1000) { /* ~1s keepalive, forzar envío */
                last_keepalive_ms = tnow;
                mark_sent_reset();
                send_reports_if_armed();
            }
        } else {
            last_keepalive_ms = tnow;
        }
        if (tnow - last_stats_ms >= 2000) {
            unsigned long bp = hidp_bp_count();
            int pend = reports_pending();
            xpd_log("INFO", "stats: bp=%lu pending=%d armed=%d intr=%d", bp, pend, g_st.armed, g_st.l2cap_intr >= 0);
            hidp_bp_reset();
            last_stats_ms = tnow;
            /* 120s sin cliente principal = app cerrada: apagar el daemon suelto */
            if (g_st.had_client && g_st.client_fd < 0 && g_st.last_client_ms > 0
                    && tnow - g_st.last_client_ms >= 120000) {
                xpd_log("INFO", "no client for 120s: stopping daemon");
                g_st.running = 0;
                break;
            }
            trusted_watch(&g_st);
        }

        if (r == 0) {
            continue;
        }

        /* socket de sondeo: responde y cierra SIN tocar el cliente principal */
        if (g_st.ctl_fd >= 0 && FD_ISSET(g_st.ctl_fd, &rfds)) uds_accept_ctl(&g_st);
        if (g_st.ctl_client_fd >= 0 && FD_ISSET(g_st.ctl_client_fd, &rfds)) {
            char tmp[128];
            ssize_t cn = read(g_st.ctl_client_fd, tmp, sizeof(tmp) - 1);
            if (cn <= 0) {
                uds_ctl_close(&g_st);
            } else {
                tmp[cn] = '\0';
                char *cline = tmp;
                while (*cline == ' ' || *cline == '\n' || *cline == '\r') cline++;
                /* PING del ancla es solo keepalive: NO cuenta como cliente real.
                 * Si contara, el autostop de 120s nunca llegaria a dispararse. */
                int is_ping = !strncmp(cline, "PING", 4);
                if (!is_ping) {
                    g_st.had_client = 1;
                    g_st.last_client_ms = now_ms();
                }
                char rep[160];
                if (is_ping) snprintf(rep, sizeof(rep), "PONG\n");
                else if (!strncmp(cline, "STATE", 5))
                    snprintf(rep, sizeof(rep), "OK STATE active=%d armed=%d bt=%d\n",
                            g_st.active, g_st.armed, g_st.bt_fd >= 0);
                else if (!strncmp(cline, "SCAN ?", 6))
                    snprintf(rep, sizeof(rep), "OK SCAN 0\n");
                else snprintf(rep, sizeof(rep), "ERR CMD\n");
                ssize_t wr = write(g_st.ctl_client_fd, rep, strlen(rep));
                (void)wr;
                uds_ctl_close(&g_st); // una orden por conexion de sondeo
            }
        }

        if (g_st.uds_fd >= 0 && FD_ISSET(g_st.uds_fd, &rfds)) {
            if (uds_accept(&g_st) == 0) {
                g_st.had_client = 1;
                g_st.last_client_ms = 0;
            }
            g_st.client_buf_len = 0;
        }
        if (g_st.client_fd >= 0 && FD_ISSET(g_st.client_fd, &rfds)) {
            char tmp[512];
            ssize_t n = read(g_st.client_fd, tmp, sizeof(tmp));
            if (n <= 0) {
                xpd_log("INFO", "client disconnected -> disarm (neutral), daemon stays");
                send_neutral_and_disarm();
                close(g_st.client_fd);
                g_st.client_fd = -1;
                g_st.client_buf_len = 0;
                g_st.last_client_ms = now_ms(); // gracia 120s para autostop
                continue;
            }
            if (g_st.client_buf_len + n > (ssize_t)sizeof(g_st.client_buf) - 1) {
                /* overflow: descartar buffer para resync */
                g_st.client_buf_len = 0;
            }
            memcpy(g_st.client_buf + g_st.client_buf_len, tmp, n);
            g_st.client_buf_len += (int)n;
            g_st.client_buf[g_st.client_buf_len] = '\0';

            int start = 0;
            for (int i = 0; i < g_st.client_buf_len; i++) {
                if (g_st.client_buf[i] == '\n' || g_st.client_buf[i] == '\r') {
                    g_st.client_buf[i] = '\0';
                    char *buf = g_st.client_buf + start;
                    /* trim fin */
                    int blen = (int)strlen(buf);
                    while (blen > 0 && (buf[blen-1] == '\n' || buf[blen-1] == '\r' || buf[blen-1] == ' ' || buf[blen-1] == '\t')) buf[--blen] = '\0';
                    /* saltar vacías */
                    char *p = buf;
                    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
                    if (*p == '\0') { start = i + 1; continue; }
                    buf = p;
                    xpd_log("INFO", "cmd: %s", buf);

                    if (strncmp(buf, "START", 5) == 0) {
                if (g_st.active) {
                    xpd_log("INFO", "START received while active, restarting...");
                    send_neutral_and_disarm();
                    hidp_close_all(&g_st);
                    sdp_cleanup_handles(&g_st);
                    g_st.active = 0;
                }
                sdp_cleanup_handles(&g_st);
                if (sdp_register_all(&g_st) == 0 && hidp_listen(&g_st) == 0) {
                    g_st.active = 1;
                    g_st.assert_ticks = 0;
                    reassert_gamepad_identity(&g_st);
                    reply_client("OK START\n");
                } else {
                    reply_client("ERR START\n");
                }
            } else if (strncmp(buf, "STOP", 4) == 0 || strncmp(buf, "QUIT", 4) == 0) {
                reply_client("OK STOP\n");
                send_neutral_and_disarm();
                xpd_cleanup(&g_st);
                break;
            } else if (strncmp(buf, "ARM", 3) == 0) {
                int on = 1;
                if (buf[3] == ' ' || buf[3] == '\t') on = atoi(buf + 4);
                else if (buf[3] == '\0') on = 1;
                if (on) {
                    g_st.armed = 1;
                    g_st.keepalive_ticks = 0;
                    send_reports_if_armed();
                    xpd_log("INFO", "ARM=1");
                    reply_client("OK ARM\n");
                } else {
                    send_neutral_and_disarm();
                    xpd_log("INFO", "ARM=0 (neutral sent)");
                    reply_client("OK DISARM\n");
                }
            } else if (strncmp(buf, "DISARM", 6) == 0) {
                send_neutral_and_disarm();
                xpd_log("INFO", "DISARM (neutral sent)");
                reply_client("OK DISARM\n");
            } else if (strncmp(buf, "NEUTRAL", 7) == 0) {
                set_neutral_reports();
                send_reports_if_armed();
                reply_client("OK NEUTRAL\n");
            } else if (strncmp(buf, "R3 ", 3) == 0) {
                uint8_t tmp[64];
                int want = g_st.rpt03_len ? g_st.rpt03_len : 10;
                int got = parse_hex(buf + 3, tmp, sizeof(tmp));
                if (got == want) {
                    memcpy(g_st.last_report_03, tmp, got);
                    g_st.last_report_03_len = got;
                    send_reports_if_armed(); /* enviar transición (dedup) */
                    reply_client("OK R3\n");
                } else {
                    reply_client("ERR R3 hex\n");
                }
            } else if (strncmp(buf, "R1 ", 3) == 0) {
                if (g_st.rpt01_len == 0) { reply_client("OK R1\n"); }
                else {
                uint8_t tmp[64];
                int want = g_st.rpt01_len;
                int got = parse_hex(buf + 3, tmp, sizeof(tmp));
                if (got == want) {
                    memcpy(g_st.last_report_01, tmp, got);
                    g_st.last_report_01_len = got;
                    send_reports_if_armed(); /* enviar transición (dedup) */
                    reply_client("OK R1\n");
                } else {
                    reply_client("ERR R1 hex\n");
                }
                }
            } else if (strncmp(buf, "STATE", 5) == 0) {
                /* C5: la app consulta si el daemon esta realmente activo antes
                 * de dar por bueno el arranque (PING puede responder con active=0) */
                char rep[96];
                snprintf(rep, sizeof(rep), "OK STATE active=%d armed=%d bt=%d\n",
                        g_st.active, g_st.armed, g_st.bt_fd >= 0);
                reply_client(rep);
            } else if (strncmp(buf, "BTRESTART", 9) == 0) {
                /* C4: la app mato bluetoothd (BT-Patch); re-registrar SDP/CoD/EIR
                 * cuando el framework lo respawnee (reintento cada 4s) */
                g_st.sdp_rereg_pending = 1;
                g_st.sdp_rereg_at = now_ms() + 5000;
                xpd_log("INFO", "BTRESTART: SDP re-registration pending");
                reply_client("OK BTRESTART\n");
            } else if (strncmp(buf, "MODE", 4) == 0) {
                /* Modo de identidad del registro SDP Device ID (E27):
                 * generic -> 0001:0001 (web/PC), ds3 -> 054c:0268 (MissionControl).
                 * La consola guarda VID/PID al emparejar: cambiar el modo ANTES
                 * del bonding; si ya estaba emparejado, borrar el host y re-parear. */
                const char *arg = buf + 4;
                while (*arg == ' ' || *arg == '\t') arg++;
                /* identidad SDP + formato de report por consola (E27/E28):
                 *   generic -> 0001:0001 (web/PC) | 0x03 x10, 0x01 x8
                 *   ds3     -> 054c:0268 (MC)     | 0x01 x48, sin secundario
                 * (identidades retiradas: contratos historicos en docs/08 E28.)
                 * La consola guarda VID/PID al emparejar: cambiar el modo ANTES
                 * del bonding; si ya estaba emparejado, borrar el host y re-parear. */
                uint16_t nv, np; uint8_t n3i, n3l, n1i, n1l;
                if (strncmp(arg, "ds3", 3) == 0) { nv=0x054c; np=0x0268; n3i=0x01; n3l=48; n1i=0; n1l=0; }
                else if (strncmp(arg, "generic", 7) == 0) { nv=0x0001; np=0x0001; n3i=0x03; n3l=10; n1i=0x01; n1l=8; }
                else { reply_client("ERR MODE (generic|ds3)\n"); start = i + 1; continue; }
                int changed = (nv != g_st.di_vid) || (np != g_st.di_pid)
                        || (n3i != g_st.rpt03_id) || (n3l != g_st.rpt03_len)
                        || (n1i != g_st.rpt01_id) || (n1l != g_st.rpt01_len);
                g_st.di_vid = nv; g_st.di_pid = np;
                g_st.rpt03_id = n3i; g_st.rpt03_len = n3l;
                g_st.rpt01_id = n1i; g_st.rpt01_len = n1l;
                if (changed) {
                    /* invalidar el cache de dedup y reconstruir el neutro del modo */
                    mark_sent_reset();
                    set_neutral_reports();
                }
                if (g_st.active && changed) {
                    /* re-registrar con la identidad nueva */
                    sdp_cleanup_handles(&g_st);
                    if (sdp_register_all(&g_st) == 0) {
                        reassert_gamepad_identity(&g_st);
                        sdp_state_save(g_statepath, nv, np);
                    }
                    xpd_log("INFO", "MODE %s: DI %04x:%04x re-registered", arg, nv, np);
                } else {
                    xpd_log("INFO", "MODE %s: DI %04x:%04x (applies to next registration)", arg, nv, np);
                }
                reply_client("OK MODE\n");
            } else if (strncmp(buf, "SCAN", 4) == 0) {
                const char *arg = buf + 4;
                while (*arg == ' ' || *arg == '\t') arg++;
                if (g_st.bt_fd < 0) {
                    reply_client("ERR SCAN nohci\n");
                } else if (strcmp(arg, "?") == 0 || arg[0] == '\0') {
                    uint8_t se = 0;
                    if (hci_read_scan_enable(&g_st, &se) == 0) {
                        char line[24];
                        snprintf(line, sizeof(line), "OK SCAN %u\n", (unsigned)se);
                        reply_client(line);
                        xpd_log("INFO", "scan enable=%u", se);
                    } else {
                        reply_client("ERR SCAN read\n");
                    }
                } else if (strcmp(arg, "1") == 0) {
                    if (hci_write_scan_enable(&g_st, 0x03) == 0) {
                        reassert_gamepad_identity(&g_st);
                        xpd_log("INFO", "scan=visible; CoD/EIR re-asserted");
                        reply_client("OK SCAN 1\n");
                    } else {
                        reply_client("ERR SCAN 1\n");
                    }
                } else if (strcmp(arg, "0") == 0) {
                    if (hci_write_scan_enable(&g_st, 0x02) == 0) {
                        reassert_gamepad_identity(&g_st);
                        xpd_log("INFO", "scan=hidden; CoD/EIR re-asserted");
                        reply_client("OK SCAN 0\n");
                    } else {
                        reply_client("ERR SCAN 0\n");
                    }
                } else {
                    reply_client("ERR SCAN arg\n");
                }
            } else if (strncmp(buf, "REASSERT", 8) == 0) {
                if (g_st.bt_fd < 0) {
                    reply_client("ERR REASSERT nohci\n");
                } else {
                    reassert_gamepad_identity(&g_st);
                    xpd_log("INFO", "CoD/EIR re-asserted on REASSERT");
                    reply_client("OK REASSERT\n");
                }
            } else if (strncmp(buf, "LINKRESET", 9) == 0) {
                int n = drop_all_acl(&g_st);
                if (n < 0) {
                    reply_client("ERR LINKRESET\n");
                } else {
                    xpd_log("INFO", "LINKRESET: dropped %d ACL link(s)", n);
                    reply_client("OK LINKRESET\n");
                }
            } else if (strncmp(buf, "PING", 4) == 0) {
                reply_client("OK PING\n");
            } else {
                reply_client("ERR unknown\n");
            }
                    start = i + 1;
                }
            }
            if (start > 0) {
                int rem = g_st.client_buf_len - start;
                if (rem > 0) memmove(g_st.client_buf, g_st.client_buf + start, rem);
                g_st.client_buf_len = rem;
                g_st.client_buf[g_st.client_buf_len] = '\0';
            }
        }

        int ctrl_l = (g_st.l2cap_ctrl_listen >= 0 && FD_ISSET(g_st.l2cap_ctrl_listen, &rfds));
        int intr_l = (g_st.l2cap_intr_listen >= 0 && FD_ISSET(g_st.l2cap_intr_listen, &rfds));
        int ctrl_c = (g_st.l2cap_ctrl >= 0 && FD_ISSET(g_st.l2cap_ctrl, &rfds));
        int intr_c = (g_st.l2cap_intr >= 0 && FD_ISSET(g_st.l2cap_intr, &rfds));
        if (ctrl_l || intr_l || ctrl_c || intr_c) {
            int had_intr = g_st.l2cap_intr;
            hidp_handle_fds(&g_st, ctrl_l, intr_l, ctrl_c, intr_c);
            if (had_intr < 0 && g_st.l2cap_intr >= 0 && g_st.armed) {
                /* interrupt recién conectado: forzar reenvío de estado actual */
                g_st.keepalive_ticks = 0;
                mark_sent_reset();
                send_reports_if_armed();
            }
        }
    }

    if (g_st.running) xpd_cleanup(&g_st);
    xpd_log("INFO", "xpd stopped");
    xpd_log_close();
    return 0;
}
