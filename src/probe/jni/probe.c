/* sonda de permisos sin root: comprueba si el GID bluetooth (1002) basta
 * para los4 canales que necesita el emulador HID. No escribe nada en el
 * sistema: cada socket se cierra en el acto. */
#include <jni.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <grp.h>
#include <fcntl.h>
#include <sys/select.h>
#include <stdarg.h>
#include <stdio.h>

#define AF_BLUETOOTH_LOCAL 31
#define BTPROTO_L2CAP_LOCAL 0
#define BTPROTO_HCI_LOCAL 1
#define SOCK_SEQPACKET_LOCAL 5   /* linux sockaddr constants (ARM EABI) */

/* sockaddr_l2 del kernel 2.6.32: family(2) psm(2) bdaddr(6) cid(2) */
typedef struct {
    unsigned short l2_family;
    unsigned short l2_psm;
    unsigned char l2_bdaddr[6];
    unsigned short l2_cid;
} sockaddr_l2_t;

typedef struct {
    unsigned short hci_family;
    unsigned short hci_dev;
    unsigned short hci_channel;
} sockaddr_hci_t;

static void add(char *buf, size_t cap, const char *fmt, ...) {
    size_t n = strlen(buf);
    if (n + 2 >= cap) return;
    char tmp[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    strncat(buf + n, tmp, cap - n - 2);
    strcat(buf, "\n");
}

JNIEXPORT jstring JNICALL
Java_com_jalm_xperiapad_probe_ProbeActivity_runProbe(JNIEnv *env, jclass cls) {
    (void)cls;
    char buf[4096];
    buf[0] = '\0';

    /* 1) GIDs del proceso */
    gid_t gids[64];
    int ng = getgroups(64, gids);
    int has_bt = 0;
    for (int i = 0; i < ng; i++) if (gids[i] == 1002) has_bt = 1;
    add(buf, sizeof(buf), "1) GIDs: %s  [uid=%d, ngids=%d]",
            has_bt ? "1002 presente" : "1002 ausente (NOTA: net_bt=3002, net_bt_admin=3001 son los GIDs de permiso)",
            (int)getuid(), ng);
    for (int i = 0; i < ng && i < 16; i++) add(buf, sizeof(buf), "1)   gid[%d]=%d", i, (int)gids[i]);

    /* 2) SDP local: /dev/socket/bluetooth */
    {
        int s = socket(PF_UNIX, SOCK_STREAM, 0);
        if (s < 0) add(buf, sizeof(buf), "2) SDP /dev/socket/bluetooth: socket ERR errno=%d", errno);
        else {
            struct sockaddr_un a;
            memset(&a, 0, sizeof(a));
            a.sun_family = AF_UNIX;
            strcpy(a.sun_path, "/dev/socket/bluetooth");
            int rc = connect(s, (struct sockaddr *)&a, sizeof(a));
            add(buf, sizeof(buf), "2) SDP /dev/socket/bluetooth: %s (errno=%d)",
                    rc == 0 ? "CONECTADO (acceso OK)" : "FALLO", rc == 0 ? 0 : errno);
            close(s);
        }
    }

    /* 3) PSM L2CAP 0x11/0x13: bind(2) + close inmediato */
    for (int psm = 0x11; psm <= 0x13; psm += 2) {
        int s = socket(AF_BLUETOOTH_LOCAL, SOCK_SEQPACKET_LOCAL, BTPROTO_L2CAP_LOCAL);
        if (s < 0) { add(buf, sizeof(buf), "3) L2CAP PSM 0x%02x: socket ERR errno=%d", psm, errno); continue; }
        sockaddr_l2_t a;
        memset(&a, 0, sizeof(a));
        a.l2_family = AF_BLUETOOTH_LOCAL;
        a.l2_psm = (unsigned short)(psm << 8 | psm >> 8); /* htobs en ARM LE */
        int rc = bind(s, (struct sockaddr *)&a, sizeof(a));
        int e = errno;
        add(buf, sizeof(buf), "3) L2CAP PSM 0x%02x: %s (errno=%d%s)", psm,
                rc == 0 ? "BIND OK (sin root!)" : "FALLO", rc == 0 ? 0 : e,
                rc == 0 ? "" : (e == 13 ? " EACCES" : (e == 98 ? " EADDRINUSE" : "")));
        close(s);
    }

    /* 4) HCI raw hci0: open + bind (solo lectura de permisos, sin comandos) */
    {
        int s = socket(AF_BLUETOOTH_LOCAL, SOCK_RAW, BTPROTO_HCI_LOCAL);
        if (s < 0) add(buf, sizeof(buf), "4) HCI raw: socket ERR errno=%d", errno);
        else {
            sockaddr_hci_t a;
            memset(&a, 0, sizeof(a));
            a.hci_family = AF_BLUETOOTH_LOCAL;
            a.hci_dev = 0; a.hci_channel = 0;
            int rc = bind(s, (struct sockaddr *)&a, sizeof(a));
            add(buf, sizeof(buf), "4) HCI raw hci0: %s (errno=%d)",
                    rc == 0 ? "BIND OK (sin root!)" : "FALLO", rc == 0 ? 0 : errno);
            close(s);
        }
    }

    /* 5) D-Bus sistema: /dev/socket/dbus (660 bluetooth, politica allow *) */
    {
        int s = socket(PF_UNIX, SOCK_STREAM, 0);
        if (s < 0) add(buf, sizeof(buf), "5) DBus /dev/socket/dbus: socket ERR errno=%d", errno);
        else {
            struct sockaddr_un a;
            memset(&a, 0, sizeof(a));
            a.sun_family = AF_UNIX;
            strcpy(a.sun_path, "/dev/socket/dbus");
            int rc = connect(s, (struct sockaddr *)&a, sizeof(a));
            add(buf, sizeof(buf), "5) DBus /dev/socket/dbus: %s (errno=%d)",
                    rc == 0 ? "CONECTADO (acceso OK)" : "FALLO", rc == 0 ? 0 : errno);
            close(s);
        }
    }

    /* 6) SDP sobre L2CAP local (PSM 1), conexion no bloqueante completada */
    {
        int s = socket(AF_BLUETOOTH_LOCAL, SOCK_SEQPACKET_LOCAL, BTPROTO_L2CAP_LOCAL);
        if (s < 0) { add(buf, sizeof(buf), "6) SDP-L2CAP: socket ERR errno=%d", errno); }
        else {
            sockaddr_l2_t a;
            memset(&a, 0, sizeof(a));
            a.l2_family = AF_BLUETOOTH_LOCAL;
            a.l2_psm = 0;
            int rc = bind(s, (struct sockaddr *)&a, sizeof(a));
            memset(&a, 0, sizeof(a));
            a.l2_family = AF_BLUETOOTH_LOCAL;
            a.l2_psm = (unsigned short)(1 << 8);
            if (rc == 0) {
                /* 3 direcciones: BDADDR_LOCAL(00:..:ff:ff:ff), BDADDR_ANY y la MAC propia */
                static const unsigned char addrs[3][6] = {
                    {0,0,0,0,0xff,0xff}, {0,0,0,0,0,0}, {0x8c,0x64,0x22,0x9b,0x17,0x15}
                };
                const char *names[3] = {"BDADDR_LOCAL", "ANY", "propia 8C:64:..."};
                for (int k = 0; k < 3; k++) {
                    memcpy(a.l2_bdaddr, addrs[k], 6);
                    int fl = fcntl(s, F_GETFL, 0);
                    fcntl(s, F_SETFL, fl | O_NONBLOCK);
                    rc = connect(s, (struct sockaddr *)&a, sizeof(a));
                    int conn_errno = errno;
                    if (rc < 0 && conn_errno == EINPROGRESS) {
                        fd_set w; FD_ZERO(&w); FD_SET(s, &w);
                        struct timeval tv; tv.tv_sec = 1; tv.tv_usec = 500000;
                        int sr = select(s + 1, NULL, &w, NULL, &tv);
                        if (sr > 0) {
                            int soerr = 0; socklen_t sl = sizeof(soerr);
                            getsockopt(s, SOL_SOCKET, SO_ERROR, &soerr, &sl);
                            rc = (soerr == 0) ? 0 : -1;
                            conn_errno = soerr;
                        } else { conn_errno = (sr == 0) ? 110 : errno; }
                    }
                    add(buf, sizeof(buf), "6) SDP-L2CAP PSM1 -> %s: %s (errno=%d)",
                            names[k], rc == 0 ? "CONECTADO (sin root POSIBLE!)" : "FALLO",
                            rc == 0 ? 0 : conn_errno);
                    /* reconectar: nuevo socket para cada intento */
                    if (k < 2) {
                        close(s);
                        s = socket(AF_BLUETOOTH_LOCAL, SOCK_SEQPACKET_LOCAL, BTPROTO_L2CAP_LOCAL);
                        memset(&a, 0, sizeof(a));
                        a.l2_family = AF_BLUETOOTH_LOCAL;
                        a.l2_psm = 0;
                        bind(s, (struct sockaddr *)&a, sizeof(a));
                        a.l2_family = AF_BLUETOOTH_LOCAL;
                        a.l2_psm = (unsigned short)(1 << 8);
                    }
                }
            } else {
                add(buf, sizeof(buf), "6) SDP-L2CAP bind local FALLO errno=%d", errno);
            }
            close(s);
        }
    }

    /* 8) HCI write-permission: enviar Read_Class_of_Device (0x0c23), solo lectura.
     * Si write() funciona, el proceso puede enviar comandos HCI (CoD/EIR) sin root. */
    {
        int s = socket(AF_BLUETOOTH_LOCAL, SOCK_RAW, BTPROTO_HCI_LOCAL);
        if (s < 0) add(buf, sizeof(buf), "8) HCI cmd (Read CoD): socket ERR errno=%d", errno);
        else {
            sockaddr_hci_t a;
            memset(&a, 0, sizeof(a));
            a.hci_family = AF_BLUETOOTH_LOCAL;
            a.hci_dev = 0; a.hci_channel = 0;
            int rc = bind(s, (struct sockaddr *)&a, sizeof(a));
            if (rc == 0) {
                unsigned char cmd[4] = {0x01, 0x23, 0x0c, 0x00}; /* HCI command packet, OGF3 OCF0x23 */
                ssize_t n = write(s, cmd, sizeof(cmd));
                add(buf, sizeof(buf), "8) HCI cmd Read CoD (sin cambiar nada): %s (n=%d errno=%d)",
                        n == 4 ? "ENVIADO (HCI write OK sin root!)" : "FALLO",
                        (int)n, n == 4 ? 0 : errno);
            } else add(buf, sizeof(buf), "8) HCI bind FALLO errno=%d", errno);
            close(s);
        }
    }

    return (*env)->NewStringUTF(env, buf);
}
