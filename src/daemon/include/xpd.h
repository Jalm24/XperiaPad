#ifndef XPD_H
#define XPD_H

#include <stdint.h>
#include <stddef.h>

#define XPD_SOCKET_NAME "xperiapad"
#define XPD_CTL_SOCKET_NAME "xperiapadctl" /* sondeos: no desplazan al cliente principal */
#define XPD_LOG_PATH_DEFAULT "xpd.log"

#define COD_GAMEPAD 0x000508u
#define COD_PHONE_DEFAULT 0x58020cu /* observado en el dispositivo; se sobreescribe con el valor real al iniciar */

#define SDP_HANDLE_HID  0x20000u
#define SDP_HANDLE_DI   0x20001u
#define SDP_HANDLE_SYS_DI 0x10000u

typedef struct {
    int running;
    int active;         /* modo gamepad activo (SDP+HIDP+CoD/EIR aplicados) */
    int armed;          /* app en foco: emision habilitada */
    int bt_fd;          /* HCI socket */
    int uds_fd;         /* listening socket principal */
    int ctl_fd;         /* listening socket de sondeo (PING/STATE/SCAN ?) */
    int ctl_client_fd;  /* conexion de sondeo, se cierra tras responder */
    char ctl_buf[256];
    int ctl_buf_len;
    int client_fd;      /* app connection */
    char client_buf[1024];
    int client_buf_len;
    int had_client;         /* hubo cliente principal alguna vez */
    long long last_client_ms; /* cuando se perdio el cliente (gracia 120s) */
    int l2cap_ctrl_listen;
    int l2cap_intr_listen;
    int l2cap_ctrl;
    int l2cap_intr;
    uint32_t original_cod;
    int have_original_cod;
    int assert_ticks;   /* contador para reafirmación periódica */
    int keepalive_ticks;
    uint8_t last_report_03[64];
    size_t last_report_03_len;
    uint8_t last_report_01[64];
    size_t last_report_01_len;
    int hci_dbg;        /* contador para log de eventos HCI (debug) */
    int sdp_rereg_pending;  /* bluetoothd respawned: re-registrar SDP/CoD/EIR */
    long long sdp_rereg_at; /* ms absolutos del proximo intento */
    long long first_sweep_at; /* gracia inicial antes del primer sweep trusted */
    uint16_t di_vid;    /* VID del registro SDP Device ID (perfil activo) */
    uint16_t di_pid;    /* PID idem */
    uint8_t rpt03_id;   /* report id del report principal (0x03/0x07/0x01) */
    uint8_t rpt03_len;  /* tamano de data del report principal */
    uint8_t rpt01_id;   /* report id secundario (0x01/0x02; 0 = sin report) */
    uint8_t rpt01_len;  /* tamano de data secundaria (0 = sin report) */
    int reassert_ticks; /* contador para reafirmacion CoD/EIR del modo gamepad */
} xpd_state_t;

#endif
