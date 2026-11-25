<!-- Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later
Additional GPLv3 §7(b): preserve this attribution in modified copies. -->

# 00 — Inicio rápido (ES)

## Requisitos del stack (no de versión)

La app no exige una ROM, un kernel ni una versión de Android concretas. Necesita:

- **Root** (`su`) para sockets HCI/L2CAP y bind en PSM < 0x1000.
- **D-Bus del sistema** en `/dev/socket/dbus` con `org.bluez`, y **SDP** accesible en `/dev/socket/bluetooth`.
- **BT-Patch aplicado**: `DisablePlugins = input` en `/system/etc/bluetooth/main.conf`. No parchear el framework ni el kernel. Procedimiento y backups: [`08-experimentos.md`](08-experimentos.md) (P2).
- **L2CAP libre** en PSM `0x11`/`0x13` y eventos Android con `SOURCE_TOUCHPAD` (`0x100008`).
- APK firmado (`XperiaPad-release.apk`) e instalado con `adb install -r`; host con BlueZ (PC Linux).

Configuración de referencia: RetroActive `4.0.2.A.0.62 FULL` (Android 2.3.4, kernel 2.6.32.9-perf, BCM4329, BlueZ 4.69). Si tu stack difiere, la app lo detecta y avisa **“puede fallar horriblemente”**, pero **no bloquea** nada: puedes intentarlo igual. Para Switch necesitas MissionControl; ver aviso abajo.

## Pasos

1. Enciende Bluetooth en el teléfono y aplica BT-Patch antes de abrir la app.
2. Abre **XperiaPad**. Si el daemon está `OFF`, cámbialo a `ON` (toggle). No hace falta pulsar “START daemon”.
3. Desde el PC, empareja y conecta el dispositivo (`bluetoothctl connect 8C:64:22:9B:17:15`).
4. En la app pulsa **Conectar** y mantén la pantalla de juego en foco para enviar reportes (`ARM`).
5. Verifica en el PC: `ls -l /dev/input/js0` y `jstest --normal /dev/input/js0` (o un navegador con Gamepad API).

Si el PC queda conectado pero no aparece `js0`, pulsa **Reconectar gamepad** en la pantalla principal y vuelve a conectar desde el host. El log real del daemon está en `/data/data/com.jalm.xperiapad/files/xpd.log`.

## Límites conocidos

- El daemon necesita **root** y sockets HCI/L2CAP crudos; los antivirus pueden alertar.
- Sin permiso `INTERNET`. Se apaga solo tras **120 s sin cliente real**; vuelve a arrancar al encender BT o al iniciar el sistema.
- CoD/EIR/SDP pueden quedar inconsistentes tras cierres forzados; usa **Reconectar gamepad** o reinicia Bluetooth.
- El control del daemon está protegido por permiso Android `signature`.
- **Switch/MissionControl es experimental**: identidad DS3 `054c:0268`, implementada pero **no validada en consola real**. Si cambias de protocolo, borra el host y vuelve a emparejar.

Más detalle: [`README.md`](README.md), [`03-bluetooth-y-sdp.md`](03-bluetooth-y-sdp.md), [`10-auditoria-y-checklist.md`](10-auditoria-y-checklist.md).
