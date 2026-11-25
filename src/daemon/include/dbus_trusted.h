#ifndef XPD_DBUS_TRUSTED_H
#define XPD_DBUS_TRUSTED_H

/* Aplica Trusted=true por D-Bus a todos los hosts marcados en el archivo
 * "trusted" de BlueZ. Evita reiniciar bluetoothd (el framework interpreta
 * el killall como crash y apaga/enciende el adaptador).
 * Devuelve 0 si al menos un host se aplico y ninguno fallo; -1 si no. */
int dbus_trust_all(void);

#endif
