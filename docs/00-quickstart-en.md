<!-- Copyright (C) 2026 Jalm24
SPDX-License-Identifier: GPL-3.0-or-later
Additional GPLv3 §7(b): preserve this attribution in modified copies. -->

# 00 — Quick start (EN)

## Stack requirements (not version requirements)

The app does not require a specific ROM, kernel or Android version. It needs:

- **Root** (`su`) for raw HCI/L2CAP sockets and binding PSM < 0x1000.
- **System D-Bus** at `/dev/socket/dbus` with `org.bluez`, and an accessible **SDP** server at `/dev/socket/bluetooth`.
- **BT-Patch applied**: `DisablePlugins = input` in `/system/etc/bluetooth/main.conf`. Do not patch the framework or kernel. Procedure and backups: [`08-experimentos.md`](08-experimentos.md) (P2, in Spanish).
- **Free L2CAP** on PSM `0x11`/`0x13` and Android input events with `SOURCE_TOUCHPAD` (`0x100008`).
- Signed APK (`XperiaPad-release.apk`) installed with `adb install -r`; BlueZ host (Linux PC).

Validated reference: RetroActive `4.0.2.A.0.62 FULL` (Android 2.3.4, kernel 2.6.32.9-perf, BCM4329, BlueZ 4.69). If your stack differs, the app detects it and warns **“may fail horribly”**, but **blocks nothing**: you can still try. A Switch needs MissionControl; see the note below.

## Steps

1. Enable Bluetooth on the phone and apply BT-Patch before opening the app.
2. Open **XperiaPad**. If the daemon is `OFF`, toggle it to `ON`. Do not press “START daemon”.
3. From the PC, pair and connect (`bluetoothctl connect 8C:64:22:9B:17:15`).
4. Press **Connect** in the app and keep the game screen in focus so reports are sent (`ARM`).
5. Check the host: `ls -l /dev/input/js0` and `jstest --normal /dev/input/js0` (or a Gamepad API browser).

If the PC connects but `js0` never appears, press **Reconnect gamepad** in the home screen and connect again from the host. Daemon log: `/data/data/com.jalm.xperiapad/files/xpd.log`.

## Known limits

- The daemon needs **root** and raw HCI/L2CAP sockets; antivirus tools may flag it.
- No `INTERNET` permission. It stops after **120 s without a real client** and restarts on boot/BT-on.
- CoD/EIR/SDP may be stale after forced closes; use **Reconnect gamepad** or toggle Bluetooth.
- Daemon control is protected by an Android `signature` permission.
- **Switch/MissionControl is experimental**: DS3 identity `054c:0268`, implemented but **not validated on real hardware**. After changing protocol, forget the host and pair again.

More detail: [`README.md`](README.md), [`03-bluetooth-y-sdp.md`](03-bluetooth-y-sdp.md), [`10-auditoria-y-checklist.md`](10-auditoria-y-checklist.md).
