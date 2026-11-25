/* Copyright (C) 2026 Jalm24
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Additional GPLv3 §7(b) term: this author attribution must be
 * preserved in modified source files and in the project's legal notices. */

/* trusted.c — auto-trust de hosts emparejados (E27).
 *
 * El gate de autorizacion HID de esta ROM (E17) lo dispara la capa de
 * seguridad de BlueZ al llegar una conexion entrante a un PSM registrado
 * como HID, si el dispositivo no esta marcado "trusted" en el almacen
 * de BlueZ 4. Con trusted=true, BlueZ auto-autoriza todos los servicios
 * del peer sin llamar al agente (que rechaza HID), y la conexion llega
 * a nuestros listeners L2CAP (verificado end-to-end en E27).
 *
 * Almacenamiento de BlueZ en esta ROM: flat por adapter en
 * /data/misc/bluetoothd/<mac_adapter>/ con archivos textfile claveados
 * por MAC (linkkeys, profiles, names, ...). El flag trusted vive en el
 * archivo "trusted" (lineas "MAC true|false").
 *
 * El flag solo se carga en memoria cuando bluetoothd arranca, por lo que
 * tras agregar entradas hay que reiniciar bluetoothd (el framework lo
 * respawnea solo) y re-registrar nuestro SDP. Esa parte la maneja
 * trusted_watch() en xpd.c.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include "trusted.h"
#include "log.h"

#define BT_STORAGE "/data/misc/bluetoothd"
#define BT_UID 1002   /* uid de bluetooth en esta ROM */
#define BT_GID 1002

/* Encuentra el directorio de almacenamiento del adapter (unico en este
 * dispositivo). Devuelve 0 y copia la ruta en out, -1 si no hay. */
static int find_adapter_dir(char *out, size_t outlen) {
    DIR *d = opendir(BT_STORAGE);
    if (!d) return -1;
    struct dirent *e;
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        if (strlen(e->d_name) != 17) continue; /* solo MACs */
        snprintf(out, outlen, "%s/%s", BT_STORAGE, e->d_name);
        found = 1;
        break;
    }
    closedir(d);
    return found ? 0 : -1;
}

/* 1 si la MAC ya tiene una linea en path (true o false: se respeta la
 * decision del usuario de desmarcar un host en Ajustes). */
static int mac_in_file(const char *path, const char *mac) {
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[256];
    int found = 0;
    while (fgets(line, sizeof(line), f)) {
        char t[32];
        if (sscanf(line, "%31s", t) == 1 && strcmp(t, mac) == 0) { found = 1; break; }
    }
    fclose(f);
    return found;
}

/* Marca como trusted cada MAC enlazada (linkkeys) que no figure en el
 * archivo trusted. Devuelve cuantas agrego (-1 si no hay almacen o
 * bondings); *written = 1 si toco el archivo. */
int trusted_sweep(int *written) {
    if (written) *written = 0;
    char dir[128], lkpath[192], trpath[192];
    if (find_adapter_dir(dir, sizeof(dir)) != 0) return -1;
    snprintf(lkpath, sizeof(lkpath), "%s/linkkeys", dir);
    snprintf(trpath, sizeof(trpath), "%s/trusted", dir);

    FILE *f = fopen(lkpath, "r");
    if (!f) return -1; /* sin bondings todavia */
    char line[512];
    int added = 0;
    while (fgets(line, sizeof(line), f)) {
        char mac[32];
        if (sscanf(line, "%31s", mac) != 1) continue;
        if (strlen(mac) != 17) continue;
        if (mac_in_file(trpath, mac)) continue;
        FILE *tf = fopen(trpath, "a");
        if (!tf) { fclose(f); return added; }
        fprintf(tf, "%s true\n", mac);
        fclose(tf);
        added++;
        xpd_log("INFO", "trusted: %s marked", mac);
    }
    fclose(f);
    if (added > 0) {
        chown(trpath, BT_UID, BT_GID);
        chmod(trpath, 0644);
        if (written) *written = 1;
    }
    return added;
}
